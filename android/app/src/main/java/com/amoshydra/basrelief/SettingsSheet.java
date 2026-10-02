package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

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
 * caption is {@code .caption}, the row is {@code .opt}, the switch is
 * {@code .opt .sw}, the aside is {@code .note}, and the accents are {@code --line},
 * {@code --dim}, {@code --dimmer} and {@code --accent}. Where a colour is written as
 * an int here it is that CSS value and not an approximation.
 *
 * <p><b>A sheet, in a Dialog window.</b> The mockup draws this screen as the same
 * {@code .sheet} the studio uses -- panel colour, 22dp top corners, a grip, most of
 * the display -- and that shape is what makes it read as glass over a live render
 * rather than as a page. It is built on {@link ModalSheet} at 0.88 so it is the same
 * card as the apply and the depth screens, which are one gesture away from it, rather
 * than a fourth one written here.
 *
 * <p>It stays a <b>Dialog</b>, and that is a deliberate trade rather than an
 * oversight. A Dialog brings its own window, so the mockup's shape -- a sibling view
 * over the {@code GLSurfaceView} -- cannot happen to it, and that shape is documented
 * to stop compositing entirely (b/7179570, recorded in AOSP 2012 and still in the
 * API 27 sources): the view is placed, opaque, attached, {@code isShown()}, in a
 * window that has drawn hundreds of frames, receiving every tap and absent from the
 * display. The cost is the render behind it -- a Dialog window is its own surface, so
 * this screen sits on a dimmed studio rather than on the photograph. That is the
 * right way round: a correctly compositing modal over an invisible photograph is a
 * worse outcome than a visible photograph behind a modal that never appears. It is
 * also why the card is {@link ModalSheet}'s card rather than the studio sheet's own
 * panel; see {@link #present} for the numbers.
 *
 * <p><b>No header.</b> The design has none: the first thing in the sheet is the grip
 * and the first content row is {@code caption Battery}. There was a 16sp title and a
 * 15sp Done here, which is a full-width line the mockup does not draw and which
 * pushed the first group down by the height of itself. Back already dismisses and so
 * does the grip; the Done that remains is in the foot, under the content it closes,
 * rather than above the first question.
 */
final class SettingsSheet {

    /**
     * The three toggles, named once.
     *
     * <p>A pref key as a literal at two call sites is a key that can be renamed at
     * one of them, which is a toggle that silently stops persisting. The keys live
     * in {@link Settings} next to the one reader each has, and nothing here spells a
     * key out.
     */
    private static final String KEY_BATTERY_SAVER = Settings.KEY_BATTERY_SAVER_FOLLOW;
    private static final String KEY_CALIBRATE_ON_WAKE = Settings.KEY_CALIBRATE_ON_WAKE;
    private static final String KEY_SLEEP_WHEN_STILL = Settings.KEY_SLEEP_WHEN_STILL;

    /**
     * What a key nobody has written yet reads as. On, for all three.
     *
     * <p>{@link Settings} says why, and this is the same decision: each of the three
     * describes behaviour the app had before it was a switch, so a user upgrading
     * must not find their wallpaper behaving differently because a new key is absent.
     */
    private static final boolean DEFAULT_ON = true;

    /** The mockup's grip bar: rgba(255,255,255,.42). */
    private static final int GRIP_BAR = 0x6BFFFFFF;

    private final MainActivity activity;
    private ModalSheet sheet;

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

    // ------------------------------------------------------------------- present

    /**
     * Present it.
     *
     * <p><b>0.88</b> is the mockup's {@code .modal { max-height: 88% }}. The card
     * itself is {@link ModalSheet}'s -- {@code 0xFF171B21} at 20dp top corners, where
     * the studio's own sheet is {@code 0xEB161A20} at 22dp -- and that difference is
     * deliberate rather than missed. That card is shared with the apply, depth and
     * playlist screens, which are the same primitive one gesture away; a second card
     * definition here is exactly the drift {@link ModalSheet} exists to stop. And given
     * the Dialog window, there is nothing behind the card to blend with, so an opaque
     * {@code #171B21} and a 92% {@code #161A20} are within about one percent per
     * channel of each other on screen. Making this the studio's panel is a change to
     * {@link ModalSheet}, with a flag so the other three keep theirs -- not something
     * to do from inside a screen that uses it.
     *
     * <p>{@code body} is called once and only once: it adds the scroll to the card,
     * so a second call is a second scroll rather than a replacement.
     */
    private void present() {
        sheet = new ModalSheet(activity, 0.88f);
        sheet.body(buildBody());

        // The one visible way out, and {@code .btn.ghost} rather than a filled plate:
        // this button closes a screen and changes nothing on it, so it should not
        // carry the weight of the primary action.
        Button done = ModalSheet.button(activity, activity.getString(R.string.action_done),
                false, true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        sheet.footerRow(done);
        sheet.show(activity);
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
    }

    // ---------------------------------------------------------------------- body

    /**
     * The four groups, in the mockup's order: Battery, Motion, Gyro, About.
     *
     * <p>Nothing between the grip and {@code caption Battery}, because the design has
     * nothing there -- see the header note in the class javadoc.
     */
    private View buildBody() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(grip());

        // ---- Battery. One row, because the one question is whether the app obeys the
        // OS setting or not; there is nothing else about power to decide here.
        // `first` is the mockup's `.caption:first-child`: in the design the grip is a
        // sibling of the scroll rather than inside it, so the first caption is the
        // first thing in the scroll and takes 2px rather than 16.
        box.addView(ModalSheet.caption(activity,
                activity.getString(R.string.group_battery), true));
        box.addView(toggle(R.string.settings_follow_battery_saver,
                R.string.settings_follow_battery_saver_sub, KEY_BATTERY_SAVER));

        // ---- Motion. One group, one control: the glide rate. Depth, Zoom and Motion
        // are all per-photograph and live in the sheet on the photo, so this group
        // holds the one motion setting that is about the device.
        box.addView(ModalSheet.caption(activity,
                activity.getString(R.string.group_motion), false));
        box.addView(GlideControl.build(activity, prefs()));
        // .note: 11px, --dimmer, centred, under the control it qualifies rather than
        // beside the next group. It says what the one rate is FOR, which is the part
        // a user cannot guess: re-centring and home-to-lock are the same motion, and
        // a user who thinks they are two settings is wrong in a way nothing else on
        // this screen would correct.
        box.addView(ModalSheet.note(activity,
                activity.getString(R.string.settings_glide_note)));

        // ---- Gyro. Both rows are the sensor's own lifecycle: when it re-reads
        // neutral, and when it stops.
        box.addView(ModalSheet.caption(activity,
                activity.getString(R.string.group_gyro), false));
        box.addView(toggle(R.string.settings_calibrate_wake,
                R.string.settings_calibrate_wake_sub, KEY_CALIBRATE_ON_WAKE));
        box.addView(toggle(R.string.settings_sleep_still,
                R.string.settings_sleep_still_sub, KEY_SLEEP_WHEN_STILL));

        // ---- About & diagnostics. This row is a fact, not a setting, so its swatch is
        // the same tile with no accent dot -- `false`, not a dimmed one -- and it takes
        // no tap. The null listener is what makes ModalSheet's row non-clickable, so
        // this cannot drift into a live-looking row that does nothing.
        box.addView(ModalSheet.caption(activity,
                activity.getString(R.string.group_about), false));
        box.addView(ModalSheet.option(activity,
                activity.getString(R.string.app_name) + " " + versionName(),
                activity.getString(R.string.settings_licence), false, false, null));

        // Diagnostics last, with no heading of its own. It is a panel of values rather
        // than a control, and the mockup puts it at the bottom on purpose: debug text
        // sprinkled through the controls reads as content, and content is what the user
        // came here to look at.
        box.addView(DiagnosticsBlock.build(activity));
        return box;
    }

    /**
     * The sheet's handle: a 38x4 bar centred in a 22dp strip, as the mockup draws it.
     *
     * <p><b>It collapses, on a tap.</b> The mockup's grip drags the studio's sheet
     * down, and this screen is a Dialog, which cannot be dragged -- a grip that only
     * <i>looked</i> draggable would advertise a gesture that does nothing, and a
     * control that changes nothing is the failure this app rules out. Tapping it
     * collapses the sheet, which is what the mockup's own handler does
     * ("drag down to collapse"): the same outcome, by the gesture the platform can
     * actually deliver. So the bar is not decoration and is not a lie.
     *
     * <p>It is the first child of the body rather than pinned above it, because
     * {@link ModalSheet} has exactly one scroll and that is where a caller's content
     * goes. The difference is that the bar leaves with the content once the sheet has
     * been scrolled, which no amount of arrangement inside one scroll can prevent.
     */
    private View grip() {
        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        // .grip { height: 22px; align-items: center; justify-content: center }. No
        // band behind it: a full-width translucent fill across the top edge reads as
        // a header, which is the thing this screen just deleted.
        strip.setGravity(Gravity.CENTER);
        strip.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ModalSheet.dp(activity, 22)));

        View bar = new View(activity);
        bar.setBackgroundColor(GRIP_BAR);
        strip.addView(bar, new LinearLayout.LayoutParams(
                ModalSheet.dp(activity, 38), ModalSheet.dp(activity, 4)));

        strip.setContentDescription(activity.getString(R.string.settings_close));
        strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        return strip;
    }

    // ------------------------------------------------------------------- widgets

    /**
     * One switch row.
     *
     * <p>The row's shape is {@link ModalSheet#option}'s, so a settings row cannot
     * drift from the three other screens built on the same card. Only the state is
     * ours, and only because the design's switch <i>is</i> the swatch: {@code .opt .sw}
     * is a tile with a dot on it, not a track with a thumb, so there is no separate
     * widget to put state on.
     *
     * <p>A tap repaints this row rather than rebuilding the screen. A full re-open
     * showed the stored value too, but it threw the scroll position and the dialog
     * with it, and a user comparing two toggles would lose their place each time.
     *
     * <p>Repainted from what was <b>stored</b>, not from the value the tap computed:
     * reading back is the only way the swatch can disagree with the pref, and the
     * pref is the truth everything else reads.
     */
    private View toggle(int titleRes, int subRes, final String key) {
        // Built first, attached second: the listener needs the row it repaints, and a
        // row cannot be named inside the expression that creates it.
        final LinearLayout row = ModalSheet.option(activity,
                activity.getString(titleRes), activity.getString(subRes),
                isTrue(key, DEFAULT_ON), false, null);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // apply() commits the in-memory map before it returns, so the read
                // below is the value just written and not the one that was there.
                prefs().edit().putBoolean(key, !isTrue(key, DEFAULT_ON)).apply();
                repaint(row, isTrue(key, DEFAULT_ON));
            }
        });
        announce(row, isTrue(key, DEFAULT_ON));
        return row;
    }

    /**
     * Move the row's own dot, and nothing else.
     *
     * <p>The swatch is the row's first child and the dot is that child's first child,
     * and the dot is the only thing in the row that carries state -- which is why
     * this is two {@code getChildAt}s and not a rebuild of the row.
     */
    private void repaint(LinearLayout row, boolean on) {
        View dot = swatchOf(row);
        if (dot == null) return;
        Drawable d = dot.getBackground();
        if (d instanceof GradientDrawable) {
            // Transparent rather than a dimmed accent, because that is exactly what
            // ModalSheet draws for "off" when the row is built; anything else and the
            // same stored value is drawn two different ways.
            ((GradientDrawable) d).setColor(on ? ModalSheet.ACCENT : Color.TRANSPARENT);
        }
        announce(row, on);
    }

    /**
     * The dot inside the swatch at a row's leading edge, or null.
     *
     * <p>Guarded rather than cast blind: a swatch drawn without a dot would otherwise
     * take this whole screen down on a tap, which is a worse outcome than one row
     * that quietly loses its badge.
     */
    private static View swatchOf(View row) {
        if (!(row instanceof ViewGroup)) return null;
        View swatch = ((ViewGroup) row).getChildAt(0);
        if (swatch == null) return null;
        if (!(swatch instanceof ViewGroup)) return null;
        return ((ViewGroup) swatch).getChildAt(0);
    }

    /**
     * Say the state out loud, because an accent dot is not something anyone can hear.
     *
     * <p>API 30 gave views a state channel, and the row carries On or Off there, so a
     * screen reader reads the title and the subtitle as themselves and the state as
     * the state. Below 30 there is no such channel, so the swatch -- the tile, which
     * is the thing that changed -- describes itself instead, which is what this screen
     * did before the state description existed.
     */
    private void announce(View row, boolean on) {
        String state = activity.getString(
                on ? R.string.settings_state_on : R.string.settings_state_off);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            row.setStateDescription(state);
        } else if (row instanceof ViewGroup) {
            View swatch = ((ViewGroup) row).getChildAt(0);
            if (swatch != null) swatch.setContentDescription(state);
        }
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
}