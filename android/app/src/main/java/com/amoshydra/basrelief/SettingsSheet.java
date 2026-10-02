package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The settings screen. Four groups, and nothing else.
 *
 * <p>Deliberately a separate file rather than more of MainActivity. MainActivity is
 * already the largest thing in the app and every remaining piece of work wanted to
 * go in it, which is the reason those pieces had to be done one at a time. A screen
 * with no state of its own does not belong in the class that owns all the state.
 *
 * <p><b>Nothing about a photograph appears here.</b> Depth, Zoom, Motion, framing
 * and the adjustments are all in the sheet, on the photo. Settings answers questions
 * about the device and the app; a photo is neither.
 *
 * <p>Four groups because four is what the questions actually are: Battery, Motion,
 * Gyro, About. Settings is where a product grows a second product, and the defence
 * is refusing anything that is not one of those.
 *
 * <p>Built against the signed-off mockup: {@code .design/prototype/final.html},
 * Settings tab, and its CSS is the specification for the numbers here -- the
 * caption is {@code .caption}, the row is {@code .opt}, and the accents are
 * {@code --line}, {@code --dim}, {@code --dimmer} and {@code --accent}. Where a
 * colour is written as an int here it is that CSS value and not an approximation.
 */
final class SettingsSheet {

    /**
     * The three toggles, named once.
     *
     * <p>A pref key as a literal at two call sites is a key that can be renamed at
     * one of them, which is a toggle that silently stops persisting.
     *
     * <p><b>These three are stored and read back by this screen, and by nothing
     * else yet.</b> They are named here as the contract the sensor code is to read,
     * rather than inlined where it will be wired up, so that whoever does that does
     * not have to go and find the spelling. A control that changes nothing is the
     * failure this project rules out, and the fix is the reader side, not the
     * removal of the switch: the mockup is signed off with all four groups, and a
     * missing toggle is a worse lie than a pending one.
     */
    // The keys live in Settings, next to the code that reads them. A key string
    // duplicated here and there compiles fine, flips the switch, and changes nothing.
    private static final String KEY_BATTERY_SAVER = Settings.KEY_BATTERY_SAVER_FOLLOW;
    private static final String KEY_CALIBRATE_ON_WAKE = Settings.KEY_CALIBRATE_ON_WAKE;
    private static final String KEY_SLEEP_WHEN_STILL = Settings.KEY_SLEEP_WHEN_STILL;

    private final MainActivity activity;
    private android.app.Dialog dialog;

    private SettingsSheet(MainActivity activity) {
        this.activity = activity;
    }

