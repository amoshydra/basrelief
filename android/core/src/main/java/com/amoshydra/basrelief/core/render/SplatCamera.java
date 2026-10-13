package com.amoshydra.basrelief.core.render;

/**
 * The splat cloud's camera: where the eye sits, which way it looks, and the
 * clip-space shift the Origin and Pan rows apply.
 *
 * <p><b>It is the relief's camera one medium over, and it lives in {@code :core}
 * for the same reason {@link CameraModel} does.</b> The values that are easy to
 * get quietly wrong here — "at rest the picture is exactly the photograph, at
 * every origin and every pivot depth" and "the pivot holds the centre under tilt"
 * — are statements about a projection, not about GL, so they are asserted without
 * a device. The renderer keeps only the uniforms.
 *
 * <p><b>The cloud's own frame.</b> The cloud is in the still's camera frame:
 * {@code x} right, {@code y} down, {@code z} forward, principal point at the
 * centre. The SOURCE view is the eye at the origin looking down {@code +z} with
 * the still's vertical field of view; that is the view the cloud reconstructs, so
 * that is the view "the photograph" means. At rest this camera is exactly that
 * source view, and the Origin and Pan rows are a <b>clip-space shift</b> on top of
 * it — which is what makes the at-rest picture the photograph translated, at
 * every depth and whatever the pivot's depth is.
 *
 * <p><b>Why the origin is a shift and not a lateral camera move.</b> The relief
 * moves the eye to the pivot's x/y, which for its flat reference plane is exactly
 * a translation of the picture. A cloud has no single plane, so the same lateral
 * move would show parallax across depths at rest — the picture would not be the
 * photograph. The shift is the only construction that keeps the invariant, and
 * the pivot's depth still drives the orbit below.
 *
 * <p><b>The pivot is the cloud's surface under the origin's ray, and the tilt is
 * a turntable about it.</b> The origin's ray is the source camera's ray through
 * NDC {@code (2*originX, 2*originY)}; {@link #pivot} finds the frontmost splat
 * under it (or the reference-plane point when the cloud has nothing there). The
 * eye then rotates about that point and the orientation rotates with it, so at
 * rest ({@code R = I}) the eye is back at the source position and the view is the
 * source view; the pivot projects to the same NDC under every tilt (its view
 * vector is unchanged by {@code R}), so it holds the centre.
 *
 * <p><b>And the pivot is the surface the SHADER draws, which is why the Depth
 * knob reaches it.</b> The vertex shader moves every splat along the ray from the
 * eye by {@link Splat#depthScale} before it is drawn, so the cloud's surface
 * under the origin sits at the MOVED depth; a pivot resolved from the node's own
 * depths would mark a point the cloud is no longer at at any multiplier but 1.
 * {@link #pivot} therefore picks on the moved depth and returns the moved point,
 * so the crosshair, the aim and the cloud cannot name different pivots. At rest
 * the pivot's value does not enter the view at all — the eye is the source eye
 * whatever the pivot is — so this moves the orbit and the marker and nothing
 * about the picture.
 *
 * <p>Not thread-safe and mutable, exactly as {@link CameraModel} is: the renderer
 * owns one and only ever touches it on the GL thread. {@link #frame} writes into
 * one reusable {@link Frame}, so drawing allocates nothing.
 */
public final class SplatCamera {

    /**
     * The tilt is an ANGLE, not a lateral displacement, so it is the same swing
     * at every pivot depth — a turntable. The angle is the relief's own: its
     * {@code TILT_RANGE} lateral offset at {@code CAM_DIST} is
     * {@code atan(TILT_RANGE / CAM_DIST)} radians, which is the same feel the
     * splat had when its eye was a fixed offset at a fixed target distance.
     */
    public static final float TILT_RANGE = CameraModel.TILT_RANGE;
    public static final float CAM_DIST = CameraModel.CAM_DIST;

    public static final float NEAR = 0.05f;
    public static final float FAR = 4000f;

