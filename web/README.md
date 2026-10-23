# `web/` — the Basrelief gallery

A static gallery and viewer for the files the rest of this repository makes. Drop
a packed depth PNG on the page and it becomes a relief you can move; drop a
`.splat` cloud and it becomes a point cloud you can move; a relief can be saved
back out as a packed PNG. **Nothing is uploaded, stored or remembered** — the
`File` objects in this tab are the gallery, so a refresh empties it.

The product shape is *"someone sends you a PNG and you drop it on a page."* There
is no account, no server and no cloud library, which is also why this exists: no
Android app can re-bind its own live wallpaper, so a file you can send someone is
the distribution the platform permits.

## What a file is

The gallery decides each file's kind from the file itself, in one classifier
(`src/media.ts`). The card badge, the header count and the viewer all read that
one answer.

| what you dropped | how it is known | what opens |
|---|---|---|
| a packed PNG with a `dePH` / `dEPt` chunk | PNG signature + the reader | **relief** — displaced by its depth map |
| a plain PNG (or other decodable image) | the reader says no chunk | **photo** — shown flat |
| a `.splat` cloud | `.splat` extension + a length that is whole 32-byte records | **cloud** — a real Gaussian splat cloud, drawn sorted |
| `basrelief_<tag>.splat.json` | `.splat.json` extension | nothing; it is the **lens sidecar** for the cloud beside it |
| unreadable / still being read | neither | nothing; the card says why |

### The three states, and why there is a third

- **has depth** — a well-formed chunk was read and its dimensions match the
  photo. The badge says `depth`.
- **no depth** — a decodable image with no chunk. The badge says `no depth`.
  This is a fact, not a default.
- **could not be read** — the file's bytes are not here yet, its image did not
  decode, its chunk is malformed, or its depth map is a different size from the
  photo. The badge says `unreadable` or `reading…`.

A file that is still being read must never present as “no depth”: a half-read
file has not answered. That is the Android app's `DepthSource.UNKNOWN` /
`SplatSource.UNREAD` rule, and it applies equally to a cloud whose length is not
whole records.

A depth map whose dimensions disagree with the photo is **refused, not
stretched**. `contract/deph/whole-png.png` is exactly that case (a 3×2 depth
under an 8×6 photo): the reader accepts the payload because the contract says a
whole PNG is passed through, and the app layer refuses the pairing.

## The format

A packed PNG is an ordinary, viewable photograph with one extra **ancillary**
chunk — `dePH` or `dEPt`, matched case-insensitively over four ASCII bytes —
carrying the depth map. Because it is ancillary, every normal image viewer still
shows the colour image.

The payload is either:

1. a **complete grayscale PNG**, self-describing and returned byte-for-byte; or
2. exactly `width × height` bytes of **raw 8-bit grey**, row-major, with the
   dimensions taken from the outer `IHDR`.

Refusals are deliberate: a raw payload of the wrong size, an empty chunk, a raw
payload placed *before* `IHDR` (so it claims no dimensions), and a non-PNG are all
refused. The fixtures and their stated failure modes live in `contract/deph/`;
this reader is the contract's third implementation, beside
`DepthPng.java` and `reference/webgl-port/src/png-depth.js`.

### Writing one

`src/pack.ts` is the format's writer, and the gallery's **Save packed PNG**
action uses it. It takes the colour image and the depth map it already read and
produces one PNG:

- **a PNG input is never re-encoded** — the output is the dropped file's own
  bytes with one `dePH` chunk inserted, so the colour is exactly what was sent;
- the chunk goes **immediately before the first `IDAT`, after `IHDR`** — a raw
  payload in front of `IHDR` claims no dimensions, and a chunk after `IDAT` is
  missed by readers that stop walking at the image data;
- a file that already has a depth chunk gets it **replaced**, so there is never
  more than one;
- the **CRC** is CRC-32 over the chunk's type and data. This page's reader never
  checks one, which is why `contract/deph/cases.json`'s `pack` block pins the
  chunk's exact bytes and the output's SHA-256, and `src/pack.test.ts` also
  recomputes the CRC with `node:zlib`.

A non-PNG colour (a JPEG) can only be packed after a canvas re-encode, because a
`dePH` chunk needs a PNG to live in. That path is written and the viewer says
when it was taken ("the colour was re-encoded…") — today it is unreachable,
since a relief must carry a chunk and only a PNG can. It is what the Generate
action will use on a JPEG photo.

**What a wrong answer looks like:** a bad CRC is depth on one machine and none on
another (strict readers drop the chunk); a chunk after `IDAT` is the same for a
reader that stops early; a re-encoded colour is a softer picture nobody can
compare side by side; and a depth payload of the wrong length is a file the
writer claimed to make and the reader refuses — so the writer refuses a
mismatched depth map before writing anything.

