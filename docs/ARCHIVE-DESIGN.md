# Archive — design for review

Status: proposal, nothing implemented. This document and the wireframe at
`.design/prototype/archive.html` are the whole of it; no app code is changed.

Settled over the life of this file: **archiving the active photo keeps the
wallpaper**; **archive is not a header button**; **archive is a filter over the
gallery, not a second screen**; and — the final pass — **the archive is one
quiet, secondary disclosure, and the empty grid does not know or care why it is
empty.**

Three ideas from earlier drafts are deliberately gone, not softened:
**"All photos are archived" as a state**, **a promoted/accent route in the empty
state**, and **any special-casing of the archived-active photo**. They are
deleted rather than reasoned about further.

---

## 1. The decision

**Archive hides a photo from the gallery without deleting it.** It is a
**boolean `archived` on `LibraryItem`**: one bit, no file move, no second list,
no version bump. It is also **a filter over the same list**, not a place you
navigate to.

### Why a filter, argued from the model

Playlists is a sheet because a playlist is *a thing you configure* — its own
blob, trigger, surfaces, active flag. Archive has none of that: one boolean per
row, and the gallery is one list. A screen for that predicate buys a second
route, a second empty state, a second rendering of the rows, and a per-row
Restore that duplicates a context-menu verb. A disclosure in the grid deletes
all of it and keeps the context menu and multi-select literal, because the rows
are the real cells.

### The active photo: archiving it KEEPS the wallpaper

Unchanged. `Library.withArchived(id, true)` **does not touch `activeId`**. The
engine keeps resolving the same id and drawing the same relief; the row is
simply not in the live list. **"The active row is archived" is a legal state.**
`Library.of()` is unchanged and requires only that the active id resolve to an
item in `items`; an archived row is still there. No self-heal — self-healing
here would be doing the delete the user chose not to do.

**No marker is added for this case.** An archived active photo is just a photo
that is on the wallpaper and not in the live grid; the sheet names it as it
names any photo, and the gallery stays quiet. (Earlier drafts appended
` · archived` to the photo line; that is deleted as a special case.)

### Every place that assumed "active ⇒ listed"

All `:app` listing code; the `:core` model tolerates the state.

1. **`Library.of()`** — asks only whether the active id is in `items`; an
   archived row is. No change, no self-heal.
2. **`Gallery.buildStrip()`** — `sortedLive()`. An archived active has no tile;
   the strip shows what you can select, and you cannot select it.
3. **`Gallery.buildGrid()`** — live section from `sortedLive()`; the archived
   section from `sortedArchived()` only when the disclosure is open.
4. **`Gallery.showCopySourcePicker()` / playlist picker** — live rows only.
5. **`LibraryStore.rows()`** — live rows; a second accessor for archived rows.
6. **`Gallery.select()` / `moveStripTile` / `drawStripRing`** — selectable ids
   are always live, so no change.
7. **The grid's disclosure** — UI state `showArchived` on `Gallery`, not
   persisted, cleared with `picked` by `dismissGrid()`.
8. **`MainActivity.refreshPhotoLine()`** — unchanged. It names the active photo
   and its surface, archived or not; there is no archived variant.
9. **The render path** — `ReliefRenderer`, `DepthWallpaperService`, the sliders,
   `FramingRows`, `DepthGenDialog` keep resolving and editing the active by id.
   Not a bug; the feature.
10. **`Playlists.livingIds`** — skips archived ids, so rotation never *selects*
    one; the archived active can still be current, and its playlist membership
    stays real.
11. **`Library.retainExisting`** — keeps archived rows; a row whose file is gone
    is dropped as always, and `of()` clears an active that dropped.
12. **`withArchived`** — must carry `appliedSurfaces` through the four-argument
    `of`, or each archive silently forgets a lock-screen apply.

### The contrast with delete

| action | what the wallpaper does | why |
|---|---|---|
| **Archive** the active photo | **keeps showing it** | the row is hidden, not destroyed; the selection is untouched |
| **Delete** the active photo | **falls back to the marble bust** | the row is gone, so the pointer must clear — `Library.remove` still nulls `activeId` in the same write |

### What archive is *not*

- **Not a file move, a folder, or a playlist.** All are a second container for a
  photo; the index is the one owner.
- **Not a sort.** A sort is read order; archive is membership.
- **Not a screen, a mode, or a hero.** It is one quiet row.

---

## 2. The model

No code here — the shape the implementation takes.

