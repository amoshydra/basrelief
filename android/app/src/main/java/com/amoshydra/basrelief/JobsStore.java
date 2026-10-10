package com.amoshydra.basrelief;

import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.store.Jobs;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import java.io.File;

/**
 * The pending-import record, on disk.
 *
 * <p>The same shape as {@link LibraryStore}: one blob under one key, read and written
 * whole, with the serialisation owned by {@link Jobs} in {@code :core} so the format
 * is testable without a device. The writes use {@code commit()} rather than
 * {@code apply()}, and that is deliberate: the whole point of this record is to
 * survive a process death, and an async fsync is exactly the guarantee it cannot
 * give.
 *
 * <p><b>Progress is not written here.</b> A running import's "frame 137 of 480"
 * comes from the streaming node's sidecar file and lives in {@code VideoImport}'s
 * in-memory live map — a copy on disk would be a second, staler answer, and the
 * record's own write paths are minutes apart (submit, fail, clear). What is on disk
 * is the fact that survives: the prompt, the host, and the server's own failure
 * message.
 */
final class JobsStore {

    private JobsStore() {
    }

    static Jobs load(SharedPreferences prefs) {
        return Jobs.parse(prefs.getString(Jobs.KEY, null));
    }

    /**
     * The colour file a row may play as a PREVIEW while its import runs, or null.
     *
     * <p>The app-layer half of the one derivation: the precedence rule — no clips of
     * its own, a record exists — is {@link Jobs#previewColourName} in {@code :core},
     * which also owns the "tracks win" rule, and this only answers the half the pure
     * function cannot: whether the file is actually here. A record whose colour is
     * missing (a run whose remux failed) is not a preview — playing a file that is
     * not there is a decoder error, not a picture.
     *
     * <p>Both hosts and the renderer's cold path call THIS, so a wallpaper and the
     * studio cannot disagree about whether a row is previewing.
     */
    static File previewColour(SharedPreferences prefs, File filesDir, LibraryItem item) {
        String name = load(prefs).previewColourName(item);
        if (name == null) return null;
        File f = PrefFiles.resolve(filesDir, name);
        return f != null && f.exists() ? f : null;
    }

    static void saveNow(SharedPreferences prefs, Jobs jobs) {
        prefs.edit().putString(Jobs.KEY, jobs.toJson()).commit();
    }

    /**
     * Write one job, replacing any earlier job for the same item.
     *
     * <p>The caller builds the whole {@link Jobs.Job}, because only the caller knows
     * the prompt the server returned; this class owns the write and nothing else.
     */
    static void record(SharedPreferences prefs, Jobs.Job job) {
        if (job == null || !job.isUsable()) return;
        saveNow(prefs, load(prefs).with(job));
    }

    /**
     * Mark the job named by {@code promptId} failed, carrying the message the user
     * must be able to read.
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
     * Clear the job named by {@code promptId}.
     *
     * <p>Scoped to the prompt rather than the item, so a superseded run finishing
     * cannot clear the record of the submission that replaced it.
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
