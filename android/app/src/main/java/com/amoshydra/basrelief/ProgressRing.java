package com.amoshydra.basrelief;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The pill's progress ring: a 15dp arc that turns once per run.
 *
 * <p>Exists because {@code ProgressBar} cannot be made to look like this. A platform
 * indeterminate drawable is a full circle whose sweep the framework drives, sized by
 * the widget and tinted by the theme — on a dark sheet at 15dp it reads as a grey
 * blob, and there is no supported way to restyle it that does not also restyle every
 * other progress indicator in the app.
 *
 * <p>So it is drawn here: a fixed stroke width, a fixed track, and a head that
 * {@link #start()} advances. Animation is driven by {@code invalidateSelf} on a
 * handler rather than by a {@code ValueAnimator}, because the pill's whole job is to
 * be visible when the sheet is <i>not</i> -- and an animator attached to a view the
 * sheet has hidden is an animator nobody cancels.
 */
final class ProgressRing extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float strokePx;
    /** The arc's own colour: the track is dim, the head is the caller's. */
    private final int headColour;

    /** 0..1 around the circle. Drives the sweep, not the rotation. */
    private float phase;
    private boolean spinning;

    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!spinning) return;
            phase = (phase + 0.045f) % 1f;
            invalidateSelf();
            handler.postDelayed(this, 16);
        }
    };

    ProgressRing(int diameterPx, int strokePx, int colour, int trackColour) {
        this.strokePx = strokePx;
        this.headColour = colour;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(colour);
        this.trackColour = trackColour;
    }

    private final int trackColour;

    void start() {
        spinning = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        spinning = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float h = strokePx / 2f + 1f;
        bounds.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
    }

    @Override
    public void draw(Canvas canvas) {
        paint.setColor(trackColour);
        canvas.drawArc(bounds, 0, 360, false, paint);
        // A quarter of the circle, so it reads as motion rather than as a pie chart.
        // The HEAD IS THE CONSTRUCTOR'S COLOUR. This was the literal accent, so the
        // pill's ring -- which asks for white in so many words -- drew a mint head
        // and its own caller said otherwise; one of the two had to give, and the
        // caller was right: a component that ignores the colour it is handed is a
        // second palette nobody can find.
        paint.setColor(headColour);
        canvas.drawArc(bounds, phase * 360f, 90f, false, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}