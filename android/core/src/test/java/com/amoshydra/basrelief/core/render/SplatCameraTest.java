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
                                ox, oy, panX, panY, 0f, 0f, 1f, cloud, cloud.length / 3, REF_DEPTH);
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
                    ox, 0.2f, 0f, 0f, 0f, 0f, 1f, near, 1, REF_DEPTH);
            SplatCamera.Frame b = farCam.frame(SURFACE_W, SURFACE_H, FOVY,
                    ox, 0.2f, 0f, 0f, 0f, 0f, 1f, far, 1, REF_DEPTH);
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
                        ox, oy, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
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
                        ox, 0f, 0f, 0f, tilt[0], tilt[1], 1f, cloud, 3, REF_DEPTH);
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
                    ox, -ox, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
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
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
        float px = rest.pivotX, py = rest.pivotY, pz = rest.pivotZ;
        float r0 = (float) Math.sqrt(px * px + py * py + pz * pz);
        for (float[] tilt : new float[][]{{0.16f, 0f}, {0f, 0.16f}, {0.1f, -0.12f}}) {
            SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                    0f, 0f, 0f, 0f, tilt[0], tilt[1], 1f, cloud, 1, REF_DEPTH);
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
                0f, 0f, 0f, 0f, 0.16f, 0f, 1f, cloud, 1, REF_DEPTH);
        assertTrue("positive tiltX moves the eye right", x.eyeX > 1e-4f);
        assertEquals("and not vertically", 0f, x.eyeY, 1e-6f);
        SplatCamera.Frame y = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0.16f, 1f, cloud, 1, REF_DEPTH);
        assertTrue("positive tiltY moves the eye down (cloud +y)", y.eyeY > 1e-4f);
        assertEquals("and not horizontally", 0f, y.eyeX, 1e-6f);
    }

    /** Motion 0 parks the camera exactly as it parks the relief's. */
    @Test
    public void motionZeroParksTheCamera() {
        float[] cloud = cloudAt(0f, 0f, 2.4f);
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0.16f, 0.16f, 0f, cloud, 1, REF_DEPTH);
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
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH);
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
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
        assertTrue("a real splat is used rather than a guess", f.pivotFound);
        assertEquals(2f, f.pivotZ, 1e-6f);

        // No splat in front of the camera: the reference-plane point on the ray.
        SplatCamera.Frame empty = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH);
        assertFalse("an empty cloud cannot claim a splat", empty.pivotFound);
        assertEquals("the fallback is the reference-plane point on the ray",
                0f, empty.pivotX, 1e-6f);
        assertEquals(0f, empty.pivotY, 1e-6f);
        assertEquals(REF_DEPTH, empty.pivotZ, 1e-6f);
        // And on an off-axis origin the fallback point is on that ray.
        SplatCamera.Frame off = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0.25f, -0.2f, 0f, 0f, 0f, 0f, 1f, new float[0], 0, REF_DEPTH);
        float[] p = project(off, off.pivotX, off.pivotY, off.pivotZ);
        assertEquals(0f, p[0], 1e-4f);
        assertEquals(0f, p[1], 1e-4f);
    }

    /** A splat behind the camera is not under any ray. */
    @Test
    public void pivotIgnoresSplatsBehindTheCamera() {
        float[] cloud = new float[]{0f, 0f, -3f, 0f, 0f, 1.5f};
        SplatCamera.Frame f = camera().frame(SURFACE_W, SURFACE_H, FOVY,
                0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 2, REF_DEPTH);
        assertEquals(1.5f, f.pivotZ, 1e-6f);
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
                    0f, 0f, 0f, 0f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
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
                0f, 0f, SURFACE_W / 2f, -SURFACE_H / 4f, 0f, 0f, 1f, cloud, 1, REF_DEPTH);
        for (float z : new float[]{0.5f, 2.4f, 6f}) {
            float[] got = project(f, 0f, 0f, z);
            assertEquals("panX half the surface is -1 NDC", -1f, got[0], 1e-4f);
            assertEquals("panY a quarter of the surface is -0.5 NDC", -0.5f, got[1], 1e-4f);
        }
    }
}
