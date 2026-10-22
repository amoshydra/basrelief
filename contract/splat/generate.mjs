#!/usr/bin/env node
/**
 * Regenerate `contract/splat/records.bin` and its expectations from the demo cloud.
 *
 *   node contract/splat/generate.mjs
 *
 * The fixture is **records captured from a real cloud**, not records written by
 * hand, because the point of it is the exact bytes the node's compressor produced.
 * Picks are predicates rather than fixed indices so a re-run over a different demo
 * still lands on the same KINDS of record, and the file states the index it
 * resolved so a reader can go and look.
 *
 * Checked in, like `deph/generate.mjs`, so the fixture can be explained — nothing
 * here runs during a test, because a test that rewrites its own expectation can
 * never fail.
 *
 * Each pick also ASSERTS the property that makes it worth having, so the fixture
 * cannot quietly lose its hardest case (the rule `TagContractTest` states for the
 * tag table).
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const SOURCE = 'android/app/src/main/res/raw/demo.splat';
const SOURCE_PATH = join(root, SOURCE);
const RECORD_BYTES = 32;

const bytes = readFileSync(SOURCE_PATH);
const count = Math.floor(bytes.length / RECORD_BYTES);
if (bytes.length % RECORD_BYTES !== 0) {
  throw new Error(`${SOURCE} is not a whole number of records — the fixture assumes it is`);
}

const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.length);
const fields = (i) => {
  const o = i * RECORD_BYTES;
  return {
    position: [view.getFloat32(o, true), view.getFloat32(o + 4, true), view.getFloat32(o + 8, true)],
    scale: [
      view.getFloat32(o + 12, true),
      view.getFloat32(o + 16, true),
      view.getFloat32(o + 20, true),
    ],
    color: [bytes[o + 24], bytes[o + 25], bytes[o + 26], bytes[o + 27]],
    quaternion: [bytes[o + 28], bytes[o + 29], bytes[o + 30], bytes[o + 31]],
    texels: [
      [0, 1, 2, 3].map((k) => view.getUint32(o + k * 4, true)),
      [4, 5, 6, 7].map((k) => view.getUint32(o + k * 4, true)),
    ],
  };
};

const find = (predicate) => {
  for (let i = 0; i < count; i++) if (predicate(i, fields(i))) return i;
  return -1;
};

/**
 * How many of the source cloud's colour words read as a float32 NaN, and why.
 *
 * The exact condition is the FLOAT's, not a byte threshold: the word is
 * `r | g<<8 | b<<16 | a<<24`, so the exponent is `((a & 0x7F) << 1) | (b >> 7)`,
 * and it is all-ones when `a` is 127 or 255 and `b >= 128` (with a nonzero
 * mantissa). An opaque bright splat has `a == 255`, which is why the shorthand
 * "alpha and blue both at or above 128" describes what was actually seen — but
 * `a == 200` is a finite float and this generator does not claim otherwise.
 */
const nanColourWords = (() => {
  let n = 0;
  for (let i = 0; i < count; i++) if (Number.isNaN(view.getFloat32(i * RECORD_BYTES + 24, true))) n++;
  return n;
})();

