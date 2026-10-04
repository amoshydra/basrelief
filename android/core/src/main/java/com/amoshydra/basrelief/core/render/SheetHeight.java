package com.amoshydra.basrelief.core.render;

/**
 * The resting height of a modal bottom sheet, as a function of its content.
 *
 * <p>Pure arithmetic on three integers, deliberately not on a {@code View}: the rule
 * is easy to state, easy to get subtly wrong, and impossible to check on a device
 * without a dialog open and a ruler. {@code AGENTS.md}'s rule is that anything which
 * would otherwise be untestable platform code belongs here as a function of plain
 * values, and this is the exact case — the modal sheet's height was previously a
 * function of scroll position, which is what made it jitter.
 *
 * <p><b>The rule, in one line:</b>
 *
 * <pre>{@code resting = clamp(content, MIN_CONTENT, ceiling) == min(content, ceiling)}</pre>
 *
 * <p>which resolves the four behaviours a modal sheet has to have together:
 *
 * <ul>
 *   <li><b>Short content opens at its own height</b> — a caption-only sheet is a
 *       caption tall, not a third of the screen.</li>
 *   <li><b>Content taller than the sheet opens at the ceiling</b>, and scrolls. The
 *       clamp is what makes the same expression serve both cases.</li>
 *   <li><b>Content that grows makes the sheet grow</b>, up to the ceiling — because
 *       the expression reads {@code content} every time, so a row added or a picker
 *       expanded is picked up with no trigger to register.</li>
 *   <li><b>Content that collapses makes the sheet shrink.</b> The same reason, and
 *       the reason a general mechanism beats a list of callsites: a modal written in
 *       six months gets this for free rather than quietly opening at the wrong
 *       height.</li>
 * </ul>
 *
 * <p><b>What this expression must never take a scroll position as an input.</b> The
 * previous rule was {@code min(content, ceiling, open + scrollY)} and it looped:
 * scrolling changed the viewport height, the height changed the scroll range, the
 * new range moved where the content actually was, and that fed back into the
 * height. It survived review because every <i>resting</i> bound was right, and a
 * settled state is the one thing a screenshot cannot check. Content height in,
 * height out — never scroll in.
 *
 * <p><b>Why a floor exists at all.</b> {@link #MIN_CONTENT_DP} is what stops a sheet
 * whose content has not been measured yet — or measured as nothing — from
 * collapsing to nothing and taking its own scroll range with it. A sheet with no
 * scroll range cannot be dragged open, so the floor is not cosmetic.
 */
public final class SheetHeight {

    /**
     * The shortest a sheet may ever be, in dp.
     *
     * <p>Not a design floor. It exists because a card measured at zero (first
     * layout, content not yet measured) would clamp its own viewport to nothing,
     * and a card with no scroll range cannot be expanded by a drag — so it would
     * stay stuck rather than recover on the next pass.
     */
    public static final int MIN_CONTENT_DP = 120;

    private SheetHeight() {
    }

    /**
     * The height the sheet rests at, given its content.
     *
     * @param contentPx  the content's own natural height, measured with an
     *                   {@code UNSPECIFIED} height spec so it cannot be influenced
     *                   by the viewport the card happens to be at this pass. Using a
     *                   bounded spec here is the loop: a shorter viewport would
     *                   report a shorter "content", which would shorten the viewport.
     * @param ceilingPx  the most the sheet may ever be, from the screen fraction less
     *                   the insets and the chrome
     * @param minPx      {@link #MIN_CONTENT_DP} in pixels
     * @return {@code min(content, ceiling)}, never below {@code minPx}
     */
    public static int resting(int contentPx, int ceilingPx, int minPx) {
        int top = contentPx < ceilingPx ? contentPx : ceilingPx;
        if (top < minPx) return minPx;
        return top;
    }

    /**
     * The tallest the scrolling part of the sheet may be: its content, capped.
     *
     * <p>Identical to {@link #resting}. It is a separate name because the modal sheet
     * uses the number in two places with different intents — as the resting height and
     * as the ceiling the scroll clamps to — and giving them one name would invite
     * exactly the substitution this class exists to prevent.
     */
    public static int maximum(int contentPx, int ceilingPx, int minPx) {
        return resting(contentPx, ceilingPx, minPx);
    }
}