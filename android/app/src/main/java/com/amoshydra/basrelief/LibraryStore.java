package com.amoshydra.basrelief;

import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.SortOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The gallery index, stored as one JSON blob.
 *
 * <p>Deliberately thin. It owns the key and the write; every decision about what
 * the index should contain lives in {@link Library} in {@code :core}, where it
 * can be tested without an Android device, and the read-and-migrate sequence
 * lives in {@link AppState} because it has to be ordered against the config's
 * own migration.
 *
 * <p>So a caller that wants to change the index does a read-modify-write through
 * {@link AppState#update} rather than reaching for {@link #save} directly, and
 * this class is not the place to add a rule.
 */
final class LibraryStore {

    private LibraryStore() {
    }

    static Library load(SharedPreferences prefs) {
        return Library.parse(prefs.getString(Library.KEY, null));
    }

    static void save(SharedPreferences prefs, Library library) {
        prefs.edit().putString(Library.KEY, library.toJson()).apply();
    }

    /**
     * Synchronous, because the index and the config are written together and an
     * {@code apply()} on either could be reordered against the other.
     */
    static void saveNow(SharedPreferences prefs, Library library) {
        prefs.edit().putString(Library.KEY, library.toJson()).commit();
    }

    // ------------------------------------------------------------ operations

    /** Select an item, or pass null for the bundled demo. */
    static Library select(SharedPreferences prefs, String id) {
        Library next = load(prefs).withSelection(id).withBundled();
        if (id != null) next = next.markUsed(id, System.currentTimeMillis());
        saveNow(prefs, next);
        return next;
    }

    /**
     * Add a photo, and select it.
     *
     * <p>Selecting on add is the point: the user picked a photo in order to see
     * it, and the studio is the live preview, so not selecting it would show them
     * the previous photo while the status line claimed otherwise.
     *
     * @param label the name the user recognises, normally the picked file's own
     *              display name. It has to be passed in rather than derived: the
     *              file on disk is named from the item's id, because two items
     *              must never share one, which means the filename is a UUID and
     *              useless as something to show in a list. Null falls back to that
     *              filename, which is a last resort and not a label.
     */
    static LibraryItem add(SharedPreferences prefs, String imageName, String depthName,
                           String label) {
        LibraryItem item = LibraryItem.of(LibraryItem.newId(), imageName, depthName,
                System.currentTimeMillis()).withLabel(label);
        Library next = load(prefs).withItem(item).withSelection(item.id).withBundled();
        saveNow(prefs, next);
        return item;
    }

    /**
     * Unlink a photo.
     *
     * <p>The index is the only place the selection is recorded, so clearing it is
     * part of removing the row and cannot be forgotten: there is no second copy
     * to leave behind. A deleted active item lands on the bundled demo rather
     * than on an arbitrary neighbour, because quietly showing a photo the user
     * did not pick is worse than showing the one they can recognise as the
     * default.
     *
     * <p>Deliberately does not touch the files. The caller removes them only
     * after this has been persisted, so a crash in between leaves orphan files,
     * which are invisible, rather than an index that points at files that are
     * gone.
     */
    static Library remove(SharedPreferences prefs, String id) {
        Library next = load(prefs).remove(id).withBundled();
        saveNow(prefs, next);
        return next;
    }

    /**
     * Drop rows that share an image file with another row, and report what went.
     *
     * <p>Repairs the index in place when an older build's share path wrote every
     * import to one fixed filename. Each drop goes through {@link #remove}, so the
     * selection pointer is handled by the same code that handles a user deletion
     * rather than by a second rule that has to agree with it.
     *
     * <p><b>Files are deliberately not deleted.</b> The rows are the defect, and
     * the depth maps those rows referenced are real work the user may still want.
     * Reclaiming them is a separate, explicit decision rather than a side effect
     * of opening the app.
     *
     * <p>Cheap enough to run on every launch: it groups in memory and never
     * touches the disk, so there is no flag to get wrong and no migration to
     * re-run after a crash.
     *
     * @return the labels of the dropped rows, for a message the user can read
     */
    static List<String> repairDuplicateImages(SharedPreferences prefs) {
        Library lib = load(prefs);
        List<String> doomed = lib.itemsSharingAnImageName();
        if (doomed.isEmpty()) return Collections.emptyList();
        List<String> labels = new ArrayList<String>();
        for (String id : doomed) {
            LibraryItem item = lib.find(id);
            labels.add(item == null ? id : item.displayName());
            lib = lib.remove(id);
        }
        // One write for the whole repair, and withBundled so the bundled row
        // cannot be the thing that went missing.
        saveNow(prefs, lib.withBundled());
        return labels;
    }

    /** Apply a per-item edit to the item with this id, leaving the rest alone. */
    static Library editItem(SharedPreferences prefs, String id, Editor editor) {
        Library lib = load(prefs);
        LibraryItem item = lib.find(id);
        if (item == null) return lib;
        Library next = lib.withItem(editor.edit(item));
        saveNow(prefs, next);
        return next;
    }

    static Library setSort(SharedPreferences prefs, SortOrder order) {
        Library next = load(prefs).withSort(order);
        saveNow(prefs, next);
        return next;
    }

    /** The rows to show, in the stored order. Never null. */
    static List<LibraryItem> rows(SharedPreferences prefs) {
        return load(prefs).sorted();
    }

    interface Editor {
        LibraryItem edit(LibraryItem item);
    }
}
