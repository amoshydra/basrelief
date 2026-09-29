# Concept: an instrument panel, not a settings screen

## The idea in one paragraph

The studio should feel like **lighting equipment**, not like an Android settings
dialog that happens to sit on top of a picture. A basrelief is a physical thing
lit from one side; the controls are the ones a photographer or a rigger already
has in their hands — depth, throw, distance, where you're standing. So: a dark,
translucent, precise console with one warm accent, real shape language, numbers
set in tabular figures so they don't jitter as they change, and diagnostics
demoted to where an instrument would actually put them. The preview is the hero
and the console is a thin, quiet, legible layer over it.

## Why this and not the obvious alternatives

**Not a camera app.** The gesture vocabulary is right but the mental model is
wrong. A camera app hides its controls because they are secondary to the act of
shooting. Here the act *is* adjustment, and precision is the product.

**Not a dense pro-audio console.** Everything is exposed, which is a great idea
on a tablet and a bad one on a phone you are holding one-handed.

**Not a floating card.** Translucency over the preview is what makes the app
feel connected to its output. A solid card would sever that.

## Design tokens

The app currently has no theme, so these are not a refinement — they are the
first ones it will have.

### Colour

| Token | Value | Role |
|---|---|---|
| `surface` | `#0B0E14` @ 92% | the sheet |
| `surface-raised` | `#161B26` @ 96% | controls resting on the sheet |
| `surface-sunken` | `#05070B` @ 70% | tracks, wells, the diagnostics block |
| `stroke` | `#FFFFFF` @ 10% | 1dp separators and control outlines |
| `stroke-strong` | `#FFFFFF` @ 18% | focused / active outline |
| `accent` | `#F0B429` | primary action, active state, the one thing you can change |
| `accent-dim` | `#F0B429` @ 22% | ripple, selected track fill |
| `text-primary` | `#F2F4F8` | values, labels |
| `text-secondary` | `#98A2B3` | units, supporting text |
| `text-tertiary` | `#5D6675` | disabled, hints |
| `ok` | `#3DD68C` | engine healthy |
| `warn` | `#F0B429` | degraded, needs attention |
| `error` | `#F97066` | failed |

Two decisions worth defending:

**The accent is warm.** A basrelief is defined by raking light. A cool blue
accent reads as "Android"; amber reads as "lamp". It is also used sparingly —
only on the primary action and the active state — so it competes with nothing
in the photograph it sits over.

**The sheet is 92% opaque, not 100%.** You should be able to see the edge of the
photo continuing underneath the panel. It is the difference between a console
and a lid.

### Type

Five sizes, each with one job. The current app has five sizes and no jobs.

| Role | Size | Weight | Notes |
|---|---|---|---|
| Display | 28 | 300 | the live value of the selected control, tabular |
| Title | 16 | 600 | sheet title |
| Label | 14 | 500 | control names |
| Body | 13 | 400 | supporting text |
| Caption | 11 | 600 | section headers, `letterSpacing 0.10`, uppercase |
| Mono | 11 | 400 | diagnostics only, monospace |

**Tabular figures on every live value.** `intensity` jumping from `9` to `10` to
`100` shifts the layout sideways as you drag if the glyphs are proportional. In a
console this reads as jitter. `android:fontFeatureSettings="tnum"` fixes it, and
it is a one-line change to the theme.

### Space, shape, motion

