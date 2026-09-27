package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-item settings, the v1 -> v2 migration, and the bundled item.
 *
 * <p>The traps here are the ones this project has already paid for: a stored
 * value whose meaning changes between builds, a field that must NOT be range
 * checked because its units are not a fraction, and a cleanup pass that deletes
 * something it cannot recreate.
 */
public class PerItemSettingsTest {

    private static LibraryItem item(String id) {
        return LibraryItem.of(id, id + ".png", id + "_depth.png", 1000L);
    }

    // ------------------------------------------------------------- defaults

    @Test
    public void aNewItemStartsAtTheOldGlobalDefaults() {
        LibraryItem it = item("a");
        assertEquals(90, it.intensity);   // matches Config.DEFAULT_INTENSITY
        assertEquals(100, it.zoom);       // matches Config.DEFAULT_ZOOM
        assertEquals(0f, it.orbitX, 0f);
        assertEquals(0f, it.panX, 0f);
    }

    @Test
    public void derivedAccessorsMatchTheOnesConfigHad() {
        LibraryItem it = item("a").withIntensity(60).withZoom(150);
        assertEquals(0.6f, it.depthFraction(), 1e-6f);
        assertEquals(1.5f, it.zoomScale(), 1e-6f);
    }

    // -------------------------------------------------------------- clamping

    @Test
    public void intensityAndZoomAreClamped() {
        assertEquals(100, item("a").withIntensity(5000).intensity);
        assertEquals(0, item("a").withIntensity(-3).intensity);
        assertEquals(200, item("a").withZoom(9999).zoom);
        // below 25% would invert the camera
        assertEquals(25, item("a").withZoom(1).zoom);
    }

    @Test
    public void orbitIsClampedBecauseItIsAFraction() {
        LibraryItem it = item("a").withOrbit(9f, -9f);
        assertEquals(0.5f, it.orbitX, 1e-6f);
        assertEquals(-0.5f, it.orbitY, 1e-6f);
    }

    /**
     * Pan is in PIXELS -- CameraModel consumes panX / surfaceW -- so its range is
     * the surface size, which this class cannot know. Clamping it to orbit's
     * +/-0.5 range flattened a stored -135px to -0.5px, i.e. no pan at all, and
     * it survived review because the value still round-tripped. Delta is exactly
     * 0 on purpose: a tolerance would hide a partial clamp.
     */
    @Test
    public void panIsPixelsAndMustNotBeClampedToAFraction() {
        LibraryItem it = item("a").withPan(-135.25f, -632.5f);
        assertEquals(-135.25f, it.panX, 0f);
        assertEquals(-632.5f, it.panY, 0f);
        assertEquals(5000f, item("a").withPan(5000f, 5000f).panX, 0f);

        LibraryItem r = Library.parse(libraryOf(it)).activeItem();
        assertEquals(-135.25f, r.panX, 1e-3f);
        assertEquals(-632.5f, r.panY, 1e-3f);
    }

    // --------------------------------------------------------- round tripping

    @Test
    public void settingsSurviveAJsonRoundTrip() {
        LibraryItem it = item("a").withIntensity(37).withZoom(175)
                .withOrbit(0.2f, -0.3f).withPan(11f, -22f);
        LibraryItem r = Library.parse(
                Library.empty().withItem(it).withSelection(it.id).toJson()).activeItem();
        assertEquals(37, r.intensity);
        assertEquals(175, r.zoom);
        assertEquals(0.2f, r.orbitX, 1e-6f);
        assertEquals(-22f, r.panY, 1e-3f);
    }

    @Test
    public void mutatorsDoNotMutate() {
        LibraryItem a = item("a").withIntensity(10);
        LibraryItem b = a.withIntensity(20);
        assertEquals(10, a.intensity);
        assertEquals(20, b.intensity);
    }

    // ------------------------------------------------------------- migration

    /**
     * A v1 blob has no per-item settings. Items must survive with their ids,
     * names, labels, timestamps, the active pointer and the sort order intact,
     * and gain the defaults.
     */
    @Test
    public void v1ItemsLoadWithDefaultSettingsAndKeepEverythingElse() {
        String v1 = "{\"version\":1,\"active\":\"a\",\"sort\":\"Name\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"depth\":\"a_depth.png\","
                + "\"label\":\"Beach\",\"added_at\":111,\"last_used_at\":222},"
                + "{\"id\":\"b\",\"image\":\"b.png\",\"depth\":null,"
                + "\"added_at\":333}]}";
        Library lib = Library.parse(v1);
        assertEquals(2, lib.items().size());
        assertEquals("a", lib.activeId());
        assertEquals("Beach", lib.find("a").label);
        assertEquals(111L, lib.find("a").addedAt);
        assertEquals(222L, lib.find("a").lastUsedAt);
        assertEquals(333L, lib.find("b").addedAt);
        assertEquals(90, lib.find("a").intensity);
        assertEquals(100, lib.find("a").zoom);
        assertEquals(0f, lib.find("a").panX, 0f);
    }

