# `web/` — the Basrelief gallery

A static gallery and viewer for the files the rest of this repository makes. Drop
a packed depth PNG on the page and it becomes a relief you can move; drop a
`.splat` cloud and it becomes a point cloud you can move; a photo can have a depth
map generated on your own ComfyUI server; a relief can be saved back out as a
packed PNG. **Nothing is uploaded, and the library is saved in this browser** —
the rows, their per-photo look and the Renderer choice survive a refresh, and a
folder you pick can hold a **write-through copy** of the whole library (Back up
now / Restore) in the Android app's own schema. The ComfyUI address and the chosen
model are saved too — `prefs.json` beside `library.json`, under the app's own
`comfy_url`, `comfy_model` and `comfy_model_history` keys — so a reload does not
make you retype them.

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
**Every group and row the studio's sheet shows is present**, in the studio's order
and with the studio's look. A row this page can honour is wired to the real
render; a row it cannot is still drawn, disabled, with one sentence saying why.

- **Three row roles, and no fourth.** A switch row carries a state you can flip
  (Device tilt), an action row is a plate you can press (Save packed PNG, Test
  connection), and a reading row is information and cannot be pressed at all — its
  factory takes no listener, so a fact cannot drift into looking like a control.
  Captions, the 11/12/13/14px type scale, the 11px-radius plates, the grip and the
  one-line slider are the app's own constants from `ModalSheet`/`BasreliefSlider`.
- **The grip drags.** Pull it down and the sheet follows by height; release and it
  settles by `SheetIntent`'s rule (a 0.20 s velocity projection with a fling
  override, at the studio's 0.95/0.40 thresholds). A tap on the grip toggles.
  Collapsed, only the grip bar is left over the picture.
- **The row inventory is the studio's.** The Home/Lock segment, the Renderer
  segment and its asset row, the four cloud look knobs, Depth, Motion, FOV, Speed,
  Brightness, Shadow, Azimuth, Elevation, Origin X/Y, Pan X/Y/Pan Z, the actions
  and the foot. The rows that act:
  - a **relief** gets Depth, Motion, FOV, Brightness, Shadow, Azimuth, Elevation,
    Origin X/Y and Pan X/Y/Pan Z. Its camera is `src/camera.ts` (a port of
    `CameraModel`): tap sets the orbit centre by unprojecting through the camera,
    one-finger drag pans, two fingers pan and pinch Pan Z, and the crosshair marks
    the pivot of the frame that was drawn;
  - a **cloud** gets Depth, Size, Blur, Resolution, Motion, Brightness, Shadow,
    Origin X/Y and Pan X/Y/Pan Z; Light is disabled with a sentence because a
    cloud carries baked colour and no surface normal;
  - a **photo** gets the whole card with the displacement controls disabled until
    a depth map exists, and its asset row offers **Choose a map** and **Generate**.
- **The Renderer segment is live** once a card holds both assets: a packed depth
  PNG and a cloud built from it. Selecting Relief or Splat changes what is drawn.
  A dropped `.splat` has no photograph, so its Relief option is the one disabled
  option in the segment. The Home/Lock segment, Speed, Playlists, Archive, Set as
  wallpaper and Settings are still drawn disabled, each with its sentence.
- **The values and ranges are the app's schema**, not this page's taste:
  `src/settings.ts` reads and writes the `SurfaceSettings` body and
  `contract/settings/` pins it — see below.

## Generate depth

