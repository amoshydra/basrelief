#!/usr/bin/env node
/**
 * The writer's half of `contract/deph/`, run under plain Node.
 *
 *   node --experimental-strip-types src/pack.test.ts
 *
 * The reader (`web/src/deph.ts`) is the third implementation of this format and
 * has its own fixtures. This is the FIRST writer, and a writer's wrong answers do
 * not look wrong: a bad CRC is a chunk a strict reader silently drops, a chunk
 * after IDAT is one some readers never walk to, a re-encoded colour is a slightly
 * softer picture nobody can compare, and a payload of the wrong length is refused
 * by the reader that was supposed to draw it. So the test pins:
 *
 *   - the exact chunk bytes, including the CRC, against `cases.json`'s `pack`
 *     block — the only independent implementation of that CRC in the tree (this
 *     page's reader walks by length and never checks one);
 *   - the exact output file, which holds the placement and the untouched colour;
 *   - that the colour bytes are the input's own, with one chunk inserted;
 *   - a round trip through THIS reader AND through
 *     `reference/webgl-port/src/png-depth.js`, because a writer that only its own
 *     reader accepts is a writer that agrees with itself.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32, inflateSync } from 'node:zlib';

import { extractEmbeddedDepth } from './deph.ts';
import { DEPTH_CHUNK_KEY, packPng, packedFileName } from './pack.ts';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureDir = join(here, '..', '..', 'contract', 'deph');
const table = JSON.parse(readFileSync(join(fixtureDir, 'cases.json'), 'utf8'));

interface PackFixture {
  why: string;
  colour: string;
  colourSha256: string;
  output: string;
  outputSha256: string;
  chunkKey: string;
  placement: string;
  chunkBytes: number;
  chunkHex: string;
  payloadBytes: number;
  payloadSha256: string;
  rule: string;
}

const pack = table.pack as PackFixture;

/** The second reader, loaded from its real file: no copy, no shim. */
const referenceUrl = new URL('../../reference/webgl-port/src/png-depth.js', import.meta.url).href;
const reference = (await import(referenceUrl)) as {
  extractEmbeddedDepth(input: Uint8Array): { data?: Uint8Array; error?: string };
};

const sha = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
const bytesOf = (file: string) => new Uint8Array(readFileSync(join(fixtureDir, file)));
const hex = (bytes: Uint8Array) =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

interface Chunk {
  type: string;
  start: number;
  end: number;
}

/** Walk a PNG's chunks; asserts the walk consumes the file exactly. */
function chunksOf(png: Uint8Array): Chunk[] {
  assert.equal(hex(png.subarray(0, 8)), '89504e470d0a1a0a', 'not a PNG');
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  const out: Chunk[] = [];
  let at = 8;
  while (at + 12 <= png.length) {
    const length = view.getUint32(at, false);
    const end = at + 12 + length;
    assert.ok(end <= png.length, 'a chunk claims bytes that are not in the file');
    const type = String.fromCharCode(png[at + 4], png[at + 5], png[at + 6], png[at + 7]);
    out.push({ type, start: at, end });
    at = end;
  }
  assert.equal(at, png.length, 'the chunk walk left trailing bytes');
  return out;
}

/** The bytes of a chunk WITH its header and CRC, as a Uint8Array. */
function chunkBytes(png: Uint8Array, chunk: Chunk): Uint8Array {
  return png.subarray(chunk.start, chunk.end);
}

/** The input with every dePH/dEPt chunk removed — for "nothing else changed". */
function withoutDepthChunks(png: Uint8Array): Uint8Array {
  const kept = chunksOf(png).filter((c) => !/^dep[ht]$/i.test(c.type));
  const total = 8 + kept.reduce((n, c) => n + (c.end - c.start), 0);
  const out = new Uint8Array(total);
  out.set(png.subarray(0, 8), 0);
  let at = 8;
  for (const c of kept) {
    out.set(png.subarray(c.start, c.end), at);
    at += c.end - c.start;
  }
  return out;
}

