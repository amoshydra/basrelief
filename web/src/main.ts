import './styles.css';
import { createGallery } from './gallery.ts';

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`#${id} is missing from index.html`);
  return found as T;
}

const gallery = createGallery({
  grid: element('grid'),
  empty: element('empty'),
  count: element('count'),
  clear: element<HTMLButtonElement>('clear'),
});

const pick = element<HTMLButtonElement>('pick');
const dropzone = element('dropzone');
const input = element<HTMLInputElement>('file-input');

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
  if (input.files && input.files.length > 0) gallery.addFiles(input.files);
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
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) dropzone.classList.remove('is-over');
});

window.addEventListener('drop', (event) => {
  event.preventDefault();
  clearDrag();
  const files = event.dataTransfer?.files;
  if (files && files.length > 0) gallery.addFiles(files);
});

// A drag that ends outside the window never gets a matching dragleave, so the
// drop zone would keep its highlight. Reset on the events that end one.
window.addEventListener('dragend', clearDrag);
window.addEventListener('blur', clearDrag);
