import * as THREE from 'three';
import {
  RELIEF_FAR,
  RELIEF_FOV_DEG,
  RELIEF_NEAR,
  heightAt,
  projectWorld,
  reliefScreenToOrbit,
  reliefCamera,
  type ReliefCamera,
} from './camera.ts';
import { CAM_DIST } from './splat.ts';
import type { DephSuccess } from './deph.ts';
import {
  DEFAULT_LIGHT_AZIMUTH,
  DEFAULT_LIGHT_ELEVATION,
  brightnessGain,
  lightDirection,
  shadowLift,
} from './picture.ts';
import {
  DEPTH_DEFAULT,
  MOTION_DEFAULT,
  ORIGIN_LIMIT,
  ZOOM_DEFAULT,
  ZOOM_MAX,
  ZOOM_MIN,
} from './settings.ts';

/**
 * The relief renderer.
 *
 * This is a port of `reference/webgl-port/src/scene.js` — the only existing
 * implementation of this exact effect — and the camera model, crop, displacement
 * and float depth blur are taken from it deliberately:
 *
 *   - a plane displaced by `depth/255 - 0.5`, scaled by intensity × the plane's
 *     height, so perceived depth does not change with zoom or aspect;
 *   - per-vertex normals recomputed from the displaced surface, lit by an
 *     ambient + directional pair (real geometry shading, not a shader trick);
 *   - a fixed 45° camera at CAM_DIST, the plane fitted to the viewport with
 *     CROP_MARGIN, and the eye swinging by `motion × MOTION_GAIN ×
 *     CAM_RANGE_FRACTION × planeHeight` as the tilt moves;
 *   - a separable box blur in FLOAT before displacement, radius width/78. The
 *     8-bit depth map's terraces become contour lines under grazing light, and
 *     re-quantising the blur to 8-bit brings them back. That fraction is the
 *     same one `ReliefMesh.java` and the reference port use, and
 *     `scripts/check-parity.mjs` now holds all three together.
 *
 * What is NOT ported from it:
 *
 *   - the camera and the gestures are NOT its any more: they are the Android
 *     app's (`AGENTS.md`: the app is the reference). `camera.ts` is
 *     `CameraModel.frame` and `screenToOrbit` — the turntable about the surface
 *     under the origin, the fixed rest eye, the pan shift, the zoom crop — and
 *     the tap, the pan and the crosshair all go through it. The port's
 *     "centre is the image centre, no pan" view is exactly what the user asked
 *     to be fixed.
 *   - its frame-rate-dependent smoothing (`lerp 0.08 per frame`), which feels
 *     different at 60/90/120 Hz. This uses the time constant the Android app
 *     already settled on (`TILT_FILTER`'s 0.2 s), applied as an exponential.
 *   - its structure: one scene per open item, created and destroyed with the
 *     viewer, rather than a module-level singleton wired to global DOM ids.
 *
 * What is added beyond the port, because the studio's sheet has the rows: the
 * picture adjustments (`Picture`) and the light angles (`LightDirection`), both
 * mapped in `picture.ts` where they are tested, plus the zoom above. Every one
 * is a uniform or a model scale — none of them rebuilds the mesh or the shader.
 */

const SEGMENTS = 256;
const SMOOTHING_TAU = 0.2;

/** Fraction of the depth map's width; the banding fix. Keep in step with Java. */
export const DEPTH_BLUR_FRACTION = 1 / 78;

/** The app's defaults, from the one settings schema rather than retyped here. */
export const DEFAULT_DEPTH = DEPTH_DEFAULT / 100;
export const DEFAULT_MOTION = MOTION_DEFAULT / 100;
export const DEFAULT_ZOOM = ZOOM_DEFAULT / 100;

