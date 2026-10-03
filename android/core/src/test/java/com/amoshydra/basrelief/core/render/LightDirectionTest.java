package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The angle→vector mapping, which is the one piece of the lighting feature a
 * device cannot check.
 *
 * <p>The themes:
 *
 * <ol>
 *   <li><b>The default is the historical literal.</b> The renderer used to pass
 *       {@code (-0.35, 0.5, 0.75)} to the shader directly. The derived default
 *       angles must reproduce that direction, or every existing photo is
 *       relit on upgrade — a change with no error, no log line and no way for
 *       a user to describe what happened.
 *   <li><b>The convention is unambiguous.</b> A slider on the next screen is
 *       built against azimuth from the right and elevation from grazing; a sign
 *       flip is invisible until someone moves the control and expects the
 *       shading to follow.
 *   <li><b>The glide takes the short arc.</b> 350°→10° is 20° of travel, not
 *       340°, and two opposite lights must not interpolate through a zero
 *       vector that has no direction.
 * </ol>
 */
public class LightDirectionTest {

    private static final float EPS = 1e-6f;

    private static void assertVector(float x, float y, float z, LightDirection d) {
        assertEquals("x", x, d.x, EPS);
        assertEquals("y", y, d.y, EPS);
        assertEquals("z", z, d.z, EPS);
    }

    // ------------------------------------------------------------ the default

    /**
     * The test the whole feature has to pass before it ships.
     *
     * <p>The shader holds {@code l = normalize(uLightDir)} and uses it in one
     * {@code dot}, so <b>only the direction matters, not the length</b> — but the
     * direction must not move. The old literal has length ≈ 0.96695, so comparing
     * against it unnormalised is comparing against a different length, not a
     * different light. Normalise it and the derived angles reproduce it to better
     * than {@code 1e-6} per component. Measured on this JVM the largest error is
     * about {@code 7e-8}; the stated epsilon is an order of magnitude of headroom
     * for a different libm, and still three orders below the smallest change an
     * 8-bit framebuffer can show ({@code 1/255 ≈ 3.9e-3}).
     */
    @Test
    public void theDefaultReproducesTheHistoricalLiteral() {
        double len = Math.sqrt(0.35 * 0.35 + 0.5 * 0.5 + 0.75 * 0.75);
        float literalX = (float) (-0.35 / len);
        float literalY = (float) (0.5 / len);
        float literalZ = (float) (0.75 / len);

        LightDirection d = LightDirection.of(
                LightDirection.DEFAULT_AZIMUTH_DEG,
                LightDirection.DEFAULT_ELEVATION_DEG);

        assertEquals(literalX, d.x, EPS);
        assertEquals(literalY, d.y, EPS);
        assertEquals(literalZ, d.z, EPS);
        // The named DEFAULT is the same resolution, so a renderer initialising
        // from it cannot drift from one that calls of() with the constants.
        assertEquals(d.x, LightDirection.DEFAULT.x, 0f);
        assertEquals(d.y, LightDirection.DEFAULT.y, 0f);
        assertEquals(d.z, LightDirection.DEFAULT.z, 0f);
    }

    // ------------------------------------------------------------- the mapping

