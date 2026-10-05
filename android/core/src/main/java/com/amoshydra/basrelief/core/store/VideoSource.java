package com.amoshydra.basrelief.core.store;

/**
 * Whether a library row is a photograph or a clip, and whether the clip can be
 * played yet.
 *
 * <p><b>Why this exists, and why it is a third class.</b> {@link DepthSource}
 * answers where a <i>depth map</i> came from and {@link NormalSource} answers
 * whether shading is <i>supplied</i>. Neither can answer "is this row's picture a
 * file or a clip", because for a clip neither question is asked about the same
 * thing: the depth is a track inside a second video, and the shading is a third
 * video, and both are read per frame rather than once. So the row's medium is a
 * question of its own, it has its own honest answers, and the two existing classes
 * are resolved <i>from</i> it rather than each growing a copy of it.
 *
 * <p><b>The window is real and it is the whole reason for the fourth kind.</b> The
 * index says a row <i>names</i> clips. Whether they decode is a file read and a
 * codec, so on every selection there is a stretch before it is known — and during
 * it the row is going to render as one of two genuinely different pictures, a
 * playing clip or its poster frame. Answering either one during the window would
 * be a claim, and {@link AGENTS.md}'s standing lesson is that the claim which
 * costs a user their trust is the confident wrong one: {@code DepthSource} was
 * written because a PNG with an embedded map printed <b>"No depth"</b> over a
 * photograph whose relief was visibly playing. So the window has its own kind, and
 * {@link Answer#reportsNoClip()} is false for it.
 *
 * <p><b>POSTER is not STILL, and the difference is the point.</b> A row that has
 * clips which cannot be read is not a photograph; it is a clip that has fallen back
 * to its poster frame, which is what §3 step 8 of {@code docs/VIDEO-PLAN.md}
 * specifies as the fallback. Reporting it as a photograph would tell the user their
 * video is a photo and offer them a depth map to regenerate, when what is missing
 * is a file that was there. So the two are separate kinds, and only {@link Kind#STILL}
 * may claim a row has no clips at all.
 *
 * <p>Pure, two plain inputs, no Android — the same contract as its two siblings.
 */
public final class VideoSource {

    /**
     * The four states a row's medium can be in.
     *
     * <p>{@link #UNREAD} is listed first for the reason {@link DepthSource.Kind}
     * lists {@code UNKNOWN} first: reading it as an ordinal gives the <i>least</i>
     * claim, which is the safe direction for a value whose absence must never
     * become a statement.
     */
    public enum Kind {
        /**
         * Not known yet: the row names clips and they have not been read. Claims
         * nothing at all — neither that it is a photograph nor that it is playing.
         */
        UNREAD,

        /**
         * An ordinary photograph. The row names no clips, so the answer needs no
         * read and is never wrong.
         */
        STILL,

        /**
         * A clip whose colour and depth tracks are both here. The renderer will
         * draw per-frame displacement from the decoded tracks.
         */
        CLIP,

        /**
         * A clip whose files are named but are not all here — the restore case,
         * a sync rule, or a partial delete. <b>The poster frame is what renders</b>
         * and the status line has to say so, because the user's video is not a
         * photo and offering them a depth map to regenerate would be advice about
         * the wrong file.
         */
        POSTER
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
         * Whether the row names a clip at all — playing or fallen back.
         *
         * <p>False while {@link #isUnread()}, deliberately: an unread row is
         * exactly the row whose medium is not yet known, so answering this true
         * would be the confident wrong answer {@link DepthSource} was written to
         * remove.
         */
        public boolean hasClips() {
            return kind == Kind.CLIP || kind == Kind.POSTER;
        }

        /**
         * Whether the renderer will draw frames from a decoded clip.
         *
         * <p>The distinction {@link #hasClips()} exists for: a {@link Kind#POSTER}
         * row has clips and does not play them.
         */
        public boolean playsClip() {
            return kind == Kind.CLIP;
        }

        /**
         * Whether the app may say <b>"this is a photograph"</b>.
         *
         * <p>Only {@link Kind#STILL}. This is the counterpart of
         * {@link DepthSource.Answer#reportsNoDepth()} and it has the same job: be
         * unreachable from the unread window, or the reported bug returns through
         * the front door in a new place.
         */
        public boolean reportsNoClip() {
            return kind == Kind.STILL;
        }

        @Override
        public String toString() {
            return "VideoSource." + kind;
        }
    }

    /** An ordinary photograph. See {@link Kind#STILL}. */
    public static final Answer STILL = new Answer(Kind.STILL);

    /** The answer before the clips have been read. Claims nothing. */
    public static final Answer UNREAD = new Answer(Kind.UNREAD);

    private static final Answer CLIP = new Answer(Kind.CLIP);
    private static final Answer POSTER = new Answer(Kind.POSTER);

    private VideoSource() {
    }

    /**
     * Resolve the two facts into one answer.
     *
     * <p><b>A row with no clips never has a window.</b> That is the property which
     * keeps this class's window as narrow as it can be: the answer is known from
     * the index alone for every photograph in the gallery, so no still's tile or
     * status line flickers through {@link Kind#UNREAD} on selection.
     *
     * @param hasClips  whether the row names clips at all
     * @param playable  TRUE or FALSE once the clips have been read, or <b>null</b>
     *                  while they have not — the only way to get
     *                  {@link Kind#UNREAD} back
     */
    public static Answer resolve(boolean hasClips, Boolean playable) {
        if (!hasClips) return STILL;
        if (playable == null) return UNREAD;
        return playable.booleanValue() ? CLIP : POSTER;
    }

    /**
     * The answer for one library item.
     *
     * <p><b>A null item is {@link Kind#STILL}.</b> Nothing selected means the
     * renderer draws the packaged demo pair, which is three PNGs. The same
     * reasoning that makes {@link DepthSource#forItem} answer {@code FILE} for no
     * selection.
     *
     * @param item     the selected item, or null for none
     * @param playable TRUE or FALSE once the clips have been read, or null while
     *                 they have not
     */
    public static Answer forItem(LibraryItem item, Boolean playable) {
        if (item == null) return STILL;
        return resolve(item.video != null, playable);
    }
}