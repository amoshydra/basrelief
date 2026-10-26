# basrelief_depth — ComfyUI nodes for Basrelief

The server half of the app's depth/3D work. Three nodes, all `OUTPUT_NODE`s that
write files into ComfyUI's output directory (not tensors anything downstream
consumes):

| class | what |
|---|---|
| `MoGeVideoStream` | a video range → depth + normal clips; the app's clip import |
| `SharpStill` | one still → a budgeted `.splat`; the app's "Make 3D" |
| `MoGeGeometryNPZ` | prototype: dump a MoGe geometry packet to `.npz` |

The app never invents these names: it derives every output filename from the
`tag` it submits, so it can fetch a run it never saw a response for. The rule is
the same one the app runs (`ComfyWorkflow.streamTag`): keep the **ASCII** characters
`[A-Za-z0-9_-]`, then the first 48 — nothing is trimmed or normalised. Item ids are
UUIDs and survive unchanged. The rule lives once, in `contract.py`, and both sides
are tested against `contract/tag/cases.json`; that fixture's README explains why
the alphabet is ASCII rather than Python's `isalnum()`.

---

## SharpStill

Turns one image into a 3D Gaussian cloud in the 32 B/splat format the phone's
renderer reads (`SplatCloud` in `:core`). Apple's SHARP does the reconstruction;
the node budgets the result.

### The graph

```
LoadImage(image=<uploaded name>)
  └─▶ SharpStill(image, internal_size, budget, tag, focal_px, checkpoint, keep_loaded)
        ──▶ basrelief_<tag>.splat        (the cloud)
            basrelief_<tag>.splat.json   (the run's sidecar)
```

Two nodes, one wire. `image` is `["1", 0]` — `LoadImage`'s first output.

```json
{
  "1": {"class_type": "LoadImage",
        "inputs": {"image": "test_still_00001_.png"}},
  "2": {"class_type": "SharpStill",
        "inputs": {
          "image": ["1", 0],
          "internal_size": 1536,
          "budget": 150000,
          "tag": "item-teststill",
          "focal_px": 0.0,
          "checkpoint": "",
          "keep_loaded": false
        }}
}
```

Submit it to `POST /prompt` (`{"prompt": {…}, "client_id": "…"}`) through the
gpu-orchestrator proxy at `http://127.0.0.1:42068` — the proxy unloads
llama-server before the render, which the card needs (SHARP peaks at 7.4 GB and
the node also holds its own ~2.8 GB weights while it runs).

### Inputs

| input | type | default | meaning |
|---|---|---|---|
| `image` | `IMAGE` | — | the still. `LoadImage`'s tensor; only frame 0 is used |
| `internal_size` | `INT` | `1536` | SHARP's inference size. SHARP accepts only `384 + 288k`; anything else is snapped to the nearest and logged |
| `budget` | `INT` | `150000` | target splat count. **0 = uncapped** |
| `tag` | `STRING` | `""` | the sanitised item id the filenames are derived from |
| `focal_px` | `FLOAT` | `0.0` | the lens in pixels of the uploaded frame; `0` = SHARP's 30 mm fallback (see below) |
| `checkpoint` | `STRING` | `""` | explicit SHARP `.pt` path; empty = the node's candidates, then the torch hub cache |
| `keep_loaded` | `BOOLEAN` | `false` | keep the predictor resident between runs, or hand its VRAM back at the end |

### Outputs

| name | where | value |
|---|---|---|
| `splat` | `ui` + `/history` | `basrelief_<tag>.splat` |
| `sidecar` | `ui` + `/history` | `basrelief_<tag>.splat.json` |
| `splats` | `ui` + `result` | the record count written, **including** padding |
| `padding` | `ui` + `result` | zero records appended to reach a whole 2048 block |
| `focal` | `ui` + `result` | the lens in pixels (the same number the sidecar carries) |

### The filenames, and the fetch

```
basrelief_<tag>.splat          the cloud      GET /view?filename=…&type=output
basrelief_<tag>.splat.json     the sidecar    GET /view?filename=…&type=output
```

