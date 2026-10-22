# SPLAT-SIZE — what a fused clip ships as, and the tracked-Gaussian route

Stage 3's first half: the **shipping size** of a 4D clip. Analysis and
documentation only — no Android code was touched, no device was used, and every
build directory under `.design/` was read-only. This file does not replace
`docs/SPLAT-PLAN.md` (the stage tracker) or `docs/SPLAT-MAKE3D.md` (the still
flow); it is the measurement behind Stage 3's two unchecked boxes.

The question is the one `SPLAT-PLAN.md`'s *Open decisions* leaves open: the
hybrid fused clip is **hundreds of MB** (272 MB of dynamic layer alone for 48
frames), and that is the whole reason clips are not shipped today.

**Tool.** `splats4d==0.1.0` (the native Rust CLI, `pip install splats4d`), the
`splat4d` binary. It is a *4D* codec: a time series of antimatter15 `.splat`
frames into one seekable file with per-attribute static/dynamic split,
error-bounded quantization, deadband "hold" tracks and closed GOPs, then
zstd. All encodes below used its defaults (`--pos-mm 2 --color-levels 4
--rot-steps 0 --scale-pct 2 --gop 30`), `--fps 8.53`, and each one
**self-verified every attribute of every splat in every frame** against the
bound (`BOUNDS VERIFIED` in every log).

Artifacts live in `/tmp/opencode/splats4d/` (`make_sets2.py`, `out2/`,
`per_frame/`); the source builds are
`.design/prototype/ab/3d/splatline/clip-4ec04d1d-fused{,-sharp}/`.

---

## 1. The data

Both builds are the same clip (`item-4ec04d1d`), 48 frames at 8.53 source fps,
608×1080. `-fused` is the VGGT lift (soft, 294 px of content);
`-fused-sharp` is the same vote re-emitted from SHARP (608 px of content, 2.4×
denser). Both are **one fused static layer + a per-frame dynamic layer + a
per-frame colour** for the static points.

| | `clip-4ec04d1d-fused` | `clip-4ec04d1d-fused-sharp` |
|---|---|---|
| static layer (`static.splat`) | 150,197 pts · **4.81 MB** | 280,733 pts · **8.98 MB** |
| per-frame colour (`colour/*.bin`) | 48 × 600,788 B · **28.8 MB** | 48 × 1,122,932 B · **53.9 MB** |
| dynamic layer (`dynamic/*.splat`) | 2,531,328 pts · **81.0 MB** | 8,515,584 pts · **272.5 MB** |
| dynamic N per frame | 4,096 … 100,352 (median 77,824) | 51,200 … 288,768 (median 243,712) |
| **current on-disk total** | **114.6 MB** | **335.4 MB** |

The dynamic layer is the train plus the near field the vote calls in front of
the fused surface. It is **the whole of the size problem**: 71 % of the VGGT
build and **81 %** of the SHARP build.

---

## 2. What the format requires, and what of ours qualifies

`docs/FORMAT.md` of splats4D, *Encoding pipeline* step 1, is explicit:

> **v1 requires constant N and index correspondence across frames** (true for
> tracked-gaussian pipelines).

Both halves matter, and our fused builds split cleanly along them:

