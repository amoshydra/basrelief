package com.amoshydra.basrelief.core.store;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One entry in a {@link Library}: an image, an optional depth map, and just
 * enough metadata to list it and to find it again.
 *
 * <p><b>Identity is {@link #id}, never the filename.</b> The id is an opaque token
 * minted once when the item is added and never reused. Everything that points at
 * an item — currently the library's own {@code activeId} — points at the id, so a
 * re-import, a rename or a restore cannot silently repoint a reference at a
 * different photo. The filename is a storage detail.
 *
 * <p><b>Bare filenames only, never paths.</b> See {@link PrefFiles} for the bug
 * this prevents: with {@code allowBackup="true"} a stored absolute path survives
 * a restore and still names the previous device's {@code filesDir}, so the app
 * comes back referencing files that are not there and drops the user to the
 * bundled demo with no error. Every name this class accepts is reduced to its
 * basename, which also collapses a {@code ../} traversal to something inside
 * {@code filesDir}.
 *
 * <p><b>No version field of its own.</b> {@link Library} owns the schema version;
 * an item rides inside its blob, so a second versioned field here would be a
 * second thing to bump and a second thing to migrate.
 *
 * <p><b>Immutable</b>, like {@link Config}. The studio and the wallpaper each
 * hold a snapshot and swap it, so a mutation in one cannot be observed halfway
 * through by the other.
 */
public final class LibraryItem {

    /** Opaque, stable for the life of the item. See {@link #newId()}. */
    public final String id;

    /** Bare filename in filesDir. Never null — an item with no image is not an item. */
    public final String imageName;

    /** Bare filename in filesDir, or null for a flat item with no depth map. */
    public final String depthName;

    /** User-visible name, or null to fall back to {@link #imageName}. */
    public final String label;

    /** When the item entered the library, epoch ms. 0 if unknown. */
    public final long addedAt;

    /** When it was last set as the wallpaper, epoch ms. 0 = never used. */
    public final long lastUsedAt;

    /**
     * @param id        {@link #isValidId}, else this throws
     * @param imageName image filename or path; reduced to a basename
     * @param depthName depth filename or path, or null
     * @throws IllegalArgumentException if the id or the image is unusable. The
     *         parse path never lets this escape — {@link #fromJson} catches it
     *         and reports the entry as unusable, so one bad item costs one item
     *         and not the whole index.
     */
    public LibraryItem(String id, String imageName, String depthName, String label,
                       long addedAt, long lastUsedAt) {
        String img = normaliseName(imageName);
        if (!isValidId(id)) throw new IllegalArgumentException("bad item id: " + id);
        if (img == null) throw new IllegalArgumentException("bad image name: " + imageName);
        this.id = id;
        this.imageName = img;
        this.depthName = normaliseName(depthName);
        this.label = (label == null || label.isEmpty()) ? null : label;
        // A negative stamp from a corrupt blob would sort ahead of everything
        // and could never be reached again. Unknown is 0, which sorts last in
        // every order that looks at time.
        this.addedAt = Math.max(0L, addedAt);
        this.lastUsedAt = Math.max(0L, lastUsedAt);
    }

    /** Convenience for the common case: an image just added, no label. */
    public static LibraryItem of(String id, String imageName, String depthName, long addedAt) {
        return new LibraryItem(id, imageName, depthName, null, addedAt, 0L);
    }

    // ------------------------------------------------------------- identity

    /**
     * Mint an id.
     *
     * <p>A timestamp is not an id: two photos can be added inside the same
     * millisecond (one picker callback, a bulk import, a restored backup
     * replayed on a fast machine), and the second one would overwrite the first.
     * A random UUID cannot collide in practice, needs no counter that could be
     * reset by a reinstall, and keeps working when items are removed and
     * re-added in any order.
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * A supplier of {@link #newId()}, for the default id source in
     * {@link Library#reconcile}. Wrapped once here so the production path does
     * not have to pass a lambda and the test path can pass a counter.
     */
    public static final Supplier<String> NEW_ID = new Supplier<String>() {
        @Override
        public String get() {
            return newId();
        }
    };

    /**
     * Whether a string can be an id.
     *
     * <p>It must be a single non-blank token with no path separator. An id is
     * also interpolated into a filename by {@link #imageFileName}, so a value
     * carrying {@code /} would let a corrupt index name a file outside
     * filesDir.
     */
    public static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 64
                && !id.trim().isEmpty()
                && id.indexOf('/') < 0 && id.indexOf('\\') < 0;
    }

    // ------------------------------------------------------------ filenames

    /**
     * Reduce a stored name to a bare filename, or null if there is nothing usable.
     *
     * <p>Two inputs this has to survive, both of which occur in the wild: an
     * absolute path from an older build, and a {@code ../} traversal in a
     * corrupt blob. Both collapse to a basename, which is the value the current
     * build writes and the only form {@link PrefFiles#resolve} expects.
     *
     * <p>Rejected outright: empty, {@code .}, {@code ..}, anything with a
     * remaining separator, and anything with a control character. A name
     * carrying a NUL parses as a name and then fails at {@code open()}, far
     * from the blob that produced it.
     *
     * <p>A backslash is rejected rather than collapsed. The storage it names is
     * Linux, where a backslash is an ordinary character in a filename, so
     * treating it as a separator would be guessing at a path the writer did not
     * mean — and it means a value that is only a Windows path is dropped instead
     * of quietly resolving to a file nobody asked for.
     */
    public static String normaliseName(String stored) {
        if (stored == null) return null;
        String base = new File(stored).getName(); // strips any directory part
        if (base.isEmpty() || base.equals(".") || base.equals("..")) return null;
        if (base.indexOf('/') >= 0 || base.indexOf('\\') >= 0) return null;
        for (int i = 0; i < base.length(); i++) {
            if (base.charAt(i) < 0x20 || base.charAt(i) == 0x7f) return null;
        }
        return base;
    }

    /**
     * The image filename to write for a new item.
     *
     * <p>Derived from the id precisely so two items cannot share a file: the
     * single-slot names in use today ({@code custom_image.png}) are overwritten
     * by the next import, and an index holding two items pointing at one file
     * would render one photo twice and delete the other.
     *
     * @throws IllegalArgumentException if the id is not usable, rather than
     *         quietly producing a name two other ids could also produce
     */
    public static String imageFileName(String id) {
        return requireValidId(id, "item-" + id + ".png");
    }

    /** The depth filename to write for a new item. See {@link #imageFileName}. */
    public static String depthFileName(String id) {
        return requireValidId(id, "item-" + id + "-depth.png");
    }

    private static String requireValidId(String id, String candidate) {
        // The id is checked rather than trusted, because the candidate is then
        // only sanitised, not rejected: a traversal id would collapse to a name
        // that another traversal id could also produce, and two items would end
        // up sharing one file.
        if (!isValidId(id)) throw new IllegalArgumentException("unusable item id: " + id);
        String out = normaliseName(candidate);
        if (out == null) throw new IllegalArgumentException("unnameable id: " + candidate);
        return out;
    }

    // -------------------------------------------------------------- derived

    /** Whether this item can actually produce relief. No depth file = flat. */
    public boolean hasDepth() {
        return depthName != null;
    }

    /**
     * What to show in a list: the label if the user gave it one, else the
     * filename. Never null, so a sort comparator needs no null case.
     */
    public String displayName() {
        return label != null ? label : imageName;
    }

    // ------------------------------------------------- single-field updates

    public LibraryItem withDepth(String depth) {
        return new LibraryItem(id, imageName, depth, label, addedAt, lastUsedAt);
    }

    public LibraryItem withLabel(String newLabel) {
        return new LibraryItem(id, imageName, depthName, newLabel, addedAt, lastUsedAt);
    }

    /** Record that this item is now the wallpaper. */
    public LibraryItem withLastUsedAt(long when) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, when);
    }

    // ---------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("image", imageName);
            o.put("added_at", addedAt);
            o.put("last_used_at", lastUsedAt);
            // Absent rather than null: JSONObject drops nulls anyway, and a
            // missing key reads as "not set" without a special case.
            if (depthName != null) o.put("depth", depthName);
            if (label != null) o.put("label", label);
        } catch (JSONException e) {
            throw new AssertionError("LibraryItem.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Read one item, or null if it is not usable — which is how the index
     * discards it. Never throws.
     *
     * <p>The id must be a JSON string. Anything else means the blob is not what
     * this build writes, and a guessed id would collide with a real one later.
     */
    static LibraryItem fromJson(JSONObject o) {
        if (o == null) return null;
        Object rawId = o.opt("id");
        if (!(rawId instanceof String)) return null;
        String img = normaliseName(o.optString("image", null));
        if (img == null) return null; // no image: nothing to render, nothing to resolve
        try {
            return new LibraryItem((String) rawId, img,
                    o.optString("depth", null),
                    o.optString("label", null),
                    o.optLong("added_at", 0L),
                    o.optLong("last_used_at", 0L));
        } catch (IllegalArgumentException e) {
            return null; // a bad id; see the constructor
        }
    }

    @Override
    public String toString() {
        return "LibraryItem{" + id + " " + imageName + (depthName != null ? " +depth" : "")
                + " label=" + displayName() + " added=" + addedAt + " used=" + lastUsedAt + "}";
    }
}
