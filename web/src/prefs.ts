/**
 * The app's prefs beside its library: the ComfyUI server, and the model choices.
 *
 * The app's prefs, not its library: a server address, a model choice and the
 * studio sheet's remembered position are app/device settings with no photograph to
 * live on, so the phone keeps them under `comfy_url`, `comfy_model`,
 * `comfy_model_history`, `sheet_pos` and `sheet_open_pos` in `SharedPreferences
 * depthwall` while the rows go in the `library` blob. This module writes those
 * keys in one `prefs.json` beside `library.json`, so a file copied between the two
 * means the same thing on both sides.
 *
 * The shape is the app's, not a new schema:
 *
 *     {
 *       "comfy_url": "http://box:8188",
 *       "comfy_model": "moge_3_vitg_fp16.safetensors",
 *       "comfy_model_history": "moge_3_vitg_fp16.safetensors\nDepthAnythingV2",
 *       "sheet_pos": 0.4213,
 *       "sheet_open_pos": 0.4213
 *     }
 *
 * A key equal to its default is **not written**, the `Library` writer's rule, so
 * a library this build never touched keeps its bytes. The defaults are the app's
 * own `getString` defaults: `comfy_url` is `''` (and this page draws the dev
 * proxy's `/comfy` for it), `comfy_model` is absent (no run has been submitted),
 * and `comfy_model_history` is `''` (no history). The history is the app's
 * **newline-separated** form rather than a JSON array, because that is the string
 * `ModelRanking.encode` writes; a `prefs.json` carrying one is the same value the
 * phone reads.
 *
 * **It never throws.** A blob that is not JSON, or not an object, is the empty
 * prefs plus a sentence, and the caller keeps the original as
 * `prefs.corrupt.json` — the `library.corrupt.json` precedent. A single key of the
 * wrong type is that key's default rather than a dead page: the app's prefs rule
 * is that a value it cannot read is the default, never a guess. Keys this build
 * does not know are carried through a rewrite untouched, for the same reason the
 * index is not rewritten on load: an older build must not erase a newer one's
 * fields.
 *
 * **The history is `ModelRanking`'s rule**, implemented twice because the two
 * languages cannot share code: most recent first, then discovery order, capped at
 * four and deduplicated, and a model the server no longer offers is **skipped**
 * rather than invented, so `rankModels` is always a permutation of what was
 * discovered. It is recorded on the **selection**, not on a finished run: tapping
 * a checkpoint and backing out still says which one the user reaches for. The
 * single `comfy_model` is the different fact — the model a run was last sent to —
 * and is written at submission, as the app's `BuildDialog` does.
 *
 * This module also holds the tab's live copy of those values, which is the app's
 * arrangement one platform over: `SharedPreferences` is the transport, and the
 * panel keeps `comfyBase`/`savedModel`/`modelHistory` in memory between reads and
 * writes. The gallery binds a sink with `bindPrefsSink` once it has a store; a
 * change publishes the whole prefs object to it, and the gallery is the only
 * thing that writes `prefs.json`. Tests and the import graph get the arithmetic
 * without a browser.
 */

/** The file's name, beside `library.json`. */
export const PREFS_FILE = 'prefs.json';

/** Where an unreadable prefs blob is kept, as `library.corrupt.json` is. */
export const PREFS_CORRUPT_FILE = 'prefs.corrupt.json';

/**
 * The address the page uses when `comfy_url` is empty: the dev server's proxy.
 *
 * The stored default is `''`, the app's own, because the app has no proxy route.
 * `/comfy` is this page's meaning for it, and `comfy.ts` normalises it once at the
 * request.
 */
export const DEFAULT_BASE_URL = '/comfy';

/** The app's three ComfyUI keys, and the whole of what that group reads or writes. */
export const PREF_KEYS = ['comfy_url', 'comfy_model', 'comfy_model_history'] as const;

/**
 * The studio sheet's remembered position: `MainActivity.KEY_SHEET` and
 * `KEY_SHEET_OPEN`.
 *
 * Two keys, not one, for the app's own reason: `sheet_pos` is 1.0 while the sheet
 * is away, so it cannot also answer "how far was it open" — one key holding both
 * the current state and the place to return to is how a pill tap ends up snapping
 * the sheet fully open and discarding the position the user chose. They are
 * **numbers in 0..1**, absent when nothing has been stored (the app's `-1`
 * sentinel is a float storage detail; a missing JSON key says the same thing).
 */
