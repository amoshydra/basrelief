#!/usr/bin/env node
/**
 * The TypeScript half of `contract/deph/`, run under plain Node.
 *
 *   node --experimental-strip-types src/deph.test.ts
 *
 * The same golden bytes are read by `DepthPngContractTest` (Java, inside the
 * Android gate) and `contract/deph/png-depth.test.mjs` (the reference port).
 * This reader has no dependencies and touches no browser API, so the test needs
 * no install and no shim. The writer's half of the directory is
 * `web/src/pack.test.ts`.
 *
 * The table is the contract, so the test asserts its shape as well as its
 * answers: every case needs a stated `why`, every one is walked, and all four
 * statuses must still be present. A fixture that quietly loses its hardest
 * refusal is a rule with no guard.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { extractEmbeddedDepth, pngDimensions } from './deph.ts';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureDir = join(here, '..', '..', 'contract', 'deph');
const table = JSON.parse(readFileSync(join(fixtureDir, 'cases.json'), 'utf8'));
const cases = table.cases as Array<{
  file: string;
  status: 'ok' | 'not-png' | 'no-depth-chunk' | 'bad-payload';
  form?: 'raw' | 'png';
  width?: number;
  height?: number;
  payloadBytes: number;
  payloadSha256?: string;
  why: string;
}>;

const sha = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
const bytesOf = (file: string) => new Uint8Array(readFileSync(join(fixtureDir, file)));

let checked = 0;

for (const c of cases) {
  assert.ok(c.why, `${c.file} has no "why" — a fixture without a reason is a number`);
  const result = extractEmbeddedDepth(bytesOf(c.file));

  if (c.status !== 'ok') {
    assert.equal(result.ok, false, `${c.file}: must be refused`);
    assert.equal(result.error, c.status, `${c.file}: refusal reason`);
    checked++;
    continue;
  }

  assert.equal(result.ok, true, `${c.file}: must be accepted`);
  assert.equal(result.form, c.form, `${c.file}: payload form`);

  if (result.form === 'png') {
    // A whole-PNG payload is passed through untouched: same bytes, not re-encoded.
    assert.equal(result.png.length, c.payloadBytes, `${c.file}: payload length`);
    assert.equal(sha(result.png), c.payloadSha256, `${c.file}: payload bytes`);

    // Its dimensions are the PAYLOAD's own, not the outer image's — the app layer
    // needs that difference to refuse a mismatched pair.
    const dims = pngDimensions(result.png);
    assert.equal(result.depthWidth, dims.width, `${c.file}: depth width`);
    assert.equal(result.depthHeight, dims.height, `${c.file}: depth height`);
    assert.ok(result.depthWidth > 0 && result.depthHeight > 0, `${c.file}: readable depth IHDR`);
  } else {
    // Raw grey is returned as pixels plus the outer IHDR's dimensions, never
    // re-encoded: the payload digest is the fixture's own depth bytes.
    assert.equal(c.form, 'raw');
    assert.equal(result.gray.length, c.payloadBytes, `${c.file}: payload length`);
    assert.equal(sha(result.gray), c.payloadSha256, `${c.file}: raw pixels`);
    assert.equal(result.depthWidth, c.width, `${c.file}: width`);
    assert.equal(result.depthHeight, c.height, `${c.file}: height`);
    // Dimensions come from IHDR, so the depth and the colour image agree here.
    assert.equal(result.imageWidth, c.width, `${c.file}: outer width`);
    assert.equal(result.imageHeight, c.height, `${c.file}: outer height`);
  }
  checked++;
}

// The table is the contract: an empty or silently-shrinking one is a failure.
assert.equal(checked, cases.length);
for (const status of ['ok', 'not-png', 'no-depth-chunk', 'bad-payload']) {
  assert.ok(cases.some((c) => c.status === status), `no ${status} case in the table`);
}
assert.deepEqual(table.chunkKeys.accepted, ['dePH', 'dEPt'], 'accepted chunk keys changed');

// A Uint8Array view into a larger buffer must read at its own offset, because a
// File read and a Node Buffer are both views rather than bare ArrayBuffers.
{
  const original = bytesOf('raw-gray.png');
  const padded = new Uint8Array(original.length + 11);
  padded.set(original, 7);
  const view = padded.subarray(7);
  const fromView = extractEmbeddedDepth(view);
  const fromBytes = extractEmbeddedDepth(original);
  assert.equal(fromView.ok, true, 'offset view: accepted');
  assert.equal(fromBytes.ok, true, 'plain bytes: accepted');
  if (fromView.ok && fromBytes.ok && fromView.form === 'raw' && fromBytes.form === 'raw') {
    assert.equal(sha(fromView.gray), sha(fromBytes.gray), 'offset view: same pixels');
  } else {
    assert.fail('offset view: expected two raw results');
  }
}

// An empty buffer is not a PNG, and the reader says so rather than throwing.
{
  const empty = extractEmbeddedDepth(new Uint8Array(0));
  assert.equal(empty.ok, false);
  assert.equal(empty.error, 'not-png');
}

console.log(`deph fixtures: ${checked} cases agree with web/src/deph.ts`);
