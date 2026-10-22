package com.amoshydra.basrelief;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.amoshydra.basrelief.core.depth.DepthPng;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Downscales very large images before they are stored, uploaded or textured.
 * A 3000x4000 photo is far larger than any phone screen or GL texture needs;
 * capping the longest side keeps memory low without visible quality loss.
 *
 * PNGs that carry an embedded depth chunk (dEPt/dePH) are returned untouched —
 * resizing would strip the chunk, and those files are app-generated anyway.
 */
public final class ImageUtil {

    /** Longest-side cap applied to picked/shared images before use. */
    public static final int MAX_SIDE = 2560;

    private ImageUtil() {
    }

    /** Returns the resized PNG bytes, or the original bytes if small / has depth. */
    public static byte[] downscale(byte[] src, int maxSide) {
        if (src == null) return null;
        if (DepthPng.extract(src) != null) return src; // keep embedded depth intact

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return src;

        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= maxSide) return src;

        // power-of-two sample step so decoding never allocates the full image
        int sample = 1;
        while (longest / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(src, 0, src.length, opts);
        if (bmp == null) return src;

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int nl = Math.max(w, h);
        if (nl > maxSide) {
            float k = (float) maxSide / nl;
            int nw = Math.max(1, Math.round(w * k));
            int nh = Math.max(1, Math.round(h * k));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    /** Convenience: downscale a file into a new file (or same path overwritten). */
    public static File downscaleFile(File src, File dst, int maxSide) throws Exception {
        FileInputStream in = new FileInputStream(src);
        byte[] data;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            data = bos.toByteArray();
        } finally {
            in.close();
        }
        byte[] out = downscale(data, maxSide);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            fos.write(out);
        } finally {
            fos.close();
        }
        return dst;
    }
}
