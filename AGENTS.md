# AGENTS.md — Basrelief

Gyro-driven "3D photo": an image plus a depth map become a displaced relief
surface that moves as you tilt the device. Ships as an Android live wallpaper
with a built-in studio screen.

**Android is the product.** `src/` is an older web port kept as a reference for
the original camera model. It is NOT being kept in sync, so do not treat the two
as expected to match — see *Known drift*. Its demo assets in `public/assets/`
are the **old** demo and are regenerated only by `npm run gen:assets`; replacing
`android/app/src/main/res/raw/` does not touch them, so the two demos now differ.
All three files there are referenced by `src/main.js` (`image.png`, `depth.png`,
and `photo-with-depth.png`, which carries its depth in a `dePH` chunk) — replace
all three or the web port breaks.

## Design prototypes

`.design/prototype/` is gitignored and is a **visual reference, not the source of
truth for structure**. It has drifted from the app, and the filmstrip is the
known case: in the app the strip is **sheet content** — `Gallery.installStrip(panel, 1)`
adds it directly to the sheet panel at index 1 (under the grip, above the fix
line and the scroll), and it **fades with the sheet**. `final.html` instead drew
it absolutely positioned over the render (`bottom:calc(--sheetH + 14px)`), i.e.
outside the sheet. That mockup shape was copied faithfully by three separate
agents and shipped the user a strip floating outside the sheet — several times.
The code was right; the prototype was wrong. **Read the code (and fix the
prototype in the same change) before trusting the prototype for anything
structural.** Every prototype page now carries a drift banner saying so.

## Build and test

Android is built with Gradle **inside a Podman container**. The host needs only
`podman` (plus `adb` to install) — no Android SDK, no Gradle, no Android Studio.

```
cd android
./podman-build.sh build-image    # once: builds the SDK image (Ubuntu 22.04 + JDK 17 + SDK 36)
./podman-build.sh test           # JVM unit tests in :core — no device needed
./podman-build.sh build-apk      # also runs :core:test first; cannot skip it
./podman-build.sh install        # build-release + adb install + AOT compile: the usable build
./podman-build.sh install-debug  # debug APK: behaviour testing only, never performance
./podman-build.sh build-release  # signed only if keystore.properties exists
./podman-build.sh clean | shell | help
```

`install` needs exactly one device; with several attached it names them and
asks for `-s <serial>`, `ANDROID_SERIAL`, or the serial as an argument
(`./podman-build.sh install 192.0.2.10:5555`) rather than passing adb's
raw "more than one device" error through.

The Gradle cache is bind-mounted out of the container (`GRADLE_USER_HOME`), so
dependencies and the Gradle distribution are downloaded once instead of on
every build — locally and in CI. Cold build 14 s, warm 4 s with no network.

It is set via the **environment variable**, not `--gradle-user-home`: the
wrapper script decides where to keep the distribution and downloads it *before*
Gradle starts, so the flag alone leaves `gradle-<version>-bin.zip` re-fetched on
every run while everything else stays cached. That is a mistake which looks like it
worked, because the build still succeeds. Override the location with
`GRADLE_CACHE_DIR`, or opt out entirely with `GRADLE_NO_CACHE=1`.

CI (`.github/workflows/build.yml`) runs `build-image`, `test`, `build-apk` on
every push, plus a `vite` build for the web and a `guards` job: AGENTS.md's paths
and the port parity, `scripts/check-shaders.mjs`, which compiles every GLSL
shader the app keeps in Java, and `scripts/check-json-puts.mjs`, which refuses a
float handed to `JSONObject.put`. It invokes the scripts from the repo
root, so **`podman-build.sh` must stay runnable from any working directory**.

Web (reference port, not maintained): `npm install && npm run build`.

## Architecture

```
android/app  ──depends on──▶  android/core
  Android shell                pure JVM, zero android.* imports
```

`:core` exists so the maths that is easy to get quietly wrong is testable
without a device. It holds depth→geometry (`ReliefMesh`), the camera
(`CameraModel`), the picture adjustments (`Picture`), the splat mode's numbers
(`Splat`), the tilt smoother both the draw and the doze gate step
(`TiltFilter`), a vendored `Mat4`, the prefs path scheme (`PrefFiles`), the whole
persisted schema (`Config`, `Library`, `LibraryItem`, `Surface`,
`SurfaceSettings`, `Playlists`, `Playlist`, `Trigger`, `VideoTracks`), the three
"where did this come from" vocabularies (`DepthSource`, `NormalSource`,
`VideoSource`), and the one resolution of state into numbers
(`Framing`, `SurfaceResolver`). It also holds the perf readout's numbers and their
formatting (`RenderStats`), because the readout and the `splat:` log must print one
computation and "what a missing number prints" is exactly the mapping a screenshot
cannot check.

Anything that would otherwise be untestable GL or platform code belongs here as a
pure function of plain values — that is the whole test. `Picture` is the pattern:
the shader keeps one multiply and one mix, and the mapping it cannot be checked for
lives in `:core` with its own tests. `SurfaceResolver` is the same, answering
which surface an engine is drawing from two ints.
JUnit is `testImplementation` only, so `:core` keeps **zero runtime
dependencies** and nothing is added to the APK. The app shell's only dependency
is `project(':core')` — no AndroidX, no Maven artifacts.

**Two surfaces, one process.** The studio (`MainActivity`) and the live
wallpaper (`DepthWallpaperService`) each own a `GLSurfaceView` and a
`ReliefRenderer`. SharedPreferences `depthwall` is the *transport* between them:
the studio writes, the wallpaper reads and re-renders.

There is now a gallery, so that file holds two versioned JSON blobs: `config`
and `library` (the index). **The index owns the selection and only the index** —
the pointer exists in exactly one place, because two copies can disagree and a
disagreeing pointer is a wallpaper showing a photo the user deleted. **Everything
about a photograph is on its `LibraryItem`, including Motion** — it was global
until `Library` v3, and the config blob survives only as the legacy value the
migration reads so an upgraded user's photos keep the speed they had.
`AppState` is the single read path, and `Framing` the single resolution of that
state into numbers, because the two surfaces computing it themselves is how they
came to disagree about the bundled item.

**The index is parsed once per blob change, process-wide, and writers publish.** Measured
on a 32-item gallery: one `AppState.load` costs **~1 s** — 165 ms of JSON parse, ~770 ms in
**four** full `toJson()` serialisations used only for dirty-checks, and 66 ms re-inspecting
the blob — and a single launch called it **eight to ten times** (onCreate, two onResume
paths, `refreshSliders`, `applyFiles`, the strip/grid builders, the wallpaper engine's
resolves) on the one main thread. That is the whole of the measured 3.2 s warm / 5.7–8 s
hot launch, with `Choreographer: Skipped 304/316 frames` twice. `AppState` now keys the
parsed state on the exact two blobs: a blob change fails the compare and the state is
re-derived, so it cannot serve a stale index — the failure that matters is a wallpaper
showing a photo the user deleted. Every writer publishes the library IN
MEMORY the moment it is mutated (`LibraryStore.save`, `LibraryStore.saveNow`), so a write
does not force the next reader to re-parse and the cache is **AHEAD of the stored blob
while the write is queued** -- that flag is what a reader checks first, so the tap that
caused the write is visible to the wallpaper immediately. The disk write itself is
serialise + commit on a dedicated single `index-writer` thread (measured: `toJson` is
~81 ms and a `commit()` 33-430 ms, and every gallery tap, archive toggle and slider
release used to pay both on the UI thread); FIFO ordering is the single thread, and the
"ahead" flag is cleared only by the write whose library object IS the cached one, so an
older queued write landing cannot mark a newer publish as stored. A process death inside
the queue's window loses the write, exactly as `apply()` already accepts for every
per-item edit. `MainActivity`'s own `cachedState` and its index listener are deleted --
one cache, one invalidation rule, and `state()` is now just `AppState.load`. After:
**warm 1.7 s, hot 0.56 s, no skipped frames.** The remaining cold-start cost is the single
parse (and the dirty-check serialisations inside it); moving that off the UI thread is the
next step. The wallpaper's per-resolve diagnostic write is `apply()`, not `commit()` — a
diagnostic must not fsync on the main thread, and the studio reads it from memory-or-disk
either way.

**A render value lives on exactly one surface, and a surface's values are a set.**
`LibraryItem`'s six fields are private and are the *home* surface's storage;
`settings(Surface)` is the only read and `withSettings`/`editSettings` the only
write, so a `lock` override cannot be half-updated and a home edit cannot forget a
second surface exists. Editing one value through a constructor that defaults
everything it is not given resets everything it is not given — which shipped once,
when `withDepth` silently reset Motion, brightness and shadow because those values
had been added after it was written. `LibraryItem.copy` exists for the same
reason: identity-only edits have one path that cannot name a render value.

Because they share a process, one shares the other's `SharedPreferences`
instance — which is why "does it work cold?" can only be answered without opening
the studio first.

**Do not use `am force-stop` for that test.** It puts the package in the stopped
state, and a stopped package's wallpaper component is never instantiated, so the
engine does not come back. SurfaceFlinger keeps serving the last frame it was
given, so the home screen shows a *plausible, correct-looking, stale* wallpaper
and nothing re-renders. Measured: after a force-stop the process was gone, the
engine wrote no prefs at all, and two consecutive frames were byte-identical —
which reads exactly like "the renderer resolved the wrong item" and cost a
detailed false-positive hunt. A reboot is the honest cold test; a live process
plus a selection change is the honest update test.

### Threading

| thread | owns |
|---|---|
| UI | widgets, prefs writes, `runOnUiThread` callbacks |
| `Io` executor | file reads, PNG encode, downscale — never the UI thread |
| sensor callback | `TiltController` → writes `volatile` tilt, calls `requestRender()` |
| GL | `onDrawFrame`, mesh build, `CameraModel`, all GL state |

**The rule:** UI-thread setters (`setDepth`, `setZoom`, `setMotion`, `setTilt`)
may only write `volatile` fields. Anything touching GL state — including
`loadPair` and `screenToOrbit` — must be posted with `glView.queueEvent`.

## Invariants

These are invisible from the code and easy to undo by accident. Each one has
already cost real time.

- **The bundled demo is an ordinary preloaded photo, and delete must stick.**
  It is a row in the gallery and the one item with no file: a null image name is
  the signal the renderer already used to read
  `android/app/src/main/res/raw/image.png`, and that is all the reserved
  `Library.BUNDLED_ID` is for. It can be selected, renamed, edited, put in a
  playlist and deleted like anything else. What keeps a delete stuck is not a
  delete guard but **when the seed runs**: `withBundled` adds it only into an
  empty library, and the callers invoke it only when the stored index blob is
  **absent** — a genuinely fresh install, or the state Settings' Reset leaves
  behind by clearing the key. A stored blob with zero items is an emptied
  gallery, not a fresh one, so it stays empty however many rows were deleted
  before the last. Settings therefore restores the demo and Delete cannot.
  `retainExisting` keeps it when
  the index lists it and never adds it, because the index is the authority over
  a fileless row; `fromJson` keeps its exemption so such a row survives a round
  trip, which is parsing, not resurrection. `Playlists.afterRemoving` removes the
  id from the index and every playlist in one derivation, like any other photo.
  Deleting it while it is active clears the selection like any delete, and a
  null selection still renders `res/raw`, so the picture on screen does not
  change until another row is picked.
- **The bundled demo is a trio, and an unreferenced asset fails silently.**
  `android/app/src/main/res/raw/image.png`,
  `android/app/src/main/res/raw/depth.png` and
  `android/app/src/main/res/raw/normal.png` are loaded together; ship one
  without wiring it and the loader falls back — a flat map with *no relief at
  all*, or a normal the shader never samples and a demo that lands **flat lit**.
  The status line is the only tell, which is why it distinguishes
  "bundled demo + depth + normals" from "bundled demo + depth (flat lit)" from
  "bundled demo, FLAT (no depth found)". If you add or replace an asset, confirm
  something actually reads it — this has now bitten twice, and an asset with zero
  references is indistinguishable from a working one in a build log.
