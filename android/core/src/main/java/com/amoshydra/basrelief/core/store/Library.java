package com.amoshydra.basrelief.core.store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
     * v2: per-item settings. v3: per-surface settings and Motion on the item.
     * v4: the render mode ({@link RenderMode}) moved off the global {@code render_3d}
     * pref onto each item, exactly as Motion moved off {@link Config} in v3 — see
     * {@link #migrate}. A v1 or v2 blob is still readable; an item with no settings
     * of its own loads with the defaults in {@link LibraryItem}, which is why the
     * v0-v1 steps are a no-op.
     */
    public static final int VERSION = 4;

    /**
     * The id of the bundled marble bust, the demo every install starts with.
     *
     * <p><b>It is a file-backed row like any other.</b> The seed copies
     * {@code res/raw/image.png} and {@code res/raw/depth.png} into
     * {@code filesDir} under the names this id derives — the same
     * {@link LibraryItem#imageFileName} scheme every imported photo uses — and
     * stores them on the item. So the demo can be renamed, re-framed, given a
     * regenerated or replaced map, exported and deleted through the same code
     * paths as every other photograph, which is the whole point: the special
     * logic was never "the marble is a demo", it was "the marble has no file".
     *
     * <p>It is not an immortal row. It can be deleted and the delete sticks; what
     * brings it back is {@link #withBundled}, and the caller calls that only when
     * no index has ever been stored — a fresh install, or the state a reset
     * leaves behind. A stored index that parses to zero items is an emptied
     * gallery and stays empty.
     *
     * <p>The reserved id is still load-bearing in exactly two places, and both are
     * about the pre-migration state rather than about the demo being special: the
     * constructor and {@link LibraryItem#fromJson} tolerate a null image name, and
     * {@link #retainExisting} keeps a listed row it cannot see the files of.
     */
    public static final String BUNDLED_ID = "bundled";

    // `splatNameFor` lived here: the rule that a row's own cloud wins and the
    // Stage-0 `splat_file` debug filename was reachable from the bundled row alone.
    // Stage 1 retired both halves -- the demo's cloud ships in res/raw and the
    // ordinary seed names it on the row, so the bundled row is an ordinary 3D row
    // with its own filename and there is no fallback left to gate. The function is
    // gone rather than kept "for a migration": there is nothing to migrate, because
    // the asset IS the migration.

    /** The single SharedPreferences key the blob lives under. */
    public static final String KEY = "library";

    /** Insertion order, never reordered. See {@link #sorted()}. */
    private final List<LibraryItem> items;

    /** Id of the item the wallpaper is showing, or null when nothing is selected. */
    private final String activeId;

    /**
     * Which surfaces the selected photo has been applied to.
     *
     * <p>App-owned bookkeeping, and deliberately so. The platform is the truth about
     * what is actually set; this is the app's record of its own actions, the same
     * class of thing as {@code lastUsedAt} — which the platform also does not know
     * and cannot contradict.
     *
     * <p>It lives beside the selection rather than on each item because it only ever
     * describes the <i>selected</i> photo. Per-item storage would be duplication with
     * no reader, and duplication is how two copies come to disagree.
     *
     * <p>Monotonic: surfaces are added by applying, and there is no un-apply gesture,
     * because nothing in the app can re-bind a live wallpaper. See AGENTS.md.
     */
    private final Set<Surface> appliedSurfaces;

    private final SortOrder sort;

    private Library(List<LibraryItem> items, String activeId, SortOrder sort,
                    Set<Surface> applied) {
        this.items = Collections.unmodifiableList(items);
        this.activeId = activeId;
        this.sort = sort == null ? SortOrder.DEFAULT : sort;
        // A home wallpaper is a home wallpaper, so an absent set means home. Every
        // pre-v3 install is in exactly that state, and defaulting to home is the
        // answer that describes it rather than one that needs migrating.
        this.appliedSurfaces = Collections.unmodifiableSet(
                applied == null || applied.isEmpty()
                        ? new LinkedHashSet<Surface>(Collections.singleton(Surface.HOME))
                        : new LinkedHashSet<Surface>(applied));
    }

    private Library(List<LibraryItem> items, String activeId, SortOrder sort) {
        this(items, activeId, sort, null);
    }

    /** Which surfaces the selected photo has been applied to. Never empty. */
    public Set<Surface> appliedSurfaces() {
        return appliedSurfaces;
    }

    /** True when this photo is the live wallpaper on both screens. */
    public boolean isOnBothSurfaces() {
        return appliedSurfaces.contains(Surface.HOME) && appliedSurfaces.contains(Surface.LOCK);
    }

    /**
     * Record that the selected photo has been applied to a surface.
     *
     * <p>Monotonic, and there is no "remove" for that reason: nothing in the app can
     * re-bind a live wallpaper, so there is no gesture that could mean it. Clearing
     * the selection is what removes a photo from both.
     *
     * <p>Nothing is refused here. There used to be one exception — the demo could
     * not be recorded on the lock screen, on the grounds that it had no image of
     * its own to render there. It has a file now, and the rest of that reasoning
     * (the platform's lock frame is captured from whatever is live) is true of
     * every photograph equally, so it belongs where it is already handled: the
     * caller asks {@link SurfaceResolver#supportsPerSurfaceLock} before offering
     * the choice at all. A rule that named one row and not the rest is the
     * special-casing this index no longer has.
     */
    public Library withAppliedTo(Surface surface, boolean on) {
        if (!on) return this;
        if (appliedSurfaces.contains(surface)) return this;
        Set<Surface> next = new LinkedHashSet<Surface>(appliedSurfaces);
        next.add(surface);
        return new Library(items, activeId, sort, next);
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
        return of(raw, activeId, sort, null);
    }

    private static Library of(List<LibraryItem> raw, String activeId, SortOrder sort,
                              Set<Surface> applied) {
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
        return new Library(unique, active, sort, applied);
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

    /**
     * The photos shown in the live grid and filmstrip: everything not archived.
     *
     * <p>An archived photo is still a row in {@link #items} and still resolves by
     * id — that is what lets archiving the active photo keep the wallpaper — but
     * it is not listed. The empty state and the disclosure both key off this
     * count, not off {@link #size()}.
     */
    public List<LibraryItem> sortedLive() {
        return sortedLive(sort);
    }

    /** {@link #sortedLive()} in the given order. See {@link #sorted(SortOrder)}. */
    public List<LibraryItem> sortedLive(SortOrder order) {
        return filterArchived(sorted(order), false);
    }

    /** The archived photos, for the disclosure's inline section. */
    public List<LibraryItem> sortedArchived() {
        return sortedArchived(sort);
    }

    /** {@link #sortedArchived()} in the given order. */
    public List<LibraryItem> sortedArchived(SortOrder order) {
        return filterArchived(sorted(order), true);
    }

    private static List<LibraryItem> filterArchived(List<LibraryItem> in, boolean archived) {
        List<LibraryItem> out = new ArrayList<LibraryItem>();
        for (LibraryItem item : in) {
            if (item.isArchived() == archived) out.add(item);
        }
        return out;
    }

    /** How many photos the live grid holds. The empty state reads this, not {@link #size()}. */
    public int liveCount() {
        int n = 0;
        for (LibraryItem item : items) {
            if (!item.isArchived()) n++;
        }
        return n;
    }

    /** How many photos are hidden. The disclosure is shown only when this is not zero. */
    public int archivedCount() {
        int n = 0;
        for (LibraryItem item : items) {
            if (item.isArchived()) n++;
        }
        return n;
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

    /** The selected item's id, or null when nothing is selected. */
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
     *
     * <p>The applied-surfaces set is CARRIED ACROSS. It was not, because this method
     * rebuilt the index through the three-argument {@code of} and that defaults the
     * set to {{HOME}} -- so every single edit, on every slider change, silently reset
     * which screens the photo had been applied to. It was invisible only because
     * nothing read the set at the time; the failure was one reader away, which is not
     * a reason to leave it.
     */
    public Library withItem(LibraryItem item) {
        if (item == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>(items);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(item.id)) {
                next.set(i, item);
                return of(next, activeId, sort, appliedSurfaces);
            }
        }
        next.add(item);
        return of(next, activeId, sort, appliedSurfaces);
    }

    /**
     * Select the wallpaper, or pass null for none.
     *
     * <p>An id that is not in the list is treated as null: a selection that
     * points at nothing is exactly the dangling reference this class exists to
     * prevent, so the call is silently corrected rather than stored.
     */
    public Library withSelection(String id) {
        // The applied set is deliberately NOT carried across a selection change. It
        // describes the photo that WAS selected, so keeping it would claim the new
        // photo is on surfaces nobody applied it to -- and the segment would offer to
        // edit a lock screen that does not exist.
        return of(items, id, sort, null);
    }

    /**
     * Hide a photo from the live gallery, or bring it back.
     *
     * <p><b>The selection is not touched, and that is the decision.</b> Archiving
     * the active photo keeps the wallpaper: the row is hidden, not destroyed, so
     * the pointer still resolves and the engine keeps drawing the same relief.
     * Delete is the contrast — {@link #remove} clears the selection — and the two
     * must not be collapsed. "The active row is archived" is a legal state, and
     * {@link #of} requires only that the active id resolve to an item in
     * {@code items}, which an archived row still does. No self-heal exists for
     * this, because self-healing here would be doing the delete the user chose
     * not to do.
     *
     * <p><b>{@code appliedSurfaces} is carried through</b>, the {@link #withItem}
     * trap: this rebuilds through the four-argument {@code of} rather than the
     * three-argument one, or every archive silently records that the photo was
     * never applied to the lock screen.
     *
     * <p>An unknown id is a no-op, so a caller cannot invent a row.
     */
    public Library withArchived(String id, boolean on) {
        LibraryItem item = find(id);
        if (item == null) return this;
        return withItem(item.withArchived(on));
    }

    /**
     * Archive or restore a whole set in one write.
     *
     * <p>For the mixed-pick verbs: one commit, so a multi-select archive cannot be
     * observed half-applied. Unknown ids are skipped, exactly as the single form.
     */
    public Library withArchived(Set<String> ids, boolean on) {
        if (ids == null || ids.isEmpty()) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>(items);
        boolean changed = false;
        for (int i = 0; i < next.size(); i++) {
            LibraryItem item = next.get(i);
            if (ids.contains(item.id) && item.isArchived() != on) {
                next.set(i, item.withArchived(on));
                changed = true;
            }
        }
        return changed ? of(next, activeId, sort, appliedSurfaces) : this;
    }

    /**
     * Change the list order.
     *
     * <p>Read order only, so this writes no file and reorders nothing: the
     * stored list stays in insertion order and the order itself lives in the
     * blob, so it survives a restart.
     */
    public Library withSort(SortOrder order) {
        return of(items, activeId, order, appliedSurfaces);
    }

    /** Record that the wallpaper is showing this item. */
    public Library markUsed(String id, long when) {
        LibraryItem item = find(id);
        return item == null ? this : withItem(item.withLastUsedAt(when));
    }

    /**
     * Seed the bundled item, but only into an otherwise-empty library.
     *
     * <p>The demo is an ordinary preloaded photo with an ordinary file behind it:
     * it can be selected, renamed, edited, given a new map, put in a playlist and
     * deleted like any other row, and a delete must stick. It is seeded rather
     * than baked into the grid so that a gallery the user has emptied is a state
     * the app can be in.
     *
     * <p><b>Only when the library is otherwise empty, and only the caller can
     * know whether empty means fresh.</b> This method seeds any empty library;
     * that is a preload only because the app's load path calls it when the
     * stored index is ABSENT rather than when the parsed library happens to
     * have no items. An index that was written and parses to zero items is a
     * gallery the user emptied -- seeding it would resurrect the row they
     * deleted, on the very next load, no matter how many other photos they
     * deleted first. A missing blob is a first run, or the state a reset
     * leaves, and that is the one state that gets the demo so the gallery is
     * never an empty room before the user has done anything. The two
     * questions are deliberately not the same one, and asking the wrong one is
     * exactly how a delete stops sticking.
     *
     * <p>Where it lands is decided by its zero timestamps, not by where it is
     * inserted: every time-based order puts it last, which is right, because a
     * bundled bust is not a photo the user added and leading the newest-first
     * list with it would read as this being the current one. Name order ignores
     * timestamps entirely, so it sorts by its label there, like anything else.
     *
     * @param imageName the demo image the seed copied into {@code filesDir}, or
     *                  null when the copy did not happen. Null is not a special
     *                  case to design around: it is what a full disk produces, and
     *                  the row is then read as "the packaged pair" until a later
     *                  copy succeeds. See {@link #withBundledFiles}.
     * @param depthName the demo's map, or null with {@code imageName}
     */
    public Library withBundled(String imageName, String depthName) {
        return withBundled(imageName, depthName, null);
    }

    /**
     * As {@link #withBundled(String, String)}, with the shipped normal map.
     *
     * @param normalName the copied normal, or null when it could not be copied --
     *                   in which case the demo renders flat lit and the sheet says
     *                   so, rather than inventing a normal nothing backs
     */
    public Library withBundled(String imageName, String depthName, String normalName) {
        return withBundled(imageName, depthName, normalName, null, null);
    }

    /** As {@link #withBundled(String, String, String)}, with the demo's cloud. */
    public Library withBundled(String imageName, String depthName, String normalName,
                               String splatName) {
        return withBundled(imageName, depthName, normalName, splatName, null);
    }

    /**
     * The full seed: the demo's four files and its render mode.
     *
     * <p>The mode is for a fresh install whose legacy {@code render_3d} pref was on:
     * the seed inherits it, so the demo shows the cloud it was showing before the mode
     * moved onto the rows — the same one-time inheritance {@link #parse}'s migration
     * gives an existing library. {@link RenderMode#RELIEF} is the default and changes
     * nothing.
     */
    public Library withBundled(String imageName, String depthName, String normalName,
                               String splatName, RenderMode mode) {
        if (!items.isEmpty()) return this;
        LibraryItem demo = LibraryItem.bundled(imageName, depthName, normalName, splatName);
        return withItem(mode == null || mode == RenderMode.RELIEF
                ? demo : demo.withRenderMode(mode));
    }

    /**
     * Give the bundled row the files it is missing, if it is missing them.
     *
     * <p><b>This is the migration, and it is one derivation over the stored
     * index rather than a check at every place the demo is read.</b> A build
     * before the demo was file-backed stored the row with no image name, and
     * every renderer, gallery tile and depth panel had a branch for it. Without
     * this, an upgrading user is left holding the one special-cased item the work
     * was meant to eliminate — and doing it at the render site would mean a
     * special case in the renderer forever, which is what it replaced.
     *
     * <p><b>Idempotent by the row's own contents, not by a flag.</b> The row that
     * has an image name is already done, so a second call returns the same library
     * without touching anything. That matters because this runs on the read path,
     * which runs on every surface creation and every pref change, including on the
     * GL thread at wallpaper cold start — so "did we already do this" has to be
     * answered by looking at the data rather than by remembering.
     *
     * <p><b>It never adds a row.</b> Seeding stays {@link #withBundled}'s job and
     * stays as narrow as it is, which is what keeps a delete from being undone by a
     * load that was only supposed to repair one row's filenames.
     *
     * @param imageName the copied image, or null when the copy did not happen — in
     *                  which case nothing is changed, because naming a file that is
     *                  not there would let the next cleanup pass drop the demo
     */
    public Library withBundledFiles(String imageName, String depthName) {
        return withBundledFiles(imageName, depthName, null, null);
    }

    /**
     * As {@link #withBundledFiles(String, String)}, and also give the demo its
     * shipped normal if it is missing one.
     *
     * <p><b>Four files, and the repair decides each from the row's own contents.</b>
     * A row with no image is the pre-migration or failed-copy state and gets all
     * three named in one write; a row that already has an image is only missing a
     * normal or a cloud the build has just started shipping, and gets that one
     * repaired without touching a depth map the user may since have regenerated. The
     * repair never adds a row and never replaces a normal or cloud the row already
     * has, so it stays idempotent on the read path.
     *
     * @param normalName the copied normal, or null when the copy did not happen
     */
    public Library withBundledFiles(String imageName, String depthName, String normalName) {
        return withBundledFiles(imageName, depthName, normalName, null);
    }

    /**
     * The full repair, with the demo's cloud.
     *
     * @param splatName the copied cloud, or null when the copy did not happen. Like
     *                  the normal, a missing cloud is a degraded demo (the relief),
     *                  not a reason to refuse the pair.
     */
    public Library withBundledFiles(String imageName, String depthName, String normalName,
                                    String splatName) {
        if (imageName == null) return this;
        LibraryItem demo = find(BUNDLED_ID);
        if (demo == null) return this;
        LibraryItem repaired = demo;
        if (demo.imageName == null) {
            repaired = demo.withFiles(imageName, depthName, normalName);
        } else if (demo.normalName == null && normalName != null) {
            // Already file-backed: only a normal the row is missing is new
            // information. Written through the three-file paired write so the depth
            // it belongs to is named in the same call.
            repaired = demo.withFiles(demo.imageName, demo.depthName, normalName);
        }
        // The cloud is repaired independently, for the same reason the normal is: an
        // install that predates the asset gains it without disturbing anything else.
        if (repaired.splatName == null && splatName != null) {
            repaired = repaired.withSplat(splatName);
        }
        return repaired == demo ? this : withItem(repaired);
    }

    /**
     * Unlink an item. The pointer to it is cleared if it held one.
     *
     * <p><b>Deleting the active item is allowed.</b> Refusing it would mean the
     * one photo a user is most likely to want gone — the one filling their
     * screen — is the one they cannot remove, with no explanation. Clearing the
     * selection instead has a visible, honest outcome: the packaged demo pair,
     * which is what the renderer draws for no selection. A gallery that cannot
     * delete is not a gallery.
     *
     * <p><b>The bundled row is not an exception.</b> It is a preloaded photo, not
     * an unremovable one, so removing it drops it — and, like every other id,
     * clears the selection exactly as for any other. The seeding rule that keeps a
     * first run from showing an empty gallery lives in {@link #withBundled},
     * which the app's load path calls only when no index has ever been stored
     * -- so deleting the last row leaves a stored-but-empty gallery, an empty
     * room is a legitimate state, and the row stays gone.
     *
     * <p><b>This touches the index only, never the files.</b> Delete the files
     * <i>after</i> persisting the result, so a crash in between leaves orphan
     * files — recoverable, and invisible to the user — rather than an index that
     * references files which are gone.
     *
     * <p><b>There is no paired config change to make, and that is the point.</b>
     * An earlier design returned the config alongside the index precisely so the
     * selection pointer and the config's copy of the files could not drift apart
     * in one write and not the other. Now that the pointer lives here and only
     * here, the two writes have collapsed into one, and the pair of writes that
     * had to be kept in step no longer exists. The trap did not get harder to
     * remember; it was removed.
     */
    public Library remove(String id) {
        LibraryItem gone = find(id);
        if (gone == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>();
        for (LibraryItem item : items) {
            if (!item.id.equals(id)) next.add(item);
        }
        return of(next, isActive(id) ? null : activeId, sort);
    }

    /**
     * The ids of items that share an image FILE NAME with another item.
     *
     * <p>This exists because one code path did not uphold the rule every other
     * path follows. An item's file is named from its own id precisely so that
     * "two items must never share one" cannot happen — and the share path wrote
     * every import to a fixed {@code shared_image.png} instead. Nine rows then
     * pointed at one file, every row showed the same photo, and each of the eight
     * earlier photos was overwritten and lost. The gallery's own {@code add} note
     * says two items must never share a file; this is what enforcing it costs
     * when a caller forgets.
     *
     * <p><b>Matching on the NAME rather than on the bytes is deliberate.</b> Two
     * different photos can be byte-identical, and collapsing those would silently
     * delete a row the user made on purpose. A shared name, by contrast, is
     * impossible under the fixed write path, so it is a fingerprint of this bug
     * and nothing else — which makes the repair exact and free of file I/O.
     *
     * <p>The bundled item is in no group: its name comes from its own id like every
     * other row's, so a group of two can never contain it. The null check below is
     * the degenerate state — a copy that failed — and skipping it is what keeps a
     * nameless row from being grouped with itself.
     *
     * @return the ids to drop, empty when the index is already consistent
     */
    public List<String> itemsSharingAnImageName() {
        // Group first, then decide once per group, so "which one survives" is a
        // single question answered in one place rather than a rule applied
        // per-item that has to be careful about its own side effects.
        Map<String, List<LibraryItem>> groups = new LinkedHashMap<String, List<LibraryItem>>();
        for (LibraryItem item : items) {
            String image = item.imageName;
            if (image == null || image.length() == 0) continue;   // no file, no group
            List<LibraryItem> group = groups.get(image);
            if (group == null) {
                group = new ArrayList<LibraryItem>();
                groups.put(image, group);
            }
            group.add(item);
        }
        List<String> doomed = new ArrayList<String>();
        for (Map.Entry<String, List<LibraryItem>> e : groups.entrySet()) {
            List<LibraryItem> group = e.getValue();
            if (group.size() < 2) continue;                       // already consistent
            // The active one is kept, so the selection never has to be re-pointed
            // and the wallpaper pointer cannot be left naming a dropped row. If
            // the active is not in the group, the most recently added is kept: of
            // several rows showing one picture, that is the one last worked on.
            LibraryItem keep = null;
            for (LibraryItem item : group) {
                if (item.id.equals(activeId)) keep = item;
            }
            if (keep == null) {
                for (LibraryItem item : group) {
                    if (keep == null || item.addedAt > keep.addedAt) keep = item;
                }
            }
            for (LibraryItem item : group) {
                if (item != keep) doomed.add(item.id);
            }
        }
        return doomed;
    }

    // ------------------------------------------------------------- migration

    /**
     * Adopt the single photo pair a pre-gallery build recorded, so an existing
     * install does not silently lose the wallpaper it is showing.
     *
     * <p><b>Run once, at upgrade, not on every load.</b> This used to be called
     * {@code reconcile} and invoked on every load, which made a self-heal out of
     * a migration: any write path that put a file into filesDir without indexing
     * it would be quietly adopted. Now that every file write goes through the
     * index there is no such path to heal, so the only thing left to do is the
     * one-time adoption — and leaving that on the load path would be a way to
     * resurrect a file the user had deleted by hand.
     *
     * <p><b>Idempotent, by construction.</b> A file is matched by name, not by
     * id, so a second call finds what the first adopted, keeps that item's id,
     * and changes nothing. That matters because the upgrade path is not
     * guaranteed to run exactly once: a crash between adopting and clearing the
     * old keys leaves it to run again.
     *
     * <p>With nothing to adopt, the index is returned unchanged rather than
     * emptied. An index that already has items is authoritative, and must not be
     * reset just because the legacy keys are absent.
     *
     * @param files     {@code {image, depth}} from {@link Config#legacyFiles}, or
     *                  null when there is nothing to adopt
     * @param nowMillis stamped on an adopted item; the caller knows the file and
     *                  can pass its mtime, which is more honest than "now"
     * @param newId     id source for an adopted item, for tests
     */
    public static Library adoptLegacy(Library current, String[] files, long nowMillis,
                                      Supplier<String> newId) {
        if (current == null) current = empty();
        if (files == null || files.length < 1) return current;
        String image = LibraryItem.normaliseName(files[0]);
        if (image == null) return current;

        LibraryItem match = current.findByImage(image);
        if (match != null) return current.withSelection(match.id);

        String depth = files.length > 1 ? LibraryItem.normaliseName(files[1]) : null;
        LibraryItem adopted = LibraryItem.of(freshId(current, newId), image, depth, nowMillis);
        return current.withItem(adopted).withSelection(adopted.id);
    }

    /** {@link #adoptLegacy} with a random id source. */
    public static Library adoptLegacy(Library current, String[] files, long nowMillis) {
        return adoptLegacy(current, files, nowMillis, LibraryItem.NEW_ID);
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
     * <p><b>A row with no file belongs to the index.</b> The bundled row is the
     * only one that can be in that state — it is the one row whose files come from
     * a copy rather than from an import — and it is kept when the index lists it
     * and not added when it does not: this method filters, it never seeds.
     * Seeding is {@link #withBundled}'s job, and it is deliberately narrower, so
     * that a delete cannot be undone by a load that was only supposed to be
     * cleaning missing files.
     *
     * @param fileExists given a bare filename, whether it is present
     */
    public Library retainExisting(Predicate<String> fileExists) {
        if (fileExists == null) return this;
        List<LibraryItem> next = new ArrayList<LibraryItem>();
        for (LibraryItem item : items) {
            // A listed bundled row is kept whether or not its files can be seen
            // here. It is retention, not resurrection -- the row is already in the
            // index, so the index is the authority -- and it is what lets a restored
            // backup that came back without filesDir keep the demo instead of
            // dropping it and leaving an empty gallery. The row's files are put back
            // by the copy the load path runs, which is keyed off this row's
            // existence, so nothing here adds one.
            if (item.isBundled() || Boolean.TRUE.equals(fileExists.test(item.imageName))) {
                next.add(item);
            }
        }
        // The applied set is CARRIED ACROSS, and the reason is the same as in
        // withItem: this method rebuilt through the three-argument `of`, which
        // defaults the set to {{HOME}}, so a load that dropped one row silently
        // recorded that the user had never applied to the lock screen. It was
        // invisible for as long as nothing read the set on that path -- and
        // unlike the other derivations, this one is not behind a user gesture, so
        // it re-runs on every load of every surface.
        return of(next, activeId, sort, appliedSurfaces);
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
    o.put("applied", appliedKeys());
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
        return parse(raw, Config.DEFAULT_MOTION, false);
    }

    /**
     * As {@link #parse(String)}, but told what Motion was before it became
     * per-photo.
     *
     * <p>Motion used to be one global value in {@link Config}. Moving it onto
     * every item is a migration that has to be told the old value, because there
     * is no other copy of it: the library blob never held it. A caller that has a
     * {@code Config} in hand — which in this app means every production caller —
     * must use this form, or an upgrading user's Motion silently resets to the
     * default. The one-argument form above exists for callers that have no Config
     * to offer, which in practice means tests writing a blob from scratch.
     *
     * @param raw          the stored string, or null if nothing has been written
     * @param legacyMotion the global Motion to copy onto each item on upgrade
     */
    /**
     * Whether a stored blob is older than this build and so was migrated in memory
     * by {@link #parse} — which means the blob on disk is still stale and has to be
     * rewritten, or the next migration will be written against a shape nobody
     * actually stored.
     *
     * <p><b>Asked separately from {@code parse} because parsing cannot answer it.</b>
     * A migrated blob resolves perfectly in memory, so every test of the migration
     * passes while the file stays v2 forever and the migration re-runs on every
     * load — including on the GL thread at wallpaper cold start. Found on a device.
     *
     * <p><b>A blob from a NEWER build is not migratable and must never be
     * overwritten.</b> {@code parse} returns an empty library for it, so a caller
     * that rewrites "anything needing migration" with {@code version &lt; VERSION}
     * would silently delete a future version's photos on downgrade. Hence
     * {@code >=} and not {@code ==}: the whole point of this method is to decide
     * whether writing is safe, and clobbering is not.
     *
     * <p>A blob that will not parse is also left alone. It is unusable either way,
     * and overwriting it destroys the only copy of whatever it said.
     *
     * @param raw the stored string; null or blank is not a migration, though a
     *            caller is free to write a fresh blob
     */
    /**
     * Whether a stored blob was written by a build NEWER than this one.
     *
     * <p>Distinct from {@link #needsMigration}, and the difference is the whole
     * safety property: a newer blob is not ours to touch. A caller that sees this
     * true must not write, must not reconcile, and must not treat the empty parse as
     * something to persist -- because persisting it is how an older build deletes a
     * newer one's gallery. It happened.
     *
     * <p>Unreadable is NOT "newer". A corrupt blob is this build's problem and is
     * already handled by writing a clean one.
     */
    public static boolean isNewerVersion(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            return new JSONObject(raw).optInt("version", 0) > VERSION;
        } catch (JSONException e) {
            return false;
        }
    }

    public static boolean needsMigration(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        int version;
        try {
            version = new JSONObject(raw).optInt("version", 0);
        } catch (JSONException e) {
            return false; // unreadable: parse already yields empty, and leave it be
        }
        return version < VERSION;
    }

    /** The applied surfaces as stored keys, in a stable order. */
    private JSONArray appliedKeys() {
        JSONArray a = new JSONArray();
        for (Surface surface : Surface.values()) {
            if (appliedSurfaces.contains(surface)) a.put(surface.key);
        }
        return a;
    }

    /**
     * Read the applied surfaces. Absent means home, which is every pre-v4 install
     * and every fresh one.
     *
     * <p>An unrecognised key is skipped rather than failing the whole blob: this is
     * bookkeeping about the app's own actions, and a key from a newer build is not a
     * reason to lose somebody's gallery.
     */
    private static Set<Surface> appliedFrom(JSONObject o) {
        Set<Surface> out = new LinkedHashSet<Surface>();
        JSONArray a = o.optJSONArray("applied");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String key = a.optString(i, null);
            if (key == null) continue;
            for (Surface surface : Surface.values()) {
                if (surface.key.equals(key)) out.add(surface);
            }
        }
        return out;
    }

    public static Library parse(String raw, int legacyMotion) {
        return parse(raw, legacyMotion, false);
    }

    /**
     * As {@link #parse(String, int)}, told what the global render mode was before it
     * became per-item.
     *
     * <p>The {@code render_3d} pref was one value for the whole app, so the library
     * blob never held it. Moving it onto every item is a migration that has to be
     * told the old value — the same shape as {@code legacyMotion}, one version later
     * and for the same reason: a global render value is the wrong home. A caller
     * with the pref in hand (which in this app means every production caller) must
     * use this form, or a user who had the cloud on silently gets the relief back on
     * every row.
     *
     * @param raw          the stored string, or null if nothing has been written
     * @param legacyMotion the global Motion to copy onto each item on upgrade
     * @param legacySplat  the global {@code render_3d} to copy onto each item on
     *                     upgrade
     */
    public static Library parse(String raw, int legacyMotion, boolean legacySplat) {
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
            o = migrate(o, version, legacyMotion, legacySplat);
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
        return of(parsed, activeId, sort, appliedFrom(o));
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
     * <p>Written as a real hook rather than inlined so the next schema change has
     * an obvious place to go.
     *
     * @param fromVersion the version the blob was written at
     * @param motion      the old GLOBAL Motion, copied onto every item
     */
    private static JSONObject migrate(JSONObject o, int fromVersion, int motion,
                                      boolean legacySplat) {
        if (fromVersion < 3) {
            // v2 -> v3: the six flat render values move under surfaces.home, and
            // Motion -- which was GLOBAL, in Config -- is copied onto every item so
            // no photo changes feel on upgrade. Copying it rather than dropping it
            // is the whole point: a wallpaper that silently became three times
            // more responsive on upgrade is a change nobody can file a bug about.
            //
            // lock is left absent, which reads as "the same as home" -- a photo
            // that never had per-surface settings needs none.
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null || it.has("surfaces")) continue;
                    JSONObject home = new JSONObject();
                    try {
                        if (it.has("intensity")) home.put("depth", it.optInt("intensity"));
                        if (it.has("zoom")) home.put("zoom", it.optInt("zoom"));
                        home.put("motion", motion);
                        if (it.has("orbit_x")) home.put("origin_x", it.optDouble("orbit_x"));
                        if (it.has("orbit_y")) home.put("origin_y", it.optDouble("orbit_y"));
                        if (it.has("pan_x")) home.put("pan_x", it.optDouble("pan_x"));
                        if (it.has("pan_y")) home.put("pan_y", it.optDouble("pan_y"));
                        JSONObject surfaces = new JSONObject();
                        surfaces.put(Surface.HOME.key, home);
                        it.put("surfaces", surfaces);
                    } catch (JSONException ignored) {
                        // One unreadable item is one item at its flat defaults, not
                        // a failed migration for the whole library. These puts
                        // cannot actually throw.
                    }
                }
            }
        }
        if (fromVersion < 4 && legacySplat) {
            // v3 -> v4: the render mode moved off the global `render_3d` pref onto
            // each item. Copying it rather than dropping it is the whole point: a
            // wallpaper that silently lost its cloud on upgrade is a change nobody
            // can file a bug about. An item that already carries a mode keeps it --
            // this is a migration, not an overwrite -- and an item with no key and
            // no legacy value reads as the relief, which is the default.
            JSONArray items = o.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null || it.has("render")) continue;
                    try {
                        it.put("render", RenderMode.SPLAT.key());
                    } catch (JSONException ignored) {
                        // One unreadable item is one item at its default, not a
                        // failed migration for the whole library.
                    }
                }
            }
        }
        return o;
    }

    @Override
    public String toString() {
        return "Library{" + items.size() + " items, active=" + activeId + ", sort=" + sort.key() + "}";
    }
}
