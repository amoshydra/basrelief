package com.amoshydra.basrelief.core.depth;

/**
 * The two numbers a video run states about the server: how long it will take,
 * and how large a window of frames one inference may hold.
 *
 * <p><b>The window is what replaced the chunk plan.</b> The old video graph held a
 * whole range as one IMAGE batch and the app sliced the range into prompts that
 * fit — measured, 600 frames at 1080p died with 17.10 GiB allocated on a 23.59 GiB
 * card and 600 at 720p with 15.63 GiB. The streaming node takes the video PATH and
 * owns the loop, so length stops mattering and the only memory decision left is the
 * size of one inference's window: peak = the model plus one window. 480 frames at
 * 1920x1080 consumed 11.5-12.3 GiB flat for the whole run with window 4, and that
 * peak is dominated by MoGe-3 ViT-G ACTIVATIONS, not by the decoded frames. Four
 * times the pixels the anchor was measured at (4K) therefore needs a quarter of the
 * window to keep the same footprint.
 *
 * <p><b>The rule is arithmetic on pixels, not a table of resolutions.</b> The
 * measured anchor is a product — 1920x1080 with window 4 sits at 12.3 GiB — and the
 * activation cost scales with pixels x window, so any (width, height, window) with
 * the same product as the anchor costs the same window-worth of memory. The rule
 * returns the largest window in 1..4 whose product stays at or under the anchor:
 * 1080p gets 4, 1440p gets 2, 4K gets 1, 720p is capped at 4 because the node's
 * {@code batch_size} input itself is 1..16 and four is the largest measured.
 *
 * <p><b>The budget is the measured peak, not the cap, and that is deliberate.</b>
 * The anchor is 12.3 GiB against the server's 16 GiB PyTorch line: 3.7 GiB of the
 * line is left for the model, the CUDA context and fragmentation, which is the
 * headroom the old 15 GiB batch budget was already reaching into when it died.
 * Because the rule never exceeds the anchor product, no window it returns asks for
 * more than that measured 12.3 GiB — a 4K window of 1 costs what a 1080p window of
 * 4 did, which is exactly the measurement this rule extrapolates from.
 *
 * <p><b>{@code width} and {@code height} are the dimensions the NODE will load</b>
 * — the source scaled by the resolution chip — not the source's own size. The
 * caller computes them because the chip is its choice; this class only owns the
 * arithmetic.
 */
public final class BatchBudget {

    /** The measured anchor: 1920x1080. */
    public static final int REFERENCE_WIDTH = 1920;
    /** The measured anchor: 1920x1080. */
    public static final int REFERENCE_HEIGHT = 1080;
    /**
     * The window the anchor was measured with: 480 frames of 1920x1080@60 held
     * 11.5-12.3 GiB flat and finished in 340 s. The old graph on the same clip
     * climbed to 15.48 GiB and requested 4.03 GiB more at frame 174, on the
     * server's 16 GiB cap.
     */
    public static final int REFERENCE_WINDOW = 4;
    /** The node's own {@code batch_size} ceiling that this project has evidence for. */
    public static final int MAX_WINDOW = 4;
    /** The smallest window there is: below one frame per inference, nothing runs. */
    public static final int MIN_WINDOW = 1;

    /**
     * The window of frames one inference may hold, at the dimensions the server
     * will load.
     *
     * <p>Returns {@link #MIN_WINDOW} for an unknown dimension: a window claims
     * nothing, so an unread size must not be answered with a larger one. That is
     * the {@code DepthSource}/{@code VideoSource} rule applied to a number — the
     * caller value-loads the clip, so the only way here is a container that would
     * not say, and one frame per inference is the answer that cannot be wrong about
     * memory.
     */
    public static int streamWindow(int width, int height) {
        if (width <= 0 || height <= 0) return MIN_WINDOW;
        // The anchor as a product of pixels and frames. long because 8K x 16
        // overflows an int's worth of carelessness, not because either reaches it.
        long anchor = (long) REFERENCE_WIDTH * REFERENCE_HEIGHT * REFERENCE_WINDOW;
        long pixels = (long) width * height;
        long window = anchor / pixels;
        if (window < MIN_WINDOW) window = MIN_WINDOW;
        if (window > MAX_WINDOW) window = MAX_WINDOW;
        return (int) window;
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

    private BatchBudget() {
    }
}
