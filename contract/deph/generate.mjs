#!/usr/bin/env node
/**
 * Produces the `dePH` fixtures in this directory. NOT a test: it rewrites its own
 * expectations, which is exactly what a test must never do. The tests read
 * `cases.json` and the .png files and nothing else; this script exists so a
 * fixture can be regenerated or explained, not so a failure can be made green.
 *
 * Run: node contract/deph/generate.mjs
 *
 * Every payload byte is deliberately different from the outer image's pixels, so
 * an implementation that returns the COLOUR image where the depth belongs fails
 * the payload digest instead of passing with a plausible picture.
 */
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

let table = null;
function crc32(bytes) {
  if (!table) {
    table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let crc = -1;
  for (const b of bytes) crc = (crc >>> 8) ^ table[(crc ^ b) & 0xff];
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/** A real 8-bit grayscale PNG: signature, IHDR, IDAT, IEND. */
function grayPng(width, height, pixels) {
  if (pixels.length !== width * height) throw new Error('pixel count');
  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // filter: none
    pixels.copy(raw, y * (width + 1) + 1, y * width, (y + 1) * width);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // colour type: grayscale
  return Buffer.concat([
    PNG_SIG,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * Rebuild a PNG's chunk list with `extra` inserted before the named chunk.
 * Inserting before IEND is the ordinary place for a private ancillary chunk;
 * inserting before IHDR exists to pin the one ordering rule (a raw payload needs
 * the dimensions IHDR carries).
 */
function withChunk(png, type, data, before) {
  const parts = [png.subarray(0, 8)];
  let off = 8;
  let inserted = false;
  while (off + 12 <= png.length) {
    const len = png.readUInt32BE(off);
    const name = png.subarray(off + 4, off + 8).toString('ascii');
    if (!inserted && name === before) {
      parts.push(chunk(type, data));
      inserted = true;
    }
    parts.push(png.subarray(off, off + 12 + len));
    off += 12 + len;
  }
  if (!inserted) throw new Error(`no ${before} chunk to insert before`);
  return Buffer.concat(parts);
}

const sha = (b) => createHash('sha256').update(b).digest('hex');

// The depth ramps. 0x10.. and 0xa0.. so neither can be confused with the outer
// images, which are flat 0x80.
const raw8x6 = Buffer.from(Array.from({ length: 48 }, (_, i) => 0x10 + i));
const raw5x4 = Buffer.from(Array.from({ length: 20 }, (_, i) => 0xa0 + i));
const inner3x2 = grayPng(3, 2, Buffer.from([1, 2, 3, 4, 5, 6]));

/** @type {{file:string,status:string,form?:string,width?:number,height?:number,payloadBytes?:number,payloadSha256?:string,why:string}[]} */
const cases = [];
function emit(file, bytes, spec) {
  writeFileSync(join(dir, file), bytes);
  cases.push({ file, ...spec });
}

const colour8x6 = () => grayPng(8, 6, Buffer.alloc(48, 0x80));
const colour5x4 = () => grayPng(5, 4, Buffer.alloc(20, 0x80));

emit('raw-gray.png', withChunk(colour8x6(), 'dePH', raw8x6, 'IEND'), {
  status: 'ok',
  form: 'raw',
  width: 8,
  height: 6,
  payloadBytes: raw8x6.length,
  payloadSha256: sha(raw8x6),
  why: 'the ordinary form: raw 8-bit grey, exactly width*height bytes, under the spec-compliant key dePH',
});

emit('whole-png.png', withChunk(colour8x6(), 'dePH', inner3x2, 'IEND'), {
  status: 'ok',
  form: 'png',
  payloadBytes: inner3x2.length,
  payloadSha256: sha(inner3x2),
  why: 'a complete PNG payload is passed through byte-for-byte, never re-encoded',
});

emit('case-dEPt.png', withChunk(colour5x4(), 'dEPt', raw5x4, 'IEND'), {
  status: 'ok',
  form: 'raw',
  width: 5,
  height: 4,
  payloadBytes: raw5x4.length,
  payloadSha256: sha(raw5x4),
  why: 'dEPt is the other accepted spelling, in a different case again: matching is case-insensitive on the four ASCII bytes',
});

emit('case-DEPH.png', withChunk(colour5x4(), 'DEPH', raw5x4, 'IEND'), {
  status: 'ok',
  form: 'raw',
  width: 5,
  height: 4,
  payloadBytes: raw5x4.length,
  payloadSha256: sha(raw5x4),
  why: 'all-caps DEPH is accepted too — the key is compared lowercased, not by an exact string',
});

emit('wrong-size.png', withChunk(colour8x6(), 'dePH', Buffer.alloc(49, 0x40), 'IEND'), {
  status: 'bad-payload',
  payloadBytes: 49,
  why: 'one byte too many for raw grey is REFUSED rather than resized or padded: a chunk and an image that disagree about dimensions cannot both be right',
});

emit('empty-payload.png', withChunk(colour8x6(), 'dePH', Buffer.alloc(0), 'IEND'), {
  status: 'bad-payload',
  payloadBytes: 0,
  why: 'an empty chunk is a refusal, not an empty depth map — an all-zero map would render flat with no error',
});

emit('raw-before-ihdr.png', withChunk(colour8x6(), 'dePH', raw8x6, 'IHDR'), {
  status: 'bad-payload',
  payloadBytes: 48,
  why: 'raw grey takes its dimensions from IHDR, so the same payload placed BEFORE IHDR claims nothing and is refused; a whole-PNG payload would still be accepted there',
});

emit('near-miss-key.png', withChunk(colour8x6(), 'dEpX', raw8x6, 'IEND'), {
  status: 'no-depth-chunk',
  why: 'a four-byte key that is not dePH/dEPt in any case is not a depth chunk at all',
});

emit('no-chunk.png', withChunk(colour8x6(), 'tEXt', Buffer.from('comment\0basrelief fixture', 'latin1'), 'IEND'), {
  status: 'no-depth-chunk',
  why: 'a valid PNG with no depth chunk: "no depth" is the honest answer, and it must not be confused with a refusal',
});

{
  const junk = Buffer.from('this is not a png at all', 'ascii');
  emit('not-a-png.png', junk, {
    status: 'not-png',
    why: 'the signature check is the first gate; a non-PNG is not a PNG with no depth',
  });
}

{
  const truncated = Buffer.concat([
    colour8x6(),
    Buffer.from([0, 0, 0, 100]), // a chunk header claiming 100 bytes...
    Buffer.from('dePH', 'ascii'),
    Buffer.from([1, 2, 3, 4]), // ...with four bytes behind it
  ]);
  emit('truncated-chunk.png', truncated, {
    status: 'no-depth-chunk',
    why: 'a chunk that runs past the end of the file is malformed, so the walk stops; it must not throw, and it must not read past the buffer',
  });
}

/**
 * The PACK expectation: the writer's input, the chunk it must produce, and the
 * exact output file.
 *
 * The colour is a gradient and the depth is a different gradient, so an
 * implementation that re-encodes the colour, mixes up the payloads or writes the
 * chunk somewhere else cannot land on the pinned digest by accident. The chunk's
 * bytes (length, type, data AND CRC) are pinned as hex because this page's reader
 * does not validate CRCs — it walks by length — so the CRC's correctness cannot
 * be seen by reading the output back. The independent implementation here is the
 * only thing that can say the CRC is right.
 *
 * Placement: before the first IDAT. In front of IHDR a raw payload claims no
 * dimensions, and after IDAT a reader that stops at the image data misses it.
 */
const packWidth = 8;
const packHeight = 6;
const packColourPixels = Buffer.from(
  Array.from({ length: packWidth * packHeight }, (_, i) => (0x30 + i * 7) & 0xff),
);
const packDepth = Buffer.from(
  Array.from({ length: packWidth * packHeight }, (_, i) => (0x90 + i * 5) & 0xff),
);
const packColour = grayPng(packWidth, packHeight, packColourPixels);
const packChunk = chunk('dePH', packDepth);
const packed = withChunk(packColour, 'dePH', packDepth, 'IDAT');

emit('pack-colour.png', packColour, {
  status: 'no-depth-chunk',
  why: 'the writer\'s input: a valid colour PNG with no depth chunk. Packing it must insert exactly one chunk and change no other byte',
});

emit('pack-raw.png', packed, {
  status: 'ok',
  form: 'raw',
  width: packWidth,
  height: packHeight,
  payloadBytes: packDepth.length,
  payloadSha256: sha(packDepth),
  why: 'the writer\'s output: raw grey depth under dePH, inserted before the first IDAT and after IHDR, with the colour bytes untouched — the `pack` block above pins this file and the chunk byte for byte',
});

const doc = {
  chunkKeys: {
    accepted: ['dePH', 'dEPt'],
    match: 'case-insensitive over the four ASCII bytes; both spellings are ordinary ancillary chunks',
  },
  payloadForms: {
    png: 'a complete PNG file; returned byte-for-byte, its own IHDR naming its dimensions',
    raw: 'exactly width*height 8-bit grey bytes, dimensions taken from the OUTER IHDR, which must therefore precede the depth chunk',
  },
  pack: {
    why: 'the writer (web/src/pack.ts) is a SECOND implementation of this format. The output file and the chunk\'s exact bytes (length, type, data and CRC) are pinned here because the readers walk by length and none of them validates a CRC: a wrong CRC would be silently skipped by a strict reader and accepted by a lenient one, which is depth on one machine and none on another. The colour is a gradient and the depth is a different gradient so a re-encoded colour or a swapped payload cannot match by accident.',
    colour: 'pack-colour.png',
    colourSha256: sha(packColour),
    output: 'pack-raw.png',
    outputSha256: sha(packed),
    chunkKey: 'dePH',
    placement: 'before the first IDAT, after IHDR',
    chunkBytes: packChunk.length,
    chunkHex: packChunk.toString('hex'),
    payloadBytes: packDepth.length,
    payloadSha256: sha(packDepth),
    rule: 'a PNG input is never re-encoded: the output is the input byte-for-byte with one chunk inserted, and any existing dePH/dEPt chunk replaced rather than duplicated',
  },
  status: {
    ok: 'a depth map was recovered (form says which kind)',
    'not-png': 'the input is not a PNG file at all',
    'no-depth-chunk': 'a PNG was walked and no well-formed dePH/dEPt chunk was found',
    'bad-payload': 'a depth chunk was found but its payload is neither a PNG nor the exact raw size',
    java: "DepthPng.extract answers null for all three refusals: the app only ever asks 'is there depth', so the Java API does not distinguish them. The JS API does, and the JS test pins the specific code.",
  },
  why:
    'The app (Java) and the reference port (JS) both read this chunk out of a photograph, and each derives a depth map the renderer draws. A disagreement in what is accepted is a photograph that is 3D on the phone and flat on the web, or the reverse, with no error on either side.',
  cases,
};

writeFileSync(join(dir, 'cases.json'), JSON.stringify(doc, null, 2) + '\n');
console.log(`wrote ${cases.length} fixtures + cases.json in ${dir}`);
