package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

import org.junit.Test;

/**
 * The one resolution of "what should be on screen".
 *
 * <p>This class lived in :app for one commit, and the bug it now exists to
 * prevent happened in that window: the studio loaded a newly selected photo's
 * <i>files</i> without loading its <i>framing</i>, so the photo arrived through
 * the previous photo's pan, zoom and depth. It was invisible to the unit tests
 * because :app cannot be unit tested, and invisible on the device for as long as
 * nobody switched between two photos with different framing. It is in :core now so
 * that the next version of this mistake is a red test rather than a bug report.
 *
 * <p>The themes are the ones this class exists to hold:
 *
 * <ol>
 *   <li><b>The bundled demo renders at the defaults, whatever the index says.</b>
 *       It is not editable, so a stored value for it is a value the renderer will
 *       never use, and a slider showing it is a control lying to the user.
 *   <li><b>A null item is the bundled demo</b>, because that is what "nothing is
 *       selected" means and it is the state a delete of the active item lands in.
 *   <li><b>Motion is read from the photo, not from the caller.</b> It used to be
 *       a parameter, from a global. Now that it is per-photo, a caller cannot hand
 *       this class a Motion the photo disagrees with — which matters beyond
 *       tidiness, because the active photo's Motion is what decides whether the
 *       tilt sensor runs at all.
 *   <li><b>A surface without an override resolves to the same place its parent
 *       does.</b> The lock screen mirroring home is the default for every photo,
 *       and it has to be resolved here rather than by each caller choosing a
 *       fallback.
 * </ol>
 */
public class FramingTest {

    private static LibraryItem photo(String id, int depth, int zoom,
                                     float ox, float oy, float px, float py) {
        LibraryItem base = LibraryItem.of(id, id + ".png", null, 1L)
                .withIntensity(Surface.HOME, depth)
                .withZoom(Surface.HOME, zoom)
                .withOrigin(Surface.HOME, ox, oy)
                .withPan(Surface.HOME, px, py);
        return base;
    }

    private static Framing home(LibraryItem item) {
        return Framing.of(item, Surface.HOME);
    }

    // -------------------------------------------------------------- the bug

    /**
     * The regression, stated as a test: two photos with different framing, and
     * resolving the second must not inherit any of the first.
     */
    @Test
    public void eachPhotoResolvesItsOwnFraming() {
        Framing first = home(photo("a", 90, 100, 0f, 0f, 0f, 0f));
        Framing second = home(photo("b", 20, 175, 0.3f, -0.4f, -135f, 260f));

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

    // ---------------------------------------------------------------- motion

    /**
     * Motion is the photo's, not the caller's.
     *
     * <p>This inverts a previous test. Motion used to be global and was passed in,
     * and there was a deliberate test pinning that it came from the caller — which
     * was correct then and is the bug now. A caller that can name a Motion the
     * photo disagrees with is a caller that can draw a photo at a responsiveness
     * the user never chose for it, and whose stored value then disagrees with what
     * is on the home screen.
     */
    @Test
    public void motionComesFromThePhotoAndNotFromTheCaller() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withMotion(Surface.HOME, 50);

        // 50 on the 0..100 slider is the middle of the 0..3 tilt scale.
        assertEquals(1.5f, home(item).motion, 1e-6f);
        assertEquals(0.3f, home(item.withMotion(Surface.HOME, 10)).motion, 1e-6f);
    }

    /**
     * The bundled demo's Motion is honoured, unlike its Depth and Zoom.
     *
     * <p>The one asymmetry in this class, and it is deliberate. Motion answers a
     * question about the photo being looked at, so its slider stays live on the
     * demo while Depth and Zoom are switched off. Ignoring it here would make that
     * slider display a number the renderer never uses — the exact lie the
     * bundled-demo rule exists to prevent, reintroduced through the one value the
     * rule was relaxed for.
     */
    @Test
    public void aStoredMotionForTheBundledDemoIsUsed() {
        LibraryItem tampered = LibraryItem.bundled().withMotion(Surface.HOME, 100);
        assertTrue(home(tampered).bundled);
        assertEquals(3f, home(tampered).motion, 1e-6f);
        assertEquals(0f, home(LibraryItem.bundled().withMotion(Surface.HOME, 0)).motion, 1e-6f);
    }

