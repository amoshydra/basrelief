package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Shading is separate from displacement, and this is the one resolution of it.
 *
 * <p><b>The state this class adds is the one the app has never had:</b> a photo
 * with depth and no normal. It is displaced correctly and lit as a flat plane.
 * The traps, in the order they can bite:
 *
 * <ol>
 *   <li><b>There must be no derived state.</b> The old derived-normal path is
 *       gone, so an answer that named it would put "shaded" on the sheet while
 *       the shader drew flat — the same two-answers-to-one-question failure
 *       {@link DepthSource} exists to prevent. Only the two real states exist.
 *   <li><b>A normal with no depth is inert</b>, not supplied. There is no
 *       geometry for it to shade, so calling it shaded would describe a render
 *       that cannot happen.
 *   <li><b>Nothing selected is the packaged demo pair</b>, which ships a normal,
 *       so the sheet stays silent rather than offering a fix for a photo that is
 *       already shaded.
 * </ol>
 */
public class NormalSourceTest {

    private static LibraryItem depthOnly(String id) {
        return LibraryItem.of(id, id + ".png", id + "-depth.png", 1L);
    }

    private static LibraryItem shaded(String id) {
        return depthOnly(id).withDepthAndNormal(id + "-depth.png", id + "-normal.png");
    }

    @Test
    public void aDepthWithANormalIsSupplied() {
        NormalSource.Answer a = NormalSource.resolve(true, true);
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertTrue(a.hasSuppliedNormals());
        assertFalse(a.needsNormals());
    }

    /** The new middle state: depth, no normals, flat lit. */
    @Test
    public void aDepthWithoutANormalIsNoneAndNeedsOne() {
        NormalSource.Answer a = NormalSource.resolve(true, false);
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertFalse("flat lit, not shaded", a.hasSuppliedNormals());
        assertTrue("and the sheet may offer the fix", a.needsNormals());
    }

    /**
     * A normal with no depth is inert: the renderer draws no relief without a
     * depth map, so this is not the shaded state however many files it names.
     */
    @Test
    public void aNormalWithoutADepthIsNotSupplied() {
        NormalSource.Answer a = NormalSource.resolve(false, true);
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertFalse(a.hasSuppliedNormals());
    }

    @Test
    public void neitherFileIsNone() {
        assertEquals(NormalSource.Kind.NONE, NormalSource.resolve(false, false).kind());
    }

    @Test
    public void anItemWithBothFilesIsSupplied() {
        NormalSource.Answer a = NormalSource.forItem(shaded("a"));
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertFalse(a.needsNormals());
    }

    @Test
    public void anItemWithOnlyDepthIsFlatLit() {
        NormalSource.Answer a = NormalSource.forItem(depthOnly("a"));
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertTrue(a.needsNormals());
    }

