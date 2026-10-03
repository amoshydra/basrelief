package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;

/**
 * Which screen, chosen at the moment of applying and never before.
 *
 * <p>A live wallpaper is <b>bound</b> to a screen. The same photo behind a lock-screen
 * clock is a different render, which is the whole reason surfaces exist. But the
 * choice cannot live in a persistent control: before you have applied, there is
 * nothing to choose between. So this appears only when you press Set as wallpaper.
 *
 * <p><b>Nothing in the app can re-bind a live wallpaper.</b> Measured, not assumed:
 * {@code setWallpaperComponent} is refused by {@code WallpaperManagerService} with a
 * {@code SecurityException}, and the permission it wants is {@code signature|privileged},
 * so declaring it changes nothing. The system picker is therefore the only route, and
 * that is what makes "Set as wallpaper" the apply gesture rather than a convenience.
 *
 * <p><b>Why the lock option is dimmed rather than missing.</b> This is the opposite
 * decision to the sheet's surface segment, and the difference is deliberate. The
 * segment is absent when there is nothing to switch <i>between</i> — on one surface,
 * a two-way toggle has no second position. Here the user is being asked what they
 * want and the platform is refusing one of the answers, so the honest thing is to
 * show the answer and say why it is not available. A missing row reads as a bug; a
 * dimmed one reads as a constraint.
 *
 * <p>Choosing lock records it as applied <b>optimistically</b>, because the system
 * picker runs after this dialog and the app cannot observe what the user picks there.
 * That is the only claim available, and it is the user's stated intent rather than a
 * measurement — {@code Library.withAppliedTo} documents that distinction and refuses
 * the one case the app cannot honour (the bundled demo, which has no image to render
 * on a second engine).
 *
 * <p><b>Built on {@link ModalSheet}.</b> This screen used to hand-roll its own scrim
 * and card, which is how it came to have a fourth scrim alpha (0.55 against the
 * mockup's 0.62), a second bottom-inset listener and a primary button in the accent
 * colour while the identical Cancel/Continue pair one gesture away was white. The
 * card, the scrim, the navigation-bar inset and the back handling now exist in one
 * place; what is left here is the question and the answer.
 */
final class ApplyChooser {

    /** The mockup's {@code .modal{max-height:88%}}. */
    private static final float MAX_FRACTION = 0.88f;

    // The note ink. The title, rows and buttons are ModalSheet's now; this one is
    // the qualifier under the list, which no shared factory draws.
    private static final int DIMMER = 0x57FFFFFF;

    private final Activity activity;

    /** The one window. Both selections live in it. */
    private ModalSheet sheet;

    /** The note that only matters once Lock is chosen. */
    private TextView lockNote;

    private ApplyChooser(Activity activity) {
        this.activity = activity;
    }

    static void show(Activity activity) {
        new ApplyChooser(activity).present();
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private String str(int res) {
        return activity.getString(res);
    }

    // ------------------------------------------------------------------ build

    private void present() {
        java.util.Set<Surface> applied = new java.util.LinkedHashSet<Surface>();
        try {
            Library lib = AppState.load(Settings.prefs(activity), activity.getFilesDir()).library;
            applied.addAll(lib.appliedSurfaces());
        } catch (Exception e) {
            // Nothing recorded yet, so fall through to the default below.
        }
        final boolean lockSupported = SurfaceResolver.supportsPerSurfaceLock(
                android.os.Build.VERSION.SDK_INT);
        if (applied.isEmpty()) {
            // HOME ONLY. Defaulting to both was wrong, and the truth table says why:
            // one wallpaper on home AND lock is ONE engine, one window, one set of
            // values -- so the lock screen composites the home engine and the Lock
            // settings are never rendered by anything. Defaulting to both therefore
            // steered every user into the one state where per-surface settings cannot
            // work, while the sheet offered a switch that stored values nothing read.
            //
            // Separate surfaces need two separate applications, each choosing its own
            // destination in the system dialog. That is more steps, and it is the only
            // route there is: re-binding is signature|privileged.
            applied.add(Surface.HOME);
        } else if (!lockSupported) {
            // A restore can hand back a lock surface this platform cannot render.
            applied.remove(Surface.LOCK);
        }

        // TWO selections, not one.
        //
        // <p>This was single-select, on the reasoning that "the picker binds one
        // component to one screen". True of the picker, and irrelevant here: the
        // picker is asked once, and Android 15+ offers Home / Lock / Both. Requiring
        // two separate applications to reach "both" made the whole per-surface feature
        // undiscoverable -- the sheet's Home/Lock switch stays hidden until the photo
        // is on both, and nothing anywhere said that getting there takes two goes
        // through a system dialog this app cannot observe. Reported as "I don't
        // understand home vs lock".
        //
        // <p>Both default to on, because both is what someone editing a photo's lock
        // settings wants, and on the platform version where that is possible.
        final java.util.Set<Surface> chosen =
                new java.util.LinkedHashSet<Surface>(applied);
        final ModalSheet.OptionRow[] homeOpt = new ModalSheet.OptionRow[1];
        final ModalSheet.OptionRow[] lockOpt = new ModalSheet.OptionRow[1];

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(ModalSheet.title(activity, str(R.string.apply_title)));
        body.addView(ModalSheet.subtitle(activity, str(R.string.apply_subtitle)));

        homeOpt[0] = ModalSheet.option(activity, str(R.string.apply_home),
                str(R.string.apply_home_sub), chosen.contains(Surface.HOME), false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggle(chosen, Surface.HOME, homeOpt[0], lockOpt[0], lockSupported);
                    }
                });
        body.addView(homeOpt[0]);

