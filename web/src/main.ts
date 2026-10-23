import './styles.css';
import { createGallery } from './gallery.ts';
import { createMirror, type MirrorState } from './mirror.ts';

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`#${id} is missing from index.html`);
  return found as T;
}

const gallery = await createGallery({
  grid: element('grid'),
  empty: element('empty'),
  count: element('count'),
  clear: element<HTMLButtonElement>('clear'),
  storage: element('storage-state'),
  notice: element('library-notice'),
});

const pick = element<HTMLButtonElement>('pick');
const dropzone = element('dropzone');
const input = element<HTMLInputElement>('file-input');

// --------------------------------------------------------------- the mirror
//
// The folder is a write-through copy, never a second truth: the library lives in
// OPFS, and every control here either writes it through or reads it back. Nothing
// on the page renders from the folder, so a revoked permission cannot blank the
// gallery — the mirror's own line says what happened and the photographs stay.

const mirror = createMirror();
const mirrorState = element('mirror-state');
const mirrorPick = element<HTMLButtonElement>('mirror-pick');
const mirrorGrant = element<HTMLButtonElement>('mirror-grant');
const mirrorBackup = element<HTMLButtonElement>('mirror-backup');
const mirrorRestore = element<HTMLButtonElement>('mirror-restore');
const mirrorForget = element<HTMLButtonElement>('mirror-forget');

function renderMirror(state: MirrorState): void {
  mirrorState.textContent = state.message;
  mirrorState.dataset.status = state.status;
  mirrorPick.hidden = state.status === 'unsupported';
  mirrorPick.textContent =
    state.status === 'none' || state.status === 'unsupported'
      ? 'Choose backup folder…'
      : 'Choose another folder…';
  mirrorGrant.hidden = state.status !== 'needs-permission';
  mirrorBackup.disabled = state.status !== 'ready';
  mirrorRestore.disabled = state.status !== 'ready';
  mirrorForget.hidden = state.status === 'none' || state.status === 'unsupported';
}

mirror.onChange(renderMirror);

mirrorPick.addEventListener('click', () => void mirror.choose());
mirrorGrant.addEventListener('click', () => void mirror.grant());
mirrorBackup.addEventListener('click', () => void mirror.backup(gallery.store));
mirrorForget.addEventListener('click', () => void mirror.forget());
mirrorRestore.addEventListener('click', async () => {
  mirrorRestore.disabled = true;
  const result = await mirror.restore(gallery.store);
  if (result.ok) {
    // A restore replaces the index, so the gallery is re-read rather than patched:
    // rows that came back, and rows that did not, are the blob's answer.
    await gallery.reload();
  }
  mirrorRestore.disabled = mirror.state().status !== 'ready';
});

void mirror.refresh();

// ---------------------------------------------------------------- the intake

function choose(): void {
  input.click();
}

pick.addEventListener('click', (event) => {
  event.stopPropagation();
  choose();
});

dropzone.addEventListener('click', (event) => {
  if ((event.target as Element).closest('button')) return;
  choose();
});

input.addEventListener('change', () => {
  if (input.files && input.files.length > 0) void gallery.addFiles(input.files);
  // Reset so choosing the same file twice still fires a change event.
  input.value = '';
});

// Drag and drop across the whole window, not only the dashed box: the product
// shape is "drop a file on a page", and a drop that misses the box by 20 px must
// not make the browser navigate away from the gallery.
let dragDepth = 0;

function carriesFiles(event: DragEvent): boolean {
  return event.dataTransfer?.types.includes('Files') === true;
}

function clearDrag(): void {
  dragDepth = 0;
  dropzone.classList.remove('is-over');
}

window.addEventListener('dragenter', (event) => {
  if (!carriesFiles(event)) return;
  event.preventDefault();
  dragDepth++;
  dropzone.classList.add('is-over');
});

window.addEventListener('dragover', (event) => {
  if (!carriesFiles(event)) return;
  event.preventDefault();
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
});

window.addEventListener('dragleave', (event) => {
  if (!carriesFiles(event)) return;
  event.preventDefault();
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) dropzone.classList.remove('is-over');
});

window.addEventListener('drop', (event) => {
  event.preventDefault();
  clearDrag();
  const files = event.dataTransfer?.files;
  if (files && files.length > 0) void gallery.addFiles(files);
});

// A drag that ends outside the window never gets a matching dragleave, so the
// drop zone would keep its highlight. Reset on the events that end one.
window.addEventListener('dragend', clearDrag);
window.addEventListener('blur', clearDrag);