    /** A normal named without a depth is inert on a real item too. */
    @Test
    public void anItemWithANormalButNoDepthIsNotSupplied() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withFiles("a.png", null, "a-normal.png");
        assertFalse(item.hasSuppliedNormals());
        assertEquals(NormalSource.Kind.NONE, NormalSource.forItem(item).kind());
    }

    /**
     * Nothing selected is the packaged demo pair, which ships a normal — so the
     * sheet is silent rather than offering to supply what is already drawn. This
     * mirrors {@link DepthSource#forItem}'s answer for no selection.
     */
    @Test
    public void noSelectionIsTheShadedPackagedPair() {
        NormalSource.Answer a = NormalSource.forItem(null);
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertFalse(a.needsNormals());
    }

    /**
     * Exactly one kind reports supplied, stated over every reachable answer, so a
     * future kind cannot be added without this failing.
     *
     * <p>Superseded in scope by
     * {@link #suppliedIsTheOnlyStateThatReportsShadingAndNeedsIsTheOnlyOneThatDoesNot()},
     * which is the same enumeration with the clip answers and the three-way claim
     * check. Kept because it is the still-only half of the truth table and it fails
     * for a still that regressed.
     */
    @Test
    public void suppliedIsTheOnlyStateThatReportsShadingAmongPhotographs() {
        NormalSource.Answer[] all = {
                NormalSource.resolve(false, false),
                NormalSource.resolve(true, false),
                NormalSource.resolve(false, true),
                NormalSource.resolve(true, true),
                NormalSource.forItem(null),
                NormalSource.forItem(depthOnly("a")),
                NormalSource.forItem(shaded("a")),
        };
        int supplied = 0;
        for (NormalSource.Answer a : all) {
            if (a.hasSuppliedNormals()) {
                supplied++;
                assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
                assertFalse(a.needsNormals());
            } else {
                assertEquals(NormalSource.Kind.NONE, a.kind());
                assertTrue(a.needsNormals());
            }
        }
        assertTrue(supplied > 0);
    }

    @Test
    public void theAnswersAreSingletons() {
        assertSame(NormalSource.resolve(true, true), NormalSource.resolve(true, true));
        assertSame(NormalSource.resolve(true, false), NormalSource.resolve(false, true));
        assertSame(NormalSource.forItem(null), NormalSource.resolve(true, true));
    }

    @Test
    public void eachAnswerNamesItself() {
        assertEquals("NormalSource.NONE", NormalSource.resolve(true, false).toString());
        assertEquals("NormalSource.SUPPLIED", NormalSource.resolve(true, true).toString());
        assertEquals("NormalSource.UNREAD", NormalSource.forItem(clip("a"),
                VideoSource.forItem(clip("a"), null)).toString());
    }

    // ------------------------------------------------------- a clip's shading

    private static LibraryItem clip(String id) {
        return LibraryItem.of(id, id + ".png", id + "-depth.png", 1L)
                .withDepthAndNormal(id + "-depth.png", id + "-normal.png")
                .withVideo(VideoTracks.of(id + ".mp4", id + "-depth.mp4"));
    }

    private static LibraryItem shadedClip(String id) {
        return LibraryItem.of(id, id + ".png", id + "-depth.png", 1L)
                .withDepthAndNormal(id + "-depth.png", id + "-normal.png")
                .withVideo(VideoTracks.of(id + ".mp4", id + "-depth.mp4", id + "-normal.mp4"));
    }

    /**
     * A clip is shaded from its own normal <b>track</b>, never from the poster's PNG.
     *
     * <p>§5 samples the shading normal per fragment for a video; a still normal map
     * describes one frame of a shape that moves. Both rows below carry an identical
     * still trio, so a rule that read {@code normalName} would answer the same for
     * both — and would be wrong about one of them.
     */
    @Test
    public void aPlayingClipIsShadedFromItsOwnNormalTrack() {
        LibraryItem flatClip = clip("a");
        LibraryItem shaded = shadedClip("a");
        assertEquals("the poster files are identical, so only the track differs",
                flatClip.normalName, shaded.normalName);

        NormalSource.Answer playing = NormalSource.forItem(shaded,
                VideoSource.forItem(shaded, Boolean.TRUE));
        assertEquals(NormalSource.Kind.SUPPLIED, playing.kind());
        assertTrue(playing.hasSuppliedNormals());
    }

    /**
     * A clip with no normal track is <b>flat lit</b> — the same deliberate state as a
     * photograph with a map and no normal, and never a derived one.
     */
    @Test
    public void aClipWithNoNormalTrackIsFlatLit() {
        LibraryItem flatClip = clip("a");
        // Its poster is shaded, which is exactly the trap: reading normalName would
        // put "shaded" on the sheet while the shader drew a plane.
        assertTrue("the poster is shaded, and that is not what renders",
                flatClip.hasSuppliedNormals());
        NormalSource.Answer playing = NormalSource.forItem(flatClip,
                VideoSource.forItem(flatClip, Boolean.TRUE));
        assertEquals(NormalSource.Kind.NONE, playing.kind());
        assertFalse(playing.hasSuppliedNormals());
        assertTrue("and the sheet may offer to supply one", playing.needsNormals());
    }

    /**
     * A clip row is unknown until its clips are read, and the line must stay silent.
     *
     * <p>{@code UNREAD} is the state this class did not have and now needs, because
     * its old claim — "nothing has to be read to answer this" — was true for a file
     * the index names and false for a track a decoder has to produce. Folding the
     * window into {@code NONE} would offer "generate normals" over a clip that is
     * about to be shaded from a track, which is the reported "No depth over a
     * photograph with a map" bug with a different verb.
     */
    @Test
    public void aClipRowClaimsNothingAboutItsShadingUntilItIsRead() {
        NormalSource.Answer u = NormalSource.forItem(shadedClip("a"),
                VideoSource.forItem(shadedClip("a"), null));
        assertEquals(NormalSource.Kind.UNREAD, u.kind());
        assertTrue(u.isUnknown());
        assertFalse("not evidence of absence", u.hasSuppliedNormals());
        assertFalse("and must not offer to supply what it may already have",
                u.needsNormals());

        // And the one-argument form cannot have read them either.
        NormalSource.Answer stillOnly = NormalSource.forItem(shadedClip("a"));
        assertTrue("a caller with no clip state must claim nothing", stillOnly.isUnknown());
        assertFalse(stillOnly.needsNormals());
    }

    /**
     * A clip that fell back to its poster is shaded by the poster's PNG, because the
     * poster is what draws.
     */
    @Test
    public void aClipThatFellBackIsShadedByItsPoster() {
        LibraryItem item = shadedClip("a");
        LibraryItem flatPoster = LibraryItem.of("a", "a.png", "a-depth.png", 1L)
                .withVideo(VideoTracks.of("a.mp4", "a-depth.mp4"));

        assertTrue(item.hasSuppliedNormals());
        assertEquals(NormalSource.Kind.SUPPLIED, NormalSource.forItem(item,
                VideoSource.forItem(item, Boolean.FALSE)).kind());
        assertFalse("an unshaded poster is flat lit, not shaded",
                flatPoster.hasSuppliedNormals());
        assertEquals(NormalSource.Kind.NONE, NormalSource.forItem(flatPoster,
                VideoSource.forItem(flatPoster, Boolean.FALSE)).kind());
    }

    /**
     * Exactly one kind reports shading, and {@code UNREAD} is neither shaded nor
     * needing one — stated over every reachable answer, including a clip's, so a
     * future kind cannot be added without this failing.
     *
     * <p>This is the test that earned {@link NormalSource.Kind#UNREAD} its own
     * predicate: the old form asserted that non-supplied <i>was</i> NONE, which is
     * exactly what would have offered to generate normals over every clip on the
     * gallery on every selection.
     */
    @Test
    public void suppliedIsTheOnlyStateThatReportsShadingAndNeedsIsTheOnlyOneThatDoesNot() {
        NormalSource.Answer[] all = {
                NormalSource.resolve(false, false),
                NormalSource.resolve(true, false),
                NormalSource.resolve(false, true),
                NormalSource.resolve(true, true),
                NormalSource.forItem(null),
                NormalSource.forItem(depthOnly("a")),
                NormalSource.forItem(shaded("a")),
                NormalSource.forItem(clip("a"), VideoSource.forItem(clip("a"), Boolean.TRUE)),
                NormalSource.forItem(shadedClip("a"),
                        VideoSource.forItem(shadedClip("a"), Boolean.TRUE)),
                NormalSource.forItem(clip("a"), VideoSource.forItem(clip("a"), null)),
        };
        int supplied = 0;
        int needs = 0;
        int unknown = 0;
        for (NormalSource.Answer a : all) {
            if (a.hasSuppliedNormals()) supplied++;
            if (a.needsNormals()) needs++;
            if (a.isUnknown()) unknown++;
            // Exactly one of the three claims, and never two.
            assertEquals("a clip must not inherit a meaning: " + a, 1,
                    (a.hasSuppliedNormals() ? 1 : 0) + (a.needsNormals() ? 1 : 0)
                            + (a.isUnknown() ? 1 : 0));
        }
        assertTrue("at least one of each must be reachable", supplied > 0 && needs > 0);
        assertEquals("and the window is reachable too", 1, unknown);
    }

    /**
     * A photograph's answer is unchanged by any of this, which is the point of
     * resolving from the medium rather than branching on it.
     */
    @Test
    public void aPhotographAnswersIdenticallyThroughBothForms() {
        for (LibraryItem item : new LibraryItem[]{depthOnly("a"), shaded("a"),
                LibraryItem.of("a", "a.png", null, 1L)}) {
            assertEquals(NormalSource.forItem(item).kind(),
                    NormalSource.forItem(item, VideoSource.forItem(item, Boolean.TRUE)).kind());
            assertEquals(NormalSource.forItem(item).kind(),
                    NormalSource.forItem(item, VideoSource.STILL).kind());
        }
    }
}
