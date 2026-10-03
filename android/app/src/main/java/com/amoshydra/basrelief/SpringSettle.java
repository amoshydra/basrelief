package com.amoshydra.basrelief;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.VelocityTracker;

/**
 * One damped spring and one release rule, for every draggable surface in the app.
 *
 * <p><b>Why this exists.</b> The settings sheet's grip was the only draggable
 * surface, and it did three things wrong at once: it followed the finger with no
 * resistance, it dismissed the moment the finger crossed a threshold <i>while the
 * finger was still down</i>, and it settled with the platform's default
 * interpolator, which is a fade-shaped ease with no velocity in it. The user's
 * words described a sheet dismissed when dragged only part way, with motion that was not springy. The
 * studio sheet then got its own drag with its own constants, which is two chances
 * for one of them to keep the old behaviour — the drift this class removes.
 *
 * <p><b>The release rule is not here.</b> What a release <i>means</i> is
 * {@link com.amoshydra.basrelief.core.gesture.SheetIntent} in :core, because it is
 * pure arithmetic in fractions of travel and that is the only way it can be
 * tested without a device. This class is the machinery around it: which curve,
 * how long, and how resistance tapers.
 *
 * <p><b>The spring is a damped sine, not a library.</b> The framework has no
 * spring interpolator (its own are all fixed-shape), and a dependency for
 * fifteen lines of maths was not worth the install. The curve is the
 * underdamped step response of a second-order system, normalised to [0, 1], so
 * it overshoots once and settles; the duration is what carries the speed, and it
 * comes from the remaining distance over the release speed. A flick therefore
 * settles fast and a slow drag eases, without a second table of numbers.
 *
 * <p><b>Two profiles, because the two hosts are not the same surface.</b> The
 * dialog cards are small, close to the finger and dismissed in one gesture; the
 * studio sheet is the size of the lower screen and the user watches it move. The
 * same curve on both read as smooth rather than springy on the sheet, so
 * {@link #SHEET} is slower and overshoots harder while {@link #CARD} keeps the
 * tighter motion the dialogs already shipped. The constants live here, one block
 * each, so turning "springier" is an edit in one file rather than a hunt through
 * three touch listeners.
 *
 * <p><b>Units.</b> Fractions of full travel, so a profile reads the same on any
 * screen: position 0 is fully open, 1 is fully collapsed, velocity is travels per
 * second. The interpolator itself is unitless; only the duration is derived, and
 * the derivation cancels the span. Pixel-based helpers exist only where a caller
 * genuinely holds pixels ({@link #resistUp}).
 */
final class SpringSettle {

    // ------------------------------------------------------- tuning: the sheet

    /**
     * The studio sheet's own motion. This is deliberately slower and looser than
     * {@link #CARD}: about a 10.7% overshoot and a 250–500 ms tail, which is what
     * makes the entrance from below and a collapse read as a spring rather than
     * as a slide. The sheet is large and the user is looking at it, so the
     * overshoot is the product; the dialogs are small and one-shot, so they stay
     * tighter.
     *
     * <p>{@code damping} sets the overshoot alone: the first peak of this
     * normalized curve is {@code e^(-pi*z/sqrt(1-z^2))}, so 0.58 is ~10.7% and
     * 0.60 — the cards — is ~9.5%. {@code omega} only decides how settled the
     * curve is by t=1; at 12 the residual is ~0.1%, so the animation ends on the
     * value instead of jumping.
     *
     * <p>{@code minSettleSpeed} is the floor under the measured release speed so
     * a dead-still release still moves: 0.9 travels/s, which for this sheet's
     * roughly 1800px height is about the old 1500px/s the dialog used, but
     * screen-independent the way every other number here is. The 250ms floor is
     * the shortest settle that shows the overshoot for more than a couple of
     * frames on a 60Hz panel, and 500ms is the point where it starts to read as
     * slow rather than as springy.
     */
    static final Profile SHEET = new Profile(
            /* damping */ 0.58f,
            /* omega */ 12f,
            /* durationFactor */ 1.0f,
            /* minSettleSpeed, travels/s */ 0.9f,
            /* minDurationMs */ 250L,
            /* maxDurationMs */ 500L);

    // -------------------------------------------------------- tuning: the card

    /**
     * The dialog cards' motion: the tighter of the two, because a card is a
     * one-gesture surface — a drag either dismisses it or returns it — and a long
     * tail would leave the user waiting on a window that is already decided.
     *
     * <p>Same curve family, 0.60 damping (~9.5% overshoot), but a 0.9 duration
     * factor, a 140ms floor and a 420ms ceiling: the constants this app shipped
     * for the modal grip, kept deliberately rather than inflated along with the
     * sheet. {@code minSettleSpeed} 2.0 travels/s is about 1500px/s on a card of
     * this size — the old pixel floor — expressed the same screen-independent way
     * as the sheet's.
     */
    static final Profile CARD = new Profile(
            /* damping */ 0.60f,
            /* omega */ 12f,
            /* durationFactor */ 0.9f,
            /* minSettleSpeed, travels/s */ 2.0f,
            /* minDurationMs */ 140L,
            /* maxDurationMs */ 420L);

    /**
     * The most of a card's height an upward drag may reveal.
     *
     * <p>The card is tethered at its bottom edge: dragging up should feel like
     * pulling against something, not like lifting a free object. The resistance
     * is an asymptote rather than a clamp, so the first millimetre moves fully
     * and the pull stiffens from there; this is where it stops.
     */
    static final float RESISTANCE_FRACTION = 0.2f;

