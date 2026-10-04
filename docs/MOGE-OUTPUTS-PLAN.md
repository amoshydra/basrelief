# Using MoGe's depth, normals and mask — the plan

Written 2026-10-04. A design document, not code. It decides how the three
useful `MoGeRender` outputs reach the app, and it is written to be the interface
between two changes happening in parallel: the still graph moving to MoGe
(`ComfyWorkflow`) and the renderer moving to GLES3. **Do not touch either from
this document's work**; the sections below say what each side must expose and
consume.

Read `AGENTS.md` first. Read `docs/VIDEO-PLAN.md` second. This file builds on
both and assumes them.

## The measured facts this plan is built on

These came from the live server and are inputs, not things to re-derive:

- `MoGeRender` has five outputs: `depth`, `depth_colored`, `normal_opengl`,
  `normal_directx`, `mask`.
- `depth` is 256 grey levels, structure correct, but a **glowing lamp becomes a
  huge white blob** — a monocular model cannot know a light source's distance.
- `mask` is a silhouette (mostly white, subject black). It is **not** depth.
- `normal_opengl` / `normal_directx` are the same map with green flipped.
  Decoded correctly, the normal agrees with the depth **84% of the time within
  30°**. An earlier 17% figure was a decoder bug.
- MoGe runs at **0.91 s/frame at 720×1366**; Depth-Anything V2 at 2.1 s/frame at
  135×256. Faster and vastly higher resolution.

## The one insight, stated once

Today the normal is **derived from the depth gradient**,
`normalize(-dz/dx, -dz/dy, 1)` — on the CPU in `ReliefMesh.build`, and again in
the GLES3 vertex shader (`VERT3`). That is why 8-bit depth terraces become
visible contour lines under grazing light, and why `AGENTS.md` insists on a
**float** blur (`DEPTH_BLUR_FRACTION = 1/78`) before the gradient is taken: the
blur is a workaround for a derivative of quantised data.

**MoGe predicts the normal directly.** There is no gradient step, so there is no
quantisation to smooth and no noise to amplify. The banding's *cause* is
removed, not attenuated.

**But that only holds where MoGe made the depth.** A user's own imported depth
file has no predicted normal, so the derived path must stay, and stay correct.
Everything below is about keeping those two worlds from drifting apart.

---

## 0. Two facts to establish before building (cheap, do first)

Both the mask plan and the agreement policy rest on things that are half-known.
Settle them with one photo and one run, off the app:

1. **Mask polarity and coverage.** Is white *valid* or *background*? Does the
   mask actually cover the lamp blob, or is it only a figure/ground silhouette?
   Record the answer with the mask rendered beside the depth. This decides
   whether §4 can use the mask at all or needs an independent outlier heuristic.
2. **Does the 16% matter visually?** Render one real photo twice — derived
   normals vs supplied — and look at where `dot(derived, supplied) < cos 30°`.
   The current shader only adds `1 + 0.25*diff`, so a 30° disagreement moves
   brightness by roughly ±12%. It may be invisible. If it is invisible, the
   agreement gate in §3 is unnecessary and we should not pay for it.

This is a spike, not a slice: no product code, nothing to keep. It collides
with neither in-flight conversion (it runs on the server plus one photo).

---

## 1. Storage — a third bare filename, scoped to depth

**Verdict: add `normalName` to `LibraryItem`, exactly parallel to `depthName`.**
Null means "derive the normal from depth", which is precisely what every
existing item already does. So the migration is a no-op and **`Library.VERSION`
stays 3**; an old build reading a new blob ignores the unknown `normal` key and
derives, which is the right degradation, and a new build reading an old blob
reads null and derives.

Do **not** infer "has normals" from provenance (a "came from MoGe" flag). The
renderer reads a *file*; if the file is absent the renderer must derive. A flag
that says normals exist while the file does not is the "asset with zero
references" failure `AGENTS.md` already records twice — a thing that looks
right in a build log and renders flat.

- `LibraryItem.normalFileName(id)` = `"item-" + id + "-normal.png"`, mirroring
  `depthFileName`.
