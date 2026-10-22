"""Basrelief video-depth node: stream a video through MoGe with bounded memory.

Why this node exists
--------------------
ComfyUI materializes every tensor a prompt touches. `VHS_LoadVideo` turns a whole
range into one IMAGE batch, and `MoGeInference.batch_size` bounds the forward pass
but not the DATA (`batch_size` chunks the inference; the input batch and the
per-frame geometry outputs stay live for the whole range). Measured on this box:
~110 MB per 1920x1080 frame, so a 174-frame chunk needed ~19.5 GiB and died on the
server's 16 GiB PyTorch cap while a 132-frame chunk fit.

This node takes the video PATH instead — the same string `VHS_LoadVideoPath` takes —
and owns the loop itself: decode a window, infer, render, encode the frames straight
out. Peak memory is the model plus one window, so length and resolution stop
mattering. A 4K ten-minute video is the same code path as a 720p five-second one.

Decode and encode go through the ffmpeg CLI (PyAV is not in this image); the model
call is the core `MoGeModel.infer`, so weights, caching and device management stay
ComfyUI's (`LoadMoGeModel` feeds this node the same `MOGE_MODEL` the core nodes use).

The render math is deliberately a COPY of `comfy_extras/nodes_moge.py`'s two private
helpers rather than an import: they are 20 lines of arithmetic and importing private
helpers from a file upstream rewrites on its own schedule is how a node breaks on
someone else's release. `moge_model.infer` is the one upstream surface this relies on.

The client contract: outputs are `basrelief_<tag>_depth.mp4`, `basrelief_<tag>_normal.mp4`,
`basrelief_<tag>_color.mp4` and the sidecar `basrelief_<tag>.progress.json` (all
fetchable through `/view`); the sidecar holds `{done, total, state, src_frames,
src_fps, fps_out, color, focal}` and is rewritten every window; the run's UI payload in
`/history` names the same files. **`color` is absent/null when the source already
fit the playback caps** -- then the app's own remux is the colour and no generation
is lost. **`focal` is the camera focal length in PIXELS of the uploaded frame**,
fitted once from the first window's point map (see `_fit_focal`), and 0 when the
fit could not be made: the app reads 0 as "unknown" and falls back to a 50-degree
default rather than drawing nothing. The generated tracks are always capped
(`target_height` from the sheet chip, `max_long_side` for playback, the smaller
wins), because measured, a 1440x2560@50 source could not be held even by the
colour stream alone on a Pixel 7 Pro.

`fps_cap` (default 30) caps the rate the clips are written at, never above the
source. Output frame k is source frame `round_half_up(k * src/out)` -- same
timeline, fewer samples.
"""

import json
import math
import os
import subprocess
import time

import numpy as np
import torch

import comfy.model_management
import comfy.utils
import folder_paths


# --------------------------------------------------------------- render math (copied)

def _normals_from_points(points: torch.Tensor) -> torch.Tensor:
    """Camera-space surface normals from a (B, H, W, 3) point map (v1 fallback)."""
    finite = torch.isfinite(points).all(dim=-1)
    pts = torch.where(finite.unsqueeze(-1), points, torch.zeros_like(points))
    dx = pts[..., :, 2:, :] - pts[..., :, :-2, :]
    dy = pts[..., 2:, :, :] - pts[..., :-2, :, :]
    dx = torch.nn.functional.pad(dx.permute(0, 3, 1, 2), (1, 1, 0, 0)).permute(0, 2, 3, 1)
    dy = torch.nn.functional.pad(dy.permute(0, 3, 1, 2), (0, 0, 1, 1)).permute(0, 2, 3, 1)
    n = torch.cross(dy, dx, dim=-1)
    n = torch.nn.functional.normalize(n, dim=-1)
    return torch.where(finite.unsqueeze(-1), n, torch.zeros_like(n))


def _normalize_disparity(depth: torch.Tensor) -> torch.Tensor:
    """Per-frame normalize 1/depth to [0, 1] using 0.1/99.9 percentile clipping."""
    out = torch.zeros_like(depth)
    for i in range(depth.shape[0]):
        d = depth[i]
        valid = torch.isfinite(d) & (d > 0)
        if not valid.any():
            continue
        disp = torch.where(valid, 1.0 / d.clamp_min(1e-6), torch.zeros_like(d))
        disp_valid = disp[valid]
        lo = torch.quantile(disp_valid, 0.001)
        hi = torch.quantile(disp_valid, 0.999)
        scale = (hi - lo).clamp_min(1e-6)
        norm = ((disp - lo) / scale).clamp(0.0, 1.0)
        out[i] = torch.where(valid, norm, torch.zeros_like(norm))
    return out


# -------------------------------------------------------------------- the focal

def _slope(x: torch.Tensor, y: torch.Tensor) -> float:
    """Least-squares slope of y against x, NaN when x does not vary."""
    xm = x - x.mean()
    var = (xm * xm).sum()
    if not bool(torch.isfinite(var)) or float(var) <= 0.0:
        return float("nan")
    return float((xm * (y - y.mean())).sum() / var)


