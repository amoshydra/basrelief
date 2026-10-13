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
 * <p><b>A clip is this same row with {@link #video} set</b>, and that is deliberate
 * rather than a shortcut: a {@code LibraryItem} is a photograph, a clip is a
 * photograph plus three streams, and every render-value rule here is written about
 * photographs. Adding three loose name fields would also have made the medium
 * reachable only through a second container, and the index has to be the <b>one</b>
 * place that says what the selected row is. See {@link #video}.
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

    /**
     * Bare filename in filesDir of a supplied normal map, or null when there is
     * none.
     *
     * <p><b>Shading is separate from displacement, and this is the file that
     * supplies it.</b> Null does not mean "derive one" — there is deliberately no
     * derived-normal path any more — it means the photo renders <b>flat lit</b>:
     * displaced correctly by its depth, but lit as a flat plane because no normal
     * map says which way each cell faces. A photo can therefore have a map and no
     * shading, which is a legitimate state the sheet reports rather than a missing
     * asset quietly worked around.
     *
     * <p>Scoped to {@link #depthName}: a normal predicts the surface for one depth
     * map, so any write that replaces the depth must clear this (see
     * {@link #withDepth}), and the only writer that sets both is
     * {@link #withDepthAndNormal}. A stale normal — shading that disagrees with the
     * geometry — is worse than no shading at all, so it cannot be produced by a code
     * path.
     */
    public final String normalName;

    /**
     * The clip's three tracks, or null when this row is an ordinary photograph.
     *
     * <p><b>One nullable reference to a separate value, not three more fields.</b>
     * {@link VideoTracks} holds the colour, depth and normal clips and holds the
     * invariant that makes them a unit — colour without depth renders flat with no
     * relief and no error — which three loose {@code String}s could not, because any
     * of the three could be null and every writer would have to remember.
     *
     * <p><b>The poster frame stays {@link #imageName}.</b> §3 step 8 of
     * {@code docs/VIDEO-PLAN.md} makes the poster "the still shown in the gallery,
     * and the fallback if the video cannot decode", so keeping it on the photograph
     * is what lets a clip degrade to a photograph on the spot
     * ({@link VideoSource.Kind#POSTER}) rather than becoming a row with no image,
     * which {@link #fromJson} would drop and which would take the user's photo with
     * it.
     *
     * <p><b>A row is still an ordinary photograph for every other rule in this
     * class.</b> Its six render values are per-surface settings and know nothing
     * about the medium; {@link #hasDepth()} still asks about the <i>still</i> files,
     * because the question "where does this row's depth come from" has exactly one
     * answer and {@link DepthSource} is where it lives. A row that names clips
     * therefore needs no special case anywhere in the index, in {@link Library}, or
     * in {@link com.amoshydra.basrelief.core.render.Framing} — which is what keeps
     * <b>the index the single owner of the selection</b>, since nothing has to be
     * resolved through a second container to tell the two apart.
     */
    public final VideoTracks video;

    /**
     * The camera's focal length in <b>pixels of {@link #imageName}</b>, or 0 for
     * unknown.
     *
     * <p><b>One number, because MoGe's intrinsics are one number.</b> The stream
     * node infers with {@code force_projection=True}, so the point map it returns
     * is the depth unprojected through its own camera, and those intrinsics are
     * square-pixel and centred on (0.5, 0.5) by construction. The whole camera is
     * therefore {@code X/Z = (u - 0.5)/(f/W)}, {@code Y/Z = (v - 0.5)/(f/H)} with
     * the same {@code f} -- measured against a real geometry packet, reproducing
     * the point map to 4e-6 relative. The 3D (splat) render mode is the reader;
     * the relief does not need it.
     *
     * <p><b>Belongs to the colour, not to the depth.</b> It is a property of the
     * photograph the run saw, so a writer that replaces the image or attaches new
     * files must say what happens to it ({@link #withFilesAndVideo} takes it as an
     * argument) and a writer that only replaces the depth map carries it, because
     * the colour -- and therefore the camera -- has not changed.
     *
     * <p><b>0 is "unknown" and is omitted from the blob</b>, the same rule
     * {@code normal} and {@code video} follow: a library this build never touched
     * stays byte-identical, which is why this needed no schema version. Every row
     * written before this field existed has 0, and the renderer falls back to a
     * 50-degree default rather than refusing to draw -- a window claims nothing,
     * but a look must not vanish.
     */
    public final float focal;

    /**
     * The Gaussian splat cloud's filename, or null when this row has none.
     *
     * <p><b>A second medium on the row, and deliberately not a second row.</b>
     * The index owns the selection and only the index, so a splat is one more
     * nullable filename on the photograph — exactly the shape {@link #normalName}
     * and {@link #video} already have — rather than a separate container that
     * could disagree with the pointer. A row with a splat is still a photograph
     * for every other rule in this class: the same six per-surface settings, the
     * same {@code settings(Surface)} read, and {@code hasDepth()} still asks about
     * the still files, because the still is the poster and the fallback.
     *
     * <p><b>A bare filename, never a path</b>, for the reason every name here is:
     * with {@code allowBackup="true"} a stored absolute path survives a restore
     * and names the previous device's {@code filesDir}, so the splat would not
     * degrade to the relief — it would be a row that renders nothing.
     *
     * <p><b>0-or-absent in the blob.</b> Written only when present, so a library
     * of ordinary photographs is byte-identical to one this build never touched —
     * the {@code normal}/{@code video}/{@code focal} argument, and why this needed
     * no schema bump either. Every mutator carries it, because a splat is exactly
     * the shape of value that was added after the writers existed: the
     * {@code withDepth} scar one field over.
     */
    public final String splatName;

    /**
     * The splat cloud's own lens, as the dimensionless ratio {@code focal / height},
     * or 0 for unknown.
     *
     * <p><b>Why the cloud needs a lens of its own, and why this is not
     * {@link #focal}.</b> A Gaussian's screen size is proportional to the focal it
     * was reconstructed through, and each {@code .splat} was built at its own: the
     * node reports it in the sidecar ({@code focal}, {@code height}) and the
     * renderer draws the cloud through the source camera that focal implies. The
     * {@link #focal} beside it is a <b>different</b> number in a <b>different</b>
     * unit — pixels of the colour image, measured by a MoGe run — and it belongs to
     * the relief's depth scale and, for a clip, to the uploaded video frame. A
     * cloud is built from the poster still, so its lens can be a different ratio
     * from the clip's; folding the two into one number would let the clip path and
     * the cloud path overwrite each other's units. {@link Splat#focalOverHeight}
     * owns the derivation and {@link Splat#fovyForFocalOverHeight} the conversion
     * to the camera's field of view.
     *
     * <p><b>0 is "unknown" and is omitted from the blob</b>, the rule
     * {@code normal}, {@code video}, {@code focal}, {@code splat} and
     * {@code render} all follow, so a library this build never touched stays
     * byte-identical and this needed no schema bump. Every cloud generated before
     * this field existed has 0, and the renderer falls back to the demo cloud's
     * hard-coded lens rather than refusing to draw — so those clouds keep exactly
     * the look they have, and only a regeneration (which reads the sidecar) gives
     * a row its own.
     */
    public final float splatLens;

    /**
     * How this row is drawn: the relief, or the splat cloud.
     *
     * <p><b>A render value like Depth and Motion, and the item is its home.</b> It
     * used to be one global {@code render_3d} pref, so a gallery drew every
     * photograph the same way whichever the single switch last said — a global
     * render value is the wrong home, which is why the pref is now only the
     * migration source (see {@link Library}'s v3 to v4 migration, the
     * Motion-in-{@code Config} precedent one version later).
     *
     * <p><b>SPLAT is a request, not a fact about what is on screen.</b> A row can
     * ask for the cloud and have none yet — that is the "press Splat, the offer
     * follows" flow — and it renders its relief until one exists. Whether the cloud
     * is actually drawn is {@link SplatSource}'s answer plus the renderer's own
     * on-screen identity, the same split its siblings make.
     *
     * <p>Stored as {@link RenderMode#key()} and written only when it is not
     * {@link RenderMode#RELIEF}, so a library of ordinary photographs is
     * byte-identical to one this build never touched — the {@code normal}/{@code video}/
     * {@code focal} argument, and why this needed no schema bump of its own (the
     * v4 bump is for the migration, not the field).
     */
    public final RenderMode renderMode;

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
     * Whether this photo is hidden from the live gallery without being deleted.
     *
     * <p><b>One boolean, not a second container and not a timestamp.</b> Archive
     * hides a row from the gallery while leaving its file in {@code filesDir};
     * restore is the same bit off. There is deliberately no {@code archivedAt}:
     * no sort order reads one, and a value nothing reads is the
     * {@code powerSave} trap the project already paid for. The row is still an
     * ordinary row in {@link Library#items} and still resolves — archiving the
     * active photo deliberately leaves it on the wallpaper.
     *
     * <p>Default false, so an old v3 blob that has no {@code archived} key parses
     * as live. It is not a new schema: the key is written only when true, so a
     * library with no archived photos is byte-identical to one this build never
     * touched.
     */
    private final boolean archived;

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
                        SurfaceSettings.DEFAULT_LIGHT_ELEVATION,
                        SurfaceSettings.DEFAULT_SPEED,
                        SurfaceSettings.DEFAULT_SPLAT_DEPTH,
                        SurfaceSettings.DEFAULT_SPLAT_EPS2D,
                        SurfaceSettings.DEFAULT_SPLAT_BLUR,
                        SurfaceSettings.DEFAULT_SPLAT_SCALE),
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
        this(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lockSettings, false, null, null, 0f, 0f, null, RenderMode.RELIEF);
    }

    private LibraryItem(String id, String imageName, String depthName,
                        String normalName, String label, long addedAt, long lastUsedAt) {
        this(id, imageName, depthName, label, addedAt, lastUsedAt,
                SurfaceSettings.defaults(), null, false, normalName, null, 0f, 0f, null,
                RenderMode.RELIEF);
    }

    private LibraryItem(String id, String imageName, String depthName, String label,
                        long addedAt, long lastUsedAt,
                        SurfaceSettings home, SurfaceSettings lockSettings,
                        boolean archived, String normalName) {
        this(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lockSettings, archived, normalName, null, 0f, 0f, null,
                RenderMode.RELIEF);
    }

    /**
     * The one constructor every write path funnels into.
     *
     * <p>It takes all thirteen stored fields, so a caller that has to change one of
     * them has to name the other twelve -- and that is the {@link #copy} discipline
     * made mechanical. The {@link VideoTracks} reference rides here for the same
     * reason it is not a loose field: every mutator below names it, so a writer
     * written before clips existed cannot silently drop a user's video the way
     * {@code withDepth} silently reset Motion, brightness and shadow. The
     * {@link #focal} was added the same way, one field later, and is the reason a
     * setter is not offered for it: a writer either names it or compiles against
     * the constructor that cannot. {@link #splatLens} is the same shape once more,
     * which is why it rides here rather than behind a setter a cloud write could
     * forget.
     */
    private LibraryItem(String id, String imageName, String depthName, String label,
                        long addedAt, long lastUsedAt,
                        SurfaceSettings home, SurfaceSettings lockSettings,
                        boolean archived, String normalName, VideoTracks video,
                        float focal, float splatLens, String splatName, RenderMode renderMode) {
        String img = normaliseName(imageName);
        if (!isValidId(id)) throw new IllegalArgumentException("bad item id: " + id);
        // Every item must name a file, and the demo is no exception: the seed
        // copies res/raw/image.png into filesDir under the name its own id derives,
        // so it is stored exactly like a photo the user imported.
        //
        // The single remaining null case is the reserved id, which a blob written
        // before that migration carries and which is what a failed copy leaves
        // behind. It is read-only degradation -- the renderer falls back to the
        // packaged pair -- and it is gated on the id rather than relaxed
        // generally, so no user item can be given a null name by accident. The id
        // is not a delete guard: see Library.BUNDLED_ID.
        if (img == null && !Library.BUNDLED_ID.equals(id)) {
            throw new IllegalArgumentException("bad image name: " + imageName);
        }
        this.id = id;
        this.imageName = img;
        this.depthName = normaliseName(depthName);
        // Same bare-filename reduction as the image and the depth: a restore onto
        // another device must not carry a path, and a corrupt blob's ../ must not
        // escape filesDir. Not gated on depthName: a normal with no depth is inert
        // (see hasSuppliedNormals) and dropping it here would lose a value a repair
        // could still pair with a depth later.
        this.normalName = normaliseName(normalName);
        // Carried as given, never rebuilt: VideoTracks has already reduced all
        // three of its names to basenames and has already refused a half-written
        // clip, so re-normalising here would be a second place that could disagree
        // about what a clip is.
        this.video = video;
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
        this.archived = archived;
        // The focal is a length in pixels of the image, so 0 is "unknown" and is
        // the only non-positive value that means anything. A corrupt blob's NaN
        // or -3 is clamped rather than carried: a negative focal inverts the
        // unprojection it drives, and a NaN one poisons every vertex it touches.
        // Clamped here, in the one constructor, so no writer can store one.
        this.focal = (Float.isFinite(focal) && focal > 0f) ? focal : 0f;
        // The cloud's lens is the same shape of number: a dimensionless ratio, so
        // 0 is "unknown" and is the only non-positive value that means anything. A
        // corrupt blob's NaN or -3 is clamped here, in the one constructor, so no
        // writer can store one -- the rule the focal above already follows, and for
        // the same reason: a negative ratio inverts the field of view it implies.
        this.splatLens = (Float.isFinite(splatLens) && splatLens > 0f) ? splatLens : 0f;
        // The splat is reduced to a basename like every other name, and for the
        // same restore reason. Not gated on anything: a splat with no still files
        // is inert, and dropping it here would lose a value a later repair could
        // still pair with a photograph.
        this.splatName = normaliseName(splatName);
        // Null from a hand-written blob, or a value this build does not know, is the
        // relief: a render value read on the path to drawing must degrade to
        // something that renders. See RenderMode.parse.
        this.renderMode = renderMode == null ? RenderMode.RELIEF : renderMode;
    }

    /** Convenience for the common case: an image just added, no label. */
    public static LibraryItem of(String id, String imageName, String depthName, long addedAt) {
        return new LibraryItem(id, imageName, depthName, null, addedAt, 0L);
    }

    /**
     * The bundled marble bust: the demo every install starts with.
     *
     * <p><b>Named by the ordinary per-id scheme, like every other item.</b> It used
     * to be built with both names null, and a null image name was the renderer's
     * signal to read {@code res/raw} — so the one row in the gallery with no file
     * was the one row the depth panel refused, the map section could not offer to
     * regenerate, and a replaced map had nowhere to be written. The seed now copies
     * {@code res/raw/image.png} and {@code res/raw/depth.png} into {@code filesDir}
     * under {@code item-bundled.png} and {@code item-bundled-depth.png} — the very
     * names {@link #imageFileName}/{@link #depthFileName} derive from any id —
     * and this takes them.
     *
     * <p>Nulls are still legal here and only here, for two states that are not
     * "the demo" but "the demo's files are not here": a blob written before the
     * migration, and a copy that failed because storage did. Both render the
     * packaged pair, and both are repaired by the next successful copy — see
     * {@link Library#withBundledFiles}. The renderer treats a null image path as
     * "use the packaged pair" only when the depth path is null too, so a null image
     * beside a real depth map cannot render the demo's photo against somebody
     * else's map.
     *
     * @param imageName the copied image's bare filename, or null when the copy
     *                  did not happen
     * @param depthName the copied map's bare filename, or null
     */
    public static LibraryItem bundled(String imageName, String depthName) {
        return bundled(imageName, depthName, null);
    }

    /**
     * The bundled demo, with the shipped normal map.
     *
     * @param normalName the copied normal map's bare filename, or null when the
     *                   asset was not copied. Null is a deliberate state: the demo
     *                   then renders flat lit, which the sheet reports, rather than
     *                   inventing a normal nothing on disk backs.
     */
    public static LibraryItem bundled(String imageName, String depthName, String normalName) {
        return bundled(imageName, depthName, normalName, null);
    }

    /**
     * The bundled demo, with the shipped normal and splat cloud.
     *
     * @param splatName the copied cloud's bare filename, or null when the asset was
     *                  not copied. Null is a deliberate state: the demo then renders
     *                  its relief (or the Stage-0 card grid) rather than claiming a
     *                  cloud that is not on disk.
     */
    public static LibraryItem bundled(String imageName, String depthName, String normalName,
                                      String splatName) {
        LibraryItem demo = new LibraryItem(Library.BUNDLED_ID, imageName, depthName, normalName,
                "Marble bust", 0L, 0L);
        return splatName == null ? demo : demo.withSplat(splatName);
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

    /** The normal filename to write for a new item. See {@link #imageFileName}. */
    public static String normalFileName(String id) {
        return requireValidId(id, "item-" + id + "-normal.png");
    }

    /**
     * The colour clip's filename. See {@link #imageFileName} for why it derives
     * from the id, and {@code docs/VIDEO-PLAN.md} §2 for the format.
     *
     * <p><b>.mp4 beside .png, not instead of it.</b> §3 step 8: the poster frame is
     * a still of its own, and it is what the gallery shows and what renders if the
     * clip cannot decode. So the two media types live side by side under one id
     * rather than competing for one name, which also means no extension collision
     * can make a clip overwrite a poster.
     */
    public static String videoFileName(String id) {
        return requireValidId(id, "item-" + id + ".mp4");
    }

    /** The depth clip's filename. See {@link #videoFileName}. */
    public static String videoDepthFileName(String id) {
        return requireValidId(id, "item-" + id + "-depth.mp4");
    }

    /** The normal clip's filename. See {@link #videoFileName}. */
    public static String videoNormalFileName(String id) {
        return requireValidId(id, "item-" + id + "-normal.mp4");
    }

    /**
     * The splat cloud's filename for a new item. See {@link #imageFileName} for
     * why it derives from the id.
     *
     * <p>{@code .splat} beside the stills, not instead of them: the poster frame
     * stays {@link #imageName}, so a row whose cloud is missing or whose 3D switch
     * is off degrades to the relief rather than to nothing — the same relationship
     * {@link #videoFileName} has to the still.
     */
    public static String splatFileName(String id) {
        return requireValidId(id, "item-" + id + ".splat");
    }

    /**
     * The node's re-encoded colour clip's filename. See {@link #videoFileName}.
     *
     * <p><b>A name of its own, because the remux must stay the upload.</b> When a
     * source exceeds the playback caps the node writes a colour at those caps, and
     * that is what the renderer plays -- but the remux ({@link #videoFileName}) is
     * still the range's lossless sample copy, the plan the import checks against
     * and the file a Regenerate uploads. Overwriting it with the downscaled colour
     * would cap every later regeneration twice, so the node's colour lands here and
     * {@link VideoTracks#color} names whichever file the run produced. Every row
     * written before node colours existed already names the remux, which is why
     * this needed no migration.
     */
    public static String videoColorFileName(String id) {
        return requireValidId(id, "item-" + id + "-color.mp4");
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
     * Whether this item can produce <b>shaded</b> relief: it has a normal map
     * for the depth map it renders.
     *
     * <p><b>Both are required, and that is the point.</b> A normal with no depth
     * is inert — there is no geometry for it to shade — so it must not be reported
     * as present. And a depth with no normal is the flat-lit state, not a shaded
     * one. This is the one derivation, so the sheet, the status line and the
     * renderer cannot disagree about which of the three states a photograph is in.
     * See {@link NormalSource}, which is the resolved form callers should use.
     */
    public boolean hasSuppliedNormals() {
        return normalName != null && depthName != null;
    }

    /**
     * Whether this row names a Gaussian splat cloud. One question, one answer,
     * on the row itself — the same rule {@link #isVideo} follows, and for the
     * same reason: the index owns the selection, so a reader holding the row must
     * be able to ask without a second container.
     *
     * <p>What this does <b>not</b> say: whether the cloud is here, or whether the
     * renderer will use it. The file read is {@code PrefFiles.resolve}'s answer
     * and the 3D switch is the renderer's, exactly as a clip's playability is
     * {@code VideoSource}'s.
     */
    public boolean hasSplat() {
        return splatName != null;
    }

    /**
     * Whether this row names a clip. One question, one answer, on the row itself.
     *
     * <p><b>Deliberately on the item and not in a second container.</b> The index
     * owns the selection and only the index, so the gallery, the studio and the
     * wallpaper have to be able to ask this <i>of the row they already hold</i>.
     * A separate list of clips would mean the selection could name a photograph
     * while a second structure said it was a video — and the disagreement is
     * invisible until the wallpaper shows the wrong medium on the user's home
     * screen.
     *
     * <p>What this does <b>not</b> say: whether the clips are here. That is a file
     * read, and {@link VideoSource} owns the answer because it owns the window
     * around it.
     */
    public boolean isVideo() {
        return video != null;
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
                lockSurface ? next : lock,
                archived, normalName, video, focal, splatLens, splatName, renderMode);
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
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /** The Zoom slider, 25..200, on one surface. */
    public LibraryItem withZoom(Surface surface, int v) {
        return editSettings(surface, s -> s.with(s.depth, v, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /**
       * The Motion slider, 0..100, on one surface. It moved here from
       * {@link Config}, where it was global.
       */
    public LibraryItem withMotion(Surface surface, int v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, v, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /**
     * The Speed slider, 25..200, on one surface. <b>Clips only</b> -- a photograph
     * has no rate, so the row that edits this is shown for clips alone, but the
     * value is stored on every item the same way Motion is, because a row that gains
     * clips later must not also gain a second storage rule.
     */
    public LibraryItem withSpeed(Surface surface, int v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, v,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /** Origin, a fraction of the plane, on one surface. */
    public LibraryItem withOrigin(Surface surface, float x, float y) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, x, y,
                s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /** Pan, in PIXELS, on one surface. Unclamped; see the constructor's note. */
    public LibraryItem withPan(Surface surface, float x, float y) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion,
                s.originX, s.originY, x, y, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /** Brightness and shadow, -100..100, on one surface. Render-time only. */
    public LibraryItem withAdjust(Surface surface, int brightness, int shadow) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion,
                s.originX, s.originY, s.panX, s.panY, brightness, shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /**
     * The light's angles, on one surface. See {@link LightDirection} for the
     * convention: azimuth runs counter-clockwise from the right and wraps,
     * elevation is 0 at grazing and 90 head-on.
     */
    public LibraryItem withLight(Surface surface, float azimuthDegrees, float elevationDegrees) {
        return editSettings(surface, s -> s.withLight(azimuthDegrees, elevationDegrees));
    }

    // --------------------------------------------------- the splat look rows
    //
    // Four render values, each per photo and per surface like Depth and Motion.
    // They were global prefs; the migration in {@link Library} seeds them once from
    // the old keys, and these are the writes the sheet's four rows use. Each
    // carries the other three, so nudging Size cannot reset Depth -- the
    // {@code withDepth} scar this file exists to prevent.

    /** The splat cloud's Depth, a multiplier on the reconstructed depth. */
    public LibraryItem withSplatDepth(Surface surface, float v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                v, s.splatEps2d, s.splatBlur, s.splatScale));
    }

    /** The splat cloud's Size: the 2D floor as a fraction of a splat's cell. */
    public LibraryItem withSplatSize(Surface surface, float v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, v, s.splatBlur, s.splatScale));
    }

    /** The splat cloud's Blur: a post-blur radius in render-target pixels. */
    public LibraryItem withSplatBlur(Surface surface, float v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, v, s.splatScale));
    }

    /** The splat cloud's Resolution: the fraction of the surface it is drawn at. */
    public LibraryItem withSplatScale(Surface surface, float v) {
        return editSettings(surface, s -> s.with(s.depth, s.zoom, s.motion, s.originX,
                s.originY, s.panX, s.panY, s.brightness, s.shadow,
                s.lightAzimuth, s.lightElevation, s.speed,
                s.splatDepth, s.splatEps2d, s.splatBlur, v));
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

    /**
     * Replace the depth map, and <b>clear the supplied normal</b>.
     *
     * <p>A normal predicts the surface of one depth map, so a new depth makes the
     * old normal a lie: shading that describes a shape the geometry no longer has.
     * Clearing it is why a regenerated depth first renders flat lit and offers to
     * generate normals — a deliberate, visible state — rather than keeping a normal
     * that silently disagrees. The paired writer is {@link #withDepthAndNormal},
     * which is the only way both are set.
     */
    public LibraryItem withDepth(String depth) {
        return withDepthAndNormal(depth, null);
    }

    /**
     * Replace the depth map and its normal together.
     *
     * <p><b>The only writer that sets {@code normalName}.</b> It exists so that
     * depth and normal, which come from one MoGe run, can never be half-applied or
     * written from two derivations that disagree. A null normal is legal and means
     * "this depth has no supplied normal", which is exactly what a classical
     * (Depth-Anything) run produces; the result is then identical to
     * {@link #withDepth}.
     */
    public LibraryItem withDepthAndNormal(String depth, String normal) {
        // The focal is carried, not cleared, and that is a decision rather than an
        // omission: it describes the CAMERA of the colour image, and this writer
        // replaces the depth and its normal -- the two things a run derives from
        // that colour. A still's regenerated depth is the same photograph; a clip's
        // regenerate goes through withFilesAndVideo, which does name the focal.
        return copy(id, imageName, depth, normal, label, addedAt, lastUsedAt, video, focal,
                splatLens, splatName);
    }

    /**
     * This item's two file names replaced, and every other value carried over.
     *
     * <p>The one write that names both names together, because a half-applied pair
     * is the failure this project keeps paying for: an image with no map renders
     * flat, and a map with no image is a file nothing will ever read. It is public
     * only because the migration that gives the demo its files has to go through
     * here rather than through a loose constructor, which would default the render
     * values it is not given.
     *
     * <p>The supplied normal is cleared: a different image/depth pair is a different
     * surface, so any normal this item carried belonged to the pair being replaced.
     * Use {@link #withFiles(String, String, String)} when the new pair ships with
     * its own normal.
     */
    public LibraryItem withFiles(String image, String depth) {
        return withFiles(image, depth, null);
    }

    /**
     * This item's image, depth and normal replaced together.
     *
     * <p>For an import or a bundled repair whose normal arrives with the pair. A
     * separate two-argument form exists because most callers have no normal, and
     * naming only what you have is the point: this is the three-file paired write,
     * and the two-argument form is that write with an absent normal.
     *
     * <p><b>The clip rides along untouched.</b> That is the decision, not an
     * omission: the poster frame and the clip are one asset, and a writer that
     * swapped the poster while leaving the clips would leave the row describing two
     * different pictures — the fallback would then show a still that is not any
     * frame of the clip beside it. Replacing both is
     * {@link #withFilesAndVideo}, so "one write names everything the row points at"
     * holds for a clip as well as for a still.
     */
    public LibraryItem withFiles(String image, String depth, String normal) {
        return withFilesAndVideo(image, depth, normal, video, focal);
    }

    /**
     * This row's still files <b>and</b> its clips replaced in one write.
     *
     * <p>The paired write an import of a video needs, and the reason it is not two
     * calls. A half-applied pair is the failure this project keeps paying for, and
     * a clip makes the pair bigger rather than smaller: an import that wrote the
     * poster first and died before the clips left a row the user would see as a
     * photograph, with no error anywhere.
     *
     * <p>The focal is carried, because a caller that swaps the stills but says
     * nothing about the camera meant "the same photograph, rewritten".
     */
    public LibraryItem withFilesAndVideo(String image, String depth, String normal,
                                         VideoTracks clips) {
        return withFilesAndVideo(image, depth, normal, clips, focal);
    }

    /**
     * The same paired write, with the camera as well.
     *
     * <p><b>An import's one finish line, and the reason the focal is a parameter
     * here rather than a setter.</b> Every value on a row has exactly one writer
     * that names it; a {@code withFocal} setter would be a second write path that a
     * later writer could forget, which is the {@code withDepth} scar one field
     * over. Passing 0 stores "unknown" and is how a regenerate that could not fit a
     * focal clears the one it replaced -- the files and the number that describes
     * them arrive together, so a row cannot end up pointing at a run's depth with
     * the previous run's lens.
     */
    public LibraryItem withFilesAndVideo(String image, String depth, String normal,
                                         VideoTracks clips, float focal) {
        return copy(id, image, depth, normal, label, addedAt, lastUsedAt, clips, focal,
                splatLens, splatName);
    }

    /**
     * Attach clips to this row, or replace the ones it has. <b>The only writer of
     * {@link #video}</b>, for the reason {@link #withDepthAndNormal} is the only
     * writer of {@link #normalName}: one writer that names a whole pair is what
     * makes a pair impossible to half-apply from two derivations that disagree.
     *
     * <p><b>Null is refused rather than accepted.</b> Removing a clip is a
     * <i>delete</i>, and a delete has to remove three files; {@code :core} cannot
     * touch the filesystem, so a model that could drop the reference while the files
     * stayed would orphan them with nothing left that will ever read them. The
     * gesture belongs to the app shell, which owns the delete and can drop the row
     * or the files with it.
     *
     * @throws IllegalArgumentException if {@code clips} is null
     */
    public LibraryItem withVideo(VideoTracks clips) {
        if (clips == null) {
            throw new IllegalArgumentException("removing a clip is a delete, not an edit");
        }
        // The reserved id is the one row a clip is refused on, and for the same kind
        // of reason it is the one row allowed a null image name: its files come from
        // res/raw, which ships three PNGs and nothing else, so a clip on that row
        // names files that cannot exist. There is no import path that could put one
        // there, so the refusal is not costing anything -- it is the difference
        // between a model that can describe the demo as a clip and one that cannot.
        if (isBundled()) {
            throw new IllegalArgumentException("the bundled demo is a still trio");
        }
        return copy(id, imageName, depthName, normalName, label, addedAt, lastUsedAt, clips, focal,
                splatLens, splatName);
    }

    /**
     * Attach a splat cloud to this row, or clear it with null.
     *
     * <p><b>Null is accepted here, unlike {@link #withVideo}.</b> A splat is an
     * optional reconstruction, not a medium the row is: a clip's removal is a
     * delete of three frame-locked files, while a splat's reference can simply be
     * dropped and the row degrades to the relief on the spot. The file cleanup is
     * the app shell's, the same split {@link #withVideo} documents.
     *
     * <p><b>A caller that replaces the COLOUR image must clear this</b>, because
     * the cloud is a reconstruction of that image: a splat of one photograph
     * rendered under another is the {@code withDepth} scar in three dimensions.
     * The file writers carry it rather than clearing it themselves because
     * {@link #withFiles} is also the bundled repair, which rewrites the demo's own
     * files and must not drop the demo's cloud.
     *
     * <p><b>The lens is carried, not cleared</b> — the same choice
     * {@link #withFiles} makes for {@link #focal} — because this form says nothing
     * about the cloud's camera and a caller attaching a cloud whose lens it does not
     * know must not erase one already measured. {@link #withSplat(String, float)} is
     * the form that sets both, and it is the one an import uses.
     */
    public LibraryItem withSplat(String splat) {
        return copy(id, imageName, depthName, normalName, label, addedAt, lastUsedAt, video,
                focal, splatLens, splat);
    }

    /**
     * Attach a splat cloud <b>and its measured lens</b> in one write.
     *
     * <p><b>The import's one finish line, and the reason the lens is a parameter
     * here rather than a setter.</b> The cloud and the number that describes how it
     * was reconstructed arrive together from the same sidecar, so a writer that set
     * the filename without the lens would be the {@code withDepth} scar one field
     * over: a cloud drawn through the previous cloud's camera. Passing 0 stores
     * "unknown" and is how a run whose sidecar carried no focal leaves the row to
     * the demo lens — the same degradation a cloud written before this field existed
     * gets, and the one that keeps those clouds' look unchanged.
     */
    public LibraryItem withSplat(String splat, float lens) {
        return copy(id, imageName, depthName, normalName, label, addedAt, lastUsedAt, video,
                focal, lens, splat);
    }

    /**
     * Set how this row is drawn. <b>The only writer of {@link #renderMode}.</b>
     *
     * <p>A null mode is refused rather than read as the relief: the value is a
     * choice the sheet makes, and a caller that has no mode has nothing to say
     * about it — the {@code withVideo} refusal, one field over. The relief is
     * spelled {@link RenderMode#RELIEF}.
     */
    public LibraryItem withRenderMode(RenderMode mode) {
        if (mode == null) throw new IllegalArgumentException("a render mode is required");
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lock, archived, normalName, video, focal, splatLens, splatName, mode);
    }

    /** Whether this row asks for the cloud. See {@link #renderMode}. */
    public boolean isSplatMode() {
        return renderMode.isSplat();
    }

    public LibraryItem withLabel(String newLabel) {
        return copy(id, imageName, depthName, normalName, newLabel, addedAt, lastUsedAt,
                video, focal, splatLens, splatName);
    }

    /** Record that this item is now the wallpaper. */
    public LibraryItem withLastUsedAt(long when) {
        return copy(id, imageName, depthName, normalName, label, addedAt, when, video, focal,
                splatLens, splatName);
    }

    /**
     * Hide this photo from the live gallery, or bring it back.
     *
     * <p>An identity-only edit in the {@link #copy} sense: every render value and
     * the lock override carry over untouched, and so does every other identity
     * field. It cannot touch the library's selection — that pointer lives on
     * {@link Library} and this row has no copy of it — which is exactly what
     * makes archiving the active photo legal and reversible.
     */
    public LibraryItem withArchived(boolean on) {
        if (this.archived == on) return this;
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lock, on, normalName, video, focal, splatLens, splatName, renderMode);
    }

    /** Whether this photo is hidden from the live gallery. See {@link #archived}. */
    public boolean isArchived() {
        return archived;
    }

    /**
     * A copy of this item with only its identity fields replaced; every render
     * value carries over.
     *
     * <p>This exists because the alternative is a write site naming six values it
     * did not mean to change, and the short constructor's defaults quietly
     * resetting the other three. Three call sites had exactly that waiting in
     * them.
     *
     * <p><b>The focal is a parameter and not a field read here</b> for the same
     * reason the three names are: {@link #withFilesAndVideo} has to be able to
     * write the focal along with the files it belongs to, and a defaulted field
     * would make that the one writer with no way to say it. {@link #splatLens} is
     * the same again: {@link #withSplat(String, float)} writes it with the cloud it
     * describes, and a field read here would leave that writer with no way to say it.
     */
    private LibraryItem copy(String id, String imageName, String depthName, String normalName,
                             String label, long addedAt, long lastUsedAt,
                             VideoTracks video, float focal, float splatLens, String splatName) {
        return new LibraryItem(id, imageName, depthName, label, addedAt, lastUsedAt,
                home, lock, archived, normalName, video, focal, splatLens, splatName, renderMode);
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
            // Written only when there is one, so a library of flat-lit photos is
            // byte-identical to one an older build wrote -- which is why this needed
            // no version bump. An old build reading a new blob ignores the unknown
            // key; a new build reading an old blob reads null and renders flat.
            if (normalName != null) o.put("normal", normalName);
            // One key, one write, and absent for every photograph in the gallery --
            // so a library of stills is byte-identical to one this build never
            // touched, which is why clips needed no version bump either. The same
            // argument the normal and the archived flag already make.
            if (video != null) o.put("video", new JSONObject(video.toJson()));
            // Written only when known, so a library with no focal is byte-identical
            // to one this build never touched -- the normal/video/archived argument
            // again, and why this needed no version bump either. 0 means UNKNOWN and
            // is never stored, because a stored 0 and an absent key would be two
            // spellings of one state.
            // The cast is load-bearing: :core compiles against Maven's org.json,
            // which has put(String, float), while Android's platform org.json does
            // NOT. A bare float argument therefore links here and throws
            // NoSuchMethodError on the device -- on the index-writer thread, so it
            // takes the app down on any write. Every float this class stores goes
            // through (double), and scripts/check-json-puts.mjs enforces it.
            if (focal > 0f) o.put("focal", (double) focal);
            // The cloud's lens, only when known: a row whose cloud predates this
            // field (or whose run reported none) writes nothing and draws at the
            // demo lens, which is what keeps those clouds' look unchanged. The same
            // 0-is-absent argument the focal above makes, one unit over: a stored 0
            // and an absent key would be two spellings of one state.
            if (splatLens > 0f) o.put("splat_lens", (double) splatLens);
            // The splat, only when there is one: a library of ordinary
            // photographs stays byte-identical to one this build never touched,
            // which is why this needed no schema bump. The same argument the
            // normal, video, archived flag and focal already make.
            if (splatName != null) o.put("splat", splatName);
            // Only when it is not the relief, so a library of ordinary photographs
            // stays byte-identical to one this build never touched -- the same
            // argument the normal, video, archived flag, focal and splat make.
            if (renderMode != RenderMode.RELIEF) o.put("render", renderMode.key());
            if (label != null) o.put("label", label);
            // Written only when true, so a library with nothing archived stays
            // byte-identical to one an older build wrote. Absent means live --
            // see fromJson's optBoolean default.
            if (archived) o.put("archived", true);
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
                    (float) o.optDouble("light_elevation", SurfaceSettings.DEFAULT_LIGHT_ELEVATION),
                    // A flat blob predates the field, so absent is 1x -- and this
                    // legacy shape is read once and rewritten into the settings form,
                    // so the default lands on disk rather than being re-derived.
                    o.optInt("speed", SurfaceSettings.DEFAULT_SPEED),
                    // The four splat-look values postdate the flat shape entirely, so
                    // they are always the defaults here; the migration in Library is
                    // what seeds them from the old global prefs.
                    SurfaceSettings.DEFAULT_SPLAT_DEPTH,
                    SurfaceSettings.DEFAULT_SPLAT_EPS2D,
                    SurfaceSettings.DEFAULT_SPLAT_BLUR,
                    SurfaceSettings.DEFAULT_SPLAT_SCALE);
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
        // No image means nothing to render and nothing to resolve -- UNLESS this is
        // the reserved bundled id. A row stored before the demo got a file carries
        // no image name, and dropping it would delete the demo from every upgrading
        // user's gallery. This is a PARSE exemption and deliberately not a
        // resurrection: it keeps a row the blob already carries and adds nothing on
        // its own. The copy that fixes it is Library.withBundledFiles, and the demo
        // is deletable like any other row; what seeds it is Library.withBundled, and
        // only into an empty library.
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
                    lockFrom(o),
                    // Absent means live. An old v3 blob has no key here and
                    // parses unchanged, which is the whole reason this feature
                    // needed no version bump.
                    o.optBoolean("archived", false),
                    // Read through str(), so JSON null and the literal "null" both
                    // become null and heal on the next write. Absent is null, which
                    // is the correct degradation: flat lit, never a derived normal.
                    str(o, "normal"),
                    // A clip is parsed, never guessed. An absent key is a photograph,
                    // and so is a half-written clip object -- VideoTracks refuses one
                    // -- so a truncated or hand-edited blob degrades to the poster
                    // frame rather than to a row that claims a depth track which is
                    // not there.
                    //
                    // Ignored on the reserved id, matching withVideo's refusal: the
                    // demo's files come from res/raw and are three PNGs, so a clip on
                    // that row is a claim about files that cannot exist. The id is
                    // therefore the authority for the medium as well as for the
                    // missing image name, which is one id-gated fact rather than two.
                    Library.BUNDLED_ID.equals(id)
                            ? null
                            : VideoTracks.fromJson(o.optJSONObject("video")),
                    // 0 = unknown, and the constructor is what refuses a negative
                    // or NaN value: one place, so a hand-edited "focal": "NaN"
                    // cannot reach the vertex shader and poison a whole cloud.
                    (float) o.optDouble("focal", 0d),
                    // The cloud's lens, the same shape of read: 0 = unknown, and the
                    // constructor is what refuses a negative or NaN value so a
                    // hand-edited "splat_lens": "NaN" cannot reach the projection.
                    (float) o.optDouble("splat_lens", 0d),
                    // The splat cloud, read through str() like every other name,
                    // so JSON null and the literal "null" both become null. Absent
                    // is null, which is the correct degradation: the relief.
                    str(o, "splat"),
                    // Absent is the relief, which is what every row written before the
                    // mode existed is. RenderMode.parse also absorbs a value from a
                    // newer build rather than throwing on the path to drawing.
                    RenderMode.parse(str(o, "render")));
        } catch (IllegalArgumentException e) {
            return null; // a bad id; see the constructor
        }
    }

    @Override
    public String toString() {
        return "LibraryItem{" + id + " " + imageName + (depthName != null ? " +depth" : "")
                + (normalName != null ? " +normal" : "")
                + (splatName != null ? " +splat" : "")
                + (splatLens > 0f ? " lens=" + splatLens : "")
                + (renderMode != RenderMode.RELIEF ? " " + renderMode : "")
                + (video != null ? " " + video : "")
                + (focal > 0f ? " focal=" + focal : "")
                + " label=" + displayName() + " added=" + addedAt + " used=" + lastUsedAt
                + (archived ? " ARCHIVED" : "")
                + " home=" + home + (lock == null ? "" : " lock=" + lock) + "}";
    }
}
