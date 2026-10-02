package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app-level settings, and the one place each one is read.
 *
 * <p>These are the switches on the Settings screen that describe the <b>device and
 * the app</b> rather than a photograph. Per-photo values — Depth, Zoom, Motion,
 * framing, the adjustments — are not here: they live on the {@code LibraryItem} and
 * are read through {@code settings(Surface)}.
 *
 * <p>This class exists because of a specific failure. All three switches were drawn
 * on the Settings screen, written to prefs, and read by nothing: the behaviours they
 * describe were implemented unconditionally. A control that displays a value the app
 * ignores is worse than no control, because the user believes it. So each switch here
 * has exactly one reader, and the reader is the code that would have to change.
 *
 * <p>The keys and defaults are declared once. A key string duplicated between the
 * screen that writes it and the code that reads it is a silent no-op waiting to
 * happen — the switch flips, the behaviour never changes, and both halves compile.
 *
 * <p>Every read is defensive. These are read on the sensor thread and from a
 * {@link android.content.SharedPreferences} that a restored backup may have written
 * with the wrong type; a {@code ClassCastException} here would take the sensor
 * callback down rather than degrade one setting.
 */
final class Settings {

    private Settings() {
    }

    /** Follow the OS battery saver: drop to the lower sensor rate when it is on. */
    static final String KEY_BATTERY_SAVER_FOLLOW = "battery_saver_follow";

    /** Re-read the neutral pose every time the sensor wakes from idle sleep. */
    static final String KEY_CALIBRATE_ON_WAKE = "gyro_calibrate_on_wake";

    /** Park the rotation vector and fall back to the accelerometer when still. */
    static final String KEY_SLEEP_WHEN_STILL = "gyro_sleep_when_still";

    /**
     * Every one of these defaults to on.
     *
     * <p>Deliberately: each describes behaviour the app had before the switch
     * existed. A user upgrading must not have their wallpaper start behaving
     * differently because a new key reads as false when absent.
     */
    private static final boolean DEFAULT = true;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
    }

    private static boolean flag(SharedPreferences prefs, String key) {
        if (prefs == null) return DEFAULT;
        try {
            return prefs.getBoolean(key, DEFAULT);
        } catch (Exception e) {
            // Wrong type for the key — a restored backup, or an older build that wrote
            // a String under this name. Degrade to the behaviour the app already had.
            return DEFAULT;
        }
    }

    /**
     * Whether the OS battery saver should be allowed to reduce the sensor rate.
     *
     * <p>Read by {@link PowerSave}, which is the single path both hosts go through —
     * see the note there about why it is not consulted at each call site instead.
     */
    static boolean followBatterySaver(SharedPreferences prefs) {
        return flag(prefs, KEY_BATTERY_SAVER_FOLLOW);
    }

    /**
     * Whether waking from idle sleep should re-capture the neutral pose.
     *
     * <p>Turning this off leaves the neutral captured at the last wake. The view
     * will then be slightly off-centre if the phone was picked up in a new
     * posture, which is the trade: one fewer re-centre.
     */
    static boolean calibrateOnWake(SharedPreferences prefs) {
        return flag(prefs, KEY_CALIBRATE_ON_WAKE);
    }

    /**
     * Whether stillness should park the rotation vector.
     *
     * <p>This is the app's real battery win — the rotation vector is by far the most
     * expensive sensor involved, and once the phone is put down there is nothing to
     * render. Turning it off keeps full-rate tracking running forever.
     */
    static boolean sleepWhenStill(SharedPreferences prefs) {
        return flag(prefs, KEY_SLEEP_WHEN_STILL);
    }
}