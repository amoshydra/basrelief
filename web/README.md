# `web/` — the Basrelief gallery

A static gallery and viewer for the files the rest of this repository makes. Drop
a packed depth PNG on the page and it becomes a relief you can move; drop a
`.splat` cloud and it becomes a point cloud you can move; a photo can have a depth
map generated on your own ComfyUI server; a relief can be saved back out as a
packed PNG. **Nothing is uploaded, and the library is saved in this browser** —
the rows, their per-photo look and the Renderer choice survive a refresh. The
ComfyUI address and the chosen model are saved too — `prefs.json` beside
`library.json`, under the app's own `comfy_url`, `comfy_model` and
`comfy_model_history` keys — so a reload does not make you retype them.

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
- the app's four cloud look knobs: **Depth** (the Z-only multiplier), **Size**
  (the floor as a fraction of the cell), **Blur** (a separable post-blur) and
  **Resolution** (an offscreen render scale), each clamped to
  `contract/settings/`'s range;
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

At rest — no tilt — the picture is **exactly the photograph, translated by Pan
alone**: the eye is the cloud's own source camera, Origin names only the
turntable's axis, and Pan is one clip-space shift. `src/splat.test.ts` asserts
that through the matrices the renderer uploads, at every origin, pivot depth,
aspect and lens, and asserts that a tap and the pivot it resolves are a fixed
point.

A cloud's cost is its count and the machine's: the demo is ~150k splats, and the
app's ladder puts 1M at ~5 fps on a 2018 phone. Nothing is capped; the viewer names
the count, and says so when a cloud is dense enough for that to matter.

## The sheet

The viewer's controls are the Android studio's bottom sheet, reproduced rather
than reinvented: `src/sheet.ts` is the vocabulary, and `src/viewer.ts` composes it.
**The sheet draws the controls the page can act on**, in the studio's order and
with the studio's look. A row this page can honour is wired to the real render.
A phone-only screen is not drawn; the one disabled option is the Renderer's
Relief choice on a cloud-only card, because that segment is otherwise live and
the option explains itself.

- **Three row roles, and no fourth.** A switch row carries a state you can flip
  (Device tilt), an action row is a plate you can press (Save packed PNG, Test
  connection), and a reading row is information and cannot be pressed at all — its
  factory takes no listener, so a fact cannot drift into looking like a control.
  Captions, the 11/12/13/14px type scale, the 11px-radius plates, the grip and the
  one-line slider are the app's own constants from `ModalSheet`/`BasreliefSlider`.
