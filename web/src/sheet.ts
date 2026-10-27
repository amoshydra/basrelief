/**
 * The studio's bottom sheet, reproduced for a page.
 *
 * The reference is `BottomSheet` + `SheetMorph` + `SheetIntent` + `SpringSettle`
 * and the row vocabulary is `ModalSheet` + `BasreliefSlider` in `android/app` and
 * `android/core`. The numbers are theirs rather than a web designer's:
 *
 *   - the card is the studio's **glass**, `0xEB161A20` — 92 % of `rgb(22,26,32)`,
 *     so the photograph's edge still continues underneath — with a 22px top
 *     corner and ONE top hairline (`0x24FFFFFF`), never a four-sided border;
 *   - the **strip** is 56dp, the grip band inside it 22dp with a 38×4 bar at
 *     `0x6BFFFFFF` centred 11dp down, and that strip IS the collapsed pill: the
 *     panel's height at fraction 1 is exactly it, so there is no second view and
 *     nothing to pop;
 *   - a **caption** is 11px, upper-case, `.09em` letter-spacing, `0x57FFFFFF`,
 *     16px above and 7px below (2px for the first one, under the strip);
 *   - the **three row roles, and no fourth**: a `switchRow` carries a state you
 *     can flip, an `actionRow` is a plate you can press, and a `readingRow` is
 *     information and cannot be pressed at all — its factory takes no listener,
 *     so a fact cannot drift into looking like a control;
 *   - a **slider** is one line: a fixed name column, a 34px track with the value
 *     printed on the accent fill, and a −/＋ pair — not a thumb, because "a stock
 *     bar over a painted fill reads as a different control wearing the same
 *     clothes" is exactly right.
 *
 * **Two hosts, one gesture.** `snap: 'free'` is the studio sheet: a persistent
 * panel that rests exactly where a release left it (the app's `Snap.FREE`), can be
 * dragged down to the pill, remembers its position across launches, and carries
 * the filmstrip, the fix line and the photo line as sheet content *outside* the
 * scroll. `snap: 'stops'` is a transient card — the Build 3D dialog — which must
 * land on a stop (open or dismissed), because a half-height card is an unanswered
 * question. The difference is injected rather than branched inside the release.
 *
 * **The morph is three functions, and they are not the same function.**
 *
 *   - `morphProgress` — the collapse fraction — drives the **height across the
 *     whole travel**. That is what dragging a bottom sheet down *is*.
 *   - `shapeProgress` — the same fraction, but flat until `SHAPE_BAND_START`
 *     (0.95) — drives the **width, the bottom lift, the corner radius and the
 *     fill**, in the last tenth only. A full-width panel at 40 % of its height is
 *     still a sheet, and rounding and tinting it towards a pill while it plainly
 *     is one reads as two objects. *the width should stay unchanged until the very end*
 *   - `edgeFade` — the content's **opacity**, in its own band pinned to the
 *     collapse threshold, so **no position the sheet can rest at is ever faded**.
 *
 * All of them, and the release rule, the tether and the spring, live in
 * `sheetmath.ts` where they are unit-tested without a browser. This module is the
 * DOM that calls them, and it computes no number of its own.
 *
 * **The gesture set is the panel's, not the grip's.** A vertical drag anywhere on
 * the panel moves the sheet — the strip, the padding, the fix line, a row — and a
 * scroll that has reached its top edge hands the gesture over, which is the
 * standard nested behaviour `BottomSheet.onIntercept` states. Only a control that
 * owns its own drag (a slider's track, a text field) is offered the gesture first.
 *
 * **Not a post-layout transform, either — but for a web reason, not Android's.**
 * The panel's position and size are `height`, `bottom`, side insets and
 * `border-radius`; the only transform is the entrance offset, which is zero at
 * rest. b/7179570 is a `SurfaceView` transparent-region limitation and does not
 * apply to a browser, but a layout channel is also the one that lets the height
 * BE the drag, and the entrance is the sole exception (it is an offset in time,
 * not a position).
 *
 * Deliberate departures from the phone, kept because they are better on a page:
 * the strip is a real `<button>` with `aria-expanded`, hidden content is `inert`,
 * every slider is keyboard-operable with `role="slider"` / `aria-valuenow`, the
 * scroll has `overscroll-behavior: contain`, and the panel is capped at 620px wide
 * on a desktop viewport (a 1600px sheet is not "edge to edge", it is a barn door).
 * The switch is hand-drawn rather than a platform `Switch`: there is no platform
 * switch to inherit, and the tint values are the app's own.
 */
import {
  DIALOG_POLICY,
  PILL_BOTTOM_INSET,
  PILL_PLATE,
  PILL_RADIUS,
  PILL_STROKE,
  SHEET_GLASS,
  SHEET_HAIRLINE,
  SHEET_POLICY,
  SHEET_RADIUS,
  STRIP_GRIP,
  STRIP_HEIGHT,
  WARN_COLOUR,
  blendColour,
  clamp01,
  cornerRadiusFor,
  cssColour,
  cssColourAlpha,
  decide,
  edgeFade,
  heightFor,
  insetFor,
  peekFraction,
  pillSideMarginFor,
  pillWidthFor,
  resistUp,
  shapeProgress,
  springCurve,
  springDuration,
} from './sheetmath.ts';

/** The vertical move, in px, past which a drag claims the sheet (8dp slop). */
const DRAG_SLOP = 8;

/** The panel's own width cap: a desktop viewport gets a phone-shaped card. */
const MAX_WIDTH = 620;

/** The panel's own ceiling, as a fraction of the viewport and in px. */
const MAX_CARD_FRACTION = 0.85;

/** The pill row's own chrome: 18+18 padding, an 8px dot and a 10px gap. */
const PILL_CHROME = 18 + 18 + 8 + 10;

/** What the pill says, and therefore how it is coloured. */
export type PillState = 'idle' | 'attention' | 'progress';

/** One tile of the filmstrip: a row of the library, and whether it is open. */
export interface StripEntry {
  id: string;
  name: string;
  /** The thumbnail's object URL, or null for a row that cannot be drawn. */
  url: string | null;
  active: boolean;
}

