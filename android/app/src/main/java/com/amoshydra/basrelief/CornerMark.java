package com.amoshydra.basrelief;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * One tile's bottom-right corner: what this row is <b>doing</b>.
 *
 * <p><b>One slot, three meanings, and the strip and the grid must agree.</b> The
 * gallery already draws the clip mark there (the grid's {@code clipMark}): a
 * photograph and a clip are both ordinary rows, so the corner is the one place
 * the difference is stated. A clip import needs the same slot for two more
 * states — a ring while the server works, an amber warning when it stops — so
 * they live in one component rather than as three marks that could disagree
 * about a row:
 *
 * <ul>
 *   <li>{@link State#RING}: a job is pending; the ring runs for the whole server
 *       round, minutes at a time. The ring is the pill's own {@link ProgressRing},
 *       handed the accent because that is the colour the design draws on a tile;
 *       the pill keeps asking for white.</li>
 *   <li>{@link State#CLIP}: the clips landed; the corner becomes the clip mark
 *       and the row plays.</li>
 *   <li>{@link State#FAILED}: the run stopped. The glyph holds the corner until
 *       the user acts (Regenerate, or a delete), because this failure can arrive
 *       ten minutes after the share, with the phone face down.</li>
 * </ul>
 *
 * <p><b>{@code NONE} is a real state and the ring is stopped there.</b> The
 * spinner's tick is a {@code Handler} post that keeps a detached drawable alive
 * and invalidating itself; a state machine that only ever started rings would
 * leak one per tile per rebuild — which is why {@link #setState} stops it on
 * every exit, and {@link #stop()} exists for the host to call when it throws the
 * tile away.
 *
 * <p>Not in {@link PhotoTile}: a tile draws what <i>every</i> home draws — the
 * rings and the picture. This mark is about one host's job and is added by the
 * host, the same rule the grid's depth chip and row menu follow.
 */
final class CornerMark extends FrameLayout {

    /** What the corner is currently saying. */
    enum State {
        /** Nothing is running: the corner is empty. */
        NONE,
        /** An import is pending on the server. */
        RING,
        /** A clip that plays. */
        CLIP,
        /** An import that stopped and needs the user. */
        FAILED
    }

    /** The plate behind a glyph: a dark mark on a dark photograph is unreadable. */
    private static final int PLATE = 0x99000000;
    /** The failed state's ink: the app's warn token, matching the sheet's line. */
    private static final int WARN = 0xFFE8B04B;
    private static final int WHITE = 0xFFFFFFFF;

    private final MainActivity activity;
    private final View ringView;
    private final ProgressRing ring;
    private final TextView glyph;

    private State state = State.NONE;

    CornerMark(MainActivity activity, int sizePx) {
        super(activity);
        this.activity = activity;

        // The ring is a Drawable, so it needs a view to be the background of --
        // the same shape MainActivity's strip mark uses.
        ring = new ProgressRing(sizePx, activity.dp(2), PhotoTile.ACCENT, 0x47FFFFFF);
        ringView = new View(activity);
        ringView.setBackground(ring);
        ringView.setVisibility(GONE);
        addView(ringView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        glyph = new TextView(activity);
        glyph.setGravity(Gravity.CENTER);
        // Pixels, not sp: the host passes the slot's pixel size, and 15dp and 19dp
        // are the same design at two host scales.
        glyph.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.55f);
        GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.OVAL);
        plate.setColor(PLATE);
        glyph.setBackground(plate);
        glyph.setVisibility(GONE);
        addView(glyph, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        apply();
    }

    /**
     * Say one of the three things, and stop saying the one before it.
     *
     * <p>An unchanged state is a no-op on purpose: the ring restarts its phase if
     * it is re-started, and a rebuild that re-set every mark would make the whole
     * strip's rings jitter together.
     */
    void setState(State next) {
        if (state == next) return;
        state = next;
        apply();
    }

    /**
     * Stop the ring while keeping the state.
     *
     * <p>For a host that is about to drop the view: the drawable is detached, but
     * its handler tick is not, and an invalidating detached drawable is a leak
     * that grows with every rebuild.
     */
    void stop() {
        ring.stop();
    }

    private void apply() {
        switch (state) {
            case RING:
                setVisibility(VISIBLE);
                glyph.setVisibility(GONE);
                ringView.setVisibility(VISIBLE);
                ring.start();
                setContentDescription(activity.getString(R.string.gallery_importing));
                break;
            case CLIP:
                stop();
                ringView.setVisibility(GONE);
                showGlyph(activity.getString(R.string.gallery_clip_glyph), WHITE,
                        activity.getString(R.string.gallery_is_clip));
                break;
            case FAILED:
                stop();
                ringView.setVisibility(GONE);
                showGlyph(activity.getString(R.string.gallery_import_warning_glyph),
                        WARN, activity.getString(R.string.gallery_import_failed));
                break;
            case NONE:
            default:
                stop();
                ringView.setVisibility(GONE);
                glyph.setVisibility(GONE);
                setVisibility(GONE);
                setContentDescription(null);
                break;
        }
    }

    private void showGlyph(String text, int colour, String description) {
        glyph.setText(text);
        glyph.setTextColor(colour);
        glyph.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
        setContentDescription(description);
    }
}
