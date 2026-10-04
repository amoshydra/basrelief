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
 * window narrow: unlike {@link DepthSource} there is no {@code UNKNOWN}, because
 * nothing has to be read to answer it.
 *
 * <p>Pure, one plain input, no Android. See {@link DepthSource} for the sibling
 * decision and the precedent this follows.
 */
public final class NormalSource {

    /** The two states of a photograph's shading. */
    public enum Kind {
        /**
         * No supplied normal. The renderer draws the relief <b>flat lit</b>: the
         * displacement is real and the shading is that of a plane. This is the
         * migration state of every photo that had depth before normals existed,
         * and it is stable until a run supplies one.
         */
        NONE,

        /** A normal map for this photo's depth. The renderer shades with it. */
        SUPPLIED
    }

    /** One resolved answer: the kind, and the two things a caller needs from it. */
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
         * line and its "generate normals" action on screen.
         */
        public boolean hasSuppliedNormals() {
            return kind == Kind.SUPPLIED;
        }

        /**
         * Whether the sheet should offer to supply one.
         *
         * <p>The exact complement of {@link #hasSuppliedNormals()} today; named
         * separately so a future kind that is neither cannot inherit one meaning
         * from the other by accident.
         */
        public boolean needsNormals() {
            return kind == Kind.NONE;
        }

        @Override
        public String toString() {
            return "NormalSource." + kind;
        }
    }

    private static final Answer NONE = new Answer(Kind.NONE);
    private static final Answer SUPPLIED = new Answer(Kind.SUPPLIED);

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
     * The answer for one library item.
     *
     * <p><b>A null item is {@link Kind#SUPPLIED}.</b> Nothing selected means the
     * renderer draws the packaged demo pair, which ships a normal map, so the
     * honest answer is "shaded" and the sheet stays silent — the same reasoning
     * that makes {@link DepthSource#forItem} answer {@code FILE} for no selection.
     */
    public static Answer forItem(LibraryItem item) {
        if (item == null) return SUPPLIED;
        return item.hasSuppliedNormals() ? SUPPLIED : NONE;
    }
}