export interface SheetOptions {
  label: string;
  /**
   * `'free'` for the studio's persistent panel, `'stops'` for a transient card.
   * An argument rather than a default, exactly as `BottomSheet.Snap` is: the two
   * hosts want opposite things and a host that has not thought about it should not
   * silently get either.
   */
  snap?: 'free' | 'stops';
  /** The pill's line, which is what a collapsed studio sheet says. */
  pill?: string;
  /** The library, as the filmstrip under the strip. Studio sheets only. */
  strip?: readonly StripEntry[];
  /** A strip tile was chosen. */
  onPick?: (id: string) => void;
  /** `sheet_pos`: where the sheet rested last time, or null for nothing stored. */
  rest?: number | null;
  /** `sheet_open_pos`: where a pill tap returns to, or null. */
  open?: number | null;
  /** The sheet came to rest: its fraction, and whether it has gone away. */
  onResting?: (fraction: number, collapsed: boolean) => void;
  /** A `stops` sheet reached fully away. The host tears it down. */
  onDismiss?: () => void;
}

export interface Sheet {
  /** The bottom-anchored panel; the viewer appends it to the overlay. */
  element: HTMLElement;
  /** The scrolling part a screen adds rows to. */
  body: HTMLElement;
  /**
   * The dashed condition box under the strip, outside the scroll — the phone's fix
   * line. Outside on purpose: it is the one thing that must be readable at the
   * launch peek, when the foot of the sheet is still below the fold.
   */
  fixLine: HTMLElement;
  /** A card's buttons; the studio sheet has no footer and no Done. */
  footer: HTMLElement;
  /**
   * The phone's photo line: 12sp, 2/6 padding, one ellipsised line, the first
   * thing in the scroll above the first caption. `:empty` hides it.
   */
  photoLine: HTMLElement;
  collapsed(): boolean;
  /** Spring open to the last position the sheet rested at while open. */
  openLast(): void;
  /** The back gesture's one step out: to fully away. */
  collapse(): void;
  /** Re-place the sheet after its content changed height. No motion. */
  reapply(): void;
  /** What the collapsed pill says, and how it is coloured. */
  setPill(text: string, state?: PillState): void;
  /** Replace the filmstrip's tiles. */
  refreshStrip(entries: readonly StripEntry[]): void;
  destroy(): void;
}

