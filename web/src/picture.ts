/**
 * The two render-value mappings that are pure arithmetic.
 *
 * `Picture.java` turns a stored brightness/shadow into the scalars the fragment
 * shader consumes, and `LightDirection.java` turns two angles into the unit
 * vector the light is. Both are in `:core` in the app precisely because the
 * interesting half is the MAPPING and a screenshot cannot check it: a brightness
 * that is linear instead of exponential passes a naive identity test while being
 * visibly wrong, and a light angle that runs the wrong way round is a parallax
 * cue pointing backwards.
 *
 * This page's relief renderer applies the result inside three.js's own lighting,
 * so the pixels are not the app's — the *arithmetic* is, and it is held to the
 * app's by these tests and by the constants in `settings.ts`. There is
 * deliberately no curve here: `Picture` is one multiply and one mix, and the
 * shader-side applyments in `relief.ts` are those same two lines.
 */

/** Stored units are hundredths, matching Depth's slider convention. */
const HUNDREDTHS = 100;

/** `Picture.ADJUST_MIN`/`MAX`; restated here so this module stands alone. */
const ADJUST_MIN = -100;
const ADJUST_MAX = 100;

/**
 * Brightness as a multiplier, from hundredths of a stop.
 *
 * An exposure offset rather than an addition, because that is what a stop is:
 * +100 is one stop and doubles the image, -100 halves it. Adding a constant
 * instead would brighten shadows far more than highlights, which is not what
 * anyone means by "brighter". 0 is the identity.
 */
export function brightnessGain(brightness: number): number {
  const stops = clamp(brightness) / HUNDREDTHS;
  return Math.pow(2, stops);
}

/**
 * Shadow as a lift amount, from hundredths.
 *
 * Positive lifts the black point and leaves white at white; negative deepens
 * the blacks and likewise leaves white alone. The renderer applies it as
 * `color + lift * (1 - color)`, the cheap approximation of a lift that ignores
 * the tone curve entirely. 0 is the identity, which is the whole contract: an
 * unadjusted photo renders exactly as it was imported.
 */
export function shadowLift(shadow: number): number {
  return clamp(shadow) / HUNDREDTHS;
}

/**
 * The unit vector for a pair of light angles, mirroring `LightDirection.of`.
 *
 * Azimuth is counter-clockwise from `+x` and wraps into [0, 360); elevation is
 * above the picture plane and clamps into [0, 90]. The default pair is
 * `(-0.35, 0.5, 0.75)`'s own angles, so the default direction is that vector
 * normalised — which is the property that stops an upgrade relighting every
 * picture by a fraction of a degree.
 */
export function lightDirection(
  azimuthDegrees: number,
  elevationDegrees: number,
): [number, number, number] {
  const az = (normaliseAzimuth(azimuthDegrees) * Math.PI) / 180;
  const el = (normaliseElevation(elevationDegrees) * Math.PI) / 180;
  const cosEl = Math.cos(el);
  return [cosEl * Math.cos(az), cosEl * Math.sin(az), Math.sin(el)];
}

/**
 * Azimuth wraps: 0° and 360° are the same light, so a clamp would create a dead
 * zone at the seam. A non-finite angle answers the default rather than a
 * direction with a NaN in it.
 */
export function normaliseAzimuth(degrees: number): number {
  if (!Number.isFinite(degrees)) return DEFAULT_LIGHT_AZIMUTH;
  // The same arithmetic as `LightDirection.normaliseAzimuth`: take the remainder,
  // and only add a turn when it is negative. The `((x % 360) + 360) % 360` form
  // looks equivalent and is not — adding 360 to an in-range value re-rounds it one
  // ulp, so a stored azimuth would change on every save and a blob would never be
  // a fixed point. The Java copy already took this form; this is the copy that was
  // wrong.
  const wrapped = degrees % 360;
  const positive = wrapped < 0 ? wrapped + 360 : wrapped;
  // -0 compares equal to 0 and would round-trip as a different JSON text.
  return positive === 0 ? 0 : positive;
}

/** Elevation clamps: 0° rakes across the relief, 90° is head-on. */
export function normaliseElevation(degrees: number): number {
  if (!Number.isFinite(degrees)) return DEFAULT_LIGHT_ELEVATION;
  return Math.max(0, Math.min(90, degrees));
}

/** The default angles, derived from the historical light literal. */
export const DEFAULT_LIGHT_AZIMUTH = (Math.atan2(0.5, -0.35) * 180) / Math.PI;
export const DEFAULT_LIGHT_ELEVATION =
  (Math.asin(0.75 / Math.sqrt(0.35 * 0.35 + 0.5 * 0.5 + 0.75 * 0.75)) * 180) / Math.PI;

function clamp(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return value < ADJUST_MIN ? ADJUST_MIN : value > ADJUST_MAX ? ADJUST_MAX : value;
}
