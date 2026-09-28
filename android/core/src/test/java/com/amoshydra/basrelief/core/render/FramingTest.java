package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;

import org.junit.Test;

/**
 * The one resolution of "what should be on screen".
 *
 * <p>This class lived in :app for one commit, and the bug it now exists to
 * prevent happened in that window: the studio loaded a newly selected photo's
 * <i>files</i> without loading its <i>framing</i>, so the photo arrived through
 * the previous photo's pan, zoom and depth. It was invisible to the 133 unit
 * tests because :app cannot be unit tested, and invisible on the device for as
 * long as nobody switched between two photos with different framing. It is in
 * :core now so that the next version of this mistake is a red test rather than
 * a bug report.
 *
 * <p>The themes are the ones this class exists to hold:
 *
 * <ol>
 *   <li><b>The bundled demo renders at the defaults, whatever the index says.</b>
 *       It is not editable, so a stored value for it is a value the renderer will
 *       never use " + chr(0x2014) + " and a slider showing it is a control lying
 *       to the user.
 *   <li><b>A null item is the bundled demo</b>, because that is what "nothing is
 *       selected" means and it is the state a delete of the active item lands in.
 * </ol>
 */
public class FramingTest {

    private static LibraryItem photo(String id, int intensity, int zoom,
                                     float ox, float oy, float px, float py) {
        return LibraryItem.of(id, id + ".png", null, 1L)
                .withIntensity(intensity).withZoom(zoom)
                .withOrbit(ox, oy).withPan(px, py);
    }

    // -------------------------------------------------------------- the bug

    /**
     * The regression, stated as a test: two photos with different framing, and
     * resolving the second must not inherit any of the first.
     */
    @Test
    public void eachPhotoResolvesItsOwnFraming() {
        Framing first = Framing.of(photo("a", 90, 100, 0f, 0f, 0f, 0f), 1f);
        Framing second = Framing.of(photo("b", 20, 175, 0.3f, -0.4f, -135f, 260f), 1f);

        assertEquals(0.9f, first.depth, 1e-6f);
        assertEquals(1.0f, first.zoom, 1e-6f);

        assertEquals(0.2f, second.depth, 1e-6f);
        assertEquals(1.75f, second.zoom, 1e-6f);
        assertEquals(0.3f, second.orbitX, 1e-6f);
        assertEquals(-0.4f, second.orbitY, 1e-6f);
        assertEquals(-135f, second.panX, 1e-6f);
        assertEquals(260f, second.panY, 1e-6f);

        // The actual failure was the *second* render keeping the first's numbers.
        assertFalse("the second photo inherited the first's depth",
                first.depth == second.depth);
        assertFalse("the second photo inherited the first's zoom",
                first.zoom == second.zoom);
    }

    /** Motion is the one setting that is not a property of a photograph. */
    @Test
    public void motionComesFromTheCallerAndNotFromTheItem() {
        LibraryItem item = photo("a", 40, 120, 0.1f, 0.1f, 5f, 5f);
        assertEquals(1.5f, Framing.of(item, 1.5f).motion, 1e-6f);
        assertEquals(0.3f, Framing.of(item, 0.3f).motion, 1e-6f);
    }

    // -------------------------------------------------------- the bundled demo

    @Test
    public void aNullItemIsTheBundledDemo() {
        Framing f = Framing.of(null, 1f);
        assertTrue(f.bundled);
        assertEquals(LibraryItem.DEFAULT_INTENSITY / 100f, f.depth, 1e-6f);
        assertEquals(LibraryItem.DEFAULT_ZOOM / 100f, f.zoom, 1e-6f);
        assertEquals(0f, f.orbitX, 0f);
        assertEquals(0f, f.orbitY, 0f);
        assertEquals(0f, f.panX, 0f);
        assertEquals(0f, f.panY, 0f);
    }

    /**
     * The bundled item, resolved, is the same thing as no item at all.
     *
     * <p>Two shapes of the same state that must not be able to differ.
     */
    @Test
    public void theBundledItemResolvesIdenticallyToNoItem() {
        Framing viaNull = Framing.of(null, 1f);
        Framing viaItem = Framing.of(LibraryItem.bundled(), 1f);
        assertEquals(viaNull.bundled, viaItem.bundled);
        assertEquals(viaNull.depth, viaItem.depth, 0f);
        assertEquals(viaNull.zoom, viaItem.zoom, 0f);
        assertEquals(viaNull.orbitX, viaItem.orbitX, 0f);
        assertEquals(viaNull.panX, viaItem.panX, 0f);
    }

    /**
     * A stored Zoom for the demo is ignored.
     *
     * <p>This happened for real: the sliders wrote zoom 42 onto the bundled item
     * because the guard tested {@code activeId() == null}, and the reserved id is
     * not null. The slider then displayed 0.42x while the renderer drew the demo
     * at 1.0x. Storing a value nothing reads is the silent-drift shape this
     * project keeps getting bitten by, so it is pinned here: if the demo ever
     * becomes editable, this test fails and someone has to decide what it should
     * display.
     */
    @Test
    public void aStoredValueForTheBundledDemoIsIgnoredRatherThanUsed() {
        LibraryItem tampered = LibraryItem.bundled().withZoom(42).withIntensity(13);
        Framing f = Framing.of(tampered, 1f);
        assertTrue(f.bundled);
        assertEquals("the demo must not render at a stored zoom",
                LibraryItem.DEFAULT_ZOOM / 100f, f.zoom, 0f);
        assertEquals(LibraryItem.DEFAULT_INTENSITY / 100f, f.depth, 1e-6f);
    }

    @Test
    public void aRealPhotoIsNotTheBundledDemo() {
        assertFalse(Framing.of(photo("a", 90, 100, 0f, 0f, 0f, 0f), 1f).bundled);
    }

    // -------------------------------------------------------------- selection

    /**
     * Whatever a delete of the active item does to the index, the render has to
     * end up somewhere renderable rather than throwing or going blank.
     */
    @Test
    public void deletingTheActiveItemLandsOnTheBundledDemo() {
        Library lib = Library.empty().withItem(LibraryItem.bundled())
                .withItem(photo("a", 90, 100, 0f, 0f, 0f, 0f))
                .withSelection("a");
        assertFalse(Framing.of(lib.activeItem(), 1f).bundled);

        Library after = lib.remove("a");
        assertNull(after.activeId());
        Framing f = Framing.of(after.activeItem(), 1f);
        assertTrue("must land on the demo, not on nothing", f.bundled);
    }

    private static void assertNull(Object o) {
        org.junit.Assert.assertNull(o);
    }
}
