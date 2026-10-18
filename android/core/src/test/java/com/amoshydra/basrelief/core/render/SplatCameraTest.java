package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.amoshydra.basrelief.core.splat.SplatProjection;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

/**
 * The splat camera, which used to exist only inside {@code SplatRenderer} and so
 * could only be judged by eye on a device.
 *
 * <p>The property this class exists for is the same one {@code CameraModelTest}
 * pins for the relief, one medium over: <b>at rest, at the default Depth, the
 * picture is exactly the photograph — translated by Pan alone, Origin naming the
 * orbit centre and not moving the picture — at every origin and every pivot
 * depth.</b> For a cloud that means the at-rest projection equals the SOURCE
 * view's, shifted by the pan, at every depth, and the pivot's depth changes
 * nothing about it.
 *
 * <p>The second property is that the pivot holds the screen centre under a tilt
 * while everything else swings: the turntable turns the camera about the cloud's
 * surface under the origin, not about a plane behind it.
 *
 * <p>Every projection here goes through {@link Mat4} and the real
 * {@link SplatCamera.Frame#viewProjection}, never a restatement of the maths — a
 * formula can be re-derived to match itself, a projection cannot.
 */
public class SplatCameraTest {

    private static final float SURFACE_W = 1080f, SURFACE_H = 2340f;
    private static final float FOVY = 64.3f;
    private static final float REF_DEPTH = 2.4f;

    private SplatCamera camera() {
        return new SplatCamera();
    }

    /** A cloud that puts a splat at tangent {@code (tx, ty)} and depth {@code z}. */
    private static float[] cloudAt(float tx, float ty, float z, float... extra) {
        float[] p = new float[3 + extra.length];
        p[0] = tx * z;
        p[1] = ty * z;
        p[2] = z;
        System.arraycopy(extra, 0, p, 3, extra.length);
        return p;
    }

    private static float[] project(SplatCamera.Frame f, float x, float y, float z) {
        float[] v = {x, y, z, 1f};
        Mat4.multiplyMV(v, 0, f.viewProjection, 0, v, 0);
        return new float[]{v[0] / v[3], v[1] / v[3]};
    }

    /** The source view's projection of a point: eye at the origin, looking +z. */
    private static float[] sourceNdc(float fovyDeg, float aspect, float x, float y, float z) {
        float[] proj = new float[16];
        Mat4.perspective(proj, 0, fovyDeg, aspect, SplatCamera.NEAR, SplatCamera.FAR);
        float[] v = {x, -y, -z, 1f};
        Mat4.multiplyMV(v, 0, proj, 0, v, 0);
        return new float[]{v[0] / v[3], v[1] / v[3]};
    }

    /** The projection's own factors, which turn an image tangent into NDC. */
    private static float[] projectionFactors(float fovyDeg, float aspect) {
        float[] proj = new float[16];
        Mat4.perspective(proj, 0, fovyDeg, aspect, SplatCamera.NEAR, SplatCamera.FAR);
        return new float[]{proj[0], proj[5]};
    }

    /** A splat's view-space position: {@code view * (x, y, z, 1)}. */
    private static float[] viewPosition(SplatCamera.Frame f, float x, float y, float z) {
        float[] v = {x, y, z, 1f};
        Mat4.multiplyMV(v, 0, f.view, 0, v, 0);
        return v;
    }

    /**
     * The NDC a VIEW-space point projects to through the frame's panned projection
     * -- the one the shader draws with. The pull-back scales a view position about
     * the eye, so this is the thing that must not move.
     */
    private static float[] ndcOf(SplatCamera.Frame f, float vx, float vy, float depth) {
        float[] v = {vx, vy, -depth, 1f};
        Mat4.multiplyMV(v, 0, f.projPanned, 0, v, 0);
        return new float[]{v[0] / v[3], v[1] / v[3]};
    }

