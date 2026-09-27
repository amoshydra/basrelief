package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the settings blob.
 *
 * <p>The theme is that the stored meaning must not depend on which build wrote
 * it, and that nothing here may throw — the wallpaper parses this on the GL
 * thread during a cold start, where an exception means no wallpaper at all.
 */
public class ConfigTest {

    private static Config sample() {
        return new Config("img.png", "dep.png", 60, 150, 40, 0.1f, -0.2f, 0.3f, -0.4f);
    }

    @Test
    public void roundTripsEveryField() {
        Config c = sample();
        Config r = Config.parse(c.toJson());
        assertEquals("img.png", r.imagePath);
        assertEquals("dep.png", r.depthPath);
        assertEquals(60, r.intensity);
        assertEquals(150, r.zoom);
        assertEquals(40, r.motion);
        assertEquals(0.1f, r.orbitX, 1e-6f);
        assertEquals(-0.2f, r.orbitY, 1e-6f);
        assertEquals(0.3f, r.panX, 1e-6f);
        assertEquals(-0.4f, r.panY, 1e-6f);
    }

    @Test
    public void writesItsVersion() {
        assertTrue(Config.parse(sample().toJson()).toJson().contains("\"version\":" + Config.VERSION));
    }

    /** Absent paths must stay absent, not become the string "null". */
    @Test
    public void noImageSurvivesAsNullNotTheStringNull() {
        Config c = Config.parse(Config.defaults().toJson());
        assertNull(c.imagePath);
        assertNull(c.depthPath);
    }

    // ------------------------------------------------------- never throws

    @Test
    public void missingBlobYieldsDefaults() {
        assertEquals(Config.DEFAULT_INTENSITY, Config.parse(null).intensity);
        assertEquals(Config.DEFAULT_ZOOM, Config.parse(null).zoom);
        assertEquals(Config.DEFAULT_MOTION, Config.parse(null).motion);
        assertNull(Config.parse(null).imagePath);
    }

    @Test
    public void emptyBlobYieldsDefaults() {
        assertEquals(Config.DEFAULT_INTENSITY, Config.parse("").intensity);
        assertEquals(Config.DEFAULT_INTENSITY, Config.parse("   ").intensity);
    }

    /**
     * The one that matters most on a cold start: a truncated write must not
     * take the wallpaper down.
     */
    @Test
    public void corruptBlobYieldsDefaultsInsteadOfThrowing() {
        for (String bad : new String[]{
                "{", "not json at all", "[]", "{\"version\":", "{\"intensity\":\"abc\"}",
                "\u0000\u0001", "{\"version\":1,\"zoom\":[1,2,3]}"}) {
            Config c = Config.parse(bad);
            assertNotNull("threw or returned null for: " + bad, c);
            assertEquals("bad blob should fall back to defaults: " + bad,
                    Config.DEFAULT_INTENSITY, c.intensity);
        }
    }

    /** Guessing at a future schema would be worse than starting clean. */
    @Test
    public void newerVersionFallsBackToDefaults() {
        Config c = Config.parse("{\"version\":" + (Config.VERSION + 1) + ",\"intensity\":42}");
        assertEquals(Config.DEFAULT_INTENSITY, c.intensity);
    }

    // ------------------------------------------------------------ clamping

    @Test
    public void outOfRangeIntsAreClampedNotWrapped() {
        assertEquals(Config.INTENSITY_MAX, new Config(null, null, 500, 100, 33, 0, 0, 0, 0).intensity);
        assertEquals(Config.INTENSITY_MIN, new Config(null, null, -7, 100, 33, 0, 0, 0, 0).intensity);
        // zoom below 25% would invert the camera
        assertEquals(Config.ZOOM_MIN, new Config(null, null, 50, 1, 33, 0, 0, 0, 0).zoom);
        assertEquals(Config.ZOOM_MAX, new Config(null, null, 50, 9999, 33, 0, 0, 0, 0).zoom);
        assertEquals(Config.MOTION_MAX, new Config(null, null, 50, 100, 400, 0, 0, 0, 0).motion);
    }

