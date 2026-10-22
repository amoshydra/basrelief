/**
 * The `.splat` container, the cloud's camera, and the depth sort — as pure
 * functions, so the parts that are easy to get quietly wrong are checked without
 * a browser.
 *
 * This is the gallery's copy of a rule the Android app already implements, and
 * the reference is the app, not the old WebGL prototype:
 *
 *   - the 32-byte record is `android/core/.../splat/SplatCloud.java`'s layout,
 *     field for field;
 *   - the record IS the GPU texel pair (see `packIntegerTexels`) — the same
 *     argument as `SplatRenderer`'s `upload`, and `contract/splat/` now pins the
 *     bytes for both implementations;
 *   - the camera is `SplatCamera.java`'s: the cloud's own source view at rest, a
 *     turntable about the cloud's surface under the origin's ray, and the same
 *     tilt angle per unit of Motion;
 *   - the projection is `SplatRenderer.VERT` + `SplatProjection.java`: Sigma3D =
 *     R S S^T R^T, SigmaV = V Sigma3D V^T, the perspective Jacobian,
 *     Sigma2D = J SigmaV J^T, the conic, the eigen extents;
 *   - the floor (`eps2dFor`) is `Splat.eps2dFor`, the near-plane pull-back is
 *     `SplatCamera.minViewDistancePerSize`, and the alpha-extent clip is
 *     `Splat.extentScaleForAlpha`'s mapping, mirrored in the shader.
 *
 * What is deliberately NOT ported, and why:
 *
 *   - **Origin, Pan, Zoom and the Depth multiplier.** The gallery's viewer is a
 *     viewer: the orbit centre is the centre, the picture is not panned, the
 *     camera is the cloud's own and the node's depth is used as measured. That
 *     makes the at-rest invariant absolute here — the picture at rest is the
 *     photograph, with no qualification about a Depth value.
 *   - **The derived sort bound** (`SplatCamera.sortEps`, an O(count) max over
 *     every splat). It is a performance lever measured on one phone; the
 *     constant it is floored at (`SORT_EPS`) is what ships at high Motion, so it
 *     is the constant that is ported.
 *   - **The prototype's colour path.** `.design/prototype/ab/3d/splatline/`'s
 *     `index.html` uploads the record as `RGBA32F`; only its projection is a
 *     reference. See `packIntegerTexels` for the bug that avoids.
 *
 * Everything here is plain values: no DOM, no WebGL, no three. `splat.test.ts`
 * runs under plain Node type stripping.
 */

/**
 * One `.splat` record: position 3f, scale 3f, colour 4B, quaternion 4B.
 *
 * Defined here and re-exported by `media.ts`, because the classifier counts a
 * cloud from its length and this module reads its fields — one number, one home.
 */
export const SPLAT_RECORD_BYTES = 32;

/** `CameraModel.TILT_RANGE`: the lateral offset at `CAM_DIST` one unit of tilt means. */
export const TILT_RANGE = 0.16;
/** `CameraModel.CAM_DIST`. */
export const CAM_DIST = 1.2;
/**
 * `SurfaceSettings.motionScale` = `motion / 100 * 3`, so Motion 33 % is exactly
 * 1.0 and the viewer's 0..1 slider means the same thing the relief's does.
 */
export const MOTION_GAIN = 3;

export const NEAR = 0.05;
export const FAR = 4000;

/** The app's fovy clamp, so a broken lens cannot become a fisheye. */
export const MIN_FOVY_DEG = 20;
export const MAX_FOVY_DEG = 100;

/**
 * How near the origin's ray a splat must project to count as "under it", in
 * tangent-plane units. `SplatCamera.PIVOT_RADIUS`.
 */
export const PIVOT_RADIUS = 0.02;

/**
 * The largest share of the viewport's height a splat's 3-sigma extent may cover
 * before it is moved back along its own view ray.
 * `SplatCamera.MAX_EXTENT_FRACTION`.
 */
export const MAX_EXTENT_FRACTION = 0.1;

