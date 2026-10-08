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
import com.amoshydra.basrelief.core.depth.FrameCount;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 * <p><b>A long range is submitted as CHUNKS, and the app joins the results.</b> The
 * server graph holds a whole batch in one tensor and runs one inference over it —
 * measured, 600 frames at 1080p died with 17.10 GiB allocated on a 23.59 GiB card,
 * and 600 at 720p with 15.63 GiB — so cost is O(frames) twice over and nothing is
 * streamed. The fix is shape, not estimation: the app divides the range it holds
 * into prompts of at most {@link BatchBudget#chunkFrames} frames at the chosen
 * height, submits them to the same video graph with the loader's own
 * {@code skip_first_frames}/{@code frame_load_cap} window, and concatenates the
 * per-chunk clips here without re-encoding. Length stops being the thing that
 * decides whether an import can run at all.
 *
 * <p><b>The colour clip is the app's own remux, not a third server output.</b> For
 * every run that has the range locally (which is every run whose remux succeeded),
 * the graph is asked for depth and normal only: the app already holds the exact
 * frames as an ordinary MP4 — it is the file it uploaded — so a server-side
 * re-encode of the same frames is work and a download for nothing, and for a chunked
 * run it is also the only way to end with ONE colour rather than N. The one shape
 * that still asks the server for a colour is the run with no local range at all: a
 * failed remux, and a v1 job from the build that always requested one.
 *
 * <p><b>The row goes in first, and it is a photograph rather than a pending clip.</b>
 * The old shape wrote the row only on success, so a failed generation lost the
 * user's video and left them a message; and a successful one left them watching a
 * toast for three minutes with nothing in the strip. A poster frame is a thing the
 * app has always been able to render, so the row is exactly that, and the generation
 * edits it in place when the clips land.
 *
 * <p><b>What has not changed is the pair invariant.</b> No clips are stored until
 * there are colour AND depth, because a stored half-pair is the thing
 * {@code VideoTracks} exists to make unrepresentable -- and the index still owns the
 * selection and only the index. The row simply carries no clips until they exist,
 * which is a state the schema has always had: a photograph with no map.
 *
 * <p><b>The poster frame is the first frame of the RANGE, and the still depth and
 * normal are the first frames of the generated clips.</b> That is what keeps the
 * photograph and the clip the same picture: the still pair is not an approximation
 * of the clip, it is a frame of it. A clip has to be able to render <i>before</i> its
 * first decoded frame arrives, and that picture is this one.
 *
 * <p><b>The source is the share URI, not a copy.</b> Staging the whole clip before
 * the sheet could ask cost 10-15 seconds on a 126 MB share and left 126 MB of the
 * user's storage behind per import. {@code MediaMetadataRetriever} and
 * {@code MediaExtractor} both read content URIs, so the filmstrip, the rate, the
 * frame count and the poster come from the share itself; only the RANGE is
 * materialised locally, and it is the remuxed range (a few megabytes) -- for a
 * trim and for the whole clip alike, because that file is also the colour clip the
 * renderer ends up playing. When the remux itself fails the source is copied and
 * sent whole instead, with the server writing the colour. The share grants
 * read access for the activity's lifetime, which covers the sheet and the local
 * work; the resume path after a restart never touches the URI -- it fetches the
 * server's own outputs from the job record.
 *
 * <p><b>One {@code withFilesAndVideo} call</b>, because that is the only writer of
 * the clips field and a writer that added the row in two steps is how a row ends up
 * with a colour clip and no depth — the exact half-pair {@code VideoTracks} exists to
 * make unrepresentable.
 *
 * <p>Everything here is disk and network, so it runs on the {@code Io} worker. It
 * reports progress by PHASE rather than by percentage, because there is no honest
 * percentage to report across an upload, a server-side inference and two downloads —
 * and for a chunked run the honest denominator is the chunk: "Generating 3 of 8" is
 * a fact the user can read, where "37%" would be a number nothing measured.
 */
final class VideoImport {

    private static final String TAG = "Basrelief";

    /**
     * What THIS PROCESS knows about one row's in-flight import.
     *
     * <p><b>The record on disk is the durable half; this is the live half, and
     * the row needs both because they cover different moments.</b> The job record
     * is written once the server has accepted every prompt — before that, the
     * upload and submission are still in flight and there is nothing to write
     * down. The ring and the sheet's line must exist from the FIRST second (that
     * is the mockup's whole point: the row is the place progress lives), so the
     * worker registers the row id here before it does anything, and the phase
     * text it last reported is readable from the UI.
     *
     * <p>In memory and deliberately not persisted: if the process dies, the
     * import it was reporting dies with it, and the record is what the next
     * launch resumes from. A persisted copy would be a second answer to "is this
     * running", and the two could disagree.
     */
    static final class Live {
        /** The last phase message, for the line before a chunk count exists. */
        volatile String phase = "";
        /** True while a chunk is being generated, so the line can name k of N. */
        volatile boolean generating;
        volatile int chunk;
        volatile int count;
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
     * record solves for the count.
     */
    private static void phase(String itemId, Listener listener, String message) {
        Live l = LIVE.get(itemId);
        if (l != null) {
            l.phase = message == null ? "" : message;
            l.generating = false;
        }
        listener.phase(message);
    }

    /** A chunk is being waited on: the line may say k of N. */
    private static void generating(String itemId, int chunk, int count) {
        Live l = LIVE.get(itemId);
        if (l == null) return;
        l.generating = true;
        l.chunk = Math.max(1, chunk);
        l.count = Math.max(1, count);
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
     * Poll interval and ceiling for ONE server-side prompt.
     *
     * <p>Per prompt, not per import: a chunked run waits this long for each chunk in
     * turn, and the chunk is the unit the server actually runs.
     */
    private static final long POLL_MS = 3000L;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** The temp names a chunk's downloads land under before they are joined. */
    private static final String DEPTH_PART = "part-depth-";
    private static final String NORMAL_PART = "part-normal-";

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

    /**
     * One submitted prompt and, once the server has finished it, its outputs.
     *
     * <p>The chunk list the job record holds and this list are the same ORDER by
     * construction: submission walks the plan and appends, so position k here is
     * position k there, which is what makes the concatenation in {@link #attach}
     * cover the range in the right order.
     */
    private static final class Part {
        final String promptId;
        JSONObject outputs;

        Part(String promptId, JSONObject outputs) {
            this.promptId = promptId == null ? "" : promptId;
            this.outputs = outputs;
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
     * the existing colour clip is re-run through the video graph, and the depth and
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
        // The colour clip is already here; regeneration re-runs the graph over IT.
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
        // A regenerate passes 0, 0: the stored colour clip is ALREADY the trimmed
        // range, so regenerating its depth means running the whole of the copy.
        LibraryItem updated = generate(app, prefs, api, item.id, colourCopy, colourCopy,
                names, 0, 0, 0, listener,
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

    /**
     * Finish an import a restart interrupted, from its job record.
     *
     * <p>The other half of "you can leave — it keeps going": the record is written
     * when the server takes the work, and this is what reads it. Called at studio
     * start for each pending job, and it does one of five things, each of which is a
     * state rather than an error:
     *
     * <ul>
     *   <li><b>The photo is gone</b> (deleted while the job ran) — clear the record;
     *       there is nothing to fetch into.</li>
     *   <li><b>The server is unreachable</b> — KEEP the record. A sleeping laptop is
     *       not a lost import, and this is the case the record exists for.</li>
     *   <li><b>The server has never heard of the prompt</b> — clear it. Asking for
     *       ever about a prompt that is not there is a record that never clears, and
     *       the photograph the early write left is still on screen to Regenerate.</li>
     *   <li><b>The run FAILED on the server</b> — record its own message (a CUDA
     *       out-of-memory's text is the useful part) as the job's FAILED state,
     *       report it, and leave the row as the photograph the early write made
     *       it. The record is kept, not cleared: the sheet's line and the tile's
     *       corner carry the failure until the user acts, and a later foreground
     *       skips it rather than re-asking a run that has already answered.</li>
     *   <li><b>Finished</b> — fetch, attach, clear, exactly as an import would.</li>
     * </ul>
     *
     * <p><b>A chunked job is all-or-nothing.</b> Every chunk is asked, and nothing is
     * fetched until every one of them reports {@code completed}: a partial fetch would
     * concatenate two of eight chunks and write a clip that plays short, which is a
     * worse lie than "still working". One chunk errored, or is missing from the
     * server, fails the whole job with that chunk's own reason.
     *
     * <p><b>{@code outputs.length() > 0} is NOT "finished", and this is the bug the
     * status read fixes.</b> A running job can already have outputs, so the old test
     * called {@code attach()}, which found no depth clip, failed, and CLEARED a record
     * for a job that might still complete. The history entry's own {@code status} is
     * the only thing that knows the difference between running, finished and errored.
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
        // The record's plan must still describe a range: chunks in frame order, each
        // starting where the last stopped. The concatenation is by ORDER, so a plan
        // that overlapped or gapped would not merely describe the work wrongly -- it
        // would let the joined clip be built against a range that never existed. The
        // FIRST chunk is allowed any start: a fallback run is one window into the
        // source at the trim's own start, and only the joins have to be exact. A
        // migrated v1 chunk records no range (frames 0), which is not a fault.
        int expectedStart = -1;
        for (Jobs.Chunk chunk : job.chunks) {
            if (chunk.frames <= 0) continue;
            if (expectedStart >= 0 && chunk.startFrame != expectedStart) {
                Log.w(TAG, "resume: " + job.itemId + " chunk plan is not contiguous at "
                        + chunk.promptId);
                JobsStore.clear(prefs, chunk.promptId);
                return;
            }
            expectedStart = chunk.startFrame + chunk.frames;
        }
        Log.i(TAG, "resume: " + job.itemId + " has " + job.chunks.size() + " chunk(s), "
                + (expectedStart < 0 ? "an unrecorded range"
                        : expectedStart + " frames planned"));

        final List<Part> parts = new ArrayList<Part>();
        for (final Jobs.Chunk chunk : job.chunks) {
            JSONObject entry;
            try {
                JSONObject history = new JSONObject(api.getHistory(chunk.promptId));
                entry = history.optJSONObject(chunk.promptId);
            } catch (Exception e) {
                Log.w(TAG, "resume: " + chunk.promptId + " unreachable: " + e);
                return;   // server unreachable: KEEP the record and ask again
            }
            if (entry == null) {
                // One chunk the server has never heard of is the whole job gone:
                // the range cannot be fetched in part, and asking for ever about a
                // prompt that is not there is a record that never clears.
                Log.w(TAG, "resume: the server has no " + chunk.promptId);
                JobsStore.clear(prefs, chunk.promptId);
                return;
            }
            // An error is a RESULT, and it is not one to fetch. The server's own
            // words are the useful part -- a CUDA out-of-memory message says what
            // the range asked for and what the card had, which is the one thing the
            // user can act on -- so it is reported AND kept as the job's failed
            // state, and the row is left as the photograph it already is. Keeping,
            // not clearing, is what puts the message on the sheet instead of in a
            // toast that is gone before it is read.
            String why = failedReason(entry);
            if (why != null) {
                Log.w(TAG, "resume: " + chunk.promptId + " failed on the server: " + why);
                JobsStore.fail(prefs, chunk.promptId, why);
                listener.failed(app.getString(R.string.video_import_failed) + ": " + why);
                return;
            }
            // Neither finished nor failed: this chunk is still running. The record
            // is KEPT and asked again on the next foreground, which is the whole
            // contract of the record -- and nothing is attached while it is true.
            JSONObject status = entry.optJSONObject("status");
            if (status == null || !status.optBoolean("completed", false)) return;
            JSONObject outputs = entry.optJSONObject("outputs");
            parts.add(new Part(chunk.promptId, outputs == null ? new JSONObject() : outputs));
        }

        // The row's OWN names: attach() writes the fetched clips under the
        // id-derived names, and the resume must land on the same files or the row
        // would point at nothing.
        String[] names = {
                LibraryItem.videoFileName(job.itemId),
                LibraryItem.videoDepthFileName(job.itemId),
                LibraryItem.videoNormalFileName(job.itemId),
                LibraryItem.imageFileName(job.itemId),
                LibraryItem.depthFileName(job.itemId),
                LibraryItem.normalFileName(job.itemId),
        };

        // WHERE THE COLOUR IS, and it is not always names[0]. A share import writes
        // its remuxed range under the row's own colour name before it submits, so
        // that is the file. A REGENERATE submits a copy under a STAMPED name that
        // this record does not carry -- the record is keyed by item, and the stamped
        // name belongs to the in-flight generation -- so the source is the colour the
        // row is already showing, which is exactly the file that generation copied.
        // A v1 job has neither, and its server run wrote one; attach fetches that.
        File localColour = null;
        if (item.video != null) {
            localColour = item.video.colorFile(app.getFilesDir());
        }
        if (localColour == null || !localColour.exists()) {
            File early = new File(app.getFilesDir(), names[0]);
            localColour = early.exists() ? early : null;
        }
        attach(app, prefs, api, job.itemId, names, parts, localColour, listener,
                new Finisher() {
                    @Override
                    public LibraryItem finish(final LibraryItem built) {
                        // The SAME row: it exists from the early write, so this is an
                        // edit and not an add. Same finisher shape as regenerate.
                        LibraryStore.editItem(prefs, job.itemId, new LibraryStore.Editor() {
                            @Override
                            public LibraryItem edit(LibraryItem it) {
                                return it.withFilesAndVideo(built.imageName, built.depthName,
                                        built.normalName, built.video);
                            }
                        });
                        return LibraryStore.load(prefs).find(job.itemId);
                    }
                });
    }

    /**
     * Copy just the chosen range out of a video, WITHOUT re-encoding.
     *
     * <p><b>The upload is the range, not the file.</b> Measured: a 126 MB source
     * shared in was refused by the proxy with HTTP 413 (100 MB passes, 126 MB does
     * not), and even when it fits, uploading two minutes of video to generate a four
     * second loop is the wrong shape -- minutes of transfer for a loop that tiles.
     * MediaExtractor and MediaMuxer copy the encoded samples of the range into a new
     * container, so nothing is decoded, nothing is re-encoded, and no quality is lost:
     * a 4s range of a 126 MB file lands at a few megabytes.
     *
     * <p><b>Keyframe-aligned, and that is a trade rather than a detail.</b> The seek
     * lands on the nearest sync sample at or before the requested start, because a
     * decoder cannot begin mid-GOP: the range may therefore begin a second or so
     * early. The alternative -- re-encoding from the exact frame -- costs the whole
     * video pipeline on the device for a trim the server can refine.
     *
     * <p><b>The range is also the colour clip, and it is what a chunk plan
     * partitions.</b> Every frame the server is asked about is a frame of this file,
     * and the file stays under the row's own colour name, so a run that dies before
     * the depth lands leaves something Regenerate can use rather than nothing.
     *
     * @return the new file, or null when the range could not be copied (the caller
     *         falls back to the old single-prompt shape over the source)
     */
    private static File remuxRange(Context app, Uri source, File out,
                                   long startUs, long durUs) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaMuxer mux = null;
        try {
            // The SHARE URI, read where it is. MediaExtractor accepts a content URI
            // directly, so the source is never copied into filesDir to be read.
            ex.setDataSource(app, source, null);
            int track = -1;
            android.media.MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return null;
            ex.selectTrack(track);
            ex.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            mux = new android.media.MediaMuxer(out.getPath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = mux.addTrack(fmt);
            mux.start();

            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 << 20);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
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
            Log.w(TAG, "remux failed, falling back to the source: " + t);
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

    /** The last step of a generate, split so regenerate can edit in place instead. */
    private interface Finisher {
        LibraryItem finish(LibraryItem built);
    }

    /**
     * The shared pipeline: upload the range, submit one prompt per chunk, wait for
     * them all, fetch and join the clips, and hand the built row to a finisher.
     *
     * <p><b>Every prompt is submitted before the first is waited on.</b> The queue
     * then belongs to the server, so an app restart (or a swipe away) cannot strand
     * a chunk that was never sent -- the record lists them all the moment they all
     * exist, and the resume pass fetches whatever finished. Submitting lazily would
     * make "you can leave — it keeps going" true only for the chunks luck reached.
     *
     * @param upload      the file to upload (the remuxed range, or a staged source)
     * @param localColour the app's own colour clip for this run, or null when the
     *                    run has no local range and must ask the server to write one
     * @param names       colour clip, depth clip, normal clip, poster, depth still,
     *                    normal still
     * @return the built-but-unstored item, or null after reporting a failure
     */
    private static LibraryItem generate(final Context app, final SharedPreferences prefs,
                                        final ComfyApi api, final String rowId,
                                        final File upload, final File localColour,
                                        final String[] names,
                                        final int startFrame, final int frames,
                                        final int outHeight,
                                        final Listener listener,
                                        final Finisher finisher) {
        // The ROW's id, passed in. generate() used to mint its own, so a row could end
        // up holding files named after a DIFFERENT id -- harmless until something
        // derived a filename from the row id and silently missed.
        final String id = rowId;
        final float fps = frameRateOf(app, Uri.fromFile(upload));

        // THE CHUNK PLAN, and the one number it hangs on. A range is chunked by
        // FRAMES at the height the server will actually load it at, because that is
        // what the loader's skip/cap count and what MoGe's memory follows. The plan
        // is derived from the UPLOAD file's own length -- the colour the app keeps
        // for the same frames -- so chunk k continues where k-1 stopped by
        // construction rather than by a second reading of the sheet's range.
        final int sourceHeight = sourceHeightOf(app, Uri.fromFile(upload));
        final int loadHeight = BatchBudget.heightOf(outHeight, sourceHeight);
        final int totalFrames = localColour != null
                ? frameCountOf(app, Uri.fromFile(upload), fps)
                : 0;
        final List<int[]> plan = new ArrayList<int[]>();
        final boolean includeColour;
        if (localColour != null) {
            plan.addAll(BatchBudget.chunkPlan(totalFrames, loadHeight));
            if (plan.isEmpty()) {
                // No length could be read (or no chunk size): ONE prompt over
                // everything the file holds, which is the pre-chunking shape and
                // honest about it -- the graph's own cap of 0 reads to the end.
                plan.add(new int[] {0, 0});
                Log.w(TAG, "video generate: no frame count for " + upload.getName()
                        + ", submitting one uncapped prompt");
            }
            includeColour = false;
        } else {
            // The fallback: no local range, so the one prompt spans the source's
            // own window and must write its own colour or the clip has none.
            plan.add(new int[] {Math.max(0, startFrame), Math.max(0, frames)});
            includeColour = true;
        }
        final int promptCount = plan.size();
        Log.i(TAG, "video generate id=" + id + " source=" + upload.getName()
                + " fps=" + fps + " frames=" + totalFrames + " height=" + loadHeight
                + " prompts=" + promptCount + " colour=" + (includeColour ? "server" : "local"));

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

        final List<Part> parts = new ArrayList<Part>();
        try {
            String info = api.getObjectInfo();
            // Free the server's cached models ONCE, before the first prompt: every
            // chunk loads the same model, so repeating this per chunk would free the
            // thing the next prompt is about to need. Best effort, because a server
            // without the route must not stop an import.
            try {
                api.freeMemory();
            } catch (Exception ignored) {
            }
            long startedAtMs = System.currentTimeMillis();
            for (int i = 0; i < promptCount; i++) {
                int[] chunk = plan.get(i);
                // Each prompt is the SAME graph with a different window: the loader
                // takes skip_first_frames and frame_load_cap in FRAMES, so the chunks
                // tile the upload without a frame of arithmetic on the device.
                //
                // The stamp is offset per chunk because it IS part of the output
                // filename: two prompts built in the same millisecond would write
                // `depthmap_<same>_00001.mp4` and the second would overwrite the
                // first, which for a chunked run means silently losing a chunk's
                // depth -- the fetch would find one file where it expected two.
                String graph = ComfyWorkflow.buildVideoWorkflow(
                        new JSONObject(info), "MoGeInference", uploadedName,
                        startedAtMs + i, fps,
                        // custom_height, and the reason it is here rather than a
                        // client-side resize: VHS_LoadVideo scales the batch as it
                        // loads, so a 720p choice shrinks the IMAGE BATCH, MoGe's
                        // working set and the output clips at once. Width stays 0,
                        // which VHS reads as "keep the aspect".
                        chunk[1], chunk[0], 0, outHeight, includeColour);
                JSONObject submitted = new JSONObject(api.submitPrompt(
                        graph, "basrelief-" + id + "-" + i));
                parts.add(new Part(submitted.getString("prompt_id"), null));
            }
            // THE RECORD, written once the LAST prompt has been accepted. A record
            // holding only the chunks submitted so far would describe a range the
            // resume cannot fetch in part (it is all-or-nothing by design), so the
            // one useful moment to write it is when the list is complete.
            List<Jobs.Chunk> jobChunks = new ArrayList<Jobs.Chunk>();
            for (int i = 0; i < promptCount; i++) {
                int[] chunk = plan.get(i);
                jobChunks.add(new Jobs.Chunk(parts.get(i).promptId, chunk[0], chunk[1]));
            }
            JobsStore.record(prefs,
                    new Jobs.Job(id, jobChunks, api.host(), startedAtMs)
                            .withPlan(loadHeight));
        } catch (Exception e) {
            fail(prefs, parts.isEmpty() ? null : parts.get(0).promptId, listener,
                    app.getString(R.string.video_import_failed), e);
            return null;
        }

        // WAIT FOR EVERY CHUNK, reporting progress per chunk. The phase is a fact
        // with a denominator -- "Generating 3 of 8" -- because a long import has no
        // honest percentage and the chunk is the unit the server actually runs.
        // Each completion is also written to the record, so the count survives a
        // sheet rebuild rather than living only in this loop.
        try {
            for (int i = 0; i < promptCount; i++) {
                phase(id, listener, promptCount > 1
                        ? app.getString(R.string.video_phase_generating_chunk,
                                i + 1, promptCount)
                        : app.getString(R.string.video_phase_generating));
                generating(id, i + 1, promptCount);
                parts.get(i).outputs = pollOutputs(api, parts.get(i).promptId);
                JobsStore.progress(prefs, id, i + 1);
            }
        } catch (Exception e) {
            if (e instanceof ServerFailure) {
                // The server's own words become the row's state: the sheet line and
                // the tile's corner carry them until the user acts, which is the
                // whole reason a failed record is kept.
                String why = ((ServerFailure) e).reason;
                Log.w(TAG, "generate: the server reported: " + why);
                JobsStore.fail(prefs, parts.get(0).promptId, why);
                listener.failed(app.getString(R.string.video_import_failed) + ": " + why);
            } else {
                fail(prefs, parts.get(0).promptId, listener,
                        app.getString(R.string.video_import_failed), e);
            }
            return null;
        }

        return attach(app, prefs, api, id, names, parts, localColour, listener, finisher);
    }

    /**
     * Take finished prompts' outputs, put the clips on disk and hand the built row to
     * a finisher. THE ONE IMPLEMENTATION of the tail of an import, shared with the
     * resume pass so the two cannot drift -- a resume that fetched slightly
     * differently from an import would be a second way for a clip to be wrong.
     *
     * <p><b>Chunks are joined by SAMPLE COPY, never by re-encode.</b> The parts come
     * from the same graph settings, so their codec configuration is the same; each
     * part's samples are appended to one muxer timeline at the sample rate the parts
     * themselves carry. The codec configuration is VERIFIED first, because appending
     * a stream whose SPS/PPS differ produces a file that plays wrongly rather than
     * failing, and "plays wrongly" is the failure this project keeps paying for.
     *
     * @param parts       one per chunk, in range order, each with its outputs
     * @param localColour the app's own colour clip, or null to take the colour the
     *                    server wrote (a failed-remux run, or a migrated v1 job)
     */
    private static LibraryItem attach(final Context app, final SharedPreferences prefs,
                                      final ComfyApi api, final String rowId,
                                      final String[] names, final List<Part> parts,
                                      final File localColour, final Listener listener,
                                      final Finisher finisher) {
        if (parts.isEmpty()) {
            fail(prefs, null, listener,
                    app.getString(R.string.video_import_failed) + " (no prompts were recorded)",
                    null);
            return null;
        }
        phase(rowId, listener, app.getString(R.string.video_phase_fetching));
        final File dir = app.getFilesDir();

        // Fetch the depth and normal parts. A chunk that came back without its depth
        // is reported with the SERVER's own reason: the history entry carries the
        // exception, and a bare "could not be imported" is what the user got for a
        // CUDA out-of-memory, which says nothing about what to do next. The job is
        // recorded FAILED with that reason rather than left looking live: the sheet
        // and the tile's corner have to carry the failure until the user acts, and a
        // completed run with incomplete outputs is exactly the state this record is
        // for. Regenerate re-runs it.
        final int k = parts.size();
        File[] depthParts = new File[k];
        File[] normalParts = new File[k];
        boolean haveNormal = true;
        for (int i = 0; i < k; i++) {
            Part part = parts.get(i);
            JSONObject depthOut = findOutput(part.outputs, ComfyWorkflow.DEPTH_CLIP_PREFIX);
            JSONObject normalOut = findOutput(part.outputs, ComfyWorkflow.NORMAL_CLIP_PREFIX);
            if (depthOut == null) {
                String why = serverReason(api, part.promptId);
                String reason = why != null ? why : "the run produced no depth clip";
                Log.w(TAG, "attach: " + part.promptId + " produced no depth clip: " + why);
                JobsStore.fail(prefs, part.promptId, reason);
                listener.failed(app.getString(R.string.video_import_failed)
                        + ": " + reason);
                return null;
            }
            try {
                depthParts[i] = new File(dir, DEPTH_PART + i + ".mp4");
                write(api.view(depthOut.getString("filename"),
                        depthOut.optString("subfolder", ""),
                        depthOut.optString("type", "output")), depthParts[i]);
                if (normalOut != null) {
                    normalParts[i] = new File(dir, NORMAL_PART + i + ".mp4");
                    write(api.view(normalOut.getString("filename"),
                            normalOut.optString("subfolder", ""),
                            normalOut.optString("type", "output")), normalParts[i]);
                } else {
                    // One chunk with no normal is a flat-lit yes or no for the WHOLE
                    // clip, not a mixed state: a partial normal track would shade
                    // some frames and not others. The state is reported, not worked
                    // around.
                    haveNormal = false;
                }
            } catch (Exception e) {
                fail(prefs, parts.get(0).promptId, listener,
                        app.getString(R.string.video_import_failed), e);
                return null;
            }
        }

        File depth;
        File normal = null;
        try {
            depth = joinClips(depthParts, new File(dir, names[1]), "depth");
            if (haveNormal) {
                normal = joinClips(normalParts, new File(dir, names[2]), "normal");
            }
        } catch (Exception e) {
            fail(prefs, parts.get(0).promptId, listener,
                    app.getString(R.string.video_import_failed), e);
            return null;
        }
        // The part files are transient by construction -- their frames are in the
        // joined clip now -- and leaving one per chunk per import is garbage that
        // grows with the feature. The single-part case renamed its part away, so
        // those deletes are no-ops.
        for (int i = 0; i < k; i++) {
            deleteQuietly(depthParts[i]);
            deleteQuietly(normalParts[i]);
        }

        // The colour: the app's own file, already at names[0] for an import; a copy
        // for a regenerate; fetched from the prompt for the one shape whose graph
        // wrote it (a failed remux, or a v1 record). Never re-encoded on the device.
        File colourTarget = new File(dir, names[0]);
        try {
            if (localColour == null) {
                JSONObject colourOut = findOutput(parts.get(0).outputs,
                        ComfyWorkflow.COLOUR_CLIP_PREFIX);
                if (colourOut == null) {
                    String why = serverReason(api, parts.get(0).promptId);
                    String reason = why != null ? why : "the run produced no colour clip";
                    Log.w(TAG, "attach: " + parts.get(0).promptId
                            + " produced no colour clip: " + why);
                    JobsStore.fail(prefs, parts.get(0).promptId, reason);
                    listener.failed(app.getString(R.string.video_import_failed)
                            + ": " + reason);
                    return null;
                }
                write(api.view(colourOut.getString("filename"),
                        colourOut.optString("subfolder", ""),
                        colourOut.optString("type", "output")), colourTarget);
            } else if (!samePath(localColour, colourTarget)) {
                copyFile(localColour, colourTarget);
            }
        } catch (Exception e) {
            fail(prefs, parts.get(0).promptId, listener,
                    app.getString(R.string.video_import_failed), e);
            return null;
        }

        phase(rowId, listener, app.getString(R.string.video_phase_poster));
        File poster, depthStill, normalStill = null;
        try {
            poster = firstFrameAsPng(app, colourTarget, names[3]);
            depthStill = firstFrameAsPng(app, depth, names[4]);
            if (normal != null) {
                normalStill = firstFrameAsPng(app, normal, names[5]);
            }
        } catch (Exception e) {
            fail(prefs, parts.get(0).promptId, listener,
                    app.getString(R.string.video_import_failed), e);
            return null;
        }

        // The job is DONE and its outputs are on disk: the record has served its
        // purpose and must not make the next start ask about a finished prompt. Any
        // one of the job's prompts clears the whole job (Jobs.afterRemoving), and a
        // record superseded by a newer submission for the same row names different
        // prompts, so it survives.
        JobsStore.clear(prefs, parts.get(0).promptId);
        phase(rowId, listener, app.getString(R.string.video_phase_adding));
        VideoTracks tracks;
        try {
            tracks = VideoTracks.of(names[0], names[1], normal == null ? null : names[2]);
        } catch (Exception e) {
            fail(prefs, parts.get(0).promptId, listener,
                    app.getString(R.string.video_import_failed), e);
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

        // THE RANGE IS THE UPLOAD, AND IT IS THE COLOUR. The samples of the chosen
        // range are copied into their own container, so a 126 MB source uploads a
        // few megabytes -- and the copy is kept under the row's colour name, which
        // is both what the renderer plays and what a chunk plan partitions. A failed
        // remux falls back to the old single-prompt shape over the source, with the
        // server writing the colour, because that is the one shape with no local
        // range to use.
        phase(id, listener, app.getString(R.string.video_phase_copying));
        final long startUs = (long) (startFrame / Math.max(1f, fps) * 1_000_000f);
        // frames 0 = the whole clip, and then there is no end time to cap: the
        // copy runs until the samples do.
        final long durUs = frames > 0
                ? (long) (frames / Math.max(1f, fps) * 1_000_000f)
                : Long.MAX_VALUE / 2;
        final File colour = new File(app.getFilesDir(), names[0]);
        File made = remuxRange(app, uri, colour, startUs, durUs);
        final File upload;
        final File localColour;
        final int graphStart;
        if (made != null) {
            Log.i(TAG, "remux: " + made.length() + " bytes for " + frames + " frames");
            upload = made;
            localColour = made;
            graphStart = 0;
        } else {
            // A zero-length file under the colour name would make Regenerate treat
            // this row as having a colour clip when it does not.
            colour.delete();
            // "Use the whole clip" or a remux that failed: a URI has no File to
            // upload, so the source is copied to a STAGING name -- never the row's
            // colour name, which must not hold the untrimmed source.
            File staged = new File(app.getFilesDir(), "stage-import.mp4");
            try {
                copyTo(app, uri, staged);
            } catch (Exception e) {
                fail(prefs, null, listener, "the shared video could not be read", e);
                return;
            }
            if (staged.length() == 0) {
                fail(prefs, null, listener, "the shared video was empty", null);
                return;
            }
            upload = staged;
            localColour = null;
            graphStart = startFrame;
        }

        LibraryItem item = generate(app, prefs, api, id, upload, localColour, names,
                graphStart, frames, outHeight, listener,
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
            return executionError(history.getJSONObject(promptId));
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * The first {@code execution_error} message in a history entry, first line only,
     * or null when the run has not errored.
     *
     * <p><b>One extraction, three readers.</b> The in-flight poll, the incomplete
     * outputs path and the resume all need the same answer, and the failure this
     * project keeps paying for is a second implementation that drifts: a resume that
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

    /**
     * Wait for one prompt, then hand back its outputs.
     *
     * <p><b>The run must be FINISHED, not merely producing.</b> The history entry can
     * carry outputs before the run is over -- observed with the colour combine, which
     * finishes before MoGe -- and the old test returned on the first output it saw.
     * That let the in-flight poll call {@code attach()} mid-run, find no depth clip,
     * and tell the user the import had failed while it was still working. The status
     * is the only thing that knows the difference between running and finished; an
     * error is asked for FIRST, because an errored run reports {@code completed:
     * false} and would otherwise spin to the timeout.
     */
    private static JSONObject pollOutputs(ComfyApi api, String promptId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            JSONObject history = new JSONObject(api.getHistory(promptId));
            if (!history.has(promptId)) continue;
            JSONObject entry = history.getJSONObject(promptId);
            String why = failedReason(entry);
            if (why != null) throw new ServerFailure(why);
            JSONObject status = entry.optJSONObject("status");
            if (status != null && status.optBoolean("completed", false)) {
                JSONObject outputs = entry.optJSONObject("outputs");
                return outputs == null ? new JSONObject() : outputs;
            }
        }
        throw new Exception("the run did not finish in time");
    }

    /** The first output whose filename starts with {@code prefix}, or null. */
    private static JSONObject findOutput(JSONObject outputs, String prefix) {
        if (outputs == null) return null;
        Iterator<String> nodes = outputs.keys();
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
                if (name.startsWith(prefix)) return f;
            }
        }
        return null;
    }

    /**
     * Join chunk clips into one, by copying their samples — never re-encoding.
     *
     * <p>The parts are outputs of the same graph with the same settings, so the
     * expected case is that their codec configuration is byte-identical; that is
     * VERIFIED per part rather than assumed, because appending samples whose
     * SPS/PPS differ produces a file that plays wrongly instead of failing, and a
     * wrongly-playing depth clip is invisible until something is very wrong on the
     * screen. A mismatch is an error with the part's name in it.
     *
     * <p><b>The offset between parts is one frame of the parts' own spacing.</b> Each
     * part restarts its timestamps at whatever its own first sample was, so each is
     * rebased to zero and shifted to start after the previous part's highest
     * presentation time. <b>Highest, not last-written, and the gap is the SMALLEST
     * positive difference between the part's own timestamps.</b> Both are deliberate:
     * H.264 from the combiner may carry B-frames, so a part's samples are in decode
     * order and neither the last one written nor the delta between consecutive ones is
     * the frame duration — the smallest positive difference between any two of its
     * timestamps is, because every frame of a chunk shares one grid. A part with a
     * single frame (a legal remainder at a height where a chunk is a few frames) falls
     * back to the spacing the run has already shown; if no part can show one, the
     * declared frame rate; if the container declares neither, this fails loudly rather
     * than muxing frames on top of each other.
     */
    private static File joinClips(File[] parts, File out, String what) throws Exception {
        if (parts.length == 1) {
            // One chunk is already the clip: moved, not copied, when the filesystem
            // allows it.
            if (parts[0].renameTo(out)) return out;
            copyFile(parts[0], out);
            return out;
        }
        MediaExtractor probe = null;
        MediaMuxer mux = null;
        try {
            probe = new MediaExtractor();
            probe.setDataSource(parts[0].getPath());
            int firstTrack = videoTrackOf(probe);
            MediaFormat firstFormat = probe.getTrackFormat(firstTrack);

            // VERIFY, do not assume: every part must be the same stream.
            for (int i = 1; i < parts.length; i++) {
                MediaExtractor other = new MediaExtractor();
                try {
                    other.setDataSource(parts[i].getPath());
                    requireSameStream(firstFormat, other.getTrackFormat(videoTrackOf(other)),
                            parts[i]);
                } finally {
                    other.release();
                }
            }
            long declaredGapUs = declaredFrameUs(firstFormat);

            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = mux.addTrack(firstFormat);
            mux.start();

            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offsetUs = 0;
            long carriedGapUs = -1;
            for (int p = 0; p < parts.length; p++) {
                MediaExtractor ex = new MediaExtractor();
                List<Long> times = new ArrayList<Long>();
                try {
                    ex.setDataSource(parts[p].getPath());
                    ex.selectTrack(videoTrackOf(ex));
                    long baseUs = -1, maxWriteUs = -1;
                    while (true) {
                        long pts = ex.getSampleTime();
                        if (pts < 0) break;
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) break;
                        if (baseUs < 0) baseUs = pts;
                        times.add(pts - baseUs);
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = pts - baseUs + offsetUs;
                        info.flags = ex.getSampleFlags();
                        mux.writeSampleData(dst, buf, info);
                        maxWriteUs = Math.max(maxWriteUs, info.presentationTimeUs);
                        ex.advance();
                    }
                    if (maxWriteUs < 0) {
                        throw new Exception("a " + what + " chunk held no frames: "
                                + parts[p].getName());
                    }
                    long gapUs = smallestGapUs(times);
                    if (gapUs <= 0) gapUs = carriedGapUs;
                    if (gapUs <= 0) gapUs = declaredGapUs;
                    if (p < parts.length - 1 && gapUs <= 0) {
                        throw new Exception("the " + what + " chunks' frame spacing could "
                                + "not be read, so their timestamps cannot be joined");
                    }
                    if (gapUs > 0) carriedGapUs = gapUs;
                    offsetUs = maxWriteUs + Math.max(0, gapUs);
                } finally {
                    ex.release();
                }
            }
            mux.stop();
            return out;
        } finally {
            if (probe != null) {
                try { probe.release(); } catch (Throwable ignored) { }
            }
            if (mux != null) {
                try { mux.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * The smallest positive difference between any two timestamps, or -1.
     *
     * <p>That is the frame duration for frames on one grid, regardless of the order
     * the container hands them out: with B-frames the samples arrive in decode order,
     * so consecutive deltas jump around, while the sorted set still has every step as
     * a multiple of the frame duration.
     */
    private static long smallestGapUs(List<Long> times) {
        if (times.size() < 2) return -1;
        List<Long> sorted = new ArrayList<Long>(times);
        java.util.Collections.sort(sorted);
        long smallest = -1;
        for (int i = 1; i < sorted.size(); i++) {
            long d = sorted.get(i) - sorted.get(i - 1);
            if (d > 0 && (smallest < 0 || d < smallest)) smallest = d;
        }
        return smallest;
    }

    /**
     * Fail loudly when a part is not the same stream as the first.
     *
     * <p>The codec configuration ({@code csd-0}/{@code csd-1}) is what the samples
     * are decoded against; appending samples from a stream whose configuration
     * differs does not throw anywhere — the muxer only sees bytes — it produces a
     * file whose later frames decode wrongly. The file names are checked because
     * the message has to say WHICH chunk disagreed.
     */
    private static void requireSameStream(MediaFormat first, MediaFormat next, File part)
            throws Exception {
        boolean same = equalString(first.getString(MediaFormat.KEY_MIME),
                        next.getString(MediaFormat.KEY_MIME))
                && sameBytes(first.getByteBuffer("csd-0"), next.getByteBuffer("csd-0"))
                && sameBytes(first.getByteBuffer("csd-1"), next.getByteBuffer("csd-1"))
                && equalInt(first, next, MediaFormat.KEY_WIDTH)
                && equalInt(first, next, MediaFormat.KEY_HEIGHT);
        if (!same) {
            throw new Exception("the clips are not one stream: " + part.getName()
                    + " has a different codec configuration from the first chunk");
        }
    }

    /** The gap between two consecutive frames, from the format's declared rate, or -1. */
    private static long declaredFrameUs(MediaFormat fmt) {
        if (fmt == null || !fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) return -1;
        int fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE);
        return fps > 0 ? 1_000_000L / fps : -1;
    }

    private static int videoTrackOf(MediaExtractor ex) throws Exception {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        throw new Exception("no video track in " + ex);
    }

    private static boolean equalString(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean equalInt(MediaFormat a, MediaFormat b, String key) {
        boolean ha = a.containsKey(key), hb = b.containsKey(key);
        return ha && hb ? a.getInteger(key) == b.getInteger(key) : ha == hb;
    }

    /**
     * Byte-equality of two format buffers, treating "absent" as equal to "absent".
     *
     * <p>Absolute indexing rather than {@code equals}, because the buffers the
     * extractor hands out are views whose positions are not part of the value.
     */
    private static boolean sameBytes(ByteBuffer a, ByteBuffer b) {
        if (a == null || b == null) return a == b;
        if (a.remaining() != b.remaining()) return false;
        for (int i = a.position(); i < a.limit(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    /** Whether two files are the same path, so a copy would be onto itself. */
    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
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
     * The three facts the trim sheet needs from the source, read from the share.
     *
     * <p>One call rather than three at the call site, so the rate the poster time is
     * computed from and the rate the sheet displays cannot be read twice and differ.
     */
    static final class Meta {
        final float fps;
        final int frames;
        final int height;

        Meta(float fps, int frames, int height) {
            this.fps = fps;
            this.frames = frames;
            this.height = height;
        }
    }

    static Meta metaOf(Context app, Uri uri) {
        float fps = frameRateOf(app, uri);
        return new Meta(fps, frameCountOf(app, uri, fps), sourceHeightOf(app, uri));
    }

    /**
     * How many frames the container holds, exactly when the sample table says.
     *
     * <p><b>Exact sample count first, and that is a measured fix rather than
     * caution.</b> This used to be only {@code round(duration × fps)}, and on the
     * file this app remuxes itself a 240-frame 24fps range reports a duration of
     * 9.9586s: the MP4 muxer takes the duration from the last sample it processed,
     * which is not always the highest presentation time, so the estimate is
     * <b>239</b>. The chunk plan then asked the server for 239 frames while the
     * colour clip held 240, and the joined depth clip decoded a frame short --
     * one frame short, at the end, with no error anywhere. The sample count is one
     * sample per frame by construction, which is the same unit the server's loader
     * decodes.
     *
     * <p>The duration is only read when the sample table cannot be walked, and it
     * is read from {@link MediaMetadataRetriever}, NOT from MediaExtractor's track
     * format: measured, the extractor's {@code durationUs} is absent for the files
     * this app reads, so the count came back 0, the trim bar was built with a
     * one-frame clip, and the sheet sat at 0:00 with handles that could not move --
     * reported as "trim sheet stuck at 0.0". The retriever is the same source the
     * filmstrip already reads successfully, so the sheet cannot disagree with its own
     * thumbnails about how long the clip is.
     */
    static int frameCountOf(Context app, Uri clip, float fps) {
        int exact = sampleCountOf(app, clip);
        // The duration is a second parse of the container; skip it when the exact
        // answer already exists.
        long durationUs = exact > 0 ? 0L : durationUsOf(app, clip);
        return FrameCount.resolve(exact, durationUs, fps);
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
     * The video track's own height, for the "Original" resolution chip and for the
     * batch-memory estimate.
     *
     * <p>The track's declared height is what the loader keeps when
     * {@code custom_height} is 0. Defaulted to 1080 when the container will not say
     * -- the common phone video height -- because the alternative is an unknown the
     * guard must refuse, and refusing an import over a missing metadata field would
     * be worse than one documented guess. It is a guess only in the "Original" case:
     * the 1080p and 720p chips name their own height.
     */
    static int sourceHeightOf(Context app, Uri clip) {
        android.media.MediaExtractor ex = null;
        try {
            ex = new android.media.MediaExtractor();
            ex.setDataSource(app, clip, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                if (fmt.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                    int h = fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT);
                    if (h > 0) return h;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "source height unreadable: " + t);
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignored) { }
            }
        }
        return 1080;
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
}
