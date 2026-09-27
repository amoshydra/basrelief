package com.amoshydra.basrelief;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Fused rotation-vector tilt, calibrated to the neutral pose captured at
 * start. Exposes normalized x/y in [-1, 1] (x = left/right, y = front/back).
 *
 * Idle power saving: if the device stays still (tilt within ~1° of a slowly
 * tracking baseline) for IDLE_SLEEP_MS, the rotation-vector listener is
 * unregistered — the caller only renders on onTiltChanged, so rendering stops
 * with it. A low-power accelerometer takes over, and a shake (acceleration
 * deviating from gravity by more than SHAKE_MPS2) or a fresh start() wakes it,
 * recalibrating to the current pose.
 */
public final class TiltController implements SensorEventListener {

    public interface Listener {
        void onTiltChanged();
    }

    private static final float RANGE_DEG = 25f; // saturate beyond ±25° from neutral
    private static final int SKIP_FIRST = 1; // drop the cached reading delivered on register
    private static final int SETTLE_SAMPLES = 8; // average these for a stable neutral
    private static final long IDLE_SLEEP_MS = 10000; // still for this long -> sleep
    private static final float IDLE_EPS = 0.04f; // ~1° of tilt counts as activity
    private static final float SHAKE_MPS2 = 1.2f; // accel deviation from gravity to wake
    private static final int ACCEL_DELAY = SensorManager.SENSOR_DELAY_NORMAL;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometer;
    private final Listener listener;

    private boolean calibrated;
    private int skip;
    private int samples;
    // Tilt is measured as the RELATIVE rotation since neutral (R_neutral^T * R),
    // never absolute Euler angles — so it stays well-conditioned even when the
    // phone is near-horizontal (screen-down, e.g. lying in bed facing up),
    // where absolute pitch/roll hit their gimbal-lock singularity.
    private final float[] neutralRot = new float[9];
    private final float[] rot = new float[9];
    private final float[] rel = new float[9];
    private volatile float x, y;

    private boolean asleep;
    private long lastMoveNanos;
    /** True while a sensor listener is actually registered. */
    private boolean registered;
    private float idleBaseX, idleBaseY;

    public TiltController(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public boolean isSupported() {
        return rotationSensor != null;
    }

    public boolean isAsleep() {
        return asleep;
    }

    /**
     * (Re)start listening with fresh calibration for this viewing. Clears any
     * sleep-state accelerometer first.
     */
    /**
     * Idempotent. Calling this while already running does nothing.
     *
     * <p>It used to unregister, re-calibrate and re-register unconditionally,
     * and recalibrate() re-captures the neutral -- so every redundant call reset
     * the view to centre and tore down the sensor registration. With a caller
     * that polls (a battery broadcast, say) that produced a visible stutter and
     * a view that kept snapping back on its own.
     */
    public void start() {
        if (rotationSensor == null) return;
        if (registered && !asleep) return; // already running
        sensorManager.unregisterListener(this);
        asleep = false;
        recalibrate();
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
    }

    public void stop() {
        sensorManager.unregisterListener(this); // both rotation-vector and accelerometer
        asleep = false;
        registered = false;
    }

    /** Re-arm calibration without re-registering. */
    public void recalibrate() {
        startNeutralCapture();
        idleBaseX = 0f;
        idleBaseY = 0f;
        lastMoveNanos = System.nanoTime();
    }

    /** Reset only the calibration/settle state (used for auto-recal). */
    private void startNeutralCapture() {
        calibrated = false;
        skip = SKIP_FIRST;
        samples = 0;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // while the tilt sensor sleeps, wake on a shake (accel spike)
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];
            float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (Math.abs(mag - SensorManager.GRAVITY_EARTH) > SHAKE_MPS2) {
                wakeUp();
            }
            return;
        }

        if (!calibrated) {
            if (skip > 0) {
                skip--;
                return;
            }
            samples++;
            if (samples < SETTLE_SAMPLES) return;
            SensorManager.getRotationMatrixFromVector(neutralRot, event.values);
            calibrated = true;
            x = 0f;
            y = 0f;
            listener.onTiltChanged();
            return;
        }

        // relative rotation since neutral: rel = neutralRot^T * rot
        SensorManager.getRotationMatrixFromVector(rot, event.values);
        mulTranspose(neutralRot, rot, rel);
        float[] orientation = new float[3];
        SensorManager.getOrientation(rel, orientation);
        float pitch = orientation[1];
        float roll = orientation[2];

        float range = (float) Math.toRadians(RANGE_DEG);
        x = clamp(roll / range);
        // negated so the pitch direction matches the intended camera model:
        // tilting the phone up (top away, face to sky) reveals the TOP of the
        // scene (the subject's head), tilting down reveals the bottom
        y = clamp(-pitch / range);

        // There is deliberately NO periodic re-calibration here. There used to be
        // a 5-second heartbeat that re-captured the neutral and hard-zeroed the
        // tilt whenever the view sat within 4 degrees of centre -- so holding the
        // phone at a deliberate 3 degrees got yanked back to centre every five
        // seconds. A timer cannot tell "the user is holding an angle" from "the
        // sensor has drifted", and the view is the one thing a user is watching.
        //
        // Neutral is now re-captured only on events that genuinely warrant it:
        // waking from idle-sleep (the phone demonstrably moved), and the explicit
        // Calibrate action. The cost is that slow sensor drift is no longer
        // corrected while the phone lies untouched -- which only really matters
        // for the wallpaper, where nobody is watching the framing anyway.

        // idle detection: a slowly-tracking baseline separates still-holding
        // (sensor jitter, ~<1°) from real motion; still for IDLE_SLEEP_MS -> sleep
        long now = System.nanoTime();
        idleBaseX += (x - idleBaseX) * 0.01f;
        idleBaseY += (y - idleBaseY) * 0.01f;
        float move = (float) Math.hypot(x - idleBaseX, y - idleBaseY);
        if (move > IDLE_EPS) {
            lastMoveNanos = System.nanoTime();
        } else if (!asleep && now - lastMoveNanos > IDLE_SLEEP_MS * 1_000_000L) {
            goToSleep();
            return;
        }

        listener.onTiltChanged();
    }

    private void goToSleep() {
        asleep = true;
        registered = false;
        sensorManager.unregisterListener(this);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, ACCEL_DELAY);
        }
    }

    private void wakeUp() {
        asleep = false;
        sensorManager.unregisterListener(this);
        if (rotationSensor != null) {
            recalibrate(); // neutral matches the (shaken) current pose
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;
        }
        listener.onTiltChanged(); // trigger a fresh render
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /** out = a^T * b for row-major 3x3 matrices (a, b, out are 9-element). */
    private static void mulTranspose(float[] a, float[] b, float[] out) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float s = 0f;
                for (int k = 0; k < 3; k++) {
                    s += a[k * 3 + i] * b[k * 3 + j];
                }
                out[i * 3 + j] = s;
            }
        }
    }
}
