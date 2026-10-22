#!/usr/bin/env node
/**
 * The `.splat` record, the cloud's camera, the lens and the sort — without a
 * browser.
 *
 *   node --experimental-strip-types src/splat.test.ts
 *
 * This is the gallery's half of `contract/splat/`, and the reason that directory
 * is a contract at all: the 32-byte record is read by Java
 * (`SplatCloud.java`) and now by TypeScript, and the two copies cannot share
 * code, so they share bytes. Each test below is one of the failures that would
 * otherwise be invisible:
 *
 *   - a colour path that is not integer texels (the bright record is a float NaN);
 *   - a cloud drawn in file order (a background splat paints over the subject);
 *   - a camera whose at-rest view is not the photograph;
 *   - a guessed lens (a cloud drawn through the wrong camera looks fine);
 *   - a truncated record drawn as a shorter cloud.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  ALPHA_DISCARD,
  DATA_WIDTH,
  EPS_2D_DEFAULT,
  NO_LENS_FOVY_DEG,
  SORT_BUCKETS,
  SPLAT_RECORD_BYTES,
  CAM_DIST,
  TILT_RANGE,
  cloudCamera,
  createDepthOrder,
  eps2dFor,
  focalOverHeight,
  fovyForLens,
  medianDepth,
  packIntegerTexels,
  parseSidecar,
  pivotAt,
  positionsOf,
  project,
  readRecord,
  recordCount,
  sourceFovy,
  sourceNdc,
} from './splat.ts';

const here = dirname(fileURLToPath(import.meta.url));
const contractDir = join(here, '..', '..', 'contract');

interface FixtureRecord {
  name: string;
  index: number;
  why: string;
  position: number[];
  scale: number[];
  color: number[];
  quaternion: number[];
  texels: number[][];
  colourWordIsNaNAsFloat?: boolean;
}

interface SplatCases {
  recordBytes: number;
  sourceRecords: number;
  records: number;
  sourceColourWordsAsNaN: number;
  fixtureSha256: string;
  recordsList: FixtureRecord[];
}

const cases: SplatCases = JSON.parse(
  readFileSync(join(contractDir, 'splat', 'cases.json'), 'utf8'),
) as SplatCases;
const fixture = new Uint8Array(readFileSync(join(contractDir, 'splat', 'records.bin')));

let count = 0;
function test(name: string, fn: () => void): void {
  try {
    fn();
  } catch (err) {
    console.error(`FAIL ${name}`);
    throw err;
  }
  count++;
}

const close = (a: number, b: number, tol = 1e-6) =>
  assert.ok(Math.abs(a - b) <= tol * Math.max(1, Math.abs(a), Math.abs(b)), `${a} != ${b}`);

// ---------------------------------------------------------------- the record

test('the record is 32 bytes and the fixture is a whole number of them', () => {
  assert.equal(cases.recordBytes, SPLAT_RECORD_BYTES);
  assert.equal(fixture.length, cases.records * SPLAT_RECORD_BYTES);
  assert.equal(recordCount(fixture.length), cases.records);
  // A partial tail is not a shorter cloud: it is dropped by the count and
  // refused by the packer below.
  assert.equal(recordCount(fixture.length + 17), cases.records);
});

test('every fixture record decodes to the fields cases.json states', () => {
  const positions = positionsOf(fixture);
  cases.recordsList.forEach((expected, i) => {
    const r = readRecord(fixture, i);
    for (let k = 0; k < 3; k++) {
      close(r.position[k], expected.position[k], 0);
      close(r.scale[k], expected.scale[k], 0);
      assert.equal(positions[i * 3 + k], r.position[k], `${expected.name}: positions mirror`);
    }
    assert.deepEqual([...r.color], expected.color, `${expected.name}: colour bytes`);
    assert.deepEqual([...r.quaternion], expected.quaternion, `${expected.name}: quaternion bytes`);
  });
});

test('the packing is two integer texels per record, byte for byte', () => {
  const packed = packIntegerTexels(fixture);
  assert.equal(packed.count, cases.records);
  assert.equal(packed.width, DATA_WIDTH);
  assert.equal(packed.height, Math.ceil((cases.records * 2) / DATA_WIDTH));
  assert.equal(packed.texels.length, packed.width * packed.height * 4);

  cases.recordsList.forEach((expected, i) => {
    const a = packed.texels.subarray(i * 8, i * 8 + 4);
    const b = packed.texels.subarray(i * 8 + 4, i * 8 + 8);
    assert.deepEqual([...a], expected.texels[0], `${expected.name}: texel A`);
    assert.deepEqual([...b], expected.texels[1], `${expected.name}: texel B`);
  });

  // Zero-padding past the last splat: the texture is whole rows, and a shader
  // that read past `count` must read zeros rather than a neighbour's record.
  for (let i = cases.records * 8; i < packed.texels.length; i++) {
    assert.equal(packed.texels[i], 0);
  }
});

test('a bright record survives as integers and is a NaN as a float', () => {
  const bright = cases.recordsList.find((r) => r.name === 'bright');
  assert.ok(bright, 'the fixture lost its bright record');
  const i = cases.recordsList.indexOf(bright);
  const [r, g, b, a] = bright.color;

  // The integer path: the shader's own unpack, `u & 0xFF`, `(u >> 8) & 0xFF`, …
  const word = packIntegerTexels(fixture).texels[i * 8 + 6];
  assert.equal(word, (r | (g << 8) | (b << 16) | (a << 24)) >>> 0, 'the word is the four bytes');
  assert.equal(word & 0xff, r);
  assert.equal((word >>> 8) & 0xff, g);
  assert.equal((word >>> 16) & 0xff, b);
  assert.equal((word >>> 24) & 0xff, a);
  assert.equal(word & 0x7f000000, 0x7f000000, 'alpha is 255, so the top exponent bit is set');

  // The float path, which the prototype took: the same four bytes read as one
  // RGBA32F texel are a NaN, and `floatBitsToUint` of a canonical NaN does not
  // return these bytes. That is the flat-white speckle on Mali, and it is why the
  // texture is RGBA32UI.
  const asFloat = new DataView(fixture.buffer, fixture.byteOffset + i * SPLAT_RECORD_BYTES, 32)
    .getFloat32(24, true);
  assert.ok(Number.isNaN(asFloat), 'a float upload would carry a NaN here');
  assert.equal(bright.colourWordIsNaNAsFloat, true);

  // The trap is common, not a corner: cases.json states how much of the demo
  // cloud it covers, so a fixture that lost it is visible as a number.
  assert.ok(cases.sourceColourWordsAsNaN > 1000, 'the demo cloud has thousands of these');
});

test('a length that is not whole records is refused, never drawn short', () => {
  const truncated = new Uint8Array(fixture.length + 1);
  truncated.set(fixture);
  assert.throws(() => packIntegerTexels(truncated), /not a whole number of 32-byte records/);
  assert.throws(() => packIntegerTexels(new Uint8Array(0)), /empty/);
});

// ------------------------------------------------------------------ the lens

interface SidecarCases {
  cases: Array<{
    file: string;
    focal?: number;
    width?: number;
    height?: number;
    lens?: number;
    unparseable?: boolean;
  }>;
}

test('the sidecar reader agrees with contract/sidecar, the Java reader’s fixture', () => {
  const table: SidecarCases = JSON.parse(
    readFileSync(join(contractDir, 'sidecar', 'cases.json'), 'utf8'),
  ) as SidecarCases;
  for (const c of table.cases) {
    const text = readFileSync(join(contractDir, 'sidecar', c.file), 'utf8');
    const side = parseSidecar(text);
    if (c.unparseable) {
      assert.equal(side, null, `${c.file}: an unreadable body is no sidecar`);
      continue;
    }
    assert.ok(side, `${c.file}: parsed`);
    assert.equal(side.focal, c.focal, `${c.file}: focal`);
    assert.equal(side.width, c.width, `${c.file}: width`);
    assert.equal(side.height, c.height, `${c.file}: height`);
    close(side.lens, c.lens ?? 0, 1e-9);
  }
});

test('a missing lens is no claim, never a 0-degree camera', () => {
  assert.equal(focalOverHeight(0, 1600), 0);
  assert.equal(focalOverHeight(800, 0), 0);
  assert.equal(focalOverHeight(Number.NaN, 1600), 0);
  assert.equal(fovyForLens(0), 0);
  assert.equal(fovyForLens(Number.NaN), 0);
  // 0 is "unknown", and the look must not vanish: it is the app's fallback.
  assert.equal(sourceFovy(0), NO_LENS_FOVY_DEG);
  close(NO_LENS_FOVY_DEG, 64.27, 1e-3);

  // A real lens is the pinhole relation, and the clamp is the app's.
  close(fovyForLens(0.5), 90, 1e-9);
  close(fovyForLens(1), 53.130102, 1e-5);
  assert.equal(fovyForLens(0.001), 100, 'clamped so a broken lens is not a fisheye');
  assert.equal(fovyForLens(100), 20, 'clamped so a broken lens is not a telescope');
});

// ---------------------------------------------------------------- the camera

/** A small synthetic cloud: three depth layers, off-axis, in the cloud's frame. */
function probeCloud(): Float32Array {
  const p: number[] = [];
  for (const z of [0.4, 0.9, 2.5]) {
    for (const x of [-0.3, -0.05, 0.12, 0.35]) {
      for (const y of [-0.25, 0, 0.2]) p.push(x * z, y * z, z);
    }
  }
  return new Float32Array(p);
}

