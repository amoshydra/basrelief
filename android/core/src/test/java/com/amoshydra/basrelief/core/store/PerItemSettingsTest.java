package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-item settings, the migrations that brought them here, and the bundled item.
 *
 * <p>The traps here are the ones this project has already paid for: a stored
 * value whose meaning changes between builds, a field that must NOT be range
 * checked because its units are not a fraction, and a cleanup pass that deletes
 * something it cannot recreate.
 *
 * <p>Settings are now per <em>surface</em> as well as per item — see
 * {@link SurfaceSettings} — so every read goes through
 * {@link LibraryItem#settings(Surface)}. That indirection is not ceremony: the
 * render fields are private so that a writer cannot change one without the second
 * surface hearing about it, and a test that reads {@code item.intensity} would
 * fail to compile rather than quietly pass.
 */
public class PerItemSettingsTest {

    private static LibraryItem item(String id) {
        return LibraryItem.of(id, id + ".png", id + "_depth.png", 1000L);
    }

    private static SurfaceSettings home(LibraryItem it) {
        return it.settings(Surface.HOME);
    }

    // ------------------------------------------------------------- defaults

    @Test
    public void aNewItemStartsAtTheOldGlobalDefaults() {
        SurfaceSettings s = home(item("a"));
        assertEquals(90, s.depth);      // matches Config.DEFAULT_INTENSITY
        assertEquals(100, s.zoom);      // matches Config.DEFAULT_ZOOM
        assertEquals(33, s.motion);     // matches Config.DEFAULT_MOTION
        assertEquals(0f, s.originX, 0f);
        assertEquals(0f, s.panX, 0f);
        assertEquals(0, s.brightness);
        assertEquals(0, s.shadow);
    }

    @Test
    public void derivedAccessorsMatchTheOnesConfigHad() {
        LibraryItem it = item("a")
                .withIntensity(Surface.HOME, 60)
                .withZoom(Surface.HOME, 150)
                .withMotion(Surface.HOME, 50);
        assertEquals(0.6f, it.settings(Surface.HOME).depthFraction(), 1e-6f);
        assertEquals(1.5f, it.settings(Surface.HOME).zoomScale(), 1e-6f);
        assertEquals(1.5f, it.settings(Surface.HOME).motionScale(), 1e-6f);
    }

    /**
     * The lock screen starts as a mirror of the home screen, not as a second set of
     * values.
     *
     * <p>A new photo needs no lock entry, and every photo before anyone customises
     * one has none. If the default were "lock is its own defaults" then the lock
     * screen of a freshly imported photo would render differently from its home
     * screen for no reason the user could see.
     */
    @Test
    public void aNewItemHasNoLockOverrideAndLockResolvesToHome() {
        LibraryItem it = item("a").withIntensity(Surface.HOME, 20);
        assertFalse(it.hasOwnLockSettings());
        assertEquals(home(it).depth, it.settings(Surface.LOCK).depth);
    }

    // -------------------------------------------------------------- clamping

    @Test
    public void depthZoomAndMotionAreClamped() {
        assertEquals(100, home(item("a").withIntensity(Surface.HOME, 5000)).depth);
        assertEquals(0, home(item("a").withIntensity(Surface.HOME, -3)).depth);
        assertEquals(200, home(item("a").withZoom(Surface.HOME, 9999)).zoom);
        // below 25% would invert the camera
        assertEquals(25, home(item("a").withZoom(Surface.HOME, 1)).zoom);
        assertEquals(100, home(item("a").withMotion(Surface.HOME, 400)).motion);
        assertEquals(0, home(item("a").withMotion(Surface.HOME, -1)).motion);
    }

    @Test
    public void originIsClampedBecauseItIsAFraction() {
        SurfaceSettings s = home(item("a").withOrigin(Surface.HOME, 9f, -9f));
        assertEquals(0.5f, s.originX, 1e-6f);
        assertEquals(-0.5f, s.originY, 1e-6f);
    }

    /**
     * Pan is in PIXELS -- CameraModel consumes panX / surfaceW -- so its range is
     * the surface size, which this class cannot know. Clamping it to origin's
     * +/-0.5 range flattened a stored -135px to -0.5px, i.e. no pan at all, and it
     * survived review because the value still round-tripped. Delta is exactly 0 on
     * purpose: a tolerance would hide a partial clamp.
     */
    @Test
    public void panIsPixelsAndMustNotBeClampedToAFraction() {
        LibraryItem it = item("a").withPan(Surface.HOME, -135.25f, -632.5f);
        assertEquals(-135.25f, home(it).panX, 0f);
        assertEquals(-632.5f, home(it).panY, 0f);
        assertEquals(5000f, home(item("a").withPan(Surface.HOME, 5000f, 5000f)).panX, 0f);

        LibraryItem r = Library.parse(libraryOf(it)).activeItem();
        assertEquals(-135.25f, home(r).panX, 1e-3f);
        assertEquals(-632.5f, home(r).panY, 1e-3f);
    }

    /**
     * The adjustments are clamped on both signs, because 0 is the identity for both
     * and a value past either end has no meaning.
     */
    @Test
    public void theAdjustmentsAreClampedBothWays() {
        SurfaceSettings s = home(item("a").withAdjust(Surface.HOME, 500, -500));
        assertEquals(100, s.brightness);
        assertEquals(-100, s.shadow);
        assertEquals(0, home(item("a")).brightness);
    }

    // --------------------------------------------------------- round tripping

    @Test
    public void settingsSurviveAJsonRoundTrip() {
        LibraryItem it = item("a")
                .withIntensity(Surface.HOME, 37)
                .withZoom(Surface.HOME, 175)
                .withMotion(Surface.HOME, 61)
                .withOrigin(Surface.HOME, 0.2f, -0.3f)
                .withPan(Surface.HOME, 11f, -22f)
                .withAdjust(Surface.HOME, -40, 25);
        LibraryItem r = Library.parse(
                Library.empty().withItem(it).withSelection(it.id).toJson()).activeItem();
        SurfaceSettings s = home(r);
        assertEquals(37, s.depth);
        assertEquals(175, s.zoom);
        assertEquals(61, s.motion);
        assertEquals(0.2f, s.originX, 1e-6f);
        assertEquals(-22f, s.panY, 1e-3f);
        assertEquals(-40, s.brightness);
        assertEquals(25, s.shadow);
    }

    /**
     * A lock override survives persistence, and still overrides afterwards.
     *
     * <p>Written as a round trip rather than a field check because the failure this
     * would catch is a lock entry that parses but stops being an override — which
     * renders the lock screen correctly and quietly stops being customisable.
     */
    @Test
    public void aLockOverrideSurvivesAJsonRoundTrip() {
        LibraryItem it = item("a")
                .withIntensity(Surface.HOME, 80)
                .editSettings(Surface.LOCK, s -> s.with(25, 120, 10, 0f, 0f, 0f, 0f, 0, 0));

        LibraryItem r = Library.parse(libraryOf(it)).activeItem();
        assertTrue(r.hasOwnLockSettings());
        assertEquals(25, r.settings(Surface.LOCK).depth);
        assertEquals(80, r.settings(Surface.HOME).depth);
    }

    @Test
    public void mutatorsDoNotMutate() {
        LibraryItem a = item("a").withIntensity(Surface.HOME, 10);
        LibraryItem b = a.withIntensity(Surface.HOME, 20);
        assertEquals(10, home(a).depth);
        assertEquals(20, home(b).depth);
    }

    /**
     * Editing one value must not reset the others.
     *
     * <p>This is the hazard the {@code withX} mutators exist to prevent, and it is
     * easy to reintroduce: routing a single-field edit through a constructor that
     * defaults everything it is not given resets everything it is not given. It
     * shipped that way once — {@code withDepth} reset Motion, brightness and
     * shadow — because the values it did not name had been added later.
     */
    @Test
    public void aSingleFieldEditDoesNotResetTheOthers() {
        LibraryItem full = item("a")
                .withIntensity(Surface.HOME, 80)
                .withZoom(Surface.HOME, 150)
                .withMotion(Surface.HOME, 55)
                .withOrigin(Surface.HOME, 0.1f, -0.2f)
                .withPan(Surface.HOME, 30f, -40f)
                .withAdjust(Surface.HOME, -15, 20);

        // the three identity-only mutators are the ones most likely to take a
        // shortcut through a short constructor
        LibraryItem renamed = full.withLabel("Sunset");
        LibraryItem mapped = full.withDepth("new_depth.png");
        LibraryItem used = full.withLastUsedAt(999L);

        for (LibraryItem after : new LibraryItem[]{renamed, mapped, used}) {
            SurfaceSettings s = home(after);
            assertEquals(80, s.depth);
            assertEquals(150, s.zoom);
            assertEquals("Motion was reset by an identity-only edit", 55, s.motion);
            assertEquals(0.1f, s.originX, 1e-6f);
            assertEquals(-40f, s.panY, 1e-3f);
            assertEquals(-15, s.brightness);
            assertEquals(20, s.shadow);
        }
    }

    // ------------------------------------------------------------- migration

    /**
     * A v1 blob has no per-item settings. Items must survive with their ids, names,
     * labels, timestamps, the active pointer and the sort order intact, and gain
     * the defaults.
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
        assertEquals(90, home(lib.find("a")).depth);
        assertEquals(100, home(lib.find("a")).zoom);
        assertEquals(0f, home(lib.find("a")).panX, 0f);
    }

    /**
     * A v2 blob's flat values move under {@code surfaces.home}, and the global
     * Motion is copied onto every item.
     *
     * <p>The Motion half is the one that matters to a user. It used to be one
     * number in {@code Config}, so the library blob has no copy of it and the
     * migration has to be handed it. Dropping it instead would silently reset
     * everyone's wallpaper to the default speed on upgrade — a change nobody can
     * file a bug about, because the wallpaper still moves.
     */
    @Test
    public void v2FlatValuesAndTheGlobalMotionBothSurviveTheUpgrade() {
        String v2 = "{\"version\":2,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"depth\":null,\"added_at\":1,"
                + "\"intensity\":45,\"zoom\":160,\"orbit_x\":0.15,\"orbit_y\":-0.25,"
                + "\"pan_x\":-135,\"pan_y\":260},"
                + "{\"id\":\"b\",\"image\":\"b.png\",\"depth\":null,\"added_at\":2,"
                + "\"intensity\":10,\"zoom\":100,\"orbit_x\":0,\"orbit_y\":0,"
                + "\"pan_x\":0,\"pan_y\":0}]}";
        Library lib = Library.parse(v2, 70);

        SurfaceSettings a = home(lib.find("a"));
        assertEquals(45, a.depth);
        assertEquals(160, a.zoom);
        assertEquals(0.15f, a.originX, 1e-6f);
        assertEquals(-0.25f, a.originY, 1e-6f);
        assertEquals(-135f, a.panX, 1e-3f);
        assertEquals(260f, a.panY, 1e-3f);
        assertEquals("every item must adopt the old global Motion", 70, a.motion);

        assertEquals("the second photo too", 70, home(lib.find("b")).motion);
        assertEquals(10, home(lib.find("b")).depth);

        // and the migrated form writes back as v3, not as flat fields
        Library again = Library.parse(lib.toJson());
        assertEquals(70, home(again.find("a")).motion);
        assertEquals(45, home(again.find("a")).depth);
    }

    /**
     * A v2 blob with no explicit Motion value still gets the one it was given.
     *
     * <p>The degenerate case of the test above: the legacy value arrives as an
     * argument, so it applies whether or not any item mentioned Motion — which no
     * v2 item ever did.
     */
    @Test
    public void v2ItemsWithNoValuesAtAllStillAdoptTheGlobalMotion() {
        String v2 = "{\"version\":2,\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1}]}";
        Library lib = Library.parse(v2, 88);
        assertEquals(88, home(lib.find("a")).motion);
        assertEquals(SurfaceSettings.DEFAULT_DEPTH, home(lib.find("a")).depth);
    }

    /**
     * A v3 item with no lock entry inherits the home screen.
     *
     * <p>Migration deliberately writes no lock entry. A photo that never had
     * per-surface settings should keep tracking its home framing until someone
     * actually tunes the lock screen.
     */
    @Test
    public void aMigratedPhotoHasNoLockOverride() {
        String v2 = "{\"version\":2,\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"intensity\":45}]}";
        LibraryItem a = Library.parse(v2, 33).find("a");
        assertFalse("migration must not invent a lock override", a.hasOwnLockSettings());
        assertEquals(45, a.settings(Surface.LOCK).depth);
    }

    /**
     * Re-parsing what a migration produced must change nothing.
     *
     * <p>The weak version of this test re-parses {@code once.toJson()} and stops
     * there — and it proves nothing, because that blob is already v3, so the
     * migration does not run a second time. It passes identically whether the
     * migration is idempotent or skipped entirely.
     *
     * <p>So the version field is put back to 2 before the second parse. That forces
     * the migration to run again over content it already produced, which is the
     * case that actually breaks: a migration that overwrites or re-copies rather
     * than only filling in what is absent.
     */
    @Test
    public void migrationIsIdempotentEvenWhenForcedToRunTwice() {
        String v2 = "{\"version\":2,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"depth\":null,\"added_at\":1,"
                + "\"intensity\":45,\"zoom\":160,\"orbit_x\":0.15,\"orbit_y\":-0.25,"
                + "\"pan_x\":-135,\"pan_y\":260}]}";

        Library once = Library.parse(v2, 70);

        // The blob it wrote, with the version put back so the migration runs again.
        String alreadyMigrated = once.toJson()
                .replace("\"version\":" + Library.VERSION, "\"version\":2");
        Library twice = Library.parse(alreadyMigrated, 70);

        assertEquals("the whole blob must survive a second migration",
                once.toJson(), twice.toJson());
        SurfaceSettings a = home(once.find("a"));
        SurfaceSettings b = home(twice.find("a"));
        assertEquals(a.depth, b.depth);
        assertEquals(a.zoom, b.zoom);
        assertEquals("Motion must not be re-copied from the argument",
                a.motion, b.motion);
        assertEquals(0.15f, b.originX, 1e-6f);
        assertEquals(-135f, b.panX, 1e-3f);
    }

    /**
     * The first pass must actually do something.
     *
     * <p>The companion to the test above, and the reason it is worth having: a
     * migration that silently did nothing would leave every value below at its
     * default, which is exactly what "idempotent" looks like from the outside. The
     * two tests together are the real assertion — one proves the work happened, the
     * other proves it does not happen twice.
     */
    @Test
    public void theFirstMigrationChangesTheBlobRatherThanPassingThrough() {
        String v2 = "{\"version\":2,\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1,"
                + "\"intensity\":45,\"zoom\":160}]}";
        Library before = Library.parse(v2, 70);
        assertTrue("the migration must not be a no-op",
                !before.toJson().equals(v2));
        assertTrue("and it must write the nested shape",
                before.toJson().contains("\"surfaces\""));
        assertTrue("leaving the flat values behind would be a second copy to disagree",
                !before.toJson().contains("\"intensity\":45"));
    }

    /**
     * A migrated blob must SAY SO, so the caller rewrites it.
     *
     * <p>This is the question {@code parse} cannot answer. Parsing a v2 blob gives
     * the right items, so every test of the migration passes — while the file stays
     * v2 on disk and the migration re-runs on every load forever, including on the
     * GL thread at wallpaper cold start. Found on a device: the parsed model was
     * always right, and the on-disk shape never converged.
     */
    @Test
    public void aMigratedBlobNoLongerNeedsMigrating() {
        String v2 = "{\"version\":2,\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"intensity\":45}]}";
        assertTrue("a v2 blob needs migrating", Library.needsMigration(v2));
        assertFalse("and its migration makes that unnecessary",
                Library.needsMigration(Library.parse(v2, 33).toJson()));
    }

    /**
     * A blob from a NEWER build must never be overwritten.
     *
     * <p>The trap the mutation check found, and it is a data-loss path rather than a
     * cosmetic one. {@code parse} returns an <i>empty</i> library for a future
     * version, so a caller that rewrites anything "not current" — which is what
     * {@code version == VERSION} says — writes that empty library over a future
     * build's photos the moment the user downgrades. Silently, and with no way to
     * tell afterwards.
     */
    @Test
    public void aFutureBlobIsNotSomethingToMigrate() {
        String future = "{\"version\":" + (Library.VERSION + 1) + ",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\"}]}";
        assertEquals("parse yields empty for a newer build",
                0, Library.parse(future).items().size());
        assertFalse("but it must NOT be rewritten -- that would delete its photos",
                Library.needsMigration(future));
    }

    /**
     * An unreadable blob is left alone.
     *
     * <p>It is unusable either way, and overwriting it destroys the only copy of
     * whatever it said — which is the one thing that might still be recoverable by
     * hand.
     */
    @Test
    public void nothingOrNothingReadableIsNotMigrated() {
        assertFalse(Library.needsMigration(null));
        assertFalse(Library.needsMigration(""));
        assertFalse(Library.needsMigration("   "));
        assertFalse(Library.needsMigration("{ not json"));
    }

    /** A label carrying the version string must not make a stale blob look current. */
    @Test
    public void aVersionStringInsideALabelIsNotAMatch() {
        String v2 = "{\"version\":2,\"items\":[{\"id\":\"a\",\"image\":\"a.png\","
                + "\"label\":\"version\\\":3\"}]}";
        assertTrue("a label must not satisfy the version check",
                Library.needsMigration(v2));
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

    // ------------------------------------------------------ applied surfaces

    /**
     * A photo is on the home screen until something says otherwise.
     *
     * <p>Absent means home, not empty. Every pre-existing install is in exactly that
     * state, so defaulting to empty would mean every current user's photo claims to
     * be on no surface at all — and the segment that edits the lock screen would
     * never appear for anyone who had not just applied it.
     */
    @Test
    public void aLibraryWithNoAppliedFieldIsOnHome() {
        Library lib = Library.parse(libraryOf(item("a")));
        assertEquals(1, lib.appliedSurfaces().size());
        assertTrue(lib.appliedSurfaces().contains(Surface.HOME));
        assertFalse(lib.isOnBothSurfaces());
    }

    /**
     * The segment only exists when the photo is genuinely on both.
     *
     * <p>One surface means there is nothing to switch between, and a toggle that
     * cannot change anything is the same lie as a greyed slider.
     */
    @Test
    public void bothSurfacesRequiresBoth() {
        Library home = Library.empty().withItem(item("a")).withSelection("a");
        assertFalse("home alone is not both", home.isOnBothSurfaces());
        assertTrue(home.withAppliedTo(Surface.LOCK, true).isOnBothSurfaces());
    }

    /**
     * Selecting a different photo must not inherit the surfaces the last one was on.
     *
     * <p>The trap: carrying the set across a selection change claims the new photo is
     * on surfaces nobody applied it to, so the segment offers to edit a lock screen
     * that does not exist — and the first write to it creates one.
     */
    @Test
    public void selectingAnotherPhotoStartsOnHomeAgain() {
        Library both = Library.empty()
                .withItem(item("a")).withItem(item("b"))
                .withSelection("a").withAppliedTo(Surface.LOCK, true);
        assertTrue(both.isOnBothSurfaces());
        Library after = both.withSelection("b");
        assertFalse("b was never applied to the lock screen", after.isOnBothSurfaces());
        assertEquals(1, after.appliedSurfaces().size());
    }

    @Test
    public void theAppliedSetSurvivesPersistence() {
        Library both = Library.empty().withItem(item("a")).withSelection("a")
                .withAppliedTo(Surface.LOCK, true);
        Library back = Library.parse(both.toJson());
        assertTrue(back.isOnBothSurfaces());
    }

    /** Applying twice is a no-op rather than a duplicate entry. */
    @Test
    public void applyingTheSameSurfaceTwiceChangesNothing() {
        Library once = Library.empty().withItem(item("a")).withSelection("a")
                .withAppliedTo(Surface.LOCK, true);
        Library twice = once.withAppliedTo(Surface.LOCK, true);
        assertEquals(once.toJson(), twice.toJson());
    }

    /**
     * The bundled demo cannot be applied to the lock screen.
     *
     * <p>It has no image of its own there and the platform's lock frame is captured
     * from whatever is live, so recording it would claim a second live engine this
     * app cannot produce. Saying no beats storing a promise.
     */
    @Test
    public void theBundledDemoIsNotRecordedOnTheLockScreen() {
        Library lib = Library.empty().withItem(LibraryItem.bundled())
                .withSelection(Library.BUNDLED_ID)
                .withAppliedTo(Surface.LOCK, true);
        assertFalse(lib.isOnBothSurfaces());
    }

    /** A key from a newer build is skipped, not fatal. */
    @Test
    public void anUnknownAppliedKeyIsSkippedRatherThanFatal() {
        String blob = "{\"version\":" + Library.VERSION + ",\"active\":\"a\","
                + "\"applied\":[\"home\",\"sideways\"],\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\"}]}";
        Library lib = Library.parse(blob);
        assertEquals(1, lib.items().size());
        assertTrue(lib.appliedSurfaces().contains(Surface.HOME));
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