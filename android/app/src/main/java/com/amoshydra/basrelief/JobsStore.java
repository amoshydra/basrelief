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

    /**
     * Write one job, replacing any earlier job for the same item.
     *
     * <p>The caller builds the whole {@link Jobs.Job}, chunks and all, because
     * only the caller knows the range it planned; this class owns the write and
     * nothing else.
     */
    static void record(SharedPreferences prefs, Jobs.Job job) {
        if (job == null || !job.isUsable()) return;
        saveNow(prefs, load(prefs).with(job));
    }

    /**
     * Record how many of a job's chunks the server has finished.
     *
     * <p>A commit, on the worker, once per chunk: minutes apart, and the count is
     * the one number the sheet's job line states — a value that survives the sheet
     * being rebuilt is the point of it being on disk at all.
     */
    static void progress(SharedPreferences prefs, String itemId, int completed) {
        if (itemId == null || itemId.isEmpty()) return;
        saveNow(prefs, load(prefs).withProgress(itemId, completed));
    }

    /**
     * Mark the job one of whose chunks names {@code promptId} failed, carrying the
     * message the user must be able to read.
     *
     * <p>KEEPS the record, unlike {@link #clear}: a run the server refused is a
     * state the sheet line and the tile's corner show until the user acts, and a
     * failure that exists only in a toast is gone before it is read.
     */
    static void fail(SharedPreferences prefs, String promptId, String message) {
        if (promptId == null) return;
        saveNow(prefs, load(prefs).withError(promptId, message));
    }

    /**
     * Clear the job one of whose chunks names {@code promptId}.
     *
     * <p>One prompt id is enough for the whole job — {@link Jobs#afterRemoving}
     * removes by any chunk — which is what lets the tail clear an N-chunk import
     * with the one prompt id it happens to hold.
     */
    static void clear(SharedPreferences prefs, String promptId) {
        if (promptId == null) return;
        saveNow(prefs, load(prefs).afterRemoving(promptId));
    }

    static void clearItem(SharedPreferences prefs, String itemId) {
        if (itemId == null) return;
        saveNow(prefs, load(prefs).afterRemovingItem(itemId));
    }
}
