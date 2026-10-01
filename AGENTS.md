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
./podman-build.sh build-image    # once: builds the SDK image (Ubuntu 22.04 + JDK 17 + SDK 36)
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
Gradle starts, so the flag alone leaves `gradle-<version>-bin.zip` re-fetched on
every run while everything else stays cached. That is a mistake which looks like it
worked, because the build still succeeds. Override the location with
`GRADLE_CACHE_DIR`, or opt out entirely with `GRADLE_NO_CACHE=1`.

CI (`.github/workflows/build.yml`) runs `build-image`, `test`, `build-apk` on
every push, plus a `vite` build for the web. It invokes the script from the repo
root, so **`podman-build.sh` must stay runnable from any working directory**.

Web (reference port, not maintained): `npm install && npm run build`.

## Architecture

```
android/app  ──depends on──▶  android/core
  Android shell                pure JVM, zero android.* imports
```

`:core` exists so the maths that is easy to get quietly wrong is testable
without a device. It holds depth→geometry (`ReliefMesh`), the camera
(`CameraModel`), the picture adjustments (`Picture`), a vendored `Mat4`, the prefs
path scheme (`PrefFiles`), the whole persisted schema (`Config`, `Library`,
`LibraryItem`, `Surface`, `SurfaceSettings`, `Playlists`, `Playlist`, `Trigger`),
and the one resolution of state into numbers (`Framing`, `SurfaceResolver`).

Anything that would otherwise be untestable GL or platform code belongs here as a
pure function of plain values — that is the whole test. `Picture` is the pattern:
the shader keeps one multiply and one mix, and the mapping it cannot be checked for
lives in `:core` with its own tests. `SurfaceResolver` is the same, answering
which surface an engine is drawing from two ints.
JUnit is `testImplementation` only, so `:core` keeps **zero runtime
dependencies** and nothing is added to the APK. The app shell's only dependency
is `project(':core')` — no AndroidX, no Maven artifacts.

**Two surfaces, one process.** The studio (`MainActivity`) and the live
wallpaper (`DepthWallpaperService`) each own a `GLSurfaceView` and a
`ReliefRenderer`. SharedPreferences `depthwall` is the *transport* between them:
the studio writes, the wallpaper reads and re-renders.

There is now a gallery, so that file holds two versioned JSON blobs: `config`
and `library` (the index). **The index owns the selection and only the index** —
the pointer exists in exactly one place, because two copies can disagree and a
disagreeing pointer is a wallpaper showing a photo the user deleted. **Everything
about a photograph is on its `LibraryItem`, including Motion** — it was global
until `Library` v3, and the config blob survives only as the legacy value the
migration reads so an upgraded user's photos keep the speed they had.
`AppState` is the single read path, and `Framing` the single resolution of that
state into numbers, because the two surfaces computing it themselves is how they
came to disagree about the bundled item.

**A render value lives on exactly one surface, and a surface's values are a set.**
`LibraryItem`'s six fields are private and are the *home* surface's storage;
`settings(Surface)` is the only read and `withSettings`/`editSettings` the only
write, so a `lock` override cannot be half-updated and a home edit cannot forget a
second surface exists. Editing one value through a constructor that defaults
everything it is not given resets everything it is not given — which shipped once,
when `withDepth` silently reset Motion, brightness and shadow because those values
had been added after it was written. `LibraryItem.copy` exists for the same
reason: identity-only edits have one path that cannot name a render value.

Because they share a process, one shares the other's `SharedPreferences`
instance — which is why "does it work cold?" can only be answered without opening
the studio first.

**Do not use `am force-stop` for that test.** It puts the package in the stopped
state, and a stopped package's wallpaper component is never instantiated, so the
engine does not come back. SurfaceFlinger keeps serving the last frame it was
given, so the home screen shows a *plausible, correct-looking, stale* wallpaper
and nothing re-renders. Measured: after a force-stop the process was gone, the
engine wrote no prefs at all, and two consecutive frames were byte-identical —
which reads exactly like "the renderer resolved the wrong item" and cost a
detailed false-positive hunt. A reboot is the honest cold test; a live process
plus a selection change is the honest update test.

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

