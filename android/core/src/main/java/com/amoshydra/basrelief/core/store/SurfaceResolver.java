package com.amoshydra.basrelief.core.store;

/**
 * Which screen a live wallpaper engine is drawing, and what the lock screen can
 * possibly be on this device.
 *
 * <p><b>The raw platform facts go in, and the decision comes out.</b> :core has
 * no Android in it, so {@code Engine.getWallpaperFlags()} and
 * {@code Build.VERSION.SDK_INT} cannot be read here — the app shell reads them and
 * hands over two integers. That is the entire reason this is a class rather than
 * an {@code if} inside {@code DepthWallpaperService}: the table below is the part
 * of this feature that is easy to get quietly wrong, and an {@code if} in the
 * engine is a thing no test can reach. (The same reason
 * {@link com.amoshydra.basrelief.core.render.Framing} is in :core.)
 *
 * <p><b>What the platform does, which is not what the feature wants to assume.</b>
 * Research and sources are in {@code .design/03-android-capabilities.md}; it is
 * restated here because the pleasant answer is wrong and the whole class is a
 * refusal to give it:
 *
 * <ul>
 *   <li><b>26–33: no signal at all.</b> There is no {@code mWhich} anywhere in
 *       the engine plumbing on those releases, so an engine has nothing to read
 *       and one engine serves both screens. Per-surface settings are
 *       <i>impossible</i> there, not merely unimplemented.
 *   <li><b>34 (Android 14): the signal exists, the second engine does not.</b>
 *       {@code getWallpaperFlags()} arrived, while AOSP kept the lock engine
 *       behind {@code persist.wm.debug.lockscreen_live_wallpaper} — default off —
 *       so in practice an engine reads {@code FLAG_SYSTEM}.
 *   <li><b>35 (Android 15) up: both.</b> The gate is gone and the platform runs
 *       two engines in one process, one per surface.
 * </ul>
 *
 * <pre>
 *   SDK    flags            surface  lock       coversLock  perSurfaceLock
 *   26-33  any              HOME     STATIC     false       false
 *   34     FLAG_SYSTEM      HOME     STATIC     false       false
 *   34     FLAG_LOCK        LOCK     STATIC     true        false
 *   34     SYSTEM|LOCK      HOME     STATIC     true        false
 *   35+    FLAG_SYSTEM      HOME     LIVE       false       true
 *   35+    FLAG_LOCK        LOCK     LIVE       true        true
 *   35+    SYSTEM|LOCK      HOME     LIVE       true        true
 *   35+    no known bit     HOME     LIVE       false       true
 * </pre>
 *
 * <p>Every row is a true statement, which is the test each column has to pass.
 * The two that are easy to get backwards are the last two: a capable device that
 * is bound home only is not showing the photo on the lock screen, and one engine
 * bound to both screens shows one set of values on both.
 *
 * <p><b>Three questions, because they are three truths.</b> Which surface this
 * engine draws is a fact about the engine. Whether the lock screen composites it
 * as well is a fact about the binding. Whether the lock screen can be a live
 * surface of its own is a fact about the device, and it is the only one a control
 * should be gated on. A caller that collapses them either offers a control whose
 * value is read by nothing, or refuses one that would have worked.
 *
 * <p><b>One engine is one window, so two settings sets need two engines.</b> When
 * a user applies one wallpaper to "home and lock", the platform keeps one
 * {@code WallpaperData}, re-tasks the single engine with both flags, and the lock
 * screen composites that very surface. No amount of rendering gives a second set
 * of values a window to appear in.
 *
 * <p><b>Home is the answer everywhere it is ambiguous, and it is a real answer
 * rather than a shrug.</b> {@code lock: null} means the lock screen inherits home
 * ({@link LibraryItem#settings(Surface)}), so a photo nobody has customised
 * renders identically either way — which is what makes guessing the right way
 * round. Guessing LOCK on an engine that is really the home screen is not a
 * neutral mistake: it puts one photograph's lock framing on the home screen,
 * which reads as a rendering fault and is indistinguishable from one.
 *
 * <p>Never throws. The engine resolves on the GL thread at cold start, and a
 * throw there is a crash on the home screen rather than a fallback.
 */
public final class SurfaceResolver {

    // --------------------------------------------------------- platform levels

    /**
     * API 34, Android 14 — the first release whose {@code Engine} can say which
     * surface it is, through {@code getWallpaperFlags()}.
     *
     * <p>Written out rather than imported because :core may not contain an
     * {@code android.*} reference; {@code Build.VERSION_CODES.UPSIDE_DOWN_CAKE}
     * would put a framework class in a module specified to have none. The number
     * is the name.
     */
    public static final int API_SURFACE_SIGNAL = 34;

