import {
  ALPHA_DISCARD,
  DATA_WIDTH,
  EPS_2D_DEFAULT,
  MAX_EXTENT_FRACTION,
  MOTION_GAIN,
  NEAR,
  ORDER_WIDTH,
  SORT_EPS,
  cloudCamera,
  createDepthOrder,
  eps2dFor,
  medianDepth,
  minViewDistancePerSize,
  originRay,
  packIntegerTexels,
  packOrder,
  pivotAt,
  positionsOf,
  sourceFovy,
  type CloudCamera,
  type Pivot,
} from './splat.ts';

/**
 * The Gaussian splat renderer.
 *
 * The second medium behind the viewer's single dispatch point, and a port of
 * `android/app/src/main/java/com/amoshydra/basrelief/SplatRenderer.java` plus
 * `SplatProjection.java` — **not** of the old WebGL prototype's renderer, which
 * is on the float colour path (see `splat.ts`'s `packIntegerTexels`).
 *
 * What is taken from the app, and why it is taken rather than reinvented:
 *
 *   - **One card per splat, from `gl_InstanceID`.** Six vertices of
 *     `drawArraysInstanced`, with the sample, the corner and the UV derived from
 *     the id and the record fetched straight out of the data texture. No VBO, no
 *     per-frame allocation, and `count` is one draw-call argument.
 *   - **The record is the texel pair** (`RGBA32UI`, two texels per splat), sampled
 *     with `usampler2D` + `texelFetch` and unpacked with integer shifts. This is
 *     the point of the port: the prototype uploads `RGBA32F`, which turns every
 *     opaque bright splat's colour word into a NaN.
 *   - **The full covariance chain**, step for step from `SplatProjection`.
 *   - **The depth sort**: a counting sort into `SORT_BUCKETS` buckets, re-derived
 *     only when the camera moved past `SORT_EPS`.
 *   - **The 2D floor** (`Splat.eps2dFor`'s fraction of the on-screen cell), the
 *     **near-plane pull-back**, the **alpha-extent clip** and the **radius clip**.
 *
 * What is not here, deliberately: Origin, Pan, Zoom, the Depth multiplier, the
 * Brightness/Shadow grade, the offscreen render scale and the post-blur. The
 * gallery is a viewer; the orbit centre is the centre, the lens is the cloud's
 * own, the depth is the node's, and the picture at rest is the photograph with no
 * qualification.
 *
 * **There is no backdrop, and that is the app's decision rather than an
 * omission.** The gaps a tilt opens are the clear colour — black. Two fills were
 * tried on the phone and rejected (see AGENTS.md): a downscaled copy of the
 * photograph read as a double exposure, and row-median bands as not nice. A fill
 * with no content in it is not something to look at.
 */

/** `SplatRenderer`'s clear colour: near-black, and not the gallery's own navy. */
const BACKGROUND: [number, number, number] = [0.043, 0.043, 0.047];

/** `TiltFilter.SMOOTHING_TAU`: one time constant, so the feel is the same at 60/90/120 Hz. */
const SMOOTHING_TAU = 0.2;

/**
 * The vertex stage: one quad per splat, and the full-covariance projector.
 *
 * `SplatRenderer.VERT` with the removed features removed rather than left as
 * always-one uniforms: there is no `uDepthMul`/`uDepthRef` (the Z-only
 * displacement is the Depth knob), no Pan shift, and no Brightness/Shadow. What
 * stays is everything that changes what a pixel is: the near pull-back, the near
 * cull, the alpha-extent clip, the covariance chain, the floor, the conic, the
 * radius clip and the off-screen cull.
 */