- **The bundled demo is a row in the gallery, and it is the one item with no
  file.** `Library.BUNDLED_ID` is a reserved id; a null image name is the signal
  the renderer already used to read
  `android/app/src/main/res/raw/image.png`. Because it cannot be recreated from
  `filesDir`, three things refuse to drop it — `remove`,
  `retainExisting`, and `fromJson` — and all three are in the data layer rather
  than the UI, so no caller can route around them. The third was a real bug the
  tests found: `fromJson` dropped any item with no image name, which is correct
  for a user item and fatal for this one.
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
- **The system replaces the live wallpaper surface with a frozen snapshot, and
  it is not a timing quirk.** `WallpaperService.Engine.freeze()` (AOSP
  `frameworks/base`, `core/java/android/service/wallpaper/WallpaperService.java`)
  captures the current surface into a `HardwareBuffer`, parents it to the engine
  at `setLayer(Integer.MAX_VALUE)`, **and then hides the engine's live BLAST
  surface**. What stays on the home screen is that frozen buffer, by design,
  not a frame the engine is still painting. `Engine.isFrozen` is simply
  `mScreenshotSurfaceControl != null`, so a frozen engine reports a state that
  is not the truth. This is why the first load per engine must be synchronous —
  `ReliefRenderer.setSyncFirstLoad(true)`, which is **our own method, not a
  platform API**, despite reading like one — or the snapshot is captured empty
  and nothing is alive to repaint it. The platform hook for this is
  `Engine.shouldWaitForEngineShown()`, which returns `false` by default; it is
  `@hide`, so it is not in `android.jar` and cannot be called without
  reflection, which is why we do the work ourselves.
  The engine is also short-lived, which is why nothing may be cached across its
  lifetime: a wallpaper showing the wrong photo is usually a *live* engine reading stale
  bookkeeping, or a snapshot outliving the engine that drew it, and those two
  look identical from the home screen. `DepthWallpaperService` logs one line
  per resolution under the `Basrelief` tag with the visible flag, whether the
  surface is still valid, the item it resolved, `which=<HOME|LOCK>`, and what the renderer believes
  is on screen. Grep that before theorising — three wrong theories cost a
  whole session once already, and the fix was in a commit message rather than
  in this file, which is the same as not writing it down. Note the corollary:
  **an engine that is correct can still be invisible**, because the home screen
  may be showing the system's snapshot rather than the engine. That is what made
  a gallery selection appear to do nothing until Set as wallpaper was pressed
  again. It is no longer the mechanism, because it never could be — see below.
- **A view on top of a `GLSurfaceView` must not be positioned with a
  post-layout transform. This is a documented platform limitation, b/7179570,
  and it is the whole explanation for the invisible-sheet bug.** `SurfaceView`'s
  own class javadoc says it: *"The transparent region that makes the surface
  visible is based on the **layout positions** in the view hierarchy. If the
  **post-layout transform** properties are used to draw a **sibling view** on top
  of the SurfaceView, the view may not be properly composited with the surface."*
  AOSP recorded the limitation in 2012 (commit `c9f345ff7bc5`) rather than
  fixing it, and it is still in the API 27 SDK sources, so it has never been
  fixed. The mechanism is visible in `SurfaceView.setVisibility`, which
  deliberately calls `requestLayout()` with the comment *"the transparent region
  is computed as part of the layout phase, and it changes (obviously) when the
  visibility changes."* `ViewRootImpl.performTraversals` recomputes the region
  and, on a change, sets `mFullRedrawNeeded = true`. `setTranslationX/Y` runs
  no layout pass at all. So: **visibility and layout changes reliably repaint
  the overlay; transforms do not.**
  Measured on the Pixel 7 Pro, the failure looks like a view that is placed
  correctly, opaque, attached, `isShown()`, in a window that has drawn 904
  frames — receiving every tap and absent from the display. Input keeps working
  because touches are dispatched from the view hierarchy, not from the window
  buffer. The sheet therefore positions itself by **layout height** and the
  `＋` expand is instant; see `applySheetHeight`. If you ever want to animate
  the sheet, do it with `LayoutParams` or margins, or call `requestLayout()`
  on the `GLSurfaceView` after the change — do not reach for `translationY`.
  A structural alternative is `setZOrderOnTop(true)` on the `GLSurfaceView`,
  which sets `subLayer = +1`, makes `isAboveParent()` true and therefore skips
  `clearSurfaceViewPort()` and the transparent region entirely — nothing can
  desync because no hole is punched. The cost is that nothing in the window
  composites over the GL content.