const probes = probeCloud();
const probeCount = probes.length / 3;
const refDepth = medianDepth(probes, probeCount);

test('at rest the picture is exactly the photograph, at every pivot and lens', () => {
  // No tilt, no motion: the view must be the cloud's own source view, and the
  // pivot must not enter it. This is `SplatCameraTest`'s invariant, one platform
  // over, and it is asserted through the matrices the renderer uploads.
  for (const fovy of [sourceFovy(0), sourceFovy(0.5), sourceFovy(0.7631)]) {
    for (const aspect of [0.46, 1, 1.78]) {
      for (const originX of [-0.5, 0, 0.5]) {
        for (const originY of [-0.5, 0, 0.5]) {
          const camera = cloudCamera({
            surfaceWidth: Math.round(1000 * aspect),
            surfaceHeight: 1000,
            fovyDeg: fovy,
            tiltX: 0,
            tiltY: 0,
            motion: 1,
            originX,
            originY,
            positions: probes,
            count: probeCount,
            refDepth,
          });
          assert.ok(camera.pivot[2] > 0, 'the pivot is a distance');
          for (let i = 0; i < probeCount; i++) {
            const x = probes[i * 3];
            const y = probes[i * 3 + 1];
            const z = probes[i * 3 + 2];
            const drawn = project(camera, x, y, z);
            const source = sourceNdc(fovy, aspect, x, y, z);
            assert.ok(drawn && source);
            close(drawn[0], source[0], 1e-5);
            close(drawn[1], source[1], 1e-5);
          }
        }
      }
    }
  }
});

