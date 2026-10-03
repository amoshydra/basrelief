package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

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
     * Advance the rotating playlist, if one is due on this surface.
     *
     * <p>Rotation writes the index's OWN selection -- the same two calls a user's
     * tap makes -- and then this engine re-resolves from that index like any other
     * change. So there is no second "current photo" and nothing to keep in step: the
     * playlist decides, the index records, and the engine follows.
     *
     * <p>Deliberately idempotent and stamped. {@code advance} writes the playlists
     * blob back with the new clock, so calling it twice in the same interval is a
     * no-op rather than two photos forward -- which is why the listener above can
     * fire on its own write without a guard.
     *
     * <p>An event trigger is never due on a clock, so this does nothing for
     * {@code on_pickup}; that path is deferred and the trigger stays off.
     */
    private void advancePlaylistIfDue(SharedPreferences p) {
        try {
            Playlists playlists = Playlists.parse(p.getString(Playlists.KEY, null));
            Library index = Library.parse(p.getString(Library.KEY, null),
                    android.content.pm.PackageManager.PERMISSION_GRANTED >= 0
                            ? motionFallback(p) : 33);
            SurfaceResolver which = surface;   // read once: a re-task can change it
            Playlists.Result result = Playlists.advance(playlists, index,
                    which.surface(), System.currentTimeMillis());
            if (result.selectedId == null) return;      // nothing was due
            // The index is the single pointer, so this is the ONLY write.
            LibraryStore.saveNow(p, result.library);
            PlaylistScreen.PlaylistStore.save(p, result.playlists);
            android.util.Log.i("Basrelief", "playlist advanced to "
                    + result.selectedId);
        } catch (Exception e) {
            // A rotation must never take the wallpaper down with it. The engine
            // keeps drawing whatever it already had.
            android.util.Log.w("Basrelief", "playlist advance failed: " + e);
        }
    }

    /** Motion's old home, needed only to migrate a pre-v3 index. Never written. */
    private int motionFallback(SharedPreferences p) {
        try {
            return p.getString(Config.KEY, null) == null ? 33
                    : com.amoshydra.basrelief.core.store.Config
                            .parse(p.getString(Config.KEY, null)).motion;
        } catch (Exception e) {
            return 33;
        }
    }

    /**
     * Keep the renderer's writes on the engine's own surface.
     *
     * <p>A lock engine writes to Lock and a home engine to Home, from the same
     * {@code SurfaceResolver} that already decides which engine this is. Reading it
     * here rather than passing it per call keeps the renderer's write path identical
     * to the studio's, which is what stops the two surfaces from drifting.
     */
    /**
     * The surface to glide FROM, or null when this resolve is not a surface change.
     *
     * <p>Only when this engine was hidden and is becoming visible: that is the one
     * event that means the user was looking at the other screen a moment ago -- a lock
     * engine waking as the phone locks, a home engine waking as it unlocks. A
     * same-surface resolve (a slider moved, the index changed) is not a transition,
     * and gliding it would smear every adjustment.
     */
    private Framing glideFromSurface(LibraryItem active, SurfaceResolver resolved) {
        Surface from = pendingGlideFrom;
        pendingGlideFrom = null;             // consumed: one glide per wake
        if (from == null) return null;       // not a wake: apply, do not smear
        // The surface that was showing is the one to come from. NOT "the other
        // surface": that guess is what made every wake glide from a config nothing
        // was rendering. Same surface showing before and now -- returning to the
        // launcher, a same-surface wake -- is not a transition and must not ease.
        if (from == resolved.surface()) return null;
        if (!resolved.supportsPerSurfaceLock()) return null; // no second engine, no other side
        return Framing.of(active, from);
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
            final AppState state = AppState.load(p, getFilesDir());
            final LibraryItem active = state.activeItem();
            final String img;
            final String dep;
            if (active == null || active.isBundled()) {
                // The marble bust: no file, so res/raw. Null here is a normal
                // state and not a missing photo.
                img = null;
                dep = null;
            } else {
                java.io.File imgF = PrefFiles.resolve(getFilesDir(), active.imageName);
                java.io.File depF = PrefFiles.resolve(getFilesDir(), active.depthName);
                img = imgF == null ? null : imgF.getAbsolutePath();
                dep = depF == null ? null : depF.getAbsolutePath();
            }
            // Which screen this engine is, and therefore whose settings to render.
            // The bundled demo and an item are both read through the one
            // resolution point, so the two engines of a two-engine setup cannot
            // disagree about what a photograph looks like.
            final SurfaceResolver resolved = surface;
            // Resolved once, on the calling thread, and applied on the GL thread.
            // queueEvent is the one thread hop the renderer allows (see the
            // threading table in AGENTS.md).
            final Framing framing = Framing.of(active, resolved.surface());
            // The OTHER surface's framing, for the glide: becoming visible after a
            // hidden period means the user was just looking at the other screen, so
            // the first frame this engine draws should be the one they were looking
            // at, easing into this surface's own config over the one glide rate --
            // "a switch, not a cut".
            final Framing otherSide = glideFromSurface(active, resolved);
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
                    + " item=" + (active == null ? "bundled" : active.displayName())
                    + " want=" + (img == null ? "res/raw" : new java.io.File(img).getName())
                    + " onScreen=" + renderer.showingSamePair(img, dep)
                    + " at=" + (System.currentTimeMillis() % 1000000L);
            android.util.Log.i("Basrelief", "wallpaper: " + diagnostic);
            // Also into prefs, because the studio shows this on screen. Logcat
            // needs a cable and a filter; this needs only opening the app, which
            // is the difference between a diagnostic that gets read and one that
            // gets forgotten.
            prefs.edit().putString("wallpaper_debug", diagnostic).commit();
            // The glide is one rate: re-centring, a surface switch, and a playlist
            // advance are one motion, and three numbers nobody can predict is the
            // thing the setting exists to prevent.
            final float spanMs = glideSpanMs(p);
            glView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    // Becoming visible glides FROM the other surface's config; a
                    // same-surface re-resolve (a slider moved, a photo changed)
                    // applies instantly, because there is no other config to come
                    // from and an instant apply is the honest render of it.
                    if (otherSide != null) {
                        renderer.glide(otherSide, framing, spanMs);
                    } else {
                        renderer.apply(framing);
                    }
                    // Only reload when what is ON SCREEN differs from what should
                    // be. The renderer knows that; this class used to keep its own
                    // copy updated at request time, so a load that never landed
                    // still counted as done and the wallpaper stayed on the old
                    // photo until the process died. Re-decoding on every Depth
                    // nudge would drop a frame for nothing, so the check earns
                    // its keep.
                    if (!renderer.showingSamePair(img, dep)) {
                        // Async: the read/decode/mesh-build must not happen
                        // between two frames of the wallpaper, or it visibly
                        // freezes for seconds.
                        renderer.loadPairAsync(img, dep);
                    }
                }
            });
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
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
            glView = new WallpaperGLSurfaceView(DepthWallpaperService.this);
            glView.setEGLContextClientVersion(2);
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
                if (everShown) {
                    pendingGlideFrom = DepthWallpaperService.lastVisibleSurface;
                } else {
                    pendingGlideFrom = null;
                    everShown = true;
                }
                DepthWallpaperService.lastVisibleSurface = surface.surface();
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