export function createSheet(options: SheetOptions): Sheet {
  const studio = (options.snap ?? 'free') === 'free';
  const policy = studio ? SHEET_POLICY : DIALOG_POLICY;

  const element = document.createElement('section');
  element.className = 'sheet';
  element.setAttribute('aria-label', options.label);

  // ------------------------------------------------------------ the strip
  //
  // The panel's first child and the collapsed state itself: at fraction 1 the
  // panel's height IS this strip, so the pill needs no second view, no visibility
  // change and no lifecycle of its own.
  const strip = document.createElement('button');
  strip.type = 'button';
  strip.className = 'sheet-strip';
  strip.setAttribute('aria-label', 'Drag to move the panel');
  strip.setAttribute('aria-expanded', 'true');

  const gripBand = document.createElement('span');
  gripBand.className = 'sheet-gripband';
  const gripBar = document.createElement('span');
  gripBar.className = 'sheet-gripbar';
  gripBand.append(gripBar);
  strip.append(gripBand);

  const pill = document.createElement('span');
  pill.className = 'sheet-pill';
  const pillDot = document.createElement('span');
  pillDot.className = 'sheet-pill-dot';
  const pillLabel = document.createElement('span');
  pillLabel.className = 'sheet-pill-label';
  pillLabel.textContent = options.pill ?? 'Controls';
  pill.append(pillDot, pillLabel);
  // The label's own measurement, so the pill can be as wide as its words. Hidden
  // and nowrap: this is `TextView.measure` one platform over, and it must not be
  // influenced by the width the panel happens to have (which is the bug the app
  // records as "measure generously, then clamp").
  const pillMeasure = document.createElement('span');
  pillMeasure.className = 'sheet-pill-measure';
  pillMeasure.setAttribute('aria-hidden', 'true');
  pillMeasure.textContent = pillLabel.textContent;
  if (studio) strip.append(pill);
  strip.append(pillMeasure);

  // ----------------------------------------------------------- the content
  //
  // Everything below the strip, in one wrapper, because the fade is ONE value
  // applied to all of it: the strip is the pill and never fades, and the surface
  // underneath stays a surface.
  const content = document.createElement('div');
  content.className = 'sheet-content';

  /** The filmstrip: the library under the grip, above the fix line and the scroll. */
  const film = document.createElement('div');
  film.className = 'sheet-film';
  film.hidden = true;

  /** The fix line: at most one sentence and at most two actions. */
  const fixLine = document.createElement('div');
  fixLine.className = 'sheet-fixline';
  fixLine.hidden = true;

  /** The scroll. `body` is what a screen appends rows to; this is its clip. */
  const scroller = document.createElement('div');
  scroller.className = 'sheet-body';
  const inner = document.createElement('div');
  inner.className = 'sheet-body-inner';
  /** The phone's photo line: what this page is showing, above the first caption. */
  const photoLine = document.createElement('div');
  photoLine.className = 'sheet-photo';
  inner.append(photoLine);
  scroller.append(inner);

  const footer = document.createElement('div');
  footer.className = 'sheet-footer';

  content.append(film, fixLine, scroller, footer);
  element.append(strip, content);

  /** How far the pill floats off the foot: `env(safe-area-inset-bottom)`. */
  const safeProbe = document.createElement('div');
  safeProbe.className = 'sheet-safe-probe';
  safeProbe.setAttribute('aria-hidden', 'true');
  document.body.append(safeProbe);

  // ----------------------------------------------------------------- state
  let destroyed = false;
  let opened = false;
  let fraction = 0;
  let below = 0;
  let collapsed = false;
  let natural = 0;
  /** Where a pill tap returns to; the app's `lastResting`. */
  let lastResting: number | null = options.open ?? null;
  let pillState: PillState = 'idle';
  let frame = 0;
  let inertApplied = false;

  function reducedMotion(): boolean {
    return (
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    );
  }

  /** The navigation-bar inset, in px: the pill's 26dp is measured off THAT edge. */
  function safeBottom(): number {
    return safeProbe.offsetHeight || 0;
  }

  /** The width the sheet spans while open: the panel's container, capped. */
  function openWidth(): number {
    const parent = element.parentElement;
    const width = parent ? parent.clientWidth : window.innerWidth;
    return Math.max(0, Math.min(MAX_WIDTH, width));
  }

  /** The pill's width: what its label needs, capped by the inset and floored. */
  function pillWidth(): number {
    return pillWidthFor(pillMeasure.offsetWidth || 0, PILL_CHROME, openWidth());
  }

  /**
   * The panel's natural height: the strip plus everything under it, capped.
   *
   * A capped natural is this page's own decision (a desktop viewport would
   * otherwise get a 1400px sheet, and the phone's is the whole screen because it
   * is a phone); the fraction is still the fraction of the travel, so nothing
   * downstream knows the difference.
   */
  function measureNatural(): number {
    // Android's natural is the panel's own `wrap_content` height, bounded only by
    // the screen it lives in: `panelNaturalHeight = panel.getHeight()` read while
    // unconstrained. There is no fraction and no pixel ceiling anywhere in
    // `MainActivity`, so the studio sheet now uses the viewport and nothing else.
    // A transient CARD keeps its own cap: modal sheets are a different component
    // with a different job.
    const ceiling = studio ? window.innerHeight : window.innerHeight * MAX_CARD_FRACTION;
    const contentHeight =
      film.offsetHeight +
      (fixLine.hidden ? 0 : fixLine.offsetHeight) +
      inner.offsetHeight +
      footer.offsetHeight;
    return Math.max(STRIP_HEIGHT, Math.min(STRIP_HEIGHT + contentHeight, ceiling));
  }

  function span(): number {
    return Math.max(1, natural - STRIP_HEIGHT);
  }

  /** Mark the content operable or not. A hidden row a keyboard can reach is a lie. */
  function setInert(away: boolean): void {
    if (inertApplied === away) return;
    inertApplied = away;
    content.inert = away;
  }

  /**
   * Place the panel. Every number is a `sheetmath` answer; this is the one place
   * they become styles, and it is idempotent so a drag, a settle, a resize and a
   * content change all land here.
   */
  function apply(): void {
    if (natural <= 0) return;
    const height = Math.max(STRIP_HEIGHT, Math.round(heightFor(fraction, natural, STRIP_HEIGHT)));
    element.style.height = `${height}px`;
    element.style.transform = below === 0 ? '' : `translateY(${Math.round(below)}px)`;
    element.style.bottom = `${Math.round(insetFor(fraction, PILL_BOTTOM_INSET + safeBottom()))}px`;
    element.style.setProperty(
      '--sheet-inset',
      `${Math.round(pillSideMarginFor(fraction, openWidth(), pillWidth()))}px`,
    );

    const shape = shapeProgress(fraction);
    // The corner radius, all four corners, from the same function asked twice —
    // the top pair from the sheet's 22, the bottom pair from square — so the
    // collapsed shape is a capsule rather than a bar with two soft ends.
    const top = cornerRadiusFor(fraction, height, SHEET_RADIUS, PILL_RADIUS);
    const foot = cornerRadiusFor(fraction, height, 0, PILL_RADIUS);
    element.style.borderRadius = `${top}px ${top}px ${foot}px ${foot}px`;

    // The panel paints the glass BELOW the strip only. The strip is the panel's
    // top edge and paints its own fill, so a panel that painted the same plate
    // underneath would compose two coats of one colour and be visibly darker
    // across the top 56px. Both halves end at the same colour, so the seam is
    // invisible; what IS visible mid-morph is the strip's plate separating from
    // the sheet below it, which is the top edge becoming the pill.
    const glassAlpha = (((SHEET_GLASS >>> 24) & 0xff) / 255) * (1 - shape);
    element.style.backgroundImage =
      `linear-gradient(to bottom, rgba(0,0,0,0) 0 ${STRIP_HEIGHT}px, ` +
      `${cssColourAlpha(SHEET_GLASS, glassAlpha)} ${STRIP_HEIGHT}px)`;
    element.style.borderTopColor = cssColourAlpha(
      SHEET_HAIRLINE,
      ((((SHEET_HAIRLINE >>> 24) & 0xff) / 255) * (1 - shape)),
    );

    strip.style.background = cssColour(blendColour(SHEET_GLASS, PILL_PLATE, fraction));
    // The pill's outline is the strip's, and it is absent — not transparent —
    // while the sheet is open: the open sheet already draws its own hairline.
    const stroke = pillState === 'attention' ? WARN_COLOUR : PILL_STROKE;
    strip.style.boxShadow = `inset 0 0 0 1px ${cssColourAlpha(stroke, 0.2 * shape)}`;

    // The grip band retires by GEOMETRY and alpha together, so the pill's row is
    // the strip's leftover and the bar keeps the pill's vertical centre. An
    // invisible view that still occupies space is how the label ended up 32px
    // below the pill's centre on the phone.
    gripBand.style.height = `${Math.round(STRIP_GRIP * (1 - shape))}px`;
    gripBand.style.opacity = String(1 - shape);
    pill.style.opacity = String(shape);

    content.style.opacity = String(edgeFade(fraction));
  }

  function cancelMotion(): void {
    if (frame !== 0) {
      cancelAnimationFrame(frame);
      frame = 0;
    }
  }

  /** The resting state is decided; the host presents it and may store it. */
  function rest(): void {
    below = 0;
    collapsed = fraction >= 1;
    if (!collapsed) lastResting = fraction;
    apply();
    setInert(collapsed);
    strip.setAttribute('aria-expanded', String(!collapsed));
    options.onResting?.(fraction, collapsed);
    if (collapsed && !studio) options.onDismiss?.();
  }

  /** Animate to a fraction with the host's spring, starting from the release speed. */
  function settleTo(target: number, velocityPxPerS: number): void {
    cancelMotion();
    const to = clamp01(target);
    const from = fraction;
    if (Math.abs(to - from) < 1e-4 || reducedMotion() || natural <= 0) {
      fraction = to;
      rest();
      return;
    }
    const duration = springDuration(to - from, velocityPxPerS / span());
    const started = performance.now();
    const step = (now: number): void => {
      const t = Math.min(1, (now - started) / duration);
      fraction = from + (to - from) * springCurve(t);
      apply();
      if (t < 1) {
        frame = requestAnimationFrame(step);
      } else {
        frame = 0;
        fraction = to;
        rest();
      }
    };
    frame = requestAnimationFrame(step);
  }

  /**
   * A flick of the content, with the scroller's own decay and clamped at both ends.
   * It is the content's fling and not the sheet's: a list that coasts into its top
   * edge stops there, as a native scroller and `BottomSheet`'s own do.
   */
  function flingScroll(velocity: number): void {
    cancelMotion();
    const max = maxScroll();
    if (max <= 0 || Math.abs(velocity) < 80 || reducedMotion()) return;
    let v = velocity;
    let last = performance.now();
    const step = (now: number): void => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const next = scroller.scrollTop + v * dt;
      v *= Math.exp(-dt / 0.35);
      if (next <= 0) {
        scroller.scrollTop = 0;
        v = 0;
      } else if (next >= max) {
        scroller.scrollTop = max;
        v = 0;
      } else {
        scroller.scrollTop = next;
      }
      frame = v !== 0 && Math.abs(v) > 30 ? requestAnimationFrame(step) : 0;
    };
    frame = requestAnimationFrame(step);
  }

  /** The entrance: the whole resting surface slides up from below the window. */
  function animateBelowToZero(): void {
    const startBelow = below;
    if (startBelow <= 0 || reducedMotion()) {
      below = 0;
      apply();
      return;
    }
    const duration = springDuration(startBelow / Math.max(1, natural), 0);
    const started = performance.now();
    const step = (now: number): void => {
      const t = Math.min(1, (now - started) / duration);
      below = startBelow * (1 - springCurve(t));
      apply();
      if (t < 1) {
        frame = requestAnimationFrame(step);
      } else {
        frame = 0;
        below = 0;
        apply();
      }
    };
    frame = requestAnimationFrame(step);
  }

  /** Spring open to `at`, from below, and rest there. */
  function openAt(at: number): void {
    cancelMotion();
    fraction = clamp01(at);
    below = 0;
    collapsed = false;
    lastResting = fraction;
    natural = measureNatural();
    apply();
    setInert(false);
    strip.setAttribute('aria-expanded', 'true');
    options.onResting?.(fraction, false);
    below = heightFor(fraction, natural, STRIP_HEIGHT);
    apply();
    animateBelowToZero();
  }

  /** Place the sheet at rest with no motion: a launch restoring a stored state. */
  function restAt(at: number, away: boolean): void {
    cancelMotion();
    fraction = clamp01(at);
    below = 0;
    collapsed = away;
    natural = measureNatural();
    if (!away) lastResting = fraction;
    apply();
    setInert(away);
    strip.setAttribute('aria-expanded', String(!away));
    options.onResting?.(fraction, away);
  }

  function openLast(): void {
    openAt(lastResting ?? 0);
  }

  function setFraction(next: number): void {
    fraction = next;
    apply();
  }

  // ------------------------------------------------------------- the gesture
  //
  // One gesture for the whole panel. Android arbitrates between the scroll and the
  // sheet in `BottomSheet.onIntercept`; the same rule applies here, with the
  // browser's own scrolling as the scroll: a downward drag inside the content is
  // the scroll's while it has somewhere to go, and the sheet's at the top edge.
  interface Gesture {
    id: number;
    startY: number;
    startX: number;
    startFraction: number;
    lastY: number;
    lastAt: number;
    velocity: number;
    claimed: boolean;
    insideScroll: boolean;
    /** The content's `scrollTop` when the gesture began. */
    startScroll: number;
    /** The content's own velocity, px/s of `scrollTop` — the fling's seed. */
    scrollVelocity: number;
    /** The content used part of this gesture, so a release may fling it. */
    scrolled: boolean;
  }
  let gesture: Gesture | null = null;
  /** A claimed drag happened; the click the browser sends after it is not a tap. */
  let moved = false;
  /** Wheel movement rests and stores the sheet after the wheel gesture stops. */
  let wheelRestTimer = 0;

  /** The furthest the content can scroll. */
  function maxScroll(): number {
    return Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  }

  /**
   * Offer the drag to the content, and answer whether it took it.
   *
   * The scroll is driven here rather than by the browser, because with
   * `touch-action: pan-y` the scroller claims the touch at `pointerdown` and the
   * browser cancels the pointer as soon as it scrolls — so the one gesture Android
   * arbitrates in `BottomSheet.onIntercept`, a list dragged to its top and then
   * pulled further, could not exist. The rule is `scrollCanTake`'s and it is asked
   * on every move, which is what lets the handoff happen mid-gesture.
   */
  function scrollTo(g: Gesture, dy: number): boolean {
    const max = maxScroll();
    if (max <= 0) return false;
    // A finger moving down scrolls the content up, which DECREASES `scrollTop`.
    const want = g.startScroll - dy;
    // Past either end in that direction there is nothing left to scroll: the sheet
    // takes the rest of the gesture.
    if (want <= 0 && dy > 0) return false;
    if (want >= max && dy < 0) return false;
    scroller.scrollTop = Math.min(max, Math.max(0, want));
    g.scrolled = true;
    return true;
  }

  function claim(g: Gesture, event: PointerEvent): void {
    // The anchor is rebased to this event, so a scroll that has just reached its
    // end does not make the panel jump by the distance the scroll travelled, and
    // the touch slop does not make it jump either.
    cancelMotion();
    g.claimed = true;
    g.startY = event.clientY;
    g.startFraction = fraction;
    g.lastY = event.clientY;
    element.classList.add('sheet--dragging');
    try {
      element.setPointerCapture(event.pointerId);
    } catch {
      // A synthetic pointer cannot be captured; the moves still arrive.
    }
    event.preventDefault();
  }

  const onPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0 || gesture !== null) return;
    // A control that owns its own drag is offered the gesture first — the app's
    // `requestDisallowInterceptTouchEvent(true)` on a slider's track.
    if (
      event.target instanceof Element &&
      event.target.closest('.bslider-track, .sheet-input') !== null
    ) {
      return;
    }
    // A new touch stops a fling: the content should answer the finger, not coast
    // under it.
    cancelMotion();
    gesture = {
      id: event.pointerId,
      startY: event.clientY,
      startX: event.clientX,
      startFraction: fraction,
      lastY: event.clientY,
      lastAt: event.timeStamp,
      velocity: 0,
      claimed: false,
      insideScroll: event.target instanceof Node && scroller.contains(event.target),
      startScroll: scroller.scrollTop,
      scrollVelocity: 0,
      scrolled: false,
    };
  };

  const onPointerMove = (event: PointerEvent): void => {
    const g = gesture;
    if (g === null || event.pointerId !== g.id) return;
    const dt = Math.max(1, event.timeStamp - g.lastAt) / 1000;
    g.velocity = (event.clientY - g.lastY) / dt;
    g.scrollVelocity = -g.velocity;
    g.lastY = event.clientY;
    g.lastAt = event.timeStamp;
    const dy = event.clientY - g.startY;
    const dx = event.clientX - g.startX;
    if (!g.claimed) {
      if (Math.abs(dy) <= DRAG_SLOP) return;
      // A drag that is not clearly vertical belongs to the filmstrip's horizontal
      // scroll, exactly as `BottomSheet.mostlyVertical` decides.
      if (Math.abs(dy) <= Math.abs(dx)) return;
      // The scroll is offered the direction first and keeps the gesture while it
      // can still move. At either edge — or once it has been dragged there — the
      // sheet takes the rest, which is the handoff a native scroller cannot do.
      if (g.insideScroll && scrollTo(g, dy)) return;
      claim(g, event);
    }
    const travel = span();
    const raw = g.startFraction + (event.clientY - g.startY) / travel;
    // Tethered above fully open, hard-clamped at fully away: past open there is a
    // little glass to give, past closed there is nothing.
    setFraction(raw < 0 ? resistUp(raw * travel, travel) / travel : Math.min(1, raw));
  };

  const finishGesture = (event: PointerEvent, cancelled: boolean): void => {
    const g = gesture;
    if (g === null || event.pointerId !== g.id) return;
    gesture = null;
    if (!g.claimed) {
      // The content's own flick, with the content's own decay. A cancel is not a
      // flick: nothing is invented from a gesture the system took away.
      if (!cancelled && g.scrolled) flingScroll(g.scrollVelocity);
      return;
    }
    element.classList.remove('sheet--dragging');
    if (element.hasPointerCapture(event.pointerId)) {
      element.releasePointerCapture(event.pointerId);
    }
    if (cancelled) {
      // A cancelled drag stays where it is: the picture on screen is what was
      // already stored, and inventing a decision from a broken gesture is worse.
      rest();
      return;
    }
    const decision = decide(fraction, g.velocity / span(), policy);
    if (decision === 'collapse') {
      settleTo(1, g.velocity);
    } else if (!studio || fraction < 0) {
      // A card's EXPAND and its dead band both mean "back up" — there is no free
      // position for a transient card to stay in. A studio sheet keeps exactly
      // where the finger left it (`Snap.FREE`), with one exception: a **negative**
      // fraction is the over-open tether and there is no rest position above fully
      // open, so it falls back. That is what the tether is for.
      settleTo(0, g.velocity);
    } else {
      rest();
    }
    // The browser dispatches the click that ends a tap AFTER this handler. The
    // flag is cleared on the next task, so a drag cannot leave it set and swallow
    // the next genuine tap.
    moved = true;
    window.setTimeout(() => {
      moved = false;
    }, 0);
  };

  const onPointerUp = (event: PointerEvent): void => finishGesture(event, false);
  const onPointerCancel = (event: PointerEvent): void => finishGesture(event, true);

  // A drag is not a press: the click the browser sends at the end of one is
  // swallowed here, before a row's own handler can see it. Android consumes the
  // whole gesture in `onTouch`, which is the same rule by another mechanism.
  const onClickCapture = (event: MouseEvent): void => {
    if (!moved) return;
    event.preventDefault();
    event.stopPropagation();
  };

  const onStripClick = (): void => {
    if (collapsed) openLast();
    else settleTo(1, 0);
  };

  element.addEventListener('pointerdown', onPointerDown);
  element.addEventListener('pointermove', onPointerMove);
  element.addEventListener('pointerup', onPointerUp);
  element.addEventListener('pointercancel', onPointerCancel);
  element.addEventListener('click', onClickCapture, true);
  strip.addEventListener('click', onStripClick);

  /**
   * The wheel is the desktop analogue of the touch drag.
   *
   * It scrolls while the content has somewhere to go; at the top/bottom it hands
   * off to the sheet, exactly as `onPointerMove` does for a finger. A wheel up at
   * the top pulls the sheet down; a wheel down at the bottom pulls it up. The
   * transient build card keeps native scrolling, because a half-height dialog is
   * not a rest state.
   */
  const onWheel = (event: WheelEvent): void => {
    if (!studio || event.ctrlKey || event.metaKey || event.deltaY === 0) return;
    const insideScroll = event.target instanceof Node && scroller.contains(event.target);
    const max = maxScroll();
    if (insideScroll && max > 0) {
      if (event.deltaY > 0 && scroller.scrollTop < max - 0.5) return;
      if (event.deltaY < 0 && scroller.scrollTop > 0.5) return;
    }
    event.preventDefault();
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? window.innerHeight : 1;
    const next = clamp01(fraction + (-event.deltaY * unit) / 1200);
    if (next === fraction) return;
    cancelMotion();
    setFraction(next);
    window.clearTimeout(wheelRestTimer);
    wheelRestTimer = window.setTimeout(() => {
      wheelRestTimer = 0;
      if (!destroyed) rest();
    }, 160);
  };
  element.addEventListener('wheel', onWheel, { passive: false });

  // --------------------------------------------------------------- the content

  const observer = new ResizeObserver(() => {
    if (destroyed || !opened) return;
    const next = measureNatural();
    if (Math.abs(next - natural) < 0.5) return;
    natural = next;
    apply();
  });
  for (const node of [film, fixLine, inner, footer]) observer.observe(node);

  const onResize = (): void => {
    if (destroyed || !opened) return;
    natural = measureNatural();
    apply();
  };
  window.addEventListener('resize', onResize);

  function setPill(text: string, state: PillState = 'idle'): void {
    pillState = state;
    pillLabel.textContent = text;
    pillMeasure.textContent = text;
    pillDot.classList.toggle('is-ring', state === 'progress');
    // The dot, the label and the outline are one state: the phone recolours all
    // three from `StripState`, because the pill is the only thing on screen when
    // the sheet is away.
    strip.classList.toggle('is-attention', state === 'attention');
    // The pill's width is its label's, so a new line is a new width.
    if (opened) apply();
  }

  function refreshStrip(entries: readonly StripEntry[]): void {
    film.replaceChildren();
    film.hidden = entries.length === 0;
    for (const entry of entries) {
      const tile = document.createElement('button');
      tile.type = 'button';
      tile.className = 'sheet-film-tile';
      tile.classList.toggle('is-active', entry.active);
      if (entry.active) tile.setAttribute('aria-current', 'true');
      tile.setAttribute('aria-label', `Show ${entry.name}`);
      if (entry.url !== null) {
        const image = document.createElement('img');
        image.src = entry.url;
        image.alt = '';
        image.draggable = false;
        tile.append(image);
      }
      tile.addEventListener('click', () => options.onPick?.(entry.id));
      film.append(tile);
    }
  }

  if (options.strip !== undefined) refreshStrip(options.strip);
  if (options.pill !== undefined) setPill(options.pill);
  // A card has no pill: its strip is the handle and nothing else.
  if (!studio) strip.classList.add('sheet-strip--plain');

  // Open at the stored position, laid out against the content it actually got: a
  // sheet sized before its rows exist would open at the strip and grow a frame
  // later, and the first frame is the one the entrance starts from.
  requestAnimationFrame(() => {
    if (destroyed) return;
    natural = measureNatural();
    opened = true;
    if (options.rest !== null && options.rest !== undefined && options.rest >= 1) {
      restAt(1, true);
      return;
    }
    // A studio sheet opens at the peek on a fresh install; a transient card has no
    // peek — it opens at its own height, because a dialog shown at a third of the
    // screen would be a dialog with its buttons below the fold.
    const at =
      options.rest !== null && options.rest !== undefined && options.rest >= 0
        ? options.rest
        : studio
          ? peekFraction(window.innerHeight, natural)
          : 0;
    openAt(at);
  });

  // Android dims the sheet's plate while a control is held (`dimWhileInteracting`,
  // DIM_ALPHA = 0.05) so the render shows through the finger. The `:active`-based
  // rule that preceded this failed exactly as the user reported: a finger that
  // leaves the track loses `:active` and the plate snaps back to opaque mid-drag,
  // and it never fired for switches, segments or nudges at all. A pointer counter
  // is the honest version: it follows the pointer, not the element, and it covers
  // every control that owns its own gesture.
  const dimmed = new Set<number>();
  // The studio sheet dims while a slider is held so the picture behind shows
  // through. A transient dialog is a card over a scrim, not a sheet over the
  // picture: dimming its own controls would make the question harder to read.
  function syncDim() { element.classList.toggle('sheet--dimmed', studio && dimmed.size > 0); }
  // Sliders only. `dimWhileInteracting` is called with a `BasreliefSlider` in the
  // app -- a switch or a button does not own a drag, so there is nothing for the
  // render to show through and dimming would just flash the sheet on every tap.
  const heldTrack = (target: EventTarget | null): HTMLElement | null =>
    target instanceof Element ? (target.closest('.bslider-track') as HTMLElement | null) : null;
  /** The row being dragged: the one thing that stays legible (`HELD_ALPHA`). */
  let heldRow: HTMLElement | null = null;
  element.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    const track = heldTrack(event.target);
    if (track === null) return;
    if (dimmed.size === 0) {
      heldRow = track.closest('.bslider') as HTMLElement | null;
      heldRow?.classList.add('is-held');
    }
    dimmed.add(event.pointerId);
    syncDim();
  }, true);
  const release = (event: PointerEvent) => {
    dimmed.delete(event.pointerId);
    if (dimmed.size === 0) {
      heldRow?.classList.remove('is-held');
      heldRow = null;
    }
    syncDim();
  };
  // On WINDOW, not the panel: a pointer that lifts outside the sheet (dragging a
  // slider to the screen edge, or off the panel entirely) never bubbles a
  // `pointerup` through the element, so the dim would stick until the next
  // interaction. The pointer is the subject, so the window is the listener.
  window.addEventListener('pointerup', release, true);
  window.addEventListener('pointercancel', release, true);

  const removeDim = (): void => {
    window.removeEventListener('pointerup', release, true);
    window.removeEventListener('pointercancel', release, true);
  };
  return {
    element,
    body: inner,
    fixLine,
    footer,
    photoLine,
    collapsed: () => collapsed,
    openLast,
    collapse: () => settleTo(1, 0),
    reapply(): void {
      if (destroyed) return;
      natural = measureNatural();
      apply();
    },
    setPill,
    refreshStrip,
    destroy(): void {
      destroyed = true;
      cancelMotion();
      if (wheelRestTimer !== 0) {
        window.clearTimeout(wheelRestTimer);
        wheelRestTimer = 0;
      }
      observer.disconnect();
      window.removeEventListener('resize', onResize);
      element.removeEventListener('pointerdown', onPointerDown);
      element.removeEventListener('pointermove', onPointerMove);
      element.removeEventListener('pointerup', onPointerUp);
      element.removeEventListener('pointercancel', onPointerCancel);
      element.removeEventListener('wheel', onWheel);
      removeDim();
      element.removeEventListener('click', onClickCapture, true);
      strip.removeEventListener('click', onStripClick);
      safeProbe.remove();
      element.remove();
    },
  };
}

