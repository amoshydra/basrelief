import { extractEmbeddedDepth, type DephResult, type DephSuccess } from './deph.ts';
import {
  BUNDLED_ID,
  displayName,
  emptyLibrary,
  fileNamesOf,
  imageNameFor,
  markUsed,
  readLibrary,
  sortedRows,
  splatNameFor,
  withSettings,
  writeLibrary,
  type LibraryItem as Row,
  type RenderMode,
  type WebLibrary,
} from './library.ts';
import {
  classifyMedia,
  formatBytes,
  formatDimensions,
  formatSplatCount,
  type MediaState,
} from './media.ts';
import { parseSidecar } from './splat.ts';
import {
  PREFS_CORRUPT_FILE,
  PREFS_FILE,
  bindPrefsSink,
  getSheetPosition,
  hydratePrefs,
  readPrefs,
  setSheetPosition,
  writePrefs,
  type AppPrefs,
} from './prefs.ts';
import {
  LIBRARY_FILE,
  describeStoreError,
  openStore,
  type MediaStore,
} from './store.ts';
import { defaultSettings } from './settings.ts';
import { openViewer, type CloudAsset } from './viewer.ts';

/**
 * The gallery is the library.
 *
 * The rows are not the files in this tab: they come from `library.json`, which is
 * the Android app's own `Library` v5 blob (see `library.ts`), and the bytes behind
 * a row are resolved from the store by the bare filename the row names. That is
 * what makes a tuned look, the Renderer choice and the row itself survive a
 * refresh — and it is why the page no longer has to say "nothing is stored".
 *
 * **A row is one card, and the card's `File` is a view of a stored file.** The
 * gallery keeps no second copy of the library: every edit goes to the same object,
 * then to `library.json` through one write chain, so two editors cannot interleave
 * two blobs. The order of writes is `Library.remove`'s: rows first, files second —
 * a crash between them leaves an orphan file, which is recoverable and invisible,
 * rather than a row naming bytes that were never written.
 *
 * **A file whose row does not resolve is kept and said, never dropped.** Deleting
 * a row whose file is missing would lose the only record that the photograph
 * existed; the card reads `unreadable` with the name it wanted, which is the same
 * answer the app's `retainExisting` gives in the other direction.
 *
 * **A lone `.splat` is session-only, and the card says so.** The app's schema
 * gives every row a photograph — the still is the poster and the fallback — so a
 * dropped cloud with no picture has no valid row to write. It is imported, drawn
 * and closed with the tab; the sentence is on the card rather than in a console.
 * A cloud built from a photograph is an ordinary `splat` on that row and persists.
 */
export interface GalleryItem {
  id: string;
  row: Row;
  /** The colour file, or null when the store does not have what the row names. */
  file: File | null;
  /** Object URL for the thumbnail; revoked when the card is removed. */
  url: string | null;
  state: MediaState;
  depth: DephSuccess | null;
  width: number;
  height: number;
  /** `focal / height` for a cloud, or 0 when nothing claimed a lens. */
  lens: number;
  /** The sidecar that stated it, when one arrived this session. */
  lensFrom: string | null;
  cloud: CloudAsset | null;
  /** A sentence about the row that is not a media state, or null. */
  note: string | null;
  /** True for a card the schema cannot describe (a cloud with no photograph). */
  sessionOnly: boolean;
}

interface CardParts {
  element: HTMLLIElement;
  open: HTMLElement;
  thumb: HTMLElement;
  name: HTMLElement;
  meta: HTMLElement;
  badge: HTMLElement;
  modeBadge: HTMLElement;
  depthDot: HTMLElement;
  is3D: HTMLElement;
  reason: HTMLElement;
}

export interface GalleryOptions {
  grid: HTMLElement;
  empty: HTMLElement;
  count: HTMLElement;
  clear: HTMLButtonElement;
  /** The index's own `sort`; the phone reads the same value. */
  sort: HTMLSelectElement;
  /** The store's own line: where the library lives and how much it uses. */
  storage: HTMLElement;
  /** Messages that need reading once: a corrupt blob, a full disk. */
  notice: HTMLElement;
}

export interface Gallery {
  readonly items: readonly GalleryItem[];
  readonly store: MediaStore;
  /** True when a newer build's blob is in place and must not be overwritten. */
  readonly readOnly: boolean;
  addFiles(files: Iterable<File>): Promise<void>;
  /** Re-read `library.json` from the store. */
  reload(): Promise<void>;
}

/** The cloud card's sentence: the one shape the app's schema does not have. */
const LONE_CLOUD_NOTE =
  'A .splat with no photograph is kept for this session — the app’s library gives every row a ' +
  'photograph as its poster, so there is no row to write. A cloud built from a photo persists.';

