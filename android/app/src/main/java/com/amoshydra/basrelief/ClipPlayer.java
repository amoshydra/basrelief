package com.amoshydra.basrelief;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;

import com.amoshydra.basrelief.core.render.ReliefMesh;
import com.amoshydra.basrelief.core.store.VideoTracks;

import java.io.File;

/**
 * A clip playing: three decoders, three surfaces, one shared clock.
 *
 * <p><b>Everything about the sync lives here, and none of it lives in the
 * decoders.</b> The decoders gate themselves on absolute time; what makes them
 * <i>agree</i> is that they are handed one clock object and the same lead. Two
 * decoders with two clocks is not a sync mechanism at all, it is two pictures.
 *
 * <p><b>The lead is equal for all three and it is a policy, not a tuning
 * constant.</b> The renderer latches the newest frame at or before
 * {@code clock + lead}, so two different leads are two different frame indices by
 * construction. Measured with 400 ms against 120 ms: a steady 280 ms of skew that
 * read as a sync bug and was nothing of the kind.
 *
 * <p><b>Teardown stops the decoders before the surfaces are released</b>, in that
 * order. A decoder released the other way round hands a frame to a
 * {@link SurfaceTexture} whose GL name has already been deleted, which is a black
 * surface and no exception anywhere.
 *
 * <p><b>A hidden engine SUSPENDS a clip in place; it does not stop it.</b> The
 * decoders stop (their threads join) but the clock is held at the clip time they
 * reached, so {@link #resume()} seeds them back to that time and the picture
 * continues where it was rather than rewinding -- "a video that rewinds is a
 * photograph" applies to a screen-off just as much as to a resolve. Both
 * operations are host-thread work with no GL calls in them, because the host that
 * must do this has just paused its {@code GLSurfaceView} and that thread no longer
 * drains its queue; see {@link #suspend()}.
 */
final class ClipPlayer {

    private static final String TAG = "Basrelief";

    /**
     * The weight of the CURRENT frame in the depth field's temporal blend.
     *
     * <p>MoGe estimates each frame independently, so its per-frame errors shimmer;
     * the field is mixed with the PREVIOUS frame's field to remove the shimmer. Near
     * 1 the lag is under a frame and the smoothing is weakest; near 0 the depth is
     * nearly frozen. 0.7 is a start, filed as a constant rather than a suggestion so
     * tuning it is a one-line edit with a name.
     */
    /**
     * How far ahead of the clock a frame may be released. Equal for every decoder.
     *
     * <p>Measured at 120 ms with all streams gated identically: {@code skew=40000us}
     * — one frame interval — and {@code loops=4/4} after four cycles.
     */
    private static final long LEAD_US = 120_000L;

    /**
     * The neutral value of a depth FIELD, in the field's own 0..255 units.
     *
     * <p>128 is the still path's flat byte: {@code ReliefRenderer.decodePair} fills
     * an absent map with it, and the vertex shader unpacks {@code 128/255 - 0.5} as a
     * plane. A colour-only preview writes THIS into its field — the deliberate
     * depthless state, not a missing texture — because the vertex shader always
     * samples the CLIP's own field while the clip is drawing.
     */
    private static final float FLAT_FIELD = 128f;

    private ClipDecoder color, depth, normal;
    private ClipBlitter blit;

    /**
     * The clock's anchor: {@code nowUs() = (nanoTime - clockStartNanos) / 1000}.
     *
     * <p>Volatile because {@link #resume()} re-anchors it on the host thread while
     * the decoders' gate reads it on theirs: suspending freezes the clip time, so
     * resuming moves the anchor rather than letting the held time drift with wall
     * clock.
     */
    private volatile long clockStartNanos;
    /**
     * The shared clock. A FIELD because a grow hands the SAME object to the decoders
     * that arrive late — two clock objects is not a sync mechanism, it is two
     * pictures (see the class note).
     */
    private ClipDecoder.Clock clock;
    /**
     * The clips this player was started with, as their stored form. See
     * {@link #isSameClips}.
     *
     * <p>Null during a colour-only PREVIEW: a preview has no tracks to store — a
     * preview is deliberately never a {@link VideoTracks} — so there is no stored
     * form to compare and {@code isSameClips} answers false for every clip, which is
     * what makes the resolve that brings the tracks GROW this player rather than sit
     * on a false identity. {@link #growTo} writes the real tracks here when they
     * arrive.
     *
     * <p>{@code volatile} because hosts read it through {@code clipMatches} on their
     * own threads while the GL thread is what writes it — a stale "not the clip I am
     * playing" costs a redundant load, and the stronger reason is that the field the
     * renderer's identity rests on should not be the one field with a race in it.
     */
    private volatile String playingJson;
    /**
     * The colour file this player is playing, so a request for the same colour can
     * recognise it. See {@link #playsColour}: the identity a preview can be compared
     * by, and the one a grown clip keeps so a late preview request cannot restart it.
     *
     * <p>Volatile for the same reason as {@link #playingJson}.
     */
    private volatile File colourFile;
    private float playingRate = 1f;

