package com.amoshydra.basrelief.core.depth;

/**
 * How many frames a container holds: the exact sample count when it can be read,
 * a duration-derived estimate when it cannot.
 *
 * <p><b>The estimate is here because it is wrong by one on a file this app wrote
 * itself, and that is a measured fact.</b> A 240-frame 24fps clip remuxed by the
 * app reports a container duration of 9.9586s — the MP4 muxer's duration is
 * derived from the last sample it processed, and that sample is not always the
 * highest presentation time. {@code round(9.9586 × 24)} is <b>239</b>. Planning a
 * chunked import from that number asks the server for 239 frames, so the joined
 * depth clip decodes 239 against the colour clip's 240: one frame short, at the
 * end, with no error anywhere — and one frame of misalignment at every loop.
 *
 * <p>So the exact count ({@code MediaExtractor}'s sample count, one sample per
 * frame) wins whenever it is known, and the duration arithmetic is only the
 * fallback for a container whose sample table cannot be walked. {@link #resolve}
 * is the one place that choice is made, and {@code FrameCountTest} holds the
 * measured 240-vs-239 case so the estimate cannot quietly become the planner
 * again.
 */
public final class FrameCount {

    private FrameCount() {
    }

    /**
     * The frame count a container duration implies, rounded to the nearest frame.
     *
     * <p>An estimate, not a count: see the class note for the file where it is one
     * short. Returns 0 for a duration or rate that cannot support an answer — the
     * "a window claims nothing" rule, applied to a number.
     */
    public static int estimate(long durationUs, float fps) {
        if (durationUs <= 0 || fps <= 0) return 0;
        return Math.round(durationUs / 1_000_000f * fps);
    }

    /**
     * The frame count to plan with: the exact sample count when it was read, the
     * duration estimate otherwise.
     *
     * @param exactSamples the container's video sample count, or &lt;= 0 when the
     *                     sample table could not be read
     * @param durationUs   the container duration, for the fallback
     * @param fps          the rate the duration is expressed in
     * @return a count {@code >= 0}; 0 means neither source could answer
     */
    public static int resolve(int exactSamples, long durationUs, float fps) {
        if (exactSamples > 0) return exactSamples;
        return estimate(durationUs, fps);
    }
}
