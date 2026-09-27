package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.store.PrefFiles;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * "Depth AI" page: point the app at a ComfyUI instance, discover the depth
 * nodes it actually has, pick an image, generate a depth map, and pack it into
 * a self-contained PNG (image + dEPt chunk) that loads straight into the studio.
 */
public final class DepthGenDialog {

    public interface Callback {
        void onGenerated(String packedImagePath);
    }

    private static final String KEY_HOST = "comfy_url";
    private static final String KEY_RES = "comfy_resolution";
    private static final String KEY_MODEL = "comfy_model";

    private final Activity activity;
    private final Callback callback;
    private final Dialog dialog;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText hostInput;
    private TextView connectStatus;
    private Spinner modelSpinner;
    private EditText resInput;
    private TextView imageLabel;
    private Button generateBtn;
    private ProgressBar progress;
    private TextView status;
    private ImageView preview;
    private Button exportBtn;
    private File lastPacked;

    private String comfyBase;
    private final List<String> nodes = new ArrayList<String>();
    private final List<String> displayNames = new ArrayList<String>();
    private File pickedImage;
    private ComfyWs ws;
    private volatile boolean busy;

    public DepthGenDialog(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.dialog = build();
    }

    public void show() {
        dialog.show();
    }

    public void onPickedImage(Intent data) {
        if (data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        // Off the UI thread: the copy plus the PNG downscale is slow on a big
        // photo and would otherwise freeze the studio.
        Io.run(activity, new Callable<File>() {
            @Override
            public File call() throws Exception {
                File target = new File(activity.getFilesDir(), "depthgen_src.png");
                Io.writeFile(target, Io.readAll(activity, uri));
                // huge sources (e.g. 3000x4000) would bloat the ComfyUI upload
                // and the packed result; downscale before use
                ImageUtil.downscaleFile(target, target, ImageUtil.MAX_SIDE);
                return target;
            }
        }, new Io.Callback<File>() {
            @Override
            public void onDone(File target, Exception error) {
                if (error != null || target == null) {
                    Toast.makeText(activity,
                            "Failed to read image: "
                                    + (error == null ? "unknown" : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                setImage(target, "picked");
            }
        });
    }

    private void setImage(File f, String kind) {
        pickedImage = f;
        String size = f.length() / 1024 + " KB";
        if ("current".equals(kind)) {
            imageLabel.setText("Image: current studio image (" + size + ") — "
                    + (hasEmbeddedDepth(f) ? "already has depth" : "no depth, will generate"));
        } else {
            imageLabel.setText("Image: " + f.getName() + " (" + size + ")");
        }
    }

    private static boolean hasEmbeddedDepth(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] all = new byte[(int) f.length()];
                int off = 0;
                while (off < all.length) {
                    int n = in.read(all, off, all.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return DepthPng.extract(all) != null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pre-load the studio's current image (from prefs) as the generation
     * source, so the user can generate depth for the currently selected photo
     * without re-picking it. No-op if there is no current image.
     */
    public void useCurrentImage() {
        // The selected item's image, not a global: depth is generated FOR a
        // photograph, so the photo on screen is the only sensible source.
        AppState state = AppState.load(
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE),
                activity.getFilesDir());
        com.amoshydra.basrelief.core.store.LibraryItem active = state.activeItem();
        if (active == null || active.isBundled()) return; // the demo needs no depth
        String stored = active.imageName;
        // stored as a bare filename; resolve against this install's filesDir
        final File f = PrefFiles.resolve(activity.getFilesDir(), stored);
        if (f == null) return;
        pickedImage = f;
        final String size = f.length() / 1024 + " KB";
        imageLabel.setText("Image: current studio image (" + size + ") — checking depth…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean has = hasEmbeddedDepth(f);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (imageLabel == null) return;
                        imageLabel.setText("Image: current studio image (" + size + ") — "
                                + (has ? "already has depth" : "no depth, will generate"));
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private Dialog build() {
        final SharedPreferences prefs =
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(activity);
        title.setText("Depth AI — generate depth on your ComfyUI");
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        // --- setup: ComfyUI host ---
        // No baked-in default host: ship nothing that fingerprints the author's
        // own LAN. The user types it (or it is restored from prefs).
        hostInput = new EditText(activity);
        hostInput.setText(prefs.getString(KEY_HOST, ""));
        hostInput.setHint("http://<comfyui-host>:8188");
        root.addView(hostInput);

        Button testBtn = new Button(activity);
        testBtn.setText("Test connection / discover depth nodes");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });
        root.addView(testBtn);

        connectStatus = new TextView(activity);
        connectStatus.setTextSize(13);
        connectStatus.setTextIsSelectable(true); // copyable errors
        root.addView(connectStatus);

        // --- image ---
        Button pickBtn = new Button(activity);
        pickBtn.setText("Pick image to generate depth for");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(intent, MainActivity.REQ_DEPTHGEN_IMAGE);
            }
        });
        root.addView(pickBtn);

        imageLabel = new TextView(activity);
        imageLabel.setTextSize(13);
        root.addView(imageLabel);

        // --- model + resolution ---
        modelSpinner = new Spinner(activity);
        List<String> placeholder = new ArrayList<String>();
        placeholder.add("(test connection first)");
        modelSpinner.setAdapter(new ArrayAdapter<String>(
                activity, android.R.layout.simple_spinner_dropdown_item, placeholder));
        root.addView(modelSpinner);

        resInput = new EditText(activity);
        resInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        resInput.setText(String.valueOf(prefs.getInt(KEY_RES, 512)));
        resInput.setHint("resolution (longest side)");
        root.addView(resInput);

        generateBtn = new Button(activity);
        generateBtn.setText("Generate depth");
        generateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generate();
            }
        });
        root.addView(generateBtn);

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress);

        status = new TextView(activity);
        status.setTextSize(13);
        status.setTextIsSelectable(true); // copyable errors
        root.addView(status);

        // depth result preview (like the web tool)
        preview = new ImageView(activity);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(260));
        preview.setVisibility(View.GONE);
        root.addView(preview);

        // export the packed PNG to Downloads (like the web tool's download)
        exportBtn = new Button(activity);
        exportBtn.setText("Export PNG to Downloads");
        exportBtn.setEnabled(false);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportPacked();
            }
        });
        root.addView(exportBtn);

        Button closeBtn = new Button(activity);
        closeBtn.setText("Close");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(closeBtn);

        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(scroll);
        d.getWindow().setLayout(
                (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                stopWs();
            }
        });
        return d;
    }

    // ------------------------------------------------------------------ connection

    private void testConnection() {
        comfyBase = hostInput.getText().toString().trim();
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_HOST, comfyBase).apply();
        connectStatus.setText("Testing connection...");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ComfyApi api = new ComfyApi(comfyBase);
                    final String info = api.getObjectInfo();
                    final JSONObject obj = new JSONObject(info);
                    final List<String> depth = new ArrayList<String>();
                    Iterator<String> it = obj.keys();
                    while (it.hasNext()) {
                        String name = it.next();
                        String lower = name.toLowerCase();
                        if (lower.contains("depth") || lower.contains("midas") || lower.contains("zoe")) {
                            depth.add(name);
                        }
                    }
                    Collections.sort(depth);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (depth.isEmpty()) {
                                connectStatus.setText("Connected (" + obj.length() + " nodes) but no "
                                        + "depth nodes — is comfyui_controlnet_aux installed?");
                                nodes.clear();
                                displayNames.clear();
                                List<String> none = new ArrayList<String>();
                                none.add("(no depth nodes)");
                                modelSpinner.setAdapter(new ArrayAdapter<String>(
                                        activity, android.R.layout.simple_spinner_dropdown_item, none));
                            } else {
                                nodes.clear();
                                displayNames.clear();
                                for (String n : depth) {
                                    nodes.add(n);
                                    displayNames.add(ComfyWorkflow.friendlyName(n));
                                }
                                connectStatus.setText("Connected — " + depth.size()
                                        + " depth node(s) found");
                                modelSpinner.setAdapter(new ArrayAdapter<String>(
                                        activity, android.R.layout.simple_spinner_dropdown_item,
                                        new ArrayList<String>(displayNames)));
                                String saved = activity
                                        .getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                                        .getString(KEY_MODEL, null);
                                if (saved != null) {
                                    int idx = depth.indexOf(saved);
                                    if (idx >= 0) modelSpinner.setSelection(idx);
                                }
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            connectStatus.setText("Connection failed: " + e.getMessage());
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ generate

    private void generate() {
        if (busy) return;
        if (comfyBase == null) {
            Toast.makeText(activity, "Test the connection first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pickedImage == null) {
            Toast.makeText(activity, "Pick an image first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nodes.isEmpty() || modelSpinner.getSelectedItem() == null) {
            Toast.makeText(activity, "No depth model available", Toast.LENGTH_SHORT).show();
            return;
        }
        int res;
        try {
            res = Integer.parseInt(resInput.getText().toString());
        } catch (Exception e) {
            res = 512;
        }
        if (res < 64) res = 64;

        final String model = nodes.get(modelSpinner.getSelectedItemPosition());
        final String clientId = "depth-app-" + System.nanoTime();
        final int resolution = res;
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, model).putInt(KEY_RES, resolution).apply();

        busy = true;
        generateBtn.setEnabled(false);
        progress.setProgress(0);
        status.setText("Starting...");

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ComfyApi api = new ComfyApi(comfyBase);
                    setStatus("Uploading image...");
                    String up = api.uploadImage(pickedImage, pickedImage.getName());
                    JSONObject upObj = new JSONObject(up);
                    String name = upObj.getString("name");
                    String subfolder = upObj.optString("subfolder", "");
                    String uploadedName = subfolder.isEmpty() ? name : subfolder + "/" + name;

                    setStatus("Building workflow...");
                    String info = api.getObjectInfo();
                    String workflow = ComfyWorkflow.buildWorkflow(
                            info, model, uploadedName, resolution, System.currentTimeMillis());

                    setStatus("Submitting to ComfyUI...");
                    String resp = api.submitPrompt(workflow, clientId);
                    JSONObject r = new JSONObject(resp);
                    if (r.has("error")) throw new Exception(r.toString());
                    final String promptId = r.getString("prompt_id");

                    // live progress via websocket (like the web tool)
                    final String wsUrl = "ws://" + ComfyWorkflow.hostPort(comfyBase) + "/ws?clientId=" + clientId;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            startWs(wsUrl);
                        }
                    });

                    // poll history for the result
                    JSONObject history = null;
                    for (int i = 0; i < 600; i++) {
                        Thread.sleep(1000);
                        String h = api.getHistory(promptId);
                        JSONObject ho = new JSONObject(h);
                        if (ho.has(promptId)) {
                            history = ho.getJSONObject(promptId);
                            break;
                        }
                    }
                    stopWs();
                    if (history == null) {
                        throw new Exception("ComfyUI did not return a result within 10 minutes");
                    }

                    // if the run failed, surface ComfyUI's real error
                    JSONObject statusObj = history.optJSONObject("status");
                    if (statusObj != null && statusObj.optString("status_str", "").equals("error")) {
                        throw new Exception("ComfyUI execution error: "
                                + ComfyWorkflow.extractExecutionError(statusObj));
                    }

                    JSONObject outputs = history.optJSONObject("outputs");
                    if (outputs == null) {
                        throw new Exception("no output in history (status: "
                                + (statusObj == null ? "?" : statusObj.optString("status_str", "?"))
                                + ")");
                    }
                    JSONObject imgObj = null;
                    Iterator<String> it = outputs.keys();
                    while (it.hasNext() && imgObj == null) {
                        JSONObject node = outputs.getJSONObject(it.next());
                        if (node.has("images")) {
                            JSONArray images = node.getJSONArray("images");
                            if (images.length() > 0) imgObj = images.getJSONObject(0);
                        }
                    }
                    if (imgObj == null) throw new Exception("no output image in history");
                    final String fname = imgObj.getString("filename");
                    final String fsub = imgObj.optString("subfolder", "");
                    final String ftype = imgObj.optString("type", "output");

                    setStatus("Downloading depth map...");
                    byte[] depthBytes = api.view(fname, fsub, ftype);
                    final Bitmap depthBmp = BitmapFactory.decodeByteArray(depthBytes, 0, depthBytes.length);
                    if (depthBmp == null) throw new Exception("depth image could not be decoded");

                    setStatus("Packing PNG with depth chunk...");
                    final Bitmap origBmp = BitmapFactory.decodeFile(pickedImage.getAbsolutePath());
                    if (origBmp == null) throw new Exception("original image could not be decoded");
                    final File packed = new File(activity.getFilesDir(),
                            "ai_depth_" + System.currentTimeMillis() + ".png");
                    DepthPngPacker.pack(origBmp, depthBmp, packed);
                    origBmp.recycle();
                    // depthBmp stays alive: it's shown in the preview below

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            preview.setImageBitmap(depthBmp);
                            preview.setVisibility(View.VISIBLE);
                            status.setText("Done — " + ComfyWorkflow.friendlyName(model) + " @ " + resolution
                                    + ", loaded into studio.");
                            progress.setProgress(1000);
                            generateBtn.setEnabled(true);
                            exportBtn.setEnabled(true);
                            lastPacked = packed;
                            busy = false;
                            if (callback != null) callback.onGenerated(packed.getAbsolutePath());
                        }
                    });
                } catch (final Exception e) {
                    stopWs();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("Failed: " + e.getMessage());
                            generateBtn.setEnabled(true);
                            busy = false;
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Build {LoadImage -> <model> -> SaveImage} from the node's own spec. */
    // ------------------------------------------------------------------ websocket

    private void startWs(String url) {
        stopWs();
        ws = new ComfyWs(url, new ComfyWs.Listener() {
            @Override
            public void onMessage(String text) {
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");
                    if (type.equals("progress")) {
                        JSONObject d = msg.getJSONObject("data");
                        int value = d.optInt("value");
                        int max = d.optInt("max");
                        // map into the web tool's 15%-85% band
                        if (max > 0) setProgress(150 + value * 700 / max);
                    } else if (type.equals("executing")) {
                        JSONObject d = msg.getJSONObject("data");
                        if (d.has("node") && !d.isNull("node")) {
                            setStatus("Processing node " + d.optString("node") + "...");
                        } else {
                            setStatus("Finalizing...");
                        }
                    }
                } catch (JSONException ignored) {
                }
            }

            @Override
            public void onError(Exception e) {
                // websocket is just progress; history polling is the source of truth
            }

            @Override
            public void onClose() {
            }
        });
        ws.start();
    }

    private void stopWs() {
        if (ws != null) ws.stop();
        ws = null;
    }

    // ------------------------------------------------------------------ export

    private void exportPacked() {
        final File src = lastPacked;
        if (src == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = activity.getContentResolver()
                                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (uri == null) throw new Exception("could not create MediaStore entry");
                        OutputStream os = activity.getContentResolver().openOutputStream(uri);
                        copy(src, os);
                        os.close();
                        toast("Saved to Downloads: " + src.getName());
                    } else {
                        toast("Export needs Android 10+");
                    }
                } catch (final Exception e) {
                    toast("Export failed: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void copy(File src, OutputStream out) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ ui helpers

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    private void setProgress(final int pct1000) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                progress.setProgress(pct1000);
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
