package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the batch-memory guard.
 *
 * <p>The numbers in here are the measured OOMs, not invented: 600 frames at 720p
 * allocated 15.63 GiB before dying, 240 at 1080p requested 5.56 GiB more on top
 * of 13.46 and died, and 600 at 1080p allocated 17.10 before dying. The guard
 * exists so the trim sheet cannot submit any of those; a test that only checked
 * the formula would keep passing while the budget let them through.
 */
public class BatchBudgetTest {

    /**
     * The formula's own arithmetic at the two heights the fit is calibrated from.
     * 1080p is 1.5× the height and 2.54× the cost — superlinear, which is the
     * whole reason the estimate is not a linear frame cap.
     */
    @Test
    public void theFormulaIsCalibratedAt720pAndSuperlinearAboveIt() {
        assertEquals(26.0, BatchBudget.perFrameMb(720), 1e-9);
        assertEquals(66.0668, BatchBudget.perFrameMb(1080), 1e-3);
        assertTrue("1080p must cost more per pixel than 720p",
                BatchBudget.perFrameMb(1080) / BatchBudget.perFrameMb(720) > 1.5);
    }

    /**
     * The three measured OOMs must all be refused. This is the test the guard
     * exists for; if the budget is ever raised "to allow a longer clip", these
     * fail rather than the server.
     */
    @Test
    public void theThreeMeasuredOomsDoNotFit() {
        assertFalse("600 @ 1080p OOMed at 17.10 GiB allocated",
                BatchBudget.fits(600, 1080));
        assertFalse("600 @ 720p OOMed at 15.63 GiB allocated",
                BatchBudget.fits(600, 720));
        assertFalse("240 @ 1080p OOMed requesting 5.56 GiB more",
                BatchBudget.fits(240, 1080));
    }

    /**
     * The other half: the guard must not refuse everything. 240 frames at 720p
     * is ~6.1 GiB by the same fit and has never OOMed, and 200 at 1080p
     * (~12.9 GiB) is the shape that sat just inside the old failures.
     */
    @Test
    public void batchesWellInsideTheBudgetStillFit() {
        assertTrue(BatchBudget.fits(240, 720));
        assertTrue(BatchBudget.fits(200, 1080));
        assertTrue(BatchBudget.fits(40, 2160));
    }

    /**
     * The auto-pick lands on the tallest resolution that fits, and returns -1
     * when nothing does — the sheet's "this range cannot be generated".
     */
    @Test
    public void theAutoPickTakesTheTallestResolutionThatFits() {
        // 240 @ 1080p is over; 720p fits, so the pick is the 720p chip.
        assertEquals(720, BatchBudget.pick(240, 1080, 1080));
        // 600 @ 1080p fits nowhere on the ladder — 720p is over too.
        assertEquals(-1, BatchBudget.pick(600, 1080, 1080));
        // A 4K source steps all the way down rather than staying at Original.
        assertEquals(720, BatchBudget.pick(240, 2160, 0));
        // A batch that already fits at the chosen chip keeps the user's choice,
        // even though a taller one (1080p) would also fit.
        assertEquals(720, BatchBudget.pick(60, 1080, 720));
        assertEquals(0, BatchBudget.pick(100, 1080, 0));
    }

    /**
     * <b>The auto-pick never upscales.</b> A 480p source whose range is over
     * budget must refuse rather than pick the 720p or 1080p chip: those are
     * upscales, so they cost MORE memory than the source and add no detail.
     */
    @Test
    public void theAutoPickNeverUpscalesAboveTheSource() {
        assertEquals(-1, BatchBudget.pick(2000, 480, 0));
        // A user's explicit upscale is still respected while it fits: the guard
        // replaces an impossible choice, it does not overrule a possible one.
        assertEquals(1080, BatchBudget.pick(100, 480, 1080));
    }

    /**
     * An unknown source height claims nothing: {@code heightOf} passes it through
     * and {@code fits} refuses it rather than treating "unknown" as "free". This
     * is the {@code UNKNOWN} rule from {@code DepthSource}/{@code VideoSource}
     * applied to a number — a window must not be answered with a yes.
     */
    @Test
    public void anUnknownHeightNeverFitsAndNeverGetsAPick() {
        assertEquals(0, BatchBudget.heightOf(0, 0));
        assertEquals(1920, BatchBudget.heightOf(0, 1920));
        assertFalse(BatchBudget.fits(10, 0));
        assertFalse(BatchBudget.fits(10, -1));
        assertEquals(-1, BatchBudget.pick(10, 0, 0));
        // A chosen chip that names a real height is used as itself, not replaced
        // by the unknown source.
        assertEquals(1080, BatchBudget.heightOf(1080, 0));
    }