// ------------------------------------------------------------------ the rows

/** `.caption` — 11px, caps, .09em, --dimmer, 16 above and 7 below. */
export function caption(text: string, first = false): HTMLElement {
  const element = document.createElement('div');
  element.className = first ? 'sheet-caption sheet-caption--first' : 'sheet-caption';
  element.textContent = text;
  return element;
}

/** `.note` — 11px, --dimmer, centred, 7px above. */
export function note(text: string): HTMLElement {
  const element = document.createElement('div');
  element.className = 'sheet-note';
  element.textContent = text;
  return element;
}

/**
 * A row that is information: a title and a subtitle, no border, no pressed
 * state, quieter ink. There is deliberately no listener parameter — a reading
 * that cannot be handed a tap cannot drift into being one.
 */
export function readingRow(title: string, sub?: string): HTMLElement {
  const element = document.createElement('div');
  element.className = 'sheet-reading';
  const heading = document.createElement('div');
  heading.className = 'sheet-reading-title';
  heading.textContent = title;
  element.append(heading);
  if (sub) {
    const detail = document.createElement('div');
    detail.className = 'sheet-reading-sub';
    detail.textContent = sub;
    element.append(detail);
  }
  return element;
}

export interface ActionRowOptions {
  label: string;
  sub?: string;
  /** True when the tap goes somewhere; a confirmation is not a destination. */
  chevron?: boolean;
  onClick: () => void;
}

