package com.amoshydra.basrelief.core.store;

/**
 * The sheet's one shared asset row: the secondary actions a row may offer once the
 * primary build lives in the 3D-build dialog's tabs.
 *
 * <p><b>Why this exists.</b> The sheet used to carry the same decisions twice. Under
 * the Renderer segment a cloud row stated the cloud's state and offered its one
 * action (<i>Make one</i> / <i>Rebuild</i> / <i>Regenerate</i>) beside a
 * <b>Delete</b>; all the way at the bottom a "This photo's map" section offered
 * <b>Regenerate</b> and <b>Replace</b>. The dialog's own two tabs
 * ({@link BuildTab}) already resolve each kind's state and its primary action, so
 * both rows were restating a decision the dialog owns. The user's report was
 * exactly that: <i>the rebuild/delete row sat right below the tabs while the photo-map row at the bottom offered
    regenerate/replace, and the two were expected to be combined</i>
 *
 * <p><b>One row, and only the secondary actions.</b> The primary build stays where
 * it can be configured — in the dialog's tabs — and this class answers the two
 * questions the one shared row asks: may the user <b>delete the cloud</b>, and may
 * they <b>act on the depth</b>. The depth action is a small vocabulary rather than
 * a boolean because the two cases are genuinely different actions, not two labels
 * for one:
 *
 * <ul>
 *   <li>{@link DepthAction#REPLACE} — a still depth <b>file</b>: choose a map from
 *       the picker, which writes over this photo's depth.</li>
 *   <li>{@link DepthAction#REGENERATE} — a clip's depth is a <b>track</b>, and the
 *       dialog's Relief tab refuses it by {@link BuildTab}'s own rule (a depth this
 *       dialog cannot replace is BUILT with no action). So the clip's regeneration,
 *       which runs the whole video graph, has no tab to live in and is a secondary
 *       action by construction. It stays reachable here instead of being stranded
 *       with the removed bottom block.</li>
 * </ul>
 *
 * <p><b>It reuses the rules rather than re-deciding them.</b> Both actions are read
 * out of the two {@link BuildTab.Answer}s, so "a run in flight offers nothing" and
 * "a failure outranks the asset it left behind" hold here exactly as they do in the
 * tabs — the two screens cannot develop two meanings of BUILDING or FAILED. The one
 * extra fact is {@link DepthSource.Answer#canReplace()}: a depth the row may not
 * touch offers nothing, so an embedded map has depth and no action, and an unread
 * window claims nothing at all.
 *
 * <p>Pure, plain inputs, no Android — the same contract as {@link BuildTab} and
 * {@link Scene3D} beside it.
 */
public final class AssetActions {

    /** The one secondary action the depth asset may offer. */
    public enum DepthAction {
        /** Nothing to offer: no map, an embedded map, or a run that owns the row. */
        NONE,

        /** A still depth file: choose another map to write over it. */
        REPLACE,

        /** A clip's depth track: run the video graph again to rebuild it. */
        REGENERATE
    }

    /** One resolved answer: the two secondary actions, and whether there are any. */
    public static final class Answer {

        private final boolean deleteCloud;
        private final DepthAction depthAction;

        private Answer(boolean deleteCloud, DepthAction depthAction) {
            this.deleteCloud = deleteCloud;
            this.depthAction = depthAction;
        }

        /** Whether the shared row may offer <b>Delete</b> for a cloud. */
        public boolean canDeleteCloud() {
            return deleteCloud;
        }

        /** The depth asset's one secondary action, or {@link DepthAction#NONE}. */
        public DepthAction depthAction() {
            return depthAction;
        }

        /**
         * Whether the row has <b>nothing</b> to offer, and must therefore not be
         * shown at all.
         *
         * <p>The house rule: a control that cannot change anything is a lie this
         * project refuses to ship. An empty shared row would be the same lie with
         * more pixels.
         */
        public boolean isNone() {
            return !deleteCloud && depthAction == DepthAction.NONE;
        }

        @Override
        public String toString() {
            return "AssetActions.delete=" + deleteCloud + ".depth=" + depthAction;
        }
    }

    /** The answer for a row with neither a cloud nor a touchable depth. */
    public static final Answer NONE = new Answer(false, DepthAction.NONE);

    private AssetActions() {
    }

    /**
     * Resolve the shared row's secondary actions from the two tabs' own answers.
     *
     * @param cloud              the Splat tab's answer for the row, or null while
     *                           it has not been resolved
     * @param relief             the Relief tab's answer for the row, or null while
     *                           it has not been resolved. <b>For a clip this is the
     *                           clip's own run state</b>, not the still one: the
     *                           caller resolves it against the video job, so a clip
     *                           import in flight offers nothing here
     * @param depth              the row's depth answer, or null while unresolved
     * @param regeneratesAsClip  whether the row's depth is (or will be) a clip's
     *                           track, so regenerating it runs the video graph
     */
    public static Answer resolve(BuildTab.Answer cloud, BuildTab.Answer relief,
                                 DepthSource.Answer depth, boolean regeneratesAsClip) {
        // Delete is a secondary action on a built cloud: a run in flight owns the
        // row and a failure outranks the asset, exactly the ranking BuildTab applies
        // to the Splat tab's own button.
        boolean deleteCloud = cloud != null && cloud.state() == BuildTab.State.BUILT;

        DepthAction depthAction = DepthAction.NONE;
        if (relief != null && relief.state() == BuildTab.State.BUILT) {
            if (regeneratesAsClip) {
                depthAction = DepthAction.REGENERATE;
            } else if (depth != null && depth.canReplace()) {
                // A still file: replace it. A row with no map is not BUILT above --
                // adding a map where there is none is the fix line's "Choose a map",
                // not this row's Replace. An embedded map is BUILT and cannot
                // replace, so it falls through to NONE, which the fix line refuses
                // for the same reason.
                depthAction = DepthAction.REPLACE;
            }
        }

        if (!deleteCloud && depthAction == DepthAction.NONE) return NONE;
        return new Answer(deleteCloud, depthAction);
    }
}
