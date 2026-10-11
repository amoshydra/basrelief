package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pending-import record: what survives a restart, and what must not be invented.
 *
 * <p>The traps are the ones the class exists for. A record that does not survive the
 * round trip is the loss this class was written to end. A blob that throws on read is
 * worse than a lost record, because it is read on the path to drawing. And a record
 * naming a photo that is gone must not be resurrected here -- this class cannot see
 * the library, so it must not pretend to.
 *
 * <p><b>v4 is one prompt again, and the OLDER chunk lists migrate rather than being
 * dropped.</b> A v2/v3 blob is a legitimate in-flight run -- the server may still be
 * running it -- and the first prompt in its list is the handle it answers to; the
 * new resume asks the sidecar for the run's state, so the frame ranges and the
 * completed-chunk count are deliberately not carried. The one fact that survives
 * every version is a failure: a failed job has to keep failing until the user acts.
 */
public class JobsTest {

    private static Jobs.Job job(String item, String prompt) {
        return new Jobs.Job(item, prompt, "http://host:8188", 1000L);
    }

    @Test
    public void aJobSurvivesTheRoundTrip() {
        Jobs jobs = Jobs.empty().with(job("a", "p1"));
        Jobs parsed = Jobs.parse(jobs.toJson());
        assertEquals(1, parsed.pending().size());
        Jobs.Job j = parsed.pending().get(0);
        assertEquals("a", j.itemId);
        assertEquals("p1", j.promptId);
        assertEquals("http://host:8188", j.host);
        assertEquals(1000L, j.startedAtMs);
        assertFalse(j.isFailed());
    }

    /**
     * The whole blob is v5: one prompt per job, a kind, and NO count.
     *
     * <p>Pinned as an exact key set because the count moving to the sidecar is the
     * decision this version made: a "done" or "frames" field written back into the
     * record would be a second, staler answer to the question the sidecar answers,
     * and a future reader could not tell which one to believe.
     */
    @Test
    public void theRecordHoldsOnePromptAndNoProgress() throws Exception {
        org.json.JSONObject blob = new org.json.JSONObject(
                Jobs.empty().with(job("a", "p1")).toJson());
        org.json.JSONObject entry = blob.getJSONArray("jobs").getJSONObject(0);
        assertEquals(5, blob.getInt("version"));
        assertTrue(entry.getString("prompt").equals("p1"));
        for (String forbidden : new String[] {"chunks", "done", "height", "frames"}) {
            assertFalse("v4 must not carry '" + forbidden + "': the sidecar owns progress",
                    entry.has(forbidden));
        }
    }

    /**
     * A splat record says so, and a clip record stays byte-identical to before.
     *
     * <p>Absent is a clip import — every record written before the field existed —
     * so adding the kind must not touch an existing blob's keys. A splat record that
     * lost its kind on the round trip would be resumed as a clip and fetch depth
     * clips that were never written.
     */
    @Test
    public void aSplatJobSaysSoAndAClipJobCarriesNoKey() throws Exception {
        org.json.JSONObject clipBlob = new org.json.JSONObject(
                Jobs.empty().with(job("a", "p1")).toJson());
        assertFalse("a clip record must not grow a kind key",
                clipBlob.getJSONArray("jobs").getJSONObject(0).has("kind"));

        org.json.JSONObject splatBlob = new org.json.JSONObject(
                Jobs.empty().with(new Jobs.Job("b", "p2", "http://h:8188", 1000L,
                        Jobs.Kind.SPLAT)).toJson());
        assertEquals("splat", splatBlob.getJSONArray("jobs").getJSONObject(0)
                .getString("kind"));
        assertEquals(Jobs.Kind.SPLAT,
                Jobs.parse(splatBlob.toString()).pending().get(0).kind);
        // An old blob (no kind) reads as a clip.
        assertEquals(Jobs.Kind.CLIP,
                Jobs.parse(clipBlob.toString()).pending().get(0).kind);
    }

    /**
     * A version 1 record MIGRATES: its one prompt is the handle the old run answers
     * to.
     *
     * <p>Not dropped, and that is the decision every schema bump had to make: a v1
     * blob is a legitimate in-flight run, so forgetting it would strand a finished
     * result in the output folder, which is the exact loss this class exists to end.
     */
    @Test
    public void aVersionOneRecordMigratesToTheNewShape() {
        String v1 = "{\"version\":1,\"jobs\":[{\"item\":\"a\",\"prompt\":\"p1\","
                + "\"host\":\"h\",\"started\":7}]}";
        Jobs jobs = Jobs.parse(v1);
        assertEquals("a v1 job is migrated, not dropped", 1, jobs.pending().size());
        Jobs.Job j = jobs.pending().get(0);
        assertEquals("a", j.itemId);
        assertEquals("h", j.host);
        assertEquals(7L, j.startedAtMs);
        assertEquals("p1", j.promptId);
        assertTrue(j.isUsable());
    }

