package com.amoshydra.basrelief.core.store;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * A clip's own three files: colour, depth, normals.
 *
 * <p><b>Why this is a type and not three more fields on {@link LibraryItem}.</b> A
 * {@code LibraryItem} is a photograph, and every rule in the index is written about
 * photographs — its six render values, its per-surface settings, its poster. A clip
 * is a photograph <i>plus</i> three streams that are only meaningful together, and
 * the one thing that makes them a unit is an invariant no photograph has: §2 of
 * {@code docs/VIDEO-PLAN.md} says <i>"never store one without the other"</i>,
 * because colour without depth renders <b>flat, with no relief and no error</b> —
 * the same silent failure the bundled demo's trio already warns about. Three loose
 * {@code String} fields cannot hold that: any of the three can be null, and a
 * half-written triple is a state the type should make unrepresentable rather than
 * one every writer has to remember.
 *
 * <p>So the colour and the depth are <b>required by construction</b>, and there is
 * no factory that produces a clip without both. The normal track is the one file
 * whose absence is legal, and it is legal for a reason that is not an omission:
 * {@code AGENTS.md} forbids deriving shading, so a clip with no normal track is
 * <b>flat lit</b> — a deliberate, reported state.
 *
 * <p><b>Bare filenames only, never paths</b>, reduced by
 * {@link LibraryItem#normaliseName} exactly as an image, a depth map and a normal
 * map are. {@code allowBackup="true"} means an absolute path survives a restore
 * pointing at the previous device's {@code filesDir}, and a clip restored that way
 * is not a degraded clip — it is a gallery row that renders nothing at all.
 * Resolution therefore goes through {@link PrefFiles}, which accepts both forms
 * so an upgrade from a build that wrote a path does not lose the clip.
 *
 * <p><b>The poster frame is not here.</b> It is {@link LibraryItem#imageName}, and
 * that is deliberate: §3 step 8 of the plan makes the poster "the still shown in
 * the gallery, and the fallback if the video cannot decode". Keeping it on the
 * photograph rather than here is what lets a clip degrade to a photograph on the
 * spot — see {@link VideoSource.Kind#POSTER} — instead of becoming a row with no
 * image, which the index would drop.
 *
 * <p><b>Immutable</b>, like everything else in the index. Pure JVM, no Android:
 * it holds names and the arithmetic of resolving them.
 */
public final class VideoTracks {

    /**
     * The colour clip, {@code <name>.mp4}. Never null.
     *
     * <p>H.264 or HEVC in an ordinary MP4, which is what {@code MediaExtractor}
     * reads — §2's reason for choosing ordinary files over a custom container.
     */
    public final String color;

    /**
     * The per-frame depth clip, {@code <name>-depth.mp4}. Never null.
     *
     * <p><b>A track, not a file the renderer opens once.</b> §1: every frame
     * carries its own depth, so this is a second stream that must be paired frame
     * for frame with {@link #color} by a shared clock (§4). It is the reason a
     * clip is not an image with an extra map, and the reason {@link DepthSource}
     * has a kind of its own for it.
     */
    public final String depth;

    /**
     * The per-frame shading normal, {@code <name>-normal.mp4}, or null.
     *
     * <p>Null means <b>flat lit</b>: the relief displaces correctly and is lit as
     * a plane. That is the state a classical depth run produces and the state
     * {@code NormalSource} reports outright, and it is not worked around by
     * deriving a normal from the depth gradient — {@code AGENTS.md} forbids that
     * twice over, because the derivation is the banding artifact the float blur
     * exists to hide and it makes the reported state a lie.
     */
    public final String normal;

    /**
     * @throws IllegalArgumentException if colour or depth is absent or unusable.
     *         The pair invariant is enforced here rather than checked at every
     *         read site, because a check at a read site is a check somebody will
     *         not write.
     */
    private VideoTracks(String color, String depth, String normal) {
        String c = LibraryItem.normaliseName(color);
        String d = LibraryItem.normaliseName(depth);
        // "null" is rejected by normaliseName itself, which is what heals a blob
        // whose JSON null was once read back as the four-character string.
        if (c == null) throw new IllegalArgumentException("no colour clip: " + color);
        if (d == null) throw new IllegalArgumentException("no depth clip: " + depth);
        this.color = c;
        this.depth = d;
        // Not gated on depth, exactly as LibraryItem's normalName is not: dropping
        // it here would lose a value a later repair could still pair.
        this.normal = LibraryItem.normaliseName(normal);
    }

    /** A clip with no normal track: correct displacement, flat lit. */
    public static VideoTracks of(String color, String depth) {
        return new VideoTracks(color, depth, null);
    }

    /** A clip whose shading arrives with it. */
    public static VideoTracks of(String color, String depth, String normal) {
        return new VideoTracks(color, depth, normal);
    }

    /**
     * The same clip, with a normal track. The only writer of {@link #normal},
     * mirroring {@link LibraryItem#withDepthAndNormal}: one writer that names both
     * halves of a pair is how a pair stops being half-applied.
     */
    public VideoTracks withNormal(String normal) {
        return new VideoTracks(color, depth, normal);
    }

    /**
     * Whether this clip supplies its own shading.
     *
     * <p>Not the same question as {@link NormalSource}, which also asks whether
     * there is geometry to shade — for a clip the depth is required by
     * construction, so this half of the answer is already true.
     */
    public boolean hasNormalTrack() {
        return normal != null;
    }

    // ------------------------------------------------------------- resolving

    /**
     * The colour clip's file, or null when it is not there.
     *
     * <p>Through {@link PrefFiles#resolve}, so both the current bare-filename form
     * and an absolute path from a build that wrote one resolve — and a restored
     * absolute path re-resolves to this device's {@code filesDir}, which is the
     * whole reason names are stored the way they are.
     */
    public File colorFile(File filesDir) {
        return PrefFiles.resolve(filesDir, color);
    }

    /** The depth clip's file, or null. See {@link #colorFile}. */
    public File depthFile(File filesDir) {
        return PrefFiles.resolve(filesDir, depth);
    }

    /** The normal clip's file, or null — including when no normal is named. */
    public File normalFile(File filesDir) {
        return normal == null ? null : PrefFiles.resolve(filesDir, normal);
    }

    /**
     * Whether this clip can actually be played, as far as the filesystem is
     * concerned.
     *
     * <p><b>The pair again, and nothing else.</b> A colour clip with no depth clip
     * would render flat with no relief and no error, which §2 calls the failure
     * this whole format was designed to avoid, so it is not "playable" here. The
     * normal track is not required, because a clip without one is a deliberate
     * state rather than a broken one.
     *
     * <p><b>Files present is not files decodable.</b> This is the one cheap
     * question the index can answer without a decoder, and it is deliberately
     * labelled as cheap: the answer it feeds, {@link VideoSource}, has its own
     * unread state for the window between "the files are there" and "the decoder
     * produced frames", and guessing across that window is precisely the bug
     * {@link DepthSource.Kind#UNKNOWN} exists to prevent.
     */
    public boolean playable(File filesDir) {
        if (filesDir == null) return false;
        return colorFile(filesDir) != null && depthFile(filesDir) != null;
    }

    // ------------------------------------------------------------- serialise

    /**
     * The three names, as one object under one key.
     *
     * <p>One key rather than three flat ones for the same reason {@code surfaces}
     * is one object: a reader from before this feature ignores an unknown key,
     * where three new top-level keys are three more shapes it would have to
     * tolerate separately.
     */
    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("color", color);
            o.put("depth", depth);
            // Absent rather than null, so a flat-lit clip's blob is smaller and a
            // reader cannot see the string "null".
            if (normal != null) o.put("normal", normal);
        } catch (JSONException e) {
            throw new AssertionError("VideoTracks.toJson cannot fail", e);
        }
        return o.toString();
    }

    /**
     * Read the three names, or <b>null</b> when they cannot make a clip.
     *
     * <p>Never throws. A missing colour or depth, a JSON null, the literal
     * {@code "null"}, a path or a traversal all come back as "there is no clip" —
     * and "no clip" is a real, renderable state, because the row still has its
     * poster frame and reads as a photograph. That is the degradation §3 step 8
     * calls for, and it is strictly better than the alternatives: dropping the row
     * loses the user's photo, and accepting the half-written triple would describe
     * a render that cannot happen.
     *
     * <p>Package-private, like {@link LibraryItem#fromJson}: this is the parse
     * path, and it is one library row's business.
     */
    static VideoTracks fromJson(JSONObject o) {
        if (o == null || o.isNull("color") || o.isNull("depth")) return null;
        String c = LibraryItem.normaliseName(optString(o, "color"));
        String d = LibraryItem.normaliseName(optString(o, "depth"));
        if (c == null || d == null) return null;
        return new VideoTracks(c, d, optString(o, "normal"));
    }

    /**
     * A string field, or null when it is absent, JSON null, or the string "null".
     *
     * <p>Duplicated from {@link LibraryItem}'s own reader rather than shared,
     * because that one is private to a class that has no business exposing its
     * parse helper — and the behaviour it encodes (Android's {@code optString}
     * returning the four-character string for a JSON null) is documented on the
     * original, which is where a reader of this goes to find out why.
     */
    private static String optString(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String v = o.optString(key, null);
        if (v == null || v.isEmpty() || "null".equals(v)) return null;
        return v;
    }

    @Override
    public String toString() {
        return "VideoTracks{" + color + " +depth" + (normal == null ? " (flat lit)" : " +normal") + "}";
    }
}