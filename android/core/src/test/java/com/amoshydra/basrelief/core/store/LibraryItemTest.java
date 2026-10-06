package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Pins one library entry.
 *
 * <p>Two themes. The first is that an item is identified by its id and stores
 * bare filenames — the restore case that already cost this project a user's
 * image is the reason, and it is a property of the stored string, so it has to
 * be tested on the stored string.
 *
 * <p>The second is that reading an item out of a blob never throws. The blob is
 * written by an older or newer build, or truncated; a gallery entry that brings
 * the app down is worse than one that is missing.
 */
public class LibraryItemTest {

    private static final String ID = "a1b2c3d4";

    private static LibraryItem sample() {
        return new LibraryItem(ID, "a.png", "a-depth.png", "Sunset", 1700000000000L, 42L);
    }

    private static JSONObject json(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            fail("test JSON should parse: " + raw);
            return null;
        }
    }

    // ---------------------------------------------------------- round trip

    @Test
    public void roundTripsEveryField() {
        LibraryItem r = LibraryItem.fromJson(json(sample().toJson()));
        assertNotNull(r);
        assertEquals(ID, r.id);
        assertEquals("a.png", r.imageName);
        assertEquals("a-depth.png", r.depthName);
        assertEquals("Sunset", r.label);
        assertEquals(1700000000000L, r.addedAt);
        assertEquals(42L, r.lastUsedAt);
    }

    /** Absent fields must stay absent, not become the string "null". */
    @Test
    public void anItemWithNoDepthOrLabelSurvives() {
        LibraryItem flat = LibraryItem.of(ID, "a.png", null, 5L);
        LibraryItem r = LibraryItem.fromJson(json(flat.toJson()));
        assertNotNull(r);
        assertNull(r.depthName);
        assertNull(r.label);
        assertFalse(r.hasDepth());
    }

    // ------------------------------------------------------------ filenames

    /**
     * The bug this class exists to prevent, at the item level.
     *
     * <p>An absolute path written by an older build, or produced by a restore,
     * must not be stored as one: it names the previous device's filesDir and the
     * photo silently disappears.
     */
    @Test
    public void anAbsolutePathIsStoredAsABareFilename() {
        LibraryItem item = new LibraryItem(ID, "/data/user/0/com.amoshydra.basrelief/files/a.png",
                null, null, 1L, 0L);
        assertEquals("a.png", item.imageName);
        assertFalse(item.imageName.contains("/"));
    }

    /** The same rule applied to a read value, since parse is where old data enters. */
    @Test
    public void aRestoredAbsolutePathInAStoredItemIsReducedToItsBasename() {
        LibraryItem r = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"/old/device/files/a.png\"}"));
        assertNotNull(r);
        assertEquals("a.png", r.imageName);
    }

    /** A corrupt blob must not be able to name a file outside filesDir. */
    @Test
    public void aTraversalCollapsesToABasename() {
        assertEquals("x.png", LibraryItem.normaliseName("../x.png"));
        assertEquals("x.png", LibraryItem.normaliseName("a/b/x.png"));
        assertEquals("x.png", LibraryItem.normaliseName("/a/b/x.png"));
    }

    /**
     * A backslash is not a separator on the filesystem that stores these files,
     * so it is rejected rather than treated as one. Collapsing it would be
     * guessing at a path the writer did not mean.
     */
    @Test
    public void aWindowsSeparatorIsRejectedRatherThanGuessedAt() {
        assertNull(LibraryItem.normaliseName("..\\x.png"));
        assertNull(LibraryItem.normaliseName("a\\b.png"));
    }

    /**
     * Names that cannot become a file. Each would parse, be stored, and then fail
     * somewhere else — {@code .} and {@code ..} name directories, a NUL makes
     * every {@code open()} throw FileNotFoundException, and a backslash survives
     * {@code new File(x).getName()} on Linux and only becomes a separator on a
     * later device.
     */
    @Test
    public void unusableNamesAreRejected() {
        for (String bad : new String[]{null, "", ".", "..", "/", "a\u0000b", "x\\y"}) {
            assertNull("should be rejected: " + bad, LibraryItem.normaliseName(bad));
        }
        // a directory part is not a rejection, it is a collapse
        assertEquals("dir", LibraryItem.normaliseName("sub/dir"));
    }

    /** An item with no image is not an item: there is nothing to render or resolve. */
    @Test
    public void anItemWithNoImageIsUnusable() {
        assertNull(LibraryItem.fromJson(json("{\"id\":\"" + ID + "\"}")));
        assertNull(LibraryItem.fromJson(json("{\"id\":\"" + ID + "\",\"image\":\"\"}")));
        assertNull(LibraryItem.fromJson(json("{\"id\":\"" + ID + "\",\"image\":\"..\"}")));
    }

    /**
     * A non-string id means the blob is not what this build writes. Guessing a
     * value would risk colliding with a real id later, so the entry is dropped.
     */
    @Test
    public void anIdThatIsNotAStringIsUnusable() {
        assertNull(LibraryItem.fromJson(json("{\"image\":\"a.png\"}")));
        assertNull(LibraryItem.fromJson(json("{\"id\":7,\"image\":\"a.png\"}")));
        assertNull(LibraryItem.fromJson(json("{\"id\":null,\"image\":\"a.png\"}")));
        assertNull(LibraryItem.fromJson(json("{\"id\":{\"a\":1},\"image\":\"a.png\"}")));
        assertNull(LibraryItem.fromJson(null));
    }

    @Test
    public void idValidationRejectsSeparatorsAndBlanks() {
        assertTrue(LibraryItem.isValidId(ID));
        assertFalse(LibraryItem.isValidId(null));
        assertFalse(LibraryItem.isValidId(""));
        assertFalse(LibraryItem.isValidId("   "));
        assertFalse(LibraryItem.isValidId("a/b"));
        assertFalse(LibraryItem.isValidId("a\\b"));
    }

    /**
     * Two items must never share a file. The single-slot names in use today are
     * overwritten by the next import, and an index with two entries pointing at
     * one file would render one photo twice and lose the other.
     */
    @Test
    public void generatedFileNamesAreUniquePerItem() {
        String a = LibraryItem.imageFileName("id-one");
        String b = LibraryItem.imageFileName("id-two");
        String aDepth = LibraryItem.depthFileName("id-one");
        Set<String> names = new HashSet<String>();
        for (String n : new String[]{a, b, aDepth, LibraryItem.depthFileName("id-two")}) {
            assertEquals("not a bare filename: " + n, n, LibraryItem.normaliseName(n));
            assertTrue("two items would share a file: " + n, names.add(n));
        }
        assertFalse("an image and its depth must not collide", a.equals(aDepth));
    }

    @Test
    public void generatedFileNamesRejectAnUnusableId() {
        try {
            LibraryItem.imageFileName("../evil");
            fail("a traversal id must not produce a filename");
        } catch (IllegalArgumentException expected) {
            // the only way to stop this at the call site
        }
    }

    // ------------------------------------------------------------ identity

    /**
     * A timestamp is not an id. Two photos added in the same millisecond — one
     * picker callback, a bulk import, a replayed restore — would collide, and the
     * second would overwrite the first.
     */
    @Test
    public void mintedIdsDoNotCollide() {
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 2000; i++) {
            String id = LibraryItem.newId();
            assertTrue(LibraryItem.isValidId(id));
            assertTrue("minted a duplicate id: " + id, ids.add(id));
        }
    }

    // -------------------------------------------------------------- derived

    @Test
    public void displayNameFallsBackToTheFilename() {
        assertEquals("Sunset", sample().displayName());
        assertEquals("a.png", LibraryItem.of(ID, "a.png", null, 0L).displayName());
    }

    /** Negative stamps from a corrupt blob would sort ahead of everything, forever. */
    @Test
    public void timestampsAreClampedToNonNegative() {
        LibraryItem r = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"added_at\":-5,\"last_used_at\":-1}"));
        assertNotNull(r);
        assertEquals(0L, r.addedAt);
        assertEquals(0L, r.lastUsedAt);
    }

    /** A timestamp that is not a number is "unknown", not a crash. */
    @Test
    public void aNonNumericTimestampReadsAsZero() {
        LibraryItem r = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"added_at\":\"soon\"}"));
        assertNotNull(r);
        assertEquals(0L, r.addedAt);
    }

    @Test
    public void aTimestampBeyondLongRangeIsClampedByTheParser() {
        LibraryItem r = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"added_at\":1e300}"));
        assertNotNull(r);
        assertTrue("must not become negative: " + r.addedAt, r.addedAt >= 0L);
    }

    // ------------------------------------------------- single-field updates

    /** The read-modify-write failure mode: a setter must not reset a sibling. */
    @Test
    public void mutatorsCarryEveryOtherField() {
        LibraryItem s = sample();
        LibraryItem withDepth = s.withDepth("b-depth.png");
        assertEquals("b-depth.png", withDepth.depthName);
        assertEquals(s.id, withDepth.id);
        assertEquals(s.imageName, withDepth.imageName);
        assertEquals(s.label, withDepth.label);
        assertEquals(s.addedAt, withDepth.addedAt);
        assertEquals(s.lastUsedAt, withDepth.lastUsedAt);

        LibraryItem used = s.withLastUsedAt(99L);
        assertEquals(99L, used.lastUsedAt);
        assertEquals(s.addedAt, used.addedAt);
        assertEquals(s.depthName, used.depthName);

        LibraryItem relabelled = s.withLabel("Beach");
        assertEquals("Beach", relabelled.label);
        assertEquals(s.imageName, relabelled.imageName);
    }

    // ------------------------------------------------------- supplied normals

    /**
     * A normal name is a bare filename and rides the same rules as the image and
     * the depth: absent when null, and a path is reduced to its basename so a
     * restore onto another device cannot keep a path into the old filesDir.
     */
    @Test
    public void normalNameSurvivesARoundTripAndIsAbsentWhenNull() {
        LibraryItem shaded = sample().withDepthAndNormal("a-depth.png", "a-normal.png");
        assertEquals("a-normal.png", shaded.normalName);
        LibraryItem back = LibraryItem.fromJson(json(shaded.toJson()));
        assertNotNull(back);
        assertEquals("a-normal.png", back.normalName);
        assertTrue(back.hasSuppliedNormals());

        // Absent rather than null, so a flat-lit library stays byte-identical to
        // one an older build wrote.
        LibraryItem flat = sample();
        assertNull(flat.normalName);
        assertFalse("no normal, no key", flat.toJson().contains("normal"));
        assertNull(LibraryItem.fromJson(json(flat.toJson())).normalName);

        // The literal "null" heals to absent, like every other name.
        LibraryItem literal = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"added_at\":1,"
                        + "\"depth\":\"a-depth.png\",\"normal\":\"null\"}"));
        assertNotNull(literal);
        assertNull(literal.normalName);
    }

    /**
     * <b>A new depth invalidates the old normal, and this pins that.</b> A normal
     * predicts one depth map's surface; keeping it across a depth replacement is
     * shading that disagrees with the geometry, which is worse than going flat.
     */
    @Test
    public void withDepthClearsTheSuppliedNormal() {
        LibraryItem shaded = sample().withDepthAndNormal("a-depth.png", "a-normal.png");
        LibraryItem remapped = shaded.withDepth("b-depth.png");
        assertEquals("b-depth.png", remapped.depthName);
        assertNull("the old normal describes the old shape", remapped.normalName);
        assertFalse(remapped.hasSuppliedNormals());
    }

    /** The paired writer is the only way both are set, and sets both together. */
    @Test
    public void withDepthAndNormalSetsBothAndCarriesEverythingElse() {
        LibraryItem base = sample()
                .withIntensity(Surface.HOME, 70)
                .withMotion(Surface.HOME, 55);
        LibraryItem paired = base.withDepthAndNormal("d.png", "n.png");
        assertEquals("d.png", paired.depthName);
        assertEquals("n.png", paired.normalName);
        assertEquals(70, paired.settings(Surface.HOME).depth);
        assertEquals(55, paired.settings(Surface.HOME).motion);
        assertEquals(base.label, paired.label);
        assertEquals(base.imageName, paired.imageName);

        // A classical run passes no normal, and that is the same as withDepth.
        LibraryItem flat = base.withDepthAndNormal("d.png", null);
        assertNull(flat.normalName);
        assertFalse(flat.hasSuppliedNormals());
    }

    /**
     * The single-field-edit trap, including the new field: a setter for one render
     * value must not reset another, and a depth write must not be reached from a
     * brightness slider.
     *
     * <p>This shipped broken once — {@code withDepth} reset Motion, brightness and
     * shadow because the values it did not pass had been added after it was
     * written — so it is asserted over the whole set rather than one convenient
     * pair.
     */
    @Test
    public void aSingleFieldEditResetsNothingItDoesNotName() {
        LibraryItem full = sample()
                .withDepthAndNormal("d.png", "n.png")
                .withIntensity(Surface.HOME, 66)
                .withZoom(Surface.HOME, 133)
                .withMotion(Surface.HOME, 44)
                .withAdjust(Surface.HOME, 12, -9)
                .withLight(Surface.HOME, 200f, 40f);

        LibraryItem brightened = full.withAdjust(Surface.HOME, 20, -9);
        assertEquals("d.png", brightened.depthName);
        assertEquals("n.png", brightened.normalName);
        assertEquals(66, brightened.settings(Surface.HOME).depth);
        assertEquals(133, brightened.settings(Surface.HOME).zoom);
        assertEquals(44, brightened.settings(Surface.HOME).motion);
        assertEquals(200f, brightened.settings(Surface.HOME).lightAzimuth, 1e-6f);

        LibraryItem relit = full.withLight(Surface.HOME, 210f, 35f);
        assertEquals("n.png", relit.normalName);
        assertEquals(44, relit.settings(Surface.HOME).motion);
        assertEquals(20, brightened.settings(Surface.HOME).brightness);

        LibraryItem motioned = full.withMotion(Surface.HOME, 10);
        assertEquals("n.png", motioned.normalName);
        assertEquals(133, motioned.settings(Surface.HOME).zoom);
    }

    /**
     * An old blob with no normal key parses with null, which is the flat-lit
     * state — the correct degradation, and the reason this needed no version bump.
     */
    @Test
    public void anOldBlobWithNoNormalKeyParsesFlat() {
        LibraryItem old = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"depth\":\"a-depth.png\","
                        + "\"added_at\":1}"));
        assertNotNull(old);
        assertNull(old.normalName);
        assertFalse("depth with no normal is flat lit", old.hasSuppliedNormals());
    }

    /** A normal name is reduced to a basename like every other stored filename. */
    @Test
    public void normalNameIsStrippedToItsBasename() {
        LibraryItem item = sample().withDepthAndNormal("d.png", "/old/device/files/n.png");
        assertEquals("n.png", item.normalName);
    }

    // -------------------------------------------------------------- archive

    /**
     * The flag survives a round trip, and a live item does not write the key.
     *
     * <p>The second half is a size/byte-stability property and not a style
     * choice: a library with nothing archived must serialise exactly as it did
     * before this feature existed, so the key is absent rather than false.
     */
    @Test
    public void archiveSurvivesARoundTripAndIsAbsentWhenFalse() {
        LibraryItem live = sample();
        assertFalse(live.isArchived());
        assertFalse("a live item must not write an archived key",
                live.toJson().contains("archived"));

        LibraryItem hidden = live.withArchived(true);
        assertTrue(hidden.isArchived());
        LibraryItem back = LibraryItem.fromJson(json(hidden.toJson()));
        assertNotNull(back);
        assertTrue("the archive flag must survive a round trip", back.isArchived());

        // and restoring is the same bit off, through the same round trip
        LibraryItem shown = back.withArchived(false);
        assertFalse(shown.isArchived());
        assertFalse(shown.toJson().contains("archived"));
    }

    /**
     * An old v3 blob has no archived key at all, and must parse as live.
     *
     * <p>This is the whole reason the feature needed no version bump and no
     * migration: {@code optBoolean("archived", false)} reads the absent key as
     * live, so an upgraded user's photos all stay in the gallery.
     */
    @Test
    public void anOldBlobWithNoArchivedKeyParsesAsLive() {
        LibraryItem old = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"added_at\":1}"));
        assertNotNull(old);
        assertFalse("absent means live", old.isArchived());
    }

    /**
     * Archiving is an identity-only edit: it must carry every render value and
     * the lock override.
     *
     * <p>The {@code withDepth} trap again, one field over. A {@code withArchived}
     * built from a constructor naming only the identity fields would reset the
     * eight settings and the lock override.
     */
    @Test
    public void withArchivedCarriesEveryRenderValueAndTheLockOverride() {
        LibraryItem full = sample()
                .withIntensity(Surface.HOME, 70)
                .withZoom(Surface.HOME, 140)
                .withMotion(Surface.HOME, 55)
                .withAdjust(Surface.HOME, -12, 18)
                .withSettings(Surface.LOCK, sample().settings(Surface.LOCK)
                        .with(30, 120, 20, 0.1f, -0.1f, 5f, -5f, 4, -4, 210f, 40f,
                                SurfaceSettings.DEFAULT_SPEED));

        LibraryItem hidden = full.withArchived(true);
        assertTrue(hidden.hasOwnLockSettings());
        assertEquals(70, hidden.settings(Surface.HOME).depth);
        assertEquals(140, hidden.settings(Surface.HOME).zoom);
        assertEquals(55, hidden.settings(Surface.HOME).motion);
        assertEquals(-12, hidden.settings(Surface.HOME).brightness);
        assertEquals(18, hidden.settings(Surface.HOME).shadow);
        assertEquals(30, hidden.settings(Surface.LOCK).depth);
        assertEquals(210f, hidden.settings(Surface.LOCK).lightAzimuth, 1e-6f);

        // Every other identity field, too.
        assertEquals(full.id, hidden.id);
        assertEquals(full.imageName, hidden.imageName);
        assertEquals(full.depthName, hidden.depthName);
        assertEquals(full.label, hidden.label);
        assertEquals(full.addedAt, hidden.addedAt);
        assertEquals(full.lastUsedAt, hidden.lastUsedAt);
    }

    /** Setting the value it already is returns the same instance, not a write. */
    @Test
    public void withArchivedIsIdempotent() {
        LibraryItem live = sample();
        assertSame(live, live.withArchived(false));
        LibraryItem hidden = live.withArchived(true);
        assertSame(hidden, hidden.withArchived(true));
    }

    // --------------------------------------------------------- never throws

    @Test
    public void aStoredItemIsNeverNullWhereItShouldBeAnItem() {
        for (String raw : new String[]{
                "{}", "{\"id\":\"" + ID + "\"}", "{\"image\":\"a.png\"}",
                "{\"id\":\"\",\"image\":\"a.png\"}",
                "{\"id\":\"" + ID + "\",\"image\":\"/x/y.png\",\"depth\":42}",
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"label\":[]}"}) {
            LibraryItem item = LibraryItem.fromJson(json(raw));
            if (item != null) {
                assertTrue("unusable id survived: " + raw, LibraryItem.isValidId(item.id));
                assertNotNull("an item must always have an image: " + raw, item.imageName);
                assertEquals("image must be a bare filename: " + raw,
                        item.imageName, LibraryItem.normaliseName(item.imageName));
            }
        }
    }

    // ------------------------------------------------- "null" is not a filename

    /**
     * Android's optString turns a JSON null into the four-character string "null".
     *
     * <p>It shipped: the live index carried {@code "depth":"null"} for every photo with
     * no map, which is what the parse below now heals. The consequences were not
     * cosmetic — hasDepth() is {@code depthName != null}, so the app believed a map
     * existed, the gallery drew a depth chip, the renderer looked for a file called
     * "null" and fell back to a flat map, and the fix line that exists precisely to
     * say "No depth" never appeared for any of them.
     *
     * <p>Written as a round trip because that is the shape of the bug: one read was
     * survivable, and it was the <em>write</em> that made it permanent.
     */
    @Test
    public void aJsonNullDepthIsAbsentAndSurvivesARewrite() {
        LibraryItem item = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"depth\":null}"));
        assertNotNull("an item with an image must parse", item);
        assertNull("a JSON null is an absent map, not a map named \"null\"", item.depthName);
        assertFalse("hasDepth() must not be true for a photo with no map", item.hasDepth());

        // And again, from a blob the buggy build already wrote.
        Library index = Library.empty()
                .withBundled(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH).withItem(item);
        // Precisely the poisoned form. A bare "\"depth\"" would also match the
        // per-surface depth INSIDE surfaces, which is a different field and always
        // present.
        assertFalse("the string \"null\" must not be written back",
                index.toJson().contains("\"depth\":\"null\""));
        LibraryItem again = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"depth\":\"null\"}"));
        assertNull("the literal string \"null\" heals to absent too", again.depthName);
    }

    /** The same trap on the image name, where it would be a file called "null". */
    @Test
    public void aNullImageNameIsAbsentAndNormaliseRejectsTheLiteral() {
        assertNull("normaliseName must reject \"null\"", LibraryItem.normaliseName("null"));
        // The reserved id is the one entry allowed to come back with no image name,
        // and only because an install that ran before the demo was file-backed has a
        // blob that says so. It is repaired by Library.withBundledFiles, not here.
        LibraryItem stale = LibraryItem.fromJson(json(
                "{\"id\":\"" + Library.BUNDLED_ID + "\",\"image\":null}"));
        assertNotNull("the pre-migration demo row must survive a parse", stale);
        assertNull("and it must have no image NAME", stale.imageName);
        assertTrue("and it must still be the demo", stale.isBundled());

        // The row as it is stored now: named, like every other item, and a user
        // item with no image is still junk either way.
        LibraryItem named = LibraryItem.fromJson(json(
                "{\"id\":\"" + Library.BUNDLED_ID + "\",\"image\":\""
                        + LibraryTest.DEMO_IMAGE + "\"}"));
        assertNotNull(named);
        assertEquals(LibraryTest.DEMO_IMAGE, named.imageName);
        assertNull(LibraryItem.fromJson(json("{\"id\":\"nope\",\"image\":null}")));
    }

    /** A label is optional too, and "null" is not one. */
    @Test
    public void aNullLabelIsAbsent() {
        LibraryItem item = LibraryItem.fromJson(json(
                "{\"id\":\"" + ID + "\",\"image\":\"a.png\",\"label\":null}"));
        assertNotNull(item);
        assertNull("a JSON null label is absent", item.label);
    }

    // -------------------------------------------------------------------- clips

    /**
     * The three clip names survive the item's own round trip, and a photograph
     * writes no clip key at all.
     *
     * <p>The second half is a byte-stability property, not a style choice, and the
     * reason clips needed no version bump: a library of photographs must serialise
     * exactly as it did before the feature existed. See
     * {@link VideoLibraryTest} for the same property asserted over a whole index.
     */
    @Test
    public void clipNamesSurviveARoundTripAndAreAbsentForAPhotograph() {
        LibraryItem clip = sample().withVideo(VideoTracks.of("a.mp4", "a-depth.mp4"));
        LibraryItem back = LibraryItem.fromJson(json(clip.toJson()));
        assertNotNull(back);
        assertEquals("a.mp4", back.video.color);
        assertEquals("a-depth.mp4", back.video.depth);
        assertNull(back.video.normal);
        assertTrue(back.isVideo());

        LibraryItem photo = sample();
        assertFalse(photo.isVideo());
        assertNull(photo.video);
        assertFalse("a photograph must not write a video key",
                photo.toJson().contains("video"));
        assertNull(LibraryItem.fromJson(json(photo.toJson())).video);
    }

    /**
     * A clip is a photograph as far as every other field is concerned, and this is
     * asserted rather than assumed because the opposite shipped once.
     *
     * <p>{@code withDepth} reset Motion, brightness and shadow when it was called,
     * because those values had been added after it was written and the constructor it
     * used defaulted everything it was not given. A clip is exactly that shape of
     * value — a field added after the writers existed — so a writer that forgot it
     * would quietly turn a user's video back into a photograph, with no error
     * anywhere. The full enumeration over every mutator is in
     * {@link VideoLibraryTest}; this is the item-level half.
     */
    @Test
    public void aDepthWriteOnAClipKeepsTheClip() {
        LibraryItem shaded = sample()
                .withVideo(VideoTracks.of("a.mp4", "a-depth.mp4", "a-normal.mp4"))
                .withIntensity(Surface.HOME, 70)
                .withMotion(Surface.HOME, 55);
        LibraryItem remapped = shaded.withDepth("b-depth.png");

        assertEquals("the poster's map is what withDepth writes", "b-depth.png",
                remapped.depthName);
        assertNull("and its normal is cleared with it, as always", remapped.normalName);
        assertNotNull("but the clip is a different asset and rides along",
                remapped.video);
        assertEquals("a.mp4", remapped.video.color);
        assertEquals("a-normal.mp4", remapped.video.normal);
        assertEquals(70, remapped.settings(Surface.HOME).depth);
        assertEquals(55, remapped.settings(Surface.HOME).motion);
    }
}