export const SHEET_POS_KEY = 'sheet_pos';
export const SHEET_OPEN_KEY = 'sheet_open_pos';


/**
 * `ModelRanking.HISTORY_CAP`, which `scripts/check-parity.mjs` holds to this one.
 *
 * Four is enough to keep the usual rotation of checkpoints within one tap of the
 * top, and short enough that the tail of the list is still discovery order.
 */
export const MODEL_HISTORY_CAP = 4;

/** One blob's worth of app prefs, decoded. */
export interface AppPrefs {
  /** The address as typed, or `''` for the proxy. */
  comfyUrl: string;
  /** The model the last run was sent to, or `''` for none. */
  comfyModel: string;
  /** Recent selections, most recent first, at most `MODEL_HISTORY_CAP`. */
  comfyModelHistory: string[];
  /** `sheet_pos`: the sheet's collapse fraction, or null for nothing stored. */
  sheetPos: number | null;
  /** `sheet_open_pos`: where a pill tap returns to, or null for nothing stored. */
  sheetOpen: number | null;
}

export type PrefsStatus = 'ok' | 'empty' | 'corrupt';

export interface PrefsRead {
  prefs: AppPrefs;
  /** Keys this build does not know, carried through a rewrite. */
  extra: Record<string, unknown>;
  status: PrefsStatus;
  /** A sentence for the page, or null when there is nothing to say. */
  message: string | null;
}

/** The app's defaults: no address, no run's model, no history, no sheet position. */
export function defaultPrefs(): AppPrefs {
  return {
    comfyUrl: '',
    comfyModel: '',
    comfyModelHistory: [],
    sheetPos: null,
    sheetOpen: null,
  };
}

// ------------------------------------------------------------------- the blob

/**
 * Parse a stored blob.
 *
 * The version question does not arise: the app's prefs have no schema number, so
 * a key this build does not know is preserved rather than interpreted, and a key
 * whose value is the wrong type falls back to its default. Nothing here throws,
 * because this runs on the path to drawing a page.
 */
export function readPrefs(raw: string | null): PrefsRead {
  if (raw === null || raw.trim() === '') {
    return { prefs: defaultPrefs(), extra: {}, status: 'empty', message: null };
  }

  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch (err) {
    return corrupt(`prefs.json is not readable JSON (${reasonOf(err)})`);
  }
  if (!isRecord(body)) return corrupt('prefs.json is not a JSON object.');

  const prefs = defaultPrefs();
  const extra: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (key === 'comfy_url') {
      // The address is trimmed on read as well as on write: whitespace is not part
      // of a URL, and the field the app stores is its own trimmed text.
      prefs.comfyUrl = typeof value === 'string' ? value.trim() : '';
    } else if (key === 'comfy_model') {
      // A model name is a filename the node chose; it is taken verbatim, never
      // trimmed, because this is a value compared against `/object_info`.
      prefs.comfyModel = typeof value === 'string' ? value : '';
    } else if (key === 'comfy_model_history') {
      prefs.comfyModelHistory = decodeHistory(value);
    } else if (key === SHEET_POS_KEY) {
      prefs.sheetPos = decodeFraction(value);
    } else if (key === SHEET_OPEN_KEY) {
      prefs.sheetOpen = decodeFraction(value);
    } else {
      extra[key] = value;
    }
  }
  return { prefs, extra, status: 'ok', message: null };
}

/**
 * A stored collapse fraction, or null for "nothing stored".
 *
 * The app's sentinel is the float `-1`; here a missing key is the same fact, so a
 * negative number — which is only ever a drag's over-open tether, never a rest
 * position — is no position at all. A value outside 0..1 is clamped rather than
 * refused: a hand-edited 1.4 can only mean "fully away".
 */
function decodeFraction(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null;
  return value > 1 ? 1 : value;
}

function corrupt(detail: string): PrefsRead {
  return {
    prefs: defaultPrefs(),
    extra: {},
    status: 'corrupt',
    message:
      `${detail} This page starts from the app’s defaults for the ComfyUI address and model; ` +
      `the unreadable file is kept as ${PREFS_CORRUPT_FILE}.`,
  };
}

