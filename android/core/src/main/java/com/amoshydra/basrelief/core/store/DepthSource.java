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
 *
 * <p><b>A clip's depth is a track, and that is a fourth answer rather than a
 * special case inside the three.</b> {@link VideoSource} answers whether a row will
 * be drawn as a clip at all, and this class is resolved from that answer rather than
 * branching on it, so there is one truth table and the clip path cannot develop
 * answers of its own. See {@link Kind#TRACK}.
 */
public final class DepthSource {

    /**
     * The four states a row's depth can be in, plus the window before it is known.
     *
     * <p>{@link #UNKNOWN} is listed first so that reading it as an ordinal gives
     * the <i>least</i> claim, which is the safe direction for a value whose absence
     * should never become a statement.
     *
     * <p>{@link #TRACK} is the fourth, and it exists because a clip's depth is not
     * in any file this class could name before. See {@link VideoSource} for what
     * decides it.
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
        EMBEDDED,

        /**
         * The depth is a <b>track inside a clip</b>, decoded per frame.
         *
         * <p>A fourth answer rather than {@link #EMBEDDED}, and the difference is
         * which file the map is in: an embedded map is a chunk of the image being
         * drawn, while a track is a second video that has to stay frame-locked to
         * the first (§4 of {@code docs/VIDEO-PLAN.md}). Reporting one as the other
         * would be the same kind of lie this class exists to remove, and it would be
         * a lie about the two things a caller most needs to get right — where the
         * bytes come from, and what it may offer to do about them.
         *
         * <p>It has depth, and it offers <b>neither</b> action, for the same reason
         * {@link #EMBEDDED} does: regenerating is §3's offline pass over a whole
         * clip, not a write of one map file, and "replace" would have to replace the
         * colour and the normals with it or the row would describe two different
         * clips. A control that could only half-apply is the {@code withDepth} trap
         * wearing a UI.
         */
        TRACK
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
            return kind == Kind.FILE || kind == Kind.EMBEDDED || kind == Kind.TRACK;
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
         * out of scope for that case — false for {@link Kind#TRACK}, where
         * generating means §3's offline pass over every frame and is not a write of
         * one file — and false while unknown, because offering a control before
         * knowing the state is how a control ends up able to do nothing.
         */
        public boolean canRegenerate() {
            return kind == Kind.NONE || kind == Kind.FILE;
        }

        /**
         * Whether to offer <b>Replace</b>. There is nothing to replace for an
         * embedded map, because there is no separate depth file, and nothing safe to
         * replace for a track, because the depth clip is locked to a colour clip
         * frame for frame (§3 step 7: same frame count, same rate, same GOP).
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
    private static final Answer TRACK = new Answer(Kind.TRACK);

    private DepthSource() {
    }

    /**
     * Resolve the two facts into one answer, for a photograph.
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
     * The whole truth table: a row's medium, and the two facts of its stills.
     *
     * <p><b>The medium is asked first, and it can answer
     * {@link Kind#UNKNOWN} on its own.</b> A row that names clips has not yet been
     * decoded, so it is going to render as one of two genuinely different pictures
     * — a playing clip, or its poster frame — and neither may be claimed before the
     * decoder has said which. That is the same window {@link Kind#UNKNOWN} already
     * exists for on a PNG's chunk, with the same rule attached: say nothing.
     *
     * <p><b>A clip that fell back answers as the photograph it now is.</b> Not as
     * {@link Kind#TRACK}, because nothing is reading a track, and not as
     * {@link Kind#NONE}, because the poster may have a map of its own — so the still
     * rules apply unchanged, and a user whose clips were lost by a restore gets the
     * ordinary photograph answers rather than a claim about a track.
     *
     * @param video        the row's medium, or null for "the caller has not
     *                     resolved it", which is {@link Kind#UNKNOWN} rather than a
     *                     guess
     * @param hasDepthFile whether the row names a separate still depth file.
     *                     Ignored for a playing clip, whose depth is a track
     * @param embedded     whether the poster's PNG carries a depth chunk, or null
     *                     while it has not been read. Only asked for a row that will
     *                     render as a photograph
     */
    public static Answer resolve(VideoSource.Answer video, Boolean hasDepthFile,
                                 Boolean embedded) {
        if (video == null) return UNKNOWN;        // not resolved: claim nothing
        if (video.isUnread()) return UNKNOWN;    // the clip has not been read
        if (video.playsClip()) return TRACK;
        if (Boolean.TRUE.equals(hasDepthFile)) return FILE;
        if (embedded == null) return UNKNOWN;
        return resolve(false, embedded.booleanValue());
    }

    /**
     * The answer for one library item, with the row's medium already resolved.
     *
     * <p>The form the app uses, because it puts "a null {@code depthName} means the
     * file is the question, not the answer" in one place instead of in every
     * caller, and it asks the medium first because for a clip the map is not in a
     * file at all.
     *
     * <p><b>A null item is {@link Kind#FILE}.</b> Nothing selected means the
     * renderer is drawing the packaged demo pair, which does have a map, so the
     * honest answer is "has depth" and the line stays silent — which is what it has
     * always done there.
     *
     * @param item     the selected item, or null for none
     * @param embedded TRUE or FALSE once the image has been read, or <b>null</b>
     *                 while it has not been
     * @param video    {@link VideoSource#forItem} for the same item, or null if it
     *                 has not been resolved — which claims nothing rather than
     *                 guessing, for the reason {@link Kind#UNKNOWN} exists
     */
    public static Answer forItem(LibraryItem item, Boolean embedded,
                                 VideoSource.Answer video) {
        if (item == null) return FILE;                 // the packaged pair
        return resolve(video, Boolean.valueOf(item.depthName != null), embedded);
    }

    /**
     * The still-only form: the row's medium is taken from the index and the clips
     * are treated as unread.
     *
     * <p>Kept because it is what every photograph wants, and because a caller that
     * holds no clip state should not have to invent one. It is <b>honest about a
     * video row rather than convenient</b>: a row naming clips returns
     * {@link Kind#UNKNOWN}, because this form cannot know whether the clip will
     * play, and answering {@link Kind#FILE} or {@link Kind#TRACK} would describe a
     * render it has not established. That is the same rule the whole class is built
     * on — a window claims nothing — applied to a second window.
     */
    public static Answer forItem(LibraryItem item, Boolean embedded) {
        return forItem(item, embedded, VideoSource.forItem(item, null));
    }
}
