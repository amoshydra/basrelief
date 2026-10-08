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
 * asks the server about each one, and either finishes the import or forgets it.
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
     * longer name the work.
     */
    public static final int VERSION = 2;

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

        public Job(String itemId, List<Chunk> chunks, String host, long startedAtMs) {
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
        }

        /** A one-prompt job, the shape v1 records migrate to. */
        public Job(String itemId, String promptId, String host, long startedAtMs) {
            this(itemId,
                    Collections.singletonList(new Chunk(promptId, 0, 0)),
                    host, startedAtMs);
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
                    j = new Job(item, chunks, host, started);
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
