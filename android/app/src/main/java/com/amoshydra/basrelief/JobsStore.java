package com.amoshydra.basrelief;

import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.store.Jobs;

/**
 * The pending-import record, on disk.
 *
 * <p>The same shape as {@link LibraryStore}: one blob under one key, read and written
 * whole, with the serialisation owned by {@link Jobs} in {@code :core} so the format
 * is testable without a device. The writes use {@code commit()} rather than
 * {@code apply()}, and that is deliberate: the whole point of this record is to
 * survive a process death, and an async fsync is exactly the guarantee it cannot
 * give.
 */
final class JobsStore {

    private JobsStore() {
    }

    static Jobs load(SharedPreferences prefs) {
        return Jobs.parse(prefs.getString(Jobs.KEY, null));
    }

    static void saveNow(SharedPreferences prefs, Jobs jobs) {
        prefs.edit().putString(Jobs.KEY, jobs.toJson()).commit();
    }

    static void record(SharedPreferences prefs, String itemId, String promptId, String host) {
        saveNow(prefs, load(prefs).with(new Jobs.Job(itemId, promptId, host,
                System.currentTimeMillis())));
    }

    static void clear(SharedPreferences prefs, String promptId) {
        if (promptId == null) return;
        saveNow(prefs, load(prefs).afterRemoving(promptId));
    }

    static void clearItem(SharedPreferences prefs, String itemId) {
        if (itemId == null) return;
        saveNow(prefs, load(prefs).afterRemovingItem(itemId));
    }
}
