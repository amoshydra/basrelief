package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the ComfyUI depth graph.
 *
 * <p>Assertions are on the parsed structure, not the raw string: Android's
 * org.json and Maven's order keys differently, so an exact-string assertion
 * would pass here and then behave differently on a device.
 */
public class ComfyWorkflowTest {

    private static final long TS = 1_700_000_000_000L;
    private static final String MODEL = "DepthAnythingV2ModelLoader";

    /** A minimal but realistic /object_info body for the depth node. */
    private static String objectInfo() {
        return "{"
                + "\"" + MODEL + "\": {"
                + "  \"input\": {\"required\": {"
                + "    \"image\":      [\"IMAGE\", {}],"
                + "    \"resolution\": [\"INT\", {\"default\": 512, \"min\": 64, \"max\": 4096}],"
                + "    \"ckpt_name\":  [\"COMBO\", {\"default\": \"d2.pth\"}],"
                + "    \"a\":          [\"FLOAT\"],"
                + "    \"b\":          [\"BOOLEAN\"],"
                + "    \"c\":          [\"STRING\"]"
                + "  }}"
                + "}}";
    }

    private static JSONObject wf() throws JSONException {
        return new JSONObject(ComfyWorkflow.buildWorkflow(objectInfo(), MODEL, "up.png", 1024, TS));
    }

    @Test
    public void graphHasExactlyThreeNodes() throws Exception {
        JSONObject g = wf();
        assertEquals(3, g.length());
        assertEquals("LoadImage", g.getJSONObject("1").getString("class_type"));
        assertEquals(MODEL, g.getJSONObject("2").getString("class_type"));
        assertEquals("SaveImage", g.getJSONObject("3").getString("class_type"));
    }

    /**
     * The wiring is the whole point of this test. ComfyUI accepts a graph whose
     * references are off by one and renders the wrong tensor, which shows up as a
     * blank depth map rather than an error.
     */
    @Test
    public void depthNodeReadsLoadImageAndSaveReadsTheDepthNode() throws Exception {
        JSONObject g = wf();

        JSONArray depthImage = g.getJSONObject("2").getJSONObject("inputs").getJSONArray("image");
        assertEquals(2, depthImage.length());
        assertEquals("1", depthImage.getString(0));
        assertEquals(0, depthImage.getInt(1));

        // must be node 2, NOT 1 — saving LoadImage's output saves the photo
        JSONArray saveImages = g.getJSONObject("3").getJSONObject("inputs").getJSONArray("images");
        assertEquals(2, saveImages.length());
        assertEquals("2", saveImages.getString(0));
        assertEquals(0, saveImages.getInt(1));
    }

    @Test
    public void loadNodeCarriesTheUploadedName() throws Exception {
        assertEquals("up.png", wf().getJSONObject("1").getJSONObject("inputs").getString("image"));
    }

    @Test
    public void filenamePrefixUsesTheInjectedTimestamp() throws Exception {
        String prefix = wf().getJSONObject("3").getJSONObject("inputs").getString("filename_prefix");
        assertEquals("depthmap_" + TS, prefix);
    }

    @Test
    public void resolutionOverridesTheNodeDefault() throws Exception {
        JSONObject in = wf().getJSONObject("2").getJSONObject("inputs");
        assertEquals(1024, in.getInt("resolution")); // default was 512
    }

    /** `image` comes from the wire, so the spec's placeholder must not override it. */
    @Test
    public void imageInputIsNotDuplicatedOrOverwritten() throws Exception {
        assertFalse(wf().getJSONObject("2").getJSONObject("inputs").has("image_"));
        assertTrue(wf().getJSONObject("2").getJSONObject("inputs").get("image") instanceof JSONArray);
    }

    @Test
    public void nonResolutionDefaultsComeFromTheNodeSpec() throws Exception {
        JSONObject in = wf().getJSONObject("2").getJSONObject("inputs");
        assertEquals("d2.pth", in.getString("ckpt_name"));
    }

