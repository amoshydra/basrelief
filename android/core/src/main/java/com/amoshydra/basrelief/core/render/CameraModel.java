package com.amoshydra.basrelief.core.render;

/**
 * The camera model: how the relief plane is scaled to the viewport, where the
 * camera sits as a function of tilt/orbit, how the pivot's surface depth aims
 * the camera at the subject under the origin, and how a screen tap unprojects
 * back to a new orbit centre.
 *
 * <p>Extracted from {@code ReliefRenderer} (which used android.opengl.Matrix and
 * could only be exercised on a device) so it is plain JVM code with no Android
 * imports and is therefore unit-testable. The maths is unchanged — see
 * {@code CameraModelTest}, which pins the tap -> unproject -> project
 * round-trip that used to be a manual ritual.
 *
 * <p>Instances are mutable and NOT thread-safe. The renderer owns one and only
 * ever touches it on the GL thread. {@link #frame} writes into a single
 * reusable {@link Frame}, so drawing a frame allocates nothing.
 */
public final class CameraModel {

    public static final float FOV_DEG = 45f;
    public static final float CAM_DIST = 1.2f;
    public static final float CROP_MARGIN = 1.08f;
    public static final float TILT_RANGE = 0.16f;
    public static final float NEAR = 0.1f;
    public static final float FAR = 10f;

    /** Everything the renderer needs for one draw, computed in a single pass. */
    public static final class Frame {
        /** {@code projPanned * view * S(scale, scale, scale * zScale)} */
        public final float[] mvp = new float[16];
        /** {@code projPanned * view} — the plane marker is transformed by this. */
        public final float[] viewProjection = new float[16];
        public float eyeX, eyeY, eyeZ;
        /**
         * The pivot's world position: the surface point under the origin, which
         * is what the camera aims at. The eye sits directly above it in z at the
         * fixed camera distance (at rest), so {@code pivotX}/{@code pivotY} are
         * also the reference eye's x/y and the splat cards are placed from
         * {@code (pivotX, pivotY, CAM_DIST)}.
         */
        public float pivotX, pivotY, pivotZ;
        public float zScale;
        public float scale;
    }

    private float aspect;        // image width / height
    private float surfaceAspect; // viewport width / height
    private float zoom = 1f;
    private float depth = 1f;    // 0..1 fraction of maxZScale()
    private float motion = 1f;
    /**
     * The surface height under the origin, in model units: the blurred depth
     * field's value at the origin's image position, {@code h = v/255 - 0.5}, the
     * same quantity the vertex shader displaces by. 0 is the plane.
     */
    private float pivotHeight;

    private final Frame frame = new Frame();
    private final float[] proj = new float[16];
    private final float[] panMat = new float[16];
    private final float[] projPanned = new float[16];
    private final float[] view = new float[16];
    private final float[] invVP = new float[16];
    private final float[] ray = new float[4];

    public void setAspect(float aspect) {
        this.aspect = aspect;
    }

    public void setSurfaceAspect(float surfaceAspect) {
        this.surfaceAspect = surfaceAspect;
    }

    public void setZoom(float zoom) {
        this.zoom = Math.max(0.25f, Math.min(2f, zoom));
    }

    /**
     * @param depth 0..1 as a FRACTION of {@link #maxZScale()} at the current
     *               zoom, not an absolute z-scale. See {@link #computeZScale}.
     */
    public void setDepth(float depth) {
        this.depth = Math.max(0f, Math.min(1f, depth));
    }

    public void setMotion(float motion) {
        this.motion = motion;
    }

    /**
     * The surface height under the origin, in model units (the depth field's
     * {@code v/255 - 0.5}, so in {@code [-0.5, 0.5]}).
     *
     * <p><b>Why the camera wants it.</b> {@link #frame} aims at the pivot: with
     * the height at 0 that aim point is on the reference plane, so a tilt holds
     * the PLANE fixed and everything nearer swings -- the user's it orbited around the background. With the field's height under the origin, the aim
     * point is the SURFACE under the origin, so the subject holds and the
     * background swings.
     *
     * <p><b>What it must not change.</b> The at-rest picture. The eye keeps the
     * fixed camera distance and the pivot's own x/y, so it sits directly above
     * the pivot at any height; {@code lookAt}'s direction is then still straight
     * down and the view matrix is exactly the plane point's own. That is the
     * whole reason this construction needs no model-scale compensation, and it
     * is what keeps "at rest the picture is exactly the photograph" true for
     * every origin and every pivot height.
     *
     * <p>The value is clamped to the model's own depth axis ({@code [-0.5, 0.5]},
     * which {@code ReliefMesh} defines), and a non-finite value is the plane,
     * because a window claims nothing. The clamp is also what guarantees the
     * pivot stays in front of the camera: the model's own depth limit
     * ({@link #maxZScale}) is {@code 0.9 * CAM_DIST / scale}, so a pivot at the
     * extreme is {@code 0.45 * CAM_DIST} at most and the eye can never end up
     * behind its own aim point.
     */
    public void setPivotHeight(float pivotHeight) {
        if (!Float.isFinite(pivotHeight)) pivotHeight = 0f;
        this.pivotHeight = Math.max(-0.5f, Math.min(0.5f, pivotHeight));
    }

