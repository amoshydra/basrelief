/**
 * The write-through mirror: a folder the user picked, never a second truth.
 *
 * This is the app's storage decision, one platform over (`TODO.md`, "mirror, not
 * external-as-truth"): the library lives in OPFS, and a chosen folder is a copy
 * with an explicit **Back up now** and **Restore**. Nothing renders from the
 * folder, so a revoked permission, an unplugged drive or an offline cloud folder
 * pauses the mirror and cannot blank the gallery.
 *
 * **The handle is kept in IndexedDB because a `FileSystemHandle` is
 * structured-cloneable**, so the choice survives a reload. The *permission* does
 * not: after a reload the browser answers `prompt` (or `denied`), which is why
 * there is a granted state and a needs-permission state rather than "the folder is
 * broken". A handle that cannot be stored — a private window with IndexedDB
 * blocked — still works for this tab, and the page says it will have to be chosen
 * again.
 *
 * **A backup writes the index LAST, and a restore writes it last too.** Files are
 * content-addressed by name and the blob names them, so the order decides which
 * half-state a crash leaves: files without an index are orphans (harmless, and a
 * later backup rewrites them), while an index naming files that were never copied
 * is a library where some photographs do not open. The same asymmetry is why a
 * delete removes rows before files.
 */
import { PLAYLISTS_FILE, LIBRARY_FILE, describeStoreError, isMissing, isRefused, readFrom, writeInto } from './store.ts';
import { fileNamesOf, readLibrary } from './library.ts';
import type { MediaStore } from './store.ts';

export type MirrorStatus = 'unsupported' | 'none' | 'needs-permission' | 'ready' | 'unavailable';

export interface MirrorState {
  status: MirrorStatus;
  /** The remembered folder's name, or null. */
  name: string | null;
  /** The one sentence the page shows beneath the buttons. */
  message: string;
}

export interface RestoreResult {
  ok: boolean;
  message: string;
}

export interface Mirror {
  state(): MirrorState;
  onChange(listener: (state: MirrorState) => void): void;
  /** Load the remembered handle and ask the browser what it currently allows. */
  refresh(): Promise<void>;
  /** Choose a folder. Must be called from a gesture — the picker requires one. */
  choose(): Promise<void>;
  /** Ask for write permission again. Also a gesture. */
  grant(): Promise<void>;
  forget(): Promise<void>;
  backup(store: MediaStore): Promise<string>;
  restore(store: MediaStore): Promise<RestoreResult>;
}

const DB_NAME = 'basrelief-mirror';
const DB_VERSION = 1;
const STORE_NAME = 'handles';
const HANDLE_KEY = 'folder';

/** What the picker is, without depending on a Chromium-only type in lib.dom. */
interface DirectoryPickerWindow {
  showDirectoryPicker?: (options?: {
    id?: string;
    mode?: 'read' | 'readwrite';
  }) => Promise<FileSystemDirectoryHandle>;
}

/** The permission methods, which lib.dom does not always declare. */
interface PermissionedHandle extends FileSystemDirectoryHandle {
  queryPermission?: (descriptor: { mode: 'read' | 'readwrite' }) => Promise<PermissionState>;
  requestPermission?: (descriptor: { mode: 'read' | 'readwrite' }) => Promise<PermissionState>;
}

/**
 * The mirror has one state at a time, and each one has its own sentence.
 *
 * `unsupported` and `none` are deliberately different: the first says this browser
 * has no folder API (mobile, Firefox, Safari), the second says it does and nothing
 * has been chosen. Collapsing them would tell a phone user to "choose a folder"
 * with a button that cannot exist.
 */
