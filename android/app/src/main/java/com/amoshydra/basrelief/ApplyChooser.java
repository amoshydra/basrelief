package com.amoshydra.basrelief;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
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
 */
final class ApplyChooser {

    private final Activity activity;
    private Dialog dialog;

    private ApplyChooser(Activity activity) {
        this.activity = activity;
    }

    static void show(Activity activity) {
        new ApplyChooser(activity).present();
    }

    // ------------------------------------------------------------------ tokens
    // The mockup's own values. There is no shared UI module, so these are restated
    // per file; a value that drifts between two screens is a visible seam.

    private static final int INK = 0xFFFFFFFF;
    private static final int DIM = 0x8CFFFFFF;
    private static final int DIMMER = 0x57FFFFFF;
    private static final int LINE = 0x24FFFFFF;
    private static final int ACCENT = 0xFF7FD1C1;
    private static final int MODAL = 0xFF171B21;
    private static final int SCRIM = 0x8C000000;

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ build

    private View build(final Surface initial) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(SCRIM);

        final boolean lockSupported = SurfaceResolver.supportsPerSurfaceLock(
                android.os.Build.VERSION.SDK_INT);

        LinearLayout modal = new LinearLayout(activity);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable card = new GradientDrawable();
        card.setColor(MODAL);
        card.setCornerRadii(new float[]{dp(20), dp(20), 0, 0, dp(20), dp(20), 0, 0});
        modal.setBackground(card);

        TextView title = new TextView(activity);
        title.setText(R.string.apply_title);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(INK);
        modal.addView(title);

        TextView sub = new TextView(activity);
        sub.setText(R.string.apply_subtitle);
        sub.setTextSize(13);
        sub.setTextColor(DIM);
        sub.setPadding(0, dp(3), 0, dp(10));
        modal.addView(sub);

        // One selection, two candidates. A pair of checkboxes would be the wrong
        // control: the picker binds one component to one screen, and offering two
        // independent choices implies both can be had at once.
        final Surface[] chosen = {initial};
        final Option[] homeOpt = new Option[1];
        final Option[] lockOpt = new Option[1];

        homeOpt[0] = option(R.string.apply_home, R.string.apply_home_sub,
                initial == Surface.HOME, new Runnable() {
                    @Override
                    public void run() {
                        chosen[0] = Surface.HOME;
                        homeOpt[0].setSelected(true);
                        lockOpt[0].setSelected(false);
                    }
                });
        modal.addView(homeOpt[0].view);

        if (lockSupported) {
            lockOpt[0] = option(R.string.apply_lock, R.string.apply_lock_sub,
                    initial == Surface.LOCK, new Runnable() {
                        @Override
                        public void run() {
                            chosen[0] = Surface.LOCK;
                            lockOpt[0].setSelected(true);
                            homeOpt[0].setSelected(false);
                        }
                    });
        } else {
            // Dimmed, with the reason, and NOT tappable. The opacity is the
            // mockup's .locked -- a whole row at 42% rather than just the text, so
            // it reads as unavailable rather than as a label.
            lockOpt[0] = option(R.string.apply_lock, R.string.apply_lock_unavailable,
                    false, null);
            lockOpt[0].setLocked();
        }
        modal.addView(lockOpt[0].view);

        if (!lockSupported) {
            TextView note = new TextView(activity);
            note.setText(R.string.apply_lock_note);
            note.setTextSize(12);
            note.setTextColor(DIMMER);
            note.setLineSpacing(0, 1.2f);
            note.setPadding(dp(2), dp(10), dp(2), 0);
            modal.addView(note);
        }

