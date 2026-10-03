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
 * <p><b>The spring is a damped second-order response, not a library.</b> The
 * framework has no spring interpolator (its own are all fixed-shape), and a
 * dependency for fifteen lines of maths was not worth the install. The curve is
 * the step response of a second-order system, normalised to [0, 1]; the duration
 * is what carries the speed, and it comes from the remaining distance over the
 * release speed. A flick therefore settles fast and a slow drag eases, without a
 * second table of numbers.
 *
 * <p><b>Critically damped, so it settles and never bounces.</b> Both profiles run
 * at damping 1.0, which makes the step response monotone. An earlier version ran
 * underdamped and overshot — 10.7%, then 9.5% on the cards, then 0.15% — and the
 * user rejected that three times, the last as comically springy, like a cartoon. Lowering
 * damping was the wrong lever: any value below 1 overshoots by construction, so
 * "very subtle" was being approximated by a smaller bounce rather than by removing
 * the mechanism. {@link Spring} keeps all three cases and dispatches on the value,
 * because the critical case has its own closed form
 * ({@code 1 - e^(-w t)(1 + w t)}) and the underdamped one divides by zero there.
 * Monotone is a structural property, not a number to be argued down.
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
     * The studio sheet's own motion, tuned to settle rather than bounce.
     *
     * <p>The user's correction, twice: tone the bounce down and settle quickly, then the bounce was comical, like a cartoon, and was asked to be very subtle
     *
     * <p><b>This is critically damped, and overshoot is now structurally
     * impossible rather than merely small.</b> The earlier passes picked damping by
     * arithmetic — 0.58, then 0.90 — and both were still springs, because a damping
     * below 1 <i>always</i> overshoots by construction, and 0.90 still leaves a
     * visible bounce on a surface this large. Arguing that ~0.15% was "below what
     * an eye can see" was the wrong defence of a number the user had already
     * rejected twice. {@code damping >= 1} is the branch that cannot bounce: the
     * step response is monotone, so there is no peak to argue about.
     *
     * <p>It is still a spring, not the platform's fixed ease, and that distinction
     * is the whole reason this class exists (see the class javadoc): velocity is
     * still measured from the gesture and the duration is still distance over
     * speed, so a flick settles fast and a slow release eases. What is gone is the
     * oscillation, which was never the part being asked for.
     *
     * <p>{@code omega} shapes how settled the curve is by t=1; at 12 the residual
     * is ~0.1%, so the animation lands on the value rather than stopping short of
     * it. {@code minSettleSpeed} is the floor under the measured release speed so a
     * dead-still release still moves: 0.9 travels/s, screen-independent the way
     * every other number here is.
     */
    static final Profile SHEET = new Profile(
            /* damping */ 1.0f,
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
     * <p><b>Also critically damped.</b> This profile sat at 0.60 — a first peak of
     * <b>~9.5%</b> — behind a comment arguing that a small transient card is "where
     * a visible spring still reads as feedback rather than as imprecision". That was
     * me defending a number the user had twice called a cartoon, and it is the
     * reason every modal in this app visibly bounced. There is no version of that
     * argument that survives the user asking twice; the card is a window, not a
     * toy, and it now settles.
     *
     * <p>Same curve family and the same remaining constants — a 0.9 duration factor,
     * a 140 ms floor, a 420 ms ceiling — so a dismiss still reads as immediate. Only
     * the damping changed, which is the only thing that decides whether it bounces.
     */
    static final Profile CARD = new Profile(
            /* damping */ 1.0f,
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
         * Damping ratio. <b>At or above 1 the settling is monotone and cannot
         * overshoot</b>, which is what every profile in this app uses; below 1 the
         * curve is a spring proper and overshoots once by construction, with a
         * first peak of {@code e^(-pi*z/sqrt(1-z^2))}.
         *
         * <p>Both profiles are critically damped rather than merely low-damped
         * because the user rejected the bounce three times, and the middle pass
         * answered a request to remove it by lowering damping to 0.90 and arguing
         * the remaining 0.15% was invisible. {@link Spring} dispatches on this
         * value, so asking for zero bounce costs one branch at construction.
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

    /**
     * The critically damped step response, which is the default for both hosts.
     *
     * <p><b>Two curves, because one formula cannot express both.</b> The
     * underdamped step response of a second-order system is
     * {@code 1 - e^(-z w t)(cos(wd t) + (z w / wd) sin(wd t))}, where the damped
     * frequency is {@code wd = w sqrt(1 - z^2)}. That form was used alone until the
     * user rejected the bounce three times — the last two passes lowered damping
     * rather than removing the mechanism, and at damping 1.0 {@code wd} is
     * {@code sqrt(0)}: the sine term vanishes and {@code z w / wd} is a division by
     * zero, which is the exact reason a previous attempt stopped at 0.90 and
     * rationalised the residual overshoot as invisible.
     *
     * <p><b>It is not invisible, and the branch is cheap.</b> The critical case has
     * a closed form of its own — {@code 1 - e^(-w t)(1 + w t)} — that follows from
     * taking the limit of the same expression, so this is the same system solved at
     * its critical point rather than a different animation. It is monotone by
     * construction: the velocity of that curve is {@code w^2 t e^(-w t)}, which is
     * non-negative for all {@code t >= 0}. <b>That is the property being bought —
     * overshoot cannot be dialled down further because there is no peak to
     * measure.</b>
     *
     * <p>{@code damping > 1} is overdamped and has the form
     * {@code 1 - e^(-z w t)(1 + (z w t) / sqrt(z^2 - 1))}; it is kept so a profile
     * can ask for an even flatter settle, and it is monotone for the same reason.
     */
    private static final class Spring implements TimeInterpolator {

        /** Critically damped: the underdamped form's limit as {@code wd -> 0}. */
        private static final class CriticallyDamped implements TimeInterpolator {
            private final float omega;

            CriticallyDamped(float omega) {
                this.omega = omega;
            }

            @Override
            public float getInterpolation(float t) {
                if (t <= 0f) return 0f;
                if (t >= 1f) return 1f;
                float wt = omega * t;
                return 1f - (float) Math.exp(-wt) * (1f + wt);
            }
        }

        /** Overdamped: two real roots, monotone, slower to land than critical. */
        private static final class Overdamped implements TimeInterpolator {
            private final float zomega;
            private final float root;

            Overdamped(float damping, float omega) {
                this.zomega = damping * omega;
                this.root = (float) Math.sqrt(damping * damping - 1f);
            }

            @Override
            public float getInterpolation(float t) {
                if (t <= 0f) return 0f;
                if (t >= 1f) return 1f;
                return 1f - (float) (Math.exp(-zomega * t) * (1f + zomega * t / root));
            }
        }

        /** Underdamped: the spring proper, which overshoots by construction. */
        private static final class Underdamped implements TimeInterpolator {
            private final float damping;
            private final float omega;
            private final float damped;

            Underdamped(float damping, float omega) {
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

        Spring(float damping, float omega) {
            // Dispatched once, at construction, so a profile never branches per
            // frame and the interpolation is a plain method call.
            if (damping >= 1f) {
                this.interpolator = damping == 1f
                        ? new CriticallyDamped(omega)
                        : new Overdamped(damping, omega);
            } else {
                this.interpolator = new Underdamped(damping, omega);
            }
        }

        private final TimeInterpolator interpolator;

        @Override
        public float getInterpolation(float t) {
            return interpolator.getInterpolation(t);
        }
    }

}
