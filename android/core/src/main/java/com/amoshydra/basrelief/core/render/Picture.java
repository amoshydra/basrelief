package com.amoshydra.basrelief.core.render;

/**
 * The two picture adjustments, as the scalars the fragment shader consumes.
 *
 * <p>Brightness and Shadow change how the photograph renders, not where it sits,
 * so unlike Depth and Zoom they are not camera state — see
 * {@link CameraModel}, which knows nothing about them.
 *
 * <p><b>Why the maths lives here and not in the shader.</b> The shader is one
 * multiply and one mix; what is easy to get wrong is the <i>mapping</i> from a
 * stored -100..100 to those two numbers, and that is pure arithmetic with no GL in
 * it. Putting it here means the interesting half is unit tested, and the shader is
 * left holding only the arithmetic. The tradeoff is that the formula now exists
 * twice — here in Java and as GLSL in {@code ReliefRenderer} — so both are
 * deliberately trivial and neither is allowed to grow a branch.
 *
 * <p><b>0 is the identity for both.</b> That is the whole contract: a photo nobody
 * has adjusted renders exactly as it was imported, and a stored 0 cannot be
 * distinguished from no adjustment. Any change here that breaks it changes every
 * user's wallpaper.
 *
 * <p><b>Neither ever writes pixels.</b> The user's PNG is theirs; these are
 * render-time only, and the value is discarded the moment the surface goes away.
 */
public final class Picture {

    /** Stored units are hundredths, matching Depth's slider convention. */
    private static final float HUNDREDTHS = 100f;

    private Picture() {
    }

    /**
     * Brightness as a multiplier, from hundredths of a stop.
     *
     * <p>An exposure offset rather than an addition, because that is what a stop is:
     * +100 is one stop and doubles the image, -100 halves it. Adding a constant
     * instead would brighten shadows far more than highlights, which is not what
     * anyone means by "brighter".
     *
     * @param brightness -100..100, clamped on the way in
     * @return a positive multiplier; 1.0 at 0
     */
    public static float brightnessGain(int brightness) {
        float stops = clamp(brightness, -100, 100) / HUNDREDTHS;
        return (float) Math.pow(2.0d, stops);
    }

    /**
     * Shadow as a lift amount, from hundredths.
     *
     * <p>Positive lifts the black point and leaves white at white; negative deepens
     * the blacks toward black and likewise leaves white alone. The shader applies it
     * as {@code color + lift * (1 - color)}, which is the cheap approximation of a
     * lift that ignores the tone curve entirely — it shifts the whole image rather
     * than only the shadows, and that is a deliberate trade for a wallpaper: a real
     * lift needs a tone curve, and a tone curve is where a shader on a mid-range
     * phone starts costing frames.
     *
     * @param shadow -100..100, clamped on the way in
     * @return 0.0 at 0, which the shader must leave untouched
     */
    public static float shadowLift(int shadow) {
        return clamp(shadow, -100, 100) / HUNDREDTHS;
    }

    /**
     * The one place these are bounded.
     *
     * <p>The bounds are {@code SurfaceSettings}'s, restated rather than imported:
     * {@code :render} already depends on {@code :store} for {@link CameraModel}'s
     * sake, so importing them would work — but a shader-uniform mapping is read by
     * people who will not go looking in another class for its range, and the test
     * {@code theClampIsTheStoresClamp} is what keeps the two from drifting.
     */
    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}