export async function createGallery(options: GalleryOptions): Promise<Gallery> {
  const { store, message: storeMessage } = await openStore();

  const items: GalleryItem[] = [];
  const cards = new Map<string, CardParts>();
  /** Sidecar lenses by the cloud filename they describe, in either arrival order. */
  const sidecarLenses = new Map<string, { lens: number; name: string }>();
  let sidecarFiles = 0;

  let library: WebLibrary = emptyLibrary();
  let readOnly = false;
  /** One write chain, so two edits cannot interleave two blobs. */
  let writeChain: Promise<void> = Promise.resolve();

  function isOpenable(item: GalleryItem): boolean {
    if (item.file === null) return false;
    const kind = item.state.kind;
    return kind === 'relief' || kind === 'photo' || kind === 'cloud';
  }

  // ------------------------------------------------------------------ notices

  function notice(text: string | null, warn = false): void {
    options.notice.hidden = text === null || text === '';
    options.notice.textContent = text ?? '';
    options.notice.classList.toggle('is-warn', warn);
  }

  /**
   * Add a sentence without dropping one already shown.
   *
   * Two blobs can be unreadable at once, and the prefs are read after the
   * library; a plain `notice` would let one broken file's sentence disappear
   * behind the other's.
   */
  function noticeMore(text: string): void {
    const shown =
      options.notice.hidden || !options.notice.textContent ? '' : `${options.notice.textContent} `;
    notice(`${shown}${text}`, true);
  }

  if (storeMessage !== null) notice(storeMessage, true);

  // ------------------------------------------------------------------- prefs
  //
  // The app's prefs, not its library: a server address and a model choice are
  // device/app settings, so they live in their own blob beside `library.json`
  // (`prefs.ts`). Read before anything can open a viewer, so the first address
  // the build dialog shows is the stored one, and hydrate without writing — a
  // rewrite on load would drop keys this build does not know.

  let prefsRaw: string | null = null;
  let prefsReadError: string | null = null;
  try {
    prefsRaw = await store.readText(PREFS_FILE);
  } catch (err) {
    prefsReadError =
      `The saved ComfyUI settings could not be read (${describeStoreError(err)}); ` +
      'this page starts from the app’s defaults.';
  }
  const prefsRead = readPrefs(prefsRaw);
  hydratePrefs(prefsRead.prefs);
  const prefsExtra = prefsRead.extra;
  if (prefsRead.status === 'corrupt' && prefsRaw !== null) {
    // The `library.corrupt.json` rule: keep the only record of what the file said.
    try {
      await store.writeText(PREFS_CORRUPT_FILE, prefsRaw);
    } catch {
      // A full disk can refuse the copy too; the sentence already names the file.
    }
  }

  // The archive is a disclosure row under the grid, as `Gallery.java:1436` draws
  // it: quiet, 48dp, a count and a chevron, only when something is archived.
  const archivedGrid = document.getElementById('archived-grid') as HTMLElement;
  const archiveToggle = document.getElementById('archive-toggle') as HTMLButtonElement;
  const archiveLabel = document.getElementById('archive-label') as HTMLElement;
  const archiveChev = archiveToggle.querySelector('.archive-chev') as HTMLElement;
  archiveToggle.addEventListener('click', () => {
    archivedGrid.hidden = !archivedGrid.hidden;
    archiveChev.textContent = archivedGrid.hidden ? '▸' : '▾';
  });

  /**
   * Rename a row. `label` is the schema's own field for a user-visible name -- the
   * storage keeps the UUID filename, so a rename never moves bytes.
   */
  async function renameItem(item: GalleryItem, next: string): Promise<void> {
    if (readOnly) return;
    const label = next.trim();
    if (label === '' || label === displayName(item.row)) return;
    library = {
      ...library,
      items: library.items.map((r) => (r.id === item.id ? { ...r, label } : r)),
    };
    item.row = library.items.find((r) => r.id === item.id) ?? item.row;
    await persist();
    renderCard(item);
    renderCounts();
  }

  // ------------------------------------------------------------------ selecting
  //
  // `Gallery.java` starts a pick on a long press and shows a selection line; a tap
  // then toggles instead of opening. One set, one mode, and the line is the only
  // place a bulk action can be reached.
  const picked = new Set<string>();
  let selecting = false;
  const selectionBar = document.getElementById('selection-bar') as HTMLElement;
  const selectionCount = document.getElementById('selection-count') as HTMLElement;

  function syncSelection(): void {
    selectionBar.hidden = !selecting;
    selectionCount.textContent = `${picked.size} selected`;
    for (const [id, parts] of cards) {
      parts.element.classList.toggle('is-picked', picked.has(id));
    }
  }

  function startSelecting(id: string): void {
    selecting = true;
    picked.add(id);
    syncSelection();
  }

  function stopSelecting(): void {
    selecting = false;
    picked.clear();
    syncSelection();
  }

  (document.getElementById('selection-cancel') as HTMLButtonElement).addEventListener('click', () => stopSelecting());
  (document.getElementById('selection-archive') as HTMLButtonElement).addEventListener('click', () => {
    void bulk(true);
  });
  (document.getElementById('selection-delete') as HTMLButtonElement).addEventListener('click', () => {
    void bulk(false);
  });

  async function bulk(archive: boolean): Promise<void> {
    if (readOnly) return;
    const ids = new Set(picked);
    if (ids.size === 0) return;
    if (archive) {
      library = {
        ...library,
        items: library.items.map((r) => (ids.has(r.id) ? { ...r, archived: true } : r)),
      };
      await persist();
      stopSelecting();
      paint();
      return;
    }
    // Delete goes through the same removal as the menu, one row at a time, so the
    // selection is cleared and the files are dropped in one derivation.
    for (const item of [...items]) if (ids.has(item.id)) await removeItem(item);
    stopSelecting();
  }

  /** Archive or restore one row. The selection is untouched: archive hides. */
  async function setArchived(item: GalleryItem, next: boolean): Promise<void> {
    if (readOnly) return;
    library = {
      ...library,
      items: library.items.map((r) => (r.id === item.id ? { ...r, archived: next } : r)),
    };
    await persist();
    paint();
  }

  /**
   * Paint the shelf: live rows in the grid, archived ones under the disclosure.
   * One function, because a card cannot belong to both and two render paths would
   * let a row appear twice.
   */
  function paint(): void {
    clearCards();
    const rows = sortedRows(library);
    for (const row of rows) if (!row.archived) addCard(row, options.grid);
    const archived = rows.filter((row) => row.archived);
    for (const row of archived) addCard(row, archivedGrid);
    archiveToggle.hidden = archived.length === 0;
    archiveLabel.textContent = `Archived (${archived.length})`;
    for (const it of items) void inspect(it);
  }

  /** One chain, so two keystrokes cannot interleave two blobs. */
  let prefsChain: Promise<void> = Promise.resolve();
  bindPrefsSink((prefs: AppPrefs) => {
    // Capture the text NOW: a later change must write the newer blob, not
    // whatever the in-memory values look like when this write finishes.
    const text = writePrefs(prefs, prefsExtra);
    prefsChain = prefsChain
      .then(() => store.writeText(PREFS_FILE, text))
      .catch((err) => {
        notice(`Could not save the ComfyUI settings: ${describeStoreError(err)}`, true);
      });
  });

  // ------------------------------------------------------------------- writes

  /**
   * Publish the in-memory library to the store.
   *
   * The text is captured NOW rather than inside the chained callback: two edits in
   * one tick must both land, and the second must be the newer blob rather than
   * whatever the library looks like when the first write finishes.
   */
  function persist(): Promise<void> {
    if (readOnly) return Promise.resolve();
    const text = writeLibrary(library);
    writeChain = writeChain
      .then(() => store.writeText(LIBRARY_FILE, text))
      .catch((err) => {
        notice(`Could not save the library: ${describeStoreError(err)}`, true);
      });
    return writeChain;
  }

  function refreshStorage(): void {
    const count = library.items.length;
    const photos = `${count} ${count === 1 ? 'photo' : 'photos'}`;
    if (store.kind === 'memory') {
      options.storage.textContent = `Not saved — this tab only · ${photos}`;
      return;
    }
    options.storage.textContent = `Saved in this browser · ${photos}`;
    void store.usage().then((usage) => {
      if (usage === null || usage.usage <= 0) return;
      if (store.kind === 'memory') return;
      options.storage.textContent = `Saved in this browser · ${photos} · ${formatBytes(usage.usage)}`;
    });
  }

  // ---------------------------------------------------------------- the index

  function clearCards(): void {
    for (const item of items) {
      if (item.url !== null) URL.revokeObjectURL(item.url);
      cards.get(item.id)?.element.remove();
    }
    items.length = 0;
    cards.clear();
  }


  /**
   * The app's own preload: the marble bust, as a photograph, a relief and a
   * cloud. The names carry the `demo/` prefix the store resolves against the
   * bundle, so nothing is copied into OPFS until the row is opened.
   */
  function bundledRow(): Row {
    return {
      id: BUNDLED_ID,
      image: 'demo-image.png',
      depth: 'demo-depth.png',
      normal: 'demo-normal.png',
      video: null,
      focal: 0,
      // The cloud ships without a sidecar, so it draws at the app's documented
      // fallback lens rather than pretending to a measured one.
      splat: 'demo-cloud.splat',
      splatLens: 0,
      render: 'relief',
      label: 'Marble bust',
      addedAt: Date.now(),
      lastUsedAt: 0,
      archived: false,
      home: defaultSettings(),
      lock: null,
    };
  }

  options.sort.addEventListener('change', () => {
    const next = options.sort.value;
    if (next === library.sort) return;
    library = { ...library, sort: next as WebLibrary['sort'] };
    void persist();
    clearCards();
    for (const row of sortedRows(library)) addCard(row);
    for (const item of items) void inspect(item);
  });

  async function load(): Promise<void> {
    clearCards();
    sidecarLenses.clear();
    sidecarFiles = 0;

    const raw = await store.readText(LIBRARY_FILE);
    const read = readLibrary(raw);
    library = read.library;
    readOnly = read.readOnly;

    // Seeded only when the index is ABSENT, never when it is empty. An emptied
    // gallery means the user deleted the demo, and the app's own rule is that a
    // delete has to stick -- which is why this is `raw === null` and not
    // `library.items.length === 0`.
    if (raw === null && !readOnly) {
      library = { ...library, items: [bundledRow(), ...library.items] };
    } else {
      // A row written by the build that stored `demo/...` lost its prefix on the
      // way back in, because the schema keeps bare filenames. Re-point it here
      // rather than writing: the parse is the wrong place to repair a blob, and
      // an older build must not rewrite one it does not own.
      library = {
        ...library,
        items: library.items.map((r) =>
          r.id === BUNDLED_ID
            ? {
                ...r,
                image: 'demo-image.png',
                depth: r.depth === null ? null : 'demo-depth.png',
                normal: r.normal === null ? null : 'demo-normal.png',
                splat: r.splat === null ? null : 'demo-cloud.splat',
              }
            : r,
        ),
      };
    }

    options.sort.value = library.sort;
    if (read.message !== null) notice(read.message, read.status !== 'empty');
    if (read.status === 'corrupt' && raw !== null) {
      // Keep one copy of the unreadable blob. Overwriting it would destroy the only
      // record of whatever it said, and the app's own rule is the same: a corrupt
      // blob is this build's problem and must not be silently re-derived from
      // nothing without leaving the original behind.
      try {
        await store.writeText('library.corrupt.json', raw);
      } catch {
        // A full disk can refuse the copy too; the sentence already said where the
        // blob went, and refusing to open would be worse than not keeping a copy.
      }
    }
    // Nothing is written on load. A valid blob from the phone is left exactly as it
    // is (a rewrite would drop keys this build does not know, which is how an older
    // build erases a newer one's fields); a missing or corrupt one is written by
    // the first edit instead.

    paint();
    renderCounts();
    refreshStorage();
    for (const item of items) void inspect(item);
  }

  // -------------------------------------------------------------------- cards

  function addCard(row: Row, target: HTMLElement = options.grid): GalleryItem {
    const item: GalleryItem = {
      id: row.id,
      row,
      file: null,
      url: null,
      state: { kind: 'reading' },
      depth: null,
      width: 0,
      height: 0,
      lens: row.splatLens,
      lensFrom: null,
      cloud: null,
      note: null,
      sessionOnly: false,
    };
    items.push(item);
    const parts = createCard(item);
    cards.set(item.id, parts);
    target.append(parts.element);
    renderCard(item);
    return item;
  }

  function dropCard(item: GalleryItem): void {
    const index = items.indexOf(item);
    if (index >= 0) items.splice(index, 1);
    if (item.url !== null) {
      URL.revokeObjectURL(item.url);
      item.url = null;
    }
    cards.get(item.id)?.element.remove();
    cards.delete(item.id);
    renderCounts();
  }

  // ------------------------------------------------------------------- import

  async function addFiles(files: Iterable<File>): Promise<void> {
    for (const file of files) {
      if (isSidecarName(file.name)) {
        void readSidecar(file);
        continue;
      }
      if (readOnly) {
        notice(
          'library.json was written by a newer build, so this page will not overwrite it — ' +
            `“${file.name}” was not imported.`,
          true,
        );
        continue;
      }
      if (isSplatName(file.name)) await importLoneCloud(file);
      else await importPhoto(file);
    }
    renderCounts();
  }

  /**
   * A dropped `.splat`: a card, not a row. See the module comment.
   *
   * The bytes are not copied into the store either, because nothing would ever
   * resolve them again: a file the index does not name is an orphan, and saying
   * "kept for this session" while quietly writing 23 MB into the quota would be
   * the worse half of both choices.
   */
  async function importLoneCloud(file: File): Promise<void> {
    const side = sidecarLenses.get(file.name);
    const lens = side?.lens ?? 0;
    const id = newId();
    const row: Row = {
      id,
      image: null,
      depth: null,
      normal: null,
      video: null,
      focal: 0,
      splat: file.name,
      splatLens: lens,
      render: 'splat',
      label: file.name,
      addedAt: Date.now(),
      lastUsedAt: 0,
      archived: false,
      home: defaultSettings(),
      lock: null,
    };
    const item = addCard(row);
    item.sessionOnly = true;
    item.file = file;
    item.lens = lens;
    item.lensFrom = side?.name ?? null;
    item.note = LONE_CLOUD_NOTE;
    item.state = classifyMedia({
      name: file.name,
      size: file.size,
      depth: null,
      readError: null,
      decodes: null,
    });
    if (item.state.kind === 'cloud') {
      item.cloud = {
        file,
        bytes: null,
        name: file.name,
        lens,
        lensFrom: side?.name ?? null,
        splats: item.state.splats,
      };
    }
    renderCard(item);
    renderCounts();
  }

  /** A photograph, a relief or any other image: written to the store, then its row. */
  async function importPhoto(file: File): Promise<void> {
    const id = newId();
    const storedName = imageNameFor(id, file.name);
    const row: Row = {
      id,
      image: storedName,
      depth: null,
      normal: null,
      video: null,
      focal: 0,
      splat: null,
      splatLens: 0,
      render: 'relief',
      // The dropped name becomes the label: the schema's own way of keeping a
      // user-visible name that is not a storage detail, so a card and a phone both
      // read "beach.png" rather than "item-<uuid>.png".
      label: file.name,
      addedAt: Date.now(),
      lastUsedAt: 0,
      archived: false,
      home: defaultSettings(),
      lock: null,
    };
    const item = addCard(row);
    item.file = file;
    item.url = URL.createObjectURL(file);

    await inspect(item, file);
    if (!items.includes(item)) return; // removed while it was being read

    try {
      await store.write(storedName, file);
    } catch (err) {
      dropCard(item);
      notice(`“${file.name}” was not saved: ${describeStoreError(err)}`, true);
      return;
    }

    // The row goes into the index only after its bytes are in the store, so the
    // blob never names a file that was not written.
    library = { ...library, items: [...library.items, row] };
    persist();
    refreshStorage();
    renderCard(item);
  }

  /**
   * A dropped `*.splat.json`: read it, remember its lens, and hand it to the cloud
   * it names whether that cloud is already on screen or arrives later.
   *
   * A sidecar adds no card and reaches no classifier: it is the node's lens
   * measurement for the cloud beside it, not media.
   */
  async function readSidecar(file: File): Promise<void> {
    sidecarFiles++;
    renderCounts();
    let text: string;
    try {
      text = await file.text();
    } catch {
      return;
    }
    const side = parseSidecar(text);
    const cloudName = file.name.slice(0, file.name.length - '.json'.length);
    if (!side) return; // an unreadable body is no sidecar, not a sidecar with no lens
    sidecarLenses.set(cloudName, { lens: side.lens, name: file.name });
    for (const item of items) {
      if (item.file?.name !== cloudName && item.row.splat !== cloudName) continue;
      item.lens = side.lens;
      item.lensFrom = file.name;
      if (item.cloud !== null) {
        item.cloud = { ...item.cloud, lens: side.lens, lensFrom: file.name };
      }
      if (!item.sessionOnly) {
        item.row = { ...item.row, splatLens: side.lens };
        replaceRow(item.row);
        persist();
      }
      renderCard(item);
    }
    renderCounts();
  }

  // -------------------------------------------------------------- inspection

  /**
   * Decide what a row's files are.
   *
   * The colour image is read from the store by the name the row gives it. A row
   * that names a separate depth FILE (which the phone writes) is read as grey at
   * the image's own size and refused, not stretched, when the two disagree;
   * otherwise the depth is the `dePH` chunk embedded in the image.
   */
  async function inspect(item: GalleryItem, provided?: File): Promise<void> {
    if (item.sessionOnly) return;

    const { row } = item;
    if (row.image === null) {
      finish(item, {
        kind: 'unreadable',
        reason:
          row.id === BUNDLED_ID
            ? 'The bundled demo ships inside the phone app, so its files are not here.'
            : 'This row names no photograph, so there is nothing to draw.',
      });
      return;
    }

    // A file the caller is holding (an import, before its bytes are written) is used
    // directly; a row loaded from the index has to be resolved from the store. The
    // alternative — always reading the store — inspected an import BEFORE it was
    // written and reported its own file as missing, which is how this was found.
    // A read that throws must become a card that says so, not one that reads
    // forever: `inspect` is the only thing that calls `finish`, so an escaping
    // rejection leaves the tile spinning for the life of the page.
    let file: File | null = null;
    try {
      file = provided ?? (await store.read(row.image));
    } catch (err) {
      finish(item, {
        kind: 'unreadable',
        reason: `${row.image} could not be read: ${String(err)}`,
      });
      return;
    }
    if (file === null) {
      finish(item, {
        kind: 'unreadable',
        reason:
          `library.json names ${row.image}, but the store has no such file. ` +
          'The row is kept so the selection still resolves; import the file again.',
      });
      return;
    }
    item.file = file;
    if (item.url === null) item.url = URL.createObjectURL(file);

    // A cloud on the row: resolved here so the card can state its count, and so
    // the viewer gets the same asset whether the row was just imported or loaded.
    let cloudNote: string | null = null;
    if (row.splat !== null) {
      // The LENGTH, not the bytes: a count is `size / 32`, and reading a 13 MB
      // cloud to divide its size is the difference between a page that opens on
      // a slow link and one that does not. The bytes move on the first open.
      // The length, not the bytes. A count is `size / 32`, and downloading a 13 MB
      // cloud to divide its size is what makes a page take a minute on a slow
      // link. When the length cannot be trusted -- a cached response, a proxy
      // that strips `content-length`, a CDN that answers 0 -- the card says
      // "cloud" without a count and the viewer fills it in on the first open.
      const cloudSize = await store.size(row.splat);
      if (cloudSize === null) {
        // The length could not be trusted (no header, a cache, a proxy). The
        // card says "cloud" without a count; the viewer fills it in on the first
        // open, which is when the bytes move anyway.
      } else if (cloudSize > 0 && cloudSize % 32 === 0) {
        // EAGER for now: the lazy path left `mountCloud` without an asset when the
        // viewer opened, so a Splat row built a stage and no sheet. The count still
        // comes from the length; the bytes are read here until the lazy mount has
        // been fixed and seen on a device.
        const cloudFile = await store.read(row.splat);
        item.cloud = {
          file: cloudFile,
          bytes: null,
          name: row.splat,
          lens: row.splatLens,
          lensFrom: null,
          splats: Math.floor(cloudSize / 32),
        };
      } else {
        // A successful response with a length that is not a multiple of the record
        // size: that is a real verdict, not a network artefact.
        cloudNote = `${row.splat} is not a whole number of 32-byte records, so it is not a cloud.`;
      }
    }
    item.note = cloudNote;

    // A `.splat` is known from its name and length alone; the bytes are read on
    // open, because a card should not read a 13 MB cloud to show a count.
    const early = classifyMedia({
      name: file.name,
      size: file.size,
      depth: null,
      readError: null,
      decodes: null,
    });
    if (early.kind !== 'reading') {
      finish(item, early);
      return;
    }

    const decoded = await probeImage(item.url ?? '');
    item.width = decoded.width;
    item.height = decoded.height;

    let depth: DephResult | null = null;
    if (row.depth !== null) {
      const stored = await depthFromStoredFile(row.depth, item.width, item.height);
      if (stored !== null && 'reason' in stored) {
        finish(item, { kind: 'unreadable', reason: stored.reason });
        return;
      }
      if (stored === null) {
        // The row names a depth file that is not here. That is a flat photograph,
        // not the "not read yet" window: `classifyMedia`'s `depth: null` means the
        // bytes have not been read, and answering `reading…` forever would be the
        // spinner that never stops. A missing map is the app's legal flat state,
        // said on the card.
        item.note =
          `library.json names the depth file ${row.depth}, but the store has no such file — ` +
          'this photo draws flat until it is restored.';
        depth = { ok: false, error: 'no-depth-chunk', chunks: [] };
      } else {
        depth = stored.depth;
      }
    } else {
      try {
        const bytes = new Uint8Array(await file.arrayBuffer());
        depth = extractEmbeddedDepth(bytes);
      } catch (err) {
        finish(item, {
          kind: 'unreadable',
          reason: err instanceof Error ? err.message : 'the file could not be read',
        });
        return;
      }
    }

    item.depth = depth !== null && depth.ok ? depth : null;

    finish(
      item,
      classifyMedia({
        name: file.name,
        size: file.size,
        depth,
        readError: null,
        decodes: decoded.ok,
      }),
    );
  }

  /**
   * A row's separate depth file, as the grey field the relief draws with.
   *
   * The dimensions are checked, not scaled: the app's pairing rule is that a depth
   * map which does not line up is refused, because stretching it cannot make the
   * pair correct. A file that is not here answers null, which reads as "no depth"
   * only once the image itself has decoded — a missing depth file is a flat photo,
   * a legal state with the sentence on the card.
   */
  async function depthFromStoredFile(
    name: string,
    imageWidth: number,
    imageHeight: number,
  ): Promise<{ depth: DephSuccess } | { reason: string } | null> {
    const file = await store.read(name);
    if (file === null) return null;
    let bitmap: ImageBitmap;
    try {
      bitmap = await createImageBitmap(file);
    } catch {
      return { reason: `The depth file ${name} is not a decodable image.` };
    }
    try {
      if (
        imageWidth > 0 &&
        imageHeight > 0 &&
        (bitmap.width !== imageWidth || bitmap.height !== imageHeight)
      ) {
        return {
          reason:
            `The depth file ${name} is ${bitmap.width}×${bitmap.height} but the photo is ` +
            `${imageWidth}×${imageHeight} — refusing to stretch it.`,
        };
      }
      const canvas = document.createElement('canvas');
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      if (!context) return { reason: 'This browser has no 2D canvas to read the depth map.' };
      context.drawImage(bitmap, 0, 0);
      const rgba = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
      const gray = new Uint8Array(bitmap.width * bitmap.height);
      for (let i = 0; i < gray.length; i++) gray[i] = rgba[i * 4];
      return {
        depth: {
          ok: true,
          form: 'raw',
          gray,
          depthWidth: bitmap.width,
          depthHeight: bitmap.height,
          imageWidth,
          imageHeight,
        },
      };
    } finally {
      bitmap.close();
    }
  }

  function finish(item: GalleryItem, state: MediaState): void {
    if (!items.includes(item)) return; // removed while it was being read
    item.state = state;
    renderCard(item);
    renderCounts();
  }

  // ---------------------------------------------------------------- editing

  function replaceRow(next: Row): void {
    library = {
      ...library,
      items: library.items.map((row) => (row.id === next.id ? next : row)),
    };
    const item = items.find((candidate) => candidate.id === next.id);
    if (item) item.row = next;
  }

  /** A colour image this page packed or generated: same row, new bytes. */
  async function replaceImage(item: GalleryItem, file: File): Promise<void> {
    const storedName = imageNameFor(item.id, file.name);
    const previous = item.row.image;
    try {
      await store.write(storedName, file);
    } catch (err) {
      notice(`The packed photo was not saved: ${describeStoreError(err)}`, true);
      return;
    }
    if (previous !== null && previous !== storedName) {
      try {
        await store.remove(previous);
      } catch {
        // An orphan file is invisible and a later backup rewrites it; refusing the
        // new file because the old one would not delete is worse.
      }
    }
    if (item.url !== null) URL.revokeObjectURL(item.url);
    item.file = file;
    item.url = URL.createObjectURL(file);
    // The depth lives in the packed PNG's own chunk now, so the row names no
    // separate depth file — the app's `DepthSource.EMBEDDED` state.
    const next: Row = { ...item.row, image: storedName, depth: null };
    replaceRow(next);
    persist();
    const parts = cards.get(item.id);
    if (parts) {
      parts.name.textContent = displayName(next);
      parts.name.title = displayName(next);
      parts.open.setAttribute('aria-label', `Open ${displayName(next)}`);
    }
    await inspect(item);
    refreshStorage();
  }

  /** A cloud this page built or attached, written under the row's own cloud name. */
  async function saveCloud(item: GalleryItem, cloud: CloudAsset | null): Promise<void> {
    if (cloud === null) {
      const previous = item.row.splat;
      item.cloud = null;
      replaceRow({ ...item.row, splat: null, splatLens: 0, render: 'relief' });
      persist();
      if (previous !== null) {
        try {
          await store.remove(previous);
        } catch {
          // The row no longer names it; an orphan is recoverable, a failed delete
          // that keeps the row is not.
        }
      }
      renderCard(item);
      refreshStorage();
      return;
    }

    const name = splatNameFor(item.id);
    let bytes: Uint8Array | null = cloud.bytes;
    try {
      if (bytes === null && cloud.file !== null) {
        bytes = new Uint8Array(await cloud.file.arrayBuffer());
      }
      if (bytes === null) throw new Error('the cloud has no bytes to save');
      await store.write(name, bytes);
    } catch (err) {
      notice(`The cloud was not saved: ${describeStoreError(err)}`, true);
      return;
    }

    // The bytes stay with the session's asset: a row reopened before the next
    // reload draws from memory, and `mountCloud` reads `bytes` before it tries the
    // file. Dropping them here made a just-built cloud fail to open.
    item.cloud = { ...cloud, name, bytes, file: null };
    item.lens = cloud.lens;
    // A cloud that has just been built is what the user is looking at, and the row
    // has to say so: reopening the row must start in Splat, not in the relief the
    // viewer was in before the build.
    replaceRow({ ...item.row, splat: name, splatLens: cloud.lens, render: 'splat' });
    persist();
    renderCard(item);
    refreshStorage();
  }

  // ------------------------------------------------------------------ removal

  async function removeItem(item: GalleryItem): Promise<void> {
    if (readOnly) {
      notice('library.json was written by a newer build, so this page will not change it.', true);
      return;
    }
    if (item.sessionOnly) {
      dropCard(item);
      return;
    }
    const names = fileNamesOf(item.row);
    library = {
      ...library,
      items: library.items.filter((row) => row.id !== item.id),
      active: library.active === item.id ? null : library.active,
    };
    // Rows first, files second: an orphan file is invisible, a row naming bytes
    // that are gone is a card that cannot open. The index write is AWAITED, or a
    // failed save would leave the blob naming a file that is already deleted.
    await persist();
    dropCard(item);
    for (const name of names) {
      try {
        await store.remove(name);
      } catch {
        // An undeletable orphan is harmless; the row that named it is already gone.
      }
    }
    refreshStorage();
  }

  async function clear(): Promise<void> {
    if (readOnly) {
      notice('library.json was written by a newer build, so this page will not clear it.', true);
      return;
    }
    const names = new Set<string>();
    for (const item of items) {
      if (item.sessionOnly) continue;
      for (const name of fileNamesOf(item.row)) names.add(name);
    }
    library = { ...library, items: [], active: null };
    // A stored, empty library is an emptied gallery and stays empty — the app's
    // rule for the demo's delete, applied to the whole page. Written before the
    // files go, and awaited, so a failed save cannot leave rows naming deleted files.
    await persist();
    for (const item of [...items]) dropCard(item);
    sidecarLenses.clear();
    sidecarFiles = 0;
    for (const name of names) {
      try {
        await store.remove(name);
      } catch {
        // Orphans are recoverable; refusing to clear because one file will not go
        // is not.
      }
    }
    renderCounts();
    refreshStorage();
  }

  // ------------------------------------------------------------------- cards

  function createCard(item: GalleryItem): CardParts {
    const element = document.createElement('li');
    element.className = 'card';
    element.dataset.id = item.id;

    const open = document.createElement('div');
    open.className = 'card-open';
    open.tabIndex = 0;
    open.setAttribute('role', 'button');
    // `stateDescription`: the facts a screen reader needs, not just the name.
    const facts = [`Open ${displayName(item.row)}`];
    if (item.row.render === 'splat') facts.push('drawn as a cloud');
    if (item.state.kind === 'relief') facts.push('has a depth map');
    if (item.row.archived) facts.push('archived');
    if (item.id === library.active) facts.push('shown on the phone');
    open.setAttribute('aria-label', facts.join(', '));

    const thumb = document.createElement('div');
    thumb.className = 'card-thumb';

    const body = document.createElement('div');
    body.className = 'card-body';

    const name = document.createElement('div');
    name.className = 'card-name';
    name.textContent = displayName(item.row);
    name.title = displayName(item.row);

    const meta = document.createElement('div');
    meta.className = 'card-meta';

    const badge = document.createElement('span');
    badge.className = 'badge';
    const modeBadge = document.createElement('span');
    modeBadge.className = 'badge badge--mode';
    modeBadge.hidden = true;

    const reason = document.createElement('div');
    reason.className = 'card-reason';
    reason.hidden = true;

    const badgeRow = document.createElement('div');
    badgeRow.className = 'card-badges';
    badgeRow.append(badge, modeBadge);
    body.append(badgeRow, reason);
    // The tile is the photograph plus two overlays; the caption body is not drawn.
    const depthDot = document.createElement('span');
    depthDot.className = 'cell-dot';
    depthDot.hidden = true;

    const is3D = document.createElement('span');
    is3D.className = 'cell-3d';
    is3D.textContent = '3D';
    is3D.hidden = true;

    open.append(thumb, depthDot, is3D);

    // The browser's own long-press menu ("Save image", "Open in new tab") fires on
    // the same gesture as the pick, and it wins: the pick never starts. Suppress it
    // on the tile; the `⋯` menu is the one the page offers.
    element.addEventListener('contextmenu', (event) => event.preventDefault());

    let holdTimer = 0;
    open.addEventListener('pointerdown', () => {
      if (selecting) return;
      holdTimer = window.setTimeout(() => startSelecting(item.id), 500);
    });
    for (const end of ['pointerup', 'pointerleave', 'pointercancel']) {
      open.addEventListener(end, () => window.clearTimeout(holdTimer));
    }

    open.addEventListener('click', () => {
      if (!selecting) {
        openItem(item);
        return;
      }
      if (picked.has(item.id)) picked.delete(item.id);
      else picked.add(item.id);
      if (picked.size === 0) stopSelecting();
      else syncSelection();
    });
    open.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openItem(item);
      }
    });

    // `Gallery.java` puts every per-photo action behind one `⋯` on the tile.
    const menuButton = document.createElement('button');
    menuButton.type = 'button';
    menuButton.className = 'card-menu-btn';
    menuButton.textContent = '⋯';
    menuButton.setAttribute('aria-label', `Actions for ${displayName(item.row)}`);
    menuButton.setAttribute('aria-haspopup', 'menu');

    // The Popover API: top layer, light dismiss, Escape, and no z-index war. The
    // button is the invoker, so opening and closing are the platform's job.
    const menu = document.createElement('div');
    menu.className = 'card-menu';
    menu.setAttribute('role', 'menu');
    menu.popover = 'auto';
    menu.id = `menu-${item.id}`;
    menuButton.setAttribute('popovertarget', menu.id);
    // A popover is positioned against the viewport, so it is placed from the
    // button's rect when it opens; without this it lands in the middle of the
    // screen, which is the one thing a menu must not do.
    menu.addEventListener('toggle', (event) => {
      if ((event as ToggleEvent).newState !== 'open') return;
      const r = menuButton.getBoundingClientRect();
      menu.style.left = `${Math.round(Math.min(r.left, window.innerWidth - 140))}px`;
      menu.style.top = `${Math.round(r.bottom + 4)}px`;
    });

    const action = (label: string, run: () => void): HTMLButtonElement => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'card-menu-item';
      b.setAttribute('role', 'menuitem');
      b.textContent = label;
      b.addEventListener('click', (event) => {
        event.stopPropagation();
        menu.hidePopover();
        run();
      });
      return b;
    };

    menu.append(
      action('Rename', () => {
        const next = window.prompt('Name', displayName(item.row));
        if (next !== null) void renameItem(item, next);
      }),
      action(item.row.archived ? 'Restore' : 'Archive', () => void setArchived(item, !item.row.archived)),
      action('Delete', () => void removeItem(item)),
    );

    element.append(open, menuButton, menu);
    return { element, open, thumb, name, meta, badge, modeBadge, depthDot, is3D, reason };
  }

  /**
   * Open one row.
   *
   * Opening is this page's "set as wallpaper": it moves the index's selection and
   * stamps `last_used_at`, which is exactly what `Library.markUsed` means on the
   * phone. That is the one write the viewer needs from the gallery, and it makes
   * the selection in `library.json` a fact rather than a placeholder.
   *
   * A viewer is torn down and rebuilt when the filmstrip picks another row: one
   * open row at a time, because the sheet's settings belong to the row that is
   * drawn.
   */
  let openHandle: { close(): void } | null = null;

  function openItem(item: GalleryItem): void {
    const { row, file } = item;
    if (file === null) return;
    if (!item.sessionOnly) {
      library = markUsed(library, row.id, Date.now());
      item.row = library.items.find((candidate) => candidate.id === row.id) ?? row;
      persist();
    }

    const openRow = item.row;
    // The filmstrip is the library, as the studio's is: every row that can be
    // drawn, with this one ringed. A row whose file is missing cannot be opened,
    // so it is not a tile that leads nowhere.
    const strip = items
      .filter((candidate) => candidate.file !== null && isOpenable(candidate))
      .map((candidate) => ({
        id: candidate.id,
        name: displayName(candidate.row),
        url: candidate.url,
        active: candidate.id === item.id,
      }));

    openHandle?.close();
    openHandle = openViewer({
      name: displayName(openRow),
      file,
      url: item.url ?? '',
      state: item.state,
      depth: item.depth,
      // The count came from the file's length, so the first open is when the
      // bytes move. The viewer does not know the store; this is the way in.
      loadCloudFile: openRow.splat
        ? async () => await store.read(openRow.splat as string)
        : undefined,
      size: file.size,
      width: item.width,
      height: item.height,
      lens: item.lens,
      lensFrom: item.lensFrom,
      cloud: item.cloud,
      hasCloud: openRow.splat !== null,
      settings: openRow.home,
      mode: openRow.render,
      strip,
      onPick: (id) => {
        const next = items.find((candidate) => candidate.id === id);
        if (next && next !== item) openItem(next);
      },
      sheetPos: getSheetPosition(),
      onSheetResting: (fraction, collapsed) => setSheetPosition(fraction, collapsed),
      onSettings: (settings, mode) => {
        if (item.sessionOnly) return;
        const next: Row = {
          ...withSettings(item.row, 'home', settings),
          render: mode as RenderMode,
        };
        replaceRow(next);
        persist();
        renderCard(item);
      },
      onGenerated: (packed, _depth, _width, _height) => {
        if (item.sessionOnly) return;
        void replaceImage(item, packed);
      },
      onCloud: (cloud) => {
        if (item.sessionOnly) {
          item.cloud = cloud;
          if (cloud === null) dropCard(item);
          else renderCard(item);
          renderCounts();
          return;
        }
        void saveCloud(item, cloud).then(() => renderCounts());
      },
    });
  }

  function renderCard(item: GalleryItem): void {
    // The index owns the selection; the shelf marks it. Nothing else on the page
    // says which row the phone is showing.
    cards.get(item.id)?.element.classList.toggle('is-active', item.id === library.active);
    const parts = cards.get(item.id);
    if (!parts) return;

    const { state } = item;
    parts.open.classList.toggle('card-open--disabled', !isOpenable(item));
    parts.open.setAttribute('aria-disabled', String(!isOpenable(item)));

    // The thumbnail is rebuilt rather than patched: there is one of each kind (an
    // img, a skeleton, a cloud texture, a warning), and which one it is IS the
    // state.
    parts.thumb.replaceChildren();
    parts.thumb.className = 'card-thumb';

    if ((state.kind === 'relief' || state.kind === 'photo') && item.url !== null) {
      const image = document.createElement('img');
      image.src = item.url;
      image.alt = '';
      image.loading = 'lazy';
      image.decoding = 'async';
      parts.thumb.append(image);
    } else if (state.kind === 'cloud') {
      parts.thumb.classList.add('card-thumb--cloud');
      const glyph = document.createElement('span');
      glyph.className = 'cloud-glyph cloud-glyph--small';
      glyph.setAttribute('aria-hidden', 'true');
      parts.thumb.append(glyph);
    } else if (state.kind === 'reading') {
      parts.thumb.classList.add('card-thumb--reading');
    } else {
      parts.thumb.classList.add('card-thumb--warn');
      const mark = document.createElement('span');
      mark.className = 'thumb-mark';
      mark.textContent = '!';
      parts.thumb.append(mark);
    }

    const facts: string[] = [];
    if (state.kind === 'cloud') {
      facts.push(formatSplatCount(state.splats));
    } else if (item.cloud) {
      // A cloud on the row is an asset, and a rebuild must be visible FROM THE
      // CARD: a stale count would read as "nothing happened".
      facts.push(`${formatSplatCount(item.cloud.splats)} cloud`);
    }
    const dims = formatDimensions(item.width, item.height);
    if (dims) facts.push(dims);
    if (item.file !== null) facts.push(formatBytes(item.file.size));
    parts.meta.textContent = facts.join(' · ');

    switch (state.kind) {
      case 'relief':
        // The phone draws a 10dp dot for a row that can stand out, and a `3D`
        // tag for one that is three-dimensional. Both overlay the photograph.
        setBadge(parts.badge, '', 'badge--depth', 'has a depth map');
        parts.depthDot.hidden = false;
        parts.is3D.hidden = false;
        break;
      case 'photo':
        setBadge(parts.badge, 'no depth', 'badge--none', state.detail);
        break;
      case 'cloud':
        setBadge(parts.badge, '', 'badge--cloud', 'Gaussian splat cloud');
        parts.is3D.hidden = false;
        break;
      case 'unreadable':
        setBadge(parts.badge, 'unreadable', 'badge--warn', state.reason);
        break;
      case 'reading':
        setBadge(parts.badge, 'reading…', 'badge--reading', 'still being read');
        break;
    }

    // A cloud card IS the cloud; a mode badge there would be a second badge saying
    // the same thing.
    // No mode badge. Android's corner marks are three specific states -- an import
    // ring, a clip's play mark, a failed run -- and this page has none of them, so
    // a mark here said nothing a reader could act on. The Renderer segment already
    // names the mode, on the screen where it can be changed.
    // Inline, not `hidden`: `.badge` sets `display: inline-block`, and a class's
    // display beats the UA's `[hidden]` rule. This is the third time a `hidden`
    // assignment has been silently outranked in this page.
    parts.modeBadge.style.display = 'none';

    const reason = state.kind === 'unreadable' ? state.reason : item.note;
    const warn = state.kind === 'unreadable';
    parts.reason.hidden = reason === null;
    parts.reason.textContent = reason ?? '';
    parts.reason.title = reason ?? '';
    parts.reason.classList.toggle('card-reason--note', !warn);
  }

  function renderCounts(): void {
    // The intake is a first-visit explanation, not a permanent ceiling. Android's
    // gallery has no hero because the app opens onto a library; once there is
    // something here, the grid is the page and "Add files" is an action.
    document.body.classList.toggle('has-library', items.length > 0);
    const counts = { depth: 0, clouds: 0, unreadable: 0, reading: 0 };
    for (const item of items) {
      if (item.cloud && item.state.kind !== 'cloud') counts.clouds++;
      switch (item.state.kind) {
        case 'relief':
          counts.depth++;
          break;
        case 'cloud':
          counts.clouds++;
          break;
        case 'unreadable':
          counts.unreadable++;
          break;
        case 'reading':
          counts.reading++;
          break;
        case 'photo':
          break;
      }
    }

    // "photo", not "file": the storage's word is for the storage line, and Android's
    // gallery counts photos. A sidecar is not a photo and is not counted as one.
    const total = items.length;
    const parts: string[] = [
      `${total} ${total === 1 ? 'photo' : 'photos'}`,
    ];
    if (sidecarFiles > 0) parts.push(`${sidecarFiles} sidecar${sidecarFiles === 1 ? '' : 's'}`);
    if (counts.depth > 0) parts.push(`${counts.depth} with depth`);
    if (counts.clouds > 0) {
      parts.push(`${counts.clouds} splat ${counts.clouds === 1 ? 'cloud' : 'clouds'}`);
    }
    if (counts.unreadable > 0) parts.push(`${counts.unreadable} unreadable`);
    if (counts.reading > 0) parts.push(`${counts.reading} reading`);
    if (sidecarFiles > 0) {
      const attached = [...sidecarLenses.keys()].filter((name) =>
        items.some((item) => item.file?.name === name || item.row.splat === name),
      ).length;
      const unmatched = sidecarFiles - attached;
      parts.push(
        `${sidecarFiles} lens ${sidecarFiles === 1 ? 'sidecar' : 'sidecars'}` +
          (unmatched > 0 ? ` (${unmatched} unmatched)` : ''),
      );
    }

    options.count.textContent = total === 0 ? 'No photos yet' : parts.join(' · ');
    options.empty.hidden = total > 0;
    options.clear.disabled = total === 0 || readOnly;
    options.clear.title = readOnly
      ? 'library.json was written by a newer build; this page will not clear it.'
      : '';
  }

  options.clear.addEventListener('click', () => void clear());

  await load();

  // After the library's own sentence, so neither can hide the other: both blobs
  // can be unreadable, and the prefs are the second one read.
  const prefsMessage = prefsRead.message ?? prefsReadError;
  if (prefsMessage !== null) noticeMore(prefsMessage);

  return {
    get items(): readonly GalleryItem[] {
      return items;
    },
    store,
    get readOnly(): boolean {
      return readOnly;
    },
    addFiles,
    reload: load,
  };
}

