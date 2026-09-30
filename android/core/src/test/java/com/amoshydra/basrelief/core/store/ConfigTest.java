package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the settings blob.
 *
 * <p>Two themes, and both have already cost something:
 *
 * <ol>
 *   <li><b>Nothing here may throw.</b> The wallpaper parses this on the GL
 *       thread during a cold start, where an exception means no wallpaper at
 *       all. So every shape of bad input has a defined answer rather than an
 *       exception.
 *   <li><b>The stored meaning must not depend on which build wrote it.</b> A
 *       v1 blob names an image and a depth that this build no longer renders
 *       from here, and losing them silently would lose the photo the install is
 *       currently showing.
 * </ol>
 *
 * <p>The per-photograph settings that used to live here are pinned by
 * {@link PerItemSettingsTest} instead, on the item that owns them now.
 */
public class ConfigTest {

    // ------------------------------------------------------------ round trip

    @Test
    public void roundTripsMotion() {
        for (int motion : new int[]{0, 1, 33, 66, 100}) {
            Config c = new Config(motion);
            assertEquals("motion " + motion, motion, Config.parse(c.toJson()).motion);
        }
    }

    @Test
    public void writesItsVersion() {
        JSONObjectish version = new JSONObjectish(Config.defaults().toJson());
        assertEquals(String.valueOf(Config.VERSION), version.get("version"));
    }

    /** The blob is one small number, and nothing that used to be global rode along. */
    @Test
    public void theBlobHoldsNothingButMotion() {
        String json = new Config(42).toJson();
        assertFalse("a dead field is a promise to migrate it later",
                json.contains("intensity") || json.contains("zoom")
                        || json.contains("orbit") || json.contains("pan")
                        || json.contains("image") || json.contains("depth")
                        || json.contains("active"));
    }

    // --------------------------------------------------------------- nothing

    @Test
    public void missingBlobYieldsDefaults() {
        assertEquals(Config.DEFAULT_MOTION, Config.parse(null).motion);
    }

    @Test
    public void emptyBlobYieldsDefaults() {
        for (String blank : new String[]{"", "   ", "\n", "\t"}) {
            assertEquals("blank: " + blank, Config.DEFAULT_MOTION, Config.parse(blank).motion);
        }
    }

    /**
     * The one that has to hold. A throw here is not a crash the user sees "for
     * a moment" — it is a wallpaper that never appears, on the GL thread, with
     * nothing left to recover it.
     */
    @Test
    public void corruptBlobYieldsDefaultsInsteadOfThrowing() {
        String[] nasty = {
                "{", "}", "[]", "nonsense", "{\"version\":", "{\"motion\":",
                "{\"motion\":\"lots\"}", "{\"motion\":null}", "null", "3", "\"str\"",
                "{\"version\":1,\"motion\":[1,2]}", "{\"motion\":1e400}",
        };
        for (String bad : nasty) {
            Config c = Config.parse(bad);
            assertNotNull("must not return null for: " + bad, c);
            assertEquals("bad blob: " + bad, Config.DEFAULT_MOTION, c.motion);
        }
    }

    @Test
    public void newerVersionFallsBackToDefaults() {
        String future = "{\"version\":" + (Config.VERSION + 1) + ",\"motion\":99}";
        assertEquals("guessing at a newer shape is worse than starting clean",
                Config.DEFAULT_MOTION, Config.parse(future).motion);
    }

    /**
     * A motion we failed to read falls back to the default, not to zero.
     *
     * <p>Zero is a real value meaning "the relief barely responds to tilt", so
     * choosing it for a field we could not parse would look like a fault in the
     * response curve rather than a fault in the parse — and would be far harder
     * to trace back to storage.
     */
    @Test
    public void anUnreadableMotionFallsBackToTheDefaultNotToZero() {
        assertEquals(Config.DEFAULT_MOTION, Config.parse("{\"motion\":\"x\"}").motion);
        assertEquals(Config.DEFAULT_MOTION, Config.parse("{\"version\":2}").motion);
        assertEquals(Config.DEFAULT_MOTION, Config.parse("{}").motion);
    }

    /**
     * The case that motivated separating "out of range" from "not a number".
     *
     * <p>{@code optInt} casts whatever it finds, and every one of these casts to
     * 0 or to MAX_VALUE rather than failing. On this field 0 is a real setting,
     * so a corrupt blob would have produced a working app that no longer responds
     * to tilt, with nothing on screen to suggest the stored value was at fault.
     */
    @Test
    public void aNonFiniteMotionBecomesTheDefaultNotTheStrongestSetting() {
        for (String bad : new String[]{
                "{\"motion\":1e400}", "{\"motion\":-1e400}", "{\"motion\":1e999}",
                "{\"motion\":-1e999}", "{\"motion\":1e309}",
        }) {
            assertEquals("must not clamp a non-finite number: " + bad,
                    Config.DEFAULT_MOTION, Config.parse(bad).motion);
        }
    }

