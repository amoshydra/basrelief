package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.amoshydra.basrelief.core.depth.BatchBudget;
import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.store.Jobs;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.VideoTracks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A video, in one gesture, all the way to a row that plays.
 *
 * <p><b>The row exists before the depth does.</b> A clip is colour AND depth —
 * {@link VideoTracks} refuses to be built with one without the other, because colour
 * without depth renders flat with no error anywhere — and at import we have only the
 * colour. So the share is read where it is, the row goes into the gallery as a
 * PHOTOGRAPH -- the poster frame, no depth, no clips -- and then the depth and normal
 * clips are generated and attached to that row.
 *
 * <p><b>ONE prompt runs the whole range, because the server node owns the loop.</b>
 * {@code MoGeVideoStream} takes the uploaded video's path, decodes a window at a
 * time, runs MoGe over it and writes the two clips itself, so the graph is two nodes
 * and the app has no chunk plan, no per-chunk fetch and no clip join. Measured on
 * the exact clip the old path died on: 480 frames of 1920x1080@60 held 11.5-12.3 GiB
 * flat for the whole run (the old graph climbed to 15.48 GiB and requested 4.03 more
 * at frame 174 on the server's 16 GiB cap) and finished in 340 s. Length stopped
 * being a thing that decides whether an import can run at all.
 *
 * <p><b>The colour clip is the app's own remux, and it is also the upload.</b> The
 * share is remuxed -- encoded samples copied, nothing re-encoded -- into the row's
 * colour filename, which the renderer later plays; that same file is what is
 * uploaded and what the node reads. So the frames MoGe is asked about and the frames
 * the user sees are one file, and the node never has to produce a colour at all.
 *
 * <p><b>The progress channel is the node's sidecar, and it is a FILE.</b> The node
 * rewrites {@code basrelief_<tag>.progress.json} every window with
 * {@code {done, total, state}}, fetchable through the same {@code /view} route as
 * the clips, so "frame 137 of 480" is a number the server actually stated rather
 * than a client-side guess. The tag is the item id, sanitised by the app's copy of
 * the node's rule, which is what lets the fetch and resume paths address files they
 * have never been told about.
 *
 * <p><b>The count is verified, not trusted.</b> The app counts its own upload's
 * samples exactly and checks the node's reported {@code frames} against it; the
 * fetch then checks the arrived depth clip's samples against the colour's. This is
 * what replaced the old duration-based planner: a 240-frame remux reports 9.9586 s,
 * so {@code round(9.9586 x 24)} is 239, and planning a run from that number asked
 * for one frame fewer than the colour held -- a clip one frame short, at the end,
 * with no error anywhere.
 *
 * <p><b>No clips are stored until they both exist.</b> The pair invariant is in
 * {@code VideoTracks}, not in a writer, and the index still owns the selection and
 * only the index. The row simply carries no clips until they exist, which is a
 * state the schema has always had: a photograph with no map.
 *
 * <p><b>The poster frame is the first frame of the RANGE, and the still depth and
 * normal are the first frames of the generated clips.</b> That is what keeps the
 * photograph and the clip the same picture: the still pair is not an approximation
 * of the clip, it is a frame of it. A clip has to be able to render <i>before</i> its
 * first decoded frame arrives, and that picture is this one.
 *
 * <p><b>Everything here is disk and network, so it runs on the {@code Io} worker.</b>
 * It reports progress by PHASE and by the sidecar's own frame count, never by a
 * percentage derived on the device: "frame 137 of 480" is a fact the sidecar gives,
 * where "37%" would be a number nothing measured.
 */
final class VideoImport {

    private static final String TAG = "Basrelief";

    /**
     * What THIS PROCESS knows about one row's in-flight import.
     *
     * <p><b>The record on disk is the durable half; this is the live half, and
     * the row needs both because they cover different moments.</b> The job record
     * is written once the server has accepted the prompt — before that, the
     * upload and submission are still in flight and there is nothing to write
     * down. The ring and the sheet's line must exist from the FIRST second (that
     * is the mockup's whole point: the row is the place progress lives), so the
     * worker registers the row id here before it does anything, and the phase
     * text it last reported is readable from the UI.
     *
     * <p><b>{@code done}/{@code total} are the sidecar's numbers, not the
     * record's.</b> They are updated from each sidecar read (~2 s) while the node
     * runs, which is what makes the line say "frame 137 of 480"; nothing writes
     * them to disk, because a persisted copy would be a second, staler answer and
     * the sidecar is always the truth a resume asks.
     *
     * <p>In memory and deliberately not persisted: if the process dies, the
     * import it was reporting dies with it, and the record is what the next
     * launch resumes from.
     */
    static final class Live {
        /** The last phase message, for the line before the sidecar has a count. */
        volatile String phase = "";
        /** True while the sidecar is reporting frames, so the line can name k of N. */
        volatile boolean generating;
        volatile int done;
        volatile int total;
    }

    private static final Map<String, Live> LIVE = new ConcurrentHashMap<String, Live>();

    /** Whether an import for this row is running in this process. */
    static boolean isRunning(String itemId) {
        return itemId != null && LIVE.containsKey(itemId);
    }

    /** The live phase of this row's import, or null when nothing is running. */
    static Live live(String itemId) {
        return itemId == null ? null : LIVE.get(itemId);
    }

    private static void begin(String itemId) {
        if (itemId != null && !itemId.isEmpty()) LIVE.put(itemId, new Live());
    }

    private static void end(String itemId) {
        if (itemId != null) LIVE.remove(itemId);
    }

    /**
     * The one way a phase is both reported to the caller and remembered here.
     *
     * <p>The sheet's line is derived from {@link #live}, so a phase that only
     * reached the listener would be a progress word the UI could not re-read
     * after a rebuild — the same "stashed somewhere a rebuild clears" trap the
     * record solves for the prompt.
     */
    private static void phase(String itemId, Listener listener, String message) {
        Live l = LIVE.get(itemId);
        if (l != null) {
            l.phase = message == null ? "" : message;
            l.generating = false;
        }
        listener.phase(message);
    }

    /**
     * Frames the server has finished, from the sidecar: the line may say k of N.
     *
     * <p>Recorded on the LIVE object and reported through {@code progress} rather
     * than {@code phase}, because a phase is a change of WHAT the run is doing and
     * the host re-resolves the display for one, while this number moves every poll
     * (~2 s) and the medium does not. A host that repainted the world per count
     * would run an index read and a pair resolve 900 times across one import.
     *
     * <p>A count that did not move reports nothing: the sidecar is rewritten per
     * window, so a poll can land between writes and read the number it read last
     * time.
     */
    private static void generating(String itemId, Listener listener, int done, int total) {
        Live l = LIVE.get(itemId);
        if (l == null) return;
        int d = Math.max(0, done);
        int t = Math.max(0, total);
        if (l.generating && l.done == d && l.total == t) return;
        l.generating = true;
        l.done = d;
        l.total = t;
        listener.progress(d, t);
    }

    /**
     * A run the server itself refused, carrying its words.
     *
     * <p>Its own type because the failure path records the first kind as the
     * job's own FAILED state (the sheet and the tile say so until the user
     * acts), while the second kind is a phone-side failure reported the same way
     * but without a server reason to quote.
     */
    private static final class ServerFailure extends Exception {
        final String reason;

        ServerFailure(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /**
     * How often the sidecar and the history are asked, and how long one run may
     * take.
     *
     * <p>Two seconds because that is the rate the node rewrites the sidecar (a few
     * times a second) and the rate the sheet's line wants to move at; the history
     * is read in the same pass, so a finished run is noticed within a poll of the
     * file landing. The timeout is per run and generous: the measured 480-frame run
     * took 340 s, and 30 minutes is ~2000 frames at that pace.
     */
    private static final long POLL_MS = 2000L;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * How many consecutive poll failures a network blip may cost.
     *
     * <p>A streamed run is minutes long and one dropped poll must not mark a live
     * server job FAILED: a FAILED record is one the resume pass SKIPS, so a blip
     * would strand a result the server went on to finish. Five in a row — ten
     * seconds of an unreachable server — is a phone-side failure, and that is what
     * the failure path is for.
     */
    private static final int HISTORY_MISSES = 5;

    /** What the caller is told, so a sheet can show something honest. */
    interface Listener {
        void phase(String message);
        void done(LibraryItem item);
        void failed(String message);

        /**
         * The sidecar's finished-frame count moved, from the server's own file.
         *
         * <p>Its own callback rather than a {@code phase}: a phase is a change of
         * WHAT the run is doing (uploading, fetching) and the host re-resolves the
         * medium for it, while the count moves every poll (~2 s) and the medium
         * does not. A host that repainted the world per frame count would run an
         * index read and a pair resolve 900 times across one import.
         *
         * <p>Default no-op: a caller that shows no live line does not implement it.
         */
        default void progress(int done, int total) {
        }

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

    /**
     * What one finished run produced: the two server-side file names and the frame
     * count the node reported (0 when the history carried no ui payload).
     *
     * <p>Its own type because two readers hand it to {@link #attach} in opposite
     * directions: the live poll reads the names out of {@code /history}, while a
     * resume that found the sidecar saying {@code done} derives them from the tag.
     */
    private static final class StreamOutputs {
        final String depthName;
        final String normalName;
        final int frames;

        StreamOutputs(String depthName, String normalName, int frames) {
            this.depthName = depthName == null ? "" : depthName;
            this.normalName = normalName == null ? "" : normalName;
            this.frames = Math.max(0, frames);
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
     * the existing colour clip is re-run through the stream node, and the depth and
     * normal clips plus the three stills come back aligned.
     *
     * <p><b>New stamped filenames, not the id-derived ones.</b> The renderer decides
     * "already showing" by PATH, and a regenerate that overwrote the same paths would
     * be told the pair is current while the decoders held the OLD file open -- the new
     * clip would never load. New names make the pair identity change, which is the
     * same mechanism the still path's {@code ai_depth_<stamp>.png} naming uses. The
     * superseded files are left on disk, exactly as the still path's superseded maps
     * are.
     *
     * <p><b>The colour is a local copy, made before the server is asked anything.</b>
     * Its content is already here -- it is the clip being regenerated -- so the server
     * is never asked to re-encode it, and the new generation owns a file under its
     * own name from the start.
     *
     * @param item the clip row to regenerate. Must carry clips.
     */
    static void regenerate(final Context app, final SharedPreferences prefs,
                           final LibraryItem item, final ComfyApi api,
                           final Listener listener) {
        if (item == null) {
            fail(prefs, null, listener, "regenerate: no item", null);
            return;
        }
        begin(item.id);
        try {
            regenerateBody(app, prefs, item, api, listener);
        } finally {
            end(item.id);
        }
    }

    /** The body of {@link #regenerate}, split so the live registration is one try/finally. */
    private static void regenerateBody(final Context app, final SharedPreferences prefs,
                                       final LibraryItem item, final ComfyApi api,
                                       final Listener listener) {
        // The colour clip is already here; regeneration re-runs the node over IT.
        // TWO WAYS IT IS HERE: attached to the row as a clip, or fetched by an
        // import whose attach() wrote the colour clip and then failed before the
        // depth landed -- a partial fetch, and the case this fallback still serves.
        // The import writes the remuxed range under this name before the server
        // round, so a row whose generation never ran still has its range here and
        // this is its retry.
        final File colour = item.video != null
                ? item.video.colorFile(app.getFilesDir())
                : new File(app.getFilesDir(), LibraryItem.videoFileName(item.id));
        if (colour == null || !colour.exists()) {
            fail(prefs, null, listener, "regenerate: the colour clip is gone", null);
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
        File colourCopy = new File(app.getFilesDir(), names[0]);
        try {
            copyFile(colour, colourCopy);
        } catch (Exception e) {
            fail(prefs, null, listener,
                    app.getString(R.string.video_import_failed), e);
            return;
        }
        // A regenerate passes 0: the stored colour clip is ALREADY the trimmed
        // range, so regenerating its depth means running the whole of the copy at
        // its own height.
        LibraryItem updated = generate(app, prefs, api, item.id, colourCopy, names, 0,
                listener, editFinisher(prefs, item.id));
        if (updated != null) listener.done(updated);
    }

    /**
     * Finish an import a restart interrupted, from its job record.
     *
     * <p>The other half of "you can leave — it keeps going": the record is written
     * when the server takes the work, and this is what reads it. Called at studio
     * start for each pending job, and it answers from the run's own sidecar first:
     *
     * <ul>
     *   <li><b>The photo is gone</b> (deleted while the job ran) — clear the record;
     *       there is nothing to fetch into.</li>
     *   <li><b>The server is unreachable</b> — KEEP the record. A sleeping laptop is
     *       not a lost import, and this is the case the record exists for.</li>
     *   <li><b>The sidecar says {@code running}</b> — adopt the run: seed the live
     *       count from its {@code done/total} and ask again next foreground. The
     *       node is doing the work; re-submitting here would run it twice against
     *       the same output filenames.</li>
     *   <li><b>The sidecar says {@code done}</b> — fetch, attach, clear, exactly as
     *       an import would. The files are named from the tag, so no history
     *       payload is needed.</li>
     *   <li><b>The sidecar says {@code failed}</b>, or the history reports an error
     *       — record the server's own message as the job's FAILED state, report it,
     *       and leave the row as the photograph the early write made it.</li>
     *   <li><b>The server has no trace at all</b> — re-submit from frame 0. A
     *       single prompt has no partial clip to reuse: the node writes both files
     *       whole, at the end, by rename, so there is nothing to continue. The
     *       colour is already the range locally and becomes the upload again.</li>
     * </ul>
     *
     * <p><b>{@code outputs.length() > 0} is NOT "finished", and this is the bug the
     * status read fixes.</b> The history entry can carry outputs before the run is
     * over, so the old test attached mid-run, found no depth clip, and told the
     * user the import had failed while it was still working. The history status and
     * the sidecar's own state are the only things that know the difference; both
     * are asked before anything is fetched or re-submitted.
     */
    static void resume(final Context app, final SharedPreferences prefs,
                       final Jobs.Job job, final Listener listener) {
        final LibraryItem item = LibraryStore.load(prefs).find(job.itemId);
        if (item == null) {
            JobsStore.clearItem(prefs, job.itemId);
            return;
        }
        // A failed job is a STATE, not work to re-ask: the server has already
        // answered, and asking again returns the same refusal. The sheet's line and
        // the tile's corner say so until the user Regenerates or deletes the row.
        if (job.isFailed()) return;
        begin(job.itemId);
        try {
            resumeBody(app, prefs, job, item, listener);
        } finally {
            end(job.itemId);
        }
    }

    /** The body of {@link #resume}, split so the live registration is one try/finally. */
    private static void resumeBody(final Context app, final SharedPreferences prefs,
                                   final Jobs.Job job, final LibraryItem item,
                                   final Listener listener) {
        final ComfyApi api = new ComfyApi(job.host);
        final File dir = app.getFilesDir();
        final String tag = ComfyWorkflow.streamTag(job.itemId);
        // The row's OWN names: attach() writes the fetched clips under the
        // id-derived names, and the resume must land on the same files or the row
        // would point at nothing.
        final String[] names = {
                LibraryItem.videoFileName(job.itemId),
                LibraryItem.videoDepthFileName(job.itemId),
                LibraryItem.videoNormalFileName(job.itemId),
                LibraryItem.imageFileName(job.itemId),
                LibraryItem.depthFileName(job.itemId),
                LibraryItem.normalFileName(job.itemId),
        };
        // WHERE THE COLOUR IS: the row's own clip when it has one, otherwise the
        // early import's file under the same name. No colour means there is nothing
        // to re-submit from and nothing to preview -- clear rather than leave a
        // line with no action, and the row's own fix line offers Generate.
        final File colour = item.video != null
                ? item.video.colorFile(dir)
                : new File(dir, names[0]);
        if (colour == null || !colour.exists()) {
            Log.w(TAG, "resume: " + job.itemId + " has no colour clip to re-submit");
            JobsStore.clearItem(prefs, job.itemId);
            return;
        }

        // THE SIDECAR IS ASKED FIRST, because it is the run's own state and it can
        // answer four of the cases without touching /history.
        JSONObject side;
        try {
            byte[] body = api.viewIfPresent(ComfyWorkflow.streamProgressName(tag),
                    "", "output");
            side = body == null
                    ? null : new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Unreachable: KEEP the record and ask again next foreground. A
            // sleeping laptop is not a lost import -- this is the case the record
            // exists for.
            Log.w(TAG, "resume: " + job.itemId + " sidecar unreachable: " + e);
            return;
        }
        if (side != null) {
            String state = side.optString("state", "running");
            if ("done".equals(state)) {
                // The files are named from the tag, so the sidecar's own names (and
                // the deterministic fallback) are the whole fetch contract. The
                // planned count is the colour's own samples -- the artifact check
                // then compares what arrived with what the app holds.
                StreamOutputs out = new StreamOutputs(
                        nonEmpty(side.optString("depth", ""),
                                ComfyWorkflow.streamDepthName(tag)),
                        nonEmpty(side.optString("normal", ""),
                                ComfyWorkflow.streamNormalName(tag)),
                        0);
                LibraryItem built = attach(app, prefs, api, job.itemId, names, out,
                        job.promptId, sampleCountOf(app, Uri.fromFile(colour)),
                        listener, editFinisher(prefs, job.itemId));
                if (built != null) listener.done(built);
                return;
            }
            if ("failed".equals(state)) {
                String why = serverReason(api, job.promptId);
                String reason = why != null ? why : "the run failed on the server";
                Log.w(TAG, "resume: " + job.itemId + " failed on the server: " + reason);
                JobsStore.fail(prefs, job.promptId, reason);
                listener.failed(app.getString(R.string.video_import_failed) + ": " + reason);
                return;
            }
            // Running (or a state from a future version that is not a decision):
            // adopt the run. Nothing is fetched and nothing is re-submitted -- the
            // server is doing the work, and the line gets its count from the
            // sidecar's own done/total. A later foreground asks again.
            generating(job.itemId, listener, side.optInt("done", 0), side.optInt("total", 0));
            Log.i(TAG, "resume: " + job.itemId + " still running "
                    + side.optInt("done", 0) + "/" + side.optInt("total", 0));
            return;
        }

        // No sidecar. /history is the second question, and a finished run must be
        // fetched rather than re-run: the server has already paid for it.
        JSONObject entry;
        try {
            JSONObject history = new JSONObject(api.getHistory(job.promptId));
            entry = history.optJSONObject(job.promptId);
        } catch (Exception e) {
            Log.w(TAG, "resume: " + job.promptId + " unreachable: " + e);
            return;   // server unreachable: KEEP the record and ask again
        }
        if (entry != null) {
            String why = failedReason(entry);
            if (why != null) {
                Log.w(TAG, "resume: " + job.promptId + " failed on the server: " + why);
                JobsStore.fail(prefs, job.promptId, why);
                listener.failed(app.getString(R.string.video_import_failed) + ": " + why);
                return;
            }
            JSONObject status = entry.optJSONObject("status");
            if (status != null && status.optBoolean("completed", false)) {
                StreamOutputs out = streamOutputsOf(entry, tag);
                LibraryItem built = attach(app, prefs, api, job.itemId, names, out,
                        job.promptId, sampleCountOf(app, Uri.fromFile(colour)),
                        listener, editFinisher(prefs, job.itemId));
                if (built != null) listener.done(built);
                return;
            }
            // Neither finished nor failed: the prompt is running or queued. The
            // record is KEPT and asked again on the next foreground, which is the
            // whole contract of the record.
            return;
        }

        // THE SERVER HAS NO TRACE, so the old run is gone and this is a re-submit
        // from frame 0. A single prompt has no partial clip to reuse -- the node
        // writes both files whole and renames them at the end -- so "resume" here
        // means "run it again", and the colour is already the range.
        Log.i(TAG, "resume: the server has no " + job.promptId
                + ", re-submitting " + job.itemId);
        LibraryItem built = generate(app, prefs, api, job.itemId, colour, names, 0,
                listener, editFinisher(prefs, job.itemId));
        if (built != null) listener.done(built);
    }

    /**
     * The finisher every resume and regenerate uses: the SAME row, new files behind
     * it. {@code editItem} is the in-place editor and it keeps everything
     * {@code withFilesAndVideo} does not name, so framing, playlists and the id
     * survive the edit.
     */
    private static Finisher editFinisher(final SharedPreferences prefs, final String itemId) {
        return new Finisher() {
            @Override
            public LibraryItem finish(LibraryItem built) {
                LibraryStore.editItem(prefs, itemId, new LibraryStore.Editor() {
                    @Override
                    public LibraryItem edit(LibraryItem it) {
                        return it.withFilesAndVideo(built.imageName, built.depthName,
                                built.normalName, built.video);
                    }
                });
                return LibraryStore.load(prefs).find(itemId);
            }
        };
    }

    // ------------------------------------------------------------------ remux

    /** How an extractor is pointed at a source: a share URI or a local file. */
    private interface RemuxSource {
        void apply(MediaExtractor ex) throws Exception;
    }

    /**
     * Copy just the chosen range out of the shared video, WITHOUT re-encoding.
     *
     * <p><b>The upload is the range, not the file.</b> Measured: a 126 MB source
     * shared in was refused by the proxy with HTTP 413 (100 MB passes, 126 MB does
     * not), and even when it fits, uploading two minutes of video to generate a four
     * second loop is the wrong shape -- minutes of transfer for a loop that tiles.
     * MediaExtractor and MediaMuxer copy the encoded samples of the range into a new
     * container, so nothing is decoded, nothing is re-encoded, and no quality is lost:
     * a 4s range of a 126 MB file lands at a few megabytes.
     *
     * <p><b>The range is also the colour clip.</b> It is what the renderer plays and
     * it is the file the node reads, so every frame the server is asked about is a
     * frame of this file -- and a run that dies before the depth lands leaves
     * something Regenerate can use rather than nothing.
     *
     * <p><b>Keyframe-aligned, and that is a trade rather than a detail.</b> The seek
     * lands on the nearest sync sample at or before the requested start, because a
     * decoder cannot begin mid-GOP: the range may therefore begin a second or so
     * early. The alternative -- re-encoding from the exact frame -- costs the whole
     * video pipeline on the device for a trim the user can refine. The run's frame
     * plan is the file that results, not the sheet's arithmetic, which is why the
     * node is asked for the whole of this file rather than for N frames.
     *
     * @return the new file, or null when the range could not be copied
     */
    private static File remuxRange(final Context app, final Uri source, File out,
                                   long startUs, long durUs) {
        return remuxRange(new RemuxSource() {
            @Override
            public void apply(MediaExtractor ex) throws Exception {
                // The SHARE URI, read where it is. MediaExtractor accepts a content
                // URI directly, so the source is never copied into filesDir to be
                // read.
                ex.setDataSource(app, source, null);
            }
        }, out, startUs, durUs);
    }

    /**
     * The same copy, read from a local file.
     *
     * <p>The fallback for a provider the extractor cannot seek directly: the share
     * is copied once and the range is cut from the copy. The colour clip must be
     * cut locally either way -- nothing else plays the share's format reliably --
     * so a source that cannot be remuxed at all fails the import rather than
     * uploading an untrimmed file whose playback medium does not exist.
     */
    private static File remuxRange(final File source, File out, long startUs, long durUs) {
        return remuxRange(new RemuxSource() {
            @Override
            public void apply(MediaExtractor ex) throws Exception {
                ex.setDataSource(source.getPath());
            }
        }, out, startUs, durUs);
    }

    private static File remuxRange(RemuxSource source, File out, long startUs, long durUs) {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            source.apply(ex);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return null;
            ex.selectTrack(track);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            mux = new MediaMuxer(out.getPath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = mux.addTrack(fmt);
            mux.start();

            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // The first sample's own pts is the base, so the copy starts at zero
            // whatever the seek rounded back to -- the range's length is what was
            // asked for, and its start is the keyframe.
            long baseUs = -1L;
            long endUs = startUs + durUs;
            while (true) {
                long pts = ex.getSampleTime();
                if (pts < 0 || pts > endUs) break;
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (baseUs < 0) baseUs = pts;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts - baseUs;
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(dst, buf, info);
                ex.advance();
            }
            mux.stop();
            return out.length() > 0 ? out : null;
        } catch (Throwable t) {
            Log.w(TAG, "remux failed: " + t);
            return null;
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
            if (mux != null) { try { mux.release(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * Report a failure: a line in the log for the diagnosis, and the row's own
     * state when a record exists.
     *
     * <p><b>The log line is not a nicety, it is the difference between a bug we can
     * find and one we cannot.</b> Measured: an import failed before it reached the
     * server, the user saw a toast, and by the time it was reported there was nothing
     * in the log at all -- no jobs record (the failure was before the submit), no
     * exception, no phase -- so the only evidence was gone and the report could not be
     * acted on. A failure that is displayed and not recorded is a failure that has to
     * be reproduced from scratch.
     *
     * <p><b>A record that exists is marked FAILED, not cleared.</b> The failure has
     * to stay on the sheet and on the tile's corner until the user acts; clearing it
     * leaves the message in a toast. A failure before any record exists has no
     * prompt id to name and clears nothing — the row is a photograph with no depth,
     * which the fix line already answers.
     */
    private static void fail(SharedPreferences prefs, String promptId, Listener listener,
                             String detail, Throwable e) {
        String message = detail + (e == null || e.getMessage() == null
                ? "" : ": " + e.getMessage());
        Log.w(TAG, "import failed" + (promptId == null ? "" : " after " + promptId)
                + ": " + message, e == null ? null : e);
        if (promptId != null) JobsStore.fail(prefs, promptId, message);
        listener.failed(message);
    }

    /** The last step of a generate, split so resume and regenerate edit in place instead. */
    private interface Finisher {
        LibraryItem finish(LibraryItem built);
    }

    /**
     * The shared pipeline: upload the range, submit the one prompt, wait, fetch and
     * hand the built row to a finisher.
     *
     * <p><b>The upload is the range and the whole plan.</b> The node is asked for
     * {@code frame_count = 0} -- the whole of the file -- because the app already
     * holds the exact frames the colour clip plays: asking for a computed count is
     * how the old planner shipped a depth clip one frame short of its colour. The
     * app's own sample count is used only to CHECK, both against the node's reported
     * count and against the depth file that arrives.
     *
     * <p><b>The window is sized from the pixels the node will load.</b> The
     * resolution chip scales the batch before MoGe sees it, so the memory rule is
     * asked with the scaled dimensions; see {@link BatchBudget#streamWindow}.
     *
     * @param upload    the file to upload (the remuxed range, or a regenerate copy)
     * @param names     colour clip, depth clip, normal clip, poster, depth still,
     *                  normal still
     * @param outHeight the resolution chip: the height to load at, 0 = the source's
     * @return the built-but-unstored item, or null after reporting a failure
     */
    private static LibraryItem generate(final Context app, final SharedPreferences prefs,
                                        final ComfyApi api, final String rowId,
                                        final File upload, final String[] names,
                                        final int outHeight,
                                        final Listener listener,
                                        final Finisher finisher) {
        // The ROW's id, passed in. generate() used to mint its own, so a row could end
        // up holding files named after a DIFFERENT id -- harmless until something
        // derived a filename from the row id and silently missed.
        final String id = rowId;
        final String tag = ComfyWorkflow.streamTag(id);
        if (!ComfyWorkflow.isStreamTagUsable(tag)) {
            // The node would fall back to a timestamp name the app cannot predict,
            // and every fetch would ask for files that do not exist. An id that
            // sanitises to nothing is not an id this flow can run.
            fail(prefs, null, listener, app.getString(R.string.video_import_failed)
                    + ": the row's id cannot name a server run", null);
            return null;
        }

        // The dimensions the NODE will load, not the file's raw size: the chip may
        // scale the source down, and that scaled size is what the memory rule is
        // about. An unknown size gets window 1; see BatchBudget.streamWindow.
        int[] size = videoSizeOf(app, Uri.fromFile(upload));
        int loadHeight = outHeight <= 0 ? size[1] : Math.min(outHeight, size[1]);
        int loadWidth = size[1] > 0
                ? Math.round(size[0] * (float) loadHeight / size[1]) : size[0];
        int window = BatchBudget.streamWindow(loadWidth, loadHeight);
        // The CHECK's baseline, exact when the sample table can be walked: the one
        // number this app knows about its own file, and the one the duration
        // estimate famously gets wrong by one.
        int plannedFrames = sampleCountOf(app, Uri.fromFile(upload));
        Log.i(TAG, "video generate id=" + id + " source=" + upload.getName()
                + " frames=" + plannedFrames + " load=" + loadWidth + "x" + loadHeight
                + " window=" + window);

        phase(id, listener, app.getString(R.string.video_phase_uploading));
        String uploadedName;
        try {
            JSONObject up = new JSONObject(api.uploadImage(upload, upload.getName()));
            uploadedName = up.getString("name");
        } catch (Exception e) {
            // No clear here: this is the UPLOAD, before the server has the work, so
            // there is no record yet -- and no prompt id is in scope.
            listener.failed(app.getString(R.string.video_import_failed) + ": " + e.getMessage());
            return null;
        }

        final long startedAtMs = System.currentTimeMillis();
        String promptId = null;
        try {
            String info = api.getObjectInfo();
            // Free the server's cached models ONCE, before the prompt: the run is
            // one prompt now, so there is no per-chunk repetition to avoid -- but a
            // server holding a previous model's cache has less room for the window,
            // and freeing it is best effort because a server without the route must
            // not stop an import.
            try {
                api.freeMemory();
            } catch (Exception ignored) {
            }
            String graph = ComfyWorkflow.buildVideoStreamWorkflow(
                    new JSONObject(info), "MoGeInference", uploadedName, tag,
                    0, 0, Math.max(0, outHeight), window);
            JSONObject submitted = new JSONObject(api.submitPrompt(graph, "basrelief-" + id));
            promptId = submitted.getString("prompt_id");
            // THE RECORD, written once the server has accepted the prompt. From here
            // the run survives this process: the sidecar names its state, and the
            // record names the prompt, the host and the row.
            JobsStore.record(prefs, new Jobs.Job(id, promptId, api.host(), startedAtMs));
        } catch (Exception e) {
            fail(prefs, promptId, listener, app.getString(R.string.video_import_failed), e);
            return null;
        }

        try {
            phase(id, listener, app.getString(R.string.video_phase_generating));
            // Seed the line with the plan so it states a denominator from the first
            // second; the sidecar replaces both numbers as they become real.
            if (plannedFrames > 0) generating(id, listener, 0, plannedFrames);
            StreamOutputs outputs = pollStream(api, promptId, tag, id, listener, plannedFrames);
            return attach(app, prefs, api, id, names, outputs, promptId, plannedFrames,
                    listener, finisher);
        } catch (Exception e) {
            if (e instanceof ServerFailure) {
                // The server's own words become the row's state: the sheet line and
                // the tile's corner carry them until the user acts, which is the
                // whole reason a failed record is kept.
                String why = ((ServerFailure) e).reason;
                Log.w(TAG, "generate: the server reported: " + why);
                JobsStore.fail(prefs, promptId, why);
                listener.failed(app.getString(R.string.video_import_failed) + ": " + why);
            } else {
                fail(prefs, promptId, listener,
                        app.getString(R.string.video_import_failed), e);
            }
            return null;
        }
    }

    /**
     * Take a finished run's outputs, put the clips on disk and hand the built row to
     * a finisher. THE ONE IMPLEMENTATION of the tail of an import, shared with the
     * resume pass so the two cannot drift -- a resume that fetched slightly
     * differently from an import would be a second way for a clip to be wrong.
     *
     * <p><b>The colour is not fetched.</b> It is {@code names[0]} already: the import
     * remuxed the range there before submitting, and a regenerate copied its source
     * there. The node writes no colour at all, which is what makes the frames the
     * server saw and the frames the renderer plays one file by construction.
     *
     * <p><b>The frame check has two halves and they catch different failures.</b>
     * {@link #pollStream} checks the count the node REPORTED against the app's own
     * sample count before anything is downloaded; this checks the file that actually
     * ARRIVED. A discrepancy either way is one frame of drift against the colour at
     * every loop, which is the defect the old duration-derived planner shipped and
     * this project cannot see in a screenshot.
     *
     * @param outputs   the server-side file names, from /history or the sidecar
     * @param plannedFrames the app's own count of the colour clip, or 0 when unread
     */
    private static LibraryItem attach(final Context app, final SharedPreferences prefs,
                                      final ComfyApi api, final String rowId,
                                      final String[] names, final StreamOutputs outputs,
                                      final String promptId, final int plannedFrames,
                                      final Listener listener, final Finisher finisher) {
        phase(rowId, listener, app.getString(R.string.video_phase_fetching));
        final File dir = app.getFilesDir();
        final File depth = new File(dir, names[1]);
        final File normal = new File(dir, names[2]);
        boolean haveNormal = true;
        try {
            write(api.view(outputs.depthName, "", "output"), depth);
            if (outputs.normalName.isEmpty()) {
                // The node emits both; an empty name means flat lit is the honest
                // state rather than a fetch of a file nothing claimed exists.
                haveNormal = false;
            } else {
                byte[] normalBytes = api.viewIfPresent(outputs.normalName, "", "output");
                if (normalBytes == null) {
                    // A missing normal is flat lit, a deliberate state: the relief is
                    // displaced correctly and lit as a plane. It must not fail the
                    // import, and it must not silently imply shading either.
                    haveNormal = false;
                } else {
                    write(normalBytes, normal);
                }
            }
        } catch (Exception e) {
            fail(prefs, promptId, listener, app.getString(R.string.video_import_failed), e);
            return null;
        }

        if (plannedFrames > 0) {
            int depthFrames = sampleCountOf(app, Uri.fromFile(depth));
            if (depthFrames > 0 && depthFrames != plannedFrames) {
                String reason = "the depth clip holds " + depthFrames
                        + " frames but the colour holds " + plannedFrames;
                Log.w(TAG, "attach: " + rowId + " " + reason);
                fail(prefs, promptId, listener,
                        app.getString(R.string.video_import_failed) + ": " + reason, null);
                return null;
            }
        }

        File colour = new File(dir, names[0]);
        phase(rowId, listener, app.getString(R.string.video_phase_poster));
        File poster, depthStill, normalStill = null;
        try {
            poster = firstFrameAsPng(app, colour, names[3]);
            depthStill = firstFrameAsPng(app, depth, names[4]);
            if (haveNormal) normalStill = firstFrameAsPng(app, normal, names[5]);
        } catch (Exception e) {
            fail(prefs, promptId, listener, app.getString(R.string.video_import_failed), e);
            return null;
        }

        // The job is DONE and its outputs are on disk: the record has served its
        // purpose and must not make the next start ask about a finished prompt. It
        // is cleared by THIS prompt id, so a superseded run finishing cannot clear
        // the record of the submission that replaced it.
        JobsStore.clear(prefs, promptId);
        phase(rowId, listener, app.getString(R.string.video_phase_adding));
        VideoTracks tracks;
        try {
            tracks = VideoTracks.of(names[0], names[1], haveNormal ? names[2] : null);
        } catch (Exception e) {
            fail(prefs, promptId, listener, app.getString(R.string.video_import_failed), e);
            return null;
        }
        LibraryItem built = LibraryItem.of(rowId, poster.getName(),
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
     *                   trim sheet's range, and they are used to CUT the local colour
     *                   clip -- the range that is uploaded and that the node reads is
     *                   this file, so the sheet's arithmetic and the run's frames are
     *                   the same by construction. Only the note OFFSETS travel to the
     *                   server for the poster; the node itself always runs the whole
     *                   upload from frame 0.
     * @param outHeight  the resolution chip: the height the node should load at,
     *                   0 = the source's own
     */
    static void run(final Context app, final SharedPreferences prefs, final Uri uri,
                    final String label, final ComfyApi api,
                    final int startFrame, final int frames, final int outHeight,
                    final Listener listener) {
        final String id = LibraryItem.newId();
        // Registered BEFORE the first phase and the early write, so the sheet's
        // ring and line exist from the first second of the import -- the upload and
        // the submission are exactly the window before a job record can exist.
        begin(id);
        try {
            runBody(app, prefs, uri, label, api, startFrame, frames, outHeight, id,
                    listener);
        } finally {
            end(id);
        }
    }

    /** The body of {@link #run}, split so the live registration is one try/finally. */
    private static void runBody(final Context app, final SharedPreferences prefs,
                                final Uri uri, final String label, final ComfyApi api,
                                final int startFrame, final int frames, final int outHeight,
                                final String id, final Listener listener) {
        // Read ONCE, from the share itself. The poster's time and the remux's window
        // are computed from the same rate, or the picture the user lands on and the
        // range the server generates would come from two clocks.
        final float fps = frameRateOf(app, uri);

        // THE PHOTO LANDS FIRST, from the SHARE, before anything reaches the server.
        // The row exists from here on, so an import killed mid-flight -- an install,
        // an OOM kill, a swipe away -- leaves a photograph the user can see rather
        // than an orphaned clip file and no row at all. Measured: exactly that loss
        // cost a finished server job and its only handle.
        //
        // The poster is the RANGE's first frame, so it is the same picture the
        // finished import writes and does not visibly change when the import lands.
        // It is also why a poster failure now REFUSES the import instead of being
        // logged and skipped: without a poster there is no row, and the finisher
        // edits the row in place -- a finished server job would have nowhere to
        // land.
        final long posterAtUs = (long) (startFrame / Math.max(1f, fps) * 1_000_000f);
        phase(id, listener, app.getString(R.string.video_phase_poster));
        File poster;
        try {
            poster = firstFrameAsPng(app, uri, LibraryItem.imageFileName(id), posterAtUs);
        } catch (Exception e) {
            fail(prefs, null, listener, "the shared video could not be read for a poster", e);
            return;
        }
        LibraryItem placed = LibraryItem.of(id, poster.getName(), null,
                System.currentTimeMillis()).withLabel(label);
        // Selected as well as added: the user just asked for this photo, and a row
        // that arrives without being shown is a photo they have to go looking for.
        LibraryStore.saveNow(prefs,
                LibraryStore.load(prefs).withItem(placed).withSelection(placed.id));
        listener.added(placed);

        // Id-derived names, so a re-import of the same video is a new row with its
        // own files; regenerate uses stamped names for the same row.
        final String[] names = {
                LibraryItem.videoFileName(id),
                LibraryItem.videoDepthFileName(id),
                LibraryItem.videoNormalFileName(id),
                LibraryItem.imageFileName(id),
                LibraryItem.depthFileName(id),
                LibraryItem.normalFileName(id),
        };

        // THE RANGE IS CUT LOCALLY, AND IT IS THE COLOUR AND THE UPLOAD. The samples
        // of the chosen range are copied into their own container, so a 126 MB source
        // uploads a few megabytes -- and the copy is kept under the row's colour name,
        // which is both what the renderer plays and what the node reads.
        phase(id, listener, app.getString(R.string.video_phase_copying));
        final long startUs = (long) (startFrame / Math.max(1f, fps) * 1_000_000f);
        // frames 0 = the whole clip, and then there is no end time to cap: the
        // copy runs until the samples do.
        final long durUs = frames > 0
                ? (long) (frames / Math.max(1f, fps) * 1_000_000f)
                : Long.MAX_VALUE / 2;
        final File colour = new File(app.getFilesDir(), names[0]);
        File made = remuxRange(app, uri, colour, startUs, durUs);
        if (made == null) {
            // A zero-length file under the colour name would make Regenerate treat
            // this row as having a colour clip when it does not.
            colour.delete();
            // The provider may not offer the extractor a seekable handle. Copy the
            // share once and cut the range from the copy -- the colour clip must be
            // cut locally either way, because it is the playback medium.
            File staged = new File(app.getFilesDir(), "stage-import.mp4");
            try {
                copyTo(app, uri, staged);
            } catch (Exception e) {
                fail(prefs, null, listener, "the shared video could not be read", e);
                return;
            }
            made = remuxRange(staged, colour, startUs, durUs);
            deleteQuietly(staged);
            if (made == null) {
                colour.delete();
                fail(prefs, null, listener,
                        "the shared video could not be cut into a clip", null);
                return;
            }
        }

        LibraryItem item = generate(app, prefs, api, id, made, names, outHeight, listener,
                editFinisher(prefs, id));
        if (item != null) listener.done(item);
    }

    // ------------------------------------------------------------ server reads

    /**
     * Wait for the one prompt, reporting the sidecar's progress on the way.
     *
     * <p><b>The run must be FINISHED, not merely producing.</b> The history entry can
     * carry outputs before the run is over, and the old test returned on the first
     * output it saw -- then {@code attach} found no depth clip and the user was told
     * the import had failed while it was still working. The status is the only thing
     * that knows the difference between running and finished; an error is asked for
     * FIRST, because an errored run reports {@code completed: false} and would
     * otherwise spin to the timeout.</p>
     *
     * <p><b>The sidecar is read every pass and the history in the same pass, sidecar
     * first.</b> The sidecar is the progress channel and the history is the
     * completion channel; asking both at the poll rate means the line moves at the
     * rate the node writes and a finished run is noticed within one poll of the file
     * landing. A sidecar read that fails is ignored -- absence is its normal state
     * before the first window and after an output-directory clean -- while a history
     * failure is tolerated {@link #HISTORY_MISSES} times before it is one.
     */
    private static StreamOutputs pollStream(ComfyApi api, String promptId, String tag,
                                            String itemId, Listener listener,
                                            int plannedFrames) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int missed = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            try {
                byte[] body = api.viewIfPresent(ComfyWorkflow.streamProgressName(tag),
                        "", "output");
                if (body != null) {
                    JSONObject side = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    int total = side.optInt("total", 0);
                    if (total > 0) {
                        generating(itemId, listener, side.optInt("done", 0), total);
                    }
                }
            } catch (Exception ignored) {
                // The sidecar is a bonus: the history below is the authority on
                // whether the run is over, and a failed progress read is not a
                // failed run.
            }

            JSONObject entry;
            try {
                JSONObject history = new JSONObject(api.getHistory(promptId));
                entry = history.optJSONObject(promptId);
                missed = 0;
            } catch (Exception e) {
                if (++missed >= HISTORY_MISSES) throw e;
                continue;
            }
            if (entry == null) continue;   // queued, or not yet recorded

            String why = failedReason(entry);
            if (why != null) throw new ServerFailure(why);
            JSONObject status = entry.optJSONObject("status");
            if (status != null && status.optBoolean("completed", false)) {
                StreamOutputs out = streamOutputsOf(entry, tag);
                verifyStreamFrames(out, plannedFrames);
                return out;
            }
        }
        throw new Exception("the run did not finish in time");
    }

    /**
     * The server's reported frame count must be the count the app planned for.
     *
     * <p><b>This replaces the old planner's job and inverts it.</b> The planner
     * computed a count to ASK for from the container's duration; a 240-frame remux
     * reports 9.9586 s, so {@code round(9.9586 x 24)} is 239 and the run asked for
     * one frame fewer than the colour held. The run no longer asks for a count at
     * all -- it runs the whole upload -- and the app's EXACT sample count is used to
     * check what the node says it wrote. A mismatch is a failure with both numbers
     * in it, because one frame short is a hitch at every loop and nothing else in
     * the pipeline can see it.
     *
     * <p>Either side unknown (0) is reported to the log and not treated as a
     * mismatch: a window claims nothing, and {@link #attach} is the second oracle
     * once the file is on disk.
     */
    private static void verifyStreamFrames(StreamOutputs out, int plannedFrames)
            throws Exception {
        if (out.frames <= 0) {
            Log.w(TAG, "the run reported no frame count; only the fetched clip can be checked");
            return;
        }
        if (plannedFrames <= 0) {
            Log.w(TAG, "the run reported " + out.frames
                    + " frames and the upload's own count could not be read");
            return;
        }
        if (out.frames != plannedFrames) {
            throw new Exception("the server wrote " + out.frames
                    + " frames but the clip holds " + plannedFrames);
        }
    }

    /**
     * A finished history entry's ui payload: the two file names and the frame count.
     *
     * <p>The node is an OUTPUT_NODE whose ui payload names the files it wrote under
     * its own node id ({@code {"2": {"depth": [...], "normal": [...],
     * "frames": [...]}}}). The payload is found by its SHAPE rather than by that id,
     * because the builder owns the id and a rename there would otherwise silently
     * find nothing; the deterministic names then still address the files, since the
     * node derives them from the tag.
     */
    private static StreamOutputs streamOutputsOf(JSONObject entry, String tag) {
        String depth = null;
        String normal = null;
        int frames = 0;
        JSONObject outputs = entry == null ? null : entry.optJSONObject("outputs");
        if (outputs != null) {
            Iterator<String> nodes = outputs.keys();
            while (nodes.hasNext()) {
                JSONObject node = outputs.optJSONObject(nodes.next());
                if (node == null) continue;
                if (node.optJSONArray("depth") == null) continue;
                depth = firstString(node, "depth");
                normal = firstString(node, "normal");
                frames = firstInt(node, "frames");
                break;
            }
        }
        if (depth == null) depth = ComfyWorkflow.streamDepthName(tag);
        if (normal == null) normal = ComfyWorkflow.streamNormalName(tag);
        return new StreamOutputs(depth, normal, frames);
    }

    private static String firstString(JSONObject o, String key) {
        JSONArray arr = o.optJSONArray(key);
        if (arr == null || arr.length() == 0) return null;
        String s = arr.optString(0, "");
        return s.isEmpty() ? null : s;
    }

    private static int firstInt(JSONObject o, String key) {
        JSONArray arr = o.optJSONArray(key);
        if (arr == null || arr.length() == 0) return 0;
        return arr.optInt(0, 0);
    }

    /**
     * The server's own reason for a run the sidecar says failed, or null.
     *
     * <p>Reads the history entry and returns the first {@code execution_error}
     * message's text, first line only — a CUDA OOM's message runs to several lines and
     * the first one is the sentence that matters. Never throws: a reason is a bonus,
     * not something that may replace the failure it explains.
     */
    private static String serverReason(ComfyApi api, String promptId) {
        if (promptId == null) return null;
        try {
            JSONObject history = new JSONObject(api.getHistory(promptId));
            if (!history.has(promptId)) return null;
            return executionError(history.getJSONObject(promptId));
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * The first {@code execution_error} message in a history entry, first line only,
     * or null when the run has not errored.
     *
     * <p><b>One extraction, three readers.</b> The in-flight poll, the resume and the
     * sidecar-failed path all need the same answer, and the failure this project
     * keeps paying for is a second implementation that drifts: a resume that
     * recognised an error the poll did not would treat a running job as finished.
     * The first line is taken because a CUDA out-of-memory's message runs to several
     * lines of advice and the first one is the sentence that names the cause.
     *
     * <p>Never throws: a reason is a bonus, not something that may replace the
     * failure it explains.
     */
    private static String executionError(JSONObject entry) {
        if (entry == null) return null;
        JSONObject status = entry.optJSONObject("status");
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
        return null;
    }

    /**
     * The server's own words for a run that has FAILED, or null while it has not.
     *
     * <p><b>One shape, every reader.</b> A run that reports {@code status_str: error}
     * without a readable message must not be treated as finished by one reader and as
     * failed by another, so both the poll and the resume ask this one question --
     * which delegates to {@link #executionError} for the message rather than parsing
     * the status a second way.
     */
    private static String failedReason(JSONObject entry) {
        String why = executionError(entry);
        JSONObject status = entry.optJSONObject("status");
        if (status != null && "error".equals(status.optString("status_str", ""))) {
            return why != null ? why : ComfyWorkflow.extractExecutionError(status);
        }
        return why;
    }

    // ------------------------------------------------------------------ files

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
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

    /** A file-to-file copy, for the colour a regenerate re-uses. */
    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
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

    /** Delete a transient file whose absence is equally fine. */
    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            f.delete();
        } catch (Throwable ignored) {
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
        return firstFrameAsPng(app, Uri.fromFile(clip), name, atUs);
    }

    /**
     * The same frame, read from the share URI.
     *
     * <p>{@code MediaMetadataRetriever.setDataSource(Context, Uri)} is what lets the
     * early poster come from the share itself: the source is never copied into
     * filesDir, so there is no local file for this to read even if it wanted one.
     */
    private static File firstFrameAsPng(Context app, Uri clip, String name, long atUs)
            throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(app, clip);
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
     * The facts the trim sheet needs from the source, read from the share.
     *
     * <p>One call rather than two at the call site, so the rate the poster time is
     * computed from and the rate the sheet displays cannot be read twice and differ.
     * The sheet's bar needs the rate and the length; the resolution chips are
     * self-describing and the memory rule is asked with the UPLOAD's dimensions at
     * run time, so the source's height is deliberately not read here.
     */
    static final class Meta {
        final float fps;
        final int frames;

        Meta(float fps, int frames) {
            this.fps = fps;
            this.frames = frames;
        }
    }

    static Meta metaOf(Context app, Uri uri) {
        float fps = frameRateOf(app, uri);
        return new Meta(fps, frameCountOf(app, uri, fps));
    }

    /**
     * How many frames the container holds, exactly when the sample table says.
     *
     * <p><b>The exact sample count wins, and that is a measured fact rather than
     * caution.</b> The duration estimate is wrong by one on a file this app writes
     * itself: a 240-frame 24fps remux reports 9.9586s — the MP4 muxer takes the
     * duration from the last sample it processed, which is not always the highest
     * presentation time — and {@code round(9.9586 × 24)} is <b>239</b>. The run no
     * longer PLANS from this number: the colour clip is the plan, and the node runs
     * all of it. The count is still read exactly for the trim bar's denominator and
     * for the frame check on the node's report, and the duration stays only as the
     * fallback for a container whose sample table cannot be walked — the rule that a
     * window claims nothing, applied to a number.
     *
     * <p>The duration itself is read from {@link MediaMetadataRetriever}, NOT from
     * MediaExtractor's track format: measured, the extractor's {@code durationUs} is
     * absent for the files this app reads, so the count came back 0, the trim bar
     * was built with a one-frame clip, and the sheet sat at 0:00 with handles that
     * could not move — reported as "trim sheet stuck at 0.0". The retriever is the
     * same source the filmstrip already reads successfully, so the sheet cannot
     * disagree with its own thumbnails about how long the clip is.
     */
    static int frameCountOf(Context app, Uri clip, float fps) {
        int exact = sampleCountOf(app, clip);
        if (exact > 0) return exact;
        long durationUs = durationUsOf(app, clip);
        if (durationUs <= 0 || fps <= 0) return 0;
        return Math.round(durationUs / 1_000_000f * fps);
    }

    /**
     * The video track's sample count — the exact number of decodable frames.
     *
     * <p>Walks the sample table without reading any sample data, so the cost is
     * the container's index rather than its bytes. Returns 0 when the container
     * cannot be read at all, which makes the caller fall back to the duration
     * estimate — a window that claims nothing rather than a guessed count.
     */
    private static int sampleCountOf(Context app, Uri clip) {
        if (clip == null) return 0;
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(app, clip, null);
            ex.selectTrack(videoTrackOf(ex));
            int n = 0;
            while (ex.getSampleTime() >= 0) {
                n++;
                ex.advance();
            }
            return n;
        } catch (Throwable t) {
            Log.w(TAG, "sample count unreadable: " + t);
            return 0;
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    private static long durationUsOf(Context app, Uri clip) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(app, clip);
            String ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (ms != null) return Long.parseLong(ms) * 1000L;
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        return 0L;
    }

    /**
     * The video track's own dimensions, for the memory rule.
     *
     * <p>Deliberately un-defaulted, unlike the old height reader: the window is a
     * memory decision, so an unread size must come back as "unknown" and get the
     * smallest window rather than a guessed 1080 that the rule would then trust.
     * The file is the app's own remux, so the only way here is a corrupt or empty
     * container.
     *
     * @return {@code {width, height}}, or {@code {0, 0}} when the track cannot say
     */
    private static int[] videoSizeOf(Context app, Uri clip) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(app, clip, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                if (fmt.containsKey(android.media.MediaFormat.KEY_WIDTH)
                        && fmt.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                    int w = fmt.getInteger(android.media.MediaFormat.KEY_WIDTH);
                    int h = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT);
                    if (w > 0 && h > 0) return new int[] {w, h};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "source dimensions unreadable: " + t);
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
        return new int[] {0, 0};
    }

    /**
     * The source frame rate, in frames per second.
     *
     * <p>Read from the container, and defaulted rather than guessed into silence: a
     * container that will not say gives 24, which is the common phone rate. A wrong
     * rate here slides the poster's time against the range, so it is read rather
     * than assumed -- but a container with no declared rate is not a reason to
     * refuse a clip.
     */
    static float frameRateOf(Context app, Uri clip) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(app, clip, null);
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

    private static int videoTrackOf(MediaExtractor ex) throws Exception {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        throw new Exception("no video track in " + ex);
    }
}
