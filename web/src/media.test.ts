#!/usr/bin/env node
/**
 * The gallery's media vocabulary, tested without a browser.
 *
 *   node --experimental-strip-types src/media.test.ts
 *
 * The three states are the app's `DepthSource` rule, and the test exists because
 * each wrong answer looks plausible on screen:
 *
 *   - a file still being read reported as "no depth" (a window claiming a fact);
 *   - a malformed depth chunk reported as "no depth" (the file lies about depth);
 *   - a depth map of the wrong size stretched over the photo (no error anywhere);
 *   - a `.splat` whose length is not whole records counted as a cloud.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { extractEmbeddedDepth } from './deph.ts';
import {
  SPLAT_RECORD_BYTES,
  classifyMedia,
  formatBytes,
  formatSplatCount,
  type MediaProbe,
} from './media.ts';

const fixtureDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'contract', 'deph');
const bytesOf = (file: string) => new Uint8Array(readFileSync(join(fixtureDir, file)));

/** A probe for a PNG fixture that has finished reading and decoding. */
function pngProbe(file: string, decodes: boolean | null = true): MediaProbe {
  const bytes = bytesOf(file);
  return {
    name: file,
    size: bytes.length,
    depth: extractEmbeddedDepth(bytes),
    readError: null,
    decodes,
  };
}

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

test('a file still being read is not a photo without depth', () => {
  assert.deepEqual(
    classifyMedia({ name: 'photo.png', size: 0, depth: null, readError: null, decodes: null }),
    { kind: 'reading' },
  );
});

test('a PNG with no chunk stays unknown until the image itself decodes', () => {
  // The bytes say "no depth chunk", but a file that does not decode yet is not
  // yet a photograph. This is the window DepthSource.UNKNOWN exists for.
  assert.deepEqual(classifyMedia(pngProbe('no-chunk.png', null)), { kind: 'reading' });
});

test('a packed PNG is a relief, and names the payload form', () => {
  assert.deepEqual(classifyMedia(pngProbe('raw-gray.png')), { kind: 'relief', form: 'raw' });
});

test('a plain PNG is a photograph', () => {
  const state = classifyMedia(pngProbe('no-chunk.png'));
  assert.equal(state.kind, 'photo');
  if (state.kind === 'photo') assert.match(state.detail, /no embedded depth/);
});

test('a malformed depth chunk is refused, never reported as no depth', () => {
  for (const file of ['wrong-size.png', 'empty-payload.png', 'raw-before-ihdr.png']) {
    const state = classifyMedia(pngProbe(file));
    assert.equal(state.kind, 'unreadable', `${file}: kind`);
    if (state.kind === 'unreadable') assert.match(state.reason, /malformed/, `${file}: reason`);
  }
});

test('a depth map that is not the photo size is refused, not stretched', () => {
  const probe = pngProbe('whole-png.png');
  // The reader accepts the payload (the contract says a whole PNG is passed
  // through); the refusal belongs to the app layer, and it is explicit.
  assert.equal(probe.depth?.ok, true, 'reader accepts the whole-PNG payload');
  if (probe.depth?.ok) {
    assert.equal(probe.depth.depthWidth, 3);
    assert.equal(probe.depth.depthHeight, 2);
    assert.equal(probe.depth.imageWidth, 8);
    assert.equal(probe.depth.imageHeight, 6);
  }
  const state = classifyMedia(probe);
  assert.equal(state.kind, 'unreadable');
  if (state.kind === 'unreadable') {
    assert.match(state.reason, /3×2/);
    assert.match(state.reason, /8×6/);
    assert.match(state.reason, /refusing to stretch/);
  }
});

test('a non-PNG image is a photograph, and a non-image is unreadable', () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);
  const asPhoto = classifyMedia({
    name: 'from-phone.jpg',
    size: jpeg.length,
    depth: extractEmbeddedDepth(jpeg),
    readError: null,
    decodes: true,
  });
  assert.equal(asPhoto.kind, 'photo');
  if (asPhoto.kind === 'photo') assert.match(asPhoto.detail, /not a PNG/);

  const asGarbage = classifyMedia({
    name: 'notes.txt',
    size: jpeg.length,
    depth: extractEmbeddedDepth(jpeg),
    readError: null,
    decodes: false,
  });
  assert.equal(asGarbage.kind, 'unreadable');
});

test('a damaged image is unreadable even if its depth chunk parses', () => {
  const state = classifyMedia(pngProbe('raw-gray.png', false));
  assert.equal(state.kind, 'unreadable');
  if (state.kind === 'unreadable') assert.match(state.reason, /did not decode/);
});

test('a .splat is counted from its length, without reading the bytes', () => {
  const state = classifyMedia({
    name: 'bale.SPLAT',
    size: 479_232 * SPLAT_RECORD_BYTES,
    depth: null,
    readError: null,
    decodes: null,
  });
  assert.deepEqual(state, { kind: 'cloud', splats: 479_232 });
  assert.equal(formatSplatCount(479_232), '479,232 splats');
});

test('a .splat that is not whole records is unreadable', () => {
  const odd = classifyMedia({
    name: 'truncated.splat',
    size: SPLAT_RECORD_BYTES * 10 + 1,
    depth: null,
    readError: null,
    decodes: null,
  });
  assert.equal(odd.kind, 'unreadable');
  if (odd.kind === 'unreadable') assert.match(odd.reason, /32-byte records/);

  const empty = classifyMedia({
    name: 'empty.splat',
    size: 0,
    depth: null,
    readError: null,
    decodes: null,
  });
  assert.equal(empty.kind, 'unreadable');
});

test('a read failure is unreadable, never a photograph', () => {
  const state = classifyMedia({
    name: 'photo.png',
    size: 0,
    depth: null,
    readError: 'the file could not be read',
    decodes: null,
  });
  assert.deepEqual(state, { kind: 'unreadable', reason: 'the file could not be read' });
});

test('byte sizes read the way the sheet writes them', () => {
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(1023), '1023 B');
  assert.equal(formatBytes(1024), '1.0 KB');
  assert.equal(formatBytes(1024 * 1024 * 4.8), '4.8 MB');
  assert.equal(formatBytes(1024 * 1024 * 23.4), '23 MB');
});

console.log(`media states: ${count} checks agree with web/src/media.ts`);