- **A clip is a photograph plus one nullable reference, and it is not a second kind
  of row.** `LibraryItem` carries `VideoTracks` (colour, depth and normal clips) and
  nothing else changed; `Library`, the selection, the sort, the archive flag and
  `Playlists` have no branch for a clip. The alternative was rejected on the same
  ground the selection pointer is: **the index owns the selection and only the
  index**, so a separate container of clips would let the pointer name a photograph
  while a second structure said it was a video — and that disagreement is invisible
  until the wallpaper is showing the wrong medium on the user's home screen.
  Everything about the *render values* stays exactly as it was for photographs: the
  same six per-surface settings, the same `settings(Surface)` read, and `hasDepth()`
  still asks about the **still** files, because the real question has one answer and
  `DepthSource` is where it lives.
  - **The poster frame stays `imageName`.** §3 step 8 of `docs/VIDEO-PLAN.md` makes
    the poster "the still shown in the gallery, and the fallback if the video cannot
    decode", so keeping it on the photograph is what lets a clip degrade to a
    photograph on the spot rather than becoming a row with no image — which
    `LibraryItem.fromJson` drops, taking the user's photo with it.
  - **The pair invariant is in the type, not in a writer.** §2: colour without depth
    renders **flat, with no relief and no error**. Three loose strings could each be
    null with every writer having to remember; `VideoTracks` refuses to be built
    without both. `playable(filesDir)` asks the same pair one layer out, and a
    half-written clip object in a blob parses as **no clip at all** rather than as a
    row claiming a track that is not there.
  - **A running import is the pair invariant's one TRANSIENT exception, and the
    record is what keeps it from being silent.** `VideoImport` remuxes the range to
    the row's remux file (`LibraryItem.videoFileName`) *before* anything is
    submitted, so while a `Jobs` record exists the colour can play with a
    deliberately flat field — colour moving, flat
    relief — without the index ever holding half a clip: `item.video` stays null and
    the preview is a request passed to the renderer, never stored. What makes that not
    §2's silent flat clip is the record (the only thing that says the depth is
    expected) and the status line, which says "video preview, depth pending". The
    precedence lives in exactly one place, `Jobs.previewColourName`: **`item.video !=
    null` wins over a lingering record**, because at finish the tracks and the record
    coexist for a moment — and for a regenerate's whole run — and a preview there would
    restart a real clip; a row with no tracks and a record (FAILED included) is
    preview-eligible, and the app layer adds only the file check
    (`JobsStore.previewColour`). A preview must **grow in place, never restart**:
    `ClipPlayer.growTo` adds the depth/normal decoders to the SAME player on the SAME
    clock, seeded at the colour's current clip time (`ClipDecoder.startAt`), and
    `startClip`'s identity check grows instead of stopping when the running colour file
    is the one the tracks name — "a video that rewinds is a photograph".
  - **Filenames, never absolute paths — the same rule as an image.** §7 says so
    explicitly ("a video pair is two more filenames and the same rule"), and the
    consequence is sharper than for a still: a restored absolute path does not give a
    degraded clip, it gives a gallery row that renders nothing. Resolution goes
    through `PrefFiles` so both forms work.
  - **Two windows, one rule: a window claims nothing.** `DepthSource.UNKNOWN` (a PNG's
    chunk not yet read), `VideoSource.UNREAD` (a clip's container not yet read) and
    `NormalSource.UNREAD` all exist because defaulting an unknown to a negative *is*
    the bug this project has already shipped once. `VideoSource.Kind.POSTER` is the
    other half: a clip whose files are missing is **not** a photograph, and reporting
    it as one would offer a depth map to regenerate when what is missing is a file
    that was there.
  - **The bundled demo is refused a clip, from both sides.** `res/raw` ships three
    PNGs, so a clip on that row names files that cannot exist; `withVideo` throws and
    the parse ignores the key. Gated on the reserved id, which already gates the one
    other thing true of it (a null image name) — so this is one id-gated fact rather
    than a second special case.
  - **`Framing.sourceKind` is a float and is compared, unlike `bundled`.** It says
    what the *pixels* are rather than which row produced them, so a still and a clip
    at identical numbers are two pictures and a resolve that conflated them would drop
    a real frame. It is a float so `ofValues` can reproduce it; the **eleven-argument
    `ofValues` is deprecated** because it cannot, and `ReliefRenderer.snapshot()` is
    the one caller that has to move before any clip can be imported. Do not add a
    medium flag to `Framing` as a boolean — that is the `bundled` trap one level
    worse. It is still read off the row by default, and a row running a colour-only
    preview has no `video` on it, so the hosts pass the third argument of
    `Framing.of(item, surface, previewing)` — an **overload**, not a field — because
    the preview's pixels are video too and a framing that called them STILL would
    differ from the renderer's own snapshot on every resolve, defeating the
    no-op-paint guard and letting a glide stamp STILL onto a moving clip.
- **Shading is supplied, never derived, and flat lit is a deliberate state.**
  The old path derived a normal from the depth gradient
  (`normalize(-dzdx, -dzdy, 1)`); it is gone from both backends, as is the
  GLES3 vertex shader's central-difference code. A photo with depth and no
  normal has *correct displacement and plane shading* — `NormalSource` reports
  it and the sheet says "Flat lit — depth, no normals" with the fix. Do not
  reintroduce a derivative to make an unshaded photo "look right": that is the
  banding artifact the float blur exists to hide, and it makes the sheet's state
  a lie. The normal is a texture (`normal_opengl`, OpenGL convention, so G is
  **not** negated) sampled per fragment; it is part of the loaded pair identity,
  so a photo that gains one is re-installed rather than reported as already
  showing.
- **Origin moves the orbit centre and NOT the picture, in both backends, and the
  reversal is deliberate.** Origin used to translate the picture so the origin point
  landed at the CENTRE of the screen: the relief moved the camera to the pivot and
  aimed at it (`CameraModel.frame`), and the splat subtracted `(-2*originX,
  -2*originY)` from its clip-space shift (`SplatCamera.frame`). A tap therefore slid
  the picture until the point touched was centred — *"readjusting the view"* — and
  the user asked for that to stop. Both backends now mean the same thing: **at rest
  the picture is exactly the photograph, translated by Pan alone**, at every origin
  and every pivot depth, and Origin names only the ORBIT CENTRE.
  - **The relief is a TURNTABLE about the pivot with a FIXED rest eye.** The eye is
    `(0, 0, CAM_DIST)` — the image centre, looking straight down — rotated about the
    pivot `(originX*aspect*scale, originY*scale, h*scale*zScale)`, and the
    orientation rotates with it; at rest the rotation is the identity, so the view is
    the photograph whatever the origin is. The pivot keeps its OWN screen position
    under tilt (a rotation about a point leaves that point's view direction
    unchanged), so the subject under the origin still HOLDS while the background
    swings — the aim-at-the-pivot's job, done by the rotation axis. The angle is the
    splat's (`TILT_RANGE / CAM_DIST * motion`), so the swing is the same at every
    pivot depth; the old lateral slide's angle was `range / (CAM_DIST - pz)` and
    therefore grew with the pivot's height. The card mode's reference eye
    (`uSplatEye`) is that same fixed eye, not `(pivotX, pivotY, CAM_DIST)`.
  - **The splat's shift is Pan's alone.** Origin still picks the ray the pivot is
    found on and is the turntable's axis; it is not in `sx`/`sy`.
  - **The crosshair now sits at the origin's OWN image position, not the screen
    centre** — the visible difference, and correct: it marks the orbit centre.
  - **The tap must stay consistent.** `SplatRenderer.tapOrigin` undoes the drawn
    shift to reach the source camera's ray; with the shift pan-only it subtracts only
    the pan, or the tap lands wrong. The relief's tap is unchanged: unprojecting
    through the turntable resolves the plane point under the finger, and the pivot's
    depth is still the ray's own surface.
  - **An origin outside the cloud is now reachable and must not break anything.**
    `SurfaceSettings.ORIGIN_LIMIT` is **2.0** (±2 picture-widths); the splat tap's
    own clamp in `ReliefRenderer.screenToOrbit` derives from it. The pivot pick
    already falls back (the nearest splat, then the reference-plane point on the
    ray), and `SplatCameraTest` pins that a ray outside the cloud still answers a
    finite aim — no NaN. The `FramingRows` Origin **range** still derives from
    `ORIGIN_LIMIT` rather than a literal, and its old rationale — "where the camera's
    pivot reaches the plane's edge", true only because the shift dragged the origin to
    the centre — is rewritten; the warning it also carried, that the row, the stored
    value and the render must be ONE number, stands.
  - **THE COST, stated rather than discovered:** every existing photo with a stored
    origin looks different — its picture loses the shift. That is a deliberate,
    user-requested reversal, not a bug fix, and the acceptance test is the user's own
    eye on the device.
- **The 3D mode is one card per grid sample, DERIVED FROM `gl_VertexID`, and the
  focal is the only camera a photograph carries.** `render_3d` (a pref, read in
  `ReliefRenderer.reloadFromPrefs`, **off by default**) swaps the continuous relief
  for a point-splat cloud, GLES3 only. There is no buffer: one card is six vertices
  of `glDrawArrays`, and the vertex shader gets the sample (`gl_VertexID / 6`), the
  corner (the 0,1,2,1,3,2 pattern indexed by `gl_VertexID % 6`) and the UV from that
  id. **That is what makes the resolution a uniform** — the level pref is an index
  into `Splat.GRID_LEVELS` and the whole draw is a uniform block and one call — where
  one quad per sample in a VBO was 18 MB and a measured **7.4 s** build at the bottom
  level (three contexts per launch, 22 s of GL thread: the ANR the user saw as
  "isn't responding"), and 71 MB at the top one. The card grid is independent of the
  field's: the field is built at `GRID = 384` and a finer card grid interpolates it
  rather than reading detail it does not hold. The vertex shader's `uSplat` branch
  places every card on the ray from the eye the picture is PINNED to — the camera at
  rest, `uSplatEye` — through its own reference-plane point, closer to that eye than
  the point by the card's own depth, with the card's size scaled by the same depth
  ratio. The colour is sampled at the sample's own texel for all four
  corners — **flat**, which is what removes the shear, because a card's colour cannot
  stretch across it however the card is placed — while the normal keeps a per-corner
  UV — **a gradient**, because a flat normal per card makes a cloud of cards read as
  facets. The height is fetched once per sample, so **no triangle spans a depth
  cliff**, and the holes that open when the camera moves are the single view
  genuinely disoccluding: the honest part of the mode, not a bug. Every card stays on
  its own ray, so the picture at rest is the photograph whatever the Depth slider
  says and whatever the focal is — only camera motion reveals the depth.
  `Splat.cardSize` is `1.15 / (GRID + 1)`, the coverage constant: one grid cell plus
  a 15% margin, so the reference plane tiles rather than screen-dooring, and a card's
  angular size is its footprint's at every depth because the size carries the same
  depth ratio the position does.
  - **There is no backdrop, and that is a decision with two attempts behind it.** The
    gaps a tilt opens are the clear colour — black — and they were twice filled and
    twice rejected: first with a sixteenth-scale copy of the photograph, which is
    still a legible picture and, behind a cloud whose depth spread is most of the
    camera distance, lands tens of pixels from the surface it stands in for — a
    hole at a silhouette showed a second, soft edge of the subject, reported as a
    double exposure that was clear as day. Then with row-median bands (the palette,
    no edges, flat lit so the normal map could not put the silhouette back), which
    removed the doubling and was rejected on sight as not nice. The honest
    conclusion is that a fill with no content in it is not something to look at:
    **the gaps are for the inpainting pass to fill, and until then black is the
    truthful thing to show.** What the mode does keep from the first attempt is
    nothing at all — the code, the texture, the quad and the `:core` constant are
    gone rather than left behind a flag.
  - **The mode switch belongs to the branch that draws, not to a shared block above
    it.** `uSplat` selects the shader's card arithmetic; the geometry it is drawn
    with is chosen by the caller. While those were two conditions (`render3d` for
    the uniform, `render3d && aCorner >= 0` for the geometry) they could disagree,
    and they did: the cards stopped reading `aCorner`, the compiler dropped the
    attribute, `glGetAttribLocation` answered -1, the guard fell back to the RELIEF
    grid while `uSplat` stayed 1 — so the card maths ran over the relief's vertices
    with `uSplatGrid` still 0, `sx / uSplatGrid` was NaN, and every triangle was
    discarded. The screen was black in every mode with no error anywhere, and the
    only tell was the marker still drawing. Each branch now sets its own mode, and
    `aCorner` is gone from the app entirely: no shader reads it any more, so a
    field that only ever answered -1 is a field that means nothing.
  - **The aspect goes on X and only on X, where the relief puts it.** The card's
    footprint is the cell it replaces — `aspect/GRID` wide and one grid cell tall — so the
    sample's position and the x half of the footprint carry `uAspect` and the y half
    does not. Written as `(aPosition + aCorner * uSplatSize) * uAspect` it squashed
    every non-square photograph to its own aspect and stopped the cards tiling
    vertically at all: a portrait 9:16 filled the screen's width and 56 % of its
    height, reported as all 3D media looked shorter than it should. The invariant
    to keep is the one the relief's own `else` branch states in one line: the
    `h == 0` position must be *the relief's position*, x scaled and y not, because
    that equality is the whole of "the picture does not move when the mode is
    switched". No `:core` test can hold a GLSL expression, which is why this is
    written down here rather than asserted — and it survived a full green gate with
    the picture visibly wrong, so a splat change has to be looked at on a device.
  - **The card is placed on a ray from the camera AT REST, not from the moving one.**
    `uSplatEye` is the tilt-free eye, and with the relief's camera now a turntable
    about the pivot that eye is the FIXED rest eye `(0, 0, CAM_DIST)` — the image
    centre looking straight down, the same camera the photograph is pinned to at every
    origin — and each card sits on the ray from it through its own reference-plane
    point, closer by its own depth. Scaling the sample's position about
    the MODEL CENTRE (`q * (zEye / uCamDist)`, what the branch did) is that same
    construction with the eye assumed to sit on the model's z axis; that assumption
    does not hold once the camera moves at all, and the whole cloud was then placed as
    if the eye were the model's centre, which the user reported as relief orbiting around the bottom-left while 3D stayed centred. **Feeding it the
    MOVING eye
    is not the fix and would destroy the mode**: a point on the eye's ray projects to
    that eye's pixel whatever its depth, so every card would land exactly on its own
    pixel and the render would be identical at every Depth and every tilt — no
    parallax, no gaps, no reveal at all. The depth has to be fixed in world space and
    the camera has to move through it. `uCamDist` went with the old form rather than
    being left as a uniform nothing reads; the 0.15 margin is now the ray's distance
    (`closer = max(dist - zOff, 0.15)`), and the `h == 0` position is still exactly the
    relief's.
  - **The pivot is the SURFACE point under the origin, not the plane point behind
    it, and the TURNTABLE's axis is what carries it.** `CameraModel.setPivotHeight`
    takes the blurred field's height under the origin's image position
    (`ReliefMesh.heightAt`, in model units), and `frame` places the rotation axis at
    `(px, py, h * scale * zScale)` — the plane point lifted through the same
    model-to-world factor `uMVP` applies. The plane point was the old axis: the plane
    held while everything nearer swung, which the user reported as an orbit defined only on the 2D plane, and the pivot belongs on the subject's nose rather than the
    background. **The rest eye is FIXED** at `(0, 0, CAM_DIST)` — it does not move
    with the pivot's depth OR with the origin — so at rest the view is the photograph
    and the pivot keeps its own image position; the axis's depth moves the orbit and
    the marker and nothing about the picture. Measured off-device over 4 aspects x 3
    surfaces x 3 zooms x 4 depths x 4 origins x 3 tilts: the at-rest projection of the
    plane is identical for every origin and every pivot height (double precision), the
    splat card still lands on its own reference pixel (7e-15), the pivot keeps its own
    screen position under tilt while the plane point under the origin swings up to 0.21
    NDC, and every visible card corner still moves under tilt (min 8e-6, max 1.13 NDC).
    No model-scale compensation is needed and none should be added — the naive
    `eye = pivot + CAM_DIST` would move the eye off the plane's distance and shrink the
    picture (0.5 * 0.9 * `CAM_DIST` of pivot is a 31% zoom-out), and scaling the model
    to compensate would zoom the whole photo as the origin moved from a near subject to
    a far background. The pivot is the RELIEF's
    surface; the splat's focal depth gain is deliberately not folded in, so the
    aim is one camera for both modes and the mode toggle does not jump. A clip's
    pivot comes from the still POSTER's field — the live depth is a GPU texture
    with no CPU copy — so it is one number per pair, like every other framing
    value. The marker reads the frame's pivot and `uSplatEye` is the frame's fixed rest
    eye — the crosshair sits on the orbit centre and the cards are placed from the
    camera the photograph is pinned to — so they
    cannot name different pivots, and `screenToOrbit` resolves the tap through
    the same pivot depth the draw used. **And the marker is drawn in the SPLAT
    branch too, not only after the relief's `drawRender`.** The branch that draws
    the Gaussian cloud returns before the relief's marker call, so a row in Splat
    mode had no origin indicator at all — the one place the pivot is otherwise
    invisible — and it stayed that way until it was reported. `SplatRenderer.draw`
    therefore returns the frame it drew with, and the host draws the crosshair from
    that frame, so the marker and the cloud cannot name different pivots. The
    marker's radius is a constant ANGLE in both modes: a fraction of the model
    scale for the relief, a fraction of the eye-to-pivot distance for the cloud,
    because a cloud twice as far away would otherwise draw a ring half the size.
    The cloud may also have drawn through an offscreen target, so the host restores
    the surface viewport before the marker — an overlay drawn into a half-size
    target would land in the wrong place.
  - **The focal does NOT go into the ray, and that is a decision with a measurement
    behind it.** `X = (u-0.5)/(f/W)*Z` is the unprojection through the PHOTO's
    camera; drawing it through this renderer's camera — fixed 45°, because it is the
    same camera the relief and every gesture use — re-frames the picture, and a
    phone's main camera is nearer 70° than 45, so the same photo would arrive up to
    70% larger and cropped. The ray is therefore the drawing camera's own and the
    stored focal scales the DEPTH (its image plane sits `f/H` image heights in front
    of the eye); scaling the whole cloud about the eye is the same statement the
    other way round, which is why this is one implementation and not two. `SplatTest`
    pins the arithmetic. A row with `focal == 0` draws the documented **50-degree
    default** (`f = H/2 / tan(25°)`) rather than nothing: a window claims nothing,
    but a look must not vanish.
  - **The node fits the focal once per run, and the app stores it on the row.**
    `_fit_focal` regresses `X/Z` against `(j + 0.5) / W` (and `Y/Z` against `(i + 0.5) / H`)
    on the first window's `points`, which is legitimate because MoGe's intrinsics
    are ONE number: square pixels, centre exactly (0.5, 0.5) by construction
    (`intrinsics_from_focal_center`), measured reproducing the point map to 4e-6
    relative (p99 5e-4). The mean of the two axes, scaled from the model input to the
    uploaded frame, is written to the sidecar and the ui payload; a fit that is not
    finite and positive writes 0. `VideoImport.attach` writes it through
    `withFilesAndVideo` — the one finish line an import already uses — so the files
    and the camera arrive together and a regenerate replaces it, and there is
    deliberately no `withFocal` setter that a later writer could forget.
  - **A focal is part of the pair identity, so a row that gains one is a
    re-install.** `showingSamePair` takes it and there is deliberately **no
    three-argument overload to forget it**: a caller that omitted it would compare a
    row's real focal against 0, answer "not the same pair" on every resolve and
    re-decode the photograph each time — the waste the guard exists to prevent, from
    the safe-looking call. `PairCache` keys on it for the same reason. The field is
    0-or-absent in the blob (omitted from `toJson` when 0, no schema bump — the
    `normal`/`video` rule) and every mutator carries it; `VideoLibraryTest`'s
    `everyMutatorCarriesTheClip` walk asserts it beside the clip, because it is the
    same shape of value: added after the writers existed.
  - **A cloud carries its OWN lens, and it is a different number in a different
    unit from the row's `focal`.** A Gaussian's screen size is proportional to the
    focal it was reconstructed through, and every `.splat` was built at its own —
    the node reports it in the sidecar (`focal`, `height`, both in pixels of the
    frame it was handed) and `SplatRenderer` draws the cloud through the source
    camera that focal implies. The row's `focal` beside it is a MoGe measurement in
    pixels of the COLOUR image and drives the relief's depth scale; a cloud is built
    from the poster still, so the two can be different ratios and folding them into
    one field would let the clip path and the cloud path overwrite each other's
    units. The cloud's lens is therefore stored as the dimensionless
    `LibraryItem.splatLens` = `focal / height` (`Splat.focalOverHeight`), because a
    focal in pixels is only meaningful against the frame it was measured on and the
    phone may decode the still at a different height and draw it on a different
    surface; the ratio is the lens itself. `Splat.fovyForFocalOverHeight` converts
    it to the camera's field of view.
    - **The fallback is the demo cloud's constant, and that is deliberate.**
      `SplatRenderer.SOURCE_FOVY`/`SOURCE_TARGET_Z` are the bale's 30 mm lens and
      front surface, and a row with no stored lens (every cloud generated before
      this field existed) draws at exactly those — so those clouds keep the look they
      have and change only on a regeneration, which reads the sidecar. `0` is
      unknown and omitted from the blob, the `normal`/`video`/`focal` rule, so no
      schema bump; `SplatImport.fetch` is the one finish line that writes the lens
      with the cloud it describes, through `withSplat(String, float)`, and the
      one-argument form carries the lens rather than clearing it (the `withFiles`
      rule for `focal`).
    - **The pivot's fallback is the cloud's own centre, not the bale's 2.4.**
      `SplatCloud.medianDepth` is the loaded cloud's median `z`, warmed on the IO
      thread at load and read on the GL thread at upload; the frontmost splat under
      the origin's ray remains the real pivot, and the demo target answers only when
      there is no cloud at all.
    - **The lens is not part of the pair identity.** It is read at draw time from
      the wanted row, so a row that gains a lens repaints through the loaded cloud
      with no re-decode; a new cloud FILE (a new filename) is the identity change,
      exactly as it was before.
  - **The splat look is four per-photo, per-surface values, and two of them are
    different things that both look like "softness".** The problem they tune: a 150k
    cloud is drawn at roughly one splat per 12 px with ~1 px splats, so a bright flat
    field reads as a screen-door — invisible on mid-tones, black-and-white noise on a
    bright sky. **They were global dev prefs while the look was still being chosen by
    eye; the tuning is over, so they are in the counted chain now** — on
    `SurfaceSettings`, carried by its positional `with`, resolved by `Framing` and
    compared by `sameAs`, exactly as Depth and Motion are. The schema went to **v5**
    and `Library`'s v4→v5 migration seeds every item's home surface from the old keys
    (`ReliefRenderer.KEY_SPLAT_EPS2D` / `KEY_SPLAT_BLUR` / `KEY_SPLAT_DEPTH` /
    `KEY_SPLAT_SCALE`), read once by `AppState` and never at draw; a value equal to its
    default is written nowhere, so an untouched library's items are byte-identical.
    The product defaults are unchanged: `Splat.EPS_2D_DEFAULT` = 0.5 of the cell,
    `Splat.BLUR_DEFAULT` = 0, `Splat.DEPTH_MUL_DEFAULT` = 1,
    `Splat.RENDER_SCALE_DEFAULT` = 1. **BUDGET is the one that stays a pref**: it is
    generation-side, read at submission, and the number the user compares by eye is
    the number that runs — see below.
    - **SIZE is a coverage lift, not a blur, and that distinction is the whole
      point.** The `eps2d` floor lifts only the splats whose screen covariance is
      too small to sample and leaves the well-sampled ones alone, so it removes the
      screen-door without smearing detail. The knob stores a FRACTION OF THE CELL
      (`Splat.EPS_2D_DEFAULT` = 0.5, the row spans **0..0.80** — the bottom is no
      floor), and the draw
      resolves it to exactly `fraction × cell`, with the cell the ON-SCREEN one
      (`viewportArea / count × zoom²`). **There is no second, target-area minimum**,
      and deleting it is what made the knob live at every count.
      - **The minimum was the SIZE knob frozen at one cloud's count.** Its
        `SAMPLING_MIN_AREA_FRACTION` was `6 / (912 × 1984)` of the render target's
        area — the clean 6 px² measured on the 149,504-splat beach — and
        `EPS_2D_DEFAULT / that fraction` is **150,784 splats, 0.9% from the beach's
        own count**. So at the default the two terms were one statement: `0.5 × cell`
        for the beach reproduces the recorded minimum within 1% at all four render
        targets (0.939 / 3.756 / 6.051 / 15.03 against 0.931 / 3.72 / 6.00 /
        14.90). It could only ever place a floor *above* `fraction × cell`, and past
        the count where it exceeded the raw cell — `1 / that fraction = 301,568`
        splats, ratio 0.50 at the beach, 1.34 at 403,456, 3.47 at 1,046,528 — that
        excess was over-blur. That is why it first needed the cap
        `min(minimum, cell)` and why the cap is now gone with it.
      - **Above the old crossover the floor drops from the cell to `fraction ×
        cell`, so a dense cloud is sharper AND cheaper.** Measured on the 6T's
        403,456 bundled row (scale 1.00, Motion 100, zoom 0.62, so the on-screen cell
        is 2.408 and the default floor goes 2.408 → 1.204): median `gpuMs`
        **37.45 → 33.16, −11.5%**, by continuous sensor draws after a visibility
        change — the hot method, and the same method both sides. (The *original*
        uncapped minimum would have been 8.380 here.) The smooth region is still the
        clean reference render's **0.70× the photograph's own high-frequency
        energy**.
      - **Provenance, marked rather than trusted.** The *cap's* own figure —
        median `gpuMs` **56.1 → 37.7**, read as "~33% of frame time", floor
        8.380 → 2.408 — was recorded in the commit that added the cap, **before
        the release-install rule in Build constraints existed**, so its build's
        provenance is unrecorded and it is very likely an un-AOT'd build. On such
        a build the frame is CPU-dominated (`sortMs` in the hundreds of ms), and a
        fill change's ratio does not transfer to a `status=speed` build; it must
        not be quoted as a frame-time figure. The **`−11.5%` deletion figure above
        WAS measured on the release APK and is the sound one.**
      - **The bottom of the range is the identity, and it is a real setting.** The
        floor once stopped at 0.45 "so that every setting the control offers is one
        that works"; that premise is what fell. SIZE is per photo, and the gap it
        closes is the cell, so one shared minimum made every cloud obey the sparsest
        cloud's requirement — a dense cloud, which needs no lift, was smeared to
        provide one. At `0` the floor vanishes and the splat is drawn at its own
        covariance, so the picture is purely the cloud's density — which is what the
        count measurements say determines sharpness. Below about 0.4 of the cell on a
        SPARSE cloud the screen-door returns: the 149,504 beach at 0.5 of the cell
        resolves to the ≈6 px its sky needed, and at 0 it gets no lift at all. That is
        a real look reachable on purpose rather than a broken region the control
        refuses to enter, and the default 0.5 sits in the middle of the useful band.
        **No schema bump**: `0` was previously clamped *up* on write, so no stored
        blob contains it and every existing photo keeps its value.
      - **The SIZE row is live at every count now** — it was inert above 301,568
        splats, where the floor was the cell and `fraction × cell ≤ 0.8 × cell` could
        only ask for more. That is the whole point of the change.
    - **BLUR is a real post-softness on the splat FBO, and it trades detail away.**
      One extra FBO and a separable Gaussian (13 taps per axis, fullscreen triangle
      from `gl_VertexID`), skipped entirely at radius 0, so the identity costs
      nothing. `Splat.BLUR_MAX` = 6 render-target pixels. **MSAA was assessed and is
      NOT the tool**: it antialiases the EDGES of the triangles that are drawn, and
      the artifact is the BLACK GAPS between splats that are not drawn — a coverage
      problem, not an aliasing one, which is why the 4x MSAA already on the surface
      (`ReliefRenderer.enableMSAA`) leaves the screen-door exactly as it was. **Only
      density (BUDGET, a regeneration) gets the detail back**; the blur is honest
      about being a trade, not a fix.
    - **BUDGET is generation-side and is read at submission, not at draw.** It is
      the `budget` the app sends to `SharpStill`, read in `SplatImport.generate`
      from the same pref the build dialog's Splat tab states (`BuildDialog.cloudBudget`), so
      the number the user compares by eye is the number that runs. The ladder's own
      verdict is 150k soft, 741k right, 1.05M indistinguishable from full — and on
      the measured phone 741k ≈ 18 fps, 1.05M ≈ 13. A dev changes it and presses
      Regenerate on one row to compare. The tab's `~10 s · ~4.8 MB` estimate is
      deliberately NOT scaled by the budget: it is the node's own reference run, and
      a scaled guess would be a second, unmeasured number.
    - **The four rows are shown only when they can change a pixel** — the selected row
      is in Splat mode AND names a cloud file that is here, the two conditions
      `ReliefRenderer.useSplat()` draws under. A row in Splat mode with no cloud
      renders its relief, where all four are inert; a control that cannot change
      anything is the lie this file keeps refusing to ship. They are built and removed
      by `MainActivity.syncSplatRows` exactly as the relief's Depth row is by
      `syncDepthRow`, and they dim the sheet while held (`dimWhileInteracting`) so the
      render shows through the finger. Budget stays in the build dialog's Splat tab
      because it changes nothing on screen to reveal.
    - **The floor's and the blur's per-setting cost is the one thing still
      unmeasured, because the phone is unreachable** (`No route to host`). The
      `splat:` STATS line now prints `eps2d=` and `blurPx=` beside `count`/`fps`/
      `gpuMs`, so the first device session can read the cost straight off a log —
      that is what those two fields are for.
    - **A splat's quad is clipped to the radius its own alpha survives, so the fill it
      removes was already being paid for and thrown away.** The fragment shader
      discards a fragment whose alpha falls below `Splat.ALPHA_DISCARD` (1/255), and
      that boundary is a RADIUS: with `power = -0.5 r²` the fragment survives when
      `alpha * exp(-0.5 r²) >= thr`, i.e. `r <= sqrt(2 ln(alpha/thr))`. The vertex
      shader emits the quad at the 3σ extent `r = 3` and scales it by
      `Splat.extentScaleForAlpha` — `keep = min(1, sqrt(2 ln(a/thr)) / 3)` — so the
      quad covers EXACTLY the region the fragment stage keeps and **no pixel that was
      drawn before changes**. `theQuadShrinksToExactlyWhereTheFragmentShaderDiscards`
      inverts the mapping and pins that the scaled edge lands on the discard boundary,
      just inside it survives and just outside it is discarded.
      - **One threshold, and it is a uniform, not two literals.** `Splat.ALPHA_DISCARD`
        is the source of truth; the renderer uploads it as `uAlphaDiscard` and BOTH
        stages read it. A literal copied into VERT and FRAG is the drift this avoids:
        the quad would end somewhere other than where the discard begins, and the
        difference is a soft ring of clipped splats no test could name. (`check-shaders`
        joins only string literals, so a Java constant inside a shader string would be
        silently dropped from the compiled source — another reason it is a uniform.)
      - **An opaque splat is the identity and a splat at the threshold draws nothing.**
        `sqrt(2 ln 255) = 3.33 > 3`, so the full quad is kept for every `a >= 1`; the
        quad only shrinks below `a = thr * exp(4.5) = 0.353`. At `a <= thr` every
        fragment would be discarded, so the vertex shader drops the splat outright.
        **Measured on the shipped demo cloud: 8.3% of splats are below 0.353 and the
        mean extent is 0.988** — a ~2% fill cut on a high-opacity cloud, so this is a
        small win there and a larger one on a cloud with many faint splats. The stats
        line reports `alphaDrop=`/`alphaShrank=`/`alphaExtent=` precisely so a device
        log says which it was, rather than a claim.
      - **`gpuMsPerMpx` is the fill measurement the next step reads.** `gpuMs`
        normalized by the render target's megapixels (`render=WxH` is already on the
        line): the renderer is fill-bound, so this ratio stays flat as the render scale
        changes and rises with the average splat coverage. It is a diagnostic, not a
        control — there is deliberately no user-facing knob for it.
    - **The near-plane pull-back is a MINIMUM VIEW DISTANCE per splat, and the moment
      it becomes a camera move it breaks the at-rest invariant.** The vertex shader's
      radius clip drops a splat whose 3σ extent exceeds half the frame — gsplat's
      `radius_clip`, because a splat that close to the eye costs a screenful of
      fragments to lay down a wash; measured at 669k splats, **922 ms a frame with
      those drawn against 76 ms without them**, which is the whole of the stutter.
      Dropping leaves the hole the wash was covering, so a splat nearer than
      `SplatCamera.minViewDistance` is moved back **along its own view ray** to that
      distance instead (`mv.xyz *= zmin / depth`). That scaling keeps the splat's
      screen position EXACTLY — both `clip.xy` and `clip.w` scale by it, so the NDC is
      unchanged — and divides its projected covariance by the square of it, because
      every perspective Jacobian entry goes as one over the depth. The SIZE is
      deliberately NOT scaled with the position: that is what "no fov compensation"
      has to mean here, and it is why the splat is drawn smaller rather than drawn
      whole.
      - **Pulling the EYE back instead is the one thing that must not be done.** A
        point at depth `z` then projects to `f x / (z + d)`, which is the photograph
        only at the reference plane, whatever focal is chosen — so `SplatCameraTest`'s
        at-rest assertions go red for every splat off it. Moving the splat leaves the
        frame untouched, which is exactly what those tests guard.
      - **The minimum cannot be a single depth.** A cloud's `size / depth` is
        heavy-tailed (the largest 3D axis over its depth, over the median, measured
        **7–245×** across the clouds in the tree), so a threshold derived from the
        worst splat would pull back and visibly shrink the whole near field. Tying it
        to each splat's OWN size touches exactly the splats whose extent is the
        problem.
      - **And per-splat means the `O(count)` pass the design expected is not needed at
        all.** The design looked for the nearest drawn splat's depth; but the splat's
        size is already in the vertex data and its minimum is proportional to it, so
        the only per-framing input is the projection's own `proj5` — one uniform,
        `SplatCamera.minViewDistancePerSize` — and a cloud with nothing near the
        camera is untouched by construction (no splat satisfies the clamp). Measured
        on five real clouds (149k–1.18M splats), the largest `size / depth` is
        **0.0133**, against a threshold of 0.0795 at fovy 100 and 0.0276 at fovy 45,
        so none of them is touched at any normal framing (at the extreme 20° zoom a
        mild clamp can just reach the 0.0133 cloud — which is correct: zooming in
        magnifies extents).
      - **`SplatCamera.MAX_EXTENT_FRACTION = 0.1` is the number, and it is not
        arbitrary.** The bound it states is the ON-AXIS extent, and the Jacobian's
        off-axis term magnifies it by up to `sqrt(1 + tan²)`; at the frame corner that
        is enough to cross the clip's own 0.5 threshold, which would put the splat
        straight back into the drop. `thePullBackKeepsTheSplatUnderTheRadiusClip`
        walks the corners and the framings and **fails at 0.5 (mutation-checked)** —
        so the clamp guarantees a splat is DRAWN, which is the whole point of it.
        `thePullBackKeepsASplatOnItsPixelAndShrinksIt` pins the exact-position and
        scaled-extent property through the real projection chain.
      - **The device tell is `pullbackAspect=` on the `splat upload:` line** — the
        loaded cloud's own largest `size / depth`, printed once per upload, because
        the vertex shader cannot count what it clamped. The clamp can engage only
        above `1 / minViewDistancePerSize(proj5, …)`, so a number below that says the
        cloud is untouched and a number above it says which splats are being pulled
        back.
