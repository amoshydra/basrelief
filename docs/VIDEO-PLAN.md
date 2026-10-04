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
  sampled per pixel. **With a supplied normal track, never derived** —
  `AGENTS.md`: *shading is supplied, never derived*, and `c7c2578` removed the
  derivation from both backends. A clip with no normal is **flat lit**, a
  deliberate state.
- Live displacement in a **vertex** shader **ships**: GLES3, `ec2dd69`, measured
  60 fps at 1440×3120. GLES2's zero vertex texture units is why the build switch
  exists, not why the path is unavailable.

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

**Resolved 2026-10-05, deliberately.** The two stale claims flagged above have
now been edited rather than left standing: §1's *"live displacement in a vertex
shader is not an option"* (overtaken by `ec2dd69` and by the spike's own 60 fps)
and §5's *"a **separate renderer**, not a mode"* (the shipping renderer is one
class with a build switch). §5's *"derive the normal from its neighbours in the
shader"* is corrected for the same reason and the same rule: `AGENTS.md` forbids
deriving shading. `AGENTS.md` says the code and the log win over a written rule —
but that is a deliberate edit, not a quiet one.

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

**The rule: one clock, and the clock is the pairing.** Both decoders are gated
against the **same wall clock**, so a clock-gated decoder produces frame *N* for
tick *N* by construction. The renderer then **latches both streams on one shared
tick** rather than per drawn frame, which makes the choice of frame identical for
both. There is no per-stream timestamp to match on, so the tick *is* the pairing.

**Gate on ABSOLUTE time, not on the extractor's PTS.** `BufferInfo.presentationTimeUs`
**restarts at zero on every loop**, so a gate of the form `pts > now + lead` is
false from the second loop onward, the gate never blocks again, and both decoders
free-run at whatever rate their own frames cost. Measured before this was fixed:
**82 fps / 17 loops against 15 loops, 7.5 s apart within 20 s.** The absolute time
is `pts + loops × clipDurationUs`.

**The two leads must be the same value.** Different leads cost a steady frame or
two of skew on their own — a *policy* difference that looks exactly like a sync
bug and is invisible until you make them equal and the offset vanishes.

- **Never** drop or skip depth frames to catch up — drift is worse than a
  dropped frame, because the whole picture is wrong rather than briefly stale.
- **A full ring blocks the decoder.** That is the back-pressure this section
  asks for, and it is why a slow draw stalls the decoder instead of
  desynchronising it.
- **Bound the ring by TIME, not by frame count.** A frame count is not a bound at
  all when the streams cost different amounts: a 256×256 depth track and a
  720×1366 colour track sharing a two-frame ring raced **six clip-lengths apart**,
  because one frame of depth costs a fraction of one frame of colour.
- **Gate BEFORE releasing into the surface.** Releasing first renders the frame
  into the `SurfaceTexture` immediately, so every tick spent parked leaves
  another frame sitting there, and a later drain takes the whole burst — never
  clock-checked frame by frame. Measured: frames 1.3 s *ahead* of the clock while
  the gate reported itself waiting.
- **Retire each ring unconditionally.** Gating the trim on "the pair matched" is
  a deadlock wearing a correctness hat: when the streams are apart the pair never
  forms, so neither ring is trimmed, so both fill, so both park forever.
- **A loop seam clears both rings at once.** A rewind moves both streams
  together, and for a moment neither has a frame at the old clock — so a pair
  chosen at that instant mixes a pre-seek colour with a post-seek depth. The
  same bug in a different place.
- `MediaExtractor` + `MediaCodec` per track; release both on pause.
- **A codec call that loses a race with `stop()` must not be fatal.** With three
  concurrent decoders the teardown interleavings multiply; an
  `IllegalStateException` from `queueInputBuffer`/`dequeueOutputBuffer` means the
  codec is being released underneath the thread, which is normal shutdown and
  should end the loop, not kill the GL thread's source.
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
GPU surface: ~3.9 MB per 720×1366 frame, about **98 MB/s at 25 fps**.

### The CPU-plane decode path does not work on this hardware

An earlier draft of this section recommended taking **both** streams to the CPU
as bytes tagged with `presentationTimeUs`, on the reasoning that a CPU handover
removes every queue and therefore every latency. Measured on 2026-10-05, that
path **produces data that is not the video**:

