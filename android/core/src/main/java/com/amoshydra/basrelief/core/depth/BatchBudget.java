package com.amoshydra.basrelief.core.depth;

/**
 * What the depth server can hold for one video batch, and which resolution fits.
 *
 * <p><b>Why this exists.</b> The video graph runs ONE MoGe inference over the whole
 * batch — that is what makes a clip import four lines of wiring instead of a
 * per-frame pipeline — so the frames are not streamed and the GPU holds every one
 * of them, plus MoGe's own float tensors, at once. Measured on the user's server
 * (RTX 4090, 23.59 GiB), the same graph with three ranges:
 *
 * <ul>
 *   <li>600 frames @ 1080p — allocated 17.10 GiB, OOM.</li>
 *   <li>600 frames @ 720p — allocated 15.63 GiB, OOM.</li>
 *   <li>240 frames @ 1080p — allocated 13.46 GiB, requested 5.56 more, OOM.</li>
 * </ul>
 *
 * <p>So the cost is ~26 MB per frame at 720p and ~80 MB per frame at 1080p —
 * superlinear in height (2.25× the pixels costs ~3×), because MoGe's internal
 * working resolution follows the input and the loader's {@code custom_height}
 * scales the whole batch at once. <b>FRAMES are the term that dominates, not the
 * model.</b>
 *
 * <p><b>The estimate is an empirical fit, not physics.</b> It is here in {@code
 * :core} because it is pure arithmetic a test can drive, and because a guard that
 * let a measured-OOM batch through is exactly the kind of mistake this project
 * cannot catch by looking at the sheet.
 *
 * <p><b>The budget is 15 GiB, not the 16 the brief suggested.</b> At 26 MB/frame
 * the formula lands the measured 600-frame 720p batch at 15.23 GiB — inside 16 —
 * and it OOMed with 15.63 GiB allocated. 15 GiB is the largest round line that
 * rejects all three measured batches while still allowing 240 frames at 720p
 * (6.1 GiB). The card's own 23.59 GiB leaves the model, the CUDA context and
 * fragmentation their room; this number is what the BATCH may ask for.
 */
public final class BatchBudget {

    /** Measured: 600 frames at 720p allocated 15.63 GiB before it died. */
    public static final double MB_PER_FRAME_AT_720 = 26.0;

    /**
     * Superlinear in height: 2.25× the pixels costs ~3×. The exponent is fitted
     * from the two measured points (26 MB/frame at 720p, ~80 at 1080p), not
     * derived — MoGe holds a different number of float tensors as the working
     * resolution grows.
     */
    public static final double HEIGHT_EXPONENT = 2.3;

    /**
     * The line the estimate must stay under, in MB.
     *
     * <p>See the class doc: the brief said ~16 GiB, and the arithmetic says that
     * line lets the measured 600@720p OOM through. 15 GiB rejects all three
     * measured batches.
     */
    public static final double BUDGET_MB = 15 * 1024.0;

    /**
     * The resolution chips, in the order the sheet draws them. {@code 0} means
     * "the source's own height" — the value the loader takes as "no scaling".
     */
    public static final int[] LADDER = {0, 1080, 720};

    private BatchBudget() {
    }

    /**
     * The measured cost of one frame at {@code height}, in MB.
     *
     * <p>A height of 0 or less has no cost to report — the caller has no source
     * height — and {@link #fits} refuses it rather than treating it as free.
     */
    public static double perFrameMb(int height) {
        if (height <= 0) return 0;
        return MB_PER_FRAME_AT_720 * Math.pow(height / 720.0, HEIGHT_EXPONENT);
    }

    /** The whole batch's estimated cost, in MB. */
    public static double estimateMb(int frames, int height) {
        if (frames <= 0) return 0;
        return frames * perFrameMb(height);
    }

    /**
     * Whether a batch of {@code frames} at {@code height} can be submitted.
     *
     * <p>An unknown height (0) never fits: a guard that answered "fits" for a
     * batch whose size it could not compute would be the state field that nothing
     * reads, one layer up.
     */
    public static boolean fits(int frames, int height) {
        return height > 0 && frames > 0 && estimateMb(frames, height) <= BUDGET_MB;
    }

    /** The pixel height a chip means: the source's own for {@code 0}. */
    public static int heightOf(int chip, int sourceHeight) {
        return chip <= 0 ? sourceHeight : chip;
    }

    /**
     * The chip to select for a batch of {@code frames}.
     *
     * <p>The chosen one when it fits — a user's smaller pick is respected even
     * when a taller one would fit — otherwise the TALLEST of {@link #LADDER} that
     * does, so the range lands on the best quality that can actually run. An
     * upscale above the source is never auto-picked: it costs more memory and
     * adds no detail. Returns {@code -1} when even the smallest offerable height
     * does not fit, which is the sheet's "this range cannot be generated".
     */
    public static int pick(int frames, int sourceHeight, int chosen) {
        if (fits(frames, heightOf(chosen, sourceHeight))) return chosen;
        int best = -1;
        int bestHeight = -1;
        for (int chip : LADDER) {
            int h = heightOf(chip, sourceHeight);
            if (h <= 0 || h > sourceHeight) continue;
            if (fits(frames, h) && h > bestHeight) {
                best = chip;
                bestHeight = h;
            }
        }
        return best;
    }
}
