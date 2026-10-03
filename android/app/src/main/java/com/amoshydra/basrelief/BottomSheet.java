package com.amoshydra.basrelief;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;

import com.amoshydra.basrelief.core.gesture.SheetIntent;

/**
 * One draggable sheet: entrance, drag arbitration, release intent, settle and
 * fade, wrapped once and configured per host.
 *
 * <p><b>Why this is a component and not four fixes in {@code MainActivity}.</b>
 * The studio's sheet and the settings/apply/playlists cards are the same gesture
 * with different plumbing, and every one of the user's five reports — "spring
 * open", "drag within the content", "fade the content", "read my intent", "where
 * is the spring" — is a property of the gesture, not of either view tree. Writing
 * them in {@code MainActivity} would have fixed the studio and left the cards on
 * the old rule; this class is the one place the rule lives, and the two hosts are
 * injected differences rather than copies.
 *
 * <p><b>Three differences, injected — and {@link Snap} is the one that is easy to
 * get wrong.</b> The studio's sheet is a sibling of a {@code GLSurfaceView}, and
 * b/7179570 means a post-layout transform can be attached, opaque,
 * {@code isShown()} and absent from the display, so it moves by <b>layout</b>; a
 * dialog card is in its own window with no surface under it and moves by
 * <b>{@code translationY}</b>. That is {@link Host#applyPosition}. The second is
 * the fade: one progress value drives the content, the glass and, for a dialog,
 * the scrim. The third is the snap policy: a dialog is transient and must always
 * land on a stop, while the studio sheet is a persistent panel that keeps exactly
 * the position the finger left — free positioning, stored and restored. That
 * policy is an enum in the constructor rather than a condition inside the
 * release, so a host has to say which one it wants and the next sheet cannot
 * silently inherit the wrong one.
 *
 * <p><b>Commit nothing until release.</b> While the finger is down the sheet
 * moves and the committed state does not. Letting go is the decision, and
 * {@link SheetIntent} in :core decides it from the position and the release
 * velocity in fractions of travel. A settle that lands is the only other moment
 * the state changes, which is why the host hears about it through
 * {@link Host#onResting} rather than by watching the fraction.
 *
 * <p><b>The entrance is a position offset, not a height.</b> A spring from below
 * must not lay the sheet out at zero height: the strip and the renderer read the
 * panel's layout, and the first layout has to be the peek it will rest at. So
 * {@link #open} lays the surface out at its resting fraction first and then
 * animates a <i>below</i> offset from the full visible height to zero. The studio
 * host turns that offset into a negative {@code bottomMargin} — a layout change,
 * which is the movement b/7179570 permits and its own javadoc recommends — and
 * the dialog host into extra {@code translationY}. The height never changes
 * during an entrance; only where the laid-out surface sits.
 *
 * <p><b>The fade is alpha, never a transform.</b> One progress value — 0 at the
 * fade start, 1 at fully collapsed — drives three surfaces through
 * {@link Host#applyFade}: the content leads ({@link #contentAlpha}), the glass
 * follows ({@link #glassAlpha}), and a dialog's scrim brightens as the card comes
 * down. Alpha is a paint property, so it composites where a translation of the
 * same view would not, and the floors keep a half-collapsed sheet reading as a
 * surface with contents rather than as an empty pane.
 */
final class BottomSheet {

    /**
     * What a release may land on. This is deliberately an argument and not a
     * default: the two hosts want opposite things, and a host that has not
     * thought about it should not silently get either.
     */
    enum Snap {

        /**
         * Always land on a stop: fully open or fully collapsed/dismissed, never
         * mid-way. For a transient card — a settings modal, the depth panel, the
         * apply chooser — a half-height card is an unanswered question, and the
         * user asked for exactly this distinction.
         */
        TO_STOPS,

        /**
         * Decide collapse on release and otherwise keep the position the finger
         * left, exactly. For a persistent panel — the studio's sheet — whose
         * position is a place the user chose, not a state to be rounded to. The
         * position is handed back through {@link Host#onResting} so the host can
         * store and restore it.
         */
        FREE
    }

    /**
     * What a host has to provide. Deliberately tiny: the three differences that
     * are real (how a fraction becomes pixels, what the fade drives, and what a
     * release rests on) plus the scroll the gesture offers first refusal.
     */
    interface Host {

        /**
         * The full travel in pixels: the studio panel's natural height, or the
         * dialog card's measured height. Zero is a real answer before layout; the
         * component degrades to a jump rather than guessing a scale.
         */
        float spanPx();

