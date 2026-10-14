package com.amoshydra.basrelief;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;

import com.amoshydra.basrelief.core.render.CameraModel;
import com.amoshydra.basrelief.core.render.RenderStats;
import com.amoshydra.basrelief.core.render.Splat;
import com.amoshydra.basrelief.core.render.SplatCamera;
import com.amoshydra.basrelief.core.splat.SplatCloud;
import com.amoshydra.basrelief.core.splat.SplatProjection;

/**
 * A real 3D Gaussian splat cloud, drawn with the full-covariance projector.
 *
 * <p><b>It is a helper, not a second renderer.</b> {@link ReliefRenderer} owns
 * the {@code GLSurfaceView}, the camera, the tilt smoothing and the prefs; this
 * class owns one program, three textures and a counting sort, and does its work
 * inside the draw the host already performs. That is what makes the splat fit the
 * wallpaper's model: frames are requested by the sensor (the surface is
 * {@code RENDERMODE_WHEN_DIRTY}), and this class never asks for one of its own.
 *
 * <p><b>The math is the spike's, with one correction.</b> The spike's vertex
 * shader built {@code A3 = viewRot * R * S} and read
 * {@code c_ij = dot(A3[i], A3[j])} — the Gram matrix of the COLUMNS, which
 * algebraically collapses to {@code S^2}: the splat's rotation and the view
 * rotation both cancel, so every splat was a world-axis-aligned ellipsoid. Here
 * the chain is the one the plan states and {@link SplatProjection} tests:
 * {@code Sigma3D = R S S^T R^T}, {@code SigmaV = V Sigma3D V^T}, the perspective
 * Jacobian, {@code Sigma2D = J SigmaV J^T}, the conic and the eigen extents.
 * {@link #VERT} mirrors {@link SplatProjection} step for step.
 *
 * <p><b>The record is uploaded verbatim, as INTEGER texels.</b> The 32-byte
 * {@code .splat} record is exactly two {@code RGBA32UI} texels —
 * {@code (x,y,z,sx)} and {@code (sy,sz,colourBits,quatBits)} — so the file goes
 * straight to the GPU with no CPU repack and no per-frame allocation, and the
 * shader unpacks the colour and quaternion with integer shifts. <b>It must not be
 * a float texture.</b> Four uint8 packed into a float32 make a NaN for every
 * bright splat (alpha &ge; 128 and blue &ge; 128), and a NaN's payload does not
 * survive {@code floatBitsToUint} on every driver: Mali returns a canonical NaN,
 * so those splats draw as flat white/grey — the hard-edged speckle over a sky or
 * a white wall, with the tan sand beside it untouched because its blue byte is
 * below 128. Integers cannot be canonicalised, so the bytes arrive exactly.
 *
 * <p><b>The camera is the photograph's own, and it is a camera.</b> A cloud is in
 * the frame of the still SHARP ran on (x right, y down, z forward, principal point
 * at the centre), so the source view is the eye at the origin looking down +z with
 * that still's vertical field of view — the row's own lens, read from its sidecar
 * and stored as {@link com.amoshydra.basrelief.core.store.LibraryItem#splatLens};
 * see {@link #SOURCE_FOVY} for the demo constant a row without one falls back to.
 * At rest — no tilt, no Origin, no Pan — the view is exactly that source view, so
 * the picture is the photograph. Zoom narrows the field of view (a crop of the same
 * camera, which is what zoom means for a photograph), Origin sets the point the
 * tilt orbits about (the cloud's own surface under the origin's ray) and shifts the
 * picture to it, and Pan is a screen-pixel view offset. All three mean here what
 * they mean for the relief, and the whole construction lives in
 * {@link com.amoshydra.basrelief.core.render.SplatCamera} so the at-rest invariant
 * is asserted without a device.
 */
final class SplatRenderer {

    static final String TAG = "Basrelief";

    /** Texels per row of the data texture (2 texels per splat). */
    private static final int DATA_W = 2048;
    /** Texels per row of the order texture. */
    private static final int ORDER_W = 2048;
    private static final float NEAR = 0.05f;
    private static final float FAR = 4000f;

    /**
     * The FALLBACK source camera: the demo cloud's own lens and target, used only
     * when the row has no measured lens (every cloud generated before the sidecar's
     * {@code focal} was stored) or no cloud at all.
     *
     * <p><b>The demo cloud is the bale, and these are its numbers.</b> SHARP's
     * fallback 30 mm lens on a 1440x2560 still gives {@code f = 2036.6 px}, so the
     * vertical field of view is 64.3 degrees; the target sits on the optical axis at
     * the bale's front surface. They were the constants for EVERY cloud until the
     * item's own lens ({@link com.amoshydra.basrelief.core.store.LibraryItem#splatLens},
     * resolved through {@link Splat#fovyForFocalOverHeight}) arrived, which is the
     * bug this pair now only bounds: a Gaussian's screen size is proportional to the
     * focal it was reconstructed through, so drawing a cloud built at 1514..2375 px
     * through the bale's 2036.6 px made every splat the wrong size.
     *
     * <p><b>The fallback is deliberate, not a leftover.</b> A row with no lens keeps
     * exactly the look it had, so the clouds already on disk do not change until a
     * regeneration reads their sidecar and stores the lens. Same numbers as the
     * WebGL reference viewer's source view.
     */
    private static final float SOURCE_FOVY = (float) (2 * Math.toDegrees(
            Math.atan(2560.0 / 2 / (30.0 * Math.hypot(1440, 2560) / Math.hypot(36, 24)))));
    private static final float SOURCE_TARGET_Z = 2.4f;
    /** The zoom range the FOV crop is clamped to, so it never becomes a fisheye. */
    private static final float MIN_FOVY = 20f;
    private static final float MAX_FOVY = 100f;

    /** One run's numbers, printed once a second while a splat draws. */
    private static final boolean STATS = true;

    private static final int GL_TIME_ELAPSED_EXT = 0x88BF;
    private static final int GL_QUERY_RESULT_EXT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE_EXT = 0x8867;

    // GL names, recreated with the context.
    private int prog;
    private int vboCorner;
    private int texData;
    private int texOrder;
    private boolean ready;

    // Uniform locations.
    private int uProj, uView, uViewRot, uData, uOrder, uDataW, uOrderW, uNear, uFudge,
            uFocal, uViewport, uBrightnessGain, uShadowLift, uEps2d, uDepthMul, uDepthRef,
            uAlphaDiscard;

    // The cloud's CPU mirror.
    private int count;
    private float[] pos = new float[0];
    private float[] depths = new float[0];
    private int[] order = new int[0];
    private int[] counts = new int[0];
    private int dataRows;
    private int orderRows;

    /**
     * The depth the pivot falls back to when the cloud has nothing in front of the
     * camera: the loaded cloud's own median depth, or {@link #SOURCE_TARGET_Z} when
     * there is no cloud.
     *
     * <p>It is the cloud's CENTRE rather than the bale's front surface, because the
     * fallback has to be about the cloud that is actually on screen. Written at
     * upload (where the positions are), read at draw. The frontmost splat under the
     * origin's ray remains the real pivot; this is only what an empty-ish cloud
     * falls back to, so an orbit cannot vanish.
     */
    private float refDepth = SOURCE_TARGET_Z;

    // Offscreen target: the picture is drawn at `renderScale` of the surface and
    // blitted up. Fragment work is what scales with pixels and splats are soft.
    private int fbo, fboColour, fboDepth;
    // The blur ping-pong target: the horizontal pass writes here, the vertical
    // pass writes back into `fbo`. Allocated only while the dev BLUR knob is on.
    private int fboB, fboBColour;
    private int renderW = 1, renderH = 1;
    private float scaleInUse = 1f;
    /** Whether the last ensureOffscreen armed the FBO path (scale < 1 or blur on). */
    private boolean offscreenActive;
    /** Whether the last ensureOffscreen armed the blur passes. */
    private boolean blurActive;

    // The blur program and its uniforms. GLES3 only, like everything here.
    private int blurProg;
    private int uBlurTex, uBlurTexel, uBlurDir, uBlurRadius;

    /**
     * The two dev values the last frame actually drew with, for the stats line.
     *
     * <p>Written in {@link #draw} from the arguments, so the per-second log states
     * the numbers the frame used rather than the prefs -- a screenshot of the log
     * is then self-describing without a second adb call.
     */
    private float eps2dInUse = Splat.EPS_2D_DEFAULT;
    private float blurPxInUse = Splat.BLUR_DEFAULT;
    /** The dev value the last frame drew with, for the stats line. */
    private float depthMulInUse = Splat.DEPTH_MUL_DEFAULT;

    /**
     * The alpha-extent clip's reach over the loaded cloud, for the stats line: how
     * many splats it drops outright, how many it shrinks, and the mean extent factor
     * across the whole cloud.
     *
     * <p><b>These are a property of the CLOUD, not of the camera</b> -- the baked
     * opacity is per record -- so they are derived once at {@link #upload} and read
     * by the per-second log. They exist because the saving the clip buys depends
     * entirely on the cloud's opacity distribution, which a screenshot cannot state:
     * a high-opacity cloud keeps nearly every full quad, and the honest report of
     * that is a number in a log rather than a claim. {@link Splat#extentScaleForAlpha}
     * is the mapping the vertex shader mirrors.
     */
    private int alphaDropped;
    private int alphaShrank;
    private float alphaMeanExtent = 1f;

    /**
     * The frame the cloud was last DRAWN with, for resolving a tap: the projection's
     * own factors and the Origin + Pan shift. The tap's ray is the SOURCE camera's,
     * so the tapped pixel's source NDC is the drawn NDC minus this shift -- and the
     * DRAWN frame's numbers are used rather than recomputed so the tap lands on the
     * picture the user actually saw. Written in {@link #draw}.
     */
    private float tapProj0, tapProj5, tapShiftX, tapShiftY;

    private final float[] eye = new float[3];
    private final float[] forward = new float[3];
    private final float[] viewRot = new float[9];
    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    /** NDC per unit of (right . p) / depth — the projection matrix's own factors. */
    private float camFocalX, camFocalY;