    /** The rate a restart should use; see {@link #setRate}. */
    float rate() {
        return playingRate;
    }
    private int videoW, videoH;
    private boolean fullRange = true;
    /**
     * Whether the decoders are decoding right now.
     *
     * <p>Volatile because {@link #suspend()}/{@link #resume()} write it from the
     * host thread -- the one a visibility change runs on -- while the GL thread
     * reads it every frame. "Decoding right now" is deliberately NOT the question
     * identity checks ask; see {@link #isLive()}.
     */
    private volatile boolean running;
    /**
     * Whether a hide suspended this player and it is holding its clip time.
     *
     * <p>{@link #isLive()} is the field the rest of the app wants: a suspended
     * player is still "the clip we want", it is simply not decoding, and treating
     * it as gone is what would tear it down and rebuild it -- a rewind.
     */
    private volatile boolean suspended;
    /** The clip time {@link #suspend()} stopped at; where {@link #resume()} seeds. */
    private volatile long suspendedAtUs;
    private volatile boolean framesArrived;
    /** Frames newly latched since start; the once-a-second line reports its delta. */
    private volatile int latches;
    private String error;

    /** The colour texture the fragment shader samples. 0 until started. */
    int colourTex() {
        return blit == null || blit.colour == null ? 0 : blit.colour.tex2d;
    }

    /** The float depth field the VERTEX shader samples. 0 until started. */
    int depthFieldTex() {
        return blit == null || blit.depth == null ? 0 : blit.depth.tex2d;
    }

    /** The normal texture, or 0 for a flat-lit clip. */
    int normalTex() {
        return blit == null || blit.normal == null ? 0 : blit.normal.tex2d;
    }

    boolean hasNormal() {
        return normal != null;
    }

    boolean isRunning() {
        return running;
    }

    /** Whether a hide suspended this player; see {@link #isLive()}. */
    boolean isSuspended() {
        return suspended;
    }

    /**
     * Whether this player is the clip we want: decoding, or suspended in place
     * holding its time.
     *
     * <p><b>The question every identity check must ask instead of
     * {@link #isRunning()}.</b> A suspended player whose three files still match
     * is the clip that should be on screen; calling it "not running" would make
     * {@code clipMatches} answer false, the resolve would reload the pair, and
     * {@code startClip} would stop the suspended player and build three new
     * decoders -- a rewind to frame 0, the reported "a video that rewinds is a
     * photograph", arriving from the safe-looking comparison.
     *
     * <p>{@link #isRunning()} stays for the questions about decoding itself: the
     * frame pump (a suspended clip wants no frames) and the watchdog (a suspended
     * clip cannot stall).
     */
    boolean isLive() {
        return running || suspended;
    }

    /**
     * Whether these are the clips already playing.
     *
     * <p><b>By stored form, not by object.</b> Two {@code VideoTracks} parsed from the
     * same index entry name the same three files and are still two objects, and
     * comparing them by identity restarts a clip that was merely re-parsed -- which is
     * what happens on <i>every</i> read of the index, so the clip would never survive a
     * resolve. The stored form is the identity the rest of the app already treats as
     * such: the index owns the row, and two rows are the same row when their blobs
     * match.
     *
     * <p>Compared rather than relying on object identity is also why a clip with no
     * normal track and one with it are different here: the two blobs differ, so a clip
     * whose normal arrives is a different clip and is genuinely restarted.
     */
    /** "colour/depth/normal" produced counts, for the once-a-second line. */
    String producedCounts() {
        return (color == null ? "-" : String.valueOf(color.produced()))
                + "/" + (depth == null ? "-" : String.valueOf(depth.produced()))
                + "/" + (normal == null ? "-" : String.valueOf(normal.produced()));
    }