def _fit_focal(points, src_w: int, src_h: int, out_w: int, out_h: int) -> int:
    """The camera's focal length in pixels of the uploaded frame, or 0.

    MoGe infers with `force_projection=True`, so `points` is exactly the depth
    unprojected through its intrinsics -- and those intrinsics are square-pixel
    and centred on (0.5, 0.5) by construction
    (`intrinsics_from_focal_center(f_diag / aspect, f_diag, 0.5, 0.5)`, in
    `comfy_extras/nodes_moge.py`). ONE number therefore describes them:
    `X/Z = (u - 0.5) / (f/W)` and `Y/Z = (v - 0.5) / (f/H)` with the same `f`.
    Measured against a real geometry packet, `unproject(depth, {fx, fy,
    cx = cy = 0.5})` reproduces `points` to 4e-6 relative (p99 5e-4), so this
    regression is over-determined rather than approximate.

    Fitted on a SUBSAMPLE: the fit is two sums over millions of pixels, and a
    65k-point draw answers it -- the two axes agree to <0.1% on real data, which
    is why the mean of the two independent answers is the one number written.
    (`_slope` returns 1/(f/pixels) because the regressor is the pixel coordinate,
    so the focal in pixels is the dimension over the slope.)

    The answer is scaled from the MODEL INPUT resolution to the UPLOADED frame:
    the app draws the source-sized poster of the same file, so a focal in the
    scaled inference's pixels would make the lens look longer by the generation
    scale. A fit that is not finite and positive writes 0, which the app reads as
    "unknown" -- and a window claims nothing, so a failure is never a guess.
    """
    if points is None or not hasattr(points, "dim"):
        return 0
    p = points.detach().float()
    if p.dim() == 4:
        p = p[0]
    if p.dim() != 3 or p.shape[2] != 3 or p.shape[0] < 8 or p.shape[1] < 8:
        return 0
    ph, pw = int(p.shape[0]), int(p.shape[1])
    step = max(1, int(round((pw * ph / 65536.0) ** 0.5)))
    sub = p[::step, ::step]
    z = sub[..., 2]
    ok = torch.isfinite(sub).all(dim=-1) & (z.abs() > 1e-6)
    if int(ok.sum()) < 64:
        return 0
    safe = torch.where(z.abs() > 1e-6, z, torch.ones_like(z))
    xz = (sub[..., 0] / safe)[ok]
    yz = (sub[..., 1] / safe)[ok]
    rows = torch.arange(0, ph, step, device=p.device)[:sub.shape[0]].float()
    cols = torch.arange(0, pw, step, device=p.device)[:sub.shape[1]].float()
    uu = ((cols + 0.5) / pw).unsqueeze(0).expand(sub.shape[0], sub.shape[1])[ok]
    vv = ((rows + 0.5) / ph).unsqueeze(1).expand(sub.shape[0], sub.shape[1])[ok]
    ax = _slope(uu, xz)
    ay = _slope(vv, yz)
    if not (math.isfinite(ax) and math.isfinite(ay) and ax > 0.0 and ay > 0.0):
        return 0
    fx = pw / ax
    fy = ph / ay
    scale = (src_h / float(out_h)) if out_h > 0 else 1.0
    focal = int(round(0.5 * (fx + fy) * scale))
    return focal if focal > 0 else 0


# ------------------------------------------------------------------------- ffmpeg io

def _probe(path):
    """width, height, fps string, frame count (0 when the container cannot say)."""
    raw = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height,r_frame_rate,nb_frames",
        "-show_entries", "format=duration", "-of", "json", path,
    ])
    d = json.loads(raw)
    st = d["streams"][0]
    w, h = int(st["width"]), int(st["height"])
    fps = st.get("r_frame_rate") or "0/1"
    nb = int(st.get("nb_frames") or 0)
    if nb <= 0:
        dur = float(d.get("format", {}).get("duration") or 0.0)
        num, den = (fps.split("/") + ["1"])[:2]
        rate = float(num) / float(den or 1)
        nb = int(dur * rate + 0.5)
    return w, h, fps, nb


def _decoder(path):
    return subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-map", "0:v:0", "-an",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE, bufsize=10 ** 8)


def _encoder(path, w, h, fps, gop, crf):
    return subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (w, h), "-r", fps, "-i", "-",
         "-c:v", "libx264", "-preset", "veryfast", "-crf", str(crf),
         # A short GOP is load-bearing for the player: it restarts a clip by seeking
         # to the nearest keyframe, and it bounds how far a decoder that fell behind
         # can be pulled forward. One second.
         "-g", str(gop),
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-f", "mp4", path],
        stdin=subprocess.PIPE, bufsize=1024 * 1024)


def _even(n):
    """yuv420p needs even dimensions; the old VHS path had the same constraint."""
    return max(2, n - (n % 2))


# ------------------------------------------------------------------------- the node

