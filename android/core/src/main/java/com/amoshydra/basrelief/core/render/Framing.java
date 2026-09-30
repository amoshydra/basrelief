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
        // The picture adjustments are not camera state, but they are still per-photo
        // and per-surface, so they resolve here rather than in the renderer: the
        // renderer is handed numbers, never an item.
        this.bundled = item == null || item.isBundled();
        SurfaceSettings s = item == null ? null : item.settings(surface);
        if (this.bundled) {
            // The bundled demo has no stored framing of its own to keep, so Depth,
            // Zoom, Origin and Pan are the defaults. Not a fallback: it is the only
            // correct answer, and inheriting the last photo's zoom is how the bust
            // ended up framed by a portrait it had never seen.
            //
            // MOTION IS THE EXCEPTION, and deliberately so. Its slider stays live on
            // the demo while Depth and Zoom are switched off, because how responsive
            // the relief is answers a question about the photo the user is looking at
            // rather than about the demo's framing. Ignoring it here would put the
            // slider back in the state this whole rule exists to prevent: a control
            // that displays a number the renderer never uses.
            this.depth = SurfaceSettings.DEFAULT_DEPTH / 100f;
            this.zoom = SurfaceSettings.DEFAULT_ZOOM / 100f;
            this.orbitX = 0f;
            this.orbitY = 0f;
            this.panX = 0f;
            this.panY = 0f;
            this.motion = s == null
                    ? SurfaceSettings.DEFAULT_MOTION / 100f * 3f
                    : s.motionScale();
            // Picture is switched off for the demo, so its stored values are the same
            // kind of lie a stored Zoom would be: displayed nowhere, used here.
            this.brightnessGain = 1f;
            this.shadowLift = 0f;
        } else {
            // Everything comes from the item for one surface. Nothing is passed in:
            // a value that arrives from two places is a value that can disagree,
            // and this class is the single resolution point, which is the only
            // reason it can be unit tested.
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
}
