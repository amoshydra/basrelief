package com.amoshydra.basrelief;

import android.app.Activity;
import android.net.Uri;

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
