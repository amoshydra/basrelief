package com.amoshydra.basrelief.core.depth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Builds the ComfyUI graph the Depth AI page submits, plus the small pure
 * helpers that go with it.
 *
 * <p>This was extracted verbatim out of {@code DepthGenDialog} so it could be
 * tested. Nothing here touches the Android framework — the only outside type is
 * {@code org.json}, which the platform provides at runtime and which is a
 * {@code testImplementation} dependency here.
 *
 * <p>The node wiring is the part worth pinning. The graph is joined by literal
 * {@code ["<node id>", <output index>]} references, and an off-by-one there
 * produces a graph ComfyUI accepts but renders from the wrong tensor — which
 * surfaces as a blank or unblended depth map rather than an error.
 *
 * <p><b>Two graph shapes.</b> A classical depth node (Depth-Anything V1/V2, the
 * preprocessors) is one node that takes an image and emits an image, so the
 * graph is {@code LoadImage → <node> → SaveImage}. MoGe is a pipeline rather
 * than a node — a loader, an inference step and a renderer — so it gets its own
 * builder and its own five-node graph. {@link #buildWorkflow} picks between them
 * and the two shapes are kept structurally distinct on purpose; collapsing them
 * would put the pipeline's wires on the single-node path.
 */
public final class ComfyWorkflow {

    private ComfyWorkflow() {
    }

    // --------------------------------------------------------------- node ids
    //
    // Two shapes, so two disjoint sets of ids. They may share the string "1"
    // for LoadImage because every graph rewrites its own ids; the constants are
    // named for the shape they belong to so a future edit cannot wire one
    // shape's id into the other's graph.

    /** Node id of the {@code LoadImage} node; every shape starts from it. */
    private static final String LOAD_ID = "1";
    /** Node id of the single depth node; {@code SaveImage} reads its output. */
    private static final String DEPTH_ID = "2";
    /** Node id of the single-node shape's {@code SaveImage}. */
    private static final String SAVE_ID = "3";

    /** Node id of the MoGe loader. */
    private static final String MOGE_LOAD_ID = "2";
    /** Node id of {@code MoGeInference}; {@code MoGeRender} reads its output. */
    private static final String MOGE_INFER_ID = "3";
    /** Node id of the depth {@code MoGeRender}; its {@code SaveImage} reads it. */
    private static final String MOGE_RENDER_ID = "4";
    /** Node id of the depth shape's {@code SaveImage}. */
    private static final String MOGE_SAVE_ID = "5";
    /**
     * Node id of the normal {@code MoGeRender}.
     *
     * <p>A second renderer, not a second output on the first: {@code MoGeRender}
     * selects exactly one of its five outputs through its {@code output} input, so
     * "depth and normal from one inference" is two cheap render nodes sharing the
     * expensive {@link #MOGE_INFER_ID}. ComfyUI caches the inference, so it runs
     * once.
     */
    private static final String MOGE_NORMAL_RENDER_ID = "6";
    /** Node id of the normal {@code SaveImage}. */
    private static final String MOGE_NORMAL_SAVE_ID = "7";

    /** The three classes that make up the MoGe pipeline, in order. */
    static final String MOGE_LOADER = "LoadMoGeModel";
    static final String MOGE_INFERENCE = "MoGeInference";
    static final String MOGE_RENDER = "MoGeRender";

    // ---- the VIDEO shape's own ids.
    //
    // A THIRD set, disjoint from both still shapes, and that is the point: the video
    // graph is the same MoGe pipeline with a video loader in front and a video
    // combiner behind, so every id it shares with the still pipeline would be one
    // more wire to get wrong silently. Its own ids mean a wire into the still shape
    // cannot compile against this one.

    /** {@code VHS_LoadVideoPath}: the frames, as one IMAGE batch. */
    private static final String VLOAD_ID = "10";
    /** The MoGe loader — same class as the still shape, its own id here. */
    private static final String V_MOGE_LOAD_ID = "11";
    /** {@code MoGeInference}: ONE inference for the whole batch. */
    private static final String V_MOGE_INFER_ID = "12";
    /** {@code MoGeRender(output=depth)}, then {@code VHS_VideoCombine}. */
    private static final String V_MOGE_RENDER_ID = "13";
    private static final String V_DEPTH_COMBINE_ID = "14";
    /** {@code MoGeRender(output=normal_opengl)}, then its own combiner. */
    private static final String V_MOGE_NORMAL_RENDER_ID = "15";
    private static final String V_NORMAL_COMBINE_ID = "16";

    static final String VIDEO_LOADER = "VHS_LoadVideo";
    static final String VIDEO_COMBINE = "VHS_VideoCombine";


    /**
     * The {@code MoGeRender.output} the still path asks for.
     *
     * <p>This is the one MoGe input the builder deliberately overrides rather
     * than taking from {@code /object_info}: the still relief needs
     * <em>displacement</em>, and of the five renders only {@code depth} is one.
     * {@code mask} is a silhouette, {@code normal_opengl}/{@code normal_directx}
     * are normal maps and {@code depth_colored} is a colourised depth. The
     * server's own default happens to be {@code depth}, but the app's need is
     * fixed, so it is stated here and tested rather than left to a default that
     * could change under us.
     */
    static final String MOGE_STILL_OUTPUT = "depth";

    /**
     * The second {@code MoGeRender.output} the still path asks for: the normal map
     * that shades the relief.
     *
     * <p><b>{@code normal_opengl}, not {@code normal_directx}.</b> The renderer's
     * normals are OpenGL-convention (+Y up, +Z toward the viewer); OpenGL and
     * DirectX differ only in the green channel, and picking the wrong one inverts
     * the vertical lighting cue. This is a device-only check: it cannot be settled
     * off the phone.
     *
     * <p>It rides the same {@code MoGeInference} as {@link #MOGE_STILL_OUTPUT}: the
     * inference is the expensive part, and the two renders are nearly free.
     */
    public static final String MOGE_NORMAL_OUTPUT = "normal_opengl";

    private static final int FIRST_OUTPUT = 0;

    /**
     * The depth {@code SaveImage}'s prefix. Public because the fetch path tells the
     * returned files apart by it: two {@code SaveImage} nodes run in one prompt and
     * the history names them by filename, not by node.
     */
    public static final String DEPTH_FILE_PREFIX = "depthmap_";

    /**
     * The normal {@code SaveImage}'s prefix. See {@link #DEPTH_FILE_PREFIX}.
     *
     * <p>Distinct from the depth prefix on purpose: the second output the fetch
     * path does not expect is the failure that is silent, so the file names are the
     * association and a test pins them.
     */
    public static final String NORMAL_FILE_PREFIX = "normal_";
    /**
     * The prefix of the depth CLIP, mirroring {@link #DEPTH_FILE_PREFIX}.
     *
     * <p><b>Clip, not frame.</b> The still path saves a PNG per run and the fetch
     * path takes the newest by prefix; here there is one file, an mp4, and it keeps
     * the same leading prefix so the two fetch paths cannot confuse each other's
     * files.
     */
    public static final String DEPTH_CLIP_PREFIX = DEPTH_FILE_PREFIX;
    /** The prefix of the normal clip. See {@link #DEPTH_CLIP_PREFIX}. */
    public static final String NORMAL_CLIP_PREFIX = NORMAL_FILE_PREFIX;

    /**
     * Assemble the ComfyUI graph for the chosen depth model.
     *
     * <p>Dispatches to the single-node shape or the MoGe pipeline shape. The
     * model argument is whichever identifier {@link #usableDepthModels} offered:
     * an ordinary node class name, or one of the MoGe checkpoint filenames.
     *
     * @param objectInfo   the whole {@code /object_info} body, used to discover
     *                     the model's required inputs and their defaults
     * @param model        the chosen model: a ComfyUI class name, one of the
     *                     {@code LoadMoGeModel.model_name} filenames, or
     *                     {@code MoGeInference} for MoGe's own default checkpoint
     * @param uploadedName the name ComfyUI gave the uploaded image
     * @param resolution   value for the node's {@code resolution} input; ignored
     *                     by MoGe, whose size is native to the model
     * @param timestampMs  stamped into {@code filename_prefix}; passed in rather
     *                     than read from the clock so the only moving part in
     *                     the output is pinnable. Note this does NOT make the
     *                     output byte-identical across JVMs — Android's org.json
     *                     and Maven's order object keys differently — so assert
     *                     on the parsed structure, never on the raw string.
     * @return the graph as a JSON string, ready for {@code /prompt}
     * @throws JSONException if {@code objectInfo} is malformed or the model is
     *                       absent from it
     */
    public static String buildWorkflow(String objectInfo, String model, String uploadedName,
                                       int resolution, long timestampMs) throws JSONException {
        JSONObject info = new JSONObject(objectInfo);
        if (isMoGeModel(info, model)) {
            return buildMoGeWorkflow(info, model, uploadedName, timestampMs);
        }

        JSONObject nodeInfo = info.getJSONObject(model);

        JSONObject load = loadImageNode(uploadedName);

        JSONObject depth = new JSONObject();
        depth.put("class_type", model);
        // Everything the node requires, discovered from /object_info — except
        // `image`, which is wired from LoadImage below.
        JSONObject depthIn = discoveredInputs(nodeInfo, "image");
        // The app's resolution slider only overrides a node that declares one,
        // because an input a node does not have would be rejected by the server.
        if (depthIn.has("resolution")) depthIn.put("resolution", Integer.valueOf(resolution));
        // Wired from LoadImage, not from the uploaded filename: the file has
        // already been uploaded, and this is how ComfyUI refers to it in-graph.
        depthIn.put("image", link(LOAD_ID));
        depth.put("inputs", depthIn);

        // Save the DEPTH node's image output (not LoadImage's).
        JSONObject save = saveImageNode(DEPTH_ID, FIRST_OUTPUT, DEPTH_FILE_PREFIX, timestampMs);

        JSONObject wf = new JSONObject();
        wf.put(LOAD_ID, load);
        wf.put(DEPTH_ID, depth);
        wf.put(SAVE_ID, save);
        return wf.toString();
    }

    /**
     * Assemble the seven-node MoGe pipeline:
     *
     * <pre>
     * LoadImage
     *   → LoadMoGeModel(model_name)
     *   → MoGeInference(moge_model, image, resolution_level, …)
     *       ├→ MoGeRender(output=depth)         → SaveImage(depthmap_…)
     *       └→ MoGeRender(output=normal_opengl) → SaveImage(normal_…)
     * </pre>
     *
     * <p>Every scalar input is discovered from {@code /object_info}, so a
     * default the server changes or a step the server adds is honoured. Three
     * inputs cannot come from {@code /object_info} and are wired here by name:
     * {@code LoadMoGeModel}'s {@code moge_model} output feeds
     * {@code MoGeInference.moge_model}, {@code LoadImage}'s image feeds
     * {@code MoGeInference.image}, and {@code MoGeInference}'s geometry feeds each
     * {@code MoGeRender.moge_geometry}. Their values are links, not scalars —
     * the schema can only name their types. Each {@code MoGeRender.output} is also
     * forced, for the reason on {@link #MOGE_STILL_OUTPUT} and
     * {@link #MOGE_NORMAL_OUTPUT}.
     *
     * <p><b>Two renders from one inference.</b> {@code MoGeRender} selects one
     * output, so the displacement and the shading cannot come from one node; both
     * render nodes read the one {@link #MOGE_INFER_ID}, which ComfyUI caches and so
     * runs once. Losing the normal half is the failure that is silent, which is why
     * the two saver prefixes are distinct and pinned.
     *
     * @param info         the parsed {@code /object_info} body
     * @param model        the checkpoint filename, or {@code MoGeInference} to
     *                     keep the server's discovered default
     */
    static String buildMoGeWorkflow(JSONObject info, String model, String uploadedName,
                                    long timestampMs) throws JSONException {
        JSONObject loaderInfo = info.getJSONObject(MOGE_LOADER);
        JSONObject inferInfo = info.getJSONObject(MOGE_INFERENCE);
        JSONObject renderInfo = info.getJSONObject(MOGE_RENDER);

        JSONObject load = loadImageNode(uploadedName);

        JSONObject loader = new JSONObject();
        loader.put("class_type", MOGE_LOADER);
        JSONObject loaderIn = discoveredInputs(loaderInfo);
        // The chosen checkpoint wins over the loader's discovered default. The
        // picker offers one entry per `model_name` option, so this is where that
        // choice lands; `MoGeInference` asks for the discovered default instead.
        if (!MOGE_INFERENCE.equals(model)) loaderIn.put("model_name", model);
        loader.put("inputs", loaderIn);

        JSONObject infer = new JSONObject();
        infer.put("class_type", MOGE_INFERENCE);
        JSONObject inferIn = discoveredInputs(inferInfo, "moge_model", "image");
        inferIn.put("moge_model", link(MOGE_LOAD_ID));
        inferIn.put("image", link(LOAD_ID));
        infer.put("inputs", inferIn);

        // Two renderers off the one inference. Built by one factory so the two
        // cannot drift in which scalar inputs they discover or which node they
        // read their geometry from; only the forced output differs.
        JSONObject depthRender = moGeRenderNode(renderInfo, MOGE_STILL_OUTPUT, MOGE_INFER_ID);
        JSONObject normalRender = moGeRenderNode(renderInfo, MOGE_NORMAL_OUTPUT, MOGE_INFER_ID);

        JSONObject depthSave = saveImageNode(MOGE_RENDER_ID, FIRST_OUTPUT,
                DEPTH_FILE_PREFIX, timestampMs);
        JSONObject normalSave = saveImageNode(MOGE_NORMAL_RENDER_ID, FIRST_OUTPUT,
                NORMAL_FILE_PREFIX, timestampMs);

        JSONObject wf = new JSONObject();
        wf.put(LOAD_ID, load);
        wf.put(MOGE_LOAD_ID, loader);
        wf.put(MOGE_INFER_ID, infer);
        wf.put(MOGE_RENDER_ID, depthRender);
        wf.put(MOGE_SAVE_ID, depthSave);
        wf.put(MOGE_NORMAL_RENDER_ID, normalRender);
        wf.put(MOGE_NORMAL_SAVE_ID, normalSave);
        return wf.toString();
    }

    /**
     * Assemble the video graph: the MoGe pipeline with a video loader in front and a
     * video combiner behind each render.
     *
     * <pre>
     * VHS_LoadVideoPath(video, force_rate, …)  ──▶ images, as ONE IMAGE batch
     *   LoadMoGeModel(model_name) ──┐
     *   MoGeInference(moge_model, image) ──▶ geometry for the WHOLE batch
     *       ├→ MoGeRender(output=depth)        → VHS_VideoCombine(depthmap_…)
     *       └→ MoGeRender(output=normal_opengl)→ VHS_VideoCombine(normal_…)
     * </pre>
     *
     * <p><b>One inference for every frame, because an IMAGE is a batch.</b> This is
     * the whole reason the graph is four lines of new wiring rather than a new
     * pipeline: {@code MoGeInference.image} takes a batch, so a clip's frames go in
     * as one node and come back as one geometry, and per-frame is only an
     * implementation detail of the batch size. The alternative — extracting frames
     * and running the existing still graph once per frame — costs one round trip,
     * one prompt and one model load per frame, and a clip is dozens of frames.
     *
     * <p><b>Out[0] of the loader is the IMAGE.</b> The loader has four outputs and
     * two of them are typed {@code IMAGE}: the frames and the mask. Guessing wrong is
     * the silent failure this class exists to prevent — a mask fed to MoGe produces a
     * plausible depth map of nothing, and nothing anywhere says so — so the index is
     * pinned by test rather than trusted.
     *
     * <p><b>{@code VHS_LoadVideo}, NOT {@code VHS_LoadVideoPath}.</b> The two look
     * interchangeable and only one of them works: {@code Path} validates its argument
     * as a path on the <b>server's</b> filesystem and rejects the name of an uploaded
     * file outright — measured, {@code "video - Invalid file path: up.mp4"} — while
     * {@code LoadVideo} takes a filename from the combo the server builds out of its
     * input directory, which is where {@code /upload/image} puts it. Since the depth
     * server is reached over HTTP and is not on the phone, there is no path we could
     * hand the other node. Verified end to end: both clips generate.
     *
     * <p><b>Both clips come out of one graph</b> for the reason the still path emits
     * both: {@code MoGeRender} selects exactly one output, so depth and normal are two
     * render nodes sharing one cached inference, and two combiners so neither file
     * overwrites the other.
     *
     * @param fps        frames per second of the OUTPUT clips. It must be the source
     *                   clip's rate, or the depth drifts against the colour by
     *                   exactly this factor — the sync bug, at generation time
     *                   instead of playback time.
     * @param frameCap   how many frames to read, so a long video does not become an
     *                   unbounded graph. {@code 0} means the server's own default.
     */
    static String buildVideoWorkflow(JSONObject info, String model, String videoPath,
                                     long timestampMs, float fps, int frameCap,
                                     int width, int height) throws JSONException {
        JSONObject vloadInfo = info.getJSONObject(VIDEO_LOADER);
        JSONObject combineInfo = info.getJSONObject(VIDEO_COMBINE);
        JSONObject loaderInfo = info.getJSONObject(MOGE_LOADER);
        JSONObject inferInfo = info.getJSONObject(MOGE_INFERENCE);
        JSONObject renderInfo = info.getJSONObject(MOGE_RENDER);

        JSONObject vload = new JSONObject();
        vload.put("class_type", VIDEO_LOADER);
        JSONObject vloadIn = discoveredInputs(vloadInfo);
        // A path on the SERVER's filesystem, which is why the clip is uploaded first
        // -- exactly as the still path uploads its PNG before asking for a graph.
        vloadIn.put("video", videoPath);
        // The source rate is asked for explicitly rather than left to the loader's
        // default, because the OUTPUT clips are written at this rate too and the two
        // have to be the same number or the depth slides against the colour.
        vloadIn.put("force_rate", (double) fps);
        if (frameCap > 0) vloadIn.put("frame_load_cap", frameCap);
        if (width > 0) vloadIn.put("custom_width", width);
        if (height > 0) vloadIn.put("custom_height", height);
        vload.put("inputs", vloadIn);

        JSONObject loader = new JSONObject();
        loader.put("class_type", MOGE_LOADER);
        JSONObject loaderIn = discoveredInputs(loaderInfo);
        if (!MOGE_INFERENCE.equals(model)) loaderIn.put("model_name", model);
        loader.put("inputs", loaderIn);

        JSONObject infer = new JSONObject();
        infer.put("class_type", MOGE_INFERENCE);
        JSONObject inferIn = discoveredInputs(inferInfo, "moge_model", "image");
        inferIn.put("moge_model", link(V_MOGE_LOAD_ID));
        // Output 0 of the loader -- the FRAMES. See the class note on the mask.
        inferIn.put("image", link(VLOAD_ID, FIRST_OUTPUT));
        infer.put("inputs", inferIn);

        // The same two renders, the same forced outputs, the same discovered
        // scalars as the still path -- one factory, so the video shape cannot drift
        // into asking for depth_colored because someone edited one builder.
        JSONObject depthRender = moGeRenderNode(renderInfo, MOGE_STILL_OUTPUT, V_MOGE_INFER_ID);
        JSONObject normalRender = moGeRenderNode(renderInfo, MOGE_NORMAL_OUTPUT, V_MOGE_INFER_ID);

        JSONObject depthCombine = videoCombineNode(combineInfo, V_MOGE_RENDER_ID,
                FIRST_OUTPUT, DEPTH_CLIP_PREFIX, timestampMs, fps);
        JSONObject normalCombine = videoCombineNode(combineInfo, V_MOGE_NORMAL_RENDER_ID,
                FIRST_OUTPUT, NORMAL_CLIP_PREFIX, timestampMs, fps);

        JSONObject wf = new JSONObject();
        wf.put(VLOAD_ID, vload);
        wf.put(V_MOGE_LOAD_ID, loader);
        wf.put(V_MOGE_INFER_ID, infer);
        wf.put(V_MOGE_RENDER_ID, depthRender);
        wf.put(V_DEPTH_COMBINE_ID, depthCombine);
        wf.put(V_MOGE_NORMAL_RENDER_ID, normalRender);
        wf.put(V_NORMAL_COMBINE_ID, normalCombine);
        return wf.toString();
    }

    /**
     * One {@code VHS_VideoCombine}, discovering every scalar the server offers.
     *
     * <p><b>h264 in an mp4</b>, forced rather than discovered: the format list's
     * first entry is a GIF, and a GIF has no reliable frame timing, which would put
     * the depth and the colour a frame apart for the whole clip. The container is the
     * renderer's contract — {@code MediaExtractor} reading it — so it is stated here
     * and pinned by test.
     */
    private static JSONObject videoCombineNode(JSONObject combineInfo, String imagesNodeId,
                                              int imagesOutputIndex, String prefix,
                                              long timestampMs, float fps)
            throws JSONException {
        JSONObject n = new JSONObject();
        n.put("class_type", VIDEO_COMBINE);
        JSONObject in = discoveredInputs(combineInfo, "images");
        in.put("images", link(imagesNodeId, imagesOutputIndex));
        in.put("frame_rate", (double) fps);
        in.put("filename_prefix", prefix + timestampMs);
        in.put("format", "video/h264-mp4");
        n.put("inputs", in);
        return n;
    }

    /**
     * One {@code MoGeRender} selecting one of its outputs.
     *
     * <p>Every renderer reads the same inference, so it is built twice per graph
     * with only {@code output} differing. The factory exists so a future scalar the
     * server adds reaches both renders rather than one.
     */
    private static JSONObject moGeRenderNode(JSONObject renderInfo, String output,
                                              String geometryNodeId)
            throws JSONException {
        JSONObject render = new JSONObject();
        render.put("class_type", MOGE_RENDER);
        JSONObject renderIn = discoveredInputs(renderInfo, "moge_geometry");
        // WHICH inference, passed in rather than closed over. Sharing this factory is
        // the point -- so a scalar the server adds reaches both shapes at once -- but
        // hardcoding the still shape's id here would silently wire the video's two
        // renders to a node that does not exist in the video graph, which ComfyUI
        // reports as a missing node rather than as a wrong depth.
        renderIn.put("moge_geometry", link(geometryNodeId));
        renderIn.put("output", output);
        render.put("inputs", renderIn);
        return render;
    }

    // --------------------------------------------------------------- graph pieces

    private static JSONObject loadImageNode(String uploadedName) throws JSONException {
        JSONObject load = new JSONObject();
        load.put("class_type", "LoadImage");
        load.put("inputs", new JSONObject().put("image", uploadedName));
        return load;
    }

    /**
     * The one {@code SaveImage} factory for both shapes and both MoGe outputs.
     * Sharing it keeps the node id reference, the output index and the filename
     * prefix in one place, so the MoGe path cannot quietly name a different file
     * than the classical one, nor the depth saver the normal prefix.
     */
    private static JSONObject saveImageNode(String sourceId, int outputIndex,
                                            String prefix, long timestampMs)
            throws JSONException {
        JSONObject save = new JSONObject();
        save.put("class_type", "SaveImage");
        JSONObject saveIn = new JSONObject();
        saveIn.put("images", new JSONArray().put(sourceId).put(outputIndex));
        saveIn.put("filename_prefix", prefix + timestampMs);
        save.put("inputs", saveIn);
        return save;
    }

    private static JSONArray link(String nodeId) {
        return link(nodeId, FIRST_OUTPUT);
    }

    private static JSONArray link(String nodeId, int outputIndex) {
        return new JSONArray().put(nodeId).put(outputIndex);
    }

    // --------------------------------------------------------------- input discovery

    /**
     * A node's required inputs, or an empty object when it declares none.
     *
     * <p>Some ComfyUI builds put them under {@code input.required} and some
     * under a bare {@code required}; both are accepted, as in the builder.
     */
    private static JSONObject requiredInputs(JSONObject nodeInfo) {
        if (nodeInfo == null) return new JSONObject();
        JSONObject input = nodeInfo.optJSONObject("input");
        JSONObject required = input != null ? input.optJSONObject("required") : null;
        if (required == null) required = nodeInfo.optJSONObject("required"); // fallback
        return required != null ? required : new JSONObject();
    }

    private static JSONArray requiredSpec(JSONObject nodeInfo, String name) {
        return requiredInputs(nodeInfo).optJSONArray(name);
    }

    /**
     * The scalar values a node's required inputs need, from {@code /object_info}.
     *
     * <p>Inputs named in {@code wired} are skipped: they are links to another
     * node, and the caller supplies the link. Every other required input gets
     * the node's own default, or a value derived from its declaration — never a
     * drop, because ComfyUI rejects a prompt missing a required input.
     *
     * <p>Two combo spellings reach here. The older one holds the option array
     * itself in the type slot — {@code [["small","large"], {"default":"large"}]}
     * — and the newer one names the type and nests the options in the options
     * dict — {@code ["COMBO", {"options":["small","large"]}]}. Both take the
     * explicit default when there is one; with no default the older form falls
     * back to its first option and the newer form now does the same, which is
     * what {@code LoadMoGeModel.model_name} needs.
     */
    private static JSONObject discoveredInputs(JSONObject nodeInfo, String... wired)
            throws JSONException {
        JSONObject required = requiredInputs(nodeInfo);
        JSONObject out = new JSONObject();
        Iterator<String> it = required.keys();
        while (it.hasNext()) {
            String inName = it.next();
            if (isWired(inName, wired)) continue;
            JSONArray spec = required.optJSONArray(inName);
            if (spec == null || spec.length() == 0) continue;
            // A combo's type slot may be the option array itself, not a type
            // name. Calling getString(0) on it throws, so a required combo used
            // to make the builder fail before the prompt was ever sent.
            Object typeObj = spec.opt(0);
            String type = typeObj instanceof String ? (String) typeObj : "";
            Object def = spec.length() > 1 ? spec.opt(1) : null;
            Object value;
            if (def instanceof JSONObject) {
                JSONObject opts = (JSONObject) def;
                if (opts.has("default")) {
                    value = opts.get("default");
                } else if (opts.optJSONArray("options") != null
                        && opts.optJSONArray("options").length() > 0) {
                    value = opts.optJSONArray("options").opt(0);
                } else {
                    value = fallbackFor(typeObj);
                }
            } else if (def == null) {
                value = fallbackFor(typeObj); // never drop a required input
            } else {
                value = def;
            }
            out.put(inName, value);
        }
        return out;
    }

    private static boolean isWired(String name, String[] wired) {
        for (String w : wired) {
            if (w.equals(name)) return true;
        }
        return false;
    }

    /** A safe value for a required input the caller gave no default for. */
    static Object defaultForType(String type) {
        if (type.equals("INT")) return 0;
        if (type.equals("FLOAT")) return 0.0;
        if (type.equals("BOOLEAN")) return true;
        return "";
    }

    /**
     * A value for a required input with no explicit default.
     *
     * <p>A combo declares no type name; its slot holds the option array, and the
     * first option is the only value guaranteed to be valid. Anything else falls
     * back to {@link #defaultForType}, with an empty string for a missing type.
     */
    private static Object fallbackFor(Object typeObj) {
        if (typeObj instanceof JSONArray) {
            JSONArray options = (JSONArray) typeObj;
            return options.length() > 0 ? options.opt(0) : "";
        }
        return defaultForType(typeObj instanceof String ? (String) typeObj : "");
    }

    // --------------------------------------------------------------- model filter

    /**
     * The server's nodes and MoGe checkpoints that this app can build a graph
     * from, sorted by name.
     *
     * <p>This is the chooser's whole candidate list: the name heuristic that
     * finds nodes worth looking at, {@link #canBuildWorkflow} that removes the
     * ones the builder cannot drive, and the MoGe pipeline's checkpoints. It
     * lives here, not in the dialog, because it is a pure function of the
     * server's JSON and belongs under test.
     *
     * <p>MoGe is not a single node, so it is not found by the node loop. Its
     * entries are one per {@code LoadMoGeModel.model_name} option — discovered
     * from {@code /object_info}, never a hard-coded list — and each is a value
     * {@link #buildWorkflow} expands into the pipeline.
     *
     * @param objectInfo the whole {@code /object_info} body
     * @return identifiers the builder can turn into a graph: ordinary depth node
     *         class names, and MoGe checkpoint filenames
     */
    public static List<String> usableDepthModels(JSONObject objectInfo) {
        List<String> usable = new ArrayList<String>();
        Iterator<String> it = objectInfo.keys();
        while (it.hasNext()) {
            String name = it.next();
            if (!looksLikeDepthModel(name)) continue;
            JSONObject node = objectInfo.optJSONObject(name);
            if (node != null && canBuildWorkflow(node)) usable.add(name);
        }
        usable.addAll(moGeCheckpoints(objectInfo));
        Collections.sort(usable);
        return usable;
    }

    /**
     * Whether the server names any depth-like node at all, usable or not.
     *
     * <p>Exists so an empty list can be explained rather than misreported: "No
     * depth models on this server" is false when the server has plenty and this
     * app simply cannot drive any of them.
     */
    public static boolean hasDepthNode(JSONObject objectInfo) {
        Iterator<String> it = objectInfo.keys();
        while (it.hasNext()) {
            if (looksLikeDepthModel(it.next())) return true;
        }
        return false;
    }

    /** The name heuristic the panel has always used to find depth nodes. */
    static boolean looksLikeDepthModel(String name) {
        String lower = name.toLowerCase();
        return lower.contains("depth") || lower.contains("midas") || lower.contains("zoe")
                || lower.contains("moge");
    }

    /**
     * The MoGe checkpoints the server offers, in the order {@code /object_info}
     * lists them.
     *
     * <p>Only returned when the whole three-node pipeline is present: a server
     * with {@code MoGeInference} but no loader or renderer could not run the
     * graph the builder emits, and offering it would turn a model choice into a
     * failed run. Empty otherwise, which is what leaves a server without MoGe
     * exactly as it was before.
     */
    static List<String> moGeCheckpoints(JSONObject objectInfo) {
        List<String> out = new ArrayList<String>();
        if (!hasMoGePipeline(objectInfo)) return out;
        JSONArray spec = requiredSpec(objectInfo.optJSONObject(MOGE_LOADER), "model_name");
        if (spec == null) return out;
        JSONArray options = spec.optJSONArray(0);
        if (options == null) {
            Object def = spec.opt(1);
            if (def instanceof JSONObject) options = ((JSONObject) def).optJSONArray("options");
        }
        if (options == null) return out;
        for (int i = 0; i < options.length(); i++) {
            Object option = options.opt(i);
            if (option instanceof String) out.add((String) option);
        }
        return out;
    }

    /**
     * Whether a model argument names the MoGe pipeline rather than an ordinary node.
     *
     * <p>Public because it is also the answer to "does this run also emit a
     * normal": the MoGe graph does, and any single-node model does not. The fetch
     * path records it when the run is submitted, so a missing normal on a run that
     * was supposed to have one fails loudly instead of going quietly flat.
     */
    public static boolean isMoGeModel(JSONObject info, String model) {
        if (model == null || !hasMoGePipeline(info)) return false;
        // MoGeInference on its own asks for the discovered default checkpoint.
        if (MOGE_INFERENCE.equals(model)) return true;
        // A real top-level node is an ordinary single-node model, whatever it
        // is called, so it must not be stolen by checkpoint matching.
        if (info.has(model)) return false;
        return moGeCheckpoints(info).contains(model);
    }

    private static boolean hasMoGePipeline(JSONObject info) {
        return info.optJSONObject(MOGE_LOADER) != null
                && info.optJSONObject(MOGE_INFERENCE) != null
                && info.optJSONObject(MOGE_RENDER) != null;
    }

    /**
     * Whether {@link #buildWorkflow} can drive this node from an uploaded image.
     *
     * <p>The rule mirrors the single-node builder exactly, and that is the point:
     * a node the chooser keeps must be one the builder can actually submit. The
     * builder supplies a value for the required input named {@code image} by
     * wiring it to {@code LoadImage}, and for every other required input it sends
     * the node's own default or a type-appropriate plain value. It cannot produce
     * a link, so a required input of a custom handle type — {@code MODEL},
     * {@code DAMODEL}, {@code LATENT}, {@code MASK}, a second {@code IMAGE} —
     * would be sent as an empty string and refused by the server. A required
     * combo is a plain value the builder can pick, so it counts as satisfiable.
     * The node must also emit an image as output 0, because {@code SaveImage}
     * always reads that output.
     *
     * <p>MoGe's own nodes deliberately fail this: each is one stage of a
     * pipeline, not a single image-to-image step, so it is {@link #moGeCheckpoints}
     * rather than this predicate that offers MoGe to the chooser.
     *
     * @param nodeInfo one node's entry from {@code /object_info}
     * @return true when a single image plus that node's defaults is a valid graph
     */
    public static boolean canBuildWorkflow(JSONObject nodeInfo) {
        if (nodeInfo == null) return false;
        JSONObject input = nodeInfo.optJSONObject("input");
        JSONObject required = input != null ? input.optJSONObject("required") : null;
        if (required == null) required = nodeInfo.optJSONObject("required"); // fallback
        if (required == null) return false;

        // SaveImage is hard-wired to output 0, so that output must be the image.
        JSONArray output = nodeInfo.optJSONArray("output");
        if (output != null && output.length() > 0 && !"IMAGE".equals(output.opt(0))) {
            return false;
        }

        boolean hasImage = false;
        Iterator<String> it = required.keys();
        while (it.hasNext()) {
            String name = it.next();
            JSONArray spec = required.optJSONArray(name);
            if (spec == null || spec.length() == 0) return false;
            Object type = spec.opt(0);
            if (name.equals("image")) {
                // The builder wires only an IMAGE-typed input named `image`.
                if (!"IMAGE".equals(type)) return false;
                hasImage = true;
            } else if (!(type instanceof JSONArray) && !isPlainInputType(type)) {
                // A handle type can only come from another node, so the builder
                // would put "" here and the server would reject the prompt.
                return false;
            }
        }
        return hasImage;
    }

    /** Types whose required input the builder can fill with a literal value. */
    private static boolean isPlainInputType(Object type) {
        if (!(type instanceof String)) return false;
        String t = (String) type;
        return t.equals("INT") || t.equals("FLOAT") || t.equals("BOOLEAN")
                || t.equals("STRING") || t.equals("COMBO");
    }

    /** Turn a raw ComfyUI class name into something human-readable. */
    public static String friendlyName(String name) {
        if (name.contains("DepthAnythingV2")) return "Depth Anything V2";
        if (name.contains("DepthAnything")) return "Depth Anything V1";
        String lower = name.toLowerCase();
        if (lower.contains("midas")) return "MiDaS";
        if (lower.contains("zoe")) return "ZoeDepth";
        if (lower.contains("moge")) return friendlyMoGe(name);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                b.append(' ');
                continue;
            }
            // Split camelCase, but not after a character we just turned into a
            // space — "Load-Checkpoint" would otherwise come out as "Load
            //  Checkpoint" with a double space, which is visible in the model
            // dropdown. Runs of capitals stay together, so GLES20 survives.
            boolean prevUpper = i > 0 && Character.isUpperCase(name.charAt(i - 1));
            boolean alreadySpaced = b.length() > 0 && b.charAt(b.length() - 1) == ' ';
            if (i > 0 && Character.isUpperCase(c) && !prevUpper && !alreadySpaced) {
                b.append(' ');
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Whether a chooser entry is a MoGe checkpoint, which is also the entries that
     * produce a normal map.
     *
     * <p>Used to preselect the model that can answer "Generate normals" — the
     * picker's default would otherwise be the alphabetically first node, which is
     * never MoGe because capitalised class names sort before the lowercase
     * checkpoint filenames. It is a preference, not a filter: every entry stays in
     * the list and the user can choose any of them.
     */
    public static boolean isMoGeCheckpoint(String name) {
        return name != null && name.toLowerCase().contains("moge");
    }

    /**
     * Human names for the MoGe classes and the checkpoint filenames.
     *
     * <p>The picker's MoGe entries are filenames, because
     * {@code LoadMoGeModel.model_name} is what picks the model, so this has to
     * read both filenames and class names. A filename is split on its separators
     * so {@code moge_3_vitg_fp16.safetensors} reads as a model rather than as a
     * file.
     */
    static String friendlyMoGe(String name) {
        if (name.contains(MOGE_LOADER)) return "Load MoGe Model";
        if (name.contains(MOGE_RENDER)) return "MoGe Render";
        if (name.contains("MoGePanoramaInference")) return "MoGe Panorama Inference";
        if (name.contains(MOGE_INFERENCE)) return "MoGe Inference";
        if (name.contains("MoGePointMapToMesh")) return "MoGe Point Map to Mesh";
        if (name.contains("MoGeGeometryToFOV")) return "MoGe Geometry to FOV";
        return moGeFileLabel(name);
    }

    private static String moGeFileLabel(String name) {
        String base = name;
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        String[] parts = base.split("[_\\-]+");
        StringBuilder b = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (part.equalsIgnoreCase("moge")) {
                b.append("MoGe");
                continue;
            }
            String p = part.toLowerCase();
            if (p.length() == 4 && p.startsWith("vit")) {
                // vitg/vitl/vitb/vits -> ViT-G/ViT-L/ViT-B/ViT-S
                b.append("ViT-").append(Character.toUpperCase(p.charAt(3)));
            } else {
                b.append(p);
            }
        }
        return b.toString();
    }

    /**
     * Pull a readable error out of {@code history.status.messages}.
     *
     * @return the first {@code execution_error} as {@code "Type: message"},
     *         or a generic fallback naming the ComfyUI console
     */
    public static String extractExecutionError(JSONObject status) {
        try {
            JSONArray messages = status.getJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) {
                JSONArray pair = messages.getJSONArray(i);
                if (pair.length() >= 2 && pair.getString(0).equals("execution_error")) {
                    JSONObject err = pair.getJSONObject(1);
                    String type = err.optString("exception_type", "");
                    String msg = err.optString("exception_message", "");
                    if (!type.isEmpty()) return type + ": " + msg;
                }
            }
        } catch (JSONException ignored) {
            // fall through to the generic message
        }
        return "execution error (see ComfyUI console)";
    }

    /**
     * Reduce a user-typed base URL to just {@code host:port} for display.
     * Strips any scheme and any path, so {@code http://box:8188/queue} and
     * {@code box:8188} both show as {@code box:8188}.
     */
    public static String hostPort(String base) {
        String s = base;
        if (s.startsWith("http://")) s = s.substring(7);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }
}