Both are deterministic, so the app addresses them without a response. The app
then renames the cloud into its own per-item scheme
(`LibraryItem.splatFileName(id)` = `item-<id>.splat`), exactly as it renames the
clip files.

The sidecar is the run's own state, read the same way:

```json
{"state": "done", "splat": "basrelief_<tag>.splat",
 "splats": 149504, "selected": 149504, "padding": 0, "focal": 1411,
 "internal_size": 1536, "budget": 150000, "width": 1216, "height": 1632,
 "tag": "<tag>"}
```

`splats` is the record count (`selected + padding`); `selected` is the count the
re-sample kept; `focal` is the number the renderer wants.

### The budget

`budget` is a **ceiling on the file**, and the padding records count: they are
real records the phone uploads and sorts. The target is therefore rounded down to
a whole 2048-splat block, so the written count can never exceed the budget (a
budget below one block still writes one block — a cloud cannot be padded to
nothing). Measured: `budget 150000 → 149504` (0.3% under), `300000 → 299008`.

It is enforced as a **uniform (u,v) cell re-sample, never a `[::step]`**. A 1-D
stride over the raster order is what the earlier video build did, and it is
wrong: measured on that build the kept points sat ~6.3 px apart in u against
~0.7 px in v, and rendered as vertical streaks through the whole picture. The
node bins each visible point by its projected image cell at the frame's lens and
keeps the **highest-opacity** point per cell, which is uniform in image space by
construction. On the test still at 150k the stride renders at **30.15/255**
mean per-channel error against the photograph; the uniform re-sample at
**6.62/255** — a 4.5× better reconstruction, and the stride's black holes (1.6%
of the frame) are gone. `_choose_cell_px` bisects for the finest cell that still
fits the budget, so the same code serves any target.

`budget = 0` is uncapped and **verbatim**: every gaussian SHARP predicted, with
no alpha or z filter, byte-identical to the approved converter's output (below).

### What the record is, and why there is no PLY

The `.splat` record is 32 bytes: 3 float32 position, 3 float32 scale, 4 uint8
sRGB colour, 4 uint8 rotation. The approved pipeline wrote a PLY with
`save_ply`, stripped it to a vertex-only PLY, and converted with `ply2splat.py`.
This node skips the file and builds the record directly from SHARP's tensors,
through the **same round trip**: colour linearRGB→sRGB then SH degree 0, scale
and opacity through their log/logit storage, rotation normalised and quantised —
the logits computed in torch, where `save_ply` computes them, because numpy's
`log` differs by one ULP and exp() carries that into the scale.

Verified: with `save_ply → strip_ply → ply2splat.py` run beside it in the same
torch version, the node's uncapped output is **0 differing bytes** out of
37,748,736. (Against the checked-in `teststill.splat`, built on a box with a
different torch — 2.14.1+cu130 against this container's 2.11.0+cu128 — the
positions drift by ~7e-5 relative, which is a runtime difference, not a logic
one; the count and the peak VRAM are exact.)

### The lens

`sharp.utils.io.load_rgb` reads `FocalLengthIn35mmFilm` from EXIF and falls back
to 30 mm. ComfyUI's `LoadImage` hands the node a tensor with the EXIF gone, so
the node reproduces the fallback:

```
f_px = 30 * hypot(W, H) / hypot(36, 24)
```

For the 1216×1632 still that is **1411.2 px** — the same lens the approved
uncapped cloud used, and the same number the phone's renderer bakes for its demo
cloud. Only the budgeted re-sample reads it (it is the lens the (u,v) grid is
uniform in); the cloud's own numbers do not depend on it. A caller that knows a
better focal passes it in `focal_px`.

### The model

`_predict_sharp` reproduces Splatline's still backend (`predict_sharp_image` at
`internal_size 1536`) verbatim — it is a copy, not an import, because that
helper lives in Splatline's `utils/splat_models.py`, not in the `sharp` package,
and this node must not depend on that repo being present.