- **Space:** 4dp base — `2, 4, 8, 12, 16, 20, 24, 32`.
- **Radii:** `sm 6` (nudges, chips), `md 10` (buttons, fields), `lg 16` (the
  sheet's top corners), `pill` (tracks).
- **Elevation:** surface tint only, never a drop shadow. A shadow on a
  translucent sheet over a dark photo reads as mud.
- **Motion:** expand is **instant**. It means "I want the controls back" and by
  the time a slide has finished they are there and usable; the wait is the app
  appearing to think. Collapse takes 180ms with a decelerate curve — that is the
  user finishing with the panel, and a short motion there gets in nobody's way.
  Everything else is a framework `RippleDrawable` at its default timing.
- **Haptics:** `HapticFeedbackConstants.CLOCK_TICK` on slider detents,
  `CONFIRM`/`REJECT` on collapse and expand. Framework-only, and the single
  cheapest perceived-quality win available.

## Layout

```
┌──────────────────────────────┐
│ ░░░ preview, full bleed ░░░  │
│                              │
│                     ⊕ orbit  │   ← the marker you actually aim with
│                              │
│  ╭────────────────────────╮  │   ← sheet, lg radius, 92% surface
│  │ ▬▬▬                      │  │   ← handle: 44dp, full-width grip
│  │                          │  │
│  │ PHOTO                     │  │   ← caption, which photo is loaded
│  │ Image          share…png │  │   ← title + identity
│  │ ───────────────────────  │  │   ← hairline
│  │                          │  │
│  │ Depth              54 %  │  │   ← label + tabular value, one line
│  │ ●━━━━━━━──────────────   │  │   ← track beneath
│  │                          │  │
│  │ Motion             45 %  │  │
│  │ ●━━━━━───────────────    │  │
│  │                          │  │
│  │ Zoom               1.0×  │  │
│  │ ●━━━───────────────      │  │
│  │                          │  │
│  │ ┌──────────┬──────────┐ │  │
│  │ │  Origin  │  Centre  │ │  │   ← framing promoted, not hidden
│  │ │ 0.000,0  │  tap the │ │  │
│  │ │          │  preview │ │  │
│  │ └──────────┴──────────┘ │  │
│  │                          │  │
│  │ IMAGE  DEPTH  GALLERY  CAL │  │   ← the four peer commands, unchanged
│  │                          │  │
│  │  Set as wallpaper       │  │   ← primary: accent, filled
│  │  Depth AI (ComfyUI)     │  │   ← secondary: outlined
│  │                          │  │
│  │ ⌄ diagnostics            │  │   ← collapsed by default
│  ╰────────────────────────╯  │
└──────────────────────────────┘
```

### The changes that matter

**Values move onto the label line.** Today every slider burns two rows — a text
line above, then the track — so the number you are changing is never adjacent to
the thing you are changing. Putting `Depth  54%` on one line with the track
beneath removes half the vertical budget and makes the relationship obvious.

**Framing is promoted out of the disclosure.** For a tool called Basrelief,
exact origin is the substance, and it is the one thing a gesture cannot express.
It becomes a permanent row, not something hidden behind `▾`. The **Centre**
affordance ties it to the preview: the app *already* draws an orbit marker, and
that marker is the origin. Tapping it recentres, so the control and the thing it
controls are the same object on screen.

**The photo gets an identity line.** `PHOTO` / `share_image.png` tells you what
you are editing. Right now there is no way to see which item is loaded without
opening the gallery.

**Primary and secondary are visually distinct.** `Set as wallpaper` is the point
of the app and today it is the same weight as `Depth AI (ComfyUI)`. One is
filled with the accent; the other is outlined. The Actions row keeps all four
commands at equal weight, because they are peers.

**Diagnostics collapse.** The engine line has earned its place — it is how the
invisible-panel bug was ever findable, and it is read from a screenshot without
a cable. But it does not belong beside the controls. It becomes a `⌄
diagnostics` disclosure, monospace, dimmed, collapsed by default, and it is the
one place a status colour is allowed.

**The hint moves to the preview.** "Tap: orbit center · 2-finger: pan · pinch:
zoom" teaches gestures the panel cannot. It belongs as a first-run coach overlay
on the picture, dismissed on first touch, not as body text in a settings list.

## States

Every one of these currently has no designed form.

| State | Treatment |
|---|---|
| No photo chosen | Preview shows a centred prompt with a single primary `Choose a photo`; the console shows only that, not three dead sliders |
| Depth map missing | `Depth` row carries a `warn` badge and a `Flat` marker — not a silent fallback, which is the current behaviour |
| Item not editable | Framing row is replaced by a locked state with a reason, not a set of disabled controls |
| Engine degraded | Diagnostics row shows a `warn` dot; the primary action stays enabled because the wallpaper may still be fine |
| Sheet collapsed | Floating ＋ becomes a 56dp pill with the accent, showing the photo's thumbnail at 24dp |
| First run | Coach marks on the preview for tap / two-finger / pinch |

## Touch targets

`dp(36)` today. The redesign floor is `dp(48)` for anything tappable, `dp(40)`
minimum for the paired nudge buttons, which sit adjacent and are used in
alternation so their combined target is comfortable even if each is not 48.

## Accessibility

- Every icon-only control carries a `contentDescription`; the nudge buttons
  announce `Increase origin X` rather than `+`.
- The slider rows collapse to one accessibility node: *"Depth, 54 percent"*,
  with the SeekBar as the adjustable action, so a screen reader does not read
  the label, the value and the track as three separate stops.
- Collapsing and expanding the sheet announce themselves
  (`announceForAccessibility`), because the change is otherwise entirely visual.
- `text-tertiary` on `surface` is 4.9:1 — above the 4.5:1 requirement for
  body text but below 7:1, so it is only ever used for non-essential hints.

## What is deliberately not changing

- **Actions stays exactly four buttons**, equal weight. They are peers and
  inventing hierarchy among them would be a lie about the model.
- **The bundled marble bust stays in the gallery**, non-deletable, always
  rendered at defaults.
- **Preview-while-dragging, persist-on-release** for the sliders. It is correct.
- **The sheet's free positioning** and the remembered fraction, with the restore
  clamped so a launch is never blank.
- **Framing remains the single resolution of state into numbers**, so the studio
  and the wallpaper cannot disagree.

## A note on motion, honestly

The obvious way to animate the sheet is with `translationY`, and that is exactly
what broke it — see `AGENTS.md`. A transform did not reliably repaint the panel's
region on this device, so the sheet is positioned by **layout height** and the
expand is instant, because animating a layout property is what makes the
animation feel like the wait the user objected to in the first place.

That trade is real and it is the reason this concept is quieter than it would
otherwise be. A spring-feel sheet needs transforms, and transforms need the
repaint bug fixed first. **Fixing that bug is the highest-value UI work this app
could do next**, because it would unlock motion on the only surface where motion
matters.