- **The static layer + its per-frame colour qualifies — completely.**
  `static.splat` is the *same points every frame* (the fusion's whole point),
  so N is constant (150,197 / 280,733) and index *i* is the same surface point
  in every frame. The per-frame `colour/*.bin` is the same N points' RGB. This
  is a genuine coherent 4D capture: geometry, rotation, scale and opacity
  constant, RGB animated.

- **The dynamic layer qualifies for neither half.** Its N varies frame to frame
  (4,096 … 100,352 / 51,200 … 288,768), so the encoder errors out as-is. And
  even after forcing N constant, index *i* in frame *t* is a *different surface
  point* from index *i* in frame *t+1*: each frame is an independent monocular
  reconstruction, not a tracking. That is exactly the assumption the format's
  temporal deltas and deadband holds rest on, and it is false for our dynamic
  data.

So a fused clip is **one qualifying half and one that does not qualify**, and
the two are measured separately below. Forcing the dynamic half in (pad to the
max count, or stride to the min) makes the encoder accept it but does **not**
make the ratio meaningful — a ratio measured on data that violates the format's
assumption is not the ratio our clips would get.

---

## 3. Measured encodings

Four ways of feeding the two builds to `splat4d`, each labelled with what it
does and does not satisfy. `zstd -19 --long=27` on the same raw is the best
generic lossless baseline.

**None of the `splat4d` encodes is lossless**: the codec is error-bounded by
construction (±2 mm position, ±4/255 colour, exact rotation, ±2 % scale), and
every encode verified those bounds exhaustively. "All points kept" below means
no *decimation* — every input record is encoded within those bounds — as
opposed to the stride sets, which drop points outright.

### `-fused` (VGGT)

| set | what | raw | `.splat4d` | ratio | zstd-19-long |
|---|---|---|---|---|---|
| `vggt_lighting` | static layer + per-frame colour — **qualifies** | 230.7 MB | **3.02 MB** | **76.4×** | 6.45 MB (35.8×) |
| `vggt_pad` | dynamic padded to N=100,352 — constant N, **no correspondence**, all-points-kept | 154.1 MB | 12.91 MB | 11.9× | 43.3 MB (3.6×) |
| `vggt_stride` | dynamic strided to N=4,096 — constant N, no correspondence, **lossy** | 6.3 MB | 1.22 MB | 5.2× | — |
| per-frame independent | each dynamic frame its own one-frame `.splat4d` — all-points-kept | 81.0 MB | **11.32 MB** | **7.15×** | — |

### `-fused-sharp` (SHARP)

| set | what | raw | `.splat4d` | ratio | zstd-19-long |
|---|---|---|---|---|---|
| `sharp_lighting` | static layer + per-frame colour — **qualifies** | 431.2 MB | **6.53 MB** | **66.0×** | 14.16 MB (30.5×) |
| `sharp_pad` | dynamic padded to N=288,768 — constant N, **no correspondence**, all-points-kept | 443.5 MB | 98.72 MB | 4.49× | 237.3 MB (1.87×) |
| `sharp_stride` | dynamic strided to N=51,200 — constant N, no correspondence, **lossy** | 78.6 MB | 30.85 MB | 2.55× | — |
| per-frame independent | each dynamic frame its own one-frame `.splat4d` — all-points-kept | 272.5 MB | **81.46 MB** | **3.35×** | — |

**What the format itself decided about the dynamic data.** On `sharp_pad` the
static fractions it found were pos 0.3 %, rot 0.0 %, rgb 0.4 %, alpha 0.4 %,
scale 0.0 %; on `vggt_pad`, pos 2.4 %, rgb 2.4 %, alpha 5.0 %. The codec
correctly sees our dynamic clouds as **almost entirely dynamic** — the
opposite of the coherent half, where it found pos/rot/scale/alpha **100 %
static** and only RGB (24.8 % / 14.4 %) moving.

**Two honest readings of the dynamic numbers.**

- *Against the real dynamic raw* (81.0 / 272.5 MB), the schemes that keep every
  point land at **7.15×** (VGGT) and **3.35×** (SHARP) via per-frame independent
  encoding,
  and the forced-constant-N 4D files at **6.3×** and **2.8×**. Per-frame
  independent beats forcing the 4D temporal model, because the model then has
  no false correspondence to chase.
- *Against the padded raw* the printed ratios are lower (11.9× / 4.49×) because
  the padding inflates the denominator; padding itself costs almost nothing.

The SHARP build's dynamic half compresses about **half as well** as the VGGT
build's (3.35× vs 7.15×), and it is 3.4× larger to begin with. SHARP's
per-frame Gaussians are more anisotropic and less temporally repeatable; VGGT's
softer, coarser cloud is more redundant. The quality build is also the
expensive build.

---

## 4. What a fused clip would actually ship as

Ship = the qualifying half encoded with `splat4d` (static geometry + per-frame
lighting) **plus** the dynamic half. The two halves do not share an N, so the
static + lighting half is **one container** and the dynamic half is either
**48 one-frame containers** (per-frame independent) or **one constant-N
container** (pad/stride).

| | `-fused` (VGGT) | `-fused-sharp` |
|---|---|---|
| static + lighting | 3.02 MB | 6.53 MB |
| dynamic — per-frame independent (all-points-kept) | 11.32 MB | 81.46 MB |
| dynamic — one padded 4D file (all-points-kept) | 12.91 MB | 98.72 MB |
| dynamic — 4D stride to constant N (lossy) | 1.22 MB † | 30.85 MB ‡ |
| **shipped, every dynamic point kept** | **14.3 MB** | **88.0 MB** |
| shipped, padded 4D dynamic | 15.9 MB | 105.3 MB |
| current on-disk | 114.6 MB | 335.4 MB |
| **overall ratio, every point kept** | **8.0×** | **3.8×** |

† the VGGT stride target is N=4,096 — the *smallest* frame — so the busiest
frame keeps 4 % of its points. The number is real but the picture is not.
‡ the SHARP stride target is N=51,200: 78.6 MB of points from 272.5 MB (3.46×
fewer), and the player would show a sparse train.

**The conclusion is unambiguous.** The coherent half is *solved* — 66–76×, and
`splat4d` beats even `zstd -19 --long` by ~2.2× on it. But the dynamic half is
**79 % of the VGGT build's shipped bytes and 93 % of the SHARP build's**
(11.3 of 14.3 MB; 81.5 of 88.0 MB), and it is the half
the format's model cannot touch. Even the best every-point-kept dynamic encoding leaves
the SHARP clip at **88 MB** — still hundreds of times the whole app. `splat4d`
alone does not make a fused clip shippable; it makes the *static background*
free, and that was never the problem.

---

## 5. Tracked Gaussians — the one representation that fixes the dynamic half

The dynamic half is uncooperative for one reason: it is 48 *independent*
reconstructions with no shared identity. The representation that fixes that is
a **tracked (4DGS-style) reconstruction**: one canonical set of Gaussians with
per-frame motion, so N is constant **and index *i* is the same Gaussian in
every frame** — the format's stated assumption, satisfied exactly.

### What a tracked reconstruction is

- **Dynamic 3D Gaussians** (Luiten et al., 3DV 2024): a canonical cloud with
  **persistent colour, opacity and size**; only **position and rotation** move
  per timestep, driven by 3D optical flow with local-rigidity regularisation.
  Three of the five attributes are static *by construction*.
- **4D Gaussian Splatting** (Wu et al., CVPR 2024) and **Deformable 3D
  Gaussians**: a canonical cloud plus a deformation field (MLP) mapping
  `(x, t) → (Δpos, Δrot, Δscale, Δopacity)`; colour via time-varying SH.

Either emits **one N for the whole clip**, and a `.splat` frame per timestep —
the exact input `splat4d` v1 wants.

### What the pipeline change costs

- **Inputs already exist.** VGGT gives the camera poses; MoGe already runs on
  the box and gives metric depth. The clip is already uploaded. The new thing
  is the optimisation, not new capture.
- **Training code must be deployed.** D3DGS or 4DGS (PyTorch + PyTorch3D, and
  tiny-cuda-nn for the deformation MLP). Nothing like it is in
  `comfy-docker/basedir/custom_nodes` today. This is a real deployment, not a
  node.
- **Compute, measured on other people's hardware** (the box is one RTX 4090,
  24 GB):
  - 4D-GS: **~30 min** per scene on one RTX 3090 (HyperNeRF-class; 8 min on
    D-NeRF).
  - Instant4D: **7.2 min** average.
  - Dynamic 3D Gaussians: ~**4 min** for the 10,000-iteration frame-0 fit plus
    the motion optimisation; a full 150-frame run is tens of minutes.
  - **Our clips are 48 frames, 608×1080, single camera** — shorter than these
    benchmarks, so a plausible **5–20 min per clip** on the 4090. This is an
    **estimate**; the benchmarks are multi-view or moving-camera and our regime
    (below) is harder.
  - VRAM 10–24 GB. `llama-server` holds ~20 GB of the 24 GB and must be freed
    first (the orchestrator's stop endpoint), exactly as for any GPU render.
- **New emit step.** Canonical Gaussians + per-frame deformation → 48
  constant-N `.splat` frames → `splat4d encode`. Mechanical, but new.
- **It replaces** the per-frame SHARP/VGGT reconstruction, the cross-frame
  vote, and the fused/dynamic emit.

### What it buys — size

The format's own published benchmark is a real tracked capture (the *juggle*
sequence, Dynamic 3D Gaussians, 150 frames × 336,568 splats, 1,615 MB raw):
**19.4× at defaults**, **39.5× coarse + colour-denoise**. Applying those ratios
to one tracked cloud of N Gaussians over our 48 frames
(raw = `48 × N × 32 B`):

