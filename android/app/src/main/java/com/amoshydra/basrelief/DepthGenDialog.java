package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The Depth AI flow: one screen to set up a ComfyUI run, and a run that
 * survives the studio being closed.
 *
 * <p><b>What changed and why.</b> This used to be a dialog that hid the
 * endpoint behind "discovered automatically", which is fine until the server is
 * on a different machine — which it usually is — and made the user pick the image
 * again. The endpoint is now an ordinary editable field with a Test connection
 * button beside it, and the source is always the photo on screen, because depth
 * is generated <i>for a photograph</i>.
 *
 * <p><b>Setup is one screen</b> — server, model, resolution — because three
 * screens for one decision is three chances to change your mind.
 *
 * <p><b>A successful run applies itself.</b> No review step and no confirm
 * dialog: the app asked the question on the user's behalf and the server has now
 * answered it, so there is nothing left to confirm. Regenerating is the undo,
 * which is why that needs no confirmation either.
 *
 * <p><b>The map is replaced only on success.</b> See {@link #applyMap} — this is
 * the single most important property of the class, and it is why the whole
 * success path is built as one function that ends in one write.
 *
 * <p><b>The run outlives the dialog.</b> R-BG1: the job lives on the server, not
 * here. So the poll loop is a daemon thread, the prompt id is written to prefs
 * before the dialog can be dismissed, and {@link #resumePending} asks the server
 * what happened when the app comes back. Nothing about a run is cancelled by
 * closing anything — collapsing the sheet is not a cancel (R-S4).
 */
public final class DepthGenDialog {

    public interface Callback {
        /**
         * A generated map is on disk and {@code LibraryItem.depthName} has been
         * written. Always on the UI thread. The host reloads the render here.
         *
         * @param itemId         the photo the map belongs to — captured when the
         *                       panel opened, <em>not</em> re-read from the index
         * @param packedImagePath absolute path of the packed PNG
         */
        void onApplied(String itemId, String packedImagePath);

        /**
         * The old single-argument hook, kept only so a host that has not moved to
         * {@link #onApplied} still gets told.
         *
         * <p>It took no item id, so its only way to find the photo was to re-read
         * the <em>currently selected</em> one — and a run takes tens of seconds, so
         * a user who switched photos in the meantime got another photo's depth map.
         * {@link DepthGenDialog#applyMap} now does that write against the id
         * captured at launch, which is the only place it can be right, so an
         * implementation here must not write {@code depthName} again.
         */
        void onGenerated(String packedImagePath);
    }

    // --------------------------------------------------------------- keys

    private static final String KEY_HOST = "comfy_url";
    private static final String KEY_RES = "comfy_resolution";
    private static final String KEY_MODEL = "comfy_model";

    /**
     * The in-flight run, as a JSON blob, so a process that was killed mid-run
     * can ask the server on the next launch instead of losing the answer.
     *
     * <p>This is what makes R-BG1 true rather than aspirational. "The job lives
     * on the server" only helps if something remembers which job: a daemon
     * thread dies with the process, so the handle to the server's copy has to be
     * on disk.
     */
    private static final String KEY_PENDING = "comfy_pending_run";

    private static final String TAG = "Basrelief";

    /** Output sizes offered. Pinned to the mockup's single row of nudges. */
    private static final int[] RESOLUTIONS = {256, 512, 768, 1024, 1536, 2048};
    private static final int DEFAULT_RESOLUTION = 1024;
    /** Below this a depth map has too few levels to build a relief from. */
    private static final int MIN_RESOLUTION = 256;

    /**
     * How long to keep asking the server before giving up.
     *
     * <p>Deliberately generous: a rotation-vector model on a 2048 map is tens of
     * seconds on good hardware and minutes on a laptop, and the alternative to
     * waiting is throwing away work the user is watching.
     */
    private static final long POLL_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long POLL_INTERVAL_MS = 1000L;

    private static final String NOTIFY_CHANNEL = "depthgen";
    private static final int NOTIFY_ID = 0x0DE7;

    /**
     * A run older than this on resume is not worth chasing.
     *
     * <p>ComfyUI's history keeps a finished prompt, so an abandoned record would
     * otherwise be picked up weeks later and applied to a photo the user has long
     * since moved on from. The job itself is long finished by then; the record
     * being present is an artefact of the process having been killed.
     */
    private static final long PENDING_MAX_AGE_MS = 30 * 60 * 1000L;

    // --------------------------------------------------------------- shared run state
    //
    // Static, not per-instance, and that is the whole point of R-BG1. The dialog
    // is owned by MainActivity, which is destroyed and recreated on a rotation
    // and on "don't keep activities"; a run takes tens of seconds and must not
    // die with a window. Everything the sheet needs to draw the fix line or the
    // pill reads these statics, so a live panel can be rebuilt from scratch at
    // any point and know the truth.

    /** What a finished run did. Read by the sheet's fix line. */
    public enum Result { NONE, APPLIED, FAILED }

    /**
     * The one in-flight run, or null. Guarded by {@link #RUN_LOCK} on every
     * read and write: the worker thread clears it and the UI thread reads it, and
     * an unsynchronised field would let the UI read a half-built run.
     */
    private static Run run;
    private static final Object RUN_LOCK = new Object();

    /**
     * One main-thread handler for the whole class.
     *
     * <p>Static because a run outlives any one dialog: the resume path and the
     * completion of a run started by a dialog that has since been destroyed both
     * have to get back to the UI thread, and neither can go through an instance.
     */
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 0..100, or -1 when nothing is running. R-BG2: the pill shows this. */
    private static volatile int progress = -1;

    /** The outcome of the most recent run, so the fix line can say so once. */
    private static volatile Result result = Result.NONE;

    /** Why the last run failed. Never null once {@link #result} is FAILED. */
    private static volatile String failureCause = "";

    /** Set once a run succeeds; read by Diagnostics for the map's filename. */
    private static volatile String lastMapName = "";
    private static volatile String lastModel = "";

    /**
     * The host, told when a run lands.
     *
     * <p>Static for the same reason as {@link #run}: a resumed run has no dialog,
     * and the studio still has to reload the render when it comes back. Weak,
     * because an Activity-scoped callback held strongly from a static would keep
     * the Activity alive for the life of the process — and the studio is
     * destroyed and recreated on every rotation.
     */
    private static volatile WeakReference<Callback> host;

    /** Tell the host a map landed. No-op when there is no live host. */
    private static void notifyHost(String itemId, String packedPath) {
        WeakReference<Callback> ref = host;
        Callback cb = ref == null ? null : ref.get();
        if (cb == null) return;
        cb.onApplied(itemId, packedPath);
    }

    /**
     * The currently open panel, if any.
     *
     * <p>Not a View reference: the panel's own runnable, so the worker's
     * completion can reach the UI thread through one hop. Held weakly in spirit
     * only — it is nulled in {@code onDismiss}, which is the moment the panel
     * stops being able to receive anything.
     */
    private static volatile DepthGenDialog openPanel;

    // --------------------------------------------------------------- instance

    private final Activity activity;
    private final Callback callback;
    private final Dialog dialog;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * The photo this panel is generating for.
     *
     * <p>Captured at {@link #show()}, not read at completion. The user can
     * select a different photo while a run is in flight — the old code wrote
     * {@code depthName} against whatever was selected when the answer arrived,
     * which attaches one photo's depth map to another photo.
     */
    private String targetItemId;

    /** The image the depth is generated for, or null for the bundled demo. */
    private File targetImage;

    // The one screen. Three blocks, always all present, so changing a value
    // never navigates and never discards what was typed elsewhere.
    private LinearLayout setupBlock;
    private LinearLayout runBlock;
    private LinearLayout outcomeBlock;

    private TextView serverDot;
    private TextView serverAddress;
    private TextView serverHint;
    private Button serverChangeBtn;
    private EditText hostInput;
    private Button testBtn;
    private TextView serverStatus;

    private LinearLayout modelList;
    private TextView modelEmpty;

    private TextView resValue;
    private int resolution;

    private Button generateBtn;
    private Button cancelBtn;

    private TextView runStatus;
    private TextView runPercent;
    private ProgressBar runProgress;
    private Button runHideBtn;

    private TextView outcomeText;
    private TextView outcomeSubject;
    private Button outcomeRetryBtn;
    private Button outcomeServerBtn;

    private String comfyBase;
    private String savedModel;
    private final List<String> nodes = new ArrayList<String>();
    private final List<String> displayNames = new ArrayList<String>();
    private boolean modelsFetched;

    /**
     * The run's progress socket.
     *
     * <p>Static because it belongs to the run and not to a panel: a resumed run
     * has no panel, and a panel that is dismissed must not take the run's
     * progress reporting with it (R-S4).
     */
    private static volatile ComfyWs ws;

    /**
     * One in-flight run, as plain values.
     *
     * <p>Immutable except {@link #promptId}, which is written by the worker and
     * read by the resume path. That is the only mutable field and it is the one
     * that cannot be known at submit time.
     */
    private static final class Run {
        /**
         * The application context, so the pipeline can write files and prefs from
         * a worker thread without holding the Activity.
         *
         * <p>An Activity reference here would be a leak held for the length of a
         * run that may be minutes, and the whole point of R-BG1 is that the run
         * outlives the window that started it.
         */
        final Context app;
        final String host;
        final String model;
        final int resolution;
        final String itemId;
        final String imagePath;
        final String clientId;
        volatile String promptId;
        /**
         * When it was queued. Not final: a record read back from prefs carries the
         * original value, which is the whole point of storing it.
         */
        long startedAt;

        Run(Context app, String host, String model, int resolution, String itemId,
            String imagePath, String clientId) {
            this.app = app;
            this.host = host;
            this.model = model;
            this.resolution = resolution;
            this.itemId = itemId;
            this.imagePath = imagePath;
            this.clientId = clientId;
            this.startedAt = System.currentTimeMillis();
        }

        /** The record written to prefs, so a killed process can resume this. */
        String toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("host", host);
                o.put("model", model);
                o.put("resolution", resolution);
                o.put("itemId", itemId);
                o.put("image", imagePath);
                o.put("clientId", clientId);
                o.put("startedAt", startedAt);
                if (promptId != null) o.put("promptId", promptId);
            } catch (Exception ignored) {
                // org.json only throws for NaN and a non-finite number, none of
                // which can appear here.
            }
            return o.toString();
        }

        static Run fromJson(Context app, String blob) {
            if (blob == null) return null;
            try {
                JSONObject o = new JSONObject(blob);
                String host = o.optString("host", "");
                String itemId = o.optString("itemId", "");
                String image = o.optString("image", "");
                String clientId = o.optString("clientId", "");
                if (host.length() == 0 || itemId.length() == 0 || image.length() == 0
                        || clientId.length() == 0) {
                    return null;
                }
                Run r = new Run(app, host, o.optString("model", ""),
                        o.optInt("resolution", DEFAULT_RESOLUTION),
                        itemId, image, clientId);
                String p = o.optString("promptId", null);
                r.promptId = (p == null || p.length() == 0) ? null : p;
                r.startedAt = o.optLong("startedAt", 0L);
                return r;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public DepthGenDialog(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.dialog = build();
        // Registered here rather than in show(), so a run that finishes while the
        // panel is closed — collapsed sheet, backgrounded app — still reaches the
        // studio and reloads the render. see {@link #finishApplied}.
        if (callback != null) host = new WeakReference<Callback>(callback);
    }

    // --------------------------------------------------------------- public API
    //
    // Everything MainActivity needs is here, and nothing here touches
    // MainActivity. The sheet owns the fix line, the pill and the Regenerate /
    // Replace links; this class owns the panel and the run, and reports the run's
    // state so the sheet can draw it.

    /** Show the panel. The subject is the photo on screen. */
    public void show() {
        // Captured here, once, so completion cannot attach to a different photo.
        captureSubject();
        openPanel = this;
        render();
        dialog.show();
    }

    /** Whether a generation is in flight, wherever the user is in the app. */
    public static boolean isRunning() {
        synchronized (RUN_LOCK) {
            return run != null;
        }
    }

    /** 0..100, or -1 when nothing is running. R-BG2. */
    public static int progressPercent() {
        return progress;
    }

    /**
     * What the last run did, so the fix line can say so exactly once.
     *
     * <p>A run that is still in flight is not a result yet. The caller should
     * read {@link #isRunning()} first.
     */
    public static Result lastResult() {
        return result;
    }

    /** Why the last run failed. Empty when it did not fail. */
    public static String failureCause() {
        return failureCause;
    }

    /**
     * Map filename and the model that produced it, for Diagnostics (R-D9).
     *
     * <p>Never a control. The map's filename is debug output; a user who sees
     * {@code ai_depth_1790559208165.png} in a button has learned nothing and now
     * has a filename to be confused by.
     */
    public static String diagnostics() {
        if (lastMapName.length() == 0) return "";
        StringBuilder b = new StringBuilder(lastMapName);
        if (lastModel.length() > 0) {
            b.append(" · ").append(ComfyWorkflow.friendlyName(lastModel));
        }
        return b.toString();
    }

    /**
     * Ask the server about a run that was in flight when the process went away.
     *
     * <p>R-BG1. The job is on the server, so the app does not lose it by being
     * closed — it loses the <i>answer</i> unless the prompt id was persisted, and
     * this is the path that turns the persisted id back into a map.
     *
     * <p>Safe to call from {@code onResume}: it takes the pending record, so a
     * second call while one is in flight finds nothing to do.
     */
    public static void resumePending(final Context context) {
        // The application context, not the Activity: this may be called from a
        // resumed run whose activity has already gone.
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs =
                app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        final String blob = prefs.getString(KEY_PENDING, null);
        if (blob == null) return;
        final Run pending = Run.fromJson(app, blob);
        if (pending == null) {
            // Unusable record: clear it, or every launch would retry a job that
            // can never be resumed and the user could never start a new one.
            prefs.edit().remove(KEY_PENDING).apply();
            return;
        }
        // A record older than PENDING_MAX_AGE_MS is a process that was killed and never
        // came back, not a run in progress. Dropping it silently is right: the
        // user has had their answer for half an hour and is owed no notification.
        if (pending.startedAt > 0
                && System.currentTimeMillis() - pending.startedAt > PENDING_MAX_AGE_MS) {
            android.util.Log.i(TAG, "depth: dropping an abandoned pending record");
            prefs.edit().remove(KEY_PENDING).apply();
            return;
        }
        synchronized (RUN_LOCK) {
            if (run != null) return; // one run at a time; already live
            run = pending;
        }
        // No prompt id means it died between being queued and being submitted, so
        // there is nothing on the server to ask about. Drop it and let the user
        // start again rather than reporting a failure for a job that never ran.
        if (pending.promptId == null) {
            finishFailed(pending, "the run did not reach the server");
            return;
        }
        progress = 0;
        sayPhase(app, R.string.depth_phase_resuming);
        android.util.Log.i(TAG, "depth: resuming pending run " + pending.promptId);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pollAndFinish(pending, new ComfyApi(pending.host));
            }
        }, "depth-resume");
        t.setDaemon(true);
        t.start();
    }

    // --------------------------------------------------------------- subject

    /**
     * The photo on screen, captured as this panel's subject.
     *
     * <p>Depth is generated FOR a photograph, so the photo on screen is the only
     * sensible source. The bundled demo has no file to generate from and already
     * ships a map, so it is not a subject at all.
     */
    private void captureSubject() {
        SharedPreferences prefs =
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE);
        LibraryItem active = AppState.load(prefs, activity.getFilesDir()).activeItem();
        if (active == null || active.isBundled()) {
            targetItemId = null;
            targetImage = null;
            return;
        }
        targetItemId = active.id;
        // Stored as a bare filename; resolve against this install's filesDir.
        targetImage = PrefFiles.resolve(activity.getFilesDir(), active.imageName);
    }

    // --------------------------------------------------------------- build

    private Dialog build() {
        final SharedPreferences prefs =
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE);
        comfyBase = prefs.getString(KEY_HOST, "");
        savedModel = prefs.getString(KEY_MODEL, null);
        resolution = snapResolution(prefs.getInt(KEY_RES, DEFAULT_RESOLUTION));

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(24));
        scroll.addView(root);

        TextView title = text(R.string.depth_title, 16);
        root.addView(title);
        TextView sub = text(R.string.depth_panel_subtitle, 13);
        sub.setTextColor(0xFF8A9199);
        LinearLayout.LayoutParams subLp = lp();
        subLp.bottomMargin = dp(16);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        root.addView(buildSetup(prefs));
        root.addView(buildOutcome());
        root.addView(buildRun());

        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(scroll);
        // The panel is a bottom sheet over the render, which is the product: the
        // mockup draws it as a scrim over a peeked sheet, not a centred dialog.
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                // Save whatever was typed, on the way out. R-PANEL6: closing and
                // reopening the panel preserves the address. The old code wrote
                // the endpoint only from the connection test, so typing one and
                // closing threw it away — and because the dialog instance is
                // reused, the typed text stayed on screen looking saved right up
                // until the activity was recreated, which made plain data loss
                // look intermittent.
                persistTyped();
                // The panel stops receiving; the run does not stop with it.
                if (openPanel == DepthGenDialog.this) openPanel = null;
                stopWs();
            }
        });
        return d;
    }

    private View buildSetup(SharedPreferences prefs) {
        setupBlock = new LinearLayout(activity);
        setupBlock.setOrientation(LinearLayout.VERTICAL);

        // --- server ---
        //
        // The endpoint is enterable, not merely discovered. "Discovered
        // automatically" is a dead end the first time the server is on another
        // machine, which is the normal case: discovery only ever worked on this
        // LAN, and the server is usually elsewhere.
        setupBlock.addView(caption(R.string.depth_server));

        LinearLayout serverRow = row();
        serverDot = new TextView(activity);
        serverDot.setText("●");
        serverDot.setTextSize(13);
        serverDot.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(20)));
        serverRow.addView(serverDot);

        serverAddress = new TextView(activity);
        serverAddress.setTextSize(14);
        serverAddress.setSingleLine(true);
        serverAddress.setEllipsize(android.text.TextUtils.TruncateAt.END);
        serverRow.addView(serverAddress, weight());

        serverChangeBtn = smallButton(R.string.depth_server_change, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                revealHostField(true);
            }
        });
        serverRow.addView(serverChangeBtn);
        setupBlock.addView(serverRow);

        hostInput = new EditText(activity);
        hostInput.setText(comfyBase);
        hostInput.setHint(R.string.depth_server_hint);
        hostInput.setTextSize(14);
        // A hostname, not a number: the default soft keyboard offers digits and
        // a field the user cannot paste an address into is a field they will
        // mistype.
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setSingleLine(true);
        hostInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams hostLp = lp();
        hostLp.topMargin = dp(8);
        hostInput.setLayoutParams(hostLp);
        setupBlock.addView(hostInput);

        serverStatus = text(R.string.depth_server_untested, 13);
        serverStatus.setTextColor(0xFF8A9199);
        serverStatus.setVisibility(View.GONE);
        // Copyable: a ComfyUI refusal can be a stack trace, and a user reporting
        // a connection problem needs to be able to paste it rather than
        // photograph it. Same reason the old status line was selectable.
        serverStatus.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusLp = lp();
        statusLp.topMargin = dp(8);
        serverStatus.setLayoutParams(statusLp);
        setupBlock.addView(serverStatus);

        LinearLayout probeRow = row();
        probeRow.setLayoutParams(topMargin(dp(10)));
        testBtn = smallButton(R.string.depth_test_connection, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });
        probeRow.addView(testBtn);
        setupBlock.addView(probeRow);

        // --- model ---
        setupBlock.addView(caption(R.string.depth_model));
        modelEmpty = text(R.string.depth_models_untested, 13);
        modelEmpty.setTextColor(0xFF8A9199);
        setupBlock.addView(modelEmpty);
        modelList = new LinearLayout(activity);
        modelList.setOrientation(LinearLayout.VERTICAL);
        setupBlock.addView(modelList);

        // --- resolution ---
        //
        // No track and no tappable row. Every control in this app gets explicit
        // - and +, because half the adjustments are signed and a one-sided
        // control cannot reach half the range; a row that silently increments on
        // tap is a control that changes a value the user did not aim at.
        setupBlock.addView(caption(R.string.depth_resolution));
        LinearLayout resRow = row();
        TextView resLabel = text(R.string.depth_resolution, 14);
        resRow.addView(resLabel);
        resRow.addView(new View(activity), weight());
        resValue = new TextView(activity);
        resValue.setTextSize(14);
        resValue.setTextColor(0xFF7FD1C1);
        resRow.addView(resValue);
        setupBlock.addView(resRow);

        LinearLayout resBtns = row();
        resBtns.setLayoutParams(topMargin(dp(8)));
        resBtns.addView(nudge(R.string.depth_nudge_down, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudgeResolution(-1);
            }
        }));
        resBtns.addView(new View(activity), weight());
        resBtns.addView(nudge(R.string.depth_nudge_up, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nudgeResolution(1);
            }
        }));
        setupBlock.addView(resBtns);

        // --- actions ---
        LinearLayout actions = row();
        actions.setLayoutParams(topMargin(dp(18)));
        cancelBtn = smallButton(R.string.action_cancel, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actions.addView(cancelBtn);
        generateBtn = smallButton(R.string.depth_generate, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun();
            }
        });
        actions.addView(generateBtn);
        setupBlock.addView(actions);

        return setupBlock;
    }

    /**
     * The failed outcome, below the setup block.
     *
     * <p>Setup stays visible above it on purpose: R-PANEL6 says a failed run
     * keeps the typed address, and the only way to keep it is not to have thrown
     * it away. Hiding the controls would also make Retry unreachable without
     * first re-entering everything.
     */
    private View buildOutcome() {
        outcomeBlock = new LinearLayout(activity);
        outcomeBlock.setOrientation(LinearLayout.VERTICAL);
        outcomeBlock.setVisibility(View.GONE);
        outcomeBlock.setLayoutParams(topMargin(dp(18)));

        outcomeText = text(R.string.fix_no_answer, 13);
        outcomeText.setTextColor(0xFFE2725B);
        outcomeBlock.addView(outcomeText);

        outcomeSubject = text("", 12);
        outcomeSubject.setTextColor(0xFF8A9199);
        outcomeSubject.setVisibility(View.GONE);
        outcomeSubject.setLayoutParams(topMargin(dp(4)));
        outcomeBlock.addView(outcomeSubject);

        LinearLayout actions = row();
        actions.setLayoutParams(topMargin(dp(10)));
        outcomeRetryBtn = smallButton(R.string.fix_try_again, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Retry means retry: the same host, model and resolution, with no
                // confirm step. There is nothing about a retry to confirm.
                startRun();
            }
        });
        actions.addView(outcomeRetryBtn);
        outcomeServerBtn = smallButton(R.string.depth_edit_server, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                revealHostField(true);
            }
        });
        actions.addView(outcomeServerBtn);
        outcomeBlock.addView(actions);

        return outcomeBlock;
    }

    /**
     * The run frame. R-PANEL7: it carries no controls.
     *
     * <p>The job is queued with the model baked in, so a control that appeared
     * to change it could not. The one button here is not a control — it is the
     * way out, and it is the same thing as the back gesture, so hiding it would
     * only trap the user.
     */
    private View buildRun() {
        runBlock = new LinearLayout(activity);
        runBlock.setOrientation(LinearLayout.VERTICAL);
        runBlock.setVisibility(View.GONE);
        runBlock.setLayoutParams(topMargin(dp(18)));

        runStatus = text(R.string.depth_phase_queued, 14);
        runBlock.addView(runStatus);
        runPercent = text("", 13);
        runPercent.setTextColor(0xFF8A9199);
        runPercent.setLayoutParams(topMargin(dp(4)));
        runBlock.addView(runPercent);

        runProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        runProgress.setMax(100);
        runProgress.setLayoutParams(topMargin(dp(10)));
        runBlock.addView(runProgress);

        runHideBtn = smallButton(R.string.fix_run_in_background, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Collapsing never cancels anything (R-S4). The sheet is a window,
                // not a job: the run continues on the server and the pill carries
                // the percentage (R-BG2).
                askNotifyOnce();
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams hideLp = topMargin(dp(14));
        runHideBtn.setLayoutParams(hideLp);
        runBlock.addView(runHideBtn);

        return runBlock;
    }

    // --------------------------------------------------------------- state → view

    /**
     * Put the three blocks into the state they should be showing.
     *
     * <p>The only method that decides what is on screen, and every event ends in
     * a call to it. That is deliberate: the panel has four states and they have
     * to be mutually exclusive, and the way to guarantee that is one switch
     * rather than each event deciding what to hide.
     */
    private void render() {
        boolean running = isRunning();
        setupBlock.setVisibility(running ? View.GONE : View.VISIBLE);
        runBlock.setVisibility(running ? View.VISIBLE : View.GONE);
        outcomeBlock.setVisibility(!running && lastResult() == Result.FAILED
                ? View.VISIBLE : View.GONE);
        if (running) renderRun();
        else renderSetup();
    }

    private void renderSetup() {
        revealHostField(false);
        serverAddress.setText(comfyBase.length() == 0
                ? activity.getString(R.string.depth_server_hint)
                : ComfyWorkflow.hostPort(comfyBase));
        // The dot is the only test result there is. "Not tested" is grey rather
        // than red, because an untested server is not a broken one.
        serverDot.setTextColor(!modelsFetched ? 0xFF6C737B : 0xFF7FD1C1);
        serverStatus.setText(modelsFetched
                ? activity.getString(R.string.depth_models_found, nodes.size())
                : activity.getString(R.string.depth_server_untested));
        serverStatus.setVisibility(View.VISIBLE);
        modelEmpty.setVisibility(modelsFetched ? View.GONE : View.VISIBLE);
        if (modelsFetched) {
            // Both texts are set whenever the list is known, because the list can
            // go from several models to none (the user pointed the app at a
            // different server) and a stale "Test the connection to load models"
            // beside an empty list would send them round the loop.
            modelEmpty.setText(nodes.isEmpty()
                    ? R.string.depth_models_none : R.string.depth_models_untested);
        }
        resValue.setText(activity.getString(R.string.depth_resolution_value, resolution));
        // Generate needs a confirmed model. The list is the SERVER's and cannot
        // be known before it is reached (R-PANEL2), so there is no path to a run
        // that skipped the model choice.
        generateBtn.setEnabled(modelsFetched && !nodes.isEmpty());
        if (lastResult() == Result.FAILED && failureCause.length() > 0) {
            outcomeText.setText(activity.getString(R.string.fix_no_answer) + "\n"
                    + activity.getString(R.string.depth_run_cause, failureCause));
            // Which photo it was FOR. A run outlives the panel and the user may
            // have switched photos while it was going, so "unchanged" is not
            // actionable without the subject attached to it.
            outcomeSubject.setText(activity.getString(R.string.depth_run_was_for,
                    subjectLabel()));
            outcomeSubject.setVisibility(View.VISIBLE);
        }
    }

    private void renderRun() {
        int pct = progressPercent();
        runPercent.setText(pct >= 0
                ? activity.getString(R.string.pill_generating, pct) : "");
        runProgress.setProgress(pct < 0 ? 0 : pct);
    }

    /**
     * Show or hide the address field.
     *
     * <p>Hidden by default because the panel opens with a stored address and the
     * user should never have to re-type it (R-PANEL1 asks once, ever). Shown by
     * Test connection when there is nothing stored, and by Change or Edit server
     * otherwise.
     */
    private void revealHostField(boolean explicit) {
        boolean show;
        if (explicit) {
            show = true;
        } else {
            show = comfyBase == null || comfyBase.length() == 0;
        }
        hostInput.setVisibility(show ? View.VISIBLE : View.GONE);
        serverChangeBtn.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show && hostInput.getText().length() == 0 && comfyBase != null) {
            hostInput.setText(comfyBase);
        }
    }

    // --------------------------------------------------------------- connection

    private void testConnection() {
        // Same rule as startRun: the field only speaks when it is showing.
        String typed = hostInput.getVisibility() == View.VISIBLE
                ? hostInput.getText().toString().trim() : comfyBase;
        if (typed == null || typed.length() == 0) {
            serverStatus.setText(R.string.depth_endpoint_required);
            revealHostField(true);
            return;
        }
        comfyBase = typed;
        // Written immediately, not on success: a typed address that turns out to
        // be wrong is still the address the user wants to correct, and losing it
        // on a failed test makes the second attempt start from nothing. It also
        // means a probe against the wrong address has somewhere to come back to.
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_HOST, comfyBase).apply();
        serverStatus.setText(R.string.depth_testing);
        serverStatus.setVisibility(View.VISIBLE);
        // Cleared as well as marked unfetched. A list left in place beside
        // "Testing..." is a list the user could act on, so Generate would offer a
        // model from a server that is not answering.
        modelsFetched = false;
        nodes.clear();
        displayNames.clear();
        final String host = comfyBase;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> found = new ArrayList<String>();
                    final JSONObject obj = new JSONObject(new ComfyApi(host).getObjectInfo());
                    Iterator<String> it = obj.keys();
                    while (it.hasNext()) {
                        String name = it.next();
                        String lower = name.toLowerCase();
                        if (lower.contains("depth") || lower.contains("midas")
                                || lower.contains("zoe")) {
                            found.add(name);
                        }
                    }
                    Collections.sort(found);
                    final List<String> sorted = found;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            modelsFetched = true;
                            nodes.clear();
                            nodes.addAll(sorted);
                            displayNames.clear();
                            for (String n : sorted) {
                                displayNames.add(ComfyWorkflow.friendlyName(n));
                            }
                            buildModelRows();
                            render();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            modelsFetched = false;
                            nodes.clear();
                            displayNames.clear();
                            // Names the cause, and keeps the typed address where it
                            // was. R-PANEL6.
                            serverStatus.setText(activity.getString(
                                    R.string.depth_test_failed, describe(e)));
                            // The field comes back on a failure, because a failed
                            // probe is exactly when the user needs to edit it.
                            revealHostField(true);
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Rebuild the model options from the fetched list.
     *
     * <p>R-PANEL3: preselect what was used last, if the server still offers it.
     * Depth results get compared, so a silently different model makes the
     * comparison meaningless. If the saved model is gone the first entry is
     * selected and <em>the change is visible</em> — a swap the user cannot see
     * is a swap they will blame the map for.
     */
    private void buildModelRows() {
        modelList.removeAllViews();
        if (nodes.isEmpty()) return;
        int chosen = 0;
        boolean savedGone = false;
        if (savedModel != null) {
            int idx = nodes.indexOf(savedModel);
            if (idx >= 0) {
                chosen = idx;
            } else {
                savedGone = true;
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            final String node = nodes.get(i);
            View row = modelRow(node, displayNames.get(i), i == chosen);
            modelList.addView(row);
        }
        if (savedGone) {
            TextView note = text(activity.getString(R.string.depth_model_gone,
                    ComfyWorkflow.friendlyName(savedModel), displayNames.get(0)), 12);
            note.setTextColor(0xFFE8B04B);
            note.setLayoutParams(topMargin(dp(6)));
            modelList.addView(note);
        }
    }

    private View modelRow(String node, String friendly, boolean selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView mark = new TextView(activity);
        mark.setText(selected ? "◉" : "○");
        mark.setTextSize(15);
        mark.setTextColor(selected ? 0xFF7FD1C1 : 0xFF6C737B);
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(24),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(mark);

        TextView name = text(friendly, 14);
        name.setTag(node);
        row.addView(name);

        // Whole row is the target, not just the text: a list of node names with
        // 20dp hit targets is a list nobody taps correctly.
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectModel(node);
            }
        });
        return row;
    }

    private void selectModel(String node) {
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View child = modelList.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue; // the "gone" note
            LinearLayout row = (LinearLayout) child;
            TextView mark = (TextView) row.getChildAt(0);
            TextView name = (TextView) row.getChildAt(1);
            boolean on = node.equals(name.getTag());
            mark.setText(on ? "◉" : "○");
            mark.setTextColor(on ? 0xFF7FD1C1 : 0xFF6C737B);
        }
    }

    private String selectedModel() {
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View child = modelList.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            if (!"◉".contentEquals(((TextView) row.getChildAt(0)).getText())) continue;
            return String.valueOf(((TextView) row.getChildAt(1)).getTag());
        }
        return null;
    }

    // --------------------------------------------------------------- resolution

    private void nudgeResolution(int delta) {
        int idx = indexOfResolution(resolution);
        int next = idx + delta;
        if (next < 0) next = 0;
        if (next >= RESOLUTIONS.length) next = RESOLUTIONS.length - 1;
        if (RESOLUTIONS[next] == resolution) return;
        resolution = RESOLUTIONS[next];
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putInt(KEY_RES, resolution).apply();
        resValue.setText(activity.getString(R.string.depth_resolution_value, resolution));
    }

    private static int indexOfResolution(int value) {
        int best = 0;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (Math.abs(RESOLUTIONS[i] - value) < Math.abs(RESOLUTIONS[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    private static int snapResolution(int stored) {
        return RESOLUTIONS[indexOfResolution(Math.max(MIN_RESOLUTION, stored))];
    }

    // --------------------------------------------------------------- run

    /**
     * Start a run for the photo on screen.
     *
     * <p>Every entry point converges here — Generate, Retry, and the sheet's
     * Regenerate — because they are the same decision. R-D7: regenerating a
     * photo that already has a map is a normal action, not an error state.
     */
    /**
     * Ask for POST_NOTIFICATIONS the first time a run is sent to the background.
     *
     * <p>Here rather than at launch because this is the only moment the permission
     * is needed, and a prompt shown to everyone for a feature most installations
     * never touch is a cost paid by everyone.
     *
     * <p>Without it the completion notification is silently suppressed on 33+, and
     * the run finishes invisibly: the map applies, the user is told nothing, and a
     * wait that took forty seconds looks like a wait that failed. So this is asked
     * for, once, at the point where the user has just said that they are leaving.
     *
     * <p>One-shot, tracked in prefs, because a permission dialog that reappears on
     * every run is worse than no notification at all.
     */
    private void askNotifyOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (activity.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                    .getBoolean("asked_notify", false)) return;
            activity.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("asked_notify", true).apply();
            if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        } catch (Exception ignored) {
            // A device that does not know the string never gets asked, and the run
            // completes either way. Nothing here is worth failing a run over.
        }
    }

    public void startRun() {
        if (isRunning()) return;
        // Deliberately NOT re-captured here. The panel's subject is fixed when the
        // panel opens, and Retry re-queues the run that just failed: re-reading
        // the selection at this point would aim a retry at whichever photo the
        // user has since switched to, which is a second way to attach a map to
        // the wrong photograph. The panel is re-targeted by being reopened, which
        // is an explicit act.
        if (targetItemId == null || targetImage == null || !targetImage.exists()) {
            // No photo, or the bundled demo, which ships its own map and has no
            // file to generate from.
            serverStatus.setText(R.string.depth_no_subject);
            return;
        }
        // Read the endpoint from the field when it is showing, and from the stored
        // value when it is not. The field is hidden whenever an address is
        // already known, so reading it unconditionally would return "" on the
        // common path and refuse a run against a server the user has already
        // configured — which is exactly what the old code got wrong.
        String host = hostInput.getVisibility() == View.VISIBLE
                ? hostInput.getText().toString().trim() : comfyBase;
        if (host == null || host.length() == 0) {
            serverStatus.setText(R.string.depth_endpoint_required);
            revealHostField(true);
            return;
        }
        comfyBase = host;
        final String model = selectedModel();
        if (model == null) {
            // No model chosen means the server was never reached. Sending a
            // request with a guessed node name would fail server-side with a
            // message about a node rather than about the connection. Testing is
            // the fix, so do that rather than refusing.
            testConnection();
            return;
        }

        final Run r = new Run(activity.getApplicationContext(), comfyBase, model, resolution,
                targetItemId, targetImage.getAbsolutePath(),
                "depth-app-" + System.nanoTime());
        synchronized (RUN_LOCK) {
            if (run != null) return; // a run started while we were checking
            run = r;
        }
        result = Result.NONE;
        failureCause = "";
        progress = 0;
        // Persisted before the request is sent, so a process killed between here
        // and the answer still knows which server to ask.
        pending(r.app, r.toJson());
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE).edit()
                .putString(KEY_HOST, comfyBase)
                .putString(KEY_MODEL, model)
                .putInt(KEY_RES, resolution)
                .apply();
        savedModel = model;
        render();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                submitAndPoll(r);
            }
        }, "depth-run");
        t.setDaemon(true);
        t.start();
    }

    private static void submitAndPoll(final Run r) {
        try {
            ComfyApi api = new ComfyApi(r.host);
            sayPhase(r.app, R.string.depth_phase_uploading);
            String up = api.uploadImage(new File(r.imagePath), new File(r.imagePath).getName());
            JSONObject upObj = new JSONObject(up);
            String name = upObj.getString("name");
            String subfolder = upObj.optString("subfolder", "");
            String uploadedName = subfolder.isEmpty() ? name : subfolder + "/" + name;

            sayPhase(r.app, R.string.depth_phase_queued);
            String info = api.getObjectInfo();
            String workflow = ComfyWorkflow.buildWorkflow(info, r.model, uploadedName,
                    r.resolution, System.currentTimeMillis());
            String resp = api.submitPrompt(workflow, r.clientId);
            JSONObject parsed = new JSONObject(resp);
            if (parsed.has("error")) throw new Exception(parsed.toString());
            r.promptId = parsed.getString("prompt_id");
            // Rewrite with the prompt id now that it exists. Until this lands on
            // disk a killed process has nothing to ask the server about, which is
            // the one window R-BG1 cannot cover — it is a few milliseconds wide.
            pending(r.app, r.toJson());

            startWs(r, "ws://" + ComfyWorkflow.hostPort(r.host)
                    + "/ws?clientId=" + r.clientId);
            pollAndFinish(r, api);
        } catch (final Exception e) {
            stopWs();
            finishFailed(r, describe(e));
        }
    }

    /**
     * Wait for the answer and apply it. Shared by a live run and a resumed one:
     * R-BG1 says on return the app asks the server what happened, so the same
     * code has to do both or the two can disagree.
     */
    private static void pollAndFinish(final Run r, final ComfyApi api) {
        // Set only once a complete map is on disk. It gates the finally below so
        // the slot is not released early on the success path — the UI post that
        // applies the map is queued, and a slot freed before it runs would let a
        // second run start and overwrite the first's map.
        boolean[] reachedSuccess = {false};
        try {
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            JSONObject history = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
                JSONObject ho = new JSONObject(api.getHistory(r.promptId));
                if (ho.has(r.promptId)) {
                    history = ho.getJSONObject(r.promptId);
                    break;
                }
            }
            stopWs();
            if (history == null) {
                throw new Exception("the server did not answer within 10 minutes");
            }
            JSONObject statusObj = history.optJSONObject("status");
            if (statusObj != null && statusObj.optString("status_str", "").equals("error")) {
                throw new Exception(ComfyWorkflow.extractExecutionError(statusObj));
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

            sayPhase(r.app, R.string.depth_phase_fetching);
            byte[] depthBytes = api.view(imgObj.getString("filename"),
                    imgObj.optString("subfolder", ""), imgObj.optString("type", "output"));
            Bitmap depthBmp = BitmapFactory.decodeByteArray(depthBytes, 0, depthBytes.length);
            if (depthBmp == null) throw new Exception("the depth image could not be decoded");

            sayPhase(r.app, R.string.depth_phase_saving);
            Bitmap origBmp = BitmapFactory.decodeFile(r.imagePath);
            if (origBmp == null) throw new Exception("the photo could not be decoded");
            // Written to its FINAL name, next to the user's other files, so the
            // name the index will hold is the name on disk. A temp file plus a
            // rename would be the same thing with an extra step and a second
            // failure mode.
            File packed = new File(r.app.getFilesDir(),
                    "ai_depth_" + System.currentTimeMillis() + ".png");
            try {
                DepthPngPacker.pack(origBmp, depthBmp, packed);
            } finally {
                origBmp.recycle();
                depthBmp.recycle();
            }

            // The file exists. From here the run has succeeded and the map is
            // replaced — there is no path out of this method that reaches the
            // write in applyMap without a complete map on disk.
            reachedSuccess[0] = true;
            finishApplied(r, packed);
        } catch (final Exception e) {
            stopWs();
            finishFailed(r, describe(e));
        } catch (Throwable t) {
            // An OOM packing a 2048 map is the realistic one, and it is not an
            // Exception. Without this it would escape the thread's handler and
            // take the process with it, and — worse — leave `run` set, which
            // disables Generate forever with nothing on screen to say why.
            stopWs();
            finishFailed(r, describe(
                    new Exception(t.getClass().getSimpleName() + ": " + t.getMessage())));
        } finally {
            // Not on the success path: finishApplied's UI post has not run yet,
            // and freeing the slot before the map is applied would let a second
            // run start and overwrite it.
            if (!reachedSuccess[0]) clearRunIfOurs(r);
        }
    }

    /**
     * Release the run slot, if it is still ours.
     *
     * <p>Idempotent, and guarded by identity so a newer run's slot cannot be
     * cleared by an older run's late completion.
     */
    private static void clearRunIfOurs(Run r) {
        synchronized (RUN_LOCK) {
            if (run == r) run = null;
        }
    }

    /**
     * A run succeeded: replace the map, and tell whoever is listening.
     *
     * <p>Hops to the UI thread because the two things it does — writing the
     * index and calling the host's callback — are the studio's, and the studio
     * owns prefs writes. Every View update from a run has to come through this
     * hop; a generation runs for tens of seconds on a worker thread and touching
     * a widget from there is a crash waiting to happen.
     */
    private static void finishApplied(final Run r, final File packed) {
        final String name = packed.getName();
        // Read off the worker, before the hop: a prefs read is safe on any thread
        // and a notification that arrives a frame later is a notification that
        // arrives.
        final String label = labelFor(r.app, r.itemId);
        UI.post(new Runnable() {
            @Override
            public void run() {
                // Cleared here and not left set. `run` is what makes the panel show
                // the run frame and what makes startRun() decline; leaving it set
                // after the run has landed would disable Generate forever, with
                // nothing on screen to say why.
                synchronized (RUN_LOCK) {
                    if (run == r) run = null;
                }
                // The file is on disk already, so there is no state in which the
                // run is over and this has not happened. Ordered before
                // applyMap so nothing can observe APPLIED with a stale map.
                DepthGenDialog panel = openPanel;
                if (panel != null) panel.dialog.dismiss();
                applyMap(r.app, r.itemId, name);
                lastMapName = name;
                lastModel = r.model;
                // Back to "not running", so the pill stops carrying a percentage
                // for a job that has finished (R-BG2: it carries it *while* it
                // runs). The outcome is `result`, not a stuck-at-100 progress.
                progress = -1;
                result = Result.APPLIED;
                clearPending(r.app);
                android.util.Log.i(TAG, "depth: applied " + name + " to item " + r.itemId);
                // After the write, before the notification: the studio has to
                // reload the render either way, and if this throws the map is
                // already on disk and already in the index.
                notifyHost(r.itemId, packed.getAbsolutePath());
                notifyOutcome(r.app, true, label);
            }
        });
    }

    /**
     * The panel's own subject, by name. Read on the UI thread from the index, and
     * it is the panel's captured id — never the current selection, which is the
     * mistake {@link #captureSubject} exists to prevent.
     */
    private String subjectLabel() {
        if (targetItemId == null) return "";
        return labelFor(activity.getApplicationContext(), targetItemId);
    }

    /**
     * The photo's own name, for a notification. Falls back to the id, which is
     * ugly but never wrong, and never to an empty string — a notification that
     * says "Added to " is worse than one that names a UUID.
     */
    private static String labelFor(Context app, String itemId) {
        try {
            LibraryItem item = AppState.load(
                    app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE),
                    app.getFilesDir()).library.find(itemId);
            if (item != null && item.displayName() != null && item.displayName().length() > 0) {
                return item.displayName();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "depth: could not read a label for " + itemId, e);
        }
        return itemId;
    }

    /**
     * A run failed, was cancelled, or never reached the server.
     *
     * <p>Note what is NOT here: no write of {@code depthName}, no removal of the
     * old map, no touch of the item. A failed run leaves the photo exactly as it
     * was — existing map, depth value and relief all intact (R-D5). The fix line
     * says so, because an error that does not say what survived leaves the user
     * unable to tell whether their depth is gone, which is the worst outcome this
     * app has.
     */
    private static void finishFailed(final Run r, final String cause) {
        final String label = labelFor(r.app, r.itemId);
        UI.post(new Runnable() {
            @Override
            public void run() {
                synchronized (RUN_LOCK) {
                    if (run == r) run = null;
                }
                progress = -1;
                failureCause = cause;
                result = Result.FAILED;
                clearPending(r.app);
                android.util.Log.w(TAG, "depth: failed, map unchanged: " + cause);
                DepthGenDialog panel = openPanel;
                if (panel != null) panel.render();
                notifyOutcome(r.app, false, label);
            }
        });
    }

    /**
     * Replace a photo's depth map.
     *
     * <p><b>The one and only write of {@code depthName} in this class.</b> It is
     * called from exactly one place — the success path — which is what makes
     * R-D5 true rather than intended: there is no code path from a failure, a
     * cancel or a timeout to this method.
     *
     * <p>Uses {@link LibraryItem#withDepth}, which preserves every other render
     * value. That is not incidental: {@code withDepth} once reset Motion,
     * brightness and shadow, because it was written through a constructor that
     * defaulted the values added after it.
     */
    private static void applyMap(Context app, String itemId, String depthFileName) {
        SharedPreferences prefs = app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        LibraryStore.editItem(prefs, itemId, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                return item.withDepth(depthFileName);
            }
        });
    }

    // --------------------------------------------------------------- notification

    /**
     * Tell the user a run finished. R-BG3.
     *
     * <p>A plain notification, deliberately. No foreground service and no new
     * permission: a run is tens of seconds of socket reads, and a foreground
     * service for that would be the heavier and more visible answer to a problem
     * the notification does not have.
     *
     * <p><b>API 33+ caveat, and it is a real one.</b> A notification is only
     * <i>shown</i> with {@code POST_NOTIFICATIONS}, and this app does not declare
     * it — adding a runtime permission is a product decision, not one to slip in
     * beside a feature. So above 33 the notification is dropped with a log line
     * saying so, rather than posted into silence. The map is still applied and
     * the fix line still reports it; only the out-of-app nudge is missing, and it
     * is missing loudly in logcat rather than silently on the glass.
     */
    private static void notifyOutcome(Context context, boolean applied, String label) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!nm.areNotificationsEnabled()) {
                android.util.Log.w(TAG, "depth: notifications are disabled; "
                        + "POST_NOTIFICATIONS is not declared (API "
                        + Build.VERSION.SDK_INT + ")");
                return;
            }
            String channelId = NOTIFY_CHANNEL;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId,
                        context.getString(R.string.depth_notify_channel),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(context.getString(R.string.depth_notify_channel_desc));
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, channelId)
                    : new Notification.Builder(context);
            b.setSmallIcon(android.R.drawable.ic_menu_upload);
            b.setContentTitle(context.getString(applied
                    ? R.string.depth_notify_ready : R.string.depth_notify_failed));
            b.setContentText(context.getString(applied
                    ? R.string.depth_notify_ready_text : R.string.depth_notify_failed_text,
                    label));
            b.setAutoCancel(true);
            b.setContentIntent(openStudio(context));
            nm.notify(NOTIFY_ID, b.build());
        } catch (Throwable t) {
            // A missing notification must never take the app down with it, and
            // must never be the reason a run is not applied. The map is already
            // written by the time we get here.
            android.util.Log.w(TAG, "depth: notification failed", t);
        }
    }

    /**
     * An intent that brings the studio forward. The activity is
     * {@code singleTask}, so this resumes the existing task rather than stacking
     * a second copy of the studio — the same reason the manifest declares it that
     * way for a share.
     */
    private static PendingIntent openStudio(Context context) {
        Intent i = new Intent();
        i.setClassName("com.amoshydra.basrelief", "com.amoshydra.basrelief.MainActivity");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, i, flags);
    }

    // --------------------------------------------------------------- progress

    /**
     * Publish progress for the pill and the panel (R-BG2).
     *
     * <p>Static and volatile: the websocket thread writes it and the UI thread
     * reads it, and the websocket belongs to the run rather than to any dialog.
     */
    static void setProgress(int pct) {
        progress = Math.max(0, Math.min(100, pct));
        final DepthGenDialog panel = openPanel;
        if (panel != null) {
            UI.post(new Runnable() {
                @Override
                public void run() {
                    panel.renderRun();
                }
            });
        }
    }

    /**
     * Name the phase, to whichever panel is open.
     *
     * <p>Static for the same reason as {@link #setProgress}: a resumed run has no
     * dialog of its own, and the panel the user opened on their way back must
     * still be told what is happening.
     */
    private static void sayPhase(final Context app, final int stringRes) {
        final String s = app.getString(stringRes);
        UI.post(new Runnable() {
            @Override
            public void run() {
                DepthGenDialog panel = openPanel;
                if (panel != null && panel.runStatus != null) panel.runStatus.setText(s);
            }
        });
    }

    private static void startWs(final Run r, final String url) {
        UI.post(new Runnable() {
            @Override
            public void run() {
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
                                // Map into the 15-85 band: upload and save are
                                // real work and the bar must not sit at zero or
                                // 100 while they happen.
                                if (max > 0) setProgress(15 + value * 70 / max);
                            } else if (type.equals("executing")) {
                                JSONObject d = msg.getJSONObject("data");
                                if (d.has("node") && !d.isNull("node")) {
                                    sayPhase(r.app, R.string.depth_phase_working);
                                }
                            }
                        } catch (Exception ignored) {
                            // The websocket is progress only. History polling is
                            // the source of truth, so a frame we cannot parse is
                            // a frame we drop.
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        // As above: the poll does not need this to work.
                    }

                    @Override
                    public void onClose() {
                    }
                });
                ws.start();
            }
        });
    }

    /**
     * Drop the progress socket. Static and null-only: the socket belongs to the
     * run, and dismissing the panel must not end the run (R-S4) — so this is
     * called when a run finishes, not when a window closes.
     *
     * <p>It deliberately does not {@code stop()} the socket. The poll is the
     * source of truth and is about to end anyway; a half-closed socket mid-poll
     * is a second failure mode for no gain.
     */
    private static void stopWs() {
        ComfyWs live = ws;
        ws = null;
        // Actually closed, not merely forgotten. The old code dropped the
        // reference and left the socket to time out, which on a finished run
        // means a thread parked in a 60s read for nothing; and this is the one
        // place a run's socket can be released, because dismissing the panel must
        // not end the run (R-S4).
        if (live != null) live.stop();
    }

    // --------------------------------------------------------------- prefs

    /**
     * Write the endpoint and resolution from the fields, if they hold something
     * usable, so nothing typed is lost when the panel closes.
     */
    private void persistTyped() {
        if (hostInput == null) return;
        String host = hostInput.getText().toString().trim();
        if (host.length() == 0) return;
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_HOST, host).apply();
    }

    private static void pending(Context app, String json) {
        app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING, json).apply();
    }

    /**
     * Forget the in-flight run.
     *
     * <p>Called only from a terminal outcome. Leaving a stale record behind would
     * make {@link #resumePending} re-ask about a finished job on every launch.
     */
    private static void clearPending(Context app) {
        app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply();
    }

    /**
     * A short, readable reason. ComfyUI's own messages can be a stack trace, and
     * a fix line that wraps a paragraph is a paragraph.
     */
    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return "unknown";
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }

    // --------------------------------------------------------------- views

    private TextView text(int stringRes, float size) {
        TextView t = new TextView(activity);
        t.setText(stringRes);
        t.setTextSize(size);
        return t;
    }

    private TextView text(CharSequence s, float size) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(size);
        return t;
    }

    /** The small uppercase group label, as in the sheet's own headings. */
    private TextView caption(int stringRes) {
        TextView t = text(stringRes, 11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTextColor(0xFF8A9199);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private Button smallButton(int stringRes, View.OnClickListener onClick) {
        Button b = new Button(activity);
        b.setText(stringRes);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    /**
     * A −/+ glyph button.
     *
     * <p>A fixed width rather than {@code setMinWidth}: minWidth is only a floor,
     * so the button still sized itself from its own text and the glyph ended up
     * narrower than the thumb target on a low-density screen.
     */
    private Button nudge(int glyphRes, View.OnClickListener onClick) {
        Button b = smallButton(glyphRes, onClick);
        b.setTextSize(17);
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(int px) {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = px;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Retained for the host's existing call sites. Each is a no-op or a
    // delegate, and each says why, because a method that does nothing and does
    // not say so is how the next reader goes looking for the behaviour.
    // ------------------------------------------------------------------

    /**
     * @deprecated the source is always the photo on screen (R-D8: a share
     *     auto-inserts and selects, and generation only patches that item's
     *     {@code depthName}). Kept so {@code MainActivity} compiles; it now
     *     only re-captures the subject.
     */
    @Deprecated
    public void useCurrentImage() {
        captureSubject();
    }

    /**
     * @deprecated the panel no longer takes a picked image — depth is generated
     *     for the selected photograph, so there is nothing to pick. Kept so
     *     {@code MainActivity.onActivityResult} compiles; it does nothing.
     */
    @Deprecated
    public void onPickedImage(Intent data) {
        // Intentionally empty. See above.
    }
}