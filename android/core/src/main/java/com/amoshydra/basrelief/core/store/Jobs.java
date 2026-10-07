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
 * <p><b>Parsing never throws and never invents.</b> The same rule as {@link Library}
 * and {@link Playlists}: this is read on the path to drawing, a corrupt blob yields
 * an empty set rather than an exception, and a job naming a photo that no longer
 * exists is dropped by the caller rather than resurrected here — this class cannot
 * see the library and must not pretend to.
 */
public final class Jobs {

    /** The SharedPreferences key this blob lives under. */
    public static final String KEY = "jobs";

    /** The schema version, so a future field can migrate rather than guess. */
    public static final int VERSION = 1;

    /** One submitted import: the photo it belongs to, and the server's handle. */
    public static final class Job {
        public final String itemId;
        public final String promptId;
        public final String host;
        public final long startedAtMs;

        public Job(String itemId, String promptId, String host, long startedAtMs) {
            this.itemId = itemId == null ? "" : itemId;
            this.promptId = promptId == null ? "" : promptId;
            this.host = host == null ? "" : host;
            this.startedAtMs = startedAtMs;
        }

        /** Whether this record is worth asking the server about. */
        public boolean isUsable() {
            return !itemId.isEmpty() && !promptId.isEmpty() && !host.isEmpty();
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
     * The same set with this job added, replacing any earlier job for the same item.
     *
     * <p><b>Keyed by ITEM, not by prompt.</b> One photo has one import in flight: a
     * second submission for the same row supersedes the first, and keeping both would
     * leave two fetches racing to write the same filenames. The old prompt keeps
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

    /** The same set without one job, by prompt id. Unknown ids are ignored. */
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
                e.put("prompt", j.promptId);
                e.put("host", j.host);
                e.put("started", j.startedAtMs);
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
     */
    public static Jobs parse(String json) {
        if (json == null || json.isEmpty()) return empty();
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("version", 0) > VERSION) return empty();
            JSONArray arr = o.optJSONArray("jobs");
            if (arr == null) return empty();
            List<Job> out = new ArrayList<Job>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                Job j = new Job(e.optString("item", ""), e.optString("prompt", ""),
                        e.optString("host", ""), e.optLong("started", 0L));
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