### `LibraryItem`

- New private field `archived` (boolean, default false); `withArchived(boolean)`
  through `copy()`; `isArchived()`.
- `fromJson`: `o.optBoolean("archived", false)`; `toJson` writes the key only
  when true (absent means live).

### `Library`

- **`of(...)` unchanged.** No active-archived self-heal.
- `withArchived(String id, boolean on)` and the `Set` bulk form — set the flag
  and nothing else; **do not touch `activeId`**; carry `appliedSurfaces`.
- `sortedLive()` / `sortedArchived()` — the existing comparators over the two
  filtered lists.
- `liveCount()` / `archivedCount()`.
- `size()`, `isEmpty()`, `find()`, `contains()` stay over **all** rows, so the
  seed's "is the library empty" test and validation still see archived ids.

### `Gallery` (app)

- New field `showArchived` (boolean), not persisted, cleared with `picked` in
  `dismissGrid()`.
- The disclosure and the archived section render **only when
  `archivedCount() > 0`**. The open state is **derived**:
  `open = showArchived && archivedCount() > 0`. An archive emptied while the
  disclosure is open simply stops producing rows, and the row follows them out.
- `buildGrid()` renders, in order: the live cells, the add tile **or** the empty
  box, the quiet disclosure (when archived > 0), and the muted archived cells
  (when open). **There is no footer.**
- `showRowMenu()` — **Archive** on a live row, **Restore** on an archived one;
  scoped verbs for a mixed pick. It is now the **only** action surface, and its
  Paste row is disabled with its reason when nothing is picked.

### The one empty state

The grid shows the empty box when there are **no live rows**, and the box is the
same whatever happened to the photos: **message `No photos`, `Add photo`**. It
does not ask whether the archive is empty, does not mention archive, and changes
nothing about itself. If the archive happens to have something in it, the
**same quiet disclosure row** appears below the box — exactly as it appears
below six photos. If not, it does not appear at all. That is the only
interaction between "empty" and "archived", and it is not a variant of the empty
state.

**The box is a box, not a caption.** It is `aspect-ratio: 1 / 1`, at full grid
width — the same square shape as the grid's own cells (`aspect-ratio: 1`), which
are the reference. Three cells wide and three cells tall, it reads as *"a photo
goes here"* rather than as a line of text that happens to be empty.

### Seeding, retention, playlists

- Seeding unchanged: `withBundled` only into an empty list, and only on an
  absent blob.
- `retainExisting` keeps archived rows; the bundled exemption still covers an
  archived bundled row.
- Playlists keep an archived member and **skip it** while archived
  (`livingIds` gains `&& !index.find(id).isArchived()`), so restore brings the
  membership back. An archived member reads **"Archived · skipped while
  archived"**, never "Missing photo".
- Archive is a **`Library`-only write**: one blob, unlike delete's two.

---

## 3. The UX flow

The whole feature, stated once:

> **Live photos in the grid. A quiet `Archived · N` disclosure only when the
> archive has something in it. Expanding it shows muted rows inline. Nothing
> else.**

### The disclosure — one treatment

- **Where:** in the grid's scroll content, below the last photo and the add tile
  (or below the empty box when the grid is empty) — and **above the playlists
  section**, which is always present. The playlists design moved it up, so the
  archive is no longer the closing row; see `PLAYLISTS-EXPLORATION.md`.
- **What:** `Archived · N` with a chevron `▸`/`▾`.
- **How it looks — the same in every context:** 12&#8239;px, dimmer ink, no
  hairline rule, no accent, small padding. It is deliberately quieter than a
  photo.
- **Reachable, not prominent:** in the app the row keeps a **48&#8239;dp touch
  target**, so it is a comfortable tap at a quiet weight. It is the only route
  to archived photos, so it must never be smaller than a comfortable tap — but
  reachable is not prominent, and it is never promoted, enlarged, or tinted.
- **Shown only when `archivedCount() > 0`.** Nothing archived, no row, in a full
  grid and an empty one alike.

### Expanding it