    @Test
    public void orbitIsClampedToThePanRange() {
        Config c = new Config(null, null, 50, 100, 33, 9f, -9f, 0, 0);
        assertEquals(0.5f, c.orbitX, 1e-6f);
        assertEquals(-0.5f, c.orbitY, 1e-6f);
    }

    /**
     * Pan is in PIXELS (CameraModel consumes panX / surfaceW), so its range is the
     * surface size and this class must not clamp it. Clamping it to orbit's
     * +/-0.5 range silently turned a stored -135px pan into -0.5px, i.e. no pan
     * at all, and it survived review because the value still round-tripped.
     */
    @Test
    public void panIsPixelsAndMustNotBeClampedToAFraction() {
        Config c = new Config(null, null, 50, 100, 33, 0f, 0f, -135.25f, -632.5f);
        assertEquals(-135.25f, c.panX, 0f);
        assertEquals(-632.5f, c.panY, 0f);
        // and it must survive a round trip unmangled
        Config r = Config.parse(c.toJson());
        assertEquals(-135.25f, r.panX, 1e-3f);
        assertEquals(-632.5f, r.panY, 1e-3f);
        // large values pass through too; the renderer clamps to the real surface
        assertEquals(5000f, new Config(null, null, 50, 100, 33, 0, 0, 5000f, 5000f).panX, 0f);
    }

    /**
     * The old 0..200 intensity scale. A stored value's meaning is unrecoverable
     * — nothing recorded which build wrote it — so it is clamped once, here,
     * and is unambiguous from v1 onward.
     */
    @Test
    public void legacyWideIntensityIsClampedOnceOnUpgrade() {
        Config c = Config.fromLegacyKeys("i.png", "d.png", 150, 100, 33, 0, 0, 0, 0);
        assertEquals(100, c.intensity);
        assertEquals("i.png", c.imagePath);
    }

    // --------------------------------------------------- derived mappings

    /**
     * The three 0..100 sliders map onto three different internal ranges. These
     * are the conversions the renderer used to inline; if one drifts, Depth or
     * Motion silently stops doing what the slider says.
     */
    @Test
    public void sliderToInternalMappings() {
        assertEquals(0f, new Config(null, null, 0, 100, 0, 0, 0, 0, 0).depthFraction(), 1e-6f);
        assertEquals(1f, new Config(null, null, 100, 100, 0, 0, 0, 0, 0).depthFraction(), 1e-6f);
        assertEquals(0.6f, new Config(null, null, 60, 100, 0, 0, 0, 0, 0).depthFraction(), 1e-6f);

        assertEquals(1f, new Config(null, null, 0, 100, 0, 0, 0, 0, 0).zoomScale(), 1e-6f);
        assertEquals(2f, new Config(null, null, 0, 200, 0, 0, 0, 0, 0).zoomScale(), 1e-6f);
        assertEquals(0.25f, new Config(null, null, 0, 25, 0, 0, 0, 0, 0).zoomScale(), 1e-6f);

        assertEquals(0f, new Config(null, null, 0, 100, 0, 0, 0, 0, 0).motionScale(), 1e-6f);
        assertEquals(3f, new Config(null, null, 0, 100, 100, 0, 0, 0, 0).motionScale(), 1e-6f);
    }

    /** Depth 0% must be exactly flat — the property that regressed once already. */
    @Test
    public void depthZeroIsExactlyZeroNotNearly() {
        Config flat = new Config(null, null, 0, 100, 33, 0, 0, 0, 0);
        assertEquals(0f, flat.depthFraction(), 0f); // delta 0: must be bit-exact
        // and it must survive a round trip as exactly zero
        assertEquals(0f, Config.parse(flat.toJson()).depthFraction(), 0f);
    }
}