    /**
     * How near the origin's ray a splat must project to count as "under it", in
     * the camera's tangent-plane units ({@code x/z}, {@code y/z}).
     *
     * <p>It is a window, not a claim: the cloud covers the still, so a ray through
     * any point of the picture finds splats well inside this radius (the demo's
     * 5.6 px cell is ~0.0027 in these units); the radius only decides how many
     * candidates the frontmost pick may consider. A ray that genuinely lands in a
     * hole falls through to the nearest splat and then to the reference plane —
     * see {@link #pivot}.
     */
    public static final float PIVOT_RADIUS = 0.02f;

    /** Everything the renderer needs for one draw, computed in a single pass. */
    public static final class Frame {
        /** World-to-view, column-major, the layout GLSL's mat4 wants. */
        public final float[] view = new float[16];
        /** The base perspective, before the Origin/Pan shift. */
        public final float[] proj = new float[16];
        /** {@code T(origin + pan) * proj} — what the shader's {@code uProj} gets. */
        public final float[] projPanned = new float[16];
        /** {@code projPanned * view}, for a caller that projects a point (tests). */
        public final float[] viewProjection = new float[16];
        public float eyeX, eyeY, eyeZ;
        /** The view direction, {@code R*(0,0,1)}; {@code +z} at rest. */
        public float forwardX, forwardY, forwardZ;
        /** The cloud surface point the orbit turns about. */
        public float pivotX, pivotY, pivotZ;
        /** Whether {@link #pivot} found a splat rather than falling back. */
        public boolean pivotFound;
        /** The NDC shift from Origin + Pan. */
        public float shiftX, shiftY;
    }

    private final Frame frame = new Frame();
    private final float[] shiftMat = new float[16];
    /** Scratch for {@link #originForRay}; the drawn {@link #frame} is left untouched. */
    private final Frame tapPick = new Frame();

    /**
     * Build one frame's camera.
     *
     * @param surfaceW,surfaceH the drawing surface, for the pan fraction
     * @param fovyDeg  the vertical field of view; Zoom is a crop of it
     * @param orbitX,orbitY the Origin row, in {@code [-0.5, 0.5]} of the viewport
     * @param panX,panY the Pan row, in surface pixels (the relief's unit)
     * @param tiltX,tiltY the gyro's smoothed tilt, already in the relief's sign
     * @param motion   the Motion gain; 0 parks the camera exactly as it parks the relief's
     * @param positions the cloud's xyz mirror, {@code count * 3} floats
     * @param count    splats
     * @param refDepth the reference depth the pivot falls back to
     * @param depthMul the Depth knob: the multiplier the vertex shader applies to
     *                 each splat's depth. The pivot is resolved on the MOVED
     *                 surface, so this must be the same clamped value the shader
     *                 gets; 1 is the node's own depth and leaves the pivot where it
     *                 was.
     */
    public Frame frame(float surfaceW, float surfaceH, float fovyDeg,
                       float orbitX, float orbitY, float panX, float panY,
                       float tiltX, float tiltY, float motion,
                       float[] positions, int count, float refDepth, float depthMul) {
        float aspect = surfaceH > 0f ? surfaceW / surfaceH : 1f;
        Mat4.perspective(frame.proj, 0, fovyDeg, aspect, NEAR, FAR);
        float proj0 = frame.proj[0], proj5 = frame.proj[5];
        // The origin's ray, in the camera's tangent plane: the point the source
        // camera projects to NDC (2*orbitX, 2*orbitY). At rest the pivot sits on
        // this ray and the shift below brings it to the centre.
        float tanX = proj0 != 0f ? 2f * orbitX / proj0 : 0f;
        float tanY = proj5 != 0f ? -2f * orbitY / proj5 : 0f;
        pivot(positions, count, tanX, tanY, refDepth, depthMul, frame);

        // The turntable: R = Rx(b) * Ry(-a) about the pivot. The signs are the
        // relief's — positive tiltX moves the eye +x (right), positive tiltY moves
        // it +y in this frame (down), which is the same visual direction the relief
        // moves it in its y-up world.
        float a = tiltX * (TILT_RANGE / CAM_DIST) * motion;
        float b = tiltY * (TILT_RANGE / CAM_DIST) * motion;
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float cb = (float) Math.cos(b), sb = (float) Math.sin(b);

        float px = frame.pivotX, py = frame.pivotY, pz = frame.pivotZ;
        // r = E0 - P = -P, through Ry(-a) then Rx(b).
        float rx = -px, ry = -py, rz = -pz;
        float yx = rx * ca - rz * sa;
        float yy = ry;
        float yz = rx * sa + rz * ca;
        float ex = yx;
        float ey = yy * cb - yz * sb;
        float ez = yy * sb + yz * cb;
        float eyeX = px + ex, eyeY = py + ey, eyeZ = pz + ez;

        // forward = R*(0,0,1), up = R*(0,-1,0). At rest these are +z and -y.
        float fwx = -sa;
        float fwy = -ca * sb;
        float fwz = ca * cb;
        float upx = 0f, upy = -cb, upz = -sb;

        frame.eyeX = eyeX;
        frame.eyeY = eyeY;
        frame.eyeZ = eyeZ;
        frame.forwardX = fwx;
        frame.forwardY = fwy;
        frame.forwardZ = fwz;
        Mat4.lookAt(frame.view, 0, eyeX, eyeY, eyeZ,
                eyeX + fwx, eyeY + fwy, eyeZ + fwz, upx, upy, upz);

        // Origin + Pan as one clip-space shift. The relief's pan sign is kept
        // (-2*panX/W, +2*panY/H) so the row means the same thing in both modes; the
        // origin contributes (-2*originX, -2*originY), which moves the point at NDC
        // (2*originX, 2*originY) — the pivot — to the centre.
        float sx = (surfaceW > 0f ? -2f * panX / surfaceW : 0f) - 2f * orbitX;
        float sy = (surfaceH > 0f ? 2f * panY / surfaceH : 0f) - 2f * orbitY;
        frame.shiftX = sx;
        frame.shiftY = sy;
        Mat4.identity(shiftMat, 0);
        Mat4.translateM(shiftMat, 0, sx, sy, 0f);
        Mat4.multiplyMM(frame.projPanned, 0, shiftMat, 0, frame.proj, 0);
        Mat4.multiplyMM(frame.viewProjection, 0, frame.projPanned, 0, frame.view, 0);
        return frame;
    }