    /**
     * The camera, as pure math in {@code :core}: the source view, the Origin/Pan
     * shift, and the turntable about the cloud's surface under the origin. It
     * lives there so the at-rest invariant is asserted without a device; see
     * {@link SplatCamera}.
     */
    private final SplatCamera splatCamera = new SplatCamera();

    // The sort gate: the camera state the current order was derived from.
    private float sortedTiltX = Float.NaN, sortedTiltY = Float.NaN, sortedZoom = Float.NaN;
    private float sortedOrbitX = Float.NaN, sortedOrbitY = Float.NaN;
    private boolean sortPrimed;
    private int lastCulled;
    /** NDC beyond this is certainly off-screen; the CPU cull count uses it. */
    private static final float CULL_MARGIN = 1.3f;

    /**
     * The sort gate's live threshold, cached on the framing it was derived from.
     *
     * <p>{@code SplatCamera.sortEps} is {@code O(count)}, so it must not run per
     * frame; but the tilt changes none of the inputs below, so it is derived once
     * per framing (at upload and on a zoom, origin, pan, motion or surface change)
     * and reused for every frame of a tilt. The key is exactly the set of values the
     * bound is a function of: the resolved pivot, the projection's own factors, the
     * Origin+Pan shift, Motion, and the surface. A framing that cannot claim a bound
     * answers the shipped constant -- see {@code SplatCamera.SHIPPED_SORT_EPS} for
     * why the gate is {@code max(constant, derived)} and can only ever loosen.
     *
     * <p><b>Pan is in the key because it genuinely moves the bound, and that was
     * checked rather than assumed.</b> {@code SplatCamera.pxPerTilt} takes the max
     * over the splats VISIBLE under the current shift, and pan is a screen-space
     * offset, so panning changes which splats that max is over even though it does
     * not change the pivot or any splat's parallax. Measured on the bundled cloud at
     * the device's own framing the bound goes from 439 px/tilt at pan (0, 0) to 564
     * at the stored (-55, 451) -- 28%, with the pivot identical -- so dropping pan
     * would serve a stale bound. It is not the cost it looks like either: a pure pan
     * moves no ordering, so {@code needsSort} does not include it and no sort runs;
     * the derivation is what repeats. {@code SplatCameraTest}'s
     * {@code panIsPartOfTheBoundsFramingKey} is the guard, so a future "pan is a
     * no-op" simplification goes red instead of drifting in.
     */
    private boolean sortEpsStale = true;
    private float sortEpsInUse = SplatCamera.SHIPPED_SORT_EPS;
    private float boundPivotX, boundPivotY, boundPivotZ;
    private float boundProj0, boundProj5, boundShiftX, boundShiftY, boundMotion;
    private int boundSurfaceW, boundSurfaceH;

    // Frame accounting.
    private int framesInWindow;
    private long lastLogNanos;
    private long lastFrameNanos;
    private long sortNanosInWindow;
    private int sortsInWindow;
    private long drawNanosInWindow;
    private long maxFrameNanos;
    private long windowStartNanos;
    private float gpuMs;

    /**
     * The last window's numbers, for the studio's on-glass readout.
     *
     * <p>Published from {@link #logStats} — the SAME computation the log prints, not a
     * second one that agrees today — and read on the UI thread through {@link
     * #perfStats()}. A fresh immutable object once a window; the UI thread does one
     * volatile read and allocates nothing, and it never waits on the GL thread for a
     * number. Null until the first window has been measured, which the readout blanks
     * rather than showing as a zero.
     */
    private volatile RenderStats perfStats;

    private boolean timerExt;
    private int timerQuery;
    private boolean queryOpen;
    private boolean queryInFlight;
    private final int[] queryOut = new int[1];

    private IntBuffer orderBuf;
    private final float[] probeCov = new float[3];
    private final float[] probeConic = new float[3];
    private boolean probePending;
    private SplatCloud probeCloud;

    boolean isReady() {
        return ready && count > 0;
    }

    int count() {
        return count;
    }

    /**
     * The last stats window, for the studio's readout, or null before the first.
     *
     * <p>Safe from the UI thread: a volatile read of an immutable object. The GL
     * thread publishes it in {@link #logStats}, so the readout and the log line carry
     * one computation.
     */
    RenderStats perfStats() {
        return perfStats;
    }

    /**
     * Resolve a tap to the Origin it names: the image position of the frontmost
     * splat under the ray through the tapped pixel.
     *
     * <p><b>It casts the same ray {@link SplatCamera#pivot} resolves the orbit
     * from</b>, so the tap, the pivot, the aim and the crosshair are one answer. The
     * drawn view is the source view shifted by Origin + Pan, so the tapped pixel's
     * SOURCE NDC is the drawn NDC minus that shift -- and the shift and projection
     * factors come from the frame the cloud was last DRAWN with, not recomputed, so
     * the tap lands on the picture the user saw. The Depth knob is handed through
     * only for the pick (a splat it pushes through the eye is skipped); the answer is
     * that splat's image position, which the knob does not move.
     *
     * @param ndcX,ndcY the tapped pixel in the drawn camera's NDC
     * @param out receives the origin fraction; must hold 2 floats
     * @return true if it resolved; false only when the cloud or its frame is not
     *         here yet, in which case {@code out} is untouched
     */
    boolean tapOrigin(float ndcX, float ndcY, float[] out) {
        if (!isReady() || tapProj0 == 0f || tapProj5 == 0f) return false;
        float sourceNdcX = ndcX - tapShiftX;
        float sourceNdcY = ndcY - tapShiftY;
        splatCamera.originForRay(pos, count,
                sourceNdcX / tapProj0, -sourceNdcY / tapProj5,
                refDepth, depthMulInUse, tapProj0, tapProj5, out);
        return true;
    }

    /** A new EGL context: every name this class cached is meaningless now. */
    void onContextLost() {
        prog = 0;
        vboCorner = 0;
        texData = 0;
        texOrder = 0;
        fbo = 0;
        fboColour = 0;
        fboDepth = 0;
        fboB = 0;
        fboBColour = 0;
        blurProg = 0;
        renderW = 1;
        renderH = 1;
        scaleInUse = 1f;
        offscreenActive = false;
        blurActive = false;
        ready = false;
        sortPrimed = false;
        sortEpsStale = true;
        timerExt = false;
        timerQuery = 0;
        queryOpen = false;
        queryInFlight = false;
        count = 0;
        refDepth = SOURCE_TARGET_Z;
    }

