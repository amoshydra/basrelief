package com.amoshydra.basrelief;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;

/**
 * The diagnostics block at the bottom of Settings.
 *
 * <p>Everything here is for a person with a cable and a problem. It lives in one
 * place, at the bottom, and never inside the controls -- because debug text
 * sprinkled through a UI reads as content, and content is what the user came to look
 * at.
 *
 * <p>It is deliberately not the logcat. The engine's own view of itself is written
 * to prefs by the wallpaper service, which is a different process with a different
 * lifecycle, and "the studio looks right" says nothing about whether the home screen
 * is showing what is selected. That difference is the whole class of bug this block
 * exists to make visible without a cable.
 *
 * <p>Monospace, because this is a block of key/value pairs and a proportional font
 * makes the second column ragged enough that the eye cannot run down it. It is the
 * one place in the app that asks for a typeface rather than a size, and that is
 * because it is the one place that is a table.
 */
final class DiagnosticsBlock {

    /** Shown where a value has not been written yet -- see {@link #valueOf}. */
    private static final String NONE = "—";

    private DiagnosticsBlock() {
    }

    static View build(final MainActivity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        // Fainter than a settings row (which is 0x0AFFFFFF / --line): this is a panel
        // of values rather than a control, and it should sit behind the content
        // rather than compete with the rows above it. The border is the same hairline,
        // so it belongs to the same family.
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(activity, 11));
        bg.setColor(0x08FFFFFF);
        bg.setStroke(dp(activity, 1), 0x1FFFFFFF);
        box.setBackground(bg);

        box.addView(line(activity, R.string.diag_engine_prefix, read(activity, "wallpaper_debug")));
        box.addView(line(activity, R.string.diag_gyro_prefix, read(activity, "gyro_status")));
        box.addView(line(activity, R.string.diag_status_prefix, read(activity, "status")));

        // The map filename lives here and nowhere else. It is the name of a file on
        // disk and means nothing to anyone choosing a photo, which is why it was
        // never a label on a control.
        box.addView(line(activity, R.string.diag_map_prefix, activeDepthName(activity)));

        TextView hint = new TextView(activity);
        hint.setText(R.string.diag_tap_to_refresh);
        hint.setTextSize(11);
        hint.setTextColor(0x57FFFFFF); // --dimmer, as .note
        hint.setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), 0);
        box.addView(hint);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Re-read on tap rather than live: these are written from the sensor
                // thread and the pref listener, and rebuilding this block on every
                // write would be a layout pass per gyro sample.
                activity.refreshDiagnostics();
            }
        });
        return box;
    }

    /**
     * The depth map's filename for whatever is selected.
     *
     * <p>Read through {@link LibraryStore} rather than by parsing the blob here, so
     * this block cannot disagree with the studio about what the index says is
     * active. That disagreement is the bug the block exists to catch, so it must not
     * be able to cause one.
     */
    private static String activeDepthName(MainActivity activity) {
        try {
            Library lib = LibraryStore.load(
                    activity.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE));
            LibraryItem item = lib.activeItem();
            return item == null ? "" : item.depthName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * One key/value line.
     *
     * <p>The prefix comes from a resource and carries its own trailing space, so the
     * separator is not a second place the wording can differ between screens -- the
     * studio's status line and this block both say "Status:" from one string.
     */
    private static TextView line(MainActivity activity, int prefixRes, String value) {
        TextView t = new TextView(activity);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(0x8CFFFFFF); // --dim
        t.setText(activity.getString(prefixRes) + valueOf(value));
        t.setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), dp(activity, 2));
        return t;
    }

    /**
     * An absent value.
     *
     * <p>An em dash rather than an empty string: an empty line in a monospace table
     * is indistinguishable from a blank row, so a value the engine has not written
     * yet would read as no row at all.
     */
    private static String valueOf(String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }

    private static String read(MainActivity activity, String key) {
        try {
            return activity.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                    .getString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static int dp(MainActivity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
