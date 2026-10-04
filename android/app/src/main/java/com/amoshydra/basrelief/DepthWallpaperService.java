package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.store.Trigger;
import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * The live wallpaper. The GLSurfaceView renders into the wallpaper surface
 * by overriding getHolder() to return the engine's surface holder (the
 * Learn OpenGL ES wallpaper pattern).
 */
public class DepthWallpaperService extends WallpaperService {

    /** The surface that was showing most recently, process-wide. Both engines live
     *  in this process, so this is the one honest answer to "what did the user just
     *  see". See DepthEngine.pendingGlideFrom for why it exists. */
    private static Surface lastVisibleSurface;
    /**
     * What the LOCK engine is currently showing, published for the HOME engine.
     *
     * <p><b>The cross-engine hand-off the user asked for</b> -- the last frame should be taken from the lock engine rather than drawn again -- and it is possible because both
     * engines share one process. The lock engine records its framing while it draws; the
     * home engine, cold-loading because the device was just unlocked, takes it as the
     * origin of its FIRST frame instead of painting its own and correcting afterwards.
     *
     * <p>Volatile and static, like {@link #lastVisibleSurface} and for the same reason.
     * Cleared when a LOCK engine stops being the foreground surface, so a stale value
     * cannot be inherited by a home engine that starts for some other reason.
     */
    private static volatile Framing lockFramingShowing;

    /**
     * The engines alive under this service, in creation order.
     *
     * <p>Main thread only: engines are created, destroyed and re-tasked there,
     * and so is the unlock broadcast. It exists so the unlock receiver can ask
     * the same surface question an engine would — see {@link #surfacesForUnlock()}.
     */
    private final List<DepthEngine> engines = new ArrayList<DepthEngine>();

