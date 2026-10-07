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
 * colour. So this copies the clip, puts the row in the gallery as a PHOTOGRAPH --
 * the poster frame, no depth, no clips -- and then generates the depth and normal
 * clips and attaches them to that row.
 *
 * <p><b>The row goes in first, and it is a photograph rather than a pending clip.</b>
 * The old shape wrote the row only on success, so a failed generation lost the
 * user's video and left them a message; and a successful one left them watching a
 * toast for three minutes with nothing in the strip. A poster frame is a thing the
 * app has always been able to render, and the sheet already offers Generate for a
 * photo with no map -- so the row is that, the staged colour clip sits on disk under
 * the row's own id, and Generate runs the VIDEO graph over it.
 *
 * <p><b>What has not changed is the pair invariant.</b> No clips are stored until
 * there are colour AND depth, because a stored half-pair is the thing
 * {@code VideoTracks} exists to make unrepresentable -- and the index still owns the
 * selection and only the index. The row simply carries no clips until they exist,
 * which is a state the schema has always had: a photograph with no map.
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
     * reachable in one gesture: a loop that tiles, rather than an unbounded server
     * job the user waits on for an hour. 120 frames is 4 seconds at 30 fps, and it
     * is a TRADE, not a limit: server time and file size scale with it, so a longer
     * loop costs both, and the resolution is the other side of the same trade.
     */
    static final int FRAME_CAP = 0; // 0 = the whole clip; see the note above

    /** Poll interval and ceiling for the server-side run. */
    private static final long POLL_MS = 3000L;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** What the caller is told, so a sheet can show something honest. */
    interface Listener {
        void phase(String message);
        void done(LibraryItem item);
        void failed(String message);

        /**
         * The row is in the index NOW, before any depth exists.
         *
         * <p>It is a photograph at this point -- a poster frame with no map -- which
         * is a thing the app has always been able to show, so the strip can draw it
         * and the sheet can offer Generate exactly as it does for a still. The host
         * reindexes and selects on this callback, so the user sees their video land
         * instead of watching a toast for three minutes.
         *
         * <p>Default no-op: a caller that only cares about the finished row (a
         * regenerate, where the row already exists) does not implement it.
         */
        default void added(LibraryItem item) {
        }
    }

    private VideoImport() {
    }

    /**
     * Regenerate depth and normal for a clip that is already in the gallery.
     *
     * <p>The still path's Regenerate wrote ONE frame, because it ran the image graph
     * on the poster PNG -- which is what the button did before the video graph
     * existed, and on a clip row it was worse than nothing: it replaced the poster's
     * still depth with a single-frame map while the depth CLIP kept playing. This is
     * the clip's own version of the same action, and it regenerates the WHOLE set:
     * the existing colour clip is re-uploaded and re-run through the video graph, and
     * all three clips plus the three stills come back aligned, because they are
     * written by one decode.
     *
     * <p><b>New stamped filenames, not the id-derived ones.</b> The renderer decides
     * "already showing" by PATH, and a regenerate that overwrote the same paths would
     * be told the pair is current while the decoders held the OLD file open -- the new
     * clip would never load. New names make the pair identity change, which is the
     * same mechanism the still path's {@code ai_depth_<stamp>.png} naming uses. The
     * superseded files are left on disk, exactly as the still path's superseded maps
     * are.
     *
     * @param item the clip row to regenerate. Must carry clips.
     */
    static void regenerate(final Context app, final SharedPreferences prefs,
                           final LibraryItem item, final ComfyApi api,
                           final Listener listener) {
        if (item == null) {
            listener.failed(app.getString(R.string.video_import_failed));
            return;
        }
        // The colour clip is already here; regeneration re-runs the graph over IT.
        // TWO WAYS IT IS HERE: attached to the row as a clip, or STAGED under the
        // row's own id by an import whose generation has not run or has failed. The
        // second is what makes Generate work on a freshly added video, and it is a
        // convention on the id rather than a field, so nothing about a photograph's
        // schema had to change to hold half a clip.
        final File colour = item.video != null
                ? item.video.colorFile(app.getFilesDir())
                : new File(app.getFilesDir(), LibraryItem.videoFileName(item.id));
        if (colour == null || !colour.exists()) {
            listener.failed(app.getString(R.string.video_import_failed));
            return;
        }
        long stamp = System.currentTimeMillis();
        String[] names = {
                "ai_clip_" + stamp + ".mp4",        // colour clip
                "ai_clipdepth_" + stamp + ".mp4",   // depth clip
                "ai_clipnormal_" + stamp + ".mp4",  // normal clip
                "ai_clip_" + stamp + ".png",        // poster (image)
                "ai_depth_" + stamp + ".png",       // depth still -- the still path's convention
                "ai_normal_" + stamp + ".png",      // normal still
        };
        // A regenerate passes 0, 0: the stored colour clip is ALREADY the trimmed
        // range, so regenerating its depth means running the whole of it.
        LibraryItem updated = generate(app, prefs, api, item.id, colour, names, 0, 0, 0, listener,
                new Finisher() {
                    @Override
                    public LibraryItem finish(LibraryItem built) {
                        // Same id, same framing, same playlist membership: only the
                        // files behind it change. editItem is the in-place editor,
                        // and it keeps everything withFilesAndVideo does not name.
                        LibraryStore.editItem(prefs, item.id, new LibraryStore.Editor() {
                            @Override
                            public LibraryItem edit(LibraryItem it) {
                                return it.withFilesAndVideo(built.imageName,
                                        built.depthName, built.normalName, built.video);
                            }
                        });
                        return LibraryStore.load(prefs).activeItem();
                    }
                });
        if (updated != null) listener.done(updated);
    }

    /** The last step of a generate, split so regenerate can edit in place instead. */
    private interface Finisher {
        LibraryItem finish(LibraryItem built);
    }

    /**
     * The shared pipeline: upload the colour, run the video graph, fetch all three
     * clips, write the stills, and hand the built row to a finisher.
     *
     * @param names colour clip, depth clip, normal clip, poster, depth still, normal still
     * @return the built-but-unstored item, or null after reporting a failure
     */
    private static LibraryItem generate(final Context app, final SharedPreferences prefs,
                                        final ComfyApi api, final String rowId,
                                        final File colourSource,
                                        final String[] names,
                                        final int startFrame, final int frames,
                                        final int outHeight,
                                        final Listener listener,
                                        final Finisher finisher) {
        // The ROW's id, passed in. generate() used to mint its own, so a row could end
        // up holding files named after a DIFFERENT id -- harmless until something
        // derived a filename from the row id and silently missed.
        final String id = rowId;
        float fps = frameRateOf(colourSource);
        Log.i(TAG, "video generate id=" + id + " source=" + colourSource.getName()
                + " fps=" + fps);

        listener.phase(app.getString(R.string.video_phase_uploading));
        String uploadedName;
        try {
            JSONObject up = new JSONObject(api.uploadImage(colourSource, colourSource.getName()));
            uploadedName = up.getString("name");
        } catch (Exception e) {
            // No clear here: this is the UPLOAD, before the server has the work, so
            // there is no record yet -- and promptId is not even in scope.
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }

        listener.phase(app.getString(R.string.video_phase_generating));
        JSONObject outputs;
        // Declared OUTSIDE the try: the incomplete-outputs branch below needs it to
        // ask the server WHY, and a local inside the try is out of scope there.
        String promptId = null;
        try {
            String info = api.getObjectInfo();
            String graph = ComfyWorkflow.buildVideoWorkflow(
                    new JSONObject(info), "MoGeInference", uploadedName,
                    System.currentTimeMillis(), fps,
                    // custom_height, and the reason it is here rather than a client-side
                    // resize: VHS_LoadVideo scales the batch as it loads, so a 720p
                    // choice shrinks the IMAGE BATCH, MoGe's working set and all three
                    // output clips at once. A 1380x2156 x 120-frame batch is ~4 GiB of
                    // image plus the inference's own memory and dies on a 24 GiB card;
                    // the same clip at 720p height is under half a gigabyte. Width stays
                    // 0, which VHS reads as "keep the aspect".
                    frames > 0 ? frames : FRAME_CAP, startFrame, 0, outHeight);
            // Free the server's cached models first. A video batch is close to the
            // card's ceiling by itself, so whatever ran before it decides whether it
            // fits -- best effort, because a server without the route must not stop
            // an import.
            try {
                api.freeMemory();
            } catch (Exception ignored) {
            }
            JSONObject submitted = new JSONObject(api.submitPrompt(graph, "basrelief-" + id));
            promptId = submitted.getString("prompt_id");
            // THE RECORD, written the moment the server has the work. From here the
            // result is reachable again after a restart, which is what the sheet's Job
            // copy promises and what a process death used to destroy: the prompt id
            // lived in this thread's memory, so a finished batch sat unreachable in
            // the server's output folder with no handle to fetch it.
            JobsStore.record(prefs, id, promptId, api.host());
            outputs = pollOutputs(app, api, promptId);
        } catch (Exception e) {
            JobsStore.clear(prefs, promptId);
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }

        JSONObject depthOut = null, normalOut = null, colourOut = null;
        java.util.Iterator<String> nodes = outputs.keys();
        while (nodes.hasNext()) {
            JSONObject node = outputs.optJSONObject(nodes.next());
            if (node == null) continue;
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
                } else if (colourOut == null
                        && name.startsWith(ComfyWorkflow.COLOUR_CLIP_PREFIX)) {
                    colourOut = f;
                }
            }
        }
        if (depthOut == null || colourOut == null) {
            // The outputs came back but not all of them, and the reason is on the
            // SERVER -- the history entry carries the exception, and a bare "could not
            // be imported" is what the user got for a CUDA out-of-memory, which says
            // nothing about what to do next. The history is read again here because
            // this is the one path that knows the outputs are incomplete, and the
            // server's own words are worth more than any guess of ours.
            String why = serverReason(api, promptId);
            listener.failed(app.getString(R.string.video_import_failed)
                    + (why == null ? " (the run produced no depth clip)" : ": " + why));
            return null;
        }

        listener.phase(app.getString(R.string.video_phase_fetching));
        try {
            write(api.view(colourOut.getString("filename"),
                    colourOut.optString("subfolder", ""),
                    colourOut.optString("type", "output")), new File(app.getFilesDir(), names[0]));
            write(api.view(depthOut.getString("filename"),
                    depthOut.optString("subfolder", ""),
                    depthOut.optString("type", "output")), new File(app.getFilesDir(), names[1]));
            if (normalOut != null) {
                write(api.view(normalOut.getString("filename"),
                        normalOut.optString("subfolder", ""),
                        normalOut.optString("type", "output")), new File(app.getFilesDir(), names[2]));
            }
        } catch (Exception e) {
            JobsStore.clear(prefs, promptId);
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }

        listener.phase(app.getString(R.string.video_phase_poster));
        File poster, depthStill, normalStill = null;
        try {
            poster = firstFrameAsPng(app, new File(app.getFilesDir(), names[0]), names[3]);
            depthStill = firstFrameAsPng(app, new File(app.getFilesDir(), names[1]), names[4]);
            if (normalOut != null) {
                normalStill = firstFrameAsPng(app, new File(app.getFilesDir(), names[2]), names[5]);
            }
        } catch (Exception e) {
            JobsStore.clear(prefs, promptId);
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }

        // The job is DONE and its outputs are on disk: the record has served its
        // purpose and must not make the next start ask about a finished prompt.
        JobsStore.clear(prefs, promptId);
        listener.phase(app.getString(R.string.video_phase_adding));
        VideoTracks tracks;
        try {
            tracks = VideoTracks.of(names[0], names[1],
                    normalOut == null ? null : names[2]);
        } catch (Exception e) {
            JobsStore.clear(prefs, promptId);
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }
        LibraryItem built = LibraryItem.of(id, poster.getName(),
                        depthStill.getName(), System.currentTimeMillis())
                .withFilesAndVideo(poster.getName(), depthStill.getName(),
                        normalStill == null ? null : normalStill.getName(), tracks);
        return finisher.finish(built);
    }

    /**
     * Run the whole import on the calling (Io) thread.
     *
     * @param uri    the shared video
     * @param label  the name the sending app showed
     */
    static void run(final Context app, final SharedPreferences prefs, final Uri uri,
                    final String label, final ComfyApi api, final Listener listener) {
        run(app, prefs, uri, label, api, 0, 0, 0, listener);
    }

    /**
     * @param startFrame the trim's first frame, in the OUTPUT rate; 0 from the start
     * @param frames     how many frames to take; 0 = the whole clip. These are the
     *                   trim sheet's range, and they travel to the server as the
     *                   loader's skip/limit -- see {@link ComfyWorkflow}. No frame
     *                   arithmetic happens on the device, so there is exactly one
     *                   place the trim can be wrong.
     */
    static void run(final Context app, final SharedPreferences prefs, final Uri uri,
                    final String label, final ComfyApi api,
                    final int startFrame, final int frames, final int outHeight,
                    final Listener listener) {
        final String id = LibraryItem.newId();
        listener.phase(app.getString(R.string.video_phase_copying));
        final File colourSource = new File(app.getFilesDir(), LibraryItem.videoFileName(id));
        try {
            copyTo(app, uri, colourSource);
        } catch (Exception e) {
            listener.failed(app.getString(R.string.video_import_failed));
            return;
        }
        if (colourSource.length() == 0) {
            listener.failed(app.getString(R.string.video_import_failed));
            return;
        }
        // THE PHOTO LANDS FIRST, from the LOCAL file, before anything reaches the
        // server. The row exists from here on, so an import killed mid-flight -- an
        // install, an OOM kill, a swipe away -- leaves a photograph the user can see
        // and retry rather than an orphaned clip file and no row at all. Measured:
        // exactly that loss cost a finished server job and its only handle.
        //
        // The poster is the RANGE's first frame, so it is the same picture the
        // finished import writes and does not visibly change when the import lands.
        final float earlyFps = frameRateOf(colourSource);
        final long posterAtUs = (long) (startFrame / Math.max(1f, earlyFps) * 1_000_000f);
        try {
            File poster = firstFrameAsPng(app, colourSource, LibraryItem.imageFileName(id),
                    posterAtUs);
            LibraryItem early = LibraryItem.of(id, poster.getName(), null,
                    System.currentTimeMillis()).withLabel(label);
            // Selected as well as added: the user just asked for this photo, and a row
            // that arrives without being shown is a photo they have to go looking for.
            LibraryStore.saveNow(prefs,
                    LibraryStore.load(prefs).withItem(early).withSelection(id));
        } catch (Exception e) {
            // A poster that cannot be read is not fatal: the import still writes its
            // own at the end. Failing here would refuse an import over a preview.
            Log.w(TAG, "early poster failed: " + e);
        }

        // Id-derived names, so a re-import of the same video is a new row with its
        // own files; regenerate uses stamped names for the same row.
        String[] names = {
                LibraryItem.videoFileName(id),
                LibraryItem.videoDepthFileName(id),
                LibraryItem.videoNormalFileName(id),
                LibraryItem.imageFileName(id),
                LibraryItem.depthFileName(id),
                LibraryItem.normalFileName(id),
        };

        // THE ROW GOES IN FIRST, as a photograph, and that is a deliberate reversal
        // of "write the row once, at the end". The old shape left the user watching a
        // toast for three minutes with nothing in the strip, and if the run failed
        // they got a message and no row at all -- their video, gone. A poster frame is
        // a photograph: the app has always rendered one, the sheet already offers
        // Generate for a photo with no map, and the staged colour clip sits on disk
        // under this row's own id, so Generate has something to run on.
        //
        // What has NOT changed is the pair invariant: no clips are stored until there
        // are colour AND depth, because a stored half-pair is the thing VideoTracks
        // exists to make unrepresentable. The row simply has none yet.
        listener.phase(app.getString(R.string.video_phase_poster));
        File poster;
        try {
            poster = firstFrameAsPng(app, colourSource, LibraryItem.imageFileName(id));
        } catch (Exception e) {
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return;
        }
        LibraryItem placed = LibraryItem.of(id, poster.getName(), null,
                System.currentTimeMillis()).withLabel(label);
        Library added = LibraryStore.load(prefs).withItem(placed).withSelection(placed.id);
        LibraryStore.saveNow(prefs, added);
        listener.added(placed);

        LibraryItem item = generate(app, prefs, api, id, colourSource, names,
                startFrame, frames, outHeight, listener,
                new Finisher() {
                    @Override
                    public LibraryItem finish(LibraryItem built) {
                        // EDIT IN PLACE, the same derivation a regenerate uses: the row
                        // is already the user's, and adding it a second time would
                        // either duplicate it or lose the selection it carries.
                        LibraryStore.editItem(prefs, id, new LibraryStore.Editor() {
                            @Override
                            public LibraryItem edit(LibraryItem it) {
                                return it.withFilesAndVideo(built.imageName,
                                        built.depthName, built.normalName, built.video);
                            }
                        });
                        return LibraryStore.load(prefs).activeItem();
                    }
                });
        if (item != null) listener.done(item);
    }

    /**
     * The server's own reason for a run that produced incomplete outputs, or null.
     *
     * <p>Reads the history entry again and returns the first {@code execution_error}
     * message's text, first line only — a CUDA OOM's message runs to several lines and
     * the first one is the sentence that matters. Never throws: a reason is a bonus,
     * not something that may replace the failure it explains.
     */
    private static String serverReason(ComfyApi api, String promptId) {
        if (promptId == null) return null;
        try {
            JSONObject history = new JSONObject(api.getHistory(promptId));
            if (!history.has(promptId)) return null;
            JSONObject status = history.getJSONObject(promptId).optJSONObject("status");
            if (status == null) return null;
            JSONArray msgs = status.optJSONArray("messages");
            if (msgs == null) return null;
            for (int i = 0; i < msgs.length(); i++) {
                JSONArray pair = msgs.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                if (!"execution_error".equals(pair.optString(0))) continue;
                JSONObject body = pair.optJSONObject(1);
                if (body == null) continue;
                String m = body.optString("exception_message", null);
                if (m == null) continue;
                int nl = m.indexOf('\n');
                return nl > 0 ? m.substring(0, nl) : m;
            }
        } catch (Exception ignored) {
        }
        return null;
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
        return firstFrameAsPng(app, clip, name, 0L);
    }

    /**
     * Frame 0 of a clip as a PNG, or the frame at {@code atUs}.
     *
     * <p>The time matters for the EARLY poster: a trimmed import's first frame is the
     * range's first frame, not the source's, so the poster written before the server
     * round is the same picture the finished import would write -- otherwise the
     * photograph would visibly change when the import completed.
     */
    private static File firstFrameAsPng(Context app, File clip, String name, long atUs)
            throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(clip.getPath());
            Bitmap frame = mmr.getFrameAtTime(atUs,
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
    /**
     * Copy the shared video into the app's storage so the trim sheet can read it.
     *
     * <p>A STAGING name, not the item's: the row does not exist yet -- the range is
     * what the sheet is about to ask for -- and naming the file after an id that has
     * not been minted would be a name the import then has to work around.
     */
    static File stage(Context app, Uri uri) throws Exception {
        File staged = new File(app.getFilesDir(), "stage-import.mp4");
        copyTo(app, uri, staged);
        return staged;
    }

    /**
     * The clip's frame count at the given rate, or 0 when the duration is unknown.
     *
     * <p>Duration from {@link MediaMetadataRetriever}, NOT from MediaExtractor's
     * track format: measured, the extractor's {@code durationUs} is absent for the
     * files this app stages, so the count came back 0, the trim bar was built with a
     * one-frame clip, and the sheet sat at 0:00 with handles that could not move --
     * reported as "trim sheet stuck at 0.0". The retriever is the same source the
     * filmstrip already reads successfully, so the sheet cannot disagree with its own
     * thumbnails about how long the clip is.
     */
    static int frameCountOf(File clip, float fps) {
        long us = durationUsOf(clip);
        if (us <= 0 || fps <= 0) return 0;
        return Math.round(us / 1_000_000f * fps);
    }

    private static long durationUsOf(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) return Long.parseLong(ms) * 1000L;
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        return 0L;
    }

    static float frameRateOf(File clip) {
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
