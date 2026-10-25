/**
 * Where the library actually lives: the browser's origin-private file system.
 * **OPFS is the truth, and that is the same decision the app made one platform
 * over.** The app's `filesDir` is the single source of truth and a user-chosen
 * folder is a write-through mirror with an explicit Back up now / Restore — never
 * external-as-truth, because a folder is a *permission*: it is revoked, an SD card
 * unmounts, a cloud drive goes offline, and with the folder as truth every render
 * path needs an "unavailable" state and a missing folder becomes an empty
 * library. `TODO.md` records that decision; this module is its web half.
 *
 * OPFS rather than the picker for the truth because it needs no gesture, no
 * permission and no user decision: `navigator.storage.getDirectory()` works on a
 * phone, in a private window, and on the first paint. The folder API does not.
 *
 * The layout is flat and unremarkable on purpose:
 *
 *     basrelief/
 *       library.json        the app's Library v5 blob (see library.ts)
 *       prefs.json          the app's app prefs — comfy_url/model/history (see prefs.ts)
 *       playlists.json      optional, opaque — the app's own blob, copied not read
 *       item-<id>.<ext>     one colour image per row, original bytes
 *       item-<id>.splat     a Gaussian cloud, when a row has one
 *
 * Flat, and names not paths, because `PrefFiles`' rule is what makes a backup
 * portable: the same directory copied into the phone's `filesDir` resolves, and
 * `library.json`'s contents are the phone's `library` pref.
 *
 * **When OPFS is unavailable the page still works, and says what that costs.** A
 * browser without it gets an in-memory store: import, view, tune, generate and
 * save all behave, and a refresh empties the gallery — which is what this page did
 * before the store existed, so it is a smaller lie than refusing to open.
 */
/**
 * The app's Library blob, as one file.
 *
 * The phone keeps this JSON under the `library` pref key; a folder needs a name,
 * and `library.json` says what it is without a lookup table.
 */
export const LIBRARY_FILE = 'library.json';

/**
 * What a store is, so OPFS and the in-memory fallback are one type.
 *
 * Deliberately small: read, write, remove, list, and the library as text. Nothing
 * here knows about photographs, rows or the mirror — those are the callers' — so
 * the mirror can drive the same interface over a *picked folder* and there is one
 * implementation of "a write" rather than two.
 */
export interface MediaStore {
  /** Where the bytes actually are, for the sentence the page shows. */
  readonly kind: 'opfs' | 'memory';
  list(): Promise<string[]>;
  read(name: string): Promise<File | null>;
  /**
   * The file's length, without its bytes.
   *
   * A splat cloud's count is `bytes / 32`, so the card can state it from a HEAD
   * request instead of downloading a 13 MB file to divide its size by 32.
   */
  size(name: string): Promise<number | null>;
  write(name: string, data: FileData): Promise<void>;
  remove(name: string): Promise<void>;
  readText(name: string): Promise<string | null>;
  writeText(name: string, text: string): Promise<void>;
  /** Bytes used and the quota, or null when the browser will not say. */
  usage(): Promise<{ usage: number; quota: number } | null>;
}

/**
 * The app's second blob: playlists, carrying its own version and its own shape.
 *
 * This page does not read it — playlists do not rotate here — but a backup folder
 * that has one must not lose it on a Restore, so it is copied through untouched.
 */
export const PLAYLISTS_FILE = 'playlists.json';

/** The OPFS subdirectory that holds everything this page writes. */
export const STORE_DIR = 'basrelief';

/** What the page can say about the store's own state. */
export interface StoreInfo {
  store: MediaStore;
  /** A sentence about a degraded store, or null when OPFS is here. */
  message: string | null;
}

/**
 * Open the store.
 *
 * Never throws: a browser without OPFS answers a memory store plus the sentence
 * that says nothing will survive a refresh.
 */
export async function openStore(): Promise<StoreInfo> {
  const storage = typeof navigator === 'undefined' ? undefined : navigator.storage;
  if (!storage || typeof storage.getDirectory !== 'function') {
    return {
      store: memoryStore(),
      message:
        'This browser has no origin-private file system, so nothing is saved: ' +
        'a refresh empties the gallery. Everything else on the page still works for this session.',
    };
  }
  try {
    const root = await storage.getDirectory();
    const dir = await root.getDirectoryHandle(STORE_DIR, { create: true });
    return { store: opfsStore(dir), message: null };
  } catch (err) {
    return {
      store: memoryStore(),
      message:
        `This browser would not open its file storage (${reasonOf(err)}), so nothing is saved: ` +
        'a refresh empties the gallery. Everything else on the page still works for this session.',
    };
  }
}

/** The bytes a caller can hand to a store or a folder. */
export type FileData = Blob | ArrayBuffer | ArrayBufferView | string;

/** A directory that can be walked; `values()` is not in every lib.dom build. */
interface WalkableDirectory extends FileSystemDirectoryHandle {
  values(): AsyncIterableIterator<FileSystemFileHandle>;
}

