# Video + depth — the plan

Written 2026-10-04, after the research in `TODO.md` and two rejected
approaches. This is the document to read before writing any video code.

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

Two ordinary files. No custom container, no parser, no dependency. **The pair
invariant is the same one the bundled demo already has**: ship one without the
other and it renders flat with no relief and no error — so never store one
without the other, and say so in the status line.

---

## 3. The offline pass at import

Everything hard happens **once**, at import, on the ComfyUI server. Runtime is
then decode-and-upload.

1. **Downscale** to the render size. A wallpaper does not need 4K.
2. **Depth per frame**, offline.
3. **Temporal smoothing** — the trap. Per-frame monocular depth is noisy frame
   to frame, and unsmoothed it strobes: the relief shimmers even when nothing
   moves. Smooth across a small window before quantising.
4. **Float blur, never 8-bit re-quantised.** `DEPTH_BLUR_FRACTION`'s invariant
   applies to video identically: 8-bit terraces become contour lines under
   grazing light. This must stay in float until the final encode.
5. **Encode the depth video** as greyscale.
6. **Poster frame** — the still shown in the gallery, and the fallback if the
   video cannot decode.

**Order matters:** the blur has to come after smoothing and before encoding,
because encoding is the quantisation step.

---

## 4. Runtime: decode in lockstep

Two decoders, one clock. The colour and depth frames must be the **same
frame**, or the relief slides against the image.

- `MediaExtractor` + `MediaCodec` per track; release both on pause.
- **Never** drop or skip depth frames to catch up — drift is worse than a
  dropped frame, because the whole picture is wrong rather than briefly stale.
- Decode **ahead** into a small ring, so a slow frame doesn't stall the draw.
- **The engine's lifecycle is the hard part.** A wallpaper engine is torn down
  and rebuilt routinely, and `AGENTS.md` is explicit that nothing may be cached
  across its lifetime. So decoders are created in `onSurfaceCreated`/visibility
  and **released on hidden**, not parked.

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

**Spike first, before any of it:** fragment-shader relief over two
synchronised videos on the OnePlus. If that does not look right, the phase
plan changes and no feature work was wasted.

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
- **Float blur**, never re-quantised.
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
