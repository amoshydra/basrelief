package com.amoshydra.basrelief.core.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The morph's arithmetic: the panel's height as it becomes the pill, the content's
 * fade, and the corner radius at each end.
 *
 * <p><b>The traps, each of which is a real failure this project has shipped.</b>
 *
 * <ol>
 *   <li><b>A height that reaches zero cannot come back.</b> That was the pop: the
 *       old mapping was {@code (1 - f) * natural}, so the panel had no height to
 *       grow from and a second view had to appear in its place. The floor is
 *       {@code pillPx}, tested here at both ends and by a sweep, because a
 *       lerp that dips below its floor for some intermediate fraction would be a
 *       card that gets <i>taller</i> as it collapses.
 *   <li><b>The band must begin where resting stops being possible.</b> The studio
 *       sheet is {@code FREE} and parks anywhere below its collapse threshold, so a
 *       fade starting earlier would leave a usable sheet at half alpha — the exact
 *       "looks disabled" complaint, reintroduced. The first test in the fade block
 *       is therefore not "the fade works" but "the fade cannot reach a position
 *       the sheet can rest at", and the band constant is pinned equal to the
 *       policy's threshold so the two cannot drift apart.
 *   <li><b>A fraction, not a percentage of what is left.</b> {@code edgeFade} must
 *       be exactly {@code 1.0} at {@code f = 0} and at the band start — not
 *       {@code 0.9999998} from a division — and exactly {@code 0.0} at the pill.
 *   </li>
 *   <li><b>A corner radius larger than half the box is a bite, not a round.</b>
 *       Tested by sweeping, because the bound is what makes it safe at any panel
 *       size and the failure is invisible at one size.
 * </ol>
 *
 * <p>It is in :core because the rule is two floats and a constant; the thing that
 * turns it into pixels is a {@code LayoutParams} write, and that is :app's.
 */
public class SheetMorphTest {

    /** A tall panel and a 56dp pill, as on the Pixel: px throughout. */
    private static final float NATURAL = 2400f;
    private static final float PILL = 168f;   // 56dp at 3x
    private static final float OPEN_R = 22f;  // the sheet's own top corner, px
    private static final float PILL_R = 28f;  // half the pill's height: a semicircle

    // ------------------------------------------------------------ the height

    /** Exact endpoints: this is the whole contract, and the morph is built on it. */
    @Test
    public void theHeightIsTheNaturalHeightWhenOpenAndThePillWhenCollapsed() {
        assertEquals(NATURAL, SheetMorph.heightFor(0f, NATURAL, PILL), 0f);
        assertEquals(PILL, SheetMorph.heightFor(1f, NATURAL, PILL), 0f);
    }

    /**
     * Never below the pill — at any fraction, and for a panel shorter than its own
     * pill.
     *
     * <p>The second half is the one that is not obvious: a lerp between a natural
     * height and a pill height where the pill is <i>taller</i> would make the panel
     * grow as the user collapsed it. {@code heightFor} clamps its inputs instead,
     * so a rotation caught mid-morph cannot invert the gesture.
     */
    @Test
    public void theHeightIsNeverBelowThePill() {
        for (int i = 0; i <= 100; i++) {
            float f = i / 100f;
            assertTrue("at f=" + f + " the panel is shorter than the pill",
                    SheetMorph.heightFor(f, NATURAL, PILL) >= PILL - 1e-4f);
            // And with a pill taller than the panel, which is a degenerate case that
            // must still not invert.
            float inverted = SheetMorph.heightFor(f, PILL, NATURAL);
            assertTrue("an over-tall pill must not make a short panel grow as it "
                    + "collapses (f=" + f + ")", inverted >= NATURAL - 1e-4f);
        }
    }

    /** Monotonic: every step down is a step smaller, so a reversal reads as a reversal. */
    @Test
    public void theHeightOnlyEverDecreasesAsTheSheetCollapses() {
        float previous = Float.MAX_VALUE;
        for (int i = 0; i <= 1000; i++) {
            float h = SheetMorph.heightFor(i / 1000f, NATURAL, PILL);
            assertTrue("grew at f=" + (i / 1000f), h <= previous + 1e-4f);
            previous = h;
        }
    }

