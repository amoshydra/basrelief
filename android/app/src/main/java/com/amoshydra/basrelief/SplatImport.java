package com.amoshydra.basrelief;

import android.content.Context;
import android.content.SharedPreferences;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.render.SplatSidecar;
import com.amoshydra.basrelief.core.store.Jobs;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "Make 3D" flow: one still → one budgeted Gaussian splat cloud.
 *
 * <p><b>The app half of Stage 2, and deliberately the shape {@link VideoImport}
 * already proved.</b> Upload the still through the same {@code /upload/image} path,
 * submit one prompt, write a {@link Jobs} record the moment the server accepts,
 * poll the run's own sidecar and {@code /history}, fetch the cloud by the filename
 * the tag derives, rename it into the item's per-id scheme, and publish the index.
 * The record is what survives a restart: the server runs the node whether or not
 * anyone is watching, and without the prompt id the finished cloud would sit in the
 * output folder unreachable — the loss {@code Jobs} exists to end.
 *
 * <p><b>Stills only.</b> A clip needs the streaming node plus the fusion, and its
 * dynamic layer's size is Stage 3's problem. So the upload is the item's poster
 * still ({@code imageName}), and a clip row can still get a cloud of its poster.
 *
 * <p><b>The cloud is a filename on the row, and the mode is the row's own.</b> This
 * writes {@code splatName} through {@link LibraryStore#editItem}, which publishes
 * the new index in memory the moment it is mutated — so the wallpaper sees it
 * immediately — and then the caller patches the strip with
 * {@code Gallery.itemUpdated} rather than reindexing (a rebuild re-decodes every
 * thumbnail and is not allowed).
 */
final class SplatImport {

    private static final String TAG = "Basrelief";

    /** What THIS PROCESS knows about one row's in-flight cloud build. */
    static final class Live {
        /** The last phase message, for the row's line. */
        volatile String phase = "";
    }

    private static final Map<String, Live> LIVE = new ConcurrentHashMap<String, Live>();

    /** Whether a cloud build for this row is running in this process. */
    static boolean isRunning(String itemId) {
        return itemId != null && LIVE.containsKey(itemId);
    }

    /**
     * Whether <b>any</b> cloud build is running in this process, for the collapsed
     * pill.
     *
     * <p>Process-wide, exactly like {@code BuildDialog.isRunning()} for a depth run:
     * the pill is the sheet's away-state and has no item of its own, so a build for
     * a row the user has since scrolled away from is still the thing they started
     * and still the thing worth showing. {@link #isRunning} stays the per-row
     * question the sheet's shared asset row and the dialog's Splat tab ask.
     */
    static boolean anyRunning() {
        return !LIVE.isEmpty();
    }

    /** The live phase of this row's build, or null when nothing is running. */
    static Live live(String itemId) {
        return itemId == null ? null : LIVE.get(itemId);
    }

    private static void begin(String itemId) {
        if (itemId != null && !itemId.isEmpty()) LIVE.put(itemId, new Live());
    }

    private static void end(String itemId) {
        if (itemId != null) LIVE.remove(itemId);
    }

    private static void phase(String itemId, Listener listener, String message) {
        Live l = LIVE.get(itemId);
        if (l != null) l.phase = message == null ? "" : message;
        listener.phase(message);
    }

    /** A run the server itself refused, carrying its words. */
    private static final class ServerFailure extends Exception {
        final String reason;

        ServerFailure(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** How often the sidecar and history are asked, and how long one run may take. */
    private static final long POLL_MS = 1000L;
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    /** What the caller is told, so a sheet can show something honest. */
    interface Listener {
        void phase(String message);
        void done(LibraryItem item);
        void failed(String message);
    }

    private SplatImport() {
    }

    /**
     * Build a cloud for one row, off the UI thread.
     *
     * @param item the row to reconstruct. Must name a still.
     */
    static void run(Context app, SharedPreferences prefs, LibraryItem item,
                    ComfyApi api, Listener listener) {
        if (item == null) {
            listener.failed("make 3D: no item");
            return;
        }
        begin(item.id);
        try {
            LibraryItem updated = generate(app, prefs, api, item, listener);
            if (updated != null) listener.done(updated);
        } finally {
            end(item.id);
        }
    }

    /**
     * Finish a build a restart interrupted, from its job record.
     *
     * <p>The same shape as {@link VideoImport#resume}, and the same answers: the
     * photo is gone → clear; the server is unreachable → keep; the sidecar says
     * done → fetch and attach; running → keep and adopt; failed → record the
     * server's words and keep. A record with no trace of the run on the server is
     * re-submitted from the still, because a cloud is written whole at the end.
     */
    static void resume(Context app, SharedPreferences prefs, Jobs.Job job, Listener listener) {
        final LibraryItem item = LibraryStore.load(prefs).find(job.itemId);
        if (item == null) {
            JobsStore.clearItem(prefs, job.itemId);
            return;
        }
        if (job.isFailed()) {
            // A failure is a state, not work to re-ask; the sheet already shows it.
            return;
        }
        final ComfyApi api = new ComfyApi(job.host);
        begin(item.id);
        try {
            // THE HISTORY FOR THE STORED PROMPT ID, not the sidecar. The sidecar is a
            // fixed filename any run may have left behind, so a resume that read it
            // could see a previous run's `done` and fetch the wrong cloud -- or a
            // previous run's `failed` and refuse to touch a job that is fine. The job
            // record holds the ONE PROMPT this build submitted, and the history is
            // keyed by exactly that, so it is the only question whose answer belongs
            // to this job.
            JSONObject entry = null;
            try {
                JSONObject hist = new JSONObject(api.getHistory(job.promptId));
                entry = hist.optJSONObject(job.promptId);
            } catch (Exception e) {
                // Unreachable server: a sleeping laptop is not a lost build.
                return;
            }
            if (entry == null) {
                // The server has no trace of THIS prompt: re-submit from the still.
                LibraryItem updated = generate(app, prefs, api, item, listener);
                if (updated != null) listener.done(updated);
                return;
            }
            JSONObject status = entry.optJSONObject("status");
            if (status != null && status.optBoolean("completed", false)) {
                LibraryItem updated = fetch(app, prefs, api, job, item, listener);
                if (updated != null) listener.done(updated);
                return;
            }
            if (status != null && "error".equals(status.optString("status_str", ""))) {
                String why = ComfyWorkflow.extractExecutionError(status);
                JobsStore.fail(prefs, job.promptId, why);
                listener.failed(why);
                return;
            }
            // Queued or running: keep the record; the node is doing the work.
            // Re-submitting here would run it twice against the same output names.
        } catch (Exception e) {
            android.util.Log.w(TAG, "splat resume: " + e);
        } finally {
            end(item.id);
        }
    }

    /** Upload, submit, record, poll, fetch. Returns the built row or null. */
    private static LibraryItem generate(Context app, SharedPreferences prefs, ComfyApi api,
                                        LibraryItem item, Listener listener) {
        final String id = item.id;
        final String tag = ComfyWorkflow.streamTag(id);
        if (!ComfyWorkflow.isStreamTagUsable(tag)) {
            listener.failed(app.getString(R.string.splat_failed)
                    + ": the row's id cannot name a server run");
            return null;
        }
        File still = PrefFiles.resolve(app.getFilesDir(), item.imageName);
        if (still == null || !still.isFile()) {
            listener.failed(app.getString(R.string.splat_failed)
                    + ": the photo's file is gone");
            return null;
        }

        phase(id, listener, app.getString(R.string.splat_phase_uploading));
        String uploadedName;
        try {
            JSONObject up = new JSONObject(api.uploadImage(still, still.getName()));
            uploadedName = up.getString("name");
        } catch (Exception e) {
            listener.failed(app.getString(R.string.splat_failed) + ": " + e.getMessage());
            return null;
        }

        final long startedAtMs = System.currentTimeMillis();
        String promptId = null;
        try {
            String info = api.getObjectInfo();
            if (!ComfyWorkflow.hasSharpStill(new JSONObject(info))) {
                listener.failed(app.getString(R.string.splat_no_node));
                return null;
            }
            // Best effort, like the clip path: a server holding a previous model's
            // cache has less room for SHARP's own weights, and a server without the
            // route must not stop the build.
            try {
                api.freeMemory();
            } catch (Exception ignored) {
            }
            // The budget is the dev BUDGET knob's value, read here at submission
            // rather than at draw: it is a generation-side number, and the point of
            // the ladder is to regenerate one item at a different one and look at
            // it. Clamped in :core, so a pref that cannot claim a count runs the
            // shipped default rather than the node's own 999999 fallback.
            int budget = ComfyWorkflow.SHARP_BUDGET;
            try {
                budget = ComfyWorkflow.clampSharpBudget(
                        prefs.getInt(ReliefRenderer.KEY_SPLAT_BUDGET,
                                ComfyWorkflow.SHARP_BUDGET));
            } catch (Exception ignored) {
                // A pref of the wrong type is the default, not a crash.
            }
            android.util.Log.i(TAG, "splat: budget=" + budget + " for " + id);
            String graph = ComfyWorkflow.sharpStillWorkflow(new JSONObject(info), uploadedName,
                    tag, item.focal, budget);
            JSONObject submitted = new JSONObject(api.submitPrompt(graph, "basrelief-splat-" + id));
            promptId = submitted.getString("prompt_id");
            // THE RECORD, written once the server has accepted the prompt, and
            // carrying the KIND so a resume fetches a cloud rather than depth clips.
            JobsStore.record(prefs, new Jobs.Job(id, promptId, api.host(), startedAtMs,
                    Jobs.Kind.SPLAT));
        } catch (Exception e) {
            fail(prefs, promptId, listener, app.getString(R.string.splat_failed), e);
            return null;
        }

        try {
            phase(id, listener, app.getString(R.string.splat_phase_generating));
            poll(api, promptId);
            phase(id, listener, app.getString(R.string.splat_phase_fetching));
            return fetch(app, prefs, api,
                    new Jobs.Job(id, promptId, api.host(), startedAtMs, Jobs.Kind.SPLAT),
                    item, listener);
        } catch (Exception e) {
            if (e instanceof ServerFailure) {
                String why = ((ServerFailure) e).reason;
                android.util.Log.w(TAG, "splat: the server reported: " + why);
                JobsStore.fail(prefs, promptId, why);
                listener.failed(app.getString(R.string.splat_failed) + ": " + why);
            } else {
                fail(prefs, promptId, listener, app.getString(R.string.splat_failed), e);
            }
            return null;
        }
    }

    /** Ask the history for THIS prompt until the run is done or has failed. */
    private static void poll(ComfyApi api, String promptId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int misses = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                // THE HISTORY FOR THIS PROMPT IS THE ONLY THING ASKED, because it is
                // the only thing that can answer for THIS run. The sidecar is a FIXED
                // filename -- basrelief_<tag>.progress.json -- that the PREVIOUS run
                // leaves behind saying done, so asking it first returned on the poll's
                // very first iteration and every build completed instantly with the
                // build before it: a 703k build produced an 806k cloud, and a rebuild
                // looked instantaneous because nothing ever waited. Its `failed` was
                // the worse half -- a stale failure would abort a healthy run with the
                // last one's words. The prompt id is the job this app created, so this
                // is the question it can answer for itself rather than one any run may
                // answer for it.
                JSONObject entry = null;
                try {
                    JSONObject hist = new JSONObject(api.getHistory(promptId));
                    entry = hist.optJSONObject(promptId);
                } catch (Exception ignored) {
                    // A blip. An unasked history decides NOTHING -- neither done nor
                    // failed -- and `misses` below is what turns a run of them into a
                    // failure, so one dropped poll cannot abort a live run.
                }
                if (entry != null) {
                    JSONObject status = entry.optJSONObject("status");
                    if (status != null) {
                        // `completed` is the one thing that says the run is over.
                        // Outputs alone do NOT: a running entry can already carry them,
                        // and treating that as done is the bug the clip import's status
                        // read exists to prevent.
                        if (status.optBoolean("completed", false)) return;
                        String s = status.optString("status_str", "");
                        if ("error".equals(s)) {
                            throw new ServerFailure(ComfyWorkflow.extractExecutionError(status));
                        }
                    }
                }
                // An absent entry is not a verdict either: the server registers the
                // prompt when it starts it, and a run queued behind another has none
                // yet. The deadline is what bounds that wait.
                misses = 0;
            } catch (ServerFailure e) {
                throw e;
            } catch (Exception e) {
                // A blip must not mark a live run FAILED: a FAILED record is one the
                // resume pass skips, so one dropped poll would strand a result the
                // server went on to finish. Five in a row is a phone-side failure --
                // the same tolerance the clip import uses.
                if (++misses >= 5) {
                    throw new ServerFailure("the server is unreachable: " + e.getMessage());
                }
            }
            Thread.sleep(POLL_MS);
        }
        throw new ServerFailure("the run did not finish in time");
    }

    /**
     * Fetch the cloud, rename it into the item's per-id scheme, and publish the row.
     *
     * <p>THE ONE finish line, shared with the resume so the two cannot drift. The
     * cloud is written to a {@code .part} name and renamed into place, so a fetch
     * interrupted halfway leaves no half-file that the renderer would try to read as
     * a cloud — a truncated cloud is not a degraded one, it is a wrong one.
     */
    private static LibraryItem fetch(Context app, SharedPreferences prefs, ComfyApi api,
                                     Jobs.Job job, LibraryItem item, Listener listener)
            throws Exception {
        final String tag = ComfyWorkflow.streamTag(item.id);
        final File dir = app.getFilesDir();
        final File target = new File(dir, LibraryItem.splatFileName(item.id));
        final File part = new File(dir, LibraryItem.splatFileName(item.id) + ".part");
        byte[] bytes = api.view(ComfyWorkflow.sharpSplatName(tag), "", "output");
        FileOutputStream out = new FileOutputStream(part);
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new java.io.IOException("the cloud would not move into place");
        }
        // The record has served its purpose: cleared by THIS prompt id, so a
        // superseded run finishing cannot clear the record of the submission that
        // replaced it.
        JobsStore.clear(prefs, job.promptId);
        // THE LENS, from the run's own sidecar. The node measured a focal in pixels
        // of the frame it was handed and states that frame's width and height; the
        // ratio is the lens itself, so it survives the phone decoding the still at a
        // different size. A sidecar that cannot be read or that carries no focal is
        // "no lens" -- the cloud still loads and draws at the demo lens, exactly as
        // every cloud written before this field existed. SplatSidecar is in :core so
        // this resolution is tested against contract/sidecar/ rather than by eye.
        SplatSidecar side = null;
        try {
            byte[] sideBytes = api.viewIfPresent(
                    ComfyWorkflow.sharpSidecarName(tag), "", "output");
            side = sideBytes == null
                    ? null
                    : SplatSidecar.parse(new String(sideBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w(TAG, "splat: no lens from the sidecar: " + e);
        }
        // The row's own mode is already SPLAT (pressing Splat set it); this names the
        // cloud and stores the camera it was reconstructed through. A rebuild replaces
        // the name, so the renderer's path identity changes and the new file loads.
        final float cloudLens = side == null ? 0f : side.lens();
        LibraryItem updated = item.withSplat(target.getName(), cloudLens);
        LibraryStore.editItem(prefs, item.id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem it) {
                return it.withSplat(target.getName(), cloudLens);
            }
        });
        android.util.Log.i(TAG, "splat: " + item.id + " -> " + target.getName()
                + " " + (bytes.length / 32) + " splats"
                + (cloudLens > 0f
                        ? " lens=" + cloudLens + " (" + side.focal + "px/" + side.height
                                + " of " + side.width + "x" + side.height + ")"
                        : " lens=unknown"));
        return updated;
    }

    /** A phone-side failure, reported the same way as a server one. */
    private static void fail(SharedPreferences prefs, String promptId, Listener listener,
                             String detail, Throwable e) {
        String message = detail + (e == null || e.getMessage() == null ? "" : ": " + e.getMessage());
        if (promptId != null) JobsStore.fail(prefs, promptId, message);
        listener.failed(message);
    }
}
