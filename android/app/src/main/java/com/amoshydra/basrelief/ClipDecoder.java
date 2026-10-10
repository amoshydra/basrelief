package com.amoshydra.basrelief;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One video track, decoded to a {@link Surface}, and held at the shared clock.
 *
 * <p><b>The synchronisation mechanism is a gate on RELEASE, not a correction
 * afterwards.</b> The output buffer is held across the wait, so the codec physically
 * cannot run ahead of the clock. A gate after the release cannot work: the frame is
 * already in the surface's queue, and a queue nobody can index by time is exactly the
 * constant skew this class exists to remove.
 *
 * <p><b>Everything here was measured, and two of the measurements are why the code
 * looks the way it does.</b> Both were surprises that looked plausible, so both are
 * recorded rather than tidied away:
 *
 * <ul>
 *   <li><b>The gate is on ABSOLUTE time.</b> Gating on the raw {@code pts} looks
 *       right and stops working at the first loop, because the extractor restarts
 *       {@code pts} at 0 every cycle while the shared clock is absolute and only
 *       grows. From the second loop on, {@code pts > now + lead} is never true and the
 *       gate never blocks. Both decoders then free-run at their own decode rates —
 *       measured 82 frames/s and 17 loops against 15 — and drift 7.5 s apart over
 *       20 s. Hence {@code absPts = pts + loops * clipUs}.
 *   <li><b>The bound is in TIME, not in frames.</b> One frame of colour costs 12 ms
 *       of decode and one of depth nothing at all, so a shared two-frame bound let
 *       depth run to end-of-file and loop <b>six times</b> while colour looped once,
 *       leaving the two 3.6 s apart. Hence {@link #LEAD_US}.
 * </ul>
 *
 * <p><b>Every decoder must be given the SAME lead value.</b> The renderer releases up
 * to {@code clock + lead} and latches the newest frame, so two different leads are two
 * different frame indices by construction. Measured with 400 ms against 120 ms: a
 * steady 280 ms of skew that looked exactly like a sync bug and was a policy
 * difference.
 *
 * <p><b>Looping rewinds both streams together</b> and there is nothing to mix across
 * the seam, because no timestamp now comes from a queue — the gate keeps each decoder
 * on the same frame index.
 *
 * <p><b>A decoder that cannot keep up is bounded by a seek, not left behind.</b>
 * The gate pins a decoder that is FAST — a frame is held until its absolute time is
 * due — but a stream whose codec cannot decode at the clip's rate releases frames
 * that are already overdue, and the deficit accumulates. Measured on a 50 fps import:
 * all three 1080p streams ran at ~39-40 fps, each fell behind by its own deficit,
 * and the relief was 0.4-1.0 s from the picture at the loop. {@link #catchUpIfLate}
 * is the fail-soft bound: when a released frame is far enough behind the clock and
 * enough time has passed since the last seek, the decoder jumps to the frame that is
 * due now. It is deliberately hard to trigger while a stream keeps up — see the
 * constants for the measured numbers the threshold sits between.
 *
 * <p><b>This is not a {@link Thread} of its own making.</b> It starts one, and
 * {@link #stop} joins it, because a decoder thread outliving the surface it feeds
 * takes the renderer's texture with it.
 */
final class ClipDecoder {

    private static final String TAG = "Basrelief";

    /**
     * How far ahead of the clock a frame may be released.
     *
     * <p>The lead is a head start, not a deadline: it exists because a frame decoded
     * now is not on screen now. Measured at 120 ms with both streams gated identically,
     * giving {@code skew=40000us} — one frame interval — and {@code loops=4/4} after
     * four cycles.
     */
    private static final long LEAD_US = 120_000L;

    /**
     * How far behind the clock a released frame may be before the decoder seeks
     * forward to the frame that is due now.
     *
     * <p><b>The fail-soft bound for an overloaded decoder, chosen to be unreachable
     * while one keeps up.</b> The gate releases a frame the moment its absolute time
     * is due, so a healthy decoder hands a frame over within a scheduling delay of
     * {@code clock + lead}: the measured steady-state skew between two gated streams
     * is one frame interval (40 ms in the recorded run) and the gate's own lead is
     * 120 ms. A decoder that cannot keep up is a different shape: it releases frames
     * whose pts is already past, and the deficit accumulates — measured on a 50 fps
     * source, three 1080p streams each ran at ~39-40 fps and the depth drifted
     * 0.4-1.0 s from the colour. The threshold sits well above healthy jitter and
     * below the measured failure.
     */
    private static final long CATCH_UP_LAG_US = 300_000L;

    /**
     * The least time between two catch-ups.
     *
     * <p>A seek decodes from the sync sample before its target, so firing it
     * continuously would turn a slightly slow stream into one that is always
     * jumping. The magnitude is set by the measured deficit: at ~39 fps against a
     * 50 fps clip the lag grows ~220 ms/s, so after a catch-up it takes over a
     * second to cross the threshold again. With the threshold and this cooldown the
     * lag stays under roughly half a second instead of growing without bound.
     */
    private static final long CATCH_UP_COOLDOWN_US = 1_000_000L;

    /** How long either dequeue may block, in ms. Short, because the gate polls this. */
    private static final int POLL_MS = 5;

    /** The shared absolute clock. Microseconds, monotonic, growing for ever. */
    interface Clock {
        long nowUs();
    }

    private final String path;
    private final String tag;

    /** The track's own duration, so a loop can be placed on the absolute clock. */
    private final long clipUs;

    private volatile Clock clock;
    private volatile long leadUs = LEAD_US;

    /**
     * Playback rate: 1 is normal, 2 is twice as fast. The clip's own timeline is
     * untouched -- the rate only changes WHEN a frame's turn comes.
     *
     * <p><b>Applied at the gate, and that is the whole mechanism.</b> The gate
     * compares a frame's clip time against the wall clock, so dividing that
     * comparison by the rate makes the clip play at the rate: at 0.5x every frame
     * waits twice as long, and NOTHING is skipped or repeated. Scaling the clock
     * instead would work too and would be a second clock to keep in step with the
     * one the two decoders already share; dropping frames would lose the clip's
     * content rather than its speed.
     *
     * <p>Both decoders of a clip must be given the same rate, exactly as they are
     * given the same lead: two rates are two frame indices by construction.
     */
    private volatile float rate = 1f;

    void setRate(float r) {
        this.rate = r <= 0f ? 1f : r;
    }

    private volatile boolean loop = true;
    private volatile boolean running;
    private volatile boolean waiting;

    /**
     * The clip time this decoder should join at, or -1 to start from the beginning.
     *
     * <p>Set by {@link ClipPlayer#growTo} when a decoder is created for a clip that
     * is ALREADY playing: the depth track arrives minutes into a run, and decoding
     * it from 0 would walk the whole clip before reaching the frame the colour is
     * showing. See {@link #startAt}.
     */
    private volatile long startAtUs = -1L;

    /**
     * Join the clip at the time the shared clock has already reached.
     *
     * <p><b>The gate's arithmetic is the whole reason this works.</b> A frame's
     * absolute time is {@code file pts + basePtsUs} and the release test is
     * {@code absPts <= (now + lead) * rate} — so the on-screen clip time at wall
     * {@code now} is {@code now * rate}. The seek target is exactly that, taken
     * modulo the clip's own duration with the whole loops moved into
     * {@code basePtsUs}, which is the same term the loop rewind accumulates. No
     * per-start offset is added when samples are queued: the file's own pts IS the
     * timeline, whole loops included.
     *
     * <p>The seek lands on the nearest SYNC sample at or before the target, so the
     * frames between it and the target are released immediately — that is the gate's
     * normal behaviour for frames whose time has come, and it costs one GOP of
     * catch-up rather than a walk from zero.
     *
     * <p>No {@code awaitingKeyframe} guard here: a decoder that has never decoded
     * anything starts clean at the sync sample, and the guard exists for a REWIND,
     * where the codec continues from references it no longer has.
     */
    void startAt(long elapsedUs) {
        this.startAtUs = elapsedUs;
    }

    /**
     * Set on a rewind, cleared by the first KEY frame.
     *
     * <p>The seam showed a macroblock MOSAIC -- black with scattered coloured
     * tiles -- which is what a decoder emits for a frame whose references it does
     * not have. The rewind lands on the nearest sync sample, and that is not
     * guaranteed to be an IDR: with an open GOP, or a sync sample that is a plain
     * I frame, everything until the next key frame references pictures from before
     * the seek. Releasing those renders the mosaic; DROPPING them renders nothing,
     * which is a held frame for a few frames instead of garbage. The flag is what
     * makes the seam correct for any encoder's GOP, rather than for the one we
     * happened to measure.
     */
    private volatile boolean awaitingKeyframe;

    /**
     * How many frames may be dropped waiting for a key frame before giving up.
     *
     * <p>The flag is not guaranteed: measured on the Pixel, after a rewind this
     * decoder never set {@code BUFFER_FLAG_KEY_FRAME} at all, so an unbounded wait
     * dropped EVERY frame for ever -- produced frozen at 115/114/114, latch/s 0,
     * draws/s 61, loops stuck: the clip froze instead of mosaicking. A bounded drop
     * is the honest shape: prefer a possible one-frame artifact to a permanent
     * freeze, and let the frame through when the flag does not come.
     */
    private int keyframeDropBudget;

    private volatile String rangeName = "unspecified->assume studio";
    private volatile boolean fullRange = true;
    private volatile int trackW, trackH;
    private volatile long lastAbsPts = -1L;
    /**
     * The accumulated loop offset added to every input timestamp.
     *
     * <p>Timestamps are rewritten to be ABSOLUTE at the INPUT, so the codec's own
     * output pts stay monotonic across a loop and no flush is needed. See the loop
     * seam note in {@link #loop0}.
     */
    private long basePtsUs;
    /**
     * The shared clock's reading at the last catch-up: the cooldown's baseline.
     *
     * <p>Initialised a full cooldown in the past, so the FIRST frame that is far
     * enough behind may catch up immediately rather than waiting out a cooldown
     * that never started. Decoder thread only.
     */
    private long lastCatchUpUs = -CATCH_UP_COOLDOWN_US;
    private volatile int produced;
    private volatile int loops;
    private volatile String error;

    private Thread thread;
    private MediaCodec codec;
    private volatile Surface surface;

    ClipDecoder(String path, String tag, long clipUs) {
        this.path = path;
        this.tag = tag;
        this.clipUs = clipUs;
    }

    void setClock(Clock c) {
        this.clock = c;
    }

    /** @param us the SAME value for every decoder in the clip. See the class note. */
    void setLeadUs(long us) {
        this.leadUs = us;
    }

    void setLoop(boolean looping) {
        this.loop = looping;
    }

    /** Install the codec's output surface. GL thread; before {@link #start()}. */
    void setSurface(Surface s) {
        this.surface = s;
    }

    /**
     * The track's own colour range, which the shader's conversion has to match.
     *
     * <p>Read from the container rather than assumed. Getting this wrong is not a
     * subtle tint: a limited-range frame expanded as full is visibly washed out, and
     * the depth track's meaning changes with it, since a depth plane has no defined
     * range at all.
     */
    boolean fullRange() {
        return fullRange;
    }

    String rangeName() {
        return rangeName;
    }

    int trackW() {
        return trackW;
    }

    int trackH() {
        return trackH;
    }

    int produced() {
        return produced;
    }

    int loops() {
        return loops;
    }

    String error() {
        return error;
    }

    /**
     * Absolute presentation time of the frame currently on screen, in microseconds.
     *
     * <p><b>NOT {@code SurfaceTexture.getTimestamp()}.</b> That is in nanoseconds and
     * it restarts every loop, so the difference between the two streams' timestamps
     * read 280 ms at steady state (nanoseconds compared as microseconds) and 3.44 s
     * across a loop seam. Both numbers were meaningless and both looked plausible,
     * which is the worst combination a diagnostic can have.
     */
    long lastAbsPts() {
        return lastAbsPts;
    }

    /** One line of state: parked at the clock, or running free? */
    String state() {
        return (waiting ? "WAITING" : "running") + "@"
                + (clock == null ? "-" : String.valueOf(clock.nowUs()));
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop0();
            }
        }, "clip-decode-" + tag);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t == null) return;
        // Join rather than detach: a decoder thread that outlives the surface it
        // feeds will hand a frame to a SurfaceTexture nobody owns any more, and the
        // symptom is a black or frozen surface with no exception anywhere.
        synchronized (this) {
            notifyAll();
        }
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    /** Block until this frame's turn has come. */
    private void awaitSpace(long ptsUs) {
        while (running) {
            Clock c = clock;
            // The rate divides the comparison, not the timestamps: see setRate.
            boolean tooEarly = c != null && ptsUs > (c.nowUs() + leadUs) * rate;
            if (!tooEarly) {
                waiting = false;
                return;
            }
            waiting = true;
            synchronized (this) {
                try {
                    wait(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Jump to the frame that is due now, if this decoder has fallen too far behind.
     *
     * <p><b>The arithmetic mirrors the loop rewind, not the gate.</b> The target is
     * {@code (clock + lead) * rate} -- the same expression the gate releases
     * against, so the target is exactly "due now" -- and the seek lands on the
     * nearest sync sample at or before it. Whole loops move into {@code basePtsUs}
     * exactly as the rewind's do, and {@code loops} is set from the same division,
     * so the diagnostics and the next rewind stay on one clock. The codec is not
     * flushed, for the same reason the rewind does not flush: timestamps are made
     * absolute at the INPUT, so the codec's own output stays monotonic across the
     * jump. The frames between the sync sample and the target are released as their
     * turn comes, and the keyframe wait drops the stale ones.
     *
     * <p><b>What makes this cheap is the encoder's GOP.</b> The stream node pins its
     * GOP to one second, so a catch-up costs at most that much decode before the
     * frame that is due. The colour is the app's own remux, which carries the
     * source's GOP, so the bound there is one source GOP -- the honest limit.
     *
     * <p>Never fires while {@code awaitingKeyframe}: a jump already in flight must
     * not be jumped again on the stale frames it is dropping, and the cooldown is a
     * second guard on the same hazard.
     *
     * @return true when a seek was made, in which case the frame in hand is late
     *         and must not be shown
     */
    private boolean catchUpIfLate(MediaExtractor extractor, long absPtsUs) {
        Clock c = clock;
        if (c == null || awaitingKeyframe || rate <= 0f || clipUs <= 0) return false;
        long nowUs = c.nowUs();
        long targetUs = (long) ((nowUs + leadUs) * (double) rate);
        if (targetUs - absPtsUs <= CATCH_UP_LAG_US) return false;
        if (nowUs - lastCatchUpUs <= CATCH_UP_COOLDOWN_US) return false;
        lastCatchUpUs = nowUs;
        long jump = targetUs / clipUs;
        basePtsUs = jump * clipUs;
        loops = (int) jump;
        long seekUs = targetUs - basePtsUs;
        // seekTo even at 0: exactly on a loop boundary the stream must still rewind
        // to the start, which is what the loop path does.
        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        awaitingKeyframe = true;
        keyframeDropBudget = 8;
        android.util.Log.i(TAG, "clip " + tag + " catch-up: "
                + (targetUs - absPtsUs) / 1000 + "ms behind, seek to " + seekUs
                + "us (loop " + jump + ")");
        return true;
    }

    private void loop0() {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("no video track in " + path);
            if (surface == null) throw new IOException("no output surface for " + path);

            int range = getInt(format, "color-range");
            fullRange = (range == 1);
            rangeName = (range == 1 ? "pc/full" : range == 2 ? "tv/studio" : "unspecified->studio");
            trackW = getInt(format, MediaFormat.KEY_WIDTH);
            trackH = getInt(format, MediaFormat.KEY_HEIGHT);
            android.util.Log.i(TAG, "clip " + tag + " "
                    + format.getString(MediaFormat.KEY_MIME) + " " + trackW + "x" + trackH
                    + " range=" + rangeName + " clipUs=" + clipUs);

            extractor.selectTrack(track);
            // JOIN AT THE CLIP TIME THE CLOCK IS ALREADY AT, if this decoder was
            // seeded for a grow. See startAt: the target is the wall clock scaled by
            // the rate (which is what the gate releases against), taken modulo the
            // clip so whole loops land in basePtsUs exactly as the rewind's do.
            long seedUs = startAtUs;
            if (seedUs > 0) {
                long targetUs = (long) (seedUs * rate);
                long wholeLoops = clipUs > 0 ? targetUs / clipUs : 0;
                basePtsUs = wholeLoops * clipUs;
                long seekUs = targetUs - basePtsUs;
                if (seekUs > 0) {
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                }
                android.util.Log.i(TAG, "clip " + tag + " joins at " + seekUs + "us"
                        + " (wall " + seedUs + "us, loop " + wholeLoops + ")");
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (running && !outputDone) {
                try {
                    if (!inputDone) {
                        int in = codec.dequeueInputBuffer(POLL_MS);
                        if (in >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(in);
                            int size = extractor.readSampleData(buf, 0);
                            if (size < 0 && loop) {
                                // REWIND, and read the first sample into the SAME
                                // buffer. The old shape dequeued this buffer, found
                                // end-of-stream, and continued WITHOUT queueing it --
                                // so every loop leaked one input buffer, and after as
                                // many loops as the codec has buffers,
                                // dequeueInputBuffer returns -1 for ever and all
                                // three decoders stall. Measured on the Pixel:
                                // produced frozen at 453/453/453, latch/s 0, draws/s
                                // 61 -- four rewinds of a 4s clip at 30fps, against a
                                // codec with about that many input buffers. The spike
                                // ran four loops and stopped, which is why it never
                                // showed: the leak is invisible until it is fatal.
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                loops++;
                                basePtsUs += clipUs;
                                awaitingKeyframe = true;
                                keyframeDropBudget = 8;
                                size = extractor.readSampleData(buf, 0);
                            }
                            if (size >= 0) {
                                // ABSOLUTE pts at the input: the codec's output stays
                                // monotonic across loops, which is what makes the
                                // no-flush rewind safe.
                                codec.queueInputBuffer(in, 0, size,
                                        extractor.getSampleTime() + basePtsUs, 0);
                                extractor.advance();
                            } else if (!loop) {
                                codec.queueInputBuffer(in, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                // A rewind that yielded nothing: return the buffer so
                                // it is not leaked, and try again on the next poll.
                                codec.queueInputBuffer(in, 0, 0, 0, 0);
                            }
                        }
                    }
                    int out = codec.dequeueOutputBuffer(info, POLL_MS);
                    if (out >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (!cfg) {
                            // Drop anything before the first key frame after a rewind:
                            // see awaitingKeyframe. Released without rendering, so the
                            // surface keeps the last good frame instead of a mosaic.
                            if (awaitingKeyframe) {
                                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                                if (key) {
                                    awaitingKeyframe = false;
                                } else if (keyframeDropBudget-- <= 0) {
                                    // The flag never came. Render anyway: a possible
                                    // one-frame artifact beats a frozen clip, which is
                                    // what the unbounded wait produced on the Pixel.
                                    awaitingKeyframe = false;
                                } else {
                                    codec.releaseOutputBuffer(out, false);
                                    if (eos) outputDone = true;
                                    continue;
                                }
                            }
                            // GATE BEFORE RELEASE, on the ABSOLUTE time. See the class note.
                            // Already absolute: the offset was added when the frame was
                            // QUEUED, so the codec's own pts is the loop clock's time
                            // and nothing is recomputed from `loops` here.
                            long absPts = info.presentationTimeUs;
                            awaitSpace(absPts);
                            // A frame whose turn has come is released; a frame that is
                            // far enough LATE is the signal that this stream cannot
                            // keep up, and is replaced by a seek to what is due now.
                            // See catchUpIfLate.
                            if (!eos && catchUpIfLate(extractor, absPts)) {
                                codec.releaseOutputBuffer(out, false);
                                continue;
                            }
                            codec.releaseOutputBuffer(out, true);
                            lastAbsPts = absPts;
                            produced++;
                        } else {
                            codec.releaseOutputBuffer(out, false);
                        }
                        if (eos) outputDone = true;
                    }
                } catch (IllegalStateException race) {
                    // The codec was stopped or released underneath this thread, which
                    // is exactly what stop() does. Measured on the OnePlus 6T with
                    // THREE streams: "queueInputBuffer() is valid only at Executing
                    // states; currently at Released". Teardown is not a fault -- ending
                    // the loop is correct, and letting it escape kills the decoder
                    // thread and takes the renderer's source with it.
                    if (running) android.util.Log.w(TAG, "clip " + tag + " raced teardown: " + race);
                    break;
                }
            }
        } catch (Throwable t) {
            error = t.toString();
            android.util.Log.e(TAG, "clip " + tag + ": " + t, t);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) { }
                try { codec.release(); } catch (Throwable ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) { }
            }
            codec = null;
        }
    }

    private static int getInt(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