class MoGeVideoStream:
    """Run MoGe over a video range and write colour + depth + normal clips.

    The input is a path, NOT an IMAGE tensor, and that is the whole point: see the
    module docstring. Outputs are filenames in the ComfyUI output directory (fetch
    them like any other produced video) plus the frame count written. All three
    clips are encoded from the SAME decoded frames, so they are aligned in time by
    construction -- that is what removes the whole skew class for heavy sources.
    """

    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "moge_model": ("MOGE_MODEL",),
                "video": ("STRING", {"default": ""}),
                # Stable names for the app: the files are `basrelief_<tag>_depth.mp4`,
                # `..._normal.mp4` and the sidecar `basrelief_<tag>.progress.json`, so a
                # client can address all three without having seen a response. Empty
                # falls back to a timestamp+pid, which only a human can follow.
                "tag": ("STRING", {"default": ""}),
                "start_frame": ("INT", {"default": 0, "min": 0, "max": 10 ** 9}),
                # 0 = to the end of the video.
                "frame_count": ("INT", {"default": 0, "min": 0, "max": 10 ** 9}),
                # 0 = the source's own height. The old path's custom_height means
                # the same thing, and the model sees the scaled frames either way.
                "target_height": ("INT", {"default": 0, "min": 0, "max": 8192}),
                # The playback tracks must be sized for the PHONE, not the camera:
                # measured, a 1440x2560@50 source could not be decoded by even ONE
                # of the three streams on a Pixel 7 Pro (the colour fell 0.4-2.4 s
                # behind), while 1920x1080-class content held ~40 fps per stream.
                # This caps the LONG side of both the generated tracks and the
                # colour re-encode. 0 = no cap.
                "max_long_side": ("INT", {"default": 1920, "min": 0, "max": 8192}),
                # Frames per inference call. This is the memory knob: peak = model +
                # window. 4 is the core node's default and keeps 4K windows small.
                "batch_size": ("INT", {"default": 4, "min": 1, "max": 16}),
                # The rate the DEPTH AND NORMAL clips are written at, capped from the
                # source. Measured on a Pixel 7 Pro: three simultaneous 1080p50
                # decoder streams each ran at ~39-40 fps, so their positions
                # diverged ~0.5-1 s over minutes (the player's own skew line), worst
                # on the heaviest encode. The renderer latches ~30-40/s and the depth
                # is sampled per draw, so writing the generated tracks at 30 removes
                # two thirds of that load and lets the shared clock pin all three.
                # 0 = the source's own rate.
                "fps_cap": ("INT", {"default": 30, "min": 0, "max": 240}),
                # Keep MoGe's sky/invalid mask, which renders the masked region as
                # depth 0 -- a hard cutout. The measured failure of it: on a
                # portrait against a blown-out sky the raised limbs read as
                # "almost sky" and merge into the black hole, so the relief loses
                # them. False keeps the raw predicted depth everywhere, which
                # grades the background instead of deleting it. Default True is
                # exactly what this node always did.
                "apply_mask": ("BOOLEAN", {"default": True}),
                # The PREVIOUS frame's weight in an exponential blend of the depth:
                # 0 = off (each frame independent, MoGe's raw output), 0.7 = mostly
                # the previous frame. Measured need: on a portrait against a
                # blown-out sky, six consecutive frames showed the leg silhouette
                # and the bale edge morphing frame to frame -- per-frame inference
                # has no reason to agree with itself -- while Video-Depth-Anything
                # held them steady. A causal EMA is the cheap part of that win: it
                # cannot invent detail, and fast motion ghosts if pushed too high,
                # so the knob is explicit and defaults to off.
                "temporal_blend": ("FLOAT", {"default": 0.0, "min": 0.0, "max": 0.95,
                                             "step": 0.05}),
                "resolution_level": ("INT", {"default": 9, "min": 0, "max": 9}),
                "refine_steps": ("INT", {"default": 3, "min": 0, "max": 8}),
            }
        }

    RETURN_TYPES = ("STRING", "STRING", "STRING", "INT")
    RETURN_NAMES = ("color_path", "depth_path", "normal_path", "frames")
    FUNCTION = "run"
    CATEGORY = "basrelief"
    # This node produces FILES, not a tensor anything downstream consumes, so it is
    # an output node. Without this ComfyUI refuses the whole prompt with
    # `prompt_no_outputs`: nothing consumes its return values, and the validator
    # cannot know the files were the point.
    OUTPUT_NODE = True

    @classmethod
    def IS_CHANGED(cls, **kwargs):
        # The video file can change under the same name; never cache this node's
        # output. A stale cache here would hand back the depth of a previous clip.
        return float("NaN")

    def run(self, moge_model, video, tag, start_frame, frame_count, target_height,
            max_long_side, batch_size, fps_cap, apply_mask, temporal_blend,
            resolution_level, refine_steps):
        src = folder_paths.get_annotated_filepath(video)
        if not src or not os.path.isfile(src):
            raise ValueError("video not found in the input directory: %r" % (video,))

        src_w, src_h, fps, src_frames = _probe(src)
        # Two independent caps, and the smaller wins: the sheet's chip (a GENERATION
        # choice, measured in height, as the old custom_height was) and the long side
        # (a PLAYBACK choice -- the phone decodes these clips). Measured: a
        # 1440x2560@50 source could not be held even by the colour stream alone on a
        # Pixel 7 Pro, while 1920x1080-class content held ~40 fps per stream.
        gen_scale = 1.0
        if target_height > 0:
            gen_scale = min(gen_scale, target_height / float(src_h))
        play_scale = 1.0
        if max_long_side > 0:
            play_scale = min(play_scale, max_long_side / float(max(src_w, src_h)))
        gen_scale = min(gen_scale, play_scale)
        out_w = _even(int(round(src_w * gen_scale)))
        out_h = _even(int(round(src_h * gen_scale)))
        col_w = _even(int(round(src_w * play_scale)))
        col_h = _even(int(round(src_h * play_scale)))

        total = src_frames - start_frame if frame_count <= 0 else frame_count
        if total <= 0:
            raise ValueError("nothing to do: start_frame=%d of %d frames"
                             % (start_frame, src_frames))

        # The rate the generated tracks are written at, capped from the source.
        # The source is decoded in order either way; the cap only decides which
        # frames become output frames, and the mapping keeps the TIMELINE: output
        # frame k is source frame round_half_up(k * src/out), so the clips stay
        # aligned with the colour in TIME -- they simply have fewer samples. The
        # app's frame-count check applies the same rule; rounding is half-up
        # (int(x + 0.5)) on both sides, never banker's rounding, or the two
        # derivations disagree by a frame at some rates.
        num, den = (fps.split("/") + ["1"])[:2]
        src_rate = float(num) / float(den or 1)
        capped = fps_cap > 0 and src_rate > fps_cap
        out_rate = float(fps_cap) if capped else src_rate
        ratio = src_rate / out_rate
        enc_fps = ("%d/1" % fps_cap) if capped else fps
        gop = max(1, int(round(out_rate)))
        total_out = 0
        while int(total_out * ratio + 0.5) < total:
            total_out += 1

        safe = _safe_tag(tag)
        out_dir = folder_paths.get_output_directory()
        depth_name = "basrelief_%s_depth.mp4" % safe
        normal_name = "basrelief_%s_normal.mp4" % safe
        color_name = "basrelief_%s_color.mp4" % safe
        progress_name = "basrelief_%s.progress.json" % safe
        depth_final = os.path.join(out_dir, depth_name)
        normal_final = os.path.join(out_dir, normal_name)
        color_final = os.path.join(out_dir, color_name)
        depth_tmp = os.path.join(out_dir, depth_name[:-4] + ".tmp.mp4")
        normal_tmp = os.path.join(out_dir, normal_name[:-4] + ".tmp.mp4")
        color_tmp = os.path.join(out_dir, color_name[:-4] + ".tmp.mp4")
        progress_path = os.path.join(out_dir, progress_name)

        # The colour is re-encoded only when the source exceeds the playback caps,
        # and at the PLAYBACK scale rather than the sheet chip: what the user looks
        # at should be the best the phone can hold. When the source already fits,
        # the app plays its own remux (a lossless sample copy) and no generation is
        # thrown away -- `color` is absent from the sidecar and the ui.
        need_color = (col_w, col_h) != (src_w, src_h) or capped

        # The focal is fitted once, on the first window, and then travels in the
        # sidecar and the ui payload. 0 = "no claim": the fit failed, or the
        # model did not return a point map, and the app draws its 50-degree
        # default rather than the run failing over a number a look does not need.
        focal = 0
        fitted = False

        def write_progress(done, state):
            # The app's progress channel AND its resume state. A websocket event dies
            # with a reconnect; this does not, and it can be fetched through /view like
            # any other output file. Written every window -- a few times a second is
            # exactly the rate the sheet's line wants.
            try:
                with open(progress_path, "w") as f:
                    json.dump({"done": done, "total": total_out, "state": state,
                               "depth": depth_name, "normal": normal_name,
                               "color": color_name if need_color else None,
                               "progress": progress_name,
                               # The input side of the count, so the app can verify
                               # the exactness it planned instead of trusting it.
                               "src_frames": total, "src_fps": round(src_rate, 3),
                               "fps_out": int(round(out_rate)),
                               # The camera's one number, in pixels of the uploaded
                               # frame. 0 until the first window has been fitted.
                               "focal": focal}, f)
            except OSError:
                pass

        prev_smooth = None
        dec = _decoder(src)
        dep_enc = _encoder(depth_tmp, out_w, out_h, enc_fps, gop, 14)
        # The normal is noise-like and was 16 MB for a 7 s clip at CRF 14; 17 is the
        # same picture to the eye and roughly half the file and decode cost. The
        # depth stays at 14 -- it is the geometry, and it is the smaller file.
        nrm_enc = _encoder(normal_tmp, out_w, out_h, enc_fps, gop, 17)
        col_enc = _encoder(color_tmp, col_w, col_h, enc_fps, gop, 16) if need_color else None
        frame_bytes = src_w * src_h * 3
        written = 0
        started = time.time()
        pbar = comfy.utils.ProgressBar(total_out)
        write_progress(0, "running")

        try:
            # Skip to the range's first frame by decoding through it. Frame-exact,
            # which `-ss` before -i is not (it lands on a keyframe and the offset
            # then depends on the decoder's timestamps).
            for _ in range(start_frame):
                if not dec.stdout.read(frame_bytes):
                    raise ValueError("start_frame %d is past the end of the video"
                                     % start_frame)

            src_index = 0
            kept = 0
            next_keep = 0
            while written < total_out:
                window = []
                # The keep-rule: output frame k is source frame round_half_up(k *
                # src/out). With no cap, ratio is 1 and every frame is kept.
                while len(window) < batch_size and written + len(window) < total_out:
                    raw = dec.stdout.read(frame_bytes)
                    if len(raw) < frame_bytes:
                        break
                    if src_index == next_keep:
                        window.append(np.frombuffer(raw, np.uint8).reshape(src_h, src_w, 3))
                        kept += 1
                        next_keep = int(kept * ratio + 0.5)
                    src_index += 1
                if not window:
                    break

                b_src = torch.from_numpy(np.stack(window)).float().div_(255.0)
                b_src = b_src.permute(0, 3, 1, 2)
                c_u8 = None
                if col_enc is not None:
                    b_col = b_src
                    if (col_w, col_h) != (src_w, src_h):
                        b_col = torch.nn.functional.interpolate(
                            b_src, size=(col_h, col_w), mode="bilinear", antialias=True)
                    c_u8 = (b_col.clamp(0, 1) * 255.0).round().to(torch.uint8)
                    c_u8 = c_u8.permute(0, 2, 3, 1).cpu().numpy()
                b = b_src
                if (out_w, out_h) != (src_w, src_h):
                    b = torch.nn.functional.interpolate(
                        b_src, size=(out_h, out_w), mode="bilinear", antialias=True)

                with torch.inference_mode():
                    geo = moge_model.infer(b, resolution_level=resolution_level,
                                           apply_mask=apply_mask,
                                           refine_steps=refine_steps)

                if not fitted:
                    # ONCE per run, on the FIRST window: the intrinsics are constant
                    # for a run, and the point map is right there. `points` is not
                    # always present (older MoGe builds), which is a 0 -- see
                    # _fit_focal.
                    fitted = True
                    focal = _fit_focal(geo.get("points"), src_w, src_h, out_w, out_h)
                    print("[basrelief] focal=%d px (of the %dx%d source)"
                          % (focal, src_w, src_h), flush=True)

                depth = _normalize_disparity(geo["depth"].float())
                if temporal_blend > 0:
                    # s = t*prev + (1-t)*raw, seeded on the range's first frame so a
                    # clip starts at its own estimate rather than at a blend with
                    # nothing. The state carries across windows; it is per RUN, and a
                    # run is one range, so two clips cannot leak into each other.
                    for i in range(depth.shape[0]):
                        raw = depth[i]
                        smoothed = raw if prev_smooth is None else (
                                prev_smooth * temporal_blend + raw * (1.0 - temporal_blend))
                        depth[i] = smoothed
                        prev_smooth = smoothed.clone()
                if "normal" in geo:
                    n = geo["normal"].float()
                else:
                    n = _normals_from_points(geo["points"].float())
                # OpenGL convention, exactly as MoGeRender(normal_opengl) does:
                # Y and Z flipped, then 0..1.
                n = n * n.new_tensor([1.0, -1.0, -1.0])
                n = (n * 0.5 + 0.5).clamp(0.0, 1.0)

                d_rgb = depth.unsqueeze(-1).expand(-1, -1, -1, 3)
                d_u8 = (d_rgb.clamp(0, 1) * 255.0).round().to(torch.uint8).cpu().numpy()
                n_u8 = (n.clamp(0, 1) * 255.0).round().to(torch.uint8).cpu().numpy()
                for i in range(d_u8.shape[0]):
                    dep_enc.stdin.write(d_u8[i].tobytes())
                    nrm_enc.stdin.write(n_u8[i].tobytes())
                    if c_u8 is not None:
                        col_enc.stdin.write(c_u8[i].tobytes())
                    written += 1
                    pbar.update_absolute(written)

                # Every window, so the sheet's line moves at the window rate rather
                # than in 30-frame steps; the file is ~150 bytes.
                write_progress(written, "running")
                if written % 30 < batch_size:
                    print("[basrelief] %d/%d frames  %.2fs/frame"
                          % (written, total_out, (time.time() - started) / max(1, written)),
                          flush=True)

                if comfy.model_management.processing_interrupted():
                    raise comfy.model_management.InterruptProcessingException()
        except BaseException:
            for p in (dec, dep_enc, nrm_enc, col_enc):
                if p is None:
                    continue
                try:
                    p.kill()
                except Exception:
                    pass
            for f in (depth_tmp, normal_tmp, color_tmp):
                try:
                    os.remove(f)
                except OSError:
                    pass
            write_progress(written, "failed")
            raise
        finally:
            try:
                dec.wait(timeout=10)
            except Exception:
                pass

        encoders = [dep_enc, nrm_enc] + ([col_enc] if col_enc is not None else [])
        for enc in encoders:
            enc.stdin.close()
            enc.wait()
        if any(enc.returncode != 0 for enc in encoders):
            # Never rename a broken file over the good name: the failure has to reach
            # the app as a failure, not as a clip that plays short.
            for f in (depth_tmp, normal_tmp, color_tmp):
                try:
                    os.remove(f)
                except OSError:
                    pass
            write_progress(written, "failed")
            raise RuntimeError("encoder failed (rc=%s)"
                               % ([enc.returncode for enc in encoders],))
        os.replace(depth_tmp, depth_final)
        os.replace(normal_tmp, normal_final)
        if col_enc is not None:
            os.replace(color_tmp, color_final)
        write_progress(written, "done")
        print("[basrelief] done: %d frames in %.1fs" % (written, time.time() - started),
              flush=True)
        # The ui payload is what /history carries after the run; the result tuple is
        # what a downstream node would consume. The app reads the ui names (and polls
        # the sidecar mid-run), so both are stated rather than derived. `color` is
        # absent when the source already fit the playback caps and the app's own
        # remux is the colour.
        ui = {"depth": [depth_name], "normal": [normal_name],
              "progress": [progress_name], "frames": [written],
              # The same one number the sidecar carries, so a resume that found only
              # /history still stores it; absent on an older node, where it reads 0.
              "focal": [focal]}
        if col_enc is not None:
            ui["color"] = [color_name]
        return {"ui": ui,
                "result": (color_name if col_enc is not None else "", depth_name,
                           normal_name, written)}


