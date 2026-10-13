package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Is there any 3D here", combined from the two sources that can supply it.
 *
 * <p><b>The trap is the same one both its inputs carry, and it is the reason this
 * class exists rather than a line of boolean arithmetic at the call site.</b> A row
 * with a splat cloud and no depth map read "No depth" while the renderer drew a
 * Gaussian cloud: the line was derived from {@link DepthSource} alone. So the
 * question is <b>relief OR splat</b>, and it has a third answer for the window
 * before either source has been read — because answering "no 3D" during that window
 * is the confident wrong answer this project keeps paying for, with a new noun.
 *
 * <p>And a <b>known positive beats the other source's window</b>: a row that has a
 * depth map has 3D whether or not its cloud has been stat'd, so a pending read must
 * not blank a line about a picture already on screen.
 */
public class Scene3DTest {

    private static final String ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    /** A row with a separate depth file. */
    private static LibraryItem withDepth() {
        return LibraryItem.of(ID, "a.png", "a_depth.png", 1L);
    }

    /** A row with no depth file at all. */
    private static LibraryItem noDepth() {
        return LibraryItem.of(ID, "a.png", null, 1L);
    }

    /** The depth answer for a row, with its medium resolved and the PNG read. */
    private static DepthSource.Answer depth(LibraryItem item, Boolean embedded) {
        return DepthSource.forItem(item, embedded, VideoSource.forItem(item, null));
    }

    /** The depth answer while the PNG's chunk has not been read. */
    private static DepthSource.Answer depthUnread(LibraryItem item) {
        return depth(item, null);
    }

    /** The cloud answer for a row whose file has been read. */
    private static SplatSource.Answer cloud(LibraryItem item, boolean present) {
        return SplatSource.forItem(item, Boolean.valueOf(present));
    }

    /** Neither a map nor a cloud: the one state that may say "no 3D". */
    @Test
    public void neitherSaysNo3d() {
        Scene3D.Answer a = Scene3D.resolve(depth(noDepth(), Boolean.FALSE), cloud(noDepth(), false));
        assertEquals(Scene3D.Kind.NONE, a.kind());
        assertTrue(a.reportsNo3d());
        assertFalse(a.has3d());
        assertFalse(a.isUnknown());
    }

    /**
     * The reported bug: a cloud with no depth map is 3D.
     *
     * <p>This is the whole reason the class exists. The depth answer is
     * {@link DepthSource.Kind#NONE} — there is genuinely no map — and the row must
     * still be reported as having 3D, because the renderer is drawing a cloud.
     */
    @Test
    public void aCloudWithNoDepthMapIsStill3d() {
        LibraryItem item = LibraryItem.of(ID, "a.png", null, 1L).withSplat("a.splat");
        Scene3D.Answer a = Scene3D.resolve(depth(item, Boolean.FALSE), cloud(item, true));
        assertEquals(Scene3D.Kind.PRESENT, a.kind());
        assertTrue(a.has3d());
        assertFalse("a row with a cloud must never say \"no 3D\"", a.reportsNo3d());
    }

    /** A map with no cloud is 3D, and so is both together. */
    @Test
    public void aDepthMapIs3dWithOrWithoutACloud() {
        LibraryItem map = withDepth();
        assertEquals(Scene3D.Kind.PRESENT,
                Scene3D.resolve(depth(map, Boolean.FALSE), cloud(map, false)).kind());
        LibraryItem both = withDepth().withSplat("a.splat");
        assertEquals(Scene3D.Kind.PRESENT,
                Scene3D.resolve(depth(both, Boolean.FALSE), cloud(both, true)).kind());
    }

    /** An embedded map is a map: it is depth, so it is 3D. */
    @Test
    public void anEmbeddedMapIs3d() {
        LibraryItem item = noDepth();
        assertEquals(Scene3D.Kind.PRESENT,
                Scene3D.resolve(depth(item, Boolean.TRUE), cloud(item, false)).kind());
    }

    /** A clip's depth is a track: the row plays in 3D, so it is 3D. */
    @Test
    public void aClipIs3d() {
        LibraryItem clip = LibraryItem.of(ID, "a.png", null, 1L)
                .withVideo(VideoTracks.of("c.mp4", "d.mp4"));
        VideoSource.Answer video = VideoSource.forItem(clip, Boolean.TRUE);
        Scene3D.Answer a = Scene3D.resolve(
                DepthSource.forItem(clip, Boolean.FALSE, video), cloud(clip, false));
        assertEquals(Scene3D.Kind.PRESENT, a.kind());
        assertFalse(a.reportsNo3d());
    }

