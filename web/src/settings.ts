/**
 * The app's render-value body, as the same bytes it stores.
 *
 * `SurfaceSettings.java` is the app's storage for one photo on one surface: a
 * versioned JSON object of seventeen values, every one of them clamped where it
 * is constructed. This module is that schema's second reader, and
 * `contract/settings/cases.json` is the only shared statement of the bodies the
 * two must agree on — the app's own tests cannot see TypeScript, so the numbers
 * live in the fixture and `scripts/check-parity.mjs` holds the Java constants,
 * the constants below and the fixture together.
 *
 * Why the web needs it at all: the studio's sheet is a set of controls over
 * these values, and this page mirrors the sheet. A slider whose maximum differs
 * from the app's would let a visitor tune a picture the phone cannot produce,
 * which is the same lie as a button that does nothing — so the ranges are the
 * app's, not this page's taste.
 *
 * **Absent means the default, never zero.** The app parses this body on the GL
 * thread at wallpaper cold start, so `fromJson` never throws: a value that is
 * not a finite number answers its documented default. That is the rule every
 * three-state reader in this repository follows ("a window claims nothing"),
 * applied to a stored number.
 *
 * **Pan is in pixels and is deliberately NOT clamped.** Unlike every other
 * value, its bound is the surface's own larger dimension, which this layer
 * cannot know; the app's `SurfaceSettings` documents the bug that a clamp to
 * Origin's fraction range caused (a stored −135 px became −0.5 px). The viewer
 * carries Pan as a live row and as a gesture, so its bound is applied where the
 * surface is: `ReliefRenderer.panBy`'s `-limit..limit`.
 */
import {
  DEFAULT_LIGHT_AZIMUTH,
  DEFAULT_LIGHT_ELEVATION,
  normaliseAzimuth,
  normaliseElevation,
} from './picture.ts';

/** The Library blob's schema version the body belongs to (`Library.VERSION`). */
export const SETTINGS_SCHEMA = 5;

// --------------------------------------------------------------------- bounds
//
// Every one of these is `SurfaceSettings`'s (or the `Splat`/`LightDirection`
// constant it reads), and `check-parity` fails if the two copies disagree.

/** Depth, as the slider's 0..100. */
export const DEPTH_MIN = 0;
export const DEPTH_MAX = 100;
export const DEPTH_DEFAULT = 90;

/** Zoom, as a percentage of 1.0x. Below 25% the camera would invert. */
export const ZOOM_MIN = 25;
export const ZOOM_MAX = 200;
export const ZOOM_DEFAULT = 100;

/** Motion, 0..100. The renderer scales it to 0..3. */
export const MOTION_MIN = 0;
export const MOTION_MAX = 100;
export const MOTION_DEFAULT = 33;

/** Brightness and shadow, signed offsets; 0 is the identity for both. */
export const ADJUST_MIN = -100;
export const ADJUST_MAX = 100;
export const ADJUST_DEFAULT = 0;

/**
 * Origin is a FRACTION of the plane and may sit outside the picture: it names
 * the orbit centre, not a position on the photo. ±2 picture-widths is the
 * control's travel, not a bound of the model.
 */
export const ORIGIN_LIMIT = 2;
export const ORIGIN_DEFAULT = 0;

/** Playback speed in percent. Clips only; the web draws no clips. */
export const SPEED_MIN = 25;
export const SPEED_MAX = 200;
export const SPEED_DEFAULT = 100;

/** Pan Z: the camera distance as a multiplier. 1.0 is the source camera. */
export const PAN_Z_MIN = 0.5;
export const PAN_Z_MAX = 5;
export const PAN_Z_DEFAULT = 1;

/** The splat look, phase-2 on this page but part of the body it must read. */
export const SPLAT_DEPTH_MIN = 0;
export const SPLAT_DEPTH_MAX = 5;
export const SPLAT_DEPTH_DEFAULT = 1;
export const SPLAT_EPS2D_MIN = 0;
export const SPLAT_EPS2D_MAX = 0.8;
export const SPLAT_EPS2D_DEFAULT = 0.5;
export const SPLAT_BLUR_MIN = 0;
export const SPLAT_BLUR_MAX = 12;
export const SPLAT_BLUR_DEFAULT = 0;
export const SPLAT_SCALE_MIN = 0.1;
export const SPLAT_SCALE_MAX = 1;
export const SPLAT_SCALE_DEFAULT = 1;

