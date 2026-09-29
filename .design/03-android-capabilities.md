# What is buildable framework-only, and what is not

Research for `02-concept.md`, on `minSdk 26` / `targetSdk 36`, Java, no
AndroidX, no Material Components. Levels were read from the
`data-version-added` attributes on `developer.android.com` and from AOSP
`frameworks/base` at `master`.

This document exists because I would rather be told a thing does not exist than
find out on a device. It has already earned that: **three things I believed and
wrote down were wrong**, and they are corrected below rather than quietly
dropped.

---

## 1. The finding that matters most: `targetSdk 36` is unconditionally edge-to-edge

Android 15 enforced edge-to-edge at `targetSdk 35` with an opt-out;
`windowOptOutEdgeToEdgeEnforcement` is **deprecated and disabled at API 36** and
the app cannot opt out. Also at `targetSdk 35+`,
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT`, `_SHORT_EDGES` **and `_NEVER` are all
reinterpreted as `_ALWAYS`** for non-floating windows, so `never` is no longer an
escape.

Which makes the current code wrong in a way that is easy to miss, because the
calls still compile and still do nothing:

| Call in `applySystemUi()` | Status |
|---|---|
| `Window.setStatusBarColor` | deprecated API 35, **no effect on Android 15+** |
| `Window.setNavigationBarColor` | deprecated API 35, **no effect on Android 15+** |
| `View.setSystemUiVisibility` | deprecated API 30 |
| `Window.setNavigationBarContrastEnforced` | **not** deprecated — the one bar control that still works |

So the studio is calling three dead APIs and relying on flags that are on their
way out. The replacement is a version split — `WindowInsetsController` on 30+,
legacy flags on 26–29. `Window.setDecorFitsSystemWindows(false)` is API 30 and
itself deprecated at 35, but harmless and still the right call on 30–34.

### The inset trap worth knowing before writing any of it

On API 26–29, insets are **not dispatched to siblings** after anything returns
`WindowInsets.CONSUMED`. AndroidX ships `ViewGroupCompat.installCompatInsetsDispatch`
to paper over this; framework-only, the fix is simply **never return
`CONSUMED`** — return the insets unchanged and let the root be the only consumer.
There is no nested-scrolling list here competing for insets, so this costs
nothing and removes the version split.

Two rules that matter for a draggable sheet at the bottom of the screen:

- Use `getInsets(...)`, **not** `getInsetsIgnoringVisibility(...)`, for anything
  the user must be able to *tap*. The ignoring variant returns the inset a bar
  *would* take — right for stable layout, wrong for a touch target.
- Pad the bottom with `Type.systemGestures()`, not `Type.systemBars()`. On
  gesture-nav devices the gesture inset is larger, and a rounded sheet whose
  bottom corners sit under the bar has its corners visually cut.

---

## 2. Theming: dynamic colour *is* available, but not the M3 role names

This surprised me and is good news. `Theme.DeviceDefault`'s accent is wired to
the Material You tonal palette in AOSP:

```xml
<color name="accent_device_default_dark">@color/system_accent1_100</color>
```

So the app **already picks up dynamic colour on API 31+**, with no library —
because it inherits `Theme.DeviceDefault.NoActionBar`.

Two traps:

**The M3 semantic role colours are API 34, not 31.** On 31–33 you get the raw
tonal ramps (`system_accent1_0…1000`, `system_neutral1_*`, `system_error_*`) and
must derive `surface`/`onSurface` yourself. The semantic roles
(`system_surface_container_high_dark`, `system_on_surface_*`, `system_outline_*`)
arrived at **34**.

**The M3 attribute names are not public.** Checked against
`core/res/res/values/public-final.xml`:

| Attr | Public? |
|---|---|
| `android.R.attr.colorPrimary` | **yes** |
| `android.R.attr.colorSurface` | **no** |
| `android.R.attr.colorSurfaceVariant` | **no** |
| `android.R.attr.colorSurfaceContainer*` | **no** |

They exist in the framework's internal `attrs.xml`, which is why they are visible
in AOSP, and they will not compile from an app. The correct pattern is a
`values/colors.xml` with `values-v31` (tonal ramps) and `values-v34` (semantic
roles), referenced as literal colours or your own attrs.

**Force Dark is opt-in, not automatic.** At API 29 a light-themed app has
`android:forceDarkAllowed="true"` set for it by the platform themes. A dark theme
(or a `DayNight` one) is the defence. The widespread belief that Android 10
force-darkened apps unprompted is wrong.

---

## 3. Shape: the constraint that bites this design specifically

The sheet needs **only its top two corners rounded**. That turns out to matter
more than it looks, because AOSP's `GradientDrawable.getOutline()` branches on
which radius API you use:

```java
case RECTANGLE:
    if (st.mRadiusArray != null) {
        outline.setPath(mPath);              // path outline
        return;                              // <-- canClip() == false
    }
    outline.setRoundRect(bounds, rad);        // round-rect: canClip() == true
