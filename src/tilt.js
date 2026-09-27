/**
 * Tilt input: normalizes gyroscope / mouse / touch into a target in [-1, 1]
 * and exposes a per-frame smoothed value.
 *
 * getTarget() → { x, y }  raw normalized target (x: left/right, y: fwd/back)
 * update()    → { x, y }  smoothed value, call once per animation frame
 */

const TILT_RANGE_DEG = 25; // tilt beyond ±25° from neutral saturates
const SMOOTHING = 0.08; // lerp factor per frame

export function createTiltInput({ onStatus } = {}) {
  const target = { x: 0, y: 0 };
  const smooth = { x: 0, y: 0 };
  let neutral = null; // { beta, gamma } captured at calibration
  let gyroActive = false;

  const status = (msg) => onStatus?.(msg);

  function onOrientation(e) {
    if (e.beta == null || e.gamma == null) return;
    if (!neutral) calibrate(e);

    // Screen rotation changes how beta/gamma map to the view.
    const angle = screen.orientation?.angle ?? window.orientation ?? 0;
    let dx, dy;
    const dBeta = e.beta - neutral.beta;
    const dGamma = e.gamma - neutral.gamma;
    switch (angle) {
      case 90:  dx = dBeta;  dy = dGamma;  break;
      case -90:
      case 270: dx = -dBeta; dy = -dGamma; break;
      case 180: dx = -dGamma; dy = -dBeta; break;
      default:  dx = dGamma; dy = dBeta;   break; // portrait
    }

    target.x = clamp(dx / TILT_RANGE_DEG);
    target.y = clamp(dy / TILT_RANGE_DEG);
  }

  function calibrate(e) {
    neutral = { beta: e.beta ?? 0, gamma: e.gamma ?? 0 };
  }

  function clamp(v) {
    return Math.max(-1, Math.min(1, v));
  }

  return {
    /**
     * Enable gyroscope. Must be called from a user gesture on iOS.
     * Returns true if gyro was (or already is) active.
     */
    async enableGyro() {
      if (typeof DeviceOrientationEvent === 'undefined') {
        status('gyroscope not supported — using touch/mouse');
        return false;
      }
      try {
        // iOS 13+ requires explicit permission from a user gesture.
        if (typeof DeviceOrientationEvent.requestPermission === 'function') {
          const res = await DeviceOrientationEvent.requestPermission();
          if (res !== 'granted') {
            status('motion permission denied — using touch/mouse');
            return false;
          }
        }
        window.addEventListener('deviceorientation', onOrientation);
        gyroActive = true;
        status('input: gyroscope (tilt your phone)');
        return true;
      } catch (err) {
        status('gyro unavailable — using touch/mouse');
        return false;
      }
    },

    /** Recapture the current phone pose as "neutral". */
    calibrate() {
      neutral = null; // next gyro event becomes the new neutral
    },

    /** Pointer fallback (desktop mouse, or touch drag). shouldIgnore() suppresses tilt during pan gestures. */
    bindPointer(el, shouldIgnore) {
      const setFromPoint = (cx, cy) => {
        if (gyroActive || shouldIgnore?.()) return;
        target.x = clamp((cx / window.innerWidth) * 2 - 1);
        target.y = clamp((cy / window.innerHeight) * 2 - 1);
      };
      el.addEventListener('pointermove', (e) => setFromPoint(e.clientX, e.clientY));
      el.addEventListener('pointerdown', (e) => setFromPoint(e.clientX, e.clientY));
    },

    /** Call once per frame; returns smoothed tilt in [-1, 1]. */
    update() {
      smooth.x += (target.x - smooth.x) * SMOOTHING;
      smooth.y += (target.y - smooth.y) * SMOOTHING;
      return smooth;
    },
  };
}
