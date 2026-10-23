#!/usr/bin/env node
/**
 * The relief's camera, without a browser: `CameraModel.frame` and
 * `screenToOrbit`, one platform over.
 *
 *   node --experimental-strip-types src/camera.test.ts
 *
 * Why these four properties and not more:
 *
 *   - **At rest the picture is the photograph, translated by Pan alone**, at
 *     every origin, every pivot height, every Depth and every Zoom. This is the
 *     app's deliberate reversal (`AGENTS.md`: Origin moves the orbit centre and
 *     NOT the picture), and it is the property a tap-to-origin feature breaks
 *     first.
 *   - **The pivot keeps its own screen position under a tilt** — it is the
 *     rotation centre — while a point off it moves. That is what "the subject
 *     under the origin holds while the background swings" means.
 *   - **Pan is a clip-space shift at every depth**, not a camera move.
 *   - **A tap round-trips**: a plane point projected to NDC, unprojected back,
 *     is the same Origin fraction — through the real matrices, never a restated
 *     formula.
 */
import assert from 'node:assert/strict';

import {
  heightAt,
  invert,
  projectWorld,
  reliefCamera,
  reliefScale,
  reliefScreenToOrbit,
  type ReliefFraming,
} from './camera.ts';
import { multiply } from './splat.ts';

let checks = 0;

function test(name: string, fn: () => void): void {
  fn();
  checks++;
  console.log(`ok   ${name}`);
}

function close(actual: number, expected: number, tolerance: number, what = ''): void {
  assert.ok(
    Math.abs(actual - expected) <= tolerance,
    `${what} expected ${expected}, got ${actual} (tolerance ${tolerance})`,
  );
}

function framing(overrides: Partial<ReliefFraming> = {}): ReliefFraming {
  return {
    imageAspect: 0.75,
    surfaceAspect: 0.5,
    zoom: 1,
    depth: 0.9,
    pivotHeight: 0.2,
    motion: 1,
    camDist: 1,
    orbitX: 0,
    orbitY: 0,
    panX: 0,
    panY: 0,
    tiltX: 0,
    tiltY: 0,
    surfaceWidth: 1000,
    surfaceHeight: 2000,
    ...overrides,
  };
}

/** A plane point in model space, from an Origin fraction. */
function planePoint(f: ReliefFraming, ox: number, oy: number): [number, number, number] {
  const scale = reliefScale(f.imageAspect, f.surfaceAspect, f.zoom);
  return [ox * f.imageAspect * scale, oy * scale, 0];
}

test('at rest the picture is the photograph at every origin, pivot height, depth and zoom', () => {
  for (const zoom of [0.25, 1, 2]) {
    for (const depth of [0, 0.9, 1]) {
      for (const originX of [-1.5, -0.3, 0, 0.4, 1.9]) {
        for (const originY of [-1.2, 0, 0.8]) {
          for (const pivotHeight of [-0.5, 0, 0.5]) {
            const f = framing({ zoom, depth, orbitX: originX, orbitY: originY, pivotHeight });
            const reference = framing({ zoom, depth, orbitX: 0, orbitY: 0, pivotHeight: 0 });
            const camera = reliefCamera(f);
            const base = reliefCamera(reference);
            for (const [ox, oy] of [
              [0, 0],
              [0.5, 0.5],
              [-0.5, -0.5],
              [0.31, -0.17],
            ] as const) {
              const p = planePoint(f, ox, oy);
              const a = projectWorld(camera, ...p);
              const b = projectWorld(base, ...p);
              assert.ok(a && b);
              close(a[0], b[0], 1e-6, `origin ${originX},${originY} pivot ${pivotHeight}`);
              close(a[1], b[1], 1e-6, `origin ${originX},${originY} pivot ${pivotHeight}`);
            }
          }
        }
      }
    }
  }
});

test('the tilt is a turntable about the pivot: the pivot holds, the eye keeps its distance', () => {
  const rest = reliefCamera(framing({ tiltX: 0, tiltY: 0, orbitX: 0.2, orbitY: -0.1 }));
  const tilted = reliefCamera(framing({ tiltX: 0.8, tiltY: -0.6, orbitX: 0.2, orbitY: -0.1 }));

  const a = projectWorld(rest, ...rest.pivot);
  const b = projectWorld(tilted, ...tilted.pivot);
  assert.ok(a && b);
  close(a[0], b[0], 1e-5, 'pivot x');
  close(a[1], b[1], 1e-5, 'pivot y');

  const distance = (p: readonly number[], q: readonly number[]) =>
    Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
  const restEye = [0, 0, 1.2];
  close(
    distance(tilted.eye, tilted.pivot),
    distance(restEye, rest.pivot),
    1e-6,
    'the eye keeps its distance to the pivot',
  );

  // The picture really moves: a point away from the pivot has a new NDC.
  const p = planePoint(framing(), 0.4, 0.4);
  const before = projectWorld(rest, ...p);
  const after = projectWorld(tilted, ...p);
  assert.ok(before && after);
  assert.ok(Math.hypot(before[0] - after[0], before[1] - after[1]) > 0.01);
});

