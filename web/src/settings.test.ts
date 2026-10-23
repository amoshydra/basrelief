#!/usr/bin/env node
/**
 * The TypeScript half of `contract/settings/`, run under plain Node.
 *
 *   node --experimental-strip-types src/settings.test.ts
 *
 * The fixture is the only shared statement of the render-value body: the app
 * reads and writes it in `SurfaceSettings`, this page reads and writes it in
 * `settings.ts`, and `scripts/check-parity.mjs` holds the two copies of every
 * RANGE together with the table below. Nothing here touches a browser API, so
 * the test needs no install.
 *
 * What a wrong answer looks like, case by case:
 *
 *   - a range that drifts is a slider whose maximum the app cannot produce. The
 *     picture a visitor tunes is then not one the phone can render, and no test
 *     on either side can see the other copy — which is why the numbers are here
 *     and the script reads both sources.
 *   - an absent field read as 0 instead of its default is a photo that goes flat
 *     the first time a partial body is parsed. "Absent" and "zero" are different
 *     facts, exactly as they are for a depth map.
 *   - a clamp that clamps PAN is the bug `SurfaceSettings` documents: an 86.5
 *     pixel offset read through Origin's fraction range becomes 2, and the
 *     picture jumps.
 *   - a writer that emits its optional keys at their defaults rewrites every
 *     stored body on upgrade for no rendered difference.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  ADJUST_MAX,
  ADJUST_MIN,
  ADJUST_DEFAULT,
  AZIMUTH_MAX,
  AZIMUTH_MIN,
  DEPTH_DEFAULT,
  DEPTH_MAX,
  DEPTH_MIN,
  ELEVATION_MAX,
  ELEVATION_MIN,
  MOTION_DEFAULT,
  MOTION_MAX,
  MOTION_MIN,
  OPTIONAL_KEYS,
  ORIGIN_DEFAULT,
  ORIGIN_LIMIT,
  PAN_Z_DEFAULT,
  PAN_Z_MAX,
  PAN_Z_MIN,
  REQUIRED_KEYS,
  SETTINGS_SCHEMA,
  SPEED_DEFAULT,
  SPEED_MAX,
  SPEED_MIN,
  SPLAT_BLUR_DEFAULT,
  SPLAT_BLUR_MAX,
  SPLAT_BLUR_MIN,
  SPLAT_DEPTH_DEFAULT,
  SPLAT_DEPTH_MAX,
  SPLAT_DEPTH_MIN,
  SPLAT_EPS2D_DEFAULT,
  SPLAT_EPS2D_MAX,
  SPLAT_EPS2D_MIN,
  SPLAT_SCALE_DEFAULT,
  SPLAT_SCALE_MAX,
  SPLAT_SCALE_MIN,
  ZOOM_DEFAULT,
  ZOOM_MAX,
  ZOOM_MIN,
  defaultSettings,
  fromJson,
  toJson,
  type SurfaceSettings,
} from './settings.ts';

const here = dirname(fileURLToPath(import.meta.url));
const fixture = JSON.parse(
  readFileSync(join(here, '..', '..', 'contract', 'settings', 'cases.json'), 'utf8'),
) as {
  schema: number;
  why: string;
  approximately: { fields: string[]; tolerance: number; why: string };
  ranges: Record<string, { min: number; max: number; default?: number; why: string }>;
  defaults: { body: Record<string, unknown>; expected: Record<string, number>; why: string };
  populated: {
    body: Record<string, number>;
    expected: Record<string, number>;
    why: string;
  };
  clamps: Array<{ field: string; stored: number; expected: number; why: string }>;
  junk: Array<{ body: Record<string, unknown>; why: string }>;
};

/** The fixture's range keys, and the three constants each one must equal. */
const RANGE_CONSTANTS: Record<string, [number, number, number | null]> = {
  depth: [DEPTH_MIN, DEPTH_MAX, DEPTH_DEFAULT],
  zoom: [ZOOM_MIN, ZOOM_MAX, ZOOM_DEFAULT],
  motion: [MOTION_MIN, MOTION_MAX, MOTION_DEFAULT],
  adjust: [ADJUST_MIN, ADJUST_MAX, ADJUST_DEFAULT],
  origin: [-ORIGIN_LIMIT, ORIGIN_LIMIT, ORIGIN_DEFAULT],
  panZ: [PAN_Z_MIN, PAN_Z_MAX, PAN_Z_DEFAULT],
  speed: [SPEED_MIN, SPEED_MAX, SPEED_DEFAULT],
  splatDepth: [SPLAT_DEPTH_MIN, SPLAT_DEPTH_MAX, SPLAT_DEPTH_DEFAULT],
  splatEps2d: [SPLAT_EPS2D_MIN, SPLAT_EPS2D_MAX, SPLAT_EPS2D_DEFAULT],
  splatBlur: [SPLAT_BLUR_MIN, SPLAT_BLUR_MAX, SPLAT_BLUR_DEFAULT],
  splatScale: [SPLAT_SCALE_MIN, SPLAT_SCALE_MAX, SPLAT_SCALE_DEFAULT],
  // The light's two defaults are derived from the historical vector, so the
  // fixture states only the ranges; picture.test.ts holds the derivation.
  azimuth: [AZIMUTH_MIN, AZIMUTH_MAX, null],
  elevation: [ELEVATION_MIN, ELEVATION_MAX, null],
};