The photo sheet's asset row is the studio's: its **state** (`No depth map yet`,
`Has a depth map`, embedded, a run in flight, or the server's own failure words)
and its **actions** (**Generate**, and **Choose a map** for a depth file you
already have). The ranking is `BuildTab`/`AssetRow`/`AssetActions`': a run in
flight offers nothing, and a failure outranks the asset it left behind.

Generate opens a dialog with the ComfyUI address, a Test connection action and the
model chooser. Then:

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
pnpm test       # all ten contract/state tests, under plain Node
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

- **Drag** on the picture to pan it. A cloud and a relief share this: the camera
  slides at every depth, which is the studio's Pan, and the rows follow the
  finger. **Device tilt** (a switch row on the sheet) is what swings the camera —
  it asks for orientation permission where the browser requires it (iOS). If the
  browser exposes the API but emits no events, the switch turns itself back off
  and says so; the rows and pan still work.
- **The sheet** holds the render values. On a relief: **Depth**, **Motion**,
  **FOV**, **Brightness**, **Shadow**, the light's **Azimuth**/**Elevation**,
  **Origin X/Y** and **Pan X/Y/Pan Z**. On a cloud: **Depth**, **Size**, **Blur**,
  **Resolution**, **Motion**, **Brightness**, **Shadow**, Origin and Pan. On a
  photo the displacement controls are disabled until a map exists. Each slider is
  a fill with the value printed on it and a −/＋ pair; the track is draggable and
  the arrow keys step it. **Reset view** returns every value, the tilt and the
  neutral pose.
- **Tap** the picture to set the orbit centre; **drag** pans; **two fingers** pan
  and pinch Pan Z. The crosshair marks the centre of the frame that was drawn.
- **Save packed PNG** (a relief only, an action row on the sheet) writes the colour
  and the depth map it read into one PNG and downloads it as `<name>.packed.png`.
  A photo says why there is nothing to pack, and a cloud says a `.splat` is not a
  PNG — neither leaves a dead button.
- **Generate depth** (a photo's asset row) opens the ComfyUI dialog: address,
  Test connection, model chooser, Generate. It uploads the photo, builds the still
  graph, polls the returned prompt id, fetches the depth map, packs it and shows
  the relief. **Choose a map** does the same with a depth image you already have.
  The dialog's **Splat** tab builds a SHARP cloud when the server has the node.
- **Grip**: drag it down to collapse the sheet to the handle, tap it to toggle.
  **Done** collapses it; **← Gallery** or **Escape** closes the viewer (Escape
  closes the Build dialog first, then collapses the sheet).
- **Not on this page**, drawn disabled with a sentence rather than left as an
  absence: the Home/Lock surfaces, playlists, archive, Set as wallpaper, Settings
  and Speed. A page cannot set a wallpaper, it rotates no playlists, it hides no
  row in an archive, and it draws no clips.

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
a one-finger drag (pan), two fingers (pan plus pinch → Pan Z) and the crosshair
are the studio's set; `src/camera.test.ts` pins the at-rest invariant and the
tap round trip through the real matrices. Smoothing uses the app's time constant
(`tau = 0.2 s`) so the feel is the same at 60/90/120 Hz. The gallery's media
vocabulary, the reader's return shape (raw grey + dimensions, or whole PNG + its
own dimensions, as Java does) and the viewer seam are this app's own.

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
  playlists.json            optional, opaque — copied, never read here
  item-<id>.<ext>           one colour image per row, original bytes
  item-<id>.splat           a Gaussian cloud, when a row has one
```

`library.json` is `Library`'s schema — `surfaces.home`/`lock` of the same
seventeen values, `render`, `archived`, `focal`, `splat`, `splat_lens`, the opaque
`video`, the sort and the selection — so a folder written here can be opened on
the phone and a folder the phone wrote can be opened here. The parser never
throws: a corrupt blob is an empty gallery with a sentence and the original kept
as `library.corrupt.json`, and a blob from a *newer* build is read-only (no
import, edit, delete or Clear can overwrite it). `contract/library/cases.json` is
the shared statement of that schema. Filenames are bare, never paths, which is
what makes a backup portable.

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

**A picked folder is a write-through copy, never a second truth.** This is the
app's decision (`TODO.md`): a folder is a *permission* — revoked, unmounted,
offline — and with a folder as truth every render path needs an "unavailable"
state and a missing folder becomes an empty gallery. So:

- **Choose backup folder…** stores the `FileSystemHandle` in IndexedDB (it is
  structured-cloneable, so it survives a reload). The *permission* does not
  survive: `queryPermission` answers `prompt`, which is the **Allow the folder**
  button, not a failure.
- **Back up now** writes every stored file, then `library.json` **last**, so an
  interrupted backup never names a file it did not copy. `prefs.json` is copied
  like any other store file; **Restore** is library-driven and does not write
  device settings back over the running browser's.
- **Restore** reads the folder's `library.json`, validates it, copies the files it
  names plus an optional `playlists.json`, then writes the index last. A file the
  folder is missing is named in the sentence and the row is kept, with the card
  saying what it wanted — never a silently shorter gallery.
- **Unavailable** is a visible state with its own sentence: the folder is gone
  (`NotFoundError`) or the browser refuses writes (`NotAllowedError`). The gallery
  is untouched either way, because nothing renders from the folder.

Unsupported browsers (no folder picker) get that sentence too, and a browser
without OPFS gets a memory store, the line **Not saved — this tab only**, and a
page that otherwise works.

## What it does not do yet

- **Clips and the video graph** — a `.mp4` pair is not read, and the Speed row is
  drawn disabled because a page draws no clips.
- **A cloud's colour is not colour-managed.** The baked bytes go to the framebuffer
  exactly as the app's shader writes them, so the picture matches the app rather
  than an `sRGB`-converted one.
- **Accounts, a server, sync with the phone** — none, by design.
- **Playlist behaviour and the archive** — the schema round-trips both, and
  nothing rotates or hides.
- **A lone `.splat` is not persisted** — the app's schema gives every row a
  photograph as its poster, so a cloud dropped without a picture is kept for the
  session and the card says so. A cloud built from a photo persists.
- **The folder picker is Chromium desktop only** — OPFS, the part that is the
  truth, works everywhere. A browser without OPFS says that nothing will be saved
  and keeps working for the session.

## Layout

```
web/
  index.html          page shell (no framework)
  src/main.ts         file intake: picker + whole-window drag & drop
  src/gallery.ts      the library: cards, inspection, imports and per-row writes
  src/library.ts      the Library v5 blob: reader, writer, names, sort (pure, tested)
  src/store.ts        OPFS (and the memory fallback): the files and the two blobs
  src/mirror.ts       a picked folder: the handle in IndexedDB, Back up / Restore
  src/media.ts        the one media classifier + formatting (pure, tested)
  src/deph.ts         the dePH/dEPt reader (contract/deph third copy)
  src/pack.ts         the dePH writer + the browser colour re-encode (pure half tested)
  src/splat.ts        the .splat record, the cloud camera, the lens, the sort (pure, tested)
  src/camera.ts       the relief camera: CameraModel's frame, tap and heightAt (pure, tested)
  src/settings.ts     the SurfaceSettings schema: ranges, defaults, clamps (pure, tested)
  src/prefs.ts        the app's prefs: comfy_url/model/history + ModelRanking's rule (pure, tested)
  src/picture.ts      brightness, shadow and the light vector (pure, tested)
  src/comfy.ts        the ComfyUI client: the depth and SHARP graphs, the poll, the fetch
  src/sheet.ts        the studio's bottom sheet: rows, grip, captions, the slider
  src/cloud.ts        WebGL2 splat renderer (ported projection, integer texels)
  src/viewer.ts       one dispatch point: relief | photo | cloud | message, the rows and gestures
  src/relief.ts       three.js relief renderer (CameraModel camera + the app's tone/light)
  src/tilt.ts         deviceorientation → a smoothed tilt target
  src/deph.test.ts    the dePH fixtures, under plain Node
  src/pack.test.ts    the writer's fixture, chunk/CRC and both round trips
  src/media.test.ts   the three states, without a browser
  src/splat.test.ts   contract/splat + the camera, lens, sort, tap and floor
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