| | on device | the file, all 100 frames |
|---|---|---|
| colour luma mean | 26.3 | 77.2 |
| colour **U** mean | **7.3** | 125.5 |
| colour **V** mean | **7.1** | 130.5 |

The copied luma's first row (`8,8,8,8,0,0,0,81,4,65,0,0`) occurs **nowhere** in
either clip's luma plane, at any byte offset, in any frame — while the `Image`'s
own reported geometry is perfectly self-consistent (`rowStride=256 pixelStride=1`
for a 256×256 track). **A swizzled vendor layout behind a stride that does not
describe it.** Neither device's `media_codecs.xml` mentions it.

Two more, from the same session:

- **`ImageReader` will not convert for you.** Asking for `RGBA_8888` fails on the
  first frame: *"producer output buffer format `0x7fa30c06` doesn't match the
  ImageReader's configured buffer format `0x1`"*. `0x7fa30c06` is
  `YUV_420_888`. MediaCodec decodes to its native format and expects the
  **consumer** to convert — which is why a `SurfaceTexture` gets colour for free
  and an `ImageReader` does not.
- **`YUV_420_888` requires even dimensions.** So the 257² grid **cannot be a YUV
  video at all.** The depth track is stored 256×256 and `blurredField(bytes, 256,
  256, grid)` produces the 257² field at runtime.
- **A Java YUV→RGB loop over 983k px/frame ANRs the process.** Measured, five
  seconds after launch. Any conversion belongs in a shader.

So the CPU is used for the **depth field only**, and colour stays on an external
`SurfaceTexture` with the conversion done by the sampler. That is not a
compromise forced by elegance: it is the only combination measured to work.

### What the format decisions are, and what they cost

- **4:4:4 for the normals video, and it is affordable.** `MOGE-OUTPUTS-PLAN.md`
  §5 is right that 4:2:0 corrupts a vector field, because U and V carry X and Y.
  Its open question — *"4:4:4, which hardware decoders do not universally
  offer"* — is now **measured on two GPUs**: `High 4:4:4 Predictive` configured
  and decoded on an **Adreno 630** (OnePlus 6T, sdk 35) and a **Mali-G710**
  (Pixel 7 Pro, sdk 37). Cost on a 4 s / 100-frame clip: **2.87 MB against
  colour's 1.17 MB.**
- **Store the depth field ASPECT-MATCHED to the grid, not square.** The stored
  field's dimensions are free — `resample(src, dw, dh, …)` indexes it by its own
  `dw`/`dh` in normalised uv — while the *lattice* must be square, because
  `resample` returns `n × n`. For a 2:1 portrait clip, 128×256 is
  **~47% smaller than 256×256 for zero loss**, because the mesh samples 257²
  points and nothing finer survives to a vertex. Square is the worst case for
  bandwidth; **512 is not the lever** — that is the *MoGe inference* resolution
  (`DepthGenDialog.RESOLUTIONS`), which is a different knob entirely.
- **Blur the depth exactly once, at runtime.** `blurredField` takes a `byte[]`, so
  it blurs the values the **decoder** produced, and the terraces are created *by*
  the quantisation the encoder applied. Blurring at import as well is not
  redundant, it is destructive: it collapsed the field from `max 255 / mean 44`
  to `max 101 / mean 13.5`, flattening the relief to nothing.
- **The runtime blur is cheap on the GPU and expensive on the CPU.**
  `ReliefMesh.blurredField` measured **30–42 ms per call on the GL thread** — 775
  ms of GL thread per second at 25 fps — against a 40 ms budget. It was replaced
  by a GPU box filter, `(2r+1)²` taps over the 257² field, into an `RGBA16F`
  target.

---

## 5. The renderer change

This is the real work. It is **one renderer with a per-frame source**, not a
second class: `ec2dd69` is a single `ReliefRenderer` with a `BASRELIEF_RENDERER`
build switch, and `ReliefMesh.blurredField` is shared.

- Colour texture + depth texture, both uploaded per frame.
- **Two shading paths, chosen per source.** Vertex displacement from the depth
  field for the parallax, and/or a **supplied normal track** sampled per fragment
  for the lighting — the normal-mapped path keeps the photo fixed while only the
  shading follows the surface, so a high-contrast silhouette cannot separate from
  its own geometry. Light it with the existing `uLightDir`, and apply `Picture`
  exactly as today so brightness and shadow do not fork. **Never derive the
  normal in the shader.**
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
