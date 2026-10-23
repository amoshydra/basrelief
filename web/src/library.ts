/**
 * The app's gallery index, as the same bytes it stores.
 *
 * `Library.java` is the Android app's library: a versioned JSON blob under the
 * `library` key of `SharedPreferences depthwall`, holding the photographs, each
 * one's per-surface render values, the selection and the list order. This module
 * is that schema's second reader and writer, so `library.json` in this page's
 * storage (and in a backup folder) is a blob the phone can read, and a blob the
 * phone wrote is one this page can read. `contract/library/cases.json` is the
 * only shared statement of the bodies the two must agree on; the app's own tests
 * cannot see TypeScript, and `scripts/check-parity.mjs` holds the schema number
 * and the field set both sides write.
 *
 * **Filenames, never paths** — `LibraryItem`'s rule and `PrefFiles`' reason: with
 * `allowBackup="true"` a stored absolute path survives a restore and points at
 * the previous device's `filesDir`, so the row resolves to nothing. Every name
 * here is reduced to a basename, and the store re-resolves it against its own
 * directory.
 *
 * **It never throws.** A corrupt, half-written or newer blob is an empty library
 * plus a sentence, not a dead page — the app's `parse` rule. The one state that
 * must not be written over is a blob from a *newer* build: its fields may mean
 * something else, so `readOnly` is set and the caller must refuse every write
 * rather than replace somebody's gallery with this build's empty one.
 *
 * **What round-trips, and what is inert here.** Everything the app stores is
 * preserved: `SurfacesSettings` per surface (including a lock override this page
 * cannot render), `render`, `archived`, `focal`, `splat`, `splat_lens`, `video`
 * (this page draws no clips, but the bytes — and the filenames the mirror has to
 * copy — survive), the sort order, the applied surfaces and the selection. What
 * is *inert* is exactly the behaviour: nothing rotates, nothing is archived and
 * nothing plays. That is the difference between a schema the app owns and a
 * feature this page has.
 *
 * **Playlists are a different blob.** The app keeps them under its own
 * `playlists` key (`Playlists.KEY`), not inside this one, so there is nothing
 * here to round-trip; a backup folder's `playlists.json`, if it has one, is
 * copied through untouched by the mirror rather than interpreted.
 */
import {
  defaultSettings,
  fromJson as settingsFromJson,
  toJson as settingsToJson,
  type SurfaceSettings,
} from './settings.ts';

/** The `Library` blob's schema version (`Library.VERSION`). */
export const LIBRARY_SCHEMA = 5;

/** The orders the app lists in (`SortOrder`'s stored keys). */
export const SORT_KEYS = ['added_desc', 'name_asc', 'recently_used'] as const;
export type SortKey = (typeof SORT_KEYS)[number];

/** The two surfaces an item's settings can be stored for (`Surface`'s keys). */
export const SURFACE_KEYS = ['home', 'lock'] as const;
export type SurfaceKey = (typeof SURFACE_KEYS)[number];

/**
 * The keys the writer emits, in its own order.
 *
 * Declared as data as well as written by hand, so the field set is one statement
 * `web/src/library.test.ts` can hold to the fixture — and `scripts/check-parity`
 * to the Java writers. A key added to the writer and not to this list is a key the
 * contract does not know about; a key in this list and not in the fixture is a
 * field the fixture's round trip does not cover.
 */
export const BLOB_KEYS = ['version', 'sort', 'applied', 'items', 'active'] as const;

/** Every key a row may carry, in `LibraryItem.toJson`'s order. */
export const ITEM_KEYS = [
  'id',
  'image',
  'added_at',
  'last_used_at',
  'surfaces',
  'depth',
  'normal',
  'video',
  'focal',
  'splat_lens',
  'splat',
  'render',
  'label',
  'archived',
] as const;

/**
 * The id of the app's bundled demo.
 *
 * The one row whose `image` may be absent, because its files ship inside the
 * phone app — so this page can list it and cannot show it. Reading it is the
 * parse exemption the app has; showing it is not something this page can do.
 */
export const BUNDLED_ID = 'bundled';

/** `RenderMode`'s stored keys: how a row is drawn. */
export type RenderMode = 'relief' | 'splat';