    @Test
    public void ofReturnsUnitLength() {
        float[] azimuths = {0f, 33f, 90f, 179.5f, 250f, 359f};
        float[] elevations = {0f, 15f, 50.862f, 76f, 90f};
        for (float az : azimuths) {
            for (float el : elevations) {
                LightDirection d = LightDirection.of(az, el);
                float length = (float) Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);
                assertEquals("az=" + az + " el=" + el, 1f, length, EPS);
            }
        }
    }

    /**
     * The convention sliders are built against: counter-clockwise from the right,
     * elevation from grazing to head-on.
     */
    @Test
    public void theConventionIsCounterClockwiseFromTheRight() {
        assertVector(1f, 0f, 0f, LightDirection.of(0f, 0f));     // right
        assertVector(0f, 1f, 0f, LightDirection.of(90f, 0f));    // top
        assertVector(-1f, 0f, 0f, LightDirection.of(180f, 0f));  // left
        assertVector(0f, -1f, 0f, LightDirection.of(270f, 0f));  // bottom
        assertVector(0f, 0f, 1f, LightDirection.of(0f, 90f));    // head-on
    }

    /** Elevation 0 is in the picture plane; 90 is straight at the viewer. */
    @Test
    public void elevationZeroIsGrazingAndNinetyIsHeadOn() {
        assertEquals(0f, LightDirection.of(137f, 0f).z, EPS);
        assertTrue("raising elevation must push the light toward the viewer",
                LightDirection.of(137f, 45f).z > 0f);
        assertVector(0f, 0f, 1f, LightDirection.of(250f, 90f));
    }

    // ---------------------------------------------------------- the normalisation

    /**
     * Azimuth wraps rather than clamps: 0 and 360 are the same light, so a clamp
     * would be a dead zone at the seam. Both directions must come out identical.
     */
    @Test
    public void azimuthWrapsRatherThanClampingAtTheSeam() {
        assertVector(LightDirection.of(350f, 30f).x,
                LightDirection.of(350f, 30f).y,
                LightDirection.of(350f, 30f).z,
                LightDirection.of(-10f, 30f));
        assertVector(LightDirection.of(30f, 30f).x,
                LightDirection.of(30f, 30f).y,
                LightDirection.of(30f, 30f).z,
                LightDirection.of(390f, 30f));
        assertVector(LightDirection.of(0f, 0f).x,
                LightDirection.of(0f, 0f).y,
                LightDirection.of(0f, 0f).z,
                LightDirection.of(720f, 0f));
    }

    /** Elevation clamps: behind the relief is not a state this shader can show. */
    @Test
    public void elevationClampsToTheFrontHalfSpace() {
        assertVector(LightDirection.of(20f, 0f).x,
                LightDirection.of(20f, 0f).y,
                LightDirection.of(20f, 0f).z,
                LightDirection.of(20f, -40f));
        assertVector(LightDirection.of(20f, 90f).x,
                LightDirection.of(20f, 90f).y,
                LightDirection.of(20f, 90f).z,
                LightDirection.of(20f, 120f));
    }

    /**
     * A non-finite angle resolves to the default rather than to a NaN vector.
     *
     * <p>A NaN reaching the uniform makes pixels undefined, and on the wallpaper
     * the only caller that can supply one is a corrupt blob on the GL thread —
     * which must not be a crash and must not be a black screen.
     */
    @Test
    public void nonFiniteAnglesBecomeTheDefault() {
        // Each angle falls back on its own; the other one still applies.
        assertVector(LightDirection.DEFAULT.x, LightDirection.DEFAULT.y,
                LightDirection.DEFAULT.z,
                LightDirection.of(Float.NaN, LightDirection.DEFAULT_ELEVATION_DEG));
        assertEquals("a NaN azimuth takes the default azimuth",
                LightDirection.DEFAULT_AZIMUTH_DEG,
                LightDirection.of(Float.NaN, 30f).azimuthDegrees(), 1e-3f);
        assertEquals("a NaN elevation takes the default elevation",
                LightDirection.DEFAULT_ELEVATION_DEG,
                LightDirection.of(30f, Float.NaN).elevationDegrees(), 1e-3f);
    }

    // -------------------------------------------------------------- the inverse

    /** The inverse is cheap and must round-trip, because the glide relies on it. */
    @Test
    public void theInverseRecoversTheAngles() {
        LightDirection d = LightDirection.of(312.5f, 67.25f);
        assertEquals(312.5f, d.azimuthDegrees(), 1e-3f);
        assertEquals(67.25f, d.elevationDegrees(), 1e-3f);

        LightDirection again = LightDirection.fromVector(d.x, d.y, d.z);
        assertEquals(d.x, again.x, EPS);
        assertEquals(d.y, again.y, EPS);
        assertEquals(d.z, again.z, EPS);

        // A directionless vector is not a direction; the default is the only
        // honest answer and keeps a NaN out of the renderer.
        assertVector(LightDirection.DEFAULT.x, LightDirection.DEFAULT.y,
                LightDirection.DEFAULT.z, LightDirection.fromVector(0f, 0f, 0f));
    }

    // --------------------------------------------------------------- the glide

    /**
     * The glide decision, pinned as arithmetic: carry the angles and take the
     * short arc.
     *
     * <p>This is why {@code Framing} exposes angles rather than a vector. The
     * naive component lerp of 350°→10° passes through 180° — the light sweeps
     * the wrong way through every intermediate angle — and two opposite lights
     * (0° and 180° at elevation 0) produce the zero vector midway, which has no
     * direction to normalize.
     */
    @Test
    public void theGlideTakesTheShortArcAroundTheSeam() {
        assertEquals(350f, LightDirection.lerpAzimuth(350f, 10f, 0f), 1e-4f);
        assertEquals(10f, LightDirection.lerpAzimuth(350f, 10f, 1f), 1e-4f);
        assertEquals(355f, LightDirection.lerpAzimuth(350f, 10f, 0.25f), 1e-4f);
        assertEquals(0f, LightDirection.lerpAzimuth(350f, 10f, 0.5f), 1e-4f);
        assertEquals(5f, LightDirection.lerpAzimuth(350f, 10f, 0.75f), 1e-4f);

        // Halfway is the light from the RIGHT, not the 340°-long way round.
        LightDirection mid = LightDirection.of(
                LightDirection.lerpAzimuth(350f, 10f, 0.5f), 0f);
        assertVector(1f, 0f, 0f, mid);
    }

    /**
     * Two opposite lights interpolate through a defined direction.
     *
     * <p>A component lerp from (1,0,0) to (-1,0,0) is exactly (0,0,0) at the
     * midpoint. The angle path passes through 90° instead — the light from the
     * top — which is what a viewer expects a light rotating from one side to
     * the other to do.
     */
    @Test
    public void oppositeLightsNeverInterpolateThroughAZeroVector() {
        LightDirection mid = LightDirection.of(
                LightDirection.lerpAzimuth(0f, 180f, 0.5f), 0f);
        assertVector(0f, 1f, 0f, mid);
    }

    /** Lerping beyond the ends stays wrapped, so a glide can overshoot safely. */
    @Test
    public void lerpAzimuthStaysInRange() {
        for (float t = -0.5f; t <= 1.5f; t += 0.25f) {
            float az = LightDirection.lerpAzimuth(10f, 350f, t);
            assertTrue("az=" + az + " at t=" + t, az >= 0f && az < 360f);
        }
    }
}
