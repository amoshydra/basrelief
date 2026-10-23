/**
 * The `dePH` / `dEPt` writer: a colour image plus a depth map become ONE PNG.
 *
 * This is the second implementation of `contract/deph/`'s format — the first
 * writer beside the three readers (`DepthPng.java`,
 * `reference/webgl-port/src/png-depth.js`, `web/src/deph.ts`). It shares bytes
 * with them rather than code: `contract/deph/cases.json`'s `pack` block pins the
 * exact chunk (including its CRC) and the exact output file, and
 * `web/src/pack.test.ts` holds this writer to it and round-trips the result
 * through the other two readers.
 *
 * Three rules, each with a failure that looks like success:
 *
 *   1. **A PNG input is never re-encoded.** The output is the input's own bytes
 *      with one ancillary chunk inserted; decode/re-encode would silently soften
 *      the colour the user sent, and every ordinary PNG viewer would still show a
 *      plausible picture. Only a non-PNG (a JPEG) is re-encoded, by the browser
 *      half at the bottom of this file, and the caller is told so it can say it.
 *
 *   2. **The chunk goes before the first IDAT, after IHDR.** A place after IDAT
 *      is legal for an ancillary chunk and some readers stop walking at the image
 *      data, so a chunk there is missed by exactly the readers this format is
 *      for. In front of IHDR a RAW payload claims no dimensions at all (the
 *      reader refuses it — see `raw-before-ihdr.png`).
 *
 *   3. **The CRC is over the type and the data, big-endian, and it is not
 *      optional.** A wrong CRC is silently skipped by strict readers (libpng
 *      warns and drops the chunk) and accepted by lenient ones, so the failure
 *      is "no depth on one machine and depth on another". This page's reader
 *      does not validate CRCs — it walks by length — which is exactly why the
 *      CRC cannot be self-checked here and is pinned against an independent
 *      implementation in the fixture instead.
 *
 * The depth payload is the reader's own: raw 8-bit grey for a RAW result, or the
 * whole PNG byte-for-byte for a PNG result. The writer never re-encodes the
 * depth either.
 */

import {
  isDepthChunkKey,
  isPngSignature,
  pngDimensions,
  type DephSuccess,
} from './deph.ts';

/** The spec-compliant spelling: ancillary, private, reserved bit uppercase. */
export const DEPTH_CHUNK_KEY = 'dePH';

const PNG_SIGNATURE_LENGTH = 8;

export type PackError = 'not-png' | 'bad-colour' | 'bad-depth' | 'size-mismatch';

export type PackResult =
  | {
      ok: true;
      /** The packed file: the colour PNG's bytes with the depth chunk inserted. */
      png: Uint8Array;
      /** How many depth chunks an earlier packer left behind and this one replaced. */
      replaced: number;
    }
  | { ok: false; error: PackError; detail: string };

const MSG_NOT_PNG =
  'the colour is not a PNG — decode and re-encode it to PNG first (the browser ' +
  'half of this module does that), or the original bytes cannot be kept';

interface PngChunk {
  type: string;
  start: number;
  /** One past the chunk's CRC. */
  end: number;
}

let crcTable: Uint32Array | null = null;

/** The PNG CRC table; built once, lazily, so importing this costs nothing. */
function table(): Uint32Array {
  if (crcTable) return crcTable;
  const next = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    next[n] = c >>> 0;
  }
  crcTable = next;
  return next;
}

/**
 * CRC-32 as PNG defines it: the ITU-T V.42 polynomial, reflected, initial value
 * `0xffffffff`, and the result inverted. Over the chunk TYPE and its DATA — not
 * the length, and not the CRC itself.
 */
function crc32(bytes: Uint8Array): number {
  const t = table();
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = (crc >>> 8) ^ t[(crc ^ bytes[i]) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/** Four-byte big-endian length, type, data, four-byte big-endian CRC. */
function writeChunk(type: string, data: Uint8Array): Uint8Array {
  if (type.length !== 4) throw new Error(`a chunk type is four bytes, not "${type}"`);
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length, false);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
  out.set(data, 8);
  // The CRC covers out[4 .. 8+data.length): the type and the data, adjacent.
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)), false);
  return out;
}