test('the tilt is a turntable: the pivot holds, the eye keeps its distance', () => {
  const options = {
    surfaceWidth: 1000,
    surfaceHeight: 1400,
    fovyDeg: sourceFovy(0.7631),
    motion: 1,
    positions: probes,
    count: probeCount,
    refDepth,
  };
  const rest = cloudCamera({ ...options, tiltX: 0, tiltY: 0 });
  const tilted = cloudCamera({ ...options, tiltX: 0.7, tiltY: -0.4 });

  // The pivot keeps its OWN screen position — it is the rotation centre.
  const restPivot = project(rest, rest.pivot[0], rest.pivot[1], rest.pivot[2]);
  const tiltedPivot = project(tilted, tilted.pivot[0], tilted.pivot[1], tilted.pivot[2]);
  assert.ok(restPivot && tiltedPivot);
  close(restPivot[0], tiltedPivot[0], 1e-5);
  close(restPivot[1], tiltedPivot[1], 1e-5);

  // The eye swings on a circle about it (|E - P| == |E0 - P| = |P|).
  const distance = (a: readonly number[], b: readonly number[]) =>
    Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
  close(distance(tilted.eye, tilted.pivot), distance([0, 0, 0], rest.pivot), 1e-6);

  // And the picture really moves: the whole mode is this displacement.
  let moved = 0;
  for (let i = 0; i < probeCount; i++) {
    const a = project(rest, probes[i * 3], probes[i * 3 + 1], probes[i * 3 + 2]);
    const b = project(tilted, probes[i * 3], probes[i * 3 + 1], probes[i * 3 + 2]);
    if (a && b) moved = Math.max(moved, Math.hypot(a[0] - b[0], a[1] - b[1]));
  }
  assert.ok(moved > 0.01, `the tilt must move the picture (moved ${moved})`);

  // MOTION_GAIN: the angle is TILT_RANGE / CAM_DIST per unit of tilt, times
  // motion — so Motion 33 % is the app's 1.0.
  assert.equal(TILT_RANGE / CAM_DIST, 0.16 / 1.2);
});

