package com.amoshydra.basrelief.core.render;

/**
 * The camera model: how the relief plane is scaled to the viewport, where the
 * camera sits as a function of tilt/orbit, and how a screen tap unprojects
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
        public float zScale;
        public float scale;
    }

    private float aspect;        // image width / height
    private float surfaceAspect; // viewport width / height
    private float zoom = 1f;
    private float depth = 1f;    // 0..1 fraction of maxZScale()
    private float motion = 1f;

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
     */
    public Frame frame(float surfaceW, float surfaceH,
                       float panX, float panY,
                       float orbitX, float orbitY,
                       float tiltX, float tiltY) {
        float scale = computeScale();
        float px = orbitX * aspect * scale;
        float py = orbitY * scale;
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
        Mat4.lookAt(view, 0, frame.eyeX, frame.eyeY, frame.eyeZ, px, py, 0f, 0f, 1f, 0f);

        Mat4.multiplyMM(frame.viewProjection, 0, projPanned, 0, view, 0);
        frame.scale = scale;
        frame.zScale = computeZScale(scale);
        System.arraycopy(frame.viewProjection, 0, frame.mvp, 0, 16);
        Mat4.scaleM(frame.mvp, 0, scale, scale, scale * frame.zScale);
        return frame;
    }

    /**
     * Unproject a screen tap to a new orbit centre, in the same plane as the
     * current orbit pivot (z = 0 in plane space).
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