/**
 * The blob the app would store, for the keys this build knows.
 *
 * A default value is omitted rather than written, so a prefs file this build
 * never touched stays byte-identical — and, like the index, a rewrite carries
 * through every key this build does not understand.
 */
export function writePrefs(prefs: AppPrefs, extra: Record<string, unknown> = {}): string {
  const out: Record<string, unknown> = {};
  if (prefs.comfyUrl !== '') out.comfy_url = prefs.comfyUrl;
  if (prefs.comfyModel !== '') out.comfy_model = prefs.comfyModel;
  const history = encodeHistory(prefs.comfyModelHistory);
  if (history !== '') out.comfy_model_history = history;
  if (prefs.sheetPos !== null) out[SHEET_POS_KEY] = prefs.sheetPos;
  if (prefs.sheetOpen !== null) out[SHEET_OPEN_KEY] = prefs.sheetOpen;
  for (const [key, value] of Object.entries(extra)) {
    if (KNOWN_KEYS.includes(key)) continue;
    out[key] = value;
  }
  return JSON.stringify(out);
}

/** Every key this build owns, so a stale `extra` copy can never overwrite one. */
const KNOWN_KEYS: readonly string[] = [
  ...PREF_KEYS,
  SHEET_POS_KEY,
  SHEET_OPEN_KEY,
];

// ------------------------------------------------------------- model history

/**
 * `ModelRanking.decode`: the app's newline form as a history.
 *
 * Empty entries and duplicates collapse and the list is capped, so a hand-edited
 * file cannot produce a longer or repeated list. Anything that is not a stored
 * string — an array, a number, null — is no history, which is the app's default.
 */
export function decodeHistory(stored: unknown): string[] {
  if (typeof stored !== 'string' || stored.length === 0) return [];
  const out: string[] = [];
  for (const part of stored.split('\n')) {
    if (part.length === 0 || out.includes(part)) continue;
    if (out.length >= MODEL_HISTORY_CAP) break;
    out.push(part);
  }
  return out;
}

/**
 * `ModelRanking.encode`: one model per line, and the empty string for no history.
 *
 * Newlines rather than JSON because a checkpoint filename cannot contain one, and
 * because this string is exactly what the phone's prefs hold. Deduplication and
 * the cap belong to `rememberSelected`/`decodeHistory`, as they do in Java; this
 * is the stored form and nothing else.
 */
export function encodeHistory(history: readonly string[]): string {
  const kept: string[] = [];
  for (const name of history) {
    if (name.length === 0 || name.includes('\n')) continue;
    kept.push(name);
  }
  return kept.join('\n');
}

/**
 * `ModelRanking.rank`: `history` first, most recent first, then discovery order.
 *
 * A history entry the server no longer offers is skipped rather than inserted, so
 * the answer is always a permutation of `models`: every entry came from it, and
 * none is lost. Duplicates in either input collapse, so a list cannot grow two
 * rows for one model.
 */
export function rankModels(models: readonly string[], history: readonly string[]): string[] {
  const out: string[] = [];
  for (const recent of history) {
    if (recent.length === 0 || out.includes(recent)) continue;
    if (models.includes(recent)) out.push(recent);
  }
  for (const model of models) {
    if (model.length > 0 && !out.includes(model)) out.push(model);
  }
  return out;
}

/**
 * `ModelRanking.remember`: the history after a selection.
 *
 * Called on the **selection**, not on a finished run — a user who taps a
 * checkpoint and backs out has still said which one they reach for. The chosen
 * model moves to the front, an earlier occurrence is removed, and the list is
 * capped; an empty model changes nothing.
 */
export function rememberSelected(history: readonly string[], model: string): string[] {
  const out: string[] = [];
  if (model.length > 0) out.push(model);
  for (const previous of history) {
    if (previous.length === 0 || out.includes(previous)) continue;
    if (out.length >= MODEL_HISTORY_CAP) break;
    out.push(previous);
  }
  return out;
}

// --------------------------------------------------------------- the live copy

let current: AppPrefs = defaultPrefs();

/**
 * Where a change goes, or null before the gallery has opened a store.
 *
 * The app's panel is the same shape: `comfyBase`/`savedModel`/`modelHistory` are
 * read once when the panel opens and written at the events that change them, and
 * `SharedPreferences` is only the transport. Here the gallery owns `prefs.json`
 * and binds this; nothing in this module does I/O, so `comfy.ts` can read the
 * address and model without importing the store.
 */
