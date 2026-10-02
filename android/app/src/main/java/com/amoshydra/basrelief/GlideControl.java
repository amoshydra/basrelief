package com.amoshydra.basrelief;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The one glide rate, as a slider.
 *
 * <p>Split out of {@link SettingsSheet} because it is the only control on that
 * screen that is not a switch, and because it is the one setting with a real
 * physical meaning: how long the view takes to travel.
 *
 * <p>It reads and writes {@code glide_ms} under {@link AppState#PREFS}, which is
 * what {@link TiltController} reads to decide how fast a re-centre eases in --
 * see {@code TiltController.glideRate()}. The range is not this file's to pick:
 * {@link TiltController#GLIDE_MIN_MS} is the shortest glide that stays inside the
 * renderer's fast-attack budget and {@link TiltController#GLIDE_MAX_MS} the
 * slowest that still reads as a glide, so both ends are declared there and the
 * slider is made to fit them. A slider whose lower half did nothing would be the
 * same lie as a slider that wrote nothing.
 *
 * <p>One rate, used for re-centring, for switching between home and lock, and for
 * playlist advance. Three speeds for three things is three numbers nobody can
 * predict, and they all describe the same physical motion.
 *
 * <p>Drawn by hand rather than with a bare {@link SeekBar}, because the mockup's
 * slider is a filled track with the value printed inside it, not a thumb: see
 * {@code .design/prototype/final.html}, the {@code .row}/{@code .track} CSS and
 * the Settings tab.
 */
final class GlideControl {

    /** The mockup's accent (#7fd1c1) at the {@code opacity:.85} it draws the fill at. */
    private static final int ACCENT_FILL = 0xD97FD1C1;
    /** --dim: the label and any text on the track that is outside the fill. */
    private static final int DIM = 0x8CFFFFFF;
    /** rgba(255,255,255,.10) -- the empty part of the track (.row .track). */
    private static final int TRACK = 0x1AFFFFFF;
    /** rgba(255,255,255,.14) -- the hairline the mockup draws around a nudge. */
    private static final int LINE = 0x24FFFFFF;
    /** The value printed inside the fill: near-black on the accent. */
    private static final int ON_FILL = 0xFF08110F;
    /**
     * One nudge is one bar of the track.
     *
     * <p>A bar rather than a constant time, so the two ends of the range cannot
     * disagree about what a tap is worth. At the declared range one bar happens to
     * be 100 ms, which is fine enough to aim with and coarse enough to tap with a
     * fingertip; the reasoning for the range itself is on the constants in
     * {@link TiltController}.
     */
    private static final int NUDGE_BARS = 1;

    private GlideControl() {
    }

    /**
     * Build the slider. Persists to {@code glide_ms} under the app prefs.
     *
     * <p>Written once on release rather than per pixel of travel, for the same
     * reason every other slider does it: a drag would otherwise re-read and
     * re-validate the whole library once per pixel. The nudges persist too, since
     * they are a complete gesture with no "release" to wait for.
     */
    static View build(final MainActivity activity, final SharedPreferences prefs) {
        final int min = TiltController.GLIDE_MIN_MS;
        final int max = TiltController.GLIDE_MAX_MS;

        // Defensive on the read as well as the write: a pref written as a String by
        // an older build, or by a restored backup, throws out of getInt and would
        // take the whole settings screen down rather than showing one wrong number.
        int current = TiltController.GLIDE_DEFAULT_MS;
        try {
            current = prefs.getInt(TiltController.KEY_GLIDE_MS, current);
        } catch (Exception e) {
            // leave the default
        }
        current = clamp(current, min, max);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // .row { margin: 8px 0 } in the mockup. The caption above already supplies
        // its own 7px, so this is the rest of that gap and nothing else.
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(activity, 8);
        row.setLayoutParams(rowLp);

        // ---- label, a fixed column so every slider on this screen lines up
        TextView lab = new TextView(activity);
        lab.setText(R.string.slider_glide);
        lab.setTextSize(13);
        lab.setTextColor(DIM);
        row.addView(lab, new LinearLayout.LayoutParams(dp(activity, 66),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- the track: a fill, the value on top of it, and an invisible SeekBar
        // to take the drag. The platform widget cannot do this (it has a thumb,
        // the mockup has a fill) and a bare thumb over a filled track reads as a
        // different control, so the SeekBar is here only for the touch handling.
        final FrameLayout track = new FrameLayout(activity);
        track.setBackground(round(TRACK, 0, dp(activity, 9)));
        row.addView(track, new LinearLayout.LayoutParams(0,
                dp(activity, 34), 1f));

        final View fill = new View(activity);
        fill.setBackground(round(ACCENT_FILL, 0, dp(activity, 9)));
        track.addView(fill, new FrameLayout.LayoutParams(0,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView val = new TextView(activity);
        val.setTextSize(13);
        val.setTypeface(null, Typeface.BOLD);
        val.setGravity(Gravity.CENTER_VERTICAL);
        val.setPadding(dp(activity, 11), 0, dp(activity, 11), 0);
        // .row .over { left: 11px } -- the value starts at the same place either
        // way; only its colour changes when it moves off the fill.
        track.addView(val, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final SeekBar bar = new SeekBar(activity);
        bar.setMax(max - min);
        // Transparent, because the fill IS the progress. Both the thumb and the
        // track are tinted out rather than replaced, so the widget's own padding
        // (which reserves room for the thumb) is the only thing left showing --
        // and that is zeroed too, or the draggable range would stop short.
        bar.setPadding(0, 0, 0, 0);
        bar.setProgressTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        bar.setThumbTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        track.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- minus and plus, because a value you can only raise is a value you
        // cannot correct (the mockup's own note on its sliders). The glyphs are
        // the two strings the depth dialog already uses rather than a third pair
        // written here: a minus sign is a single character that is easy to get
        // wrong and impossible to notice.
        LinearLayout nudges = new LinearLayout(activity);
        nudges.setOrientation(LinearLayout.HORIZONTAL);
        TextView minus = nudgeButton(activity, R.string.depth_nudge_down);
        TextView plus = nudgeButton(activity, R.string.depth_nudge_up);
        LinearLayout.LayoutParams minusLp =
                new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 30));
        minusLp.rightMargin = dp(activity, 5); // .nudges { gap: 5px }
        nudges.addView(minus, minusLp);
        nudges.addView(plus, new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 30)));
        row.addView(nudges, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- wiring
        // One place to move the bar and repaint the track, because two is two
        // chances to disagree about what is stored.
        final Runnable[] paint = new Runnable[1];
        paint[0] = new Runnable() {
            @Override
            public void run() {
                int p = bar.getProgress();
                setProgressText(val, activity, p + min, min, max);
                // The width is not known until the track has been laid out, and
                // that happens after every progress change, so the fill is sized
                // against the measured width rather than a percentage.
                int w = track.getWidth();
                if (w > 0) {
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) fill.getLayoutParams();
                    lp.width = Math.round(w * p / (float) (max - min));
                    fill.setLayoutParams(lp);
                }
            }
        };
        // A layout pass changes the track's width without touching the SeekBar, so
        // repaint from the layout too -- otherwise the fill is sized against the
        // width from the previous pass and is wrong after any rotation.
        track.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or_, int ob) {
                paint[0].run();
            }
        });

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (syncing) return; // populating a control must never write
                paint[0].run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                prefs.edit().putInt(TiltController.KEY_GLIDE_MS, b.getProgress() + min).apply();
            }
        });

        // The direction lives on the button as a tag rather than in two near-identical
        // anonymous listeners, so the tag is set before the listener is attached.
        minus.setTag(Integer.valueOf(-NUDGE_BARS));
        plus.setTag(Integer.valueOf(NUDGE_BARS));
        View.OnClickListener step = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                int bars = tag instanceof Integer ? (Integer) tag : NUDGE_BARS;
                nudge(bar, clamp(bar.getProgress() + bars, 0, max - min), prefs, min);
            }
        };
        minus.setOnClickListener(step);
        plus.setOnClickListener(step);

        setSyncing(true);
        try {
            bar.setProgress(current - min);
        } finally {
            setSyncing(false);
        }
        paint[0].run();
        return row;
    }

    /**
     * Move the bar by a nudge, and store it.
     *
     * <p>A nudge has no "release" to wait for the way a drag does, so unlike a drag
     * it is persisted immediately: waiting for a touch-up that has already happened
     * would mean storing nothing at all.
     */
    private static void nudge(SeekBar bar, int p, SharedPreferences prefs, int min) {
        // Programmatic, so the change listener must not treat it as a drag -- but it
        // does have to be written here, since nothing else will.
        setSyncing(true);
        try {
            bar.setProgress(p);
        } finally {
            setSyncing(false);
        }
        prefs.edit().putInt(TiltController.KEY_GLIDE_MS, p + min).apply();
    }

    /**
     * The value, and whether it sits on the fill or beside it.
     *
     * <p>Two states, not two widgets: the mockup's {@code .row .val} and
     * {@code .row .over} are the same element with a different colour, and both
     * start 11px from the left. Below a twelfth of the track the fill is narrower
     * than the text, so the text has to leave the accent and be drawn in ink on
     * the grey instead -- the one place this slider is legible at its own
     * extremes.
     */
    private static void setProgressText(TextView val, MainActivity activity, int ms,
                                    int min, int max) {
        // The threshold is the mockup's: pct >= 12 in its slider(), which is where
        // the fill becomes wider than the text sitting in it. Computed from the
        // range this control was actually built with rather than re-derived from the
        // constants, so the two can never describe different ranges.
        float frac = (ms - min) / (float) (max - min);
        boolean onFill = frac >= 0.12f;
        val.setTextColor(onFill ? ON_FILL : Color.WHITE);
        val.setText(fmt(ms));
        val.setContentDescription(activity.getString(R.string.slider_glide) + ": " + fmt(ms));
    }

    private static String fmt(int ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
    }

    private static TextView nudgeButton(MainActivity activity, int glyphRes) {
        TextView t = new TextView(activity);
        t.setText(glyphRes);
        t.setTextSize(16);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(0x1AFFFFFF, dp(activity, 1), dp(activity, 8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 28),
                dp(activity, 30));
        t.setLayoutParams(lp);
        return t;
    }

    private static android.graphics.drawable.GradientDrawable round(
            int fill, int strokePx, int radiusPx) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, LINE);
        return g;
    }

    private static int dp(android.content.Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Guarded against the change listener firing on a programmatic
     * {@code setProgress}, which would write the value back on open -- so opening
     * Settings once would overwrite the glide the user set, with whatever the
     * slider happened to be sitting at.
     *
     * <p>Same pattern as {@code syncingZoom} in MainActivity, and it has to be a
     * real field written around the {@code setProgress} call: an earlier version
     * of this class declared the flag and a {@code setSyncing} helper that
     * nothing ever called, so the guard did nothing at all.
     */
    private static boolean syncing;

    static void setSyncing(boolean v) {
        syncing = v;
    }
}
