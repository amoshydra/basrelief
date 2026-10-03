package com.amoshydra.basrelief.core.render;

import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

/**
 * What to draw, resolved from the app's state.
 *
 * <p><b>This class exists because the studio and the wallpaper disagreed.</b>
 * Both surfaces have to turn "which item is selected" into five numbers, and when
 * they each did it inline they drifted: the wallpaper service reset the framing
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

    /** True when the marble bust is showing, and the files are in res/raw. */
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
        // The demo is editable. Only its IMAGE comes from res/raw; Depth, Zoom,
        // Motion, Origin, Pan and the adjustments are ordinary render values that
        // the reserved item holds perfectly well.
        //
        // The one thing that remains special is the FILE: the bundled item has no
        // image on disk, which is why LibraryItem.fromJson exempts it. Unrelated to
        // framing, and still true.
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
     */
    public static Framing ofValues(float depth, float zoom, float motion,
                                   float orbitX, float orbitY, float panX, float panY,
                                   float brightnessGain, float shadowLift) {
        return new Framing(depth, zoom, motion, orbitX, orbitY, panX, panY,
                brightnessGain, shadowLift);
    }

    private Framing(float depth, float zoom, float motion,
                    float orbitX, float orbitY, float panX, float panY,
                    float brightnessGain, float shadowLift) {
        this.depth = depth;
        this.zoom = zoom;
        this.motion = motion;
        this.orbitX = orbitX;
        this.orbitY = orbitY;
        this.panX = panX;
        this.panY = panY;
        this.brightnessGain = brightnessGain;
        this.shadowLift = shadowLift;
        this.bundled = false;
    }
}
