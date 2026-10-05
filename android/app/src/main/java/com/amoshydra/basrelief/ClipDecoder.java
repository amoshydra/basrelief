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

    private volatile boolean loop = true;
    private volatile boolean running;
    private volatile boolean waiting;

    private volatile String rangeName = "unspecified->assume studio";
    private volatile boolean fullRange = true;
    private volatile int trackW, trackH;
    private volatile long lastAbsPts = -1L;
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
            boolean tooEarly = c != null && ptsUs > c.nowUs() + leadUs;
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
                            if (size < 0) {
                                if (loop) {
                                    codec.flush();
                                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                    loops++;
                                    continue;
                                }
                                codec.queueInputBuffer(in, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int out = codec.dequeueOutputBuffer(info, POLL_MS);
                    if (out >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (!cfg) {
                            // GATE BEFORE RELEASE, on the ABSOLUTE time. See the class note.
                            long absPts = clipUs > 0
                                    ? info.presentationTimeUs + loops * clipUs
                                    : info.presentationTimeUs;
                            awaitSpace(absPts);
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