export const CLOUD_VERT = `#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

uniform mat4 uProj;
uniform mat4 uView;
uniform mat3 uViewRot;
uniform usampler2D uData;
uniform usampler2D uOrder;
uniform int uDataW;
uniform int uOrderW;
uniform float uNear;
uniform vec2 uFocal;
uniform vec2 uViewport;
uniform float uEps2d;
uniform float uAlphaDiscard;
uniform float uMinViewDistancePerSize;

layout(location = 0) in vec2 aCorner;

out vec4 vColor;
out vec2 vQ;
out vec2 vCenterPx;
out vec3 vConic;

vec4 unpack_unorm_4x8(uint u) {
  return vec4(u & 0xFFu, (u >> 8) & 0xFFu, (u >> 16) & 0xFFu, (u >> 24) & 0xFFu) * (1.0 / 255.0);
}

void main() {
  uint sp = texelFetch(uOrder, ivec2(gl_InstanceID % uOrderW, gl_InstanceID / uOrderW), 0).x;
  ivec2 c0 = ivec2(int(2u * sp) % uDataW, int(2u * sp) / uDataW);
  ivec2 c1 = ivec2(int(2u * sp + 1u) % uDataW, int(2u * sp + 1u) / uDataW);
  uvec4 A = texelFetch(uData, c0, 0);
  uvec4 B = texelFetch(uData, c1, 0);

  vec3 spPos = vec3(uintBitsToFloat(A.x), uintBitsToFloat(A.y), uintBitsToFloat(A.z));
  vec3 spScale = vec3(uintBitsToFloat(A.w), uintBitsToFloat(B.x), uintBitsToFloat(B.y));
  vec4 spCol = unpack_unorm_4x8(B.z);
  vec4 q4 = unpack_unorm_4x8(B.w) * 2.0 - 1.0;

  vec4 mv = uView * vec4(spPos, 1.0);
  float depth = -mv.z;

  // The near-plane pull-back: a splat nearer than its own minimum view distance is
  // moved back along its own view ray, which leaves its screen position exactly
  // unchanged (clip.xy and clip.w scale together) and shrinks its covariance by
  // the square of that scale. The size is deliberately NOT scaled with it.
  float size3d = max(max(abs(spScale.x), abs(spScale.y)), abs(spScale.z));
  float zmin = uMinViewDistancePerSize * size3d;
  if (depth > 0.0 && depth < zmin) {
    mv.xyz *= zmin / depth;
    depth = zmin;
  }
  vColor = spCol;
  vQ = aCorner;
  vConic = vec3(0.0);
  vCenterPx = vec2(0.0);
  if (depth < uNear) {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    return;
  }

  // The alpha-extent clip: the fragment stage discards below uAlphaDiscard, and
  // that boundary is a radius, so scaling the quad by
  // min(1, sqrt(2 ln(a / thr)) / 3) covers exactly the region it keeps. A splat at
  // or below the threshold has no surviving radius and is dropped outright.
  if (!(spCol.a > uAlphaDiscard)) {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    return;
  }
  float keep = min(1.0, sqrt(2.0 * log(spCol.a / uAlphaDiscard)) / 3.0);

  float n = inversesqrt(max(dot(q4, q4), 1e-6));
  q4 *= n;
  float qw = q4.x, qx = q4.y, qy = q4.z, qz = q4.w;
  mat3 Rq = mat3(
    1.0 - 2.0 * (qy * qy + qz * qz), 2.0 * (qx * qy + qw * qz), 2.0 * (qx * qz - qw * qy),
    2.0 * (qx * qy - qw * qz), 1.0 - 2.0 * (qx * qx + qz * qz), 2.0 * (qy * qz + qw * qx),
    2.0 * (qx * qz + qw * qy), 2.0 * (qy * qz - qw * qx), 1.0 - 2.0 * (qx * qx + qy * qy));

  mat3 M = Rq * mat3(spScale.x, 0.0, 0.0,
                     0.0, spScale.y, 0.0,
                     0.0, 0.0, spScale.z);
  mat3 Sig3 = M * transpose(M);
  mat3 SigV = uViewRot * Sig3 * transpose(uViewRot);
  float c00 = SigV[0][0];
  float c01 = SigV[0][1];
  float c02 = SigV[0][2];
  float c11 = SigV[1][1];
  float c12 = SigV[1][2];
  float c22 = SigV[2][2];

  float invd = 1.0 / depth;
  float jx0 = uFocal.x * invd;
  float jx2 = uFocal.x * mv.x * invd * invd;
  float jy1 = uFocal.y * invd;
  float jy2 = uFocal.y * mv.y * invd * invd;
  float u00 = jx0 * c00 + jx2 * c02;
  float u01 = jx0 * c01 + jx2 * c12;
  float u02 = jx0 * c02 + jx2 * c22;
  float u11 = jy1 * c11 + jy2 * c12;
  float u12 = jy1 * c12 + jy2 * c22;
  float cov00 = u00 * jx0 + u02 * jx2;
  float cov01 = u01 * jy1 + u02 * jy2;
  float cov11 = u11 * jy1 + u12 * jy2;

  // The 2D low-pass floor, gsplat's eps2d: a FLOOR, not a blur. It lifts only the
  // splats too small to sample, so it closes the screen-door without smearing the
  // ones that are well sampled. Already in render pixels, from Splat.eps2dFor.
  cov00 += uEps2d;
  cov11 += uEps2d;

  float det = cov00 * cov11 - cov01 * cov01;
  if (det <= 1e-9) {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    return;
  }
  vConic = vec3(cov11 / det, -cov01 / det, cov00 / det);

  float mid = 0.5 * (cov00 + cov11);
  float root = sqrt(max(mid * mid - det, 0.0));
  float l1 = mid + root;
  float l2 = max(mid - root, 0.0);
  float r1 = 3.0 * sqrt(max(l1, 0.0));
  float r2 = 3.0 * sqrt(max(l2, 0.0));
  // The radius clip (gsplat's radius_clip): a splat whose 3-sigma extent is a
  // large fraction of the frame is a splat at the camera, and its quad costs a
  // screenful of fragments to lay down a wash. Dropped, not clamped — a clamped
  // quad leaves the hole its own wash was covering.
  if (max(r1, r2) > 0.5 * uViewport.y) {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    return;
  }
  vec2 e1 = vec2(cov01, l1 - cov00);
  float el = length(e1);
  e1 = el > 1e-9 ? e1 / el : vec2(1.0, 0.0);
  vec2 e2 = vec2(e1.y, -e1.x);

  vec2 offsetPx = keep * (aCorner.x * r1 * e1 + aCorner.y * r2 * e2);
  vec2 offsetNdc = offsetPx * 2.0 / uViewport;
  vec4 clip = uProj * mv;
  vec2 ndc = clip.xy / clip.w;
  vec2 ext = abs(offsetNdc);
  if (abs(ndc.x) - ext.x > 1.0 || abs(ndc.y) - ext.y > 1.0) {
    gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
    return;
  }
  gl_Position = vec4((ndc + offsetNdc) * clip.w, clip.z, clip.w);
  vCenterPx = (ndc * 0.5 + 0.5) * uViewport;
}
`;

