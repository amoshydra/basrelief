# SPLAT-MAKE3D — the "Make 3D" flow (Stage 2, app half)

The plan for turning an item into a splat cloud the wallpaper renders. Written to be fired as
an implementation brief the moment the Stage 0 fix lands (it edits `LibraryItem`/`MainActivity`;
two agents in those files is how a schema gets half-applied).

## The user-visible flow

1. **Select an item.** The sheet's geometry group gains one row: **"Renderer — Relief | Splat"**.
   **Both options are always tappable.** The cloud row below it is the *asset* row, not a call to
   action:

   | state | renderer row | cloud row says | cloud row's action |
   |---|---|---|---|
   | no cloud | `Relief │ Splat` (Splat tappable) | `no cloud yet — make one?` | **Make one** |
   | building | `Splat` selected | `reconstructs this photo · about 10 s` | (progress) |
   | built | either selected | `149,504 splats · SHARP` | **Rebuild** |
   | failed | `Splat` selected | `failed — <the server's own words>` | **Regenerate** |

   **Pressing Splat is the offer.** There is no "Make 3D" button living on the sheet by default:
   the intent comes first, the offer follows. Splat stays selected while the cloud is built, and
   the picture shows the relief until the cloud exists.

2. The offer opens a small confirm dialog — the depth dialog's shape: what will run (SHARP, on
   the configured server), the **budget** (150,000 splats), the **estimate** (~10 s, ~4.8 MB),
   Not now / Make one.
3. **While it runs**: the row carries the progress, the gallery tile's corner takes the ring
   (`CornerMark`'s existing RING state — the same ring the pill uses), and the status line names
   the phase. A still is short, so no sidecar-driven frame count is needed; the record + the
   history poll are enough.
4. **Done**: the item names its splat, the row shows the count and offers Rebuild, and the
   wallpaper draws it when the About **3D mode** switch is on. The tile gains a small **3D**
   badge (the corner slot's one spare state; `▶` already means a clip).
5. **Failed**: the server's words stay on the row with Regenerate. The record is kept as FAILED,
   exactly as a clip import's is — a failure that lives only in a toast is gone before it is read.

## Controls — what each one means in each mode

| control | relief | splat |
|---|---|---|
| **Depth** | how far the surface is displaced | **hidden** — nothing to displace; the cloud's shape is reconstructed |
| **Light direction** | lights the displaced surface | **hidden** — SHARP's Gaussians carry no usable surface normal (measured: the covariance's minor axis is dominated by the view ray, ~11° from it at the image centre and ~28° at the edge), and no per-splat diffuse is identity at the default direction, so a real relight is an SH problem |
| **Zoom** | the camera | same value, same meaning |
| **Origin** | the point the orbit turns about | same value — the pivot rides the cloud's surface depth |
| **Pan** | the view | same value |
| **Motion** | how much the tilt moves it | the parallax gain — the cloud's own geometry does the rest |
| **Brightness · Shadow** | the picture's grade | unchanged |

The shared six **carry their values across the toggle** — the same photo, the same framing — and
only the rows that apply are shown. A control nothing reads is a bug, not a greyed row: the
relief's two are hidden in splat mode, and the splat has none of its own.

## Where the UI lives

- **The sheet**, in the existing geometry group beside the depth rows — not a new screen, not a
  new dialog family. The depth group already answers "what is this item's geometry"; a cloud is
  the same question answered in 3D.
- **About → 3D mode** stays the draw/no-draw switch it already is (Stage 0 built it).
- **The tile corner** reuses the one derivation (`Gallery.cornerState`) with one added state.

## The mechanics

- **`:core`**
  - `ComfyWorkflow.sharpStill(...)` — `LoadImage → SharpStill`, with the budget and the item tag;
    node ids, inputs and outputs pinned by tests, the way the MoGe and stream graphs are.
  - A `SplatSource` vocabulary (NONE / FILE / UNREAD) beside `DepthSource` / `NormalSource` /
    `VideoSource`, so "where did this come from" answers for a cloud like everything else.
  - The budget constant (150,000) in one named place.
- **`:app`**
  - Upload the still through the existing `/upload/image` path the depth flow already uses.
  - Submit `/prompt`; write the **`Jobs` record** (item, host, prompt) the moment the server
    accepts — the record is the only thing that says the work is expected.
  - Poll `/history` (+ the node's sidecar JSON), fetch `basrelief_<tag>.splat` via `/view`,
    **rename into the item's per-id scheme** (`item-<id>.splat`), and store the **filename**.
  - `editItem` (the index write + the in-memory publish, so the wallpaper sees it immediately),
    then `Gallery.itemUpdated` — a patch, never a reindex.
  - Clear the record; on failure keep it as FAILED with the server's words.
- **Estimates** come from the node's measured numbers: SHARP forward 2.5–2.9 s, ~5 s model load,
  ~10 s end to end for a still at the 150k budget.

## Decisions

- **Budget**: the generator emits a conservative **150k** default in v1; the LOD ladder plus a
  one-off measured pick comes later. (Recommendation from the performance work: the count is the
  cost; 150k holds ~55 fps with culling, ~48 fps without.)
- **Stills first.** Clips need the streaming node plus the fusion, and their dynamic layer's size
  is Stage 3's problem.
- **One row, four states** — no separate "3D" screen.

## Invariants this flow must keep

- Filenames, never paths (`PrefFiles`); the index owns the selection.
- The nullable splat field's rules: 0-or-absent in JSON, and **every mutator carries it**.
- The record is what says the work is expected; a killed app does not lose the run.
- Progress patches the strip and grid; a rebuild re-decodes every thumbnail and is not allowed.
- The ring stops whenever the state leaves RING and whenever a tile is dropped.
- A failure shows the server's own words, and Regenerate clears the record and re-runs.
