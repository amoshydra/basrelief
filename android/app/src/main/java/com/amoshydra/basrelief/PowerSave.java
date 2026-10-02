package com.amoshydra.basrelief;

import android.content.Context;
import android.os.PowerManager;

/**
 * Whether the OS is asking us to conserve power.
 *
 * <p>Used to drop the sensor rate: a live wallpaper that renders at the full
 * gyro rate forever is the difference between a phone that lasts and one that
 * gets hot. Read defensively because {@link PowerManager} is a system service
 * and can be null in odd states.
 *
 * <p><b>The user's switch lives here rather than at the two call sites.</b> Both
 * hosts — the studio and the wallpaper engine — consult this, and consulting the
 * pref separately at each would be two places to forget. This is the one read
 * path, so the switch cannot work in one surface and not the other, which is the
 * same reason there is only one selection pointer in the whole app.
 */
final class PowerSave {

    private PowerSave() {
    }

    static boolean isOn(Context context) {
        // Checked first, and separately from the system service: a user who has
        // turned this off should not even be asked, and the pref read must not be
        // inside the try that guards a system call.
        if (!Settings.followBatterySaver(Settings.prefs(context))) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }
}
