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

    /**
     * Whether {@code candidate}'s file holds exactly the same bytes.
     *
     * <p>Used to notice that a newly imported image is one already in the gallery.
     * A share sheet re-sends the same photo whenever it is used again, and each
     * send used to become another gallery row pointing at one file, so the gallery
     * filled with identical thumbnails that the user could not tell apart.
     *
     * <p>Size is checked first and is not a heuristic but a short circuit: the
     * downscaled images this compares are megabytes, so the length test throws out
     * almost every candidate without reading a byte, and the hash only runs on the
     * handful that match. Equality is then decided on content, because two
     * different photos of the same subject can share a byte count and must not be
     * collapsed into one row.
     *
     * <p>Absent or unreadable files are never equal, so a missing image cannot be
     * mistaken for a match and silently swallow a real import.
     *
     * @return true only if {@code candidate} exists and its contents are identical
     */
    public static boolean sameContent(byte[] bytes, File candidate) {
        if (bytes == null || candidate == null || !candidate.isFile()) return false;
        if (candidate.length() != bytes.length) return false;
        return java.util.Arrays.equals(digest(bytes), digest(candidate));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform, so this is unreachable.
            // Falling back to a length-only compare would silently weaken the check
            // this exists to make, so it is better to fail loudly than quietly.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] digest(File f) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return md.digest();
        } catch (java.io.IOException e) {
            return null;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
