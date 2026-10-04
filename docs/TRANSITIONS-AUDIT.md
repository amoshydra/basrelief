# Transitions audit — instant paints and guessed current values

Written 2026-10-04 at `93e49b4`, as a read-only audit. It exists to find
**places that share the class of the bugs the `apply` guard was built for**:

> don't paint anything instantly. frame can only be rendered by gliding towards,
> not immediately painting.

Two shapes are in scope, because they were the two root causes this session:

1. **A frame produced instantly rather than eased toward** (a resolve that
   paints a destination a glide is already moving to).
2. **A "current value" guessed rather than read** from the thing that is
   actually rendering (`lastVisibleSurface`, a surface label, a stored *target*
   used as an origin).

Nothing was changed. Every finding below is either marked **correct** with the
reason, or **bug** with evidence — and where there is no evidence, it says so.

---

## Method and limits

- Read the code at `93e49b4`: `ReliefRenderer`, both hosts, `Framing`,
  `LightDirection`, `Picture`, `SurfaceResolver`, `SheetMorph`, `SheetIntent`,
  `PlaylistStatus`, plus every `apply`, `glide`, `Framing.of` and
  `isGlidingToward` call site.
- **The device is not running this commit. It is running the parent,
  `9dc59dc`.** Verified by pulling the installed `base.apk` and reading the
  string it logs at startup: `Basrelief service starting, build 9dc59dc`.
  The APK's `lastUpdateTime` is `2026-10-04 14:58:43`; `93e49b4` was authored
  `15:00:01`, ~80 s later. So the `Apply` enum, the guard in
  `ReliefRenderer.apply`, and `isGlidingToward` **do not exist on the device**.
  The task's premise that the device runs `93e49b4` is wrong.
- Consequence for measurement: **none of this audit's findings could be
  measured**, because the code that contains them is not installed. Installing
  `93e49b4` would drop the live-wallpaper binding (AGENTS.md; the same
  `install -r` caveat), which needs the system picker to restore — a user
  gesture this audit cannot make. So every finding is "not measured" unless
  noted, and the device pass is listed at the end.
- One **read-only** observation was taken on `9dc59dc` and is included only
  where the relevant code is unchanged between the two commits:
  `adb -s 192.0.2.11:5555 logcat`, one wake from doze produced
  `which=LOCK` at `15:07:26.351` then `which=HOME` at `15:07:28.836`, both
  `visible=true surface=true onScreen=true`. This says both engines ran
  `applyFromPrefs`; it does **not** say both drew, because the diagnostic line
  is emitted (line 648) *before* the `isForegroundSurface` gate that returns
  (line 719). Worth knowing the next time someone reads a two-line
  `which=LOCK`/`which=HOME` pair as a double-apply: the log cannot show that.

---

## Findings

### A. `apply()`'s guard does not cover "a glide is on its way", and only one of the two hosts compensates — **BUG**

- `ReliefRenderer.java:1461-1466` — the guard is
  `if (why == RESOLVE && snapshot().sameAs(f)) return;`.
- `ReliefRenderer.java:1310-1319` — `isGlidingToward(f)` is the check that
  actually answers "is a glide already heading to `f`"; it reads `glideTo`
  first, then `snapshot()`.
- `DepthWallpaperService.java:749-770` — the wallpaper calls
  `isGlidingToward` before `apply`, so a repeat resolve cannot cancel a live
  glide there.
- `MainActivity.java:5107-5114` (`applyFiles`) — the studio does **not**.
- `ReliefRenderer.java:1448-1454` — the guard's own javadoc claims it covers
  "a resolve re-painting a frame a glide is already easing toward". It does
  not: mid-glide, `snapshot()` holds the interpolated value, not the
  destination, so `snapshot().sameAs(f)` is false and the guard falls through
  to paint `f` and clear `glideFrom`/`glideTo`.

**Reachable paths through `applyFiles` while a glide is running** (the studio
surface switch arms a 600–3000 ms glide, default 1200 ms; `MainActivity.java:4623-4647`):

- tap Home/Lock, then tap the **already-active** tile in the strip/grid →
  `Gallery.select` → `applyFiles` (`Gallery.java:1625-1644`), same pair →
  `RESOLVE` with `f` equal to the glide's destination, snapshot mid-flight →
  paint, glide cancelled. Visible as a snap.
- tap Home/Lock, then tap **Revert to Home** in the same window →
  `MainActivity.java:4492-4510` → `applyFiles`, same pair → same snap.