- **No third-party app can re-bind its own live wallpaper. Stop trying.**
  Measured, not assumed: `setWallpaperComponent` is refused by
  `WallpaperManagerService` with `SecurityException: Access denied ... must have
  permission android.permission.SET_WALLPAPER_COMPONENT`, and that permission is
  `signature|privileged`, so declaring it changes nothing — `dumpsys package`
  shows it requested and never granted. `WallpaperManager` has no public
  equivalent (`setBitmap`, `setResource` and `setStream` all replace a live
  wallpaper with a static image), so the system picker is the only route, which
  is what makes "Set as wallpaper" the apply gesture rather than a convenience.
  There was a `WallpaperRefresh` class that tried it by reflection, down to a
  second route through the binder; both were refused, always, so it has been
  deleted rather than left as a reflection path that can only fail and a status
  line telling users `rebind: REFUSED` as though their device were at fault.
  Its one surviving fact — the component users select — now lives on
  `DepthWallpaperService` as `wallpaperComponent`, beside the service it names.
  **The engine follows the index by itself**; see the "state that records this
  is on screen" invariant for how that was made true rather than papered over.
- **`am force-stop` on a package that *is* the active wallpaper makes Android
  revert the wallpaper to the default static image.** This is a sharper form of
  the trap above, and it cost a long session: every screenshot afterwards is a
  static image (usually black), the engine never runs at all, and the app looks
  broken when it is not. Force-stopping "for a clean test" is what silently
  uninstalled the wallpaper under a dozen consecutive experiments. For a cold
  start, launch the activity and press HOME; never stop the package.
- **A new `onSurfaceCreated` means a new EGL context, so the renderer must forget
  every GL name it cached.** The wallpaper loses its context routinely, because
  the system tears the engine down and rebuilds it. `ReliefRenderer` keeps
  `hasInstalled` for this, cleared alongside `textureColor` in
  `onSurfaceCreated`: without it `reloadFromPrefs` sees the pair it wants is
  already showing and returns without uploading, into a context that holds no
  texture. Symptom: a black wallpaper beside a diagnostic reporting the correct
  photo loaded. `glGenTextures` returning **0** is the tell — name 0 is the
  default texture, which has no image and samples pure black, so it is what a
  call made with no current context leaves behind. A texture name of 0 is never
  a success.
- **A load can land while the GL thread is parked, and a texture name of 0 means
  the install did not happen.** The studio writes the index while the wallpaper
  is hidden, and the engine's pref listener queues the reload; `queueEvent` then
  runs it *before* `onResume`, so `upload()` executes with no current context
  and returns 0. The frame that follows is black. What made this permanent rather
  than transient is that the renderer recorded the pair as installed anyway, so
  the next `onVisibilityChanged(true)` found `showingSamePair` true and declined
  to retry — the black outlived the condition that caused it. `install()` now
  returns false for texture 0 and nothing is recorded, so the next visibility
  change retries and succeeds. This is the same mistake as `hasInstalled` and
  `onSurfaceCreated` one level deeper, and the pattern is the lesson: **any state
  that records "this is on screen" must only be written when the GL calls that
  put it there actually succeeded.**
