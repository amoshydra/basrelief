import type { DephSuccess } from './deph.ts';
import {
  describeMedia,
  formatBytes,
  formatDimensions,
  formatSplatCount,
  type MediaState,
} from './media.ts';
import { createCloudView, type CloudView } from './cloud.ts';
import { colourPngBytes, packPng, packedFileName } from './pack.ts';
import { DEFAULT_DEPTH, DEFAULT_MOTION, createReliefView, type ReliefView } from './relief.ts';
import { NO_LENS_FOVY_DEG, sourceFovy } from './splat.ts';
import { createDeviceTilt, type DeviceTilt } from './tilt.ts';

/**
 * The viewer's ONE dispatch point.
 *
 * A dropped file decides its own medium, and this is the only place that maps a
 * medium to a renderer. The relief renderer is behind `relief.ts`, a photograph
 * is a plain image (with no depth there is nothing to displace), and a cloud is
 * behind `cloud.ts`. That is deliberately a `switch` with a named function per
 * case rather than a renderer interface: one more medium is one more case, not an
 * abstraction to design around.
 */

export interface OpenableItem {
  name: string;
  /** The dropped file itself; a cloud's bytes are read from here. */
  file: File;
  /** Object URL for the colour image; owned by the gallery item, not revoked here. */
  url: string;
  state: MediaState;
  depth: DephSuccess | null;
  size: number;
  width: number;
  height: number;
  /** `focal / height` from the cloud's sidecar, or 0 when nothing claimed a lens. */
  lens: number;
  /** The sidecar that stated it, for the line that says which camera this is. */
  lensFrom: string | null;
}