    /**
     * The budget is a hard line, and it is the 15 GiB the class doc justifies:
     * the brief's 16 would let the measured 600@720p batch (15.23 GiB) through.
     */
    @Test
    public void theBudgetLineIsTheOneThatRejectsTheMeasured720pBatch() {
        assertEquals(15 * 1024, BatchBudget.BUDGET_MB, 1e-9);
        assertTrue(BatchBudget.estimateMb(600, 720) > BatchBudget.BUDGET_MB);
        assertTrue(BatchBudget.estimateMb(240, 1080) > BatchBudget.BUDGET_MB);
    }

    /**
     * The chunk rule: the largest batch the budget allows with headroom, capped at
     * a measured-successful batch. 1080p is budget-bound (174 frames, ~11.5 GiB --
     * the line itself allows 232, and a chunk is a whole job, so the margin is the
     * point); 720p is cap-bound at 240 (~6.1 GiB), because the fit would allow ~443
     * and nothing measured supports a batch that size.
     */
    @Test
    public void chunksAreSizedFromTheBudgetWithHeadroomAndACap() {
        assertEquals(240, BatchBudget.chunkFrames(720));
        assertEquals(174, BatchBudget.chunkFrames(1080));
        assertTrue("a chunk must never exceed the measured-safe batch",
                BatchBudget.chunkFrames(480) <= BatchBudget.MAX_CHUNK_FRAMES);
        // Every height the budget can answer for stays inside the headroom line and
        // inside the absolute line: the chunk is what a prompt will ask the card for.
        for (int h : new int[] {480, 720, 1080, 2160}) {
            int n = BatchBudget.chunkFrames(h);
            assertTrue("chunk at " + h + "p must be positive", n > 0);
            assertTrue("chunk at " + h + "p must leave the headroom",
                    BatchBudget.estimateMb(n, h)
                            <= BatchBudget.BUDGET_MB * BatchBudget.CHUNK_HEADROOM + 1e-9);
            assertTrue("chunk at " + h + "p must fit the absolute line",
                    BatchBudget.fits(n, h));
        }
    }

    /**
     * The plan tiles the range exactly: no gap, no overlap, the last chunk takes the
     * remainder. A plan that skipped or repeated a frame would make the concatenated
     * clip play wrongly without failing anywhere, which is why this is arithmetic
     * worth a test rather than a loop worth trusting.
     */
    @Test
    public void thePlanTilesTheRangeExactly() {
        java.util.List<int[]> plan = BatchBudget.chunkPlan(601, 720);
        assertEquals(3, plan.size());
        assertEquals(3, BatchBudget.chunkCount(601, 720));
        assertEquals(0, plan.get(0)[0]);
        assertEquals(240, plan.get(0)[1]);
        assertEquals(240, plan.get(1)[0]);
        assertEquals(240, plan.get(1)[1]);
        assertEquals(480, plan.get(2)[0]);
        assertEquals(121, plan.get(2)[1]);

        int covered = 0;
        int next = 0;
        for (int[] c : plan) {
            assertEquals("each chunk starts where the last stopped", next, c[0]);
            next = c[0] + c[1];
            covered += c[1];
        }
        assertEquals("every frame is covered exactly once", 601, covered);
        // A range no larger than one chunk is a one-prompt plan, not no plan.
        assertEquals(1, BatchBudget.chunkPlan(120, 720).size());
        assertEquals(1, BatchBudget.chunkCount(120, 720));
    }

    /**
     * An unknown height cannot be chunked -- the window-claims-nothing rule applied
     * to a plan -- and a length nothing has read yet (0) yields no plan either.
     */
    @Test
    public void anUnknownHeightOrLengthHasNoChunkPlan() {
        assertEquals(0, BatchBudget.chunkFrames(0));
        assertTrue(BatchBudget.chunkPlan(100, 0).isEmpty());
        assertTrue(BatchBudget.chunkPlan(0, 720).isEmpty());
        assertEquals(0, BatchBudget.chunkCount(100, 0));
        assertEquals(0, BatchBudget.chunkCount(0, 720));
    }
}
