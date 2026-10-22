import { extractEmbeddedDepth, type DephResult } from './deph.ts';
import {
  classifyMedia,
  formatBytes,
  formatDimensions,
  formatSplatCount,
  type MediaState,
} from './media.ts';
import { openViewer } from './viewer.ts';

/**
 * The gallery: files in, cards out, one inspection per file.
 *
 * There is no server and no persistence, so the File objects in this list ARE the
 * gallery. Each one is inspected once — image decode and the `dePH` read are two
 * separate reads of one file, because a decoded ImageBitmap cannot give the
 * chunks back — and the answer is the one `classifyMedia` returned.
 */

export interface GalleryItem {
  id: string;
  file: File;
  /** Object URL for the image; revoked when the card is removed. */
  url: string;
  state: MediaState;
  depth: Extract<DephResult, { ok: true }> | null;
  width: number;
  height: number;
}

interface CardParts {
  element: HTMLLIElement;
  open: HTMLElement;
  thumb: HTMLElement;
  name: HTMLElement;
  meta: HTMLElement;
  badge: HTMLElement;
  reason: HTMLElement;
}

export interface GalleryOptions {
  grid: HTMLElement;
  empty: HTMLElement;
  count: HTMLElement;
  clear: HTMLButtonElement;
}

export interface Gallery {
  addFiles(files: Iterable<File>): void;
  readonly items: readonly GalleryItem[];
}