    String playingJsonForLog() {
        return playingJson;
    }

    boolean isSameClips(com.amoshydra.basrelief.core.store.VideoTracks other) {
        return other != null && playingJson != null && playingJson.equals(other.toJson());
    }

    String error() {
        return error;
    }

    int videoWidth() {
        return videoW;
    }

    int videoHeight() {
        return videoH;
    }

    /** One line for the log: per-track state, the skew and the loop counts. */
    /**
     * @param withProbes true only while the debug view is up. The two readbacks are
     *                   synchronous glReadPixels on the GL thread -- a pipeline stall
     *                   each, one of them a float readback of an R32F target -- and
     *                   diagnostics that cost a hitch every second in the shipping
     *                   path are not diagnostics, they are the sluggishness.
     */
    String stats(boolean withProbes) {
        if (color == null) return "not started";
        return "colour[" + color.state() + " loops=" + color.loops() + "]"
                // A null depth is a colour-only preview, and it is NAMED rather than
                // skipped: "-" in a diagnostics line reads as a broken decoder, and
                // "preview" is the state the line is there to distinguish.
                + " depth[" + (depth == null ? "preview"
                        : depth.state() + " loops=" + depth.loops()) + "]"
                // BOTH deltas, each against the shared colour, and each named. The
                // old single skew reported only the depth's, and the measured
                // divergence of a 50 fps import was worst on the NORMAL -- the
                // heaviest encode -- so the stream that was furthest behind was the
                // one the line could not see.
                + " depthSkew=" + skewMs(color, depth)
                + " normalSkew=" + skewMs(color, normal)
                + " range=" + color.rangeName()
                + (withProbes
                    ? " fieldCentre=" + blit.fieldCentreSample()
                        + " normalRGB=" + blit.normalCentreSample()
                    : "");
    }

    /**
     * The gap between the colour's on-screen frame and one track's, in ms, or "-".
     *
     * <p>-1 is "no frame yet" on either side and must not be differenced: a decoder
     * that has released nothing reports -1, and {@code |-1 - x|} is a number that
     * reads as a huge skew rather than as "nothing has played yet".
     */
    private static String skewMs(ClipDecoder colour, ClipDecoder track) {
        float v = skewValueMs(colour, track);
        return Float.isNaN(v) ? "-" : (Math.round(v) + "ms");
    }

    /**
     * The depth track's skew against the colour, in ms, or NaN when either side has no
     * frame yet.
     *
     * <p>Numeric rather than a formatted string, because the studio's on-glass readout
     * shows the worse track's skew and the log shows both — one derivation, two
     * presentations. {@link #skewMs} formats the same value for the log line.
     */
    float depthSkewMs() {
        return skewValueMs(color, depth);
    }

    /** The normal track's skew against the colour, in ms, or NaN; see {@link #depthSkewMs}. */
    float normalSkewMs() {
        return skewValueMs(color, normal);
    }

    /** The skew itself, in whole ms, or NaN. See {@link #skewMs} for the absent case. */
    private static float skewValueMs(ClipDecoder colour, ClipDecoder track) {
        if (colour == null || track == null) return Float.NaN;
        long a = colour.lastAbsPts();
        long b = track.lastAbsPts();
        if (a < 0 || b < 0) return Float.NaN;
        return (float) (Math.abs(a - b) / 1000L);
    }

