# SORT-MEASUREMENTS — the depth sort's two levers, measured

The depth sort is the largest remaining measured cost on the splat's GL thread.
Two levers were proposed against it: the bucket count (`SORT_BUCKETS = 262144`)
and the gate (`needsSort`'s `1e-3`). This file is the measurement of both — a
**curve and a picture cost, not a change**. No shipped constant was moved.

Everything here is device work except the picture comparison, which is a
reference render (there is no device screenshot; see *What this could not do*).

## Method

- **Device** `<device-serial>` (OnePlus 6T, Android 15). Bundled row *Marble bust* in
  Splat mode, `files/item-bundled.splat` = **403,456 splats**, render scale
  1.00, `render=1080x2340`, `eps2d=2.4078`.
- **Build: release, not debug.** A debug APK's sort is **~58 ms** here even
  after `cmd package compile -m speed -f` — the documented Java penalty — so the
  release APK is the only usable measurement build (the previous bucket A/B used
  release too). Both carry the same `BuildConfig.GIT_COMMIT`, and the debug
  APK's sha256 is used for the revert check below.
- **Diagnostic.** A temporary dev override read from a file (`buckets`, `eps`,
  `force`, `chain`, `dtilt`), so the two levers could be swept in one install
  and one thermal session. `force` runs a sort every frame; `chain` keeps
  requesting frames (a continuous workload); `dtilt` adds a synthetic per-frame
  tilt step, a controlled stand-in for a hand. **All of it is reverted**; the
  shipped APK's sha256 is unchanged.
- **Two frame sources, never compared across them.** (a) **spaced taps**
  (`adb shell input tap 540 600` every 1.15 s) — the cold method, one frame per
  tap. (b) **chained** continuous draws — the hot method. `sortMs` is the
  `splat:` line's per-sort average; `gpuMs` its GPU timer.

## Lever 1 — `SORT_BUCKETS`

### The curve

`force=1` so every drawn frame sorts; `sorts/s ≈ fps` (the ratio is 1.0–1.05).

**Spaced taps (cold) — the method that reproduces the known 13.6 ms:**

| buckets | `sortMs` median | `gpuMs` median |
|---|---|---|
| 8,192 | **11.14 ms** | 73.49 |
| 65,536 | **11.82 ms** | 72.17 |
| 262,144 | **13.09 ms** | 72.63 |

**Chained (hot) — a cross-check, a different method:**

| buckets | `sortMs` median (two rounds) | `gpuMs` |
|---|---|---|
| 8,192 | 18.52 / 18.78 ms | 37.0 |
| 65,536 | 19.31 / 19.32 ms | 37.3 |
| 262,144 | 20.47 / 20.07 ms | 37.5 |

- The 262,144 spaced-tap median (**13.09 ms**) reproduces the known **13.6 ms**.
  The chained number is higher because the sustained load throttles the SoC
  (some windows fell to `gpuMs` 65–78, `fps` 11–14). **The two curves are not
  comparable in absolute terms; only within themselves.**
- Both curves agree on the shape: the 32× costs **≈ 1.95 ms cold / ≈ 1.7 ms
  hot** per sort. That is the `Arrays.fill` + prefix sum over the bucket array,
  which is a function of the bucket count and not of the splat count — the
  previous session measured **2.07 ms** for the same 32× at 149,504 splats on
  the Pixel, and this is the same number.
- `gpuMs` is flat across the bucket counts (72.2–73.5 cold). That is the
  control: the bucket count cannot change the GPU's work, and it does not.

### The picture cost

The `.design` WebGL harness — a port of the app's own vertex and fragment
shaders, run on the desktop GPU (SwiftShader), deterministic across runs — was
pointed at the same 403,456-splat cloud at the device's framing (1080×2340,
fovy 100°, floor 2.4078). A near-exact sort (**1,048,576 buckets**, bucket width
≈ 6e-7) is the reference, so the difference is the ordering artifact alone.

| buckets | pixels differing | > 8/255 | > 16 | > 32 | max |
|---|---|---|---|---|---|
| 8,192 | 14.26 % | 7,495 | 1,429 | 104 | **70** |
| 65,536 | 3.15 % | 516 | 36 | 0 | 25 |
| 262,144 | 0.75 % | 74 | 2 | 0 | 18 |

- **Lowering to 8,192 does bring back a real ordering artifact.** It is a
  deterministic speckle: 7,495 pixels differ by more than 8/255, 104 by more
  than 32/255 (up to 70), in 1,413 components of median size 3 px, concentrated
  on the subject (rows 468–1,872), not the sky (top rows: 0 differences).
- **So the 32× was not pointless.** It removes a real, if small, ordering
  artifact. What it did *not* do is clear the artifact it was bought for: the
  reported "white noise on the sky, a face and a wall" was root-caused as the
  packed-colour NaN (`98d74ec`), which is a different artifact in a different
  place, and a desktop reference could not have shown the ordering hypothesis
  wrong because it preserves the NaN payload.
- The honest shape of the trade: **≈ 2 ms per sort buys a change confined to
  ~0.3 % of pixels.**

## Lever 2 — the sort gate

### The derivation

The gate belongs to the **splat** camera, so the bound must be the splat
camera's own largest visible screen displacement per unit tilt, over 8
directions, through the real `SplatCamera.frame` chain on the real cloud:

| framing | px / unit tilt | sub-pixel `eps = 0.5 / px` |
|---|---|---|
| the device's own (fovy 100°, origin (−0.352, 0.341), pan (−55, 451)) | 183.13 | **2.73e-3** |
| worst reachable for this cloud (zoom 2, origin (0.35, 0.35)) | 374.15 | **1.34e-3** |

`CameraModel.tiltPixels` — the task's suggested home — is the **relief's**
camera, and at the same framing it answers 399 px/tilt → **1.25e-3**. It is the
wrong camera here and is conservative by ~2×: using it would sort more than the
splat needs. The splat's tilt is the same `TILT_RANGE` and moves the eye the
same 0.16 per unit, but the splat's fovy is clamped to 100° at this zoom (the
relief's is 45°), so the same world motion projects smaller.

Consequence for the design: a **single constant** safe at every framing of this
cloud is ~**1.3e-3** — only 1.3× the shipped `1e-3`, i.e. a near-nothing lever,
and it would be *negative* at framings where the cloud is barely visible. A
**live, per-framing bound** (`0.5 / pxPerTilt` at the current framing) is the
defensible one and is ~**2.73e-3** on this device.

### The effect

A synthetic per-frame tilt step `D` drives the real `needsSort`, with `chain=1`
so frames are continuous. `sorts/s ≈ fps × min(1, D/eps)` — measured:

| per-frame step `D` | `eps = 1e-3` (sorts/frames) | `eps = 3e-3` | `eps = 5e-3` |
|---|---|---|---|
| 4.0e-4 | 0.364 | 0.126 | 0.074 |
| 1.9e-3 | 1.050 | 0.459 | 0.344 |

- The rate is inversely proportional to `eps` while `D < eps`. Raising the gate
  to the live sub-pixel bound (2.73e-3) therefore gives **≈ 2.3–2.9× fewer
  sorts** on this device (measured at `eps = 3e-3`, which brackets it). A single
  safe constant (1.3e-3) gives only **≈ 1.3×**.
- At the task's stated per-frame delta (1.9e-3) the shipped `1e-3` gate **does
  never skip** (`sorts/frames` = 1.05), which is the premise confirmed; a
  2.73e-3 gate skips ~2 frames in 3.
- The **staleness the picture would carry** is bounded by construction: at the
  framing the bound was computed for, a camera change of `eps` moves no visible
  pixel by more than 0.5 px. But that is only a *sub-pixel* guarantee. A fixed
  `eps` of 5e-3 is **1.87 px** at the worst framing (zoom 2, corner origin), so
  the bound must be live, not a constant. And "the frame is pixel-identical" is
  a stronger claim than "the order cannot visibly change" — the order can still
  flip for splats within a sub-pixel depth band, and whether that shows depends
  on their overlap. That part was **not** verified on a phone.

## The sort's real share of the frame

- **Per sort:** ~13 ms cold, ~20 ms hot at 262,144 buckets. The cold number is
  the one comparable to the literature and the previous session.
- **Rate:** with the phone still, the wallpaper logs **`sorts/s=0`** — the gate
  skips everything, which is the doze working. With a hand at `D = 1.9e-3` the
  gate never skips, so `sorts/s ≈ fps ≈ 26` on this device → **≈ 340 ms/s
  (cold) to ≈ 520 ms/s (hot) of a CPU core — 34–52 %**. At a slow `D = 4e-4`
  the gate skips to ~0.36×fps → **≈ 12 %**. The task's 15 % (Pixel) sits in that
  range; the sort is the largest CPU cost on the GL thread and it is heat and
  latency, not just frame time.
- **But it is hidden under the GPU.** The frame is GPU-bound (`gpuMs ≈ 37.5`
  hot, `sortMs ≈ 20`), so the sort overlaps the previous frame's GPU work. The
  ≈ 2 ms/sort the bucket count buys moved `fps` from 21.62 to 22.00 — about
  0.8 ms of frame time, at the edge of noise. **On this device and this cloud
  the bucket saving is essentially invisible in frame time; its value is CPU
  headroom and heat.**

## What this could not do

- **See the phone's own frame.** `adb exec-out screencap` is not trustworthy
  and was not used, and no user screenshot was available, so the Lever 1 picture
  judgement is a **reference-render** judgement, not a device one. Whether the
  8,192 residual (0.3 % of pixels, up to 70/255) is visible in the hand is the
  user's eye.
- **Move the phone.** The 6T sits still and dozes, so the real gate could not be
  driven by hand; the per-frame tilt step is synthetic. The gate's *behaviour*
  is a pure function of that step, which is why the sweep is meaningful, but the
  step's real-world distribution was not measured.
- **Measure the sort cost on a phone that is not throttling.** The chained
  method runs the SoC hot; the spaced-tap method runs it cold. Neither is the
  steady state of a pocketed phone, and the two differ by ~1.6× on `sortMs`.

## Verification

- `cd android && ./podman-build.sh build-apk` — `:core:test` green (731 tests
  across 40 classes), app compiles.
- `node scripts/check-parity.mjs` — OK. `check-shaders.mjs` — 12 shaders, 0
  failed. `check-json-puts.mjs` — OK.
- **Revert check:** the diagnostic is fully reverted and the rebuilt debug APK's
  sha256 is
  `c4d951570c2db9b9be6b92fd82b796898c0806163f9c918db3b60a0a6520b9e7` — byte
  identical to the pre-experiment build of `9ec30c3`. The pulled installed APK
  matches it.
- Device left as found: bundled row *Marble bust* in Splat mode, origin/pan/
  zoom/depth unchanged, render scale 1.00, live wallpaper bound on HOME.
