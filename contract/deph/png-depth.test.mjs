#!/usr/bin/env node
/**
 * The reference port's half of `contract/deph/`, run under plain Node.
 *
 *   node contract/deph/png-depth.test.mjs
 *
 * `png-depth.js` touches no browser API — only Uint8Array/DataView/String and its
 * own zlib writer — so it loads here unmodified. Nothing is shimmed, and there is
 * no package install: Node's built-in `zlib` decodes the PNG the port encodes, so
 * the raw-grey fixture can be checked all the way to its pixels rather than merely
 * "some bytes came back".
 *
 * The Java side of the same fixtures is `DepthPngContractTest`.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';

import { extractEmbeddedDepth } from '../../reference/webgl-port/src/png-depth.js';

const dir = dirname(fileURLToPath(import.meta.url));
const cases = JSON.parse(readFileSync(join(dir, 'cases.json'), 'utf8')).cases;
const sha = (b) => createHash('sha256').update(b).digest('hex');
const bytesOf = (file) => readFileSync(join(dir, file));
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** The IHDR dimensions and IDAT payload of a PNG this port produced. */
function pngParts(bytes) {
  assert.deepEqual([...bytes.subarray(0, 8)], [...PNG_SIG], 'not a PNG');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let off = 8;
  let width = 0;
  let height = 0;
  let idat = null;
  while (off + 12 <= bytes.length) {
    const length = view.getUint32(off);
    const type = Buffer.from(bytes.subarray(off + 4, off + 8)).toString('ascii');
    if (type === 'IHDR') {
      width = view.getUint32(off + 8);
      height = view.getUint32(off + 12);
    }
    if (type === 'IDAT') idat = bytes.subarray(off + 8, off + 8 + length);
    off += 12 + length;
  }
  assert.ok(idat, 'no IDAT');
  return { width, height, idat };
}

/** Undo the port's encoder: stored-block zlib, one filter byte per scanline. */
function rawGrayOf(bytes, width, height) {
  const scanlines = inflateSync(pngParts(bytes).idat);
  assert.equal(scanlines.length, (width + 1) * height);
  const out = Buffer.alloc(width * height);
  for (let y = 0; y < height; y++) {
    assert.equal(scanlines[y * (width + 1)], 0, 'filter byte must be 0 (none)');
    scanlines.copy(out, y * width, y * (width + 1) + 1, (y + 1) * (width + 1));
  }
  return out;
}

let checked = 0;
for (const c of cases) {
  assert.ok(c.why, `${c.file} has no "why" — a fixture without a reason is a number`);
  const result = extractEmbeddedDepth(bytesOf(c.file));

  if (c.status !== 'ok') {
    assert.ok(result.error, `${c.file}: must be refused`);
    assert.equal(result.error, c.status, `${c.file}: refusal reason`);
    checked++;
    continue;
  }

  assert.equal(result.error, undefined, `${c.file}: must be accepted`);
  assert.ok(result.data instanceof Uint8Array, `${c.file}: data`);

  if (c.form === 'png') {
    // A whole-PNG payload is passed through untouched: same bytes, not re-encoded.
    assert.equal(result.data.length, c.payloadBytes, `${c.file}: payload length`);
    assert.equal(sha(result.data), c.payloadSha256, `${c.file}: payload bytes`);
    assert.deepEqual([...result.data.subarray(0, 8)], [...PNG_SIG]);
  } else {
    // Raw grey is re-encoded, so the contract is checked through the encoder:
    // the dimensions it used and the pixels it put in, not its compression.
    assert.equal(c.form, 'raw');
    const parts = pngParts(result.data);
    assert.equal(parts.width, c.width, `${c.file}: width`);
    assert.equal(parts.height, c.height, `${c.file}: height`);
    assert.equal(sha(rawGrayOf(result.data, c.width, c.height)), c.payloadSha256,
      `${c.file}: raw pixels`);
  }
  checked++;
}

// The table is the contract, so an empty or silently-shrinking one is a failure.
assert.equal(checked, cases.length);
for (const status of ['ok', 'not-png', 'no-depth-chunk', 'bad-payload']) {
  assert.ok(cases.some((c) => c.status === status), `no ${status} case in the table`);
}

console.log(`deph fixtures: ${checked} cases agree with png-depth.js`);