Tapping the row shows the archived cells **inline**, in the same grid, muted (a
darker fill from the app's palette) and sorted by the current order. Their `⋯`
menu says **Restore**; Copy settings, Paste, Add to playlist, Rename and Delete
are unchanged, because an archived photo is still a real photo. Restore moves the
row back into the live grid in its sorted place.

### The filmstrip

**Live rows only, always.** Every strip tile is a photo you can put on the
wallpaper, and an archived photo is not selectable. The filter never touches it.

### Actions, and the way out

**The footer is gone.** The grid used to carry four buttons — `Copy settings`,
`Paste onto selected`, `Add to playlist`, `Done` — and every one was redundant:

- `Copy settings`, `Paste`, and `Add to playlist` are all already in the row's
  context menu (`Gallery.showRowMenu`). The footer was a second route to the same
  three actions, and a second route is a second thing to keep in step.
- `Done` calls `dismissGrid()`, which its own comment calls *"the same exit BACK
  takes"*. It was a button for a gesture the platform already provides.

So the grid is **content plus the context menu**, and **BACK is the way out**.
State the trade honestly: a gesture is less discoverable than a button, and this
removes the one visible exit. It is accepted because the alternative is four
controls drawing over the render to duplicate things that are already one tap
away, and because the platform's back affordance (gesture bar or button) is the
convention every other Android screen relies on.

**Paste needs a selection, and now says so in the menu.** With the footer gone,
the row menu is the only place that requirement can be met, so its **Paste onto
selected** item is disabled when nothing is picked and carries its reason —
*"Select photos to paste onto first"* — the same string the footer's dimmed
button used. A control that silently does nothing is the failure this app
avoids, so the reason is stated rather than implied. (Consequence: pasting acts
on a selection; to paste onto one photo, hold it first. The old single-row
`Paste onto this` is retired.)

### What the earlier drafts had, and why it is gone

- **"All photos are archived" as a state.** Deleted. The empty grid answers one
  question — *what can I tap?* — and the answer is the same however the photos
  left. One message, `No photos`.
- **A promoted / accent route in the empty state.** Deleted. The archive is a
  rare, secondary thing; it does not get a hero because the grid is empty. The
  same quiet 12&#8239;px row appears.
- **A dedicated archived-active treatment (a tab, a promoted row, a photo-line
  suffix).** Deleted. An archived active photo is simply not listed; nothing
  announces it.

### The states (three, all genuinely different screens)

| state | what the user sees |
|---|---|
| **full** | live photos, the add tile, then the quiet `Archived · N ▸` because something is archived. |
| **expanded** | the same grid with `Archived · N ▾` and the muted archived rows inline. |
| **empty** | `No photos` + `Add photo`; then the same quiet `Archived · N ▸` if the archive has anything in it. |

No state is "the empty state with different words", and no state is an
archived-active special case.

### Step by step

1. Grid, browsing. Header `Gallery · Newest ⇅` (the playlists button is gone;
   a rotating playlist adds an indicator there); the quiet `Archived · 2 ▸`
   below the photos and above the playlists section.
2. `⋯` on a live tile → Copy settings · Paste · Add to playlist · Rename ·
   **Archive** · Delete.
3. Tap **Archive**: the row leaves the live grid; toast `Archived`. The wallpaper
   does not move, even if it was the active photo.
4. `⋯` on a picked tile → **Archive N selected** (or **Restore M selected**, or
   both for a mixed pick).
5. Tap `Archived · N ▸` → `Archived · N ▾` and the muted rows inline.
6. `⋯` → **Restore** on a row → it rejoins the live grid; toast `Restored`.
7. `⋯` → **Delete** on an archived row → the existing confirm → if it was the
   archived active, the wallpaper falls back to the marble bust. **The contrast
   with step 3.**
8. Restore or delete the last archived row → the disclosure and the section
   vanish; if the grid is also empty, it is the unchanged empty box.

---

## 4. Strings

In the voice of `strings.xml`, ready to lift.

```xml
<!-- The one archive treatment. Rendered only while archivedCount() > 0. -->
<string name="gallery_archived_toggle">Archived · %1$d</string>
<string name="gallery_archived_toggle_show">Show archived photos, %1$d</string>
<string name="gallery_archived_toggle_hide">Hide archived photos, %1$d</string>

<!-- The context menu: Archive on a live row, Restore on an archived one. -->
<string name="gallery_archive">Archive</string>
<string name="gallery_restore">Restore</string>
<string name="gallery_archive_selected">Archive %1$d selected</string>
<string name="gallery_restore_selected">Restore %1$d selected</string>

<!-- Toasts. Delete reuses the app's existing delete strings. -->
<string name="gallery_archived">Archived</string>
<string name="gallery_restored">Restored</string>
<plurals name="gallery_archived_many">
    <item quantity="one">%1$d archived</item>
    <item quantity="other">%1$d archived</item>
</plurals>
<plurals name="gallery_restored_many">
    <item quantity="one">%1$d restored</item>
    <item quantity="other">%1$d restored</item>
</plurals>

<!-- Spoken state of an archived cell, drawn as a muted fill. -->
<string name="gallery_talk_archived">%1$s, archived</string>

<!-- Playlists: an archived member is not a missing one. -->
<string name="playlists_state_archived">skipped, in the archive</string>
<string name="playlists_photo_archived">Archived</string>
<string name="playlists_photo_archived_sub">skipped while archived</string>
```

Reused existing strings: `gallery_empty` ("No photos") — the one empty-state
message — `gallery_add_photo`, `gallery_delete_one`,
`gallery_delete_one_message`, `gallery_delete_selected`,
`gallery_delete_active_note`, `gallery_deleted_active`, `gallery_title`,
`gallery_playlists`, `gallery_sort`, the four `photo_line_*` forms
**unchanged** (no archived variant), and **`gallery_paste_needs_selection`**,
which moves from the footer button's description to the context menu's disabled
Paste row.

**Retired this pass:** `gallery_paste_onto_this` (pasting now acts on a
selection), and the four footer-button uses that were duplicates of menu items.
Also `gallery_all_archived` (the empty grid does not name archive),
`gallery_archived_active` (no special toast), and `photo_line_archived_suffix`.

**Deleted in earlier passes:** `gallery_archived_empty` / `_hint`; from the
modal era, `gallery_archive_title`, `gallery_archive_subtitle`,
`gallery_archive_caption`, the modal row sub-lines, `gallery_archive_restore`,
`gallery_archive_restored`, `gallery_archive_delete`,
`gallery_archive_delete_message`, `gallery_archive_close`,
`gallery_archive_row`, `gallery_archive_talk`, `gallery_archive_restore_talk`,
`gallery_empty_archived`, and the old "restore it from Archive" toasts.

---

## 5. What would pin it

For when this is built. Not part of the design task.

- `PerItemSettingsTest`: a single-field archive edit resets no render value; a
  v3 item with no `archived` key reads live; round trips preserve the flag and
  omit it when false.
- `LibraryTest`: **archiving the active item leaves `activeId` unchanged**;
  `of()` does **not** clear an active-archived selection; `withArchived` carries
  `appliedSurfaces`; unknown id is a no-op; bulk archive is one write; the empty
  seed is not re-run for an archive-only library; `retainExisting` keeps an
  archived row; **deleting the archived active row clears the selection** (the
  contrast).
- `PlaylistTest`: an archived member survives a round trip and rotation steps
  over it; unarchiving restores membership; delete removes it.
- A sort test: `sortedLive()` and `sortedArchived()` partition `sorted()`.
- **`:app`**: the disclosure renders iff `archivedCount() > 0`, in a full grid
  and an empty one alike and with the same weight; the open state is derived and
  closes when the count reaches zero; `showArchived` is false after
  `dismissGrid()`; the empty state reads `gallery_empty` and does not vary with
  the archive; the context menu labels match the row state, and its Paste item is
  disabled with the selection reason when nothing is picked; **no footer is
  built** (there is no view for the four retired buttons).

---

## 6. The choices I would most like overruled

1. **BACK as the only way out.** A gesture is less discoverable than the `Done`
   button it replaces, and the grid is a full-screen takeover. I accepted the
   removal because `Done` was literally the same call as BACK, but if a visible
   exit is wanted, one small button is the cost.
2. **Pasting now needs a selection** (`Paste onto this` retired), so a
   single-photo paste is hold-then-paste. The alternative is to keep the
   single-row paste in the menu and accept that the selection requirement is
   only stated by the label. Easy to restore `gallery_paste_onto_this`.
3. **`No photos` as the single empty-state message.** Chosen, and it is what the
   app already says; if a different one sentence is preferred it is one string —
   but there is still exactly one.
4. **The empty region at `aspect-ratio: 1 / 1` full width.** It matches the
   cells' square shape, but a full-width square is a large empty area; a
   smaller inset square, or `4 / 3`, would leave more of the screen for the
   header. One token.
5. **Restoring needs the disclosure open to act on an archived row** — the only
   route is the quiet row or the context menu on a hidden row. The deliberate
   price of not promoting the archive.
6. **The section, not an interleave**, and **playlists keep an archived member
   and skip it** — both carried over and cheap to change.
