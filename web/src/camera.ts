/**
 * The relief's camera, as plain matrices — the port of `CameraModel`.
 *
 * The gallery's first pass drew the relief with a camera that could only swing:
 * the orbit centre was the centre, there was no pan and no tap. That was a
 * viewer's camera, not the studio's, and the studio's is the reference
 * (`AGENTS.md`: Android owns the design). This module is `CameraModel.frame` and
 * `CameraModel.screenToOrbit` with the same constants, the same turntable about
 * the surface under the origin, the same fixed rest eye and the same pan:
 *
 *   - the eye is the rest eye `(0, 0, CAM_DIST * camDist)`, rotated ABOUT the
 *     pivot, and the orientation rotates with it — so at rest the view is the
 *     photograph whatever the origin is, and the pivot keeps its own screen
 *     position under a tilt while everything else swings;
 *   - the pivot is the SURFACE point under the origin
 *     (`(originX*aspect*scale, originY*scale, pivotHeight*scale)`), with its
 *     height read from the depth field by {@link heightAt} — "it orbits around
 *     the subject", not the background;
 *   - Pan is one clip-space shift (`-2*panX/W`, `+2*panY/H`) applied to the
 *     projection, so it moves the picture at every depth and never moves the
 *     camera;
 *   - Zoom scales the plane (`computeScale`), which is a crop of this same
 *     camera rather than a second one;
 *   - a tap unprojects through the camera as it is, Pan and current pivot
 *     height included, to the plane point under the finger.
 *
 * Pure values, no DOM and no three: `relief.ts` sets three's camera FROM this
 * answer, and `camera.test.ts` asserts the round trips without a browser.
 */

import { CAM_DIST, TILT_RANGE, lookAt, multiply, perspective, type Vec3 } from './splat.ts';

/** `CameraModel.FOV_DEG`. */
export const RELIEF_FOV_DEG = 45;
/** `CameraModel.CROP_MARGIN`. */
export const RELIEF_CROP_MARGIN = 1.08;
/** `CameraModel.NEAR` / `FAR`. */
export const RELIEF_NEAR = 0.1;
export const RELIEF_FAR = 10;

export interface ReliefFraming {
  /** The photograph's width / height. */
  imageAspect: number;
  /** The viewport's width / height. */
  surfaceAspect: number;
  /** Zoom as a multiplier, 0.25..2 — the studio's stored percentage / 100. */
  zoom: number;
  /** Depth as a fraction of the plane's height, 0..1. */
  depth: number;
  /**
   * `ReliefMesh.heightAt`'s answer at the origin, in model units
   * (`depth/255 - 0.5`, so [-0.5, 0.5]).
   */
  pivotHeight: number;
  /** `SurfaceSettings.motionScale`, 0..1; 0 parks the camera. */
  motion: number;
  /** Pan Z: the camera distance. 1 is the source camera. */
  camDist: number;
  /** The Origin row, as a fraction of the picture; ±`ORIGIN_LIMIT`. */
  orbitX: number;
  orbitY: number;
  /** The Pan row, in surface (CSS) pixels. */
  panX: number;
  panY: number;
  /** The smoothed tilt, [-1, 1]. */
  tiltX: number;
  tiltY: number;
  /** The drawing surface in CSS pixels, for the pan shift. */
  surfaceWidth: number;
  surfaceHeight: number;
}

export interface ReliefCamera {
  /** The plane's height in world units: the mesh's y scale. */
  scale: number;
  /** The mesh's z scale in world units; a displaced point's world z is `h * zScale`. */
  zScale: number;
  /** The orbit centre in world space: the surface under the origin. */
  pivot: Vec3;
  eye: Vec3;
  forward: Vec3;
  up: Vec3;
  /** The source projection (no pan), column-major. */
  proj: Float32Array;
  /** `T(pan) * proj` — what the draw uses. */
  projPanned: Float32Array;
  /** World-to-view, column-major. */
  view: Float32Array;
  /** `projPanned * view`; the marker and the tap go through this. */
  viewProjection: Float32Array;
}

/** `CameraModel.computeScale`: the plane's height that covers the viewport. */
export function reliefScale(imageAspect: number, surfaceAspect: number, zoom: number): number {
  if (!(imageAspect > 0)) return 0;
  const visH = 2 * CAM_DIST * Math.tan((RELIEF_FOV_DEG * Math.PI) / 360);
  const visW = visH * (surfaceAspect > 0 ? surfaceAspect : 1);
  const z = Number.isFinite(zoom) ? Math.max(0.25, Math.min(2, zoom)) : 1;
  return Math.max(visH, visW / imageAspect) * RELIEF_CROP_MARGIN * z;
}

