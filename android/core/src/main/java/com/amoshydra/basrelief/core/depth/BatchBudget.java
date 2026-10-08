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

    /**
     * The largest chunk, in frames, that this project has evidence for: 240 at
     * 720p is 6.1 GiB by the fit and is the number {@code BatchBudgetTest}
     * records as never having OOMed.
     *
     * <p><b>A chunk is a prompt, so the ceiling is time and blast radius, not
     * memory.</b> The budget below says a 720p batch of ~440 frames would fit,
     * and nothing measured supports that shape; a chunk is one prompt whose
     * result is fetched whole, so a 7-minute chunk also means 7 minutes with no
     * progress line and a whole chunk to redo if the server is interrupted.
     * 240 keeps every chunk at or below a measured-successful batch.
     */
    public static final int MAX_CHUNK_FRAMES = 240;

    /**
     * The fraction of {@link #BUDGET_MB} one chunk may use.
     *
     * <p>The budget is what a single batch may ask for, and it stands at 15 GiB
     * with the class doc's own reasons; a chunk is a whole job, so it also needs
     * the model, the CUDA context and the fragmentation room that doc describes
     * beyond the batch. 0.75 is the deliberate margin — a 1080p chunk lands at
     * ~11.5 GiB instead of touching the line — and it is one number because the
     * difference between a chunk that fits and one that OOMs is exactly the
     * thing no screenshot can check.
     */
    public static final double CHUNK_HEADROOM = 0.75;

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

    // ------------------------------------------------------------- the time

    /**
     * Measured server cost per frame: a 120-frame import took about 90s end to
     * end, so ~0.75s of server time per frame at 720p-class input. An ESTIMATE
     * shown as one ("about ..."), never a promise, and the one constant to
     * revise when the pipeline or the host changes.
     *
     * <p>Here rather than on the trim sheet because TWO surfaces state it: the
     * trim sheet before a run ("about 40s of server time") and the job line while
     * one runs ("about 4 min" remaining). Two copies of the constant is how the
     * two numbers come to disagree about the same server.
     */
    public static final float SECONDS_PER_FRAME = 0.75f;

    /**
     * The server-time line where the framing flips to a Job. About the SHAPE of
     * the wait rather than about cost: under half a minute a sheet can honestly
     * say "about 40s"; past it, the honest thing is that the user can leave and
     * the work continues.
     */
    public static final float JOB_SECONDS = 30f;

    /**
     * How much server time {@code frames} are expected to take, in seconds.
     *
     * <p>0 for a frame count nothing has read, so a caller can tell "no work left
     * to estimate" from "an instant".
     */
    public static float estimateSeconds(int frames) {
        if (frames <= 0) return 0f;
        return frames * SECONDS_PER_FRAME;
    }

    // ------------------------------------------------------------ chunked runs

    /**
     * The largest number of frames one prompt may hold at {@code height}.
     *
     * <p><b>The rule, stated once:</b> the largest batch the budget allows with
     * {@link #CHUNK_HEADROOM} to spare, capped at {@link #MAX_CHUNK_FRAMES}. The
     * budget is what makes a chunk fit at all; the cap is what keeps a run of
     * cheap frames (a 480p source can afford over a thousand per batch by the
     * fit) from becoming one enormous prompt. The two bounds are the two failure
     * modes named on the constant: too large OOMs, and a chunk is a prompt, so
     * one that spans too many frames costs the user progress and the project a
     * batch it may have to run twice.
     *
     * <p>Returns {@code 0} for an unknown height, like {@link #fits}: a window
     * claims nothing, so it cannot be answered with a chunk size.
     */
    public static int chunkFrames(int height) {
        if (height <= 0) return 0;
        int byBudget = (int) Math.floor(BUDGET_MB * CHUNK_HEADROOM / perFrameMb(height));
        return Math.min(MAX_CHUNK_FRAMES, byBudget);
    }

    /**
     * How many prompts a range of {@code frames} costs at {@code height}, or 0
     * when the range cannot be chunked at all (an unknown height).
     */
    public static int chunkCount(int frames, int height) {
        int n = chunkFrames(height);
        if (n <= 0 || frames <= 0) return 0;
        return (frames + n - 1) / n;
    }

    /**
     * The chunk boundaries of a range, in order: each row is
     * {@code {startFrame, frameCount}} and the rows tile {@code [0, frames)}
     * exactly — no gap, no overlap, the last one taking the remainder.
     *
     * <p><b>In frames, never in times.</b> The loader's {@code skip_first_frames}
     * and {@code frame_load_cap} count decoded frames, so the plan is what makes
     * the concatenation line up: chunk k continues where chunk k-1 stopped by
     * construction, and no second division of the same range can disagree with
     * this one.
     *
     * <p>Empty when {@code frames <= 0} (a length nothing has read yet) or the
     * height is unknown — the caller then has one prompt over whatever it can
     * read, which is the pre-chunking behaviour rather than a guess.
     */
    public static java.util.List<int[]> chunkPlan(int frames, int height) {
        java.util.List<int[]> plan = new java.util.ArrayList<int[]>();
        int n = chunkFrames(height);
        if (n <= 0 || frames <= 0) return plan;
        for (int start = 0; start < frames; start += n) {
            plan.add(new int[] {start, Math.min(n, frames - start)});
        }
        return plan;
    }
}
