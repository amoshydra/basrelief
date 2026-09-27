# AGENTS.md — Depth Tilt

## Project overview

Gyro-driven "3D photo" (image + depth map → relief → tilts with device). Two apps
sharing the same effect and camera model:

- **Web demo** — Vite + three.js in `src/` (`index.html`, `vite.config.js`).
- **Android live wallpaper** — native Java + OpenGL ES 2 in `android/`, built with
  **Gradle inside a Podman container** (intentional: no AndroidX/Maven deps).
  Includes a "studio" preview/editor screen.

The Android port must match the web behavior 1:1 (`src/scene.js`, `src/tilt.js`,
`src/png-depth.js` are the reference).

## Environment

**Primary dev machine: Linux desktop.** The Android APK is
built with `./podman-build.sh` in `android/` — podman is the only requirement
(plus `adb` for on-device install). No Android SDK, Gradle, or Android Studio on
the host.

- Node 26, OpenJDK 17, git, podman installed.
- JDK 17 is used **inside the container**; the host JDK is not used for builds.

**Secondary: Termux on an Android phone** — holds the legacy no-Gradle `build.sh`
for fast on-device iteration, and is where the app is visually verified.
- `/tmp` is often not writable on Termux; use `$(mktemp -d)` or a private dir.
- `#!/usr/bin/env` shebangs fail (no `/usr/bin/env` on Android) → npm scripts in
  `package.json` invoke vite via `node node_modules/vite/bin/vite.js` directly.
- Shared storage is at `~/storage/shared/Download/` (copy APKs there).

## Commands

### Web
```
npm install
npm run dev       # HTTPS on :5173 (basic-ssl); phone needs to accept the cert
npm run build
npm run gen:assets   # regenerate public/assets demo image+depth pair
```

### Android (canonical: Gradle in Podman — needs only podman on the host)
```
cd android
./podman-build.sh build-image    # once: builds the SDK image (Ubuntu 22.04 + JDK 17 + SDK 34)
./podman-build.sh build-apk      # -> app/build/outputs/apk/debug/app-debug.apk
./podman-build.sh install        # build + adb install on the connected device
./podman-build.sh build-release  # signed only if keystore.properties exists
./podman-build.sh clean | shell | help
```
No Android SDK, Gradle, or Android Studio is needed on the host — `gradlew` +
`gradle-wrapper.jar` are committed and run inside the container. The host only
needs `podman` (and `adb` for `install`).

### Android (legacy: no-Gradle, Termux on the phone only)
```
cd android
bash setup.sh        # one-time: install aapt2/apksigner/dx/zip, fetch android.jar
bash build.sh        # aapt2 → javac → dx → zip → apksigner → build/apk/app.apk
node scripts/generate-icon.mjs
```
Kept for fast on-device iteration. Both builds compile the same sources under
`app/src/main/`, so keep them in sync. The Termux path **cannot** run on the
desktop (needs Termux `aapt2`, which is aarch64-only).

Install: copy the APK to shared storage and install it from a file manager
(launching the installer programmatically is unreliable on some devices).

Release signing is opt-in: drop a gitignored `keystore.properties` next to
`app/build.gradle` and `podman-build.sh build-release` will sign; without it the
release APK is unsigned.

## Android build pipeline

**Canonical = Gradle.** Standard Android Gradle Project rooted at `android/`
(`app/src/main/{java,res}`), AGP 8.1.4, Gradle 8.2 wrapper, compiled in the
Podman image. This is what CI / F-Droid / Play will use.

**Zero dependencies on purpose.** The app uses only the Android framework
(`android.app.Activity`, `GLSurfaceView`, `WallpaperService`) and deliberately has
**no AndroidX / Maven artifacts** — `dependencies {}` in `app/build.gradle` is
empty. Keep it that way unless there's a strong reason.

Constraints (learned the hard way):
- **The manifest has NO `package=` attribute** (AGP 8 rejects it). The package
  lives in `app/build.gradle` → `namespace` / `applicationId`. The legacy
  `build.sh` passes the same name to aapt2 via its custom-package flag; keep all
  three in sync when renaming.
- **`--` is illegal inside an XML comment** — don't write `--flag` in manifest
  comments; it fails the build with a confusing parse error.
- **Java 8 source/target** (`VERSION_1_8`) — matches the existing sources.
- Termux legacy path only: `android.jar` = API 33 at `$HOME/android-sdk/android.jar`;
  use Termux's `aapt2` (aarch64), Google's is x86_64 and won't run on-device.

## Android coding gotchas (learned the hard way)