/** One row: a photograph, its optional assets, and how it is drawn. */
export interface LibraryItem {
  id: string;
  /** Bare filename of the colour image, or null for the bundled demo only. */
  image: string | null;
  /** Bare filename of a separate depth map, or null when the map is embedded. */
  depth: string | null;
  /** Bare filename of a supplied normal map, or null (flat lit). */
  normal: string | null;
  /** A clip's three filenames, carried opaquely; this page draws no clips. */
  video: Record<string, string> | null;
  /** The colour camera's focal in pixels of `image`, or 0 for unknown. */
  focal: number;
  /** Bare filename of the Gaussian splat cloud, or null. */
  splat: string | null;
  /** The cloud's own lens as `focal / height`, or 0 for unknown. */
  splatLens: number;
  render: RenderMode;
  /** User-visible name, or null to fall back to the image filename. */
  label: string | null;
  addedAt: number;
  lastUsedAt: number;
  archived: boolean;
  home: SurfaceSettings;
  /** The lock screen's own values, or null while it mirrors home. */
  lock: SurfaceSettings | null;
}

/** The index: the items, the selection and the read order. */
export interface WebLibrary {
  sort: SortKey;
  applied: SurfaceKey[];
  /** Id of the row the app would draw, or null when nothing is selected. */
  active: string | null;
  items: LibraryItem[];
}

export type LibraryReadStatus = 'ok' | 'empty' | 'corrupt' | 'newer';

export interface LibraryRead {
  library: WebLibrary;
  status: LibraryReadStatus;
  /** A sentence for the page, or null when there is nothing to say. */
  message: string | null;
  /** The version a newer blob claimed; 0 unless status is 'newer'. */
  newerVersion: number;
  /** True when the blob is from a newer build and must not be overwritten. */
  readOnly: boolean;
}

/** An empty gallery: nothing listed, nothing selected, newest first. */
export function emptyLibrary(): WebLibrary {
  return { sort: 'added_desc', applied: ['home'], active: null, items: [] };
}

// --------------------------------------------------------------- the reader

/**
 * Parse a stored blob.
 *
 * The version is checked before anything else: a blob from a newer build is not
 * this build's to interpret *or* to replace, so it answers an empty library with
 * `readOnly` set. A blob that is not JSON, or not an object, is `corrupt` — the
 * caller may keep a copy and start clean, because an unreadable blob is this
 * build's problem rather than a future one's.
 *
 * Individual bad items are dropped rather than failing the whole blob, the app's
 * rule: losing one unreadable row is recoverable, losing forty good ones because
 * of the forty-first is not.
 */
export function readLibrary(raw: string | null): LibraryRead {
  if (raw === null || raw.trim() === '') {
    return {
      library: emptyLibrary(),
      status: 'empty',
      message: null,
      newerVersion: 0,
      readOnly: false,
    };
  }

  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch (err) {
    return corrupt(`library.json is not readable JSON (${reasonOf(err)})`);
  }
  if (!isRecord(body)) return corrupt('library.json is not a JSON object.');

  const version = asInt(body.version, 0);
  if (version > LIBRARY_SCHEMA) {
    return {
      library: emptyLibrary(),
      status: 'newer',
      message:
        `library.json was written by a newer build (v${version}); this page reads v${LIBRARY_SCHEMA} ` +
        'and will not overwrite it. Nothing on this page will be saved until it is replaced.',
      newerVersion: version,
      readOnly: true,
    };
  }

  const items: LibraryItem[] = [];
  const seen = new Set<string>();
  const rawItems = Array.isArray(body.items) ? body.items : [];
  for (const entry of rawItems) {
    const item = parseItem(entry);
    if (item === null) continue;
    // First writer wins, as `Library.of` does: a duplicated id is a corrupt blob,
    // and obeying the second copy would let one row replace another.
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    items.push(item);
  }

  const applied: SurfaceKey[] = [];
  const rawApplied = Array.isArray(body.applied) ? body.applied : null;
  if (rawApplied) {
    for (const key of rawApplied) {
      if (key === 'home' || key === 'lock') applied.push(key);
    }
  }
  if (applied.length === 0) applied.push('home');

  // A selection that names nothing is a dangling pointer; the app corrects it on
  // every read and so does this, because a restore can bring one back.
  const active =
    typeof body.active === 'string' && items.some((item) => item.id === body.active)
      ? body.active
      : null;

  return {
    library: {
      sort: isSortKey(body.sort) ? body.sort : 'added_desc',
      applied,
      active,
      items,
    },
    status: 'ok',
    message: null,
    newerVersion: 0,
    readOnly: false,
  };
}