- **The depth blur must stay in `float`.** 8-bit depth has ~1-level terraces;
  grazing light amplifies them into visible contour lines. The fix is a
  separable box blur in float precision before tessellation — **re-quantising the
  result to 8-bit recreates the terraces and the artifact returns.**
  `DEPTH_BLUR_FRACTION = 1/78` is a fraction of the **grid** width, because
  `ReliefMesh` resamples to the grid before blurring. Change the radius and you
  must regenerate the goldens (`RegenerateGoldens`) and re-check banding on a
  real photo; the bundled demo map is only 4-level quantised and hides artifacts.
- **The system replaces the live wallpaper surface with a frozen snapshot, and
  it is not a timing quirk.** `WallpaperService.Engine.freeze()` (AOSP
  `frameworks/base`, `core/java/android/service/wallpaper/WallpaperService.java`)
  captures the current surface into a `HardwareBuffer`, parents it to the engine
  at `setLayer(Integer.MAX_VALUE)`, **and then hides the engine's live BLAST
  surface**. What stays on the home screen is that frozen buffer, by design,
  not a frame the engine is still painting. `Engine.isFrozen` is simply
  `mScreenshotSurfaceControl != null`, so a frozen engine reports a state that
  is not the truth. This is why the first load per engine must be synchronous —
  `ReliefRenderer.setSyncFirstLoad(true)`, which is **our own method, not a
  platform API**, despite reading like one — or the snapshot is captured empty
  and nothing is alive to repaint it. The platform hook for this is
  `Engine.shouldWaitForEngineShown()`, which returns `false` by default; it is
  `@hide`, so it is not in `android.jar` and cannot be called without
  reflection, which is why we do the work ourselves.
  The engine is also short-lived, which is why nothing may be cached across its
  lifetime: a wallpaper showing the wrong photo is usually a *live* engine reading stale
  bookkeeping, or a snapshot outliving the engine that drew it, and those two
  look identical from the home screen. `DepthWallpaperService` logs one line
  per resolution under the `Basrelief` tag with the visible flag, whether the
  surface is still valid, the item it resolved, `which=<HOME|LOCK>`, and what the renderer believes
  is on screen. Grep that before theorising — three wrong theories cost a
  whole session once already, and the fix was in a commit message rather than
  in this file, which is the same as not writing it down. Note the corollary:
  **an engine that is correct can still be invisible**, because the home screen
  may be showing the system's snapshot rather than the engine. That is what made
  a gallery selection appear to do nothing until Set as wallpaper was pressed
  again. It is no longer the mechanism, because it never could be — see below.
- **A view on top of a `GLSurfaceView` must not be positioned with a
  post-layout transform. This is a documented platform limitation, b/7179570,
  and it is the whole explanation for the invisible-sheet bug.** `SurfaceView`'s
  own class javadoc says it: *"The transparent region that makes the surface
  visible is based on the **layout positions** in the view hierarchy. If the
  **post-layout transform** properties are used to draw a **sibling view** on top
  of the SurfaceView, the view may not be properly composited with the surface."*
  AOSP recorded the limitation in 2012 (commit `c9f345ff7bc5`) rather than
  fixing it, and it is still in the API 27 SDK sources, so it has never been
  fixed. The mechanism is visible in `SurfaceView.setVisibility`, which
  deliberately calls `requestLayout()` with the comment *"the transparent region
  is computed as part of the layout phase, and it changes (obviously) when the
  visibility changes."* `ViewRootImpl.performTraversals` recomputes the region
  and, on a change, sets `mFullRedrawNeeded = true`. `setTranslationX/Y` runs
  no layout pass at all. So: **visibility and layout changes reliably repaint
  the overlay; transforms do not.**
  Measured on the Pixel 7 Pro, the failure looks like a view that is placed
  correctly, opaque, attached, `isShown()`, in a window that has drawn 904
  frames — receiving every tap and absent from the display. Input keeps working
  because touches are dispatched from the view hierarchy, not from the window
  buffer. The sheet therefore positions itself by **layout height** and the
  `＋` expand is instant; see `applySheetHeight`. If you ever want to animate
  the sheet, do it with `LayoutParams` or margins, or call `requestLayout()`
  on the `GLSurfaceView` after the change — do not reach for `translationY`.
  A structural alternative is `setZOrderOnTop(true)` on the `GLSurfaceView`,
  which sets `subLayer = +1`, makes `isAboveParent()` true and therefore skips
  `clearSurfaceViewPort()` and the transparent region entirely — nothing can
  desync because no hole is punched. The cost is that nothing in the window
  composites over the GL content.
- **No third-party app can re-bind its own live wallpaper. Stop trying.**
  Measured, not assumed: `setWallpaperComponent` is refused by
  `WallpaperManagerService` with `SecurityException: Access denied ... must have
  permission android.permission.SET_WALLPAPER_COMPONENT`, and that permission is
  `signature|privileged`, so declaring it changes nothing — `dumpsys package`
  shows it requested and never granted. `WallpaperManager` has no public
  equivalent (`setBitmap`, `setResource` and `setStream` all replace a live
  wallpaper with a static image), so the system picker is the only route, which
  is what makes "Set as wallpaper" the apply gesture rather than a convenience.
  There was a `WallpaperRefresh` class that tried it by reflection, down to a
  second route through the binder; both were refused, always, so it has been
  deleted rather than left as a reflection path that can only fail and a status
  line telling users `rebind: REFUSED` as though their device were at fault.
  Its one surviving fact — the component users select — now lives on
  `DepthWallpaperService` as `wallpaperComponent`, beside the service it names.
  **The engine follows the index by itself**; see the "state that records this
  is on screen" invariant for how that was made true rather than papered over.
- **`am force-stop` on a package that *is* the active wallpaper makes Android
  revert the wallpaper to the default static image.** This is a sharper form of
  the trap above, and it cost a long session: every screenshot afterwards is a
  static image (usually black), the engine never runs at all, and the app looks
  broken when it is not. Force-stopping "for a clean test" is what silently
  uninstalled the wallpaper under a dozen consecutive experiments. For a cold
  start, launch the activity and press HOME; never stop the package.
- **A new `onSurfaceCreated` means a new EGL context, so the renderer must forget
  every GL name it cached.** The wallpaper loses its context routinely, because
  the system tears the engine down and rebuilds it. `ReliefRenderer` keeps
  `hasInstalled` for this, cleared alongside `textureColor` in
  `onSurfaceCreated`: without it `reloadFromPrefs` sees the pair it wants is
  already showing and returns without uploading, into a context that holds no
  texture. Symptom: a black wallpaper beside a diagnostic reporting the correct
  photo loaded. `glGenTextures` returning **0** is the tell — name 0 is the
  default texture, which has no image and samples pure black, so it is what a
  call made with no current context leaves behind. A texture name of 0 is never
  a success.
- **A load can land while the GL thread is parked, and a texture name of 0 means
  the install did not happen.** The studio writes the index while the wallpaper
  is hidden, and the engine's pref listener queues the reload; `queueEvent` then
  runs it *before* `onResume`, so `upload()` executes with no current context
  and returns 0. The frame that follows is black. What made this permanent rather
  than transient is that the renderer recorded the pair as installed anyway, so
  the next `onVisibilityChanged(true)` found `showingSamePair` true and declined
  to retry — the black outlived the condition that caused it. `install()` now
  returns false for texture 0 and nothing is recorded, so the next visibility
  change retries and succeeds. This is the same mistake as `hasInstalled` and
  `onSurfaceCreated` one level deeper, and the pattern is the lesson: **any state
  that records "this is on screen" must only be written when the GL calls that
  put it there actually succeeded.**
- **The probe for "is there a current context" must be EGL, never a GL call.**
  `SplatRenderer.upload` refuses a queued upload that ran before the GL thread had
  a context, and it asked `glGetString(GL_VERSION) == null` — which is what
  AGENTS.md said it answers. On Mali it does not: it dereferences the absent
  dispatch table and takes the process down. Measured on the Pixel 7 Pro: the
  studio's first splat load produced `SIGSEGV in glGetString+44` from
  `SplatRenderer.upload`, `applySplat` on `GLThread`, every launch — the studio
  was unusable, and the empty-log "compile failed" it was written to prevent was
  never reached. `EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT` answers
  the same question and touches no GL state. **A guard whose own probe can crash
  is worse than no guard**, and this one had been a landmine since it was written.
- **Decodes run off the GL thread and finish out of order, so the newest request
  must win by sequence, not by arrival.** A runnable queued while the engine was
  hidden replays the *previous* selection, and a large PNG decode can complete
  after a newer small one, installing the old photo last. Observed on device
  (`STALE` in a temporary trace), not merely possible. `loadSeq` tags each
  request and a result whose tag is not the newest is dropped.
- **Matrix order.** `clip = proj * view * model`. Any unprojection must invert
  `proj * view` in that same order; `view * proj` is a real bug.
