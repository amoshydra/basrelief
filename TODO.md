# Basrelief — open items

Written to survive context compaction. Update it when an item closes; it is the list a
future session reads instead of re-deriving decisions.

## Decisions waiting on the user

**Storage / backup (deferred by the user, but the finding stands).** Auto Backup has a
**25 MB per-app quota, and when it is exceeded the system backs up *nothing*** — not the
index, not the settings — silently. A handful of 2560px PNGs already exceeds it, so the app
may currently be backing up an empty set while looking configured. Recommendation:
**exclude the bulk (a photos/videos subdirectory) from Auto Backup** so the index and
settings always survive; add a **user-chosen SAF folder** with "Back up now" / "Restore" as
the real, visible backup; keep video in a non-backed-up directory. **Do not make SAF the
primary store** — `AppState.load` runs on the GL thread at cold start and must complete
synchronously, and a binder (possibly cloud) round trip there makes "folder unavailable"
indistinguishable from "photo deleted".

**Question answered — mirror, not external-as-truth.** Internal `filesDir` stays the single
source of truth; the SAF folder is a write-through copy with an explicit "Back up now" /
"Restore". In failure order: the wallpaper must render at cold start on the GL thread, and
SAF is a binder call to a folder Android may have revoked (user removed it, SD unmounted,
cloud provider offline); with internal as truth those events only pause the mirror, with
external as truth **every render path needs an "unavailable" state** and a missing folder
becomes a blank wallpaper. **Do not ask on first launch** — the app is fully functional
without it, and a permission wall for a feature that can be set up later is hostile; ask when
the user chooses "Set up backup folder". Revoking can never wipe anything because nothing
lives there; re-map by choosing again and the mirror re-writes. If the real goal is "the
folder IS the library" (sync through the user's own NAS), external-as-truth plus an internal
cache is possible but pays the availability complexity above — confirm before building it.

**Reversal rule at the bottom.** Today the release decision is projection-based, so a
*decisive* upward reversal from 98% stays open but a slow creep still collapses. If the user
wants "past ~90% down, any upward movement means stay", that is a direction-change rule on
top of the projection — about five lines, and `SheetIntentTest` already pins the current
boundary.

**Pill tap after a collapsed relaunch** opens fully rather than to the last expanded
fraction. The stored `sheet_pos` *is* the collapsed state; the last expanded fraction would
need storing separately.

## Device-only verification (cannot be done off the phone)

- **`on_unlock`**: Playlists → New → 2+ photos → trigger On unlock → surfaces Home → Rotate
  on → Done → lock → unlock. Expect the wallpaper to advance and the photo line to read
  `playlist: <name> · n+1 of m`. `adb -s <device-serial> logcat -s Basrelief` distinguishes "the
  broadcast never arrived" from "arrived and nothing was due".
- **Spring feel** (settled, ≲1% overshoot) and the **fling branches**. Synthetic swipes
  report a bogus negative velocity and `screencap` forces the final frame, so neither can be
  verified from this side.
- **The photo cross-fade on the home screen** — the engine uses the same renderer, but the
  wallpaper path itself was never exercised.
- **The light rake and the seam**: pull Elevation to 0 — grazing light is the whole point of
  the feature and cannot be judged from here (`screencap` forces the frame under test). And
  decide whether azimuth **clamping** at 0/359 is acceptable or the slider must wrap.
- **Per-surface Lock settings** on API 35+; the static lock frame on a sub-35 device (moot on
  the current phones).
- IME over the depth field, RTL, TalkBack.

## Ideation backlog, ranked

1. ~~**Light UI**~~ **Shipped** (`ed1726e`) — Azimuth/Elevation sliders in a `Light` group;
   bounds derived from `LightDirection`; both angles in one `setLight`; the untouched angle
   carried as a float so the preview is exact. Remaining: **presets**, which are also the
   cheap way across the 0/359 seam (the slider clamps, it does not wrap), and the user's
   judgement that a low-elevation rake actually reads as raking light.
2. **Auto-depth on import + camera capture** — depth is the moat, and today it needs a panel
   and a button.
