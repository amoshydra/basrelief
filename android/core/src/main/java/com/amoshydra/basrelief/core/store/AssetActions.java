package com.amoshydra.basrelief.core.store;

/**
 * The sheet's asset row: the secondary actions a row may offer beside its primary
 * build.
 *
 * <p><b>Why this exists.</b> The sheet used to carry the same decisions twice. Under
 * the Renderer segment a cloud row stated the cloud's state and offered its one
 * action (<i>Make one</i> / <i>Rebuild</i> / <i>Regenerate</i>) beside a
 * <b>Delete</b>; all the way at the bottom a "This photo's map" section offered
 * <b>Regenerate</b> and <b>Replace</b>. The user's report was exactly that: <i>the rebuild/delete row sat right below the tabs while the photo-map row at the bottom offered
    regenerate/replace, and the two were expected to be combined</i> The two rows became one, and the one row now states the
 * selected mode's asset and offers its primary build as well — see {@link AssetRow},
 * which composes this answer with {@link BuildTab}'s. This class stays the gate for
 * the <b>second</b> button: it decides the actions that are not the mode's build.
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
        /** Nothing to offer: an embedded map, a track, or a run that owns the row. */
        NONE,

        /** A still depth file: choose another map to write over it. */
        REPLACE,

        /** No depth yet: choose a map to add. */
        CHOOSE,

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
                // A still file: replace it. An embedded map is BUILT and cannot
                // replace, so it falls through to NONE.
                depthAction = DepthAction.REPLACE;
            }
        } else if (relief != null && relief.state() == BuildTab.State.NONE
                && depth != null && depth.reportsNoDepth()) {
            // No map yet, and the row says so: choose one. The gate is
            // reportsNoDepth() rather than canReplace(), so the UNKNOWN window --
            // which is also State.NONE, and whose canReplace() is false -- claims
            // nothing rather than offering a map for a photo that may have one.
            depthAction = DepthAction.CHOOSE;
        }

        if (!deleteCloud && depthAction == DepthAction.NONE) return NONE;
        return new Answer(deleteCloud, depthAction);
    }
}