    /**
     * The window claims nothing — and neither input may shortcut it.
     *
     * <p>An unread PNG chunk and an unread cloud file are both "not known yet", and
     * a row in either window must not be told it has no 3D.
     */
    @Test
    public void eitherWindowClaimsNothing() {
        LibraryItem still = noDepth();
        Scene3D.Answer depthWindow =
                Scene3D.resolve(depthUnread(still), cloud(still, false));
        assertTrue("an unread PNG chunk is not a negative", depthWindow.isUnknown());
        assertFalse(depthWindow.reportsNo3d());

        LibraryItem cloudRow = noDepth().withSplat("a.splat");
        Scene3D.Answer cloudWindow = Scene3D.resolve(
                depth(cloudRow, Boolean.FALSE), SplatSource.forItem(cloudRow, null));
        assertTrue("an unread cloud file is not a negative", cloudWindow.isUnknown());
        assertFalse(cloudWindow.reportsNo3d());
    }

    /**
     * A known positive wins over the other source's window.
     *
     * <p>The row has a depth map and its cloud has not been read. It is 3D, and
     * saying nothing (the {@code UNKNOWN} answer) would blank a line about a picture
     * already on screen — the failure the window rule is meant to prevent, run
     * backwards.
     */
    @Test
    public void aKnownPositiveWinsOverTheOtherWindow() {
        LibraryItem mapWithCloud = withDepth().withSplat("a.splat");
        Scene3D.Answer a = Scene3D.resolve(depth(mapWithCloud, Boolean.FALSE),
                SplatSource.forItem(mapWithCloud, null));
        assertEquals(Scene3D.Kind.PRESENT, a.kind());
        assertTrue(a.has3d());

        LibraryItem cloudWithUnreadPng = noDepth().withSplat("a.splat");
        Scene3D.Answer b = Scene3D.resolve(depthUnread(cloudWithUnreadPng),
                cloud(cloudWithUnreadPng, true));
        assertEquals("a known cloud beats an unread PNG", Scene3D.Kind.PRESENT, b.kind());
    }

    /** Nothing selected is the packaged pair, which has a map: it is 3D and silent. */
    @Test
    public void noSelectionIs3d() {
        Scene3D.Answer a = Scene3D.resolve(DepthSource.forItem(null, null, null),
                SplatSource.forItem(null, null));
        assertEquals(Scene3D.Kind.PRESENT, a.kind());
        assertFalse(a.reportsNo3d());
    }

    /** A caller that has not resolved one of the sources claims nothing. */
    @Test
    public void anUnresolvedSourceClaimsNothing() {
        assertTrue(Scene3D.resolve(null, SplatSource.UNREAD).isUnknown());
        assertTrue(Scene3D.resolve(DepthSource.UNKNOWN, null).isUnknown());
        assertTrue(Scene3D.resolve(null, null).isUnknown());
    }

    /** Only NONE may say "no 3D"; every kind is reachable. */
    @Test
    public void onlyNoneSaysNo3dAndEveryKindIsReachable() {
        for (Scene3D.Kind kind : Scene3D.Kind.values()) {
            Scene3D.Answer a = answerFor(kind);
            assertEquals(kind, a.kind());
            assertEquals("only NONE reports no 3D: " + kind,
                    kind == Scene3D.Kind.NONE, a.reportsNo3d());
            assertEquals("only PRESENT has 3D: " + kind,
                    kind == Scene3D.Kind.PRESENT, a.has3d());
            assertEquals("only UNKNOWN is unknown: " + kind,
                    kind == Scene3D.Kind.UNKNOWN, a.isUnknown());
        }
    }

    private static Scene3D.Answer answerFor(Scene3D.Kind kind) {
        LibraryItem none = noDepth();
        LibraryItem map = withDepth();
        switch (kind) {
            case UNKNOWN: return Scene3D.resolve(depthUnread(none), cloud(none, false));
            case PRESENT: return Scene3D.resolve(depth(map, Boolean.FALSE), cloud(map, false));
            case NONE:
            default:      return Scene3D.resolve(depth(none, Boolean.FALSE), cloud(none, false));
        }
    }

    /** The two fixed answers are singletons, like their siblings'. */
    @Test
    public void theAnswersAreSingletons() {
        assertEquals(Scene3D.UNKNOWN, Scene3D.resolve(null, null));
        assertEquals(Scene3D.resolve(depth(noDepth(), Boolean.FALSE), cloud(noDepth(), false)),
                Scene3D.resolve(depth(noDepth(), Boolean.FALSE), cloud(noDepth(), false)));
    }
}
