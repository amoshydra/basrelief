package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ================= the VIDEO shape =================

    /**
     * The server as it actually answered {@code /object_info}, for the video shape.
     *
     * <p>The two video nodes are the real signatures, including the loader's
     * <b>four</b> outputs of which two are typed {@code IMAGE}. That duplication is
     * the trap this fixture exists to keep honest: output 1 is the MASK, and a graph
     * that fed it to MoGe would produce a plausible depth map of nothing with no
     * error anywhere.
     */
    /**
     * Assert that a node's input is a link to exactly one node and output index.
     *
     * <p>The graph is a set of wires, so a failure has to say which one — which is
     * why every call site names it. Compared through the array's own string form
     * rather than element-wise, because the failure message then reads
     * {@code [12, 0] expected [13, 0]} rather than "arrays differ".
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

    private static JSONObject videoServerInfo() throws JSONException {
        JSONObject base = moGeServerInfo();
        base.put("VHS_LoadVideo", new JSONObject("{"
                + "\"input\": {\"required\": {"
                + "  \"video\": [\"STRING\", {}],"
                + "  \"force_rate\": [\"FLOAT\", {\"default\": 0.0}],"
                + "  \"custom_width\": [\"INT\", {\"default\": 0}],"
                + "  \"custom_height\": [\"INT\", {\"default\": 0}],"
                + "  \"frame_load_cap\": [\"INT\", {\"default\": 0}],"
                + "  \"skip_first_frames\": [\"INT\", {\"default\": 0}],"
                + "  \"select_every_nth\": [\"INT\", {\"default\": 1}]}},"
                // out0 IMAGE (frames), out1 IMAGE (mask), out2 AUDIO, out3 VIDEO
                + "\"output\": [\"I\", \"I\", \"A\", \"V\"]}"));
        base.put("VHS_VideoCombine", new JSONObject("{"
                + "\"input\": {\"required\": {"
                + "  \"images\": [\"IMAGE\", {}],"
                + "  \"frame_rate\": [\"FLOAT\", {\"default\": 0.0}],"
                + "  \"loop_count\": [\"INT\", {\"default\": 0}],"
                + "  \"filename_prefix\": [\"STRING\", {}],"
                + "  \"format\": [\"COMBO\", {\"options\": [\"image/gif\","
                + "     \"video/h264-mp4\", \"video/webm\"]}],"
                + "  \"pingpong\": [\"BOOLEAN\", {\"default\": false}],"
                + "  \"save_output\": [\"BOOLEAN\", {\"default\": true}]}},"
                + "\"output\": [\"V\"]}"));
        return base;
    }

    private static JSONObject videoWf() throws JSONException {
        return new JSONObject(ComfyWorkflow.buildVideoWorkflow(
                videoServerInfo(), "moge_3_vitg_fp16.safetensors",
                "/tmp/up.mp4", TS, 24f, 48, 0, 0, 0));
    }

    /**
     * Every wire of the video graph, pinned to its node <b>and</b> its output index.
     *
     * <p>Each assertion names the node it is about, because a graph is a set of wires
     * and a failure that does not say which one is a failure you have to re-derive.
     */
    @Test
    public void everyWireOfTheVideoGraphIsPinned() throws JSONException {
        JSONObject wf = videoWf();

        // The loader reads the UPLOADED path and samples at the OUTPUT rate. The rate
        // is the one number both the loader and both combiners must agree on, and it
        // is the number whose disagreement slides the depth against the colour.
        assertEquals("loader path", "/tmp/up.mp4",
                wf.getJSONObject("10").getJSONObject("inputs").getString("video"));
        assertEquals("loader rate", 24.0,
                wf.getJSONObject("10").getJSONObject("inputs").getDouble("force_rate"), 1e-9);
        assertEquals("frame cap", 48,
                wf.getJSONObject("10").getJSONObject("inputs").getInt("frame_load_cap"));

        // MoGe reads the loader's output 0 -- the FRAMES, not the mask. This is the
        // assertion the whole two-IMAGE-outputs hazard exists for.
        JSONObject inferIn = wf.getJSONObject("12").getJSONObject("inputs");
        assertLink("MoGe reads the loader's IMAGE (frames, out 0)",
                "12", "10", 0, wf, "image");
        assertLink("MoGe reads this graph's own model loader",
                "12", "11", 0, wf, "moge_model");

        // BOTH renders read THIS graph's inference, not the still shape's node id.
        // Sharing the render factory is deliberate, and hardcoding the still id in it
        // is the bug that would have wired the video's depth to a node that is not in
        // this graph.
        assertLink("depth render reads the video inference",
                "13", "12", 0, wf, "moge_geometry");
        assertLink("normal render reads the video inference",
                "15", "12", 0, wf, "moge_geometry");

        // The forced outputs, which are the whole point of asking MoGe at all: `depth`
        // is displacement and only `depth` is. A default change must not silently turn
        // the depth clip into a colourised one.
        assertEquals("depth render asks for depth", "depth",
                wf.getJSONObject("13").getJSONObject("inputs").getString("output"));
        assertEquals("normal render asks for the OpenGL convention", "normal_opengl",
                wf.getJSONObject("15").getJSONObject("inputs").getString("output"));

        // Each combiner reads ITS OWN render, and writes h264 -- not the format list's
        // first entry, which is a GIF and has no reliable frame timing.
        assertLink("depth clip reads the depth render", "14", "13", 0, wf, "images");
        assertLink("normal clip reads the normal render", "16", "15", 0, wf, "images");
        assertEquals("depth clip is h264", "video/h264-mp4",
                wf.getJSONObject("14").getJSONObject("inputs").getString("format"));
        assertEquals("normal clip is h264", "video/h264-mp4",
                wf.getJSONObject("16").getJSONObject("inputs").getString("format"));
        assertEquals("depth clip rate", 24.0,
                wf.getJSONObject("14").getJSONObject("inputs").getDouble("frame_rate"), 1e-9);
        assertEquals("normal clip rate", 24.0,
                wf.getJSONObject("16").getJSONObject("inputs").getDouble("frame_rate"), 1e-9);
    }

    /**
     * Two prefixes, or one file overwrites the other and the depth half silently
     * disappears.
     *
     * <p>{@code MoGeRender} selects one output, so the two clips cannot come from one
     * node — and losing the normal half is the failure that is invisible, which is
     * exactly how it already behaved once.
     */
    @Test
    public void theTwoClipsAreToldApartByTheirPrefixes() throws JSONException {
        JSONObject wf = videoWf();
        String depth = wf.getJSONObject("14").getJSONObject("inputs").getString("filename_prefix");
        String normal = wf.getJSONObject("16").getJSONObject("inputs").getString("filename_prefix");
        assertTrue("the depth clip keeps the still path's prefix, so the two fetch "
                        + "routines cannot confuse each other's files",
                depth.startsWith(ComfyWorkflow.DEPTH_FILE_PREFIX));
        assertTrue(normal.startsWith(ComfyWorkflow.NORMAL_FILE_PREFIX));
        assertFalse("two writers, one name: the second overwrites the first",
                depth.equals(normal));
        assertTrue("and both are stamped, or a re-run's files cannot be told from "
                + "a previous run's", depth.endsWith(String.valueOf(TS))
                        && normal.endsWith(String.valueOf(TS)));
    }

    /**
     * The video shape is a THIRD graph, structurally distinct from both still shapes.
     *
     * <p>Asserted as node counts and as the absence of both still loaders, because a
     * single-node builder that grew a video branch would keep passing every other
     * test here while shipping a graph whose frames never reached the sampler.
     */
    @Test
    public void theVideoShapeIsNotTheStillShape() throws JSONException {
        JSONObject video = videoWf();
        JSONObject moge = moGeWf("moge_3_vitg_fp16.safetensors");

        // Node COUNT cannot tell these two apart, which is worth stating because it
        // was the assertion written first. Both are seven nodes: the video graph
        // replaces LoadImage with the video loader and each SaveImage with a
        // VideoCombine, and nothing is added or removed. A count is only a
        // discriminator when the shapes genuinely differ in size -- and asserting one
        // that happens to hold for the wrong reason is a guard that cannot fail.
        // EIGHT now, not seven: the third combiner is the colour clip's writer, and
        // it is the node that makes the three clips one decode. (The node-count note
        // below survives this edit: the point was never that the shapes match in
        // size, it is that size cannot DISTINGUISH them -- and one more node still
        // cannot.)
        assertEquals("both shapes have the MoGe middle in common; size still proves nothing",
                moge.length() + 1, video.length());

        // What does distinguish them is the CLASSES, so that is what is pinned.
        assertFalse("the video graph must not load a still",
                video.toString().contains("\"LoadImage\""));
        assertFalse("nor save frames -- it writes clips, not PNGs",
                video.toString().contains("SaveImage"));
        assertTrue("it must load the video",
                video.toString().contains(ComfyWorkflow.VIDEO_LOADER));
        // THREE combines now, not two: depth, normal, and the COLOUR that used to be
        // the original upload. A colour track taken from the source has a different
        // frame count from the depth's capped batch, so the two loop on different
        // clocks and drift at every seam -- while the relief stays behind. Writing the
        // colour back out of the same loader's batch makes all three one decode: one
        // frame count, one rate, one duration, one loop.
        assertTrue("three combines -- depth, normal, and the colour straight off the loader",
                countOf(video.toString(), ComfyWorkflow.VIDEO_COMBINE) == 3);
        // And the shared middle is still the MoGe pipeline, not a second copy of it.
        assertTrue(video.toString().contains(ComfyWorkflow.MOGE_INFERENCE));
        assertEquals("one inference for the whole batch",
                1, countOf(video.toString(), ComfyWorkflow.MOGE_INFERENCE));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
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