export function openViewer(item: OpenableItem): void {
  const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;

  const overlay = document.createElement('div');
  overlay.className = 'viewer';
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-label', item.name);

  const stage = document.createElement('div');
  stage.className = 'viewer-stage';

  const bar = document.createElement('div');
  bar.className = 'viewer-bar';

  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'viewer-back';
  back.textContent = '← Gallery';
  back.setAttribute('aria-label', 'Back to the gallery');

  const heading = document.createElement('div');
  heading.className = 'viewer-heading';
  const name = document.createElement('div');
  name.className = 'viewer-name';
  name.textContent = item.name;
  const meta = document.createElement('div');
  meta.className = 'viewer-meta';
  meta.textContent = [
    describeMedia(item.state),
    formatDimensions(item.width, item.height),
    formatBytes(item.size),
  ]
    .filter(Boolean)
    .join(' · ');

  heading.append(name, meta);
  bar.append(back, heading);
  overlay.append(stage, bar);
  document.body.append(overlay);
  document.body.classList.add('viewer-open');

  const teardown: Array<() => void> = [];
  let relief: ReliefView | null = null;
  let cloud: CloudView | null = null;
  let tilt: DeviceTilt | null = null;
  let abort: AbortController | null = null;
  let closed = false;

  function close(): void {
    if (closed) return;
    closed = true;
    abort?.abort();
    tilt?.stop();
    relief?.destroy();
    cloud?.destroy();
    for (const fn of teardown) fn();
    document.removeEventListener('keydown', onKey);
    overlay.remove();
    document.body.classList.remove('viewer-open');
    previousFocus?.focus();
  }

  function onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  }

  document.addEventListener('keydown', onKey);
  back.addEventListener('click', close);
  back.focus();

  switch (item.state.kind) {
    case 'relief':
      if (item.depth) {
        void mountRelief(item.depth);
      } else {
        mountMessage('This photo’s depth map is missing from the item.', 'Nothing was drawn.');
      }
      break;
    case 'photo':
      mountPhoto();
      break;
    case 'cloud':
      void mountCloud();
      break;
    case 'unreadable':
      mountMessage('This file could not be read.', item.state.reason);
      break;
    case 'reading':
      mountMessage('This file is still being read.', 'Wait a moment and open it again.');
      break;
  }

  function setDock(dock: HTMLElement): void {
    overlay.append(dock);
    teardown.push(() => dock.remove());
  }

  async function mountRelief(depth: DephSuccess): Promise<void> {
    const loader = document.createElement('div');
    loader.className = 'viewer-loading';
    loader.textContent = 'loading relief…';
    stage.append(loader);
    teardown.push(() => loader.remove());

    abort = new AbortController();
    try {
      relief = await createReliefView(stage, {
        imageUrl: item.url,
        depth,
        signal: abort.signal,
      });
    } catch (err) {
      loader.remove();
      if (closed || (err instanceof DOMException && err.name === 'AbortError')) return;
      const reason = err instanceof Error ? err.message : 'the renderer could not start';
      mountMessage('This photo’s depth map could not be drawn.', reason);
      return;
    }

    loader.remove();
    if (closed) {
      relief.destroy();
      return;
    }

    stage.classList.add('stage--relief');
    tilt = createDeviceTilt((x, y) => relief?.setTilt(x, y));

    // The controls are built only once the renderer is actually showing, so a
    // failed decode never leaves sliders that cannot change a pixel.
    const dock = document.createElement('div');
    dock.className = 'viewer-dock';

    const depthControl = slider({
      label: 'Depth',
      value: Math.round(DEFAULT_DEPTH * 100),
      onInput: (value) => relief?.setDepth(value / 100),
    });
    const motionControl = slider({
      label: 'Motion',
      value: Math.round(DEFAULT_MOTION * 100),
      onInput: (value) => relief?.setMotion(value / 100),
    });

    const reset = button('Reset view', () => {
      depthControl.set(Math.round(DEFAULT_DEPTH * 100));
      motionControl.set(Math.round(DEFAULT_MOTION * 100));
      relief?.setTilt(0, 0);
      tilt?.calibrate();
    });

    /**
     * Save packed PNG: the one action on this page that writes a file. A relief
     * already carries its depth, so today this re-packs it: same colour bytes,
     * same depth, one chunk, in the slot every reader walks to. The writer exists
     * for the next task — generating a depth map for a photo — which is why it
     * carries a re-encode path this button cannot reach yet.
     *
     * The re-encode sentence is unreachable from here as long as a relief must
     * have a `dePH`/`dEPt` chunk and only a PNG can carry one. It is written now
     * so that path cannot arrive with the colour silently re-encoded.
     */
    const packNote = document.createElement('div');
    packNote.className = 'viewer-note';
    packNote.hidden = true;

    const save = button('Save packed PNG', async () => {
      save.disabled = true;
      save.textContent = 'Packing…';
      packNote.classList.remove('viewer-note--warn');
      try {
        const colour = await colourPngBytes(item.file);
        // The viewer can be closed while a large image decodes; downloading a file
        // nobody is waiting for is worse than doing nothing.
        if (closed) return;
        const packed = packPng(colour.bytes, depth);
        if (!packed.ok) {
          packNote.hidden = false;
          packNote.classList.add('viewer-note--warn');
          packNote.textContent = `Could not pack this photo: ${packed.detail}`;
          return;
        }
        const filename = packedFileName(item.file.name);
        downloadPng(packed.png, filename);
        save.textContent = 'Saved packed PNG';
        packNote.hidden = false;
        packNote.textContent = colour.reencoded
          ? 'The colour was re-encoded to PNG because the dropped file was not a PNG — ' +
            'the saved picture is a canvas render of it, not the original bytes.'
          : `Saved as ${filename}: the original colour bytes with the depth map inserted` +
            `${packed.replaced > 0 ? ', replacing the depth chunk it already had' : ''}.`;
      } catch (err) {
        packNote.hidden = false;
        packNote.classList.add('viewer-note--warn');
        packNote.textContent =
          `Could not pack this photo: ` +
          `${err instanceof Error ? err.message : 'the file could not be read'}`;
      } finally {
        save.disabled = false;
        if (save.textContent === 'Packing…') save.textContent = 'Save packed PNG';
      }
    });

    const hint = document.createElement('div');
    hint.className = 'viewer-hint';
    hint.textContent = 'drag on the photo, or use device tilt';

    let tiltRunning = false;
    const tiltButton = button('Use device tilt', async () => {
      if (!tilt) return;
      if (!tiltRunning) {
        const started = await tilt.start();
        if (!started) {
          tiltButton.textContent = 'No device tilt';
          hint.textContent = 'this browser exposes no orientation sensor — drag on the photo';
          return;
        }
        tiltRunning = true;
        tiltButton.textContent = 'Using device tilt';
        hint.textContent = 'waiting for the orientation sensor…';
        // A laptop can expose the API and never emit an event. Say which it was
        // rather than promising motion that will not come.
        window.setTimeout(() => {
          if (closed || !tiltRunning) return;
          hint.textContent = tilt?.isActive()
            ? 'tilt your device, or drag on the photo'
            : 'no orientation events arrived — drag on the photo';
        }, 1000);
      } else {
        tilt.stop();
        tiltRunning = false;
        tiltButton.textContent = 'Use device tilt';
        hint.textContent = 'drag on the photo, or use device tilt';
      }
    });

    const actions = document.createElement('div');
    actions.className = 'viewer-actions';
    actions.append(reset, tiltButton, save);

    dock.append(depthControl.element, motionControl.element, actions, packNote, hint);
    setDock(dock);

    // Pointer drag drives the same tilt target the gyro does. A drag that ends
    // lets the picture settle back to rest rather than holding the last tilt.
    let dragging = false;
    const applyPointer = (event: PointerEvent): void => {
      const rect = stage.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      relief?.setTilt(
        ((event.clientX - rect.left) / rect.width) * 2 - 1,
        ((event.clientY - rect.top) / rect.height) * 2 - 1,
      );
    };
    const onDown = (event: PointerEvent): void => {
      if (tilt?.isActive()) return;
      dragging = true;
      try {
        stage.setPointerCapture(event.pointerId);
      } catch {
        // A synthetic or already-released pointer cannot be captured; the drag
        // still works from the move events, so this is not a failure.
      }
      applyPointer(event);
    };
    const onMove = (event: PointerEvent): void => {
      if (dragging) applyPointer(event);
    };
    const onUp = (event: PointerEvent): void => {
      if (!dragging) return;
      dragging = false;
      if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
      relief?.setTilt(0, 0);
    };

    stage.addEventListener('pointerdown', onDown);
    stage.addEventListener('pointermove', onMove);
    stage.addEventListener('pointerup', onUp);
    stage.addEventListener('pointercancel', onUp);
    teardown.push(() => {
      stage.removeEventListener('pointerdown', onDown);
      stage.removeEventListener('pointermove', onMove);
      stage.removeEventListener('pointerup', onUp);
      stage.removeEventListener('pointercancel', onUp);
    });
  }

  function mountPhoto(): void {
    const image = document.createElement('img');
    image.className = 'viewer-photo';
    image.src = item.url;
    image.alt = item.name;
    stage.append(image);

    const note = document.createElement('div');
    note.className = 'viewer-hint viewer-hint--floating';
    // The reason a Save action is not here, stated where its absence is: a
    // packed PNG is a colour image PLUS a depth map, and there is no map.
    note.textContent = 'No embedded depth — shown flat, nothing to pack.';
    stage.append(note);
  }

  /**
   * The cloud's case. It is async because the file has to be read, and the
   * renderer is built only after that succeeds — so a failed read leaves a
   * message, never a canvas with no data.
   *
   * The controls are the relief's, minus Depth (a cloud's shape is the node's
   * measurement, and this viewer does not displace it): Motion and the tilt come
   * from the same two sources, and the lens line states which camera is drawing —
   * because a cloud drawn through the wrong lens looks perfectly fine and is the
   * wrong picture.
   */
  async function mountCloud(): Promise<void> {
    if (item.state.kind !== 'cloud') return;
    const splats = item.state.splats;

    const loader = document.createElement('div');
    loader.className = 'viewer-loading';
    loader.textContent = 'loading cloud…';
    stage.append(loader);
    teardown.push(() => loader.remove());

    let bytes: Uint8Array;
    try {
      bytes = new Uint8Array(await item.file.arrayBuffer());
    } catch (err) {
      loader.remove();
      if (closed) return;
      mountMessage(
        'This cloud’s bytes could not be read.',
        err instanceof Error ? err.message : 'the file could not be read',
      );
      return;
    }
    if (closed) {
      loader.remove();
      return;
    }

    try {
      cloud = createCloudView(stage, { bytes, lens: item.lens });
    } catch (err) {
      loader.remove();
      mountMessage(
        'This cloud could not be drawn.',
        err instanceof Error ? err.message : 'the renderer could not start',
      );
      return;
    }
    loader.remove();

    stage.classList.add('stage--cloud');
    tilt = createDeviceTilt((x, y) => cloud?.setTilt(x, y));

    const dock = document.createElement('div');
    dock.className = 'viewer-dock';

    const motionControl = slider({
      label: 'Motion',
      value: Math.round(DEFAULT_MOTION * 100),
      onInput: (value) => cloud?.setMotion(value / 100),
    });

    const reset = button('Reset view', () => {
      motionControl.set(Math.round(DEFAULT_MOTION * 100));
      cloud?.setTilt(0, 0);
      tilt?.calibrate();
    });

    const hint = document.createElement('div');
    hint.className = 'viewer-hint';
    hint.textContent = 'drag on the cloud, or use device tilt';

    let tiltRunning = false;
    const tiltButton = button('Use device tilt', async () => {
      if (!tilt) return;
      if (!tiltRunning) {
        const started = await tilt.start();
        if (!started) {
          tiltButton.textContent = 'No device tilt';
          hint.textContent = 'this browser exposes no orientation sensor — drag on the cloud';
          return;
        }
        tiltRunning = true;
        tiltButton.textContent = 'Using device tilt';
        hint.textContent = 'waiting for the orientation sensor…';
        window.setTimeout(() => {
          if (closed || !tiltRunning) return;
          hint.textContent = tilt?.isActive()
            ? 'tilt your device, or drag on the cloud'
            : 'no orientation events arrived — drag on the cloud';
        }, 1000);
      } else {
        tilt.stop();
        tiltRunning = false;
        tiltButton.textContent = 'Use device tilt';
        hint.textContent = 'drag on the cloud, or use device tilt';
      }
    });

    // Which camera is drawing, said rather than guessed. A cloud through the wrong
    // lens is not visibly broken — it is the wrong picture — so the two answers a
    // missing number can have are both stated, and they are not the same sentence.
    const lens = document.createElement('div');
    lens.className = 'viewer-note';
    lens.textContent = lensLine(item.lens, item.lensFrom);

    // A cloud is not a PNG, so there is nothing for the packer to insert a chunk
    // into. Said rather than left as a disabled button nobody can explain.
    const packNote = document.createElement('div');
    packNote.className = 'viewer-note';
    packNote.textContent = 'Nothing to save as a packed PNG — a .splat cloud is not a PNG.';

    const actions = document.createElement('div');
    actions.className = 'viewer-actions';
    actions.append(reset, tiltButton);

    dock.append(motionControl.element, actions, lens, packNote);
    if (splats >= 750_000) {
      const cost = document.createElement('div');
      cost.className = 'viewer-note viewer-note--warn';
      // The app's own ladder, stated as what it is: measured on a phone, not a
      // promise about this machine.
      cost.textContent =
        `${formatSplatCount(splats)} — a cloud this dense is the top of the app's ` +
        'ladder: ~5 fps on a 2018 phone. Your machine decides.';
      dock.append(cost);
    }
    dock.append(hint);
    setDock(dock);

    // Pointer drag drives the same tilt target the gyro does, and a drag that ends
    // lets the cloud settle back to rest rather than holding the last tilt.
    let dragging = false;
    const applyPointer = (event: PointerEvent): void => {
      const rect = stage.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      cloud?.setTilt(
        ((event.clientX - rect.left) / rect.width) * 2 - 1,
        ((event.clientY - rect.top) / rect.height) * 2 - 1,
      );
    };
    const onDown = (event: PointerEvent): void => {
      if (tilt?.isActive()) return;
      dragging = true;
      try {
        stage.setPointerCapture(event.pointerId);
      } catch {
        // A synthetic or already-released pointer cannot be captured; the drag
        // still works from the move events, so this is not a failure.
      }
      applyPointer(event);
    };
    const onMove = (event: PointerEvent): void => {
      if (dragging) applyPointer(event);
    };
    const onUp = (event: PointerEvent): void => {
      if (!dragging) return;
      dragging = false;
      if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
      cloud?.setTilt(0, 0);
    };

    stage.addEventListener('pointerdown', onDown);
    stage.addEventListener('pointermove', onMove);
    stage.addEventListener('pointerup', onUp);
    stage.addEventListener('pointercancel', onUp);
    teardown.push(() => {
      stage.removeEventListener('pointerdown', onDown);
      stage.removeEventListener('pointermove', onMove);
      stage.removeEventListener('pointerup', onUp);
      stage.removeEventListener('pointercancel', onUp);
    });
  }

  function mountMessage(title: string, detail: string): void {
    const card = document.createElement('div');
    card.className = 'viewer-message';
    const headingEl = document.createElement('div');
    headingEl.className = 'viewer-message-title';
    headingEl.textContent = title;
    const body = document.createElement('p');
    body.textContent = detail;
    card.append(headingEl, body);
    stage.append(card);
  }
}

