package com.amoshydra.basrelief.core.store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Every playlist the user has, in one versioned blob under one key — the same
 * shape and the same two reasons as {@link Config} and {@link Library}: one key
 * means one atomic write, so a reader can never observe half-updated state, and a
 * new field is a key an older build ignores rather than one it misreads.
 *
 * <p><b>It is a separate blob on purpose.</b> A playlist's shape does not affect
 * the gallery index, so there is no version to bump there and no migration to
 * write in two places. Rotation needs both blobs; nothing else does.
 *
 * <p><b>Rotation writes the index's selection, and there is nothing else for it
 * to write.</b> That is the invariant this whole class exists to hold. The index
 * owns the pointer to the current photo — see the class note on {@link Library} —
 * and a rotation is a user write by every mechanism that matters: it goes through
 * {@link Library#withSelection} and {@link Library#markUsed}, it lands in the same
 * key, and it is read back by the same code. A playlist holds no current photo, no
 * cursor and no position, so there is no second pointer that could disagree with
 * the first — and a wallpaper showing a photo the user deleted is what two
 * disagreeing pointers look like from the home screen.
 *
 * <p><b>At most one playlist rotates.</b> Enforced here rather than in the editor,
 * so no caller can route around it. The reason follows from the paragraph above:
 * there is one selection in the index and one position in it, so two active
 * playlists would each write that pointer and the second would silently undo the
 * first, leaving a wallpaper that appears to skip. Which <i>surfaces</i> the one
 * playlist applies to is its own field, so covering both screens is a setting
 * rather than a reason to need two rotators.
 *
 * <p><b>A delete touches both blobs.</b> {@link #afterRemoving} derives the pair
 * together rather than making the caller remember the second half, because the
 * failure it prevents is specific: a playlist that still names a deleted photo
 * will select it on its next rotation, and the index will silently drop that
 * selection — so the wallpaper stops changing and the playlist looks broken rather
 * than stale. SharedPreferences can put both keys in one editor, so the caller
 * really does commit them as one write; deriving them apart would make that
 * impossible to arrange.
 *
 * <p><b>Never throws.</b> {@link #parse} keeps whatever it can read and drops only
 * what it cannot, because a corrupt blob must not mean "the wallpaper starts
 * changing at random", and a throw on the cold-start path is worse than either.
 */
public final class Playlists {

    /**
     * The first version of this blob, so there is nothing to migrate from yet.
     *
     * <p>{@link #migrate} exists anyway, as a no-op hook, for the same reason
     * {@link Library}'s first two versions are no-ops: the next shape change then
     * has an obvious place to go instead of being written into the parse path
     * where it cannot be read.
     */
    public static final int VERSION = 1;

    /** The single SharedPreferences key the blob lives under. */
    public static final String KEY = "playlists";

    /**
     * Where a shuffled choice comes from when the caller does not care.
     *
     * <p>Static because it is only a convenience for the overload that does not
     * take one, and because a shuffle nobody asked to be reproducible should not
     * be: {@link Random} is thread-safe, and the seeded form is what the tests
     * pass in so a rotation can be replayed.
     */
    private static final Random SHARED_RANDOM = new Random();

    /** Insertion order, never reordered: a playlist is a list, not a set. */
    private final List<Playlist> playlists;

    private Playlists(List<Playlist> playlists) {
        this.playlists = Collections.unmodifiableList(playlists);
    }

    /** No playlists at all, which is every install's first state. */
    public static Playlists empty() {
        return new Playlists(new ArrayList<Playlist>());
    }

    /**
     * The one place the two invariants are enforced, so no code path can build a
     * set that breaks them: ids are unique (first writer wins) and at most one
     * playlist is active (first active wins).
     *
     * <p>Both are "first wins" for the same reason as {@link Library#of}: the
     * entry that was written first is the one that was there first, so it is the
     * one the rest of the blob was built around.
     */
    private static Playlists of(List<Playlist> raw) {
        List<Playlist> unique = new ArrayList<Playlist>();
        boolean activeTaken = false;
        for (Playlist p : raw) {
            if (p == null) continue;
            boolean seen = false;
            for (Playlist kept : unique) {
                if (kept.id.equals(p.id)) { seen = true; break; }
            }
            if (seen) continue;
            // A second active entry is a corrupt blob, and picking one silently
            // would be picking for the user. The first is the one that was already
            // rotating, so the others stand down.
            if (p.active && !activeTaken) {
                activeTaken = true;
                unique.add(p);
            } else if (p.active) {
                unique.add(p.withActive(false));
            } else {
                unique.add(p);
            }
        }
        return new Playlists(unique);
    }

    // ------------------------------------------------------------- reading

    /** Every playlist, in the order the editor listed them. Unmodifiable. */
    public List<Playlist> playlists() {
        return playlists;
    }

    public int size() {
        return playlists.size();
    }

    public boolean isEmpty() {
        return playlists.isEmpty();
    }

    /** @return the playlist, or null if there is no such id */
    public Playlist find(String id) {
        if (id == null) return null;
        for (Playlist p : playlists) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /**
     * The one playlist that rotates, or null when none does.
     *
     * <p>At most one, by the class note. The photo line (R-PL3) wants this: a
     * wallpaper that changes on its own must be able to name what is changing it,
     * and "the first of several" is not something to put in front of a user.
     */
    public Playlist active() {
        for (Playlist p : playlists) {
            if (p.active) return p;
        }
        return null;
    }

    /** The playlist that rotates on the given screen, or null. */
    public Playlist rotating(Surface surface) {
        Playlist p = active();
        return (p != null && p.appliesTo(surface)) ? p : null;
    }

    // ------------------------------------------------------------ mutating

    /**
     * Add a playlist, or replace the one with the same id.
     *
     * <p>A replacement keeps its position, for the reason
     * {@link Library#withItem} does: renaming a playlist must not move its row
     * out from under a thumb.
     */
    public Playlists withPlaylist(Playlist playlist) {
        if (playlist == null) return this;
        List<Playlist> next = new ArrayList<Playlist>(playlists);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(playlist.id)) {
                next.set(i, playlist);
                return of(next);
            }
        }
        next.add(playlist);
        return of(next);
    }

    /** Unlink a playlist. The photo rows are untouched — they are not its to delete. */
    public Playlists without(String playlistId) {
        if (playlistId == null || find(playlistId) == null) return this;
        List<Playlist> next = new ArrayList<Playlist>();
        for (Playlist p : playlists) {
            if (!p.id.equals(playlistId)) next.add(p);
        }
        return of(next);
    }

    /**
     * Start or stop a playlist rotating, and stand the others down.
     *
     * <p>Switching one on switches every other one off, because one is all the
     * index can express. See the class note for why two rotators cannot coexist.
     *
     * <p>Setting the already-current value changes nothing and returns {@code this},
     * so a toggle in the UI cannot produce a write that only exists to be undone.
     */
    public Playlists withActive(String playlistId, boolean active) {
        if (find(playlistId) == null) return this;
        boolean changed = false;
        List<Playlist> next = new ArrayList<Playlist>();
        for (Playlist p : playlists) {
            // Activating names exactly one winner; deactivating touches exactly one
            // row. Conflating the two is how "off" ends up meaning "all off".
            boolean want = active
                    ? p.id.equals(playlistId)
                    : (p.id.equals(playlistId) ? false : p.active);
            Playlist updated = p.withActive(want);
            if (updated.active != p.active) changed = true;
            next.add(updated);
        }
        return changed ? of(next) : this;
    }

    /** Record that a playlist just rotated, so its next advance is a whole interval away. */
    public Playlists withAdvanced(String playlistId, long when) {
        Playlist p = find(playlistId);
        if (p == null) return this;
        Playlist updated = p.advancedAt(when);
        if (updated.lastAdvancedAt == p.lastAdvancedAt) return this;
        return withPlaylist(updated);
    }

    /**
     * Drop one photo id from every playlist, keeping each one's order.
     *
     * <p>The one half of a delete that is not about the index. {@link #afterRemoving}
     * is the entry point that also removes the row; this is the blob's own half, so
     * a caller that has already dropped the row somewhere else still has one place
     * that knows the ids have to go.
     *
     * <p>The bundled row is an ordinary id here, like everywhere else: it can be
     * put in a playlist and deleted out of one. A guard for it would leave the
     * playlist naming a photo the index no longer has, which rotation would then
     * step over silently -- the exact failure this method exists to prevent.
     *
     * <p>No playlist is dropped for becoming empty. An emptied playlist is inert
     * ({@link #advance} has nothing to rotate to) and keeps its name and its
     * settings, because the user configured those and deleting the photo is not a
     * request to forget them.
     */
    public Playlists withoutItem(String itemId) {
        if (itemId == null) return this;
        List<Playlist> next = new ArrayList<Playlist>();
        boolean changed = false;
        for (Playlist p : playlists) {
            Playlist updated = p.excluding(itemId);
            if (updated != p) changed = true;
            next.add(updated);
        }
        return changed ? of(next) : this;
    }

    // ------------------------------------------------------- across blobs

    /**
     * The two blobs one gesture touches, already consistent with each other.
     *
     * <p>A value rather than two return values because the caller must not be able
     * to take one and forget the other: {@link #selectedId} is null for a gesture
     * that selected nothing, which is what lets the caller skip a pointless write.
     */
    public static final class Result {
        /** The index, with the selection written. */
        public final Library library;

        /** The playlists, with the rotation stamped. */
        public final Playlists playlists;

        /** The photo the rotation selected, or null if nothing moved. */
        public final String selectedId;

        Result(Library library, Playlists playlists, String selectedId) {
            this.library = library;
            this.playlists = playlists;
            this.selectedId = selectedId;
        }

        /** Whether a rotation actually happened. */
        public boolean rotated() {
            return selectedId != null;
        }

        @Override
        public String toString() {
            return "Result{selected=" + selectedId + ", active="
                    + playlists.active() + "}";
        }
    }

    /**
     * Advance the active playlist for a surface, if a clock says it is due.
     *
     * <p><b>The selection is written through the index and nowhere else.</b> The
     * returned {@link Result#library} differs from the one passed in by exactly
     * {@code withSelection(next).markUsed(next, now)} — the same two calls a user's
     * own selection makes, on the same key — and no field of {@link Playlist}
     * changes to record which photo is up. A caller that wants a rotation to be
     * visible has nothing to do but persist the pair.
     *
     * <p><b>Called twice in a row it moves once.</b> The stamp it writes is the
     * playlist's own clock, so an engine that asks on every visibility change, a
     * timer and a user tapping "next" cannot between them burn a day of the
     * interval. That matters here specifically because two surfaces can both ask.
     *
     * <p><b>A surface is a gate, not a hint.</b> A playlist naming only the lock
     * screen does not rotate for the home engine: the lock engine does not exist
     * below API 35 (R-P2), and staying put is the honest answer on a device that
     * cannot do it.
     *
     * @param surface which screen is asking; null means the home screen, so a
     *                caller that never mentions it still gets the rotation
     * @param now     epoch ms, passed in rather than read from the clock so a
     *                decision can be tested and replayed
     * @param random  the source for a shuffled choice, or null for a shared one
     */
    public static Result advance(Playlists playlists, Library library, Surface surface,
                                 long now, Random random) {
        return advance(playlists, library, surface, now, null, random);
    }

    /**
     * Advance the active playlist because an event just happened.
     *
     * <p>The event is one of the non-timed {@link Trigger}s — an unlock now, a
     * pickup when its caller lands — and only a playlist whose own trigger is
     * that same event is due. A timed playlist is left alone however overdue it
     * is: a clock is what advances it, and handing the event in must not become a
     * second clock. See {@link Playlist#isDue(long, Trigger)} for both rules.
     *
     * <p><b>The moment identifies the event.</b> {@code now} is passed once per
     * event and shared by everything that hears it, so two deliveries of one
     * unlock cannot step the playlist twice; a later unlock is a later moment and
     * does advance.
     *
     * <p>Everything after "is it due" is the clock path unchanged: the selection
     * is written through the index by the same two calls a user's tap makes, the
     * playlist stamps its own clock, and the caller persists the pair. An
     * event-triggered rotation is a rotation, not a second kind of move.
     */
    public static Result advance(Playlists playlists, Library library, Surface surface,
                                 long now, Trigger event, Random random) {
        Playlists from = playlists == null ? empty() : playlists;
        Library index = library == null ? Library.empty() : library;
        Surface on = surface == null ? Surface.HOME : surface;
        Result unmoved = new Result(index, from, null);

        Playlist playlist = from.rotating(on);
        if (playlist == null) return unmoved;            // nothing is rotating here
        if (!playlist.isDue(now, event)) return unmoved; // not yet, or not this event
        return rotate(from, playlist, index, now, random);
    }

    /**
     * Move the rotating playlist by one photo now, whatever its schedule says.
     *
     * <p><b>For the user's own "Use" gesture.</b> Switching a playlist on has to
     * be visible in the same moment or the feature reads as broken: a daily list
     * that advanced an hour ago is not due, and waiting 23 hours to learn whether
     * the button worked is exactly the doubt this method removes. It is the same
     * rotation as {@link #advance}, minus the due gate.
     *
     * <p><b>It ignores the schedule but never fakes the clock.</b> The index's
     * selection is written through the same two calls a tap makes, and the
     * playlist is stamped with the real {@code now} — never a future time that
     * would push the next automatic rotation further out. An event trigger is
     * stepped by the same rule as a timed one, because the user is the event.
     *
     * <p>A playlist that rotates nowhere (not active, or its screens unreadable)
     * and one with no living photos both return {@code rotated() == false} and a
     * result identical to the input, so a caller can still persist unconditionally
     * without inventing a move.
     */
    public static Result step(Playlists playlists, Library library, Surface surface,
                              long now, Random random) {
        Playlists from = playlists == null ? empty() : playlists;
        Library index = library == null ? Library.empty() : library;
        Surface on = surface == null ? Surface.HOME : surface;

        Playlist playlist = from.rotating(on);
        if (playlist == null) return new Result(index, from, null);
        return rotate(from, playlist, index, now, random);
    }

    /** {@link #step} with a shared random source for shuffled playlists. */
    public static Result step(Playlists playlists, Library library, Surface surface,
                              long now) {
        return step(playlists, library, surface, now, null);
    }

    /**
     * The rotation itself, shared by the scheduled and the immediate paths so
     * the selection write cannot drift between them.
     *
     * <p>No photo to rotate to is deliberately not stamped: leaving the playlist
     * due means a photo added back into the list is picked up on the next ask,
     * whereas stamping would start a fresh interval on a rotation that never
     * happened.
     */
    private static Result rotate(Playlists from, Playlist playlist, Library index,
                                 long now, Random random) {
        List<String> living = livingIds(playlist, index);
        if (living.isEmpty()) return new Result(index, from, null);

        String next = nextId(playlist, living, index.activeId(),
                random == null ? SHARED_RANDOM : random);

        // Exactly the user's own write. If this ever becomes something else, the
        // wallpaper has a second way of deciding what to show.
        Library nextIndex = index.withSelection(next).markUsed(next, now);
        return new Result(nextIndex, from.withAdvanced(playlist.id, now), next);
    }

    /** {@link #advance} for an event, with a shared random source for shuffled playlists. */
    public static Result advance(Playlists playlists, Library library, Surface surface,
                                 long now, Trigger event) {
        return advance(playlists, library, surface, now, event, null);
    }

    /** {@link #advance} with a shared random source for shuffled playlists. */
    public static Result advance(Playlists playlists, Library library, Surface surface,
                                 long now) {
        return advance(playlists, library, surface, now, null, null);
    }

    /**
     * Delete one photo: the index row <i>and</i> the id in every playlist, derived
     * together.
     *
     * <p>Both halves in one call because the halves have to be persisted together,
     * and because a caller that computes them separately is a caller that can
     * persist one. A playlist that still names a deleted photo does not fail
     * loudly on its next rotation: {@link #advance} steps over the missing id, so
     * the wallpaper simply never comes back to it, and nothing anywhere says why.
     *
     * <p>The bundled row is removed like any other id: the index drops its row
     * and every playlist loses it in the same derivation, so the two halves
     * cannot end up describing different libraries. Whether an emptied index then
     * gets the demo seeded back is {@link Library#withBundled}'s decision, not
     * this method's -- {@code afterRemoving} always removes.
     */
    public static Result afterRemoving(Playlists playlists, Library library, String id) {
        Playlists from = playlists == null ? empty() : playlists;
        Library index = library == null ? Library.empty() : library;
        if (id == null) return new Result(index, from, null);
        return new Result(index.remove(id), from.withoutItem(id), null);
    }

    /**
     * The ids of a playlist's photos that the index can still resolve.
     *
     * <p>The filter that makes a hole survivable. A playlist can name a photo the
     * gallery no longer holds — the delete missed it, or a restore brought back
     * the blob without the files — and rotation has to step over it rather than
     * refuse the whole list. It also means a rotation can never select something
     * the index cannot render, because every candidate came out of the index.
     */
    private static List<String> livingIds(Playlist playlist, Library index) {
        List<String> out = new ArrayList<String>();
        for (String id : playlist.itemIds) {
            if (index.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * The photo that comes next.
     *
     * <p>In order, it resumes from the <b>index's selection</b> — so a photo the
     * user picked by hand is the one the playlist carries on from, and there is no
     * position field anywhere that could disagree with it. When the selection is
     * not one of this playlist's photos there is no position to resume from and
     * the list starts again at its first photo, which is at least predictable.
     *
     * <p>Shuffled, it never picks the photo already on screen when it has a
     * choice. A shuffle that can land on the current photo shows the user no
     * change at all, and an unchanged wallpaper on a timer is indistinguishable
     * from a broken one — which is exactly the doubt R-PL3 is written to remove.
     */
    private static String nextId(Playlist playlist, List<String> living,
                                 String currentId, Random random) {
        if (playlist.shuffle) {
            List<String> candidates = new ArrayList<String>(living);
            if (candidates.size() > 1) candidates.remove(currentId);
            if (candidates.isEmpty()) return living.get(0);
            return candidates.get(random.nextInt(candidates.size()));
        }
        int at = -1;
        for (int i = 0; i < living.size(); i++) {
            if (living.get(i).equals(currentId)) { at = i; break; }
        }
        return living.get(at < 0 ? 0 : (at + 1) % living.size());
    }

    // ------------------------------------------------------------ serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            JSONArray arr = new JSONArray();
            for (Playlist p : playlists) {
                arr.put(new JSONObject(p.toJson()));
            }
            o.put("playlists", arr);
        } catch (JSONException e) {
            throw new AssertionError("Playlists.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Parse a stored blob, keeping whatever can be read. Never throws.
     *
     * <p>Missing, corrupt or written by a newer build all yield no playlists, which
     * is the safe direction: nothing rotates, exactly as before the feature
     * existed, and the wallpaper keeps whatever the index says. Guessing at a
     * schema is worse — see {@link Library#parse} for the same rule on a blob whose
     * contents are the user's photos.
     *
     * <p>Individual bad entries are dropped rather than failing the whole blob.
     * Losing one unreadable playlist costs one playlist; losing four good ones
     * because of the fifth costs the feature.
     *
     * @param raw the stored string, or null if nothing has been written yet
     */
    public static Playlists parse(String raw) {
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
            // guessing would be worse than not rotating at all.
            return empty();
        }
        if (version < VERSION) {
            o = migrate(o, version);
        }

        List<Playlist> parsed = new ArrayList<Playlist>();
        try {
            JSONArray arr = o.optJSONArray("playlists");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Playlist p = Playlist.fromJson(arr.optJSONObject(i));
                    if (p != null) parsed.add(p);
                }
            }
        } catch (RuntimeException e) {
            // Unreachable for well-formed input and org.json is lenient, but the
            // promise is unconditional: keep what was read, drop what was not.
        }
        return of(parsed);
    }

    /**
     * Bring an older blob up to {@link #VERSION}.
     *
     * <p>v0 to v1: nothing, because v1 is the first version of this blob. Written
     * as a real hook rather than inlined so the next shape change has an obvious
     * place to go, and a no-op is idempotent by construction — which is the same
     * argument {@link Library#migrate} makes for its own first two steps.
     */
    private static JSONObject migrate(JSONObject o, int fromVersion) {
        return o;
    }

    @Override
    public String toString() {
        return "Playlists{" + playlists.size() + ", active="
                + (active() == null ? "none" : active().id) + "}";
    }
}