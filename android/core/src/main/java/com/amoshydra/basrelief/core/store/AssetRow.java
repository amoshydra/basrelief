package com.amoshydra.basrelief.core.store;

/**
 * The sheet's asset row, directly under the Renderer segment: the <b>selected
 * mode's state</b> and its <b>primary action</b>, beside the secondary actions that
 * belong to that mode.
 *
 * <p><b>Why this exists, and what it corrects.</b> The row under the Renderer
 * segment used to hold only the <i>secondary</i> actions, on the reasoning that the
 * 3D-build dialog's Relief and Splat tabs already stated each kind's state and
 * offered its primary action. That reasoning hid both facts behind a dialog: the
 * user reported it as <i>the splat regeneration action and the splat count had gone missing</i> — the cloud's count and its Rebuild were one tap behind a
 * control you had to know to tap. So the row states the selected mode's asset and
 * offers its primary action, and the dialog becomes the <i>settings and confirm</i>
 * that primary opens: the budget on the Splat tab, the model and resolution on the
 * Relief tab.
 *
 * <p><b>Only the selected mode's row exists.</b> The segment is the mode selector, so
 * a row drawn as a relief states its depth and a row drawn as a cloud states its
 * cloud. There is no second row restating the other mode's asset.
 *
 * <p><b>One ranking, one gate.</b> The state and the primary action are
 * {@link BuildTab}'s own answers — the same ones the dialog's tabs read — so "a run
 * in flight offers nothing" and "a failure outranks the asset it left behind" hold
 * here exactly as there. The secondary actions are {@link AssetActions}' own answer,
 * so Delete, Replace, Choose a map and a clip's regeneration are gated once. This
 * class combines the two; it decides neither.
 *
 * <p><b>The fix line is the depth run's signal, so a Relief row does not restate
 * one.</b> A still depth run owns the sheet's fix line, which states its percentage,
 * and a row that repeated it would be a second, staler copy — so a Relief row reports
 * its asset whatever the run is doing, and its button comes from the tab answer
 * ({@link BuildTab.Action#NONE} while a run owns the row, {@code REGENERATE} after a
 * failure). A cloud build has <b>no</b> fix line, so a Splat row carries its phase
 * and its failure's own words, which is what makes the count and Rebuild reachable
 * at all.
 *
 * <p>Pure, plain inputs, no Android — the same contract as {@link BuildTab} and
 * {@link AssetActions} beside it.
 */
public final class AssetRow {

    /** The state sentence the row shows. */
    public enum State {
        /**
         * Nothing to report: no row, or a window that claims nothing.
         *
         * <p>A row in this state with no button is hidden rather than shown empty.
         */
        NONE,

        /** Relief, no map: "No depth map yet". */
        NO_DEPTH,

        /** Relief, a separate depth file: "Has a depth map". */
        HAS_DEPTH,

        /** Relief, depth inside the image's own PNG: nothing this row can act on. */
        DEPTH_EMBEDDED,

        /** Relief, depth in a clip's track: the row's Regenerate rebuilds the set. */
        DEPTH_TRACK,

        /** Splat, no cloud: "no cloud yet — make one?". */
        NO_CLOUD,

        /** Splat, a cloud is here: "N splats · SHARP", N read from the file. */
        CLOUD,

        /** Splat, a run owns the row: the live phase, and no action. */
        BUILDING,

        /** Splat, the last run failed: the server's own words, and a retry. */
        FAILED
    }

    /** One button the row may carry. */
    public enum Action {
        /** No button. */
        NONE,

        /** The mode's first build: Generate (Relief) / Make one (Splat). Opens the dialog. */
        BUILD,

        /** Replace a built asset: Rebuild (Splat). Opens the dialog. */
        REBUILD,

        /** Retry a build: Regenerate. Opens the dialog. */
        REGENERATE,

        /** A still depth file: choose another map, through the file picker. */
        REPLACE,

        /** No map yet: choose one, through the same file picker. */
        CHOOSE,

        /** Delete the cloud. */
        DELETE,

        /** A clip's depth track: rebuild the whole track set, through the video graph. */
        REGENERATE_CLIP;

        /**
         * The {@link BuildTab.Action} this button runs, or {@link BuildTab.Action#NONE}
         * for a secondary.
         *
         * <p>So a caller can resolve the button's own word through {@code BuildTab}'s
         * ranking rather than keeping a second copy of it — the labels are the one
         * thing that stays on the platform.
         */
        public BuildTab.Action tabAction() {
            switch (this) {
                case BUILD:      return BuildTab.Action.BUILD;
                case REBUILD:    return BuildTab.Action.REBUILD;
                case REGENERATE: return BuildTab.Action.REGENERATE;
                default:         return BuildTab.Action.NONE;
            }
        }
    }

    /** One resolved row: its state, its two button slots, and the tab its build opens. */
    public static final class Answer {

        private final State state;
        private final Action lead;
        private final Action trail;
        private final BuildTab.Kind tab;

        private Answer(State state, Action lead, Action trail, BuildTab.Kind tab) {
            this.state = state;
            this.lead = lead;
            this.trail = trail;
            this.tab = tab;
        }

        /** The sentence the row states, before any words. */
        public State state() {
            return state;
        }

        /**
         * The button that comes <b>first</b> (left), or {@link Action#NONE}.
         *
         * <p>Relief leads with the asset action and trails with the build; Splat leads
         * with the build and trails with Delete, because Delete is destructive and
         * goes last. The order is the design's, and it lives here so the two slots
         * cannot be swapped by a caller.
         */
        public Action lead() {
            return lead;
        }

