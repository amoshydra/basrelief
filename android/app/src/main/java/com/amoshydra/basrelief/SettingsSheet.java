package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
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
 * caption is {@code .caption}, the row is {@code .opt}, the aside is
 * {@code .note}, and the colours are {@code --line}, {@code --dim},
 * {@code --dimmer} and {@code --accent}. Where a colour is written as an int it
 * is that CSS value and not an approximation.
 *
 * <p><b>The switches are real switches, a deliberate departure from the
 * mockup.</b> The design draws its state mark as a 34dp tile with an accent dot
 * -- {@code .opt .sw} -- which reads as a picture or a colour chip rather than as
 * a control that can be moved. Material Components is not available ({@code :app}
 * depends only on {@code :core}; {@link ModalSheet} says why), so the honest
 * equivalent is the platform {@link android.widget.Switch} with the app's own
 * tints; see {@link ModalSheet#switchRow}. The row keeps the mockup's geometry:
 * the switch takes the swatch's leading slot rather than adding a trailing
 * column, so every settings row still reads the same way.
 *
 * <p><b>The grip drags.</b> It used to be a tap-only handle, on the assumption
 * that a Dialog could not be dragged. The drag is a transform inside the Dialog's
 * own window and cannot desync the studio's {@code GLSurfaceView}, which lives in
 * a different window -- {@link ModalSheet#grip} has the whole argument. A control
 * shaped like the studio sheet's handle has to behave like it.
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
     * The four toggles, named once.
     *
     * <p>A pref key as a literal at two call sites is a key that can be renamed at
     * one of them, which is a toggle that silently stops persisting. Each key lives
     * next to its one reader -- the three behaviours in {@link Settings}, and the 3D
     * mode in {@link ReliefRenderer}, which is the class that draws it -- and
     * nothing here spells a key out.
     */
    private static final String KEY_BATTERY_SAVER = Settings.KEY_BATTERY_SAVER_FOLLOW;
    private static final String KEY_CALIBRATE_ON_WAKE = Settings.KEY_CALIBRATE_ON_WAKE;
    private static final String KEY_SLEEP_WHEN_STILL = Settings.KEY_SLEEP_WHEN_STILL;
    // The 3D mode's key used to live here too. It moved onto the item (the sheet's
    // Renderer row), and the legacy pref is read once by AppState's migration, so
    // this screen no longer names it at all.

    /**
     * What a key nobody has written yet reads as. On, for all three.
     *
     * <p>{@link Settings} says why, and this is the same decision: each of the three
     * describes behaviour the app had before it was a switch, so a user upgrading
     * must not find their wallpaper behaving differently because a new key is absent.
     */
    private static final boolean DEFAULT_ON = true;

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
        // The handle is ModalSheet's own, installed above the scroll: it stays
        // reachable however far the content is scrolled, and its drag cannot fight
        // the scroll it does not overlap. It both taps and drags to close, so it
        // is an honest control rather than the decoration it used to be.
        sheet.grip(activity.getString(R.string.settings_close));
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

    /**
     * Ask before resetting, on the app's own card.
     *
     * <p>Delete in the gallery is the model: a destructive action asks first, in
     * the same dialog family every other question uses, and nothing about the
     * question is drawn with a platform {@code AlertDialog} whose theme this app
     * deliberately does not flip. Reset is worth a question because nothing it
     * removes is recoverable — every photo and its file, every playlist and
     * every setting.
     *
     * <p>{@link ModalSheet#show} installs the back handling itself
     * ({@code dismissOnBack}), so BACK closes the question instead of leaving
     * the Activity; closing means "do not reset", which is the safe direction.
     */
    private void confirmReset() {
        final ModalSheet confirm = new ModalSheet(activity, 0.88f);
        // The most destructive question in the app opts out of scrim-tap
        // dismissal. A tap on inert dimming is very often a mis-aimed tap meant for
        // the card, and this dialog's answer empties the gallery and restores the
        // demo -- losing the read-and-decide to a stray tap is not an acceptable
        // cost. Cancel and Reset are the ways out; the backdrop is not.
        confirm.setDismissOnScrimTap(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView title = ModalSheet.title(activity,
                activity.getString(R.string.settings_reset_title));
        // The family's 10dp under the title, the same gap the gallery's modals
        // put under theirs with no subtitle.
        title.setPadding(0, 0, 0, ModalSheet.dp(activity, 10));
        body.addView(title);
        body.addView(ModalSheet.subtitle(activity,
                activity.getString(R.string.settings_reset_message)));
        confirm.body(body);
        Button cancel = ModalSheet.button(activity,
                activity.getString(R.string.action_cancel), false, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm.dismiss();
            }
        });
        // Primary, because it is the reason this modal exists -- the same weight
        // the gallery gives the delete it confirms.
        Button reset = ModalSheet.button(activity,
                activity.getString(R.string.settings_reset), true, true);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm.dismiss();
                activity.resetApp();
            }
        });
        confirm.footerRow(cancel, reset);
        confirm.show(activity);
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

        // The gaps on this screen come from two places and nowhere else:
        //   - the row, caption and note spacing is ModalSheet's named token set
        //     (OPT_PAD_DP / OPT_INNER_GAP_DP / OPT_GAP_DP = .opt, CAPTION_TOP_DP
        //     and CAPTION_BOTTOM_DP = .caption, NOTE_TOP_DP = .note), so a row
        //     cannot carry a one-off margin;
        //   - the slider carries .row{margin:8px 0} itself (BasreliefSlider).
        // Nothing here invents a gap. A margin with no token behind it is exactly
        // how Reset came to sit flush against the diagnostics block below it.

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

        // ---- About & diagnostics. This is a fact, not a control, so it is a
        // readingRow: no border, no pressed state, no mark, quieter ink. It used to
        // share plainRow with Reset, which is why the one destructive button in the
        // app and the version string looked identical; readingRow's factory takes no
        // listener, so it cannot become a button by accident. The version is read
        // from the package manager, never hard-coded.
        box.addView(ModalSheet.caption(activity,
                activity.getString(R.string.group_about), false));
        box.addView(ModalSheet.readingRow(activity,
                activity.getString(R.string.app_name) + " " + versionName(),
                activity.getString(R.string.settings_licence)));

        // ---- The 3D mode is per item now, and lives on the sheet's geometry group
        // as "Renderer — Relief | Splat". It used to be a device-wide switch here in
        // About; a global render value is the wrong home (a gallery would draw every
        // photograph the same way whichever the single switch last said), so it moved
        // onto the row and the legacy pref is only the migration source. The Stage-0
        // card-grid dev toggle it also carried is no longer in the UI.

        // ---- Reset. The one destructive action here, so it asks first (see
        // confirmReset) and it carries the consequence on the plate itself. The
        // subtitle is settings_reset_message, the same string the confirm dialog
        // shows, so the row and the question cannot drift into two sentences about
        // one action. A bordered ghost plate, because that is what "tap me" looks
        // like everywhere else in the app (the fix line's actions): a destructive
        // action must not read as a tappable label. No chevron -- the confirm that
        // follows is a question, not a destination.
        box.addView(ModalSheet.actionRow(activity,
                activity.getString(R.string.settings_reset),
                activity.getString(R.string.settings_reset_message), false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmReset();
                    }
                }));

        // Diagnostics last, with no heading of its own. It is a panel of values rather
        // than a control, and the mockup puts it at the bottom on purpose: debug text
        // sprinkled through the controls reads as content, and content is what the user
        // came here to look at.
        box.addView(DiagnosticsBlock.build(activity));
        return box;
    }

    // ------------------------------------------------------------------- widgets

    /**
     * One switch row.
     *
     * <p>Built by {@link ModalSheet#switchRow}, so the row's shape, its platform
     * switch and the state it speaks are the shared card's, not this screen's.
     * What belongs here is only what a tap means: write the key, and let the
     * stored value stand. Nothing is repainted from a copy -- the switch carries
     * the state it was built with and the change listener reports the state the
     * widget actually moved to.
     */
    private View toggle(int titleRes, int subRes, String key) {
        return toggle(titleRes, subRes, key, DEFAULT_ON, null);
    }

    /**
     * The same row, for a switch that starts off and whose change has to reach a
     * reader that is already running.
     *
     * <p>{@code defaultOn} is a parameter rather than {@link #DEFAULT_ON} because
     * the 3D mode is the one switch here that must not arrive on for an upgrading
     * user: every row was framed against the relief, and this mode is a different
     * reconstruction of the same photograph rather than a fix to it.
     *
     * <p>{@code afterWrite} is what makes such a switch visible the moment it is
     * flipped. The battery and gyro behaviours are read at their next use -- the
     * next sensor wake, the next glide -- but the renderer holds the mode in a
     * field, so a switch that only wrote the pref would show the old look until the
     * app was restarted, which is the "control that displays a value the app
     * ignores" this screen exists to refuse.
     */
    private View toggle(int titleRes, int subRes, final String key, boolean defaultOn,
                        final Runnable afterWrite) {
        return ModalSheet.switchRow(activity,
                activity.getString(titleRes), activity.getString(subRes),
                isTrue(key, defaultOn),
                new ModalSheet.OnToggled() {
                    @Override
                    public void onToggled(boolean on) {
                        // apply() commits the in-memory map before it returns, so
                        // the next read is the value just written and not the one
                        // that was there.
                        prefs().edit().putBoolean(key, on).apply();
                        if (afterWrite != null) afterWrite.run();
                    }
                });
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