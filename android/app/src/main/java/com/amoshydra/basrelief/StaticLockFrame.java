package com.amoshydra.basrelief;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.util.Log;

import com.amoshydra.basrelief.core.store.SurfaceResolver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The still frame the lock screen gets where no live engine can reach it.
 *
 * <p>Below API 35 the platform runs one live engine, on the home screen, and the lock
 * screen gets a flat image. The apply chooser says so — "the lock screen keeps a still
 * frame" — and until now nothing put one there, so the sentence described something
 * that did not happen.
 *
 * <p><b>Why a readback rather than a re-render.</b> The frame has to be the frame the
 * user is looking at, which is a function of every adjustment, the camera, the mesh and
 * the fade. Re-deriving it on another thread would mean a second copy of all of that,
 * and the two would disagree the moment anything changed. Reading the pixels that were
 * just drawn is the only version that cannot drift.
 *
 * <p><b>Why GLES20.glReadPixels and not glReadBuffer.</b> The latter is the correct
 * call for a framebuffer object and is not in GLES2. Reading the default framebuffer
 * after a draw is the portable form, and the cost is one pipeline stall per capture —
 * which is why this is not on any per-frame path.
 *
 * <p><b>Why it is not the whole story.</b> A lock frame is captured from whatever is
 * live <i>at the moment of capture</i>, so it does not tilt. That is what "flat" means
 * and the chooser's wording says so rather than promising parallax it cannot deliver.
 */
final class StaticLockFrame {

    private StaticLockFrame() {
    }

    /**
     * Whether this platform needs one at all.
     *
     * <p>Asked of {@link SurfaceResolver} rather than of {@code Build.VERSION}, because
     * that class holds the tested truth table: 35+ has a second live engine, 34 knows
     * which screen it is but still runs only one, and 26–33 have no signal at all.
     */
    static boolean isNeeded() {
        return SurfaceResolver.of(android.os.Build.VERSION.SDK_INT).lockSupport()
                == SurfaceResolver.LockSupport.STATIC_FRAME;
    }

    /**
     * Read the framebuffer back as a bitmap.
     *
     * <p><b>MUST be called on the GL thread</b>, immediately after a draw and before
     * the next {@code eglSwapBuffers} — the default framebuffer's contents are
     * undefined after the swap. Returns null rather than throwing: a capture that
     * cannot happen is not a crash, and the caller has a perfectly good thing to do
     * instead, which is leave the lock screen alone.
     *
     * @return an ARGB_8888 bitmap, flipped upright, or null if there is nothing to read
     */
    static Bitmap capture(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        final int bytes = width * height * 4;
        if (bytes <= 0) return null;

        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        buffer.order(ByteOrder.nativeOrder());
        try {
            GLES20.glReadPixels(0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        } catch (RuntimeException e) {
            // No current context, or the driver refused. Either way there is no frame.
            Log.w("Basrelief", "lock frame: readback failed: " + e);
            return null;
        }
        if (buffer.position() != 0) {
            // glReadPixels advances the position. Rewind before wrapping, or the
            // Bitmap constructor silently reads nothing and produces a black image --
            // which looks exactly like a successfully captured black wallpaper.
            buffer.rewind();
        }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            Log.w("Basrelief", "lock frame: glReadPixels reported an error");
            return null;
        }

        Bitmap out;
        try {
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            out.copyPixelsFromBuffer(buffer);
        } catch (RuntimeException e) {
            // Out of memory on a 1440x3120 frame is a real possibility and a real
            // reason to give up quietly rather than to take the wallpaper down.
            Log.w("Basrelief", "lock frame: could not wrap the pixels: " + e);
            return null;
        }
        return flip(out);
    }

    /**
     * GL's origin is bottom-left; a Bitmap's is top-left.
     *
     * <p>Without this the lock screen shows the photo upside down, which is the kind
     * of bug that reads as "the wallpaper is broken" rather than as an orientation
     * mistake. The same inversion the colour and depth textures get in the upload path
     * — see the note on {@code GLUtils.texImage2D} in AGENTS.md.
     */
    private static Bitmap flip(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        // Mirror vertically: row 0 of the GL image is the BOTTOM of the picture.
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(1f, -1f, w / 2f, h / 2f);
        Bitmap flipped = Bitmap.createBitmap(out, 0, 0, w, h, m, true);
        if (flipped != out) {
            out.recycle();
        }
        src.recycle();
        return flipped;
    }

    /**
     * Hand the frame to the system as the lock-screen wallpaper.
     *
     * <p><b>MUST NOT be called on the main thread.</b> {@code setBitmap} writes the
     * image through to the wallpaper service, which is a binder call followed by file
     * I/O on the service's side; on the main thread that is an ANR waiting to happen
     * on a large frame.
     *
     * <p>Also: this REPLACES the live wallpaper for the lock screen. That is the
     * intent below 35 — there is no second live engine to replace — but it is why the
     * chooser words it as a still frame rather than as a live one.
     *
     * @return true if the platform accepted it
     */
    static boolean publish(Context context, Bitmap frame) {
        if (frame == null || frame.isRecycled()) return false;
        try {
            WallpaperManager wm = (WallpaperManager)
                    context.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) return false;
            wm.setBitmap(frame, null, true, WallpaperManager.FLAG_LOCK);
            return true;
        } catch (Exception e) {
            // SecurityException if the app lacks SET_WALLPAPER (it declares it), and
            // IllegalArgumentException for a zero-sized bitmap on some OEM builds.
            // Either way the wallpaper stays as it was, which is the safe outcome.
            Log.w("Basrelief", "lock frame: setBitmap refused: " + e);
            return false;
        } finally {
            frame.recycle();
        }
    }
}