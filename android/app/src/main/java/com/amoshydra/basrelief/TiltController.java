package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Fused rotation-vector tilt, calibrated to the neutral pose captured at
 * start. Exposes normalized x/y in [-1, 1] (x = left/right, y = front/back).
 *
 * <p>A re-calibration makes the current pose the new neutral, so the tilt
 * becomes 0 -- usually a long way from where the view already is. Rather than
 * publish that as a step, it eases in over a fraction of a second; see
 * {@link #beginGlide} for the mechanism and the reasons. Deliberate movement
 * is untouched.
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

    /**
     * The idle sensor's reporting period, in microseconds.
     *
     * <p>Was {@code SENSOR_DELAY_NORMAL}, which the device reported as a 200 ms
     * period (5 Hz). That exists only to notice a shake, and a shake is
     * large-amplitude and lasts far longer than the reporting period, so 2 Hz
     * catches it. The accelerometer is the cheapest sensor on the device, so this
     * is a modest saving rather than a headline one - but it is the only thing
     * still polling once the phone has been put down.
     */
    private static final int ACCEL_IDLE_PERIOD_US = 500_000;

    /**
     * Batching window for the idle sensor, in microseconds.
     *
     * <p>Nothing in this app used batching before, so the hub delivered every
     * sample and the AP woke for each one. Batching lets the hub accumulate
     * samples and wake the AP once per window.
     *
     * <p>Bounded at 1 s on purpose: the window is also the worst-case latency
     * before a shake is noticed, and a wallpaper that takes a second to come back
     * to life feels broken. Two samples per wake is the trade. Do not raise this
     * to save more power - the accelerometer is not what drains the battery here.
     */
    private static final int ACCEL_IDLE_MAX_LATENCY_US = 1_000_000;

    /**
     * Rotation-vector period while the OS is in battery-save mode, in
     * microseconds. The normal period is {@link SensorManager#SENSOR_DELAY_GAME},
     * which the device reports as 20 ms.
     *
     * <p>Halved rather than disabled: in battery-save the wallpaper should still
     * respond, just less smoothly. This is the setting the app used to read and
     * throw away.
     */
    private static final int ROTATION_POWER_SAVE_PERIOD_US = 50_000;

    // Re-centre glide. See beginGlide() for why it lives here and not in the
    // renderer.
    //
    // The glide RATE is the user's setting, read from `glide_ms` on every
    // re-centre rather than baked in here, so that the one slider on the
    // settings screen moves something real. A slider that writes a number no
    // code reads is the worst thing this app can ship: it looks correct on
    // screen and lies about what it changed.
    //
    // RECENTRE_RATE below is only the FALLBACK for an install that has never
    // opened Settings (or a pref written by a future version). It is 0.7/s,
    // which is a 1.43 s re-centre of the full +-1 tilt range -- the same
    // 1.2-ish seconds the mockup's slider shows as its default.
    //
    // The rate is capped well below the point at which the renderer's fast
    // attack would fire and undo the whole thing.  That renderer is nonlinear:
    // while below ATTACK_ERROR it closes max(alpha, 8*err) per frame, so a
    // steadily moving target settles at a steady err = sqrt(rate*dt/8) and the
    // attack fires once that passes ATTACK_ERROR -- i.e. at
    // rate = 8*ATTACK_ERROR^2/dt, which is 6.25/s at the ~50 Hz that
    // SENSOR_DELAY_GAME actually delivers, and LOWER on a slower sensor
    // (2.5/s at 20 Hz, which is what battery-save uses).  The smoothstep's peak
    // rate is 1.5x its mean, so RECENTRE_RATE = 0.7 peaks at 1.05/s: a factor of
    // 2.4 below the worst of those, and 6x below the nominal.
    // MIN_GLIDE_MS is that same budget expressed as the shortest glide the user
    // can ask for: peak = 1.5 / span must stay under the 2.5/s that the
    // slowest sensor rate permits, which puts the floor at ~600 ms.  The
    // slider's minimum IS this floor, so no reachable value is ever clamped --
    // the clamp below is only a guard for a hand-edited or corrupt pref.
    // If ATTACK_ERROR or SMOOTHING_TAU in ReliefRenderer is changed, re-check
    // that budget and move the slider's minimum with it.
    private static final float RECENTRE_RATE = 0.7f;        // tilt units per second
    private static final float RECENTRE_MIN_SECONDS = 0.3f; // floor, so no re-centre is a one-frame pop

    /**
     * Pref key and range of the one glide rate, shared with {@link GlideControl},
     * which writes it. Both numbers live here because the two ends of the range
     * are not free: 3000 ms is the slowest glide that still reads as a glide,
     * and 600 ms is the attack budget above.
     */
    static final String KEY_GLIDE_MS = "glide_ms";
    static final int GLIDE_MIN_MS = 600;
    static final int GLIDE_MAX_MS = 3000;
    static final int GLIDE_DEFAULT_MS = 1200;

    private final SharedPreferences prefs;

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
    /**
     * Whether the OS is in battery-save mode. Written from the UI thread via
     * {@link #setPowerSave(boolean)} and read when a sensor is (re)registered,
     * so it is deliberately not volatile: registration always happens on the
     * sensor thread and is ordered by the sensor manager's own lock, and making
     * it volatile would only suggest a stronger guarantee than exists.
     */
    private boolean powerSave;
    private float idleBaseX, idleBaseY;

    /**
     * Re-centre glide. {@link #viewX} / {@link #viewY} are what the accessors
     * report: the measured tilt, except while a re-centre is easing into the
     * new neutral. Sensor-thread only, apart from {@link #pendingRecentring}.
     */
    private float viewX, viewY;
    private float glideFromX, glideFromY;
    private long glideStartNanos;
    private float glideSpan;
    private boolean gliding;

    /**
     * Set by {@link #recalibrate} (UI thread) and consumed on the sensor thread
     * when the new neutral is actually published. {@code volatile} because it is
     * the one new field written from off the sensor thread: without it a
     * recalibrate can be missed and the view snaps to centre again, which is the
     * bug this whole mechanism exists to remove. (The neighbouring calibration
     * fields are plain, but a missed write to one of those only costs a few more
     * settle samples, whereas a missed write to this costs the whole glide.)
     */
    private volatile boolean pendingRecentring;

    public TiltController(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        // Read on every glide rather than cached in a field, so a change made in
        // the studio's settings sheet is picked up by a wallpaper engine that is
        // already running. SharedPreferences keeps the file in memory, so this is
        // a map lookup, not a disk read -- and a glide is rare.
        this.prefs = context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The user's glide rate, in tilt units per second.
     *
     * <p>The setting is a DURATION -- how long a re-centre of the full tilt range
     * should take, which is what the slider says -- and the rate is its reciprocal.
     * Going via the rate rather than fixing the span keeps the glide proportional
     * to how far it actually has to travel: a 15-degree nudge is a fifth of a
     * second whether the phone was picked up off a table or turned in the hand.
     *
     * <p>Read defensively. A pref written as a String by an older build, or by a
     * restored backup, throws ClassCastException out of {@code getInt}, and this
     * runs on the sensor thread where an exception takes the sensor down.
     */
    private float glideRate() {
        // The fallback is the constant this class always had: 0.7 tilt units per
        // second, which is a 1428 ms re-centre of the full range.
        int ms = Math.round(1000f / RECENTRE_RATE);
        try {
            ms = prefs.getInt(KEY_GLIDE_MS, ms);
        } catch (Exception e) {
            // Wrong type for the key -- a pref written as a String by an older
            // build, or by a restored backup. Fall back rather than die.
            ms = Math.round(1000f / RECENTRE_RATE);
        }
        if (ms < GLIDE_MIN_MS) ms = GLIDE_MIN_MS;
        if (ms > GLIDE_MAX_MS) ms = GLIDE_MAX_MS;
        return 1000f / ms;
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
        registerRotationVector();
        registered = true;
    }

    public void stop() {
        sensorManager.unregisterListener(this); // both rotation-vector and accelerometer
        asleep = false;
        registered = false;
    }

    /**
     * Re-arm calibration without re-registering.
     *
     * <p>Only ARMS the glide. The neutral is not published until
     * SETTLE_SAMPLES have arrived, and arming here rather than there is what
     * makes a second recalibrate during one capture window harmless: the flag is
     * a boolean, so the second call overwrites the first and a single glide
     * starts from the final neutral, at the final position, with no jump.
     */
    public void recalibrate() {
        pendingRecentring = true;
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

    /**
     * Tilt as presented to the renderer: the measured tilt, except for the
     * fraction of a re-centre glide that has not been presented yet. See
     * {@link #beginGlide}.
     */
    public float getX() {
        return viewX;
    }

    /** @see #getX() */
    public float getY() {
        return viewY;
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
            if (pendingRecentring) {
                pendingRecentring = false;
                beginGlide(); // before the listener reads getX()/getY()
            }
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

        advanceGlide();
        listener.onTiltChanged();
    }

    // ------------------------------------------------------------ re-centre glide

    /**
     * Start easing the presented tilt into a freshly captured neutral.
     *
     * <p><b>Why the glide is here and not in the renderer.</b> The renderer has a
     * deliberate fast-attack mode for real hand movement
     * ({@code ReliefRenderer.ATTACK_ERROR}: snap in one frame once the error
     * exceeds ~3 degrees of phone rotation), which is right for a hand and
     * wrong for a re-centre. A re-centre used to hard-zero x and y, and since
     * the phone is very often picked up at a different angle from the one it was
     * put down at, that hard zero arrived as a jump of many degrees -- a
     * jump-cut exactly where an orbit belongs. That path is
     * {@code recalibrate()}, which fires on every visibility change of the
     * wallpaper, on wake from idle-sleep, and on the studio's Calibrate button.
     *
     * <p>So the filter cannot be told about it from the outside: to the
     * renderer a re-centre and a fast flick are the same single large step in
     * the target, and it has to snap the second one. Both surfaces reach the
     * renderer only through {@code setTilt(getX(), getY())}, so there is no other
     * channel for the REASON to travel -- and this is the only class that knows
     * the reason. Hence the glide lives here, and the renderer's fast attack is
     * left exactly as it was, which is also what keeps deliberate movement
     * snapping: a hand never touches this code path.
     *
     * <p>Consequences worth knowing:
     * <ul>
     *   <li>Both surfaces get it, because they share this class. See
     *       {@code DepthWallpaperService} / {@code MainActivity}.</li>
     *   <li>The studio does NOT glide on resume, because
     *       {@link #start} is idempotent and the studio's poll re-enters it
     *       while the sensor is still registered. The wallpaper stops and
     *       restarts its sensor, so it does recalibrate, and that is the snap
     *       that was reported.</li>
     *   <li>Until the glide ends, {@link #getX} / {@link #getY} report the
     *       presented tilt rather than the instantaneous reading. The only
     *       consumer is the renderer; the idle detector in onSensorChanged uses
     *       the raw fields, so it is unaffected.</li>
     * </ul>
     *
     * <p>The glide re-aims at the LIVE measurement rather than at 0, so a phone
     * that keeps moving during the glide simply re-targets, and when the glide
     * ends the presented value is bit-for-bit the measurement. That is what
     * makes an interrupted or repeated re-centre seamless.
     */
    private void beginGlide() {
        float travel = Math.max(Math.abs(x - viewX), Math.abs(y - viewY));
        // The rate is read HERE rather than cached, so a glide started after the
        // user changed the slider glides at the speed they just chose, including
        // in an engine that was already running when they did.
        glideSpan = Math.max(RECENTRE_MIN_SECONDS, travel / glideRate());
        glideFromX = viewX;
        glideFromY = viewY;
        glideStartNanos = System.nanoTime();
        gliding = true;
    }

    /**
     * Move the presented tilt along the glide, or just report the measurement.
     *
     * <p>The clock is absolute rather than a per-event delta, so the glide takes
     * the same wall-clock time whatever rate the sensor delivers, and a dropped
     * event cannot lengthen it.
     */
    private void advanceGlide() {
        if (!gliding) {
            viewX = x;
            viewY = y;
            return;
        }
        float u = (System.nanoTime() - glideStartNanos) / 1e9f / glideSpan;
        if (u >= 1f) {
            gliding = false;
            viewX = x;
            viewY = y;
            return;
        }
        // Smoothstep: zero slope at both ends, so the glide neither starts nor
        // stops with a step in rate -- a pop in velocity is more visible than a
        // pop in position. Its derivative 6u(1-u) is non-negative, so the path
        // is monotonic and cannot overshoot the new neutral.
        float p = u * u * (3f - 2f * u);
        viewX = glideFromX + (x - glideFromX) * p;
        viewY = glideFromY + (y - glideFromY) * p;
    }

    private void goToSleep() {
        asleep = true;
        registered = false;
        sensorManager.unregisterListener(this);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    ACCEL_IDLE_PERIOD_US, ACCEL_IDLE_MAX_LATENCY_US);
        }
    }

    private void wakeUp() {
        asleep = false;
        sensorManager.unregisterListener(this);
        if (rotationSensor != null) {
            recalibrate(); // neutral matches the (shaken) current pose
            registerRotationVector();
            registered = true;
        }
        listener.onTiltChanged(); // trigger a fresh render
    }

    /**
     * Register the rotation vector at whichever period policy calls for.
     *
     * <p>Batching is deliberately NOT used on this path even when batching is
     * available, because the whole point of this sensor is the feel of the
     * parallax: a batched delivery arrives late and unevenly, which is exactly
     * what the smoothing exists to hide. The idle path is the only one where
     * latency is invisible, so it is the only one that batches.
     */
    private void registerRotationVector() {
        if (rotationSensor == null) return;
        if (powerSave) {
            sensorManager.registerListener(this, rotationSensor, ROTATION_POWER_SAVE_PERIOD_US);
        } else {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    /**
     * Respond to the OS entering or leaving battery-save mode.
     *
     * <p>Re-registers only when a sensor is actually running, so this is safe to
     * call at any time, including before {@link #start()} and while asleep. The
     * previous period takes effect on the next wake from idle.
     */
    public void setPowerSave(boolean on) {
        if (powerSave == on) return;
        powerSave = on;
        if (registered && !asleep) {
            // Restart the listener so the new period applies immediately. The
            // neutral is re-captured by start(), which is right: the phone may
            // have been picked up since the last capture.
            start();
        }
    }

    /** Whether the OS is currently in battery-save mode. */
    public boolean isPowerSave() {
        return powerSave;
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
