# Video + depth — the plan

Written 2026-10-04, after the research in `TODO.md` and two rejected
approaches. This is the document to read before writing any video code.

Revised 2026-10-05, after the device spike. The spike was wrong twice and both
failures are recorded here rather than tidied away: it read §3's float rule as
"blur at import" when that rule is about the blur's *arithmetic* and is unchanged
(§3), and it measured lockstep with a pacer instead of a clock (§4). Numbers
added in this revision are measured unless they say otherwise; device
measurements are a **Pixel 7 Pro, sdk 37**.

---

## 1. The decision that shapes everything: depth per frame

The user rejected 2.5D (one depth map per clip). So every frame carries its
own depth. Everything below follows from that one requirement, including the
renderer change — it is not a feature that can be bolted onto the still
pipeline.

**Why it's expensive, stated once so it isn't rediscovered:**

- Per-frame displacement **cannot** be a CPU mesh rebuild. `ReliefMesh.build`
  measured **6.6 ms at grid 256 on a host machine**; at 30 Hz on a phone that
  is not close. The still path builds a mesh once per photo, which is why it
  gets away with it.
- So the relief must move **into the fragment shader** — a depth texture
  sampled per pixel, with normals derived in the shader. That is a different
  renderer from the one shipping today.
- Live displacement in a **vertex** shader is not an option: GLES2's minimum
  vertex texture units is zero. That would need GLES3.

---

## 2. The format

Nothing on the platform carries depth video:

| format | verdict |
|---|---|
| Android DDF | still-image spec, API 29, **write-side only** |
| MP4-AT | real container, **no platform reader** (AOSP `MPEG4Extractor`: zero refs; Media3 1.6+ or a dep) |
| Apple spatial video | **no depth track** |
| APV | wrong tool |

So we define our own, out of parts the platform already reads:

- **`<name>.mp4`** — colour, ordinary H.264/HEVC, played by `MediaExtractor`
- **`<name>-depth.mp4`** — greyscale, same frame count, same frame rate
- **`<name>-normal.mp4`** — the shading normal (§3 step 5), timed like the depth
  video or it is a second sync problem

Ordinary files. No custom container, no parser, no dependency. **The pair
invariant is the same one the bundled demo already has**: ship colour without
depth and it renders flat with no relief and no error — so never store one
without the other, and say so in the status line. The normal is the one file
whose absence is legal: no normal is flat lit, which the status line says
outright.

---

## 3. The offline pass at import

Everything hard happens **once**, at import, on the ComfyUI server. Runtime is
then decode-and-upload.

1. **Downscale** to the render size. A wallpaper does not need 4K.
2. **Depth per frame**, offline.
3. **Temporal smoothing** — the trap. Per-frame monocular depth is noisy frame
   to frame, and unsmoothed it strobes: the relief shimmers even when nothing
   moves. Smooth across a small window before quantising.
4. **Normalise depth once, across the whole clip.** Normalising each frame by its
   own min and max makes the relief scale *breathe* as the scene's depth range
   shifts — the same subject near at frame 40 and far at frame 41, with the
   camera still. One min/max for the clip, applied to every frame. This is a
   decision rather than a measurement: breathing is the wrong thing not to do.
5. **A normals video.** MoGe already emits `normal_opengl` for a still
   (`NormalSource.SUPPLIED`); per frame it emits one the same way. Shading then
   samples a normal texture exactly as the still path does, and the per-frame
   **CPU face-normal accumulation disappears entirely** — the largest runtime
   cost still on the table, and it is removable. It also keeps the architecture
   as `AGENTS.md` requires: shading is supplied, never derived. A clip with no
   normal is flat lit, which is a deliberate state and not a broken one.
6. **Resample to the grid** — 257×257, the points the mesh actually reads — and
   **encode the depth video as 8-bit greyscale**.
7. **All-intra, or a short GOP; identical timing on every video.** A looping
   living photo has to be enterable at any frame, so every frame should be a
   clean entry point. All of the files need the same fps, the same frame count
   **and the same GOP structure**, or their PTS will not line up — which is the
   sync bug fixed at encode time rather than compensated for at runtime.
