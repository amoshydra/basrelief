package com.amoshydra.basrelief.core.store;

/**
 * Whether a photograph renders <b>shaded</b> or <b>flat lit</b>, and therefore
 * what the sheet may offer to do about it.
 *
 * <p><b>Why this exists.</b> Shading used to be derived from the depth map at
 * draw time, so every photograph with depth was shaded and there was nothing to
 * decide. It is now supplied separately: a depth map displaces the surface, and a
 * <i>normal map</i> says which way each cell faces. A photo with depth and no
 * normal is displaced correctly and lit as a flat plane — which is a real,
 * deliberate state, not a bug — and it is the state every existing install
 * upgrades into.
 *
 * <p><b>There is deliberately no {@code DERIVED}.</b> The old derived-normal
 * path is gone, not deprecated, and a kind that named it would be a state the
 * renderer could not render: it would put "shaded" on the sheet while the shader
 * drew flat. Two answers, two surfaces, one question — exactly the
 * {@link DepthSource} failure this class is shaped to avoid.
 *
 * <p>The question is answered from the item alone, because a normal is a separate
 * file named by the index and there is no embedded form to read. That keeps the
 * window narrow — and it is the reason {@link Kind#UNREAD} is a clip-only state:
 * a clip's shading is a <i>track</i>, and whether that track is what will be drawn
 * is a fact about the decoder rather than about the index. See {@link VideoSource}.
 *
 * <p><b>Still two shading answers, not four.</b> A clip's normal track is supplied
 * shading and an absent one is flat lit, exactly as for a photograph, and a kind
 * that named the difference would be a state the renderer acts on identically — the
 * "two answers to one question" failure {@link DepthSource} exists to avoid, in the
 * place where it would put "shaded" on the sheet and the shader drew from a
 * different file.
 *
 * <p>Pure, one plain input, no Android. See {@link DepthSource} for the sibling
 * decision and the precedent this follows.
 */
public final class NormalSource {

    /** The three states a row's shading can be in. */
    public enum Kind {
        /**
         * No supplied normal. The renderer draws the relief <b>flat lit</b>: the
         * displacement is real and the shading is that of a plane. This is the
         * migration state of every photo that had depth before normals existed,
         * and it is stable until a run supplies one.
         */
        NONE,

        /** A normal map for this photo's depth. The renderer shades with it. */
        SUPPLIED,

        /**
         * Not known yet, and the reason is not the usual one.
         *
         * <p><b>A clip that has not been decoded.</b> This class had no unknown state
         * and said so, because a normal is a file the index names and nothing has to
         * be read to know whether one exists. A clip breaks that: the shading comes
         * from a third video, and whether <i>that</i> is what will be drawn is not
         * known until the decoder has reported.
         *
         * <p>So the window has its own kind rather than being folded into
         * {@link #NONE}, and {@link Answer#needsNormals()} is false for it. Folding
         * it in would put "Flat lit — depth, no normals" and a "generate normals"
         * button over a clip whose every frame is shaded — which is the reported
         * "No depth over a photograph with a map" bug again, with a different verb.
         */
        UNREAD
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

        /**
         * Whether the renderer shades this photo with a supplied normal.
         *
         * <p>False for {@link Kind#NONE}, which is what puts the sheet's flat-lit
         * line and its "generate normals" action on screen, and false while
         * {@link #isUnknown()}, because an unread answer is not evidence of absence.
         */
        public boolean hasSuppliedNormals() {
            return kind == Kind.SUPPLIED;
        }

        /**
         * Whether the sheet should offer to supply one.
         *
         * <p>Only {@link Kind#NONE}. Named separately from
         * {@link #hasSuppliedNormals()} so a kind that is neither — which
         * {@link Kind#UNREAD} now is — cannot inherit one meaning from the other by
         * accident. That is exactly what happened when {@code UNREAD} arrived: the
         * complement was the obvious thing to write, and it would have offered to
         * generate normals for a clip that is about to be shaded from a track.
         */
        public boolean needsNormals() {
            return kind == Kind.NONE;
        }

        /**
         * Whether the answer is not known yet.
         *
         * <p>Only {@link Kind#UNREAD}, and the line must stay silent for it — the
         * same rule {@link DepthSource} has carried since a PNG's embedded map
         * printed "No depth" over a photograph whose relief was playing.
         */
        public boolean isUnknown() {
            return kind == Kind.UNREAD;
        }

        @Override
        public String toString() {
            return "NormalSource." + kind;
        }
    }

    private static final Answer NONE = new Answer(Kind.NONE);
    private static final Answer SUPPLIED = new Answer(Kind.SUPPLIED);
    private static final Answer UNREAD = new Answer(Kind.UNREAD);

    private NormalSource() {
    }

    /**
     * Resolve the two facts into one answer.
     *
     * <p><b>A normal with no depth is not supplied normals.</b> It has nothing to
     * shade: the renderer draws no relief without a depth map, so reporting it as
     * shaded would describe a render that cannot happen. Both are required.
     *
     * @param hasDepth  whether the item names a depth map
     * @param hasNormal whether the item names a normal map
     */
    public static Answer resolve(boolean hasDepth, boolean hasNormal) {
        return hasDepth && hasNormal ? SUPPLIED : NONE;
    }

    /**
     * The answer for one library item, with the row's medium already resolved.
     *
     * <p><b>A clip is shaded from its own normal track</b>, never from the poster's
     * PNG: §5 of {@code docs/VIDEO-PLAN.md} samples the shading normal per fragment
     * for a video, and a still normal map describes one frame of a shape that moves.
     * So the medium is asked first, and only a row that will render as a photograph
     * falls through to the two facts of its stills.
     *
     * <p>A clip whose files are missing answers about its poster frame, because that
     * is what will be drawn — the same rule {@link DepthSource} follows, and for the
     * same reason: describe the render, not the asset.
     *
     * @param item  the selected item, or null for none
     * @param video {@link VideoSource#forItem} for the same item, or null if it has
     *              not been resolved, which is {@link Kind#UNREAD} rather than a
     *              guess
     */
    public static Answer forItem(LibraryItem item, VideoSource.Answer video) {
        if (item == null) return SUPPLIED;
        if (video == null || video.isUnread()) return UNREAD;
        if (video.playsClip()) {
            // Non-null by construction: playsClip() requires the row to name clips.
            return resolve(true, item.video.hasNormalTrack());
        }
        // A photograph, or a clip fallen back to its poster frame.
        return item.hasSuppliedNormals() ? SUPPLIED : NONE;
    }

    /**
     * The still-only form: the row's medium is taken from the index and the clips
     * are treated as unread.
     *
     * <p>Correct for every photograph, and deliberately silent for a video row: this
     * form cannot know whether the clip will play, so it claims nothing rather than
     * describing shading it has not established.
     *
     * <p><b>A null item is {@link Kind#SUPPLIED}.</b> Nothing selected means the
     * renderer draws the packaged demo pair, which ships a normal map, so the
     * honest answer is "shaded" and the sheet stays silent — the same reasoning
     * that makes {@link DepthSource#forItem} answer {@code FILE} for no selection.
     */
    public static Answer forItem(LibraryItem item) {
        if (item == null) return SUPPLIED;
        return forItem(item, VideoSource.forItem(item, null));
    }
}
