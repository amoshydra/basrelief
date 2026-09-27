# AGENTS.md — Basrelief

Gyro-driven "3D photo": an image plus a depth map become a displaced relief
surface that moves as you tilt the device. Ships as an Android live wallpaper
with a built-in studio screen.

**Android is the product.** `src/` is an older web port kept as a reference for
the original camera model. It is NOT being kept in sync, so do not treat the two
as expected to match — see *Known drift*. Its demo assets in `public/assets/`
are the **old** demo and are regenerated only by `npm run gen:assets`; replacing
`android/app/src/main/res/raw/` does not touch them, so the two demos now differ.
All three files there are referenced by `src/main.js` (`image.png`, `depth.png`,
and `photo-with-depth.png`, which carries its depth in a `dePH` chunk) — replace
all three or the web port breaks.

## Build and test

Android is built with Gradle **inside a Podman container**. The host needs only
`podman` (plus `adb` to install) — no Android SDK, no Gradle, no Android Studio.

```
cd android
./podman-build.sh build-image    # once: builds the SDK image (Ubuntu 22.04 + JDK 17 + SDK 34)
./podman-build.sh test           # JVM unit tests in :core — no device needed
./podman-build.sh build-apk      # also runs :core:test first; cannot skip it
./podman-build.sh install        # build + adb install on the connected device
./podman-build.sh build-release  # signed only if keystore.properties exists
./podman-build.sh clean | shell | help
```

The Gradle cache is bind-mounted out of the container (`GRADLE_USER_HOME`), so
dependencies and the Gradle distribution are downloaded once instead of on
every build — locally and in CI. Cold build 14 s, warm 4 s with no network.

It is set via the **environment variable**, not `--gradle-user-home`: the
wrapper script decides where to keep the distribution and downloads it *before*
Gradle starts, so the flag alone leaves `gradle-8.2-bin.zip` re-fetched on every
run while everything else stays cached. That is a mistake which looks like it
worked, because the build still succeeds. Override the location with
`GRADLE_CACHE_DIR`, or opt out entirely with `GRADLE_NO_CACHE=1`.

CI (`.github/workflows/build.yml`) runs `build-image`, `test`, `build-apk` on
every push, plus a `vite` build for the web. It invokes the script from the repo
root, so **`podman-build.sh` must stay runnable from any working directory**.

Web (reference port, not maintained): `npm install && npm run build`.

Two more, only on a phone with Termux:

```
bash android/setup.sh     # one-time: aapt2/apksigner/dx/zip, fetch android.jar
bash android/build.sh     # legacy no-Gradle build, for fast on-device iteration
```

It compiles the same sources (`core/` + `app/src/main/`) in one `javac`, so
keep the two build paths in step. It **cannot** run on the desktop: it needs
Termux's aarch64 `aapt2`.

## Architecture

```
android/app  ──depends on──▶  android/core
  Android shell                pure JVM, zero android.* imports
```

`:core` exists so the maths that is easy to get quietly wrong is testable
without a device. It holds depth→geometry (`ReliefMesh`), the camera
(`CameraModel`), a vendored `Mat4`, and the prefs path scheme (`PrefFiles`).
JUnit is `testImplementation` only, so `:core` keeps **zero runtime
dependencies** and nothing is added to the APK. The app shell's only dependency
is `project(':core')` — no AndroidX, no Maven artifacts.

**Two surfaces, one process.** The studio (`MainActivity`) and the live
wallpaper (`DepthWallpaperService`) each own a `GLSurfaceView` and a
`ReliefRenderer`. SharedPreferences `depthwall` is the *transport* between them:
the studio writes, the wallpaper reads and re-renders. It is not a data store —
it holds exactly one item's config and has no concept of a library. A gallery
will need a real data layer (Room, relative paths, a versioned config blob).

Because they share a process, one shares the other's `SharedPreferences`
instance — which is why "does it work cold?" can only be answered by
force-stopping the app and reaching the wallpaper *without* opening the studio.

### Threading