        /** The button that comes <b>second</b> (right), or {@link Action#NONE}. */
        public Action trail() {
            return trail;
        }

        /** The tab the row's build action opens the dialog on. */
        public BuildTab.Kind tab() {
            return tab;
        }

        /**
         * Whether the row has nothing to show: no state and no button.
         *
         * <p>The house rule is about <b>controls</b> — a control that cannot change
         * anything is a lie this project refuses to ship. An empty row would be the
         * same lie with more pixels. A state sentence with no button is not a
         * control, so a row that has something to say stays: an embedded map is the
         * case, and it says why there is nothing to build.
         */
        public boolean isNone() {
            return state == State.NONE && lead == Action.NONE && trail == Action.NONE;
        }

        @Override
        public String toString() {
            return "AssetRow." + state + ".lead=" + lead + ".trail=" + trail;
        }
    }

    /** The answer for a row with nothing to state and nothing to offer. */
    public static final Answer NONE = new Answer(State.NONE, Action.NONE, Action.NONE,
            BuildTab.Kind.RELIEF);

    private AssetRow() {
    }

    /**
     * Resolve the selected mode's row.
     *
     * <p>The two {@link BuildTab.Answer}s and the {@link DepthSource.Answer} are the
     * caller's own — the same values the dialog's tabs and the fix line read — so
     * there is one derivation of "is a run in flight" and one of "does this row have
     * a map". The secondaries come from {@link AssetActions}, which this method
     * calls rather than re-implementing.
     *
     * @param mode               the selected mode, or null for the relief
     * @param relief             the Relief tab's answer, or null while unresolved
     * @param splat              the Splat tab's answer, or null while unresolved
     * @param depth              the row's depth answer, or null while unresolved
     * @param regeneratesAsClip  whether the row's depth is (or will be) a clip's
     *                           track, so regenerating it runs the video graph
     */
    public static Answer resolve(RenderMode mode, BuildTab.Answer relief, BuildTab.Answer splat,
                                 DepthSource.Answer depth, boolean regeneratesAsClip) {
        boolean splatMode = mode != null && mode.isSplat();
        BuildTab.Kind tab = splatMode ? BuildTab.Kind.SPLAT : BuildTab.Kind.RELIEF;
        BuildTab.Answer selected = splatMode ? splat : relief;
        AssetActions.Answer secondaries = AssetActions.resolve(splat, relief, depth, regeneratesAsClip);

        State state = stateFor(splatMode, selected, depth);
        Action primary = primaryFor(selected);
        Action secondary = secondaryFor(splatMode, secondaries);

        // The design's order: Relief leads with the asset action and trails with the
        // build; Splat leads with the build and trails with Delete.
        Action lead = splatMode ? primary : secondary;
        Action trail = splatMode ? secondary : primary;
        return new Answer(state, lead, trail, tab);
    }

    /**
     * The sentence the row states.
     *
     * <p>A Splat row carries its run, because a cloud build has no fix line. A Relief
     * row is its depth's own kind — the same four sentences the dialog's Relief tab
     * states — and never a run, because the fix line is the depth run's signal. The
     * unread window answers {@link State#NONE}: a window claims nothing, which is why
     * {@link DepthSource.Answer#isUnknown()} is asked before the kind.
     */
    private static State stateFor(boolean splatMode, BuildTab.Answer selected,
                                  DepthSource.Answer depth) {
        if (selected == null) return State.NONE;
        if (splatMode) {
            switch (selected.state()) {
                case BUILDING: return State.BUILDING;
                case FAILED:   return State.FAILED;
                case BUILT:    return State.CLOUD;
                case NONE:
                default:       return State.NO_CLOUD;
            }
        }
        if (depth == null || depth.isUnknown()) return State.NONE;
        switch (depth.kind()) {
            case NONE:     return State.NO_DEPTH;
            case FILE:     return State.HAS_DEPTH;
            case EMBEDDED: return State.DEPTH_EMBEDDED;
            case TRACK:    return State.DEPTH_TRACK;
            default:       return State.NONE;
        }
    }

    /** The mode's build action, read straight off the tab's own ranking. */
    private static Action primaryFor(BuildTab.Answer selected) {
        if (selected == null) return Action.NONE;
        switch (selected.action()) {
            case BUILD:      return Action.BUILD;
            case REBUILD:    return Action.REBUILD;
            case REGENERATE: return Action.REGENERATE;
            default:         return Action.NONE;
        }
    }

    /**
     * The mode's other button, from {@link AssetActions}' answer.
     *
     * <p>A Splat row's secondary is Delete; a Relief row's is the one depth action.
     * The mode decides which half of the gate applies, so a cloud's Delete cannot
     * appear on a relief row and a depth's Replace cannot appear on a cloud row.
     */
    private static Action secondaryFor(boolean splatMode, AssetActions.Answer secondaries) {
        if (secondaries == null) return Action.NONE;
        if (splatMode) {
            return secondaries.canDeleteCloud() ? Action.DELETE : Action.NONE;
        }
        switch (secondaries.depthAction()) {
            case REPLACE:    return Action.REPLACE;
            case CHOOSE:     return Action.CHOOSE;
            case REGENERATE: return Action.REGENERATE_CLIP;
            case NONE:
            default:         return Action.NONE;
        }
    }
}