test('the pivot is the frontmost splat under the ray, and never a NaN', () => {
  // A background splat is nearer the ray's image position than the subject, and
  // must lose: a nearest-in-image pick names a splat behind the subject.
  const cloud = new Float32Array([
    0.001, 0.001, 2.0, // background, almost exactly on the ray
    0.0, 0.0, 0.5, // subject, on the ray and in front
  ]);
  const p = pivotAt(cloud, 2, 0, 0, 1);
  assert.equal(p.found, true);
  close(p.z, 0.5, 1e-9);

  // A ray that lands in a hole falls back to the nearest splat…
  const off = pivotAt(cloud, 2, 0.9, 0.9, 1);
  assert.equal(off.found, true);
  // …and a cloud with nothing in front of the camera to the reference plane,
  // which is a finite aim rather than a NaN.
  const empty = pivotAt(new Float32Array([0, 0, -1]), 1, 0, 0, 1.7);
  assert.equal(empty.found, false);
  close(empty.z, 1.7, 1e-9);
  assert.ok(Number.isFinite(empty.x) && Number.isFinite(empty.y));
});

// ------------------------------------------------------------------ the sort

test('the draw order is back to front', () => {
  const count3 = 3;
  const positions = new Float32Array([0, 0, 1, 0, 0, 2, 0, 0, 3]);
  const camera = cloudCamera({
    surfaceWidth: 800,
    surfaceHeight: 800,
    fovyDeg: sourceFovy(0),
    tiltX: 0,
    tiltY: 0,
    motion: 1,
    positions,
    count: count3,
    refDepth: 2,
  });
  const sorter = createDepthOrder(count3);
  const order = [...sorter.sort(positions, count3, camera)];
  // Far first: index 2 (z = 3), then 1, then 0. Unsorted would be 0, 1, 2, and
  // the near splat would be painted over by the far one.
  assert.deepEqual(order, [2, 1, 0]);
  assert.equal(sorter.buckets, SORT_BUCKETS);

  // The order is a permutation of every splat: nothing dropped, nothing twice.
  assert.deepEqual([...order].sort(), [0, 1, 2]);
});

test('the sort holds on the probe cloud, through the real camera', () => {
  const camera = cloudCamera({
    surfaceWidth: 1200,
    surfaceHeight: 800,
    fovyDeg: sourceFovy(0.5),
    tiltX: 0.3,
    tiltY: -0.2,
    motion: 1,
    positions: probes,
    count: probeCount,
    refDepth,
  });
  const order = createDepthOrder(probeCount).sort(probes, probeCount, camera);
  const depthOf = (i: number) => {
    const j = i * 3;
    return (
      camera.forward[0] * (probes[j] - camera.eye[0]) +
      camera.forward[1] * (probes[j + 1] - camera.eye[1]) +
      camera.forward[2] * (probes[j + 2] - camera.eye[2])
    );
  };
  // Non-increasing: the first splat drawn is the farthest. A bucket is a range,
  // so this is an inequality and not an equality — that is the counting sort's
  // stated cost, not a bug.
  for (let i = 1; i < order.length; i++) {
    assert.ok(
      depthOf(order[i - 1]) + 1e-3 >= depthOf(order[i]),
      `splat ${order[i - 1]} (${depthOf(order[i - 1])}) drawn before ${order[i]} (${depthOf(order[i])})`,
    );
  }
});

// ------------------------------------------------------------- the look

test('the SIZE floor is a fraction of the on-screen cell', () => {
  // `Splat.eps2dFor`: the same fraction is the same effect at every resolution,
  // count and zoom — which a number of pixels cannot be.
  const at = (w: number, h: number, n: number, zoom = 1) => eps2dFor(EPS_2D_DEFAULT, w, h, n, zoom);
  close(at(912, 1984, 149504) / (912 * 1984), at(1440, 3120, 149504) / (1440 * 3120), 1e-9);
  close(at(1000, 1000, 100000, 2) / at(1000, 1000, 100000, 1), 4, 1e-9);
  assert.equal(at(0, 100, 100), 0);
  assert.equal(at(100, 100, 0), 0);
  assert.equal(eps2dFor(0, 100, 100, 1000), 0);
  assert.equal(eps2dFor(Number.NaN, 100, 100, 1000), 0);
});

test('the alpha threshold the shader reads is one number', () => {
  // Both stages take it as a uniform; a splat at or below it draws nothing, and
  // an opaque one keeps its whole quad.
  assert.equal(ALPHA_DISCARD, 1 / 255);
  const extent = (alpha: number) =>
    alpha <= ALPHA_DISCARD ? 0 : Math.min(1, Math.sqrt(2 * Math.log(alpha / ALPHA_DISCARD)) / 3);
  assert.equal(extent(1), 1, 'an opaque splat is the identity');
  close(extent(43 / 255), 0.914, 1e-3);
  assert.equal(extent(0), 0, 'a transparent splat is dropped outright');
});

console.log(`splat: ${count} checks agree with the app and contract/splat`);
