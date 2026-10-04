package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.gesture.SheetIntent;
import com.amoshydra.basrelief.core.gesture.SheetMorph;
import com.amoshydra.basrelief.core.render.Framing;
import com.amoshydra.basrelief.core.render.LightDirection;
import com.amoshydra.basrelief.core.store.DepthSource;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.NormalSource;
import com.amoshydra.basrelief.core.store.Playlist;
import com.amoshydra.basrelief.core.store.PlaylistStatus;
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
import java.util.ArrayList;
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
        return AppState.load(this, prefs, getFilesDir());
    }
    private TextView statusView;
    private LinearLayout panel;

    // ------------------------------------------------------------ bottom sheet
    /**
     * The studio sheet's remembered position, as the collapse fraction. Written
     * on every release and read on launch: a persistent panel reopens where the
     * user left it, never corrected to a stop. 1.0 means it was put away, and
     * that reopens as the pill.
     */
    private static final String KEY_SHEET = "sheet_pos";
    /**
     * The last position the sheet rested at while it was <i>not</i> put away.
     *
     * <p>Separate from {@link #KEY_SHEET} on purpose, and the separation is what
     * makes a tap on the pill mean something. {@code sheet_pos} is 1.0 while the
     * sheet is away, so it cannot also be the answer to "how far was it open" —
     * one key cannot hold both the current state and the place to return to, and
     * overwriting the first with the second is how a pill tap ends up snapping the
     * sheet fully open and discarding the position the user chose. Both are
     * written together in {@link #onSheetResting} and neither is ever migrated:
     * a missing {@code sheet_open} reads as "never open this session", which is the
     * fully-open fallback the component already had.
     */
    private static final String KEY_SHEET_OPEN = "sheet_open_pos";

    /**
     * The collapsed sheet's height, and therefore the strip's height at every
     * fraction — the strip is this tall and the panel is never shorter.
     *
     * <p>56dp for two reasons that happen to agree. It is the mockup's pill row read
     * as a card rather than a chip, and it is comfortably over the 48dp Android
     * asks of a touch target — which matters now in a way it did not before,
     * because the whole collapsed sheet is this strip and it is the drag handle.
     * A shorter strip would be a handle the user has to aim at.
     */
    private static final int STRIP_HEIGHT_DP = 56;

    /**
     * The grip band inside the strip, above the pill's own row.
     *
     * <p>Its own number rather than "whatever is left", because the strip's
     * remaining height <i>is</i> the pill's row: the label is revealed by geometry,
     * not by fading, so at {@code fraction = 0} the leftover is zero and there is
     * no row to show. This is the height that makes the leftover zero.
     */
    private static final int STRIP_GRIP_DP = 22;

    /**
     * How far the pill may come from the screen's edges, in dp, TOTAL -- the design's
     * {@code max-width:calc(100% - 56px)}.
     *
     * <p><b>The pill is content-width, and this is the cap.</b> It was a fixed fraction
     * of the screen (0.72) and read as a bar with a label in it rather than as a pill:
     * the design wraps its text, so a two-word label makes a small pill and a long
     * filename makes a wide one, up to this cap. the pill was still too wide is that
     * difference, and the fraction could never have produced it.
     */
    private static final int PILL_MAX_INSET_DP = 56;
    /** A floor, so a one-character label is still a pill rather than a lozenge. */
    private static final int PILL_MIN_WIDTH_DP = 96;


    /**
     * How far the collapsed pill floats above the window's bottom edge.
     *
     * <p>26dp, from the same holder's {@code capLp.bottomMargin = dp(26)}. The old
     * comment on {@link #applySheetRadius} said the pill was "flush to the window's
     * bottom edge", which was true of the morph and false of the pill: the pill was
     * 26dp off it, and rounding its bottom corners while it was flush is what would
     * have shown the render through them.
     */
    private static final int PILL_BOTTOM_INSET_DP = 26;

    /**
     * The sheet's own glass, and so the strip's colour while the sheet is open.
     *
     * <p>The value {@link #panelBackground} is built with. The strip is the panel's
     * first child and covers its top {@link #STRIP_HEIGHT_DP} exactly, so when the
     * two differ the open sheet carries a plate on top that the morph then has to
     * explain away: a lighter band across the top of the sheet that becomes the
     * pill. Starting the strip at the panel's own colour makes it part of the sheet
     * it is a piece of, which is the same claim the height makes.
     */
    private static final int SHEET_GLASS = 0xEB161A20;

    /**
     * The pill's own plate, and so the strip's colour once collapsed.
     *
     * <p>0xB81C2026, which is {@code rgba(28,32,38,.72)} — and it is not a new
     * number. It is what {@code pillBackground} has always returned, and what
     * {@code 227a53f^} handed the original pill on all three of its states. The
     * morph did not invent the strip's fill; it inherited the pill's, and the
     * reason the collapsed sheet does not look like the pill was never the colour
     * at all.
     *
     * <p>Recovering it matters because the alternative is guessing, and the two
     * plausible guesses are both wrong in a way a user would see. The old
     * {@code PILL_DIM} ({@code 0x8CFFFFFF}) is <b>not</b> this pill's plate — it
     * was the status <i>dot</i> — and it survives as {@link #STRIP_DIM}, still
     * drawing the dot. A 55%-white plate would also have eaten that dot: the dot is
     * the same 55% white, so on a pill of that colour the one thing the collapsed
     * sheet exists to show would be invisible on it.
     *
     * <p><b>It is deliberately MORE transparent than the design's {@code .72}.</b> The
     * user: <i>the pill should be less visible, with a glass effect and an outline</i> The design gets its glass from
     * {@code backdrop-filter:blur(18px) saturate(150%)}, and blur behind a window is not
     * available to this app at all — measured, and it is why a Dialog cannot have it.
     * Without the blur, the same 72% fill reads as a solid dark plate rather than as
     * glass, so the transparency has to carry what the blur would have. The
     * {@code 0x33FFFFFF} outline is what keeps it legible once the fill is this weak:
     * the edge, not the fill, is what says "this is a surface" over a busy wallpaper.
     * This is the one knob if it wants to be lighter or heavier.
     */
    private static final int PILL_PLATE = 0x8C1C2026;
    /**
     * How much of the phone the sheet occupies when no position has been stored.
     *
     * <p>The render is the product; the controls are an invitation. Opening fully
     * spends two thirds of the screen on sliders before the user has seen the thing
     * they are adjusting, which is the wrong order. A third is the user's number:
     * enough sheet to show the grip, the filmstrip and the first row or two, and
     * two thirds of the screen left for the photograph.
     *
     * <p>This is only the FIRST launch's position. The sheet does not snap, so
     * after the first drag the stored fraction is what it reopens at; see
     * {@link BottomSheet.Snap#FREE}.
     */
    private static final float SHEET_PEEK_FRACTION = 1f / 3f;
    /** A vertical move past this claims the gesture, so a tap on the strip is a no-op. */
    private static final int DRAG_SLOP_DP = 8;

    /**
     * The sheet's one gesture: entrance, drag arbitration, release intent, settle
     * and fade.
     *
     * <p>Everything about the <i>rule</i> is in {@link BottomSheet}; this class
     * supplies the mechanism, because it owns the views. The policy is
     * {@link BottomSheet.Snap#FREE}: the studio sheet is a persistent panel, so a
     * release keeps the exact position it left unless it was a collapse — no
     * snap points, no rounding — and that position is stored and restored. The
     * panel moves by layout height with an entrance carried in its bottom margin,
     * because a sibling of a {@code GLSurfaceView} cannot be positioned by a
     * transform (b/7179570), and margins are a layout change, which the platform
     * note itself recommends.
     */
    private BottomSheet sheet;
    /**
     * The height of the scroll's foot fade, in dp.
     *
     * <p>The mockup masks the last 22px ({@code final.html:57-59}), and this is
     * a deliberate deviation from it: the user asked for the gradient to be
     * "slightly taller" after seeing the 22dp version land. One named number,
     * so a tune is an edit here and nowhere else; 32 is 22 plus the 10dp that
     * reads as "slightly" on the devices this was checked on.
     */
    private static final int SHEET_FADE_DP = 32;

    /**
     * The panel's own LayoutParams, kept so its height can be changed at runtime.
     *
     * <p>Position is expressed as a layout height plus an entrance bottom margin,
     * rather than as {@code translationY}, and that is not a stylistic choice - it
     * is the difference between working and not. See {@link #applySheetPosition}.
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
     * bottom inset, so the space that keeps the foot clear of the bar has to be
     * recomputed. {@link #requestSheetInsets} is the one place that asks.
     *
     * <p>The inset is <b>content padding</b>, not a margin or padding on THIS
     * view, and that is load-bearing: Android draws a scroll view's fading edge
     * at the view's bounds, so a margin would end the fade at the bar's top
     * instead of the window's bottom, and this view's own padding is the one
     * strip the fade can never paint. See the inset listener in {@code onCreate}
     * and {@link BottomFadeScrollView}.
     */
    private ScrollView sheetScroll;
    /** The one dashed line under the grip. See {@link #buildFixLine}. */
    private LinearLayout fixLine;

    /**
     * The pill, which is the top strip of this same panel.
     *
     * <p><b>There is no separate pill view, and that is the whole change.</b> It
     * used to be a second view with its own label, dot, ring, background and click
     * listener, made VISIBLE when the panel's height reached zero — two state
     * changes driven by one gesture, which is a pop by construction, and the reason
     * the pill could not be a drag handle (a different view cannot be dragging the
     * sheet). The pill is now {@link #sheetStrip} at {@code fraction = 1}: same
     * view, same touch listener, same gesture. See {@link SheetMorph}.
     */
    /**
     * The quantised pill-ness the strip was last laid out at, so a drag does not call
     * requestLayout every frame. Eight steps: the row is either clipped or not, and the
     * only thing resolution buys in between is how abruptly it appears.
     */
    private int stripDrawnPillness = -1;

    private LinearLayout sheetStrip;
    /**
     * The pill's own row inside the strip: the status dot and the label, and nothing
     * else. At {@code f = 0} it measures to ZERO HEIGHT, because the grip band takes
     * the strip and this is a weighted child with its minimum cleared -- so the open
     * sheet has no pill text by layout rather than by alpha. That is the design's own
     * behaviour: the pill is an element BELOW the sheet, which an open sheet covers.
     */
    private LinearLayout pillRow;
    /**
     * {@code layout_weight} released to the grip band as the pill appears, so the bar
     * keeps the pill's vertical centre instead of sitting at the top of the tall strip.
     * Weight is a layout property and this is the one place in the morph where layout,
     * not alpha, is the right channel.
     */
    private android.widget.Space slack;
    /** The sheet's grip bar, hidden once the strip has become the pill. */
    private LinearLayout gripBand;

    /**
     * The strip's glass. The mockup's rgba(28,32,38,.72) with a hairline edge, and
     * it doubles as the pill's plate.
     *
     * <p>A field because the morph writes its corner radius every frame and because
     * the three states each recolour the edge — the border is what the eye lands on
     * for a warning, so it cannot be a value baked into the constructor.
     */
    private android.graphics.drawable.GradientDrawable stripBackground;

    /** The panel's own background, so the morph can round its top corners. */
    private ModalSheet.TopEdgeBackground panelBackground;

    /**
     * The colour the strip's own state wants, before the morph blends it.
     *
     * <p>All three of {@link StripState} ask for the same fill today, so this is one
     * value and nothing reads it back yet. It is {@link #SHEET_GLASS} and **not**
     * {@link #PILL_PLATE}, which is the mistake this field was written to prevent:
     * it is the colour the blend starts from, and the pill's plate is where the
     * blend <i>ends</i>. Blending {@code PLATE} to {@code PLATE} is a constant, and
     * the open sheet kept a plate on it for the whole session — caught on the device
     * by reading the morph's own log line, which reported the pill's colour at
     * {@code f = 0.27}.
     *
     * <p>It exists at all because {@link #applyStripState} and {@link #applySheetTone}
     * both write the fill, and the second must not be undone by the first: a state
     * change while the sheet is collapsed would snap the glass back to full opacity
     * under the user's finger. One base, one writer.
     */
    private int stripFillBase = SHEET_GLASS;
    /**
     * The fill {@link #applySheetTone} last wrote to the strip, so a stroke change can
     * restore it. {@code GradientDrawable.setStroke} CLEARS the fill, and the repair
     * used to restore the sheet's base rather than the current blend -- which repainted
     * the pill opaque after every state change.
     */
    private int pillFillNow = PILL_PLATE;

    /** The last fill written, so an unchanged blend is not re-set. */
    private int lastSheetFill = -1;

    /**
     * The navigation bar's height, cached from the one place insets are read.
     *
     * <p>The pill floats {@link #PILL_BOTTOM_INSET_DP} off the bottom edge, and on
     * 35+ the bar is <i>shown</i> whenever the sheet is collapsed — so at exactly
     * the moment the pill exists, the bar is the thing it has to clear. Asking for
     * this inside {@link #applySheetPosition} would mean a {@code WindowInsets}
     * read per frame, which is not something a drag can do; {@link
     * #sheetFootInset} already resolves the value correctly for both API paths, so
     * the listener that receives the insets stores it and the morph reads the
     * field.
     */
    private int navBarInsetPx;

    /** The last edge alpha written to {@link #panelBackground}, for the same reason. */
    private float lastSheetEdgeAlpha = -1f;

    /** The label inside {@link #sheetStrip}. The ellipsis lives on THIS, not the strip. */
    private TextView stripLabel;

    /** The dot, or the ring while a generation runs. Swapped by {@link #setStripMark}. */
    private View stripMark;
    private ProgressRing stripRing;

    /**
     * The panel's height the last time the morph placed it, in pixels.
     *
     * <p>Read by {@link #applySheetFade} because the component's {@code applyFade}
     * hands over a <i>progress</i> value built from the dialogs' fade line, which is
     * not the studio's. The studio's fade is a function of the same collapse
     * fraction {@link #applySheetPosition} was just given, so it keeps it rather than
     * inverting somebody else's number.
     */
    private float lastSheetFraction = -1f;

    /**
     * The entrance offset the last {@link #applySheetPosition} was given.
     *
     * <p>Held so the panel can be re-placed from the insets listener without the
     * gesture being re-run. Every quantity the morph writes is a function of the
     * fraction <i>and</i> this, so re-placing from one and not the other would
     * leave the two disagreeing — which is how an entrance offset would end up
     * written into the collapsed pill's bottom inset.
     */
    private float lastSheetBelowPx;

    /** What {@link #applyStripState} last drew, so a drag does not redraw it. */
    private StripState stripDrawnState;
    /** ...and the percentage it was drawn with, because PROGRESS carries one. */
    private int stripDrawnPercent = -1;
    private StripState stripState = StripState.IDLE;
    /** The Depth slider row, which does not exist for a photo with no map. */
    private View depthBarRow;

    /**
     * Whether each item's image carries an embedded depth chunk, by item id.
     *
     * <p><b>Absent means "not read yet", which is not the same as false.</b> That
     * distinction is the whole of this fix: a PNG can carry its depth map inside
     * itself, in a {@code dePH}/{@code dEPt} chunk, and for such a photo
     * {@code depthName} is null — so asking the index alone answered "no" and the
     * fix line printed <b>"No depth"</b> over a photograph whose relief was visibly
     * playing. Deciding it needs a file read, so there is a window on every
     * selection, and answering "no depth" inside that window is the bug. The window
     * maps to {@link DepthSource.Kind#UNKNOWN}, which claims nothing.
     *
     * <p><b>Keyed by id and read on delivery, never captured.</b> Two selections in
     * quick succession queue two reads, and each delivery puts its own answer under
     * its own key and then re-derives from whatever is selected <i>now</i>. So a slow
     * read for a photo the user has already moved away from cannot apply its answer
     * to the photo on screen — the race this cache would otherwise have.
     *
     * <p>Only read for an item with <b>no</b> separate depth file: an item that names
     * one is {@link DepthSource.Kind#FILE} from the index alone, so its line never
     * passes through the window at all.
     */
    private final java.util.Map<String, Boolean> embeddedDepthById =
            new java.util.HashMap<>();

    /** Ids with a read already in flight, so a refresh cannot queue the same one twice. */
    private final java.util.Set<String> embeddedReadInFlight = new java.util.HashSet<>();
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
    /**
     * "This photo's map", as one unit with its body.
     *
     * <p>A {@link SheetSection} rather than a caption plus a loose row, because
     * that shape is exactly how the section used to lie: the caption was added
     * unconditionally, so a photo with no map showed it over nothing, and the
     * Regenerate/Replace row was built once at construction, so a selection
     * change left a row describing the previous photo. {@link #refreshMapSection}
     * is the one place the whole section is put into the state of the item on
     * screen.
     */
    private SheetSection mapSection;
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

    /**
     * The active item id the strip and grid were last brought in step with.
     *
     * <p>Set once on launch and then in {@link #refreshSliders}, which every
     * in-app selection reaches. {@code onResume} uses it to tell a selection the
     * wallpaper moved while the studio was backgrounded -- a playlist rotation
     * writes the same pointer a tap does -- from a resume that changed nothing,
     * because {@link Gallery#reindex()} throws away every thumbnail and decodes
     * it again. Null means "no selection", a real state (a fresh install, or the
     * bundled item showing), not an unknown, so the comparison is null-safe
     * rather than treating null as "cannot tell".
     */
    private String shownActiveId;

    /**
     * The active item's last-used stamp when the strip last read the index.
     *
     * <p>Read beside {@link #shownActiveId}, and for the case that id alone
     * misses: a rotation that lands on the item already selected writes the same
     * id with a fresh stamp ({@code Playlists.advance} is
     * {@code withSelection(next).markUsed(next, now)}), and under the "Recently
     * used" order that stamp is the sort key, so the row can move with the id
     * unchanged. Only the rotated item's stamp moves, and the rotated item is
     * the active one, so the active item's stamp is the whole of what a
     * background writer can change.
     */
    private long shownActiveUsedAt;

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
        // Playlists are their own slice. The gallery's section opens the editor
        // for one playlist; the host owns the screen, this installs the route.
        gallery.setPlaylistsNavigator(new Gallery.PlaylistsNavigator() {
            @Override
            public void openEditor(String playlistId) {
                PlaylistScreen.edit(MainActivity.this, playlistId);
            }
        });

        // --- fullscreen preview ---
        glView = new GLSurfaceView(this);
        // 3 for the shipping GPU-displacement renderer (vertex texture fetch),
        // 2 for the legacy CPU-mesh fallback. One build-time switch decides both
        // this and the renderer's own program/mesh strategy -- see
        // BuildConfig.RENDERER_GLES3.
        glView.setEGLContextClientVersion(BuildConfig.RENDERER_GLES3 ? 3 : 2);
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
        //
        // Top edge only, per .sheet{border-top:1px solid var(--line)}. The
        // GradientDrawable stroke this replaced drew the hairline on all four
        // edges -- the 1-2px bleed down the sides and along the foot the user
        // reported -- and the two side lines were also why the rounded corners
        // read as a boxed card. ModalSheet.TopEdgeBackground clips the edge to
        // the top pixel, so it follows the corner rounding and stops there.
        panel.setBackground(panelBackground = new ModalSheet.TopEdgeBackground(
                0xEB161A20, 0x24FFFFFF, dp(22), dp(1)));
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
        // the user asked to be draggable. The rule itself is in {@link BottomSheet}.
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return sheet.onTouch(v, e);
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
        //
        // This is also the PILL: the panel's height at fraction 1 is exactly this
        // strip, so the collapsed sheet needs no second view. See sheetStrip.
        sheetStrip = (LinearLayout) buildSheetHandle();
        panel.addView(sheetStrip, 0);

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
        // scrollable rather than looking like the end of the list; the length
        // itself lives on SHEET_FADE_DP, which deviates deliberately. The fade
        // is at the foot only: BottomFadeScrollView suppresses Android's top
        // edge fade, which the design does not draw. This is a child property
        // -- not a transform -- so it is safe over the GLSurfaceView.
        sheetScroll.setFadingEdgeLength(dp(SHEET_FADE_DP));
        sheetScroll.setVerticalFadingEdgeEnabled(true);
        // The sheet itself has no padding, so the content carries it: 14dp sides, and
        // the bottom gap lives on the CONTENT, not on the scroll. Android draws a
        // scroll view's fading edge over getFadeHeight(), which is height minus
        // paddingBottom -- so the last 14dp of the scroll is the one strip the fade
        // can never cover. Measured before this change: fadeLen 43px with padBottom
        // 27px on the OnePlus (inset 0), and 143px of bar plus the same 14dp on the
        // Pixel, leaving unfaded content/glass between the fade's end and the bar on
        // BOTH devices -- the reported gap. On the content it spaces the last control
        // by the same 14dp and leaves the fade free to reach the view's bounds.
        final int sheetPadLeft = dp(14);
        final int sheetPadBottom = dp(14);
        sheetScroll.setPadding(sheetPadLeft, 0, sheetPadLeft, 0);
        // The navigation-bar inset is reserved on the CONTENT, as bottom
        // padding beside the 14dp foot gap. That is the one place it does not
        // disturb the fade, and the fade is the point: the bar is transparent
        // now (applySystemUi turns the platform's contrast scrim off), so the
        // gradient has to run under it rather than stop at its top edge.
        //
        // Two obvious placements are wrong, both measured on device. A bottom
        // MARGIN on the scroll moves the view's bounds -- and the fading edge is
        // drawn at the bounds -- up to the bar's top, which is exactly the hard
        // boundary this fixes. Bottom PADDING on the scroll keeps the bounds at
        // the window bottom but is excluded from the fade region
        // (View.getFadeHeight() is height minus padding), which is how the fade
        // came to stop 14dp short. On the content, the inset neither moves the
        // bounds nor clips the fade: content at rest ends above the bar, while
        // the last SHEET_FADE_DP of glass -- under the bar -- still fades.
        // ModalSheet.insetBottom is reused rather than re-derived so all five
        // surfaces resolve the bar identically.
        sheetScroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bar = sheetFootInset(insets);
                // Cached for the collapsed pill's bottom inset. The bar's visibility
                // follows the sheet's committed state, so this value changes exactly
                // when the pill appears or goes -- which is why the morph cannot ask
                // for it itself and has to be handed it.
                if (navBarInsetPx != bar) {
                    navBarInsetPx = bar;
                    // Re-place the panel: an inset the pill has not yet cleared is
                    // not a padding problem, and only the morph writes this margin.
                    if (lastSheetFraction >= 0f) {
                        applySheetPosition(lastSheetFraction, lastSheetBelowPx);
                    }
                }
                int bottom = sheetPadBottom + bar;
                if (sheetContent.getPaddingBottom() != bottom) {
                    // setPadding requests its own layout pass -- the same shape
                    // that reliably repaints over the GLSurfaceView (b/7179570)
                    // -- and the fade never left the bounds it is drawn at.
                    sheetContent.setPadding(0, 0, 0, bottom);
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
        // The 14dp foot gap the mockup draws, and later the navigation-bar
        // inset, live on the content so the scroll's fade reaches its own
        // bounds. See the inset comment above.
        sheetContent.setPadding(0, 0, 0, sheetPadBottom);
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
        final AppState initial = AppState.load(this, prefs, getFilesDir());
        final LibraryItem initialItem = initial.activeItem();
        // The strip was just built from this same index, so this is what it is
        // showing. It keeps the first resume from reindexing a gallery that has
        // not moved; see shownActiveId and shownActiveUsedAt.
        shownActiveId = initial.activeId();
        shownActiveUsedAt = initialItem == null ? 0L : initialItem.lastUsedAt;
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
        SheetSection picture = addSection(
                heading(R.string.group_picture, R.string.group_picture_surface));
        picture.body().addView(addSlider("brightness",
                s == null ? SurfaceSettings.DEFAULT_ADJUST : s.brightness,
                SurfaceSettings.ADJUST_MIN, SurfaceSettings.ADJUST_MAX));
        picture.body().addView(addSlider("shadow",
                s == null ? SurfaceSettings.DEFAULT_ADJUST : s.shadow,
                SurfaceSettings.ADJUST_MIN, SurfaceSettings.ADJUST_MAX));

        // Light is its own group, and the first one the mockup did not draw: it
        // postdates that file. The two rows are angles, not tone, so they share
        // nothing with Picture but the fact that both change how the photograph
        // renders -- and the light is the thing a basrelief IS, so burying it as a
        // third Picture row would hide the one lever that shows the depth at all.
        // Placed after Picture and before framing because the renderer applies
        // them in that order: tone first, then the shading multiplied over it.
        //
        // The bounds are read from LightDirection rather than typed, because the
        // store normalises through that same class and a control that commands a
        // value the model rewrites is the trap this project has already paid for
        // twice (the Origin range at twice ORIGIN_LIMIT, pan at an invented +/-4000).
        // Azimuth's canonical domain is [0, 360) and its unit here is whole
        // degrees, so the row's top is (int) AZIMUTH_MAX - 1: 360 is not an end to
        // clamp at, it is the same light as 0, which the other end already offers.
        SheetSection light = addSection(
                heading(R.string.group_light, R.string.group_light_surface));
        light.body().addView(addSlider("light_azimuth",
                azimuthForSlider(s == null
                        ? SurfaceSettings.DEFAULT_LIGHT_AZIMUTH : s.lightAzimuth),
                (int) LightDirection.AZIMUTH_MIN, (int) LightDirection.AZIMUTH_MAX - 1));
        light.body().addView(addSlider("light_elevation",
                elevationForSlider(s == null
                        ? SurfaceSettings.DEFAULT_LIGHT_ELEVATION : s.lightElevation),
                (int) LightDirection.ELEVATION_MIN, (int) LightDirection.ELEVATION_MAX));

        // Origin and Pan are two sections, not one section with two families under
        // it. "Where it sits" named a group that held exactly two things, each of
        // which is a thing in its own right and neither of which is a family of
        // controls — one axis each, with a unit. So the umbrella is gone and the two
        // family captions become the section headings, at the section's weight
        // rather than the 11dp label weight they used to be drawn at.
        //
        // Nothing about the rows changes: same four sliders, same keys, same ranges,
        // same persistence, same preview. FramingRows is still built exactly once —
        // it installs a layout listener on the GL view and registers each row into
        // the shared slider map, so building it per section would double every one
        // of those. Only the parent changes.
        addFramingSections();

        // "This photo's map": the mockup's heading for the Regenerate/Replace row and
        // the fix line. It replaces a row of three buttons that were not peers --
        // Gallery became the filmstrip, Depth became the fix line, and Calibrate
        // became a reported condition in Settings > Gyro rather than an action.
        //
        // The body is put into the selected photo's state here and re-derived from
        // the live selection on every refresh; see refreshMapSection, which is the
        // only place that decides what the section shows.
        mapSection = addSection(getString(R.string.group_depth));
        refreshMapSection(initial);

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
        // .btnrow{gap:8px}: the gap every other pair of buttons in the app uses.
        // makeButton's default 6dp is for a row that may hold more than two
        // controls; this is always one gear beside one action.
        LinearLayout.LayoutParams gearLp =
                (LinearLayout.LayoutParams) gearButton.getLayoutParams();
        gearLp.rightMargin = dp(8);
        gearButton.setLayoutParams(gearLp);
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

        // The sheet's one gesture handler. Built here, after every view the host
        // touches exists, and used by the grip's listener, the panel's own touch
        // listener (installed above; the body runs at touch time) and the panel's
        // intercept. The entrance offset becomes a bottom margin: a layout change,
        // which is what a sibling of the GLSurfaceView is allowed to move by.
        sheet = new BottomSheet(new BottomSheet.Host() {
            @Override
            public float spanPx() {
                return panelHeight();
            }

            @Override
            public void applyPosition(float fraction, float belowPx) {
                applySheetPosition(fraction, belowPx);
            }

            @Override
            public void applyFade(float progress) {
                // The progress handed over here is the DIALOGS' fade line, built
                // from BottomSheet.FADE_START, and the studio's fade is a different
                // function of the same collapse fraction — a band, not a ramp from
                // 0.60. So the studio ignores the number and asks SheetMorph, using
                // the fraction applySheetPosition was just given. Passing the
                // fraction through the interface instead would be the cleaner
                // signature and would break ModalSheet, which is a shared host and
                // whose fade genuinely is driven by this progress.
                applySheetFade(SheetMorph.edgeFade(lastSheetFraction));
            }

            @Override
            public View scrollView() {
                return sheetScroll;
            }

            @Override
            public void onResting(float fraction, boolean collapsed) {
                onSheetResting(fraction, collapsed);
            }
        }, SpringSettle.SHEET, SheetIntent.SHEET, BottomSheet.Snap.FREE,
                dp(DRAG_SLOP_DP), new Runnable() {
            @Override
            public void run() {
                // A tap on the strip that never became a drag. This is the pill's
                // old click listener, and it works now because the pill is the
                // strip: one view, one gesture, no second target to miss.
                //
                // Only from the collapsed state, because that is the only place the
                // tap used to mean anything. Expanded, the sheet's own gesture is
                // the way down and a tap on the handle doing nothing is deliberate:
                // a control that duplicated the gesture beside it was one of the
                // things the user was asked to unlearn.
                if (sheet != null && sheet.isCollapsed()) expandUi();
            }
        });

        // The last position the sheet rested at while open, read once so a pill tap
        // in this session returns there rather than snapping fully open. See
        // restoreLastResting.
        sheet.restoreLastResting(prefs.getFloat(KEY_SHEET_OPEN, -1f));

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
                // layout pass, and the entrance rewrites the panel's
                // LayoutParams; a requestLayout() made from inside that pass is
                // deferred to a second pass that measures the panel before the new
                // height is known, so the peek was computed and silently not
                // applied -- the sheet stayed at its natural height. A posted
                // runnable runs after the traversal, where the change is an
                // ordinary one.
                panel.post(new Runnable() {
                    @Override
                    public void run() {
                        openInitial();
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
        // The selection the strip is showing before the IO thread can select a
        // matching row. A re-share of the photo already on screen writes the same
        // id back, so nothing the strip draws has moved and reindex() would drop
        // and re-decode every thumbnail for nothing; see the target == null
        // branch. Read here because findByContent's select happens on the IO
        // thread, before the callback that needs this value can run.
        final String activeBefore = state().activeId();
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
                    // The row that was just selected may be the one the strip is
                    // not ringing and the grid not showing, because the pair of
                    // reads above re-resolve the render, not the index views.
                    // Only worth a rebuild when the selection actually moved: a
                    // re-share of the photo already on screen writes the same id
                    // back, and reindex() drops and re-decodes every thumbnail.
                    if (!TextUtils.equals(activeBefore, state().activeId())) {
                        gallery.reindex();
                    }
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
                // The grid may be open behind the share, and the strip is always
                // up: both read the index once and would otherwise keep showing
                // the list the new row was added to. Measured: a shared photo was
                // selected and absent from the gallery, which reads as the import
                // having failed.
                gallery.reindex();
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
     *
     * <p><b>Every row is a candidate, including the demo.</b> The demo is a file
     * in {@code filesDir} now, so it has bytes to compare and a re-share of the
     * same picture is the same "already in the gallery" case as any other row.
     * A row whose file is missing is skipped, which is where a demo whose copy
     * failed lands — it renders the packaged pair and matches nothing.
     */
    private LibraryItem findByContent(byte[] bytes) {
        for (LibraryItem item : LibraryStore.rows(prefs)) {
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
        AppState state = AppState.load(this, prefs, getFilesDir());
        refreshFixLine(state);
        refreshStatus();
        // The strip repaints only when something else refreshes it otherwise, which
        // is how a failed run used to leave it reading "Generating" until the sheet
        // was touched. applyStripState re-reads whether a run is in progress, so this
        // is what carries the transition in and out of PROGRESS.
        applyStripState();
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
        // The index can change while the studio is backgrounded -- a playlist
        // rotation writes the same selection a tap does -- and the strip and grid
        // each read it once at build time. Re-reading is what re-rings the strip
        // after a rotation; without it the studio comes back showing the previous
        // photo's ring, which is a wallpaper and a gallery disagreeing about what
        // is on screen.
        //
        // Conditional on the index actually having moved. This call used to run
        // on every resume, and reindex() throws away and re-decodes every
        // thumbnail, so every return to the studio paid a whole-strip flash for
        // an index that had not changed. The wallpaper is the only writer that
        // can move the selection while this activity is paused, and it moves it
        // through the same two calls a tap makes; the id catches a rotation to
        // another photo, and the stamp catches one that re-selects the same
        // photo (a fresh markUsed under the "Recently used" order moves the
        // row). Both fields are kept current by refreshSliders, which every
        // in-app selection reaches, and null is a real selection state (no row
        // selected) rather than an unknown.
        AppState resumed = AppState.load(this, prefs, getFilesDir());
        LibraryItem resumedItem = resumed.activeItem();
        if (!TextUtils.equals(shownActiveId, resumed.activeId())
                || (resumedItem != null && resumedItem.lastUsedAt != shownActiveUsedAt)) {
            gallery.reindex();
        }
        // The renderer reloads the new selection a few lines down, so the sheet
        // above it must re-resolve from the same read or a rotation leaves the
        // controls showing the previous photo's numbers while the render shows
        // the new one. This is also the first refresh a cold launch gets, which
        // is what puts the fix line and the depth caption on screen: they are
        // built from the launch selection in onCreate and were never refreshed
        // before this, so a photo with no map came back without its "No depth"
        // line until the user touched something.
        refreshSliders();
        // Tidy the stored filenames on the UI thread. reloadFromPrefs() (GL
        // thread) only reads them; normalising here keeps pref writes off the
        // GL thread and means a restored legacy absolute path is rewritten as a
        // bare filename on the very next launch.
        // One load covers both migrations and the self-heal, and writes only if
        // something actually needed changing.
        AppState.load(this, prefs, getFilesDir());
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
     * {@link #onSheetResting} for where it is driven and why that cannot
     * oscillate.
     *
     * <p>The consequence of a visible bar is handled where {@code sheetScroll} is
     * built: once a bar can be visible, the sheet's foot can be occluded by it, so
     * the bar inset is folded into the content's bottom padding — the same fix the
     * modals already carry, except it must not sit on the scroll itself or it
     * would cut the fade short. When the bar is hidden the same padding recomputes
     * to zero, which {@link #requestSheetInsets} asks for after every change.
     *
     * <p><b>The bar carries no platform contrast scrim on this window.</b> With a
     * transparent bar colour the platform normally decides whether the content
     * behind the bar is contrasty enough and, if it judges it is not, paints its
     * own opaque scrim; since API 29 that is {@code DecorView.calculateBarColor}
     * returning {@code SCRIM_LIGHT} or the semi-transparent system bar colour,
     * drawn by the decor's navigation colour view. That scrim is the opaque band
     * that sat on the sheet's fade. It is turned off on 29+ with {@code
     * setNavigationBarContrastEnforced(false)}, because what is behind the bar
     * while the bar is visible is the sheet's own 92%-opaque glass rather than the
     * photo, and the design asks for the fade to run through. The colour calls are
     * only made below 35: at 35 the setter is deprecated and an app targeting 35+
     * gets a forced-transparent bar, so setting it there would only pretend to
     * work.
     *
     * <p>{@code setDecorFitsSystemWindows(false)} is API 30+ and is what makes the
     * transparent bar actually overlay our content rather than reserve a strip;
     * below 30 the layout flags do the equivalent. Both are guarded, because
     * minSdk is 26 and neither call may run on a level that does not have it.
     */
    private void applySystemUi() {
        Window window = getWindow();
        // The flag is what lets the decor draw behind the bars at all, and on
        // 35+ it is also what keeps DecorView.calculateBarColor out of the
        // branch that returns opaque black when it is absent. It stays on every
        // level.
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        // The platform's contrast scrim, off; see the javadoc. The call needs
        // API 29, and it is the switch that stops the decor's navigation colour
        // view before any of it appears.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
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
            // the collapsed state asks for. Collapsed is the component's own
            // committed state, the same one the pill and the drag settle follow, so
            // "away" means one thing rather than three.
            android.view.WindowInsetsController controller =
                    window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                // A swipe may reveal the bar transiently; otherwise it stays gone,
                // which is what "fully invisible" means.
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController
                                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (sheet != null && sheet.isCollapsed()) {
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
     * reserved foot space is built from is not constant. Asking again is what
     * keeps the foot clear on a three-button bar — the tallest case, and the one
     * that was reported — where the bar covers the last ~48dp of the screen. It
     * is safe on every API: on 26–34 the bar never appears and the inset stays
     * zero.
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
     * bar: the listener ran once with inset 0 and the inset was never re-read.
     * The bar's height REGARDLESS of visibility is the right number on
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
        if (sheet != null && !sheet.isCollapsed()) {
            // "Expanded" is the component's committed state — the same one the pill
            // and the system bars follow — rather than a second threshold that could
            // disagree about what "open" means. One back press puts the sheet away;
            // a second, with nothing else open, leaves the app.
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
     * <p>Called from the component's commit rather than from a control, because
     * the sheet is collapsed by dragging the handle and there is no button left
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
        sheet.collapse();
    }

    private void expandUi() {
        // Spring up from below to the last place the sheet rested, not to a
        // default: a persistent panel that the user positioned is not corrected
        // for the convenience of the pill. A host that has never left it open
        // falls back to fully open inside the component.
        sheet.openLast();
    }

    // ------------------------------------------------------------ framing fields

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
        // .fixline .acts{gap:8px}, between the two and nowhere else. A right
        // margin on every button -- which is what the 4dp on each used to be --
        // leaves a dead strip at the box's right edge too, so the pair sat 4dp
        // short of the sentence above it.
        if (fixActions.getChildCount() == 2) {
            LinearLayout.LayoutParams first = (LinearLayout.LayoutParams)
                    fixActions.getChildAt(0).getLayoutParams();
            first.rightMargin = dp(8);
            fixActions.getChildAt(0).setLayoutParams(first);
        }
        // Attention is a property of the CONDITION, not of the fact that a fix line
        // exists. Setting it here once meant a purely informational line -- the
        // bundled demo's, before that sentence was removed -- showed an amber "No
        // depth" pill when there was nothing wrong at all. The caller decides.
        setStripState(attention ? StripState.ATTENTION : StripState.IDLE);
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
        // No margin of its own: the gap between the pair is applied by
        // setFixLine to the first button only, so the second reaches the box's
        // right padding exactly like the sentence above it reaches the left.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * The conditions the strip knows how to report.
     *
     * <p>Deliberately a small closed set rather than a general mechanism. Every entry
     * is a thing the app can <i>do something about</i>; a line that can only inform
     * is a status paragraph, and this is one sentence.
     *
     * <p>Named for the strip rather than the pill because the pill <i>is</i> the
     * strip now — see {@link #sheetStrip}. The three states are unchanged: this is
     * the old pill's condition set, and it is what lets the collapsed sheet say
     * what is going on without being open.
     */
    private enum StripState { IDLE, ATTENTION, PROGRESS }
    private static final int WARN_COLOUR = 0xFFE8B04B;
    /** The idle status dot: --dimmer, because it is a fact rather than a prompt. */
    private static final int STRIP_DIM = 0x8CFFFFFF;

    private void setStripState(StripState state) {
        // The CONDITION, as opposed to what is drawn. Kept separately because a
        // running generation is a transient override: when it ends, the strip must go
        // back to whatever the fix line last said rather than to a stale copy of
        // progress. Overwriting the condition with the transient state is what made
        // a finished run leave the strip saying "Generating" forever.
        stripState = state;
        applyStripState();
    }

    /**
     * Draw one state, and nothing else.
     *
     * <p><b>The call graph is one-way on purpose:</b>
     * {@code setStripState} → {@code applyStripState}, and nothing below calls
     * upward. The visibility half this used to sit between them is gone: there is no
     * second view to show or hide. What replaced it — the navigation bar's
     * visibility — follows the <i>committed</i> state from {@link #onSheetResting},
     * which is the only other thing that depended on being collapsed, and keeping
     * the two apart is what stops them recursing.
     *
     * <p>Also skips the work when the state has not changed. This runs on every
     * refresh of the fix line, and each draw allocated a fresh drawable and ring.
     */
    /**
     * Give the strip its pill-ness: the row's SHARE of the height, not its alpha.
     *
     * <p><b>This is layout, and it is the right channel for once.</b> The fade's rule
     * runs the other way -- an alpha change is a paint and cannot desync the
     * {@code GLSurfaceView}'s transparent region, so the content below is faded. But the
     * pill's row is a child of the strip, which is a SIBLING of the surface, and changing
     * a {@code LinearLayout} child's weight calls {@code requestLayout} on a layout whose
     * position and size do not change. That is a re-measure inside a fixed box, never a
     * post-layout transform, so it is not the b/7179570 hazard.
     *
     * <p>What it buys is that the open sheet has NO pill row at all rather than an
     * invisible one. The design's pill is a separate element that an open sheet covers,
     * so an open sheet never showed a pill; the morph moved the pill's contents up into
     * the sheet's top edge, and clipping the row to nothing is how that stays true. An
     * alpha of 0 would still be a row that is there.
     */
    private void applyStripPillness(float pillness) {
        if (pillRow == null || slack == null || gripBand == null) return;
        int step = Math.round(pillness * 8f);
        if (step == stripDrawnPillness) return;
        stripDrawnPillness = step;
        float p = step / 8f;
        if (step == 0) {
            stripBackground.setStroke(0, 0);
            stripBackground.setColor(Color.TRANSPARENT);
        } else {
            applyStripState(true);
        }
        // THE GRIP BAND'S HEIGHT GOES TO ZERO AND THE ROW TAKES THE STRIP. It was left at
        // its constructed dp(STRIP_GRIP_DP) for the whole travel, so the row was laid out
        // BELOW an invisible band and the label sat at the foot of the capsule -- measured
        // on the device: pill [571,2733][869,2900], text [679,2825][815,2872], text centre
        // 32px below the pill's. An invisible view that still occupies space is the whole
        // failure, and the fix is its height, not the row's gravity.
        LinearLayout.LayoutParams bandLp = (LinearLayout.LayoutParams) gripBand.getLayoutParams();
        bandLp.height = Math.round(dp(STRIP_GRIP_DP) * (1f - p));
        bandLp.weight = 0f;
        gripBand.setLayoutParams(bandLp);
        // The row is WRAP_CONTENT and must STAY wrap_content: a MATCH_PARENT row measures
        // to whatever the panel offers rather than to its own text, so "measure the row"
        // returned the panel's width and the label was sized to the wrong number.
        LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) pillRow.getLayoutParams();
        rowLp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        rowLp.height = LinearLayout.LayoutParams.MATCH_PARENT;
        rowLp.weight = 0f;
        rowLp.gravity = Gravity.CENTER;
        pillRow.setLayoutParams(rowLp);
        // The slack is retired entirely: the row's MATCH_PARENT height already centres it,
        // and a weighted spacer beside it would fight that.
        LinearLayout.LayoutParams slackLp = (LinearLayout.LayoutParams) slack.getLayoutParams();
        slackLp.height = 0;
        slackLp.weight = 0f;
        slack.setLayoutParams(slackLp);
        gripBand.setAlpha(1f - p);
        // The strip is the row's parent; there is no field for it and one is not needed,
        // because every child that changed height has already been given its new params
        // and any one of them can carry the request up.
        pillRow.requestLayout();
    }

    /**
     * Set the strip's outline and keep its fill.
     *
     * <p><b>Because {@code GradientDrawable.setStroke} clears the fill.</b> AOSP's
     * implementation writes {@code setColor(TRANSPARENT)} and installs a new stroke
     * paint, so the setStroke calls in {@link #applyStripState} had been silently
     * erasing the strip's plate on every state change. What showed through was the
     * PANEL's plate underneath, so the pill was opaque whatever {@code PILL_PLATE} said
     * -- two reports of "not translucent at all", and the number was never the reason.
     * One helper, because the failure is invisible and will happen again otherwise.
     */
    private void setStripStroke(int colour) {
        if (stripBackground == null) return;
        // The outline exists only where the pill does; see applyStripPillness.
        stripBackground.setStroke(dp(1), colour);
        // THE CURRENT BLEND, not the base. This wrote `stripFillBase` -- the SHEET's
        // glass at 0xEB -- and so repainted the pill opaque on every state change,
        // right after applySheetTone had made it translucent. The pill therefore looked
        // glassy only in the window between a drag and the next state update, which is
        // exactly what the user reported and what three passes failed to explain.
        // getColor() on the drawable is the value applySheetTone last wrote.
        stripBackground.setColor(pillFillNow);
    }

    private void applyStripState() {
        applyStripState(false);
    }

    private void applyStripState(boolean force) {
        if (sheetStrip == null) return;
        // A run in progress outranks the condition. This is the one thing worth
        // showing while the sheet is away: it is the only feedback a user gets for a
        // generation they started and then slid the sheet over. Read on every
        // refresh rather than pushed, because the run is started from a dialog that
        // is not this class and there is no callback to hang the end on.
        StripState drawn = DepthGenDialog.isRunning() ? StripState.PROGRESS : stripState;
        // The same STATE is not always the same PICTURE: PROGRESS carries a number.
        // Guarding on the state alone would have frozen the percentage at whatever it
        // was when the run started -- a progress bar that does not progress. Read once
        // here so the guard and the text cannot disagree about it.
        int percent = drawn == StripState.PROGRESS
                ? DepthGenDialog.progressPercent() : -1;
        // The pill's own palette, unconditionally: whether it is VISIBLE is
        // applyStripPillness's job, decided by whether a pill exists at all. Blending
        // the colour here as well was doing the same job in two places.
        int shape = Math.round(SheetMorph.shapeProgress(lastSheetFraction) * 16f);
        if (!force && stripDrawnState == drawn && stripDrawnPercent == percent) return;
        stripDrawnState = drawn;
        stripDrawnPercent = percent;
        switch (drawn) {
            case IDLE:
                stripLabel.setText(R.string.pill_controls);
                stripLabel.setTextColor(0xFFFFFFFF);
                setStripStroke(0x33FFFFFF);
                setStripMark(false);
                break;
            case ATTENTION:
                stripLabel.setText(R.string.pill_no_depth);
                stripLabel.setTextColor(WARN_COLOUR);
                // The BORDER carries the warning too. Changing only the text colour
                // left a pill that read as informational at a glance, which is the
                // opposite of the point -- the border is what you see first. It is
                // also why the fill never blends toward the old PILL_DIM: a 55%
                // white plate has no room for a 55% white border on it.
                setStripStroke(WARN_COLOUR);
                setStripMark(false);
                break;
            case PROGRESS:
                // The percentage comes from the same read the guard used.
                stripLabel.setText(getString(R.string.pill_generating, percent));
                stripLabel.setTextColor(0xFFFFFFFF);
                stripBackground.setStroke(dp(1), 0x33FFFFFF);
                setStripMark(true);
                break;
            default:
                break;
        }
        applyStripFill();
    }

    /** An 8dp dot when idle, a 15dp spinning ring while a generation runs. */
    private void setStripMark(boolean progress) {
        if (stripMark == null) return;
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) stripMark.getLayoutParams();
        if (progress) {
            if (stripRing == null) {
                // The ring's HEAD is white and its track is .28 -- an accent head
                // reads as a mint spinner, which is a different component.
                stripRing = new ProgressRing(dp(15), dp(2), 0xFFFFFFFF, 0x47FFFFFF);
            }
            stripMark.setBackground(stripRing);
            lp.width = dp(15);
            lp.height = dp(15);
            stripRing.start();
        } else {
            if (stripRing != null) stripRing.stop();
            android.graphics.drawable.GradientDrawable dot =
                    new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            // Dim when idle, bright when something needs attention: the dot is the
            // one part of the pill you notice before you read.
            dot.setColor(stripState == StripState.ATTENTION ? WARN_COLOUR : STRIP_DIM);
            stripMark.setBackground(dot);
            lp.width = dp(8);
            lp.height = dp(8);
        }
        lp.rightMargin = dp(10);
        stripMark.setLayoutParams(lp);
    }

    /**
     * The strip's glass: the mockup's rgba(28,32,38,.72) with a hairline edge.
     *
     * <p>An opaque-enough fill with an edge is the whole job — the sheet it stands in
     * for is glass over a render, and the render can be anything. The corner radius
     * is not set here: the morph owns it, and it must match the panel's own every
     * frame or one of them peeks out of the other at {@code f = 1}.
     *
     * <p>The fill is the value the morph blends <i>to</i>, not one it blends from,
     * so it is only ever seen at {@code f = 1}. What the strip actually draws at
     * any other fraction is {@link #applySheetTone}'s, and every state change goes
     * through {@link #applyStripFill} so the two cannot disagree.
     */
    private android.graphics.drawable.GradientDrawable pillBackground(int stroke) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        // setColor AFTER setStroke, always. GradientDrawable.setStroke CLEARS the fill
        // (AOSP: it writes setColor(TRANSPARENT) then sets a new stroke paint), so the
        // original order left every strip with NO fill at all and the panel's own plate
        // showing through -- which is why the pill was solid no matter what PILL_PLATE
        // said, and why the user had to report it twice.
        d.setStroke(dp(1), stroke);
        d.setColors(new int[]{PILL_PLATE_TOP, PILL_PLATE_BOTTOM});
        // A GRADIENT, because a flat fill reads as a plate and a gradient reads as glass.
        // This is the largest single tell available to us and it costs nothing: the fill
        // is already being set, only with one colour instead of two. Light at the top,
        // marginally darker at the foot, the way a real pane catches the sky.
        d.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        return d;
    }

    /**
     * The pill's fill, top and bottom.
     *
     * <p><b>What "liquid glass" can and cannot be here, decided by measurement rather
     * than taste.</b> Apple's effect refracts and samples the backdrop; both need the
     * backdrop BLURRED, and a window background blur is composited only for a translucent
     * <i>floating</i> window. Our sheet is a sibling of the {@code GLSurfaceView} in the
     * same window and a Dialog is {@code ty=BASE_APPLICATION}, so that is unavailable at
     * any radius — measured twice, and the reason three passes of turning the fill down
     * never produced glass. What IS available is everything painted: transparency, a
     * gradient, a lit top rim and a shaded foot. That is a static glass, and over a
     * photograph it reads convincingly; over a high-contrast test pattern it will still
     * look like a tinted pane, because it cannot sample what is behind it.
     *
     * <p>Both ends are the same hue; only the alpha moves, and only by a little. A wider
     * spread would read as a UI gradient rather than as glass.
     */
    private static final int PILL_PLATE_TOP = 0x5E1C2026;
    private static final int PILL_PLATE_BOTTOM = 0x4A1C2026;
    /**
     * Write the strip's fill for the collapse it is currently at.
     *
     * <p>One writer, two callers: {@link #applyStripState} when the <i>condition</i>
     * changes and {@link #applySheetTone} when the <i>geometry</i> moves. Both set
     * the same base and both land here, which is the only way a generation finishing
     * while the sheet is collapsed cannot snap the glass back to full opacity under
     * the user's finger.
     */
    private void applyStripFill() {
        // Before the morph has ever placed the panel there is no fraction to blend from.
        // The strip's own constructor fill is the pill's, but the PANEL has not yet been
        // told to give its fill up -- so on a cold launch in the collapsed state the
        // panel painted its glass at full alpha underneath the strip and the pill was
        // opaque until something dragged the sheet. `applySheetPosition` is what tells
        // the panel, so the honest answer here is to let it run rather than to fake a
        // blend against a panel in an unknown state.
        if (lastSheetFraction < 0f) return;
        applySheetTone(lastSheetFraction, SheetMorph.blendColour(stripFillBase, PILL_PLATE,
                SheetMorph.insetFor(lastSheetFraction, 1f)));
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
     * The panel's top strip: the drag handle, and <b>the pill</b>.
     *
     * <p>Added as the panel's FIRST child so it sits above everything, and at a
     * fixed {@link #STRIP_HEIGHT_DP} — which is the whole of the morph's geometry.
     * The panel's height is {@code lerp(natural, STRIP_HEIGHT_DP, f)}, so at
     * {@code f = 1} the strip <i>is</i> the whole panel and every other child is
     * laid out below the panel's bounds and clipped away. Nothing is shown or
     * hidden, nothing swaps, and there is no second view to be out of step.
     *
     * <p><b>The pill's row is the strip's leftover height, not a fade.</b> The grip
     * band is a fixed {@link #STRIP_GRIP_DP} and the row below it takes the rest,
     * so at {@code f = 0} the leftover is zero — the label is <i>clipped into
     * existence</i> as the sheet closes rather than dissolved, which is what keeps
     * this project's standing rule that the studio sheet is never faded. Alpha is
     * reserved for the content below, and only in the edge band; see {@link
     * SheetMorph#edgeFade}.
     *
     * <p><b>Dragging the pill is dragging the sheet</b>, because it is the same view
     * with the same listener: {@code BottomSheet} owns the gesture and this only
     * stands the panel's arbitration down for the duration. At {@code f = 1} the
     * entire visible sheet is this strip, so the whole pill is the drag target.
     */
    private View buildSheetHandle() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        // Horizontal centring for the grip bar; the pill row is MATCH_PARENT width,
        // so this only moves the bar.
        strip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        strip.setContentDescription(getString(R.string.sheet_drag_handle));
        strip.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(STRIP_HEIGHT_DP)));
        // The pill's own plate. A field so the morph can round it with the panel
        // and so the attention state can recolour its edge.
        stripBackground = pillBackground(0x33FFFFFF);
        strip.setBackground(stripBackground);

        // The grip band: the mockup's 38x4 bar and nothing else, centred in a fixed
        // band so the pill's row below gets exactly the strip's leftover height.
        // No band fill — a full-width translucent band across the sheet's top edge
        // reads as a header, which is what the mockup deliberately does not draw.
        gripBand = new LinearLayout(this);
        gripBand.setOrientation(LinearLayout.VERTICAL);
        gripBand.setGravity(android.view.Gravity.CENTER);
        View grip = new View(this);
        grip.setBackgroundColor(0x6BFFFFFF);   // the mockup's rgba(255,255,255,.42)
        gripBand.addView(grip, new LinearLayout.LayoutParams(dp(38), dp(4)));
        // The grip band takes the height the pill row does not. With the panel at
        // MATCH_PARENT the two split the strip, at f = 0 the row has nothing and is
        // gone by MEASUREMENT rather than by alpha, and with `slack` freed the grip
        // band takes the strip itself so the bar stays vertically centred in the pill.
        slack = new android.widget.Space(this);
        strip.addView(gripBand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(STRIP_GRIP_DP)));
        strip.addView(slack, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f));

        // The pill's row: the status dot (or a progress ring) and a label that
        // carries the ellipsis. Weight 1 so it takes precisely the leftover.
        final LinearLayout row = new LinearLayout(this);
        pillRow = row;
        row.setOrientation(LinearLayout.HORIZONTAL);
        // CENTER_VERTICAL for the dot against the text, and CENTER_HORIZONTAL because the
        // row is WRAP_CONTENT: without it the mark and label sit at the row's own left
        // edge, which becomes the pill's left edge, and the content reads off-centre in a
        // capsule that is symmetric. The row's own padding is the pill's inset, so the
        // two side paddings are equal and the content is centred by construction.
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        // Weight 1 with a ZERO minimum: the row takes whatever the grip band leaves, so
        // at f = 0 it is exactly nothing and the pill's text is not on the sheet at all.
        // Android honours a 0dp weighted child down to zero and only then applies
        // minimumHeight, which is why the minimum has to be cleared -- the default 25dp
        // would have left the label occupying a header the open sheet must not have.
        row.setMinimumHeight(0);
        // Side padding so the dot is not hard against the panel's edge when the
        // panel's own corners round over it.
        row.setPadding(dp(18), 0, dp(18), 0);

        stripMark = new View(this);
        android.graphics.drawable.GradientDrawable dot =
                new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(STRIP_DIM);
        stripMark.setBackground(dot);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        markLp.rightMargin = dp(10);
        row.addView(stripMark, markLp);

        stripLabel = new TextView(this);
        stripLabel.setTextSize(13.5f);
        stripLabel.setTextColor(0xFFFFFFFF);
        stripLabel.setMaxLines(1);
        // The ellipsis is on the LABEL, not on the strip. On the strip it competed
        // with the dot and the padding for room, so a long sentence compressed the
        // dot into a speck before the text shortened at all. Here the dot is fixed
        // and the text takes what is left.
        stripLabel.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(stripLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // WRAP_CONTENT, not MATCH_PARENT: the pill's width is what its content needs, and
        // a MATCH_PARENT child measures to whatever the parent offers -- so the cap came
        // back as the "measured" width, the panel was sized to the cap, and the label
        // ellipsised inside it. "No dep..." was that. See pillWidthPx.
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f);
        // The strip centres the row, so the pill's content is centred in the pill rather
        // than pinned to the left of a full-width row. The grip band takes the slack above.
        rowLp.gravity = Gravity.CENTER_HORIZONTAL;
        strip.addView(row, rowLp);

        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // The strip is a control of the sheet's own, so the panel's
                // arbitration stands down for the whole gesture rather than
                // deciding the same drag a second time.
                boolean end = e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                panel.requestDisallowInterceptTouchEvent(!end);
                return sheet.onTouch(v, e);
            }
        });
        return strip;
    }

    /**
     * The sheet's panel, and the one place a vertical drag is arbitrated.
     *
     * <p>The panel is only the ViewGroup the platform offers interception
     * through; the rule itself is {@link BottomSheet#onIntercept}, shared with
     * the modal cards. The scroll is resolved here because the panel is the
     * thing that knows where its own children are, and the scroll's bound is
     * what makes a nested drag hand over at its end.
     */
    private final class SheetPanel extends LinearLayout {

        SheetPanel(android.content.Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return sheet.onIntercept(e, isInsideScroll(e.getY()));
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
     * Put the sheet where the user last left it, springing it up from below; the
     * peek is only the first launch's answer.
     *
     * <p>Capped, never padded: a sheet shorter than the fraction opens fully rather
     * than being inflated with padding to reach it, because padding would be a row
     * of dead space that looks like a control and is not one.
     *
     * <p><b>Launch state is the stored position, and the peek is the fallback.</b>
     * This used to always open at the peek, and before that it always opened at
     * 100% because it called the full-open height whenever the panel had not been
     * measured. The peek is kept as the fresh-install answer; after that the
     * stored fraction wins, so a drag is never undone by closing the app. A stored
     * 1.0 means the sheet was put away: it reopens as the pill, with no entrance
     * animation, because the user already decided it was away.
     *
     * <p>The entrance itself is {@link BottomSheet#open}: the panel is laid out at
     * the resting height first — the strip, the scroll and the renderer's first
     * frame all read that layout — and the below offset then springs from an
     * entirely off-screen position back to rest. The first frame's height is that
     * resting height, never zero, which is the part the layout ordering depends on.
     */
    private void openInitial() {
        if (panelNaturalHeight <= 0) {
            // While no height has been imposed yet, getHeight() is the panel's
            // own wrap_content height -- the denominator the fraction is a fraction
            // of, and the only chance to read it before a height is imposed.
            panelNaturalHeight = panel.getHeight();
        }
        if (panelNaturalHeight <= 0) {
            // Still unmeasured: no layout pass has run. Defer the whole decision
            // rather than guessing a fraction of an unknown height.
            panel.post(new Runnable() {
                @Override
                public void run() {
                    openInitial();
                }
            });
            return;
        }
        float stored = prefs.getFloat(KEY_SHEET, -1f);
        if (stored >= 1f) {
            // Put away last time: the strip is the whole panel and it must be there
            // from the first frame rather than after a collapse animation the user
            // did not ask for. restAt places it with no motion, and apply() draws
            // the strip's state on the way through — there is nothing to show or
            // hide, which is the point.
            sheet.restAt(1f, true);
            // restAt places the panel and routes through applySheetPosition, which is
            // what tells the panel to give up its fill -- but it runs before the strip's
            // own children have been measured, so the pill's WIDTH may have been taken
            // from an unmeasured row. Re-place once the layout has settled. This is the
            // cold-launch-in-the-collapsed-state path the user reported: opaque until
            // something dragged it, because only a drag re-ran the placement.
            panel.post(new Runnable() {
                @Override
                public void run() {
                    sheet.reapply();
                    applyStripState();
                }
            });
            applyStripState();
            return;
        }
        float fraction = stored >= 0f ? stored : peekFraction();
        sheet.open(fraction);
        applyStripState();
    }

    /**
     * The width the sheet spans while it is open, which is the denominator the
     * collapsed pill's width fraction is taken against.
     *
     * <p>The display metrics rather than {@code root.getWidth()}, because the morph
     * runs before the first layout has necessarily settled and a zero there would
     * silently resolve the pill's width to <i>full</i> screen — the one value that
     * reproduces the bar this whole change exists to remove. The metrics are the
     * same number {@link #peekFraction} already measures the sheet against.
     */
    private float screenWidthPx() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * The pill's collapsed width in px: what its label actually needs, capped.
     *
     * <p><b>Measured, not a fraction of the screen.</b> The pill wraps its content, so
     * the width is the label's own measurement plus the row's padding and the status
     * mark, held to {@link #PILL_MAX_INSET_DP} of the screen's edges. A fixed share of
     * the screen was the "too wide" the user reported: with a two-word label it was the
     * same size as with a long filename, and neither bore any relation to the text.
     *
     * <p>Falls back to the cap while the label has not been measured yet, which is the
     * first frame. That is the safe direction -- the cap is the widest the pill may be,
     * so a wrong first frame is a pill that is briefly too wide rather than one that is
     * narrower than its own text.
     */
    /**
     * The pill's collapsed width in px: what its own row actually measures.
     *
     * <p><b>The ROW is asked, rather than reconstructed from its parts.</b> A previous
     * version added the padding, the mark's width and a guessed dp gap, plus the
     * label's {@code measureText} — and every one of those was wrong in a different
     * way: the mark's {@code getWidth()} is zero before layout, its right margin is
     * applied by the LinearLayout and not by the mark, and {@code measureText} ignores
     * the TextView's own compound padding. The sum came out SHORTER than the row
     * needed, so {@code wrap_content} was overruled, the panel clamped the label and the
     * user saw "No dep…". Arithmetic that has to agree with a layout engine is
     * arithmetic that will not.
     *
     * <p>So the row is measured, at the cap. {@code AT_MOST} means a long filename
     * ellipsises at the cap instead of forcing the panel wider, and
     * {@code UNSPECIFIED} height keeps this a width question only — it must not disturb
     * a height the strip is mid-way through setting.
     */
    private float pillWidthPx() {
        float cap = screenWidthPx() - dp(PILL_MAX_INSET_DP);
        if (pillRow == null) return cap;
        // MEASURE GENEROUSLY, THEN CLAMP -- not the other way round. The spec used to be
        // AT_MOST cap, where cap is the PANEL's target width; but the row lives INSIDE
        // the panel with its own padding and the status mark, so it was measured against
        // one width and laid out in a narrower one. A label that measures 299px inside a
        // 292px panel ellipsises, and no amount of moving the cap fixes that -- the two
        // numbers have to be the same number.
        //
        // So the row is measured UNSPECIFIED in width, which is what "what does this text
        // need" means, and the answer is clamped to the cap afterwards.
        int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        pillRow.measure(widthSpec, heightSpec);
        float measured = pillRow.getMeasuredWidth();
        // A pixel of rounding slack. The panel's width is screen - 2*margin, and rounding
        // the margin UP by half a pixel leaves the panel a pixel short of what the row
        // measured -- measured on the device as measured=299 against panelWidth=298, which
        // ellipsised the final glyph. The slack belongs here, where the measurement is,
        // and never as a guessed inset somewhere else.
        float wanted = Math.min(Math.max(measured + 2f, dp(PILL_MIN_WIDTH_DP)), cap);
        return wanted;
    }

    /** The first-launch fraction: a third of the phone, capped at the whole panel. */
    private float peekFraction() {        int peek = Math.round(SHEET_PEEK_FRACTION
                * getResources().getDisplayMetrics().heightPixels);
        peek = Math.min(peek, panelNaturalHeight);
        return 1f - (float) peek / panelNaturalHeight;
    }

    /**
     * Place the panel from the component's fraction and entrance offset.
     *
     * <p><b>The height is {@link SheetMorph#heightFor}: a lerp from the panel's
     * natural height to the strip's, never a shrink to zero.</b> That single change
     * is what makes the pill a morph rather than a swap. It used to be
     * {@code showing = 1 - fraction}, so at {@code f = 1} the panel's height was
     * <b>zero</b> and a second view had to appear in its place — two state changes
     * from one gesture, which pops by construction, and the reason the pill could
     * not be a drag handle. Now the panel at {@code f = 1} <i>is</i> the strip, at
     * its real height, and the pill needs no second view, no visibility change and
     * no lifecycle of its own.
     *
     * <p>Height also <b>never</b> falls below the strip, which is what keeps the drag
     * handle reachable at the bottom of the travel: a panel that shrank to nothing
     * would take its own handle with it and could never be dragged back up.
     *
     * <p><b>Positioning by layout height and margin rather than by
     * {@code translationY} is a regression repair, not a new idea.</b> The panel
     * used to be hidden and shown with {@code setVisibility}, which broke when it
     * was replaced by a transform: every readable property stayed correct
     * ({@code VISIBLE}, {@code isShown()}, {@code translationY} 0.0, {@code alpha}
     * 1.0, sized and attached, in a window that had drawn 904 frames) and the panel
     * was operable to the touch yet absent from the display. b/7179570 is the
     * documented mechanism — a SurfaceView's transparent region is computed from
     * layout positions, so a post-layout transform of a sibling can desync from it
     * — and layout changes, including margins, are exactly what it does recompute.
     * The entrance offset rides in {@code bottomMargin} for the same reason.
     *
     * <p><b>The corner radius is a drawable property</b>, for the same reason: it
     * must not cost a relayout, and it certainly must not be a transform. It is
     * applied to the panel's own background <i>and</i> to the strip's, to the same
     * number, because at {@code f = 1} the strip covers the panel exactly and any
     * disagreement between the two radii would show as a sliver of one poking out
     * of the other.
     *
     * <p>Over-opening is allowed a little past fully shown: that is the visible
     * tether of {@link SpringSettle#resistUp}, the top edge rising above its
     * resting place and falling back. {@code SheetMorph} carries it as the negative
     * domain of the same function rather than as a separate ceiling here, so there
     * is one place that knows the panel's height at every fraction.
     */
    private void applySheetPosition(float fraction, float belowPx) {
        if (panelNaturalHeight <= 0) {
            // First time through, the panel is still unconstrained, so this is its
            // real height and the only chance to learn it.
            panelNaturalHeight = panel.getHeight();
            if (panelNaturalHeight <= 0) return;  // not measured yet; caller defers
        }
        lastSheetFraction = fraction;
        lastSheetBelowPx = belowPx;
        float height = SheetMorph.heightFor(fraction, panelNaturalHeight,
                dp(STRIP_HEIGHT_DP));
        int target = Math.round(height);
        // The bottom margin carries TWO things and they compose additively, which is
        // the only reason one field can hold both: the entrance offset pushes the
        // panel down past the window's bottom edge, and the pill's inset lifts it
        // back off it. At rest the offset is zero and this is exactly the inset.
        int bottom = Math.round(SheetMorph.insetFor(fraction,
                dp(PILL_BOTTOM_INSET_DP) + navBarInsetPx)) - Math.round(belowPx);
        int side = Math.round(SheetMorph.pillSideMarginFor(fraction,
                screenWidthPx(), pillWidthPx()));
        // The radius is compared against the last one applied, because it changes
        // with the height and would otherwise be re-set on every frame of a drag
        // whose height had not moved. setCornerRadii invalidates the drawable: a
        // repaint, never a relayout, which is the property that makes this safe here.
        //
        // The pill's target is half the STRIP's height, not half the panel's current
        // one. Passing the current height reads as "half the box" and hands the
        // interpolation a target that grows with the sheet: measured on a device, a
        // 2400px panel was given a 443px corner half way down the drag. The current
        // height is only the CEILING, which is what SheetMorph's fourth argument
        // clamping is for.
        // The bottom pair: the same function with a SQUARE open end, which is what
        // the panel has always been. Passing the strip's own radius as the open end
        // too would make it a constant, and a bottom corner that is 28dp from the
        // first frame of the drag cuts an 83px notch out of the sheet's foot while
        // the foot is still sitting on the screen edge -- the exact artefact the
        // two-corner version was avoiding, reintroduced at the other end.
        float pillRadius = SheetMorph.cornerRadiusFor(fraction, target, 0f,
                dp(STRIP_HEIGHT_DP) * 0.5f);
        // The top pair targets that same number, so the two arrive together and the
        // collapsed shape is a capsule rather than a bar with two soft ends.
        //
        // Both targets are the CONSTANT half-strip, never each other's current value.
        // Easing the top toward the bottom's *animated* radius makes the top pair dip
        // below the sheet's own 22dp half way down the drag -- measured at 18.0dp,
        // because it is being pulled toward a radius that is itself still on its way
        // up from zero. Measured on a sweep, not reasoned about: it is a 4dp change
        // on a corner and would never have been noticed by eye.
        float topRadius = SheetMorph.cornerRadiusFor(fraction, target, dp(22),
                dp(STRIP_HEIGHT_DP) * 0.5f);
        // Every number this method writes is in the guard. Leaving the fill out
        // would be a way of shipping a colour that never moves until something else
        // happened to invalidate the drawable, and the colour is the part a bounds
        // check cannot see.
        int fill = SheetMorph.blendColour(stripFillBase, PILL_PLATE,
                SheetMorph.insetFor(fraction, 1f));
        if (panelLp.height == target && panelLp.bottomMargin == bottom
                && panelLp.leftMargin == side && panelLp.rightMargin == side
                && lastSheetTopRadius == topRadius && lastSheetBottomRadius == pillRadius
                && lastSheetFill == fill) {
            return;
        }
        lastSheetTopRadius = topRadius;
        lastSheetBottomRadius = pillRadius;
        panelLp.height = target;
        panelLp.bottomMargin = bottom;
        panelLp.leftMargin = side;
        panelLp.rightMargin = side;
        // setLayoutParams requests a layout, which is the repaint this needs.
        panel.setLayoutParams(panelLp);
        applySheetRadius(topRadius, pillRadius);
        applySheetTone(fraction, fill);
        // The pill's CONTENT lives in the sheet's top strip since the morph, but the
        // design's pill is a separate element that an open sheet COVERS -- which is why
        // an open sheet shows no pill text, and why "No depth" was on screen twice at
        // once. So the label, the mark and the outline all fade with the shape: at the
        // top of an open sheet the strip is the grip band and nothing else, exactly as
        // the design draws the sheet's top edge.
        applyStripPillness(SheetMorph.shapeProgress(fraction));
        // Same reasoning as the fade's line, and bounded by this method's own guard:
        // this is reached only when one of the numbers moved, so it is one line per
        // real change rather than one per frame.
        android.util.Log.i("Basrelief", "pill measured=" + pillWidthPx()
                + " side=" + side + " panelWidth=" + (screenWidthPx() - 2 * side));
        android.util.Log.i("Basrelief", "morph f=" + fraction
                + " height=" + target + "px (natural=" + panelNaturalHeight
                + " pill=" + dp(STRIP_HEIGHT_DP) + ")"
                + " inset=" + side + "/" + bottom
                + " radius=" + topRadius + "/" + pillRadius
                + " fill=" + Integer.toHexString(fill));
    }

    /** The top radius last written, so an unchanged height does not rewrite it. */
    private float lastSheetTopRadius = -1f;
    /** The bottom radius last written, for the same reason. */
    private float lastSheetBottomRadius = -1f;

    /**
     * Round the panel and its strip together, <b>all four corners</b>.
     *
     * <p>The top pair is the existing interpolation: the sheet's own 22dp, easing to
     * half a pill. The bottom pair is the same function asked the same question with
     * {@code 0} as the open radius, so it goes from square to the pill's own radius
     * and arrives at exactly the number the top pair arrives at — which is what makes
     * it a <b>capsule</b> rather than a bar with two soft ends. One function, two
     * arguments: there is no second rule to keep in step with the first, and the
     * ceiling that clamped the top pair clamps the bottom pair for free.
     *
     * <p><b>The bottom corners could not be rounded while the panel was flush to the
     * window's bottom edge.</b> The comment this replaces said so, and it was right
     * about the morph and wrong about the pill: rounding a box that runs off the
     * bottom of the screen shows the render through the notches and reads as a pill
     * with its foot cut off. Lifting it {@link #PILL_BOTTOM_INSET_DP} is what makes
     * the rounding mean something — which is the order these two fixes have to go in.
     */
    private void applySheetRadius(float top, float bottom) {
        float[] corners = new float[]{top, top, bottom, bottom, bottom, bottom, top, top};
        if (panelBackground != null) panelBackground.setCornerRadii(corners);
        if (stripBackground != null) stripBackground.setCornerRadii(corners);
    }

    /**
     * The morph's colour: the strip's glass, and the panel's hairline leaving.
     *
     * <p><b>The fill is a lerp between two recovered numbers</b> — {@link
     * #SHEET_GLASS}, the sheet's own glass, at {@code f = 0}, and {@link
     * #PILL_PLATE}, the original pill's plate, at {@code f = 1}. Nothing is picked
     * and nothing is invented; see the constants for both. The strip covers the
     * panel's top {@link #STRIP_HEIGHT_DP} exactly, so this is the colour of the
     * sheet's top edge, and at {@code f = 0} making it equal the panel's is what
     * means the open sheet has no plate on it that the morph then has to explain.
     *
     * <p><b>Alpha blends with the channels.</b> The strip is glass over a live
     * render — its whole job is that the photograph's edge continues underneath — so
     * a pill that arrived opaque would stop floating and start sitting, and most of
     * the distance between these two ends is alpha.
     *
     * <p><b>The panel's hairline retires, because the strip supplies the
     * outline.</b> The panel draws a 1px 14%-white line along its top edge and the
     * strip draws its own 1px 20% stroke around the same bounds, so while the sheet
     * is open the two add up to the sheet's single top border. Once the panel is
     * inset to the pill's own box that sum wraps a <i>capsule</i>, and what reads is
     * one line a little brighter than the pill ever had. Fading the panel's out as
     * {@code f → 1} leaves exactly the strip's stroke — the original pill's own
     * single outline — and it is the reason this is a blend and not a swap.
     *
     * <p>Both are paint properties, so neither costs a relayout: the one
     * {@code setLayoutParams} above is the only layout the morph asks for.
     */
    private void applySheetTone(float fraction, int fill) {
        // The whole colour, not just its alpha: the blend moves the channels by ten
        // levels and the alpha by eighty, so an alpha-only comparison would pass a
        // half-finished blend and leave the strip at the wrong tone.
        if (stripBackground != null && lastSheetFill != fill) {
            lastSheetFill = fill;
            pillFillNow = fill;
            stripBackground.setColor(fill);
        }
        // THE PANEL MUST GIVE UP ITS FILL AS THE STRIP BECOMES THE PILL, or the two
        // opaque layers compose back to solid and the glass is invisible no matter what
        // PILL_PLATE says. The panel is the sheet; the strip is the pill. At f = 1 the
        // strip covers the panel exactly, so the panel painting its own plate underneath
        // is a second coat of the same colour -- measured at roughly 80% composed from
        // two 55% layers, which is exactly the "not translucent at all" the user has now
        // reported three times while I moved the wrong number.
        //
        // They are one surface at two stages, not two surfaces, so only one of them
        // paints at any moment: the panel while it is a sheet, the strip once it is a
        // pill. The cross-fade is on shapeProgress, the same as everything else about the
        // shape, so it cannot disagree with the corners or the width.
        if (panelBackground != null) {
            panelBackground.setFillAlpha(1f - SheetMorph.shapeProgress(fraction));
        }
        if (panelBackground == null) return;
        float edge = 1f - SheetMorph.insetFor(fraction, 1f);
        if (Math.abs(lastSheetEdgeAlpha - edge) < 0.004f) return;
        lastSheetEdgeAlpha = edge;
        panelBackground.setEdgeAlpha(edge);
    }

    /**
     * The morph's fade: the <b>content below the strip</b> only, and only in the
     * edge band.
     *
     * <p><b>Never the strip.</b> The grip, the dot, the ring and the label are the
     * pill, and they are the only thing that survives the collapse, so their alpha
     * stays 1 throughout. Anything that fades is content that is leaving.
     *
     * <p><b>Never the panel's own glass either</b>, for the same reason it is never
     * faded today: the surface must stay a surface. The morph's floor means the
     * panel is also never shorter than the strip, so the glass cannot be all the
     * panel at {@code f = 1} and it has nothing to fade into.
     *
     * <p>Alpha, not a transform and not a height: alpha is a paint property, so it
     * cannot desync the {@code GLSurfaceView}'s transparent region (b/7179570) and
     * it costs no relayout per frame. It is a function of the collapse fraction
     * alone, never of scroll position — see {@link SheetMorph#edgeFade} for why that
     * is the half that matters.
     */
    private void applySheetFade(float alpha) {
        if (panel == null) return;
        // From index 1: index 0 is the strip, which is the pill and never fades.
        // Every other child — the filmstrip, the fix line, the scroll — is content
        // below it. Iterating rather than naming them means a child Gallery adds
        // later fades with everything else instead of staying behind.
        int written = 0;
        for (int i = 1; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            // The epsilon keeps a settle's tail from writing the same value every
            // frame, which is a per-frame invalidate for nothing.
            if (Math.abs(child.getAlpha() - alpha) > 0.004f) {
                child.setAlpha(alpha);
                written++;
            }
        }
        // One line per actual change, in the same spirit as the wallpaper's
        // per-resolution diagnostic and for the same reason: the morph's two
        // numbers are invisible from the outside — uiautomator emits no alpha, and
        // screencap cannot be trusted for appearance — so the only honest way to
        // check them from outside this process is for the process to say them.
        // Bounded by the write count, so a drag logs its band's descent and nothing
        // else: above the band the alpha is 1.0 and this never fires at all.
        if (written > 0) {
            android.util.Log.i("Basrelief", "morph f=" + lastSheetFraction
                    + " contentAlpha=" + alpha
                    + " children=" + (panel.getChildCount() - 1));
        }
    }

    /**
     * Add Origin and Pan as two sibling sections.
     *
     * <p><b>{@link FramingRows} hands back ONE box</b> holding a caption, two rows,
     * a caption and two more — and it cannot be asked for half of it, because every
     * call builds all four sliders, registers them into the shared {@code sliders}
     * map and installs a layout listener on the GL view. So it is built once and the
     * rows are <b>re-parented</b>: each family caption opens a section and the rows
     * after it are moved into that section's body. The sliders are the same objects
     * either way; only their parent changes.
     *
     * <p><b>Each row is removed from the box before it is added to a section.</b>
     * {@code addView} does not re-parent: it throws
     * {@code IllegalStateException: The specified child already has a parent}. This
     * was a crash on every launch until the {@code removeView} went in, so it is
     * written down rather than left as a line that looks like tidiness.
     *
     * <p><b>Rows are filed by the caption above them, not by a hard-coded
     * index.</b> A caption is a {@link TextView} and an axis row is a
     * {@link BasreliefSlider}, which is a {@code LinearLayout} and not a
     * {@code TextView}, so the two are told apart without depending on the order the
     * rows were added in — and X before Y is preserved because the order inside each
     * family is whatever {@code FramingRows} built, unchanged.
     *
     * <p><b>A shape this does not recognise is logged, not guessed at.</b> If a
     * future change to {@code FramingRows} returns something that is not a vertical
     * box, or a row appears before any caption, the layout degrades to "one section
     * per caption found" and says so in logcat — rather than inventing a heading for
     * rows it cannot place, which is the same class of bug as a caption over
     * nothing.
     */
    private void addFramingSections() {
        View built = FramingRows.build(this);
        if (!(built instanceof LinearLayout)) {
            android.util.Log.w("Basrelief", "framing: unexpected shape "
                    + (built == null ? "null" : built.getClass().getName())
                    + ", so no section was added for it");
            return;
        }
        LinearLayout box = (LinearLayout) built;
        // Snapshotted first: addView detaches from `box`, so iterating box's own
        // child list while re-parenting would skip every second row.
        List<View> children = new ArrayList<View>(box.getChildCount());
        for (int i = 0; i < box.getChildCount(); i++) children.add(box.getChildAt(i));

        SheetSection section = null;
        int captions = 0;
        int orphaned = 0;
        for (View child : children) {
            if (child instanceof TextView) {
                // The caption's own words become the section heading, so the wording
                // is the same string it always was and nothing is re-typed here.
                section = addSection(((TextView) child).getText().toString());
                captions++;
                continue;
            }
            if (section == null) {
                // Cannot happen with today's FramingRows, which opens with a caption.
                // Counted rather than filed under a guessed heading.
                orphaned++;
                continue;
            }
            // Detach first: addView onto a view that still has a parent throws
            // rather than moving it.
            box.removeView(child);
            section.body().addView(child);
        }
        // Loud on purpose: this is the shape assumption above, and it should be
        // visible if it ever stops holding rather than quietly changing the sheet.
        if (captions != 2 || orphaned > 0) {
            android.util.Log.w("Basrelief", "framing: expected 2 family captions and no "
                    + "leading rows, found " + captions + " and " + orphaned);
        }
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
                sheet.reapply();
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
                        String zoomId = AppState.load(this, prefs, getFilesDir()).activeId();
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
        TextView tv = captionView(text, first);
        sheetContent.addView(tv);
        return tv;
    }

    /**
     * One caption, styled and spaced, but not added to the tree.
     *
     * <p>Split out of {@code sectionLabel} so {@link SheetSection} can own its
     * caption. The two used to be one method that added the label to the scroll
     * as a side effect, which is exactly how a caption could outlive the body it
     * named.
     */
    private TextView captionView(String text, boolean first) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x57FFFFFF);
        tv.setTextSize(11);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.09f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        layOutCaption(tv, first);
        return tv;
    }

    /**
     * One sheet section: its caption and the rows that caption names, as one
     * unit.
     *
     * <p><b>Why the unit exists.</b> The sheet used to add a caption and then
     * its rows as unrelated siblings of the scroll, and two real defects came
     * out of that shape. "This photo's map" was added unconditionally while its
     * Regenerate/Replace row was added only when the launch photo had a map, so
     * a photo with no map showed a caption over nothing; and the row was a local
     * captured at construction, so selecting a different photo left a row
     * describing the photo that was no longer on screen. With both in one
     * object, "is this section shown" is one decision, and a body can be
     * replaced wholesale without leaving its caption behind.
     *
     * <p>Caption and body are both direct children of the section, and the
     * section is a single child of the scroll. Hiding the whole unit is the only
     * way to make a section absent -- the caption is never hidden on its own,
     * because that is the orphan this component exists to prevent.
     *
     * <p><b>{@link #syncCaption()} is that rule, enforced rather than
     * remembered.</b> The paragraph above is a promise about every call site, and
     * this project has a standing record of a promise about call sites not holding:
     * "This photo's map" was added unconditionally while its row was added
     * conditionally, and a caption stood over nothing. So the caption's visibility is
     * derived from the body instead of being decided beside it -- one derivation, so
     * a body emptied by a later refresh cannot leave a caption naming nothing, which
     * is the reported orphan. It is a caption that may be hidden; the section as a
     * whole is still only ever hidden as a unit.
     */
    private final class SheetSection extends LinearLayout {

        private final TextView caption;
        private final LinearLayout body;

        SheetSection(String captionText) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            caption = captionView(captionText, false);
            addView(caption);
            body = new LinearLayout(MainActivity.this);
            body.setOrientation(VERTICAL);
            addView(body);
        }

        /** The rows under this section's caption. */
        LinearLayout body() {
            return body;
        }

        /**
         * Hide this section's caption while its body has nothing in it.
         *
         * <p>Re-checked whenever the body is built and whenever it is rebuilt, so
         * the caption and its rows cannot drift apart across a selection change.
         * {@code refreshMapSection} is the existing precedent for re-deriving a
         * section's contents from the live selection; this is the same idea applied
         * to the heading rather than the body.
         *
         * <p><b>Visibility, not emptiness.</b> A child that is itself GONE does not
         * count: a caption over a hidden row is the same orphan as a caption over no
         * row, and the sheet has a GONE body of its own (the Depth row, removed
         * while the selected photo has no map).
         */
        void syncCaption() {
            boolean anyVisible = false;
            for (int i = 0; i < body.getChildCount(); i++) {
                if (body.getChildAt(i).getVisibility() == View.VISIBLE) {
                    anyVisible = true;
                    break;
                }
            }
            int want = anyVisible ? View.VISIBLE : View.GONE;
            // Guarded so the common case costs nothing: this runs on every refresh,
            // and setVisibility requests a layout even when it changes nothing.
            if (caption.getVisibility() != want) {
                caption.setVisibility(want);
                android.util.Log.i("Basrelief", "orphan guard: hid the caption of \""
                        + caption.getText() + "\" because its body is empty");
            }
        }
    }

    /**
     * Add one section to the scroll.
     *
     * <p>The caption is synced <b>once, posted</b>, rather than being left to the
     * caller: at construction every body is empty, so the answer cannot be known
     * yet, and a rule that depends on remembering to check after the last row is a
     * rule that will be forgotten at the one section whose body can be empty. A
     * posted runnable lands after the caller has finished filling the body and
     * after this layout pass, so it sees the section as the user will.
     */
    private SheetSection addSection(String caption) {
        final SheetSection section = new SheetSection(caption);
        sheetContent.addView(section);
        section.post(new Runnable() {
            @Override
            public void run() {
                section.syncCaption();
            }
        });
        return section;
    }


    /**
     * The sheet has come to rest, or an entrance has placed it. The only two moments
     * the position or the collapsed state is decided.
     *
     * <p><b>What this replaced.</b> The old pill's {@code updatePillVisibility} did
     * two unrelated things from this same callback: it showed and hid the second
     * view, and it drove the navigation bar. The first half is gone — there is no
     * second view — and what is left is here, which is why this method exists at all
     * rather than the callback being a one-liner.
     *
     * <p>Two keys, because one key cannot hold both the current state and the place
     * to return to. {@code sheet_pos} is the current position and is 1.0 while the
     * sheet is away; {@code sheet_open_pos} is the last position the sheet rested at
     * while it was <i>not</i> away, which is what a tap on the pill returns to. See
     * {@link #KEY_SHEET_OPEN}.
     *
     * <p>The bar follows the <b>committed</b> state, not a live threshold, so a drag
     * that reaches the bottom and reverses never flashes the system bars and a
     * mid-settle every-frame relayout is not a second state machine. Guarded on
     * attachment so a position resolved before the window exists cannot force the
     * decor early; {@code onWindowFocusChanged} applies the same state once there is
     * one.
     */
    private void onSheetResting(float fraction, boolean collapsed) {
        // Every release stores the exact position; the launch reads it back.
        prefs.edit().putFloat(KEY_SHEET, fraction).apply();
        if (!collapsed) {
            // Only while it is present, so a collapse cannot overwrite the place to
            // return to with 1.0 — which is the bug a single key would have.
            prefs.edit().putFloat(KEY_SHEET_OPEN, fraction).apply();
        }
        // Expanded, back is discoverable and the bar composites over the render;
        // collapsed, the wallpaper must be full-bleed. 26–34 keep their
        // always-immersive flags.
        if (Build.VERSION.SDK_INT >= 35 && panel != null && panel.isAttachedToWindow()) {
            applySystemUi();
        }
        if (collapsed) dismissHintIfShown();
    }

    /**
     * One line of section body text: a fact, not a control.
     *
     * <p>Dim at 12sp, the same reading weight as the photo line and the surface
     * note. A statement dressed like a slider label would read as something to
     * operate.
     *
     * <p>The map section's body was its only user, and it stopped needing one when
     * the demo got a file: the body used to say "Ships with its own depth map"
     * instead of offering Regenerate/Replace, which was a body with nothing to do
     * dressed as a fact. Kept because a section body is a legitimate shape and
     * nothing else should have to build one by hand.
     */
    private TextView sectionNote(int textRes) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextSize(12);
        t.setTextColor(0x8CFFFFFF);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    /**
     * The mockup's {@code .caption} padding.
     *
     * <p>A method rather than an inline call because which caption is "first" can
     * change: hiding the depth caption when its row is absent makes "How it moves"
     * the first caption in the scroll, and it has to take the 2px top gap rather
     * than the 16px of an inter-group heading. Two captions cannot both be first,
     * and the depth row's presence is the one thing that decides which is.
     *
     * <p>No side padding: {@code .caption{margin:16px 0 7px}} has none, and the
     * 4dp this carried put every caption 4dp right of the slider labels and
     * tracks beneath it, which begin at the scroll's own 14dp edge. Every
     * caption is a label for the rows under it, so it has to share their column.
     */
    private void layOutCaption(TextView tv, boolean first) {
        tv.setPadding(0, first ? dp(2) : dp(16), 0, dp(7));
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
     * What the two light rows currently feed the preview.
     *
     * <p>Floats, unlike Brightness and Shadow, because the stored angle is a
     * float: the defaults are 124.992° and 50.862° and the whole point of those
     * non-round constants is that an existing wallpaper does not get relit on
     * upgrade. The rows print whole degrees; the untouched angle keeps its
     * stored value, so a drag on one row previews the light that is actually
     * stored rather than a rounded version of it.
     */
    private float shownAzimuth = SurfaceSettings.DEFAULT_LIGHT_AZIMUTH;
    private float shownElevation = SurfaceSettings.DEFAULT_LIGHT_ELEVATION;

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
        } else if ("light_azimuth".equals(key) || "light_elevation".equals(key)) {
            // Both angles go in one call, because setLight's angle and vector must
            // never be updated apart; the angle this key is not moving comes from
            // the same shown field a drag maintains, exactly as the adjustment
            // pair above does. One call, not two setters.
            renderer.setLight(
                    "light_azimuth".equals(key) ? value : shownAzimuth,
                    "light_elevation".equals(key) ? value : shownElevation);
        } else renderer.setDepth(value / 100f);
    }

    /**
     * A stored azimuth as the whole degree its row shows.
     *
     * <p>Rounded rather than truncated, so the default 124.992° reads 125° and
     * not 124° -- the same reason the constants are exact: the number on screen
     * should be the nearest degree to the light being drawn. Re-normalising
     * through {@link LightDirection} keeps the result inside the row's
     * {@code [AZIMUTH_MIN, AZIMUTH_MAX)} domain: rounding 359.6° gives 360,
     * which is the same light as 0 and must show as 0, not as a value the row's
     * own maximum cannot represent.
     */
    private static int azimuthForSlider(float degrees) {
        return (int) LightDirection.normaliseAzimuth(Math.round(degrees));
    }

    /**
     * A stored elevation as the whole degree its row shows. See
     * {@link #azimuthForSlider}; elevation clamps, so there is no seam to fold.
     */
    private static int elevationForSlider(float degrees) {
        return Math.round(LightDirection.normaliseElevation(degrees));
    }

    /**
     * Record the light angle a row is now showing, so the sibling row's next
     * preview sends the light the user is looking at.
     *
     * <p>Called from the preview <b>and</b> from the release path, because a
     * nudge never previews -- it is already a complete gesture. Without the
     * second call the value a nudge wrote would be missing from the next drag of
     * the other row, and that drag would flash the pre-nudge angle back onto the
     * photograph until its own release re-read the store.
     */
    private void noteShownLight(String key, int value) {
        if ("light_azimuth".equals(key)) shownAzimuth = value;
        else if ("light_elevation".equals(key)) shownElevation = value;
    }

    private String formatValue(String key, int value) {
        if (key.equals("zoom")) return String.format(Locale.US, "%.1f×", value / 100f);
        if (key.equals("intensity")) return value + "%";
        if (key.equals("motion")) return value + "%";
        // The light's two rows print their own unit, and only their own unit: the
        // convention (azimuth counter-clockwise from the right, elevation 0
        // grazing) lives in the caption above them, because neither number means
        // anything without knowing what is being measured.
        if (key.equals("light_azimuth") || key.equals("light_elevation")) {
            return getString(R.string.slider_degrees, value);
        }
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
                        // A nudge is a complete gesture with no preview, so the shown
                        // angle is recorded here as well: without it the next drag of
                        // the sibling row would preview with the angle the nudge
                        // replaced. See noteShownLight.
                        noteShownLight(key, v);
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
                else noteShownLight(key, v);
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
     * <p>Every value is written for the demo too. Depth and Zoom used to
     * be refused there because {@code Framing} forced the demo to its defaults,
     * so a stored value would have been one the renderer never read. The fix was
     * to make {@code Framing} read the item, so there is nothing left to refuse;
     * and the demo's image is a file in {@code filesDir} now, so nothing about it
     * is unwriteable either.
     */
    private void persist(final String key, final int value) {
        final boolean isDepth = "intensity".equals(key);
        final boolean isZoom = "zoom".equals(key);
        final boolean isMotion = "motion".equals(key);
        AppState state = AppState.load(this, prefs, getFilesDir());
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
                if ("light_azimuth".equals(key) || "light_elevation".equals(key)) {
                    // Same rule as the adjustment pair: the angle this key does not
                    // name is read from the SAME surface, unrounded, so dragging
                    // elevation cannot snap the stored 124.992° azimuth to the 125°
                    // the row displays. withLight wraps and clamps through
                    // SurfaceSettings, which is the one place that happens.
                    SurfaceSettings other = item.settings(editSurface);
                    return item.withLight(editSurface,
                            "light_azimuth".equals(key) ? value : other.lightAzimuth,
                            "light_elevation".equals(key) ? value : other.lightElevation);
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
        AppState state = AppState.load(this, prefs, getFilesDir());
        LibraryItem item = state.activeItem();
        // What the strip and grid were last brought in step with. Recorded on
        // every refresh rather than only after a rebuild, because every in-app
        // selection reaches this method (a gallery tap, a delete of the active
        // row, the share path selecting an existing photo), and a later resume
        // that sees the same id must not re-decode the whole strip; see
        // onResume. The stamp goes with the id because under the "Recently
        // used" order it is the sort key, so a rotation can move the row
        // without moving the selection.
        shownActiveId = state.activeId();
        shownActiveUsedAt = item == null ? 0L : item.lastUsedAt;
        refreshFixLine(state);
        // The photo line and the lock note answer "what is the wallpaper doing",
        // which changes exactly when the selection does, so they are refreshed
        // from the same one read as everything else here.
        refreshPhotoLine(state);
        refreshSurfaceNote(state);
        syncDepthRow(state);
        refreshMapSection(state);
        // Read once, here, and used by both the sliders and the sensor policy
        // below. Two reads would be two chances to disagree, which is how a slider
        // ends up showing 0 while the sensor is still told to run.
        SurfaceSettings s = item == null ? null : item.settings(editSurface);
        // Every bar is live, including on the demo. They used to be disabled
        // for it, because Framing forced the demo to the defaults and a live slider
        // there displayed a number the renderer ignored -- the same class of lie as a
        // status line claiming content that is not there. Framing reads the demo's
        // stored values now, and the demo has a file to render them against, so
        // the premise is gone and so is the reason to disable.
        {
            setBar("intensity", s == null ? SurfaceSettings.DEFAULT_DEPTH : s.depth, true);
            setBar("zoom", s == null ? SurfaceSettings.DEFAULT_ZOOM : s.zoom, true);
            setBar("motion", s == null ? SurfaceSettings.DEFAULT_MOTION : s.motion, true);
            shownBrightness = s == null ? SurfaceSettings.DEFAULT_ADJUST : s.brightness;
            shownShadow = s == null ? SurfaceSettings.DEFAULT_ADJUST : s.shadow;
            setBar("brightness", shownBrightness, true);
            setBar("shadow", shownShadow, true);
            // The light, repopulated from the same one read as every other row.
            // This is the row the "previous surface's values" bug must not skip:
            // the stored angle is a float and the row shows whole degrees, so the
            // shown values keep the stored precision while the bars get the
            // rounded form -- precision for the sibling row's preview, rounding
            // for the number the user reads.
            shownAzimuth = s == null
                    ? SurfaceSettings.DEFAULT_LIGHT_AZIMUTH : s.lightAzimuth;
            shownElevation = s == null
                    ? SurfaceSettings.DEFAULT_LIGHT_ELEVATION : s.lightElevation;
            setBar("light_azimuth", azimuthForSlider(shownAzimuth), true);
            setBar("light_elevation", elevationForSlider(shownElevation), true);
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
     * Put "This photo's map" into the state of the photo actually selected.
     *
     * <p><b>Two states, and no third.</b>
     *
     * <ul>
     *   <li>A photo with a map gets the Regenerate/Replace row — <i>including</i>
     *       the bundled demo, which is a file-backed row like any other since the
     *       seed copied its pair into {@code filesDir}. It had a special body here
     *       ("Ships with its own depth map") for as long as it had no image file to
     *       regenerate from and nowhere to write a replacement; both reasons are
     *       gone, so it now gets the row every other photo gets.</li>
     *   <li>A photo with no map gets <b>no section at all</b>, not a bare caption.
     *       The fix line already says "No depth" with the two actions that answer
     *       it, directly under the grip where it cannot scroll away; a second copy
     *       here would be two controls for one fact.</li>
     * </ul>
     *
     * <p>Rebuilt from the live selection on every refresh, which is what makes
     * the row always describe the photo on screen rather than the one selected
     * when the sheet was built. The old shape -- caption added unconditionally,
     * row built once from the launch item -- produced both defects this replaces:
     * a caption over nothing for a launch photo with no map, and the previous
     * photo's row left behind after a selection change.
     *
     * <p><b>This is the one section whose body is emptied on purpose</b>, which is
     * why the caption is re-synced at the end rather than only at construction: a
     * selection change clears the body and rebuilds it, so a caption decided once
     * would be describing whatever the body held when the sheet was built. The
     * whole section is still hidden as a unit here — which is stronger than the
     * caption guard and the right thing for this one — and the sync keeps the
     * invariant true for the case where a caller later stops hiding the section.
     */
    private void refreshMapSection(AppState state) {
        if (mapSection == null) return;
        mapSection.body().removeAllViews();
        LibraryItem item = state.activeItem();
        // No selection means the renderer is drawing the packaged demo pair, which
        // does have a map -- so the caption belongs, and the ordinary row does not:
        // there is no id to write a regenerated map against.
        if (item == null || item.depthName == null) {
            mapSection.setVisibility(View.GONE);
            mapSection.syncCaption();
            return;
        }
        mapSection.body().addView(buildMapActions());
        mapSection.setVisibility(View.VISIBLE);
        mapSection.syncCaption();
    }

    /**
     * Regenerate and Replace, on one row, for a file-backed photo with a map.
     *
     * <p>Two actions because an applied map has two things you might want to do
     * to it, and neither is a third thing: deleting it belongs to the gallery
     * row menu, where delete already asks first.
     *
     * <p>Gallery is NOT here. It was a button because browsing used to be a
     * mode you entered; it is the filmstrip now, so a button that opens a
     * gallery you are already standing in is a control with nothing to do.
     * Calibrate is not here either: it is a reported condition in Settings >
     * Gyro, not something the user does while looking at a photo.
     *
     * <p>Built fresh for each selection rather than captured once. Both actions
     * resolve the active photo at click time -- the picker path reuses the
     * active id, and the depth panel captures its subject when it opens -- so a
     * stale row could only ever describe the wrong photo, never edit it.
     */
    private View buildMapActions() {
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
        return mapRow;
    }

    /**
     * <b>The one answer to "where does this photo's depth come from".</b>
     *
     * <p>Every caller goes through here, and the gallery's chip should too — which
     * is the fix and not a tidy-up. The gallery derives it from {@code Io}'s
     * embedded callback and this used to derive it from {@code depthName != null},
     * and the two disagreed on exactly the photographs this app's own
     * {@code dePH} format exists to produce. Two answers to one question is the
     * class of bug this project keeps paying for, so there is one function now.
     *
     * <p>Also <b>starts the read when the answer needs one</b>, which is why it is
     * called rather than merely consulted: an item with no separate depth file cannot
     * be answered from the index, and until the file has been read the answer is
     * {@link DepthSource.Kind#UNKNOWN} — silent, offering nothing. See
     * {@link #embeddedDepthById}.
     */
    private DepthSource.Answer depthSourceFor(AppState state) {
        LibraryItem item = state.activeItem();
        DepthSource.Answer answer =
                DepthSource.forItem(item, embeddedAnswerOf(item));
        // Only a photo the index cannot answer needs the file, so this is the only
        // case that can open the window — and an item with a depth file therefore
        // never flickers through "unknown" on a selection.
        if (answer.isUnknown()) requestEmbeddedDepth(item);
        return answer;
    }

    /** The cached embedded flag for an item, or null while it has not been read. */
    private Boolean embeddedAnswerOf(LibraryItem item) {
        if (item == null) return null;
        return embeddedDepthById.get(item.id);
    }

    /**
     * Read one image's PNG and remember whether it carries a depth chunk.
     *
     * <p><b>Off the UI thread, on the shared {@link Io} worker</b>, because it is a
     * whole-file read. It must never run inline: this is reached from the fix line,
     * which runs on the UI thread, and the wallpaper parses state on the GL thread
     * at cold start where a blocking read is a stalled wallpaper.
     *
     * <p><b>The delivery's only effect is the cache, then a re-derive.</b> It does
     * not carry the answer to whichever line asked for it — it is keyed by the id it
     * read, and the refresh that follows resolves the <i>current</i> selection from
     * the cache. That is what makes two selections in quick succession safe: the
     * slower read cannot land on the newer photo.
     */
    private void requestEmbeddedDepth(final LibraryItem item) {
        if (item == null) return;
        synchronized (embeddedReadInFlight) {
            if (embeddedDepthById.containsKey(item.id)) return;   // already answered
            if (!embeddedReadInFlight.add(item.id)) return;       // already reading
        }
        final String id = item.id;
        final File image = PrefFiles.resolve(getFilesDir(), item.imageName);
        Io.run(this, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return image != null && readEmbeddedDepth(image);
            }
        }, new Io.Callback<Boolean>() {
            @Override
            public void onDone(Boolean embedded, Exception error) {
                synchronized (embeddedReadInFlight) {
                    embeddedReadInFlight.remove(id);
                }
                // An unreadable file answers false, not "unknown": it is a settled
                // answer and it is what the gallery already does for the same file.
                // Leaving it unknown would keep the line silent for ever.
                embeddedDepthById.put(id, embedded != null && embedded);
                // Re-derive rather than assume: by now the selection may be
                // somewhere else entirely, and this refresh reads whatever is
                // selected NOW. If the selection has not moved, this is the frame
                // on which the line stops being blank and starts telling the truth.
                refreshSliders();
            }
        });
    }

    /**
     * Whether one PNG carries a depth chunk in a {@code dePH}/{@code dEPt} chunk.
     *
     * <p>The whole file is read, because {@link DepthPng#extract} walks the chunk
     * list and a chunk written after {@code IDAT} would be missed by a truncated
     * read — and answering "no embedded depth" for a photograph that has one is this
     * bug pointing the other way. Correctness over the cost of a buffered read that
     * happens off the UI thread, once per photo.
     *
     * <p>Never throws: a missing or unreadable file is "no", which is the gallery's
     * answer for it too.
     */
    private static boolean readEmbeddedDepth(File file) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            return DepthPng.extract(bytes.toByteArray()) != null;
        } catch (Throwable ignored) {
            // OOM on a very large image, or an I/O error. Either way the honest
            // answer is "no embedded chunk", which is what the renderer will find.
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Whether this item has a depth map, from the one derivation.
     *
     * <p>The Depth slider's whole reason for existing, and the caption's. It used to
     * be {@code item == null || item.depthName != null}, which knew only about a
     * separate depth file and so hid the control for a photograph whose depth was
     * inside its own PNG — the same knowledge gap that put "No depth" on screen.
     *
     * <p><b>Unknown counts as "no slider", not as "no depth".</b> The slider's
     * absence is the signal that there is nothing to adjust, and a momentary absence
     * while a file is being read is a far smaller lie than a control offering to
     * adjust a map that may not be there. It is restored the frame the answer lands.
     * The <i>line</i> is the opposite choice, because a line claiming absence is the
     * reported bug; see {@link #refreshFixLine}.
     */
    private boolean initialItemHasMap(AppState state) {
        DepthSource.Answer answer = depthSourceFor(state);
        return answer.isUnknown() ? false : answer.hasDepth();
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
        // Reset first, so only the branch that means it actually changes the colour.
        setFixBorderColour(FIX_BORDER);

        // A run in progress, and a run that just failed, are conditions too. The
        // mockup puts both in the SHEET, not only on the collapsed pill: "the sheet
        // is open, so the fix line is already the signal".
        if (DepthGenDialog.isRunning()) {
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
        // The one derivation, so this line and the gallery's chip cannot disagree
        // about the same photograph. It may also start the file read that resolves an
        // embedded map, which is why it is consulted rather than looked up.
        DepthSource.Answer depth = depthSourceFor(state);
        // A failed run outranks "there is a map", because the sentence reports what
        // SURVIVED and a failure is the thing the user has to act on. Kept above the
        // depth answer and deliberately keyed on the item's own file rather than on
        // hasDepth(): the question is whether anything is left to lose, and an
        // embedded map cannot be the thing a regeneration just destroyed.
        if (DepthGenDialog.lastResult() == DepthGenDialog.Result.FAILED
                && ((item != null && item.depthName != null)
                    || !DepthGenDialog.failureCause().isEmpty())) {
            // An error that does not say what survived leaves the user unable to tell
            // whether their depth is gone, which is the worst outcome this app has.
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
        if (depth.isUnknown()) {
            // The window while an embedded depth map is still being read. The line
            // says <b>nothing</b> rather than claiming an absence it has not
            // established, and it deliberately also does not report flat lit: until
            // the depth is known, whether this photo is shaded is not known either.
            // The next refresh fills it in.
            setFixLine(null, null, null, null, null);
            setStripState(StripState.IDLE);
            return;
        }
        if (!depth.reportsNoDepth()) {
            // The photograph HAS depth. Shading is the separate question: it is
            // supplied by a normal map or it is not, and if it is not the relief is
            // flat lit. This is the new middle state — displaced correctly, lit as a
            // plane — and it is neither "No depth" nor a fault, so it says why there
            // is no shading and offers the fix rather than reading as broken.
            //
            // The one resolution of that question, so this line, the renderer and the
            // status line cannot disagree. Nothing selected is the packaged trio,
            // which ships a normal, so it is silent like every shaded photo.
            NormalSource.Answer normals = NormalSource.forItem(item);
            if (normals.needsNormals()) {
                setFixLine(getString(R.string.fix_flat_lit),
                        getString(R.string.fix_flat_lit_actions), new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // The ordinary Depth AI panel, and not a special
                                // path: it already prefers MoGe, which is the model
                                // that emits the normal this state is missing. The
                                // user can change checkpoint, resolution and host
                                // there exactly as any other run.
                                openDepthGen();
                            }
                        }, null, null, false);
                return;
            }
            // Shaded, or the packaged trio: nothing to report. A line that can only
            // inform is a status paragraph, and this is one sentence.
            setFixLine(null, null, null, null, null);
            setStripState(StripState.IDLE);
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
        // No side padding: this is a full-width reading that sits in the same
        // column as the captions and rows below it.
        t.setPadding(0, dp(2), 0, dp(6));
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
            // Armed, but showing a photo it does not contain -- the user picked
            // this one by hand. Falling through here used to drop the playlist
            // mention entirely, so the line read as though nothing were armed
            // while the playlist was still scheduled to take the screen back at
            // its next trigger. The pointer and the active ring deliberately do
            // not move: the ring means what is on screen, and the missing fact is
            // better said than moved. Both this and the gallery header's version
            // read PlaylistStatus, so the two screens cannot disagree.
            long now = System.currentTimeMillis();
            PlaylistStatus status = PlaylistStatus.of(lists, state.library, now);
            setPhotoLine(name, getString(R.string.photo_line_playlist_armed,
                    name, rotating.displayName(),
                    PlaylistScreen.nextChangeLabel(this, rotating, status, now)));
            return;
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
        // below it is what separates the note from the photo line. No side
        // padding, so its text shares the row column like every other line.
        t.setPadding(0, 0, 0, dp(8));
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
                            AppState st = AppState.load(MainActivity.this,
                                    prefs, getFilesDir());
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

    /**
     * Clear the app's stored state, back to what a fresh install starts with.
     *
     * <p><b>Prefs and files together, because they are one dataset.</b> The
     * blobs in {@code depthwall} are the library, the playlists, the config and
     * the settings toggles; the files in {@code filesDir} are what the library
     * rows name. Clearing one without the other leaves either rows that render
     * nothing or files nothing can reach — and a reset is the one gesture that
     * says "start over", so it removes the whole thing. That is also why it
     * deletes orphan files: filesDir is the app's private photo store, and a
     * rule that kept "referenced" files would have to know which orphans were
     * once referenced.
     *
     * <p><b>Removing the key is what makes the next launch fresh.</b>
     * {@link AppState#load} preloads the bundled demo only when no index has
     * ever been stored, so clearing the key brings the demo back; writing an
     * empty index instead would be an emptied gallery, and the demo would stay
     * gone. That distinction is the whole reason Settings can restore the demo
     * and Delete cannot.
     *
     * <p>Files go last and off the UI thread: the blobs are the truth, so a
     * crash between the two leaves orphan files, which are invisible, rather
     * than an index pointing at files that are gone.
     */
    void resetApp() {
        prefs.edit().clear().commit();
        // Bitmaps of the photos that are about to be deleted. Nothing looks
        // them up again, but holding megabytes of a deleted photo is the kind
        // of leak that only shows up as an OOM on someone else's device later.
        PairCache.clear();
        final File dir = getFilesDir();
        Io.run(new Callable<Void>() {
            @Override
            public Void call() {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
                return null;
            }
        });
        // Back to the home surface. A reset that left the sheet editing a lock
        // override would show a screen the fresh index cannot render, and the
        // segment would offer to edit values that no longer exist.
        editSurface = Surface.HOME;
        if (gallery != null) gallery.setSurface(Surface.HOME);
        renderer.setEditSurface(Surface.HOME);
        // The clear() removed the index key, so this load is a genuine first
        // run: it preloads the demo and writes it under the same rule every
        // other surface reads.
        applyFiles();
        refreshSliders();
        refreshSurfaceSegment();
        if (gallery != null) gallery.reindex();
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
        String active = isImage ? null : AppState.load(this, prefs, getFilesDir()).activeId();
        if (!isImage && active == null) {
            // Nothing is selected, so there is no item whose map this could become:
            // the filename is derived from the item's id, and there is no id. That
            // is a state about the selection, not about any one row -- the demo is
            // a file-backed row like every other and takes a picked map exactly as
            // an imported photo does, under the same derived name.
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
                    // The picker can be opened from the open grid, whose list was
                    // read before this row existed; re-read it now or the selected
                    // photo is missing from the gallery that is still on screen.
                    gallery.reindex();
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
        AppState state = AppState.load(this, prefs, getFilesDir());
        LibraryItem active = state.activeItem();
        // Resolved from the item, and from nothing else. There is no branch for
        // "the demo": its image is a file in filesDir under the name its own id
        // derives, so it takes this path exactly as an imported photo does. A null
        // item -- nothing is selected -- resolves to nothing at all, and the
        // renderer draws the packaged demo pair for that, which is the documented
        // meaning of an empty gallery and the state a delete of the active row
        // lands in.
        java.io.File imgF = PrefFiles.resolve(getFilesDir(), active == null ? null : active.imageName);
        java.io.File depF = PrefFiles.resolve(getFilesDir(), active == null ? null : active.depthName);
        // The supplied normal, resolved from the same item as the other two. Null
        // is a real answer -- flat lit -- and not a failure: it is the state of every
        // photo whose depth predates normals, and the renderer draws it deliberately.
        java.io.File nrmF = PrefFiles.resolve(getFilesDir(), active == null ? null : active.normalName);
        final String img = imgF == null ? null : imgF.getAbsolutePath();
        final String dep = depF == null ? null : depF.getAbsolutePath();
        final String nrm = nrmF == null ? null : nrmF.getAbsolutePath();
        final Framing framing = Framing.of(state.activeItem(), editSurface);
        // Same pair means this resolve only moves the framing: it goes through the
        // apply guard (`RESOLVE`), so a framing already on screen is a no-op and a
        // glide in flight is not cancelled. A DIFFERENT pair is a new picture, and
        // its framing must be painted -- gliding it would smear one photo into the
        // next -- so it states `PAIR_CHANGE` and bypasses the guard. Read here, on
        // the calling thread, because the renderer's own record is the only honest
        // answer and it is volatile. The normal is part of the identity: a photo
        // that gains one is a pair change, or the new texture is never uploaded.
        final ReliefRenderer.Apply why = renderer.showingSamePair(img, dep, nrm)
                ? ReliefRenderer.Apply.RESOLVE
                : ReliefRenderer.Apply.PAIR_CHANGE;
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.apply(framing, why);
            }
        });
        // Async for the same reason as the wallpaper: a synchronous load stalls
        // the GL thread for the length of a full decode plus mesh build.
        renderer.loadPairAsync(img, dep, nrm);
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
     * so a control below the fold is visibly reachable; {@code SHEET_FADE_DP}
     * makes it taller, a deliberate deviation. Android's fading edge is
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
     * view <b>bounds</b>: a margin moves the bounds (and the fade) up away from
     * the window's bottom edge, and this view's own padding is clipped out of the
     * fade region, which is why the navigation-bar inset lives on the content
     * instead. See the inset listener in {@code onCreate}.
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

        /**
         * Keep the parent in the running for this gesture.
         *
         * <p>{@code ScrollView} declares itself the only vertical claimant for the
         * rest of a drag: the moment it starts scrolling it calls
         * {@code requestDisallowInterceptTouchEvent(true)} on its parent, so the
         * panel's arbitration is never offered another event and the nested
         * hand-over the sheet needs — scroll the content to its end, then the same
         * drag moves the sheet — can never happen inside one gesture. Clearing the
         * flag after every event puts the choice back with the panel, whose rule is
         * exactly {@code canScrollVertically}: while the content can use the drag
         * the panel declines, and the moment it cannot the panel claims it.
         *
         * <p>The clear is safe for the sliders: a slider's own
         * {@code requestDisallowInterceptTouchEvent(true)} arrives on its DOWN,
         * before this view is the touch target, and once a child owns the gesture
         * this view's intercept and touch handlers are not called at all.
         */
        private void reofferGestureToParent() {
            android.view.ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            boolean intercepted = super.onInterceptTouchEvent(e);
            reofferGestureToParent();
            return intercepted;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean handled = super.onTouchEvent(e);
            reofferGestureToParent();
            return handled;
        }
    }
}