    /**
     * Start playing one full clip: colour, depth, optional normal. GL thread.
     *
     * @param grid the relief grid, so the depth field is exactly the texture the
     *             vertex shader samples — {@code GRID + 1}, because the vertex at
     *             grid {@code (gx, gy)} reads texel {@code (gx, gy)}.
     * @return false if the clips could not be opened, which is a <b>poster</b> state
     *         rather than a failure: the photograph is still there.
     */
    boolean start(VideoTracks tracks, File filesDir, int grid) {
        stop();
        // Through VideoTracks' OWN resolvers, not a second call to PrefFiles. Those
        // are the pair invariant's other half -- playable() asks the same question one
        // layer out -- and resolving here as well would be a second derivation of
        // which file a stored name means.
        File cf = tracks.colorFile(filesDir);
        File df = tracks.depthFile(filesDir);
        File nf = tracks.hasNormalTrack() ? tracks.normalFile(filesDir) : null;
        if (cf == null || df == null || !cf.exists() || !df.exists()) {
            error = "clip files missing";
            return false;
        }
        return open(cf, df, (nf != null && nf.exists()) ? nf : null, grid, tracks.toJson());
    }

    /**
     * Start a colour-only PREVIEW: the user's video plays while the server makes
     * its depth, displaced by a deliberately FLAT field.
     *
     * <p><b>This is not a degraded clip and must not be one silently.</b> Colour
     * without depth is exactly the state the pair invariant exists to make
     * unrepresentable in the INDEX — but here it is transient, it exists only while
     * a job record says the depth is coming, the status line says so, and the sheet's
     * line says so. The colour clip is the import's own remux, written before
     * anything was submitted, so the picture moving here is the user's video and not
     * a placeholder.
     *
     * <p>The identity is null on purpose: a preview is not a {@link VideoTracks} and
     * has no stored form. {@link #growTo} moves the identity to the real tracks when
     * they arrive, and until then every clip request compares unequal — which is what
     * makes the resolve that brings the tracks grow this player.
     *
     * @return false if the colour file could not be opened: a poster state, like
     *         {@link #start}.
     */
    boolean startPreview(File colour, int grid) {
        stop();
        if (colour == null || !colour.exists()) {
            error = "clip files missing";
            return false;
        }
        return open(colour, null, null, grid, null);
    }

    /**
     * The body both starts share. {@code df}/{@code nf} null means a colour-only
     * preview; {@code identity} is the stored form {@link #isSameClips} compares, or
     * null for a preview.
     */
    private boolean open(File cf, File df, File nf, int grid, String identity) {
        // The blit radius is the production CPU constant, converted to field texels.
        // One blur of one radius: two would give two surfaces that differ for no
        // visible reason.
        blit = new ClipBlitter(Math.max(1,
                Math.round(grid * ReliefMesh.DEPTH_BLUR_FRACTION)));
        blit.create();

        long clipUs = durationUs(cf);
        clockStartNanos = System.nanoTime();
        // ONE clock object, and a field because a grow hands the SAME one to the
        // decoders that arrive late. See growTo.
        clock = new ClipDecoder.Clock() {
            @Override
            public long nowUs() {
                return (System.nanoTime() - clockStartNanos) / 1000L;
            }
        };
        colourFile = cf;
        playingJson = identity;

        color = new ClipDecoder(cf.getPath(), "colour", clipUs);
        if (df != null) depth = new ClipDecoder(df.getPath(), "depth", clipUs);
        // The normal track is OPTIONAL and its absence is not a failure: the clip is
        // whole without it, and the only thing it changes is whether the relief is
        // shaded or lit as a plane. Same rule as a still's missing normal map.
        if (nf != null) normal = new ClipDecoder(nf.getPath(), "normal", clipUs);

        // The surfaces have to exist before the codecs are configured, so the blitter's
        // targets are allocated first and bound to their SurfaceTextures after.
        // Sizes come from the container, not from the field: colour and normal are
        // shown at their own size and only depth is resampled to the grid.
        probeSize(cf);
        blit.colour = ClipBlitter.makeRgba(Math.max(1, videoW), Math.max(1, videoH));
        blit.depth = ClipBlitter.makeField(grid + 1);
        blit.tmp = ClipBlitter.makeField(grid + 1);
        // A preview has no depth decoder, so NOTHING will ever write this field on
        // that run: fill it with the deliberate flat value. An unfilled R32F target
        // is undefined memory the vertex shader samples as displacement, and
        // "undefined" reads as black, white or noise -- all three reported as "depth
        // is broken" with nothing anywhere saying it was never written.
        if (depth == null) ClipBlitter.fillField(blit.depth, FLAT_FIELD);
        if (normal != null) {
            int nw = videoW, nh = videoH;
            probeSize(nf);
            blit.normal = ClipBlitter.makeRgba(Math.max(1, nw), Math.max(1, nh));
            probeSize(cf); // restore: probeSize is a shared reader
        }
        blit.bindOes(blit.colour);
        blit.bindOes(blit.depth);
        if (blit.normal != null) blit.bindOes(blit.normal);

        color.setSurface(blit.colour.surface());
        if (depth != null) depth.setSurface(blit.depth.surface());
        if (normal != null) normal.setSurface(blit.normal.surface());

        // ONE clock, and the SAME lead for every decoder. See the class note.
        color.setClock(clock);
        color.setLeadUs(LEAD_US);
        if (depth != null) {
            depth.setClock(clock);
            depth.setLeadUs(LEAD_US);
        }
        if (normal != null) {
            normal.setClock(clock);
            normal.setLeadUs(LEAD_US);
        }

        // The rate the renderer has resolved, applied to all of them before any of
        // them releases a frame -- a clip must not start at 1x and then jump.
        setRate(playingRate);
        color.start();
        if (depth != null) depth.start();
        if (normal != null) normal.start();
        running = true;
        fullRange = color.fullRange();
        android.util.Log.i(TAG, "clip started " + videoW + "x" + videoH
                + " grid=" + grid + " normal=" + (normal != null)
                + " preview=" + (identity == null) + " leadUs=" + LEAD_US);
        return true;
    }

