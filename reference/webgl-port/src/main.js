import { createDepthScene } from './scene.js';
import { createTiltInput } from './tilt.js';
import { extractEmbeddedDepth } from './png-depth.js';

const DEFAULT_IMAGE = '/assets/image.png';
const DEFAULT_DEPTH = '/assets/depth.png';
const DEFAULT_EMBEDDED = '/assets/photo-with-depth.png';

const container = document.getElementById('app');
const statusEl = document.getElementById('status');
const enableBtn = document.getElementById('enable-motion');
const calibrateBtn = document.getElementById('calibrate');
const resetOrbitBtn = document.getElementById('reset-orbit');
const orbitMarker = document.getElementById('orbit-marker');
const intensityInput = document.getElementById('intensity');
const softnessInput = document.getElementById('softness');
const motionInput = document.getElementById('motion');
const zoomInput = document.getElementById('zoom');
const toggleUiBtn = document.getElementById('toggle-ui');
const toggleFsBtn = document.getElementById('toggle-fs');
const fileImage = document.getElementById('file-image');
const fileDepth = document.getElementById('file-depth');

const scene = createDepthScene(container);
const tilt = createTiltInput({
  onStatus: (msg) => (statusEl.textContent = msg),
});

// --- boot: prefer the demo PNG with embedded depth, fall back to the pair ---
const blobUrl = (bytes, type = 'image/png') =>
  URL.createObjectURL(new Blob([bytes], { type }));

(async () => {
  try {
    const res = await fetch(DEFAULT_EMBEDDED);
    const buf = res.ok ? await res.arrayBuffer() : null;
    const result = buf ? extractEmbeddedDepth(buf) : null;
    if (result?.data) {
      await scene.loadPair(blobUrl(buf), blobUrl(result.data));
      statusEl.textContent = 'demo: PNG with embedded depth (dePH chunk)';
      return;
    }
  } catch {
    /* fall through to the two-file demo */
  }
  scene.loadPair(DEFAULT_IMAGE, DEFAULT_DEPTH).catch(() => {
    statusEl.textContent = 'demo assets missing — run: pnpm run gen:assets';
  });
})();

// --- inputs ---
// panGesture is true while a two-finger drag or shift/right-drag is active,
// so the tilt fallback doesn't fight the pan gesture.
let panGesture = false;
tilt.bindPointer(container, () => panGesture);
enableBtn.addEventListener('click', async () => {
  const ok = await tilt.enableGyro();
  if (ok) enableBtn.hidden = true;
});
calibrateBtn.addEventListener('click', () => {
  tilt.calibrate();
  statusEl.textContent = 'calibrated — hold still';
});
resetOrbitBtn.addEventListener('click', () => {
  scene.resetOrbit();
  scene.resetPan();
  statusEl.textContent = 'view reset';
});

// --- gestures ---
// tap (single, quick, still)  → set orbit center
// two-finger drag             → pan
// shift+drag / right-drag     → pan (desktop)
// single-finger / mouse drag  → tilt fallback (handled in tilt.js)
const pointers = new Map();
let tapStart = null;

container.addEventListener('contextmenu', (e) => e.preventDefault());

container.addEventListener('pointerdown', (e) => {
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 2 || e.shiftKey || e.button === 2) {
    panGesture = true;
    tapStart = null;
  } else if (pointers.size === 1) {
    panGesture = false; // fresh plain gesture (clears stale shift-drag state)
    tapStart = { x: e.clientX, y: e.clientY, t: performance.now() };
  }
});

container.addEventListener('pointermove', (e) => {
  const prev = pointers.get(e.pointerId);
  if (!prev) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (panGesture) {
    // divide by pointer count: with two fingers this equals the midpoint delta
    scene.panBy(
      (e.clientX - prev.x) / pointers.size,
      (e.clientY - prev.y) / pointers.size
    );
  }
});

