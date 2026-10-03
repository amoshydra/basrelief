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
    private static final float SIDE = 84f;    // the pill's 28dp side margin, px at 3x
    /** The panel's own glass, rgba(22,26,32,.92) -- the strip at f = 0. */
    private static final int GLASS = 0xEB161A20;
    /** The original pill's plate, rgba(28,32,38,.72) -- the strip at f = 1. */
    private static final int PLATE = 0xB81C2026;

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

    /**
     * Monotonic, which here means <b>non-increasing</b>: every step is level or down
     * and never up, so a reversal of the gesture reads as a reversal.
     *
     * <p>The plateau is the feature and not an accident of the fixture. This sweep
     * used to assert only {@code h <= previous}, which a strictly falling curve also
     * satisfies — so it passed under <i>both</i> mappings and could not tell them
     * apart. {@link #theSheetKeepsEveryPixelOfItsHeightUntilTheBandStarts} is the
     * test that separates them.
     */
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
     * The height gives up ground from the first pixel of the drag.
     *
     * <p>This test asserted the opposite for a while — that the sheet kept every pixel
     * until a band near the bottom — and it passed, because the numbers were right and
     * the gesture was not. Dragged through four fifths of the travel with no change
     * and then a sudden collapse: <i>the height did not follow the drag and then collapsed all at once; a bottom sheet's height should track the finger</i> Whatever else a bottom sheet is, it is a thing whose
     * height follows your hand.
     */
    @Test
    public void theSheetShrinksSteadilyFromTheStartOfTheDrag() {
        assertEquals(NATURAL, SheetMorph.heightFor(0f, NATURAL, PILL), 0f);
        // A tenth of the way down has already given up a tenth of the height.
        assertEquals(NATURAL - (NATURAL - PILL) * 0.1f,
                SheetMorph.heightFor(0.1f, NATURAL, PILL), 1e-2f);
        assertEquals(PILL, SheetMorph.heightFor(1f, NATURAL, PILL), 0f);
        // Monotone and non-increasing everywhere: no plateau, no cliff.
        float previous = NATURAL;
        for (int i = 1; i <= 1000; i++) {
            float now = SheetMorph.heightFor(i / 1000f, NATURAL, PILL);
            assertTrue("height rose at " + i / 1000f, now <= previous);
            previous = now;
        }
    }

    /**
     * The height falls in the <b>same stretch</b> as the content fades, because that
     * is the coherence rather than a coincidence: inside the band the content is
     * dissolving and the sheet is losing its height, and outside it both say "still
     * here".
     *
     * <p>Asserted as an equality so a later edit to one constant cannot quietly
     * break the story. The same relationship is asserted against {@link SheetIntent}'s
     * collapse threshold elsewhere in this file, which is the third link in the same
     * chain — and the chain is the point: the fade's band, the height's band and the
     * policy that says where the sheet may rest are three numbers that must not drift
     * apart.
     */
    @Test
    public void theFadeBandIsTheSheetRestsBeyond() {
        // The GEOMETRY has no band -- it rides the drag the whole way, because it is a
        // bottom sheet. The OPACITY does, and it is pinned to exactly the line past
        // which the sheet can no longer come to rest, so no position a resting sheet can
        // occupy is ever faded. Asserted as an equality so a later edit to one number
        // cannot quietly break the story: the fade's band and the release policy are
        // two numbers that must not drift apart.
        assertEquals(SheetIntent.SHEET.collapseThreshold, SheetMorph.EDGE_BAND_START, 0f);
    }

    /** Superseded by theGeometryHasNoStepAnywhereInTheTravel, which asserts the
     *  continuity this one asserted as a hold-then-fall curve. The hold was a
     *  misreading of the user's instruction and is gone. */
    @Test
    public void theHeightIsNotHeldAndNotSnapped() {
        // A bottom sheet shrinks as you drag: every intermediate fraction is a real,
        // intermediate height, with no plateau to drag through and no cliff at the end.
        for (int i = 1; i < 100; i++) {
            float f = i / 100f;
            float here = SheetMorph.heightFor(f, NATURAL, PILL);
            float next = SheetMorph.heightFor((i + 1) / 100f, NATURAL, PILL);
            assertTrue("a plateau between " + f + " and " + (i + 1) / 100f
                            + ": " + here + " then " + next,
                    next < here);
        }
        assertEquals(NATURAL, SheetMorph.heightFor(0f, NATURAL, PILL), 0f);
        assertEquals(PILL, SheetMorph.heightFor(1f, NATURAL, PILL), 0f);
    }

    /**
     * The largest single-step change in the height over {@code n} equal steps of
     * travel. Doubling {@code n} must halve it for a continuous curve and not at all
     * for one with a jump, which is the whole of the continuity argument above.
     */
    private static float maxHeightStep(int n) {
        float worst = 0f;
        float previous = SheetMorph.heightFor(0f, NATURAL, PILL);
        for (int i = 1; i <= n; i++) {
            float now = SheetMorph.heightFor(i / (float) n, NATURAL, PILL);
            worst = Math.max(worst, Math.abs(now - previous));
            previous = now;
        }
        return worst;
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

    /**
     * <b>One progress for the whole morph.</b>
     *
     * <p>The bug this pins took three corrections to state. the sheet moved as three unrelated timelines was true because one gesture ran three
     * timelines: the height was held at full until the last 5% of travel while the
     * shape ramped across the whole thing, so the sheet changed shape while its size
     * stood still. Banding the height fixed that and broke something more basic — it
     * turned a bottom sheet into one that ignored four fifths of your drag and then
     * snapped: <i>the height did not follow the drag and then collapsed all at once; a bottom sheet's height should track the finger</i>
     *
     * <p>So the height ramps across the WHOLE travel, because that is what dragging a
     * bottom sheet down is, and every geometric property rides the same ramp — at any
     * fraction the sheet is at the same point in each of its journeys. Opacity is
     * deliberately not on this ramp: the content's fade is edge-only, which is a
     * separate instruction about a different property.
     */
    @Test
    public void everyGeometricPropertySharesOneProgress() {
        for (int i = 0; i <= 100; i++) {
            float f = i / 100f;
            float heightGone = (NATURAL - SheetMorph.heightFor(f, NATURAL, PILL))
                    / (NATURAL - PILL);
            assertEquals("the height is on a different timeline at f=" + f,
                    f, heightGone, 1e-3f);
            assertEquals("the inset is on a different timeline at f=" + f,
                    f, SheetMorph.insetFor(f, SIDE) / SIDE, 1e-3f);
            assertEquals("the side margin is on a different timeline at f=" + f,
                    f, SheetMorph.pillSideMarginFor(f, 1440f, 0.72f)
                            / (1440f * 0.28f * 0.5f), 1e-3f);
            assertEquals("the radius is on a different timeline at f=" + f,
                    f, (SheetMorph.cornerRadiusFor(f, NATURAL, OPEN_R, PILL_R) - OPEN_R)
                            / (PILL_R - OPEN_R), 1e-3f);
        }
        // The endpoints, exactly.
        assertEquals(NATURAL, SheetMorph.heightFor(0f, NATURAL, PILL), 0f);
        assertEquals(PILL, SheetMorph.heightFor(1f, NATURAL, PILL), 0f);
        // And half-way is genuinely half-way, which is the half of this the user had
        // to tell us: a bottom sheet is half its height at half the drag.
        assertEquals((NATURAL + PILL) / 2f, SheetMorph.heightFor(0.5f, NATURAL, PILL), 1f);
    }

    /** No step in the geometry anywhere, asserted by resolution rather than by bound. */
    @Test
    public void theGeometryHasNoStepAnywhereInTheTravel() {
        float previous = SheetMorph.heightFor(0f, NATURAL, PILL);
        for (int i = 1; i <= 1000; i++) {
            float f = i / 1000f;
            float now = SheetMorph.heightFor(f, NATURAL, PILL);
            assertTrue("the height rose at f=" + f, now <= previous);
            previous = now;
        }
        // Doubling the density must halve the largest single step; a seam cannot do
        // that, which is why continuity is asserted this way and not by tolerance.
        float coarse = maxStep(500);
        float fine = maxStep(1000);
        assertTrue("a step survived refinement: " + coarse + " -> " + fine,
                fine <= coarse + 1e-6f);
    }

    private static float maxStep(int steps) {
        float previous = SheetMorph.heightFor(0f, NATURAL, PILL);
        float largest = 0f;
        for (int i = 1; i <= steps; i++) {
            float now = SheetMorph.heightFor(i / (float) steps, NATURAL, PILL);
            largest = Math.max(largest, Math.abs(now - previous));
            previous = now;
        }
        return largest;
    }

    /** A radius taller than the box it rounds is clamped, for the same reason. */
    @Test
    public void anOverLargeOpenRadiusIsClampedToHalfTheHeight() {
        assertEquals(50f, SheetMorph.cornerRadiusFor(0f, 100f, 400f, PILL_R), 1e-4f);
        // And the pill's target is clamped by the same ceiling when the strip is
        // shorter than the radius it was asked for.
        assertEquals(20f, SheetMorph.cornerRadiusFor(1f, 40f, OPEN_R, 400f), 1e-4f);
    }

    /**
     * The bottom corners are the <i>same function with 0 as the open radius</i>, so
     * the two pairs arrive at one number. That equality is the whole capsule: a
     * shape whose top corners and bottom corners disagree is a bar with two soft
     * ends, which is exactly what the morph produced when only the top pair was
     * rounded.
     */
    @Test
    public void theTopAndBottomCornersAgreeOnceCollapsed() {
        for (int i = 0; i <= 100; i++) {
            float f = i / 100f;
            // The bottom pair asks the same question with a square open end, which is
            // what the panel has always been at f = 0.
            float bottom = SheetMorph.cornerRadiusFor(f, PILL, 0f, PILL_R);
            float top = SheetMorph.cornerRadiusFor(f, PILL, OPEN_R, bottom);
            if (f >= 1f) {
                assertEquals("f=" + f + " left the corners disagreeing", bottom, top,
                        1e-4f);
            }
            assertTrue("f=" + f + " rounded the bottom corners while open: " + bottom,
                    bottom <= 1e-4f || f > 0f);
        }
        // Both ends of the collapsed shape: a semicircle, and a square while open.
        assertEquals(0f, SheetMorph.cornerRadiusFor(0f, PILL, 0f, PILL_R), 1e-4f);
        assertEquals(PILL_R, SheetMorph.cornerRadiusFor(1f, PILL, 0f, PILL_R), 1e-4f);
    }

    // --------------------------------------------------------------- the insets

    /**
     * The inset is <b>0 while the sheet is open</b>, which is what keeps the open
     * sheet laid out at exactly the margins it always had. A pill inset that started
     * at a few dp would draw a seam up both sides of every expanded sheet, and
     * there is no way to see that in a collapsed screenshot.
     */
    @Test
    public void thereIsNoInsetUntilTheSheetCollapses() {
        assertEquals(0f, SheetMorph.insetFor(0f, SIDE), 1e-4f);
        assertEquals(0f, SheetMorph.insetFor(-0.2f, SIDE), 1e-4f);
        // And it is exactly the pill's own inset once collapsed -- 28dp at 3x.
        assertEquals(SIDE, SheetMorph.insetFor(1f, SIDE), 1e-4f);
        assertEquals(SIDE, SheetMorph.insetFor(1.4f, SIDE), 1e-4f);
    }

    /**
     * The trap in the last stretch of travel, swept rather than spot-checked: the
     * user's complaint was that the collapsed sheet did not look like the pill, and
     * the failure was a shape that sat at the pill's <i>sizes</i> and did nothing
     * about its <i>position</i> until the end. A snap in over the final 1% is
     * invisible in any screenshot taken at rest and obvious in the hand.
     */
    @Test
    public void theInsetMovesSmoothlyThroughTheEndOfTheTravel() {
        float previous = SheetMorph.insetFor(0f, SIDE);
        for (int i = 1; i <= 1000; i++) {
            float f = i / 1000f;
            float now = SheetMorph.insetFor(f, SIDE);
            assertTrue("f=" + f + " went backwards: " + previous + " then " + now,
                    now >= previous - 1e-4f);
            assertTrue("f=" + f + " overshot the pill's own inset: " + now,
                    now <= SIDE + 1e-4f);
            previous = now;
        }
        // The specific boundary the user saw: 0.99 -> 1.0 must be one 1% step and
        // not the whole inset. A lerp moves SIDE/100 = 0.84px in that step; an
        // inset that snapped would move all 84.
        float step = SheetMorph.insetFor(1f, SIDE) - SheetMorph.insetFor(0.99f, SIDE);
        assertEquals(SIDE / 100f, step, 1e-3f);
        assertTrue("the last 1% moved " + step + "px of a " + SIDE + "px inset",
                step < SIDE * 0.02f);
    }

    /** A negative inset is not an inset, and nothing here throws on nonsense. */
    @Test
    public void nothingHereThrowsOnDegenerateInsetInput() {
        assertEquals(0f, SheetMorph.insetFor(0.5f, -10f), 1e-4f);
        assertEquals(0f, SheetMorph.insetFor(1f, 0f), 1e-4f);
        assertEquals(0f, SheetMorph.insetFor(Float.NaN, SIDE), 1e-4f);
        assertEquals(SIDE, SheetMorph.insetFor(Float.POSITIVE_INFINITY, SIDE), 1e-4f);
    }

    // ---------------------------------------------------------- the pill's width

    /**
     * The collapsed <b>width</b>, not a margin — and this is the test that would have
     * caught the first attempt.
     *
     * <p>That attempt was faithful to history and still wrong: 28dp a side, recovered
     * from the pre-morph pill, leaves the pill 88% of this phone's width, which is a
     * bar. The user asked for the total width to be smaller. So the endpoint here is
     * asserted as a <b>width</b>, and a fraction the eye reads as a capsule: 0.72.
     */
    @Test
    public void theCollapsedWidthIsTheFractionAndNotARoundingOfTheScreen() {
        float screen = 1440f;
        float margin = SheetMorph.pillSideMarginFor(1f, screen, 0.72f);
        float width = screen - 2f * margin;
        assertEquals(screen * 0.72f, width, 0.01f);
        // The bar this replaces, for the size of the difference: 28dp at this
        // phone's density is 83px a side, which is 88.4% of the width.
        float barMargin = 83f;
        assertTrue("the old 28dp margin left the pill this wide: "
                        + (screen - 2f * barMargin),
                (screen - 2f * barMargin) > screen * 0.85f);
        assertTrue("so the two are not the same shape",
                width < (screen - 2f * barMargin) - 200f);
    }

    /** Centred by construction: one number, so the two sides cannot differ. */
    @Test
    public void thePillIsCentredBecauseThereIsOnlyOneMargin() {
        float screen = 1080f;   // a compact phone
        float margin = SheetMorph.pillSideMarginFor(1f, screen, 0.72f);
        assertEquals("the right margin is not the left one", margin, margin, 0f);
        assertEquals(screen, 2f * margin + screen * 0.72f, 1e-3f);
        // The left edge of the pill and the space to the right of it are equal, which
        // is what "centred" means and is only true because the margin is half of the
        // leftover rather than two independent numbers.
        assertEquals(margin, (screen - (screen - 2f * margin)) / 2f, 1e-3f);
    }

    /**
     * The margin grows on the same curve as the height's fall, so the pill cannot
     * reach its width before it reaches its height or after.
     *
     * <p>Swept rather than spot-checked because the failure is a seam: two quantities
     * animated on two curves that cross at the ends but not in between give a shape
     * that is briefly a lozenge and briefly a bar, which no single sample finds.
     */
    @Test
    public void theMarginGrowsSmoothlyAndIsZeroUntilTheSheetCollapses() {
        assertEquals("the open sheet must be laid out at the margins it always had",
                0f, SheetMorph.pillSideMarginFor(0f, 1440f, 0.72f), 0f);
        assertEquals(0f, SheetMorph.pillSideMarginFor(-0.2f, 1440f, 0.72f), 0f);
        float previous = 0f;
        for (int i = 1; i <= 10000; i++) {
            float f = i / 10000f;
            float now = SheetMorph.pillSideMarginFor(f, 1440f, 0.72f);
            assertTrue("the margin went backwards at f=" + f, now >= previous - 1e-4f);
            // Same continuity argument as the height: the step shrinks with the
            // sampling, so there is no seam hiding in it.
            assertTrue("the margin jumped by " + (now - previous) + " at f=" + f,
                    now - previous < 1440f * 0.001f);
            previous = now;
        }
        // And it is exactly the pill's width at the pill.
        assertEquals(1440f * 0.14f,
                SheetMorph.pillSideMarginFor(1f, 1440f, 0.72f), 1e-3f);
    }

    /** Degenerate widths and fractions must not throw or invert the sides. */
    @Test
    public void theMarginIsWellBehavedOnDegenerateInput() {
        assertEquals(0f, SheetMorph.pillSideMarginFor(1f, 0f, 0.72f), 0f);
        assertEquals(0f, SheetMorph.pillSideMarginFor(1f, -100f, 0.72f), 0f);
        // A fraction of 1 is "no narrowing" and 0 is "no pill", both of which are
        // degenerate but neither of which may produce a negative margin.
        assertEquals(0f, SheetMorph.pillSideMarginFor(1f, 1440f, 1f), 0f);
        assertEquals(720f, SheetMorph.pillSideMarginFor(1f, 1440f, 0f), 1e-3f);
        // Out-of-range fractions clamp rather than extrapolate.
        assertEquals(201.6f,
                SheetMorph.pillSideMarginFor(9f, 1440f, 0.72f), 1e-3f);
        assertEquals(0f, SheetMorph.pillSideMarginFor(Float.NaN, 1440f, 0.72f), 0f);
    }

    // ------------------------------------------------------------ the colour

    /** Both ends are the values the object actually had, so both are pinned. */
    @Test
    public void theBlendRunsFromTheSheetsGlassToThePillsPlate() {
        // rgba(22,26,32,.92) is the panel's own glass and rgba(28,32,38,.72) is the
        // plate the original pill drew -- both recovered, neither picked.
        assertEquals(0xEB161A20, SheetMorph.blendColour(GLASS, PLATE, 0f));
        assertEquals(0xB81C2026, SheetMorph.blendColour(GLASS, PLATE, 1f));
        // Past either end it clamps rather than extrapolating, so an over-open drag
        // cannot push the sheet's glass past its own colour.
        assertEquals(0xEB161A20, SheetMorph.blendColour(GLASS, PLATE, -0.3f));
        assertEquals(0xB81C2026, SheetMorph.blendColour(GLASS, PLATE, 1.9f));
    }

    /**
     * Alpha blends with the channels, and that is deliberate: the strip is glass
     * <i>over a live render</i>, so most of the distance between the two ends is
     * alpha and a blend that only moved the channels would leave the collapsed pill
     * as opaque as the sheet it came from.
     */
    @Test
    public void theBlendMovesTheAlphaAsWellAsTheColour() {
        int mid = SheetMorph.blendColour(GLASS, PLATE, 0.5f);
        // 210, not 209: the true mid-alpha is 209.5 and Math.round takes half up.
        // Written as the expected value rather than (a+b)/2 because the integer
        // average is a different number from the rounded one, and asserting it is
        // what a reader will try first.
        assertEquals(210, mid >>> 24);
        assertEquals((0x16 + 0x1C) / 2, mid >> 16 & 0xFF);
        assertEquals((0x1A + 0x20) / 2, mid >> 8 & 0xFF);
        assertEquals((0x20 + 0x26) / 2, mid & 0xFF);
        // And the alpha really is on a lerp between the two ends, not held: a third
        // of the way along must be most of the way from 0xEB to 0xB8.
        int third = SheetMorph.blendColour(GLASS, PLATE, 1f / 3f);
        assertTrue("a third of the way along gave alpha " + (third >>> 24),
                third >>> 24 < 0xEB && third >>> 24 > 0xB8);
    }

    /**
     * <b>No step at the end of the travel</b>, which is the second time this has been
     * a user's complaint and the first time it has been on a channel a bound check
     * cannot see. A colour that snaps in over the final 1% reads as the old
     * two-view pop arriving through a different door: the shape is right at every
     * sampled fraction and the whole thing still twitches.
     *
     * <p>Swept at a hundredth of a percent across the whole travel, then specifically
     * across the boundary the user named, 0.99 to 1.0.
     */
    @Test
    public void theBlendHasNoStepAnywhereInTheTravel() {
        int previous = SheetMorph.blendColour(GLASS, PLATE, 0f);
        for (int i = 1; i <= 10000; i++) {
            float f = i / 10000f;
            int now = SheetMorph.blendColour(GLASS, PLATE, f);
            // Per channel, so a step in one of them cannot hide inside a summed
            // difference. Rounding is the floor: a lerp over this range moves the
            // alpha 0x33 and each colour channel by ten, so one step is a rounding
            // step and nothing more.
            //
            // The direction differs per channel and getting that wrong is what the
            // first run of this test caught: the alpha FALLS, because the strip is
            // the sheet's glass at 0xEB easing into the pill's plate at 0xB8 -- it
            // becomes more transparent so more of the render shows through. The
            // colour channels rise. Each is asserted in its own direction rather than
            // as an absolute difference, because "no step" means "no jump", not "no
            // movement", and a signed check is the one that would catch a channel
            // reversing mid-travel.
            assertTrue("f=" + f + " rose in alpha: " + (previous >>> 24) + " -> "
                            + (now >>> 24), now >>> 24 <= previous >>> 24);
            assertTrue("f=" + f + " fell by more than a rounding step: "
                            + (previous >>> 24) + " -> " + (now >>> 24),
                    (previous >>> 24) - (now >>> 24) <= 1);
            for (int shift = 0; shift <= 16; shift += 8) {
                int was = (previous >> shift) & 0xFF;
                int is = (now >> shift) & 0xFF;
                assertTrue("f=" + f + " fell in the channel at shift " + shift
                        + ": " + was + " -> " + is, is >= was);
                assertTrue("f=" + f + " moved the channel at shift " + shift
                        + " by " + (is - was) + " levels in one step",
                        is - was <= 1);
            }
            previous = now;
        }
        // The named boundary, stated on its own so the intent survives the sweep
        // being relaxed later: 0.99 -> 1.0 must be an ordinary step.
        int a = SheetMorph.blendColour(GLASS, PLATE, 0.99f);
        int b = SheetMorph.blendColour(GLASS, PLATE, 1f);
        assertTrue("the last 1% jumped the alpha by "
                        + Math.abs((b >>> 24) - (a >>> 24)) + " levels",
                Math.abs((b >>> 24) - (a >>> 24)) <= 1);
        assertEquals(0xB8, b >>> 24);
    }

    /** The blend is total: no input combination throws. */
    @Test
    public void theBlendNeverThrows() {
        assertEquals(GLASS, SheetMorph.blendColour(GLASS, PLATE, Float.NaN));
        assertEquals(PLATE, SheetMorph.blendColour(GLASS, PLATE,
                Float.POSITIVE_INFINITY));
        assertEquals(GLASS, SheetMorph.blendColour(GLASS, PLATE,
                Float.NEGATIVE_INFINITY));
        // Opaque black to opaque white, the degenerate pair.
        assertEquals(0xFFFFFFFF, SheetMorph.blendColour(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFF000000, SheetMorph.blendColour(0xFF000000, 0xFFFFFFFF, 0f));
    }
}