    /**
     * The over-open tether survives, continuous through zero.
     *
     * <p>A drag upward past fully open carries a small negative fraction and the
     * panel rises above its resting place before settling back — the spring the
     * user can feel at the top of a drag. Clamping {@code f < 0} to zero would
     * delete it, and the jump at zero would be a visible step in the same gesture.
     */
    @Test
    public void aboveOpenThePanelKeepsRisingAndStaysContinuousAtZero() {
        assertTrue("a tenth of the span past open must still be taller",
                SheetMorph.heightFor(-0.1f, NATURAL, PILL) > NATURAL);
        assertEquals(NATURAL, SheetMorph.heightFor(-1e-6f, NATURAL, PILL), 1e-2f);
        assertEquals(NATURAL, SheetMorph.heightFor(0f, NATURAL, PILL), 0f);
        // resistUp is bounded at a fifth of the span, so the far end is 1.1x.
        assertEquals(NATURAL * 1.1f, SheetMorph.heightFor(-0.1f, NATURAL, PILL), 1e-2f);
    }

    /** Degenerate inputs must not throw or produce a negative height. */
    @Test
    public void nothingHereThrowsOnDegenerateInput() {
        assertEquals(0f, SheetMorph.heightFor(0f, 0f, 0f), 0f);
        assertEquals(0f, SheetMorph.heightFor(1f, -5f, -5f), 0f);
        assertTrue(SheetMorph.heightFor(0.5f, 100f, 100f) > 0f);
    }

    // ------------------------------------------------------------- the fade

    /**
     * **The trap as a test: no reachable position has a present sheet and a faded
     * one.** The studio sheet is FREE, so it can rest anywhere the policy does not
     * collapse it, and that range ends exactly at the band's start.
     */
    @Test
    public void aSheetThatCanRestIsNeverFaded() {
        for (int i = 0; i <= 950; i++) {
            float f = i / 1000f;
            assertEquals("f=" + f + " can rest, so its content must be fully opaque",
                    1f, SheetMorph.edgeFade(f), 0f);
        }
        // And the boundary itself, from both sides: exactly 1.0 at the band start.
        assertEquals(1f, SheetMorph.edgeFade(SheetMorph.EDGE_BAND_START), 0f);
        assertEquals(1f, SheetMorph.edgeFade(SheetMorph.EDGE_BAND_START - 1e-4f), 0f);
    }

    /**
     * The band begins where {@link SheetIntent#SHEET} stops being able to rest.
     *
     * <p>Pinned rather than assumed. If someone moves the studio's collapse
     * threshold, the fade band has to move with it or the sheet grows a reachable
     * place where it is present and faded — which is the bug the tight band
     * exists to make impossible.
     */
    @Test
    public void theBandStartsWhereTheStudioSheetStopsBeingAbleToRest() {
        assertEquals("a fade band that opens before the resting range does would "
                        + "park a usable sheet at half alpha",
                SheetIntent.SHEET.collapseThreshold, SheetMorph.EDGE_BAND_START, 0f);
    }

    /** The endpoints, exactly — a division that lands on 0.9999998 is a bug. */
    @Test
    public void theFadeIsOneAtOpenAndZeroAtThePill() {
        assertEquals(1f, SheetMorph.edgeFade(0f), 0f);
        assertEquals(0f, SheetMorph.edgeFade(1f), 0f);
        // Past the pill it must stay at the floor rather than going negative, or a
        // settle overshoot would set a negative alpha and throw.
        assertEquals(0f, SheetMorph.edgeFade(1.4f), 0f);
    }

