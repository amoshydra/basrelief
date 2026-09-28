package com.amoshydra.basrelief.core.render;

import com.amoshydra.basrelief.core.store.LibraryItem;

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

    /** True when the marble bust is showing, and the files are in res/raw. */
    public final boolean bundled;

    private Framing(LibraryItem item, float motion) {
        this.bundled = item == null || item.isBundled();
        if (this.bundled) {
            // The bundled item is not editable, so it has no stored framing of its
            // own to keep. Resetting to the defaults is not a fallback -- it is
            // the only correct answer, and inheriting the last photo's zoom is
            // how the bust ended up framed by a portrait it had never seen.
            this.depth = LibraryItem.DEFAULT_INTENSITY / 100f;
            this.zoom = LibraryItem.DEFAULT_ZOOM / 100f;
            this.orbitX = 0f;
            this.orbitY = 0f;
            this.panX = 0f;
            this.panY = 0f;
        } else {
            this.depth = item.depthFraction();
            this.zoom = item.zoomScale();
            this.orbitX = item.orbitX;
            this.orbitY = item.orbitY;
            this.panX = item.panX;
            this.panY = item.panY;
        }
        this.motion = motion;
    }

    /**
     * The framing for a selected item.
     *
     * <p>Takes the item and the motion separately rather than an {@code AppState},
     * because {@code AppState} is in :app and this class has moved to :core
     * precisely so it can be tested. The two values it needs are the whole
     * contract, and a test that cannot construct one is a test that does not
     * exist.
     *
     * @param item   the selected item, or null for the bundled demo
     * @param motion the resolved motion scale, 0..3
     */
    public static Framing of(LibraryItem item, float motion) {
        return new Framing(item, motion);
    }
}
