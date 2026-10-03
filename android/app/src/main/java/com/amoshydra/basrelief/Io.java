package com.amoshydra.basrelief;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs blocking file/bitmap work off the UI thread and delivers the result back
 * on it.
 *
 * <p>This exists because PNG encoding is slow: {@link ImageUtil#downscale}
 * decodes and re-encodes at {@code CompressFormat.PNG, 100}, which is lossless
 * and takes seconds on a 12MP bitmap. Doing that inline in
 * {@code onActivityResult} or a share handler freezes the UI long enough to be
 * an ANR. The app also has no {@code largeHeap}, so buffering a big image is a
 * real OOM risk.
 *
 * <p>One shared single-threaded executor, so overlapping picks queue instead of
 * racing on the same output file. Shutdown is driven by
 * {@link MainActivity#onDestroy()}, which outlives every dialog in the app.
 */
final class Io {

    interface Callback<T> {
        /** Always delivered on the UI thread. Exactly one of the two is non-null. */
        void onDone(T result, Exception error);
    }

    private static ExecutorService exec;

    private static synchronized ExecutorService exec() {
        // Recreated after shutdown: the activity can legitimately be destroyed
        // and recreated (low memory, "don't keep activities"), and a permanently
        // dead executor would silently break the image picker for the rest of
        // the process's life.
        if (exec == null || exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor();
        }
        return exec;
    }

    private Io() {
    }

    /**
     * Decode a small preview into a view, off the UI thread, at a fixed size.
     *
     * <p>Gallery thumbnails, so the decode is a two-line inSampleSize calculation
     * rather than a full-size bitmap: a 4000x3000 photo decoded at full size for a
     * 56px tile is 48MB of heap for something the user will never look at closely,
     * and the list would stutter while it happened.
     *
     * <p>Silently does nothing on failure. A row that cannot show a thumbnail
     * should still be a usable row, and a decode error is not worth interrupting
     * browsing to report.
     */
    static void decodePreview(final Activity activity, final File file,
                              final ImageView into, final int sizePx) {
        decodePreview(activity, file, into, sizePx, null);
    }

    /**
     * As above, and also reports whether the PNG carries its own depth map.
     *
     * <p>Separate from the thumbnail because the two questions have different
     * costs and different lifetimes: the picture is wanted now, and the depth
     * answer only changes the row's subtitle. Both come out of the one read.
     *
     * @param onEmbedded may be null
     */
    static void decodePreview(final Activity activity, final File file,
                              final ImageView into, final int sizePx,
                              final EmbeddedDepthListener onEmbedded) {
        run(activity, new Callable<Preview>() {
            @Override
            public Preview call() {
                return preview(file, sizePx);
            }
        }, new Callback<Preview>() {
            @Override
            public void onDone(Preview p, Exception error) {
                if (p == null) return;
                if (p.bitmap != null) into.setImageBitmap(p.bitmap);
                if (onEmbedded != null) onEmbedded.onFound(p.embeddedDepth);
            }
        });
    }

    /**
     * Decode a small preview of a packaged resource into a view, off the UI
     * thread, at the same size and sample scale as the file form above.
     *
     * <p>The bundled demo is the reason this exists. It is an ordinary gallery
     * row with no file on disk — that filelessness is exactly what makes it the
     * bundled row — so the file path cannot serve it and its thumbnail drew a
     * flat placeholder while every other row showed its photograph. Its bytes
     * live in {@code res/raw} at import size, so it takes the same two-line
     * sample calculation: a full-size decode for a 46px tile is tens of
     * megabytes of heap, and a resource decode is no more free than a file one,
     * which is why it runs on the same worker thread.
     *
     * <p>No embedded-depth listener here. The demo's map is a separate
     * {@code res/raw/depth.png} rather than a chunk inside these bytes, so
     * there is nothing in them to find; the grid's chip comes from
     * {@code canStandOut} instead.
     */
    static void decodeResourcePreview(final Activity activity, final int resId,
                                      final ImageView into, final int sizePx) {
        run(activity, new Callable<Bitmap>() {
            @Override
            public Bitmap call() {
                return resourcePreview(activity, resId, sizePx);
            }
        }, new Callback<Bitmap>() {
            @Override
            public void onDone(Bitmap bitmap, Exception error) {
                if (bitmap != null) into.setImageBitmap(bitmap);
            }
        });
    }

    private static Bitmap resourcePreview(Activity activity, int resId, int sizePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(activity.getResources(), resId, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(
                    Math.max(bounds.outWidth, bounds.outHeight), sizePx);
            return BitmapFactory.decodeResource(activity.getResources(), resId, opts);
        } catch (Throwable ignored) {
            // Missing or unreadable resource: the tile keeps its background,
            // exactly as a missing file does on the file path.
            return null;
        }
    }

    /** Told whether a PNG carries an embedded depth map. Always on the UI thread. */
    interface EmbeddedDepthListener {
        void onFound(boolean embedded);
    }

    /**
     * What a gallery row needs to know about one photo, from a single read.
     *
     * <p>The thumbnail and the embedded-depth answer come from one pass on
     * purpose. The embedded depth lives in a PNG ancillary chunk, so answering
     * it means reading the file, and a gallery row that read it separately would
     * read every photo twice -- on a list with no view recycling, which is every
     * photo in the library.
     */
    static final class Preview {
        Bitmap bitmap;
        /** True when the PNG carries its own depth map in a dEPt/dePH chunk. */
        boolean embeddedDepth;
    }

    private static Preview preview(File file, int sizePx) {
        Preview out = new Preview();
        try {
            byte[] bytes = readAll(file);
            if (bytes == null) return out;
            out.embeddedDepth = DepthPng.extract(bytes) != null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(
                    Math.max(bounds.outWidth, bounds.outHeight), sizePx);
            out.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable ignored) {
            // Out of memory, or an unreadable file. Either way the row still
            // works: no picture, and the depth answer falls back to "no file".
        }
        return out;
    }

    /**
     * The power-of-two sub-sample that keeps the longest side at or above
     * {@code sizePx}.
     *
     * <p>Shared by the file and resource decodes so the bundled demo's thumbnail
     * is built at exactly the scale of every other tile — the two paths are one
     * implementation with two byte sources, not two sizes that have to be kept
     * in step by hand.
     */
    private static int sampleSize(int longest, int sizePx) {
        int sample = 1;
        while (longest / (sample * 2) >= sizePx) sample *= 2;
        return sample;
    }

    private static byte[] readAll(File file) throws Exception {
        if (file == null) return null;
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Fire and forget: run on the shared thread and drop both the result and any
     * exception.
     *
     * <p>For work whose only effect is on disk, where a failure has nothing to
     * report to (deleting an already-deleted file) and no UI to change. It
     * exists so those call sites do not have to write a no-op callback that reads
     * as though the result were being used.
     *
     * <p>Swallows the exception rather than letting it reach the executor's
     * uncaught handler, which on Android kills the process.
     */
    static <T> void run(final Callable<T> work) {
        exec().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    work.call();
                } catch (Exception ignored) {
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static <T> void run(final Activity activity, final Callable<T> work,
                        final Callback<T> callback) {
        exec().execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = work.call();
                } catch (Exception e) {
                    error = e;
                }
                final T r = result;
                final Exception e = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDone(r, e);
                    }
                });
            }
        });
    }

    /**
     * Run work on the shared background thread and deliver the result to
     * {@code deliver} on whichever thread you choose.
     *
     * <p>Same executor as {@link #run(Activity, Callable, Callback)}, but for
     * callers with no Activity to post back to — the wallpaper service. Pass the
     * GL thread's own dispatcher as {@code deliver} when the result has to be
     * applied to GL state.
     */
    static <T> void run(final Callable<T> work, final Callback<T> callback,
                        final java.util.concurrent.Executor deliver) {
        exec().execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = work.call();
                } catch (Exception e) {
                    error = e;
                }
                final T r = result;
                final Exception e = error;
                deliver.execute(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDone(r, e);
                    }
                });
            }
        });
    }

    /** Called when the activity goes away, so the worker thread does not linger. */
    static void shutdown() {
        synchronized (Io.class) {
            if (exec != null) {
                exec.shutdown();
                exec = null;
            }
        }
    }

    /** Read a content:// or file:// stream fully into memory. Blocking. */
    static byte[] readAll(Activity activity, Uri uri) throws Exception {
        InputStream in = activity.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("cannot open " + uri);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    static void writeFile(File target, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