- **`apply()` refuses an instant paint of a frame already on screen, and the guard
  lives there -- not at the call sites -- with the two legitimate escapes named.**
  The rule: *nothing may be painted instantly; a frame can only be reached by gliding towards it* `ReliefRenderer.apply(Framing, Apply)` no-ops a
  `RESOLVE` whose framing `sameAs` the snapshot, and lets only `GESTURE` (a slider
  must lead the finger) and `PAIR_CHANGE` (a different photo; gliding it would smear)
  through. The default overload is `RESOLVE`, the safe one, so a future caller cannot
  inherit the paint by accident. It is a method and not a rule copied to the five call
  sites (`glide`'s origin, `stepGlide`'s completion, `cancelGlide`, the wallpaper's
  resolve and pre-hold, the studio's `applyFiles`) because four of those five were
  wrong at some point this session and a sixth would reintroduce it silently. The
  measured symptom, twice: a resolve re-painted a frame a glide was easing toward --
  lock's `2x` over home's `1x` -- which the eye reads as a snap. **A glide's ORIGIN
  must be what is showing, never a guessed surface label**: `glide(null, to)` takes
  `snapshot()`, and passing a `Framing` computed from `lastVisibleSurface` painted the
  wrong config on the return to the launcher because the lock engine records itself as
  last-visible while the keyguard is up.
- **The lock frame is held on the HOME engine while the screen is dark, and that is
  not the instant paint the rule forbids.** `holdLockFrameForUnlock` applies the LOCK
  framing to the HOME engine on hide, but only when `!isInteractive()` or the device is
  locked — `if (interactive && !locked) return;`, and **neither of those two readings
  is allowed to be a constant.** `GLSurfaceView` queues the event and refuses to draw
  while paused
  (`readyToDraw()` requires `!mPaused`), so no frame is presented: the next drawn frame
  -- the one the keyguard reveals -- is already LOCK, and the unlock's
  `glide(null, HOME)` origin-apply is a no-op. It is **load-bearing and not superseded
  by `inheritFirstFrameFrom`**: measured, disabling it makes a surviving HOME engine
  hold HOME across a lock cycle, so the unlock resolves `snap.zoom=1.42 dest.zoom=1.42`
  and the glide is gone. Inheritance covers a *freshly created* engine; the pre-hold
  covers a *surviving* one.
  **The guard this sits behind had a term that could not be true, and the log line
  that was supposed to prove it did not exist.** It read
  `if (isInteractive() && !isDeviceLockedNow() && !keyguardWasUp()) return;`, where
  `keyguardWasUp()` is `KeyguardManager.isKeyguardLocked()` — and whose own javadoc
  says it reports "whether the keyguard exists at all", a **capability**, while the
  guard used it as "the keyguard has taken over", which is **state**. One question
  asked twice, once with a reading that tracks the cycle. The term is gone and the
  decision is now the two readings that both demonstrably move.
  **What it cost to find: the two claims about that term disagreed.** One recorded
  `isKeyguardLocked()` as `true` on every sample of every cycle, making the third
  term a constant `false`; measured here on the same device it reads **`false` while
  awake and unlocked and `true` while locked** — it moves. So the old guard would
  have behaved identically in this cycle, and **this is a robustness fix rather than
  a demonstrated bug fix**: the correctness of the pre-hold was resting on a signal
  whose relationship to lock state is undocumented and has now been measured two
  ways. It is still strictly better in both worlds — on a device where that reading
  *is* constant, the old guard ran the pre-hold on every hide, including a studio
  covering the wallpaper, which is the one case the rule exists to prevent.
  **And the proof that was cited did not exist:** the javadoc said "when a studio
  covers the wallpaper the hide logs `interactive=true`, this returns" — that string
  matched only the comment. There is now a `pre-hold enter …` / `pre-hold hold=…`
  pair, logged **before** the gates, because a log that only appears on the decision
  path cannot distinguish "the guard said no" from "the method never ran" — which is
  exactly what happened on the first attempt, producing zero lines and no way to tell
  which it was. Measured on a Pixel 7 Pro, sdk 37, both surfaces bound separately:
  studio covering an unlocked wallpaper logs `hold=false`, the screen darkening logs
  `hold=true`. **The glide itself is not loggable** — `origin=snapshot`, `applyNoop`
  and `applyPaint` are trace strings from instrumentation that is not in the tree, so
  whether the unlock glide still runs is the user's eye, not a log line.
- **Never hand-derive vendored matrix semantics and verify them against a
  reference you wrote yourself.** `Mat4.scaleM` shipped computing `S·m` instead of
  `m·S` because my hand-written AOSP reference was "corrected" to match my own
  wrong code. `Mat4ScaleTest`'s expected values were captured by running the
  **real** `android.opengl.Matrix.scaleM` on a device.
- **Frame pacing: do not add a frame-rate gate.** The surface is
  `RENDERMODE_WHEN_DIRTY` and only the gyro requests a frame (~50 Hz), so a cap
  saves nothing — and returning early from `onDrawFrame` does not cancel the
  present, so `eglSwapBuffers` re-presents the previous buffer mid-scanout and
  **tears the screen**. The draw rate is already bounded by the sensor. Pace at
  the request site if you ever need to.
- **A doze stops the frames while the phone is still, and it is the draw-path
  battery win — the gyro stays registered so waking is instant.** A frame is ~40 ms
  of GPU at 150k splats and the rotation vector asks for ~50/s, so while the
  wallpaper is visible with Motion > 0 the GPU ran at 100 % continuously. The cost
  is `gpuMs × frames drawn`, and the only safe place to cut it is the request site
  (the frame-pacing rule above: returning early from `onDrawFrame` tears the
  screen). So `TiltController` gates its `listener.onTiltChanged()` — the one call
  both hosts render from — and stops publishing while the tilt has not moved. The
  rotation vector is deliberately NOT unregistered (that is the separate 10 s idle
  sleep, which takes over for the long-still case and recalibrates on wake); the
  gyro is a few hundred µA and the GPU is order 1 W, so keeping the sensor and
  parking the frames is the whole win, and a wake needs no recalibration and no
  glide.
  - **The threshold is DERIVED, not picked**, and lives in `:core` where it can be
    tested without a device. `CameraModel.tiltPixels` answers the largest visible
    screen displacement a tilt delta produces at a framing (through the real
    `frame`/`mvp` chain, over the surface's depth range, counting only points on
    screen), and `CameraModel.DOZE_EPS` is the delta whose worst case is
    `DOZE_SUBPIXEL_PX` (0.5 px) — 6.0e-4 tilt, measured at 766 px/tilt on a
    1440x3120 surface at Zoom 200 %, Depth 100 %, the pivot at its extreme. The test
    `theDozeThresholdStaysUnderASubPixelAtEveryFraming` is the guard: an edit to
    `TILT_RANGE`, `FOV_DEG` or `CROP_MARGIN` that made the picture move more per
    degree of tilt goes red rather than letting the doze become visible.
  - **The gate watches the FILTERED tilt, not the raw reading, and that is the
    difference between a doze that engages and one that chatters.** The quantity is
    the smoothed tilt's movement **since the last movement publish** — not the raw
    reading's deviation from its ~2 s baseline. The raw signal carries the phone's
    own sensor and desk noise, which the renderer's `SMOOTHING_TAU` filter removes
    before anything is drawn, so the gate's raw movement straddles its own threshold
    while the drawn picture does not move: measured on the 6T, `move` ran
    **6e-5 … 1.6e-3** around `dozeThresh = 6.0e-4` and the wallpaper published at
    **~50/s with `asleep=false`** — the full ~0.86 A, 3.5-4.0 W of a continuously
    drawn wallpaper, for a picture that was not moving. The same desk, same protocol,
    after this change: **~2 publishes/s, `dozing=true`, with `move` still running up
    to 1.9e-3** — the raw quantity above the threshold while the filtered one
    (`fMove`, **1e-5 … 5.7e-4**) is below it. Referencing the last publish rather than
    the sample before it is also what bounds the frozen frame: the drift accumulated
    while dozing is exactly the quantity the wake fires on, so the first frame after a
    wake steps by less than `DOZE_EPS` (plus the renderer's own intentional `tau`
    lag, which is there whatever the doze does) and the renderer eases the rest. The
    two quantities are printed side by side on the `tilt:` line (`move` raw, `fMove`
    filtered) precisely because a log that prints only the outcome cannot say which
    one drove a decision.
  - **Inside the dwell with less than the bound travelled, NOTHING is drawn, and
    that is the point of the reference.** The gate's third branch used to publish
    every sample — "keep drawing while the movement settles" — which made sense while
    its quantity was the raw deviation from the baseline and could bound nothing. Once
    the quantity is "movement since the last frame", a publish inside the dwell is by
    definition a frame the gate has proven cannot be visibly different from the one on
    screen. It was also the bulk of the frames that remained: a crossing costs ONE
    frame, and the dwell's settling published twelve more per crossing. Measured, same
    desk and protocol: dropping it took the same window from **~14 publishes/s to
    ~2/s**, with `dozing=true` and no publishes at all for four to six consecutive
    seconds in most windows. The honest cost is stated rather than hidden: while the
    phone
    moves only a little (below `bound × 50`, i.e. a very slow deliberate tilt) the
    picture is sampled at the crossing rate rather than the sensor's, with every step
    under the bound — `DOZE_SUBPIXEL_PX` is what makes that acceptable, and the user's
    eye on a slow tilt is the acceptance test.
  - **The gate's filter is the renderer's, and it is one function, not a mirror
    kept in step by hand.** `TiltFilter` (in `:core`) holds the tau, the fast attack
    and the step; `ReliefRenderer` steps it once per drawn frame and `TiltController`
    once per sensor sample, fed the same presented tilt — so the gate measures the
    value that is about to be drawn. The gate needs its own state (the renderer's
    `smoothX`/`smoothY` advance only while it draws, so a gate reading them would
    freeze the moment it dozed and never wake), and the two are consistent because
    `tau` is a TIME CONSTANT: the same elapsed time is the same step at either
    cadence, which `TiltFilterTest` asserts rather than assumes. The fast attack is
    deliberately part of the mirror: a gate with only the exponential would lag the
    target exactly while a deliberate movement is under way — the one moment the
    bound matters — and so would allow a wake bigger than the bound. And the mirror
    is enforced, not merely documented: `scripts/check-parity.mjs` fails if either
    caller derives a step of its own (`Math.exp`) or declares a copy of the
    constants, because a second copy would keep working around the wrong threshold
    with nothing to say so.
  - **The doze is not `IDLE_EPS`.** That answers a different question (ten seconds
    of stillness) and is ~1°, a few pixels; it is the raw reading's deviation from
    its baseline that feeds it, deliberately, because that stage answers "has the
    phone been moved" and the filtered signal would hide a slow deliberate movement
    from it. Folding the two together would either make the idle sleep unreachable or
    make the doze visible.
  - **A running re-centre glide publishes every sample**, because it is a
    deliberate visible movement and dozing it would freeze the glide part-way. The
    wake threshold is at least the doze threshold (equal here, so the step the wake
    takes is the smallest justifiable one) and a dwell (`DOZE_DWELL_MS`) before
    dozing is the hysteresis — a filtered movement sitting on the threshold cannot
    chatter, and a brief pause between movements does not doze. Since the reference
    is what bounds the frames, the dwell now only delays the `dozing` flag; it no
    longer costs a single frame of its own.
  - **A doze that works produces no `splat:` STATS line**, because that line only
    prints while a splat draws — the instrument disappears exactly when the change
    takes effect. `TiltController` therefore logs one `tilt:` line a second from the
    sensor callback: `samples/s`, `publish/s` (the frames the hosts are asked for),
    the current `move` and `fMove` against `dozeThresh`, and whether the doze or the
    idle sleep is engaged.
- **`GLUtils.texImage2D` puts bitmap row 0 at `v=0` (bottom).** The colour
  bitmap is flipped vertically so image-top = `v=1`, matching top-first depth
  sampling. Same for the depth.
- **The depth test must be enabled and the depth buffer cleared**, or torn relief
  pieces paint in submission order and far cells cover near ones.
- **GLES2 has no `glPointSize`** — set it in the vertex shader.
- **Embedded depth** lives in a custom PNG ancillary chunk, keys `dEPt`/`dePH`,
  case-insensitive. The payload is either a whole PNG or raw `w*h` 8-bit grey,
  with dimensions from IHDR. `DepthPng.java` / `src/png-depth.js`.
- **Store image/depth as filenames, not absolute paths.** With
  `allowBackup="true"` an absolute path survives a restore but points at the old
  device's `filesDir`, and the user silently loses their image. `PrefFiles`
  handles both forms; see its test for the restore case.
- **Gyro:** `TYPE_ROTATION_VECTOR`; re-calibrate on every visibility change
  (average ~8 samples, never a cached reading); time-based exponential smoothing
  (`tau = 0.2s`, `TiltFilter.SMOOTHING_TAU`, so the doze gate and the draw step one
  filter) so the feel is identical at 60/90/120 Hz. After 10 s of stillness
  it unregisters the rotation-vector sensor and falls back to a low-power
  accelerometer until a shake — that idle-sleep is the **sensor-side** win; the
  draw-path win is the doze above, which parks the frames without giving up the
  sensor.
- **Sensor power, and what is deliberately *not* done to it.** Three rules, each
  of which cost something to learn:
  - **The idle accelerometer is the only thing still polling once the phone is put
    down**, so it is the only safe target. It runs at 2 Hz with a 1 s batching
    window (`ACCEL_IDLE_PERIOD_US` / `ACCEL_IDLE_MAX_LATENCY_US`). Do not raise the
    window to save more: the window is also the worst-case latency before a shake
    is noticed, and a wallpaper that takes a second to wake feels broken. The
    saving is real but modest — the accelerometer is the cheapest sensor there is,
    and the rotation vector is the expensive one, which is why the idle-sleep
    above matters more than any of this.
  - **Never batch the rotation vector.** Batching is invisible on the idle path and
    ruinous on the live one: a batched delivery arrives late and unevenly, which is
    precisely the jitter the smoothing exists to hide. One code path, one policy:
    `registerRotationVector()` picks the period, and batching is a decision made
    only by the idle branch.
  - **Never slow the live sensor to save power**, and never add a frame-rate cap.
    The parallax feel is the entire product; the draw rate is already bounded by
    the sensor, and a cap tears the screen (see the frame-pacing invariant). The one
    deliberate exception is OS battery-save, which cuts the rotation-vector rate
    from 50 Hz to 20 Hz — reduced smoothness, still responsive, and only when the
    user has already told us they want less.
- **A state field that nothing reads is a bug, not a placeholder.**
  `ReliefRenderer.powerSave` was written by three call sites that all queried
  `PowerSave.isOn()` and read by none, so the app detected battery saver and did
  nothing — at the one moment the answer is worth having. The field is now gone
  rather than kept "for a future resolution-scale change": the renderer has no
  frame rate to give up, so it could never act, and speculative state is the same
  bug one commit later. The response lives in `TiltController.setPowerSave`. Note
  the audit gap this exposed: a dead-code sweep that counts *occurrences* misses a
  field that is assigned and never read, which is a different and quieter failure.
- **A sensor that cannot change a pixel should not be running.** At Motion 0 the
  renderer draws the same frame however the phone is held, so
  `SensorPolicy.shouldRunSensor()` includes `motionActive`. Both hosts feed it from
  the value they have already resolved — `Framing.motion` in the service, the
  active item's settings in the studio — rather than asking the renderer, because
  that value is on the calling thread and a getter would have cost a hop to the GL
  thread to learn something already in hand. Default is `true`, not false, so a
  caller that never mentions Motion still gets the parallax rather than silently
  losing it. **Because Motion belongs to the active photo, this is now a
  per-photo decision:** switching from a photo at Motion 60 to one at Motion 0
  parks the sensor, which is the whole point of moving it off the config.
  `refreshSliders` reads the settings once and uses that one value for both the
  slider and the sensor, because two reads are two chances to disagree — which is
  how a slider ends up showing 0 while the sensor is still told to run.
- **A gesture owns the renderer's values for its span; the index is told once, at
  the end.** Measured on a Pixel 7 Pro, sdk 37, and the reason is arithmetic rather
  than taste: `AppState.load` costs **113 ms** and `LibraryStore.editItem` a
  **synchronous `commit()`** of the whole index for **33–430 ms** depending on what
  the write queue is doing. Three rules, all of them about *which* thread and
  *when*.
  - **A gesture writes the index once, on release.** Pan must not persist per
    `ACTION_MOVE`, and zoom's per-move persistence was a duplicate of what the Zoom
    row's own `OnChanged` already commits. The sheet's established rule — preview
    while dragging, commit on release — is not a nicety here; it is the difference
    between a drag at 60 Hz and one at 2 Hz. `ReliefRenderer.saveFramingToPrefs` is
    the single write, and it takes origin, pan **and** zoom in one read-modify-write:
    three write paths for one set of render values is three chances for two of them
    to be wrong.
  - **The prefs write belongs on the UI thread; only the maths needs the GL one.**
    `ReliefRenderer.screenToOrbit` reads the matrices the last drawn frame built, so
    it stays inside `queueEvent`. The save that followed it used to run there too,
    which held the GL thread for **153–213 ms** after every tap — the frame the tap
    asked for could not be drawn until the disk write finished. `MainActivity.tapToOrbit`
    is the shape: queue the unprojection, `runOnUiThread` the write.
  - **A release that changed nothing writes nothing.** `framingMoved` is set from a
    value *comparison* against the renderer, not from "this branch ran": `setZoom` and
    `panBy` both clamp, so a pinch at its zoom stop and a pan at the surface bound run
    the write path every frame and move nothing. The rule `BasreliefSlider.release`
    already follows for every slider on the sheet.
- **A live render value belongs to the renderer and a stored one to the index, and
  each control has to be told which it is reading.** Three candidates exist for a
  framing axis after a gesture — the renderer's volatiles, `LibraryItem.settings`,
  and the int a `FramingRows` slider displays — and only one of them is *current*:
  the index write lands **388 ms** after the gesture that produced it and on another
  thread, so anything that re-reads storage at gesture end reads the value from
  *before* the gesture, and the next nudge on that row then commits the stale number.
  **So the four framing rows are pushed from the renderer, never re-read**
  (`FramingRows.pushFromRenderer`), and `FramingRows.refresh` — stored values onto
  the rows — stays for the other direction, where the stored value genuinely is the
  truth: a gallery selection or a surface switch, before the renderer has reloaded.
  The same distinction is why the Zoom row is pushed during a pinch
  (`setZoomSlider`) while the Depth, Motion, Brightness, Shadow and light rows are
  not: **only the values a gesture can change between a write and the display need
  this**, and adding a push where nothing is stale is how a second source of truth
  gets created.
- **Pan and orbit move a matrix. Neither may re-tessellate.** `ReliefMesh.build` and
  `ReliefMesh.blurredField` are reached from exactly one place, `ReliefRenderer.upload`,
  which is a *pair* change. A gesture moves `ReliefRenderer`'s `orbitX`/`orbitY` and
  `panX`/`panY`, and `onDrawFrame`'s `captureFraming` copies them into the `Framing`
  the draw uses — no geometry work. If a framing control ever appears to trigger a
  rebuild, the cheap write has become a `reloadFromPrefs` (which is also an
  `AppState.load`, and also 113 ms) and that is the bug to look for.

- **A `Dialog` CAN have a backdrop blur. The gate is the THEME, not the window type —
  and this file recorded the opposite, measured, which is how a capability came to look
  like a platform limitation.** The wrong finding: with a modal open,
  `dumpsys SurfaceFlinger` printed zero `blurRadius` entries device-wide while
  `blurSupported()` was true and `setBackgroundBlurRadius(25dp)` was called, so the
  conclusion was that AOSP composites a background blur only for a translucent,
  *floating* window, that a `Dialog` is `ty=BASE_APPLICATION`, and that the only route was
  `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW`. **Every measurement was real and
  the inference was wrong.** The framework line is `DecorView.updateBackgroundBlurRadius`:

      mBackgroundBlurRadius = mCrossWindowBlurEnabled && mWindow.isTranslucent()
              ? mOriginalBackgroundBlurRadius : 0;

  and `PhoneWindow.isTranslucent()` is nothing but the cached `android:windowIsTranslucent`
  THEME attribute (`generateLayout`: `mIsTranslucent = a.getBoolean(
  R.styleable.Window_windowIsTranslucent, false)`). `ModalSheet` built its dialog from
  `Theme_DeviceDefault_NoActionBar`, an opaque *activity* theme, so `isTranslucent()`
  answered false and the radius we set was forced to 0 before it reached SurfaceFlinger —
  a call that landed and logged `blur=true`, which is exactly why it read as a capability
  failure. `setFormat(PixelFormat.TRANSLUCENT)` sets the SURFACE format and does not answer
  that question; that was the trap. The window is now built from `R.style.ModalWindow`
  (`android/app/src/main/res/values/themes.xml`), which sets `windowIsTranslucent` and a
  transparent `windowBackground` — DecorView layers the blur *under* the window background
  (`LayerDrawable([blur, mOriginalBackgroundDrawable])`), so an opaque one paints over the
  blur it just asked for. AOSP's own "Window blurs" page implements the effect on an
  **activity** with a translucent theme and names dialogs and bottom sheets as intended
  hosts, so the host was never the question. Verified on the Pixel 7 Pro: with the modal
  open the filmstrip behind it composites as soft blobs, not sharp tiles. **And the
  `dumpsys SurfaceFlinger` check cannot confirm this** — sdk 37 prints no per-layer blur
  field at all, so "zero regions" was evidence of nothing; the confirmation is the screen.
  **`windowIsFloating` is deliberately not set**: it only decides whether a blur is clipped
  to the window's bounds or drawn fullscreen, and this window is fullscreen (MATCH_PARENT,
  and the scrim fills it), so fullscreen is what we want.
  **What survives from the wrong hunt:** `setContentView(..., MATCH_PARENT, MATCH_PARENT)`
  overrides `setLayout` on the window, so a "make it float" spike that sized the content did
  not size the window; a spike that renders is not a spike that is *correct* (one left the
  sheet outside `root`, where the morph, the strip and the panel's layout all assume it, and
  looked fine); and a view in one window can only have **painted** glass, which is a mimic,
  not the effect. The studio sheet still cannot blur — it is a sibling view beside the
  `GLSurfaceView` in the same window — so that part was never in question.
- **`setStroke` on a `GradientDrawable` clears the fill.** AOSP writes
  `setColor(TRANSPARENT)` and installs a new stroke paint, so a stroke change after a fill
  silently leaves no fill at all — which reads as a *design choice* rather than as a bug,
  and is why "not translucent" survived three attempts to lower the alpha. Set the colour
  after the stroke, and let one helper own the pairing.
- **An invisible view still occupies space.** Fading the studio pill's grip bar to alpha 0
  left its band at `dp(22)`, so the pill's label was laid out below an empty band and sat
  32px under the capsule's centre — diagnosed by the user, not by any bounds check, because
  every bound was individually correct. When hiding part of a stack, change its **height**.

### Build constraints

- The manifest has **no `package=` attribute** (AGP 8 rejects it). The package
  lives in `app/build.gradle` as `namespace` / `applicationId`, and that is now
  the only place it is declared. Keep the two in sync when renaming.
- `--` is illegal inside an XML comment. Writing `--flag` there fails the build
  with a confusing parse error.
- Java 8 source/target (`VERSION_1_8`).
- The container bakes its own debug keystore. Without it AGP mints a fresh key
  in ephemeral storage every build, every APK gets a different signature, and
  `adb install -r` always fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
  whose only recovery is an uninstall, which wipes the user's settings. (The
  repo's `keystore.properties` points release signing at that same throwaway
  `android/debug.keystore`, which is why a release build installs over a debug
  one with `-r`.)
