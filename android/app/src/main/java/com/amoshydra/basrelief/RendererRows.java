package com.amoshydra.basrelief;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.splat.SplatCloud;
import com.amoshydra.basrelief.core.store.BuildTab;
import com.amoshydra.basrelief.core.store.Jobs;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.store.RenderMode;

import java.io.File;
import java.util.Locale;

/**
 * The sheet's "Renderer — Relief | Splat" row, and the cloud's asset row under it.
 *
 * <p><b>Two rows, one question each.</b> The renderer row is the <i>choice</i> —
 * which draw path this photo uses — and both options are always tappable. The cloud
 * row is the <i>asset state</i>: whether a cloud exists, what it cost, and the one
 * action that fits that state (Make one / Rebuild / Regenerate). Pressing Splat with
 * no cloud is the offer; there is deliberately no "Make 3D" button living on the
 * sheet by default, because the intent comes first and the offer follows.
 *
 * <p><b>It lives in the geometry group, beside the depth rows.</b> That group
 * already answers "what is this item's geometry"; a cloud is the same question
 * answered in 3D. A control nothing reads is a bug, not a greyed row: the relief's
 * Depth and Light rows are hidden in splat mode (see {@code MainActivity}), and the
 * splat has none of its own.
 *
 * <p>The class is built once and refreshed on every {@code refreshSliders}, the same
 * contract {@link FramingRows} has, so a selection change or a finished build
 * repaints the row from the live index rather than from a captured item.
 */
final class RendererRows {

    private final MainActivity activity;
    private final LinearLayout box;

    private final Button reliefBtn;
    private final Button splatBtn;
    private final TextView cloudText;
    private final Button cloudAction;
    private final Button cloudDelete;
    private final LinearLayout cloudRow;

