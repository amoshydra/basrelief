package com.amoshydra.basrelief.core.store;

/**
 * Whether a row has any 3D at all: a relief it can displace, or a splat cloud it
 * can draw. The one answer the sheet's "no 3D" line and its amber pill read.
 *
 * <p><b>Why this exists.</b> The no-depth line was derived from the depth answer
 * alone, so a row with a splat cloud and no depth map — a perfectly ordinary row,
 * because the cloud and the map are separate files on {@link LibraryItem} — read
 * <b>"No depth"</b> and lit the pill amber while the renderer was drawing a
 * Gaussian cloud in three dimensions. The line was lying about the row on screen,
 * which is the same failure {@link DepthSource} was written to remove, one medium
 * over. The user's rule is the whole of it: <b>as long as a relief or a splat is
 * present, the no-depth affordance must not appear at all</b>; only a row with
 * neither may say anything, and what it says is "no 3D" rather than "No depth".
 *
 * <p><b>It is a combining function, not a fifth vocabulary.</b> "Where did this
 * depth come from" is {@link DepthSource}; "where did this cloud come from" is
 * {@link SplatSource}; this class asks neither again and invents no facts of its
 * own. It only combines the two answers, so there is one place that decides what
 * "has 3D" means and the fix line, the pill and any future reader cannot drift.
 *
 * <p><b>A window claims nothing, in both inputs.</b> A depth answer of
 * {@link DepthSource.Kind#UNKNOWN} and a cloud answer of
 * {@link SplatSource.Kind#UNREAD} are both "not read yet", and neither is a
 * negative: a row whose cloud file has not been stat'd must not be reported as
 * having no 3D, or the confident wrong answer returns through the front door. But
 * a <b>known positive wins over the other window</b>: a row that already has a
 * depth map is a row that has 3D whether or not its cloud has been read, so the
 * answer is {@link Kind#PRESENT} rather than a silence that would blank a line
 * about a picture already on screen.
 *
 * <p>Pure, two plain inputs, no Android — the same contract as its siblings.
 */
public final class Scene3D {

    /**
     * The three answers.
     *
     * <p>{@link #UNKNOWN} is listed first so that reading it as an ordinal gives
     * the <i>least</i> claim, the safe direction for a value whose absence must
     * never become a statement.
     */
    public enum Kind {
        /** Not known yet: one of the two sources has not been read. Claims nothing. */
        UNKNOWN,

        /** Neither a depth map nor a cloud. The only state that may say "no 3D". */
        NONE,

        /** A depth map, a cloud, or both: the row can draw in 3D. */
        PRESENT
    }

    /** One resolved answer: the kind, and the two questions a caller asks of it. */
    public static final class Answer {

        private final Kind kind;

        private Answer(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        /** Whether the answer is not known yet. See {@link Kind#UNKNOWN}. */
        public boolean isUnknown() {
            return kind == Kind.UNKNOWN;
        }

        /**
         * Whether the row has 3D <b>known to be there</b>.
         *
         * <p>False while {@link #isUnknown()}, deliberately: an unknown answer is
         * not evidence of absence, and a caller that used this to decide "show
         * nothing" would put the no-3D line over a row whose cloud was simply not
         * read yet.
         */
        public boolean has3d() {
            return kind == Kind.PRESENT;
        }

        /**
         * Whether the app may say <b>"no 3D"</b>.
         *
         * <p><b>Only {@link Kind#NONE}.</b> This is the single most important
         * method in the class: it must not be reachable from either window, or the
         * reported bug returns through the front door.
         */
        public boolean reportsNo3d() {
            return kind == Kind.NONE;
        }

        @Override
        public String toString() {
            return "Scene3D." + kind;
        }
    }

    /** The answer before either source has been read. Claims nothing. */
    public static final Answer UNKNOWN = new Answer(Kind.UNKNOWN);

    private static final Answer NONE = new Answer(Kind.NONE);
    private static final Answer PRESENT = new Answer(Kind.PRESENT);

    private Scene3D() {
    }

    /**
     * Resolve the two source answers into one "is there any 3D" answer.
     *
     * <p>A null input is not a source that has been read, so it claims nothing —
     * the same "a window claims nothing" rule, applied to a caller that has not
     * resolved the medium yet.
     *
     * @param depth the row's depth answer, or null while it has not been resolved
     * @param splat the row's cloud answer, or null while it has not been resolved
     */
    public static Answer resolve(DepthSource.Answer depth, SplatSource.Answer splat) {
        if (depth == null || splat == null) return UNKNOWN;
        // A known positive first: a row that has a depth map has 3D whether or not
        // its cloud file has been read, so a pending read must not silence the line.
        if (depth.hasDepth() || splat.hasCloud()) return PRESENT;
        // Neither is present yet -- but if either is still a window, neither is
        // absent either. Only a settled NONE on BOTH may say "no 3D".
        if (depth.isUnknown() || splat.isUnread()) return UNKNOWN;
        return NONE;
    }
}
