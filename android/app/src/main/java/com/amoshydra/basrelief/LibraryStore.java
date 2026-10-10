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

    /**
     * The index, migrating a pre-v3 blob.
     *
     * <p>{@code legacyMotion} is Motion's old home: one global number in
     * {@link Config}. It is passed in rather than read here because
     * {@link LibraryStore} does not own the config blob, and because reading it
     * inside {@link #load} would mean parsing the config on every call — including
     * the ones on the GL thread at cold start. Nothing writes Motion to the config
     * any more; this value only ever moves a user's photos onto the per-item one.
     */
    static synchronized Library load(SharedPreferences prefs, int legacyMotion) {
        return Library.parse(prefs.getString(Library.KEY, null), legacyMotion);
    }

    /**
     * The index, for a caller with no {@code Config} in hand.
     *
     * <p>Reads the config to get the legacy Motion, which costs one extra parse.
     * That is why {@link AppState} uses the two-argument form instead: it is on the
     * GL thread at cold start, where a wallpaper depends on this returning fast.
     */
    static synchronized Library load(SharedPreferences prefs) {
        return load(prefs, ConfigStore.load(prefs).motion);
    }

    /**
     * One writer thread for the index, so the UI never serialises or fsyncs it.
     *
     * <p>Measured on a 32-item gallery: {@code toJson()} is ~81 ms and a
     * {@code commit()} 33-430 ms, and every gallery tap, archive toggle and slider
     * release paid both ON THE UI THREAD -- the "releasing a control freezes the UI"
     * report, and the reason the studio's own tap felt late. The write is now
     * queued: the in-memory library is published to {@link AppState} IMMEDIATELY
     * (so every reader, including the wallpaper, sees the new selection at once),
     * and the serialise-and-write follows on this thread. Ordering is FIFO because
     * there is exactly one thread, which is also what keeps two quick taps from
     * landing out of order.
     *
     * <p>The durability trade is stated rather than implied: a process death inside
     * the queue's window loses the write, exactly as {@code apply()} already accepts
     * for every per-item edit. The writes that MUST land before the user can act
     * again -- none, currently -- would need to ask for a synchronous flush.
     */
    private static final java.util.concurrent.ExecutorService INDEX_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "index-writer");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    private static void queueWrite(final SharedPreferences prefs, final Library library,
                                   final boolean durable) {
        AppState.publishedLibrary(prefs, library);
        INDEX_WRITER.execute(new Runnable() {
            @Override
            public void run() {
                String blob = library.toJson();
                SharedPreferences.Editor e = prefs.edit().putString(Library.KEY, blob);
                if (durable) {
                    e.commit();
                } else {
                    e.apply();
                }
                AppState.libraryStored(library, blob);
            }
        });
    }

    static synchronized void save(SharedPreferences prefs, Library library) {
        queueWrite(prefs, library, false);
    }

    /**
     * Synchronous, because the index and the config are written together and an
     * {@code apply()} on either could be reordered against the other.
     */
    static synchronized void saveNow(SharedPreferences prefs, Library library) {
        queueWrite(prefs, library, true);
    }

    // ------------------------------------------------------------ operations

    /**
     * The index as every mutating path sees it: the stored blob, with the
     * bundled demo seeded only when nothing has ever been stored.
     *
     * <p>This is the "genuinely fresh install" test, and it is about the stored
     * blob rather than about the parsed library. An index that parses to zero
     * items is a gallery the user emptied; seeding it again would resurrect the
     * bundled row on every later write, so deleting it as the last row could
     * never stick. A missing blob means no index has ever been written -- a
     * fresh install, or the state a reset leaves behind -- and that is the one
     * state that gets the preload.
     *
     * <p><b>The demo is seeded here with no filename, and that is repaired.</b>
     * This class has no {@code Context} and so cannot copy {@code res/raw} into
     * {@code filesDir}; the read path can, and it does so on the next
     * {@link AppState#load} in the same process, which every write here is
     * reached from. A demo row with no filename renders the packaged pair in the
     * meantime, and the row is never lost — so the repair needs no flag, no
     * ordering guarantee and no second writer.
     */
    private static synchronized Library loadForWrite(SharedPreferences prefs) {
        Library lib = load(prefs);
        String stored = prefs.getString(Library.KEY, null);
        return stored == null || stored.trim().isEmpty() ? lib.withBundled(null, null) : lib;
    }

    /** Select an item, or pass null to select nothing. */
    static synchronized Library select(SharedPreferences prefs, String id) {
        Library next = loadForWrite(prefs).withSelection(id);
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
    static synchronized LibraryItem add(SharedPreferences prefs, String imageName, String depthName,
                           String label) {
        LibraryItem item = LibraryItem.of(LibraryItem.newId(), imageName, depthName,
                System.currentTimeMillis()).withLabel(label);
        Library next = loadForWrite(prefs).withItem(item).withSelection(item.id);
        saveNow(prefs, next);
        return item;
    }

    /**
     * Unlink a photo.
     *
     * <p>The index is the only place the selection is recorded, so clearing it is
     * part of removing the row and cannot be forgotten: there is no second copy
     * to leave behind. A deleted active item lands on the packaged demo pair
     * rather than on an arbitrary neighbour, because quietly showing a photo the
     * user did not pick is worse than showing the one they can recognise as the
     * default.
     *
     * <p>Deliberately does not touch the files. The caller removes them only
     * after this has been persisted, so a crash in between leaves orphan files,
     * which are invisible, rather than an index that points at files that are
     * gone.
     */
    static synchronized Library remove(SharedPreferences prefs, String id) {
        Library next = loadForWrite(prefs).remove(id);
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
    static synchronized List<String> repairDuplicateImages(SharedPreferences prefs) {
        Library lib = loadForWrite(prefs);
        List<String> doomed = lib.itemsSharingAnImageName();
        if (doomed.isEmpty()) return Collections.emptyList();
        List<String> labels = new ArrayList<String>();
        for (String id : doomed) {
            LibraryItem item = lib.find(id);
            labels.add(item == null ? id : item.displayName());
            lib = lib.remove(id);
        }
        // One write for the whole repair. The read went through loadForWrite, so
        // a fresh install keeps its preload and an emptied gallery stays empty;
        // the repair itself never seeds.
        saveNow(prefs, lib);
        return labels;
    }

    /** Apply a per-item edit to the item with this id, leaving the rest alone. */
    static synchronized Library editItem(SharedPreferences prefs, String id, Editor editor) {
        Library lib = load(prefs);
        LibraryItem item = lib.find(id);
        if (item == null) return lib;
        Library next = lib.withItem(editor.edit(item));
        // apply(), not commit(). A per-item edit is a control release, and commit()
        // was measured at 33-430ms of BLOCKED UI THREAD depending on the write queue
        // -- reported as "releasing a control freezes the UI". The serialisation and
        // the in-memory update still happen here (milliseconds); only the fsync moves
        // to the framework's background writer. The writes that keep commit() are the
        // ones whose loss would STRAND the user -- add, remove, select -- because a
        // resurrected deleted photo or a selection pointing at nothing is worse than
        // a nudged slider lost to a crash.
        save(prefs, next);
        return next;
    }

    static synchronized Library setSort(SharedPreferences prefs, SortOrder order) {
        Library next = loadForWrite(prefs).withSort(order);
        saveNow(prefs, next);
        return next;
    }

    /** The live rows to show, in the stored order. Never null. */
    static synchronized List<LibraryItem> rows(SharedPreferences prefs) {
        return load(prefs).sortedLive();
    }

    /** The archived rows, for the disclosure's section. Never null. */
    static synchronized List<LibraryItem> archivedRows(SharedPreferences prefs) {
        return load(prefs).sortedArchived();
    }

    /**
     * Archive or restore one photo. One blob, unlike delete's two.
     *
     * <p>Archive never touches the playlists: a playlist keeps an archived member
     * and skips it (see {@code Playlists.livingIds}), because restoring the photo
     * must bring back the place the user arranged. Delete is the other half — it
     * removes the id everywhere in the same derivation — and this is the
     * deliberate contrast, so this write is the index and nothing else.
     */
    static synchronized Library setArchived(SharedPreferences prefs, String id, boolean archived) {
        Library next = loadForWrite(prefs).withArchived(id, archived);
        saveNow(prefs, next);
        return next;
    }

    /** Archive or restore a whole set in one write. See {@link Library#withArchived(java.util.Set, boolean)}. */
    static synchronized Library setArchived(SharedPreferences prefs, java.util.Set<String> ids, boolean archived) {
        Library next = loadForWrite(prefs).withArchived(ids, archived);
        saveNow(prefs, next);
        return next;
    }

    interface Editor {
        LibraryItem edit(LibraryItem item);
    }
}