function corrupt(detail: string): LibraryRead {
  return {
    library: emptyLibrary(),
    status: 'corrupt',
    message: `${detail} This page starts with an empty gallery; the unreadable file is kept as library.corrupt.json.`,
    newerVersion: 0,
    readOnly: false,
  };
}

/**
 * One row, or null when it is unusable.
 *
 * The id must be a plain token (it is interpolated into a filename), and every
 * item must name an image — the bundled demo excepted, because its files ship
 * inside the phone app. Every other field defaults rather than throwing, which is
 * what makes a v1/v2 flat row read as a v5 one: the settings reader owns the
 * clamps, so a corrupt value lands on a picture rather than on an exception.
 */
function parseItem(value: unknown): LibraryItem | null {
  if (!isRecord(value)) return null;
  const id = typeof value.id === 'string' ? value.id : '';
  if (!isValidId(id)) return null;

  // `LibraryItem.normaliseName`'s rule: a basename, never a path, and the four
  // character string "null" is not a filename (Android's optString returns it for
  // a JSON null, and a parse-then-write cycle would then store it as a real name).
  const image = normaliseName(value.image);
  if (image === null && id !== BUNDLED_ID) return null;

  return {
    id,
    image,
    depth: normaliseName(value.depth),
    normal: normaliseName(value.normal),
    video: videoFrom(value.video),
    focal: positive(value.focal),
    splat: normaliseName(value.splat),
    splatLens: positive(value.splat_lens),
    // `RenderMode.parse`: anything this build does not know is the relief, because
    // this is read on the path to drawing and a render must not vanish.
    render: value.render === 'splat' ? 'splat' : 'relief',
    label: typeof value.label === 'string' && value.label !== '' ? value.label : null,
    // A negative stamp would sort ahead of everything and could never be reached
    // again; unknown is 0, which sorts last in every time order.
    addedAt: Math.max(0, asInt(value.added_at, 0)),
    lastUsedAt: Math.max(0, asInt(value.last_used_at, 0)),
    // Absent means live: every blob written before the flag existed is unarchived.
    archived: value.archived === true,
    home: homeFrom(value),
    lock: lockFrom(value),
  };
}

/**
 * The home surface's settings, from the nested v3 shape or the flat one.
 *
 * Both are read here rather than relying on a migration, so a hand-edited or
 * third-party blob cannot silently lose a photo's framing. A flat row predates
 * per-photo Motion, and its `origin_*` was spelled `orbit_*`; the app reads both
 * spellings and so does this. The four splat-look values postdate the flat shape
 * entirely, so they are the defaults there — exactly as in `homeFrom`.
 */
function homeFrom(row: Record<string, unknown>): SurfaceSettings {
  const surfaces = isRecord(row.surfaces) ? row.surfaces : null;
  const home = surfaces && isRecord(surfaces.home) ? surfaces.home : null;
  if (home) return settingsFromJson(home);

  const flatKeys = [
    'intensity',
    'zoom',
    'orbit_x',
    'origin_x',
    'pan_x',
    'pan_y',
    'motion',
    'light_azimuth',
    'light_elevation',
  ];
  if (!flatKeys.some((key) => key in row)) return defaultSettings();

  const flat: Record<string, unknown> = {};
  if ('intensity' in row) flat.depth = row.intensity;
  if ('zoom' in row) flat.zoom = row.zoom;
  if ('motion' in row) flat.motion = row.motion;
  if ('origin_x' in row) flat.origin_x = row.origin_x;
  else if ('orbit_x' in row) flat.origin_x = row.orbit_x;
  if ('origin_y' in row) flat.origin_y = row.origin_y;
  else if ('orbit_y' in row) flat.origin_y = row.orbit_y;
  if ('pan_x' in row) flat.pan_x = row.pan_x;
  if ('pan_y' in row) flat.pan_y = row.pan_y;
  if ('brightness' in row) flat.brightness = row.brightness;
  if ('shadow' in row) flat.shadow = row.shadow;
  if ('light_azimuth' in row) flat.light_azimuth = row.light_azimuth;
  if ('light_elevation' in row) flat.light_elevation = row.light_elevation;
  return settingsFromJson(flat);
}

