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
 * runs the batch whether or not anyone is watching. What did NOT survive was the
 * <i>prompt id</i>, which lived in a worker thread's memory: an app restart (an
 * install, an OOM kill, a swipe away) lost the only handle to a finished result
 * sitting in the server's output folder. The work was done and thrown away by a
 * process restart, which is the worst of both worlds — paid for, and unreachable.
 *
 * <p><b>A record, not a queue.</b> Nothing here schedules anything; a job is a
 * prompt the app has submitted and not yet fetched. The studio reads it at start,
 * asks the server about each one, and either finishes the import, keeps the
 * record (still running, or unreachable), or keeps it FAILED — a run the server
 * refused is reported with the server's own words and stays on the sheet until
 * the user acts, because a failure that exists only in a toast is a failure that
 * cannot be read.
 *
 * <p><b>A job is a list of chunks, not one prompt.</b> A long range is submitted
 * as several prompts — one per batch the server can hold, see
 * {@code BatchBudget.chunkPlan} — and the record has to hold all of them or a
 * restart can only fetch that part of the range. The list is the ORDER the frames
 * come back in, so the fetch tail concatenates by position; the chunk's own
 * {@code startFrame}/{@code frames} are what says which part of the range it
 * covers, and they are what a later reader checks the list against.
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
     * <p>v1 was one prompt for the whole range. v2 is a chunk list, because a
     * chunked import submits N prompts for one item and one prompt id can no
     * longer name the work. v3 adds the three facts a RUNNING or FAILED run has
     * to show outside the worker that owns it: the load height its memory figure
     * is stated at, how many chunks the server has finished, and the server's own
     * failure message. A v2 blob parses as a run with no height, no progress and
     * no failure — which is what it is.
     */
    public static final int VERSION = 3;

    /** One submitted prompt: which frames it covers, and the server's handle. */
    public static final class Chunk {
        public final String promptId;
        public final int startFrame;
        public final int frames;

        public Chunk(String promptId, int startFrame, int frames) {
            this.promptId = promptId == null ? "" : promptId;
            this.startFrame = Math.max(0, startFrame);
            this.frames = Math.max(0, frames);
        }

        /**
         * Whether this chunk is worth asking the server about.
         *
         * <p>Only the prompt id: a migrated v1 chunk has no frame range recorded
         * (the old schema never stored one) and is still a legitimate in-flight
         * run. {@code frames == 0} therefore means "the whole range / not
         * recorded", exactly as it does on the loader.
         */
        public boolean isUsable() {
            return !promptId.isEmpty();
        }

        @Override
        public String toString() {
            return "Chunk{" + startFrame + "+" + frames + " @ " + promptId + "}";
        }
    }

    /** One submitted import: the photo it belongs to, and the server's handles. */
    public static final class Job {
        public final String itemId;
        /** The submitted chunks, in frame order. Never empty on a usable job. */
        public final List<Chunk> chunks;
        public final String host;
        public final long startedAtMs;
        /**
         * The height the chunks were planned at, for the job line's memory
         * figure, or 0 when nothing recorded one (a v2 blob, a migrated v1).
         *
         * <p>Stored rather than re-derived because the estimate is a function of
         * the height the SERVER loaded at, and the source's own height may have
         * been scaled by the resolution chip.
         */
        public final int height;
        /**
         * How many chunks the server has reported {@code completed}, in order.
         *
         * <p>The one number the job line counts with: "Generating 3 of 8" is
         * derived from this and the chunk list, so it survives the sheet being
         * rebuilt and cannot disagree with what was fetched.
         */
        public final int completed;
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

        public Job(String itemId, List<Chunk> chunks, String host, long startedAtMs) {
            this(itemId, chunks, host, startedAtMs, 0, 0, null);
        }

        /** The full shape, for a run that has progress or a failure to carry. */
        public Job(String itemId, List<Chunk> chunks, String host, long startedAtMs,
                   int height, int completed, String error) {
            this.itemId = itemId == null ? "" : itemId;
            List<Chunk> usable = new ArrayList<Chunk>();
            if (chunks != null) {
                for (Chunk c : chunks) {
                    // An unusable chunk is dropped here rather than carried and
                    // skipped by every reader: a handle nothing can act on is not
                    // a handle.
                    if (c != null && c.isUsable()) usable.add(c);
                }
            }
            this.chunks = Collections.unmodifiableList(usable);
            this.host = host == null ? "" : host;
            this.startedAtMs = startedAtMs;
            this.height = Math.max(0, height);
            this.completed = Math.max(0, Math.min(completed, usable.size()));
            this.error = error == null || error.isEmpty() ? null : error;
        }

        /** A one-prompt job, the shape v1 records migrate to. */
        public Job(String itemId, String promptId, String host, long startedAtMs) {
            this(itemId,
                    Collections.singletonList(new Chunk(promptId, 0, 0)),
                    host, startedAtMs);
        }

        /**
         * The same job with the load height its chunks were planned at.
         *
         * <p>Set once, when the record is written; the memory figure the job line
         * states is this height's, because that is the batch the server holds.
         */
        public Job withPlan(int planHeight) {
            return new Job(itemId, chunks, host, startedAtMs, planHeight, completed, error);
        }

        /** The same job with {@code done} chunks finished. Monotonic by clamp. */
        public Job withProgress(int done) {
            return new Job(itemId, chunks, host, startedAtMs, height, done, error);
        }

        /** The same job, failed, carrying the server's own words. */
        public Job withError(String message) {
            return new Job(itemId, chunks, host, startedAtMs, height, completed, message);
        }

        /** Whether the run this record names has stopped and needs the user. */
        public boolean isFailed() {
            return error != null;
        }

        /** Whether this record is worth asking the server about. */
        public boolean isUsable() {
            return !itemId.isEmpty() && !host.isEmpty() && !chunks.isEmpty();
        }

        /** Every prompt this job has submitted, in chunk order. */
        public List<String> prompts() {
            List<String> out = new ArrayList<String>();
            for (Chunk c : chunks) out.add(c.promptId);
            return out;
        }

        @Override
        public String toString() {
            return "Job{" + itemId + " x" + chunks.size() + " on " + host + "}";
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
     * The same set with this job added, replacing any earlier job for the same item.
     *
     * <p><b>Keyed by ITEM, not by prompt.</b> One photo has one import in flight: a
     * second submission for the same row supersedes the first, and keeping both would
     * leave two fetches racing to write the same filenames. The old prompts keep
     * running on the server, which costs nothing and can be ignored.
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
     * The same set with one item's job carrying {@code done} completed chunks.
     *
     * <p>Keyed by ITEM, like {@link #with}, because progress belongs to the run
     * and a job the server has not answered yet has no progress to record. The
     * count is clamped by {@link Job#withProgress}, so a late report cannot run
     * past the chunk list.
     */
    public Jobs withProgress(String itemId, int done) {
        if (itemId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            next.add(itemId.equals(j.itemId) ? j.withProgress(done) : j);
        }
        return new Jobs(next);
    }

    /**
     * The same set with the job one of whose chunks names {@code promptId} marked
     * failed, carrying {@code message}.
     *
     * <p>The record is KEPT, not cleared: the failure is a state the sheet and the
     * tile have to show until the user acts, and a cleared record is a failure
     * that exists only in a toast. Naming any chunk is enough, for the same reason
     * {@link #afterRemoving} takes any chunk — a chunked job's prompts are one
     * unit.
     */
    public Jobs withError(String promptId, String message) {
        if (promptId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            next.add(j.prompts().contains(promptId) ? j.withError(message) : j);
        }
        return new Jobs(next);
    }

    /**
     * The same set without any job one of whose chunks names {@code promptId}.
     *
     * <p><b>Any chunk, because the prompts are one unit.</b> A chunked job's
     * prompts were submitted together and are fetched together, so clearing the
     * job by naming any one of them is the only useful reading — and it is
     * deliberately scoped to a prompt rather than to the item, so a job that has
     * been SUPERSEDED (the record now holds a newer submission for the same
     * item, with different prompts) is not cleared by the old run finishing.
     */
    public Jobs afterRemoving(String promptId) {
        if (promptId == null) return this;
        List<Job> next = new ArrayList<Job>();
        for (Job j : jobs) {
            if (!j.prompts().contains(promptId)) next.add(j);
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
                // The three v3 facts. Written unconditionally except the error:
                // an absent error IS "still running", which is the honest default
                // for a reader that does not know this version.
                e.put("height", j.height);
                e.put("done", j.completed);
                if (j.error != null) e.put("error", j.error);
                JSONArray cs = new JSONArray();
                for (Chunk c : j.chunks) {
                    JSONObject co = new JSONObject();
                    co.put("prompt", c.promptId);
                    co.put("start", c.startFrame);
                    co.put("frames", c.frames);
                    cs.put(co);
                }
                e.put("chunks", cs);
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
     * <p><b>A v1 record MIGRATES rather than being dropped.</b> v1 is one prompt
     * for the whole range, which is a perfectly legitimate in-flight run — the
     * server still has it, and dropping the record would strand its finished
     * result in the output folder, which is the exact loss this class was written
     * to end. The v1 item maps to a one-chunk job with no frame range (the old
     * schema never recorded one), and the fetch tail handles it through the same
     * colour-is-on-the-server branch a fallback upload uses, because that is what
     * a v1 run's graph produced.
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
                // The v3 facts, absent in a v2 blob and therefore the defaults a
                // v2 record genuinely has: no height, no progress, no failure.
                int height = e.optInt("height", 0);
                int done = e.optInt("done", 0);
                String error = e.optString("error", null);
                Job j;
                if (version >= 2) {
                    JSONArray cs = e.optJSONArray("chunks");
                    if (cs == null) continue;
                    List<Chunk> chunks = new ArrayList<Chunk>();
                    for (int c = 0; c < cs.length(); c++) {
                        JSONObject co = cs.optJSONObject(c);
                        if (co == null) continue;
                        chunks.add(new Chunk(co.optString("prompt", ""),
                                co.optInt("start", 0), co.optInt("frames", 0)));
                    }
                    j = new Job(item, chunks, host, started, height, done, error);
                } else {
                    j = new Job(item, e.optString("prompt", ""), host, started);
                }
                // An unusable entry is dropped here rather than carried and skipped by
                // every reader: a record nothing can act on is not a record.
                if (j.isUsable()) out.add(j);
            }
            return new Jobs(out);
        } catch (Throwable t) {
            return empty();
        }
    }
}
