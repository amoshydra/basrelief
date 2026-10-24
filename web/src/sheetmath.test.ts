#!/usr/bin/env node
/**
 * The studio sheet's numbers, without a browser: `SheetMorph`, `SheetIntent` and
 * `SpringSettle.SHEET`, one platform over.
 *
 *   node --experimental-strip-types src/sheetmath.test.ts
 *
 * Every one of these properties has shipped wrong on the phone already, and every
 * one of them is invisible in a screenshot taken at rest:
 *
 *   - **the height ramps across the whole travel** (no plateau, no cliff), because
 *     a banded height was reported twice — once as "it doesn't reduce in height,
 *     when i go further, it suddenly collapses" and once as "illogical";
 *   - **the shape waits for the last tenth**, because a full-width panel that is
 *     40 % of its height is still a sheet, and rounding it towards a pill while it
 *     plainly is one reads as two objects;
 *   - **no position the sheet can REST at is ever faded**: the content's band is
 *     the collapse threshold, so a parked, usable sheet is fully opaque by
 *     construction rather than by luck;
 *   - **a release inside the dead band rests exactly where it is**, which is the
 *     studio's `Snap.FREE` — the web used to round it to the nearer of two;
 *   - **the tether past full asymptotes** at a fifth of the span instead of
 *     running free;
 *   - **the settle's duration comes from distance over the release speed**, clamped
 *     to 250-500 ms, and its curve is critically damped so it cannot bounce.
 *
 * And one continuity claim per property: **the last 1 % of travel must not step.**
 * A step there is the old two-view pop (an opaque plate one frame, a transparent
 * capsule the next) and it is exactly what a rest-state screenshot cannot see.
 */
import assert from 'node:assert/strict';

import {
  COLLAPSE_THRESHOLD,
  DIALOG_POLICY,
  EDGE_BAND_START,
  EXPAND_THRESHOLD,
  FLING_TRAVEL_PER_SECOND,
  PILL_BOTTOM_INSET,
  PILL_MAX_INSET,
  PILL_MIN_WIDTH,
  PILL_PLATE,
  PILL_RADIUS,
  RESISTANCE_FRACTION,
  SHAPE_BAND_START,
  SHEET_GLASS,
  SHEET_PEEK_FRACTION,
  SHEET_POLICY,
  SPRING,
  STRIP_HEIGHT,
  blendColour,
  clamp01,
  cornerRadiusFor,
  cssColour,
  decide,
  edgeFade,
  heightFor,
  insetFor,
  morphProgress,
  peekFraction,
  pillSideMarginFor,
  pillWidthFor,
  projected,
  resistUp,
  shapeProgress,
  springCurve,
  springDuration,
} from './sheetmath.ts';

let checks = 0;

function test(name: string, fn: () => void): void {
  fn();
  checks++;
  console.log(`ok   ${name}`);
}

function close(actual: number, expected: number, tolerance: number, what = ''): void {
  assert.ok(
    Math.abs(actual - expected) <= tolerance,
    `${what} expected ${expected}, got ${actual} (tolerance ${tolerance})`,
  );
}

// ------------------------------------------------------------------ the height

test('the height ramps across the whole travel, with no plateau and no cliff', () => {
  const natural = 620;
  const pill = STRIP_HEIGHT;
  // Every step is a real step: the "hold then fall" band returned `natural`
  // exactly for the first 95 % and then fell off a cliff.
  let previous = heightFor(0, natural, pill);
  const perStep = (natural - pill) * 0.001;
  for (let i = 1; i <= 1000; i++) {
    const height = heightFor(i / 1000, natural, pill);
    close(previous - height, perStep, 1e-9, `step at f=${i / 1000}`);
    previous = height;
  }
  assert.equal(heightFor(0, natural, pill), natural);
  assert.equal(heightFor(1, natural, pill), pill);
});

test('the height never falls below the strip, so the handle is always reachable', () => {
  for (const natural of [0, 40, 56, 120, 620, 2400]) {
    for (let i = 0; i <= 100; i++) {
      const height = heightFor(i / 100, natural, STRIP_HEIGHT);
      assert.ok(height >= STRIP_HEIGHT - 1e-9, `height ${height} at f=${i / 100}`);
    }
  }
});

