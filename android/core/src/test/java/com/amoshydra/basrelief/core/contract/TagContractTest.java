package com.amoshydra.basrelief.core.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The tag rule, against {@code contract/tag/cases.json} — the same table
 * {@code server/comfy-node/tests/test_contract.py} runs.
 *
 * <p>Why a fixture at all: the app derives output filenames from the item id
 * without ever having seen a server response, while the node derives them from the
 * tag it is handed. Neither can call the other's code, so the only way the two
 * copies can be held together is a shared set of inputs and expected outputs. The
 * rule's own docstring in {@code ComfyWorkflow.streamTag} says which two
 * divergences this table exists to make unrepresentable.
 */
public class TagContractTest {

    private static JSONObject cases() {
        return ContractFiles.json("tag", "cases.json");
    }

    @Test
    public void everyCaseInTheTableIsTheRule() {
        JSONArray cases = cases().getJSONArray("cases");
        assertTrue("the table is empty", cases.length() > 0);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            assertEquals(c.getString("why"),
                    c.getString("tag"), ComfyWorkflow.streamTag(c.getString("id")));
        }
    }

    /**
     * A table that has lost its hard rows is a rule with no guard, so the classes
     * of case are checked rather than merely described. The non-BMP one matters
     * most: it is the ONLY case that can see a truncation measured in code units
     * instead of characters.
     */
    @Test
    public void theTableCoversTheCasesItClaimsTo() {
        JSONArray cases = cases().getJSONArray("cases");
        boolean overLong = false;
        boolean empty = false;
        boolean nonBmp = false;
        boolean nonAscii = false;
        boolean edgeDash = false;
        for (int i = 0; i < cases.length(); i++) {
            String id = cases.getJSONObject(i).getString("id");
            overLong |= id.length() > ComfyWorkflow.TAG_MAX_CHARS;
            empty |= id.isEmpty();
            nonBmp |= id.codePointCount(0, id.length()) < id.length();
            edgeDash |= id.startsWith("-") || id.endsWith("-");
            for (int j = 0; j < id.length(); j++) nonAscii |= id.charAt(j) > 0x7f;
        }
        assertTrue("no over-long id", overLong);
        assertTrue("no empty id", empty);
        assertTrue("no non-BMP character", nonBmp);
        assertTrue("no non-ASCII character", nonAscii);
        assertTrue("no leading/trailing dash", edgeDash);
    }

    /**
     * The truncation is 48 KEPT CHARACTERS, and every kept character is ASCII, so
     * the answer is the same number of bytes, code points and UTF-16 units. That
     * is what makes the same constant mean one thing on both sides.
     */
    @Test
    public void truncationKeepsTheFirst48KeptCharactersNotCodeUnits() {
        JSONArray cases = cases().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            String id = cases.getJSONObject(i).getString("id");
            String tag = ComfyWorkflow.streamTag(id);
            assertTrue(tag.length() <= ComfyWorkflow.TAG_MAX_CHARS);
            assertEquals(tag.length(), tag.codePointCount(0, tag.length()));
            for (int j = 0; j < tag.length(); j++) {
                assertTrue("non-ASCII survived: " + tag.charAt(j), tag.charAt(j) < 0x80);
            }
        }
    }

    /** Re-sanitising a tag the app already sent is what the node does, so it is a no-op. */
    @Test
    public void theRuleIsIdempotent() {
        JSONArray cases = cases().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            String once = ComfyWorkflow.streamTag(cases.getJSONObject(i).getString("id"));
            assertEquals(once, ComfyWorkflow.streamTag(once));
        }
    }

    /**
     * The point of the rule: both sides derive these names, from the id alone. The
     * node's half is asserted in the Python test against the same block.
     */
    @Test
    public void theFilenamesBothSidesDeriveFromTheTag() {
        JSONObject names = cases().getJSONObject("names");
        String tag = names.getString("tag");
        assertEquals(tag, ComfyWorkflow.streamTag(names.getString("id")));
        JSONObject stream = names.getJSONObject("stream");
        assertEquals(stream.getString("depth"), ComfyWorkflow.streamDepthName(tag));
        assertEquals(stream.getString("normal"), ComfyWorkflow.streamNormalName(tag));
        assertEquals(stream.getString("progress"), ComfyWorkflow.streamProgressName(tag));
        JSONObject splat = names.getJSONObject("splat");
        assertEquals(splat.getString("cloud"), ComfyWorkflow.sharpSplatName(tag));
        assertEquals(splat.getString("sidecar"), ComfyWorkflow.sharpSidecarName(tag));
    }

    @Test
    public void anEmptyTagIsTheOneTheAppRefusesToSubmit() {
        assertFalse(ComfyWorkflow.isStreamTagUsable(ComfyWorkflow.streamTag("///")));
        assertFalse(ComfyWorkflow.isStreamTagUsable(""));
        assertFalse(ComfyWorkflow.isStreamTagUsable(null));
        assertTrue(ComfyWorkflow.isStreamTagUsable(ComfyWorkflow.streamTag("item-1")));
    }
}
