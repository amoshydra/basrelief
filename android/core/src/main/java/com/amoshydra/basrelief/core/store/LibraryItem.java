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
    /**
     * The home screen's settings. The only place a render value is stored.
     *
     * <p>This was nine separate fields once, and the decomposition is what made it
     * possible to add Motion, brightness and shadow and forget to store three of
     * them: a value that has to be copied out of another object into a field is a
     * value that can be left behind. One field, assigned once, cannot.
     *
     * <p>{@link #lock} is null until the lock screen is customised, and null means
     * "the same as home" — see {@link #settings(Surface)}, which is the only way
     * either is read.
     */
    private final SurfaceSettings home;

    /** The lock screen's own settings, or null while it still mirrors home. */
    private final SurfaceSettings lock;

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
                SurfaceSettings.DEFAULT_DEPTH, SurfaceSettings.DEFAULT_ZOOM,
                SurfaceSettings.DEFAULT_ORIGIN, SurfaceSettings.DEFAULT_ORIGIN,
                SurfaceSettings.DEFAULT_PAN, SurfaceSettings.DEFAULT_PAN);
    }

    /**
     * The form a write site holds six render values for.
     *
     * <p>The six-argument constructor above is "a photo that has not been adjusted
     * yet", and it is the one a caller reaches for by accident. This one still
     * defaults Motion, the two adjustments and the light, so it carries the same
     * hazard for those: a caller updating an existing item through it silently
     * resets them. It exists because a migration or a test legitimately holds a
     * handful of loose numbers; every user-facing write goes through
     * {@link #editSettings} and the {@code withX} mutators, which copy the values
     * they do not name.
     */
    public LibraryItem(String id, String imageName, String depthName, String label,
                       long addedAt, long lastUsedAt,
                       int intensity, int zoom,
                       float orbitX, float orbitY, float panX, float panY) {
        this(id, imageName, depthName, label, addedAt, lastUsedAt,
                new SurfaceSettings(intensity, zoom, SurfaceSettings.DEFAULT_MOTION,
                        orbitX, orbitY, panX, panY,
                        SurfaceSettings.DEFAULT_ADJUST, SurfaceSettings.DEFAULT_ADJUST,
                        SurfaceSettings.DEFAULT_LIGHT_AZIMUTH,
                        SurfaceSettings.DEFAULT_LIGHT_ELEVATION),
                null);
    }

    /**
     * The form every write site uses: both surfaces as values.
     *
     * <p>A site that already has a {@link SurfaceSettings} — every mutation here
     * and the parser — must use this one, because taking the values apart to pass
     * them and putting them back together is a chance to pair the wrong surface
     * with the wrong numbers. That mistake is not hypothetical: writing the lock
     * screen through a loose constructor overwrote the home screen with it, and
     * rendered correctly on both.
     *
     * <p>There used to be a public constructor taking nine loose numbers plus the
     * lock override. It was deleted when the light arrived rather than taught two
     * more arguments: it defaulted everything it was not given, so it was the
     * {@code withDepth} trap with a constructor's name, and after the parser moved
     * here it had no callers left to justify keeping it alive for the next one.
     */
    private LibraryItem(String id, String imageName, String depthName, String label,
                        long addedAt, long lastUsedAt,
                        SurfaceSettings home, SurfaceSettings lockSettings) {
        String img = normaliseName(imageName);
        if (!isValidId(id)) throw new IllegalArgumentException("bad item id: " + id);
        // Every user item must name a file. The one exception is the reserved
        // bundled id, which reads from res/raw and therefore has none. Gated on
        // the id rather than relaxed generally, so no user item can be given a
        // null name by accident. The id is not a delete guard -- see
        // Library.BUNDLED_ID -- it is only what lets this fileless row exist.
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
        // Every clamp happens in SurfaceSettings, once. A value clamped here as
        // well is a value that can be read two ways.
        //
        // Pan is deliberately NOT clamped even there: it is carried in pixels and
        // consumed as panX / surfaceW, so its valid range is the surface size -
        // which this class cannot know, and which differs per device and per
        // orientation. The renderer's setPan() clamps on the way in. Clamping to
        // the origin-style +/-0.5 looked harmless and silently flattened every pan
        // offset to almost nothing: a stored -135px became -0.5px. Do not add a
        // range check without knowing the units.
        this.home = home;
        this.lock = lockSettings;
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
        // "null" is not a filename. It arrives here from a blob whose JSON null was
        // read with optString, which returns the four-character string rather than
        // null -- so a parse-then-write cycle could turn an absent map into a file
        // named "null", and from then on hasDepth() was true and the fix line stayed
        // silent for a photo with no depth. See str().
        if ("null".equals(stored)) return null;
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

    /**
     * A string field, or null when it is absent, JSON null, or the string "null".
     *
     * <p>{@code optString} is not null-safe for a missing key on Android's
     * implementation: given an explicit JSON null it returns the four-character
     * string {@code "null"}, not null. Every field read that way therefore turned
     * "this photo has no depth map" into "this photo has depth map 'null'" the first
     * time the index was rewritten -- and {@code hasDepth()} is
     * {@code depthName != null}, so the app believed a map existed, the gallery drew a
     * depth chip for it, the renderer looked for a file called "null" and fell back to
     * a flat map, and the fix line that exists precisely to say "No depth" never
     * appeared.
     *
     * <p>The literal "null" is treated as absent as well as JSON null, so a blob
     * already written by the buggy build heals on the next parse rather than needing
     * a migration.
     */
    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        if (v == null || v.isEmpty() || "null".equals(v)) return null;
        return v;
    }

    /**
     * The image filename to write for a new item.
     *
     * <p>Derived from the id precisely so two items cannot share a file: the
     * single-slot names in use today are overwritten by the next import, and an
     * index holding two items pointing at one file would render one photo twice and
     * delete the other.
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

    // ------------------------------------------------- per-surface access

    /**
       * The settings for one surface.
       *
       * <p>A null lock override means "the same as home", and returns home's
       * values rather than null - so every reader treats both surfaces
       * identically and a missing override is never a special case at a call
       * site. This is the only way a render value is read, and (with
       * {@link #withSettings}) the only way one is written, which is why the six
       * fields above are private.
       */
    public SurfaceSettings settings(Surface surface) {
        return (surface == Surface.LOCK && lock != null) ? lock : home;
    }

    /** True once the lock screen has its own settings instead of mirroring home. */
    public boolean hasOwnLockSettings() {
        return lock != null;
    }

    /**
       * Replace one surface's settings wholesale. A null lock restores "the same
       * as home".
       *
       * <p>Read-modify-write, never a field setter: a surface's values are a set,
       * and writing one of them without reading the other eight first is how an
       * unrelated value gets reset.
       */
    public LibraryItem withSettings(Surface surface, SurfaceSettings next) {
        boolean lockSurface = surface == Surface.LOCK;
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                lockSurface ? home : next,
                lockSurface ? next : lock);
    }

    /**
       * Edit one surface by a function. The read-modify-write in one call, so no
       * caller can hold a stale copy of the other eight values - every slider
       * change goes through here.
       */
    public LibraryItem editSettings(Surface surface,
            java.util.function.UnaryOperator<SurfaceSettings> edit) {
        return withSettings(surface, edit.apply(settings(surface)));
    }

    /** The Depth slider, 0..100, on one surface. */
    public LibraryItem withIntensity(Surface surface, int v) {
        return editSettings(surface, s -> s.with(v, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /** The Zoom slider, 25..200, on one surface. */
    public LibraryItem withZoom(Surface surface, int v) {
        return editSettings(surface, s -> s.with(s.depth, v, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /**
       * The Motion slider, 0..100, on one surface. It moved here from
       * {@link Config}, where it was global.
       */
    public LibraryItem withMotion(Surface surface, int v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, v, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /** Origin, a fraction of the plane, on one surface. */
    public LibraryItem withOrigin(Surface surface, float x, float y) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, x, y,
                s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /** Pan, in PIXELS, on one surface. Unclamped; see the constructor's note. */
    public LibraryItem withPan(Surface surface, float x, float y) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion,
                s.originX, s.originY, x, y, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /** Brightness and shadow, -100..100, on one surface. Render-time only. */
    public LibraryItem withAdjust(Surface surface, int brightness, int shadow) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion,
                s.originX, s.originY, s.panX, s.panY, brightness, shadow,
                s.lightAzimuth, s.lightElevation));
    }

    /**
     * The light's angles, on one surface. See {@link LightDirection} for the
     * convention: azimuth runs counter-clockwise from the right and wraps,
     * elevation is 0 at grazing and 90 head-on.
     */
    public LibraryItem withLight(Surface surface, float azimuthDegrees, float elevationDegrees) {
        return editSettings(surface, s -> s.withLight(azimuthDegrees, elevationDegrees));
    }

    /**
       * Copy every render value from another item onto this one, for one surface.
       *
       * <p>Per-surface on purpose: how a landscape reads on the home screen says
       * nothing about how it reads behind a lock-screen clock, and a copy that
       * carried both would quietly overwrite a lock screen the user had tuned.
       */
    public LibraryItem copySettingsFrom(LibraryItem source, Surface surface) {
        return withSettings(surface, source.settings(surface));
    }

    // ------------------------------------------------- single-field updates

    public LibraryItem withDepth(String depth) {
        return copy(id, imageName, depth, label, addedAt, lastUsedAt);
    }

    public LibraryItem withLabel(String newLabel) {
        return copy(id, imageName, depthName, newLabel, addedAt, lastUsedAt);
    }

    /** Record that this item is now the wallpaper. */
    public LibraryItem withLastUsedAt(long when) {
        return copy(id, imageName, depthName, label, addedAt, when);
    }

    /**
     * A copy of this item with only its identity fields replaced; every render
     * value carries over.
     *
     * <p>This exists because the alternative is a write site naming six values it
     * did not mean to change, and the short constructor's defaults quietly
     * resetting the other three. Three call sites had exactly that waiting in
     * them.
     */
    private LibraryItem copy(String id, String imageName, String depthName,
                             String label, long addedAt, long lastUsedAt) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lock);
    }

    // ---------------------------------------------------------- serialise

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("image", imageName);
            o.put("added_at", addedAt);
            o.put("last_used_at", lastUsedAt);
            // Nested from v3. Written flat until now, which is fine to read and
            // is exactly why Library.migrate() has to exist: the shape an item is
            // written in and the shape it was written in are different facts, and
            // only the reader has to know both.
            JSONObject surfaces = new JSONObject();
            surfaces.put(Surface.HOME.key, settings(Surface.HOME).toJson());
            if (lock != null) surfaces.put(Surface.LOCK.key, lock.toJson());
            o.put("surfaces", surfaces);
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
    /**
     * One item's home settings, from either the nested v3 shape or the flat one.
     *
     * <p>Both are read here rather than relying on {@link Library#migrate} alone,
     * so a hand-edited, partially-written or third-party blob cannot silently lose
     * a photo's framing: whichever shape is present wins, and an item with neither
     * gets the defaults rather than dropping.
     *
     * <p>A flat item has no motion of its own, because motion used to be global.
     * The value it takes is whatever the item carries, or the default. A real v2
     * blob's motion arrives via migrate(), which copies Config's value onto every
     * item so no photo changes feel on upgrade.
     */
    private static SurfaceSettings homeFrom(JSONObject o) {
        JSONObject nested = o.optJSONObject("surfaces");
        if (nested != null) {
            JSONObject home = nested.optJSONObject(Surface.HOME.key);
            if (home != null) return SurfaceSettings.fromJson(home);
        }
        // Any flat key at all, not just these three: a blob carrying only pan_x was
        // falling through to the defaults and quietly losing the user's framing.
        if (o.has("intensity") || o.has("zoom") || o.has("orbit_x") || o.has("origin_x")
                || o.has("pan_x") || o.has("pan_y") || o.has("motion")
                || o.has("light_azimuth") || o.has("light_elevation")) {
            // "origin" was called "orbit" in the flat shape; both spellings are read
            // so a blob written by either version keeps the user's framing.
            float ox = (float) o.optDouble("origin_x", 0d);
            float oy = (float) o.optDouble("origin_y", 0d);
            if (!o.has("origin_x")) ox = (float) o.optDouble("orbit_x", SurfaceSettings.DEFAULT_ORIGIN);
            if (!o.has("origin_y")) oy = (float) o.optDouble("orbit_y", SurfaceSettings.DEFAULT_ORIGIN);
            return new SurfaceSettings(
                    o.optInt("intensity", SurfaceSettings.DEFAULT_DEPTH),
                    o.optInt("zoom", SurfaceSettings.DEFAULT_ZOOM),
                    o.optInt("motion", SurfaceSettings.DEFAULT_MOTION),
                    ox, oy,
                    (float) o.optDouble("pan_x", SurfaceSettings.DEFAULT_PAN),
                    (float) o.optDouble("pan_y", SurfaceSettings.DEFAULT_PAN),
                    o.optInt("brightness", SurfaceSettings.DEFAULT_ADJUST),
                    o.optInt("shadow", SurfaceSettings.DEFAULT_ADJUST),
                    (float) o.optDouble("light_azimuth", SurfaceSettings.DEFAULT_LIGHT_AZIMUTH),
                    (float) o.optDouble("light_elevation", SurfaceSettings.DEFAULT_LIGHT_ELEVATION));
        }
        return SurfaceSettings.defaults();
    }

    /**
     * The lock override, or null for "the same as home". Absent means inherit,
     * which is the default and the only state a new photo needs.
     */
    private static SurfaceSettings lockFrom(JSONObject o) {
        JSONObject nested = o.optJSONObject("surfaces");
        if (nested == null) return null;
        JSONObject lock = nested.optJSONObject(Surface.LOCK.key);
        return lock == null ? null : SurfaceSettings.fromJson(lock);
    }

    static LibraryItem fromJson(JSONObject o) {
        if (o == null) return null;
        Object rawId = o.opt("id");
        if (!(rawId instanceof String)) return null;
        String id = (String) rawId;
        String img = normaliseName(str(o, "image"));
        // No image means nothing to render and nothing to resolve -- UNLESS this
        // is the reserved bundled item, which reads from res/raw and so has no
        // file by design. This is a PARSE exemption and deliberately not a
        // resurrection: it keeps a row the blob already carries (so a rename or
        // a settings edit survives the next load) and adds nothing on its own.
        // The demo is deletable like any other row; what seeds it is
        // Library.withBundled, and only into an empty library.
        if (img == null && !Library.BUNDLED_ID.equals(id)) return null;
        try {
            SurfaceSettings home = homeFrom(o);
            // Both surfaces as resolved values, not nine loose numbers: the loose
            // constructor this used to call defaulted anything it was not given,
            // which is how a parse can quietly drop a value a future build added.
            return new LibraryItem(id, img,
                    str(o, "depth"),
                    str(o, "label"),
                    o.optLong("added_at", 0L),
                    o.optLong("last_used_at", 0L),
                    home,
                    lockFrom(o));
        } catch (IllegalArgumentException e) {
            return null; // a bad id; see the constructor
        }
    }

    @Override
    public String toString() {
        return "LibraryItem{" + id + " " + imageName + (depthName != null ? " +depth" : "")
                + " label=" + displayName() + " added=" + addedAt + " used=" + lastUsedAt
                + " home=" + home + (lock == null ? "" : " lock=" + lock) + "}";
    }
}