test('below zero the height keeps rising: that is the tether', () => {
  // A drag past full carries a negative fraction, and the sheet visibly rises
  // above its resting place. Clamping here would delete the spring.
  assert.equal(heightFor(-0.1, 1000, 56), 1100);
  assert.ok(heightFor(-0.005, 620, 56) > 620);
});

test('a NaN fraction is the open sheet, not an inverted one', () => {
  assert.equal(morphProgress(NaN), 0);
  assert.equal(heightFor(NaN, 620, 56), 620);
  assert.equal(shapeProgress(NaN), 0);
  assert.equal(edgeFade(NaN), 1);
});

// ------------------------------------------------------------------- the shape

test('the shape is the sheet’s own until the collapse threshold, and then ramps', () => {
  assert.equal(shapeProgress(0), 0);
  for (let i = 0; i <= 95; i++) {
    assert.equal(shapeProgress(i / 100), 0, `shape moved at f=${i / 100}`);
  }
  close(shapeProgress(0.975), 0.5, 1e-12);
  assert.equal(shapeProgress(1), 1);
  // And the two bands are one line: the fade, the width, the radius and the fill
  // all begin where a release would have committed to collapsing.
  assert.equal(SHAPE_BAND_START, COLLAPSE_THRESHOLD);
  assert.equal(EDGE_BAND_START, COLLAPSE_THRESHOLD);
});

test('no restable position is ever faded, and the content is gone at the pill', () => {
  // A free sheet rests anywhere below the collapse threshold, so every fraction
  // in 0..0.95 must be exactly opaque. This is the complaint the band exists for:
  // a parked, fully usable sheet sitting at half alpha.
  for (let i = 0; i <= 950; i++) {
    assert.equal(edgeFade(i / 1000), 1, `faded at f=${i / 1000}`);
  }
  assert.equal(edgeFade(1), 0);
  // Monotone and continuous through the band.
  let previous = 1;
  for (let i = 951; i <= 1000; i++) {
    const alpha = edgeFade(i / 1000);
    assert.ok(alpha <= previous + 1e-12, 'the fade reversed');
    assert.ok(previous - alpha <= 0.021, 'the fade stepped');
    previous = alpha;
  }
  assert.equal(edgeFade(1.4), 0);
});

test('the radius goes from the sheet’s 22 to half a strip, and never past half the box', () => {
  assert.equal(cornerRadiusFor(0, 620, 22, PILL_RADIUS), 22);
  assert.equal(cornerRadiusFor(1, STRIP_HEIGHT, 22, PILL_RADIUS), PILL_RADIUS);
  // The radius is the shape's, so it is still 22 just below the band.
  assert.equal(cornerRadiusFor(0.9499, 620, 22, PILL_RADIUS), 22);
  for (const height of [0, 10, 56, 120, 620, 2400]) {
    for (let i = 0; i <= 100; i++) {
      const radius = cornerRadiusFor(i / 100, height, 22, PILL_RADIUS);
      assert.ok(radius <= height / 2 + 1e-9, `radius ${radius} of a ${height}px box`);
      assert.ok(radius >= 0, 'negative radius');
    }
  }
});

test('the pill is lifted off the foot and inset to its own width, both in the band', () => {
  assert.equal(insetFor(0, PILL_BOTTOM_INSET), 0);
  assert.equal(insetFor(0.9, PILL_BOTTOM_INSET), 0);
  assert.equal(insetFor(1, PILL_BOTTOM_INSET), PILL_BOTTOM_INSET);
  // Centred by construction: half of what is left over, so the two sides agree.
  assert.equal(pillSideMarginFor(1, 412, 260), (412 - 260) / 2);
  assert.equal(pillSideMarginFor(0, 412, 260), 0);
  // A pill wider than its window cannot push the margin negative.
  assert.equal(pillSideMarginFor(1, 100, 260), 0);
});

test('the pill’s width is measured, capped and floored', () => {
  // Content-width: the label plus the row's chrome, with the app's 2px slack.
  assert.equal(pillWidthFor(200, 54, 412), 256);
  // The 96 floor, so a one-word label is still a pill rather than a lozenge.
  assert.equal(pillWidthFor(10, 54, 412), PILL_MIN_WIDTH);
  // The cap is the screen less the 56dp of total inset.
  assert.equal(pillWidthFor(1000, 54, 412), 412 - PILL_MAX_INSET);
  // A window narrower than the floor: the cap wins, because a pill wider than its
  // window is not a pill.
  assert.equal(pillWidthFor(10, 54, 80), 80 - PILL_MAX_INSET);
});

