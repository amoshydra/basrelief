package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.amoshydra.basrelief.core.gesture.SheetIntent;

/**
 * The mockup's {@code .scrim + .modal}, built once.
 *
 * <p>Three screens need the same thing — apply, depth generation, playlists — and each
 * had written its own, which is how three of them ended up with three different
 * scrim alphas, two different card heights and two different primary-button colours on
 * screens that are one gesture apart.
 *
 * <p><b>A Dialog, not a sibling view.</b> The mockup draws the modal inside the phone
 * over a peeked sheet, and that shape is exactly the one that stops working over a
 * {@code GLSurfaceView}: the transparent region is computed from layout positions, so a
 * sibling placed by a post-layout transform is attached, opaque, {@code isShown()},
 * receiving every touch and absent from the display (b/7179570, recorded in AOSP 2012
 * and still in the API 27 sources). A Dialog is its own window, so the hazard does not
 * apply — which is why every overlay in this app is one.
 *
 * <p>What that costs is the render behind it: a Dialog window is opaque unless asked
 * otherwise, so the studio does not show through. That is the correct trade — a
 * correctly compositing modal over an invisible photograph beats a visible photograph
 * behind a modal that never appears.
 *
 * <p><b>No Material Components.</b> {@code :app} deliberately depends only on
 * {@code :core}: no AndroidX, no Material, and no theme — which is why every
 * widget here is styled by hand. Material's {@code BottomSheetDialog} and
 * {@code SwitchCompat} are therefore not options, and adding the dependency to
 * get them would be a redesign, not a fix. The honest equivalents live here: the
 * platform {@link android.widget.Switch}, tinted with the app's own colours
 * ({@link #switchRow}), and the card's own grip, which drags the card down to
 * dismiss ({@link #grip}). One implementation, so the four screens built on this
 * card cannot drift into four different ones — which is how this class came to
 * exist.
 *
 * <p><b>Every sheet gets a grip. No exceptions.</b> {@link #grip} is the sheet's
 * discoverable way out, and ten of the twelve modals shipped without one: the
 * discoverable ways out were BACK, a drag on content that only hands over at a
 * specific edge, and a footer button that often <i>commits</i> rather than closes
 * (Generate, Save). A sheet with none of those obvious is a sheet a user cannot
 * get out of without knowing the platform. The user's answer to whether the small gallery popups should carry one too was that they should, so this is a rule and not a
 * per-screen decision &mdash; and it costs every one of them {@code dp(22)} of
 * chrome, which is why {@link #chromeHeight} adds the strip's height only when
 * there is one.
 *
 * <p><b>Each grip's description names its own sheet</b>, so a screen reader
 * announces "Close the delete question" rather than the same word on eight
 * screens. That is why {@link #grip} takes the label instead of hard-coding one.
 *
 * <p><b>Three row roles, and no fourth.</b> A row is one of three things, and
 * each has its own factory here: a {@link #switchRow} carries a state you can
 * flip, an {@link #actionRow} is a plate you can press (with a chevron when it
 * navigates), and a {@link #readingRow} is information and cannot be pressed at
 * all — its factory takes no listener. The rule is: <b>if it can be tapped it
 * carries an affordance — a switch, a plate or a chevron; if it carries none it
 * must not respond to touch.</b> The rule exists because the one factory this
 * replaces, {@code plainRow}, took an optional tap and therefore drew Reset and
 * "Basrelief 0.1.0" identically, so a destructive button and a version string
 * were one glance apart with nothing to tell them apart. Pressed states come
 * from the shared plate ({@link #plate}), never from a row that styles itself.
 *
 * <p><b>The card's height is a function of the card, never of the scroll.</b> A
 * modal opens at {@link #OPEN_FRACTION} of the screen and grows and shrinks <b>at
 * its edges</b>: pull past the top and it expands, pull past the bottom and it
 * collapses, and between the edges scrolling is reading and the height does not
 * move at all.
 *
 * <p><b>That last sentence is a bug fix, not a preference, and it must stay that
 * way.</b> The height was once {@code min(content, ceiling, open + scrollY)} — a
 * function of the scroll position — and it made every modal jitter while its content
 * scrolled. The loop: scrolling changed the viewport height, the height changed the
 * scroll range, the new range moved where the content actually was, and that fed
 * back into the height. The user's report was <i>scrolling inside the sheet made the content jitter up and down</i> and their diagnosis was exact: <i>the scroll
 * is also trying to expand the sheet; it shouldn't</i>. It passed review because every
 * <i>resting</i> bound was right — and a settled state is the one thing that cannot
 * be checked from a screenshot. <b>If you are reading this to make a modal taller as
 * it scrolls, that is the regression.</b> The whole rule is four small pure functions
 * ({@link #viewportMax}, {@link #viewportMin}, {@link #viewportAtRest},
 * {@link #viewportAfterOverScroll}) and one hook, {@link CappedScroll}'s
 * {@code overScrollBy}.
 *
 * <p><b>What the growing card costs</b>, because it is not free: every frame the
 * height is moving costs a measure and layout pass of the card's tree, which is why
 * the rule is applied in {@code onMeasure} rather than by setting a height after the
 * fact, and why a pull that has reached a stop costs nothing at all. What it buys is
 * the thing asked for on all four screens: a form can be read at a comfortable size
 * and then opened up, without scrolling ever moving the card out from under the
 * finger.
 *
 * <p><b>The backdrop is blurred where the platform can blur, and darker where it
 * cannot.</b> This card is a real Dialog window, so on API 31+ it can ask the
 * compositor to blur what is behind it — the studio's render, the wallpaper, whatever
 * app is under — with {@link Window#setBackgroundBlurRadius}, gated on
 * {@link #blurSupported(Context)}. <b>Being able to blur and being allowed to are two
 * different things</b>, and the second is why the window is built from the plain
 * non-fullscreen theme with {@code FLAG_FULLSCREEN} cleared: the blur region is composited
 * only for a translucent, <i>floating</i> window, and a fullscreen theme is neither. The
 * user reached the same conclusion independently from the other direction — at
 * {@code targetSdk 36} on Android 15+ an app is edge-to-edge already and
 * {@code FLAG_FULLSCREEN} is deprecated and ignored, so the theme name had become a
 * liability that could only subtract capabilities. The size is requested explicitly rather
 * than inherited, since a Dialog takes its layout params from the theme at attach time.
 * Below 31 there is no such call and some 31+ devices report
 * no cross-window blur support at all, in which case WMS draws nothing and leaves the
 * window background transparent. So the scrim carries the difference instead:
 * {@link #SCRIM} is the blurred value and {@link #darker} adds
 * nothing — see the {@link #SCRIM} note for the measurement that removed the second
 * value and left the scrim doing the work on its own. <b>None of this touches
 * the studio sheet</b> — it is a view beside the {@code GLSurfaceView} in the same
 * window, which cannot be blurred from inside itself without a per-frame screenshot.
 *
 * <p><b>The backdrop is the fourth dismissal, and a confirm opts out.</b> Tapping
 * outside the card closes it, through {@link #dismiss()} so it animates like the grip
 * and still runs {@link #onDismiss}. A destructive confirm does not: a tap on inert
 * dimming is very often a mis-aimed tap meant for the card, and losing the question
 * costs the user the whole read-and-decide. See {@link #setDismissOnScrimTap}.
 *
 * <p><b>The grip runs on the shared sheet component; the mechanism is injected.</b>
 * The drag, the entrance, the release rule and the fade live in
 * {@link BottomSheet}. This card is its translation host — safe here because a
 * Dialog owns its own window and contains no {@code SurfaceView} — while the
 * studio sheet is the layout-height host beside a {@code GLSurfaceView}
 * (b/7179570). It also asks for a different policy: a dialog is transient,
 * so {@link BottomSheet.Snap#TO_STOPS} makes every release land on open or gone,
 * never parked, and {@link com.amoshydra.basrelief.core.gesture.SheetIntent#DIALOG}
 * commits a collapse at 0.60. The persistent studio sheet is free to 0.95. Both
 * the policy and the mechanism are arguments, so the two cannot silently swap.
 */
final class ModalSheet {

    // The mockup's tokens. --scrim is rgba(0,0,0,.62); the depth screen deliberately
    // passes a lighter one, which is a difference in the design and not an accident.
    //
    // ONE scrim for every device, and it is now the whole backdrop mechanism rather
    // than a fallback for one: a Dialog cannot have its background blurred, so this
    // alpha IS the separation. .94 is opaque enough that nothing behind a modal
    // competes with it, and deliberately short of solid -- the design's objection to
    // a black rectangle still holds, just weakly, and at .94 the photograph is a
    // suggestion rather than an absence.
    static final int SCRIM = 0xF0000000;

    /**
     * {@code SCRIM_UNBLURRED_STEP}, {@link #SCRIM_UNBLURRED} and {@link #darker} were
     * deleted rather than left at zero.
     *
     * <p><b>Why the split existed and why it was wrong.</b> A scrim is drawn OVER
     * the window's blur, so the blur's contrast reaches the eye multiplied by
     * {@code 1 - scrimAlpha}. That is real, and it drove a light scrim on
     * blur-capable devices (.30) and a heavy one elsewhere (.85) on the theory that
     * the blur would then be visible. <b>It never was.</b> Measured on a Pixel 7 Pro
     * with a dialog open over a bright photograph: the call lands
     * ({@code blur=true sdk=37 radius=74}), the window is {@code fmt=TRANSLUCENT},
     * {@code fillxfill} and not {@code FLAG_FULLSCREEN}, the compositor has
     * {@code KawaseDualFilterV2} with {@code disableBackgroundBlur=false} — and
     * nothing appears. Two independent reasons, and neither is fixable from here:
     * AOSP composites a background blur only for a window that is translucent and
     * <i>floating</i>, and a {@link android.app.Dialog} is {@code ty=BASE_APPLICATION};
     * a floating window is {@code TYPE_APPLICATION_OVERLAY}, which needs
     * {@code SYSTEM_ALERT_WINDOW}, a permission a wallpaper studio should not hold.
     * And {@code dumpsys SurfaceFlinger} on sdk 37 prints no {@code blurRadius} field
     * at all — zero entries device-wide — so the shell cannot even confirm it.
     *
     * <p><b>So the scrim is the separation on every device</b>, which is what it was
     * before anyone mistook it for a progressive enhancement. The blur call is still
     * made, because it costs nothing and may land on an OEM that composites it, but
     * the design no longer depends on it.
     */

    static final int CARD = 0xFF171B21;
    static final int LINE = 0x24FFFFFF;
    static final int INK = 0xFFFFFFFF;
    static final int DIM = 0x8CFFFFFF;
    static final int DIMMER = 0x57FFFFFF;
    static final int ACCENT = 0xFF7FD1C1;
    /** The ink on an accent or white fill: the mockup's #08110f. */
    static final int INK_ON_FILL = 0xFF08110F;

