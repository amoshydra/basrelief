# `web/` — the Basrelief gallery

A static gallery and viewer for the files the rest of this repository makes. Drop
a packed depth PNG on the page and it becomes a relief you can move; drop a
`.splat` cloud and it is listed and counted. **Nothing is uploaded, stored or
remembered** — the `File` objects in this tab are the gallery, so a refresh
empties it.

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
| a `.splat` cloud | `.splat` extension + a length that is whole 32-byte records | **counted** — the renderer is the next change |
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

A `.splat` cloud is a sequence of 32-byte records:

| offset | bytes | field |
|---|---|---|
| 0 | 12 | position `x, y, z` — float32 ×3 |
| 12 | 24 | scale `sx, sy, sz` — float32 ×3, already `exp()`-decoded |
| 24 | 4 | colour RGBA, 8-bit each |
| 28 | 4 | quaternion, 8-bit each |

The count shown on a card is `bytes / 32`, so it costs nothing to read before the
renderer exists.

## Running it

```
cd web
pnpm install
pnpm dev        # HTTPS on :5173
pnpm build      # typecheck + static bundle in web/dist
pnpm preview    # serve the built bundle
pnpm test       # contract/deph fixtures + media-state checks
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
  rest.
- **Use device tilt** asks for orientation permission where the browser requires
  it (iOS) and drives the same motion from the sensor. If the browser exposes the
  API but emits no events, the hint says so and dragging still works.
- **Depth** sets how far the relief is displaced; **Motion** sets how far the eye
  swings. **Reset view** returns both, the tilt and the neutral pose.
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

## What it does not do yet

- **Export / packing** — the Android app packs an image and depth into a PNG;
  this page only reads them.
- **Drawing `.splat` clouds** — files are listed, counted and opened to a
  placeholder. The cloud renderer is the next change, with its own warnings.
- **Accounts, a server, sync with the phone** — none, by design.
- **Persistence** — no IndexedDB, no localStorage; reload and the gallery is
  empty.
- **Zoom / pan / choosing an orbit centre** — the first pass is a viewer.

## Layout

```
web/
  index.html          page shell (no framework)
  src/main.ts         file intake: picker + whole-window drag & drop
  src/gallery.ts      the cards, thumbnails, counts and per-file inspection
  src/media.ts        the one media classifier + formatting (pure, tested)
  src/deph.ts         the dePH/dEPt reader (contract/deph third copy)
  src/viewer.ts       one dispatch point: relief | photo | cloud | message
  src/relief.ts       three.js relief renderer (ported camera model)
  src/tilt.ts         deviceorientation → a smoothed tilt target
  src/deph.test.ts    the fixtures, under plain Node
  src/media.test.ts   the three states, without a browser
```

CI's `web-app` job runs `pnpm test`, `pnpm typecheck` and `pnpm build`.