    RendererRows(MainActivity activity) {
        this.activity = activity;

        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        // The mode row: a caption and the same two-button track the Home/Lock
        // segment uses, so a switch looks like a switch wherever it appears.
        box.addView(caption());
        LinearLayout seg = new LinearLayout(activity);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        int pad = activity.dp(3);
        seg.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(activity.dp(10));
        bg.setStroke(activity.dp(1), 0x24FFFFFF);
        seg.setBackground(bg);
        reliefBtn = segmentButton(activity.getString(R.string.renderer_relief), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(RenderMode.RELIEF);
            }
        });
        splatBtn = segmentButton(activity.getString(R.string.renderer_splat), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // PRESSING SPLAT IS THE OFFER. Selecting the mode is the row's job;
                // the dialog follows only when there is no cloud to show, which is
                // what "the intent comes first, the offer follows" means. The offer
                // is now the one build dialog, on its Splat tab, so a share, a
                // Rebuild and this press all land in the same place.
                boolean wasRelief = !isSplatSelected();
                setMode(RenderMode.SPLAT);
                LibraryItem item = activity.state().activeItem();
                if (wasRelief && item != null && !hasCloud(item) && !isBuilding(item)) {
                    activity.openBuildDialog(BuildTab.Kind.SPLAT);
                }
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        slp.leftMargin = activity.dp(2);
        seg.addView(reliefBtn, rlp);
        seg.addView(splatBtn, slp);
        box.addView(seg);

        // The asset row: a line of state and the one action that fits it.
        cloudRow = new LinearLayout(activity);
        cloudRow.setOrientation(LinearLayout.HORIZONTAL);
        cloudRow.setGravity(Gravity.CENTER_VERTICAL);
        cloudText = new TextView(activity);
        cloudText.setTextSize(12);
        cloudText.setTextColor(0x8CFFFFFF);
        cloudRow.addView(cloudText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cloudAction = activity.makeButton("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCloudAction();
            }
        }, LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
        cloudRow.addView(cloudAction);
        // Delete sits beside Rebuild once a cloud exists. It is a destructive action,
        // so it is a quiet plate rather than a filled one, and it is hidden whenever
        // the action button is not Rebuild.
        cloudDelete = activity.makeButton(activity.getString(R.string.splat_action_delete),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LibraryItem item = activity.state().activeItem();
                        if (item != null) activity.deleteCloud(item);
                    }
                }, LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
        cloudRow.addView(cloudDelete);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = activity.dp(6);
        cloudRow.setLayoutParams(crp);
        box.addView(cloudRow);
    }

    View view() {
        return box;
    }

    /** One caption, styled like the sheet's other family captions. */
    private TextView caption() {
        TextView t = new TextView(activity);
        t.setText(R.string.renderer_caption);
        t.setTextSize(11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0x57FFFFFF);
        t.setPadding(0, activity.dp(6), 0, activity.dp(4));
        return t;
    }

    private Button segmentButton(String label, View.OnClickListener tap) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(activity.dp(8), 0, activity.dp(8), 0);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setText(label);
        b.setOnClickListener(tap);
        return b;
    }

    // ---------------------------------------------------------------- state

    private boolean isSplatSelected() {
        LibraryItem item = activity.state().activeItem();
        return item != null && item.isSplatMode();
    }

    private boolean hasCloud(LibraryItem item) {
        if (item.splatName == null) return false;
        File f = PrefFiles.resolve(activity.getFilesDir(), item.splatName);
        return f != null && f.isFile();
    }

    private boolean isBuilding(LibraryItem item) {
        return activity.splatJob(item.id) != null || SplatImport.isRunning(item.id);
    }

    /** The record for this row's cloud build, or null. */
    private Jobs.Job jobFor(LibraryItem item) {
        return activity.splatJob(item.id);
    }

    // ---------------------------------------------------------------- refresh

    /** Repaint the whole row from the live index. Called by {@code refreshSliders}. */
    void refresh() {
        LibraryItem item = activity.state().activeItem();
        if (item == null) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);

        boolean splat = item.isSplatMode();
        style(reliefBtn, !splat);
        style(splatBtn, splat);

        Jobs.Job job = jobFor(item);
        if (job != null && job.isFailed()) {
            // The server's own words, kept until the user acts — a failure that
            // exists only in a toast is gone before it is read.
            cloudText.setText(activity.getString(R.string.splat_row_failed, job.error));
            cloudText.setTextColor(0xE8E8B04B);
            showAction(activity.getString(R.string.action_regenerate), true, false);
            return;
        }
        cloudText.setTextColor(0x8CFFFFFF);
        if (isBuilding(item)) {
            SplatImport.Live live = SplatImport.live(item.id);
            String phase = live == null || live.phase.isEmpty()
                    ? activity.getString(R.string.splat_row_building)
                    : live.phase;
            cloudText.setText(phase);
            // (progress): the ring is on the tile's corner; the row states the phase.
            showAction(null, false, false);
            return;
        }
        if (hasCloud(item)) {
            cloudText.setText(activity.getString(R.string.splat_row_built,
                    countOf(item), activity.getString(R.string.splat_generator)));
            showAction(activity.getString(R.string.action_rebuild), true, true);
            return;
        }
        cloudText.setText(R.string.splat_row_none);
        showAction(activity.getString(R.string.splat_action_make), true, false);
    }

    /** How many records the cloud holds, from the file's own size. */
    private String countOf(LibraryItem item) {
        File f = PrefFiles.resolve(activity.getFilesDir(), item.splatName);
        long n = f == null ? 0 : f.length() / SplatCloud.RECORD_BYTES;
        return String.format(Locale.US, "%,d", n);
    }

    private void showAction(String label, boolean enabled, boolean deletable) {
        cloudDelete.setVisibility(deletable ? View.VISIBLE : View.GONE);
        if (label == null) {
            cloudAction.setVisibility(View.GONE);
            return;
        }
        cloudAction.setVisibility(View.VISIBLE);
        cloudAction.setText(label);
        cloudAction.setEnabled(enabled);
        cloudAction.setAlpha(enabled ? 1f : 0.4f);
    }

    private void style(Button b, boolean on) {
        b.setTextColor(on ? 0xFFFFFFFF : 0x8CFFFFFF);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(on ? 0x28FFFFFF : 0x00000000);
        tb.setCornerRadius(activity.dp(7));
        b.setBackground(tb);
    }

    // ---------------------------------------------------------------- actions

    private void setMode(RenderMode mode) {
        LibraryItem item = activity.state().activeItem();
        if (item == null || item.renderMode == mode) return;
        activity.setRenderMode(item.id, mode);
    }

    private void onCloudAction() {
        LibraryItem item = activity.state().activeItem();
        if (item == null) return;
        // EVERY route into a cloud build is the one dialog's Splat tab: the offer
        // when there is no cloud, the rebuild when there is, and acting on a
        // failure. The dialog reads the failure record for itself and labels its
        // button "Regenerate"; clearing the record is part of submitting, in the
        // dialog, so this does not clear it here — a screen that clears a state it
        // is about to show is how the server's own words get lost before they are
        // read.
        activity.openBuildDialog(BuildTab.Kind.SPLAT);
    }
}