    /** Show the settings screen over the studio. */
    static void show(MainActivity activity) {
        new SettingsSheet(activity).present();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ chrome

    /**
     * One option row: the switch, then the title and its subtitle.
     *
     * <p>The switch is on the LEFT and the text follows it, which is the order the
     * mockup draws and the order a thumb expects; it used to be a checkmark button
     * on the right, so reading a row meant reading past the answer to find the
     * question.
     *
     * <p>Rounded, hairline border, faint fill: the three things that make a row
     * read as a row rather than as two loose lines of text. The border is the
     * only one of the three that carries structure at all, so it is the mockup's
     * {@code --line} (rgba(255,255,255,.14)) exactly rather than a guess.
     *
     * @param key the pref this row owns, or null for a row that only reports. The
     *            row repaints its own swatch from storage after a tap rather than
     *            rebuilding the screen: a full re-open showed the stored value, but
     *            it also threw away the scroll position and the dialog with it, and
     *            a user comparing two toggles would lose their place each time.
     */
    private LinearLayout row(String title, String subtitle, String key, boolean fallback) {
        boolean on = key != null && isTrue(key, fallback);
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(12);
        r.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor(0x0AFFFFFF);
        bg.setStroke(dp(1), 0x24FFFFFF);
        r.setBackground(bg);

        final FrameLayout holder = new FrameLayout(activity);
        holder.addView(swatch(on));
        r.addView(holder, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        // 12px between the switch and the words, .opt { gap:12px }.
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD); // .opt b
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText(subtitle);
            s.setTextSize(12);
            s.setTextColor(0x8CFFFFFF); // --dim
            text.addView(s);
        }
        r.addView(text, textLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8); // .opt { margin-bottom:8px }
        r.setLayoutParams(lp);

        if (key != null) {
            final String k = key;
            final boolean fb = fallback;
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = !isTrue(k, fb);
                    prefs().edit().putBoolean(k, now).apply();
                    // Repaint from what was STORED rather than from `now`. Reading
                    // back is the only way the swatch can disagree with the pref,
                    // and the pref is the truth everything else reads.
                    holder.removeAllViews();
                    holder.addView(swatch(isTrue(k, fb)));
                }
            });
        }
        return r;
    }

    /**
     * The on/off swatch: a 34dp tile with an accent badge on its bottom-right
     * corner when the setting is on.
     *
     * <p>The mockup draws the badge as a filled circle that overhangs the tile and
     * is ringed in the panel colour, so it reads as sitting ON the tile rather
     * than inside it. That ring is why the badge is offset by -3dp: without it the
     * badge is clipped by nothing but is the same size as one drawn flush, and the
     * distinction between "on" and "a slightly brighter square" disappears.
     *
     * <p>A {@code View} with a drawable rather than a {@code Button} with a "✓":
     * the glyph was the only reason the row needed a platform widget, and a Button
     * brings its own minimum width, insets and background that all had to be
     * overridden to get back to 34dp.
     */
    private View swatch(boolean on) {
        FrameLayout f = new FrameLayout(activity);
        android.graphics.drawable.GradientDrawable tile =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF3D5563, 0xFF1B262E});
        tile.setCornerRadius(dp(9));
        f.setBackground(tile);
        if (on) {
            View badge = new View(f.getContext());
            android.graphics.drawable.GradientDrawable dot =
                    new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(0xFF7FD1C1);
            // 2px of the panel colour, so the badge reads as on top of the tile.
            dot.setStroke(dp(2), 0xFF0E1115);
            badge.setBackground(dot);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(14), dp(14));
            blp.rightMargin = -dp(3);
            blp.bottomMargin = -dp(3);
            blp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            f.addView(badge, blp);
        }
        // Spoken, because a coloured dot is not a state a screen reader can read.
        f.setContentDescription(activity.getString(
                on ? R.string.settings_state_on : R.string.settings_state_off));
        return f;
    }

    /**
     * A group heading. Four of these, one per group, and they are the only thing
     * on the screen that is a heading.
     *
     * <p>{@code .caption}: 11sp, letter-spacing .09em, uppercase, --dimmer, bold,
     * 16px above and 7px below. The first one is normally 2px from the top rather
     * than 16 -- the mockup's {@code :first-child} rule -- which matters because
     * here the heading is preceded by the title row rather than by the top edge, so
     * all four take the 16px.
     *
     * <p>Uppercase plus letter spacing is what makes a heading read as a heading at
     * 11sp. Without it the four group titles look like more slider labels and the
     * page stops being four groups.
     */
    private TextView caption(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTextColor(0x57FFFFFF); // --dimmer
        t.setPadding(dp(4), dp(16), dp(4), dp(7));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    // ------------------------------------------------------------------ build

    private View build() {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xF20E1115);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);

        // ---- title
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(R.string.settings_title);
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(activity);
        done.setText(R.string.action_done);
        done.setTextColor(0x8CFFFFFF);
        done.setTextSize(15);
        done.setPadding(dp(12), dp(8), dp(4), dp(8));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        head.addView(done);
        box.addView(head);

        // ---- Battery. One row, because the one question is whether the app obeys the
        // OS setting or not; there is nothing else about power to decide here.
        box.addView(caption(activity.getString(R.string.group_battery)));
        box.addView(row(activity.getString(R.string.settings_follow_battery_saver),
                activity.getString(R.string.settings_follow_battery_saver_sub),
                KEY_BATTERY_SAVER, true));

        // ---- Motion. One group, one control: the glide rate. Depth, Zoom and Motion
        // are all per-photograph and live in the sheet on the photo, so this group
        // holds the one motion setting that is about the device.
        box.addView(caption(activity.getString(R.string.group_motion)));
        box.addView(GlideControl.build(activity, prefs()));
        TextView glideNote = new TextView(activity);
        glideNote.setText(R.string.settings_glide_note);
        glideNote.setTextSize(11);
        glideNote.setTextColor(0x57FFFFFF);
        // Centred and dimmer, .note in the mockup: it is an aside, and it sits
        // under the control it qualifies rather than beside the next group.
        glideNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(7);
        glideNote.setLayoutParams(noteLp);
        box.addView(glideNote);

        // ---- Gyro. All four group headings go through caption(), which is the
        // whole reason they read as headings rather than as four more labels; see
        // there for the treatment and why it is not optional.
        box.addView(caption(activity.getString(R.string.group_gyro)));
        box.addView(row(activity.getString(R.string.settings_calibrate_wake),
                activity.getString(R.string.settings_calibrate_wake_sub),
                KEY_CALIBRATE_ON_WAKE, true));
        box.addView(row(activity.getString(R.string.settings_sleep_still),
                activity.getString(R.string.settings_sleep_still_sub),
                KEY_SLEEP_WHEN_STILL, true));

        // ---- About & diagnostics
        box.addView(caption(activity.getString(R.string.group_about)));
        // No key: the mockup's About row carries an empty swatch and is a fact, not
        // a switch, so it must not accept a tap. Passing a key here would make the
        // whole row look live and do nothing.
        box.addView(row(activity.getString(R.string.app_name) + " " + versionName(),
                activity.getString(R.string.settings_licence), null, false));
        box.addView(DiagnosticsBlock.build(activity));

        scroll.addView(box);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    // -------------------------------------------------------------- persistence

    /**
     * The mockup's About row reads "Basrelief 0.1.0" -- the name and the version.
     *
     * <p>Asked of the package manager rather than hard-coded, so the row cannot
     * quote a version the APK does not have; that is the same class of bug as the
     * status line claiming a depth map the renderer is not using.
     */
    private String versionName() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Read a boolean setting.
     *
     * <p>Defensive for the same reason the glide read is: a pref restored from a
     * backup can hold a String under a key that is a boolean here, and
     * {@code getBoolean} throws rather than falling back. A throw here would take
     * down the whole settings screen, which is a far worse outcome than showing one
     * row at its default.
     */
    private boolean isTrue(String key, boolean fallback) {
        try {
            return prefs().getBoolean(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * A dialog rather than a view added to the studio's own root.
     *
     * <p>A Dialog brings its own window, so the settings screen does not have to know
     * what the studio's view tree looks like, cannot be laid out underneath the
     * sheet, and cannot leave a half-attached view behind if the activity is
     * recreated. It also means nothing here has to be added to -- or removed from --
     * MainActivity.
     */
    private void present() {
        android.app.Dialog d = new android.app.Dialog(activity,
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        d.setContentView(build());
        dialog = d;
        d.show();
    }

    private void hide() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}