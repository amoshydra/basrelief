package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.VideoTracks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A video, in one gesture, all the way to a row that plays.
 *
 * <p><b>Everything happens before the row exists.</b> A clip is colour AND depth —
 * {@link VideoTracks} refuses to be built with one without the other, because colour
 * without depth renders flat with no error anywhere — and at import we have only the
 * colour. So this copies the clip, generates the depth and normal clips, and then
 * writes the row ONCE with all of it. There is no "pending" half-row and no second
 * structure holding a colour clip on its own: the index owns the selection and only
 * the index, and a row that existed without its depth would be a claim the renderer
 * cannot honour.
 *
 * <p><b>The poster frame is the first frame of the source, and the still depth and
 * normal are the first frames of the generated clips.</b> That is what keeps the
 * photograph and the clip the same picture: the still pair is not an approximation
 * of the clip, it is a frame of it. A clip has to be able to render <i>before</i> its
 * first decoded frame arrives, and that picture is this one.
 *
 * <p><b>One {@code withFilesAndVideo} call</b>, because that is the only writer of
 * the clips field and a writer that added the row in two steps is how a row ends up
 * with a colour clip and no depth — the exact half-pair {@code VideoTracks} exists to
 * make unrepresentable.
 *
 * <p>Everything here is disk and network, so it runs on the {@code Io} worker. It
 * reports progress by PHASE rather than by percentage, because there is no honest
 * percentage to report across an upload, a server-side inference and two downloads.
 */
final class VideoImport {

    private static final String TAG = "Basrelief";

    /**
     * How many frames of the source to generate depth for.
     *
     * <p><b>A living photo is a loop, not a video.</b> One MoGe inference runs per
     * clip -- the frames are a batch -- so the cost scales with frames, and a
     * one-hour clip at 24 fps is 86,400 of them. The cap is what makes the feature
     * reachable in one gesture: a short loop that tiles, rather than an unbounded
     * server job the user waits on for an hour.
     */
    static final int FRAME_CAP = 48;

    /** Poll interval and ceiling for the server-side run. */
    private static final long POLL_MS = 3000L;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** What the caller is told, so a sheet can show something honest. */
    interface Listener {
        void phase(String message);
        void done(LibraryItem item);
        void failed(String message);
    }

    private VideoImport() {
    }

    /**
     * Run the whole import on the calling (Io) thread.
     *
     * @param uri    the shared video
     * @param label  the name the sending app showed
     */
    static void run(final Context app, final SharedPreferences prefs, final Uri uri,
                    final String label, final ComfyApi api, final Listener listener) {
        final String id = LibraryItem.newId();
        listener.phase("Copying the clip");
        final File colorFile = new File(app.getFilesDir(), LibraryItem.videoFileName(id));
        try {
            copyTo(app, uri, colorFile);
        } catch (Exception e) {
            listener.failed("Could not read that video: " + e.getMessage());
            return;
        }
        if (colorFile.length() == 0) {
            listener.failed("That video was empty.");
            return;
        }

        // The source rate, read rather than assumed. It is the number the loader and
        // both output combiners must agree on, and a wrong one slides the depth
        // against the colour by exactly that factor for the whole clip -- which reads
        // as a sync bug and is a rate bug.
        float fps = frameRateOf(colorFile);
        Log.i(TAG, "video import id=" + id + " bytes=" + colorFile.length()
                + " fps=" + fps);

        // ---- 1. upload the clip
        listener.phase("Uploading");
        String uploadedName;
        try {
            JSONObject up = new JSONObject(api.uploadImage(colorFile, colorFile.getName()));
            uploadedName = up.getString("name");
        } catch (Exception e) {
            listener.failed("The depth server took the clip but refused the upload: "
                    + e.getMessage());
            return;
        }

        // ---- 2. run the video graph
        listener.phase("Generating depth for the clip");
        JSONObject outputs;
        try {
            String info = api.getObjectInfo();
            String graph = ComfyWorkflow.buildVideoWorkflow(
                    new JSONObject(info), "MoGeInference", uploadedName,
                    System.currentTimeMillis(), fps, FRAME_CAP, 0, 0);
            JSONObject submitted = new JSONObject(api.submitPrompt(graph, "basrelief-" + id));
            String promptId = submitted.getString("prompt_id");
            outputs = pollOutputs(app, api, promptId);
        } catch (Exception e) {
            listener.failed("Depth generation failed: " + e.getMessage());
            return;
        }

        // ---- 3. fetch both clips, told apart by their prefixes
        JSONObject depthOut = null, normalOut = null;
        java.util.Iterator<String> nodes = outputs.keys();
        while (nodes.hasNext()) {
            JSONObject node = outputs.optJSONObject(nodes.next());
            if (node == null) continue;
            // VHS writes its file under "gifs" for every container, which is a
            // misnomer in its own schema. Reading only "images" is how the still
            // path found its PNGs and would find NOTHING here.
            JSONArray files = node.optJSONArray("gifs");
            if (files == null) files = node.optJSONArray("images");
            if (files == null) continue;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String name = f.optString("filename", "");
                if (depthOut == null && name.startsWith(ComfyWorkflow.DEPTH_CLIP_PREFIX)) {
                    depthOut = f;
                } else if (normalOut == null
                        && name.startsWith(ComfyWorkflow.NORMAL_CLIP_PREFIX)) {
                    normalOut = f;
                }
            }
        }
        if (depthOut == null) {
            listener.failed("The run produced no depth clip.");
            return;
        }