| canonical N | raw | @19.4× | @39.5× |
|---|---|---|---|
| 100k | 153.6 MB | 7.9 MB | 3.9 MB |
| 150k (the phone's measured render budget) | 230.4 MB | 11.9 MB | 5.8 MB |
| 300k | 460.8 MB | 23.8 MB | 11.7 MB |
| 1M | 1,536 MB | 79.2 MB | 38.9 MB |

Compare the current SHARP fused clip: dynamic-only shipped 81.5–98.7 MB, whole
clip 88 MB. **At N ≤ 300k the whole clip ships at roughly 4–24 MB — about 4–23×
smaller than the fused hybrid — and the dynamic half's ratio goes from 3.35× to
19–39×, a 6–12× improvement on the term that is 93 % of the bytes.**

### What it buys — count

A tracked clip's rendered per-frame count is **N, constant**, instead of
`static (280k) + dynamic (up to 288k) = up to 568k`. That is roughly half the
splats, and the number becomes a *budget you choose* rather than an artefact of
the reconstruction. (Even so, the phone's measured ceiling is 100–150k at
50–60 fps per `SPLAT-PLAN.md`, so a 300k tracked cloud still needs the Stage 3
LOD ladder.)

### The count is the deciding variable, and that is the honest catch

4DGS storage is often quoted at **~2 GB per scene** (millions of Gaussians).
An **unpruned** tracked reconstruction does **not** win: at N=1M it ships at
39–79 MB, no better than the fused dynamic half. The size win comes from
combining the representation (which makes the 19–39× ratios reachable) with a
**count budget** (which sets the raw). Tracked Gaussians fix the *ratio*;
LOD/pruning fixes the *count*. Both are needed, and only the ratio is intrinsic
to the representation.

---

## 6. Honest limits

1. **The 19.4× / 39.5× are not measured on our data.** They are splats4D's
   published numbers on a multi-view Dynamic 3D Gaussians capture. What *is*
   measured here is the fused builds' own 66–76× (coherent) and 3.35–7.15×
   (dynamic). A tracked reconstruction of our clip would land somewhere in
   between — the format's own number is the conservative anchor, and the exact
   ratio cannot be known without training it.
2. **Our regime is the hard one for 4DGS.** The benchmarks are multi-view or
   have a moving camera; ours is a **locked-off monocular** clip with a moving
   object. Monocular dynamic reconstruction is research-grade (Mono-DyGS and
   similar), and the scene's own depth supervision comes from SHARP/MoGe, not
   from a second view.
3. **The train is motion-blurred in the source.** No reconstruction recovers
   detail that was never captured; a tracked train is a tracked blurred train.
4. **The count must be budgeted or the win evaporates** (the N=1M row above).
   The quality at a 150k budget for a scene whose static layer alone is 280k
   points is an open question — the phone budget and the reconstruction quality
   are in tension.
5. **The dynamic ratios have a build dependence** (SHARP 3.35× vs VGGT 7.15×)
   that tracks gaussian anisotropy and redundancy, not the scene. A tracked
   cloud's own density/anisotropy will move its ratio the same way.
6. **`splat4d`'s dynamic encoding of our data is not the ratio our clips
   *would* get** if they were tracked — that is the whole point of §5 — and the
   ratios in §3 are labelled accordingly. A number measured on data that
   violates the format's assumptions is not a shipping number.

---

## 7. Reproduce

```
cd /tmp/opencode/splats4d
uv venv .venv && . .venv/bin/activate && uv pip install splats4d
python3 make_sets2.py                       # builds sets2/{vggt,sharp}_{lighting,pad,stride}
for s in vggt_lighting vggt_pad vggt_stride sharp_lighting sharp_pad sharp_stride; do
  splat4d encode -i sets2/$s -o out2/$s.splat4d --fps 8.53 --gop 30 \
      --report out2/$s.report.json
done
# per-frame independent dynamic: encode each dynamic/frame_%04d.splat alone
splat4d info out2/sharp_lighting.splat4d    # header + section sizes
```

Sources (read-only): `.design/prototype/ab/3d/splatline/clip-4ec04d1d-fused/`
and `.../clip-4ec04d1d-fused-sharp/`. `make_sets2.py` reads their
`static.splat`, `colour/frame_%04d.bin` and `dynamic/frame_%04d.splat` and
writes only under `/tmp/opencode/`.