    public float getAspect() {
        return aspect;
    }

    /** Model scale: how big the plane is so it covers the viewport. */
    public float computeScale() {
        if (aspect <= 0f) return 0f;
        float visH = 2f * CAM_DIST * (float) Math.tan(Math.toRadians(FOV_DEG / 2f));
        float visW = visH * surfaceAspect;
        return Math.max(visH, visW / aspect) * CROP_MARGIN * zoom;
    }

    /**
     * Surface pixels the picture moves for one whole unit of orbit (Origin) on
     * one axis, at the current zoom and geometry.
     *
     * <p><b>The maths.</b> {@link #frame} puts the camera pivot at
     * {@code px = orbitX * aspect * scale} ({@code py = orbitY * scale}) and
     * aims the camera at it, so a change in the orbit moves the camera and the
     * whole plane together. The visible world height at the pivot plane is
     * {@code visH = 2 * CAM_DIST * tan(FOV / 2)}, so one world unit there is
     * {@code surfaceH / visH} pixels, and the answer is
     * {@code (horizontal ? aspect : 1) * scale * surfaceH / visH}.
     *
     * <p><b>This is the exact inverse of the last step of
     * {@link #screenToOrbit}</b> ({@code ox = wx / (aspect * scale)}), which is
     * why multiplying a drag by it makes the picture track the finger 1:1 at
     * any zoom. The X axis carries the {@code aspect} factor because the
     * pivot's world x is scaled by the image's width/height ratio while y is
     * not; a single "pixels per unit" shared by both axes would therefore
     * mistrack one of them by the image's aspect, which is the kind of quiet
     * wrongness {@code CameraModelTest} exists to catch.
     *
     * <p><b>What deliberately does not enter.</b> The pivot's own depth. The
     * value is the PLANE's rate at the rest camera, and that is exact wherever
     * it matters: at rest the camera aims at the surface point under the origin
     * but the eye sits directly above the pivot, so the view matrix is the plane
     * point's own and the unprojection this is the inverse of is unchanged by
     * the pivot's height. Tilt does move the answer, and it is left out on
     * purpose: the value is read once per gesture, and a value that moved with
     * the gyro -- or with the surface under the origin as the slider moves --
     * would make the same drag change speed while the phone is in the hand.
     * Measured on the local rate (two taps 10 px apart) over the reachable
     * sweep: 3.9% at the extremes with the pivot at depth, against 2.6% with it
     * on the plane. The caller applies its own margin over tracking anyway.
     *
     * @param horizontal true for the X axis, false for Y
     * @param surfaceH   the drawing surface's height in pixels, as handed to
     *                   {@link #frame}
     * @return pixels, or 0 when there is no surface or no loaded image yet
     */
    public float screenPixelsPerOrbit(boolean horizontal, float surfaceH) {
        float scale = computeScale();
        if (scale <= 0f || surfaceH <= 0f) return 0f;
        float visH = 2f * CAM_DIST * (float) Math.tan(Math.toRadians(FOV_DEG / 2f));
        return (horizontal ? aspect : 1f) * scale * surfaceH / visH;
    }

    /**
     * The deepest the relief may go at the current zoom without the camera
     * ending up inside it: the relief half-depth (0.5) times the model scale
     * must stay under 0.9x the camera distance.
     */
    public float maxZScale() {
        float s = computeScale();
        return s <= 0f ? 0f : (0.9f * CAM_DIST) / s;
    }

    /**
     * Actual z-scale = the Depth fraction of {@link #maxZScale()}.
     *
     * <p>Depth used to be an absolute value hard-clamped to {@code maxZScale()},
     * which meant the top of the slider did nothing once the clamp bit — at
     * Zoom 2.0 more than half the slider range was a silent no-op, and the UI
     * never said so. Making it a fraction means the whole 0..1 range is always
     * live at every zoom, and "100%" always means "as deep as the camera
     * allows here".
     *
     * <p>Consequence: absolute depth now varies with zoom (a zoomed-out view can
     * carry a deeper relief, since more plane is visible). At Zoom 1.0 the
     * default is within 1% of the old absolute behaviour.
     */
    public float computeZScale(float scale) {
        if (scale <= 0f) return 0f;
        return depth * maxZScale();
    }