export function createMirror(): Mirror {
  let current: MirrorState = {
    status: 'none',
    name: null,
    message: 'Nothing is backed up to a folder yet. The library lives in this browser.',
  };
  let handle: FileSystemDirectoryHandle | null = null;
  let handleRemembered = false;
  const listeners: Array<(state: MirrorState) => void> = [];

  function set(state: MirrorState): void {
    current = state;
    for (const listener of [...listeners]) listener(current);
  }

  function setFor(handleName: string, status: MirrorStatus, message: string): void {
    set({ status, name: handleName, message });
  }

  function supported(): boolean {
    if (typeof window === 'undefined') return false;
    const picker = (window as unknown as DirectoryPickerWindow).showDirectoryPicker;
    return typeof picker === 'function';
  }

  function unsupportedMessage(): string {
    return (
      'This browser has no folder picker, so it cannot back up to a folder — the library ' +
      'is still saved in this browser. A Chromium browser on a desktop can.'
    );
  }

  async function remember(next: FileSystemDirectoryHandle): Promise<void> {
    handle = next;
    handleRemembered = false;
    try {
      await putHandle(next);
      handleRemembered = true;
    } catch {
      // Private windows refuse IndexedDB outright. The handle still works for this
      // tab, and saying so is better than a silent second failure.
      handleRemembered = false;
    }
  }

  async function permissionOf(target: PermissionedHandle): Promise<PermissionState> {
    if (typeof target.queryPermission !== 'function') return 'granted';
    try {
      return await target.queryPermission({ mode: 'readwrite' });
    } catch {
      return 'prompt';
    }
  }

  /** The handle, if the browser will let us write to it right now. */
  async function usable(): Promise<FileSystemDirectoryHandle | null> {
    if (handle === null) {
      handle = await loadHandle();
      if (handle !== null) handleRemembered = true;
    }
    if (handle === null) {
      set({
        status: 'none',
        name: null,
        message: 'Nothing is backed up to a folder yet. The library lives in this browser.',
      });
      return null;
    }
    const name = handle.name;
    const permission = await permissionOf(handle);
    if (permission === 'granted') {
      setFor(name, 'ready', `Backed up to “${name}”. Nothing is read from this folder — it is a copy.`);
      return handle;
    }
    if (permission === 'denied') {
      setFor(
        name,
        'unavailable',
        `The browser will not let this page write to “${name}” any more. Nothing is lost — the ` +
          'library is in this browser. Choose the folder again to re-enable Back up now.',
      );
      return null;
    }
    setFor(
      name,
      'needs-permission',
      `“${name}” is remembered, but this page needs your permission to write to it again. ` +
        'Allow it, or choose another folder.',
    );
    return null;
  }

  async function fail(err: unknown, whileDoing: string): Promise<string> {
    const name = current.name ?? 'the folder';
    if (isRefused(err)) {
      setFor(
        name,
        'needs-permission',
        `Writing to “${name}” was refused while ${whileDoing}. Allow the folder again — ` +
          'nothing else is affected.',
      );
      return current.message;
    }
    if (isMissing(err)) {
      setFor(
        name,
        'unavailable',
        `“${name}” is no longer there (moved, unmounted or deleted) while ${whileDoing}. ` +
          'The library is safe in this browser; choose the folder again.',
      );
      return current.message;
    }
    const detail = describeStoreError(err);
    setFor(name, 'unavailable', `Could not use “${name}” while ${whileDoing}: ${detail}`);
    return current.message;
  }

  return {
    state: () => current,

    onChange(listener) {
      listeners.push(listener);
      listener(current);
    },

    async refresh() {
      if (!supported()) {
        set({ status: 'unsupported', name: null, message: unsupportedMessage() });
        return;
      }
      await usable();
      if (handle !== null && !handleRemembered) {
        set({
          ...current,
          message: `${current.message} This browser would not remember the choice, so it will have to be made again after a reload.`,
        });
      }
    },

    async choose() {
      if (!supported()) {
        set({ status: 'unsupported', name: null, message: unsupportedMessage() });
        return;
      }
      const picker = (window as unknown as DirectoryPickerWindow).showDirectoryPicker;
      if (picker === undefined) return;
      try {
        const picked = await picker.call(window, { id: 'basrelief-backup', mode: 'readwrite' });
        await remember(picked);
        // A freshly picked handle is granted for this tab, but ask rather than
        // assume: the same code path then covers a browser that answers `prompt`.
        const permission = await permissionOf(picked as PermissionedHandle);
        if (permission === 'granted') {
          setFor(
            picked.name,
            'ready',
            `Backed up to “${picked.name}”. Nothing is read from this folder — it is a copy.`,
          );
        } else {
          setFor(
            picked.name,
            'needs-permission',
            `“${picked.name}” is remembered; allow write access before backing up.`,
          );
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') {
          // The user closed the picker. That is not a failure, and the previous
          // folder (if any) is still the one.
          set({ ...current, message: `${current.message} No folder was chosen.` });
          return;
        }
        await fail(err, 'choosing it');
      }
    },

    async grant() {
      if (handle === null) {
        await usable();
        if (handle === null) return;
      }
      const permissioned = handle as PermissionedHandle;
      try {
        if (typeof permissioned.requestPermission === 'function') {
          await permissioned.requestPermission({ mode: 'readwrite' });
        }
      } catch (err) {
        await fail(err, 'asking for permission');
        return;
      }
      await usable();
    },

    async forget() {
      handle = null;
      handleRemembered = false;
      try {
        await deleteHandle();
      } catch {
        // Nothing to forget, or nothing that can forget: either way the handle is
        // gone from this tab, which is what the button means.
      }
      set({
        status: 'none',
        name: null,
        message: 'The folder has been forgotten. Nothing was deleted — it only ever held a copy.',
      });
    },

    async backup(store) {
      const target = await usable();
      if (target === null) return current.message;
      const name = target.name;
      try {
        const names = await store.list();
        let files = 0;
        for (const file of names) {
          // The index goes last, so an interrupted backup never names a file it
          // did not copy.
          if (file === LIBRARY_FILE) continue;
          const content = await store.read(file);
          if (content === null) continue;
          await writeInto(target, file, content);
          files++;
        }
        const library = await store.readText(LIBRARY_FILE);
        if (library === null) {
          setFor(name, 'ready', `“${name}” is ready, but there is no library to back up yet.`);
          return current.message;
        }
        await writeInto(target, LIBRARY_FILE, library);
        const sentence =
          `Backed up ${files} ${files === 1 ? 'file' : 'files'} and library.json to “${name}”. ` +
          'The library stays in this browser; the folder is a copy.';
        setFor(name, 'ready', sentence);
        return sentence;
      } catch (err) {
        return fail(err, 'backing up');
      }
    },

    async restore(store) {
      const target = await usable();
      if (target === null) return { ok: false, message: current.message };
      const name = target.name;
      try {
        const file = await readFrom(target, LIBRARY_FILE);
        if (file === null) {
          const sentence =
            `“${name}” has no library.json, so there is nothing to restore. ` +
            'Choose the folder you backed up to.';
          setFor(name, 'ready', sentence);
          return { ok: false, message: sentence };
        }
        const text = await file.text();
        const read = readLibrary(text);
        if (read.status === 'corrupt') {
          const sentence =
            `“${name}”'s library.json could not be read, so nothing was restored: ${read.message ?? ''}`;
          setFor(name, 'ready', sentence);
          return { ok: false, message: sentence };
        }
        if (read.status === 'newer') {
          const sentence =
            `“${name}”'s library.json was written by a newer build (v${read.newerVersion}), so ` +
            'nothing was restored. This page will not guess at fields it does not know.';
          setFor(name, 'ready', sentence);
          return { ok: false, message: sentence };
        }

        let copied = 0;
        const missing: string[] = [];
        const seen = new Set<string>();
        for (const item of read.library.items) {
          for (const fileName of fileNamesOf(item)) {
            if (seen.has(fileName)) continue;
            seen.add(fileName);
            const content = await readFrom(target, fileName);
            if (content === null) {
              missing.push(fileName);
              continue;
            }
            await store.write(fileName, content);
            copied++;
          }
        }
        // The app's playlists blob is not this page's to interpret, but a folder
        // that has one must not lose it on a restore.
        const playlists = await readFrom(target, PLAYLISTS_FILE);
        if (playlists !== null) await store.write(PLAYLISTS_FILE, playlists);

        // The index LAST: the gallery is re-read from it, and a row whose file is
        // still missing says so rather than being dropped.
        await store.writeText(LIBRARY_FILE, text);

        const rows = read.library.items.length;
        const sentence = missing.length === 0
          ? `Restored ${rows} ${rows === 1 ? 'row' : 'rows'} and ${copied} ` +
            `${copied === 1 ? 'file' : 'files'} from “${name}”.`
          : `Restored ${rows} ${rows === 1 ? 'row' : 'rows'} from “${name}”, but ${missing.length} ` +
            `${missing.length === 1 ? 'file was' : 'files were'} not in the folder ` +
            `(${missing.slice(0, 3).join(', ')}${missing.length > 3 ? ', …' : ''}) — those rows ` +
            'say so on their cards.';
        setFor(name, 'ready', sentence);
        return { ok: true, message: sentence };
      } catch (err) {
        const message = await fail(err, 'restoring');
        return { ok: false, message };
      }
    },
  };
}

