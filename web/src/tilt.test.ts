#!/usr/bin/env node
/**
 * The tilt sign convention, without a sensor.
 *
 * Android's `TiltController` maps `x = roll` and `y = -pitch`, so tilting the
 * phone up reveals the top of the scene. The reference port's `+beta` mapping was
 * vertically mirrored; these cases pin the corrected mapping for every screen
 * rotation.
 */
import assert from 'node:assert/strict';

import { tiltFromOrientation } from './tilt.ts';

let checks = 0;

function test(name: string, fn: () => void): void {
  fn();
  checks++;
  console.log(`ok   ${name}`);
}

function close(actual: number, expected: number, what: string): void {
  assert.ok(
    Math.abs(actual - expected) <= 1e-9,
    `${what}: expected ${expected}, got ${actual}`,
  );
}

test('portrait: up tilt reveals the top and right tilt moves right', () => {
  const tilt = tiltFromOrientation(0, 10, 5);
  close(tilt.x, 0.2, 'x');
  close(tilt.y, -0.4, 'y');
});

test('portrait: the vertical axis is not the reference port’s mirrored +beta', () => {
  close(tiltFromOrientation(0, -10, 0).y, 0.4, 'down tilt');
});

test('90 degrees: beta becomes the horizontal axis and vertical is negated', () => {
  const tilt = tiltFromOrientation(90, 10, 5);
  close(tilt.x, 0.4, 'x');
  close(tilt.y, -0.2, 'y');
});

test('180 degrees: both axes rotate and the vertical sign follows pitch', () => {
  const tilt = tiltFromOrientation(180, 10, 5);
  close(tilt.x, -0.2, 'x');
  close(tilt.y, 0.4, 'y');
});

test('270 degrees: the opposite landscape mapping', () => {
  const tilt = tiltFromOrientation(270, 10, 5);
  close(tilt.x, -0.4, 'x');
  close(tilt.y, 0.2, 'y');
});

test('both axes clamp to the [-1, 1] range', () => {
  const tilt = tiltFromOrientation(0, 100, -100);
  close(tilt.x, -1, 'x');
  close(tilt.y, -1, 'y');
});

console.log(`tilt: ${checks} checks passed`);
