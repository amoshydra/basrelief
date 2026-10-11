#!/usr/bin/env python3
"""SHARP still PLY -> `.splat` with a UNIFORM (u,v) re-sample and density compensation.

WHY THIS EXISTS. A one-dimensional stride over SHARP's storage order looks like a
uniform subsample and is not one: the kept points are unevenly spaced in image
space. Measured on the bale still at SHARP's own 2036.6 px lens, stride 8 leaves
neighbours ~6.3 px apart in u and ~0.7 px in v -- a columnar cloud -- and a stride
carries no scale compensation, so the gaps never close. Rendered, that is vertical
striping and holes (the same defect the hybrid's `resample_sharp.py` fixed for the
train clip, and its measured numbers are the reason this is a grid and not a
stride).

WHAT THIS DOES. Bin the still's points into a grid of `--cell-px` cells in (u,v) at
the still's own lens -- the same logic as the hybrid's `resample_sharp.py` -- and
keep the HIGHEST-OPACITY point per occupied cell. Uniform in image space by
construction. Then compensate the scale: a kept splat stands in for its whole cell,
so if its own screen footprint is smaller than the cell it is grown until it covers
it (`--comp coverage`, the default). That is `sqrt(cell multiplier)` PER SPLAT,
where the multiplier is the cell's area over the splat's own footprint -- which is
what keeps the fill (and the phone's GPU) down, because a single global factor
(`--comp global`, the shipped stride cap's `sqrt(step)`) inflates the big near
splats by the same amount as the small far ones. Measured on the bale at 150 k, the
global `sqrt(visible/kept) = 2.80` reaches 100 % coverage at ~110 ms GPU (~10 fps),
while the per-splat rule reaches it at ~40 ms (~31 fps). The margin exists because
the renderer's alpha falls off as `exp(-4.5 d^2/R^2)`, so a splat whose 3-sigma
extent exactly reaches the cell corner still leaves its opaque core short.

The PLY is in SHARP's own camera frame (x right, y down, z forward, principal point
at the image centre), so no pose work is needed -- this is a single still, not a
VGGT clip.

Usage:
  make-demo-splat.py IN.ply OUT.splat --cell-px 4.0 [--report]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

SH_C0 = 0.28209479177387814
PAD_TO = 2048


def read_ply_vertex(path: Path):
    """Return (N, 14) float32 rows in the standard 3DGS attribute order."""
    with open(path, "rb") as f:
        if f.readline().strip() != b"ply":
            raise SystemExit("not a ply")
        fmt = f.readline().strip().decode()
        if fmt != "format binary_little_endian 1.0":
            raise SystemExit("unsupported: " + fmt)
        n, props, in_vertex = None, [], False
        while True:
            line = f.readline()
            if not line:
                raise SystemExit("no end_header")
            t = line.decode().strip().split()
            if not t:
                continue
            if t[0] == "element":
                in_vertex = (t[1] == "vertex")
                if in_vertex:
                    n = int(t[2])
            elif t[0] == "property" and in_vertex and t[1] == "float":
                props.append(t[2])
            elif t[0] == "end_header":
                break
        if n is None:
            raise SystemExit("no vertex element")
        raw = np.fromfile(f, dtype="<f4", count=n * len(props))
    v = raw.reshape(n, len(props))
    col = {name: i for i, name in enumerate(props)}
    order = ["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity",
             "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
    return v[:, [col[k] for k in order]]


def convert(src: Path, dst: Path, cell_px: float, f_px: float, src_w: float,
            src_h: float, alpha_min: float, scale_factor: float | None,
            pad_to: int, report: bool, pick: str = "opacity",
            comp: str = "coverage", cov_margin: float = 1.0) -> dict:
    v = read_ply_vertex(src)
    n_all = v.shape[0]

    pos = v[:, 0:3].astype(np.float64)
    opacity = 1.0 / (1.0 + np.exp(-v[:, 6]))
    scale = np.exp(v[:, 7:10]).astype(np.float64)
    rgb = np.clip(0.5 + SH_C0 * v[:, 3:6], 0.0, 1.0)
    q = v[:, 10:14].astype(np.float64)

    vis = (opacity > alpha_min) & (pos[:, 2] > 0.05)
    z = pos[:, 2]
    u = f_px * pos[:, 0] / np.maximum(z, 1e-6) + src_w / 2
    vv = f_px * pos[:, 1] / np.maximum(z, 1e-6) + src_h / 2
    i = np.floor(u / cell_px).astype(np.int64)
    j = np.floor(vv / cell_px).astype(np.int64)
    key = (i + 8192) * 32768 + (j + 8192)

    cand = np.flatnonzero(vis)
    if pick == "center":
        # The point nearest the cell centre, so the kept splat is a typical one:
        # picking the highest-opacity point biases toward SHARP's big gaussians
        # (opacity correlates with scale), which inflates the fill and the GPU
        # cost for the same coverage.
        cu = (np.floor(u / cell_px) + 0.5) * cell_px
        cv = (np.floor(vv / cell_px) + 0.5) * cell_px
        dist2 = (u - cu) ** 2 + (vv - cv) ** 2
        o = np.lexsort((dist2[cand], key[cand]))
    else:
        o = np.lexsort((-opacity[cand], key[cand]))   # by cell, best opacity first
    cand = cand[o]
    kk = key[cand]
    first = np.concatenate([[True], kk[1:] != kk[:-1]])
    sel = cand[first]                                  # one point per occupied cell

    n_vis = int(vis.sum())
    n = len(sel)
    if scale_factor is not None:
        factor = np.full(n, scale_factor)
    elif comp == "coverage":
        # Per-splat coverage: a kept splat stands in for its whole cell, so if its
        # own screen footprint is smaller than the cell it must grow until it
        # covers it (radius to the cell corner = cell_px/sqrt(2); the quad's
        # extent is 3 sigma). A splat already big enough is left alone -- which is
        # what keeps the fill (and the GPU) down, because a single global factor
        # inflates the big near splats by the same amount as the small far ones.
        gm = np.cbrt(scale[sel, 0] * scale[sel, 1] * scale[sel, 2])
        r_screen = gm * f_px / np.maximum(z[sel], 1e-6)          # 1 sigma, px
        need = cov_margin * cell_px / (np.sqrt(2.0) * 3.0)       # 3 sigma covers the cell
        factor = np.maximum(1.0, need / np.maximum(r_screen, 1e-6))
    else:
        # One kept splat now stands in for n_vis/n of them, so its footprint must
        # grow by the square root of that ratio (area ~ scale^2) to keep coverage.
        factor = np.full(n, float(np.sqrt(n_vis / max(n, 1))))
    scale_factor = float(np.mean(factor))

    rec = np.empty(n, dtype=[("pos", "<f4", 3), ("scl", "<f4", 3),
                             ("col", "u1", 4), ("rot", "u1", 4)])
    rec["pos"] = pos[sel].astype(np.float32)
    rec["scl"] = (scale[sel] * factor[:, None]).astype(np.float32)
    rgba = np.empty((n, 4), np.uint8)
    rgba[:, :3] = np.rint(rgb[sel] * 255).astype(np.uint8)
    rgba[:, 3] = np.rint(np.clip(opacity[sel], 0, 1) * 255).astype(np.uint8)
    rec["col"] = rgba
    qq = q[sel]
    qq = qq / np.maximum(np.linalg.norm(qq, axis=1, keepdims=True), 1e-12)
    rec["rot"] = np.rint(np.clip(qq * 128 + 128, 0, 255)).astype(np.uint8)

    pad = (-n) % pad_to
    dst.parent.mkdir(parents=True, exist_ok=True)
    with open(dst, "wb") as fh:
        rec.tofile(fh)
        if pad:
            fh.write(b"\x00" * (pad * 32))

    rep = {
        "ply": src.name, "splat": dst.name,
        "n_all": n_all, "n_visible": n_vis, "n_kept": n, "n_padded": n + pad,
        "cell_px": cell_px, "f_px": round(f_px, 2), "src_hw": [src_w, src_h],
        "alpha_min": alpha_min, "scale_factor": round(scale_factor, 4),
        "factor_pct": [round(float(np.percentile(factor, p)), 3) for p in (10, 50, 90, 99)],
        "bytes": int((n + pad) * 32),
    }
    if report:
        # The columnar check: the grid's spacing is cell_px by construction, so
        # compare the cell centres' nearest-neighbour u and v gaps.
        rep["occupied_cells"] = int(len(np.unique(key[cand])))
        rep["alpha_pct"] = [round(float(np.percentile(opacity, p)), 4)
                            for p in (1, 10, 50, 90, 99)]
        print(json.dumps(rep, indent=1))
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--cell-px", type=float, default=4.0,
                    help="(u,v) bin size at --f-px")
    ap.add_argument("--f-px", type=float, default=2036.6,
                    help="SHARP's lens in px at --src-w: 30 mm on 36x24 for "
                         "hypot(src_w, src_h)")
    ap.add_argument("--src-w", type=float, default=1440.0)
    ap.add_argument("--src-h", type=float, default=2560.0)
    ap.add_argument("--alpha-min", type=float, default=0.004)
    ap.add_argument("--scale-factor", type=float, default=None,
                    help="override the scale compensation (default sqrt(visible/kept))")
    ap.add_argument("--pad-to", type=int, default=PAD_TO)
    ap.add_argument("--pick", choices=["opacity", "center"], default="opacity",
                    help="which point to keep per occupied cell")
    ap.add_argument("--comp", choices=["coverage", "global"], default="coverage",
                    help="scale compensation: per-splat coverage (default) or the "
                         "global sqrt(visible/kept)")
    ap.add_argument("--cov-margin", type=float, default=2.5,
                    help="with --comp coverage: how far past the cell corner a kept "
                         "splat's 3-sigma extent is grown (the alpha falloff means a "
                         "3-sigma disc's opaque core is smaller than its extent, so "
                         "pure geometric coverage still speckles)")
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    rep = convert(Path(a.src), Path(a.dst), a.cell_px, a.f_px, a.src_w, a.src_h,
                  a.alpha_min, a.scale_factor, a.pad_to, a.report, a.pick, a.comp,
                  a.cov_margin)
    if not a.report:
        print(f"{rep['ply']} -> {rep['splat']}: {rep['n_all']} -> "
              f"{rep['n_kept']} (+{rep['n_padded'] - rep['n_kept']} pad), "
              f"cell {rep['cell_px']} px, scale x{rep['scale_factor']} "
              f"({rep['bytes']/1e6:.2f} MB)")


if __name__ == "__main__":
    main()
