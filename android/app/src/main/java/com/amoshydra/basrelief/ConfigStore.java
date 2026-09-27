package com.amoshydra.basrelief;

import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.PrefFiles;

import java.io.File;

/**
 * The single place the app reads and writes its settings.
 *
 * <p>Before this existed, the studio and the wallpaper each read and wrote nine
 * loose keys, so a value's meaning depended on which build wrote it and a reader
 * could observe a half-applied change. Everything now goes through
 * {@link Config}, stored as one JSON blob under {@link Config#KEY}, with the
 * legacy keys migrated on first read.
 *
 * <p>Both surfaces use this class, so the interpretation of a stored value is
 * defined once. Note the legacy keys are NOT written alongside the blob: doing so
 * would reintroduce two sources of truth, and a reader could disagree with
 * itself.
 */
final class ConfigStore {

    private ConfigStore() {
    }

    /**
     * Read the config, migrating from the legacy loose keys on first call.
     *
     * <p>Migration is idempotent: once the blob exists it is authoritative, so a
     * second call cannot resurrect a legacy value.
     */
    static Config load(SharedPreferences prefs, File filesDir) {
        String blob = prefs.getString(Config.KEY, null);
        if (blob != null) return Config.parse(blob);

        Config migrated = Config.fromLegacyKeys(
                prefs.getString("image_path", null),
                prefs.getString("depth_path", null),
                prefs.getInt("intensity", Config.DEFAULT_INTENSITY),
                clampInt(prefs.getInt("zoom", Config.DEFAULT_ZOOM),
                        Config.ZOOM_MIN, Config.ZOOM_MAX),
                clampInt(prefs.getInt("motion", Config.DEFAULT_MOTION),
                        Config.MOTION_MIN, Config.MOTION_MAX),
                clampFloat(prefs.getFloat("orbit_x", 0f), -0.5f, 0.5f),
                clampFloat(prefs.getFloat("orbit_y", 0f), -0.5f, 0.5f),
                clampFloat(prefs.getFloat("pan_x", 0f), -0.5f, 0.5f),
                clampFloat(prefs.getFloat("pan_y", 0f), -0.5f, 0.5f));
        save(prefs, migrated);

        // Clear the old keys so a stale legacy value can never be re-read after
        // the blob is written — and so a backup does not carry two copies.
        SharedPreferences.Editor e = prefs.edit();
        e.remove("image_path").remove("depth_path").remove("intensity")
                .remove("zoom").remove("motion")
                .remove("orbit_x").remove("orbit_y").remove("pan_x").remove("pan_y");
        e.apply();
        return migrated;
    }

    static void save(SharedPreferences prefs, Config c) {
        prefs.edit().putString(Config.KEY, c.toJson()).apply();
    }

    /**
     * Read-modify-write in one step.
     *
     * <p>The studio changes one setting at a time, and a plain
     * {@code load(); mutate; save()} would race with the wallpaper's own reads.
     * Committing synchronously keeps the read-modify-write atomic with respect to
     * the other process sharing these prefs.
     */
    static Config update(SharedPreferences prefs, File filesDir, Editor editor) {
        Config next = editor.edit(load(prefs, filesDir));
        prefs.edit().putString(Config.KEY, next.toJson()).commit();
        return next;
    }

    interface Editor {
        Config edit(Config c);
    }

    /**
     * Drop a stored image/depth if the file is not present on this device.
     *
     * <p>A restored backup carries filenames, not paths, and the file may simply
     * not exist here. Keeping a dangling value would retry it on every load and
     * leave the status line claiming content that cannot be shown.
     */
    static Config dropMissingFiles(SharedPreferences prefs, File filesDir, Config c) {
        String img = PrefFiles.resolve(filesDir, c.imagePath) != null ? c.imagePath : null;
        String dep = PrefFiles.resolve(filesDir, c.depthPath) != null ? c.depthPath : null;
        if (img == c.imagePath && dep == c.depthPath) return c; // nothing to do
        Config fixed = new Config(img, dep, c.intensity, c.zoom, c.motion,
                c.orbitX, c.orbitY, c.panX, c.panY);
        save(prefs, fixed);
        return fixed;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
