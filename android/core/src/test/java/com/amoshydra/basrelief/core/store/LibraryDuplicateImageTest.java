package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The share path wrote every import to one fixed filename, so an install could
 * end up with nine gallery rows all reading a single file: the same photo under
 * every row, and each earlier photo lost. These pin the repair, and pin the part
 * that would be dangerous to get wrong — which row survives, because the survivor
 * is the one the selection pointer may end up naming.
 */
public class LibraryDuplicateImageTest {

    private static LibraryItem item(String id, String image, long addedAt) {
        return LibraryItem.of(id, image, null, addedAt);
    }

    private static Library lib(LibraryItem... items) {
        Library l = Library.empty();
        for (LibraryItem item : items) l = l.withItem(item);
        return l;
    }

    @Test
    public void aConsistentIndexHasNothingToDrop() {
        Library l = lib(item("a", "item-a.png", 1), item("b", "item-b.png", 2));
        assertTrue(l.itemsSharingAnImageName().isEmpty());
    }

    @Test
    public void everyRowButTheActiveOneIsDropped() {
        Library l = lib(item("a", "shared.png", 1), item("b", "shared.png", 2),
                item("c", "shared.png", 3))
                .withSelection("b");
        List<String> doomed = l.itemsSharingAnImageName();
        assertEquals(2, doomed.size());
        assertEquals(new HashSet<String>(Arrays.asList("a", "c")),
                new HashSet<String>(doomed));
    }

    @Test
    public void theActiveRowSurvivesEvenThoughItIsTheOldest() {
        // The trap: keeping "the newest" here would drop the row the selection
        // names, and the wallpaper pointer would be left pointing at nothing.
        Library l = lib(item("a", "shared.png", 1), item("b", "shared.png", 2),
                item("c", "shared.png", 3))
                .withSelection("a");
        assertFalse(l.itemsSharingAnImageName().contains("a"));
    }

    @Test
    public void withNoActiveRowTheNewestSurvives() {
        Library l = lib(item("a", "shared.png", 1), item("b", "shared.png", 9),
                item("c", "shared.png", 5));
        List<String> doomed = l.itemsSharingAnImageName();
        assertEquals(2, doomed.size());
        assertFalse("the newest must be the survivor", doomed.contains("b"));
        assertTrue(doomed.contains("a"));
        assertTrue(doomed.contains("c"));
    }

    @Test
    public void theBundledItemIsNeverDropped() {
        Library l = lib(item("a", "shared.png", 1), item("b", "shared.png", 2))
                .withItem(LibraryItem.bundled(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH))
                .withSelection("a");
        // The demo's image name comes from its own id, exactly as every other
        // row's does, so it cannot join a group of two and no repair can drop it.
        // The check is on NAMES rather than bytes precisely so this holds: a real
        // row can never share the demo's name, and two genuinely identical photos
        // must not be collapsed for it.
        assertFalse(l.itemsSharingAnImageName().contains(Library.BUNDLED_ID));
        assertTrue(l.find(Library.BUNDLED_ID) != null);
    }

    @Test
    public void groupsAreIndependent() {
        Library l = lib(item("a", "one.png", 1), item("b", "one.png", 2),
                item("c", "two.png", 3))
                .withSelection("a");
        assertEquals(Collections.singletonList("b"), l.itemsSharingAnImageName());
    }

    @Test
    public void afterDroppingTheDuplicatesTheSelectionStillResolves() {
        // The end-to-end property that matters: repair, then ask the index what is
        // selected, and it must still be a row that exists.
        Library l = lib(item("a", "shared.png", 1), item("b", "shared.png", 2),
                item("c", "shared.png", 3))
                .withSelection("b");
        for (String id : l.itemsSharingAnImageName()) l = l.remove(id);
        String active = l.activeId();
        assertTrue("selection must survive the repair", active != null);
        assertTrue("selection must name a row that exists", l.find(active) != null);
    }
}