    /**
     * One ramp over the <b>whole real line</b>, never a rise, always in range.
     *
     * <p>Sweeping past both ends is the "no history" requirement made testable.
     * "Dragging back up retraces the same alpha" cannot be asserted by comparing a
     * pure function with itself, but the <i>property that delivers it</i> can: a
     * total, single-valued, non-increasing function of one argument has nothing to
     * remember, so there is no state in which "coming up" could differ from
     * "going down". An implementation carrying a direction flag, or a fade driven
     * by anything but {@code f}, fails here.
     */
    @Test
    public void theFadeOnlyEverFallsAsTheSheetCollapses() {
        float previous = Float.MAX_VALUE;
        for (int i = -1000; i <= 2000; i++) {
            float f = i / 1000f;
            float a = SheetMorph.edgeFade(f);
            assertTrue("rose at f=" + f, a <= previous + 1e-6f);
            assertTrue("out of 0..1 at f=" + f, a >= 0f && a <= 1f);
            previous = a;
        }
    }

    /**
     * The band spans exactly the last stretch of travel — and that is the whole of
     * "identical in both directions" that a test can check.
     *
     * <p><b>What is not testable here, and why.</b> "Dragging back up retraces the
     * same alpha" is a property of {@code edgeFade} taking <i>one</i> argument and
     * holding no state: there is no direction for it to get wrong. Comparing
     * {@code edgeFade(f)} with {@code edgeFade(f)} would be a tautology dressed as
     * a test, which is worse than no test because it cannot fail. The assertable
     * half — and the half the brief's trap actually lives in — is the band's
     * <b>extent</b>: opaque at its start, gone at the pill, strictly between, and
     * narrow. If the band were wide, a sheet could rest inside it, and that is the
     * "parked at half alpha" failure; so the band's width is pinned to the same
     * 0.05 as the policy's dead zone.
     */
    @Test
    public void theBandSpansExactlyTheLastStretchOfTravel() {
        assertEquals("the band is the last 5% of travel, which is the last 5% the "
                        + "sheet can no longer rest in",
                0.05f, 1f - SheetMorph.EDGE_BAND_START, 1e-6f);
        assertEquals(1f, SheetMorph.edgeFade(SheetMorph.EDGE_BAND_START), 0f);
        assertEquals(0f, SheetMorph.edgeFade(1f), 0f);
        // Strictly between, everywhere inside: a flat step would be a pop, and this
        // is the part that has to be a ramp for the morph to read as continuous.
        for (int i = 1; i < 100; i++) {
            float f = SheetMorph.EDGE_BAND_START
                    + (1f - SheetMorph.EDGE_BAND_START) * (i / 100f);
            float a = SheetMorph.edgeFade(f);
            assertTrue("not ramping at f=" + f + " (alpha " + a + ")", a > 0f && a < 1f);
        }
    }

    /**
     * At the band start the sheet is <b>already nearly the pill</b> — which is why
     * fading there is honest rather than the "looks disabled" failure.
     *
     * <p>This test replaced one that asserted the opposite and was simply wrong.
     * It claimed the band begins with the panel "still nearly its full height", on
     * the reasoning that the fade must not be keyed to height. But the height
     * mapping is a <i>linear lerp</i>, so 5% of travel is 5% of the height range
     * either way, and at {@code EDGE_BAND_START} the panel is down to about an
     * eighth of its natural height — 93dp against a 56dp pill on the Pixel. There
     * is no meaningful difference between "fraction of travel" and "fraction of
     * height remaining" here, because the two mappings are the same function. The
     * distinction that <i>is</i> real is structural — {@code edgeFade} takes no
     * height at all — and it is stated in the code, not testable here.
     *
     * <p>What the number below pins is the <b>substantive</b> half of the tight
     * band, which the other tests do not reach on their own: the reason a user who
     * parks inside the band is not looking at a usable sheet is that the sheet has
     * already shrunk to a few dp of content above the strip. If the pill were, say,
     * a fifth of the panel rather than 56dp, the band would need to be much
     * narrower still, and this test is what would say so.
     */
    @Test
    public void insideTheBandTheSheetIsAlreadyNearlyThePill() {
        float atBandStart = SheetMorph.heightFor(SheetMorph.EDGE_BAND_START, NATURAL, PILL);
        assertTrue("a sheet parked at the band start must not be a usable sheet, or "
                        + "fading its content is the 'looks disabled' bug",
                atBandStart < NATURAL * 0.2f);
        // Still the pill's floor plus a sliver, and the sliver is what fades.
        assertTrue(atBandStart > PILL);
        assertTrue("and the whole band is a small slice of the height range",
                (atBandStart - PILL) < (NATURAL - PILL) * 0.1f);
    }

