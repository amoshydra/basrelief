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
    static final float DEPTH_TEMPORAL_BLEND = 0.7f;

    /** The previous depth field, for the temporal blend; null until first blit. */
    private ClipBlitter.Channel prevField;
    private boolean depthPrevValid;

    /**
     * How far ahead of the clock a frame may be released. Equal for every decoder.
     *
     * <p>Measured at 120 ms with all streams gated identically: {@code skew=40000us}
     * — one frame interval — and {@code loops=4/4} after four cycles.
     */
    private static final long LEAD_US = 120_000L;

    private ClipDecoder color, depth, normal;
    private ClipBlitter blit;

    private long clockStartNanos;
    /**
     * The clips this player was started with, as their stored form. See
     * {@link #isSameClips}.
     */
    private String playingJson;
    private int videoW, videoH;
    private boolean fullRange = true;
    private boolean running;
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
    String stats() {
        if (color == null) return "not started";
        return "colour[" + color.state() + " loops=" + color.loops() + "]"
                + " depth[" + depth.state() + " loops=" + depth.loops() + "]"
                + " skew=" + (Math.abs(color.lastAbsPts() - depth.lastAbsPts()) / 1000L) + "ms"
                + " range=" + color.rangeName();
    }

    /**
     * Start playing one clip. GL thread.
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

        // The blit radius is the production CPU constant, converted to field texels.
        // One blur of one radius: two would give two surfaces that differ for no
        // visible reason.
        blit = new ClipBlitter(Math.max(1,
                Math.round(grid * ReliefMesh.DEPTH_BLUR_FRACTION)));
        blit.create();

        long clipUs = durationUs(cf);
        clockStartNanos = System.nanoTime();

        playingJson = tracks.toJson();
        color = new ClipDecoder(cf.getPath(), "colour", clipUs);
        depth = new ClipDecoder(df.getPath(), "depth", clipUs);
        // The normal track is OPTIONAL and its absence is not a failure: the clip is
        // whole without it, and the only thing it changes is whether the relief is
        // shaded or lit as a plane. Same rule as a still's missing normal map.
        if (nf != null && nf.exists()) normal = new ClipDecoder(nf.getPath(), "normal", clipUs);

        // The surfaces have to exist before the codecs are configured, so the blitter's
        // targets are allocated first and bound to their SurfaceTextures after.
        // Sizes come from the container, not from the field: colour and normal are
        // shown at their own size and only depth is resampled to the grid.
        probeSize(cf);
        blit.colour = ClipBlitter.makeRgba(Math.max(1, videoW), Math.max(1, videoH));
        blit.depth = ClipBlitter.makeField(grid + 1);
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
        depth.setSurface(blit.depth.surface());
        if (blit.normal != null) normal.setSurface(blit.normal.surface());

        // ONE clock, and the SAME lead for all three. See the class note.
        ClipDecoder.Clock clock = new ClipDecoder.Clock() {
            @Override
            public long nowUs() {
                return (System.nanoTime() - clockStartNanos) / 1000L;
            }
        };
        color.setClock(clock);
        color.setLeadUs(LEAD_US);
        depth.setClock(clock);
        depth.setLeadUs(LEAD_US);
        if (normal != null) {
            normal.setClock(clock);
            normal.setLeadUs(LEAD_US);
        }

        color.start();
        depth.start();
        if (normal != null) normal.start();
        running = true;
        fullRange = color.fullRange();
        android.util.Log.i(TAG, "clip started " + videoW + "x" + videoH
                + " grid=" + grid + " normal=" + (normal != null) + " leadUs=" + LEAD_US);
        return true;
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
    /** Frames newly latched since start. */
    int latches() {
        return latches;
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
        blit.update(blit.depth);
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
        float depthScale = depth.fullRange() ? 1f : 255f / 219f;
        float depthOffset = depth.fullRange() ? 0f : -16f * (255f / 219f);

        // Colour: a straight copy. The sampler does the YUV to RGB here, which is
        // where it is free. Range-corrected for the same reason, from the colour
        // track's own report rather than the depth's.
        blit.blit(blit.colour, blit.program(ClipBlitter.COPY),
                color.fullRange() ? 1f : 255f / 219f, color.fullRange() ? 0f : -16f * (255f / 219f),
                0, 0);

        // Depth: the box blur, into the float field the vertex shader reads. The
        // shader divides by its own tap count, so the divisor travels with the loop.
        blit.blit(blit.depth, blit.program(ClipBlitter.BLUR_DEPTH), depthScale, depthOffset,
                1f / Math.max(1, blit.depth.w), 1f / Math.max(1, blit.depth.h));

        if (blit.normal != null) {
            // NORMAL_TRACK_RANGE, not 1. The normal map is a DIRECTION, so its 0.5 is
            // a middle and the expansion is what makes that middle the renderer's 0.
            // Skip it and every normal map reads as a lit-from-the-bottom-left
            // surface, which looks like a plausible lighting bug and is not one.
            blit.blit(blit.normal, blit.program(ClipBlitter.COPY_SCALED),
                    normal.fullRange() ? 1f : 255f / 219f,
                    normal.fullRange() ? 0f : -16f * (255f / 219f), 0, 0);
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
            blit = null;
        }
        if (prevField != null) {
            release(prevField);
            prevField = null;
        }
        // The temporal blend restarts with the clip: the stale previous field belongs
        // to a pair that is gone.
        depthPrevValid = false;
        running = false;
        framesArrived = false;
        // The identity goes with the clip. Leaving it behind would make a later
        // isSameClips answer for a player that is not running -- harmless only because
        // every caller checks isRunning first, which is exactly the kind of coupling a
        // stale field invites.
        playingJson = null;
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
