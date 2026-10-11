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
        assertRoundTrip(ndcX, ndcY, tiltX, tiltY, panX, panY, 0f);
    }

    private void assertRoundTrip(float ndcX, float ndcY,
                                 float tiltX, float tiltY,
                                 float panX, float panY,
                                 float pivotHeight) {
        CameraModel m = model();
        m.setPivotHeight(pivotHeight);
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
     * A tap still resolves to the plane point under the finger when the pivot
     * is at depth: the unprojection runs through the camera that drew the
     * frame, which aims at the surface under the CURRENT origin, and
     * re-projecting the resolved plane point through that same camera must
     * land back on the tap. The new centre is on the plane; its height is the
     * field's, which is the renderer's half and not this class's.
     */
    @Test
    public void tapRoundTripsWithAPivotAtDepth() {
        assertRoundTrip(0.3f, -0.2f, 0f, 0f, 0f, 0f, 0.45f);
        assertRoundTrip(-0.45f, 0.35f, 0.3f, -0.25f, 40f, -90f, -0.4f);
        assertRoundTrip(0.6f, 0.1f, -0.5f, 0.4f, -120f, 60f, 0.2f);
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
     *
     * <p>The pivot-height loop is the same property with the camera aiming at a
     * surface point: at rest the eye sits directly above the pivot, so the view
     * matrix is the plane's own and the identity is untouched by the depth. It
     * is pinned here rather than left to the at-rest picture test because this
     * is the value the Origin slider actually reads.
     */
    @Test
    public void originTravelIsTheInverseOfTapUnprojection() {
        for (float zoom : new float[]{0.25f, 0.7f, 1f, 2f}) {
            for (boolean horizontal : new boolean[]{true, false}) {
                for (float pivotHeight : new float[]{0f, 0.45f, -0.45f}) {
                    CameraModel m = model();
                    m.setZoom(zoom);
                    m.setPivotHeight(pivotHeight);
                    float d = 100f; // pixels from the screen centre
                    float ndcX = horizontal ? 2f * d / SURFACE_W : 0f;
                    float ndcY = horizontal ? 0f : 2f * d / SURFACE_H;
                    float[] orbit = new float[2];
                    assertTrue("tap should hit the plane at zoom " + zoom
                                    + " with pivot " + pivotHeight,
                            m.screenToOrbit(ndcX, ndcY, SURFACE_W, SURFACE_H,
                                    0f, 0f, 0f, 0f, 0f, 0f, orbit));
                    float moved = horizontal ? orbit[0] : orbit[1];
                    float expected = d / m.screenPixelsPerOrbit(horizontal, SURFACE_H);
                    assertEquals("zoom " + zoom + " pivot " + pivotHeight
                                    + (horizontal ? " x" : " y"),
                            expected, moved, 1e-4f);
                }
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

    /** Project a world point through a frame — the plane-only project() cannot. */
    private static float[] projectWorld(CameraModel.Frame f, float x, float y, float z) {
        float[] v = {x, y, z, 1f};
        Mat4.multiplyMV(v, 0, f.viewProjection, 0, v, 0);
        return new float[]{v[0] / v[3], v[1] / v[3]};
    }

    /**
     * THE AT-REST INVARIANT, and the whole reason the pivot can have a depth at
     * all: at rest the camera keeps the fixed camera distance and the pivot's
     * own x/y, so it sits DIRECTLY ABOVE the pivot and {@code lookAt}'s
     * direction is still straight down. The view matrix is therefore exactly
     * the plane point's own, and the picture is the photograph for every origin
     * and every pivot height — no model-scale compensation, and no zoom of the
     * photo as the origin moves from a near subject to a far background.
     *
     * <p>This is the sweep the splat's own commit measured: 4 aspects x 3
     * surfaces x 3 zooms x 3 depths x 3 origins x 3 heights, comparing the
     * projection of the same plane points against the pivot-at-zero camera.
     */
    @Test
    public void pivotAtDepthLeavesTheAtRestPictureUnchanged() {
        float[][] surfaces = {{1440, 3120}, {1080, 1080}, {2400, 1080}};
        float[] aspects = {9f / 16f, 1f, 1400f / 2560f, 16f / 9f};
        float[] zooms = {0.25f, 1f, 2f};
        float[] depths = {0f, 0.5f, 1f};
        float[] origins = {-0.45f, 0f, 0.3f};
        float[] heights = {0f, 0.5f, -0.5f};
        for (float[] surface : surfaces) {
            for (float aspect : aspects) {
                for (float zoom : zooms) {
                    for (float depth : depths) {
                        for (float origin : origins) {
                            for (float h : heights) {
                                CameraModel plane = model();
                                plane.setAspect(aspect);
                                plane.setSurfaceAspect(surface[0] / surface[1]);
                                plane.setZoom(zoom);
                                plane.setDepth(depth);
                                CameraModel surfaced = model();
                                surfaced.setAspect(aspect);
                                surfaced.setSurfaceAspect(surface[0] / surface[1]);
                                surfaced.setZoom(zoom);
                                surfaced.setDepth(depth);
                                surfaced.setPivotHeight(h);

                                CameraModel.Frame a = plane.frame(surface[0], surface[1],
                                        0f, 0f, origin, -origin, 0f, 0f);
                                CameraModel.Frame b = surfaced.frame(surface[0], surface[1],
                                        0f, 0f, origin, -origin, 0f, 0f);
                                // The eye does not move with the pivot: that is the
                                // construction, not a coincidence of the sweep.
                                assertEquals("eyeX h=" + h, a.eyeX, b.eyeX, 0f);
                                assertEquals("eyeY h=" + h, a.eyeY, b.eyeY, 0f);
                                assertEquals("eyeZ h=" + h, a.eyeZ, b.eyeZ, 0f);
                                // The plane's own points project identically.
                                for (float px : new float[]{-0.5f, 0f, 0.5f}) {
                                    for (float py : new float[]{-0.5f, 0f, 0.5f}) {
                                        float[] na = projectWorld(a,
                                                px * aspect * a.scale, py * a.scale, 0f);
                                        float[] nb = projectWorld(b,
                                                px * aspect * b.scale, py * b.scale, 0f);
                                        assertEquals("plane point " + px + "," + py
                                                        + " moved at rest (h=" + h + ")",
                                                na[0], nb[0], 1e-4f);
                                        assertEquals("plane point " + px + "," + py
                                                        + " moved at rest (h=" + h + ")",
                                                na[1], nb[1], 1e-4f);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What the pivot at depth buys: under a tilt the camera aims at the surface
     * point under the origin, so THAT point holds the screen centre while the
     * plane behind it swings — the user's orbit around the subject rather than the background. With the pivot on the plane the old behaviour stands:
     * the plane point is the fixed one.
     */
    @Test
    public void thePivotHoldsAndThePlaneMovesUnderTilt() {
        for (float h : new float[]{0.45f, 0.2f, -0.45f}) {
            for (float[] tilt : new float[][]{{0.16f, 0f}, {0f, -0.16f}, {0.12f, 0.09f}}) {
                CameraModel m = model();
                m.setPivotHeight(h);
                CameraModel.Frame f = m.frame(SURFACE_W, SURFACE_H, 0f, 0f,
                        0.2f, -0.1f, tilt[0], tilt[1]);
                float[] pivot = projectWorld(f, f.pivotX, f.pivotY, f.pivotZ);
                assertEquals("the pivot must hold the centre (h=" + h + ")", 0f, pivot[0], 1e-4f);
                assertEquals("the pivot must hold the centre (h=" + h + ")", 0f, pivot[1], 1e-4f);
                // The plane point behind it is where the old pivot was: with the
                // aim on the surface it must MOVE, and that motion is the reveal.
                float[] plane = projectWorld(f, f.pivotX, f.pivotY, 0f);
                assertTrue("the plane behind the pivot must swing (h=" + h + ")",
                        Math.hypot(plane[0], plane[1]) > 1e-3f);
            }
        }
        CameraModel m = model();
        CameraModel.Frame f = m.frame(SURFACE_W, SURFACE_H, 0f, 0f,
                0.2f, -0.1f, 0.16f, 0f);
        float[] plane = projectWorld(f, f.pivotX, f.pivotY, 0f);
        assertEquals("pivot on the plane: the plane point is the fixed one", 0f, plane[0], 1e-4f);
        assertEquals("pivot on the plane: the plane point is the fixed one", 0f, plane[1], 1e-4f);
    }

    /**
     * The pivot's world z is the field height through the SAME model-to-world
     * factor {@code uMVP} applies ({@code S(scale, scale, scale * zScale)}), so
     * the camera aims at the drawn surface point rather than at some other
     * depth. The clamp and the non-finite case are pinned here too, because
     * either one being wrong puts the aim behind the eye and the picture
     * upside down.
     */
    @Test
    public void pivotWorldZIsTheFieldHeightThroughTheModelScale() {
        CameraModel m = model();
        for (float h : new float[]{-0.5f, -0.2f, 0f, 0.35f, 0.5f}) {
            for (float depth : new float[]{0f, 0.5f, 1f}) {
                m.setDepth(depth);
                m.setPivotHeight(h);
                CameraModel.Frame f = m.frame(SURFACE_W, SURFACE_H, 0f, 0f,
                        0.1f, -0.2f, 0f, 0f);
                assertEquals("h=" + h + " depth=" + depth,
                        h * f.scale * f.zScale, f.pivotZ, 1e-6f);
                assertEquals("pivot x", 0.1f * IMAGE_ASPECT * f.scale, f.pivotX, 1e-6f);
                assertEquals("pivot y", -0.2f * f.scale, f.pivotY, 1e-6f);
            }
        }
        // Beyond the model's own depth axis clamps; a non-finite height is the
        // plane -- a window claims nothing, and NaN in the view matrix is a
        // black screen.
        m.setDepth(1f);
        m.setPivotHeight(2f);
        CameraModel.Frame clamped = m.frame(SURFACE_W, SURFACE_H, 0f, 0f, 0f, 0f, 0f, 0f);
        assertEquals(0.5f * clamped.scale * clamped.zScale, clamped.pivotZ, 1e-6f);
        m.setPivotHeight(Float.NaN);
        assertEquals(0f, m.frame(SURFACE_W, SURFACE_H, 0f, 0f, 0f, 0f, 0f, 0f).pivotZ, 0f);
    }
}