3. **Portable packed PNG (export/import) + a static web viewer** — the only distribution the
   platform permits, since no app can rebind its own wallpaper.
4. **Depth polarity invert per photo** — one tap for inside-out maps from monocular models.
5. **Diorama** — subject over a treated background.
6. **ComfyUI workflow templates** — the run machine is already generic over an image output.
7. **On-device depth** — the strategic one: relief without a GPU server. Breaks the
   no-Maven-artifact rule; 3–6 weeks.

## Video + depth — research done, plan agreed

- **Formats:** Android's DDF is a **still-image** spec (API 29, write-side only); MP4-AT is
  the real video container and **no platform code reads it** (AOSP `MPEG4Extractor`: zero
  references; Media3 1.6+ only, i.e. a dependency); Apple spatial video carries **no depth**;
  APV is the wrong tool. So define our own: **colour video + a separate grayscale depth
  video**, both ordinary files `MediaExtractor` reads.
- **Depth source:** generate it **offline at import** on the ComfyUI server. Temporal flicker
  is the trap.
- **The user requires DEPTH PER FRAME** (2026-10-03), not one map per clip — the 2.5D
  "cheap honest mode" is **rejected**. What that commits us to: (a) a per-frame depth pass
  with **temporal smoothing** in the offline step, or the wallpaper strobes; (b) playback
  decodes colour and depth **in lockstep**; (c) per-frame displacement cannot be a CPU mesh
  rebuild (`ReliefMesh.build` measured 6.6 ms at grid 256 on the host — no chance at 30 Hz
  on a phone), so the relief moves into the **fragment shader** (relief mapping over a depth
  texture) — a real renderer change; (d) two decoders run whenever the item is visible, so it
  is opt-in per item and battery is **not** ≈ today; (e) spike the fragment-shader relief
  over two synchronized videos on the OnePlus before promising the feature.
- **Battery:** a video wallpaper **can never sleep while visible** (the still path's real win
  is that it does). ~30–80 mA for the decoder alone. Phases: **0** stills-from-video;
  **1** studio-only; **2** pre-decoded "living photo" loop (recommended — battery ≈ today);
  **3** real-time, opt-in per item, default **off**.
- **Precompute at import so runtime is upload-and-draw:** downscale; depth; temporal smooth;
  **blur in float** (the banding invariant — an 8-bit re-quantised blur brings the contours
  back); resample to the grid; normals; poster frame; loop frames.
- Live displacement would need **GLES3** (depth in the vertex shader; GLES2's vertex texture
  unit minimum is zero), and `ReliefMesh.build` measured 6.6 ms at grid 256 on the host —
  impossible at 30 Hz on a phone.

## Known drift / deliberate deviations

- **`MainActivity` still rebuilds the whole film strip on two paths** — a re-share that
  selects an existing photo, and `onResume` (which calls `gallery.reindex()`
  unconditionally). The Gallery-side fix (`b426298`) made a *selection* re-parent one tile;
  these two call the rebuild path directly, so every return to the studio still re-decodes
  each thumbnail. Fix shape: reindex only when the index actually changed.

- The studio sheet's Home·Lock segment **scrolls**; the mockup pins it outside the scroll.
  The user chose scrolling.
- The gallery draws `Tap a photo to use it · hold to pick several`, which no mockup state
  draws. The user chose to keep it and make selection obvious instead.
- **The studio sheet never fades at all** — its contents stay fully legible until they have
  been dragged off the bottom edge; the earlier content-only fade was still a fade, and an
  extreme drag is not a reason for the panel to look disabled. A **dialog's content** still
  dissolves (per-child alpha) and its scrim reveals as the card descends, because a modal
  that is leaving is gone, not parked.
- `check-parity` enforces two architectural invariants: **no `new Dialog(` outside
  `ModalSheet`**, and **no `plainRow`** (rows come from the three role factories).

## Playlists — why the user could not use one (diagnosed 2026-10-03)