test('the fill blends from the sheet’s glass to the pill’s plate, in the band only', () => {
  assert.equal(blendColour(SHEET_GLASS, PILL_PLATE, 0), SHEET_GLASS);
  assert.equal(blendColour(SHEET_GLASS, PILL_PLATE, 0.95), SHEET_GLASS);
  assert.equal(blendColour(SHEET_GLASS, PILL_PLATE, 1), PILL_PLATE);
  // Alpha moves a long way (0xEB to 0x8C) and the channels a little, and both
  // move monotonically: the strip is glass over a render, so a pill that arrived
  // opaque would stop floating and start sitting.
  let previousAlpha = (SHEET_GLASS >>> 24) & 0xff;
  for (let i = 950; i <= 1000; i++) {
    const colour = blendColour(SHEET_GLASS, PILL_PLATE, i / 1000);
    const alpha = (colour >>> 24) & 0xff;
    assert.ok(alpha <= previousAlpha, 'the fill got more opaque as it collapsed');
    previousAlpha = alpha;
  }
  assert.equal((blendColour(SHEET_GLASS, PILL_PLATE, 1) >>> 24) & 0xff, 0x8c);
  // A NaN fraction is the sheet's own glass — the one outcome that must never be
  // a fully transparent strip.
  assert.equal(blendColour(SHEET_GLASS, PILL_PLATE, NaN), SHEET_GLASS);
  assert.equal(cssColour(PILL_PLATE), 'rgba(28, 32, 38, 0.549)');
});

// ----------------------------------------------------------------- the release

test('a decisive flick decides by itself, from anywhere', () => {
  assert.equal(decide(0.1, FLING_TRAVEL_PER_SECOND, SHEET_POLICY), 'collapse');
  assert.equal(decide(0.9, -FLING_TRAVEL_PER_SECOND, SHEET_POLICY), 'expand');
  // Just under the fling line is the projection's job, not the flick's.
  assert.equal(decide(0.5, 0.2, SHEET_POLICY), 'stay');
});

test('the projection is the last direction that matters', () => {
  // Dragged to 98 % and released moving UP: the projection lands above the
  // expand line, so the sheet stays open. A position threshold could never read
  // this — the finger has crossed every position threshold there is.
  const position = 0.98;
  const upward = -1.0; // travels per second, under the fling line
  assert.equal(decide(position, upward, SHEET_POLICY), 'stay');
  assert.ok(projected(position, upward) < position);
  // Down, up, down again ends moving down: the earlier excursion does not exist.
  assert.equal(decide(0.9, 0.5, SHEET_POLICY), 'collapse');
});

test('the dead band is the hysteresis: a release there changes nothing', () => {
  for (const position of [0.4, 0.5, 0.6, 0.8, 0.9]) {
    assert.equal(decide(position, 0, SHEET_POLICY), 'stay', `f=${position}`);
  }
  // And it is a POLICY: the same release that rests on the studio sheet snaps a
  // dialog back to open, because a half-height card is an unanswered question.
  assert.equal(decide(0.7, 0, SHEET_POLICY), 'stay');
  assert.equal(decide(0.7, 0, DIALOG_POLICY), 'collapse');
  assert.equal(decide(0.5, 0, DIALOG_POLICY), 'stay');
  assert.equal(decide(0.5, 0.8, DIALOG_POLICY), 'collapse');
  // The two thresholds are deliberately not one number.
  assert.notEqual(DIALOG_POLICY.collapseThreshold, DIALOG_POLICY.expandThreshold);
  assert.equal(SHEET_POLICY.collapseThreshold, COLLAPSE_THRESHOLD);
  assert.equal(SHEET_POLICY.expandThreshold, EXPAND_THRESHOLD);
});

test('the projection is clamped to the travel range', () => {
  assert.equal(projected(0.1, -10), 0);
  assert.equal(projected(0.9, 10), 1);
  close(projected(0.5, 1), 0.7, 1e-12);
});

// ------------------------------------------------------------------ the tether