/**
 * A row you can tap: a bordered ghost plate, its label centred and a pressed
 * state. The plate IS the affordance — a bordered row with no mark is the same
 * picture as a fact in a bordered row, which is why the reading row above has no
 * border at all.
 */
export function actionRow(options: ActionRowOptions): HTMLButtonElement {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'sheet-action';
  const text = document.createElement('span');
  text.className = 'sheet-action-text';
  const label = document.createElement('span');
  label.className = 'sheet-action-label';
  label.textContent = options.label;
  text.append(label);
  if (options.sub) {
    const sub = document.createElement('span');
    sub.className = 'sheet-action-sub';
    sub.textContent = options.sub;
    text.append(sub);
  }
  row.append(text);
  if (options.chevron) {
    const arrow = document.createElement('span');
    arrow.className = 'sheet-action-chevron';
    arrow.setAttribute('aria-hidden', 'true');
    arrow.textContent = '›';
    row.append(arrow);
  }
  row.addEventListener('click', options.onClick);
  return row;
}

export interface SwitchRow {
  element: HTMLElement;
  set(on: boolean): void;
  /** The row's subtitle, for a capability that is discovered rather than given. */
  setSub(text: string): void;
  /** Disable a capability the browser has said it cannot provide. */
  setDisabled(disabled: boolean): void;
}