/**
 * The chunk list of a PNG stream, or null when the stream cannot be walked.
 *
 * Deliberately strict: the writer's whole promise is that the colour survives
 * byte-for-byte, so a file it cannot account for chunk by chunk is refused
 * rather than copied with a guess. A browser's `<img>` may still show a PNG whose
 * tail is malformed; that is the browser's leniency, not a licence to write.
 */
function chunkList(png: Uint8Array): PngChunk[] | null {
  if (!isPngSignature(png)) return null;
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  const chunks: PngChunk[] = [];
  let offset = PNG_SIGNATURE_LENGTH;
  while (offset + 12 <= png.length) {
    const length = view.getUint32(offset, false);
    const end = offset + 12 + length;
    if (end > png.length) return null; // a chunk claiming bytes that are not here
    chunks.push({
      type: String.fromCharCode(png[offset + 4], png[offset + 5], png[offset + 6], png[offset + 7]),
      start: offset,
      end,
    });
    offset = end;
  }
  // Trailing bytes that are not a whole chunk header: the file is not the stream
  // it claims to be, and copying a tail nothing validates is a worse answer than
  // refusing.
  return offset === png.length ? chunks : null;
}

/**
 * Pack a colour image and a depth map into one PNG.
 *
 * Takes the colour as PNG bytes. A non-PNG is refused with `not-png` rather than
 * re-encoded here, because decoding needs a browser and this half is the pure,
 * Node-tested one; `colourPngBytes` below is the browser half that does it and
 * reports that it did.
 *
 * Refusals are the file's own fault and all mean "do not write this file":
 *
 *   - `not-png`: no PNG signature (the re-encode path).
 *   - `bad-colour`: a PNG whose chunks cannot be walked, or that has no readable
 *     IHDR / no IDAT.
 *   - `bad-depth`: the payload claims nothing (empty) or, for raw grey, is not
 *     `width * height` bytes — such a file would be refused by every reader.
 *   - `size-mismatch`: the depth names different dimensions from the colour. The
 *     app's classifier refuses a mismatch rather than stretching it, so packing
 *     one would produce a file that is unreadable everywhere.
 */
