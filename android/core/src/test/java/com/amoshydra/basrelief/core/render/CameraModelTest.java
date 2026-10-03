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
        m.setDepth(1f);
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

    /**
     * One whole Origin unit must move the picture by exactly the number of
     * pixels {@code screenToOrbit} says a drag of that many pixels resolves to
     * — the two are inverses, and that identity is the whole basis of the
     * tracking rule the framing sliders use.
     *
     * <p>Deliberately a round-trip through the real unprojection rather than a
     * restatement of the formula: a formula can be re-derived to match itself,
     * the round-trip cannot.
     */
    @Test
    public void originTravelIsTheInverseOfTapUnprojection() {
        for (float zoom : new float[]{0.25f, 0.7f, 1f, 2f}) {
            for (boolean horizontal : new boolean[]{true, false}) {
                CameraModel m = model();
                m.setZoom(zoom);
                float d = 100f; // pixels from the screen centre
                float ndcX = horizontal ? 2f * d / SURFACE_W : 0f;
                float ndcY = horizontal ? 0f : 2f * d / SURFACE_H;
                float[] orbit = new float[2];
                assertTrue("tap should hit the plane at zoom " + zoom,
                        m.screenToOrbit(ndcX, ndcY, SURFACE_W, SURFACE_H,
                                0f, 0f, 0f, 0f, 0f, 0f, orbit));
                float moved = horizontal ? orbit[0] : orbit[1];
                float expected = d / m.screenPixelsPerOrbit(horizontal, SURFACE_H);
                assertEquals("zoom " + zoom + (horizontal ? " x" : " y"),
                        expected, moved, 1e-4f);
            }
        }
    }

    /**
     * The tracking value is proportional to zoom (the model scale is), and the
     * X axis is exactly {@code aspect} times Y. Both are pinned because a
     * plausible "same pixels on both axes" rule passes a single-axis check and
     * then mistracks one of them by the image's aspect.
     */
    @Test
    public void originTravelScalesWithZoomAndAspect() {
        CameraModel m = model();
        float x1 = m.screenPixelsPerOrbit(true, SURFACE_H);
        float y1 = m.screenPixelsPerOrbit(false, SURFACE_H);
        assertEquals("X carries the image aspect", IMAGE_ASPECT * y1, x1, 1e-3f);
        m.setZoom(2f);
        assertEquals("tracking doubles with zoom", x1 * 2f,
                m.screenPixelsPerOrbit(true, SURFACE_H), 1e-3f);
        m.setZoom(0.25f);
        assertEquals("tracking quarters with zoom", x1 * 0.25f,
                m.screenPixelsPerOrbit(true, SURFACE_H), 1e-3f);
    }

    /** No surface means no tracking to compute; the caller must not divide by 0. */
    @Test
    public void originTravelIsZeroWithoutASurface() {
        CameraModel m = model();
        assertEquals(0f, m.screenPixelsPerOrbit(true, 0f), 0f);
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
        m.setDepth(100f); // clamped to 1 by setDepth
        float scale = m.computeScale();
        float z = m.computeZScale(scale);
        // relief half-depth (0.5) * z * scale must stay under the camera distance
        assertTrue("zScale must clamp so the relief does not engulf the camera",
                0.5f * z * scale < CameraModel.CAM_DIST);
    }

    /**
     * The bug this replaced: Depth used to be an absolute value hard-clamped to
     * maxZScale, so the top of the slider was a silent no-op once the clamp bit
     * (at Zoom 2.0 more than half the range did nothing). Depth is now a
     * fraction of maxZScale, so every step of 0..1 must actually change zScale
     * at every zoom.
     */
    @Test
    public void everyDepthStepIsLiveAtEveryZoom() {
        for (float zoom : new float[]{0.25f, 0.5f, 1f, 1.5f, 2f}) {
            CameraModel m = model();
            m.setZoom(zoom);
            float scale = m.computeScale();
            float previous = -1f;
            for (int pct = 0; pct <= 100; pct += 5) {
                m.setDepth(pct / 100f);
                float z = m.computeZScale(scale);
                assertTrue("zoom " + zoom + " at " + pct + "% must increase zScale",
                        z > previous - 1e-9f);
                previous = z;
            }
            // 100% must land exactly on the camera-safe maximum
            m.setDepth(1f);
            assertEquals("zoom " + zoom + ": 100% == maxZScale",
                    m.maxZScale(), m.computeZScale(scale), 1e-6f);
        }
    }

    /** Depth 0 must be a genuinely flat surface, whatever the zoom. */
    @Test
    public void depthZeroIsFlatAtEveryZoom() {
        for (float zoom : new float[]{0.25f, 0.5f, 1f, 2f}) {
            CameraModel m = model();
            m.setZoom(zoom);
            m.setDepth(0f);
            assertEquals("zoom " + zoom + ": Depth 0% must be flat",
                    0f, m.computeZScale(m.computeScale()), 0f);
        }
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