    /**
     * Grow a running PREVIEW into the full clip, in place.
     *
     * <p><b>The colour decoder, its clock and its frame are never touched</b> — not a
     * stop, not a seek, not a restart. "A video that rewinds is a photograph" is a
     * reported defect, and the whole point of a preview is that the picture the user
     * has been watching keeps playing; only the depth it is displaced by may
     * change. So the new decoders are created against the SAME clock instance with
     * the SAME lead and the CURRENT rate — two clocks is two pictures — and are
     * seeded at the clip time the colour has already reached
     * ({@link ClipDecoder#startAt}) rather than walking from zero.
     *
     * <p>The identity moves to the full tracks HERE, at the grow, so a later
     * {@link #isSameClips} answers for what is actually playing. A preview that kept
     * a null identity answers false for its own tracks (fine: it needs to grow), but
     * a grown clip that did not take them would be grown again on every resolve —
     * each time stopping the depth decoder it just created.
     */
    void growTo(VideoTracks tracks, File filesDir) {
        // isLive, not isRunning: a grow can land while the clip is SUSPENDED -- the
        // tracks arrive while the engine is hidden -- and the identity still has to
        // move to them, or the next resolve would grow the same player again. The
        // new decoders are built and NOT started in that case; resume seeds all
        // three. See the start block below.
        if (!isLive() || blit == null || color == null) return;
        final File cf = tracks.colorFile(filesDir);
        final File df = tracks.depthFile(filesDir);
        final File nf = tracks.hasNormalTrack() ? tracks.normalFile(filesDir) : null;
        long clipUs = durationUs(cf);

        // A decoder already writing this channel's SurfaceTexture must be stopped
        // before another one is pointed at it: two producers on one surface is a
        // symptom nobody can diagnose from the screen. Unreachable today — a grow
        // happens only when the colour's identity changed — and refused structurally
        // rather than trusted.
        if (depth != null) {
            depth.stop();
            depth = null;
        }
        if (df != null) {
            depth = new ClipDecoder(df.getPath(), "depth", clipUs);
            depth.setSurface(blit.depth.surface());
            depth.setClock(clock);
            depth.setLeadUs(LEAD_US);
        }
        if (normal != null) {
            normal.stop();
            normal = null;
        }
        if (nf == null && blit.normal != null) {
            // A grown clip with no normal must not keep sampling a stale one: release
            // the channel so the renderer falls back to the still's normal, exactly
            // as a flat-lit clip does.
            release(blit.normal);
            blit.normal = null;
        }
        if (nf != null) {
            normal = new ClipDecoder(nf.getPath(), "normal", clipUs);
            int nw = videoW, nh = videoH;
            probeSize(nf);
            blit.normal = ClipBlitter.makeRgba(Math.max(1, nw), Math.max(1, nh));
            blit.bindOes(blit.normal);
            normal.setSurface(blit.normal.surface());
            normal.setClock(clock);
            normal.setLeadUs(LEAD_US);
            probeSize(cf); // restore the shared reader
        }

        // The rate BEFORE startAt: the seed target is the wall clock scaled by the
        // rate, which is the same arithmetic the gate releases against.
        setRate(playingRate);
        if (!suspended) {
            long now = clock == null ? 0L : clock.nowUs();
            if (depth != null) {
                depth.startAt(now);
                depth.start();
            }
            if (normal != null) {
                normal.startAt(now);
                normal.start();
            }
            android.util.Log.i(TAG, "clip grew in place depth=" + (depth != null)
                    + " normal=" + (normal != null) + " at=" + now
                    + "us -- colour untouched");
        } else {
            // Suspended: the colour is stopped too, and the clock is held rather
            // than live, so there is no "now" to seed to. resume() seeds all three
            // at the held time -- the same time the colour continues from -- so
            // the grown tracks join one clip time like every other start.
            android.util.Log.i(TAG, "clip grew in place while suspended depth="
                    + (depth != null) + " normal=" + (normal != null)
                    + " -- resume seeds all three");
        }
        playingJson = tracks.toJson();
    }

