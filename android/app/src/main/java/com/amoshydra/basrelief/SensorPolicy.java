package com.amoshydra.basrelief;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Decides when the gyro may run. Paused when the wallpaper/preview is not
 * visible (screen off, another app on top) or when the battery is low —
 * unless the device is charging, which lifts the low-battery pause.
 * Callers re-check on every visibility and battery change.
 */
public final class SensorPolicy {

    private static final int LOW_BATTERY_PERCENT = 20;

    private final Context context;
    private final Runnable onChange;

    private boolean visible = true;
    private boolean batteryLow = false;
    private boolean charging = false;
    private boolean batteryGate = true; // false = bypass the low-battery pause
    private int batteryPercent = 100;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryPercent = level >= 0 ? Math.round(100f * level / scale) : 100;
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            // low battery only pauses the gyro when NOT charging
            batteryLow = batteryPercent <= LOW_BATTERY_PERCENT && !charging;
            onChange.run();
        }
    };

    public SensorPolicy(Context context, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
    }

    /** Registers the battery listener; sticky BATTERY_CHANGED reports immediately. */
    public void start() {
        context.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    public void stop() {
        context.unregisterReceiver(batteryReceiver);
    }

    public void setVisible(boolean v) {
        visible = v;
        onChange.run();
    }

    /** When false, the low-battery pause is ignored (used by the studio). */
    public void setBatteryGate(boolean enabled) {
        batteryGate = enabled;
        onChange.run();
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isBatteryLow() {
        return batteryLow;
    }

    public boolean isCharging() {
        return charging;
    }

    public int getBatteryPercent() {
        return batteryPercent;
    }

    /** Gyro runs while visible; the low-battery pause applies only if the gate is on. */
    public boolean shouldRunSensor() {
        return visible && (!batteryGate || !batteryLow);
    }
}