- **GLES2 has no `glPointSize`** — set `gl_PointSize` in the vertex shader instead.
- **`GLUtils.texImage2D` puts bitmap row 0 at texture v=0 (bottom).** Flip the color
  bitmap vertically (`Bitmap.createBitmap(src, 0,0,w,h, scale(1,-1) matrix)`) so
  image-top = v=1, matching the top-first depth sampling.
- **Matrix order:** rendering is `clip = proj * view * model`. Any unprojection
  (e.g. `screenToOrbit`) must invert `proj * view` (the SAME order) — inverting
  `view * proj` is a real bug.
- **Threading:** renderer matrices/buffers are GL-thread-only; UI-thread setters may
  only write `volatile` fields. Anything touching GL state (including
  `screenToOrbit`, `loadPair`) must run on the GL thread via `glView.queueEvent`.
- **Depth test must be enabled and the depth buffer cleared**, or torn relief pieces
  draw in submission order and far cells paint over near ones.
- **Gyro:** `TYPE_ROTATION_VECTOR`; calibrate on every visibility (skip cached
  reading, average ~8 samples); time-based exponential smoothing (`tau=0.2s`) so the
  feel is identical on 60/90/120 Hz; idle-sleep after 5 s still (accel shake-wake).
- **Embedded depth PNG:** custom ancillary chunk `dePH`/`dEPt` (case-insensitive);
  payload is either a whole PNG or raw `w*h` 8-bit gray (dimensions from IHDR).
  Implemented in `src/png-depth.js` (web) and `DepthPng.java` (Android).
- **8-bit depth banding — the fix, so it is never undone.** The depth map has only
  256 levels, so smooth surfaces have ~1-level terraces (measured: runs of ~8 px at
  a constant value). A grazing directional light amplifies those terraces into
  visible contour lines, worst on the shadowed side of a face. The fix is a
  **separable box blur kept in FLOAT precision** (radius `DEPTH_BLUR_FRACTION = 1/78`
  of the depth width) applied to the depth *before* tessellation. The load-bearing
  detail: **the blur output must stay `float`/`Float32Array` — re-quantizing it back
  to `Uint8` recreates the terraces and the artifact returns.** This was verified by
  A/B on-device; raising the grid, changing light direction, half-Lambert, framebuffer
  dithering, and nearest/bilinear/cubic sampling all made no difference, which is what
  ruled those out. Keep `DEPTH_BLUR_FRACTION` identical in `src/scene.js` and
  `android/core/.../ReliefMesh.java` — the Android side is now pinned by
  `ReliefMeshGoldenTest`, but nothing still enforces the web/Android match.
- **The two ports have already drifted** (verified, not hypothetical): default
  depth intensity is 45 on web vs 90 on Android; the web tilt range scales with
  zoom while Android's is constant; web has no z-scale clamp but Android silently
  clamps `intensity` to `0.9*CAM_DIST/scale`, so the Depth slider stops working at
  high zoom without saying so. Treat "matches the web" as an explicit list of
  invariants to check, not a general claim.
- **The bundled demo depth map is `res/raw/depth.png` and must be loaded as a
  pair with `res/raw/image.png`.** It shipped unreferenced from the first commit,
  so the out-of-the-box demo fell through to a flat depth map and rendered with
  no relief at all. The status line now distinguishes the two
  ("bundled demo + depth" vs "bundled demo, FLAT") because when they both said
  "bundled demo" the failure was invisible.
- **The build container bakes in its own debug keystore** (`android/Dockerfile`).
  Without it AGP mints a fresh key in ephemeral storage on every build, every
  APK gets a different signature, `adb install -r` always fails with
  INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only recovery is an uninstall —
  which silently wipes the user's studio settings each time. If you ever see
  that error, this is why.
- **The wallpaper DOES load itself on a cold start, via
  `ReliefRenderer.onSurfaceCreated` -> `reloadFromPrefs()`.** Do not "discover" a
  missing initial load here: it was checked, and the pref-change listener is
  only a *live update* path on top of it. An attempt to add a second
  one-shot load in `Engine.onSurfaceChanged` was written, reviewed and reverted
  as redundant — it only cost a duplicate 393k-vertex mesh build. A cold start
  was verified rendering correctly (app force-stopped, studio never opened,
  reached via Settings -> Wallpaper -> Live wallpapers -> Preview).
- **This ROM's wallpaper picker does not apply the selection.** Tapping "Set
  wallpaper" reports success and no app-side error is logged, but
  `dumpsys wallpaper` still shows `com.android.systemui...ImageWallpaper`. That
  is a device/ROM quirk, not an app bug — do not chase it as one.