        // Cancel / Continue
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        row.setLayoutParams(rowLp);
        row.addView(ghost(activity.getString(R.string.action_cancel)), new LinearLayout.LayoutParams(
                0, dp(49), 1f));
        row.addView(primary(activity.getString(R.string.apply_continue)), new LinearLayout.LayoutParams(
                0, dp(49), 1f));
        modal.addView(row);
        row.getChildAt(0).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        ((View) row.getChildAt(1)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recordAndLaunch(chosen[0]);
            }
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(modal);
        FrameLayout.LayoutParams modalLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        modalLp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.86f);
        modalLp.width = FrameLayout.LayoutParams.MATCH_PARENT;
        scrim.addView(scroll, modalLp);
        return scrim;
    }

    // ------------------------------------------------------------------ widgets

    /** One candidate row: a swatch, a title, and a reason. */
    private final class Option {
        private final View view;
        private final GradientDrawable swatch;
        private final GradientDrawable dot;
        private final View dotHolder;

        Option(int titleRes, int subRes, boolean selected, Runnable onTap) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x00000000);
            bg.setCornerRadius(dp(11));
            bg.setStroke(dp(1), LINE);
            row.setBackground(bg);

            // The swatch: a 34dp rounded tile carrying a 14dp accent dot with a
            // 2px ring in the modal's own colour at the bottom-right. The ring is
            // what makes the dot read as sitting ON the tile rather than printed on it.
            FrameLayout holder = new FrameLayout(activity);
            swatch = new GradientDrawable();
            swatch.setCornerRadius(dp(9));
            // As the tile's BACKGROUND, not as a child: a Drawable is not a View,
            // and a View would have a second background of its own to fight.
            holder.setBackground(swatch);

            dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dotHolder = new View(activity);
            dotHolder.setBackground(dot);
            dotHolder.setElevation(dp(1));
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(14), dp(14));
            dotLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            dotLp.leftMargin = -dp(3);
            dotLp.bottomMargin = -dp(3);
            holder.addView(dotHolder, dotLp);

            LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            holderLp.rightMargin = dp(12);
            row.addView(holder, holderLp);

            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView b = new TextView(activity);
            b.setText(titleRes);
            b.setTextSize(14);
            b.setTextColor(INK);
            text.addView(b);
            TextView small = new TextView(activity);
            small.setText(subRes);
            small.setTextSize(12);
            small.setTextColor(DIM);
            text.addView(small);
            row.addView(text, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (onTap != null) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onTap.run();
                    }
                });
                row.setClickable(true);
            } else {
                row.setClickable(false);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            view = row;
            setSelected(selected);
        }

        void setSelected(boolean on) {
            swatch.setColor(on ? 0x33FFFFFF : 0x14FFFFFF);
            // The ring is in the MODAL's colour, which is what makes the dot read as
            // sitting ON the tile rather than printed on it.
            dot.setColor(on ? ACCENT : 0x00FFFFFF);
            dot.setStroke(dp(2), MODAL);
        }

        /** The mockup's .locked: the whole row at 42%, and not tappable. */
        void setLocked() {
            view.setAlpha(0.42f);
        }
    }

    private Option option(int titleRes, int subRes, boolean selected, Runnable onTap) {
        return new Option(titleRes, subRes, selected, onTap);
    }

    private Button ghost(String label) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(INK);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), LINE);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp != null) lp.rightMargin = dp(6);
        return b;
    }

    private Button primary(String label) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFF08110F);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(11));
        b.setBackground(bg);
        return b;
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
    private void recordAndLaunch(Surface surface) {
        try {
            SharedPreferences prefs = Settings.prefs(activity);
            Library current = AppState.load(prefs, activity.getFilesDir()).library;
            Library next = current.withAppliedTo(surface, true);
            if (next != current) {
                LibraryStore.saveNow(prefs, next);
                // The sheet's own segment must re-read, or it keeps offering a
                // switch for a photo the index says is on one screen.
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).onAppliedTo(surface);
                }
            }
        } catch (Exception e) {
            // Recording is bookkeeping. Failing it must not stop the user applying.
            android.util.Log.w("Basrelief", "apply record failed: " + e);
        }
        dismiss();
        launchPicker();
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
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void present() {
        Surface initial = Surface.HOME;
        try {
            Library lib = AppState.load(Settings.prefs(activity), activity.getFilesDir()).library;
            if (lib.appliedSurfaces().contains(Surface.LOCK)
                    && !lib.appliedSurfaces().contains(Surface.HOME)) {
                initial = Surface.LOCK;
            }
        } catch (Exception e) {
            // Home is the safe default: it is the surface the wallpaper is on unless
            // something says otherwise.
        }

        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        View content = build(initial);
        if (content.getLayoutParams() != null) {
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        dialog.setContentView(content);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                dialog = null;
            }
        });
        dialog.show();
    }
}