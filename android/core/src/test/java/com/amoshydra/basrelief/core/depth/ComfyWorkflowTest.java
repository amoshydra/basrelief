package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

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

    // --------------------------------------------------------------- model filter

    /**
     * The depth-related entries copied from the user's real ComfyUI
     * {@code /object_info} (one server, 1762 nodes). Real schemas on purpose: the
     * bug this guards is a node the server lists but whose required inputs cannot
     * be met, and an invented schema would not have caught it.
     */
    private static JSONObject realServerInfo() throws JSONException {
        return new JSONObject("{"
                + "\"DepthAnythingPreprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"DepthAnythingV2Preprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"Zoe_DepthAnythingPreprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"MiDaS-DepthMapPreprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"MeshGraphormer-DepthMapPreprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\", \"MASK\"]},"
                + "\"DepthAnything_V2\": {"
                + "  \"input\": {\"required\": {\"da_model\": [\"DAMODEL\"],"
                + "                           \"images\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"DownloadAndLoadDepthAnythingV2Model\": {"
                + "  \"input\": {\"required\": {\"model\": ["
                + "      [\"depth_anything_v2_vits_fp16.safetensors\","
                + "       \"depth_anything_v2_vitl_fp32.safetensors\"],"
                + "      {\"default\": \"depth_anything_v2_vitl_fp32.safetensors\"}]}},"
                + "  \"output\": [\"DAMODEL\"]},"
                + "\"SBS_External_Depthmap_by_SamSeen\": {"
                + "  \"input\": {\"required\": {\"base_image\": [\"IMAGE\"],"
                + "                           \"depth_map\": [\"IMAGE\"],"
                + "                           \"depth_scale\": [\"INT\"],"
                + "                           \"mode\": [[\"Parallel\", \"Cross-eyed\"]]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"MeshGraphormer+ImpactDetector-DepthMapPreprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"],"
                + "                           \"bbox_detector\": [\"BBOX_DETECTOR\"]}},"
                + "  \"output\": [\"IMAGE\", \"MASK\"]},"
                + "\"Hy3DRenderMultiViewDepth\": {"
                + "  \"input\": {\"required\": {\"trimesh\": [\"TRIMESH\"],"
                + "                           \"render_size\": [\"INT\"],"
                + "                           \"texture_size\": [\"INT\"]}},"
                + "  \"output\": [\"IMAGE\", \"MASK\"]}"
                + "}");
    }

    /**
     * The whole point: the chooser's list is exactly the nodes the graph builder
     * can submit. A single-image preprocessor stays; a node that needs a model
     * handle, a mask, a mesh or a second image goes.
     */
    @Test
    public void filterKeepsWhatTheBuilderCanRunAndDropsTheRest() throws Exception {
        List<String> expected = Arrays.asList(
                "DepthAnythingPreprocessor",
                "DepthAnythingV2Preprocessor",
                "MeshGraphormer-DepthMapPreprocessor",
                "MiDaS-DepthMapPreprocessor",
                "Zoe_DepthAnythingPreprocessor");
        assertEquals(expected, ComfyWorkflow.usableDepthModels(realServerInfo()));
    }

    /** The named regression: this one is offered today and always fails. */
    @Test
    public void depthAnythingV2IsDroppedBecauseItNeedsADaModelHandle() throws Exception {
        assertFalse(ComfyWorkflow.canBuildWorkflow(
                realServerInfo().getJSONObject("DepthAnything_V2")));
    }

    /** The named regression: a loader is not a depth producer. */
    @Test
    public void downloadAndLoadDepthAnythingV2ModelIsDropped() throws Exception {
        // Its only required input is a satisfiable combo; what disqualifies it is
        // the missing `image` input and the DAMODEL output SaveImage cannot read.
        assertFalse(ComfyWorkflow.canBuildWorkflow(
                realServerInfo().getJSONObject("DownloadAndLoadDepthAnythingV2Model")));
    }

    @Test
    public void aNodeWithOnlyAnImageInputIsRunnable() throws Exception {
        assertTrue(ComfyWorkflow.canBuildWorkflow(
                realServerInfo().getJSONObject("DepthAnythingV2Preprocessor")));
    }

    /** A second, ordinary IMAGE input is still a missing link, not a scalar. */
    @Test
    public void aSecondRequiredImageInputMakesANodeUnrunnable() throws Exception {
        assertFalse(ComfyWorkflow.canBuildWorkflow(
                realServerInfo().getJSONObject("SBS_External_Depthmap_by_SamSeen")));
    }

    @Test
    public void aNonImageOutputZeroMakesANodeUnrunnable() throws Exception {
        // SaveImage reads output 0, so a node whose first output is a mask would
        // be accepted by ComfyUI's graph check and then save the wrong tensor.
        String info = "{\"DepthMask\": {\"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + " \"output\": [\"MASK\"]}}";
        assertFalse(ComfyWorkflow.canBuildWorkflow(
                new JSONObject(info).getJSONObject("DepthMask")));
    }

    /**
     * A required combo has no type name — element 0 is the option array — so the
     * older {@code getString(0)} threw before the prompt was sent. The builder
     * must pick the node's default, or the first option when it has none.
     */
    @Test
    public void requiredComboIsFilledInsteadOfThrowing() throws Exception {
        String info = "{\"LoadDepth\": {\"input\": {\"required\": {"
                + "  \"image\": [\"IMAGE\"],"
                + "  \"which\": [[\"small\", \"large\"], {\"default\": \"large\"}],"
                + "  \"bare\":  [[\"tiny\", \"huge\"]]}}}}";
        JSONObject in = new JSONObject(
                ComfyWorkflow.buildWorkflow(info, "LoadDepth", "up.png", 512, TS))
                .getJSONObject("2").getJSONObject("inputs");
        assertEquals("large", in.getString("which"));
        assertEquals("tiny", in.getString("bare"));
    }

    /** The flag that lets an empty list explain itself instead of lying. */
    @Test
    public void hasDepthNodeDistinguishesAnEmptyServerFromAnUnusableOne() throws Exception {
        assertTrue(ComfyWorkflow.hasDepthNode(realServerInfo()));
        assertFalse(ComfyWorkflow.hasDepthNode(new JSONObject("{\"KSampler\": {}}")));
    }

    // --------------------------------------------------------------- MoGe pipeline

    /**
     * The MoGe schemas copied from the live server's {@code /object_info}. The
     * combo spelling matters: {@code LoadMoGeModel.model_name} has options but
     * no explicit default, which is the newer {@code ["COMBO", {"options":…}]}
     * form and the case the builder has to fall back on. A sibling ordinary
     * depth node is present so the two shapes are exercised on one server.
     */
    private static JSONObject moGeServerInfo() throws JSONException {
        return new JSONObject("{"
                + "\"LoadMoGeModel\": {"
                + "  \"input\": {\"required\": {"
                + "    \"model_name\": [\"COMBO\", {\"multiselect\": false, \"options\": ["
                + "       \"moge_2_vitl_normal_fp16.safetensors\","
                + "       \"moge_3_vitg_fp16.safetensors\"]}]}},"
                + "  \"output\": [\"MOGE_MODEL\"]},"
                + "\"MoGeInference\": {"
                + "  \"input\": {\"required\": {"
                + "    \"moge_model\": [\"MOGE_MODEL\", {}],"
                + "    \"image\": [\"IMAGE\", {}],"
                + "    \"resolution_level\": [\"INT\", {\"default\": 9, \"min\": 0, \"max\": 9}],"
                + "    \"fov_x_degrees\": [\"FLOAT\", {\"default\": 0.0}],"
                + "    \"batch_size\": [\"INT\", {\"default\": 4}],"
                + "    \"force_projection\": [\"BOOLEAN\", {\"default\": true}],"
                + "    \"apply_mask\": [\"BOOLEAN\", {\"default\": true}],"
                + "    \"refine_steps\": [\"INT\", {\"default\": 3}]}},"
                + "  \"output\": [\"MOGE_GEOMETRY\"]},"
                + "\"MoGeRender\": {"
                + "  \"input\": {\"required\": {"
                + "    \"moge_geometry\": [\"MOGE_GEOMETRY\", {}],"
                + "    \"output\": [\"COMBO\", {\"default\": \"depth\", \"options\": ["
                + "       \"depth\", \"depth_colored\", \"normal_opengl\","
                + "       \"normal_directx\", \"mask\"]}]}},"
                + "  \"output\": [\"IMAGE\"]},"
                + "\"DepthAnythingV2Preprocessor\": {"
                + "  \"input\": {\"required\": {\"image\": [\"IMAGE\"]}},"
                + "  \"output\": [\"IMAGE\"]}"
                + "}");
    }

    private static JSONObject moGeWf(String checkpoint) throws JSONException {
        return new JSONObject(ComfyWorkflow.buildWorkflow(
                moGeServerInfo().toString(), checkpoint, "up.png", 1024, TS));
    }

    // ================= the STREAM shape =================

    /**
     * Assert that a node's input is a link to exactly one node and output index.
     *
     * <p>The graph is a set of wires, so a failure has to say which one — which is
     * why every call site names it. Compared through the array's own string form
     * rather than element-wise, because the failure message then reads
     * {@code [2, 0] expected [1, 0]} rather than "arrays differ".
     */
    private static void assertLink(String what, String readerNodeId,
                                   String targetNodeId, int outputIndex,
                                   JSONObject wf, String inputName) {
        JSONObject in = wf.getJSONObject(readerNodeId).getJSONObject("inputs");
        assertTrue(what + ": " + readerNodeId + " has no input '" + inputName + "'",
                in.has(inputName));
        // Compared against the array's own string form, which quotes the node id --
        // a link is a [string, int] pair, not an int, and the quoting is exactly the
        // thing that would catch a graph whose node ids were emitted as numbers.
        assertEquals(what, "[\"" + targetNodeId + "\"," + outputIndex + "]",
                in.getJSONArray(inputName).toString());
    }

    /**
     * The server's answer for the stream shape: the real MoGe pipeline with the
     * {@code MoGeVideoStream} custom node beside it.
     *
     * <p>The schema is copied from the live server's {@code /object_info}: eleven
     * required inputs, the {@code MOGE_MODEL} handle first, and the scalars with
     * the defaults the builder is expected to discover. The list matters because
     * two of them -- {@code resolution_level} and {@code refine_steps} -- are
     * never named by the app and must arrive from exactly here.
     *
     * <p><b>Both caps' fixture defaults are deliberately NOT the live defaults
     * (30 and 1920).</b> The app must STATE each for its own playback reasons -- see
     * {@link ComfyWorkflow#STREAM_FPS_CAP} and
     * {@link ComfyWorkflow#STREAM_MAX_LONG_SIDE} -- so if this fixture carried the
     * app's values, an assertion reading them could not tell "the app stated it"
     * from "the builder inherited the default", which is a guard that cannot fail.
     * 60 and 4096 make each constant the only way its value can reach the input.
     */
    private static JSONObject streamServerInfo() throws JSONException {
        JSONObject base = moGeServerInfo();
        base.put("MoGeVideoStream", new JSONObject("{"
                + "\"input\": {\"required\": {"
                + "  \"moge_model\": [\"MOGE_MODEL\", {}],"
                + "  \"video\": [\"STRING\", {\"default\": \"\"}],"
                + "  \"tag\": [\"STRING\", {\"default\": \"\"}],"
                + "  \"start_frame\": [\"INT\", {\"default\": 0, \"min\": 0}],"
                + "  \"frame_count\": [\"INT\", {\"default\": 0, \"min\": 0}],"
                + "  \"target_height\": [\"INT\", {\"default\": 0, \"min\": 0, \"max\": 8192}],"
                + "  \"max_long_side\": [\"INT\", {\"default\": 4096, \"min\": 0, \"max\": 8192}],"
                + "  \"batch_size\": [\"INT\", {\"default\": 4, \"min\": 1, \"max\": 16}],"
                + "  \"fps_cap\": [\"INT\", {\"default\": 60, \"min\": 0, \"max\": 240}],"
                + "  \"resolution_level\": [\"INT\", {\"default\": 9, \"min\": 0, \"max\": 9}],"
                + "  \"refine_steps\": [\"INT\", {\"default\": 3, \"min\": 0, \"max\": 8}]}},"
                + "\"output\": [\"STRING\", \"STRING\", \"STRING\", \"INT\"]}"));
        return base;
    }

    /** One stream input's fixture default, for the "stated, not inherited" guards. */
    private static int streamFixtureDefault(String input) throws JSONException {
        return streamServerInfo().getJSONObject("MoGeVideoStream")
                .getJSONObject("input").getJSONObject("required")
                .getJSONArray(input).getJSONObject(1).getInt("default");
    }

    private static JSONObject streamWf() throws JSONException {
        return new JSONObject(ComfyWorkflow.buildVideoStreamWorkflow(
                streamServerInfo(), "MoGeInference", "up.mp4", "item-1",
                0, 0, 720, 4));
    }

    /**
     * The whole graph, pinned: two nodes whose one wire is a link to the loader's
     * output 0.
     *
     * <p>A graph is a set of wires, and the failure mode is an off-by-one or a
     * scalar in a link's place -- ComfyUI accepts it and renders from the wrong
     * tensor, or rejects it with a message naming a type rather than the mistake.
     */
    @Test
    public void theStreamGraphIsTwoNodesAndOneWire() throws Exception {
        JSONObject wf = streamWf();
        assertEquals("one loader, one stream node, nothing else", 2, wf.length());
        assertEquals("LoadMoGeModel", wf.getJSONObject("1").getString("class_type"));
        assertEquals(ComfyWorkflow.VIDEO_STREAM,
                wf.getJSONObject("2").getString("class_type"));
        assertLink("the stream node reads the loader's MOGE_MODEL",
                "2", "1", 0, wf, "moge_model");
    }

    /**
     * The run's own inputs travel as scalars, exactly as given.
     *
     * <p>{@code batch_size} is the memory knob and comes from
     * {@code BatchBudget.streamWindow}; {@code frame_count} is 0 on purpose -- the
     * upload IS the range, and asking for a duration-derived count is the measured
     * off-by-one (240 frames report 9.9586 s, so {@code round(x*24)} is 239) this
     * app has already shipped once.
     *
     * <p>Both cap assertions are meaningful only because the fixture's defaults are
     * different numbers (60 and 4096, see {@link #streamServerInfo}), so each can
     * pass only when the builder STATES the constant -- and the {@code assertNotEquals}
     * pair holds the fixture to that, because a fixture that drifted back to the
     * app's value would turn each assertion into a guard that cannot fail.
     */
    @Test
    public void theStreamNodeCarriesTheRunsOwnInputs() throws Exception {
        JSONObject in = streamWf().getJSONObject("2").getJSONObject("inputs");
        assertEquals("up.mp4", in.getString("video"));
        assertEquals("item-1", in.getString("tag"));
        assertEquals(0, in.getInt("start_frame"));
        assertEquals(0, in.getInt("frame_count"));
        assertEquals("the resolution chip is the node's target_height",
                720, in.getInt("target_height"));
        assertEquals("the window", 4, in.getInt("batch_size"));
        assertEquals("the generated tracks' rate cap is the app's, not the node's default",
                ComfyWorkflow.STREAM_FPS_CAP, in.getInt("fps_cap"));
        assertEquals("the playback long-side cap is the app's, not the node's default",
                ComfyWorkflow.STREAM_MAX_LONG_SIDE, in.getInt("max_long_side"));
        assertNotEquals("the fixture's fps_cap default must differ, or the assertion "
                        + "above cannot tell the app's cap from the discovered default",
                ComfyWorkflow.STREAM_FPS_CAP, streamFixtureDefault("fps_cap"));
        assertNotEquals("the fixture's max_long_side default must differ, or the assertion "
                        + "above cannot tell the app's cap from the discovered default",
                ComfyWorkflow.STREAM_MAX_LONG_SIDE, streamFixtureDefault("max_long_side"));
    }

    /**
     * The two scalars the app never names arrive from the node's spec, so a server
     * that tunes them tunes the stream shape too, and a future scalar reaches it
     * without an app change.
     */
    @Test
    public void streamScalarsTheAppDoesNotNameComeFromTheSpec() throws Exception {
        JSONObject in = streamWf().getJSONObject("2").getJSONObject("inputs");
        assertEquals(9, in.getInt("resolution_level"));
        assertEquals(3, in.getInt("refine_steps"));
    }

    /** A chosen checkpoint lands on the loader; {@code MoGeInference} keeps the default. */
    @Test
    public void aChosenCheckpointLandsOnTheStreamLoader() throws Exception {
        JSONObject wf = new JSONObject(ComfyWorkflow.buildVideoStreamWorkflow(
                streamServerInfo(), "moge_3_vitg_fp16.safetensors", "up.mp4", "t",
                0, 0, 0, 2));
        assertEquals("moge_3_vitg_fp16.safetensors",
                wf.getJSONObject("1").getJSONObject("inputs").getString("model_name"));
        // MoGeInference means "the discovered default". The loader's combo has no
        // explicit default, so the spec's first option is what reaches the node --
        // the same rule the still shape follows, and it means the app never has to
        // name a checkpoint to get one.
        assertEquals("moge_2_vitl_normal_fp16.safetensors",
                streamWf().getJSONObject("1").getJSONObject("inputs").getString("model_name"));
    }

    /**
     * The fetch and resume paths address files they have never been told about, so
     * the names they derive must be the node's own names -- and the app's sanitiser
     * must be the node's rule, because otherwise the two disagree on the one string
     * the whole run is addressed by.
     */
    @Test
    public void streamFileNamesAndTagMatchTheNodesRule() {
        assertEquals("basrelief_item-1_depth.mp4", ComfyWorkflow.streamDepthName("item-1"));
        assertEquals("basrelief_item-1_normal.mp4", ComfyWorkflow.streamNormalName("item-1"));
        assertEquals("basrelief_item-1.progress.json",
                ComfyWorkflow.streamProgressName("item-1"));
        // A UUID id, the production case, survives unchanged.
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                ComfyWorkflow.streamTag("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        // The node's rule: alnum plus - and _, everything else dropped, 48 max.
        assertEquals("abc-d", ComfyWorkflow.streamTag("a b/c-d"));
        assertEquals("a_b-cd", ComfyWorkflow.streamTag("a_b-c d"));
        StringBuilder sixty = new StringBuilder();
        for (int i = 0; i < 60; i++) sixty.append('x');
        assertEquals(48, ComfyWorkflow.streamTag(sixty.toString()).length());
        assertEquals("", ComfyWorkflow.streamTag("///"));
        assertFalse(ComfyWorkflow.isStreamTagUsable(""));
        assertFalse(ComfyWorkflow.isStreamTagUsable(null));
        assertTrue(ComfyWorkflow.isStreamTagUsable("item-1"));
    }

    /**
     * The stream shape is structurally different from both still shapes, pinned by
     * the classes it does and does not contain.
     *
     * <p>Node COUNT proves nothing (the still MoGe shape is seven and the single
     * node is three; a shape can be the right size for the wrong reason), so what is
     * asserted is that no still class survives into this graph and the stream node
     * is the only consumer.
     */
    @Test
    public void theStreamShapeIsNotAStillShape() throws Exception {
        JSONObject stream = streamWf();
        assertFalse("the stream graph must not load a still",
                stream.toString().contains("\"LoadImage\""));
        assertFalse("nor save a still", stream.toString().contains("SaveImage"));
        assertFalse("no inference: the node owns the loop",
                stream.toString().contains(ComfyWorkflow.MOGE_INFERENCE));
        assertFalse(stream.toString().contains(ComfyWorkflow.MOGE_RENDER));
        assertTrue(stream.toString().contains(ComfyWorkflow.VIDEO_STREAM));
        assertEquals("one loader, one stream node", 2, stream.length());
    }


    @Test
    public void moGeGraphHasSevenNodesAndWiresEachStage() throws Exception {
        JSONObject g = moGeWf("moge_3_vitg_fp16.safetensors");
        assertEquals(7, g.length());
        assertEquals("LoadImage", g.getJSONObject("1").getString("class_type"));
        assertEquals("LoadMoGeModel", g.getJSONObject("2").getString("class_type"));
        assertEquals("MoGeInference", g.getJSONObject("3").getString("class_type"));
        assertEquals("MoGeRender", g.getJSONObject("4").getString("class_type"));
        assertEquals("SaveImage", g.getJSONObject("5").getString("class_type"));
        assertEquals("MoGeRender", g.getJSONObject("6").getString("class_type"));
        assertEquals("SaveImage", g.getJSONObject("7").getString("class_type"));

        // The three links, each pinned to node AND output index — an off-by-one
        // here is a graph ComfyUI runs from the wrong tensor.
        JSONArray inferModel = g.getJSONObject("3").getJSONObject("inputs")
                .getJSONArray("moge_model");
        assertEquals("2", inferModel.getString(0));
        assertEquals(0, inferModel.getInt(1));

        JSONArray inferImage = g.getJSONObject("3").getJSONObject("inputs").getJSONArray("image");
        assertEquals("1", inferImage.getString(0));
        assertEquals(0, inferImage.getInt(1));

        // BOTH renderers read the one inference, at output 0. This is what makes
        // the expensive step run once while still emitting two tensors.
        for (String renderId : new String[]{"4", "6"}) {
            JSONArray geometry = g.getJSONObject(renderId).getJSONObject("inputs")
                    .getJSONArray("moge_geometry");
            assertEquals("3", geometry.getString(0));
            assertEquals(0, geometry.getInt(1));
        }

        // SaveImage reads MoGeRender (node 4), not MoGeInference or LoadImage.
        JSONArray saveImages = g.getJSONObject("5").getJSONObject("inputs")
                .getJSONArray("images");
        assertEquals("4", saveImages.getString(0));
        assertEquals(0, saveImages.getInt(1));
    }

    /**
     * <b>The highest-risk output of the whole MoGe change:</b> the normal map.
     *
     * <p>A second {@code SaveImage} the fetch path does not know about produces a
     * file nobody reads, and the photo then renders flat with no error anywhere.
     * So the normal render is pinned to {@code normal_opengl} (not the directx
     * variant), reading the same inference, and its saver's prefix is the distinct
     * one the app sorts the returned files by.
     */
    @Test
    public void moGeAlsoEmitsTheNormalMapWithItsOwnPrefix() throws Exception {
        JSONObject g = moGeWf("moge_3_vitg_fp16.safetensors");

        JSONObject renderIn = g.getJSONObject("6").getJSONObject("inputs");
        assertEquals(ComfyWorkflow.MOGE_NORMAL_OUTPUT,
                renderIn.getString("output"));
        assertEquals("normal_opengl", renderIn.getString("output"));

        JSONArray images = g.getJSONObject("7").getJSONObject("inputs")
                .getJSONArray("images");
        assertEquals("6", images.getString(0));
        assertEquals(0, images.getInt(1));
        assertEquals(ComfyWorkflow.NORMAL_FILE_PREFIX + TS,
                g.getJSONObject("7").getJSONObject("inputs").getString("filename_prefix"));
        // The two savers must not share a prefix, or the fetch cannot tell them apart.
        assertFalse(g.getJSONObject("7").getJSONObject("inputs").getString("filename_prefix")
                .equals(g.getJSONObject("5").getJSONObject("inputs").getString("filename_prefix")));
    }

    /** The depth render is still forced, even though a normal render now exists. */
    @Test
    public void moGeStillsRenderDepthOnTheDepthNode() throws Exception {
        JSONObject in = moGeWf("moge_3_vitg_fp16.safetensors")
                .getJSONObject("4").getJSONObject("inputs");
        assertEquals("depth", in.getString("output"));
        assertEquals(ComfyWorkflow.DEPTH_FILE_PREFIX + TS,
                moGeWf("moge_3_vitg_fp16.safetensors")
                        .getJSONObject("5").getJSONObject("inputs").getString("filename_prefix"));
    }

    @Test
    public void moGeCheckpointArgumentBecomesTheLoaderModelName() throws Exception {
        assertEquals("moge_3_vitg_fp16.safetensors",
                moGeWf("moge_3_vitg_fp16.safetensors").getJSONObject("2")
                        .getJSONObject("inputs").getString("model_name"));
        assertEquals("moge_2_vitl_normal_fp16.safetensors",
                moGeWf("moge_2_vitl_normal_fp16.safetensors").getJSONObject("2")
                        .getJSONObject("inputs").getString("model_name"));
    }

    /** A combo with options and no explicit default takes its first option. */
    @Test
    public void moGeLoaderWithoutAChosenCheckpointUsesTheFirstOption() throws Exception {
        assertEquals("moge_2_vitl_normal_fp16.safetensors",
                moGeWf("MoGeInference").getJSONObject("2").getJSONObject("inputs")
                        .getString("model_name"));
    }

    @Test
    public void moGeInferenceScalarsComeFromTheNodeSpec() throws Exception {
        JSONObject in = moGeWf("moge_3_vitg_fp16.safetensors")
                .getJSONObject("3").getJSONObject("inputs");
        assertEquals(9, in.getInt("resolution_level"));
        assertEquals(0.0, in.getDouble("fov_x_degrees"), 0.0);
        assertEquals(4, in.getInt("batch_size"));
        assertTrue(in.getBoolean("force_projection"));
        assertTrue(in.getBoolean("apply_mask"));
        assertEquals(3, in.getInt("refine_steps"));
    }

    @Test
    public void moGeModelListOffersEveryCheckpointNotThePipelineNodes() throws Exception {
        List<String> models = ComfyWorkflow.usableDepthModels(moGeServerInfo());
        assertTrue(models.contains("moge_2_vitl_normal_fp16.safetensors"));
        assertTrue(models.contains("moge_3_vitg_fp16.safetensors"));
        // The ordinary single-node model on the same server is untouched.
        assertTrue(models.contains("DepthAnythingV2Preprocessor"));
        // A pipeline stage is not a single image-to-image model.
        assertFalse(models.contains("MoGeInference"));
        assertFalse(models.contains("LoadMoGeModel"));
        assertFalse(models.contains("MoGeRender"));
    }

    @Test
    public void moGeIsNotOfferedWithoutTheWholePipeline() throws Exception {
        JSONObject partial = new JSONObject(
                "{\"MoGeInference\": {\"input\": {\"required\": {"
                        + "\"image\": [\"IMAGE\"]}}, \"output\": [\"MOGE_GEOMETRY\"]}}");
        assertTrue(ComfyWorkflow.usableDepthModels(partial).isEmpty());
        // ...but the server still names a depth-like node, so the panel can say
        // it found nodes it cannot drive rather than "no depth models".
        assertTrue(ComfyWorkflow.hasDepthNode(partial));
    }

    /**
     * The two shapes must stay different graphs. If a future edit collapsed
     * MoGe into the single-node path, SaveImage would read a node id that does
     * not exist (or the wrong tensor) with nothing else to catch it.
     */
    @Test
    public void theMoGeShapeIsNotTheSingleNodeShape() throws Exception {
        JSONObject ordinary = new JSONObject(
                ComfyWorkflow.buildWorkflow(objectInfo(), MODEL, "up.png", 1024, TS));
        JSONObject moge = moGeWf("moge_3_vitg_fp16.safetensors");

        assertEquals(3, ordinary.length());
        assertEquals(7, moge.length());

        // SaveImage's source differs: the single node is id 2, MoGe's renderer id 4.
        assertEquals("2", ordinary.getJSONObject("3").getJSONObject("inputs")
                .getJSONArray("images").getString(0));
        assertEquals("4", moge.getJSONObject("5").getJSONObject("inputs")
                .getJSONArray("images").getString(0));
    }

    /**
     * The public "does this run also emit a normal" question the fetch path asks.
     * An ordinary node does not; a MoGe checkpoint does.
     */
    @Test
    public void onlyTheMoGeShapeEmitsANormal() throws Exception {
        assertTrue(ComfyWorkflow.isMoGeModel(moGeServerInfo(),
                "moge_3_vitg_fp16.safetensors"));
        assertTrue(ComfyWorkflow.isMoGeModel(moGeServerInfo(), "MoGeInference"));
        assertFalse(ComfyWorkflow.isMoGeModel(moGeServerInfo(), "DepthAnythingV2Preprocessor"));
        assertFalse(ComfyWorkflow.isMoGeModel(new JSONObject(objectInfo()), MODEL));
    }

    /** The picker's MoGe preference is a predicate over the offered names. */
    @Test
    public void moGeCheckpointPredicateNamesTheNormalCapableEntries() {
        assertTrue(ComfyWorkflow.isMoGeCheckpoint("moge_3_vitg_fp16.safetensors"));
        assertTrue(ComfyWorkflow.isMoGeCheckpoint("MoGeInference"));
        assertFalse(ComfyWorkflow.isMoGeCheckpoint("DepthAnythingV2Preprocessor"));
        assertFalse(ComfyWorkflow.isMoGeCheckpoint(null));
    }

    @Test
    public void friendlyNameHandlesMoGeClassesAndCheckpoints() {
        assertEquals("MoGe Inference", ComfyWorkflow.friendlyName("MoGeInference"));
        assertEquals("MoGe Render", ComfyWorkflow.friendlyName("MoGeRender"));
        assertEquals("Load MoGe Model", ComfyWorkflow.friendlyName("LoadMoGeModel"));
        assertEquals("MoGe 3 ViT-G fp16",
                ComfyWorkflow.friendlyName("moge_3_vitg_fp16.safetensors"));
        assertEquals("MoGe 2 ViT-L normal fp16",
                ComfyWorkflow.friendlyName("moge_2_vitl_normal_fp16.safetensors"));
    }
}
