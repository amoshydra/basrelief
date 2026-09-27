/**
 * Extracts an embedded depth map from a PNG that carries it in a custom
 * ancillary chunk. The color image stays viewable in any PNG viewer.
 *
 * Accepted chunk names (case-insensitive):
 *   dePH — spec-compliant form (ancillary, private, reserved-bit
 *          uppercase, unsafe-to-copy since depth is coupled to pixels)
 *   dEPt — used by some depth-export tools
 *
 * Accepted payloads:
 *   1. a complete grayscale PNG file (self-describing), or
 *   2. raw 8-bit grayscale pixels, exactly width×height bytes
 *      (dimensions taken from IHDR) — re-encoded to a PNG on the fly.
 *
 * Returns one of:
 *   { data: Uint8Array }             — depth map as PNG bytes
 *   { error: 'not-png' }             — input isn't a PNG file
 *   { error: 'bad-payload', ... }    — chunk found, payload unrecognized
 *   { error: 'no-depth-chunk', ... } — PNG parsed, no depth chunk present
 */
const DEPTH_CHUNK_NAMES = ['deph', 'dept'];
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

export function extractEmbeddedDepth(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (bytes.length < 8 || !PNG_SIG.every((b, i) => bytes[i] === b)) {
    return { error: 'not-png' };
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const chunks = [];
  let width = 0;
  let height = 0;
  let offset = 8;
  // chunk layout: length(4) | type(4) | data(length) | crc32(4)
  while (offset + 12 <= bytes.length) {
    const length = view.getUint32(offset);
    if (offset + 12 + length > bytes.length) break; // malformed, stop
    const type = String.fromCharCode(
      bytes[offset + 4],
      bytes[offset + 5],
      bytes[offset + 6],
      bytes[offset + 7]
    );
    chunks.push(type);

    if (type === 'IHDR') {
      width = view.getUint32(offset + 8);
      height = view.getUint32(offset + 12);
    }

    if (DEPTH_CHUNK_NAMES.includes(type.toLowerCase())) {
      const data = bytes.subarray(offset + 8, offset + 8 + length);
      if (PNG_SIG.every((b, i) => data[i] === b)) {
        return { data }; // payload is a whole PNG already
      }
      if (width > 0 && height > 0 && data.length === width * height) {
        return { data: encodeGrayPng(data, width, height) }; // raw 8-bit gray
      }
      return {
        error: 'bad-payload',
        chunks,
        payloadLength: data.length,
        expectedRaw: width * height,
      };
    }
    offset += 12 + length;
  }
  return { error: 'no-depth-chunk', chunks };
}

// ---------------------------------------------------------------------------
// minimal PNG encoder: 8-bit grayscale, no compression (zlib stored blocks)
// ---------------------------------------------------------------------------

let crcTable = null;
function crc32(bytes) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < bytes.length; i++) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ bytes[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

function adler32(bytes) {
  let a = 1;
  let b = 0;
  for (let i = 0; i < bytes.length; i++) {
    a = (a + bytes[i]) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

/** Wrap raw bytes in a zlib stream using uncompressed "stored" blocks. */
function zlibStore(bytes) {
  const blocks = Math.ceil(bytes.length / 65535) || 1;
  const out = new Uint8Array(2 + bytes.length + blocks * 5 + 4);
  const view = new DataView(out.buffer);
  out[0] = 0x78; // zlib header: deflate, 32K window
  out[1] = 0x01; // no preset dictionary, lowest check (valid with stored blocks)
  let src = 0;
  let dst = 2;
  do {
    const len = Math.min(65535, bytes.length - src);
    const final = src + len >= bytes.length;
    out[dst] = final ? 1 : 0; // BFINAL, BTYPE=00 (stored)
    view.setUint16(dst + 1, len, true); // LEN, little-endian
    view.setUint16(dst + 3, ~len & 0xffff, true); // NLEN
    out.set(bytes.subarray(src, src + len), dst + 5);
    src += len;
    dst += 5 + len;
  } while (src < bytes.length);
  view.setUint32(dst, adler32(bytes)); // adler32 of the uncompressed data
  return out;
}

function pngChunk(type, data) {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
  out.set(data, 8);
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

/** Encode raw 8-bit grayscale pixels (row-major) into a valid PNG. */
function encodeGrayPng(gray, width, height) {
  // one filter byte (0 = none) prepended to every scanline
  const scanlines = new Uint8Array((width + 1) * height);
  for (let y = 0; y < height; y++) {
    scanlines.set(gray.subarray(y * width, (y + 1) * width), y * (width + 1) + 1);
  }
  const ihdr = new Uint8Array(13);
  const view = new DataView(ihdr.buffer);
  view.setUint32(0, width);
  view.setUint32(4, height);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // color type: grayscale
  // compression 0, filter 0, interlace 0 — already zeroed

  const sig = new Uint8Array(PNG_SIG);
  const parts = [
    sig,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlibStore(scanlines)),
    pngChunk('IEND', new Uint8Array(0)),
  ];
  const total = parts.reduce((n, p) => n + p.length, 0);
  const png = new Uint8Array(total);
  let at = 0;
  for (const p of parts) {
    png.set(p, at);
    at += p.length;
  }
  return png;
}
