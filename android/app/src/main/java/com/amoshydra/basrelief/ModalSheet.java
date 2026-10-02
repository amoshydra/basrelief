package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private ModalSheet(Activity activity, float maxHeightFraction) {
        this.maxHeightFraction = maxHeightFraction;

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(SCRIM);

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadii(new float[]{dp(activity, 20), dp(activity, 20), 0, 0,
                dp(activity, 20), dp(activity, 20), 0, 0});
        bg.setStroke(dp(activity, 1), LINE);
        card.setBackground(bg);
        card.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16));

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

        scrim.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        root = scrim;
    }

    private final FrameLayout root;

    /** Present. */
    void show(Activity activity) {
        dialog = new Dialog(activity,
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        // Transparent, so the scrim is ours to draw and the platform's dim is not
        // stacked on top of it. Two dimmers is how a .62 scrim ends up at .8.
        dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                dialog = null;
            }
        });
        // A Dialog does not dismiss itself on back by default, and a modal you cannot
        // leave with the system gesture is a trap.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss();
                    return true;
                }
                return false;
            }
        });
        dialog.show();
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
        int ceiling = (int) (screen * maxHeightFraction);
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
        t.setPadding(dp(activity, 2), first ? dp(activity, 4) : dp(activity, 16),
                dp(activity, 2), dp(activity, 7));
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
}