    /**
     * Upload a parsed cloud, replacing whatever was on the GPU.
     *
     * <p><b>A GL name of 0 is never a success</b> — the rule the relief's
     * {@code install} learned the hard way. If the program or any texture fails to
     * come up, nothing is recorded as ready and the host falls back to the relief
     * rather than drawing into a context that holds no data.
     *
     * @return true when the cloud is drawable
     */
    boolean upload(SplatCloud cloud) {
        releaseTextures();
        ready = false;
        count = 0;
        alphaDropped = 0;
        alphaShrank = 0;
        alphaMeanExtent = 1f;
        if (cloud == null || cloud.count == 0) return false;
        // A queued upload can run before this GL thread has a current context (the
        // platform creates the engine's context lazily). Every GL entry point is
        // then a no-op and glCreateShader answers 0 -- the empty-log "compile
        // failed" seen on device -- so refuse here rather than build against
        // nothing. The caller retries on the next resolve, and no state that says
        // "this is on screen" is written.
        //
        // The probe is the EGL context, not glGetString: glGetString with no
        // current context does not answer null on every driver -- on the Mali
        // this app ships to it dereferences the absent dispatch table and takes
        // the whole process down (measured: SIGSEGV in glGetString+44, from
        // SplatRenderer.upload, on the studio's first splat load). EGL14 answers
        // the question directly and touches no GL state.
        if (android.opengl.EGL14.eglGetCurrentContext()
                == android.opengl.EGL14.EGL_NO_CONTEXT) return false;

        if (prog == 0) buildProgram();
        if (prog == 0) return false;
        if (vboCorner == 0) buildCorner();
        if (vboCorner == 0) return false;

        int n = cloud.count;
        dataRows = ceilRows(n * 2, DATA_W);
        orderRows = ceilRows(n, ORDER_W);

        int[] v = new int[2];
        GLES30.glGenTextures(2, v, 0);
        texData = v[0];
        texOrder = v[1];
        if (texData == 0 || texOrder == 0) return false;

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texData);
        // TIMED, and the three phases are separate on purpose: the buffer packing and
        // the position mirror are CPU work this thread does not have to do -- the file
        // read already happens on the Io executor -- while the glTexImage2D below is
        // the driver's own upload and cannot be moved. Which of them the stall is made
        // of decides whether the fix is to prepare the buffers elsewhere or to make the
        // texture smaller, and they are different changes.
        long tPack0 = System.nanoTime();
        // Whole rows, zero-padded: glTexImage2D reads DATA_W * dataRows * 16
        // bytes, and a truncated file's raw buffer can be shorter than that.
        ByteBuffer padded = ByteBuffer.allocateDirect(dataRows * DATA_W * 16)
                .order(ByteOrder.nativeOrder());
        padded.put(cloud.raw(), 0, Math.min(cloud.raw().length, n * 32));
        padded.position(0);
        long tPack1 = System.nanoTime();
        // UNSIGNED INTEGER, not float: the record's bytes ARE the texel. A float
        // texture makes the packed colour/quaternion a NaN for every bright splat
        // and the driver's floatBitsToUint then returns a canonical NaN -- the
        // bright-region speckle. See VERT's uData note.
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32UI, DATA_W, dataRows, 0,
                GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, padded);
        long tGl = System.nanoTime();
        nearest(texData);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOrder);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32UI, ORDER_W, orderRows, 0,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, null);
        nearest(texOrder);

        // The CPU mirror the sort reads. Positions only: the depth pass walks
        // them once per camera move, and the GPU reads everything else.
        pos = new float[n * 3];
        cloud.positions(pos);
        // The gate's bound is O(count) and belongs to this cloud: mark it stale so
        // the next draw derives it for whatever framing is then in force.
        sortEpsStale = true;
        long tMirror = System.nanoTime();
        // The alpha-extent clip's reach: a property of the cloud's baked opacities,
        // so it is derived once here and read by the stats line. Splat.extentScaleForAlpha
        // is the mapping the vertex shader mirrors, so this counts exactly the splats
        // the shader will drop (every fragment discarded) or shrink.
        int[] col = new int[4];
        int dropped = 0, shrank = 0;
        double extentSum = 0.0;
        for (int i = 0; i < n; i++) {
            cloud.color(i, col);
            float s = Splat.extentScaleForAlpha(col[3] / 255f);
            extentSum += s;
            if (s <= 0f) dropped++;
            else if (s < 1f) shrank++;
        }
        alphaDropped = dropped;
        alphaShrank = shrank;
        alphaMeanExtent = (float) (extentSum / n);
        long tAlpha = System.nanoTime();
        android.util.Log.i(TAG, "splat upload: n=" + n
                + " pack=" + (tPack1 - tPack0) / 1e6f + "ms"
                + " glData=" + (tGl - tPack1) / 1e6f + "ms"
                + " mirror=" + (tMirror - tGl) / 1e6f + "ms"
                + " alpha=" + (tAlpha - tMirror) / 1e6f + "ms"
                + " total=" + (tAlpha - tPack0) / 1e6f + "ms");
        // The cloud's own centre, for the pivot fallback. `medianDepth` is warmed
        // on the IO thread (see ReliefRenderer.requestSplat), so this is a read of a
        // memo rather than a sort on the GL thread; a cloud that was not warmed
        // computes it here, which is why it is memoised at all.
        refDepth = cloud.medianDepth() > 0f ? cloud.medianDepth() : SOURCE_TARGET_Z;
        depths = new float[n];
        order = new int[n];
        counts = new int[SORT_BUCKETS + 1];

        count = n;
        ready = true;
        sortPrimed = false;
        sortedTiltX = Float.NaN;
        sortedTiltY = Float.NaN;
        sortedZoom = Float.NaN;
        sortedOrbitX = Float.NaN;
        sortedOrbitY = Float.NaN;
        lastFrameNanos = System.nanoTime();
        windowStartNanos = 0;
        framesInWindow = 0;
        sortsInWindow = 0;
        maxFrameNanos = 0;
        probePending = true;
        probeCloud = cloud;
        return true;
    }

    /** Drop the GPU side of the current cloud, keeping the program. */
    void releaseTextures() {
        if (texData != 0 || texOrder != 0) {
            int[] v = new int[2];
            v[0] = texData;
            v[1] = texOrder;
            GLES30.glDeleteTextures(2, v, 0);
        }
        texData = 0;
        texOrder = 0;
        releaseOffscreen();
        refDepth = SOURCE_TARGET_Z;
        ready = false;
    }

    /** Free everything; called when the host stops drawing splats. */
    void release() {
        releaseTextures();
        if (prog != 0) {
            GLES30.glDeleteProgram(prog);
            prog = 0;
        }
        if (blurProg != 0) {
            GLES30.glDeleteProgram(blurProg);
            blurProg = 0;
        }
        if (vboCorner != 0) {
            int[] v = new int[1];
            v[0] = vboCorner;
            GLES30.glDeleteBuffers(1, v, 0);
            vboCorner = 0;
        }
    }

    /**
     * Draw one frame.
     *
     * <p>{@code tiltX}/{@code tiltY} are already smoothed by the host (the same
     * values {@link CameraModel#frame} is given), and {@code motion} scales them
     * so Motion 0 parks the camera exactly as it parks the relief's. Origin and
     * Pan are the relief's own values, meaning the same thing here: the point the
     * orbit turns about, and a screen-pixel view offset.
     *
     * @param zoom the framing's zoom in [0.25, 2]; 1 is the source view
     * @param cloudLens the cloud's own lens, as {@code focal / height}, or 0 for
     *                  unknown. See {@link com.amoshydra.basrelief.core.store.LibraryItem#splatLens};
     *                  0 draws at {@link #SOURCE_FOVY}, the demo cloud's lens.
     * @param eps2d the splat Size: the 2D low-pass floor as a FRACTION OF THE
     *              CELL, clamped through {@link Splat#clampEps2d} so a bad blob
     *              cannot reach the shader, and resolved into render pixels by
     *              {@link Splat#eps2dFor} -- {@code fraction * cell}, with no
     *              separate minimum above it. {@link Splat#EPS_2D_DEFAULT} is the
     *              shipped value.
     * @param blurPx the splat Blur: a separable post-blur radius in render-target
     *               pixels, clamped through {@link Splat#clampBlurPx}; 0 is the
     *               identity and skips both passes entirely.
     * @param depthMul the splat Depth: a multiplier on the cloud's depth, clamped
     *                 through {@link Splat#clampDepthMul} and applied in the vertex
     *                 shader as {@link Splat#depthScale} on each splat's position AND
     *                 size. 1 is the node's own depth, 0 collapses the cloud onto the
     *                 reference plane (the flat photograph). At rest the picture is
     *                 the photograph at every value; only the parallax changes. The
     *                 CPU sort still reads the node's own positions, and that is
     *                 consistent: the scale is monotonic in depth, so it cannot
     *                 reorder the cloud. The PIVOT, by contrast, is resolved on the
     *                 moved depth -- see {@link SplatCamera#pivot} -- because it is
     *                 the surface the shader draws, so the crosshair and the aim
     *                 follow the knob rather than marking a point the cloud has left.
     * @return the camera frame this draw used, or null when nothing was drawn — so
     *         the host can place its orbit marker at the SAME pivot the cloud was
     *         aimed at. The frame is the one reusable instance, valid until the next
     *         call; the host consumes it immediately, in the same GL event.
     */
    SplatCamera.Frame draw(float surfaceWf, float surfaceHf, float tiltX, float tiltY, float motion,
              float zoom, float orbitX, float orbitY, float panX, float panY,
              float brightnessGain, float shadowLift, float renderScale, float cloudLens,
              float eps2d, float blurPx, float depthMul) {
        if (!isReady() || surfaceWf <= 0f || surfaceHf <= 0f) return null;
        int surfaceW = Math.max(1, Math.round(surfaceWf));
        int surfaceH = Math.max(1, Math.round(surfaceHf));
        long t0 = System.nanoTime();

        // Clamped here as well as at the pref read, because the renderer is the one
        // place both the dev slider and the cold reload converge and a value that
        // reached the shader unclamped would be a state no test can name.
        // The knob stores a FRACTION OF THE CELL, not pixels; the viewport turns it
        // into the pixels the shader works in, below. See Splat.eps2dFor for why a
        // fixed number of pixels cannot be the setting on its own, and why there is
        // deliberately no separate sampling minimum above the fraction.
        eps2d = Splat.clampEps2d(eps2d);
        blurPx = Splat.clampBlurPx(blurPx);
        boolean blur = blurPx >= 0.5f;
        blurPxInUse = blurPx;
        // The dev knob is clamped here as well as at the pref read, for the same
        // reason: the renderer is where the slider and the cold reload converge.
        depthMul = Splat.clampDepthMul(depthMul);
        depthMulInUse = depthMul;

        ensureOffscreen(surfaceW, surfaceH, renderScale, blur);
        int vw = fbo != 0 ? renderW : surfaceW;
        int vh = fbo != 0 ? renderH : surfaceH;
        eps2dInUse = Splat.eps2dFor(eps2d, vw, vh, count, zoom);

        // The cloud's OWN lens, not the demo's. The row stores the dimensionless
        // focal/height the node measured in its sidecar, and a Gaussian's screen
        // size is proportional to the focal it was reconstructed through -- so the
        // bale's constant was drawing every cloud's splats at the wrong size. 0 is
        // "unknown" and keeps that constant, which is what every cloud generated
        // before the lens was stored gets: those keep exactly the look they have.
        float lensFovy = Splat.fovyForFocalOverHeight(cloudLens);
        float sourceFovy = lensFovy > 0f ? lensFovy : SOURCE_FOVY;
        float fovy = sourceFovy / Math.max(0.25f, Math.min(2f, zoom));
        fovy = Math.max(MIN_FOVY, Math.min(MAX_FOVY, fovy));

        // The camera, all of it in :core: the source view, the Origin/Pan shift,
        // and the turntable about the cloud's own surface under the origin. See
        // SplatCamera for why the origin is a shift and not a lateral move.
        // The Depth knob is handed to the camera as well as the shader: the pivot
        // is the surface the shader DRAWS, so it must be resolved on the moved
        // depth or the crosshair and the orbit stay at the old height.
        SplatCamera.Frame cam = splatCamera.frame(surfaceW, surfaceH, fovy,
                orbitX, orbitY, panX, panY, tiltX, tiltY, motion,
                pos, count, refDepth, depthMulInUse);
        System.arraycopy(cam.view, 0, view, 0, 16);
        // uProj gets the PANNED projection: the shift is what makes the at-rest
        // picture the photograph moved by Origin and Pan, at every depth.
        System.arraycopy(cam.projPanned, 0, proj, 0, 16);
        // The frame a later tap resolves against, from the frame this draw used. The
        // projection factors are the base perspective's (the shift is a clip-space
        // translation and does not change them); the shift is the Origin + Pan shift
        // the tap must undo to reach the SOURCE camera's ray. See tapOrigin.
        tapProj0 = cam.proj[0];
        tapProj5 = cam.proj[5];
        tapShiftX = cam.shiftX;
        tapShiftY = cam.shiftY;
        eye[0] = cam.eyeX; eye[1] = cam.eyeY; eye[2] = cam.eyeZ;
        forward[0] = cam.forwardX; forward[1] = cam.forwardY; forward[2] = cam.forwardZ;
        camFocalX = proj[0];
        camFocalY = proj[5];
        // The world-to-view rotation, in the layout GLSL's mat3 wants: the array
        // uploaded with transpose=false becomes the mat3 column by column, so
        // arr[0..2] must be V's column 0 — which, from the view matrix's
        // column-major storage, is (view[0], view[1], view[2]). The shader then
        // has V (world to view) and computes SigmaV = V Sigma3D V^T.
        viewRot[0] = view[0]; viewRot[1] = view[1]; viewRot[2] = view[2];
        viewRot[3] = view[4]; viewRot[4] = view[5]; viewRot[5] = view[6];
        viewRot[6] = view[8]; viewRot[7] = view[9]; viewRot[8] = view[10];

        // The sort gate's live threshold, from the frame this draw will use. The
        // camera has not moved if the tilt, orbit and zoom are all within it, so the
        // order the last sort derived is still the order to draw. The threshold is
        // this framing's own sub-pixel bound, floored at the constant it replaces so
        // it can only ever loosen (see SplatCamera.SHIPPED_SORT_EPS).
        //
        // The bound is a max over every splat and so is O(count); the tilt changes
        // none of its inputs, so it is derived once per FRAMING and reused for every
        // frame of a tilt. The key is exactly the set of values it is a function of.
        if (sortEpsStale || surfaceW != boundSurfaceW || surfaceH != boundSurfaceH
                || motion != boundMotion || cam.pivotX != boundPivotX
                || cam.pivotY != boundPivotY || cam.pivotZ != boundPivotZ
                || cam.proj[0] != boundProj0 || cam.proj[5] != boundProj5
                || cam.shiftX != boundShiftX || cam.shiftY != boundShiftY) {
            sortEpsInUse = splatCamera.sortEps(surfaceW, surfaceH, motion, cam, pos, count);
            boundSurfaceW = surfaceW;
            boundSurfaceH = surfaceH;
            boundMotion = motion;
            boundPivotX = cam.pivotX;
            boundPivotY = cam.pivotY;
            boundPivotZ = cam.pivotZ;
            boundProj0 = cam.proj[0];
            boundProj5 = cam.proj[5];
            boundShiftX = cam.shiftX;
            boundShiftY = cam.shiftY;
            sortEpsStale = false;
        }

        if (needsSort(tiltX, tiltY, orbitX, orbitY, zoom, sortEpsInUse)) {
            sort();
            sortedTiltX = tiltX;
            sortedTiltY = tiltY;
            sortedOrbitX = orbitX;
            sortedOrbitY = orbitY;
            sortedZoom = zoom;
            sortPrimed = true;
        }
        uploadOrder();

        // The on-device half of the projection test, once per load, now that the
        // camera and the viewport the shader uses are both in hand.
        if (probePending) {
            probePending = false;
            logProjectionProbe(probeCloud, vw, vh);
            probeCloud = null;
        }

        pollGpuTimer();
        long d0 = System.nanoTime();
        beginGpuTimer();

        if (fbo != 0) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glViewport(0, 0, vw, vh);
        GLES30.glClearColor(0.043f, 0.043f, 0.047f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(prog);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texData);
        GLES30.glUniform1i(uData, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOrder);
        GLES30.glUniform1i(uOrder, 1);
        GLES30.glUniform1i(uDataW, DATA_W);
        GLES30.glUniform1i(uOrderW, ORDER_W);
        GLES30.glUniform1f(uNear, NEAR);
        GLES30.glUniform1f(uFudge, 1f);
        GLES30.glUniform1f(uEps2d, eps2dInUse);
        // The alpha discard threshold: ONE number, from Splat.ALPHA_DISCARD, that both
        // stages read -- the fragment stage's discard and the vertex stage's quad-extent
        // clip (Splat.extentScaleForAlpha). Uploaded rather than written into the shader
        // so the two cannot drift apart.
        GLES30.glUniform1f(uAlphaDiscard, Splat.ALPHA_DISCARD);
        // The depth multiplier and the plane it scales about. The plane is the
        // cloud's own median depth (the pivot's fallback), so 0 flattens onto the
        // cloud's centre rather than onto the camera; the shader mirrors
        // Splat.depthScale, which the test pins the identity of.
        GLES30.glUniform1f(uDepthMul, depthMulInUse);
        GLES30.glUniform1f(uDepthRef, refDepth);
        GLES30.glUniform1f(uBrightnessGain, brightnessGain);
        GLES30.glUniform1f(uShadowLift, shadowLift);
        GLES30.glUniform2f(uFocal, 0.5f * vw * proj[0], 0.5f * vh * proj[5]);
        GLES30.glUniform2f(uViewport, vw, vh);
        GLES30.glUniformMatrix4fv(uProj, 1, false, proj, 0);
        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0);
        GLES30.glUniformMatrix3fv(uViewRot, 1, false, viewRot, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboCorner);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, count);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glDisable(GLES30.GL_BLEND);

        if (fbo != 0) {
            // The dev BLUR knob, when on: horizontal fbo -> fboB, vertical fboB ->
            // fbo, then the copy up. Both passes are skipped at radius 0, which is
            // why the identity costs nothing at all.
            if (blur && fboB != 0 && ensureBlurProgram()) {
                blurPass(fboB, fboColour, vw, vh, 1f, 0f, blurPx);
                blurPass(fbo, fboBColour, vw, vh, 0f, 1f, blurPx);
            }
            // The upscale to the surface is a DRAWN copy, not a scaled
            // glBlitFramebuffer. That blit was the only call in the path whose
            // behaviour on a SCALED source is driver-dependent, and it is what the
            // two blank frames had in common: the offscreen path is reached by a
            // render scale below 100% and by the blur, and by nothing else, and both
            // came back black while the same draw straight to the surface was fine.
            // A fullscreen triangle sampling the colour texture is the operation the
            // blur already performs, at radius 0, where the kernel is exactly a copy
            // -- so this needs no new program and no new buffer. At radius 0 BLUR_FRAG
            // now early-outs to a single fetch (see its own note); before that it ran
            // the whole 13-tap loop with twelve weights of exactly zero, which was
            // 13 fetches per pixel over the whole surface for a copy. The blit is kept
            // as the fallback for a driver where the blur program will not build, so
            // the worst case is the behaviour that shipped.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (ensureBlurProgram()) {
                blurPass(0, fboColour, surfaceW, surfaceH, 1f, 0f, 0f);
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo);
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                GLES30.glBlitFramebuffer(0, 0, renderW, renderH, 0, 0, surfaceW, surfaceH,
                        GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }
        endGpuTimer();

        long now = System.nanoTime();
        long dt = now - lastFrameNanos;
        lastFrameNanos = now;
        if (dt > maxFrameNanos) maxFrameNanos = dt;
        drawNanosInWindow += now - d0;
        framesInWindow++;
        if (STATS) logStats(now, surfaceW, surfaceH);
        return cam;
    }

    /**
     * Depth-sort buckets: the cloud's whole depth range is quantised into this many
     * bins and the draw order is back-to-front by bin. Within a bin the order is
     * arbitrary, because a counting sort assigns no finer key than the bin.
     *
     * <p><b>The bins are why a background splat can paint over a foreground one.</b>
     * These splats are effectively opaque (alpha ~1), so the LAST one drawn wins;
     * where a bin is wider than a splat, the splats sharing it are ordered
     * arbitrarily and a background splat can land on top of a foreground one. At
     * 8192 over a whole cloud the bins are wide. Measured on the actual cloud files:
     * the beach cloud puts <b>53%</b> of its splats in buckets wider than themselves,
     * the user's cloud <b>3.5%</b> -- but those are the big, near splats, whose
     * mis-ordering covers many pixels. The user sees it as white noise / white stuff
     * on the sky, a face and a wall, and it does NOT go away with density (a
     * 1,200,000-splat cloud still shows it), which is what rules out the screen-door
     * explanation.
     *
     * <p><b>This is a test of the hypothesis as much as a fix.</b> A reference
     * renderer that sorts exactly is clean, which is the strongest evidence that the
     * ordering is the difference -- but whether raising the bucket count is enough to
     * clear it on a phone is for the user's eye, not for this comment. The cost is
     * the {@link java.util.Arrays#fill} of the count array and the prefix sum over it,
     * both over ints, so roughly a millisecond more per sort. The mapping is
     * deliberately left linear ({@code (SORT_BUCKETS - 1) - (depths[i] - dmin) *
     * scale}); a log-depth mapping is the likely follow-up, one change at a time.
     */
    private static final int SORT_BUCKETS = 262144;

    // ------------------------------------------------------------------ sort

    /**
     * Whether the draw order the last sort derived is still the order to draw.
     *
     * <p><b>The tilt axes use the live bound; orbit and zoom keep the constant.</b>
     * Tilt is the axis the gyro drives continuously -- the one the measurement and
     * the derivation cover -- so it is the one whose gate moves. Orbit and zoom are
     * deliberate gestures that change the pivot and the field of view, and the
     * constant they keep is already ~1 px of picture movement (an Origin delta of
     * {@code d} shifts the drawing by {@code 2d} NDC, i.e. {@code d * surfaceW}
     * pixels), so they are not the cost this change is about.
     *
     * <p>{@code tiltEps} is never below {@link SplatCamera#SHIPPED_SORT_EPS} -- see
     * there for why the gate may only loosen -- so no path through here can sort
     * more often than the constant did.
     */
    private boolean needsSort(float tiltX, float tiltY, float orbitX, float orbitY,
                              float zoom, float tiltEps) {
        if (!sortPrimed) return true;
        return Math.abs(tiltX - sortedTiltX) > tiltEps
                || Math.abs(tiltY - sortedTiltY) > tiltEps
                || Math.abs(orbitX - sortedOrbitX) > 1e-3f
                || Math.abs(orbitY - sortedOrbitY) > 1e-3f
                || Math.abs(zoom - sortedZoom) > 1e-3f;
    }

    /**
     * Back-to-front counting sort, on the GL thread, only when the camera moved.
     *
     * <p>The draw order is the whole reason the mode is sorted rather than
     * depth-tested: alpha blending is not commutative, and a splat cloud has no
     * opaque surface to write depth against. A camera that has not moved has the
     * same order it had last frame, and re-deriving it is pure waste — which is
     * what the spike's every-frame schedule did.
     *
     * <p>The depth pass also counts the splats whose projected centre is well
     * outside the viewport, in the same loop and with a division-free test: the
     * shader culls on the quad's bounding box, so this is a lower bound on what
     * the GPU drops, reported in the stats.
     */
    private void sort() {
        long t0 = System.nanoTime();
        float fx = forward[0], fy = forward[1], fz = forward[2];
        float ex = eye[0], ey = eye[1], ez = eye[2];
        // The camera axes, from the world-to-view rotation V (row-major). viewRot
        // is stored in GLSL's column-major order, so V's row 0 (right) is
        // (viewRot[0], viewRot[3], viewRot[6]) and row 1 (up) is
        // (viewRot[1], viewRot[4], viewRot[7]).
        float rx = viewRot[0], ry = viewRot[3], rz = viewRot[6];
        float ux = viewRot[1], uy = viewRot[4], uz = viewRot[7];
        float fxl = camFocalX, fyl = camFocalY;
        int culled = 0;
        float dmin = Float.MAX_VALUE, dmax = -Float.MAX_VALUE;
        for (int i = 0, j = 0; i < count; i++, j += 3) {
            float px = pos[j] - ex, py = pos[j + 1] - ey, pz = pos[j + 2] - ez;
            float v = fx * px + fy * py + fz * pz;
            depths[i] = v;
            if (v < dmin) dmin = v;
            if (v > dmax) dmax = v;
            // The shader culls on the quad's bounding box; this is the same test
            // on the projected CENTRE, so it is a lower bound on what the GPU
            // drops. |ndc| > margin <=> |axis . p| * projFactor > margin * v,
            // which avoids a division per splat.
            if (v > 0.01f) {
                float cx = rx * px + ry * py + rz * pz;
                float cy = ux * px + uy * py + uz * pz;
                float lim = CULL_MARGIN * v;
                if (Math.abs(cx) * fxl > lim || Math.abs(cy) * fyl > lim) culled++;
            }
        }
        lastCulled = culled;
        float span = dmax - dmin > 1e-6f ? dmax - dmin : 1e-6f;
        float scale = (SORT_BUCKETS - 1) / span;
        int[] cnt = counts;
        Arrays.fill(cnt, 0);
        for (int i = 0; i < count; i++) {
            int b = (SORT_BUCKETS - 1) - (int) ((depths[i] - dmin) * scale);
            if (b < 0) b = 0;
            if (b >= SORT_BUCKETS) b = SORT_BUCKETS - 1;
            cnt[b + 1]++;
        }
        for (int b = 1; b <= SORT_BUCKETS; b++) cnt[b] += cnt[b - 1];
        int[] ord = order;
        for (int i = 0; i < count; i++) {
            int b = (SORT_BUCKETS - 1) - (int) ((depths[i] - dmin) * scale);
            if (b < 0) b = 0;
            if (b >= SORT_BUCKETS) b = SORT_BUCKETS - 1;
            ord[cnt[b]++] = i;
        }
        sortNanosInWindow += System.nanoTime() - t0;
        sortsInWindow++;
    }

    private void uploadOrder() {
        if (orderBuf == null || orderBuf.capacity() < count) {
            orderBuf = ByteBuffer.allocateDirect(ceilRows(Math.max(count, 1), ORDER_W)
                    * ORDER_W * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        }
        orderBuf.clear();
        orderBuf.put(order, 0, count);
        orderBuf.position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOrder);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, ORDER_W, ceilRows(count, ORDER_W),
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, orderBuf);
    }

    // ------------------------------------------------------------- offscreen

    private void ensureOffscreen(int surfaceW, int surfaceH, float scale, boolean blur) {
        int rw = Math.max(1, Math.round(surfaceW * scale));
        int rh = Math.max(1, Math.round(surfaceH * scale));
        // The blur needs a texture to sample, so it forces the FBO path even at
        // render scale 1 -- the one case that otherwise draws straight to the
        // surface. `blurActive` records what was ASKED, not whether fboB came up, so
        // a target that fails to build does not re-allocate every frame; the draw
        // checks fboB itself before using it.
        boolean wantFbo = scale < 0.999f || blur;
        boolean unchanged = rw == renderW && rh == renderH
                && Math.abs(scale - scaleInUse) < 1e-4f
                && offscreenActive == wantFbo && blurActive == blur;
        if (unchanged && (!wantFbo || fbo != 0)) return;
        releaseOffscreen();
        scaleInUse = scale;
        renderW = rw;
        renderH = rh;
        offscreenActive = wantFbo;
        blurActive = blur;
        if (!wantFbo) return;      // 1.0 draws straight to the surface
        int[] v = new int[1];
        GLES30.glGenFramebuffers(1, v, 0);
        fbo = v[0];
        GLES30.glGenTextures(1, v, 0);
        fboColour = v[0];
        GLES30.glGenRenderbuffers(1, v, 0);
        fboDepth = v[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboColour);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, rw, rh, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        // LINEAR, not NEAREST: the blur samples this texture, and a nearest tap at
        // the fractional offsets the Gaussian uses would quantise the kernel back
        // toward a box. Nothing else samples it -- the blit's filter is its own
        // argument -- so this changes only the blur.
        linear(fboColour);
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, fboDepth);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, rw, rh);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboColour, 0);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, fboDepth);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            // An incomplete target is a black frame; fall back to the surface.
            android.util.Log.w(TAG, "splat offscreen incomplete (" + st + "), drawing direct");
            releaseOffscreen();
            renderW = surfaceW;
            renderH = surfaceH;
            scaleInUse = 1f;
            return;
        }
        if (!blur) return;
        // The blur's ping-pong target. No depth: it is a colour-only pass. A failure
        // here is not fatal -- the draw skips the blur and shows the picture -- which
        // is the right way round, because a missing pass is soft and a half-built
        // one is black.
        GLES30.glGenFramebuffers(1, v, 0);
        fboB = v[0];
        GLES30.glGenTextures(1, v, 0);
        fboBColour = v[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboBColour);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, rw, rh, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        linear(fboBColour);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboBColour, 0);
        int stB = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (stB != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.w(TAG, "splat blur target incomplete (" + stB + "), blur off");
            int[] del = new int[1];
            del[0] = fboB;
            GLES30.glDeleteFramebuffers(1, del, 0);
            del[0] = fboBColour;
            GLES30.glDeleteTextures(1, del, 0);
            fboB = 0;
            fboBColour = 0;
        }
    }

    /**
     * One axis of the separable blur, into {@code target}, sampling {@code srcTex}.
     *
     * <p>Fullscreen triangle, no attribute, no buffer: the vertex shader derives it
     * from {@code gl_VertexID}. The caller has already guaranteed a program and a
     * source texture, so this does no error handling of its own -- see {@link #draw},
     * which only calls it when {@link #ensureBlurProgram} answered true and
     * {@code fboB} exists.
     */
    private void blurPass(int target, int srcTex, int w, int h,
                          float dx, float dy, float radius) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        // The blur reads no attribute; the cards' array 0 is left disabled so a
        // driver that fetches anyway cannot read a buffer this pass never binds.
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glUseProgram(blurProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glUniform1i(uBlurTex, 0);
        GLES30.glUniform2f(uBlurTexel, 1f / w, 1f / h);
        GLES30.glUniform2f(uBlurDir, dx, dy);
        GLES30.glUniform1f(uBlurRadius, radius);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    /** Build the blur program on first use. False when it cannot come up. */
    private boolean ensureBlurProgram() {
        if (blurProg != 0) return true;
        int vs = compile(GLES30.GL_VERTEX_SHADER, BLUR_VERT);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, BLUR_FRAG);
        if (vs == 0 || fs == 0) return false;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs);
        GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "splat blur link failed: " + GLES30.glGetProgramInfoLog(p));
            GLES30.glDeleteProgram(p);
            return false;
        }
        blurProg = p;
        uBlurTex = GLES30.glGetUniformLocation(blurProg, "uBlurTex");
        uBlurTexel = GLES30.glGetUniformLocation(blurProg, "uBlurTexel");
        uBlurDir = GLES30.glGetUniformLocation(blurProg, "uBlurDir");
        uBlurRadius = GLES30.glGetUniformLocation(blurProg, "uBlurRadius");
        for (int loc : new int[] { uBlurTex, uBlurTexel, uBlurDir, uBlurRadius }) {
            if (loc < 0) android.util.Log.e(TAG, "splat blur uniform dropped in silence");
        }
        return true;
    }

    private void releaseOffscreen() {
        if (fbo != 0) {
            int[] v = new int[1];
            v[0] = fbo;
            GLES30.glDeleteFramebuffers(1, v, 0);
            v[0] = fboColour;
            GLES30.glDeleteTextures(1, v, 0);
            v[0] = fboDepth;
            GLES30.glDeleteRenderbuffers(1, v, 0);
        }
        if (fboB != 0) {
            int[] v = new int[1];
            v[0] = fboB;
            GLES30.glDeleteFramebuffers(1, v, 0);
            v[0] = fboBColour;
            GLES30.glDeleteTextures(1, v, 0);
        }
        fbo = 0;
        fboColour = 0;
        fboDepth = 0;
        fboB = 0;
        fboBColour = 0;
        offscreenActive = false;
        blurActive = false;
    }

    private static int ceilRows(int count, int width) {
        return (count + width - 1) / width;
    }

    private void nearest(int tex) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    /** A colour target the blur samples: linear both ways, clamped at the edge. */
    private void linear(int tex) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    // -------------------------------------------------------------- program

    private void buildProgram() {
        int vs = compile(GLES30.GL_VERTEX_SHADER, VERT);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG);
        if (vs == 0 || fs == 0) return;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs);
        GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "splat program link failed: "
                    + GLES30.glGetProgramInfoLog(p));
            GLES30.glDeleteProgram(p);
            return;
        }
        prog = p;
        uProj = GLES30.glGetUniformLocation(prog, "uProj");
        uView = GLES30.glGetUniformLocation(prog, "uView");
        uViewRot = GLES30.glGetUniformLocation(prog, "uViewRot");
        uData = GLES30.glGetUniformLocation(prog, "uData");
        uOrder = GLES30.glGetUniformLocation(prog, "uOrder");
        uDataW = GLES30.glGetUniformLocation(prog, "uDataW");
        uOrderW = GLES30.glGetUniformLocation(prog, "uOrderW");
        uNear = GLES30.glGetUniformLocation(prog, "uNear");
        uFudge = GLES30.glGetUniformLocation(prog, "uFudge");
        uEps2d = GLES30.glGetUniformLocation(prog, "uEps2d");
        uAlphaDiscard = GLES30.glGetUniformLocation(prog, "uAlphaDiscard");
        uDepthMul = GLES30.glGetUniformLocation(prog, "uDepthMul");
        uDepthRef = GLES30.glGetUniformLocation(prog, "uDepthRef");
        uFocal = GLES30.glGetUniformLocation(prog, "uFocal");
        uViewport = GLES30.glGetUniformLocation(prog, "uViewport");
        uBrightnessGain = GLES30.glGetUniformLocation(prog, "uBrightnessGain");
        uShadowLift = GLES30.glGetUniformLocation(prog, "uShadowLift");
        for (int loc : new int[] { uProj, uView, uViewRot, uData, uOrder, uDataW, uOrderW,
                uNear, uFudge, uEps2d, uAlphaDiscard, uDepthMul, uDepthRef, uFocal, uViewport,
                uBrightnessGain, uShadowLift }) {
            if (loc < 0) android.util.Log.e(TAG, "splat uniform dropped in silence");
        }
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            // The shader's own name and the object name, because a compile that
            // fails with an EMPTY info log is not a source error: it is a call made
            // with no current context, where every GL entry point is a no-op and
            // glCreateShader answers 0.
            android.util.Log.e(TAG, "splat shader compile failed type="
                    + (type == GLES30.GL_VERTEX_SHADER ? "VERT" : "FRAG")
                    + " shader=" + s + ": " + GLES30.glGetShaderInfoLog(s));
            if (s != 0) GLES30.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private void buildCorner() {
        FloatBuffer corners = ByteBuffer.allocateDirect(6 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        corners.put(new float[] { -1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1 });
        corners.position(0);
        int[] v = new int[1];
        GLES30.glGenBuffers(1, v, 0);
        vboCorner = v[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboCorner);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 2 * 4, corners, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    // ---------------------------------------------------------------- probe

    /**
     * Run the {@code :core} chain once per load and log the footprint it predicts.
     *
     * <p>The shader cannot call {@link SplatProjection}, but the two must agree: this
     * projects one real splat through the pure functions with the same camera the
     * shader just used and prints the 2-sigma extent in pixels and the exponent at
     * the quad's edge, which must be {@code -4.5} on the principal axis. It is the
     * on-device half of {@code SplatProjectionTest}, and it gives the pure functions
     * a real caller rather than leaving them a tested-but-unused library.
     */
    private void logProjectionProbe(SplatCloud cloud, int vw, int vh) {
        if (count == 0) return;
        int[] q = new int[4];
        cloud.quaternion(0, q);
        float[] s = new float[3];
        cloud.scale(0, s);
        float qw = q[0] / 128f - 1f, qx = q[1] / 128f - 1f;
        float qy = q[2] / 128f - 1f, qz = q[3] / 128f - 1f;
        // viewRot is stored in GLSL's column-major order; the pure functions want
        // the same rotation row-major, so transpose it back.
        float[] v = new float[9];
        v[0] = viewRot[0]; v[1] = viewRot[3]; v[2] = viewRot[6];
        v[3] = viewRot[1]; v[4] = viewRot[4]; v[5] = viewRot[7];
        v[6] = viewRot[2]; v[7] = viewRot[5]; v[8] = viewRot[8];
        float[] p = new float[3];
        cloud.position(0, p);
        // The view-space point: the world offset through V, then the shader's own
        // depth = -mv.z.
        float wx = p[0] - eye[0], wy = p[1] - eye[1], wz = p[2] - eye[2];
        float mx = v[0] * wx + v[1] * wy + v[2] * wz;
        float my = v[3] * wx + v[4] * wy + v[5] * wz;
        float mz = v[6] * wx + v[7] * wy + v[8] * wz;
        float depth = -mz;
        float fx = 0.5f * vw * camFocalX;
        float fy = 0.5f * vh * camFocalY;
        boolean ok = SplatProjection.project(s[0], s[1], s[2], qw, qx, qy, qz,
                v, mx, my, depth, fx, fy, probeCov);
        if (ok && SplatProjection.conic(probeCov[0], probeCov[1], probeCov[2], probeConic)) {
            float[] r = new float[2];
            SplatProjection.extents(probeCov[0], probeCov[1], probeCov[2], 1f, r);
            // The principal axis of the symmetric 2x2: (c01, l1 - c00).
            float l1 = 0.5f * (probeCov[0] + probeCov[2]) + (float) Math.sqrt(Math.max(
                    0.25f * (probeCov[0] - probeCov[2]) * (probeCov[0] - probeCov[2])
                            + probeCov[1] * probeCov[1], 0f));
            float ex = probeCov[1], ey = l1 - probeCov[0];
            float el = (float) Math.sqrt(ex * ex + ey * ey);
            if (el < 1e-9f) { ex = 1f; ey = 0f; } else { ex /= el; ey /= el; }
            float power = SplatProjection.power(probeConic, r[0] * ex, r[0] * ey);
            android.util.Log.i(TAG, "splat probe: splat0 depth=" + fmt(depth)
                    + " r1=" + fmt(r[0]) + " r2=" + fmt(r[1]) + "px"
                    + " powerAtR1=" + fmt(power) + " (want " + fmt(-4.5f) + ")");
        } else {
            android.util.Log.w(TAG, "splat probe: first splat's covariance is degenerate");
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.4f", v);
    }

    // ---------------------------------------------------------------- stats

    private void logStats(long now, int surfaceW, int surfaceH) {
        if (windowStartNanos == 0) {
            windowStartNanos = now;
            lastLogNanos = now;
            return;
        }
        if (now - lastLogNanos < RenderStats.WINDOW_NANOS) return;
        long windowNanos = now - lastLogNanos;
        // ONE computation. The log below prints these locals and the readout reads the
        // snapshot built from them, so the two cannot report two frame rates for one
        // window -- which is the whole reason RenderStats.fps is a :core function.
        float fps = RenderStats.fps(framesInWindow, windowNanos);
        float frameMs = RenderStats.frameMs(fps);
        // The log's own long-standing shape: a window that ran no sort prints 0. The
        // READOUT omits it, because "no sort measured" is not "sort cost 0" -- so the
        // presence check below is the one place the two presentations differ.
        float sortMs = sortsInWindow > 0 ? sortNanosInWindow / 1e6f / sortsInWindow : 0f;
        float drawMs = drawNanosInWindow / 1e6f / Math.max(1, framesInWindow);
        int rw = fbo != 0 ? renderW : surfaceW;
        int rh = fbo != 0 ? renderH : surfaceH;
        float mpx = rw * rh / 1e6f;
        // GPU time per megapixel of the render target: the fill intensity, and the
        // number the next step reads. The renderer is fill-bound, so gpuMs tracks the
        // target's AREA -- this ratio stays flat as the render scale changes and rises
        // with the average splat coverage. The alpha-extent clip is a fill reduction,
        // so its effect shows up here beside alphaShrank/alphaExtent.
        float gpuMsPerMpx = mpx > 0f ? gpuMs / mpx : 0f;
        // The readout's snapshot: the same numbers, with the sort marked ABSENT when
        // none ran rather than carried as a zero.
        perfStats = new RenderStats(fps, frameMs, gpuMs,
                sortsInWindow > 0 ? sortMs : RenderStats.NONE, RenderStats.NONE,
                count, rw, rh);
        android.util.Log.i(TAG, "splat: count=" + count
                + " eps2d=" + fmt(eps2dInUse)
                + " blurPx=" + fmt(blurPxInUse)
                + " depthMul=" + fmt(depthMulInUse)
                + " sortEps=" + fmt(sortEpsInUse)
                + " fps=" + fmt(fps)
                + " frameMs=" + fmt(frameMs)
                + " maxFrameMs=" + fmt(maxFrameNanos / 1e6f)
                + " sortMs=" + fmt(sortMs)
                + " sorts/s=" + sortsInWindow
                + " drawMs=" + fmt(drawMs)
                + " gpuMs=" + fmt(gpuMs)
                + " gpuMsPerMpx=" + fmt(gpuMsPerMpx)
                + " scale=" + fmt(scaleInUse)
                + " render=" + rw + "x" + rh
                + " alphaDrop=" + alphaDropped
                + " alphaShrank=" + alphaShrank
                + " alphaExtent=" + fmt(alphaMeanExtent)
                + " culled=" + lastCulled + "/" + count);
        framesInWindow = 0;
        sortsInWindow = 0;
        sortNanosInWindow = 0;
        drawNanosInWindow = 0;
        maxFrameNanos = 0;
        lastLogNanos = now;
    }

    // --------------------------------------------------------------- timer

    private boolean hasExtension(String name) {
        int[] n = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, n, 0);
        for (int i = 0; i < n[0]; i++) {
            if (name.equals(GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i))) return true;
        }
        return false;
    }

    private void ensureTimer() {
        if (timerExt || timerQuery != 0) return;
        timerExt = hasExtension("GL_EXT_disjoint_timer_query");
        if (timerExt) {
            int[] q = new int[1];
            GLES30.glGenQueries(1, q, 0);
            timerQuery = q[0];
        }
    }

    private void beginGpuTimer() {
        ensureTimer();
        if (!timerExt || queryInFlight || queryOpen) return;
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, timerQuery);
        queryOpen = true;
    }

    private void endGpuTimer() {
        if (!queryOpen) return;
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);
        queryOpen = false;
        queryInFlight = true;
    }

    private void pollGpuTimer() {
        if (!timerExt || !queryInFlight) return;
        GLES30.glGetQueryObjectuiv(timerQuery, GL_QUERY_RESULT_AVAILABLE_EXT, queryOut, 0);
        if (queryOut[0] == 0) return;
        GLES30.glGetQueryObjectuiv(timerQuery, GL_QUERY_RESULT_EXT, queryOut, 0);
        gpuMs = queryOut[0] / 1e6f;
        queryInFlight = false;
    }

    // -------------------------------------------------------------- shaders

    /**
     * The full-covariance projector. Mirrors {@link SplatProjection}: Sigma3D =
     * R S S^T R^T, SigmaV = V Sigma3D V^T, the perspective Jacobian, Sigma2D =
     * J SigmaV J^T, the conic and the eigen extents. One card is six vertices of
     * {@code glDrawArraysInstanced}, and the sample comes from {@code gl_InstanceID}.
     */
    private static final String VERT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2D;\n" +
            "precision highp usampler2D;\n" +
            "\n" +
            // A floor on the 2D covariance, not a blur; see its use below and the
            // class's note. A UNIFORM rather than a const so the dev SIZE knob
            // (Splat.EPS_2D_DEFAULT's home) moves it live on the GL thread with no
            // recompile and no restart.
            "uniform float uEps2d;\n" +
            // The alpha below which the fragment shader discards. ONE number for both
            // stages (Splat.ALPHA_DISCARD, uploaded as a uniform): the fragment stage
            // compares a fragment's alpha against it, and this stage clips the quad to
            // the radius at which that comparison would throw the fragment away -- see
            // the uAlphaDiscard use below and Splat.extentScaleForAlpha, whose test
            // pins that the clipped quad ends exactly where the discard begins.
            "uniform float uAlphaDiscard;\n" +
            // The dev DEPTH knob and the plane it scales about. Mirrors
            // Splat.depthScale, which the :core test pins: 1 is the node's depth,
            // 0 collapses the cloud onto the plane. See the use below for why the
            // scale is per-splat and applies to the size as well as the position.
            "uniform float uDepthMul;\n" +
            "uniform float uDepthRef;\n" +
            "\n" +
            "uniform mat4 uProj;\n" +
            "uniform mat4 uView;\n" +
            "uniform mat3 uViewRot;\n" +
            // An UNSIGNED-INTEGER texture, not a float one, and that is the fix for
            // the bright-region speckle. The record's colour and quaternion are four
            // uint8 each; packing them into an RGBA32F texel makes the float a NaN
            // for every bright splat (alpha >= 128 AND blue >= 128), and a NaN's
            // payload does not survive the round trip on every driver -- Mali hands
            // floatBitsToUint a canonical NaN, so the colour reads as a flat
            // white/grey instead of the bytes. Measured: the sky turned grey and
            // speckled while the tan sand beside it stayed pixel-perfect, and the
            // grey region was exactly the NaN-colour splats. As integers the bytes
            // ARE the texel and nothing can canonicalise them; the position and
            // scale floats come back with uintBitsToFloat, which is exact for the
            // normal floats the node writes.
            "uniform usampler2D uData;\n" +
            "uniform usampler2D uOrder;\n" +
            "uniform int uDataW;\n" +
            "uniform int uOrderW;\n" +
            "uniform float uNear;\n" +
            "uniform float uFudge;\n" +
            "uniform vec2 uFocal;\n" +
            "uniform vec2 uViewport;\n" +
            "\n" +
            "layout(location = 0) in vec2 aCorner;\n" +
            "\n" +
            "out vec4 vColor;\n" +
            "out vec2 vQ;\n" +
            "out vec2 vCenterPx;\n" +
            "out vec3 vConic;\n" +
            "\n" +
            "vec4 unpack_unorm_4x8(uint u) {\n" +
            "  return vec4(u & 0xFFu, (u >> 8) & 0xFFu, (u >> 16) & 0xFFu, (u >> 24) & 0xFFu) * (1.0 / 255.0);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "  uint sp = texelFetch(uOrder, ivec2(gl_InstanceID % uOrderW, gl_InstanceID / uOrderW), 0).x;\n" +
            "  ivec2 c0 = ivec2(int(2u * sp) % uDataW, int(2u * sp) / uDataW);\n" +
            "  ivec2 c1 = ivec2(int(2u * sp + 1u) % uDataW, int(2u * sp + 1u) / uDataW);\n" +
            "  uvec4 A = texelFetch(uData, c0, 0);\n" +
            "  uvec4 B = texelFetch(uData, c1, 0);\n" +
            "\n" +
            "  vec3 spPos = vec3(uintBitsToFloat(A.x), uintBitsToFloat(A.y), uintBitsToFloat(A.z));\n" +
            "  vec3 spScale = vec3(uintBitsToFloat(A.w), uintBitsToFloat(B.x), uintBitsToFloat(B.y));\n" +
            "  vec4 spCol = unpack_unorm_4x8(B.z);\n" +
            "  vec4 q4 = unpack_unorm_4x8(B.w) * 2.0 - 1.0;\n" +
            "\n" +
            // THE DEV DEPTH KNOB. A splat sits on the ray from the eye through its
            // own image pixel, at its own depth, so moving it ALONG that ray changes
            // the depth and leaves the photograph alone: scaling the whole vector by
            // (ref + mul*(z - ref)) / z keeps it on the ray, and the same factor on
            // its size keeps its angular size its footprint's at every depth. So at
            // rest the picture is the photograph at every multiplier and only the
            // parallax under tilt changes; 0 lands every splat on the reference
            // plane, which is the flat photograph. Mirrors Splat.depthScale, whose
            // test pins the identity at 1 and the ray invariance.
            //
            // The max(..., 0) floors the moved depth: a multiplier above 1 can push
            // a near splat past the eye, and a splat there would render mirrored --
            // floored, it collapses to the eye and the near cull below drops it.
            "  float zr = uDepthRef;\n" +
            "  float z2 = zr + uDepthMul * (spPos.z - zr);\n" +
            "  float dscl = spPos.z > 1e-4 ? max(z2, 0.0) / spPos.z : 1.0;\n" +
            "  spPos *= dscl;\n" +
            "  spScale *= dscl;\n" +
            "\n" +
            "  vec4 mv = uView * vec4(spPos, 1.0);\n" +
            "  float depth = -mv.z;\n" +
            "  vColor = spCol;\n" +
            "  vQ = aCorner;\n" +
            "  vConic = vec3(0.0);\n" +
            "  vCenterPx = vec2(0.0);\n" +
            "  if (depth < uNear) {\n" +
            "    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "\n" +
            // THE ALPHA-EXTENT CLIP. The fragment shader discards a fragment whose
            // alpha falls below uAlphaDiscard, and that boundary is a RADIUS: with
            // power = -0.5 r^2 in the Mahalanobis radius r the conic defines, the
            // fragment survives when a * exp(-0.5 r^2) >= uAlphaDiscard, i.e.
            // r <= sqrt(2 ln(a / thr)). The quad below is emitted at r = 3 (uFudge is
            // fixed at 1), so scaling it by min(1, sqrt(2 ln(a/thr)) / 3) makes the
            // quad cover exactly the region the fragment stage keeps. Nothing that was
            // drawn before changes: every fragment the smaller quad removes is one the
            // discard was already throwing away. Mirrors Splat.extentScaleForAlpha,
            // whose test pins that the clipped edge lands exactly on the discard
            // boundary. A splat at or below the threshold has NO surviving radius, so
            // it is dropped outright -- free, because every one of its fragments would
            // have been discarded. The test is the fragment shader's own !(a > thr),
            // so a NaN falls on the drop side rather than through it.
            "  if (!(spCol.a > uAlphaDiscard)) {\n" +
            "    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  float keep = min(1.0, sqrt(2.0 * log(spCol.a / uAlphaDiscard)) / 3.0);\n" +
            "\n" +
            "  float n = inversesqrt(max(dot(q4, q4), 1e-6));\n" +
            "  q4 *= n;\n" +
            "  float qw = q4.x, qx = q4.y, qy = q4.z, qz = q4.w;\n" +
            "  mat3 Rq = mat3(\n" +
            "    1.0 - 2.0 * (qy * qy + qz * qz), 2.0 * (qx * qy + qw * qz), 2.0 * (qx * qz - qw * qy),\n" +
            "    2.0 * (qx * qy - qw * qz), 1.0 - 2.0 * (qx * qx + qz * qz), 2.0 * (qy * qz + qw * qx),\n" +
            "    2.0 * (qx * qz + qw * qy), 2.0 * (qy * qz - qw * qx), 1.0 - 2.0 * (qx * qx + qy * qy));\n" +
            "\n" +
            "  mat3 M = Rq * mat3(spScale.x, 0.0, 0.0,\n" +
            "                     0.0, spScale.y, 0.0,\n" +
            "                     0.0, 0.0, spScale.z);\n" +
            "  mat3 Sig3 = M * transpose(M);\n" +
            "  mat3 SigV = uViewRot * Sig3 * transpose(uViewRot);\n" +
            "  float c00 = SigV[0][0];\n" +
            "  float c01 = SigV[0][1];\n" +
            "  float c02 = SigV[0][2];\n" +
            "  float c11 = SigV[1][1];\n" +
            "  float c12 = SigV[1][2];\n" +
            "  float c22 = SigV[2][2];\n" +
            "\n" +
            "  float invd = 1.0 / depth;\n" +
            "  float jx0 = uFocal.x * invd;\n" +
            "  float jx2 = uFocal.x * mv.x * invd * invd;\n" +
            "  float jy1 = uFocal.y * invd;\n" +
            "  float jy2 = uFocal.y * mv.y * invd * invd;\n" +
            "  float u00 = jx0 * c00 + jx2 * c02;\n" +
            "  float u01 = jx0 * c01 + jx2 * c12;\n" +
            "  float u02 = jx0 * c02 + jx2 * c22;\n" +
            "  float u11 = jy1 * c11 + jy2 * c12;\n" +
            "  float u12 = jy1 * c12 + jy2 * c22;\n" +
            "  float cov00 = u00 * jx0 + u02 * jx2;\n" +
            "  float cov01 = u01 * jy1 + u02 * jy2;\n" +
            "  float cov11 = u11 * jy1 + u12 * jy2;\n" +
            // The 2D low-pass floor, gsplat's eps2d. It is a FLOOR, not a blur:
            // it lifts the splats whose screen covariance is too small to sample
            // and leaves the well-sampled ones alone, so a far field of ~2 px
            // splats at 2 px spacing stops reading as a screen-door fog.
            //
            // It arrives here already in render pixels, from Splat.eps2dFor, which
            // turns the knob's fraction of the cell into the unit this line needs.
            // That indirection is not decoration: a constant here is measured in a
            // unit the gap does not use, and the gap is what is being closed.
            //
            // It is exactly `fraction * cell` -- the fraction of the on-screen cell
            // one splat stands for -- with NO second, target-area minimum under it.
            // There used to be one, and it was this knob frozen at a single cloud's
            // count (0.5 of the 912x1984 sweep frame IS 6 px^2 there), so above
            // 301,568 splats it added over-blur and below it the fraction already
            // reproduced the measurement. See Splat.eps2dFor.
            // Measured on one cloud's smooth sky, as high-frequency energy against
            // the photograph's own: at the cloud's 912x1984 frame, 2.5 px leaves
            // 3.0x and 6 px reaches 0.67x; at the 1440x3120 the phone draws it
            // into, the same two numbers are 7.3x and 3.2x, and it takes 15 px to
            // reach 0.67x again. As a fraction of the cell all of those are 0.5.
            // The dev SIZE knob spans 0.45..0.80, so a phone can re-pick the trade
            // by eye at any resolution and every setting it offers works. This is
            // the only place the floor lives --
            // :core's SplatProjection::conic has no equivalent (its MIN_DET is a
            // singularity guard, not a floor), so there is nothing to keep in step.
            "  cov00 += uEps2d;\n" +
            "  cov11 += uEps2d;\n" +
            "\n" +
            "  float det = cov00 * cov11 - cov01 * cov01;\n" +
            "  if (det <= 1e-9) {\n" +
            "    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  vConic = vec3(cov11 / det, -cov01 / det, cov00 / det);\n" +
            "\n" +
            "  float mid = 0.5 * (cov00 + cov11);\n" +
            "  float root = sqrt(max(mid * mid - det, 0.0));\n" +
            "  float l1 = mid + root;\n" +
            "  float l2 = max(mid - root, 0.0);\n" +
            "  float r1 = 3.0 * uFudge * sqrt(max(l1, 0.0));\n" +
            "  float r2 = 3.0 * uFudge * sqrt(max(l2, 0.0));\n" +
            // Radius clip -- the guard gsplat calls radius_clip. A splat whose
            // 3-sigma extent is a large fraction of the frame is a splat AT the
            // camera, and its quad costs a screenful of fragments to lay down a
            // wash of colour. Measured on the phone at 669k splats: 922 ms a frame
            // with these drawn, 76 ms without them -- this is the whole of the
            // stutter. Dropped, not clamped: a clamped quad leaves the hole its own
            // wash was covering, and the threshold is far outside anything a real
            // surface produces, so no surface splat is ever lost to it.
            "  if (max(r1, r2) > 0.5 * uViewport.y) {\n" +
            "    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  vec2 e1 = vec2(cov01, l1 - cov00);\n" +
            "  float el = length(e1);\n" +
            "  e1 = el > 1e-9 ? e1 / el : vec2(1.0, 0.0);\n" +
            "  vec2 e2 = vec2(e1.y, -e1.x);\n" +
            "\n" +
            "  vec2 offsetPx = keep * (aCorner.x * r1 * e1 + aCorner.y * r2 * e2);\n" +
            "  vec2 offsetNdc = offsetPx * 2.0 / uViewport;\n" +
            "  vec4 clip = uProj * mv;\n" +
            "  vec2 ndc = clip.xy / clip.w;\n" +
            "  vec2 ext = abs(offsetNdc);\n" +
            "  if (abs(ndc.x) - ext.x > 1.0 || abs(ndc.y) - ext.y > 1.0) {\n" +
            "    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);\n" +
            "    return;\n" +
            "  }\n" +
            "  gl_Position = vec4((ndc + offsetNdc) * clip.w, clip.z, clip.w);\n" +
            "  vCenterPx = (ndc * 0.5 + 0.5) * uViewport;\n" +
            "}\n";

    private static final String FRAG =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "\n" +
            "uniform float uBrightnessGain;\n" +
            "uniform float uShadowLift;\n" +
            // The alpha discard threshold. It is Splat.ALPHA_DISCARD, uploaded as a
            // uniform, and it is the SAME number the vertex shader clips the quad
            // extent against (see VERT's uAlphaDiscard) -- so the quad ends exactly
            // where this discard begins and a pixel that survived before still does.
            // It was a literal 0.00392156862 (1/255) here; a second literal in VERT
            // is the drift this avoids.
            "uniform float uAlphaDiscard;\n" +
            "\n" +
            "in vec4 vColor;\n" +
            "in vec2 vQ;\n" +
            "in vec2 vCenterPx;\n" +
            "in vec3 vConic;\n" +
            "out vec4 outColor;\n" +
            "\n" +
            "void main() {\n" +
            "  if (dot(vQ, vQ) > 1.0) {\n" +
            "    discard;\n" +
            "  }\n" +
            "  vec2 d = gl_FragCoord.xy - vCenterPx;\n" +
            "  float power = -0.5 * (vConic.x * d.x * d.x + 2.0 * vConic.y * d.x * d.y + vConic.z * d.y * d.y);\n" +
            "  if (power > 0.0) {\n" +
            "    discard;\n" +
            "  }\n" +
            "  float a = vColor.a * exp(power);\n" +
            "  if (a < uAlphaDiscard) {\n" +
            "    discard;\n" +
            "  }\n" +
            // The picture grade, the same one multiply and one mix the relief's
            // fragment shader applies (Picture.brightnessGain / shadowLift). It is
            // a grade on the baked colour, which is exactly what Brightness and
            // Shadow mean, and 0 is the identity for both — the rule the class
            // states. The two rows are visible in splat mode and this is what makes
            // them real rather than controls nothing reads.
            "  vec3 c = vColor.rgb * uBrightnessGain;\n" +
            "  c += uShadowLift * (1.0 - c);\n" +
            "  outColor = vec4(c, a);\n" +
            "}\n";

    // ------------------------------------------------------------------ blur
    //
    // The dev BLUR knob: a real post-softness on the splat FBO, in render-target
    // pixels, 0 = identity. It is NOT the SIZE floor and does not pretend to be --
    // the floor lifts the splats too small to sample and leaves the well-sampled
    // ones alone, so it removes the screen-door without touching detail; a blur
    // smears the whole picture, so it removes residual speckle at the cost of the
    // detail the floor preserved. Density (BUDGET, a regeneration) is the only
    // thing that gets the detail back. See the class note for the measurement.
    //
    // MSAA was assessed and is NOT used, because it cannot do this job: it
    // antialiases the EDGES of the triangles that are drawn, and the artifact is
    // the BLACK GAPS between splats that are not drawn -- a coverage problem, not
    // an aliasing one. 4x MSAA on the surface (ReliefRenderer.enableMSAA) already
    // applies to the direct-to-surface path and leaves the screen-door exactly as
    // it was. So the blur is a separable Gaussian: one pass per axis, each a
    // fullscreen triangle derived from gl_VertexID (no VBO), sampling the previous
    // pass's colour texture. 13 taps per axis at the top radius.

    /** Fullscreen triangle: no attribute, no buffer, the same id trick as the cards. */
    private static final String BLUR_VERT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "out vec2 vUv;\n" +
            "void main() {\n" +
            "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
            "  vUv = p;\n" +
            "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n" +
            "}\n";

    /**
     * One axis of the separable Gaussian, and the upscale copy.
     *
     * <p>The offsets are whole pixels and the weights are a Gaussian of sigma
     * {@code radius/2}, so {@code radius} is the kernel's half-width in pixels --
     * the number the label states. The loop is a fixed {@code -6..6} with a
     * weight that is zero beyond the radius, so the cost is constant whenever the
     * pass runs at all and no dynamic loop bound can reach the driver. The blur
     * passes are skipped entirely at radius &lt; 0.5, which is why 0 is exactly the
     * identity for the BLUR row.
     *
     * <p><b>The same program performs the upscale copy at radius 0, and there a
     * radius below the blur's own minimum is ONE fetch, not thirteen.</b> The loop's
     * weight is zero for every off-centre tap at radius 0 -- {@code step(0.001, |fi|)}
     * is 1 for every {@code |fi| >= 1} -- so the twelve off-centre taps contribute
     * nothing but were still fetched, because {@code w} depends on a uniform and the
     * compiler cannot fold the loop away. That was thirteen fetches per pixel over the
     * whole surface, the fixed cost that survived every render scale. The early-out
     * below reduces the copy to the one tap that carries the answer.
     *
     * <p><b>It is bit-identical, and here is the argument so it can be falsified.</b>
     * At radius 0 the loop leaves {@code sum = texture(vUv) * 1.0 + Σ(texture(...) * 0.0)}
     * and {@code wsum = 1.0}, so {@code sum / wsum} is the centre texel: adding exact
     * zeros to a finite value, and dividing by exactly 1, are both the identity in IEEE
     * 754. What would break it is a <b>non-finite off-centre texel</b>, because
     * {@code Inf * 0.0} is NaN and would poison the sum -- and the source is an RGBA8
     * render target, whose normalised samples are always finite, so it cannot.
     */
    private static final String BLUR_FRAG =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uBlurTex;\n" +
            "uniform vec2 uBlurTexel;\n" +
            "uniform vec2 uBlurDir;\n" +
            "uniform float uBlurRadius;\n" +
            "in vec2 vUv;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            // The copy path (radius 0) is a single fetch; the same threshold the Java
            // side uses to decide the blur passes run at all (blurPx >= 0.5f). A
            // uniform branch, so it is coherent across the whole draw.
            "  if (uBlurRadius < 0.5) { outColor = texture(uBlurTex, vUv); return; }\n" +
            "  float sigma = max(uBlurRadius, 0.5) * 0.5;\n" +
            "  float twoSigma2 = 2.0 * sigma * sigma;\n" +
            "  vec4 sum = vec4(0.0);\n" +
            "  float wsum = 0.0;\n" +
            "  for (int i = -6; i <= 6; i++) {\n" +
            "    float fi = float(i);\n" +
            "    float w = exp(-(fi * fi) / twoSigma2);\n" +
            "    w *= 1.0 - step(uBlurRadius + 0.001, abs(fi));\n" +
            "    sum += texture(uBlurTex, vUv + uBlurDir * uBlurTexel * fi) * w;\n" +
            "    wsum += w;\n" +
            "  }\n" +
            "  outColor = wsum > 0.0 ? sum / wsum : texture(uBlurTex, vUv);\n" +
            "}\n";
}
