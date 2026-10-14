# SPLAT-PLAN — real Gaussian splats as the live wallpaper

Status: **Stage 0 complete.** Tracking doc for the stages, the measured budget, and the
open decisions. Update the checkboxes as work lands.

> **A correction found while porting the spike.** The spike's vertex shader read the
> covariance as `c_ij = dot(A3[i], A3[j])` with `A3 = viewRot · R · S` — the Gram
> matrix of the COLUMNS, which algebraically collapses to `S²`: the splat's rotation
> and the view rotation both cancel, so every splat was a world-axis-aligned ellipsoid,
> not the "full covariance" the spike's own commit message claimed. The product port
> uses the chain the plan states (`Σ3D = R·S·Sᵀ·Rᵀ`, `ΣV = V·Σ3D·Vᵀ`, Jacobian,
> `Σ2D = J·ΣV·Jᵀ`), which is what `SplatProjectionTest` pins. The spike's *ceiling*
> measurements (fps vs count) are unaffected — they were fill-bound — but its splat
> SHAPES were the diagonal-only approximation it meant to improve on.

## What is already proven (device measurements, Pixel 7 Pro / Mali-G710)

The spike (`spike-splat3d`: `4b063b2`, `05207d7`, `71e3cdd`, `805ef4d`) renders real
Gaussians on the phone: full covariance projection (the vendored SuperSplat formulation —
**not** the web viewers' diagonal-only approximation), instanced quads from one RGBA32F
texture plus an order texture, sorted alpha, one `gl_VertexID`-derived quad per splat.

- **Ceilings** (AOT release install): ~**100–150k** splats at 50–60 fps; ~250–400k at ~30;
  1.18M at ~13 fps GPU-only. The GPU is the wall at every rung ≥150k; the sort is not.
- **Fusion vs unfused on device**: the fused static layer is byte-identical across frames
  while the unfused far field moves ~16 px/frame. The fused render is also *cheaper per
  count* (its re-sample emits smaller splats): 147k raw SHARP ≈ 42–55 ms GPU against the
  fused 4× rung's 13–21 ms.
- **The classification is the quality weak link** (the user's verdict): fusion wins where
  the static/dynamic split is right (the train), the per-frame cloud wins where it is
  wrong (the barn's field, the station's edges).
- **Format**: `.splat` (32 B/record) is read and rendered from the app's files dir today.
  `.ply` should be converted at generation time (3.9 GB PLYs → 2.2 GB `.splat` for one
  clip), never shipped.
- **The Java penalty, and the two numbers that are easy to conflate.** This device
  has `dalvik.vm.usejit=false` and ART refuses to AOT a **debuggable** app, so a debug
  install is interpreter-only. Measured on a Pixel 7 Pro, same 299,008-splat cloud and
  settings, debug/`[status=verify]` against release/`[status=speed]`: **~40× fps**
  (1.25 → 50.1), `sortMs` 326 → 5.55 ms, `frameMs` 802 → 19.95 ms, with the GPU
  identical in both. Every Java `O(N)` loop is 30–100× slower interpreted, and the
  dominant one — the depth sort — is memory-bound, which is why it is catastrophic
  rather than merely slow. The narrower **1.5–2× fps** is a *different* comparison: a
  release build **before** it was AOT'd against the same release build after
  `cmd package compile -m speed -f` (sort 27.9 → 3.0–4.5 ms at 4×). There the frame is
  already GPU-bound, so the 7× sort win shows as only 1.5–2× fps — a fact about AOT on
  a release build, not the debug penalty. The compile is run explicitly either
  way, because the install-time filter is a ROM policy and not a guarantee —
  measured here a release `adb install -r` lands at `status=speed [reason=install]`,
  but a debuggable app gets no install-time AOT at all. It is now part of
  `podman-build.sh install` rather than a step to remember.

## Stages

### Stage 0 — a splat as the wallpaper (demo)

- [x] `:core`: the `.splat` reader/model and the projection math as pure functions
      (Σ3D = R·S·Sᵀ·Rᵀ, view Jacobian, conic, eigen-derived quad extents) with tests
      (`SplatCloud`/`SplatCloudTest`, `SplatProjection`/`SplatProjectionTest`; the
      3σ axis is pinned at exp(−4.5) on both principal axes and a degenerate
      covariance is refused rather than NaN'd).
- [x] `:app`: a GLES3 Gaussian renderer ported from the spike (`SplatRenderer`),
      fitting the wallpaper's model (`RENDERMODE_WHEN_DIRTY`, the sensor's
      `requestRender`, no continuous draw), with sorted alpha, frustum culling
      (shader extents + a CPU centre count) and render-scale (`splat_scale`).
- [x] A splat file on the item (nullable **filename**, never a path; `PrefFiles`
      resolution; 0-or-absent in JSON; every mutator carries it — the `withDepth`
      scar; `splatFileName`/`hasSplat`/`withSplat`).
- [x] The wallpaper draws it when present; the studio's 3D switch selects it
      (`render_3d`; the splat replaces the relief only when a cloud is actually
      uploaded, and the relief's card mode is skipped while one is expected).
- [x] One budgeted demo cloud (149,955 splats) generated offline by a **uniform
      (u,v) re-sample** (`scripts/make-demo-splat.py`), pushed, and verified on the
      home screen: tilt moves it, ~31 fps, GPU ~40 ms, wallpaper component intact.
- [x] Gate: `./podman-build.sh build-apk`, `node scripts/check-shaders.mjs`,
      `node scripts/check-parity.mjs` (AGENTS.md counts updated: 591 tests / 33
      classes).

**What shipped, measured on a Pixel 7 Pro (Mali-G710, 1440×3120, release build +
`cmd package compile -m speed -f`).** 151,552 splats (149,955 + 2048-pad) in the
wallpaper engine: ~30–32 fps, `frameMs` 31–33, GPU 38–45 ms, CPU sort 7–11 ms per
camera move (0 when still), draw submit 0.21–0.32 ms. That is the bale's honest
cost: the spike measured 147 k raw SHARP at 42–55 ms GPU, so a hole-free cloud of
this scene is fill-bound at ~40 ms on this phone — the plan's 50–60 fps budget is
for smaller-splat clouds (the fused static layer), not the bale. The `:core` probe
runs once per load on device and reads `powerAtR1 = −4.5000`, the chain's own
consistency check.

**Two defects found in the first Stage-0 drop, both fixed here:**

- **Every item showed the splat.** The debug pref applied to any row that named no
  cloud, which is not a debug path but a regression: selecting a photo replaced it
  with the demo. The rule is now `Library.splatNameFor` in `:core` (unit tested):
  a row's own filename wins, and the debug filename is reachable from the
  **reserved `BUNDLED_ID` row alone**. Every other row renders its own media.
- **The demo cloud was a 1-D stride, and it showed.** Stride 8 over SHARP's
  storage order leaves the kept points unevenly spaced in image space — rendered,
  that is the vertical striping the user reported (off-device renders of the same
  cloud: `cap150` and stride-8 stripe, the grid does not). Measured off-device on
  the same cloud: stride 8 covers **59.5 %** of the subject's 2-px cells, the
  uniform grid **100 %**. The demo is now a (u,v)-cell re-sample at SHARP's own
  lens (cell 5.6 px, highest-opacity point per cell) with a **per-splat**
  compensation — `√(cell multiplier)`, the multiplier being the cell's area over
  the splat's own screen footprint, grown by a margin for the alpha falloff.
  Measured on the phone at 150 k: the **global** `√(visible/kept) = ×2.80` (the
  shipped stride cap's `√step`) reaches 100 % coverage at **110 ms GPU ≈ 10 fps**,
  while the per-splat rule reaches it at **~40 ms ≈ 31 fps** — the global factor
  inflates the big near splats by the same amount as the small far ones, and the
  renderer is fill-bound. That is the deviation from the literal global factor, and
  it is why the count target is met at a reviewable frame rate rather than 10 fps.
  The script is `scripts/make-demo-splat.py` (`--comp global` reproduces the
  shipped cap's factor for comparison).

**Deliberately deferred, and labelled in the code:**

- **The item field is real; the demo delivery is a debug pref, gated to the bundled
  row.** The index change (`splatName`, `withSplat`, `PrefFiles`, 0-or-absent,
  every mutator) is the product shape and is unit tested. But nothing in Stage 0
  *writes* it — there is no import UI — so the demo is delivered by
  `KEY_SPLAT_FILE` (`splat_file`, default `demo.splat`): a bare filename in
  `filesDir` used **only when the active item is the reserved `BUNDLED_ID` row**.
  Stage 1 replaces it with a splat in `res/raw` copied by the ordinary seed and
  deletes the pref. It cannot hijack a normal photo: no string a normal item can
  carry reaches it.
- **The splat's camera is hard-coded to the demo's.** The source view is SHARP's
  own camera for a 1440×2560 still (`f = 2036.6 px`, `FOVY = 64.3°`, target on the
  optical axis at the bale's front surface). There is no camera on the item, so a
  different cloud would not frame correctly — Stage 1/2 must carry the generation
  camera (as `focal` does for the relief).
- **Tilt, zoom, Origin and Pan.** The splat responds to the gyro (the product's
  `TiltController` semantics, `motion` scaling included), to Zoom as a field-of-
  view crop, to **Origin** as the point the tilt's turntable turns about (the
  cloud's own surface under the origin's ray, with the picture shifted to it) and
  to **Pan** as a screen-pixel view offset — the relief's own values, meaning the
  same thing. Depth is still a relief concept and is not mapped: the cloud's shape
  is reconstructed, so there is nothing to displace, and the row stays hidden. The
  camera lives in `:core` as `SplatCamera`, with its at-rest invariant asserted by
  `SplatCameraTest`.
- **A transient "could not load (upload failed)" on a fresh engine.** A queued
  upload can run before the GL thread has a current context; `upload` now refuses
  it (rather than building against nothing) and the next resolve retries — observed
  self-healing within a few seconds. The retry is on the next resolve, not a timer.
- **The sort runs on the GL thread.** ~3–9 ms in release, but ~140 ms on the first
  sort in a debug build (the documented Java penalty). Fine at this budget; the
  Stage 1 LOD policy is where a cheaper order would live.
- **No budget/LOD at load.** The demo is pre-budgeted offline by a uniform (u,v)
  re-sample of SHARP's 1.18 M (`scripts/make-demo-splat.py`, cell 5.6 px, ~150 k).
  Stage 1 owns emit-to-budget and distance-adaptive LOD.
- **The render-scale knob is a pref, not a control** (`splat_scale`, 0.25–1.0,
  default 1.0). Measured unnecessary at 147 k, so it stays out of the UI.

### Stage 1 — product shape

- [ ] A `SplatSource` vocabulary beside `DepthSource` / `NormalSource` / `VideoSource`.
- [ ] The bundled demo ships a splat in `res/raw` (the ordinary-photo rules apply).
- [ ] The budget/LOD policy in `:core` (emit-to-budget; distance-adaptive LOD).
- [ ] The studio's state line and Generate/Replace actions for splats.

### Stage 2 — generation in-product

- [ ] A "Make 3D" job reusing `Jobs` / `VideoImport`: upload the still or clip, run
      SHARP → VGGT → fusion on the box, download the budgeted `.splat` set with the
      sidecar's progress, and let the item render it.
- [ ] Server side: a custom node (or small service) beside `MoGeVideoStream`.

### Stage 3 — size and quality

- [ ] A compressed container (splats4d-style; our coherent half measured **66×**,
      431 MB → 6.5 MB).
- [ ] Tracked Gaussians for the dynamic layer: exact classification, compression, and a
      lower per-frame count in one change.
- [ ] Streaming / LOD selection at load.

## Open decisions

- **Clip playback rate.** The builds sample at 8.53 fps; the sources are 24–60. The
  dynamic layer's size at a real rate is the shipping question (hundreds of MB) — solved
  by Stage 3, or by accepting the sampled rate.
- **Where the budget is enforced** — the generator (simplest, one number per device class)
  or the loader (adaptive).
- **On-device generation is out of scope.** SHARP-class models do not run on the phone;
  the box generates, the phone renders.
