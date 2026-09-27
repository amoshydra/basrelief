package com.amoshydra.basrelief.core.store;

import java.io.File;

/**
 * How image/depth filenames are stored in {@code SharedPreferences}.
 *
 * <p>They are stored as a BARE FILENAME, not an absolute path. Absolute paths
 * plus {@code android:allowBackup="true"} is a live data-loss bug: prefs and
 * {@code filesDir} are both backed up and restored together, but the restored
 * absolute path still points at the OLD device's
 * {@code /data/user/0/com.amoshydra.basrelief/files/...}, so the app comes back
 * referencing files that are not there and silently drops the user back to the
 * bundled demo, with no error.
 *
 * <p>Storing a relative name makes the pair survive a restore, because the name
 * is re-resolved against whatever {@code filesDir} is on the new device.
 *
 * <p>Deliberately free of Android imports so it can be unit-tested; the app
 * shell passes {@code context.getFilesDir()}.
 */
public final class PrefFiles {

    private PrefFiles() {
    }

    /** The form {@link #resolve} expects: a bare filename. */
    public static String store(File f) {
        return f.getName();
    }

    /**
     * Resolve a stored value to an existing file, or null if it is gone.
     *
     * <p>Accepts both the current bare-filename form and the absolute paths
     * written by older builds, so an upgrade does not drop the user's image.
     * An absolute value is used as-is when the file really is there (same
     * device, no restore); otherwise the basename is retried inside
     * {@code filesDir}, which is what makes a restored backup work.
     *
     * @return the file, or null when the stored value is empty, unresolvable, or
     *         names a file that no longer exists
     */
    public static File resolve(File filesDir, String stored) {
        if (stored == null || stored.length() == 0) return null;
        File direct = new File(stored);
        if (direct.isAbsolute() && direct.exists()) return direct;
        File inFiles = new File(filesDir, direct.getName());
        return inFiles.exists() ? inFiles : null;
    }
}
