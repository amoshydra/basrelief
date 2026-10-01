package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import android.content.SharedPreferences;

import java.io.File;
import java.util.function.Predicate;

/**
 * The app's settings and gallery, read together and in the right order.
 *
 * <p>These used to be read independently, and that is what made the migration
 * fragile. Two builds can record a photo in two places — the loose
 * {@code image_path} key a pre-versioned install used, and the {@code image_path}
 * field inside the v1 config blob — and whoever read first would write a fresh
 * config, destroying the other source before it had been read. So both are
 * adopted here, in one place, before either blob is rewritten.
 *
 * <p>What a surface actually needs is {@link #activeItem()}, and the name says
 * why there is exactly one: the index owns the selection, so there is one
 * pointer and it cannot dangle. See {@link Config} for why Motion is all that is
 * left in it.
 */
final class AppState {

    /**
     * The one SharedPreferences file both surfaces read.
     *
     * <p>It used to be spelled independently in three places, which is the shape
     * of bug where the studio writes one file and the wallpaper watches another.
     * One owner, here, because the studio and the wallpaper must not be able to
     * disagree about which file they are talking about.
     */
    static final String PREFS = "depthwall";

    final Config config;
    final Library library;

    private AppState(Config config, Library library) {
        this.config = config;
        this.library = library;
    }

    /**
     * What the renderer should draw, or null for the bundled marble bust.
     *
     * <p>Null is a normal, renderable answer and not an error: the bundled item
     * has no file, so a caller handles it by reading {@code res/raw} rather than
     * by treating it as a missing photo. It is also where a delete of the active
     * item lands, and where an install that has never chosen anything starts.
     */
    LibraryItem activeItem() {
        return library.activeItem();
    }

    /**
     * The selected item's id, or null for the bundled demo.
     *
     * <p>Exposed because a caller about to <i>write</i> a per-item setting has to
     * know whether there is an item to write it to, and null is the honest answer
     * for the demo: it has settings of its own, but they are not editable.
     */
    String activeId() {
        LibraryItem active = activeItem();
        return active == null ? null : active.id;
    }


    /** True when the wallpaper is the bundled demo. */
    boolean isBundled() {
        LibraryItem active = activeItem();
        return active == null || active.isBundled();
    }

    // ------------------------------------------------------------------ load

    /**
     * Read both blobs, migrating and self-healing as needed.
     *
     * <p>Safe to call on every surface creation and on every pref change: it
     * writes only when it has something to change, and every step is idempotent.
     * That matters more than it looks, because the wallpaper calls this from the
     * GL thread at cold start, where a throw means no wallpaper at all.
     */
    static AppState load(SharedPreferences prefs, File filesDir) {
        // Read the raw config BEFORE anything can rewrite it. This is the only
        // point at which the v1 pair can still be seen.
        String configBlob = prefs.getString(Config.KEY, null);
        String libraryBlob = prefs.getString(Library.KEY, null);

        // A blob from a NEWER build is left completely alone.
        //
        // Library.parse returns an empty library for one, because a schema it does
        // not understand must not be guessed at. That is right for parsing and it was
        // catastrophic in combination with everything after it: the empty result
        // differed from the bundled-and-reconciled one, so libraryDirty went true,
        // and the empty index was written back over the user's photos.
        //
        // So installing an OLDER build over a newer one wiped the gallery. Measured,
        // not theorised: it happened, on a device, and the only survivor was the one
        // file the old build's own config still referenced.
        //
        // The fix is to refuse to write anything when the stored blob is not ours --
        // not to guess, and not to "reconcile" a schema we cannot read. An old build
        // must be harmless to a newer install's data, which is the whole point of
        // storing a version number.
        boolean storedIsNewer = Library.isNewerVersion(libraryBlob);
        if (storedIsNewer) {
            return new AppState(Config.parse(configBlob), Library.empty());
        }

        Config config = Config.parse(configBlob);
        Library library = Library.parse(libraryBlob, config.motion);
        boolean libraryDirty = false;

        // 1. Adopt whatever a pre-gallery build was showing, but only when there
        //    is no index at all. An existing index is authoritative, and
        //    re-adopting on every load is how a file the user deleted by hand
        //    comes back.
        if (libraryBlob == null) {
            String[] legacy = legacyPair(configBlob, prefs, filesDir);
            if (legacy != null) {
                library = Library.adoptLegacy(library, legacy, addedAtOf(filesDir, legacy[0]));
                libraryDirty = true;
            }
        }

        // 2. Drop rows whose photo is not on this device, and make sure the
        //    bundled item is there. Both self-heal, so a restored backup cannot
        //    leave a gallery of photos that render as nothing.
        Library cleaned = library
                .retainExisting(existsIn(filesDir))
                .withBundled();
        if (!cleaned.toJson().equals(library.toJson())) {
            library = cleaned;
            libraryDirty = true;
        }

        // A migrated blob must be WRITTEN, not just parsed. The v2 -> v3 migration
        // resolves correctly in memory and re-runs on every load without this, so
        // the on-disk shape never converges: every surface creation and every pref
        // change re-walks every item, including on the GL thread at wallpaper cold
        // start. Worse, it leaves the next migration reading a v2 blob while its
        // author reasonably expects v3, which is a trap rather than a cost.
        //
        // Found on a device, not by a test: the tests assert on the parsed model,
        // and the parsed model was always right.
        if (libraryDirty || Library.needsMigration(libraryBlob)) {
            LibraryStore.save(prefs, library);
        }

        // The config is rewritten only to drop the dead v1 fields. Its Motion is
        // already correct either way, so this is housekeeping rather than a
        // correctness fix — but leaving a v1 blob in place would mean Config.parse
        // had to keep tolerating fields nothing reads, and the next reader would
        // have to wonder which of them still mean something.
        if (Config.needsMigration(configBlob)) {
            ConfigStore.save(prefs, config);
        }

        return new AppState(config, library);
    }