Measured in the container on the test still: **2.5–2.9 s** for the forward
pass, **7.41 GB** peak VRAM, **1,179,648** gaussians uncapped (a function of
`internal_size` alone, not of the input's size or aspect). End to end through
`/prompt` — model load included — **~9–15 s**; the predictor is released at the
end unless `keep_loaded`.

---

## Running it in this box's ComfyUI container

The node runs **inside ComfyUI's process**, so SHARP's code and its weights have
to be reachable there. They were not; this is what was done.

The container (`comfyui-nvidia`) already had torch 2.11.0+cu128, torchvision,
timm, scipy, matplotlib, gsplat and opencv. It was missing the `sharp` package
and `plyfile`. Installed into ComfyUI's own venv (`/comfy/mnt/venv`, a bind
mount, so it survives a container restart):

```bash
podman exec -u root comfyui-nvidia /comfy/mnt/venv/bin/python -m pip install \
    plyfile pillow-heif
podman exec -u root comfyui-nvidia /comfy/mnt/venv/bin/python -m pip install \
    --no-deps \
    "git+https://github.com/apple-aiml-research/ml-sharp.git@aed6527499ef91cba3b54c18d49a870f25947190"
```

The commit is the one Splatline's venv had (`sharp 0.1`), so the model code is
exactly what produced the reference. `--no-deps` because the deps are already
there; `gsplat` is not needed by the inference path and was not installed by
this node's dependency (`sharp.cli.render` imports it, `sharp.models` does not).

The weights (`sharp_2572gikvuh.pt`, 2.8 GB) are copied onto the **bind mount**
so they outlive the container, and the node looks for them there:

```
/basedir/tools/sharp/sharp_2572gikvuh.pt
```

(`/comfy/mnt/models/sharp/…` is checked first; an explicit `checkpoint` input
wins over both; if none is present the node falls back to
`torch.hub.load_state_dict_from_url`, which downloads once into the torch hub
cache.)

**A new custom node is only registered at ComfyUI startup**, so a restart is
required after adding or editing one. ComfyUI-Manager's reboot route worked:

```bash
curl -X POST http://127.0.0.1:42068/api/manager/reboot
```

---

## MoGeVideoStream

The clip import path; see the module docstring in `nodes.py` for its measured
memory story. Outputs `basrelief_<tag>_depth.mp4`, `basrelief_<tag>_normal.mp4`,
`basrelief_<tag>_color.mp4` (only when the source exceeded the playback caps) and
the sidecar `basrelief_<tag>.progress.json`, all fetched through `/view`.

## MoGeGeometryNPZ

Prototype only: dumps a `MOGE_GEOMETRY` packet to `basrelief_<tag>.npz`. Not part
of the import path.

---

## Licences and credits

The node is GPL-3.0, like the rest of the repository (see `LICENSE`). It does not
bundle the models it calls:

- **Apple SHARP** — the code is under Apple's software licence, and the
  checkpoint is under the Apple Machine Learning Research Model License. That
  model licence is limited to non-commercial research use. Read
  [LICENSE](https://github.com/apple-aiml-research/ml-sharp/blob/main/LICENSE)
  and
  [LICENSE_MODEL](https://github.com/apple-aiml-research/ml-sharp/blob/main/LICENSE_MODEL)
  before using `SharpStill`.
- **MoGe** — MIT, except for its DINOv2 code, which is Apache-2.0. It is
  installed separately in ComfyUI.
- **ComfyUI** — GPL-3.0; the node imports its runtime and reproduces the MoGe
  render maths used by `MoGeVideoStream`.
- **Splatline** — MIT. `_predict_sharp` reproduces its
  `predict_sharp_image`. The required notice travels with this package in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- The 32-byte `.splat` container follows the layout used by
  [antimatter15/splat](https://github.com/antimatter15/splat).

The repository-wide credits, including the 3D Gaussian Splatting paper and
gsplat, are in [README.md#credits](../../../README.md#credits).
