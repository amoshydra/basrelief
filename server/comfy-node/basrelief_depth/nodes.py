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
src_fps, fps_out, color}` and is rewritten every window; the run's UI payload in
`/history` names the same files. **`color` is absent/null when the source already
fit the playback caps** -- then the app's own remux is the colour and no generation
is lost. The generated tracks are always capped (`target_height` from the sheet chip,
`max_long_side` for playback, the smaller wins), because measured, a 1440x2560@50
source could not be held even by the colour stream alone on a Pixel 7 Pro.

`fps_cap` (default 30) caps the rate the clips are written at, never above the
source. Output frame k is source frame `round_half_up(k * src/out)` -- same
timeline, fewer samples.
"""

import json
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

        safe = "".join(c for c in (tag or "") if c.isalnum() or c in "-_")[:48]
        if not safe:
            safe = "%d_%d" % (int(time.time()), os.getpid())
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
                               "fps_out": int(round(out_rate))}, f)
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
              "progress": [progress_name], "frames": [written]}
        if col_enc is not None:
            ui["color"] = [color_name]
        return {"ui": ui,
                "result": (color_name if col_enc is not None else "", depth_name,
                           normal_name, written)}


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