/** The config→JSON key map, which is the contract's spelling of each field. */
const JSON_KEY: Record<keyof SurfaceSettings, string> = {
  depth: 'depth',
  zoom: 'zoom',
  motion: 'motion',
  originX: 'origin_x',
  originY: 'origin_y',
  panX: 'pan_x',
  panY: 'pan_y',
  brightness: 'brightness',
  shadow: 'shadow',
  lightAzimuth: 'light_azimuth',
  lightElevation: 'light_elevation',
  speed: 'speed',
  splatDepth: 'splat_depth',
  splatEps2d: 'splat_eps2d',
  splatBlur: 'splat_blur',
  splatScale: 'splat_scale',
  panZ: 'pan_z',
};

const approx = new Set(fixture.approximately.fields);
const near = (a: number, b: number): boolean =>
  Math.abs(a - b) <= fixture.approximately.tolerance;

/** `expected` is in JSON spelling; compare it to a parsed settings object. */
function assertSettings(actual: SurfaceSettings, expected: Record<string, number>, what: string) {
  for (const [field, key] of Object.entries(JSON_KEY) as Array<[keyof SurfaceSettings, string]>) {
    const want = expected[key];
    assert.ok(want !== undefined, `${what}: expected has no ${key}`);
    if (approx.has(key)) {
      assert.ok(
        near(actual[field], want),
        `${what}: ${key} is ${actual[field]}, expected about ${want}`,
      );
    } else {
      assert.equal(actual[field], want, `${what}: ${key}`);
    }
  }
  // Nothing extra: a field the fixture does not state is a field this test is not
  // checking, which is how a value gets added to the schema with no guard.
  assert.deepEqual(
    Object.keys(actual).sort(),
    Object.keys(JSON_KEY).sort(),
    `${what}: field set`,
  );
}

// ---------------------------------------------------------------- the schema

assert.equal(fixture.schema, SETTINGS_SCHEMA, 'fixture schema versus SETTINGS_SCHEMA');
assert.ok(fixture.why.length > 0, 'the fixture must say why it exists');

// ----------------------------------------------------------------- the ranges
//
// Every range the fixture states must be the constant the sheet draws with. The
// same numbers are checked against the Java source by `scripts/check-parity.mjs`,
// so this is one claim in three places rather than three claims.