/** IHDR/IDAT of a PNG the reference port encoded, decoded to raw grey pixels. */
function grayFromPng(png: Uint8Array, width: number, height: number): Uint8Array {
  const chunks = chunksOf(png);
  const ihdr = chunks.find((c) => c.type === 'IHDR');
  const idat = chunks.find((c) => c.type === 'IDAT');
  assert.ok(ihdr && idat, 'the re-encoded depth PNG has no IHDR/IDAT');
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  assert.equal(view.getUint32(ihdr.start + 8, false), width, 'encoded depth width');
  assert.equal(view.getUint32(ihdr.start + 12, false), height, 'encoded depth height');
  const scanlines = new Uint8Array(inflateSync(chunkBytes(png, idat).subarray(8, -4)));
  assert.equal(scanlines.length, (width + 1) * height);
  const out = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    assert.equal(scanlines[y * (width + 1)], 0, 'filter byte must be 0 (none)');
    out.set(scanlines.subarray(y * (width + 1) + 1, (y + 1) * (width + 1)), y * width);
  }
  return out;
}

// -------------------------------------------------------------- the fixture

assert.ok(pack && pack.why, 'cases.json has no `pack` block — the writer has no fixture');
assert.equal(pack.chunkKey, DEPTH_CHUNK_KEY, 'the fixture and the writer disagree about the key');
assert.equal(sha(bytesOf(pack.colour)), pack.colourSha256, 'the fixture colour changed');
assert.equal(sha(bytesOf(pack.output)), pack.outputSha256, 'the fixture output changed');

const colour = bytesOf(pack.colour);
const depth = extractEmbeddedDepth(bytesOf(pack.output));
assert.equal(depth.ok, true, 'the fixture output does not read as a packed PNG');
if (!depth.ok) throw new Error('unreachable');
assert.equal(depth.form, 'raw');
if (depth.form !== 'raw') throw new Error('unreachable');
assert.equal(sha(depth.gray), pack.payloadSha256, 'the fixture payload changed');
assert.equal(depth.gray.length, pack.payloadBytes);

// --------------------------------------------------------- the writer itself

const written = packPng(colour, depth);
if (!written.ok) assert.fail(`the writer refused the fixture colour: ${written.detail}`);

// The whole output file, byte for byte: placement, CRC, colour and payload in one
// assertion. A reader that ignores CRCs cannot catch a wrong one; this can.
assert.equal(sha(written.png), pack.outputSha256, 'the packed file does not match the fixture');
assert.deepEqual(written.png, bytesOf(pack.output), 'the packed file is not byte-identical');
assert.equal(written.replaced, 0, 'a colour with no depth chunk had nothing to replace');

// ------------------------------------------------------------ chunk placement

const writtenChunks = chunksOf(written.png);
const names = writtenChunks.map((c) => c.type);
assert.deepEqual(names, ['IHDR', 'dePH', 'IDAT', 'IEND'], 'chunk order changed');
const depthChunk = writtenChunks.find((c) => c.type === 'dePH');
assert.ok(depthChunk, 'no dePH chunk was written');
const firstIdat = writtenChunks.find((c) => c.type === 'IDAT');
assert.ok(firstIdat);
assert.equal(depthChunk.end, firstIdat.start, 'the depth chunk must sit immediately before IDAT');
assert.equal(writtenChunks[0].type, 'IHDR');
assert.equal(depthChunk.start, writtenChunks[0].end, 'the depth chunk must sit immediately after IHDR');

// The chunk's exact bytes, including the CRC. `pack.chunkHex` came from
// `generate.mjs`, a separate implementation (Node Buffers, its own CRC table).
assert.equal(chunkBytes(written.png, depthChunk).length, pack.chunkBytes, 'chunk length changed');
assert.equal(hex(chunkBytes(written.png, depthChunk)), pack.chunkHex, 'chunk bytes (and CRC) changed');

// And an independent CRC32 over type+data, for THIS payload, so the property is
// checked rather than only compared with one recorded number.
{
  const bytes = chunkBytes(written.png, depthChunk);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const stored = view.getUint32(bytes.length - 4, false);
  assert.equal(stored, crc32(bytes.subarray(4, bytes.length - 4)), 'the CRC is not CRC32(type+data)');
}

// ------------------------------------------------------- the colour survived

