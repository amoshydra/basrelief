package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Which frame count an import is planned from, and the measured file where the
 * two answers differ.
 *
 * <p>The trap this exists to pin: a frame count derived from a container's
 * DURATION is not the frame count. Measured on the app's own remux of a 240-frame
 * 24fps range, the container duration is <b>9.9586s</b> — the MP4 muxer takes the
 * duration from the last sample it processed, which is not always the highest
 * presentation time — and {@code round(9.9586 × 24) = 239}. An import planned from
 * 239 asked the server for one frame fewer than the colour clip held, so the
 * joined depth clip decoded 239 against 240: one frame short, at the end, with no
 * error anywhere and a hitch at every loop.
 */
public class FrameCountTest {

    /** The measured remux: 240 samples, 9.9586s, 24fps. */
    private static final long MEASURED_DURATION_US = 9_958_600L;
    private static final float MEASURED_FPS = 24f;
    private static final int MEASURED_SAMPLES = 240;

    /**
     * The exact sample count wins, because the duration estimate on this very
     * file is one short — and that one frame was a real import's depth clip.
     */
    @Test
    public void theExactCountWinsOnTheMeasuredFile() {
        assertEquals("the duration estimate undercounts the file the app wrote",
                239, FrameCount.estimate(MEASURED_DURATION_US, MEASURED_FPS));
        assertEquals("the sample count is the count that matches the colour clip",
                MEASURED_SAMPLES,
                FrameCount.resolve(MEASURED_SAMPLES, MEASURED_DURATION_US, MEASURED_FPS));
    }

    /**
     * With no sample count, the estimate is still the fallback — a container
     * whose sample table cannot be walked is not a reason to refuse a clip.
     */
    @Test
    public void theEstimateIsUsedOnlyWhenTheExactCountIsMissing() {
        assertEquals(239, FrameCount.resolve(0, MEASURED_DURATION_US, MEASURED_FPS));
        assertEquals(239, FrameCount.resolve(-1, MEASURED_DURATION_US, MEASURED_FPS));
    }

    /**
     * Nothing to read is 0, never a guess: the "a window claims nothing" rule,
     * applied to a number. An unknown count is what makes the caller fall back to
     * one uncapped prompt rather than plan a range it cannot measure.
     */
    @Test
    public void anUnreadableContainerIsZero() {
        assertEquals(0, FrameCount.estimate(0, 24f));
        assertEquals(0, FrameCount.estimate(-5, 24f));
        assertEquals(0, FrameCount.estimate(MEASURED_DURATION_US, 0f));
        assertEquals(0, FrameCount.estimate(MEASURED_DURATION_US, -24f));
        assertEquals(0, FrameCount.resolve(0, 0, 0));
    }

    /**
     * The estimate is a round, not a floor: a duration that sits past the
     * midpoint of a frame is that frame's count, so a container whose duration
     * legitimately includes the last frame's hold is not truncated.
     */
    @Test
    public void theEstimateRoundsToTheNearestFrame() {
        assertEquals("10.0s at 24fps is 240 frames",
                240, FrameCount.estimate(10_000_000L, 24f));
        assertEquals("one frame's worth rounds to one",
                1, FrameCount.estimate(41_000L, 24f));
    }
}
