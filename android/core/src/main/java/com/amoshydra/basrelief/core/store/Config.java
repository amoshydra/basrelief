package com.amoshydra.basrelief.core.store;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The user's settings as one versioned blob, so their meaning does not depend
 * on which build wrote it.
 *
 * <p><b>Why this exists.</b> The settings used to live as nine loose
 * SharedPreferences keys with no version. That is only unambiguous while the
 * meaning of every key is frozen forever: an earlier build wrote {@code
 * intensity} on a 0..200 scale, and because the reader clamped 0..100 the value
 * silently changed meaning instead of being migrated. The same class of problem
 * is waiting for {@code zoom} and {@code motion}, which are stored as 0..100 but
 * mapped onto 0.25..2.0 and 0..3.
 *
 * <p><b>Why it is a single JSON string.</b> One pref key means one atomic write,
 * so a reader can never observe half-updated settings, and adding a field later
 * does not mean adding a key that older builds will not know to ignore.
 *
 * <p><b>Never throws.</b> {@link #parse} returns defaults for anything it cannot
 * understand — missing, corrupt, or written by a newer build. The wallpaper
 * parses this on the GL thread during a cold start, where an exception means no
 * wallpaper at all, which is a far worse outcome than falling back to defaults.
 */
public final class Config {

    /** Bump when the meaning of a stored field changes, and add a migration. */
    public static final int VERSION = 1;

    /** The single SharedPreferences key the blob lives under. */
    public static final String KEY = "config";

    public static final int DEFAULT_INTENSITY = 90;
    public static final int DEFAULT_ZOOM = 100;
    public static final int DEFAULT_MOTION = 33;

    public static final int INTENSITY_MIN = 0;
    public static final int INTENSITY_MAX = 100;
    public static final int ZOOM_MIN = 25;   // 0.25x
    public static final int ZOOM_MAX = 200;  // 2.0x
    public static final int MOTION_MIN = 0;
    public static final int MOTION_MAX = 100; // scaled to 0..3 internally

    /** Filenames relative to filesDir, or null. See {@link PrefFiles}. */
    public final String imagePath;
    public final String depthPath;
    public final int intensity; // 0..100
    public final int zoom;      // 25..200
    public final int motion;    // 0..100
    public final float orbitX;  // -0.5..0.5
    public final float orbitY;
    public final float panX;
    public final float panY;

    public Config(String imagePath, String depthPath, int intensity, int zoom, int motion,
                  float orbitX, float orbitY, float panX, float panY) {
        this.imagePath = imagePath;
        this.depthPath = depthPath;
        this.intensity = clamp(intensity, INTENSITY_MIN, INTENSITY_MAX);
        this.zoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        this.motion = clamp(motion, MOTION_MIN, MOTION_MAX);
        this.orbitX = clamp(orbitX, -0.5f, 0.5f);
        this.orbitY = clamp(orbitY, -0.5f, 0.5f);
        this.panX = panX;
        this.panY = panY;
    }

    public static Config defaults() {
        return new Config(null, null, DEFAULT_INTENSITY, DEFAULT_ZOOM, DEFAULT_MOTION,
                0f, 0f, 0f, 0f);
    }

    // ------------------------------------------------------------- derived

    /** Depth as a fraction of the reachable maximum — see CameraModel.setDepth. */
    public float depthFraction() {
        return intensity / 100f;
    }

    public float zoomScale() {
        return zoom / 100f;
    }

    public float motionScale() {
        return motion / 100f * 3f;
    }

    // ---------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            o.put("intensity", intensity);
            o.put("zoom", zoom);
            o.put("motion", motion);
            o.put("orbit_x", (double) orbitX);
            o.put("orbit_y", (double) orbitY);
            o.put("pan_x", (double) panX);
            o.put("pan_y", (double) panY);
            // Absent rather than null: JSONObject drops nulls anyway, and a
            // missing key reads as "not set" without a special case.
            if (imagePath != null) o.put("image_path", imagePath);
            if (depthPath != null) o.put("depth_path", depthPath);
        } catch (JSONException e) {
            throw new AssertionError("Config.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Parse a stored blob, falling back to defaults for anything unusable.
     *
     * @param raw the stored string, or null if nothing has been written yet
     */
    public static Config parse(String raw) {
        if (raw == null || raw.isEmpty()) return defaults();
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (JSONException e) {
            return defaults(); // corrupt
        }
        int version = o.optInt("version", 0);
        if (version > VERSION) {
            // A newer build wrote this. Its fields may mean something else, so
            // guessing would be worse than starting clean.
            return defaults();
        }
        if (version < VERSION) {
            o = migrate(o, version);
        }
        return new Config(
                o.isNull("image_path") ? null : o.optString("image_path", null),
                o.isNull("depth_path") ? null : o.optString("depth_path", null),
                o.optInt("intensity", DEFAULT_INTENSITY),
                o.optInt("zoom", DEFAULT_ZOOM),
                o.optInt("motion", DEFAULT_MOTION),
                (float) o.optDouble("orbit_x", 0d),
                (float) o.optDouble("orbit_y", 0d),
                (float) o.optDouble("pan_x", 0d),
                (float) o.optDouble("pan_y", 0d));
    }

    /**
     * Bring an older blob up to {@link #VERSION}.
     *
     * <p>Currently a no-op for v0, because v0 is the first versioned schema —
     * the pre-blob era was nine loose keys, handled by
     * {@link #fromLegacyKeys}. It exists so the next change has an obvious
     * place to go, and so the parse path is already shaped like a real one.
     */
    private static JSONObject migrate(JSONObject o, int fromVersion) {
        return o; // no older blob schema exists yet
    }

    /**
     * Fold the pre-blob flat keys into a v1 config, so an existing install
     * keeps its settings across the upgrade.
     *
     * <p>The one lossy part is {@code intensity}: the oldest build wrote it on a
     * 0..200 scale and nothing recorded which build wrote a given value, so a
     * stored 150 is clamped to 100 rather than guessed at. That is a deliberate
     * one-time reinterpretation — after this, the value is unambiguous.
     *
     * @param legacy value for each key, as it would have been read before
     */
    public static Config fromLegacyKeys(String imagePath, String depthPath,
                                        int intensity, int zoom, int motion,
                                        float orbitX, float orbitY,
                                        float panX, float panY) {
        return new Config(imagePath, depthPath, intensity, zoom, motion,
                orbitX, orbitY, panX, panY);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
