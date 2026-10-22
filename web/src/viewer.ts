import type { DephSuccess } from './deph.ts';
import {
  describeMedia,
  formatBytes,
  formatDimensions,
  formatSplatCount,
  type MediaState,
} from './media.ts';
import { DEFAULT_DEPTH, DEFAULT_MOTION, createReliefView, type ReliefView } from './relief.ts';
import { createDeviceTilt, type DeviceTilt } from './tilt.ts';

/**
 * The viewer's ONE dispatch point.
 *
 * A dropped file decides its own medium, and this is the only place that maps a
 * medium to a renderer. The relief renderer is behind `relief.ts`; a photograph
 * is a plain image, because with no depth there is nothing to displace; a
 * `.splat` cloud is listed, counted and named, and gets its renderer in the next
 * change. That is deliberately a `switch` with a named function per case rather
 * than a renderer interface: one more medium is one more case, not an
 * abstraction to design around.
 */

export interface OpenableItem {
  name: string;
  /** Object URL for the colour image; owned by the gallery item, not revoked here. */
  url: string;
  state: MediaState;
  depth: DephSuccess | null;
  size: number;
  width: number;
  height: number;
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
  let tilt: DeviceTilt | null = null;
  let abort: AbortController | null = null;
  let closed = false;

  function close(): void {
    if (closed) return;
    closed = true;
    abort?.abort();
    tilt?.stop();
    relief?.destroy();
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
      mountCloud(item.state.splats);
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
    actions.append(reset, tiltButton);

    dock.append(depthControl.element, motionControl.element, actions, hint);
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
    note.textContent = 'No embedded depth — shown flat.';
    stage.append(note);
  }

  function mountCloud(splats: number): void {
    const card = document.createElement('div');
    card.className = 'cloud-card';

    const glyph = document.createElement('div');
    glyph.className = 'cloud-glyph';
    glyph.setAttribute('aria-hidden', 'true');

    const count = document.createElement('div');
    count.className = 'cloud-count';
    count.textContent = formatSplatCount(splats);

    const copy = document.createElement('p');
    copy.textContent =
      'This is a Gaussian splat cloud. Its renderer is the next change — the file is ' +
      'listed and counted here, not drawn yet.';

    card.append(glyph, count, copy);
    stage.append(card);
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