    // The mockup's spacing tokens, named once and used by every widget below, so
    // a screen built on this card cannot carry a one-off gap. Each name is the
    // CSS rule it comes from in .design/prototype/final.html.
    /** {@code .opt{padding:12px}} — a row's own padding, all four sides. */
    static final int OPT_PAD_DP = 12;
    /** {@code .opt{display:flex;gap:12px}} — a row's mark to its text column. */
    static final int OPT_INNER_GAP_DP = 12;
    /** {@code .opt{margin-bottom:8px}} — the gap between two rows. */
    static final int OPT_GAP_DP = 8;
    /** {@code .caption{margin:16px 0 7px}} — above and below a group heading. */
    static final int CAPTION_TOP_DP = 16;
    static final int CAPTION_BOTTOM_DP = 7;
    /**
     * {@code .caption:first-child{margin-top:2px}} — the first caption sits right
     * under the grip. The mockup says 2 and this screen has always shipped 4;
     * this pass names values rather than tuning them.
     */
    static final int CAPTION_FIRST_TOP_DP = 4;
    /** {@code .note{margin-top:7px}} — an aside under the control it qualifies. */
    static final int NOTE_TOP_DP = 7;
    /** The {@code margin-top:14px} every mockup modal's button row carries. */
    static final int FOOTER_TOP_DP = 14;
    /** {@code .btnrow{gap:8px}} — between two buttons, and only between them. */
    static final int FOOTER_GAP_DP = 8;

    /**
     * How far the dialog's window blurs what is behind it, in dp.
     *
     * <p><b>Chosen for legibility, not for taste: at this radius text behind the card
     * is unreadable, which is the property that makes it a backdrop rather than a
     * decoration.</b> Words are made illegible by destroying the high-frequency detail
     * a letter is — its strokes' edges — and 25dp on a 2.8-density screen is past that
     * for the 12–14sp text this app's own rows and the studio's fix line use. Below
     * about 15dp a word stays legible and the modal reads as glass laid over
     * something you are being invited to read, which is the wrong invitation: the
     * photograph is not part of this screen.
     *
     * <p><b>25dp rather than more, deliberately.</b> The platform clamps a background
     * blur radius to the device's own maximum, so a larger number here is a request
     * some devices will quietly reduce — which would make this constant mean different
     * things on different phones. 25dp is a value devices accept as-is, so the
     * backdrop looks the same everywhere the blur exists at all. It is also the
     * strongest value that still lets the photograph read as a photograph.
     *
     * <p><b>Not the same property as the scrim's alpha.</b> This is a window
     * attribute, so it blurs the studio's GL surface and the wallpaper behind the
     * dialog as well as any app underneath; the scrim then darkens what comes
     * through. Both are needed and neither substitutes for the other, which is why
     * is called on devices that cannot use it; see {@link #SCRIM}.
     */
    private static final int BLUR_RADIUS_DP = 25;

    // The grip: .grip{height:22px} and its 38x4 bar, rgba(255,255,255,.42).
    private static final int GRIP_HEIGHT_DP = 22;
    private static final int GRIP_BAR_WIDTH_DP = 38;
    private static final int GRIP_BAR_HEIGHT_DP = 4;
    private static final int GRIP_BAR = 0x6BFFFFFF;

    // The platform switch, tinted with the app's own colours. On is the accent;
    // off is the hairline family. The off thumb takes --dim rather than --line,
    // because --line's 14% alpha leaves no visible thumb at all.
    private static final int SWITCH_TRACK_ON = 0x997FD1C1;
    private static final int SWITCH_TRACK_OFF = LINE;
    private static final int SWITCH_THUMB_ON = ACCENT;
    private static final int SWITCH_THUMB_OFF = DIM;

    /** The tallest a modal may be, as a fraction of the display. */
    private final float maxHeightFraction;

    /**
     * How much of the screen the scrolling part of a card occupies when the card
     * opens, before the user has scrolled anything.
     *
     * <p><b>A third, and the studio panel's own number.</b> Not because a modal is
     * the studio panel — it is not, and the two must not be confused — but because
     * both are the same decision: a sheet that spends the whole screen before the
     * user has read its first line has answered a question they had not asked. The
     * studio's javadoc says it for the render ("the render is the product; the
     * controls are an invitation"); for a modal the same words hold about the
     * title, the subtitle and the first control.
     *
     * <p><b>Why a card has to open below its content to scroll at all.</b> A card
     * exactly as tall as its content has a scroll range of zero, which is what three
     * of the four screens were doing and is the whole of "it does not work like the
     * settings sheet": measured on a 1080x2340 device, the apply chooser and the
     * playlists list were 836px and 937px tall with nothing to scroll, while the
     * settings sheet had 200px of travel inside a card that never moved. Opening
     * short is what gives a modal a scroll, and the growth below is what turns that
     * scroll into height.
     *
     * <p>It costs the first thing the user sees: every card now opens showing about
     * a third of the screen, so a long form's last rows need a scroll to reach where
     * they used to be visible without one. The footer (Done, Cancel/Generate) and
     * the grip are outside the scrolling part and so are visible at any height.
     */
    private static final float OPEN_FRACTION = 1f / 3f;

    /**
     * The floor under both height questions, in dp: a card is never given so little
     * scrolling part that the content in it is a sentence. See {@link #applyHeight},
     * which asks it twice — once for the opening height and once for the ceiling —
     * so that the two cannot disagree about what "too little" means.
     */
    private static final int MIN_CONTENT_DP = 120;

    private final LinearLayout card;
    private final CappedScroll scroll;
    private final LinearLayout footer;
    private Dialog dialog;

    /**
     * The grip strip, once {@link #grip} has installed one, so the height cap
     * can take the handle out of the scroll's budget. Null when the card has no
     * grip, which is a real state: the playlists list has no handle.
     */
    private View gripHandle;

    /**
     * The card's own sheet behaviour: grip drag, entrance, release intent and
     * fade. Null until {@link #grip} installs one; a card without a grip is a
     * card without that gesture.
     */
    private BottomSheet sheet;

    /** Run after the window goes away, however it went away. See {@link #onDismiss}. */
    private Runnable dismissAction;

    /**
     * The card's padding before the navigation-bar inset is folded into the
     * bottom, held so the inset listener can re-apply the whole set without
     * guessing which sides the inset touches.
     */
    private final int padLeft;
    private final int padTop;
    private final int padRight;
    private final int padBottom;

    /**
     * The bottom system-bar inset, in pixels, added to {@link #padBottom}. Zero
     * until the first inset dispatch, which is correct on a device with no bar.
     */
    private int bottomInset;

    /**
     * The scrim's own colour, held so the fade can drive its alpha without
     * reconstructing the colour: the drag tracks one progress value and the scrim
     * brightens (becomes less opaque) as the card comes down, which is the
     * background being revealed behind a modal that is leaving.
     */
    private final int scrim;

    /**
     * Whether a tap on the backdrop dismisses this modal. Set by the constructor and
     * changeable by {@link #setDismissOnScrimTap} before {@link #show}.
     */
    private boolean dismissOnScrimTap;

    /**
     * Whether this device can blur one window's content into another.
     *
     * <p><b>API 31 is where {@code Window.setBackgroundBlurRadius} exists at all</b> —
     * there is nothing to call below it, and nothing on 26–30 does this job: a window
     * can only be blurred by the compositor if the platform has a compositor path for
     * it, which is what arrived in Android 12. Below 31 the answer is a flat no and
     * the caller must not reflect around it.
     *
     * <p><b>And 31 does not promise the blur either.</b> Some devices report cross-
     * window blur disabled: the GPU cannot do it, a battery-saver mode switches it
     * off, or the platform is asked for minimal post-processing. The AOSP javadoc for
     * {@link Window#setBackgroundBlurRadius} is explicit that in those cases no blur
     * is computed or drawn and the window background is simply left transparent. So
     * this is a real capability question and not a version check dressed up as one —
     * which is exactly why the scrim is decided from the same answer, so a device that
     * says no gets the darker backdrop instead of a transparent gap.
     *
     * <p><b>Where this must not be used:</b> the studio sheet. It is a view over the
     * {@code GLSurfaceView} in the <i>same</i> window, and a window cannot be blurred
     * from inside itself without a per-frame screenshot, which is both a stall and the
     * thing AGENTS.md forbids as an appearance check. That is why this lives here, on
     * the Dialog, and why {@code BottomSheet} has no equivalent.
     */
    static boolean blurSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        // An INSTANCE method on the system WindowManager service, and the exact name
        // matters: it is isCrossWindowBlurEnabled, not isCrossWindowBlurSupported,
        // and there is no static form of either. Reaching it through the service is
        // also the only honest way to ask — the answer is a property of the device's
        // compositor, not of this app.
        android.view.WindowManager wm = (android.view.WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        // Null is a real answer on a device with no window service to ask, and it must
        // read as "no blur" rather than throwing inside a constructor.
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    /**
     * A modal on the standard scrim: {@code .scrim} at {@code rgba(0,0,0,.62)}, which
     * is what the mockup draws behind three of its four modals — or its much darker
     * counterpart on a device that cannot blur; see {@link #darker}.
     */
    ModalSheet(Activity activity, float maxHeightFraction) {
        this(activity, maxHeightFraction, SCRIM, true);
    }

    /**
     * A modal on a chosen scrim.
     *
     * @param scrim the colour behind the card, <b>as it looks with a blur behind
     *     it</b>. One screen in the design is drawn on a lighter one — the depth panel
     *     is a form, and .62 over a photograph is enough to make it hard to read — so
     *     this is a parameter rather than a constant, but only because the mockup says
     *     so. The no-blur counterpart is derived from it, not passed, so a caller
     *     cannot supply a value that is correct on one path and wrong on the other.
     */
    ModalSheet(Activity activity, float maxHeightFraction, int scrim) {
        this(activity, maxHeightFraction, scrim, true);
    }

    /**
     * A modal on a chosen scrim, saying whether a stray tap on the backdrop closes it.
     *
     * @param dismissOnScrimTap false for a question whose answer is destructive. A
     *     scrim tap is the least deliberate tap in the app — the finger lands on
     *     nothing, on a region that looks like inert backdrop — so it is the one that
     *     should do least. See {@link #setDismissOnScrimTap} for the callers and the
     *     full reasoning; this constructor exists so the choice is made at the call
     *     site where the modal's purpose is known, rather than inferred from whether
     *     anyone remembered.
     */
    ModalSheet(Activity activity, float maxHeightFraction, int scrim,
               boolean dismissOnScrimTap) {
        this.maxHeightFraction = maxHeightFraction;
        // Resolved ONCE, here, and stored — not asked again in show(). The scrim has to
        // be a single colour for the lifetime of the window because the drag fade
        // drives its alpha as a fraction of this value; a value that could change
        // under a running drag would make the fade non-deterministic. A device does
        // not gain or lose cross-window blur mid-window (battery saver can turn it off,
        // which WMS applies to new windows), and the dialog is short-lived regardless.
        this.scrim = scrim;
        this.dismissOnScrimTap = dismissOnScrimTap;

        FrameLayout scrimView = new FrameLayout(activity);
        scrimView.setBackgroundColor(this.scrim);

        // The card is a SheetCard, not a stock LinearLayout, because it is where
        // a vertical drag inside the scrolling content is arbitrated; see its
        // onInterceptTouchEvent. The listener carries the gesture after the card
        // has claimed it, the same pairing the studio's panel uses.
        card = new SheetCard(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        // A gesture that starts on the card's own area -- its padding, the gap a
        // row leaves -- must not fall through to whatever is beneath, and a
        // vertical one moves the card. What a child handles never arrives here;
        // the content drag is the SheetCard's intercept. `sheet` is installed by
        // grip() and the body runs at touch time, after it exists.
        card.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return sheet != null && sheet.onTouch(v, e);
            }
        });
        // The mockup's .modal edge: a top hairline only, over the fill's own top
        // corners. A GradientDrawable stroke would draw four edges, putting a 1px
        // bright line down both sides and along the foot of a card that is flush
        // with the screen's own edges -- the "bleeding" reported on the studio
        // sheet, and true of every surface built on this card.
        card.setBackground(new TopEdgeBackground(CARD, LINE, dp(activity, 20),
                dp(activity, 1)));
        // .modal{padding:18px 16px 24px}: 18 top, 16 sides, 24 foot. These were
        // rotated here -- 18 sides, 16 top and foot -- so the same card content
        // started 2dp further in than the playlists screen's, which reads the
        // stylesheet directly. One card, one padding, matching the CSS.
        padLeft = dp(activity, 16);
        padTop = dp(activity, 18);
        padRight = dp(activity, 16);
        padBottom = dp(activity, 24);
        card.setPadding(padLeft, padTop, padRight, padBottom);