# ------------------------------------------------------------------ SHARP still

# SHARP's encoder accepts only internal sizes of the form 384 + 288k (Splatline's
# still backend ran at 1536, and the approved bale still was built at the same
# setting). A size outside that family is snapped to the nearest entry rather than
# failing a run over a hint.
SHARP_INTERNAL_BASE = 384
SHARP_INTERNAL_STEP = 288

# The checkpoint the approved still pipeline used. Its basename is also what the
# torch hub cache stores, so a box that has run `sharp predict` once already has it.
SHARP_DEFAULT_CKPT = "sharp_2572gikvuh.pt"
SHARP_CKPT_URL = ("https://ml-site.cdn-apple.com/models/sharp/"
                  "sharp_2572gikvuh.pt")

# Where the checkpoint is looked for when the node is not told. The container
# deployment puts it on a bind mount (which outlives the container); the torch hub
# cache is the last resort, and downloads once.
SHARP_CKPT_CANDIDATES = (
    "/comfy/mnt/models/sharp/" + SHARP_DEFAULT_CKPT,
    "/basedir/tools/sharp/" + SHARP_DEFAULT_CKPT,
)

# 32 B/splat: 3 float32 position, 3 float32 scale, 4 uint8 colour, 4 uint8 rotation.
# The record IS the two RGBA32F texels the phone's renderer uploads.
SPLAT_ITEMSIZE = 32
# A .splat whose splat count is not a multiple of 2048 makes a WebGL texture upload
# refuse the whole cloud -- a black screen with a correct count in the HUD. Every
# file is therefore padded with zero records to that block. The approved uncapped
# still happened to be an exact multiple (1,179,648 = 576 x 2048); a budgeted one is
# not, and an unpadded budgeted file is the silent failure this constant prevents.
SPLAT_PAD_TO = 2048
# Points fainter than this are not worth a cell in the budgeted re-sample. This is
# the reference implementation's own floor.
SPLAT_ALPHA_MIN = 0.004
SH_C0 = 0.28209479177387814

