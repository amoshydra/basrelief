package com.amoshydra.basrelief.core.store;

/**
 * How a photograph is drawn: as the displaced relief, or as a Gaussian splat cloud.
 *
 * <p><b>The item is the home, and this class exists because a global was the wrong
 * one.</b> The mode used to be one {@code render_3d} pref, so it described the app
 * rather than the picture — and a gallery would then draw every photograph the same
 * way, or none, whichever the single switch last said. It is a render value like
 * Depth and Motion, so it lives on the row. The pref survives only as the
 * <b>migration source</b>: {@link Library}'s v3 to v4 migration reads it once and
 * copies its value onto every item, exactly as Motion moved out of {@link Config}
 * in v3 and for exactly the same reason.
 *
 * <p><b>SPLAT is a request, not a fact about what is on screen.</b> A row can ask for
 * the cloud and have none yet — that is the whole "press Splat and the offer follows"
 * flow — and it renders its relief until one exists. Whether the cloud is actually
 * drawn is {@link SplatSource}'s answer plus the renderer's own on-screen identity,
 * the same split {@link DepthSource} and {@link VideoSource} make for their media.
 */
public enum RenderMode {

    /** The displaced relief. The default, and what every pre-v4 row is. */
    RELIEF("relief"),

    /** The Gaussian splat cloud, when the row has one. */
    SPLAT("splat");

    private final String key;

    RenderMode(String key) {
        this.key = key;
    }

    /** The stored spelling. See {@link #parse}. */
    public String key() {
        return key;
    }

    /** Whether this is the cloud. One question, one answer. */
    public boolean isSplat() {
        return this == SPLAT;
    }

    /**
     * Read a stored spelling, defaulting to {@link #RELIEF}.
     *
     * <p>Absent, null, unknown and a value from a newer build all answer
     * {@code RELIEF} rather than throwing: this is read on the path to drawing, and
     * the relief is the degradation that renders something. The same rule
     * {@code SurfaceSettings.fromJson} and {@code VideoSource} follow.
     */
    public static RenderMode parse(String stored) {
        if (stored == null) return RELIEF;
        for (RenderMode mode : values()) {
            if (mode.key.equals(stored)) return mode;
        }
        return RELIEF;
    }
}