    /**
     * A v2 or v3 CHUNK LIST migrates to its FIRST prompt.
     *
     * <p>The first chunk is the handle the old run answers to -- it was submitted
     * first and the old tail held it -- while the frame ranges and the completed
     * count are dropped: the new resume asks the sidecar instead of continuing a
     * chunk list, and a "completed chunks" number read as frames would be a lie.
     */
    @Test
    public void aChunkListMigratesToItsFirstPrompt() {
        String v3 = "{\"version\":3,\"jobs\":[{\"item\":\"a\",\"host\":\"h\","
                + "\"started\":7,\"height\":720,\"done\":2,\"chunks\":["
                + "{\"prompt\":\"p0\",\"start\":0,\"frames\":240},"
                + "{\"prompt\":\"p1\",\"start\":240,\"frames\":240}]}]}";
        Jobs.Job j = Jobs.parse(v3).pending().get(0);
        assertEquals("p0", j.promptId);
        assertEquals("a", j.itemId);
        assertFalse(j.isFailed());

        // A v2 blob is the same shape without the v3 facts.
        String v2 = "{\"version\":2,\"jobs\":[{\"item\":\"a\",\"host\":\"h\","
                + "\"started\":7,\"chunks\":[{\"prompt\":\"p1\",\"start\":0,"
                + "\"frames\":240}]}]}";
        assertEquals("p1", Jobs.parse(v2).pending().get(0).promptId);
    }

    /** A v3 failure survives the migration: a failed run keeps failing. */
    @Test
    public void aFailuresMessageSurvivesTheMigration() {
        String v3 = "{\"version\":3,\"jobs\":[{\"item\":\"a\",\"host\":\"h\","
                + "\"started\":7,\"done\":1,\"error\":\"CUDA out of memory\","
                + "\"chunks\":[{\"prompt\":\"p0\",\"start\":0,\"frames\":240}]}]}";
        Jobs.Job j = Jobs.parse(v3).pending().get(0);
        assertTrue(j.isFailed());
        assertEquals("CUDA out of memory", j.error);
    }

    /**
     * A second submission for one row SUPERSEDES the first, keyed by item.
     *
     * <p>Not by prompt: one photo has one import in flight, and keeping both would
     * leave two fetches racing to write the same filenames. The same rule covers a
     * resume's re-submit, which writes a new prompt for the same item.
     */
    @Test
    public void aSecondJobForOneItemReplacesTheFirst() {
        Jobs jobs = Jobs.empty().with(job("a", "p1")).with(job("a", "p2"));
        assertEquals("one row, one job", 1, jobs.pending().size());
        assertEquals("p2", jobs.pending().get(0).promptId);
    }

    /**
     * Removal and failure are keyed by PROMPT, not item -- and that is load-bearing.
     * A superseded run (the record now holds a newer prompt for the same item) must
     * not be cleared or failed by the old run finishing.
     */
    @Test
    public void removalIsByPromptAndByItem() {
        Jobs jobs = Jobs.empty().with(job("a", "p1")).with(job("b", "p2"));
        assertEquals(1, jobs.afterRemoving("p1").pending().size());
        assertEquals("b", jobs.afterRemoving("p1").pending().get(0).itemId);
        assertEquals("removing an unknown prompt is not an error",
                2, jobs.afterRemoving("nope").pending().size());
        assertEquals("a superseded prompt does not clear the newer record",
                2, jobs.afterRemoving("old-for-a").pending().size());
        assertEquals("and a delete of the photo takes its job",
                1, jobs.afterRemovingItem("a").pending().size());
        assertEquals("b", jobs.afterRemovingItem("a").pending().get(0).itemId);
    }

    /**
     * A corrupt, empty or future blob is an EMPTY set, never a throw.
     *
     * <p>Read on the path to drawing: an exception here is a crash at start-up, and a
     * future version must not be guessed at -- reading fields with meanings this code
     * does not know is how a schema becomes a lie.
     */
    @Test
    public void aCorruptOrFutureBlobIsEmptyAndNeverThrows() {
        assertEquals(0, Jobs.parse(null).pending().size());
        assertEquals(0, Jobs.parse("").pending().size());
        assertEquals(0, Jobs.parse("not json at all").pending().size());
        assertEquals(0, Jobs.parse("{\"version\":99,\"jobs\":[{\"item\":\"a\","
                + "\"prompt\":\"p\",\"host\":\"h\"}]}").pending().size());
        assertEquals(0, Jobs.parse("{\"version\":4}").pending().size());
        assertEquals("a v2 job with no chunk list is not a job",
                0, Jobs.parse("{\"version\":2,\"jobs\":[{\"item\":\"a\",\"host\":\"h\"}]}")
                        .pending().size());
    }

