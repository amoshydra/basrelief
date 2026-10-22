/**
 * Generates a synthetic demo photo + matching depth map:
 *   public/assets/image.png  (color)
 *   public/assets/depth.png  (grayscale, white = near)
 *
 * Run from reference/webgl-port: pnpm run gen:assets
 */
import { PNG } from 'pngjs';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const W = 800;
const H = 600;
const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'assets');
mkdirSync(outDir, { recursive: true });

// --- layers, painted far → near; each returns { c: [r,g,b], d: 0..255 } or null ---

const lerp = (a, b, t) => a + (b - a) * t;
const lerpC = (c1, c2, t) => c1.map((v, i) => lerp(v, c2[i], t));

function sky(x, y) {
  const t = y / H;
  return { c: lerpC([24, 40, 92], [240, 150, 90], t * t * 1.4), d: 22 };
}

function circleLayer(cx, cy, r, color, d, dome = 0) {
  return (x, y) => {
    const dx = x - cx;
    const dy = y - cy;
    const dist2 = dx * dx + dy * dy;
    if (dist2 > r * r) return null;
    const rn = Math.sqrt(dist2) / r; // 0 center → 1 edge
    // fake shading: light from top-left
    const shade = 1 - 0.35 * ((dx + dy) / (2 * r) + 0.5);
    return {
      c: color.map((v) => Math.min(255, v * shade)),
      d: d + dome * (1 - rn * rn), // optional spherical bulge
    };
  };
}

function triangleLayer(x1, y1, x2, y2, x3, y3, color, d) {
  const area = (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1);
  return (x, y) => {
    const s = ((x3 - x) * (y1 - y) - (x1 - x) * (y3 - y)) / area;
    const t = ((x1 - x) * (y2 - y) - (x2 - x) * (y1 - y)) / area;
    const u = 1 - s - t;
    if (s < 0 || t < 0 || u < 0) return null;
    const shade = 0.85 + 0.3 * u; // brighter toward the peak
    return { c: color.map((v) => Math.min(255, v * shade)), d };
  };
}

function ground(x, y) {
  const horizon = H * 0.62;
  if (y < horizon) return null;
  const t = (y - horizon) / (H - horizon);
  // subtle horizontal stripes for visible parallax texture
  const stripe = Math.floor(y / 22) % 2 === 0 ? 1 : 0.92;
  return {
    c: lerpC([70, 130, 70], [30, 70, 40], t).map((v) => v * stripe),
    d: lerp(95, 135, t), // ground gets closer toward the bottom
  };
}

const layers = [
  sky,
  circleLayer(620, 130, 55, [255, 220, 130], 30), // sun
  triangleLayer(-50, H * 0.62, 260, 180, 570, H * 0.62, [110, 100, 150], 65), // mountain L
  triangleLayer(300, H * 0.62, 640, 230, 950, H * 0.62, [90, 85, 130], 80), // mountain R
  circleLayer(640, 230, 12, [245, 245, 250], 82), // snow cap
  ground,
  circleLayer(170, 420, 85, [220, 70, 80], 170, 50), // red bubble
  circleLayer(640, 470, 105, [70, 130, 220], 190, 55), // blue bubble
  circleLayer(420, 520, 70, [240, 190, 60], 215, 45), // yellow bubble
  circleLayer(420, 520, 22, [250, 240, 200], 265), // highlight
  circleLayer(70, 560, 60, [150, 80, 200], 245, 40), // purple bubble (nearest)
];

// --- rasterize ---
const img = new PNG({ width: W, height: H });
const dep = new PNG({ width: W, height: H });

for (let y = 0; y < H; y++) {
  for (let x = 0; x < W; x++) {
    let c = [0, 0, 0];
    let d = 0;
    for (const layer of layers) {
      const hit = layer(x, y);
      if (hit) {
        c = hit.c;
        d = hit.d;
      }
    }
    const i = (y * W + x) * 4;
    img.data[i] = c[0];
    img.data[i + 1] = c[1];
    img.data[i + 2] = c[2];
    img.data[i + 3] = 255;
    dep.data[i] = dep.data[i + 1] = dep.data[i + 2] = Math.max(0, Math.min(255, d));
    dep.data[i + 3] = 255;
  }
}

const out = (png, name) =>
  writeFileSync(join(outDir, name), PNG.sync.write(png));
out(img, 'image.png');
out(dep, 'depth.png');

// --- also emit a single PNG with the depth map embedded in a dePH chunk ---
// (ancillary chunk → the color image stays viewable in any PNG viewer)

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

/** Insert a custom ancillary chunk (payload = a whole PNG file) before IEND. */
function embedPngChunk(imageBuf, chunkType, payloadBuf) {
  const iendStart = imageBuf.length - 12;
  if (imageBuf.toString('ascii', iendStart + 4, iendStart + 8) !== 'IEND') {
    throw new Error('not a valid PNG (missing IEND)');
  }
  const chunk = Buffer.alloc(12 + payloadBuf.length);
  chunk.writeUInt32BE(payloadBuf.length, 0);
  chunk.write(chunkType, 4, 'ascii');
  payloadBuf.copy(chunk, 8);
  chunk.writeUInt32BE(crc32(chunk.subarray(4, 8 + payloadBuf.length)), 8 + payloadBuf.length);
  return Buffer.concat([imageBuf.subarray(0, iendStart), chunk, imageBuf.subarray(iendStart)]);
}

const imageBuf = PNG.sync.write(img);
const depthBuf = PNG.sync.write(dep);
writeFileSync(join(outDir, 'photo-with-depth.png'), embedPngChunk(imageBuf, 'dePH', depthBuf));

console.log(`wrote ${join(outDir, 'image.png')} and depth.png (${W}x${H})`);
console.log(`wrote ${join(outDir, 'photo-with-depth.png')} (depth embedded in dePH chunk)`);