    @Test
    public void migrationIsIdempotent() {
        String v1 = "{\"version\":1,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"depth\":null,\"added_at\":1}]}";
        Library once = Library.parse(v1);
        // re-parsing the re-serialised form must be stable
        Library twice = Library.parse(once.toJson());
        assertEquals(once.activeId(), twice.activeId());
        assertEquals(once.items().size(), twice.items().size());
        assertEquals(once.find("a").intensity, twice.find("a").intensity);
    }

    @Test
    public void aFutureVersionYieldsAnEmptyLibraryRatherThanAGuess() {
        String future = "{\"version\":" + (Library.VERSION + 1)
                + ",\"items\":[{\"id\":\"a\",\"image\":\"a.png\"}]}";
        assertEquals(0, Library.parse(future).items().size());
    }

    @Test
    public void corruptBlobYieldsAnEmptyLibraryInsteadOfThrowing() {
        for (String bad : new String[]{"{", "nonsense", "[]", "{\"version\":", " "}) {
            assertNotNull(Library.parse(bad));
            assertEquals("bad blob: " + bad, 0, Library.parse(bad).items().size());
        }
    }

    // ------------------------------------------------------- the bundled item

    @Test
    public void theBundledItemHasNoFilesAndIsRecognised() {
        LibraryItem b = LibraryItem.bundled();
        assertTrue(b.isBundled());
        assertEquals(Library.BUNDLED_ID, b.id);
        assertNull("bundled reads from res/raw, so it has no file", b.imageName);
        assertNull(b.depthName);
    }

    @Test
    public void theBundledItemCannotBeRemoved() {
        Library lib = Library.empty().withItem(item("a")).withItem(LibraryItem.bundled());
        assertEquals(2, lib.items().size());
        Library after = lib.remove(Library.BUNDLED_ID);
        assertEquals("the bundled item must survive remove()", 2, after.items().size());
        assertNotNull(after.find(Library.BUNDLED_ID));
    }

    @Test
    public void theBundledItemSurvivesAFileCleanupPass() {
        // A predicate that reports nothing exists -- the worst case, e.g. right
        // after a restore onto a device where filesDir came back empty.
        Library lib = Library.empty().withItem(item("a")).withItem(LibraryItem.bundled());
        Library kept = lib.retainExisting(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(String name) {
                return false;
            }
        });
        assertEquals("only the bundled item may remain", 1, kept.items().size());
        assertNotNull("the one item that cannot be recreated must not be dropped",
                kept.find(Library.BUNDLED_ID));
    }

    @Test
    public void theBundledItemSurvivesAJsonRoundTrip() {
        // fromJson drops any item with no image name, because for a user item
        // that means nothing to render. The bundled item has none by design, so
        // without an exemption it was silently dropped on every parse -- and
        // since it cannot be recreated from filesDir, the gallery lost its only
        // way back to the default on the very next launch.
        Library lib = Library.empty().withItem(LibraryItem.bundled())
                .withSelection(Library.BUNDLED_ID);
        Library back = Library.parse(lib.toJson());
        assertEquals("the bundled item must survive persistence", 1, back.items().size());
        assertNotNull("and must still be there after a restart", back.find(Library.BUNDLED_ID));
        assertEquals(Library.BUNDLED_ID, back.activeId());
        assertTrue(back.activeItem().isBundled());
    }

    @Test
    public void anItemWithNoImageIsStillDroppedOnParse() {
        // The exemption above is for the reserved id only; a user item with no
        // image is still junk and must not come back.
        String junk = "{\"version\":" + Library.VERSION + ",\"items\":["
                + "{\"id\":\"nope\",\"image\":null,\"added_at\":1}]}";
        assertEquals(0, Library.parse(junk).items().size());
    }

    @Test
    public void anOrdinaryItemIsStillRemovable() {
        Library lib = Library.empty().withItem(item("a")).withItem(item("b"));
        assertEquals(1, lib.remove("a").items().size());
        assertNull(lib.remove("a").find("a"));
    }

    @Test
    public void anItemWithNoDepthIsStillAnItem() {
        // A missing depth file is not grounds for removal -- a flat item is legal.
        Library lib = Library.empty().withItem(LibraryItem.of("a", "a.png", null, 1L));
        assertEquals(1, lib.retainExisting(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(String name) {
                return "a.png".equals(name);
            }
        }).items().size());
    }

    // --------------------------------------------------------------- helpers

    private static String libraryOf(LibraryItem active) {
        return Library.empty().withItem(active).withSelection(active.id).toJson();
    }
}
