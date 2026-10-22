import * as THREE from 'three';

/**
 * Real-geometry relief renderer: the photo is displaced into a genuine 3D
 * surface (continuous, no tearing) with normals recomputed from the relief,
 * so directional light actually shades it — real geometry cues (silhouette,
 * parallax, shading) instead of a flat-card texture trick.
 */

const FOV = 45;
const CAM_DIST = 1.2;
const CROP_MARGIN = 1.08;
const CAM_RANGE_FRACTION = 0.15;
const MOTION_GAIN = 3;
const SEGMENTS = 256; // relief grid

export function createDepthScene(container) {
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  container.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x000000);

  const camera = new THREE.PerspectiveCamera(FOV, window.innerWidth / window.innerHeight, 0.1, 10);
  camera.position.set(0, 0, CAM_DIST);

  // ambient keeps the photo's natural colors; the directional adds real shading
  scene.add(new THREE.AmbientLight(0xffffff, Math.PI));
  const sun = new THREE.DirectionalLight(0xffffff, 0.5);
  sun.position.set(-0.4, 0.7, 1).normalize();
  scene.add(sun);

  const geometry = new THREE.PlaneGeometry(1, 1, SEGMENTS, SEGMENTS);
  const material = new THREE.MeshLambertMaterial({ color: 0xffffff });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.frustumCulled = false;
  scene.add(mesh);

  const loader = new THREE.TextureLoader();
  let planeAspect = 1;
  let intensity = 0.9;
  let motion = 1 / 3;
  let zoom = 1;

  const orbit = new THREE.Vector2(0, 0);
  const pan = new THREE.Vector2(0, 0);
  const _pivot = new THREE.Vector3();
  const _v3 = new THREE.Vector3();
  const _ndc = new THREE.Vector2();
  const _raycaster = new THREE.Raycaster();
  const _zPlane = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0);

  const pivotWorld = (out) => out.set(orbit.x * mesh.scale.x, orbit.y * mesh.scale.y, 0);

  function applyPan() {
    if (pan.x === 0 && pan.y === 0) {
      camera.clearViewOffset();
    } else {
      camera.setViewOffset(
        window.innerWidth,
        window.innerHeight,
        pan.x,
        pan.y,
        window.innerWidth,
        window.innerHeight
      );
    }
    camera.updateProjectionMatrix();
  }

  const loadTexture = (url, srgb) =>
    new Promise((resolve, reject) => {
      loader.load(
        url,
        (tex) => {
          tex.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
          resolve(tex);
        },
        undefined,
        reject
      );
    });

  function fitPlaneToViewport() {
    const viewAspect = window.innerWidth / window.innerHeight;
    const visH = 2 * CAM_DIST * Math.tan(THREE.MathUtils.degToRad(FOV / 2));
    const visW = visH * viewAspect;
    const scale = Math.max(visH, visW / planeAspect) * CROP_MARGIN * zoom;
    mesh.scale.set(scale * planeAspect, scale, 1);
    applyIntensity(); // z-scale depends on plane size
  }

  // Displacement is a fraction of the plane's height, so the perceived depth
  // stays constant across zoom / viewport aspect.
  function applyIntensity() {
    mesh.scale.z = intensity * mesh.scale.y;
  }

  function softenDepthImage(image, factor) {
    const w = image.width;
    const h = image.height;
    const sw = Math.max(1, Math.round(w / factor));
    const sh = Math.max(1, Math.round(h / factor));
    const small = document.createElement('canvas');
    small.width = sw;
    small.height = sh;
    small.getContext('2d').drawImage(image, 0, 0, sw, sh);
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(small, 0, 0, w, h);
    return canvas;
  }

  let depthSource = null;
  let edgeSoftness = 0;
  let depthPixels = null;
  let depthDims = { w: 0, h: 0 };
  const softnessFactor = () => 1 + edgeSoftness * 47;

  // The 8-bit depth map has quantization terraces (~1 level per few texels on
  // smooth surfaces); grazing directional light turns them into visible contour
  // lines. A separable box blur kept in FLOAT precision (never re-quantized to
  // 8-bit) smooths the slope so the relief normals vary continuously. Radius =
  // width/78 matches the Android port (verified: >= width/78 removes banding).
  const DEPTH_BLUR_FRACTION = 1 / 78;

  function blurDepthPixels(src, w, h) {
    const r = Math.max(1, Math.round(w * DEPTH_BLUR_FRACTION));
    const tmp = new Float32Array(w * h);
    for (let y = 0; y < h; y++) {
      let s = 0;
      for (let x = -r; x <= r; x++) {
        const xx = Math.max(0, Math.min(w - 1, x));
        s += src[y * w + xx];
      }
      for (let x = 0; x < w; x++) {
        tmp[y * w + x] = s;
        const add = Math.max(0, Math.min(w - 1, x + r + 1));
        const sub = Math.max(0, Math.min(w - 1, x - r));
        s += src[y * w + add] - src[y * w + sub];
      }
    }
    const out = new Float32Array(w * h);
    const cnt = 2 * r + 1;
    for (let x = 0; x < w; x++) {
      let s = 0;
      for (let y = -r; y <= r; y++) {
        const yy = Math.max(0, Math.min(h - 1, y));
        s += tmp[yy * w + x];
      }
      for (let y = 0; y < h; y++) {
        out[y * w + x] = s / (cnt * cnt);
        const add = Math.max(0, Math.min(h - 1, y + r + 1));
        const sub = Math.max(0, Math.min(h - 1, y - r));
        s += tmp[add * w + x] - tmp[sub * w + x];
      }
    }
    return out;
  }

  function buildReliefGeometry() {
    if (!depthPixels) return;
    const { w, h } = depthDims;
    const smooth = blurDepthPixels(depthPixels, w, h);
    const pos = geometry.attributes.position.array;
    for (let i = 0; i < pos.length; i += 3) {
      const u = pos[i] + 0.5; // plane x in [-0.5,0.5] -> uv.u
      const v = pos[i + 1] + 0.5; // plane y -> uv.v (1 = top)
      // bilinear sample of the float-blurred depth (row 0 = image top)
      const fx = u * (w - 1);
      const fy = (1 - v) * (h - 1);
      const x0 = Math.min(w - 1, Math.floor(fx));
      const y0 = Math.min(h - 1, Math.floor(fy));
      const x1 = Math.min(w - 1, x0 + 1);
      const y1 = Math.min(h - 1, y0 + 1);
      const tx = fx - x0;
      const ty = fy - y0;
      const v00 = smooth[y0 * w + x0], v10 = smooth[y0 * w + x1];
      const v01 = smooth[y1 * w + x0], v11 = smooth[y1 * w + x1];
      pos[i + 2] = ((v00 * (1 - tx) + v10 * tx) * (1 - ty)
          + (v01 * (1 - tx) + v11 * tx) * ty) / 255 - 0.5;
    }
    geometry.attributes.position.needsUpdate = true;
    geometry.computeVertexNormals(); // real lighting normals from the relief
    geometry.computeBoundingSphere();
  }

  function applyDepthCanvas(canvas) {
    const { width, height } = canvas;
    const rgba = canvas.getContext('2d').getImageData(0, 0, width, height).data;
    depthPixels = new Uint8Array(width * height);
    for (let i = 0; i < depthPixels.length; i++) depthPixels[i] = rgba[i * 4];
    depthDims = { w: width, h: height };
    buildReliefGeometry();
  }

  function setTextures(map, depth) {
    material.map = map;
    material.needsUpdate = true;
    planeAspect = map.image.width / map.image.height;
    fitPlaneToViewport();
  }

  return {
    async loadPair(imageUrl, depthUrl) {
      const [map, rawDepth] = await Promise.all([
        loadTexture(imageUrl, true),
        loadTexture(depthUrl, false),
      ]);
      depthSource = rawDepth.image;
      applyDepthCanvas(softenDepthImage(depthSource, softnessFactor()));
      rawDepth.dispose();
      setTextures(map, null);
    },

    async loadImage(imageUrl) {
      const map = await loadTexture(imageUrl, true);
      setTextures(map, null);
    },

    setIntensity(v) {
      intensity = v;
      applyIntensity();
    },

    setEdgeSoftness(v) {
      edgeSoftness = v;
      if (!depthSource) return;
      applyDepthCanvas(softenDepthImage(depthSource, softnessFactor()));
    },

    setMotion(v) {
      motion = v;
    },

    setZoom(v) {
      zoom = v;
      fitPlaneToViewport();
    },

    setOrbitFromScreen(clientX, clientY) {
      _ndc.set(
        (clientX / window.innerWidth) * 2 - 1,
        -(clientY / window.innerHeight) * 2 + 1
      );
      _raycaster.setFromCamera(_ndc, camera);
      const hit = _raycaster.ray.intersectPlane(_zPlane, _v3);
      if (!hit) return;
      orbit.x = THREE.MathUtils.clamp(_v3.x / mesh.scale.x, -0.5, 0.5);
      orbit.y = THREE.MathUtils.clamp(_v3.y / mesh.scale.y, -0.5, 0.5);
    },

    resetOrbit() {
      orbit.set(0, 0);
    },

    panBy(dx, dy) {
      const limit = Math.max(window.innerWidth, window.innerHeight);
      pan.x = THREE.MathUtils.clamp(pan.x - dx, -limit, limit);
      pan.y = THREE.MathUtils.clamp(pan.y - dy, -limit, limit);
      applyPan();
    },

    resetPan() {
      pan.set(0, 0);
      applyPan();
    },

    getOrbitScreen() {
      pivotWorld(_v3).project(camera);
      return {
        x: (_v3.x * 0.5 + 0.5) * window.innerWidth,
        y: (-_v3.y * 0.5 + 0.5) * window.innerHeight,
      };
    },

    render(tilt) {
      const range = motion * MOTION_GAIN * CAM_RANGE_FRACTION * mesh.scale.y;
      pivotWorld(_pivot);
      camera.position.set(
        _pivot.x + tilt.x * range,
        _pivot.y - tilt.y * range,
        CAM_DIST
      );
      camera.lookAt(_pivot);
      renderer.render(scene, camera);
    },

    onResize() {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(window.innerWidth, window.innerHeight);
      fitPlaneToViewport();
      applyPan();
    },
  };
}
