package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The one 3D-build dialog's two tabs: which tab a row opens on, what each tab may
 * offer it, and the mode a build writes.
 *
 * <p>The traps this pins are the ones a screenshot cannot show. The tab a build
 * runs on <b>is</b> the mode it writes, so {@link BuildTab#defaultFor} and
 * {@link BuildTab#modeFor} must be inverses or opening a row and pressing the
 * visible button silently flips how it draws. A run in flight must offer nothing,
 * or the user can start a second build over the first. And a failure must outrank
 * a built asset: the row may still hold yesterday's cloud, but the thing the user
 * has to act on is the run that did not land.
 */
public class BuildTabTest {

    // --------------------------------------------------------------- mode ↔ tab

    /** The tab a row opens on is the tab whose build writes its mode. */
    @Test
    public void theDefaultTabNamesTheRowsMode() {
        assertEquals(BuildTab.Kind.SPLAT, BuildTab.defaultFor(RenderMode.SPLAT));
        assertEquals(BuildTab.Kind.RELIEF, BuildTab.defaultFor(RenderMode.RELIEF));
    }

    /**
     * Round trip: the mode a build writes is the mode the default rule reads back.
     *
     * <p>This is the property that keeps the dialog from contradicting the
     * renderer. If it failed, pressing the button the dialog is showing would
     * switch the picture's draw path — the "two answers to one question" failure
     * one level down.
     */
    @Test
    public void buildingOnTheDefaultTabKeepsTheRowsMode() {
        for (RenderMode mode : RenderMode.values()) {
            assertEquals(mode, BuildTab.modeFor(BuildTab.defaultFor(mode)));
        }
    }

    /** A null mode claims nothing and is the relief, like every other absent value. */
    @Test
    public void aNullModeIsTheRelief() {
        assertEquals(BuildTab.Kind.RELIEF, BuildTab.defaultFor(null));
        assertEquals(RenderMode.RELIEF, BuildTab.modeFor(null));
    }

    /** Each tab writes its own mode; the tab you built on is the mode you get. */
    @Test
    public void eachTabWritesItsOwnMode() {
        assertEquals(RenderMode.SPLAT, BuildTab.modeFor(BuildTab.Kind.SPLAT));
        assertEquals(RenderMode.RELIEF, BuildTab.modeFor(BuildTab.Kind.RELIEF));
    }

    // ------------------------------------------------------------------- splat

    /** No cloud is a first build; a cloud is a rebuild. */
    @Test
    public void theSplatTabOffersBuildThenRebuild() {
        BuildTab.Answer none = BuildTab.forSplat(false, false, false);
        assertEquals(BuildTab.State.NONE, none.state());
        assertEquals(BuildTab.Action.BUILD, none.action());
        assertTrue(none.canBuild());

        BuildTab.Answer built = BuildTab.forSplat(true, false, false);
        assertEquals(BuildTab.State.BUILT, built.state());
        assertEquals(BuildTab.Action.REBUILD, built.action());
    }

    /** A failed cloud build is a retry, even over a cloud a previous run left. */
    @Test
    public void aFailedSplatRunOutranksTheCloudItLeftBehind() {
        BuildTab.Answer a = BuildTab.forSplat(true, false, true);
        assertEquals(BuildTab.State.FAILED, a.state());
        assertEquals(BuildTab.Action.REGENERATE, a.action());
    }

    /** A run in flight offers nothing, whatever the row otherwise holds. */
    @Test
    public void aRunningSplatBuildOffersNothing() {
        for (boolean hasCloud : new boolean[] {false, true}) {
            for (boolean failed : new boolean[] {false, true}) {
                BuildTab.Answer a = BuildTab.forSplat(hasCloud, true, failed);
                assertEquals(BuildTab.State.BUILDING, a.state());
                assertEquals(BuildTab.Action.NONE, a.action());
                assertFalse("a run in flight cannot be started again", a.canBuild());
            }
        }
    }

    // ------------------------------------------------------------------ relief

    /** No depth is a first build; a replaceable depth map is a regeneration. */
    @Test
    public void theReliefTabOffersBuildThenRegenerate() {
        BuildTab.Answer none = BuildTab.forRelief(false, true, false, false);
        assertEquals(BuildTab.State.NONE, none.state());
        assertEquals(BuildTab.Action.BUILD, none.action());

        BuildTab.Answer built = BuildTab.forRelief(true, true, false, false);
        assertEquals(BuildTab.State.BUILT, built.state());
        assertEquals(BuildTab.Action.REGENERATE, built.action());
    }

    /**
     * An embedded map and a clip's track are BUILT with nothing to offer.
     *
     * <p>The decision is {@link DepthSource.Answer#canReplace()}: this dialog's
     * still generator may write a depth <i>file</i>, and neither of those rows has
     * a file to write. The fix line refuses them for exactly this reason, so a tab
     * that offered a build here would be a control that could only half-apply —
     * the {@code withDepth} trap wearing a tab.
     */
    @Test
    public void aDepthThisDialogCannotReplaceOffersNothing() {
        // hasDepth=true, canAct=false: the embedded / track case.
        BuildTab.Answer embedded = BuildTab.forRelief(true, false, false, false);
        assertEquals(BuildTab.State.BUILT, embedded.state());
        assertEquals(BuildTab.Action.NONE, embedded.action());
        assertFalse(embedded.canBuild());
    }

    /** An unread window is neither depth nor an action: canAct is false while UNKNOWN. */
    @Test
    public void theUnreadReliefWindowClaimsNothing() {
        BuildTab.Answer a = BuildTab.forRelief(false, false, false, false);
        assertEquals(BuildTab.State.NONE, a.state());
        assertEquals(BuildTab.Action.NONE, a.action());
    }

    /** A failed depth run is a retry; a run in flight offers nothing. */
    @Test
    public void theReliefRunStatesRankAsTheSplatOnesDo() {
        BuildTab.Answer failed = BuildTab.forRelief(true, true, false, true);
        assertEquals(BuildTab.State.FAILED, failed.state());
        assertEquals(BuildTab.Action.REGENERATE, failed.action());

        BuildTab.Answer running = BuildTab.forRelief(true, true, true, true);
        assertEquals(BuildTab.State.BUILDING, running.state());
        assertEquals(BuildTab.Action.NONE, running.action());
    }

    /**
     * The two tabs never disagree about what a state is called.
     *
     * <p>Both resolvers rank run state the same way — building, then failed, then
     * the asset — so a screen can share one `render` switch over the two answers
     * instead of branching on which tab it is. Deriving that twice is how the two
     * tabs would drift into two different meanings of FAILED.
     */
    @Test
    public void bothTabsRankTheRunTheSameWay() {
        assertEquals(BuildTab.forSplat(true, true, false).state(),
                BuildTab.forRelief(true, true, true, false).state());
        assertEquals(BuildTab.forSplat(true, false, true).state(),
                BuildTab.forRelief(true, true, false, true).state());
    }
}
