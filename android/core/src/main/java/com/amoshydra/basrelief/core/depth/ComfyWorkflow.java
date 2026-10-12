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
 * <p><b>Three graph shapes.</b> A classical depth node (Depth-Anything V1/V2, the
 * preprocessors) is one node that takes an image and emits an image, so the
 * graph is {@code LoadImage → <node> → SaveImage}. MoGe is a pipeline rather
 * than a node — a loader, an inference step and a renderer — so it gets its own
 * builder and its own seven-node graph. {@link #buildWorkflow} picks between
 * them and the two still shapes are kept structurally distinct on purpose;
 * collapsing them would put the pipeline's wires on the single-node path. The
 * VIDEO shape is a third builder again: {@code LoadMoGeModel →
 * MoGeVideoStream}, a custom node that streams frames through MoGe and writes
 * the depth and normal clips itself — see {@link #buildVideoStreamWorkflow}.
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

    // ---- the STREAM shape's own ids.
    //
    // A THIRD set, disjoint from both still shapes, and that is the point: the stream
    // shape shares the MoGe loader with the still pipeline, so a wire into the still
    // shape's ids would be one more way to be silently wrong. Its own ids mean a wire
    // into the still shapes cannot compile against this one.

    /** {@code LoadMoGeModel}: the checkpoint, whose MOGE_MODEL feeds the stream node. */
    private static final String S_MOGE_LOAD_ID = "1";
    /** {@code MoGeVideoStream}: the loop, the inference and the two clips, one node. */
    private static final String S_STREAM_ID = "2";

    /** The custom node that streams a video through MoGe with bounded memory. */
    public static final String VIDEO_STREAM = "MoGeVideoStream";

    /**
     * The rate cap the generated tracks are written at, in frames per second.
     *
     * <p><b>ONE constant, because the player's sync rests on it.</b> Measured on a
     * Pixel 7 Pro with a 50 fps source: three simultaneous 1080p clip decoders each
     * ran at ~39-40 fps, so the shared clock could not pin them and the relief
     * drifted 0.4-1.0 s from the picture, worst on the heaviest encode. Depth and
     * normal are therefore written at {@code min(source, 30)} -- output frame k is
     * source frame {@code round_half_up(k x src/out)}, the same timeline with fewer
     * samples -- while the colour stays the app's own remux, re-encoded by nobody.
     * 30 because the renderer latches ~30-40 frames/s and samples the depth per
     * draw, so the generated tracks never needed the source's rate. It is stated
     * here and passed explicitly rather than left to the node's default, or a
     * server-side default change would move the rate under the player's measured
     * behaviour. 0 would mean the source's own rate; the app never passes it.
     */
    public static final int STREAM_FPS_CAP = 30;

    /**
     * The long-side cap the generated clips and the node's colour are written at,
     * in pixels.
     *
     * <p><b>ONE constant, because it is the other half of the player's contract.</b>
     * Measured on a Pixel 7 Pro, a 1440x2560 portrait 50 fps source could not be
     * decoded by even ONE of the three streams -- the colour alone fell 0.4-2.4 s
     * behind -- while 1080p-class content held ~40 fps per stream. The node
     * therefore writes the depth and normal tracks, and its own re-encoded colour
     * when the source exceeds the caps, at {@code long_side <= max_long_side}; the
     * app plays the node's colour when it wrote one (see
     * {@code VideoImport.attach}). 1920 keeps the colour under 1080p in its long
     * dimension; 0 would mean no cap, and the app never passes it. Stated here and
     * passed explicitly rather than left to the node's default, because a
     * server-side default change would move a playback dimension the phone's
     * measured behaviour rests on.
     */
    public static final int STREAM_MAX_LONG_SIDE = 1920;

    /**
     * The prefix of every file the stream node writes.
     *
     * <p>The names are the app's whole handle on the run's outputs: the node takes a
     * {@code tag} and derives {@code basrelief_<tag>_depth.mp4},
     * {@code basrelief_<tag>_normal.mp4} and {@code basrelief_<tag>.progress.json}
     * from it, so a client can address all three WITHOUT having seen a response.
     * That is what lets the app fetch a run that finished while the phone was dead
     * and read the sidecar for a run that is still going. The node also derives
     * {@code basrelief_<tag>_color.mp4}, but the app never addresses that one
     * unprompted: it exists only when the source exceeded the playback caps, and
     * the sidecar's {@code color} field is what says so -- an absent name is the
     * answer "the remux is the colour", not a name to guess at.
     */
    public static final String STREAM_FILE_PREFIX = "basrelief_";
    /** The depth clip's suffix; see {@link #STREAM_FILE_PREFIX}. */
    public static final String STREAM_DEPTH_SUFFIX = "_depth.mp4";
    /** The normal clip's suffix; see {@link #STREAM_FILE_PREFIX}. */
    public static final String STREAM_NORMAL_SUFFIX = "_normal.mp4";
    /** The progress sidecar's suffix; see {@link #STREAM_FILE_PREFIX}. */
    public static final String STREAM_PROGRESS_SUFFIX = ".progress.json";

    /**
     * The tag the stream node's filenames are derived from, sanitised by the app's
     * copy of the node's own rule.
     *
     * <p><b>The app sanitises because the app has to NAME the files without asking.</b>
     * The node runs {@code c.isalnum() or c in "-_"} and truncates to 48; the fetch
     * and resume paths derive filenames from the item id and must produce exactly the
     * string the node would, or they would ask for files that do not exist. Item ids
     * are UUIDs and survive this unchanged; the rule exists for a hand-edited or
     * imported index whose id carries a space or punctuation.
     *
     * <p>An id that sanitises to nothing yields the empty tag, and the node then falls
     * back to its own timestamp name, which the app cannot predict — callers use
     * {@link #isStreamTagUsable} to refuse that before submitting rather than fetch
     * names that will never exist.
     */
    public static String streamTag(String itemId) {
        if (itemId == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < itemId.length() && b.length() < 48; i++) {
            char c = itemId.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') b.append(c);
        }
        return b.toString();
    }

    /** Whether a tag is one the node's filenames can be derived from. */
    public static boolean isStreamTagUsable(String tag) {
        return tag != null && !tag.isEmpty();
    }

    /** {@code basrelief_<tag>_depth.mp4}, the depth clip's server-side name. */
    public static String streamDepthName(String tag) {
        return STREAM_FILE_PREFIX + tag + STREAM_DEPTH_SUFFIX;
    }

    /** {@code basrelief_<tag>_normal.mp4}, the normal clip's server-side name. */
    public static String streamNormalName(String tag) {
        return STREAM_FILE_PREFIX + tag + STREAM_NORMAL_SUFFIX;
    }

    /** {@code basrelief_<tag>.progress.json}, the sidecar's server-side name. */
    public static String streamProgressName(String tag) {
        return STREAM_FILE_PREFIX + tag + STREAM_PROGRESS_SUFFIX;
    }

    // ---- the SHARP still shape: one still -> one budgeted cloud.

    /**
     * The custom node that turns one still into a Gaussian splat cloud.
     *
     * <p>Two nodes, one wire: {@code LoadImage -> SharpStill}. The node does the
     * reconstruction (Apple's SHARP) and writes the cloud in the phone's 32 B record
     * format plus a sidecar JSON; see
     * {@code server/comfy-node/basrelief_depth/README.md}. The app addresses both
     * outputs by name from the tag it submitted, so a run can be fetched without
     * having seen a response — the same contract the stream node's files have.
     */
    public static final String SHARP_STILL = "SharpStill";

    /** {@code LoadImage} for the still shape. Every shape starts from it. */
    private static final String SHARP_LOAD_ID = "1";
    /** {@code SharpStill}: the one node that makes the cloud. */
    private static final String SHARP_ID = "2";

    /**
     * The cloud's default budget, in splats: a <b>ceiling on the file</b>, and the
     * one number this flow states rather than discovers.
     *
     * <p>150,000 is the node's own conservative default and the count the
     * performance work measured the phone holding: ~55 fps with culling, ~48 without.
     * The count is the cost, so the LOD ladder and a one-off measured pick come
     * later; v1 ships the flat budget. The node rounds it down to a whole
     * 2048-splat block, so the written count can never exceed it (measured:
     * 150000 -> 149504).
     */
    public static final int SHARP_BUDGET = 150_000;

    /**
     * The dev BUDGET range, in splats: the ladder the user compares by eye.
     *
     * <p>150k is the shipped default and the bottom of the range; 1.2M is the top
     * of the ladder (the measured verdict is 150k soft, 741k right, 1.05M
     * indistinguishable from full -- and on this phone 741k ≈ 18 fps, 1.05M ≈ 13).
     * A stored value is clamped here rather than trusted, the same rule
     * {@link com.amoshydra.basrelief.core.render.Splat#clampEps2d} follows: a pref
     * that cannot claim a count answers the default rather than the node's own
     * 999999 fallback, which is a number this app never chose.
     */
    public static final int SHARP_BUDGET_MIN = 150_000;
    public static final int SHARP_BUDGET_MAX = 1_200_000;

    /** The budget a stored value means. Non-positive and absurd values answer the default. */
    public static int clampSharpBudget(int budget) {
        if (budget <= 0) return SHARP_BUDGET;
        if (budget < SHARP_BUDGET_MIN) return SHARP_BUDGET_MIN;
        if (budget > SHARP_BUDGET_MAX) return SHARP_BUDGET_MAX;
        return budget;
    }

    /**
     * SHARP's inference size. It accepts only {@code 384 + 288k}; the node snaps
     * anything else to the nearest and logs it, so this is a request rather than a
     * promise. 1536 is the size the node's own reference run used.
     */
    public static final int SHARP_INTERNAL_SIZE = 1536;

    /** The cloud's server-side suffix: {@code basrelief_<tag>.splat}. */
    public static final String SHARP_SPLAT_SUFFIX = ".splat";
    /** The sidecar's server-side suffix: {@code basrelief_<tag>.splat.json}. */
    public static final String SHARP_SIDECAR_SUFFIX = ".splat.json";

    /** {@code basrelief_<tag>.splat}, the cloud's server-side name. */
    public static String sharpSplatName(String tag) {
        return STREAM_FILE_PREFIX + tag + SHARP_SPLAT_SUFFIX;
    }

    /** {@code basrelief_<tag>.splat.json}, the run's sidecar. */
    public static String sharpSidecarName(String tag) {
        return STREAM_FILE_PREFIX + tag + SHARP_SIDECAR_SUFFIX;
    }

    /** Whether the server has the node this flow submits. */
    public static boolean hasSharpStill(JSONObject objectInfo) {
        return objectInfo != null && objectInfo.optJSONObject(SHARP_STILL) != null;
    }

    /**
     * Assemble the cloud graph: {@code LoadImage -> SharpStill}.
     *
     * <pre>
     * LoadImage(image=&lt;uploaded name&gt;)
     *   └─▶ SharpStill(image, internal_size, budget, tag, focal_px, checkpoint, keep_loaded)
     *         ──▶ basrelief_&lt;tag&gt;.splat
     *             basrelief_&lt;tag&gt;.splat.json
     * </pre>
     *
     * <p>Every scalar except the ones this class owns is discovered from
     * {@code /object_info}, so a server that adds or retunes an input is honoured —
     * the same rule the MoGe and stream builders follow. The caller-owned ones are
     * stated rather than defaulted: {@code image} is wired to {@code LoadImage}
     * output 0, {@code tag} is the sanitised item id the filenames derive from, and
     * {@code budget} is {@link #SHARP_BUDGET}.
     *
     * <p><b>{@code focal_px} is passed, not computed.</b> The node cannot read EXIF
     * through {@code LoadImage}, so it falls back to a 30 mm lens; a row that already
     * knows its camera (a MoGe run fitted one) hands the number in and the cloud's
     * grid is uniform in that lens instead. 0 is "unknown" and is the node's own
     * fallback.
     *
     * @param info         the parsed {@code /object_info} body
     * @param uploadedName the name ComfyUI gave the uploaded still
     * @param tag          the sanitised run tag; see {@link #streamTag}
     * @param focalPx      the lens in pixels of the uploaded frame, or 0 for the
     *                     node's fallback
     */
    public static String sharpStillWorkflow(JSONObject info, String uploadedName, String tag,
                                            float focalPx) throws JSONException {
        return sharpStillWorkflow(info, uploadedName, tag, focalPx, SHARP_BUDGET);
    }

    /**
     * The same graph with the budget stated, for the dev BUDGET knob.
     *
     * <p>A budget is a ceiling on the file, and the whole point of the dev ladder is
     * to regenerate one item at a different one and look at it -- so the value has
     * to be the caller's, read from the pref at the moment of submission. The
     * one-argument-lighter form above keeps {@link #SHARP_BUDGET}, so nothing that
     * does not opt in changes; the clamp is here so a pref that cannot claim a
     * count reaches the node as the default rather than as the node's own fallback.
     */
    public static String sharpStillWorkflow(JSONObject info, String uploadedName, String tag,
                                            float focalPx, int budget) throws JSONException {
        JSONObject nodeInfo = info.getJSONObject(SHARP_STILL);

        JSONObject load = loadImageNode(uploadedName);

        JSONObject sharp = new JSONObject();
        sharp.put("class_type", SHARP_STILL);
        // `image` is a link and every other input is discovered and then overridden
        // with the value this class owns, so a scalar the node adds still reaches it
        // while the ones the flow depends on are stated rather than defaulted.
        JSONObject in = discoveredInputs(nodeInfo, "image");
        in.put("image", link(SHARP_LOAD_ID));
        in.put("internal_size", SHARP_INTERNAL_SIZE);
        in.put("budget", clampSharpBudget(budget));
        in.put("tag", tag);
        in.put("focal_px", (double) Math.max(0f, focalPx));
        // Hand the predictor's VRAM back at the end: the phone runs one still at a
        // time, and a resident 2.8 GB beside whatever the server already caches is
        // exactly what the proxy's unload exists to avoid.
        in.put("keep_loaded", false);
        sharp.put("inputs", in);

        JSONObject wf = new JSONObject();
        wf.put(SHARP_LOAD_ID, load);
        wf.put(SHARP_ID, sharp);
        return wf.toString();
    }


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
     * Assemble the stream graph: two nodes, one prompt, no window plan.
     *
     * <pre>
     * LoadMoGeModel(model_name) ──▶ MOGE_MODEL
     *   MoGeVideoStream(moge_model, video, tag, start_frame, frame_count,
     *                   target_height, max_long_side, batch_size, fps_cap,
     *                   resolution_level, refine_steps)
     *     ──▶ basrelief_&lt;tag&gt;_depth.mp4, basrelief_&lt;tag&gt;_normal.mp4,
     *         basrelief_&lt;tag&gt;_color.mp4 (only when the caps fired),
     *         basrelief_&lt;tag&gt;.progress.json  (files, in the output dir)
     * </pre>
     *
     * <p><b>The node owns the loop, which is why the app stopped chunking.</b>
     * {@code MoGeVideoStream} takes the video PATH, decodes a window at a time,
     * runs MoGe over it and encodes the frames straight out, so peak memory is the
     * model plus one window instead of the whole range. The graph that made a long
     * clip a batch — {@code VHS_LoadVideo → MoGeInference → VHS_VideoCombine} — held
     * every frame of the range live and OOMed on the measured clip: 480 frames at
     * 1920x1080 climbed to 15.48 GiB and requested 4.03 more at frame 174 on the
     * server's 16 GiB cap. Through the node the same clip held 11.5-12.3 GiB flat
     * for all 480 frames and finished in 340 s.</p>
     *
     * <p><b>{@code batch_size} is the window and therefore the memory knob.</b> The
     * caller sizes it from the pixels the node will load — {@code
     * BatchBudget.streamWindow} — because the measured peak is dominated by MoGe's
     * ViT-G activations for the window, not by the decoded frames. It is the one
     * number in this graph a client computes rather than discovers.</p>
     *
     * <p><b>{@code resolution_level} and {@code refine_steps} are discovered, not
     * named.</b> The app has never exposed them; they take the server's own defaults
     * exactly as the still MoGe shape does, so a server that tunes them tunes both
     * shapes at once.</p>
     *
     * <p><b>{@code video} is the uploaded file's server-side name.</b> The app
     * uploads its own remuxed range first and the node never sees the share URI.
     * The upload is the PLAN and the best local copy -- it is also what a regenerate
     * uploads again -- but it is no longer necessarily the colour the renderer
     * plays: for a source heavier than the playback caps the node re-encodes a
     * colour of its own and the app fetches and plays that.</p>
     *
     * <p><b>{@code frame_count = 0} means the whole upload</b>, which is what every
     * app run passes: the remux is already the range, so the plan is "all of it" and
     * the app verifies the written count against its own sample count afterwards.
     * Asking for a duration-derived count is exactly the off-by-one the old chunk
     * planner shipped — a 240-frame remux reports 9.9586 s, so {@code round(9.9586 ×
     * 24)} is 239 and the joined depth decoded one frame short.</p>
     *
     * <p><b>{@code max_long_side} and {@code fps_cap} are the sync knob, and the two
     * constants own them.</b> The generated tracks -- and the node's colour, when the
     * source exceeds either cap -- are written at {@code long_side <=
     * STREAM_MAX_LONG_SIDE} and {@code min(source, STREAM_FPS_CAP)} and the timeline
     * is kept: output frame k is source frame
     * {@code round_half_up(k × src/out)}, so fewer samples over the same seconds.
     * See each constant for the measurements that made the caps necessary.</p>
     *
     * <p><b>The tag is the app's handle on the outputs.</b> The node sanitises it
     * ({@link #streamTag}) and derives all three filenames from it, so the fetch and
     * resume paths can name files they have never been told about.</p>
     *
     * @param info         the parsed {@code /object_info} body
     * @param model        the checkpoint filename, or {@code MoGeInference} to
     *                     keep the server's discovered default
     * @param videoPath    the uploaded file's name in the server's input dir
     * @param tag          the sanitised run tag; see {@link #streamTag}
     * @param startFrame   the first frame to process; always 0 for the app, whose
     *                     upload is already trimmed
     * @param frameCount   how many frames to process; 0 = to the end of the upload
     * @param targetHeight the height to load at; 0 = the source's own
     * @param batchSize    the inference window, 1..16; see {@code BatchBudget}
     */
    public static String buildVideoStreamWorkflow(JSONObject info, String model, String videoPath,
                                                  String tag, int startFrame, int frameCount,
                                                  int targetHeight, int batchSize)
            throws JSONException {
        JSONObject loaderInfo = info.getJSONObject(MOGE_LOADER);
        JSONObject streamInfo = info.getJSONObject(VIDEO_STREAM);

        JSONObject loader = new JSONObject();
        loader.put("class_type", MOGE_LOADER);
        JSONObject loaderIn = discoveredInputs(loaderInfo);
        if (!MOGE_INFERENCE.equals(model)) loaderIn.put("model_name", model);
        loader.put("inputs", loaderIn);

        JSONObject stream = new JSONObject();
        stream.put("class_type", VIDEO_STREAM);
        // `moge_model` is a link and `video` is the uploaded name the caller has
        // already resolved; every other input is discovered and then overridden with
        // the caller's value, so a scalar the node adds still reaches it while the
        // ones this class owns are stated rather than defaulted.
        JSONObject in = discoveredInputs(streamInfo, "moge_model", "video");
        in.put("moge_model", link(S_MOGE_LOAD_ID));
        in.put("video", videoPath);
        in.put("tag", tag);
        in.put("start_frame", Math.max(0, startFrame));
        in.put("frame_count", Math.max(0, frameCount));
        in.put("target_height", Math.max(0, targetHeight));
        // After target_height, as on the node: the chip is a generation choice, the
        // long-side cap is a playback one. See STREAM_MAX_LONG_SIDE.
        in.put("max_long_side", STREAM_MAX_LONG_SIDE);
        in.put("batch_size", batchSize);
        // After batch_size, as on the node: the window is the memory knob, the caps
        // are the sync ones.
        in.put("fps_cap", STREAM_FPS_CAP);
        stream.put("inputs", in);

        JSONObject wf = new JSONObject();
        wf.put(S_MOGE_LOAD_ID, loader);
        wf.put(S_STREAM_ID, stream);
        return wf.toString();
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
