package com.amoshydra.basrelief.core.store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The imports that are still running on the server, as a record on disk.
 *
 * <p><b>This exists because "you can leave — it keeps going" was a lie.</b> The trim
 * sheet's Job copy says the work survives leaving the app, and it does — the server
 * runs the node whether or not anyone is watching. What did NOT survive was the
 * <i>prompt id</i>, which lived in a worker thread's memory: an app restart (an
 * install, an OOM kill, a swipe away) lost the only handle to a finished result
 * sitting in the server's output folder. The work was done and thrown away by a
 * process restart, which is the worst of both worlds — paid for, and unreachable.
 *
 * <p><b>A record, not a queue.</b> Nothing here schedules anything; a job is a
 * prompt the app has submitted and not yet fetched. The studio reads it at start,
 * asks the server about the run — the streaming node's sidecar first, because it is
 * the run's own state — and either finishes the import, keeps the record (still
 * running, or unreachable), keeps it FAILED, or re-submits from frame 0 when the
 * server has no trace of the prompt left. A failure is reported with the server's
 * own words and stays on the sheet until the user acts, because a failure that
 * exists only in a toast cannot be read.
 *
 * <p><b>A job is ONE prompt again.</b> v2 and v3 recorded a chunk list because a
 * long range was submitted as N prompts; the streaming node takes the whole range
 * in one prompt and owns the loop internally, so the list collapsed back to the
 * single handle it started as. <b>The count is not stored here.</b> A running run's
 * "frame 137 of 480" comes from the node's sidecar file, read live and carried in
 * memory for the sheet; a persisted number would be a second, staler answer to the
 * same question. The v4 schema is therefore item, host, prompt and (when failed)
 * the server's message — nothing a reader has to interpret.
 *
 * <p><b>Parsing never throws and never invents.</b> The same rule as {@link Library}
 * and {@link Playlists}: this is read on the path to drawing, a corrupt blob yields
 * an empty set rather than an exception, and a job naming a photo that no longer
 * exists is dropped by the caller rather than resurrected here — this class cannot
 * see the library and must not pretend to.
 */
public final class Jobs {

    /** The SharedPreferences key this blob lives under. */
    public static final String KEY = "jobs";

    /**
     * The schema version, so a future field can migrate rather than guess.
     *
     * <p>v1 was one prompt for the whole range. v2 was a chunk list, because a
     * chunked import submitted N prompts for one item. v3 added a load height, a
     * completed-chunk count and the server's failure message. v4 returns to ONE
     * prompt, because the streaming node takes the whole range in one prompt and
     * owns the loop; the count moved to the node's sidecar, and the height had no
     * reader once the sheet stopped stating a per-chunk memory figure. A v1, v2 or
     * v3 blob migrates by keeping its first prompt — the handle the old run answers
     * to — and its failure message, if any.
     */
    public static final int VERSION = 4;

    /** One submitted import: the photo it belongs to, and the server's handle. */
    public static final class Job {
        public final String itemId;
        /** The server's prompt id. Never empty on a usable job. */
        public final String promptId;
        public final String host;
        public final long startedAtMs;
        /**
         * The server's own failure message, or null while the job is still viable.
         *
         * <p><b>A failure is kept, not cleared.</b> It can arrive minutes after
         * the share, and the sheet and the tile's corner have to say so until the
         * user acts — a toast is gone before it is read. The record stays until
         * Regenerate or a delete removes it, and the resume pass skips a failed
         * job because re-asking a run that already errored cannot change it.
         */
        public final String error;

        public Job(String itemId, String promptId, String host, long startedAtMs) {
            this(itemId, promptId, host, startedAtMs, null);
        }

        /** The full shape, for a run that has a failure to carry. */
        public Job(String itemId, String promptId, String host, long startedAtMs,
                   String error) {
            this.itemId = itemId == null ? "" : itemId;
            this.promptId = promptId == null ? "" : promptId;
            this.host = host == null ? "" : host;
            this.startedAtMs = startedAtMs;
            this.error = error == null || error.isEmpty() ? null : error;
        }

        /** The same job, failed, carrying the server's own words. */
        public Job withError(String message) {
            return new Job(itemId, promptId, host, startedAtMs, message);
        }