    /**
     * When the photo was added, which is the file's own mtime if it has one.
     *
     * <p>Not "now", and not zero either. Zero ties with the bundled item, and
     * every time-based sort then falls back to its tie-break -- which put the
     * marble bust above the user's only photo under "newest first", reading as
     * though the demo were the current selection. The mtime is also the honest
     * answer: this file was written when the photo was imported, whatever the
     * clock said since.
     *
     * <p>Falls back to now for a file that cannot be stat'ed, which is better
     * than a tie.
     */
    private static long addedAtOf(File filesDir, String imageName) {
        File f = PrefFiles.resolve(filesDir, imageName);
        if (f != null && f.lastModified() > 0L) return f.lastModified();
        return System.currentTimeMillis();
    }

    private static Predicate<String> existsIn(final File filesDir) {
        return new Predicate<String>() {
            @Override
            public boolean test(String name) {
                return name != null && PrefFiles.resolve(filesDir, name) != null;
            }
        };
    }

    /** Whether a blob declares the version this build writes, not merely a number. */

    /**
     * The pair a pre-gallery build recorded, from either of the two places it
     * could have recorded it.
     *
     * <p>Both are consulted, blob first, and the first that yields something
     * wins. An install holding both is mid-migration, and the v1 blob is the
     * newer of the two — it is what the last build was actually rendering, so it
     * is the one that is right.
     *
     * <p>Only the paths are carried over. An intensity or a zoom in the old
     * global is a per-photograph default now, and the adopted item takes the
     * {@link LibraryItem} defaults rather than whatever the old global happened
     * to be, so two photos cannot disagree about what "the default" means.
     */
    private static String[] legacyPair(String configBlob, SharedPreferences prefs, File filesDir) {
        String[] fromBlob = Config.legacyFiles(configBlob);
        if (fromBlob != null) return fromBlob;
        if (prefs.getString("image_path", null) == null) return null;
        String image = PrefFiles.store(PrefFiles.resolve(filesDir, prefs.getString("image_path", null)));
        if (image == null) return null;
        String depth = PrefFiles.store(PrefFiles.resolve(filesDir, prefs.getString("depth_path", null)));
        return new String[]{image, depth};
    }

    // ---------------------------------------------------------------- update

    /**
     * Change one item's settings, or the selection, or Motion, in one write.
     *
     * <p>Read-modify-write with a synchronous commit. There is only one process,
     * but a slider on the UI thread and the wallpaper's pref listener on the GL
     * thread are two writers to the same blobs, and a plain {@code apply()} would
     * let the second write race the first — which is how a slider drag ends up
     * persisted as the value it happened to pass over rather than where it was
     * released.
     */
    static AppState update(SharedPreferences prefs, File filesDir, Editor editor) {
        AppState next = editor.edit(load(prefs, filesDir));
        LibraryStore.save(prefs, next.library);
        ConfigStore.save(prefs, next.config);
        return next;
    }

    interface Editor {
        AppState edit(AppState s);
    }

    static AppState of(Config config, Library library) {
        return new AppState(config, library);
    }
}