const picks = [
  {
    name: 'first',
    index: 0,
    why:
      'the file’s first record: an ordinary splat whose baked opacity (alpha 43) makes ' +
      'the quad SHRINK — Splat.extentScaleForAlpha gives sqrt(2 ln(43)) / 3 = 0.914 — and ' +
      'whose quaternion is far from identity, so the q*128+128 decode and the R S S^T R^T ' +
      'rotation are both exercised',
    check: (f) => f.color[3] > 1 && f.color[3] < 90,
  },
  {
    name: 'bright',
    index: find((i) => Number.isNaN(view.getFloat32(i * RECORD_BYTES + 24, true))),
    why:
      'THE record this contract exists for: an opaque bright splat whose colour word reads ' +
      'as a float32 NaN. Uploaded as RGBA32F (the prototype’s path) the colour comes back ' +
      'through floatBitsToUint as a canonical NaN on Mali and this splat draws flat white; ' +
      'uploaded as RGBA32UI the bytes ARE the texel. The exact condition is the exponent: ' +
      '((alpha & 0x7F) << 1) | (blue >> 7) == 0xFF, i.e. alpha 127 or 255 and blue >= 128',
    check: (f) => f.color[3] >= 128 && f.color[2] >= 128,
  },
  {
    name: 'opaque',
    index: find((i, f) => i >= count / 2 && f.color[3] === 255 && f.scale.every((s) => s > 0)),
    why:
      'a fully opaque, mid-file record: the alpha-extent clip is the IDENTITY here ' +
      '(sqrt(2 ln 255) = 3.33 > 3, so the whole 3-sigma quad is kept), which is the other ' +
      'end of the range from `first`',
    check: (f) => f.color[3] === 255,
  },
  {
    name: 'padding',
    index: find((i, f) => f.position.every((v) => v === 0) && f.scale.every((v) => v === 0) && f.color.every((v) => v === 0)),
    why:
      'the node pads a budget out with zero records, and a real cloud therefore ENDS in ' +
      'them: 32 zero bytes must decode without throwing and must be culled (`z > 0` is the ' +
      'shader’s test), not divided by',
    check: (f) => f.position[2] === 0 && f.scale[0] === 0 && f.color[3] === 0,
  },
];

const pieces = [];
const records = [];
for (const pick of picks) {
  if (pick.index < 0) throw new Error(`no record matched "${pick.name}" in ${SOURCE}`);
  const f = fields(pick.index);
  if (!pick.check(f)) throw new Error(`record ${pick.index} for "${pick.name}" does not hold its own reason`);
  pieces.push(bytes.subarray(pick.index * RECORD_BYTES, (pick.index + 1) * RECORD_BYTES));

  const record = { name: pick.name, index: pick.index, why: pick.why, ...f };
  if (pick.name === 'bright') {
    // State the trap, and refuse to write a fixture whose trap is not real.
    const asFloat = view.getFloat32(pick.index * RECORD_BYTES + 24, true);
    if (!Number.isNaN(asFloat)) throw new Error('the bright record is not a NaN as a float32');
    record.colourWordIsNaNAsFloat = true;
  }
  records.push(record);
}
const fixture = Buffer.concat(pieces);
writeFileSync(join(here, 'records.bin'), fixture);

const cases = {
  recordBytes: RECORD_BYTES,
  source: SOURCE,
  sourceRecords: count,
  recordsBytes: fixture.length,
  records: fixture.length / RECORD_BYTES,
  sourceColourWordsAsNaN: nanColourWords,
  fixtureSha256: createHash('sha256').update(fixture).digest('hex'),
  layout: [
    'offset 0..11  position x,y,z    float32 x3, little-endian',
    'offset 12..23 scale    sx,sy,sz float32 x3, already exp()-decoded',
    'offset 24..27 colour   r,g,b,a  uint8 x4',
    'offset 28..31 quat     w,x,y,z  uint8 x4, q*128+128',
    'The 32 bytes ARE the two RGBA32UI texels the renderer uploads: texels[0] = (x,y,z,sx),',
    'texels[1] = (sy,sz,colourWord,quatWord), each word the little-endian uint32 at its offset.',
  ],
  recordsList: records,
};
writeFileSync(join(here, 'cases.json'), `${JSON.stringify(cases, null, 2)}\n`);

console.log(`contract/splat: ${records.length} records from ${SOURCE} (${count} records)`);
for (const r of records) {
  console.log(
    `  ${r.name.padEnd(8)} #${r.index}  colour=${r.color.join(',')}` +
      `  z=${r.position[2]}  texel.quat=0x${r.texels[1][3].toString(16)}`,
  );
}
console.log(`  sha256 ${cases.fixtureSha256}`);
