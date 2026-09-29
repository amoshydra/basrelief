# `.design/` — studio redesign

A concept and an interactive prototype for the Basrelief studio. **Nothing here
changes the app.** It is a proposal, kept out of `android/` so it cannot be
mistaken for shipped code.

## Contents

| File | What it is |
|---|---|
| `01-audit.md` | What the studio is today, read out of the code. Evidence, not opinion. |
| `02-concept.md` | The redesign: direction, tokens, layout, states, accessibility. |
| `03-android-capabilities.md` | What is actually buildable framework-only, and what is AndroidX-only. |
| `prototype/index.html` | Both UIs side by side and interactive. Self-contained, no network. |

Open `prototype/index.html` in any browser. Drag the grip on either phone, drag
the sliders, open Framing, open Diagnostics.

## The one-paragraph version

The app has **no theme**. `res/values/` contains one file, `strings.xml`, and
the manifest declares `@android:style/Theme.DeviceDefault.NoActionBar`, so every
control's appearance is whatever the user's OEM ships — different on a Pixel, a
Samsung and a Xiaomi, and subject to force-dark on top of a theme nobody
authored. There is no colour system, no shape language, no type scale. Every
control is a stock `Button` with `setAllCaps(false)`; there is not one
`RippleDrawable` or rounded corner in the project.

So the redesign is mostly *subtraction and definition*: own a palette, give
numbers a tabular figure so they stop jittering, put each value on the same line
as the control it belongs to, promote Framing out of a disclosure because exact
origin is the substance of a basrelief, make `Set as wallpaper` the one filled
button because it is the point of the app, and move the debug output into a
collapsible block where it belongs.

## A finding that shaped the whole thing

The sheet's "invisible but still takes taps" bug — which cost an evening — is a
**documented Android limitation, `b/7179570`, open since 2012**. `SurfaceView`'s
own javadoc says a sibling view positioned with a *post-layout transform* may
not composite correctly over the surface, because the transparent region is
computed from *layout* positions. `setVisibility` runs a layout pass and
repaints; `setTranslationY` does not.

That constrains the redesign more than anything aesthetic. It is why the sheet
is positioned by layout height, why the expand animation is instant, and why
this concept is quieter than it would like to be. **Fixing or working around
that limitation properly is the highest-value UI work available**, because it
would unlock motion on the one surface where motion matters. See
`03-android-capabilities.md` for the options, including `setZOrderOnTop`.

## How far to trust this

**The audit is trustworthy.** Every claim in `01-audit.md` is a statement about
code that exists in this repository, and most of them are greppable.

**The concept is a proposal.** It is a considered argument, not a measurement.

**The prototype has never been rendered.** There was no browser available in the
environment it was written in, so it has been verified structurally — the JS
parses, it is self-contained with no network requests, and every one of the 20
selectors the script reaches for resolves against the markup — but nobody has
looked at it. Treat the first viewing as a review, not a confirmation.

**The buildability claims are sourced.** `03-android-capabilities.md` separates
what was verified from documentation and AOSP source from what was inferred,
and flags the things that turned out not to exist.