- **Decodes run off the GL thread and finish out of order, so the newest request
  must win by sequence, not by arrival.** A runnable queued while the engine was
  hidden replays the *previous* selection, and a large PNG decode can complete
  after a newer small one, installing the old photo last. Observed on device
  (`STALE` in a temporary trace), not merely possible. `loadSeq` tags each
  request and a result whose tag is not the newest is dropped.
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
  (`tau = 0.2s`) so the feel is identical at 60/90/120 Hz. After 10 s of stillness
  it unregisters the rotation-vector sensor and falls back to a low-power
  accelerometer until a shake — **that idle-sleep is the app's real battery win**,
  not anything in the draw path.
- **Sensor power, and what is deliberately *not* done to it.** Three rules, each
  of which cost something to learn:
  - **The idle accelerometer is the only thing still polling once the phone is put
    down**, so it is the only safe target. It runs at 2 Hz with a 1 s batching
    window (`ACCEL_IDLE_PERIOD_US` / `ACCEL_IDLE_MAX_LATENCY_US`). Do not raise the
    window to save more: the window is also the worst-case latency before a shake
    is noticed, and a wallpaper that takes a second to wake feels broken. The
    saving is real but modest — the accelerometer is the cheapest sensor there is,
    and the rotation vector is the expensive one, which is why the idle-sleep
    above matters more than any of this.
  - **Never batch the rotation vector.** Batching is invisible on the idle path and
    ruinous on the live one: a batched delivery arrives late and unevenly, which is
    precisely the jitter the smoothing exists to hide. One code path, one policy:
    `registerRotationVector()` picks the period, and batching is a decision made
    only by the idle branch.
  - **Never slow the live sensor to save power**, and never add a frame-rate cap.
    The parallax feel is the entire product; the draw rate is already bounded by
    the sensor, and a cap tears the screen (see the frame-pacing invariant). The one
    deliberate exception is OS battery-save, which cuts the rotation-vector rate
    from 50 Hz to 20 Hz — reduced smoothness, still responsive, and only when the
    user has already told us they want less.
- **A state field that nothing reads is a bug, not a placeholder.**
  `ReliefRenderer.powerSave` was written by three call sites that all queried
  `PowerSave.isOn()` and read by none, so the app detected battery saver and did
  nothing — at the one moment the answer is worth having. The field is now gone
  rather than kept "for a future resolution-scale change": the renderer has no
  frame rate to give up, so it could never act, and speculative state is the same
  bug one commit later. The response lives in `TiltController.setPowerSave`. Note
  the audit gap this exposed: a dead-code sweep that counts *occurrences* misses a
  field that is assigned and never read, which is a different and quieter failure.
- **A sensor that cannot change a pixel should not be running.** At Motion 0 the
  renderer draws the same frame however the phone is held, so
  `SensorPolicy.shouldRunSensor()` includes `motionActive`. Both hosts feed it from
  the value they have already resolved — `Framing.motion` in the service, the
  active item's settings in the studio — rather than asking the renderer, because
  that value is on the calling thread and a getter would have cost a hop to the GL
  thread to learn something already in hand. Default is `true`, not false, so a
  caller that never mentions Motion still gets the parallax rather than silently
  losing it. **Because Motion belongs to the active photo, this is now a
  per-photo decision:** switching from a photo at Motion 60 to one at Motion 0
  parks the sensor, which is the whole point of moving it off the config.
  `refreshSliders` reads the settings once and uses that one value for both the
  slider and the sensor, because two reads are two chances to disagree — which is
  how a slider ends up showing 0 while the sensor is still told to run.

### Build constraints

- The manifest has **no `package=` attribute** (AGP 8 rejects it). The package
  lives in `app/build.gradle` as `namespace` / `applicationId`, and that is now
  the only place it is declared. Keep the two in sync when renaming.
- `--` is illegal inside an XML comment. Writing `--flag` there fails the build
  with a confusing parse error.
- Java 8 source/target (`VERSION_1_8`).
- The container bakes its own debug keystore. Without it AGP mints a fresh key
  in ephemeral storage every build, every APK gets a different signature, and
  `adb install -r` always fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
  whose only recovery is an uninstall, which wipes the user's settings.