export function createGallery(options: GalleryOptions): Gallery {
  const items: GalleryItem[] = [];
  const cards = new Map<string, CardParts>();

  function isOpenable(state: MediaState): boolean {
    return state.kind === 'relief' || state.kind === 'photo' || state.kind === 'cloud';
  }

  function addFiles(files: Iterable<File>): void {
    for (const file of files) {
      const item: GalleryItem = {
        id: newId(),
        file,
        url: URL.createObjectURL(file),
        state: { kind: 'reading' },
        depth: null,
        width: 0,
        height: 0,
      };
      items.push(item);

      const parts = createCard(item);
      cards.set(item.id, parts);
      options.grid.append(parts.element);
      renderCard(item);

      void inspect(item);
    }
    renderCounts();
  }

  function remove(item: GalleryItem): void {
    const index = items.indexOf(item);
    if (index < 0) return;
    items.splice(index, 1);
    URL.revokeObjectURL(item.url);
    cards.get(item.id)?.element.remove();
    cards.delete(item.id);
    renderCounts();
  }

  function clear(): void {
    for (const item of [...items]) remove(item);
  }

  async function inspect(item: GalleryItem): Promise<void> {
    try {
      // A `.splat` is known from its name and length alone; do not read a
      // multi-megabyte cloud just to put its count on a card.
      const early = classifyMedia({
        name: item.file.name,
        size: item.file.size,
        depth: null,
        readError: null,
        decodes: null,
      });
      if (early.kind !== 'reading') {
        finish(item, early);
        return;
      }

      const decoded = await probeImage(item.url);
      item.width = decoded.width;
      item.height = decoded.height;

      let depth: DephResult | null = null;
      try {
        const bytes = new Uint8Array(await item.file.arrayBuffer());
        depth = extractEmbeddedDepth(bytes);
      } catch (err) {
        finish(item, {
          kind: 'unreadable',
          reason: err instanceof Error ? err.message : 'the file could not be read',
        });
        return;
      }

      item.depth = depth.ok ? depth : null;

      finish(
        item,
        classifyMedia({
          name: item.file.name,
          size: item.file.size,
          depth,
          readError: null,
          decodes: decoded.ok,
        }),
      );
    } catch (err) {
      finish(item, {
        kind: 'unreadable',
        reason: err instanceof Error ? err.message : 'the file could not be read',
      });
    }
  }

  function finish(item: GalleryItem, state: MediaState): void {
    if (!items.includes(item)) return; // removed while it was being read
    item.state = state;
    renderCard(item);
    renderCounts();
  }

  function createCard(item: GalleryItem): CardParts {
    const element = document.createElement('li');
    element.className = 'card';
    element.dataset.id = item.id;

    const open = document.createElement('div');
    open.className = 'card-open';
    open.tabIndex = 0;
    open.setAttribute('role', 'button');
    open.setAttribute('aria-label', `Open ${item.file.name}`);

    const thumb = document.createElement('div');
    thumb.className = 'card-thumb';

    const body = document.createElement('div');
    body.className = 'card-body';

    const name = document.createElement('div');
    name.className = 'card-name';
    name.textContent = item.file.name;
    name.title = item.file.name;

    const meta = document.createElement('div');
    meta.className = 'card-meta';

    const badge = document.createElement('span');
    badge.className = 'badge';

    const reason = document.createElement('div');
    reason.className = 'card-reason';
    reason.hidden = true;

    body.append(name, meta);
    const badgeRow = document.createElement('div');
    badgeRow.className = 'card-badges';
    badgeRow.append(badge);
    body.append(badgeRow, reason);
    open.append(thumb, body);

    const removeButton = document.createElement('button');
    removeButton.type = 'button';
    removeButton.className = 'card-remove';
    removeButton.textContent = '×';
    removeButton.setAttribute('aria-label', `Remove ${item.file.name}`);
    removeButton.addEventListener('click', (event) => {
      event.stopPropagation();
      remove(item);
    });

    const activate = (): void => {
      if (!isOpenable(item.state)) return;
      openViewer({
        name: item.file.name,
        url: item.url,
        state: item.state,
        depth: item.depth,
        size: item.file.size,
        width: item.width,
        height: item.height,
      });
    };

    open.addEventListener('click', activate);
    open.addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      activate();
    });

    element.append(open, removeButton);
    return { element, open, thumb, name, meta, badge, reason };
  }

  function renderCard(item: GalleryItem): void {
    const parts = cards.get(item.id);
    if (!parts) return;

    const { state } = item;
    parts.open.classList.toggle('card-open--disabled', !isOpenable(state));
    parts.open.setAttribute('aria-disabled', String(!isOpenable(state)));

    // The thumbnail is rebuilt rather than patched: there is one of each kind
    // (an img, a skeleton, a cloud texture, a warning), and which one it is IS
    // the state.
    parts.thumb.replaceChildren();
    parts.thumb.className = 'card-thumb';

    if (state.kind === 'relief' || state.kind === 'photo') {
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
    }
    const dims = formatDimensions(item.width, item.height);
    if (dims) facts.push(dims);
    facts.push(formatBytes(item.file.size));
    parts.meta.textContent = facts.join(' · ');

    switch (state.kind) {
      case 'relief':
        setBadge(
          parts.badge,
          'depth',
          'badge--depth',
          `embedded depth map (dePH, ${state.form === 'raw' ? 'raw grey' : 'whole PNG'})`,
        );
        break;
      case 'photo':
        setBadge(parts.badge, 'no depth', 'badge--none', state.detail);
        break;
      case 'cloud':
        setBadge(parts.badge, 'cloud', 'badge--cloud', 'Gaussian splat cloud');
        break;
      case 'unreadable':
        setBadge(parts.badge, 'unreadable', 'badge--warn', state.reason);
        parts.reason.hidden = false;
        parts.reason.textContent = state.reason;
        parts.reason.title = state.reason;
        break;
      case 'reading':
        setBadge(parts.badge, 'reading…', 'badge--reading', 'still being read');
        break;
    }

    if (state.kind !== 'unreadable') {
      parts.reason.hidden = true;
      parts.reason.textContent = '';
      parts.reason.title = '';
    }
  }

  function renderCounts(): void {
    const counts = { depth: 0, clouds: 0, unreadable: 0, reading: 0 };
    for (const item of items) {
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

    const parts: string[] = [
      `${items.length} ${items.length === 1 ? 'file' : 'files'}`,
    ];
    if (counts.depth > 0) parts.push(`${counts.depth} with depth`);
    if (counts.clouds > 0) {
      parts.push(`${counts.clouds} splat ${counts.clouds === 1 ? 'cloud' : 'clouds'}`);
    }
    if (counts.unreadable > 0) parts.push(`${counts.unreadable} unreadable`);
    if (counts.reading > 0) parts.push(`${counts.reading} reading`);

    options.count.textContent = items.length === 0 ? 'No files yet' : parts.join(' · ');
    options.empty.hidden = items.length > 0;
    options.clear.disabled = items.length === 0;
  }

  options.clear.addEventListener('click', clear);
  renderCounts();

  return {
    addFiles,
    get items(): readonly GalleryItem[] {
      return items;
    },
  };
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
    const image = new Image();
    image.onload = () => resolve({ ok: true, width: image.naturalWidth, height: image.naturalHeight });
    image.onerror = () => resolve({ ok: false, width: 0, height: 0 });
    image.src = url;
  });
}
