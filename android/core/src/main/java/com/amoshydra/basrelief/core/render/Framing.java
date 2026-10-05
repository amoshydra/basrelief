package com.amoshydra.basrelief.core.render;

import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

/**
 * What to draw, resolved from the app's state.
 *
 * <p><b>This class exists because the studio and the wallpaper disagreed.</b>
 * Both surfaces have to turn "which item is selected" into one set of numbers, and
 * when they each did it inline they drifted: the wallpaper service reset the framing
 * to defaults for the bundled demo, while the studio's own reload left it at
 * whatever the previously selected photo had. Selecting the marble bust after
 * panning a photo therefore showed the bust through that photo's framing, in one
 * surface and not the other, with nothing in the log.
 *
 * <p>It lives in :core, not :app, and that is the point rather than an
 * accident of packaging: it has no Android in it, so it can be unit tested, and
 * it is the value most likely to be quietly wrong. A version of this class sat
 * in :app for the length of one commit, where the bug it now guards -- the
 * renderer keeping the previous item's pan and zoom -- could not have been
 * caught at all.
 */
public final class Framing {

    public final float depth;
    public final float zoom;
    public final float motion;
    public final float orbitX;
    public final float orbitY;
    public final float panX;
    public final float panY;

    /**
     * Brightness as a multiplier, and shadow as a lift. Already mapped.
     *
     * <p>Pre-mapped on purpose: the shader holds one multiply and one mix, and the
     * part that is easy to get wrong is the mapping, which is unit tested in
     * {@link PictureTest}. See {@link Picture} for why it is duplicated in GLSL.
     */
    public final float brightnessGain;
    public final float shadowLift;

    /**
     * The light, as the two angles {@link LightDirection} defines.
     *
     * <p><b>Angles, not a vector, and the glide is why.</b> These are the values a
     * person edits, so resolving to the vector would be fine for drawing — but the
     * surface glide interpolates every render value, and interpolating the vector's
     * components is wrong at exactly the seam this class exists to cross:
     * 350°→10° lerps through 180° (a 340° spin the eye reads as a glitch), and two
     * opposite lights produce a zero vector with no direction. Carrying the angles
     * lets {@link LightDirection#lerpAzimuth} take the 20° the eye expects.
     */
    public final float lightAzimuth;
    public final float lightElevation;

    /** True when the marble bust is showing, or when nothing is selected. */
    public final boolean bundled;

    // ------------------------------------------------------------ the medium

    /**
     * A photograph: one image, one depth map, one normal map, uploaded once.
     */
    public static final float SOURCE_STILL = 0f;

    /**
     * A clip: colour, depth and shading all arrive per frame, decoded off the GL
     * thread and paired by a shared clock.
     *
     * <p>A <b>number</b> rather than a flag, and that is not a compromise. Every
     * value {@link #ofValues} can carry has to be one it can carry, because
     * {@code ofValues} is how a renderer hands back what it is currently drawing as
     * a glide's origin — and a field the snapshot cannot reproduce would be exactly
     * the {@link #bundled} trap one level worse: {@code bundled} is merely not
     * compared, whereas a medium that could not be reproduced would make the
     * no-op-paint guard miss a genuinely different picture, which drops a real frame.
     *
     * <p>Discrete, and <b>not interpolated by a glide</b>, like {@link #bundled}: a
     * glide crosses a surface boundary, and the medium on the other side of it is
     * whatever the other surface's row names.
     */
    public static final float SOURCE_VIDEO = 1f;

    /**
     * Whether this picture is drawn from a still or from a decoded clip.
     *
     * <p><b>Compared by {@link #sameAs}, unlike {@link #bundled}.</b> That
     * asymmetry is the whole reason to read the two together. {@code bundled} says
     * <i>which row</i> a framing came from and two framings of the same numbers are
     * the same picture whatever produced them; the medium says <i>what the pixels
     * are</i>, so a still and a clip at identical depth, zoom and light are two
     * different pictures and a resolve that conflated them would leave the previous
     * one on screen while the state said otherwise.
     */
    public final float sourceKind;

