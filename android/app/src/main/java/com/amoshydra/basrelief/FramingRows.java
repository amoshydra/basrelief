package com.amoshydra.basrelief;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

/**
 * The framing axes as four slider rows.
 *
 * <p>Replaces a one-line disclosure that opened two columns of platform
 * {@code EditText}s — underlined white boxes, the default Android look, in two
 * cramped columns on a 1440px screen. The wireframe gives Pan the same treatment as
 * Origin: "two axes, like Origin — one slider for each". Four full-width rows, each
 * with a −/＋ pair like every other adjustment, and nothing behind a tap.
 *
 * <p>A separate file rather than more of {@code MainActivity}: that class is the
 * largest in the app and every UI slice this redesign wanted went into it, which is
 * why those slices had to be done one at a time. This one is a whole section with no
 * state of its own, so it can be built elsewhere and added in one line.
 *
 * <p><b>Why sliders and not fields.</b> A numeric field is a keyboard and a caret, and
 * a −/＋ pair reaches every value a drag does. A field also cannot preview: nothing
 * moves until the user commits, and framing is the one adjustment where moving is the
 * whole point.
 */
final class FramingRows {

    private FramingRows() {
    }

    /**
     * Origin is a fraction of the reachable range, so it is carried in thousandths
     * to keep a slider integral: −1.000..1.000.
     */
    private static final int ORIGIN_MIN = -1000;
    private static final int ORIGIN_MAX = 1000;

