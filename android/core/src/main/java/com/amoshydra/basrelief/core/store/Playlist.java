package com.amoshydra.basrelief.core.store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named, ordered list of library items, plus the conditions under which the
 * wallpaper moves through it.
 *
 * <p><b>A playlist stores no idea of where it has got to.</b> There is no cursor,
 * no position, no "current photo": the only pointer to a photo anywhere in this
 * feature is {@link Library#activeId()}, and rotation writes that same field a
 * user's own selection writes. A second position field would be a second copy of
 * one value, and two copies of a pointer can disagree — which is how a wallpaper
 * ends up advancing from a position its selection does not name. Resuming from
 * the selection instead is also the behaviour a user wants: they pick a photo by
 * hand, and the playlist picks up from there.
 *
 * <p><b>Ids, never filenames.</b> {@link #itemIds} holds {@link LibraryItem#id}s,
 * for the reason the index's selection is a pointer to an id: a re-import, a
 * rename or a restore must not repoint a reference at a different photo.
 *
 * <p><b>An id that no longer resolves is skipped, not fatal.</b> A photo can
 * disappear without the playlist blob being rewritten — a restore onto a device
 * whose filesDir came back empty is the real case — so rotation has to be able to
 * step over a hole. The alternative (dropping any playlist that names a missing
 * id) would lose a user's ordering because one photo went missing.
 *
 * <p><b>The surfaces are intent, not capability.</b> {@link #surfaces} records
 * which screens the user meant this playlist for. Whether this device can honour
 * the lock screen at all is a platform fact resolved by the caller — an engine
 * only learns its surface on API 34, and only 35+ runs a second live engine
 * (R-P1 to R-P3) — so a degrade must never be written into the blob. A stored
 * "this device cannot do lock" would outlive the device and be a lie on the next
 * one, and the honest form of the same information is a playlist that stays put
 * because nobody called {@code advance} for that surface.
 *
 * <p><b>Immutable</b>, like everything else in this package: the studio and the
 * wallpaper each hold a snapshot and swap it, so a mutation in one cannot be seen
 * halfway through by the other.
 */
public final class Playlist {

    /** Opaque and stable, like {@link LibraryItem#id}. See {@link #newId()}. */
    public final String id;

    /** The user's name for it, or null. Never blank. */
    public final String name;

    /** The photos, in order. Unmodifiable; duplicates and junk are dropped on write. */
    public final List<String> itemIds;

    public final Trigger trigger;

    /** The screens it applies to. Unmodifiable. Empty means "not yet": see {@link #appliesTo}. */
    public final List<Surface> surfaces;

    /** Whether the next photo is picked at random rather than in order. */
    public final boolean shuffle;

    /** True for the one playlist that rotates. See {@link Playlists#withActive}. */
    public final boolean active;

    /**
     * When this playlist last rotated, epoch ms. 0 = never.
     *
     * <p><b>Its own clock, deliberately not the photo's.</b> The obvious cheaper
     * field is the selected item's {@code lastUsedAt}, which rotation already
     * writes. It is the wrong answer twice over: a selection the user made by
     * hand is not a rotation, so deriving the rhythm from it means picking a photo
     * in the gallery silently resets the daily timer; and when the selection is not
     * one of this playlist's photos, its stamp belongs to a photograph that has
     * nothing to do with this list. One number per owner, never derived.
     */
    public final long lastAdvancedAt;

    // ------------------------------------------------------------- lifecycle

    private Playlist(String id, String name, List<String> itemIds, Trigger trigger,
                     List<Surface> surfaces, boolean shuffle, boolean active,
                     long lastAdvancedAt) {
        this.id = id;
        this.name = name;
        this.itemIds = Collections.unmodifiableList(itemIds);
        this.trigger = trigger == null ? Trigger.DEFAULT : trigger;
        this.surfaces = Collections.unmodifiableList(surfaces);
        this.shuffle = shuffle;
        this.active = active;
        // A negative stamp from a corrupt blob is a timestamp before the epoch,
        // which reads as "never advanced" to every comparison that matters but is
        // not the same thing, and 0 already means exactly that.
        this.lastAdvancedAt = Math.max(0L, lastAdvancedAt);
    }

    /**
     * A new playlist, with the settings a user gets before choosing any: it
     * rotates daily, on the home screen, in order, and it is not the active one.
     *
     * <p>Home is the default because a surface is chosen at apply time (R-U1) and
     * because a rotation that applied to nothing would be a wallpaper that changes
     * for no visible reason. {@code itemIds} may be empty — the editor builds the
     * list before the user fills it — and an empty playlist is inert rather than
     * broken; see {@link Playlists#advance}.
     */
    public static Playlist create(String id, String name, List<String> itemIds) {
        List<Surface> home = new ArrayList<Surface>();
        home.add(Surface.HOME);
        return new Playlist(id, cleanName(name), cleanItemIds(itemIds),
                Trigger.DEFAULT, home, false, false, 0L);
    }

    /**
     * A new playlist's id.
     *
     * <p>Delegates to {@link LibraryItem#newId()} rather than minting its own,
     * because there is one id rule in this app and a second one would be a second
     * answer to "is this usable": a single token, no path separator, short enough
     * to sit in a filename. A playlist id is never interpolated into a filename,
     * but reusing the rule costs nothing and a divergent copy would not be
     * noticed until an id was rejected in one place and accepted in the other.
     */
    public static String newId() {
        return LibraryItem.newId();
    }

    /** Whether a string can be a playlist id. One rule; see {@link #newId()}. */
    public static boolean isValidId(String id) {
        return LibraryItem.isValidId(id);
    }

    // -------------------------------------------------------------- accessors

    public int size() {
        return itemIds.size();
    }

    public boolean isEmpty() {
        return itemIds.isEmpty();
    }

    /**
     * What to show in a list. Never null and never blank, so a row needs no
     * fallback of its own.
     *
     * <p>An unnamed playlist says "Playlist" rather than its first photo: the name
     * is what identifies a playlist in R-PL3's photo line, and showing a photo
     * there would put the second name of a photo where a name belongs.
     */
    public String displayName() {
        return name != null ? name : "Playlist";
    }

    /**
     * Whether this playlist rotates on the given surface.
     *
     * <p>An empty surface list means no — deliberately. A blob with no readable
     * surface is one this build cannot act on, and guessing HOME for it would
     * start rotating a wallpaper the user never asked to rotate, which is the
     * outcome R-PL3 exists to make visible. Staying put is the harmless answer.
     */
    public boolean appliesTo(Surface surface) {
        if (surface == null) return false;
        for (Surface s : surfaces) {
            if (s == surface) return true;
        }
        return false;
    }

    /**
     * Whether a clock says this playlist should advance now.
     *
     * <p>Three answers, and the middle one is the trap: an event trigger is never
     * due, because the event is what calls {@link Playlists#advance}, and treating
     * its interval of zero as "already elapsed" would fire it on every read.
     *
     * <p>A clock that has gone backwards gives false. The alternative is
     * {@code now - lastAdvancedAt} being hugely positive after a clock change,
     * which advances the wallpaper the moment the user fixes the time.
     *
     * <p>{@code lastAdvancedAt == 0} means never advanced, and "never" is longer
     * than any interval, so the first advance is due at once. That is what a daily
     * playlist means to someone who has just made one.
     */
    public boolean isDue(long now) {
        if (!trigger.isTimed()) return false;
        long elapsed = now - lastAdvancedAt;
        if (elapsed < 0L) return false; // the clock moved backwards
        return elapsed >= trigger.intervalMillis();
    }

    public boolean contains(String itemId) {
        return indexOf(itemId) >= 0;
    }

    /** @return the position, or -1 */
    public int indexOf(String itemId) {
        if (itemId == null) return -1;
        for (int i = 0; i < itemIds.size(); i++) {
            if (itemIds.get(i).equals(itemId)) return i;
        }
        return -1;
    }

    // --------------------------------------------------------------- editing

    /**
     * Every field, so no edit can silently reset one it does not name.
     *
     * <p>This is the {@link LibraryItem#copy} lesson again: a value that has to be
     * copied out of an object into a constructor argument is a value that gets
     * left behind when a field is added later, which is how {@code withDepth}
     * shipped resetting Motion, brightness and shadow.
     */
    private Playlist copy(String name, List<String> itemIds, Trigger trigger,
                          List<Surface> surfaces, boolean shuffle, boolean active,
                          long lastAdvancedAt) {
        return new Playlist(id, name, itemIds, trigger, surfaces, shuffle, active,
                lastAdvancedAt);
    }

    /** Rename. A blank name becomes no name rather than a row of spaces. */
    public Playlist withName(String next) {
        return copy(cleanName(next), itemIds, trigger, surfaces, shuffle, active,
                lastAdvancedAt);
    }

    /**
     * Replace the photos, in order.
     *
     * <p>Order is the user's, so it is stored verbatim; duplicates and unusable ids
     * are dropped because a repeat would show one photo twice in a row and make
     * the "3 of 6" count in R-PL3 a lie. First occurrence wins, so the user's
     * ordering of the survivors is unchanged.
     */
    public Playlist withItemIds(List<String> next) {
        return copy(name, cleanItemIds(next), trigger, surfaces, shuffle, active,
                lastAdvancedAt);
    }

    /** Add one photo at the end. A repeat is ignored rather than duplicated. */
    public Playlist including(String itemId) {
        if (!LibraryItem.isValidId(itemId) || contains(itemId)) return this;
        List<String> next = new ArrayList<String>(itemIds);
        next.add(itemId);
        return withItemIds(next);
    }

    /**
     * Drop one photo, keeping the order of the rest.
     *
     * <p>Order-preserving on purpose: removing the first photo must not reshuffle
     * what was after it, or a delete changes the sequence the user arranged.
     */
    public Playlist excluding(String itemId) {
        if (itemId == null || !contains(itemId)) return this;
        List<String> next = new ArrayList<String>();
        for (String id : itemIds) {
            if (!id.equals(itemId)) next.add(id);
        }
        return withItemIds(next);
    }

    /**
     * Move one photo by whole places, keeping every other photo in order.
     *
     * <p><b>A model operation rather than a list rebuilt in the editor.</b> The
     * editor could hand {@link #withItemIds(List)} a freshly constructed order,
     * but that path re-derives the whole list from a value the UI computed, so an
     * id that arrived from another writer between the read and the write would be
     * dropped as junk rather than moved. Moving by id touches only that id and
     * leaves every other position exactly as it was.
     *
     * <p>A photo that is not in the list, a zero delta, and a move past either
     * end all return {@code this}, so an editor's edge button cannot produce a
     * write that only exists to be undone. This never adds or removes an id -- a
     * hole is moved, not filled -- and it cannot reach the index's selection,
     * because a {@link Playlist} holds no pointer to it.
     *
     * @param itemId the photo to move; null or unknown changes nothing
     * @param delta  negative moves it towards the front, positive towards the back
     */
    public Playlist moved(String itemId, int delta) {
        int at = indexOf(itemId);
        if (at < 0 || delta == 0) return this;
        int to = at + delta;
        if (to < 0 || to >= itemIds.size()) return this;
        List<String> next = new ArrayList<String>(itemIds);
        next.remove(at);
        next.add(to, itemId);
        return withItemIds(next);
    }

    public Playlist withTrigger(Trigger next) {
        return copy(name, itemIds, next, surfaces, shuffle, active, lastAdvancedAt);
    }

    /**
     * Choose the screens this playlist applies to.
     *
     * <p>A null list means "not chosen yet" and becomes the home screen, because
     * that is the one surface every install has. Note the deliberate asymmetry
     * with the parser, which keeps an unreadable list empty: this is the editor
     * choosing a value, and the parser is reporting a blob it could not read, and
     * only one of those should invent a screen.
     */
    public Playlist withSurfaces(List<Surface> next) {
        List<Surface> cleaned = cleanSurfaces(next);
        if (cleaned.isEmpty()) {
            List<Surface> home = new ArrayList<Surface>();
            home.add(Surface.HOME);
            cleaned = home;
        }
        return copy(name, itemIds, trigger, cleaned, shuffle, active, lastAdvancedAt);
    }

    /** Whether this playlist rotates on the given screen as well as the ones it has. */
    public Playlist alsoOn(Surface surface) {
        if (surface == null || surfaces.contains(surface)) return this;
        List<Surface> next = new ArrayList<Surface>(surfaces);
        next.add(surface);
        return copy(name, itemIds, trigger, next, shuffle, active, lastAdvancedAt);
    }

    public Playlist withShuffle(boolean next) {
        return copy(name, itemIds, trigger, surfaces, next, active, lastAdvancedAt);
    }

    /**
     * Whether this playlist rotates.
     *
     * <p>Ignored on its own: {@link Playlists#withActive} is the only way to set
     * it, because it also has to turn the others off, and a setter here would be a
     * second way to reach the field that only sets half of what setting it means.
     */
    public Playlist withActive(boolean next) {
        return copy(name, itemIds, trigger, surfaces, shuffle, next, lastAdvancedAt);
    }

    /** Record that this playlist just rotated. See {@link #lastAdvancedAt}. */
    public Playlist advancedAt(long when) {
        return copy(name, itemIds, trigger, surfaces, shuffle, active,
                Math.max(0L, when));
    }

    // ------------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("trigger", trigger.key());
            o.put("shuffle", shuffle);
            o.put("active", active);
            o.put("last_advanced_at", lastAdvancedAt);
            // Absent rather than null, so a reader needs no special case for it.
            if (name != null) o.put("name", name);
            JSONArray items = new JSONArray();
            for (String id : itemIds) items.put(id);
            o.put("item_ids", items);
            JSONArray surfaces = new JSONArray();
            for (Surface s : this.surfaces) surfaces.put(s.key);
            o.put("surfaces", surfaces);
        } catch (JSONException e) {
            throw new AssertionError("Playlist.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Read one playlist, or null if it is not usable — which is how the blob
     * discards it. Never throws.
     *
     * <p>Every field except the id defaults, so a blob written by a build that had
     * fewer fields reads as a playlist with those defaults rather than dropping.
     * The id must be a JSON string: anything else means the blob is not what this
     * build writes, and a guessed id would collide with a real one.
     *
     * <p>{@code surfaces} is passed through as read rather than defaulted, which
     * is the one place {@link #withSurfaces}'s "null becomes home" does not apply.
     */
    static Playlist fromJson(JSONObject o) {
        if (o == null) return null;
        Object rawId = o.opt("id");
        if (!(rawId instanceof String)) return null;
        String id = (String) rawId;
        if (!isValidId(id)) return null;

        List<String> items = new ArrayList<String>();
        JSONArray arr = o.optJSONArray("item_ids");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object raw = arr.opt(i);
                // A non-string in the list is one hole to step over, not a reason
                // to lose the other six photos.
                if (raw instanceof String) items.add((String) raw);
            }
        }

        List<Surface> surfaces = new ArrayList<Surface>();
        JSONArray ss = o.optJSONArray("surfaces");
        if (ss != null) {
            for (int i = 0; i < ss.length(); i++) {
                Object raw = ss.opt(i);
                if (!(raw instanceof String)) continue;
                Surface s = Surface.fromKey((String) raw, null);
                if (s != null && !surfaces.contains(s)) surfaces.add(s);
            }
        }

        return new Playlist(id, cleanName(o.optString("name", null)),
                cleanItemIds(items), Trigger.parse(o.optString("trigger", null)),
                surfaces,
                o.optBoolean("shuffle", false),
                o.optBoolean("active", false),
                o.optLong("last_advanced_at", 0L));
    }

    // ----------------------------------------------------------------- utils

    private static String cleanName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Drop unusable ids and repeats, keeping the first of each.
     *
     * <p>A repeat would show one photo twice running and make the position count
     * in R-PL3 ("3 of 6") wrong, which is a small lie that is always on screen.
     */
    private static List<String> cleanItemIds(List<String> raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String id : raw) {
            if (!LibraryItem.isValidId(id) || out.contains(id)) continue;
            out.add(id);
        }
        return out;
    }

    private static List<Surface> cleanSurfaces(List<Surface> raw) {
        List<Surface> out = new ArrayList<Surface>();
        if (raw == null) return out;
        for (Surface s : raw) {
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Playlist{" + id + " " + displayName() + " " + itemIds
                + " " + trigger.key() + " on " + surfaces
                + (shuffle ? " shuffled" : "") + (active ? " ACTIVE" : "")
                + " last=" + lastAdvancedAt + "}";
    }
}