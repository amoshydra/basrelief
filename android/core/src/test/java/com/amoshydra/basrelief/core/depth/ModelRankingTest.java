package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the model chooser's ordering and its history.
 *
 * <p>The bug this exists for: the dialog preferred the first MoGe entry it
 * discovered, so which checkpoint was selected by default followed
 * {@code /object_info}'s listing order. A server that lists {@code moge_2…}
 * before {@code moge_3…} selected MoGe 2 and left MoGe 3 unselected with nothing
 * on screen explaining why. The user's use decides the order now, and the tests
 * hold {@link ModelRanking} to being pure list arithmetic that cannot name a
 * model.
 */
public class ModelRankingTest {

    private static final List<String> DISCOVERED = Arrays.asList(
            "DepthAnythingV2Preprocessor",
            "moge_2_vitl_normal_fp16.safetensors",
            "moge_3_vitg_fp16.safetensors");

    /** The reported bug, inverted: a used model is at the top, not the first one. */
    @Test
    public void theMostRecentlyUsedModelIsFirst() {
        List<String> ranked = ModelRanking.rank(DISCOVERED,
                Collections.singletonList("moge_3_vitg_fp16.safetensors"));
        assertEquals("moge_3_vitg_fp16.safetensors", ranked.get(0));
        // Everything else keeps discovery order behind it.
        assertEquals(Arrays.asList(
                        "moge_3_vitg_fp16.safetensors",
                        "DepthAnythingV2Preprocessor",
                        "moge_2_vitl_normal_fp16.safetensors"),
                ranked);
    }

    /**
     * The second-most-recent is remembered too: picking B then C leaves C first
     * and B second, so the previous choice is still one tap away.
     */
    @Test
    public void aNewerSelectionMovesToTheFrontAndThePreviousStaysSecond() {
        List<String> h = ModelRanking.remember(Collections.<String>emptyList(), "B");
        h = ModelRanking.remember(h, "C");
        assertEquals(Arrays.asList("C", "B"), h);

        // ...and that order is what the rows then use.
        List<String> ranked = ModelRanking.rank(Arrays.asList("A", "B", "C", "D"), h);
        assertEquals(Arrays.asList("C", "B", "A", "D"), ranked);
    }

    /**
     * A checkpoint the server no longer offers must not fail anything: it is
     * skipped, and the entries around it keep their relative order.
     */
    @Test
    public void aHistoryEntryNotOnTheServerIsSkippedAndTheRestKeepOrder() {
        List<String> ranked = ModelRanking.rank(DISCOVERED,
                Arrays.asList("moge_4_gone.safetensors", "moge_2_vitl_normal_fp16.safetensors"));
        assertEquals(Arrays.asList(
                        "moge_2_vitl_normal_fp16.safetensors",
                        "DepthAnythingV2Preprocessor",
                        "moge_3_vitg_fp16.safetensors"),
                ranked);
        // The missing entry is nowhere in the result.
        assertFalse(ranked.contains("moge_4_gone.safetensors"));
    }

    /** A fresh install: no history, so exactly the discovery order, unchanged. */
    @Test
    public void anEmptyHistoryLeavesDiscoveryOrderUntouched() {
        assertEquals(DISCOVERED, ModelRanking.rank(DISCOVERED, Collections.<String>emptyList()));
        assertEquals(DISCOVERED, ModelRanking.rank(DISCOVERED, null));
    }

    /** The history is short by construction, and one selection cannot repeat. */
    @Test
    public void theHistoryIsCappedAndNeverDuplicates() {
        List<String> h = Collections.emptyList();
        for (String model : Arrays.asList("A", "B", "C", "D", "E", "F")) {
            h = ModelRanking.remember(h, model);
        }
        assertEquals(ModelRanking.HISTORY_CAP, h.size());
        assertEquals(Arrays.asList("F", "E", "D", "C"), h);
        assertEquals(new HashSet<String>(h).size(), h.size());

        // Re-selecting an entry moves it up rather than duplicating it.
        h = ModelRanking.remember(h, "E");
        assertEquals(Arrays.asList("E", "F", "D", "C"), h);
        // A decoded blob that somehow carries more or repeats is cleaned too.
        List<String> decoded = ModelRanking.decode("A\nA\nB\nC\nD\nE");
        assertEquals(Arrays.asList("A", "B", "C", "D"), decoded);
    }

    /**
     * The whole safety property: ranking is a permutation. Every returned entry
     * came from the input, nothing was added, nothing was lost.
     */
    @Test
    public void rankNeverInventsAModelAndLosesNothing() {
        List<String> ranked = ModelRanking.rank(DISCOVERED,
                Arrays.asList("moge_3_vitg_fp16.safetensors", "not_a_model", " DepthRan"));
        assertEquals(DISCOVERED.size(), ranked.size());
        Set<String> input = new HashSet<String>(DISCOVERED);
        for (String model : ranked) {
            assertTrue("rank invented " + model, input.contains(model));
        }
        assertTrue(ranked.containsAll(DISCOVERED));
        assertFalse(ranked.contains("not_a_model"));
    }

    /** Duplicates do not survive either path, so a row cannot be drawn twice. */
    @Test
    public void aRepeatedEntryDoesNotDuplicateARow() {
        List<String> ranked = ModelRanking.rank(DISCOVERED,
                Arrays.asList("moge_2_vitl_normal_fp16.safetensors",
                        "moge_2_vitl_normal_fp16.safetensors"));
        assertEquals(DISCOVERED.size(), ranked.size());
        assertEquals(ranked.size(), new HashSet<String>(ranked).size());

        List<String> withBlank = new ArrayList<String>(Arrays.asList("B", "", "B"));
        assertEquals(Arrays.asList("A", "B"), ModelRanking.remember(withBlank, "A"));
    }

    /** The prefs form round-trips, and empty means "no history", never a blank row. */
    @Test
    public void theStoredFormRoundTripsAndEmptyMeansNoHistory() {
        List<String> h = Arrays.asList("moge_3_vitg_fp16.safetensors",
                "moge_2_vitl_normal_fp16.safetensors");
        assertEquals(h, ModelRanking.decode(ModelRanking.encode(h)));
        assertEquals("", ModelRanking.encode(Collections.<String>emptyList()));
        assertTrue(ModelRanking.decode("").isEmpty());
        assertTrue(ModelRanking.decode(null).isEmpty());
    }
}
