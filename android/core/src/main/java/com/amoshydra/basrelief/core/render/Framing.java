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

    private Framing(LibraryItem item, Surface surface) {
        this.bundled = item == null || item.isBundled();
        // Null only when there is no item at all -- not when there is a bundled one,
        // which resolves like anything else from here.
        SurfaceSettings s = item == null ? null : item.settings(surface);

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
     */
    public static Framing ofValues(float depth, float zoom, float motion,
                                   float orbitX, float orbitY, float panX, float panY,
                                   float brightnessGain, float shadowLift,
                                   float lightAzimuth, float lightElevation) {
        return new Framing(depth, zoom, motion, orbitX, orbitY, panX, panY,
                brightnessGain, shadowLift, lightAzimuth, lightElevation);
    }

    private Framing(float depth, float zoom, float motion,
                    float orbitX, float orbitY, float panX, float panY,
                    float brightnessGain, float shadowLift,
                    float lightAzimuth, float lightElevation) {
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
        this.bundled = false;
    }
}
