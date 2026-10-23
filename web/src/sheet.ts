/**
 * The studio's bottom sheet, reproduced for a page.
 *
 * The reference is `ModalSheet.java` + `SettingsSheet.java` + `BasreliefSlider.java`
 * in `android/app`, and the vocabulary is deliberately theirs rather than a web
 * designer's:
 *
 *   - the card is `#171b21` with a 20px top corner and a single top hairline
 *     (`0x24FFFFFF`), never a four-sided border;
 *   - a **caption** is 11px, upper-case, `.09em` letter-spacing, `0x57FFFFFF`,
 *     16px above and 7px below (4px for the first one, which sits under the grip);
 *   - the **three row roles, and no fourth**: a `switchRow` carries a state you
 *     can flip, an `actionRow` is a plate you can press, and a `readingRow` is
 *     information and cannot be pressed at all — its factory takes no listener,
 *     so a fact cannot drift into looking like a control;
 *   - the **grip** is the sheet's discoverable way out and the thing you drag:
 *     a 38×4 bar at `rgba(255,255,255,.42)` on a 30px strip;
 *   - a **slider** is one line: a fixed name column, a 34px track with the value
 *     printed on the accent fill, and a −/＋ pair — not a thumb, because "a stock
 *     bar over a painted fill reads as a different control wearing the same
 *     clothes" is exactly right.
 *
 * **Only the rows that can act are drawn.** This page has no wallpaper, no
 * library and no second surface, so the Home/Lock segment, the playlists, the
 * archive and the Renderer segment are omitted — and each omission is stated in
 * a reading row, because a missing control with no sentence is indistinguishable
 * from one that was forgotten. The viewer composes the rows; this module owns the
 * vocabulary and the geometry, exactly as `ModalSheet` does on the phone.
 *
 * The drag's release rule is `SheetIntent`'s: a 0.20 s projection of the release
 * velocity with a 1.2-travels-per-second fling override, under the studio's
 * 0.95/0.40 thresholds. A web sheet has two resting states rather than the
 * studio's free position, so a release inside the dead band settles to the
 * nearer one instead of parking.
 */

/** `SheetIntent.PROJECTION_SECONDS`: the momentum a hand expects carried. */
const PROJECTION_SECONDS = 0.2;
/** `SheetIntent.FLING_TRAVEL_PER_SECOND`: a decisive throw decides by itself. */
const FLING_TRAVEL_PER_SECOND = 1.2;
/** `SheetIntent.SHEET`: collapse only near the bottom; expand below 0.40. */
const COLLAPSE_THRESHOLD = 0.95;
const EXPAND_THRESHOLD = 0.4;

/** `.grip{height:22px}` plus the card's 8px of breathing room. */
const GRIP_HEIGHT = 30;
/** The tallest the sheet may get, as a fraction of the viewport. */
const MAX_HEIGHT_FRACTION = 0.7;

export interface Sheet {
  /** The bottom-anchored panel; the viewer appends it to the overlay. */
  element: HTMLElement;
  /** The scrolling part a screen adds rows to. */
  body: HTMLElement;
  /** The foot, outside the scroll so its buttons are reachable at any height. */
  footer: HTMLElement;
  collapsed(): boolean;
  expand(): void;
  collapse(): void;
  destroy(): void;
}