        /** Whether the run this record names has stopped and needs the user. */
        public boolean isFailed() {
            return error != null;
        }

        /** Whether this record is worth asking the server about. */
        public boolean isUsable() {
            return !itemId.isEmpty() && !host.isEmpty() && !promptId.isEmpty();
        }

        @Override
        public String toString() {
            return "Job{" + itemId + " @ " + promptId + " on " + host + "}";
        }
    }

    private final List<Job> jobs;

    private Jobs(List<Job> jobs) {
        this.jobs = jobs;
    }

    public static Jobs empty() {
        return new Jobs(new ArrayList<Job>());
    }

    /** Every pending job, oldest first, so a resume pass asks in submission order. */
    public List<Job> pending() {
        List<Job> out = new ArrayList<Job>(jobs);
        Collections.sort(out, new java.util.Comparator<Job>() {
            @Override
            public int compare(Job a, Job b) {
                return Long.compare(a.startedAtMs, b.startedAtMs);
            }
        });
        return out;
    }

    /**
     * The record for one row, or null. "The" record is well defined because
     * {@link #with} keeps one entry per item — see its own note on why a second
     * submission supersedes rather than accompanies.
     */
    public Job forItem(String itemId) {
        if (itemId == null) return null;
        for (Job j : jobs) {
            if (itemId.equals(j.itemId)) return j;
        }
        return null;
    }

    /**
     * The colour file name a row may PREVIEW while its depth import runs, or null.
     *
     * <p><b>The one derivation of "is this row a preview right now", and the
     * precedence rule lives here rather than at the two call sites.</b> A preview
     * is exactly: the row has no clips of its own, and a job record for it exists.
     * A recorded run whose colour clip is not on disk is not a preview, but that
     * half is a FILE question — {@code :core} cannot stat, so the app layer resolves
     * the name and checks existence ({@code JobsStore.previewColour}), and the rule
     * here stays pure arithmetic over the index and the record.
     *
     * <p><b>{@code item.video != null} WINS over a lingering record, and it is a
     * rule rather than an artifact of statement order.</b> The record is cleared at
     * the END of an import and the row's tracks are written around the same moment;
     * any resolve that lands in that window would, under the other precedence,
     * restart a clip that has real depth and real shading back at frame 0 as a
     * colour-only preview — and "a video that rewinds is a photograph" is the
     * reported defect this whole feature cannot afford to reintroduce. The window is
     * also not confined to one function: a REGENERATE runs the graph against a row
     * that already has tracks, so its record and its clips coexist for the whole
     * run, and the running clip must simply keep playing.
     *
     * <p><b>A FAILED record still previews.</b> The failure is kept on purpose so
     * the sheet line and the tile's corner can show the server's words until the
     * user acts; the colour clip is still the user's video and still plays. Stopping
     * it because the depth run failed would take the moving picture away at the exact
     * moment there is nothing else to look at.
     *
     * <p>The name is {@link LibraryItem#videoFileName} because that is where the
     * import's local remux is written BEFORE anything is submitted — the range is
     * the upload, and it is also the colour the finished clip will name.
     */
    public String previewColourName(LibraryItem item) {
        if (item == null || item.video != null) return null;
        if (forItem(item.id) == null) return null;
        return LibraryItem.videoFileName(item.id);
    }

