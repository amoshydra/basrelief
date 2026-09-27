package com.amoshydra.basrelief;

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
 * <p>Resolving it once is cheaper than the alternative. There is no unit test
 * here because there is no Android in {@code :core} -- but this is exactly the
 * kind of value that is testable precisely <i>because</i> it has no Android in
 * it, so if this ever grows a dependency it should move to {@code :core} rather
 * than stay.
 */
final class Framing {

    final float depth;
    final float zoom;
    final float motion;
    final float orbitX;
    final float orbitY;
    final float panX;
    final float panY;

    /** True when the marble bust is showing, and the files are in res/raw. */
    final boolean bundled;

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

    static Framing of(AppState state) {
        return new Framing(state.activeItem(), state.config.motionScale());
    }
}