    /** A demo with no stored Motion still gets the default, not a null deref. */
    @Test
    public void theBundledDemoWithoutAStoredMotionGetsTheDefault() {
        assertEquals(SurfaceSettings.DEFAULT_MOTION / 100f * 3f,
                home(LibraryItem.bundled()).motion, 1e-6f);
        assertEquals("and the same for no item at all",
                SurfaceSettings.DEFAULT_MOTION / 100f * 3f,
                Framing.of(null, Surface.HOME).motion, 1e-6f);
    }

    // ---------------------------------------------------------- the picture

    /**
     * A photo's Brightness and Shadow reach the renderer as mapped scalars.
     *
     * <p>Pinned as the mapped values rather than the stored -100..100 because the
     * shader only ever sees these two numbers, so these are what "the photo is at
     * +40" actually means at the point of use.
     */
    @Test
    public void thePictureAdjustmentsArriveAlreadyMapped() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withAdjust(Surface.HOME, 100, -100);
        Framing f = home(item);
        assertEquals("one stop up", 2f, f.brightnessGain, 1e-6f);
        assertEquals(-1f, f.shadowLift, 1e-6f);
    }

    /** Picture follows the same rule as everything else: stored means used. */
    @Test
    public void aStoredPictureForTheBundledDemoIsUsed() {
        LibraryItem stored = LibraryItem.bundled().withAdjust(Surface.HOME, 100, -100);
        Framing f = home(stored);
        assertTrue(f.bundled);
        assertEquals("one stop up", 2f, f.brightnessGain, 1e-6f);
        assertEquals(-1f, f.shadowLift, 1e-6f);
    }

    /** And an untouched demo is unadjusted, so 0 stays the identity. */
    @Test
    public void anUntouchedBundledDemoIsUnadjusted() {
        Framing f = home(LibraryItem.bundled());
        assertEquals(1f, f.brightnessGain, 0f);
        assertEquals(0f, f.shadowLift, 0f);
    }

    /** Picture is per surface, like everything else a photo owns. */
    @Test
    public void thePictureAdjustmentsArePerSurface() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withAdjust(Surface.HOME, 100, 0)
                .editSettings(Surface.LOCK, s -> s.with(s.depth, s.zoom, s.motion,
                        s.originX, s.originY, s.panX, s.panY, 0, -100));
        assertEquals(2f, home(item).brightnessGain, 1e-6f);
        assertEquals(0f, home(item).shadowLift, 1e-6f);
        assertEquals(1f, Framing.of(item, Surface.LOCK).brightnessGain, 1e-6f);
        assertEquals(-1f, Framing.of(item, Surface.LOCK).shadowLift, 1e-6f);
    }

    // --------------------------------------------------------------- surfaces

    /**
     * A photo with no lock override resolves the lock screen to its home values.
     *
     * <p>Not a fallback: inheriting is the default state of every photo, so if this
     * resolved differently, every photo would need an explicit lock entry before
     * its lock screen looked like its home screen.
     */
    @Test
    public void aPhotoWithNoLockOverrideResolvesLockToHome() {
        LibraryItem item = photo("a", 20, 175, 0.3f, -0.4f, -135f, 260f);
        Framing onLock = Framing.of(item, Surface.LOCK);

        assertFalse("a photo with no lock override must not claim one",
                item.hasOwnLockSettings());
        assertEquals(home(item).depth, onLock.depth, 0f);
        assertEquals(home(item).zoom, onLock.zoom, 0f);
        assertEquals(home(item).orbitX, onLock.orbitX, 0f);
        assertEquals(home(item).orbitY, onLock.orbitY, 0f);
        assertEquals(home(item).panX, onLock.panX, 0f);
        assertEquals(home(item).panY, onLock.panY, 0f);
        assertEquals(home(item).motion, onLock.motion, 0f);
    }

    /**
     * A lock override is used, and home is left alone.
     *
     * <p>The direction of the second half is the one that matters. How a landscape
     * reads on the home screen says nothing about how it reads behind a lock-screen
     * clock, so the lock screen exists to be tuned separately — and a write to one
     * surface that leaked onto the other would make the second surface
     * un-customisable in practice.
     */
    @Test
    public void aLockOverrideIsUsedAndLeavesHomeAlone() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .editSettings(Surface.LOCK, s -> s.with(30, 140, 80, 0.1f, -0.2f,
                        -40f, 15f, -20, 35));

        assertTrue(item.hasOwnLockSettings());
        Framing onLock = Framing.of(item, Surface.LOCK);
        assertEquals(0.3f, onLock.depth, 1e-6f);
        assertEquals(1.4f, onLock.zoom, 1e-6f);
        assertEquals(2.4f, onLock.motion, 1e-6f);
        assertEquals(0.1f, onLock.orbitX, 1e-6f);
        assertEquals(-0.2f, onLock.orbitY, 1e-6f);
        assertEquals(-40f, onLock.panX, 1e-6f);
        assertEquals(15f, onLock.panY, 1e-6f);

        assertEquals("home must not have followed the lock override",
                0.9f, home(item).depth, 1e-6f);
        assertEquals(1.0f, home(item).zoom, 1e-6f);
        assertEquals(0f, home(item).orbitX, 0f);
    }

    /** Two surfaces, two Motions, on the one photo. */
    @Test
    public void motionIsPerSurface() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withMotion(Surface.HOME, 100)
                .withMotion(Surface.LOCK, 0);

        assertEquals(3f, home(item).motion, 1e-6f);
        assertEquals(0f, Framing.of(item, Surface.LOCK).motion, 1e-6f);
    }

    /**
     * A lock override is only created by writing to the lock screen.
     *
     * <p>Edits to the home surface must not conjure one. If they did, a photo the
     * user never touched on the lock screen would stop following its home framing
     * the first time they nudged a home slider — which is exactly the "lock screen
     * silently stops tracking the photo" failure the private fields exist to
     * prevent, arriving by the other route.
     */
    @Test
    public void editingHomeDoesNotCreateALockOverride() {
        LibraryItem item = photo("a", 90, 100, 0f, 0f, 0f, 0f)
                .withIntensity(Surface.HOME, 40)
                .withZoom(Surface.HOME, 150)
                .withPan(Surface.HOME, 20f, 20f)
                .withAdjust(Surface.HOME, -30, 10);

        assertFalse("a home edit created a lock override", item.hasOwnLockSettings());
        assertEquals(0.4f, home(item).depth, 1e-6f);
        assertEquals(0.4f, Framing.of(item, Surface.LOCK).depth, 1e-6f);
    }

    // -------------------------------------------------------- the bundled demo

    @Test
    public void aNullItemIsTheBundledDemo() {
        Framing f = Framing.of(null, Surface.HOME);
        assertTrue(f.bundled);
        assertEquals(SurfaceSettings.DEFAULT_DEPTH / 100f, f.depth, 1e-6f);
        assertEquals(SurfaceSettings.DEFAULT_ZOOM / 100f, f.zoom, 1e-6f);
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
        Framing viaNull = Framing.of(null, Surface.HOME);
        Framing viaItem = home(LibraryItem.bundled());
        assertEquals(viaNull.bundled, viaItem.bundled);
        assertEquals(viaNull.depth, viaItem.depth, 0f);
        assertEquals(viaNull.zoom, viaItem.zoom, 0f);
        assertEquals(viaNull.motion, viaItem.motion, 0f);
        assertEquals(viaNull.orbitX, viaItem.orbitX, 0f);
        assertEquals(viaNull.panX, viaItem.panX, 0f);
    }

    /**
     * A stored value for the bundled demo is <b>used</b>.
     *
     * <p><b>This test asserts the opposite of what it used to, and that is the
     * point.</b> It was named
     * {@code aStoredValueForTheBundledDemoIsIgnoredRatherThanUsed}, and it existed
     * because of a real bug: the sliders wrote zoom 42 onto the bundled item —
     * the guard tested {@code activeId() == null} and the reserved id is not null —
     * and this class ignored it, so the slider displayed 0.42x while the renderer
     * drew at 1.0x. Storing a value nothing reads is the silent-drift shape this
     * project keeps getting bitten by.
     *
     * <p>The bug was fixed in the wrong place. The controls were switched off for
     * the demo, which hides the symptom and leaves this class still unable to
     * honour a stored value. Only the demo's <i>image</i> comes from res/raw;
     * Depth, Zoom, Motion, Origin, Pan and the adjustments are ordinary render
     * values the reserved item holds perfectly well.
     *
     * <p>So the demo is editable and the value is read. The rule that actually
     * matters is unchanged and is the first assertion below: a control must never
     * display a number the renderer does not use.
     */
    @Test
    public void aStoredValueForTheBundledDemoIsUsedRatherThanIgnored() {
        LibraryItem stored = LibraryItem.bundled()
                .withZoom(Surface.HOME, 42)
                .withIntensity(Surface.HOME, 13);
        Framing f = home(stored);
        assertTrue("the demo is still the demo", f.bundled);
        assertEquals("a stored zoom is now honoured", 0.42f, f.zoom, 1e-6f);
        assertEquals(0.13f, f.depth, 1e-6f);
    }

    /** The same on the lock screen: the demo is not special on either surface. */
    @Test
    public void aStoredValueForTheBundledDemoIsUsedOnTheLockScreenToo() {
        LibraryItem stored = LibraryItem.bundled().withZoom(Surface.LOCK, 42);
        assertTrue(Framing.of(stored, Surface.LOCK).bundled);
        assertEquals(0.42f, Framing.of(stored, Surface.LOCK).zoom, 1e-6f);
    }

    /**
     * A demo with nothing stored renders at the defaults.
     *
     * <p>The property the old test was really protecting, still true and still worth
     * pinning: an untouched demo looks like the demo rather than like whatever the
     * last photo left behind.
     */
    @Test
    public void anUntouchedBundledDemoRendersAtTheDefaults() {
        Framing f = home(LibraryItem.bundled());
        assertTrue(f.bundled);
        assertEquals(SurfaceSettings.DEFAULT_ZOOM / 100f, f.zoom, 0f);
        assertEquals(SurfaceSettings.DEFAULT_DEPTH / 100f, f.depth, 1e-6f);
        assertEquals(0f, f.orbitX, 0f);
        assertEquals(0f, f.panX, 0f);
    }

    @Test
    public void aRealPhotoIsNotTheBundledDemo() {
        assertFalse(home(photo("a", 90, 100, 0f, 0f, 0f, 0f)).bundled);
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
        assertFalse(home(lib.activeItem()).bundled);

        Library after = lib.remove("a");
        assertNull(after.activeId());
        Framing f = home(after.activeItem());
        assertTrue("must land on the demo, not on nothing", f.bundled);
    }

    /**
     * ofValues hands back exactly what it was given, and never claims to be the
     * bundled special case.
     *
     * <p>The surface glide's start point is a renderer handing its CURRENT numbers
     * back in, so a value that came back changed would make the glide start from
     * somewhere the view never was -- an invisible jump inside a visible ease.
     */
    @Test
    public void ofValuesReturnsExactlyWhatItWasGiven() {
        Framing f =
                Framing.ofValues(
                        0.9f, 1.2f, 0.3f, 0.11f, -0.22f, 40f, -60f, 1.5f, 0.5f);
        assertEquals(0.9f, f.depth, 0f);
        assertEquals(1.2f, f.zoom, 0f);
        assertEquals(0.3f, f.motion, 0f);
        assertEquals(0.11f, f.orbitX, 0f);
        assertEquals(-0.22f, f.orbitY, 0f);
        assertEquals(40f, f.panX, 0f);
        assertEquals(-60f, f.panY, 0f);
        assertEquals(1.5f, f.brightnessGain, 0f);
        assertEquals(0.5f, f.shadowLift, 0f);
        assertFalse("a snapshot is numbers, not the bundled file case",
                f.bundled);
    }
}