- `toJson` writes `"normal"` only when non-null; `fromJson` reads it through the
  existing `str(...)` helper so JSON null and the literal `"null"` both become
  null.
- `hasSuppliedNormals()` is `normalName != null && depthName != null`. A normal
  with no depth is inert and must not be reported as present.

### The pair invariant does not become a triple

The image+depth pair stays the renderable pair, and a missing half still renders
FLAT with a status tell. **Normals are a modifier of the depth half, not a peer
of it.** Missing normals must *degrade to the derived path*, which is a correct,
shipping render — not a flat one. So:

- **Failure mode of a missing normal file: you get banding back.** Honest and
  self-healing, because `PrefFiles.resolve` returns null for a file that is not
  there and the renderer then derives. No row is dropped, because
  `retainExisting` only tests the image file (and it should stay that way: a
  missing normal is not grounds to lose the photo).
- **The failure mode to design against is not a missing normal but a *stale*
  one**: the normal predicted for a *different* depth, after the user replaced
  or regenerated the map. That is the one that produces "lit as though shaped
  one way while the vertices sit another", and it is worse than banding.

### The write discipline that makes staleness impossible

`normalName` is scoped to a specific `depthName`, so **every write of depth must
decide about the normal in the same derivation** — the same rule `withFiles`
already enforces for the image/depth pair.

- `withDepthAndNormal(depth, normal)` is the **only** writer that sets
  `normalName`. Used by a MoGe run, which produces both from one graph.
- `withDepth(depth)` is redefined as "depth with no supplied normal" and
  **clears** `normalName`. This is the classical generator and any hand-picked
  map. `DepthGenDialog.applyMap` is the single call site; it passes null for a
  classical run and a filename for a MoGe run.
- `withFiles(image, depth)` keeps clearing the normal (the bundled repair and
  any classical import). Add a three-argument `withFiles(image, depth, normal)`
  for a MoGe import.
- `DepthGenDialog` must learn which model produced the run so it writes the
  right pair. The run already knows its model; it must not lose it before
  `applyMap`.

Because the only setter is a paired write and the only single-field setter
clears, a stale normal cannot be produced by a code path — the same structural
answer `AppState`/`Framing` uses everywhere.

### One question, one answer (the `DepthSource` pattern)

Add a tiny `:core` `NormalSource` (or extend `DepthSource`) that answers "does
the renderer draw supplied normals for this item" from the item alone. Do not
let the gallery, the status line and the renderer each check `normalName != null`
separately: that is the `DepthSource` bug (the status line said "No depth" while
the renderer drew the embedded map). One function, and a test.

### The bundled demo

The marble bust stays without a normal. It is only 4-level quantised and hides
artifacts by construction (`AGENTS.md`), so it is the *wrong* photo on which to
demonstrate the improvement. Its normal is legitimately null → derived, which is
also the proof that adding the field changes nothing on upgrade.

---

## 2. The branch — one source of truth, one shading path

The proposed rule, "use MoGe's normals when the depth came from MoGe; derive
otherwise", is right. The risk is that "derive" already exists in two places
(CPU faces, GPU central differences) and "supply" would make three. Make it
one.

**Verdict: resolve the source once per load into a `NormalSource` carried on the
render, and give the shader exactly one way to consume a normal — a normal map.**

Concretely, the strongest form collapses both worlds into the same artifact:

- A still's render carries a **normal field** (the `(GRID+1)^2` normals), not a
  "has-normals" branch.
- **Supplied**: decode `normal_opengl`, flip vertically exactly like the colour
  bitmap, map `[0,1] → [-1,1]`, upload as an RGB texture.
- **Derived**: compute the normals with the **one `:core` routine** (the
  existing `ReliefMesh` face-normal maths, or a small `Normals.from(blurredField)`)
  and upload the same kind of texture.

Then the shader samples `uNormal` at `vUv` and lights it. There is no
`if (hasNormals)` in the shading code and **no second lighting path to drift**.
The derived formula lives in `:core`, where `ReliefMeshGoldenTest` can pin it,
instead of being re-expressed in GLSL. The GLES3 vertex shader's
central-difference code is then retired for stills rather than duplicated. The
upload cost is trivial next to the 12.6 MB mesh the GLES3 path already deleted:
a 257² RGB field is ~198 KB.