Read from the device, not guessed: the playlist the user built is stored with
`"active":false`. A playlist with rotation off is deliberately inert — a saved list — and
nothing on the screen said so where they were looking. The editor's one essential control,
`Rotate through this list`, is a row among rows, and `PlaylistScreen` **deliberately has no
"rotate now"** (its own javadoc, line 47). So a person can name a playlist, give it two
photos, a trigger and a surface, and it will never move, with the only tell a small state
word on the list row.

**Required fix, in the user's own words (how a created playlist is used):**
- Activation must be an explicit action, not a toggle buried mid-screen: a **`Use this
  playlist`** row in the editor that turns rotation on and **steps once immediately**, so
  the effect is visible in the same moment.
- A **`Next photo`** action, so `Daily`/`Hourly` can be demonstrated without waiting for a
  wake-up.
- The list row leads with **`Not rotating — tap to use`** / **`Rotating on Home`**, not a
  corner dot.
- Only ONE playlist rotates per surface; the editor must say which one does.
- NO special logic for the bundled item; only Settings' Reset restores it — the user
  restated this rule and it governs the map section's refusal guards too.

## Playlists — make "is this running?" unmissable (2026-10-03)

**The diagnosis, in one line:** a playlist has no visible state, so a saved list and a running
list look identical, and an inert feature reads as a broken one. The switch being "hard to
find" was the smaller half of the bug.

**One signal, three places, derived from ONE function.** All of it must read
`Playlists.rotating(surface)` — never a recomputation, or the three places will disagree and
one of them will lie.