# The predictor is cached across runs in this process, keyed by checkpoint+device,
# and released at the end of a run unless the caller asks otherwise -- SHARP is
# ~2.8 GB resident and the card is shared with ComfyUI's own models.
_SHARP_PREDICTOR = None
_SHARP_PREDICTOR_KEY = None


def _safe_tag(tag, fallback=None):
    """The app's own tag rule: alnum plus '-'/'_', truncated to 48.

    The node derives every output filename from this, and the app derives the same
    filenames from the item id without ever having seen a response -- so both sides
    must run the SAME rule. Item ids are UUIDs and survive it unchanged; the rule
    exists for a hand-edited index whose id carries a space or punctuation. An id
    that sanitises to nothing falls back to a timestamp+pid, which only a human can
    follow (the app refuses such a tag before submitting).
    """
    safe = "".join(c for c in (tag or "") if c.isalnum() or c in "-_")[:48]
    if not safe:
        safe = fallback or ("%d_%d" % (int(time.time()), os.getpid()))
    return safe


def _sharp_internal_size(n):
    """Snap to the nearest size SHARP's encoder accepts (384 + 288k)."""
    n = int(n)
    if n <= SHARP_INTERNAL_BASE:
        return SHARP_INTERNAL_BASE
    k = int(round((n - SHARP_INTERNAL_BASE) / float(SHARP_INTERNAL_STEP)))
    return SHARP_INTERNAL_BASE + k * SHARP_INTERNAL_STEP


def _sharp_focal_px(w, h):
    """SHARP's 30 mm-equivalent fallback focal, in pixels of a w x h frame.

    `sharp.utils.io.load_rgb` reads `FocalLengthIn35mmFilm` from EXIF and falls back
    to 30 mm when it is absent. ComfyUI's `LoadImage` hands the node a tensor with
    the EXIF already gone, so the node reproduces the fallback -- which is also
    exactly the lens the approved uncapped still used (1216x1632 -> 1411.2 px, and
    the phone's own renderer bakes the same number for its demo cloud).
    """
    return 30.0 * math.hypot(float(w), float(h)) / math.hypot(36.0, 24.0)


def _release_sharp():
    """Drop the cached predictor and hand its VRAM back."""
    global _SHARP_PREDICTOR, _SHARP_PREDICTOR_KEY
    if _SHARP_PREDICTOR is not None:
        _SHARP_PREDICTOR = None
        _SHARP_PREDICTOR_KEY = None
        try:
            torch.cuda.empty_cache()
        except Exception:
            pass


