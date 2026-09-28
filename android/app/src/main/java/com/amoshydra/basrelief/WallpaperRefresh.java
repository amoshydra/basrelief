package com.amoshydra.basrelief;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Nudges the system into re-binding this app's live wallpaper.
 *
 * <p><b>Why this is needed at all.</b> The home screen does not necessarily show
 * the live engine. The system snapshots the wallpaper surface shortly after it
 * appears and can keep serving that snapshot, so a selection change can reach the
 * engine correctly " + "-" + " it re-reads the index, reloads the photo, applies the right
 * framing " + "-" + " and the picture on the home screen is unchanged. There is no way to
 * tell those apart by looking, and "press Set as wallpaper again" fixed it every
 * time, which is the clue: that button tells the engine nothing. It <i>re-binds
 * the component</i>, and the re-bind is what refreshes the snapshot.
 *
 * <p>So the app has to do the re-bind itself, which needs
 * {@code android.permission.SET_WALLPAPER}. This app had only INTERNET, so it
 * could not, and the only thing that worked was the system picker " + "-" + " a UI
 * interruption the user has to confirm, for something that should just work.
 * Every engine-side fix in this area is still correct and still needed; this is
 * the piece that makes them visible on the home screen.
 *
 * <p>Deliberately silent and deliberately conditional. It acts only when this
 * app's live wallpaper is <i>already</i> the active one, because activating a
 * wallpaper behind the user's back is not ours to do " + "-" + " that is what the Set as
 * wallpaper button and its system picker are for.
 */
final class WallpaperRefresh {

    private WallpaperRefresh() {
    }

    private static final String TAG = "Basrelief";

    /**
     * Re-bind if, and only if, our live wallpaper is already the active one.
     *
     * <p><b>This is a hidden API, reached by reflection, and that is not a
     * shortcut -- it is the only lever there is.</b> The public
     * {@code WallpaperManager} offers setBitmap, setResource and setStream, all of
     * which replace a live wallpaper with a static one, plus the picker intent
     * the Set as wallpaper button uses, which is a UI interruption. There is no
     * public way for an app to ask the system to re-bind its own live wallpaper.
     *
     * <p>So this is best-effort and it says so. Android 9 and later restrict
     * access to non-SDK interfaces, so the call can be refused on some builds;
     * {@code setWallpaperComponent} is not on the greylist, which makes failure
     * likely rather than merely possible. The consequence of failure is logged
     * and is nothing else " + "-" + " the app behaves exactly as it did before, and the
     * user can still press Set as wallpaper. A reflection call that throws
     * unchecked would be a crash on a user's home screen, so every failure mode
     * is caught and reported rather than propagated.
     *
     * @return true if a re-bind was requested
     */
    static boolean ifOursIsActive(Context context) {
        try {
            return rebind(context);
        } finally {
            // Whether or not it worked, the studio shows the result, so this is
            // never another silent no-op.
            android.content.SharedPreferences sp = context.getSharedPreferences(
                    AppState.PREFS, Context.MODE_PRIVATE);
            sp.edit().putString("wallpaper_rebind", sp.getString("wallpaper_rebind", "not tried"))
                    .commit();
        }
    }

    private static boolean rebind(Context context) {
        WallpaperManager wm = WallpaperManager.getInstance(context);
        WallpaperInfo info;
        try {
            info = wm.getWallpaperInfo();
        } catch (Exception e) {
            // A security exception means the platform refused the query. Treat it
            // as "not ours" rather than activating anything.
            return false;
        }
        if (info == null) return false; // a static image, or nothing set

        ComponentName ours = component(context);
        if (!isOurs(info, ours)) return false;

        try {
            Method m = WallpaperManager.class.getMethod("setWallpaperComponent",
                    ComponentName.class);
            m.invoke(wm, ours);
            Log.i(TAG, "wallpaper: re-bound so the system re-snapshots the surface");
            note(context, "rebind: OK");
            return true;
        } catch (Throwable t) {
            // Throwable, not Exception: a hidden-API refusal surfaces as
            // NoSuchMethodException, an InvocationTargetException wrapping a
            // SecurityException, or on some builds an Error. All of them mean
            // "could not", and none of them should reach the user as a crash.
            Log.w(TAG, "wallpaper: re-bind unavailable on this device; "
                    + "Set as wallpaper still works", t);
            note(context, "rebind: REFUSED (" + t.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /**
     * Whether the active wallpaper is this app's live wallpaper.
     *
     * <p>The manifest names the service relatively ({@code .DepthWallpaperService}),
     * so the system may hand back either that or the flattened form. Comparing
     * ComponentName objects directly would miss one of them and quietly skip the
     * refresh, which is the same silent no-op this class exists to fix.
     */
    private static boolean isOurs(WallpaperInfo info, ComponentName ours) {
        ComponentName active = info.getComponent();
        if (active == null) return false;
        if (ours.equals(active)) return true;
        String cls = active.getClassName();
        return ours.getPackageName().equals(active.getPackageName())
                && (cls.equals(ours.getClassName()) || cls.endsWith("." + ours.getClassName()));
    }

    private static void note(Context context, String what) {
        android.util.Log.i(TAG, what);
        context.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                .edit().putString("wallpaper_rebind", what).commit();
    }

    /** The component users select, for the Set as wallpaper button. */
    static ComponentName component(Context context) {
        return new ComponentName(context, DepthWallpaperService.class);
    }
}