- **The sheet is the studio's morph.** It is one panel, not two states: a release
  projects through `SheetIntent.SHEET` (a 0.20 s velocity projection with a fling
  override, at the studio's 0.95/0.40 thresholds) and the sheet **rests wherever
  the projection lands** — `Snap.FREE`. The position is remembered across a
  refresh in `prefs.json`, as the app's own `sheet_pos` and `sheet_open_pos`, so a
  reload reopens where you left it and a tap on the pill returns to the fraction it
  was dragged to, not to full. The arithmetic is `src/sheetmath.ts` (unit-tested,
  no browser); `src/sheet.ts` is the DOM that calls it.
- **Height and shape are two ramps, and the content fade is a third.** The height
  follows the hand across the whole travel — no plateau, no cliff — while the
  width, the bottom lift, the corner radius and the fill wait for the last tenth
  (`SHAPE_BAND_START`, the collapse threshold, so a full-width 40 %-tall panel is
  still a sheet). The content's opacity is pinned to the same line, which means
  **no position the sheet can rest at is ever faded**; it dissolves only inside the
  band where it can no longer come to rest.
- **Collapsed, it is the pill**: a 56dp capsule, 26dp plus the safe-area inset off
  the foot, as wide as its label up to a 56dp inset and no narrower than 96dp, with
  an 8dp status dot (`Controls`, `No 3D`, `Build failed`, or a run's own word) and
  a 1px outline. Tapping it springs the sheet back to the remembered fraction.
- **The whole panel drags, and the scroll has first refusal.** A vertical drag
  anywhere moves the sheet; a scroll that can still use the direction keeps it and
  hands over at its top edge. A drag past full is tethered at a fifth of the span;
  the settle is critically damped, its duration derived from the release speed and
  clamped to 250-500 ms, and `prefers-reduced-motion` cuts the settle rather than
  the tether.
- **The filmstrip, the fix line and the photo line are sheet content.** The
  filmstrip is the library under the grip and it fades with the sheet; the photo
  line is the quiet 12px line at the top of the scroll. The fix line is the dashed
  condition box under the strip, but the asset row now lives in the scroll below
  the Renderer segment, so the fix line is left hidden. **There is no footer and
  no Done** — the sheet is put away by dragging and by the pill, and Reset view is
  a row inside the scroll.
- **The row inventory is what the page can do.** The Renderer segment and its
  asset row, the selected mode's look rows, Motion, Picture, Light, Origin X/Y,
  Pan X/Y/Pan Z, the actions and the foot. The rows that act:
  - a **relief** gets Depth, Motion, FOV, Brightness, Shadow, Azimuth, Elevation,
    Origin X/Y and Pan X/Y/Pan Z. Its camera is `src/camera.ts` (a port of
    `CameraModel`): tap sets the orbit centre by unprojecting through the camera,
    one-finger drag tilts when the sensor is off, two fingers pan by their midpoint
    and pinch Pan Z, and the crosshair marks the pivot of the frame that was drawn;
  - a **cloud** gets Depth, Size, Blur, Resolution, Motion, Brightness, Shadow,
    Origin X/Y and Pan X/Y/Pan Z; Light is disabled with a sentence because a
    cloud carries baked colour and no surface normal;
  - a **photo** gets the whole card with the displacement controls disabled until
    a depth map exists, and its asset row offers **Choose a map** and **Generate**.
- **The Renderer segment is live** once a card holds both assets: a packed depth
  PNG and a cloud built from it. Selecting Relief or Splat changes what is drawn.
  A dropped `.splat` has no photograph, so its Relief option is the one disabled
  option in the segment. The Home/Lock segment, Speed, Playlists, Archive, Set as
  wallpaper and Settings are **not drawn**: they are phone-only screens with no
  action this page can honour.
- **The values and ranges are the app's schema**, not this page's taste:
  `src/settings.ts` reads and writes the `SurfaceSettings` body and
  `contract/settings/` pins it — see below.

## Generate depth

The photo sheet's asset row is the studio's: its **state** (`No depth map`,
`Depth map`, embedded, a run in flight, or the server's own failure words) and
its **actions** (**Make one** / **Rebuild**, and **Choose a map** for a depth
file you already have). The ranking is `BuildTab`/`AssetRow`/`AssetActions`': a
run in flight offers nothing, and a failure outranks the asset it left behind.

The dialog connects to the ComfyUI server **on open** — the Server row reports
the address and whether it answered, the model list arrives from it, and Test is
there for a changed address rather than as a step. The dialog asks only what to
build (the segment) and from what (the model, or the cloud budget); the address
is one summary row that opens when it needs changing. **The run is stated in the
footer**, beside the button that started it: the phase, the elapsed seconds and
a five-step track, for as long as it runs. Closing the panel does not cancel it;
**Stop** does. Then:

1. **The graph is `ComfyWorkflow.java`'s still shape.** A classical depth node is
   `LoadImage → <node> → SaveImage`; MoGe is the seven-node pipeline
   (`LoadMoGeModel → MoGeInference → MoGeRender(depth) → SaveImage`, plus the
   normal render/save). Every scalar is discovered from `/object_info` at the
   node's own default; the graph's own inputs — the uploaded name, `output`, the
   saver prefixes and `filename_prefix` — are the only overrides. A server without
   a node this page can drive says so; nothing is substituted.
2. **The poll is keyed by the prompt id `/prompt` returned**, never by a
   fixed-name sidecar. An absent history entry is not a verdict (a queued run has
   none), and a run is only over when `status.completed` is true. A history entry
   carrying outputs while still running is not "finished"; a `status_str: error`
   carries the server's `execution_error` words into the row.
3. **The depth image is fetched from `/view`, scaled to the photo, packed with the
   tested `dePH` writer** (`src/pack.ts`, the same one Save packed PNG uses), and
   shown as a relief. The gallery card is replaced with that packed file too — in
   the library, under the row's own image name — so closing and reopening the
   viewer, and a refresh, still show the generated relief.
4. **A failed preflight appears as a sentence**, not a silent nothing.

### Build a splat cloud

The same dialog's **Splat** tab is the studio's SHARP build, and its primary is
the asset row's (`Make one` / `Rebuild` / `Regenerate` under `BuildTab`'s
ranking):

1. **The graph is `ComfyWorkflow.sharpStillWorkflow`'s:** two nodes, one wire —
   `LoadImage → SharpStill`. Every scalar is discovered from `/object_info` at its
   own default; the five the flow owns are the uploaded name, `tag`,
   `budget`, `internal_size` (1536) and `keep_loaded: false`. `checkpoint` is left
   at the node's own default. A server without `SharpStill` says so; a depth node
   is not a cloud generator and nothing is substituted.
2. **A BUDGET is stated, not discovered.** 150,000 is the app's default, the
   slider runs 150k–1.2M, and the ladder is the app's own measurement: **150k
   soft, 741k right, 1.05M indistinguishable from full**, and ~5 fps at 1M on a
   2018 phone. The node rounds down to a whole 2048-splat block, so 200k writes
   198,656.
3. **The tag is the contract's rule** (`contract/tag`): ASCII `[A-Za-z0-9_-]`,
   then the first 48. Both output names are derived from it —
   `basrelief_<tag>.splat` and `basrelief_<tag>.splat.json` — so a finished run is
   fetched without ever reading a response, exactly as the app does.
4. **The poll is the same rule as depth**: the prompt id `/prompt` returned, an
   absent history entry is running, and only `status.completed` says it is over.
   The fixed-name progress sidecar is never read.
5. **The sidecar is fetched WITH the cloud and attached.** Its `focal / height`
   is the lens the cloud was reconstructed through; without it the viewer draws
   the app's fallback (64.3°) and says that is what it is doing. A cloud shown
   with the wrong lens looks perfectly fine, which is why it is fetched in the
   same run and not left to a later drop.
6. **The cloud is held in memory on the card**, so closing and reopening the
   viewer still shows it; **Rebuild** replaces it and the card's count (the
   rebuilt bytes are a new cloud); **Delete** removes it.

**What a wrong answer looks like:** no budget control (a cloud nobody chose the
density of); a cloud drawn at the fallback lens because the sidecar was not
fetched; a "finished" run read from a stale sidecar (a prompt that has not
started has no history entry, and a previous run's sidecar can answer for it);
and a rebuild that reuses the old card (the count and the bytes would both be the
previous run's).

### CORS and the address

A browser cannot POST to ComfyUI on another origin unless that server allows it.
`pnpm dev` therefore proxies **`/comfy`** (see `vite.config.ts`) to
`http://127.0.0.1:8188`, and `/comfy` is the default address — the path this
gallery is actually run through. Point the proxy elsewhere with
`COMFY_URL=http://host:8188 pnpm dev`. The proxy **strips the page's
`Origin`/`Referer`** before forwarding: ComfyUI's CSRF check compares Origin with
the Host it was reached on and answers every POST with HTTP 403 otherwise. That
was measured against a live server — the upload worked only after the strip — and
it is why the proxy is the path that works rather than a convenience.

A **static build has no proxy**, so it must either be served behind a proxy of
your own or talk to ComfyUI directly with ComfyUI started with
`--enable-cors-header`. `pnpm preview` also uses the dev proxy. The address is
saved with the library in `prefs.json` (the app's `comfy_url`), so it survives a
reload; it is not a render value and has no row to live on. A failed request says
both routes explicitly ("a direct cross-origin
request needs ComfyUI to allow CORS… or run pnpm dev and use /comfy"), and a
static build's `/comfy` 404 says that this is what happened.

### The settings contract

`SurfaceSettings` is the app's seventeen-value render body inside the `Library` v5
blob, and `src/settings.ts` is its second reader and writer.
`contract/settings/cases.json` states what the two agree about: the ranges, the
defaults an absent field resolves to, one populated body the writer must give back
byte for byte, 27 clamps, and a junk body that must not throw. `src/settings.test.ts`
holds the TypeScript side to it, and `scripts/check-parity.mjs` reads the fixture
and holds **both** languages' constants to the same numbers — so a Size slider
that reached 0.9 where the app stops at 0.8 would be a red build rather than a
picture a visitor can tune and the phone cannot produce.

Two fields are deliberately *not* clamped the way their neighbours are: **pan is in
pixels**, and its bound is the surface's own larger dimension, which this layer
cannot know (clamping it to Origin's fraction range once turned a stored −135 px
into −0.5 px); and **azimuth wraps**, because 0° and 360° are the same light, so a
clamp would make a dead zone at the seam.

## Running it

```
cd web
pnpm install
pnpm dev        # HTTPS on :5173
pnpm build      # typecheck + static bundle in web/dist
pnpm preview    # serve the built bundle
pnpm test       # all eleven contract/state tests, under plain Node
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

- **Drag** on the picture with two fingers to pan it. A cloud and a relief share
  this: the camera slides at every depth, which is the studio's Pan, and the rows
  follow the finger. A one-finger drag tilts when the sensor is off; **Device
  tilt** (a switch row on the sheet) is what swings the camera — it asks for
  orientation permission where the browser requires it (iOS). If the browser
  exposes the API but emits no events, the switch turns itself back off and says
  so; the rows and pan still work.
- **The sheet** holds the render values. On a relief: **Depth**, **Motion**,
  **FOV**, **Brightness**, **Shadow**, the light's **Azimuth**/**Elevation**,
  **Origin X/Y** and **Pan X/Y/Pan Z**. On a cloud: **Depth**, **Size**, **Blur**,
  **Resolution**, **Motion**, **Brightness**, **Shadow**, Origin and Pan. On a
  photo the displacement controls are disabled until a map exists. Each slider is
  a fill with the value printed on it and a −/＋ pair; the track is draggable and
  the arrow keys step it. **Reset view** returns every value, the tilt and the
  neutral pose.
- **Tap** the picture to set the orbit centre; a one-finger drag tilts when the
  sensor is off; **two fingers** pan by their midpoint and pinch Pan Z. The
  crosshair marks the centre of the frame that was drawn.
- **Save packed PNG** (a relief only, an action row on the sheet) writes the colour
  and the depth map it read into one PNG and downloads it as `<name>.packed.png`.
  A photo says why there is nothing to pack, and a cloud says a `.splat` is not a
  PNG — neither leaves a dead button.
- **Generate depth** (a photo's asset row) opens the ComfyUI dialog: the Server
  row connects on open, the model chooser lists what that server can drive, and
  Make one / Rebuild is the one action. It uploads the photo, builds the still
  graph, polls the returned prompt id, fetches the depth map, packs it and shows
  the relief. **Choose a map** does the same with a depth image you already have.
  The dialog's **Splat** tab builds a SHARP cloud when the server has the node,
  with the budget stated as points and file size.
- **The strip**: drag it (or anywhere on the panel) to move the sheet; tap it to
  toggle — collapsed it is the pill, and tapping the pill springs back to where
  the sheet last rested. **← Gallery** or **Escape** closes the viewer (Escape
  closes the Build dialog first, then collapses the sheet). There is no Done
  button: the gesture beside it is the dismissal.
- **Not on this page** — phone-only screens are not drawn: the Home/Lock
  surfaces, playlists, archive, Set as wallpaper, Settings and Speed. A page
  cannot set a wallpaper, it rotates no playlists, it hides no row in an archive,
  and it draws no clips.

The picture fills the viewport (cover, with the reference port's 8 % crop) at FOV
45°, as the wallpaper does. Pan moves the picture at every depth; FOV scales the
plane (a crop of the same camera); Pan Z dollies the camera. A landscape photo on
a portrait phone starts cropped — the FOV row narrows or widens that crop.

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

What is *not* taken from it: its structure (this app creates one scene per open
item and destroys it with the viewer), its gesture model and its
frame-rate-dependent smoothing (`lerp 0.08` per frame).

**The camera and the gestures are the ANDROID app's, not the port's.**
`src/camera.ts` is `CameraModel`: the fixed rest eye, the turntable about the
surface under the origin (so the subject holds and the background swings), the
clip-space Pan, the zoom crop, and tap → unproject → a new orbit centre. A tap,
a one-finger drag (tilt when the sensor is off), two fingers (pan plus pinch →
Pan Z) and the crosshair are the studio's set; `src/camera.test.ts` pins the
at-rest invariant and the tap round trip through the real matrices. Smoothing uses
the app's time constant (`tau = 0.2 s`) so the feel is the same at 60/90/120 Hz.
The gallery's media vocabulary, the reader's return shape (raw grey + dimensions,
or whole PNG + its own dimensions, as Java does) and the viewer seam are this
app's own.

The cloud renderer is a port of the **Android app**, not of the reference port and
not of `.design/prototype/ab/3d/splatline/`. The prototype's projection is the same
maths, but its upload is `RGBA32F`, which is the colour path this app replaced. What
is taken from it is nothing; what is taken from `SplatRenderer` is listed under *The
cloud renderer* above.

## Where the library lives

**OPFS is the truth.** `navigator.storage.getDirectory()` needs no gesture and no
permission, works on a phone, and holds:

```
basrelief/
  library.json              the Android app's Library v5 blob
  prefs.json                the app's prefs: comfy_url, comfy_model, comfy_model_history
  playlists.json            optional, opaque — carried through, never read here
  item-<id>.<ext>           one colour image per row, original bytes
  item-<id>.splat           a Gaussian cloud, when a row has one
```

`library.json` is `Library`'s schema — `surfaces.home`/`lock` of the same
seventeen values, `render`, `archived`, `focal`, `splat`, `splat_lens`, the opaque
`video`, the sort and the selection — so a copy of these files can be opened on
the phone and one the phone wrote can be opened here. The parser never throws: a
corrupt blob is an empty gallery with a sentence and the original kept as
`library.corrupt.json`, and a blob from a *newer* build is read-only (no import,
edit, delete or Clear can overwrite it). `contract/library/cases.json` is the
shared statement of that schema. Filenames are bare, never paths, which is what
makes a copy portable.

`prefs.json` is the app's **prefs**, not more of its library: a ComfyUI address and
a model choice are device/app settings, so the phone keeps them under
`comfy_url`, `comfy_model` and `comfy_model_history` in SharedPreferences while
the rows go in the `library` blob. `src/prefs.ts` writes those same three keys,
with the app's defaults (no address, no saved model, no history), and a history in
the app's **newline form** — `ModelRanking`'s `encode`/`decode` — so a
`prefs.json` copied between the two means the same thing. The history is
`ModelRanking`'s rule: most recent first, then discovery order, four deep and
deduplicated, a model the server no longer offers skipped rather than invented,
recorded on the **selection** rather than a finished run. A corrupt blob is the
defaults with a sentence and the original kept as `prefs.corrupt.json`; one
wrong-typed key falls back to that key's default; unknown keys survive a rewrite.

**There is no folder mirror or phone sync yet.** The page once had Back up now /
Restore against a picked folder, and it was removed: `showDirectoryPicker` is not
dependable on Android Chrome, and the phone cannot read a `library.json` back, so
"shared with Android" was true of the format and false of the workflow. A real
sync, on both sides, is a feature to design rather than a button to keep. Nothing
in the page draws from a folder; OPFS is the store.

A browser without OPFS gets a memory store, the line **Not saved — this tab
only**, and a page that otherwise works.

## What it does not do yet

- **Clips and the video graph** — a `.mp4` pair is not read, and there is no Speed
  row because a page draws no clips.
- **A cloud's colour is not colour-managed.** The baked bytes go to the framebuffer
  exactly as the app's shader writes them, so the picture matches the app rather
  than an `sRGB`-converted one.
- **Accounts, a server, sync with the phone** — none, by design.
- **Playlist behaviour and the archive** — the schema round-trips both, and
  nothing rotates or hides.
- **A lone `.splat` is not persisted** — the app's schema gives every row a
  photograph as its poster, so a cloud dropped without a picture is kept for the
  session and the card says so. A cloud built from a photo persists.
- **Folder backup / phone sync** — removed until it can be a real sync; see above.
  OPFS works everywhere and is the store.

## Layout

```
web/
  index.html          page shell (no framework)
  src/main.ts         file intake: picker + whole-window drag & drop
  src/gallery.ts      the library: cards, inspection, imports and per-row writes
  src/library.ts      the Library v5 blob: reader, writer, names, sort (pure, tested)
  src/store.ts        OPFS (and the memory fallback): the files and the two blobs
  src/media.ts        the one media classifier + formatting (pure, tested)
  src/deph.ts         the dePH/dEPt reader (contract/deph third copy)
  src/pack.ts         the dePH writer + the browser colour re-encode (pure half tested)
  src/splat.ts        the .splat record, the cloud camera, the lens, the sort (pure, tested)
  src/camera.ts       the relief camera: CameraModel's frame, tap and heightAt (pure, tested)
  src/settings.ts     the SurfaceSettings schema: ranges, defaults, clamps (pure, tested)
  src/prefs.ts        the app's prefs: comfy_url/model/history + ModelRanking's rule (pure, tested)
  src/picture.ts      brightness, shadow and the light vector (pure, tested)
  src/comfy.ts        the ComfyUI client: the depth and SHARP graphs, the poll, the fetch
  src/sheetmath.ts    the sheet's arithmetic: the morph, the release, the spring (pure, tested)
  src/sheet.ts        the studio's sheet: the morph, the pill, the strip, the rows
  src/cloud.ts        WebGL2 splat renderer (ported projection, integer texels)
  src/viewer.ts       one dispatch point: relief | photo | cloud | message, the rows and gestures
  src/relief.ts       three.js relief renderer (CameraModel camera + the app's tone/light)
  src/tilt.ts         deviceorientation → a smoothed tilt target
  src/deph.test.ts    the dePH fixtures, under plain Node
  src/pack.test.ts    the writer's fixture, chunk/CRC and both round trips
  src/media.test.ts   the three states, without a browser
  src/splat.test.ts   contract/splat + the camera, lens, sort, tap and floor
  src/sheetmath.test.ts  the sheet: two ramps, the free rest, the tether, the spring
  src/camera.test.ts  the relief camera: at-rest, turntable, Pan and the tap round trip
  src/settings.test.ts  contract/settings: ranges, bodies, clamps and junk
  src/library.test.ts   contract/library: the populated blob, defaults, flat and junk
  src/prefs.test.ts     the prefs blob, the history form and the ranking rule
  src/picture.test.ts   the two Picture mappings and the light convention
  src/comfy.test.ts     the depth and SHARP graphs, the tag rule, the history gates
```

CI's `web-app` job runs `pnpm test`, `pnpm typecheck` and `pnpm build`; the
`guards` job's `scripts/check-shaders.mjs` compiles the two GLSL shaders in
`src/cloud.ts` too, so a shader error is a red build rather than a viewer that
draws nothing.

## The bundled demo

A fresh page starts with the app's own preload — the marble bust, as a
photograph, a relief and a cloud. It is seeded only when `library.json` is
absent, never when it is empty, because an empty gallery means the demo was
deleted and a delete has to stick.

The cloud's count comes from the file's length, not its bytes: a one-byte
`Range` request reads `content-range` (GitHub Pages gzips the file, so `HEAD`
reports the compressed length), and the records themselves move only when
**Splat** is selected. Bundled files are kept in the Cache API, so a reload
costs about 300 bytes rather than 2.7 MB.

### Motion demo

`demo.html` is a controller-free loop of that bundled cloud. The camera pans Z
and tilts through a closed 10 s cycle, so the last frame is the first frame.
`?capture` starts it paused at t=0; the recording harness seeks, settles the
cloud, resumes for one period, and pauses again, which is what makes the MP4
seamless. The page is a Vite input, so `pnpm build` ships it beside the gallery.

The README's clickable still is `docs/screens/basrelief-demo.jpg`. The recorded
video is attached to the README as a GitHub user-attachment rather than stored
in this page: 720×1280, 30 fps and ten seconds.

## Gestures

| gesture | what it does |
|---|---|
| tap | set the orbit centre |
| one-finger drag | tilt the camera — only when the sensor is off, because the gyro owns the tilt |
| two fingers | pan by their midpoint, pinch for the camera distance |
| ⌖ beside *Device tilt* | re-centre the sensor's neutral pose |