def _load_sharp_predictor(checkpoint, device_str):
    """Load and cache SHARP's predictor for the given checkpoint and device."""
    global _SHARP_PREDICTOR, _SHARP_PREDICTOR_KEY
    key = (checkpoint or SHARP_DEFAULT_CKPT, device_str)
    if _SHARP_PREDICTOR is not None and _SHARP_PREDICTOR_KEY == key:
        return _SHARP_PREDICTOR
    _release_sharp()
    try:
        from sharp.models import PredictorParams, create_predictor
    except ImportError as e:
        raise RuntimeError(
            "SharpStill needs Apple's SHARP package in ComfyUI's Python "
            "environment. Install it (and plyfile) with: pip install "
            "plyfile 'git+https://github.com/apple-aiml-research/ml-sharp.git"
            "@aed6527499ef91cba3b54c18d49a870f25947190' --no-deps, and put "
            "sharp_2572gikvuh.pt where the node can see it. See the package "
            "README. (%s)" % e)
    path = checkpoint or ""
    if not path:
        for cand in SHARP_CKPT_CANDIDATES:
            if os.path.isfile(cand):
                path = cand
                break
    if path and os.path.isfile(path):
        state = torch.load(path, map_location="cpu")
    else:
        # No local copy: torch hub fetches to its own cache and reuses it after.
        state = torch.hub.load_state_dict_from_url(SHARP_CKPT_URL, progress=True)
    predictor = create_predictor(PredictorParams())
    predictor.load_state_dict(state)
    predictor.eval().to(device_str)
    _SHARP_PREDICTOR = predictor
    _SHARP_PREDICTOR_KEY = key
    return predictor


def _predict_sharp(predictor, image, f_px, device, internal_size):
    """The Splatline still backend's `predict_sharp_image`, reproduced verbatim.

    Copied rather than imported: it lives in Splatline's own `utils/splat_models.py`,
    not in the `sharp` package, and the node must not depend on that repo being
    present. `image` is an 8-bit HxWx3 RGB numpy frame, exactly what
    `sharp.utils.io.load_rgb` returns and what the reference driver fed the model.
    """
    import torch
    import torch.nn.functional as F
    from sharp.utils.gaussians import unproject_gaussians

    internal_shape = (internal_size, internal_size)
    img_pt = torch.from_numpy(image.copy()).float().to(device).permute(2, 0, 1) / 255.0
    _, height, width = img_pt.shape
    disp_factor = torch.tensor([f_px / width]).float().to(device)
    img_resized = F.interpolate(
        img_pt[None], size=internal_shape, mode="bilinear", align_corners=True)
    gaussians_ndc = predictor(img_resized, disp_factor)

    intrinsics = torch.tensor([
        [f_px, 0, width / 2, 0],
        [0, f_px, height / 2, 0],
        [0, 0, 1, 0],
        [0, 0, 0, 1],
    ]).float().to(device)
    intrinsics_resized = intrinsics.clone()
    intrinsics_resized[0] *= internal_shape[0] / width
    intrinsics_resized[1] *= internal_shape[1] / height
    return unproject_gaussians(
        gaussians_ndc, torch.eye(4).to(device), intrinsics_resized, internal_shape)


def _cell_keys(u, v, cell):
    """A compact integer key per (u,v) cell, so grouping is one `np.unique`."""
    i = np.floor(u / cell).astype(np.int64)
    j = np.floor(v / cell).astype(np.int64)
    width = int(j.max() - j.min()) + 1
    return (i - i.min()) * width + (j - j.min())


def _choose_cell_px(u, v, target):
    """The smallest (u,v) cell whose occupied-cell count is at most `target`.

    The budget is a UNIFORM image-space re-sample, never a 1-D stride over the PLY
    order: a `[::step]` cap on this build kept every 8th gaussian of the raster,
    which measured 6.3 px apart in u against 0.7 px in v at SHARP's own lens -- a
    columnar cloud that rendered as vertical streaks through the whole picture
    (46% of 2x2-px cells occupied). Binning in (u,v) and keeping the highest-opacity
    point per cell took the same cap to 95% occupancy and removed the streaks.
    Occupied cells fall monotonically as the cell grows, so a bisection finds the
    finest grid that still fits the budget.
    """
    hi = float(max(u.max() - u.min(), v.max() - v.min())) + 1.0
    lo = 1e-3
    for _ in range(30):
        mid = 0.5 * (lo + hi)
        if np.unique(_cell_keys(u, v, mid)).size <= target:
            hi = mid
        else:
            lo = mid
    return hi


def _resample_indices(u, v, opa, vis, cell):
    """One index per occupied (u,v) cell: the highest-opacity visible point in it.

    The reference's own selection: `np.lexsort((-opa, key))` then the first row of
    each cell. `opa` is the sigmoid opacity, so "highest opacity" is the point the
    renderer would show through most strongly -- a plain stride has no such rule and
    that is why it can keep a near-transparent point and drop an opaque one beside
    it.
    """
    key = _cell_keys(u, v, cell)
    cand = np.flatnonzero(vis)
    order = np.lexsort((-opa[cand], key[cand]))
    cand = cand[order]
    kk = key[cand]
    first = np.concatenate([[True], kk[1:] != kk[:-1]])
    return cand[first]


