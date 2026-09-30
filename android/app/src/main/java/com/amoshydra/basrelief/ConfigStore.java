package com.amoshydra.basrelief;

import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.store.Config;

/**
 * Motion, stored as one JSON blob.
 *
 * <p><b>This class used to be the app's settings.</b> It held the image, the
 * depth map, Depth, Zoom and the framing, read and written by both surfaces, with
 * a {@code dropMissingFiles} pass to keep a restored backup honest. Those
 * settings are properties of a photograph and now live on
 * {@link com.amoshydra.basrelief.core.store.LibraryItem}, so what is left is one
 * legacy number: how responsive the relief is to tilt, before Motion became a
 * property of a photograph rather than of the device. Nothing writes it any more.
 * It is read once, by {@link LibraryStore#load}, so a v2 blob's photos can adopt
 * the Motion their owner had chosen instead of silently resetting to the default
 * on upgrade.
 *
 * <p>What replaced the old work:
 * <ul>
 *   <li>The legacy-key migration is in {@link AppState#load}, because the files
 *       a pre-versioned install recorded have to be adopted into the index
 *       <i>before</i> anything rewrites the config that also records them. Doing
 *       it here would mean the two migrations had an order dependency nobody
 *       could see from either file.</li>
 *   <li>Dropping rows whose file is missing is {@code Library.retainExisting},
 *       which runs over the whole index rather than the one selected pair, and
 *       can be tested without a device.</li>
 * </ul>
 */
final class ConfigStore {

    private ConfigStore() {
    }

    static Config load(SharedPreferences prefs) {
        return Config.parse(prefs.getString(Config.KEY, null));
    }

    static void save(SharedPreferences prefs, Config c) {
        prefs.edit().putString(Config.KEY, c.toJson()).apply();
    }

}