/** Azimuth wraps here: 0° and 360° are the same light. Elevation clamps. */
export const AZIMUTH_MIN = 0;
export const AZIMUTH_MAX = 360;
export const ELEVATION_MIN = 0;
export const ELEVATION_MAX = 90;

// --------------------------------------------------------------------- schema

export interface SurfaceSettings {
  depth: number;
  zoom: number;
  motion: number;
  originX: number;
  originY: number;
  /** Pixels. Deliberately unclamped; see the module comment. */
  panX: number;
  /** Pixels. Deliberately unclamped; see the module comment. */
  panY: number;
  brightness: number;
  shadow: number;
  /** Degrees in [0, 360). */
  lightAzimuth: number;
  /** Degrees in [0, 90]. */
  lightElevation: number;
  speed: number;
  splatDepth: number;
  splatEps2d: number;
  splatBlur: number;
  splatScale: number;
  panZ: number;
}

/** Everything at its default: a photo nobody has adjusted. */
export function defaultSettings(): SurfaceSettings {
  return {
    depth: DEPTH_DEFAULT,
    zoom: ZOOM_DEFAULT,
    motion: MOTION_DEFAULT,
    originX: ORIGIN_DEFAULT,
    originY: ORIGIN_DEFAULT,
    panX: 0,
    panY: 0,
    brightness: ADJUST_DEFAULT,
    shadow: ADJUST_DEFAULT,
    lightAzimuth: DEFAULT_LIGHT_AZIMUTH,
    lightElevation: DEFAULT_LIGHT_ELEVATION,
    speed: SPEED_DEFAULT,
    splatDepth: SPLAT_DEPTH_DEFAULT,
    splatEps2d: SPLAT_EPS2D_DEFAULT,
    splatBlur: SPLAT_BLUR_DEFAULT,
    splatScale: SPLAT_SCALE_DEFAULT,
    panZ: PAN_Z_DEFAULT,
  };
}

/**
 * Read one surface's settings, defaulting every absent field and clamping every
 * present one. Never throws and never returns null: this body is read on the
 * wallpaper's cold-start path in the app, and on this page it is read before the
 * sheet is built, so a corrupt value has to resolve to a picture rather than to
 * an exception.
 */
export function fromJson(body: unknown): SurfaceSettings {
  const o = isRecord(body) ? body : {};
  return {
    depth: clampInt(asInt(o.depth, DEPTH_DEFAULT), DEPTH_MIN, DEPTH_MAX),
    zoom: clampInt(asInt(o.zoom, ZOOM_DEFAULT), ZOOM_MIN, ZOOM_MAX),
    motion: clampInt(asInt(o.motion, MOTION_DEFAULT), MOTION_MIN, MOTION_MAX),
    originX: clampFloat(asFloat(o.origin_x, ORIGIN_DEFAULT), -ORIGIN_LIMIT, ORIGIN_LIMIT),
    originY: clampFloat(asFloat(o.origin_y, ORIGIN_DEFAULT), -ORIGIN_LIMIT, ORIGIN_LIMIT),
    panX: asFloat(o.pan_x, 0),
    panY: asFloat(o.pan_y, 0),
    brightness: clampInt(asInt(o.brightness, ADJUST_DEFAULT), ADJUST_MIN, ADJUST_MAX),
    shadow: clampInt(asInt(o.shadow, ADJUST_DEFAULT), ADJUST_MIN, ADJUST_MAX),
    lightAzimuth: normaliseAzimuth(asFloat(o.light_azimuth, DEFAULT_LIGHT_AZIMUTH)),
    lightElevation: normaliseElevation(asFloat(o.light_elevation, DEFAULT_LIGHT_ELEVATION)),
    speed: clampInt(asInt(o.speed, SPEED_DEFAULT), SPEED_MIN, SPEED_MAX),
    splatDepth: clampFloat(
      finiteOr(asFloat(o.splat_depth, SPLAT_DEPTH_DEFAULT), SPLAT_DEPTH_DEFAULT),
      SPLAT_DEPTH_MIN,
      SPLAT_DEPTH_MAX,
    ),
    splatEps2d: clampFloat(
      finiteOr(asFloat(o.splat_eps2d, SPLAT_EPS2D_DEFAULT), SPLAT_EPS2D_DEFAULT),
      SPLAT_EPS2D_MIN,
      SPLAT_EPS2D_MAX,
    ),
    splatBlur: clampFloat(
      finiteOr(asFloat(o.splat_blur, SPLAT_BLUR_DEFAULT), SPLAT_BLUR_DEFAULT),
      SPLAT_BLUR_MIN,
      SPLAT_BLUR_MAX,
    ),
    splatScale: clampFloat(
      finiteOr(asFloat(o.splat_scale, SPLAT_SCALE_DEFAULT), SPLAT_SCALE_DEFAULT),
      SPLAT_SCALE_MIN,
      SPLAT_SCALE_MAX,
    ),
    panZ: clampFloat(
      finiteOr(asFloat(o.pan_z, PAN_Z_DEFAULT), PAN_Z_DEFAULT),
      PAN_Z_MIN,
      PAN_Z_MAX,
    ),
  };
}