/**
 * Which camera a cloud is drawn through, in one line.
 *
 * Three states, and they are three different sentences: a sidecar with a usable
 * lens (the cloud's own camera), a sidecar that states no lens (the node's 0, or a
 * missing number — "no claim"), and no sidecar at all. The last two draw the same
 * default look, and saying which one it is is the difference between an honest
 * fallback and a guessed lens.
 */
function lensLine(lens: number, from: string | null): string {
  if (from && lens > 0) {
    return `lens from ${from} — ${sourceFovy(lens).toFixed(1)}° vertical field of view`;
  }
  const fallback = `${NO_LENS_FOVY_DEG.toFixed(1)}° vertical, the app’s default look`;
  if (from) return `${from} states no usable lens — drawn at ${fallback}`;
  return `no lens sidecar — drawn at ${fallback}`;
}

interface SliderOptions {
  label: string;
  value: number;
  onInput: (value: number) => void;
}

interface SliderControl {
  element: HTMLElement;
  set(value: number): void;
}

function slider(options: SliderOptions): SliderControl {
  const row = document.createElement('label');
  row.className = 'viewer-slider';

  const label = document.createElement('span');
  label.className = 'viewer-slider-label';
  label.textContent = options.label;

  const input = document.createElement('input');
  input.type = 'range';
  input.min = '0';
  input.max = '100';
  input.step = '1';
  input.value = String(options.value);

  const output = document.createElement('span');
  output.className = 'viewer-slider-value';
  output.textContent = `${options.value}%`;

  const set = (value: number): void => {
    input.value = String(value);
    output.textContent = `${value}%`;
    options.onInput(value);
  };

  input.addEventListener('input', () => set(Number(input.value)));
  set(options.value);

  row.append(label, input, output);
  return {
    element: row,
    set(value: number): void {
      input.value = String(value);
      output.textContent = `${value}%`;
      options.onInput(value);
    },
  };
}

function button(text: string, onClick: () => void | Promise<void>): HTMLButtonElement {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = 'viewer-button';
  element.textContent = text;
  element.addEventListener('click', () => void onClick());
  return element;
}

/**
 * Hand the packed bytes to the browser as a download.
 *
 * The object URL is revoked late rather than immediately: a revoke in the same
 * task as the click is a download some browsers cancel, and the symptom is a
 * button that appears to work and a file that never arrives.
 */
function downloadPng(bytes: Uint8Array, filename: string): void {
  // `new Uint8Array(bytes)` copies into an ArrayBuffer-backed view, which is what
  // `Blob` accepts; the writer's own array is typed over `ArrayBufferLike`.
  const blob = new Blob([new Uint8Array(bytes)], { type: 'image/png' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