- **An APK that is *installed* is always `build-release`, and the install is
  followed by the AOT compile — never the debug APK, and never an install on its
  own.** This ROM has `dalvik.vm.usejit=false` and ART refuses to AOT a
  **debuggable** app, so a debug install reports `arm64: [status=verify]` and runs
  interpreter-only. Measured on a Pixel 7 Pro, same 299,008-splat cloud and
  settings, debug/`verify` against release/`speed`: **fps 1.25 vs 50.1, `frameMs`
  802 vs 19.95, `sortMs` 326 vs 5.55 ms** — with the GPU identical in both
  (`gpuMs` 28–85). Every Java `O(N)` loop is 30–100× slower interpreted, and the
  dominant CPU cost here — the depth sort — is memory-bound, which is why it is
  catastrophic rather than merely slow, not the 1.5–2× a release-without-AOT
  comparison shows. And the AOT step is run explicitly rather than assumed: an
  `adb install -r` **does** invalidate whatever odex was there (measured here, a
  non-debuggable APK lands at `arm64: [status=speed] [reason=install]`, i.e. this
  ROM's install-time filter does a full compile) — but the install-time filter is
  a ROM policy, not a guarantee, it can be a lighter filter or a deferred
  background dexopt, and a debuggable app gets none at all. So `podman-build.sh install`
  runs `cmd package compile -m speed -f` for `com.amoshydra.basrelief`
  anyway and reports the resulting dexopt status; the debug APK is the explicit
  `install-debug`, for behaviour only. Finally, an install does not swap code
  under a running process — reopen the studio or re-select the wallpaper, and use
  `am kill`, never `am force-stop`, which reverts the live wallpaper.

### Termux notes (host-side, not a build path)

`/tmp` is often not writable — use `$(mktemp -d)`. `#!/usr/bin/env` shebangs
fail (no `/usr/bin/env` on Android), which is why the npm scripts invoke vite via
`node node_modules/vite/bin/vite.js`. Shared storage is
`~/storage/shared/Download/`; copy APKs there and install from the file manager,
since launching the installer programmatically is unreliable on some devices.
There is no longer a build script that runs here — everything builds through
`podman-build.sh`.

## Verification

`./podman-build.sh test` is the gate — pure JVM, ~15 s, no device, and every
`assemble`/`bundle` depends on it, so **there is no path to an APK that skips
it**. 747 tests across forty-one classes:

**But `test` only runs `:core:test` — it does not compile `:app`, and it will
report `BUILD SUCCESSFUL` while the app does not build.** It is the gate for
`:core` work and nothing more. Any change to `:app` must be verified with
`./podman-build.sh build-apk`, which runs `test` first and then compiles the app.
This has already cost one session: a broken `MainActivity` call passed `test`
clean, and the failure surfaced as an unrelated agent reporting it could not
build. **"The gate is green" is only true of the JVM tests until `build-apk`
has run.**

And **neither gate can see a GLSL error**: shaders live in Java strings, compile at
runtime on the GL thread, and a failed compile throws there. `node
scripts/check-shaders.mjs` closes that hole — it extracts every shader the app keeps
in its sources (both the GLES2 and GLES3 variants, `#version` or not) and runs it
through `glslangValidator`, and CI installs the tool and runs it in the `guards`
job. It exists because **`sample` is a reserved word in GLSL ES 3.00** and using it
as a variable name put the studio and the live wallpaper into a crash-loop on a real
phone: the engine crashed on every restart until Android gave up and reverted the
wallpaper to the system's static image — which only the wallpaper picker can
restore. One shader checker would have caught it before the install; the file's own
rule is that anything that can invalidate a device test has to be checkable without
one.

The same hole exists one language over, for **`JSONObject.put(String, float)`**.
`:core` compiles against Maven's org.json (`compileOnly 'org.json:json:20231013'`),
which HAS that overload; Android's platform org.json does NOT. A bare float
therefore links, passes `:core:test` — which runs on the JVM, against the very
org.json that has the method — and passes `build-apk`, which compiles, and then
throws `NoSuchMethodError` at runtime. It shipped: `LibraryItem.toJson` wrote
`focal` and `splat_lens` as floats, the throw was on the `index-writer` thread, and
every library write took the app down, so any gallery tap crashed it.
`scripts/check-json-puts.mjs` closes it, and the rule it enforces is one cast:
**every float a `toJson` stores goes through `(double)`**, which resolves to
`put(String, double)` on both platforms. The APK's own DEX is the proof — `dexdump
-d` over the built dex lists `put(String, Object/int/double/boolean/long)` and no
`(String, float)` at all, so the check is of the artifact and not of the source.

- `ReliefMeshGoldenTest` — exact vertex positions and normals for a fixed depth
  map. Mutation-checked: re-quantising the blur, halving its radius, flipping
  the v convention and dropping a face normal each fail it. It also pins
  `heightAt`, the field sampling the pivot is resolved from: row 0 is the
  image's top (plane y = +0.5), the value becomes `v/255 - 0.5`, bilinear
  between samples, clamped at the edge, and 0 without a field.
- `SplatTest` — the splat mode's two numbers, each with the one wrong answer that
  a screenshot cannot tell from the right one. The fallback focal is asserted by
  INVERTING it: the implied field of view is 50 degrees for every image height, and
  scaling the height scales the focal, because a constant in pixels would make the
  same lens wider on a taller image. The depth scale the shader forms
  (`effectiveFocalPx / fallbackFocalPx`) is pinned at 1 for an unknown focal,
  linear in the focal, and invariant when the focal and the height scale together.
  A height that cannot imply a reference answers 0, and the shader treats that as
  the default look rather than dividing by it — the "a window claims nothing" rule
  applied to a number. The card size is held to a RANGE at every grid rather than a
  value: at least the cell it replaces, or the reference plane is a screen door,
  and under two cells, or the picture smears into itself. Its fourth is the
  **level ladder**: every level must be a real index, the grids must not go
  backwards, and anything that is not a level — a negative, an index past the end,
  a value a newer build wrote — answers the default, because that default is the
  look the mode shipped with and an upgrade must not move it. Its fifth is the
  **floor's unit**, which is now the whole of it: `eps2d` is a fraction of the
  cell a splat stands for (`viewportArea / count`, magnified by the zoom), not
  a number of pixels, because the gap it closes is the cell and a pixel is a unit
  the cell does not use: the same setting is a different effect at every resolution
  and every zoom, and the knob's maximum was below what the phone needed. Measured
  on one cloud's smooth sky (high-frequency energy against the photograph's own):
  6 px reached 0.67x at the cloud's 912x1984 frame while the 1440x3120 the phone
  draws it into needed 15 px — and 8, the old ceiling, still left 2.1x. Both are
  0.5 of the cell, which is what makes one default correct for both. So the test
  asserts the *invariance* and not either pixel value: the floor over the cell is
  the same number at both resolutions, the phone's is the cloud's scaled by the
  square of the resolution ratio, and the zoom enters squared because it magnifies
  both axes. **The bottom of the range is the identity, and a test walks it**:
  `theBottomOfTheRangeIsNoFloor` pins `EPS_2D_MIN == 0`, that `eps2dFor(0, …)` is 0
  at a sparse AND a dense count and both ends of the zoom clamp — so the picture is
  purely the cloud's density there — and that the default 0.5 still lifts, so the two
  ends of the range are two looks rather than a value and its guard. There is no
  second, target-area term, and **the test that pins the
  property the deleted minimum made false** is
  `theSizeKnobMovesTheFloorAboveTheOldCrossover`: above the count where that
  constant's ratio exceeded 1 (301,568 splats) the floor used to be the cell, with
  `0.8 × cell < cell`, so the knob could not move it at all — and the test walks
  403,456 and 1,046,528 asserting the floor is exactly `fraction × cell`, below the
  cell, and moves with the knob. The rest keep what still means something: the floor
  is **monotone in count and in zoom** with no step anywhere in the count (the old
  crossover included), and a cell that cannot be claimed (no splats, no viewport, a
  NaN fraction) answers 0, which is the identity for a floor — the "a window claims
  nothing" rule applied to a number, where a guess would be a floor derived from a
  count of nothing. Its
  sixth is a cloud's **identity**, which is the path AND the bytes behind it and not
  the path alone: a rebuild writes the *same* filename, so a path-only identity
  answers "already showing it", the request is dropped, and the new cloud never
  reaches the screen — reported as a rebuild that sometimes appears to do nothing
  while the file on disk had gone from 4.8 MB to 23 MB. The byte count is a
  sufficient stamp because the node is deterministic (its own tests pin
  byte-identical output for the same budget and inputs), and a count that cannot be
  read is 0, so an absent file cannot collide with a loaded one. Its seventh is the
  **shape knob** the sheet's splat group gained after the look knobs. The DEPTH
  multiplier is pinned as the identity at 1 for every depth and as the flat
  photograph at 0 (every splat on the reference plane), and above all by the
  invariant that makes both true at once: the scale it applies leaves each splat on
  the ray from the eye through its own image pixel, so the at-rest picture is the
  photograph at every value and only the parallax moves. A NaN is the default, not a
  bound, and a depth or plane that claims nothing is the identity.
- `SplatProjectionTest` / `SplatCloudTest` — the real Gaussian splat's reader and
  projector, the two things a shader cannot be checked for off-device. The
  container test pins the 32-byte record field for field, including that the
  colour and quaternion bytes ARE the little-endian words at offsets 24 and 28
  (the record IS the two `RGBA32UI` texels the renderer uploads — **integer
  texels, and that is load-bearing**: four uint8 packed into a float32 is a NaN
  for every bright colour, alpha and blue both at or above 128, and a NaN's
  payload does not survive `floatBitsToUint` on Mali — it answers a canonical
  NaN, so those splats drew as flat white. Measured: the sky went grey and
  speckled, sat 0.14 against the faithful render's 3.72, while the tan sand
  beside it — blue below 128, not a NaN — stayed pixel-perfect. The container
  test pins that condition too, so the reason the texture cannot be a float one
  is asserted rather than remembered), and that a partial trailing record is
  dropped rather than read past. It also carries the near-plane pull-back's reach,
  `maxSizeOverDepth` — the cloud's largest 3D axis over the depth of the splat that
  carries it — because the vertex shader's clamp is
  `depth < minViewDistancePerSize(proj5, …) * size` and everything in it but the
  projection is a property of the cloud, so the device log's `pullbackAspect=` can
  say whether the clamp engages without a pass over the GPU's data. A splat at or
  behind the camera cannot claim an aspect, so an empty or wholly-behind cloud
  reports no pull-back rather than a division's answer.
  The projection test pins the chain the plan states — `Sigma3D = R S S^T R^T`,
  `SigmaV = V Sigma3D V^T`, the perspective Jacobian, `Sigma2D = J SigmaV J^T`,
  the conic, the eigen extents — and above all the relation that makes the quad's
  edge the Gaussian's edge: at the 3-sigma extent the exponent is exactly `-4.5`
  on BOTH principal axes, whatever the covariance, and `exp` of it is
  `exp(-4.5)`. A chain that put the extent at two sigma, or dropped the half,
  would still draw a plausible ellipse; this is what a screenshot cannot tell.
  Its second trap is the degenerate covariance: zero, rank-one and zero-area 2x2s
  are refused (both `conic` and `extents` return false and zero their output)
  rather than inverted to infinities that poison every vertex reading the conic.
  The spherical-splat invariance is the transpose check — an isotropic
  covariance cannot change under a view rotation, so a transposed `V` passes on
  an elongated splat and fails here.
- `SplatCameraTest` — the splat cloud's camera, which used to exist only inside
  `SplatRenderer` and so could only be judged by eye on a device. It pins the
  same at-rest invariant `CameraModelTest` pins for the relief, one medium over:
  **at rest the picture is exactly the photograph, translated by Pan alone — Origin
  names the orbit centre and does not move the picture — at every origin and every
  pivot depth** — asserted as a projection through the
  real `Mat4` chain, never a restatement of the maths. The splat's version of
  "the eye does not move with the pivot" is that two clouds resolving very
  different pivots project every probe point identically; the eye is the source
  eye in both. Its second theme is the turntable: the tilt is a ROTATION about
  the cloud's own surface under the origin, so the pivot keeps its OWN screen
  position (its own image position, not the centre) while a point off it swings, the
  eye keeps its distance to the pivot, and the
  signs are the relief's (positive tiltX moves the eye right, positive tiltY
  down in the cloud's y-down frame — a flipped axis is the parallax running the
  wrong way, which no screenshot at rest shows). Its third is the pivot pick:
  the FRONTMOST splat under the ray, not the nearest in image space (a background
  splat can project to the same pixel as the subject), a real splat used when the
  ray's radius holds none, and the reference-plane point on the ray only when the
  cloud has nothing in front of the camera — the `DepthSource` "a window claims
  nothing" rule applied to an orbit centre. Its fourth is the **Depth knob's reach
  into the pivot**: the pick is made on the surface the SHADER draws — every splat
  moved along the ray from the eye by `Splat.depthScale` — so the pivot and the
  crosshair follow the knob instead of marking a point the cloud has left,
  and a splat the knob pushes through the eye (a moved depth of 0) is skipped
  exactly as the vertex shader's near cull drops it. The companion assertion is
  that this moves the pivot and NOT the picture: at rest the eye is the source eye
  whatever the pivot is, so the same probe points project identically at every
  multiplier. Zoom is a crop of the same camera and
  Pan is a screen-pixel shift at every depth, both pinned. Its fifth is the **tap**:
  a tap resolves the origin by RAY-CASTING into the cloud — the image position of
  the frontmost splat under the ray through the tapped pixel — through the same
  `SplatCamera.originForRay`/`pivot` pick the orbit uses, so the tap, the pivot, the
  aim and the crosshair name one point. It pins that the pick is the frontmost splat
  on the ray and not the nearest in image space, that a ray holding no splat falls
  back the way the pivot does (the nearest splat, then the reference-plane point on
  the ray), that a splat the Depth knob pushes through the eye is skipped, that the
  answer does not depend on the Depth knob (the image position is `x/z`, which
  `Splat.depthScale` preserves), and above all that **tapping and then resolving is
  a fixed point**: the origin a tap produces is the image position of the splat the
  pivot picks for that origin. That is the property the old tap — the tapped SCREEN
  fraction, which drifted by the drawn Pan shift — broke. Its sixth is the
  **sort gate**: `SplatCamera.pxPerTilt` is a max over EVERY splat, and the gate is
  the larger of `SHIPPED_SORT_EPS` and half a pixel over `pxPerTilt` — the derived
  bound may only ever
  LOOSEN the gate, never tighten it. That floor is the whole design, not a safety
  belt: at the device's own Motion the sub-pixel gate is *tighter* than the `1e-3`
  it would replace, and tightening would sort more with no defect to fix, so the
  constant is the floor and the derived term is the lever. The tests are the
  no-regression guarantee (the gate is ≥ `1e-3` at every reachable framing,
  *including* the Motion sweep — the unfloored first attempt produced 8.9e-4 at
  Motion 3), that the lever is demonstrably live at the default Motion, that the
  DERIVED bound is sub-pixel at every framing asserted against `tiltPixels` through
  the real `frame` chain (the "assert the projection" rule), that the gate is live
  (it shrinks as the field of view narrows), and that a framing which cannot claim a
  bound falls back to the CONSTANT rather than to zero. **And its framing key
  includes Pan, which was checked and is a real trigger, not a conservative
  inclusion:** `pxPerTilt` counts only the splats VISIBLE under the Pan
  shift, so panning moves that window and moves the max — measured on
  `SplatCameraTest`'s cloud at the device's framing, the bound moves ~25% between pan
  (0, 0) and (400, -400) with the pivot IDENTICAL — so it stays in `SplatRenderer`'s key and
  `SplatCameraTest`'s `panIsPartOfTheBoundsFramingKey` keeps a "pan is a no-op"
  simplification from quietly serving a stale bound. (The shift is pan's alone now that
  Origin no longer moves the picture; the old figure was larger because the origin was
  in the shift too.) **Motion is the boundary the
  measurement forgot:** `SurfaceSettings.motionScale` is `motion` divided by 100 and
  multiplied by 3, so Motion
  33 (the default) is scale 1.0 and Motion 100 is 3.0, and
  `docs/SORT-MEASUREMENTS.md`'s `183 px/tilt` is at scale 1 — which is why the
  device's live bound read as three times wrong. The derived gate beats the constant
  only below a Motion that depends on the framing (2.66 → Motion ~89 % at the
  device's, ~1.03 → Motion ~34 % at the worst reachable). Its seventh is the
  **near-plane pull-back**, and it is the class's second geometry claim rather than a
  number: `thePullBackKeepsASplatOnItsPixelAndShrinksIt` projects a splat through the
  real chain before and after the pull-back and holds the screen position to `1e-5`
  while both 3σ extents fall by exactly the scale — the property that makes the clamp
  a splat move and not a camera move. `thePullBackKeepsTheSplatUnderTheRadiusClip`
  sweeps every framing and screen corner (including the frame corner, where the
  off-axis Jacobian term is largest) and asserts the clamped splat stays under the
  radius clip's half-frame threshold — **it fails at `MAX_EXTENT_FRACTION = 0.5`
  (mutation-checked)**, which is what makes 0.1 the number rather than a taste.
  `aCloudWithNothingNearTheCameraIsUntouched` pins the measured 0.0133 real-cloud
  aspect against the threshold at three framings, and
  `theMinimumViewDistanceIsThePinholeRelationAndClaimsNothingElse` pins the mapping
  (linear in size, inverse in the fraction, scale-invariant, and 0 for anything that
  cannot claim one).
- `FramingTest`'s `theBundledDemoIsNotADifferentPictureFromItsOwnNumbers` — the two audit
  findings that made the instant-paint guard absent where it was needed most.
  **`sameAs` compared `bundled` while `ofValues` — which builds every snapshot — hard-codes
  it `false`**, so a resolve of the marble bust could never no-op: the guard was
  structurally missing on the one row every install starts with. `bundled` says WHICH ROW a
  framing came from, not what the picture is. And the guard itself asked only
  `snapshot().sameAs(f)`, which is **false mid-glide** — the volatile values match neither
  endpoint — so it protected a frame that had ARRIVED and not one in flight, which is the
  case it exists for. It now asks `isGlidingToward`, so a resolve is a no-op when the frame
  is on screen **or on its way there**. The wallpaper had compensated by calling that
  itself; every other caller, `MainActivity.applyFiles` among them, snapped.
- `Mat4ScaleTest` — the `scaleM` semantics, captured from the real Android API.
- `FramingTest`'s `sameAs` — whether two framings render the same picture, which is how
  the wallpaper decides whether a repeat resolve of one transition has anything to say. The
  failure it prevents is a **cancelled glide**: a transition resolves more than once
  (becoming visible, an insets dispatch, a pref write), each resolve builds a NEW `Framing`
  with the same values, and without a value comparison the renderer cannot tell "a different
  picture" from "the picture I am gliding towards" — so the second resolve painted the
  destination instantly, 1.2s into a glide, which the user saw as a snap. Every one of the
  **eighteen** fields is compared, and the field count is asserted, because a field added
  without teaching `sameAs` about it would make a real frame silently a no-op. The reflection
  that drives it **excludes static fields**, which it had to start doing when the twelfth
  arrived: `SOURCE_STILL`/`SOURCE_VIDEO` are `public static final float`, so counting every
  float found more than the instance fields and the count could then be "updated" to match
  whatever the code happened to declare — which is a guard that can no longer fail. That
  twelfth field is
  `sourceKind`, and it is **compared where `bundled` is not**, which is the whole reason to
  read the two together: `bundled` says which *row* produced a framing, so two framings of
  the same numbers are the same picture, while the medium says what the *pixels* are, so a
  still and a clip at identical depth, zoom and light are two pictures. It is a float rather
  than a flag for the same reason `SurfaceSettings.with` names every value — a field
  `ofValues` cannot reproduce is a field the no-op-paint guard cannot see. The fourteenth is
  `renderMode` (relief or splat cloud), compared for the same reason the medium is: a relief
  and a cloud at identical numbers are two pictures, so a toggle must paint rather than
  answer "already showing". It is read from the item, so the two surfaces and the renderer's
  own snapshot cannot disagree about it. The last four are the splat look — Depth, Size,
  Blur and Resolution — compared because a cloud at a different look is a different picture;
  they were global prefs and are per photo and per surface now, so a snapshot must be able
  to reproduce them or every resolve of a tuned cloud repaints.
  - **`aPreviewRowNamesTheMediumOfItsPixels` — the preview's medium is video, and the
    ROW cannot say so.** An import's preview row is deliberately a photograph (nothing
    half a clip is ever stored), while the pixels on screen are the colour clip's — so
    the hosts pass `Framing.of(item, surface, previewing)` and this pins that it
    answers `SOURCE_VIDEO`, that the flag only ADDS video (tracks are video with or
    without it, and a plain photograph stays still), and therefore that the host
    framing cannot differ from the renderer's own snapshot on every preview resolve.
    Without it the no-op-paint guard never fires while a preview plays and a glide
    landing over one stamps STILL onto a moving clip.
  - **The medium is SWITCHED, never eased — and `isVideo()` matches it EXACTLY.**
    `ReliefRenderer.snapshot()` is the twelve-argument `ofValues` and was the last
    caller of the deprecated eleven-argument form. Through that one a snapshot of a
    playing clip came back saying "still", compared unequal to the frame on screen,
    and `apply(RESOLVE)` repainted on every resolve — **the instant-paint snap, live
    the moment a clip existed rather than as a visible bug.** A glide eases its eleven
    values and **holds `sourceKind` whole**, switching it in the `t >= 1f` branch:
    because `sameAs` compares it, easing would make *every frame of the glide differ
    from the one before*, defeating the no-op-paint guard for the whole duration.
    `isVideo()` was `!= SOURCE_STILL` and is now `== SOURCE_VIDEO`, because a test
    caught the consequence: `lerp(STILL, VIDEO, 0.5)` answered **true** there, so an
    interpolated medium did not merely fail to name a picture, it **claimed to be a
    clip.** Nothing in the shipping path eases it, which is exactly why that would
    have survived review — and `== SOURCE_VIDEO` is also what survives a third medium,
    since `!= STILL` would call a future `SOURCE_DEPTH_ONLY` a video.
  - **An import that a restart interrupts leaves a photograph AND a record, and the
    run's own state is a FILE the server keeps rewriting.** Two halves, and both are
    load-bearing. The poster is written from the LOCAL file before anything reaches
    the server, at the RANGE's first frame so the picture does not change when the
    import lands -- so a process death (an install, an OOM kill, a swipe away) leaves
    a row the user can see and retry, where measured it left an orphaned clip file
    and no row at all. And the job is a record on disk (`Jobs` + `JobsStore`, key
    `jobs`) holding the item, its ONE PROMPT and the HOST that issued it, written the
    moment the server accepts the prompt, cleared when the outputs are fetched, and
    **kept as FAILED when the run stops** -- the sheet's job line and the tile's
    corner carry the server's own words until Regenerate or a delete clears them,
    because a failure that exists only in a toast is gone before it is read.
    `JobsStore` commits rather than applies, deliberately. The record is keyed by
    ITEM, so a second submission supersedes the first instead of leaving two fetches
    racing for the same filenames; clearing and failing are keyed by PROMPT, so a
    superseded run finishing cannot clear the record of the submission that replaced
    it. The schema is **v4**, one prompt again: the streaming node takes the whole
    range in one prompt and owns the loop, and **the count is deliberately NOT in the
    record** -- a running run's progress is the sidecar's `done`/`total`, read live
    into `VideoImport.Live`, and a persisted copy would be a second, staler answer.
    A v1 blob (one prompt) and a v2/v3 blob (a chunk list) both MIGRATE by keeping
    their FIRST prompt, because any of them is a legitimate in-flight run and
    dropping it would strand a finished result -- the exact loss this class exists to
    end. **The node's sidecar (`basrelief_<tag>.progress.json`, fetchable through
    /view) is a **FIXED filename that any run leaves behind**, so it may not answer for a run
    it does not belong to -- and it did: the poll asked it FIRST and returned on its
    first iteration because the PREVIOUS run's sidecar was still there saying `done`,
    so every build completed instantly with the cloud from the build before it
    (measured: a 703k build producing an 806k cloud, and a rebuild that looked
    instantaneous because nothing ever waited; its `failed` was the worse half, since
    a previous run's failure would have aborted a healthy build with the last one's
    words). **The run is identified by the PROMPT the app created, and that prompt's
    own history entry is the only thing that says the run is over** -- it is keyed by
    exactly the job the app submitted, so it is the one question another run cannot
    answer, and an absent entry is NOT a verdict, because the server registers the
    prompt when it starts it and a run queued behind another has none yet. What
    follows is what the sidecar is still read FOR, as content, once the history has
    spoken, and the resume reads it only for those -- never to decide, because the history has already decided:** photo gone or
    no colour clip → clear; server unreachable → KEEP (a sleeping laptop is not a
    lost import); sidecar `running` → KEEP and seed the line's count from its
    `done`/`total` (the node is doing the work, and re-submitting here would run it
    twice against the same output filenames); sidecar `done` → fetch, attach, clear,
    with the file names the sidecar itself states; sidecar `failed` or a history
    error → record the server's own words as the job's FAILED state and KEEP it (a
    later foreground skips a failed job rather than re-asking a run that has already
    answered, and Regenerate is the action that clears it); the server has no trace
    at all → **re-submit from frame 0**, because a single prompt has no partial clip
    to reuse: the node writes both files whole and renames them at the end, so there
    is nothing to continue. And **`outputs.length() > 0` is not "finished"**: a
    running entry can already carry outputs, so the old in-flight poll hit
    `attach()`, found no depth clip, and reported a working import as failed -- the
    poll and the resume now both require `status.completed` and both ask the one
    `failedReason()` for the server's words. `jobs` is in the studio's
    index-transport key set, and `attach()` is the ONE implementation of an import's
    fetch tail, shared with the resume so the two cannot drift.
  - **The frame count a run is planned from is the UPLOAD itself, and the count the
    node reports is checked against it -- a duration-derived count must never plan a
    run again.** Measured: the app's own remux of a 240-frame 24fps range reports a
    container duration of **9.9586s** (the MP4 muxer takes it from the last sample
    processed, not the highest presentation time), and `round(9.9586 × 24) = 239`.
    The old chunk planner asked the server for 239 frames while the colour held 240,
    so the joined depth clip decoded **239 against 240** -- one frame short, at the
    end, no error anywhere, a hitch at every loop. The stream node is now given
    `frame_count = 0` and runs the whole of the file the app holds, and the app's
    exact sample count (`VideoImport.sampleCountOf`, one sample per frame) is used
    only to CHECK, on the axes the caps make separate: the sidecar's `src_frames`
    must equal that count (the upload is the plan, before anything is downloaded);
    the arrived depth clip's sample count -- and the node colour's, when the run
    named one -- must equal what the node says it wrote (`frames`/`done`, NEVER the
    remux's, which a capped clip is shorter than BY DESIGN); the node colour is
    never compared with the remux's frame count (only the `src_frames` check ties it
    to the upload); and every fetched clip's duration must match the timeline within
    one output frame, plus half a source frame of frame-grid rounding -- depth/normal
    against the remux's source timeline, the node colour against the depth it was
    encoded beside, because the same decoded frames at the same rate are equal by
    construction. Every mismatch is a failure with both numbers in it, and either
    side unknown (0) is logged as unchecked rather than called a mismatch, because a
    window claims nothing.
  - **The playback tracks are sized for the PHONE, not the camera, and the player
    bounds only a stream that is genuinely spent.** Measured on a Pixel 7 Pro: a
    1440x2560 portrait 50 fps source could not be decoded at all -- even the COLOUR
    stream fell 0.4-2.4 s behind -- while 1080p-class content held ~40 fps per
    stream (three simultaneous decoders each ran at ~39-40 fps and the shared clock
    could not pin them: the relief drifted 0.4-1.0 s from the picture, worst on the
    normal, the heaviest encode). Two caps are therefore stated by the app rather
    than left to the node: `ComfyWorkflow.STREAM_MAX_LONG_SIDE = 1920` (the long
    side of the generated tracks AND of the node's colour) and
    `ComfyWorkflow.STREAM_FPS_CAP = 30` (their rate), each passed explicitly, and
    output frame k is source frame `round_half_up(k × src/out)`: fewer samples over
    the same seconds, never a retime. The sidecar carries `src_frames`/`src_fps`/
    `fps_out`, which is what the fetch checks above are computed from. **The colour
    is the node's when the source exceeded the caps and the app's own remux
    otherwise.** The remux (`LibraryItem.videoFileName`) stays the upload, the
    plan's frame count and the Regenerate input -- overwriting it with the
    downscaled colour would cap every later regeneration twice; a node colour lands
    on its own name (`LibraryItem.videoColorFileName`) and `VideoTracks.color`
    names whichever file the run produced, so every existing row stays valid with no
    migration. And for a decoder that STILL falls behind, `ClipDecoder.catchUpIfLate`
    is a last resort, not a recovery mechanism: a released frame more than **1.5 s**
    behind the clock, at most once per **10 s**, seeks forward to the frame due now.
    The first version (300 ms / 1 s) fired into a SEEK STORM on the measured source --
    the seek lands on the PREVIOUS keyframe (`SEEK_TO_CLOSEST_SYNC`) and the
    source's GOPs were seconds long, so every catch-up rewound further than it
    saved and the stream never converged, which the user saw as strobing -- so the
    thresholds are now unreachable by a stream that is merely keeping up, and with
    the caps this should never fire at all. The seek shape is otherwise unchanged:
    the loop-rewind arithmetic, no flush, timestamps absolute at the input, never
    while `awaitingKeyframe` and never at `rate <= 0`. `ClipPlayer.stats` reports
    both deltas (`depthSkew`/`normalSkew`) because the old single skew was blind to
    the normal, the worst offender.
  - **An import patches the strip and the grid; it does not reindex them.** The
    early write inserts ONE tile and ONE cell (`Gallery.itemAppeared`), the
    completion repaints that row (`Gallery.itemUpdated`), and `reindex()` stays
    for what genuinely rebuilds (a sort change, Settings' reset, a selection the
    wallpaper moved). A rebuild throws every tile away and re-decodes every
    thumbnail, and a decode that has to queue lands as an empty tile — the
    filmstrip blanking that `Io.runLong` is the worker half of. Progress is not
    allowed to be a rebuild either: a phase callback repaints the line and the
    corner marks in place (`MainActivity.refreshImportUi`).
  - **The tile's corner is one slot with one derivation, shared by the strip and
    the grid.** `Gallery.cornerState(item, job)` decides between a RING while an
    import is pending (the `Jobs` record, OR `VideoImport.isRunning` for the
    window before a record can exist), `▶` for a row that names clips, and the
    amber warning for a failed run; `CornerMark` draws it. The ring uses the
    same `ProgressRing` the pill does, handed the accent because that is the
    design's tile colour — the pill asks for white. And the ring is stopped
    whenever the state leaves RING and whenever a tile is dropped: a detached
    drawable whose handler tick is still posted invalidates itself for ever,
    which is a leak per tile per rebuild.
  - **The running job's line is derived, never stashed.** While a clip job
    exists it takes the fix line's seat (`MainActivity.showClipJobLine`),
    because the row is a photograph with no depth for the whole server round and
    "No depth · Generate a map" under it would invite a second generation of the
    thing already running. The count is the SIDECAR's `done`/`total` ("Frame 137
    of 480"), carried in `VideoImport.Live` and re-read on every repaint; between
    sidecar reads the live phase's own word (Uploading, Fetching) is the honest
    text, and the remaining time comes from `BatchBudget`'s one estimate through
    the trim sheet's own formatter — so "about 40s" before the run and "about
    4 min" during it cannot disagree. The memory figure left this line with the
    chunk plan, because the streaming node sizes its own window and the sheet no
    longer states one. A failed line shows the SERVER'S OWN WORDS and a
    Regenerate action that clears the record and re-runs; no field holds any of
    it, so a sheet rebuild re-derives exactly the same line.
  - **One prompt runs a long range, because the server node owns the loop.** The
    old video graph (`VHS_LoadVideo → MoGeInference → VHS_VideoCombine`)
    materialised the range as ONE IMAGE batch and held every frame's float tensors
    at once: measured, 480 frames at 1920x1080 climbed to 15.48 GiB and requested
    4.03 GiB more at frame 174, dead under the server's 16 GiB cap. The custom node
    `MoGeVideoStream` takes the video PATH instead, decodes a window at a time,
    infers and encodes the frames straight out, so **peak is the model plus one
    window**: the same 480-frame clip held 11.5-12.3 GiB flat for the whole run and
    finished in 340 s, with frame 0 numerically equal to the app's reference render.
    The graph is therefore TWO nodes (`LoadMoGeModel → MoGeVideoStream`; its source
    lives at `server/comfy-node/basrelief_depth/` in this repo and is deployed), and
    the app has no chunk plan, no per-part join and no codec verification -- those
    helpers (`BatchBudget.chunk*`, `ClipJoin`, `VideoImport.joinClips`) are deleted
    with the shape they served. **The window is the memory knob, and its rule is pure
    arithmetic in `:core`:** `BatchBudget.streamWindow(width, height)` returns the
    largest window in 1..4 whose `pixels × window` product stays at or under the
    measured anchor (`1920×1080 × 4` = the 12.3 GiB peak), so 1080p gets 4, 1440p 2,
    4K 1, and an unknown dimension gets 1 -- the "a window claims nothing" rule
    applied to a number. **The chip is a SHORT-SIDE cap** -- `1080p` means 1080
    across, so a portrait 1440x2560 recording gets 1080x1920 rather than the 608
    pixels wide a height-only reading gives (`BatchBudget.targetHeightFor`; the node
    itself still takes a height, so the conversion happens where the dimensions are
    read). **The colour is the node's when the source exceeded the
    playback caps, and the app's own remux otherwise.** The remux is still the
    upload and the plan, and it is never overwritten by the node's downscaled
    colour -- a Regenerate uploads the remux, or it would cap an already-capped
    copy twice; the node colour lands on its own name
    (`LibraryItem.videoColorFileName`) and `VideoTracks.color` names whichever file
    the run produced. A failed remux copies the share and cuts the range from the
    copy, and a source within the caps is not re-encoded at all.
  - **A clip's SPEED is a render value on the item, and the gate is where it
    applies.** Playback rate is per photo and per surface, on `SurfaceSettings`
    beside `motion`, edited by a row in the "How it moves" group shown only for a
    row with tracks -- and NOT in a pref. Prefs hold the index, device settings, UI
    state and diagnostics, and exactly one render value: `config.motion`, which
    survives only as the migration source for the version that moved Motion onto the
    items. A global speed would reintroduce that one version later. The mechanism is
    the DECODER'S GATE, never dropped frames: the gate compares a frame's clip time
    against the wall clock, so dividing that comparison by the rate plays the clip at
    the rate and nothing is skipped or repeated while the decoder keeps up -- the
    clip's own timeline is untouched, so `loops`, the seam arithmetic and the skew
    line keep their meaning; the catch-up above is the bounded exception for a
    decoder that cannot keep up.
    Both decoders of a clip get the same rate for the same reason they get the same
    lead: two rates are two frame indices by construction.
  - **`Framing` has EIGHTEEN float fields and the count is asserted, so a new one
    cannot be added silently.** `SurfaceSettings.with` takes sixteen arguments and
    `Framing.ofValues` eighteen; every writer and every test call site fails to
    compile when a field is added, which is the only mechanical guard there is. The
    writers carry the new field through rather than resetting it -- the `withDepth`
    scar one field over -- and `FramingTest` drives one varied vector per field so a
    nineteenth cannot be forgotten. `speed` IS compared by `sameAs`, unlike
    `bundled`: a frame at 0.5x is a different picture from the same frame at 1x, and
    the no-op-paint guard has to see the difference or a speed change snaps. The
    last four are the splat look (`splatDepth`/`splatEps2d`/`splatBlur`/`splatScale`),
    compared for the same reason: a cloud at a different Depth, Size, Blur or
    Resolution is a different picture, so a change to one must paint.
  - **Two shader families, two unit systems, and the uniform that goes to
    location -1 is dropped in silence.** The clip's depth shader works in **0..255**
    (it scales the blurred mean by 255 in-shader) and its range offset is
    `-16f * (255f / 219f)`. The colour and normal shaders work in **0..1** (normalized
    samples) and their offset is `-16f / 219f`. Handing the 0..255 offset to the 0..1
    shaders makes every pixel negative and clamps it to black — reported as "normal
    is completely black". And the colour shader originally **never declared
    `uRangeFix`**, so `glUniform2f` went to location -1 and was silently discarded:
    colour looked fine while normal, declaring the same uniform, went black from the
    same value — and colour was displayed unexpanded (washed-out blacks) the whole
    time. **A shader that receives a uniform must declare it, and the units belong in
    a comment beside the value.**
  - **The depth FIELD's row 0 is the image's TOP row, and the clip blit must
    un-flip to match.** The mesh reads the field with `gy = (1 - aUV.y) * grid`
    (`VERT3`), and `ReliefMesh` stores it top-first with `uploadField`'s unflipped
    upload — the still convention, pinned by the golden test's v case. The clip
    blit writes the field through the SurfaceTexture transform, which includes
    Android's bottom-up flip, so sampling straight through `uST` writes the image's
    BOTTOM row at field row 0: the depth vertically mirrored against the video,
    protrusions where the scene recedes. The colour needs no such correction
    (the still path flips colour at upload), which is why the video looked right
    while the relief did not. The fix is `uv.y = 1.0 - uv.y` after the transform,
    **in the depth shader only**.
  - **An R32F texture must be NEAREST, both filters.** It is not filterable without
    the float-linear extension, and a LINEAR float texture is *incomplete* — the
    sampler returns a constant wherever it is read. The still path's `uploadField`
    has said NEAREST since it was written; the clip's `makeField` said LINEAR, so the
    vertex shader read a uniform depth and every clip drew as a flat plane whose
    Depth slider multiplied a constant.
  - **This driver refuses a GLSL ES 1.00 program that mixes `samplerExternalOES`
    with `sampler2D`.** The temporal depth blend put a `sampler2D` over an R32F
    texture beside the OES one, and Mali dropped the DRAW with
    `GL_INVALID_OPERATION` — uniforms and pointers clean, the draw alone refused.
    **The temporal blend is parked for this reason**; if depth flicker needs it back,
    it must be a separate pass whose two samplers are the same type. Probe every GL
    call in a pass before theorising about which one fails.
  - **A debug view must sample what the draw SAMPLES.** The layer view first read
    the render's still fields (`r.textureColor`/`r.textureNormal`) instead of the
    clip-aware helpers, so colour and normal showed the frozen poster while relief
    and depth played; and it must view the depth field through the mesh's own flip
    (`1 - v`), or it shows mirrored while the relief is correct. Both were reported
    as debug-view faults, and both were. Long-press the status line to cycle it.
  - **A GLSL comment must never share a line with the statement after it.** A `//`
    comment written into the fragment shader swallowed the `vec2 uv` declaration on
    the same line: the shader never compiled, the program never linked, every draw
    was refused, and the debug view simply stopped appearing with **nothing logged**.
    This is the third comment-eats-code bug of one session, after `--` inside an XML
    comment and a brace-eating splice — and the cure each time was to measure the
    failing call rather than reason about the pipeline.
  - **The renderer's medium is a FIELD, written where the pair swaps and nowhere
    else.** `clipRequested` is what a host sets before it asks for a pair; the decode
    carries it as `Pending.isClip()`; `applyPending` writes `sourceKind` in the same
    GL event as the swap — **not at request time**, because a request that never
    completes would then have named a medium the screen never reached. Two fields on
    purpose: conflating a request with a fact about the screen would let a late decode
    rename the medium of the pair already being displayed.
  - **A clip must not decode while its engine is hidden, and the stop cannot ride
    the GL queue.** `onVisibilityChanged(false)` pauses the GL view, and a paused
    `GLSurfaceView` does not drain `queueEvent`, so a stop posted there sits
    undelivered for as long as the engine stays hidden — measured as a clip player
    created by the LOCK engine ~11 minutes earlier, three `clip-decode-*` threads
    and three `MediaCodec_loop` threads burning most of a core behind a dark
    screen, the battery-stats decode counter up 5m42s in 6 minutes of wall clock.
    So the host calls `suspendClip()` on the thread the visibility change ran on:
    the decoders stop, the clock is HELD at the clip time they reached, and
    `resumeClip()` seeds all three back to it — the same `startAt` seeding a grow
    uses — so waking continues the picture instead of rewinding it. Identity checks
    use `isLive()` (running OR suspended), because treating a suspended player as
    gone is what tears it down and rebuilds it; the pump and the watchdog stay on
    `isRunning()`, because a suspended player wants no frames and cannot stall. A
    `clipSuspendWanted` flag closes the race where a start lands after the hide, and
    the resume is gated on `PowerManager.isInteractive()` so an AOD doze pulse —
    visible but not interactive — does not rebuild three decoders for a screen
    nobody sees.
- `FramingRowValueTest` — the three numbers a framing row *prints*, and above all
  that Origin and Pan cannot be converted by one rule. It is the display half of the
  rule `ConfigTest` and `PerItemSettingsTest` pin on the stored half: **pan is in
  pixels and must not be clamped to Origin's fraction range.** That is easy to state
  and easy to break by making the two keys share a helper, because a shared helper has
  to pick a clamp and the only clamps in play are each other's — so both wrong answers
  are put on record with numbers that cannot survive either: an 86.5-pixel pan read
  with Origin's rule is 86500 thousandths and the row's own range throws it to the far
  stop, while the same 8.65% camera swing read with pan's rule is **0 pixels** and
  nothing complains, because pan has no range clamp to notice. The quieter of the two
  failures is why it is worth a test. Its second theme is the **round trip in both
  directions** — `originThousandths` is pinned against the device's own tap value
  (`-0.15712592` is `-157`), and `originFraction` must give back the integer it came
  from for every thousandth in the reachable range — because "the control displays
  exactly what the gesture will write" holds only while both sides call the same
  function, and two derivations of one value agree until the day one is edited.
- `RenderStatsTest` — the two lines the studio's on-glass performance readout prints,
  and above all **what a missing number prints**. The readout is an instrument for
  tuning splat cost, and the bug it exists to prevent is a zero that looks measured:
  the relief has no GPU timer and no sort, and a splat window in which the camera did
  not move runs no sort, so a `sort 0.0ms` the backend never measured is worse than a
  blank field — it claims the sort is free. The rule is the codebase's own, **a window
  claims nothing**: a field with no value is OMITTED, and the sentinels are a NaN and a
  negative count, because `0` is a real count (an empty cloud, asserted) and a real
  time. Its second theme is the **single derivation** the readout shares with the
  `splat:` log line: `RenderStats.fps` turns a frame count and an elapsed window into
  fps in one place, so the log and the readout cannot report two frame rates for one
  window, and `WINDOW_NANOS` is one constant so they cannot average two spans either.
  The formatting is pinned because a screenshot cannot check it: the headline is one
  decimal and blanks to an em dash, the count compacts to `741k`/`1.1M` so the render
  size stays on the line, and a relief prints `frame 16.7ms` and nothing else.
- `TiltFilterTest` — the tilt smoother, which two callers step at two different
  sample rates: `ReliefRenderer` once per drawn frame, `TiltController`'s doze gate
  once per sensor sample. It pins the rule (the exponential below `ATTACK_ERROR`,
  the blend into it, a snap at and above it, no overshoot, monotone and symmetric
  in the error) and, above all, **the property that lets one function serve both
  rates**: the gap is a TIME CONSTANT, so two half-steps land where one whole step
  does — a fraction hard-coded per frame would pass on the renderer and make the
  gate drift with the sensor rate. It also pins the clamp: a gap past `MAX_DT`
  (which is *below* tau) takes one capped step rather than becoming a snap, and a
  gap that cannot claim a number (NaN, negative, infinite) takes no step at all —
  the "a window claims nothing" rule applied to a step. The *mirror* itself is not
  a unit test's to hold, because both call sites are in `:app` and no `:core` test
  can see them: `scripts/check-parity.mjs` fails if either caller derives a
  smoothing step of its own or declares a copy of the constants.
- `CameraModelTest` — tap → unproject → project round-trip, plus "every Depth
  step is live at every zoom" and "Depth 0% is flat at every zoom". Its third
  theme is the pivot at depth: **the at-rest picture is unchanged for every origin
  and every pivot height** — the turntable's rest eye is fixed, so the view is the
  photograph whatever the origin and whatever the
  pivot (`originDoesNotMoveTheAtRestPicture` walks 5 origins x 3 heights x 3 depths x
  3 zooms through the real `frame` chain) — the pivot keeps its OWN position under
  tilt while the
  plane behind it swings, the pivot's world z is `h * scale * zScale`, and the
  Origin travel identity survives a pivot height because it is measured at
  rest.
- `PrefFilesTest` — the restore case, which silently costs users their image.
- `PrefFilesContentTest` — that two files are the same image, which is what stops
  a re-share from adding another identical gallery row. The traps it pins are the
  two ways the check can be made too eager: two different photos sharing a byte
  count must **not** collapse, and a missing file must never count as a match or
  an import gets swallowed by a row that has lost its image.
- `ConfigTest` — the settings blob: round-trip, clamping, and that `parse`
  never throws, because the wallpaper parses it on the GL thread at cold start.
  It also pins that pan is in **pixels** and must not be clamped to orbit's
  fraction range.
- `ComfyWorkflowTest` — the ComfyUI graph wiring, especially that the depth
  node reads node 1 and `SaveImage` reads node **2**, not 1. An off-by-one
  there renders the wrong tensor with no error. Its second theme is the **second
  graph shape**: MoGe is a pipeline (`LoadMoGeModel → MoGeInference → MoGeRender`),
  not a single node, so it gets its own seven-node builder and the tests pin each
  wire to its node *and* output index. **MoGe emits `depth` and `normal_opengl`
  from one `MoGeInference`** — two render nodes sharing the one cached
  inference, each with its own `SaveImage` and a distinct filename prefix
  (`depthmap_`/`normal_`), because a second output the fetch path does not
  expect is the failure that is silent. The MoGe picker entries are the
  discovered `LoadMoGeModel.model_name` options — one per checkpoint — so a
  server without the whole pipeline offers nothing and an ordinary node on the
  same server is untouched; a test asserts the two shapes have different node
  counts and different `SaveImage` sources so an edit cannot collapse them.
  - **The STREAM shape is a third builder: two nodes, one wire, and its node
    COUNT proves nothing.** `LoadMoGeModel → MoGeVideoStream` — the custom node
    streams frames through MoGe and writes both clips itself, so there is no
    `LoadImage`, no `SaveImage` and no inference node in this graph at all, and
    the test pins the CLASSES (no still loader, no saver, no `MoGeInference`),
    not a size, because a size is a discriminator that can pass for the wrong
    reason. The one wire — `MoGeVideoStream.moge_model ← LoadMoGeModel` output 0
    — is pinned to node AND index like every other link. Everything else travels
    as scalars: `video` is the uploaded name, `tag` is the item id sanitised by
    the app's copy of the node's rule (alnum plus `-`/`_`, 48 max) because the
    fetch and resume paths derive `basrelief_<tag>_depth.mp4` / `_normal.mp4` /
    `.progress.json` from it without ever being told them, `batch_size` is the
    window, and `frame_count` is 0 — the upload is the plan. `resolution_level`
    and `refine_steps` are deliberately NOT named: they arrive from the node's
    own spec exactly as the still MoGe shape's do, and this was checked against
    the deployed node's `/object_info` rather than assumed. The old VHS video
    builder is deleted with its tests; the graph that runs is this one.
  - **A guard that prints "matches" while exiting 1 is worse than no guard, and
    this one did.** `checkTestCounts` logged "matches AGENTS.md" from the *class*
    check's happy path while the *test* check had already pushed a failure, so a
    stale count (516 while the tree held 519) printed a reassuring line and exited
    1. I read that line twice as green while grepping for `"OK"` — which is exactly
    the mechanism the script's own comment warns about, and the reason to fix a gate
    that congratulates itself. Both counts must now agree before it says so.
  - **A count is only a discriminator when the shapes genuinely differ.** The old
    video shape's test asserting it had a different node count from the MoGe still
    shape was a guard that could not fail: the two happened to differ, but size
    alone proves nothing about which classes are wired. What is pinned instead is
    the CLASSES: the stream shape has no `LoadImage`, no `SaveImage` and no
    `MoGeInference`, and its graph is exactly the two nodes the builder names.
- `BatchBudgetTest` — the window rule and the time estimate. The rule is
  `streamWindow(width, height)`: the largest window in 1..4 whose `pixels ×
  window` product stays at or under the measured anchor, where the anchor is
  1920×1080 with window 4 — the run that held **11.5-12.3 GiB flat for all 480
  frames** while the old whole-batch graph climbed to 15.48 GiB and asked for
  4.03 more at frame 174 on the server's 16 GiB cap. The tests hold the
  endpoints (1080p → 4, 4K → 1, 1440p → 2), sweep a grid to assert no returned
  window ever exceeds the anchor product unless the pixels already do, and pin
  the unknown-dimension answer at 1 — the `DepthSource`/`VideoSource` "a window
  claims nothing" rule applied to a number, because a size the caller could not
  read must not be answered with a larger window. The measured costs that drove
  the chunk planner (26 MB/frame at 720p, the 2.3 height exponent, the 15 GiB
  batch budget) are deleted with the shape that needed them: the streaming
  node's peak is dominated by MoGe's ViT-G activations for one window, not by
  the decoded frames, which is why the rule is linear in `pixels × window` at
  all. Its second theme is the time estimate both surfaces state — the sheet
  before a run, the job line during one — from the single `SECONDS_PER_FRAME`
  constant. Its third is the chip's short-side reading (`targetHeightFor`):
  landscape 1080p is a no-op, portrait 1440x2560 at the 1080 chip is 1920 tall
  (1080x1920, not 608x1080), a small source is never upscaled, and unknown
  dimensions claim nothing.
- `JobsTest` — the pending-import record. The traps are the ones the class
  exists for: a record that does not survive the round trip is the loss this
  class was written to end, a blob that throws on read is worse than a lost
  record because it is read on the path to drawing, and a record naming a photo
  that is gone must not be resurrected. **v4 is one prompt again, and v5 adds the
  `Kind`** (clip or splat), so the
  tests pin the record's key set as exactly item/host/started/prompt/error for a
  clip job — a `done` or `frames` field written back would be a second, staler answer to the
  question the node's sidecar answers — and pin that a SPLAT job writes its kind
  while a clip job stays byte-identical to one written before the field existed
  (absent is a clip import). The migrations are the older half: a v1
  prompt and a v2/v3 chunk list both MIGRATE rather than being dropped (the
  first prompt is the handle the old run answers to, and dropping the record
  would strand a finished server result), and a v3 failure message survives, so
  a failed run keeps failing until the user acts. Removal and failure are keyed
  by PROMPT while the record itself is keyed by ITEM — that split is pinned,
  because a superseded run finishing must not clear or fail the submission that
  replaced it. Its preview theme pins the precedence window: a record with no
  tracks on the row names the row's colour preview, another row's record names
  nothing, **tracks win over a lingering record** (the resolve that lands
  between the finish's writes, and every resolve during a regenerate), and a
  FAILED record still previews because the colour is still the user's video.
- `ModelRankingTest` — the model chooser's ordering: the most recently used
  models first, then discovery order. It exists because the dialog preferred the
  first MoGe entry it found, so the default checkpoint followed the server's
  listing order — the device selected MoGe 2 while MoGe 3 sat unselected. There is
  no version ranking and no model named in `:core`: `rank` is a permutation, so a
  history entry the server no longer offers is **skipped, never invented**, and
  the rest keep their order. The history is capped and deduplicated by `remember`,
  called on the **selection** rather than a finished run, and `encode`/`decode`
  are the newline prefs form — an empty history is the empty string, so a fresh
  install is discovery order exactly as before.
- `FramingTest` — the one resolution of what should be on screen. It
  is in `:core` rather than `:app` for a reason: it lived in `:app` for one
  commit, and in that window the studio loaded a newly selected photo's
  files without its framing, so every photo arrived through the previous
  photo's pan, zoom and depth. A value that cannot be unit tested is a
  value that cannot be checked, and this is the one most likely to be
  quietly wrong. It also pins that the bundled demo renders at the
  defaults *whatever the index says*, because a Zoom stored on it is a
  number a slider will display and the renderer will never use — now on
  **both** surfaces, since a lock override is a second way to store a value
  nothing reads. Its third theme is Motion: a test here used to assert Motion
  came *from the caller*, which was right while Motion was global and is the bug
  now that it is per-photo. It also pins the inheritance rule — a lock screen with
  no override resolves to exactly the home screen, because that is every photo's
  initial state, and a write to home must not conjure an override.
- `PerItemSettingsTest` — per-surface settings, the migrations that brought them
  here, and the one reserved id. The traps are the ones this file already names:
  pan is in **pixels** and must not be clamped to origin's fraction range (delta
  exactly 0, so a partial clamp cannot hide), and the bundled item is the one entry
  with no file on disk, so a cleanup pass or a parse must not drop it — it cannot
  be recreated from filesDir. More it earns: the v2 → v3 migration must copy
  the old **global** Motion onto every item, because the library blob has no copy of
  it and dropping it silently resets everyone's wallpaper speed on upgrade; the v4 →
  v5 migration must copy the four **global** splat-look prefs onto every item's home
  surface for the same reason, and it is asserted **idempotent by the data** with the
  version forced back to 4 so the second parse actually exercises the step rather
  than skipping it — the weak version re-parses `toJson()`, which is already v5 and
  proves nothing; a default value must write **no key**, so an untouched library's
  items stay byte-identical; the four look values round-trip and clamp to their
  useful band, **including a SIZE of 0, which must not be clamped up to the old
  0.45 floor**; and a single-field edit must not reset the values it does not name,
  which shipped broken once — `withDepth` reset Motion, brightness and shadow,
  because the values it did not pass had been added after it was written, and the
  four splat rows are exactly that shape of value one version later.
- `SurfaceResolverTest` — which surface an engine is drawing, and the **three**
  questions it takes to answer that: the engine's own surface, whether the lock
  screen composites it, and what the lock screen can be at all. They are three
  separate truths on purpose — collapsing them either offers a control nothing
  reads, or refuses one that would work. The traps are the ones the truth table
  names: 26–33 have no signal to read, 34 has the signal but not the second engine,
  "both" is one window and therefore one set of values, and a lock-only binding on
  34 is a **fact** while per-surface lock settings are still a **promise** — the
  two must not be collapsed, or a Lock toggle appears on a screen whose value is
  never rendered. A negative or unread flags value is not a bitmask; usability is
  decided by sign, because masking −1 yields every bit.
- `PictureTest` — brightness and shadow, which live in `:core` precisely so they
  are not trapped in GLSL where nothing can check them. The trap is the one this
  project keeps paying for: **0 must be the identity for both**, because a new
  photo is written with explicit zeros and so every photo in every library takes
  that path on its first render. Brightness is hundredths of a *stop*, so it is
  exponential — a linear mapping passes a naive identity test while being visibly
  wrong. Shadow is signed, or "less contrast" becomes unreachable.
- `LightDirectionTest` — the angle→vector mapping for `uLightDir`, which was a
  literal in the shader's caller until now. The trap is the default: the two
  constants are **derived from** `(-0.35f, 0.5f, 0.75f)` rather than picked as
  round numbers, and the test holds the resolved direction to within `1e-6` per
  component (measured ~7e-8), so an upgrade cannot quietly relight every photo.
  It also pins the convention sliders will be built against (azimuth
  counter-clockwise from the right, elevation 0 grazing and 90 head-on), that
  azimuth wraps while elevation clamps, and that the surface glide takes the
  short arc around 0/360 — neither spinning 340° nor passing through the zero
  vector two opposite lights would produce.
- `PlaylistTest` — rotation, and the invariant it exists to hold: rotation writes
  the index's own selection, by the same two calls a user's tap makes, so there is
  never a second pointer. The test asserts **byte-equality of the whole blob**, and
  a second test enumerates the blob's allowed keys — so adding one is a deliberate
  edit rather than an accident. Also pinned: a delete removes the id from every
  playlist in the same derivation; a corrupt or future blob yields no rotation and
  does not throw; a playlist naming a deleted photo cannot resurrect it and a hole
  does not cost the playlist; a reorder moves one photo by whole places, refuses to
  wrap past either end, carries every setting, and cannot grow the list or fill a
  hole; an event trigger is never due on a clock, an unlock event fires only an
  on-unlock playlist and only once per moment — the two families must not answer
  for each other; and a user's
  hand-picked selection does not move a playlist's own clock. Note the trap in its
  own making — a "migration is idempotent" test that only re-parses the
  re-serialised blob **skips the migration entirely** and so cannot test it.
- `PlaylistStatusTest` — what is rotating, on which screens, and when it next
  changes. The trap is a status that is **written down**: every fact already lives
  on a playlist or on the index, and `PlaylistTest` enumerates the blob's allowed
  keys, so a stored copy would break rotation's byte-equality guarantee as well as
  drift. Its second theme is the **surface as a gate**: a playlist whose stored
  screens could not be read rotates nowhere, and a status that guessed home would
  put a running badge on a wallpaper nothing will move. It also pins the
  guaranteed-immediate step behind the editor's Use action — the scheduled path
  must decline inside the interval, and `step` must move anyway, byte-identically
  to a tap's write and stamped with the real now rather than a faked due time that
  would push the next automatic rotation further out.
- `LibraryDuplicateImageTest` — which row survives when several share an image
  file, which is the repair for a share path that wrote every import to one fixed
  name. The trap it exists to pin is *which* survivor: the active row is kept even
  when it is the oldest, because keeping "the newest" instead would drop the row
  the selection names and leave the wallpaper pointer pointing at nothing. The
  last test asserts the end-to-end property — after the repair, the selection still
  resolves to a row that exists.
- `LibraryTest` / `LibraryItemTest` / `SortOrderTest` — the gallery index. The
  traps are the ones this file already names: a corrupt blob must not throw, a
  restored backup must not resurrect a dangling selection, an absolute path
  must never be stored, and deleting the *active* item has to clear the
  wallpaper pointer in the same write — otherwise the next reconcile adopts the
  deleted files straight back and the delete never sticks. The bundled preload
  is deletable on those same terms, and what stops a reload resurrecting it is
  the empty-only seed, not a refusal to delete.
  Its third theme is that **the demo is a file-backed row**, so the tests build it
  from `LibraryItem.imageFileName(BUNDLED_ID)` rather than spelling a name out —
  the assertion *is* that the seed stores it under the ordinary per-id scheme. The
  migration that gives an already-installed row those files is pinned there too,
  and the trap in it is **idempotence held by the row's own contents rather than
  by a flag**: this runs on the read path, including on the GL thread at wallpaper
  cold start, so "have we done this yet" has to be answerable by looking at the
  data. Three further traps: a *failed* copy must store no filename at all, because
  a name for a file that is not there is what the next cleanup pass drops the row
  for; the repair must never *add* the row, or a delete stops sticking; and a row
  seeded by the write path (which has no `Context`, so cannot copy) must converge
  on the same bytes as one seeded by the read path.
- `SheetMorphTest` — the studio sheet **morphing into the pill** as one view on one
  axis, because it used to be two views with two lifecycles (the panel's height
  reached zero and a separate `pill` view went VISIBLE), and a pop by construction
  is also why the pill could not be a drag handle. **The height mapping is piecewise
  -- hold, then fall -- and that is a decision, not an accident.** Every fraction below
  `HEIGHT_BAND_START` returns the natural height *exactly*; only the last stretch
  falls to the pill. The brief: the shrink should happen only at the very end of the travel. A plain lerp spends the whole gesture giving up
  height, so every centimetre of travel costs the sheet some of the screen and
  dragging down reads as shrinking a card rather than putting something away.
  `HEIGHT_BAND_START` **is** `EDGE_BAND_START` -- the same line as the fade -- because
  inside the band the content is dissolving and the sheet is losing its height, and
  outside it both say "still here"; the test asserts that equality, and against
  `SheetIntent.SHEET`'s threshold, so all three numbers cannot drift apart.
  **Price this before widening it:** the whole collapse now happens in the last 5%
  of travel, and the studio sheet is `FREE`, so it can rest nowhere inside that band
  and a drag past 95% commits all at once. It is one constant.
  The height's other traps: it must never dip below the pill at any fraction (a curve
  that dipped would be a card that gets *taller* as it collapses, which a sweep
  catches and a spot check does not), and it must keep rising for a negative
  fraction, because that is the over-open tether and clamping it would delete a
  spring the user can feel. **"Monotonic" now means non-increasing, and the plateau is
  the point** -- a strictly decreasing function over the whole travel is precisely
  the old behaviour. Continuity is asserted by *resolution*: doubling the sweep
  density must halve the largest single step, which a seam at the join cannot do.
  An earlier version asserted "the last 1% must move under 5% of the fall" and
  failed; the assertion was wrong rather than the curve, because the fall is
  confined to 5% of travel, so 1% of travel is a fifth of the fall by arithmetic. The fade's trap is **the parked sheet**: the studio is
  `FREE` and can rest anywhere below its collapse threshold, so the band is pinned
  to exactly that threshold — asserted *equal* to `SheetIntent.SHEET`'s, so the two
  cannot drift. **What makes fading there honest is that equality and nothing
  else.** `SheetIntent` collapses a release only when `projected >
  collapseThreshold`, and `edgeFade` is exactly 1.0 at or below that same number, so
  no position the sheet can rest at is ever faded. The argument never mentions
  height, which is why it survived the height being changed underneath it. An
  earlier version of this test justified the band by the sheet being *small* at its
  start — true only while the height mapping was linear, and it sampled exactly one
  point of the curve to prove it. That is a test which keeps passing straight through
  the moment the curve's shape changes; it was rewritten and the fact that the band
  start is now at **full** height is pinned instead. The cost is recorded rather
  than asserted away: inside the band the sheet is still tall while its content
  dissolves, so it is the one stretch where a fading sheet is also a big sheet.
  Note two more tests here written wrong and fixed rather than papered over: one
  compared `edgeFade(f)` with `edgeFade(f)`, which cannot fail; and one asserted
  the band began with the panel "nearly its full height". The radius test exists because interpolating toward
  `panelHeight / 2` gave a 2400px panel a **611px** corner half way down a drag —
  a stadium — so the panel's half-height is a ceiling, never the target. Its third
  theme is the rest of the **shape**, which the radius could not fix alone: the
  morph rounded the sheet's top corners and left the object a full-width bar
  flush to the bottom of the screen, and the user filed that as the fully collapsed sheet did not look like the original pill — a complaint every measured
  number contradicted, because the strip really was 167px, unmoved and unfaded.
  **Appearance is not measurable by bounds, and this is the second time that has
  cost a round trip.** **ONE PROGRESS FOR THE WHOLE MORPH, and what it took to get the
  SHAPE right.** The height was banded -- held at full until the last 5% of travel --
  while the shape was a plain ramp across the whole gesture, so at a quarter of the way
  down the drag the sheet had all of its height **and** was already narrower, lifted,
  rounder and recoloured. One gesture, three timelines, every measured number correct --
  and the user read it as the sheet moved as three unrelated timelines. That half
  was real and is fixed: all five properties read `SheetMorph.morphProgress`, which is the
  collapse fraction itself.
  **But the band was then the wrong fix, in the other direction**, and the user had to
  say so a second time: *the height did not follow the drag and then collapsed all at once; a bottom sheet's height should track the finger*
  My first reading of *"the shrinkage shouldn't happen until at the very end of the
  bottom"* was "hold until the last 5%", which is a bottom sheet that ignores four fifths
  of your drag and then snaps shut. **So the geometry has NO band at all: the height ramps
  across the whole travel, because that is what dragging a bottom sheet down IS.** The band
  survives in exactly one place -- the content's OPACITY, asked for twice, pinned to
  precisely the line past which the sheet can no longer come to rest, so no position a
  resting sheet can occupy is ever faded. **Two instructions about two different
  properties, read as one.** Seven tests encoded the band and were rewritten rather than
  deleted; four had been passing while asserting the wrong thing. Two earn their place:
  `everyGeometricPropertySharesOneProgress` walks a hundred fractions and holds the
  height, the inset, the side margin and the radius at the SAME point in their journeys,
  which is the invariant that actually stopped the "illogical" report; and
  `theHeightIsNotHeldAndNotSnapped` walks the travel asserting every intermediate
  fraction is a real intermediate height -- no plateau, no cliff -- which is the bug that
  had to be reported twice before it was fixed.
  **TWO RAMPS, ON TWO PROPERTIES, AND THE BAND IS ON THE SHAPE.** The final shape of
  the answer: `morphProgress` is the collapse fraction and drives the **height** the whole
  way, because *a bottom sheet's height should not always be full*; `shapeProgress`
  holds the sheet's own shape and drives the **width, insets, radius and fill** only in
  the last tenth, because *the width should stay unchanged until the very end* A full-width
  panel at 40% of its height is still a sheet, and rounding and tinting it towards a pill
  while it is plainly a sheet reads as two objects. **This file got this wrong in BOTH
  directions** — banded height and ramped shape ("illogical"), then banded height left in
  place after moving the band (the height did not reduce and then it collapsed) —
  so the two functions are named for what each drives and one test holds each to its own.
  `SHAPE_BAND_START` is 0.9 and is a judgement in one number; the content's opacity keeps
  its own separate band at `EDGE_BAND_START`, which is about a different property again.
  **The pill's shape is a width, not a margin.**
  version of this fix specified it as 28dp a side -- faithful to the pre-morph pill,
  and still a bar, because on a 483dp phone that leaves 88% of the width. The token
  is now `PILL_WIDTH_FRACTION = 0.72` in `:app`, with the subtraction in
  `SheetMorph.pillSideMarginFor`; a fraction rather than dp because the sheet is
  edge-to-edge on every supported phone, so a fixed dp width is nearly the screen on
  a compact one and a lozenge on a large one. Centred by construction -- the margin
  is half the leftover -- so the two sides cannot differ. So `insetFor` (26dp off the
  bottom, plus the nav bar) and
  `blendColour` (the sheet's own glass to the original pill's plate, both
  recovered from `227a53f^`) are here too, and the capsule is the *same* radius
  function asked with `0` as its open radius rather than a second rule that could
  drift from the first. And the eight-value array that writes them is a function
  too, `SheetMorph.cornerRadii`, because the order — **TL, TR, BR, BL** — is a
  separate defect from the radius's value and was reported as one: *the right side's rounding was wrong*, with the outline's top-right corner square while its
  top-left was rounded. Hand-written at the call site the array was
  `{top, top, bottom, bottom, bottom, bottom, top, top}`, which reads as four
  correct pairs in the wrong order and puts the bottom radius on the top-right
  corner. A screenshot at rest cannot tell a wrong radius from a wrong slot; reading
  the array back corner by corner can. Their shared trap is **no step in the last 1% of travel**,
  swept at a hundredth of a percent because a snap there is invisible in any
  screenshot taken at rest and obvious in the hand. Two of the assertions here are
  written as *signed* checks per channel for a reason worth keeping: the alpha
  **falls** (the strip eases from 0xEB glass into 0xB8 plate, so more of the render
  shows through) while the colour channels rise, and a first run that asserted one
  direction for all four caught exactly that.