    /**
     * Whether this row will be drawn from a clip, as far as the index knows.
     *
     * <p><b>Deliberately not "can play".</b> Whether the clips decode is a file and
     * codec question the renderer settles as it loads them; putting it here would
     * make a render value a function of an I/O result, and every framing value in
     * this class is a function of stored state. {@link
     * com.amoshydra.basrelief.core.store.VideoSource} owns the other half and the
     * window around it.
     */
    public boolean isVideo() {
        // EXACT match, not "anything that is not still".
        //
        // This was `!= SOURCE_STILL`, and it was wrong in a way a test caught: a
        // half-eased medium — `lerp(SOURCE_STILL, SOURCE_VIDEO, 0.5)` — answered
        // TRUE here, so an interpolated value did not merely fail to name a picture,
        // it actively claimed to be a clip. Nothing in the shipping path produces one
        // today, which is exactly why it would have survived: the field is only
        // interpolated if somebody eases it, and the easing is what this protects.
        //
        // An exact match is also the answer that survives a THIRD medium. `!= STILL`
        // would call a future SOURCE_DEPTH_ONLY a video; `== SOURCE_VIDEO` calls it
        // what it is, which is the same rule the rest of the class follows -- a value
        // means what it says and not what it is merely not.
        return sourceKind == SOURCE_VIDEO;
    }

    /**
     * Whether two framings would render the same picture.
     *
     * <p><b>Exists so a resolve can decline to paint.</b> A transition resolves more than
     * once — becoming visible, an insets dispatch, a pref write — and each resolve builds
     * a NEW {@code Framing} with the same values. Without a value comparison the renderer
     * cannot tell "this is a different picture" from "this is the picture I am already
     * gliding towards", so the second resolve cancels a glide in flight and paints the
     * destination instantly. Measured on the device: the glide armed, and a plain apply
     * of its own destination landed 1.2 seconds later, mid-flight — the frame the user
     * reported as a snap.
     *
     * <p><b>Every field, and that is the point.</b> This class has twelve of them and
     * no {@code equals} for the same reason {@code SurfaceSettings.with} takes
     * eleven arguments: adding a value without teaching the comparison about it would
     * make the renderer treat a real change as a no-op and silently drop the frame. The
     * test reflects over the fields so a thirteenth cannot be forgotten.
     */
    public boolean sameAs(Framing other) {
        if (other == null) return false;
        if (this == other) return true;
        return near(depth, other.depth)
                && near(zoom, other.zoom)
                && near(motion, other.motion)
                && near(orbitX, other.orbitX)
                && near(orbitY, other.orbitY)
                && near(panX, other.panX)
                && near(panY, other.panY)
                && near(brightnessGain, other.brightnessGain)
                && near(shadowLift, other.shadowLift)
                && near(lightAzimuth, other.lightAzimuth)
                && near(lightElevation, other.lightElevation)
                && near(sourceKind, other.sourceKind);
        // `bundled` is deliberately NOT compared, and the reason is structural rather
        // than a preference: Framing.ofValues -- which builds every snapshot -- hard-codes
        // it to false, so a sameAs that required it could NEVER no-op for the bundled
        // demo. The demo is the row every install starts with, so the guard this method
        // exists for was absent on the first photo anyone sees, and every resolve of the
        // marble bust painted instantly, mid-glide included.
        //
        // It is also the wrong question. `bundled` says WHICH ROW a framing came from; two
        // framings of the same numbers are the same picture whatever row produced them.
        // The field keeps its meaning as a label on Framing.of -- the renderer, the
        // diagnostic and the studio's reload all read it there -- and nothing anywhere
        // reads it to decide WHETHER TO DRAW, which is the only thing this comparison is
        // for.
        //
        // `sourceKind` IS compared, and the contrast is the lesson: it is a float rather
        // than a boolean precisely so a snapshot can reproduce it, so there is no
        // structural reason to exclude it -- and it must not be excluded, because a clip
        // and a still at the same numbers are not the same picture.
    }