        /**
         * Place the surface.
         *
         * @param fraction the collapse fraction: 0 fully open, 1 fully collapsed
         * @param belowPx an extra downward offset for the entrance, in pixels:
         *                positive is below the resting place, negative is the
         *                spring's overshoot above it. Always 0 while the finger is
         *                down and once a settle has landed.
         */
        void applyPosition(float fraction, float belowPx);

        /**
         * Fade by one progress value: 0 while the sheet is at or above its
         * resting-open range, 1 at fully collapsed. Content leads, glass follows,
         * and a dialog's scrim brightens; see {@link #contentAlpha},
         * {@link #glassAlpha} and the host's own scrim.
         */
        void applyFade(float progress);

        /** The scroll that gets first refusal on a drag, or null if the host has none. */
        View scrollView();

        /**
         * The resting state this host should now present — and, if it persists
         * its position, store. Called at an entrance's start (the position is
         * already chosen) and whenever a release comes to rest: the only two
         * moments the position or the state is decided.
         *
         * @param fraction where the sheet rests: 1 when collapsed
         * @param collapsed true when the sheet has committed to being away
         */
        void onResting(float fraction, boolean collapsed);
    }

    /**
     * The content never goes below this, so a sheet that is half way down still
     * reads as a surface with contents rather than as an empty pane. Fully
     * transparent at 50% is the failure this floor prevents.
     */
    static final float CONTENT_ALPHA_FLOOR = 0.15f;

    /**
     * The glass follows the content rather than leading it: it stays more legible
     * than the contents all the way down, because the glass is what still says
     * "a sheet is here" once the contents have dissolved.
     */
    static final float GLASS_ALPHA_FLOOR = 0.45f;

    /**
     * The fraction at which the fade starts. It is deliberately the same 0.60 as
     * {@link SheetIntent#COLLAPSE_THRESHOLD}: content begins to dissolve exactly
     * where a release would have collapsed the sheet, so the fade and the
     * decision agree about when the sheet is on its way out, and a sheet resting
     * below the line keeps its content fully legible.
     */
    static final float FADE_START = SheetIntent.COLLAPSE_THRESHOLD;

    private final Host host;
    private final SpringSettle.Profile profile;
    private final Snap snap;
    private final int slopPx;
    private final Runnable tapAction;

    /** Current collapse fraction, including drags and settles. 0 open, 1 away. */
    private float fraction;

    /** Entrance offset in pixels; see {@link Host#applyPosition}. */
    private float belowPx;

    /** The committed state, changed only by a settle landing or an entrance. */
    private boolean collapsed;

    /**
     * The last fraction the sheet rested at while not collapsed; where
     * {@link #openLast} returns to. Initialised by the first {@link #open} or
     * {@link #restAt}, so a pill tap after a collapse goes back to the place the
     * user chose rather than to a default.
     */
    private float lastResting = -1f;

    // ---- one gesture's state. All of it is meaningless outside DOWN..UP ----
    private boolean tracking;
    private long downTime;
    private boolean claimed;
    private boolean downInsideScroll;
    /** Anchor for the finger delta: rebased when the sheet claims mid-gesture. */
    private float anchorRawX;
    private float anchorRawY;
    private float anchorFraction;
    private SpringSettle.Drag drag;
    private ValueAnimator animator;

    /**
     * @param host the mechanism
     * @param profile the host's spring constants
     * @param snap what a release rests on; see {@link Snap}
     * @param slopPx the touch slop past which a drag claims the sheet
     * @param tapAction run when a touch releases without ever claiming; null for a
     *                  host whose grip does nothing on a tap. Kept separate from
     *                  the view's own click listener so the drag can consume
     *                  every event and still honour a tap.
     */
    BottomSheet(Host host, SpringSettle.Profile profile, Snap snap, int slopPx,
                Runnable tapAction) {
        this.host = host;
        this.profile = profile;
        this.snap = snap;
        this.slopPx = slopPx;
        this.tapAction = tapAction;
    }

    /** Whether the sheet has committed to being away. See the class javadoc. */
    boolean isCollapsed() {
        return collapsed;
    }

