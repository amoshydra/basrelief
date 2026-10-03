package com.amoshydra.basrelief.core.render;

/**
 * The direction the relief's one light comes from, as a pure function of two
 * angles.
 *
 * <p><b>This class exists because the light was a literal.</b> The fragment
 * shader multiplies the photograph by {@code 1 + 0.25 * dot(n, l)} and nothing
 * else, where {@code l} was hard-coded to {@code (-0.35, 0.5, 0.75)} in
 * {@code ReliefRenderer}. A basrelief is defined by raking light, so that angle
 * is the single biggest visual lever the app has, and it was out of the user's
 * hands. The mapping from "an angle a person can think in" to "the vector the
 * shader samples" is exactly the kind of arithmetic that is easy to get quietly
 * wrong, so it lives here rather than in GLSL or in the renderer.
 *
 * <h2>Convention — this is the contract sliders are built against</h2>
 *
 * <ul>
 *   <li><b>Model space</b> is the relief's: {@code +x} is right, {@code +y} is
 *       up, {@code +z} points out of the picture, toward the viewer. A flat
 *       relief's normals point along {@code +z}.</li>
 *   <li><b>Azimuth</b> is measured in the picture plane, counter-clockwise as
 *       the viewer sees it, from {@code +x}: {@code 0°} is light from the
 *       right, {@code 90°} from the top, {@code 180°} from the left, {@code
 *       270°} from the bottom. Any value is accepted and <b>wrapped</b> into
 *       {@code [0, 360)} — wrapped, not clamped, because 0° and 360° are the
 *       same light and a clamp would create a dead zone at the seam.</li>
 *   <li><b>Elevation</b> is the angle above the picture plane: {@code 0°} is
 *       grazing, the light lying in the plane of the relief and raking across
 *       it; {@code 90°} is head-on from the viewer. Values are <b>clamped</b>
 *       to {@code [0, 90]}. A negative elevation puts the light behind the
 *       relief, where the shader's clamped {@code dot} can only render black,
 *       so it is not offered.</li>
 *   <li>The vector <b>points from the surface toward the light</b>, which is
 *       what {@code uLightDir} means: the shader samples {@code dot(normal,
 *       normalize(uLightDir))}. It is returned at <b>unit length</b>. The
 *       shader normalizes anyway, so only the direction carries meaning, but a
 *       canonical length makes comparison, interpolation and the inverse
 *       well-defined.</li>
 * </ul>
 *
 * <h2>The default is the historical literal, not round numbers</h2>
 *
 * <p>{@code (-0.35, 0.5, 0.75)} is azimuth 124.992°, elevation 50.862°. Those
 * constants are <b>derived from the literal</b> rather than picked, so
 * {@code of(DEFAULT_AZIMUTH_DEG, DEFAULT_ELEVATION_DEG)} reproduces the
 * shading every existing photo already had to within about {@code 7e-8} per
 * component — far below the 8-bit output step of {@code 1/255}. A round
 * "125° / 50°" would instead rotate every wallpaper's light by a fraction of a
 * degree on upgrade: a change nobody could file a bug about, which is exactly
 * the kind of silent relight this class exists to prevent.
 *
 * <p>Immutable. The renderer resolves it when settings change, not per frame,
 * and the glide between two surfaces interpolates the <i>angles</i> via
 * {@link #lerpAzimuth} so a 350°→10° change travels 20°, not 340°.
 */
public final class LightDirection {

    // ------------------------------------------------------------- the range

    /** Azimuth wraps here: 0° and 360° are the same light. See the class doc. */
    public static final float AZIMUTH_MIN = 0f;
    public static final float AZIMUTH_MAX = 360f;

    /** Elevation is clamped here: 0° rakes across the relief, 90° is head-on. */
    public static final float ELEVATION_MIN = 0f;
    public static final float ELEVATION_MAX = 90f;

    // ---------------------------------------------------------- the default

    /** The respective angle of the historical literal {@code (-0.35, 0.5, 0.75)}. */
    public static final float DEFAULT_AZIMUTH_DEG =
            (float) Math.toDegrees(Math.atan2(0.5, -0.35));

    /** @see #DEFAULT_AZIMUTH_DEG */
    public static final float DEFAULT_ELEVATION_DEG =
            (float) Math.toDegrees(Math.asin(
                    0.75 / Math.sqrt(0.35 * 0.35 + 0.5 * 0.5 + 0.75 * 0.75)));

    /**
     * The default light as a resolved vector: the exact direction the shader
     * had before this class existed, at unit length.
     */
    public static final LightDirection DEFAULT =
            of(DEFAULT_AZIMUTH_DEG, DEFAULT_ELEVATION_DEG);

    // ---------------------------------------------------------------- state

    /** Model-space components, pointing from the surface toward the light. */
    public final float x;
    public final float y;
    public final float z;