    /**
     * The 3-sigma extents the vertex shader would emit for a splat of size
     * {@code s} at a view position, through {@link SplatProjection} -- the same
     * chain, not a restatement of it. The view rotation is the frame's, in the
     * row-major order {@code SplatProjection} wants.
     */
    private static float[] extents(SplatCamera.Frame f, float s,
                                   float vx, float vy, float depth) {
        float[] viewRot = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) viewRot[r * 3 + c] = f.view[c * 4 + r];
        }
        float[] cov2 = new float[3];
        SplatProjection.project(s, s, s, 1f, 0f, 0f, 0f, viewRot, vx, vy, depth,
                0.5f * SURFACE_W * f.proj[0], 0.5f * SURFACE_H * f.proj[5], cov2);
        float[] out = new float[2];
        SplatProjection.extents(cov2[0], cov2[1], cov2[2], 1f, out);
        return out;
    }

    // ------------------------------------------------------- the at-rest picture

    /**
     * THE INVARIANT. At rest the projection of a cloud point is the source
     * projection of the same point, shifted by the PAN — for several origins, both
     * pans, and, in the companion test, several pivot depths. ORIGIN does not
     * appear: it names the orbit centre and nothing about the at-rest picture.
     *
     * <p>It is deliberately a cloud of points at different depths: the shift has
     * to be exact at every depth, which is the whole reason the pan is a
     * clip-space shift rather than a lateral camera move.
     */
    @Test
    public void atRestThePictureIsThePhotographShiftedByPan() {
        float aspect = SURFACE_W / SURFACE_H;
        float[] cloud = new float[]{
                0f, 0f, 2.4f,
                0.1f, -0.2f, 1.1f,
                -0.3f, 0.25f, 4.7f,
                0.4f, 0.4f, 0.6f,
        };
        for (float ox : new float[]{-0.5f, -0.2f, 0f, 0.15f, 0.5f}) {
            for (float oy : new float[]{-0.4f, 0f, 0.3f}) {
                for (float panX : new float[]{0f, 120f, -300f}) {
                    for (float panY : new float[]{0f, -75f}) {
                        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                                ox, oy, panX, panY, 0f, 0f, 1f, cloud, cloud.length / 3, REF_DEPTH, 1f);
                        float shiftX = -2f * panX / SURFACE_W;
                        float shiftY = 2f * panY / SURFACE_H;
                        for (int i = 0, j = 0; i < cloud.length / 3; i++, j += 3) {
                            float[] want = sourceNdc(FOVY, aspect,
                                    cloud[j], cloud[j + 1], cloud[j + 2]);
                            float[] got = project(f, cloud[j], cloud[j + 1], cloud[j + 2]);
                            assertEquals("x at origin " + ox + "," + oy + " pan " + panX + "," + panY
                                            + " point " + i,
                                    want[0] + shiftX, got[0], 1e-4f);
                            assertEquals("y at origin " + ox + "," + oy + " pan " + panX + "," + panY
                                            + " point " + i,
                                    want[1] + shiftY, got[1], 1e-4f);
                        }
                    }
                }
            }
        }
    }

    /**
     * The pivot's depth does not enter the at-rest view. Two clouds put their
     * surface under the origin at very different depths; the same origin must
     * project every probe point identically.
     *
     * <p>This is the splat's version of the relief's "the eye does not move with
     * the pivot": the turntable rotates about the pivot, and {@code R = I} at
     * rest puts the eye back at the source position whatever the pivot is.
     */
    @Test
    public void thePivotDepthDoesNotChangeTheAtRestPicture() {
        // Same ray (tangent 0,0), two very different surface depths.
        float[] near = cloudAt(0f, 0f, 0.8f);
        float[] far = cloudAt(0f, 0f, 6.0f);
        for (float ox : new float[]{0f, 0.3f, -0.45f}) {
            SplatCamera nearCam = camera();
            SplatCamera farCam = camera();
            SplatCamera.Frame a = nearCam.frame(SURFACE_W, SURFACE_H, FOVY,
                    ox, 0.2f, 0f, 0f, 0f, 0f, 1f, near, 1, REF_DEPTH, 1f);
            SplatCamera.Frame b = farCam.frame(SURFACE_W, SURFACE_H, FOVY,
                    ox, 0.2f, 0f, 0f, 0f, 0f, 1f, far, 1, REF_DEPTH, 1f);
            assertTrue("the two clouds must resolve different pivots", a.pivotZ != b.pivotZ);
            // And the eye is the source eye in both.
            assertEquals(0f, a.eyeX, 0f);
            assertEquals(0f, a.eyeY, 0f);
            assertEquals(0f, a.eyeZ, 0f);
            for (float z : new float[]{0.6f, 2.4f, 5f}) {
                for (float x : new float[]{-0.4f, 0f, 0.4f}) {
                    float[] pa = project(a, x, 0.1f, z);
                    float[] pb = project(b, x, 0.1f, z);
                    assertEquals("pivot depth must not move the picture (x=" + x + " z=" + z + ")",
                            pa[0], pb[0], 1e-6f);
                    assertEquals("pivot depth must not move the picture (x=" + x + " z=" + z + ")",
                            pa[1], pb[1], 1e-6f);
                }
            }
        }
    }

    /**
     * Origin keeps its own image position on screen: the pivot for the origin
     * {@code (ndc/2)} projects to that same NDC at rest. It does NOT bring the
     * point to the centre — the origin now names the orbit centre and must not
     * move the picture.
     */
    @Test
    public void theOriginKeepsItsRayAtItsOwnImagePosition() {
        float aspect = SURFACE_W / SURFACE_H;
        for (float ndcX : new float[]{-0.8f, -0.3f, 0f, 0.45f, 0.9f}) {
            for (float ndcY : new float[]{-0.7f, 0f, 0.6f}) {
                float ox = ndcX / 2f, oy = ndcY / 2f;
                // A splat exactly on the ray the origin names.
                float[] proj = new float[16];
                Mat4.perspective(proj, 0, FOVY, aspect, SplatCamera.NEAR, SplatCamera.FAR);
                float tx = ndcX / proj[0];
                float ty = -ndcY / proj[5];
                float[] cloud = cloudAt(tx, ty, 3.1f);
                SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                        ox, oy, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
                assertTrue("the pivot must be the splat on the ray", f.pivotFound);
                float[] p = project(f, f.pivotX, f.pivotY, f.pivotZ);
                assertEquals("pivot x at ndc " + ndcX + "," + ndcY, ndcX, p[0], 1e-4f);
                assertEquals("pivot y at ndc " + ndcX + "," + ndcY, ndcY, p[1], 1e-4f);
            }
        }
    }

    // --------------------------------------------------------------- the orbit

    /**
     * The turntable, and the property that makes it one: under a tilt the pivot
     * keeps the SAME screen position it had at rest — which is its own image
     * position, not the centre — while a point off the pivot swings. The eye also
     * leaves the origin and stays the same distance from the pivot — it rotates,
     * it does not translate.
     */
    @Test
    public void thePivotHoldsAndTheCloudSwingsUnderTilt() {
        float aspect = SURFACE_W / SURFACE_H;
        float[] proj = new float[16];
        Mat4.perspective(proj, 0, FOVY, aspect, SplatCamera.NEAR, SplatCamera.FAR);
        for (float ox : new float[]{-0.3f, 0f, 0.35f}) {
            for (float[] tilt : new float[][]{{0.16f, 0f}, {0f, -0.16f}, {0.12f, 0.09f}}) {
                // A splat exactly on the origin's ray is the pivot; two others are
                // off it and must swing.
                float tx = 2f * ox / proj[0];
                float[] cloud = cloudAt(tx, 0f, 2.4f,
                        0.2f, 0.15f, 3.0f,
                        -0.25f, 0.3f, 1.5f);
                SplatCamera.Frame rest = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                        ox, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, 1f);
                assertTrue("the pivot is the splat on the ray", rest.pivotFound);
                float[] pRest = project(rest, rest.pivotX, rest.pivotY, rest.pivotZ);
                SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                        ox, 0f, 0f, 0f, tilt[0], tilt[1], 1f, cloud, 3, REF_DEPTH, 1f);
                float[] pivot = project(f, f.pivotX, f.pivotY, f.pivotZ);
                assertEquals("pivot holds x (tilt " + tilt[0] + "," + tilt[1] + " origin " + ox + ")",
                        pRest[0], pivot[0], 1e-4f);
                assertEquals("pivot holds y (tilt " + tilt[0] + "," + tilt[1] + " origin " + ox + ")",
                        pRest[1], pivot[1], 1e-4f);
                // And it is the origin's own image position, not the centre.
                assertEquals("the pivot sits at its own image x", 2f * ox, pivot[0], 1e-4f);
                // A point away from the pivot moves: the reveal.
                float[] other = project(f, cloud[3], cloud[4], cloud[5]);
                assertTrue("a point off the pivot must swing",
                        Math.hypot(other[0] - pivot[0], other[1] - pivot[1]) > 1e-3f);
            }
        }
    }

    /** At rest the eye is the source eye and the view is the source view. */
    @Test
    public void atRestTheEyeIsTheSourceEye() {
        float[] cloud = cloudAt(0.1f, 0.05f, 2.4f);
        for (float ox : new float[]{-0.4f, 0f, 0.4f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    ox, -ox, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
            assertEquals(0f, f.eyeX, 1e-6f);
            assertEquals(0f, f.eyeY, 1e-6f);
            assertEquals(0f, f.eyeZ, 1e-6f);
        }
    }

    /**
     * A tilt is a rotation, not a lateral slide: the eye keeps its distance to the
     * pivot. A slide would change the distance and the reveal would breathe.
     */
    @Test
    public void tiltRotatesTheEyeAboutThePivotWithoutChangingTheDistance() {
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        SplatCamera c = camera();
        SplatCamera.Frame rest = c.frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
        float px = rest.pivotX, py = rest.pivotY, pz = rest.pivotZ;
        float r0 = (float) Math.sqrt(px * px + py * py + pz * pz);
        for (float[] tilt : new float[][]{{0.16f, 0f}, {0f, 0.16f}, {0.1f, -0.12f}}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    0f, 0f, 0f, 0f, tilt[0], tilt[1], 1f, cloud, 1, REF_DEPTH, 1f);
            float r = (float) Math.sqrt(
                    (f.eyeX - px) * (f.eyeX - px)
                            + (f.eyeY - py) * (f.eyeY - py)
                            + (f.eyeZ - pz) * (f.eyeZ - pz));
            assertEquals("the eye must stay on its sphere (tilt "
                    + tilt[0] + "," + tilt[1] + ")", r0, r, 1e-4f);
            assertTrue("the eye must actually move",
                    Math.abs(f.eyeX) + Math.abs(f.eyeY) > 1e-5f);
        }
    }

    /**
     * The tilt signs are the relief's: positive tiltX moves the eye to +x (right),
     * positive tiltY to +y in the cloud's y-down frame (down). A flipped axis is
     * the parallax running the wrong way, which no screenshot at rest shows.
     */
    @Test
    public void theTiltSignsMatchTheRelief() {
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        SplatCamera.Frame x = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0.16f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
        assertTrue("positive tiltX moves the eye right", x.eyeX > 1e-4f);
        assertEquals("and not vertically", 0f, x.eyeY, 1e-6f);
        SplatCamera.Frame y = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0.16f, 1f, cloud, 1, REF_DEPTH, 1f);
        assertTrue("positive tiltY moves the eye down (cloud +y)", y.eyeY > 1e-4f);
        assertEquals("and not horizontally", 0f, y.eyeX, 1e-6f);
    }

    /** Motion 0 parks the camera exactly as it parks the relief's. */
    @Test
    public void motionZeroParksTheCamera() {
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0.16f, 0.16f, 0f, cloud, 1, REF_DEPTH, 1f);
        assertEquals(0f, f.eyeX, 0f);
        assertEquals(0f, f.eyeY, 0f);
        assertEquals(0f, f.eyeZ, 0f);
    }

    // ---------------------------------------------------------------- the pivot

    /**
     * The pivot is the FRONTMOST splat under the ray, not the nearest in image
     * space: a background splat can project to the same pixel as the subject.
     */
    @Test
    public void pivotPicksTheFrontmostSplatUnderTheRay() {
        // Both on the ray (tangent 0,0); the back one is nearer the image centre
        // only because it is behind the front one.
        float[] cloud = new float[]{
                0f, 0f, 5.0f,     // far
                0f, 0f, 2.0f,     // near
        };
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH, 1f);
        assertTrue(f.pivotFound);
        assertEquals("the near splat is the pivot", 2.0f, f.pivotZ, 1e-6f);
    }

    /**
     * The pivot prefers the frontmost splat under the ray, but a cloud with
     * nothing inside the ray's radius still answers a real point (the nearest
     * splat) rather than a guess; only a cloud with nothing in front of the
     * camera at all falls back to the reference plane on the ray.
     */
    @Test
    public void pivotFallsBackWhenTheCloudHasNothingUnderTheRay() {
        // One splat far off to the side: outside the radius, so the middle
        // fallback (the nearest splat) is what answers.
        float[] cloud = new float[]{5f, 5f, 2f};
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
        assertTrue("a real splat is used rather than a guess", f.pivotFound);
        assertEquals(2f, f.pivotZ, 1e-6f);

        // No splat in front of the camera: the reference-plane point on the ray.
        SplatCamera.Frame empty = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH, 1f);
        assertFalse("an empty cloud cannot claim a splat", empty.pivotFound);
        assertEquals("the fallback is the reference-plane point on the ray",
                0f, empty.pivotX, 1e-6f);
        assertEquals(0f, empty.pivotY, 1e-6f);
        assertEquals(REF_DEPTH, empty.pivotZ, 1e-6f);
        // And on an off-axis origin the fallback point is on that ray, so it sits at
        // the origin's own image position (2*orbit), not the centre.
        SplatCamera.Frame off = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0.25f, -0.2f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH, 1f);
        float[] p = project(off, off.pivotX, off.pivotY, off.pivotZ);
        assertEquals(0.5f, p[0], 1e-4f);
        assertEquals(-0.4f, p[1], 1e-4f);
    }

    /** A splat behind the camera is not under any ray. */
    @Test
    public void pivotIgnoresSplatsBehindTheCamera() {
        float[] cloud = new float[]{0f, 0f, -3f, 0f, 0f, 1.5f};
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH, 1f);
        assertEquals(1.5f, f.pivotZ, 1e-6f);
    }

    /**
     * An origin OUTSIDE the cloud is now reachable — {@code SurfaceSettings.ORIGIN_LIMIT}
     * is 2.0 — so its ray may hold no splat at all. The pick must still answer a
     * finite point and the frame a finite camera: the control must not be able to
     * produce a NaN aim, which would poison the whole view matrix.
     *
     * <p>With splats in front of the camera the fallback is the nearest one (a real
     * point beats a guess); with an empty cloud it is the reference-plane point on
     * the ray. Both are finite, and the eye/forward are too.
     */
    @Test
    public void anOriginOutsideTheCloudStillResolvesAFiniteAim() {
        float[] cloud = new float[]{0f, 0f, 2.4f, 0.1f, 0.1f, 1.5f};
        for (float ox : new float[]{1.5f, -2f, 2f}) {
            for (float oy : new float[]{1.8f, -1.5f}) {
                SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                        ox, oy, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH, 1f);
                assertTrue("pivot x must be finite at origin " + ox + "," + oy,
                        Float.isFinite(f.pivotX));
                assertTrue("pivot y must be finite at origin " + ox + "," + oy,
                        Float.isFinite(f.pivotY));
                assertTrue("pivot z must be finite and positive at origin " + ox + "," + oy,
                        Float.isFinite(f.pivotZ) && f.pivotZ > 0f);
                assertTrue("the eye must be finite at origin " + ox + "," + oy,
                        Float.isFinite(f.eyeX) && Float.isFinite(f.eyeY)
                                && Float.isFinite(f.eyeZ));
                assertTrue("the forward must be finite at origin " + ox + "," + oy,
                        Float.isFinite(f.forwardX) && Float.isFinite(f.forwardY)
                                && Float.isFinite(f.forwardZ));
            }
        }
        // An empty cloud at an outside origin: the reference-plane point on the ray.
        SplatCamera.Frame empty = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                2f, -2f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH, 1f);
        assertFalse("an empty cloud cannot claim a splat", empty.pivotFound);
        assertTrue(Float.isFinite(empty.pivotX) && Float.isFinite(empty.pivotY)
                && Float.isFinite(empty.pivotZ));
    }

    // -------------------------------------------------- the Depth knob and the pivot

    /**
     * THE REPORTED BUG. The Depth knob moves every splat's DEPTH — a Z-only
     * displacement, x and y staying in their image position — so the cloud's
     * surface under the origin is at a different depth, and the pivot, the aim and
     * the crosshair must move with it. Resolved from the node's own positions they
     * would mark a surface that is no longer there at any multiplier but 1: the
     * origin indicator sat at the old height while the cloud was at twice it.
     *
     * <p>The assertion is against the SHADER's own arithmetic, {@link
     * Splat#depthScale}, applied to the picked splat: the pivot must be exactly
     * where the vertex shader draws that splat — x and y untouched, z at the moved
     * depth — not a second derivation of it that could drift from the first.
     */
    @Test
    public void thePivotFollowsTheDepthKnob() {
        // A splat inside the origin's ray radius (tangent 0.008, 0.012 against a
        // radius of 0.02), so the FRONT pick is what answers.
        float rawX = 0.04f, rawY = 0.06f, rawZ = 5f;
        float[] cloud = {rawX, rawY, rawZ};
        for (float mul : new float[]{0f, 0.5f, 1f, 2f, 3f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, mul);
            float s = Splat.depthScale(rawZ, REF_DEPTH, mul);
            assertEquals("pivot x is the drawn x at mul=" + mul, rawX, f.pivotX, 1e-5f);
            assertEquals("pivot y is the drawn y at mul=" + mul, rawY, f.pivotY, 1e-5f);
            assertEquals("pivot z is the drawn depth at mul=" + mul, rawZ * s, f.pivotZ, 1e-5f);
        }
        // The endpoints in plain numbers, so the direction is on record: 0 puts
        // the pivot on the reference plane, 1 leaves it where the node measured.
        SplatCamera.Frame flat = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 0f);
        assertEquals(REF_DEPTH, flat.pivotZ, 1e-5f);
        SplatCamera.Frame asMeasured = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
        assertEquals(rawZ, asMeasured.pivotZ, 1e-5f);
    }

    /**
     * The pivot MOVES with the knob, at the pivot's own level — which is what makes
     * "the marker follows the depth" a fix and not a re-framing. The old form of
     * this test also asserted the picture held still at every multiplier; that was
     * true of the ray-preserving scale and is deliberately false of the Z-only one
     * (see {@code SplatTest}), so what is pinned here is the pivot's own movement,
     * while the eye stays the source eye at every multiplier.
     */
    @Test
    public void theDepthKnobMovesThePivot() {
        float[] cloud = {0.04f, 0.06f, 5f, 0.3f, -0.2f, 1.2f, -0.5f, 0.4f, 3.3f};
        SplatCamera.Frame base = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0.2f, -0.1f, 30f, -20f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, 1f);
        for (float mul : new float[]{0f, 0.5f, 2f, 3f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    0.2f, -0.1f, 30f, -20f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, mul);
            assertTrue("the pivot must actually move at mul=" + mul,
                    Math.abs(f.pivotZ - base.pivotZ) > 1e-3f);
            // The knob moves the splats, not the camera: the eye is the source eye
            // whatever the pivot's moved depth is.
            assertEquals(0f, f.eyeX, 0f);
            assertEquals(0f, f.eyeY, 0f);
            assertEquals(0f, f.eyeZ, 0f);
        }
    }

    /**
     * A splat the Depth knob pushes THROUGH the eye is not the pivot. The vertex
     * shader floors the moved depth at 0 and its near cull then drops that splat;
     * a pivot resolved from the raw depths would pick the front one anyway and aim
     * the orbit at a point the cloud does not draw.
     */
    @Test
    public void aSplatPushedThroughTheEyeIsNotThePivot() {
        // mul 3 about ref 2.4: z = 0.5 moves to 2.4 + 3*(0.5 - 2.4) = -3.3, floored
        // to 0 and culled by the shader. The farther splat on the same ray is the
        // pivot, at its own moved depth.
        float[] cloud = {0f, 0f, 0.5f, 0f, 0f, 4f};
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH, 3f);
        assertEquals("the near splat really is pushed through the eye",
                0f, Splat.depthScale(0.5f, REF_DEPTH, 3f), 0f);
        assertEquals("so the farther splat is the pivot",
                4f * Splat.depthScale(4f, REF_DEPTH, 3f), f.pivotZ, 1e-5f);
    }

    // --------------------------------------------------------------- the tap

    /**
     * A tap names the FRONTMOST splat under its ray, not the nearest in image
     * space. A background splat can project closer to the ray than the foreground
     * one and still be behind it, so the two picks differ here on purpose.
     */
    @Test
    public void aTapNamesTheFrontmostSplatOnItsRayNotTheNearestInImageSpace() {
        // Tangents are x/z: the background projects 0.001 from the ray, the
        // foreground 0.005, and both are inside PIVOT_RADIUS.
        float[] cloud = new float[6];
        System.arraycopy(cloudAt(0.005f, 0f, 1f), 0, cloud, 0, 3);   // foreground
        System.arraycopy(cloudAt(0.001f, 0f, 5f), 0, cloud, 3, 3);   // background
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] out = new float[2];
        boolean found = camera().originForRay(cloud, 2, 0f, 0f, REF_DEPTH, 1f,
                f[0], f[1], out);
        assertTrue(found);
        assertEquals("the foreground splat's image position, not the background's",
                0.005f * f[0] * 0.5f, out[0], 1e-6f);
        assertEquals(0f, out[1], 1e-6f);
    }

    /**
     * A ray that holds no splat falls back the way {@link SplatCamera#pivot} does:
     * the nearest splat when there is one, and the reference-plane point on the ray
     * only when the cloud has nothing in front of the camera. Either way the answer
     * is an Origin, so a tap never vanishes.
     */
    @Test
    public void aTapRayThatHoldsNoSplatFallsBackToItsOwnImagePosition() {
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] out = new float[2];
        // An empty cloud: the reference-plane point on the ray, whose image position
        // is the tapped pixel's own.
        boolean found = camera().originForRay(new float[0], 0, 0.25f, -0.1f,
                REF_DEPTH, 1f, f[0], f[1], out);
        assertFalse(found);
        assertEquals("the fallback is the ray's own image position",
                0.25f * f[0] * 0.5f, out[0], 1e-6f);
        assertEquals(0.1f * f[1] * 0.5f, out[1], 1e-6f);
        // A splat far outside the radius is the middle fallback: a real point rather
        // than a guess, so its own image position answers.
        float[] off = cloudAt(1.5f, 2f, 2f);
        camera().originForRay(off, 1, 0f, 0f, REF_DEPTH, 1f, f[0], f[1], out);
        assertEquals("the nearest splat's image position", 1.5f * f[0] * 0.5f, out[0], 1e-6f);
        assertEquals("and its y, not the ray's", -2f * f[1] * 0.5f, out[1], 1e-6f);
    }

    /**
     * A splat the Depth knob pushes THROUGH the eye is skipped by the tap's pick,
     * exactly as it is skipped by the pivot's: the vertex shader culls it, so a tap
     * must not name a point the cloud does not draw.
     */
    @Test
    public void aTapSkipsASplatTheDepthKnobPushesThroughTheEye() {
        // mul 3 about ref 2.4: z=0.5 moves to 2.4 + 3*(0.5 - 2.4) = -3.3, floored to
        // 0 and culled. The farther splat on nearly the same ray is the tap's pick.
        float[] cloud = new float[6];
        System.arraycopy(cloudAt(0.001f, 0f, 0.5f), 0, cloud, 0, 3);  // pushed through
        System.arraycopy(cloudAt(0.005f, 0f, 4f), 0, cloud, 3, 3);    // survivor
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] out = new float[2];
        camera().originForRay(cloud, 2, 0f, 0f, REF_DEPTH, 1f, f[0], f[1], out);
        assertEquals("at mul 1 the near splat is the pick",
                0.001f * f[0] * 0.5f, out[0], 1e-6f);
        assertEquals("the near splat really is pushed through the eye at mul 3",
                0f, Splat.depthScale(0.5f, REF_DEPTH, 3f), 0f);
        camera().originForRay(cloud, 2, 0f, 0f, REF_DEPTH, 3f, f[0], f[1], out);
        assertEquals("so the far splat's image position is the tap's answer",
                0.005f * 4f / 7.2f * f[0] * 0.5f, out[0], 1e-6f);
    }

    /**
     * THE PROPERTY THE OLD TAP BROKE. For a tap at a given pixel, the origin it
     * produces must be the image position of the splat the {@link
     * SplatCamera#pivot} would pick for that origin: tapping and then resolving is a
     * fixed point, so the tap, the pivot, the aim and the crosshair name one point.
     *
     * <p>Asserted through the real camera, never as a restatement of the mapping: the
     * origin from the tap is handed to {@link SplatCamera#frame} and the pivot it
     * resolves is converted back to an image position, which must be that origin.
     */
    @Test
    public void aTapAndThePivotItResolvesAreAFixedPoint() {
        // A front and a back splat on nearly the same ray, and a third well off it,
        // so the pick is a real choice rather than the tapped point itself.
        float[] cloud = new float[9];
        System.arraycopy(cloudAt(0.052f, 0.031f, 2.0f), 0, cloud, 0, 3);
        System.arraycopy(cloudAt(0.045f, 0.026f, 7.0f), 0, cloud, 3, 3);
        System.arraycopy(cloudAt(0.40f, -0.30f, 1.5f), 0, cloud, 6, 3);
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] out = new float[2];
        boolean found = camera().originForRay(cloud, 3, 0.050f, 0.030f,
                REF_DEPTH, 1f, f[0], f[1], out);
        assertTrue(found);
        assertEquals("the origin is the front splat's image position",
                0.052f * f[0] * 0.5f, out[0], 1e-6f);
        assertEquals("the origin is the front splat's image position",
                -0.031f * f[1] * 0.5f, out[1], 1e-6f);
        // Resolving that origin picks the SAME splat: the pivot's image position is
        // the origin, to float noise.
        SplatCamera.Frame fr = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                out[0], out[1], 0f, 0f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, 1f);
        assertTrue("the pivot is a real splat, not the fallback", fr.pivotFound);
        float invz = 1f / fr.pivotZ;
        assertEquals("tapping then resolving is a fixed point",
                out[0], fr.pivotX * invz * f[0] * 0.5f, 1e-5f);
        assertEquals("tapping then resolving is a fixed point",
                out[1], -fr.pivotY * invz * f[1] * 0.5f, 1e-5f);
    }

    /**
     * The tap follows the Depth knob, exactly as the pivot does: the origin is the
     * picked splat's image position on the surface the SHADER draws, and the Z-only
     * displacement moves it to {@code x / movedZ}, so the answer changes with the
     * multiplier. That is the picture the tap must name; "the same at every
     * multiplier" was the ray-preserving form's claim, and it is no longer true.
     */
    @Test
    public void theTapFollowsTheDepthKnob() {
        float[] cloud = new float[6];
        System.arraycopy(cloudAt(0.052f, 0.031f, 2.0f), 0, cloud, 0, 3);
        System.arraycopy(cloudAt(0.045f, 0.026f, 7.0f), 0, cloud, 3, 3);
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] out = new float[2];
        camera().originForRay(cloud, 2, 0.050f, 0.030f, REF_DEPTH, 1f,
                f[0], f[1], out);
        assertEquals("at mul 1 it is the front splat's image position",
                0.052f * f[0] * 0.5f, out[0], 1e-6f);
        // mul 0.5 about ref 2.4: the front splat's z=2 moves to 2.2, so its drawn
        // image position is 0.104/2.2 -- a different answer from the raw x/z above.
        camera().originForRay(cloud, 2, 0.050f, 0.030f, REF_DEPTH, 0.5f,
                f[0], f[1], out);
        assertEquals("at mul 0.5 it is the MOVED image position",
                0.104f / 2.2f * f[0] * 0.5f, out[0], 1e-6f);
    }

    // ------------------------------------------------------------------- zoom

    /**
     * Zoom is a crop of the same camera: the source projection scales exactly with
     * the projection's own factors, so the at-rest picture stays the photograph,
     * magnified — not a different framing.
     */
    @Test
    public void zoomIsACropOfTheSameCamera() {
        float aspect = SURFACE_W / SURFACE_H;
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        for (float fovy : new float[]{30f, 50f, 64.3f, 100f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, fovy,
                    0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
            float[] want = sourceNdc(fovy, aspect, 0.2f, -0.1f, 2f);
            float[] got = project(f, 0.2f, -0.1f, 2f);
            assertEquals("fovy " + fovy, want[0], got[0], 1e-4f);
            assertEquals("fovy " + fovy, want[1], got[1], 1e-4f);
        }
    }

    // --------------------------------------------------------------- pan alone

    /**
     * Pan is a screen-pixel shift, in the relief's unit: a pan of half the surface
     * width moves the picture by one NDC unit, at every depth.
     */
    @Test
    public void panMovesThePictureByPixelsAtEveryDepth() {
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, SURFACE_W / 2f, -SURFACE_H / 4f, 0f, 0f, 1f, cloud, 1, REF_DEPTH, 1f);
        for (float z : new float[]{0.5f, 2.4f, 6f}) {
            float[] got = project(f, 0f, 0f, z);
            assertEquals("panX half the surface is -1 NDC", -1f, got[0], 1e-4f);
            assertEquals("panY a quarter of the surface is -0.5 NDC", -0.5f, got[1], 1e-4f);
        }
    }

    // ------------------------------------------------------------- the sort gate

    /** A cloud that fills the picture at a spread of depths, deterministically. */
    private static float[] syntheticCloud() {
        final int N = 40;
        float[] p = new float[N * N * 3];
        int k = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                float u = ((i / (float) (N - 1)) * 2f - 1f) * 0.8f;
                float v = ((j / (float) (N - 1)) * 2f - 1f) * 0.8f;
                float z = 0.5f + 1.1f * ((i * 7 + j * 13) % 17) / 16f;
                p[k++] = u * z;
                p[k++] = v * z;
                p[k++] = z;
            }
        }
        return p;
    }

    private static float medianZ(float[] p) {
        int n = p.length / 3;
        float[] z = new float[n];
        for (int i = 0; i < n; i++) z[i] = p[i * 3 + 2];
        java.util.Arrays.sort(z);
        return (n & 1) == 1 ? z[n / 2] : 0.5f * (z[n / 2 - 1] + z[n / 2]);
    }

    /**
     * THE DERIVED BOUND'S GUARD, the shape {@code CameraModelTest}'s doze test
     * established one medium over: the tilt the DERIVED sub-pixel bound allows moves
     * no VISIBLE splat by more than half a pixel, at every reachable framing.
     *
     * <p>The bound is a max over every splat, so what this pins is that the analytic
     * form {@link SplatCamera#pxPerTilt} agrees with the real {@link
     * SplatCamera#frame} chain -- the same "assert the projection, never a
     * restatement" rule the rest of this class follows. A camera constant that
     * outgrew the arithmetic, or a visibility sign that let an off-screen splat
     * count, goes red here rather than on a phone.
     *
     * <p>It is asserted at Motion 1, the tilt range's own unit -- the same unit
     * {@code CameraModel.DOZE_EPS} is stated at. Motion scales the travel linearly,
     * so at Motion 3 the derived sub-pixel gate is three times tighter than the
     * constant and the FLOOR governs instead; that boundary is pinned by {@link
     * #theGateIsNeverTighterThanTheConstantItReplaces}.
     */
    @Test
    public void theDerivedBoundStaysUnderASubPixelAtEveryFraming() {
        float[] cloud = syntheticCloud();
        int n = cloud.length / 3;
        float refDepth = medianZ(cloud);
        float[] fovys = {20f, 32f, 50f, 64.3f, 100f};
        float[] origins = {-0.4f, 0f, 0.4f};
        float[][] pans = {{0f, 0f}, {-55f, 451f}, {200f, -300f}};
        float worst = 0f;
        for (float fovy : fovys) {
            for (float ox : origins) {
                for (float oy : origins) {
                    for (float[] pan : pans) {
                        SplatCamera c = camera();
                        SplatCamera.Frame f = c.frame(SURFACE_W, SURFACE_H, fovy,
                                ox, oy, pan[0], pan[1], 0f, 0f, 1f, cloud, n, refDepth, 1f);
                        float px = c.pxPerTilt(SURFACE_W, SURFACE_H, 1f, f, cloud, n);
                        assertTrue("the bound must claim some displacement at fovy=" + fovy
                                        + " origin=" + ox + "," + oy, px > 0f);
                        float eps = SplatCamera.SUBPIXEL_PX / px;
                        float disp = c.tiltPixels(eps, SURFACE_W, SURFACE_H, fovy,
                                ox, oy, pan[0], pan[1], 1f, cloud, n, refDepth, 1f);
                        if (disp > worst) worst = disp;
                        assertTrue("the derived bound would move the picture " + disp
                                        + "px at fovy=" + fovy + " origin=" + ox + "," + oy
                                        + " pan=" + pan[0] + "," + pan[1],
                                disp <= SplatCamera.SUBPIXEL_PX);
                    }
                }
            }
        }
        // The derivation's worst case sits near the bound; keep the two from
        // drifting apart, the same guard CameraModelTest keeps on the doze.
        assertTrue("the derivation's worst case has shrunk to " + worst,
                worst > 0.3f * SplatCamera.SUBPIXEL_PX);
    }

    /**
     * The bound is LIVE, not a constant: a narrower field of view magnifies the same
     * world motion, so the gate must shrink with it. A constant would pass the sweep
     * above and be exactly the near-nothing lever the measurement rejected.
     */
    @Test
    public void theSortGateShrinksWithTheFieldOfView() {
        float[] cloud = syntheticCloud();
        int n = cloud.length / 3;
        float refDepth = medianZ(cloud);
        SplatCamera c = camera();
        SplatCamera.Frame wide = c.frame(SURFACE_W, SURFACE_H, 100f,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, n, refDepth, 1f);
        float epsWide = c.sortEps(SURFACE_W, SURFACE_H, 1f, wide, cloud, n);
        SplatCamera.Frame narrow = c.frame(SURFACE_W, SURFACE_H, 20f,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, n, refDepth, 1f);
        float epsNarrow = c.sortEps(SURFACE_W, SURFACE_H, 1f, narrow, cloud, n);
        assertTrue("the gate must loosen as the field of view widens: "
                        + epsWide + " vs " + epsNarrow,
                epsWide > 1.2f * epsNarrow);
    }

    /**
     * THE NO-REGRESSION GUARANTEE: at every reachable framing -- including the high
     * Motion where the derived sub-pixel gate would be tighter than the constant --
     * the gate is at least {@link SplatCamera#SHIPPED_SORT_EPS}. The derived bound
     * may only ever LOOSEN the gate, so this change cannot sort more often than the
     * constant it replaces anywhere. The Motion sweep is the point: the first
     * attempt's form was unfloored, and at Motion 3 (the device's own) it produced
     * 8.9e-4, i.e. a 1.13x pessimisation.
     */
    @Test
    public void theGateIsNeverTighterThanTheConstantItReplaces() {
        float[] cloud = syntheticCloud();
        int n = cloud.length / 3;
        float refDepth = medianZ(cloud);
        float[] fovys = {20f, 32f, 50f, 64.3f, 100f};
        float[] motions = {0f, 0.5f, 0.99f, 1f, 2f, 3f};
        float[] origins = {-0.45f, 0f, 0.45f};
        float[][] pans = {{0f, 0f}, {-55f, 451f}, {400f, -400f}};
        for (float fovy : fovys) {
            for (float motion : motions) {
                for (float ox : origins) {
                    for (float oy : origins) {
                        for (float[] pan : pans) {
                            SplatCamera c = camera();
                            SplatCamera.Frame f = c.frame(SURFACE_W, SURFACE_H, fovy,
                                    ox, oy, pan[0], pan[1], 0f, 0f, motion,
                                    cloud, n, refDepth, 1f);
                            float eps = c.sortEps(SURFACE_W, SURFACE_H, motion, f, cloud, n);
                            assertTrue("the gate tightened to " + eps + " at fovy=" + fovy
                                            + " motion=" + motion + " origin=" + ox + "," + oy
                                            + " pan=" + pan[0] + "," + pan[1],
                                    eps >= SplatCamera.SHIPPED_SORT_EPS);
                        }
                    }
                }
            }
        }
    }

    /**
     * AND THE LEVER IS REAL: at the default Motion the derived bound is LOOSER than
     * the constant, so the change actually skips sorts rather than being a no-op
     * everywhere. The framing is the device's own -- the bundled row's stored
     * {@code zoom 62} and {@code origin (-0.35164642, 0.34062168)}, {@code pan
     * (-55, 451)}, on a 1080x2340 surface, with the bundled cloud's lens giving
     * {@code fovy 100} -- and the Motion is the DEFAULT, which is the scale the
     * measurement's figures are stated at.
     */
    @Test
    public void theGateIsLooserThanTheConstantAtTheDefaultMotion() {
        float[] cloud = syntheticCloud();
        int n = cloud.length / 3;
        float refDepth = medianZ(cloud);
        float defaultMotion = SurfaceSettings.DEFAULT_MOTION / 100f * 3f;
        SplatCamera c = camera();
        SplatCamera.Frame f = c.frame(SURFACE_W, SURFACE_H, 100f,
                -0.35164642f, 0.34062168f, -55f, 451f, 0f, 0f, defaultMotion,
                cloud, n, refDepth, 1f);
        float eps = c.sortEps(SURFACE_W, SURFACE_H, defaultMotion, f, cloud, n);
        assertTrue("the gate must beat the constant at the default Motion ("
                        + eps + ") or the lever is a no-op",
                eps > SplatCamera.SHIPPED_SORT_EPS);
    }

    /**
     * A framing that cannot claim a bound answers the CONSTANT, not zero: "a window
     * claims nothing" applied to a bound, and the no-regression rule read together.
     * An absent cloud must not tighten a gate that is shipping.
     */
    @Test
    public void aFramingThatClaimsNothingFallsBackToTheConstant() {
        SplatCamera c = camera();
        SplatCamera.Frame f = c.frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH, 1f);
        assertEquals("no positions", SplatCamera.SHIPPED_SORT_EPS,
                c.sortEps(SURFACE_W, SURFACE_H, 1f, f, null, 0), 0f);
        assertEquals("an empty cloud", SplatCamera.SHIPPED_SORT_EPS,
                c.sortEps(SURFACE_W, SURFACE_H, 1f, f, new float[0], 0), 0f);
        float[] behind = {0f, 0f, -1f, 1f, 1f, -2f};
        assertEquals("a cloud entirely behind the camera", SplatCamera.SHIPPED_SORT_EPS,
                c.sortEps(SURFACE_W, SURFACE_H, 1f, f, behind, 2), 0f);
        assertEquals("no surface", SplatCamera.SHIPPED_SORT_EPS,
                c.sortEps(0f, SURFACE_H, 1f, f, cloudAt(0f, 0f, 2.4f), 1), 0f);
    }

    /**
     * PAN IS PART OF THE BOUND'S FRAMING KEY, and it is a real trigger rather than a
     * conservative inclusion.
     *
     * <p>{@link SplatCamera#pxPerTilt} takes the max only over the splats VISIBLE
     * under the current Pan shift, so pan -- a screen-space offset that moves the
     * window without changing the pivot or any splat's parallax -- changes which
     * splats that max is over. {@code SplatRenderer} therefore re-derives
     * {@code sortEps} on a pan, and this pins that it must: at the device's own
     * framing, the bound moves ~25% between pan (0, 0) and (400, -400) with the
     * pivot IDENTICAL, so a future "pan is a no-op, drop it from the key" change
     * goes red here rather than serving a stale bound to a tilt. (Origin is no
     * longer in the shift, so this is pan's effect alone.)
     */
    @Test
    public void panIsPartOfTheBoundsFramingKey() {
        float[] cloud = syntheticCloud();
        int n = cloud.length / 3;
        float refDepth = medianZ(cloud);
        // The device framing: Origin and Motion fixed, only Pan moved.
        SplatCamera.Frame zero = camera().frame(SURFACE_W, SURFACE_H, 100f,
                -0.35164642f, 0.34062168f, 0f, 0f, 0f, 0f, 3f, cloud, n, refDepth, 1f);
        float pxZero = camera().pxPerTilt(SURFACE_W, SURFACE_H, 3f, zero, cloud, n);
        SplatCamera.Frame panned = camera().frame(SURFACE_W, SURFACE_H, 100f,
                -0.35164642f, 0.34062168f, 400f, -400f, 0f, 0f, 3f, cloud, n, refDepth, 1f);
        float pxPanned = camera().pxPerTilt(SURFACE_W, SURFACE_H, 3f, panned, cloud, n);
        // Pan is a clip-space shift: it does not move the pivot...
        assertEquals("pan must not move the pivot",
                zero.pivotZ, panned.pivotZ, 0f);
        // ...but it does change the visible set, and so the bound.
        assertTrue("pan must materially move the bound: " + pxZero + " vs " + pxPanned,
                pxPanned > 1.2f * pxZero || pxPanned < 0.8f * pxZero);
    }

    // --------------------------------------------- the near-plane pull-back

    /**
     * THE PULL-BACK'S ONE PROPERTY: a splat nearer than its own minimum view
     * distance keeps its SCREEN POSITION exactly while its projected extent shrinks
     * by the scale, asserted by projecting through the real {@link Mat4} chain and
     * {@link SplatProjection} -- never a restatement of the rule. This is what makes
     * it a pull-back that does not re-frame the picture: the clamp moves the splat,
     * not the eye.
     */
    @Test
    public void thePullBackKeepsASplatOnItsPixelAndShrinksIt() {
        float size = 0.03f;                            // 3D semi-axis, cloud units
        float[] splat = cloudAt(0.35f, -0.28f, 0.05f); // a splat almost at the lens
        for (float fovy : new float[]{100f, 64.3f, 45f, 33f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, fovy,
                    0.2f, -0.1f, 40f, -25f, 0f, 0f, 1f, splat, 1, REF_DEPTH, 1f);
            float[] mv = viewPosition(f, splat[0], splat[1], splat[2]);
            float depth = -mv[2];
            float zmin = SplatCamera.minViewDistance(size, f.proj[5],
                    SplatCamera.MAX_EXTENT_FRACTION);
            assertTrue("the splat must be nearer than its minimum at fovy " + fovy
                            + " (depth " + depth + ", min " + zmin + ")",
                    depth < zmin);
            float k = zmin / depth;
            float[] before = ndcOf(f, mv[0], mv[1], depth);
            float[] after = ndcOf(f, mv[0] * k, mv[1] * k, depth * k);
            assertEquals("x must not move (fovy " + fovy + ")", before[0], after[0], 1e-5f);
            assertEquals("y must not move (fovy " + fovy + ")", before[1], after[1], 1e-5f);
            float[] e0 = extents(f, size, mv[0], mv[1], depth);
            float[] e1 = extents(f, size, mv[0] * k, mv[1] * k, depth * k);
            // Every Jacobian entry goes as 1/depth, so Sigma2D falls by k^2 and both
            // extents by exactly k.
            assertEquals("the major extent is the scale (fovy " + fovy + ")",
                    e0[0] / k, e1[0], 1e-3f * e0[0]);
            assertEquals("and so is the minor one (fovy " + fovy + ")",
                    e0[1] / k, e1[1], 1e-3f * e0[1]);
            assertTrue("the extent must actually shrink (fovy " + fovy + ")",
                    e1[0] < 0.95f * e0[0]);
        }
    }

    /**
     * AND THE SPLAT IS DRAWN, NOT DROPPED: at the default fraction a clamped
     * splat's extent stays under the radius clip's half-frame threshold at every
     * framing and every screen position -- including the frame corner, where the
     * Jacobian's off-axis term is largest, which is why the default is 0.1 and not
     * nearer the threshold. Swept over a splat placed just inside its own minimum,
     * so the reduction is the SMALLEST the rule ever applies.
     */
    @Test
    public void thePullBackKeepsTheSplatUnderTheRadiusClip() {
        float aspect = SURFACE_W / SURFACE_H;
        float[][] ndcs = {{0f, 0f}, {0.5f, 0.5f}, {0.9f, -0.85f}, {-0.95f, 0.9f}};
        float[] sizes = {0.005f, 0.02f, 0.05f, 0.2f};
        float worst = 0f;
        for (float fovy : new float[]{100f, 64.3f, 45f, 33f, 20f}) {
            float[] proj = projectionFactors(fovy, aspect);
            for (float[] nd : ndcs) {
                for (float size : sizes) {
                    float zmin = SplatCamera.minViewDistance(size, proj[1],
                            SplatCamera.MAX_EXTENT_FRACTION);
                    // Just inside the minimum, so k is barely above 1.
                    float z = 0.99f * zmin;
                    float[] splat = cloudAt(nd[0] / proj[0], -nd[1] / proj[1], z);
                    SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, fovy,
                            0f, 0f, 0f, 0f, 0f, 0f, 1f, splat, 1, REF_DEPTH, 1f);
                    float[] mv = viewPosition(f, splat[0], splat[1], splat[2]);
                    float depth = -mv[2];
                    float k = Math.max(1f, zmin / depth);
                    float[] e = extents(f, size, mv[0] * k, mv[1] * k, depth * k);
                    float extent = Math.max(e[0], e[1]);
                    if (extent > worst) worst = extent;
                    assertTrue("the clamped splat must be drawn at fovy " + fovy
                                    + " ndc " + nd[0] + "," + nd[1] + " size " + size
                                    + ": extent " + extent + " vs the half-frame clip "
                                    + 0.5f * SURFACE_H,
                            extent <= 0.5f * SURFACE_H);
                }
            }
        }
        // And the bound is not vacuous: the sweep reaches a real fraction of it.
        assertTrue("the corner case must approach the bound, not sit at zero: " + worst,
                worst > 0.2f * 0.5f * SURFACE_H);
    }

    /**
     * A CLOUD WITH NOTHING NEAR THE CAMERA IS UNTOUCHED, and here is the margin.
     * Measured across five real clouds in the tree (149k-1.18M splats), the largest
     * 3D axis over depth is 0.0133. The default's threshold is
     * {@code MAX_EXTENT_FRACTION / (1.5 * proj5)}, which at the two widest framings
     * the app offers is 0.0795 (fovy 100) and 0.0276 (fovy 45) -- five times the
     * worst real aspect at the widest, and twice it at a typical zoom-in. So no real
     * cloud is touched without a genuinely anomalous splat.
     */
    @Test
    public void aCloudWithNothingNearTheCameraIsUntouched() {
        float worstRealAspect = 0.0133f;
        for (float fovy : new float[]{100f, 64.3f, 45f}) {
            float proj5 = projectionFactors(fovy, SURFACE_W / SURFACE_H)[1];
            float depth = 2.4f;
            float size = worstRealAspect * depth;
            float zmin = SplatCamera.minViewDistance(size, proj5,
                    SplatCamera.MAX_EXTENT_FRACTION);
            assertTrue("a real cloud's worst aspect must not be pulled back at fovy "
                            + fovy + " (min " + zmin + " vs depth " + depth + ")",
                    zmin <= depth);
        }
    }

    /**
     * The rule itself: the minimum is LINEAR in the size, INVERSE in the fraction,
     * and depends on the two only through {@code size / depth}, so scaling a whole
     * cloud does not change which splats are pulled back. A
     * projection or a fraction that cannot claim one answers 0 -- no pull-back --
     * the "a window claims nothing" rule applied to a distance.
     */
    @Test
    public void theMinimumViewDistanceIsThePinholeRelationAndClaimsNothingElse() {
        assertEquals(0f, SplatCamera.minViewDistancePerSize(0f, 0.1f), 0f);
        assertEquals(0f, SplatCamera.minViewDistancePerSize(-1f, 0.1f), 0f);
        assertEquals(0f, SplatCamera.minViewDistancePerSize(Float.NaN, 0.1f), 0f);
        assertEquals(0f, SplatCamera.minViewDistancePerSize(1f, 0f), 0f);
        assertEquals(0f, SplatCamera.minViewDistancePerSize(1f, Float.NaN), 0f);
        assertEquals(0f, SplatCamera.minViewDistance(0f, 1f, 0.1f), 0f);
        assertEquals(0f, SplatCamera.minViewDistance(Float.NaN, 1f, 0.1f), 0f);

        float proj5 = 0.8390996f; // 1 / tan(50 deg), the app's widest framing
        assertEquals(1.5f * proj5 / 0.1f,
                SplatCamera.minViewDistancePerSize(proj5, 0.1f), 1e-4f);
        assertEquals(2f * SplatCamera.minViewDistance(0.02f, proj5, 0.1f),
                SplatCamera.minViewDistance(0.04f, proj5, 0.1f), 1e-6f);
        assertEquals(2f * SplatCamera.minViewDistance(0.02f, proj5, 0.1f),
                SplatCamera.minViewDistance(0.02f, proj5, 0.05f), 1e-4f);

        // Scale-invariance: the clamped depth over the splat's own depth depends on
        // size/depth alone.
        float ratio = 0f;
        for (float s : new float[]{0.1f, 1f, 10f}) {
            float size = 0.02f * s, depth = 0.05f * s;
            float k = SplatCamera.minViewDistance(size, proj5,
                    SplatCamera.MAX_EXTENT_FRACTION) / depth;
            if (ratio == 0f) ratio = k;
            else assertEquals("the pull-back ratio must be scale-invariant", ratio, k, 1e-4f);
        }
        assertTrue("and it must be a real pull-back: " + ratio, ratio > 1f);
    }
}