    private SpringSettle() {
    }

    /**
     * One host's motion constants, with the curve they compose.
     *
     * <p>A profile is not a bag of numbers: {@link #duration} is the one place
     * distance-over-speed becomes milliseconds, and it is the only thing that
     * makes "a flick settles fast and a slow drag eases" true. Keeping the curve
     * and its duration together means a caller cannot take one without the other.
     */
    static final class Profile {

        /**
         * Damping ratio. Below 1 the curve overshoots once — that overshoot is
         * what reads as a spring; at 1 or above the settling is monotone and reads
         * as an ease. The first peak's height is {@code e^(-pi*z/sqrt(1-z^2))}.
         */
        final float damping;

        /**
         * Undamped angular frequency. Only shapes the normalized curve's envelope:
         * at t=1 the envelope is {@code e^(-z*omega)}, so 12 leaves ~0.1% and the
         * animation lands rather than stopping mid-oscillation.
         */
        final float omega;

        /**
         * Multiplied into {@code distance / speed}. Below 1 the spring is faster
         * than the hand released it, above 1 slower. The single speed knob.
         */
        final float durationFactor;

        /**
         * The speed a settle is assumed to have at minimum, in travels per second,
         * so a release with no measurable velocity still moves like a spring
         * instead of taking a second.
         */
        final float minSettleSpeedTravelPerS;

        /** A settle never finishes faster than this; below it the spring reads as a snap. */
        final long minDurationMs;

        /** A settle never takes longer than this; above it the card reads as stuck. */
        final long maxDurationMs;

        private final TimeInterpolator spring;

        private Profile(float damping, float omega, float durationFactor,
                        float minSettleSpeedTravelPerS,
                        long minDurationMs, long maxDurationMs) {
            this.damping = damping;
            this.omega = omega;
            this.durationFactor = durationFactor;
            this.minSettleSpeedTravelPerS = minSettleSpeedTravelPerS;
            this.minDurationMs = minDurationMs;
            this.maxDurationMs = maxDurationMs;
            this.spring = new Spring(damping, omega);
        }

        /** The one curve. Stateless, so one instance serves every settle of a host. */
        TimeInterpolator spring() {
            return spring;
        }

        /**
         * How long a settle of {@code distanceTravel} at {@code velocityTravelPerS}
         * takes.
         *
         * <p>Distance over speed is the natural answer, and the clamp is the
         * minimum speed so a dead-still release still has somewhere to go. Both
         * arguments are fractions of the full travel, never pixels, so the span
         * cancels and a small phone and a large one feel the same.
         */
        long duration(float distanceTravel, float velocityTravelPerS) {
            float speed = Math.max(Math.abs(velocityTravelPerS), minSettleSpeedTravelPerS);
            long ms = (long) (Math.abs(distanceTravel) / speed * 1000f * durationFactor);
            return Math.max(minDurationMs, Math.min(maxDurationMs, ms));
        }
    }

    // ------------------------------------------------------------- the maths

    /**
     * The rubber-band offset for a drag, given in raw finger units.
     *
     * <p>Positive values (downward, the direction the card exists to travel)
     * pass through untouched, because damping those would make the dismissal
     * feel broken. Negative values (a finger above the start, lifting the card
     * off its bottom edge) are divided by their own distance, so the movement
     * approaches {@code span * }{@value #RESISTANCE_FRACTION} of the span and
     * never reaches it. {@code dy} and {@code spanPx} are in the same unit; the
     * fraction cancels the unit, which is why this is shared by a panel and a
     * card without a conversion.
     *
     * @param dy the raw offset, positive down
     * @param spanPx the surface's height; a tethered scale to the thing being moved
     */
    static float resistUp(float dy, float spanPx) {
        if (dy >= 0f || spanPx <= 0f) return Math.max(dy, 0f);
        float over = -dy;
        float limit = spanPx * RESISTANCE_FRACTION;
        return -over / (1f + over / limit);
    }

    /**
     * Velocity tracking for one gesture, so the release speed is measured rather
     * than inferred from where the finger was.
     *
     * <p>One instance per DOWN/UP pair. {@link #end} recycles it; the caller is
     * responsible for ending a gesture exactly once, including when a new DOWN
     * interrupts an old one.
     */
    static final class Drag {

        private final VelocityTracker tracker = VelocityTracker.obtain();

        void add(MotionEvent event) {
            tracker.addMovement(event);
        }

        /** The gesture's speed along Y in px/s; positive is down. */
        float velocityY() {
            tracker.computeCurrentVelocity(1000);
            return tracker.getYVelocity();
        }

        void end() {
            tracker.recycle();
        }
    }

    /** The damped sine: {@code 1 - e^(-z w t)(cos(wd t) + (z w / wd) sin(wd t))}. */
    private static final class Spring implements TimeInterpolator {

        private final float damping;
        private final float omega;
        private final float damped;

        Spring(float damping, float omega) {
            this.damping = damping;
            this.omega = omega;
            this.damped = omega * (float) Math.sqrt(1f - damping * damping);
        }

        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float envelope = (float) Math.exp(-damping * omega * t);
            float phase = (float) Math.cos(damped * t)
                    + (damping * omega / damped) * (float) Math.sin(damped * t);
            return 1f - envelope * phase;
        }
    }

}