- a brief background/resume during a glide: `onResume` queues
  `reloadFromPrefs` (`MainActivity.java:1444-1449`), whose `apply(mine)`
  (`ReliefRenderer.java:1549`) has the same shape when `mine` is the glide's
  destination.

**Not measured.** The fix is small and safe but is deliberately left for the
owner: fold the check into the guard, e.g.
`if (why == RESOLVE && (snapshot().sameAs(f) || isGlidingToward(f))) return;`
and then the wallpaper's call-site check is redundant. Note `isGlidingToward`
already calls `snapshot()`, so the combined predicate is one `glideTo`
comparison plus one snapshot comparison. Before changing it, test both
directions: a resolve that genuinely disagrees with the glide must still
paint, and a repeat resolve of the same destination must not.

### B. `snapshot()` can never carry `bundled`, so a `RESOLVE` against the bundled demo is never a no-op — **BUG (latent)**

- `ReliefRenderer.java:1386-1395` — `snapshot()` is built with
  `Framing.ofValues(...)`.
- `Framing.java:249` — `ofValues` hard-codes `bundled = false`.
- `Framing.java:80-95` — `sameAs` compares `bundled` like every other field
  (the field count is asserted by `FramingTest`).

So for the bundled item, a resolved `f` has `bundled = true` while every
snapshot has `bundled = false`: `snapshot().sameAs(f)` is **structurally
false**. The guard's no-op is disabled for exactly the one row that already
has a history of special-case bugs.

In isolation this only means "a same-demo resolve repaints identical values and
cancels any glide", which the wallpaper masks because `isGlidingToward`
compares `glideTo` (a resolved framing, `bundled = true`) before `snapshot()`.
The damage is that it removes the safety net under Finding A: on the bundled
demo, the guard is no protection at all, and Finding A's studio paths snap
regardless of whether the values actually differ.

**Not measured.** Fixing it means either carrying the displayed render's
`bundled` into `snapshot()` (a second constructor path) or excluding `bundled`
from the no-op comparison (it is a property of *which file to read*, not of
the framing a glide interpolates), and either is a change `FramingTest`
would need to pin.

### C. `lockFramingShowing` documents a clear that does not exist, and publishes a target rather than a frame — **BUG (stale-hand-off risk)**

- `DepthWallpaperService.java:46` — the field's doc: *"Cleared when a LOCK
  engine stops being the foreground surface, so a stale value cannot be
  inherited by a home engine that starts for some other reason."*
- `DepthWallpaperService.java:733-735` — its **only** write:
  `if (resolved.surface() == LOCK) lockFramingShowing = framing;` — and it sits
  *before* the `isForegroundSurface` gate, so it records the resolved target
  even for a lock engine that then declines to draw.
- There is no clear anywhere in the file (**re-verified 2026-10-05** by grep:
  four references, **one write, zero clears**; `onVisibilityChanged`'s hide
  branch and `onDestroy` do not touch it).
- `DepthWallpaperService.java:891-892` consumes it —
  `renderer.inheritFirstFrameFrom(lockFramingShowing, 250f)` — which passes it
  as the **glide origin**: `glide(inherit, mine, ...)`, and `glide` paints a
  non-null origin first.

Two distinct problems:

1. **The doc promises a clear that is not implemented.** The guard that saves
   it today is the *caller's* `!isDeviceLockedNow()` on engine creation, not
   the field being fresh.
2. **The inherited origin is a resolved target, not the renderer's
   snapshot.** On a genuine unlock the lock engine never glides
   (`isSurfaceTransition` refuses LOCK), so target == shown and it is honest.
   But a HOME engine created for a reason *other* than an unlock (e.g. a
   re-bind while the process survives and the device is already unlocked) will
   inherit whatever lock framing was last written, and `glide` will paint it as
   the origin — the same "instant paint of a guessed config" the last commit
   removed from the *other* origin path, just routed through inheritance
   instead of a surface label. The origin-apply is into the volatile fields
   only, so it is invisible unless a frame is then drawn with it; on a cold
   engine the first drawn frame is that origin's config.

**Not measured.** The reboot case is safe (the static dies with the process),
which is why the doc's claim survives casual use. A device pass needs a re-bind
without process death, which is hard to stage by hand.

### D. `keyguardWasUp()` answered a capability question and was read as state — **RESOLVED, `adf834c`, and one of the two claims was wrong**