```

| Approach | Rounded fill | Rounded shadow | Clips children |
|---|---|---|---|
| `setCornerRadius(r)` (uniform) | yes | **yes** | with `setClipToOutline(true)` |
| `setCornerRadii(float[8])` (per-corner) | yes | **no**, plain rect shadow | **no** |

So a top-rounded sheet via `setCornerRadii` gets a rounded fill and a square
shadow. Options: accept the square shadow (on a dark translucent sheet over a
photo, a shadow is nearly invisible anyway), or put the rounded corners on a
child `FrameLayout` inside the sheet rather than on the sheet itself.

Also: the shadows guide's warning — *"Clipping views is an expensive operation,
so don't animate the shape you use to clip a view."* The sheet's shape must not
be animated, which is fine, because the sheet's *position* is animated instead.

`View.setRenderEffect(RenderEffect.createBlurEffect(...))` is **API 31** and is a
real framework class (the AndroidX `graphics` package is a wrapper). A genuinely
blurred translucent sheet is available from 31, ungated by API level below that.

---

## 4. Input: one widespread belief is wrong

**`NestedScrollingChild`, `NestedScrollingParent` and their Helpers are not
public API.** Verified three ways: `developer.android.com/reference/android/view/NestedScrollingChild`
returns **404**; a recursive search of the `platform_frameworks_base` mirror finds
the interface only at `core/java/com/android/internal/widget/NestedScrollingChild.java`
(note the package); and that file's own javadoc still says views should use the
`ViewCompat` shims.

Only the **methods** are public, and all since **API 21**:
`View.startNestedScroll`, `stopNestedScroll`, `setNestedScrollingEnabled`,
`dispatchNestedScroll`, `dispatchNestedPreScroll`, and the `ViewParent` /
`ViewGroup` callbacks. So a nested-scrolling custom view is buildable with zero
dependencies — you just cannot `implements NestedScrollingChild`, and you own the
parent-pointer bookkeeping and delta-array reuse yourself.

**`BottomSheetBehavior` is not available.** It is MDC, and it is built on
`androidx.customview.widget.ViewDragHelper`, which **does not exist anywhere in
`platform_frameworks_base`** — a recursive tree search finds nothing. A
framework-only sheet is `ViewConfiguration.getScaledTouchSlop()` for the
threshold, `VelocityTracker` for the release, `OverScroller.fling` for the
settle, and `startNestedScroll`/`dispatchNestedPreScroll` so the sheet yields to
the inner scroll list first. Which is essentially what this app already does by
hand.

Two details that are commonly got wrong:

- **Drag thresholds should exceed touch slop.** `getScaledTouchSlop()` is in
  pixels and already density-scaled, and at ~8dp a 24dp handle starts moving on a
  tap. Use `Math.max(slop, dp(16))` and compare cumulative `dy` from the down
  point.
- **`TouchDelegate` must be set on an ancestor, and that ancestor must manually
  forward `onTouchEvent` to it.** It is not a property of the small view. For a
  panel built in code, making the view 48dp with the graphic centred is simpler
  and more reliable.

Minimum touch target is **48dp**, and there is **no framework API that enforces
or reports it** — `ViewConfiguration` has no such constant. Hitting it is just
arithmetic. (`AccessibilityNodeInfo.setTouchBoundsInScreen`, which I expected to
be the reporting mechanism, **does not exist** in the SDK or in AOSP.)

---

## 5. Motion: springs are framework-only, and reduced motion is one line

`SpringAnimation` and `FlingAnimation` are `androidx.dynamicanimation` — the
official guide's first step is "add the support library". They were never
framework classes. But a spring is easy without them:

- **For a drag release**, use `OverScroller.fling(...)`, which takes a real
  velocity, and read `getCurrY()` per frame. This is what a sheet wants.
- **For a non-gestural animation**, a damped spring as a `TimeInterpolator` fed to
  `ValueAnimator`. The closed form is about fifteen lines; the one subtlety is
  that the animator's duration must be ≥ the spring's settle time or the tail is
  clipped and it "snaps" at the end.

`PathInterpolator` (API 21) covers the cubic-bezier cases but **cannot overshoot**,
because a Bézier with control points in [0,1] is monotonic. M3's "emphasised
decelerate" overshoot needs a custom interpolator.

**Reduced motion is one line, at exactly minSdk:**

```java
if (!ValueAnimator.areAnimatorsEnabled()) { jumpToEndState(); return; }
```

`areAnimatorsEnabled()` is **API 26** and returns false both when the user has
animations off (which is what the accessibility "Remove animations" setting
does) **and when battery saver is on**. For an app whose product *is* motion this
is a product decision, not just a UI one. Note the tension with this app's
existing `PowerSave.isOn()`: the two can disagree, and for the tilt rate the
existing `TiltController.setPowerSave` is the right signal while for UI
transitions `areAnimatorsEnabled()` is.

**The interaction with the SurfaceView constraint is the important part.** Per
`AGENTS.md`, a `translationY` animation on the panel will not reliably repaint
over the `GLSurfaceView`. So a spring on a *transform* is not usable for the
sheet's position today. A spring on scale/alpha/elevation of controls that are
not the sheet is fine, and a spring driven through `LayoutParams` is fine.

---

## 6. Accessibility: two of my own recommendations were wrong

**`View.announceForAccessibility` is deprecated at API 36.** The deprecation note
is explicit that services may ignore it and that announcements are the wrong tool
for confirming simple actions. My concept doc recommended it for the sheet
collapsing; that was wrong. The replacements, all framework:

| Need | API | Level |
|---|---|---|
| On/off/selected state | `View.setStateDescription` | 30 |
| A value changing as you drag | `View.setAccessibilityLiveRegion(POLITE)` | 19 |
| The sheet's name | `View.setAccessibilityPaneTitle` | 28 |
| Section headers | `View.setAccessibilityHeading` | 28 |

**Custom sliders must publish a range.** `AccessibilityNodeInfo.setRangeInfo`
(API 19) plus `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD` (21). Without
them a hand-rolled slider is announced as a button and cannot be adjusted by a
screen reader at all. The framework `SeekBar` does this for free, which is an
argument for keeping `SeekBar` rather than replacing it.

Also confirmed missing rather than available: `setTouchBoundsInScreen` (above),
and `AccessibilityManager.isReduceMotionEnabled` **does not exist** — the
animation duration scale is the whole story on 26–35.

One more real trap, specific to a panel built in Java: **there are no layout
files to audit.** Every accessibility gap in this app is a gap in code, and a
`GLSurfaceView` and anything drawn into it contribute nothing accessible, so any
information about the photo must exist in real sibling `View`s.

---

## 7. Text and density

- `DisplayMetrics.scaledDensity` is **deprecated** — non-linear font scaling
  (Android 14+) makes it inaccurate. Use `TypedValue.applyDimension(COMPLEX_UNIT_SP, …)`,
  which is non-linear-aware.
- `TextView.setTextSize(float)` is already `COMPLEX_UNIT_SP`, so it is
  font-scale-correct. **This codebase already does both correctly** — `dp()` is
  used for padding only, and padding must not scale with font size.
- `setAutoSizeTextTypeUniformWithConfiguration` is **API 26**, so with `minSdk 26`
  the full feature is available with no compat shim. Pass
  `COMPLEX_UNIT_SP` or the min/max will not track the user's font scale.
- **`setElegantTextHeight` is ignored at `targetSdk 36`** — deprecated by the
  Android 16 behaviour change, and it will not show as a compile warning because
  the *method* is not deprecated, only the attribute's behaviour. Text metrics
  will differ from a 35-targeted build.
- Use `MotionEvent.getX()/getY()` and `View.getLocationInWindow()`, never
  `getRawX()/getRawY()`, in immersive mode — the window origin is not the screen
  origin.

---

## 8. What Material Components gives you that you cannot have

Worth stating plainly, because these are the things a redesign reaches for:

| Missing | Notes |
|---|---|
| `MaterialButton`, `FAB`, `CardView`, `Chip`, `TextInputLayout`, `Snackbar` | MDC only |
| `BottomSheetBehavior` / `BottomSheetDialog` | MDC, on `ViewDragHelper`, which is absent from AOSP |
| `MaterialShapeDrawable`, `ShapeAppearance`, corner morphing | MDC. Framework outline is rect/circle/round-rect only |
| **Tonal elevation / elevation overlay** | No `elevationOverlay*` attribute exists in the framework. The tonal-surface maths is MDC's. Must be hand-computed, or read `system_surface_container_*` on API 34+ |
| `SpringAnimation`, `FlingAnimation` | `androidx.dynamicanimation` |
| `MotionLayout` | `androidx.constraintlayout` |
| `DynamicColors.applyToActivitiesIfAvailable()`, `HarmonizedColors` | The dynamic-colour guide's only Views path is MDC. The *colours* are reachable (§2); the *helper* is not |
| `WindowCompat`, `WindowInsetsControllerCompat`, `ViewCompat` | AndroidX. Framework `WindowInsetsController` (30) is the real thing |

What you **can** have framework-only: dynamic colour on 31+, `RippleDrawable`,
`StateListAnimator`, elevation, round-rect outlines with rounded shadows, real
`RenderEffect` blur on 31+, day/night, 48dp targets, and springs via
`ValueAnimator`.

---

## 9. Corrections this forced on `02-concept.md`

Recorded rather than quietly edited, because the point of commissioning the
research was to catch exactly this:

1. **`announceForAccessibility` for the sheet.** Deprecated at API 36. Replaced
   with `setStateDescription` / `setAccessibilityLiveRegion` /
   `setAccessibilityPaneTitle`.
2. **Dynamic colour was omitted.** It is available framework-only on 31+ and the
   app already has it by virtue of inheriting `Theme.DeviceDefault`. The
   concept's fixed accent palette is therefore a *choice*, not a necessity — and
   the honest version of that choice is to keep a fixed accent deliberately
   rather than by omission.
3. **The sheet's rounded top corners** are a per-corner `setCornerRadii`, which
   loses outline clipping and the rounded shadow. Now stated in the concept
   rather than discovered at implementation time.

---

## 10. Could not verify

Carried forward rather than guessed at:

1. `AccessibilityManager.isHighContrastTextEnabled` — reference page states API
   36; AOSP has the method with no `@since`.
2. `AccessibilityManager.isAccessibilityButtonSupported` — reference states API
   27, uncorroborated.
3. `DisplayMetrics.scaledDensity` deprecation level — javadoc confirmed, level
   not; likely 34.
4. `WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS` and
   `_TRANSPARENT_CAPTION_BAR_BACKGROUND` — present in the class, levels not
   individually extracted.
5. `ViewConfiguration.getScaledMinimumFlingVelocity(int, int, int)` — API 34
   confirmed, behaviour untested.
6. Whether `RenderEffect` blur composites correctly with `setClipToOutline` over
   a `SurfaceView`. Device-only.
7. M3's tonal-overlay alpha ladder (5/8/11/12/14% white at 0/1/2/3/5dp) — from
   my own knowledge. What *was* verified is that no framework equivalent exists,
   so the system is MDC-only either way.
8. Desktop/freeform windowing caption-bar behaviour in immersive mode.
9. The companion research could not reach `cs.android.com` or the Issue Tracker
   SPA, so its bug IDs come from AOSP commit messages rather than from
   issuetracker.google.com. `b/7179570` is a Google-internal number and is not
   publicly resolvable — the AOSP commit `c9f345ff7bc5` that documents the
   limitation is the citable artefact.
