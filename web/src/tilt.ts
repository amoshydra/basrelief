/**
 * Tilt input from the device's orientation sensor.
 *
 * beta/gamma deltas from a neutral pose captured at calibration, mapped through
 * the screen rotation, clamped to ±1 and normalised against a ±25° range. The
 * browser exposes this only in a secure context, and iOS additionally requires an
 * explicit permission request from a user gesture — so `start()` is async and is
 * called from the viewer's own button, never on load.
 *
 * The vertical axis is negated after the screen-rotation mapping. Android's
 * `TiltController` uses `x = roll` and `y = -pitch` so tilting the phone up (top
 * away, face to the sky) reveals the top of the scene; the reference port's
 * `+beta` was vertically mirrored.
 *
 * `isActive()` only answers true once an event has actually arrived. A laptop that
 * registers the listener and never emits one must not suppress the pointer
 * fallback.
 */

const TILT_RANGE_DEG = 25;

export interface DeviceTilt {
  /** Ask for permission if the browser wants it, then listen. */
  start(): Promise<boolean>;
  stop(): void;
  /** Make the current pose neutral again (the next event is the new zero). */
  calibrate(): void;
  /** True only while listening AND at least one orientation event has arrived. */
  isActive(): boolean;
}

interface OrientationPermission {
  requestPermission?: () => Promise<'granted' | 'denied' | string>;
}

function clamp(value: number): number {
  return Math.max(-1, Math.min(1, value));
}

/**
 * Map a beta/gamma delta to the camera's tilt for one screen rotation.
 *
 * Pure so the sign conventions are testable without a phone. The vertical
 * component is negated to match `TiltController.y = -pitch`; this is the
 * difference from `reference/webgl-port/src/tilt.js`.
 */
export function tiltFromOrientation(
  angle: number,
  betaDelta: number,
  gammaDelta: number,
): { x: number; y: number } {
  let dx: number;
  let dy: number;
  switch (angle) {
    case 90:
      dx = betaDelta;
      dy = gammaDelta;
      break;
    case -90:
    case 270:
      dx = -betaDelta;
      dy = -gammaDelta;
      break;
    case 180:
      dx = -gammaDelta;
      dy = -betaDelta;
      break;
    default:
      dx = gammaDelta;
      dy = betaDelta;
      break;
  }
  return {
    x: clamp(dx / TILT_RANGE_DEG),
    y: clamp(-dy / TILT_RANGE_DEG),
  };
}

export function createDeviceTilt(onTilt: (x: number, y: number) => void): DeviceTilt {
  let listening = false;
  let received = false;
  let neutral: { beta: number; gamma: number } | null = null;

  function onOrientation(event: DeviceOrientationEvent): void {
    if (event.beta === null || event.gamma === null) return;
    if (neutral === null) {
      neutral = { beta: event.beta, gamma: event.gamma };
      return;
    }

    received = true;
    const mapped = tiltFromOrientation(
      screen.orientation?.angle ?? 0,
      event.beta - neutral.beta,
      event.gamma - neutral.gamma,
    );
    onTilt(mapped.x, mapped.y);
  }

  return {
    async start(): Promise<boolean> {
      if (listening) return true;
      if (typeof window.DeviceOrientationEvent === 'undefined') return false;

      const permission = window.DeviceOrientationEvent as unknown as OrientationPermission;
      if (typeof permission.requestPermission === 'function') {
        try {
          const answer = await permission.requestPermission();
          if (answer !== 'granted') return false;
        } catch {
          return false;
        }
      }

      neutral = null;
      received = false;
      window.addEventListener('deviceorientation', onOrientation);
      listening = true;
      return true;
    },

    stop(): void {
      if (!listening) return;
      window.removeEventListener('deviceorientation', onOrientation);
      listening = false;
      received = false;
      neutral = null;
      onTilt(0, 0);
    },

    calibrate(): void {
      neutral = null;
    },

    isActive(): boolean {
      return listening && received;
    },
  };
}