export interface ReliefView {
  setDepth(value: number): void;
  setMotion(value: number): void;
  /** The Origin row: the orbit centre as a fraction of the picture. */
  setOrigin(x: number, y: number): void;
  /** The Pan row, in CSS pixels of the drawing surface; follows the finger. */
  setPan(x: number, y: number): void;
  /** Pan Z: the camera distance. 1 is the source camera. */
  setPanZ(scale: number): void;
  /** The orbit centre on the glass, in CSS pixels, from the frame last drawn. */
  pivotPixel(): { x: number; y: number } | null;
  /** Called after every rendered frame, so the marker follows the drawn picture. */
  onDraw(callback: () => void): void;
  /**
   * A tap on the picture, in NDC: unprojected through the camera as it is (Pan,
   * zoom and the current pivot height included) to the plane point under the
   * finger, which becomes the new origin. Null when the tap misses the picture.
   */
  tapOrigin(ndcX: number, ndcY: number): { x: number; y: number } | null;
  /**
   * Zoom as a multiplier (25 % is 0.25, 100 % is 1.0, 200 % is 2.0). It scales
   * the plane, exactly as `CameraModel.computeScale` does, which is why it is a
   * crop of the same camera rather than a second one: the eye-to-picture
   * geometry, and therefore the parallax, is unchanged and only magnified.
   */
  setZoom(value: number): void;
  /** Brightness and shadow, as the app's stored hundredths. 0/0 is the identity. */
  setAdjust(brightness: number, shadow: number): void;
  /** The light's angles in degrees; `LightDirection`'s convention. */
  setLight(azimuthDegrees: number, elevationDegrees: number): void;
  /** Tilt target in [-1, 1]; the rendered value eases toward it. */
  setTilt(x: number, y: number): void;
  resize(): void;
  destroy(): void;
}

export interface ReliefOptions {
  imageUrl: string;
  depth: DephSuccess;
  /** Aborted when the viewer closes while the image or depth is still loading. */
  signal?: AbortSignal;
}

interface DepthPixels {
  width: number;
  height: number;
  gray: Uint8Array;
}

/**
 * The depth payload as 8-bit grey pixels, whichever form the chunk carried.
 * Raw grey is already pixels; a whole-PNG payload is decoded once.
 */
async function readDepthPixels(depth: DephSuccess): Promise<DepthPixels> {
  if (depth.form === 'raw') {
    return { width: depth.depthWidth, height: depth.depthHeight, gray: depth.gray };
  }

  const buffer = depth.png.buffer.slice(
    depth.png.byteOffset,
    depth.png.byteOffset + depth.png.byteLength,
  ) as ArrayBuffer;
  const bitmap = await createImageBitmap(new Blob([buffer], { type: 'image/png' }));
  try {
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) throw new Error('a 2D canvas is unavailable, so the depth PNG cannot be read');
    context.drawImage(bitmap, 0, 0);
    const rgba = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
    const gray = new Uint8Array(bitmap.width * bitmap.height);
    for (let i = 0; i < gray.length; i++) {
      gray[i] = rgba[i * 4];
    }
    return { width: bitmap.width, height: bitmap.height, gray };
  } finally {
    bitmap.close();
  }
}

/**
 * Separable box blur, kept in float. This is the reference port's own sliding
 * window, ported as-is so the two produce the same field; the radius is a
 * fraction of the map's width (see DEPTH_BLUR_FRACTION).
 */
function blurGray(src: Uint8Array, w: number, h: number): Float32Array {
  const radius = Math.max(1, Math.round(w * DEPTH_BLUR_FRACTION));
  const tmp = new Float32Array(w * h);

  for (let y = 0; y < h; y++) {
    let sum = 0;
    for (let x = -radius; x <= radius; x++) {
      sum += src[y * w + Math.max(0, Math.min(w - 1, x))];
    }
    for (let x = 0; x < w; x++) {
      tmp[y * w + x] = sum;
      const add = Math.max(0, Math.min(w - 1, x + radius + 1));
      const sub = Math.max(0, Math.min(w - 1, x - radius));
      sum += src[y * w + add] - src[y * w + sub];
    }
  }

  const out = new Float32Array(w * h);
  const taps = 2 * radius + 1;
  for (let x = 0; x < w; x++) {
    let sum = 0;
    for (let y = -radius; y <= radius; y++) {
      sum += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
    }
    for (let y = 0; y < h; y++) {
      out[y * w + x] = sum / (taps * taps);
      const add = Math.max(0, Math.min(h - 1, y + radius + 1));
      const sub = Math.max(0, Math.min(h - 1, y - radius));
      sum += tmp[add * w + x] - tmp[sub * w + x];
    }
  }
  return out;
}

/**
 * Bilinear sample of the blurred field. Row 0 is the image's TOP row, matching
 * `PlaneGeometry`'s v = 1 at the top vertices.
 */