    /**
     * Pan is in pixels, and its bound is the renderer's, not a constant here.
     *
     * <p><b>The renderer is the truth and this control derives from it.</b>
     * {@code ReliefRenderer.setPan} (and {@code panBy}) clamp to
     * {@code max(surfaceW, surfaceH)} — the camera's reachable travel on the
     * surface it is actually drawing into. This row used to carry a flat ±4000,
     * which on a 3120px-tall phone left the last ~880 units of drag as a silent
     * dead stop: the slider moved, the picture did not, and the stored value was
     * then re-clamped away on the next load. A range that can disagree with the
     * clamp is a control that displays values nothing renders.
     *
     * <p><b>That the clamp depends on the surface size decides the shape of the
     * fix.</b> A different phone, a rotation or a multi-window resize all move
     * the bound, so no compile-time number can be right; the honest option is
     * for the row to follow the same quantity the renderer clamps against. That
     * quantity is read from {@link MainActivity#glView}, whose layout size IS
     * the size {@code onSurfaceChanged} hands the renderer, and the range is
     * refreshed on every layout change. Before the first layout the display
     * metrics are the best reading of the same number, because the GL view is
     * full-screen; see {@link #panLimit}.
     *
     * <p>The row is built with {@link BasreliefSlider#createScreenPixels} because a
     * pan unit already is a screen pixel: the camera applies pan after the
     * projection, so a drag of D pixels changes pan by D and the picture by D, at
     * every zoom. Zoom is deliberately not passed to the row.
     */
    private static int panLimit(MainActivity activity) {
        int w = activity.glView.getWidth();
        int h = activity.glView.getHeight();
        if (w > 0 && h > 0) return Math.max(w, h);
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /** Build the whole section: readout, Origin caption, X, Y, Pan caption, X, Y. */
    static View build(final MainActivity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        // The readout, because the group heading above it says only "Where it sits"
        // and a heading is not a place for a number.
        activity.framingReadout = new TextView(activity);
        activity.framingReadout.setTextSize(13);
        activity.framingReadout.setTextColor(0x8CFFFFFF);
        activity.framingReadout.setPadding(activity.dp(4), activity.dp(2), activity.dp(4), activity.dp(6));
        box.addView(activity.framingReadout);
        refreshReadout(activity);

        box.addView(caption(activity, R.string.framing_origin_caption));
        box.addView(axis(activity, "originX", true));
        box.addView(axis(activity, "originY", true));

        box.addView(caption(activity, R.string.framing_pan_caption));
        box.addView(axis(activity, "panX", false));
        box.addView(axis(activity, "panY", false));

        // The row is built before the first layout pass, when glView has no size;
        // panLimit() falls back to the display metrics there. This listener
        // replaces that estimate with the real surface size the renderer will
        // clamp against — and re-derives it on a rotation or a window resize, so
        // the slider can never be left offering travel the picture cannot take.
        activity.glView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (l == ol && t == ot && r == or && b == ob) return;
                int limit = Math.max(r - l, b - t);
                if (limit <= 0) return;
                setPanRange(activity, limit);
            }
        });

        refreshReadout(activity);
        return box;
    }

    /**
     * Give both pan rows the renderer's current bound, silently.
     *
     * <p>The readout follows in the same call: it prints the pan values, so a
     * range that stopped the sliders at the bound while the readout still printed
     * a larger stored number would be two readings of one thing — the exact
     * disagreement this row exists to prevent. The value itself is only shown
     * clamped; the stored blob is not rewritten, so a pan stored on a larger
     * screen survives a visit to a smaller one, exactly as
     * {@code ReliefRenderer.setPan}'s own comment promises.
     */
    private static void setPanRange(MainActivity activity, int limit) {
        setRange(activity, "panX", limit);
        setRange(activity, "panY", limit);
        refreshReadout(activity);
    }

    private static void setRange(MainActivity activity, String key, int limit) {
        BasreliefSlider slider = activity.sliders.get(key);
        if (slider != null) slider.setRange(-limit, limit);
    }

    /**
     * The small caption above a pair of rows, naming the unit.
     *
     * <p>The same treatment as a group heading but for a family rather than a group —
     * which is why this is not {@code sectionLabel}: that one is for the four groups,
     * and using it here would make "Origin" read as a section in its own right.
     */
    private static TextView caption(MainActivity activity, int res) {
        TextView t = new TextView(activity);
        t.setText(res);
        t.setTextSize(11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0x57FFFFFF);
        t.setPadding(activity.dp(4), activity.dp(6), activity.dp(4), activity.dp(4));
        return t;
    }

    /** One axis as a slider row. */
    private static View axis(final MainActivity activity, final String key,
                             final boolean fraction) {
        BasreliefSlider.ValueFormatter fmt = new BasreliefSlider.ValueFormatter() {
            @Override
            public String format(int v) {
                return fraction
                        ? String.format(java.util.Locale.US, "%.2f", v / 1000f)
                        : String.valueOf(v);
            }
        };
        BasreliefSlider.OnChanged changed = new BasreliefSlider.OnChanged() {
            @Override
            public void onChanged(int v) {
                apply(activity, key, fraction, v);
            }
        };
        // Pan's unit is a screen pixel, so its row drags 1:1 and its range is
        // the renderer's own bound; see panLimit. Origin is a fraction of the
        // camera's own travel and its screen meaning does scale with zoom, so it
        // keeps the range-scaled rule like every other key; see
        // BasreliefSlider.createScreenPixels for the camera reasoning.
        final int limit = panLimit(activity);
        BasreliefSlider slider = fraction
                ? BasreliefSlider.create(activity, key, read(activity, key, true),
                        ORIGIN_MIN, ORIGIN_MAX, fmt, changed)
                : BasreliefSlider.createScreenPixels(activity, key, read(activity, key, false),
                        -limit, limit, fmt, changed);
        // Live preview, the half these four rows were missing. The store is still
        // written once, on release, by OnChanged above; this only moves the
        // renderer's own fields, which is what makes pan and origin follow the
        // finger like Depth and Zoom already do. Nothing here reads or writes the
        // index: a preview that persisted would re-read and re-validate the whole
        // gallery once per step of travel.
        final boolean isX = key.endsWith("X");
        slider.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int v) {
                final float value = fraction ? v / 1000f : v;
                // One queued event for the cancel and the write together. Queued
                // because cancelGlide is GL-thread state (the GLSurfaceView's own
                // touch handler queues it the same way), and one event rather than
                // two so the cancel cannot run after the write and re-apply the
                // glide's destination over it.
                activity.glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        activity.renderer.cancelGlide();
                        if (fraction) {
                            activity.renderer.setOrbit(
                                    isX ? value : activity.renderer.getOrbitX(),
                                    isX ? activity.renderer.getOrbitY() : value);
                        } else {
                            activity.renderer.setPan(
                                    isX ? value : activity.renderer.getPanX(),
                                    isX ? activity.renderer.getPanY() : value);
                        }
                    }
                });
                activity.glView.requestRender();
            }
        });
        // The sheet goes to glass while the control is in use; the row stays at
        // 60%. Installed here because these sliders are built in this class rather
        // than by MainActivity.addSlider, and the two paths must not diverge.
        activity.dimWhileInteracting(slider);
        activity.sliders.put(key, slider);
        return slider.view();
    }

    /** One axis's stored value as a slider integer, on the surface being edited. */
    private static int read(MainActivity activity, String key, boolean fraction) {
        LibraryItem item = activity.state().activeItem();
        if (item == null) return 0;
        SurfaceSettings f = item.settings(activity.editSurface);
        boolean isX = key.endsWith("X");
        if (fraction) return (int) Math.round((isX ? f.originX : f.originY) * 1000f);
        // Shown clamped to the same bound the renderer uses, not as stored: a pan
        // written on a larger surface is inside the blob but outside what this
        // screen can render, so printing the raw number here would describe a
        // position the picture is not at.
        int limit = panLimit(activity);
        long v = Math.round(isX ? f.panX : f.panY);
        if (v > limit) v = limit;
        if (v < -limit) v = -limit;
        return (int) v;
    }

    /**
     * Persist one axis, on the surface being edited, leaving the other seven alone.
     *
     * <p>{@code editItem} rather than a whole-item write, so the sibling axis and every
     * adjustment survive: the rule the whole sheet follows, and the one
     * {@code withOrigin} exists to make easy. The renderer follows by re-resolving
     * from the index on the GL thread — one path for everything, no second way for the
     * renderer to learn where the camera is, which is how the two surfaces came to
     * disagree about the bundled item in the first place.
     */
    private static void apply(final MainActivity activity, final String key,
                              final boolean fraction, final int value) {
        final boolean isX = key.endsWith("X");
        final float v = fraction ? value / 1000f : value;
        final String id = activity.state().activeId();
        if (id == null) return; // the bundled demo keeps its own framing
        LibraryStore.editItem(activity.prefs, id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                SurfaceSettings other = item.settings(activity.editSurface);
                return fraction
                        ? (isX ? item.withOrigin(activity.editSurface, v, other.originY)
                               : item.withOrigin(activity.editSurface, other.originX, v))
                        : (isX ? item.withPan(activity.editSurface, v, other.panY)
                               : item.withPan(activity.editSurface, other.panX, v));
            }
        });
        activity.glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                activity.renderer.reloadFromPrefs();
            }
        });
        activity.glView.requestRender();
        refreshReadout(activity);
    }

    /**
     * Repopulate every framing control from the surface being edited.
     *
     * <p><b>Called from {@code MainActivity.refreshSliders}, and that is not
     * optional.</b> These four rows register into the same {@code sliders} map as
     * Depth and Zoom (see {@link #axis}), but they are addressed by a different set
     * of keys and — unlike the other five — were never written back after the sheet
     * was built. A surface switch or a gallery selection therefore left them showing
     * the <i>previous</i> surface's origin and pan, and the next nudge committed
     * that stale value onto the surface the user had just switched to. Switching
     * Home → Lock and nudging repeated, so two surfaces the user had framed
     * differently converged on one of them.
     *
     * <p>The rule is the one the whole sheet follows: a control must display
     * exactly what the gesture will write, or the gesture writes what the control
     * happened to be displaying.
     */
    static void refresh(MainActivity activity) {
        setSlider(activity, "originX", true);
        setSlider(activity, "originY", true);
        setSlider(activity, "panX", false);
        setSlider(activity, "panY", false);
        refreshReadout(activity);
    }

    /**
     * One framing row set from the stored value, silently.
     *
     * <p>{@link BasreliefSlider#setValue} is inert by construction — it has no
     * listener to fire — so this cannot re-enter {@code onChanged} and write the
     * value it is only trying to display.
     */
    private static void setSlider(MainActivity activity, String key, boolean fraction) {
        BasreliefSlider slider = activity.sliders.get(key);
        if (slider == null) return;
        slider.setValue(read(activity, key, fraction));
    }

    /**
     * The one number the heading cannot carry, so it sits directly under it. The
     * wireframe draws the pair on one line because they are one question — where the
     * photograph sits — at two scales.
     */
    static void refreshReadout(MainActivity activity) {
        if (activity.framingReadout == null) return;
        activity.framingReadout.setText(activity.getString(R.string.framing_readout,
                fmt3(read(activity, "originX", true) / 1000f),
                fmt3(read(activity, "originY", true) / 1000f),
                read(activity, "panX", false),
                read(activity, "panY", false)));
    }

    private static String fmt3(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}