export interface SwitchRowOptions {
  title: string;
  sub: string;
  on: boolean;
  disabled?: boolean;
  onToggle: (on: boolean) => void;
}

/**
 * A row that carries a state you can flip.
 *
 * The whole row is the control and the switch is a mark inside it, not a second
 * tap target: two live targets give one control two announcements and let a tap
 * on the switch fall through to the row as a second toggle. The switch is
 * trailing because that is where a hand reaches for one; the leading slot is
 * where the design draws its *swatch*, which is a picture of a state rather than
 * a control.
 */
export function switchRow(options: SwitchRowOptions): SwitchRow {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'sheet-switch';
  row.setAttribute('aria-pressed', String(options.on));
  row.disabled = options.disabled === true;
  row.classList.toggle('is-disabled', row.disabled);

  const text = document.createElement('span');
  text.className = 'sheet-switch-text';
  const title = document.createElement('span');
  title.className = 'sheet-switch-title';
  title.textContent = options.title;
  const sub = document.createElement('span');
  sub.className = 'sheet-switch-sub';
  sub.textContent = options.sub;
  text.append(title, sub);

  const track = document.createElement('span');
  track.className = 'sheet-switch-track';
  track.setAttribute('aria-hidden', 'true');
  const thumb = document.createElement('span');
  thumb.className = 'sheet-switch-thumb';
  track.append(thumb);

  row.append(text, track);

  let on = options.on;
  const paint = (next: boolean): void => {
    on = next;
    row.classList.toggle('is-on', on);
    row.setAttribute('aria-pressed', String(on));
  };
  paint(on);

  row.addEventListener('click', () => {
    paint(!on);
    options.onToggle(on);
  });

  return {
    element: row,
    set(next: boolean): void {
      // Silent by construction: populating a control must never write the state
      // it is only trying to display.
      paint(next);
    },
    setSub(next: string): void {
      sub.textContent = next;
    },
    setDisabled(next: boolean): void {
      row.disabled = next;
      row.classList.toggle('is-disabled', next);
    },
  };
}