**Status: fixed and measured. Both the finding's premise and one of its two
contradictory claims turned out to be wrong, so this is recorded as a
correction rather than a fix.**

- `DepthWallpaperService.java` — `isDeviceLockedNow()`'s doc still says:
  *"`isKeyguardLocked()` read true on every sample of every cycle including
  after two full unlocks. Eight attempts used it as a transition signal and it
  never once changed, so every rule built on it was decided by a constant."*
- `keyguardWasUp()` was exactly `km.isKeyguardLocked()`.
- `holdLockFrameForUnlock` read
  `if (isInteractive() && !isDeviceLockedNow() && !keyguardWasUp()) return;`
  and its doc claimed *"Verified: when a studio covers the wallpaper the hide
  logs `interactive=true`, this returns."*

**The audit was right that the two could not both be true, and wrong about
which was false.** Measured on the same device (Pixel 7 Pro, sdk 37, both
surfaces bound separately) with a log added at the top of the method:

```
studio covering an unlocked wallpaper   interactive=true  locked=false  keyguard=false  -> hold=false
the screen darkening while HOME hides  interactive=false locked=false  keyguard=false  -> hold=true
LOCK engine, after POWER               interactive=false locked=true   keyguard=true   -> (HOME gate)
```

`isKeyguardLocked()` **moves**: false while awake and unlocked, true while
locked. So the third term was not a constant `false`, the early return was
**not** dead, and the old guard would have behaved identically to the new one in
this cycle. **`adf834c` is therefore a robustness fix, not a demonstrated bug
fix**, and the claim this finding rested on does not reproduce.

**The fix is still strictly better in both worlds.** The term answered a
*capability* question — its own javadoc said "whether the keyguard exists at
all" — while the guard used it as *state*. On a device where that reading **is**
constant, the old guard ran the pre-hold on every hide including an app cover,
which is the one case the rule exists to prevent. Either way the decision now
rests only on `isInteractive()` and `isDeviceLocked()`, both of which are
documented to move.

**And the cited proof did not exist.** `grep interactive=` matched only the
comment — there was no such log in the tree, so "this returns" was checkable
against nothing. There is now a `pre-hold enter …` / `pre-hold hold=…` pair
logged **before** the gates, because a log that only appears on the decision
path cannot distinguish "the guard said no" from "the method never ran" — which
is what happened on the first attempt, producing zero lines and no way to tell
which it was.

**The lesson worth keeping, and it is about the audit rather than the code: a
document that records a measurement nobody can reproduce is worse than one that
records none.** This finding sent two sessions looking at a term that was
provably live. The other half of it — "needs a device pass" — was the correct
instinct, and one lock/unlock cycle settled it in under a minute.

### E. `Apply.GESTURE` is declared but never used; gestures bypass `apply` entirely — **correct, but the invariant overstates the code**

- `ReliefRenderer.java:1415` — the constant; grep finds no `Apply.GESTURE`
  call site.
- Gestures reach the renderer through the direct volatile setters
  (`setDepth`/`setZoom`/`setMotion`/`setAdjust`/`setLight`/`setOrbit`/`setPan`)
  from `MainActivity.applyStoredSlider` (`MainActivity.java:3446-3462`) and
  `FramingRows.axis`'s preview (`FramingRows.java:243-268`). Those write fields
  and never consult the guard, which is what a gesture needs.

So the "two legitimate escapes" are really *one* used (`PAIR_CHANGE`) plus one
reserved. That is fine — a gesture cannot accidentally inherit the no-op
because it never calls `apply` — but anyone reading only `apply`'s doc would
think the `GESTURE` path is live. Worth a sentence, not a change.

### F. The wallpaper classifies a pair change as `RESOLVE` — **correct in effect, stated reason missing**

- `DepthWallpaperService.java:768-770` — every resolve is
  `renderer.apply(framing)` (`RESOLVE`), including one where the selection
  moved to a different photo.
- It is correct: the guard lets a differing framing paint (that is the pair
  change), and `isGlidingToward` protects a live glide. But it is the exact
  case `PAIR_CHANGE` exists for, and the wallpaper is the one host that does
  not state it. If a future change ever makes a *same-pair* wallpaper resolve
  with values equal to a live glide's destination, the call-site
  `isGlidingToward` is what saves it; the enum reason does not.

### G. A live slider preview is frozen for the length of a pair decode and a cross-fade — **correct by design, with a small cost**

