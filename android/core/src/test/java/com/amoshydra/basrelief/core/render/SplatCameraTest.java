package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The splat camera, which used to exist only inside {@code SplatRenderer} and so
 * could only be judged by eye on a device.
 *
 * <p>The property this class exists for is the same one {@code CameraModelTest}
 * pins for the relief, one medium over: <b>at rest the picture is exactly the
 * photograph — translated by the Origin and Pan rows — at every origin and every
 * pivot depth.</b> For a cloud that means the at-rest projection equals the SOURCE
 * view's, shifted by {@code (-2*originX, -2*originY)} and the pan, at every depth,
 * and the pivot's depth changes nothing about it.
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

    // ------------------------------------------------------- the at-rest picture

    /**
     * THE INVARIANT. At rest the projection of a cloud point is the source
     * projection of the same point, shifted by the Origin and Pan — for several
     * origins, both pans, and, in the companion test, several pivot depths.
     *
     * <p>It is deliberately a cloud of points at different depths: the shift has
     * to be exact at every depth, which is the whole reason the origin is a
     * clip-space shift rather than a lateral camera move.
     */
    @Test
    public void atRestThePictureIsThePhotographShiftedByOriginAndPan() {
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
                        float shiftX = -2f * ox - 2f * panX / SURFACE_W;
                        float shiftY = -2f * oy + 2f * panY / SURFACE_H;
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
     * Origin at the tapped screen fraction brings the tapped point to the centre:
     * the whole point of a tap-to-orbit in this mode. The pivot for the origin
     * {@code (ndc/2)} must project to the screen centre.
     */
    @Test
    public void theOriginBringsItsRayToTheCentre() {
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
                assertEquals("pivot x at ndc " + ndcX + "," + ndcY, 0f, p[0], 1e-4f);
                assertEquals("pivot y at ndc " + ndcX + "," + ndcY, 0f, p[1], 1e-4f);
            }
        }
    }

    // --------------------------------------------------------------- the orbit

    /**
     * The turntable, and the property that makes it one: under a tilt the pivot
     * still projects to the centre (the shift centre), while a point off the pivot
     * swings. The eye also leaves the origin and stays the same distance from the
     * pivot — it rotates, it does not translate.
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
                SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                        ox, 0f, 0f, 0f, tilt[0], tilt[1], 1f, cloud, 3, REF_DEPTH, 1f);
                assertTrue("the pivot is the splat on the ray", f.pivotFound);
                float[] pivot = project(f, f.pivotX, f.pivotY, f.pivotZ);
                assertEquals("pivot holds x (tilt " + tilt[0] + "," + tilt[1] + " origin " + ox + ")",
                        0f, pivot[0], 1e-4f);
                assertEquals("pivot holds y (tilt " + tilt[0] + "," + tilt[1] + " origin " + ox + ")",
                        0f, pivot[1], 1e-4f);
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
        // And on an off-axis origin the fallback point is on that ray.
        SplatCamera.Frame off = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0.25f, -0.2f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH, 1f);
        float[] p = project(off, off.pivotX, off.pivotY, off.pivotZ);
        assertEquals(0f, p[0], 1e-4f);
        assertEquals(0f, p[1], 1e-4f);
    }

    /** A splat behind the camera is not under any ray. */
    @Test
    public void pivotIgnoresSplatsBehindTheCamera() {
        float[] cloud = new float[]{0f, 0f, -3f, 0f, 0f, 1.5f};
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH, 1f);
        assertEquals(1.5f, f.pivotZ, 1e-6f);
    }

    // -------------------------------------------------- the Depth knob and the pivot

    /**
     * THE REPORTED BUG. The Depth knob moves every splat along the ray from the
     * eye, so the cloud's surface under the origin is at a different depth — and
     * the pivot, the aim and the crosshair must move with it. Resolved from the
     * node's own positions they would mark a surface that is no longer there at
     * any multiplier but 1: the origin indicator sat at the old height while the
     * cloud was at twice it.
     *
     * <p>The assertion is against the SHADER's own arithmetic, {@link
     * Splat#depthScale}, applied to the picked splat's position: the pivot must be
     * exactly the point the vertex shader draws that splat at, not a second
     * derivation of it that could drift from the first.
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
            assertEquals("pivot x is the drawn x at mul=" + mul, rawX * s, f.pivotX, 1e-5f);
            assertEquals("pivot y is the drawn y at mul=" + mul, rawY * s, f.pivotY, 1e-5f);
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
     * THE INVARIANT THE KNOB EXISTS FOR, at the pivot's own level: moving the
     * pivot must not move the picture. At rest the eye is the source eye whatever
     * the pivot is, so a cloud drawn at a different multiplier — its pivot at a
     * different height — projects every fixed world point identically. This is the
     * pivot-side companion to {@code SplatTest}'s ray-preservation assertion, and
     * it is what makes "the marker follows the depth" a fix and not a re-framing.
     */
    @Test
    public void theDepthKnobMovesThePivotWithoutMovingTheAtRestPicture() {
        float[] cloud = {0.04f, 0.06f, 5f, 0.3f, -0.2f, 1.2f, -0.5f, 0.4f, 3.3f};
        float[] probes = {-0.4f, 0f, 2.4f, 0.5f, 0.3f, 0.7f, 0f, -0.2f, 6f};
        SplatCamera.Frame base = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0.2f, -0.1f, 30f, -20f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, 1f);
        for (float mul : new float[]{0f, 0.5f, 2f, 3f}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    0.2f, -0.1f, 30f, -20f, 0f, 0f, 1f, cloud, 3, REF_DEPTH, mul);
            assertTrue("the pivot must actually move at mul=" + mul,
                    Math.abs(f.pivotZ - base.pivotZ) > 1e-3f);
            for (int i = 0; i < probes.length; i += 3) {
                float[] a = project(base, probes[i], probes[i + 1], probes[i + 2]);
                float[] b = project(f, probes[i], probes[i + 1], probes[i + 2]);
                assertEquals("x must not move at mul=" + mul, a[0], b[0], 1e-6f);
                assertEquals("y must not move at mul=" + mul, a[1], b[1], 1e-6f);
            }
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
                0.005f * f[0] * 0.5f, out[0], 1e-6f);
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
     * The tap does not depend on the Depth knob. The origin is the picked splat's
     * IMAGE POSITION, and {@link Splat#depthScale} preserves {@code x/z}, so the
     * answer is the same at every multiplier -- the dependence that made the old tap
     * land away from the point that was touched.
     */
    @Test
    public void theTapDoesNotDependOnTheDepthKnob() {
        float[] cloud = new float[6];
        System.arraycopy(cloudAt(0.052f, 0.031f, 2.0f), 0, cloud, 0, 3);
        System.arraycopy(cloudAt(0.045f, 0.026f, 7.0f), 0, cloud, 3, 3);
        float[] f = projectionFactors(FOVY, SURFACE_W / SURFACE_H);
        float[] base = new float[2];
        boolean first = true;
        for (float mul : new float[]{0f, 0.5f, 1f, 2f, 3f}) {
            float[] out = new float[2];
            camera().originForRay(cloud, 2, 0.050f, 0.030f, REF_DEPTH, mul,
                    f[0], f[1], out);
            if (first) {
                base[0] = out[0];
                base[1] = out[1];
                first = false;
            }
            assertEquals("origin x at mul=" + mul, base[0], out[0], 1e-6f);
            assertEquals("origin y at mul=" + mul, base[1], out[1], 1e-6f);
        }
        assertEquals("and it is the front splat's image position",
                0.052f * f[0] * 0.5f, base[0], 1e-6f);
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
}
