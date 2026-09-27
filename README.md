# Basrelief

Turn a photo + its depth map into a real 3D **relief** that tilts with your phone.
Two apps share one effect and one camera model:

- **Web demo** (`src/`, Vite + three.js) — run in any mobile browser.
- **Android live wallpaper** (`android/`, plain Java + OpenGL ES 2) — a gyro-driven
  live wallpaper plus a full editing "studio" screen. No AndroidX, no Maven
  dependencies.

## How it works

The photo is mapped onto a displaced triangle mesh: each grid vertex is pushed
along Z by the depth map (`z = depth/255 − 0.5`, scaled by an intensity
parameter), and **per-vertex normals are averaged from the surrounding faces** so a
directional light actually shades the relief. Tilt the device and the camera
swings around a selectable **orbit centre** — true perspective parallax, real
silhouette, and real shading. This is genuine geometry, not a parallax shader.

Because the depth map is 8-bit, its quantisation terraces are smoothed by a
**separable float box blur** (radius `width/78`) applied before tessellation —
without it, grazing light turns those terraces into visible contour lines.

## Web demo

```
npm install
npm run dev            # HTTPS on :5173 — required for gyro in browsers
npm run gen:assets     # regenerate the synthetic demo image+depth pair
```

Controls (touch-first): tap = set orbit centre, two-finger drag = pan,
two-finger pinch / slider = zoom, sliders = depth intensity / motion / edge
smoothing, single-finger drag = tilt fallback, fullscreen + hide-controls.

## Android app

### Build (Gradle in a container — no Android Studio, no SDK on the host)

```
cd android
./podman-build.sh build-image    # once: builds the SDK image
./podman-build.sh build-apk      # -> app/build/outputs/apk/debug/app-debug.apk
./podman-build.sh install        # build + install on the attached device over adb
```

The host needs only **podman** (and `adb` for `install`): `gradlew` and the
wrapper are committed and run inside the container (Ubuntu 22.04, JDK 17,
Android SDK 36). No Android Studio required.

Install the APK and set it via **Basrelief → "Set as wallpaper"** (or long-press
home → Wallpapers → Basrelief).

Notes:
- Plain Java, framework APIs only — no AndroidX/Maven dependencies.
- Regenerate the launcher icon: `node scripts/generate-icon.mjs`.

### Studio → wallpaper

The studio screen edits everything: image/depth pickers, depth intensity, motion,
zoom, orbit centre (tap), pan (two-finger), reset. All settings are
persisted to `SharedPreferences`; the live wallpaper **mirrors them** live. The
wallpaper itself is read-only — it only tilts with the gyro. The gyro pauses
(not visible, low battery unless charging, or after 5 s of idle) apply to the
**wallpaper**; the studio preview runs the gyro regardless of battery.

### Depth AI (generate depth from a LAN ComfyUI)

The studio's **Depth AI** button opens a page that talks directly to a ComfyUI
instance on your network (no PC browser needed):

- Enter your ComfyUI URL and **Test connection** — the app queries `/object_info`
  and lists the depth nodes the instance *actually* has (Depth Anything V1/V2,
  MiDaS, Zoe… anything installed, e.g. via `comfyui_controlnet_aux`).
- Pick an image, choose a model + resolution, **Generate** — live progress via a
  dependency-free WebSocket (`/ws`), result fetched via `/history` and `/view`.
- The app then packs the image + depth into one self-contained PNG (raw `dEPt`
  chunk, same as your `depthmap.html` tool) and loads it straight into the studio
  and wallpaper.

Implementation: `ComfyApi.java` (HTTP), `ComfyWs.java` (RFC6455), `DepthPngPacker.java`.

### Gyro

`TYPE_ROTATION_VECTOR`, calibrated to the neutral pose each time the wallpaper
becomes visible (screen on / back home), with a stale-reading skip + settle
average. Tilt is exponentially smoothed with a **time-based constant** (0.2 s) so
it feels identical on 60/90/120 Hz displays. Rotation maps roll→x, pitch→y.

**Idle sleep:** after ~5 s of stillness the rotation-vector listener is
unregistered and rendering stops (only the last frame stays). A low-power
accelerometer takes over, and a shake — or the wallpaper becoming visible
again — wakes it, re-calibrating to the current pose.

## Embedded depth PNG format

A color PNG may carry its depth map in a custom **ancillary chunk** — invisible to
normal viewers, so the file stays a plain viewable image. The parser (`png-depth.js`
web, `DepthPng.java` Android) accepts both chunk names:

| Name   | Meaning |
|--------|---------|
| `dePH` | spec-compliant (ancillary, private, reserved-bit uppercase, unsafe-to-copy) |
| `dEPt` | used by some tools (matched case-insensitively) |

Two payload forms are accepted:

1. **A complete PNG** — the chunk data is a whole grayscale depth PNG
   (self-describing; `photo-with-depth.png` in the repo is an example).
2. **Raw 8-bit gray** — exactly `width × height` bytes, row-major, dimensions from
   the file's IHDR (this is the format your ComfyUI/Depth Anything V2 export uses).

The generator (`scripts/generate-assets.mjs`) embeds a PNG payload as `dePH`.

## Project layout

```
src/                web app (Vite + three.js)
  scene.js          camera model, relief geometry, pan/zoom/orbit
  tilt.js           deviceorientation handling
  png-depth.js      embedded-depth chunk parser (dePH/dEPt)
  main.js           UI wiring
scripts/            web asset generator
android/
  podman-build.sh   canonical build (containerised Gradle)
  Dockerfile        Ubuntu 22.04 + JDK 17 + Android SDK 36
  app/src/main/java/com/amoshydra/basrelief/
    DepthWallpaperService.java  live wallpaper (getHolder()→wallpaper surface)
    ReliefRenderer.java         GLES2 renderer shell; delegates the maths to :core
    TiltController.java         rotation-vector tilt + calibration + idle sleep
    SensorPolicy.java           gyro pauses: not visible / battery ≤ 20%
    DepthPng.java               embedded-depth chunk parser
    DepthPngPacker.java         packs image+depth into a dEPt PNG
    ImageUtil.java              downscales huge images before use
    ComfyApi.java, ComfyWs.java ComfyUI REST + progress websocket
    DepthGenDialog.java         Depth AI page
    MainActivity.java           studio: pickers, sliders, gestures, share
  core/src/main/java/com/amoshydra/basrelief/core/render/   pure JVM, no Android imports
    ReliefMesh.java             depth → geometry + the float depth blur
    CameraModel.java            projection, z-clamp, tap → orbit
    Mat4.java                   vendored subset of android.opengl.Matrix
```

## Tests

The rendering core is a plain JVM module, so the maths that is easy to get
subtly wrong is covered by ordinary unit tests — no device, no emulator:

```
cd android
./podman-build.sh test        # or: just ./podman-build.sh build-apk
```

`ReliefMeshGoldenTest` pins the exact vertex output for a fixed depth map, which
is what keeps the depth-banding fix (contour lines caused by 8-bit depth
quantisation) from silently regressing. `CameraModelTest` checks that tapping to
re-centre the relief round-trips through the projection.

JUnit is a test-only dependency and is not packaged into the APK.

## License

GPL-3.0 — see [LICENSE](LICENSE).