export interface SliderRow {
  element: HTMLElement;
  set(value: number): void;
}

export interface SliderRowOptions {
  label: string;
  value: number;
  min: number;
  max: number;
  /** How the value is written: `%`, `°`, or a signed offset. */
  format: (value: number) => string;
  /** Every change, live — the renderer follows the finger. */
  onInput: (value: number) => void;
  /**
   * The value to keep, once the user has let go.
   *
   * The app's rule for every slider on its sheet is "preview while dragging,
   * commit on release": a page that stored on every input would rewrite its
   * library at the drag's frame rate. A nudge and a keyboard step are discrete
   * committed edits, so they call this too — they have no drag to end.
   */
  onRelease?: (value: number) => void;
  /** A row whose value nothing on this page can change: drawn, inert, honest. */
  disabled?: boolean;
}

/** `BasreliefSlider`'s geometry lives in `styles.css` (66px name, 34px track,
 * 28×30 nudges) because it is presentation; the drag sensitivity does not. */
const VALUE_PAD = 11;
/** Below this much fill the value is drawn white on the grey instead of ink. */
const INSIDE_FRACTION = 0.12;
/** Full sweep of a range, in px, when the step cap does not bind. */
const FULL_SWEEP_PX = 420;
/** The most travel one unit may demand on a short range. */
const MAX_STEP_PX = 40;
/** The platform's touch slop, so a tap that shivers is still a tap. */
const SLOP = 3;

/**
 * One adjustment on one line: its name, a filled track with the value printed
 * on it, and a pair of nudges.
 *
 * A wheel, not a place on the bar: a drag moves the value by how far the finger
 * travels, and a bound resets the measure so hitting the end and continuing does
 * not wind up. A tap has no direction to take a step in, so it changes nothing;
 * the −/＋ pair is the step. Keyboard users get the same steps on the focused
 * track, so the control is not drag-only.
 */
