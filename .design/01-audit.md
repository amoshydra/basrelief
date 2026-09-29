# Audit: what the studio actually is today

Written by reading the code, not by taste. Every claim here is checkable, and
several of them are the reason the UI feels unfinished rather than merely plain.

## The single biggest fact

`res/values/` contains **one file**: `strings.xml`. There are no styles, no
themes, no colours, no drawables. The manifest declares

```xml
android:theme="@android:style/Theme.DeviceDefault.NoActionBar"
```

so every pixel of the interface is inherited from the platform default **on the
user's device**. Consequences:

- The app looks different on a Pixel, a Samsung and a Xiaomi. Nothing in this
  repo decides that; the OEM does.
- On API 29+ the platform may apply force-dark or dynamic colour on top of a
  theme the app never authored, so a dark studio can be re-tinted behind the
  app's back.
- There is no colour, type or spacing system to design *within*. Every value is
  invented at the point of use.

This is the root cause of most of what follows. You cannot restyle what has no
style.

## Inventory of controls

Everything is a stock framework widget with, at most, `setAllCaps(false)` and a
minimum height. There is no `RippleDrawable`, no `StateListDrawable`, no
`GradientDrawable`, no `outlineProvider`, no `StateListAnimator` anywhere in the
project.

| Element | Implementation | Notes |
|---|---|---|
| Panel background | `Color.argb(180, 11, 14, 20)` | one hardcoded colour |
| Handle strip | `Color.argb(90, 255, 255, 255)` | one hardcoded colour |
| Grip bar | `Color.argb(150, 255, 255, 255)` | one hardcoded colour |
| Section labels | `0x88FFFFFF`, 11sp, `setLetterSpacing(0.15)` | letter-spacing implies a caps treatment that isn't there |
| Buttons | `new Button(this)`, `setAllCaps(false)`, `minHeight dp(36)` | no background, no shape, no pressed state |
| Sliders | `new SeekBar(this)` | stock |
| Fields | `new EditText(this)` | stock |
| Status line | `new TextView`, 12sp, `Color.WHITE` | |

`makeButton` is the single button factory, and it contributes exactly two
styling decisions: `setAllCaps(false)` and `dp(36)`.

## Concrete problems

**1. No owned visual identity.** See above. This is first because it subsumes
the rest.

**2. No interaction feedback.** No ripple on any control. `Button` gets a
default pressed state from the platform theme, but that is an OEM's idea, not
this app's, and it is invisible on a surface as dark as the panel. The one
gesture this app is built around — dragging the sheet — has no visual response
beyond the panel moving.

**3. No shape language.** Not one rounded corner in the app. The panel, the
handle strip, the grip, every button and field is a hard rectangle. This is the
single most visible tell that no design system exists, and it is also the
cheapest to fix at framework level (`GradientDrawable` with a corner radius).

**4. An accidental and fragile type scale.** Ten `setTextSize` calls resolve to
**four distinct sizes** — 11, 12, 13, 14 — with no scale and no line-height
decisions, and several roles share a size that should differ: the gesture hint
and the section labels are both 11sp, while the status line and the Framing
toggle are both 12sp. Nothing in the app decides what type means; each call site
picks a number.

**5. The debug diagnostics live in the shipping surface.** The status line
reads:

```
Engine: visible=true surface=true item=share_image.png want=shared_image.png onScreen=true at=767804
```

That line has earned its place — it is the only reason a class of invisible-
rendering bug was findable at all, and it is read from a screenshot without a
cable. But it is developer output presented as product UI, it is two lines of
mixed-case identifiers, and it does not belong on the same surface as the
controls. It wants to be behind a tap-to-reveal, in a diagnostics screen, or
compiled out of release builds — ideally all three.

**6. Density and hierarchy.** At full height the panel occupies roughly half
the screen to present three sliders, a disclosure and six buttons. Every control
is a full-width row. The vertical budget is spent on repetition rather than on
information: each slider burns a label line *above* its track, so the value is
never adjacent to the thing it controls.

**7. Framing is hidden behind a disclosure.** `Framing ▾` collapses the most
precise controls in the app. For a tool called Basrelief, exact origin and pan
is arguably the substance, and it is the one thing that cannot be expressed by
a gesture.

**8. Touch targets below the platform guidance.** `dp(36)` minimum height on
buttons, against a 48dp recommendation. The nudge buttons are `dp(40)`. The
floating ＋ is the only control at `dp(48)`.

**9. No empty, loading or error states in the panel.** The status line carries
diagnostics but the interface has no designed state for "no photo chosen",
"depth map missing", or "this item can't be edited".

**10. The hint line is a sentence of middot-separated instructions.** "Tap:
orbit center · 2-finger: pan · pinch: zoom" — it teaches gestures that are
otherwise undiscoverable, which is good, but it is set as body text in the
panel rather than as an onboarding affordance tied to the preview.

## What is genuinely good and must survive the redesign

- **The status/diagnostics line exists at all.** Most apps would not. It is the
  reason the invisible-panel bug was findable, and it is read from a photo.
- **One read feeds the panel and the renderer**, so the displayed values cannot
  disagree with what is rendered. `Framing` exists precisely to guarantee that.
- **Preview-while-dragging, persist-on-release.** The sliders update the label
  and the renderer on every frame and write once. That is the correct policy
  and it should not be disturbed.
- **Actions has exactly four equal-weight commands.** Equal weight is right for
  four peer commands; the design should not start inventing hierarchy among them.
- **The sheet is free-positioned and the position is remembered** as a fraction,
  with the restore clamped so a launch is never a blank screen.
