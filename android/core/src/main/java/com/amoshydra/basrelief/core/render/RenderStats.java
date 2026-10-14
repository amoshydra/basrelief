package com.amoshydra.basrelief.core.render;

/**
 * One stats window's numbers, and the two lines the studio's performance readout
 * prints from them.
 *
 * <p><b>Why this is a class in {@code :core}.</b> The studio draws an fps and a
 * frame-cost readout over the render, behind a Settings switch, and the numbers are
 * the SAME ones the {@code splat:} log line prints. Two derivations of one number
 * agree until the day one of them is edited, which is exactly the failure
 * {@link com.amoshydra.basrelief.core.store.FramingRowValue} records for the framing
 * rows one screen over. So each renderer computes a window ONCE, its log prints that
 * window and this snapshot carries it to the readout, and the arithmetic that decides
 * "what fps is this" and "what does a missing number print" lives here where it can
 * be checked without a device.
 *
 * <p><b>A window claims nothing.</b> The relief has no GPU timer and no sort; the
 * splat has no clip skew. A backend that lacks a number reports {@link #NONE}, and
 * {@link #detail} OMITS that field — it never prints {@code 0}, because
 * {@code 0 ms of sort} and {@code no sort measured} are different facts, and this
 * codebase's rule is that an unknown is not a negative. Zero is a real count (an
 * empty cloud) and a real time, so the sentinels are a NaN and a negative count,
 * not zero.
 */
public final class RenderStats {

    /** An absent float measurement. Never printed as a value. */
    public static final float NONE = Float.NaN;

    /** An absent count. Zero is a real count, so the sentinel is negative. */
    public static final int NO_COUNT = -1;

    /**
     * The window both the per-second log and the readout report over.
     *
     * <p>Shared so the two cannot drift: a log that averaged a second while the
     * readout averaged a quarter would print two frame rates for one window, which is
     * the "two computations" this class exists to prevent. The readout's TEXT updates
     * faster than this (see {@code PerfOverlay}); the NUMBERS change once per window,
     * because that is the window the log prints.
     */
    public static final long WINDOW_NANOS = 1_000_000_000L;

    public final float fps;
    public final float frameMs;
    /** GPU time, or {@link #NONE} where the backend has no timer. */
    public final float gpuMs;
    /** CPU sort time, or {@link #NONE} where the backend does not sort. */
    public final float sortMs;
    /** A clip track's skew against the colour, or {@link #NONE} for a photograph. */
    public final float skewMs;
    /** The splat count, or {@link #NO_COUNT} where there is no cloud. */
    public final int count;
    /** The render target's size, or 0 where it is the surface itself. */
    public final int renderW;
    public final int renderH;

    public RenderStats(float fps, float frameMs, float gpuMs, float sortMs,
                       float skewMs, int count, int renderW, int renderH) {
        this.fps = fps;
        this.frameMs = frameMs;
        this.gpuMs = gpuMs;
        this.sortMs = sortMs;
        this.skewMs = skewMs;
        this.count = count;
        this.renderW = renderW;
        this.renderH = renderH;
    }

    // --------------------------------------------------------- the arithmetic

    /**
     * Frames per second over a window, or {@link #NONE} when the window is empty.
     *
     * <p>The renderers pass the frame count and the elapsed nanos they already keep;
     * this is the ONE fps in the app, so the log and the readout cannot disagree.
     */
    public static float fps(int frames, long windowNanos) {
        if (frames <= 0 || windowNanos <= 0L) return NONE;
        return frames * 1e9f / windowNanos;
    }

    /** The mean frame interval an fps implies, or {@link #NONE} for no fps. */
    public static float frameMs(float fps) {
        if (Float.isNaN(fps) || fps <= 0f) return NONE;
        return 1000f / fps;
    }

    /**
     * The mean of a nanos total over its sample count, or {@link #NONE} for none.
     *
     * <p>{@code count <= 0} is the absent case, and it is deliberately not
     * {@code 0 ms}: a window in which no sort ran has no sort time to report, and
     * printing zero would claim a sort that measured as free.
     */
    public static float meanMs(long nanos, int count) {
        if (count <= 0) return NONE;
        return nanos / 1e6f / count;
    }

    /**
     * The worse of two measurements, treating {@link #NONE} as absent.
     *
     * <p>For the relief's two clip tracks: the readout shows the one furthest behind,
     * because that is the number that says the picture is drifting. Both absent is
     * absent.
     */
    public static float worstOf(float a, float b) {
        boolean na = Float.isNaN(a);
        boolean nb = Float.isNaN(b);
        if (na && nb) return NONE;
        if (na) return b;
        if (nb) return a;
        return Math.max(a, b);
    }

    // ---------------------------------------------------------- the two lines

    /** The headline: "58.3 fps", or "— fps" when no window has been measured. */
    public void headline(StringBuilder out) {
        if (Float.isNaN(fps)) {
            out.append('\u2014');
        } else {
            appendFixed(out, fps, 1);
        }
        out.append(" fps");
    }

    /**
     * The supporting line, present fields only, joined by a middle dot.
     *
     * <p>Cost first — the frame interval, the GPU time, the CPU sort — then the clip
     * skew, then the two facts that give the times their meaning: the splat count and
     * the render target's size. A field with no value is omitted entirely, so a relief
     * reads "frame 16.7ms" and a splat reads the full line, and neither shows a zero
     * it did not measure.
     */
    public void detail(StringBuilder out) {
        int start = out.length();
        field(out, start, "frame", frameMs, 1);
        field(out, start, "gpu", gpuMs, 1);
        field(out, start, "sort", sortMs, 1);
        field(out, start, "skew", skewMs, 0);
        if (count != NO_COUNT) {
            separator(out, start);
            appendCount(out, count);
            out.append(" splats");
        }
        if (renderW > 0 && renderH > 0) {
            separator(out, start);
            out.append(renderW).append('\u00d7').append(renderH);
        }
    }

    private static void field(StringBuilder out, int start, String name,
                              float value, int decimals) {
        if (Float.isNaN(value)) return;
        separator(out, start);
        out.append(name).append(' ');
        appendFixed(out, value, decimals);
        out.append("ms");
    }

    private static void separator(StringBuilder out, int start) {
        if (out.length() > start) out.append(" \u00b7 ");
    }

    /**
     * A fixed number of decimals, half-up, with no {@code String.format} and no
     * locale: the readout is rebuilt a few times a second on the UI thread, and a
     * formatter that allocates — or that a locale can render as "58,3" — would both
     * cost and drift. The values are non-negative, which is the only domain these
     * fields have.
     */
    private static void appendFixed(StringBuilder out, float v, int decimals) {
        int scale = decimals > 0 ? 10 : 1;
        int scaled = Math.round(v * scale);
        out.append(scaled / scale);
        if (decimals > 0) {
            out.append('.');
            out.append(scaled % scale);
        }
    }

    /**
     * A count as "741k" or "1.1M", so a million-splat cloud does not push the render
     * size off the line. Only the display rounds; the value itself is exact.
     */
    private static void appendCount(StringBuilder out, int n) {
        if (n >= 1_000_000) {
            appendFixed(out, n / 1_000_000f, 1);
            out.append('M');
        } else if (n >= 1_000) {
            out.append(Math.round(n / 1_000f));
            out.append('k');
        } else {
            out.append(n);
        }
    }
}
