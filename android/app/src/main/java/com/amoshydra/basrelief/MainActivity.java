package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.Config;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.OpenableColumns;
import android.database.Cursor;
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
    SharedPreferences prefs;
    private TextView statusView;
    private LinearLayout panel;
    private Button expandBtn;
    private TextView hintView;
    private DepthGenDialog depthGenDialog;
    private SeekBar zoomBar;
    private TextView zoomLabel;

    /**
     * The container the gallery overlay is added to.
     *
     * <p>A field because the gallery is opened long after {@code onCreate} has
     * returned, and a local would not survive to be useful. The preview stays
     * visible behind it, which is the point: picking a framing means watching the
     * result, not choosing from a list and then finding out.
     */
    private FrameLayout rootLayout;
    private Gallery gallery;

    /** Slider handles by key, so a selection change can repopulate them. */
    private final java.util.Map<String, SeekBar> bars = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> barLabels = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> barMin = new java.util.HashMap<>();
    /**
     * The label each slider was built with, by key.
     *
     * <p>Kept because the key is a storage name, not something to show. Without
     * this a refresh relabelled Depth to "intensity", which is the field name
     * from the blob and means nothing to the person using the app.
     */
    private final java.util.Map<String, String> barNames = new java.util.HashMap<>();
    private boolean syncingZoom = false;

    /** Last value written to the shared {@code gyro_status} key, to avoid rewriting it per frame. */
    private String lastGyroStatus = null;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if ("status".equals(key) || "gyro_status".equals(key)
                            || "wallpaper_debug".equals(key)) {
                        refreshStatus();
                    }
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
        prefs = getSharedPreferences(AppState.PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        gallery = new Gallery(this, root);

        // --- fullscreen preview ---
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        ReliefRenderer.enableMSAA(glView); // 4x MSAA: smooths polygon edges
        renderer = new ReliefRenderer(getApplicationContext());
        renderer.setShowOrbitMarker(true); // crosshair in the studio
        // The renderer needs a way back onto the GL thread; only the host has
        // the GLSurfaceView whose queueEvent provides it.
        renderer.setGlDispatch(ReliefRenderer.glExecutor(glView));
        renderer.setRenderRequester(new Runnable() {
            @Override
            public void run() {
                glView.requestRender();
            }
        });
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
        // One read feeds every slider, so the panel can never show values that
        // disagree with what the renderer loads. Depth and Zoom come off the
        // selected item, because they are properties of a photograph; Motion
        // comes off the config, because it is a property of this device and how
        // it is held.
        final AppState initial = AppState.load(prefs, getFilesDir());
        final LibraryItem initialItem = initial.activeItem();
        panel.addView(addSlider("Depth", "intensity",
                initialItem == null ? LibraryItem.DEFAULT_INTENSITY : initialItem.intensity, 0, 100));
        panel.addView(addSlider("Motion", "motion",
                initial.config.motion, 0, 100));
        panel.addView(addSlider("Zoom", "zoom",
                initialItem == null ? LibraryItem.DEFAULT_ZOOM : initialItem.zoom, 25, 200));

        Button resetView = makeButton("Reset view", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Framing and Zoom belong to the photo, so this resets the photo
                // and leaves every other one exactly as it was.
                final String id = AppState.load(prefs, getFilesDir()).activeId();
                if (id != null) {
                    LibraryStore.editItem(prefs, id, new LibraryStore.Editor() {
                        @Override
                        public LibraryItem edit(LibraryItem item) {
                            return item.withZoom(LibraryItem.DEFAULT_ZOOM)
                                    .withOrbit(0f, 0f).withPan(0f, 0f);
                        }
                    });
                }
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
        // Replaces the old Reset button, which cleared the image to fall back to
        // the bundled demo. The marble bust is a row in the gallery now, so
        // choosing it is a selection like any other -- and the reset it used to
        // perform is one tap, in a list that also says what everything else is.
        buttons.addView(makeButton("Gallery", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gallery.show();
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
                        DepthWallpaperService.wallpaperComponent(MainActivity.this));
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
        // The name the sending app showed, so the gallery row is recognisable.
        final String pickedName = displayNameOf(uri);
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
                // A shared image is a new item, not a replacement: sharing three
                // photos should leave three rows, and the two already in the
                // gallery must not lose their depth maps and framing to the third.
                LibraryItem shared = LibraryStore.add(prefs, PrefFiles.store(target), null, pickedName);
                prefs.edit().remove("status").apply();
                refreshSliders();
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
                            // Attach it to the photo it was generated FOR, not to
                            // whatever happens to be selected when the request
                            // comes back -- which may be a different photo by
                            // then, and would then carry the wrong depth map.
                            String id = AppState.load(prefs, getFilesDir()).activeId();
                            if (id != null) {
                                LibraryStore.editItem(prefs, id, new LibraryStore.Editor() {
                                    @Override
                                    public LibraryItem edit(LibraryItem item) {
                                        return item.withDepth(packed);
                                    }
                                });
                            }
                            prefs.edit().remove("status").apply();
                            applyFiles();
                            Toast.makeText(MainActivity.this,
                                    "Depth added to this photo", Toast.LENGTH_LONG).show();
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
        // One load covers both migrations and the self-heal, and writes only if
        // something actually needed changing.
        AppState.load(prefs, getFilesDir());
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
                        // Zoom belongs to the photo, so a pinch frames this one
                        // and leaves every other photo at its own zoom.
                        String zoomId = AppState.load(prefs, getFilesDir()).activeId();
                        if (zoomId != null) {
                            LibraryStore.editItem(prefs, zoomId, new LibraryStore.Editor() {
                                @Override
                                public LibraryItem edit(LibraryItem item) {
                                    return item.withZoom(z);
                                }
                            });
                        }
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
                // Preview only. Persisting here would mean a drag writes
                // synchronously on the UI thread once per pixel of travel, and
                // each write re-reads and re-validates the whole gallery --
                // a few hundred stat calls for one gesture. The label and the
                // renderer still follow the finger, so nothing looks laggy; the
                // write happens once, on release.
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
                persist(key, seekBar.getProgress() + min);
            }
        });
        row.addView(bar);
        bars.put(key, bar);
        barLabels.put(key, tv);
        barMin.put(key, min);
        barNames.put(key, label);
        if (isZoom) {
            zoomBar = bar;
            zoomLabel = tv;
        }
        return row;
    }

    /**
     * Write one slider's final value, once, on release.
     *
     * <p>Motion goes to the config and Depth/Zoom to the selected item, because
     * only Motion is a property of this device rather than of a photograph. The
     * item's id is read here rather than captured when the slider was built, so a
     * slider left over from a previous selection writes to whatever is on screen
     * now -- which is what the user was looking at when they let go.
     */
    private void persist(final String key, final int value) {
        if ("motion".equals(key)) {
            ConfigStore.setMotion(prefs, value);
            return;
        }
        // activeId() is NOT null for the bundled item -- it returns the reserved
        // id -- so a null check here let every slider write to it. Those values
        // were then stored, displayed, and ignored by Framing, which renders the
        // demo at the defaults. A control that shows a number the renderer never
        // uses is worse than a control that is visibly switched off, so the
        // sliders are disabled for this item in refreshSliders.
        AppState state = AppState.load(prefs, getFilesDir());
        if (!state.isEditableItem()) return;
        final boolean isDepth = "intensity".equals(key);
        LibraryStore.editItem(prefs, state.activeId(), new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                return isDepth ? item.withIntensity(value) : item.withZoom(value);
            }
        });
    }

    /**
     * Repopulate the sliders from what is actually selected.
     *
     * <p>Called after a gallery selection, because Depth and Zoom belong to the
     * photo: selecting a portrait after a landscape has to show the portrait's
     * numbers, and leaving the previous ones on screen would invite the user to
     * drag a slider they thought was still editing the photo they were just
     * looking at.
     *
     * <p>Guarded by {@code syncingZoom} for every bar, not just Zoom. Without it,
     * {@code setProgress} fires {@code onProgressChanged}, which writes straight
     * back to storage -- so selecting a photo would silently rewrite the
     * previously selected one with the values it was already showing.
     */
    void refreshSliders() {
        AppState state = AppState.load(prefs, getFilesDir());
        LibraryItem item = state.activeItem();
        // Motion is always editable -- it is not a property of a photograph.
        // Depth and Zoom are not, when the bundled demo is showing, because the
        // renderer draws it at the defaults regardless of what the index holds.
        // Leaving the sliders live would show a number that does nothing, which
        // is the same class of lie as a status line claiming content that is not
        // there, and much easier to hit by accident.
        boolean editable = state.isEditableItem();
        syncingZoom = true;
        try {
            setBar("intensity", item == null ? LibraryItem.DEFAULT_INTENSITY : item.intensity,
                    editable);
            setBar("zoom", item == null ? LibraryItem.DEFAULT_ZOOM : item.zoom, editable);
            setBar("motion", state.config.motion, true);
        } finally {
            syncingZoom = false;
        }
    }

    private void setBar(String key, int value, boolean enabled) {
        SeekBar bar = bars.get(key);
        if (bar == null) return;
        bar.setEnabled(enabled);
        bar.setAlpha(enabled ? 1f : 0.4f);
        Integer min = barMin.get(key);
        int v = Math.max(min == null ? 0 : min, Math.min(bar.getMax() + (min == null ? 0 : min), value));
        bar.setProgress(v - (min == null ? 0 : min));
        TextView tv = barLabels.get(key);
        String name = barNames.get(key);
        if (tv != null) tv.setText((name == null ? key : name) + ": " + formatValue(key, v));
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

    /**
     * A button that fills an equal share of a horizontal row.
     *
     * <p>The default everywhere in the studio, because the Actions row is four
     * commands that deserve equal weight. Gallery rows want {@link
     * #makeButton(String, View.OnClickListener, int, float)} with WRAP_CONTENT
     * instead -- a full-width menu button would sit on top of the row's own
     * label, which is exactly what happened the first time.
     */
    Button makeButton(String text, View.OnClickListener listener) {
        return makeButton(text, listener, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
    }

    /**
     * @param width  {@code MATCH_PARENT} to share a row equally, or
     *               {@code WRAP_CONTENT} to hug the label
     * @param weight 1f to share leftover space, 0f to take only what it needs
     */
    Button makeButton(String text, View.OnClickListener listener, int width, float weight) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinimumHeight(dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
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
        // A picked IMAGE starts a new gallery item, so its filename has to be
        // derived from an id that does not exist yet; a picked DEPTH belongs to
        // the photo already on screen, so it reuses that item's id. The old
        // single pair, custom_image.png, was overwritten by the next import --
        // which is why two items could never coexist on disk.
        final String newId = isImage ? LibraryItem.newId() : null;
        // What the user calls this photo, so a gallery row does not show the UUID
        // that the on-disk name has to be derived from.
        final String pickedName = isImage ? displayNameOf(data.getData()) : null;
        String active = isImage ? null : AppState.load(prefs, getFilesDir()).activeId();
        if (!isImage && (active == null || Library.BUNDLED_ID.equals(active))) {
            // The bundled demo ships with its own depth map, and it is not
            // deletable or editable -- so a depth map picked here would have
            // nowhere to live. Saying so is better than silently writing a file
            // no row will ever read.
            Toast.makeText(this, "Pick a photo from the gallery first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String depthForId = active;
        final File target = new File(getFilesDir(), isImage
                ? LibraryItem.imageFileName(newId)
                : LibraryItem.depthFileName(depthForId == null ? "orphan" : depthForId));
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
                if (isImage) {
                    // A new item, selected immediately: the user picked a photo in
                    // order to see it, and the studio is the live preview, so
                    // showing the previous one while the status line claimed
                    // otherwise would be a lie.
                    LibraryItem added = LibraryStore.add(prefs, PrefFiles.store(target), null, pickedName);
                    Toast.makeText(MainActivity.this,
                            "Added " + added.displayName(), Toast.LENGTH_SHORT).show();
                } else if (depthForId != null) {
                    final String id = depthForId;
                    LibraryStore.editItem(prefs, id, new LibraryStore.Editor() {
                        @Override
                        public LibraryItem edit(LibraryItem item) {
                            return item.withDepth(PrefFiles.store(target));
                        }
                    });
                    Toast.makeText(MainActivity.this, "Depth updated", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            "Pick an image first", Toast.LENGTH_SHORT).show();
                }
                refreshSliders();
                applyFiles();
            }
        });
    }

    /**
     * The name a document provider shows for a picked item.
     *
     * <p>Best effort: a provider that does not answer gives null and the row falls
     * back to the on-disk filename. That is a worse label, not a broken one, so
     * there is nothing here worth failing the import over.
     */
    private String displayNameOf(Uri uri) {
        if (uri == null) return null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) {
                    String name = c.getString(i);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
            // A provider that throws is a provider we cannot name.
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * Put the selected item on screen: its framing <i>and</i> its files.
     *
     * <p>Both, and the framing is the half that was missing. Loading only the
     * files left the renderer holding the previously selected item's orbit, pan
     * and zoom, so picking a photo in the gallery showed it through the last
     * photo's framing and it looked like per-item settings were not working at
     * all. The stored values had been correct the whole time; nothing had told
     * the renderer to use them.
     *
     * <p>This is the same resolution the wallpaper does, through the same
     * {@link Framing}, because a third path that computes it slightly
     * differently is precisely how the two surfaces came to disagree about the
     * bundled item earlier. There is now one resolution and every caller applies
     * both halves.
     *
     * <p>Reads here, applies on the GL thread: {@code queueEvent} is the one hop
     * the renderer allows, and {@code setPan} clamps against the real surface
     * size so it has to run there.
     */
    void applyFiles() {
        // The index has already dropped rows whose file is absent, so this only
        // has to resolve names against this install's filesDir -- which is what
        // makes a restored backup work: the blob holds bare filenames and the
        // old device's absolute path is never stored.
        AppState state = AppState.load(prefs, getFilesDir());
        LibraryItem active = state.activeItem();
        final String img;
        final String dep;
        if (active == null || active.isBundled()) {
            // The marble bust: no file, so res/raw. Null is a normal state here,
            // not a missing photo.
            img = null;
            dep = null;
        } else {
            java.io.File imgF = PrefFiles.resolve(getFilesDir(), active.imageName);
            java.io.File depF = PrefFiles.resolve(getFilesDir(), active.depthName);
            img = imgF == null ? null : imgF.getAbsolutePath();
            dep = depF == null ? null : depF.getAbsolutePath();
        }
        final Framing framing = Framing.of(state.activeItem(), state.config.motionScale());
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.apply(framing);
            }
        });
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

    void refreshStatus() {
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
        // The wallpaper engine's own view of itself, if it has ever run. A live
        // wallpaper is a separate surface with its own lifecycle, so "the studio
        // looks right" says nothing about whether the home screen is showing what
        // is selected -- and the two being different is the whole class of bug
        // this line exists to make visible without a cable.
        String w = prefs.getString("wallpaper_debug", "");
        if (!w.isEmpty()) s += "\nEngine: " + w;
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

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
