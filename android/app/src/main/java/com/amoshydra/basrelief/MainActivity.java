package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Playlist;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.SurfaceSettings;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.app.Activity;
import android.app.WallpaperInfo;
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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.util.List;
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

    GLSurfaceView glView;
    ReliefRenderer renderer;
    private TiltController tilt;
    private SensorPolicy sensorPolicy;
    SharedPreferences prefs;

    /**
     * The current index, loaded. For a section built in its own file, so it reads the
     * same one resolution every control here reads rather than parsing the blob again.
     */
    AppState state() {
        return AppState.load(prefs, getFilesDir());
    }
    private TextView statusView;
    private LinearLayout panel;

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
    /**
     * How much of the phone the sheet occupies when the studio opens.
     *
     * <p>The render is the product; the controls are an invitation. Opening fully
     * spends two thirds of the screen on sliders before the user has seen the thing
     * they are adjusting, which is the wrong order. A third is the user's number:
     * enough sheet to show the grip, the filmstrip and the first row or two, and
     * two thirds of the screen left for the photograph.
     *
     * <p>This is NOT the denominator {@link #SHEET_COLLAPSE_FRACTION} is derived
     * from. That one is about how far a drag can travel from the handle, not about
     * where the sheet opens; see its javadoc.
     */
    private static final float SHEET_PEEK_FRACTION = 1f / 3f;
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
     *
     * <p>It is derived from the handle offset and the panel height — how far a drag
     * can go — and NOT from the peek. Shortening the launch peek to a third moves
     * where the sheet starts, not how far a drag from the handle can reach, so this
     * needs no re-deriving when {@link #SHEET_PEEK_FRACTION} changes. The strip
     * joining the panel grows the panel's natural height, which only raises the
     * ceiling above 0.95, so 0.9 stays reachable.
     */
    private static final float SHEET_COLLAPSE_FRACTION = 0.9f;
    /**
     * True from the moment a drag claims the sheet until the finger lifts, so a
     * slide cannot fight a live drag.
     *
     * <p>Set by the grip strip and by {@link SheetPanel}'s arbitration alike, and
     * only once the drag is past the slop: a tap on the panel is not a drag, and a
     * flag left set by a tap would make every later slide skip its final
     * placement.
     */
    private boolean sheetDragging = false;
    /** The DOWN the current sheet drag is measured from, in raw screen pixels. */
    private float sheetDragDownRawX;
    private float sheetDragDownRawY;
    /** {@link #sheetFraction} when the drag began; every move resolves from this. */
    private float sheetDragDownFraction;
    /** True once a vertical drag has claimed the sheet. */
    private boolean sheetDragClaimed;
    /**
     * True when the current gesture began inside {@link #sheetScroll}, so the
     * scroll gets first refusal on it. See {@link SheetPanel#onInterceptTouchEvent}.
     */
    private boolean sheetDragFromScroll;

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
     * The panel's scroll view.
     *
     * <p>A field rather than a local because the navigation-bar inset has to be
     * re-read from outside {@code onCreate}: the bar is shown or hidden as the
     * sheet collapses (see {@link #applySystemUi}), and hiding it changes the
     * bottom inset, so the margin that keeps the foot — and the fade — clear of
     * the bar has to be recomputed. {@link #requestSheetInsets} is the one place
     * that asks.
     *
     * <p>The inset is a <b>margin</b>, not padding, and that is load-bearing:
     * Android draws a scroll view's fading edge at the view's bounds, so the
     * only way to move the fade above a navigation bar is to move the bounds.
     * See the inset listener in {@code onCreate} and {@link BottomFadeScrollView}.
     */
    private ScrollView sheetScroll;
    /** The one dashed line under the grip. See {@link #buildFixLine}. */
    private LinearLayout fixLine;
    /** The pill that replaces the sheet when it is collapsed. See {@link #setPillState}. */
    /**
     * The collapsed sheet, as the mockup's row: a status dot (or a progress ring)
     * followed by a label that carries the ellipsis.
     */
    private LinearLayout pill;
    /** The label inside {@link #pill}. The ellipsis lives on THIS, not on the pill. */
    private TextView pillLabel;
    /** The dot, or the ring while a generation runs. Swapped by {@link #setPillState}. */
    private View pillMark;
    private ProgressRing pillRing;
    /** What {@link #applyPillState} last drew, so a drag does not redraw it. */
    private PillState pillDrawnState;
    /** ...and the percentage it was drawn with, because PROGRESS carries one. */
    private int pillDrawnPercent = -1;
    private PillState pillState = PillState.IDLE;
    /** The Depth slider row, which does not exist for a photo with no map. */
    private View depthBarRow;
    /**
     * The first group caption, "Standing out of the plane".
     *
     * <p>The Depth row belongs immediately after this caption, and the position is
     * re-derived from this view every time Depth is inserted. It is an anchor, not
     * a saved index, on purpose: an integer captured when the sheet was built is 0
     * when the selected photo had no map (there is nothing to take the index of),
     * and re-adding at 0 puts Depth above every other control including the Home /
     * Lock segment. A sibling that is always present cannot go stale that way.
     */
    private View reliefCaption;
    /**
     * The second group caption, "How it moves".
     *
     * <p>A field only so its top gap can follow the depth caption's presence: when
     * the depth caption is hidden because its row is absent, this becomes the first
     * caption in the scroll and takes the mockup's {@code .caption:first-child} 2px;
     * when it is not, it keeps the 16px of an inter-group heading. {@link
     * #layOutCaption} applies whichever is true at the moment.
     */
    private TextView motionCaption;
    private TextView fixText;
    private LinearLayout fixActions;
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
    TextView framingReadout;
    /**
     * One watcher for all four fields: they all want the same thing, and four
     * identical instances would be four places to keep in step.
     */
;
    private TextView hintView;
    private DepthGenDialog depthGenDialog;
    /** The one horizontal slider control, keyed as the settings are. */
    private BasreliefSlider zoomSlider;

    /**
     * Which surface the sheet is editing.
     *
     * <p>Not a mode the user picks in Settings: it follows the photo, and it is only
     * ever anything but HOME when the platform can give the lock screen its own live
     * engine. The segment below the grip is how it changes.
     */
    Surface editSurface = Surface.HOME;
    /** The Home / Lock switch. Always built; shown by {@link #refreshSurfaceSegment}. */
    private LinearLayout surfaceSegment;
    private Button segHome, segLock;

    /**
     * The photo line: the photograph's name and what the wallpaper is doing with
     * it. One view carrying R-U2, R-U3 and R-PL3; see {@link #refreshPhotoLine}.
     */
    private TextView photoLine;

    /**
     * The line under the Home / Lock segment that is either the inheritance fact
     * ("same as home", R-U5) or the action that restores it (R-U6). One view,
     * because a surface either owns its settings or it does not — the two states
     * cannot both hold, and a disabled button beside a fact would be a second
     * control for one truth.
     */
    private TextView surfaceNote;

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
    /** Every slider on the sheet, by key. Replaces four parallel maps. */
    final java.util.Map<String, BasreliefSlider> sliders =
            new java.util.HashMap<>();
    /**
     * The label each slider was built with, by key.
     *
     * <p>Kept because the key is a storage name, not something to show. Without
     * this a refresh relabelled Depth to "intensity", which is the field name
     * from the blob and means nothing to the person using the app.
     */

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

        // Before anything reads the index. An older build's share path wrote every
        // import to one fixed filename, so the gallery could hold many rows all
        // reading a single file - the same photo under every row, and each earlier
        // photo overwritten. Repaired here rather than in a migration so there is
        // no flag to forget; the check is in-memory and costs nothing per launch.
        final List<String> dropped = LibraryStore.repairDuplicateImages(prefs);
        if (!dropped.isEmpty()) {
            // Only the index is fixed. The depth maps those rows referenced are the
            // user's own work, so they are left on disk to reclaim deliberately.
            android.util.Log.i("Basrelief", "gallery repair: dropped " + dropped.size()
                    + " row(s) sharing one image file");
        }

        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        gallery = new Gallery(this, root);
        // The picker lives here: REQ_IMAGE and the import path are MainActivity's,
        // and duplicating them in Gallery would be two places that can disagree about
        // what "add a photo" means.
        gallery.setPhotoPicker(new Gallery.PhotoPicker() {
            @Override
            public void pickPhoto() {
                pickFile(REQ_IMAGE);
            }
        });
        // Playlists are their own slice. Until then the header entry says so rather
        // than opening nothing.
        gallery.setPlaylistsNavigator(new Gallery.PlaylistsNavigator() {
            @Override
            public void openPlaylists() {
                PlaylistScreen.show(MainActivity.this);
            }
        });

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
        // The panel is a SheetPanel, not a stock LinearLayout, because it is where
        // a vertical drag is arbitrated between the scroll and the sheet; see its
        // onInterceptTouchEvent.
        panel = new SheetPanel(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // The mockup's --panel: rgba(22,26,32,.92). This was 0xB40B0E14 -- a
        // different hue AND a much weaker alpha, so the sheet read as a grey wash
        // over the photo instead of glass. There is no Android equivalent of
        // backdrop-filter below API 31, so the flat panel at 92% is the honest
        // approximation: the photo's edge still continues underneath.
        android.graphics.drawable.GradientDrawable sheetBg =
                new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(0xEB161A20);
        sheetBg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        sheetBg.setStroke(dp(1), 0x24FFFFFF);
        panel.setBackground(sheetBg);
        // No panel padding: the mockup pads the SCROLLING content, not the sheet, so
        // the grip and the fix line sit flush to the edges the way they do there.
        panel.setPadding(0, 0, 0, 0);
        // A gesture that starts on the sheet must not reach the GLSurfaceView beneath
        // it and orbit or pan the render, and a vertical one moves the sheet. A plain
        // ViewGroup consumes nothing, so a touch on the panel's own area -- the fix
        // line's text, the gap a control leaves -- falls through to the surface.
        // ViewGroup dispatch offers every touch to the children first, so the
        // ScrollView still scrolls and the sliders still drag; what lands here is
        // only what no control handled, which is exactly the "outside of controls"
        // the user asked to be draggable. See {@link #moveSheetDrag}.
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        beginSheetDrag(e);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        return moveSheetDrag(e);
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        finishSheetDrag();
                        return true;
                    default:
                        return true;
                }
            }
        });

        // header: just the title. The minimise button used to live here, and it is gone
        // because sliding the sheet down now does it - a control that duplicates a
        // gesture sitting next to it is a second thing to learn and a second thing to
        // break. The handle above it is the affordance, and the plus button that
        // appears afterwards is the way back.
        // Handle first, so it is the topmost child and the only fixed thing in the
        // sheet. There is no title row: the mockup puts the first caption directly
        // under the grip, and a permanent "Basrelief - live preview" costs a line of
        // the third-of-the-screen peek on every screen to say something the user is already looking
        // at. The title lived in a header that held nothing else.
        panel.addView(buildSheetHandle(), 0);

        // Scrolling content. Built here, before anything is added to it, because
        // every section below goes into sheetContent rather than onto the panel, so
        // the handle stays pinned and reachable and the rest can be scrolled.
        // The fix line, between the grip and the scrolling content. Outside the
        // scroll on purpose (R-F1/R-S2): it is the one thing that must be reachable
        // at the launch peek, when the foot of the sheet is still below the fold.
        // Inside the scroll it would be the first thing to scroll away, which is
        // exactly when a condition needs reading.
        fixLine = buildFixLine();
        panel.addView(fixLine, 1);

        // The filmstrip, directly under the grip and above the fix line. It is a
        // child of the sheet now, not a sibling floating over the render: the
        // user asked for a simple layout under the grip, and the whole measured
        // bottom-margin machinery (underlayHeight, layoutStrip, a global layout
        // listener, top and bottom insets) existed only to track an animated sheet
        // from outside it. A child of the panel needs none of that.
        //
        // Gallery owns the strip's content and its taps; the studio owns this
        // seat, passed in as the panel and the index. Index 1 is stable because
        // the grip at 0 is never rebuilt and the strip re-adds at the same index
        // on every refresh.
        gallery.installStrip(panel, 1);

        sheetScroll = new BottomFadeScrollView(this);
        sheetScroll.setFillViewport(true);
        sheetScroll.setClipToPadding(false);
        // The mockup masks the last 22px so a control below the fold is visibly
        // scrollable rather than looking like the end of the list. The fade is at
        // the foot only: BottomFadeScrollView suppresses Android's top edge fade,
        // which the design does not draw. This is a child property -- not a
        // transform -- so it is safe over the GLSurfaceView.
        sheetScroll.setFadingEdgeLength(dp(22));
        sheetScroll.setVerticalFadingEdgeEnabled(true);
        // The sheet itself has no padding, so the content carries it: 14dp sides, and
        // the bottom inset lives here so it scrolls away rather than eating height.
        final int sheetPadLeft = dp(14);
        final int sheetPadBottom = dp(14);
        sheetScroll.setPadding(sheetPadLeft, 0, sheetPadLeft, sheetPadBottom);
        // Fold the navigation-bar inset into a bottom MARGIN, the same way every
        // modal does -- except the modals fold it into padding, and padding is
        // wrong here for one reason: the scroll view's fading edge is drawn at
        // the view's BOUNDS. Padding lives inside the bounds, so padding moves
        // the content away from the edge while the edge itself stays at the
        // window's bottom, underneath the bar -- which is exactly the reported
        // bug: an unfaded strip of sheet between the fade and the bar, with the
        // fade itself invisible behind the bar. A margin moves the bounds, and
        // therefore the fade, to sit just above the bar. The margin region below
        // it shows the panel's own glass, which is what the bar then composites
        // over. ModalSheet.insetBottom is reused rather than re-derived so all
        // five surfaces resolve the bar identically.
        sheetScroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // The design's 14dp gap is content spacing, so it stays padding.
                v.setPadding(sheetPadLeft, 0, sheetPadLeft, sheetPadBottom);
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) v.getLayoutParams();
                int bar = sheetFootInset(insets);
                if (lp.bottomMargin != bar) {
                    lp.bottomMargin = bar;
                    // A layout change, not a transform: the fade has to be
                    // re-laid-out to move, and this is also the shape that
                    // reliably repaints over the GLSurfaceView (b/7179570).
                    v.setLayoutParams(lp);
                }
                return insets;
            }
        });
        // The listener has to be asked once there is a window. A listener installed
        // before attachment is not called, and requestApplyInsets on a detached view
        // has no ViewRootImpl to dispatch through -- the same trap the gallery strip
        // documents at installStrip(). This was the reason the inset "took" in the
        // code and never on the device: the request below used to run here, while
        // sheetScroll was still detached and not even added to the panel.
        sheetScroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.requestApplyInsets();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        sheetContent = new LinearLayout(this);
        sheetContent.setOrientation(LinearLayout.VERTICAL);
        sheetScroll.addView(sheetContent, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        // Weight 1 so the scroll area takes whatever the panel's current height
        // leaves after the handle, and the content scrolls within it.
        panel.addView(sheetScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // gesture hint (hidden once the user minimises the panel)
        hintView = new TextView(this);
        hintView.setText(R.string.gesture_hint);
        hintView.setTextColor(0x99FFFFFF);
        hintView.setTextSize(11);
        if (prefs.getBoolean("hint_dismissed", false)) hintView.setVisibility(View.GONE);
        sheetContent.addView(hintView);

        // The surface segment, above the first heading and below the fix line.
        // Absent -- not greyed, not disabled -- unless the photo is genuinely on both
        // screens AND the platform can give the lock screen its own live engine.
        // A toggle that cannot change anything is the same lie as a greyed slider.
        // Built ALWAYS and gated with GONE, not built conditionally.
        //
        // <p>The conditional build was why the switch "did not exist": the segment was
        // decided once, here in onCreate, and onResume never re-checked it. Android
        // re-delivers to the running studio rather than recreating it, so onCreate
        // never ran again -- and after applying to both surfaces the only way to see
        // the switch was a cold start, which a live wallpaper must never be given
        // (force-stop reverts it). GONE is the design's "absent, not greyed": no space,
        // not tappable, and it can appear the moment the index says it should.
        surfaceSegment = buildSurfaceSegment(editSurface);
        sheetContent.addView(surfaceSegment);
        refreshSurfaceSegment();

        // The inheritance note belongs to the segment it explains, so it follows
        // it directly. The photo line is under the same pair: the design puts it
        // near the top of the sheet as the first thing you read, and both live
        // inside the scroll because the segment scrolls with the controls it
        // changes.
        surfaceNote = buildSurfaceNote();
        sheetContent.addView(surfaceNote);
        photoLine = buildPhotoLine();
        sheetContent.addView(photoLine);

        // The caption is always the first thing in the scroll, whether or not the
        // Home / Lock segment sits above it on the panel, so the mockup's
        // .caption:first-child 2px is the right top gap in both cases. The old
        // condition (2px only when the segment was absent, 16px otherwise) was
        // inverted and is the uneven fix-line -> segment -> caption gap that read too
        // large: 10px of segment margin plus 16px of caption made 26 where the
        // mockup draws 12.
        reliefCaption = sectionLabel(
                heading(R.string.group_relief, R.string.group_relief_surface),
                true);

        // 0..100 = fraction of the maximum depth the camera allows at the
        // current zoom. Was 0..200, but the top half was a silent no-op at
        // high zoom because of the z-clamp.
        //
        // One read feeds every slider, so the panel can never show values that
        // disagree with what the renderer loads. All three come off the selected
        // item: Depth, Zoom and Motion are all properties of a photograph now, so
        // a slider left showing the previous photo's numbers would be editing
        // something nobody can see.
        final AppState initial = AppState.load(prefs, getFilesDir());
        final LibraryItem initialItem = initial.activeItem();
        // The caption and the depth row are one thing. The row below is built
        // conditionally from the same fact, and syncDepthRow keeps the two in step
        // after a selection change; deciding the launch state here means a photo
        // with no map never shows its heading for a moment (or until the next
        // selection) with nothing under it.
        reliefCaption.setVisibility(initialItemHasMap(initial) ? View.VISIBLE : View.GONE);
        final SurfaceSettings s = initialItem == null
                ? null : initialItem.settings(Surface.HOME);
        // Depth exists only for a photo that HAS a depth map (R-D1). It used to be
        // built always and greyed out with "no depth map" written beside it, which
        // was a control that could not do anything plus a second place saying the
        // same thing. The fix line is now the only signal, and it is one sentence.
        depthBarRow = initialItemHasMap(initial)
                ? addSlider("intensity",
                        s == null ? SurfaceSettings.DEFAULT_DEPTH : s.depth, 0, 100)
                : null;
        // Immediately after the caption it belongs under, derived here rather than
        // saved: see depthInsertIndex for the bug a saved index caused.
        if (depthBarRow != null) sheetContent.addView(depthBarRow, depthInsertIndex());
        motionCaption = sectionLabel(
                heading(R.string.group_motion, R.string.group_motion_surface));
        // The launch state of the first-caption rule: without a depth row the caption
        // above Depth is absent, so this is the first caption the scroll shows.
        layOutCaption(motionCaption, !initialItemHasMap(initial));
        sheetContent.addView(addSlider("motion",
                s == null ? SurfaceSettings.DEFAULT_MOTION : s.motion, 0, 100));
        sheetContent.addView(addSlider("zoom",
                s == null ? SurfaceSettings.DEFAULT_ZOOM : s.zoom, 25, 200));

        // Picture is its own group because these two answer a different question
        // from Depth and Zoom: they change how the photograph renders, not where it
        // sits or how far it stands out. Signed, because both directions are useful
        // and an unsigned control makes "less contrast" unreachable.
        sectionLabel(heading(R.string.group_picture, R.string.group_picture_surface));
        sheetContent.addView(addSlider("brightness",
                s == null ? SurfaceSettings.DEFAULT_ADJUST : s.brightness,
                SurfaceSettings.ADJUST_MIN, SurfaceSettings.ADJUST_MAX));
        sheetContent.addView(addSlider("shadow",
                s == null ? SurfaceSettings.DEFAULT_ADJUST : s.shadow,
                SurfaceSettings.ADJUST_MIN, SurfaceSettings.ADJUST_MAX));

        sectionLabel(heading(R.string.group_framing, R.string.group_framing_surface));
        sheetContent.addView(FramingRows.build(this));

        // "This photo's map": the mockup's heading for the Regenerate/Replace row and
        // the fix line. It replaces a row of three buttons that were not peers --
        // Gallery became the filmstrip, Depth became the fix line, and Calibrate
        // became a reported condition in Settings > Gyro rather than an action.
        sectionLabel(getString(R.string.group_depth));

        // Regenerate and Replace, on one row, and only when there IS a map. Two
        // actions because an applied map has two things you might want to do to it,
        // and neither is a third thing: deleting it belongs to the gallery row menu,
        // where delete already asks first.
        //
        // Gallery is NOT here. It was a button because browsing used to be a mode you
        // entered; it is the filmstrip now, so a button that opens a gallery you are
        // already standing in is a control with nothing to do. Calibrate is not here
        // either: it is a reported condition in Settings > Gyro, not something the
        // user does while looking at a photo.
        boolean hasMap = initialItem != null && initialItem.depthName != null;
        if (hasMap && !initial.isBundled()) {
            LinearLayout mapRow = new LinearLayout(this);
            mapRow.setOrientation(LinearLayout.HORIZONTAL);
            mapRow.addView(makeButton(getString(R.string.action_regenerate),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openDepthGen();
                        }
                    }), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            mapRow.addView(makeButton(getString(R.string.action_replace),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Reuses the active item's id, so it overwrites this
                            // photo's map rather than starting a new gallery row.
                            pickFile(REQ_DEPTH);
                        }
                    }), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            // The mockup's .btnrow gap. Two bordered plates sharing an edge read as
            // one control with a doubled border; the gap is what makes them two, and
            // it is set on the surviving child rather than passed to addView because
            // each makeButton already carries the right plate and pressed state.
            View regenButton = mapRow.getChildAt(0);
            LinearLayout.LayoutParams regenLp =
                    (LinearLayout.LayoutParams) regenButton.getLayoutParams();
            regenLp.rightMargin = dp(8);
            regenButton.setLayoutParams(regenLp);
            LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mapLp.topMargin = dp(2);
            mapRow.setLayoutParams(mapLp);
            sheetContent.addView(mapRow);
        }

        // The foot. A hairline, the debug line, then Settings. The debug line is
        // small, monospaced and dim on purpose: it answers "what is wrong", and a
        // 12sp white line in the middle of the controls read as content the user
        // had come to look at. It stays in the sheet as ONE line because noticing
        // something is off needs no navigation; the full block is in Settings,
        // where a person is already asking.
        View footRule = new View(this);
        footRule.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        ruleLp.topMargin = dp(14);
        footRule.setLayoutParams(ruleLp);
        sheetContent.addView(footRule);

        statusView = new TextView(this);
        statusView.setTextColor(0x57FFFFFF);
        statusView.setTextSize(10);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        // .debug { margin-top:14px; padding-top:9px } over the hairline above. The
        // same 9px at the bottom is what keeps the debug line and the foot plate
        // from reading as one cramped block at the very foot of the sheet.
        statusView.setPadding(0, dp(9), 0, dp(9));
        sheetContent.addView(statusView);

        // The sheet's foot: a gear icon button and the primary action, side by side.
        //
        // The gear is a glyph because a worded "Settings" plate competed for the
        // row's width with the action that matters, and "Set as wallpaper" is why
        // someone opens this sheet at all. Fixed at 48dp so it cannot steal width
        // from the action that takes the rest. A glyph alone is unnamed to a screen
        // reader, so it carries settings_title as its content description.
        //
        // Set as wallpaper moved here from the gallery's action row at the user's
        // request: one route now, not two. It opens the same ApplyChooser the
        // gallery's route did.
        LinearLayout footRow = new LinearLayout(this);
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        Button gearButton = makeButton(getString(R.string.settings_glyph),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSettings();
                    }
                }, dp(48), 0f);
        // The platform Button style carries its own 88dp minimum width, which would
        // override the 48dp LayoutParams. Setting the minimum here makes the square
        // the width that is actually laid out.
        gearButton.setMinimumWidth(dp(48));
        gearButton.setContentDescription(getString(R.string.settings_title));
        footRow.addView(gearButton);
        footRow.addView(makeButton(getString(R.string.apply_set_as_wallpaper),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ApplyChooser.show(MainActivity.this);
                    }
                }, 0, 1f));
        sheetContent.addView(footRow);

        panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(panel, panelLp);

        // The collapsed representation of the sheet. This replaces a "+" button,
        // which could only ever mean one thing and had to guess which. The pill has
        // three states -- idle, attention, progress -- so it can say what is going on
        // without the sheet being open.
        pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setVisibility(View.GONE);
        // 8px vertical, 18px horizontal, and a 38px minimum height. The height is a
        // floor rather than a fixed value so the pill does not change size between
        // "Controls" and the attention sentence -- it is one control in three states.
        pill.setPadding(dp(18), dp(8), dp(18), dp(8));
        pill.setMinimumHeight(dp(38));
        pill.setBackground(pillBackground(0x33FFFFFF));
        // Glass, so it floats over the render rather than sitting on it. The fill is
        // the mockup's rgba(28,32,38,.72); it used to be the same value in BOTH the
        // supported and unsupported branches, which made the fallback a no-op.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            pill.setBackgroundColor(0xFF1C2026);
        }

        pillMark = new View(this);
        android.graphics.drawable.GradientDrawable dot =
                new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(PILL_DIM);
        pillMark.setBackground(dot);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        markLp.rightMargin = dp(10);
        pill.addView(pillMark, markLp);

        pillLabel = new TextView(this);
        pillLabel.setTextSize(13.5f);
        pillLabel.setTextColor(0xFFFFFFFF);
        pillLabel.setMaxLines(1);
        // The ellipsis is on THIS view, not on the pill. On the pill it competed with
        // the dot and the horizontal padding for room, so a long sentence compressed
        // the dot into a speck before the text shortened at all. Here the dot is
        // fixed and the text takes what is left.
        pillLabel.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(pillLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expandUi();
            }
        });

        // The cap. A WRAP_CONTENT pill on its own measures against the screen, so a
        // long sentence runs edge to edge. Wrapping it in a full-width holder with 28px
        // side margins bounds what the pill is offered -- FrameLayout measures a
        // child AT_MOST its own width -- so the pill still hugs its text and still
        // cannot leave the screen.
        FrameLayout pillCap = new FrameLayout(this);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        capLp.leftMargin = dp(28);
        capLp.rightMargin = dp(28);
        capLp.bottomMargin = dp(26);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        pillCap.addView(pill, pillLp);
        // The cap is wider than the pill and a drag on its empty edge would reach the
        // render for the same reason the panel's would. The pill itself is a child and
        // keeps its click; this consumes only what the pill did not.
        pillCap.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return true;
            }
        });
        root.addView(pillCap, capLp);

        setContentView(root);

        // Open at the peek, but only after layout: the height is a fraction of the
        // panel's own measured height, which is zero until the first layout pass.
        //
        // A remembered position is deliberately NOT restored. It existed to avoid
        // launching with no controls at all, which reads as a broken launch -- but
        // the peek is a better answer to that than remembering, and restoring made
        // the launch position depend on wherever the sheet happened to be left. The
        // launch is now the same every time, which is what makes it learnable.
        //
        // Triggered by the panel's first LAYOUT CHANGE, not a global-layout
        // listener. A listener added to root.getViewTreeObserver() before the view
        // is attached uses that observer's pre-attach instance, which ViewRootImpl
        // replaces when the window attaches -- so the callback silently never ran
        // and the panel stayed at its full natural height. An OnLayoutChangeListener
        // is stored on the view itself, so it survives attachment and fires on the
        // first real layout pass, which is exactly when panelNaturalHeight is known.
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                panel.removeOnLayoutChangeListener(this);
                // Posted, not called inline. The listener fires at the end of the
                // layout pass, and applySheetHeight rewrites the panel's
                // LayoutParams; a requestLayout() made from inside that pass is
                // deferred to a second pass that measures the panel before the new
                // height is known, so the peek was computed and silently not
                // applied -- the sheet stayed at its natural height. A posted
                // runnable runs after the traversal, where the change is an
                // ordinary one.
                panel.post(new Runnable() {
                    @Override
                    public void run() {
                        openAtPeek();
                    }
                });
            }
        });
        // The filmstrip was installed into the panel above; it lives in the sheet
        // and survives the collapse, so it does not need opening.

        applySystemUi(); // decor view exists only after setContentView

        // Back must close the gallery grid and collapse the sheet before it can
        // leave the studio. Installed here, once the window exists, and after
        // setContentView so the dispatcher this registers on is final.
        installBackHandler();

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
                // Already in the gallery? Then this is the same photo sent again,
                // not a new one, and the row that owns it keeps its depth map and
                // framing. Sharing the same picture repeatedly used to add a row
                // every time, all of them reading the one file below.
                if (findByContent(bytes) != null) return null;
                // A unique name per import, the same as the picker's path. The
                // fixed name this replaced overwrote the previous share, so every
                // item pointed at the same bytes and the earlier photos were lost.
                File target = new File(getFilesDir(),
                        LibraryItem.imageFileName(LibraryItem.newId()));
                Io.writeFile(target, bytes);
                return target;
            }
        }, new Io.Callback<File>() {
            @Override
            public void onDone(File target, Exception error) {
                if (error != null) {
                    Toast.makeText(MainActivity.this,
                            "Failed to load shared image: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (target == null) {
                    // A null return means identical bytes are already in the
                    // gallery, and findByContent has already marked which row.
                    applyFiles();
                    refreshSliders();
                    Toast.makeText(MainActivity.this,
                            "Already in the gallery \u2014 selected it",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                // A shared image is a new item, not a replacement: sharing three
                // photos should leave three rows, and the two already in the
                // gallery must not lose their depth maps and framing to the third.
                LibraryStore.add(prefs, PrefFiles.store(target), null, pickedName);
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

    /**
     * The gallery row already holding these exact bytes, or null.
     *
     * <p>Selects it as a side effect, so a re-share leaves the user on the item
     * they just sent rather than on whatever was active before. Called from the
     * IO thread, and {@link LibraryStore} is the only thing it touches.
     */
    private LibraryItem findByContent(byte[] bytes) {
        for (LibraryItem item : LibraryStore.rows(prefs)) {
            if (item.isBundled()) continue;      // no file, so nothing to match
            File f = PrefFiles.resolve(getFilesDir(), item.imageName);
            if (f == null || !PrefFiles.sameContent(bytes, f)) continue;
            LibraryStore.select(prefs, item.id);
            return item;
        }
        return null;
    }

    /** Show the Depth AI dialog, pre-loaded with the current studio image. */
    /**
     * Repaint everything that can say a run is happening.
     *
     * <p>Wired to {@link DepthGenDialog#setRunListener} because the run lives on a
     * worker thread in a class that knows nothing about the sheet. Registered here,
     * once, where the dialog is first opened -- and never cleared, because the
     * reference is weak and this Activity is the thing being collected.
     */
    private void onDepthRunStateChanged() {
        AppState state = AppState.load(prefs, getFilesDir());
        refreshFixLine(state);
        refreshStatus();
        // The pill repaints only on a collapse otherwise, which is how a failed run
        // used to leave it reading "Generating" until the sheet was touched.
        updatePillVisibility();
    }

    private void openDepthGen() {
        DepthGenDialog.setRunListener(new DepthGenDialog.RunListener() {
            @Override
            public void onRunStateChanged() {
                onDepthRunStateChanged();
            }
        });
        if (depthGenDialog == null) {
            depthGenDialog = new DepthGenDialog(MainActivity.this,
                    new DepthGenDialog.Callback() {
                        /**
                         * The map has been written and attached already.
                         *
                         * <p>This callback used to be the ONLY place the depth name was
                         * written, and it looked the id up from the current selection
                         * at the moment the request came back. A run takes tens of
                         * seconds, so that is a different photo than the one the run was
                         * started for -- and one photo's depth map gets attached to
                         * another. The dialog now owns the write, because it captured
                         * the right id when the panel opened, and it reaches this
                         * callback only on success.
                         *
                         * <p>So all that is left here is showing the result.
                         */
                        @Override
                        public void onApplied(String itemId, String packedPath) {
                            prefs.edit().remove("status").apply();
                            applyFiles();
                            refreshSliders();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_depth_updated),
                                    Toast.LENGTH_LONG).show();
                        }

                        /** Retired. The dialog writes the map itself, on success only. */
                        @Override
                        public void onGenerated(String packedPath) {
                            prefs.edit().remove("status").apply();
                            applyFiles();
                        }
                    });
        }
        depthGenDialog.useCurrentImage();
        depthGenDialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The switch can become real between launches: applying puts the photo on a
        // second surface, and onCreate -- where it used to be decided -- does not run
        // again. This is the one line that makes it appear without a cold start.
        refreshSurfaceSegment();
        // Collect a run that finished while the app was closed. Not optional: the
        // run is process-static and its prompt id is in prefs, so nothing else in the
        // app would ever go back and ask.
        DepthGenDialog.resumePending(this);
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
        // The renderer writes to whatever surface it is told to. Never told, it
        // defaults to Home, which is right until the user picks Lock -- and wrong from
        // then on, silently: the segment says Lock, the numbers move, and Home is what
        // changed. Set here as well as in the segment's click, because onResume is the
        // first thing that runs after a recreation with the segment already on Lock.
        renderer.setEditSurface(editSurface);
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
        // The gallery owns the strip's thumbnails and click listeners; dismissing
        // it drops the strip and the grid with the hierarchy they live in.
        if (gallery != null) gallery.dismiss();
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
     *
     * <p><b>The navigation bar follows the sheet on Android 15+, and is hidden
     * below it.</b> The bar is the system's own back affordance, and API 35 is the
     * level where it composites over a live wallpaper, so 26–34 keep the immersive
     * flags they always had. On 35+ the bar is visible while the sheet is expanded
     * — back is discoverable while working — and hidden when the sheet is collapsed,
     * so the wallpaper is full-bleed and the collapsed pill is not sitting under a
     * bar. The decision is a function of the sheet's position and nothing else, so
     * the system's resulting inset change cannot feed back into it; see
     * {@link #updatePillVisibility} for where it is driven and why that cannot
     * oscillate.
     *
     * <p>The consequence of a visible bar is handled where {@code sheetScroll} is
     * built: once a bar can be visible, the sheet's foot can be occluded by it, so
     * the bar inset is folded into the sheet's bottom padding — the same fix the
     * modals already carry. When the bar is hidden the same padding recomputes to
     * zero, which {@link #requestSheetInsets} asks for after every change.
     *
     * <p>{@code setDecorFitsSystemWindows(false)} is API 30+ and is what makes the
     * transparent bar actually overlay our content rather than reserve a strip;
     * below 30 the layout flags do the equivalent. Both are guarded, because
     * minSdk is 26 and neither call may run on a level that does not have it.
     */
    private void applySystemUi() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
            }
            // Keep the window edge-to-edge; the bar's own visibility is decided
            // below. LAYOUT_HIDE_NAVIGATION is the layout half and is still the
            // flag path.
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            // Show or hide the bar through the controller, which is the supported
            // path on API 30+. The deprecated HIDE_NAVIGATION flag is not reliable
            // under Android 15's enforced edge-to-edge, and hiding is exactly what
            // the collapsed state asks for. Collapsed is the SAME threshold the pill
            // and the drag use, so "away" means one thing rather than three.
            android.view.WindowInsetsController controller =
                    window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                // A swipe may reveal the bar transiently; otherwise it stays gone,
                // which is what "fully invisible" means.
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController
                                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (sheetFraction >= SHEET_COLLAPSE_FRACTION) {
                    controller.hide(WindowInsets.Type.navigationBars());
                } else {
                    controller.show(WindowInsets.Type.navigationBars());
                }
            }
            requestSheetInsets();
            return;
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Re-read the bottom system-bar inset into the sheet's foot.
     *
     * <p>The bar is shown or hidden as the sheet collapses, so the inset the
     * padding is built from is not constant. Asking again is what keeps the foot
     * clear on a three-button bar — the tallest case, and the one that was
     * reported — where the bar covers the last ~48dp of the screen. It is safe on
     * every API: on 26–34 the bar never appears and the inset stays zero.
     */
    private void requestSheetInsets() {
        if (sheetScroll == null) return;
        sheetScroll.requestApplyInsets();
    }

    /**
     * How much room the sheet's foot must leave for the navigation bar.
     *
     * <p>Not just {@link ModalSheet#insetBottom}: that reports the bar's CURRENT
     * visibility, and on 35+ the bar is hidden until the system applies our
     * {@code show()} request. The dispatch that follows the request can therefore
     * carry 0 while the bar is about to appear, and no later dispatch is
     * guaranteed -- which is exactly why the foot was still under a three-button
     * bar: the listener ran once with inset 0 and the padding was never
     * recomputed. The bar's height REGARDLESS of visibility is the right number on
     * 35+, because the bar is visible whenever the sheet is expanded and the sheet
     * has no height whenever the bar is hidden, so reserving it can never be wasted
     * space. Below 35 the app hides the bar outright with the immersive flags and
     * the current visibility is the honest value.
     */
    private int sheetFootInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 35) {
            return Math.max(ModalSheet.insetBottom(insets),
                    insets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.navigationBars()).bottom);
        }
        return ModalSheet.insetBottom(insets);
    }

    // --------------------------------------------------------------------- back

    /**
     * The studio's own back handling, for the two things that are not dialogs.
     *
     * <p><b>Why the Activity needs one at all.</b> The gallery grid is a sibling
     * view in this Activity's root, and the sheet is this class's own panel — so
     * neither registers a back callback the way every {@link Dialog} does through
     * {@link ModalSheet#dismissOnBack}. Without this, the system back reaches the
     * Activity and finishes the app with the gallery open.
     *
     * <p><b>Why it is split by API level.</b> The app targets SDK 36 and the
     * manifest sets {@code android:enableOnBackInvokedCallback="true"}, so from
     * Android 13 a back press is delivered as an {@link OnBackInvokedCallback},
     * never as {@code KEYCODE_BACK}. {@link #onBackPressed()} is the complement on
     * 26–32 and is dead on a modern device. Exactly one branch is live per level;
     * the two must not both run.
     *
     * <p><b>Why the callback itself is not static state consumed elsewhere.</b>
     * It is registered on this Activity's own dispatcher and lives exactly as long
     * as the window does, which is the whole lifetime of these two views.
     */
    private void installBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            if (!handleBack()) finish();
                        }
                    });
        }
    }

    /**
     * Consume one back press, in the order the user sees the layers.
     *
     * <p>The gallery grid is the topmost takeover, so it goes first. The sheet is
     * next: back with it expanded collapses it — one step, from open to put away —
     * using {@link #collapseSheet}, which is exactly what a drag to the bottom
     * does. That keeps one notion of "collapsed" rather than a second state.
     *
     * <p>Returns false when nothing on this screen wanted the press, which is what
     * lets the caller fall through to finishing the app — the correct outcome for
     * a root screen with nothing open.
     */
    private boolean handleBack() {
        if (gallery != null && gallery.isGridShowing()) {
            gallery.dismissGrid();
            return true;
        }
        if (sheetFraction < SHEET_COLLAPSE_FRACTION) {
            // "Expanded" is the drag's own threshold — the same constant a live drag
            // compares against — rather than a second one that could disagree about
            // what "open" means. One back press puts the sheet away; a second, with
            // nothing else open, leaves the app.
            collapseSheet();
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // The API 26–32 complement of installBackHandler's callback. On 33+ this is
        // never called because the callback supersedes it; see installBackHandler.
        if (!handleBack()) super.onBackPressed();
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

    /**
     * Take the sheet out of the way, leaving the run going.
     *
     * <p>What "Run in background" means. It does not cancel -- collapsing never does
     * (R-S4) -- it just puts the work where the pill can report it, which is the only
     * place the percentage can be read while the controls are in the way.
     *
     * <p>Animated, unlike {@link #expandUi}: a short motion here reads as the sheet
     * getting out of the way, and the alternative is the controls vanishing under the
     * finger with no acknowledgement at all.
     */
    /**
     * The one glide rate, as the studio uses it. Same setting, same reading, as the
     * engine's -- one motion, one number, or three numbers nobody can predict.
     */
    /**
     * Keep frames coming while a glide is in flight.
     *
     * <p>The renderer steps the tween at the top of every drawn frame, which makes
     * the glide composite with the gyro for free -- but only if frames are drawn.
     * This surface is {@code RENDERMODE_WHEN_DIRTY} and a still phone produces no
     * sensor events, so without this the preview would show the glide's first frame
     * and freeze there.
     *
     * <p>Self-chaining on a handler rather than a ValueAnimator, for the same reason
     * the tween lives in the draw loop: an animator attached to a view nobody is
     * requesting frames for is an animation that never runs.
     */
    private final android.os.Handler glidePump = new android.os.Handler();
    private final Runnable glideTick = new Runnable() {
        @Override
        public void run() {
            if (renderer == null || !renderer.isGliding()) return;
            glView.requestRender();
            glidePump.postDelayed(this, 16);
        }
    };

    private void pumpGlideFrames() {
        glView.requestRender();
        glidePump.removeCallbacks(glideTick);
        glidePump.postDelayed(glideTick, 16);
    }

    private float glideSpanMs() {
        try {
            return Math.max(1f, prefs.getInt(TiltController.KEY_GLIDE_MS,
                    TiltController.GLIDE_DEFAULT_MS));
        } catch (Exception e) {
            return TiltController.GLIDE_DEFAULT_MS;
        }
    }

    private void collapseSheet() {
        setSheetPosition(1f, true);
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
     * The demo's framing is editable too. It used to be disabled here, for the same
     * reason the sliders were: Framing discarded what was typed, so a live field
     * silently did nothing. Framing reads it now.
     */

    /**
     * The dashed box under the grip: at most one sentence and at most two actions.
     *
     * <p><b>This is the only signal that a photo has no depth map</b> (R-F3). There
     * used to be a greyed Depth slider with "no depth map" written beside it, which
     * was two controls for one fact and a control that could not do anything. The
     * Depth control's <i>absence</i> is now the signal, so a photo with a map shows
     * no line at all.
     *
     * <p>Drawn with a dashed border rather than a solid one so it reads as an
     * interruption rather than as another panel -- a solid card here looks like
     * content, and content is what the user came to look at.
     *
     * <p>At most two actions, never three (R-F2). The third thing you could want to
     * do with a depth map is delete it, and that is a gallery row action, not
     * something that belongs in a sentence you are reading under a handle.
     */
    /**
     * The one dashed line under the grip, outside the scroll.
     *
     * <p>Geometry is the mockup's: 14px side margins so it does not run edge to edge,
     * 11px corners, 11/12 padding, and 9px between the sentence and its actions. The
     * border is a field rather than a value baked into the constructor because the
     * failed state recolours it -- see {@link #fixBorderColour}.
     */
    private LinearLayout buildFixLine() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        DashBorder border = new DashBorder(dp(1), dp(6), FIX_BORDER, dp(11));
        fixBorder = border;
        box.setBackground(border);
        box.setPadding(dp(11), dp(11), dp(11), dp(12));

        fixText = new TextView(this);
        fixText.setTextColor(Color.WHITE);
        fixText.setTextSize(13);
        fixText.setLineSpacing(0, 1.42f);
        box.addView(fixText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actsLp.topMargin = dp(9);
        actions.setLayoutParams(actsLp);
        fixActions = actions;
        box.addView(actions);

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.leftMargin = dp(14);
        boxLp.rightMargin = dp(14);
        boxLp.bottomMargin = dp(10);
        box.setLayoutParams(boxLp);

        box.setVisibility(View.GONE);
        return box;
    }

    /** The dashed border itself, so a state can recolour it. */
    private DashBorder fixBorder;

    /**
     * Recolour the dashed edge.
     *
     * <p>The mockup's failure state turns the border {@code --bad} rather than
     * changing the sentence alone, because the border is what the eye lands on: a
     * dashed line saying "No answer" in white reads as an informational line.
     */
    private void setFixBorderColour(int colour) {
        if (fixBorder != null) fixBorder.setColour(colour);
    }

    /** The mockup's dashed edge: rgba(255,255,255,.3), and --bad for a failure. */
    private static final int FIX_BORDER = 0x4DFFFFFF;
    private static final int BAD_COLOUR = 0xFFE2725B;

    /**
     * Show the fix line, or take it away.
     *
     * <p>With no condition holding it is GONE rather than blank (R-F4): an empty box
     * with a dashed border is a permanent ornament that trains the eye to stop
     * reading it.
     */
    private void setFixLine(CharSequence sentence, String primaryLabel,
                            View.OnClickListener primary, String secondaryLabel,
                            View.OnClickListener secondary) {
        setFixLine(sentence, primaryLabel, primary, secondaryLabel, secondary, true);
    }

    /**
     * @param attention whether this condition means something needs the user. False for
     *                  a purely informational line, which must not turn the pill amber.
     */
    private void setFixLine(CharSequence sentence, String primaryLabel,
                            View.OnClickListener primary, String secondaryLabel,
                            View.OnClickListener secondary, boolean attention) {
        if (sentence == null) {
            fixLine.setVisibility(View.GONE);
            return;
        }
        fixLine.setVisibility(View.VISIBLE);
        fixText.setText(sentence);
        fixActions.removeAllViews();
        // Two at most, and the layout refuses to be asked for three.
        if (primaryLabel != null) {
            fixActions.addView(fixButton(primaryLabel, primary));
        }
        if (secondaryLabel != null) {
            fixActions.addView(fixButton(secondaryLabel, secondary));
        }
        // Attention is a property of the CONDITION, not of the fact that a fix line
        // exists. Setting it here meant the bundled demo -- whose line says only
        // "Bundled demo" and offers "Add a photo" -- showed an amber "No depth" pill
        // when there was nothing wrong at all. The caller decides.
        setPillState(attention ? PillState.ATTENTION : PillState.IDLE);
    }

    /**
     * One fix-line action.
     *
     * <p>Weighted to fill the row, so one action is the full width and two split it --
     * the mockup's {@code flex:1} on both. It was WRAP_CONTENT at the left, which made
     * a single action look like a stray chip rather than the whole row.
     *
     * <p>Colours set explicitly for the same reason every other button here does: the
     * app has no dark theme, so an unstyled Button arrives light-themed with dark
     * UPPER-CASE text, on a near-black card.
     */
    private Button fixButton(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), 0x24FFFFFF);
        b.setBackground(bg);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * The conditions the fix line knows how to report.
     *
     * <p>Deliberately a small closed set rather than a general mechanism. Every entry
     * is a thing the app can <i>do something about</i>; a line that can only inform
     * is a status paragraph, and this is one sentence.
     */
    private enum PillState { IDLE, ATTENTION, PROGRESS }
    private static final int WARN_COLOUR = 0xFFE8B04B;
    /** The idle status dot: --dimmer, because it is a fact rather than a prompt. */
    private static final int PILL_DIM = 0x8CFFFFFF;

    private void setPillState(PillState state) {
        // The CONDITION, as opposed to what is drawn. Kept separately because a
        // running generation is a transient override: when it ends, the pill must go
        // back to whatever the fix line last said rather than to a stale copy of
        // progress. Overwriting the condition with the transient state is what made
        // a finished run leave the pill saying "Generating" forever.
        pillState = state;
        updatePillVisibility();
    }

    /**
     * Draw one state, and nothing else.
     *
     * <p><b>This method must not call back into {@link #updatePillVisibility}.</b>
     * Splitting "record the condition" from "draw it" put a trailing visibility call
     * in the drawing half while the visibility half ended by calling the drawer, and
     * the two recursed into each other until the stack ran out. Sliding the sheet down
     * is the only gesture that reaches this path, which is why it survived every other
     * interaction anyone tried.
     *
     * <p>The call graph is one-way on purpose:
     * {@code setPillState} → {@code updatePillVisibility} → {@code applyPillState}.
     * Nothing below this method calls upward.
     *
     * <p>Also skips the work when the state has not changed. This runs on every frame
     * of a drag, and each draw allocated a fresh GradientDrawable and ProgressRing --
     * per frame, on the UI thread, for a pill whose appearance had not moved.
     */
    private void applyPillState(PillState state) {
        if (pill == null) return;
        // The same STATE is not always the same PICTURE: PROGRESS carries a number.
        // Guarding on the state alone would have frozen the percentage at whatever it
        // was when the run started -- a progress bar that does not progress. Read once
        // here so the guard and the text cannot disagree about it.
        int percent = state == PillState.PROGRESS
                ? DepthGenDialog.progressPercent() : -1;
        if (pillDrawnState == state && pillDrawnPercent == percent) return;
        pillDrawnState = state;
        pillDrawnPercent = percent;
        switch (state) {
            case IDLE:
                pillLabel.setText(R.string.pill_controls);
                pillLabel.setTextColor(0xFFFFFFFF);
                pill.setBackground(pillBackground(0x33FFFFFF));
                setPillMark(false);
                break;
            case ATTENTION:
                pillLabel.setText(R.string.pill_no_depth);
                pillLabel.setTextColor(WARN_COLOUR);
                // The BORDER carries the warning too. Changing only the text colour
                // left a pill that read as informational at a glance, which is the
                // opposite of the point -- the border is what you see first.
                pill.setBackground(pillBackground(WARN_COLOUR));
                setPillMark(false);
                break;
            case PROGRESS:
                // The percentage comes from the same read the guard used.
                pillLabel.setText(getString(R.string.pill_generating, percent));
                pillLabel.setTextColor(0xFFFFFFFF);
                pill.setBackground(pillBackground(0x33FFFFFF));
                setPillMark(true);
                break;
            default:
                break;
        }
    }

    /** An 8dp dot when idle, a 15dp spinning ring while a generation runs. */
    private void setPillMark(boolean progress) {
        if (pillMark == null) return;
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) pillMark.getLayoutParams();
        if (progress) {
            if (pillRing == null) {
                // The ring's HEAD is white and its track is .28 -- an accent head
                // reads as a mint spinner, which is a different component.
                pillRing = new ProgressRing(dp(15), dp(2), 0xFFFFFFFF, 0x47FFFFFF);
            }
            pillMark.setBackground(pillRing);
            lp.width = dp(15);
            lp.height = dp(15);
            pillRing.start();
        } else {
            if (pillRing != null) pillRing.stop();
            android.graphics.drawable.GradientDrawable dot =
                    new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            // Dim when idle, bright when something needs attention: the dot is the
            // one part of the pill you notice before you read.
            dot.setColor(pillState == PillState.ATTENTION ? WARN_COLOUR : PILL_DIM);
            pillMark.setBackground(dot);
            lp.width = dp(8);
            lp.height = dp(8);
        }
        lp.rightMargin = dp(10);
        pillMark.setLayoutParams(lp);
    }

    /**
     * The pill is the sheet's <b>collapsed</b> representation, so it shows only when
     * the sheet is away.
     *
     * <p>It used to appear alongside the fix line as well, saying the same thing
     * twice on one screen. Attention is a property of the condition rather than of
     * the sheet's position: {@link #setPillState} records it, and this only decides
     * whether there is anywhere to show it.
     */
    private void updatePillVisibility() {
        if (pill == null) return;
        boolean collapsed = sheetFraction >= SHEET_COLLAPSE_FRACTION;
        pill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        // The navigation bar is part of the same decision, driven by the one value
        // that decides "collapsed" here. Expanded, back is discoverable and the bar
        // composites over the render; collapsed, the wallpaper must be full-bleed.
        // 26–34 keep their always-immersive flags. Guarded on attachment so a
        // position resolved before the window exists cannot force the decor early;
        // onWindowFocusChanged applies the same state once there is one.
        if (Build.VERSION.SDK_INT >= 35 && panel != null && panel.isAttachedToWindow()) {
            applySystemUi();
        }
        if (!collapsed) return;

        // A run in progress outranks the condition. This is the one thing worth
        // showing while the sheet is away: it is the only feedback a user gets for a
        // generation they started and then slid the sheet over. Read on every
        // collapse rather than pushed, because the run is started from a dialog that
        // is not this class and there is no callback to hang the end on.
        // A run in progress outranks the condition. applyPillState supplies the
        // percentage itself; this method's whole job is deciding WHICH state applies.
        applyPillState(DepthGenDialog.isRunning() ? PillState.PROGRESS : pillState);
    }

    /**
     * The pill's glass: the mockup's rgba(28,32,38,.72) with a hairline edge.
     *
     * <p>An opaque-enough fill with an edge is the whole job -- the sheet it stands in
     * for is glass over a render, and the render can be anything. The border is a
     * parameter because attention changes it, which is the part you notice first.
     */
    private android.graphics.drawable.GradientDrawable pillBackground(int stroke) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(999));
        d.setColor(0xB81C2026);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView fieldCaption(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        // Was unset, so it inherited the light theme's near-black text and vanished
        // against the dark sheet. --dim is the mockup's caption colour.
        t.setTextColor(0x8CFFFFFF);
        t.setPadding(dp(4), dp(4), dp(4), 0);
        return t;
    }












    // ---------------------------------------------------------------- bottom sheet

    /**
     * The drag strip, added as the panel's FIRST child so it sits above the title.
     *
     * <p>The grip is the sheet's own affordance, so it always drags; {@link
     * SheetPanel} arbitrates drags everywhere else. Both go through the same
     * {@link #beginSheetDrag}/{@link #moveSheetDrag} pair, so the gesture cannot
     * behave differently depending on where on the sheet it started.
     */
    private View buildSheetHandle() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(android.view.Gravity.CENTER);
        // No band. A full-width translucent fill across the sheet's top edge reads as
        // a header; the mockup draws a 38x4 bar and nothing else. The strip stays as
        // the touch target, which is what the height is for.
        strip.setBackground(null);
        strip.setContentDescription(getString(R.string.sheet_drag_handle));
        strip.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));

        // A short centred bar inside the strip: the strip is the touch target, the
        // bar is the thing that reads as a handle.
        View grip = new View(this);
        LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gripLp.setMargins(0, dp(9), 0, 0);
        // The mockup's grip bar: rgba(255,255,255,.42).
        grip.setBackgroundColor(0x6BFFFFFF);
        grip.setLayoutParams(gripLp);
        strip.addView(grip);

        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // The grip is a control of the sheet's own, so the panel's
                        // arbitration stands down for the whole gesture rather than
                        // deciding the same drag a second time.
                        panel.requestDisallowInterceptTouchEvent(true);
                        beginSheetDrag(e);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        return moveSheetDrag(e);
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        finishSheetDrag();
                        panel.requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return strip;
    }

    // ------------------------------------------------------------- sheet dragging

    /**
     * Record where a sheet drag starts. Does not claim anything yet.
     *
     * <p>Called from the grip's DOWN and from {@link SheetPanel}'s intercept DOWN,
     * which is the one event that is guaranteed to arrive before any child can
     * claim the gesture. Claiming happens on the first MOVE past the slop, so a
     * tap is not a drag.
     */
    private void beginSheetDrag(MotionEvent e) {
        sheetDragDownRawX = e.getRawX();
        sheetDragDownRawY = e.getRawY();
        sheetDragDownFraction = sheetFraction;
        sheetDragClaimed = false;
    }

    /**
     * Follow a MOVE the sheet owns. Claims the gesture past {@link #DRAG_SLOP_DP}.
     *
     * @return true, so the caller's touch listener consumes the event.
     */
    private boolean moveSheetDrag(MotionEvent e) {
        float dy = e.getRawY() - sheetDragDownRawY;
        if (!sheetDragClaimed && Math.abs(dy) > dp(DRAG_SLOP_DP)) {
            claimSheetDrag(dy);
        }
        if (sheetDragClaimed) dragSheet(dy);
        return true;
    }

    /** The sheet takes the gesture: stop the animation and follow from here on. */
    private void claimSheetDrag(float dy) {
        sheetDragClaimed = true;
        sheetDragging = true;
        // A half-finished animation would otherwise leap to wherever the animator
        // happened to be on the next frame.
        panel.animate().cancel();
        dragSheet(dy);
    }

    /**
     * Move the sheet by a drag that has already claimed the gesture.
     *
     * <p>Measured from the DOWN every time, never incrementally: a double delivery
     * (the intercept applying the claiming MOVE and the listener receiving it) is
     * the same number twice instead of twice the travel.
     *
     * <p>Positive dy is a downward drag, and {@code sheetFraction} measures how far
     * DOWN the sheet is, so the two add. Subtracting here is what made the handle
     * run backwards: pull down and it opened further.
     */
    private void dragSheet(float dy) {
        float h = panelHeight();
        if (h <= 0f) return;
        float next = sheetDragDownFraction + dy / h;
        if (next < 0f) next = 0f;
        if (next > 1f) next = 1f;
        sheetFraction = next;
        if (next >= SHEET_COLLAPSE_FRACTION) {
            // Close enough to the bottom to count as gone. Collapsing here rather
            // than on release means the sheet never rests as a useless sliver with
            // the handle barely reachable, and the pill appears under the finger
            // rather than after it lifts. The gesture is done moving the sheet.
            sheetDragClaimed = false;
            setSheetPosition(1f, false);
        } else {
            // Live and unsnapped: the panel goes exactly where the finger is and
            // stays there on release. A layout height, not a translation - the same
            // reason applySheetHeight exists.
            applySheetHeight(1f - next);
        }
    }

    /**
     * The finger lifted or the gesture was taken away.
     *
     * <p>Only the saved position is updated. No snapping: where you let go is
     * where it rests.
     */
    private void finishSheetDrag() {
        if (sheetDragClaimed) setSheetPosition(sheetFraction, false);
        sheetDragClaimed = false;
        sheetDragging = false;
    }

    /**
     * The sheet's panel, and the one place a vertical drag is arbitrated.
     *
     * <p>The rule, in one sentence: <b>a vertical drag moves the sheet unless a
     * control below it claimed the gesture, or the scrolling content can still use
     * it in that direction.</b> It is explicit rather than a fight because each of
     * the three claimants says so in its own way:
     *
     * <ul>
     *   <li><b>Controls.</b> A slider track calls {@code
     *       requestDisallowInterceptTouchEvent(true)} on touch-down, so the platform
     *       never offers its gesture here at all. The strip's {@code
     *       HorizontalScrollView} likewise claims horizontal drags. Those are the
     *       "controls" the user's sentence excludes.</li>
     *   <li><b>The scroll.</b> A touch that starts inside {@link #sheetScroll} is
     *       offered to the scroll first: while it can scroll further in the drag's
     *       direction, the sheet stays out. At the top or the bottom — "already at
     *       the end" — it cannot, so the same drag moves the sheet, which is the
     *       standard bottom-sheet feel.</li>
     *   <li><b>The sheet.</b> Everything else — the grip, the filmstrip, the fix
     *       line, captions, the gaps — reaches the panel and moves the sheet.</li>
     * </ul>
     *
     * <p>A drag that is not clearly vertical (dy no larger than dx) is left for
     * whoever under the finger wants it, so the strip's horizontal scroll and the
     * scroll's diagonal flings are not stolen by a few pixels of drift.
     */
    private final class SheetPanel extends LinearLayout {

        SheetPanel(android.content.Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Every gesture starts here: this runs before any child can
                    // claim anything. The outcome is only recorded; it is claimed
                    // on a MOVE.
                    sheetDragFromScroll = isInsideScroll(e.getY());
                    beginSheetDrag(e);
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (sheetDragClaimed) return true;
                    float dy = e.getRawY() - sheetDragDownRawY;
                    float dx = e.getRawX() - sheetDragDownRawX;
                    if (Math.abs(dy) <= dp(DRAG_SLOP_DP)) return false;
                    // A mostly-horizontal drag belongs to whatever scrolls
                    // horizontally — the filmstrip — not to the sheet.
                    if (Math.abs(dy) <= Math.abs(dx)) return false;
                    if (sheetDragFromScroll && sheetScroll != null
                            && sheetScroll.canScrollVertically(dy > 0f ? -1 : 1)) {
                        // The scroll can use this one; it is not the sheet's.
                        return false;
                    }
                    claimSheetDrag(dy);
                    return true;
                }
                default:
                    return false;
            }
        }

        /**
         * Whether a panel-local y lands in the scrolling content.
         *
         * <p>The scroll is a direct child of the panel, so its top and bottom are
         * already panel coordinates and no global offset is needed.
         */
        private boolean isInsideScroll(float y) {
            return sheetScroll != null
                    && y >= sheetScroll.getTop() && y < sheetScroll.getBottom();
        }
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
    /**
     * Put the sheet at {@link #SHEET_PEEK_FRACTION} of the phone, capped at its own
     * content height.
     *
     * <p>Capped, never padded: a sheet shorter than the fraction opens fully rather
     * than being inflated with padding to reach it, because padding would be a row
     * of dead space that looks like a control and is not one.
     *
     * <p><b>The launch state is the peek, and measuring before applying is what makes
     * that true.</b> This used to call {@code applySheetHeight(1f)} whenever the
     * panel had not been measured, which is every fresh launch -- so the sheet opened
     * at 100% of its natural height and the design's peek was never reached.
     * Because the filmstrip sits on whatever is beneath it, the over-tall sheet is
     * also why the strip rode up to y=0. Learning {@link #panelNaturalHeight} here,
     * from a post-layout callback where {@code getHeight()} is already measured, is
     * the whole fix.
     */
    private void openAtPeek() {
        if (panelNaturalHeight <= 0) {
            // While no height has been imposed yet, getHeight() is the panel's
            // own wrap_content height -- the denominator the fraction is a fraction
            // of, and the only chance to read it before applySheetHeight freezes it.
            panelNaturalHeight = panel.getHeight();
        }
        if (panelNaturalHeight <= 0) {
            // Still unmeasured: no layout pass has run. Defer the whole decision
            // rather than guessing a fraction of an unknown height.
            panel.post(new Runnable() {
                @Override
                public void run() {
                    openAtPeek();
                }
            });
            return;
        }
        int peek = Math.round(SHEET_PEEK_FRACTION
                * getResources().getDisplayMetrics().heightPixels);
        peek = Math.min(peek, panelNaturalHeight);
        float showing = (float) peek / panelNaturalHeight;
        sheetFraction = 1f - showing;
        applySheetHeight(showing);
        updatePillVisibility();
    }

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
        updatePillVisibility();
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

    /**
     * Persist the renderer's framing, unless a surface glide is mid-flight.
     *
     * <p>A glide tweens all nine values over {@code glide_ms}, so for that window
     * the renderer's orbit and pan are an interpolation between two surfaces, not
     * either surface's framing. Persisting them would write the blend onto the
     * surface being edited — a cross-surface write with no gesture that means it,
     * and one that repeated switching drags both surfaces to their average. The
     * values are settled by the next touch, so skipping here costs nothing and the
     * glide's own destination is what a later save records.
     *
     * <p>This is the guard the two save sites below share. It is not the whole fix:
     * a gesture made <i>during</i> a glide is still overwritten by the tween, which
     * needs a glide cancel in {@code ReliefRenderer} and is reported rather than
     * faked here. It does stop the tween from being stored as though it were the
     * user's framing.
     */
    private void savePanIfSettled() {
        if (renderer.isGliding()) return;
        renderer.savePanToPrefs();
    }

    /** @see #savePanIfSettled — the tap-to-orbit variant, which saves origin too. */
    private void saveViewIfSettled() {
        if (renderer.isGliding()) return;
        renderer.saveViewToPrefs();
    }

    private boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // A gesture takes control from a glide in flight. Queued, not called:
                // the renderer's values are the GL thread's, and this runs on the UI
                // thread. Without it the tween and the finger contest the same values
                // for the rest of the span and the gesture is lost.
                glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        renderer.cancelGlide();
                    }
                });
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
                        // and leaves every other photo at its own zoom -- and to the
                        // surface being edited, like every other adjustment. This was
                        // hard-coded to HOME, so pinching while the sheet said Lock
                        // silently changed Home instead: the numbers moved, Lock did
                        // not, and the two surfaces converged on whichever was last
                        // pinched.
                        String zoomId = AppState.load(prefs, getFilesDir()).activeId();
                        if (zoomId != null) {
                            LibraryStore.editItem(prefs, zoomId, new LibraryStore.Editor() {
                                @Override
                                public LibraryItem edit(LibraryItem item) {
                                    return item.withZoom(editSurface, z);
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
                savePanIfSettled(); // persist pan state (volatile reads, UI-thread safe)
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
                                saveViewIfSettled();
                            }
                        });
                        glView.requestRender();
                    }
                }
                pointers.remove(id);
                tapCandidate = false;
                savePanIfSettled(); // persist pan state
                return true;

            case MotionEvent.ACTION_CANCEL:
                pointers.clear();
                tapCandidate = false;
                startDist = -1f;
                savePanIfSettled();
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

    /**
     * A group heading.
     *
     * <p>Uppercase with letter spacing is what makes an 11sp label read as a heading
     * rather than as one more slider name. Without it the groups blur into the
     * controls they introduce.
     */
    /**
     * A group heading, carrying the surface being edited.
     *
     * <p>The mockup's "On both surfaces" state suffixes every caption with the
     * surface -- {@code Standing out of the plane — lock} -- because a switch that
     * silently changes which set of values you are editing is a switch nobody can
     * trust. On Home, where there is nothing to distinguish, the plain heading stands.
     */
    private String heading(int plainRes, int surfaceRes) {
        if (editSurface != Surface.LOCK) return getString(plainRes);
        return getString(surfaceRes, getString(R.string.surface_lock));
    }

    private TextView sectionLabel(String text) {
        return sectionLabel(text, false);
    }

    /**
     * @param first the caption at the very top of the scroll. The mockup's
     *              {@code .caption:first-child} gets 2px rather than 16px, because the
     *              grip is already a visible boundary and a second band of space under
     *              it is dead space on the one screen every user opens.
     * @return the caption itself, so a caller can anchor a later insert to it
     *              rather than to an index that can be captured before its
     *              neighbours exist.
     */
    private TextView sectionLabel(String text, boolean first) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x57FFFFFF);
        tv.setTextSize(11);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.09f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        layOutCaption(tv, first);
        sheetContent.addView(tv);
        return tv;
    }

    /**
     * The mockup's {@code .caption} padding.
     *
     * <p>A method rather than an inline call because which caption is "first" can
     * change: hiding the depth caption when its row is absent makes "How it moves"
     * the first caption in the scroll, and it has to take the 2px top gap rather
     * than the 16px of an inter-group heading. Two captions cannot both be first,
     * and the depth row's presence is the one thing that decides which is.
     */
    private void layOutCaption(TextView tv, boolean first) {
        tv.setPadding(dp(4), first ? dp(2) : dp(16), dp(4), dp(7));
    }

    /**
     * Where in {@code sheetContent} the Depth row is inserted.
     *
     * <p>Directly after {@link #reliefCaption}, the first group caption, which is
     * the sibling that is always present. Re-derived here at insert time from the
     * live tree instead of kept as a field: the field this replaced was captured
     * with {@code indexOfChild(depthBarRow)} in {@code onCreate}, and when the
     * selected photo had no map there was no {@code depthBarRow} to take the index
     * of, so it stored 0. The next photo with a map was then inserted at 0 -- above
     * the hint and above the Home / Lock segment -- because a saved integer cannot
     * know that its neighbours were added after it was written.
     */
    private int depthInsertIndex() {
        int at = reliefCaption == null ? -1 : sheetContent.indexOfChild(reliefCaption);
        return at < 0 ? sheetContent.getChildCount() : at + 1;
    }

    /**
     * What the Picture sliders currently show.
     *
     * <p>Mirrors the renderer, not the store: these exist so a drag can preview
     * without reading the index on every frame. They are seeded by
     * {@link #refreshSliders} and updated as the sliders move, and the store is
     * only written on release.
     */
    private int shownBrightness = 0;
    private int shownShadow = 0;

    /**
     * One of the -/+ buttons beside a slider.
     *
     * <p>Goes through {@link #setBar} rather than writing the store directly, so a
     * nudge cannot skip the preview the drag does. {@code setBar} carries the
     * syncingZoom guard, so this does not re-enter the change listener and store the
     * value twice.
     *
     * @param direction -1 or +1
     */

    /**
     * Push a slider's value into the renderer, on the GL thread.
     *
     * <p>Called from a drag, a release and a -/+ nudge, so all three paths share
     * one copy: two copies of the same four branches is how one of them ends up
     * missing a key.
     */
    private void applyStoredSlider(String key, int value) {
        if ("zoom".equals(key)) renderer.setZoom(value / 100f);
        else if ("motion".equals(key)) renderer.setMotion(value / 100f * 3f);
        else if ("brightness".equals(key) || "shadow".equals(key)) {
            renderer.setAdjust(
                    "brightness".equals(key) ? value : shownBrightness,
                    "shadow".equals(key) ? value : shownShadow);
        } else renderer.setDepth(value / 100f);
    }

    private String formatValue(String key, int value) {
        if (key.equals("zoom")) return String.format(Locale.US, "%.1f×", value / 100f);
        if (key.equals("intensity")) return value + "%";
        if (key.equals("motion")) return value + "%";
        // Signed and bare: "40" on a -100..100 control already reads as an offset,
        // and a "%" here would be a lie -- neither is a percentage of anything.
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /**
     * One slider: label, track, value inside the fill, and a - / + pair.
     *
     * <p>Built by {@link BasreliefSlider} rather than from a stock SeekBar plus
     * buttons. The version it replaced was a vertical stack -- label, bar, then a
     * full-width "-" button and a full-width "+" button on their own rows -- so
     * every adjustment cost about 130dp and the sheet could not show its own foot.
     * As one 34dp horizontal row the whole set fits the peek.
     *
     * <p>No syncing guard is needed here, and that is not a simplification. The
     * guard existed because {@code setProgress} fires the change listener, so
     * populating a control wrote to storage. {@link BasreliefSlider#setValue} has no
     * listener to fire: populating it cannot write, by construction rather than by a
     * flag someone has to remember.
     */
    private View addSlider(final String key, int initial, int min, int max) {
        BasreliefSlider slider = BasreliefSlider.create(this, key, initial, min, max,
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        return formatValue(key, value);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        // The renderer already followed the finger through onPreview;
                        // this is the store, once, on release.
                        final int v = value;
                        glView.queueEvent(new Runnable() {
                            @Override
                            public void run() {
                                applyStoredSlider(key, v);
                            }
                        });
                        glView.requestRender();
                        persist(key, v);
                    }
                });
        // Live preview, once per pixel of travel. It reads shown* and never the
        // index -- and it must UPDATE them, because applyStoredSlider sends both
        // adjustments at once and a preview that reset the other one would flicker
        // as the user crossed from one slider to the other.
        slider.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                final int v = value;
                if ("brightness".equals(key)) shownBrightness = v;
                else if ("shadow".equals(key)) shownShadow = v;
                glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        applyStoredSlider(key, v);
                    }
                });
                glView.requestRender();
            }
        });
        // The sheet becomes glass while this control is in use; see
        // beginControlDim. Installed for every slider, framing rows included, so a
        // control cannot be dimmed on one path and not the other.
        dimWhileInteracting(slider);
        sliders.put(key, slider);
        if ("zoom".equals(key)) zoomSlider = slider;
        return slider.view();
    }

    // ------------------------------------------------------------- sheet dimming

    /**
     * How far every row but the one in use goes while a control is held.
     *
     * <p>The user's spec is 5 % sheet, 60 % held control. Those two cannot be
     * parent and child alpha, because Android multiplies them: dimming the panel
     * to 5% and setting the slider to 60% renders at 3%, which is invisible. So
     * the intent is implemented at the level it works at — each row and the panel
     * background go to 5% individually, and only the control's own row holds 60%.
     * Alpha is a child property rather than a transform, so this is safe over the
     * {@code GLSurfaceView} in a way a translation would not be.
     */
    private static final float DIM_ALPHA = 0.05f;

    /**
     * The held control's row.
     *
     * <p>Its previous alpha is recorded before this is applied, like every other
     * view the dim touches: the off-path walk stops at the held control, so this
     * write is the only one with no other record behind it.
     */
    private static final float HELD_ALPHA = 0.60f;

    /**
     * Every view this dim touched, with the alpha it had before.
     *
     * <p>A list rather than a blanket "restore to 1": a disabled slider lives at
     * 0.4, and a restore that did not remember would silently re-enable it. The
     * parallel list is the restore value for the view at the same index.
     */
    private final java.util.List<View> dimmedViews = new java.util.ArrayList<>();
    private final java.util.List<Float> dimmedAlphas = new java.util.ArrayList<>();

    /** The control currently holding the sheet dimmed, or null. */
    private BasreliefSlider dimmedControl;

    /**
     * Let this slider dim the sheet for the duration of its gesture.
     *
     * <p>{@link BasreliefSlider.OnInteraction} fires exactly once per gesture —
     * on a track touch-down or a nudge press, and again when the finger lifts or
     * the gesture is taken away — so this needs no bookkeeping of its own beyond
     * which control owns the dim.
     */
    void dimWhileInteracting(final BasreliefSlider slider) {
        slider.setOnInteraction(new BasreliefSlider.OnInteraction() {
            @Override
            public void onStart() {
                beginControlDim(slider);
            }

            @Override
            public void onEnd() {
                endControlDim(slider);
            }
        });
    }

    /**
     * Dim the sheet, leaving one control legible.
     *
     * <p>The panel's background is dimmed as a drawable, and the rows are dimmed
     * individually down the branch the held control sits on: every sibling off
     * that branch goes to {@link #DIM_ALPHA} and the branch's containers stay at
     * full alpha, or a parent's alpha would multiply the held row's 60% down to
     * nothing. A second finger landing on another slider takes the dim over
     * rather than stacking a second one.
     */
    private void beginControlDim(BasreliefSlider control) {
        if (dimmedControl == control) return;
        endControlDim(null);
        dimmedControl = control;
        setSheetBackgroundAlpha(DIM_ALPHA);
        dimOffPath(panel, control);
        // After the walk, so the held row's own value is 60% and not multiplied by
        // a dim it never received -- and RECORDED, because the walk returns at the
        // held control and never records it. Without this the one view the dim
        // changes is the one view the restore forgets, and the row stays at 60%
        // after the finger lifts. See recordAlpha.
        recordAlpha(control);
        control.setAlpha(HELD_ALPHA);
    }

    /** Put every dimmed view back. A no-op for a control that no longer owns it. */
    private void endControlDim(BasreliefSlider control) {
        if (control != null && dimmedControl != control) return;
        if (dimmedControl == null && dimmedViews.isEmpty()) return;
        dimmedControl = null;
        setSheetBackgroundAlpha(1f);
        for (int i = 0; i < dimmedViews.size(); i++) {
            dimmedViews.get(i).setAlpha(dimmedAlphas.get(i));
        }
        dimmedViews.clear();
        dimmedAlphas.clear();
    }

    /** The panel's glass, dimmed as a colour rather than as a parent alpha. */
    private void setSheetBackgroundAlpha(float alpha) {
        android.graphics.drawable.Drawable background = panel.getBackground();
        if (background != null) background.setAlpha(Math.round(alpha * 255f));
    }

    /**
     * Dim every view off the branch from {@code container} down to {@code active}.
     *
     * <p>Child properties all the way down, never a group alpha: the multiplication
     * that breaks the naive implementation is exactly the parent-child relationship
     * this walk avoids. An ancestor on the branch is left alone so the held row's
     * alpha is its own.
     */
    private void dimOffPath(View container, View active) {
        if (container == active) return;
        if (!(container instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) container;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == active || isAncestorOf(child, active)) {
                dimOffPath(child, active);
            } else {
                dimView(child);
            }
        }
    }

    private void dimView(View view) {
        recordAlpha(view);
        view.setAlpha(DIM_ALPHA);
    }

    /**
     * Remember a view's alpha, before the dim changes it, for the restore.
     *
     * <p>Every view whose alpha this dim writes must come through here. The held
     * control is the case that is easy to miss: {@link #dimOffPath} returns at the
     * held control, so it never reaches {@link #dimView}, and a restore driven
     * only by the walk leaves the row it was holding at {@link #HELD_ALPHA}
     * forever -- the reported "opacity does not come back on release".
     */
    private void recordAlpha(View view) {
        dimmedViews.add(view);
        dimmedAlphas.add(view.getAlpha());
    }

    private static boolean isAncestorOf(View maybeAncestor, View view) {
        if (!(maybeAncestor instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) maybeAncestor;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == view || isAncestorOf(child, view)) return true;
        }
        return false;
    }

    /**
     * Write one slider's final value, once, on release.
     *
     * <p>All three sliders write to the selected item. Motion used to be the
     * exception and went to the config, because it was once global; it is the
     * photo's now, so it takes the same path as Depth and Zoom.
     *
     * <p>The item's id is read here rather than captured when the slider was built,
     * so a slider left over from a previous selection writes to whatever is on
     * screen now -- which is what the user was looking at when they let go.
     *
     * <p>Depth and Zoom are refused for the bundled demo, and Motion is not. The
     * demo renders its framing at the defaults regardless of what the index holds,
     * so a stored zoom there would be a number the renderer never uses -- but
     * Motion is honoured on the demo, and refusing it would leave a live slider
     * that silently does nothing. See {@link Framing} for the same asymmetry.
     */
    private void persist(final String key, final int value) {
        final boolean isDepth = "intensity".equals(key);
        final boolean isZoom = "zoom".equals(key);
        final boolean isMotion = "motion".equals(key);
        AppState state = AppState.load(prefs, getFilesDir());
        // activeId() is NOT null for the bundled item -- it returns the reserved id
        // -- so a null check here let every slider write to it.
        LibraryStore.editItem(prefs, state.activeId(), new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                // Written to the surface being EDITED. Hard-coding Home here is
                // what made the segment a control that changed nothing: it would
                // move the switch, the label would change, and the values on screen
                // would be re-read from Home on the next resolve.
                if (isDepth) return item.withIntensity(editSurface, value);
                if (isZoom) return item.withZoom(editSurface, value);
                if ("brightness".equals(key) || "shadow".equals(key)) {
                    // The other adjustment is read from the SAME surface, or moving
                    // one of them would silently reset the other to its home value.
                    SurfaceSettings other = item.settings(editSurface);
                    return item.withAdjust(editSurface,
                            "brightness".equals(key) ? value : other.brightness,
                            "shadow".equals(key) ? value : other.shadow);
                }
                return item.withMotion(editSurface, value);
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
     * <p>Populating a control must never write. It used to need a
     * {@code syncingZoom} guard for exactly this -- {@code setProgress} fires
     * {@code onProgressChanged}, so selecting a photo silently rewrote the photo you
     * had just left with the values it was already showing. The flag is gone because
     * {@link BasreliefSlider#setValue} has no change listener to fire, so the trap no
     * longer exists rather than being merely avoided.
     */
    void refreshSliders() {
        AppState state = AppState.load(prefs, getFilesDir());
        LibraryItem item = state.activeItem();
        refreshFixLine(state);
        // The photo line and the lock note answer "what is the wallpaper doing",
        // which changes exactly when the selection does, so they are refreshed
        // from the same one read as everything else here.
        refreshPhotoLine(state);
        refreshSurfaceNote(state);
        syncDepthRow(state);
        // Read once, here, and used by both the sliders and the sensor policy
        // below. Two reads would be two chances to disagree, which is how a slider
        // ends up showing 0 while the sensor is still told to run.
        SurfaceSettings s = item == null ? null : item.settings(editSurface);
        // Every bar is live, including on the bundled demo. They used to be disabled
        // for it, because Framing forced the demo to the defaults and a live slider
        // there displayed a number the renderer ignored -- the same class of lie as a
        // status line claiming content that is not there. Framing reads the demo's
        // stored values now, so the premise is gone and so is the reason to disable.
        {
            setBar("intensity", s == null ? SurfaceSettings.DEFAULT_DEPTH : s.depth, true);
            setBar("zoom", s == null ? SurfaceSettings.DEFAULT_ZOOM : s.zoom, true);
            setBar("motion", s == null ? SurfaceSettings.DEFAULT_MOTION : s.motion, true);
            shownBrightness = s == null ? SurfaceSettings.DEFAULT_ADJUST : s.brightness;
            shownShadow = s == null ? SurfaceSettings.DEFAULT_ADJUST : s.shadow;
            setBar("brightness", shownBrightness, true);
            setBar("shadow", shownShadow, true);
        }
        // The four framing rows are sliders on this same sheet and must follow the
        // same one resolution. They live in FramingRows and register under different
        // keys, so this method never touched them -- leaving them showing the
        // previous surface's origin and pan after a switch, which the next nudge
        // then wrote onto the new surface. See FramingRows.refresh.
        FramingRows.refresh(this);
        // Motion decides whether the sensor can change a pixel at all, so it
        // decides whether the sensor should be running -- and Motion belongs to the
        // active photo now, so switching photos at Motion 0 parks the sensor even
        // if the photo you came from had it at 60. Read from the item rather than
        // asked of the renderer, because the value is on this thread already and a
        // getter would cost a hop to the GL thread to learn something in hand.
        if (sensorPolicy != null) {
            sensorPolicy.setMotionActive(s != null && s.motionScale() > 0f);
        }
    }

    /**
     * Add or remove the Depth control so it matches the photo on screen.
     *
     * <p>The row is built once, from whatever was selected at launch, so without
     * this a photo with no map would be shown a Depth slider after the user picked
     * one that has a map, and the reverse. That is R-D1 and R-PV5 at once: the
     * control's <i>presence</i> is the signal that there is a map, so a stale row is
     * a false statement about the photo rather than a cosmetic mismatch.
     *
     * <p>Removed from every lookup as well as from the view hierarchy. Leaving the
     * SeekBar in {@code bars} would let {@link #setBar} write to a detached view,
     * which does not throw and does nothing -- the kind of write that looks like it
     * worked.
     */
    private void syncDepthRow(AppState state) {
        boolean want = initialItemHasMap(state);
        // The heading goes with the control, and this is the one place that decides
        // both. The caption is not deleted when the map is absent: it is the anchor
        // the row is re-inserted after, so hiding it and bringing it back is what
        // makes "no map, no heading" and "map, heading" the same decision. A caption
        // over nothing is a group heading for a control that is not there.
        if (reliefCaption != null) {
            reliefCaption.setVisibility(want ? View.VISIBLE : View.GONE);
        }
        if (motionCaption != null) {
            // When the depth caption goes, this one becomes the scroll's first
            // caption and must take the 2px gap instead of the 16px inter-group one.
            layOutCaption(motionCaption, !want);
        }
        if (want && depthBarRow == null) {
            LibraryItem item = state.activeItem();
            SurfaceSettings s = item == null ? null : item.settings(editSurface);
            depthBarRow = addSlider("intensity",
                    s == null ? SurfaceSettings.DEFAULT_DEPTH : s.depth, 0, 100);
            // After the first caption and therefore under the Home / Lock segment,
            // wherever it belongs today. Derived, never saved -- see depthInsertIndex.
            sheetContent.addView(depthBarRow, depthInsertIndex());
        } else if (!want && depthBarRow != null) {
            sheetContent.removeView(depthBarRow);
            // Out of the map as well as out of the view tree. Left in it,
            // setBar would write to a detached view -- which does not throw and
            // does nothing, the kind of write that looks like it worked.
            sliders.remove("intensity");
            depthBarRow = null;
        }
    }

    /**
     * Whether this item has a depth map at all.
     *
     * <p>The bundled demo does -- it is the one asset pair in res/raw, and shipping
     * one without wiring the other renders correctly with no relief and no
     * explanation. See AGENTS.md.
     */
    private boolean initialItemHasMap(AppState state) {
        LibraryItem item = state.activeItem();
        return item != null && (item.isBundled() || item.depthName != null);
    }

    /**
     * The fix line, from the conditions that hold right now.
     *
     * <p>Three of the PRD's four are implemented: no depth map, a run in progress,
     * and a run that failed. Unreachable-server and failed-generation are the SAME
     * condition to a user, so they are one line and one pair of actions.
     *
     * <p><b>The fourth -- gyro neutral drifted -- is deliberately absent, and this
     * is an audit rather than an omission.</b> No drift value exists to read:
     * {@code TiltController} exposes tilt, sleep and battery state and nothing
     * about the neutral's stability, and its only slow-moving number,
     * {@code idleBaseX/Y}, is a baseline of where the phone is being <i>held</i> —
     * it tracks a deliberate angle exactly as readily as a drifting neutral,
     * which is the ambiguity the controller's own note says a timer cannot
     * resolve ("a timer cannot tell 'the user is holding an angle' from 'the
     * sensor has drifted'"). It is also private to that class, which this file
     * does not own, so there is no honest way to read it from here. A fix line
     * that fires on a held pose would report a condition nothing measures, so the
     * condition is dropped rather than faked; drift is corrected where it is
     * observed instead, by the recalibration on every wake and visibility change.
     *
     * <p>With a map present this shows NOTHING (R-F4). Not an empty box: a dashed
     * border left on screen trains the eye to stop reading it, which is how a real
     * condition stops being noticed.
     */
    private void refreshFixLine(AppState state) {
        LibraryItem item = state.activeItem();
        boolean bundled = item == null || item.isBundled();
        // Reset first, so only the branch that means it actually changes the colour.
        setFixBorderColour(FIX_BORDER);

        // A run in progress, and a run that just failed, are conditions too -- and
        // they outrank the bundled check, because they are about a real photograph.
        // The mockup puts both in the SHEET, not only on the collapsed pill: "the
        // sheet is open, so the fix line is already the signal".
        if (!bundled && DepthGenDialog.isRunning()) {
            setFixLine(getString(R.string.fix_generating,
                            Math.max(0, DepthGenDialog.progressPercent())),
                    getString(R.string.fix_run_in_background), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Collapsing is not cancelling; this just takes the sheet
                            // out of the way so the pill carries the rest of the wait.
                            collapseSheet();
                        }
                    }, null, null, false);
            return;
        }
        if (!bundled && DepthGenDialog.lastResult() == DepthGenDialog.Result.FAILED
                && (item.depthName != null || !DepthGenDialog.failureCause().isEmpty())) {
            // The sentence says what SURVIVED. An error that does not leaves the user
            // unable to tell whether their depth is gone, which is the worst outcome
            // this app has -- and it is the outcome the PRD named.
            setFixBorderColour(BAD_COLOUR);
            setFixLine(getString(R.string.fix_no_answer),
                    getString(R.string.fix_try_again), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openDepthGen();
                        }
                    },
                    getString(R.string.fix_choose_map), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pickFile(REQ_DEPTH);
                        }
                    });
            return;
        }
        if (bundled) {
            // The demo is editable now, so it has no missing map -- but it is the
            // "back to the default" row, and first run wants one sentence saying so
            // with a way to add a photo. That is a condition, not a badge.
            // attention=false: the bundled demo HAS a depth map and renders in relief,
            // so an amber pill reading "No depth" beside it was a lie. The five-arg
            // overload hard-codes attention=true, which is why this call site kept the
            // behaviour its own comment described as fixed.
            setFixLine(getString(R.string.fix_bundled_demo),
                    getString(R.string.fix_add_photo), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pickFile(REQ_IMAGE);
                        }
                    }, null, null, false);
            return;
        }
        if (item.depthName != null) {
            setFixLine(null, null, null, null, null);
            setPillState(PillState.IDLE);
            return;
        }
        // Two actions, and they are PEERS: the PRD settles that generating may be as
        // common as choosing, so neither is styled as the real one.
        setFixLine(getString(R.string.fix_no_depth),
                getString(R.string.fix_no_depth_actions), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openDepthGen();
                    }
                },
                getString(R.string.fix_choose_map), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickFile(REQ_DEPTH);
                    }
                });
    }

    // ----------------------------------------------------------- the photo line

    /**
     * The line that says what the wallpaper is doing with this photograph.
     *
     * <p>Three states, one view, exactly as the PRD asks:
     * <ul>
     *   <li>a playlist is rotating it — {@code playlist: Favourites · 3 of 6}
     *       (R-PL3). A wallpaper that changes on its own must never look like a
     *       bug, and only the list that is actually rotating the surface being
     *       edited counts;</li>
     *   <li>no surface is showing it — {@code not on a screen yet} (R-U3);</li>
     *   <li>otherwise the surface it is on, which is the surface the sheet is
     *       editing when the photo is on both ({@code — lock}, R-U2).</li>
     * </ul>
     *
     * <p><b>Why "on a screen" is a platform question.</b> The index's
     * applied-surfaces record is the source for <i>which</i> surfaces were
     * applied to, but it cannot answer <i>whether</i> any is: it defaults to
     * {@code {HOME}} so every never-applied photo in the gallery would claim the
     * home screen, and a home-only apply writes nothing new to it (see
     * {@code Library.withAppliedTo} and {@code withSelection}). What can answer
     * it is whether this app's own wallpaper component is currently bound, which
     * changes at exactly the apply moment the line has to report. See
     * {@link #ourWallpaperIsBound}.
     */
    private TextView buildPhotoLine() {
        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(4), dp(2), dp(4), dp(6));
        return t;
    }

    private void refreshPhotoLine(AppState state) {
        if (photoLine == null) return;
        LibraryItem item = state.activeItem();
        if (item == null) {
            photoLine.setVisibility(View.GONE);
            return;
        }
        photoLine.setVisibility(View.VISIBLE);
        String name = item.displayName();

        Playlists lists = Playlists.parse(prefs.getString(Playlists.KEY, null));
        Playlist rotating = lists.rotating(editSurface);
        if (rotating != null) {
            int at = rotating.indexOf(item.id);
            // A playlist that does not contain the current photo is not rotating
            // THIS photo. Naming it with a position would put a count on screen
            // that is sometimes false, which is the one thing a rotation status
            // may never be.
            if (at >= 0) {
                setPhotoLine(name, getString(R.string.photo_line_playlist,
                        name, rotating.displayName(), at + 1, rotating.size()));
                return;
            }
        }

        if (!ourWallpaperIsBound()) {
            setPhotoLine(name, getString(R.string.photo_line_not_on_screen, name));
            return;
        }

        // The surface named is the one being edited when it can differ (the photo
        // is on both), and Home otherwise. Reading it from editSurface rather
        // than picking a label in advance is what makes the line and the segment
        // agree by construction.
        Surface named = state.library.isOnBothSurfaces() && editSurface == Surface.LOCK
                ? Surface.LOCK : Surface.HOME;
        setPhotoLine(name, getString(R.string.photo_line_surface, name,
                getString(named == Surface.LOCK
                        ? R.string.surface_lock : R.string.surface_home)));
    }

    /**
     * Show the name and the state on one line, the name inked brighter.
     *
     * <p>The mockup draws a solid name box beside a dim state box. One
     * {@link android.text.Spannable} rather than two TextViews because the line
     * is one sentence that has to ellipsize as a whole; two views cannot
     * truncate text that would cross between them, and a long label would push
     * the state off the edge instead.
     */
    private void setPhotoLine(String name, String text) {
        android.text.SpannableString line = new android.text.SpannableString(text);
        int end = Math.min(name == null ? 0 : name.length(), text.length());
        line.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 0, end, 0);
        photoLine.setText(line);
        photoLine.setTextColor(0x8CFFFFFF);
    }

    /**
     * Whether this app's own live wallpaper is the one currently bound.
     *
     * <p>The platform is the authority on what is actually on a screen — the
     * app's own record is a statement of intent (see the class note on
     * {@code Library.appliedSurfaces}). Compared against this service's
     * component so a different live wallpaper cannot make this photo claim a
     * screen it is not on. The lock query is API 34+, where a lock-only binding
     * becomes expressible; below that the home query is the whole answer.
     *
     * <p>Never throws: {@code getWallpaperInfo} rethrows a dead system server's
     * {@code DeadSystemException} as an unchecked exception, and a line that took
     * the studio down with it would be worse than a line that says the
     * pessimistic thing.
     */
    private boolean ourWallpaperIsBound() {
        WallpaperManager wm = WallpaperManager.getInstance(this);
        if (wm == null) return false;
        android.content.ComponentName ours = DepthWallpaperService.wallpaperComponent(this);
        try {
            WallpaperInfo home = wm.getWallpaperInfo();
            if (home != null && ours.equals(home.getComponent())) return true;
            if (Build.VERSION.SDK_INT >= 34) {
                WallpaperInfo lock = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                return lock != null && ours.equals(lock.getComponent());
            }
        } catch (Exception e) {
            // No wallpaper service, or no visibility of whatever is bound. The
            // honest reading of "cannot tell" is the state that claims nothing.
        }
        return false;
    }

    // ----------------------------------------------------- same as home / revert

    /**
     * The line under the Home / Lock segment. See {@link #refreshSurfaceNote}.
     */
    private TextView buildSurfaceNote() {
        TextView t = new TextView(this);
        t.setTextSize(11);
        t.setTextColor(0x8CFFFFFF);
        // The mockup's .segnote hugs the segment: no top padding, and the gap
        // below it is what separates the note from the photo line.
        t.setPadding(dp(4), 0, dp(4), dp(8));
        t.setVisibility(View.GONE);
        return t;
    }

    /**
     * Show what Lock is inheriting, or offer to restore it (R-U5, R-U6).
     *
     * <p>{@code hasOwnLockSettings()} is the single source for whether the lock
     * screen has settings of its own; the app did not read it anywhere before
     * this, which is how the lock screen came to mirror home with nothing on
     * screen saying so and no way back once it did not.
     *
     * <p>Only rendered where the segment itself can appear. Below 35 there is no
     * second live engine, so a note describing Lock's inheritance would be a
     * promise about a surface whose values are never rendered.
     */
    private void refreshSurfaceNote(AppState state) {
        if (surfaceNote == null) return;
        LibraryItem item = state.activeItem();
        if (item == null || surfaceForSegment() == null || editSurface != Surface.LOCK) {
            surfaceNote.setVisibility(View.GONE);
            return;
        }
        boolean own = item.hasOwnLockSettings();
        surfaceNote.setVisibility(View.VISIBLE);
        surfaceNote.setText(own
                ? R.string.surface_revert_to_home : R.string.surface_same_as_home);
        // Dim while it is a fact, accent while it is an action, so the one state
        // that can be tapped reads as the one that can be tapped.
        surfaceNote.setTextColor(own ? 0xFF7FD1C1 : 0x8CFFFFFF);
        surfaceNote.setClickable(own);
        surfaceNote.setFocusable(own);
        surfaceNote.setOnClickListener(own ? revertLockSettings : null);
    }

    /**
     * Clear Lock's override so it follows Home again (R-U6).
     *
     * <p>{@code withSettings(Surface.LOCK, null)} is the clearing call: null is
     * not "no settings", it is the model's own representation of inheritance, and
     * {@code settings(LOCK)} falls back to Home for every reader. The write goes
     * through {@code LibraryStore.editItem}, which is a read-modify-write of the
     * one item and nothing else — the selection lives in the index and is not
     * touched, and an id that is no longer in the index is refused by
     * {@code editItem}'s first check, so a deleted photo cannot be resurrected
     * by a stale click. The item is re-read at click time rather than captured,
     * for the same reason every other write here is.
     */
    private final View.OnClickListener revertLockSettings = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final String id = state().activeId();
            if (id == null) return;
            LibraryStore.editItem(prefs, id, new LibraryStore.Editor() {
                @Override
                public LibraryItem edit(LibraryItem item) {
                    return item.withSettings(Surface.LOCK, null);
                }
            });
            // The sheet is editing Lock, so the numbers on it must re-resolve:
            // clearing does not change Home's stored values, but it does change
            // what LOCK renders, and leaving the old override on screen would be
            // a control showing values nothing renders. applyFiles re-resolves
            // the framing; refreshSliders repopulates every control and the note.
            applyFiles();
            refreshSliders();
        }
    };

    /**
     * The surface this photo can be switched between, or null when it cannot.
     *
     * <p>Two conditions, and both are real:
     *
     * <ul>
     *   <li>The photo must be <b>on both</b> surfaces. On one there is nothing to
     *       switch between.</li>
     *   <li>The platform must actually run a second live engine, which is API 35+.
     *       On 34 an engine can tell which screen it is, but there is still only one
     *       -- so the app knows the fact and still promises nothing. Asked of
     *       {@link SurfaceResolver} rather than of {@code Build.VERSION}, because that
     *       class holds the truth table and it is unit tested.</li>
     * </ul>
     *
     * <p>Below 35 this returns null and the segment is <b>absent</b>. Not disabled:
     * an absent control cannot be tapped and cannot lie about what it would do.
     */
    private Surface surfaceForSegment() {
        // The PLATFORM is the gate, not the applied-surfaces record.
        //
        // <p>This used to also require the photo to be applied to both surfaces, on
        // the design's reasoning that "on one surface there is nothing to switch
        // between". That reasoning is wrong on a platform that CAN run a second
        // engine: before lock is bound, the switch still edits stored values and the
        // preview, and those values take effect the moment the system binds lock --
        // so the control is not dead, it is early. And gating on the app's own
        // "applied" bookkeeping had a second cost: that record is the user's stated
        // intent, not a measurement of what the system bound, so the gate could be
        // wrong in both directions.
        //
        // <p>Below 35 this still returns null, because there the control IS dead --
        // no lock engine can ever exist, so Lock's values would be permanently
        // unread. Absent, per the design.
        if (!SurfaceResolver.supportsPerSurfaceLock(android.os.Build.VERSION.SDK_INT)) {
            return null;
        }
        return Surface.HOME;
    }

    /**
     * The Home / Lock switch: two buttons, and the whole sheet follows the choice.
     *
     * <p>Built from plain Buttons and added as a child of the scrolling content, so
     * it scrolls with everything else. That is what the mockup shows, and it keeps
     * the segment out of the sheet's fixed chrome, where a view over the GL surface
     * would be positioned by layout rather than by a transform -- the shape that
     * stops compositing entirely (b/7179570).
     */
    /**
     * The Home / Lock switch, always constructed, shown by {@link #refreshSurfaceSegment}.
     *
     * <p>Keeps its two buttons as fields so the active one can be restyled in place
     * rather than by rebuilding -- rebuilding is what made the switch unable to
     * appear without a cold start in the first place.
     */
    private LinearLayout buildSurfaceSegment(Surface current) {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(3);
        seg.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x24FFFFFF);
        seg.setBackground(bg);

        for (final Surface surface : Surface.values()) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(12.5f);
            // The mockup's .seg button, not the platform Button: 8px sides, a 7px
            // radius, and NO 48dp minimum. The theme's minimum is what made the
            // segment read as a heavy control -- a 48dp plate around a 12.5sp
            // label -- when the design draws a ~30px pill. An explicit height beats
            // the theme minimum where setMinHeight did not, and matches the fixed
            // heights the rest of the sheet already uses (the 34dp slider track).
            b.setMinWidth(0);
            b.setMinHeight(0);
            b.setPadding(dp(8), 0, dp(8), 0);
            b.setIncludeFontPadding(false);
            b.setGravity(Gravity.CENTER);
            b.setText(surface == Surface.HOME
                    ? getString(R.string.surface_home) : getString(R.string.surface_lock));
            boolean on = surface == current;
            b.setTextColor(on ? 0xFFFFFFFF : 0x8CFFFFFF);
            android.graphics.drawable.GradientDrawable tb =
                    new android.graphics.drawable.GradientDrawable();
            tb.setColor(on ? 0x28FFFFFF : 0x00000000);
            tb.setCornerRadius(dp(7));
            b.setBackground(tb);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (editSurface == surface) return;
                    editSurface = surface;
                    // Copy and paste must follow the surface being edited, or they
                    // silently always mean Home.
                    if (gallery != null) gallery.setSurface(surface);
                    // A volatile write from the UI thread, which is what the renderer's
                    // setters do. Without this the segment moved, the numbers moved,
                    // and two-finger pan and tap-to-orbit wrote to HOME anyway -- so
                    // Lock looked edited and Home was what changed.
                    renderer.setEditSurface(surface);
                    // The preview eases to the new surface's framing rather than
                    // snapping: the same glide the engine uses on a lock/unlock, the
                    // same rate, because it is the same motion -- "a switch, not a
                    // cut". Snapping here would also make the studio disagree with the
                    // product about what a surface change looks like.
                    glView.queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            AppState st = AppState.load(prefs, getFilesDir());
                            com.amoshydra.basrelief.core.render.Framing to =
                                    com.amoshydra.basrelief.core.render.Framing.of(
                                            st.activeItem(), surface);
                            renderer.glide(null, to, glideSpanMs());
                        }
                    });
                    // A tween that steps per frame animates nothing when nobody asks
                    // for frames, and this surface is WHEN_DIRTY with the phone
                    // lying still: no sensor, no frames, a frozen preview. Pump.
                    pumpGlideFrames();
                    refreshSliders();
                    // NOT applyFiles(). A surface switch changes no file -- same photo,
                    // same depth map -- and applyFiles queues renderer.apply(), which
                    // ends a glide in flight. Queued after the glide's own event, it
                    // cancelled the glide microseconds after arming it, every time,
                    // which is why the switch never animated: the arm and the cancel
                    // were both in the click handler. The foot line is the only other
                    // thing applyFiles did that this switch needs.
                    refreshStatus();
                    setSegmentActive(surface);
                }
            });
            if (surface == Surface.HOME) segHome = b; else segLock = b;
            setSegmentButton(b, surface == current);
            // .seg { gap:2px } -- the two buttons sit in the same track with a
            // hairline of track between them rather than sharing an edge. The fixed
            // height is the mockup's button box; see above for why it is not
            // WRAP_CONTENT and left to the platform Button's minimum.
            LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(0,
                    dp(30), 1f);
            if (surface == Surface.LOCK) segLp.leftMargin = dp(2);
            seg.addView(b, segLp);
        }
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        outer.bottomMargin = dp(10);
        seg.setLayoutParams(outer);
        return seg;
    }

    /** One button of the segment, and whether it is the surface being edited. */
    private void setSegmentButton(Button b, boolean on) {
        b.setTextColor(on ? 0xFFFFFFFF : 0x8CFFFFFF);
        android.graphics.drawable.GradientDrawable tb =
                new android.graphics.drawable.GradientDrawable();
        tb.setColor(on ? 0x28FFFFFF : 0x00000000);
        tb.setCornerRadius(dp(7));
        b.setBackground(tb);
    }

    /** Restyle the pair after a switch, or after the segment appears. */
    private void setSegmentActive(Surface surface) {
        if (segHome != null) setSegmentButton(segHome, surface == Surface.HOME);
        if (segLock != null) setSegmentButton(segLock, surface == Surface.LOCK);
    }

    /**
     * Show the switch only when it can change something.
     *
     * <p>Called from {@code onResume} and after an apply, which are the only two
     * moments the answer can change: the index's applied-surfaces record is written
     * by applying, and the platform version never changes.
     */
    private void refreshSurfaceSegment() {
        if (surfaceSegment == null) return;
        boolean show = surfaceForSegment() != null;
        // GONE, never INVISIBLE and never disabled-with-opacity: the design's rule is
        // that a control which cannot change anything is absent. GONE is absent.
        surfaceSegment.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) setSegmentActive(editSurface);
        // Called on resume, which is also where a just-finished apply lands: the
        // photo line's "on a screen" answer can change while the app was behind
        // the system picker, so re-read it here rather than only on a selection.
        AppState state = state();
        refreshPhotoLine(state);
        refreshSurfaceNote(state);
    }

    /**
     * The index says this photo is now on a second screen.
     *
     * <p>Called by the apply chooser after it records an intent, so the user is told
     * what was recorded rather than inferring it from a segment that has not appeared
     * yet.
     *
     * <p><b>The segment does NOT appear in response.</b> The sheet is built inline in
     * {@code onCreate}, so bringing it back means rebuilding the panel, the fix line
     * and every slider's captured initial value -- a large change to the largest
     * method in the app for one event. It appears the next time the studio opens,
     * which is immediately after the system picker the user is being sent to anyway.
     * Said plainly rather than left to be discovered: the alternative was a
     * {@code buildSheet()} that does not exist.
     */
    void onAppliedTo(Surface surface) {
        // Both branches are int resource ids, so the ternary cannot pick a String
        // overload -- hence two calls rather than one with a ternary.
        if (surface == Surface.LOCK) {
            Toast.makeText(this, R.string.applied_to_lock, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.applied_to_home, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The index says the photo is now on both screens, so the switch can exist.
     *
     * <p>The segment is built with the rest of the sheet and there is no
     * {@code buildSheet()} to re-run, so it still appears next time the studio opens
     * -- which is straight after the system picker this hands you back from. The toast
     * is what tells you in the meantime, and it names the outcome rather than saying
     * "done", because the system dialog may bind one screen and not the other and
     * this app cannot observe which.
     */
    void onAppliedToBoth() {
        Toast.makeText(this, R.string.applied_to_both, Toast.LENGTH_SHORT).show();
        // The switch becomes real this moment, and the sheet is already on screen --
        // so this is where it appears, not on the next launch.
        refreshSurfaceSegment();
    }

    /**
     * The photo was chosen for the lock screen on a platform with no live lock engine.
     *
     * <p>So the lock screen gets the frame it is about to be sent, captured from the
     * render currently on screen. The chooser already promises this — "the lock screen
     * keeps a still frame" — and until now nothing put one there, so the sentence
     * described something that did not happen.
     *
     * <p>Asks the GLSurfaceView for a frame because the capture has to happen on the GL
     * thread, after a draw and before the buffer is recycled. The renderer holds the
     * flag; the view owns when a frame happens.
     */
    void onLockSurfaceChosen() {
        if (!StaticLockFrame.isNeeded()) return;
        renderer.captureLockFrame();
        glView.requestRender();
    }

    /** Open the settings screen. Four groups, and nothing about a photo. */
    void openSettings() {
        SettingsSheet.show(this);
    }

    /** Re-read the diagnostics block. Called when it is tapped, not per gyro sample. */
    void refreshDiagnostics() {
        refreshStatus();
    }

    /**
     * Populate a slider from stored state.
     *
     * <p>No clamping and no guard, because {@link BasreliefSlider#setValue} does both
     * and has no change listener to fire -- so this cannot write, which is what the
     * old {@code syncingZoom} dance existed to prevent. Losing that flag is the
     * point, not a simplification.
     */
    private void setBar(String key, int value, boolean enabled) {
        BasreliefSlider slider = sliders.get(key);
        if (slider == null) return;
        slider.setEnabled(enabled);
        slider.setValue(value);
    }

    /** Update the zoom slider to match the current renderer zoom. */
    private void setZoomSlider(int percent) {
        if (zoomSlider == null) return;
        zoomSlider.setValue(percent);
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
        // 48dp is the platform's minimum touch target. The foot of the sheet lived
        // on 36dp buttons, which the device report read as text rather than as
        // buttons, and a three-button navigation bar made the miss worse.
        b.setMinimumHeight(dp(48));
        // Horizontal only: the 48dp minimum is what gives the tappable plate its
        // height, and the plate is the background, so what is drawn and what is
        // tappable are the same rectangle.
        b.setPadding(dp(12), 0, dp(12), 0);
        // The manifest theme is LIGHT (Theme.DeviceDefault.NoActionBar, no values-night),
        // so an unstyled Button arrives as a raised platform button with near-black
        // text -- invisible on a dark sheet. Every button sets its colours explicitly
        // rather than the app flipping a global theme, which would restyle the GL
        // surface's transparency and every platform dialog at once.
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(buttonPlate(0x1FFFFFFF, 0x33FFFFFF, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    /**
     * A button plate with a pressed state.
     *
     * <p>{@code setBackground} replaces the {@code StateListDrawable} the platform
     * Button would have used for its own feedback, so a plain
     * {@link android.graphics.drawable.GradientDrawable} leaves a button that looks
     * identical whether or not it is being pressed. This is the smallest state list
     * that answers a tap -- a brighter fill while {@code state_pressed} -- without a
     * second button style: the same plate is used by every {@code makeButton}.
     *
     * @param fill       the resting fill
     * @param pressed    the fill while the finger is down
     * @param radiusDp   corner radius, so a row of buttons can keep the sheet's 11dp
     */
    private android.graphics.drawable.StateListDrawable buttonPlate(
            int fill, int pressed, int radiusDp) {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable down =
                new android.graphics.drawable.GradientDrawable();
        down.setColor(pressed);
        down.setCornerRadius(dp(radiusDp));
        down.setStroke(dp(1), 0x24FFFFFF);
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        android.graphics.drawable.GradientDrawable up =
                new android.graphics.drawable.GradientDrawable();
        up.setColor(fill);
        up.setCornerRadius(dp(radiusDp));
        up.setStroke(dp(1), 0x24FFFFFF);
        states.addState(new int[]{}, up);
        return states;
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
            Toast.makeText(this, R.string.toast_pick_photo_first,
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
                    Toast.makeText(MainActivity.this, R.string.toast_depth_updated, Toast.LENGTH_SHORT).show();
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
        final Framing framing = Framing.of(state.activeItem(), editSurface);
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
        // One line: which map, and the gyro. The wireframe is explicit that this is
        // the sheet's whole debug content -- "the map's file name and the model that
        // made it are answers to 'what is wrong', and the sheet's Depth row is not" --
        // and that the engine's own view of itself belongs in Settings, where someone
        // is already asking. It used to append the engine line here too, which wrapped
        // to three dense lines at the one place the eye goes when something looks
        // wrong. DiagnosticsBlock still shows all of it.
        StringBuilder line = new StringBuilder();
        line.append(getString(R.string.diag_depth_prefix))
                .append(prefs.getString("status", ""));
        String gyro = prefs.getString("gyro_status", "");
        if (!gyro.isEmpty()) line.append("  |  ").append(gyro);
        statusView.setText(line.toString());
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

    /**
     * A {@link ScrollView} whose edge fade is drawn only at the foot.
     *
     * <p>The design masks the last 22px of the scroll ({@code final.html:57-59})
     * so a control below the fold is visibly reachable. Android's fading edge is
     * all-or-nothing at the API level and draws at <b>both</b> ends, so the top of
     * the scroll grew a bright band under the grip that the design never draws --
     * the "not aligned correctly" the user reported. Suppressing the top
     * <i>strength</i> is the one supported hook for a bottom-only fade; the bottom
     * edge is untouched and still uses {@code setFadingEdgeLength}.
     *
     * <p><b>Does the fade actually render? Yes, and it is worth saying why,
     * because the usual answer is misleading.</b> Android's fading edge changes
     * mechanism on {@code View.getSolidColor()}: a non-zero value would let it
     * draw the gradient as a src-over fill, and this view's is 0 — {@code View}'s
     * default implementation returns 0, and the panel's GradientDrawable belongs
     * to the panel, not to this view. The zero case is not the dead one: the edge
     * is drawn as a DST_OUT gradient over the content (API 28+ saves the edge
     * region with {@code saveUnclippedLayer} / {@code restoreUnclippedLayer};
     * 26–27 use an alpha {@code saveLayer}), which erases the content through the
     * gradient and reveals whatever is behind this view — the panel's own glass,
     * because the panel drew its background before its children. So the fade
     * lands on {@code 0xEB161A20}, exactly as the mockup's CSS mask lands on
     * {@code --panel}, with no background required on the scroll itself.
     *
     * <p>What that mechanism does imply is that the fade is anchored to these
     * view <b>bounds</b>: padding is inside the bounds and cannot move it, which
     * is why the navigation-bar inset is applied as a margin in {@code onCreate}.
     *
     * <p>This is a child effect, not a transform, so it is safe over the
     * {@code GLSurfaceView} -- the same reason the sheet positions itself by layout
     * height rather than {@code translationY} (see AGENTS.md and b/7179570).
     */
    private static final class BottomFadeScrollView extends ScrollView {
        BottomFadeScrollView(android.content.Context context) {
            super(context);
        }

        @Override
        protected float getTopFadingEdgeStrength() {
            // Zero disables the top fade without touching the bottom one.
            return 0f;
        }
    }
}