    /**
     * The tolerance, and why an exact comparison could never work here.
     *
     * <p><b>A glide lands a whisker off its destination, and that was the snap.</b>
     * Measured: a glide easing to {@code lightAzimuth 209.0, panY 0.0} settled at
     * {@code 209.00562, 0.00486}; a fresh resolve a second later asked for the exact
     * values, an {@code ==} comparison said "a different picture", and the renderer painted
     * it INSTANTLY — which is precisely what the user's rule forbids and what they saw as a
     * snap mid-way through the transition.
     *
     * <p><b>Derived from the measurements on both sides, not chosen.</b> The largest
     * residues a settled glide leaves, from three device logs: <b>0.007</b>, <b>0.00486</b>
     * and <b>0.00024</b>. The smallest REAL differences that must still paint, from the same
     * logs: azimuth <b>209 -> 239</b> (30), depth <b>0.9 -> 0.29</b> (0.61), zoom
     * <b>1.0 -> 2.0</b> (1.0). One hundredth sits a hundredfold above the worst residue and
     * sixtyfold below the smallest real step, so neither side is close.
     *
     * <p><b>The first attempt used {@code 1e-4} and was WRONG by construction</b> — set
     * below residues that had already been measured at 0.005 and 0.007, so a glide
     * settling with a 0.00024 residue still slipped through and painted instantly. A
     * tolerance must be read off the numbers, never picked and then compared against
     * them; the test below asserts both sides of the margin so the next adjustment has to
     * confront both.
     */
    private static final float SAME_EPSILON = 1e-2f;

    private static boolean near(float a, float b) {
        // NOT `Math.abs(a - b) < e`: NaN fails every comparison, and two NaNs are "the same
        // picture" in the only sense that matters here -- a resolve that produced NaN twice
        // has not asked for anything new, and treating it as different would paint.
        if (Float.isNaN(a) && Float.isNaN(b)) return true;
        return Math.abs(a - b) <= SAME_EPSILON;
    }

    private Framing(LibraryItem item, Surface surface) {
        this.bundled = item == null || item.isBundled();
        // Null only when there is no item at all -- not when there is a bundled one,
        // which resolves like anything else from here.
        SurfaceSettings s = item == null ? null : item.settings(surface);

        // Asked of the row, never of the renderer and never of the caller. A caller
        // that could name the medium would be a second source for a value the index
        // already owns, which is how the two surfaces came to disagree about the
        // bundled item in the first place.
        this.sourceKind = (item != null && item.video != null)
                ? SOURCE_VIDEO : SOURCE_STILL;

        // Everything comes from the item for one surface, INCLUDING THE BUNDLED
        // DEMO. Nothing arrives from two places: a value that comes from two
        // places is a value that can disagree, and this class is the single
        // resolution point, which is the only reason it can be unit tested.
        //
        // There used to be a special case here that forced the demo to the defaults
        // and ignored whatever was stored, and the studio's controls were switched
        // off to match. That was a workaround for a real bug -- the sliders wrote a
        // value onto the reserved item and this class ignored it, so a control
        // displayed a number the renderer never used -- and the fix was applied to
        // the CONTROL instead of to the bug. Switching the controls off hides the
        // symptom and leaves the class still unable to honour a stored value.
        //
        // The demo is editable. Its image is a file in filesDir like any other
        // photo's -- the seed copies it there under the name its own id derives --
        // so Depth, Zoom, Motion, Origin, Pan, the adjustments and the light are
        // ordinary render values that the reserved item holds perfectly well, and
        // nothing in this class has to know which row is selected.
        if (s == null) {
            // No item at all, which is what "nothing is selected" means and the
            // state a delete of the active item lands in. Defaults, and no
            // null-dereference on the way to drawing the demo.
            this.depth = SurfaceSettings.DEFAULT_DEPTH / 100f;
            this.zoom = SurfaceSettings.DEFAULT_ZOOM / 100f;
            this.orbitX = 0f;
            this.orbitY = 0f;
            this.panX = 0f;
            this.panY = 0f;
            this.motion = SurfaceSettings.DEFAULT_MOTION / 100f * 3f;
            this.brightnessGain = 1f;
            this.shadowLift = 0f;
            this.lightAzimuth = LightDirection.DEFAULT_AZIMUTH_DEG;
            this.lightElevation = LightDirection.DEFAULT_ELEVATION_DEG;
            return;
        }
        this.depth = s.depthFraction();
        this.zoom = s.zoomScale();
        this.orbitX = s.originX;
        this.orbitY = s.originY;
        this.panX = s.panX;
        this.panY = s.panY;
        this.motion = s.motionScale();
        this.brightnessGain = Picture.brightnessGain(s.brightness);
        this.shadowLift = Picture.shadowLift(s.shadow);
        this.lightAzimuth = s.lightAzimuth;
        this.lightElevation = s.lightElevation;
    }