/** `Splat.ALPHA_DISCARD`: the ONE threshold both shader stages read. */
export const ALPHA_DISCARD = 1 / 255;

/** `Splat.EPS_2D_DEFAULT`: the 2D floor as a fraction of the on-screen cell. */
export const EPS_2D_DEFAULT = 0.5;

/** Texels per row of the data texture: two per splat. `SplatRenderer.DATA_W`. */
export const DATA_WIDTH = 2048;
/** Texels per row of the order texture. `SplatRenderer.ORDER_W`. */
export const ORDER_WIDTH = 2048;

/**
 * The depth-sort bucket count. `SplatRenderer.SORT_BUCKETS`, and the reason it is
 * that number rather than a round 8192.
 *
 * Blending is not commutative, so the draw order is back-to-front; a counting
 * sort gives that in one pass, but it assigns no finer key than a bucket, so two
 * splats sharing a bucket are ordered arbitrarily. The app shipped 8192 first and
 * measured that **53%** of the beach cloud's splats sat in buckets wider than
 * themselves, which is a background splat painting over a foreground one — white
 * speckle on a sky, insensitive to density. 262144 is what replaced it. At the
 * demo's 149,504 splats that is 0.57 splats per bucket, so within-bucket order is
 * essentially exact; the cost is a fixed `fill` + prefix sum over 262,145 ints
 * (~1 MB), which is independent of the count, and it runs only when the camera
 * moved past `SORT_EPS`.
 */
export const SORT_BUCKETS = 262144;

/**
 * The sort gate: `SplatCamera.SHIPPED_SORT_EPS`. A camera change smaller than
 * this cannot move any splat by a visible pixel at the app's own tilt range, so
 * it is not worth re-deriving the order. Only ever the floor here — the derived
 * bound is not ported.
 */
export const SORT_EPS = 1e-3;

/**
 * The fallback lens: the vertical field of view a cloud with no sidecar is drawn
 * through, in degrees.
 *
 * `SplatRenderer.SOURCE_FOVY`, derived rather than picked — SHARP's fallback
 * 30 mm lens on a 1440x2560 still gives `f = 2036.6 px`, so
 * `2 atan(1280/2036.6) = 64.3°`. It is the app's own "no lens stored" look, and
 * this is the number the gallery must use if the two are not to disagree; the
 * viewer says in words that this is what it is drawing, because a guessed lens
 * looks perfectly fine and is the wrong camera.
 */
export const NO_LENS_FOVY_DEG =
  (2 * Math.atan(2560 / 2 / ((30 * Math.hypot(1440, 2560)) / Math.hypot(36, 24))) * 180) /
  Math.PI;

// ------------------------------------------------------------- the record

/** One decoded record, in the units the file stores. */
export interface SplatRecord {
  position: [number, number, number];
  /** Already `exp()`-decoded by the node, not a log-scale. */
  scale: [number, number, number];
  /** 0..255 per channel; sRGB bytes, straight to the shader. */
  color: [number, number, number, number];
  /** `q * 128 + 128` per component, `w, x, y, z` — the shader decodes it. */
  quaternion: [number, number, number, number];
}

/** Whole records in a cloud of this many bytes, dropping a partial tail. */
export function recordCount(byteLength: number): number {
  if (!Number.isFinite(byteLength) || byteLength <= 0) return 0;
  return Math.floor(byteLength / SPLAT_RECORD_BYTES);
}

/**
 * One record, by index. Throws past the end rather than inventing values: this is
 * the fixture/test accessor, and a caller reading a record that is not there has a
 * bug, not a cloud.
 */
export function readRecord(bytes: Uint8Array, index: number): SplatRecord {
  if (!Number.isInteger(index) || index < 0 || index >= recordCount(bytes.length)) {
    throw new RangeError(`record ${index} is not in a ${bytes.length}-byte cloud`);
  }
  return decodeRecord(
    new DataView(bytes.buffer, bytes.byteOffset + index * SPLAT_RECORD_BYTES, SPLAT_RECORD_BYTES),
    0,
  );
}