    /**
     * API 35, Android 15 — the first release AOSP actually runs a second live
     * engine for the lock screen, and so the first where a per-surface render has
     * a second window to appear in. This gates the <i>possibility</i> of
     * per-surface settings, not the feature's taste.
     */
    public static final int API_SECOND_LOCK_ENGINE = 35;

    // ------------------------------------------------------------ the flags

    /**
     * {@code WallpaperManager.FLAG_SYSTEM} — the home screen. API 24.
     *
     * <p>The values are copied rather than imported for the same reason the API
     * levels are: they arrive as a plain {@code int} from the engine, and :core
     * must not name a framework class to look at one.
     */
    public static final int FLAG_SYSTEM = 1;

    /** {@code WallpaperManager.FLAG_LOCK} — the lock screen. API 24. */
    public static final int FLAG_LOCK = 2;

    /**
     * "The flags were never read." The state of every engine below
     * {@link #API_SURFACE_SIGNAL}, and of any caller that does not pass them.
     *
     * <p>A sentinel rather than a nullable, for the reason this project keeps one
     * checked constant for every closed set: one way to say it, checked in one
     * place. It cannot collide — the platform returns a bitmask of the two
     * constants above and never a negative value.
     */
    public static final int FLAGS_UNREAD = -1;

    // ----------------------------------------------------------------- state

    /** {@code Build.VERSION.SDK_INT}. Kept, so the diagnostic can name it. */
    public final int sdk;

    /**
     * The raw {@code Engine.getWallpaperFlags()} value, or {@link #FLAGS_UNREAD}.
     *
     * <p>Stored unmasked on purpose. A diagnostic that prints a cleaned-up number
     * cannot show the bit that broke it, and the raw value is what the platform
     * actually said.
     */
    public final int flags;

    private SurfaceResolver(int sdk, int flags) {
        this.sdk = sdk;
        this.flags = flags;
    }

    /**
     * Facts from a level where the platform does not publish them — which is every
     * level below {@link #API_SURFACE_SIGNAL}, and the only honest way to call
     * this there, since {@code getWallpaperFlags()} does not exist to be called.
     */
    public static SurfaceResolver of(int sdk) {
        return new SurfaceResolver(sdk, FLAGS_UNREAD);
    }

    /**
     * Facts as read from the engine.
     *
     * <p>{@code flags} is <b>ignored below {@link #API_SURFACE_SIGNAL}</b>, and
     * that is deliberate rather than defensive: on 26–33 no such value can exist,
     * so a non-zero one is a caller bug or a mock, and honouring it would mean
     * believing a number the platform cannot produce. Below the signal the answer
     * is the fallback.
     */
    public static SurfaceResolver of(int sdk, int flags) {
        return new SurfaceResolver(sdk, flags);
    }

    // -------------------------------------------------------------- the facts

    /** Whether the platform can tell this engine which surface it is at all. */
    public boolean canTellSurface() {
        return sdk >= API_SURFACE_SIGNAL;
    }

    /** Whether the flags held here were actually read from the platform. */
    public boolean flagsRead() {
        return flags != FLAGS_UNREAD;
    }

    /**
     * Whether these flags are a real answer at all: the platform published them,
     * on a level that publishes them, and they are a bitmask.
     *
     * <p>One predicate for two callers, because "can this answer be believed" is
     * one question and answering it twice is two chances to disagree — which is
     * how {@link #surface()} and {@link #coversLockScreen()} would end up
     * disagreeing about the same engine and each be individually defensible.
     *
     * <p><b>Negative is not a bitmask.</b> The platform answers 1, 2 or 3, and a
     * bit it adds later can only be switched on, never on by a sign change — so a
     * negative value can only be {@link #FLAGS_UNREAD} or a caller bug, and both
     * get the fallback. Masking one would report every bit set, which is how an
     * unread value turns into a lock screen nobody was told about.
     */
    private boolean flagsUsable() {
        return canTellSurface() && flags >= 0;
    }

    // ------------------------------------------------------------- the answer

    /**
     * The surface this engine draws. What a lock-only engine draws the lock
     * screen's settings and everything else draws the home screen's.
     *
     * <p>Three cases collapse to {@link Surface#HOME} on purpose, and each is a
     * trap rather than an oversight:
     *
     * <ul>
     *   <li><b>No signal</b> (below 34). Nothing to read; home is the only
     *       answer there is, and the lock screen is showing this same surface.
     *   <li><b>Both flags</b>. One engine bound to home <i>and</i> lock draws one
     *       window that both screens composite. Treating "both" as lock would put
     *       the lock framing on the home screen; treating it as an error would
     *       blank the wallpaper over a value that is perfectly well defined.
     *   <li><b>Neither flag</b>, or a value with no known bit set. Not a
     *       documented platform state, so it is the fallback again rather than a
     *       guess.
     * </ul>
     *
     * <p>Flags are <b>masked</b>, not compared, so a bit this build has never heard
     * of cannot hide one it has: the platform is free to add one, and a resolver
     * that demanded an exact match would drop the lock engine back to home the
     * day it did. The one thing masking cannot be trusted with is
     * {@link #FLAGS_UNREAD}, whose every bit is set — so it is excluded by
     * {@link #flagsUsable()} rather than masked, and a value the caller never
     * supplied resolves to the fallback.
     */
    public Surface surface() {
        if (!flagsUsable()) return Surface.HOME;
        boolean lock = (flags & FLAG_LOCK) != 0;
        boolean system = (flags & FLAG_SYSTEM) != 0;
        return (lock && !system) ? Surface.LOCK : Surface.HOME;
    }