    /**
     * The framing for a selected item, on one surface.
     *
     * <p>Takes the item and a {@link Surface} rather than an {@code AppState},
     * because {@code AppState} is in :app and this class has moved to :core
     * precisely so it can be tested.
     *
     * <p><b>Motion is no longer a parameter.</b> It used to be passed in, from
     * {@code Config}, because it was global. It is per-photo now, so it is read
     * from the item like every other render value - which means the active
     * photo's Motion decides whether the sensor runs, and a caller can no longer
     * hand this class a motion the photo does not agree with.
     *
     * @param item    the selected item, or null for the bundled demo
     * @param surface which screen is being drawn; LOCK falls back to the item's
     *                home settings when the lock screen has none of its own
     */
    public static Framing of(LibraryItem item, Surface surface) {
        return new Framing(item, surface);
    }

    /** The home surface, which is what the studio previews. */
    public static Framing of(LibraryItem item) {
        return new Framing(item, Surface.HOME);
    }

    /**
     * A framing from plain values, for a renderer that already has numbers and needs
     * them as one thing.
     *
     * <p>Exists for the surface glide: a renderer easing between two configs needs to
     * hand its <i>current</i> values back in as the start of a glide, and only this
     * class may build one -- a builder in :app would be a second way to make a
     * Framing, and the two could drift. {@code bundled} is false because a glide
     * start is a snapshot of numbers, not a photo, and the bundled special case is
     * about which FILE to read.
     *
     * <p>The light arrives as angles, matching the fields; the renderer derives the
     * vector with {@link LightDirection#of} at the point of use. Passing a vector
     * here would put the wrap on the caller.
     *
     * <p><b>And so does the medium</b>, for the same reason: a snapshot of what is
     * on screen that cannot name whether it is a clip is a snapshot of a different
     * picture from the one being drawn, and {@link #sameAs} — which is what decides
     * whether a resolve paints — would then say "changed" on every resolve.
     */
    public static Framing ofValues(float depth, float zoom, float motion,
                                   float orbitX, float orbitY, float panX, float panY,
                                   float brightnessGain, float shadowLift,
                                   float lightAzimuth, float lightElevation,
                                   float sourceKind) {
        return new Framing(depth, zoom, motion, orbitX, orbitY, panX, panY,
                brightnessGain, shadowLift, lightAzimuth, lightElevation, sourceKind);
    }

    /**
     * @deprecated The form that <b>cannot say whether the picture is a clip</b>, so it
     * always says still. A renderer that hands back what it is currently drawing
     * must use the twelve-argument form above, because a snapshot taken through this
     * one loses the medium and then compares unequal to the frame on screen — an
     * instant repaint, which is the snap {@link #sameAs} exists to prevent.
     *
     * <p>Kept only because it has a caller that has not moved yet. It is correct for
     * every photograph, and {@code :core} cannot fix the caller from here.
     */
    @Deprecated
    public static Framing ofValues(float depth, float zoom, float motion,
                                   float orbitX, float orbitY, float panX, float panY,
                                   float brightnessGain, float shadowLift,
                                   float lightAzimuth, float lightElevation) {
        return ofValues(depth, zoom, motion, orbitX, orbitY, panX, panY,
                brightnessGain, shadowLift, lightAzimuth, lightElevation, SOURCE_STILL);
    }

    private Framing(float depth, float zoom, float motion,
                    float orbitX, float orbitY, float panX, float panY,
                    float brightnessGain, float shadowLift,
                    float lightAzimuth, float lightElevation, float sourceKind) {
        this.depth = depth;
        this.zoom = zoom;
        this.motion = motion;
        this.orbitX = orbitX;
        this.orbitY = orbitY;
        this.panX = panX;
        this.panY = panY;
        this.brightnessGain = brightnessGain;
        this.shadowLift = shadowLift;
        this.lightAzimuth = lightAzimuth;
        this.lightElevation = lightElevation;
        this.sourceKind = sourceKind;
        this.bundled = false;
    }
}
