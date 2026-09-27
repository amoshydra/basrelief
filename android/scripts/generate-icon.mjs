/**
 * Generates the launcher icon: a "depth relief" motif (concentric parallax
 * layers + a bright foreground bubble) rendered at 4x then downsampled for
 * anti-aliasing. Writes android/res/mipmap-xxxhdpi/ic_launcher.png (512px).
 */
import { PNG } from 'pngjs';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = 512;
const SS = 4; // supersample factor
const W = OUT * SS;

const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'app', 'src', 'main', 'res', 'mipmap-xxxhdpi');
mkdirSync(outDir, { recursive: true });

const png = new PNG({ width: W, height: W });

const cx = W / 2;
const cy = W * 0.56;

function blend(dst, idx, r, g, b, a) {
  const da = dst[idx + 3] / 255;
  const na = a;
  const oa = na + da * (1 - na);
  if (oa === 0) return;
  dst[idx] = Math.round((r * na + dst[idx] * da * (1 - na)) / oa);
  dst[idx + 1] = Math.round((g * na + dst[idx + 1] * da * (1 - na)) / oa);
  dst[idx + 2] = Math.round((b * na + dst[idx + 2] * da * (1 - na)) / oa);
  dst[idx + 3] = Math.round(oa * 255);
}

function disc(x, y, r, r2, g2, b2, a) {
  const rp = r * r;
  for (let yy = Math.max(0, Math.floor(y - r)); yy < Math.min(W, Math.ceil(y + r)); yy++) {
    for (let xx = Math.max(0, Math.floor(x - r)); xx < Math.min(W, Math.ceil(x + r)); xx++) {
      const dx = xx - x, dy = yy - y;
      if (dx * dx + dy * dy <= rp) {
        blend(png.data, (yy * W + xx) * 4, r2, g2, b2, a);
      }
    }
  }
}

function ring(x, y, r, width, r2, g2, b2, a) {
  const rOut = r + width / 2;
  const rIn = r - width / 2;
  for (let yy = Math.max(0, Math.floor(y - rOut)); yy < Math.min(W, Math.ceil(y + rOut)); yy++) {
    for (let xx = Math.max(0, Math.floor(x - rOut)); xx < Math.min(W, Math.ceil(x + rOut)); xx++) {
      const dx = xx - x, dy = yy - y;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d >= rIn && d <= rOut) {
        blend(png.data, (yy * W + xx) * 4, r2, g2, b2, a);
      }
    }
  }
}

// background: dark navy
disc(cx, cy, W * 0.9, 0x0b, 0x0e, 0x14, 255);
// relief layers: concentric depth rings
ring(cx, cy, W * 0.30, W * 0.05, 0x2a, 0x5f, 0x8f, 255);
ring(cx, cy, W * 0.22, W * 0.04, 0x3f, 0x8a, 0xc2, 255);
ring(cx, cy, W * 0.15, W * 0.03, 0x66, 0xbf, 0xf0, 255);
// foreground bubble popping out (parallax)
disc(cx - W * 0.16, cy - W * 0.22, W * 0.14, 0x2f, 0xc0, 0xf2, 255);
disc(cx - W * 0.20, cy - W * 0.28, W * 0.045, 0xcf, 0xf2, 0xff, 255);

// downsample SS->OUT via box average
const out = new PNG({ width: OUT, height: OUT });
for (let y = 0; y < OUT; y++) {
  for (let x = 0; x < OUT; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let sy = 0; sy < SS; sy++) {
      for (let sx = 0; sx < SS; sx++) {
        const i = ((y * SS + sy) * W + (x * SS + sx)) * 4;
        r += png.data[i];
        g += png.data[i + 1];
        b += png.data[i + 2];
        a += png.data[i + 3];
      }
    }
    const n = SS * SS;
    const o = (y * OUT + x) * 4;
    out.data[o] = Math.round(r / n);
    out.data[o + 1] = Math.round(g / n);
    out.data[o + 2] = Math.round(b / n);
    out.data[o + 3] = Math.round(a / n);
  }
}

writeFileSync(join(outDir, 'ic_launcher.png'), PNG.sync.write(out));
console.log('wrote', join(outDir, 'ic_launcher.png'), `${OUT}x${OUT}`);
