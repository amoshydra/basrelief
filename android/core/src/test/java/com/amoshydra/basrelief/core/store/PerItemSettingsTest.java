package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.render.LightDirection;
import com.amoshydra.basrelief.core.render.Splat;

import org.json.JSONObject;
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
        assertEquals(LightDirection.DEFAULT_AZIMUTH_DEG, s.lightAzimuth, 0f);
        assertEquals(LightDirection.DEFAULT_ELEVATION_DEG, s.lightElevation, 0f);
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
        assertEquals(SurfaceSettings.ORIGIN_LIMIT, s.originX, 1e-6f);
        assertEquals(-SurfaceSettings.ORIGIN_LIMIT, s.originY, 1e-6f);
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

    // --------------------------------------------------------- the splat look

    /**
     * The four splat-look values round-trip, and a default writes no key.
     *
     * <p>The {@code normal}/{@code video}/{@code focal} rule: an item whose value IS
     * the default serialises without the key, so a library nobody opened the splat
     * rows on stays byte-identical to one an older build wrote -- which is why moving
     * them into the counted chain needed no key every untouched item gains.
     */
    @Test
    public void theSplatLookRoundTripsAndADefaultIsOmitted() {
        LibraryItem tuned = item("a")
                .withSplatDepth(Surface.HOME, 1.5f)
                .withSplatSize(Surface.HOME, 0.7f)
                .withSplatBlur(Surface.HOME, 2.5f)
                .withSplatScale(Surface.HOME, 0.6f);
        LibraryItem r = Library.parse(libraryOf(tuned)).activeItem();
        assertEquals(1.5f, home(r).splatDepth, 1e-6f);
        assertEquals(0.7f, home(r).splatEps2d, 1e-6f);
        assertEquals(2.5f, home(r).splatBlur, 1e-6f);
        assertEquals(0.6f, home(r).splatScale, 1e-6f);

        String json = home(item("a")).toJson().toString();
        assertFalse(json, json.contains("splat_depth"));
        assertFalse(json, json.contains("splat_eps2d"));
        assertFalse(json, json.contains("splat_blur"));
        assertFalse(json, json.contains("splat_scale"));
        // And an untouched item's whole blob gains nothing either.
        String untouched = libraryOf(item("a"));
        assertFalse(untouched, untouched.contains("splat_"));
    }

    /**
     * A stored SIZE of 0 round-trips and is not rewritten upward.
     *
     * <p>The bug this pins: `0` used to be clamped UP to 0.45 on construction, so a
     * user dragging SIZE to the new bottom would have had the value silently raised
     * and the control would not move — a knob that lies about what it stores. It also
     * pins that `0` is written at all: it is not the default 0.5, so the
     * omit-if-default rule does not apply and the key must be present.
     *
     * <p><b>No schema bump is needed, and this is the reason.</b> `0` was previously
     * clamped up on write, so no stored blob contains it, and every existing photo
     * keeps exactly the value it had; the range simply grew downward. A negative is
     * still the bottom, so a hand-edited blob cannot resurrect the old floor either.
     */
    @Test
    public void aStoredSizeOfZeroRoundTripsAndIsNotRewrittenToTheOldFloor() {
        LibraryItem it = item("a").withSplatSize(Surface.HOME, 0f);
        assertEquals("0 is kept on write", 0f, home(it).splatEps2d, 0f);
        assertTrue("and it is written, because it is not the default",
                home(it).toJson().toString().contains("splat_eps2d"));

        LibraryItem r = Library.parse(libraryOf(it)).activeItem();
        assertEquals("0 survives the round trip", 0f, home(r).splatEps2d, 0f);
        assertEquals(Splat.EPS_2D_MIN, home(
                item("a").withSplatSize(Surface.HOME, -1f)).splatEps2d, 1e-6f);
    }

    /**
     * The splat look clamps to the useful band, and a value that cannot claim one
     * answers the default rather than a NaN.
     *
     * <p>Each clamp lives in {@link Splat}, and the store applies it once on
     * construction, so a hand-edited blob cannot put an out-of-range value on the
     * shader.
     */
    @Test
    public void theSplatLookIsClampedToItsUsefulRange() {
        SurfaceSettings high = home(item("a")
                .withSplatDepth(Surface.HOME, 99f)
                .withSplatSize(Surface.HOME, 9f)
                .withSplatBlur(Surface.HOME, 99f)
                .withSplatScale(Surface.HOME, 9f));
        assertEquals(Splat.DEPTH_MUL_MAX, high.splatDepth, 1e-6f);
        assertEquals(Splat.EPS_2D_MAX, high.splatEps2d, 1e-6f);
        assertEquals(Splat.BLUR_MAX, high.splatBlur, 1e-6f);
        assertEquals(Splat.RENDER_SCALE_MAX, high.splatScale, 1e-6f);

        SurfaceSettings low = home(item("a")
                .withSplatDepth(Surface.HOME, -1f)
                .withSplatSize(Surface.HOME, -9f)
                .withSplatBlur(Surface.HOME, -1f)
                .withSplatScale(Surface.HOME, 0.01f));
        assertEquals(Splat.DEPTH_MUL_MIN, low.splatDepth, 1e-6f);
        assertEquals(Splat.EPS_2D_MIN, low.splatEps2d, 1e-6f);
        assertEquals(Splat.BLUR_DEFAULT, low.splatBlur, 1e-6f);
        assertEquals(Splat.RENDER_SCALE_MIN, low.splatScale, 1e-6f);

        // A non-finite value answers the default, not the nearest bound.
        SurfaceSettings nan = home(item("a")
                .withSplatDepth(Surface.HOME, Float.NaN)
                .withSplatSize(Surface.HOME, Float.NaN)
                .withSplatBlur(Surface.HOME, Float.NaN)
                .withSplatScale(Surface.HOME, Float.NaN));
        assertEquals(Splat.DEPTH_MUL_DEFAULT, nan.splatDepth, 0f);
        assertEquals(Splat.EPS_2D_DEFAULT, nan.splatEps2d, 0f);
        assertEquals(Splat.BLUR_DEFAULT, nan.splatBlur, 0f);
        assertEquals(Splat.RENDER_SCALE_DEFAULT, nan.splatScale, 0f);
    }

    /**
     * The light normalises on the way in: azimuth wraps, elevation clamps.
     *
     * <p>Wrapped rather than clamped is the deliberate half. 0° and 360° are the
     * same light, so a clamp would leave a dead zone at the seam where a slider
     * appeared to move and the render did not. A stored -30 is therefore 330, and
     * both ends of that wrap must resolve to the same vector.
     */
    @Test
    public void theLightNormalisesOnWrite() {
        SurfaceSettings s = home(item("a").withLight(Surface.HOME, -30f, 120f));
        assertEquals(330f, s.lightAzimuth, 1e-6f);
        assertEquals(90f, s.lightElevation, 1e-6f);

        assertEquals("750 and 30 are the same azimuth",
                30f, home(item("a").withLight(Surface.HOME, 750f, 10f)).lightAzimuth, 1e-6f);
        assertEquals("a negative elevation is clamped to grazing, not reflected",
                0f, home(item("a").withLight(Surface.HOME, 10f, -40f)).lightElevation, 0f);

        // and the normalised value is what persists: a round trip cannot bring
        // back the out-of-range input and relight the photo differently.
        LibraryItem r = Library.parse(libraryOf(
                item("a").withLight(Surface.HOME, -30f, 120f))).activeItem();
        assertEquals(330f, home(r).lightAzimuth, 1e-4f);
        assertEquals(90f, home(r).lightElevation, 1e-4f);
    }

    /**
     * An absent light key reads as the default, not as zero.
     *
     * <p>Every blob written before this build is missing both keys, and 0/0 is a
     * different light (from the right, edge-on) than the historical
     * {@code (-0.35, 0.5, 0.75)}. A default of 0 would relight every photo on
     * upgrade — the exact failure the derived default constants exist to prevent.
     */
    @Test
    public void absentLightKeysReadAsTheDefault() {
        SurfaceSettings s = SurfaceSettings.fromJson(new JSONObject());
        assertEquals(LightDirection.DEFAULT_AZIMUTH_DEG, s.lightAzimuth, 0f);
        assertEquals(LightDirection.DEFAULT_ELEVATION_DEG, s.lightElevation, 0f);

        SurfaceSettings partial = SurfaceSettings.fromJson(
                new JSONObject("{\"depth\":50,\"light_elevation\":10}"));
        assertEquals("the present key is read", 10f, partial.lightElevation, 1e-6f);
        assertEquals("the absent one still defaults",
                LightDirection.DEFAULT_AZIMUTH_DEG, partial.lightAzimuth, 0f);
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
                .withAdjust(Surface.HOME, -40, 25)
                .withLight(Surface.HOME, 312.5f, 67.25f);
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
        assertEquals(312.5f, s.lightAzimuth, 1e-4f);
        assertEquals(67.25f, s.lightElevation, 1e-4f);
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
                .editSettings(Surface.LOCK, s -> s.with(25, 120, 10, 0f, 0f, 0f, 0f, 0, 0,
                        s.lightAzimuth, s.lightElevation, s.speed,
                        s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale, s.panZ));

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
     * A single-field ARCHIVE edit resets no render value.
     *
     * <p>{@link LibraryItem#withArchived} is an identity-only edit and must be
     * built like {@link LibraryItem#copy}, not through a constructor that names
     * only the flag — the exact trap that shipped once, when {@code withDepth}
     * reset Motion, brightness and shadow.
     */
    @Test
    public void archivingResetsNoRenderValue() {
        LibraryItem full = item("a")
                .withIntensity(Surface.HOME, 80)
                .withZoom(Surface.HOME, 150)
                .withMotion(Surface.HOME, 55)
                .withOrigin(Surface.HOME, 0.1f, -0.2f)
                .withPan(Surface.HOME, 30f, -40f)
                .withAdjust(Surface.HOME, -15, 20)
                .withLight(Surface.HOME, 205f, 35f);

        SurfaceSettings s = home(full.withArchived(true));
        assertEquals(80, s.depth);
        assertEquals(150, s.zoom);
        assertEquals("Motion was reset by archiving", 55, s.motion);
        assertEquals(0.1f, s.originX, 1e-6f);
        assertEquals(-40f, s.panY, 1e-3f);
        assertEquals(-15, s.brightness);
        assertEquals(20, s.shadow);
        assertEquals(205f, s.lightAzimuth, 1e-6f);
        assertEquals(35f, s.lightElevation, 1e-6f);
    }

    /**
     * A v3 item with no {@code archived} key reads live, and the key is omitted
     * when false — so an upgraded blob is byte-identical to one this build never
     * touched.
     */
    @Test
    public void anItemWithNoArchivedKeyReadsLiveAndDoesNotGainTheKey() {
        LibraryItem old = item("a");
        assertFalse("an old blob has no archived key, which means live",
                old.isArchived());
        assertFalse("a live item must not write the key",
                old.toJson().contains("\"archived\""));

        LibraryItem hidden = old.withArchived(true);
        assertTrue(Library.parse(Library.empty().withItem(hidden).toJson())
                .find("a").isArchived());
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
                .withAdjust(Surface.HOME, -15, 20)
                .withLight(Surface.HOME, 205f, 35f)
                .withSplatDepth(Surface.HOME, 1.5f)
                .withSplatSize(Surface.HOME, 0.7f)
                .withSplatBlur(Surface.HOME, 2.5f)
                .withSplatScale(Surface.HOME, 0.6f);

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
            assertEquals("the light was reset by an identity-only edit",
                    205f, s.lightAzimuth, 1e-6f);
            assertEquals(35f, s.lightElevation, 1e-6f);
            // The four splat values are the newest fields and therefore the most
            // likely to be dropped by a writer written before they existed -- the
            // withDepth scar exactly. An identity-only edit must carry all four.
            assertEquals(1.5f, s.splatDepth, 1e-6f);
            assertEquals(0.7f, s.splatEps2d, 1e-6f);
            assertEquals(2.5f, s.splatBlur, 1e-6f);
            assertEquals(0.6f, s.splatScale, 1e-6f);
        }
    }

    /**
     * Editing one splat row must not reset the other three -- or anything else.
     *
     * <p>The four look values were added after every writer existed, which is the
     * shape that shipped the {@code withDepth} bug: a constructor that defaults what
     * it is not given. Each of the four mutators goes through {@code editSettings}
     * and a {@code SurfaceSettings.with} that names all sixteen values, so this is
     * the guard made mechanical -- and asserted here so a future writer that takes
     * the short path fails a test rather than a user's cloud.
     */
    @Test
    public void aSplatRowEditDoesNotResetTheOthers() {
        LibraryItem full = item("a")
                .withIntensity(Surface.HOME, 80)
                .withZoom(Surface.HOME, 150)
                .withMotion(Surface.HOME, 55)
                .withAdjust(Surface.HOME, -15, 20)
                .withLight(Surface.HOME, 205f, 35f)
                .withSplatDepth(Surface.HOME, 1.5f)
                .withSplatSize(Surface.HOME, 0.7f)
                .withSplatBlur(Surface.HOME, 2.5f)
                .withSplatScale(Surface.HOME, 0.6f);

        LibraryItem afterDepth = full.withSplatDepth(Surface.HOME, 2f);
        LibraryItem afterSize = full.withSplatSize(Surface.HOME, 0.55f);
        LibraryItem afterBlur = full.withSplatBlur(Surface.HOME, 1f);
        LibraryItem afterScale = full.withSplatScale(Surface.HOME, 0.8f);

        for (LibraryItem after : new LibraryItem[]{
                afterDepth, afterSize, afterBlur, afterScale}) {
            SurfaceSettings s = home(after);
            assertEquals("Depth was reset by a splat-row edit", 80, s.depth);
            assertEquals(150, s.zoom);
            assertEquals("Motion was reset by a splat-row edit", 55, s.motion);
            assertEquals(-15, s.brightness);
            assertEquals(20, s.shadow);
            assertEquals("the light was reset by a splat-row edit",
                    205f, s.lightAzimuth, 1e-6f);
        }
        // And each edit moved exactly its own value.
        assertEquals(2f, home(afterDepth).splatDepth, 1e-6f);
        assertEquals(0.7f, home(afterDepth).splatEps2d, 1e-6f);
        assertEquals(2.5f, home(afterDepth).splatBlur, 1e-6f);
        assertEquals(0.6f, home(afterDepth).splatScale, 1e-6f);
        assertEquals(1.5f, home(afterSize).splatDepth, 1e-6f);
        assertEquals(0.55f, home(afterSize).splatEps2d, 1e-6f);
        assertEquals(2.5f, home(afterSize).splatBlur, 1e-6f);
        assertEquals(0.6f, home(afterSize).splatScale, 1e-6f);
        assertEquals(1.5f, home(afterBlur).splatDepth, 1e-6f);
        assertEquals(0.7f, home(afterBlur).splatEps2d, 1e-6f);
        assertEquals(1f, home(afterBlur).splatBlur, 1e-6f);
        assertEquals(0.6f, home(afterBlur).splatScale, 1e-6f);
        assertEquals(1.5f, home(afterScale).splatDepth, 1e-6f);
        assertEquals(0.7f, home(afterScale).splatEps2d, 1e-6f);
        assertEquals(2.5f, home(afterScale).splatBlur, 1e-6f);
        assertEquals(0.8f, home(afterScale).splatScale, 1e-6f);
    }

    /**
     * Editing the light must not reset the other values.
     *
     * <p>The same hazard as the test above, one field over: a {@code withLight} that
     * built its result from a constructor naming only the light would reset Depth,
     * Zoom, Motion, Origin, Pan and the adjustments. It goes through
     * {@code editSettings} and {@link SurfaceSettings#withLight}, which read every
     * other value from the settings being edited.
     */
    @Test
    public void editingTheLightDoesNotResetTheOthers() {
        LibraryItem before = item("a")
                .withIntensity(Surface.HOME, 80)
                .withZoom(Surface.HOME, 150)
                .withMotion(Surface.HOME, 55)
                .withOrigin(Surface.HOME, 0.1f, -0.2f)
                .withPan(Surface.HOME, 30f, -40f)
                .withAdjust(Surface.HOME, -15, 20);

        LibraryItem after = before.withLight(Surface.HOME, 205f, 35f);
        SurfaceSettings s = home(after);
        assertEquals(80, s.depth);
        assertEquals(150, s.zoom);
        assertEquals(55, s.motion);
        assertEquals(0.1f, s.originX, 1e-6f);
        assertEquals(-40f, s.panY, 1e-3f);
        assertEquals(-15, s.brightness);
        assertEquals(20, s.shadow);
        assertEquals(205f, s.lightAzimuth, 1e-6f);
        assertEquals(35f, s.lightElevation, 1e-6f);
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
     * A v3 blob's items adopt the global render mode, once.
     *
     * <p>The Motion precedent one version later: the mode used to be one
     * {@code render_3d} pref, so the library blob has no copy of it and the migration
     * has to be handed it. Dropping it would silently switch every user's 3D photos
     * back to the relief — a change nobody can file a bug about, because the photo
     * still renders.
     *
     * <p>Three halves, because each is a way to get it wrong: the value must be
     * copied when it was on, must be the relief when it was off, and an item that
     * already carries a mode must keep its own (this is a migration, not an
     * overwrite).
     */
    @Test
    public void theGlobalRenderModeIsCopiedOntoEveryItemOnUpgrade() {
        String v3 = "{\"version\":3,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1},"
                + "{\"id\":\"b\",\"image\":\"b.png\",\"added_at\":2}]}";
        Library on = Library.parse(v3, 33, true);
        assertEquals(RenderMode.SPLAT, on.find("a").renderMode);
        assertEquals("the second photo too", RenderMode.SPLAT, on.find("b").renderMode);

        Library off = Library.parse(v3, 33, false);
        assertEquals(RenderMode.RELIEF, off.find("a").renderMode);

        String withOwn = "{\"version\":3,\"items\":[{\"id\":\"a\",\"image\":\"a.png\","
                + "\"added_at\":1,\"render\":\"relief\"}]}";
        assertEquals("an item's own mode is not overwritten",
                RenderMode.RELIEF, Library.parse(withOwn, 33, true).find("a").renderMode);

        // The migrated form is v4, so the migration does not re-run on every load.
        assertFalse(Library.needsMigration(on.toJson()));
    }

    /**
     * A v4 blob's items adopt the global splat-look values, once.
     *
     * <p>The {@code render_3d} precedent one version later: the four look values used
     * to be global dev prefs, so the library blob has no copy of them and the
     * migration has to be handed them. Dropping them would silently reset every
     * cloud's look to the product defaults -- a change nobody can file a bug about,
     * because the cloud still renders.
     *
     * <p>Three halves, because each is a way to get it wrong: the values must be
     * copied onto every item, an item that already carries one must keep its own
     * (this is a migration, not an overwrite), and the migrated form must be v5 so
     * the step does not re-run.
     */
    @Test
    public void theGlobalSplatKnobsAreCopiedOntoEveryItemOnUpgrade() {
        String v4 = "{\"version\":4,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1,"
                + "\"surfaces\":{\"home\":{\"depth\":90,\"zoom\":100}}},"
                + "{\"id\":\"b\",\"image\":\"b.png\",\"added_at\":2,"
                + "\"surfaces\":{\"home\":{\"depth\":90,\"splat_depth\":2.0}}}]}";
        Library.LegacySplat tuned = Library.LegacySplat.of(1.5f, 0.7f, 2.5f, 0.6f);
        Library lib = Library.parse(v4, 33, false, tuned);

        SurfaceSettings a = home(lib.find("a"));
        assertEquals(1.5f, a.splatDepth, 1e-6f);
        assertEquals(0.7f, a.splatEps2d, 1e-6f);
        assertEquals(2.5f, a.splatBlur, 1e-6f);
        assertEquals(0.6f, a.splatScale, 1e-6f);
        assertEquals("the second photo gets the values it is missing",
                0.7f, home(lib.find("b")).splatEps2d, 1e-6f);
        assertEquals(2.5f, home(lib.find("b")).splatBlur, 1e-6f);
        assertEquals(0.6f, home(lib.find("b")).splatScale, 1e-6f);
        assertEquals("an item's own value is not overwritten",
                2.0f, home(lib.find("b")).splatDepth, 1e-6f);

        assertFalse("the migrated form must be v5",
                Library.needsMigration(lib.toJson()));
    }

    /**
     * The splat-knob migration is idempotent even when forced to run twice.
     *
     * <p><b>The re-parse that proves nothing, avoided.</b> Re-parsing
     * {@code once.toJson()} alone sees version 5 and skips the migration entirely, so
     * the test would pass identically whether the step is idempotent or simply never
     * runs. The version field is put back to 4 before the second parse, which forces
     * the step over content it already produced -- the case that actually breaks, a
     * migration that re-copies rather than only filling in what is absent.
     */
    @Test
    public void theSplatKnobMigrationIsIdempotentEvenWhenForcedToRunTwice() {
        String v4 = "{\"version\":4,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1,"
                + "\"surfaces\":{\"home\":{\"depth\":90,\"zoom\":100}}}]}";
        Library.LegacySplat tuned = Library.LegacySplat.of(1.5f, 0.7f, 2.5f, 0.6f);
        Library once = Library.parse(v4, 33, false, tuned);
        String alreadyMigrated = once.toJson()
                .replace("\"version\":" + Library.VERSION, "\"version\":4");
        Library twice = Library.parse(alreadyMigrated, 33, false, tuned);
        assertEquals("the whole blob must survive a second migration",
                once.toJson(), twice.toJson());
    }

    /**
     * Default splat knobs write nothing, so an untouched library keeps its items.
     *
     * <p>The migration is gated on the data being non-default: a user who never
     * opened the dev group had no pref, so every legacy value is its default and
     * nothing is written into any item. Only the version moves, which is what makes
     * the step one-time.
     */
    @Test
    public void defaultSplatKnobsWriteNothingSoAnUntouchedLibraryKeepsItsItems() {
        String v4 = "{\"version\":4,\"active\":\"a\",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\",\"added_at\":1,"
                + "\"surfaces\":{\"home\":{\"depth\":90,\"zoom\":100}}}]}";
        Library lib = Library.parse(v4, 33, false, Library.LegacySplat.defaults());
        assertFalse("no item may gain a splat key",
                lib.toJson().contains("splat_"));
        assertEquals(90, home(lib.find("a")).depth);
        assertEquals(SurfaceSettings.DEFAULT_SPLAT_DEPTH,
                home(lib.find("a")).splatDepth, 0f);
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

    // ------------------------------------------- a newer build's blob is not ours

    /**
     * A blob from a newer build is recognised, and is NOT a migration.
     *
     * <p>These are different questions and conflating them is how a gallery is
     * deleted. "Needs migrating" means ours-but-older, so we may write. "Newer" means
     * not ours at all, so we must not -- because parse returns an EMPTY library for
     * it, and anything that then persists that empty result has overwritten the
     * user's photos with nothing.
     *
     * <p>Measured: installing an older build over a newer one wiped the index, and
     * the only file that survived was the one the old build's own config still named.
     */
    @Test
    public void aNewerBlobIsNeitherCurrentNorAMigration() {
        String future = "{\"version\":" + (Library.VERSION + 5) + ",\"items\":["
                + "{\"id\":\"a\",\"image\":\"a.png\"}]}";
        assertTrue("must be recognised as newer", Library.isNewerVersion(future));
        assertFalse("and must never be rewritten", Library.needsMigration(future));
    }

    /** An unreadable blob is this build's problem, not a reason to leave it alone. */
    @Test
    public void anUnreadableBlobIsNotNewer() {
        assertFalse(Library.isNewerVersion(null));
        assertFalse(Library.isNewerVersion(""));
        assertFalse(Library.isNewerVersion("{ not json"));
    }

    /** Ours, and older, is the case that genuinely is a migration. */
    @Test
    public void ourOwnOlderBlobIsAMigrationAndNotNewer() {
        String v2 = "{\"version\":2,\"items\":[]}";
        assertFalse(Library.isNewerVersion(v2));
        assertTrue(Library.needsMigration(v2));
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
     * The demo records on the lock screen like any other photo.
     *
     * <p>It used to be refused, on the grounds that it had no image of its own to
     * render there. It has a file now — the seed copies its pair into filesDir
     * under the names its id derives — and the rest of that reasoning (the
     * platform's lock frame is captured from whatever is live) is true of every
     * photograph equally, so it belongs where it already is: the chooser asks
     * {@code SurfaceResolver.supportsPerSurfaceLock} before offering the choice at
     * all. A rule that named one row and not the rest is the special-casing this
     * index no longer has.
     */
    @Test
    public void theBundledDemoIsRecordedOnTheLockScreenLikeAnyOtherPhoto() {
        Library lib = Library.empty().withItem(demo())
                .withSelection(Library.BUNDLED_ID)
                .withAppliedTo(Surface.LOCK, true);
        assertTrue(lib.isOnBothSurfaces());
        assertEquals("which is exactly what an ordinary photo gets", lib.toJson(),
                Library.empty().withItem(demo()).withSelection(Library.BUNDLED_ID)
                        .withAppliedTo(Surface.LOCK, true).toJson());
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

    /** The demo, carrying the files the seed copies out of {@code res/raw}. */
    private static LibraryItem demo() {
        return LibraryItem.bundled(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH);
    }

    /**
     * The demo is recognised by its reserved id and has an image like everything
     * else in the gallery.
     *
     * <p>It had no image name at all until the seed began copying {@code res/raw}
     * into {@code filesDir}, and that missing file was the whole of its
     * special-casing: a depth panel that refused to generate for it, a map section
     * that could not offer to regenerate, a picked map with nowhere to go.
     */
    @Test
    public void theBundledItemHasTheOrdinaryFilesAndIsRecognised() {
        LibraryItem b = demo();
        assertTrue(b.isBundled());
        assertEquals(Library.BUNDLED_ID, b.id);
        assertEquals(LibraryTest.DEMO_IMAGE, b.imageName);
        assertEquals(LibraryTest.DEMO_DEPTH, b.depthName);
        assertTrue("and so it can stand out of its plane like any other row", b.hasDepth());
    }

    /**
     * The demo ships a normal, so a fresh install lands shaded — and the two-arg
     * seed is the flat-lit state, which is what an install that predates the asset
     * gets until the copy repairs it.
     */
    @Test
    public void theBundledItemIsShadedWhenTheNormalShips() {
        LibraryItem shaded = LibraryItem.bundled(
                LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "item-bundled-normal.png");
        assertEquals("item-bundled-normal.png", shaded.normalName);
        assertTrue(shaded.hasSuppliedNormals());
        assertEquals(NormalSource.Kind.SUPPLIED, NormalSource.forItem(shaded).kind());

        // The old two-arg seed names no normal, which is flat lit — a real state,
        // not a missing file, and the one the repair upgrades.
        assertNull(demo().normalName);
        assertFalse(demo().hasSuppliedNormals());
    }

    /**
     * The bundled repair decides each of the three files from the row's contents:
     * a fileless row gets all three, a row missing only the normal gets that one,
     * and a normal the row already has is never replaced.
     */
    @Test
    public void theBundledRepairCarriesTheNormalAndNeverReplacesOne() {
        Library seeded = Library.empty().withBundled(
                LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "n.png");
        assertEquals("n.png", seeded.find(Library.BUNDLED_ID).normalName);

        // A pre-migration row is named all three in one write.
        Library repaired = Library.empty()
                .withItem(LibraryItem.bundled(null, null))
                .withBundledFiles(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "n.png");
        LibraryItem demo = repaired.find(Library.BUNDLED_ID);
        assertEquals(LibraryTest.DEMO_IMAGE, demo.imageName);
        assertEquals(LibraryTest.DEMO_DEPTH, demo.depthName);
        assertEquals("n.png", demo.normalName);

        // A file-backed row missing only the normal is repaired, idempotently.
        Library once = Library.empty()
                .withBundled(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, null)
                .withBundledFiles(LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "n.png");
        assertEquals("n.png", once.find(Library.BUNDLED_ID).normalName);
        Library twice = once.withBundledFiles(
                LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "n.png");
        assertEquals("the repair must be idempotent", once.toJson(), twice.toJson());

        // A later copy does not overwrite a normal the row already carries.
        Library kept = once.withBundledFiles(
                LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH, "other.png");
        assertEquals("n.png", kept.find(Library.BUNDLED_ID).normalName);
    }

    /** The demo's settings are ordinary settings, and this class writes them. */
    @Test
    public void theBundledItemTakesAnEditLikeAnyOtherRow() {
        LibraryItem b = demo().withIntensity(Surface.HOME, 55).withZoom(Surface.LOCK, 130);
        assertEquals(55, b.settings(Surface.HOME).depth);
        assertEquals(130, b.settings(Surface.LOCK).zoom);
        assertEquals("the depth went to the surface it named, not both",
                LibraryTest.DEMO_DEPTH, b.depthName);
    }

    @Test
    public void theBundledItemIsRemovedLikeAnyOtherRow() {
        Library lib = Library.empty().withItem(item("a")).withItem(demo());
        assertEquals(2, lib.items().size());
        Library after = lib.remove(Library.BUNDLED_ID);
        assertEquals("the bundled item must go when it is asked to", 1, after.items().size());
        assertNull(after.find(Library.BUNDLED_ID));
        assertNotNull("and the library keeps its other row", after.find("a"));
    }

    @Test
    public void theBundledItemSurvivesAFileCleanupPass() {
        // A predicate that reports nothing exists -- the worst case, e.g. right
        // after a restore onto a device where filesDir came back empty. The demo
        // row is listed, so a cleanup keeps it and the next load puts its files
        // back: this is retention, not resurrection, and nothing in the cleanup
        // adds a row.
        Library lib = Library.empty().withItem(item("a")).withItem(demo());
        Library kept = lib.retainExisting(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(String name) {
                return false;
            }
        });
        assertEquals("only the bundled item may remain", 1, kept.items().size());
        assertNotNull("a listed row must not be dropped by a file check",
                kept.find(Library.BUNDLED_ID));
    }

    @Test
    public void theBundledItemSurvivesAJsonRoundTrip() {
        // A row stored before the demo got a file has no image name, and
        // fromJson drops any item without one — because for a user item that means
        // nothing to render. Without an exemption the pre-migration row was
        // silently dropped on every parse, so the one row the migration exists to
        // repair was the row the repair could never reach. The exemption is
        // parsing, not resurrection: it keeps what the blob carries and adds
        // nothing. What names the files is Library.withBundledFiles, and what seeds
        // the row is withBundled, and only into an empty library.
        Library lib = Library.empty()
                .withItem(LibraryItem.bundled(null, null))
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

    /**
     * Speed round-trips, defaults to 1x, and survives an edit that does not name it.
     *
     * <p>The three halves are the three ways this field can be wrong. A round trip
     * that fails loses the user's rate on the next read. A default that is not 1x
     * changes every clip that predates the field. And the third is the
     * {@code withDepth} scar one field over: a single-field writer that forgets
     * {@code speed} resets it silently, which is exactly how Motion, brightness and
     * shadow were reset by an edit that had been written before they existed.
     */
    @Test
    public void speedRoundTripsAndIsNotResetByOtherEdits() {
        LibraryItem item = LibraryItem.of("a", "a.png", "a-depth.png", 1L);
        assertEquals("a photo that has never been adjusted plays at 1x",
                SurfaceSettings.DEFAULT_SPEED, item.settings(Surface.HOME).speed);

        LibraryItem slow = item.withSpeed(Surface.HOME, 50);
        assertEquals(50, slow.settings(Surface.HOME).speed);
        assertEquals("0.5x", 0.5f, slow.settings(Surface.HOME).speedScale(), 1e-6f);

        // Through the blob, because that is where a lost field goes.
        LibraryItem parsed = Library.parse(
                Library.empty().withItem(slow).toJson()).find("a");
        assertEquals("the rate survives the round trip", 50,
                parsed.settings(Surface.HOME).speed);

        // And an edit that names something else leaves it alone.
        LibraryItem nudged = parsed.withIntensity(Surface.HOME, 42);
        assertEquals("a depth edit must not reset the rate", 50,
                nudged.settings(Surface.HOME).speed);
        LibraryItem zoomed = parsed.withZoom(Surface.HOME, 150);
        assertEquals("nor a zoom edit", 50, zoomed.settings(Surface.HOME).speed);

        // The bounds, clamped on read like every other value here.
        assertEquals(SurfaceSettings.SPEED_MIN,
                item.withSpeed(Surface.HOME, 1).settings(Surface.HOME).speed);
        assertEquals(SurfaceSettings.SPEED_MAX,
                item.withSpeed(Surface.HOME, 9000).settings(Surface.HOME).speed);
    }
}