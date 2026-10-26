import { createCloudView, type CloudView } from './cloud.ts';
import { defaultSettings } from './settings.ts';

/**
 * A loop of the bundled marble cloud, for the README's motion preview.
 *
 * This is a demo page, not a second viewer: it has one cloud, no library and no
 * controls. The camera moves through a closed sine cycle, so the last frame is
 * the first frame and the loop has no cut. `?capture` starts paused at t=0; the
 * capture harness resumes it, records one period, and stops it. That is why
 * `window.__basreliefDemo` exists: a recorded frame's time must be the page's
 * time, not whatever the browser's first paint happened to catch.
 */

interface DemoHandle {
  readonly ready: true;
  readonly period: number;
  readonly time: number;
  pause(): void;
  resume(): void;
  seek(ms: number): void;
}

declare global {
  interface Window {
    __basreliefDemo?: DemoHandle;
  }
}

const PERIOD_MS = 10_000;

function requiredElement(id: string): HTMLElement {
  const element = document.getElementById(id);
  if (element === null) throw new Error(`#${id} is missing`);
  return element;
}

const stage = requiredElement('stage');
const loading = requiredElement('loading');
const errorBox = requiredElement('error');

const capture = new URLSearchParams(window.location.search).has('capture');
let view: CloudView;
let paused = capture;
let phase = 0;
let lastFrame = 0;

/** The closed camera cycle. Both ends are the same pose, so the loop is a cut. */
function pose(ms: number): void {
  const turn = (ms / PERIOD_MS) * Math.PI * 2;
  // A lateral turntable plus a smaller fore/aft roll, with a gentle dolly.
  view.setTilt(0.62 * Math.sin(turn), 0.22 * Math.sin(turn * 2));
  view.setPanZ(1 + 0.10 * Math.cos(turn));
}

function tick(now: number): void {
  if (!paused) {
    if (lastFrame === 0) lastFrame = now;
    phase = (phase + (now - lastFrame)) % PERIOD_MS;
    lastFrame = now;
    pose(phase);
  } else {
    lastFrame = 0;
  }
  requestAnimationFrame(tick);
}

async function main(): Promise<void> {
  try {
    const cloudUrl = new URL('demo/demo-cloud.splat', document.baseURI);
    const response = await fetch(cloudUrl);
    if (!response.ok) {
      throw new Error(`the cloud answered HTTP ${response.status}`);
    }
    const bytes = new Uint8Array(await response.arrayBuffer());
    view = createCloudView(stage, { bytes, lens: 0 });

    const settings = defaultSettings();
    view.setDepth(settings.splatDepth);
    view.setSize(settings.splatEps2d);
    view.setBlur(settings.splatBlur);
    view.setResolution(settings.splatScale);
    view.setMotion(settings.motion / 100);
    view.setZoom(settings.zoom / 100);
    view.setAdjust(settings.brightness, settings.shadow);
    view.setOrigin(settings.originX, settings.originY);
    view.setPan(settings.panX, settings.panY);
    view.setPanZ(settings.panZ);
    pose(0);

    // Let the first frame draw before the page says it is ready. The capture
    // harness waits for this, so it never records a half-uploaded cloud.
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });

    loading.hidden = true;
    window.__basreliefDemo = {
      ready: true,
      period: PERIOD_MS,
      get time() {
        return phase;
      },
      pause() {
        paused = true;
        lastFrame = 0;
      },
      resume() {
        paused = false;
        lastFrame = 0;
      },
      seek(ms: number) {
        phase = ((ms % PERIOD_MS) + PERIOD_MS) % PERIOD_MS;
        pose(phase);
      },
    };

    requestAnimationFrame(tick);
  } catch (error) {
    loading.hidden = true;
    errorBox.hidden = false;
    errorBox.textContent =
      error instanceof Error ? `The cloud could not be drawn: ${error.message}` : 'The cloud could not be drawn.';
    throw error;
  }
}

void main();
