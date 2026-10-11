package com.amoshydra.basrelief.core.store;

/**
 * Where a row's Gaussian splat cloud comes from — the fourth "where did this come
 * from" vocabulary, beside {@link DepthSource}, {@link NormalSource} and
 * {@link VideoSource}.
 *
 * <p><b>Why a fourth class.</b> A cloud is not a depth map, not a normal map and not
 * a clip: it is a whole reconstruction of the photograph, and the questions the
 * other three answer are all asked about a different file. So "does this row have a
 * cloud, and is it here" is a question of its own, and the sheet's cloud row is the
 * reader.
 *
 * <p><b>The window claims nothing.</b> The index says a row <i>names</i> a cloud;
 * whether the bytes are on disk is a file read, so on every selection there is a
 * stretch before it is known — the same window {@link VideoSource#Kind#UNREAD}
 * exists for. Answering {@link Kind#NONE} during it would put "no cloud yet — make
 * one?" over a row that already has one, which is the confident wrong answer
 * {@code DepthSource} was written to remove.
 *
 * <p>Pure, two plain inputs, no Android — the same contract as its three siblings.
 */
public final class SplatSource {

    /**
     * The three states a row's cloud can be in.
     *
     * <p>{@link #UNREAD} is listed first so reading it as an ordinal gives the
     * <i>least</i> claim, the safe direction for a value whose absence must never
     * become a statement.
     */
    public enum Kind {
        /** Not known yet: the row names a cloud and the file has not been read. */
        UNREAD,

        /** No cloud at all. The only state that may say "no cloud yet". */
        NONE,

        /** The cloud file is here. */
        FILE
    }

    /** One resolved answer: the kind, and what a caller needs from it. */
    public static final class Answer {

        private final Kind kind;

        private Answer(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        /** Whether the answer is not known yet. See {@link Kind#UNREAD}. */
        public boolean isUnread() {
            return kind == Kind.UNREAD;
        }

        /**
         * Whether the row has a cloud <b>known to be here</b>.
         *
         * <p>False while {@link #isUnread()}, deliberately: an unknown answer is not
         * evidence of absence.
         */
        public boolean hasCloud() {
            return kind == Kind.FILE;
        }

        /** Whether the app may say <b>"no cloud yet"</b>. Only {@link Kind#NONE}. */
        public boolean reportsNoCloud() {
            return kind == Kind.NONE;
        }

        @Override
        public String toString() {
            return "SplatSource." + kind;
        }
    }

    /** The answer before the file has been read. Claims nothing. */
    public static final Answer UNREAD = new Answer(Kind.UNREAD);

    private static final Answer NONE = new Answer(Kind.NONE);
    private static final Answer FILE = new Answer(Kind.FILE);

    private SplatSource() {
    }

    /**
     * Resolve the two facts into one answer.
     *
     * @param hasCloud whether the row names a cloud at all
     * @param present  TRUE or FALSE once the file has been read, or <b>null</b>
     *                 while it has not — the only way to get {@link Kind#UNREAD}
     */
    public static Answer resolve(boolean hasCloud, Boolean present) {
        if (!hasCloud) return NONE;
        if (present == null) return UNREAD;
        return present.booleanValue() ? FILE : NONE;
    }

    /**
     * The answer for one library item.
     *
     * <p><b>A null item is {@link Kind#NONE}.</b> Nothing selected is not a cloud
     * row; the sheet has no id to act against.
     *
     * @param item    the selected item, or null for none
     * @param present TRUE or FALSE once the cloud file has been read, or null while
     *                it has not
     */
    public static Answer forItem(LibraryItem item, Boolean present) {
        if (item == null) return NONE;
        return resolve(item.splatName != null, present);
    }
}