def _splat_records(pos, scale_logit, quat, f_dc, opa_logit):
    """The 32 B/splat record, from the PLY's stored values, as `ply2splat.py` builds it.

    The inputs are exactly what `save_ply` writes: `scale_logit = log(singular_value)`,
    `f_dc` the sRGB colour as SH degree 0, `opa_logit` the opacity logit, and the
    float32 quaternion. This side of the split is the CONVERTER's arithmetic, so an
    uncapped run is byte-identical to `save_ply` -> strip -> `ply2splat.py` under one
    torch version rather than merely close (verified: 0 differing bytes). The
    logit-producing half lives in `_build_splat`, in torch, where `save_ply` computes
    it -- numpy's `log` differs from torch's by one ULP, and exp() carries that into
    the scale on ~5% of records.
    """
    n = int(pos.shape[0])
    rec = np.empty(n, dtype=[("pos", "<f4", 3), ("scl", "<f4", 3),
                             ("col", "u1", 4), ("rot", "u1", 4)])
    rec["pos"] = pos.astype(np.float32)
    rec["scl"] = np.exp(scale_logit.astype(np.float32)).astype(np.float32)
    rgb = np.clip(0.5 + SH_C0 * f_dc.astype(np.float32), 0.0, 1.0)
    rgba = np.empty((n, 4), np.uint8)
    rgba[:, :3] = np.rint(rgb * 255.0).astype(np.uint8)
    opa = 1.0 / (1.0 + np.exp(-opa_logit.astype(np.float32)))
    rgba[:, 3] = np.rint(np.clip(opa, 0.0, 1.0) * 255.0).astype(np.uint8)
    rec["col"] = rgba
    q = quat.astype(np.float32)
    q = q / np.maximum(np.linalg.norm(q, axis=1, keepdims=True), 1e-12)
    rec["rot"] = np.rint(np.clip(q * 128.0 + 128.0, 0.0, 255.0)).astype(np.uint8)
    return rec


def _build_splat(g, f_px, w, h, budget):
    """SHARP's Gaussians3D -> the padded `.splat` records and the run's numbers.

    Returns `(records, splats, pad, cell)`: the record array (unpadded), the count
    written, the zero-record padding, and the (u,v) cell used (0 when uncapped).

    `budget` is a ceiling on the FILE, not on the selection: the padding records are
    real records the phone uploads and sorts, so the target is rounded down to a
    whole block and the padded total can never exceed it. `budget = 0` is uncapped
    and verbatim -- every gaussian SHARP predicted, exactly as the approved still was
    written (no alpha or z filter, matching teststill.splat).
    """
    from sharp.utils import color_space as cs_utils

    pos = g.mean_vectors.flatten(0, 1).detach().float().cpu().numpy()
    quat = g.quaternions.flatten(0, 1).detach().float().cpu().numpy()
    # `save_ply`'s own stored fields, computed in torch float32 so the records match
    # the PLY round trip exactly: the scale logit, the opacity logit (its
    # `_inverse_sigmoid`), and the sRGB colour as SH degree 0 (its
    # `convert_rgb_to_spherical_harmonics`).
    scale_logit = torch.log(g.singular_values.flatten(0, 1).detach().float())
    opa_t = g.opacities.flatten(0, 1).detach().float()
    opa_logit = torch.log(opa_t / (1.0 - opa_t))
    # SHARP predicts linearRGB and `save_ply` converts it to sRGB on the way out for
    # public renderers; the `.splat` record carries that sRGB.
    f_dc = ((cs_utils.linearRGB2sRGB(g.colors.flatten(0, 1).detach().float())
             - 0.5) / SH_C0)
    scale_logit = scale_logit.cpu().numpy()
    opa_logit = opa_logit.cpu().numpy().reshape(-1)
    f_dc = f_dc.cpu().numpy()
    # The re-sample's "highest opacity" rule reads the sigmoid, exactly as the PLY
    # converter reconstructs it.
    opa = (1.0 / (1.0 + np.exp(-opa_logit))).astype(np.float32)
    n = int(pos.shape[0])

    target = 0
    if budget and int(budget) > 0:
        target = (int(budget) // SPLAT_PAD_TO) * SPLAT_PAD_TO
        if target < SPLAT_PAD_TO:
            target = SPLAT_PAD_TO

    cell = 0.0
    if target <= 0:
        sel = np.arange(n)
    else:
        z = pos[:, 2]
        safe_z = np.maximum(z, 1e-6)
        u = f_px * pos[:, 0] / safe_z + w / 2.0
        v = f_px * pos[:, 1] / safe_z + h / 2.0
        vis = ((opa > SPLAT_ALPHA_MIN) & (z > 0.05)
               & np.isfinite(u) & np.isfinite(v))
        nvis = int(vis.sum())
        if nvis <= target:
            sel = np.flatnonzero(vis)
        else:
            cell = _choose_cell_px(u[vis], v[vis], target)
            sel = _resample_indices(u, v, opa, vis, cell)
            print("[basrelief] budget %d: cell %.3f px -> %d of %d visible"
                  % (target, cell, len(sel), nvis), flush=True)

    rec = _splat_records(pos[sel], scale_logit[sel], quat[sel], f_dc[sel],
                         opa_logit[sel])
    splats = int(rec.shape[0])
    pad = (-splats) % SPLAT_PAD_TO
    return rec, splats, pad, cell


class SharpStill:
    """SHARP on one still image, written as a budgeted `.splat` for the phone.

    The still half of Stage 2: the graph is `LoadImage -> SharpStill`, the node runs
    Apple's SHARP at `internal_size` (the setting every approved build used), then
    emits the 32 B/splat format the phone's renderer reads. There is no PLY on disk:
    the record is built directly from the model's tensors through the same round trip
    `save_ply` + the `ply2splat.py` converter perform, so an uncapped run is
    byte-identical to the approved converter's output when both run under one torch
    version (measured: 0 differing bytes of 37,748,736). The checked-in
    `teststill.splat` was built under a different torch (2.14 against this box's 2.11),
    and against it the positions drift ~7e-5 relative -- a runtime difference, not a
    logic one; the count (1,179,648) and the peak VRAM (7.41 GB) are exact.

    `budget` caps the file. It is enforced as a uniform (u,v) cell re-sample, never a
    1-D stride over the raster order -- see `_choose_cell_px` for the measured
    streaks that rule removes -- and the cap is a ceiling on the FILE, so the
    padding records count: the target is rounded down to a whole 2048-splat block.
    `budget = 0` is uncapped and verbatim.

    The outputs are deterministic filenames in the ComfyUI output directory, derived
    from `tag` exactly as the app derives them: `basrelief_<tag>.splat` (the cloud)
    and `basrelief_<tag>.splat.json` (a sidecar with the count, the focal and the
    run's settings). Fetch both through `/view?filename=...&type=output`, or read the
    `ui` payload of `/history`.
    """

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {
            "image": ("IMAGE",),
            # SHARP accepts only 384 + 288k; an invalid value is snapped and logged.
            "internal_size": ("INT", {"default": 1536, "min": 384, "max": 3072,
                                      "step": SHARP_INTERNAL_STEP}),
            # Target splat count; 0 = uncapped. The file is rounded down to a whole
            # padding block, so the written count never exceeds the budget.
            "budget": ("INT", {"default": 150000, "min": 0, "max": 10 ** 9}),
            # Stable names for the app: `basrelief_<tag>.splat` and
            # `basrelief_<tag>.splat.json`, so a client can address them without
            # having seen a response. Empty falls back to a timestamp+pid.
            "tag": ("STRING", {"default": ""}),
            # Pixels of the uploaded frame; 0 = SHARP's 30 mm fallback. Only the
            # budgeted re-sample reads it (it is the lens the (u,v) grid is uniform
            # in); the cloud itself is unaffected.
            "focal_px": ("FLOAT", {"default": 0.0, "min": 0.0, "max": 100000.0,
                                   "step": 1.0}),
            # Explicit checkpoint path; empty = the node's own candidates, then the
            # torch hub cache (which downloads once).
            "checkpoint": ("STRING", {"default": ""}),
            # Keep the predictor resident between runs (fast repeats) or hand the
            # ~2.8 GB back at the end (the default, on a shared card).
            "keep_loaded": ("BOOLEAN", {"default": False}),
        }}

    RETURN_TYPES = ("STRING", "INT", "INT", "FLOAT")
    RETURN_NAMES = ("splat_path", "splats", "padding", "focal")
    FUNCTION = "run"
    CATEGORY = "basrelief"
    # This node produces a FILE, not a tensor anything downstream consumes.
    OUTPUT_NODE = True

    @classmethod
    def IS_CHANGED(cls, **kwargs):
        # The image and the weights can change under the same prompt; never cache.
        return float("NaN")

    def run(self, image, internal_size, budget, tag, focal_px, checkpoint, keep_loaded):
        import comfy.model_management

        started = time.time()
        device = comfy.model_management.get_torch_device()
        size = _sharp_internal_size(internal_size)
        if size != int(internal_size):
            print("[basrelief] internal_size %d is not 384+288k; using %d"
                  % (internal_size, size), flush=True)

        if image is None or getattr(image, "dim", lambda: 0)() < 4:
            raise ValueError("SharpStill needs an IMAGE tensor (B,H,W,C)")
        h, w = int(image.shape[1]), int(image.shape[2])
        # SHARP's loader takes an 8-bit RGB frame; ComfyUI's tensor is that frame as
        # float/255, so this round trip is lossless and reproduces the reference
        # input exactly (LoadImage's own conversion is the inverse).
        img_u8 = (image[0].detach().cpu().float().clamp(0.0, 1.0).numpy() * 255.0
                  ).round().astype(np.uint8)
        f_px = float(focal_px) if focal_px and float(focal_px) > 0 else _sharp_focal_px(w, h)

        pbar = comfy.utils.ProgressBar(3)
        predictor = _load_sharp_predictor(checkpoint, str(device))
        pbar.update_absolute(1)
        with torch.no_grad():
            g = _predict_sharp(predictor, img_u8, f_px, device, size)
        if comfy.model_management.processing_interrupted():
            raise comfy.model_management.InterruptProcessingException()
        pbar.update_absolute(2)

        rec, splats, pad, _cell = _build_splat(g, f_px, w, h, budget)
        n = int(g.mean_vectors.flatten(0, 1).shape[0])
        pbar.update_absolute(3)

        safe = _safe_tag(tag)
        out_dir = folder_paths.get_output_directory()
        splat_name = "basrelief_%s.splat" % safe
        side_name = "basrelief_%s.splat.json" % safe
        splat_path = os.path.join(out_dir, splat_name)
        side_path = os.path.join(out_dir, side_name)
        tmp = splat_path + ".tmp"
        try:
            with open(tmp, "wb") as fh:
                rec.tofile(fh)
                if pad:
                    fh.write(b"\x00" * (pad * SPLAT_ITEMSIZE))
            os.replace(tmp, splat_path)
        except BaseException:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise
        focal_i = int(round(f_px))
        try:
            with open(side_path, "w") as fh:
                json.dump({"state": "done", "splat": splat_name, "splats": splats + pad,
                           "selected": splats, "padding": pad, "focal": focal_i,
                           "internal_size": size, "budget": int(budget),
                           "width": w, "height": h, "tag": safe}, fh)
        except OSError:
            pass

        if not keep_loaded:
            _release_sharp()
        print("[basrelief] %s: %d splats (+%d pad) from %d, focal %d px, %.1fs"
              % (splat_name, splats + pad, pad, n, focal_i, time.time() - started),
              flush=True)
        return {"ui": {"splat": [splat_name], "sidecar": [side_name],
                       "splats": [splats + pad], "padding": [pad], "focal": [focal_i]},
                "result": (splat_name, splats + pad, pad, f_px)}


