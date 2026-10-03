package com.amoshydra.basrelief;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/**
 * One damped spring and one release rule, for every draggable surface in the app.
 *
 * <p><b>Why this exists.</b> The settings sheet's grip was the only draggable
 * surface, and it did three things wrong at once: it followed the finger with no
 * resistance, it dismissed the moment the finger crossed a threshold <i>while the
 * finger was still down</i>, and it settled with the platform's default
 * interpolator, which is a fade-shaped ease with no velocity in it. The user's
 * words were the sheet was dismissed when dragged only part way, and the motion was not springy A
 * second drag was then written for the studio's sheet, with its own constants;
 * two drags is two chances for one of them to keep the old behaviour, which is
 * exactly the drift this class removes.
 *
 * <p><b>The release rule is a projection, not a position.</b> A short quick flick
 * covers more ground than a long slow drag, and the gesture people expect is the
 * one where the fling carries on under its own momentum. So on release the
 * remaining travel is extrapolated at the measured velocity for
 * {@link #PROJECTION_SECONDS}, and that projected landing place is compared with
 * {@link #dismissDistance}. A release speed past
 * {@link #DISMISS_VELOCITY_HEIGHTS_PER_S} card-heights per second dismisses on its
 * own, so a card flicked from only a little way down still goes.
 *
 * <p><b>Decide on release, never mid-drag.</b> While the finger is down the user
 * is moving the card; letting go is the decision. The old grip dismissed the
 * instant the threshold was crossed, so the card vanished from under a finger
 * that had not finished the gesture — and there is no way to un-let-go of a
 * button that is already gone.
 *
 * <p><b>The spring is a damped sine, not a library.</b> The framework has no
 * spring interpolator (its own are all fixed-shape), and a dependency for
 * fifteen lines of maths was not worth the install. The curve is the
 * underdamped step response of a second-order system, normalised to [0, 1], so
 * it overshoots once and settles; the duration is what carries the speed, and it
 * comes from the remaining distance over the release speed. A flick therefore
 * settles fast and a slow drag eases, without a second table of numbers.
 *
 * <p><b>The one difference at the studio sheet.</b> The settings/apply/playlists
 * cards live in a {@code Dialog}, and drag their card by {@code translationY} —
 * safe there because a Dialog owns its own window and contains no
 * {@code SurfaceView}. The studio's sheet is a sibling of the
 * {@code GLSurfaceView} in the same window, and b/7179570 means a post-layout
 * transform can be attached, opaque, {@code isShown()} and absent from the
 * display; it must move by <b>layout height</b> instead. That difference is the
 * mechanism, not the motion: both use {@link #spring()} and {@link #duration},
 * and both use {@link #dismissesOnRelease} on release. Only
 * {@link #settleTranslation} is Dialog-specific.
 *
 * <p><b>Units.</b> Distances are pixels, velocities pixels per second (what
 * {@link VelocityTracker#getYVelocity} returns with a 1000 ms horizon). The
 * container-relative constants are fractions of the card's own height, so they
 * read the same on a small phone and a large one.
 */
final class SpringSettle {

    // ------------------------------------------------------- tuning: one each

    /**
     * How far down, as a fraction of the card's height, the projected landing
     * place must be for a release to dismiss.
     *
     * <p>0.25 is the mockup's own feel: a card dragged a quarter of its height
     * and released goes, and one dragged less comes back. Lower is twitchier.
     */
    static final float DISMISS_FRACTION = 0.25f;

    /**
     * The floor under {@link #DISMISS_FRACTION}, in dp, so a very short card
     * cannot be dismissed by a stray few pixels. The caller converts it with its
     * own density; 64dp is roughly a thumb's travel on a small sheet.
     */
    static final int DISMISS_MIN_DP = 64;

    /**
     * A downward release at least this many card-heights per second dismisses
     * regardless of position: a flick from 2cm down is still a flick. One
     * height per second is about a decisive wrist flick; a drag is well under
     * it, and {@link #PROJECTION_SECONDS} catches the middle.
     */
    static final float DISMISS_VELOCITY_HEIGHTS_PER_S = 1.0f;

    /**
     * How far ahead, in seconds, the release is projected at its measured
     * speed. This is the momentum the hand expects to see carried; too small and
     * a flick has to be aimed, too large and a slow drag over-dismisses.
     */
    static final float PROJECTION_SECONDS = 0.25f;

    /**
     * The most of the card's height an upward drag may reveal.
     *
     * <p>The card is tethered at its bottom edge: dragging up should feel like
     * pulling against something, not like lifting a free object. The resistance
     * is an asymptote rather than a clamp, so the first millimetre moves fully
     * and the pull stiffens from there; this is where it stops.
     */
    static final float RESISTANCE_FRACTION = 0.2f;

    /**
     * The stiffness proxy: multiplied into {@code distance / speed}.
     *
     * <p>Below 1 the spring is faster than the hand released it, above 1 slower.
     * This is the one number to turn when the whole app should feel snappier or
     * softer; the damping ratio below shapes the curve rather than its speed.
     */
    static final float DURATION_FACTOR = 0.9f;