    /**
     * Whether this engine is the lock screen's <i>alone</i> — the one shape in
     * which a second set of values has a window of its own.
     *
     * <p>A fact about how the platform bound the wallpaper, and deliberately not
     * the product gate. A 34 device with the debug property set really does run a
     * lock-only engine; the app still declines to promise per-surface settings
     * there, which is {@link #supportsPerSurfaceLock(int)}. Conflating the two is
     * how a control ends up on a screen whose value is stored and never rendered.
     */
    public boolean isLockOnly() {
        return surface() == Surface.LOCK;
    }

    /**
     * Whether the platform says the lock screen composites <b>this</b> engine as
     * well as home.
     *
     * <p>True for a lock-only binding and for "both". False for a home-only one,
     * which is the common case: the photo is on the home screen and the lock
     * screen is not showing it at all — the state the studio reads as "not on a
     * screen yet".
     *
     * <p>Below {@link #API_SURFACE_SIGNAL} this is false on every level, including
     * the ones where the lock screen really is compositing this surface. The
     * platform publishes no signal there, so the app cannot establish it and must
     * not claim it. When a user applies one wallpaper to both screens below 34,
     * the lock screen gets whatever the home screen gets, and no per-surface
     * setting exists to contradict it.
     *
     * <p>{@link #FLAGS_UNREAD}, and any other negative value, is false here for
     * the same reason it is not a surface: every one of its bits is set, so
     * masking it alone would report a lock screen nobody has ever been told about.
     */
    public boolean coversLockScreen() {
        return flagsUsable() && (flags & FLAG_LOCK) != 0;
    }

    /**
     * What the lock screen can be, on this device. R-P3's fallback, named.
     *
     * <p>This is about the <b>app's options</b>, not about what happens to be on
     * the lock screen at this moment, which is why it does not consult the flags:
     * no app can bind a live wallpaper anywhere, so below 35 the one wallpaper
     * call in reach is {@code setBitmap(..., FLAG_LOCK)} and the lock screen can
     * only be given a still. A still is depthless and does not respond to tilt,
     * which is why the UI states that in a line rather than hiding the control.
     *
     * <p>On 35+ the route to a live lock surface is the system picker, not a call
     * this app can make: the destination is the user's to choose, which is the
     * same reason applying is a gesture rather than a button.
     */
    public LockSupport lockSupport() {
        return supportsPerSurfaceLock() ? LockSupport.LIVE : LockSupport.STATIC_FRAME;
    }

    /**
     * Whether this device can carry per-surface lock settings at all. R-P3's gate:
     * the Lock control is offered only where this is true.
     *
     * <p>A static question about a device, deliberately taking only an SDK level,
     * because the studio is not an engine and has no flags to ask with. Forward
     * compatible on purpose — a level this build has never heard of can only have
     * more capability than 35, never less.
     */
    public static boolean supportsPerSurfaceLock(int sdk) {
        return sdk >= API_SECOND_LOCK_ENGINE;
    }

    /** The same question, for a caller holding a resolver. */
    public boolean supportsPerSurfaceLock() {
        return supportsPerSurfaceLock(sdk);
    }

    @Override
    public String toString() {
        return "sdk=" + sdk
                + " flags=" + (flagsRead() ? "0x" + Integer.toHexString(flags) : "unread")
                + " surface=" + surface()
                + " lock=" + lockSupport()
                + " coversLock=" + coversLockScreen()
                + " perSurfaceLock=" + supportsPerSurfaceLock();
    }

    /**
     * The two honest states of a lock screen, with no third.
     *
     * <p>There is no "yes, and the values are on their way" below 35, and no
     * "ignore the user, the platform knows best" above it. Each constant names a
     * thing the app can actually do, which is the test every version of this enum
     * has to pass.
     */
    public enum LockSupport {
        /**
         * 35 and up: the lock screen can be a live surface with settings of its
         * own, and the user gets there through the system picker.
         */
        LIVE,
        /**
         * Below 35: no live lock surface this app can reach, so the only thing it
         * may put on the lock screen is a frame it captured. Flat, and it does not
         * tilt.
         */
        STATIC_FRAME
    }
}