- `DepthSourceTest` — whether a photo has depth at all, and **where that depth
  lives**, which are two different questions the app had been answering with one. The
  bug it exists for: `hasDepth()` asked only whether a separate depth *file* exists, so a
  PNG carrying its map inside itself in a `dePH`/`dEPt` chunk reported **"No depth" while
  the renderer was drawing the embedded map** — the status line lied about the photo on
  screen, and the gallery, which reads the file, got it right. Four kinds, not two:
  `NONE`, `FILE`, `EMBEDDED`, `TRACK`. Its traps are the user's decisions rather than the
  author's: an embedded map reports **has depth** and offers **neither** Regenerate nor
  Replace, because there is no depth file to replace. And the window where the embedded
  flag is still being read must NOT answer `NONE` — defaulting an unknown to "no depth"
  *is* the bug, so there is a third answer for it and a test that says so. `TRACK` is a
  clip's depth, and it is a fourth kind rather than an `EMBEDDED` special case because
  which file the map is in is the whole difference: an embedded map is a chunk of the image
  being drawn, a track is a second video that must stay frame-locked to the first
  (`docs/VIDEO-PLAN.md` §2, §4). It offers neither action for two separate reasons —
  regenerating is §3's offline pass over every frame, and replacing a depth clip alone
  would leave it locked to a colour clip it no longer matches (§3 step 7). A clip opens a
  **second window of the same shape**, and it is the wider one: before the decoder reports,
  a row naming clips will render as one of two different pictures, so `UNREAD` is reached
  from `VideoSource` exactly as it is from the PNG's chunk. The two-argument `forItem` is
  kept and is *honest about a clip rather than convenient*: it cannot have read a
  container, so a clip row comes back `UNKNOWN` instead of being guessed at.