- **SharedPreferences key `depthwall` is the single source of truth** between the
  studio and the wallpaper (image/depth paths, intensity, motion, zoom, orbit,
  pan). The wallpaper is read-only; the studio edits. (This is the *transport*
  between the two processes, not a data store — it holds exactly one item's
  config and has no concept of a library of items. A gallery will need a real
  data layer; see the roadmap.)

## Key files

```
src/scene.js            web camera model, relief geometry, pan/zoom/orbit
src/tilt.js             web deviceorientation handling
src/png-depth.js        web embedded-depth chunk parser
src/main.js             web UI wiring

android/podman-build.sh   CANONICAL build: build-image/build-apk/install/release/clean
android/Dockerfile        Ubuntu 22.04 + JDK 17 + Android SDK 34 + build-tools 34.0.0
android/app/build.gradle  AGP config (namespace, sdk levels, empty dependencies)
android/build.sh          LEGACY no-Gradle build (Termux on the phone only)

android/app/src/main/java/com/amoshydra/basrelief/     Android shell (GLES2 + UI)
  DepthWallpaperService.java  live wallpaper (getHolder() → wallpaper surface)
  ReliefRenderer.java         GL state, textures, orbit marker; delegates math to :core
  TiltController.java         tilt + calibration + idle sleep + shake wake
  SensorPolicy.java           gyro pauses (not visible / battery ≤ 20%)
  DepthPng.java               embedded-depth chunk parser
  DepthPngPacker.java         packs image+depth into a dEPt PNG
  ImageUtil.java              downscales huge images (>2560px) before use
  ComfyApi.java / ComfyWs.java   Depth AI: ComfyUI REST + progress websocket
  DepthGenDialog.java         Depth AI page (host, model, generate, export)
  MainActivity.java           studio: pickers, sliders, gestures, share, immersive

android/core/src/main/java/com/amoshydra/basrelief/core/render/   pure JVM, NO android.*
  ReliefMesh.java             depth → geometry + the float depth blur (golden-tested)
  CameraModel.java            projection/view/z-clamp/tap-unprojection (round-trip tested)
  Mat4.java                   vendored subset of android.opengl.Matrix
```

`SettingsActivity.java` and `ReliefSurface.java` were removed — settings live
inside the studio, and relief geometry is now `:core`'s `ReliefMesh.java`.

### `:core` — the pure-JVM rendering core (why it exists)

`android/core` is a `java-library` with **no Android imports at all**, holding
the two pieces of maths that are easy to get subtly wrong: depth→geometry and
the camera. The renderer shell depends on it; nothing depends back. This exists
so those invariants are enforced by a build rather than by prose.

- **JUnit is `testImplementation` only** — it is never packaged into the APK,
  so the module keeps zero *runtime* dependencies even though the app shell now
  allows some.
- **`ReliefMeshGoldenTest` pins exact positions+normals** for a fixed synthetic
  depth map, captured from the renderer *as it shipped*. The banding fix is
  invisible in the code and a careless edit (re-quantising the blur to 8-bit,
  flipping the v convention, dropping a face normal) brings back visible
  contour lines with no failing build. Verified the test has teeth by mutation:
  all four of those regressions fail it.
- **`CameraModelTest` asserts the tap→unproject→project round-trip.** Re-project
  with the ORIGINAL orbit pivot, not the newly resolved one — adopting the new
  orbit moves the camera, so asserting a round-trip through it would be
  asserting a bug.
- Every `assemble`/`bundle` task `dependsOn ':core:test'`, so there is no way to
  produce an APK without the tests having passed. `podman-build.sh test` runs
  them alone; they are fast and need no device.
- `Mat4` is a vendored subset of `android.opengl.Matrix` (Apache-2.0), present
  only so the camera is testable off-device. Keep the operations in the same
  order as the Android originals or the goldens/round-trips will drift.

## Verification

- Web: `npm run build`; math checks run in Node with three.js replicating the GL
  matrix math (e.g. orbit tap→unproject→project round-trips must be ~0 px error).
- Android core: `podman-build.sh test` — pure JVM, no device, runs as part of
  `build-apk`. The mesh golden is the regression net for the banding fix; if it
  fails, do NOT regenerate the goldens to make it green unless the geometry
  change was deliberate and verified on-device.
- Android: `android/podman-build.sh build-apk` must succeed in-container, and
  `podman-build.sh install` should install + launch without crashing (verify with
  `adb shell pidof com.amoshydra.basrelief`). **Device-only checks** (can't verify
  off the phone): gyro sign conventions and feel, texture orientation, wallpaper
  rendering/performance, OEM immersive behavior. Ask the user to confirm these.
