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
 * <p><b>The grip runs on the shared sheet component; the mechanism is injected.</b>
 * The drag, the entrance, the release rule and the fade live in
 * {@link BottomSheet}. This card is its translation host — safe here because a
 * Dialog owns its own window and contains no {@code SurfaceView} — while the
 * studio sheet is the layout-height host beside a {@code GLSurfaceView}
 * (b/7179570). It also asks for a different snap policy: a dialog is transient,
 * so {@link BottomSheet.Snap#TO_STOPS} makes every release land on open or gone,
 * never parked; the studio's persistent sheet is free. The policy is an argument
 * to the component, so the two cannot silently swap.
 */
final class ModalSheet {

    // The mockup's tokens. --scrim is rgba(0,0,0,.62); the depth screen deliberately
    // passes a lighter one, which is a difference in the design and not an accident.
    static final int SCRIM = 0x9E000000;
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
     * A modal on the standard scrim: {@code .scrim} at {@code rgba(0,0,0,.62)}, which
     * is what the mockup draws behind three of its four modals.
     */
    ModalSheet(Activity activity, float maxHeightFraction) {
        this(activity, maxHeightFraction, SCRIM);
    }

    /**
     * A modal on a chosen scrim.
     *
     * @param scrim the colour behind the card. One screen in the design is drawn on a
     *     lighter one — the depth panel is a form, and .62 over a photograph is
     *     enough to make it hard to read — so this is a parameter rather than a
     *     constant, but only because the mockup says so.
     */
    ModalSheet(Activity activity, float maxHeightFraction, int scrim) {
        this.maxHeightFraction = maxHeightFraction;
        this.scrim = scrim;

        FrameLayout scrimView = new FrameLayout(activity);
        scrimView.setBackgroundColor(scrim);

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
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
                // changed it, so re-cap. applyHeight() is a no-op before first layout;
                // its own post() runs it once there is a height to compute against.
                if (root.getHeight() > 0) applyHeight();
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
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        // Transparent, so the scrim is ours to draw and the platform's dim is not
        // stacked on top of it. Two dimmers is how a .62 scrim ends up at .8.
        dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
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
                // The card's contents and its glass are one view, so they fade
                // together here; the scrim is the surface that follows, tracking
                // the same progress so it brightens — becomes less opaque — as the
                // card comes down and the background is revealed behind it. The
                // drawable's own alpha is set rather than a fresh background
                // colour, because this runs on every frame of a drag and
                // setBackgroundColor would allocate a ColorDrawable per frame.
                card.setAlpha(BottomSheet.contentAlpha(progress));
                android.graphics.drawable.Drawable scrimDrawable = root.getBackground();
                if (scrimDrawable != null) {
                    int base = Color.alpha(scrim);
                    scrimDrawable.setAlpha(Math.round(base * (1f - progress)));
                }
            }

            @Override
            public View scrollView() {
                // The grip is this card's only drag surface; the content inside
                // the scroll keeps its own gesture and is never offered to the
                // sheet. The studio sheet is where content drags matter, because
                // its strip and fix line sit next to the scroll.
                return null;
            }

            @Override
            public void onResting(float fraction, boolean collapsed) {
                if (collapsed) dismiss();
            }
        }, SpringSettle.CARD, BottomSheet.Snap.TO_STOPS, slop, new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });
        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return sheet.onTouch(v, e);
            }
        });
        card.addView(strip, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, GRIP_HEIGHT_DP)));
    }

    /**
     * Cap the scroll to the card's share of the screen, minus the card's own
     * chrome and the pinned footer.
     *
     * <p>The cap is a property of the scroll ({@link CappedScroll}), not a fixed
     * height applied once after layout. That is what lets the card shrink-wrap: a
     * three-line form is as tall as its content, and a form longer than the
     * fraction scrolls inside a card that stops at the fraction. The old version
     * measured the whole card and cut the scroll after the fact, which on a
     * short card had already been stretched to the screen by the weighted child
     * (see the constructor) and so could never shrink-wrap at all.
     *
     * <p>It runs again whenever the answer changes: the first layout, an inset
     * dispatch, a footer, a body swap. The ceiling is derived from values that
     * are all in hand at those moments; measuring the card is not.
     */
    private void applyHeight() {
        int screen = root.getHeight();
        if (screen <= 0) return;
        // The inset is inside the card's padding, so it is not height the card may
        // use: subtract it from the ceiling and the card still fits the fraction.
        int ceiling = (int) (screen * maxHeightFraction) - bottomInset;
        int maxScroll = ceiling - chromeHeight();
        if (maxScroll < dp(root.getContext(), 120)) {
            // A screen so short that the chrome alone eats the fraction: prefer a
            // scrollable card that overflows the fraction to one with no content
            // in it. Same floor the playlists editor used, and the reason it had
            // to exist is this branch.
            maxScroll = (int) (screen * maxHeightFraction);
        }
        scroll.setCeiling(maxScroll);
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
        if (root.getHeight() > 0) applyHeight();
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
        if (root.getHeight() > 0) applyHeight();
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
     * A ScrollView with a ceiling, which the framework has no attribute for.
     *
     * <p>It measures its child the way a ScrollView always does — the child's
     * natural height, unbounded — and then clamps its own measured height to the
     * ceiling. The child keeps its natural height, which is exactly what makes it
     * scroll, and below the ceiling the view shrink-wraps, so a short list is a
     * short card. This is the capability the playlists screen used to carry
     * privately; it now applies to every modal, which is what makes a
     * {@code body()} swap in that screen preserve its own look.
     */
    private static final class CappedScroll extends ScrollView {

        private int ceiling;

        CappedScroll(Context context) {
            super(context);
        }

        void setCeiling(int px) {
            if (ceiling == px) return;
            ceiling = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (ceiling > 0 && getMeasuredHeight() > ceiling) {
                setMeasuredDimension(getMeasuredWidth(), ceiling);
            }
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
     */
    static final class TopEdgeBackground extends GradientDrawable {

        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int edgeColour;
        private final float radius;
        private final float edgeWidth;

        TopEdgeBackground(int fill, int edgeColour, float topCornerRadiusPx,
                          float edgeWidthPx) {
            this.edgeColour = edgeColour;
            this.radius = topCornerRadiusPx;
            this.edgeWidth = edgeWidthPx;
            setShape(RECTANGLE);
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

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            int a = ((edgeColour >>> 24) * getAlpha()) / 255;
            edge.setColor((a << 24) | (edgeColour & 0x00FFFFFF));
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