/**
 * The body the app would store.
 *
 * Two rules from `SurfaceSettings.toJson` are load-bearing and mirrored here:
 * the twelve values the schema always writes go out even at their defaults, and
 * the splat look and Pan Z are written **only when they differ from the
 * default** — so an item nobody opened the cloud rows on stays byte-identical
 * across an upgrade. A writer that always wrote them would change every stored
 * library's bytes for no rendered difference.
 */
export function toJson(s: SurfaceSettings): Record<string, number> {
  const o: Record<string, number> = {
    depth: s.depth,
    zoom: s.zoom,
    motion: s.motion,
    origin_x: s.originX,
    origin_y: s.originY,
    pan_x: s.panX,
    pan_y: s.panY,
    brightness: s.brightness,
    shadow: s.shadow,
    light_azimuth: s.lightAzimuth,
    light_elevation: s.lightElevation,
    speed: s.speed,
  };
  if (s.splatDepth !== SPLAT_DEPTH_DEFAULT) o.splat_depth = s.splatDepth;
  if (s.splatEps2d !== SPLAT_EPS2D_DEFAULT) o.splat_eps2d = s.splatEps2d;
  if (s.splatBlur !== SPLAT_BLUR_DEFAULT) o.splat_blur = s.splatBlur;
  if (s.splatScale !== SPLAT_SCALE_DEFAULT) o.splat_scale = s.splatScale;
  if (s.panZ !== PAN_Z_DEFAULT) o.pan_z = s.panZ;
  return o;
}

/** The twelve keys the writer always emits, in its own order. */
export const REQUIRED_KEYS = [
  'depth',
  'zoom',
  'motion',
  'origin_x',
  'origin_y',
  'pan_x',
  'pan_y',
  'brightness',
  'shadow',
  'light_azimuth',
  'light_elevation',
  'speed',
] as const;

/** The five written only when they differ from their default. */
export const OPTIONAL_KEYS = [
  'splat_depth',
  'splat_eps2d',
  'splat_blur',
  'splat_scale',
  'pan_z',
] as const;

// --------------------------------------------------------------------- utils

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `JSONObject.optInt`: a number truncates toward zero, anything else defaults. */
function asInt(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : fallback;
}

/** `JSONObject.optDouble`: a number is itself, anything else defaults. */
function asFloat(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function finiteOr(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback;
}

export function clampInt(value: number, lo: number, hi: number): number {
  return value < lo ? lo : value > hi ? hi : value;
}

export function clampFloat(value: number, lo: number, hi: number): number {
  return value < lo ? lo : value > hi ? hi : value;
}