function endPointer(e) {
  pointers.delete(e.pointerId);
  if (panGesture) {
    if (pointers.size < 2 && !e.shiftKey) panGesture = false;
    tapStart = null;
    return;
  }
  if (!tapStart) return;
  const moved = Math.hypot(e.clientX - tapStart.x, e.clientY - tapStart.y);
  const held = performance.now() - tapStart.t;
  tapStart = null;
  if (moved < 12 && held < 300) {
    scene.setOrbitFromScreen(e.clientX, e.clientY);
    statusEl.textContent = 'orbit center set';
  }
}
container.addEventListener('pointerup', endPointer);
container.addEventListener('pointercancel', endPointer);
intensityInput.addEventListener('input', (e) => {
  scene.setIntensity(e.target.value / 100);
});
scene.setIntensity(intensityInput.value / 100);
softnessInput.addEventListener('input', (e) => {
  scene.setEdgeSoftness(e.target.value / 100);
});
scene.setEdgeSoftness(softnessInput.value / 100);
motionInput.addEventListener('input', (e) => {
  scene.setMotion(e.target.value / 100);
});
scene.setMotion(motionInput.value / 100);
zoomInput.addEventListener('input', (e) => {
  scene.setZoom(e.target.value / 100);
});
scene.setZoom(zoomInput.value / 100);

// --- hide / show controls ---
toggleUiBtn.addEventListener('click', () => {
  document.body.classList.toggle('ui-hidden');
});

// --- fullscreen (with webkit prefix fallback; may be rejected on iPhone) ---
toggleFsBtn.addEventListener('click', async () => {
  const doc = document.documentElement;
  const fsEl = document.fullscreenElement || document.webkitFullscreenElement;
  try {
    if (fsEl) {
      await (document.exitFullscreen ?? document.webkitExitFullscreen).call(document);
    } else {
      await (doc.requestFullscreen ?? doc.webkitRequestFullscreen).call(doc);
    }
  } catch {
    statusEl.textContent = 'fullscreen not supported on this browser';
  }
});

// --- custom image/depth upload ---
const custom = { image: null, depth: null };
function maybeLoadCustom() {
  if (custom.image && custom.depth) {
    scene.loadPair(custom.image, custom.depth).then(() => {
      statusEl.textContent = 'custom pair loaded';
    });
  }
}
fileImage.addEventListener('change', async (e) => {
  const f = e.target.files[0];
  if (!f) return;
  // a single PNG may already carry its depth map in a dePH chunk
  const buf = await f.arrayBuffer();
  const result = extractEmbeddedDepth(buf);
  if (result.data) {
    if (custom.image) URL.revokeObjectURL(custom.image);
    if (custom.depth) URL.revokeObjectURL(custom.depth);
    custom.image = blobUrl(buf);
    custom.depth = blobUrl(result.data);
    await scene.loadPair(custom.image, custom.depth);
    statusEl.textContent = 'loaded PNG with embedded depth';
    return;
  }
  // no usable embedded chunk: show why, and preview the image flat
  console.debug('[depth] no embedded chunk:', result.error, result.chunks);
  if (custom.image) URL.revokeObjectURL(custom.image);
  custom.image = URL.createObjectURL(f);
  await scene.loadImage(custom.image);
  statusEl.textContent =
    result.error === 'not-png'
      ? 'not a PNG file — embedded depth needs a PNG with a dePH chunk'
      : result.error === 'bad-payload'
        ? `depth chunk payload unrecognized (${result.payloadLength} bytes, expected PNG or ${result.expectedRaw} raw bytes)`
        : 'no embedded depth chunk — pick a depth map too';
  maybeLoadCustom();
});
fileDepth.addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (!f) return;
  if (custom.depth) URL.revokeObjectURL(custom.depth);
  custom.depth = URL.createObjectURL(f);
  maybeLoadCustom();
});

// --- resize + main loop ---
window.addEventListener('resize', () => scene.onResize());

function frame() {
  scene.render(tilt.update());
  const p = scene.getOrbitScreen();
  orbitMarker.style.left = `${p.x}px`;
  orbitMarker.style.top = `${p.y}px`;
  requestAnimationFrame(frame);
}
frame();
