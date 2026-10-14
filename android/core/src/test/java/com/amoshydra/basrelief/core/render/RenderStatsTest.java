package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two lines the studio's performance readout prints, and above all what a
 * MISSING number prints.
 *
 * <p><b>The bug this class exists for is a zero that looks measured.</b> The relief
 * has no GPU timer and no sort; a splat window in which the camera did not move runs
 * no sort. The readout is an instrument the user reads to judge cost, so a
 * {@code sort 0.0ms} it did not measure is worse than a blank field: it claims the
 * sort is free. The rule is the codebase's own — a window claims nothing — so a field
 * with no value is OMITTED, and the sentinels are a NaN and a negative count because
 * zero is a real count (an empty cloud) and a real time.
 *
 * <p>The second theme is the single derivation. The {@code splat:} log prints fps and
 * frameMs from the same window the readout reads; the arithmetic that turns a frame
 * count and an elapsed time into fps lives here, in one function, so the log and the
 * readout cannot report two frame rates for one window. The numbers in these tests are
 * the ones a Pixel 7 Pro's own log produced.
 */
public class RenderStatsTest {

    // ----------------------------------------------------------- the arithmetic

    @Test
    public void fpsIsFramesOverTheWindow() {
        // 60 frames in one second is 60 fps, and the same rate over half a second is
        // the same number: the window is the unit, not the sample count.
        assertEquals(60f, RenderStats.fps(60, 1_000_000_000L), 1e-3f);
        assertEquals(60f, RenderStats.fps(30, 500_000_000L), 1e-3f);
        // The measured device rate: 741k splats drew at roughly 18 fps.
        assertEquals(18f, RenderStats.fps(18, 1_000_000_000L), 1e-3f);
    }

    /**
     * An empty window is not 0 fps.
     *
     * <p>0 fps is a real frame rate (a surface that draws nothing); no frames counted
     * is the absence of a measurement, and the readout blanks it rather than claiming
     * the renderer has stopped.
     */
    @Test
    public void anEmptyWindowHasNoFps() {
        assertTrue(Float.isNaN(RenderStats.fps(0, 1_000_000_000L)));
        assertTrue(Float.isNaN(RenderStats.fps(10, 0L)));
        assertTrue(Float.isNaN(RenderStats.fps(-1, 1_000_000_000L)));
    }

    @Test
    public void frameMsIsTheInverseOfFps() {
        assertEquals(16.667f, RenderStats.frameMs(60f), 1e-3f);
        assertEquals(100f, RenderStats.frameMs(10f), 1e-3f);
        // The splat's measured 18 fps is a 55.6 ms frame.
        assertEquals(55.556f, RenderStats.frameMs(RenderStats.fps(18, 1_000_000_000L)), 1e-3f);
        assertTrue(Float.isNaN(RenderStats.frameMs(RenderStats.NONE)));
        assertTrue(Float.isNaN(RenderStats.frameMs(0f)));
    }

    @Test
    public void meanMsIsAbsentWhenNothingWasMeasured() {
        assertEquals(2f, RenderStats.meanMs(6_000_000L, 3), 1e-3f);
        // THE POINT: no samples is NONE, not 0. A no-sort window has no sort time.
        assertTrue(Float.isNaN(RenderStats.meanMs(0L, 0)));
        assertTrue(Float.isNaN(RenderStats.meanMs(999L, 0)));
    }

    @Test
    public void worstOfPicksThePresentLargerAndTreatsAbsentAsAbsent() {
        assertEquals(12f, RenderStats.worstOf(12f, 8f), 0f);
        assertEquals(12f, RenderStats.worstOf(8f, 12f), 0f);
        assertEquals(12f, RenderStats.worstOf(RenderStats.NONE, 12f), 0f);
        assertEquals(12f, RenderStats.worstOf(12f, RenderStats.NONE), 0f);
        // Both absent is absent -- a photograph has no skew at all.
        assertTrue(Float.isNaN(RenderStats.worstOf(RenderStats.NONE, RenderStats.NONE)));
    }

    // --------------------------------------------------------------- headline

    @Test
    public void theHeadlineIsOneDecimalAndBlanksAnAbsentFps() {
        StringBuilder b = new StringBuilder();
        new RenderStats(58.3f, 17.2f, RenderStats.NONE, RenderStats.NONE,
                RenderStats.NONE, RenderStats.NO_COUNT, 0, 0).headline(b);
        assertEquals("58.3 fps", b.toString());

        b.setLength(0);
        new RenderStats(RenderStats.NONE, RenderStats.NONE, RenderStats.NONE,
                RenderStats.NONE, RenderStats.NONE, RenderStats.NO_COUNT, 0, 0).headline(b);
        // An em dash, the app's own "nothing here", not "0.0 fps".
        assertEquals("\u2014 fps", b.toString());
    }

