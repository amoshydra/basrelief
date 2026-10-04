# Video import — the UX, and the strings

Mockup: `.design/prototype/video-import.html` (gitignored). This file is the part
that must survive: the flow, the scaling arithmetic, the copy rules, and every
string, named so they can be lifted into `strings.xml`. It renders the plan in
`docs/VIDEO-PLAN.md`; where the two disagree, the plan wins.

> **`.design/prototype/final.html` is stale.** It draws the gallery filmstrip
> floating on the render edge, which the app no longer does. In the real app
> `Gallery.installStrip(LinearLayout host, int index)` adds the strip **inside the
> sheet panel** — *"the host is the sheet's panel: the strip is added directly to
> it"* — and `MainActivity` passes `panel` at index 1, directly under the grip.
> Do not trust the prototype for structural chrome; read the code.

## The decision, stated once

**A dual-handle trimmer, made usable at every clip length by pinch-zoom, plus a
direct seek.** No sampler, no mode picker, no resolution control. The user sets
start and end; the phone resolves the rest.

| | Living photo (phase 2) | Video wallpaper (phase 3) |
|---|---|---|
| what it is | the trimmed range, pre-decoded at import, replayed from textures | the whole clip, played from two live decoders in lockstep |
| battery | **≈ today** — the wallpaper still sleeps | **30–80 mA, and it can never sleep while visible** |
| who gets it | the default, always | **opt-in and disclosed**, never by accident |

## The filmstrip is not on this screen

The gallery's filmstrip lives **inside the sheet panel**, under the grip
(`installStrip(panel, 1)`). It is gallery furniture, **not** a sibling floating
over the render.