/**
 * One frame's camera — `CameraModel.frame`, in the same order.
 *
 * `clip = projPanned * view * model`, and {@link reliefScreenToOrbit} inverts
 * `projPanned * view` in that SAME order. Getting it backwards is a real
 * historical bug, which is why the round trip is asserted rather than trusted.
 */
export function reliefCamera(f: ReliefFraming): ReliefCamera {
  const scale = reliefScale(f.imageAspect, f.surfaceAspect, f.zoom);
  // The gallery's displacement is a fraction of the plane's height — the port's
  // rule, kept so this change does not alter every existing relief's depth. The
  // PIVOT is lifted through the same factor the vertex shader applies.
  const zScale = Math.max(0, Math.min(1, f.depth)) * scale;
  const camDist = Number.isFinite(f.camDist) ? Math.max(0.5, Math.min(5, f.camDist)) : 1;
  const pivotHeight = Number.isFinite(f.pivotHeight)
    ? Math.max(-0.5, Math.min(0.5, f.pivotHeight))
    : 0;

  const px = f.orbitX * f.imageAspect * scale;
  const py = f.orbitY * scale;
  const pz = pivotHeight * zScale;

  const proj = perspective(
    RELIEF_FOV_DEG,
    f.surfaceAspect > 0 ? f.surfaceAspect : 1,
    RELIEF_NEAR,
    RELIEF_FAR,
  );

  const panMatrix = new Float32Array(16);
  panMatrix[0] = panMatrix[5] = panMatrix[10] = panMatrix[15] = 1;
  if (f.surfaceWidth > 0 && f.surfaceHeight > 0) {
    panMatrix[12] = (-2 * f.panX) / f.surfaceWidth;
    panMatrix[13] = (2 * f.panY) / f.surfaceHeight;
  }
  const projPanned = multiply(panMatrix, proj);

  // The turntable: the rest eye rotated about the pivot. The angle is the
  // splat's (`TILT_RANGE / CAM_DIST` per unit of tilt, times motion), so the
  // swing is the same at every pivot depth.
  const angle = (TILT_RANGE / (CAM_DIST * camDist)) * f.motion;
  const a = f.tiltX * angle;
  const b = f.tiltY * angle;
  const ca = Math.cos(a);
  const sa = Math.sin(a);
  const cb = Math.cos(b);
  const sb = Math.sin(b);

  const dx = -px;
  const dy = -py;
  const dz = CAM_DIST * camDist - pz;
  const ex = ca * dx + sa * dz;
  const ey = sb * sa * dx + cb * dy - sb * ca * dz;
  const ez = -cb * sa * dx + sb * dy + cb * ca * dz;
  const eye: Vec3 = [px + ex, py + ey, pz + ez];
  const forward: Vec3 = [-sa, sb * ca, -cb * ca];
  const up: Vec3 = [0, cb, sb];

  const view = lookAt(eye, [eye[0] + forward[0], eye[1] + forward[1], eye[2] + forward[2]], up);
  const viewProjection = multiply(projPanned, view);

  return {
    scale,
    zScale,
    pivot: [px, py, pz],
    eye,
    forward,
    up,
    proj,
    projPanned,
    view,
    viewProjection,
  };
}

/**
 * A tap, unprojected to the plane z = 0 — `CameraModel.screenToOrbit`.
 *
 * Returns the Origin fraction under the finger, or null when the ray misses the
 * plane (the plane is always hit with a perspective camera; null is the
 * degenerate camera and the off-picture case, where a new orbit centre would be
 * a number the row cannot hold).
 */
export function reliefScreenToOrbit(
  camera: ReliefCamera,
  ndcX: number,
  ndcY: number,
  imageAspect: number,
  surfaceAspect: number,
  zoom: number,
): [number, number] | null {
  const inverse = invert(camera.viewProjection);
  if (!inverse) return null;

  const near = unproject(inverse, ndcX, ndcY, -1);
  const far = unproject(inverse, ndcX, ndcY, 1);
  if (!near || !far) return null;
  const dz = far[2] - near[2];
  if (dz === 0) return null;
  const t = -near[2] / dz;
  const wx = near[0] + (far[0] - near[0]) * t;
  const wy = near[1] + (far[1] - near[1]) * t;

  const scale = reliefScale(imageAspect, surfaceAspect, zoom);
  if (!(scale > 0)) return null;
  const ox = wx / (imageAspect * scale);
  const oy = wy / scale;
  // A tap outside the picture names no point ON it; the app refuses it rather
  // than storing an origin a pixel beside the photo.
  if (Math.abs(ox) > 0.5 || Math.abs(oy) > 0.5) return null;
  return [ox, oy];
}

