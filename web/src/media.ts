/**
 * What a dropped file IS, decided in one place.
 *
 * The app has two media on one row (`RenderMode.RELIEF` and `RenderMode.SPLAT`)
 * and four "where did this come from" vocabularies on the Android side. The web
 * gallery needs the same discipline, not a PNG flag with a cloud bolted beside
 * it: a file's kind is a property of the file, and every surface (badge, meta
 * line, viewer dispatch) reads THIS answer.
 *
 * The three-state rule the brief calls out is the app's `DepthSource` rule:
 *
 *   - a file that carries a depth map is a relief;
 *   - a readable image with no depth chunk is a photograph;
 *   - a file whose bytes are not here yet, or whose claims do not add up, is
 *     UNKNOWN — never "a photograph without depth".
 *
 * A Gaussian splat cloud is a second known medium, so it extends the same
 * vocabulary rather than sitting outside it. `reading` is the window in which
 * nothing may be claimed: `DepthSource.UNKNOWN` and `SplatSource.UNREAD` in one
 * state, because the reason is the same.
 *
 * This module is pure: it takes plain values and returns plain values, so the
 * three states can be tested without a browser. The DOM-free test is
 * `web/src/media.test.ts`.
 */

import type { DephResult } from './deph.ts';

/** One `.splat` record: position 3f, scale 3f, colour 4B, quaternion 4B. */
export const SPLAT_RECORD_BYTES = 32;

export type MediaState =
  | { kind: 'reading' }
  | { kind: 'relief'; form: 'raw' | 'png' }
  | { kind: 'photo'; detail: string }
  | { kind: 'cloud'; splats: number }
  | { kind: 'unreadable'; reason: string };

export interface MediaProbe {
  /** The file's name, used for the `.splat` extension. */
  name: string;
  /** The file's length in bytes; a cloud's count comes from here alone. */
  size: number;
  /** The reader's answer once the bytes are read; null while they are not. */
  depth: DephResult | null;
  /** Set when the file could not be read at all. */
  readError: string | null;
  /**
   * Whether the file decodes as an image. `null` means "not tried / unknown yet"
   * — the window in which the honest answer is `reading`, not `photo`.
   */
  decodes: boolean | null;
}

function isSplatName(name: string): boolean {
  return name.toLowerCase().endsWith('.splat');
}

/**
 * Decide a file's kind.
 *
 * A `.splat` is decided from its NAME AND LENGTH before any bytes are read, which
 * is what makes showing the count free: records = bytes / 32. It must still be a
 * whole number of records; a truncated cloud is not a shorter cloud, it is a file
 * that has not been read completely.
 */
export function classifyMedia(probe: MediaProbe): MediaState {
  if (probe.readError) {
    return { kind: 'unreadable', reason: probe.readError };
  }

  if (isSplatName(probe.name)) {
    if (probe.size === 0) {
      return { kind: 'unreadable', reason: 'the .splat file is empty' };
    }
    if (probe.size % SPLAT_RECORD_BYTES !== 0) {
      return {
        kind: 'unreadable',
        reason:
          `the .splat file is not a whole number of ${SPLAT_RECORD_BYTES}-byte records` +
          ` (${formatBytes(probe.size)})`,
      };
    }
    return { kind: 'cloud', splats: probe.size / SPLAT_RECORD_BYTES };
  }

  // No bytes yet. This is the window that must not claim "no depth".
  if (probe.depth === null) return { kind: 'reading' };

  const depth = probe.depth;

  if (!depth.ok) {
    // The file SAYS it carries depth and the payload is unusable. That is a
    // refusal, not a photograph with no depth.
    if (depth.error === 'bad-payload') {
      const found = depth.payloadLength ?? 0;
      const expected = depth.expectedRaw ?? 0;
      return {
        kind: 'unreadable',
        reason: `the depth chunk is malformed (${found} bytes; ${expected} expected for raw grey)`,
      };
    }

    // `not-png` and `no-depth-chunk` both need the image itself to decode before
    // the file may be called a photograph.
    if (probe.decodes === null) return { kind: 'reading' };
    if (!probe.decodes) {
      return { kind: 'unreadable', reason: 'the file is not a decodable image' };
    }
    return {
      kind: 'photo',
      detail:
        depth.error === 'not-png'
          ? 'not a PNG — no embedded depth is possible'
          : 'plain image — no embedded depth',
    };
  }

  if (probe.decodes === false) {
    return { kind: 'unreadable', reason: 'the image did not decode' };
  }
  if (probe.decodes === null) return { kind: 'reading' };

  // A depth map with different dimensions is REFUSED, never stretched. The app
  // packs raw grey at the photo's own size, so a mismatch means this file was
  // built by something else and its pairing cannot be trusted.
  if (
    depth.depthWidth > 0 &&
    depth.depthHeight > 0 &&
    depth.imageWidth > 0 &&
    depth.imageHeight > 0 &&
    (depth.depthWidth !== depth.imageWidth || depth.depthHeight !== depth.imageHeight)
  ) {
    return {
      kind: 'unreadable',
      reason:
        `the depth map is ${depth.depthWidth}×${depth.depthHeight} but the photo is ` +
        `${depth.imageWidth}×${depth.imageHeight} — refusing to stretch it`,
    };
  }

  return { kind: 'relief', form: depth.form };
}

const splatCountFormat = new Intl.NumberFormat('en-US');

/** The sheet's own wording: `479,232 splats`. */
export function formatSplatCount(count: number): string {
  return `${splatCountFormat.format(count)} splats`;
}

export function formatBytes(size: number): string {
  if (!Number.isFinite(size) || size < 0) return 'unknown size';
  if (size < 1024) return `${size} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = size / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

/** `3024×4032`, or the empty string when the dimensions are not known. */
export function formatDimensions(width: number, height: number): string {
  if (width <= 0 || height <= 0) return '';
  return `${width}×${height}`;
}

/** One short line for the viewer's title bar. */
export function describeMedia(state: MediaState): string {
  switch (state.kind) {
    case 'relief':
      return state.form === 'raw' ? 'packed PNG · raw grey depth' : 'packed PNG · PNG depth';
    case 'photo':
      return `photo · ${state.detail}`;
    case 'cloud':
      return `${formatSplatCount(state.splats)} · gaussian splat cloud`;
    case 'reading':
      return 'reading…';
    case 'unreadable':
      return `unreadable · ${state.reason}`;
  }
}