    /**
     * The cloud surface point under the origin's ray, into {@code out}.
     *
     * <p>The ray is {@code (tanX, tanY, 1)}: a splat at {@code (x, y, z)} projects
     * to {@code (x/z, y/z)} in the same tangent plane. The pick is the <b>frontmost
     * splat within {@link #PIVOT_RADIUS}</b> — "the nearest surface under the ray"
     * — because a nearest-in-image pick can name a background splat that happens to
     * lie behind the subject. When no splat is inside the radius the nearest one is
     * used anyway (a real point beats a guess), and when the cloud has nothing in
     * front of the camera at all the answer is the reference-plane point on the
     * ray: a window claims nothing, but the orbit must not vanish.
     *
     * <p><b>The pick is made on the surface the SHADER draws, not on the node's
     * own depths.</b> The Depth knob moves every splat along the ray from the eye
     * by {@link Splat#depthScale} before it is drawn, so the surface under the
     * origin is at the MOVED depth; a pivot resolved from the raw positions marks
     * a point the cloud is no longer at at any multiplier but 1 — the reported
     * "the origin did not follow the depth". The scale preserves {@code x/z} and
     * {@code y/z}, so the ray's radial test is unchanged; what moves is the depth
     * ordering and the chosen point. A splat the knob pushed through the eye — a
     * moved depth of 0, which the vertex shader's near cull drops — is skipped
     * here too, so the orbit can never aim at a point that is not drawn.
     */
    public void pivot(float[] positions, int count, float tanX, float tanY,
                      float refDepth, float depthMul, Frame out) {
        if (positions == null || count <= 0) {
            fallbackPivot(tanX, tanY, refDepth, out);
            return;
        }
        float r2 = PIVOT_RADIUS * PIVOT_RADIUS;
        float frontZ = Float.MAX_VALUE, frontX = 0f, frontY = 0f;
        boolean front = false;
        float nearD2 = Float.MAX_VALUE, nearX = 0f, nearY = 0f, nearZ = 0f;
        boolean near = false;
        for (int i = 0, j = 0; i < count; i++, j += 3) {
            float x = positions[j], y = positions[j + 1], z = positions[j + 2];
            // Behind or on the camera: no image position, so not under any ray.
            if (!(z > 1e-4f)) continue;
            // The scale the shader applies to this splat's whole vector. It keeps
            // the ray (x/z is invariant) and moves the depth, and 0 is the shader's
            // "pushed through the eye" -- culled there, skipped here.
            float dscl = Splat.depthScale(z, refDepth, depthMul);
            if (!(dscl > 0f)) continue;
            float sx = x * dscl, sy = y * dscl, sz = z * dscl;
            float invz = 1f / z;
            float dx = x * invz - tanX;
            float dy = y * invz - tanY;
            float d2 = dx * dx + dy * dy;
            if (d2 < nearD2) {
                nearD2 = d2;
                nearX = sx; nearY = sy; nearZ = sz;
                near = true;
            }
            if (d2 <= r2 && sz < frontZ) {
                frontZ = sz;
                frontX = sx; frontY = sy;
                front = true;
            }
        }
        if (front) {
            out.pivotX = frontX; out.pivotY = frontY; out.pivotZ = frontZ;
            out.pivotFound = true;
        } else if (near) {
            out.pivotX = nearX; out.pivotY = nearY; out.pivotZ = nearZ;
            out.pivotFound = true;
        } else {
            fallbackPivot(tanX, tanY, refDepth, out);
        }
    }