8. **Poster frame** — the still shown in the gallery, and the fallback if the
   video cannot decode.

### The blur runs at RUNTIME, on the decoded bytes

This list used to carry a step that said *"Float blur, never 8-bit
re-quantised … `DEPTH_BLUR_FRACTION`'s invariant applies to video identically …
must stay in float until the final encode."* **That rule is about the blur's
arithmetic and it has not changed: float in, float out, or the terraces come
back.** What it never said — and what the spike read it as though it did — is
*where* the blur runs, and there the rule runs the other way: **after
quantisation, never before.**

Terraces are created *by* the quantisation, so a blur applied before it cannot
remove them; only a blur applied to the already-decoded 8-bit values takes them
out. `AGENTS.md`: *"re-quantising the result to 8-bit recreates the terraces and
the artifact returns."* The spike blurred in float at import, encoded 8-bit and
never blurred again — precisely the case that invariant forbids, because the
terraces were built after the only blur that existed. Correct arithmetic in the
wrong place is still the artifact.

The signature says where the call belongs:
`ReliefMesh.blurredField(byte[] depth, int dw, int dh, int grid)` — **`byte[]`**.
It blurs the *decoded* 8-bit values. For a still those bytes are the PNG's; for
video they must be the decoded frame's. Same call, same point in the pipeline.

**So the pipeline is:**

| | steps |
|---|---|
| **import** | depth per frame → temporal smooth → normalise across the clip → normals video → resample to the grid (257×257) → encode 8-bit |
| **runtime** | decode → `blurredField(bytes, 257, 257, 256)` → upload as float |

**The cost, so nobody optimises it away.** The resample is import-time because
the mesh only ever reads the grid's points; doing that 1M-pixel resample per
frame measured **82 ms** on the Pixel 7 Pro and was removed. What is left at
runtime is a separable box blur over 66k floats at 257×257 — **~1 ms**. The
ceiling it has to fit under is the measured **60 fps at 1440×3120** (§5).

**The general rule this is one instance of:** everything that is a function of
one frame moves to import; the blur stays at runtime because it is a function of
the frame **and** of the quantisation that frame passed through.

**Conflicts left standing on purpose.** Step 5 contradicts §1 and §5 as they
are written, which still describe a fragment-shader relief with normals derived
from their neighbours, and it contradicts `MOGE-OUTPUTS-PLAN.md` §5, which rules
that a video stores no normal — 3× the bytes, 4:2:0 corrupts a vector field, and
a third decoder to hold in lockstep. `AGENTS.md` and the shipping renderer settle
the first question the other way (GLES3 vertex displacement, the normal as a
texture), and §4 makes the third ring a non-issue: it is one more ring asked the
same question with the same number. §1, §5 and `MOGE-OUTPUTS-PLAN.md` §5 are
what need correcting, deliberately and together.

Two more stale claims sit in those same two sections, left untouched for the
same reason: §1's *"live displacement in a vertex shader is not an option"*, and
§5's *"a **separate renderer**, not a mode"*. The first was overtaken by the
spike's own result (§5); the second by the shipping renderer, which is one class
with a GLES2/GLES3 build switch rather than two. `AGENTS.md` says the code and
the log win over a written rule — but that is a deliberate edit, not a quiet one.

---

## 4. Runtime: decode in lockstep

Two decoders, **one clock**. The colour and depth frames must be the **same
frame**, or the relief slides against the image. Here is what violating that
looks like, because the requirement above is easy to claim and hard to have
been honouring.

**The spike shipped a pacer and called it a clock.** It released a `Semaphore`
once per drawn frame and reported `drift=0` on every sample, while the relief
was visibly pinned to where the subject had been two or three frames ago. Both
counters advanced together because the hand-offs really *were* in lockstep —
**counting hand-offs cannot detect an asymmetry in latency.** The cause was two
paths that did not cost the same:

