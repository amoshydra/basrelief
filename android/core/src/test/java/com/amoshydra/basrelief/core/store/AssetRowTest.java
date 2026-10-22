package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The sheet's asset row: the selected mode's state and its primary action, beside
 * the secondary actions that belong to that mode.
 *
 * <p>The traps this pins are the ones a screenshot cannot show, and they are the two
 * halves of the design correction. <b>Only the selected mode's row exists</b>: the
 * same row resolves to a depth row under Relief and a cloud row under Splat, so a
 * cloud's Delete can never appear on a relief row and a depth's Replace can never
 * appear on a cloud row. And <b>the primary is {@link BuildTab}'s own action</b>, not
 * a second ranking: a run in flight offers no button in either mode, a failure offers
 * its retry, and a built asset offers its rebuild — the same answers the dialog's
 * tabs read.
 *
 * <p>It also pins the one asymmetry, which is a decision rather than an accident: a
 * <b>Splat</b> row carries its run (the phase, the server's words) because a cloud
 * build has no fix line, while a <b>Relief</b> row states its asset whatever the run
 * is doing, because the fix line is the depth run's signal and a second copy would be
 * staler.
 */
public class AssetRowTest {

    private static DepthSource.Answer depthFile() {
        return DepthSource.resolve(true, false);
    }

    private static DepthSource.Answer embedded() {
        return DepthSource.resolve(false, true);
    }

    private static DepthSource.Answer track() {
        return DepthSource.resolve(VideoSource.resolve(true, Boolean.TRUE), true, null);
    }

    private static DepthSource.Answer noDepth() {
        return DepthSource.resolve(false, false);
    }

    /** A row in relief mode, with the two tabs' answers a caller would pass. */
    private static AssetRow.Answer relief(BuildTab.Answer relief, DepthSource.Answer depth,
                                          boolean clip) {
        return AssetRow.resolve(RenderMode.RELIEF, relief,
                BuildTab.forSplat(false, false, false), depth, clip);
    }

    /** A row in splat mode, with the two tabs' answers a caller would pass. */
    private static AssetRow.Answer splat(BuildTab.Answer splat) {
        return AssetRow.resolve(RenderMode.SPLAT,
                BuildTab.forRelief(false, true, false, false), splat, noDepth(), false);
    }

    // ------------------------------------------------------------------ relief

    /** No map yet: the row states it and offers Choose a map beside Generate. */
    @Test
    public void aReliefRowWithNoMapOffersChooseThenGenerate() {
        AssetRow.Answer a = relief(BuildTab.forRelief(false, true, false, false), noDepth(), false);
        assertEquals(AssetRow.State.NO_DEPTH, a.state());
        assertEquals(AssetRow.Action.CHOOSE, a.lead());
        assertEquals(AssetRow.Action.BUILD, a.trail());
        assertFalse(a.isNone());
    }

    /** A still depth file: Replace the asset, and Regenerate the build. */
    @Test
    public void aReliefRowWithAFileOffersReplaceThenRegenerate() {
        AssetRow.Answer a = relief(BuildTab.forRelief(true, true, false, false), depthFile(), false);
        assertEquals(AssetRow.State.HAS_DEPTH, a.state());
        assertEquals(AssetRow.Action.REPLACE, a.lead());
        assertEquals(AssetRow.Action.REGENERATE, a.trail());
    }

    /**
     * An embedded map states why there is nothing to build, and offers no button.
     *
     * <p>A sentence is not a control, so this row is not the "control that cannot
     * change anything" the house rule refuses — it is the answer to why the buttons
     * are absent.
     */
    @Test
    public void anEmbeddedMapStatesItselfAndOffersNothing() {
        AssetRow.Answer a = relief(BuildTab.forRelief(true, false, false, false), embedded(), false);
        assertEquals(AssetRow.State.DEPTH_EMBEDDED, a.state());
        assertEquals(AssetRow.Action.NONE, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
        assertFalse("a state sentence keeps the row, which says why there is no button",
                a.isNone());
    }

    /** A clip's track: the row states it and offers its Regenerate, which no tab can. */
    @Test
    public void aClipTrackOffersItsRegenerate() {
        AssetRow.Answer a = relief(BuildTab.forRelief(true, false, false, false), track(), true);
        assertEquals(AssetRow.State.DEPTH_TRACK, a.state());
        assertEquals(AssetRow.Action.REGENERATE_CLIP, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
    }

    /**
     * An unread depth window claims nothing: no sentence and no button, so hidden.
     *
     * <p>This is the {@link DepthSource#UNKNOWN} rule at the row: answering "No depth
     * map yet" during the window is the reported bug this project already paid for.
     */
    @Test
    public void anUnreadDepthWindowIsHidden() {
        AssetRow.Answer a = relief(BuildTab.forRelief(false, false, false, false),
                DepthSource.UNKNOWN, false);
        assertEquals(AssetRow.State.NONE, a.state());
        assertTrue(a.isNone());
    }

    /**
     * A relief row states its asset while a depth run is going, and does not restate
     * the run.
     *
     * <p>The fix line owns a running depth run — it is the only place that states its
     * percentage — so a row repeating it would be a second, staler copy. The row keeps
     * saying what the asset is, and the run's own state just takes the button away.
     */
    @Test
    public void aReliefRunTakesTheButtonButNotTheSentence() {
        AssetRow.Answer running = relief(BuildTab.forRelief(true, true, true, false), depthFile(), false);
        assertEquals(AssetRow.State.HAS_DEPTH, running.state());
        assertEquals(AssetRow.Action.NONE, running.lead());
        assertEquals(AssetRow.Action.NONE, running.trail());

        AssetRow.Answer failed = relief(BuildTab.forRelief(true, true, false, true), depthFile(), false);
        assertEquals(AssetRow.State.HAS_DEPTH, failed.state());
        assertEquals(AssetRow.Action.REGENERATE, failed.trail());
    }

    // ------------------------------------------------------------------- splat

    /** No cloud: the row states it and offers Make one. */
    @Test
    public void aSplatRowWithNoCloudOffersMakeOne() {
        AssetRow.Answer a = splat(BuildTab.forSplat(false, false, false));
        assertEquals(AssetRow.State.NO_CLOUD, a.state());
        assertEquals(AssetRow.Action.BUILD, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
    }

    /** A cloud: Rebuild first, Delete last, because Delete is destructive. */
    @Test
    public void aSplatRowWithACloudOffersRebuildThenDelete() {
        AssetRow.Answer a = splat(BuildTab.forSplat(true, false, false));
        assertEquals(AssetRow.State.CLOUD, a.state());
        assertEquals(AssetRow.Action.REBUILD, a.lead());
        assertEquals(AssetRow.Action.DELETE, a.trail());
    }

    /** A cloud run carries its phase, and offers nothing. */
    @Test
    public void aSplatRunCarriesItsPhaseAndOffersNothing() {
        AssetRow.Answer a = splat(BuildTab.forSplat(true, true, false));
        assertEquals(AssetRow.State.BUILDING, a.state());
        assertEquals(AssetRow.Action.NONE, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
        assertFalse("the phase is a sentence, so the row stays to show it", a.isNone());
    }

    /** A failed cloud build carries the server's words and offers its retry. */
    @Test
    public void aFailedSplatRunOffersItsRegenerate() {
        AssetRow.Answer a = splat(BuildTab.forSplat(true, false, true));
        assertEquals(AssetRow.State.FAILED, a.state());
        assertEquals(AssetRow.Action.REGENERATE, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
    }

    // ------------------------------------------------------------------- shape

    /**
     * The mode decides the row, from one set of facts.
     *
     * <p>The same row with a depth file and a cloud resolves to the depth's row under
     * Relief and the cloud's under Splat. This is the whole of "only the selected
     * mode's row exists", and it is why a cloud's Delete cannot appear on a relief
     * row and a depth's Replace cannot appear on a cloud row.
     */
    @Test
    public void theModeDecidesWhichAssetTheRowDescribes() {
        BuildTab.Answer reliefAnswer = BuildTab.forRelief(true, true, false, false);
        BuildTab.Answer splatAnswer = BuildTab.forSplat(true, false, false);
        AssetRow.Answer underRelief = AssetRow.resolve(RenderMode.RELIEF, reliefAnswer,
                splatAnswer, depthFile(), false);
        AssetRow.Answer underSplat = AssetRow.resolve(RenderMode.SPLAT, reliefAnswer,
                splatAnswer, depthFile(), false);
        assertEquals(AssetRow.State.HAS_DEPTH, underRelief.state());
        assertEquals(AssetRow.State.CLOUD, underSplat.state());
        assertEquals(AssetRow.Action.DELETE, underSplat.trail());
        assertEquals(AssetRow.Action.REPLACE, underRelief.lead());
    }

    /**
     * The primary is {@link BuildTab}'s action, not a second ranking.
     *
     * <p>Walked over every tab state rather than asserted case by case, so a state
     * that gains or loses an action cannot leave the row behind.
     */
    @Test
    public void thePrimaryIsTheTabsOwnAction() {
        for (boolean building : new boolean[] {false, true}) {
            for (boolean failed : new boolean[] {false, true}) {
                for (boolean hasCloud : new boolean[] {false, true}) {
                    BuildTab.Answer answer = BuildTab.forSplat(hasCloud, building, failed);
                    AssetRow.Answer row = splat(answer);
                    assertEquals("the cloud row's build action follows the tab",
                            answer.action(), row.lead().tabAction());
                }
            }
        }
    }

    /** The row's tab is the selected mode's, so the primary opens the right dialog tab. */
    @Test
    public void theRowNamesTheSelectedModesTab() {
        assertEquals(BuildTab.Kind.RELIEF, relief(BuildTab.forRelief(false, true, false, false),
                noDepth(), false).tab());
        assertEquals(BuildTab.Kind.SPLAT, splat(BuildTab.forSplat(false, false, false)).tab());
    }

    /** A null mode claims nothing and is the relief, like every other absent value. */
    @Test
    public void aNullModeIsTheRelief() {
        AssetRow.Answer a = AssetRow.resolve(null, BuildTab.forRelief(false, true, false, false),
                BuildTab.forSplat(true, false, false), noDepth(), false);
        assertEquals(AssetRow.State.NO_DEPTH, a.state());
        assertEquals(BuildTab.Kind.RELIEF, a.tab());
    }

    /** An unresolved row claims nothing, the same "a window claims nothing" rule. */
    @Test
    public void anUnresolvedRowIsHidden() {
        AssetRow.Answer a = AssetRow.resolve(null, null, null, null, false);
        assertTrue(a.isNone());
        assertEquals(AssetRow.Action.NONE, a.lead());
        assertEquals(AssetRow.Action.NONE, a.trail());
    }

    /** The build actions name their tab action; a secondary names none. */
    @Test
    public void onlyTheBuildActionsNameATabAction() {
        assertEquals(BuildTab.Action.BUILD, AssetRow.Action.BUILD.tabAction());
        assertEquals(BuildTab.Action.REBUILD, AssetRow.Action.REBUILD.tabAction());
        assertEquals(BuildTab.Action.REGENERATE, AssetRow.Action.REGENERATE.tabAction());
        assertEquals(BuildTab.Action.NONE, AssetRow.Action.REPLACE.tabAction());
        assertEquals(BuildTab.Action.NONE, AssetRow.Action.CHOOSE.tabAction());
        assertEquals(BuildTab.Action.NONE, AssetRow.Action.DELETE.tabAction());
        assertEquals(BuildTab.Action.NONE, AssetRow.Action.REGENERATE_CLIP.tabAction());
        assertEquals(BuildTab.Action.NONE, AssetRow.Action.NONE.tabAction());
    }
}
