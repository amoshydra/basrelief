package com.amoshydra.basrelief.core.gesture;

/**
 * What letting go of a bottom-sheet drag means, as one pure function.
 *
 * <p><b>Why this is a class and not four {@code if}s in a touch listener.</b>
 * The user's report was that the sheet read intent wrong: dragging it all the way
 * down and then changing direction upward should keep it open, and dragging down,
 * up, and down again should close it. The rule that answers both is not a position
 * threshold — a sheet dragged to 98% has crossed every position threshold there
 * is, and the only thing that says the user changed their mind is the
 * <i>direction at release</i>. The rule is therefore a projection of the fling,
 * with two thresholds around it, and it belongs where it can be tested without a
 * device: :core. It uses no Android type, so it is plain JVM code with a JUnit
 * test pinning each of the user's cases.
 *
 * <p><b>Units.</b> Both arguments are fractions of the full travel — position 0
 * is fully open, 1 is fully collapsed, so the constant means the same thing on
 * every screen and for every host. Velocity is the same fraction per second. The
 * studio sheet's travel is the panel's natural height; a dialog card's is its
 * own. Nothing here ever sees a pixel, which is what makes the rule
 * screen-independent by construction.
 *
 * <p><b>The dead band is the hysteresis.</b> {@link #COLLAPSE_THRESHOLD} and
 * {@link #EXPAND_THRESHOLD} are deliberately not the same number. A release that
 * projects into the band means the gesture was not decisive, and the honest
 * answer is to change nothing: a single 0.5 boundary would flip state on a
 * release a hair either side of it, and a jitter at the boundary would keep
 * flipping it.
 *
 * <p><b>The projection is what makes the last direction the only one that
 * matters.</b> A drag down, up and down again ends moving down, so its projected
 * landing place is further down than where the finger let go — the earlier
 * excursion does not exist at release. The reverse is how a 98% drag that is
 * brought back up survives: the upward release velocity subtracts from the
 * position, and enough of it lands the projection below the expand threshold. A
 * flick past {@link #FLING_TRAVEL_PER_SECOND} skips the arithmetic entirely — a
 * decisive throw from anywhere is a decision by itself, which is the one gesture
 * a pure position rule could never read.
 */
public final class SheetIntent {

    /**
     * A projected landing place above this collapses the sheet. 0.60 is past the
     * halfway point, so a release here has already given up most of the travel and
     * a collapse is the small motion; below it the sheet would have to reverse.
     */
    public static final float COLLAPSE_THRESHOLD = 0.60f;

    /**
     * A projected landing place below this expands the sheet. The gap to
     * {@link #COLLAPSE_THRESHOLD} is the dead band: a release inside it leaves the
     * sheet where it is rather than guessing which way the user meant.
     */
    public static final float EXPAND_THRESHOLD = 0.40f;

    /**
     * How far ahead, in seconds, the release is projected at its measured speed.
     * This is the momentum a hand expects to see carried; the task's own number,
     * and the same 0.20s the dialog's old dismiss rule used. Too small and a flick
     * has to be aimed, too large and a slow drag overshoots its target.
     */
    public static final float PROJECTION_SECONDS = 0.20f;

    /**
     * A release at least this fast, in full travels per second, decides by itself:
     * down is a collapse, up is an expand, whatever the position. 1.2 is about a
     * decisive wrist flick; a deliberate drag is well under it, and
     * {@link #PROJECTION_SECONDS} catches the middle. The two guards are
     * complementary on purpose — the projection reads the gesture, the fling reads
     * the throw.
     */
    public static final float FLING_TRAVEL_PER_SECOND = 1.2f;

    /** What the release committed, if anything. */
    public enum Decision {
        /** Animate to fully collapsed. */
        COLLAPSE,
        /** Animate to fully open. */
        EXPAND,
        /** Not decisive: leave the sheet where the release left it. */
        STAY
    }

    private SheetIntent() {
    }

    /**
     * Decide a release.
     *
     * @param position the collapse fraction at release: 0 open, 1 collapsed
     * @param velocityPerSecond the finger's speed in full travels per second;
     *                          positive is downward, toward collapse
     * @return what the caller should settle to; {@link Decision#STAY} means the
     *         release was inside the dead band and nothing about the position should
     *         change
     */
    public static Decision decide(float position, float velocityPerSecond) {
        if (velocityPerSecond >= FLING_TRAVEL_PER_SECOND) return Decision.COLLAPSE;
        if (velocityPerSecond <= -FLING_TRAVEL_PER_SECOND) return Decision.EXPAND;
        float projected = projected(position, velocityPerSecond);
        if (projected > COLLAPSE_THRESHOLD) return Decision.COLLAPSE;
        if (projected < EXPAND_THRESHOLD) return Decision.EXPAND;
        return Decision.STAY;
    }

    /**
     * Where the fling would land, clamped to the travel range.
     *
     * <p>Clamped because "past the bottom" is not a place, and because a clamp
     * cannot change a decision that has already been made by the time the
     * projection runs off either end: at the top the position is what expands it,
     * at the bottom a collapse. Kept public so a caller that wants to show the
     * projected landing (a debug overlay, a test's arithmetic) reads the same
     * number the decision reads.
     */
    public static float projected(float position, float velocityPerSecond) {
        float projected = position + velocityPerSecond * PROJECTION_SECONDS;
        if (projected < 0f) return 0f;
        if (projected > 1f) return 1f;
        return projected;
    }
}