- `ReliefRenderer.java:1647-1652` — while `pairChangePending`, `captureFraming`
  is skipped so the outgoing photo is not drawn through the incoming photo's
  Depth/Zoom; during the 200 ms cross-fade both renders use their captured
  framing.
- Consequence: a Depth/Zoom drag during a decode (up to ~1 s) or a fade
  (200 ms) is not visible until the transition lands. The first is the
  documented fix for "depth arrives before the image"; the second is a 200 ms
  lag on a slider. Not the instant-paint class; recorded so it is a decision
  rather than a surprise.

---

## What is correct, and why (so the audit is complete)

- **The light eases.** `LightDirection.lerpAzimuth` is used on transitions,
  not only drags: `ReliefRenderer.stepGlide` (`1369-1372`) interpolates both
  angles through the short arc, and each fading `Render` carries its own
  resolved vector (`captureFraming`, `1891-1898`), so a pair change blends two
  lights rather than snapping. `setLight` itself snapping is correct — it is
  the gesture/`apply` path.
- **Brightness and Shadow ease.** `stepGlide` lerps the two `Picture` uniforms
  (`1367-1368`) and `drawRender` reads them per render (`1726-1727`), so a
  pair change does not carry one photo's adjustment onto the other.
- **The cross-fade is time-driven and self-pumping** (`1655-1687`): wall-clock,
  so 60/90/120 Hz agree, and it asks the host for frames while it runs. It is
  armed only when a pair replaces a pair already on screen (`install`,
  `1856-1873`), so the first frame an engine draws is never a half-fade — the
  system-snapshot constraint holds.
- **The glide origin is read, not guessed, on both hosts.**
  `DepthWallpaperService.java:749-750` passes `null`, and `glide` takes
  `snapshot()` (`ReliefRenderer.java:1281-1285`); the studio's surface switch
  does the same (`MainActivity.java:4631`). This is the `93e49b4` fix; it
  duplicates AGENTS.md's "a glide's origin must be what is showing, never a
  guessed surface label", so it is not restated as a finding.
- **`:core` derivations are pure in their changing inputs.** `SurfaceResolver`
  takes `(sdk, flags)` and derives `surface()`/`coversLockScreen()` from the
  flags, not from a constant; `Framing` is a function of `(item, surface)`;
  `SheetMorph`/`SheetIntent` take the drag fraction/position/velocity;
  `PlaylistStatus.of` takes `now` and every caller passes a fresh
  `System.currentTimeMillis()` (`MainActivity.java:4357`,
  `Gallery.java:938`, `PlaylistScreen.java:430,713`). **No `:core` derivation
  computes a changeable value from an unchanging one.** The
  `isKeyguardLocked` pattern (Finding D) is in `:app`.