/** The lock override, or null for "the same as home". */
function lockFrom(row: Record<string, unknown>): SurfaceSettings | null {
  const surfaces = isRecord(row.surfaces) ? row.surfaces : null;
  if (!surfaces || !isRecord(surfaces.lock)) return null;
  return settingsFromJson(surfaces.lock);
}

/**
 * A clip's three filenames, or null.
 *
 * This page draws no clips, but a row that has one still has to round-trip and
 * still names files a backup must copy. A half-written clip object is no clip at
 * all — `VideoTracks` refuses one, because colour without depth renders flat with
 * no relief and no error — so both names are required here too.
 */
function videoFrom(value: unknown): Record<string, string> | null {
  if (!isRecord(value)) return null;
  const color = normaliseName(value.color);
  const depth = normaliseName(value.depth);
  if (color === null || depth === null) return null;
  const out: Record<string, string> = { color, depth };
  const normal = normaliseName(value.normal);
  if (normal !== null) out.normal = normal;
  return out;
}

// --------------------------------------------------------------- the writer

/**
 * The blob the app would store, key for key and in `Library.toJson`'s order.
 *
 * The ordering is not required by JSON, and it is kept anyway: a mirror diff of
 * two backups should be a diff of the library, not of an implementation. Values
 * that mean "absent" are omitted rather than written as null — the app's rule, so
 * a library this build never touched stays byte-identical.
 */
export function writeLibrary(library: WebLibrary): string {
  const out: Record<string, unknown> = {
    version: LIBRARY_SCHEMA,
    sort: library.sort,
    applied: library.applied,
    items: library.items.map(writeItem),
  };
  if (library.active !== null) out.active = library.active;
  return JSON.stringify(out);
}

function writeItem(item: LibraryItem): Record<string, unknown> {
  const surfaces: Record<string, unknown> = { home: settingsToJson(item.home) };
  if (item.lock !== null) surfaces.lock = settingsToJson(item.lock);

  // Built in `LibraryItem.toJson`'s order, including the conditional `image` in
  // its own slot rather than appended at the end.
  const out: Record<string, unknown> = { id: item.id };
  if (item.image !== null) out.image = item.image;
  out.added_at = item.addedAt;
  out.last_used_at = item.lastUsedAt;
  out.surfaces = surfaces;
  if (item.depth !== null) out.depth = item.depth;
  if (item.normal !== null) out.normal = item.normal;
  if (item.video !== null) out.video = item.video;
  // 0 is "unknown" and is never stored: a stored 0 and an absent key would be two
  // spellings of one state.
  if (item.focal > 0) out.focal = item.focal;
  if (item.splatLens > 0) out.splat_lens = item.splatLens;
  if (item.splat !== null) out.splat = item.splat;
  // Only when it is not the relief, so a library of ordinary photographs stays
  // byte-identical to one this build never touched.
  if (item.render !== 'relief') out.render = item.render;
  if (item.label !== null) out.label = item.label;
  if (item.archived) out.archived = true;
  return out;
}

/** Give one surface its own values, carrying every other field across. */
export function withSettings(
  item: LibraryItem,
  surface: SurfaceKey,
  settings: SurfaceSettings,
): LibraryItem {
  return surface === 'home' ? { ...item, home: settings } : { ...item, lock: settings };
}

// ------------------------------------------------------------- derived facts

/** What a list should show: the label if there is one, else the filename. */
export function displayName(item: LibraryItem): string {
  if (item.label !== null && item.label !== '') return item.label;
  if (item.image !== null) return item.image;
  return 'the bundled demo';
}

/**
 * Every file this row names, in one list.
 *
 * The mirror copies exactly these, and the gallery resolves the first two. A name
 * is never a path: the store and the folder are both flat, which is what makes a
 * backup portable.
 */
