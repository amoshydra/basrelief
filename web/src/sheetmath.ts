/**
 * The studio's sheet, as arithmetic: no DOM, no event, no store.
 *
 * This is `SheetMorph` + `SheetIntent` + `SpringSettle` one platform over, and it
 * is a separate module for the reason those are in `:core` — the sheet is the one
 * surface in this project that has already been shipped wrong four times, every
 * one of them invisible in a screenshot taken at rest:
 *
 *   - a height band that held the sheet at full until the last 5 % of travel and
 *     then snapped: the height did not follow the drag and then collapsed all at
 *     once;
 *   - the opposite, a shape that ramped while the height was banded (the sheet moved as three unrelated timelines);
 *   - a free panel that rounded a 40 %-released drag up to the nearer of two
 *     states instead of resting where the finger left it;
 *   - a content fade that started where a *resting* sheet could still be, so a
 *     parked, fully usable sheet sat at half alpha.
 *
 * Each of those is a pure function of one number, so each one is a test here
 * rather than a device session. `sheet.ts` is the DOM that calls these; nothing
 * in this file may import anything.
 */

// ----------------------------------------------------------------- SheetIntent
//
// The release rule's constants, by value. `SheetIntent.SHEET`'s policy is
// 0.95 / 0.40 and its projection is 0.20 s of the release velocity, with a
// 1.2-travels-per-second flick deciding by itself.

/** `SheetIntent.PROJECTION_SECONDS`: the momentum a hand expects carried. */
export const PROJECTION_SECONDS = 0.2;
/** `SheetIntent.FLING_TRAVEL_PER_SECOND`: a decisive throw decides by itself. */
export const FLING_TRAVEL_PER_SECOND = 1.2;
/** `SheetIntent.SHEET.collapseThreshold`: collapse only almost at the bottom. */
export const COLLAPSE_THRESHOLD = 0.95;
/** `SheetIntent.SHEET.expandThreshold`: below this a release is an expand. */
export const EXPAND_THRESHOLD = 0.4;
/** `SheetIntent.DIALOG`'s pair: a transient card must not park half way. */
export const DIALOG_COLLAPSE_THRESHOLD = 0.6;
export const DIALOG_EXPAND_THRESHOLD = 0.4;

// ------------------------------------------------------------------ SheetMorph
//
// Two ramps on two properties, and the difference between them is the whole
// design: the height follows the hand across the WHOLE travel, while the shape
// (width, inset, radius, fill) waits for the last tenth. The content's opacity is
// a third band, pinned to where resting stops being possible.

/** `SheetMorph.SHAPE_BAND_START`: `SheetIntent.SHEET.collapseThreshold`. */
export const SHAPE_BAND_START = COLLAPSE_THRESHOLD;
/** `SheetMorph.EDGE_BAND_START`: the same line, for the content's fade. */
export const EDGE_BAND_START = COLLAPSE_THRESHOLD;

/**
 * `MainActivity.STRIP_HEIGHT_DP` — the collapsed sheet's height, and the height of
 * the strip at every fraction. The panel is never shorter than this.
 */
export const STRIP_HEIGHT = 56;
/** `MainActivity.STRIP_GRIP_DP`: the grip band inside the strip. */
export const STRIP_GRIP = 22;
/** `MainActivity.PILL_MAX_INSET_DP`: how far the pill may come from the edges. */
export const PILL_MAX_INSET = 56;
/** `MainActivity.PILL_MIN_WIDTH_DP`: a floor, so a short label is still a pill. */
export const PILL_MIN_WIDTH = 96;
/** `MainActivity.PILL_BOTTOM_INSET_DP`: how far the pill floats above the foot. */
export const PILL_BOTTOM_INSET = 26;
/** The capsule's own radius: half the strip, so the ends are semicircles. */
export const PILL_RADIUS = STRIP_HEIGHT / 2;
/** `MainActivity`'s open corner: `dp(22)`. */
export const SHEET_RADIUS = 22;
/** `MainActivity.SHEET_GLASS`: the panel's own plate, `rgba(22,26,32,.92)`. */
export const SHEET_GLASS = 0xeb161a20;
/** `MainActivity.PILL_PLATE`: what the strip blends to once it is the pill. */
export const PILL_PLATE = 0x8c1c2026;
/** `MainActivity.STRIP_DIM`: the idle status dot, `rgba(255,255,255,.55)`. */
export const PILL_DOT = 0x8cffffff;
/** `MainActivity`'s pill outline, `rgba(255,255,255,.2)`. */
export const PILL_STROKE = 0x33ffffff;
/** `MainActivity`'s open top hairline, `rgba(255,255,255,.14)`. */
export const SHEET_HAIRLINE = 0x24ffffff;
/** `MainActivity.WARN_COLOUR`: the attention dot, `rgb(232,176,75)`. */
export const WARN_COLOUR = 0xffe8b04b;

