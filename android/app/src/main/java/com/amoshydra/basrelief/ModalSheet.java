package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    /** The tallest a modal may be, as a fraction of the display. */
    private final float maxHeightFraction;

    private final LinearLayout card;
    private final ScrollView scroll;
    private final LinearLayout footer;
    private Dialog dialog;

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

        scroll = new ScrollView(activity);
        // The mockup masks the last 22px; a fading edge is a child property, so this
        // is safe where a translated overlay would not be.
        scroll.setFadingEdgeLength(dp(activity, 22));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setClipToPadding(false);

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
    }

    /**
     * Cap the card, then give the rest to the scroll.
     *
     * <p>Done after layout, from the card's own measured height, rather than by setting
     * a fixed fraction up front — which is what the apply modal did, so a three-line
     * form sat at the top of a large empty card with the buttons far from the content.
     */
    private void applyHeight() {
        int screen = root.getHeight();
        if (screen <= 0) return;
        // The inset is inside the card's padding, so it is not height the card may
        // use: subtract it from the ceiling and the card still fits the fraction.
        int ceiling = (int) (screen * maxHeightFraction) - bottomInset;
        if (ceiling <= 0) return;
        int natural = card.getMeasuredHeight();
        if (natural <= ceiling) {
            // Shrink-wrap: the card is as tall as it needs and no taller.
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            return;
        }
        int footerHeight = footer.getVisibility() == View.GONE ? 0 : footer.getMeasuredHeight();
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ceiling - footerHeight));
    }

    /** Add content to the scrolling part. */
    void body(View view) {
        scroll.addView(view, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Add a row of buttons that stays put. Equal weights; two is the usual number. */
    void footerRow(Button... buttons) {
        footer.setVisibility(buttons.length == 0 ? View.GONE : View.VISIBLE);
        for (Button b : buttons) {
            footer.addView(b, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (buttons.length > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(root.getContext(), 14);
            footer.setLayoutParams(lp);
            card.addView(footer);
        }
    }

    void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
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

    /** {@code .btn} — a filled plate. {@code .btn.ghost} is the transparent one. */
    static Button button(Activity activity, String label, boolean primary, boolean ghost) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(activity, 49));
        b.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 11));
        if (primary) {
            // .btn.primary is a WHITE fill with near-black text. Accent is the app's
            // highlight colour, not this button's: using it here made Cancel/Continue
            // disagree with the identical pair in the playlists modal.
            bg.setColor(INK);
            b.setTextColor(INK_ON_FILL);
        } else {
            bg.setColor(ghost ? Color.TRANSPARENT : 0x1AFFFFFF);
            bg.setStroke(dp(activity, 1), LINE);
            b.setTextColor(INK);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp != null) lp.rightMargin = dp(activity, 4);
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
        t.setPadding(0, first ? dp(activity, 4) : dp(activity, 16), 0, dp(activity, 7));
        return t;
    }

    /** {@code .note} — 11px, --dimmer, centred. */
    static TextView note(Activity activity, CharSequence text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(DIMMER);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(activity, 7), 0, 0);
        return t;
    }

    /**
     * {@code .opt .sw} — the 34dp tile with a 14dp accent dot on its bottom-right,
     * ringed in the card's own colour so the dot reads as sitting ON the tile.
     *
     * @return the tile's container, so the dot can be toggled without rebuilding.
     */
    static FrameLayout swatch(Activity activity, boolean on, View.OnClickListener tap) {
        FrameLayout holder = new FrameLayout(activity);
        GradientDrawable tile = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF3D5563, 0xFF1B262E});
        tile.setCornerRadius(dp(activity, 9));
        holder.setBackground(tile);

        View dot = new View(activity);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(on ? ACCENT : Color.TRANSPARENT);
        d.setStroke(dp(activity, 2), CARD);
        dot.setBackground(d);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                dp(activity, 14), dp(activity, 14), Gravity.BOTTOM | Gravity.RIGHT);
        dotLp.leftMargin = -dp(activity, 3);
        dotLp.bottomMargin = -dp(activity, 3);
        holder.addView(dot, dotLp);

        if (tap != null) holder.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(activity, 34), dp(activity, 34));
        lp.rightMargin = dp(activity, 12);
        holder.setLayoutParams(lp);
        return holder;
    }

    /** {@code .opt} — a bordered row with a swatch, a title and a subtitle. */
    static LinearLayout option(Activity activity, String titleText, String subText,
                               boolean on, boolean locked, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(activity, 11));
        bg.setStroke(dp(activity, 1), LINE);
        row.setBackground(bg);
        // .locked: the WHOLE row at 42%, so it reads as unavailable rather than as a
        // label whose text happens to be grey.
        if (locked) row.setAlpha(0.42f);

        row.addView(swatch(activity, on, null));

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
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setClickable(!locked && tap != null);
        if (!locked && tap != null) row.setOnClickListener(tap);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(activity, 8);
        row.setLayoutParams(lp);
        return row;
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