export function sliderRow(options: SliderRowOptions): SliderRow {
  const min = Math.min(options.min, options.max);
  const max = Math.max(options.min, options.max);
  // Range-scaled, exactly as the app derives it: a full sweep is one comfortable
  // drag rather than a different gesture per row. Zoom's 175 units and Depth's
  // 100 then feel the same, and a short range is capped so it stays crossable.
  const pxPerUnit = Math.min(MAX_STEP_PX, FULL_SWEEP_PX / Math.max(1, max - min));

  const row = document.createElement('div');
  row.className = options.disabled ? 'bslider is-disabled' : 'bslider';

  const label = document.createElement('span');
  label.className = 'bslider-label';
  label.textContent = options.label;

  const track = document.createElement('div');
  track.className = 'bslider-track';
  track.tabIndex = options.disabled ? -1 : 0;
  track.setAttribute('role', 'slider');
  track.setAttribute('aria-label', options.label);
  track.setAttribute('aria-valuemin', String(min));
  track.setAttribute('aria-valuemax', String(max));
  if (options.disabled) track.setAttribute('aria-disabled', 'true');

  const fill = document.createElement('div');
  fill.className = 'bslider-fill';
  const printed = document.createElement('span');
  printed.className = 'bslider-value';
  track.append(fill, printed);

  const nudges = document.createElement('div');
  nudges.className = 'bslider-nudges';
  const down = nudge('−', -1);
  const up = nudge('＋', +1);
  nudges.append(down, up);

  row.append(label, track, nudges);

  let shown = clamp(options.value);
  let lastText = '';
  let lastWidth = 0;

  function paint(value: number): void {
    shown = value;
    const fraction = max > min ? (value - min) / (max - min) : 0;
    const text = options.format(value);
    fill.style.width = `${fraction * 100}%`;
    printed.textContent = text;
    if (text !== lastText || lastWidth === 0) {
      lastText = text;
      lastWidth = printed.offsetWidth;
    }
    const filledPx = track.clientWidth * fraction;
    // Both tests, not either: a fraction past the threshold on a narrow track is
    // still a fill narrower than the number, and near-black ink running off the
    // accent is the illegibility the threshold exists to prevent.
    const onFill = fraction >= INSIDE_FRACTION && filledPx >= lastWidth + VALUE_PAD * 2;
    printed.classList.toggle('is-on-fill', onFill);
    track.setAttribute('aria-valuenow', String(value));
    track.setAttribute('aria-valuetext', text);
    track.setAttribute('aria-valuemin', String(min));
    track.setAttribute('aria-valuemax', String(max));
  }

  function move(value: number): void {
    if (options.disabled) return;
    const next = clamp(value);
    if (next === shown) return;
    paint(next);
    options.onInput(next);
  }

  /** The edit is over: hand the kept value to whoever stores it. */
  function commit(): void {
    if (options.disabled) return;
    options.onRelease?.(shown);
  }

  function nudge(glyph: string, direction: number): HTMLButtonElement {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'bslider-nudge';
    button.textContent = glyph;
    button.setAttribute('aria-label', `${options.label} ${glyph}`);
    button.disabled = options.disabled === true;
    button.addEventListener('click', () => {
      const before = shown;
      move(shown + direction);
      if (shown !== before) commit();
    });
    return button;
  }

  function clamp(value: number): number {
    if (!Number.isFinite(value)) return min;
    return value < min ? min : value > max ? max : Math.round(value);
  }

  // ------------------------------------------------------------------ the drag
  let dragging = false;
  let pointerId = -1;
  let downX = 0;
  let anchorX = 0;
  let anchorValue = 0;
  let pressedAt = 0;

  track.addEventListener('pointerdown', (event) => {
    if (options.disabled || event.button !== 0) return;
    pointerId = event.pointerId;
    dragging = true;
    downX = event.clientX;
    anchorX = downX;
    anchorValue = shown;
    pressedAt = shown;
    try {
      track.setPointerCapture(event.pointerId);
    } catch {
      // A synthetic pointer cannot be captured; a real one always can.
    }
    event.preventDefault();
  });

  track.addEventListener('pointermove', (event) => {
    if (!dragging || event.pointerId !== pointerId) return;
    const dx = event.clientX - downX;
    if (Math.abs(dx) < SLOP) return;
    if (anchorX === downX) {
      // Anchor at the slop boundary so there is no jump when the drag starts and
      // the travel carried by the claiming event is not lost.
      anchorX = downX + (dx > 0 ? SLOP : -SLOP);
      anchorValue = shown;
    }
    const next = anchorValue + Math.round((event.clientX - anchorX) / pxPerUnit);
    if (next < min || next > max) {
      // A stop, not a wind-up: discard travel past the end and measure from the
      // end itself, so reversing moves the value at once.
      anchorValue = next < min ? min : max;
      anchorX = event.clientX;
      move(anchorValue);
      return;
    }
    move(next);
  });

  const endDrag = (event: PointerEvent): void => {
    if (!dragging || event.pointerId !== pointerId) return;
    dragging = false;
    if (track.hasPointerCapture(event.pointerId)) track.releasePointerCapture(event.pointerId);
    // A cancelled drag returns to where it began: the renderer followed the
    // preview, so leaving the bar showing a value nothing holds would be a second
    // thing on screen the picture does not agree with.
    if (event.type === 'pointercancel' && shown !== pressedAt) {
      move(pressedAt);
      return; // the value on screen is the one that was already stored
    }
    // A release that moved nothing writes nothing — the same rule
    // `ReliefRenderer.saveFramingToPrefs` follows for a gesture.
    if (shown !== pressedAt) commit();
  };
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);

  // The keyboard is the same steps the nudges are, so a control with no thumb is
  // still operable without a drag.
  track.addEventListener('keydown', (event) => {
    if (options.disabled) return;
    const before = shown;
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowDown':
        move(shown - 1);
        break;
      case 'ArrowRight':
      case 'ArrowUp':
        move(shown + 1);
        break;
      case 'PageDown':
        move(shown - 10);
        break;
      case 'PageUp':
        move(shown + 10);
        break;
      case 'Home':
        move(min);
        break;
      case 'End':
        move(max);
        break;
      default:
        return;
    }
    event.preventDefault();
    if (shown !== before) commit();
  });

  // Laid out after it is in the document: the "is the fill wide enough for the
  // number" test reads the track's width, which is zero until then.
  requestAnimationFrame(() => {
    if (track.isConnected) paint(shown);
  });
  paint(shown);

  return {
    element: row,
    set(value: number): void {
      // Inert, like the app's `setValue`: no listener, nothing to forget, so a
      // refresh cannot write the value it is only displaying.
      paint(clamp(value));
    },
  };
}

/** `.btn` — a plate, filled and primary or ghost. */
export function sheetButton(
  label: string,
  primary: boolean,
  onClick: () => void,
): HTMLButtonElement {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = primary ? 'sheet-btn sheet-btn--primary' : 'sheet-btn sheet-btn--ghost';
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
}

/** One button of a two-option segment (the Home/Lock and Renderer rows). */
export interface SegmentRowOptions {
  labels: readonly [string, string];
  /** Which option is marked as current, or -1 for neither. */
  active: number;
  /** A segment nothing on this page can move: drawn, both options inert. */
  disabled?: boolean;
  /**
   * Options that cannot act even though the segment can. The Renderer segment on
   * a cloud-only card is the case: Splat is a real choice, and Relief is not.
   */
  disabledIndices?: readonly number[];
  onSelect?: (index: number) => void;
}

/**
 * The studio's two-button track, used by the Home/Lock and Renderer segments.
 *
 * A segment is a mode selector, not a switch row: both options are always
 * visible and the marked one is the current state. When `disabled`, it is still
 * drawn — a missing control with no sentence is indistinguishable from one that
 * was forgotten — and the caption/reading row beside it says why.
 */
export function segmentRow(options: SegmentRowOptions): HTMLElement {
  const segment = document.createElement('div');
  segment.className = 'sheet-segment';
  options.labels.forEach((label, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'sheet-segment-btn';
    button.textContent = label;
    button.disabled =
      options.disabled === true || options.disabledIndices?.includes(index) === true;
    button.classList.toggle('is-active', index === options.active);
    button.setAttribute('aria-pressed', String(index === options.active));
    if (options.onSelect) button.addEventListener('click', () => options.onSelect?.(index));
    segment.append(button);
  });
  return segment;
}

/**
 * A text field on a plate. Used by Generate depth for the ComfyUI base URL: the
 * studio writes that address to its prefs, and this page writes the app's own
 * `comfy_url` to `prefs.json` beside the library — an address is not a render
 * value, so it has no row to live on.
 */
export function inputRow(options: {
  label: string;
  value: string;
  placeholder?: string;
  inputMode?: 'url' | 'text';
  onInput: (value: string) => void;
}): { element: HTMLElement; input: HTMLInputElement } {
  const element = document.createElement('label');
  element.className = 'sheet-input-row';
  const label = document.createElement('span');
  label.className = 'sheet-input-label';
  label.textContent = options.label;
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'sheet-input';
  input.value = options.value;
  input.placeholder = options.placeholder ?? '';
  input.autocomplete = 'off';
  input.spellcheck = false;
  if (options.inputMode) input.inputMode = options.inputMode;
  input.addEventListener('input', () => options.onInput(input.value));
  element.append(label, input);
  return { element, input };
}
