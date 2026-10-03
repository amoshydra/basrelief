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
 * is fully open, 1 is fully collapsed, so a constant means the same thing on
 * every screen. Velocity is the same fraction per second. The studio sheet's
 * travel is the panel's natural height; a dialog card's is its own. Nothing here
 * ever sees a pixel, which is what makes the rule screen-independent by
 * construction.
 *
 * <p><b>The thresholds are a policy, not a rule.</b> {@link Policy} carries the
 * two numbers a host chooses, and the rule above them never changes. That is
 * deliberate: the studio sheet is a persistent panel and the user wants a free
 * position almost to the very bottom, so its collapse line is {@link #SHEET}'s
 * 0.95; a transient dialog must not park half way, so it is {@link #DIALOG}'s
 * 0.60. Two hosts with opposite needs over one derivation, instead of two
 * branches inside {@code decide} that could silently drift.
 *
 * <p><b>The dead band is the hysteresis.</b> A policy's two thresholds are
 * deliberately not the same number. A release that projects into the band means
 * the gesture was not decisive, and the honest answer is to change nothing: a
 * single 0.5 boundary would flip state on a release a hair either side of it,
 * and a jitter at the boundary would keep flipping it.
 *
 * <p><b>The projection is what makes the last direction the only one that
 * matters.</b> A drag down, up and down again ends moving down, so its projected
 * landing place is further down than where the finger let go — the earlier
 * excursion does not exist at release. The reverse is how a deep drag that is
 * brought back up survives: the upward release velocity subtracts from the
 * position, and enough of it lands the projection below the collapse line. A
 * flick past {@link #FLING_TRAVEL_PER_SECOND} skips the arithmetic entirely — a
 * decisive throw from anywhere is a decision by itself, which is the one gesture
 * a pure position rule could never read.
 */
public final class SheetIntent {

    /**
     * How far ahead, in seconds, the release is projected at its measured speed.
     * This is the momentum a hand expects to see carried; the same 0.20s the
     * dialog's old dismiss rule used. Too small and a flick has to be aimed, too
     * large and a slow drag overshoots its target.
     */
    public static final float PROJECTION_SECONDS = 0.20f;

    /**
     * A release at least this fast, in full travels per second, decides by itself:
     * down is a collapse, up is an expand, whatever the position. 1.2 is about a
     * decisive wrist flick; a deliberate drag is well under it, and
     * {@link #PROJECTION_SECONDS} catches the middle. The two guards are
     * complementary on purpose — the projection reads the gesture, the fling reads
     * the throw.
     *
     * <p>Shared by every policy, deliberately: the user asked for the studio's
     * fling behaviour to be unchanged, and a host that accepted a flick from
     * further away than another would be two rules wearing one name.
     */
    public static final float FLING_TRAVEL_PER_SECOND = 1.2f;

    /**
     * The two position thresholds one host releases against.
     *
     * <p>A projected landing place above {@link #collapseThreshold} collapses;
     * below {@link #expandThreshold} expands; between them the host's snap policy
     * decides what "not decisive" looks like (a free panel rests; a dialog returns
     * to open). The thresholds are the part the user can feel, so each host names
     * the pair it wants instead of inheriting a default.
     */
    public static final class Policy {

        /** A projected landing place above this commits a collapse. */
        public final float collapseThreshold;

        /** A projected landing place below this commits an expansion. */
        public final float expandThreshold;

        Policy(float collapseThreshold, float expandThreshold) {
            this.collapseThreshold = collapseThreshold;
            this.expandThreshold = expandThreshold;
        }
    }

    /**
     * A transient dialog's policy: collapse at 0.60, expand below 0.40.
     *
     * <p>0.60 is past the halfway point, so a release there has already given up
     * most of the travel and a collapse is the small motion; below it the card
     * would have to reverse.
     */
    public static final Policy DIALOG = new Policy(0.60f, 0.40f);

    /**
     * The studio sheet's policy: collapse only at 0.95, expand below 0.40.
     *
     * <p>The user: allow a free position even low on the screen, collapsing only near the end or on a downward fling. 0.95 is the projected landing place, not the finger's, so a
     * slow drag has to actually reach the bottom; the shared fling override is
     * what still closes it from anywhere with one decisive throw. Between 0.60
     * and 0.95 a release rests exactly where it is (the host's free policy), which
     * is the point of the number being different.
     */
    public static final Policy SHEET = new Policy(0.95f, 0.40f);

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
     * Decide a release under {@code policy}.
     *
     * @param position the collapse fraction at release: 0 open, 1 collapsed
     * @param velocityPerSecond the finger's speed in full travels per second;
     *                          positive is downward, toward collapse
     * @param policy the host's two thresholds; see {@link Policy}
     * @return what the caller should settle to; {@link Decision#STAY} means the
     *         release was inside the dead band and nothing about the position should
     *         change
     */
    public static Decision decide(float position, float velocityPerSecond, Policy policy) {
        if (velocityPerSecond >= FLING_TRAVEL_PER_SECOND) return Decision.COLLAPSE;
        if (velocityPerSecond <= -FLING_TRAVEL_PER_SECOND) return Decision.EXPAND;
        float projected = projected(position, velocityPerSecond);
        if (projected > policy.collapseThreshold) return Decision.COLLAPSE;
        if (projected < policy.expandThreshold) return Decision.EXPAND;
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