test('Pan is a clip-space shift at every depth, and moves no camera', () => {
  const plain = reliefCamera(framing({ panX: 0, panY: 0 }));
  const panned = reliefCamera(framing({ panX: 120, panY: -45, surfaceWidth: 1000, surfaceHeight: 2000 }));
  assert.deepEqual(Array.from(panned.eye), Array.from(plain.eye));

  const expectedX = (-2 * 120) / 1000;
  const expectedY = (2 * -45) / 2000;
  for (const [ox, oy] of [
    [0, 0],
    [0.5, 0.5],
    [-0.4, 0.2],
  ] as const) {
    const a = projectWorld(plain, ...planePoint(framing(), ox, oy));
    const b = projectWorld(panned, ...planePoint(framing(), ox, oy));
    assert.ok(a && b);
    close(b[0] - a[0], expectedX, 1e-6);
    close(b[1] - a[1], expectedY, 1e-6);
  }
});

test('a tap round-trips through the real matrices to the Origin it names', () => {
  for (const tiltX of [0, 0.6]) {
    for (const tiltY of [0, -0.4]) {
      for (const panX of [0, 87]) {
        const f = framing({ tiltX, tiltY, panX, orbitX: 0.13, orbitY: -0.22 });
        const camera = reliefCamera(f);
        for (const [ox, oy] of [
          [0.2, 0.1],
          [-0.35, -0.4],
          [0, 0],
        ] as const) {
          const ndc = projectWorld(camera, ...planePoint(f, ox, oy));
          assert.ok(ndc);
          const back = reliefScreenToOrbit(camera, ndc[0], ndc[1], f.imageAspect, f.surfaceAspect, f.zoom);
          assert.ok(back);
          // The matrices are Float32, so the round trip's floor is its epsilon.
          close(back[0], ox, 1e-5, `tap x (tilt ${tiltX}, pan ${panX})`);
          close(back[1], oy, 1e-5, `tap y (tilt ${tiltY}, pan ${panX})`);
        }
      }
    }
  }
});

test('a tap outside the picture names nothing rather than an origin beside it', () => {
  const f = framing();
  const camera = reliefCamera(f);
  // NDC beyond the plane's edge at every framing: the corner of the viewport,
  // with the picture not filling it. The 8% crop means ±1 is inside; use a
  // deliberately eccentric camera height instead to miss the plane.
  const scale = reliefScale(f.imageAspect, f.surfaceAspect, f.zoom);
  const outside = projectWorld(camera, 3 * f.imageAspect * scale, 0, 0);
  assert.ok(outside);
  assert.equal(
    reliefScreenToOrbit(camera, outside[0], outside[1], f.imageAspect, f.surfaceAspect, f.zoom),
    null,
  );
});

test('heightAt: row 0 is the image top, bilinear, clamped, and 0 without a field', () => {
  // 4x3 field, each row a distinct value.
  const w = 4;
  const h = 3;
  const field = new Float32Array([
    1, 1, 1, 1,
    0.5, 0.5, 0.5, 0.5,
    0, 0, 0, 0,
  ]);
  // oy = +0.5 is the top row.
  close(heightAt(field, w, h, 0, 0.5), 1, 1e-6);
  close(heightAt(field, w, h, 0, 0), 0.5, 1e-6);
  close(heightAt(field, w, h, 0, -0.5), 0, 1e-6);
  // Between rows, halfway: (1 + 0.5) / 2.
  close(heightAt(field, w, h, 0, 0.25), 0.75, 1e-6);
  // Clamped at the edge rather than reading past it.
  close(heightAt(field, w, h, 9, 9), 1, 1e-6);
  assert.equal(heightAt(null, w, h, 0, 0), 0);
  assert.equal(heightAt(field, 0, 0, 0, 0), 0);
});

test('the matrix inverse is the inverse the tap depends on', () => {
  const camera = reliefCamera(framing({ tiltX: 0.4, tiltY: -0.3, panX: 40 }));
  const inverse = invert(camera.viewProjection);
  assert.ok(inverse);
  const product = multiply(camera.viewProjection, inverse);
  for (let i = 0; i < 16; i++) {
    close(product[i], i % 5 === 0 ? 1 : 0, 1e-4, `identity[${i}]`);
  }
});

console.log(`\ncamera: ${checks} checks passed`);
