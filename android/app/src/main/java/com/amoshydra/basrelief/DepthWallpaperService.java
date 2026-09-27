package com.amoshydra.basrelief;

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
        private boolean loaded = false;
        private SharedPreferences prefs;
        private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                        if ("intensity".equals(key) || "zoom".equals(key) || "motion".equals(key)) {
                            final int inten = p.getInt("intensity", 90);
                            final int zoomv = p.getInt("zoom", 100);
                            final int motionv = p.getInt("motion", 33);
                            glView.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    renderer.setIntensity(inten / 100f);
                                    renderer.setZoom(zoomv / 100f);
                                    renderer.setMotion(motionv / 100f * 3f);
                                }
                            });
                            glView.requestRender();
                        } else if ("orbit_x".equals(key) || "orbit_y".equals(key)
                                || "pan_x".equals(key) || "pan_y".equals(key)) {
                            final float ox = p.getFloat("orbit_x", 0f);
                            final float oy = p.getFloat("orbit_y", 0f);
                            final float px = p.getFloat("pan_x", 0f);
                            final float py = p.getFloat("pan_y", 0f);
                            glView.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    renderer.setOrbit(ox, oy);
                                    renderer.setPan(px, py);
                                }
                            });
                            glView.requestRender();
                        } else if ("image_path".equals(key) || "depth_path".equals(key)) {
                            final String img = p.getString("image_path", null);
                            final String dep = p.getString("depth_path", null);
                            glView.queueEvent(new Runnable() {
                                @Override
                                public void run() {
                                    renderer.loadPair(img, dep);
                                }
                            });
                            glView.requestRender();
                        }
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

        /**
         * Load the configured image/depth once, when a real surface exists.
         *
         * <p>This has to be explicit. The only other path that loads anything is
         * {@link #prefListener}, which fires when a preference CHANGES — and the
         * wallpaper never writes preferences (it is read-only; the studio owns
         * them). So on a genuine cold start (reboot, or the engine recreated
         * before the studio has ever been opened) nothing was ever loaded,
         * {@code textureColor} stayed -1 and the wallpaper rendered pure black.
         *
         * <p>It only appeared to work because the wallpaper service runs in the
         * SAME process as the studio (no android:process), so opening the studio
         * writes a preference and the listener happens to fire.
         *
         * <p>{@code onSurfaceChanged} is the right hook: it means a surface
         * really exists, so the GL thread can create textures. Guarded by
         * {@code loaded} so we rebuild the mesh only once per engine.
         */
        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            if (loaded || glView == null) return;
            loaded = true;
            glView.queueEvent(new Runnable() {
                @Override
                public void run() {
                    renderer.reloadFromPrefs();
                }
            });
            glView.requestRender();
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
        }

        /** Start/stop the gyro based on visibility and battery, then report. */
        private void updateSensor() {
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
