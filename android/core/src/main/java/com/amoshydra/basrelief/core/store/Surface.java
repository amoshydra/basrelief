package com.amoshydra.basrelief.core.store;

/**
 * The two places a live wallpaper is shown.
 *
 * <p>Not a preference and not a mode: a live wallpaper is bound to a screen, and
 * the same photo on the lock screen is a different render, because it is seen
 * through a clock and a darker scrim. What works on the home screen is often too
 * dark to read beside one, which is the whole reason these exist.
 *
 * <p>Deliberately two, and no more. A second "surface" would need a third screen
 * to have a meaning on, and the moment there are three the question of what
 * inherits from what stops having an obvious answer.
 *
 * <p><b>Platform.</b> Distinguishing them at all is version-dependent: an engine
 * can only learn which one it is drawing from API 34, and the platform only runs
 * a second live engine for the lock screen on 35+. Below that the lock screen
 * cannot carry its own live settings, and callers are expected to fall back
 * rather than to pretend. See {@code .design/03-android-capabilities.md}.
 *
 * <p>Which of those two is in force is not decided here, and is not decided by
 * the engine either: {@link SurfaceResolver} answers it from the raw SDK level
 * and the raw wallpaper flags, which is the whole reason it is testable.
 */
public enum Surface {
    /** The home screen. The wallpaper. Always available. */
    HOME("home"),
    /**
     * The lock screen. Per-surface settings need API 35+ to exist, and
     * {@link SurfaceResolver#supportsPerSurfaceLock(int)} is the question to ask
     * rather than the version to compare by hand at each call site.
     */
    LOCK("lock");

    /** The key this surface is stored under inside an item's {@code surfaces}. */
    public final String key;

    Surface(String key) {
        this.key = key;
    }

    /** The surface for a stored key, or {@code fallback} if it is not one. */
    public static Surface fromKey(String key, Surface fallback) {
        for (Surface s : values()) if (s.key.equals(key)) return s;
        return fallback;
    }
}
