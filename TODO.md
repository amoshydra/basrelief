# Basrelief — open items

Written to survive context compaction. Update it when an item closes; it is the list a
future session reads instead of re-deriving decisions.

## Decisions waiting on the user

**Storage / backup (deferred by the user, but the finding stands).** Auto Backup has a
**25 MB per-app quota, and when it is exceeded the system backs up *nothing*** — not the
index, not the settings — silently. A handful of 2560px PNGs already exceeds it, so the app
may currently be backing up an empty set while looking configured. Recommendation:
**exclude the bulk (a photos/videos subdirectory) from Auto Backup** so the index and
settings always survive; add a **user-chosen SAF folder** with "Back up now" / "Restore" as
the real, visible backup; keep video in a non-backed-up directory. **Do not make SAF the
primary store** — `AppState.load` runs on the GL thread at cold start and must complete
synchronously, and a binder (possibly cloud) round trip there makes "folder unavailable"
indistinguishable from "photo deleted".

**Reversal rule at the bottom.** Today the release decision is projection-based, so a
*decisive* upward reversal from 98% stays open but a slow creep still collapses. If the user
wants "past ~90% down, any upward movement means stay", that is a direction-change rule on
top of the projection — about five lines, and `SheetIntentTest` already pins the current
boundary.

**Pill tap after a collapsed relaunch** opens fully rather than to the last expanded
fraction. The stored `sheet_pos` *is* the collapsed state; the last expanded fraction would
need storing separately.

## Device-only verification (cannot be done off the phone)

- **`on_unlock`**: Playlists → New → 2+ photos → trigger On unlock → surfaces Home → Rotate
  on → Done → lock → unlock. Expect the wallpaper to advance and the photo line to read
  `playlist: <name> · n+1 of m`. `adb -s <device-serial> logcat -s Basrelief` distinguishes "the
  broadcast never arrived" from "arrived and nothing was due".
- **Spring feel** (settled, ≲1% overshoot) and the **fling branches**. Synthetic swipes
  report a bogus negative velocity and `screencap` forces the final frame, so neither can be
  verified from this side.
- **The photo cross-fade on the home screen** — the engine uses the same renderer, but the
  wallpaper path itself was never exercised.
- **Per-surface Lock settings** on API 35+; the static lock frame on a sub-35 device (moot on
  the current phones).
- IME over the depth field, RTL, TalkBack.

## Ideation backlog, ranked

1. **Light UI** (in progress) — azimuth/elevation sliders + presets, against the contract in
   `LightDirection`. The bundle already stores it per surface.
2. **Auto-depth on import + camera capture** — depth is the moat, and today it needs a panel
   and a button.
3. **Portable packed PNG (export/import) + a static web viewer** — the only distribution the
   platform permits, since no app can rebind its own wallpaper.
4. **Depth polarity invert per photo** — one tap for inside-out maps from monocular models.
5. **Diorama** — subject over a treated background.
6. **ComfyUI workflow templates** — the run machine is already generic over an image output.
7. **On-device depth** — the strategic one: relief without a GPU server. Breaks the
   no-Maven-artifact rule; 3–6 weeks.

## Video + depth — research done, plan agreed

- **Formats:** Android's DDF is a **still-image** spec (API 29, write-side only); MP4-AT is
  the real video container and **no platform code reads it** (AOSP `MPEG4Extractor`: zero
  references; Media3 1.6+ only, i.e. a dependency); Apple spatial video carries **no depth**;
  APV is the wrong tool. So define our own: **colour video + a separate grayscale depth
  video**, both ordinary files `MediaExtractor` reads.
- **Depth source:** generate it **offline at import** on the ComfyUI server. Temporal flicker
  is the trap; **2.5D (one depth map per clip)** is the cheap honest mode and is right for
  live-photo-style footage.
- **Battery:** a video wallpaper **can never sleep while visible** (the still path's real win
  is that it does). ~30–80 mA for the decoder alone. Phases: **0** stills-from-video;
  **1** studio-only; **2** pre-decoded "living photo" loop (recommended — battery ≈ today);
  **3** real-time, opt-in per item, default **off**.
- **Precompute at import so runtime is upload-and-draw:** downscale; depth; temporal smooth;
  **blur in float** (the banding invariant — an 8-bit re-quantised blur brings the contours
  back); resample to the grid; normals; poster frame; loop frames.
- Live displacement would need **GLES3** (depth in the vertex shader; GLES2's vertex texture
  unit minimum is zero), and `ReliefMesh.build` measured 6.6 ms at grid 256 on the host —
  impossible at 30 Hz on a phone.

## Known drift / deliberate deviations

- The studio sheet's Home·Lock segment **scrolls**; the mockup pins it outside the scroll.
  The user chose scrolling.
- The gallery draws `Tap a photo to use it · hold to pick several`, which no mockup state
  draws. The user chose to keep it and make selection obvious instead.
- The sheet fade is **content-only** (the glass stays opaque) by the user's instruction; a
  dialog's scrim still reveals as the card comes down.
- `check-parity` enforces two architectural invariants: **no `new Dialog(` outside
  `ModalSheet`**, and **no `plainRow`** (rows come from the three role factories).
