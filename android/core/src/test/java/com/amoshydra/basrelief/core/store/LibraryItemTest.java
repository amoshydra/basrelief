package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
}
