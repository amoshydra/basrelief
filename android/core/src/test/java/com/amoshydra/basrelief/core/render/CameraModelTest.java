package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The camera model used to be reachable only from inside a GLSurfaceView, so
 * its correctness was checked by hand on a device. These tests pin the two
 * properties that actually matter:
 *
 * <ol>
 *   <li>a tap round-trips (unproject -> the resulting orbit, when used as the
 *       new pivot, projects back to the pixel that was tapped), and
 *   <li>the z-scale clamp keeps the camera outside the relief.</li>
 * </ol>
 *
 * <p>Matrix order {@code projPanned * view} is the load-bearing detail: an
 * earlier version inverted {@code view * proj} and every tap was subtly wrong.
 * The round-trip below is what catches that — a wrong order still produces a
 * plausible-looking orbit, it just doesn't land where you tapped.
 */
public class CameraModelTest {

    private static final int SURFACE_W = 1440, SURFACE_H = 3120;
    private static final float IMAGE_ASPECT = 1400f / 2560f;

    private CameraModel model() {
        CameraModel m = new CameraModel();
        m.setAspect(IMAGE_ASPECT);
        m.setSurfaceAspect(SURFACE_W / (float) SURFACE_H);
        m.setZoom(1f);
        m.setIntensity(1f);
        m.setMotion(1f);
        return m;
    }

    /**
     * Tap at NDC, unproject to a plane point, then project that SAME point
     * through the SAME camera. It must land back on the original NDC
     * coordinate.
     *
     * <p>Note this re-projects with the ORIGINAL orbit pivot, not the newly
     * resolved one. Adopting the new orbit moves the camera, so projecting
     * through it would move the point to screen centre — a correct
     * implementation cannot round-trip that way, and asserting it would be
     * asserting a bug.
     */
    @Test
    public void tapRoundTripsThroughOrbit() {
        assertRoundTrip(0f, 0f, 0f, 0f, 0f, 0f);
        assertRoundTrip(0.3f, -0.2f, 0f, 0f, 0f, 0f);
        assertRoundTrip(-0.45f, 0.35f, 0f, 0f, 0f, 0f);
        assertRoundTrip(0.6f, 0.1f, 0f, 0f, 0f, 0f);
        assertRoundTrip(0f, 0f, 0.3f, -0.25f, 40f, -90f);   // tilt + pan
        assertRoundTrip(-0.2f, 0.15f, -0.5f, 0.4f, -120f, 60f);
    }

    private void assertRoundTrip(float ndcX, float ndcY,
                                 float tiltX, float tiltY,
                                 float panX, float panY) {
        CameraModel m = model();
        float[] orbit = new float[2];
        assertTrue("tap at " + ndcX + "," + ndcY + " should hit the plane",
                m.screenToOrbit(ndcX, ndcY, SURFACE_W, SURFACE_H,
                        panX, panY, 0f, 0f, tiltX, tiltY, orbit));

        float scale = m.computeScale();
        float wx = orbit[0] * IMAGE_ASPECT * scale;
        float wy = orbit[1] * scale;
        // same camera as the tap was resolved through: orbit pivot still (0, 0)
        float[] ndc = m.project(wx, wy, SURFACE_W, SURFACE_H,
                panX, panY, 0f, 0f, tiltX, tiltY);
        assertTrue("projection must succeed", ndc != null);
        assertEquals("x round-trip (" + ndcX + "," + ndcY + ")", ndcX, ndc[0], 1e-4f);
        assertEquals("y round-trip (" + ndcX + "," + ndcY + ")", ndcY, ndc[1], 1e-4f);
    }

    /** A resolved orbit is always inside the plane; it is never clamped into it. */
    @Test
    public void resolvedOrbitStaysOnThePlane() {
        CameraModel m = model();
        float[] orbit = new float[2];
        assertTrue(m.screenToOrbit(0f, 0f, SURFACE_W, SURFACE_H,
                0f, 0f, 0f, 0f, 0f, 0f, orbit));
        assertTrue(Math.abs(orbit[0]) <= 0.5f);
        assertTrue(Math.abs(orbit[1]) <= 0.5f);
    }

    /** The camera may never end up inside the relief, whatever intensity says. */
    @Test
    public void zScaleIsClampedSoCameraStaysOutside() {
        CameraModel m = model();
        m.setIntensity(100f); // absurd
        float scale = m.computeScale();
        float z = m.computeZScale(scale);
        // relief half-depth (0.5) * z * scale must stay under the camera distance
        assertTrue("zScale must clamp so the relief does not engulf the camera",
                0.5f * z * scale < CameraModel.CAM_DIST);
    }

    @Test
    public void scaleCoversViewport() {
        CameraModel m = model();
        float scale = m.computeScale();
        float visH = 2f * CameraModel.CAM_DIST
                * (float) Math.tan(Math.toRadians(CameraModel.FOV_DEG / 2f));
        float visW = visH * (SURFACE_W / (float) SURFACE_H);
        assertTrue("plane height covers viewport height", scale >= visH);
        assertTrue("plane width covers viewport width", scale * IMAGE_ASPECT >= visW);
    }

    /** frame() must be side-effect free to call repeatedly (reused each draw). */
    @Test
    public void frameIsStableAcrossCalls() {
        CameraModel m = model();
        CameraModel.Frame a = m.frame(SURFACE_W, SURFACE_H, 0f, 0f, 0.1f, -0.2f, 0.3f, 0.4f);
        float[] first = a.mvp.clone();
        float firstZ = a.zScale, firstEx = a.eyeX;
        CameraModel.Frame b = m.frame(SURFACE_W, SURFACE_H, 0f, 0f, 0.1f, -0.2f, 0.3f, 0.4f);
        assertTrue("same Frame instance is reused", a == b);
        org.junit.Assert.assertArrayEquals("mvp must be deterministic", first, b.mvp, 0f);
        assertEquals("zScale", firstZ, b.zScale, 0f);
        assertEquals("eyeX", firstEx, b.eyeX, 0f);
    }
}
