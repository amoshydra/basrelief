package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PointF;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Live studio: the relief preview fills the screen and the image/depth
 * pickers plus intensity/layer-split sliders are overlaid on top, updating
 * the preview in real time (mirrors the web demo's single-screen UI).
 * Everything is written to SharedPreferences, so an active Depth Tilt
 * wallpaper applies the same settings live.
 */
public class MainActivity extends Activity {

    private static final int REQ_IMAGE = 1;
    private static final int REQ_DEPTH = 2;
    public static final int REQ_DEPTHGEN_IMAGE = 3;

    private GLSurfaceView glView;
    private ReliefRenderer renderer;
    private TiltController tilt;
    private SensorPolicy sensorPolicy;
    private SharedPreferences prefs;
    private TextView statusView;
    private LinearLayout panel;
    private Button expandBtn;
    private TextView hintView;
    private DepthGenDialog depthGenDialog;
    private SeekBar zoomBar;
    private TextView zoomLabel;
    private boolean syncingZoom = false;

    /** Last value written to the shared {@code gyro_status} key, to avoid rewriting it per frame. */
    private String lastGyroStatus = null;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if ("status".equals(key) || "gyro_status".equals(key)) refreshStatus();
                }
            };

    // gesture state
    private final SparseArray<PointF> pointers = new SparseArray<>();
    private float lastMidX, lastMidY;
    private float startDist = -1f;
    private float pinchStartZoom = 1f;
    private float downX, downY;
    private long downTime;
    private boolean tapCandidate = false;
    private final float slopPx = 12f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ReliefRenderer.PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);

        // --- fullscreen preview ---
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        ReliefRenderer.enableMSAA(glView); // 4x MSAA: smooths polygon edges
        renderer = new ReliefRenderer(getApplicationContext());
        renderer.setShowOrbitMarker(true); // crosshair in the studio
        // The renderer needs a way back onto the GL thread; only the host has
        // the GLSurfaceView whose queueEvent provides it.
        renderer.setGlDispatch(ReliefRenderer.glExecutor(glView));
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // gestures: tap = orbit center, single-finger drag = manual tilt
        // (no sensor), two-finger = pan + pinch zoom
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e);
            }
        });

        // --- overlay controls (bottom panel) ---
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(180, 11, 14, 20));
        int pad = dp(12);
        panel.setPadding(dp(16), pad, dp(16), pad + dp(6));

        // header: title + minimise button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Basrelief — live preview");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button minBtn = new Button(this);
        minBtn.setText("—");
        minBtn.setAllCaps(false);
        minBtn.setMinimumHeight(dp(32));
        minBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapseUi();
            }
        });
        header.addView(minBtn);
        panel.addView(header);

        // gesture hint (hidden once the user minimises the panel)
        hintView = new TextView(this);
        hintView.setText("Tap: orbit center · 2-finger: pan · pinch: zoom");
        hintView.setTextColor(0x99FFFFFF);
        hintView.setTextSize(11);
        if (prefs.getBoolean("hint_dismissed", false)) hintView.setVisibility(View.GONE);
        panel.addView(hintView);

        sectionLabel("Adjust");

        // 0..100 = fraction of the maximum depth the camera allows at the
        // current zoom. Was 0..200, but the top half was a silent no-op at
        // high zoom because of the z-clamp.
        //
        // One read of the versioned blob feeds every slider, so the panel can
        // never show values that disagree with what the renderer loads.
        final Config initial = ConfigStore.load(prefs, getFilesDir());
        panel.addView(addSlider("Depth", "intensity",
                initial.intensity, 0, 100));
        panel.addView(addSlider("Motion", "motion",
                initial.motion, 0, 100));
        panel.addView(addSlider("Zoom", "zoom",
                initial.zoom, 25, 200));

        Button resetView = makeButton("Reset view", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                    @Override
                    public Config edit(Config c) {
                        return new Config(c.imagePath, c.depthPath, c.intensity, 100, c.motion,
                                0f, 0f, 0f, 0f);
                    }
                });
                setZoomSlider(100);
                glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        renderer.resetView();
                    }
                });
                glView.requestRender();
            }
        });
        resetView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(resetView);

        sectionLabel("Actions");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(makeButton("Image", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_IMAGE);
            }
        }));
        buttons.addView(makeButton("Depth", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_DEPTH);
            }
        }));
        buttons.addView(makeButton("Reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                    @Override
                    public Config edit(Config c) {
                        return new Config(null, null, c.intensity, c.zoom, c.motion,
                                c.orbitX, c.orbitY, c.panX, c.panY);
                    }
                });
                prefs.edit().remove("status").apply();
                applyFiles();
            }
        }));
        buttons.addView(makeButton("Calibrate", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tilt != null) tilt.recalibrate();
                glView.requestRender();
            }
        }));
        panel.addView(buttons);

        // Depth AI: generate a depth map on a LAN ComfyUI instance, then pack
        // it into a self-contained PNG and load it into the studio + wallpaper
        Button depthAiBtn = makeButton("Depth AI (ComfyUI)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDepthGen();
            }
        });
        depthAiBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(depthAiBtn);

        // reliable way to reach the live-wallpaper picker on any launcher
        Button setWallpaper = makeButton("Set as wallpaper", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(MainActivity.this, DepthWallpaperService.class));
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    try {
                        startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                    } catch (ActivityNotFoundException e2) {
                        Toast.makeText(MainActivity.this,
                                "Open System Settings > Wallpaper to set the live wallpaper",
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        setWallpaper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(setWallpaper);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(8), 0, 0);
        panel.addView(statusView);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(panel, panelLp);

        // floating expand handle, shown only while the panel is minimised
        expandBtn = new Button(this);
        expandBtn.setText("＋");
        expandBtn.setAllCaps(false);
        expandBtn.setMinHeight(dp(48));
        expandBtn.setMinWidth(dp(48));
        expandBtn.setVisibility(View.GONE);
        expandBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expandUi();
            }
        });
        FrameLayout.LayoutParams expandLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        expandLp.bottomMargin = dp(16);
        root.addView(expandBtn, expandLp);

        setContentView(root);
        applySystemUi(); // decor view exists only after setContentView

        tilt = new TiltController(getApplicationContext(), new TiltController.Listener() {
            @Override
            public void onTiltChanged() {
                renderer.setTilt(tilt.getX(), tilt.getY());
                glView.requestRender();
                // fires on the sleep/wake transitions too, so the status line
                // tracks idle-sleep without polling
                writeGyroStatus();
            }
        });
        sensorPolicy = new SensorPolicy(getApplicationContext(), new Runnable() {
            @Override
            public void run() {
                updateTilt();
            }
        });
        sensorPolicy.setBatteryGate(false); // studio is foreground — no battery pause
        sensorPolicy.start();
        writeGyroStatus(); // publish our own state, not the wallpaper's
        prefs.registerOnSharedPreferenceChangeListener(statusListener);

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /**
     * A "Share → Depth Tilt" image arrives here. Copy it into private storage
     * (so it survives and the wallpaper can read it by path), set it as the
     * current image, then open the Depth AI dialog so the user can generate a
     * depth map for it if it doesn't already carry one.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)) return;
        final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        // Read + downscale off the UI thread: PNG re-encode of a large photo
        // takes seconds and would be an ANR inline.
        Io.run(this, new Callable<File>() {
            @Override
            public File call() throws Exception {
                byte[] bytes = ImageUtil.downscale(Io.readAll(MainActivity.this, uri),
                        ImageUtil.MAX_SIDE);
                File target = new File(getFilesDir(), "shared_image.png");
                Io.writeFile(target, bytes);
                return target;
            }
        }, new Io.Callback<File>() {
            @Override
            public void onDone(File target, Exception error) {
                if (error != null || target == null) {
                    Toast.makeText(MainActivity.this,
                            "Failed to load shared image: "
                                    + (error == null ? "unknown" : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final String shared = PrefFiles.store(target);
                ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                    @Override
                    public Config edit(Config c) {
                        return new Config(shared, null, c.intensity, c.zoom, c.motion,
                                c.orbitX, c.orbitY, c.panX, c.panY);
                    }
                });
                prefs.edit().remove("status").apply();
                applyFiles();
                Toast.makeText(MainActivity.this,
                        "Shared image loaded \u2014 set as wallpaper, or generate depth",
                        Toast.LENGTH_LONG).show();
                // open the Depth AI page for this image (it may lack depth)
                openDepthGen();
            }
        });
    }

    /** Show the Depth AI dialog, pre-loaded with the current studio image. */
    private void openDepthGen() {
        if (depthGenDialog == null) {
            depthGenDialog = new DepthGenDialog(MainActivity.this,
                    new DepthGenDialog.Callback() {
                        @Override
                        public void onGenerated(String packedPath) {
                            final String packed = new java.io.File(packedPath).getName();
                            ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                                @Override
                                public Config edit(Config c) {
                                    return new Config(packed, null, c.intensity, c.zoom, c.motion,
                                            c.orbitX, c.orbitY, c.panX, c.panY);
                                }
                            });
                            prefs.edit().remove("status").apply();
                            applyFiles();
                            Toast.makeText(MainActivity.this,
                                    "Depth loaded — it's now the wallpaper image",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }
        depthGenDialog.useCurrentImage();
        depthGenDialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Tidy the stored filenames on the UI thread. reloadFromPrefs() (GL
        // thread) only reads them; normalising here keeps pref writes off the
        // GL thread and means a restored legacy absolute path is rewritten as a
        // bare filename on the very next launch.
        ConfigStore.load(prefs, getFilesDir());
        ConfigStore.dropMissingFiles(prefs, getFilesDir(), ConfigStore.load(prefs, getFilesDir()));
        glView.onResume();
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.reloadFromPrefs();
            }
        });
        glView.requestRender();
        refreshStatus();
        sensorPolicy.setVisible(true);
    }

    @Override
    protected void onPause() {
        sensorPolicy.setVisible(false);
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sensorPolicy != null) sensorPolicy.stop();
        prefs.unregisterOnSharedPreferenceChangeListener(statusListener);
        Io.shutdown();
        super.onDestroy();
    }

    /** Start/stop the preview gyro based on visibility and battery. */
    private void updateTilt() {
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.setPowerSave(PowerSave.isOn(MainActivity.this));
            }
        });
        boolean run = sensorPolicy != null && sensorPolicy.shouldRunSensor()
                && tilt != null && tilt.isSupported();
        if (run) {
            tilt.start();
            glView.requestRender();
        } else if (tilt != null) {
            tilt.stop();
        }
        refreshStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi();
    }

    // ------------------------------------------------------------------ fullscreen / ui

    /**
     * System bars are configured once, classically: the status bar stays
     * visible but transparent (content draws behind it via LAYOUT_FULLSCREEN),
     * the navigation bar is hidden, and the layout flags are stable — so
     * nothing here ever triggers a window resize. Minimise/expand only toggles
     * our own panel and never touches the system bars.
     */
    private void applySystemUi() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void collapseUi() {
        if (hintView != null && hintView.getVisibility() == View.VISIBLE) {
            prefs.edit().putBoolean("hint_dismissed", true).apply();
            hintView.setVisibility(View.GONE);
        }
        panel.setVisibility(View.GONE);
        expandBtn.setVisibility(View.VISIBLE);
    }

    private void expandUi() {
        expandBtn.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ gestures

    private boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pointers.put(id, new PointF(e.getX(index), e.getY(index)));
                downX = e.getX();
                downY = e.getY();
                downTime = SystemClock.uptimeMillis();
                tapCandidate = true;
                startDist = -1f;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                pointers.put(id, new PointF(e.getX(index), e.getY(index)));
                tapCandidate = false;
                if (pointers.size() == 2) {
                    pinchStartZoom = renderer.getZoom();
                    startDist = pointerDistance();
                    lastMidX = pointerMidX();
                    lastMidY = pointerMidY();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < pointers.size(); i++) {
                    int pid = pointers.keyAt(i);
                    int pi = e.findPointerIndex(pid);
                    if (pi >= 0) pointers.valueAt(i).set(e.getX(pi), e.getY(pi));
                }
                if (tapCandidate) {
                    float moved = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                    if (moved > slopPx) tapCandidate = false;
                }

                if (pointers.size() >= 2) {
                    // pinch zoom (relative to two-finger start, no drift)
                    if (startDist > 0f) {
                        float newZoom = pinchStartZoom * (pointerDistance() / startDist);
                        renderer.setZoom(newZoom);
                        int pct = Math.round(renderer.getZoom() * 100f);
                        final int z = pct;
                        ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                            @Override
                            public Config edit(Config c) {
                                return c.withZoom(z);
                            }
                        });
                        setZoomSlider(pct);
                    }
                    // pan by midpoint delta
                    float mx = pointerMidX();
                    float my = pointerMidY();
                    renderer.panBy(mx - lastMidX, my - lastMidY);
                    lastMidX = mx;
                    lastMidY = my;
                    glView.requestRender();
                } else if (pointers.size() == 1 && !tilt.isSupported()) {
                    // single-finger drag = manual tilt fallback (no sensor)
                    renderer.setTilt(
                            clamp((e.getX() / glView.getWidth()) * 2f - 1f),
                            clamp((e.getY() / glView.getHeight()) * 2f - 1f));
                    glView.requestRender();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pointers.remove(id);
                tapCandidate = false;
                startDist = -1f;
                if (pointers.size() == 1) {
                    lastMidX = pointerMidX();
                    lastMidY = pointerMidY();
                }
                renderer.savePanToPrefs(); // persist pan state (volatile reads, UI-thread safe)
                return true;

            case MotionEvent.ACTION_UP:
                if (pointers.size() == 1 && tapCandidate) {
                    float moved = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                    long held = SystemClock.uptimeMillis() - downTime;
                    if (moved < slopPx && held < 300) {
                        final float fx = e.getX();
                        final float fy = e.getY();
                        final int fw = glView.getWidth();
                        final int fh = glView.getHeight();
                        // unproject on the GL thread so it uses the same view
                        // matrices as the renderer (no UI/GL race), then persist
                        glView.queueEvent(new Runnable() {
                            @Override
                            public void run() {
                                renderer.screenToOrbit(fx, fy, fw, fh);
                                renderer.saveViewToPrefs();
                            }
                        });
                        glView.requestRender();
                    }
                }
                pointers.remove(id);
                tapCandidate = false;
                renderer.savePanToPrefs(); // persist pan state
                return true;

            case MotionEvent.ACTION_CANCEL:
                pointers.clear();
                tapCandidate = false;
                startDist = -1f;
                renderer.savePanToPrefs();
                return true;
        }
        return true;
    }

    private float pointerDistance() {
        if (pointers.size() < 2) return 0f;
        PointF a = pointers.valueAt(0);
        PointF b = pointers.valueAt(1);
        return (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private float pointerMidX() {
        if (pointers.size() == 0) return 0f;
        float sum = 0f;
        for (int i = 0; i < pointers.size(); i++) sum += pointers.valueAt(i).x;
        return sum / pointers.size();
    }

    private float pointerMidY() {
        if (pointers.size() == 0) return 0f;
        float sum = 0f;
        for (int i = 0; i < pointers.size(); i++) sum += pointers.valueAt(i).y;
        return sum / pointers.size();
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    // ------------------------------------------------------------------ controls

    private void sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x88FFFFFF);
        tv.setTextSize(11);
        tv.setLetterSpacing(0.15f);
        tv.setPadding(0, dp(10), 0, dp(2));
        panel.addView(tv);
    }

    private String formatValue(String key, int value) {
        if (key.equals("zoom")) return String.format(Locale.US, "%.1f×", value / 100f);
        if (key.equals("intensity")) return value + "%";
        if (key.equals("motion")) return value + "%";
        return String.valueOf(value);
    }

    private View addSlider(final String label, final String key, int initial,
                           int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        final TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setText(label + ": " + formatValue(key, initial));
        row.addView(tv);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        final boolean isZoom = "zoom".equals(key);
        final boolean isMotion = "motion".equals(key);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncingZoom) return;
                int value = progress + min;
                tv.setText(label + ": " + formatValue(key, value));
                final int stored = value;
                final int v = stored;
                final String k = key;
                ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                    @Override
                    public Config edit(Config c) {
                        if ("intensity".equals(k)) return c.withIntensity(v);
                        if ("zoom".equals(k)) return c.withZoom(v);
                        if ("motion".equals(k)) return c.withMotion(v);
                        return c;
                    }
                });
                glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        if (isZoom) {
                            renderer.setZoom(stored / 100f);
                        } else if (isMotion) {
                            renderer.setMotion(stored / 100f * 3f);
                        } else {
                            renderer.setDepth(stored / 100f);
                        }
                    }
                });
                glView.requestRender();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar);
        if (isZoom) {
            zoomBar = bar;
            zoomLabel = tv;
        }
        return row;
    }

    /** Update the zoom slider + label to match the current renderer zoom. */
    private void setZoomSlider(int percent) {
        if (zoomBar == null) return;
        int v = Math.max(25, Math.min(200, percent));
        syncingZoom = true;
        try {
            zoomBar.setProgress(v - 25); // min = 25
            if (zoomLabel != null) zoomLabel.setText("Zoom: " + formatValue("zoom", v));
        } finally {
            syncingZoom = false;
        }
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinimumHeight(dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = 1f;
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    // ------------------------------------------------------------------ picking

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DEPTHGEN_IMAGE) {
            if (depthGenDialog != null) depthGenDialog.onPickedImage(data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        final boolean isImage = requestCode == REQ_IMAGE;
        final File target = new File(getFilesDir(),
                "custom_" + (isImage ? "image" : "depth") + ".png");
        final Uri uri = data.getData();
        // Off the UI thread: huge images (e.g. 3000x4000) are decoded and
        // re-encoded as PNG here, which is slow enough to trip an ANR.
        Io.run(this, new Callable<File>() {
            @Override
            public File call() throws Exception {
                byte[] raw = Io.readAll(MainActivity.this, uri);
                // huge images are downscaled before use; depth maps are small
                // and left alone
                byte[] bytes = isImage
                        ? ImageUtil.downscale(raw, ImageUtil.MAX_SIDE)
                        : raw;
                Io.writeFile(target, bytes);
                return target;
            }
        }, new Io.Callback<File>() {
            @Override
            public void onDone(File done, Exception error) {
                if (error != null || done == null) {
                    Toast.makeText(MainActivity.this,
                            "Failed to read file: "
                                    + (error == null ? "unknown" : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                final boolean isImg = isImage;
                final String stored = PrefFiles.store(target);
                ConfigStore.update(prefs, getFilesDir(), new ConfigStore.Editor() {
                    @Override
                    public Config edit(Config c) {
                        return isImg ? c.withFiles(stored, null) : c.withFiles(c.imagePath, stored);
                    }
                });
                Toast.makeText(MainActivity.this, "Picked \u2014 updating preview\u2026",
                        Toast.LENGTH_SHORT).show();
                applyFiles();
            }
        });
    }

    private void applyFiles() {
        // Resolve relative names, and drop any that no longer exist: after a
        // restore onto a different device the file may simply be absent, and a
        // dangling value would otherwise be retried on every load.
        Config c = ConfigStore.load(prefs, getFilesDir());
        c = ConfigStore.dropMissingFiles(prefs, getFilesDir(), c);
        final java.io.File imgF = PrefFiles.resolve(getFilesDir(), c.imagePath);
        final java.io.File depF = PrefFiles.resolve(getFilesDir(), c.depthPath);
        final String img = imgF == null ? null : imgF.getAbsolutePath();
        final String dep = depF == null ? null : depF.getAbsolutePath();
        // Async for the same reason as the wallpaper: a synchronous load stalls
        // the GL thread for the length of a full decode plus mesh build.
        renderer.loadPairAsync(img, dep);
        glView.requestRender();
        refreshStatus();
    }

    /**
     * Resolve a stored image/depth, normalising the pref as a side effect.
     *
     * <p>If the stored value is a legacy absolute path that resolved only by
     * the filesDir fallback, rewrite it as a bare filename so the stale path
     * does not linger in prefs (and in the next backup). If it cannot be
     * resolved at all, drop it so we stop retrying a file that is gone.
     *
     * @return the absolute path, or null if there is nothing usable
     */
    private String resolveOrClear(String key) {
        String stored = prefs.getString(key, null);
        java.io.File f = PrefFiles.resolve(getFilesDir(), stored);
        if (f == null) {
            if (stored != null) prefs.edit().remove(key).apply();
            return null;
        }
        String canonical = f.getName();
        if (!canonical.equals(stored)) {
            prefs.edit().putString(key, canonical).apply();
        }
        return f.getAbsolutePath();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        // May be invoked from the sensor thread (gyro_status is written when the
        // tilt sleeps or wakes), and SharedPreferences fires its change listeners
        // on whichever thread did the write. Touching a View from there is a
        // crash waiting to happen, so always hop to the UI thread.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            });
            return;
        }
        String s = prefs.getString("status", "loading…");
        String g = prefs.getString("gyro_status", "");
        if (!g.isEmpty()) s += "  |  " + g;
        statusView.setText("Status: " + s);
    }

    /**
     * Publish the STUDIO's own gyro state to the shared {@code gyro_status} key.
     *
     * <p>This key used to be written only by the wallpaper service, so the studio
     * displayed the wallpaper's state — which is always "paused (not visible)"
     * while the studio is in the foreground. The status line therefore reported
     * that a working preview gyro was dead, which is the opposite of the truth
     * and precisely the wrong thing to see in the one screen used to check that
     * tilt works.
     *
     * <p>Whichever surface is in front owns the key. The studio's SensorPolicy
     * runs with the battery gate disabled (it is foreground), so the battery
     * states the wallpaper reports do not apply here.
     *
     * <p>Only writes on change: this is called from the tilt callback, and a
     * SharedPreferences write per sensor frame would be its own bug.
     */
    private void writeGyroStatus() {
        String s;
        if (tilt == null) {
            s = "gyro off";
        } else if (tilt.isAsleep()) {
            s = "gyro sleeping (idle)";
        } else {
            s = "gyro active";
        }
        if (s.equals(lastGyroStatus)) return;
        lastGyroStatus = s;
        prefs.edit().putString("gyro_status", s).apply();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