| thread | owns |
|---|---|
| UI | widgets, prefs writes, `runOnUiThread` callbacks |
| `Io` executor | file reads, PNG encode, downscale — never the UI thread |
| sensor callback | `TiltController` → writes `volatile` tilt, calls `requestRender()` |
| GL | `onDrawFrame`, mesh build, `CameraModel`, all GL state |

**The rule:** UI-thread setters (`setDepth`, `setZoom`, `setMotion`, `setTilt`)
may only write `volatile` fields. Anything touching GL state — including
`loadPair` and `screenToOrbit` — must be posted with `glView.queueEvent`.

## Invariants

These are invisible from the code and easy to undo by accident. Each one has
already cost real time.

- **The bundled demo is a pair, and an unreferenced asset fails silently.**
  `android/app/src/main/res/raw/image.png` and
  `android/app/src/main/res/raw/depth.png` are loaded together; ship one
  without wiring it and the loader falls back to a flat map, which renders
  correctly and has *no relief at all*. The status line is the only tell, which
  is why it distinguishes "bundled demo + depth" from "bundled demo, FLAT". If
  you add or replace an asset, confirm something actually reads it — this has
  now bitten twice, and an asset with zero references is indistinguishable from
  a working one in a build log.
- **The depth blur must stay in `float`.** 8-bit depth has ~1-level terraces;
  grazing light amplifies them into visible contour lines. The fix is a
  separable box blur in float precision before tessellation — **re-quantising the
  result to 8-bit recreates the terraces and the artifact returns.**
  `DEPTH_BLUR_FRACTION = 1/78` is a fraction of the **grid** width, because
  `ReliefMesh` resamples to the grid before blurring. Change the radius and you
  must regenerate the goldens (`RegenerateGoldens`) and re-check banding on a
  real photo; the bundled demo map is only 4-level quantised and hides artifacts.
- **Matrix order.** `clip = proj * view * model`. Any unprojection must invert
  `proj * view` in that same order; `view * proj` is a real bug.
- **Never hand-derive vendored matrix semantics and verify them against a
  reference you wrote yourself.** `Mat4.scaleM` shipped computing `S·m` instead of
  `m·S` because my hand-written AOSP reference was "corrected" to match my own
  wrong code. `Mat4ScaleTest`'s expected values were captured by running the
  **real** `android.opengl.Matrix.scaleM` on a device.
- **Frame pacing: do not add a frame-rate gate.** The surface is
  `RENDERMODE_WHEN_DIRTY` and only the gyro requests a frame (~50 Hz), so a cap
  saves nothing — and returning early from `onDrawFrame` does not cancel the
  present, so `eglSwapBuffers` re-presents the previous buffer mid-scanout and
  **tears the screen**. The draw rate is already bounded by the sensor. Pace at
  the request site if you ever need to.
- **`GLUtils.texImage2D` puts bitmap row 0 at `v=0` (bottom).** The colour
  bitmap is flipped vertically so image-top = `v=1`, matching top-first depth
  sampling. Same for the depth.
- **The depth test must be enabled and the depth buffer cleared**, or torn relief
  pieces paint in submission order and far cells cover near ones.
- **GLES2 has no `glPointSize`** — set it in the vertex shader.
- **Embedded depth** lives in a custom PNG ancillary chunk, keys `dEPt`/`dePH`,
  case-insensitive. The payload is either a whole PNG or raw `w*h` 8-bit grey,
  with dimensions from IHDR. `DepthPng.java` / `src/png-depth.js`.
- **Store image/depth as filenames, not absolute paths.** With
  `allowBackup="true"` an absolute path survives a restore but points at the old
  device's `filesDir`, and the user silently loses their image. `PrefFiles`
  handles both forms; see its test for the restore case.
- **Gyro:** `TYPE_ROTATION_VECTOR`; re-calibrate on every visibility change
  (average ~8 samples, never a cached reading); time-based exponential smoothing
  (`tau = 0.2s`) so the feel is identical at 60/90/120 Hz. After 5 s of stillness
  it unregisters the rotation-vector sensor and falls back to a low-power
  accelerometer until a shake — **that idle-sleep is the app's real battery win**,
  not anything in the draw path.

