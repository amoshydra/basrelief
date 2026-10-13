package com.amoshydra.basrelief.core.store;

/**
 * The one 3D-build dialog's two tabs, and what each can offer a row.
 *
 * <p><b>Why this exists.</b> The studio used to have two build flows with two
 * entry points: a depth panel that a share opened whether or not the user wanted
 * depth, and a separate "Make 3D?" confirm for a splat cloud. The user's report
 * was that a share assume the user wants to generate depth, and the fix is
 * one dialog with two tabs, {@link Kind#RELIEF} and {@link Kind#SPLAT}. The
 * decision of <i>which</i> tab a row opens on, and <i>what each tab may offer</i>,
 * is a pure function of the index's own facts — so it lives here, where it can be
 * tested, exactly like {@link DepthSource} and {@link SplatSource} beside it.
 *
 * <p><b>The tab decides the mode.</b> Building on a tab writes that tab's
 * {@link RenderMode} on the row ({@link #modeFor}), so the picture the user was
 * looking at while they chose is the picture they get. A row can hold both a
 * depth map and a cloud at once — each is a separate file on {@link LibraryItem}
 * — so neither tab's build removes the other's asset, and each tab reports its
 * own state independently.
 *
 * <p><b>A window claims nothing, applied to a button.</b> The action a tab offers
 * is {@link Action#NONE} whenever the app cannot honestly say a build is
 * available: while a run is in flight, and for a row whose depth the still
 * generator cannot replace (an embedded map, or a clip's track). An "action" that
 * could only no-op is the control this project keeps refusing to ship.
 */
public final class BuildTab {

    /** Which of the two builds a tab runs. {@link #RELIEF} is the default. */
    public enum Kind {
        /** Build the depth map, and set the row's mode to {@link RenderMode#RELIEF}. */
        RELIEF,

        /** Build the Gaussian splat cloud, and set the row's mode to {@link RenderMode#SPLAT}. */
        SPLAT
    }

    /**
     * The state a tab is in for a row.
     *
     * <p>Listed so that an ordinal read gives the <i>least</i> claim first, the
     * same direction {@link DepthSource.Kind} and {@link SplatSource.Kind} read.
     */
    public enum State {
        /** Nothing built and nothing running. */
        NONE,

        /** The asset is on the row. */
        BUILT,

        /** A run for this row is in flight. */
        BUILDING,

        /** The last run for this row failed; its words are the row's record. */
        FAILED
    }

    /**
     * The one action a tab can offer.
     *
     * <p>Separate values for a first build, a rebuild and a retry, because the
     * three read differently to a user — "Make one", "Rebuild", "Regenerate" — and
     * the choice is a rule about state, not a label an individual screen picks.
     */
    public enum Action {
        /** Nothing to offer. See the class javadoc. */
        NONE,

        /** There is no asset yet: build the first one. */
        BUILD,

        /** There is an asset: replace it. */
        REBUILD,

        /** The last attempt failed: run it again. */
        REGENERATE
    }

    /** One resolved answer: which tab, what state, and the one action it may offer. */
    public static final class Answer {

        private final Kind tab;
        private final State state;
        private final Action action;

        private Answer(Kind tab, State state, Action action) {
            this.tab = tab;
            this.state = state;
            this.action = action;
        }

        public Kind tab() {
            return tab;
        }

        public State state() {
            return state;
        }

        public Action action() {
            return action;
        }

        /** Whether the tab has a build to offer. False while {@link Action#NONE}. */
        public boolean canBuild() {
            return action != Action.NONE;
        }

        @Override
        public String toString() {
            return "BuildTab." + tab + "." + state + "." + action;
        }
    }

    private BuildTab() {
    }

    /**
     * The tab a row should open on: the one naming its stored mode.
     *
     * <p><b>One rule, no special cases.</b> The tab a build runs on is the mode it
     * writes ({@link #modeFor}), so opening on the row's current mode means the
     * dialog never silently contradicts the renderer: a row being drawn as a cloud
     * opens on Splat, a row being drawn as a relief opens on Relief, and a brand
     * new row — which is {@link RenderMode#RELIEF} — opens on Relief. The share
     * path is no exception, because the alternative is a second rule that decides
     * a fresh share differently from every other fresh row, and a rule that only
     * exists for one caller is a rule the next reader will not find.
     *
     * <p>A null mode claims nothing and is read as {@link #RELIEF}, the same
     * degradation {@link RenderMode#parse} makes.
     */
    public static Kind defaultFor(RenderMode mode) {
        return mode != null && mode.isSplat() ? Kind.SPLAT : Kind.RELIEF;
    }

    /**
     * The mode a build on this tab writes. The tab you built on is the mode you get.
     *
     * <p>A null tab is read as {@link Kind#RELIEF}, the same "absent means the
     * default" rule the rest of the store follows.
     */
    public static RenderMode modeFor(Kind tab) {
        return tab == Kind.SPLAT ? RenderMode.SPLAT : RenderMode.RELIEF;
    }

    /**
     * What the Splat tab can offer a row.
     *
     * @param hasCloud whether the row names a cloud file that is <b>here</b> (the
     *                 {@link SplatSource} answer, not the raw index)
     * @param building whether a cloud build for this row is in flight, in this
     *                 process or as a persisted {@link Jobs} record
     * @param failed   whether the row's last cloud build failed
     */
    public static Answer forSplat(boolean hasCloud, boolean building, boolean failed) {
        // Building first: a run in flight is the only state the user cannot act on,
        // and a lingering success or failure from before it must not shadow it.
        if (building) return new Answer(Kind.SPLAT, State.BUILDING, Action.NONE);
        // A failure outranks a built cloud: the row may still hold the previous
        // cloud, but the thing the user has to act on is the run that did not land.
        if (failed) return new Answer(Kind.SPLAT, State.FAILED, Action.REGENERATE);
        if (hasCloud) return new Answer(Kind.SPLAT, State.BUILT, Action.REBUILD);
        return new Answer(Kind.SPLAT, State.NONE, Action.BUILD);
    }

    /**
     * What the Relief tab can offer a row.
     *
     * @param hasDepth   whether the row has a depth map <b>known to be there</b>
     *                   ({@link DepthSource.Answer#hasDepth()}, so an unread window
     *                   is false)
     * @param canAct     whether this dialog's still generator may write a depth map
     *                   for the row — {@link DepthSource.Answer#canReplace()}, which
     *                   is false for an embedded map and for a clip's track. Those
     *                   two are BUILT states with nothing to offer, and saying so
     *                   is the point: the fix line refuses them for the same reason.
     * @param building   whether a depth run for this row is in flight
     * @param failed     whether the row's last depth run failed
     */
    public static Answer forRelief(boolean hasDepth, boolean canAct,
                                   boolean building, boolean failed) {
        if (building) return new Answer(Kind.RELIEF, State.BUILDING, Action.NONE);
        if (failed) return new Answer(Kind.RELIEF, State.FAILED, Action.REGENERATE);
        if (!canAct) {
            // Embedded map or clip track: it HAS depth, but not one this dialog can
            // write. The state is BUILT; the action is nothing.
            return new Answer(Kind.RELIEF, hasDepth ? State.BUILT : State.NONE, Action.NONE);
        }
        if (hasDepth) return new Answer(Kind.RELIEF, State.BUILT, Action.REGENERATE);
        return new Answer(Kind.RELIEF, State.NONE, Action.BUILD);
    }
}
