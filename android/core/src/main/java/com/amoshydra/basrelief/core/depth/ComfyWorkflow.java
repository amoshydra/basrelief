package com.amoshydra.basrelief.core.depth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Builds the ComfyUI graph the Depth AI page submits, plus the small pure
 * helpers that go with it.
 *
 * <p>This was extracted verbatim out of {@code DepthGenDialog} so it could be
 * tested. Nothing here touches the Android framework — the only outside type is
 * {@code org.json}, which the platform provides at runtime and which is a
 * {@code testImplementation} dependency here.
 *
 * <p>The node wiring is the part worth pinning. The graph is three nodes joined
 * by literal {@code ["<node id>", <output index>]} references, and an off-by-one
 * there produces a graph ComfyUI accepts but renders from the wrong tensor —
 * which surfaces as a blank or unblended depth map rather than an error.
 */
public final class ComfyWorkflow {

    private ComfyWorkflow() {
    }

    /** Node id of the {@code LoadImage} node; the depth node reads its output. */
    private static final String LOAD_ID = "1";
    /** Node id of the depth-anything node; {@code SaveImage} reads its output. */
    private static final String DEPTH_ID = "2";
    private static final String SAVE_ID = "3";
    private static final int FIRST_OUTPUT = 0;

    /**
     * Assemble the three-node depth workflow.
     *
     * @param objectInfo   the whole {@code /object_info} body, used to discover
     *                     the model's required inputs and their defaults
     * @param model        ComfyUI class name of the depth node, e.g.
     *                     {@code DepthAnythingV2ModelLoader}
     * @param uploadedName the name ComfyUI gave the uploaded image
     * @param resolution   value for the node's {@code resolution} input
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
        JSONObject nodeInfo = info.getJSONObject(model);

        // /object_info nests inputs under "input": { "required": {...}, ... }
        JSONObject input = nodeInfo.optJSONObject("input");
        JSONObject required = input != null ? input.optJSONObject("required") : null;
        if (required == null) required = nodeInfo.optJSONObject("required"); // fallback
        if (required == null) required = new JSONObject();

        JSONObject load = new JSONObject();
        load.put("class_type", "LoadImage");
        load.put("inputs", new JSONObject().put("image", uploadedName));

        JSONObject depth = new JSONObject();
        depth.put("class_type", model);
        JSONObject depthIn = new JSONObject();
        // Wired from LoadImage, not from the uploaded filename: the file has
        // already been uploaded, and this is how ComfyUI refers to it in-graph.
        depthIn.put("image", new JSONArray().put(LOAD_ID).put(FIRST_OUTPUT));

        Iterator<String> it = required.keys();
        while (it.hasNext()) {
            String inName = it.next();
            if (inName.equals("image")) continue; // already wired above
            JSONArray spec = required.getJSONArray(inName);
            String type = spec.getString(0);
            Object def = spec.length() > 1 ? spec.opt(1) : null;
            Object value;
            if (inName.equals("resolution")) {
                value = Integer.valueOf(resolution);
            } else if (def instanceof JSONObject) {
                // options dict — use the node's own default when present
                JSONObject opts = (JSONObject) def;
                value = opts.has("default") ? opts.get("default") : defaultForType(type);
            } else if (def == null) {
                value = defaultForType(type); // never drop a required input
            } else {
                value = def;
            }
            depthIn.put(inName, value);
        }
        depth.put("inputs", depthIn);

        JSONObject save = new JSONObject();
        save.put("class_type", "SaveImage");
        JSONObject saveIn = new JSONObject();
        // Save the DEPTH node's image output (not LoadImage's).
        saveIn.put("images", new JSONArray().put(DEPTH_ID).put(FIRST_OUTPUT));
        saveIn.put("filename_prefix", "depthmap_" + timestampMs);
        save.put("inputs", saveIn);

        JSONObject wf = new JSONObject();
        wf.put(LOAD_ID, load);
        wf.put(DEPTH_ID, depth);
        wf.put(SAVE_ID, save);
        return wf.toString();
    }

    /** A safe value for a required input the caller gave no default for. */
    static Object defaultForType(String type) {
        if (type.equals("INT")) return 0;
        if (type.equals("FLOAT")) return 0.0;
        if (type.equals("BOOLEAN")) return true;
        return "";
    }

    /** Turn a raw ComfyUI class name into something human-readable. */
    public static String friendlyName(String name) {
        if (name.contains("DepthAnythingV2")) return "Depth Anything V2";
        if (name.contains("DepthAnything")) return "Depth Anything V1";
        String lower = name.toLowerCase();
        if (lower.contains("midas")) return "MiDaS";
        if (lower.contains("zoe")) return "ZoeDepth";
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
