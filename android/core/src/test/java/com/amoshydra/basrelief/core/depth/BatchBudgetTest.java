package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the window rule and the time estimate.
 *
 * <p>The numbers in here are the measured run, not invented: 480 frames of
 * 1920x1080@60 through the streaming node held 11.5-12.3 GiB with a window of 4 and
 * finished in 340 s, where the old whole-batch graph climbed to 15.48 GiB and
 * requested 4.03 GiB more at frame 174 on the server's 16 GiB cap. The rule exists
 * so a 4K source cannot be submitted at a window that costs four times the measured
 * peak; a test that only checked the formula would keep passing while the rule let
 * it through.
 */
public class BatchBudgetTest {

    /**
     * The anchor: the exact resolution the measurement was taken at gets the exact
     * window it was measured with.
     */
    @Test
    public void theMeasuredAnchorGetsItsMeasuredWindow() {
        assertEquals(4, BatchBudget.streamWindow(1920, 1080));
    }

    /**
     * <b>4K needs a smaller window, and this is the test the rule exists for.</b>
     * Four times the anchor's pixels at the same window would be four times its
     * activation footprint; window 1 makes the product equal to the measured one,
     * which is the whole extrapolation the rule makes.
     */
    @Test
    public void fourKStepsDownToTheSmallestWindow() {
        assertEquals(1, BatchBudget.streamWindow(3840, 2160));
    }

    /** A middle resolution lands between the two ends rather than at either. */
    @Test
    public void aMiddleResolutionGetsAMiddleWindow() {
        assertEquals(2, BatchBudget.streamWindow(2560, 1440));
    }

    /**
     * The floor and the cap: nothing returns less than one frame per inference, and
     * a small source cannot buy a window larger than the one that was measured.
     */
    @Test
    public void theWindowIsClampedToOneAndFour() {
        assertEquals("a 480p source is capped at the measured window, not the fit",
                4, BatchBudget.streamWindow(854, 480));
        assertEquals(4, BatchBudget.streamWindow(1280, 720));
        assertEquals(1, BatchBudget.streamWindow(7680, 4320));
    }

    /**
     * The invariant the safety rests on: no (pixels, window) pair the rule returns
     * asks for more than the measured anchor product, so no run it plans can cost
     * more than the 12.3 GiB that was measured to fit under the server's 16 GiB cap.
     * A sweep rather than a spot check, because the arithmetic has a clamp at each
     * end and the interesting failures live near them.
     */
    @Test
    public void noReturnedWindowExceedsTheMeasuredProduct() {
        long anchor = (long) BatchBudget.REFERENCE_WIDTH
                * BatchBudget.REFERENCE_HEIGHT * BatchBudget.REFERENCE_WINDOW;
        int[] widths = {640, 854, 1280, 1920, 2560, 3840, 4096, 7680};
        int[] heights = {360, 480, 720, 1080, 1440, 2160, 4320};
        for (int w : widths) {
            for (int h : heights) {
                long pixels = (long) w * h;
                int window = BatchBudget.streamWindow(w, h);
                assertTrue("window at " + w + "x" + h + " must be at least 1", window >= 1);
                assertTrue("window at " + w + "x" + h + " must be at most the measured",
                        window <= BatchBudget.MAX_WINDOW);
                if (pixels <= anchor) {
                    assertTrue("the product at " + w + "x" + h + " (window " + window
                                    + ") must not exceed the measured anchor",
                            pixels * window <= anchor);
                } else {
                    // A source larger than the anchor product cannot be made safe by
                    // any window; the smallest window is the answer, and a 6K/8K run
                    // then fails with the server's own memory message rather than
                    // with a rule that pretends to have solved it.
                    assertEquals("an oversized source gets exactly the smallest window",
                            BatchBudget.MIN_WINDOW, window);
                }
            }
        }
    }

    /**
     * An unknown dimension claims nothing: it gets the smallest window rather than a
     * guessed larger one. This is the {@code DepthSource}/{@code VideoSource} rule
     * applied to a number -- a window that cannot compute its cost must not be
     * answered with a window that assumes a size.
     */
    @Test
    public void anUnknownDimensionGetsTheSmallestWindow() {
        assertEquals(1, BatchBudget.streamWindow(0, 1080));
        assertEquals(1, BatchBudget.streamWindow(1920, 0));
        assertEquals(1, BatchBudget.streamWindow(0, 0));
        assertEquals(1, BatchBudget.streamWindow(-1920, -1080));
    }

    /**
     * The time estimate: the one constant the trim sheet and the running job line
     * both state, and 0 for a count nothing has read -- so a caller can tell "no
     * work to estimate" from "an instant".
     */
    @Test
    public void theTimeEstimateIsTheOneConstantBothSurfacesState() {
        assertEquals(0.75f, BatchBudget.SECONDS_PER_FRAME, 1e-9);
        assertEquals(30f, BatchBudget.JOB_SECONDS, 1e-9);
        assertEquals(90f, BatchBudget.estimateSeconds(120), 1e-9);
        assertEquals(0f, BatchBudget.estimateSeconds(0), 1e-9);
        assertEquals(0f, BatchBudget.estimateSeconds(-5), 1e-9);
    }

    /**
     * The chip means a SHORT-SIDE cap, and the portrait case is the one that proves
     * it: measured on a 1440x2560 phone recording, the old height-only reading turned
     * "1080" into a 608-wide clip -- a quarter of the pixels the user asked for.
     * Landscape is unchanged, which is why the bug could live this long, and a source
     * smaller than the chip is never upscaled.
     */
    @Test
    public void aChipCapsTheShortSide() {
        // Landscape 1080p: the chip is a no-op, exactly as before.
        assertEquals(1080, BatchBudget.targetHeightFor(1080, 1920, 1080));
        // Portrait 1440x2560: "1080" means 1080 on the WIDTH -> 1080x1920.
        assertEquals(1920, BatchBudget.targetHeightFor(1080, 1440, 2560));
        // Portrait 1080x1920 at the 720 chip -> 720x1280.
        assertEquals(1280, BatchBudget.targetHeightFor(720, 1080, 1920));
        // Square: either side is the short side.
        assertEquals(720, BatchBudget.targetHeightFor(720, 1080, 1080));
        // Never an upscale: a 640x360 source at the 1080 chip keeps its height.
        assertEquals(360, BatchBudget.targetHeightFor(1080, 640, 360));
        // Chip 0 is the source's own size, and an unread shape claims nothing.
        assertEquals(0, BatchBudget.targetHeightFor(0, 1440, 2560));
        assertEquals(0, BatchBudget.targetHeightFor(1080, 0, 0));
    }
}
