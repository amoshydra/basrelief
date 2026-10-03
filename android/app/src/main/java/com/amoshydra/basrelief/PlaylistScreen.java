package com.amoshydra.basrelief;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Playlist;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;
import com.amoshydra.basrelief.core.store.Trigger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The playlists screen: a list, and an editor.
 *
 * <p>Built to {@code .design/prototype/final.html}, Playlists tab. Every colour,
 * size and gap below is that file's value with the alpha unpacked, so a design
 * change is a diff against the stylesheet rather than a judgement call.
 *
 * <p><b>This screen writes one key, and it is not the index.</b> Everything it can
 * do — name, trigger, surfaces, rotation on or off, unlink a photo, delete a
 * playlist — is a {@link Playlists} edit, and every write goes through {@link
 * PlaylistStore}, which has no {@link Library} parameter anywhere in it. There is
 * deliberately no "rotate now" and no tappable photograph in the editor, because
 * both would want to write a selection and the index already owns it. A second
 * pointer is a wallpaper showing a photograph the user deleted, so the way to
 * keep that from happening is not to have the second writer: the one operation
 * that <i>does</i> write the selection is {@link Playlists#advance}, and nothing
 * here calls it.
 *
 * <p><b>Unlinking a photograph is not deleting one.</b> Taking a photo out of a
 * playlist writes the playlists blob and leaves the index alone, because the
 * photo is still in the gallery. The other half of that story — a photo actually
 * deleted, which has to leave every playlist in the same derivation — belongs to
 * {@link Playlists#afterRemoving} and is called by whoever deletes the row, not
 * from here.
 *
 * <p><b>On pickup is shown, not hidden.</b> It is a real {@link Trigger}, and a
 * stored one has to survive a round trip, so the row is present, carries the
 * reason it cannot be chosen, and stays selected if something else chose it. A
 * control that silently does nothing is the one failure mode this project's
 * constitution rules out.
 *
 * <p>Implemented by a separate agent against the signed-off mockup; see
 * {@code .design/prototype/final.html}, Playlists tab.
 */
final class PlaylistScreen {

    // ---------------------------------------------- the mockup's own numbers
    // --panel #171b21 · --line rgba(255,255,255,.14) · --accent #7fd1c1
    // --dim rgba(255,255,255,.55) · --dimmer rgba(255,255,255,.34)

    private static final int MODAL_BG = 0xFF171B21;
    private static final int LINE = 0x24FFFFFF;
    private static final int SCRIM = 0x9E000000;
    private static final int ACCENT = 0xFF7FD1C1;
    private static final int DIM = 0x8CFFFFFF;
    private static final int DIMMER = 0x57FFFFFF;
    private static final int PRIMARY_INK = 0xFF0B0D10;
    /** A swatch with no photograph in it — the gallery's own tile fill. */
    private static final int TILE = 0xFF2E3B44;
    private static final int TILE_BUNDLED = 0xFF4A423A;
    /** The mockup's `.locked`, which is how an unavailable row reads. */
    private static final float LOCKED_ALPHA = 0.42f;

    private static final int MODAL_RADIUS_DP = 20;
    private static final int MODAL_PAD_DP = 16;
    private static final int MODAL_PAD_TOP_DP = 18;
    private static final int MODAL_PAD_BOTTOM_DP = 24;
    /** `.modal { max-height: 88% }`. */
    private static final float MODAL_MAX_FRACTION = 0.88f;
    /** `.btn { padding: 13px }` around a 14px line, which measures 49. */
    private static final int BUTTON_DP = 49;
    private static final int BUTTON_RADIUS_DP = 11;
    private static final int BUTTONROW_GAP_DP = 8;
    private static final int BUTTONROW_TOP_DP = 14;

    private static final int OPTION_PAD_DP = 12;
    private static final int OPTION_RADIUS_DP = 11;
    private static final int OPTION_GAP_DP = 8;
    /** `.opt .sw`, and the `.sw.on::after` dot, whose 14px includes its 2px ring. */
    private static final int SWATCH_DP = 34;
    private static final int SWATCH_RADIUS_DP = 9;
    private static final int DOT_DP = 14;
    private static final int DOT_OFFSET_DP = -3;

    private final MainActivity activity;

    /** The one window. Both states live in it, so a row tap swaps the contents. */
    private Dialog dialog;
    /** The modal's body: the scroll plus the button row, rebuilt per state. */
    private LinearLayout panel;
    /** The scroll of the current state, so an inset can re-cap it. */
    private CappedScroll scroll;
    /** The playlist being edited, or null for the list. */
    private String openId;
    /** The editor's name field, held so a commit does not have to find it. */
    private EditText nameField;
    /** The navigation-bar inset, folded into the modal's bottom padding. */
    private int bottomInset;
    // The editor builds its own window rather than using ModalSheet -- it
    // predates it, and its capped scroll and per-state rebuild are its own -- so
    // it carries the inset itself. It shares ModalSheet.insetBottom so the two
    // cannot resolve the bar differently. The delete confirm is the one
    // ModalSheet here: it is a question rather than a state of this editor, and
    // it should look like the questions the gallery asks.

    private PlaylistScreen(MainActivity activity) {
        this.activity = activity;
    }

    /** Open the playlists screen over the studio. */
    static void show(MainActivity activity) {
        new PlaylistScreen(activity).present();
    }

    // ------------------------------------------------------------------ widgets

    private int dp(int v) {
        return activity.dp(v);
    }

    private String str(int res) {
        return activity.getString(res);
    }

    private GradientDrawable plain(int fill, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** A vertical container that fills the card's width. */
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** The mockup's `.opt`: an outlined card with no fill, so the modal shows through. */
    private LinearLayout option() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP));
        GradientDrawable bg = plain(Color.TRANSPARENT, dp(OPTION_RADIUS_DP));
        bg.setStroke(dp(1), LINE);
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(OPTION_GAP_DP);
        r.setLayoutParams(lp);
        return r;
    }

    /**
     * A swatch: the tile, and the accent dot in its bottom-right corner when the
     * row is on.
     *
     * <p>The dot is drawn <i>outside</i> the tile, which is why the tile's frame
     * does not clip its children. A dot inside the tile would be a second mark
     * lying on the photograph, and the mockup is unambiguous about which of the
     * two it draws.
     *
     * @param photo a decoded thumbnail, or null for a plain tile
     */
    private View swatch(int fill, ImageView photo, boolean on) {
        FrameLayout box = new FrameLayout(activity);
        box.setClipChildren(false);
        box.setBackground(plain(fill, dp(SWATCH_RADIUS_DP)));
        if (photo != null) {
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(photo, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        if (on) box.addView(dot(), dotParams());
        // Purely decorative: the state it carries is announced on the row.
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // .opt{gap:12px}: the tile is the row's leading mark, and the text that
        // follows it used to start at the tile's own edge -- no gap at all --
        // unlike every card built by ModalSheet.option, which has always put 12dp
        // between the two.
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                dp(SWATCH_DP), dp(SWATCH_DP));
        swatchLp.rightMargin = dp(12);
        box.setLayoutParams(swatchLp);
        return box;
    }

    private View dot() {
        View v = new View(activity);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ACCENT);
        // The modal's own colour, so the dot reads as sitting on the card rather
        // than inside the photograph.
        d.setStroke(dp(2), MODAL_BG);
        v.setBackground(d);
        return v;
    }

    private FrameLayout.LayoutParams dotParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(DOT_DP), dp(DOT_DP), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(DOT_OFFSET_DP), dp(DOT_OFFSET_DP));
        return lp;
    }

    /**
     * A row's name and detail line, held together.
     *
     * <p>One object rather than two out-parameters, because {@link #announce} has
     * to reach both text views by reference to take them out of the accessibility
     * tree, and a builder that could only hand back the container would force it
     * to walk children it does not own.
     */
    private static final class Row {

        final LinearLayout box;
        final TextView title;
        final TextView detail;

        Row(LinearLayout box, TextView title, TextView detail) {
            this.box = box;
            this.title = title;
            this.detail = detail;
        }
    }

    private Row texts(String title, String detail) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        // The mockup's `.opt b`, which the browser resolves to 700.
        t.setTypeface(null, Typeface.BOLD);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        TextView d = null;
        if (detail != null && !detail.isEmpty()) {
            d = new TextView(activity);
            d.setText(detail);
            d.setTextSize(12);
            d.setTextColor(DIM);
            d.setMaxLines(2);
        }
        box.addView(t);
        if (d != null) box.addView(d);
        return new Row(box, t, d);
    }

    /**
     * Say what a row is, once, and take its own text out of the a11y tree.
     *
     * <p>Every row here draws its state as a coloured corner dot, which nobody can
     * hear — and in the list that dot is the whole difference between "this is the
     * playlist changing your wallpaper" and "this is one of several". So the state
     * is folded into the row's own description, and the two text views are
     * silenced, or a screen reader reads the description and then its contents
     * and the user hears every option twice.
     */
    private void announce(View row, Row texts, int stateRes) {
        texts.title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (texts.detail != null) {
            texts.detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        String state = str(stateRes);
        row.setContentDescription(texts.detail == null
                ? activity.getString(R.string.playlists_talk_one, texts.title.getText(), state)
                : activity.getString(R.string.playlists_talk,
                        texts.title.getText(), texts.detail.getText(), state));
    }

    /**
     * A mark row: tile, dot, two lines, and a tap.
     *
     * <p>The mockup draws a trigger, a surface and a rotation the same way, so
     * they are built the same way. What differs between them is the tap, the state
     * word — which is passed in rather than derived, because the list calls the
     * same fact "rotating" and a control that calls it two different things is a
     * control whose value cannot be read — and the unavailable variant, which
     * keeps its tile and its reason and only drops its brightness.
     *
     * @param stateRes the one word that carries this row's state to a screen
     *                 reader, because the dot it is drawn as is invisible to one
     */
    private View markRow(boolean on, String title, String detail, int stateRes,
                         View.OnClickListener tap, boolean unavailable) {
        LinearLayout r = option();
        r.addView(swatch(TILE, null, on));
        Row texts = texts(title, detail);
        r.addView(texts.box, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        announce(r, texts, unavailable ? R.string.playlists_state_unavailable : stateRes);
        r.setOnClickListener(tap);
        if (unavailable) r.setAlpha(LOCKED_ALPHA);
        return r;
    }

    private int markState(boolean on) {
        return on ? R.string.playlists_state_on : R.string.playlists_state_off;
    }

    /** The modal's `<h3>`: 16px, bold, with 4px of air under it. */
    private TextView heading(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    /** The modal's `.sub`: 13px, dim, and the gap beneath it is the caller's. */
    private TextView subtitle(String s, int bottomDp) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(DIM);
        t.setPadding(0, 0, 0, dp(bottomDp));
        return t;
    }

    /**
     * The mockup's `.caption`.
     *
     * <p>16 above and 7 below are the stylesheet's margins. They are padding here
     * because a LinearLayout adds margins where a block flow collapses them, so
     * the caller's own bottom padding is set to keep the pair summing to what the
     * stylesheet's collapsing produced.
     */
    private TextView caption(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(11);
        t.setAllCaps(true);
        if (Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.09f);
        t.setTextColor(DIMMER);
        t.setTypeface(medium());
        t.setPadding(0, dp(16), 0, dp(7));
        return t;
    }

    /** A family heading inside a group — the mockup's `.axisgroup .h`. */
    private TextView family(String s) {
        TextView t = caption(s);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    /** The mockup's `.note`. */
    private TextView note(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(DIMMER);
        t.setPadding(0, dp(7), 0, 0);
        return t;
    }

    /**
     * 600 in the stylesheet. Android has no 600, and its own name for the weight
     * between regular and bold is this one — rounding to one of the two would
     * change how every heading in the app reads.
     */
    private static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ buttons

    private TextView button(int labelRes, boolean primary, View.OnClickListener tap) {
        TextView b = new TextView(activity);
        b.setText(str(labelRes));
        b.setTextSize(14);
        b.setTypeface(medium());
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? PRIMARY_INK : Color.WHITE);
        GradientDrawable bg = plain(primary ? Color.WHITE : Color.TRANSPARENT,
                dp(BUTTON_RADIUS_DP));
        bg.setStroke(dp(1), primary ? Color.WHITE : LINE);
        b.setBackground(bg);
        b.setMinHeight(dp(BUTTON_DP));
        b.setPadding(dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP));
        b.setOnClickListener(tap);
        return b;
    }

    private LinearLayout buttonRow(View... buttons) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(BUTTONROW_TOP_DP);
        row.setLayoutParams(lp);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f);
            if (i > 0) b.leftMargin = dp(BUTTONROW_GAP_DP);
            row.addView(buttons[i], b);
        }
        return row;
    }

    // ------------------------------------------------------------------- window

    /**
     * A Dialog rather than a view in the studio's root, for the reason
     * {@link SettingsSheet} gives: it brings its own window, so this screen does
     * not have to know what the studio's view tree looks like, cannot end up laid
     * out under the sheet, and leaves nothing half-attached behind it.
     */
    private void present() {
        FrameLayout frame = new FrameLayout(activity);
        View scrim = new View(activity);
        scrim.setBackgroundColor(SCRIM);
        frame.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBackground());
        panel.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                bottomInset = insetBottom(insets);
                applyPanelPadding();
                return insets;
            }
        });
        applyPanelPadding();
        frame.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        dialog.setContentView(frame);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // The name is a text field, so a BACK press never takes its focus
                // away and the focus listener would not fire. Dismissing is the one
                // backstop that covers every way out of this screen.
                commitName();
                openId = null;
                dialog = null;
            }
        });
        showList();
        // Back closes this screen, not the studio; see ModalSheet.dismissOnBack.
        ModalSheet.dismissOnBack(dialog, new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });
        dialog.show();
        frame.requestApplyInsets();
    }

    /**
     * The modal's own card: rounded at the top only, with the mockup's hairline
     * along the top edge.
     *
     * <p>{@link ModalSheet.TopEdgeBackground} rather than a stroked
     * {@code GradientDrawable}: a stroke draws four edges, and this card is
     * flush with the screen's sides and foot, so the design's single
     * {@code border-top} arrived as a 1px line down both flanks and along the
     * bottom — the same bleed reported on the studio sheet.
     */
    private GradientDrawable panelBackground() {
        return new ModalSheet.TopEdgeBackground(MODAL_BG, LINE, dp(MODAL_RADIUS_DP),
                dp(1));
    }

    private void applyPanelPadding() {
        panel.setPadding(dp(MODAL_PAD_DP), dp(MODAL_PAD_TOP_DP), dp(MODAL_PAD_DP),
                dp(MODAL_PAD_BOTTOM_DP) + bottomInset);
        if (scroll != null) scroll.setCeiling(ceiling());
    }

    private int insetBottom(WindowInsets insets) {
        // One resolution for all four dialogs: see ModalSheet.insetBottom, which
        // also names the bar (systemBars on API 30+) and the minSdk 26 fallback.
        return ModalSheet.insetBottom(insets);
    }

    /**
     * How tall the scrolling part may be.
     *
     * <p>The stylesheet caps the modal at 88% of the screen and lets it shrink-wrap
     * below that, which is the difference between a three-row list sitting at the
     * bottom of the display and an editor that would run off the top. The button
     * row is excluded: the mockup draws it inside the scroll, but an editor long
     * enough to scroll has to keep its Done reachable. What is left of the 88% is
     * the scroll's.
     */
    private int ceiling() {
        android.util.DisplayMetrics m = activity.getResources().getDisplayMetrics();
        int chrome = MODAL_PAD_TOP_DP + MODAL_PAD_BOTTOM_DP + BUTTON_DP + BUTTONROW_TOP_DP;
        int left = (int) (m.heightPixels * MODAL_MAX_FRACTION) - (int) (chrome * m.density)
                - bottomInset;
        if (left < dp(120)) left = (int) (m.heightPixels * MODAL_MAX_FRACTION);
        return left;
    }

    /**
     * A ScrollView with a ceiling, which the framework has no attribute for.
     *
     * <p>It measures its child the way a ScrollView always does — the child's
     * natural height, unbounded — and then clamps its own measurement to the
     * ceiling. The child keeps its natural height, which is exactly what makes it
     * scroll, and the view shrink-wraps below the ceiling so a short list still
     * looks like the mockup's.
     */
    private static final class CappedScroll extends ScrollView {

        private int ceiling;

        CappedScroll(Context context) {
            super(context);
        }

        void setCeiling(int px) {
            if (ceiling == px) return;
            ceiling = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (ceiling > 0 && getMeasuredHeight() > ceiling) {
                setMeasuredDimension(getMeasuredWidth(), ceiling);
            }
        }
    }

    // --------------------------------------------------------------------- list

    private void showList() {
        // Ahead of clearing the field, for the reason showEditor gives.
        commitName();
        openId = null;
        nameField = null;
        Playlists lists = PlaylistStore.load(activity.prefs);
        panel.removeAllViews();

        panel.addView(heading(str(R.string.playlists_title)));
        panel.addView(subtitle(str(R.string.playlists_subtitle), 14));

        LinearLayout box = column();
        if (lists.isEmpty()) {
            // A screen with only a New button is a screen that has not said
            // anything. This is the first build in which Playlists leads anywhere.
            box.addView(note(str(R.string.playlists_none)));
        }
        Library lib = library();
        for (Playlist p : lists.playlists()) {
            box.addView(listRow(p, lib));
        }
        panel.addView(startScroll(box));
        panel.addView(buttonRow(
                button(R.string.playlist_new, false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        newPlaylist();
                    }
                }),
                button(R.string.action_done, true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                })));
    }

    /**
     * One playlist: its name, and everything that decides when it moves.
     *
     * <p>The swatch is its first photograph rather than a generic tile, because a
     * column of identical tiles tells a user nothing about which list is which and
     * the photograph is the only thing here that is recognisable at a glance. The
     * corner dot is the one that rotates, and it is the only mark of that
     * anywhere in the app.
     */
    private View listRow(final Playlist p, Library lib) {
        LibraryItem first = firstPhoto(p, lib);
        LinearLayout r = option();
        r.addView(swatch(tileFill(first), photoView(first), p.active));
        Row texts = texts(p.displayName(), summary(p));
        r.addView(texts.box, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        announce(r, texts, p.active
                ? R.string.playlists_state_rotating : R.string.playlists_state_idle);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(p.id);
            }
        });
        return r;
    }

    /** {@code 4 photos · Daily · Home + Lock} — the row's whole answer, on one line. */
    private String summary(Playlist p) {
        return activity.getString(p.shuffle
                        ? R.string.playlists_row_summary_shuffled
                        : R.string.playlists_row_summary,
                photos(p.size()), p.trigger.label(), surfaces(p));
    }

    private String photos(int count) {
        return activity.getResources().getQuantityString(R.plurals.playlist_photos, count, count);
    }

    /**
     * Which screens, in one line.
     *
     * <p>Both are named in index order rather than in the order they were ticked,
     * so the same pair always reads the same way. A playlist whose stored surface
     * list could not be read says so: {@link Playlist#appliesTo} answers no for
     * every screen, and a summary that left the gap blank would end in a dangling
     * separator claiming a screen the playlist is not on.
     */
    private String surfaces(Playlist p) {
        boolean home = p.surfaces.contains(Surface.HOME);
        boolean lock = p.surfaces.contains(Surface.LOCK);
        if (home && lock) {
            return activity.getString(R.string.playlists_surfaces_both,
                    str(R.string.surface_home), str(R.string.surface_lock));
        }
        if (home) return str(R.string.surface_home);
        if (lock) return str(R.string.surface_lock);
        return str(R.string.playlists_surfaces_none);
    }

    // ------------------------------------------------------------------- editor

    private void openEditor(String playlistId) {
        if (playlistId == null || PlaylistStore.load(activity.prefs).find(playlistId) == null) {
            // Deleted by a rotation, or by a delete in the gallery, while this
            // screen was open. Falling back to the list is the honest answer; an
            // editor with nothing in it is not.
            showList();
            return;
        }
        openId = playlistId;
        showEditor();
    }

    private void showEditor() {
        // Before reading, not after. Rebuilding detaches the field the name is
        // being typed into, so a name committed on focus loss can arrive after
        // the value this method renders from was read — which is a rename that
        // silently undoes itself. Cheap when nothing changed: the store compares
        // before committing.
        commitName();
        Playlist p = PlaylistStore.load(activity.prefs).find(openId);
        if (p == null) {
            openId = null;
            showList();
            return;
        }
        panel.removeAllViews();

        nameField = nameFieldFor(p);
        panel.addView(nameField);
        panel.addView(subtitle(photos(p.size()), 0));

        LinearLayout box = column();

        // ---- whether it rotates at all
        //
        // The list's corner dot says which playlist is the one that moves the
        // wallpaper, so this is where the dot has to be decided. A mark that reads
        // a fact nothing can change is a control displaying a value this build
        // never acts on.
        box.addView(rotationGroup(p));

        // ---- how it picks. Shuffle is a rotation behaviour, so it lives in the
        // same group as the switch that turns rotation on; a shuffled playlist
        // that is not rotating still shuffles nothing.
        box.addView(shuffleRow(p));

        // ---- when. The two families are grouped, not listed flat: a timed
        // trigger and an event one are not interchangeable, and a list that put
        // them in a row says they are.
        box.addView(caption(str(R.string.playlist_rotate_on)));
        box.addView(family(str(R.string.playlists_family_timed)));
        box.addView(triggerRow(p, Trigger.DAILY, R.string.playlists_trigger_daily,
                R.string.playlists_trigger_daily_sub));
        box.addView(triggerRow(p, Trigger.HOURLY, R.string.playlists_trigger_hourly,
                R.string.playlists_trigger_hourly_sub));
        box.addView(family(str(R.string.playlists_family_event)));
        box.addView(triggerRow(p, Trigger.ON_UNLOCK, R.string.playlists_trigger_unlock,
                R.string.playlists_trigger_unlock_sub));
        box.addView(triggerRow(p, Trigger.ON_PICKUP, R.string.playlists_trigger_pickup,
                R.string.playlists_trigger_pickup_sub));

        // ---- where
        box.addView(caption(str(R.string.playlist_surfaces)));
        box.addView(surfaceRow(p, Surface.HOME, R.string.apply_home));
        box.addView(surfaceRow(p, Surface.LOCK, R.string.apply_lock));
        if (!SurfaceResolver.supportsPerSurfaceLock(Build.VERSION.SDK_INT)) {
            // The stored surfaces are intent, and a degrade must never be written
            // into the blob, so this is a line under the group rather than a row
            // that cannot be ticked. See the class note on Playlist.surfaces.
            box.addView(note(str(R.string.apply_lock_note)));
        }

        // ---- which photos
        box.addView(caption(str(R.string.playlists_photos_caption)));
        box.addView(photoList(p));

        panel.addView(startScroll(box));
        panel.addView(buttonRow(
                button(R.string.playlist_delete, false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmDelete(p.id);
                    }
                }),
                button(R.string.action_done, true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        commitName();
                        showList();
                    }
                })));
    }

    /**
     * The name, as the editor's title.
     *
     * <p>An editable field rather than a label with a rename control beside it,
     * because the mockup's editor has nothing else in its title row and a name
     * that can only be set before the screen opens is a name this screen cannot
     * change. It is written on focus loss and on dismiss rather than per
     * keystroke, because populating a control must never write and neither must
     * typing.
     */
    private EditText nameFieldFor(Playlist p) {
        EditText field = new EditText(activity);
        field.setText(p.name == null ? "" : p.name);
        field.setHint(str(R.string.gallery_playlist_name));
        field.setHintTextColor(DIMMER);
        field.setTextColor(Color.WHITE);
        field.setTextSize(16);
        field.setTypeface(null, Typeface.BOLD);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        // The underline is the platform's, not the design's. The mockup's title is
        // a heading, and a field dressed as a heading should not also carry the
        // one decoration this app's cards do not have.
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setPadding(0, 0, 0, dp(4));
        field.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) commitName();
            }
        });
        return field;
    }

    /**
     * The typed name, if it differs from the stored one.
     *
     * <p>Blank is not a special case: {@link Playlist#withName} turns it into no
     * name at all rather than a row of spaces, and {@link Playlist#displayName}
     * then has something honest to show. The store compares before committing, so
     * re-entering the editor and leaving it without typing is not a write.
     */
    private void commitName() {
        final EditText field = nameField;
        if (field == null || openId == null) return;
        final String typed = field.getText().toString().trim();
        PlaylistStore.edit(activity.prefs, openId, new PlaylistStore.Editor() {
            @Override
            public Playlist edit(Playlist p) {
                return p.withName(typed);
            }
        });
    }

    /**
     * Rotation on or off for this one playlist, both ways.
     *
     * <p>Goes through {@link Playlists#withActive}, never
     * {@link Playlist#withActive}: only the first stands the others down, and one
     * is all the index can express. The per-playlist setter is still public, which
     * is exactly why this call is spelled out rather than left to look obvious.
     *
     * <p>Turning it <i>off</i> is the half that has to be here as well. The list
     * draws a dot on whichever playlist is rotating, so a control that can only
     * put one there is a dot that cannot be taken away, and a user who does not
     * want a wallpaper that moves has no way to say so. {@code withActive(id,
     * false)} touches exactly that one row and leaves the others as they were, so
     * a toggle needs no special case.
     */
    private View rotationGroup(final Playlist p) {
        LinearLayout box = column();
        box.addView(caption(str(R.string.playlists_rotation)));
        box.addView(markRow(p.active, str(R.string.playlists_rotate_row),
                str(R.string.playlists_rotate_row_sub), p.active
                        ? R.string.playlists_state_rotating
                        : R.string.playlists_state_idle,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PlaylistStore.setActive(activity.prefs, p.id, !p.active);
                        showEditor();
                    }
                }, false));
        return box;
    }

    /**
     * Shuffle: whether the next photo is random or the next one in the list.
     *
     * <p>{@link Playlist#shuffle} and the rotation have carried this flag since
     * they were written, and until now nothing in the app could set it — a stored
     * capability no control reached, which is the failure this project's
     * constitution rules out. It goes through the same store as every other
     * edit, so it is derived from what is on disk and cannot reset the fields it
     * does not name.
     *
     * <p>Both directions are here. A control that could only turn shuffle on
     * would be a value with no way back, and the state word beside the row says
     * which state it is in for anyone who cannot see the corner dot.
     */
    private View shuffleRow(final Playlist p) {
        return markRow(p.shuffle, str(R.string.playlists_shuffle_row),
                str(R.string.playlists_shuffle_row_sub), markState(p.shuffle),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
                            @Override
                            public Playlist edit(Playlist current) {
                                return current.withShuffle(!current.shuffle);
                            }
                        });
                        showEditor();
                    }
                }, false);
    }

    /**
     * One of the four triggers.
     *
     * <p>{@link Trigger#ON_PICKUP} is drawn dimmed, with the reason under it and a
     * tap that says the same thing again. Hiding it would be tidier and worse: a
     * stored {@code on_pickup} would then read as {@code daily} everywhere it is
     * shown, and a wallpaper that starts changing on a clock is the "must never
     * look like a bug" outcome the model exists to prevent.
     */
    private View triggerRow(final Playlist p, final Trigger trigger, int labelRes, int subRes) {
        final boolean unavailable = trigger == Trigger.ON_PICKUP;
        return markRow(p.trigger == trigger, str(labelRes), str(subRes),
                markState(p.trigger == trigger), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (unavailable) {
                            // Named, not silent. A dimmed row that is also inert
                            // is a control that does nothing at all.
                            toast(R.string.playlists_pickup_unavailable);
                            return;
                        }
                        if (p.trigger == trigger) return;
                        PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
                            @Override
                            public Playlist edit(Playlist current) {
                                return current.withTrigger(trigger);
                            }
                        });
                        showEditor();
                    }
                }, unavailable);
    }

    /**
     * One surface, on or off.
     *
     * <p>The last one cannot be turned off, and says so.
     * {@link Playlist#appliesTo} answers no for an empty list, so a playlist on no
     * screen is one that never moves — and {@link Playlist#withSurfaces} would
     * quietly put it back on the home screen instead, which is a control that
     * un-ticks and re-ticks itself.
     */
    private View surfaceRow(final Playlist p, final Surface surface, int labelRes) {
        final boolean on = p.surfaces.contains(surface);
        return markRow(on, str(labelRes), null, markState(on), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (on) {
                    if (p.surfaces.size() <= 1) {
                        toast(R.string.playlists_needs_surface);
                        return;
                    }
                    final List<Surface> kept = new ArrayList<Surface>(p.surfaces);
                    kept.remove(surface);
                    PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
                        @Override
                        public Playlist edit(Playlist current) {
                            return current.withSurfaces(kept);
                        }
                    });
                } else {
                    // alsoOn rather than a rebuilt list, so a write that adds a
                    // surface cannot drop one this build has never heard of.
                    PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
                        @Override
                        public Playlist edit(Playlist current) {
                            return current.alsoOn(surface);
                        }
                    });
                }
                showEditor();
            }
        }, false);
    }

    /**
     * The photographs, in the order they will be shown.
     *
     * <p>Not tappable, and there is nothing behind the tap. Choosing a photo from
     * here would be a second way to write the selection, and the index owns it; a
     * playlist has no cursor either, so there would be nothing truthful to show
     * as "the one you are on". The edits are taking a photo out and changing its
     * place, both of which are playlists edits and leave the gallery exactly as
     * it was.
     *
     * <p><b>Reordering is up/down buttons, not a drag handle.</b> The list already
     * lives in a scroll view, and a drag inside a scroll view is the same gesture
     * as a scroll: the framework's own arbitration resolves that in the scroll's
     * favour, so a drag only works after a long press — an interaction with no
     * visible affordance, on the one control this screen added for itself. A
     * button has no ambiguity, the row's own edge is where the move is, and one
     * press is one {@link Playlist#moved} call. The edge button is absent — an
     * INVISIBLE plate, so rows still line up — rather than present and inert,
     * because a control that does nothing is the failure this screen goes out of
     * its way to avoid.
     *
     * <p>A hole is shown rather than dropped. A playlist can name a photograph the
     * gallery no longer holds — the restore case, or a delete that arrived from
     * somewhere else — and rotation steps over it. Hiding the row would make the
     * count above quietly wrong with nothing to explain why, and that count is
     * the thing that makes a playlist's length worth trusting. A hole is movable
     * too: moving it changes where it would reappear, and never makes it resolve.
     */
    private View photoList(Playlist p) {
        LinearLayout box = column();
        if (p.isEmpty()) {
            // The way in is the gallery's own Add to playlist, which already
            // exists and appends to this list. Saying so beats adding a second
            // route that would then have to be kept in step with it.
            box.addView(note(str(R.string.playlists_none_photos)));
            return box;
        }
        Library lib = library();
        for (int i = 0; i < p.itemIds.size(); i++) {
            final String id = p.itemIds.get(i);
            final LibraryItem item = lib.find(id);
            final String label = item == null ? null : item.displayName();
            LinearLayout r = option();
            Row texts;
            if (item == null) {
                r.addView(swatch(TILE, null, false));
                texts = texts(str(R.string.playlists_photo_missing),
                        str(R.string.playlists_photo_missing_sub));
                announce(r, texts, R.string.playlists_state_missing);
            } else {
                r.addView(swatch(tileFill(item), photoView(item), false));
                texts = texts(item.displayName(), null);
                announce(r, texts, R.string.playlists_state_in_list);
            }
            r.addView(texts.box, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(movePhoto(id, label, -1, i == 0));
            r.addView(movePhoto(id, label, 1, i == p.itemIds.size() - 1));
            r.addView(removePhoto(id, label));
            box.addView(r);
        }
        return box;
    }

    /**
     * A move button: the arrow, its description, and the reorder.
     *
     * <p>{@link Playlist#moved} rather than rebuilding the order here, so a photo
     * that arrived from another writer is not dropped as junk and a hole is
     * carried along rather than filled. See {@code photoList} for why this is a
     * button and not a drag.
     *
     * @param atEdge true for the first/last photo, where the arrow has nowhere to
     *               go and the button is made invisible rather than inert
     */
    private View movePhoto(final String id, final String label, final int delta,
                           boolean atEdge) {
        String description = label == null
                ? str(delta < 0 ? R.string.playlists_move_missing_up
                        : R.string.playlists_move_missing_down)
                : activity.getString(delta < 0 ? R.string.playlists_move_up
                        : R.string.playlists_move_down, label);
        TextView b = glyphPlate(str(delta < 0 ? R.string.playlists_move_up_glyph
                        : R.string.playlists_move_down_glyph),
                description, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PlaylistStore.edit(activity.prefs, openId, new PlaylistStore.Editor() {
                            @Override
                            public Playlist edit(Playlist p) {
                                return p.moved(id, delta);
                            }
                        });
                        showEditor();
                    }
                });
        if (atEdge) b.setVisibility(View.INVISIBLE);
        return b;
    }

    /**
     * A glyph on a dark plate, the row's small control.
     *
     * <p>The plate exists for the reason the gallery's row menu has one: a dark
     * glyph on a dark card is a control that is present and unreadable. Building
     * it once is also why the remove mark and the two arrows cannot drift apart.
     */
    private TextView glyphPlate(String glyph, String description,
                                View.OnClickListener tap) {
        TextView t = new TextView(activity);
        t.setText(glyph);
        t.setTextSize(15);
        t.setTextColor(0xCCFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackground(plain(0x99000000, dp(14)));
        t.setContentDescription(description);
        t.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(SWATCH_DP), dp(SWATCH_DP));
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private View removePhoto(final String id, String label) {
        return glyphPlate(str(R.string.playlists_remove_glyph),
                label == null
                        ? str(R.string.playlists_remove_missing)
                        : activity.getString(R.string.playlists_remove_photo, label),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // The playlists blob only. The photograph is still in the
                        // gallery, and the other half of a real delete —
                        // Playlists.afterRemoving — belongs to whoever deletes the
                        // row.
                        PlaylistStore.edit(activity.prefs, openId, new PlaylistStore.Editor() {
                            @Override
                            public Playlist edit(Playlist p) {
                                return p.excluding(id);
                            }
                        });
                        showEditor();
                    }
                });
    }

    // ------------------------------------------------------------------ actions

    private void newPlaylist() {
        // create() applies the defaults a new playlist starts with — daily, home
        // screen, in order, not rotating — and the name is left unset, so the
        // editor's field is genuinely empty rather than pre-filled with something
        // the user did not type.
        openEditor(PlaylistStore.add(activity.prefs,
                Playlist.create(Playlist.newId(), null, null)));
    }

    /**
     * Ask before unlinking, in the app's own modal.
     *
     * <p>This was the last platform {@code AlertDialog} in the app: a white
     * Material card inside a near-black screen, drawn by a different design
     * system from every other question one gesture away. {@link ModalSheet} is
     * the app's card, so the question looks like the rest of them. The sheet
     * registers its own back handling, which is why this does not call
     * {@code dismissOnBack} itself.
     *
     * <p>The playlist is read again here rather than carried in from the editor,
     * because a rotation or an edit may have landed while the sheet was up; the
     * row being confirmed is the one on disk.
     */
    private void confirmDelete(final String id) {
        Playlist p = PlaylistStore.load(activity.prefs).find(id);
        if (p == null) {
            showList();
            return;
        }
        final ModalSheet sheet = new ModalSheet(activity, MODAL_MAX_FRACTION);
        LinearLayout body = column();
        TextView head = ModalSheet.title(activity, str(R.string.gallery_delete_title));
        // The gap the other modals get from their subtitle; see Gallery.modalBody.
        head.setPadding(0, 0, 0, dp(10));
        body.addView(head);
        body.addView(ModalSheet.subtitle(activity,
                activity.getString(R.string.playlists_delete_message, p.displayName())));
        sheet.body(body);

        Button cancel = ModalSheet.button(activity, str(R.string.action_cancel), false, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        Button delete = ModalSheet.button(activity, str(R.string.playlist_delete), true, true);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Close first, then act: the editor is what is left when the
                // question goes, and the same order the gallery's confirm uses.
                sheet.dismiss();
                PlaylistStore.remove(activity.prefs, id);
                showList();
            }
        });
        sheet.footerRow(cancel, delete);
        sheet.show(activity);
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ library

    /**
     * The gallery index, read through the single read path.
     *
     * <p>{@code AppState.load} rather than {@link LibraryStore#load} because it
     * migrates and self-heals, so the bundled row is guaranteed present and a row
     * whose file has gone is already gone by the time a playlist is compared
     * against it. The read may write the index, but only the self-heal does, and
     * neither {@code withBundled} nor {@code retainExisting} moves the selection.
     */
    private Library library() {
        return AppState.load(activity.prefs, activity.getFilesDir()).library;
    }

    /** The first photograph a playlist can actually show, or null. */
    private LibraryItem firstPhoto(Playlist p, Library lib) {
        for (String id : p.itemIds) {
            LibraryItem item = lib.find(id);
            if (item != null) return item;
        }
        return null;
    }

    private int tileFill(LibraryItem item) {
        return item != null && item.isBundled() ? TILE_BUNDLED : TILE;
    }

    /**
     * A decoded thumbnail for a row, or null.
     *
     * <p>Null for the bundled demo, which has no file to decode: it ships inside
     * {@code res/raw} and its tile keeps the app's own bundled fill, which is the
     * same reason the filmstrip does not decode it either.
     */
    private ImageView photoView(LibraryItem item) {
        if (item == null || item.isBundled()) return null;
        File file = PrefFiles.resolve(activity.getFilesDir(), item.imageName);
        if (file == null) return null;
        ImageView thumb = new ImageView(activity);
        Io.decodePreview(activity, file, thumb, dp(SWATCH_DP));
        return thumb;
    }

    // ------------------------------------------------------------------ storage

    /**
     * The playlists blob, owned in one place.
     *
     * <p>Lives here rather than in {@link Gallery}, whose two playlist helpers are
     * private: they were written before this screen existed, and lifting them
     * would mean editing a file this change does not own. Deliberately one nested
     * class rather than a second top-level one, because the task's one new file is
     * the shape that keeps it out of everyone else's way.
     *
     * <p><b>No method here takes a {@link Library}.</b> That is the whole reason
     * the class is shaped this way: a store whose signature cannot express a
     * selection cannot be the route by which a second pointer is written, so the
     * invariant holds structurally rather than by review.
     */
    static final class PlaylistStore {

        private PlaylistStore() {
        }

        /** What is stored, or nothing. Never throws, because parse does not. */
        /**
         * Replace the whole blob with a value computed elsewhere.
         *
         * <p>For the one caller that already holds the result: playlist rotation,
         * which runs in the wallpaper engine and stamps its own clock. Every other
         * writer goes through {@link #edit}, because that is the only way to be sure
         * the change was derived from what was actually stored.
         *
         * <p>It goes through {@link #write} regardless, so the newer-build guard and
         * the unchanged-value skip still apply. Rotation fires on its own write, so a
         * guard that let it through twice would move two photos per tick.
         */
        static void save(SharedPreferences prefs, Playlists value) {
            write(prefs, Playlists.parse(raw(prefs)), value);
        }

        static Playlists load(SharedPreferences prefs) {
            return Playlists.parse(raw(prefs));
        }

        /**
         * Apply one edit to one playlist, from the value on disk.
         *
         * <p>Re-reads rather than writing the object the screen was built from. A
         * rotation may have run while this screen was open — it stamps the
         * playlist's own clock — and an edit derived from a stale snapshot would
         * write the old clock back, which is how a daily playlist comes to
         * advance twice a day.
         */
        static Playlists edit(SharedPreferences prefs, String id, Editor editor) {
            String raw = raw(prefs);
            if (writtenByNewerBuild(raw)) return Playlists.parse(raw);
            Playlists before = Playlists.parse(raw);
            Playlist current = before.find(id);
            if (current == null) return before;
            Playlist edited = editor.edit(current);
            if (edited == null) return before;
            return write(prefs, before, before.withPlaylist(edited));
        }

        /**
         * The one playlist that rotates, or none.
         *
         * <p>{@link Playlists#withActive} rather than
         * {@link Playlist#withActive}, because only the first stands the others
         * down. One is all the index can express — see the class note on
         * {@link Playlists} — and a direct write to one row's flag is how two
         * rotators end up each quietly undoing the other.
         *
         * @return the id that ended up rotating, for opening straight into it
         */
        static String add(SharedPreferences prefs, Playlist playlist) {
            String raw = raw(prefs);
            if (writtenByNewerBuild(raw)) return null;
            Playlists before = Playlists.parse(raw);
            write(prefs, before, before.withPlaylist(playlist));
            return playlist.id;
        }

        static Playlists setActive(SharedPreferences prefs, String id, boolean on) {
            String raw = raw(prefs);
            if (writtenByNewerBuild(raw)) return Playlists.parse(raw);
            Playlists before = Playlists.parse(raw);
            return write(prefs, before, before.withActive(id, on));
        }

        /**
         * Unlink a playlist. The photographs are untouched — they are not its to
         * delete — and the index is not written, so a wallpaper already showing one
         * of them carries on showing it.
         */
        static Playlists remove(SharedPreferences prefs, String id) {
            String raw = raw(prefs);
            if (writtenByNewerBuild(raw)) return Playlists.parse(raw);
            Playlists before = Playlists.parse(raw);
            return write(prefs, before, before.without(id));
        }

        /**
         * Commit, and only when the value actually changed.
         *
         * <p>{@code commit()} rather than {@code apply()} for the reason
         * {@link LibraryStore#saveNow} gives: this blob and the index are read by
         * the wallpaper on its own thread, and an asynchronous write could be
         * reordered against the rotation that writes both keys. Synchronous also
         * means a rotation cannot land between this screen's read and its write.
         *
         * <p>Compared against what was <i>read</i> rather than against the raw
         * stored string, so a blob this build could not read is replaced by
         * whatever the user just did instead of being quietly normalised behind
         * their back — and a control that changed nothing produces no write at
         * all, which is the same rule {@link Playlists#withActive} keeps for the
         * active flag.
         */
        private static Playlists write(SharedPreferences prefs, Playlists before, Playlists after) {
            if (after.toJson().equals(before.toJson())) return after;
            prefs.edit().putString(Playlists.KEY, after.toJson()).commit();
            return after;
        }

        private static String raw(SharedPreferences prefs) {
            return prefs.getString(Playlists.KEY, null);
        }

        /**
         * Whether the stored blob was written by a build newer than this one.
         *
         * <p>{@link Playlists#parse} answers "no playlists" for a version it does
         * not know, which is right for reading and fatal for writing: a
         * read-modify-write would then store an empty list over a newer install's
         * playlists, which is how an older build destroys a newer build's data.
         * {@code AppState} refuses the same way for the index and {@code Playlists}
         * offers no equivalent to call, so it is checked here — once, in the only
         * method every write goes through.
         */
        private static boolean writtenByNewerBuild(String raw) {
            if (raw == null || raw.trim().isEmpty()) return false;
            try {
                return new JSONObject(raw).optInt("version", 0) > Playlists.VERSION;
            } catch (JSONException e) {
                // Corrupt, not newer. parse() has already chosen the safe reading
                // of it, and an edit is allowed to replace it.
                return false;
            }
        }

        /**
         * One edit to one playlist.
         *
         * <p>Applied to the value read from disk, so a field the editor does not
         * name — the rotation clock, the active flag — keeps the one that is
         * there rather than the one the screen happened to have loaded. This is
         * the {@code withDepth} lesson: an edit handed a constructor with loose
         * values resets every value it does not pass, which is how a rename once
         * cleared Motion, brightness and shadow.
         */
        interface Editor {
            Playlist edit(Playlist playlist);
        }
    }

    // ------------------------------------------------------------------- layout

    /** Put {@code box} in a fresh, capped scroll, and make it the current one. */
    private View startScroll(LinearLayout box) {
        scroll = new CappedScroll(activity);
        scroll.setCeiling(ceiling());
        scroll.addView(box);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }
}