    /** A required input with no default must still be sent, or ComfyUI rejects it. */
    @Test
    public void requiredInputWithNoDefaultGetsATypeAppropriateValue() throws Exception {
        JSONObject in = wf().getJSONObject("2").getJSONObject("inputs");
        assertEquals(0.0, in.getDouble("a"), 0.0);   // FLOAT
        assertTrue(in.getBoolean("b"));              // BOOLEAN
        assertEquals("", in.getString("c"));         // STRING
    }

    @Test
    public void unknownModelIsReportedNotSwallowed() {
        try {
            ComfyWorkflow.buildWorkflow(objectInfo(), "NoSuchNode", "up.png", 512, TS);
            fail("expected a JSONException naming the missing model");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("NoSuchNode"));
        }
    }

    @Test
    public void missingRequiredBlockStillProducesAValidGraph() throws Exception {
        // Some ComfyUI builds report no "input" at all; the node must not vanish.
        String bare = "{\"" + MODEL + "\": {}}";
        JSONObject g = new JSONObject(
                ComfyWorkflow.buildWorkflow(bare, MODEL, "up.png", 512, TS));
        assertEquals(3, g.length());
        assertTrue(g.getJSONObject("2").getJSONObject("inputs").get("image") instanceof JSONArray);
    }

    @Test
    public void friendlyNameHandlesKnownModelsAndCamelCase() {
        assertEquals("Depth Anything V2", ComfyWorkflow.friendlyName("DepthAnythingV2ModelLoader"));
        assertEquals("Depth Anything V1", ComfyWorkflow.friendlyName("DepthAnythingModelLoader"));
        assertEquals("MiDaS", ComfyWorkflow.friendlyName("midas_v21_small"));
        assertEquals("ZoeDepth", ComfyWorkflow.friendlyName("ZoeD_N")); // case-insensitive
        assertEquals("Load Checkpoint", ComfyWorkflow.friendlyName("Load-Checkpoint"));
        // a run of capitals must not be split: GLES20 stays GLES20
        assertEquals("Foo GLES20 Bar", ComfyWorkflow.friendlyName("FooGLES20Bar"));
    }

    /**
     * A hyphen becomes a space, and the capital after it must not add a second
     * one. This shipped as a visible double space in the model dropdown.
     */
    @Test
    public void friendlyNameNeverDoublesASpace() {
        assertEquals("Load Checkpoint", ComfyWorkflow.friendlyName("Load-Checkpoint"));
        assertEquals("a b", ComfyWorkflow.friendlyName("a-b")); // spacing only, never recased
        assertEquals("A B", ComfyWorkflow.friendlyName("A-B"));
        // and must not gain a leading space either
        assertEquals("Load", ComfyWorkflow.friendlyName("Load"));
        assertEquals("Checkpoint", ComfyWorkflow.friendlyName("Checkpoint"));
        assertFalse(ComfyWorkflow.friendlyName("Load-Checkpoint").contains("  "));
    }

    @Test
    public void hostPortStripsSchemeAndPath() {
        assertEquals("box:8188", ComfyWorkflow.hostPort("http://box:8188/queue"));
        assertEquals("box:8188", ComfyWorkflow.hostPort("box:8188"));
        assertEquals("10.0.2.2:8188", ComfyWorkflow.hostPort("http://10.0.2.2:8188"));
    }

    @Test
    public void executionErrorIsSurfacedFromTheMessagePair() throws Exception {
        JSONObject status = new JSONObject("{\"messages\":["
                + "[\"progress\",{\"value\":1}],"
                + "[\"execution_error\",{\"exception_type\":\"RuntimeError\","
                + "\"exception_message\":\"boom\"}]]}");
        assertEquals("RuntimeError: boom", ComfyWorkflow.extractExecutionError(status));
    }

    @Test
    public void executionErrorFallsBackRatherThanThrowing() throws Exception {
        // no messages key at all
        assertEquals("execution error (see ComfyUI console)",
                ComfyWorkflow.extractExecutionError(new JSONObject("{}")));
        // messages present but no execution_error pair
        assertEquals("execution error (see ComfyUI console)",
                ComfyWorkflow.extractExecutionError(
                        new JSONObject("{\"messages\":[[\"progress\",{}]]}")));
    }
}
