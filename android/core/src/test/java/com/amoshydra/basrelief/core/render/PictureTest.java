package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two picture adjustments.
 *
 * <p>These exist as a class in {@code :core} rather than as a line of GLSL because
 * the shader cannot be tested: {@code :app} has no unit tests, so arithmetic that
 * lives there is arithmetic nobody can check. The split means only the arithmetic
 * stayed behind — see {@link Picture}.
 *
 * <p>The traps here are the ones this project has already paid for elsewhere: a
 * value that is not the identity at 0, and a clamp that only half applies.
 */
public class PictureTest {

    private static final float EPS = 1e-6f;

    // ------------------------------------------------------------- identities

    /**
     * Zero must be the identity for both.
     *
     * <p>This is the load-bearing property of the whole class. A photo nobody has
     * adjusted has to render exactly as it was imported, so if 0 does something —
     * even something imperceptible — every user's wallpaper is wrong and nothing
     * says so. It is asserted per adjustment and then jointly, because a shared
     * constant is exactly where one adjustment's zero could drift from the other's.
     */
    @Test
    public void zeroIsTheIdentityForBoth() {
        assertEquals("brightness must be a no-op at 0", 1f, Picture.brightnessGain(0), EPS);
        assertEquals("shadow must be a no-op at 0", 0f, Picture.shadowLift(0), EPS);
    }

    /**
     * A stored 0 is indistinguishable from no adjustment at all.
     *
     * <p>The reason the identity matters in practice: a new photo is written with
     * explicit zeros rather than absent keys, so every photo in every library takes
     * this path on its first render.
     */
    @Test
    public void anUntouchedPhotoRendersIdenticallyToOneWithNoAdjustment() {
        assertEquals(Picture.brightnessGain(0), Picture.brightnessGain(0), 0f);
        assertEquals(Picture.shadowLift(0), Picture.shadowLift(0), 0f);
    }

    // ------------------------------------------------------------- brightness

    /**
     * Brightness is hundredths of a stop, so it is exponential.
     *
     * <p>Pinned because a linear mapping would pass a naive "0 means no change"
     * test while being visibly wrong: an addition of 1.0 in place of a stop is a
     * huge shift, and it darkens shadows rather than brightening the image evenly.
     */
    @Test
    public void brightnessIsMeasuredInStops() {
        assertEquals("one stop up doubles the image", 2f, Picture.brightnessGain(100), EPS);
        assertEquals("one stop down halves it", 0.5f, Picture.brightnessGain(-100), EPS);
        assertEquals("half a stop up is a root two", (float) Math.sqrt(2.0d),
                Picture.brightnessGain(50), EPS);
    }

    /** Brighter is always a larger multiplier. A sign error here is invisible otherwise. */
    @Test
    public void brightnessIncreasesMonotonically() {
        float previous = 0f;
        for (int v = -100; v <= 100; v += 5) {
            float gain = Picture.brightnessGain(v);
            assertTrue("gain fell at " + v, gain > previous);
            previous = gain;
        }
    }

    // ---------------------------------------------------------------- shadow

    /**
     * Shadow is a signed lift, not a magnitude.
     *
     * <p>Negative deepens rather than doing nothing. An unsigned lift would read -100
     * as 0 and leave "more contrast" and "less contrast" indistinguishable, which is
     * how a two-sided control ends up one-sided.
     */
    @Test
    public void shadowIsSigned() {
        assertTrue("negative shadow must darken", Picture.shadowLift(-50) < 0f);
        assertTrue("positive shadow must lighten", Picture.shadowLift(50) > 0f);
        assertEquals(-0.5f, Picture.shadowLift(-50), EPS);
        assertEquals(0.5f, Picture.shadowLift(50), EPS);
    }

    // ------------------------------------------------------------- clamping

    /**
     * Both clamp on the way in, symmetrically and completely.
     *
     * <p>Delta exactly 0 on purpose, as elsewhere in this project: a tolerance would
     * hide a clamp that only half applies, and a one-sided clamp is exactly the bug
     * this catches — the stored value is bounded on write by the same convention
     * {@code SurfaceSettings} uses, and a second bound here that disagrees with it
     * would make the stored value and the rendered value differ.
     */
    @Test
    public void bothAdjustmentsClampSymmetrically() {
        assertEquals("brightness clamps high", Picture.brightnessGain(100),
                Picture.brightnessGain(9999), 0f);
        assertEquals("brightness clamps low", Picture.brightnessGain(-100),
                Picture.brightnessGain(-9999), 0f);
        assertEquals("shadow clamps high", 1f, Picture.shadowLift(9999), 0f);
        assertEquals("shadow clamps low", -1f, Picture.shadowLift(-9999), 0f);
    }

    /**
     * Clamping is to the same -100..100 the store uses.
     *
     * <p>Pinned by asserting against {@link com.amoshydra.basrelief.core.store
     * .SurfaceSettings}'s own bounds, because two constants that agree today and
     * drift tomorrow is the failure mode: the value would be stored at 100 and
     * rendered as something else, and the slider would show a number the renderer
     * is not using — the exact lie the bundled-demo rule exists to prevent.
     */
    @Test
    public void theClampIsTheStoresClamp() {
        assertEquals(100, com.amoshydra.basrelief.core.store.SurfaceSettings.ADJUST_MAX);
        assertEquals(-100, com.amoshydra.basrelief.core.store.SurfaceSettings.ADJUST_MIN);
        assertEquals(2f, Picture.brightnessGain(
                com.amoshydra.basrelief.core.store.SurfaceSettings.ADJUST_MAX), EPS);
        assertEquals(-1f, Picture.shadowLift(
                com.amoshydra.basrelief.core.store.SurfaceSettings.ADJUST_MIN), 0f);
    }
}