    private LightDirection(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // -------------------------------------------------------------- mapping

    /**
     * The unit vector for a pair of angles.
     *
     * <p>The input is normalised first: azimuth wraps into {@code [0, 360)} and
     * elevation clamps into {@code [0, 90]}. A non-finite input becomes the
     * corresponding default rather than a NaN direction, because a NaN reaching
     * the uniform turns pixels undefined and the caller that supplied it is a
     * corrupt blob on the GL thread, which must not be a crash or a black
     * screen.
     *
     * @param azimuthDegrees counter-clockwise from {@code +x}, any finite value
     * @param elevationDegrees above the picture plane, clamped to [0, 90]
     * @return a unit-length direction, never null
     */
    public static LightDirection of(float azimuthDegrees, float elevationDegrees) {
        double az = Math.toRadians(normaliseAzimuth(azimuthDegrees));
        double el = Math.toRadians(normaliseElevation(elevationDegrees));
        double cosEl = Math.cos(el);
        return new LightDirection(
                (float) (cosEl * Math.cos(az)),
                (float) (cosEl * Math.sin(az)),
                (float) Math.sin(el));
    }

    /**
     * The inverse: the unit vector for an arbitrary direction.
     *
     * <p>Exists for the one place the renderer needs to hand its current light
     * back as a resolved value — the surface glide's start point — and for
     * tests. A zero or non-finite vector returns {@link #DEFAULT}: there is no
     * direction in it, and a NaN would poison every later comparison.
     */
    public static LightDirection fromVector(float x, float y, float z) {
        double len = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!(len > 0d) || Double.isInfinite(len) || Double.isNaN(len)) return DEFAULT;
        return new LightDirection((float) (x / len), (float) (y / len),
                (float) (z / len));
    }

    // ------------------------------------------------------------- the inverse

    /**
     * The azimuth of this direction, in the convention above.
     *
     * <p>Meaningless at elevation 90°, where the light is head-on and every
     * azimuth is the same direction; {@code atan2(0, 0)} is 0, so this reports
     * 0 there rather than throwing.
     */
    public float azimuthDegrees() {
        return normaliseAzimuth((float) Math.toDegrees(Math.atan2(y, x)));
    }

    /** The elevation of this direction, in the convention above. */
    public float elevationDegrees() {
        double clamped = Math.max(-1d, Math.min(1d, z));
        return normaliseElevation((float) Math.toDegrees(Math.asin(clamped)));
    }

    // ----------------------------------------------------------------- glide

    /**
     * Interpolate an azimuth the short way around the circle.
     *
     * <p>This is the one piece of the surface glide that needs care, and the
     * reason the glide carries angles rather than the vector. A plain lerp of
     * the <b>components</b> is not enough: 350°→10° lerps through 180° (a 340°
     * spin through every intermediate light), and two exactly opposite lights
     * (0° and 180° at elevation 0) pass through the zero vector, which has no
     * direction to normalize. Interpolating the angle with the shortest signed
     * delta travels the 20° the eye expects and stays well defined at both
     * ends.
     *
     * @param t 0 gives the from angle, 1 gives the to angle
     * @return the interpolated angle, already wrapped into [0, 360)
     */
    public static float lerpAzimuth(float fromDegrees, float toDegrees, float t) {
        float from = normaliseAzimuth(fromDegrees);
        float delta = normaliseAzimuth(toDegrees) - from;
        if (delta > 180f) {
            delta -= AZIMUTH_MAX;
        } else if (delta < -180f) {
            delta += AZIMUTH_MAX;
        }
        return normaliseAzimuth(from + delta * t);
    }

    // -------------------------------------------------------------- normalize

    /**
     * Wrap any finite azimuth into {@code [0, 360)}; a non-finite value becomes
     * {@link #DEFAULT_AZIMUTH_DEG}.
     */
    public static float normaliseAzimuth(float degrees) {
        if (Float.isNaN(degrees) || Float.isInfinite(degrees)) {
            return DEFAULT_AZIMUTH_DEG;
        }
        float wrapped = degrees % AZIMUTH_MAX;
        if (wrapped < 0f) wrapped += AZIMUTH_MAX;
        // -0.0f compares equal to 0f but prints as "-0.0" and would round-trip
        // as a different JSON text; collapse it so "0 means 0" is literally true.
        if (wrapped == 0f) return 0f;
        return wrapped;
    }

    /**
     * Clamp any elevation into {@code [0, 90]}; a non-finite value becomes
     * {@link #DEFAULT_ELEVATION_DEG}.
     */
    public static float normaliseElevation(float degrees) {
        if (Float.isNaN(degrees)) return DEFAULT_ELEVATION_DEG;
        if (degrees < ELEVATION_MIN) return ELEVATION_MIN;
        if (degrees > ELEVATION_MAX) return ELEVATION_MAX;
        return degrees;
    }

    @Override
    public String toString() {
        return "LightDirection{az=" + azimuthDegrees() + "° el="
                + elevationDegrees() + "° -> (" + x + ", " + y + ", " + z + ")}";
    }
}