    /**
     * ...whereas a merely out-of-range value is clamped, because that is a known
     * older range rather than corruption. The line is finiteness, not magnitude:
     * a value beyond {@code Long} parses as a huge finite double, which is a
     * range problem like any other, so it clamps. Drawing the line at "seems too
     * big to be real" would be a guess about the writer's intent.
     */
    @Test
    public void anOutOfRangeMotionIsStillClamped() {
        assertEquals(100, Config.parse("{\"motion\":150}").motion);
        assertEquals(100, Config.parse("{\"motion\":400}").motion);
        assertEquals(0, Config.parse("{\"motion\":-5}").motion);
        assertEquals(100, Config.parse("{\"motion\":99999999999999999999}").motion);
        assertEquals(0, Config.parse("{\"motion\":-99999999999999999999}").motion);
    }

    // -------------------------------------------------------------- clamping

    @Test
    public void motionIsClampedNotWrapped() {
        assertEquals(100, new Config(500).motion);
        assertEquals(0, new Config(-7).motion);
        assertEquals(100, Config.parse("{\"motion\":99999}").motion);
        assertEquals(0, Config.parse("{\"motion\":-99999}").motion);
    }

    // The 0..100 slider -> 0..3 render-scale mapping is no longer pinned here.
    // Motion belongs to a photograph, so the value that gets scaled lives on
    // SurfaceSettings and the mapping is asserted in PerItemSettingsTest
    // (derivedAccessorsMatchTheOnesConfigHad). A copy of the formula left here
    // would be a second one to keep in step with the first.

    // -------------------------------------------------------------- migration

    /**
     * A v1 blob's Motion is the one field that survives, so it must be read
     * rather than defaulted. Losing it would reset the response curve on upgrade,
     * which is a silent behaviour change to every existing install.
     */
    @Test
    public void aV1BlobKeepsItsMotion() {
        String v1 = "{\"version\":1,\"image_path\":\"a.png\",\"depth_path\":\"a-depth.png\","
                + "\"intensity\":40,\"zoom\":150,\"motion\":77,"
                + "\"orbit_x\":0.1,\"orbit_y\":-0.2,\"pan_x\":-135.25,\"pan_y\":-632.5}";
        assertEquals(77, Config.parse(v1).motion);
    }

    @Test
    public void aV0BlobStillParses() {
        assertEquals(5, Config.parse("{\"motion\":5}").motion);
    }

    // ------------------------------------------------------- legacy file read

    /**
     * Migration only: the pair a v1 install is showing has to be readable, or the
     * upgrade drops the photo that install is currently displaying.
     */
    @Test
    public void legacyFilesReadsThePairAV1InstallIsShowing() {
        String v1 = "{\"version\":1,\"image_path\":\"custom_image.png\","
                + "\"depth_path\":\"custom_depth.png\"}";
        String[] files = Config.legacyFiles(v1);
        assertNotNull(files);
        assertEquals("custom_image.png", files[0]);
        assertEquals("custom_depth.png", files[1]);
    }

    @Test
    public void legacyFilesHandlesAFlatItem() {
        String v1 = "{\"version\":1,\"image_path\":\"a.png\"}";
        String[] files = Config.legacyFiles(v1);
        assertNotNull(files);
        assertEquals("a.png", files[0]);
        assertNull("a missing depth is a flat item, not a reason to skip", files[1]);
    }

    /**
     * A restored backup carries absolute paths from the old device's filesDir.
     * The index stores bare filenames, so an absolute path that reached it
     * unresolved would leave a gallery entry that can never be opened — this is
     * the same data-loss case {@code PrefFilesTest} covers for a single setting.
     */
    @Test
    public void legacyFilesStripsARestoredAbsolutePath() {
        String v1 = "{\"version\":1,\"image_path\":"
                + "\"/data/user/0/com.amoshydra.basrelief/files/beach.png\"}";
        String[] files = Config.legacyFiles(v1);
        assertNotNull(files);
        assertEquals("beach.png", files[0]);
    }

    @Test
    public void legacyFilesReturnsNullWhenThereIsNothingToAdopt() {
        // v2 already: the files belong to items, not to here
        assertNull(Config.legacyFiles(Config.defaults().toJson()));
        // a newer build's shape is not ours to read
        assertNull(Config.legacyFiles("{\"version\":99,\"image_path\":\"a.png\"}"));
        // no image named, or nothing stored at all
        assertNull(Config.legacyFiles("{\"version\":1,\"image_path\":null}"));
        assertNull(Config.legacyFiles("{\"version\":1}"));
        assertNull(Config.legacyFiles(null));
        assertNull(Config.legacyFiles(""));
        assertNull(Config.legacyFiles("{oops"));
    }

    /**
     * Not a live accessor, so it must not answer for a current blob. If it ever
     * did, an upgrade run on a v2 install could adopt a file that has since been
     * deleted by hand.
     */
    @Test
    public void legacyFilesDoesNotAnswerForTheCurrentVersion() {
        String v2 = "{\"version\":2,\"motion\":33}";
        assertNull(Config.legacyFiles(v2));
    }

    // --------------------------------------------------------------- helpers

    /**
     * A one-field read of the serialised form, so the version assertion does not
     * drag in a JSON dependency the rest of this file does not use.
     */
    private static final class JSONObjectish {
        private final String json;

        JSONObjectish(String json) {
            this.json = json;
        }

        String get(String key) {
            String needle = "\"" + key + "\":";
            int at = json.indexOf(needle);
            if (at < 0) return null;
            int from = at + needle.length();
            int to = from;
            while (to < json.length() && "-0123456789".indexOf(json.charAt(to)) >= 0) to++;
            return from == to ? null : json.substring(from, to);
        }
    }
}
