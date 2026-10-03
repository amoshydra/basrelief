package com.amoshydra.basrelief.core.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The release rule, pinned case by case.
 *
 * <p>Each test is one of the user's sentences about how the sheet read their
 * intent, expressed as numbers: 98% and reversing must not collapse, down–up–down
 * must, a flick decides by itself, and a release that means nothing leaves the
 * sheet alone. The numbers are fractions of travel, never pixels, so these tests
 * say nothing about any screen.
 */
public class SheetIntentTest {

    private static final float EPS = 1e-4f;

    /**
     * The dead band is the whole point of having two thresholds: a release near
     * the middle changes nothing, from either side.
     *
     * <p>If this ever becomes one threshold, this test fails — which is what it
     * is for, because a single boundary flips state on jitter and the user's
     * "down, up, down" would collapse on the tiniest drift.
     */
    @Test
    public void aStillReleaseInsideTheDeadBandChangesNothing() {
        for (float position : new float[]{0.40f, 0.45f, 0.50f, 0.55f, 0.60f}) {
            assertEquals("a release at " + position + " with no speed is not decisive",
                    SheetIntent.Decision.STAY, SheetIntent.decide(position, 0f));
        }
    }

    /**
     * The thresholds are exclusive on the staying side and inclusive past it.
     *
     * <p>0.60 itself projects to 0.60, which is not "above 0.60", so it stays;
     * 0.61 collapses. Pinning the exact edge stops a refactor from quietly
     * turning {@code >} into {@code >=} and moving the feel by a whole percent.
     */
    @Test
    public void theThresholdsHaveExactlyOneEdge() {
        assertEquals(SheetIntent.Decision.STAY, SheetIntent.decide(0.60f, 0f));
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.61f, 0f));
        assertEquals(SheetIntent.Decision.STAY, SheetIntent.decide(0.40f, 0f));
        assertEquals(SheetIntent.Decision.EXPAND, SheetIntent.decide(0.39f, 0f));
    }

    /**
     * A slow drag in one direction is projected forward before the decision, so a
     * release just short of a threshold still commits.
     *
     * <p>This is the momentum the hand expects to see: the sheet keeps going the
     * way it was moving for a moment after the finger leaves. Without it a drag
     * would have to be dragged all the way to its target and stopped.
     */
    @Test
    public void aSlowDragIsProjectedForwardBeforeTheDecision() {
        // 0.55 + 0.5 travels/s * 0.2s = 0.65, past the collapse threshold.
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.55f, 0.5f));
        // 0.45 - 0.5 * 0.2 = 0.35, past the expand threshold.
        assertEquals(SheetIntent.Decision.EXPAND, SheetIntent.decide(0.45f, -0.5f));
    }

    /**
     * Down, up, down again: the final direction is the only one that exists at
     * release.
     *
     * <p>The same release position decides differently depending on which way the
     * finger was finally moving, which is exactly the user's complaint — the
     * gesture must not remember the deepest point it ever reached.
     */
    @Test
    public void theLastDirectionIsTheOnlyOneThatOrders() {
        // Ended moving down: the projection carries it past the collapse line.
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.62f, 0.3f));
        // Same position, but ended moving up: the projection stays in the band.
        assertEquals(SheetIntent.Decision.STAY, SheetIntent.decide(0.62f, -0.3f));
    }

    /**
     * The user's first case: dragged deep, brought back upward, released while
     * still moving up.
     *
     * <p>The upward release subtracts from the position, so the release lands on
     * the collapse edge and stays — the direction at release is what saved it.
     * From the very bottom the position alone cannot come back before the throw
     * is over, and the fling guard is the throw that does.
     */
    @Test
    public void aReverseFromDeepInsideTheTravelDoesNotCollapse() {
        // 0.70 - 0.5 * 0.2 = 0.60: exactly on the collapse edge, so it stays.
        assertEquals(SheetIntent.Decision.STAY, SheetIntent.decide(0.70f, -0.5f));
        // 98%, but thrown upward hard: a flick, decided by speed alone.
        assertEquals(SheetIntent.Decision.EXPAND, SheetIntent.decide(0.98f, -1.2f));
    }

    /**
     * The honest boundary of the rule: a reversal too slow to change the
     * projection still collapses from the bottom.
     *
     * <p>This is deliberate, not a missed case. A release at 98% moving up at
     * half a travel per second lands at 88% — the user let go while the sheet was
     * still essentially away, and collapsing is the defensible reading. The test
     * pins the reading rather than leaving it to a later accident.
     */
    @Test
    public void aReversalWithoutEnoughSpeedLeavesTheProjectionInCharge() {
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.98f, -0.5f));
    }

    /**
     * A decisive flick decides by itself, from anywhere, which is the one thing a
     * pure position rule can never read.
     */
    @Test
    public void aDecisiveFlickDecidesWithoutTravellingTheDistance() {
        // Almost fully open and thrown down: collapse.
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.10f, 1.2f));
        // Almost away and thrown up: expand.
        assertEquals(SheetIntent.Decision.EXPAND, SheetIntent.decide(0.90f, -1.2f));
    }

    /**
     * The fling guard is inclusive at its own number: exactly 1.2 travels per
     * second is a flick. Off by a hair it is not, and the projection decides —
     * which at 0.10 is no decision to collapse at all.
     */
    @Test
    public void theFlingEdgeIsInclusive() {
        assertEquals(SheetIntent.Decision.COLLAPSE, SheetIntent.decide(0.10f, 1.2f));
        assertEquals(SheetIntent.Decision.EXPAND, SheetIntent.decide(0.10f, 1.19f));
    }

    /**
     * The projection is clamped to the travel range, so a throw from either end
     * cannot produce a landing place outside it.
     */
    @Test
    public void theProjectionIsClampedToTheTravel() {
        assertTrue(SheetIntent.projected(0.95f, 1.0f) <= 1f);
        assertEquals(1f, SheetIntent.projected(0.95f, 1.0f), EPS);
        assertEquals(0f, SheetIntent.projected(0.05f, -1.0f), EPS);
    }
}