    @Override
    public void onCreate() {
        super.onCreate();
        // WHICH BUILD THIS IS, once per service creation, so every log session opens with
        // the answer. Asked for after a session where "is the new APK actually installed"
        // was a real question four times and the only way to tell was comparing byte sizes
        // and file timestamps. BuildConfig.GIT_COMMIT is the commit the APK was built from.
        android.util.Log.i("Basrelief", "Basrelief service starting, build "
                + BuildConfig.GIT_COMMIT
                + " renderer=" + (BuildConfig.RENDERER_GLES3 ? "gles3" : "gles2"));
        // The unlock-edge listener lived here and was REMOVED because it cannot be
        // registered: addKeyguardLockedStateListener requires
        // android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, which is not in the
        // public SDK, and the SecurityException it threw from onCreate took the whole
        // wallpaper service down on every creation. isKeyguardLocked() needs no
        // permission; the LISTENER does.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * Which surfaces one unlock should be asked for: the ones this process has an
     * engine for, home first.
     *
     * <p>This is the same gate an engine applies to itself — a playlist for the
     * lock screen does not rotate for the home engine, and vice versa — asked
     * once by the service because the receiver is one. A home-only binding asks
     * home alone; a lock-only binding asks lock; both engines ask home and then
     * lock, and the first rotation stamps the shared moment so the second is
     * inert. With no engine yet (the service can be bound a moment before the
     * first one appears) home is the answer, which is also where an unlock lands.
     */
    private List<Surface> surfacesForUnlock() {
        boolean home = false;
        boolean lock = false;
        for (DepthEngine engine : engines) {
            if (engine.resolvedSurface().surface() == Surface.LOCK) lock = true;
            else home = true;
        }
        List<Surface> surfaces = new ArrayList<Surface>();
        if (home || !lock) surfaces.add(Surface.HOME);
        if (lock) surfaces.add(Surface.LOCK);
        return surfaces;
    }

    /**
     * Advance the rotating playlist for one reason — a clock or an event — and
     * persist the pair.
     *
     * <p>Rotation writes the index's OWN selection: the same two calls a user's
     * tap makes, on the same key. The selection is the only pointer, so the
     * engine that hears the write re-resolves from the index like any other
     * change; there is no second "current photo" to keep in step.
     *
     * <p>Deliberately idempotent and stamped. {@code advance} writes the playlists
     * blob back with a new clock, so asking twice for one reason is a no-op
     * rather than two photos forward — which is why the pref listener can fire on
     * its own write without a guard, and why one unlock asked of two surfaces
     * moves once.
     *
     * @param on    which screen is asking; the surface gate, not a hint
     * @param event null when a clock is asking, or the event that just happened
     * @param now   the shared moment of the reason, so one event has one answer
     */
    private void advancePlaylist(SharedPreferences p, Surface on, Trigger event, long now) {
        try {
            Playlists playlists = Playlists.parse(p.getString(Playlists.KEY, null));
            Library index = Library.parse(p.getString(Library.KEY, null), motionFallback(p));
            Playlists.Result result = Playlists.advance(playlists, index, on, now, event);
            if (result.selectedId == null) return;      // nothing was due
            // The index is the single pointer, so this is the ONLY write.
            LibraryStore.saveNow(p, result.library);
            PlaylistScreen.PlaylistStore.save(p, result.playlists);
            android.util.Log.i("Basrelief", "playlist advanced to "
                    + result.selectedId + (event == null ? "" : " on " + event.key()));
        } catch (Exception e) {
            // A rotation must never take the wallpaper down with it. The engine
            // keeps drawing whatever it already had.
            android.util.Log.w("Basrelief", "playlist advance failed: " + e);
        }
    }

    /** Motion's old home, needed only to migrate a pre-v3 index. Never written. */
    private static int motionFallback(SharedPreferences p) {
        try {
            return p.getString(Config.KEY, null) == null ? 33
                    : Config.parse(p.getString(Config.KEY, null)).motion;
        } catch (Exception e) {
            return 33;
        }
    }

    /**
     * The component a user selects, i.e. what the studio hands to the system
     * picker.
     *
     * <p>It lives here rather than in a helper class because it names this
     * service, and a class called something like "WallpaperRefresh" outliving
     * the refresh it was named for is the sort of thing that reads as working.
     * <p>
     * This is also the whole of the "re-bind" story now, and it is worth
     * knowing why there is nothing more: an app cannot ask the system to
     * re-bind its own live wallpaper. {@code setWallpaperComponent} is hidden
     * and needs {@code SET_WALLPAPER_COMPONENT}, which is
     * {@code signature|privileged}, so the platform refuses it for every
     * ordinary app -- verified on a Pixel 7 Pro, not assumed. {@code
     * WallpaperManager} offers no public equivalent, and setBitmap/setResource/
     * setStream all replace a live wallpaper with a static image. The picker is
     * the only route, which is why "Set as wallpaper" is the apply gesture and
     * not a convenience.
     */
    static android.content.ComponentName wallpaperComponent(android.content.Context context) {
        return new android.content.ComponentName(context, DepthWallpaperService.class);
    }

    @Override
    public Engine onCreateEngine() {
        return new DepthEngine();
    }

    private class DepthEngine extends Engine {

        private class WallpaperGLSurfaceView extends GLSurfaceView {
            WallpaperGLSurfaceView(Context context) {
                super(context);
            }

            @Override
            public SurfaceHolder getHolder() {
                return getSurfaceHolder();
            }
        }

        private WallpaperGLSurfaceView glView;
        private ReliefRenderer renderer;
        private TiltController tilt;
        private SensorPolicy sensorPolicy;
        /** Last value onVisibilityChanged gave us, for the diagnostic line only. */
        private volatile boolean visible = true;
        /**
         * What the user was actually looking at the moment before this engine woke.
         *
         * <p>Null unless this engine just became visible, so a slider move or an
         * index change -- which also run applyFromPrefs -- cannot glide: only a wake
         * carries a "from", and it is consumed by the resolve that follows.
         */
        private Surface pendingGlideFrom;
        /**
         * Whether this engine has ever been shown.
         *
         * <p>The system destroys and rebuilds wallpaper engines routinely, so the
         * first show of each engine is a cold start with no previous frame. Only a
         * later show is a transition.
         */
        private boolean everShown;
        /**
         * The surface that was showing most recently, process-wide.
         *
         * <p>Static because both engines share this process (AGENTS.md), and it is
         * the only honest answer to "what did the user just see". The first version
         * of the glide assumed waking meant the OTHER surface was just showing --
         * which on a one-engine setup bound to both surfaces (flags=0x3,
         * surface=HOME, coversLock) is a surface that never rendered anything, so
         * every wake animated from a config no screen had been showing. Reported as
         * "it now always glides from lock to home", on turning the screen on, on
         * unlocking, and on returning to the launcher. The truth is knowable: ask
         * whichever engine was visible.
         */
        // Not static here: a non-static inner class cannot hold static fields, and
        // the service is the process-wide home both engines already share.
        // (Held as DepthWallpaperService.lastVisibleSurface.)
        private boolean tiltRunning = false;
        /**
         * Which screen this engine is drawing, resolved in :core from the raw
         * platform facts rather than here.
         *
         * <p>Volatile because {@code onWallpaperFlagsChanged} writes it on the main
         * thread and {@code applyFromPrefs} reads it from whichever thread the
         * prefs listener fires on. A resolver is immutable, so publishing one is
         * publishing a whole answer rather than half of one — which is the same
         * reason nothing about the surface is cached on the service: two engines
         * share this process.
         */
        private volatile SurfaceResolver surface =
                SurfaceResolver.of(Build.VERSION.SDK_INT);
        /** Last image/depth handed to the renderer, so a Depth nudge does not re-decode. */
        private SharedPreferences prefs;
        private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                        // Two keys now, not one: Motion is in the config, while
                        // the selection and every per-item setting are in the
                        // index. Both are single versioned blobs, so each write is
                        // one consistent snapshot -- the reason the old per-key
                        // branches could not observe half a change is the same
                        // reason to keep watching blobs rather than keys.
                        // Three keys now. Playlists is watched because a rotation
                        // that nothing observes is a playlist that never advances --
                        // and the engine is the only thing alive when the screen is
                        // off, which is when a timed trigger is most likely to fall
                        // due.
                        if (!Config.KEY.equals(key) && !Library.KEY.equals(key)
                                && !Playlists.KEY.equals(key)) return;
                        advancePlaylistIfDue(p);
                        applyFromPrefs(p);
                    }
                };

        /**
         * Read both blobs and put the result on the GL thread.
         *
         * <p>Split out of the listener so the engine's own first load and every
         * later change resolve identically. Having two paths that were meant to
         * agree is what produced the black-wallpaper bug, where a disagreement
         * means the wallpaper shows something nobody chose.
         *
         * <p>Reads on the calling thread and touches GL only inside the queued
         * runnable: {@code queueEvent} is the one thread hop the renderer allows
         * (see the threading table in AGENTS.md).
         */
        /**
         * The one line that makes this diagnosable.
         *
         * <p>The engine is otherwise completely silent, and that has now cost two
         * investigations: a black wallpaper that took three wrong theories before log
         * timestamps settled it, and a stale wallpaper that could not be told apart from
         * a dead engine, because both look like a correct picture of the wrong thing.
         * The three facts needed to separate those are the resolved item, whether the
         * surface is still valid, and what the renderer believes is on screen. One
         * line, so the next occurrence is a grep rather than an argument.
         *
         * <p>Not in the render path: this runs on transitions only, and
         * {@code onDrawFrame} must stay free of per-frame work.
         */
        /**
         * Record what the engine just did, where the studio will display it.
         *
         * <p>Every path that resolves state goes through here, including the cold
         * load from {@code onSurfaceCreated}. That one matters: the live-update
         * path alone produced no output at all on a device where the wallpaper
         * would not follow the gallery, which is consistent with the engine cold
         * loading and then never being told anything -- so a diagnostic that only
         * covers the path you are debugging tells you nothing.
         */
        private void note(String what) {
            android.util.Log.i("Basrelief", "wallpaper: " + what);
            if (prefs != null) {
                prefs.edit().putString("wallpaper_debug", what).commit();
            }
        }