/**
 * The blurred field's height under an image position — `ReliefMesh.heightAt`.
 *
 * Row 0 is the image's TOP row and the origin's y is up-positive, so the row is
 * `(0.5 - oy) * (h - 1)`. Bilinear, clamped at the edge; 0 without a field, so
 * the pivot on an absent field is the plane rather than NaN.
 */
export function heightAt(
  field: Float32Array | null,
  width: number,
  height: number,
  orbitX: number,
  orbitY: number,
): number {
  if (!field || width < 1 || height < 1 || field.length < width * height) return 0;
  const gx = Math.max(0, Math.min(width - 1, (orbitX + 0.5) * (width - 1)));
  const gy = Math.max(0, Math.min(height - 1, (0.5 - orbitY) * (height - 1)));
  const x0 = Math.floor(gx);
  const y0 = Math.floor(gy);
  const x1 = Math.min(width - 1, x0 + 1);
  const y1 = Math.min(height - 1, y0 + 1);
  const tx = gx - x0;
  const ty = gy - y0;
  const v00 = field[y0 * width + x0];
  const v10 = field[y0 * width + x1];
  const v01 = field[y1 * width + x0];
  const v11 = field[y1 * width + x1];
  return (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty;
}

/** A world point through `projPanned * view`, into NDC; null behind the eye. */
export function projectWorld(
  camera: ReliefCamera,
  x: number,
  y: number,
  z: number,
): [number, number] | null {
  const m = camera.viewProjection;
  const cx = m[0] * x + m[4] * y + m[8] * z + m[12];
  const cy = m[1] * x + m[5] * y + m[9] * z + m[13];
  const cw = m[3] * x + m[7] * y + m[11] * z + m[15];
  if (!(cw > 0)) return null;
  return [cx / cw, cy / cw];
}

function unproject(inverse: Float32Array, ndcX: number, ndcY: number, z: number): Vec3 | null {
  const x = inverse[0] * ndcX + inverse[4] * ndcY + inverse[8] * z + inverse[12];
  const y = inverse[1] * ndcX + inverse[5] * ndcY + inverse[9] * z + inverse[13];
  const zz = inverse[2] * ndcX + inverse[6] * ndcY + inverse[10] * z + inverse[14];
  const w = inverse[3] * ndcX + inverse[7] * ndcY + inverse[11] * z + inverse[15];
  if (w === 0) return null;
  return [x / w, y / w, zz / w];
}

/** Column-major 4x4 inverse; null for a singular matrix. */
export function invert(m: Float32Array): Float32Array | null {
  const out = new Float32Array(16);
  const a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
  const a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
  const a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
  const a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];

  const b00 = a00 * a11 - a01 * a10;
  const b01 = a00 * a12 - a02 * a10;
  const b02 = a00 * a13 - a03 * a10;
  const b03 = a01 * a12 - a02 * a11;
  const b04 = a01 * a13 - a03 * a11;
  const b05 = a02 * a13 - a03 * a12;
  const b06 = a20 * a31 - a21 * a30;
  const b07 = a20 * a32 - a22 * a30;
  const b08 = a20 * a33 - a23 * a30;
  const b09 = a21 * a32 - a22 * a31;
  const b10 = a21 * a33 - a23 * a31;
  const b11 = a22 * a33 - a23 * a32;

  const det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
  if (!Number.isFinite(det) || det === 0) return null;
  const inv = 1 / det;

  out[0] = (a11 * b11 - a12 * b10 + a13 * b09) * inv;
  out[1] = (a02 * b10 - a01 * b11 - a03 * b09) * inv;
  out[2] = (a31 * b05 - a32 * b04 + a33 * b03) * inv;
  out[3] = (a22 * b04 - a21 * b05 - a23 * b03) * inv;
  out[4] = (a12 * b08 - a10 * b11 - a13 * b07) * inv;
  out[5] = (a00 * b11 - a02 * b08 + a03 * b07) * inv;
  out[6] = (a32 * b02 - a30 * b05 - a33 * b01) * inv;
  out[7] = (a20 * b05 - a22 * b02 + a23 * b01) * inv;
  out[8] = (a10 * b10 - a11 * b08 + a13 * b06) * inv;
  out[9] = (a01 * b08 - a00 * b10 - a03 * b06) * inv;
  out[10] = (a30 * b04 - a31 * b02 + a33 * b00) * inv;
  out[11] = (a21 * b02 - a20 * b04 - a23 * b00) * inv;
  out[12] = (a11 * b07 - a10 * b09 - a12 * b06) * inv;
  out[13] = (a00 * b09 - a01 * b07 + a02 * b06) * inv;
  out[14] = (a31 * b01 - a30 * b03 - a32 * b00) * inv;
  out[15] = (a20 * b03 - a21 * b01 + a22 * b00) * inv;
  return out;
}
