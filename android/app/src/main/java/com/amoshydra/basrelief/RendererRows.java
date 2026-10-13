package com.amoshydra.basrelief;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.AssetActions;
import com.amoshydra.basrelief.core.store.BuildTab;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.RenderMode;

/**
 * The sheet's "Renderer — Relief | Splat" row, and the one shared asset row under
 * it.
 *
 * <p><b>Two rows, one question each.</b> The renderer row is the <i>choice</i> —
 * which draw path this photo uses — and both options are always tappable. The row
 * under it is the <i>secondary actions</i> on the row's built assets: <b>Delete</b>
 * a cloud, and one depth action (<b>Replace</b> a still map, or <b>Regenerate</b> a
 * clip's track). Which of those apply is {@link AssetActions}, in {@code :core}, so
 * it is a tested decision rather than a branch on this screen.
 *
 * <p><b>Why the primary action is not here.</b> The cloud row used to state the
 * cloud's state and offer its one action (<i>Make one</i> / <i>Rebuild</i> /
 * <i>Regenerate</i>), and a bottom "This photo's map" section offered Regenerate and
 * Replace — two rows restating what the 3D-build dialog's tabs already resolve. The
 * user reported exactly that: <i>the rebuild/delete row sat right below the tabs while the photo-map row at the bottom offered
    regenerate/replace, and the two were expected to be combined</i> So the primary lives only in the dialog
 * (where it can be configured), the bottom section is gone, and this row holds the
 * secondary actions the tabs deliberately do not.
 *
 * <p><b>The tab owns its primary action.</b> Tapping the tab you are already on
 * opens the build dialog on it — Generate / Regenerate for Relief, Make one /
 * Rebuild / Regenerate for Splat. Switching to the other tab just switches, except
 * that a tab with nothing built yet offers its build, which is the offer the Splat
 * tab has always made. That is what keeps Rebuild and Regenerate reachable after the
 * cloud row stopped carrying them, without a dialog on every mode switch.
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
    /** The depth slot: Replace a still map, or Regenerate a clip's track. */
    private final Button depthActionBtn;
    private final Button cloudDeleteBtn;
    private final LinearLayout assetRow;

    /**
     * What {@link #depthActionBtn} is currently offering.
     *
     * <p>The listener is installed once and reads this, rather than the button
     * carrying a new listener on every refresh — the label and the action must be the
     * same value, and re-binding a listener per repaint is how they come apart.
     */
    private AssetActions.DepthAction shownDepthAction = AssetActions.DepthAction.NONE;

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
                        onTabTapped(BuildTab.Kind.RELIEF);
                    }
                });
        splatBtn = segmentButton(activity.getString(R.string.renderer_splat),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onTabTapped(BuildTab.Kind.SPLAT);
                    }
                });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, activity.dp(30), 1f);
        slp.leftMargin = activity.dp(2);
        seg.addView(reliefBtn, rlp);
        seg.addView(splatBtn, slp);
        box.addView(seg);

        // The shared asset row: the secondary actions only, each shown when it can
        // actually act. See the class javadoc for what it replaced.
        assetRow = new LinearLayout(activity);
        assetRow.setOrientation(LinearLayout.HORIZONTAL);
        depthActionBtn = activity.makeButton("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LibraryItem item = activity.state().activeItem();
                if (item == null) return;
                switch (shownDepthAction) {
                    case REPLACE:
                        // Reuses the active item's id, so it overwrites this photo's
                        // map rather than starting a new gallery row.
                        activity.replaceDepthMap();
                        break;
                    case REGENERATE:
                        // A clip regenerates its TRACK SET through the video graph --
                        // the image graph would write a one-frame depth over a
                        // playing clip's depth track. DepthSource.canRegenerate says
                        // a track may ask for this; the routing is the half the
                        // renderer cannot know.
                        activity.regenerateClip(item);
                        break;
                    default:
                        break;
                }
            }
        }, 0, 1f);
        assetRow.addView(depthActionBtn);
        cloudDeleteBtn = activity.makeButton(activity.getString(R.string.splat_action_delete),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        LibraryItem item = activity.state().activeItem();
                        if (item != null) activity.deleteCloud(item);
                    }
                }, 0, 1f);
        assetRow.addView(cloudDeleteBtn);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        crp.topMargin = activity.dp(6);
        assetRow.setLayoutParams(crp);
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

        // The secondary actions, resolved once. A row with nothing to offer hides
        // the whole row: a control that cannot change anything is a lie, and an
        // empty row is that lie with more pixels.
        AssetActions.Answer actions = activity.assetActionsFor(item);
        shownDepthAction = actions.depthAction();
        if (actions.depthAction() == AssetActions.DepthAction.NONE) {
            depthActionBtn.setVisibility(View.GONE);
        } else {
            depthActionBtn.setVisibility(View.VISIBLE);
            depthActionBtn.setText(actions.depthAction() == AssetActions.DepthAction.REPLACE
                    ? R.string.action_replace : R.string.action_regenerate);
        }
        cloudDeleteBtn.setVisibility(actions.canDeleteCloud() ? View.VISIBLE : View.GONE);
        assetRow.setVisibility(actions.isNone() ? View.GONE : View.VISIBLE);
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
     * A tab was tapped: switch the mode, and offer that tab's primary build when the
     * tab is the one already selected, or when it has nothing built yet.
     *
     * <p>The mode is switched first and unconditionally, because the segment is also
     * the mode switch — the picture follows the tab even when no dialog opens. The
     * dialog is the tab's <i>primary action</i> (Generate / Regenerate, Make one /
     * Rebuild / Regenerate), which is why a re-tap opens it: the primary has no other
     * home on this sheet, and the bottom block that used to carry Regenerate is gone.
     */
    private void onTabTapped(BuildTab.Kind kind) {
        LibraryItem item = activity.state().activeItem();
        if (item == null) return;
        boolean wasCurrent = (kind == BuildTab.Kind.SPLAT) == item.isSplatMode();
        setMode(BuildTab.modeFor(kind));
        BuildTab.Answer answer = activity.buildTabAnswer(kind, item);
        // The offer: a tab with nothing built yet (or a failed build) opens its
        // dialog, which is the behaviour the Splat tab has always had, now stated
        // for both tabs and both run states.
        if (wasCurrent || answer.state() == BuildTab.State.NONE
                || answer.state() == BuildTab.State.FAILED) {
            activity.openBuildDialog(kind);
        }
    }

    private void setMode(RenderMode mode) {
        LibraryItem item = activity.state().activeItem();
        if (item == null || item.renderMode == mode) return;
        activity.setRenderMode(item.id, mode);
    }
}
