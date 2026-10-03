package com.amoshydra.basrelief.core.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The release rule, pinned case by case, under both policies.
 *
 * <p>Each test is one of the user's sentences about how the sheet read their
 * intent, expressed as numbers: 98% and reversing must not collapse, down–up–down
 * must, a flick decides by itself, and a release that means nothing leaves the
 * sheet alone. Then the studio's own correction: it stays free almost to the very
 * bottom and collapses only at 0.95 projected or on a downward flick, while the
 * same rule with the dialog's numbers collapses at 0.60. The numbers are
 * fractions of travel, never pixels, so these tests say nothing about any screen.
 */
public class SheetIntentTest {

    private static final float EPS = 1e-4f;

    private static SheetIntent.Decision dialog(float position, float velocity) {
        return SheetIntent.decide(position, velocity, SheetIntent.DIALOG);
    }

    private static SheetIntent.Decision sheet(float position, float velocity) {
        return SheetIntent.decide(position, velocity, SheetIntent.SHEET);
    }

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
            assertEquals("a dialog release at " + position + " with no speed is not decisive",
                    SheetIntent.Decision.STAY, dialog(position, 0f));
        }
    }

    /**
     * A policy's thresholds are exclusive on the staying side and inclusive past
     * it. 0.60 itself projects to 0.60, which is not "above 0.60", so it stays;
     * 0.61 collapses. Pinning the exact edge stops a refactor from quietly
     * turning {@code >} into {@code >=} and moving the feel by a whole percent.
     */
    @Test
    public void theThresholdsHaveExactlyOneEdge() {
        assertEquals(SheetIntent.Decision.STAY, dialog(0.60f, 0f));
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.61f, 0f));
        assertEquals(SheetIntent.Decision.STAY, dialog(0.40f, 0f));
        assertEquals(SheetIntent.Decision.EXPAND, dialog(0.39f, 0f));
    }

    /**
     * The studio's correction, pinned: "always allow free position even at the
     * lower end of the screen, collapse it only when it is almost nearing the
     * end". Against the same rule with {@link SheetIntent#SHEET}'s numbers, 0.94
     * projected stays and 0.96 collapses, so the free range covers the whole
     * screen except the last twentieth.
     */
    @Test
    public void theStudioStaysFreeAlmostToTheVeryBottom() {
        for (float position : new float[]{0.60f, 0.70f, 0.80f, 0.90f, 0.94f, 0.95f}) {
            assertEquals("a free studio release at " + position + " keeps the position",
                    SheetIntent.Decision.STAY, sheet(position, 0f));
        }
        assertEquals("past 0.95 projected it commits",
                SheetIntent.Decision.COLLAPSE, sheet(0.96f, 0f));
    }

    /**
     * The same release means different things to the two hosts — which is the
     * reason the thresholds became a policy. At 70% a dialog is already gone; the
     * studio is still exactly where it was let go.
     */
    @Test
    public void theSameReleaseMeansDifferentThingsUnderEachPolicy() {
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.70f, 0f));
        assertEquals(SheetIntent.Decision.STAY, sheet(0.70f, 0f));

        // 0.90 released upward at half a travel per second projects to 0.80:
        // collapsing for a card, still resting for the panel.
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.90f, -0.5f));
        assertEquals(SheetIntent.Decision.STAY, sheet(0.90f, -0.5f));
    }

    /**
     * The fling override is shared and unchanged by the correction: a decisive
     * downward throw closes the studio sheet from anywhere, even though its
     * position line is now 0.95.
     */
    @Test
    public void theStudioFlingOverrideIsUnchanged() {
        assertEquals(SheetIntent.Decision.COLLAPSE, sheet(0.10f, 1.2f));
        assertEquals(SheetIntent.Decision.COLLAPSE, sheet(0.50f, 1.5f));
        assertEquals(SheetIntent.Decision.EXPAND, sheet(0.98f, -1.2f));
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
        // 0.55 + 0.5 travels/s * 0.2s = 0.65, past the dialog collapse threshold.
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.55f, 0.5f));
        // 0.45 - 0.5 * 0.2 = 0.35, past the expand threshold.
        assertEquals(SheetIntent.Decision.EXPAND, dialog(0.45f, -0.5f));
        // The same slow push on the studio: 0.94 + 0.3 * 0.2 = 1.0, so a drag
        // that was still moving down at 94% is finished by its own momentum.
        assertEquals(SheetIntent.Decision.COLLAPSE, sheet(0.94f, 0.3f));
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
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.62f, 0.3f));
        // Same position, but ended moving up: the projection stays in the band.
        assertEquals(SheetIntent.Decision.STAY, dialog(0.62f, -0.3f));
    }

    /**
     * The user's first case: dragged deep, brought back upward, released while
     * still moving up.
     *
     * <p>For a dialog the upward release subtracts from the position, so the
     * release lands on the collapse edge and stays; from the very bottom the
     * position alone cannot come back before the throw is over, and the fling
     * guard is the throw that does.
     */
    @Test
    public void aReverseFromDeepInsideTheTravelDoesNotCollapse() {
        // 0.70 - 0.5 * 0.2 = 0.60: exactly on the collapse edge, so it stays.
        assertEquals(SheetIntent.Decision.STAY, dialog(0.70f, -0.5f));
        // 98%, but thrown upward hard: a flick, decided by speed alone.
        assertEquals(SheetIntent.Decision.EXPAND, dialog(0.98f, -1.2f));
    }

    /**
     * The honest boundary of the rule: a reversal too slow to change the
     * projection still collapses a dialog from the bottom.
     *
     * <p>This is deliberate, not a missed case. A release at 98% moving up at
     * half a travel per second lands at 88% — the user let go while the card was
     * still essentially away, and collapsing is the defensible reading. The
     * studio's deeper line is what makes the same release rest there instead; see
     * {@link #theSameReleaseMeansDifferentThingsUnderEachPolicy}.
     */
    @Test
    public void aReversalWithoutEnoughSpeedLeavesTheProjectionInCharge() {
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.98f, -0.5f));
    }

    /**
     * A decisive flick decides by itself, from anywhere, which is the one thing a
     * pure position rule can never read.
     */
    @Test
    public void aDecisiveFlickDecidesWithoutTravellingTheDistance() {
        // Almost fully open and thrown down: collapse.
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.10f, 1.2f));
        // Almost away and thrown up: expand.
        assertEquals(SheetIntent.Decision.EXPAND, dialog(0.90f, -1.2f));
    }

    /**
     * The fling guard is inclusive at its own number: exactly 1.2 travels per
     * second is a flick. Off by a hair it is not, and the projection decides —
     * which at 0.10 is no decision to collapse at all.
     */
    @Test
    public void theFlingEdgeIsInclusive() {
        assertEquals(SheetIntent.Decision.COLLAPSE, dialog(0.10f, 1.2f));
        assertEquals(SheetIntent.Decision.EXPAND, dialog(0.10f, 1.19f));
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