/** One record from a view already positioned at it. */
function decodeRecord(view: DataView, offset: number): SplatRecord {
  return {
    position: [view.getFloat32(offset, true), view.getFloat32(offset + 4, true), view.getFloat32(offset + 8, true)],
    scale: [
      view.getFloat32(offset + 12, true),
      view.getFloat32(offset + 16, true),
      view.getFloat32(offset + 20, true),
    ],
    color: [
      view.getUint8(offset + 24),
      view.getUint8(offset + 25),
      view.getUint8(offset + 26),
      view.getUint8(offset + 27),
    ],
    quaternion: [
      view.getUint8(offset + 28),
      view.getUint8(offset + 29),
      view.getUint8(offset + 30),
      view.getUint8(offset + 31),
    ],
  };
}

/** Every position, contiguous — what the sort and the pivot read. */
export function positionsOf(bytes: Uint8Array, count = recordCount(bytes.length)): Float32Array {
  const out = new Float32Array(count * 3);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.length);
  for (let i = 0, j = 0; i < count; i++, j += 3) {
    const o = i * SPLAT_RECORD_BYTES;
    out[j] = view.getFloat32(o, true);
    out[j + 1] = view.getFloat32(o + 4, true);
    out[j + 2] = view.getFloat32(o + 8, true);
  }
  return out;
}

/** A cloud packed the way the data texture wants it. */
export interface PackedCloud {
  /** Texels per row; two texels per splat, so one row is `DATA_WIDTH/2` splats. */
  width: number;
  /** Whole rows, zero-padded past the last splat. */
  height: number;
  /** `width * height * 4` unsigned ints: the record's own little-endian words. */
  texels: Uint32Array;
  count: number;
}

/**
 * The record bytes as an `RGBA32UI` texel pair per splat — **integers, not
 * floats, and that is load-bearing.**
 *
 * The 32-byte record is exactly two texels: `(x, y, z, sx)` and
 * `(sy, sz, colourWord, quatWord)`, where the last two are each four `uint8` read
 * as the little-endian unsigned word they are. So the file goes to the GPU with
 * no repack: this function is a straight little-endian word copy, and the shader
 * unpacks colour and quaternion with integer shifts.
 *
 * **It must not be an `RGBA32F` texture.** WebGL1's usual trick of reading the
 * record as eight floats makes the packed colour a *NaN* for every bright splat —
 * four bytes at or above 128 in the exponent's place, i.e. alpha ≥ 128 **and**
 * blue ≥ 128 — and a NaN's payload does not survive `floatBitsToUint` on every
 * driver: Mali hands back a canonical NaN, so those splats draw flat white. That
 * is the bug `98d74ec` fixed on the Android side, and a browser on a phone has
 * the same GPU families. `contract/splat/` carries a bright record precisely so
 * this cannot be re-broken quietly: `splat.test.ts` asserts its float reading is
 * NaN and its integer words are the bytes.
 *
 * A length that is not a whole number of records is refused. The classifier
 * already calls such a file `unreadable` and never opens a viewer for it; this is
 * the second lock on the same door, because a truncated record read as a short
 * one draws a cloud that is missing the end and says nothing.
 */
export function packIntegerTexels(bytes: Uint8Array, dataWidth = DATA_WIDTH): PackedCloud {
  if (bytes.length % SPLAT_RECORD_BYTES !== 0) {
    throw new RangeError(
      `the .splat is not a whole number of ${SPLAT_RECORD_BYTES}-byte records (${bytes.length} bytes)`,
    );
  }
  const count = recordCount(bytes.length);
  if (count === 0) throw new RangeError('the .splat file is empty');
  const width = Math.max(1, Math.floor(dataWidth));
  const height = Math.ceil((count * 2) / width);
  const texels = new Uint32Array(width * height * 4);
  const view = new DataView(bytes.buffer, bytes.byteOffset, count * SPLAT_RECORD_BYTES);
  const words = count * 8;
  for (let i = 0; i < words; i++) texels[i] = view.getUint32(i * 4, true);
  return { width, height, texels, count };
}

