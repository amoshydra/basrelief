package com.amoshydra.basrelief.core.render;

/**
 * The tilt smoother: one step function and its two constants, in {@code :core}
 * because <b>two</b> callers have to apply the SAME filter to the same signal.
 *
 * <p><b>Who the two are.</b> {@code ReliefRenderer} smooths the tilt it draws
 * with ({@code smoothX}/{@code smoothY}), because a single time constant is what
 * stops the relief shimmering from sensor jitter. {@code TiltController}'s doze
 * gate needs the same value, because the question it asks is "has the DRAWN
 * picture moved?" — and the drawn value is the smoothed one, not the raw
 * reading, which carries the phone's own sensor and desk noise. Measuring the
 * raw signal instead is what made the doze chatter around its threshold while
 * the picture sat still.
 *
 * <p><b>Why one class and not two matching implementations.</b> The gate cannot
 * read the renderer's filter: the renderer's advances only while it is drawing,
 * so a gate that read it would freeze the moment it dozed and could never wake.
 * So the gate keeps its own state — and the two states are the same function
 * evaluated at two different sample rates (the sensor's, ~50 Hz, and the draw
 * frame's). Putting that function here means there is no second derivation of
 * the constants or of the fast-attack rule to drift; the mirror is by
 * construction rather than by comment. {@code TiltFilterTest} pins the rule and
 * the one property that lets one function serve both rates: {@code tau} is a
 * time constant, so the same elapsed time is the same step whatever the frame
 * size.
 *
 * <p><b>Why the fast attack is part of the mirror and not just the exponential.</b>
 * The gate's quantity is only an upper bound on the drawn value's movement if it
 * is the same filter: below {@link #ATTACK_ERROR} both ease, and above it both
 * snap in a single step. A gate with only the exponential would lag the target
 * exactly while a deliberate fast movement is under way — the one moment the
 * bound matters, because it is what bounds the step the wake takes — so it would
 * under-report the drawn value's movement and allow a wake bigger than the bound.
 * Mirroring the attack makes the gate's value the drawn value's estimate in both
 * regimes.
 *
 * <p>This class holds no state: the callers keep their own pair of smoothed
 * values, so nothing here can be shared across threads by accident.
 */
public final class TiltFilter {

    /**
     * The smoothing time constant, in seconds.
     *
     * <p>Display-refresh independent on purpose, so the feel is the same at
     * 60/90/120 Hz. This is the value used for SMALL errors — sensor jitter while
     * the phone is held still — and it is what stops the relief from shimmering
     * when nothing is moving.
     */
    public static final float SMOOTHING_TAU = 0.2f;

    /**
     * The error above which a deliberate movement snaps in a single step.
     *
     * <p>A single time constant cannot tell a deliberate movement from a sensor
     * wobble, so it treats both identically: with {@code tau = 0.2} the filter
     * closes only ~9.5% of the gap per frame at 50 Hz, and a 30 degree tilt takes
     * ~460 ms to get 90% of the way there, which reads as "the gyro lags". So the
     * fraction followed per frame is raised once the error is large: below this
     * the exponential applies unchanged (jitter stays smoothed), above it the
     * fraction saturates at 1 and the value snaps in one frame.
     *
     * <p>This is a FEEL constant. Lower it if the motion reads as twitchy; raise it
     * if mid-movement still feels like it lags. Tilt is normalised as
     * {@code roll / RANGE_DEG} with {@code RANGE_DEG = 25}, so 0.125 here is about
     * 3 degrees of phone rotation, not 7.
     */
    public static final float ATTACK_ERROR = 0.125f;

    /**
     * How steeply the exponential blends into the fast regime: the fraction
     * followed is {@code max(alpha, |err| * ATTACK_GAIN)} below
     * {@link #ATTACK_ERROR}, so the two regimes meet continuously at
     * {@code |err| = alpha / ATTACK_GAIN} — a kink there would be a visible pop in
     * velocity as a movement crossed it.
     */
    public static final float ATTACK_GAIN = 8f;

    /**
     * The longest gap between two steps that a single step may span, in seconds.
     *
     * <p>A step is an exponential in elapsed time, so an unbounded gap would make
     * the fraction 1 and the filter not a filter at all. Capping it bounds one
     * step's response to a stall — the renderer's own frame gap, and the gate's
     * sample gap after a sensor pause — and keeps the two callers' answers
     * comparable. Measured at ~50 Hz the cap is never reached in normal use.
     */
    public static final float MAX_DT = 0.1f;

    /**
     * The exponential step for a frame or sample gap of {@code dt} seconds.
     *
     * <p>A gap that cannot claim a value (non-finite, zero or negative) answers 0 —
     * the identity for a filter step, so a step that cannot be measured does not
     * move the value. The codebase's "a window claims nothing" rule applied to a
     * number.
     */
    public static float alphaFor(float dt) {
        if (!Float.isFinite(dt) || dt <= 0f) return 0f;
        if (dt > MAX_DT) dt = MAX_DT;
        return 1f - (float) Math.exp(-dt / SMOOTHING_TAU);
    }

    /**
     * One axis: exponential for small errors, instant for large ones.
     *
     * @param alpha the exponential step for this gap, from {@link #alphaFor}
     */
    public static float approach(float smooth, float target, float alpha) {
        float err = target - smooth;
        float a = alpha;
        float mag = Math.abs(err);
        if (mag > ATTACK_ERROR) {
            a = 1f;                 // deliberate movement: snap
        } else if (mag * ATTACK_GAIN > a) {
            a = mag * ATTACK_GAIN;  // blend smoothly into the fast regime
        }
        return smooth + err * a;
    }

    private TiltFilter() {
    }
}