    /**
     * Stop decoding and hold the clip time. Any thread; no GL calls.
     *
     * <p><b>This is what a hidden engine must do, and it cannot be a stop.</b> The
     * host that hides has just paused the {@code GLSurfaceView}: its event queue is
     * no longer being drained, so anything that would stop the clip by being posted
     * there sits undelivered for as long as the engine is hidden -- measured as
     * three {@code clip-decode-*} threads and three {@code MediaCodec_loop} threads
     * burning most of a core for eleven minutes behind a dark screen. The decoders
     * are not tied to visibility; this is the tie.
     *
     * <p><b>The clock is held, not reset.</b> The decoders stop at the clip time
     * they reached, and {@link #resume()} seeds all three back to it through
     * {@link ClipDecoder#startAt} -- the same seeding {@link #growTo} uses -- so
     * waking continues the picture instead of rewinding it. Idempotent, so a hide
     * that fires twice costs nothing.
     */
    void suspend() {
        if (!running) return;
        if (color != null) color.stop();
        if (depth != null) depth.stop();
        if (normal != null) normal.stop();
        // AFTER the joins: the decoders stop at the clip time they reached, and
        // that time is what resume seeds to. Reading it first would rewind by the
        // join's own few milliseconds.
        suspendedAtUs = clock == null ? 0L : clock.nowUs();
        running = false;
        suspended = true;
        android.util.Log.i(TAG, "clip suspended at " + suspendedAtUs + "us");
    }

    /**
     * Resume a suspended clip IN PLACE. Any thread; no GL calls.
     *
     * <p>The clock is re-anchored to the held clip time before anything starts --
     * the decoders gate on {@code nowUs()}, so without this the held time would
     * have drifted with wall clock while suspended and every decoder would be
     * seeded to a time the picture never reached. Then every decoder is seeded to
     * the same held time and started, which is one clip time for all three by
     * construction. Idempotent.
     */
    void resume() {
        if (!suspended) return;
        clockStartNanos = System.nanoTime() - suspendedAtUs * 1000L;
        if (color != null) {
            color.startAt(suspendedAtUs);
            color.start();
        }
        if (depth != null) {
            depth.startAt(suspendedAtUs);
            depth.start();
        }
        if (normal != null) {
            normal.startAt(suspendedAtUs);
            normal.start();
        }
        suspended = false;
        running = true;
        android.util.Log.i(TAG, "clip resumed at " + suspendedAtUs + "us");
    }

    /**
     * Whether this player is playing one specific colour file.
     *
     * <p>The identity a PREVIEW can be compared by: a preview has no tracks to store
     * (and must not — {@link VideoTracks} refuses to be built without depth), so the
     * colour file is the whole of what it can be. A grown full clip still answers
     * TRUE for the file it grew from, which is exactly what keeps the resolve that
     * brought the tracks from tearing the grown clip down and starting a preview of
     * it — the one restart this feature must never do.
     *
     * <p>{@code isLive}, not {@code isRunning}: a suspended player is still the clip
     * this colour names, and answering "not playing" would let the resolve that is
     * supposed to resume it start a preview over it instead.
     */
    boolean playsColour(File f) {
        return isLive() && f != null && colourFile != null
                && colourFile.getPath().equals(f.getPath());
    }

