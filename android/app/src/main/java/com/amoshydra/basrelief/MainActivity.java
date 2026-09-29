package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
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

    // ------------------------------------------------------------ bottom sheet
    // Three snap states instead of the old visible/hidden pair, because the
    // panel is taller than the preview it is there to frame and two states were
    // not enough: either the controls covered the picture or they were all gone.
    private static final String KEY_SHEET = "sheet_pos";
    /**
     * How far down the panel sits, as a fraction of its own height: 0 is fully
     * open, 1 is entirely off the bottom.
     *
     * <p>A fraction rather than pixels, because the panel's height changes with the
     * screen, with the keyboard, and when the Framing grid expands. A pixel offset
     * saved today would be a different position tomorrow.
     *
     * <p>There are no snap points, deliberately. The first version had three and
     * every one was wrong: the panel is not a menu, and a position you cannot leave
     * is a position you have to argue with.
     */
    private float sheetFraction = 0f;
    /**
     * A restored position is clamped to this, so launching never opens the app with
     * no controls at all, which reads as a broken launch rather than as a
     * remembered position.
     */
    private static final float MAX_RESTORED_FRACTION = 0.85f;
    /** A vertical move past this claims the gesture, so a tap on the strip is a no-op. */
    private static final int DRAG_SLOP_DP = 8;
    /** How long the panel takes to slide, in ms. */
    private static final int SHEET_SLIDE_MS = 180;
    /**
     * How far down the sheet must be dragged before it counts as collapsed.
     *
     * <p>The only threshold in an otherwise snap-free sheet. Without it, a drag to
     * the bottom leaves a sliver of panel too short to grab, and the way back is a
     * plus button sitting under the finger that just let go.
     *
     * <p><b>This cannot be 1.0, and that is not a matter of taste.</b> The handle
     * sits about 76px below the panel's top edge, and a finger cannot leave the
     * screen. So the furthest a drag can ever travel is the panel's visible height
     * minus that 76px, which puts the ceiling on the reachable fraction at about
     * 1 - 76/1623 = 0.95 no matter where the drag started. A threshold above that is
     * dead code: it reads as a feature that was implemented and quietly does
     * nothing, which is the worst of both. 0.9 leaves room under the ceiling.
     */
    private static final float SHEET_COLLAPSE_FRACTION = 0.9f;
    /** True between the handle's DOWN and UP, so a slide cannot fight a live drag. */
    private boolean sheetDragging = false;
    private View sheetHandle;

    /**
     * The panel's own LayoutParams, kept so its height can be changed at runtime.
     *
     * <p>Position is expressed as a layout height rather than as {@code translationY},
     * and that is not a stylistic choice - it is the difference between working and
     * not. See {@link #applySheetHeight}.
     */
    private FrameLayout.LayoutParams panelLp;
    /**
     * The panel's scrolling content.
     *
     * <p>Everything except the drag handle lives in here. The sheet can be pulled
     * down to any height, so at small heights the controls below the fold would
     * otherwise be unreachable - there would be no way to get at them and no way to
     * know they were there. The handle stays outside so it is always reachable, which
     * is what makes the sheet draggable in the first place.
     */
    private LinearLayout sheetContent;
    /**
     * The panel's height when fully open, captured once.
     *
     * <p>Needed because {@code getHeight()} reports the height currently imposed,
     * which is the very thing being animated. Captured before any height is set,
     * while the panel is still unconstrained and therefore natural.
     */
    private int panelNaturalHeight = 0;

    // ---------------------------------------------------------- framing fields
    // Nudge steps. Origin is a stored fraction; pan is pixels. Deliberately
    // different magnitudes, because they are different units and a shared step
    // would make one of them unusable.
    private static final float ORBIT_STEP = 0.01f;
    private static final float PAN_STEP = 1f;
    private LinearLayout framingGrid;
    private TextView framingReadout;
    private EditText orbitXField, orbitYField, panXField, panYField;
    private boolean framingExpanded = false;
    /**
     * Set while a field is written programmatically, so the watcher's own
     * write-back does not fight the formatter.
     */
    private boolean suppressFramingWatcher = false;
    /**
     * One watcher for all four fields: they all want the same thing, and four
     * identical instances would be four places to keep in step.
     */
    private final android.text.TextWatcher framingWatcher = new android.text.TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (suppressFramingWatcher) return;
            applyFramingFields(false);
        }
    };
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

        // header: just the title. The minimise button used to live here, and it is gone
        // because sliding the sheet down now does it - a control that duplicates a
        // gesture sitting next to it is a second thing to learn and a second thing to
        // break. The handle above it is the affordance, and the plus button that
        // appears afterwards is the way back.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Basrelief — live preview");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Handle first, so it is the topmost child and sits above the title.
        panel.addView(buildSheetHandle(), 0);

        // Scrolling content. Built here, before anything is added to it, because
        // every section below goes into sheetContent rather than onto the panel, so
        // the handle stays pinned and reachable and the rest can be scrolled.
        ScrollView sheetScroll = new ScrollView(this);
        sheetScroll.setFillViewport(true);
        sheetScroll.setClipToPadding(false);
        sheetContent = new LinearLayout(this);
        sheetContent.setOrientation(LinearLayout.VERTICAL);
        sheetScroll.addView(sheetContent, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        // Weight 1 so the scroll area takes whatever the panel's current height
        // leaves after the handle, and the content scrolls within it.
        panel.addView(sheetScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        sheetContent.addView(header);

        // gesture hint (hidden once the user minimises the panel)
        hintView = new TextView(this);
        hintView.setText("Tap: orbit center · 2-finger: pan · pinch: zoom");
        hintView.setTextColor(0x99FFFFFF);
        hintView.setTextSize(11);
        if (prefs.getBoolean("hint_dismissed", false)) hintView.setVisibility(View.GONE);
        sheetContent.addView(hintView);

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
        sheetContent.addView(addSlider("Depth", "intensity",
                initialItem == null ? LibraryItem.DEFAULT_INTENSITY : initialItem.intensity, 0, 100));
        sheetContent.addView(addSlider("Motion", "motion",
                initial.config.motion, 0, 100));
        sheetContent.addView(addSlider("Zoom", "zoom",
                initialItem == null ? LibraryItem.DEFAULT_ZOOM : initialItem.zoom, 25, 200));

        sheetContent.addView(buildFramingSection(initial.isEditableItem()));

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
        sheetContent.addView(resetView);

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
        sheetContent.addView(buttons);

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
        sheetContent.addView(depthAiBtn);

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
        sheetContent.addView(setWallpaper);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(8), 0, 0);
        sheetContent.addView(statusView);

        panelLp = new FrameLayout.LayoutParams(
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

        // Restore the panel position, but only after layout: the half and hidden
        // translations are a fraction of the panel's own measured height, which
        // is zero until the first layout pass has run.
        final float savedPos = prefs.getFloat(KEY_SHEET, 0f);
        panel.post(new Runnable() {
            @Override
            public void run() {
                setSheetPosition(Math.min(savedPos, MAX_RESTORED_FRACTION), false);
            }
        });
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
        // Only the sensor acts on battery-save, and it is not GL-thread state, so
        // this needs no hop. It used to also poke the renderer, which had no frame
        // rate to give up and a field that nothing read.
        if (tilt != null) tilt.setPowerSave(PowerSave.isOn(MainActivity.this));
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

    /**
     * Retire the gesture hint, once, the first time the sheet is put away.
     *
     * <p>Called from {@link #setSheetPosition} rather than from a control, because
     * the sheet is now collapsed by dragging the handle and there is no button left
     * to hang it on. Tying it to the control would mean the hint survived a drag.
     */
    private void dismissHintIfShown() {
        if (hintView != null && hintView.getVisibility() == View.VISIBLE) {
            prefs.edit().putBoolean("hint_dismissed", true).apply();
            hintView.setVisibility(View.GONE);
        }
    }

    private void expandUi() {
        // Back to where it was, which is the useful answer: someone who works with
        // the sheet most of the way down should not have to open it fully every
        // time. Falls back to fully open if the stored position is the hidden one.
        //
        // Deliberately not animated. The plus button means "I want the controls back",
        // and by the time the sheet has finished sliding they are there and usable -
        // the wait is the app appearing to think about it. Snapping to the position
        // costs one layout pass. The collapse still slides, because that is the user
        // finishing with the panel and a short motion there costs nothing.
        setSheetPosition(sheetFraction >= 1f ? 0f : sheetFraction, false);
    }

    // ------------------------------------------------------------ framing fields

    /**
     * Exact numeric control of the origin and the pan.
     *
     * <p>Collapsed to a single readout line, because the panel is already taller
     * than the preview it exists to frame. The collapsed row is not just a
     * disclosure: it shows the values, so most visits never need to open it.
     *
     * <p>Disabled wholesale for the bundled demo, and that is not tidiness.
     * {@code Framing.of} returns zeroed orbit and pan for the bundled item, so
     * anything typed here would be discarded on the next resolve. Live fields
     * that silently do nothing are the same lie as a live Depth slider that the
     * renderer ignores, which is why Depth and Zoom are already disabled for it.
     */
    private View buildFramingSection(final boolean editable) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView toggle = new TextView(this);
        toggle.setTextSize(14);
        toggle.setPadding(dp(4), dp(8), dp(4), dp(4));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!editable) return;
                setFramingExpanded(!framingExpanded);
            }
        });
        framingReadout = toggle;
        box.addView(toggle);

        // Two columns rather than four stacked rows: same information in half the
        // height, which is the whole reason the section is worth opening.
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setVisibility(View.GONE);
        LinearLayout originCol = new LinearLayout(this);
        originCol.setOrientation(LinearLayout.VERTICAL);
        originCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout panCol = new LinearLayout(this);
        panCol.setOrientation(LinearLayout.VERTICAL);
        panCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        originCol.addView(fieldCaption("Origin (fraction)"));
        orbitXField = addFramingField(originCol, "X", editable, true, 0);
        orbitYField = addFramingField(originCol, "Y", editable, true, 1);
        panCol.addView(fieldCaption("Pan (px)"));
        panXField = addFramingField(panCol, "X", editable, false, 2);
        panYField = addFramingField(panCol, "Y", editable, false, 3);

        grid.addView(originCol);
        grid.addView(panCol);
        framingGrid = grid;
        box.addView(grid);

        refreshFramingFields();
        return box;
    }

    private TextView fieldCaption(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setPadding(dp(4), dp(4), dp(4), 0);
        return t;
    }

    /**
     * One framing row: label, minus, field, plus.
     *
     * @param which 0..3 selecting which of orbitX/orbitY/panX/panY this edits
     */
    private EditText addFramingField(LinearLayout col, String label, boolean editable,
            boolean fraction, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(18),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(l);

        Button minus = nudgeButton("−", editable, fraction, which, -1);
        Button plus = nudgeButton("+", editable, fraction, which, +1);
        row.addView(minus);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        // A signed decimal, with the fraction side keeping its digits. Pan is
        // whole pixels because a fractional pixel offset is not a thing anyone
        // can aim at.
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (!fraction) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        field.setEnabled(editable);
        field.setEms(fraction ? 7 : 6);
        field.setContentDescription(label + (fraction ? " origin fraction" : " pan pixels"));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        flp.setMargins(dp(2), 0, dp(2), 0);
        field.setLayoutParams(flp);
        // Live: the relief moves as you type, which is the only reason to type a
        // number instead of dragging. The WRITE is deferred to focus loss or the
        // IME action, because a prefs commit per keystroke is a disk write per
        // keystroke and the sliders already learned that the expensive part is
        // the write, not the preview.
        field.addTextChangedListener(framingWatcher);
        field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId,
                    android.view.KeyEvent event) {
                applyFramingFields(true);
                return true;
            }
        });
        row.addView(field);
        // After the field, not before it, so the pair reads - value +.
        row.addView(plus);
        col.addView(row);

        return field;
    }

    private Button nudgeButton(String glyph, boolean editable, boolean fraction,
            final int which, final int direction) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(0, 0, 0, 0);
        // A fixed width, not setMinWidth. minWidth is only a floor, so the button
        // still laid itself out from its text and squeezed the "+" off the end of
        // the row -- which is exactly the control you want most.
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        b.setEnabled(editable);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudge(which, direction);
            }
        });
        return b;
    }

    private float currentFramingValue(int which) {
        switch (which) {
            case 0: return renderer.getOrbitX();
            case 1: return renderer.getOrbitY();
            case 2: return renderer.getPanX();
            default: return renderer.getPanY();
        }
    }

    private void nudge(int which, int direction) {
        float step = which < 2 ? ORBIT_STEP : PAN_STEP;
        // Rounded to the precision the field shows, so a nudged value and a typed
        // value are the same number. Rounding only for display would let the
        // stored value and the visible value drift apart by a rounding error,
        // which is the sort of small disagreement that becomes a real one.
        float next = roundFramingValue(
                currentFramingValue(which) + step * direction, which < 2);
        writeFramingField(which, next);
        applyFramingFields(true);
    }

    /**
     * Round to the precision the field displays.
     *
     * <p>Applied to the value, not just to its text, so what is stored and what is
     * shown are the same number. A nudge of 0.01 added to a value already rounded
     * to four places can otherwise leave the fifth place set, and the field would
     * then read back something that was never stored.
     */
    private float roundFramingValue(float v, boolean fraction) {
        if (fraction) {
            // 10000f here and "%.4f" in formatFramingValue below are the same
            // precision stated twice. A constant for it was tried and removed: it
            // cannot be used by a format string, so it would have looked like the
            // single source of truth while the two could still disagree.
            return Math.round(v * 10000f) / 10000f;
        }
        return Math.round(v);
    }

    private String formatFramingValue(float v, boolean fraction) {
        float r = roundFramingValue(v, fraction);
        if (fraction) {
            return String.format(java.util.Locale.US, "%.4f", r);
        }
        return String.valueOf((long) r);
    }

    /**
     * Push the four fields into the renderer.
     *
     * @param persist true to also write them to the index
     */
    private void applyFramingFields(boolean persist) {
        float ox = parseField(orbitXField, renderer.getOrbitX());
        float oy = parseField(orbitYField, renderer.getOrbitY());
        float px = parseField(panXField, renderer.getPanX());
        float py = parseField(panYField, renderer.getPanY());
        renderer.setOrbit(ox, oy);
        renderer.setPan(px, py);
        if (persist) {
            renderer.saveViewToPrefs();
            refreshSliders();
        }
        glView.requestRender();
    }

    /** A field mid-edit is allowed to be unparseable; keep the old value then. */
    private float parseField(EditText f, float fallback) {
        if (f == null) return fallback;
        try {
            return Float.parseFloat(f.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Pull the four values out of the renderer into the fields and the readout. */
    private void refreshFramingFields() {
        float ox = renderer.getOrbitX(), oy = renderer.getOrbitY();
        float px = renderer.getPanX(), py = renderer.getPanY();
        writeFramingField(0, ox);
        writeFramingField(1, oy);
        writeFramingField(2, px);
        writeFramingField(3, py);
        if (framingReadout != null) {
            framingReadout.setText(String.format(java.util.Locale.US,
                    "Framing   %.3f, %.3f  ·  %d, %d px%s",
                    ox, oy, Math.round(px), Math.round(py),
                    framingExpanded ? "   ▲" : "   ▾"));
        }
    }

    private void writeFramingField(int which, float value) {
        EditText f = which == 0 ? orbitXField : which == 1 ? orbitYField
                : which == 2 ? panXField : panYField;
        if (f == null) return;
        String s = formatFramingValue(value, which < 2);
        if (s.contentEquals(f.getText())) return;
        // Writing the field re-enters the watcher, which would read back a value
        // that is mid-format. Suppressed rather than detached, so there is no way
        // to leave a field with no watcher attached.
        suppressFramingWatcher = true;
        try {
            f.setText(s);
        } finally {
            suppressFramingWatcher = false;
        }
    }

    private void setFramingExpanded(boolean expanded) {
        framingExpanded = expanded;
        if (framingGrid != null) framingGrid.setVisibility(expanded ? View.VISIBLE : View.GONE);
        refreshFramingFields();
        // The panel just changed height, so the half and hidden positions have
        // moved with it.
        reapplySheetAfterResize();
    }

    // ---------------------------------------------------------------- bottom sheet

    /**
     * The drag strip, added as the panel's FIRST child so it sits above the title.
     *
     * <p>Only this strip drags. The rest of the panel is sliders, buttons and text
     * fields, and a sheet that claimed a drag anywhere on itself would break every
     * one of them.
     */
    private View buildSheetHandle() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(android.view.Gravity.CENTER);
        strip.setBackgroundColor(Color.argb(90, 255, 255, 255));
        strip.setContentDescription("Drag to move the panel");
        strip.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        // A short centred bar inside the strip: the strip is the touch target, the
        // bar is the thing that reads as a handle.
        View grip = new View(this);
        LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(dp(40), dp(4));
        gripLp.setMargins(0, dp(6), 0, dp(6));
        grip.setBackgroundColor(Color.argb(150, 255, 255, 255));
        grip.setLayoutParams(gripLp);
        strip.addView(grip);

        sheetHandle = strip;
        strip.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY, downFraction;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        downFraction = sheetFraction;
                        dragging = false;
                        sheetDragging = true;
                        // Cancel any half-finished animation, or the panel leaps to
                        // wherever the animator happened to be when the finger landed.
                        panel.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - downRawY;
                        // Claim the gesture only once it is clearly vertical, so a
                        // tap on the strip stays a no-op instead of a jump.
                        if (!dragging && Math.abs(dy) > dp(DRAG_SLOP_DP)) dragging = true;
                        if (dragging) {
                            float h = panelHeight();
                            if (h > 0f) {
                                // Positive dy is a downward drag, and sheetFraction
                                // measures how far DOWN the sheet is, so the two add.
                                // Subtracting here is what made the handle run
                                // backwards: pull down and it opened further.
                                float next = downFraction + dy / h;
                                if (next < 0f) next = 0f;
                                if (next > 1f) next = 1f;
                                sheetFraction = next;
                                if (sheetFraction >= SHEET_COLLAPSE_FRACTION) {
                                    // Close enough to the bottom to count as gone.
                                    // Collapsing here rather than on release means the
                                    // sheet never rests as a useless sliver with the
                                    // handle barely reachable, and the plus button
                                    // appears under the finger rather than after it
                                    // lifts. The drag ends with it.
                                    dragging = false;
                                    setSheetPosition(1f, false);
                                } else {
                                    // Live and unsnapped: the panel goes exactly where
                                    // the finger is and stays there on release.
                                    // A layout height, not a translation - the same
                                    // reason applySheetHeight exists.
                                    applySheetHeight(1f - sheetFraction);
                                }
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            // Only updates the saved position and the handle. No
                            // snapping: where you let go is where it rests.
                            setSheetPosition(sheetFraction, false);
                        }
                        dragging = false;
                        sheetDragging = false;
                        return true;
                    default:
                        return true;
                }
            }
        });
        return strip;
    }

    /**
     * The panel's full height, captured once and then owned by this class.
     *
     * <p>{@code getHeight()} cannot be used for this, because it reports the height
     * currently imposed - which is the thing being animated. Before any height is
     * set the panel is unconstrained, so the first measurement is its natural height.
     */
    private float panelHeight() {
        if (panelNaturalHeight > 0) return panelNaturalHeight;
        int h = panel.getHeight();
        return h > 0 ? h : 0f;
    }

    /**
     * Give the panel a height, and so place it on screen.
     *
     * <p>The argument is how much of the panel is <i>showing</i>: 0 is hidden, 1 is
     * fully open. That is the complement of {@code sheetFraction}, which measures
     * how far <i>down</i> the sheet is, and the two must not be confused - doing so
     * collapses the sheet to nothing height the moment it opens.
     *
     * <p><b>Positioning by layout height rather than by {@code translationY} is a
     * regression repair, not a new idea.</b> The panel used to be hidden and shown
     * with nothing but {@code setVisibility(GONE)} and {@code setVisibility(VISIBLE)},
     * and that worked. Replacing it with a transform broke it, and the symptom was
     * maddening because every readable property stayed correct: visibility VISIBLE,
     * {@code isShown()} true, {@code translationY} 0.0, {@code alpha} 1.0, sized and
     * attached, in a window that had drawn 904 frames - operable to the touch and
     * absent from the display, confirmed on a screenshot taken on the phone.
     *
     * <p>So the difference is layout versus transform. Changing visibility changes
     * layout, and the platform re-lays-out and repaints the region. Changing a
     * transform did not reliably repaint that region here: the panel kept the pixels
     * it had while hidden while still accepting every tap.
     *
     * <p>Because the panel is anchored to the bottom, a smaller height shows its top
     * portion, which is the direction a bottom sheet should collapse.
     */
    private void applySheetHeight(float showing) {
        if (panelNaturalHeight <= 0) {
            // First time through, the panel is still unconstrained, so this is its
            // real height and the only chance to learn it.
            panelNaturalHeight = panel.getHeight();
            if (panelNaturalHeight <= 0) return;  // not measured yet; caller defers
        }
        int target = Math.round(Math.max(0f, Math.min(1f, showing)) * panelNaturalHeight);
        if (panelLp.height == target) return;
        panelLp.height = target;
        // setLayoutParams requests a layout, which is the repaint this needs.
        panel.setLayoutParams(panelLp);
    }

    /**
     * Put the panel at a fraction of its height, and update everything that follows
     * from that: the plus button, and the remembered position.
     *
     * <p>Position is a layout height, not a transform - see {@link #applySheetHeight}
     * for why, and for the measurement that forced it.
     */
    private void setSheetPosition(float fraction, boolean animate) {
        if (panelHeight() <= 0f) {
            // Not laid out yet. Defer rather than derive a position from a height of
            // zero, which would park the panel somewhere arbitrary.
            final float f = fraction;
            final boolean a = animate;
            panel.post(new Runnable() {
                @Override
                public void run() {
                    setSheetPosition(f, a);
                }
            });
            return;
        }
        sheetFraction = Math.max(0f, Math.min(1f, fraction));
        // A little past the very bottom still counts as hidden, so dragging hard to
        // the end does not leave a sliver of panel on screen.
        // The same threshold the drag uses, so "far enough down to be gone" means one
        // thing rather than two literals that can drift apart. This one was 0.98,
        // which the drag could never reach - see SHEET_COLLAPSE_FRACTION.
        boolean hidden = sheetFraction >= SHEET_COLLAPSE_FRACTION;

        panel.animate().cancel();
        expandBtn.setVisibility(hidden ? View.VISIBLE : View.GONE);
        // sheetFraction is "how far DOWN the sheet is": 0 is fully open, 1 closed.
        // The height is its complement - how much of the panel is showing - and
        // confusing the two collapses the sheet to nothing the instant it opens.
        float showing = 1f - sheetFraction;
        if (hidden) {
            // Zero height: laid out, so painted, and showing nothing.
            applySheetHeight(0f);
            // The sheet has been put away, which is the moment the gesture hint has
            // served its purpose. It used to hang off the minimise button, and that
            // button is gone now that the drag does the job.
            dismissHintIfShown();
        } else if (animate) {
            slideTo(showing);
        } else {
            applySheetHeight(showing);
        }
        if (!hidden) {
            // Never remembered as hidden: launching with no controls reads as a
            // broken launch, not as a remembered position.
            prefs.edit().putFloat(KEY_SHEET, Math.min(sheetFraction, MAX_RESTORED_FRACTION))
                    .apply();
        }
    }

    /**
     * Why the panel is moved rather than hidden, since the obvious implementation
     * is wrong and fails in a way that looks like nothing at all happened.
     *
     * <p>This was measured on a Pixel 7 Pro, not reasoned about. Every part of the
     * restore ran correctly and the panel still did not appear: {@code visibility}
     * was {@code VISIBLE}, {@code translationY} was {@code 0.0}, {@code alpha} was
     * {@code 1.0}, the panel was 1440x1588 and attached to a window that was
     * actively producing frames. It was operable but not on the glass.
     *
     * <p>Two facts pinned it down. Dragging the sheet worked, because a drag writes
     * {@code translationY} on every frame and each write invalidates the panel's
     * region. And the panel reappeared the moment the user switched apps or pulled
     * the notification shade down and back - both of which force a full window
     * redraw. So the panel's pixels on screen were left over from the hidden frame,
     * and nothing in the restore path ever marked that region dirty.
     *
     * <p>The cause was using {@code setVisibility(INVISIBLE)} to hide the panel.
     * Making a view visible again does not reliably repaint the area it used to
     * occupy, and nothing else in the restore touched that region, so the window
     * went on showing the hidden frame. Translation has no such gap: it is a
     * transform of a view that is always drawn, so restoring is just another
     * position and the region is invalidated like any other change.
     *
     * <p>So the panel is always VISIBLE and "hidden" means translated off the bottom.
     * It keeps its layout either way, which also means its measured height survives
     * to be restored - the reason the earlier version used INVISIBLE rather than
     * GONE no longer needs defending.
     */

    /**
     * Slide the panel open by animating its height.
     *
     * <p>Animating layout rather than a transform is deliberate and is the whole
     * fix; {@link #applySheetHeight} explains why, and the measurement that forced
     * it. The animator is driven explicitly and calls applySheetHeight on every
     * frame, so the panel is re-laid-out continuously rather than being moved by a
     * transform the display may not repaint.
     */
    private void slideTo(final float showing) {
        panel.animate().cancel();
        applySheetHeight(0f);
        android.animation.ValueAnimator slide =
                android.animation.ValueAnimator.ofFloat(0f, showing);
        slide.setDuration(SHEET_SLIDE_MS);
        slide.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                applySheetHeight((Float) a.getAnimatedValue());
            }
        });
        slide.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                // onAnimationEnd also fires for a cancel; isRunning() cannot tell them
                // apart, so the distinction has to be recorded here.
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                // Skipped while a drag has taken over, which has already placed the
                // panel where the finger wants it.
                if (cancelled || sheetDragging) return;
                applySheetHeight(showing);
            }
        });
        slide.start();
    }

    /**
     * Re-place the panel after its height changes.
     *
     * <p>Expanding the Framing section makes the panel taller, so the stored
     * fraction has to be re-applied or the panel silently jumps position.
     */
    private void reapplySheetAfterResize() {
        panel.post(new Runnable() {
            @Override
            public void run() {
                setSheetPosition(sheetFraction, false);
            }
        });
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
        sheetContent.addView(tv);
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
        // Motion decides whether the sensor can change a pixel at all, so it
        // decides whether the sensor should be running. Set here because this is
        // the one place the studio reads the stored Motion value, and it runs on
        // every framing change.
        if (sensorPolicy != null) {
            sensorPolicy.setMotionActive(state.config.motionScale() > 0f);
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