/** `MainActivity.SHEET_PEEK_FRACTION`: the first launch's fraction, a third. */
export const SHEET_PEEK_FRACTION = 1 / 3;

/**
 * `SpringSettle.SHEET`: critically damped, so it settles and never bounces.
 *
 * Damping at or above 1 is the branch that cannot overshoot — the user rejected
 * the bounce three times, and every value below 1 overshoots by construction.
 */
export const SPRING = {
  damping: 1,
  omega: 12,
  durationFactor: 1,
  minSettleSpeed: 0.9,
  minDurationMs: 250,
  maxDurationMs: 500,
} as const;

/** `SpringSettle.RESISTANCE_FRACTION`: the tether past full is a fifth of the span. */
export const RESISTANCE_FRACTION = 0.2;

// ------------------------------------------------------------------ the morph

/**
 * The collapse fraction, clamped to 0..1. NaN reads as "not collapsed": every
 * number downstream is a layout value or a paint colour, and a NaN reaching
 * either is not a degraded sheet — it is an unusable one.
 */
export function morphProgress(fraction: number): number {
  if (!(fraction > 0)) return 0;
  if (fraction >= 1) return 1;
  return fraction;
}

/**
 * The SHAPE's progress: the sheet's own shape until `SHAPE_BAND_START`, then a
 * straight ramp to the capsule.
 *
 * Read by the width, the inset, the radius and the fill — **not** by the height.
 * A full-width panel at 40 % of its height is still a sheet, and rounding it
 * towards a pill while it plainly is one reads as two objects.
 */
export function shapeProgress(fraction: number): number {
  const f = morphProgress(fraction);
  if (f <= SHAPE_BAND_START) return 0;
  return (f - SHAPE_BAND_START) / (1 - SHAPE_BAND_START);
}

/**
 * The panel's height at a collapse fraction.
 *
 * **The height ramps across the whole travel** — that is what dragging a bottom
 * sheet down *is* — and it never falls below the strip, which is what keeps the
 * drag handle reachable at the bottom of the travel. Below zero it keeps rising:
 * that is the tether, and it is the same `(1 - fraction)` extension the old
 * `showing = 1 - fraction` had.
 */
export function heightFor(fraction: number, naturalPx: number, pillPx: number): number {
  const pill = Math.max(0, pillPx);
  const natural = Math.max(pill, naturalPx);
  if (fraction >= 1) return pill;
  if (fraction > 0) return natural + (pill - natural) * morphProgress(fraction);
  // The tether, and only a REAL negative fraction reaches it: `natural * (1 - NaN)`
  // is NaN, and a NaN height is not a degraded sheet — it is an unusable one. Java
  // reaches `natural` for NaN through its lerp branch, and so does this.
  if (fraction < 0) return natural * (1 - fraction);
  return natural;
}

/**
 * The content's alpha below the strip: 1 everywhere except the last 5 % of
 * travel, and 0 only at the pill.
 *
 * The band is `COLLAPSE_THRESHOLD`, so **no position the sheet can rest at is
 * ever faded**: a free sheet rests anywhere below that line, and inside the band
 * the sheet is within 5 % of travel of being the pill, which is not "still here".
 * The strip itself never fades — the grip, the dot and the label are the pill.
 */
export function edgeFade(fraction: number): number {
  // One comparison, not two: `!(f > band)` is true for 0, for a negative
  // fraction AND for NaN, and a NaN alpha is an invisible strip. Java's own copy
  // reads `f <= 0f || f <= EDGE_BAND_START`, which lets NaN through to the
  // division; this is the stricter of the two on purpose.
  if (!(fraction > EDGE_BAND_START)) return 1;
  if (fraction >= 1) return 0;
  return 1 - (fraction - EDGE_BAND_START) / (1 - EDGE_BAND_START);
}

/**
 * The panel's top corner radius at a collapse fraction.
 *
 * The two ends are the sheet's 22 and half a pill; the panel's half-height is a
 * *ceiling*, applied to the result, never the interpolation target — interpolating
 * towards `height / 2` gives a 2400px panel a 611px corner half way down, which is
 * a stadium.
 */
