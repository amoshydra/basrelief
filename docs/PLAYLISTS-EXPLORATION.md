# Playlists — the gallery treatment

Status: design, nothing implemented, no app code changed. The drawing is
`.design/prototype/playlists-explore.html` (three tabs, ten states).
`docs/ARCHIVE-DESIGN.md` is touched only where item 5 reordered the scroll.

**Gallery only.** Nothing is drawn inside the studio sheet. The prototype lags
the app (`AGENTS.md`, *Design prototypes*), so the chrome here was checked
against `ModalSheet` / `PhotoTile` / `Gallery.java`, not copied from the stale
`final.html`.

This pass is eight changes to the accepted gallery design. The earlier
reasoning still holds and is not repeated in full: the list is gallery content,
the **editor stays a screen**, and the archive analogy only goes part way.

---

## 1. The row: label leads, edit then control trail

```
[ 2×2 collage ]  Favourites                       [ ✎ ]  [ ◉ ]
                 4 photos · Daily · Home + Lock
```

The control moved from the leading slot to the **trailing** one, which is the
app's own convention, stated in `ModalSheet.switchRow`'s javadoc:

> *"Android and Material both put the label on the leading side and the control
> on the trailing one, and users' hands already know that: the right edge is
> where you reach for a toggle, and a leading switch reads as a second label."*

`switchRow` implements it exactly that way — the text takes a weight-1 column
first, the control is added second with `OPT_INNER_GAP_DP` (12dp) as its leading
margin. The playlist row copies that: **label (weight 1) → `✎` → radio**.

- **The `✎` sits between the label and the control**, per the sketch, and is a
  **separate live 38dp target** that consumes its own tap. Configure and
  activate never share a hit area — the rule the playlists screen already names.
- **The radio is a mark, not a target** (as the switch is in `switchRow`): the
  **row** is the toggle. A second live target would give one control two
  announcements.
- It is still **a radio, not a switch** (`Playlists.withActive` stands the
  others down; one is all the index can express), and still **a toggle wearing a
  radio's clothes** (tapping the active row clears the flag; zero is legal).
  Enabling steps once, so the wallpaper changes in the moment; **disabling does
  not touch the index** (`PlaylistStore.setActive` writes only `Playlists.KEY`,
  PlaylistScreen.java ~1552).

---

## 2. A header indicator, shown only while something rotates

The header is `Gallery · Newest ⇅` in the normal case. When a playlist rotates
it gains **one** indicator naming it, sitting **immediately left of the sort**:

```
Gallery                    ▶ Favourites  Newest ⇅
Gallery   ▶ Favourites, Landscapes and City…  Newest ⇅
```

**Space allocation.** The header is a flex row: `Gallery` is `flex:0 0 auto`
(intrinsic, ~60dp), the **sort keeps its intrinsic width and never shrinks**
(`flex:0 0 auto`, ~72dp), and the indicator is the **only flexible part** —
`flex:0 1 auto; min-width:0`. So the name takes whatever is left and
`text-overflow:ellipsis` truncates it; a long name cannot push the sort off or
wrap it, and the header height never changes. Measured: the short name is 89dp
wide and ends at x=361, left of the sort at x=367; the long sample
(`Favourites, Landscapes and City Breaks of 2026`) has `scrollWidth 298 >
clientWidth 225`, so it ellipsises.

- **One indicator, never one per playlist** — only one can rotate.
- **Shown only when rotating.** Idle, the header is unchanged.
- **The `▶` is the same 13px as the label**, not a smaller stray glyph, on the
  same baseline — it reads as a deliberate mark.
- **The whole `▶ name` group is one tappable target** (not just the name, not
  just the 12px glyph), and it carries **`min-height:48dp`** so the hit area is
  generous whatever the name's length.
- **Tapping scrolls to the active row, and nothing opens.** It finds
  `.plrow.on` and `scrollIntoView({block:'center'})`s it — so the *active*
  playlist is brought into view, not merely the section — then flashes a brief
  accent outline. **The editor is never shown and no row is selected** (“don't
  need to open it”). The prototype had to make `.cells` the scroll container
  (`flex:1; min-height:0`) for the scroll to have anywhere to go; the app's
  equivalent is a `smoothScrollTo` on the gallery's `ScrollView`.

---

## 3 & 4. The add affordances go first

- **`Add media` is the first cell in the grid**, before every photo.
- **`New playlist` is the first item in the list**, before every playlist row.

Both are **fixed leading affordances, not list members**. The add cell is not a
photograph and `New playlist` is not a playlist, so neither is sorted: they sit
outside `sorted()` / the playlist order and the sort still means what it always
did — `Newest` orders **photos**, `Recent`/`Name` likewise, and the `＋` never
moves. Hoisting them changes render order only: no index write, no stored value,
no comparator.

(Code touchpoint: the grid's add cell is today built at `rows.size()` as a cell
in the flow; this moves it to index 0 with the photographs starting at 1. The
empty state — no photos — keeps its own `Add photo` button inside the empty box,
so the leading `＋` is the populated case only.)

---

## 5. Archive above playlists

The scroll order is now:

```
photos  →  archived disclosure  →  playlists section
```

The archive is the **quiet conditional row**; playlists is the **always-present
section**, below it. It reads as a divider: the small "there are more photos this
way" line, then the section that configures what shows.

This **supersedes** `docs/ARCHIVE-DESIGN.md`'s "at the end of the scroll / closing
quiet row", which was the older decision. That doc's two ordering lines have been
updated to say the archive sits below the photos and **above the playlists
section**; nothing else in it changed.

---

## 6. Zero playlists: the sentence is gone