The round trip is through two readers: `src/pack.test.ts` packs the fixture,
asserts the result is byte-identical to `contract/deph/pack-raw.png`, reads it
back with `src/deph.ts`, and reads it again with
`reference/webgl-port/src/png-depth.js` (imported from its real source in Node),
comparing the depth pixels the reference port re-encodes.

A `.splat` cloud is a sequence of 32-byte records:

| offset | bytes | field |
|---|---|---|
| 0 | 12 | position `x, y, z` — float32 ×3 |
| 12 | 24 | scale `sx, sy, sz` — float32 ×3, already `exp()`-decoded |
| 24 | 4 | colour RGBA, 8-bit each |
| 28 | 4 | quaternion, 8-bit each |

The count shown on a card is `bytes / 32`, so it costs nothing to read. A length
that is not a whole number of records is **unreadable**, not a shorter cloud — and
the renderer refuses the same file a second time, so a truncated cloud cannot be
drawn by either path.

## The cloud renderer

`src/cloud.ts` is a WebGL2 port of the Android app's `SplatRenderer` +
`SplatProjection`, and the look is the app's:

- one card per splat, six vertices of `drawArraysInstanced`, everything derived
  from `gl_InstanceID` — there is no vertex buffer and no per-frame allocation;
- the **full covariance** chain (`R S Sᵀ Rᵀ`, through the view rotation and the
  perspective Jacobian, to a conic), not a screen-space circle;
- a **back-to-front counting sort** into 262,144 buckets, re-derived only when the
  camera moves past the app's `1e-3` tilt gate — alpha blending is not commutative,
  and a sorted cloud is the difference between parallax and speckle;
- the **2D floor** (`0.5` of the on-screen cell), the **near-plane pull-back**, the
  **alpha-extent clip** and the **radius clip**, at the app's own constants;
- **no backdrop.** The gaps a tilt opens are black, which is the app's decision:
  two fills were tried and rejected.

**The colour path is integer, and that is not a detail.** The record's 32 bytes are
uploaded as two `RGBA32UI` texels and unpacked with integer shifts. Read as
`RGBA32F` — what the old prototype does — the four colour bytes of an opaque
bright splat are a **NaN**, and Mali canonicalises it, so the splat draws flat
white with no error anywhere. `contract/splat/` pins that.

**The lens comes from the sidecar, or it is the app's fallback and the viewer says
so.** A cloud's screen size is proportional to the focal it was reconstructed
through, so drawing it through the wrong one is a plausible-looking wrong picture.
Drop `basrelief_<tag>.splat.json` beside the cloud (in either order) and its
`focal / height` is used; without one the viewer draws the app's documented
no-lens look (64.3° vertical) and states that on the dock, because "no sidecar" and
"a sidecar that claims no lens" are different facts.

At rest — no tilt, no origin — the picture is **exactly the photograph**: the eye
is the cloud's own source camera, and the tilt is a turntable about the cloud's
surface under the centre. `src/splat.test.ts` asserts that through the matrices
the renderer uploads, at every origin, pivot depth, aspect and lens.

A cloud's cost is its count and the machine's: the demo is ~150k splats, and the
app's ladder puts 1M at ~5 fps on a 2018 phone. Nothing is capped; the viewer names
the count, and says so when a cloud is dense enough for that to matter.

## Running it

```
cd web
pnpm install
pnpm dev        # HTTPS on :5173
pnpm build      # typecheck + static bundle in web/dist
pnpm preview    # serve the built bundle
pnpm test       # contract/deph (reader and writer), media states and contract/splat, under plain Node
pnpm typecheck  # tsc --noEmit
```

`pnpm dev` serves **HTTPS** (a self-signed certificate, accepted once). That is
not decoration: browsers expose `deviceorientation` only in a secure context, and
a phone opening the LAN address is not localhost. Use the network URL Vite prints
to try the gyro on a real device.

**`file://` will not work.** ES modules and fetch both refuse it, so opening
`index.html` by double-clicking shows a blank page. Serve it (`pnpm dev`,
`pnpm preview`, or any static host); `pnpm build` produces a static `dist/` with
no server-side requirement.

### Using the viewer

- **Drag** on the photo to swing the camera; releasing lets it settle back to
  rest. A cloud does the same: the eye turns about the cloud's own surface under
  the centre, so the subject holds and the background swings.
- **Use device tilt** asks for orientation permission where the browser requires
  it (iOS) and drives the same motion from the sensor. If the browser exposes the
  API but emits no events, the hint says so and dragging still works.