    /**
     * Pull the newest decoded frame of each track and blit it into its target.
     * GL thread, once per drawn frame.
     *
     * <p><b>Updates before blits</b>, because a SurfaceTexture's transform matrix is
     * only valid once the frame has been handed over. Reading it earlier draws a
     * frame rotated by a stale matrix, which looks exactly like a texture orientation
     * bug and is not one.
     */
    /**
     * Frames released since start, across all three tracks: the WATCHDOG's signal.
     *
     * <p>A codec can wedge -- measured on two devices, both decoders frozen with the
     * pump still drawing, loops diverged and skew at 183 SECONDS -- and nothing in
     * the gate, the buffer handling or the decoder callbacks reports it. The only
     * observable is that this number stops growing while the clip still claims to be
     * running, so that is what the watchdog watches.
     */
    int producedTotal() {
        int t = 0;
        if (color != null) t += color.produced();
        if (depth != null) t += depth.produced();
        if (normal != null) t += normal.produced();
        return t;
    }

    /** Frames newly latched since start. */
    int latches() {
        return latches;
    }

    /**
     * The clip's playback rate, 1 = normal.
     *
     * <p>Handed to EVERY decoder, for the same reason the lead is: the two are
     * gated on one clock, and two rates would be two frame indices by
     * construction -- the depth would slide against the colour by exactly the ratio.
     * Safe to call while playing: the decoders read it per frame.
     */
    void setRate(float r) {
        if (color != null) color.setRate(r);
        if (depth != null) depth.setRate(r);
        if (normal != null) normal.setRate(r);
        playingRate = r;
    }

    int tick() {
        if (!running || blit == null) return 0;
        // Latch first, and let updateTexImage's OWN return value be the "is there
        // anything new" test.
        //
        // There used to be a guard here that returned early while
        // `st.getTimestamp() == 0 && !framesArrived`, on the reasoning that nothing had
        // been decoded yet. It was self-defeating: getTimestamp() only becomes
        // non-zero once updateTexImage() has latched a frame, and framesArrived is only
        // set at the END of this method -- so the first tick returned early and every
        // tick after it did too, for ever. updateTexImage was never called, the OES
        // texture was never latched, the blit sampled an empty texture, and the screen
        // was black.
        //
        // It failed silently and looked like three separate bugs: a "frozen frame"
        // (the poster showing through), a blank screen (the blit painting black over
        // the relief), and a still image when leaving a clip. It even made a
        // constant-colour probe shader measure zero, because the probe never ran.
        //
        // updateTexImage returning false means "no new frame, the previous one is
        // still current" -- which is exactly the cheap test the guard was reaching for,
        // and it can only be consulted after asking.
        boolean fresh = blit.update(blit.colour);
        // No depth decoder means a colour-only preview: there is no producer on the
        // field's SurfaceTexture, and its content is the deliberate FLAT fill from
        // open(). Skip the update and the two blur passes -- the field must not be
        // touched until real depth exists.
        if (depth != null) blit.update(blit.depth);
        if (blit.normal != null) blit.update(blit.normal);
        if (fresh) latches++;
        if (!fresh) {
            // Nothing new this frame. Returning is right and cheap: re-blitting the
            // same content would cost a full-screen pass for an identical result.
            return 0;
        }

        // The container's OWN colour range, per track, read from the decoder rather
        // than assumed -- because the depth and the normal do not have to agree.
        //
        // Limited ("tv", luma 16-235) is what the server actually writes: measured on
        // a generated clip, `color_range=tv`. The vertex shader reads `/255.0 - 0.5`,
        // so an unexpanded luma loses 16/255 at the bottom and floats the whole
        // surface -- uniformly shallower relief with no error anywhere. Full range is
        // the identity, which is the same rule Picture follows for brightness: 0 must
        // mean "unchanged" or every first frame takes the wrong path.
        //
        // Colour: a straight copy. The sampler does the YUV to RGB here, which is
        // where it is free. Range-corrected for the same reason, from the colour
        // track's own report rather than the depth's.
        // 0..1 UNITS for colour and normal -- see the shaders. The depth path is the
        // one that works in 0..255, and its offset below is the one that belongs there.
        float cScale = color.fullRange() ? 1f : 255f / 219f;
        float cOffset = color.fullRange() ? 0f : -16f / 219f;
        blit.blit(blit.colour, blit.program(ClipBlitter.COPY), cScale, cOffset, 0, 0);

        if (depth != null) {
            float depthScale = depth.fullRange() ? 1f : 255f / 219f;
            float depthOffset = depth.fullRange() ? 0f : -16f * (255f / 219f);
            // Depth: the box blur, into the float field the vertex shader reads. The
            // shader divides by its own tap count, so the divisor travels with the loop.
            // Separable: the horizontal box out of the OES into the intermediate, then
            // the vertical box out of the intermediate into the field. The same filter,
            // 7 taps each instead of 49 in one -- and the range expansion rides the
            // SECOND pass, which is the one that writes the field's units.
            blit.blitOesTo(blit.depth, blit.tmp, blit.program(ClipBlitter.BLUR_H), 1f, 0f,
                    1f / Math.max(1, blit.tmp.w), 0f);
            blit.blit2d(blit.tmp, blit.depth, blit.program(ClipBlitter.BLUR_V),
                    depthScale, depthOffset, 0f, 1f / Math.max(1, blit.depth.h));
        }

        if (blit.normal != null) {
            // NORMAL_TRACK_RANGE, not 1. The normal map is a DIRECTION, so its 0.5 is
            // a middle and the expansion is what makes that middle the renderer's 0.
            // Skip it and every normal map reads as a lit-from-the-bottom-left
            // surface, which looks like a plausible lighting bug and is not one.
            blit.blit(blit.normal, blit.program(ClipBlitter.COPY_SCALED),
                    normal.fullRange() ? 1f : 255f / 219f,
                    normal.fullRange() ? 0f : -16f / 219f, 0, 0);
        }
        framesArrived = true;
        return 1;
    }

