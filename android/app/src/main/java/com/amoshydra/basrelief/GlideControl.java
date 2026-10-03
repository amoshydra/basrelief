package com.amoshydra.basrelief;

import android.content.SharedPreferences;
import android.view.View;

import java.util.Locale;

/**
 * The one glide rate, as a {@link BasreliefSlider}.
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
 * <p><b>It is a configuration of the common slider, not a second one.</b> This
 * class used to hand-roll its own track, value and nudges, and that copy drifted
 * the moment it was written: its label, track and nudge pair sat flush against
 * each other, while every slider in the studio sheet put the mockup's 9dp
 * ({@code .row} / {@code .nudges} gap) between them, because the gap was written
 * twice. It now passes its range, formatting and one write callback to
 * {@link BasreliefSlider} and draws nothing itself, so the same label column,
 * track and nudge geometry cannot be right on one screen and wrong on the other.
 */
final class GlideControl {

    private GlideControl() {
    }

    /**
     * Build the slider. Persists to {@code glide_ms} under the app prefs.
     *
     * <p>Written once per finished gesture rather than per step of travel, for
     * the same reason every other slider does it: a drag would otherwise
     * re-read and re-validate the whole library once per step. A nudge is a
     * finished gesture with no release to wait for, so it is written too --
     * both arrive here through {@link BasreliefSlider.OnChanged}.
     */
    static View build(final MainActivity activity, final SharedPreferences prefs) {
        // Defensive on the read as well as the write: a pref written as a String by
        // an older build, or by a restored backup, throws out of getInt and would
        // take the whole settings screen down rather than showing one wrong number.
        int current = TiltController.GLIDE_DEFAULT_MS;
        try {
            current = prefs.getInt(TiltController.KEY_GLIDE_MS, current);
        } catch (Exception e) {
            // leave the default
        }
        return BasreliefSlider.create(activity, TiltController.KEY_GLIDE_MS, current,
                TiltController.GLIDE_MIN_MS, TiltController.GLIDE_MAX_MS,
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        // Seconds with one decimal, e.g. "1.2s". Locale.US so the
                        // separator is the dot the rest of the app writes; the
                        // value is milliseconds because that is what TiltController
                        // stores and what the slider's range is declared in.
                        return String.format(Locale.US, "%.1fs", value / 1000f);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        prefs.edit().putInt(TiltController.KEY_GLIDE_MS, value).apply();
                    }
                }).view();
    }
}