test('a drag past full asymptotes at a fifth of the span, never at nothing', () => {
  assert.equal(resistUp(0, 620), 0);
  assert.equal(resistUp(120, 620), 120); // downward is untouched
  const limit = 620 * RESISTANCE_FRACTION;
  let previous = 0;
  for (const over of [1, 10, 50, 200, 1000, 1e6]) {
    const offset = resistUp(-over, 620);
    // The first millimetre moves fully; the pull stiffens from there.
    assert.ok(offset < 0, 'a pull up must be negative');
    assert.ok(-offset < limit, `pull ${-offset} reached the asymptote ${limit}`);
    assert.ok(-offset > previous, 'the tether reversed');
    previous = -offset;
  }
  // Half the limit is at half the limit: `over / (1 + over/limit)`.
  close(-resistUp(-limit, 620), limit / 2, 1e-9);
  // Nothing that cannot claim a span is stretched.
  assert.equal(resistUp(-100, 0), 0);
});

test('a fraction that has been tethered still maps through the same height', () => {
  const span = 620 - STRIP_HEIGHT;
  // A finger 200px above the open sheet moves it by 200px of *resisted* travel,
  // not 200px of the sheet's own: the panel is above its resting place, and it
  // comes back.
  const raw = -200 / span;
  const fraction = resistUp(raw * span, span) / span;
  assert.ok(fraction < 0);
  assert.ok(heightFor(fraction, 620, STRIP_HEIGHT) > 620);
  assert.ok(heightFor(fraction, 620, STRIP_HEIGHT) < 620 + 200);
});

// ------------------------------------------------------------------ the spring

test('the settle’s duration is distance over speed, clamped to 250-500ms', () => {
  // A flick covers a long distance fast; a slow release eases.
  assert.equal(springDuration(0.5, 2), SPRING.minDurationMs);
  assert.equal(springDuration(1.5, 0.5), SPRING.maxDurationMs);
  close(springDuration(0.4, 1), 400, 1e-9);
  // Linear in distance at a fixed speed, until the clamps.
  close(springDuration(0.2, 1), 250, 1);
  close(springDuration(0.3, 1), 300, 1e-9);
  // A dead-still release still moves: the speed floor is 0.9 travels/s.
  close(springDuration(0.5, 0), 500, 1e-9); // 0.5/0.9 = 555ms, clamped
  close(springDuration(0.2, 0), 250, 1e-9); // 222ms -> the floor
  // The derivation cancels the span: both arguments are fractions of travel, so
  // the same RATIO of distance to speed is the same duration.
  assert.equal(springDuration(0.6, 2), springDuration(0.3, 1));
});

test('the curve is critically damped: monotone, no overshoot, lands at 1', () => {
  assert.equal(springCurve(0), 0);
  assert.equal(springCurve(1), 1);
  assert.equal(springCurve(-1), 0);
  assert.equal(springCurve(4), 1);
  let previous = 0;
  for (let i = 1; i <= 200; i++) {
    const value = springCurve(i / 200);
    assert.ok(value >= previous - 1e-12, `the curve went backwards at t=${i / 200}`);
    assert.ok(value <= 1 + 1e-12, `the curve overshot at t=${i / 200}`);
    previous = value;
  }
  // At t=1 the residual is the envelope: e^-12 * 13, so it lands on the value
  // rather than stopping short of it.
  assert.ok(springCurve(1) - springCurve(0.999) < 0.001);
});

// -------------------------------------------------------------------- the peek

test('the sheet opens at a third of the viewport, capped by what the panel has', () => {
  assert.equal(SHEET_PEEK_FRACTION, 1 / 3);
  // A third of 915 is 305; the panel is 620 tall, so the fraction is 1 - 305/620.
  close(peekFraction(915, 620), 1 - 305 / 620, 1e-12);
  // A panel shorter than the peek opens fully rather than at a negative fraction.
  assert.equal(peekFraction(915, 120), 0);
  // Nothing that cannot claim a height gets a fraction.
  assert.equal(peekFraction(915, 0), 0);
  assert.equal(peekFraction(0, 620), 0);
});

test('the fraction clamps to the travel range without inverting a NaN', () => {
  assert.equal(clamp01(-0.2), 0);
  assert.equal(clamp01(0.4), 0.4);
  assert.equal(clamp01(3), 1);
  assert.equal(clamp01(NaN), 0);
});

console.log(`\nsheet: ${checks} checks passed`);