/**
 * The fragment stage: the conic's Gaussian weight and the alpha discard.
 *
 * `SplatRenderer.FRAG` without the Brightness/Shadow grade — the gallery's cloud
 * has no such controls, and a grade nothing sets is a multiply that reads like a
 * feature.
 */
export const CLOUD_FRAG = `#version 300 es
precision highp float;

uniform float uAlphaDiscard;

in vec4 vColor;
in vec2 vQ;
in vec2 vCenterPx;
in vec3 vConic;
out vec4 outColor;

void main() {
  if (dot(vQ, vQ) > 1.0) {
    discard;
  }
  vec2 d = gl_FragCoord.xy - vCenterPx;
  float power = -0.5 * (vConic.x * d.x * d.x + 2.0 * vConic.y * d.x * d.y + vConic.z * d.y * d.y);
  if (power > 0.0) {
    discard;
  }
  float a = vColor.a * exp(power);
  if (a < uAlphaDiscard) {
    discard;
  }
  outColor = vec4(vColor.rgb, a);
}
`;

export interface CloudView {
  /** The viewer's 0..1 Motion; 33 % is the app's 1.0 (`SurfaceSettings.motionScale`). */
  setMotion(value: number): void;
  /** Tilt target in [-1, 1], the relief's sign; the drawn value eases toward it. */
  setTilt(x: number, y: number): void;
  resize(): void;
  destroy(): void;
}

export interface CloudOptions {
  /** The `.splat` file's bytes. A length that is not whole records is refused. */
  bytes: Uint8Array;
  /** `focal / height` from the cloud's sidecar, or 0 for "no lens". */
  lens: number;
}