export function cornerRadiusFor(
  fraction: number,
  panelHeightPx: number,
  openRadiusPx: number,
  pillRadiusPx: number,
): number {
  const height = panelHeightPx > 0 ? panelHeightPx : 0;
  const ceiling = height * 0.5;
  const open = Math.min(Math.max(0, openRadiusPx), ceiling);
  const pill = Math.min(Math.max(0, pillRadiusPx), ceiling);
  const radius = open + (pill - open) * shapeProgress(fraction);
  return Math.min(radius, ceiling);
}

/** How far the collapsed shape sits inside the window's edges, in px. */
export function insetFor(fraction: number, pillInsetPx: number): number {
  return Math.max(0, pillInsetPx) * shapeProgress(fraction);
}

/**
 * The per-side margin that narrows the sheet to the pill's width.
 *
 * The pill's shape is a WIDTH, not a margin: it wraps its label. Centred by
 * construction — half of what is left over — so the two sides cannot differ.
 */
export function pillSideMarginFor(
  fraction: number,
  screenWidthPx: number,
  pillWidthPx: number,
): number {
  const screen = Math.max(0, screenWidthPx);
  const width = Math.min(screen, Math.max(0, pillWidthPx));
  return insetFor(fraction, (screen - width) * 0.5);
}

/**
 * The pill's collapsed width: what its label needs, capped, with a floor.
 *
 * `measuredPx` is the label's own measurement (`measureText`'s answer, or a
 * `scrollWidth`), `chromePx` the row's padding, dot and gap. The +2 is the app's
 * own pixel of rounding slack: a panel rounded UP by half a pixel leaves the label
 * a pixel short and ellipsises its last glyph.
 */
export function pillWidthFor(
  measuredPx: number,
  chromePx: number,
  screenWidthPx: number,
): number {
  const cap = Math.max(0, screenWidthPx - PILL_MAX_INSET);
  const wanted = Math.min(
    Math.max(measuredPx + chromePx + 2, PILL_MIN_WIDTH),
    cap,
  );
  // A cap below the floor means a window narrower than the floor itself; the cap
  // wins, because a pill wider than its window is not a pill.
  return Math.max(0, Math.min(wanted, Math.max(cap, 0)));
}

/**
 * Blend two ARGB colours along the SHAPE's progress.
 *
 * Both ends are facts: the sheet's own glass at 0, so the open sheet has no plate
 * on it the morph then has to explain away, and the original pill's plate at 1.
 * Alpha blends with the channels, because the strip is glass over a render.
 */
export function blendColour(from: number, to: number, fraction: number): number {
  const f = shapeProgress(fraction);
  if (f <= 0) return from;
  if (f >= 1) return to;
  const a = lerpChannel((from >>> 24) & 0xff, (to >>> 24) & 0xff, f);
  const r = lerpChannel((from >> 16) & 0xff, (to >> 16) & 0xff, f);
  const g = lerpChannel((from >> 8) & 0xff, (to >> 8) & 0xff, f);
  const b = lerpChannel(from & 0xff, to & 0xff, f);
  return (a << 24) | (r << 16) | (g << 8) | b;
}

function lerpChannel(from: number, to: number, f: number): number {
  return Math.round(from + (to - from) * f);
}

/** An ARGB int as a CSS colour. The one place a paint crosses into the DOM. */
export function cssColour(argb: number): string {
  const a = ((argb >>> 24) & 0xff) / 255;
  const r = (argb >> 16) & 0xff;
  const g = (argb >> 8) & 0xff;
  const b = argb & 0xff;
  // Three decimals is finer than an 8-bit channel (1/255 = 0.0039) at one extra
  // digit, so a blend cannot round to a different colour on the way out.
  return `rgba(${r}, ${g}, ${b}, ${a.toFixed(3)})`;
}

/** `cssColour` with an independent alpha, for a paint that fades separately. */
export function cssColourAlpha(argb: number, alpha: number): string {
  const r = (argb >> 16) & 0xff;
  const g = (argb >> 8) & 0xff;
  const b = argb & 0xff;
  const a = Math.max(0, Math.min(1, alpha)).toFixed(3);
  return `rgba(${r}, ${g}, ${b}, ${a})`;
}

// ---------------------------------------------------------------- the release

export type Decision = 'collapse' | 'expand' | 'stay';

export interface Policy {
  collapseThreshold: number;
  expandThreshold: number;
}

/** `SheetIntent.SHEET`: the studio's persistent panel keeps a free position. */
export const SHEET_POLICY: Policy = {
  collapseThreshold: COLLAPSE_THRESHOLD,
  expandThreshold: EXPAND_THRESHOLD,
};

/** `SheetIntent.DIALOG`: a transient card must land on a stop. */
export const DIALOG_POLICY: Policy = {
  collapseThreshold: DIALOG_COLLAPSE_THRESHOLD,
  expandThreshold: DIALOG_EXPAND_THRESHOLD,
};

