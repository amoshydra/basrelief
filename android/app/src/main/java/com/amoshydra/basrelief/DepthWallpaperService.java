package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * The live wallpaper. The GLSurfaceView renders into the wallpaper surface
 * by overriding getHolder() to return the engine's surface holder (the
 * Learn OpenGL ES wallpaper pattern).
 */
public class DepthWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new DepthEngine();
    }

    /** Null-safe path comparison: both sides are null for the bundled demo. */
    private static boolean sameFile(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
        private boolean tiltRunning = false;
        /** Last image/depth handed to the renderer, so a Depth nudge does not re-decode. */
        private String lastImage, lastDepth;
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
                        if (!Config.KEY.equals(key) && !Library.KEY.equals(key)) return;
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
        private void applyFromPrefs(SharedPreferences p) {
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
            // Resolved once, on the calling thread, and applied on the GL thread.
            // queueEvent is the one thread hop the renderer allows (see the
            // threading table in AGENTS.md).
            final Framing framing = Framing.of(state);
            glView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    renderer.apply(framing);
                    // Only reload when the file actually changed; re-decoding on
                    // every Depth nudge would drop a frame for nothing. Compared
                    // through a null-safe helper, because both are null for the
                    // bundled demo and a bare img.equals(last) threw on exactly
                    // that path.
                    if (!sameFile(img, lastImage) || !sameFile(dep, lastDepth)) {
                        lastImage = img;
                        lastDepth = dep;
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
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder h, int f, int w, int ht) {
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder h) {
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                glView.onResume();
            } else {
                glView.onPause();
            }
            sensorPolicy.setVisible(visible); // screen off / other app on top
            // Re-check on every show/hide: the user can flip battery saver
            // while the wallpaper is running.
            if (renderer != null) {
                renderer.setPowerSave(PowerSave.isOn(DepthWallpaperService.this));
            }
        }

        /** Start/stop the gyro based on visibility and battery, then report. */
        private void updateSensor() {
            if (renderer != null) {
                renderer.setPowerSave(PowerSave.isOn(DepthWallpaperService.this));
            }
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
