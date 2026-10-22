# SPLAT-LADDER — the demo cloud at seven densities

The **data half** of the quality/fps question: the same bale reconstruction at seven
densities, so a level pick can be made from measurements rather than from SHARP's
1.18 M and the demo's 150 k as two unrelated points.

One source PLY — the bale still, `.design/prototype/ab/3d/splatline/sharp/bale.ply`,
1,179,648 SHARP Gaussians — re-sampled by the **same script** at seven (u,v) cell
sizes. Same lens (`f = 2036.6 px` at 1440×2560), same highest-opacity-point-per-cell
pick, same per-splat coverage compensation. **Only the cell size changes**, so the
rungs are one reconstruction at different densities, not seven different ones.

Script: `scripts/make-demo-splat.py` (branch `splat-stage0`, unchanged).
Generated with, per rung:

```
python3 scripts/make-demo-splat.py \
  .design/prototype/ab/3d/splatline/sharp/bale.ply \
  demo-<tag>.splat --cell-px <cell> --report
```

## The ladder

`vs full` / `vs photo` are mean per-channel error (0–255) over the opaque pixels of
the **source view**; `vs full` is against the 0.25 px rung's own render, `vs photo`
against `bale.jpg` (the SHARP input still). `comp` is the per-splat compensation
factor (p10/p50/p90/p99); it falls to 1.0 as the cell approaches the cloud's own
spacing, because there is no longer a gap to close.

| cell px | file | kept | padded | MB | comp mean (p10/50/90/99) | vs full MAE | vs full PSNR | vs photo MAE | exp. fps |
|---|---:|---:|---:|---:|---|---:|---:|---:|---:|
| 5.6 | `demo-c56.splat` | 149,955 | 151,552 | 4.85 | 1.68 (1.00/1.10/3.28/4.61) | 5.44 | 26.9 | 6.11 | ~30 |
| 4.0 | `demo-c40.splat` | 267,088 | 268,288 | 8.59 | 1.54 (1.00/1.13/2.76/3.54) | 4.38 | 28.3 | 5.32 | ~27 |
| 3.0 | `demo-c30.splat` | 434,055 | 434,176 | 13.89 | 1.41 (1.00/1.12/2.27/2.87) | 3.23 | 30.6 | 4.33 | ~23 |
| 2.0 | `demo-c20.splat` | 741,404 | 743,424 | 23.79 | 1.20 (1.00/1.00/1.65/2.08) | 1.69 | 35.9 | 2.93 | ~18 |
| 1.5 | `demo-c15.splat` | 913,711 | 915,456 | 29.29 | 1.06 (1.00/1.00/1.27/1.58) | 0.82 | 41.9 | 2.13 | ~15–16 |
| 1.0 | `demo-c10.splat` | 1,051,182 | 1,052,672 | 33.69 | 1.00 (1.00/1.00/1.00/1.06) | 0.23 | 49.6 | 1.74 | ~13 |
| 0.25 | `demo-full.splat` | 1,169,318 | 1,169,408 | 37.42 | 1.00 (1.00/1.00/1.00/1.00) | — | — | 1.75 | ~10–13 |

`demo-full.splat` is **SHARP's own density** (99.2 % of the 1,178,859 visible
points, factor exactly 1.0). `demo-c56.splat` is byte-identical in count to the
shipped demo (149,955 + 2,048 pad), and regenerating it with this script
reproduces the committed numbers exactly.

Machine-readable copy: [`SPLAT-LADDER.json`](SPLAT-LADDER.json).

## Off-device renders (gsplat)

Each rung rendered with the **same** renderer/camera/lens as the dense build's own
renders (`render_dense.load_splat` + `gsplat.rendering.rasterization`, classic mode,
`sh_degree=0`, source view `V=identity`, `K` at `f=2036.6`, 1440×2560):

- `renders/<stem>_source.png` — the photograph view, one per rung
- `renders/<stem>_orbit20.png` — a 20° orbit, to see the reveal
- `source_sheet.png` / `crop_sheet.png` / `hair_three_2x.png` — contact sheets
  (densest → sparsest)

The source view is the photograph by construction, so the rungs differ only by the
reconstruction's own density. At 1:1 the 150 k rung is visibly soft (the face, the
dress strings, the grass), 915 k is very close to full, and 1.05 M+ is
indistinguishable from SHARP's 1.17 M in the still.

## Honest notes

- **The brief's "1.5 px ≈ 1.18 M" is not what the grid does.** A uniform (u,v) cell
  keeps one point per occupied cell, so it **dedups**: at 1.5 px, 913,711 of the
  1,178,859 visible points survive (77.5 %), because the cloud's own projected
  spacing is finer than 1.5 px in the dense regions. SHARP's full density needs
  cell **0.25 px** (1,169,318 kept, 37.42 MB) or the un-deduped PLY. The 1.5 px rung
  is still a useful rung — it is the last one with any compensation left (mean 1.06)
  — but it is **915 k, not 1.18 M**.
- **Only the two endpoints' fps are device measurements.** ~30 fps at 151,552 and
  ~10–13 fps at 1.18 M are from the spike/Stage-0 runs on a Pixel 7 Pro
  (`docs/SPLAT-PLAN.md`); 358 k ≈ 24 fps is the brief's middle anchor. The middle
  rungs' fps are **linear interpolation in count between those anchors**, not
  measured on the phone, and the real value moves with culling, render scale, the
  sort cost and the build (release + `cmd package compile -m speed -f`).
- **The fps cost is fill, not count.** The renderer is fill-bound: the per-splat
  compensation deliberately leaves an already-big splat alone, so the GPU cost
  tracks the total covered area, which is why the 150 k cloud is ~30 fps rather
  than the 50–60 fps the smaller-splat clouds reach at the same count. Every rung
  here covers the same subject at ~100 % (the source view is 0 % black at all seven
  densities), so the cost ladder is close to the count ladder — but a rung whose
  compensation is still >1 pays extra fill per splat on top of its count.
- **These files are not wired into the app.** The level pick is a separate step; no
  Android code was touched. The app still reads its one debug filename.

## Where they went

Pushed to the phone's app external files dir (nothing deleted; the other agent's
`scene1180k.splat`, `static150k.splat`, `dynamic98k.splat`, `fused/`, `unfused*/`
are untouched):

```
adb push demo-<tag>.splat /sdcard/Android/data/com.amoshydra.basrelief/files/
```

`demo-c56`, `demo-c40`, `demo-c30`, `demo-c20`, `demo-c15`, `demo-c10`,
`demo-full` — 151.9 MB total, 27 GB still free on the device.