**This sheet draws no filmstrip at all.** It shows the *gallery's* photos, which
is not this screen's context: this is one video, one decision. The sheet is
therefore grip → trimmer → readout → opt-in → import, with nothing above it.
(The imported video joins the gallery's strip afterward, on the gallery sheet.)

## The seek — how you reach 55:30 in an hour

Deep zoom lets you *land* on a time but gives no way to *get there*. **Tap either
time in the preview row; a clock picker opens on it.** Flick minutes to `55`,
seconds to `30`, tap **Set**. The view jumps there at the current zoom and the
range becomes `55:30–55:32`.

- **One gesture to open**, then the standard picker. It is exact, it is identical
  for a 5-second clip and an hour, and it is the accessible path — a screen reader
  reads the time, and there is no sub-pixel handle to hit.
- The picker is the Android clock-column idiom (h / min / sec), because that is
  the native way to choose a precise value.
- **A whole-clip scrub bar was rejected.** At any scale a bar is coarse — on an
  hour a tap lands within ~11 seconds, so it cannot reach 55:30 — and it would be
  a second timeline beside the trimmer. One direct seek is enough.

## The trimmer — handles, zoom, preview

- **Two 44dp handles, one per end**, constant at every zoom. The *time* under them
  changes, never their size.
- **Pinch to zoom** the timeline, plus `Fit`, `−`, `+` and trackpad wheel. Stops
  are powers of two. The indicator names the span and the factor:
  `14s spans the bar · 256×`.
- **A live preview of the frame under each handle**, and a `‹ ›` one-frame
  stepper on each card.
- **The clip length is on the axis** (`0:00 of 1:00:00 0:14`). There is no
  separate duration chip; one number, one place.
- **No wheel for fine trim** (the wheel belongs to the seek): at the deepest zoom
  the bar is already ~1.3 frames per dp, so the steppers cover the last frame.

## The scaling arithmetic, and whether an hour is reachable

Design width **320dp**, handle **44dp**. A 2s window is grabbable once it is at
least one handle wide:

```
2s × (320dp / span) ≥ 44dp   →   span ≤ 14.5s
```

The deepest stop is **14.0625s** (`3600 ÷ 256`) so the factor is a clean power of two.

| clip | span at 1× | 2s window at 1× | deepest span | deepest zoom | 2s window at deepest |
|---|---|---|---|---|---|
| 5s | 5s | 128dp | 5s | 1× | 128dp |
| 1 min | 60s | 10.7dp (overlaps) | 14.06s | 4× | 45.5dp |
| **1 hour** | 3600s | **0.18dp (useless)** | **14.06s** | **256×** | **45.7dp** |

**An hour is reachable at 256×.** The suggested **120× is not enough**: 30s on the
bar leaves a 2s window 21dp wide and the handles overlap by half. 128× still
overlaps; **256× is the first stop that clears the 44dp handle**. Clips longer
than ~20s open at their deepest stop; `Fit` returns to the whole clip.

## Long-running jobs — over 30 seconds

A depth pass that takes more than **30 seconds** of server time is a job, not a
wait. The framing changes at that line:

| server time | caption | button | line |
|---|---|---|---|
| ≤ 30s | `Import` | `Import` | `Import takes about 19s` |
| > 30s | **`Job`** | **`Start`** | `This runs as a job on the server. You can leave — it keeps going.` |

The estimate is unchanged and still true — a 4s range is `about 34s`, the hour's
whole clip is `about 7.5 hr` — but past 30s the presentation carries it: this is
not a spinner you wait behind. The number is stated the same way at every length;
it is never coloured or emphasised to signal that we think it is too long.

While the job runs, the sheet says three true things about where the work is:

- **Where the result lives:** `Saved in the server's output folder.` The app never
  sees a path — `ComfyApi` uploads, submits, polls `/history`, then fetches the
  result *by name* from `/view` — so it must not promise a file on the phone.
- **That it survives interruption:** `Closing the app does not cancel it.` The run
  is server-side; `DepthGenDialog` persists the prompt id and `resumePending` asks
  the server what happened when the app returns.
- **How to recover by hand:** `If it is interrupted, add the finished map later
  with Choose a map — you will need access to that server's output folder.` The
  affordance already exists (`fix_choose_map`); nothing is invented.

The last clause is the honest limit: recovery assumes the user can reach that
server's output folder. If the server is not theirs, the file may not be
retrievable — so the copy states the assumption rather than promising a file.

### The whole clip stays offered — and the sheet does not judge it

The hour's whole-clip pass is **7.5 hr**. It is offered, uncapped, because it is
the user's server to spend. The sheet states the duration as a **fact** and does
not warn, advise, or suggest a shorter range; there is no amber, no "anyway", no
advisory sentence. Reliability comes from the facts (how long, where, survival,
recovery), not from steering.

## The device decides the budget — nothing is a constant

```java
am.getMemoryInfo(mi);          // mi.availMem — free now
am.getLargeMemoryClass();      // the real per-app heap ceiling
bytesPerFrame = w * h * 4 * 2; // colour + depth, RGBA
budget        = min(maxHeap * 0.6, availMem * 0.3)
```

The trimmed range is resolved against the budget on a resolution ladder
(`720p → 540p → 480p → 360p`), not against a fixed frame count. On the reference
device (512 MB heap, 1.9 GB free → ≈307 MB) a 2.0s range resolves to **540p**;
drawn out to the cap it drops to **360p**, to **720p** on a large phone and
**360p** on a small one.

**That ladder is the render texture, not the depth pass.** The relief grid is
**256**, so video depth is generated at **257×257**. `DepthAnythingV2Preprocessor`
resizes so the long edge equals `resolution`, and the still default of **1024**
would throw away ~94% of each frame's work for video. The mockup has no
resolution control for the depth pass and must not imply the still default: the
`540p`/`360p` in the readout is the texture, a different axis.

## Copy rules (a first-class requirement)

- **Inform. Do not steer.** This is the rule the copy was rejected twice for
  breaking, so it is written here in full and must not be re-derived:
  - **No advice.** Delete sentences like *"A pass this long is usually not what
    you want"* and *"a shorter range prepares far faster."* Both halves are us
    having an opinion about the user's choice, or telling them what they already
    know. **Do not replace them with another version of the same advice.**
  - **No "anyway".** `Start anyway` was `Start` with a judgement bolted on — it
    says *this was advised against* The user chose; the button is the action.
    It is **`Start`**.
  - **The estimate is a fact, not a warning.** A long duration is stated plainly;
    it is not coloured amber, not louder than it needs to be to be read, and not
    distinguished from a short one except by the number. Neutral emphasis for a
    long value is fine; a judgement is not.
  - **Say where and how, not what to do.** The facts the long-job state carries:
    how long, where the result lives (the server's output folder), that it
    survives interruption, and how to recover it by hand (`Choose a map`).
- **Never show the arithmetic.** The budget formula, the heap/free figures and the
  frame totals were in the sheet and are deleted. The user gets one consequence:
  the resolution in the readout.
- **A visible control is not explained.** The pinch/drag instruction line is gone.
- **Say each thing once.** The battery explanation appears once per state.
- **Delete notes to ourselves.** "Opt-in", "nobody lands on it by accident",
  "collapsing never cancels", "your old wallpaper is untouched" are all gone.
- Measured: the sheet is **54 words** on the 5s and 1 hour panels, **69** on the
  1 min panel (its default range is already a job, so it carries the job line),
  and **53** on Long job — down from ~188–207.

## One import flow — the screen depends on WHAT, not HOW

There is **no separate share screen**. Add-a-video and share-a-video produce the
same sheet; the only difference is whether the system picker ran first.

| entry | behaviour |
|---|---|
| **Add a media** (gallery `＋`) | The system picker runs first, then this sheet. **The main path.** |
| **Share a video into the app** | The **same sheet** — the video is already chosen, so the picker is skipped. |
| **Share a photo into the app** | **No sheet** — a photo has nothing to decide; it imports straight away. |
| **A photo or video already in the gallery** | **Never.** This is a video-import screen only. |

## The flow

```
Add a media (＋) ─▶ system picker ─┐
Share a video   ──────────────────┴─▶ Import sheet  (one screen)
                                        │
                                        ▼
┌─ Import sheet ─────────────────────────────────────┐
│  TRIM                    14s spans the bar · 256×   │
│  ┌ Start [0:00] ┐  2.0s  ┌ End [0:02] ┐             │  ← tap a time → clock picker
│  │ ►frame       │        │ ►frame     │             │
│  [——|=========|——]  handles 44dp, constant          │
│  0:00            of 1:00:00            0:14         │
│  [ Fit ] [ − ] [ + ]                                │
│  2.0s · 0:00 → 0:02 · 540p · Living photo — sleep   │
│  ▸ Use the whole clip · 1:00:00                     │
│  IMPORT   Import takes about 19s                    │
│           [ Cancel ]  [ Import ]                    │
└─────────────────────────────────────────────────────┘
        │ server time > 30s  →  caption "Job", button "Start"
        ▼
   Job · 62% · about 3 min left
   Saved in the server's output folder. Closing the app does not cancel it.
   If interrupted: add it later with Choose a map (needs the server's output folder).

Share a photo ─▶ import, no sheet
```

## Strings, named for `strings.xml`

Placeholders in `%1$s` form. An em dash is `—`.

### Trimmer

| resource | string |
|---|---|
| `video_import_title` | `Video` |
| `video_caption_trim` | `Trim` |
| `video_zoom_indicator` | `%1$s spans the bar · %2$d×` |
| `video_zoom_fit` | `Fit` |
| `video_zoom_out` | `−` |
| `video_zoom_in` | `+` |
| `video_axis_of` | `of %1$s` |
| `video_preview_start` | `Start` |
| `video_preview_end` | `End` |
| `video_step_back` | `One frame earlier` |
| `video_step_forward` | `One frame later` |

### Choose a time (the seek)

| resource | string |
|---|---|
| `video_time_set_start` | `Set start` |
| `video_time_set_end` | `Set end` |
| `video_time_hr` | `hr` |
| `video_time_min` | `min` |
| `video_time_sec` | `sec` |
| `video_time_set` | `Set` |
| `video_time_cancel` | `Cancel` |

### Readout

| resource | string |
|---|---|
| `video_readout_living` | `%1$ss · %2$s → %3$s · %4$s · Living photo — the phone can still sleep` |
| `video_readout_wallpaper` | `%1$s · Video wallpaper · 30–80 mA · never sleeps` |
| `video_back_to_loop` | `← Go back to a living photo` |

### The whole-clip opt-in

| resource | string |
|---|---|
| `video_whole_line` | `Use the whole clip` |
| `video_whole_body` | `Plays live from two decoders. 30–80 mA, and it can never sleep.` |
| `video_whole_keep` | `Living photo` |
| `video_whole_confirm` | `Use the whole clip` |

### Import, and the job it becomes past 30 seconds

| resource | string |
|---|---|
| `video_caption_import` | `Import` |
| `video_import_estimate` | `Import takes %1$s` |
| `video_import_button` | `Import` |
| `video_import_cancel` | `Cancel` |
| `video_job_caption` | `Job` |
| `video_job_line` | `This runs as a job on the server. You can leave — it keeps going.` |
| `video_job_start` | `Start` |
| `video_job_running` | `Running as a job · %1$d%%` |
| `video_job_running_left` | `%1$s left` |
| `video_job_whole` | `Whole clip · %1$s` |
| `video_job_saved` | `Saved in the server’s output folder. Closing the app does not cancel it.` |
| `video_job_recover` | `If it is interrupted, add the finished map later with Choose a map — you will need access to that server’s output folder.` |
| `video_job_background` | `Run in background` |

## Open questions (left visible in the mockup)

1. **Should the binding loop be 2s or 5s?** It sets the deepest useful zoom
   (2s → ~14s on the bar; 5s → ~35s is enough). It is one constant.
2. **Should long clips open zoomed, or at fit?** They currently open at the
   deepest stop so the handles are usable on arrival; `Fit` shows the whole clip.

**Decided here:** the 60-second cap stays **removed**. A whole-clip pass is framed
by its length — past 30 seconds it is a **job** — and the sheet states the time
without judging it (see *Inform. Do not steer*). The third old question is
resolved, not open.

## What this design deliberately does not do

- It does not draw a filmstrip on this screen; it is one video, one decision.
- It does not sample, and it does not remove start/end.
- It does not cap the whole clip at a minute.
- It does not add a second timeline (scrub bar); the seek is direct.
- It does not let the user pick a mode or a resolution.
- It does not show the budget arithmetic, method narration, or notes to ourselves.