    /**
     * Stop and release everything. GL thread, and safe to call twice.
     *
     * <p>Decoders first, surfaces second, GL names last. Every other order here hands
     * a frame to something that no longer exists.
     */
    void stop() {
        if (color != null) {
            color.stop();
            color = null;
        }
        if (depth != null) {
            depth.stop();
            depth = null;
        }
        if (normal != null) {
            normal.stop();
            normal = null;
        }
        if (blit != null) {
            release(blit.colour);
            release(blit.depth);
            release(blit.normal);
            release(blit.tmp);
            blit.releasePrograms();
            blit = null;
        }

        running = false;
        // The suspension goes with the player: a stopped clip has no held time to
        // resume to, and leaving the flag set would make the NEXT player report
        // itself suspended before it ever ran.
        suspended = false;
        suspendedAtUs = 0L;
        framesArrived = false;
        // The identity goes with the clip. Leaving it behind would make a later
        // isSameClips answer for a player that is not running -- harmless only because
        // every caller checks isRunning first, which is exactly the kind of coupling a
        // stale field invites.
        playingJson = null;
        colourFile = null;
        clock = null;
    }

    private static void release(ClipBlitter.Channel c) {
        if (c == null) return;
        c.st.release();
        if (c.oesName != 0) GLES20.glDeleteTextures(1, new int[]{c.oesName}, 0);
        GLES20.glDeleteTextures(1, new int[]{c.tex2d}, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{c.fbo}, 0);
    }

    /** The colour track's own duration, so a loop can be placed on the clock. */
    private static long durationUs(File f) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && fmt.containsKey("durationUs")) {
                    return fmt.getLong("durationUs");
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
        return 0L;
    }

    /**
     * Read a track's dimensions into {@link #videoW}/{@link #videoH}.
     *
     * <p>Deliberately a reader rather than a getter: the targets have to be allocated
     * before the codecs can be configured, and the only source for a track's size is
     * the container.
     */
    private void probeSize(File f) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (fmt.containsKey(android.media.MediaFormat.KEY_WIDTH)) {
                        videoW = fmt.getInteger(android.media.MediaFormat.KEY_WIDTH);
                    }
                    if (fmt.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                        videoH = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
