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
     * Pan is in pixels and has no natural ceiling — the camera can travel further
     * than a screen is wide. This is the reachable range of the control, not a limit
     * on what may be stored.
     */
    private static final int PAN_MIN = -4000;
    private static final int PAN_MAX = 4000;

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

        refreshReadout(activity);
        return box;
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
        BasreliefSlider slider = BasreliefSlider.create(activity, key,
                read(activity, key, fraction),
                fraction ? ORIGIN_MIN : PAN_MIN,
                fraction ? ORIGIN_MAX : PAN_MAX,
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int v) {
                        return fraction
                                ? String.format(java.util.Locale.US, "%.2f", v / 1000f)
                                : String.valueOf(v);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int v) {
                        apply(activity, key, fraction, v);
                    }
                });
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
        return (int) Math.round(isX ? f.panX : f.panY);
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