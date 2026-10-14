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
 * source view, and the Pan row is a <b>clip-space shift</b> on top of it — which
 * is what makes the at-rest picture the photograph translated by Pan, at every
 * depth.
 *
 * <p><b>Why Pan is a shift and not a lateral camera move.</b> The relief's camera
 * is a real 3D camera, moved by the tilt and the origin's turntable; a splat cloud
 * has no single plane, so a lateral camera move would show parallax across depths
 * at rest and the picture would not be the
 * photograph. The shift is the only construction that keeps the invariant, and
 * the pivot's depth still drives the orbit below.
 *
 * <p><b>Origin does NOT translate the picture.</b> It names the orbit centre —
 * which ray the pivot is picked from — and nothing else about the at-rest view.
 * It used to contribute a clip-space shift that moved the pivot's ray to the
 * screen centre, so the picture was the photograph shifted by the origin and a
 * tap that set the origin "readjusted the view"; the shift is now Pan's alone.
 * The pivot keeps its own image position on screen and the crosshair marks it
 * there.
 *
 * <p><b>The pivot is the cloud's surface under the origin's ray, and the tilt is
 * a turntable about it.</b> The origin's ray is the source camera's ray through
 * NDC {@code (2*originX, 2*originY)}; {@link #pivot} finds the frontmost splat
 * under it (or the reference-plane point when the cloud has nothing there). The
 * eye then rotates about that point and the orientation rotates with it, so at
 * rest ({@code R = I}) the eye is back at the source position and the view is the
 * source view; the pivot keeps its own screen position under every tilt (its view
 * vector is unchanged by {@code R}).
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
        /** The base perspective, before the Pan shift. */
        public final float[] proj = new float[16];
        /** {@code T(pan) * proj} — what the shader's {@code uProj} gets. */
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
        /** The NDC shift from Pan alone. */
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
     * @param orbitX,orbitY the Origin row: the orbit centre as a fraction of the
     *                      viewport, allowed outside {@code [-0.5, 0.5]} (see
     *                      {@code SurfaceSettings.ORIGIN_LIMIT})
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
        // this ray at its own image position; the turntable below turns the
        // camera about it.
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

        // Pan as one clip-space shift, in the relief's sign (-2*panX/W,
        // +2*panY/H) so the row means the same thing in both modes. The ORIGIN
        // contributes NOTHING here: it must not move the picture, only the point
        // the turntable turns about (the pivot, above) and the crosshair that
        // marks it. It used to subtract (-2*originX, -2*originY), which moved the
        // pivot's ray to the screen centre -- so the picture was the photograph
        // shifted by the origin, and a tap that set the origin "readjusted the
        // view". With the term gone the pivot sits at its own image position and
        // the at-rest picture is the photograph translated by Pan alone.
        float sx = surfaceW > 0f ? -2f * panX / surfaceW : 0f;
        float sy = surfaceH > 0f ? 2f * panY / surfaceH : 0f;
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

    // --------------------------------------------- the near-plane pull-back

    /**
     * The largest share of the viewport's height a drawn splat's 3-sigma extent may
     * cover, and the near-plane pull-back's one number.
     *
     * <p><b>What it fixes.</b> The vertex shader drops a splat whose 3-sigma extent
     * exceeds half the frame (gsplat's radius clip) because a splat that close to
     * the camera costs a screenful of fragments to lay down a wash. Measured on the
     * phone at 669k splats: 922 ms a frame with those drawn, 76 ms without them.
     * Dropping is not free -- it leaves the hole the wash was covering -- so this
     * pull-back keeps the splat and bounds what it costs instead.
     *
     * <p><b>It is a MINIMUM VIEW DISTANCE, applied per splat, and that is the only
     * construction that works.</b> A splat nearer than its own minimum is moved back
     * along its own view ray until it sits at it: the view-space position is scaled
     * about the eye, so the splat's screen position is EXACTLY unchanged and its
     * projected covariance falls by the square of the scale (the perspective
     * Jacobian goes as {@code 1/depth}). The DEPTH is what changes, so the picture
     * is not re-framed -- and that is what "no fov compensation" has to mean here:
     * the size is deliberately NOT scaled with the position, so a splat that was too
     * near is drawn <em>smaller</em> rather than drawn whole.
     *
     * <p><b>Why it is not a camera move, and why the minimum cannot be one depth.</b>
     * Pulling the EYE back breaks {@link #frame}'s invariant -- "at rest the picture
     * is exactly the photograph" -- for every splat off the reference plane: a point
     * at depth {@code z} then projects to {@code f x/(z+d)}, which equals the
     * photograph only at the plane, whatever focal is chosen. Moving the splat
     * instead leaves the frame untouched, which is what {@code SplatCameraTest}
     * guards. And the threshold cannot be a single depth derived from the worst
     * splat: a cloud's {@code size/depth} is heavy-tailed (measured across the clouds
     * in the tree, the largest 3D axis over the median is 7-245x), so one deep
     * outlier would pull back -- and visibly shrink -- the whole near field. Tying
     * the minimum to each splat's OWN size touches exactly the splats whose extent is
     * the problem. Measured on five real clouds (149k to 1.18M splats), the largest
     * {@code size/depth} is 0.013, against the default's threshold of 0.028 at the
     * narrowest framing the app offers, so none of them is touched at all.
     *
     * <p><b>The number.</b> A splat of size {@code s} at view depth {@code z} has a
     * 3-sigma extent of about {@code 1.5 * proj5 * s / z} pixels -- {@code 3 * sigma}
     * times the focal in pixels ({@code 0.5 * H * proj5}) over the depth -- so
     * bounding it by {@code fraction * H} gives {@code z >= 1.5 * proj5 * s /
     * fraction}. That bound is the ON-AXIS extent: the Jacobian's off-axis term can
     * magnify it by up to {@code sqrt(1 + tan^2)}, which is why the default is 0.1
     * rather than nearer the drop threshold -- even at the frame corner the clamped
     * splat stays under the radius clip, so it is <b>drawn rather than dropped</b>,
     * which is the whole point. What it costs is fill, and fill is linear in the
     * square of this fraction.
     */
    public static final float MAX_EXTENT_FRACTION = 0.1f;

    /**
     * The minimum view distance per unit of a splat's largest 3D axis: the depth the
     * splat must sit at for its 3-sigma extent to be at most {@code
     * maxExtentFraction} of the viewport's height. Multiply by that axis to get the
     * splat's own minimum -- the one uniform {@code SplatRenderer} derives per
     * framing and the vertex shader multiplies by each splat's size.
     *
     * <p>This is the O(1) form of a quantity the design expected to need an
     * {@code O(count)} pass (the nearest drawn splat's depth). It does not: the
     * splat's size is already in the vertex data, and the minimum is proportional to
     * it, so the only per-framing input is the projection's own {@code proj5}. A
     * cloud with nothing near the camera is therefore untouched by construction --
     * no splat satisfies the clamp -- with no pass over the cloud at all.
     *
     * <p>0 for a projection or a fraction that cannot claim one; the caller reads
     * that as "no pull-back", the "a window claims nothing" rule applied to a
     * distance, so a bad uniform cannot pull the whole cloud back to nowhere.
     */
    public static float minViewDistancePerSize(float proj5, float maxExtentFraction) {
        if (!Float.isFinite(proj5) || proj5 <= 0f) return 0f;
        if (!Float.isFinite(maxExtentFraction) || maxExtentFraction <= 0f) return 0f;
        return 1.5f * proj5 / maxExtentFraction;
    }

    /**
     * The minimum view distance for a splat whose largest 3D axis is {@code size}.
     * See {@link #minViewDistancePerSize}; 0 when the size cannot claim one, which
     * leaves the splat where it is.
     */
    public static float minViewDistance(float size, float proj5, float maxExtentFraction) {
        if (!Float.isFinite(size) || size <= 0f) return 0f;
        return size * minViewDistancePerSize(proj5, maxExtentFraction);
    }

    // ------------------------------------------------------------- the sort gate

    /**
     * Half a screen pixel: the tolerance the DERIVED bound aims at.
     *
     * <p><b>Why a sub-pixel move cannot visibly reorder anything, and where that
     * argument stops.</b> The order can only change when the RELATIVE view depth of
     * two splats swaps, and under a small camera change that happens only between
     * splats at nearly equal depth. Two splats at nearly equal depth that also
     * overlap on screen are drawn on top of one another, so which of the two wins is
     * not a difference the eye can name; a camera change too small to move any pixel
     * perceptibly therefore cannot visibly reorder the cloud. That is the argument,
     * and it is deliberately stated as what it is: <b>"sub-pixel staleness" is a
     * weaker claim than "cannot visibly reorder"</b>, and the latter was NOT
     * verified on a phone. What this file pins is the sub-pixel half, which is
     * arithmetic and checkable; the visual half is the user's eye.
     */
    public static final float SUBPIXEL_PX = 0.5f;

    /**
     * The tilt gate the derived bound replaces -- {@code SplatRenderer.needsSort}'s
     * constant {@code 1e-3} -- and the FLOOR under it: the derived gate is
     * {@code max(this, SUBPIXEL_PX / pxPerTilt)}, so it may only ever LOOSEN the
     * gate, never tighten it.
     *
     * <p><b>Why the floor is the right form and not merely the safe one.</b> The
     * derived bound alone is not sub-pixel at every Motion: at the device's own
     * Motion (the bundled row stores {@code motion 100}, and {@code motionScale} is
     * {@code motion/100*3}, so 3.0) the exact bound is 564 px/tilt and its sub-pixel
     * gate is 8.9e-4 -- <b>tighter</b> than the constant, which permits 0.56 px
     * there. A gate that tightened would sort MORE than today, and there is no
     * defect to fix: {@code 1e-3} has shipped at every Motion and no ordering
     * artifact has been reported at high Motion. So the constant is empirically
     * ADEQUATE where it is loose enough, and merely too tight at low Motion (where
     * the sub-pixel gate is 2.7e-3 at Motion 33). The derived bound's job is
     * therefore to exploit the headroom the constant leaves, not to impose a
     * tighter physical rule than the one in the field: it raises the gate where the
     * framing allows and leaves it exactly alone where it does not. The honest cost
     * is recorded rather than hidden -- at Motion above ~89% the gate IS the
     * constant, so the sub-pixel property is the constant's there, not this bound's.
     *
     * <p><b>Motion is the axis that moves this most, and the one the measurement
     * forgot.</b> {@code docs/SORT-MEASUREMENTS.md}'s "183 px/tilt" is at
     * {@code motionScale 1} (Motion 33, the default) and the doc did not say so,
     * which is how a device at Motion 100 appeared to be three times looser than it
     * is. {@link #pxPerTilt} scales linearly with Motion, so the crossover is
     * {@code motionScale < 0.5 * 1/1e-3 / 183 = 2.73} -- Motion below ~91% on that
     * cloud, measured 2.66 (Motion ~89%) on the device's own.
     */
    public static final float SHIPPED_SORT_EPS = 1e-3f;

    /**
     * A hair over the sampled displacement, so the bound is an inequality and not a
     * coincidence: the arithmetic below is the displacement's first derivative, and
     * the drawn displacement is not exactly linear in the delta (measured against
     * the real {@link #frame} chain, 0.03-0.08% UNDER it at the deltas the gate
     * uses). It is the derived bound's only slack -- the max below is over EVERY
     * splat, so nothing else in it is an over-estimate.
     */
    private static final float LINEAR_MARGIN = 1.005f;

    /** The eight tilt directions the bound is maximised over. */
    private static final float[] DIR_COS = new float[8];
    private static final float[] DIR_SIN = new float[8];

    static {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            DIR_COS[i] = (float) Math.cos(a);
            DIR_SIN[i] = (float) Math.sin(a);
        }
    }

    /**
     * The largest VISIBLE screen displacement per unit tilt at this framing,
     * measured over EVERY splat in the cloud.
     *
     * <p><b>It is the cloud, not a summary of it.</b> The bound is a max over the
     * splats, so it dominates the cloud by construction and needs no superset
     * argument. It is {@code O(count)}, so it is deliberately NOT called per frame:
     * {@code SplatRenderer} derives it once per framing -- at upload and whenever the
     * zoom, origin, pan, motion or surface changes -- and reuses it while the gyro
     * moves the tilt, which is the whole of a tilt. That is the same shape as the
     * cloud's other one-off derivations ({@code medianDepth}, {@code refDepth}), and
     * it is why the slab summary that preceded it was reached for at all (it
     * over-estimated by up to 1.6x, which the floor now makes harmless but which
     * would have thrown away the lever).
     *
     * <p><b>Why the pivot is in it and not in a precomputed coefficient.</b> The
     * pivot's tangent is the ORIGIN's ray ({@code 2*orbitX/proj0}, {@code
     * -2*orbitY/proj5}) and its depth is the frontmost splat under that ray, so both
     * are framing values: a coefficient taken at one reference framing and scaled by
     * zoom and motion alone would under-estimate at every other origin (measured
     * 1.15x between a centred and the device's origin) and the gate would skip a sort
     * it should have taken. The pivot is resolved here, per framing, from the frame
     * the draw actually uses.
     *
     * <p><b>The arithmetic.</b> The tilt rotates the eye about the pivot by {@code
     * TILT_RANGE / CAM_DIST * motion} per unit on each axis. Writing {@code U, V} for
     * a unit tilt direction, {@code r = pivotZ / z} for a splat's depth relative to
     * the pivot and {@code (tanXp, tanYp)} for the pivot's image tangent, the
     * first-order screen displacement of a splat at tangent {@code (tx, ty)} is
     *
     * <pre>
     *   q  = U (tx - r tanXp) + V (ty - r tanYp)
     *   dx = fpx * s * ( U (1 - r) + tx q )
     *   dy = fpx * s * ( V (1 - r) + ty q )
     * </pre>
     *
     * with {@code fpx = surfaceH / (2 tan(fovy/2))} and {@code s = (TILT_RANGE /
     * CAM_DIST) motion}. {@code SplatCameraTest} pins it against the real {@link
     * #frame} chain. Only a VISIBLE splat counts: above Zoom 100% the picture is
     * larger than the viewport and its off-screen splats have displacements nobody
     * can see.
     *
     * <p>A framing that cannot claim a value (no cloud, no surface, a pivot that is
     * not a distance) answers 0; the caller then takes the floor.
     *
     * @param f the rest frame, as {@link #frame} built it for this draw
     */
    public float pxPerTilt(float surfaceW, float surfaceH, float motion,
                           Frame f, float[] positions, int count) {
        if (surfaceW <= 0f || surfaceH <= 0f || positions == null || count <= 0) return 0f;
        float proj0 = f.proj[0], proj5 = f.proj[5];
        float pz = f.pivotZ;
        if (proj0 == 0f || proj5 == 0f || !(pz > 0f)) return 0f;
        float fpx = 0.5f * surfaceH * proj5;
        float s = (TILT_RANGE / CAM_DIST) * motion;
        if (!(fpx > 0f) || !(s > 0f)) return 0f;
        float tanXp = f.pivotX / pz, tanYp = f.pivotY / pz;
        float best = 0f;
        for (int i = 0, j = 0; i < count; i++, j += 3) {
            float x = positions[j], y = positions[j + 1], z = positions[j + 2];
            if (!(z > 1e-4f)) continue;
            float invz = 1f / z;
            // The drawn NDC is (proj0*tx + shiftX, -proj5*ty + shiftY): the view
            // flips the cloud's y-down axis, so the visibility test carries the sign.
            float ndcx = proj0 * x * invz + f.shiftX;
            float ndcy = -proj5 * y * invz + f.shiftY;
            if (ndcx > 1f || ndcx < -1f || ndcy > 1f || ndcy < -1f) continue;
            float tx = x * invz, ty = y * invz;
            float r = pz * invz;
            float oneR = 1f - r;
            float ax = tx - r * tanXp, ay = ty - r * tanYp;
            for (int k = 0; k < 8; k++) {
                float U = DIR_COS[k], V = DIR_SIN[k];
                float q = U * ax + V * ay;
                float dx = U * oneR + tx * q;
                float dy = V * oneR + ty * q;
                float d2 = dx * dx + dy * dy;
                if (d2 > best) best = d2;
            }
        }
        return LINEAR_MARGIN * fpx * s * (float) Math.sqrt(best);
    }

    /**
     * The sort gate's live threshold: the derived sub-pixel tilt, floored at
     * {@link #SHIPPED_SORT_EPS} so the gate can only ever LOOSEN.
     *
     * <p>A framing that cannot claim a bound answers the floor -- the shipped
     * constant -- which is both the conservative direction and the no-regression
     * rule: an absent cloud must not tighten a gate that is shipping.
     *
     * @param f the rest frame, as {@link #frame} built it for this draw
     */
    public float sortEps(float surfaceW, float surfaceH, float motion,
                         Frame f, float[] positions, int count) {
        float px = pxPerTilt(surfaceW, surfaceH, motion, f, positions, count);
        if (!(px > 0f)) return SHIPPED_SORT_EPS;
        return Math.max(SHIPPED_SORT_EPS, SUBPIXEL_PX / px);
    }

    /**
     * The largest visible screen displacement, in pixels, that a tilt of {@code
     * delta} produces -- measured over EVERY splat through the real {@link #frame}
     * chain, over the same eight directions.
     *
     * <p>This is the ground truth {@link #pxPerTilt} is checked against, and the
     * shape {@link CameraModel#tiltPixels} already established for the relief: the
     * rest frame projects every splat, each tilted frame projects it again, and the
     * answer is the worst visible difference in pixels. It is {@code O(count)} per
     * framing and is NOT the draw path's bound -- {@link #sortEps} is. Only a
     * visible splat counts.
     *
     * @param delta tilt delta in the normalised units the gyro reports (0..1 over
     *              {@link #TILT_RANGE})
     */
    public float tiltPixels(float delta, float surfaceW, float surfaceH, float fovyDeg,
                            float orbitX, float orbitY, float panX, float panY, float motion,
                            float[] positions, int count, float refDepth, float depthMul) {
        if (delta == 0f || surfaceW <= 0f || surfaceH <= 0f
                || positions == null || count <= 0) return 0f;
        float[] restX = new float[count], restY = new float[count];
        float[] out = new float[2];
        Frame rest = frame(surfaceW, surfaceH, fovyDeg, orbitX, orbitY, panX, panY, 0f, 0f,
                motion, positions, count, refDepth, depthMul);
        for (int i = 0, j = 0; i < count; i++, j += 3) {
            if (project(rest, positions[j], positions[j + 1], positions[j + 2], out)) {
                restX[i] = out[0];
                restY[i] = out[1];
            } else {
                restX[i] = Float.NaN;
            }
        }
        float best = 0f;
        for (int d = 0; d < 8; d++) {
            Frame tilted = frame(surfaceW, surfaceH, fovyDeg, orbitX, orbitY, panX, panY,
                    DIR_COS[d] * delta, DIR_SIN[d] * delta, motion,
                    positions, count, refDepth, depthMul);
            for (int i = 0, j = 0; i < count; i++, j += 3) {
                if (Float.isNaN(restX[i])) continue;
                if (Math.abs(restX[i]) > 1f || Math.abs(restY[i]) > 1f) continue;
                if (!project(tilted, positions[j], positions[j + 1], positions[j + 2], out)) continue;
                float dx = (out[0] - restX[i]) * surfaceW * 0.5f;
                float dy = (out[1] - restY[i]) * surfaceH * 0.5f;
                float disp = (float) Math.hypot(dx, dy);
                if (disp > best) best = disp;
            }
        }
        return best;
    }

    /** Scratch for {@link #project}; the drawn {@link #frame} is left untouched. */
    private final float[] projScratch = new float[4];

    /**
     * Project a world point through a frame's {@code projPanned * view} into NDC,
     * into {@code out}. False when the point is behind the camera, where there is no
     * image position to have moved.
     */
    private boolean project(Frame f, float x, float y, float z, float[] out) {
        projScratch[0] = x;
        projScratch[1] = y;
        projScratch[2] = z;
        projScratch[3] = 1f;
        Mat4.multiplyMV(projScratch, 0, f.viewProjection, 0, projScratch, 0);
        float w = projScratch[3];
        if (!(w > 0f)) return false;
        out[0] = projScratch[0] / w;
        out[1] = projScratch[1] / w;
        return true;
    }
}