        /**
         * Ask the platform which screen this engine is, and resolve it in :core.
         *
         * <p>The one place {@code getWallpaperFlags()} is called, and the one place
         * the version check lives, because {@code Engine.getWallpaperFlags()} does
         * not exist below API 34 and calling it there is a
         * {@code NoSuchMethodError} rather than a compile error. Everything the
         * answer depends on is handed over as two ints, which is what lets the
         * decision itself be unit tested without a device.
         *
         * <p>Never throws. This runs on the main thread during {@code onCreate} and
         * again on every visibility change, and an engine that cannot answer must
         * still draw the home screen.
         */
        private void resolveSurface() {
            SurfaceResolver resolved;
            try {
                resolved = Build.VERSION.SDK_INT >= SurfaceResolver.API_SURFACE_SIGNAL
                        ? SurfaceResolver.of(Build.VERSION.SDK_INT, getWallpaperFlags())
                        : SurfaceResolver.of(Build.VERSION.SDK_INT);
            } catch (Exception e) {
                // A binder call on a level that claims to support it can still fail
                // on an OEM build that does not. The home screen is the correct
                // fallback, not a reason to leave the wallpaper blank.
                resolved = SurfaceResolver.of(Build.VERSION.SDK_INT);
            }
            surface = resolved;
        }

        /**
         * Advance the rotating playlist, if a clock says one is due on this
         * engine's surface.
         *
         * <p>This is the clock path only: an event trigger is never due on a
         * clock (see {@link Trigger#isTimed()}), so nothing a caller here does
         * can fire an unlock playlist. The unlock itself is delivered to the
         * service's one receiver, which hands the event to the same write.
         *
         * <p>Rotation writes the index's OWN selection -- the same two calls a
         * user's tap makes -- and then this engine re-resolves from that index
         * like any other change. So there is no second "current photo" and
         * nothing to keep in step: the playlist decides, the index records, and
         * the engine follows. The surface is read here, in the call, because a
         * re-task can change it between the read and the call.
         */
        private void advancePlaylistIfDue(SharedPreferences p) {
            DepthWallpaperService.this.advancePlaylist(p, surface.surface(), null,
                    System.currentTimeMillis());
        }

        /**
         * This engine's resolved surface. The unlock receiver asks the live ones
         * so an event is advanced for the screens this process can actually
         * serve, exactly as the engine would for a clock.
         */
        SurfaceResolver resolvedSurface() {
            return surface;
        }

    /**
     * Whether a resolve is a surface transition that must EASE, or an ordinary resolve.
     *
     * <p><b>The origin is always "what is showing", so all this decides is whether to
     * glide at all.</b> It used to return the OTHER surface's framing as the glide's
     * starting point, but that is a guess about a frame this engine may not be holding,
     * and {@code glide()} paints its origin first -- so a wrong guess was an instant
     * paint of the wrong config. The renderer's own {@code snapshot()} is the honest
     * origin (see {@code glide(null, ...)}), so the framing this method once returned is
     * gone; what remains is the one bit that was always the real question.
     *
     * <p>Only when this engine was hidden and is becoming visible: that is the one
     * event that means the user was looking at the other screen a moment ago -- a lock
     * engine waking as the phone locks, a home engine waking as it unlocks. A
     * same-surface resolve (a slider moved, the index changed) is not a transition,
     * and gliding it would smear every adjustment.
     */
    private boolean isSurfaceTransition(SurfaceResolver resolved) {
        Surface from = pendingGlideFrom;
        pendingGlideFrom = null;             // consumed: one transition per wake
        if (from == null) return false;      // not a wake: apply, do not smear
        // The LOCK engine never glides, and the reason is physical rather than
        // aesthetic: locking the phone turns the screen off, so nobody watches the
        // lock engine's first frame -- but unlocking is watched. Gliding here only
        // ever produced a start config the screen was not showing.
        //
        // Concretely, the case reported: home -> screen off -> screen on (locked).
        // lastVisibleSurface was HOME, because that is where the user was before the
        // screen went off, and the display was actually holding the lock window's own
        // retained frame. So the lock engine glided from home -- goal, home, goal,
        // the double-take again, from a start that was never on screen. Applying
        // instead is a no-op: the retained frame is already the goal.
        if (resolved.surface() == Surface.LOCK) return false;
        // No second engine means no other surface to have come from.
        if (!resolved.supportsPerSurfaceLock()) return false;
        // Same surface showing before and now is not a surface transition: returning
        // to the launcher, or a same-surface wake, must no-op rather than ease.
        return from != resolved.surface();
    }

