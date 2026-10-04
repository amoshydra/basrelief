package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One rule, four behaviours: {@code resting = min(content, ceiling)}, floored.
 *
 * <p>The four tests below are the four things a modal sheet has to do, and they are
 * separate tests rather than one table because they fail for different reasons: the
 * first two are the expression itself, the last two are what proves it is re-evaluated
 * rather than sampled once at open.
 */
public class SheetHeightTest {

    /** 1080x2400 at density 3: a tall screen, so the ceiling is not the binding term. */
    private static final int CEILING = 1600;
    private static final int MIN = SheetHeight.MIN_CONTENT_DP * 3;

    @Test
    public void shortContentOpensAtItsOwnHeight() {
        // A caption-only sheet is a caption tall. Before this rule the opening height
        // was a third of the screen regardless, so every sheet was the same size.
        assertEquals(400, SheetHeight.resting(400, CEILING, MIN));
    }

    @Test
    public void contentTallerThanTheSheetOpensAtTheCeilingAndScrolls() {
        // The same expression serves the tall case: capped, not truncated, so the
        // content scrolls inside it.
        assertEquals(CEILING, SheetHeight.resting(4000, CEILING, MIN));
    }

    @Test
    public void contentThatGrowsMakesTheSheetGrow() {
        // A row added, a picker expanded, a preset list populated after a fetch.
        int before = SheetHeight.resting(500, CEILING, MIN);
        int after = SheetHeight.resting(900, CEILING, MIN);
        assertEquals(500, before);
        assertEquals(900, after);
        assertTrue("growing content must not be clamped at the old height",
                after > before);
    }

    @Test
    public void contentThatCollapsesMakesTheSheetShrink() {
        // The same reason as growth, and the reason a general mechanism beats a list
        // of callsites: the expression reads content every time, so a modal written
        // in six months gets this rather than quietly opening at the wrong height.
        // Both ends are clear of the floor, so this tests SHRINKING rather than the
        // floor winning -- a separate test below covers collapsing below it.
        int before = SheetHeight.resting(1200, CEILING, MIN);
        int after = SheetHeight.resting(500, CEILING, MIN);
        assertEquals(1200, before);
        assertEquals(500, after);
        assertTrue("collapsing content must not keep the old height", after < before);
    }

    @Test
    public void growthStopsAtTheCeiling() {
        // Content BELOW the ceiling is its own height; content AT or ABOVE it is the
        // ceiling. The join is where the two cases meet, and both sides are asserted
        // because a clamp written as < instead of <= would pass one and fail the other.
        assertEquals(CEILING - 1, SheetHeight.resting(CEILING - 1, CEILING, MIN));
        assertEquals(CEILING, SheetHeight.resting(CEILING, CEILING, MIN));
        assertEquals(CEILING, SheetHeight.resting(CEILING + 1, CEILING, MIN));
        assertEquals(CEILING, SheetHeight.resting(99999, CEILING, MIN));
    }

    @Test
    public void anUnmeasuredSheetDoesNotCollapseToNothing() {
        // First layout measures nothing. A card clamped to zero has no scroll range,
        // and a card with no scroll range cannot be dragged open -- so it would stay
        // stuck rather than recover on the next pass. This is the floor's whole job.
        assertEquals(MIN, SheetHeight.resting(0, CEILING, MIN));
        assertEquals(MIN, SheetHeight.resting(-1, CEILING, MIN));
    }

    @Test
    public void aScreenSoShortTheCeilingIsBelowTheFloorPrefersTheFloor() {
        // Same reasoning as the branch above it in the sheet: a sheet with no content
        // in it is worse than one that overflows its fraction.
        int tinyCeiling = 100;
        assertEquals(MIN, SheetHeight.resting(500, tinyCeiling, MIN));
    }

    /**
     * The floor is below a normal sheet's resting height, so it never actually fires
     * for real content -- which is what makes it safe to have at all. 400dp of caption
     * at density 3 is 1200px, comfortably clear of a 360px floor.
     */
    @Test
    public void theFloorIsOutOfTheWayForRealContent() {
        int caption = 1200;
        assertTrue("a real caption must clear the floor by a wide margin",
                caption > MIN * 2);
        assertEquals(caption, SheetHeight.resting(caption, CEILING, MIN));
    }

    /** The two names are the same rule; they exist so a substitution reads as one. */
    @Test
    public void maximumIsTheSameRuleAsResting() {
        for (int content = 0; content <= 2000; content += 37) {
            for (int ceiling = 100; ceiling <= 1800; ceiling += 211) {
                assertEquals(SheetHeight.resting(content, ceiling, MIN),
                        SheetHeight.maximum(content, ceiling, MIN));
            }
        }
    }
}