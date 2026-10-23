/**
 * The `dePH` / `dEPt` embedded-depth reader, in TypeScript.
 *
 * This is the THIRD implementation of the contract in `contract/deph/`, beside
 * `android/core/.../depth/DepthPng.java` (Java) and
 * `reference/webgl-port/src/png-depth.js` (JavaScript). All three are tested
 * against the same golden bytes; this one is tested by `web/src/deph.test.ts`.
 * The WRITER lives in `web/src/pack.ts` and is held to the same fixture's `pack`
 * block, so the chunk this reader accepts and the chunk that writer emits are one
 * format rather than two that happen to agree today.
 *
 * What it keeps from the reference port, deliberately:
 *
 *   - the key is `dePH` or `dEPt`, matched case-insensitively over four ASCII
 *     bytes, and a key that is neither is not a depth chunk at all;
 *   - the payload is either a complete PNG (self-describing, returned
 *     byte-for-byte) or exactly `width * height` bytes of raw 8-bit grey whose
 *     dimensions come from the OUTER IHDR — which is why a raw payload placed
 *     before IHDR is refused rather than guessed at;
 *   - the walk stops quietly at a truncated chunk instead of throwing or
 *     reading past the buffer, and a non-PNG is its own error, not a PNG with
 *     no depth.
 *
 * What is deliberately different from the reference port:
 *
 *   - the result is a discriminated union carrying EITHER raw grey + its
 *     dimensions OR a whole PNG + its own dimensions, which mirrors the Java
 *     `DepthPng.Result` rather than the port's "always re-encode to a PNG".
 *     The app layer needs the payload's own dimensions to refuse a depth map
 *     that does not match the photo, and encoding a PNG only to decode it
 *     again would throw that information away.
 *   - the key comparison lowercases ASCII bytes ("A".."Z" only) rather than
 *     running `String.prototype.toLowerCase` over a Latin-1 string. The
 *     contract says "case-insensitive over the four ASCII bytes"; this is that
 *     sentence, literally, and it cannot be widened by a future Unicode table.
 *
 * The reader accepts `ArrayBuffer | Uint8Array` because the page reads a File
 * into an ArrayBuffer while the tests and fixtures hand it a Node Buffer (which
 * is a Uint8Array view with a non-zero `byteOffset`). `DataView` is built with
 * that offset so a subarray works unmodified.
 */

export type DephError = 'not-png' | 'no-depth-chunk' | 'bad-payload';

export interface DephRaw {
  ok: true;
  form: 'raw';
  /** Exactly `depthWidth * depthHeight` 8-bit grey bytes, row 0 = image top. */
  gray: Uint8Array;
  depthWidth: number;
  depthHeight: number;
  /** The colour image's dimensions, from the outer IHDR. */
  imageWidth: number;
  imageHeight: number;
}

export interface DephPng {
  ok: true;
  form: 'png';
  /** The payload byte-for-byte, as the contract requires — never re-encoded. */
  png: Uint8Array;
  /** The payload's OWN IHDR dimensions (0 if it has no readable IHDR). */
  depthWidth: number;
  depthHeight: number;
  /** The colour image's dimensions, from the outer IHDR. */
  imageWidth: number;
  imageHeight: number;
}

export interface DephFailure {
  ok: false;
  error: DephError;
  /** Chunk types walked before the answer; diagnostics only. */
  chunks: string[];
  /** For `bad-payload`: what was found, and what raw grey would have needed. */
  payloadLength?: number;
  expectedRaw?: number;
}

export type DephResult = DephRaw | DephPng | DephFailure;

export type DephSuccess = Extract<DephResult, { ok: true }>;

const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function asBytes(input: ArrayBuffer | Uint8Array): Uint8Array {
  return input instanceof Uint8Array ? input : new Uint8Array(input);
}

/** The 8-byte PNG signature, optionally at an offset (a whole-PNG payload). */
export function isPngSignature(bytes: Uint8Array, at = 0): boolean {
  if (bytes.length - at < PNG_SIG.length) return false;
  for (let i = 0; i < PNG_SIG.length; i++) {
    if (bytes[at + i] !== PNG_SIG[i]) return false;
  }
  return true;
}

function chunkType(bytes: Uint8Array, at: number): string {
  return String.fromCharCode(bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]);
}