| stream | path | latency |
|---|---|---|
| colour | `releaseOutputBuffer(.., true)` → BufferQueue → `updateTexImage()` | **1–3 frames**, in a queue nobody drained |
| depth | `getOutputImage()` → CPU copy → texture upload | **zero** |

The colour decoder ran ahead and kept the lead. The spike's own comment admitted
the defect while reporting a metric immune to it: *"codec `decoded()` is not
used: the colour SurfaceTexture queue makes it lead by a constant."* A metric
dropped because the other one misbehaves is not a second opinion.

**The rule: pair by timestamp, never by count.** Both streams leave the decoder
as **CPU bytes tagged with the frame's own `presentationTimeUs`**, and the
renderer asks both rings **the same question with the same number** — the newest
frame at or before time *t*. A skew then cannot occur rather than merely being
unlikely, because there is nothing in either path whose latency the other does
not share. Anything queued between a decoder and the sampler (a `SurfaceTexture`,
a double-buffered surface) is latency exactly one stream has, and nothing
counted downstream of it can see that.

- **Never** drop or skip depth frames to catch up — drift is worse than a
  dropped frame, because the whole picture is wrong rather than briefly stale.
- **A full ring blocks the decoder.** That is the back-pressure this section
  asks for, and it is why a slow draw stalls the decoder instead of
  desynchronising it. The ring stays small on purpose: every frame held is 3.9 MB
  of colour, and a deeper ring only lets the decoder run further ahead of the
  clock.
- **A loop seam clears both rings at once.** A rewind moves both streams
  together, and for a moment neither has a frame at the old clock — so a pair
  chosen at that instant mixes a pre-seek colour with a post-seek depth. The
  same bug in a different place.
- `MediaExtractor` + `MediaCodec` per track; release both on pause.
- **The engine's lifecycle is the hard part.** A wallpaper engine is torn down
  and rebuilt routinely, and `AGENTS.md` is explicit that nothing may be cached
  across its lifetime. So decoders are created in `onSurfaceCreated`/visibility
  and **released on hidden**, not parked.

### What the CPU still does per frame

Decode (colour RGBA, depth grey) → `blurredField` → two texture uploads. That
list is short, and it is there because **a still decodes once and the GPU owns
that texture forever, while a video decodes 25× a second** — so the decode path
is permanently in the loop, and the instinct to move all of it to the GPU cannot
simply be obeyed.

The one copy worth arguing about is colour through the CPU rather than onto a
GPU surface: ~3.9 MB per 720×1366 frame, about **98 MB/s at 25 fps**. It buys
exact timestamp pairing, which is what the clock above is built on. If a later
build wants colour to stay zero-copy on a GPU surface, the record is: **the
clock must then own that decoder's input schedule, not just the sampler.**
Letting a decoder fill a queue and sampling whatever is newest is exactly what
produced a constant two- or three-frame skew while every counter read zero.

---

## 5. The renderer change

This is the real work, and it is a **separate renderer**, not a mode:

- Colour texture + depth texture, both uploaded per frame.
- **Fragment-shader relief**: sample depth, derive the normal from its
  neighbours in the shader (Sobel or a cheaper 4-tap), light it with the
  existing `uLightDir`, apply `Picture` exactly as today so brightness and
  shadow do not fork.
- **`CameraModel` is unchanged** — the tap→unproject→project contract is about
  the camera, not the geometry, and `CameraModelTest` still applies.
- **The mesh path stays** for stills. Two paths is a cost, but the still path
  is measured and correct and the video path cannot reuse it.

**The spike has been run** — 2026-10-05, Pixel 7 Pro, sdk 37 — and it settled
the part that had to be settled before any feature work:

- **60 fps at 1440×3120**, grid 256, **GLES3 vertex displacement**: the real
  displaced geometry, the live path's lighting and `Picture`, not a
  screen-space parallax stand-in, which is what the first attempt drew and why
  it was thrown away.
- **Colour and depth are locksteppable** at that rate.
- **Two bugs it surfaced, neither predicted here**: the BufferQueue latency
  asymmetry that a `Semaphore` reported as `drift=0` (§4), and the blur placed
  before quantisation (§3).