        if (lockSupported) {
            lockOpt[0] = ModalSheet.option(activity, str(R.string.apply_lock),
                    str(R.string.apply_lock_sub), chosen.contains(Surface.LOCK), false,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggle(chosen, Surface.LOCK, homeOpt[0], lockOpt[0], lockSupported);
                        }
                    });
        } else {
            // Dimmed, with the reason, and NOT tappable. The opacity is the
            // mockup's .locked -- a whole row at 42% rather than just the text, so
            // it reads as unavailable rather than as a label.
            lockOpt[0] = ModalSheet.option(activity, str(R.string.apply_lock),
                    str(R.string.apply_lock_unavailable), false, true, null);
        }
        body.addView(lockOpt[0]);

        lockNote = note(str(R.string.apply_lock_note_separate), 11f, 8);
        lockNote.setVisibility(View.GONE);
        body.addView(lockNote);
        if (!lockSupported) {
            body.addView(note(str(R.string.apply_lock_note), 12f, 10));
        }
        updateLockNote(chosen);

        sheet = new ModalSheet(activity, MAX_FRACTION);
        sheet.body(body);

        // Cancel / Continue. One ModalSheet footer, so the plate colours, the 8dp
        // gutter and the heights are the same pair as every other question in the
        // app; the apply screen's primary used to be accent-filled, which is why
        // the same Cancel/Continue looked like two designs one gesture apart.
        Button cancel = ModalSheet.button(activity, str(R.string.action_cancel), false, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        Button proceed = ModalSheet.button(activity, str(R.string.apply_continue), true, true);
        proceed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recordAndLaunch(chosen);
            }
        });
        sheet.footerRow(cancel, proceed);
        sheet.show(activity);
    }

    /**
     * A qualifier under the list: 11 or 12px, the dimmer ink, wrapped.
     *
     * <p>Not {@link ModalSheet#note}: that is the mockup's centred aside, and these
     * are the reason a whole option cannot be chosen, which reads as a footnote and
     * is deliberately left-aligned with the rows above it.
     */
    private TextView note(String text, float sizeSp, int topDp) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(DIMMER);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(2), dp(topDp), dp(2), 0);
        return t;
    }

    // ------------------------------------------------------------------ outcome

    /**
     * Record the intent, then hand off to the system.
     *
     * <p>The record is written <b>before</b> the picker is launched, because the
     * picker can end any way and this app cannot observe how. It is the user's stated
     * intent and is monotonic — {@code withAppliedTo} ignores a false and a repeat —
     * so a later refusal costs a claim that was never going to be rendered rather
     * than an inconsistency.
     */
    private void recordAndLaunch(java.util.Set<Surface> surfaces) {
        if (surfaces == null || surfaces.isEmpty()) return;
        try {
            SharedPreferences prefs = Settings.prefs(activity);
            Library current = AppState.load(prefs, activity.getFilesDir()).library;
            Library next = current;
            for (Surface surface : surfaces) {
                next = next.withAppliedTo(surface, true);
            }
            if (next != current) {
                LibraryStore.saveNow(prefs, next);
                // The sheet's segment is built with the sheet and there is no
                // buildSheet() to re-run, so it appears on the next open -- straight
                // after the system picker. Saying so is what stops the user's next
                // question being "where did the switch go".
                if (activity instanceof MainActivity) {
                    if (surfaces.size() > 1) {
                        ((MainActivity) activity).onAppliedToBoth();
                    }
                    // Below 35 this is the only thing that will ever put the photo on
                    // the lock screen, and the chooser's own wording promises it.
                    if (surfaces.contains(Surface.LOCK)) {
                        ((MainActivity) activity).onLockSurfaceChosen();
                    }
                }
            }
        } catch (Exception e) {
            // Recording is bookkeeping. Failing it must not stop the user applying.
            android.util.Log.w("Basrelief", "apply record failed: " + e);
        }
        dismiss();
        launchPicker();
    }

    private void updateLockNote(java.util.Set<Surface> chosen) {
        if (lockNote == null) return;
        lockNote.setVisibility(chosen.contains(Surface.LOCK) ? View.VISIBLE : View.GONE);
    }

    /**
     * Flip one surface, and never leave nothing chosen.
     *
     * <p>"Apply to nothing" is not an outcome the button should offer, and below 35
     * Lock cannot be the only choice either -- there is no second engine to bind.
     */
    private void toggle(java.util.Set<Surface> chosen, Surface surface,
                        ModalSheet.OptionRow home, ModalSheet.OptionRow lock,
                        boolean lockSupported) {
        if (chosen.contains(surface)) {
            if (chosen.size() == 1) return;
            if (surface == Surface.LOCK && !lockSupported) return;
            chosen.remove(surface);
        } else {
            chosen.add(surface);
        }
        home.setMarked(chosen.contains(Surface.HOME));
        lock.setMarked(chosen.contains(Surface.LOCK));
        updateLockNote(chosen);
    }

    private void launchPicker() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                DepthWallpaperService.wallpaperComponent(activity));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Some OEM builds have no live-wallpaper change intent. The chooser is
            // the older route; if that is missing too, the only honest answer is to
            // name the place the user has to go themselves.
            try {
                activity.startActivity(
                        new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(activity, R.string.apply_manual_hint, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
    }
}
