package com.amoshydra.basrelief.core.store;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One entry in a {@link Library}: an image, an optional depth map, everything
 * needed to render that photo the way its owner left it, and just enough
 * metadata to list it and find it again.
 *
 * <p><b>Identity is {@link #id}, never the filename.</b> The id is an opaque token
 * minted once when the item is added and never reused. Everything that points at
 * an item — the library's {@code activeId} and {@link Config#activeItemId} — points
 * at the id, so a re-import, a rename or a restore cannot silently repoint a
 * reference at a different photo. The filename is a storage detail.
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

    // --------------------------------------------------------- the settings
    //
    // Depth and Zoom were global, in Config, and one pair of values had to serve
    // every photo. They are the picture's own properties now: a photo framed and
    // lit one way should come back that way, and a second photo should not
    // inherit the first one's zoom. Motion stayed global — see Config.

    /** Depth, as a 0..100 slider value. See {@link #depthFraction()}. */
    public static final int DEFAULT_INTENSITY = 90;
    public static final int INTENSITY_MIN = 0;
    public static final int INTENSITY_MAX = 100;

    /**
     * Zoom, as a slider value that maps onto 0.25x..2.0x. See {@link #zoomScale()}.
     *
     * <p>The minimum is 25 and not 0 because a zoom below 1 inverts the camera.
     */
    public static final int DEFAULT_ZOOM = 100;
    public static final int ZOOM_MIN = 25;
    public static final int ZOOM_MAX = 200;

    /** Orbit is a FRACTION of the plane, so it has a real bound. See {@link #ORBIT_LIMIT}. */
    public static final float DEFAULT_ORBIT = 0f;

    /** Pan is PIXELS, so it has no bound this class could know. See the note below. */
    public static final float DEFAULT_PAN = 0f;

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

    /** True for the reserved bundled item; see {@link Library#BUNDLED_ID}. */
    public boolean isBundled() {
        return Library.BUNDLED_ID.equals(id);
    }

    /**
     * How hard this photo stands out of its plane, 0..100. The Depth slider.
     *
     * <p>The oldest build wrote this on a 0..200 scale and nothing recorded
     * which build wrote a given value, so a stored 150 is clamped to 100 rather
     * than guessed at. That was always a one-time reinterpretation; it is
     * mentioned here because this is now where the value lives.
     */
    public final int intensity; // 0..100

    /** How close the camera is, 25..200 as a percentage of 1.0x. The Zoom slider. */
    public final int zoom;

    /** Where the user framed the photo: a fraction of the plane, -0.5..0.5. */
    public final float orbitX;
    public final float orbitY;

    /** Where the user centred the photo, in PIXELS. Deliberately unclamped. */
    public final float panX;
    public final float panY;

    /** Orbit is a fraction, so a bound exists: half the plane is the far stop. */
    public static final float ORBIT_LIMIT = 0.5f;

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
        this(id, imageName, depthName, label, addedAt, lastUsedAt,
                DEFAULT_INTENSITY, DEFAULT_ZOOM,
                DEFAULT_ORBIT, DEFAULT_ORBIT, DEFAULT_PAN, DEFAULT_PAN);
    }

    /**
     * The full form, for a write site that already holds the settings.
     *
     * <p>The six-argument constructor above is "a photo that has not been
     * adjusted yet", and it is the one a caller reaches for by accident. Anything
     * that is updating an existing item must go through a {@code withX} mutator
     * instead, so a changed value cannot silently reset the other five.
     */
    public LibraryItem(String id, String imageName, String depthName, String label,
                       long addedAt, long lastUsedAt,
                       int intensity, int zoom,
                       float orbitX, float orbitY, float panX, float panY) {
        String img = normaliseName(imageName);
        if (!isValidId(id)) throw new IllegalArgumentException("bad item id: " + id);
        // Every user item must name a file. The one exception is the reserved
        // bundled id, which reads from res/raw and therefore has none -- and it
        // is the only item that cannot be recreated from disk, so refusing it
        // here would leave the gallery with no way back to the default. Gated
        // on the id rather than relaxed generally, so no user item can be given
        // a null name by accident.
        if (img == null && !Library.BUNDLED_ID.equals(id)) {
            throw new IllegalArgumentException("bad image name: " + imageName);
        }
        this.id = id;
        this.imageName = img;
        this.depthName = normaliseName(depthName);
        this.label = (label == null || label.isEmpty()) ? null : label;
        // A negative stamp from a corrupt blob would sort ahead of everything
        // and could never be reached again. Unknown is 0, which sorts last in
        // every order that looks at time.
        this.addedAt = Math.max(0L, addedAt);
        this.lastUsedAt = Math.max(0L, lastUsedAt);
        this.intensity = clamp(intensity, INTENSITY_MIN, INTENSITY_MAX);
        this.zoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        this.orbitX = clamp(orbitX, -ORBIT_LIMIT, ORBIT_LIMIT);
        this.orbitY = clamp(orbitY, -ORBIT_LIMIT, ORBIT_LIMIT);
        // Pan is deliberately NOT clamped here. It is carried in pixels and
        // consumed as panX / surfaceW, so its valid range is the surface size —
        // which this class cannot know, and which differs per device and per
        // orientation. The renderer's setPan() clamps against the real surface.
        //
        // Clamping to the orbit-style +/-0.5 here looked harmless and silently
        // flattened every pan offset to almost nothing: a stored -135px became
        // -0.5px. Do not add a range check without knowing the units.
        this.panX = panX;
        this.panY = panY;
    }

    /** Convenience for the common case: an image just added, no label. */
    public static LibraryItem of(String id, String imageName, String depthName, long addedAt) {
        return new LibraryItem(id, imageName, depthName, null, addedAt, 0L);
    }

    /**
     * The bundled marble bust: no files, because it is read from {@code res/raw}.
     *
     * <p>Both names are null on purpose. The renderer treats a null image path
     * as "use the bundled pair", and it only does so when the depth path is null
     * too -- see the fallback in {@code ReliefRenderer.loadPair}, where a null
     * image with a non-null depth would render the bundled image against the
     * user's depth map.
     */
    public static LibraryItem bundled() {
        return new LibraryItem(Library.BUNDLED_ID, null, null, "Marble bust", 0L, 0L);
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

    /** Depth as a fraction of the reachable maximum — see CameraModel.setDepth. */
    public float depthFraction() {
        return intensity / 100f;
    }

    /** Zoom as a multiplier: 25 -> 0.25x, 100 -> 1.0x, 200 -> 2.0x. */
    public float zoomScale() {
        return zoom / 100f;
    }

    // ------------------------------------------------- single-field updates

    public LibraryItem withDepth(String depth) {
        return new LibraryItem(id, imageName, depth, label, addedAt, lastUsedAt,
                intensity, zoom, orbitX, orbitY, panX, panY);
    }

    public LibraryItem withLabel(String newLabel) {
        return new LibraryItem(id, imageName, depthName, newLabel, addedAt, lastUsedAt,
                intensity, zoom, orbitX, orbitY, panX, panY);
    }

    /** Record that this item is now the wallpaper. */
    public LibraryItem withLastUsedAt(long when) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, when,
                intensity, zoom, orbitX, orbitY, panX, panY);
    }

    /** The Depth slider, 0..100. Clamped like the constructor. */
    public LibraryItem withIntensity(int v) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                v, zoom, orbitX, orbitY, panX, panY);
    }

    /** The Zoom slider, 25..200. Clamped like the constructor. */
    public LibraryItem withZoom(int v) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                intensity, v, orbitX, orbitY, panX, panY);
    }

    /**
     * Where the photo is framed, as a fraction of the plane.
     *
     * <p>A tap in the studio lands here, and it is a fraction, so it is bounded:
     * a plane moved half its own width is as far as the view can go before the
     * photo leaves the screen.
     */
    public LibraryItem withOrbit(float x, float y) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                intensity, zoom, x, y, panX, panY);
    }

    /**
     * Where the photo is centred, in PIXELS.
     *
     * <p>Not clamped, and not measurable here: the bound is the surface, which
     * does not exist yet at this point. The renderer clamps on the way in. See
     * the note in the constructor before adding a range check.
     */
    public LibraryItem withPan(float x, float y) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                intensity, zoom, orbitX, orbitY, x, y);
    }

    // ---------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("image", imageName);
            o.put("added_at", addedAt);
            o.put("last_used_at", lastUsedAt);
            o.put("intensity", intensity);
            o.put("zoom", zoom);
            o.put("orbit_x", (double) orbitX);
            o.put("orbit_y", (double) orbitY);
            o.put("pan_x", (double) panX);
            o.put("pan_y", (double) panY);
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
     *
     * <p>Every setting defaults when it is absent, which is what makes a v1 item
     * read as a v2 item with defaults. The version lives in {@link Library}, so
     * there is nothing here to branch on.
     */
    static LibraryItem fromJson(JSONObject o) {
        if (o == null) return null;
        Object rawId = o.opt("id");
        if (!(rawId instanceof String)) return null;
        String id = (String) rawId;
        String img = normaliseName(o.optString("image", null));
        // No image means nothing to render and nothing to resolve -- UNLESS this
        // is the reserved bundled item, which reads from res/raw and so has no
        // file by design. Without this exemption the bundled item is silently
        // dropped on every parse, and since it cannot be recreated from disk the
        // gallery loses its only way back to the default on the next launch.
        if (img == null && !Library.BUNDLED_ID.equals(id)) return null;
        try {
            return new LibraryItem(id, img,
                    o.optString("depth", null),
                    o.optString("label", null),
                    o.optLong("added_at", 0L),
                    o.optLong("last_used_at", 0L),
                    o.optInt("intensity", DEFAULT_INTENSITY),
                    o.optInt("zoom", DEFAULT_ZOOM),
                    (float) o.optDouble("orbit_x", DEFAULT_ORBIT),
                    (float) o.optDouble("orbit_y", DEFAULT_ORBIT),
                    (float) o.optDouble("pan_x", DEFAULT_PAN),
                    (float) o.optDouble("pan_y", DEFAULT_PAN));
        } catch (IllegalArgumentException e) {
            return null; // a bad id; see the constructor
        }
    }

    /**
     * Write any absent setting into a stored item, at its default.
     *
     * <p>For {@link Library}'s migration only. The parse path already defaults
     * what it cannot find, so this cannot change what a v1 item reads as — it
     * makes the blob on disk the current shape as soon as it is read, rather
     * than whenever something next writes it, so a later migration has a shape
     * to migrate from.
     *
     * <p>Only ever adds keys, and only for an item this build can read, so a
     * malformed entry is left exactly as it was for {@link #fromJson} to drop.
     */
    static void stampDefaults(JSONObject o) {
        if (o == null) return;
        try {
            if (!o.has("intensity")) o.put("intensity", DEFAULT_INTENSITY);
            if (!o.has("zoom")) o.put("zoom", DEFAULT_ZOOM);
            if (!o.has("orbit_x")) o.put("orbit_x", (double) DEFAULT_ORBIT);
            if (!o.has("orbit_y")) o.put("orbit_y", (double) DEFAULT_ORBIT);
            if (!o.has("pan_x")) o.put("pan_x", (double) DEFAULT_PAN);
            if (!o.has("pan_y")) o.put("pan_y", (double) DEFAULT_PAN);
        } catch (JSONException e) {
            // A value this build cannot store anyway; fromJson defaults it.
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return "LibraryItem{" + id + " " + imageName + (depthName != null ? " +depth" : "")
                + " label=" + displayName() + " added=" + addedAt + " used=" + lastUsedAt
                + " depth=" + intensity + " zoom=" + zoom
                + " orbit=" + orbitX + "," + orbitY + " pan=" + panX + "," + panY + "}";
    }
}