        scroll = new CappedScroll(activity);
        // The mockup masks the last 22px; a fading edge is a child property, so this
        // is safe where a translated overlay would not be.
        scroll.setFadingEdgeLength(dp(activity, 22));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setClipToPadding(false);
        // WRAP_CONTENT, not the weight-1 column this used to install. A weighted
        // zero-height child of a wrap_content LinearLayout is remeasured to take
        // all the space its AT_MOST spec offers (LinearLayout.measureVertical,
        // targetSdk >= P), which made every short card full-screen and then
        // depended on applyHeight() to cut it back after layout. The ceiling is
        // the same idea done once, in onMeasure; see CappedScroll.
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // The card is a column: scroll, then footer. The footer is OUTSIDE the scroll
        // so the buttons stay reachable at any height -- the mockup puts them inside,
        // which is fine at 400x860 and not fine on a short screen with a long form.
        footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);

        scrimView.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // A tap on the backdrop is the dismissal gesture users reach for first, and
        // until now it did nothing at all. It lives on the ROOT, not the card, because
        // the root is the only view that covers the area outside the card.
        //
        // Which means the card must consume every touch that lands on IT, or a tap on
        // the card's own padding would fall through the LinearLayout (not clickable by
        // default) to this listener and close the modal the user just touched. The
        // card's own touch listener above only claims when a grip installed a sheet,
        // so a grip-less card — the apply chooser, every gallery modal — returned
        // false and leaked. setClickable is the one line that closes that: a clickable
        // View returns true from onTouchEvent, so the DOWN is consumed here and the
        // root never sees it. It adds no listener, so the click itself does nothing.
        card.setClickable(true);
        scrimView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // dismiss(), never dialog.cancel(). cancel() would also fire
                // onDismiss, but it takes the window down through the platform's own
                // path rather than this class's: no sheet.cancel(), so an entrance or
                // settle can still be running against a window that is going away, and
                // any future work in dismiss() is skipped. One dismissal path is the
                // whole point of the component.
                if (dismissOnScrimTap) dismiss();
            }
        });

        // The card is anchored to the bottom of the screen, so its footer would be
        // drawn under the gesture/navigation bar on a full-screen dialog window.
        // Fold the system's own bottom inset into the card's bottom padding: a
        // hard-coded dp is wrong on a device with no bar and wrong on one with a
        // tall one, and the inset is the only value that is right on both. The
        // Dialog window does not fit system windows (PhoneWindow clears the fit
        // sides for a non-floating window), so the insets are still delivered here.
        scrimView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                bottomInset = insetBottom(insets);
                card.setPadding(padLeft, padTop, padRight, padBottom + bottomInset);
                // The scroll's height is derived from the card, and the padding just
                // changed it, so re-cap. The inset is the gesture bar on one device
                // and zero on another, which is why both heights take it.
                applyHeight();
                return insets;
            }
        });

        root = scrimView;
    }

    private final FrameLayout root;

    /** Present. */
    void show(Activity activity) {
        // The root is built once, in the constructor, and kept in a field so the
        // card's children — the title, the rows, the fields a settings screen
        // rebuilds around — survive a dismiss. That only works if the window
        // attachment is severed when the window goes away, and dismissing a
        // Dialog does NOT detach its content view. Without this line the SECOND
        // show() re-adds a view that still has a parent and throws
        // "The specified child already has a parent" out of
        // ViewGroup.addViewInner via Dialog.setContentView. The Depth AI panel
        // is the live path: it is built once and reopened, so the second open
        // crashed. Detach rather than build a fresh root per show, because a
        // fresh root would discard every child the constructor and body()/
        // footerRow() added — the panel's blocks and the settings toggles carry
        // state, and rebuilding them per show is the "second place that has to
        // agree" this class exists to remove.
        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        dialog = new Dialog(activity,
                android.R.style.Theme_DeviceDefault_NoActionBar);
        // A window blur is composited only for a TRANSLUCENT, FLOATING window, and
        // "Fullscreen" in that theme name is a request for the one thing that
        // disqualifies it. So the theme is the plain one, the flag is cleared
        // explicitly rather than assumed absent, and the size is requested: a
        // Dialog takes its layout params from the theme at attach time, so
        // dropping "Fullscreen" without setLayout would shrink-wrap the card to
        // its content and leave the scrim covering only the card.
        //
        // The user's point is the better one and it is why the theme goes at all:
        // at targetSdk 36 on Android 15+ an app is edge-to-edge already and
        // FLAG_FULLSCREEN is deprecated and ignored, so this could only ever
        // subtract a capability.
        //
        // NOTE: this was not why the blur was invisible -- the scrim was. See
        // SCRIM for the measurement that decided the design.
        dialog.getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dialog.getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        // A BACKDROP BLUR IS COMPOSITED ONLY FOR A TRANSLUCENT, **FLOATING** WINDOW, and
        // "floating" is a geometric test, not a flag: the window has to be SMALLER than
        // the display. This was MATCH_PARENT x MATCH_PARENT, which fills the screen and is
        // therefore not floating, so setBackgroundBlurRadius was accepted and never drawn
        // -- the exact symptom the user reported from a Pixel that reports
        // mBlurEnabled=true. A one-pixel inset in both axes is enough to satisfy it
        // without moving the scrim, which is drawn by our own root anyway.
        // The window fills the display, and that is now a MEASURED decision rather than
        // an oversight. It was briefly made 4px smaller, on the theory that "floating" is
        // a geometric test (a window smaller than the display); that did nothing, because
        // setContentView's MATCH_PARENT params override setLayout and the window still
        // requested 1440x3120. It is removed rather than repaired, because it could not
        // have worked anyway and would have cost six modals a 4px shift:
        //
        //   MEASURED, with a modal open on a Pixel 7 Pro: dumpsys SurfaceFlinger prints
        //   ZERO blurRadius entries device-wide, while `disableBackgroundBlur=false` and
        //   `RenderEngine chosen blur algorithm: KawaseDualFilterV2` are both set. The
        //   window asks for a 25dp blur -- blurSupported() is true and the call is made
        //   below -- and the compositor declines to produce a region for it.
        //
        // AOSP's rule is that the region is composited only for a translucent, FLOATING
        // window, and a Dialog is ty=BASE_APPLICATION. That is not a radius, a fill or a
        // size, which is why three earlier passes of lowering the pill's opacity changed
        // nothing. A backdrop blur on this app's modals would need a
        // TYPE_APPLICATION_OVERLAY window and therefore SYSTEM_ALERT_WINDOW -- a
        // permission a wallpaper studio should not hold for a sheet background. The blur
        // call is kept because it is free and may land on an OEM that composites it; the
        // design does not depend on it.
        dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A card dismissed from a half-finished grip drag keeps the translation it
        // was showing, and this instance is reused (see the top of show()), so the
        // next open must not start half way off the screen. Cancel the component's
        // settle and entrance first: a running one would otherwise animate from
        // under the reset and run its end action into the freshly opened dialog.
        if (sheet != null) sheet.cancel();
        card.animate().cancel();
        card.setTranslationY(0f);
        card.setAlpha(1f);
        // The heights before the window is up, not in the post below. The card's
        // height is now the opening height rather than the ceiling, so a first frame
        // drawn without them would be a frame of the whole content -- for a long form
        // that is a card twice the height it settles at, drawn behind the entrance
        // and then snapped away.
        applyHeight();
        // A reused instance (the Depth AI panel is built once and reopened) reopens
        // at the opening height rather than wherever the last user left it. The
        // content's scroll position is kept on purpose; the height is not, and the
        // two are no longer the same thing.
        scroll.resetToOpen();
        // The fade is per child; a card dismissed mid-drag leaves them at the
        // alpha that drag reached, and the next open must not inherit it.
        for (int i = 0; i < card.getChildCount(); i++) {
            card.getChildAt(i).setAlpha(1f);
        }
        // The scrim too: a card dismissed mid-drag leaves it brightened, and the
        // next open must not inherit a half-revealed backdrop for the frame before
        // the component's first fade callback arrives.
        android.graphics.drawable.Drawable scrimDrawable = root.getBackground();
        if (scrimDrawable != null) scrimDrawable.setAlpha(Color.alpha(scrim));
        // ADJUST_RESIZE, because the depth panel's first field is an endpoint the user
        // has to be able to type. Without it the keyboard covers the box being typed
        // into and the dialog does not move -- which reads as the field being dead
        // rather than as an IME problem. The window must ask BEFORE show(), which is
        // why this is here and not after.
        dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Blur what is behind this window, where the platform can. It is a property of
        // the WINDOW, which is why this works at all: the studio behind the card is a
        // GLSurfaceView in another window, and the compositor is what reaches across
        // and softens it. Set before show() because the radius is read when the
        // surface is created; setting it afterwards leaves the window unblurred until
        // something else triggers a relayout, which is the kind of silence that looks
        // like the API not working.
        //
        // Without the guard this is a no-op on two kinds of device and a bug on one:
        // below API 31 the method does not exist, and on 31+ WMS draws no blur and
        // leaves the background transparent when the device reports no support — so the
        // unblurred look has to come from the scrim, which is why scrim was resolved
        // from the same answer in the constructor.
        boolean blur = blurSupported(activity);
        // Which branch actually ran, because "no blur is visible" is a symptom with
        // at least three causes and they need different fixes: the call never made
        // (capability), the call made and dropped (window shape), or the call made,
        // honoured and buried under our own scrim. Logged rather than guessed.
        android.util.Log.i("Basrelief", "ModalSheet blur=" + blur + " sdk="
                + android.os.Build.VERSION.SDK_INT + " radius="
                + (blur ? dp(activity, BLUR_RADIUS_DP) : 0) + " scrim=" + Integer.toHexString(scrim));
        if (blur) {
            dialog.getWindow().setBackgroundBlurRadius(dp(activity, BLUR_RADIUS_DP));
        }
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                dialog = null;
                if (dismissAction != null) dismissAction.run();
            }
        });
        // A modal you cannot leave with the system back gesture is a trap. See
        // dismissOnBack for why this is more than a key listener on targetSdk 36.
        dismissOnBack(dialog, new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });
        dialog.show();
        // Now that the window is attached, ask for the first inset dispatch so the
        // navigation-bar inset is known before the card settles. Without this the
        // first frame can draw the footer under the bar until the next pass.
        root.requestApplyInsets();
        root.post(new Runnable() {
            @Override
            public void run() {
                applyHeight();
            }
        });
        // The entrance needs a measured card, and a dialog's content is laid out
        // on a later traversal than show(); see the retry.
        springCardFromBelow();
    }

    /**
     * Spring the card up from below, once it has a measured height.
     *
     * <p>{@link #show} can run before the dialog's first traversal, and a card
     * that has not been laid out has a span of zero — the entrance would silently
     * be a jump. A self-retrying post is enough because the traversal is
     * guaranteed (the window is already being shown) and the retry stops the
     * moment there is a height. This is the same shape as the studio sheet's
     * {@code openAtPeek} deferral.
     */
    private void springCardFromBelow() {
        if (sheet == null) return;
        if (card.getHeight() <= 0) {
            card.post(new Runnable() {
                @Override
                public void run() {
                    springCardFromBelow();
                }
            });
            return;
        }
        sheet.open(0f);
    }

    /**
     * Add the mockup's {@code .grip} at the top of the card, above the scroll, and
     * make dragging it move the card down and dismiss it.
     *
     * <p><b>Why the grip is not in the scroll.</b> The mockup draws it as a sibling
     * above {@code .scroll} (final.html's {@code sheet()}), so it stays reachable
     * however far the content is scrolled, and a drag on it can never dispute a
     * vertical drag with the inner scroll — the two do not overlap. It used to be
     * the caller's first body child, which scrolled away with the content it was
     * supposed to close.
     *
     * <p><b>Why a transform is safe here, when the studio sheet may not use one.</b>
     * The b/7179570 hazard recorded in AGENTS.md is a view in the <i>same window</i>
     * as the {@code GLSurfaceView}: the surface's transparent region is computed from
     * layout positions, so a sibling positioned by a post-layout transform is
     * attached, opaque, {@code isShown()} and absent from the display. This card
     * lives in a Dialog, which owns its own window and contains no SurfaceView; the
     * studio's GL surface is in a different window with a different surface, so
     * nothing about its transparent region can desync. That is the same distinction
     * that makes this whole class a Dialog instead of a sibling view, and it is why
     * the studio sheet — which IS the GLSurfaceView's sibling — still drags by
     * layout height and must keep doing so.
     *
     * @param contentDescription what the handle does, for a screen reader. The bar
     *                           itself is undrawn text.
     */
    void grip(CharSequence contentDescription) {
        final Context context = root.getContext();
        final int slop = ViewConfiguration.get(context).getScaledTouchSlop();

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(Gravity.CENTER);
        strip.setContentDescription(contentDescription);
        strip.setClickable(true);

        View bar = new View(context);
        bar.setBackgroundColor(GRIP_BAR);
        strip.addView(bar, new LinearLayout.LayoutParams(
                dp(context, GRIP_BAR_WIDTH_DP), dp(context, GRIP_BAR_HEIGHT_DP)));

        gripHandle = strip;
        // A tap has always closed this sheet, and it is also the accessibility
        // action; a drag past the threshold closes it too. Both gestures go the
        // same way, so the handle does not advertise one thing and do another.
        strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        // The card is the component's translation host: a Dialog owns its own
        // window and contains no SurfaceView, so moving by translationY cannot
        // desync a transparent region the way it does for the studio sheet
        // (b/7179570). The tap action duplicates the click listener above because
        // the drag consumes every event; the click listener remains for the
        // accessibility action, which arrives as a performClick and not as touches.
        sheet = new BottomSheet(new BottomSheet.Host() {
            @Override
            public float spanPx() {
                return card.getHeight();
            }

            @Override
            public void applyPosition(float fraction, float belowPx) {
                card.setTranslationY(fraction * card.getHeight() + belowPx);
            }

            @Override
            public void applyFade(float progress) {
                // Content only: the user's correction was to fade the content rather than the sheet. The card's background is
                // the sheet surface here, so it keeps its constant alpha and each
                // child carries the fade; card.setAlpha would have taken the
                // background with it. The scrim is the surface that follows,
                // tracking the same progress so it brightens — becomes less
                // opaque — as the card comes down and the background is revealed
                // behind it. The drawable's own alpha is set rather than a fresh
                // background colour, because this runs on every frame of a drag
                // and setBackgroundColor would allocate a ColorDrawable per frame.
                float content = BottomSheet.contentAlpha(progress);
                for (int i = 0; i < card.getChildCount(); i++) {
                    card.getChildAt(i).setAlpha(content);
                }
                android.graphics.drawable.Drawable scrimDrawable = root.getBackground();
                if (scrimDrawable != null) {
                    int base = Color.alpha(scrim);
                    scrimDrawable.setAlpha(Math.round(base * (1f - progress)));
                }
            }

            @Override
            public View scrollView() {
                return scroll;
            }

            @Override
            public void onResting(float fraction, boolean collapsed) {
                if (collapsed) dismiss();
            }
        }, SpringSettle.CARD, SheetIntent.DIALOG, BottomSheet.Snap.TO_STOPS, slop,
                new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });
        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // The grip is a control of the card's own, so the content
                // arbitration stands down for the whole gesture rather than
                // deciding the same drag a second time; the same pairing the
                // studio's grip uses with its panel.
                boolean end = e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                card.requestDisallowInterceptTouchEvent(!end);
                return sheet.onTouch(v, e);
            }
        });
        card.addView(strip, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, GRIP_HEIGHT_DP)));
    }

    /**
     * Give the scrolling part the two heights it is allowed to take: where it opens,
     * and the tallest it may become.
     *
     * <p><b>Two numbers and nothing else.</b> The card used to open at the ceiling and
     * scroll inside it, which left three of the four screens with nothing to scroll at
     * all, and the first repair made the height a function of the scroll position,
     * which jittered ({@link CappedScroll} records that loop). This is the settled
     * shape: the card opens at {@link #OPEN_FRACTION}, grows and shrinks at its
     * edges, and the arithmetic in between is {@link #viewportAtRest}. Nothing here
     * reads the scroll.
     *
     * <p><b>Derived, never measured.</b> The ceiling is the fraction of the screen
     * less the card's own chrome, and the chrome is derived from the card's padding
     * tokens and the footer's real measured height; measuring the card cannot work,
     * because the card's height is the thing being decided. That is also why the
     * opening height is not clamped against the ceiling here: the scroll's own
     * {@link #viewportMin} against the content does it, and a card shorter than the
     * opening height is a card that shrink-wraps.
     *
     * <p>It runs again whenever an answer can have changed: the first layout, an
     * inset dispatch, a footer, a body swap — and once per {@link #show()}, before
     * the window is up, so the first frame is drawn at the opening height instead of
     * at the whole content and then snapping (see {@link #screenHeightPx}).
     */
    private void applyHeight() {
        int screen = screenHeightPx();
        if (screen <= 0) return;
        // The inset is inside the card's padding, so it is not height the card may
        // use: subtract it from the ceiling and the card still fits the fraction. The
        // opening height takes it too, or a card on a gesture-bar device would open
        // with its last row under the bar.
        int ceiling = (int) (screen * maxHeightFraction) - bottomInset;
        int maxHeight = ceiling - chromeHeight();
        if (maxHeight < dp(root.getContext(), MIN_CONTENT_DP)) {
            // A screen so short that the chrome alone eats the fraction: prefer a
            // scrollable card that overflows the fraction to one with no content
            // in it. Same floor the playlists editor used, and the reason it had
            // to exist is this branch.
            maxHeight = (int) (screen * maxHeightFraction);
        }
        int openHeight = Math.round(screen * OPEN_FRACTION) - bottomInset;
        // A screen where a third is less than the floor gets the floor rather than
        // a card with nothing in it. This is the same 120dp the ceiling branch
        // above uses, deliberately: one floor, two questions.
        if (openHeight < dp(root.getContext(), MIN_CONTENT_DP)) {
            openHeight = dp(root.getContext(), MIN_CONTENT_DP);
        }
        if (openHeight > maxHeight) openHeight = maxHeight;
        scroll.setBounds(openHeight, maxHeight);
    }

    /**
     * The height the card may spend, in pixels.
     *
     * <p>The root's own measured height, which is the truth — except before the
     * first traversal, where it is zero. The dialog's window is MATCH_PARENT on a
     * fullscreen theme, so the display height is the same number and is available
     * immediately; without this fallback the first frame of a long form would be
     * drawn at the card's whole content height and then snap down to its opening
     * height, which is a whole card rather than a few pixels. The posted
     * {@link #applyHeight()} after the first layout replaces it with the measured
     * value, so the fallback is a first-frame answer and not the resting one.
     */
    private int screenHeightPx() {
        int measured = root.getHeight();
        if (measured > 0) return measured;
        return root.getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * The tallest the scrolling part may be: never more than the content, never more
     * than the ceiling.
     *
     * <p>The content's own height is the shrink-wrap rule — a card is never taller
     * than what is in it — and it is why a two-option modal is still a two-option
     * modal after all of this.
     */
    static int viewportMax(int naturalPx, int ceilingPx) {
        int top = naturalPx < ceilingPx ? naturalPx : ceilingPx;
        return top > 0 ? top : 0;
    }

    /**
     * The shortest the scrolling part may be: the content, when the content is
     * shorter than {@link #OPEN_FRACTION}, and otherwise the opening height.
     *
     * <p>Where the two are the same — a card with no scroll range — this is the
     * shrink-wrap case, and {@link #viewportMax} returning the same number is what
     * makes such a card unable to grow at all.
     */
    static int viewportMin(int naturalPx, int openPx, int ceilingPx) {
        int top = viewportMax(naturalPx, ceilingPx);
        return top < openPx ? top : openPx;
    }

    /**
     * The height at rest: whatever the card is showing now, clamped into
     * [{@link #viewportMin}, {@link #viewportMax}].
     *
     * <p><b>This is the whole bug in one line.</b> There is no scroll position in
     * this signature, and there must not be one. The previous version was
     * {@code min(content, ceiling, open + scrollY)}: scrolling resized the card,
     * resizing the card changed the scroll range, the new range changed where the
     * content actually was, and that fed straight back into the height. The user
     * saw the loop as *scrolling inside the sheet made the content jitter up and down* and diagnosed it exactly: <i>the scroll is also trying to expand the
     * sheet; it shouldn't</i>.
     *
     * <p>So the height is a function of <b>how big the card currently is</b>, and
     * nothing else. Scrolling changes it only through
     * {@link #viewportAfterOverScroll}, which is a separate, deliberate, edge-only
     * path. <b>If you are here to make a modal taller as it is scrolled, do not —
     * that is the regression this replaced, and it is the one that jitters.</b>
     *
     * @param currentPx the height the card is showing; negative means "not yet
     *                  known", which the caller resolves to the opening height
     */
    static int viewportAtRest(int naturalPx, int openPx, int ceilingPx, int currentPx) {
        int min = viewportMin(naturalPx, openPx, ceilingPx);
        int max = viewportMax(naturalPx, ceilingPx);
        int target = currentPx;
        if (target < min) target = min;
        if (target > max) target = max;
        return target;
    }

    /**
     * The height after an over-scroll the content could not consume: the card follows
     * the finger by exactly that much, in the direction the finger was already going.
     *
     * <p><b>The sign convention, which is the easy thing to get backwards.</b>
     * {@code ScrollView} computes its delta as {@code lastY - y}, so a
     * <b>positive</b> delta is the finger moving <b>up</b> and a negative one is the
     * finger moving <b>down</b>. A finger moving up at the bottom edge has nothing
     * left to reveal, so the leftover goes to the card and the card <b>grows</b> —
     * which is what "expand when there is nothing more to scroll up" means to the
     * person doing it, and what every bottom sheet does. A finger moving down at the
     * top edge has nothing left to un-reveal, and the card <b>shrinks</b>. Both are
     * the same addition, because the card simply continues the motion the content
     * could not finish — and because its foot is pinned to the bottom of the screen,
     * continuing upward can only mean growing upward.
     *
     * <p><b>Which is what makes it monotone.</b> {@code current + unconsumed} moves
     * the height one way for the whole gesture, and {@link #viewportAtRest} clamps it
     * into [{@link #viewportMin}, {@link #viewportMax}], so a long pull cannot
     * overshoot and a reversal cannot walk it back past a stop: once the card is at
     * {@link #viewportMax} every further pull answers the same number. Growth and
     * shrink therefore stop at a stop rather than springing back, and neither can
     * oscillate — the property the jitter was.
     *
     * <p><b>Why it settles where the gesture ends.</b> A dialog is transient
     * ({@link BottomSheet.Snap#TO_STOPS}) and the user is reading, not parking: the
     * card resting at whatever height they pulled it to is the least surprising
     * answer, and it is also the only one that needs no second animation to undo the
     * first. The full-height stop is the ceiling; the smallest is the opening height.
     *
     * @param unconsumedYPx the edge delta the content could not scroll, in pixels,
     *                      in {@code ScrollView}'s own sign: positive is the finger
     *                      moving up. Only ever the <i>unconsumed</i> part — a delta
     *                      the content did scroll is reading, not resizing, and
     *                      {@link CappedScroll} checks the edge before it gets here.
     */
    static int viewportAfterOverScroll(int naturalPx, int openPx, int ceilingPx,
                                       int currentPx, int unconsumedYPx) {
        return viewportAtRest(naturalPx, openPx, ceilingPx, currentPx + unconsumedYPx);
    }

    /**
     * Everything in the card that is not the scroll: its padding, the grip when
     * one is installed, and the footer with its top gap.
     *
     * <p>All measured values, not re-derived dp: the card's padding already has
     * the navigation-bar inset folded in, and the footer is a real view whose
     * height the mockup's buttons define.
     */
    private int chromeHeight() {
        int chrome = card.getPaddingTop() + card.getPaddingBottom();
        // The grip's height is its fixed layout token, not its measured height:
        // the first inset dispatch can arrive before the strip has been laid out,
        // and a zero there would hand the scroll a ceiling it does not have.
        if (gripHandle != null) chrome += dp(root.getContext(), GRIP_HEIGHT_DP);
        if (footer.getVisibility() != View.GONE) {
            chrome += footer.getMeasuredHeight();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) footer.getLayoutParams();
            if (lp != null) chrome += lp.topMargin;
        }
        return chrome;
    }

    /**
     * Add or replace the content in the scrolling part.
     *
     * <p><b>Replace, not append.</b> The playlists screen is one window with two
     * states — list and editor — and swaps between them in place, so that a tap
     * is a rebuild rather than a second dialog and a flicker. Calling this twice
     * used to throw "child already has a parent" out of the second
     * {@code card.addView(scroll)}; replacing the scroll's children is also the
     * only shape that can work, because a state has content where the previous
     * state had different content, not in addition to it.
     */
    void body(View view) {
        if (scroll.getParent() == null) card.addView(scroll);
        scroll.removeAllViews();
        scroll.addView(view, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        applyHeight();
    }

    /**
     * Add or replace the row of buttons that stays put. Equal weights; two is
     * the usual number.
     *
     * <p>Replace, for the same reason {@link #body} replaces: the playlists
     * editor's footer is Delete/Done where the list's is New/Done, and appending
     * would leave New behind the editor's pair. The 8dp gutter is
     * {@code .btnrow{gap:8px}} and lives here once, rather than as a right margin
     * on a button that each screen has to remember.
     */
    void footerRow(Button... buttons) {
        footer.removeAllViews();
        footer.setVisibility(buttons.length == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) b.leftMargin = dp(root.getContext(), FOOTER_GAP_DP);
            footer.addView(buttons[i], b);
        }
        if (buttons.length > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(root.getContext(), FOOTER_TOP_DP);
            footer.setLayoutParams(lp);
            if (footer.getParent() == null) card.addView(footer);
        }
        applyHeight();
    }

    /**
     * Whether a tap on the backdrop dismisses this modal. True unless a caller says
     * otherwise.
     *
     * <p><b>The decision, and why a destructive confirm is the exception.</b> A
     * backdrop tap is the least deliberate gesture in the app: the finger lands on a
     * region that looks like inert dimming, with nothing under it to aim at and no
     * highlight to confirm against. That is exactly why it is a good dismissal for a
     * modal that is only <i>displaying</i> something — settings, the depth form, the
     * apply chooser, a picker, where closing loses nothing and the gesture the user
     * meant is the one they made.
     *
     * <p>It is a poor dismissal for a modal whose whole content is a question whose
     * answer destroys something: "Delete this photo and its file?", "Reset
     * everything?". There, a backdrop tap is very often a <i>mis-aimed</i> tap meant
     * for the card — the thumb slid off the top of a short card, or the user reached
     * for a row they could not see was covered. Reading such a tap as "no" is safe,
     * but reading it as "dismiss the question" means the user has to find the
     * question again, re-read it, and re-open it — and on a short confirm card the
     * region above the card is large and inviting, so this is not a rare mis-aim.
     * A destructive confirm therefore ignores backdrop taps and waits for Cancel, the
     * back gesture, or the destructive button itself.
     *
     * <p><b>Deliberate, not an omission.</b> The flag defaults to true and the three
     * confirms pass false, so the safe behaviour is a stated decision at each call
     * site rather than something that happens because nobody added a listener. A new
     * destructive question has to make the choice, which is the point.
     *
     * <p>Call before {@link #show}; it is read by the click listener, which is
     * installed in the constructor.
     */
    void setDismissOnScrimTap(boolean dismiss) {
        this.dismissOnScrimTap = dismiss;
    }

    void dismiss() {
        // Stop the card's own motion first: a dismissed dialog must not leave an
        // entrance or settle running against a window that no longer exists, and
        // the grip's dismissal arrives through onResting while the settle's end
        // callback is still on the stack — cancel() is a no-op then, because the
        // component has already cleared its animator before resting.
        if (sheet != null) sheet.cancel();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    /**
     * Run {@code action} after the window goes away, however it went away.
     *
     * <p><b>Why a hook and not a {@code dismiss()} override.</b> The grip, the
     * system back gesture and the playlists screen's Done button all end the
     * dialog by different routes, and three of them never call the screen's own
     * {@code dismiss()}. A caller with work to finish — the playlists editor has
     * a name field to commit — attaches it here once, so it runs on the grip tap
     * and the back press as well as on its own Done. The old private dialogs
     * carried this callback because they owned the {@link Dialog}; the callers
     * migrated onto this class still need it, so it is a capability of the class
     * rather than a second window.
     */
    void onDismiss(Runnable action) {
        dismissAction = action;
    }

    /**
     * Make system BACK dismiss {@code dialog} and never let the key reach the
     * Activity, which would finish the app.
     *
     * <p><b>Why this is not just a key listener.</b> The app targets SDK 36, and
     * from Android 16 a back press is no longer delivered as
     * {@link KeyEvent#KEYCODE_BACK} — it arrives as an
     * {@link OnBackInvokedCallback}. The {@code OnKeyListener} below is therefore
     * dead on a modern device; it is kept because it is the whole story on API 26
     * through 32, where that API does not exist. The two branches are exact
     * complements: {@code dismissOnBack} covers every API the app runs on.
     *
     * <p><b>Why it is explicit.</b> A {@link Dialog} does register a default back
     * callback of its own, so a cancelable dialog happens to dismiss on back even
     * without this. But that default is conditional on the manifest opt-in and on
     * the API level, and inheriting it means the behaviour cannot be expressed where
     * it matters. The manifest sets
     * {@code android:enableOnBackInvokedCallback}, so the callback branch is live
     * on 33+ and the key branch on 26–32.
     *
     * <p><b>No manual unregister.</b> The callback is registered on the dialog's
     * own window dispatcher, which is cleared when the window is destroyed — and
     * every path out of these dialogs destroys it. Registering before
     * {@link Dialog#show()} is safe: the window's {@code ProxyOnBackInvokedDispatcher}
     * buffers callbacks until the window attaches.
     *
     * @param dialog the dialog that must not let back through
     * @param dismissAction what dismissing means to the caller — usually its own
     *                      {@code dismiss()}, so a nulled-out holder and its cleanup
     *                      still run
     */
    static void dismissOnBack(final Dialog dialog, final Runnable dismissAction) {
        dialog.setCancelable(true);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dismissAction.run();
                    return true;
                }
                return false;
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            dismissAction.run();
                        }
                    });
        }
    }

    /**
     * The bottom system-bar inset, in pixels, or 0.
     *
     * <p>{@code systemBars()} names the bar on API 30+; below that the deprecated
     * single bottom inset is the only source. minSdk is 26, so both branches are
     * live and neither is a guess. Shared with every other full-screen dialog in the
     * app, so the four of them cannot drift to four different values.
     */
    static int insetBottom(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    // ------------------------------------------------------------------ widgets
    // The mockup's .btn family, so a modal cannot invent its own button.

    /**
     * The plate under every tappable thing: {@code .btn}'s rounded rect, with a
     * pressed state.
     *
     * <p><b>One helper, because a pressed state is feedback and not decoration.</b>
     * A custom background replaces the platform's button drawable, which is also
     * what supplies the touch feedback; without a state list here a tap on any
     * plate in the app produced no visible response at all. Building the plate
     * once means the next control that wants one cannot skip the pressed colour.
     *
     * @param primary white fill, near-black ink
     * @param ghost transparent fill; a filled ghost is {@code .btn}'s default
     */
    private static GradientDrawable plate(Activity activity, boolean primary, boolean ghost) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 11));
        int[][] states = { new int[]{android.R.attr.state_pressed}, new int[]{} };
        if (primary) {
            // .btn.primary is a WHITE fill with near-black text. Accent is the app's
            // highlight colour, not this button's: using it here made Cancel/Continue
            // disagree with the identical pair in the playlists modal.
            bg.setColor(new ColorStateList(states, new int[]{ 0xFFE2E2E2, INK }));
        } else {
            bg.setColor(new ColorStateList(states,
                    new int[]{ 0x2EFFFFFF, ghost ? Color.TRANSPARENT : 0x1AFFFFFF }));
            bg.setStroke(dp(activity, 1), LINE);
        }
        return bg;
    }

    /** {@code .btn} — a filled plate. {@code .btn.ghost} is the transparent one. */
    static Button button(Activity activity, String label, boolean primary, boolean ghost) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(activity, 49));
        b.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        b.setBackground(plate(activity, primary, ghost));
        b.setTextColor(primary ? INK_ON_FILL : INK);
        return b;
    }

    /** {@code .modal h3} */
    static TextView title(Activity activity, String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** {@code .modal .sub} — 13px, --dim, 14px of space under it. */
    static TextView subtitle(Activity activity, String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(DIM);
        t.setPadding(0, dp(activity, 4), 0, dp(activity, 14));
        return t;
    }

    /** {@code .caption} — 11px, caps, .09em, --dimmer, 16 above and 7 below. */
    static TextView caption(Activity activity, String text, boolean first) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(DIMMER);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // No side padding: the stylesheet's .caption has none, and the 2dp this
        // used to carry put every caption 2dp right of the rows and fields under
        // it, which are laid out at the card's own edge.
        t.setPadding(0, first ? dp(activity, CAPTION_FIRST_TOP_DP)
                : dp(activity, CAPTION_TOP_DP), 0, dp(activity, CAPTION_BOTTOM_DP));
        return t;
    }

    /** {@code .note} — 11px, --dimmer, centred. */
    static TextView note(Activity activity, CharSequence text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(DIMMER);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(activity, NOTE_TOP_DP), 0, 0);
        return t;
    }

    /** The accent corner dot, as a drawable, so a row can recolour it in place. */
    private static GradientDrawable dot(Activity activity, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? ACCENT : Color.TRANSPARENT);
        d.setStroke(dp(activity, 2), CARD);
        return d;
    }

    /**
     * {@code .opt .sw} — the 34dp tile with a 14dp accent dot on its bottom-right,
     * ringed in the card's own colour so the dot reads as sitting ON the tile.
     *
     * @param mark the dot's own drawable, held by the caller so the state can be
     *             changed later without rebuilding the row
     */
    private static FrameLayout swatch(Activity activity, GradientDrawable mark,
                                      View.OnClickListener tap) {
        FrameLayout holder = new FrameLayout(activity);
        GradientDrawable tile = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF3D5563, 0xFF1B262E});
        tile.setCornerRadius(dp(activity, 9));
        holder.setBackground(tile);

        View dotView = new View(activity);
        dotView.setBackground(mark);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                dp(activity, 14), dp(activity, 14), Gravity.BOTTOM | Gravity.RIGHT);
        dotLp.leftMargin = -dp(activity, 3);
        dotLp.bottomMargin = -dp(activity, 3);
        holder.addView(dotView, dotLp);

        if (tap != null) holder.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(activity, 34), dp(activity, 34));
        lp.rightMargin = dp(activity, OPT_INNER_GAP_DP);
        holder.setLayoutParams(lp);
        return holder;
    }

    /** The bordered row shell, applied to a row that has already chosen its type. */
    private static void frameRow(Activity activity, LinearLayout row) {
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, OPT_PAD_DP), dp(activity, OPT_PAD_DP),
                dp(activity, OPT_PAD_DP), dp(activity, OPT_PAD_DP));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(activity, 11));
        bg.setStroke(dp(activity, 1), LINE);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(activity, OPT_GAP_DP);
        row.setLayoutParams(lp);
    }

    /** {@code .opt b} over {@code .opt small}: the two-line text column. */
    private static LinearLayout rowText(Activity activity, String titleText, String subText) {
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView b = new TextView(activity);
        b.setText(titleText);
        b.setTextSize(14);
        b.setTextColor(INK);
        text.addView(b);
        TextView small = new TextView(activity);
        small.setText(subText);
        small.setTextSize(12);
        small.setTextColor(DIM);
        text.addView(small);
        return text;
    }

    /**
     * A picker row whose mark can be turned on and off after it is built.
     *
     * <p>The apply chooser is why this is a type and not a bare {@code LinearLayout}:
     * its two options are selections that change while the sheet is open, and the
     * mark is the only thing that says which. Rebuilding the row per tap would work
     * and would also lose the row it is replacing; the drawable the tile already
     * holds is the state.
     */
    static final class OptionRow extends LinearLayout {

        private final GradientDrawable mark;

        OptionRow(Activity activity, GradientDrawable mark) {
            super(activity);
            this.mark = mark;
        }

        /**
         * On shows the accent dot; off is transparent, like {@code .sw} without
         * {@code .on}.
         *
         * <p>Not named {@code setSelected}: that is {@link View}'s own method for
         * the accessibility state, and this is a picture, not a linear selection.
         */
        void setMarked(boolean on) {
            mark.setColor(on ? ACCENT : Color.TRANSPARENT);
        }
    }

    /** {@code .opt} — a bordered row with a swatch, a title and a subtitle. */
    static OptionRow option(Activity activity, String titleText, String subText,
                            boolean on, boolean locked, View.OnClickListener tap) {
        // The drawable is built first and handed to both the tile and the row, so
        // the row owns the mark it will later turn on and off.
        OptionRow row = new OptionRow(activity, dot(activity, on));
        frameRow(activity, row);
        // .locked: the WHOLE row at 42%, so it reads as unavailable rather than as a
        // label whose text happens to be grey.
        if (locked) row.setAlpha(0.42f);

        row.addView(swatch(activity, row.mark, null));
        row.addView(rowText(activity, titleText, subText), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setClickable(!locked && tap != null);
        if (!locked && tap != null) row.setOnClickListener(tap);
        return row;
    }

    /**
     * A row you can tap: a bordered ghost plate, with its label centred and a
     * pressed state from {@link #plate}.
     *
     * <p><b>Why a plate and not the framed row Reset used to be.</b> A bordered
     * row with no mark is the same picture as a fact in a bordered row, and the
     * two were literally the same factory — {@code plainRow} — until this pass.
     * The rule is one line: if it can be tapped it carries an affordance, and the
     * three affordances are the switch ({@link #switchRow}), this plate, and the
     * chevron for a row that navigates. If it carries none it must not respond to
     * touch, which is {@link #readingRow}'s whole definition.
     *
     * <p>Reset is the reason this exists: it is destructive, so it is a button
     * rather than a tappable label, and the subtitle carries the consequence on
     * the same plate the fix line's actions use.
     *
     * @param chevron true when the tap goes somewhere; a confirmation is a
     *                question and not a destination, so Reset passes false
     */
    static LinearLayout actionRow(Activity activity, String label, String sub,
                                  boolean chevron, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 49));
        row.setPadding(dp(activity, OPT_PAD_DP), dp(activity, OPT_PAD_DP),
                dp(activity, OPT_PAD_DP), dp(activity, OPT_PAD_DP));
        row.setBackground(plate(activity, false, true));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextSize(14);
        title.setTextColor(INK);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);
        if (sub != null && !sub.isEmpty()) {
            TextView small = new TextView(activity);
            small.setText(sub);
            small.setTextSize(12);
            small.setTextColor(DIM);
            small.setGravity(Gravity.CENTER);
            text.addView(small);
        }
        // Weight 1 so the label centres in the plate's own width; the chevron is
        // wrap and sits outside it, which is what "trailing" means.
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (chevron) {
            TextView arrow = new TextView(activity);
            arrow.setText(R.string.row_navigates_glyph);
            arrow.setTextSize(16);
            arrow.setTextColor(DIM);
            // The row is the control and describes itself; the arrow is decoration.
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            arrowLp.leftMargin = dp(activity, OPT_INNER_GAP_DP);
            row.addView(arrow, arrowLp);
        }

        row.setClickable(true);
        row.setOnClickListener(tap);
        // The plate is the affordance, and the row as one control reads the label
        // first rather than letting a screen reader stop on each line.
        row.setContentDescription(sub == null || sub.isEmpty() ? label : label + ". " + sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(activity, OPT_GAP_DP);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * A row that is information: a title and a subtitle, no border, no pressed
     * state, no trailing mark, quieter ink.
     *
     * <p><b>Why there is no listener parameter.</b> The About row and the Reset
     * row were the same factory with a nullable tap, which is how "Basrelief
     * 0.1.0" came to look exactly like a thing you could press. A reading that
     * cannot be handed a tap cannot drift into being one, so the signature is the
     * enforcement. Quieter ink is the other half: a fact is not asking to be
     * operated, so it does not use the full-strength ink a label on a control does.
     */
    static LinearLayout readingRow(Activity activity, String titleText, String subText) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        // No padding and no border: the text lines up with the caption above it,
        // which is the card's own edge. A framed reading was the ambiguity itself.
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(14);
        title.setTextColor(DIM);
        row.addView(title);
        if (subText != null && !subText.isEmpty()) {
            TextView small = new TextView(activity);
            small.setText(subText);
            small.setTextSize(12);
            small.setTextColor(DIMMER);
            row.addView(small);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(activity, OPT_GAP_DP);
        row.setLayoutParams(lp);
        return row;
    }

    /** A switch row's new state, once per user tap. */
    interface OnToggled {
        void onToggled(boolean on);
    }

    /**
     * {@code .opt}, with the platform {@link Switch} where the mockup draws its
     * swatch.
     *
     * <p><b>Why the platform widget.</b> The mockup's {@code .sw} is a 34dp tile
     * with an accent dot, which reads as a picture or a colour chip rather than as
     * something that can be moved. Material's {@code SwitchCompat} is not available
     * (see the class javadoc), so the platform switch is the honest equivalent: it
     * has the shape, the motion and the state channel users and screen readers
     * already know.
     *
     * <p><b>It is a mark, not a second tap target.</b> The Switch itself is not
     * clickable; the whole row is, and it calls {@code toggle()}. Two live targets
     * would give one control two announcements and let a tap on the switch fall
     * through to the row as a second toggle.
     *
     * <p><b>Trailing, which is where a control goes.</b> The mockup draws its state
     * mark in the leading slot, but that mark is a swatch -- a picture of a state --
     * and a switch is a control you operate. Android and Material both put the label
     * on the leading side and the control on the trailing one, and users' hands
     * already know that: the right edge is where you reach for a toggle, and a
     * leading switch reads as a second label. The user asked for the convention
     * explicitly, so this deliberately departs from the mockup's mark position.
     *
     * @param onToggled called with the new state after each tap. May be null for a
     *                  row that shows state but owns none.
     */
    static LinearLayout switchRow(final Activity activity, String titleText, String subText,
                                  boolean on, final OnToggled onToggled) {
        final LinearLayout row = new LinearLayout(activity);
        frameRow(activity, row);

        final Switch sw = new Switch(activity);
        // No ON/OFF thumb text: the tint carries the state and the words are
        // already in the row.
        sw.setShowText(false);
        sw.setChecked(on);
        // The row owns the tap; see the javadoc. Not focusable either, or a screen
        // reader stops twice on one control.
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setTrackTintList(new ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{ SWITCH_TRACK_ON, SWITCH_TRACK_OFF }));
        sw.setThumbTintList(new ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{ SWITCH_THUMB_ON, SWITCH_THUMB_OFF }));
        // Text first, so the weight-1 column takes the space and the switch keeps
        // its intrinsic width at the trailing edge. Reversing these two lines is
        // what puts the switch back on the left.
        row.addView(rowText(activity, titleText, subText), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // .opt{gap:12px}, now as the space between the text and the control.
        swLp.leftMargin = dp(activity, OPT_INNER_GAP_DP);
        row.addView(sw, swLp);

        describe(activity, row, titleText, subText, on);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // Read from the widget that moved, not from a calculation: the
                // switch cannot describe a state it does not hold.
                describe(activity, row, titleText, subText, isChecked);
                if (onToggled != null) onToggled.onToggled(isChecked);
            }
        });
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        return row;
    }

    /**
     * Say a switch row's state out loud.
     *
     * <p>The visible switch is a shape and two colours, which nobody can hear, so
     * the row's own description carries On or Off. Rewritten on every change rather
     * than written once at build time, because a description that is right until
     * the first tap is worse than none.
     */
    private static void describe(Activity activity, LinearLayout row, String title,
                                 String sub, boolean on) {
        String state = activity.getString(
                on ? R.string.settings_state_on : R.string.settings_state_off);
        row.setContentDescription(title + ". " + sub + ", " + state);
    }

    /** {@code .field} — the monospace endpoint box. */
    static android.widget.EditText field(Activity activity, String value, String hint) {
        android.widget.EditText e = new android.widget.EditText(activity);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(13.5f);
        e.setTextColor(INK);
        e.setHintTextColor(DIMMER);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setPadding(dp(activity, 11), dp(activity, 11), dp(activity, 11), dp(activity, 11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x12FFFFFF);
        bg.setCornerRadius(dp(activity, 9));
        bg.setStroke(dp(activity, 1), LINE);
        e.setBackground(bg);
        return e;
    }

    static int dp(android.content.Context context, int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * The card, and the one place a vertical drag inside its content is
     * arbitrated.
     *
     * <p>The card is only the ViewGroup the platform offers interception
     * through; the rule itself is {@link BottomSheet#onIntercept}, the same one
     * the studio's panel uses. A grip drag never reaches here — the grip
     * disallows interception for its whole gesture — and a scroll that can still
     * use the drag keeps it; everything else — a slider, a switch row, the card's
     * own padding — hands the drag to the sheet. Because a dialog is
     * {@link BottomSheet.Snap#TO_STOPS}, taking over still lands on open or
     * dismissed when the finger lifts, never parked mid-way.
     */
    private final class SheetCard extends LinearLayout {

        /**
         * Where this gesture's finger went down, in screen coordinates.
         *
         * <p>One float, and it exists only to answer "is this drag upward?" — the
         * question {@link #growthDrag} asks. The component tracks its own anchor for
         * the same purpose but does not expose it, and the alternative would be for
         * this class to re-implement the whole release rule.
         */
        private float downRawY;

        SheetCard(android.content.Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downRawY = e.getRawY();
            }
            if (growthDrag(e)) return false;
            return sheet != null && sheet.onIntercept(e, isInsideScroll(e.getY()));
        }

        /**
         * Whether an upward drag that the content has already consumed to its end
         * should still be offered to the content, so that the card grows.
         *
         * <p><b>Without this the expand gesture does not exist.</b> The component's
         * rule is that the scroll keeps a drag it can use, and once the content is at
         * its bottom edge {@code canScrollVertically(1)} is false — so the card claims
         * an upward drag there and drags <i>itself</i> up, which does nothing at all
         * because it is already open ({@link BottomSheet} tethers it with
         * {@code resistUp}). The leftover delta, the one that means "show me more",
         * would never reach {@link CappedScroll#overScrollBy} and the card could never
         * grow.
         *
         * <p>So this one case is given back to the content, and the content's edge
         * hook does the right thing with it: the delta is unconsumed, so the card
         * follows the finger up. Gated on {@link CappedScroll#canGrow()} so that once
         * the card is at its full-height stop the gesture goes back to the card and
         * keeps the feel it has today.
         *
         * <p><b>The downward direction is deliberately untouched.</b> A finger going
         * down at the content's top is the hand-over — the card drag that can dismiss
         * — and that is a better answer than shrinking the card by the same few
         * pixels, so it is left exactly as it was.
         */
        private boolean growthDrag(MotionEvent e) {
            return sheet != null
                    && e.getActionMasked() != MotionEvent.ACTION_DOWN
                    && e.getRawY() < downRawY
                    && isInsideScroll(e.getY())
                    && !scroll.canScrollVertically(1)
                    && scroll.canGrow();
        }

        /**
         * Whether a card-local y lands in the scrolling content.
         *
         * <p>The scroll is a direct child of the card, so its top and bottom are
         * already card coordinates and no global offset is needed.
         */
        private boolean isInsideScroll(float y) {
            return scroll != null
                    && y >= scroll.getTop() && y < scroll.getBottom();
        }
    }

    /**
     * A ScrollView with a height of its own — one the framework has no attribute
     * for — which grows and shrinks only at its edges.
     *
     * <p>It measures its child the way a ScrollView always does (the child's natural
     * height, unbounded) and then decides its <i>own</i> measured height from two
     * numbers and what it is already showing: {@link ModalSheet#viewportAtRest}.
     * <b>The scroll position is not an input to that, and the reason is the bug this
     * class used to have.</b>
     *
     * <p><b>The feedback loop, written down so it cannot be rebuilt by accident.</b>
     * The height used to be {@code min(content, ceiling, open + scrollY)}. Scrolling
     * therefore changed the viewport height; the viewport height changed the scroll
     * range; the new range changed where the content actually ended up; and
     * {@code scrollY} is the input, so that fed straight back into the height. Each
     * correction overshot the last and the content visibly oscillated — the user's
     * report was <i>scrolling inside the sheet made the content jitter up and down</i>, and their diagnosis was exact: <i>the scroll is also trying to expand
     * the sheet; it shouldn't</i>. It survived review because every <i>resting</i>
     * bound was correct, and a settled state is the one thing a screenshot cannot
     * check.
     *
     * <p><b>What replaces it: edge over-scroll drives the height.</b> Pull past the
     * top edge and the card expands; pull past the bottom edge and it collapses. In
     * between, scrolling is reading and the height does not move. This is the
     * canonical nested-scroll hand-off, in the one shape available here —
     * {@link #overScrollBy} rather than a {@code NestedScrollView}, because
     * {@code NestedScrollView} is an <b>AndroidX</b> widget and this app has no
     * AndroidX, no Material and no Maven dependency at all. {@code overScrollBy} is
     * precisely the platform's
     * "here is the delta, tell me what you did with it" hook, and the delta it is
     * given <i>is</i> the unconsumed edge delta once the scroll has clamped.
     *
     * <p><b>Shrink-wrap survives, and the regression it guards is recorded.</b> An
     * earlier version weighed the scroll inside the card, which made a zero-height
     * child of a wrap_content LinearLayout take all the space offered
     * (LinearLayout.measureVertical, targetSdk >= P) and so could never shrink-wrap;
     * the version after that measured the whole card and cut the scroll afterwards,
     * which could not either. This one never touches the card: it clamps its own
     * measured dimension, which is a property of the scroll and nothing else.
     */
    private static final class CappedScroll extends ScrollView {

        /** Where the viewport starts. See {@link #setBounds}. */
        private int openHeight;
        /** The tallest the viewport may be. See {@link #setBounds}. */
        private int maxHeight;
        /**
         * The content's own height, from the last measure.
         *
         * <p>Read from the child rather than from {@code getMeasuredHeight()}; see
         * {@link #onMeasure} for why that distinction is the whole difference between
         * a content height and a parent-imposed one.
         */
        private int naturalHeight;
        /**
         * The height this view is currently showing. Negative until the first measure.
         *
         * <p><b>This is the decoupling, as a field.</b> It is the height's only
         * input besides the three bounds; nothing writes it from a scroll callback,
         * and {@link #overScrollBy} writes it only for a delta the content could not
         * consume.
         */
        private int currentHeight = -1;

        CappedScroll(Context context) {
            super(context);
        }

        /**
         * The two heights this view may take, from {@link ModalSheet#applyHeight}.
         *
         * <p>Both are needed and neither implies the other: the opening height is
         * what makes a card scrollable at all, the ceiling is what stops it running
         * off the screen, and a card can be given a very large ceiling without being
         * told to open there.
         *
         * <p>The current height is <b>not</b> reset. A keyboard, an inset or a body
         * swap must not throw away a height the user pulled to; the bounds are a range
         * and the card is re-clamped into it by {@link #onMeasure}.
         */
        void setBounds(int openPx, int maxPx) {
            if (openHeight == openPx && maxHeight == maxPx) return;
            openHeight = openPx;
            maxHeight = maxPx;
            requestLayout();
        }

        /**
         * Reopen at the opening height.
         *
         * <p>For a transient dialog whose instance is reused — the Depth AI panel is
         * built once and reopened — so a second open does not inherit the height the
         * last one was pulled to. The content's scroll position is deliberately
         * <i>not</i> reset: where the user was reading is worth keeping, and it is no
         * longer coupled to the height.
         */
        void resetToOpen() {
            currentHeight = openHeight > 0 ? openHeight : -1;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            // The CONTENT's own height, read from the child -- never
            // getMeasuredHeight(). FrameLayout resolves its own measured height with
            // resolveSizeAndState against the incoming spec, so this view's measured
            // height is already bounded by whatever the card offered this pass: the
            // screen, or a screen with the keyboard up, or a half-finished layout.
            // Using it as "how tall is the content" therefore fed the parent back into
            // the content, which is the same class of loop this class was rewritten to
            // remove -- and it showed as a card collapsing to a few tens of pixels.
            // The child's measured height is the answer, because ScrollView measures
            // its child with an UNSPECIFIED height precisely so the child can be
            // taller than the viewport. fillViewport is off, so that height is the
            // content's natural one and not a stretched one.
            naturalHeight = getChildCount() > 0 ? getChildAt(0).getMeasuredHeight() : 0;
            // No budget yet means this is the first layout of the dialog and there is
            // nothing to clamp to: leave the natural height, which is what the
            // version before setBounds did, and let applyHeight's own requestLayout
            // put the real answer in the next frame.
            if (maxHeight <= 0) return;
            // A child that has not been measured yet is not a short card. Falling
            // through would answer 0 and clamp the viewport to nothing, and a card
            // that has collapsed to nothing measures its content even smaller.
            if (naturalHeight <= 0) return;
            if (currentHeight < 0) currentHeight = openHeight;
            int target = ModalSheet.viewportAtRest(naturalHeight, openHeight, maxHeight,
                    currentHeight);
            currentHeight = target;
            if (target != naturalHeight) {
                setMeasuredDimension(getMeasuredWidth(), target);
            }
        }

        /**
         * The platform's "here is a scroll delta, tell me what you did with it" hook,
         * and the only thing that may change this card's height.
         *
         * <p><b>One question decides everything: did the content have anywhere to
         * go?</b> {@code ScrollView} computes its delta as {@code lastY - y}, so a
         * positive delta is the finger moving up. If the content can still scroll that
         * way, it took the delta and this is reading — nothing here runs, and that is
         * the user's correction made mechanical. If it cannot, the delta is
         * <i>unconsumed</i>, and the card takes it: {@link #resize} moves the height
         * by exactly that much in the same direction. Reading never resizes; an edge
         * always continues the motion.
         *
         * <p><b>Only a finger may resize.</b> {@code isTouchEvent} is false for the
         * fling path in {@code computeScroll}, and that matters: when a fling reaches
         * an edge, {@code ScrollView.onOverScrolled} calls {@code mScroller.springBack},
         * whose bounce-back arrives through this same hook with the opposite sign.
         * Growing on the pull and shrinking on the spring-back is the same feedback
         * loop wearing a different hat. A fling is also a reading gesture.
         *
         * <p>{@code super} is called first so the platform's edge glow still absorbs
         * the over-scroll; this consumes nothing from the framework, it only reads the
         * delta the framework could not use.
         */
        @Override
        protected boolean overScrollBy(int dx, int dy, int scrollX, int scrollY,
                                       int scrollRangeX, int scrollRangeY,
                                       int maxScrollX, int maxScrollY,
                                       boolean isTouchEvent) {
            boolean over = super.overScrollBy(dx, dy, scrollX, scrollY, scrollRangeX,
                    scrollRangeY, maxScrollX, maxScrollY, isTouchEvent);
            if (!isTouchEvent || dy == 0 || maxHeight <= 0 || naturalHeight <= 0) return over;
            // dy > 0 is the finger moving up, which wants to scroll the content
            // further down; dy < 0 is the finger moving down, which wants to scroll it
            // back up. Either way, "can it still go that way?" is the whole test.
            boolean contentCouldTakeIt = dy > 0 ? canScrollVertically(1) : canScrollVertically(-1);
            if (!contentCouldTakeIt) resize(dy);
            return over;
        }

        /**
         * Whether the card could still get taller.
         *
         * <p>For {@link SheetCard}'s gesture arbitration, which has to know this
         * before the gesture starts: see the comment there.
         */
        boolean canGrow() {
            return maxHeight > 0 && naturalHeight > 0 && currentHeight >= 0
                    && currentHeight < ModalSheet.viewportMax(naturalHeight, maxHeight);
        }

        /**
         * Move the card by an unconsumed edge delta, if that changes anything.
         *
         * <p>The layout request is the growth itself: the height is applied in
         * {@link #onMeasure}, so asking for a layout is how the card gets taller, and
         * it is one pass per frame the height is actually moving. Once the card is at
         * a stop this returns without asking, so a long pull at the edge costs
         * nothing after the first few pixels.
         */
        private void resize(int unconsumedYPx) {
            int next = ModalSheet.viewportAfterOverScroll(naturalHeight, openHeight,
                    maxHeight, currentHeight, unconsumedYPx);
            if (next == currentHeight) return;
            currentHeight = next;
            requestLayout();
        }

        /**
         * Keep the card in the running for this gesture.
         *
         * <p>{@code ScrollView} declares itself the only vertical claimant for the
         * rest of a drag: the moment it starts scrolling it calls
         * {@code requestDisallowInterceptTouchEvent(true)} on its parent, so the
         * card's arbitration is never offered another event and the nested
         * hand-over the user asked for — scroll the content to its end, then the
         * same drag moves the card — can never happen inside one gesture. Clearing
         * the flag after every event puts the choice back with the card, whose
         * rule is exactly {@code canScrollVertically}. This is the same repair as
         * the studio's {@code BottomFadeScrollView}; the two scroll views are in
         * different windows, so neither can share the other's subclass.
         *
         * <p><b>Why this still matters now that the edges do something else.</b>
         * The edges changed meaning, not the arbitration, and the two do not
         * overlap:
         *
         * <ul>
         *   <li>Dragging <b>down</b>: the scroll keeps the gesture while
         *       {@code canScrollVertically(-1)} — while there is anything above to
         *       read. Only at the content's <b>top</b> does the card claim, and that
         *       is the hand-over: the same drag moves the card and can dismiss it.
         *       A downward pull at the <i>bottom</i> edge is the collapse, and it
         *       happens inside the scroll, so it never reaches here.
         *   <li>Dragging <b>up</b>: the scroll keeps the gesture while
         *       {@code canScrollVertically(1)}. At the content's <b>top</b> edge there
         *       is still more content below, so the scroll keeps it and the pull
         *       becomes the expand. This is why expanding is reachable at all: if the
         *       card claimed the gesture at the top edge on the way up, an upward
         *       pull would drag the card instead of opening it, and the expand would
         *       be unreachable.
         * </ul>
         *
         * <p>So the same edge answers two different gestures depending on direction,
         * which is the user's own rule: up past the top expands, down past the top
         * hands over. Neither branch needs to know about the other.
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

    /**
     * A bottom-anchored card's background: the fill with rounded top corners,
     * and a hairline along the <b>top edge only</b>.
     *
     * <p><b>Why this is not a GradientDrawable with {@code setStroke}.</b> A
     * stroke is drawn on all four edges of the shape. On a sheet or modal whose
     * left, right and bottom edges are the screen's own, that is a 1px bright
     * line down each side and along the foot — the "bleeding" the user reported
     * on the studio sheet — and it is also why the rounded top corners read as a
     * boxed card rather than as a sheet. The design draws one edge:
     * {@code .sheet / .modal { border-top:1px solid var(--line) }}.
     *
     * <p>The line is clipped to the top edge's own pixels, so at the corners it
     * follows the fill's own rounding the way a CSS {@code border-top} does: the
     * visible run is the flat top between the two arcs, not a bar laid across a
     * transparent notch.
     *
     * <p>{@code setAlpha} is honoured by folding {@link #getAlpha()} into the
     * edge paint at draw time, because the studio dims this background while a
     * control is held ({@code MainActivity.setSheetBackgroundAlpha}) and the
     * hairline has to dim with it.
     *
     * <p><b>The hairline's arc radius is read back off {@link #getCornerRadii()},
     * not cached.</b> It used to be a {@code final} field taken from the
     * constructor, which is right for a card whose radius never changes and wrong
     * for the one host that animates it: the studio's panel rounds from 22dp to
     * half a pill on every frame of a collapse, and a hairline still drawing a
     * 22dp arc under a fill rounded to 28dp leaves the corner visibly inconsistent
     * — the one row of pixels where the line stops short of the shape. Reading it
     * back cannot drift, because the same {@code setCornerRadii} call the morph
     * makes is what it reads.
     */
    static final class TopEdgeBackground extends GradientDrawable {

        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int fillColour;
        private final int edgeColour;
        private final float edgeWidth;
        /**
         * A multiplier on the FILL's alpha. The studio's panel is the sheet's glass and
         * the strip is the pill's plate, and the panel gives its fill up as the strip
         * takes over; two opaque layers of the same colour compose to solid, which is
         * what made the pill opaque however translucent its own plate was.
         */
        private float fillAlpha = 1f;
        /**
         * A multiplier on the hairline's alpha, for a host that retires the line
         * over the course of an animation. 1.0 is "draw it as configured" and every
         * card here is constructed and never touched, so their lines are unchanged.
         */
        private float edgeAlpha = 1f;

        TopEdgeBackground(int fill, int edgeColour, float topCornerRadiusPx,
                          float edgeWidthPx) {
            this.edgeColour = edgeColour;
            this.edgeWidth = edgeWidthPx;
            setShape(RECTANGLE);
            // GradientDrawable keeps its own fill, and setFillAlpha below rewrites the
            // colour's alpha -- so the subclass does not paint the fill itself and
            // setStroke's fill-clearing cannot take it away.
            this.fillColour = fill;
            setColor(fill);
            setCornerRadii(new float[]{topCornerRadiusPx, topCornerRadiusPx,
                    topCornerRadiusPx, topCornerRadiusPx, 0, 0, 0, 0});
            edge.setStyle(Paint.Style.STROKE);
            // dp(1), like every other hairline in the app: a CSS pixel is a
            // device-independent pixel, so one physical pixel would be half the
            // design's line on a 2x screen and inconsistent with the foot rule
            // and the button plates beside it.
            edge.setStrokeWidth(edgeWidthPx);
            edge.setDither(true);
        }

        /**
         * Fade the hairline without touching the fill. A paint property, so it
         * costs a repaint and no relayout — the same reason the corner radius is
         * one.
         *
         * @param alpha 1.0 as configured, 0.0 for no line at all, clamped between
         */
        void setEdgeAlpha(float alpha) {
            edgeAlpha = Math.min(1f, Math.max(0f, alpha));
        }

        /**
         * Fade the FILL, leaving the hairline alone.
         *
         * <p><b>Because the sheet and the pill are one surface at two stages.</b> The
         * studio's panel paints the sheet's glass; the strip paints the pill's plate on
         * top of it. Two opaque layers compose to roughly eighty per cent even when each
         * is set to fifty-five, so the pill was solid however translucent its own colour
         * was -- the panel underneath was still painting. The panel gives its fill up as
         * the strip becomes the pill, and the hairline follows {@link #setEdgeAlpha}
         * separately because the two have different jobs: the edge is the sheet's top
         * rule, which the pill does not want at all.
         *
         * @param alpha 1.0 as configured, 0.0 for no fill at all, clamped between
         */
        void setFillAlpha(float alpha) {
            fillAlpha = Math.min(1f, Math.max(0f, alpha));
            int a = Math.round(fillAlpha * (fillColour >>> 24));
            setColor((a << 24) | (fillColour & 0x00FFFFFF));
        }

        @Override
        public void draw(Canvas canvas) {
            // The fill's own alpha is folded into the paint rather than into setAlpha,
            // so it composes with getAlpha() the same way the edge does -- two callers
            // fade this drawable for two different reasons and neither may clobber the
            // other.
            super.draw(canvas);
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (edgeAlpha <= 0f) return;
            int a = ((edgeColour >>> 24) * getAlpha() * Math.round(edgeAlpha * 255f))
                    / (255 * 255);
            if (a <= 0) return;
            edge.setColor((a << 24) | (edgeColour & 0x00FFFFFF));
            // Read back rather than cached, so the line's arc is always the fill's
            // own arc. getCornerRadii() returns 8 entries from the first
            // setCornerRadii, and 4 after a setCornerRadius; both are laid out with
            // the top-left first.
            float[] radii = getCornerRadii();
            float radius = (radii != null && radii.length >= 4) ? radii[0] : 0f;
            float half = edgeWidth / 2f;
            int save = canvas.save();
            // Only the top edge's own pixels of the outline: the arcs start
            // radius pixels down, so the corners contribute nothing below this
            // clip and the line stops where the rounding does.
            canvas.clipRect(b.left, b.top, b.right, b.top + edgeWidth);
            // Half the stroke inset so the line sits inside the bounds rather
            // than straddling them and losing its top half to the clip.
            canvas.drawRoundRect(b.left + half, b.top + half,
                    b.right - half, b.bottom - half, radius, radius, edge);
            canvas.restoreToCount(save);
        }
    }
}