    /**
     * The Origin a tap names: the image position of the frontmost splat under the
     * ray through the tapped pixel.
     *
     * <p><b>It is {@link #pivot}'s own pick, so the tap, the pivot, the aim and the
     * crosshair cannot name different points.</b> The ray is the SOURCE camera's --
     * the eye at the origin looking down {@code +z}, the ray the pivot itself is
     * resolved from -- because the Origin is a fraction of the source image, not of
     * the current tilted view. A ray cast from the tilted eye would move with the
     * pivot, and the pivot moves with the Depth knob: that is exactly the dependence
     * that made the tap land away from the point that was touched.
     *
     * <p>The ray arrives as the source camera's tangent ({@code tanX, tanY}) and the
     * answer is converted back to the Origin fraction with the projection's own
     * factors, so a tap and the resolve of the origin it sets are one mapping -- the
     * fixed point {@code pivot} is aimed by. A ray that holds no splat falls back to
     * the reference-plane point on the ray, exactly as {@link #pivot} does, and that
     * point's image position is the tapped pixel's own: a window claims nothing, but
     * a tap still names a point.
     *
     * <p>The Depth knob reaches the PICK (a splat it pushes through the eye is
     * skipped, as in {@link #pivot}) but not the answer: {@link Splat#depthScale}
     * preserves {@code x/z}, so the picked splat's image position is the same at
     * every multiplier.
     *
     * @param positions the cloud's xyz mirror, {@code count * 3} floats
     * @param tanX,tanY the ray in the source camera's tangent plane
     * @param proj0,proj5 the projection's own factors ({@code Frame.proj[0]}, [5])
     * @param out receives the origin fraction; must hold 2 floats
     * @return true if a splat was picked; false when the reference plane answered
     */
    public boolean originForRay(float[] positions, int count, float tanX, float tanY,
                                float refDepth, float depthMul,
                                float proj0, float proj5, float[] out) {
        pivot(positions, count, tanX, tanY, refDepth, depthMul, tapPick);
        float invz = tapPick.pivotZ > 1e-6f ? 1f / tapPick.pivotZ : 0f;
        out[0] = tapPick.pivotX * invz * proj0 * 0.5f;
        out[1] = -tapPick.pivotY * invz * proj5 * 0.5f;
        return tapPick.pivotFound;
    }

    /**
     * The reference-plane point on the ray. It needs no Depth-knob correction:
     * the plane IS the knob's fixed point, {@code depthScale(refDepth, ...) == 1}
     * for every multiplier, so the point on it does not move.
     */
    private static void fallbackPivot(float tanX, float tanY, float refDepth, Frame out) {
        out.pivotX = tanX * refDepth;
        out.pivotY = tanY * refDepth;
        out.pivotZ = refDepth;
        out.pivotFound = false;
    }
}