function sampleDepth(field: Float32Array, w: number, h: number, u: number, v: number): number {
  const fx = u * (w - 1);
  const fy = (1 - v) * (h - 1);
  const x0 = Math.max(0, Math.min(w - 1, Math.floor(fx)));
  const y0 = Math.max(0, Math.min(h - 1, Math.floor(fy)));
  const x1 = Math.min(w - 1, x0 + 1);
  const y1 = Math.min(h - 1, y0 + 1);
  const tx = fx - x0;
  const ty = fy - y0;
  const top = field[y0 * w + x0] * (1 - tx) + field[y0 * w + x1] * tx;
  const bottom = field[y1 * w + x0] * (1 - tx) + field[y1 * w + x1] * tx;
  return top * (1 - ty) + bottom * ty;
}

export async function createReliefView(
  container: HTMLElement,
  options: ReliefOptions,
): Promise<ReliefView> {
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.domElement.className = 'relief-canvas';
  container.append(renderer.domElement);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0b0e14);

  // The pose and the projection are overwritten every frame by `applyCamera`
  // from `camera.ts`; these are only the constructor's required arguments.
  const camera = new THREE.PerspectiveCamera(
    RELIEF_FOV_DEG,
    Math.max(1, window.innerWidth) / Math.max(1, window.innerHeight),
    RELIEF_NEAR,
    RELIEF_FAR,
  );
  camera.position.set(0, 0, CAM_DIST);

  // Ambient keeps the photograph's own colours; the directional adds the real
  // shading that the recomputed normals make visible.
  scene.add(new THREE.AmbientLight(0xffffff, Math.PI));
  const sun = new THREE.DirectionalLight(0xffffff, 0.5);
  // The app's default light, through the app's own mapping: the historical
  // vector `(-0.35, 0.5, 0.75)` normalised. It is not a round pair of angles
  // picked to look right — the angles are DERIVED from that vector, so the page
  // starts on the same lighting every existing photo has.
  const [lx, ly, lz] = lightDirection(DEFAULT_LIGHT_AZIMUTH, DEFAULT_LIGHT_ELEVATION);
  sun.position.set(lx, ly, lz);
  scene.add(sun);

  const geometry = new THREE.PlaneGeometry(1, 1, SEGMENTS, SEGMENTS);
  const material = new THREE.MeshLambertMaterial({ color: 0xffffff });

  /**
   * Brightness and shadow, applied to the SAMPLED TEXEL and before the lighting.
   *
   * That placement is the app's (`ReliefRenderer.FRAG`): a lift applied after
   * the diffuse multiply would be re-darkened by it, so both tone values go onto
   * the photograph first. Only the arithmetic is the app's — the mapping from
   * the stored hundredths lives in `picture.ts`, where it is tested, and the
   * shader holds one multiply and one mix exactly as `Picture`'s javadoc asks.
   */
  const tones = {
    uBrightnessGain: { value: brightnessGain(0) },
    uShadowLift: { value: shadowLift(0) },
  };
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uBrightnessGain = tones.uBrightnessGain;
    shader.uniforms.uShadowLift = tones.uShadowLift;
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        '#include <common>\nuniform float uBrightnessGain;\nuniform float uShadowLift;',
      )
      .replace(
        '#include <map_fragment>',
        [
          '#include <map_fragment>',
          'diffuseColor.rgb *= uBrightnessGain;',
          'diffuseColor.rgb += uShadowLift * (1.0 - diffuseColor.rgb);',
        ].join('\n'),
      );
  };
  // One program for one material: without a stable key three would recompile a
  // second copy for every variant it thinks it sees.
  material.customProgramCacheKey = () => 'basrelief-picture';

  const mesh = new THREE.Mesh(geometry, material);
  mesh.frustumCulled = false;
  scene.add(mesh);

  let intensity = DEFAULT_DEPTH;
  let motion = DEFAULT_MOTION;
  let zoom = DEFAULT_ZOOM;
  let planeAspect = 1;
  /** The camera state the studio's framing rows own. */
  let orbitX = 0;
  let orbitY = 0;
  let panX = 0;
  let panY = 0;
  let camDist = 1;
  let pivotHeight = 0;
  /** The frame last handed to three; the marker and a tap read this one. */
  let latest: ReliefCamera | null = null;
  let frameListener: (() => void) | null = null;

  const signal = options.signal;
  const aborted = () => signal?.aborted === true;

  interface Assets {
    texture: THREE.Texture;
    pixels: DepthPixels;
  }

  async function loadAssets(): Promise<Assets> {
    let texture: THREE.Texture | null = null;
    try {
      texture = await new THREE.TextureLoader().loadAsync(options.imageUrl);
      if (aborted()) throw new DOMException('the viewer closed before the image loaded', 'AbortError');
      texture.colorSpace = THREE.SRGBColorSpace;
      texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
      material.map = texture;
      material.needsUpdate = true;
      planeAspect = texture.image.width / texture.image.height;

      const pixels = await readDepthPixels(options.depth);
      if (aborted()) {
        throw new DOMException('the viewer closed before the depth map loaded', 'AbortError');
      }
      return { texture, pixels };
    } catch (err) {
      texture?.dispose();
      throw err;
    }
  }

  let assets: Assets;
  try {
    assets = await loadAssets();
  } catch (err) {
    // Leave no canvas behind when the viewer closed (or a decode failed) part way.
    geometry.dispose();
    material.dispose();
    renderer.dispose();
    renderer.domElement.remove();
    throw err;
  }

  const { texture, pixels } = assets;
  const field = blurGray(pixels.gray, pixels.width, pixels.height);

  const positions = geometry.attributes.position;
  const array = positions.array as Float32Array;
  for (let i = 0; i < array.length; i += 3) {
    const u = array[i] + 0.5;
    const v = array[i + 1] + 0.5;
    array[i + 2] = sampleDepth(field, pixels.width, pixels.height, u, v) / 255 - 0.5;
  }
  positions.needsUpdate = true;
  geometry.computeVertexNormals();
  geometry.computeBoundingSphere();

  /**
   * The pivot's height is the field's MODEL value under the origin — "orbit the
   * subject", not the background.
   *
   * The blurred field holds 8-bit grey (0..255); the mesh displaces by
   * `v/255 - 0.5`, and the pivot must be lifted through that SAME mapping or it
   * marks a point the surface is not at. `heightAt` is the bilinear read.
   */
  function resolvePivotHeight(): void {
    const value = heightAt(field, pixels.width, pixels.height, orbitX, orbitY);
    pivotHeight = Math.max(-0.5, Math.min(0.5, value / 255 - 0.5));
  }

  function surfaceWidth(): number {
    return container.clientWidth || window.innerWidth;
  }

  function surfaceHeight(): number {
    return container.clientHeight || window.innerHeight;
  }

  /**
   * Hand the framing to `camera.ts` and lay that answer onto three.
   *
   * One camera, one source of truth: the eye, up and matrix all come from
   * `reliefCamera` (`CameraModel.frame`), and three is told the result rather
   * than deriving its own. The projection is the PANNED one, so Pan moves the
   * picture at every depth just as the app's clip-space shift does.
   */
  function applyCamera(): void {
    const width = surfaceWidth();
    const height = surfaceHeight();
    const surfaceAspect = width / Math.max(1, height);

    const frame = reliefCamera({
      imageAspect: planeAspect,
      surfaceAspect,
      zoom,
      depth: intensity,
      pivotHeight,
      motion,
      camDist,
      orbitX,
      orbitY,
      panX,
      panY,
      tiltX: smoothX,
      tiltY: smoothY,
      surfaceWidth: width,
      surfaceHeight: height,
    });
    latest = frame;

    // `mesh.scale.z` is the world depth factor, so a displaced point's world z
    // is `h * zScale` — the same factor the pivot's height is lifted through.
    mesh.scale.set(frame.scale * planeAspect, frame.scale, frame.zScale);
    camera.position.set(frame.eye[0], frame.eye[1], frame.eye[2]);
    camera.up.set(frame.up[0], frame.up[1], frame.up[2]);
    camera.lookAt(
      frame.eye[0] + frame.forward[0],
      frame.eye[1] + frame.forward[1],
      frame.eye[2] + frame.forward[2],
    );
    camera.updateMatrixWorld(true);
    camera.projectionMatrix.fromArray(frame.projPanned);
    camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
  }

  function fit(): void {
    const width = surfaceWidth();
    const height = surfaceHeight();
    renderer.setSize(width, height, false);
    camera.aspect = width / Math.max(1, height);
    applyCamera();
  }

  let targetX = 0;
  let targetY = 0;
  let smoothX = 0;
  let smoothY = 0;
  let last = performance.now();

  renderer.setAnimationLoop((now) => {
    const dt = Math.min(0.1, Math.max(0, (now - last) / 1000));
    last = now;
    const alpha = 1 - Math.exp(-dt / SMOOTHING_TAU);
    smoothX += (targetX - smoothX) * alpha;
    smoothY += (targetY - smoothY) * alpha;

    applyCamera();
    renderer.render(scene, camera);
    frameListener?.();
  });

  const observer = new ResizeObserver(() => {
    fit();
  });
  observer.observe(container);
  fit();

  let destroyed = false;

  function destroy(): void {
    if (destroyed) return;
    destroyed = true;
    observer.disconnect();
    renderer.setAnimationLoop(null);
    geometry.dispose();
    material.dispose();
    texture.dispose();
    renderer.dispose();
    renderer.forceContextLoss();
    renderer.domElement.remove();
  }

  return {
    setDepth(value: number): void {
      intensity = Math.max(0, Math.min(1, value));
      applyCamera();
    },
    setMotion(value: number): void {
      motion = Math.max(0, Math.min(1, value));
    },
    setOrigin(x: number, y: number): void {
      orbitX = Number.isFinite(x) ? x : 0;
      orbitY = Number.isFinite(y) ? y : 0;
      // The pivot is the SURFACE under the origin, so a new origin re-reads the
      // field's height there before the camera moves.
      resolvePivotHeight();
      applyCamera();
    },
    setPan(x: number, y: number): void {
      panX = Number.isFinite(x) ? x : 0;
      panY = Number.isFinite(y) ? y : 0;
      applyCamera();
    },
    setPanZ(scale: number): void {
      camDist = Number.isFinite(scale) ? Math.max(0.5, Math.min(5, scale)) : 1;
      applyCamera();
    },
    setZoom(value: number): void {
      zoom = Math.max(ZOOM_MIN / 100, Math.min(ZOOM_MAX / 100, value));
      fit();
    },
    onDraw(callback: () => void): void {
      frameListener = callback;
    },
    pivotPixel(): { x: number; y: number } | null {
      if (!latest) return null;
      const ndc = projectWorld(latest, latest.pivot[0], latest.pivot[1], latest.pivot[2]);
      if (!ndc) return null;
      return {
        x: (ndc[0] * 0.5 + 0.5) * surfaceWidth(),
        y: (0.5 - ndc[1] * 0.5) * surfaceHeight(),
      };
    },
    tapOrigin(ndcX: number, ndcY: number): { x: number; y: number } | null {
      if (!latest) return null;
      const orbit = reliefScreenToOrbit(
        latest,
        ndcX,
        ndcY,
        planeAspect,
        surfaceWidth() / Math.max(1, surfaceHeight()),
        zoom,
      );
      if (!orbit) return null;
      orbitX = Math.max(-ORIGIN_LIMIT, Math.min(ORIGIN_LIMIT, orbit[0]));
      orbitY = Math.max(-ORIGIN_LIMIT, Math.min(ORIGIN_LIMIT, orbit[1]));
      resolvePivotHeight();
      applyCamera();
      return { x: orbitX, y: orbitY };
    },
    setAdjust(brightness: number, shadow: number): void {
      // Repainting a uniform, not a geometry or a shader: the two lines live in
      // the compiled program already, so a drag costs no recompile.
      tones.uBrightnessGain.value = brightnessGain(brightness);
      tones.uShadowLift.value = shadowLift(shadow);
    },
    setLight(azimuthDegrees: number, elevationDegrees: number): void {
      const [x, y, z] = lightDirection(azimuthDegrees, elevationDegrees);
      sun.position.set(x, y, z);
    },
    setTilt(x: number, y: number): void {
      targetX = Math.max(-1, Math.min(1, x));
      targetY = Math.max(-1, Math.min(1, y));
    },
    resize(): void {
      fit();
    },
    destroy,
  };
}