export function fileNamesOf(item: LibraryItem): string[] {
  const names: string[] = [];
  if (item.image !== null) names.push(item.image);
  if (item.depth !== null) names.push(item.depth);
  if (item.normal !== null) names.push(item.normal);
  if (item.splat !== null) names.push(item.splat);
  if (item.video !== null) {
    for (const key of ['color', 'depth', 'normal']) {
      const name = item.video[key];
      if (typeof name === 'string' && name !== '') names.push(name);
    }
  }
  return [...new Set(names)];
}

/**
 * The colour image's filename for a new row.
 *
 * Derived from the id exactly as `LibraryItem.imageFileName` does, so two rows
 * cannot share a file and a re-import cannot overwrite a photo. The extension is
 * the dropped file's own, because this page keeps the original bytes: a JPEG
 * named `.png` would be a file whose name is a claim about its contents. An
 * unusable extension falls back to `png`, which the browser decodes by content
 * anyway.
 */
export function imageNameFor(id: string, original: string): string {
  const dot = original.lastIndexOf('.');
  const candidate = dot > 0 ? original.slice(dot + 1).toLowerCase() : '';
  const ext = /^[a-z0-9]{1,5}$/.test(candidate) ? candidate : 'png';
  return `item-${id}.${ext}`;
}

/** The cloud's filename for a new row; `.splat` beside the still, as the app writes it. */
export function splatNameFor(id: string): string {
  return `item-${id}.splat`;
}

/** Rows in the library's own read order. Ties fall to the most recently added. */
export function sortedRows(library: WebLibrary): LibraryItem[] {
  const rows = [...library.items];
  if (library.sort === 'name_asc') {
    return rows.sort((a, b) => {
      const byLabel = displayName(a).localeCompare(displayName(b), undefined, {
        sensitivity: 'accent',
      });
      // A total order, or a list can re-order rows between passes.
      return byLabel !== 0 ? byLabel : displayName(a) < displayName(b) ? -1 : 1;
    });
  }
  const key = (row: LibraryItem): number =>
    library.sort === 'recently_used' ? row.lastUsedAt : row.addedAt;
  // Reverse first, so a stable sort breaks a tie toward the row added last —
  // `Library.sorted`'s own construction, not a second comparison that could
  // disagree with it.
  return rows.reverse().sort((a, b) => key(b) - key(a));
}

/** Record that a row was opened, which is this page's "set as wallpaper". */
export function markUsed(library: WebLibrary, id: string, when: number): WebLibrary {
  let found = false;
  const items = library.items.map((item) => {
    if (item.id !== id) return item;
    found = true;
    return { ...item, lastUsedAt: when };
  });
  if (!found) return library;
  return { ...library, items, active: id };
}

// ------------------------------------------------------------------ helpers

/** `LibraryItem.isValidId`: one non-blank token, no path separator, 64 max. */
export function isValidId(id: string): boolean {
  return (
    id.length > 0 &&
    id.length <= 64 &&
    id.trim() !== '' &&
    !id.includes('/') &&
    !id.includes('\\')
  );
}

/**
 * `LibraryItem.normaliseName`: a basename, or null.
 *
 * A backslash is rejected rather than collapsed, because on Linux it is an
 * ordinary character in a filename — treating it as a separator would be guessing
 * at a path the writer did not mean.
 */
export function normaliseName(stored: unknown): string | null {
  if (typeof stored !== 'string') return null;
  if (stored === 'null') return null;
  // Java's File.getName() drops any directory part; do the same, including a
  // trailing separator, so both sides resolve the same basename.
  const trimmed = stored.replace(/\/+$/, '');
  const base = trimmed.slice(trimmed.lastIndexOf('/') + 1);
  if (base === '' || base === '.' || base === '..') return null;
  if (base.includes('/') || base.includes('\\')) return null;
  for (let i = 0; i < base.length; i++) {
    const code = base.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) return null;
  }
  return base;
}

function isSortKey(value: unknown): value is SortKey {
  return typeof value === 'string' && (SORT_KEYS as readonly string[]).includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `JSONObject.optInt`: a number truncates toward zero, anything else defaults. */
function asInt(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : fallback;
}

/** A length or ratio where 0 is "unknown" and nothing else non-positive means anything. */
function positive(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

function reasonOf(err: unknown): string {
  return err instanceof Error ? err.message : 'the file could not be read';
}