### Termux notes (host-side, not a build path)

`/tmp` is often not writable — use `$(mktemp -d)`. `#!/usr/bin/env` shebangs
fail (no `/usr/bin/env` on Android), which is why the npm scripts invoke vite via
`node node_modules/vite/bin/vite.js`. Shared storage is
`~/storage/shared/Download/`; copy APKs there and install from the file manager,
since launching the installer programmatically is unreliable on some devices.
There is no longer a build script that runs here — everything builds through
`podman-build.sh`.

## Verification

`./podman-build.sh test` is the gate — pure JVM, ~15 s, no device, and every
`assemble`/`bundle` depends on it, so **there is no path to an APK that skips
it**. 241 tests across sixteen classes:

- `ReliefMeshGoldenTest` — exact vertex positions and normals for a fixed depth
  map. Mutation-checked: re-quantising the blur, halving its radius, flipping
  the v convention and dropping a face normal each fail it.
- `Mat4ScaleTest` — the `scaleM` semantics, captured from the real Android API.
- `CameraModelTest` — tap → unproject → project round-trip, plus "every Depth
  step is live at every zoom" and "Depth 0% is flat at every zoom".
- `PrefFilesTest` — the restore case, which silently costs users their image.
- `PrefFilesContentTest` — that two files are the same image, which is what stops
  a re-share from adding another identical gallery row. The traps it pins are the
  two ways the check can be made too eager: two different photos sharing a byte
  count must **not** collapse, and a missing file must never count as a match or
  an import gets swallowed by a row that has lost its image.
- `ConfigTest` — the settings blob: round-trip, clamping, and that `parse`
  never throws, because the wallpaper parses it on the GL thread at cold start.
  It also pins that pan is in **pixels** and must not be clamped to orbit's
  fraction range.
- `ComfyWorkflowTest` — the ComfyUI graph wiring, especially that the depth
  node reads node 1 and `SaveImage` reads node **2**, not 1. An off-by-one
  there renders the wrong tensor with no error.
- `FramingTest` — the one resolution of what should be on screen. It
  is in `:core` rather than `:app` for a reason: it lived in `:app` for one
  commit, and in that window the studio loaded a newly selected photo's
  files without its framing, so every photo arrived through the previous
  photo's pan, zoom and depth. A value that cannot be unit tested is a
  value that cannot be checked, and this is the one most likely to be
  quietly wrong. It also pins that the bundled demo renders at the
  defaults *whatever the index says*, because a Zoom stored on it is a
  number a slider will display and the renderer will never use — now on
  **both** surfaces, since a lock override is a second way to store a value
  nothing reads. Its third theme is Motion: a test here used to assert Motion
  came *from the caller*, which was right while Motion was global and is the bug
  now that it is per-photo. It also pins the inheritance rule — a lock screen with
  no override resolves to exactly the home screen, because that is every photo's
  initial state, and a write to home must not conjure an override.
- `PerItemSettingsTest` — per-surface settings, the migrations that brought them
  here, and the one reserved id. The traps are the ones this file already names:
  pan is in **pixels** and must not be clamped to origin's fraction range (delta
  exactly 0, so a partial clamp cannot hide), and the bundled item is the one entry
  with no file on disk, so a cleanup pass or a parse must not drop it — it cannot
  be recreated from filesDir. Two more it earns: the v2 → v3 migration must copy
  the old **global** Motion onto every item, because the library blob has no copy of
  it and dropping it silently resets everyone's wallpaper speed on upgrade; and a
  single-field edit must not reset the values it does not name, which shipped broken
  once — `withDepth` reset Motion, brightness and shadow, because the values it did
  not pass had been added after it was written.