/**
 * `basrelief_<tag>.splat.json` is a lens sidecar, not media: it describes the cloud
 * at the same path without the `.json`. Matched on the extension because that is
 * the node's own name for it, and the cloud's name is matched exactly — no fuzzy
 * pairing, since a sidecar attached to the wrong cloud states a camera for a
 * picture it does not describe.
 */
function isSidecarName(name: string): boolean {
  return /\.splat\.json$/i.test(name);
}

function isSplatName(name: string): boolean {
  return name.toLowerCase().endsWith('.splat');
}

function setBadge(
  badge: HTMLElement,
  text: string,
  className: string,
  title: string,
): void {
  badge.textContent = text;
  badge.className = `badge ${className}`;
  badge.title = title;
}

/** `randomUUID` needs a secure context; a plain-HTTP LAN preview still gets ids. */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** One decode, answering whether this file is an image at all. */
function probeImage(url: string): Promise<{ ok: boolean; width: number; height: number }> {
  return new Promise((resolve) => {
    if (url === '') {
      resolve({ ok: false, width: 0, height: 0 });
      return;
    }
    const image = new Image();
    image.onload = () =>
      resolve({ ok: true, width: image.naturalWidth, height: image.naturalHeight });
    image.onerror = () => resolve({ ok: false, width: 0, height: 0 });
    image.src = url;
  });
}