/** ASCII-only lowercasing: `0x41..0x5a`, and nothing a Unicode table can widen. */
function lowerAscii(code: number): number {
  return code >= 0x41 && code <= 0x5a ? code | 0x20 : code;
}

/**
 * `dePH` / `dEPt`, case-insensitively over the four ASCII bytes. Both spellings
 * are ordinary ancillary chunks; the shared suffix is `dep` + `h` or `t`.
 *
 * Exported because the writer (`web/src/pack.ts`) must recognise the chunk it is
 * replacing with exactly the rule the reader accepts. Two copies of "which keys
 * count" is how a file ends up carrying two depth chunks, the writer's and an
 * old one, with the first-found rule deciding which one a reader sees.
 */
export function isDepthChunkKey(type: string): boolean {
  if (type.length !== 4) return false;
  return (
    lowerAscii(type.charCodeAt(0)) === 0x64 && // d
    lowerAscii(type.charCodeAt(1)) === 0x65 && // e
    lowerAscii(type.charCodeAt(2)) === 0x70 && // p
    (lowerAscii(type.charCodeAt(3)) === 0x68 || lowerAscii(type.charCodeAt(3)) === 0x74)
  ); // h / t
}

function isDepthKey(bytes: Uint8Array, at: number): boolean {
  return isDepthChunkKey(chunkType(bytes, at));
}

function u32(view: DataView, at: number): number {
  return view.getUint32(at, false);
}

/** The IHDR dimensions of a PNG stream, or 0x0 if it has no readable IHDR. */
export function pngDimensions(png: Uint8Array): { width: number; height: number } {
  if (!isPngSignature(png)) return { width: 0, height: 0 };
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  let offset = PNG_SIG.length;
  while (offset + 12 <= png.length) {
    const length = u32(view, offset);
    if (offset + 12 + length > png.length) break;
    if (chunkType(png, offset + 4) === 'IHDR' && length >= 8) {
      return { width: u32(view, offset + 8), height: u32(view, offset + 12) };
    }
    offset += 12 + length;
  }
  return { width: 0, height: 0 };
}

/**
 * Walk a PNG and recover a depth map, or say precisely why it cannot.
 *
 * The first depth chunk found decides the answer: if it is neither a complete
 * PNG nor exactly the raw size, the file is refused rather than searched for a
 * second, friendlier chunk. A chunk that runs past the end stops the walk —
 * malformed input must not throw and must not read past the buffer.
 */
export function extractEmbeddedDepth(input: ArrayBuffer | Uint8Array): DephResult {
  const bytes = asBytes(input);
  const chunks: string[] = [];

  if (!isPngSignature(bytes)) {
    return { ok: false, error: 'not-png', chunks };
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let imageWidth = 0;
  let imageHeight = 0;
  let offset = PNG_SIG.length;

  while (offset + 12 <= bytes.length) {
    const length = u32(view, offset);
    if (offset + 12 + length > bytes.length) break; // truncated: stop, do not throw

    const type = chunkType(bytes, offset + 4);
    chunks.push(type);

    if (type === 'IHDR' && length >= 8) {
      imageWidth = u32(view, offset + 8);
      imageHeight = u32(view, offset + 12);
    }

    if (isDepthKey(bytes, offset + 4)) {
      const payload = bytes.subarray(offset + 8, offset + 8 + length);

      if (isPngSignature(payload)) {
        const dims = pngDimensions(payload);
        return {
          ok: true,
          form: 'png',
          png: payload,
          depthWidth: dims.width,
          depthHeight: dims.height,
          imageWidth,
          imageHeight,
        };
      }

      // Raw grey takes its dimensions from the outer IHDR, so a chunk placed
      // before IHDR claims nothing and is refused (the fixture pins this).
      if (imageWidth > 0 && imageHeight > 0 && payload.length === imageWidth * imageHeight) {
        return {
          ok: true,
          form: 'raw',
          gray: payload,
          depthWidth: imageWidth,
          depthHeight: imageHeight,
          imageWidth,
          imageHeight,
        };
      }

      return {
        ok: false,
        error: 'bad-payload',
        chunks,
        payloadLength: payload.length,
        expectedRaw: imageWidth * imageHeight,
      };
    }

    offset += 12 + length;
  }

  return { ok: false, error: 'no-depth-chunk', chunks };
}