**Why not "always derive, use supplied only to correct" (the third option):** it
keeps the gradient of quantised depth in the pipeline, so banding survives and
the entire point is lost. The *only* place correlation with the derived normal
belongs is the inversion clamp in §3, which is a cheap safety net, not the
primary path.

**The one place a real branch is unavoidable** is video: per-frame CPU baking is
too expensive, so the video fragment shader must derive from the depth texture
itself. Until then, pin the GLSL derivative and the `:core` derivative with one
golden test, so the "two places" are provably equal rather than assumed.

### The correction supplied normals need

MoGe's normal is the surface normal **before** the renderer's `uIntensity`
stretch along z. The existing derived paths already account for the stretch
(`VERT3` folds `uIntensity` into `dzdX`; the GLES2 vertex shader writes
`aNormal.z / max(uIntensity, 1e-3)`). A supplied normal must get the **same**
treatment: `n = normalize(vec3(n.x, n.y, n.z / uIntensity))`. Skip this and the
lighting changes shape as the Depth slider moves, which is a drift between the
two sources hiding in a uniform.

### `normal_opengl`, not `normal_directx`

The renderer's normals are OpenGL-convention (+Y up, +Z toward the viewer), so
use `normal_opengl` and do **not** additionally negate G. Decode as world/object
space — **not** tangent space — so there is no TBN matrix. The vertical flip is
required because `GLUtils.texImage2D` puts row 0 at `v=0`; the colour bitmap is
already flipped, and the normal must be flipped identically or G is inverted and
the 84% figure collapses. Treat normal orientation as a **device-only check**
(`AGENTS.md`): it cannot be verified off the phone.

### What the renderer conversion must expose

For the GLES3 agent, the interface is small and should be stated, not
discovered:

- `Pending` carries the decoded normal (or null) beside `gray`.
- `Render` carries `textureNormal` (0 = derive). `isDrawable()` is unchanged:
  a normal is never required to draw.
- `showingSamePair(image, depth)` becomes `showingSamePair(image, depth,
  normal)` and `PairCache` keys on all three. This is the `hasInstalled` trap
  one level out: if the normal is not part of pair identity, a photo whose
  normal arrives later is "already showing" and the new texture is never
  uploaded.
- The status line distinguishes `custom image + MoGe depth + normals` from
  `+ depth (normals derived)`. `AGENTS.md`: an unreferenced asset is
  indistinguishable from a working one in a build log; the status line is the
  tell.

### What the graph conversion must expose

The still-graph agent must emit all three outputs from one run, because
`normalName` and `depthName` are written from the same `MoGeRender` execution:

- `MoGeRender(output=depth)` → `SaveImage`; `MoGeRender(output=normal_opengl)`
  → `SaveImage`; `MoGeRender(output=mask)` → `SaveImage` (mask used at import
  only, §4).
- Distinct `filename_prefix`es so the app can associate the returned files:
  `depthmap_<ts>`, `normal_<ts>`, `mask_<ts>`.
- `ComfyWorkflowTest` currently pins `SaveImage` to node 4 output 0 and
  `output=depth`; those assertions must grow to three savers with three wired
  outputs. (`AGENTS.md` notes the MoGe path already has its own five-node
  builder and its own tests for exactly this reason.)

---

## 3. Agreement — trust the supplied normal, clamp only inversions

The two maps agree 84% within 30°. What should happen in the 16%?

Frame it correctly: **the normal only drives the diffuse term.** The geometry —
silhouette, parallax, displacement — is entirely depth. A normal that disagrees
with the local slope cannot make the surface move wrongly; it makes the *shading*
disagree with the shape. And today that term is weak (`1 + 0.25*diff`), so a
30° error is about ±12% brightness.

**Verdict: the supplied normal wins per pixel. Do not blend. Do not gate at
30°. Clamp only near-complete inversions.**

- **Blend** (`lerp(derived, supplied, w)`) invents a knob with no measurable
  optimum, dilutes the benefit, and still cannot guarantee consistency. Reject.
