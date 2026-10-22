package com.amoshydra.basrelief.core.store;

import com.amoshydra.basrelief.core.render.LightDirection;
import com.amoshydra.basrelief.core.render.Splat;

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
 * that is clamped on read as well is a value that can be read two ways. The one
 * exception is azimuth, which wraps instead of clamping — 0° and 360° are the
 * same light, so a clamp would create a dead zone at the seam; see
 * {@link LightDirection}.
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
     * Playback speed, in percent: 25 is 0.25x and 200 is 2x.
     *
     * <p><b>A CLIP's own rate, and a render value like Motion.</b> It is on the
     * settings rather than in a pref for the reason Motion is: it changes what is
     * drawn, per photo and per surface, and a global would be the {@code config.motion}
     * mistake one version after the migration that removed it. A photograph ignores
     * it entirely -- there is nothing to play -- which is why the row that edits it
     * is shown for clips alone.
     *
     * <p>The range is asymmetric on purpose: 1x is the default and the useful
     * correction is usually SLOWER (a loop that reads as frantic), with faster
     * available for a clip that drags. 0.25x is slow enough to read as slow motion
     * rather than as broken; 2x is fast enough to feel hurried.
     */
    public static final int SPEED_MIN = 25, SPEED_MAX = 200, DEFAULT_SPEED = 100;
    /**
     * Origin is a FRACTION of the plane, and its range is what the control needs
     * rather than a bound of the model: it names the ORBIT CENTRE, which may sit
     * outside the picture, so ±2 picture-widths is a generous, still-sane travel.
     *
     * <p><b>0.5 was a consequence of the shift, not a property of the model.</b>
     * Origin used to translate the picture so the origin point landed at the
     * centre of the screen, so at +0.5 the photo's edge was mid-screen and at +1.0
     * the photo was entirely off screen — which is why the far stop was half a
     * plane. Origin no longer moves the picture (the camera is a turntable about
     * the pivot with a fixed rest eye; see {@code CameraModel.frame}), so an
     * origin outside the photo simply means the orbit centre is outside it, and
     * the bound can be what the control needs. See {@code FramingRows} for the
     * slider's own derivation.
     */
    public static final float ORIGIN_LIMIT = 2.0f;
    public static final float DEFAULT_ORIGIN = 0f;
    public static final float DEFAULT_PAN = 0f;
    /**
     * Brightness and shadow, -100..100 as a signed offset. Both are render-time
     * only — the user's PNG is never rewritten — so they are clamped on read and
     * 0 is the identity for both.
     */
    public static final int ADJUST_MIN = -100, ADJUST_MAX = 100, DEFAULT_ADJUST = 0;

    /**
     * The light's angles, stored here and mapped by {@link LightDirection}. The
     * default is that class's {@code (-0.35, 0.5, 0.75)} literal expressed as
     * angles, so an existing install upgrades without its shading moving. The
     * convention — which way azimuth runs, what elevation 0 means — is
     * documented on {@link LightDirection}, which is the single place the
     * mapping exists.
     */
    public static final float DEFAULT_LIGHT_AZIMUTH = LightDirection.DEFAULT_AZIMUTH_DEG;
    public static final float DEFAULT_LIGHT_ELEVATION = LightDirection.DEFAULT_ELEVATION_DEG;

    /**
     * The four splat-look values, and <b>they are render values on this surface
     * like every other field here</b>: per photo and per screen, so a cloud can
     * look different from the next cloud and different behind a lock-screen clock.
     * They were global prefs while the look was still being tuned by eye; the
     * tuning is over, so they moved into the counted chain exactly as Motion moved
     * out of {@link Config}. The defaults are the constants the prefs used to
     * default to, so an untouched install renders exactly what it rendered before.
     *
     * <p>Each clamp lives in {@link Splat}, which owns the range and the "a window
     * claims nothing" fallback, and is applied once here in the constructor -- the
     * same rule every other bound in this class follows.
     */
    public static final float DEFAULT_SPLAT_DEPTH = Splat.DEPTH_MUL_DEFAULT;
    public static final float DEFAULT_SPLAT_EPS2D = Splat.EPS_2D_DEFAULT;
    public static final float DEFAULT_SPLAT_BLUR = Splat.BLUR_DEFAULT;
    public static final float DEFAULT_SPLAT_SCALE = Splat.RENDER_SCALE_DEFAULT;

    /**
     * Pan Z: the camera distance as a multiplier, stored per photo and per
     * surface exactly as Pan X/Y are. 1.0 is the source camera; above pulls the
     * eye back (smaller picture, more depth room), below comes closer (bigger).
     */
    public static final float PAN_Z_MIN = 0.5f;
    public static final float PAN_Z_MAX = 5.0f;
    public static final float DEFAULT_PAN_Z = 1.0f;

    // ----------------------------------------------------------------- state

    public final int depth;
    public final int zoom;
    public final int motion;
    /** Playback speed in percent; see {@link #SPEED_MIN}. Clips only. */
    public final int speed;
    public final float originX;
    public final float originY;
    public final float panX;
    public final float panY;
    /** Pan Z: camera distance, 1.0 is the source camera. See {@link #PAN_Z_MIN}. */
    public final float panZ;
    public final int brightness;
    public final int shadow;
    /** Azimuth in degrees, wrapped into [0, 360); see {@link LightDirection}. */
    public final float lightAzimuth;
    /** Elevation in degrees, clamped into [0, 90]; see {@link LightDirection}. */
    public final float lightElevation;
    /** The splat cloud's Depth: a multiplier on the reconstructed depth. */
    public final float splatDepth;
    /** The splat cloud's Size: the 2D floor as a fraction of a splat's cell. */
    public final float splatEps2d;
    /** The splat cloud's Blur: a post-blur radius in render-target pixels. */
    public final float splatBlur;
    /** The splat cloud's Resolution: the fraction of the surface it is drawn at. */
    public final float splatScale;

    /** Everything at its default — "a photo that has not been adjusted yet". */
    public static SurfaceSettings defaults() {
        return new SurfaceSettings(DEFAULT_DEPTH, DEFAULT_ZOOM, DEFAULT_MOTION,
                DEFAULT_ORIGIN, DEFAULT_ORIGIN, DEFAULT_PAN, DEFAULT_PAN,
                DEFAULT_ADJUST, DEFAULT_ADJUST,
                DEFAULT_LIGHT_AZIMUTH, DEFAULT_LIGHT_ELEVATION, DEFAULT_SPEED,
                DEFAULT_SPLAT_DEPTH, DEFAULT_SPLAT_EPS2D, DEFAULT_SPLAT_BLUR,
                DEFAULT_SPLAT_SCALE, DEFAULT_PAN_Z);
    }

    public SurfaceSettings(int depth, int zoom, int motion,
                           float originX, float originY, float panX, float panY,
                           int brightness, int shadow,
                           float lightAzimuth, float lightElevation, int speed,
                           float splatDepth, float splatEps2d, float splatBlur,
                           float splatScale, float panZ) {
        this.depth = clamp(depth, DEPTH_MIN, DEPTH_MAX);
        this.zoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        this.motion = clamp(motion, MOTION_MIN, MOTION_MAX);
        this.originX = clamp(originX, -ORIGIN_LIMIT, ORIGIN_LIMIT);
        this.originY = clamp(originY, -ORIGIN_LIMIT, ORIGIN_LIMIT);
        this.panX = panX;
        this.panY = panY;
        this.brightness = clamp(brightness, ADJUST_MIN, ADJUST_MAX);
        this.shadow = clamp(shadow, ADJUST_MIN, ADJUST_MAX);
        // Normalised here, once, like every other bound in this class. The
        // mapping's own clamp/wrap is in LightDirection so the UI, the JSON
        // reader and the shader cannot each decide 370° for themselves.
        this.lightAzimuth = LightDirection.normaliseAzimuth(lightAzimuth);
        this.lightElevation = LightDirection.normaliseElevation(lightElevation);
        this.speed = clamp(speed, SPEED_MIN, SPEED_MAX);
        // The splat look, clamped through Splat's own helpers so the store, the
        // sheet's range and the shader cannot each decide a different bound -- and a
        // NaN answers the product default rather than reaching a uniform.
        this.splatDepth = Splat.clampDepthMul(splatDepth);
        this.splatEps2d = Splat.clampEps2d(splatEps2d);
        this.splatBlur = Splat.clampBlurPx(splatBlur);
        this.splatScale = Splat.clampRenderScale(splatScale);
        // Pan Z clamps here like every other bound; a NaN answers the identity
        // rather than reaching the camera.
        this.panZ = Float.isFinite(panZ)
                ? Math.max(PAN_Z_MIN, Math.min(PAN_Z_MAX, panZ)) : DEFAULT_PAN_Z;
    }

    // ------------------------------------------------------------- accessors

    /** Depth as a fraction of the reachable maximum. */
    public float depthFraction() { return depth / 100f; }

    /** Zoom as a multiplier: 25 is 0.25x, 100 is 1.0x, 200 is 2.0x. */
    public float zoomScale() { return zoom / 100f; }

    /** Motion as the 0..3 scale the renderer tilts by. */
    public float motionScale() { return motion / 100f * 3f; }

    /** Playback speed as a multiplier: 25 is 0.25x, 100 is 1.0x, 200 is 2.0x. */
    public float speedScale() { return speed / 100f; }

    /**
     * This surface's settings, with the given overrides applied. A copy, never a
     * mutation: an immutable value that is edited in place is a value two owners
     * can be holding at once.
     *
     * <p><b>The parameter list is positional and complete on purpose.</b> Adding
     * a field makes every call site of this method fail to compile, which is the
     * only mechanical guard there is against the bug this class has already
     * shipped: a {@code withDepth} written before Motion, brightness and shadow
     * existed reset all three the first time it was called. There is deliberately
     * no overload that omits the light; that overload would silently perform the
     * same reset for the two new values. A caller that knows only the light must
     * use {@link #withLight}, which reads the other values from {@code this}.
     */
    public SurfaceSettings with(int depth, int zoom, int motion,
                                float originX, float originY,
                                float panX, float panY,
                                int brightness, int shadow,
                                float lightAzimuth, float lightElevation, int speed,
                                float splatDepth, float splatEps2d, float splatBlur,
                                float splatScale, float panZ) {
        return new SurfaceSettings(depth, zoom, motion, originX, originY,
                panX, panY, brightness, shadow, lightAzimuth, lightElevation, speed,
                splatDepth, splatEps2d, splatBlur, splatScale, panZ);
    }

    /** The light angles, with every other value read from this instance. */
    public SurfaceSettings withLight(float azimuthDegrees, float elevationDegrees) {
        return with(depth, zoom, motion, originX, originY, panX, panY,
                brightness, shadow, azimuthDegrees, elevationDegrees, speed,
                splatDepth, splatEps2d, splatBlur, splatScale, panZ);
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
            // Floats, unlike the ints above: the angle was picked on a slider and
            // comes back exactly as stored. Wrapped/clamped first, so what is
            // written is already the canonical value the renderer will use.
            o.put("light_azimuth", (double) lightAzimuth);
            o.put("light_elevation", (double) lightElevation);
            o.put("speed", speed);
            // The four splat-look values, each written only when it is NOT the
            // default -- the normal/video/focal rule, and why moving them into the
            // schema needed no key an untouched item gains. An item that never had
            // them (every item written before they existed) reads them back as the
            // defaults, and an item whose value IS the default writes nothing, so a
            // photograph nobody opened the splat rows on stays byte-identical.
            // The cast is load-bearing: :core compiles against Maven's org.json,
            // which has put(String, float), while Android's platform org.json does
            // NOT, so a bare float throws NoSuchMethodError on the index-writer
            // thread and takes the app down on any library write.
            // scripts/check-json-puts.mjs enforces it.
            if (splatDepth != DEFAULT_SPLAT_DEPTH) o.put("splat_depth", (double) splatDepth);
            if (splatEps2d != DEFAULT_SPLAT_EPS2D) o.put("splat_eps2d", (double) splatEps2d);
            if (splatBlur != DEFAULT_SPLAT_BLUR) o.put("splat_blur", (double) splatBlur);
            if (splatScale != DEFAULT_SPLAT_SCALE) o.put("splat_scale", (double) splatScale);
            // Pan Z, the same rule: written only when it is not the default, so an
            // untouched item stays byte-identical.
            if (panZ != DEFAULT_PAN_Z) o.put("pan_z", (double) panZ);
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
                o.optInt("shadow", DEFAULT_ADJUST),
                (float) o.optDouble("light_azimuth", DEFAULT_LIGHT_AZIMUTH),
                (float) o.optDouble("light_elevation", DEFAULT_LIGHT_ELEVATION),
                // Absent means 1x: every settings blob written before this field
                // existed plays exactly as it did, which is the only honest default
                // for a value that changes motion.
                o.optInt("speed", DEFAULT_SPEED),
                // The splat look: absent is the product default, which is what every
                // item written before the fields existed carries -- so an upgrade
                // changes no cloud's look. The constructor clamps, so a hand-edited
                // blob cannot put a NaN or an out-of-range value on the shader.
                (float) o.optDouble("splat_depth", DEFAULT_SPLAT_DEPTH),
                (float) o.optDouble("splat_eps2d", DEFAULT_SPLAT_EPS2D),
                (float) o.optDouble("splat_blur", DEFAULT_SPLAT_BLUR),
                (float) o.optDouble("splat_scale", DEFAULT_SPLAT_SCALE),
                // Pan Z: absent is the source camera, which is what every item
                // written before the field existed carries -- so an upgrade changes
                // no photo's framing. The constructor clamps.
                (float) o.optDouble("pan_z", DEFAULT_PAN_Z));
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
                + " adj=" + brightness + "," + shadow
                + " light=" + lightAzimuth + "°," + lightElevation + "°"
                + " splat=" + splatDepth + "," + splatEps2d + "," + splatBlur
                + "," + splatScale;
    }
}