/**
 * One file's bytes as a Blob.
 *
 * The DOM's write type wants an `ArrayBuffer`-backed view, while a caller may hold
 * a `Uint8Array<ArrayBufferLike>` (its bytes could be on a shared buffer). Copying
 * into a fresh array both satisfies the type and means the file cannot change
 * after it was reported as written.
 */
function asBlob(data: FileData): Blob {
  if (data instanceof Blob) return data;
  if (typeof data === 'string') return new Blob([data]);
  if (data instanceof ArrayBuffer) return new Blob([data]);
  // An ArrayBufferView: copy its bytes into a buffer of their own, which is both
  // what the DOM's ArrayBuffer-only write type needs and what keeps the file
  // stable if the caller reuses its buffer.
  const view = data as ArrayBufferView;
  const copy = new Uint8Array(view.byteLength);
  copy.set(new Uint8Array(view.buffer, view.byteOffset, view.byteLength));
  return new Blob([copy]);
}

/**
 * Write one file into a directory handle.
 *
 * Shared by the store and the mirror so there is one implementation of "a write",
 * and therefore one place a quota or revocation error can come from. A
 * `createWritable` truncates by default, which is what an overwrite means here.
 */
export async function writeInto(
  dir: FileSystemDirectoryHandle,
  name: string,
  data: FileData,
): Promise<void> {
  const handle = await dir.getFileHandle(name, { create: true });
  const writable = await handle.createWritable();
  try {
    await writable.write(asBlob(data));
  } finally {
    // `close` commits and `abort` discards; an exception inside `write` must not
    // leave a half-written file claiming success.
    await writable.close();
  }
}

/**
 * Bundled assets that ship with the page and are never written by it.
 *
 * The marbled bust is the app's own preload, and the gallery starts from the
 * same picture rather than an empty shelf. These are fetchable URLs, not OPFS
 * entries: the store stays the truth for anything the user imported, and the
 * bundle is only consulted when the store does not have the file.
 */
// Flat, because a row stores a BARE filename: `library.ts` normalises
// `demo/image.png` to `image.png` on parse, which is why the first attempt lost
// the prefix and the card could not resolve its own photograph. The mapping from
// `demo-foo` to the shipped `demo/foo` lives here, one place.
const BUNDLED_PREFIX = 'demo-';
const BUNDLED_DIR = 'demo/';

function bundlePath(name: string): string | null {
  if (!name.startsWith(BUNDLED_PREFIX)) return null;
  return BUNDLED_DIR + name.slice(BUNDLED_PREFIX.length);
}

const BUNDLED_CACHE = 'basrelief-bundled';

/**
 * The bundle's own cache, because GitHub Pages sends `max-age=600` and no
 * validator: without one, every reload older than ten minutes re-downloads the
 * 2.5 MB photograph on a link where that is a minute of staring at "reading…".
 * The Cache API is the tool for exactly this and needs no naming scheme.
 */
async function cachedResponse(url: URL): Promise<Response | null> {
  try {
    const cache = await caches.open(BUNDLED_CACHE);
    return (await cache.match(url)) ?? null;
  } catch {
    return null;
  }
}

async function rememberResponse(url: URL, response: Response): Promise<void> {
  try {
    const cache = await caches.open(BUNDLED_CACHE);
    await cache.put(url, response.clone());
  } catch {
    // A browser without the Cache API keeps plain fetches; it still works.
  }
}

async function bundledSize(name: string): Promise<number | null> {
  const path = bundlePath(name);
  if (path === null) return null;
  try {
    const url = new URL(path, document.baseURI);
    const cached = await cachedResponse(url);
    if (cached !== null) return (await cached.blob()).size;
    // A RANGE, not HEAD. GitHub Pages gzips this file, so `content-length` on a
    // HEAD is the COMPRESSED length (4,223,960 for a 4,784,128-byte cloud) and a
    // count of `size / 32` is wrong by exactly the ratio. `content-range` states
    // the true total, and asking for one byte costs one byte.
    const response = await fetch(url, { headers: { Range: 'bytes=0-0' } });
    const range = response.headers.get('content-range');
    await response.body?.cancel();
    if (response.status === 206 && range !== null) {
      const total = Number(range.split('/')[1]);
      if (Number.isFinite(total) && total > 0) return total;
    }
    return null;
  } catch {
    return null;
  }
}

async function readBundled(name: string): Promise<File | null> {
  const path = bundlePath(name);
  if (path === null) return null;
  try {
    const url = new URL(path, document.baseURI);
    const cached = await cachedResponse(url);
    if (cached !== null) {
      const blob = await cached.blob();
      return new File([blob], name, { type: blob.type });
    }
    const response = await fetch(url);
    if (!response.ok) return null;
    await rememberResponse(url, response);
    const blob = await response.blob();
    return new File([blob], name, { type: blob.type });
  } catch {
    return null;
  }
}