// ------------------------------------------------------------------ IndexedDB

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) db.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB refused to open'));
    request.onblocked = () => reject(new Error('IndexedDB is blocked by another tab'));
  });
}

function putHandle(handle: FileSystemDirectoryHandle): Promise<void> {
  return new Promise((resolve, reject) => {
    openDb().then((db) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put(handle, HANDLE_KEY);
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error ?? new Error('the folder could not be remembered'));
      };
      tx.onabort = () => {
        db.close();
        reject(tx.error ?? new Error('the folder could not be remembered'));
      };
    }, reject);
  });
}

function loadHandle(): Promise<FileSystemDirectoryHandle | null> {
  return new Promise((resolve) => {
    openDb().then((db) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const request = tx.objectStore(STORE_NAME).get(HANDLE_KEY);
      request.onsuccess = () => {
        const value: unknown = request.result;
        db.close();
        resolve(isDirectoryHandle(value) ? value : null);
      };
      request.onerror = () => {
        db.close();
        resolve(null); // no remembered folder is not a failure
      };
    }, () => resolve(null));
  });
}

function deleteHandle(): Promise<void> {
  return new Promise((resolve, reject) => {
    openDb().then((db) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).delete(HANDLE_KEY);
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error ?? new Error('the folder could not be forgotten'));
      };
    }, reject);
  });
}

/**
 * A stored value that is a directory handle, and not something else.
 *
 * IndexedDB hands back whatever was stored; a value written by another page on
 * this origin, or a handle whose directory has since gone, must not be used as one
 * without asking. The permission read that follows is what notices a folder that
 * is gone, so no file access is needed here.
 */
function isDirectoryHandle(value: unknown): value is FileSystemDirectoryHandle {
  return (
    typeof value === 'object' &&
    value !== null &&
    (value as FileSystemDirectoryHandle).kind === 'directory' &&
    typeof (value as FileSystemDirectoryHandle).getFileHandle === 'function'
  );
}
