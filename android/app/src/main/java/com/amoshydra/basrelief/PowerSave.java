package com.amoshydra.basrelief;

import android.content.Context;
import android.os.PowerManager;

/**
 * Whether the OS is asking us to conserve power.
 *
 * <p>Used to drop the frame rate: a live wallpaper that renders at the full
 * gyro rate forever is the difference between a phone that lasts and one that
 * gets hot. Read defensively because {@link PowerManager} is a system service
 * and can be null in odd states.
 */
final class PowerSave {

    private PowerSave() {
    }

    static boolean isOn(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }
}
