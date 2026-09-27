package com.amoshydra.basrelief;

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
                        // The studio writes the whole config as one versioned
                        // blob, so there is a single key to watch and a single
                        // consistent snapshot to apply. The old per-key branches
                        // could observe half of a change.
                        if (!Config.KEY.equals(key)) return;
                        final Config c = ConfigStore.load(p, getFilesDir());
                        final java.io.File imgF = PrefFiles.resolve(getFilesDir(), c.imagePath);
                        final java.io.File depF = PrefFiles.resolve(getFilesDir(), c.depthPath);
                        final String img = imgF == null ? null : imgF.getAbsolutePath();
                        final String dep = depF == null ? null : depF.getAbsolutePath();
                        glView.queueEvent(new Runnable() {
                            @Override
                            public void run() {
                                renderer.setDepth(c.depthFraction());
                                renderer.setZoom(c.zoomScale());
                                renderer.setMotion(c.motionScale());
                                renderer.setOrbit(c.orbitX, c.orbitY);
                                renderer.setPan(c.panX, c.panY);
                                // Only reload the image when the file actually
                                // changed; re-decoding on every Depth nudge
                                // would drop a frame for nothing.
                                if (!img.equals(lastImage) || !dep.equals(lastDepth)) {
                                    lastImage = img;
                                    lastDepth = dep;
                                    // Async: the read/decode/mesh-build must not
                                    // happen between two frames of the wallpaper,
                                    // or it visibly freezes for seconds.
                                    renderer.loadPairAsync(img, dep);
                                }
                            }
                        });
                        glView.requestRender();
                    }
                };

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
            prefs = getSharedPreferences(ReliefRenderer.PREFS, Context.MODE_PRIVATE);
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
            getSharedPreferences(ReliefRenderer.PREFS, Context.MODE_PRIVATE)
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
