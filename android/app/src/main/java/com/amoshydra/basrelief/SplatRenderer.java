package com.amoshydra.basrelief;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;

import com.amoshydra.basrelief.core.render.CameraModel;
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
 * <p><b>The record is uploaded verbatim.</b> The 32-byte {@code .splat} record is
 * exactly two {@code RGBA32F} texels — {@code (x,y,z,sx)} and
 * {@code (sy,sz,colourBits,quatBits)} — so the file goes straight to the GPU and
 * the shader does {@code floatBitsToUint} to unpack the colour and the
 * quaternion. No CPU repack, no per-frame allocation.
 *
 * <p><b>The camera is the photograph's own, and it is a camera.</b> The demo cloud
 * is in the frame of the still SHARP ran on (x right, y down, z forward,
 * principal point at the centre), so the source view is the eye at the origin
 * looking down +z with the still's vertical field of view. At rest — no tilt, no
 * Origin, no Pan — the view is exactly that source view, so the picture is the
 * photograph. Zoom narrows the field of view (a crop of the same camera, which is
 * what zoom means for a photograph), Origin sets the point the tilt orbits about
 * (the cloud's own surface under the origin's ray) and shifts the picture to it,
 * and Pan is a screen-pixel view offset. All three mean here what they mean for
 * the relief, and the whole construction lives in
 * {@link com.amoshydra.basrelief.core.render.SplatCamera} so the at-rest invariant
 * is asserted without a device. The source camera's constants are the demo's; see
 * {@link #SOURCE_TARGET_Z} and {@link #SOURCE_FOVY}.
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
     * The source camera of the demo cloud: SHARP's fallback 30 mm lens on a
     * 1440x2560 still gives {@code f = 2036.6 px}, so the vertical field of view
     * is 64.3 degrees; the target sits on the optical axis at the bale's front
     * surface. Same numbers as the WebGL reference viewer's source view.
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
            uFocal, uViewport, uBrightnessGain, uShadowLift;

    // The cloud's CPU mirror.
    private int count;
    private float[] pos = new float[0];
    private float[] depths = new float[0];
    private int[] order = new int[0];
    private int[] counts = new int[0];
    private int dataRows;
    private int orderRows;

    // Offscreen target: the picture is drawn at `renderScale` of the surface and
    // blitted up. Fragment work is what scales with pixels and splats are soft.
    private int fbo, fboColour, fboDepth;
    private int renderW = 1, renderH = 1;
    private float scaleInUse = 1f;

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

    /** A new EGL context: every name this class cached is meaningless now. */
    void onContextLost() {
        prog = 0;
        vboCorner = 0;
        texData = 0;
        texOrder = 0;
        fbo = 0;
        fboColour = 0;
        fboDepth = 0;
        renderW = 1;
        renderH = 1;
        scaleInUse = 1f;
        ready = false;
        sortPrimed = false;
        timerExt = false;
        timerQuery = 0;
        queryOpen = false;
        queryInFlight = false;
        count = 0;
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
        if (cloud == null || cloud.count == 0) return false;
        // A queued upload can run before this GL thread has a current context (the
        // platform creates the engine's context lazily). Every GL entry point is
        // then a no-op and glCreateShader answers 0 -- the empty-log "compile
        // failed" seen on device -- so refuse here rather than build against
        // nothing. The caller retries on the next resolve, and no state that says
        // "this is on screen" is written.
        if (GLES30.glGetString(GLES30.GL_VERSION) == null) return false;

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
        // Whole rows, zero-padded: glTexImage2D reads DATA_W * dataRows * 16
        // bytes, and a truncated file's raw buffer can be shorter than that.
        ByteBuffer padded = ByteBuffer.allocateDirect(dataRows * DATA_W * 16)
                .order(ByteOrder.nativeOrder());
        padded.put(cloud.raw(), 0, Math.min(cloud.raw().length, n * 32));
        padded.position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, DATA_W, dataRows, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, padded);
        nearest(texData);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texOrder);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32UI, ORDER_W, orderRows, 0,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, null);
        nearest(texOrder);

        // The CPU mirror the sort reads. Positions only: the depth pass walks
        // them once per camera move, and the GPU reads everything else.
        pos = new float[n * 3];
        cloud.positions(pos);
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
        ready = false;
    }

    /** Free everything; called when the host stops drawing splats. */
    void release() {
        releaseTextures();
        if (prog != 0) {
            GLES30.glDeleteProgram(prog);
            prog = 0;
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
     */
    void draw(float surfaceWf, float surfaceHf, float tiltX, float tiltY, float motion,
              float zoom, float orbitX, float orbitY, float panX, float panY,
              float brightnessGain, float shadowLift, float renderScale) {
        if (!isReady() || surfaceWf <= 0f || surfaceHf <= 0f) return;
        int surfaceW = Math.max(1, Math.round(surfaceWf));
        int surfaceH = Math.max(1, Math.round(surfaceHf));
        long t0 = System.nanoTime();

        ensureOffscreen(surfaceW, surfaceH, renderScale);
        int vw = fbo != 0 ? renderW : surfaceW;
        int vh = fbo != 0 ? renderH : surfaceH;

        float fovy = SOURCE_FOVY / Math.max(0.25f, Math.min(2f, zoom));
        fovy = Math.max(MIN_FOVY, Math.min(MAX_FOVY, fovy));

        // The camera, all of it in :core: the source view, the Origin/Pan shift,
        // and the turntable about the cloud's own surface under the origin. See
        // SplatCamera for why the origin is a shift and not a lateral move.
        SplatCamera.Frame cam = splatCamera.frame(surfaceW, surfaceH, fovy,
                orbitX, orbitY, panX, panY, tiltX, tiltY, motion,
                pos, count, SOURCE_TARGET_Z);
        System.arraycopy(cam.view, 0, view, 0, 16);
        // uProj gets the PANNED projection: the shift is what makes the at-rest
        // picture the photograph moved by Origin and Pan, at every depth.
        System.arraycopy(cam.projPanned, 0, proj, 0, 16);
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

        if (needsSort(tiltX, tiltY, orbitX, orbitY, zoom)) {
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
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glBlitFramebuffer(0, 0, renderW, renderH, 0, 0, surfaceW, surfaceH,
                    GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
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
    }

    private static final int SORT_BUCKETS = 8192;

    // ------------------------------------------------------------------ sort

    private boolean needsSort(float tiltX, float tiltY, float orbitX, float orbitY,
                              float zoom) {
        if (!sortPrimed) return true;
        return Math.abs(tiltX - sortedTiltX) > 1e-3f
                || Math.abs(tiltY - sortedTiltY) > 1e-3f
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

    private void ensureOffscreen(int surfaceW, int surfaceH, float scale) {
        int rw = Math.max(1, Math.round(surfaceW * scale));
        int rh = Math.max(1, Math.round(surfaceH * scale));
        if (rw == renderW && rh == renderH && Math.abs(scale - scaleInUse) < 1e-4f && fbo != 0) return;
        releaseOffscreen();
        scaleInUse = scale;
        renderW = rw;
        renderH = rh;
        if (scale >= 0.999f) return;      // 1.0 draws straight to the surface
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
        nearest(fboColour);
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
        }
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
        fbo = 0;
        fboColour = 0;
        fboDepth = 0;
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
        uFocal = GLES30.glGetUniformLocation(prog, "uFocal");
        uViewport = GLES30.glGetUniformLocation(prog, "uViewport");
        uBrightnessGain = GLES30.glGetUniformLocation(prog, "uBrightnessGain");
        uShadowLift = GLES30.glGetUniformLocation(prog, "uShadowLift");
        for (int loc : new int[] { uProj, uView, uViewRot, uData, uOrder, uDataW, uOrderW,
                uNear, uFudge, uFocal, uViewport, uBrightnessGain, uShadowLift }) {
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
        if (now - lastLogNanos < 1_000_000_000L) return;
        float secs = (now - lastLogNanos) / 1e9f;
        float fps = framesInWindow / secs;
        float frameMs = 1000f / Math.max(fps, 0.001f);
        float sortMs = sortsInWindow > 0 ? sortNanosInWindow / 1e6f / sortsInWindow : 0f;
        android.util.Log.i(TAG, "splat: count=" + count
                + " fps=" + fmt(fps)
                + " frameMs=" + fmt(frameMs)
                + " maxFrameMs=" + fmt(maxFrameNanos / 1e6f)
                + " sortMs=" + fmt(sortMs)
                + " sorts/s=" + sortsInWindow
                + " drawMs=" + fmt(drawNanosInWindow / 1e6f / Math.max(1, framesInWindow))
                + " gpuMs=" + fmt(gpuMs)
                + " scale=" + fmt(scaleInUse)
                + " render=" + (fbo != 0 ? renderW + "x" + renderH : surfaceW + "x" + surfaceH)
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
            // See the note at its use: a floor on the 2D covariance, not a blur.
            "const float SPLAT_EPS_2D = 2.5;\n" +
            "\n" +
            "uniform mat4 uProj;\n" +
            "uniform mat4 uView;\n" +
            "uniform mat3 uViewRot;\n" +
            "uniform sampler2D uData;\n" +
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
            "  vec4 A = texelFetch(uData, c0, 0);\n" +
            "  vec4 B = texelFetch(uData, c1, 0);\n" +
            "\n" +
            "  vec3 spPos = A.xyz;\n" +
            "  vec3 spScale = vec3(A.w, B.x, B.y);\n" +
            "  vec4 spCol = unpack_unorm_4x8(floatBitsToUint(B.z));\n" +
            "  vec4 q4 = unpack_unorm_4x8(floatBitsToUint(B.w)) * 2.0 - 1.0;\n" +
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
            // splats at 2 px spacing stops reading as a screen-door fog. Measured
            // on the cloud that showed the striping (sky high-frequency energy /
            // subject high-frequency energy / black holes): 0.3 -> 10.08 / 1.68 /
            // 9.6%; 1.5 -> 5.26 / 1.46 / 1.8%; 2.5 -> 2.98 / 1.35 / 0.2%; 4.0 ->
            // 1.27 / 1.26 / 0.0%. 2.5 is the knee: the stripes are gone and the
            // subject is visually unchanged. 4.0 is the cleaner-sky option at a
            // small subject cost, and is deliberately not shipped. This is the
            // only place the floor lives -- :core's SplatProjection::conic has no
            // equivalent (its MIN_DET is a singularity guard, not a floor), so
            // there is nothing to keep in step.
            "  cov00 += SPLAT_EPS_2D;\n" +
            "  cov11 += SPLAT_EPS_2D;\n" +
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
            "  vec2 e1 = vec2(cov01, l1 - cov00);\n" +
            "  float el = length(e1);\n" +
            "  e1 = el > 1e-9 ? e1 / el : vec2(1.0, 0.0);\n" +
            "  vec2 e2 = vec2(e1.y, -e1.x);\n" +
            "\n" +
            "  vec2 offsetPx = aCorner.x * r1 * e1 + aCorner.y * r2 * e2;\n" +
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
            "  if (a < 0.00392156862) {\n" +
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
}
