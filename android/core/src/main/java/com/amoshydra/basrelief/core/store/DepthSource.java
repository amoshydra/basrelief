package com.amoshydra.basrelief.core.store;

/**
 * Where a photograph's depth map comes from, and therefore what the app may offer
 * to do about it.
 *
 * <p><b>Why this exists.</b> A PNG can carry its depth map <i>inside the image
 * file</i>, in a {@code dePH}/{@code dEPt} chunk — a documented feature of this
 * app. So "does this photo have depth" has two answers and neither of them is the
 * one the studio was asking: {@link LibraryItem#depthName} is null for an embedded
 * photo, and the fix line said <b>"No depth"</b> while the renderer was drawing the
 * embedded map and the relief was visibly playing. The line was lying about the
 * photograph on screen, which is the one thing a status line exists not to do.
 *
 * <p><b>The gallery already had this right</b>, from {@code Io}'s
 * {@code onEmbedded} callback, which is the proof it was a bug and not a policy:
 * two answers to one question, derived in two places. This class is the one answer,
 * so both read the same function. Deriving it twice is how they drifted.
 *
 * <p><b>It must agree with what the renderer actually draws.</b> {@code
 * ReliefRenderer.decodePair} reads a separate depth file when there is one and only
 * then falls back to the image's embedded chunk. <b>A file therefore beats an
 * embedded map</b>, and {@link #forItem} encodes that precedence rather than
 * letting each caller decide: a line that described the embedded map while the
 * renderer drew the file would be a different lie, in the other direction.
 *
 * <p><b>UNKNOWN is a real answer, not a default.</b> Whether a photo carries an
 * embedded map is a <i>file read</i>, so on every selection there is a window
 * before it is known. Answering {@link Kind#NONE} during that window is precisely
 * the bug this class exists to remove — it would claim "No depth" and offer to
 * generate over a photograph that has both. So the window has its own kind, and
 * {@link Answer#reportsNoDepth()} is false for it: <b>the line says nothing until
 * the answer is known.</b>
 *
 * <p>Pure, two or three plain inputs, no Android. That is the whole point of it
 * being here: it is a decision about booleans, and a decision about booleans is
 * exactly what this project can unit test and what it kept getting wrong.
 */
public final class DepthSource {

    /**
     * The three states of a photograph, plus the window before it is known.
     *
     * <p>{@link #UNKNOWN} is listed first so that reading it as an ordinal gives
     * the <i>least</i> claim, which is the safe direction for a value whose absence
     * should never become a statement.
     */
    public enum Kind {
        /** Not known yet: the image has not been read. Claims nothing. */
        UNKNOWN,

        /** No depth at all. The only state that may say "No depth". */
        NONE,

        /** A separate depth file. Both actions apply. */
        FILE,

        /**
         * The depth map is inside the image's own PNG.
         *
         * <p>It HAS depth — the relief is drawing it. It offers no action, because
         * there is no depth file to replace and regenerating is out of scope. That
         * is the user's decision, not an omission: a row of controls that could not
         * act is the failure this project keeps shipping by accident.
         */
        EMBEDDED
    }

    /** One resolved answer: the kind, and the four things a caller needs from it. */
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
         * Whether the photograph has depth <b>that is known to be there</b>.
         *
         * <p>False while {@link #isUnknown()}, deliberately: an unknown answer is
         * not evidence of absence, and every caller that used this to decide "show
         * nothing" is what put "No depth" on a photo that had a map.
         */
        public boolean hasDepth() {
            return kind == Kind.FILE || kind == Kind.EMBEDDED;
        }

        /**
         * Whether the app may say <b>"No depth"</b>.
         *
         * <p><b>Only {@link Kind#NONE}.</b> This is the single most important
         * method in the class: it is the one that must not be reachable from the
         * unknown window, or the reported bug returns through the front door.
         */
        public boolean reportsNoDepth() {
            return kind == Kind.NONE;
        }

        /**
         * Whether to offer <b>Regenerate</b>.
         *
         * <p>False for {@link Kind#EMBEDDED} — the user's decision, generating is
         * out of scope for that case — and false while unknown, because offering a
         * control before knowing the state is how a control ends up able to do
         * nothing.
         */
        public boolean canRegenerate() {
            return kind == Kind.NONE || kind == Kind.FILE;
        }

        /**
         * Whether to offer <b>Replace</b>. There is nothing to replace for an
         * embedded map, because there is no separate depth file.
         */
        public boolean canReplace() {
            return kind == Kind.NONE || kind == Kind.FILE;
        }

        @Override
        public String toString() {
            return "DepthSource." + kind;
        }
    }

    /** The answer before the file has been read. Claims nothing. */
    public static final Answer UNKNOWN = new Answer(Kind.UNKNOWN);

    private static final Answer NONE = new Answer(Kind.NONE);
    private static final Answer FILE = new Answer(Kind.FILE);
    private static final Answer EMBEDDED = new Answer(Kind.EMBEDDED);

    private DepthSource() {
    }

    /**
     * Resolve the two facts into one answer.
     *
     * <p><b>A file wins.</b> {@code hasDepthFile} alone decides {@link Kind#FILE},
     * whatever {@code embedded} says, because that is the order the renderer reads
     * in — a photograph with both is drawn from the file, so the file is what the
     * app has to describe and what the actions have to act on.
     *
     * @param hasDepthFile whether the item names a separate depth file
     * @param embedded     whether the image's PNG carries a depth chunk
     */
    public static Answer resolve(boolean hasDepthFile, boolean embedded) {
        if (hasDepthFile) return FILE;
        return embedded ? EMBEDDED : NONE;
    }

    /**
     * The answer for one library item.
     *
     * <p>The form the app uses, because it puts "a null {@code depthName} means the
     * file is the question, not the answer" in one place instead of in every
     * caller.
     *
     * <p><b>A null item is {@link Kind#FILE}.</b> Nothing selected means the
     * renderer is drawing the packaged demo pair, which does have a map, so the
     * honest answer is "has depth" and the line stays silent — which is what it has
     * always done there.
     *
     * <p><b>An item with a depth file is answered without reading anything.</b>
     * That is the property that keeps the unknown window as small as it can be: only
     * a photo with <i>no</i> separate file can ever be {@link Kind#UNKNOWN}, so the
     * window is never open for a photograph whose answer the index already knows.
     *
     * @param item     the selected item, or null for none
     * @param embedded TRUE or FALSE once the image has been read, or <b>null</b>
     *                 while it has not been — which is the only way to get
     *                 {@link Kind#UNKNOWN} back
     */
    public static Answer forItem(LibraryItem item, Boolean embedded) {
        if (item == null) return FILE;                 // the packaged pair
        if (item.depthName != null) return FILE;       // known from the index alone
        if (embedded == null) return UNKNOWN;
        return resolve(false, embedded.booleanValue());
    }
}