- **Gate at 30°** (use derived whenever they disagree by more than the measured
  threshold) guarantees shading never contradicts geometry, but it does so by
  falling back to the banded derivative across 16% of the image — exactly the
  artifact this work removes. It also keeps the derived normal alive as a
  per-pixel referee, so we never stop computing the thing we are trying to
  retire. Reject as the default.
- **Clamp inversions** (`dot(supplied, derived) <= 0` → use derived) costs
  almost nothing and catches the genuinely broken case — a normal pointing away
  from the geometry it shades. Keep it.
- **Let the mask cover the rest.** The 16% is dominated by depth edges and the
  lamp, which are regions the mask/validity step (§4) already marks. Where the
  model cannot know the geometry, fall back to derived *there and only there*.

The deciding measurement is §0.2: if the 30° disagreements are invisible at the
current weak shading, do nothing more than the inversion clamp. Adding the 30°
gate later is cheap if the lighting is ever strengthened.

---

## 4. The mask — an import-time janitor, not a stored asset

**Verdict: the mask is used once, at import, to clean the depth and to mark
which normal pixels are untrustworthy. It is not stored, for stills or video.**

Why the mask is not stored:

- Its whole job is to fix a defect *before* quantisation and blur. Once the
  depth that is saved is honest and the normals that are saved are honest, a
  render-time mask solves a problem that no longer exists.
- A stored mask is a fourth file with a fourth missing-file failure mode, read
  on the render path, for an artifact nobody looks at.
- For video it is another 1-channel stream to keep in lockstep. The
  `VIDEO-PLAN` rule is "everything hard happens once, at import"; the mask obeys
  it there or not at all.

What it does at import:

1. Decode depth and mask.
2. Establish mask polarity (§0.1). Let `invalid` be the pixels the mask (and, if
   the mask does not cover it, a saturation/outlier heuristic) marks as
   untrustworthy.
3. **Clean the depth**: fill `invalid` from its valid boundary (bounded
   push-pull / dilation), so the lamp does not become a tower. Inpaint rather
   than flatten: a constant plate is still a plate, and a real foreground object
   wrongly marked should sink toward its surroundings, not become a shelf.
4. **Clean the normals**: replace the supplied normal at `invalid` pixels with
   the normal derived from the cleaned depth, so the shipped normal map is
   internally consistent with the shipped depth.
5. Blur in float (unchanged), tessellate, save depth + normal. Discard the
   mask.

The failure mode of ignoring the mask is the lamp blob plus, where the mask is a
validity map, sampling holes and garbage normals as if they were geometry.

**If §0.1 shows the mask is a subject silhouette and not a validity map**, the
mask cannot find the lamp; the depth clean must instead be an independent
outlier guard (a large connected plateau near the depth extreme with a flat
interior), and the mask is used only for the normal-validity step. Either way
the mask is still import-only.

---

## 5. The cost — store the still's normal, never a video's

A normal is 3 channels against depth's 1, so **3× the bytes as RGB, 4× if
padded to RGBA** — and, worse for video, the wrong kind of data for a camera
codec: H.264/HEVC 4:2:0 subsamples two of the three components to half
resolution, which corrupts a vector field. Preserving it needs 4:4:4, which
hardware decoders do not universally offer.

Raw bytes, 720×1366 = 983,520 px:

| artifact | per frame | 60 s @ 30 fps |
|---|---:|---:|
| depth, 1ch | 0.98 MB | **1.77 GB** |
| normal, 3ch | 2.95 MB | **5.31 GB** |
| normal, 4ch (texture) | 3.93 MB | 7.08 GB |
| normal at grid 257², 3ch | 0.20 MB | 357 MB |

A 4-second living-photo loop (120 frames) is 354 MB of full-resolution normal
raw versus 118 MB of depth — and phase 2 already struggles to hold its textures
resident, which is why `VIDEO-PLAN` §3 downscales at import.

**Verdicts:**

- **Stills: store the normal**, at the **render resolution** — the same
  downscale the image and depth get, not full 4K and not the grid. Full
  resolution keeps the detail the predicted normal exists to provide (a normal
  map on coarse geometry is the normal-map idiom, not a bug); grid resolution
  throws that detail away for no meaningful saving on a still. A PNG at render
  size is a few MB beside a photograph.
