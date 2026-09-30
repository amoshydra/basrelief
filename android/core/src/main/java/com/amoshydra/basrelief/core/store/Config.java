package com.amoshydra.basrelief.core.store;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The settings that are genuinely global.
 *
 * <p><b>What moved and why.</b> This used to hold the image, the depth map,
 * Depth, Zoom and the framing, on top of Motion. All of those are properties of
 * <i>a photograph</i> rather than of the app, so they now live on
 * {@link LibraryItem} -- otherwise every photo in a gallery would share one
 * framing. What is left is Motion, which describes how this device is held and
 * moved.
 *
 * <p><b>Why the selection is not here either.</b> An earlier draft of this shrink
 * kept {@code activeItemId} in both this class and {@link Library}. That is two
 * owners of one value, and the two can disagree: {@link Library#of()} already
 * guarantees its selection names a real item, so a second copy in the config can
 * only ever be a copy that dangles. The index owns the selection, and
 * {@code activeItem()} returns null for "the bundled demo" -- a state that
 * renders, so it needs no representation of its own.
 *
 * <p><b>Never throws.</b> {@link #parse} returns defaults for anything it cannot
 * understand -- missing, corrupt, or written by a newer build. The wallpaper
 * parses this on the GL thread during a cold start, where an exception means no
 * wallpaper at all, which is a far worse outcome than falling back.
 */
public final class Config {

    /**
     * v2: the per-photograph settings left this blob, so it now holds only Motion.
     * v3 of the *library* took even that away -- Motion became a property of a
     * photograph -- so this blob is written no further and read once per load, by
     * the library's migration, to give a pre-v3 photo the Motion its owner chose.
     * A v1 blob is still readable; see {@link #migrate}.
     */
    public static final int VERSION = 2;

    /** The single SharedPreferences key the blob lives under. */
    public static final String KEY = "config";

    public static final int DEFAULT_MOTION = 33;
    public static final int MOTION_MIN = 0;
    public static final int MOTION_MAX = 100; // scaled to 0..3 internally

    /**
     * 0..100, and now read-only in practice.
     *
     * <p>Nothing writes it. It is the one value the library blob cannot supply,
     * because it never lived there, and dropping it would silently reset every
     * user's wallpaper speed on upgrade — a change nobody can file a bug about,
     * because the wallpaper still moves.
     */
    public final int motion;

    public Config(int motion) {
        this.motion = clamp(motion, MOTION_MIN, MOTION_MAX);
    }

    public static Config defaults() {
        return new Config(DEFAULT_MOTION);
    }


    // ------------------------------------------------------------ serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            o.put("motion", motion);
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
        if (raw == null || raw.trim().isEmpty()) return defaults();
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
        return new Config(readMotion(o));
    }

    /**
     * Motion, distinguishing a value out of range from a value that is not a
     * number at all.
     *
     * <p>Those two need different answers. A build that stored 0..200 hands us
     * 150, and clamping it into 0..100 is the right recovery — that is a known
     * older range. But a blob containing {@code 1e400} hands us an infinity, and
     * casting that to an int yields 0: <i>silently the strongest possible
     * setting</i>, on a field where 0 is a real value meaning "the relief barely
     * responds". A user whose motion reset to no response would have no way to
     * tell that from the app having decided it for them.
     *
     * <p>So a value that is not a finite number becomes the default, and only
     * an out-of-range finite number is clamped.
     */
    private static int readMotion(JSONObject o) {
        Object v = o.opt("motion");
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            // NaN and the infinities have no meaningful int, and every one of
            // them casts to 0 or MAX_VALUE rather than failing.
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                return clamp((int) d, MOTION_MIN, MOTION_MAX);
            }
        }
        return DEFAULT_MOTION;
    }

    /**
     * v0 -> v1 -> v2.
     *
     * <p>v2 removed the image, depth, Depth, Zoom and framing fields. Nothing is
     * rewritten here: the files an install already has are adopted into the index
     * by {@link #legacyFiles}, which the caller reads once, and Motion is the
     * only surviving field.
     *
     * <p>Idempotent, because reading an absent key yields a default.
     */
    private static JSONObject migrate(JSONObject o, int fromVersion) {
        return o;
    }

    // ------------------------------------------------------------ migration

    /**
     * The image and depth a v1 blob named, read once by the migration.
     *
     * <p><b>Migration only.</b> These are not live fields and must not be used
     * to resolve what to render -- the index does that, from items that carry
     * their own file names. This exists because a v1 install's photo is
     * recorded <i>only</i> in the old config blob, so dropping the field without
     * reading it first would lose the photo that install is currently showing.
     *
     * <p>Returns {@code {image, depth}}, with nulls for anything absent, or
     * null if the blob is unusable. Both names are normalised, so a stored
     * absolute path cannot escape into the index.
     *
     * @param raw the stored config blob, of any version
     */
    public static String[] legacyFiles(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
        if (o.optInt("version", 0) > 1) return null; // a newer build's shape is not ours to read
        String image = LibraryItem.normaliseName(o.optString("image_path", null));
        if (image == null) return null; // nothing to adopt
        return new String[]{image, LibraryItem.normaliseName(o.optString("depth_path", null))};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
