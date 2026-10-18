package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The tilt smoother: one rule, applied by two callers at two sample rates.
 *
 * <p><b>What this class exists for.</b> {@code ReliefRenderer} steps this filter
 * once per drawn frame to smooth the tilt it draws with; {@code TiltController}
 * steps it once per sensor sample to decide whether the drawn picture has moved
 * (the doze). They are two call sites of ONE function, which is why the "mirror"
 * is structural rather than a pair of implementations that must be kept in step —
 * {@code scripts/check-parity.mjs} guards that the step and its constants have no
 * second home, and the tests here pin the rule itself.
 *
 * <p><b>The property that lets one function serve both rates.</b> {@link
 * TiltFilter#SMOOTHING_TAU} is a time constant, so a step is a function of
 * ELAPSED TIME, not of a frame. Two steps of half the gap land exactly where one
 * step of the whole gap does — asserted here, because that equality is the whole
 * reason the gate's 50 Hz answer and the renderer's frame-rate answer are the same
 * filter rather than two filters that merely look alike. A fraction hard-coded per
 * frame would pass a spot check on the renderer and make the gate drift with the
 * sensor rate.
 */
public class TiltFilterTest {

    /** The device's own sensor period for the gate: 50 Hz. */
    private static final float SAMPLE_DT = 0.02f;

    /**
     * The step is an exponential in ELAPSED TIME: {@code alpha(tau)} is
     * {@code 1 - 1/e}, composing two gaps of half the length is composing the whole
     * gap, and a gap that cannot claim a number does not move the value.
     */
    @Test
    public void theStepIsTheExponentialOfElapsedTimeNotAFractionOfAFrame() {
        assertEquals("a zero gap is no step", 0f, TiltFilter.alphaFor(0f), 0f);
        // The time constant, pinned as the log of what is LEFT after a gap: the
        // fraction closed is 1 - exp(-dt/tau), so log(1 - alpha) is -dt/tau exactly.
        // Pinning the constant itself would be a restatement; this fails if tau moves.
        for (float dt : new float[]{SAMPLE_DT, 0.05f, TiltFilter.MAX_DT}) {
            assertEquals("the gap left after dt=" + dt + " must be exp(-dt/tau)",
                    -dt / TiltFilter.SMOOTHING_TAU,
                    (float) Math.log(1f - TiltFilter.alphaFor(dt)), 1e-6f);
        }

        // Monotone, and strictly short of 1 while the gap is legal: a step of 1 is
        // not a filter.
        float previous = -1f;
        for (float dt : new float[]{1e-4f, 0.005f, 0.02f, 0.05f, 0.1f}) {
            float a = TiltFilter.alphaFor(dt);
            assertTrue("alpha must rise with the gap (" + dt + ")", a > previous);
            assertTrue("alpha must stay short of a snap (" + dt + ")", a < 1f);
            previous = a;
        }

        // A gap longer than MAX_DT is one clamped step, not a snap: a stall must not
        // turn the filter off. The cap bites below the time constant, so a gap of tau
        // itself is already the clamped step.
        assertEquals("a long gap is clamped, not snapped",
                TiltFilter.alphaFor(TiltFilter.MAX_DT),
                TiltFilter.alphaFor(TiltFilter.MAX_DT * 50f), 0f);
        assertTrue("and the clamped step is still a filter, not a snap",
                TiltFilter.alphaFor(TiltFilter.MAX_DT) < 1f);
        assertEquals("a gap of one tau is past the cap and takes the capped step",
                TiltFilter.alphaFor(TiltFilter.MAX_DT),
                TiltFilter.alphaFor(TiltFilter.SMOOTHING_TAU), 0f);

        // A gap that cannot claim a value is the identity for a step -- no NaN and
        // no accidental snap. The codebase's "a window claims nothing" rule.
        assertEquals(0f, TiltFilter.alphaFor(Float.NaN), 0f);
        assertEquals(0f, TiltFilter.alphaFor(-1f), 0f);
        assertEquals(0f, TiltFilter.alphaFor(Float.POSITIVE_INFINITY), 0f);
    }

    /**
     * ONE ELAPSED TIME IS ONE STEP, whatever the frame it was measured in.
     *
     * <p>This is the equality the two callers rest on: the gate steps every sensor
     * sample and the renderer every drawn frame, so their frame sizes differ by
     * whatever the phone happens to be doing. Composing the smaller gaps must land
     * where the single larger gap lands, or one filter would be tracking a
     * different signal from the other.
     */
    @Test
    public void theGateAndTheDrawTakeTheSameStepForTheSameElapsedTime() {
        // err small enough that the plain exponential applies at EVERY gap below,
        // including the first half-step (the blend takes over once |err| * gain
        // passes the exponential's own fraction), so the composition is the
        // exponential's own.
        float start = 0.40f, target = 0.405f;
        for (float dt : new float[]{SAMPLE_DT, 0.033f, TiltFilter.MAX_DT}) {
            float once = TiltFilter.approach(start, target,
                    TiltFilter.alphaFor(dt));
            float twice = start;
            for (int i = 0; i < 2; i++) {
                twice = TiltFilter.approach(twice, target, TiltFilter.alphaFor(dt / 2f));
            }
            assertEquals("two half-steps must land where one whole step does (dt="
                            + dt + ")",
                    once, twice, 1e-6f);
        }

        // And a deliberate movement snaps identically at every cadence: the attack is
        // a rule about the ERROR, not about the gap, which is what lets the gate
        // recognise a flick the renderer is about to snap.
        float big = TiltFilter.ATTACK_ERROR * 2f;
        for (float dt : new float[]{SAMPLE_DT, 0.05f, TiltFilter.MAX_DT}) {
            assertEquals("an error past the attack reaches the target in one step",
                    big, TiltFilter.approach(0f, big, TiltFilter.alphaFor(dt)), 0f);
        }
    }

    /**
     * Below {@link TiltFilter#ATTACK_ERROR} the exponential applies unchanged; at
     * and above it the step reaches the target in one frame. The two regimes meet
     * where {@code |err| * ATTACK_GAIN} equals the exponential's own fraction, and
     * the step never overshoots the target in either.
     */
    @Test
    public void theStepIsASnapAboveTheAttackAndTheExponentialBelowIt() {
        float alpha = TiltFilter.alphaFor(SAMPLE_DT);
        float join = alpha / TiltFilter.ATTACK_GAIN; // where the blend reaches alpha

        // Below the join: exactly the exponential.
        for (float err : new float[]{1e-4f, join * 0.5f, join * 0.999f}) {
            assertEquals("the exponential applies unchanged at err=" + err,
                    err * alpha, TiltFilter.approach(0f, err, alpha), 1e-7f);
        }
        // Between the join and the attack: the blend, err * ATTACK_GAIN per step.
        for (float err : new float[]{join * 1.001f, 0.02f, TiltFilter.ATTACK_ERROR * 0.9f}) {
            assertEquals("the blend takes |err| * gain at err=" + err,
                    err * err * TiltFilter.ATTACK_GAIN,
                    TiltFilter.approach(0f, err, alpha), 1e-6f);
        }
        // At and above the attack: one step, exactly the target.
        for (float err : new float[]{TiltFilter.ATTACK_ERROR,
                TiltFilter.ATTACK_ERROR * 1.001f, 0.5f, 1f}) {
            assertEquals("past the attack is a snap at err=" + err,
                    err, TiltFilter.approach(0f, err, alpha), 0f);
        }

        // No overshoot and monotone in the error, both signs, every gap: a step that
        // could overshoot would put the picture past the hand, and a step that fell
        // as the error rose would be a filter that moved the wrong way.
        for (float dt : new float[]{1e-4f, SAMPLE_DT, TiltFilter.MAX_DT}) {
            float a = TiltFilter.alphaFor(dt);
            float previous = 0f;
            for (float err = 0f; err <= 1f; err += 0.005f) {
                float neg = TiltFilter.approach(0f, -err, a);
                float pos = TiltFilter.approach(0f, err, a);
                assertEquals("the two signs are symmetric at err=" + err, -neg, pos, 1e-7f);
                assertTrue("no overshoot past the target at err=" + err, pos <= err);
                assertTrue("monotone in the error at err=" + err, pos >= previous);
                previous = pos;
            }
        }

        // A step that lands short keeps its sign and does not cross the target from
        // the other side either.
        float moved = TiltFilter.approach(0.3f, 0.31f, alpha);
        assertTrue("a short step stays between the two", moved > 0.3f && moved < 0.31f);
    }
}