/**
 * Where the fling would land, clamped to the travel range.
 *
 * Clamped because "past the bottom" is not a place, and because a clamp cannot
 * change a decision already made: at the top the position expands it, at the
 * bottom a collapse.
 */
export function projected(position: number, velocityPerSecond: number): number {
  const next = position + velocityPerSecond * PROJECTION_SECONDS;
  if (!(next > 0)) return 0;
  if (next > 1) return 1;
  return next;
}

/**
 * What letting go means.
 *
 * A flick past `FLING_TRAVEL_PER_SECOND` decides by itself; otherwise the release
 * is projected forward by its own speed and compared against the policy's two
 * lines. Between them — the dead band — nothing is decided, which is the
 * hysteresis: a single boundary would flip state on a release a hair either side.
 *
 * `STAY` is not "snap to the nearer stop". For `SHEET_POLICY` it means the sheet
 * rests **exactly where the finger left it** (the studio's `Snap.FREE`); a card
 * host maps it to "back to open", because a half-height card is an unanswered
 * question.
 */
export function decide(
  position: number,
  velocityPerSecond: number,
  policy: Policy = SHEET_POLICY,
): Decision {
  if (velocityPerSecond >= FLING_TRAVEL_PER_SECOND) return 'collapse';
  if (velocityPerSecond <= -FLING_TRAVEL_PER_SECOND) return 'expand';
  const landing = projected(position, velocityPerSecond);
  if (landing > policy.collapseThreshold) return 'collapse';
  if (landing < policy.expandThreshold) return 'expand';
  return 'stay';
}

// ----------------------------------------------------------------- the spring

/**
 * The rubber-band offset for a drag, in raw finger units.
 *
 * Positive (downward, the direction the sheet exists to travel) passes through
 * untouched, because damping that would make the dismissal feel broken. Negative
 * is divided by its own distance, so the movement approaches
 * `span * RESISTANCE_FRACTION` and never reaches it.
 */
export function resistUp(dy: number, spanPx: number): number {
  if (dy >= 0 || !(spanPx > 0)) return Math.max(dy, 0);
  const over = -dy;
  const limit = spanPx * RESISTANCE_FRACTION;
  return -over / (1 + over / limit);
}

/**
 * How long a settle takes: distance over speed, clamped.
 *
 * Both arguments are fractions of the full travel, never pixels, so the span
 * cancels and a small screen and a large one feel the same. The floor under the
 * speed is what makes a dead-still release still move.
 */
export function springDuration(distanceTravel: number, velocityTravelPerS: number): number {
  const speed = Math.max(Math.abs(velocityTravelPerS), SPRING.minSettleSpeed);
  const ms = Math.trunc((Math.abs(distanceTravel) / speed) * 1000 * SPRING.durationFactor);
  return Math.max(SPRING.minDurationMs, Math.min(SPRING.maxDurationMs, ms));
}

/**
 * The critically damped step response, normalised to 0..1.
 *
 * `1 - e^(-w t)(1 + w t)` is the closed form at damping 1 — the limit of the
 * underdamped curve as its damped frequency goes to zero, which is why the sine
 * form divides by zero there. It is monotone by construction: its velocity is
 * `w^2 t e^(-w t)`, non-negative for all `t >= 0`, so **overshoot cannot be
 * dialled down further because there is no peak to measure.**
 */
export function springCurve(t: number): number {
  if (!(t > 0)) return 0;
  if (t >= 1) return 1;
  const wt = SPRING.omega * t;
  return 1 - Math.exp(-wt) * (1 + wt);
}

/**
 * The first-launch collapse fraction: a third of the viewport, as much as the
 * panel can give.
 *
 * `MainActivity.peekFraction`, exactly: the peek is a third of the SCREEN capped
 * at the panel's natural height, and the fraction is what is left of the
 * denominator. The morph then maps that fraction through `heightFor`, so the
 * covered height is a little more than a third — that is the phone's own
 * arithmetic, not an approximation of it.
 */
export function peekFraction(viewportHeightPx: number, naturalPx: number): number {
  if (!(naturalPx > 0) || !(viewportHeightPx > 0)) return 0;
  const peek = Math.min(Math.round(SHEET_PEEK_FRACTION * viewportHeightPx), naturalPx);
  return 1 - peek / naturalPx;
}

/** Clamp to the travel range; NaN is "not collapsed", the safe end. */
export function clamp01(value: number): number {
  if (!(value > 0)) return 0;
  if (value > 1) return 1;
  return value;
}