export function createSheet(options: { label: string }): Sheet {
  const element = document.createElement('section');
  element.className = 'sheet sheet--open';
  element.setAttribute('aria-label', options.label);

  const grip = document.createElement('button');
  grip.type = 'button';
  grip.className = 'sheet-grip';
  grip.setAttribute('aria-label', 'Drag to move the panel');
  grip.setAttribute('aria-expanded', 'true');
  const bar = document.createElement('span');
  bar.className = 'sheet-grip-bar';
  grip.append(bar);

  const body = document.createElement('div');
  body.className = 'sheet-body';

  const footer = document.createElement('div');
  footer.className = 'sheet-footer';

  element.append(grip, body, footer);

  let isCollapsed = false;
  let destroyed = false;

  /** The natural height: grip, the content's own height, and the foot. */
  function expandedHeight(): number {
    const cap = Math.min(
      window.innerHeight * MAX_HEIGHT_FRACTION,
      // A wide desktop phone-shaped card still should not become a column.
      620,
    );
    const content = GRIP_HEIGHT + body.scrollHeight + footer.offsetHeight;
    return Math.max(GRIP_HEIGHT + footer.offsetHeight, Math.min(content, cap));
  }

  function setHeight(px: number): void {
    element.style.height = `${Math.max(GRIP_HEIGHT, Math.round(px))}px`;
  }

  function expand(): void {
    if (destroyed) return;
    isCollapsed = false;
    element.classList.remove('sheet--collapsed');
    element.classList.add('sheet--open');
    grip.setAttribute('aria-expanded', 'true');
    body.inert = false;
    footer.inert = false;
    setHeight(expandedHeight());
  }

  function collapse(): void {
    if (destroyed) return;
    isCollapsed = true;
    element.classList.add('sheet--collapsed');
    element.classList.remove('sheet--open');
    grip.setAttribute('aria-expanded', 'false');
    // Clipped, not merely short: a hidden row that a keyboard can still reach is
    // a control the user cannot see and can still operate.
    body.inert = true;
    footer.inert = true;
    setHeight(GRIP_HEIGHT);
  }

  // ------------------------------------------------------------------- the drag
  //
  // The grip is a layout-height drag, not a transform: the sheet is a real
  // element in the page, and moving it by `height` is also what keeps its foot
  // pinned to the viewport's.
  let dragging = false;
  let moved = false;
  let pointerId = -1;
  let startY = 0;
  let startHeight = 0;
  let fullHeight = 0;
  let lastY = 0;
  let lastAt = 0;
  let velocity = 0;

  const onPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0) return;
    dragging = true;
    moved = false;
    pointerId = event.pointerId;
    startY = event.clientY;
    lastY = event.clientY;
    lastAt = event.timeStamp;
    velocity = 0;
    startHeight = element.getBoundingClientRect().height;
    fullHeight = Math.max(startHeight, expandedHeight());
    element.classList.add('sheet--dragging');
    try {
      grip.setPointerCapture(event.pointerId);
    } catch {
      // A synthetic pointer cannot be captured; the drag still works from the
      // move events this element receives directly.
    }
  };

  const onPointerMove = (event: PointerEvent): void => {
    if (!dragging || event.pointerId !== pointerId) return;
    const dy = event.clientY - startY;
    if (Math.abs(dy) > 3) moved = true;
    const dt = Math.max(1, event.timeStamp - lastAt) / 1000;
    velocity = (event.clientY - lastY) / dt;
    lastY = event.clientY;
    lastAt = event.timeStamp;
    setHeight(startHeight - dy);
  };

  const finishDrag = (event: PointerEvent): void => {
    if (!dragging || event.pointerId !== pointerId) return;
    dragging = false;
    // The transition comes back before the settle, so a release animates to its
    // target while a drag followed the finger.
    element.classList.remove('sheet--dragging');
    if (grip.hasPointerCapture(event.pointerId)) grip.releasePointerCapture(event.pointerId);

    const travel = Math.max(1, fullHeight - GRIP_HEIGHT);
    const height = element.getBoundingClientRect().height;
    const position = Math.max(0, Math.min(1, (fullHeight - height) / travel));
    // Velocity is in full travels per second, downward positive — the app's unit,
    // so the projection is the same rule at any height.
    const perSecond = velocity / travel;
    const projected = Math.max(
      0,
      Math.min(1, position + perSecond * PROJECTION_SECONDS),
    );

    let collapseIt: boolean;
    if (perSecond >= FLING_TRAVEL_PER_SECOND) collapseIt = true;
    else if (perSecond <= -FLING_TRAVEL_PER_SECOND) collapseIt = false;
    else if (projected > COLLAPSE_THRESHOLD) collapseIt = true;
    else if (projected < EXPAND_THRESHOLD) collapseIt = false;
    else collapseIt = position > 0.5; // the dead band: settle to the nearer state

    if (collapseIt) collapse();
    else expand();

    // The browser dispatches the click that ends a tap AFTER this handler. Reset
    // the movement flag on the next task, so a drag that produced no click cannot
    // leave the flag set and swallow the next genuine tap.
    window.setTimeout(() => {
      moved = false;
    }, 0);
  };

  grip.addEventListener('pointerdown', onPointerDown);
  grip.addEventListener('pointermove', onPointerMove);
  grip.addEventListener('pointerup', finishDrag);
  grip.addEventListener('pointercancel', finishDrag);
  // A tap on the grip toggles, which is also its accessibility action; a drag
  // that moved must not also count as a tap.
  const onClick = (): void => {
    if (moved) {
      moved = false;
      return;
    }
    if (isCollapsed) expand();
    else collapse();
  };
  grip.addEventListener('click', onClick);

  const onResize = (): void => {
    setHeight(isCollapsed ? GRIP_HEIGHT : expandedHeight());
  };
  window.addEventListener('resize', onResize);

  // Start open, laid out against the content it actually got: a sheet sized
  // before its rows exist would open at the grip and grow a frame later.
  requestAnimationFrame(() => {
    if (!destroyed) setHeight(expandedHeight());
  });

  return {
    element,
    body,
    footer,
    collapsed: () => isCollapsed,
    expand,
    collapse,
    destroy(): void {
      destroyed = true;
      window.removeEventListener('resize', onResize);
      grip.removeEventListener('pointerdown', onPointerDown);
      grip.removeEventListener('pointermove', onPointerMove);
      grip.removeEventListener('pointerup', finishDrag);
      grip.removeEventListener('pointercancel', finishDrag);
      grip.removeEventListener('click', onClick);
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
}

export interface SwitchRowOptions {
  title: string;
  sub: string;
  on: boolean;
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
  /** Every change, live — the renderer follows the finger and nothing persists. */
  onInput: (value: number) => void;
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
  row.className = 'bslider';

  const label = document.createElement('span');
  label.className = 'bslider-label';
  label.textContent = options.label;

  const track = document.createElement('div');
  track.className = 'bslider-track';
  track.tabIndex = 0;
  track.setAttribute('role', 'slider');
  track.setAttribute('aria-label', options.label);
  track.setAttribute('aria-valuemin', String(min));
  track.setAttribute('aria-valuemax', String(max));

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
    const next = clamp(value);
    if (next === shown) return;
    paint(next);
    options.onInput(next);
  }

  function nudge(glyph: string, direction: number): HTMLButtonElement {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'bslider-nudge';
    button.textContent = glyph;
    button.setAttribute('aria-label', `${options.label} ${glyph}`);
    button.addEventListener('click', () => move(shown + direction));
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
    if (event.button !== 0) return;
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
    if (event.type === 'pointercancel' && shown !== pressedAt) move(pressedAt);
  };
  track.addEventListener('pointerup', endDrag);
  track.addEventListener('pointercancel', endDrag);

  // The keyboard is the same steps the nudges are, so a control with no thumb is
  // still operable without a drag.
  track.addEventListener('keydown', (event) => {
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
