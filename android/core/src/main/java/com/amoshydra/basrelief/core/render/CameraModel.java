package com.amoshydra.basrelief.core.render;

/**
 * The camera model: how the relief plane is scaled to the viewport, where the
 * camera sits as a function of tilt/orbit, how the pivot's surface depth is the
 * centre the tilt turns about, and how a screen tap unprojects back to a new
 * orbit centre.
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

    /**
     * The largest screen displacement, in pixels, the tilt doze may allow.
     *
     * <p>Half a pixel: a movement smaller than this cannot carry a feature's edge
     * from one pixel to the next, so freezing the picture at the last published
     * tilt and resuming later is invisible. It is a bound rather than a taste, and
     * {@link #tiltPixels} is what makes it checkable.
     */
    public static final float DOZE_SUBPIXEL_PX = 0.5f;

    /**
     * The tilt magnitude the doze treats as "still": below it, {@code
     * TiltController} stops publishing tilt, so the hosts stop calling
     * {@code requestRender()} and the GPU idles while the gyro keeps running.
     *
     * <p><b>Derived, not picked.</b> It is {@link #DOZE_SUBPIXEL_PX} divided by the
     * largest per-unit-tilt displacement {@link #tiltPixels} reports over the
     * reachable framings: measured with that method at <b>766.1 px</b> (Zoom 200%,
     * Depth 100%, the pivot at its extreme, on a 1440x3120 surface), so {@code 0.5 /
     * 766.1 = 6.53e-4}, rounded down to <b>6.0e-4</b> for margin. The test
     * {@code theDozeThresholdStaysUnderASubPixelAtEveryFraming} holds it under the
     * bound at every reachable zoom, depth and pivot, which is the guard that a
     * later edit to {@link #TILT_RANGE} or {@link #FOV_DEG} cannot silently make the
     * doze visible.
     *
     * <p><b>What it is deliberately not.</b> It is not {@code TiltController}'s
     * {@code IDLE_EPS}, which answers a different question — ten seconds of
     * stillness — and is about a degree, a few pixels. Folding the two together
     * would either make the idle sleep unreachable or make the doze visible.
     *
     * <p>The Motion setting scales the camera's travel, and therefore this
     * displacement, linearly; the bound is stated at Motion 1, the tilt range's own
     * unit.
     */
    public static final float DOZE_EPS = 6.0e-4f;

    /** Everything the renderer needs for one draw, computed in a single pass. */
    public static final class Frame {
        /** {@code projPanned * view * S(scale, scale, scale * zScale)} */
        public final float[] mvp = new float[16];
        /** {@code projPanned * view} — the plane marker is transformed by this. */
        public final float[] viewProjection = new float[16];
        public float eyeX, eyeY, eyeZ;
        /**
         * The pivot's world position: the surface point under the origin, which
         * is the point the tilt turns the camera about — the orbit centre. The
         * camera no longer aims at it: at rest the eye is the fixed rest eye (the
         * image centre), so the pivot projects to its own image position on
         * screen and the crosshair is drawn there. A rotation about the pivot
         * leaves the pivot's screen position unchanged, so the subject under the
         * origin holds while the background swings.
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
    private float camDistScale = 1f;

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
     * <p><b>Why the camera wants it.</b> {@link #frame} places the pivot — the
     * orbit centre — at this height, so a tilt turns the camera about the SURFACE
     * under the origin rather than about the plane behind it: the reading that it orbited around the background becomes "it orbits around the subject". With
     * the height at 0 the pivot is on the reference plane and the plane point
     * under the origin holds instead.
     *
     * <p><b>What it must not change.</b> The at-rest picture. The eye is the
     * fixed rest eye at every pivot height and the origin does not move it, so
     * the at-rest view is the photograph whatever the pivot is. That is the whole
     * reason this construction needs no model-scale compensation, and it is what
     * keeps "at rest the picture is exactly the photograph, translated by Pan
     * only" true for every origin and every pivot height.
     *
     * <p>The value is clamped to the model's own depth axis ({@code [-0.5, 0.5]},
     * which {@code ReliefMesh} defines), and a non-finite value is the plane,
     * because a window claims nothing. The clamp is also what guarantees the
     * pivot stays in front of the rest eye: the model's own depth limit
     * ({@link #maxZScale}) is {@code 0.9 * CAM_DIST / scale}, so a pivot at the
     * extreme is {@code 0.45 * CAM_DIST} at most and the rest eye can never end
     * up behind its own orbit centre.
     */
    public void setPivotHeight(float pivotHeight) {
        if (!Float.isFinite(pivotHeight)) pivotHeight = 0f;
        this.pivotHeight = Math.max(-0.5f, Math.min(0.5f, pivotHeight));
    }
    /** Camera-distance (dolly) scale; >1 pulls the eye back. */
    public void setCamDistScale(float v) {
        if (!Float.isFinite(v)) v = 1f;
        if (v < 0.5f) v = 0.5f;
        if (v > 5f) v = 5f;
        camDistScale = v;
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
     * The on-screen size of one whole Origin unit, in surface pixels, at the
     * current zoom and geometry.
     *
     * <p><b>The maths.</b> An Origin unit is a fraction of the plane: its world
     * x is {@code aspect * scale} and its world y {@code scale}, and the visible
     * world height at the picture is {@code visH = 2 * CAM_DIST * tan(FOV / 2)},
     * so one world unit is {@code surfaceH / visH} pixels. The answer is
     * {@code (horizontal ? aspect : 1) * scale * surfaceH / visH}.
     *
     * <p><b>What it is now, and what it was.</b> Origin no longer moves the
     * picture, so this is no longer "how far a drag moves the picture"; it is the
     * ON-SCREEN SIZE of one Origin unit — a whole picture-width on the X axis —
     * which is what the Origin slider uses as its fine-grained drag sensitivity.
     * It is still the exact inverse of the last step of {@link #screenToOrbit}
     * ({@code ox = wx / (aspect * scale)}), so a tap and the value the slider
     * would set for the same travel stay one mapping. The X axis carries the
     * {@code aspect} factor because the pivot's world x is scaled by the image's
     * width/height ratio while y is not; a single "pixels per unit" shared by
     * both axes would therefore mistrack one of them by the image's aspect, which
     * is the kind of quiet wrongness {@code CameraModelTest} exists to catch.
     *
     * <p>The pivot's own depth deliberately does not enter — the value is the
     * PLANE's size, read once per gesture, so a value that moved with the gyro or
     * with the surface under the origin as the slider moves would make the same
     * drag change speed while the phone is in the hand.
     *
     * @param horizontal true for the X axis, false for Y
     * @param surfaceH   the drawing surface's height in pixels, as handed to
     *                   {@link #frame}
     * @return pixels, or 0 when there is no surface or no loaded image yet
     */
    public float screenPixelsPerOrbit(boolean horizontal, float surfaceH) {
        float scale = computeScale();
        if (scale <= 0f || surfaceH <= 0f) return 0f;
        float visH = 2f * CAM_DIST * camDistScale * (float) Math.tan(Math.toRadians(FOV_DEG / 2f));
        return (horizontal ? aspect : 1f) * scale * surfaceH / visH;
    }

    /**
     * The largest screen displacement, in pixels, that a tilt delta of {@code
     * delta} produces at the current framing: the worst case over the surface's
     * depth range and the current zoom.
     *
     * <p><b>Why it exists.</b> The tilt doze stops publishing tilt while the phone
     * is still, so the wallpaper stops asking for frames. Whether that is visible is
     * exactly this number, so {@link #DOZE_EPS} is DERIVED from here rather than
     * picked. It is not used in the draw path.
     *
     * <p><b>The maths, and why it is the whole surface.</b> {@link #frame}
     * rotates the eye about the pivot by {@code (TILT_RANGE / CAM_DIST) * motion}
     * per unit of tilt (the orientation rotates with it), so the pivot does not
     * move and every other point moves by the parallax between its own depth and
     * the pivot's. The rotation also swings off-axis points by their lateral
     * offset, which is why the
     * answer grows with Zoom while the pure parallax does not: the picture's
     * half-width is {@code 0.5 * aspect * scale}, and {@code scale} carries
     * {@code zoom * CROP_MARGIN}. The worst case is a corner.
     *
     * <p><b>Only VISIBLE points count.</b> Above Zoom 100% the picture is larger
     * than the viewport, and its off-screen corners have arbitrarily large
     * displacements that nobody can see. The answer is the largest displacement
     * among points that project inside the viewport at rest — what "screen
     * displacement" means. This is asserted through the real {@link #frame} /
     * {@code mvp} chain rather than restated as a formula, so it cannot drift from
     * the camera it describes.
     *
     * <p><b>Linear in the delta.</b> Over the sub-pixel deltas this exists for, the
     * answer is linear to better than a thousandth; {@code CameraModelTest} pins
     * that rather than assuming it.
     *
     * @param delta    tilt delta in the normalised units the gyro reports (0..1 over
     *                 {@link #TILT_RANGE})
     * @param surfaceW drawing surface width in pixels
     * @param surfaceH drawing surface height in pixels
     * @return the largest visible displacement in pixels, or 0 with no surface
     */
    public float tiltPixels(float delta, float surfaceW, float surfaceH) {
        if (delta == 0f || surfaceW <= 0f || surfaceH <= 0f || aspect <= 0f) return 0f;
        if (computeScale() <= 0f) return 0f;

        // A grid over the picture's unit-plane coordinates: x is already aspect
        // scaled, so the picture's half-width is 0.5 * aspect and its half-height
        // is 0.5. The depth is sampled too because the worst case is a corner.
        final int N = 48, M = 4;
        final int count = (N + 1) * (N + 1) * (M + 1);
        float[] px = new float[count], py = new float[count], pz = new float[count];
        float[] restX = new float[count], restY = new float[count];
        float[] out = new float[2];

        Frame rest = frame(surfaceW, surfaceH, 0f, 0f, 0f, 0f, 0f, 0f);
        int k = 0;
        for (int i = 0; i <= N; i++) {
            float ux = (-0.5f + (float) i / N) * aspect;
            for (int j = 0; j <= N; j++) {
                float uy = -0.5f + (float) j / N;
                for (int m = 0; m <= M; m++) {
                    float uz = -0.5f + (float) m / M;
                    px[k] = ux;
                    py[k] = uy;
                    pz[k] = uz;
                    if (projectField(rest, ux, uy, uz, out)) {
                        restX[k] = out[0];
                        restY[k] = out[1];
                    } else {
                        restX[k] = Float.NaN; // unprojectable: skip
                    }
                    k++;
                }
            }
        }

        // The worst DIRECTION matters as well as the worst point: a tilt delta of
        // this size could point anywhere, so eight evenly spaced directions are
        // tried and the largest answer is kept.
        float best = 0f;
        for (int d = 0; d < 8; d++) {
            double angle = d * Math.PI / 4.0;
            float tiltX = (float) Math.cos(angle) * delta;
            float tiltY = (float) Math.sin(angle) * delta;
            Frame tilted = frame(surfaceW, surfaceH, 0f, 0f, 0f, 0f, tiltX, tiltY);
            for (int p = 0; p < count; p++) {
                if (Float.isNaN(restX[p])) continue;
                // Off screen at rest: it cannot be a "screen displacement".
                if (Math.abs(restX[p]) > 1f || Math.abs(restY[p]) > 1f) continue;
                if (!projectField(tilted, px[p], py[p], pz[p], out)) continue;
                float dx = (out[0] - restX[p]) * surfaceW * 0.5f;
                float dy = (out[1] - restY[p]) * surfaceH * 0.5f;
                float disp = (float) Math.hypot(dx, dy);
                if (disp > best) best = disp;
            }
        }
        return best;
    }

    /**
     * Project a unit-plane field point through a frame's model-view-projection,
     * into normalised device coords. Exists so {@link #tiltPixels} can project a
     * point at any depth, which {@link #project} (plane only) cannot.
     */
    private boolean projectField(Frame f, float fx, float fy, float fz, float[] out) {
        ray[0] = fx; ray[1] = fy; ray[2] = fz; ray[3] = 1f;
        Mat4.multiplyMV(ray, 0, f.mvp, 0, ray, 0);
        if (ray[3] == 0f) return false;
        out[0] = ray[0] / ray[3];
        out[1] = ray[1] / ray[3];
        return true;
    }

    /**
     * The deepest the relief may go at the current zoom without the camera
     * ending up inside it: the relief half-depth (0.5) times the model scale
     * must stay under 0.9x the camera distance.
     */
    public float maxZScale() {
        float s = computeScale();
        return s <= 0f ? 0f : (0.9f * CAM_DIST * camDistScale) / s;
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
     * <p><b>The orbit centre is the pivot, and the pivot has a depth.</b> The
     * pivot is the surface point under the origin --
     * {@code (px, py, pivotHeight * scale * zScale)}, the plane point lifted
     * through the same model-to-world factor {@code uMVP} applies -- and the tilt
     * is a turntable about it, so the subject under the origin holds while the
     * background swings around it. See {@link #setPivotHeight}.
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

        Mat4.perspective(proj, 0, FOV_DEG, surfaceAspect, NEAR, FAR);
        Mat4.identity(panMat, 0);
        if (surfaceW > 0 && surfaceH > 0 && (panX != 0f || panY != 0f)) {
            Mat4.translateM(panMat, 0, -2f * panX / surfaceW, 2f * panY / surfaceH, 0f);
        }
        Mat4.multiplyMM(projPanned, 0, panMat, 0, proj, 0);

        // THE CAMERA IS A TURNTABLE ABOUT THE PIVOT WITH A FIXED REST EYE, and
        // the origin does not translate the picture. The eye is the REST eye --
        // the image centre at the fixed camera distance, looking straight down --
        // rotated about the pivot, and the orientation rotates with it. At rest
        // (tilt 0) the rotation is the identity, so the view is the photograph
        // whatever the origin is; the origin only names the point the tilt turns
        // about. That is what "Origin moves the orbit centre without moving the
        // picture" means, and it is the relief's half of the splat's own
        // convention (SplatCamera.frame): the two backends now mean the same
        // thing.
        //
        // It REPLACES a camera that translated BOTH the eye and the aim by the
        // pivot (px, py), which pinned the pivot to the screen centre -- so the
        // picture was the photograph shifted by the origin, and a tap that set
        // the origin "readjusted the view". The aim is no longer the pivot; the
        // rotation axis is. The pivot keeps its own screen position under the
        // tilt (a rotation about a point leaves that point's view direction
        // unchanged), so the subject under the origin still HOLDS while the
        // background swings, which is what the aim-at-the-pivot was for.
        //
        // The angle is the splat's -- TILT_RANGE / CAM_DIST per unit tilt, times
        // motion -- so the swing is the same at every pivot depth, unlike the old
        // lateral slide whose angle was range / (CAM_DIST - pz).
        float a = tiltX * (TILT_RANGE / (CAM_DIST * camDistScale)) * motion;
        float b = tiltY * (TILT_RANGE / (CAM_DIST * camDistScale)) * motion;
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float cb = (float) Math.cos(b), sb = (float) Math.sin(b);

        // d = restEye - pivot, rotated by R = Rx(b) * Ry(a). At rest R = I and
        // the eye is the rest eye (0, 0, CAM_DIST).
        float dx = -px, dy = -py, dz = CAM_DIST * camDistScale - pz;
        float ex = ca * dx + sa * dz;
        float ey = sb * sa * dx + cb * dy - sb * ca * dz;
        float ez = -cb * sa * dx + sb * dy + cb * ca * dz;
        frame.eyeX = px + ex;
        frame.eyeY = py + ey;
        frame.eyeZ = pz + ez;
        // forward = R * (0, 0, -1), up = R * (0, 1, 0).
        float fwx = -sa, fwy = sb * ca, fwz = -cb * ca;
        float upx = 0f, upy = cb, upz = sb;
        Mat4.lookAt(view, 0, frame.eyeX, frame.eyeY, frame.eyeZ,
                frame.eyeX + fwx, frame.eyeY + fwy, frame.eyeZ + fwz,
                upx, upy, upz);

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