let rangeCount = 0;
for (const [name, range] of Object.entries(fixture.ranges)) {
  assert.ok(range.why, `range ${name} has no "why" — a fixture without a reason is a number`);
  const constants = RANGE_CONSTANTS[name];
  assert.ok(constants, `range ${name} has no constant mapping — add it here and to settings.ts`);
  const [min, max, def] = constants;
  assert.equal(range.min, min, `range ${name}: min`);
  assert.equal(range.max, max, `range ${name}: max`);
  if (range.default !== undefined) {
    assert.notEqual(def, null, `range ${name}: the fixture states a default but settings.ts has none`);
    assert.equal(range.default, def, `range ${name}: default`);
  }
  rangeCount++;
}
assert.equal(
  rangeCount,
  Object.keys(RANGE_CONSTANTS).length,
  'a range in settings.ts has no row in the fixture',
);

// -------------------------------------------------------------- absent fields

assertSettings(fromJson(fixture.defaults.body), fixture.defaults.expected, 'defaults');
// A body that is not an object at all is the same body: the app never throws on
// a corrupt blob, and neither does this reader.
assertSettings(fromJson(null), fixture.defaults.expected, 'defaults from null');
assertSettings(fromJson([]), fixture.defaults.expected, 'defaults from an array');

// ------------------------------------------------------------- the populated

assertSettings(fromJson(fixture.populated.body), fixture.populated.expected, 'populated');

// The writer must give back the body it read, key for key: a round trip that
// dropped an optional value or rounded a float is a stored picture that changes
// when it is re-saved.
assert.deepEqual(
  toJson(fromJson(fixture.populated.body)),
  fixture.populated.body,
  'the populated body survives parse → write',
);

// ------------------------------------------------ which keys the writer emits

const defaultBody = toJson(defaultSettings());
assert.deepEqual(
  Object.keys(defaultBody).sort(),
  [...REQUIRED_KEYS].sort(),
  'a default body writes the twelve required keys and none of the optional ones',
);
for (const key of OPTIONAL_KEYS) {
  assert.equal(defaultBody[key], undefined, `a default body must not write ${key}`);
}
// ...and the required keys are exactly the twelve the fixture expects, so a key
// added to one list without the other is a red build rather than a silent one.
assert.deepEqual(
  [...REQUIRED_KEYS].sort(),
  Object.keys(fixture.defaults.expected)
    .filter((k) => !OPTIONAL_KEYS.includes(k as (typeof OPTIONAL_KEYS)[number]))
    .sort(),
  'REQUIRED_KEYS and the fixture disagree about the always-written set',
);

// ----------------------------------------------------------------- the clamps

assert.ok(fixture.clamps.length >= 20, 'the clamp table shrank — every field needs its bound held');
for (const c of fixture.clamps) {
  assert.ok(c.why, `clamp ${c.field} (${c.stored}) has no "why"`);
  const parsed = fromJson({ [c.field]: c.stored });
  const actual = parsed[fieldOf(c.field)];
  if (approx.has(c.field)) {
    assert.ok(near(actual, c.expected), `${c.field} ${c.stored}: ${actual} vs ${c.expected}`);
  } else {
    assert.equal(actual, c.expected, `${c.field} stored ${c.stored}`);
  }
}

// ------------------------------------------------------------- corrupt bodies

for (const junk of fixture.junk) {
  assert.ok(junk.why, 'a junk case with no "why" is not a case');
  const parsed = fromJson(junk.body);
  // Every field answers its default. This is the state a half-written blob
  // leaves, and it must render a picture rather than throw before one is drawn.
  assertSettings(parsed, fixture.defaults.expected, 'junk body');
}

function fieldOf(key: string): keyof SurfaceSettings {
  for (const [field, json] of Object.entries(JSON_KEY) as Array<[keyof SurfaceSettings, string]>) {
    if (json === key) return field;
  }
  throw new Error(`no settings field for JSON key ${key}`);
}

console.log(
  `settings contract: ${rangeCount} ranges, ${fixture.clamps.length} clamps and ` +
    `${Object.keys(JSON_KEY).length} fields agree with web/src/settings.ts`,
);
