package com.amoshydra.basrelief.core.store;

import org.json.JSONObject;

/**
 * One surface's worth of render settings for one photo.
 *
 * <p>Immutable, and the only place a render value may live. Everything here is
 * <b>per photo</b>: a wallpaper is a photograph you chose, and how far it stands
 * out of its plane is a property of that photograph, not of the app. That is the
 * change from Motion having been global — and it has a consequence the app must
 * honour, which is that the <b>active</b> photo's Motion now decides whether the
 * sensor runs at all. A photo at Motion 0 parks the sensor even when the previous
 * photo had it at 60.
 *
 * <p>Clamped on construction, which is the only place clamping happens. A value
 * that is clamped on read as well is a value that can be read two ways.
 *
 * <p><b>Pan is in pixels and is deliberately NOT clamped</b>, unlike everything
 * else here. It is consumed as {@code panX / surfaceWidth}, so its valid range is
 * the surface — which this class cannot know, and which differs per device and
 * per orientation. Clamping it to orbit's fraction range once flattened every pan
 * offset to almost nothing: a stored -135px became -0.5px. Do not add a range
 * check without knowing the units.
 */
public final class SurfaceSettings {

    // ---------------------------------------------------------------- bounds

    /**
     * Depth, as the slider's 0..100. See {@link #depthFraction()}.
     *
     * <p>The oldest build wrote depth on a 0..200 scale and recorded nothing about
     * which build wrote a given value, so a stored 150 is clamped to 100 rather than
     * guessed at. Clamped on read, never migrated — the same convention the
     * adjustments below follow.
     */
    public static final int DEPTH_MIN = 0, DEPTH_MAX = 100, DEFAULT_DEPTH = 90;

    /** Zoom, as a percentage of 1.0x. Below 25% the camera would invert. */
    public static final int ZOOM_MIN = 25, ZOOM_MAX = 200, DEFAULT_ZOOM = 100;

    /**
     * Motion, 0..100, scaled to 0..3 internally. It moved here from {@link Config},
     * where it was global, and the default is that class's — so an upgraded user's
     * wallpaper does not silently change speed on the way.
     */
    public static final int MOTION_MIN = 0, MOTION_MAX = 100, DEFAULT_MOTION = 33;
    /**
     * Origin is a FRACTION of the plane, so it has a real bound: half the plane
     * is the far stop, because that is as far as the view goes before the photo
     * leaves the screen.
     */
    public static final float ORIGIN_LIMIT = 0.5f;
    public static final float DEFAULT_ORIGIN = 0f;
    public static final float DEFAULT_PAN = 0f;
    /**
     * Brightness and shadow, -100..100 as a signed offset. Both are render-time
     * only — the user's PNG is never rewritten — so they are clamped on read and
     * 0 is the identity for both.
     */
    public static final int ADJUST_MIN = -100, ADJUST_MAX = 100, DEFAULT_ADJUST = 0;

    // ----------------------------------------------------------------- state

    public final int depth;
    public final int zoom;
    public final int motion;
    public final float originX;
    public final float originY;
    public final float panX;
    public final float panY;
    public final int brightness;
    public final int shadow;

    /** Everything at its default — "a photo that has not been adjusted yet". */
    public static SurfaceSettings defaults() {
        return new SurfaceSettings(DEFAULT_DEPTH, DEFAULT_ZOOM, DEFAULT_MOTION,
                DEFAULT_ORIGIN, DEFAULT_ORIGIN, DEFAULT_PAN, DEFAULT_PAN,
                DEFAULT_ADJUST, DEFAULT_ADJUST);
    }

    public SurfaceSettings(int depth, int zoom, int motion,
                           float originX, float originY, float panX, float panY,
                           int brightness, int shadow) {
        this.depth = clamp(depth, DEPTH_MIN, DEPTH_MAX);
        this.zoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        this.motion = clamp(motion, MOTION_MIN, MOTION_MAX);
        this.originX = clamp(originX, -ORIGIN_LIMIT, ORIGIN_LIMIT);
        this.originY = clamp(originY, -ORIGIN_LIMIT, ORIGIN_LIMIT);
        this.panX = panX;
        this.panY = panY;
        this.brightness = clamp(brightness, ADJUST_MIN, ADJUST_MAX);
        this.shadow = clamp(shadow, ADJUST_MIN, ADJUST_MAX);
    }

    // ------------------------------------------------------------- accessors

    /** Depth as a fraction of the reachable maximum. */
    public float depthFraction() { return depth / 100f; }

    /** Zoom as a multiplier: 25 is 0.25x, 100 is 1.0x, 200 is 2.0x. */
    public float zoomScale() { return zoom / 100f; }

    /** Motion as the 0..3 scale the renderer tilts by. */
    public float motionScale() { return motion / 100f * 3f; }

    /**
     * This surface's settings, with the given overrides applied. A copy, never a
     * mutation: an immutable value that is edited in place is a value two owners
     * can be holding at once.
     */
    public SurfaceSettings with(int depth, int zoom, int motion,
                                float originX, float originY,
                                float panX, float panY,
                                int brightness, int shadow) {
        return new SurfaceSettings(depth, zoom, motion, originX, originY,
                panX, panY, brightness, shadow);
    }

    // ------------------------------------------------------------- serialise

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("depth", depth);
            o.put("zoom", zoom);
            o.put("motion", motion);
            o.put("origin_x", (double) originX);
            o.put("origin_y", (double) originY);
            o.put("pan_x", (double) panX);
            o.put("pan_y", (double) panY);
            o.put("brightness", brightness);
            o.put("shadow", shadow);
        } catch (Exception ignored) {
            // A JSONObject put cannot fail on these types. If it somehow does, the
            // value is lost rather than the write: an item with default settings
            // is recoverable, a throw here is not.
        }
        return o;
    }

    /**
     * Read one surface's settings, defaulting every absent field.
     *
     * <p>Never throws and never returns null. The wallpaper parses the index on
     * the GL thread at cold start, so anything that can throw here is a crash on
     * the home screen.
     */
    public static SurfaceSettings fromJson(JSONObject o) {
        if (o == null) return defaults();
        return new SurfaceSettings(
                o.optInt("depth", DEFAULT_DEPTH),
                o.optInt("zoom", DEFAULT_ZOOM),
                o.optInt("motion", DEFAULT_MOTION),
                (float) o.optDouble("origin_x", DEFAULT_ORIGIN),
                (float) o.optDouble("origin_y", DEFAULT_ORIGIN),
                (float) o.optDouble("pan_x", DEFAULT_PAN),
                (float) o.optDouble("pan_y", DEFAULT_PAN),
                o.optInt("brightness", DEFAULT_ADJUST),
                o.optInt("shadow", DEFAULT_ADJUST));
    }

    // ----------------------------------------------------------------- utils

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return "depth=" + depth + " zoom=" + zoom + " motion=" + motion
                + " origin=" + originX + "," + originY + " pan=" + panX + "," + panY
                + " adj=" + brightness + "," + shadow;
    }
}