    /**
     * An entry missing any of the three things a run needs is DROPPED where it is
     * read, not carried and skipped by every caller.
     */
    @Test
    public void anUnusableEntryIsNotARecord() {
        String blob = "{\"version\":4,\"jobs\":["
                + "{\"item\":\"a\",\"prompt\":\"p1\",\"host\":\"h\"},"
                + "{\"item\":\"\",\"prompt\":\"p2\",\"host\":\"h\"},"
                + "{\"item\":\"c\",\"prompt\":\"\",\"host\":\"h\"},"
                + "{\"item\":\"d\",\"prompt\":\"p4\",\"host\":\"\"}]}";
        Jobs jobs = Jobs.parse(blob);
        assertEquals("only the complete record survives", 1, jobs.pending().size());
        assertEquals("a", jobs.pending().get(0).itemId);
        assertTrue(jobs.pending().get(0).isUsable());
        assertFalse(new Jobs.Job("", "p", "h", 0L).isUsable());
    }

    /** Oldest first, so a resume pass asks in the order the jobs were submitted. */
    @Test
    public void pendingIsOrderedBySubmission() {
        Jobs jobs = Jobs.empty()
                .with(new Jobs.Job("late", "p2", "h", 2000L))
                .with(new Jobs.Job("early", "p1", "h", 1000L));
        assertEquals("early", jobs.pending().get(0).itemId);
        assertEquals("late", jobs.pending().get(1).itemId);
    }

    /**
     * A FAILED run stays a record, carrying the server's own words.
     *
     * <p>The failure can arrive minutes after the share with the phone face down.
     * Clearing the record there left the failure in a toast that is gone before it
     * is read; the sheet line and the tile's corner need it on disk until the user
     * acts — Regenerate replaces the record with a fresh submission.
     */
    @Test
    public void aFailedJobSurvivesTheRoundTrip() {
        Jobs jobs = Jobs.empty()
                .with(job("a", "p1"))
                .withError("p1", "the run needed ~19 GiB; the server has ~24");
        Jobs parsed = Jobs.parse(jobs.toJson());
        assertEquals("a failure is kept, not cleared", 1, parsed.pending().size());
        Jobs.Job j = parsed.pending().get(0);
        assertTrue(j.isFailed());
        assertEquals("the run needed ~19 GiB; the server has ~24", j.error);
        assertEquals("p1", j.promptId);
    }

    /**
     * Marking failed is scoped to the PROMPT, so a superseded run's error cannot
     * land on the record of the submission that replaced it.
     */
    @Test
    public void markingFailedIsScopedToThePrompt() {
        Jobs jobs = Jobs.empty().with(job("a", "p1")).with(job("a", "p2"));
        assertTrue(jobs.withError("p2", "stopped").pending().get(0).isFailed());
        assertFalse("the superseded prompt's failure lands nowhere",
                jobs.withError("p1", "stopped").pending().get(0).isFailed());
        assertFalse("naming a prompt no job holds changes nothing",
                jobs.withError("nope", "stopped").pending().get(0).isFailed());
        assertFalse("and a null prompt is not a name",
                jobs.withError(null, "stopped").pending().get(0).isFailed());
    }

    /**
     * A row with a record and no tracks names its colour preview, and ONLY that row.
     *
     * <p>The name is the import's own remux — written before anything reaches the
     * server, which is what makes a preview possible at all — and it is answered for
     * the item the record names, never for another row. No record and no item are
     * the same answer: nothing to preview.
     */
    @Test
    public void aRecordWithoutTracksNamesTheRowsColourPreview() {
        Jobs jobs = Jobs.empty().with(job("a", "p1"));
        LibraryItem row = LibraryItem.of("a", "poster.png", null, 1L);
        assertEquals(LibraryItem.videoFileName("a"), jobs.previewColourName(row));
        assertNull("another row's record is not this row's preview",
                jobs.previewColourName(LibraryItem.of("b", "b.png", null, 1L)));
        assertNull("no record, no preview",
                Jobs.empty().previewColourName(row));
        assertNull("no row, no preview", jobs.previewColourName(null));
    }

    /**
     * <b>The precedence window, pinned.</b> Tracks WIN over a lingering record: a row
     * whose clips have been written is the full clip even if the record has not been
     * cleared yet, and a preview there would restart a real clip at frame 0. This is
     * the window every resolve between the two writes lands in, and it is also the
     * whole state a REGENERATE runs in — record and tracks together for minutes.
     */
    @Test
    public void tracksWinOverALingeringRecord() {
        Jobs jobs = Jobs.empty().with(job("a", "p1"));
        LibraryItem grown = LibraryItem.of("a", "poster.png", null, 1L)
                .withVideo(VideoTracks.of(
                        LibraryItem.videoFileName("a"),
                        LibraryItem.videoDepthFileName("a")));
        assertNull("a row with clips must never preview",
                jobs.previewColourName(grown));
    }

    /**
     * A FAILED record still previews: the failure is kept for the sheet and the
     * tile's corner, and the user's colour clip keeps playing while it is read —
     * stopping the picture at the one moment there is nothing else to look at is
     * not what "kept" means.
     */
    @Test
    public void aFailedRecordStillNamesThePreview() {
        Jobs jobs = Jobs.empty().with(job("a", "p1"))
                .withError("p1", "the run needed ~19 GiB; the server has ~24");
        LibraryItem row = LibraryItem.of("a", "poster.png", null, 1L);
        assertEquals(LibraryItem.videoFileName("a"), jobs.previewColourName(row));
    }
}
