#!/usr/bin/env node
/**
 * The render-value mappings, under plain Node.
 *
 *   node --experimental-strip-types src/picture.test.ts
 *
 * `Picture.java`'s two mappings are tested in `:core` for exactly the reason
 * this file exists: a screenshot cannot tell an exponential brightness from a
 * linear one that happens to pass through 0, and a light direction that runs
 * the wrong way round looks plausible until the finger moves. The constants
 * (0 is the identity, ±100 is ±1 stop, azimuth runs counter-clockwise) are the
 * app's, and every one of them is a property the eye reads as wrong rather than
 * as broken.
 */
import assert from 'node:assert/strict';

import {
  DEFAULT_LIGHT_AZIMUTH,
  DEFAULT_LIGHT_ELEVATION,
  brightnessGain,
  lightDirection,
  normaliseAzimuth,
  normaliseElevation,
  shadowLift,
} from './picture.ts';

const near = (a: number, b: number, eps = 1e-6): boolean => Math.abs(a - b) <= eps;

// ------------------------------------------------------------- brightness

// 0 is the identity, and it is the one value every unadjusted photo stores.
assert.equal(brightnessGain(0), 1, 'brightness 0 is exactly the identity');

// One stop is a doubling, in both directions: a LINEAR mapping would answer 1.5
// and 0.5 here, pass the identity case and be visibly wrong at every other value
// — which is why this is asserted at ±100 and at a half stop.
assert.equal(brightnessGain(100), 2, '+100 is one stop up');
assert.equal(brightnessGain(-100), 0.5, '-100 is one stop down');
assert.ok(near(brightnessGain(50), Math.SQRT2), '+50 is a half stop up');

// Monotone, and always positive: a gain of zero or less would black the picture.
let last = 0;
for (let v = -100; v <= 100; v += 5) {
  const g = brightnessGain(v);
  assert.ok(g > 0, `brightness ${v} produced a non-positive gain`);
  assert.ok(g >= last, `brightness ${v} went backwards`);
  last = g;
}

// The clamp is the store's clamp: a value past either end is the end.
assert.equal(brightnessGain(400), brightnessGain(100), 'brightness clamps at +100');
assert.equal(brightnessGain(-400), brightnessGain(-100), 'brightness clamps at -100');

// ---------------------------------------------------------------- shadow

assert.equal(shadowLift(0), 0, 'shadow 0 is exactly the identity');
assert.equal(shadowLift(100), 1, '+100 lifts black all the way to white');
assert.equal(shadowLift(-100), -1, '-100 pushes the black point down');
assert.equal(shadowLift(50), 0.5, 'half the lift is half the value');
assert.equal(shadowLift(-50), -0.5, 'and signed, so "less contrast" is reachable');
assert.equal(shadowLift(400), 1, 'shadow clamps at +100');
assert.equal(shadowLift(-400), -1, 'shadow clamps at -100');

// The shader's mix, as arithmetic: at 0 it must not move a single channel, and
// white must stay white at any lift (that is what "leaves white at white" means).
for (const lift of [-1, -0.25, 0, 0.5, 1]) {
  const apply = (c: number): number => c + lift * (1 - c);
  if (lift === 0) {
    for (const c of [0, 0.25, 0.5, 0.8, 1]) assert.equal(apply(c), c, `identity at ${c}`);
  }
  assert.ok(near(apply(1), 1), `white stays white at lift ${lift}`);
}
// A positive lift raises the black point and a negative one lowers it.
assert.ok(shadowLift(50) * (1 - 0) > 0, 'a positive lift raises black');
assert.ok(shadowLift(-50) * (1 - 0) < 0, 'a negative lift deepens black');

// ----------------------------------------------------------------- light

// The default pair is the historical literal's own angles, so the resolved
// direction is that vector normalised. This is the whole "an upgrade cannot
// relight every photo" property, and it is asserted through the real mapping.
{
  const [x, y, z] = lightDirection(DEFAULT_LIGHT_AZIMUTH, DEFAULT_LIGHT_ELEVATION);
  const len = Math.sqrt(0.35 * 0.35 + 0.5 * 0.5 + 0.75 * 0.75);
  assert.ok(near(x, -0.35 / len), `default light x: ${x}`);
  assert.ok(near(y, 0.5 / len), `default light y: ${y}`);
  assert.ok(near(z, 0.75 / len), `default light z: ${z}`);
}

// A unit vector at every pair, including the clamps: a direction that is not
// unit length magnifies the diffuse term by its length.
for (const az of [-90, 0, 45, 124.992, 260, 359, 720]) {
  for (const el of [-30, 0, 45, 90, 120]) {
    const [x, y, z] = lightDirection(az, el);
    assert.ok(near(Math.hypot(x, y, z), 1, 1e-9), `unit length at ${az}/${el}`);
  }
}

// The convention, which is the part a flipped sign ruins: azimuth is
// counter-clockwise from +x (so 90° is +y, not -y), elevation 0 is in the plane
// and 90 is straight out of it.
{
  const [x0, y0, z0] = lightDirection(0, 0);
  assert.ok(near(x0, 1) && near(y0, 0) && near(z0, 0), '0°/0° is +x');
  const [x90, y90] = lightDirection(90, 0);
  assert.ok(near(x90, 0) && near(y90, 1), '90°/0° is +y — counter-clockwise');
  const [xe, ye, ze] = lightDirection(30, 90);
  assert.ok(near(xe, 0) && near(ye, 0) && near(ze, 1), '90° elevation is head-on');
}

// Azimuth WRAPS and elevation CLAMPS — one rule each, not the same one twice.
assert.equal(normaliseAzimuth(370), 10, 'azimuth wraps, it does not clamp');
assert.equal(normaliseAzimuth(-10), 350, 'and it wraps backwards');
assert.equal(normaliseAzimuth(360), 0, '360 is the same light as 0');
assert.equal(normaliseElevation(120), 90, 'elevation clamps at head-on');
assert.equal(normaliseElevation(-30), 0, 'elevation clamps at grazing');

// A NaN answers the default rather than a direction with a NaN in it: the app's
// rule is that a number that claims nothing must not reach a uniform.
assert.equal(normaliseAzimuth(Number.NaN), DEFAULT_LIGHT_AZIMUTH);
assert.equal(normaliseElevation(Number.NaN), DEFAULT_LIGHT_ELEVATION);
{
  const [x, y, z] = lightDirection(Number.NaN, Number.NaN);
  assert.ok(Number.isFinite(x) && Number.isFinite(y) && Number.isFinite(z), 'no NaN direction');
}

console.log('picture mappings: brightness, shadow and the light direction agree with :core');