- `NormalSourceTest` — the new middle state: a photo with depth and no normal is
  **flat lit**, not shaded and not broken. The trap is the state that no longer
  exists: `DERIVED` is deliberately absent, so a kind that named it would put
  "shaded" on the sheet while the shader drew flat. A normal with no depth is
  inert, nothing selected is the packaged trio (which ships a normal), and
  exactly one kind reports supplied normals — pinned over every reachable answer
  so a future kind cannot inherit a default. **Its second theme is `UNREAD`, the
  state this class did not have and had to grow.** Its old claim — "nothing has to
  be read to answer this" — was true for a file the index names and false for a
  track a decoder has to produce, so a clip row is genuinely unknown between
  selection and first frame. Folding that window into `NONE` would put "Flat lit —
  depth, no normals" and a "generate normals" button over a clip whose every frame
  is shaded: the reported "No depth over a photograph with a map" bug again, with a
  different verb. The clip answers are also the test that a rule reading
  `normalName` would fail — a clip's *poster* can be shaded while the clip is flat
  lit, and both rows carry identical still files, so reading the still would answer
  the same for both and be wrong about one.
- `VideoSourceTest` — whether a row is a photograph or a clip, and **whether the
  clip can play yet**. It is a third source class because neither sibling can answer
  it: a clip's depth is a track inside a *second* video and its shading a third, so
  "where did this come from" is being asked of a different thing. The trap is the
  window, and it is the same rule the other two carry: **it claims nothing**, not
  "still" and not "clip", because both are claims and the default has to be the one
  that says nothing. Two supporting properties are pinned because they are what
  keeps the window narrow and honest: **no photograph ever has one** (a row with no
  clips is answered from the index alone, so no still flickers through the window on
  selection), and **`POSTER` is not `STILL`** — a clip whose files are not here is a
  clip that fell back to its poster frame, and reporting it as a photograph would
  send the user to regenerate a depth map when what is missing is a file that was
  there. The kind enumeration iterates `Kind.values()` rather than a hand-written
  answer list, because a hand-written list is exactly the guard that goes stale: a
  fifth kind could arrive and the old test would keep passing.