/** Read one file from a directory handle, or null when it is not there. */
export async function readFrom(
  dir: FileSystemDirectoryHandle,
  name: string,
): Promise<File | null> {
  try {
    const handle = await dir.getFileHandle(name);
    return await handle.getFile();
  } catch (err) {
    if (isMissing(err)) return null;
    throw err;
  }
}

/** Remove one file from a directory handle; an absent file is not an error. */
export async function removeFrom(
  dir: FileSystemDirectoryHandle,
  name: string,
): Promise<void> {
  try {
    await dir.removeEntry(name);
  } catch (err) {
    if (isMissing(err)) return;
    throw err;
  }
}

/** The names of the files directly inside a directory handle. */
export async function listIn(dir: FileSystemDirectoryHandle): Promise<string[]> {
  const names: string[] = [];
  for await (const handle of (dir as WalkableDirectory).values()) {
    if (handle.kind === 'file') names.push(handle.name);
  }
  return names.sort();
}

/**
 * One sentence for a storage failure.
 *
 * The two that matter are named rather than passed through: a quota error is a
 * full disk with a user action behind it, and a revoked folder is a permission
 * that can come back — `NotAllowedError` on a mirror write is not "broken", it is
 * "ask again".
 */
export function describeStoreError(err: unknown): string {
  const name = err instanceof DOMException ? err.name : '';
  switch (name) {
    case 'QuotaExceededError':
    case 'NS_ERROR_DOM_QUOTA_REACHED':
      return (
        'This browser’s storage is full, so that file was not saved. ' +
        'Delete a photo or clear this site’s data, then try again.'
      );
    case 'NotAllowedError':
      return 'The browser refused permission to write to that folder.';
    case 'NotFoundError':
      return 'That folder or file is no longer there.';
    case 'SecurityError':
      return 'The browser blocked storage access on this page.';
    default:
      return err instanceof Error ? err.message : 'the file could not be written';
  }
}

/** True for the errors a missing file answers with, on both engines. */
export function isMissing(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'NotFoundError';
}

/** True for the errors a revoked permission answers with. */
export function isRefused(err: unknown): boolean {
  return err instanceof DOMException && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
}

// ------------------------------------------------------------- the two stores

function opfsStore(dir: FileSystemDirectoryHandle): MediaStore {
  // A name with a separator is bundle-only: OPFS has no directories here, and
  // `getFileHandle('demo/image.png')` throws TypeError("Name is not allowed")
  // rather than answering "missing", so it would escape every fallback.
  const read = async (name: string): Promise<File | null> => {
    if (name.includes('/')) return readBundled(name);
    return (await readFrom(dir, name)) ?? (await readBundled(name));
  };
  const size = async (name: string): Promise<number | null> => {
    if (name.includes('/')) return bundledSize(name);
    const file = await readFrom(dir, name);
    return file?.size ?? (await bundledSize(name));
  };
  return {
    kind: 'opfs',
    list: () => listIn(dir),
    read,
    size,
    write: (name, data) => writeInto(dir, name, data),
    remove: (name) => removeFrom(dir, name),
    async readText(name) {
      const file = await read(name);
      return file === null ? null : file.text();
    },
    writeText: (name, text) =>
      writeInto(dir, name, new Blob([text], { type: 'application/json' })),
    async usage() {
      try {
        const estimate = await navigator.storage.estimate();
        return { usage: estimate.usage ?? 0, quota: estimate.quota ?? 0 };
      } catch {
        return null;
      }
    },
  };
}

/**
 * The fallback: the tab is the store.
 *
 * Deliberately the same interface, so no caller has a second branch for "no
 * OPFS" — the only place that difference may appear is the sentence the page
 * shows, and the fact that a refresh empties it.
 */
function memoryStore(): MediaStore {
  const files = new Map<string, File>();
  return {
    kind: 'memory',
    async list() {
      return [...files.keys()].sort();
    },
    async read(name) {
      if (name.includes('/')) return readBundled(name);
      return files.get(name) ?? (await readBundled(name));
    },
    async size(name) {
      if (name.includes('/')) return bundledSize(name);
      return files.get(name)?.size ?? (await bundledSize(name));
    },
    async write(name, data) {
      const bytes = asBlob(data);
      // Copy through a Blob so a caller that mutates its buffer later cannot
      // change a file the store already reports as written.
      files.set(name, new File([bytes], name, { type: bytes.type }));
    },
    async remove(name) {
      files.delete(name);
    },
    async readText(name) {
      const file = files.get(name);
      return file === undefined ? null : file.text();
    },
    writeText(name, text) {
      files.set(name, new File([text], name, { type: 'application/json' }));
      return Promise.resolve();
    },
    async usage() {
      let usage = 0;
      for (const file of files.values()) usage += file.size;
      return { usage, quota: 0 };
    },
  };
}

function reasonOf(err: unknown): string {
  return err instanceof Error ? err.message : 'the storage could not be opened';
}
