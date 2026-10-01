package com.amoshydra.basrelief;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A dashed outline, used by the fix line.
 *
 * <p>Not a rounded card and not a solid border. The distinction is the point: a solid
 * border reads as another panel, and this is an interruption -- something that is
 * wrong right now and goes away when it is fixed. A dashed line is the shape
 * language for "tentative", and it means the box can appear and disappear without
 * the layout feeling like it gained or lost a component.
 *
 * <p>{@link #setDither(boolean)} is on because a 1px dashed line on a dark
 * background at a non-integer position is exactly where banding shows up, and this
 * is the kind of thing that looks fine in a screenshot and shimmers on the device.
 *
 * <p>The dash pattern is fixed rather than derived from the measured bounds, because
 * deriving it means the dash count changes with width and a resizing sheet animates
 * its own border. A stable pattern is worth more here than a flush edge.
 */
final class DashBorder extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float strokeWidth;
    private final float[] dash;

    DashBorder(int strokeWidthPx, float dashLengthPx, int color) {
        this.strokeWidth = strokeWidthPx;
        this.dash = new float[]{dashLengthPx, dashLengthPx};
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidthPx);
        paint.setColor(color);
        // A round cap closes each dash into a dot rather than leaving it a
        // 1px-tall sliver, which at this width is what made it read as solid.
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Set once, here, not per draw. A PathEffect holds native state and this
        // Drawable can outlive an Activity across a configuration change, so holding
        // one is a small leak for no benefit -- but there is no reason to allocate a
        // new one every frame either.
        paint.setPathEffect(new DashPathEffect(dash, 0f));
        // A 1px dashed line on a dark background at a non-integer position is exactly
        // where banding shows up, and this is the kind of thing that looks right in a
        // screenshot and shimmers on the device.
        paint.setDither(true);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float half = strokeWidth / 2f;
        // Inset by half the stroke so the border sits inside the bounds rather than
        // straddling them -- otherwise it is clipped on the outside and the dash
        // pattern loses its first and last segment.
        canvas.drawRoundRect(
                b.left + half, b.top + half,
                b.right - half, b.bottom - half,
                strokeWidth * 2f, strokeWidth * 2f, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

}