    /**
     * The same set with this job added, replacing any earlier job for the same item.
     *
     * <p><b>Keyed by ITEM, not by prompt.</b> One photo has one import in flight: a
     * second submission for the same row supersedes the first — which is also what a
     * resume's re-submit does — and keeping both would leave two fetches racing to
     * write the same filenames. The old prompt keeps running on the server if the
     * old run is still alive; the sidecar check in {@code VideoImport.resume} is
     * what stops a re-submit from racing it.
     */
    public Jobs with(Job job) {
        if (job == null || !job.isUsable()) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            if (!j.itemId.equals(job.itemId)) next.add(j);
        }
        next.add(job);
        return new Jobs(next);
    }

    /**
     * The same set with the job whose prompt is {@code promptId} marked failed,
     * carrying {@code message}.
     *
     * <p>The record is KEPT, not cleared: the failure is a state the sheet and the
     * tile have to show until the user acts, and a cleared record is a failure
     * that exists only in a toast.
     */
    public Jobs withError(String promptId, String message) {
        if (promptId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            next.add(promptId.equals(j.promptId) ? j.withError(message) : j);
        }
        return new Jobs(next);
    }

    /**
     * The same set without the job whose prompt is {@code promptId}.
     *
     * <p><b>Scoped to the PROMPT, not the item, and that is load-bearing.</b> A job
     * that has been SUPERSEDED (the record now holds a newer submission for the same
     * item, with a different prompt) must not be cleared by the old run finishing —
     * which is exactly the state a resume's re-submit leaves behind.
     */
    public Jobs afterRemoving(String promptId) {
        if (promptId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            if (!promptId.equals(j.promptId)) next.add(j);
        }
        return new Jobs(next);
    }

    /** The same set without every job for one item, for a delete of that photo. */
    public Jobs afterRemovingItem(String itemId) {
        if (itemId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            if (!itemId.equals(j.itemId)) next.add(j);
        }
        return new Jobs(next);
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", VERSION);
            JSONArray arr = new JSONArray();
            for (Job j : jobs) {
                JSONObject e = new JSONObject();
                e.put("item", j.itemId);
                e.put("host", j.host);
                e.put("started", j.startedAtMs);
                e.put("prompt", j.promptId);
                // An absent error IS "still running", which is the honest default
                // for a reader that does not know this version.
                if (j.error != null) e.put("error", j.error);
                arr.put(e);
            }
            o.put("jobs", arr);
        } catch (Exception ignored) {
            // A put on these types cannot fail; if it somehow does, an empty record
            // is recoverable and a throw on the write path is not.
        }
        return o.toString();
    }

    /**
     * Parse the blob. Never throws: a corrupt or future blob is an empty set.
     *
     * <p>A version NEWER than this class understands is treated as empty rather than
     * guessed at, because the alternative is reading fields with meanings this code
     * does not know — the same rule {@code Library} follows.
     *
     * <p><b>An older record MIGRATES rather than being dropped.</b> A v1, v2 or v3
     * blob is a legitimate in-flight run — the server may still have it, and
     * dropping the record would strand its result in the output folder, which is
     * the exact loss this class was written to end. v1 already holds one prompt;
     * v2 and v3 hold a chunk list, and the FIRST prompt is the handle the old run
     * answers to. The failure message survives every version, because a failed job
     * has to keep failing until the user acts.
     */
    public static Jobs parse(String json) {
        if (json == null || json.isEmpty()) return empty();
        try {
            JSONObject o = new JSONObject(json);
            int version = o.optInt("version", 0);
            if (version > VERSION) return empty();
            JSONArray arr = o.optJSONArray("jobs");
            if (arr == null) return empty();
            List<Job> out = new ArrayList<Job>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String item = e.optString("item", "");
                String host = e.optString("host", "");
                long started = e.optLong("started", 0L);
                String error = e.optString("error", null);
                // v4 is one prompt under its own key; v2/v3 recorded a chunk list,
                // and the first usable prompt is the handle the old run answers to.
                // The frame ranges and the two counts are deliberately ignored: the
                // new resume asks the sidecar, and a v3 "completed chunks" number
                // read as frames would be a lie.
                String prompt;
                if (version >= 4) {
                    prompt = e.optString("prompt", "");
                } else if (version >= 2) {
                    prompt = firstChunkPrompt(e.optJSONArray("chunks"));
                } else {
                    prompt = e.optString("prompt", "");
                }
                Job j = new Job(item, prompt, host, started, error);
                // An unusable entry is dropped here rather than carried and skipped by
                // every reader: a record nothing can act on is not a record.
                if (j.isUsable()) out.add(j);
            }
            return new Jobs(out);
        } catch (Throwable t) {
            return empty();
        }
    }

    /** The first non-empty prompt in a v2/v3 chunk list, or "". */
    private static String firstChunkPrompt(JSONArray chunks) {
        if (chunks == null) return "";
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject c = chunks.optJSONObject(i);
            if (c == null) continue;
            String p = c.optString("prompt", "");
            if (!p.isEmpty()) return p;
        }
        return "";
    }
}