export function packPng(
  colourInput: ArrayBuffer | Uint8Array,
  depth: DephSuccess,
): PackResult {
  const colour = colourInput instanceof Uint8Array ? colourInput : new Uint8Array(colourInput);

  if (!isPngSignature(colour)) {
    return { ok: false, error: 'not-png', detail: MSG_NOT_PNG };
  }

  const dims = pngDimensions(colour);
  if (dims.width <= 0 || dims.height <= 0) {
    return {
      ok: false,
      error: 'bad-colour',
      detail: 'the colour PNG has no readable IHDR, so its size (and the depth chunk) is unknown',
    };
  }

  const payload = depth.form === 'png' ? depth.png : depth.gray;

  if (payload.length === 0) {
    return {
      ok: false,
      error: 'bad-depth',
      detail: 'the depth payload is empty — every reader refuses an empty chunk',
    };
  }

  if (depth.form === 'png') {
    // A whole-PNG payload names its own dimensions. 0x0 is "no claim" and is
    // allowed through (the reader accepts it too); a real disagreement is not.
    if (!isPngSignature(payload)) {
      return {
        ok: false,
        error: 'bad-depth',
        detail: 'a whole-PNG payload must begin with the PNG signature — this one does not',
      };
    }
    const payloadDims = pngDimensions(payload);
    if (
      payloadDims.width > 0 &&
      payloadDims.height > 0 &&
      (payloadDims.width !== dims.width || payloadDims.height !== dims.height)
    ) {
      return {
        ok: false,
        error: 'size-mismatch',
        detail:
          `the depth PNG is ${payloadDims.width}×${payloadDims.height} but the colour is ` +
          `${dims.width}×${dims.height} — the readers would refuse the pair rather than stretch it`,
      };
    }
  } else {
    if (depth.depthWidth !== dims.width || depth.depthHeight !== dims.height) {
      return {
        ok: false,
        error: 'size-mismatch',
        detail:
          `the depth map is ${depth.depthWidth}×${depth.depthHeight} but the colour is ` +
          `${dims.width}×${dims.height}`,
      };
    }
    if (payload.length !== dims.width * dims.height) {
      return {
        ok: false,
        error: 'bad-depth',
        detail:
          `raw grey is ${payload.length} bytes for a ${dims.width}×${dims.height} photo ` +
          `(${dims.width * dims.height} expected) — every reader refuses that`,
      };
    }
  }

  const chunks = chunkList(colour);
  if (!chunks) {
    return {
      ok: false,
      error: 'bad-colour',
      detail: 'the colour PNG cannot be walked chunk by chunk, so its bytes cannot be preserved',
    };
  }
  if (!chunks.some((chunk) => chunk.type === 'IDAT')) {
    return {
      ok: false,
      error: 'bad-colour',
      detail: 'the colour PNG has no IDAT, so there is no image data to put a chunk in front of',
    };
  }

  // Replacing rather than adding: a file that already carries a depth map gets
  // exactly one, at the one position the readers are guaranteed to reach. Two
  // chunks would leave "which one" to each reader's walk order.
  const kept: PngChunk[] = [];
  let replaced = 0;
  for (const chunk of chunks) {
    if (isDepthChunkKey(chunk.type)) replaced++;
    else kept.push(chunk);
  }

  const insertion = writeChunk(DEPTH_CHUNK_KEY, payload);
  const total =
    PNG_SIGNATURE_LENGTH +
    insertion.length +
    kept.reduce((sum, chunk) => sum + (chunk.end - chunk.start), 0);

  const out = new Uint8Array(total);
  out.set(colour.subarray(0, PNG_SIGNATURE_LENGTH), 0);
  let at = PNG_SIGNATURE_LENGTH;
  let inserted = false;
  for (const chunk of kept) {
    // Immediately before the FIRST IDAT: after IHDR (which a raw payload needs)
    // and before any image data (which is where a reader may stop looking).
    if (!inserted && chunk.type === 'IDAT') {
      out.set(insertion, at);
      at += insertion.length;
      inserted = true;
    }
    out.set(colour.subarray(chunk.start, chunk.end), at);
    at += chunk.end - chunk.start;
  }

  return { ok: true, png: out, replaced };
}

/** `photo.png` → `photo.packed.png`; a name with no extension still gets one. */
export function packedFileName(name: string): string {
  const stem = name.replace(/\.[^./\\]*$/, '');
  return `${stem || 'photo'}.packed.png`;
}

/** A colour ready to pack, and whether its bytes are the original file's. */
export interface ColourPng {
  bytes: Uint8Array;
  /**
   * True when the input was not a PNG and had to be decoded and re-encoded. The
   * colour is then a canvas's rendering of the dropped file, not the dropped
   * file: softer for a JPEG, whose decode is lossy, and colour-managed by the
   * browser. Say so in the UI — it is a different file.
   */
  reencoded: boolean;
}

/**
 * The browser half: a dropped file's PNG bytes.
 *
 * A PNG is returned untouched, which is the whole point of packing on the web
 * (the Android app's `DepthPngPacker` takes a `Bitmap` and always re-encodes; the
 * page has the original bytes and offers no excuse for throwing them away). A
 * JPEG — or anything else the browser can decode — goes through a canvas once,
 * because a `dePH` chunk can only live in a PNG.
 */
export async function colourPngBytes(file: File): Promise<ColourPng> {
  const original = new Uint8Array(await file.arrayBuffer());
  if (isPngSignature(original)) return { bytes: original, reencoded: false };

  const bitmap = await createImageBitmap(file);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('this browser has no 2D canvas to re-encode the colour with');
    context.drawImage(bitmap, 0, 0);
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (encoded) => (encoded ? resolve(encoded) : reject(new Error('the canvas produced no PNG'))),
        'image/png',
      );
    });
    return { bytes: new Uint8Array(await blob.arrayBuffer()), reencoded: true };
  } finally {
    bitmap.close();
  }
}