    /**
     * Spring open from below, resting at {@code at}.
     *
     * <p>Used for the studio's entrance at the peek or its stored position, and
     * for a pill tap's rise from a zero-height layout. The surface is laid out at
     * its resting fraction first — height, not zero, because the host's first
     * layout is read by the strip and the renderer — and the below offset then
     * carries it off the bottom and back with the shared spring.
     *
     * @param at the resting collapse fraction: the peek, a stored position, or 0
     *           for fully open
     */
    void open(float at) {
        cancelAnimation();
        endTracking();
        fraction = clamp01(at);
        belowPx = 0f;
        collapsed = false;
        lastResting = fraction;
        apply();
        host.onResting(fraction, false);
        float span = host.spanPx();
        if (span <= 0f) return;
        // The offset that puts the whole resting surface below the window's
        // bottom edge: everything that was not showing is what slides away.
        belowPx = (1f - fraction) * span;
        apply();
        animateBelowTo(0f, 0f);
    }

    /**
     * Spring open to the last position the sheet rested at while not collapsed.
     *
     * <p>This is what a pill tap means for a free-position host: not "fully open"
     * and not "the default", but the place the user chose before putting the sheet
     * away. A host that has never left the sheet open falls back to fully open,
     * which is the only position that is always sensible.
     */
    void openLast() {
        open(lastResting >= 0f ? lastResting : 0f);
    }

    /**
     * Place the sheet at rest, collapsed or not, with no motion.
     *
     * <p>For a launch restoring a stored position that says "away": the pill is
     * shown and the panel is at zero height from the first frame, exactly as the
     * old launch showed a collapsed sheet. Springing a collapse on launch would
     * animate the app deciding what the user already decided.
     */
    void restAt(float at, boolean restCollapsed) {
        cancelAnimation();
        endTracking();
        fraction = clamp01(at);
        belowPx = 0f;
        collapsed = restCollapsed;
        apply();
        if (!restCollapsed) lastResting = fraction;
        host.onResting(fraction, collapsed);
    }

    /**
     * Re-apply the current position with no motion.
     *
     * <p>For a layout change under the sheet — the Framing group expanding, a
     * content swap — where the height was recomputed and the fraction has to be
     * spent against the new height without a settle.
     */
    void reapply() {
        belowPx = 0f;
        apply();
    }