    /**
     * Compute everything for one frame and return the reusable {@link Frame}.
     *
     * <p>Surface dimensions are floats because they are used to normalise the
     * pan offset ({@code panX / surfaceW}), and the renderer carries them as
     * floats.
     *
     * <p>Order is {@code clip = projPanned * view * S(...)}. The unprojection in
     * {@link #screenToOrbit} inverts {@code projPanned * view} in that SAME
     * order; getting it backwards is a real historical bug, and
     * {@code CameraModelTest} asserts the round-trip rather than trusting a
     * comment here.
     *
     * <p><b>The aim point is the pivot, and the pivot has a depth.</b> The
     * camera translates laterally with the tilt and aims at the surface point
     * under the origin -- {@code (px, py, pivotHeight * scale * zScale)}, the
     * plane point lifted through the same model-to-world factor {@code uMVP}
     * applies -- so the subject under the origin holds while the background
     * swings around it. The eye does NOT move with the pivot's depth: it stays
     * at the fixed camera distance with the pivot's own x/y, directly above the
     * pivot, which is what makes the at-rest view matrix exactly the plane's
     * own at every height. See {@link #setPivotHeight}.
     */
    public Frame frame(float surfaceW, float surfaceH,
                       float panX, float panY,
                       float orbitX, float orbitY,
                       float tiltX, float tiltY) {
        float scale = computeScale();
        float zScale = computeZScale(scale);
        float px = orbitX * aspect * scale;
        float py = orbitY * scale;
        float pz = pivotHeight * scale * zScale;
        float range = TILT_RANGE * motion;

        Mat4.perspective(proj, 0, FOV_DEG, surfaceAspect, NEAR, FAR);
        Mat4.identity(panMat, 0);
        if (surfaceW > 0 && surfaceH > 0 && (panX != 0f || panY != 0f)) {
            Mat4.translateM(panMat, 0, -2f * panX / surfaceW, 2f * panY / surfaceH, 0f);
        }
        Mat4.multiplyMM(projPanned, 0, panMat, 0, proj, 0);

        frame.eyeX = px + tiltX * range;
        frame.eyeY = py - tiltY * range;
        frame.eyeZ = CAM_DIST;
        Mat4.lookAt(view, 0, frame.eyeX, frame.eyeY, frame.eyeZ, px, py, pz, 0f, 1f, 0f);

        Mat4.multiplyMM(frame.viewProjection, 0, projPanned, 0, view, 0);
        frame.pivotX = px;
        frame.pivotY = py;
        frame.pivotZ = pz;
        frame.scale = scale;
        frame.zScale = zScale;
        System.arraycopy(frame.viewProjection, 0, frame.mvp, 0, 16);
        Mat4.scaleM(frame.mvp, 0, scale, scale, scale * frame.zScale);
        return frame;
    }

    /**
     * Unproject a screen tap to a new orbit centre, in the same plane as the
     * current orbit pivot (z = 0 in plane space).
     *
     * <p>The unprojection runs through the camera AS IT IS, including the
     * current pivot height, so the plane point it resolves is the one that was
     * under the finger on screen. The new centre is on the plane, exactly as
     * before; its surface height -- and therefore the new pivot's depth -- is
     * read from the field under it, which is the renderer's half.
     *
     * @param out receives {x, y} orbit in [-0.5, 0.5]
     * @return false if the tap missed the plane (no orbit change)
     */
    public boolean screenToOrbit(float ndcX, float ndcY,
                                 float surfaceW, float surfaceH,
                                 float panX, float panY,
                                 float orbitX, float orbitY,
                                 float tiltX, float tiltY, float[] out) {
        Frame f = frame(surfaceW, surfaceH, panX, panY,
                orbitX, orbitY, tiltX, tiltY);
        if (f.scale <= 0f) return false;
        if (!Mat4.invert(invVP, 0, f.viewProjection, 0)) return false;

        float[] near = ray;
        near[0] = ndcX; near[1] = ndcY; near[2] = -1f; near[3] = 1f;
        Mat4.multiplyMV(near, 0, invVP, 0, near, 0);
        float nx = near[0] / near[3], ny = near[1] / near[3], nz = near[2] / near[3];

        float[] far = ray;
        far[0] = ndcX; far[1] = ndcY; far[2] = 1f; far[3] = 1f;
        Mat4.multiplyMV(far, 0, invVP, 0, far, 0);
        float fx = far[0] / far[3], fy = far[1] / far[3], fz = far[2] / far[3];

        float dz = fz - nz;
        if (dz == 0f) return false;
        float t = -nz / dz;
        float wx = nx + (fx - nx) * t;
        float wy = ny + (fy - ny) * t;

        float ox = wx / (aspect * f.scale);
        float oy = wy / f.scale;
        if (Math.abs(ox) > 0.5f || Math.abs(oy) > 0.5f) return false;
        out[0] = ox;
        out[1] = oy;
        return true;
    }

    /**
     * Project a plane-space point to normalised device coords.
     * Exists so the round-trip can be asserted in tests; not used at runtime.
     */
    public float[] project(float x, float y, float surfaceW, float surfaceH,
                           float panX, float panY,
                           float orbitX, float orbitY,
                           float tiltX, float tiltY) {
        Frame f = frame(surfaceW, surfaceH, panX, panY,
                orbitX, orbitY, tiltX, tiltY);
        ray[0] = x; ray[1] = y; ray[2] = 0f; ray[3] = 1f;
        Mat4.multiplyMV(ray, 0, f.viewProjection, 0, ray, 0);
        if (ray[3] == 0f) return null;
        return new float[] { ray[0] / ray[3], ray[1] / ray[3] };
    }
}
