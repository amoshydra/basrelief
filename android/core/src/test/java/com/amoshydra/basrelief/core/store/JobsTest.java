package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * The pending-import record: what survives a restart, and what must not be invented.
 *
 * <p>The traps are the ones the class exists for. A record that does not survive the
 * round trip is the loss this class was written to end. A blob that throws on read is
 * worse than a lost record, because it is read on the path to drawing. And a record
 * naming a photo that is gone must not be resurrected here -- this class cannot see
 * the library, so it must not pretend to.
 *
 * <p><b>The chunk list is the second half of all of that.</b> A chunked import
 * submits several prompts for one item, so a record that kept only the first would
 * strand every finished chunk behind it -- and a v1 record is a legitimate in-flight
 * single-prompt job, so it migrates to a one-chunk job rather than being dropped.
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
        // A single-prompt job is a one-chunk job, and that is the whole v1 shape.
        assertEquals(1, j.chunks.size());
        assertEquals("p1", j.chunks.get(0).promptId);
        assertEquals("http://host:8188", j.host);
        assertEquals(1000L, j.startedAtMs);
    }

    /**
     * A chunked job keeps every prompt, in order, with the range each one covers.
     *
     * <p>The ORDER is load-bearing: the fetch tail concatenates the chunks by
     * position, so a list that parsed back shuffled -- or dropped the last chunk --
     * would write a clip that plays short without failing anywhere.
     */
    @Test
    public void aChunkedJobSurvivesTheRoundTrip() {
        Jobs.Job chunked = new Jobs.Job("a", Arrays.asList(
                new Jobs.Chunk("p0", 0, 240),
                new Jobs.Chunk("p1", 240, 240),
                new Jobs.Chunk("p2", 480, 121)),
                "http://host:8188", 2000L);
        Jobs parsed = Jobs.parse(Jobs.empty().with(chunked).toJson());
        assertEquals(1, parsed.pending().size());
        Jobs.Job j = parsed.pending().get(0);
        assertEquals(3, j.chunks.size());
        assertEquals("p0", j.chunks.get(0).promptId);
        assertEquals(0, j.chunks.get(0).startFrame);
        assertEquals(240, j.chunks.get(0).frames);
        assertEquals("p2", j.chunks.get(2).promptId);
        assertEquals(480, j.chunks.get(2).startFrame);
        assertEquals("the last chunk takes the remainder", 121, j.chunks.get(2).frames);
        assertEquals(Arrays.asList("p0", "p1", "p2"), j.prompts());
    }

    /**
     * A version 1 record MIGRATES to a one-chunk job.
     *
     * <p>Not dropped, and that is the decision the schema bump had to make: a v1
     * blob is a legitimate in-flight single-prompt run -- the server still has it --
     * so forgetting it would strand a finished result in the output folder, which is
     * the exact loss this class exists to end. Its one prompt becomes one chunk with
     * no frame range, because the old schema never recorded one and inventing a
     * range would be pretending to know something nothing wrote down.
     */
    @Test
    public void aVersionOneRecordMigratesToOneChunk() {
        String v1 = "{\"version\":1,\"jobs\":[{\"item\":\"a\",\"prompt\":\"p1\","
                + "\"host\":\"h\",\"started\":7}]}";
        Jobs jobs = Jobs.parse(v1);
        assertEquals("a v1 job is migrated, not dropped", 1, jobs.pending().size());
        Jobs.Job j = jobs.pending().get(0);
        assertEquals("a", j.itemId);
        assertEquals("h", j.host);
        assertEquals(7L, j.startedAtMs);
        assertEquals(1, j.chunks.size());
        assertEquals("p1", j.chunks.get(0).promptId);
        assertEquals(0, j.chunks.get(0).startFrame);
        assertEquals(0, j.chunks.get(0).frames);
        assertTrue(j.isUsable());
    }

    /**
     * A second submission for one row SUPERSEDES the first, keyed by item.
     *
     * <p>Not by prompt: one photo has one import in flight, and keeping both would
     * leave two fetches racing to write the same filenames.
     */
    @Test
    public void aSecondJobForOneItemReplacesTheFirst() {
        Jobs jobs = Jobs.empty().with(job("a", "p1")).with(job("a", "p2"));
        assertEquals("one row, one job", 1, jobs.pending().size());
        assertEquals("p2", jobs.pending().get(0).chunks.get(0).promptId);
    }

    @Test
    public void removalIsByPromptAndByItem() {
        Jobs jobs = Jobs.empty().with(job("a", "p1")).with(job("b", "p2"));
        assertEquals(1, jobs.afterRemoving("p1").pending().size());
        assertEquals("b", jobs.afterRemoving("p1").pending().get(0).itemId);
        assertEquals("removing an unknown prompt is not an error",
                2, jobs.afterRemoving("nope").pending().size());
        assertEquals("and a delete of the photo takes its job",
                1, jobs.afterRemovingItem("a").pending().size());
        assertEquals("b", jobs.afterRemovingItem("a").pending().get(0).itemId);
    }

    /**
     * Naming ANY chunk clears the whole job, because a chunked job's prompts are one
     * unit: they were submitted together and the range is fetched together.
     */
    @Test
    public void anyChunkNamesTheWholeJob() {
        Jobs jobs = Jobs.empty().with(new Jobs.Job("a", Arrays.asList(
                new Jobs.Chunk("p0", 0, 240),
                new Jobs.Chunk("p1", 240, 240)),
                "h", 1000L));
        assertEquals("the tail holds the first prompt and still clears all of it",
                0, jobs.afterRemoving("p0").pending().size());
        assertEquals("and so does any other chunk",
                0, jobs.afterRemoving("p1").pending().size());
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
        assertEquals(0, Jobs.parse("{\"version\":1}").pending().size());
        assertEquals("a v2 job with no chunk list is not a job",
                0, Jobs.parse("{\"version\":2,\"jobs\":[{\"item\":\"a\",\"host\":\"h\"}]}")
                        .pending().size());
    }

    /**
     * An entry missing any of the three things a fetch needs is DROPPED where it is
     * read, not carried and skipped by every caller.
     */
    @Test
    public void anUnusableEntryIsNotARecord() {
        String blob = "{\"version\":1,\"jobs\":["
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
}