1. **`:core` first: a `PlaylistStatus` derivation.** What is rotating, on which surfaces, and
   when it next changes (`last_advanced_at` + the trigger's interval → "in 22 h", or "due
   now"). Pure, tested, its own test class — `PlaylistTest` already pins that rotation writes
   the index's own selection by the same two calls a tap makes, and **the blob's allowed keys
   are enumerated by a test, so the status must be DERIVED AT RENDER TIME and never stored.**
2. **The list row is the control.** Rotating: accent fill, a filled `Rotating` chip, and the
   second line reads `Rotating on Home · next: in 22 h`. Not rotating: `Not rotating` and a
   dedicated ≥48dp **Use** button at the row's end. Tapping Use turns it on **and advances
   once immediately**, so the effect is visible in the same moment.
3. **The editor gets a sticky primary action**, full width, in the thumb zone above the grip:
   `Use this playlist` when off, `Stop rotating` when on, with the live status line beside it.
   The Rotation group's switch stays as configuration; the button is the *action*.
4. **Ambient proof outside the playlist screens**: the gallery header shows a count when one
   is rotating, and the sheet's photo line names the playlist that owns the current photo
   (`playlist: name · 2 of 3`), so the state is visible from the studio, not only from the
   screen that set it.
5. **Standing down is visible.** Only one playlist rotates per surface; using one must
   visibly stand another down on that same surface, because a silently ignored list is
   exactly the confusion being fixed.

## Playlist editor — use the app's own controls (2026-10-03)

The user's three complaints, and the diagnosis under them: **`PlaylistScreen.markRow` is a fourth
row role that is none of the three `ModalSheet` defines**, so a playlist editor on the same card
reads as a different app. A checkbox wearing a costume is why nothing matches.

**The vocabulary already exists** — `ModalSheet`'s three roles, with their semantics written
down: `switchRow` (a boolean you toggle, trailing platform `Switch`, the row owns the tap and
the switch is a **mark, not a second tap target**), `actionRow` (something you press),
`readingRow` (something you read).

| control | what it is | role |
|---|---|---|
| Rotate through this list | boolean | `switchRow` |
| Shuffle | boolean | `switchRow` |
| Surfaces (Home, Lock) | one-or-more, two available, no third | **`switchRow` per surface** |
| Trigger (Daily / Hourly / On unlock / On pickup) | exactly one of four | **radio group (new)** |
| Delete | destructive | **not a row** |

**Surfaces are two switches, not checkboxes**, and this is the judgement call: with only two and
no possibility of a third, checkboxes imply a set that can grow. Two switches say what
`Playlist.surfaces` stores — these two screens, independently on or off — and it is the same
gesture the studio sheet's per-surface Home·Lock segment already means. Consistency with the
app's own existing gesture beats taxonomy.

**The one new component: a radio row**, built from `switchRow`'s bones — same `frameRow`
plate, same type scale, trailing mark instead of a trailing `Switch`, same `OPT_GAP_DP`. It
looks like its siblings because it *is* its siblings with a different trailing widget, the
same trade the platform `Switch` already makes. Under a caption, faintly indented, selected
row carrying a faint accent wash, so four rows read as ONE choice rather than four switches.
The mockup already draws rotation this way (`el('div','caption','Rotate on')`), and the
grouped caption is the mockup's, not a new invention.

**THE TRAP:** trigger is single-choice and `switchRow` has no notion of exclusivity, so tapping
one trigger must untap the others — the group owns `singleChoice()`, not the rows. Get it
wrong and two triggers can both look chosen. With surfaces as switches, a screen where a
boolean group silently behaves like a radio group is the specific bug to check for.

**Delete leaves the footer.** Today it is a peer of Done in the same `footerRow` — the most
reachable, highest-contrast position on the card, one pixel from the button that dismisses.
Two actions with opposite consequences must not share a row. Delete goes **last in the scroll**,
below a hairline, in its own quiet band, quiet styling, and **only when it can act** (a
one-photo playlist is not worth deleting, so the row should not exist). The `ModalSheet`
confirm stays — that is a correct second gate.

**Order:** Photos → Rotation (Rotate, Shuffle) → Surfaces → When (trigger) → status line →
Delete at the bottom. Configuration first, consequence at the end.

**The test of consistency:** the playlist screen must be recognisable as the settings sheet by
its *chrome alone* — same card, same grip, same row plates, same type scale, same Done in the
same place, same scrim — with only the content differing. That test would have caught all three
of the user's complaints by inspection, so it is the acceptance criterion, not the controls.

## The bundled item is ordinary, so give it a file (2026-10-03)

**The user, restated:** the bundled demo is an ordinary preloaded photo — **no special logic,
no special-casing anywhere** — and the *only* special thing is that Settings' Reset brings it
back.

**A subagent audited the three depth-path refusal guards and recommended keeping all three**,
because the bundled item has no image file. That audit is correct and the conclusion is wrong,
because it accepts the premise. `LibraryItem.bundled()` stores **`imageName` and `depthName`
both null**, and null image name is the renderer's signal to read `res/raw` — so there is no
file to upload, no file to write a regenerated map to, and no file to replace. **The special
logic is not "the marble is a demo"; it is "the marble has no file."**

**So: make it file-backed.** On seed, copy `res/raw/image.png` and `res/raw/depth.png` into
`filesDir` under ordinary filenames and store them in the item like any other photo. Then:

- the renderer needs **no null branch** — every item is a file, and `null` becomes a
  read-nothing path instead of a read-`res/raw` path;
- all three refusal guards **lose their reason** and go: there is a real image to send to the
  depth panel and a real file to write a regenerated or replaced map to;
- the item is editable, renameable, depth-regenerable, and exportable **by the same code path
  as every other photo**, which is the entire meaning of "ordinary";
- deleting still works, because deletion is the index row plus its files — no different from
  any photo, and Reset re-seeds by copying again.

**What must survive:** Reset (and only Reset) brings it back, and a delete **sticks** — which
the empty-only seed already guarantees, since seeding runs only when the index blob is
**absent**. That invariant is unchanged by this work and must not be traded away for
convenience. `res/raw/image.png` + `res/raw/depth.png` stay as the *source* the seed copies
from, and the "both are loaded together" invariant stands — the seed writes both or neither.

**Migration:** an existing install already has a stored index with the null-name row. On load,
materialise that row into files once, or the user is left with the one special-cased item the
work was meant to eliminate. Do it as one derivation over the stored index, not a special case
at the render site.

## Playlist naming was the bug, and it hid a status that matters (2026-10-03)

**The user's words:** *'Rotate through this list' turned out to be the activation button rather than a rotation mode, and the
    result was judged a failure of visual design*

**The reading was correct and the design caused it.** `Rotate through this list` sat **directly above
`Shuffle`, in the same group** — my own brief said "Rotation (Rotate, Shuffle)". Two controls
whose words both talk about *how the list is walked*, side by side, read as two modes. **A label
describing a mechanism is not a label describing a state.** Fix: name the activation for what it
does to the *wallpaper*, and separate it from anything about picking order.

| group | control | widget |
|---|---|---|
| — | **Use this playlist** (states what it does to the wallpaper) | primary action |
| How it picks | In order / Shuffle | **radio** — one of two, exclusive |
| Where it shows | Home / Lock | switch each — independent |
| When it changes | Daily / Hourly / On unlock / On pickup | **radio** — one of four |

**CORRECTION to the earlier brief: shuffle is NOT a switch.** Order and shuffle are mutually
exclusive, which is precisely what a radio *is* — so it is the same single-choice group as
trigger, and two switches in a screen where they should be two radio groups is the inconsistency
the user is objecting to. Two radio groups, two switches, one primary action.

**Two radio groups means exclusivity must be owned by the GROUP** (`RadioGroup.setOnCheckedChange`),
never per row. Two groups = two instances, and the trap doubles: a row wired to the wrong group,
or both groups sharing one, silently breaks the selection.

## The three states the app only renders one of

The user reported: playlist armed, yet gallery and film strip show an **active item that is not
in the playlist**, and nothing explains it. That is **correct behaviour with a UI that lies by
omission.** A playlist rotates the *selection* — the same pointer a tap writes, one pointer, no
second cursor — so a manual tap to a non-member is legal, the playlist keeps its schedule, and
it takes the screen back at the next trigger.

`photo_line_playlist` fires **only when the current photo is a member**, so tapping a non-member
**silently drops the playlist mention** and the screen reads "no playlist" while one is armed and
will overwrite what is on screen. Three states exist; one is rendered:

| reality | what must be shown |
|---|---|
| nothing armed | nothing |
| armed, current photo is a member | `playlist: <name> · 2 of 3` |
| **armed, current photo is NOT a member** | **`<name> is armed — it takes the screen back on unlock`** |

**Do not move the active ring.** The ring means "what is on screen", because the pointer is the
truth and moving it would make it lie about something else. The fix is showing the missing fact
beside it. Same for the gallery: the header count alone ("1 playlist rotating") is too weak when
the armed playlist does not own the current photo.

**Rejected alternative:** a manual pick *suspending* the playlist. It adds a second piece of
state to a model whose whole point is one pointer that cannot disagree with itself. Better to
say plainly when the playlist takes the screen back.

## Dialog backdrop: darker, and real blur where the platform allows

- `ModalSheet` (a real window): **`Window.setBackgroundBlurRadius`**, API 31+, gated on
  `WindowManager.isCrossWindowBlurSupported()`, with a **darker scrim** behind the card. Radius
  tuned so text behind it stays unreadable-blurred rather than legible; measure, do not guess.
- **The studio sheet cannot have blur.** It is a view over the `GLSurfaceView` in the same window,
  and a window cannot be blurred from inside itself without a screenshot — which `AGENTS.md`
  already forbids for appearance. Darker scrim only there, and say so rather than shipping a
  promise the platform cannot keep on `minSdk 26`.

## Backdrop: darker still below API 31, and every modal scrim-tappable (2026-10-03)

### Sub-API-31 needs a much darker scrim, not the same one
A blur removes competing detail *and* lowers apparent brightness. Without it the same scrim
alpha reads as a lighter, flatter wash, so **the fallback must be darker, not equal** — the
user asked for "much darker" specifically for below 31. Two constants, one derivation: keep
`SCRIM` as the blurred (API 31+) value and add a second, darker value used when
`isCrossWindowBlurSupported()` is false or `Build.VERSION.SDK_INT < 31`. **Both existing
scrim variants** (the default and the custom-scrim constructor, `ModalSheet.java:207`) must
darken on the fallback path, and the relationship between them must be preserved rather than
re-picked — e.g. darken each by the same measured step, or derive both from one pair of
tokens. Report the before/after hex for each on both paths.

### Every modal already IS a bottom sheet — the missing route is the scrim
`new Dialog(` exists **exactly once**, in `ModalSheet.java:318`, and `check-parity.mjs`
(invariant 1) fails the build on a second one. So the "replace all modals with dismissible
bottom sheets" request is **already satisfied by construction**; `PlaylistScreen` and
`SettingsSheet` say in their own javadoc they were migrated off `AlertDialog` for this reason.

**What is missing is scrim-tap dismissal.** `ModalSheet` is `setCancelable(true)` (`:697`)
and dismisses on BACK (`dismissOnBack`, `:361`), the grip (`:455`) and its own drag — but the
scrim is a plain `ColorDrawable` on the root (`:211-212`), so **tapping outside the card
does nothing**. That is the one dismissal gesture users reach for first.

Requirements:
- **A tap on the scrim dismisses; a tap on the card does not.** The card consumes its own
  taps, so the scrim listener must be on the root and must not fire for events the card
  consumed — check that a scroll, a slider drag and a field tap never leak to it.
- **The scrim tap must go through `dismiss()`, not `dialog.cancel()`**, so it animates like
  the grip and runs `dismissAction` (`:662`) — a caller with work to finish, e.g. the
  playlists editor, would otherwise be skipped. Same path, one dismissal.
- **A destructive confirm is a different thing.** A dialog asking "delete this?" must NOT
  dismiss on a stray scrim tap — that is the tap that should do least. Read each caller and
  decide deliberately; the safe default is that a confirm ignores scrim taps, and that
  decision must be visible in the code rather than accidental.

## Every sheet scrolls to expand, like the settings sheet (2026-10-04)

**The user, correcting a misreading:** *the settings sheet was the right model because its content's scroll expands and collapses it, and the
    other sheets were expected to behave the same way.* And: **do not touch the studio sheet** — *it was an entirely different UI that required the background to stay visible, with free positioning*

**Ruled out by measurement, so nobody re-derives it: it is not `maxHeightFraction`.** Every
screen already passes `0.88f` — Settings (`SettingsSheet.java:136`), ApplyChooser
(`ApplyChooser.java:57`), DepthGenDialog (`DepthGenDialog.java:138`) and PlaylistScreen
(`PlaylistScreen.java:110`). The cap is not the divergence.

**The divergence is that the card has a fixed ceiling and the content scrolls inside it.**
`CappedScroll.onMeasure` clamps itself to `ceiling`, set by `applyHeight()` from
`maxHeightFraction - chromeHeight()`. So a long form scrolls within a card of constant height,
and a short one shrink-wraps. **The card's height is not a function of scroll position** — which
is exactly what the studio panel does, and the thing being asked for.

**So the fix is in `CappedScroll`/`applyHeight`, and it must not regress the three things the
fixed ceiling currently buys:**
1. **Chrome stays put.** The grip and the footer must not scroll away — they are the
   dismissal handle and the Done button.
2. **A card shorter than the screen shrink-wraps** and is not stretched by the weighted child
   (see the `onMeasure` javadoc: the old version could never shrink-wrap).
3. **The hand-over still works.** Scroll to the content's end and the *same* drag moves the
   card; `reofferGestureToParent` exists precisely because `ScrollView` otherwise claims the
   gesture permanently. A growing card must not make the content reach its end early and
   then feel stuck.

**Acceptance test, on every sheet:** open it, scroll, and the card **grows with the content
up to the fraction ceiling** and shrinks back on the way down — one behaviour, not four.
Report each of the four screens measured, including a short one (shrink-wrap) and a long one
(grow to ceiling). `AGENTS.md` records the shrink-wrap regression once already; that is the
trap this lands in.

## Backdrop: blur everywhere it is supported, dim further where it is not

Applies to **every** modal sheet — settings, depth AI, playlist, apply, confirms. The user's
rule: **blur + dim when blur is supported; when it is not, dim the background further**
(much darker, not the same alpha). Already recorded above as two constants from one
derivation, both scrim variants, with `BottomSheet` (the studio sheet) explicitly **out of
scope** — its background must stay visible for free positioning.

## Scrolling modal content jitters — the height is coupled to scrollY (2026-10-04)

**The brief, in substance:** *scrolling inside the sheet made the content jitter, because the scroll was also trying to expand it. The scroll should expand the sheet only when scrolling up with nothing left to scroll, and collapse it only when scrolling down with nothing left to scroll.*

**The user has diagnosed it correctly.** `c3f8d41` made the card's height a continuous
function of scroll position — `viewportHeight = min(content, ceiling, open + scrollY)` — and
`CappedScroll.onMeasure` clamps its own measured height. So scrolling changes the viewport
height, which changes the layout, which can change the effective scroll position: a feedback
loop that oscillates. The bounds were measured and were *correct at rest*, which is why this
passed review; nothing measured the motion, and `AGENTS.md` already records that a settled
state is the one thing `screencap` cannot check.

**The rule, which is the fix:**
- **Within the content's scroll range the card's height is CONSTANT.** Scrolling is for
  reading, not for resizing.
- **Over-scroll UP at the top edge** → expand.
- **Over-scroll DOWN at the bottom edge** → collapse.
- Growth and shrink are **monotonic within a gesture and settle at a stop** (full height =
  the ceiling). A card that jitters is the whole symptom; monotonic-within-a-gesture is the
  property to test, not "ends up the right size".

So the mechanism is **edge over-scroll driving the parent**, not scroll position driving it —
the canonical nested-scroll handoff, where the parent consumes only the *unconsumed* delta
at the edges. Whether that is `NestedScrollView` plus a child-scroll callback, or a
`ScrollView` computing its own unconsumed delta at the edges, is a choice to make and
justify; what is not a choice is that plain `scrollY` must not drive the height.

**Must not regress:** shrink-wrap for content with no scroll range (the card still fits its
content and does not grow); the grip and footer never moving; the hand-over from content to
card drag; `OPEN_FRACTION`'s opening height; `SOFT_INPUT_ADJUST_RESIZE` with a keyboard up;
the 120dp floor; and the depth form's fields still being typeable.

## The studio sheet must MORPH into the pill, not swap with it (2026-10-04)

**The user:** *the pill should double as the drag handle, so that dragging the sheet down morphs it into the pill and
    dragging up morphs it back, seamlessly*

**Why it cannot be seamless today, measured in the code.** There are **two views with two
lifecycles**: `applySheetPosition` maps `showing = 1 - fraction`, so the panel's height reaches
**zero**, and a *separate* `pill` view (`MainActivity.java:842`, with its own `pillLabel`,
`pillMark`, `pillRing` and `updatePillVisibility()`) goes VISIBLE. Two state changes driven by
one gesture is a pop by construction. It also explains why the pill is not a drag handle: it is
a different view with its own touch handling, so dragging it cannot be dragging the sheet.

**The plan — one view, one axis, no second view.**

1. **The pill IS the panel at `fraction = 1`.** The height mapping becomes
   `lerp(panelNaturalHeight, PILL_HEIGHT, f)` instead of `(1-f) * natural`, so the panel never
   reaches zero and at `f = 1` it *is* a 56dp pill. Put this mapping in `:core` as a pure
   `SheetMorph.heightFor` with tests — exact endpoints, monotonic, never below `PILL_HEIGHT`.
   `:core` is where a resolution into numbers belongs, and it is the only way this is testable
   without a device.
2. **The morph region is the panel's top strip.** The pill's contents move **into the row that
   already holds the grip**. Expanded, the strip is grip + title and the scroll area takes the
   rest; collapsed, the scroll area's height is 0 and only the strip remains. So the label is
   *clipped into existence* rather than faded in — which is what keeps the standing rule that
   the studio sheet is **never faded**. Nothing fades; geometry reveals.
3. **The pill is a drag handle by construction**, because it is the same view with the same
   listener. Give the collapsed strip a ≥48dp target (56dp pill) so the whole pill drags.
4. **Corner radius interpolates** from the sheet's 22dp top corners to fully rounded. A
   drawable property, so no relayout and no transform.
5. **One continuous gesture both ways.** Expanding is the same interpolation reversed, so a
   reversal mid-drag is seamless for free — which also settles the older "reversal at 98%"
   question rather than adding a rule to it.
6. **The release policy does not change.** `SheetIntent` (studio `FREE`, commits at 0.95) still
   decides *open or pill*; only the rendering of the destination changes. `sheet_pos = 1.0`
   still means the pill, so there is no stored-state migration.
7. **Falls out for free:** store the last expanded fraction, so tapping the pill returns to
   where the user left it instead of snapping fully open — the known gap recorded earlier.

**The one decision that is not mine: how the content leaves.** Nothing may fade, so the content
below the strip must be *removed* by geometry, and there are two honest ways:
- **(a) slice** — the panel just gets shorter and the content is cut off. Cheapest, no motion
  inside the panel, reads as the sheet being squashed.
- **(b) slide out** — the strip holds still and the content below is translated down and away,
  clipped by the panel's own bounds. Smooths better; still layout + a child transform, never a
  transform of the panel itself, so it does not touch the `b/7179570` hazard — **but that is
  exactly the kind of claim this project verifies on a device rather than reasons about.**

## The morph's fade: content-only, and ONLY in the edge band (2026-10-04)

**The user:** *fade out, but only at the edge*

**Which corrects an invention of mine.** I had built the plan under an absolute "nothing may
fade", derived from the user's real instruction (the content must not fade even at the extreme bottom, `90c5553`). That instruction was about the **drag fade**: the sheet was fully
present and usable while its contents sat at 15% alpha, so a live panel looked switched off.
**A morph is the opposite case — the content is leaving.** So the rule is not "never fade", it
is "never fade a sheet that is still there", and a fade confined to the edge satisfies it.

**The rule to implement:**
- **Below the edge band: alpha is exactly 1.0 at every position, and the fade is a function of
  `f` alone — never of scrolling.** So scrolling content inside the sheet cannot fade anything,
  which is the case the user objected to.
- **Inside the last stretch of travel, content fades 1.0 → 0.0** as the sheet becomes the pill,
  and fades back in over the same band when dragged up. Same function both ways, so it is
  seamless and reversible.
- **The strip never fades.** The grip row, the pill's label, the dot and the progress ring are
  the pill; they are the only thing that survives, so their alpha stays 1 throughout. Anything
  that fades is content that is *leaving*.

**The parked case is the trap, and it is why the band must be tight.** The studio sheet is
`FREE` and can rest anywhere below 0.95, so a fade keyed to `f` alone can leave a **parked,
fully usable sheet sitting at 50% alpha** — which is exactly the "looks disabled" complaint,
reintroduced through the back door. The band is therefore the last few percent of travel, and
the band start is a constant in `:core` with a test that pins alpha == 1.0 at both 0.0 and at
the band start. If the user parks inside the band, the sheet is nearly the pill and a fading
content is correct; if they can park well inside it, the constant is wrong.

**Alpha is a paint property**, which is why this is safe where a transform would not be: it
cannot desync the `GLSurfaceView`'s transparent region (`AGENTS.md`), and it needs no relayout
per frame. The height is still set by `LayoutParams`, never by translating the panel.

## A sheet with the handle bar closes directly; it does not shrink first (2026-10-04)

**The user, asked plainly after I said it in jargon:** *close directly*

So the measured behaviour stays: **Settings, Playlists and Depth AI — the sheets with the
little drag bar at the top — close on a downward pull and never shrink in place.** The bar is
also the sheet's drag handle, so it claims that gesture before the content can over-scroll.
Only sheets *without* the bar (Apply, and the small Delete/Rename popups) shrink in place.

**Why, so it is not "improved" later:** closing is the clearer answer to *pull it away*. The
alternative — shrink, then close — inserts a step in front of a gesture that already works and
makes the user drag twice for one intent.

**Vocabulary, because I confused the user with it:** "card" is our word for a modal sheet
(`ModalSheet`'s javadoc), and "grip" is the little drag-handle bar at the top of one. Say
*sheet* and *the handle bar* with the user. They had to ask what a "card with a grip" was,
which is a fair failure and not something to repeat.