The spike's three files are throwaway and **are to be deleted** — they are in
the tree by accident. What is worth keeping is the numbers above and the two
failures.

---

## 6. Phases

| phase | what | battery |
|---|---|---|
| **0** | stills from video — pick a frame, get a photo | ≈ today |
| **1** | studio-only playback (no wallpaper) | n/a |
| **2** | **pre-decoded "living photo"** — frames decoded at import into a short loop, replayed from textures | ≈ today, and the recommended target |
| **3** | real-time, opt-in per item, **default off** | 30–80 mA just for the decoder |

**Phase 2 is the one to aim at.** It sidesteps lockstep decoding, drift,
codec availability and most of the battery cost, and for footage that is a
2–4 second loop it is visually indistinguishable from phase 3. Phase 3 exists
for genuinely long clips, and it is opt-in because **a video wallpaper can
never sleep while visible** — the thing that makes the still path cheap is
that it does.

---

## 7. What must not be forgotten

- The **pair invariant** — colour without depth is a silent flat render.
- **Float blur**, never re-quantised — and it runs at **runtime**, on the decoded
  bytes, because the terraces it removes are made by the quantisation it has to
  come after (§3).
- **Pair colour and depth by timestamp**, never by count (§4). A counter that
  advances in step with its twin proves nothing.
- The engine is **short-lived**; nothing cached across it.
- **Nothing decodes on the GL thread.** A video load is I/O and CPU; it goes
  on the `Io` executor like every other load, with `loadSeq` deciding the
  winner by sequence rather than arrival.
- **`PrefFiles.resolve`** stores filenames, never absolute paths — a video
  pair is two more filenames and the same rule.
- **Export** (the queued feature) must handle a video pair, or the format is
  write-only and therefore pointless.

---

## Appendix: the unlock transition, resolved (2026-10-04)

Not video, but it was solved in the same session and the findings cost eight
attempts. Recorded here because the code comments will move and this reasoning
should not have to be rediscovered.

### The requirement

On unlock: **hold exactly what was on screen** (the lock screen's framing) and
**glide to home's**. No frame painted at home's framing first. The user's words:
*on unlock nothing should change, only a glide to 1x*

### What works

`isDeviceLocked()` — **not** `isKeyguardLocked()`. Measured across two full
unlocks, `isKeyguardLocked()` read `true` on **every** sample of every cycle,
including after the unlock completed. `isDeviceLocked()` reads `true` on the
lock screen and `false` on home; it is the only reading that tracks the state.

And the **first frame is inherited, not corrected**. The lock engine publishes
its framing process-wide; the home engine, cold-loading while
`!isDeviceLocked()`, glides *from* it. This is the actual fix — the engine never
draws home's own framing at all, so there is nothing to correct. An earlier
version painted home's framing and then glided back, which is the flicker.

### Every signal that does NOT work here

| signal | measured outcome |
|---|---|
| `isKeyguardLocked()` | never changes; useless as a transition signal |
| `ACTION_USER_PRESENT` | **zero deliveries** to a receiver in this service |
| `KeyguardManager.addKeyguardLockedStateListener` | `SecurityException` — `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` is not in the public SDK; calling it from `Service.onCreate` **crashes the wallpaper** |
| `onWallpaperFlagsChanged` | zero fires — two permanently separate engines are never re-tasked |
| lock engine `onVisibilityChanged(false)` | fires *after* home's callback, so a flag set there is consumed before written |
| `Window.setBackgroundBlurRadius` | no blur region — a Dialog is `ty=BASE_APPLICATION`, and the region needs a translucent **floating** window |

`onApplyWindowInsets` **does** fire at the transition and both readings flip
there — but the first-frame inheritance made it unnecessary, since the origin
no longer has to be armed late.

### The rule that kept being violated

*"Don't paint anything instantly — a frame is reached by gliding towards it."*
An instant `apply` is legitimate only for a live gesture (a slider must lead
the finger) or a genuine pair change. Every intermediate attempt failed by
painting something and correcting it afterwards.