- **Depth** sets how far the relief is displaced; **Motion** sets how far the eye
  swings (for a cloud, Motion is the same number the app calls Motion). **Reset
  view** returns both, the tilt and the neutral pose.
- **Save packed PNG** (a relief only) writes the colour and the depth map it read
  into one PNG and downloads it as `<name>.packed.png`. A photo says why there is
  nothing to pack, and a cloud says a `.splat` is not a PNG — neither leaves a
  dead button.
- **← Gallery** or **Escape** closes the viewer.

The picture fills the viewport (cover, with the reference port's 8 % crop), as
the wallpaper does. There is no zoom or pan yet, so a landscape photo on a
portrait phone is cropped — rotate the phone for the whole thing.

## What it takes from the reference port

`src/relief.ts` is a port of `reference/webgl-port/src/scene.js`, which is the
only existing implementation of this effect:

- the plane is displaced by `depth/255 - 0.5`, scaled by `intensity × plane
  height`, so perceived depth is independent of zoom and viewport aspect;
- per-vertex normals are recomputed from the displaced surface and shaded by an
  ambient + directional pair;
- the camera is fixed at 45° and `CAM_DIST`, the plane is fitted with
  `CROP_MARGIN`, and the eye swings by
  `motion × MOTION_GAIN × CAM_RANGE_FRACTION × planeHeight`;
- the depth map is blurred by a **separable box in float**, radius
  `width / 78`, before displacement. Re-quantising that blur to 8-bit brings the
  banding back. The constant is the same one `ReliefMesh.java` and the reference
  port use, and `scripts/check-parity.mjs` checks all three.

What is *not* taken: the port's structure (this app creates one scene per open
item and destroys it with the viewer), its tap-to-set-orbit / pan / zoom
gestures, and its frame-rate-dependent smoothing (`lerp 0.08` per frame). This
uses the Android app's time constant (`tau = 0.2 s`) so the feel is the same at
60/90/120 Hz. The gallery's media vocabulary, the reader's return shape (raw grey
+ dimensions, or whole PNG + its own dimensions, as Java does) and the viewer
seam are this app's own.

The cloud renderer is a port of the **Android app**, not of the reference port and
not of `.design/prototype/ab/3d/splatline/`. The prototype's projection is the same
maths, but its upload is `RGBA32F`, which is the colour path this app replaced. What
is taken from it is nothing; what is taken from `SplatRenderer` is listed under *The
cloud renderer* above.

## What it does not do yet

- **Generate depth** — the next task, and a client of the ComfyUI node rather than
  anything in the browser. The writer above is its prerequisite: a generated depth
  map plus a colour image is exactly what it packs.
- **Zoom, pan, choosing an orbit centre, and the Depth multiplier** — the cloud is
  drawn through its own camera at the node's own depth; the relief has the same
  omissions. Both are the app's features and neither has a control here.
- **The cloud look knobs** (Size, Blur, Resolution) — the gallery draws the app's
  defaults: a `0.5`-of-the-cell floor, no post-blur, no offscreen scale.
- **A cloud's colour is not colour-managed.** The baked bytes go to the framebuffer
  exactly as the app's shader writes them, so the picture matches the app rather
  than an `sRGB`-converted one.
- **Accounts, a server, sync with the phone** — none, by design.
- **Persistence** — no IndexedDB, no localStorage; reload and the gallery is
  empty.

## Layout

```
web/
  index.html          page shell (no framework)
  src/main.ts         file intake: picker + whole-window drag & drop
  src/gallery.ts      the cards, thumbnails, counts, sidecars and per-file inspection
  src/media.ts        the one media classifier + formatting (pure, tested)
  src/deph.ts         the dePH/dEPt reader (contract/deph third copy)
  src/pack.ts         the dePH writer + the browser colour re-encode (pure half tested)
  src/splat.ts        the .splat record, the cloud camera, the lens, the sort (pure, tested)
  src/cloud.ts        WebGL2 splat renderer (ported projection, integer texels)
  src/viewer.ts       one dispatch point: relief | photo | cloud | message
  src/relief.ts       three.js relief renderer (ported camera model)
  src/tilt.ts         deviceorientation → a smoothed tilt target
  src/deph.test.ts    the dePH fixtures, under plain Node
  src/pack.test.ts    the writer's fixture, chunk/CRC and both round trips
  src/media.test.ts   the three states, without a browser
  src/splat.test.ts   contract/splat + the camera, lens, sort and floor
```

CI's `web-app` job runs `pnpm test`, `pnpm typecheck` and `pnpm build`; the
`guards` job's `scripts/check-shaders.mjs` compiles the two GLSL shaders in
`src/cloud.ts` too, so a shader error is a red build rather than a viewer that
draws nothing.