- **Video: store no normal.** Derive in the fragment shader, as `VIDEO-PLAN` §5
  already plans. Three independent reasons: 3× the bytes; 4:2:0 corruption of a
  vector field; and a *third* decoder to hold in lockstep, when two already
  carry the whole correctness burden. The video's banding is already addressed
  by temporal smoothing plus the float blur before encode.
- **Do not store a half-resolution or octahedral normal for video either.** It
  is still a second stream to sync, and a coarse normal over a full-resolution
  fragment relief is inconsistent by construction.
- **Poster frames are stills** and therefore may carry a full normal; a video
  that pauses is just a still.

---

## 6. Order of work, smallest useful first

Ranked so each step is complete — no field nothing reads (`AGENTS.md`: a state
field with no reader is a bug, not a placeholder).

0. **Establish the two facts (§0).** Server + one photo, no app code.
   *Not shippable; it is the cheapest thing and it gates decisions 3 and 4.*

1. **Mask-clean the depth at import (no schema change).** Smallest *shippable*
   slice, self-contained, and it removes a defect users can see today (the lamp
   blob). Touches only the import pass, not the renderer, not the graph builder.
   Testable as a pure `:core` function over `(depth, mask)`.

2. **Supplied normals, as one vertical slice.** Do not merge a lone
   `normalName`: land storage + graph + renderer reader together.
   - `LibraryItem` field, paired writers, `NormalSource`, tests (`:core`).
   - Graph emits `normal_opengl` alongside `depth` (graph agent).
   - Import writes `withDepthAndNormal` (import path).
   - Renderer uploads and samples the normal (renderer agent), with the
     `uIntensity` correction and the vertical flip.
   - `showingSamePair`/`PairCache` learn the third name; status line gains the
     tell.
   The `:core` normal derivation should be consolidated here if the renderer
   conversion has not already done it, so the still path has one formula.

3. **Consistency clamp/ gate, only if §0.2 showed a visible inversion.** The
   inversion clamp first; the 30° gate only if strengthening the light makes it
   worth it.

4. **Export the normal with the still.** `VIDEO-PLAN` §7 already says the format
   is write-only and therefore pointless without export; a still that carries a
   predicted normal must leave the device carrying it. Not before the feature
   has a reader.

5. **Optional: a bundled normal for the demo.** Gives a server-free first-run
   demonstration, at the cost of a hand-made asset — and the demo's 4-level map
   is the worst photo to judge it on. Low priority.

Hygiene for whoever implements: `scripts/check-parity.mjs` enforces the test
counts and class list in `AGENTS.md`. Adding a test class without updating that
file fails the build, so the plan's tests must be paired with an `AGENTS.md`
edit.

## 7. What this work must not touch

- `ComfyWorkflow` / `ComfyWorkflowTest` — owned by the still-graph conversion.
  This document specifies the output contract it must meet.
- The GLES3 conversion in `ReliefRenderer` / the hosts — owned by the renderer
  conversion. This document specifies the normal interface it must consume.
- No mockups. `.design/` is gitignored and not the source of truth.
- No commit unless asked.

## 8. What I would most like the user to overrule

1. **"Store no normal for video."** It is the most consequential call and the
   one where product intent could differ: a fully normal-mapped video is a real
   thing to want, and it would change the storage maths in §5 and add a third
   lockstep stream. I have recommended against it on bytes, codec colour
   subsampling and sync risk; if the user wants it, the recommendation changes.
2. **"No 30° agreement gate."** I trade a hard consistency guarantee for the
   benefit of removing the banded derivative entirely, on the grounds that the
   current shading is too weak for a 30° error to read. That is a judgement
   about appearance, which `AGENTS.md` says is not measurable by bounds — only
   the device A/B can settle it.
3. **Keeping `Library.VERSION` at 3.** If the project prefers an explicit
   one-time rewrite of every index when the schema grows, bumping to 4 with a
   no-op migration is defensible; I chose not to touch user data for a field
   whose absence is already the correct default.