# ------------------------------------------------------------------ prototype tool

class MoGeGeometryNPZ:
    """PROTOTYPE: dump a MOGE_GEOMETRY packet to .npz for offline 3D work.

    The streaming node keeps only depth; MoGe also produces the POINT MAP and the
    intrinsics, which is the data a real-3D (splat) render needs. This is a tool for
    the splat prototype, not part of the import path.
    """

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {
            "moge_geometry": ("MOGE_GEOMETRY",),
            "tag": ("STRING", {"default": "geo"}),
        }}

    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("npz",)
    FUNCTION = "run"
    CATEGORY = "basrelief"
    OUTPUT_NODE = True

    def run(self, moge_geometry, tag):
        import numpy as np
        safe = "".join(c for c in (tag or "geo") if c.isalnum() or c in "-_")[:48]
        if not safe:
            safe = "geo"
        name = "basrelief_%s.npz" % safe
        path = os.path.join(folder_paths.get_output_directory(), name)
        arrs = {}
        for k, v in moge_geometry.items():
            if hasattr(v, "detach"):
                arrs[k] = v.detach().cpu().numpy()
            else:
                arrs[k] = np.asarray(v)
        np.savez_compressed(path, **arrs)
        print("[basrelief] geometry dumped: %s (%d arrays)" % (name, len(arrs)), flush=True)
        return {"ui": {"npz": [name]}, "result": (name,)}
