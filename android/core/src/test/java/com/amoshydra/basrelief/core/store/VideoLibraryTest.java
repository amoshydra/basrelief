package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * A clip as a row in the gallery: does the index own it the way it owns a
 * photograph?
 *
 * <p><b>The property everything else rests on is that a clip is not a second kind of
 * row.</b> The index owns the selection and only the index — two copies of a pointer
 * can disagree, and a disagreeing pointer is a wallpaper showing a photo the user
 * deleted. A separate container of clips would have made that possible for the
 * medium as well as for the selection: the selection could name a photograph while a
 * second structure said it was a video, and the two disagreeing would be invisible
 * until the home screen was wrong.
 *
 * <p>So a clip is one {@link LibraryItem} with one extra nullable reference, and the
 * index, the selection, the sort, the archive flag and the playlists need no branch
 * of their own. These tests are what holds that claim: they assert the ordinary
 * machinery reaches the right answer for a row that happens to be a clip, rather than
 * asserting it in prose.
 *
 * <p>The one decision the documents do not make is pinned in
 * {@link #aClipWhoseFilesAreGoneKeepsItsRowAndReadsAsAPhotograph()}: a missing clip
 * degrades to the poster frame rather than dropping the row.
 */
public class VideoLibraryTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static LibraryItem photo(String id, long addedAt) {
        return LibraryItem.of(id, LibraryItem.imageFileName(id),
                LibraryItem.depthFileName(id), addedAt);
    }

    private static LibraryItem clip(String id, long addedAt) {
        return photo(id, addedAt).withVideo(VideoTracks.of(
                LibraryItem.videoFileName(id), LibraryItem.videoDepthFileName(id)));
    }

    private static LibraryItem shadedClip(String id, long addedAt) {
        return photo(id, addedAt).withVideo(VideoTracks.of(
                LibraryItem.videoFileName(id), LibraryItem.videoDepthFileName(id),
                LibraryItem.videoNormalFileName(id)));
    }

    private static Library libraryOf(LibraryItem... items) {
        Library lib = Library.empty();
        for (LibraryItem i : items) lib = lib.withItem(i);
        return lib;
    }

    // ---------------------------------------------------------------- the round trip

    /**
     * A clip survives the whole index blob with its <b>selection</b> intact.
     *
     * <p>Both halves, because they are separate risks. The clips could be lost by a
     * parse that does not read the new key — a row that silently becomes a photograph,
     * which looks like a bug report of "my video is a photo". And the selection could
     * be lost by the pointer validation in {@link Library#of}, which drops an id that
     * does not resolve; that one is invisible on a photograph because a photograph is
     * indistinguishable from a degraded clip.
     */
    @Test
    public void aClipSurvivesTheIndexRoundTripWithItsSelectionIntact() {
        Library before = libraryOf(photo("a", 10L), clip("b", 20L), shadedClip("c", 30L))
                .withSelection("b");

        Library after = Library.parse(before.toJson());

        assertEquals("the selection is the index's, and it must survive", "b",
                after.activeId());
        LibraryItem back = after.find("b");
        assertNotNull("a clip must not parse into nothing", back);
        assertTrue(back.isVideo());
        assertEquals(LibraryItem.videoFileName("b"), back.video.color);
        assertEquals(LibraryItem.videoDepthFileName("b"), back.video.depth);
        assertNull("a clip with no normal track stays flat lit, not invented", back.video.normal);

        LibraryItem shaded = after.find("c");
        assertNotNull(shaded);
        assertEquals(LibraryItem.videoNormalFileName("c"), shaded.video.normal);
        assertFalse("and the photograph beside it is still a photograph",
                after.find("a").isVideo());
    }

    /**
     * A library of photographs is byte-identical to one this build never wrote, which
     * is why clips needed no schema version.
     *
     * <p>The same argument the {@code normal} and {@code archived} keys already make,
     * and asserted as a <i>property</i> rather than a style choice: a key written as
     * {@code null} instead of absent would make every existing user's index change
     * shape on upgrade, which is how a migration gets written for nothing.
     */
    @Test
    public void aLibraryOfPhotographsWritesNoVideoKeyAtAll() {
        Library lib = libraryOf(photo("a", 10L)).withSelection("a");
        assertFalse("a photograph must not write a video key",
                lib.toJson().contains("\"video\""));
        assertEquals("and it round-trips byte for byte", lib.toJson(),
                Library.parse(lib.toJson()).toJson());
    }

    /** An old blob has no video key and every row in it is a photograph. */
    @Test
    public void anOldBlobWithNoVideoKeyParsesAsPhotographs() {
        Library after = Library.parse("{\"version\":3,\"items\":["
                + "{\"id\":\"a\",\"image\":\"item-a.png\",\"added_at\":1}]}");
        LibraryItem item = after.find("a");
        assertNotNull(item);
        assertNull(item.video);
        assertFalse(item.isVideo());
    }

    /**
     * A blob that names half a clip is a photograph, not a broken row.
     *
     * <p>The degradation is deliberate and it has to happen at the parse: a truncated
     * or hand-edited blob must not produce a row claiming a depth track that is not
     * there. Dropping the row instead would take the user's photo with it.
     */
    @Test
    public void aHalfWrittenClipInABlobDegradesToItsPoster() {
        Library after = Library.parse("{\"version\":3,\"items\":["
                + "{\"id\":\"a\",\"image\":\"item-a.png\",\"added_at\":1,"
                + "\"video\":{\"color\":\"item-a.mp4\"}}]}");
        LibraryItem item = after.find("a");
        assertNotNull("the photo must survive", item);
        assertFalse("and it is a photograph", item.isVideo());
    }

    // ------------------------------------------------------------ the single-field trap

    /**
     * <b>No write may drop a user's clip.</b> The {@code withDepth} scar, one field
     * over.
     *
     * <p>{@code withDepth} shipped broken once: it reset Motion, brightness and
     * shadow, because those values had been added after it was written and the
     * constructor it used defaulted everything it was not given. A clip is exactly
     * that shape of value — added after the writers existed — so every mutator is
     * asserted over it here rather than trusted.
     */
    @Test
    public void everyMutatorCarriesTheClip() {
        LibraryItem base = shadedClip("a", 1L)
                .withDepthAndNormal("item-a-depth.png", "item-a-normal.png")
                .withIntensity(Surface.HOME, 66)
                .withZoom(Surface.HOME, 133)
                .withMotion(Surface.HOME, 44)
                .withAdjust(Surface.HOME, 12, -9)
                .withLight(Surface.HOME, 200f, 40f)
                .editSettings(Surface.LOCK, s -> s.with(30, 120, 20,
                        0.1f, -0.1f, 5f, -5f, 4, -4, 210f, 40f));

        String color = base.video.color;
        String depth = base.video.depth;

        assertCarries("withIntensity", base.withIntensity(Surface.HOME, 20));
        assertCarries("withZoom", base.withZoom(Surface.HOME, 20));
        assertCarries("withMotion", base.withMotion(Surface.HOME, 20));
        assertCarries("withAdjust", base.withAdjust(Surface.HOME, 1, 1));
        assertCarries("withLight", base.withLight(Surface.HOME, 10f, 10f));
        assertCarries("withPan", base.withPan(Surface.HOME, 1f, 1f));
        assertCarries("withOrigin", base.withOrigin(Surface.HOME, 0.1f, 0.1f));
        assertCarries("withLabel", base.withLabel("Beach"));
        assertCarries("withLastUsedAt", base.withLastUsedAt(99L));
        assertCarries("withArchived", base.withArchived(true));
        assertCarries("withFiles", base.withFiles("b.png", "b-depth.png", "b-normal.png"));
        // The two writers that legitimately change the stills must not disturb it
        // either, because the poster and the clip are one asset.
        assertCarries("withDepth", base.withDepth("b-depth.png"));
        assertCarries("withDepthAndNormal",
                base.withDepthAndNormal("b-depth.png", "b-normal.png"));
        // And the render values really did survive, so the assertions above are not
        // passing because everything was reset to nothing.
        assertEquals(66, base.settings(Surface.HOME).depth);
        assertEquals(44, base.settings(Surface.HOME).motion);
        assertTrue("the lock override is a separate value and it survived too",
                base.hasOwnLockSettings());
    }

    private static void assertCarries(String what, LibraryItem item) {
        assertNotNull(what + " must keep the clip", item.video);
        assertEquals(what + " must keep the colour clip",
                LibraryItem.videoFileName("a"), item.video.color);
        assertEquals(what + " must keep the depth clip",
                LibraryItem.videoDepthFileName("a"), item.video.depth);
        assertEquals(what + " must keep the normal clip",
                LibraryItem.videoNormalFileName("a"), item.video.normal);
    }

    /**
     * The paired write an import needs, and the one that keeps a half-applied pair
     * unrepresentable.
     *
     * <p>A row is written with its poster and its clips in one derivation. An import
     * that wrote the poster and then died would leave a row the user sees as a
     * photograph, with no error anywhere — which is the same class of failure as a
     * depth map written without its normal.
     */
    @Test
    public void thePairedWriteSetsTheStillFilesAndTheClipTogether() {
        LibraryItem item = photo("a", 1L).withFilesAndVideo(
                "item-a.png", "item-a-depth.png", "item-a-normal.png",
                VideoTracks.of("item-a.mp4", "item-a-depth.mp4", "item-a-normal.mp4"));
        assertEquals("item-a.png", item.imageName);
        assertEquals("item-a-depth.png", item.depthName);
        assertEquals("item-a-normal.png", item.normalName);
        assertEquals("item-a.mp4", item.video.color);
        assertEquals("item-a-depth.mp4", item.video.depth);
        assertEquals("item-a-normal.mp4", item.video.normal);

        // And the same writer with no clip is an ordinary import, so an import path
        // does not need a second code path for photographs.
        LibraryItem plain = photo("a", 1L).withFilesAndVideo(
                "item-a.png", "item-a-depth.png", "item-a-normal.png", null);
        assertFalse(plain.isVideo());
    }

    /**
     * Removing a clip is refused, because removing it is a delete.
     *
     * <p>{@code :core} cannot touch the filesystem, so a model that could drop the
     * reference while the three files stayed would orphan them with nothing left that
     * will ever read them. The gesture belongs to the app shell, which owns the
     * delete and can remove the row or the files with it.
     */
    @Test
    public void withVideoRefusesToDropTheClip() {
        try {
            clip("a", 1L).withVideo(null);
            org.junit.Assert.fail("dropping a clip is a delete, not an edit");
        } catch (IllegalArgumentException expected) {
            // the only place a write can be stopped
        }
    }

    // --------------------------------------------------------------- the machinery

    /**
     * A clip is selected, archived, sorted, deleted and rotated exactly as a
     * photograph is.
     *
     * <p>One assertion per piece of machinery, all with the clip as the subject. The
     * point is not that each works — they are the same code — but that <b>none of them
     * needs a branch for a clip</b>, and that is only checkable by asking.
     */
    @Test
    public void aClipIsAnOrdinaryRowToEveryPartOfTheIndex() {
        Library lib = libraryOf(photo("a", 10L), clip("b", 20L), shadedClip("c", 30L))
                .withSelection("b");

        assertTrue("selectable", lib.activeItem().isVideo());
        assertEquals("b", lib.isActive("b") ? "b" : "not b");

        assertEquals("archived like anything else", 1,
                lib.withArchived("b", true).archivedCount());
        assertTrue("and still resolves while hidden", lib.withArchived("b", true).find("b")
                != null);

        // Newest first puts the newest clip on top, the newest photo below it.
        List<LibraryItem> sorted = lib.sortedLive(SortOrder.ADDED_DESC);
        assertEquals("c", sorted.get(0).id);
        assertEquals("b", sorted.get(1).id);
        assertEquals("a", sorted.get(2).id);

        // And a delete of the active clip clears the selection in the same write.
        Library gone = lib.remove("b");
        assertNull(gone.activeId());
        assertNull(gone.find("b"));
    }

    /**
     * A clip's file does not collide with a photograph's, so the dedupe pass cannot
     * fold two different rows together.
     *
     * <p>{@link Library#itemsSharingAnImageName} groups on the <i>image</i> name, and
     * a clip's image is its poster frame. Two rows sharing a poster are the share-path
     * bug it exists to repair; two rows with different posters are two rows, and the
     * repair must leave both alone.
     */
    @Test
    public void theDedupePassCannotFoldTwoClipsIntoOne() {
        Library lib = libraryOf(clip("a", 10L), clip("b", 20L));
        assertEquals("different posters are different rows", 0,
                lib.itemsSharingAnImageName().size());

        // The bug it does repair still applies to clips, because the poster is the
        // file two share-path imports would have collided on. Repaired by handing the
        // second row the FIRST row's poster, which is exactly what the share path did.
        Library shared = libraryOf(
                clip("a", 10L),
                LibraryItem.of("b", LibraryItem.imageFileName("a"),
                        LibraryItem.depthFileName("a"), 20L)
                        .withVideo(VideoTracks.of("item-b.mp4", "item-b-depth.mp4")))
                .withSelection("a");
        assertEquals(shared.itemsSharingAnImageName().toString(), 1,
                shared.itemsSharingAnImageName().size());
        // And the survivor is the active row even though the other is newer, so the
        // selection never has to be re-pointed -- the property that repair already had
        // to earn, and that a clip inherits for free.
        assertEquals(shared.itemsSharingAnImageName().get(0), "b");
        assertEquals("a", shared.activeId());
    }

    /**
     * A playlist may hold a clip, and rotation selects it by the index's own pointer.
     *
     * <p>Rotation writes the selection by the same calls a tap makes and asserts
     * byte-equality with it, so a clip in a playlist is covered by that guarantee for
     * free — <b>provided</b> nothing in the index has to know it is a clip. This is the
     * test that would fail if that stopped being true.
     */
    @Test
    public void aPlaylistCanRotateOntoAClipWithoutASecondMechanism() {
        Playlist playlist = Playlist.create("p", "Morning", Arrays.asList("a", "b"));
        Playlists playlists = Playlists.empty().withPlaylist(playlist)
                .withActive("p", true);
        Library lib = libraryOf(clip("a", 10L), clip("b", 20L)).withSelection("a");

        Playlists.Result r = Playlists.advance(playlists, lib, Surface.HOME, DAY);

        assertEquals("b", r.selectedId);
        // Byte-identical to the write a user's tap would make -- rotation introduced no
        // extra field, and a clip is not one.
        assertEquals(lib.withSelection("b").markUsed("b", DAY).toJson(), r.library.toJson());
        assertTrue("and the row it landed on really is a clip",
                r.library.activeItem().isVideo());
    }

    /**
     * A clip whose files are gone keeps its row and reads as a photograph.
     *
     * <p><b>The decision the documents do not make.</b> §3 step 8 makes the poster
     * frame the fallback when a clip cannot decode, and the restore case (§7: "a video
     * pair is two more filenames and the same rule") is a clip whose files really are
     * missing. So:
     *
     * <ul>
     *   <li>the <b>row</b> is kept, because {@link Library#retainExisting} keys on the
     *       image file and a clip's image is its poster — and dropping the row would
     *       delete a photo the user can still see;
     *   <li>the <b>medium</b> is {@link VideoSource.Kind#POSTER}, so the status line
     *       says the clip is not playing rather than claiming the row is a photograph,
     *       which would send the user to regenerate a depth map that is not what is
     *       missing.
     * </ul>
     */
    @Test
    public void aClipWhoseFilesAreGoneKeepsItsRowAndReadsAsAPhoto() {
        Library lib = libraryOf(photo("a", 10L), clip("b", 20L)).withSelection("b");

        // Only the posters are here; the clips are not.
        Predicate<String> postersOnly = name -> name != null
                && (name.endsWith(".png"));
        Library cleaned = lib.retainExisting(postersOnly);

        assertEquals("the clip's poster is a file, so the row is not missing",
                2, cleaned.size());
        assertNotNull(cleaned.find("b"));
        assertEquals("and the selection still resolves", "b", cleaned.activeId());
        assertTrue("it still names clips -- the index does not lie about what it holds",
                cleaned.find("b").isVideo());

        VideoSource.Answer medium = VideoSource.forItem(cleaned.find("b"), Boolean.FALSE);
        assertEquals(VideoSource.Kind.POSTER, medium.kind());
        assertFalse("but it is not described as a photograph", medium.reportsNoClip());
    }

    /**
     * A clip whose <i>poster</i> is gone is dropped, exactly as a photograph's is.
     *
     * <p>The other half of the same rule: {@code retainExisting} keys on the image
     * file, and for a clip that is the poster. So the retention test is the ordinary
     * one and needs no branch — which is the property the whole shape was chosen for.
     */
    @Test
    public void aClipWhosePosterIsGoneIsDroppedLikeAnyPhotograph() {
        Library lib = libraryOf(photo("a", 10L), clip("b", 20L)).withSelection("b");
        Predicate<String> onlyPhotoA = name -> "item-a.png".equals(name)
                || "item-a-depth.png".equals(name);

        Library cleaned = lib.retainExisting(onlyPhotoA);

        assertEquals(1, cleaned.size());
        assertNull("a dangling selection must not survive", cleaned.activeId());
        assertNull(cleaned.find("b"));
    }

    /** A clip resolves through the same single read path a photograph does. */
    @Test
    public void aClipResolvesThroughTheSameItemTheWallpaperDereferences() {
        Library lib = libraryOf(photo("a", 10L), clip("b", 20L)).withSelection("b");
        LibraryItem active = lib.activeItem();
        assertNotNull(active);
        // One dereference gives the files and the settings together, and cannot come
        // back naming a photo that was deleted.
        assertEquals(LibraryItem.imageFileName("b"), active.imageName);
        assertEquals(LibraryItem.depthFileName("b"), active.depthName);
        assertEquals(90, active.settings(Surface.HOME).depth);
        assertTrue(active.isVideo());
    }

    /**
     * The bundled demo is a trio of PNGs and is refused a clip, from both sides.
     *
     * <p>{@code res/raw} ships three PNGs and nothing else, so a clip on that row
 * names files that cannot exist, and no import path could have put one there. Both
 * halves are asserted because a refusal in only one place is a hole in the other: the
     * writer refuses, and a hand-edited or corrupt blob is ignored on parse.
 *
 * <p>Gated on the reserved id for the same reason the null image name is — see
 * {@link Library#BUNDLED_ID} — so this is one id-gated fact rather than a second
 * special case.
 */
    @Test
    public void theBundledDemoIsRefusedAClip() {
        LibraryItem demo = LibraryItem.bundled(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH);
        assertFalse(demo.isVideo());
        assertEquals(VideoSource.Kind.STILL, VideoSource.forItem(demo, Boolean.TRUE).kind());

        try {
            demo.withVideo(VideoTracks.of("item-bundled.mp4", "item-bundled-depth.mp4"));
            fail("the demo row must not be able to become a clip");
        } catch (IllegalArgumentException expected) {
            // res/raw is three PNGs
        }

        // And the parse ignores the key, so a blob cannot say otherwise.
        Library parsed = Library.parse("{\"version\":3,\"active\":\"" + Library.BUNDLED_ID
                + "\",\"items\":[{\"id\":\"" + Library.BUNDLED_ID + "\",\"image\":\""
                + LibraryTest.DEMO_IMAGE + "\",\"added_at\":1,"
                + "\"video\":{\"color\":\"item-bundled.mp4\","
                + "\"depth\":\"item-bundled-depth.mp4\"}}]}");
        assertNotNull(parsed.find(Library.BUNDLED_ID));
        assertFalse("a blob must not make the marble bust a video",
                parsed.find(Library.BUNDLED_ID).isVideo());
    }
}