/** The order texture's payload: the draw order as `R32UI` rows. */
export function packOrder(order: Uint32Array, orderWidth = ORDER_WIDTH): { width: number; height: number; texels: Uint32Array } {
  const width = Math.max(1, Math.floor(orderWidth));
  const height = Math.max(1, Math.ceil(order.length / width));
  const texels = new Uint32Array(width * height);
  texels.set(order);
  return { width, height, texels };
}

// --------------------------------------------------------------- the lens

/**
 * `basrelief_<tag>.splat.json`, as the app reads it: three numbers, one of which
 * is a reference frame.
 *
 * Mirrors `SplatSidecar`: `focal` is in pixels of the frame the compressor was
 * handed, `width`/`height` describe that frame, and the only form that survives
 * the browser decoding the still at a different size is the dimensionless
 * `focal / height`. `null` for a body that is not a JSON object at all; a missing
 * number is `0`, which is "no claim" and never a value to divide by.
 */
export interface SplatSidecar {
  focal: number;
  width: number;
  height: number;
  /** `focal / height`, or 0 when either cannot claim one. */
  lens: number;
}

function finiteOrZero(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

export function parseSidecar(text: string | null): SplatSidecar | null {
  if (text === null) return null;
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const o = body as Record<string, unknown>;
  const focal = Math.round(finiteOrZero(o.focal));
  const width = Math.round(finiteOrZero(o.width));
  const height = Math.round(finiteOrZero(o.height));
  return { focal, width, height, lens: focalOverHeight(focal, height) };
}

/** `Splat.focalOverHeight`: a dimensionless lens, or 0 for "no claim". */
export function focalOverHeight(focalPx: number, heightPx: number): number {
  if (!Number.isFinite(focalPx) || focalPx <= 0) return 0;
  if (!Number.isFinite(heightPx) || heightPx <= 0) return 0;
  return focalPx / heightPx;
}

/**
 * `Splat.fovyForFocalOverHeight`, clamped to the app's drawable range. 0 means
 * "no lens" — the caller draws `NO_LENS_FOVY_DEG`, never a 0-degree camera.
 */
export function fovyForLens(lens: number): number {
  if (!Number.isFinite(lens) || lens <= 0) return 0;
  const deg = (2 * Math.atan(0.5 / lens) * 180) / Math.PI;
  return Math.min(MAX_FOVY_DEG, Math.max(MIN_FOVY_DEG, deg));
}

/** The fovy a cloud is drawn through: its own lens, or the app's fallback look. */
export function sourceFovy(lens: number): number {
  const own = fovyForLens(lens);
  return own > 0 ? own : NO_LENS_FOVY_DEG;
}

// ------------------------------------------------------------- the camera

export type Vec3 = readonly [number, number, number];

export interface CloudCamera {
  /** World-to-view, column-major — GLSL's `mat4` layout. */
  view: Float32Array;
  /** The projection from the source camera, column-major. */
  proj: Float32Array;
  /** World-to-view rotation, column-major — GLSL's `mat3` layout. */
  viewRot: Float32Array;
  eye: Vec3;
  /** The view direction: `R * (0, 0, 1)`; `+z` at rest. */
  forward: Vec3;
  /** The cloud surface point the turntable turns about. */
  pivot: Vec3;
  /** False when the origin's ray found no splat and the reference plane answered. */
  pivotFound: boolean;
  /** The vertical field of view this frame uses, in degrees. */
  fovy: number;
}

export interface CloudCameraOptions {
  surfaceWidth: number;
  surfaceHeight: number;
  /** The vertical field of view; `sourceFovy(lens)` for a cloud. */
  fovyDeg: number;
  /** Already smoothed tilt in [-1, 1], the relief's sign convention. */
  tiltX: number;
  tiltY: number;
  /** `SurfaceSettings.motionScale`; 0 parks the camera. */
  motion: number;
  /** The orbit centre as a fraction of the viewport; the viewer passes 0, 0. */
  originX?: number;
  originY?: number;
  /** The cloud's xyz mirror, `count * 3` floats. */
  positions: Float32Array;
  count: number;
  /** The depth the pivot falls back to: the cloud's median z. */
  refDepth: number;
  /** Reuse a pivot resolved earlier — see `pivotAt`; it does not depend on tilt. */
  pivot?: Pivot;
}

export interface Pivot {
  x: number;
  y: number;
  z: number;
  found: boolean;
}

/**
 * The origin's ray in the source camera's tangent plane: the direction whose image
 * point is NDC `(2*originX, 2*originY)`. `SplatCamera.frame`'s own two lines —
 * `tanX = 2*originX/proj0`, `tanY = -2*originY/proj5` — in one place, so the
 * viewer can resolve the pivot once per framing and `cloudCamera` cannot derive
 * the ray a second way.
 */
export function originRay(
  fovyDeg: number,
  aspect: number,
  originX = 0,
  originY = 0,
): [number, number] {
  const proj = perspective(fovyDeg, aspect, NEAR, FAR);
  const proj0 = proj[0];
  const proj5 = proj[5];
  return [proj0 !== 0 ? (2 * originX) / proj0 : 0, proj5 !== 0 ? (-2 * originY) / proj5 : 0];
}

/**
 * The cloud's camera for one frame. `SplatCamera.frame`, with the Pan shift, the
 * Depth multiplier and the camDist dolly removed.
 *
 * **The at-rest invariant, which is the whole point.** At `tiltX = tiltY = 0`:
 * `a = b = 0`, so `R = I`, the eye returns to the source position `(0, 0, 0)`,
 * `forward = (0, 0, 1)`, `up = (0, -1, 0)` and the view is exactly
 * `diag(1, -1, -1)` — the cloud's own frame, i.e. the photograph — **whatever the
 * pivot is and whatever its depth**. Origin names the orbit centre and moves
 * nothing; that is the app's deliberate reversal (see `SplatCamera`'s class note)
 * and `splat.test.ts` pins it through this chain.
 *
 * The tilt is an ANGLE — a turntable about the pivot, not a lateral slide — so
 * the swing is the same at every pivot depth, and the pivot keeps its own screen
 * position while everything else moves.
 */
export function cloudCamera(options: CloudCameraOptions): CloudCamera {
  const surfaceW = options.surfaceWidth;
  const surfaceH = options.surfaceHeight;
  const aspect = surfaceH > 0 ? surfaceW / surfaceH : 1;
  const proj = perspective(options.fovyDeg, aspect, NEAR, FAR);

  // The origin's ray in the camera's tangent plane.
  const originX = options.originX ?? 0;
  const originY = options.originY ?? 0;
  const [tanX, tanY] = originRay(options.fovyDeg, aspect, originX, originY);
  const pivot =
    options.pivot ??
    pivotAt(options.positions, options.count, tanX, tanY, options.refDepth);

  // The turntable: R = Rx(b) * Ry(-a) about the pivot. The signs are the
  // relief's — positive tiltX moves the eye +x (right) and positive tiltY +y in
  // this frame (down), which is the same visual direction the relief moves it.
  const angle = (TILT_RANGE / CAM_DIST) * (options.motion ?? 0);
  const a = (options.tiltX ?? 0) * angle;
  const b = (options.tiltY ?? 0) * angle;
  const ca = Math.cos(a);
  const sa = Math.sin(a);
  const cb = Math.cos(b);
  const sb = Math.sin(b);

  // r = E0 - P = -P, through Ry(-a) then Rx(b); the eye sits at P + r.
  const rx = -pivot.x;
  const rz = -pivot.z;
  const yx = rx * ca - rz * sa;
  const yz = rx * sa + rz * ca;
  const ex = yx;
  const ey = -pivot.y * cb - yz * sb;
  const ez = -pivot.y * sb + yz * cb;
  const eye: Vec3 = [pivot.x + ex, pivot.y + ey, pivot.z + ez];

  // forward = R * (0, 0, 1), up = R * (0, -1, 0).
  const forward: Vec3 = [-sa, -ca * sb, ca * cb];
  const up: Vec3 = [0, -cb, -sb];

  const view = lookAt(eye, [eye[0] + forward[0], eye[1] + forward[1], eye[2] + forward[2]], up);
  // GLSL's mat3 wants the columns of V, which are the view matrix's first three
  // entries of each column.
  const viewRot = new Float32Array([
    view[0], view[1], view[2],
    view[4], view[5], view[6],
    view[8], view[9], view[10],
  ]);

  return {
    view,
    proj,
    viewRot,
    eye,
    forward,
    pivot: [pivot.x, pivot.y, pivot.z],
    pivotFound: pivot.found,
    fovy: options.fovyDeg,
  };
}

/**
 * The cloud surface point under a ray — `SplatCamera.pivot`, without the Depth
 * knob (not ported: the node's own depth is what is drawn).
 *
 * The pick is the **frontmost splat within `PIVOT_RADIUS`**, because a
 * nearest-in-image pick can name a background splat behind the subject. When no
 * splat is inside the radius the nearest one is used anyway (a real point beats a
 * guess), and when the cloud has nothing in front of the camera the answer is the
 * reference-plane point on the ray — a window claims nothing, but the orbit must
 * not vanish.
 *
 * **It does not depend on the tilt**, so the viewer resolves it once per framing
 * rather than per frame: the ray is the source camera's and the positions do not
 * move. The app resolves it inside `frame()` because that call builds the whole
 * camera at once; the answer is the same one.
 */
export function pivotAt(
  positions: Float32Array,
  count: number,
  tanX: number,
  tanY: number,
  refDepth: number,
): Pivot {
  if (count <= 0 || positions.length < count * 3) {
    return { x: tanX * refDepth, y: tanY * refDepth, z: refDepth, found: false };
  }
  const r2 = PIVOT_RADIUS * PIVOT_RADIUS;
  let frontZ = Number.POSITIVE_INFINITY;
  let frontX = 0;
  let frontY = 0;
  let front = false;
  let nearD2 = Number.POSITIVE_INFINITY;
  let nearX = 0;
  let nearY = 0;
  let nearZ = 0;
  let near = false;

  for (let i = 0, j = 0; i < count; i++, j += 3) {
    const x = positions[j];
    const y = positions[j + 1];
    const z = positions[j + 2];
    // Behind or on the camera: no image position, so not under any ray.
    if (!(z > 1e-4)) continue;
    const invz = 1 / z;
    const dx = x * invz - tanX;
    const dy = y * invz - tanY;
    const d2 = dx * dx + dy * dy;
    if (d2 < nearD2) {
      nearD2 = d2;
      nearX = x;
      nearY = y;
      nearZ = z;
      near = true;
    }
    if (d2 <= r2 && z < frontZ) {
      frontZ = z;
      frontX = x;
      frontY = y;
      front = true;
    }
  }

  if (front) return { x: frontX, y: frontY, z: frontZ, found: true };
  if (near) return { x: nearX, y: nearY, z: nearZ, found: true };
  return { x: tanX * refDepth, y: tanY * refDepth, z: refDepth, found: false };
}

/**
 * The cloud's median `z` — the pivot's fallback and the reference plane.
 * `SplatCloud.medianDepth`, which is the cloud's own centre rather than the demo
 * bale's front surface. 0 for an empty cloud, which the pivot reads as "no
 * claim".
 */
export function medianDepth(positions: Float32Array, count: number): number {
  if (count <= 0) return 0;
  const z = new Float32Array(count);
  for (let i = 0, j = 2; i < count; i++, j += 3) z[i] = positions[j];
  z.sort();
  const mid = count >> 1;
  return (count & 1) === 1 ? z[mid] : 0.5 * (z[mid - 1] + z[mid]);
}

/** `Splat.eps2dFor`: the 2D floor as `fraction * cell`, cell from the viewport. */
export function eps2dFor(
  fraction: number,
  viewportW: number,
  viewportH: number,
  count: number,
  zoom = 1,
): number {
  if (!Number.isFinite(fraction) || viewportW <= 0 || viewportH <= 0 || count <= 0) return 0;
  if (fraction === 0) return 0;
  const z = Number.isFinite(zoom) ? Math.max(0.25, Math.min(2, zoom)) : 1;
  const cell = ((viewportW * viewportH) / count) * z * z;
  return fraction * cell;
}

/**
 * `SplatCamera.minViewDistancePerSize`: the depth per unit of a splat's largest
 * 3D axis at which its 3-sigma extent is at most `maxExtentFraction` of the
 * viewport's height.
 *
 * 0 for a projection or a fraction that cannot claim one, which the shader reads
 * as "no pull-back" — a bad uniform must not pull the whole cloud back to
 * nowhere.
 */
export function minViewDistancePerSize(proj5: number, maxExtentFraction: number): number {
  if (!Number.isFinite(proj5) || proj5 <= 0) return 0;
  if (!Number.isFinite(maxExtentFraction) || maxExtentFraction <= 0) return 0;
  return (1.5 * proj5) / maxExtentFraction;
}

// ------------------------------------------------------------- projection

/**
 * A point through the real chain, into NDC — `null` behind the camera, where
 * there is no image position. This is what the tests assert with, so an
 * invariance is checked against the matrices the renderer uploads rather than
 * against a restatement of the arithmetic.
 */
export function project(camera: CloudCamera, x: number, y: number, z: number): [number, number] | null {
  const m = camera.view;
  // View-space point, column-major V.
  const vx = m[0] * x + m[4] * y + m[8] * z + m[12];
  const vy = m[1] * x + m[5] * y + m[9] * z + m[13];
  const vz = m[2] * x + m[6] * y + m[10] * z + m[14];
  const vw = m[3] * x + m[7] * y + m[11] * z + m[15];
  const p = camera.proj;
  const cx = p[0] * vx + p[4] * vy + p[8] * vz + p[12] * vw;
  const cy = p[1] * vx + p[5] * vy + p[9] * vz + p[13] * vw;
  const cw = p[3] * vx + p[7] * vy + p[11] * vz + p[15] * vw;
  if (!(cw > 0)) return null;
  return [cx / cw, cy / cw];
}

/**
 * The source camera's own NDC for a cloud point: `(f/aspect * x/z, -f * y/z)`.
 *
 * The minus on `y` is the frame, not a taste: the cloud is y-DOWN, and the view is
 * `diag(1, -1, -1)`, so view-space y is `-y` — a point above the principal point
 * has a positive `y` in the file and a positive NDC y. `SplatCamera.pxPerTilt`
 * writes the same sign.
 */
export function sourceNdc(fovyDeg: number, aspect: number, x: number, y: number, z: number): [number, number] | null {
  if (!(z > 0)) return null;
  const f = 1 / Math.tan((fovyDeg * Math.PI) / 360);
  return [(f / aspect) * (x / z), -f * (y / z)];
}

/** Column-major GL perspective, the app's `Mat4.perspective`. */
export function perspective(fovyDeg: number, aspect: number, near: number, far: number): Float32Array {
  const f = 1 / Math.tan((fovyDeg * Math.PI) / 360);
  const out = new Float32Array(16);
  out[0] = f / (aspect > 0 ? aspect : 1);
  out[5] = f;
  out[10] = (far + near) / (near - far);
  out[11] = -1;
  out[14] = (2 * far * near) / (near - far);
  return out;
}

/** Column-major GL lookAt, the app's `Mat4.lookAt`. */
export function lookAt(eye: Vec3, center: Vec3, up: Vec3): Float32Array {
  let zx = eye[0] - center[0];
  let zy = eye[1] - center[1];
  let zz = eye[2] - center[2];
  let len = Math.hypot(zx, zy, zz) || 1;
  zx /= len;
  zy /= len;
  zz /= len;

  let xx = up[1] * zz - up[2] * zy;
  let xy = up[2] * zx - up[0] * zz;
  let xz = up[0] * zy - up[1] * zx;
  len = Math.hypot(xx, xy, xz) || 1;
  xx /= len;
  xy /= len;
  xz /= len;

  const yx = zy * xz - zz * xy;
  const yy = zz * xx - zx * xz;
  const yz = zx * xy - zy * xx;

  const out = new Float32Array(16);
  out[0] = xx;
  out[1] = yx;
  out[2] = zx;
  out[4] = xy;
  out[5] = yy;
  out[6] = zy;
  out[8] = xz;
  out[9] = yz;
  out[10] = zz;
  out[12] = -(xx * eye[0] + xy * eye[1] + xz * eye[2]);
  out[13] = -(yx * eye[0] + yy * eye[1] + yz * eye[2]);
  out[14] = -(zx * eye[0] + zy * eye[1] + zz * eye[2]);
  out[15] = 1;
  return out;
}

// ------------------------------------------------------------ the sort

export interface DepthOrder {
  readonly buckets: number;
  readonly order: Uint32Array;
  /** Back-to-front for this camera; the caller reuses the returned array. */
  sort(positions: Float32Array, count: number, camera: CloudCamera): Uint32Array;
}

/**
 * A back-to-front counting sort, allocating its buffers once.
 *
 * **Why sorted at all.** Alpha blending is not commutative and a splat cloud has
 * no opaque surface to write depth against, so the draw order IS the depth test.
 * Unsorted, the last-drawn splat wins regardless of depth: a background splat
 * paints over the subject, which reads as speckle that no density fixes — the
 * app measured it at 1.2M splats and it was still there.
 *
 * The mapping is `bucket = (B - 1) - (depth - dmin) * scale`, so bucket 0 holds
 * the farthest splats and the order array visits them first.
 */
export function createDepthOrder(count: number, buckets = SORT_BUCKETS): DepthOrder {
  const b = Math.max(1, Math.floor(buckets));
  const n = Math.max(0, Math.floor(count));
  const order = new Uint32Array(n);
  const depths = new Float32Array(n);
  // B + 1 because the counting sort compares each bucket's running end against
  // the next one's, which is `SplatRenderer.sort`'s own array shape.
  const counts = new Int32Array(b + 1);

  return {
    buckets: b,
    order,
    sort(positions: Float32Array, countIn: number, camera: CloudCamera): Uint32Array {
      const total = Math.min(n, countIn, Math.floor(positions.length / 3));
      if (total <= 0) return order;
      const ex = camera.eye[0];
      const ey = camera.eye[1];
      const ez = camera.eye[2];
      const fx = camera.forward[0];
      const fy = camera.forward[1];
      const fz = camera.forward[2];

      let dmin = Number.POSITIVE_INFINITY;
      let dmax = Number.NEGATIVE_INFINITY;
      for (let i = 0, j = 0; i < total; i++, j += 3) {
        const v = fx * (positions[j] - ex) + fy * (positions[j + 1] - ey) + fz * (positions[j + 2] - ez);
        depths[i] = v;
        if (v < dmin) dmin = v;
        if (v > dmax) dmax = v;
      }
      // A cloud with no depth spread at all has one bucket, not a divide by 0.
      const span = dmax - dmin > 1e-6 ? dmax - dmin : 1e-6;
      const scale = (b - 1) / span;

      counts.fill(0);
      for (let i = 0; i < total; i++) {
        let k = (b - 1) - Math.trunc((depths[i] - dmin) * scale);
        if (k < 0) k = 0;
        if (k >= b) k = b - 1;
        counts[k + 1]++;
      }
      for (let k = 1; k <= b; k++) counts[k] += counts[k - 1];
      for (let i = 0; i < total; i++) {
        let k = (b - 1) - Math.trunc((depths[i] - dmin) * scale);
        if (k < 0) k = 0;
        if (k >= b) k = b - 1;
        order[counts[k]++] = i;
      }
      return order;
    },
  };
}
