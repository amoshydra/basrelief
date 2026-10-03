package com.amoshydra.basrelief;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One adjustment on one line: its name, a filled track with the value printed on
 * it, and a pair of nudges beside it.
 *
 * <p><b>A fill, not a thumb.</b> This is the design's slider — a bar of accent with
 * the number sitting on it — and {@link android.widget.SeekBar} cannot be that. The
 * platform widget has a thumb, and a stock bar over a painted fill reads as a
 * different control wearing the same clothes. So the track is one {@link Drawable}
 * that paints the grey bar, the accent fill to a fraction and the value, and the
 * touch is handled by the view that draws it. Not a {@code clipToOutline} and a
 * child view resized on every frame either: a clip expresses "inside this shape",
 * never "up to this fraction of it", so the fill would be a second view whose
 * {@code LayoutParams} are rewritten per pixel of travel.
 *
 * <p><b>One row, not three.</b> What this replaces was a label on a line of its
 * own, then a full-width bar, then two more full-width lines of buttons — a stack
 * so tall that the sheet had to scroll to reach a control the design draws on one
 * line. Here the tallest part is the 34dp track, so a control is 34dp of height
 * instead of about 130dp.
 *
 * <p><b>It stores nothing, and says so.</b> A control that persists its own value
 * has to be trusted not to persist while it is being <i>populated</i>, because
 * {@code setProgress} fires the change listener and a guard that is forgotten is a
 * screen that rewrites stored state on open — which is exactly the shape of the bug
 * {@code syncingZoom} exists to prevent in {@link MainActivity}. There is no such
 * thing to get wrong here: {@link #setValue} repaints and returns, and only a
 * gesture the user <i>finished</i> calls {@link OnChanged}. What a value means —
 * whose Depth it is, which surface it lands on — belongs to the caller, because
 * only the caller knows. A stored value the renderer does not read is a lie, and
 * this class refuses to store one it cannot vouch for.
 *
 * <p><b>A wheel, not a place on the bar.</b> A drag moves the value by how far the
 * finger travels — right raises it, left lowers it, and the travel accumulates for
 * the whole gesture — instead of mapping the touch x to an absolute value. A bound
 * resets the measure, so hitting the end and continuing does not wind up: reversing
 * moves the value on the first unit back, the way a wheel at its stop behaves. The
 * bar is a readout, not a thumb: there is no x that means "half depth", so a tap is
 * not a value selection and deliberately changes nothing; a tap has no direction to
 * take a step in, and inventing one is a guess. The −/＋ pair is the step.
 *
 * <p><b>Preview while dragging, commit on release.</b> {@link OnPreview} is
 * optional and fires for every step of travel, for every key; {@link OnChanged}
 * fires once, when the finger lifts, or when a nudge is tapped. A drag that
 * persisted as it moved would re-read and re-validate the whole gallery once per
 * step, for a few hundred stat calls to end up with one value. Preview is also the
 * only way an adjustment with no cheap write path — pan and origin rewrite the
 * whole index — can follow the finger at all.
 *
 * <p><b>The host is told when the work starts and stops.</b> {@link OnInteraction}
 * is optional and fires once when a gesture begins — a track touch-down, or a nudge
 * press — and once when it ends, so a host can make the sheet see-through while the
 * user is adjusting. {@link #setValue} cannot fire it, for the same reason it cannot
 * fire anything else: it has no listener to fire.
 *
 * <p><b>Nothing here is moved with a transform.</b> The fill is painted, not
 * translated. That is the ordinary reason — a translation would need a layout pass
 * to be worth anything — and it is also this app's documented hazard: a sibling
 * positioned by a post-layout transform over the {@code GLSurfaceView} can be laid
 * out, attached, opaque, {@code isShown()} and still absent from the display
 * (b/7179570). Repainting a drawable has no version of that failure.
 */
final class BasreliefSlider extends LinearLayout {

    /** The name column, fixed so every slider's track starts at the same x. */
    private static final int LABEL_DP = 66;
    /** Bar height. The tallest thing in the row, so the row is this tall. */
    private static final int TRACK_DP = 34;
    private static final int TRACK_RADIUS_DP = 9;
    /** The gap between name, track and nudges. */
    private static final int GAP_DP = 9;
    /** A nudge is a target first and a symbol second, so it is never smaller than this. */
    private static final int NUDGE_WIDTH_DP = 28;
    private static final int NUDGE_HEIGHT_DP = 30;
    private static final int NUDGE_RADIUS_DP = 8;
    private static final int NUDGE_GAP_DP = 5;
    /** {@code .row { margin: 8px 0 }} — the row's own top gap. */
    private static final int ROW_TOP_DP = 8;
    /** Where the value starts, on the fill or off it. The same x either way. */
    private static final int VALUE_PAD_DP = 11;
    private static final float LABEL_SP = 13f;
    private static final float VALUE_SP = 13f;
    private static final float NUDGE_SP = 16f;

    /**
     * Below this much fill the value is drawn in white on the grey instead of in
     * ink on the accent — a number you cannot read is worse than one beside a bar.
     *
     * <p>It is a floor, not the whole rule: {@link TrackFace} also asks whether the
     * fill is physically wide enough for the number, because a percentage drawn in
     * near-black across the edge of the accent is the illegibility this threshold
     * exists to prevent, and a track narrower than the text hits it whatever the
     * fraction says.
     */
    private static final float INSIDE_FRACTION = 0.12f;

    /** One nudge is one unit. Enough to aim with, and every key's range is an integer. */
    private static final int STEP = 1;

    /**
     * Finger travel one unit of a screen-pixel key asks for.
     *
     * <p>One, and in device pixels rather than dp: the unit is already a surface
     * pixel and a touch delta is measured in the same pixels, so density does not
     * enter. Scaling this by density would introduce a second ~3x error in the
     * opposite direction.
     */
    private static final float PIXEL_UNIT_TRAVEL_PX = 1f;

    /**
     * How much finger travel a full sweep of the range asks for, when the range is
     * wide enough that the cap below does not bind.
     *
     * <p>Scaled to the range rather than fixed, because one class serves every
     * control: Depth is 100 units wide and Pan is 8000, and a fixed px-per-unit
     * would make Pan untraversable and Depth twitchy. Range-scaling gives every key
     * the same feel — a full sweep is one comfortable drag instead of a different
     * gesture per row. 420dp is a little over a phone's width, so a sweep is one
     * deliberate drag with the finger still on the screen, and the 0..100 rows land
     * at 4.2dp per unit.
     *
     * <p><b>It does not apply to a key whose unit is a screen pixel.</b> Spreading
     * Pan's ±4000 over a sweep is the defect that made pan unusable; such a key
     * uses {@link #createScreenPixels} instead, and the rule there is 1:1 by the
     * unit itself. This constant is the default only.
     */
    private static final float FULL_SWEEP_DP = 420f;

    /**
     * The most travel one unit may demand.
     *
     * <p>For a short range the sweep rule would make each step absurdly long: Output
     * has six resolutions, so 420dp over its five-unit range would be 84dp per step
     * and the bar could not be crossed in one drag. The cap keeps one step a
     * deliberate press-and-drag without letting a short row hijack the sheet.
     */
    private static final float MAX_STEP_DP = 40f;

    /** The accent at the {@code opacity:.85} the mockup draws the fill at. */
    private static final int ACCENT_FILL = 0xD97FD1C1;
    /** The name, and any text off the fill. */
    private static final int DIM = 0x8CFFFFFF;
    /** The empty part of the track. */
    private static final int TRACK_EMPTY = 0x1AFFFFFF;
    /** The hairline the mockup draws around a nudge. */
    private static final int HAIRLINE = 0x24FFFFFF;
    /** The value, printed on the accent. */
    private static final int ON_FILL = 0xFF08110F;

    /** How a value is written. The caller's, because only the caller knows the key. */
    interface ValueFormatter {
        String format(int value);
    }

    /**
     * A gesture the user has finished.
     *
     * <p>Once per release and once per nudge, and never while a control is being
     * populated — the contract that keeps {@link #setValue} safe to call from a
     * refresh.
     */
    interface OnChanged {
        void onChanged(int value);
    }

    /**
     * A value being moved, for the renderer to follow.
     *
     * <p>Optional, and separate from {@link OnChanged} because the two cost
     * different things: this one runs per step of travel and must stay cheap
     * (nothing may read the index in it), while that one writes and may afford a
     * read-modify-write of the whole library. It is the hook pan and origin use to
     * follow the finger without writing on every step.
     */
    interface OnPreview {
        void onPreview(int value);
    }

    /**
     * A gesture beginning and ending, for a host to hang transient UI on.
     *
     * <p>One touch is one pair: {@code onStart} on the track's touch-down or a
     * nudge's press, {@code onEnd} when the finger lifts or the gesture is taken
     * away. A host uses this to lower the sheet's opacity while the user is working
     * and restore it on release. Separate from {@link OnChanged} because it is not
     * about the value: it fires for a tap that changes nothing, and it must not fire
     * for {@link #setValue}, which is why {@link #setValue} has nothing to call.
     */
    interface OnInteraction {
        void onStart();

        void onEnd();
    }

    private final MainActivity activity;
    private final String name;
    private final int min;
    private final int max;
    private final ValueFormatter formatter;
    private final OnChanged onChanged;

    /** Optional. Null is a slider that only paints, which is a legitimate use. */
    private OnPreview onPreview;
    /** Optional. A host that does not dim the sheet simply never installs one. */
    private OnInteraction onInteraction;
    /**
     * True between {@link #beginInteraction} and {@link #endInteraction}, so the
     * pair fires exactly once even if two fingers land on the control: the first
     * end closes the pair and a second start is already covered.
     */
    private boolean interacting;
    /**
     * Finger travel per unit, in surface pixels.
     *
     * <p>Range-scaled by default; {@link #PIXEL_UNIT_TRAVEL_PX} for a key built
     * with {@link #createScreenPixels}. See {@link #FULL_SWEEP_DP}.
     */
    private final float pxPerUnit;
    /** The platform's threshold, so a tap that shivers is still a tap. */
    private final int touchSlop;

    private final TrackFace face;
    private final Track track;
    private final View minus;
    private final View plus;

    private int value;

    /**
     * Build one row.
     *
     * @param key    which adjustment this is. Also the name it draws: see
     *               {@link #labelFor}. This class never reads or writes storage for
     *               it, so it is identity and nothing more.
     * @param value  the value to start showing. Clamped, not trusted.
     * @param min    the bottom of the range, inclusive.
     * @param max    the top of the range, inclusive. Swapped with {@code min} rather
     *               than rejected, because a range in the wrong order is a typo in
     *               the caller and a slider that draws nothing is a worse answer.
     * @param fmt    writes the value, in whatever units this key uses.
     * @param onChanged called once per finished gesture. May be null.
     */
    static BasreliefSlider create(MainActivity a, String key, int value, int min, int max,
                                  ValueFormatter fmt, OnChanged onChanged) {
        return new BasreliefSlider(a, key, value, min, max, fmt, onChanged, false);
    }

    /**
     * Create a row whose unit is a screen pixel, so one unit is one pixel of drag.
     *
     * <p>The range-scaled sweep cannot serve such a key, and that is why the class
     * has a second rule at all: the sweep asks "how wide is a comfortable full
     * drag", then spreads min..max over it, which is right for an abstract unit
     * (Depth's percent) but wrong for one the camera already measures in screen
     * pixels. There the finger and the picture move in the same quantity, so the
     * only scale that makes the picture follow the finger is 1:1 — and no choice
     * of sweep constant fixes a range-scaled rule, because the error grows with
     * the range. Pan's 8000 units asked for 420dp to cross, which on the Pixel 7
     * Pro is 6.4x too fast and is the reported "pan sliding is too sensitive".
     *
     * <p><b>Zoom does not factor in, and this is the whole answer to that
     * question.</b> The camera adds pan to the projection, after the view and the
     * model scale ({@code CameraModel.frame}: {@code projPanned = panMat * proj}),
     * so one pan unit is one pixel of picture travel at every zoom. A rule that
     * divided by zoom here would be a second bug. (Origin is the opposite case —
     * it moves the camera before projection, so its screen meaning does scale with
     * zoom — and that is exactly why only the pan keys use this factory.)
     */
    static BasreliefSlider createScreenPixels(MainActivity a, String key, int value,
                                              int min, int max,
                                              ValueFormatter fmt, OnChanged onChanged) {
        return new BasreliefSlider(a, key, value, min, max, fmt, onChanged, true);
    }

    private BasreliefSlider(MainActivity a, String key, int value, int min, int max,
                            ValueFormatter fmt, OnChanged onChanged, boolean unitIsScreenPixel) {
        super(a);
        this.activity = a;
        this.name = labelFor(a, key);
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.formatter = fmt;
        this.onChanged = onChanged;
        // dp per unit, then pixels. The cap is in dp too, so the feel is the same
        // at any density. Math.max(1, ..) covers a one-value range, which is a
        // slider that cannot move but must not divide by zero. A screen-pixel key
        // skips the whole derivation: its unit already is the drag's own unit.
        int range = Math.max(1, this.max - this.min);
        this.pxPerUnit = unitIsScreenPixel
                ? PIXEL_UNIT_TRAVEL_PX
                : Math.min(MAX_STEP_DP, FULL_SWEEP_DP / range)
                        * a.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(a).getScaledTouchSlop();

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = activity.dp(ROW_TOP_DP);
        // Set here rather than left to the caller's addView, so the row carries its
        // own gap wherever it lands in the sheet.
        setLayoutParams(rowLp);

        // ---- the name: a fixed column, so a row of sliders shares one track start
        TextView label = new TextView(a);
        label.setText(name);
        label.setTextColor(DIM);
        label.setTextSize(LABEL_SP);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                activity.dp(LABEL_DP), LayoutParams.WRAP_CONTENT);
        labelLp.rightMargin = activity.dp(GAP_DP);
        addView(label, labelLp);

        // ---- the track
        face = new TrackFace(activity.dp(TRACK_RADIUS_DP), activity.dp(VALUE_PAD_DP),
                sp(a, VALUE_SP));
        track = new Track(a, this);
        track.setBackground(face);
        track.setClickable(true);
        // Weight 1, not a fixed width: the track takes whatever the name, the nudges
        // and the sheet's own padding leave, so it is correct at any font scale.
        addView(track, new LinearLayout.LayoutParams(0, activity.dp(TRACK_DP), 1f));

        // ---- the nudges, because a value you can only raise is a value you cannot
        // correct. Half of these ranges are signed, and a drag is a gesture you have
        // to know about.
        LinearLayout nudges = new LinearLayout(a);
        nudges.setOrientation(HORIZONTAL);
        minus = nudge(R.string.depth_nudge_down, -1);
        plus = nudge(R.string.depth_nudge_up, +1);
        LinearLayout.LayoutParams minusLp = nudgeSize();
        minusLp.rightMargin = activity.dp(NUDGE_GAP_DP);
        nudges.addView(minus, minusLp);
        nudges.addView(plus, nudgeSize());
        LinearLayout.LayoutParams nudgeLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        nudgeLp.leftMargin = activity.dp(GAP_DP);
        addView(nudges, nudgeLp);

        show(clamp(value, this.min, this.max));
    }

    // ------------------------------------------------------------------ the api

    /** The row, for the caller to add to whatever it builds the sheet out of. */
    View view() {
        return this;
    }

    /**
     * What this control is showing.
     *
     * <p>Offered so the caller does not have to keep its own copy of a number the
     * screen is also displaying: two copies of one value are two chances to
     * disagree, which is how a slider ends up showing 0 while something else is
     * told to run.
     */
    int value() {
        return value;
    }

    /**
     * Show a value, and tell nobody.
     *
     * <p>This is the invariant the class exists to make safe: <b>populating a
     * control must never write.</b> It is inert by construction — no listener, no
     * guard, nothing to forget — so refreshing the sheet after a photo switch cannot
     * rewrite the photograph just left with the values it happened to be showing.
     */
    void setValue(int next) {
        show(clamp(next, min, max));
    }

    /** Let the renderer follow a drag. Optional; see {@link OnPreview}. */
    void setOnPreview(OnPreview preview) {
        this.onPreview = preview;
    }

    /** Hear a gesture begin and end. Optional; see {@link OnInteraction}. */
    void setOnInteraction(OnInteraction listener) {
        this.onInteraction = listener;
    }

    /**
     * Grey the row out, or bring it back.
     *
     * <p>The three parts that take input are set here, by name, rather than left to
     * {@code ViewGroup}'s own propagation: a nudge left live while the track it
     * belongs to was dead is a control that still writes, and a control that still
     * writes is the lie this row was built to stop telling.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        track.setEnabled(enabled);
        minus.setEnabled(enabled);
        plus.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
    }

    // --------------------------------------------------------------- the value

    /** Repaint for a value. The one place the face, the number and the description move. */
    private void show(int v) {
        value = v;
        String text = formatter.format(v);
        face.setFraction(fractionOf(v));
        face.setLabel(text);
        // Spoken as one phrase. Without it a screen reader offers a bar with no
        // number, and "adjust" is not something anyone can hear a value in.
        track.setContentDescription(name + ": " + text);
    }

    private float fractionOf(int v) {
        // A one-value range is a slider that cannot be moved. Drawing it full is a
        // lie about a range that does not exist; drawing it empty is a lie about
        // the one value it does.
        return max > min ? (v - min) / (float) (max - min) : 0f;
    }

    /**
     * A value the finger has moved to: painted and previewed, never stored.
     *
     * <p>Skipped when nothing changed, so a finger resting inside one step's worth
     * of pixels does not spam the renderer's GL queue sixty times a second with the
     * value it already has.
     */
    private void preview(int next) {
        int v = clamp(next, min, max);
        if (v == value) return;
        show(v);
        if (onPreview != null) onPreview.onPreview(v);
    }

    /**
     * The gesture started. Idempotent, so a second finger cannot open a second pair.
     */
    private void beginInteraction() {
        if (interacting) return;
        interacting = true;
        if (onInteraction != null) onInteraction.onStart();
    }

    /**
     * The gesture ended. Paired with {@link #beginInteraction}; see it for why the
     * guard is here rather than at each call site.
     */
    private void endInteraction() {
        if (!interacting) return;
        interacting = false;
        if (onInteraction != null) onInteraction.onEnd();
    }

    /**
     * A nudge. Clamped, and out through the same path a release takes.
     *
     * <p>Not previewed first: a nudge is a complete gesture with no release to wait
     * for, so previewing it would be the same value written twice.
     *
     * <p>Clamping rather than wrapping is the whole point of the pair. A minus that
     * wrapped at the bottom would set the maximum, which is a control that undoes
     * the value you were trying to correct.
     */
    private void nudge(int direction) {
        int next = clamp(value + direction * STEP, min, max);
        if (next == value) return;
        show(next);
        if (onChanged != null) onChanged.onChanged(next);
    }

    /**
     * A drag that ended. Committed only if it moved anything.
     *
     * <p>A tap that lands where the finger already was is not an edit, and a write
     * is expensive enough that a gesture nobody meant should not cause one.
     */
    private void release(int pressedAt) {
        if (value == pressedAt) return;
        if (onChanged != null) onChanged.onChanged(value);
    }

    /** Back where the gesture started, for a drag the system took away. */
    private void cancelTo(int pressedAt) {
        preview(pressedAt);
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ widgets

    /**
     * One of the pair.
     *
     * <p>A {@link TextView} rather than a {@link android.widget.Button}: a stock
     * button brings its own background, its own all-caps setting and a 48dp minimum
     * height that has to be fought with {@code setMinimumHeight} before it will lay
     * out at 28x30. This is a chip with a glyph on it, not a button in a dialog.
     */
    private View nudge(int glyphRes, final int direction) {
        String glyph = activity.getString(glyphRes);
        TextView t = new TextView(activity);
        t.setText(glyph);
        t.setTextSize(NUDGE_SP);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(nudgeBackground());
        // The name plus its own glyph, so a screen reader says what the chip belongs
        // to rather than reading a bare symbol. No new resource for this: the glyph
        // is already one, and a label needs translating where a symbol does not.
        t.setContentDescription(name + " " + glyph);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudge(direction);
            }
        });
        // The press, not the completed click, is the interaction: a nudge is a
        // hold-then-release like a drag, and a host dimming the sheet wants the dim
        // for as long as the finger is down. Returning false everywhere leaves the
        // view's own tracking, ripple and click untouched -- this listener only
        // observes. A disabled nudge is never dispatched to, so the pair cannot fire
        // for a control that is switched off.
        t.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        beginInteraction();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        endInteraction();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
        return t;
    }

    private LinearLayout.LayoutParams nudgeSize() {
        return new LinearLayout.LayoutParams(
                activity.dp(NUDGE_WIDTH_DP), activity.dp(NUDGE_HEIGHT_DP));
    }

    /**
     * A chip: the mockup's translucent fill, its hairline, and a ripple.
     *
     * <p>The ripple is masked to the same rounded rectangle as the fill, so the
     * press highlight stops at the corners instead of showing the chip is a
     * rectangle underneath the rounding.
     */
    private Drawable nudgeBackground() {
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.RECTANGLE);
        fill.setColor(TRACK_EMPTY);
        fill.setCornerRadius(activity.dp(NUDGE_RADIUS_DP));
        fill.setStroke(activity.dp(1), HAIRLINE);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(activity.dp(NUDGE_RADIUS_DP));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, mask);
    }

    /**
     * The name this control draws, resolved from the key it was addressed by.
     *
     * <p>{@link #create} is handed a key and no label, so the label has to be
     * resolved here or the fixed 66dp column draws nothing — and a row with an empty
     * name column is a row of unlabelled bars, which is the state this redesign
     * exists to end. The key is the app's own vocabulary, so the mapping is a
     * switch rather than a string resource named after each key: adding a control
     * means adding a case here, and the fallback (a key drawn as itself) means an
     * unmapped key is ugly rather than invisible.
     */
    private static String labelFor(MainActivity a, String key) {
        int res;
        if ("intensity".equals(key)) res = R.string.slider_depth;
        else if ("motion".equals(key)) res = R.string.slider_motion;
        else if ("zoom".equals(key)) res = R.string.slider_zoom;
        else if ("brightness".equals(key)) res = R.string.slider_brightness;
        else if ("shadow".equals(key)) res = R.string.slider_shadow;
        // The framing axes. They are rows in the sheet like every other adjustment,
        // so they resolve a label the same way -- which they could not while they were
        // four EditTexts behind a disclosure line.
        else if ("originX".equals(key)) res = R.string.framing_origin_x;
        else if ("originY".equals(key)) res = R.string.framing_origin_y;
        else if ("panX".equals(key)) res = R.string.framing_pan_x;
        else if ("panY".equals(key)) res = R.string.framing_pan_y;
        else res = 0;
        return res == 0 ? key : a.getString(res);
    }

    private static float sp(Context c, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                c.getResources().getDisplayMetrics());
    }

    // ----------------------------------------------------------------- the track

    /**
     * The bar, and the wheel.
     *
     * <p>One owner reference rather than a listener, because the touch and the paint
     * are the same arithmetic: a touch says <i>how far</i>, which becomes a value,
     * and the face that paints the value has to hear about it. Routing both through
     * the row means there is one place a value moves — preview, nudge, release and a
     * programmatic set all go through it, and none of them can leave the bar
     * disagreeing with the number printed on it.
     */
    private static final class Track extends View {

        private final BasreliefSlider owner;

        /** Where the finger landed, so travel starts from the touch that began it. */
        private float downX;
        /** The value when this gesture began — what a cancelled drag returns to. */
        private int pressedAt;
        /** True once horizontal travel crossed the slop and this is a drag. */
        private boolean dragging;
        /**
         * The x the drag measures from, and the value it had there.
         *
         * <p>Anchored at the slop boundary rather than at {@link #downX}, so the
         * dead zone is the slop and nothing more: no jump when the drag is claimed,
         * and the travel carried by the claiming event itself is not lost.
         */
        private float anchorX;
        private int anchorValue;

        Track(Context c, BasreliefSlider owner) {
            super(c);
            this.owner = owner;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // False, not a no-op: a dead control must not swallow the drag that is
            // trying to scroll the sheet it lives in.
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // This row lives inside a scroll, and a drag that drifts a few
                    // pixels downward belongs to the scroll rather than to the bar.
                    // Claiming the gesture here is the touch-level way to say so.
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = event.getX();
                    pressedAt = owner.value;
                    dragging = false;
                    owner.beginInteraction();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        float dx = event.getX() - downX;
                        // Below the platform's own slop this is still a tap with a
                        // shiver, and a wheel that moved on it would drift a value
                        // the user only touched to read.
                        if (Math.abs(dx) < owner.touchSlop) return true;
                        dragging = true;
                        // The dead zone is the slop and nothing more. Measuring from
                        // the point the finger crossed it keeps the travel carried by
                        // this very event -- a batched ACTION_MOVE can contain a whole
                        // gesture -- while still leaving no jump at the boundary.
                        anchorX = downX + (dx > 0 ? owner.touchSlop : -owner.touchSlop);
                        anchorValue = owner.value;
                    }
                    int next = anchorValue
                            + Math.round((event.getX() - anchorX) / owner.pxPerUnit);
                    if (next < owner.min || next > owner.max) {
                        // A stop, not a wind-up: discard the travel past the end and
                        // measure from the end itself, so reversing moves the value
                        // at once instead of first unwinding an invisible overrun.
                        // preview() still clamps, so the value stops at the bound.
                        anchorValue = next < owner.min ? owner.min : owner.max;
                        anchorX = event.getX();
                        next = anchorValue;
                    }
                    owner.preview(next);
                    return true;
                case MotionEvent.ACTION_UP:
                    // Done before the release, so a click on a bar has the same
                    // accessibility event a click anywhere else has.
                    performClick();
                    getParent().requestDisallowInterceptTouchEvent(false);
                    // release() itself declines to write when the value never left
                    // where the gesture found it, so the tap path costs nothing.
                    owner.release(pressedAt);
                    owner.endInteraction();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    // Back to where it started, preview and all: the renderer was
                    // moved by the preview, so leaving the bar showing a value that
                    // was never stored would be a second thing on screen the store
                    // does not agree with.
                    owner.cancelTo(pressedAt);
                    owner.endInteraction();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    /**
     * The grey bar, the accent fill to a fraction, and the value on top of them.
     *
     * <p>One drawable rather than a bar, a fill view and a label view, because the
     * value's colour depends on the fill's width: below the threshold it has to be
     * drawn in white on the grey, and only something holding both numbers can decide
     * that. Split across three views it would be a rule read in two places, and the
     * two places would disagree at the one value where the difference is visible.
     *
     * <p>The fill is clipped to the bar rather than given its own rounded ends. A
     * round cap on the right of a fill is a shape that has to grow from the middle
     * outwards, and it is the one part of a bar the eye reads as a thumb.
     */
    private static final class TrackFace extends Drawable {

        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float pad;

        private float fraction;
        private String label = "";
        private float textWidth;

        /**
         * Folded into each colour as it is painted rather than kept on the Paints.
         *
         * <p>The ink's colour is chosen per draw — it changes when the value crosses
         * onto the accent — and a {@code setColor} there would drop the alpha a
         * {@code setAlpha} had applied, so the whole alpha lives in one field that
         * every colour in this drawable passes through.
         */
        private int alpha = 255;

        TrackFace(float radiusPx, float padPx, float textSizePx) {
            this.radius = radiusPx;
            this.pad = padPx;
            ink.setTextSize(textSizePx);
            ink.setTypeface(Typeface.DEFAULT_BOLD);
            // A flat translucent fill at a fractional width is exactly where banding
            // shows up on a dark background, and the shimmer it produces is invisible
            // in a screenshot and annoying on the device.
            bar.setDither(true);
            fill.setDither(true);
            ink.setDither(true);
        }

        void setFraction(float f) {
            if (f == fraction) return;
            fraction = f;
            invalidateSelf();
        }

        void setLabel(String s) {
            if (s == null || s.equals(label)) return;
            label = s;
            // Measured once per value rather than per frame: the answer decides
            // whether the ink goes on the accent or beside it, and measureText on
            // every repaint is a text layout per frame of a drag for nothing.
            textWidth = ink.measureText(s);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            if (w <= 0f || h <= 0f) return;

            bar.setColor(fade(TRACK_EMPTY));
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, bar);

            // Rounded to whole pixels: a fill ending half a pixel in is the two
            // terraces this app already pays for once in the depth map.
            float filled = Math.round(w * fraction);
            if (filled > 0f) {
                fill.setColor(fade(ACCENT_FILL));
                int save = canvas.save();
                canvas.clipRect(b.left, b.top, b.left + filled, b.bottom);
                canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, fill);
                canvas.restoreToCount(save);
            }

            if (label.isEmpty()) return;
            Paint.FontMetrics fm = ink.getFontMetrics();
            float baseline = b.top + h / 2f - (fm.ascent + fm.descent) / 2f;
            ink.setColor(fade(holdsText(filled) ? ON_FILL : Color.WHITE));
            // The same x either way. Moving the number as well as recolouring it
            // would make the value jump sideways at exactly the moment the bar is
            // being read against it.
            canvas.drawText(label, b.left + pad, baseline, ink);
        }

        /**
         * Whether the accent is wide enough for the number to sit on it.
         *
         * <p>The fraction is the mockup's threshold, and the width is this file's
         * addition: at a twelfth of a narrow track the fill is still narrower than
         * the text, and near-black ink running off the edge of the accent onto the
         * grey is unreadable at every value below the point where the number ends.
         * Either test passing is not enough — the value only moves inside when both
         * hold, so it moves once, not twice.
         */
        private boolean holdsText(float filled) {
            return fraction >= INSIDE_FRACTION && filled >= textWidth + pad * 2f;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            bar.setColorFilter(cf);
            fill.setColorFilter(cf);
            ink.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private int fade(int colour) {
            int a = ((colour >>> 24) * alpha) / 255;
            return (a << 24) | (colour & 0x00FFFFFF);
        }
    }
}