    /**
     * Whether the screen is on and interactive, false when the platform will not answer.
     *
     * <p>Read by {@link #holdLockFrameForUnlock} to catch the moment the keyguard takes
     * over: at that instant {@code isDeviceLocked()} on this device is still false, so the
     * screen going non-interactive is the signal that the wallpaper is about to be
     * covered by the lock screen rather than by an app.
     */
    private boolean isInteractive() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /**
     * Whether the device is locked right now, false when the platform will not answer.
     *
     * <p>The reading that TRACKS the screen being looked at: measured true on the lock
     * screen and false on home, while {@code isKeyguardLocked()} read true on every sample
     * of every cycle. Used both to gate whether a HOME engine may inherit the lock frame
     * on a cold start and to decide, with the screen state, when the lock frame is held.
     */
    private boolean isDeviceLockedNow() {
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return false;
        try {
            return km.isDeviceLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the keyguard exists at all; unlike {@code isDeviceLocked}, it never changes here. */
    private boolean keyguardWasUp() {
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return false;
        try {
            return km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether this engine is the surface the user is currently looking at.
     *
     * <p><b>The gate that stops one engine painting over the other.</b> Both engines
     * resolve-and-apply on becoming visible, each with its own surface's settings, so
     * whichever runs second wins — and on unlock that was the lock engine, which left
     * home gliding back from lock's framing. The user: a glimpse of the home config appeared stable, then the lock config rendered again.
     *
     * <p><b>Why this cannot be a `visible` check.</b> A lock engine is visible while the
     * screen is off and the keyguard is up, and that is exactly when it must draw;
     * `visible` says nothing about which screen is ahead of the other. The platform does
     * know, and it is the one question worth asking rather than inferring:
     * {@code KeyguardManager.isKeyguardLocked()} separates the two cases an engine
     * otherwise sees as identical. With two engines, lock is foreground when the keyguard
     * is up and home is foreground when it is not.
     *
     * <p><b>One-engine setups are untouched by construction.</b> Below API 35 there is a
     * single engine covering both, its surface resolves to HOME, and a keyguard reading
     * would wrongly park it — so the gate applies only once there genuinely are two
     * engines, which is the same capability question {@link SurfaceResolver} already
     * answers.
     *
     * <p><b>Read live, never cached.</b> The keyguard changes between the moment an engine
     * becomes visible and the moment its queued apply runs, and a cached answer is the
     * stale-bookkeeping failure AGENTS.md names for this service. One call per apply, not
     * per frame.
     *
     * <p><b>Every failure path returns true.</b> A missing KeyguardManager, or an OEM that
     * refuses the call, must cost the wallpaper a correction rather than its picture —
     * declining to draw blanks the screen, which is the one outcome worse than a flash.
     */
    private boolean isForegroundSurface(SurfaceResolver resolved) {
        if (!resolved.supportsPerSurfaceLock()) return true;   // one engine, one surface
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return true;
        boolean keyguardUp;
        boolean deviceLocked;
        try {
            keyguardUp = km.isKeyguardLocked();
            deviceLocked = km.isDeviceLocked();
        } catch (Exception e) {
            return true;   // an unanswerable device draws, never blanks
        }
        // TWO readings, because neither alone is enough.
        //
        // isDeviceLocked() is the one that TRACKS the screen being looked at -- measured
        // on the device, it read true on the lock screen and false on home, while
        // isKeyguardLocked() read true on every sample of every cycle including after two
        // full unlocks. Eight attempts used isKeyguardLocked as a transition signal and
        // it never once changed, so every rule built on it was decided by a constant.
        //
        // But isDeviceLocked() alone is not sufficient either: it returns FALSE in a
        // trusted state -- a trusted place, a trusted device, or a device with no lock
        // code -- while the lock screen may still be showing. So the LOCK engine requires
        // both terms, and cannot be fooled by Smart Lock into painting nothing.
        //
        // HOME gates on deviceLocked alone, because the trusted-state caveat only ever
        // makes it read false, and false is the correct answer for home: a device in a
        // trusted state is not showing a keyguard the user must dismiss.
        if (resolved.surface() == Surface.LOCK) return keyguardUp && deviceLocked;
        return !deviceLocked;
    }

    /**
     * Make a hidden HOME engine's surface hold the LOCK framing, so the keyguard reveal
     * shows what the user was just looking at and the unlock glide starts from it.
     *
     * <p><b>Load-bearing, and measured rather than assumed.</b> It was once thought to be
     * an earlier attempt at what {@code inheritFirstFrameFrom} now does, and therefore
     * removable. Disabling it and measuring a plain lock -> screen-on -> unlock shows it is
     * not: the HOME engine <i>survives</i> a lock cycle, so it never cold-loads, so nothing
     * inherits, and the retain-frame stays HOME. The unlock then resolves
     * {@code snap.zoom=1.42 dest.zoom=1.42} -- a zero-travel no-op, the glide gone. With
     * this in place the same wake logs {@code snap.zoom=2.0 snap.panY=26.0 dest.zoom=1.42},
     * a real 2x -> 1x ease. The two mechanisms cover different engine lifetimes:
     * inheritance is a freshly created engine, this is a surviving one.
     *
     * <p><b>The snap this exists to remove.</b> On unlock the HOME engine wakes and the
     * platform reveals its surface. That surface still holds the last frame it drew —
     * HOME's own framing, from before the phone locked — and the engine only asks for the
     * LOCK origin 200 ms later, once {@code applyFromPrefs} has re-read the index. So the
     * eye sees home(1x) on the reveal, then the glide's origin paints lock(2x) over it —
     * an instant paint the user filed as a snap — and only then the glide to home. Their
     * rule is explicit: nothing may be painted instantly; a frame can only be reached by gliding towards it
     *
     * <p><b>Why the paint here is not the instant paint that rule forbids.</b> It is an
     * {@code apply} of a genuinely different framing, so the guard in
     * {@link ReliefRenderer#apply} permits it — but it runs while the surface is hidden
     * and the screen is dark. GLSurfaceView queues the event but refuses to draw
     * ({@code readyToDraw()} requires {@code !mPaused}), so the LOCK framing is written to
     * the renderer's volatile fields and <i>no frame is presented</i>. The first frame the
     * engine draws after {@code onResume} is that LOCK, which is what the platform reveals;
     * {@code glide(null, HOME)} then finds the origin equal to what is on screen and its
     * origin-apply is the guard's no-op. Measured on unlock to the launcher: the pre-hold
     * is the one {@code applyPaint}, nine seconds before the reveal, and the glide that
     * follows logs {@code origin=snapshot snap.zoom=2.0} with an origin-apply of
     * {@code applyNoop} -- nothing painted while the user could see anything.
     *
     * <p><b>When, and why it is not every hide.</b> An app covering the wallpaper must not
     * make the home surface hold the lock config — the user was looking at nothing, and
     * the launcher's return should be an instant, not a glide from lock. Only the screen
     * going dark ({@code !isInteractive()}) or the keyguard taking over qualifies, which
     * is the moment before the reveal this is preparing for. The {@code keyguard} terms
     * are the same two readings {@link #isForegroundSurface} uses; on this device
     * {@code isDeviceLocked()} is still false at the instant the screen starts to darken,
     * so {@code isInteractive()} is the one that fires. Verified: when a studio covers the
     * wallpaper the hide logs {@code interactive=true}, this returns, and the launcher
     * return is a no-op rather than a glide from a screen nobody was looking at.
     *
     * <p><b>HOME only, and both engines only.</b> A LOCK engine is never seen at wake, and
     * on a one-engine setup there is no second config to hold — the same capability the
     * rest of the surface logic gates on.
     */
    private void holdLockFrameForUnlock() {
        if (glView == null || renderer == null || prefs == null) return;
        if (surface.surface() != Surface.HOME) return;
        if (!surface.supportsPerSurfaceLock()) return;
        if (isInteractive() && !isDeviceLockedNow() && !keyguardWasUp()) return;
        final Framing lock = Framing.of(
                AppState.load(DepthWallpaperService.this, prefs, getFilesDir()).activeItem(),
                Surface.LOCK);
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.apply(lock);
            }
        });
        glView.requestRender();
    }

    /**
     * The one glide rate, from the setting the user chose. Read here rather than
     * cached so a glide that begins after the slider moved uses the speed just
     * chosen.
     */
    private float glideSpanMs(SharedPreferences p) {
        try {
            return Math.max(1f, p.getInt(TiltController.KEY_GLIDE_MS,
                    TiltController.GLIDE_DEFAULT_MS));
        } catch (Exception e) {
            return TiltController.GLIDE_DEFAULT_MS;
        }
    }

    private void tellRendererItsSurface() {
        SurfaceResolver which = surface;
        if (tilt == null) return;
        if (renderer != null) renderer.setEditSurface(which.surface());
    }

    private void applyFromPrefs(SharedPreferences p) {
        tellRendererItsSurface();
            // A flags change can arrive while the engine is still being built, and
            // this runs before the renderer exists on that path. Nothing here may
            // throw: the alternative is a wallpaper that is not drawn at all.
            if (p == null || glView == null || renderer == null) return;
            final AppState state = AppState.load(DepthWallpaperService.this, p, getFilesDir());
            final LibraryItem active = state.activeItem();
            // Resolved from the item and from nothing else. There is no branch for
            // "the demo": it is a file in filesDir like any other photograph, named
            // by its own id. A null item -- nothing selected, which is where a
            // delete of the active row lands -- resolves to nothing and is drawn as
            // the packaged demo pair, which is a normal state and not a missing
            // photo.
            java.io.File imgF = PrefFiles.resolve(getFilesDir(),
                    active == null ? null : active.imageName);
            java.io.File depF = PrefFiles.resolve(getFilesDir(),
                    active == null ? null : active.depthName);
            // The supplied normal, resolved from the same item. Null is the flat-lit
            // state, not a failure, and it is part of the pair identity below so a
            // photo that gains a normal is re-installed rather than reported as
            // already showing.
            java.io.File nrmF = PrefFiles.resolve(getFilesDir(),
                    active == null ? null : active.normalName);
            final String img = imgF == null ? null : imgF.getAbsolutePath();
            final String dep = depF == null ? null : depF.getAbsolutePath();
            final String nrm = nrmF == null ? null : nrmF.getAbsolutePath();
            // Which screen this engine is, and therefore whose settings to render.
            // Resolved through the one resolution point, so the two engines of a
            // two-engine setup cannot disagree about what a photograph looks like.
            final SurfaceResolver resolved = surface;
            // Resolved once, on the calling thread, and applied on the GL thread.
            // queueEvent is the one thread hop the renderer allows (see the
            // threading table in AGENTS.md).
            final Framing framing = Framing.of(active, resolved.surface());
            // Whether this resolve is a surface transition that must EASE. The origin
            // is not resolved here: the renderer uses what it is actually showing, so
            // the first frame of a transition is always the frame on screen.
            final boolean transition = isSurfaceTransition(resolved);
            // At Motion 0 the tilt cannot change a pixel, so the sensor is pure
            // waste. Read from the resolved framing rather than from the renderer
            // so this stays on the calling thread and needs no hop to the GL
            // thread to find out. Per-surface, therefore: a lock engine parked by
            // the lock screen's own Motion, and vice versa.
            if (sensorPolicy != null) {
                sensorPolicy.setMotionActive(framing.motion > 0f);
            }
            String diagnostic = "visible=" + visible
                    + " surface=" + (glView.getHolder() != null
                            && glView.getHolder().getSurface().isValid())
                    // "which", because "surface" above is the drawing surface's
                    // validity and a second meaning for the word in the one line
                    // people grep would cost somebody an afternoon.
                    + " which=" + resolved.surface()
                    + " item=" + (active == null ? "none" : active.displayName())
                    + " want=" + (img == null ? "packaged" : new java.io.File(img).getName())
                    + " onScreen=" + renderer.showingSamePair(img, dep, nrm)
                    + " at=" + (System.currentTimeMillis() % 1000000L);
            // Publish what the LOCK engine is showing, so a HOME engine starting on the
            // unlock can make its first frame this rather than its own. See lockFramingShowing.
            if (resolved.surface() == Surface.LOCK) {
                lockFramingShowing = framing;
            }
            android.util.Log.i("Basrelief", "wallpaper: " + diagnostic);
            // Recorded HERE, where onScreen is known, and not on becoming visible:
            // an engine that has its pair on screen is one the user can actually see,
            // and an engine created but never drawn -- which is what the platform does
            // to the home engine at screen-on -- is not. That distinction is the whole
            // difference between gliding from the surface the user was looking at and
            // gliding from a window that never appeared.
            if (visible && renderer.showingSamePair(img, dep, nrm)) {
                DepthWallpaperService.lastVisibleSurface = resolved.surface();
            }
            // THE LOCK ENGINE IS THE LAST SCREEN EVEN THOUGH IT DOES NOT PAINT.
            //
            // It declines to apply once the keyguard lifts -- see isForegroundSurface --
            // so it never reaches the line above, lastVisibleSurface stays HOME, and the
            // home engine then concludes "same surface, no transition" and does not
            // glide. But the user WAS looking at the lock screen; that is the whole
            // meaning of locking the phone. So the lock engine records itself HERE, where
            // it knows it is the foreground surface, before it declines to draw.
            //
            // Not an inference and not a cached keyguard read: isForegroundSurface asks
            // the platform at this moment, and a lock engine only passes it while the
            // keyguard is genuinely up. That is a stronger observation than
            // showingSamePair -- "this is what the user is looking at" rather than "this
            // pair happens to be in my renderer" -- which is why its own line above could
            // not do this job.
            if (resolved.surface() == Surface.LOCK && isForegroundSurface(resolved)) {
                DepthWallpaperService.lastVisibleSurface = Surface.LOCK;
            }
            // Also into prefs, because the studio shows this on screen. Logcat
            // needs a cable and a filter; this needs only opening the app, which
            // is the difference between a diagnostic that gets read and one that
            // gets forgotten.
            prefs.edit().putString("wallpaper_debug", diagnostic).commit();
            // ONLY THE ENGINE THE USER IS LOOKING AT MAY APPLY.
            //
            // Both engines run this on becoming visible, and each resolves its OWN
            // surface's settings -- so whichever ran second won the right to paint, no
            // matter which screen was showing. On unlock that was the LOCK engine: the
            // log shows which=LOCK then which=HOME 1.7s apart for the same photograph,
            // and because per-surface settings now genuinely differ, home then GLIDED
            // from lock's framing back to its own. The user's words: a glimpse of the home config appeared stable, then the lock config rendered again
            // when animated glide back to home config before stable."
            //
            // This is the same failure the phantom-home guard above fixes in the other
            // direction, and it only became visible when per-surface settings started
            // working: before that both engines resolved identical framing and a late
            // write was invisible. The tell is the same one that guard uses -- an engine
            // can be visible without being ON SCREEN -- so the gate is the surface the
            // user is actually looking at, and the engine that is not it declines to
            // draw at all rather than drawing and being corrected.
            //
            // Never gate on `visible` alone: a lock engine IS visible while the screen
            // is off and the keyguard is up, and that is precisely the moment it must
            // draw. Blanking it there is the failure mode to check for.
            if (!isForegroundSurface(resolved)) {
                return;
            }
            // The glide is one rate: re-centring, a surface switch, and a playlist
            // advance are one motion, and three numbers nobody can predict is the
            // thing the setting exists to prevent.
            final float spanMs = glideSpanMs(p);
            glView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    // Becoming visible glides FROM WHAT IS ON SCREEN -- not from the
                    // surface label guessed a moment ago.
                    //
                    // That guess could disagree with the retained frame. On the return
                    // to the launcher, lastVisibleSurface was LOCK (the lock engine
                    // records itself while the keyguard is up), so this used to arm
                    // glide(LOCK, HOME) -- and glide() paints its ORIGIN first, so the
                    // HOME surface, which already held HOME, was painted with LOCK
                    // (zoom 2.0) for a frame and then eased back to HOME (1.42).
                    // Measured: the origin apply was sameAsShowing=false
                    // delta=[zoom=1.42->2.0, panY=0.0->26.0, lightAzimuth=209.0->239.0].
                    // That is the 1x snap followed by a glide from 2x to 1x.
                    //
                    // Passing null is "from what is showing": glide() takes snapshot()
                    // as the origin and gives it one apply, a no-op when the retained
                    // frame already matches. Where the engine really was holding the
                    // other surface (the unlock, pre-held by holdLockFrameForUnlock) the
                    // snapshot IS the lock framing, so the motion is unchanged; where it
                    // was already this surface's framing, there is nothing to paint and
                    // the glide eases from where the eye is.
                    if (transition) {
                        renderer.glide(null, framing, spanMs);
                    } else {
                        // No local guard here any more: apply() refuses a RESOLVE whose
                        // framing is on screen OR already gliding towards, so this is a
                        // no-op in exactly the cases that used to need the check spelled
                        // out. It was duplicated here -- the wallpaper tested
                        // isGlidingToward and every OTHER caller, the studio's applyFiles
                        // among them, did not, which is how one call site stayed correct
                        // and the others snapped. The rule lives in the renderer now; see
                        // apply(Framing, Apply).
                        renderer.apply(framing);
                    }
                    // Only reload when what is ON SCREEN differs from what should
                    // be. The renderer knows that; this class used to keep its own
                    // copy updated at request time, so a load that never landed
                    // still counted as done and the wallpaper stayed on the old
                    // photo until the process died. Re-decoding on every Depth
                    // nudge would drop a frame for nothing, so the check earns
                    // its keep.
                    if (!renderer.showingSamePair(img, dep, nrm)) {
                        // Async: the read/decode/mesh-build must not happen
                        // between two frames of the wallpaper, or it visibly
                        // freezes for seconds.
                        renderer.loadPairAsync(img, dep, nrm);
                    }
                }
            });
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            // Before anything can ask which surfaces are live, including an
            // unlock that arrives between here and the first visibility change.
            engines.add(this);
            // First, before the renderer exists and before any path can read the
            // field: a flags change that arrives between here and the first
            // applyFromPrefs would otherwise be answered from the unread default.
            resolveSurface();
            // The whole resolver in brackets, so the one line carries the SDK level
            // and the raw flags behind "which=" and is not mistaken for it.
            note("engine created [" + surface + "] surface="
                    + (holder != null && holder.getSurface() != null
                            && holder.getSurface().isValid()));
            renderer = new ReliefRenderer(DepthWallpaperService.this);
            // Set BEFORE the surface exists, so the renderer's own cold load -- which
            // runs inside onSurfaceCreated and resolves Framing.of(item, editSurface)
            // -- resolves THIS engine's surface and not the renderer's default, HOME.
            //
            // It was only set later, from applyFromPrefs, which made the cold load a
            // race: sometimes it painted the lock config, sometimes home's, depending
            // on whether the visibility path had run yet. That is the flash -- two
            // resolves, two configs, one of them wrong, before the transition.
            renderer.setEditSurface(surface.surface());
            // THE FIRST FRAME OF AN UNLOCK IS THE LOCK SCREEN. Hand the cold load the
            // framing the LOCK engine was showing, so it glides from there instead of
            // painting its own and being corrected afterwards -- the home, lock, home
            // flicker the user reported.
            //
            // Guarded by two facts, both measured on the device rather than assumed:
            // the lock engine must have published something (a reboot has nothing to
            // inherit), and the device must not be locked right now (a screen-on into the
            // keyguard is not home's arrival). isDeviceLocked is the reading that tracks
            // the actual state -- isKeyguardLocked read true on every sample of every
            // cycle, including after two full unlocks.
            if (surface.surface() == Surface.HOME
                    && surface.supportsPerSurfaceLock()
                    && !isDeviceLockedNow()
                    && lockFramingShowing != null) {
                renderer.inheritFirstFrameFrom(lockFramingShowing, 250f);
            } else {
                renderer.inheritFirstFrameFrom(null, 250f);
            }
            glView = new WallpaperGLSurfaceView(DepthWallpaperService.this);
            // 3 for the shipping GPU-displacement renderer (vertex texture fetch),
            // 2 for the legacy CPU-mesh fallback; see BuildConfig.RENDERER_GLES3.
            glView.setEGLContextClientVersion(BuildConfig.RENDERER_GLES3 ? 3 : 2);
            ReliefRenderer.enableMSAA(glView); // 4x MSAA: smooths polygon edges
            glView.setPreserveEGLContextOnPause(true);
            // The renderer needs a way back onto the GL thread; only the host
            // has the GLSurfaceView whose queueEvent provides it.
            renderer.setGlDispatch(ReliefRenderer.glExecutor(glView));
            // The system snapshots this surface ~100 ms after it appears, so the
            // first load must be inline or that snapshot is black forever.
            renderer.setSyncFirstLoad(true);
            renderer.setRenderRequester(new Runnable() {
                @Override
                public void run() {
                    glView.requestRender();
                }
            });
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            // the wallpaper is read-only: all interaction (orbit, pan, zoom,
            // pickers) happens in the studio; it only tilts with the gyro
            prefs = getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            sensorPolicy = new SensorPolicy(DepthWallpaperService.this, new Runnable() {
                @Override
                public void run() {
                    updateSensor();
                }
            });
            sensorPolicy.start();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder h) {
            note("surface created, valid=" + (h != null && h.getSurface() != null
                    && h.getSurface().isValid()) + " -- cold load follows");
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder h, int f, int w, int ht) {
        }

        /**
         * The platform re-tasked this engine: it is now a different screen, or is
         * now both. API 34+.
         *
         * <p>This engine object is not destroyed and recreated when the
         * destination changes, so without this a wallpaper moved between surfaces
         * would keep rendering the previous surface's values - the user's picture,
         * at the previous surface's depth, zoom and framing, with no error
         * anywhere. Re-resolving and re-applying is the whole fix; the render
         * values come from {@code Framing} either way, so there is no second path
         * to keep in step.
         *
         * <p>Safe to declare on every level even though the super method arrived
         * in 34: the framework only calls it where it exists, and nothing here
         * references it by super. Calling {@code super} would be the call that
         * needs the version guard, so it is not called - AOSP's implementation is
         * empty.
         */
        @Override
        public void onWallpaperFlagsChanged(int which) {
            note("re-tasked, platform flags=0x" + Integer.toHexString(which));
            resolveSurface();
            if (prefs != null) applyFromPrefs(prefs);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder h) {
            note("surface destroyed");
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            if (visible) {
                // Only a SECOND show glides, never the first: a freshly created
                // engine has nothing on screen to come from, and a from it invents is
                // a from it will visibly snap to.
                //
                // This was the reported artifact, in three steps: the renderer's cold
                // load applies its OWN config (the lock engine's zoom), then this wake
                // armed a glide FROM the home config, whose apply() snapped to it, then
                // the tween animated back to lock. Lock, home, lock -- a double-take
                // on every screen-on. The three pieces were each correct; the
                // sequence was not, which is the same shape as the three ordering bugs
                // before it.
                //
                // The cost, stated rather than hidden: on a device that destroys and
                // rebuilds the engine per screen-on -- and AGENTS.md says this one
                // does -- the lock transition is now a correct instant rather than an
                // ease. A genuine ease there needs the cold load itself to arm the
                // glide before its first frame, which is a real change and not this
                // one.
                // CAPTURE only. Whether this engine is what the user is looking at
                // is NOT decided here, because becoming visible does not mean being
                // seen: the log shows the platform creating a HOME engine at
                // screen-on, reporting visible=true, with onScreen=false -- the
                // keyguard was showing the LOCK window. Claiming visibility here let
                // that phantom engine become "the last thing showing", so the lock
                // engine then glided from home on the lock screen: the double-take.
                pendingGlideFrom = everShown
                        ? DepthWallpaperService.lastVisibleSurface
                        // A FIRST show normally invents nothing, because a from it
                        // guesses is a from it will visibly snap to. But the platform
                        // destroys and rebuilds this engine per screen-on, so on THIS
                        // device the first show is also the unlock transition -- and
                        // refusing to arm it is why the glide disappeared entirely
                        // when the lock engine stopped painting over home.
                        //
                        // The from is not guessed: the keyguard is the platform's own
                        // answer to "which screen was the user just looking at", read
                        // here because this is the deciding moment rather than some
                        // later frame. Keyguard up means the lock screen was what the
                        // eye last saw, so a HOME engine coming back has a real origin
                        // -- the LOCK framing -- and can hold it for its first frame
                        // before easing home. That is what the user asked for: hold the frame, motion, orbit and depth before the unlock glides towards the home config
                        //
                        // Only a HOME engine that arrives as the lock screen leaves.
                        // A lock engine invents nothing -- nobody watches its first
                        // frame, the screen was off -- and a home engine waking with no
                        // departure behind it was not watching the lock screen at all.
                        // No flag and no arming: isSurfaceTransition decides from
                        // isDeviceLocked(), which is the reading that actually tracks
                        // the unlock. Anything armed here would be a second source of
                        // truth for a fact already available at the point of use.
                        : null;
                everShown = true;
                glView.onResume();
                // Ask the platform again rather than trusting the reading taken in
                // onCreate. Whether the flags are published by the time onCreate
                // runs is an OEM question this build cannot check off-device, so
                // the re-read is what makes an early zero self-healing instead of
                // a permanently wrong surface. Becoming visible is the one moment
                // at which the answer cannot be a cached guess, so it is the
                // moment to ask.
                resolveSurface();
                // R-PL4: catch up a rotation that fell due while hidden.
                // Rotation is driven by whatever wakes the pref listener, and a
                // write is not guaranteed — the screen can be off, the studio
                // closed, nothing writing, for a whole interval — so a timed
                // trigger can fall due with nothing there to observe it.
                // Becoming visible is guaranteed to happen, and it is when the
                // user is about to look. Safe from a second trigger:
                // Playlists.advance stamps its own clock, so calling it twice
                // in one interval moves once, and the listener this write
                // wakes finds nothing due and no-ops. Rotation writes the
                // index's OWN selection, so there is no second "current
                // photo" pointer to keep in step. It sits after
                // resolveSurface() because the surface is a gate, not a hint
                // (a lock-only playlist must not rotate for the home engine),
                // and before applyFromPrefs so the apply that follows
                // resolves the rotated selection in this same transition
                // rather than a frame later.
                if (prefs != null) advancePlaylistIfDue(prefs);
                // Re-read the selection now, not only when a pref change happens
                // to arrive while the engine is alive. While hidden the GL thread
                // is paused and does not drain the event queue, so an update
                // posted from the studio could sit there undelivered -- and if it
                // was dropped, only the renderer knew the wallpaper was stale.
                // Becoming visible is the one moment at which the answer cannot
                // be a cached guess, so it is the moment to ask.
                if (renderer != null && prefs != null) applyFromPrefs(prefs);
            } else {
                holdLockFrameForUnlock();
                glView.onPause();
            }
            sensorPolicy.setVisible(visible); // screen off / other app on top
            // Re-check on every show/hide: the user can flip battery saver
            // while the wallpaper is running.
            applyPowerSave();
        }

        /**
         * Hand the OS battery-save state to the sensor, which is the only thing
         * that can act on it.
         *
         * <p>This used to call a renderer setter that three call sites fed and
         * nothing read, so the app noticed battery saver and did nothing about it,
         * at the one moment the answer is worth having. The renderer was the wrong
         * home for it: the draw rate is bounded by the sensor, so there is no frame
         * rate to give up, and a cap would tear the screen. The sensor widens its
         * period instead - see {@link TiltController#setPowerSave(boolean)}.
         */
        private void applyPowerSave() {
            if (tilt != null) tilt.setPowerSave(PowerSave.isOn(DepthWallpaperService.this));
        }

        /** Start/stop the gyro based on visibility and battery, then report. */
        private void updateSensor() {
            applyPowerSave();
            boolean run = sensorPolicy != null && sensorPolicy.shouldRunSensor();
            if (run) {
                if (tilt == null) {
                    tilt = new TiltController(DepthWallpaperService.this,
                            new TiltController.Listener() {
                                @Override
                                public void onTiltChanged() {
                                    renderer.setTilt(tilt.getX(), tilt.getY());
                                    glView.requestRender();
                                }
                            });
                }
                if (!tiltRunning) {
                    tilt.start();
                    tiltRunning = true;
                }
            } else if (tiltRunning) {
                tilt.stop();
                tiltRunning = false;
            }
            writeGyroStatus();
        }

        private void writeGyroStatus() {
            String s;
            if (sensorPolicy == null) {
                s = "gyro off";
            } else if (sensorPolicy.isBatteryLow()) {
                s = "gyro paused (battery " + sensorPolicy.getBatteryPercent() + "%)";
            } else if (sensorPolicy.isCharging()) {
                s = "gyro active (charging, battery " + sensorPolicy.getBatteryPercent() + "%)";
            } else if (!sensorPolicy.isVisible()) {
                s = "gyro paused (not visible)";
            } else if (tilt != null && tilt.isAsleep()) {
                s = "gyro sleeping (idle)";
            } else {
                s = "gyro active";
            }
            getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                    .edit().putString("gyro_status", s).apply();
        }

        @Override
        public void onDestroy() {
            // First, so an unlock arriving during teardown cannot ask a dead
            // engine for its surface.
            engines.remove(this);
            if (prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            }
            if (sensorPolicy != null) sensorPolicy.stop();
            if (tilt != null) tilt.stop();
            if (glView != null) glView.onPause();
            super.onDestroy();
        }
    }
}