// Removing the chunk the writer inserted leaves the input file exactly: no
// re-encode, no header rewrite, no chunk reordering.
assert.deepEqual(
  withoutDepthChunks(written.png),
  colour,
  'the packed file is not the input bytes with one chunk inserted',
);
assert.deepEqual(
  chunkBytes(written.png, writtenChunks[0]),
  chunkBytes(colour, chunksOf(colour)[0]),
  'the IHDR changed',
);

// ------------------------------------------------------------- round trips

// This reader.
const readBack = extractEmbeddedDepth(written.png);
assert.equal(readBack.ok, true);
if (readBack.ok) {
  assert.equal(readBack.form, 'raw');
  if (readBack.form === 'raw') {
    assert.equal(sha(readBack.gray), pack.payloadSha256, 'round trip: payload bytes');
    assert.equal(readBack.depthWidth, 8);
    assert.equal(readBack.depthHeight, 6);
    assert.equal(readBack.imageWidth, 8);
    assert.equal(readBack.imageHeight, 6);
  }
}

// The reference port, from its real source file. It re-encodes raw grey to a PNG,
// so the contract is checked through its encoder: the dimensions it wrote and the
// pixels it put in, not its compression.
{
  const answer = reference.extractEmbeddedDepth(written.png);
  assert.equal(answer.error, undefined, `the reference reader refused it: ${answer.error}`);
  assert.ok(answer.data instanceof Uint8Array, 'the reference reader returned no data');
  const gray = grayFromPng(answer.data, 8, 6);
  assert.equal(sha(gray), pack.payloadSha256, 'the reference reader got different depth pixels');
}

// A whole-PNG payload, passed through byte-for-byte by both readers. `no-chunk.png`
// is a complete 8×6 PNG, which is all the payload has to be — and its own IHDR is
// what makes it a valid pair for the 8×6 colour.
{
  const wholeDepth = {
    ok: true,
    form: 'png',
    png: bytesOf('no-chunk.png'),
    depthWidth: 8,
    depthHeight: 6,
    imageWidth: 8,
    imageHeight: 6,
  } as const;
  const result = packPng(colour, wholeDepth);
  assert.equal(result.ok, true);
  if (result.ok) {
    const back = extractEmbeddedDepth(result.png);
    assert.equal(back.ok, true);
    if (back.ok && back.form === 'png') {
      assert.deepEqual(back.png, bytesOf('no-chunk.png'), 'a PNG payload must not be re-encoded');
      assert.equal(back.depthWidth, 8);
      assert.equal(back.depthHeight, 6);
    } else {
      assert.fail('the packed whole-PNG payload did not read back as a PNG payload');
    }

    const other = reference.extractEmbeddedDepth(result.png);
    assert.equal(other.error, undefined);
    assert.deepEqual(other.data, bytesOf('no-chunk.png'), 'the reference reader changed the payload');
  }
}

// ------------------------------------------------------------- idempotence

{
  const twice = packPng(written.png, depth);
  assert.equal(twice.ok, true);
  if (twice.ok) {
    assert.equal(twice.replaced, 1, 'packing a packed file must REPLACE its chunk');
    assert.deepEqual(twice.png, written.png, 'packing twice is not the identity');
  }
}

// ---------------------------------------------------------- replacement

// `raw-gray.png` already carries a depth chunk, and it sits AFTER IDAT (before
// IEND). Repacking must leave exactly one chunk and move it in front of IDAT.
{
  const existing = bytesOf('raw-gray.png');
  const fresh = Uint8Array.from({ length: 48 }, (_, i) => (0x10 + i * 3) & 0xff);
  const repacked = packPng(existing, {
    ok: true,
    form: 'raw',
    gray: fresh,
    depthWidth: 8,
    depthHeight: 6,
    imageWidth: 8,
    imageHeight: 6,
  });
  assert.equal(repacked.ok, true);
  if (repacked.ok) {
    assert.equal(repacked.replaced, 1, 'the old depth chunk was not counted');
    const all = chunksOf(repacked.png);
    assert.equal(
      all.filter((c) => /^dep[ht]$/i.test(c.type)).length,
      1,
      'a repacked file must carry exactly one depth chunk',
    );
    assert.equal(all.find((c) => c.type === 'dePH')?.end, all.find((c) => c.type === 'IDAT')?.start,
      'the replacement must be in the pre-IDAT slot too');

    const back = extractEmbeddedDepth(repacked.png);
    assert.equal(back.ok, true);
    if (back.ok && back.form === 'raw') {
      assert.equal(sha(back.gray), sha(fresh), 'the reader got the OLD payload');
    } else {
      assert.fail('the repacked file did not read back as raw grey');
    }

    // The colour is untouched: same bytes, same length (one 60-byte chunk out,
    // one 60-byte chunk in).
    assert.equal(repacked.png.length, existing.length, 'repacking changed the file length');
    assert.deepEqual(withoutDepthChunks(repacked.png), withoutDepthChunks(existing));
  }
}

