package com.amoshydra.basrelief;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.render.RenderStats;

/**
 * The studio's on-glass performance readout: fps over the frame cost, in the
 * top-right, behind a Settings switch.
 *
 * <p><b>Why it exists.</b> Splat rendering is tuned by eye while watching the cost
 * move, and that meant a cable and a {@code logcat} filter. This puts the numbers on
 * the glass, so a row can be dragged and the cost read in the same glance. It is an
 * instrument, so the numbers are the ones the {@code splat:} log line prints — one
 * computation, in {@link RenderStats}, printed by the log and read here.
 *
 * <p><b>Two sizes of type, the app's own.</b> The fps is the headline, 16sp monospace
 * at {@code --dim}; the milliseconds are the supporting line, 10sp monospace at
 * {@code --dimmer} — the status line's and the diagnostics block's family and ink. It
 * is monospace because it is a table of numbers, and a proportional face makes the
 * width jitter on every update.
 *
 * <p><b>Positioned by GRAVITY and layout margins, never a post-layout transform.</b> A
 * sibling of a {@code GLSurfaceView} positioned with {@code translationY} can be
 * composited away entirely (b/7179570), which this project has already paid a session
 * for. The margins carry the system inset, so the readout clears the status bar and
 * any cutout on the immersive studio; the studio is edge-to-edge, so without them the
 * fps would sit under the clock.
 *
 * <p><b>It must not perturb what it measures.</b> It is not clickable or focusable, so
 * a tap falls through to the render under it; it is a sibling of the sheet rather than
 * a child, so it takes no part in the sheet's layout; it is {@code WRAP_CONTENT}, so it
 * cannot change the surface's size; and its text is rebuilt a few times a second rather
 * than per frame (see {@code MainActivity.perfTick}), because a view invalidated on
 * every frame would add UI-thread work to the very path being measured.
 */
final class PerfOverlay {

    /** The pill's plate, so the readout stays legible over any photograph. */
    private static final int PLATE = 0x8C1C2026;
    /** The pill's outline; see {@code MainActivity.PILL_PLATE}. */
    private static final int OUTLINE = 0x33FFFFFF;
    /** The gap from the screen edge, on top of the system inset. */
    private static final int GAP_DP = 12;

    private final MainActivity activity;
    private final LinearLayout container;
    private final TextView headline;
    private final TextView detail;
    /** Reused across updates: the readout is rebuilt a few times a second. */
    private final StringBuilder buffer = new StringBuilder(96);

    private PerfOverlay(MainActivity activity) {
        this.activity = activity;
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.END);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(activity.dp(10));
        bg.setColor(PLATE);
        bg.setStroke(activity.dp(1), OUTLINE);
        container.setBackground(bg);
        container.setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6));
        // Not a control: it must not steal a tap from the render under it, and it must
        // not take focus. A ViewGroup offers a touch to its children first, so a view
        // that declines passes the gesture straight through to the GLSurfaceView.
        container.setClickable(false);
        container.setFocusable(false);

        headline = text(16, ModalSheet.DIM);
        detail = text(10, ModalSheet.DIMMER);
        container.addView(headline);
        container.addView(detail);
    }

    private TextView text(int sizeSp, int colour) {
        TextView t = new TextView(activity);
        t.setTextSize(sizeSp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(colour);
        t.setGravity(Gravity.END);
        t.setClickable(false);
        t.setFocusable(false);
        return t;
    }

    /**
     * Build it, add it to the studio's root at the top-right, and return it.
     *
     * <p>Added before the sheet's panel, so an expanded sheet covers it rather than
     * the readout floating over the grip. At the launch peek the sheet is a third of
     * the screen, so the readout is visible where the user is looking while moving the
     * rows.
     */
    static PerfOverlay install(MainActivity activity, FrameLayout root) {
        final PerfOverlay overlay = new PerfOverlay(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = activity.dp(GAP_DP);
        lp.rightMargin = activity.dp(GAP_DP);
        root.addView(overlay.container, lp);
        overlay.container.setVisibility(View.GONE);
        overlay.container.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                overlay.applyInsets(insets);
                return insets;
            }
        });
        overlay.container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                // A listener installed before attachment is not called, and a detached
                // view has no ViewRootImpl to dispatch through.
                v.requestApplyInsets();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        return overlay;
    }

    /**
     * Move the readout clear of the status bar and any cutout.
     *
     * <p>The status bar is visible in the studio (only the navigation bar is hidden),
     * so the top inset is real; on API 30+ the modern inset types are the source, and
     * below that the deprecated insets plus, from P, the cutout's safe insets.
     */
    private void applyInsets(WindowInsets insets) {
        int top;
        int right;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars =
                    insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            android.graphics.Insets cut =
                    insets.getInsets(WindowInsets.Type.displayCutout());
            top = Math.max(bars.top, cut.top);
            right = Math.max(bars.right, cut.right);
        } else {
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cut = insets.getDisplayCutout();
                if (cut != null) {
                    top = Math.max(top, cut.getSafeInsetTop());
                    right = Math.max(right, cut.getSafeInsetRight());
                }
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        if (lp == null) return;
        lp.topMargin = activity.dp(GAP_DP) + top;
        lp.rightMargin = activity.dp(GAP_DP) + right;
        container.setLayoutParams(lp);
    }

    void setVisible(boolean visible) {
        container.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Show one window's numbers, or claim nothing before the first.
     *
     * <p>A null snapshot is the window before anything has been measured, and it blanks
     * the headline rather than printing {@code 0.0 fps}, the same "a window claims
     * nothing" rule {@link RenderStats} applies to a missing field. The buffer is
     * reused; {@code TextView} copies the characters it is handed, so the next update
     * can clear it safely.
     */
    void setStats(RenderStats stats) {
        if (stats == null) {
            headline.setText("\u2014 fps");
            detail.setVisibility(View.GONE);
            return;
        }
        buffer.setLength(0);
        stats.headline(buffer);
        headline.setText(buffer);
        buffer.setLength(0);
        stats.detail(buffer);
        if (buffer.length() == 0) {
            detail.setVisibility(View.GONE);
        } else {
            detail.setText(buffer);
            detail.setVisibility(View.VISIBLE);
        }
    }
}
