package com.amoshydra.basrelief.core.store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The gallery index: N image+depth items, which one is the wallpaper, and the
 * order they are listed in.
 *
 * <p><b>Why this exists.</b> Today the app holds exactly one pair, in two prefs
 * keys, written to one fixed filename. SharedPreferences is the transport
 * between the studio and the wallpaper; it is not a data store, and it has no
 * concept of a library. This is that concept, and it is deliberately the same
 * shape as {@link Config} — one versioned JSON blob under one key — for the same
 * two reasons: one key means one atomic write, so a reader can never observe
 * half-updated state, and a new field is a new key an older build ignores rather
 * than one it misreads.
 *
 * <p><b>The wallpaper selection is a pointer to an id, not a field on the item.</b>
 * It has to be a pointer because removing an item must be able to say "that was
 * the wallpaper, so clear the selection", and an item that is gone cannot carry
 * the fact. It cannot be a filename, because a filename is a storage detail that
 * a re-import changes. And it is validated on every read: a restored backup
 * whose pointer names an item that is not in the list comes back with no
 * selection rather than a wallpaper pointing at nothing.
 *
 * <p><b>The two blobs have one direction of truth.</b> This index owns the
 * gallery; {@link Config} is the transport the wallpaper actually renders, and
 * holds the pair for the selected item. So:
 *
 * <ul>
 *   <li>{@link #applyTo} pushes the selection down into the config. A gallery
 *       change writes the index, then derives the config from it.
 *   <li>{@link #reconcile} pulls: it adopts a file the config knows and the
 *       index does not, and re-points the selection at whatever the config is
 *       really rendering. It is the migration and the self-heal, and it is
 *       idempotent, so calling it on every load is free.
 * </ul>
 *
 * <p>Two surfaces share this in one process, so a read-modify-write must not
 * span anything but this class. The rule is the one {@code ConfigStore} already
 * follows: take the snapshot, build the new one, persist it in a single
 * {@code commit()}, and never cache a {@code Library} across a mutation. A
 * half-applied edit here is the one that would show the user a gallery that
 * disagrees with their wallpaper.
 *
 * <p><b>Never throws.</b> {@link #parse} keeps whatever it can read and drops
 * only what it cannot, because the alternative — a corrupt index returning
 * nothing — is a user's whole gallery gone, silently, on the first launch after
 * whatever wrote it.
 */
public final class Library {

    /** Bump when the meaning of a stored field changes, and add a migration. */
    /**
     * v2: per-item settings. v1 items had none, so they load with the defaults
     * in {@link LibraryItem} -- which is why the migration below is a no-op.
     */
    public static final int VERSION = 2;

    /**
     * The id of the bundled marble bust, which every install has.
     *
     * <p>It has no files in {@code filesDir}: {@code imageName} and
     * {@code depthName} are both null, which is exactly the signal the renderer
     * uses to fall back to {@code res/raw}. It is the "back to the default"
     * affordance, and it replaces the studio's old Reset button.
     *
     * <p>It cannot be deleted. A reserved id is used rather than a flag on the
     * item so that the rule survives a round trip through JSON, and so that a
     * cleanup pass cannot quietly drop the one item that cannot be recreated
     * from files on disk.
     */
    public static final String BUNDLED_ID = "bundled";

    /** The single SharedPreferences key the blob lives under. */
    public static final String KEY = "library";

    /** Insertion order, never reordered. See {@link #sorted()}. */
    private final List<LibraryItem> items;

    /** Id of the item the wallpaper is showing, or null for the bundled demo. */
    private final String activeId;

    private final SortOrder sort;

    private Library(List<LibraryItem> items, String activeId, SortOrder sort) {
        this.items = Collections.unmodifiableList(items);
        this.activeId = activeId;
        this.sort = sort == null ? SortOrder.DEFAULT : sort;
    }

    /** An empty gallery: nothing listed, nothing selected, default order. */
    public static Library empty() {
        return new Library(new ArrayList<LibraryItem>(), null, SortOrder.DEFAULT);
    }

    /**
     * The one place the invariants are enforced, so no code path can build an
     * index that breaks them: ids are unique (first writer wins, which is the
     * one that was written first) and the selection points at an item that is
     * actually in the list.
     */
    private static Library of(List<LibraryItem> raw, String activeId, SortOrder sort) {
        List<LibraryItem> unique = new ArrayList<LibraryItem>();
        for (LibraryItem item : raw) {
            if (item == null) continue;
            boolean seen = false;
            for (LibraryItem kept : unique) {
                if (kept.id.equals(item.id)) { seen = true; break; }
            }
            if (!seen) unique.add(item);
        }
        String active = null;
        if (activeId != null) {
            for (LibraryItem item : unique) {
                if (item.id.equals(activeId)) { active = activeId; break; }
            }
        }
        return new Library(unique, active, sort);
    }

    // ------------------------------------------------------------- reading

    /** The items in insertion order. Unmodifiable; use {@link #sorted()}. */
    public List<LibraryItem> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** @return the item, or null if there is no such id */
    public LibraryItem find(String id) {
        if (id == null) return null;
        for (LibraryItem item : items) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    public boolean contains(String id) {
        return find(id) != null;
    }

    /** @return the item holding this image filename, or null */
    public LibraryItem findByImage(String imageName) {
        String name = LibraryItem.normaliseName(imageName);
        if (name == null) return null;
        for (LibraryItem item : items) {
            if (item.imageName.equals(name)) return item;
        }
        return null;
    }

    /** The selected item's id, or null when the wallpaper is the bundled demo. */
    public String activeId() {
        return activeId;
    }

    /** The selected item, or null. */
    public LibraryItem activeItem() {
        return find(activeId);
    }

    /** Whether a given id is the one the wallpaper is showing. */
    public boolean isActive(String id) {
        return activeId != null && activeId.equals(id);
    }

    public SortOrder sort() {
        return sort;
    }

    // ------------------------------------------------------------ mutating

    /**
     * Add an item, or replace the one with the same id.
     *
     * <p>A replacement keeps its position, so editing a label does not move a
     * row out from under the user's thumb.
     */
    public Library withItem(LibraryItem item) {
        if (item == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>(items);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(item.id)) {
                next.set(i, item);
                return of(next, activeId, sort);
            }
        }
        next.add(item);
        return of(next, activeId, sort);
    }

    /**
     * Select the wallpaper, or pass null for none.
     *
     * <p>An id that is not in the list is treated as null: a selection that
     * points at nothing is exactly the dangling reference this class exists to
     * prevent, so the call is silently corrected rather than stored.
     */
    public Library withSelection(String id) {
        return of(items, id, sort);
    }

    /**
     * Change the list order.
     *
     * <p>Read order only, so this writes no file and reorders nothing: the
     * stored list stays in insertion order and the order itself lives in the
     * blob, so it survives a restart.
     */
    public Library withSort(SortOrder order) {
        return of(items, activeId, order);
    }

    /** Record that the wallpaper is showing this item. */
    public Library markUsed(String id, long when) {
        LibraryItem item = find(id);
        return item == null ? this : withItem(item.withLastUsedAt(when));
    }

    /**
     * Unlink an item. The pointer to it is cleared if it held one.
     *
     * <p><b>Deleting the active item is allowed.</b> Refusing it would mean the
     * one photo a user is most likely to want gone — the one filling their
     * screen — is the one they cannot remove, with no explanation. Clearing the
     * selection instead has a visible, honest outcome: the bundled demo, which
     * the status line already names as such. A gallery that cannot delete is not
     * a gallery.
     *
     * <p><b>This touches the index only, never the files.</b> Delete the files
     * <i>after</i> persisting the result, so a crash in between leaves orphan
     * files — recoverable, and invisible to the user — rather than an index that
     * references files which are gone. Use {@link #removeFrom} to get the config
     * change that must land in the same write.
     */
    public Library remove(String id) {
        // The bundled item is the "back to the default" affordance and has no
        // files to recreate it from, so it is not removable. Refused here rather
        // than only in the UI, so no caller can bypass it.
        if (BUNDLED_ID.equals(id)) return this;
        LibraryItem gone = find(id);
        if (gone == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>();
        for (LibraryItem item : items) {
            if (!item.id.equals(id)) next.add(item);
        }
        return of(next, isActive(id) ? null : activeId, sort);
    }

    /**
     * An index and the config that go with it after removing an item.
     *
     * <p>Two things have to change together, and doing them as two calls means one
     * of them gets forgotten. If the config still named the removed item's files,
     * the wallpaper would keep rendering a photo the user deleted — and
     * {@link #reconcile} would then adopt those files straight back into the
     * index, so the delete would not just fail, it would fail <i>loudly and
     * permanently</i>. Clearing the config's files is what stops that.
     */
    public static final class Removal {
        public final Library library;
        public final Config config;

        Removal(Library library, Config config) {
            this.library = library;
            this.config = config;
        }
    }

    /**
     * Remove an item and hand back the config to write with it.
     *
     * <p>The config is cleared whenever it named the removed item, whether or not
     * the selection pointer agreed — the two can disagree after a partial
     * migration, and the config is what actually renders.
     *
     * @param config the current config, or null if there is none
     * @return the pair to persist; {@code library} is unchanged and
     *         {@code config} untouched if the id was not in the index
     */
    public Removal removeFrom(Config config, String id) {
        LibraryItem gone = find(id);
        Library next = remove(id);
        if (gone == null) return new Removal(next, config);
        return new Removal(next, namesTheSameFiles(config, gone) ? withFiles(config, null, null) : config);
    }

    private static boolean namesTheSameFiles(Config config, LibraryItem item) {
        if (config == null) return false;
        String img = LibraryItem.normaliseName(config.imagePath);
        return img != null && img.equals(item.imageName);
    }

    private static Config withFiles(Config config, String image, String depth) {
        return config == null ? null : config.withFiles(image, depth);
    }

    // ------------------------------------------------- the other direction

    /**
     * The config this index implies: the selected item's files, everything else
     * carried through untouched.
     *
     * <p>With nothing selected the config is returned as-is rather than
     * emptied. An index that has never been written must not blank a config
     * that already holds a real image, or the upgrade path would throw away the
     * wallpaper on the very first launch of the build that introduced the
     * gallery.
     *
     * <p>Only the files move. Zoom, orbit and pan are left alone, because
     * whether a new photo should inherit the framing of the old one is a
     * product decision and not this class's to make.
     */
    public Config applyTo(Config config) {
        LibraryItem active = activeItem();
        if (active == null || config == null) return config;
        return config.withFiles(active.imageName, active.depthName);
    }

    /**
     * Fold a config into the index: adopt a file the config knows and the index
     * does not, and make the selection follow what the config is really
     * rendering. This is the migration off a single pair, and the self-heal
     * after any write path that sets files without touching the index.
     *
     * <p><b>Idempotent, by construction.</b> A file is matched by name, not by
     * id, so a second call finds what the first adopted, keeps that item's id
     * and adds nothing. That is what makes it safe to call on every load: the
     * id of an already-indexed file never changes, so a pointer written by an
     * earlier call is not disturbed by a later one.
     *
     * <p>An empty config image means the wallpaper is the bundled demo, so
     * nothing is selected. Items are not removed — a photo the user added stays
     * in the gallery while the demo happens to be on screen.
     *
     * @param nowMillis stamped on an adopted item; the caller knows the file and
     *                  can pass its mtime, which is more honest than "now"
     * @param newId     id source for an adopted item, for tests
     */
    public static Library reconcile(Library current, Config config, long nowMillis,
                                    Supplier<String> newId) {
        if (current == null) current = empty();
        String image = LibraryItem.normaliseName(config == null ? null : config.imagePath);
        if (image == null) return current.withSelection(null);

        LibraryItem match = current.findByImage(image);
        if (match != null) return current.withSelection(match.id);

        String id = freshId(current, newId);
        LibraryItem adopted = LibraryItem.of(id, image,
                LibraryItem.normaliseName(config.depthPath), nowMillis);
        return current.withItem(adopted).withSelection(adopted.id);
    }

    /** {@link #reconcile} with a random id source. */
    public static Library reconcile(Library current, Config config, long nowMillis) {
        return reconcile(current, config, nowMillis, LibraryItem.NEW_ID);
    }

    /**
     * An id that is valid and not already in the index.
     *
     * <p>A source that repeats an id in use would otherwise let one item replace
     * another — the supplied id is discarded and a real one minted, because a
     * caller with a broken id source is a bug to survive, not one to obey.
     */
    private static String freshId(Library current, Supplier<String> newId) {
        if (newId != null) {
            for (int attempt = 0; attempt < 8; attempt++) {
                String candidate = newId.get();
                if (LibraryItem.isValidId(candidate) && !current.contains(candidate)) {
                    return candidate;
                }
            }
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = LibraryItem.newId();
            if (!current.contains(candidate)) return candidate;
        }
        return current.size() + "-" + System.nanoTime(); // unreachable; ids are UUIDs
    }

    /**
     * Drop every item whose image is not actually here, and clear the selection
     * if it was one of them.
     *
     * <p>The restore case. A backup carries the index and filesDir together, but
     * a user can clear app data, restore onto a device that skipped the files, or
     * lose them to a sync rule — and then the gallery would list photos that
     * render as nothing at all.
     *
     * <p>A missing <i>depth</i> file is not grounds for dropping the item: a flat
     * item is a legal item, and the user may be about to generate its depth.
     *
     * @param fileExists given a bare filename, whether it is present
     */
    public Library retainExisting(Predicate<String> fileExists) {
        if (fileExists == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>();
        for (LibraryItem item : items) {
            // The bundled item has no file to look for: it lives in res/raw.
            // Testing its null name would drop the one item that cannot be
            // recreated from disk, so it is kept unconditionally.
            if (item.isBundled() || Boolean.TRUE.equals(fileExists.test(item.imageName))) {
                next.add(item);
            }
        }
        return of(next, activeId, sort);
    }

    // -------------------------------------------------------------- sorting

    /** The items in the stored order. Never null. */
    public List<LibraryItem> sorted() {
        return sorted(sort);
    }

    /**
     * The items in the given order. Read order only: the stored list is not
     * touched, so a restart always reads the same blob and this is pure
     * presentation.
     *
     * <p>Ties fall to the most recently added item. The index has no other
     * signal to break them — two photos imported in the same millisecond have
     * identical stamps — and "the one I just added" is the better guess than
     * "the one I added first". Implemented as a reverse copy through a stable
     * sort, so the tie-break falls out of the sort rather than a second
     * comparison that could disagree with it.
     */
    public List<LibraryItem> sorted(SortOrder order) {
        SortOrder by = order == null ? sort : order;
        List<LibraryItem> out = new ArrayList<LibraryItem>(items);
        if (by == SortOrder.NAME_ASC) {
            Collections.sort(out, BY_NAME);
        } else {
            Collections.reverse(out); // ties resolve to the most recently added
            if (by == SortOrder.RECENTLY_USED) {
                Collections.sort(out, BY_LAST_USED);
            } else {
                Collections.sort(out, BY_ADDED_DESC);
            }
        }
        return out;
    }

    private static final Comparator<LibraryItem> BY_ADDED_DESC = new Comparator<LibraryItem>() {
        @Override
        public int compare(LibraryItem a, LibraryItem b) {
            return Long.compare(b.addedAt, a.addedAt);
        }
    };

    private static final Comparator<LibraryItem> BY_LAST_USED = new Comparator<LibraryItem>() {
        @Override
        public int compare(LibraryItem a, LibraryItem b) {
            int byUsed = Long.compare(b.lastUsedAt, a.lastUsedAt);
            // A never-used item (0) already sorts last; among several, fall back
            // to when it was added so the list is still predictable.
            return byUsed != 0 ? byUsed : Long.compare(b.addedAt, a.addedAt);
        }
    };

    private static final Comparator<LibraryItem> BY_NAME = new Comparator<LibraryItem>() {
        @Override
        public int compare(LibraryItem a, LibraryItem b) {
            int byLabel = String.CASE_INSENSITIVE_ORDER.compare(a.displayName(), b.displayName());
            // Case-insensitive ties ("Beach" vs "beach") still need a total order
            // or a RecyclerView re-orders rows between passes.
            return byLabel != 0 ? byLabel : a.displayName().compareTo(b.displayName());
        }
    };

    // ---------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            for (LibraryItem item : items) {
                arr.put(new JSONObject(item.toJson()));
            }
            o.put("version", VERSION);
            o.put("sort", sort.key());
            o.put("items", arr);
            // Absent rather than null: JSONObject drops nulls anyway, and a
            // missing key reads as "nothing selected" without a special case.
            if (activeId != null) o.put("active", activeId);
        } catch (JSONException e) {
            throw new AssertionError("Library.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Parse a stored index, keeping whatever can be read.
     *
     * <p>A missing, corrupt or future-versioned blob yields an empty index, which
     * is the safe direction: the gallery shows nothing and the bundled demo
     * renders, instead of the user's library being replaced by nonsense.
     *
     * <p>Individual bad items are dropped rather than failing the whole blob.
     * Losing one unreadable entry is recoverable; losing forty good ones because
     * of the forty-first is not.
     *
     * @param raw the stored string, or null if nothing has been written yet
     */
    public static Library parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return empty();
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (JSONException e) {
            return empty(); // corrupt
        }
        int version = o.optInt("version", 0);
        if (version > VERSION) {
            // A newer build wrote this. Its fields may mean something else, so
            // guessing would be worse than starting clean.
            return empty();
        }
        if (version < VERSION) {
            o = migrate(o, version);
        }

        List<LibraryItem> parsed = new ArrayList<LibraryItem>();
        String activeId = null;
        SortOrder sort = SortOrder.DEFAULT;
        try {
            sort = SortOrder.parse(o.optString("sort", null));
            activeId = o.optString("active", null);
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    LibraryItem item = LibraryItem.fromJson(arr.optJSONObject(i));
                    if (item != null) parsed.add(item);
                }
            }
        } catch (RuntimeException e) {
            // Unreachable for well-formed input, and org.json is lenient, but the
            // promise is unconditional. Keep what was read: half a gallery beats
            // none, and the selection is re-validated below regardless.
        }
        // of() drops duplicate ids and any selection that no longer resolves,
        // so a restored backup cannot hand back a dangling pointer.
        return of(parsed, activeId, sort);
    }

    /**
     * Bring an older blob up to {@link #VERSION}.
     *
     * <p>A no-op for v0, which is the first versioned schema — the pre-blob era
     * was the single {@code image_path}/{@code depth_path} pair, handled by
     * {@link #reconcile} because that pair still lives in {@link Config}. It
     * exists so the next change has an obvious place to go.
     */
    /**
     * v0 -> v1: nothing, there was no earlier schema.
     *
     * <p>v1 -> v2: also nothing, deliberately. v2 moved the settings onto each
     * item, and {@link LibraryItem} reads them with optInt/optDouble plus a
     * default, so a v1 blob parses into v2 items that carry the defaults. No
     * field has to be rewritten, which is what makes migration idempotent for
     * free: parsing the same v1 blob twice gives the same result.
     *
     * <p>Written as a real hook rather than inlined so the next schema change
     * has an obvious place to go.
     */
    private static JSONObject migrate(JSONObject o, int fromVersion) {
        return o;
    }

    @Override
    public String toString() {
        return "Library{" + items.size() + " items, active=" + activeId + ", sort=" + sort.key() + "}";
    }
}
