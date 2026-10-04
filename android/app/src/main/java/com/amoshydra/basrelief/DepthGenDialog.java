package com.amoshydra.basrelief;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.depth.ModelRanking;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import android.app.Activity;
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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
 * <p><b>The panel is a {@link ModalSheet}, and the mockup is the spec.</b> It used
 * to be a bare {@code Dialog} at 92% of the screen width with no background of its
 * own, so what shipped was the platform's light-themed rounded frame inset 4% each
 * side — not the design's full-bleed card over a scrim — and every control in it
 * was a stock {@code Button}, which on a light theme arrives with its own
 * background and no text colour of ours. So: the shared modal, and
 * {@link ModalSheet}'s widgets for every button, caption and field. The one thing
 * this file still builds by hand is what the mockup draws here and nowhere else.
 *
 * <p><b>The server field is always visible, and that is a deliberate loss.</b> PRD
 * R-PANEL5 asks for a link that says where the app is pointed — {@code •} host
 * {@code Change} — so nobody has to remember which box they typed, and this panel
 * used to do exactly that, hiding the field whenever an address was stored. The
 * mockup draws a full-width field with the stored address as its value and a Test
 * connection row under it, and the mockup wins: an address you cannot see is an
 * address you cannot check, and "Change" is one more step between a wrong guess
 * and a working server. So there is no dot, no address line and no status line
 * beside the field any more — one note under the button is where the panel says
 * anything at all. <b>If you are here to restore R-PANEL5, read this first.</b>
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
     * The model chooser's history — the last few selections, most recent first,
     * one model name per line. Separate from {@link #KEY_MODEL}, which is the
     * single saved choice a run was last sent to: that one is a comparison key
     * (R-PANEL3), this one is only the display order. The ordering arithmetic and
     * the stored form are {@link ModelRanking}'s.
     */
    private static final String KEY_MODEL_HISTORY = "comfy_model_history";

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

    /** {@code .modal{max-height:88%}}. */
    private static final float MAX_HEIGHT = 0.88f;
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
    /** The normal the last successful run also wrote, or empty for a flat run. */
    private static volatile String lastNormalName = "";
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

    /**
     * Told when a run starts, ends, or changes its percentage.
     *
     * <p>Exists because the studio's pill and fix line are the only things that can
     * say a run is happening, and neither is on the run's own thread. Without this the
     * pill was repainted only when the user collapsed the sheet -- so a run that
     * FAILED while the sheet was collapsed left the pill reading "Generating · 62%"
     * indefinitely, and the only way to clear it was to open and close the sheet.
     *
     * <p>A weak reference for the same reason {@link #host} is one: the studio is
     * destroyed and recreated on every rotation, and a run outlives it.
     */
    public interface RunListener {
        void onRunStateChanged();
    }

    private static volatile WeakReference<RunListener> runListenerRef;

    /** Register (or clear, with null) the listener for run-state changes. */
    public static void setRunListener(RunListener listener) {
        runListenerRef = listener == null ? null : new WeakReference<RunListener>(listener);
    }

    /** UI thread only, so a listener may touch views directly. */
    private static void notifyRunStateChanged() {
        WeakReference<RunListener> ref = runListenerRef;
        final RunListener l = ref == null ? null : ref.get();
        if (l == null) return;
        UI.post(new Runnable() {
            @Override
            public void run() {
                l.onRunStateChanged();
            }
        });
    }

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
     * completion can reach the UI thread through one hop.
     *
     * <p><b>Weak, and that is the price of the shared modal.</b> The panel used to
     * null this field from its own {@code onDismiss}, which was sound while the
     * panel owned its {@code Dialog}. {@link ModalSheet} owns the window and
     * exposes no dismissal hook, so nothing here can null this field when the user
     * backs out of the panel — and a static strong reference to a dismissed panel
     * pins its Activity for the life of the process, for a window nobody can see.
     * So it is a {@link WeakReference}, the same reason {@link #host} is one. The
     * studio still holds its panel strongly for as long as it is alive, so a live
     * panel is still found; a dismissed one stops being one.
     */
    private static volatile WeakReference<DepthGenDialog> openPanel;

    /** The open panel, or null. See {@link #openPanel} for why it is weak. */
    private static DepthGenDialog openPanel() {
        WeakReference<DepthGenDialog> ref = openPanel;
        return ref == null ? null : ref.get();
    }

    // --------------------------------------------------------------- instance

    private final Activity activity;
    private final Callback callback;
    private final ModalSheet sheet;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * The studio, for {@link BasreliefSlider}.
     *
     * <p>That control is built by {@code MainActivity.dp} and resolves its own
     * label, so it asks for the studio rather than an {@code Activity} — and the
     * manifest declares exactly one activity, so this is the studio on every path
     * that can reach here. Checked rather than cast blind all the same: a host that
     * is not the studio gets a panel with the stored resolution and no control to
     * change it, which is a gap, rather than a crash on the way in.
     */
    private final MainActivity studio;

    /**
     * The photo this panel is generating for.
     *
     * <p>Captured at {@link #show()}, not read at completion. The user can
     * select a different photo while a run is in flight — the old code wrote
     * {@code depthName} against whatever was selected when the answer arrived,
     * which attaches one photo's depth map to another photo.
     */
    private String targetItemId;

    /** The image the depth is generated for, or null when nothing is selected. */
    private File targetImage;

    // The one screen. Three blocks, always all present, so changing a value
    // never navigates and never discards what was typed elsewhere. Only the
    // widgets something else has to reach are fields: the rest are built and
    // added in one method, and a field nothing reads is a field nobody maintains.
    private LinearLayout setupBlock;
    private LinearLayout runBlock;
    private LinearLayout outcomeBlock;

    private EditText hostInput;
    private TextView serverNote;

    private LinearLayout modelList;
    private TextView modelEmpty;

    private int resolution;

    /** The footer. Hidden while a run is up, and greyed until a model is known. */
    private Button generateBtn;

    private TextView runStatus;
    private TextView runPercent;

    private TextView outcomeText;
    private TextView outcomeSubject;

    private String comfyBase;
    private String savedModel;
    /**
     * The last few models the user chose, most recent first. Read once when the
     * panel is built and written on every selection, so the rows a Test
     * connection builds put the familiar checkpoints on top. It is deliberately
     * not the same value as {@link #savedModel}: a model can be tapped without a
     * run, and that tap is still the signal that decides the order.
     */
    private List<String> modelHistory;
    private final List<String> nodes = new ArrayList<String>();
    private final List<String> displayNames = new ArrayList<String>();
    private boolean modelsFetched;
    /**
     * True when the server had depth-named nodes but none this app can drive.
     *
     * <p>Set only after a fetch that produced no usable model. It is what lets
     * {@link #renderSetup} say "none of these work from one image" instead of the
     * false "No depth models on this server".
     */
    private boolean modelsUnusable;

    /**
     * The selection dot of each model's row, keyed by node name.
     *
     * <p>Selection used to be a {@code ◉}/{@code ○} glyph and {@link #selectModel}
     * walked the list's children by index, reading a tag off the name — so the
     * shape of a row was load-bearing, and adding the "saved model is gone" note to
     * the same container was one more thing the walk had to know to skip. The dot
     * is now either visible or not, and this is a map: selecting a model is a
     * lookup and a visibility flip, and the rows know nothing about it.
     */
    private final Map<String, View> modelDots = new HashMap<String, View>();

    /** The node a run would be sent to. Written when the rows are built or tapped. */
    private String chosenModel;

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
     * <p>Immutable except {@link #promptId} and {@link #normalExpected}, which are
     * written by the worker and read by the resume path. Those are the two values
     * that cannot be known when the run is created: the prompt id until the server
     * answers, and whether the graph emits a normal until the graph is built.
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
        /**
         * Whether the graph submitted for this run emits a normal beside its
         * depth. Recorded when the graph is built — from the same decision
         * {@code buildWorkflow} makes, not a second heuristic — because the
         * completion path has to know whether a missing normal is a classical run
         * or a failure. Persisted so a resumed run is judged the same way.
         */
        volatile boolean normalExpected;
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
                o.put("normalExpected", normalExpected);
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
                r.normalExpected = o.optBoolean("normalExpected", false);
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
        this.studio = activity instanceof MainActivity ? (MainActivity) activity : null;
        this.sheet = build();
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
        openPanel = new WeakReference<DepthGenDialog>(this);
        render();
        sheet.show(activity);
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
        // A run that also wrote a normal says so: flat vs shaded is the difference
        // the map line is most likely to be asked about.
        if (lastNormalName.length() > 0) b.append(" + normal");
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
     * sensible source. Every photo qualifies, including the bundled demo: it is a
     * file-backed row since the seed copied its pair into {@code filesDir}, so it
     * has an image to upload and an id to write the answer against. Only a photo
     * that is not there -- nothing selected -- leaves this panel with no subject,
     * and {@link #startRun} says so rather than failing at the server.
     */
    private void captureSubject() {
        SharedPreferences prefs =
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE);
        LibraryItem active = AppState.load(activity, prefs, activity.getFilesDir())
                .activeItem();
        if (active == null) {
            targetItemId = null;
            targetImage = null;
            return;
        }
        targetItemId = active.id;
        // Stored as a bare filename; resolve against this install's filesDir.
        targetImage = PrefFiles.resolve(activity.getFilesDir(), active.imageName);
    }

    // --------------------------------------------------------------- build

    private ModalSheet build() {
        final SharedPreferences prefs =
                activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE);
        comfyBase = prefs.getString(KEY_HOST, "");
        savedModel = prefs.getString(KEY_MODEL, null);
        modelHistory = ModelRanking.decode(prefs.getString(KEY_MODEL_HISTORY, ""));
        resolution = snapResolution(prefs.getInt(KEY_RES, DEFAULT_RESOLUTION));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        addRow(root, ModalSheet.title(activity, activity.getString(R.string.depth_title)));
        addRow(root, ModalSheet.subtitle(activity,
                activity.getString(R.string.depth_panel_subtitle)));
        addRow(root, buildSetup());
        root.addView(buildOutcome());
        root.addView(buildRun());

        // The mockup's .btnrow: Cancel ghost beside Generate primary. They live in
        // the modal's footer rather than at the end of the body, which is
        // ModalSheet's one departure from the mockup and a deliberate one — the body
        // is this long, and a footer that scrolls away is a Generate button the user
        // cannot find on a short screen. Cancel is in every state, because a panel
        // with no way out of it is a trap; Generate is the setup state's alone, and
        // {@link #render} says why the other two do without it.
        Button cancel = ModalSheet.button(activity,
                activity.getString(R.string.action_cancel), false, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        generateBtn = ModalSheet.button(activity, activity.getString(R.string.depth_generate),
                true, false);
        generateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun();
            }
        });

        ModalSheet modal = new ModalSheet(activity, MAX_HEIGHT, ModalSheet.SCRIM);
        // Every ModalSheet carries a handle bar. On this form it matters more
        // than on a short one: the sheet is tall, the content scrolls, and the
        // Cancel/Generate footer is a commit and not a dismissal -- so before the
        // bar this screen's only way out was Cancel, which reads as "give up on
        // the run". The bar closes without committing anything, which is the
        // truth: a run started is a run that continues. See depth_close.
        modal.grip(activity.getString(R.string.depth_close));
        modal.body(root);
        modal.footerRow(cancel, generateBtn);
        return modal;
    }

    private View buildSetup() {
        setupBlock = new LinearLayout(activity);
        setupBlock.setOrientation(LinearLayout.VERTICAL);

        // --- server ---
        //
        // The endpoint is enterable, not merely discovered. "Discovered
        // automatically" is a dead end the first time the server is on another
        // machine, which is the normal case: discovery only ever worked on this
        // LAN, and the server is usually elsewhere. See the class javadoc for why
        // this field is shown even when an address is already stored.
        addRow(setupBlock, ModalSheet.caption(activity,
                activity.getString(R.string.depth_server), true));

        hostInput = ModalSheet.field(activity, comfyBase,
                activity.getString(R.string.depth_server_hint));
        // A hostname, not a number: the default soft keyboard offers digits and
        // a field the user cannot paste an address into is a field they will
        // mistype.
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        // Written as it is typed, not on the way out. R-PANEL6 wants a typed
        // address to survive the panel being closed, and the panel no longer owns
        // its window — ModalSheet dismisses it, on the back gesture as well as on
        // Cancel — so there is no onDismiss left to save it from. Typing it is the
        // only moment this class can be sure of.
        hostInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                persistTyped();
            }
        });
        addRow(setupBlock, hostInput);

        Button test = ModalSheet.button(activity,
                activity.getString(R.string.depth_test_connection), false, false);
        test.setLayoutParams(topMargin(dp(9)));
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });
        setupBlock.addView(test);

        // The one line this panel reports on: the mockup's `.note` under the button.
        // It replaces the status line that used to sit beside the field, and it is
        // GONE rather than blank when there is nothing to say — an empty note is an
        // ornament that teaches the eye to stop reading the line.
        serverNote = ModalSheet.note(activity, "");
        serverNote.setVisibility(View.GONE);
        // Copyable: a ComfyUI refusal can be a stack trace, and a user reporting
        // a connection problem needs to be able to paste it rather than
        // photograph it. Same reason the old status line was selectable.
        serverNote.setTextIsSelectable(true);
        addRow(setupBlock, serverNote);

        // --- model ---
        addRow(setupBlock, ModalSheet.caption(activity,
                activity.getString(R.string.depth_model), false));
        modelEmpty = text(R.string.depth_models_untested, 13);
        modelEmpty.setTextColor(ModalSheet.DIM);
        addRow(setupBlock, modelEmpty);
        modelList = new LinearLayout(activity);
        modelList.setOrientation(LinearLayout.VERTICAL);
        addRow(setupBlock, modelList);

        // --- resolution ---
        //
        // One row: the label, a 34dp track with the size printed on the fill, and
        // the pair of nudges. It used to be a label line, a value and two buttons at
        // the extremes — about 130dp for a control the design draws in 34. The
        // value is an index into RESOLUTIONS rather than a pixel count, because a
        // slider over 256..2048 lands on sizes the server is never asked for.
        if (studio != null) {
            addRow(setupBlock, ModalSheet.caption(activity,
                    activity.getString(R.string.depth_resolution_caption), false));
            setupBlock.addView(buildResolutionSlider().view());
        }

        return setupBlock;
    }

    /**
     * The mockup's {@code slider('Output', …, '1024×1024')}, on the same control the
     * studio's own sliders use.
     *
     * <p>The label is {@code depth_resolution} — "Output" — passed as the row's
     * <em>name</em>. {@link BasreliefSlider} resolves a key through its own switch
     * and falls back to drawing the key as itself, and this is the one caller that
     * uses that fallback deliberately: resolution is not one of the studio's
     * adjustments and has no key there, while the caption above the row already
     * says RESOLUTION and the row still needs a name of its own, exactly as the
     * mockup draws it.
     */
    private BasreliefSlider buildResolutionSlider() {
        return BasreliefSlider.create(studio,
                activity.getString(R.string.depth_resolution),
                indexOfResolution(resolution), 0, RESOLUTIONS.length - 1,
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int index) {
                        return activity.getString(R.string.depth_resolution_value,
                                RESOLUTIONS[index]);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int index) {
                        setResolution(RESOLUTIONS[index]);
                    }
                });
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
        addRow(outcomeBlock, outcomeText);

        outcomeSubject = text("", 12);
        outcomeSubject.setTextColor(ModalSheet.DIM);
        outcomeSubject.setVisibility(View.GONE);
        outcomeSubject.setLayoutParams(topMargin(dp(4)));
        outcomeBlock.addView(outcomeSubject);

        // One action, not two, and it is the design's word: the mockup's failed
        // state offers "Try again" and "Choose a map", and the second of those
        // belongs to the sheet — a map chosen from the gallery is not a generation.
        // "Edit server" is gone because the server field is always on screen, so
        // there is nothing left for it to reveal.
        Button retry = ModalSheet.button(activity,
                activity.getString(R.string.fix_try_again), false, false);
        retry.setLayoutParams(topMargin(dp(9)));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Retry means retry: the same host, model and resolution, with no
                // confirm step. There is nothing about a retry to confirm.
                startRun();
            }
        });
        outcomeBlock.addView(retry);

        return outcomeBlock;
    }

    /**
     * The run frame. R-PANEL7: it carries no controls.
     *
     * <p>The job is queued with the model baked in, so a control that appeared
     * to change it could not. The one button here is not a control — it is the
     * way out, and it is the same thing as the back gesture, so hiding it would
     * only trap the user.
     *
     * <p><b>No progress bar.</b> There was one, and the design has none: the
     * percentage this panel was drawing twice is the same number the studio's fix
     * line carries while the sheet is open and the pill carries when it is
     * collapsed (R-BG2), and a third copy of it on a bar is a fourth place for it
     * to be wrong. What is left is the two lines that say something the bar could
     * not: which step of the job this is, and how far along it is.
     */
    private View buildRun() {
        runBlock = new LinearLayout(activity);
        runBlock.setOrientation(LinearLayout.VERTICAL);
        runBlock.setVisibility(View.GONE);
        runBlock.setLayoutParams(topMargin(dp(18)));

        runStatus = text(R.string.depth_phase_queued, 14);
        runStatus.setTextColor(ModalSheet.INK);
        addRow(runBlock, runStatus);
        runPercent = text("", 13);
        runPercent.setTextColor(ModalSheet.DIM);
        runPercent.setLayoutParams(topMargin(dp(4)));
        runBlock.addView(runPercent);

        Button runInBackground = ModalSheet.button(activity,
                activity.getString(R.string.fix_run_in_background), false, false);
        runInBackground.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Collapsing never cancels anything (R-S4). The sheet is a window,
                // not a job: the run continues on the server and the pill carries
                // the percentage (R-BG2).
                askNotifyOnce();
                sheet.dismiss();
            }
        });
        runInBackground.setLayoutParams(topMargin(dp(9)));
        runBlock.addView(runInBackground);

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
        boolean failed = !running && lastResult() == Result.FAILED;
        setupBlock.setVisibility(running ? View.GONE : View.VISIBLE);
        runBlock.setVisibility(running ? View.VISIBLE : View.GONE);
        outcomeBlock.setVisibility(failed ? View.VISIBLE : View.GONE);
        // Generate is the setup state's action. While a run is going the run block
        // says what it is doing, and after one has failed the outcome block offers
        // "Try again" by name — so in both of those states the footer's Generate is
        // a second button for a decision the user has already been given a word for.
        generateBtn.setVisibility(running || failed ? View.GONE : View.VISIBLE);
        if (running) renderRun();
        else renderSetup();
    }

    private void renderSetup() {
        modelEmpty.setVisibility(modelsFetched ? View.GONE : View.VISIBLE);
        if (modelsFetched) {
            // Both texts are set whenever the list is known, because the list can
            // go from several models to none (the user pointed the app at a
            // different server) and a stale "Test the connection to load models"
            // beside an empty list would send them round the loop. The empty case
            // has two causes and they must not be collapsed: the server may have
            // no depth node at all, or it may have depth nodes this app cannot
            // drive from one image.
            modelEmpty.setText(!nodes.isEmpty()
                    ? R.string.depth_models_untested
                    : (modelsUnusable
                            ? R.string.depth_models_unusable
                            : R.string.depth_models_none));
        }
        // Generate needs a confirmed model. The list is the SERVER's and cannot
        // be known before it is reached (R-PANEL2), so there is no path to a run
        // that skipped the model choice.
        setUsable(generateBtn, modelsFetched && !nodes.isEmpty());
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
    }

    /**
     * Dim a button and take its taps, without changing how it looks when it is live.
     *
     * <p>{@code setEnabled(false)} alone would not do it: the platform dims a
     * disabled control from the <em>disabled state of its background</em>, and
     * {@link ModalSheet}'s plates are one {@code GradientDrawable} with no states,
     * so Generate would go inert and stay looking pressable. This is the same 0.4
     * {@link BasreliefSlider} uses for a row it has switched off.
     */
    private void setUsable(Button b, boolean usable) {
        b.setEnabled(usable);
        b.setAlpha(usable ? 1f : 0.4f);
    }

    /**
     * Say something, in the one line under Test connection.
     *
     * <p>Replaces the status line this panel used to keep beside the server field.
     * There is no dot and no "Not tested" any more: an untested server is not a
     * broken one, so the field and the button beside it are the whole answer, and
     * the note only speaks when something has actually happened.
     */
    private void say(int stringRes) {
        serverNote.setText(stringRes);
        serverNote.setVisibility(View.VISIBLE);
    }

    private void say(CharSequence s) {
        serverNote.setText(s);
        serverNote.setVisibility(View.VISIBLE);
    }

    private void sayNothing() {
        serverNote.setText("");
        serverNote.setVisibility(View.GONE);
    }

    // --------------------------------------------------------------- connection

    private void testConnection() {
        // The field is always on screen, so it always speaks. It used to be read
        // only when it was visible, because an address was otherwise stored and
        // hidden — and that is the rule that made a configured server unreachable
        // from a panel opened on the common path.
        String typed = hostInput.getText().toString().trim();
        if (typed.length() == 0) {
            say(R.string.depth_endpoint_required);
            return;
        }
        comfyBase = typed;
        // Written immediately, not on success: a typed address that turns out to
        // be wrong is still the address the user wants to correct, and losing it
        // on a failed test makes the second attempt start from nothing. It also
        // means a probe against the wrong address has somewhere to come back to.
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_HOST, comfyBase).apply();
        say(R.string.depth_testing);
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
                    // The filter is :core's, so the list the user sees is exactly
                    // the set the graph builder can submit — a node kept here and
                    // rejected there would be a generation failure with no clue
                    // that the model choice caused it.
                    final JSONObject obj = new JSONObject(new ComfyApi(host).getObjectInfo());
                    final List<String> sorted = new ArrayList<String>(
                            ComfyWorkflow.usableDepthModels(obj));
                    // Only asked when the list came back empty, and only so the
                    // message can tell "no depth nodes" from "depth nodes that
                    // cannot be driven".
                    final boolean filteredToEmpty =
                            sorted.isEmpty() && ComfyWorkflow.hasDepthNode(obj);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            modelsFetched = true;
                            modelsUnusable = filteredToEmpty;
                            // Most-recently-used first, then discovery order. The
                            // history decides what the "first entry" fallback is,
                            // which is what replaced the dialog's hard-coded MoGe
                            // preference — and why it must be re-applied on every
                            // fetch rather than stored as a fixed list.
                            nodes.clear();
                            nodes.addAll(ModelRanking.rank(sorted, modelHistory));
                            displayNames.clear();
                            for (String n : nodes) {
                                displayNames.add(ComfyWorkflow.friendlyName(n));
                            }
                            buildModelRows();
                            // The note said "Testing connection…", which is no
                            // longer true and would sit under the list it just
                            // fetched. The rows are the report.
                            sayNothing();
                            render();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            modelsFetched = false;
                            modelsUnusable = false;
                            nodes.clear();
                            displayNames.clear();
                            // The rows go too. A list the server has just failed to
                            // answer is a list of models from the server before it,
                            // left on screen next to Generate.
                            buildModelRows();
                            // Names the cause, and keeps the typed address where it
                            // was. R-PANEL6.
                            say(activity.getString(R.string.depth_test_failed, describe(e)));
                            // The field does not need revealing: a failed probe is
                            // exactly when the user needs to edit it, and it is
                            // never hidden in the first place.
                            render();
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
     *
     * <p>The list arrives already ordered by {@link ModelRanking}: most recently
     * used first, then discovery order. That replaced a hard-coded "prefer the
     * first MoGe entry" rule, which selected whichever checkpoint the server
     * happened to list first. With the history, the first entry is the model the
     * user last reached for, and a real selection is what promotes it — so this
     * method is unchanged in spirit and only the ordering moved.
     */
    private void buildModelRows() {
        modelList.removeAllViews();
        modelDots.clear();
        if (nodes.isEmpty()) {
            // Nothing to choose from, so nothing is chosen: a selection left over
            // from the previous server would be a model the run would be sent to
            // without the user ever seeing it offered.
            chosenModel = null;
            return;
        }
        // The saved model, when the server still offers it, else the first entry —
        // and the first entry is the most recently used one, by construction.
        int chosen = 0;
        boolean savedGone = false;
        int savedIdx = savedModel == null ? -1 : nodes.indexOf(savedModel);
        if (savedIdx >= 0) {
            chosen = savedIdx;
        } else if (savedModel != null) {
            savedGone = true;
        }
        chosenModel = nodes.get(chosen);
        for (int i = 0; i < nodes.size(); i++) {
            final String node = nodes.get(i);
            LinearLayout row = modelRow(node, displayNames.get(i), i == chosen);
            // The selection dot is the swatch tile's own child. Taken once, here,
            // and keyed by node name, because {@link ModalSheet#option} builds the
            // row and does not hand it back — and because a row's shape should not
            // be load-bearing for the next reader.
            FrameLayout tile = (FrameLayout) row.getChildAt(0);
            View dot = tile.getChildAt(0);
            dot.setVisibility(i == chosen ? View.VISIBLE : View.GONE);
            modelDots.put(node, dot);
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

    /**
     * One model, as the mockup's {@code .opt}: a tile with a dot on it, the name it
     * is known by, and the node the server calls it.
     *
     * <p>The whole row is the target, not just the name: a list of node names with
     * 20dp hit targets is a list nobody taps correctly.
     */
    private LinearLayout modelRow(final String node, String friendly, boolean selected) {
        LinearLayout row = ModalSheet.option(activity, friendly, node, selected, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectModel(node);
                    }
                });
        return row;
    }

    private void selectModel(String node) {
        for (Map.Entry<String, View> e : modelDots.entrySet()) {
            e.getValue().setVisibility(node.equals(e.getKey()) ? View.VISIBLE : View.GONE);
        }
        chosenModel = node;
        // The selection itself is the signal, not a finished run: a user who taps
        // a checkpoint and backs out has still said which one they reach for. The
        // rows are deliberately not reordered under the finger; this is the order
        // the next Test connection builds from, so the list does not jump as the
        // user is reading it.
        modelHistory = ModelRanking.remember(modelHistory, node);
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putString(KEY_MODEL_HISTORY, ModelRanking.encode(modelHistory))
                .apply();
    }

    private String selectedModel() {
        return chosenModel;
    }

    // --------------------------------------------------------------- resolution

    /**
     * The size the next run will ask for.
     *
     * <p>Written here rather than from the control, so the value that is stored and
     * the value the row is showing are the same write: the slider repaints itself
     * from the value it was given, and nothing here has to go and tell it.
     */
    private void setResolution(int value) {
        if (value == resolution) return;
        resolution = value;
        activity.getSharedPreferences(AppState.PREFS, Activity.MODE_PRIVATE)
                .edit().putInt(KEY_RES, value).apply();
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
        // Whatever the panel last said about the connection was said about a probe,
        // and this is not that probe. Cleared before anything below can report, so
        // the line cannot be about to be replaced by a different one.
        sayNothing();
        // R-BG3: battery saver is the OS saying the phone cannot afford this
        // run — minutes of upload, polling and a decode is the one thing this
        // panel does that a phone in saver should not be doing. Refused at
        // the tap rather than by greying Generate, because saver can be
        // flipped while the panel is open and nothing here is re-rendered on
        // it; a button greyed on stale state refuses before the user can
        // even try, while the note refuses with the reason on screen — and a
        // button that does nothing with no explanation is the failure this
        // project bans. PowerSave.isOn is the one read path both hosts use,
        // so the user's "follow battery saver" switch is honoured here
        // without a second read of it: a user who turned that switch off
        // still gets to generate.
        if (PowerSave.isOn(activity)) {
            say(R.string.depth_battery_saver);
            return;
        }
        // Deliberately NOT re-captured here. The panel's subject is fixed when the
        // panel opens, and Retry re-queues the run that just failed: re-reading
        // the selection at this point would aim a retry at whichever photo the
        // user has since switched to, which is a second way to attach a map to
        // the wrong photograph. The panel is re-targeted by being reopened, which
        // is an explicit act.
        if (targetItemId == null || targetImage == null || !targetImage.exists()) {
            // Nothing was selected, or the photo behind the selection is not on this
            // device. The latter is reachable -- a restore that carried the index and
            // not its files -- and it is the only honest answer: there is no image to
            // upload, and saying so here is better than a failed run at the server.
            say(R.string.depth_no_subject);
            return;
        }
        // The endpoint comes from the field, which is always on screen and is
        // seeded with the stored address. A configured server is one tap from a
        // run and a wrong one is one edit from being right.
        String host = hostInput.getText().toString().trim();
        if (host.length() == 0) {
            say(R.string.depth_endpoint_required);
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
        // Announced here, where `run` is already set, so the studio's fix line can
        // say "Generating" for the whole wait rather than appearing when it is over.
        notifyRunStarted();
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
            // The graph's own decision, not a second heuristic: whether this run
            // also emits a normal. Recorded before the run can finish so the fetch
            // path knows whether a missing normal is an ordinary classical run
            // (flat lit) or a failure. Persisted with the prompt id below.
            r.normalExpected = ComfyWorkflow.isMoGeModel(new JSONObject(info), r.model);
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
            // Two SaveImage nodes run in one prompt for a MoGe run (depth and
            // normal), so the outputs are classified by the filename prefix the
            // builder wrote rather than by taking the first node. A fetch path that
            // only read the first image would silently drop the normal, and the
            // photo would render flat with no error anywhere.
            JSONObject depthImg = null;
            JSONObject normalImg = null;
            Iterator<String> it = outputs.keys();
            while (it.hasNext()) {
                JSONObject node = outputs.optJSONObject(it.next());
                if (node == null || !node.has("images")) continue;
                JSONArray images = node.getJSONArray("images");
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    if (img == null) continue;
                    String filename = img.optString("filename", "");
                    if (depthImg == null
                            && filename.startsWith(ComfyWorkflow.DEPTH_FILE_PREFIX)) {
                        depthImg = img;
                    } else if (normalImg == null
                            && filename.startsWith(ComfyWorkflow.NORMAL_FILE_PREFIX)) {
                        normalImg = img;
                    }
                }
            }
            if (depthImg == null) throw new Exception("no depth output in history");
            // A MoGe run was submitted with a second saver for the normal. If it
            // did not come back, the run did not do what its graph said: fail loudly
            // rather than apply a depth that renders flat and reads as "worked".
            // A classical run legitimately has no normal and is flat lit.
            if (r.normalExpected && normalImg == null) {
                throw new Exception("the run produced no normal map");
            }

            sayPhase(r.app, R.string.depth_phase_fetching);
            byte[] depthBytes = api.view(depthImg.getString("filename"),
                    depthImg.optString("subfolder", ""), depthImg.optString("type", "output"));
            Bitmap depthBmp = BitmapFactory.decodeByteArray(depthBytes, 0, depthBytes.length);
            if (depthBmp == null) throw new Exception("the depth image could not be decoded");

            sayPhase(r.app, R.string.depth_phase_saving);
            Bitmap origBmp = BitmapFactory.decodeFile(r.imagePath);
            if (origBmp == null) throw new Exception("the photo could not be decoded");
            // One stamp for both files, so the pair this run produced is named
            // together and cannot collide with another run's.
            long stamp = System.currentTimeMillis();
            // Written to its FINAL name, next to the user's other files, so the
            // name the index will hold is the name on disk. A temp file plus a
            // rename would be the same thing with an extra step and a second
            // failure mode.
            File packed = new File(r.app.getFilesDir(), "ai_depth_" + stamp + ".png");
            try {
                DepthPngPacker.pack(origBmp, depthBmp, packed);
            } finally {
                origBmp.recycle();
                depthBmp.recycle();
            }

            // The normal, beside the packed depth. Written raw -- MoGe returns a
            // PNG -- but decoded first so a corrupt file fails here rather than as a
            // silent flat render later. The name is derived from the same stamp as
            // the depth's, so the two files a run produced are visibly a pair.
            String normalName = null;
            if (normalImg != null) {
                byte[] normalBytes = api.view(normalImg.getString("filename"),
                        normalImg.optString("subfolder", ""),
                        normalImg.optString("type", "output"));
                Bitmap check = BitmapFactory.decodeByteArray(
                        normalBytes, 0, normalBytes.length);
                if (check == null) throw new Exception("the normal image could not be decoded");
                check.recycle();
                File normalFile = new File(r.app.getFilesDir(), "ai_normal_" + stamp + ".png");
                java.io.FileOutputStream out = new java.io.FileOutputStream(normalFile);
                try {
                    out.write(normalBytes);
                } finally {
                    out.close();
                }
                normalName = normalFile.getName();
            }

            // The files exist. From here the run has succeeded and the depth (and
            // its normal, if any) is replaced — there is no path out of this method
            // that reaches the write in applyMap without a complete map on disk. The
            // two are written in ONE edit, so a photo cannot end up shaded by a
            // normal that belongs to a different depth.
            reachedSuccess[0] = true;
            finishApplied(r, packed, normalName);
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
    private static void finishApplied(final Run r, final File packed, final String normalName) {
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
                // The files are on disk already, so there is no state in which the
                // run is over and this has not happened. Ordered before
                // applyMap so nothing can observe APPLIED with a stale map.
                DepthGenDialog panel = openPanel();
                if (panel != null) panel.sheet.dismiss();
                // Depth and normal in ONE edit: a half-applied pair would shade the
                // new geometry with the old normal, which is worse than flat.
                applyMap(r.app, r.itemId, name, normalName);
                lastMapName = name;
                lastNormalName = normalName == null ? "" : normalName;
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
                notifyRunStateChanged();
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
            LibraryItem item = AppState.load(app,
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
                DepthGenDialog panel = openPanel();
                if (panel != null) panel.render();
                notifyOutcome(r.app, false, label);
                // The studio has to be told, or its pill keeps saying "Generating"
                // until someone happens to collapse the sheet again.
                notifyRunStateChanged();
            }
        });
    }

    /**
     * Replace a photo's depth map and its supplied normal.
     *
     * <p><b>The one and only write of {@code depthName} and {@code normalName} in
     * this class, and they are written together.</b> It is called from exactly one
     * place — the success path — which is what makes R-D5 true rather than intended:
     * there is no code path from a failure, a cancel or a timeout to this method.
     *
     * <p>Uses {@link LibraryItem#withDepthAndNormal}, the paired writer, so a MoGe
     * run installs the depth and the normal it predicted for it in one edit, and a
     * classical run installs the depth alone (a null normal) — which is the flat-lit
     * state. That is not incidental: {@code withDepth} once reset Motion, brightness
     * and shadow, because it was written through a constructor that defaulted the
     * values added after it, and the paired writer exists for the same reason.
     */
    private static void applyMap(Context app, String itemId,
                                 String depthFileName, String normalFileName) {
        SharedPreferences prefs = app.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE);
        LibraryStore.editItem(prefs, itemId, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                return item.withDepthAndNormal(depthFileName, normalFileName);
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
    /**
     * A run has begun. Told at the start, not only at the end, because "Generating
     * depth - 62%" is the one sentence the fix line owes the user while the wait is
     * happening; a line that appears only when the wait is over has missed the only
     * moment it was written for.
     */
    public static void notifyRunStarted() {
        notifyRunStateChanged();
    }

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
        final DepthGenDialog panel = openPanel();
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
                DepthGenDialog panel = openPanel();
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
     *
     * <p>Which is also why the panel's dismissal stopped calling this. It used to,
     * from {@code onDismiss}, which contradicted the sentence above and cost the
     * pill its percentage: the number the socket carries is the one the collapsed
     * sheet shows (R-BG2), so closing the panel used to freeze it part-way. The
     * socket now ends with the run, in every terminal path, and nothing else.
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
     * Save whatever is in the address field, if it is usable. R-PANEL6.
     *
     * <p>Called as the field is typed rather than when the panel closes, because
     * the panel does not own its window any more and so cannot know when it is
     * closing — see the watcher in {@link #buildSetup}. The resolution does not need
     * the same treatment: {@link #setResolution} writes it the moment the slider
     * moves, because the control that holds the value is the one that changed it.
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

    /**
     * Put a row in a column, as wide as the column.
     *
     * <p>{@code addView} with no {@code LayoutParams} of its own gives a child
     * WRAP_CONTENT, and a column of WRAP_CONTENT children is a column that shrinks
     * to the widest of them — so an {@code EditText} in one comes out as wide as the
     * address in it rather than as wide as the card, and a centred note comes out
     * centred inside its own width. Neither is what the mockup draws, and the second
     * is invisible until the text is long enough to matter.
     */
    private void addRow(LinearLayout column, View child) {
        column.addView(child, lp());
    }

    private static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
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