The section stays at zero — hiding it would mean you cannot create the first
playlist — but it is now **the caption and the `New playlist` row, nothing
else**. The line *"No playlists yet — New makes one"* is deleted: the row
already says what to do, and the sentence was explaining the row to the user who
is looking at it.

---

## 7. The collage — cheap because the component exists

`PhotoTile` already draws the strip tile, the grid cell, the playlist swatch and
(now) the editor row. A collage is **that class at 18dp in a 2×2 block**
(40dp overall) — four tiles on the row's leading edge. No new renderer, no new
decode path.

- **Four photos:** 2×2, four tiles.
- **More than four:** the first **three** tiles and a fourth cell showing
  **`+N`**, where `N = count − 3`. Seven reads `+4`. Three shown plus N more is
  the only reading that does not overstate.
- **One photo:** the block **collapses to a single 40dp tile** rather than three
  dead squares.
- **Zero photos** (a just-created playlist): one empty tile in the tile fill —
  the row keeps its leading mark and nothing reads as broken.
- **Off the UI thread:** each tile calls `PhotoTile.show(item)`, which goes
  through `Io.decodePreview` / `decodeResourcePreview` on `Io`'s executor and
  reuses `PairCache` — the same path the strip uses. A photo already decoded for
  the grid or strip is a **cache hit**; a never-seen one is decoded once at 18dp.

**Cost, honestly:** four small decodes per row, and this app has **no view
recycling**, so five playlists is up to twenty decodes when the section first
builds. That is bounded and mostly cache hits, and it is the reason the tiles are
18dp. **If it ever shows, the fallback is a collage on the active playlist only
and a single `PhotoTile` on the rest** — same component, one decode instead of
four.

---

## 8. Media preview in the editor

Each editor photo row now leads with a **34dp `PhotoTile`** (the swatch size),
then the name, then reorder and remove. Same component, same decode path, so a
photograph still has exactly one visual wherever it is drawn. Bare filenames are
no longer the only mark.

---

## "Show more" — the honest position

**No paging is drawn, and none is needed now.**

- The gallery grid **already scrolls**; a playlist section at its foot is reached
  by that one scroll. A second paging mechanism would duplicate it.
- The playlist list is **short by nature** — a person keeps a handful.

**What actually breaks first is not list length, it is decode work.** This app
has no `RecyclerView` and no view recycling (`Gallery` builds rows by hand), so
the marginal cost of a long list is one decode per thumbnail. The collage
multiplies that by four per row; the editor by one per photo. So the pressure is
`rows × tiles`, not `rows`.

**Threshold to revisit:** if the *playlist* rows routinely number in the dozens —
which nothing in the model encourages — or if the collage's four decodes per row
are visibly slow on a cold gallery, the first fix is **a small thumbnail cache /
recycling pass**, not paging. Paging would only be justified if a single list
grew past a full screen of rows *and* the cost were arriving in a burst; that is
not the shape this feature has.

---

## Strings

Reused: `playlists_row_summary` / `_shuffled`, `playlists_row_rotating`,
`playlists_chip_rotating`, `playlist_new`, `playlists_talk`,
`playlists_state_rotating` / `_idle`.

```xml
<!-- the section and its always-first New row -->
<string name="gallery_playlists_caption">Playlists</string>
<string name="gallery_playlist_new_row">New playlist</string>
<!-- the row's two targets, each spoken apart -->
<string name="gallery_playlist_edit">Edit %1$s</string>
<string name="gallery_playlist_rotate">Rotate %1$s</string>
<string name="gallery_playlist_stop">Stop rotating %1$s</string>
<!-- the header indicator: the glyph is decoration, the name is the content -->
<string name="gallery_rotating_indicator">%1$s</string>
<string name="gallery_rotating_indicator_talk">Rotating: %1$s</string>
<!-- the collage's overflow cell and its spoken form -->
<string name="gallery_playlist_more">+%1$d</string>
<string name="gallery_playlist_collage_talk">Playlist artwork, %1$d photos</string>
```

The editor, the picker and the status lines are unchanged, so they need nothing
new. `playlists_none` (the zero sentence) is **retired** from the gallery
section; it may still be used by the playlists screen's own list, which is not
in scope here.

---

## What would pin it

`:app`, mostly.

- The row's children order is label → `✎` → control, and the radio is not a tap
  target while the `✎` is.
- The header indicator renders **iff** `Playlists.active() != null`, names that
  playlist, sits left of the sort, ellipsises rather than displacing the sort,
  and on tap scrolls the **active row** into view and **opens nothing**.
- The grid's first cell is the add tile whenever there is at least one photo,
  and the sort comparators are untouched by it.
- `New playlist` precedes every playlist row and is not part of the order.
- The archive block precedes the playlists block in the scroll.
- Zero playlists renders the `New playlist` row and **no explanatory sentence**.
- The collage is 1 tile at ≤1 photo, 4 tiles (last `+N`) above 4, and decodes
  through `Io`, never on the UI thread. The editor rows carry `PhotoTile`s.

---

## What I would most like overruled

1. **Enabling steps once** (the wallpaper changes on a radio tap). Matches the
   editor's `Use`; drop the step to make enabling pure arming.
2. **The collage on every row.** The fallback — active playlist only, one tile
   elsewhere — is named and cheaper; every row is the better look if the decode
   work is acceptable.
3. **The header indicator being tappable.** A plain indicator is simpler;
   tappable was chosen so it has somewhere to go. It scrolls the active row into
   view and deliberately opens nothing.
4. **Archive above playlists.** Both orders are defensible; the archive doc was
   older and I let this pass supersede it.
5. **The editor's `Use` / `Stop` action** is now a second route to the same flag;
   I kept it as the smaller change.