    /**
     * The speed a settle is assumed to have at minimum, in px/s, so a release
     * with no measurable velocity still moves like a spring instead of taking a
     * second. A drag released dead still falls at roughly this speed.
     */
    static final float MIN_SETTLE_SPEED_PX_PER_S = 1500f;

    /** A settle never finishes faster than this; below it the spring reads as a snap. */
    static final long MIN_DURATION_MS = 140L;

    /** A settle never takes longer than this; above it the card reads as stuck. */
    static final long MAX_DURATION_MS = 420L;

    // The curve's shape, not its speed. Damping < 1 overshoots once, which is
    // what makes a spring read as a spring; the frequency is scaled so the
    // envelope has decayed to nothing by the end of the derived duration.
    private static final float DAMPING = 0.6f;
    private static final float OMEGA = 12f;

    private static final TimeInterpolator SPRING = new Spring();

    private SpringSettle() {
    }

    // ------------------------------------------------------------- the maths

    /**
     * The rubber-band offset for a drag, given in raw finger units.
     *
     * <p>Positive values (downward, the direction the card exists to travel)
     * pass through untouched, because damping those would make the dismissal
     * feel broken. Negative values (a finger above the start, lifting the card
     * off its bottom edge) are divided by their own distance, so the movement
     * approaches {@code spanPx * RESISTANCE_FRACTION} and never reaches it.
     *
     * @param dy the raw offset, positive down
     * @param spanPx the card's height; a tethered scale to the thing being moved
     */
    static float resistUp(float dy, float spanPx) {
        if (dy >= 0f || spanPx <= 0f) return Math.max(dy, 0f);
        float over = -dy;
        float limit = spanPx * RESISTANCE_FRACTION;
        return -over / (1f + over / limit);
    }

    /** The distance, in pixels, past which the projected landing place dismisses. */
    static float dismissDistance(float cardHeightPx, float minDismissPx) {
        return Math.max(cardHeightPx * DISMISS_FRACTION, minDismissPx);
    }

    /**
     * Whether releasing at {@code positionPx} with {@code velocityPxPerS} means
     * dismiss.
     *
     * <p>Two routes, one rule. A release speed past
     * {@link #DISMISS_VELOCITY_HEIGHTS_PER_S} card-heights per second is a flick
     * and goes; anything slower is projected forward at that speed and goes if
     * the projection crosses {@link #dismissDistance}. An upward (negative)
     * release is never accelerated by the projection — it can only settle back
     * or, if the position is already past the threshold, still dismiss, because
     * the finger left it there.
     */
    static boolean dismissesOnRelease(float positionPx, float velocityPxPerS,
                                      float cardHeightPx, float minDismissPx) {
        if (cardHeightPx > 0f
                && velocityPxPerS >= cardHeightPx * DISMISS_VELOCITY_HEIGHTS_PER_S) {
            return true;
        }
        float projected = positionPx
                + Math.max(0f, velocityPxPerS) * PROJECTION_SECONDS;
        return projected >= dismissDistance(cardHeightPx, minDismissPx);
    }

    /**
     * How long a settle of {@code distancePx} at {@code velocityPxPerS} takes.
     *
     * <p>Distance over speed is the natural answer, and the clamp is the
     * minimum speed so a dead-still release still has somewhere to go. This is
     * where "a flick settles fast and a slow drag eases" is actually true: the
     * curve is the same, only its time base changes.
     */
    static long duration(float distancePx, float velocityPxPerS) {
        float speed = Math.max(Math.abs(velocityPxPerS), MIN_SETTLE_SPEED_PX_PER_S);
        long ms = (long) (Math.abs(distancePx) / speed * 1000f * DURATION_FACTOR);
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, ms));
    }

    /** The one spring curve. Stateless, so one instance serves every settle. */
    static TimeInterpolator spring() {
        return SPRING;
    }

    /**
     * Spring {@code target}'s {@code translationY} to {@code to}, starting at
     * the release velocity, and run {@code after} when it lands.
     *
     * <p>For a card that may move by translation: a Dialog's card, or anything
     * else that owns its own window. The studio sheet cannot use this — see the
     * class javadoc — and composes {@link #spring()} and {@link #duration}
     * around a layout change instead.
     *
     * @param after run only if the animation reaches its end; a new drag cancels
     *              the animation and therefore the dismissal, which is the
     *              honest reading of grabbing a card mid-settle.
     */
    static void settleTranslation(final View target, float to, float velocityPxPerS,
                                  Runnable after) {
        float distance = to - target.getTranslationY();
        target.animate().cancel();
        target.animate()
                .translationY(to)
                .setDuration(duration(distance, velocityPxPerS))
                .setInterpolator(SPRING)
                .withEndAction(after)
                .start();
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

        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float damped = OMEGA * (float) Math.sqrt(1f - DAMPING * DAMPING);
            float envelope = (float) Math.exp(-DAMPING * OMEGA * t);
            float phase = (float) Math.cos(damped * t)
                    + (DAMPING * OMEGA / damped) * (float) Math.sin(damped * t);
            return 1f - envelope * phase;
        }
    }
}