let sink: ((prefs: AppPrefs) => void) | null = null;

/** Bind (or, with null, unbind) the one writer. Does not write anything itself. */
export function bindPrefsSink(next: ((prefs: AppPrefs) => void) | null): void {
  sink = next;
}

/**
 * Install the values read from `prefs.json`.
 *
 * Deliberately **does not publish**: loading must not rewrite the file, because a
 * rewrite drops keys this build does not know. The first real change writes it.
 */
export function hydratePrefs(prefs: AppPrefs): void {
  current = {
    comfyUrl: prefs.comfyUrl,
    comfyModel: prefs.comfyModel,
    comfyModelHistory: [...prefs.comfyModelHistory],
    sheetPos: prefs.sheetPos,
    sheetOpen: prefs.sheetOpen,
  };
}

/** A copy, so a sink cannot hold and mutate the live values. */
export function prefsNow(): AppPrefs {
  return {
    ...current,
    comfyModelHistory: [...current.comfyModelHistory],
  };
}

function publish(): void {
  if (sink !== null) sink(prefsNow());
}

/** The address the field shows and requests use; `''` means the proxy. */
export function getComfyUrl(): string {
  return current.comfyUrl || DEFAULT_BASE_URL;
}

/**
 * Remember a typed address.
 *
 * Stored trimmed, as the app's `persistTyped` does, and written as typed rather
 * than normalised: typing `http://` would otherwise be rewritten mid-word. The
 * request path normalises once, in `comfy.ts`. The proxy's own `/comfy` folds
 * back to the default `''`, so the file has one spelling for "the default".
 */
export function setComfyUrl(value: string): void {
  const trimmed = value.trim();
  const stored = trimmed === DEFAULT_BASE_URL ? '' : trimmed;
  if (stored === current.comfyUrl) return;
  current = { ...current, comfyUrl: stored };
  publish();
}

/** The model a run was last sent to, or `''`. */
export function getModelChoice(): string {
  return current.comfyModel;
}

/**
 * Record the model a run was **submitted** with.
 *
 * This is `comfy_model`, the app's `KEY_MODEL`: the single saved choice a run was
 * last sent to, written at the run rather than at the tap. It is a different fact
 * from the history — a model can be tapped without a run — and the app keeps them
 * apart for exactly that reason.
 */
export function setModelChoice(model: string): void {
  if (model === current.comfyModel) return;
  current = { ...current, comfyModel: model };
  publish();
}

/** The chooser's history, most recent first. A copy, so callers cannot mutate it. */
export function getModelHistory(): string[] {
  return [...current.comfyModelHistory];
}

/**
 * Record a model the user selected in the chooser.
 *
 * The selection is the signal, so this writes the history and nothing else; the
 * saved model moves when a run is submitted.
 */
export function selectModel(model: string): void {
  const history = rememberSelected(current.comfyModelHistory, model);
  if (sameList(history, current.comfyModelHistory)) return;
  current = { ...current, comfyModelHistory: history };
  publish();
}

// ------------------------------------------------------------------ the sheet

/**
 * Where the studio sheet last came to rest, as the app stores it.
 *
 * `fraction` is `sheet_pos` — 1.0 means the sheet was put away — and `open` is
 * `sheet_open_pos`, the place a pill tap returns to. Either is null when nothing
 * has been stored, which is a fresh install; the sheet then opens at its peek.
 */
export function getSheetPosition(): { fraction: number | null; open: number | null } {
  return { fraction: current.sheetPos, open: current.sheetOpen };
}

/**
 * Record where the sheet came to rest, exactly as `onSheetResting` does.
 *
 * Two writes, one event, and the second is conditional for the app's own reason:
 * a collapse must not overwrite the place to return to with 1.0, which is the bug
 * a single key would have. A release that changed nothing writes nothing — the
 * sheet's own rule, applied to its position.
 */
export function setSheetPosition(fraction: number, collapsed: boolean): void {
  const next = { ...current, sheetPos: fraction };
  if (!collapsed) next.sheetOpen = fraction;
  if (next.sheetPos === current.sheetPos && next.sheetOpen === current.sheetOpen) return;
  current = next;
  publish();
}

// ------------------------------------------------------------------ helpers

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function reasonOf(err: unknown): string {
  return err instanceof Error ? err.message : 'the file could not be read';
}
