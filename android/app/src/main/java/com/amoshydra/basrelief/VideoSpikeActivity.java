package com.amoshydra.basrelief;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * THROWAWAY SPIKE. Hosts {@link SpikeRenderer}: two videos in lockstep, a
 * fragment-shader relief rendered at a chosen offscreen resolution, and a
 * one-line-per-second fps report.
 *
 * <p>Launch explicitly:
 * <pre>
 *   adb shell am start -n com.amoshydra.basrelief/.VideoSpikeActivity \
 *       --es mode video --ei w 1080 --ei h 2340
 * </pre>
 * Videos are read from the app's external files dir ({@code color.mp4},
 * {@code depth.mp4}) unless {@code --es color}/{@code --es depth} override.
 * The spike is throwaway; do not route this into the gallery.
 */
public final class VideoSpikeActivity extends Activity implements SpikeRenderer.Stats {

    private static final String TAG = SpikeRenderer.TAG;

    private static final int[][] SIZES = {
            { 720, 1560 },
            { 1080, 2340 },
            { 1440, 3120 },
    };

    private GLSurfaceView view;
    private SpikeRenderer renderer;
    private TextView stats;
    private int sizeIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean video = !"still".equals(getIntent().getStringExtra("mode"));
        int w = getIntent().getIntExtra("w", SIZES[sizeIndex][0]);
        int h = getIntent().getIntExtra("h", SIZES[sizeIndex][1]);
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i][0] == w && SIZES[i][1] == h) sizeIndex = i;
        }

        File base = getExternalFilesDir(null);
        String colorPath = getIntent().getStringExtra("color");
        String depthPath = getIntent().getStringExtra("depth");
        if (colorPath == null && base != null) colorPath = new File(base, "color.mp4").getAbsolutePath();
        if (depthPath == null && base != null) depthPath = new File(base, "depth.mp4").getAbsolutePath();

        float aspect = readAspect(colorPath);
        Log.i(TAG, "spike activity starting, build " + BuildConfig.GIT_COMMIT
                + " mode=" + (video ? "video" : "still")
                + " fbo=" + w + "x" + h + " color=" + colorPath + " depth=" + depthPath);

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        renderer = new SpikeRenderer(this, colorPath, depthPath, video, aspect, this);
        renderer.setFboSize(w, h);
        renderer.setRepeat(getIntent().getIntExtra("repeat", 1));
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        stats = new TextView(this);
        stats.setTextColor(Color.WHITE);
        stats.setBackgroundColor(0x99000000);
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        stats.setPadding(16, 16, 16, 16);
        stats.setText("spike: starting…");

        Button cycle = new Button(this);
        cycle.setText("cycle render resolution");
        cycle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sizeIndex = (sizeIndex + 1) % SIZES.length;
                final int nw = SIZES[sizeIndex][0];
                final int nh = SIZES[sizeIndex][1];
                view.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        renderer.setFboSize(nw, nh);
                    }
                });
            }
        });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM);
        bottom.addView(cycle);
        bottom.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        setContentView(root);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final int nw = intent.getIntExtra("w", SIZES[sizeIndex][0]);
        final int nh = intent.getIntExtra("h", SIZES[sizeIndex][1]);
        final int nr = intent.getIntExtra("repeat", -1);
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i][0] == nw && SIZES[i][1] == nh) sizeIndex = i;
        }
        view.queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.setFboSize(nw, nh);
                if (nr > 0) renderer.setRepeat(nr);
            }
        });
    }

    private float readAspect(String colorPath) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(colorPath);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int wi = Integer.parseInt(w);
            int hi = Integer.parseInt(h);
            if (wi > 0 && hi > 0) return (float) wi / hi;
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        return 1280f / 1920f;
    }

    @Override
    public void onStats(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stats.setText(String.format(Locale.US, "build %s\n%s",
                        BuildConfig.GIT_COMMIT, line));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    @Override
    protected void onPause() {
        view.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Directly on this thread: the GL-thread queue is already paused by now,
        // so a queued release would never run and would leak two codecs per
        // recreation. See SpikeRenderer.releaseFromUiThread.
        renderer.releaseFromUiThread();
        super.onDestroy();
    }
}