    // ---------------------------------------------------------------- detail

    /**
     * A relief has only a frame interval, and that is all it prints.
     *
     * <p>The relief draws no sort and has no GPU timer. If the readout printed the
     * splat's fields as zero the user would read a free sort and a free GPU on a
     * backend that cannot measure either.
     */
    @Test
    public void aReliefPrintsOnlyTheFrameInterval() {
        StringBuilder b = new StringBuilder();
        new RenderStats(60f, 16.7f, RenderStats.NONE, RenderStats.NONE,
                RenderStats.NONE, RenderStats.NO_COUNT, 0, 0).detail(b);
        assertEquals("frame 16.7ms", b.toString());
        assertFalse(b.toString().contains("gpu"));
        assertFalse(b.toString().contains("sort"));
        assertFalse(b.toString().contains("0"));
    }

    /**
     * A relief that is playing a clip adds the worse track's skew, and nothing else.
     */
    @Test
    public void aReliefWithAClipAddsItsSkew() {
        StringBuilder b = new StringBuilder();
        new RenderStats(60f, 16.7f, RenderStats.NONE, RenderStats.NONE,
                12f, RenderStats.NO_COUNT, 0, 0).detail(b);
        assertEquals("frame 16.7ms \u00b7 skew 12ms", b.toString());
    }

    /**
     * A splat prints the whole line, and the count and size are what make the times
     * mean anything: a 55 ms frame at 741k splats on a 720x1560 target is a fill
     * number, and the same 55 ms at 10k is something else entirely.
     */
    @Test
    public void aSplatPrintsTheWholeLine() {
        StringBuilder b = new StringBuilder();
        new RenderStats(18f, 55.6f, 40f, 1.1f, RenderStats.NONE,
                741_000, 720, 1560).detail(b);
        assertEquals("frame 55.6ms \u00b7 gpu 40.0ms \u00b7 sort 1.1ms \u00b7 741k splats \u00b7 720\u00d71560",
                b.toString());
    }

    /**
     * A no-sort window omits the sort, which is the one case the splat can hit every
     * second: a still phone runs no sort, and {@code sort 0.0ms} would read as free.
     */
    @Test
    public void aSplatWindowWithNoSortOmitsTheSort() {
        StringBuilder b = new StringBuilder();
        new RenderStats(60f, 16.7f, 5.4f, RenderStats.NONE, RenderStats.NONE,
                150_000, 912, 1984).detail(b);
        assertEquals("frame 16.7ms \u00b7 gpu 5.4ms \u00b7 150k splats \u00b7 912\u00d71984",
                b.toString());
        assertFalse(b.toString().contains("sort"));
    }

    @Test
    public void theCountIsCompactedSoTheSizeStaysOnTheLine() {
        StringBuilder b = new StringBuilder();
        new RenderStats(18f, 55.6f, 40f, RenderStats.NONE, RenderStats.NONE,
                150_000, 912, 1984).detail(b);
        assertTrue(b.toString().contains("150k splats"));

        b.setLength(0);
        new RenderStats(13f, 77f, 60f, RenderStats.NONE, RenderStats.NONE,
                1_050_000, 1440, 3120).detail(b);
        assertTrue(b.toString().contains("1.1M splats"));
        // A cloud small enough to be counted exactly still reads as a number.
        b.setLength(0);
        new RenderStats(60f, 16.7f, 5f, RenderStats.NONE, RenderStats.NONE,
                800, 720, 1560).detail(b);
        assertTrue(b.toString().contains("800 splats"));
    }

    /**
     * An empty cloud is a real count, so it prints as {@code 0 splats} and not as an
     * omitted field. The distinction is the whole sentinel rule: only {@link
     * RenderStats#NO_COUNT} means "no cloud", and {@code 0} is a cloud with nothing in
     * it.
     */
    @Test
    public void anEmptyCloudIsARealZeroCount() {
        StringBuilder b = new StringBuilder();
        new RenderStats(60f, 16.7f, 5f, RenderStats.NONE, RenderStats.NONE,
                0, 720, 1560).detail(b);
        assertEquals("frame 16.7ms \u00b7 gpu 5.0ms \u00b7 0 splats \u00b7 720\u00d71560",
                b.toString());
    }

    /**
     * The window the two backends report over is one constant, so the log and the
     * readout cannot drift to two averages.
     */
    @Test
    public void bothBackendsShareOneWindow() {
        assertEquals(1_000_000_000L, RenderStats.WINDOW_NANOS);
    }
}
