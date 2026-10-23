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
import { createReliefView, type ReliefView } from './relief.ts';
import {
  ADJUST_MAX,
  ADJUST_MIN,
  AZIMUTH_MAX,
  AZIMUTH_MIN,
  DEPTH_MAX,
  DEPTH_MIN,
  ELEVATION_MAX,
  ELEVATION_MIN,
  MOTION_MAX,
  MOTION_MIN,
  ZOOM_MAX,
  ZOOM_MIN,
  defaultSettings,
} from './settings.ts';
import {
  actionRow,
  caption,
  createSheet,
  note,
  readingRow,
  sheetButton,
  sliderRow,
  switchRow,
  type Sheet,
} from './sheet.ts';
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
 *
 * The controls are the studio's bottom sheet (`sheet.ts`), mounted from the OPEN
 * ITEM rather than from the gallery, and composed here from the rows that can
 * actually act on this page. A row whose renderer does not honour it is not
 * drawn, and an omission is stated in a reading row instead: see
 * {@link notOnThisPage}.
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
  let sheet: Sheet | null = null;
  let abort: AbortController | null = null;
  let closed = false;

  function close(): void {
    if (closed) return;
    closed = true;
    abort?.abort();
    tilt?.stop();
    relief?.destroy();
    cloud?.destroy();
    sheet?.destroy();
    for (const fn of teardown) fn();
    document.removeEventListener('keydown', onKey);
    overlay.remove();
    document.body.classList.remove('viewer-open');
    previousFocus?.focus();
  }

  function onKey(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return;
    // The topmost thing first: Escape closes the sheet before it leaves the
    // picture, the way BACK does on the phone.
    if (sheet && !sheet.collapsed()) {
      event.preventDefault();
      sheet.collapse();
      return;
    }
    event.preventDefault();
    close();
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

  function setSheet(next: Sheet): void {
    sheet = next;
    overlay.append(next.element);
    // Torn down by close() with the renderer, so there is one order for the
    // picture and the controls over it.
  }

  /**
   * The rows that cannot act here, each with the sentence that makes the
   * omission a decision rather than an absence.
   *
   * Every one of these is a control the studio has: the Home/Lock segment picks
   * which surface a ROW renders, the playlists rotate what the wallpaper shows,
   * the archive hides photos from the gallery, and the Renderer segment switches
   * relief and cloud. None of them can change a pixel on this page — the file's
   * own kind decides its renderer and there is no wallpaper to set — so drawing
   * them would be the lie this project keeps refusing to ship.
   */
  function notOnThisPage(): HTMLElement[] {
    return [
      readingRow('Home and Lock', 'A page cannot set a wallpaper, so there is no second surface to adjust.'),
      readingRow('Playlists and archive', 'Nothing here is stored or kept, so there is nothing to rotate or hide.'),
      readingRow('Relief and Splat', 'The file’s own kind picks the renderer; this page does not convert one into the other.'),
      readingRow('Origin and Pan', 'This page’s camera has no orbit centre or pan, so those rows would move a number nothing draws.'),
    ];
  }

  /** One tilt row, shared by the two media: a state you can flip, or a reason. */
  function tiltRow(): ReturnType<typeof switchRow> {
    const row = switchRow({
      title: 'Device tilt',
      sub: 'Tilt the device to swing the camera; dragging does the same.',
      on: false,
      onToggle: (on) => {
        if (!tilt) return;
        if (!on) {
          tilt.stop();
          row.setSub('Tilt the device to swing the camera; dragging does the same.');
          return;
        }
        void tilt.start().then((started) => {
          if (closed) return;
          if (!started) {
            // The capability, not the user, said no. Turn the switch back off and
            // say which of the two happened rather than promising motion that
            // will never arrive.
            row.set(false);
            row.setSub('This browser exposes no orientation sensor — drag on the picture.');
            return;
          }
          row.setSub('Using the sensor. Tilt the device, or drag on the picture.');
        });
      },
    });
    return row;
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
    const view = relief;

    stage.classList.add('stage--relief');
    tilt = createDeviceTilt((x, y) => view.setTilt(x, y));
    const settings = defaultSettings();

    const panel = createSheet({ label: 'Picture settings' });

    // ---------------------------------------------------------------- "Depth"
    panel.body.append(caption('Standing out of the plane', true));
    const depthRow = sliderRow({
      label: 'Depth',
      value: settings.depth,
      min: DEPTH_MIN,
      max: DEPTH_MAX,
      format: (v) => `${v}%`,
      onInput: (v) => view.setDepth(v / 100),
    });
    panel.body.append(depthRow.element);

    // ------------------------------------------------------------- "How it moves"
    panel.body.append(caption('How it moves'));
    const motionRow = sliderRow({
      label: 'Motion',
      value: settings.motion,
      min: MOTION_MIN,
      max: MOTION_MAX,
      format: (v) => `${v}%`,
      onInput: (v) => view.setMotion(v / 100),
    });
    // The app's FOV row: the stored unit is a percentage of zoom, and the number
    // printed is the field of view that percentage leaves — 45° at 100%.
    const zoomRow = sliderRow({
      label: 'FOV',
      value: settings.zoom,
      min: ZOOM_MIN,
      max: ZOOM_MAX,
      format: (v) => `${Math.round(45 / (v / 100))}°`,
      onInput: (v) => view.setZoom(v / 100),
    });
    const tiltControl = tiltRow();
    panel.body.append(motionRow.element, zoomRow.element, tiltControl.element);

    // --------------------------------------------------------------- "Picture"
    panel.body.append(caption('Picture'));
    let brightness = settings.brightness;
    let shadow = settings.shadow;
    const applyAdjust = (): void => view.setAdjust(brightness, shadow);
    const brightnessRow = sliderRow({
      label: 'Brightness',
      value: brightness,
      min: ADJUST_MIN,
      max: ADJUST_MAX,
      format: signed,
      onInput: (v) => {
        brightness = v;
        applyAdjust();
      },
    });
    const shadowRow = sliderRow({
      label: 'Shadow',
      value: shadow,
      min: ADJUST_MIN,
      max: ADJUST_MAX,
      format: signed,
      onInput: (v) => {
        shadow = v;
        applyAdjust();
      },
    });
    panel.body.append(brightnessRow.element, shadowRow.element);

    // ----------------------------------------------------------------- "Light"
    panel.body.append(caption('Light'));
    let azimuth = settings.lightAzimuth;
    let elevation = settings.lightElevation;
    const applyLight = (): void => view.setLight(azimuth, elevation);
    // The row's domain is whole degrees. Azimuth's canonical range is [0, 360),
    // so its top is 359: 360 is the same light as 0, which the bottom already
    // offers — the app's own derivation.
    const azimuthRow = sliderRow({
      label: 'Azimuth',
      value: Math.round(azimuth) % 360,
      min: AZIMUTH_MIN,
      max: AZIMUTH_MAX - 1,
      format: (v) => `${v}°`,
      onInput: (v) => {
        azimuth = v;
        applyLight();
      },
    });
    const elevationRow = sliderRow({
      label: 'Elevation',
      value: Math.round(elevation),
      min: ELEVATION_MIN,
      max: ELEVATION_MAX,
      format: (v) => `${v}°`,
      onInput: (v) => {
        elevation = v;
        applyLight();
      },
    });
    panel.body.append(
      azimuthRow.element,
      elevationRow.element,
      note('Azimuth runs counter-clockwise from the right. Elevation 0 rakes across the relief; 90 is head-on.'),
    );

    // ------------------------------------------------------------- "This file"
    panel.body.append(caption('This file'));

    const packNote = note('');
    packNote.hidden = true;
    packNote.setAttribute('role', 'status');

    const save = actionRow({
      label: 'Save packed PNG',
      sub: 'The colour and the depth map it carries, in one PNG',
      onClick: () => void savePacked(),
    });
    panel.body.append(save, packNote);

    /**
     * Save packed PNG: the one action on this page that writes a file. A relief
     * already carries its depth, so today this re-packs it: same colour bytes,
     * same depth, one chunk, in the slot every reader walks to. The writer exists
     * for the next task — generating a depth map for a photo — which is why it
     * carries a re-encode path this button cannot reach yet.
     */
    async function savePacked(): Promise<void> {
      save.disabled = true;
      const label = save.querySelector('.sheet-action-label');
      if (label) label.textContent = 'Packing…';
      packNote.hidden = true;
      packNote.classList.remove('sheet-note--warn');
      try {
        const colour = await colourPngBytes(item.file);
        // The viewer can be closed while a large image decodes; downloading a file
        // nobody is waiting for is worse than doing nothing.
        if (closed) return;
        const packed = packPng(colour.bytes, depth);
        if (!packed.ok) {
          packNote.hidden = false;
          packNote.classList.add('sheet-note--warn');
          packNote.textContent = `Could not pack this photo: ${packed.detail}`;
          return;
        }
        const filename = packedFileName(item.file.name);
        downloadPng(packed.png, filename);
        if (label) label.textContent = 'Saved packed PNG';
        packNote.hidden = false;
        packNote.textContent = colour.reencoded
          ? 'The colour was re-encoded to PNG because the dropped file was not a PNG — ' +
            'the saved picture is a canvas render of it, not the original bytes.'
          : `Saved as ${filename}: the original colour bytes with the depth map inserted` +
            `${packed.replaced > 0 ? ', replacing the depth chunk it already had' : ''}.`;
      } catch (err) {
        packNote.hidden = false;
        packNote.classList.add('sheet-note--warn');
        packNote.textContent =
          `Could not pack this photo: ` +
          `${err instanceof Error ? err.message : 'the file could not be read'}`;
      } finally {
        save.disabled = false;
        if (label?.textContent === 'Packing…') label.textContent = 'Save packed PNG';
      }
    }

    // -------------------------------------------------------- "Not on this page"
    panel.body.append(caption('Not on this page'), ...notOnThisPage());

    // -------------------------------------------------------------------- foot
    const reset = sheetButton('Reset view', false, () => {
      const d = defaultSettings();
      brightness = d.brightness;
      shadow = d.shadow;
      azimuth = d.lightAzimuth;
      elevation = d.lightElevation;
      depthRow.set(d.depth);
      motionRow.set(d.motion);
      zoomRow.set(d.zoom);
      brightnessRow.set(d.brightness);
      shadowRow.set(d.shadow);
      azimuthRow.set(Math.round(d.lightAzimuth) % 360);
      elevationRow.set(Math.round(d.lightElevation));
      view.setDepth(d.depth / 100);
      view.setMotion(d.motion / 100);
      view.setZoom(d.zoom / 100);
      view.setAdjust(d.brightness, d.shadow);
      view.setLight(d.lightAzimuth, d.lightElevation);
      view.setTilt(0, 0);
      tilt?.calibrate();
    });
    const done = sheetButton('Done', false, () => panel.collapse());
    panel.footer.append(reset, done);

    setSheet(panel);

    // The initial values go to the renderer explicitly rather than relying on its
    // own defaults: the rows and the picture must be one number, and a row that
    // shows 90 while the renderer holds 0.5 is the disagreement this whole sheet
    // is built to prevent.
    view.setDepth(settings.depth / 100);
    view.setMotion(settings.motion / 100);
    view.setZoom(settings.zoom / 100);
    applyAdjust();
    applyLight();

    installDrag(
      stage,
      teardown,
      () => tilt?.isActive() === true,
      (x, y) => view.setTilt(x, y),
    );
  }

  function mountPhoto(): void {
    const image = document.createElement('img');
    image.className = 'viewer-photo';
    image.src = item.url;
    image.alt = item.name;
    stage.append(image);

    const noteEl = document.createElement('div');
    noteEl.className = 'viewer-hint viewer-hint--floating';
    // The reason a sheet is not here, stated where its absence is: every row on
    // the studio's sheet changes a displaced surface, and with no depth map there
    // is nothing to displace.
    noteEl.textContent = 'No embedded depth — shown flat, and there is nothing to adjust.';
    stage.append(noteEl);
  }

  /**
   * The cloud's case. It is async because the file has to be read, and the
   * renderer is built only after that succeeds — so a failed read leaves a
   * message, never a canvas with no data.
   *
   * The sheet is the relief's minus every row a cloud's renderer does not honour.
   * Motion is here because the cloud follows it; the app's cloud look knobs
   * (Depth, Size, Blur, Resolution) are not, and the sheet says so rather than
   * drawing four sliders over a picture they cannot change.
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
    const view = cloud;

    stage.classList.add('stage--cloud');
    tilt = createDeviceTilt((x, y) => view.setTilt(x, y));
    const settings = defaultSettings();

    const panel = createSheet({ label: 'Cloud settings' });

    panel.body.append(caption('How it moves', true));
    const motionRow = sliderRow({
      label: 'Motion',
      value: settings.motion,
      min: MOTION_MIN,
      max: MOTION_MAX,
      format: (v) => `${v}%`,
      onInput: (v) => view.setMotion(v / 100),
    });
    const tiltControl = tiltRow();
    panel.body.append(motionRow.element, tiltControl.element);

    panel.body.append(caption('This file'));
    // Which camera is drawing, said rather than guessed. A cloud through the
    // wrong lens is not visibly broken — it is the wrong picture — so the two
    // answers a missing number can have are both stated, and they are not the
    // same sentence.
    panel.body.append(readingRow('Lens', lensLine(item.lens, item.lensFrom)));
    // A cloud is not a PNG, so there is nothing for the packer to insert a chunk
    // into. Said rather than left as a disabled button nobody can explain.
    panel.body.append(
      readingRow('Packed PNG', 'Nothing to save — a .splat cloud is not a PNG.'),
    );
    if (splats >= 750_000) {
      // The app's own ladder, stated as what it is: measured on a phone, not a
      // promise about this machine.
      panel.body.append(
        readingRow(
          `${formatSplatCount(splats)} splats`,
          'A cloud this dense is the top of the app’s ladder: ~5 fps on a 2018 phone. Your machine decides.',
        ),
      );
    }

    panel.body.append(caption('Not on this page'));
    panel.body.append(
      ...notOnThisPage(),
      readingRow(
        'Cloud look',
        'Depth, Size, Blur and Resolution are not wired on this page yet, so this cloud is drawn at the app’s defaults.',
      ),
    );

    const reset = sheetButton('Reset view', false, () => {
      motionRow.set(settings.motion);
      view.setMotion(settings.motion / 100);
      view.setTilt(0, 0);
      tilt?.calibrate();
    });
    const done = sheetButton('Done', false, () => panel.collapse());
    panel.footer.append(reset, done);

    setSheet(panel);
    view.setMotion(settings.motion / 100);

    installDrag(
      stage,
      teardown,
      () => tilt?.isActive() === true,
      (x, y) => view.setTilt(x, y),
    );
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

/** A signed offset, bare: "+40"/"-10" already reads as an offset on this range. */
function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value);
}

/**
 * Pointer drag on the stage drives the same tilt target the gyro does, and a
 * drag that ends lets the picture settle back to rest rather than holding the
 * last tilt. Suspended while the sensor is live, because two inputs writing one
 * target at once is a picture that argues with itself.
 */
function installDrag(
  stage: HTMLElement,
  teardown: Array<() => void>,
  isTilting: () => boolean,
  setTilt: (x: number, y: number) => void,
): void {
  let dragging = false;
  const applyPointer = (event: PointerEvent): void => {
    const rect = stage.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    setTilt(
      ((event.clientX - rect.left) / rect.width) * 2 - 1,
      ((event.clientY - rect.top) / rect.height) * 2 - 1,
    );
  };
  const onDown = (event: PointerEvent): void => {
    if (isTilting()) return;
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
    setTilt(0, 0);
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
