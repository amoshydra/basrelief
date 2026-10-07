package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals("p2", jobs.pending().get(0).promptId);
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
