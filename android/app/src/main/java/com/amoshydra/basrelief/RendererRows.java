package com.amoshydra.basrelief;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.AssetRow;
import com.amoshydra.basrelief.core.store.BuildTab;
import com.amoshydra.basrelief.core.store.Jobs;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.RenderMode;

/**
 * The sheet's "Renderer — Relief | Splat" row, and the asset row under it.
 *
 * <p><b>Two rows, two questions, and only one asset row exists.</b> The renderer row
 * is the <i>mode selector</i> — which draw path this photo uses — and both options are
 * always tappable. The row directly under it is the <b>selected mode's state and its
 * primary action</b>, beside the secondary actions that belong to that mode. Which
 * actions those are is {@link AssetRow}, in {@code :core}, so it is a tested decision
 * rather than a branch on this screen.
 *
 * <p><b>Why the row holds the primary again.</b> It briefly held only the secondary
 * actions, on the reasoning that the 3D-build dialog's tabs already stated each kind's
 * state and offered its primary. That reasoning <i>hid</i> both facts behind a dialog:
 * the user reported it as <i>the splat regeneration action and the splat count had gone missing</i> — the cloud's count and its Rebuild were one tap behind a
 * control you had to know to tap. So the row states the selected mode's asset and
 * offers its primary, and the dialog becomes the <i>settings and confirm</i> the
 * primary opens: the budget on the Splat tab, the model and resolution on the Relief
 * tab.
 *
 * <p><b>The tabs are only the mode switch.</b> Tapping one switches the picture's draw
 * path and nothing else; the row's primary button is the entry to a build, and it
 * always was. The "tap the tab you are on to reach its action" behaviour is gone with
 * the reason it existed.
 *
 * <p><b>It lives in the geometry group, beside the depth rows.</b> That group
 * already answers "what is this item's geometry"; a cloud is the same question
 * answered in 3D.
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
    /** The state sentence: what the selected mode's asset is. */
    private final TextView stateText;
    /** The first button (left), and the second (right). */
    private final Button leadBtn;
    private final Button trailBtn;
    private final LinearLayout assetRow;

    /**
     * What the two buttons are currently offering, and the tab their build opens.
     *
     * <p>The listeners are installed once and read these, rather than each button
     * carrying a new listener on every refresh — the label and the action must be the
     * same value, and re-binding a listener per repaint is how they come apart.
     */
    private AssetRow.Action shownLead = AssetRow.Action.NONE;
    private AssetRow.Action shownTrail = AssetRow.Action.NONE;
    private BuildTab.Kind shownTab = BuildTab.Kind.RELIEF;

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
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0x14FFFFFF);
        segBg.setCornerRadius(activity.dp(10));
        segBg.setStroke(activity.dp(1), 0x24FFFFFF);
        seg.setBackground(segBg);
        reliefBtn = segmentButton(activity.getString(R.string.renderer_relief),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setMode(RenderMode.RELIEF);
                    }
                });
        splatBtn = segmentButton(activity.getString(R.string.renderer_splat),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setMode(RenderMode.SPLAT);
                    }
                });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        slp.leftMargin = activity.dp(2);
        seg.addView(reliefBtn, rlp);
        seg.addView(splatBtn, slp);
        box.addView(seg);

        // The asset row: the selected mode's sentence, then its buttons. The state
        // takes the space and the buttons hug the right, the shape the cloud row
        // already had.
        assetRow = new LinearLayout(activity);
        assetRow.setOrientation(LinearLayout.HORIZONTAL);
        assetRow.setGravity(Gravity.CENTER_VERTICAL);
        stateText = new TextView(activity);
        stateText.setTextSize(12);
        stateText.setTextColor(0x8CFFFFFF);
        assetRow.addView(stateText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        leadBtn = assetButton(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction(shownLead);
            }
        });
        assetRow.addView(leadBtn);
        trailBtn = assetButton(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction(shownTrail);
            }
        });
        assetRow.addView(trailBtn);
        LinearLayout.LayoutParams arp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arp.topMargin = activity.dp(6);
        assetRow.setLayoutParams(arp);
        box.addView(assetRow);
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

    /**
     * One asset-row button: the sheet's plate, hugging its label.
     *
     * <p>{@code WRAP_CONTENT} rather than the equal share {@code makeButton} defaults
     * to, because the row's buttons are beside a sentence rather than filling the row
     * — the same shape the cloud row used.
     */
    private Button assetButton(View.OnClickListener tap) {
        return activity.makeButton("", tap, LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
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

        AssetRow.Answer row = activity.assetRowFor(item);
        shownTab = row.tab();
        shownLead = row.lead();
        shownTrail = row.trail();
        stateText.setText(stateTextFor(row, item));
        show(leadBtn, row.lead());
        show(trailBtn, row.trail());
        // The gap belongs between the two buttons and nowhere else: a right margin on
        // both leaves a dead strip at the row's right edge.
        boolean both = row.lead() != AssetRow.Action.NONE && row.trail() != AssetRow.Action.NONE;
        setRightMargin(leadBtn, both ? activity.dp(8) : 0);
        setRightMargin(trailBtn, 0);
        // A row with nothing to say and nothing to offer is hidden: an empty row is a
        // control that cannot change anything with more pixels. A sentence with no
        // button is not a control, so a row that has something to say stays.
        assetRow.setVisibility(row.isNone() ? View.GONE : View.VISIBLE);
    }

    /**
     * The sentence for the selected mode's state, from the live row.
     *
     * <p>Only the Splat states carry a run: a cloud build has no fix line, so its
     * phase and its failure's own words are the sheet's only statement of it. A Relief
     * state is always the depth's own kind — a depth run is the fix line's, and a
     * second copy would be staler. The count is {@link BuildDialog#cloudCount}, read
     * from the cloud FILE, because a rebuild writes the same filename.
     */
    private CharSequence stateTextFor(AssetRow.Answer row, LibraryItem item) {
        switch (row.state()) {
            case NO_DEPTH:       return activity.getString(R.string.build_relief_none);
            case HAS_DEPTH:      return activity.getString(R.string.build_relief_built);
            case DEPTH_EMBEDDED: return activity.getString(R.string.build_relief_embedded);
            case DEPTH_TRACK:    return activity.getString(R.string.asset_row_depth_track);
            case NO_CLOUD:       return activity.getString(R.string.splat_row_none);
            case CLOUD:          return activity.getString(R.string.splat_row_built,
                    BuildDialog.cloudCount(activity, item));
            case BUILDING:       return BuildDialog.splatPhase(activity, item);
            case FAILED: {
                Jobs.Job job = activity.splatJob(item.id);
                return activity.getString(R.string.splat_row_failed, job == null ? "" : job.error);
            }
            case NONE:
            default:             return "";
        }
    }

    /** Show one button with its action's own word, or hide it. */
    private void show(Button b, AssetRow.Action action) {
        if (action == AssetRow.Action.NONE) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        b.setText(labelFor(action));
    }

    /**
     * The word for one button.
     *
     * <p>The three build actions go through {@link BuildDialog#labelFor}, so the row's
     * primary and the dialog's own footer cannot grow two words for one action. The
     * rest are the words this sheet already uses elsewhere.
     */
    private int labelFor(AssetRow.Action action) {
        switch (action) {
            case BUILD:
            case REBUILD:
            case REGENERATE:
                return BuildDialog.labelFor(action.tabAction(), shownTab);
            case REPLACE:        return R.string.action_replace;
            case CHOOSE:         return R.string.fix_choose_map;
            case DELETE:         return R.string.splat_action_delete;
            case REGENERATE_CLIP: return R.string.action_regenerate;
            case NONE:
            default:             return 0;
        }
    }

    private void setRightMargin(Button b, int px) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp == null) return;
        lp.rightMargin = px;
        b.setLayoutParams(lp);
    }

    private void style(Button b, boolean on) {
        b.setTextColor(on ? 0xFFFFFFFF : 0x8CFFFFFF);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(on ? 0x28FFFFFF : 0x00000000);
        tb.setCornerRadius(activity.dp(7));
        b.setBackground(tb);
    }

    // ---------------------------------------------------------------- actions

    /**
     * One button was pressed: run what it currently offers.
     *
     * <p>Resolved at CLICK time, not build time, for the reason on the class: the row
     * is repainted per selection but a click can land after another change. The action
     * is the one the row is showing, so the label and the effect cannot come apart.
     */
    private void onAction(AssetRow.Action action) {
        LibraryItem item = activity.state().activeItem();
        if (item == null) return;
        switch (action) {
            case BUILD:
            case REBUILD:
            case REGENERATE:
                // The dialog is the primary's settings-and-confirm, opened on the
                // selected mode's own tab.
                activity.openBuildDialog(shownTab);
                break;
            case REPLACE:
            case CHOOSE:
                // One picker for both: the file reuses the active item's id, so it
                // overwrites this photo's map or adds the first one.
                activity.replaceDepthMap();
                break;
            case DELETE:
                activity.deleteCloud(item);
                break;
            case REGENERATE_CLIP:
                // A clip regenerates its TRACK SET through the video graph -- the
                // image graph would write a one-frame depth over a playing clip's
                // depth track. The dialog's Relief tab refuses a track by BuildTab's
                // own rule, which is why this action has no tab to live in.
                activity.regenerateClip(item);
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Switch the picture's draw path. The tabs are only the mode switch; a build is
     * the row's primary button.
     */
    private void setMode(RenderMode mode) {
        LibraryItem item = activity.state().activeItem();
        if (item == null || item.renderMode == mode) return;
        activity.setRenderMode(item.id, mode);
    }
}