    // ------------------------------------------------------- corner radius

    /** The expanded sheet's 22dp, then the pill's own 28dp. Both exact. */
    @Test
    public void theCornerRadiusIsTheSheetsOwnWhenOpenAndThePillsWhenNot() {
        assertEquals(22f, SheetMorph.cornerRadiusFor(0f, NATURAL, OPEN_R, PILL_R), 0f);
        assertEquals(PILL_R, SheetMorph.cornerRadiusFor(1f, PILL, OPEN_R, PILL_R), 1e-4f);
    }

    /**
     * Never more than half the height it is rounding, at any fraction and any size.
     *
     * <p>The failure is a card with a bite taken out of each top corner, and it is
     * invisible at the one size it was written for — hence the sweep over panel
     * heights, not one number.
     */
    @Test
    public void theCornerRadiusNeverExceedsHalfTheHeight() {
        for (int h = 0; h <= 4000; h += 20) {
            for (int i = 0; i <= 100; i++) {
                float r = SheetMorph.cornerRadiusFor(i / 100f, h, OPEN_R, PILL_R);
                assertTrue("height=" + h + " f=" + (i / 100f) + " radius=" + r,
                        r <= h / 2f + 1e-3f);
                assertTrue(r >= 0f);
            }
        }
    }

    /**
     * It interpolates over a <b>6dp</b> range, not over the panel's half-height.
     *
     * <p>This is the test the wrong implementation failed, and the number is the
     * whole point. Reading "half the box" as the target gave a 2400px panel a
     * <b>611px</b> corner radius at {@code f = 0.5} — a stadium halfway down a
     * drag, nowhere near either end the user chose. The two ends are 22dp and
     * 28dp, so every intermediate value must sit between 22 and 28 and move in
     * hundredths of a dp: a rounding that travels with the panel is the same bug
     * again on a different screen.
     */
    @Test
    public void theCornerRadiusInterpolatesOverTheShortRangeBetweenTheTwoEnds() {
        for (int i = 0; i <= 1000; i++) {
            float f = i / 1000f;
            float r = SheetMorph.cornerRadiusFor(f, NATURAL, OPEN_R, PILL_R);
            assertTrue("f=" + f + " gave " + r + ", which is not between the sheet's "
                            + "own corner and the pill's",
                    r >= OPEN_R - 1e-3f && r <= PILL_R + 1e-3f);
        }
        // Mid-way is mid-way, and moves with its neighbours rather than jumping.
        float mid = SheetMorph.cornerRadiusFor(0.5f, NATURAL, OPEN_R, PILL_R);
        assertEquals((OPEN_R + PILL_R) / 2f, mid, 1e-3f);
        assertTrue(mid > SheetMorph.cornerRadiusFor(0.49f, NATURAL, OPEN_R, PILL_R));
        assertTrue(mid < SheetMorph.cornerRadiusFor(0.51f, NATURAL, OPEN_R, PILL_R));
    }

    /** A radius taller than the box it rounds is clamped, for the same reason. */
    @Test
    public void anOverLargeOpenRadiusIsClampedToHalfTheHeight() {
        assertEquals(50f, SheetMorph.cornerRadiusFor(0f, 100f, 400f, PILL_R), 1e-4f);
        // And the pill's target is clamped by the same ceiling when the strip is
        // shorter than the radius it was asked for.
        assertEquals(20f, SheetMorph.cornerRadiusFor(1f, 40f, OPEN_R, 400f), 1e-4f);
    }
}