/** The app's six corner vertices, in its own order. */
const CORNERS = new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]);

const UNIFORM_NAMES = [
  'uProj',
  'uView',
  'uViewRot',
  'uData',
  'uOrder',
  'uDataW',
  'uOrderW',
  'uNear',
  'uFocal',
  'uViewport',
  'uEps2d',
  'uAlphaDiscard',
  'uMinViewDistancePerSize',
] as const;

export function createCloudView(container: HTMLElement, options: CloudOptions): CloudView {
  const canvas = document.createElement('canvas');
  canvas.className = 'cloud-canvas';
  container.append(canvas);

  const context = canvas.getContext('webgl2', {
    alpha: false,
    // MSAA would antialias the EDGES of the quads that are drawn; the artifact is
    // the black gaps between splats that are NOT drawn, which is a coverage
    // problem. The app measured 4x MSAA leaving the screen-door exactly as it was.
    antialias: false,
    depth: false,
    stencil: false,
    premultipliedAlpha: false,
    powerPreference: 'high-performance',
  });
  if (!context) {
    canvas.remove();
    throw new Error(
      'this browser has no WebGL2, and a splat cloud needs integer textures (RGBA32UI) — ' +
        'a float upload would draw every bright splat as a NaN',
    );
  }
  // Assigned after the check, because the rest of this function is made of hoisted
  // declarations and a narrowed `context` does not survive into those.
  const gl: WebGL2RenderingContext = context;

  /** Everything that has to survive a teardown, so the error path can undo it. */
  let program: WebGLProgram | null = null;
  let dataTexture: WebGLTexture | null = null;
  let orderTexture: WebGLTexture | null = null;
  let vao: WebGLVertexArrayObject | null = null;
  let cornerBuffer: WebGLBuffer | null = null;
  let observer: ResizeObserver | null = null;
  let frameId = 0;
  let destroyed = false;

  function teardown(): void {
    if (destroyed) return;
    destroyed = true;
    cancelAnimationFrame(frameId);
    observer?.disconnect();
    if (dataTexture) gl.deleteTexture(dataTexture);
    if (orderTexture) gl.deleteTexture(orderTexture);
    if (cornerBuffer) gl.deleteBuffer(cornerBuffer);
    if (vao) gl.deleteVertexArray(vao);
    if (program) gl.deleteProgram(program);
    dataTexture = null;
    orderTexture = null;
    cornerBuffer = null;
    vao = null;
    program = null;
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    canvas.remove();
  }

  try {
    // Everything up to the first frame can throw, and every throw has to leave no
    // canvas behind — the viewer replaces a failed renderer with a message.
    return buildView();
  } catch (err) {
    teardown();
    throw err;
  }

  function buildView(): CloudView {
    const packed = packIntegerTexels(options.bytes, DATA_WIDTH);
    const positions = positionsOf(options.bytes);
    const refDepth = medianDepth(positions, packed.count);
    const fovy = sourceFovy(options.lens);
    const sorter = createDepthOrder(packed.count);

    program = build(gl, CLOUD_VERT, CLOUD_FRAG);
    const locations = new Map<string, WebGLUniformLocation>();
    for (const name of UNIFORM_NAMES) {
      const location = gl.getUniformLocation(program, name);
      // A declared uniform that the linker dropped reads as zero in the shader.
      // On the app side that shipped as a black-normal bug, so it is refused here
      // rather than assumed away.
      if (!location) throw new Error(`the cloud shader dropped uniform ${name}`);
      locations.set(name, location);
    }
    const uniform = (name: (typeof UNIFORM_NAMES)[number]) => locations.get(name) as WebGLUniformLocation;

    vao = gl.createVertexArray();
    cornerBuffer = gl.createBuffer();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, cornerBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, CORNERS, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindVertexArray(null);

    dataTexture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, dataTexture);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    // RGBA32UI, not RGBA32F: the record's bytes ARE the texel. See splat.ts.
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA32UI,
      packed.width,
      packed.height,
      0,
      gl.RGBA_INTEGER,
      gl.UNSIGNED_INT,
      packed.texels,
    );
    nearest(gl);

    // The order texture: allocated once at the cloud's size and rewritten with
    // texSubImage2D on each sort, which is the app's own shape (a fresh
    // glTexImage2D per sort would reallocate a megabyte fifty times a second).
    const orderSize = packOrder(sorter.order, ORDER_WIDTH);
    orderTexture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, orderTexture);
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.R32UI,
      orderSize.width,
      orderSize.height,
      0,
      gl.RED_INTEGER,
      gl.UNSIGNED_INT,
      orderSize.texels,
    );
    nearest(gl);

    // The one bound this renderer cannot argue with: the GPU's own texture size.
    // A cloud too large for the device says so instead of drawing nothing.
    const error = gl.getError();
    if (error !== gl.NO_ERROR) {
      throw new Error(
        `this GPU refused the cloud's textures (${packed.count} splats → ` +
          `${packed.width}×${packed.height} integer texels, GL error ${error})`,
      );
    }

    let motion = MOTION_GAIN;
    let targetX = 0;
    let targetY = 0;
    let smoothX = 0;
    let smoothY = 0;
    /** A changed non-tilt input (a resize, Motion): draw once even if settled. */
    let dirty = true;

    let width = 1;
    let height = 1;
    let eps2d = 0;
    let pivot: Pivot = { x: 0, y: 0, z: refDepth, found: false };

    /** The framing-dependent values: the floor, the aspect and the pivot's depth. */
    function fit(): void {
      const rect = container.getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = Math.max(1, Math.round((rect.width || window.innerWidth) * dpr));
      height = Math.max(1, Math.round((rect.height || window.innerHeight) * dpr));
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      gl.viewport(0, 0, width, height);
      // The floor is a fraction of the ON-SCREEN cell, so it is re-derived on
      // every resize — which is the whole reason `Splat.eps2dFor` exists.
      eps2d = eps2dFor(EPS_2D_DEFAULT, width, height, packed.count, 1);
      // The pivot depends on the projection (i.e. the aspect) and NOT on the tilt,
      // so it is resolved per framing rather than per frame — a 150k-splat scan
      // that would otherwise run sixty times a second for one unchanging answer.
      const [tanX, tanY] = originRay(fovy, width / height, 0, 0);
      pivot = pivotAt(positions, packed.count, tanX, tanY, refDepth);
      // The eye sits relative to the pivot, so a new pivot is a moved eye — and the
      // sort gate watches the tilt, which has not changed. Force the next draw to
      // re-derive the order rather than serve the one from the old aspect.
      sortedX = Number.NaN;
      dirty = true;
    }

    let sortedX = Number.NaN;
    let sortedY = Number.NaN;

    function sortAndUpload(camera: CloudCamera): void {
      const order = sorter.sort(positions, packed.count, camera);
      orderSize.texels.set(order);
      gl.bindTexture(gl.TEXTURE_2D, orderTexture);
      gl.texSubImage2D(
        gl.TEXTURE_2D,
        0,
        0,
        0,
        orderSize.width,
        orderSize.height,
        gl.RED_INTEGER,
        gl.UNSIGNED_INT,
        orderSize.texels,
      );
      sortedX = smoothX;
      sortedY = smoothY;
    }

    function draw(): void {
      const camera = cloudCamera({
        surfaceWidth: width,
        surfaceHeight: height,
        fovyDeg: fovy,
        tiltX: smoothX,
        tiltY: smoothY,
        motion,
        positions,
        count: packed.count,
        refDepth,
        pivot,
      });

      // The sort gate: the smoothed tilt is the only input that moves the eye, so
      // a camera that has not moved past `SORT_EPS` has the order it had last
      // frame and re-deriving it is pure waste (`SplatRenderer.needsSort`).
      // Motion is deliberately NOT in the gate, exactly as the app's is not: it
      // scales an angle that has not moved, so a Motion change while the phone is
      // still moves nothing at all, and one during a tilt is re-sorted by the tilt
      // gate a frame later.
      if (
        Number.isNaN(sortedX) ||
        Math.abs(smoothX - sortedX) > SORT_EPS ||
        Math.abs(smoothY - sortedY) > SORT_EPS
      ) {
        sortAndUpload(camera);
      }

      gl.clearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      // The app's own blend. Alpha blending is not commutative, which is the
      // whole reason for the sort above.
      gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.useProgram(program);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, dataTexture);
      gl.uniform1i(uniform('uData'), 0);
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, orderTexture);
      gl.uniform1i(uniform('uOrder'), 1);
      gl.uniform1i(uniform('uDataW'), packed.width);
      gl.uniform1i(uniform('uOrderW'), ORDER_WIDTH);
      gl.uniform1f(uniform('uNear'), NEAR);
      // The focals in PIXELS, which is what the Jacobian is written in:
      // 0.5 * viewport * the projection's own factor.
      gl.uniform2f(uniform('uFocal'), 0.5 * width * camera.proj[0], 0.5 * height * camera.proj[5]);
      gl.uniform2f(uniform('uViewport'), width, height);
      gl.uniform1f(uniform('uEps2d'), eps2d);
      gl.uniform1f(uniform('uAlphaDiscard'), ALPHA_DISCARD);
      gl.uniform1f(
        uniform('uMinViewDistancePerSize'),
        minViewDistancePerSize(camera.proj[5], MAX_EXTENT_FRACTION),
      );
      gl.uniformMatrix4fv(uniform('uProj'), false, camera.proj);
      gl.uniformMatrix4fv(uniform('uView'), false, camera.view);
      gl.uniformMatrix3fv(uniform('uViewRot'), false, camera.viewRot);
      gl.bindVertexArray(vao);
      gl.drawArraysInstanced(gl.TRIANGLES, 0, 6, packed.count);
      gl.bindVertexArray(null);
      gl.disable(gl.BLEND);
      gl.activeTexture(gl.TEXTURE0);
      dirty = false;
    }

    let last = performance.now();

    function loop(now: number): void {
      frameId = requestAnimationFrame(loop);
      const dt = Math.min(0.1, Math.max(0, (now - last) / 1000));
      last = now;
      const moving = Math.abs(targetX - smoothX) > 1e-6 || Math.abs(targetY - smoothY) > 1e-6;
      const alpha = 1 - Math.exp(-dt / SMOOTHING_TAU);
      smoothX += (targetX - smoothX) * alpha;
      smoothY += (targetY - smoothY) * alpha;
      // A settled cloud draws nothing: the last frame already used this pose, and
      // the residual is under 1e-6 of tilt — far below the sub-pixel bound
      // `SORT_EPS` is set from. This is the web form of the app's request-site
      // doze: the picture must not cost a GPU frame sixty times a second while it
      // is still.
      if (!moving && !dirty) return;
      draw();
    }

    observer = new ResizeObserver(() => fit());
    observer.observe(container);
    fit();
    frameId = requestAnimationFrame(loop);

    return {
      setMotion(value: number): void {
        const next = Number.isFinite(value) ? Math.max(0, Math.min(1, value)) * MOTION_GAIN : MOTION_GAIN;
        if (next !== motion) {
          motion = next;
          dirty = true;
        }
      },
      setTilt(x: number, y: number): void {
        targetX = Number.isFinite(x) ? Math.max(-1, Math.min(1, x)) : 0;
        targetY = Number.isFinite(y) ? Math.max(-1, Math.min(1, y)) : 0;
      },
      resize(): void {
        fit();
      },
      destroy(): void {
        teardown();
      },
    };
  }
}

function nearest(gl: WebGL2RenderingContext): void {
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
}

/** Compile and link, throwing the driver's own words rather than drawing nothing. */
function build(gl: WebGL2RenderingContext, vert: string, frag: string): WebGLProgram {
  const compile = (type: number, source: string, name: string): WebGLShader => {
    const shader = gl.createShader(type);
    if (!shader) throw new Error(`the ${name} shader could not be created`);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error(`the ${name} shader did not compile: ${log || 'no log'}`);
    }
    return shader;
  };
  const vs = compile(gl.VERTEX_SHADER, vert, 'vertex');
  const fs = compile(gl.FRAGMENT_SHADER, frag, 'fragment');
  const program = gl.createProgram();
  if (!program) throw new Error('the cloud program could not be created');
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program);
    gl.deleteProgram(program);
    throw new Error(`the cloud program did not link: ${log || 'no log'}`);
  }
  return program;
}