        listener.phase("Fetching");
        File depthClip, normalClip = null;
        try {
            depthClip = new File(app.getFilesDir(), LibraryItem.videoDepthFileName(id));
            write(api.view(depthOut.getString("filename"),
                    depthOut.optString("subfolder", ""),
                    depthOut.optString("type", "output")), depthClip);
            if (normalOut != null) {
                normalClip = new File(app.getFilesDir(), LibraryItem.videoNormalFileName(id));
                write(api.view(normalOut.getString("filename"),
                        normalOut.optString("subfolder", ""),
                        normalOut.optString("type", "output")), normalClip);
            }
        } catch (Exception e) {
            listener.failed("Could not download the depth clip: " + e.getMessage());
            return;
        }

        // ---- 4. the still pair, as frame 0 of the clips
        listener.phase("Writing the poster frame");
        File poster, depthStill = null, normalStill = null;
        try {
            poster = firstFrameAsPng(app, colorFile, LibraryItem.imageFileName(id));
            depthStill = firstFrameAsPng(app, depthClip, LibraryItem.depthFileName(id));
            if (normalClip != null) {
                normalStill = firstFrameAsPng(app, normalClip, LibraryItem.normalFileName(id));
            }
        } catch (Exception e) {
            listener.failed("Could not read a frame back from the clips: " + e.getMessage());
            return;
        }

        // ---- 5. ONE row, with everything
        listener.phase("Adding to the gallery");
        VideoTracks tracks = null;
        try {
            tracks = VideoTracks.of(
                    LibraryItem.videoFileName(id),
                    LibraryItem.videoDepthFileName(id),
                    normalClip == null ? null : LibraryItem.videoNormalFileName(id));
        } catch (Exception e) {
            // VideoTracks refuses a half pair, which is the point. Reaching here means
            // a depth clip we just wrote is not where we said it was, and saying so is
            // better than storing a row that renders flat.
            listener.failed("The clip pair is incomplete: " + e.getMessage());
            return;
        }
        LibraryItem item = LibraryItem.of(id,
                        poster.getName(),
                        depthStill == null ? null : depthStill.getName(),
                        System.currentTimeMillis())
                .withLabel(label)
                .withFilesAndVideo(poster.getName(),
                        depthStill == null ? null : depthStill.getName(),
                        normalStill == null ? null : normalStill.getName(),
                        tracks);
        Library next = LibraryStore.load(prefs).withItem(item).withSelection(item.id);
        LibraryStore.saveNow(prefs, next);
        listener.done(item);
    }

    /** Wait for the run, then hand back its outputs. Blocking; the Io thread only. */
    private static JSONObject pollOutputs(Context app, ComfyApi api, String promptId)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            JSONObject history = new JSONObject(api.getHistory(promptId));
            if (!history.has(promptId)) continue;
            JSONObject entry = history.getJSONObject(promptId);
            // A run that ended in an ERROR is not a run that produced nothing: without
            // this the loop would spin to the timeout and report "no depth clip" for a
            // graph the server had already refused, which sends the user looking at the
            // wrong thing.
            if (entry.has("status")) {
                JSONObject status = entry.getJSONObject("status");
                if (status.optBoolean("completed", false) && status.has("messages")) {
                    JSONArray msgs = status.optJSONArray("messages");
                    if (msgs != null && msgs.length() > 0) {
                        for (int i = 0; i < msgs.length(); i++) {
                            JSONArray pair = msgs.optJSONArray(i);
                            if (pair != null && pair.length() > 1
                                    && "execution_error".equals(pair.optString(0))) {
                                JSONObject body = pair.optJSONObject(1);
                                throw new Exception("the server reported: "
                                        + (body == null ? "an execution error"
                                            : body.optString("exception_message", "an error")));
                            }
                        }
                    }
                }
            }
            JSONObject outputs = entry.optJSONObject("outputs");
            if (outputs != null && outputs.length() > 0) return outputs;
        }
        throw new Exception("the run did not finish in time");
    }

    private static void copyTo(Context app, Uri uri, File target) throws Exception {
        InputStream in = app.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("the video could not be opened");
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) { }
            out.close();
        }
    }

    private static void write(byte[] bytes, File target) throws Exception {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
    }

    /**
     * Write frame 0 of a clip as a PNG.
     *
     * <p>At microsecond 0 and SEEK_TO_CLOSEST_SYNC, so it is the FIRST frame and not
     * the nearest keyframe to it — which matters because this file becomes the
     * poster the gallery shows and the photograph the clip falls back to.
     */
    private static File firstFrameAsPng(Context app, File clip, String name) throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(clip.getPath());
            Bitmap frame = mmr.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new Exception("no frame could be read from " + name);
            File out = new File(app.getFilesDir(), name);
            FileOutputStream fos = new FileOutputStream(out);
            try {
                if (!frame.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new Exception("the frame could not be encoded");
                }
                fos.flush();
            } finally {
                fos.close();
                frame.recycle();
            }
            return out;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) { }
        }
    }

    /**
     * The source frame rate, in frames per second.
     *
     * <p>Read from the container, and defaulted rather than guessed into silence: a
     * container that will not say gives 24, which is the common phone rate. A wrong
     * rate here slides the depth against the colour by exactly this factor, so it is
     * read rather than assumed -- but a container with no declared rate is not a
     * reason to refuse a clip.
     */
    private static float frameRateOf(File clip) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(clip.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                if (fmt.containsKey("r_frame_rate")) {
                    String r = fmt.getString("r_frame_rate");
                    if (r != null) {
                        int slash = r.indexOf('/');
                        if (slash > 0) {
                            float num = Float.parseFloat(r.substring(0, slash));
                            float den = Float.parseFloat(r.substring(slash + 1));
                            if (den > 0 && num > 0 && num / den > 0.1f) return num / den;
                        }
                    }
                }
                if (fmt.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    int f = fmt.getInteger(android.media.MediaFormat.KEY_FRAME_RATE);
                    if (f > 0) return f;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "frame rate unreadable: " + t);
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
        return 24f;
    }
}