- `SurfaceResolverTest` — which surface an engine is drawing, and the **three**
  questions it takes to answer that: the engine's own surface, whether the lock
  screen composites it, and what the lock screen can be at all. They are three
  separate truths on purpose — collapsing them either offers a control nothing
  reads, or refuses one that would work. The traps are the ones the truth table
  names: 26–33 have no signal to read, 34 has the signal but not the second engine,
  "both" is one window and therefore one set of values, and a lock-only binding on
  34 is a **fact** while per-surface lock settings are still a **promise** — the
  two must not be collapsed, or a Lock toggle appears on a screen whose value is
  never rendered. A negative or unread flags value is not a bitmask; usability is
  decided by sign, because masking −1 yields every bit.
- `PictureTest` — brightness and shadow, which live in `:core` precisely so they
  are not trapped in GLSL where nothing can check them. The trap is the one this
  project keeps paying for: **0 must be the identity for both**, because a new
  photo is written with explicit zeros and so every photo in every library takes
  that path on its first render. Brightness is hundredths of a *stop*, so it is
  exponential — a linear mapping passes a naive identity test while being visibly
  wrong. Shadow is signed, or "less contrast" becomes unreachable.
- `PlaylistTest` — rotation, and the invariant it exists to hold: rotation writes
  the index's own selection, by the same two calls a user's tap makes, so there is
  never a second pointer. The test asserts **byte-equality of the whole blob**, and
  a second test enumerates the blob's allowed keys — so adding one is a deliberate
  edit rather than an accident. Also pinned: a delete removes the id from every
  playlist in the same derivation; a corrupt or future blob yields no rotation and
  does not throw; a playlist naming a deleted photo cannot resurrect it and a hole
  does not cost the playlist; an event trigger is never due on a clock; and a user's
  hand-picked selection does not move a playlist's own clock. Note the trap in its
  own making — a "migration is idempotent" test that only re-parses the
  re-serialised blob **skips the migration entirely** and so cannot test it.
- `LibraryDuplicateImageTest` — which row survives when several share an image
  file, which is the repair for a share path that wrote every import to one fixed
  name. The trap it exists to pin is *which* survivor: the active row is kept even
  when it is the oldest, because keeping "the newest" instead would drop the row
  the selection names and leave the wallpaper pointer pointing at nothing. The
  last test asserts the end-to-end property — after the repair, the selection still
  resolves to a row that exists.
- `LibraryTest` / `LibraryItemTest` / `SortOrderTest` — the gallery index. The
  traps are the ones this file already names: a corrupt blob must not throw, a
  restored backup must not resurrect a dangling selection, an absolute path
  must never be stored, and deleting the *active* item has to clear the
  wallpaper pointer in the same write — otherwise the next reconcile adopts the
  deleted files straight back and the delete never sticks.

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

- **`adb exec-out screencap` cannot be trusted to show what is on the display,
  and using it to verify a drawing bug manufactures a pass.** Measured on the
  Pixel 7 Pro while the studio's bottom sheet was invisible-but-operable: two
  captures three seconds apart were byte-identical, `screencap` returned a
  different buffer each time it was invoked, and it showed a panel that was
  demonstrably not on the phone. Taking a capture appears to force the very
  redraw under test, so the screenshot confirms the fix you just made. It
  produced three consecutive false confirmations in one session, each
  announced with a table of measurements, none of which could have failed.
  **Ask the user for a screenshot taken on the device** (Power + Volume Down)
  and pull it with `adb pull` — that path goes through the display and shows
  what is actually there. `dumpsys activity top` is fine for existence
  questions (is this view in the hierarchy, what are its bounds) and useless
  for appearance: child bounds are **parent-relative**, not screen-absolute,
  so a swipe aimed at y=36 can land on the photo rather than on a control 36px
  below the panel's top edge.
- **A view that is placed correctly, opaque, attached, `isShown()`, and still
  not on screen has had its pixels discarded, not never drawn.** That symptom
  — operable to the touch, absent from the glass — is a stale window region,
  and the fix is to change **layout**, not a transform. The sheet used to hide
  and show with `setVisibility(GONE)`/`VISIBLE` and worked; rewriting it to
  position with `translationY` broke it, because a visibility change re-lays-out
  and repaints while a transform did not reliably repaint that region here.

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