### Build constraints

- The manifest has **no `package=` attribute** (AGP 8 rejects it). The package
  lives in `app/build.gradle` as `namespace` / `applicationId`, and the legacy
  `build.sh` passes the same name to `aapt2` via its custom-package flag. Keep
  all three in sync when renaming.
- `--` is illegal inside an XML comment. Writing `--flag` there fails the build
  with a confusing parse error.
- Java 8 source/target (`VERSION_1_8`).
- The container bakes its own debug keystore. Without it AGP mints a fresh key
  in ephemeral storage every build, every APK gets a different signature, and
  `adb install -r` always fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
  whose only recovery is an uninstall, which wipes the user's settings.
- Termux only: `android.jar` is API 33 at `$HOME/android-sdk/android.jar`.

### Termux environment quirks

`/tmp` is often not writable — use `$(mktemp -d)`. `#!/usr/bin/env` shebangs
fail (no `/usr/bin/env` on Android), which is why the npm scripts invoke vite via
`node node_modules/vite/bin/vite.js`. Shared storage is
`~/storage/shared/Download/`; copy APKs there and install from the file manager,
since launching the installer programmatically is unreliable on some devices.

## Verification

`./podman-build.sh test` is the gate — pure JVM, ~15 s, no device, and every
`assemble`/`bundle` depends on it, so **there is no path to an APK that skips
it**. 48 tests across six classes:

- `ReliefMeshGoldenTest` — exact vertex positions and normals for a fixed depth
  map. Mutation-checked: re-quantising the blur, halving its radius, flipping
  the v convention and dropping a face normal each fail it.
- `Mat4ScaleTest` — the `scaleM` semantics, captured from the real Android API.
- `CameraModelTest` — tap → unproject → project round-trip, plus "every Depth
  step is live at every zoom" and "Depth 0% is flat at every zoom".
- `PrefFilesTest` — the restore case, which silently costs users their image.
- `ConfigTest` — the settings blob: round-trip, clamping, and that `parse`
  never throws, because the wallpaper parses it on the GL thread at cold start.
  It also pins that pan is in **pixels** and must not be clamped to orbit's
  fraction range.
- `ComfyWorkflowTest` — the ComfyUI graph wiring, especially that the depth
  node reads node 1 and `SaveImage` reads node **2**, not 1. An off-by-one
  there renders the wrong tensor with no error.

The counts above are enforced by `scripts/check-parity.mjs`, so adding a test
class without updating this list fails the build instead of quietly making the
file wrong.

If `meshMatchesGoldenOutput` goes red, **read the diff and decide whether the
geometry change was intended** before regenerating. `RegenerateGoldens` is a
`main`, not a test, because a test that rewrites its own expectation can never
fail.

**Device-only checks — these cannot be verified off the phone, so ask:**
gyro sign conventions and *feel*, texture orientation, live-wallpaper
rendering and performance, and OEM immersive behaviour.

Gotcha for adb: **if more than one device is attached, pass `adb -s <serial>`.**
A second phone appearing over WiFi has silently redirected installs.

## Known drift (not bugs to fix blindly)

- Default Depth is 90 on Android vs 45 on the web port; the web tilt range
  scales with zoom, Android's does not. The ports are not expected to match —
  pick the behaviour you want deliberately rather than "fixing" one toward the
  other.
- Depth is a fraction of the reachable maximum, so absolute depth varies with
  zoom. That is intentional.
- The `intensity` pref is 0..100 and is clamped on read, not migrated, so
  values written by a 0..200 build are reinterpreted.
- The texture is uploaded without mipmaps, so heavy minification aliases. This
  matters for any future render-resolution scaling.

## Where the history lives

**This file deliberately does not record fixed bugs.** "Why is this line like
this?" is answered by the code, its comment, and `git log` — commit messages
here carry the diagnosis, the rejected hypotheses and the measurements. If a
rule here ever contradicts the code, the code and the log win; fix this file in
the same commit as whatever revealed the drift.