- `SplatSourceTest` — the fourth "where did this come from" vocabulary: a row's
  Gaussian splat cloud, beside `DepthSource`/`NormalSource`/`VideoSource`. The trap
  is the one its three siblings carry, and it is why the class has three kinds
  rather than two: **a window claims nothing.** The index says a row *names* a
  cloud; whether the bytes are here is a file read, so answering `NONE` during that
  window would put "no cloud yet — make one?" over a row that already has one — the
  confident wrong answer `DepthSource` was written to remove, with a different noun.
  It pins that a row with no cloud is answered from the index alone (so no still
  flickers through the window on selection), that `FILE` requires the file to be
  here, that only `NONE` may say "no cloud", and that every kind is reachable
  through `Kind.values()` rather than a hand-written list that would go stale.
- `Scene3DTest` — "is there any 3D here", combined from the two sources that can
  supply it. It exists because the fix line asked `DepthSource` alone, so a row with
  a splat cloud and no depth map read **"No depth"** while the renderer drew a cloud
  in three dimensions — the reported bug, and the same lie `DepthSource` was written
  to remove one medium over. The rule the user stated is the whole class: **as long
  as a relief or a splat is present the no-depth affordance must not appear at all**,
  and only a row with neither may say anything, in the words "no 3D". It is a
  combining function rather than a fifth vocabulary: it asks neither source's
  question again and invents no facts, so there is one place that decides what "has
  3D" means. The trap is the one both inputs carry — **a window claims nothing** —
  and it is why there are three kinds rather than two: an unread PNG chunk and an
  unread cloud file are both "not known yet", so a row in either window is never told
  it has no 3D. The second rule is the one a naive OR would get wrong: a **known
  positive beats the other source's window**, because a row that already has a map
  has 3D whether or not its cloud has been stat'd, and answering `UNKNOWN` there
  would blank a line about a picture already on screen. Every kind is reachable
  through `Kind.values()`, and only `NONE` may say "no 3D".
- `BuildTabTest` — the one 3D-build dialog's two tabs, and the two rules a screen
  cannot be trusted to keep: **the tab a build runs on is the mode it writes**
  (`defaultFor`/`modeFor` are inverses, so opening a row and pressing the visible
  button cannot silently flip how it draws — the "two answers to one question"
  failure one level down), and **a tab offers an action it can actually perform**.
  The traps are the state rankings: a run in flight offers `NONE` whatever the row
  otherwise holds (a second build over the first), a failure outranks the asset it
  left behind (the cloud from yesterday is not the thing to act on), and a depth
  this dialog cannot replace — an embedded chunk, a clip's track — is `BUILT` with
  no action, which is where `DepthSource.canReplace()` is read rather than
  re-decided. Both resolvers rank the run the same way, asserted, so the two tabs
  cannot grow two meanings of `FAILED`.
- `AssetActionsTest` — the **secondary** gate on the sheet's asset row: the actions
  that are not the mode's build. The user's report is the whole reason the two
  duplicated rows became one: *the rebuild/delete row sat right below the tabs while the photo-map row at the bottom offered
    regenerate/replace, and the two were expected to be combined* This class reads `BuildTab`'s own two
  answers rather than a second copy of its ranking, so "a run in flight offers
  nothing" and "a failure outranks the asset it left behind" hold here exactly as in
  the tabs. The traps are the gates: **Delete** belongs to a cloud that is here and
  to no run that owns the row; **Replace** belongs to a still depth **file** and
  **Choose a map** to a row that has none, gated on `reportsNoDepth()` so the UNREAD
  window — also `State.NONE`, and whose `canReplace()` is false — claims nothing
  rather than offering a map for a photo that may already have one; an embedded map
  offers neither. The third case is the one no tab can cover: a **clip's track** is
  `BUILT` with no action in the dialog's Relief tab (`canReplace()` is false), so its
  regeneration — which runs the whole video graph — has no tab to live in and is
  offered here instead, gated on the clip's own job rather than the still run the
  relief answer ranks. Dropping it would strand a whole feature.
- `AssetRowTest` — the row itself: the **selected mode's** state and its **primary**
  action, beside `AssetActions`' secondaries. It is what the earlier consolidation got
  wrong, and the user found it: *the splat regeneration action and the splat count had gone missing* — the state and the primary had been moved into the dialog's
  tabs, which did not deduplicate them, it **hid** them. The traps: **only the
  selected mode's row exists**, so the same facts resolve to a depth row under Relief
  and a cloud row under Splat and a cloud's Delete cannot appear on a relief row; the
  **primary is `BuildTab`'s own action**, walked over every tab state, so a run in
  flight offers no button in either mode; the **order** is the design's — Relief leads
  with the asset action (Replace / Choose a map) and trails with the build, Splat
  leads with the build and trails with Delete; and the one **asymmetry** is a
  decision: a Splat row carries its run (the phase, the server's words) because a
  cloud build has no fix line, while a Relief row states its asset whatever the run is
  doing, because the fix line is the depth run's signal and a second copy would be
  staler. `isNone()` hides a row with no state and no button — the unread window —
  but a state sentence with no button keeps it, which is how an embedded map says why
  there is nothing to build.
- `VideoTracksTest` — the clip's three files, and the invariant that makes them one
  thing. §2 says *"never store one without the other"* — colour without depth renders
  **flat, with no relief and no error** — and three loose `String`s could each be null
  with every writer having to remember, so the type refuses to be constructed without
  both and the rule lives where a writer cannot forget it. The traps: a **stored
  absolute path is the restore case** for all three names (with `allowBackup="true"`
  it survives a restore and still names the previous device's `filesDir`, so the clip
  is not degraded — it is a row that renders nothing), and resolution therefore goes
  through `PrefFiles`, which accepts both forms, because a basename that is never
  re-resolved against `filesDir` is the same bug with the other half of the fix
  missing. A **half-written clip object parses as no clip at all**, because the
  degradation is the poster photograph and dropping the row would take the user's
  photo with it. And `playable` is deliberately the *cheap* question — both required
  files resolve — because the decoder's answer belongs to the window above it, not
  here. The normal track's absence is asserted as a *state*, not an omission.
- `VideoLibraryTest` — a clip as a row, and the property everything else rests on:
  **a clip is not a second kind of row**. The index owns the selection and only the
  index, and a separate container of clips would have made the same disagreement
  possible for the *medium* as for the pointer — invisible until the home screen was
  wrong. So a clip is one `LibraryItem` with one extra nullable reference, and
  `Library`, the selection, the sort, the archive flag and `Playlists` need no branch
  of their own; that is asserted per piece of machinery rather than in prose. Two
  halves of the round trip are separate risks and both are checked, because losing
  the clips looks like a video turned back into a photo and losing the *selection* is
  invisible on a photograph. The `everyMutatorCarriesTheClip` enumeration is the
  `withDepth` scar one field over: a clip is exactly the shape of value that was added
  after the writers existed, so a writer that forgot it would quietly turn a user's
  video back into a photograph with no error anywhere. The one decision the documents
  do not make is pinned as `aClipWhoseFilesAreGoneKeepsItsRowAndReadsAsAPhoto` — the
  row is kept (its poster is a file, and dropping it would delete a photo the user can
  still see) while the medium is `POSTER`, so the status line says the clip is not
  playing rather than claiming the row is a photograph.
- `SheetIntentTest` — what letting go of a sheet drag means, which is the rule
  a drag to 98 % that reverses stays open, while down-up-down closes. The rule asked
  for. The trap is reading a *position* when the user changed *direction*: the
  tests pin that the projection carries the last direction past the collapse
  line, that the two thresholds leave a dead band where nothing changes, that a
  decisive flick decides from either end by itself, and — honestly — that a
  reversal too slow to change the projection still collapses from the bottom.
  Its second theme is that the thresholds are a **policy**, not a rule: the
  studio's free panel collapses only at 0.95 projected while a dialog commits at
  0.60, and a test holds the *same* release to opposite answers under the two so
  one host cannot quietly inherit the other's numbers. It is in `:core` because
  the rule is pure arithmetic in fractions of travel, so no device and no pixels
  are involved.

The counts above are enforced by `scripts/check-parity.mjs`, so adding a test
class without updating this list fails the build instead of quietly making the
file wrong. That script also holds the two claims no JVM test can see: the paths
this file names exist, and the tilt smoother has one home (no `:app` caller may
derive its own smoothing step or declare its own copy of the constants).

If `meshMatchesGoldenOutput` goes red, **read the diff and decide whether the
geometry change was intended** before regenerating. `RegenerateGoldens` is a
`main`, not a test, because a test that rewrites its own expectation can never
fail.

**Device-only checks — these cannot be verified off the phone, so ask:**
gyro sign conventions and *feel*, texture orientation, live-wallpaper
rendering and performance, and OEM immersive behaviour.

Gotcha for adb: **if more than one device is attached, pass `adb -s <serial>`.**
A second phone appearing over WiFi has silently redirected installs.

- **`adb exec-out screencap` cannot be trusted to show what is on the display,
  and using it to verify a drawing bug manufactures a pass.** Measured on the
  Pixel 7 Pro while the studio's bottom sheet was invisible-but-operable: two
  captures three seconds apart were byte-identical, `screencap` returned a
  different buffer each time it was invoked, and it showed a panel that was
  demonstrably not on the phone. Taking a capture appears to force the very
  redraw under test, so the screenshot confirms the fix you just made. It
  produced three consecutive false confirmations in one session, each
  announced with a table of measurements, none of which could have failed.
  **Ask the user for a screenshot taken on the device** (Power + Volume Down)
  and pull it with `adb pull` — that path goes through the display and shows
  what is actually there. `dumpsys activity top` is fine for existence
  questions (is this view in the hierarchy, what are its bounds) and useless
  for appearance: child bounds are **parent-relative**, not screen-absolute,
  so a swipe aimed at y=36 can land on the photo rather than on a control 36px
  below the panel's top edge.
- **A view that is placed correctly, opaque, attached, `isShown()`, and still
  not on screen has had its pixels discarded, not never drawn.** That symptom
  — operable to the touch, absent from the glass — is a stale window region,
  and the fix is to change **layout**, not a transform. The sheet used to hide
  and show with `setVisibility(GONE)`/`VISIBLE` and worked; rewriting it to
  position with `translationY` broke it, because a visibility change re-lays-out
  and repaints while a transform did not reliably repaint that region here.

## Known drift (not bugs to fix blindly)

- Default Depth is 90 on Android vs 45 on the web port; the web tilt range
  scales with zoom, Android's does not. The ports are not expected to match —
  pick the behaviour you want deliberately rather than "fixing" one toward the
  other.
- Depth is a fraction of the reachable maximum, so absolute depth varies with
  zoom. That is intentional.
- The `intensity` pref is 0..100 and is clamped on read, not migrated, so
  values written by a 0..200 build are reinterpreted.
- The texture is uploaded without mipmaps, so heavy minification aliases. This
  matters for any future render-resolution scaling.

## Where the history lives

**This file deliberately does not record fixed bugs.** "Why is this line like
this?" is answered by the code, its comment, and `git log` — commit messages
here carry the diagnosis, the rejected hypotheses and the measurements. If a
rule here ever contradicts the code, the code and the log win; fix this file in
the same commit as whatever revealed the drift.
