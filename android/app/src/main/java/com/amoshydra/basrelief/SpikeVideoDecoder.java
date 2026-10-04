package com.amoshydra.basrelief;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * THROWAWAY SPIKE CODE. One video track, decoded as fast as the codec will go,
 * rendered straight to a {@link Surface} (which the caller feeds from a
 * {@code SurfaceTexture}). Nothing here is production: it exists to answer
 * whether two of these can run in lockstep under a fragment-shader relief and
 * still leave the GPU time to draw.
 *
 * <p>No dependency. MediaExtractor + MediaCodec are platform classes, which is
 * the whole reason the video plan exists.
 */
final class SpikeVideoDecoder {

    private final String path;
    private final Surface surface;
    private volatile boolean running;
    private volatile int decoded;
    private volatile boolean done;
    private volatile String error;
    private Thread thread;

    SpikeVideoDecoder(String path, Surface surface) {
        this.path = path;
        this.surface = surface;
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "spike-decode-" + path);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    int decoded() {
        return decoded;
    }

    boolean isDone() {
        return done;
    }

    String error() {
        return error;
    }

    private void loop() {
        MediaCodec codec = null;
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("no video track in " + path);
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (running && !outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    // render=true hands the frame to the Surface/SurfaceTexture.
                    codec.releaseOutputBuffer(out, true);
                    decoded++;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } catch (Throwable t) {
            error = t.toString();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) { }
                try { codec.release(); } catch (Throwable ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) { }
            }
            done = true;
        }
    }
}