// ---------------------------------------------------------------- refusals

// Not a PNG: the caller must re-encode (the browser half does) rather than have
// the writer quietly re-encode it or write something invalid.
{
  const junk = bytesOf('not-a-png.png');
  const result = packPng(junk, depth);
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.error, 'not-png');
    assert.match(result.detail, /re-encode/i, 'a refusal must say how to proceed');
  }
}

// A depth map of a different size is refused, not packed: every reader refuses
// the pair one layer up, so writing it would produce a knowingly unreadable file.
{
  const mismatched = extractEmbeddedDepth(bytesOf('whole-png.png')); // 3×2 payload
  assert.equal(mismatched.ok, true);
  if (mismatched.ok) {
    assert.equal(mismatched.form, 'png');
    assert.equal(mismatched.depthWidth, 3);
    const result = packPng(colour, mismatched);
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.equal(result.error, 'size-mismatch');
      assert.match(result.detail, /3×2/);
      assert.match(result.detail, /8×6/);
    }
  }

  const wrongRaw = packPng(colour, {
    ok: true,
    form: 'raw',
    gray: new Uint8Array(48),
    depthWidth: 3,
    depthHeight: 2,
    imageWidth: 3,
    imageHeight: 2,
  });
  assert.equal(wrongRaw.ok, false);
  if (!wrongRaw.ok) assert.equal(wrongRaw.error, 'size-mismatch');
}

// An empty payload is not a flat depth map: every reader refuses it.
{
  const result = packPng(colour, {
    ok: true,
    form: 'raw',
    gray: new Uint8Array(0),
    depthWidth: 8,
    depthHeight: 6,
    imageWidth: 8,
    imageHeight: 6,
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.error, 'bad-depth');
}

// Raw grey whose length is not width*height would be refused by the readers even
// though the dimensions agree with the colour, so the writer refuses it first.
{
  const result = packPng(colour, {
    ok: true,
    form: 'raw',
    gray: new Uint8Array(47),
    depthWidth: 8,
    depthHeight: 6,
    imageWidth: 8,
    imageHeight: 6,
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.error, 'bad-depth');
}

// A `form: 'png'` result whose bytes are not a PNG is not a payload: every reader
// would call it `bad-payload` (or, worse, raw grey of the right length), so it is
// refused rather than inserted.
{
  const result = packPng(colour, {
    ok: true,
    form: 'png',
    png: new Uint8Array(48),
    depthWidth: 8,
    depthHeight: 6,
    imageWidth: 8,
    imageHeight: 6,
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.error, 'bad-depth');
}

// A colour PNG that cannot be walked (a chunk claims more bytes than the file has)
// is refused rather than copied with a guess.
{
  const cut = colour.subarray(0, 40); // IHDR's header and part of its data
  const result = packPng(cut, depth);
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.error, 'bad-colour');
}

// A PNG with no IDAT has no image, so there is nothing to put a chunk in front of.
{
  const ihdrOnly = colour.subarray(0, 8 + 12 + 13);
  const result = packPng(ihdrOnly, depth);
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.error, 'bad-colour');
}

// ---------------------------------------------------------------- the name

assert.equal(packedFileName('photo.png'), 'photo.packed.png');
assert.equal(packedFileName('holiday.JPEG'), 'holiday.packed.png');
assert.equal(packedFileName('no-extension'), 'no-extension.packed.png');
assert.equal(packedFileName('.png'), 'photo.packed.png', 'a bare extension has no name to keep');

console.log('pack: the fixture chunk (length, type, data, CRC), the exact file, the');
console.log('      untouched colour, both readers round-tripped, and the refusals agree');