    /**
     * Animate to {@code target} with the host's spring, starting from the release
     * speed. Used by the release rule and by the back gesture's collapse.
     *
     * @param targetFraction where to land; clamped to the travel range
     * @param velocityPxPerS the release velocity in px/s, positive down; zero for a
     *                       programmatic settle
     */
    private void settleTo(float targetFraction, float velocityPxPerS) {
        cancelAnimation();
        float span = host.spanPx();
        // Fold a running entrance back into the fraction first, the same way a
        // drag claim does: the below offset is the off-screen part of the travel,
        // and a settle that ignored it would snap the sheet up to its resting
        // height for one frame before animating.
        if (span > 0f && belowPx != 0f) {
            fraction = Math.min(1f, fraction + belowPx / span);
            belowPx = 0f;
            apply();
        }
        float to = clamp01(targetFraction);
        if (span <= 0f || Math.abs(to - fraction) < 1e-4f) {
            fraction = to;
            belowPx = 0f;
            apply();
            commit(to);
            return;
        }
        final float from = fraction;
        long duration = profile.duration(to - from, velocityPxPerS / span);
        ValueAnimator settle = ValueAnimator.ofFloat(from, to);
        settle.setDuration(duration);
        settle.setInterpolator(profile.spring());
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                fraction = (Float) a.getAnimatedValue();
                belowPx = 0f;
                apply();
            }
        });
        settle.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                // onAnimationEnd also fires for a cancel; isRunning() cannot tell
                // them apart, so the distinction is recorded here. A settle the
                // finger interrupted must not report a commit it never reached.
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                animator = null;
                fraction = to;
                belowPx = 0f;
                apply();
                commit(to);
            }
        });
        animator = settle;
        settle.start();
    }

    /** The back gesture's one step out: to fully away. */
    void collapse() {
        settleTo(1f, 0f);
    }

    /** Stop any in-flight motion. The position is left wherever it was. */
    void cancel() {
        cancelAnimation();
        endTracking();
    }

    // ---------------------------------------------------------------- gestures

    /**
     * The host surface's own touch path: the grip, the panel's empty areas, a
     * dialog card. Always consumes, so the gesture cannot fall through to the
     * render underneath.
     */
    boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (!claimed) {
                    if (!pastSlop(e) || !mostlyVertical(e)) return true;
                    claim(e);
                }
                dragTo(e.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return true;
                if (claimed) {
                    release();
                } else {
                    endTracking();
                    if (tapAction != null) tapAction.run();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return true;
                if (claimed) {
                    release();
                } else {
                    endTracking();
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * The host ViewGroup's intercept path: the studio panel, where a child may
     * have been offered the gesture first.
     *
     * <p>The rule, in one sentence: <b>a vertical drag moves the sheet unless a
     * control below it claimed the gesture, or the scrolling content can still
     * use it in that direction.</b> A slider's own
     * {@code requestDisallowInterceptTouchEvent(true)} keeps its drag from ever
     * arriving here; the scroll is offered the direction and keeps the gesture
     * while it can scroll that way, and the sheet takes over the moment it
     * cannot — the standard nested behaviour. Everything else (the grip, the
     * strip, the fix line, captions, the gaps) belongs to the sheet.
     *
     * @param insideScroll whether the DOWN landed inside {@link Host#scrollView};
     *                     only read on DOWN
     */
    boolean onIntercept(MotionEvent e, boolean insideScroll) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downInsideScroll = insideScroll;
                begin(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (claimed) return true;
                if (!tracking) return false;
                if (!pastSlop(e) || !mostlyVertical(e)) return false;
                if (downInsideScroll && scrollCanTake(e)) return false;
                claim(e);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // A child (or the scroll) owned this gesture to the end; the
                // sheet never claimed it. If the sheet HAD claimed it, the events
                // are flowing to the host's own touch listener, not here.
                if (!claimed) endTracking();
                return false;
            default:
                return false;
        }
    }

    /**
     * Begin tracking a gesture. Idempotent for one DOWN: the intercept path and
     * the host's own touch listener can both be handed the same event, and the
     * second delivery must not replace the velocity tracker the first one
     * started.
     */
    private void begin(MotionEvent e) {
        if (tracking && e.getDownTime() == downTime) return;
        tracking = true;
        downTime = e.getDownTime();
        claimed = false;
        anchorRawX = e.getRawX();
        anchorRawY = e.getRawY();
        anchorFraction = fraction;
        if (drag != null) drag.end();
        drag = new SpringSettle.Drag();
        drag.add(e);
    }

    /**
     * The sheet takes the gesture. The anchor is rebased to this event, so the
     * sheet starts moving from where it is: a scroll that has just reached its
     * end must not make the panel jump by the distance the scroll travelled, and
     * the touch slop must not make it jump either. Velocity tracking is
     * unaffected — the tracker saw every event from the DOWN.
     *
     * <p>An entrance grabbed mid-flight is folded back into the fraction first:
     * the below offset is exactly the part of the travel that is still off
     * screen, so {@code fraction + below/span} is the position the eye can see,
     * and the drag continues from there instead of snapping up to the resting
     * placement. A tap that never claims leaves the entrance running, which is
     * why this is not done in {@link #begin}.
     */
    private void claim(MotionEvent e) {
        cancelAnimation();
        float span = host.spanPx();
        if (span > 0f && belowPx != 0f) {
            fraction = Math.min(1f, fraction + belowPx / span);
            belowPx = 0f;
            apply();
        }
        claimed = true;
        anchorRawX = e.getRawX();
        anchorRawY = e.getRawY();
        anchorFraction = fraction;
    }

    /** Follow the finger. Every move resolves from the anchor, never incrementally. */
    private void dragTo(float rawY) {
        float span = host.spanPx();
        if (span <= 0f) return;
        float raw = anchorFraction + (rawY - anchorRawY) / span;
        // Tethered above fully open and hard-clamped at fully away: past open
        // there is a little glass to give, past closed there is nothing, and the
        // pill is where the sheet has gone.
        float next = raw < 0f ? SpringSettle.resistUp(raw * span, span) / span : raw;
        if (next > 1f) next = 1f;
        fraction = next;
        belowPx = 0f;
        apply();
    }

    /**
     * Let go: the decision, then whatever {@link Snap} says a landing means.
     *
     * <p><b>The studio sheet must not snap.</b> {@link Snap#FREE} keeps exactly
     * the position the finger left unless the release was a collapse; expanding
     * is not a stop it snaps to, it is simply "do not collapse". {@link
     * Snap#TO_STOPS} is the dialog's rule: every release lands on open or gone,
     * because a transient card parked at 40% is an unanswered question.
     */
    private void release() {
        float velocityPx = drag == null ? 0f : drag.velocityY();
        endTracking();
        float span = host.spanPx();
        if (span <= 0f) return;
        SheetIntent.Decision decision = SheetIntent.decide(fraction, velocityPx / span);
        if (decision == SheetIntent.Decision.COLLAPSE) {
            settleTo(1f, velocityPx);
        } else if (snap == Snap.TO_STOPS) {
            // EXPAND and the dead band both mean "back up" for a transient card;
            // there is no free position for it to stay in.
            settleTo(0f, velocityPx);
        } else {
            rest();
        }
    }

    /**
     * A free release comes to rest where it is: no motion, no rounding. The host
     * is told so it can persist the position, which is what makes the studio
     * sheet reopen where the user left it.
     */
    private void rest() {
        belowPx = 0f;
        lastResting = fraction;
        apply();
        host.onResting(fraction, false);
    }

    /** The entrance's own settle: the below offset to zero, same curve. */
    private void animateBelowTo(float toBelow, float velocityPxPerS) {
        cancelAnimation();
        final float from = belowPx;
        float span = Math.max(1f, host.spanPx());
        ValueAnimator entrance = ValueAnimator.ofFloat(from, toBelow);
        entrance.setDuration(
                profile.duration((toBelow - from) / span, velocityPxPerS / span));
        entrance.setInterpolator(profile.spring());
        entrance.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                belowPx = (Float) a.getAnimatedValue();
                apply();
            }
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                animator = null;
                belowPx = toBelow;
                apply();
            }
        });
        animator = entrance;
        entrance.start();
    }

    /** The resting state is decided; the host presents it and may store it. */
    private void commit(float target) {
        if (target >= 1f) {
            collapsed = true;
        } else if (target <= 0f) {
            collapsed = false;
        }
        host.onResting(fraction, collapsed);
    }

    private void apply() {
        host.applyPosition(fraction, belowPx);
        host.applyFade(fadeProgress(fraction));
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void endTracking() {
        if (drag != null) {
            drag.end();
            drag = null;
        }
        tracking = false;
        claimed = false;
    }

    private boolean pastSlop(MotionEvent e) {
        return Math.abs(e.getRawY() - anchorRawY) > slopPx;
    }

    /** A drag that is not clearly vertical belongs to the strip's horizontal scroll. */
    private boolean mostlyVertical(MotionEvent e) {
        return Math.abs(e.getRawY() - anchorRawY) > Math.abs(e.getRawX() - anchorRawX);
    }

    /**
     * Whether the scrolling content can still use this drag in its own direction.
     *
     * <p>{@code canScrollVertically(-1)} is "can scroll toward the top", which is
     * what a downward finger (positive dy) asks the content for. When it cannot —
     * the content is already at the top — the sheet takes the same drag, which is
     * the standard nested behaviour.
     */
    private boolean scrollCanTake(MotionEvent e) {
        View scroll = host.scrollView();
        if (scroll == null) return false;
        float dy = e.getRawY() - anchorRawY;
        return scroll.canScrollVertically(dy > 0f ? -1 : 1);
    }

    /**
     * The drag's fade progress: 0 while the sheet is at or above the fade start,
     * 1 at fully collapsed. One value drives the content, the glass and a
     * dialog's scrim, so the three cannot disagree about how far the drag is.
     *
     * <p>The start is {@link SheetIntent#COLLAPSE_THRESHOLD}: content begins to
     * dissolve exactly where a release would have collapsed the sheet, so the
     * fade and the decision agree about when the sheet is on its way out, and a
     * sheet resting below the line keeps its content fully legible.
     */
    static float fadeProgress(float fraction) {
        if (fraction <= FADE_START) return 0f;
        float progress = (fraction - FADE_START) / (1f - FADE_START);
        return progress > 1f ? 1f : progress;
    }

    /**
     * The content's alpha at a fade progress: 1 at the start, linearly to
     * {@link #CONTENT_ALPHA_FLOOR} at fully collapsed. The content leads.
     *
     * <p>Linear is enough — the eye reads the fade as motion, not as a curve — and
     * the floor is the part that matters: transparent-at-50% would leave a sheet
     * with visible glass and no contents while a drag is still in progress.
     */
    static float contentAlpha(float progress) {
        return 1f - (1f - CONTENT_ALPHA_FLOOR) * progress;
    }

    /**
     * The glass's alpha at the same progress: it follows the content rather than
     * leading it, and its floor is much higher, because the glass is what still
     * says "a sheet is here" once the contents have dissolved.
     */
    static float glassAlpha(float progress) {
        return 1f - (1f - GLASS_ALPHA_FLOOR) * progress;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