- **A same-photo depth regeneration swaps geometry with no fade**
  (`install`'s `samePair` branch, `1856-1873`). Correct: the photo is the same
  picture and the relief change *is* the user-visible result of the gesture;
  cross-fading geometry would be meaningless.

---

## Ranked list

1. **A — `apply`'s guard omits `isGlidingToward`, so `MainActivity.applyFiles`
   (and any future `RESOLVE` caller) can cancel a glide and snap.** Highest
   because it is the exact bug class this audit exists for, the fix is one
   predicate in one place, and it is reachable from ordinary studio gestures.
   Not measured.
2. **D — the `keyguardWasUp()` contradiction. — RESOLVED as `adf834c`, and the
   finding's premise was wrong.** Measured on the same device:
   `isKeyguardLocked()` reads **false while awake and unlocked, true while
   locked** — it moves, so the early return was never dead and the old guard
   behaved as the new one does. Shipped anyway as a robustness fix, because the
   term answered a capability question and was read as state, and because the
   log it cited for proof **did not exist in the tree**. Details in finding D.
3. **B — `snapshot().bundled` is always false. — FIXED as `80c7b78`**, together
   with #1. `sameAs` no longer compares `bundled`.
4. **C — `lockFramingShowing`'s absent clear, and target-as-origin.** **Now the
   highest unresolved item.** Medium when first ranked — the common paths are
   safe (reboot, genuine unlock) and the failure needs a re-bind without
   process death. Two things have changed since: the doc is still wrong today
   (the clear it promises is not implemented, re-verified by grep), and the only
   thing preventing a stale inherit is now the *caller's* `!isDeviceLockedNow()`
   rather than the field being fresh. The doc was corrected above; the clear is
   not implemented.
5. **F — the wallpaper states `RESOLVE` for a pair change.** Low: no behaviour
   bug, but the stated reason is the contract the enum was built to make
   explicit.
6. **E — `Apply.GESTURE` unused.** Low: documentation accuracy.
7. **G — slider preview frozen during decode/fade.** Low: a decision, with the
   200 ms fade lag worth knowing.

---

## Device pass (not done; needs the right build)

The device is on `9dc59dc`; the audit's code does not exist there. A pass needs:

1. Build and install `93e49b4` (`./podman-build.sh install`), then **re-check
   the binding** — `dumpsys wallpaper | grep mWallpaperComponent` — because an
   install drops it, and **do not `am force-stop`** (it reverts the wallpaper
   to static, the trap AGENTS.md records twice).
2. A temporary `apply`/`glide` probe that names the caller (the previous
   session used one and removed it): without it, Finding A and D cannot be
   distinguished from correct behaviour, because the shipped build logs
   neither.
3. **Finding A:** with per-surface settings differing, tap Home/Lock and, inside
   the glide span, tap the active strip tile or Revert to Home. Expect a snap
   instead of the ease; the probe should show `applyFiles` reaching `apply`
   with `sameAsShowing=false` while `glideTo` equals `f`.
4. **Finding D:** with a Lock override stored, cover the wallpaper with the
   studio, return to the launcher, and check whether the pre-hold logged an
   `apply(lock)` on that hide. If it did, `keyguardWasUp()` is constant on this
   device and the early return is dead.
5. **Finding B:** repeat A on the bundled demo; the guard should be the thing
   that stops it and cannot be.

Never `screencap` for the motion (AGENTS.md: it forces the redraw under test);
ask for a device screenshot for anything visual.

---

## Status update (2026-10-04, later the same session)

**Fixed:**

- **Ranked #1 and #2** — committed as `80c7b78`. `apply()` now asks `isGlidingToward`,
  so a resolve is a no-op when the frame is on screen **or already on its way there**;
  it previously asked only `snapshot().sameAs(f)`, which is **false mid-glide**, so it
  protected a frame that had ARRIVED and not one in flight — the case it exists for.
  The wallpaper's duplicate guard is deleted. And `sameAs` no longer compares `bundled`,
  which `ofValues` hard-codes to `false`: the guard was **structurally absent for the
  bundled demo**, the one row every install starts with.

**Also resolved since (2026-10-05):**

- **Ranked #2 (was #3) — `holdLockFrameForUnlock`'s early-return. — FIXED as `adf834c`.**
  The guard read

  ```java
  if (isInteractive() && !isDeviceLockedNow() && !keyguardWasUp()) return;
  ```

  and this section's claim — that `keyguardWasUp()` (`isKeyguardLocked()`) read **`true`
  on every sample of every cycle**, making the third term constant and the early-return
  dead — **does not reproduce.** Measured on the same device it reads **`false` while
  awake and unlocked and `true` while locked**. So the early return was live, and the old
  guard behaved as the new one does. It was fixed anyway as a **robustness** change: the
  term answered a *capability* question ("does a keyguard exist") and was read as *state*
  ("has the keyguard taken over"), so the decision now rests only on `isInteractive()`
  and `isDeviceLocked()`. On a device where that reading **is** constant the old guard
  ran the pre-hold on every hide including an app cover, which is the one case the rule
  exists to prevent.

  The load-bearing concern that justified leaving it alone was respected: the pre-hold
  still fires when the screen darkens, which is the case that keeps the unlock glide
  alive. Measured both branches on a Pixel 7 Pro, sdk 37, both surfaces bound
  separately — `hold=false` for a studio covering an unlocked wallpaper, `hold=true` for
  the screen darkening.

  **What this section got right and what it got wrong:** right that the two comments
  could not both be true, and right that it needed a device pass. Wrong about which was
  false, and wrong to record an unreproducible measurement as settled — which sent a
  later session looking at a term that was provably live. **The proof it cited did not
  exist**: *"the hide logs `interactive=true`"* matched only the comment. There is now a
  real `pre-hold enter …` / `pre-hold hold=…` pair, logged before the gates so that
  "the guard said no" is distinguishable from "the method never ran".

**Still open:**

- **`lockFramingShowing`** documents a clear that does not exist and publishes
  a resolved *target* rather than what was rendered, so a re-bind without process death
  could inherit a stale origin. Re-verified by grep on 2026-10-05: one write, **zero
  clears**. The doc is now corrected above; the clear is not implemented.
