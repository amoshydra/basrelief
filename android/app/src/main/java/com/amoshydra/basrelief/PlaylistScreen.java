package com.amoshydra.basrelief;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Playlist;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.PlaylistStatus;
import com.amoshydra.basrelief.core.store.Surface;
import com.amoshydra.basrelief.core.store.SurfaceResolver;
import com.amoshydra.basrelief.core.store.Trigger;

import org.json.JSONException;
import org.json.JSONObject;

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
    //
    // The window's own numbers -- scrim, card, padding, buttons -- are gone from
    // this file: the screen is built on ModalSheet now, so one card and one
    // scrim exist for every modal rather than one per screen. What remains here
    // is what the rows in this screen's body draw.

    /** The card fill, referenced by the corner dot's ring so the dot sits ON the card. */
    private static final int MODAL_BG = 0xFF171B21;
    private static final int LINE = 0x24FFFFFF;
    private static final int ACCENT = 0xFF7FD1C1;
    private static final int DIM = 0x8CFFFFFF;
    private static final int DIMMER = 0x57FFFFFF;
    /**
     * The running row's fill: the accent at 20%.
     *
     * <p>A wash rather than a solid plate, because the row's own photograph and
     * text still have to read; the chip and the 2dp accent border carry the
     * "this one is running" at full strength. This is the same accent at a lower
     * alpha than the gallery's picked wash (30%), because this row is a
     * standing state rather than a selection about to be acted on.
     */
    private static final int ROTATING_FILL = 0x337FD1C1;
    /** The running row's border: the accent, at the picked ring's weight. */
    private static final int ROTATING_STROKE_DP = 2;
    /**
     * A selected radio choice's fill: the accent at 12%, fainter than the
     * running row's 20% wash. Four choices sit together and only one is on, so a
     * louder wash would turn one group into four competing plates —
     * distinguishable from the running row at a glance, which is the point.
     */
    private static final int CHOICE_WASH = 0x1F7FD1C1;
    /** The mockup's `.locked`, which is how an unavailable row reads. */
    private static final float LOCKED_ALPHA = 0.42f;

    /** `.modal { max-height: 88% }`. */
    private static final float MODAL_MAX_FRACTION = 0.88f;

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
    private ModalSheet sheet;
    /** The playlist being edited, or null for the list. */
    private String openId;
    /** The editor's name field, held so a commit does not have to find it. */
    private EditText nameField;
    // The screen used to build its own window -- scrim, card, inset listener,
    // capped scroll -- which is how it ended up with a card padding, a ceiling
    // formula and a back handler that only it had. It is ModalSheet's card now:
    // body() replaces the state's content in place and footerRow() replaces the
    // state's buttons, so list and editor still share one window without either
    // of them drawing one.

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

    /**
     * The mockup's `.opt`: an outlined card with no fill, so the modal shows
     * through — or the running row, filled and bordered in the accent.
     *
     * <p>The running state is the row's own fill rather than a badge alone,
     * because the question "which of these is actually changing my wallpaper"
     * is answered by scanning the list, not by reading each row. The chip and
     * the status sentence then say where and when; the fill says which.
     */
    private LinearLayout option(boolean rotating) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP), dp(OPTION_PAD_DP));
        GradientDrawable bg = plain(rotating ? ROTATING_FILL : Color.TRANSPARENT,
                dp(OPTION_RADIUS_DP));
        if (rotating) {
            bg.setStroke(dp(ROTATING_STROKE_DP), ACCENT);
        } else {
            bg.setStroke(dp(1), LINE);
        }
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(OPTION_GAP_DP);
        r.setLayoutParams(lp);
        return r;
    }

    /**
     * A swatch: one {@link PhotoTile}, and the accent dot in its bottom-right
     * corner when the row is on.
     *
     * <p>The dot is drawn <i>outside</i> the tile, which is why the holder does
     * not clip its children: a dot inside the tile would be a second mark lying
     * on the photograph, and the mockup is unambiguous about which of the two it
     * draws. The tile itself is the gallery's own component, so a swatch and a
     * filmstrip thumbnail cannot drift into two different pictures.
     *
     * @param item the photograph, or null for a plain tile — a missing photo, or
     *             a mark row that carries no photo at all
     */
    private View swatch(LibraryItem item, boolean on) {
        FrameLayout holder = new FrameLayout(activity);
        holder.setClipChildren(false);
        PhotoTile tile = new PhotoTile(activity, dp(SWATCH_DP), dp(SWATCH_RADIUS_DP));
        tile.show(item);
        holder.addView(tile, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (on) holder.addView(dot(), dotParams());
        // Purely decorative: the state it carries is announced on the row.
        holder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // .opt{gap:12px}: the tile is the row's leading mark, and the text that
        // follows it used to start at the tile's own edge -- no gap at all --
        // unlike every card built by ModalSheet.option, which has always put 12dp
        // between the two.
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                dp(SWATCH_DP), dp(SWATCH_DP));
        swatchLp.rightMargin = dp(12);
        holder.setLayoutParams(swatchLp);
        return holder;
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

    // ------------------------------------------------------------------- window

    /**
     * Present the screen, wholesale from {@link ModalSheet}.
     *
     * <p><b>One window, two states.</b> The list and the editor are the same
     * dialog with a different body and footer, not two dialogs; a row tap swaps
     * them in place so there is no flicker and no second window to leave behind.
     * The card, the scrim, the navigation-bar inset, the capped scroll and the
     * back handling are ModalSheet's -- this screen's window code was a second
     * implementation of all five, which is how its ceiling and its card padding
     * came to differ from every other modal's.
     *
     * <p><b>The dismiss hook is the name's last chance.</b> The name is a text
     * field, so a BACK press never takes its focus away and the field's own
     * focus listener would not fire. Dismissing is the one event that covers the
     * grip, the back gesture and Done alike, so the commit lives there once.
     */
    private void present() {
        sheet = new ModalSheet(activity, MODAL_MAX_FRACTION);
        // The same grip the settings sheet installs, and for the same reason:
        // the acceptance test is that this screen is recognisable as that sheet
        // by its chrome alone, and it both taps and drags to close. The
        // description is its own, because the two screens close different things.
        sheet.grip(str(R.string.playlists_close));
        sheet.onDismiss(new Runnable() {
            @Override
            public void run() {
                commitName();
                openId = null;
                sheet = null;
            }
        });
        showList();
        sheet.show(activity);
    }

    // --------------------------------------------------------------------- list

    private void showList() {
        // Ahead of clearing the field, for the reason showEditor gives.
        commitName();
        openId = null;
        nameField = null;
        Playlists lists = PlaylistStore.load(activity.prefs);
        Library lib = library();
        // One reading of "what is running" for the whole screen, so two rows
        // cannot disagree about which one it is. See PlaylistStatus.
        final long now = System.currentTimeMillis();
        PlaylistStatus status = PlaylistStatus.of(lists, lib, now);

        // The whole state is one column, heading included. The mockup's .modal is
        // itself the scroller, so the h3 scrolls with the rows; the old screen
        // pinned a title above its own scroll, which is a second layout the card
        // does not need and a heading that would outlive its content on a long
        // list. body() replaces the previous state's column in place.
        LinearLayout box = column();
        box.addView(heading(str(R.string.playlists_title)));
        box.addView(subtitle(str(R.string.playlists_subtitle), 14));
        if (lists.isEmpty()) {
            // A screen with only a New button is a screen that has not said
            // anything. This is the first build in which Playlists leads anywhere.
            box.addView(note(str(R.string.playlists_none)));
        }
        for (Playlist p : lists.playlists()) {
            box.addView(listRow(p, lib, status, now));
        }
        sheet.body(box);

        Button newButton = ModalSheet.button(activity, str(R.string.playlist_new), false, true);
        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newPlaylist();
            }
        });
        Button done = ModalSheet.button(activity, str(R.string.action_done), false, true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        sheet.footerRow(newButton, done);
    }

    /**
     * One playlist: its name, its state, and the control that starts it.
     *
     * <p><b>The row is the control.</b> A saved list and a running list used to
     * look identical — both drew the same card and a small corner dot — so a
     * user who had configured a playlist could not tell whether it was doing
     * anything, which is how a saved list reads as a broken feature. The running
     * row is now filled and bordered in the accent and carries a filled
     * {@code Rotating} chip; the idle row carries a {@code Use} button, so the
     * one action that makes the feature work is the one thing the row offers.
     *
     * <p>{@code Use} turns rotation on and steps once in the same write, so the
     * wallpaper moves in the moment the button is pressed — the alternative is a
     * user waiting a day to find out whether it worked.
     *
     * <p>The row itself still opens the editor; {@code Use} is a child and
     * consumes its own tap. The list row is also how a playlist is configured,
     * so both gestures have to live on it without answering for each other.
     */
    private View listRow(final Playlist p, Library lib, PlaylistStatus status, long now) {
        boolean rotating = status.playlist == p;
        LibraryItem first = firstPhoto(p, lib);
        LinearLayout r = option(rotating);
        r.addView(swatch(first, rotating));
        Row texts = texts(p.displayName(), rotating
                ? activity.getString(R.string.playlists_row_rotating, surfaces(p),
                        nextChangeLabel(activity, p, status, now))
                : activity.getString(R.string.playlists_row_not_rotating, summary(p)));
        r.addView(texts.box, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (rotating) {
            r.addView(rotatingChip());
        } else {
            r.addView(useButton(p));
        }
        announce(r, texts, rotating
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

    /**
     * The filled "rotating" mark at a row's end.
     *
     * <p>Carries no tap: the row is the control, and a chip that also opened the
     * editor would be a second hit target promising nothing new. It is the
     * at-a-glance half of the state; the sentence under the name is the reading.
     */
    private View rotatingChip() {
        TextView chip = new TextView(activity);
        chip.setText(str(R.string.playlists_chip_rotating));
        chip.setTextSize(11);
        chip.setTypeface(medium());
        chip.setTextColor(ModalSheet.INK_ON_FILL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(plain(ACCENT, dp(OPTION_RADIUS_DP)));
        // The row announces the state in words; the chip is its colour.
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return chip;
    }

    /**
     * The dedicated way in: turn rotation on and step it once, now.
     *
     * <p>It is the only control on an idle row, and that is the point — the
     * previous build buried activation in a switch among switches, so the one
     * thing a configured playlist needed was the hardest thing to find.
     * {@link ModalSheet#button} sets the platform-minimum 49dp height, which is
     * the target this must not shrink below.
     */
    private Button useButton(final Playlist p) {
        Button use = ModalSheet.button(activity, str(R.string.playlists_use), false, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        use.setLayoutParams(lp);
        use.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                use(p);
                showList();
            }
        });
        return use;
    }

    /**
     * Turn this playlist on and move it one photo, in one write.
     *
     * <p><b>Both blobs, one commit.</b> Activation lives on the playlists blob
     * and the move writes the index's selection — the same two keys a rotation
     * writes from the wallpaper. Committing them separately would leave a crash
     * window where the playlist is stamped as having moved but the selection
     * never changed, or the reverse; an editor cannot express half a rotation.
     *
     * <p><b>The studio is told in the same breath.</b> The selection just moved
     * exactly as if the user had tapped the photograph, so the render, the
     * sliders and the status line are refreshed here; the wallpaper engine reads
     * the index on its own. Without this the user would press Use and watch the
     * screen behind the dialog not change.
     *
     * <p>The screen stepped is the first the playlist applies to. A playlist
     * whose stored screens could not be read steps nowhere — {@code step}
     * answers with no move rather than a guessed home — and still activates, so
     * the row says what it is rather than hiding the configuration behind a
     * failed move.
     */
    private void use(Playlist p) {
        Playlists lists = PlaylistStore.load(activity.prefs);
        Playlists on = lists.withActive(p.id, true);
        Playlist running = on.find(p.id);
        if (running == null) return;
        Surface surface = running.surfaces.isEmpty() ? Surface.HOME : running.surfaces.get(0);
        Playlists.Result r = Playlists.step(on, library(), surface,
                System.currentTimeMillis());
        activity.prefs.edit()
                .putString(Library.KEY, r.library.toJson())
                .putString(Playlists.KEY, r.playlists.toJson())
                .commit();
        activity.refreshStatus();
        activity.refreshSliders();
        activity.applyFiles();
    }

    /**
     * When a running playlist next changes, in words.
     *
     * <p>A timed trigger counts down from the playlist's own stamp; "due now"
     * wins over a small count, because it is the actionable truth. An event
     * trigger has no moment to count down to — its interval is zero, so a
     * derived countdown would say "due now" forever — and instead says the event,
     * reusing the trigger's own subtitle so the row and the editor's radio cannot
     * word one trigger two ways.
     *
     * <p>Static and package-visible because the gallery's header needs the same
     * words for its armed line: a second copy of this countdown is a second
     * answer to "when does it move", and the two would drift the first time one
     * bucket changed.
     */
    static String nextChangeLabel(MainActivity activity, Playlist p, PlaylistStatus status,
                                  long now) {
        if (!p.trigger.isTimed()) {
            return activity.getString(triggerSub(p.trigger));
        }
        if (status.dueNow) return activity.getString(R.string.playlists_next_due);
        long remain = Math.max(0L, status.nextChangeAt - now);
        long minutes = (remain + 59_999L) / 60_000L;
        if (minutes < 60) {
            return activity.getString(R.string.playlists_next_minutes, minutes);
        }
        long hours = (remain + 3_599_999L) / 3_600_000L;
        if (hours < 48) {
            return activity.getString(R.string.playlists_next_hours, hours);
        }
        return activity.getString(R.string.playlists_next_days,
                (remain + 86_399_999L) / 86_400_000L);
    }

    /** The trigger's own second line, for the event families. */
    private static int triggerSub(Trigger trigger) {
        switch (trigger) {
            case DAILY:
                return R.string.playlists_trigger_daily_sub;
            case HOURLY:
                return R.string.playlists_trigger_hourly_sub;
            case ON_UNLOCK:
                return R.string.playlists_trigger_unlock_sub;
            default:
                return R.string.playlists_trigger_pickup_sub;
        }
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

    /** One screen's name, for the "X is rotating on Home" sentence. */
    private String surfaceName(Surface surface) {
        return str(surface == Surface.LOCK ? R.string.surface_lock : R.string.surface_home);
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
        Playlists lists = PlaylistStore.load(activity.prefs);
        Playlist p = lists.find(openId);
        if (p == null) {
            openId = null;
            showList();
            return;
        }
        final long now = System.currentTimeMillis();
        Library lib = library();
        PlaylistStatus status = PlaylistStatus.of(lists, lib, now);
        // Built before the old body is detached, so the name field being replaced
        // is still the one the focus listener will see leave. The listener checks
        // identity with the current field, because by the time the detach fires
        // nameField has moved on to the new one -- see nameFieldFor.
        LinearLayout box = column();
        nameField = nameFieldFor(p);
        box.addView(nameField);

        // The decided order: the photographs, then the configuration that moves
        // them, then the consequence, then the destructive end. Photos first
        // because they are what the playlist is; configuration next because it
        // only means something about them; the status line and the action after
        // it because they are the answer; Delete last, below its own hairline,
        // because it is the one thing here that cannot be undone.
        //
        // ---- which photos
        box.addView(caption(str(R.string.playlists_photos_caption)));
        box.addView(photoList(p, lib));

        // ---- How it picks: In order or Shuffle, one of two.
        box.addView(pickingGroup(p));

        // ---- Surfaces: one switch per screen. Two switches, not checkboxes:
        // there are exactly two and no third can appear, so a checkbox would
        // imply a set that can grow; see the class note.
        box.addView(surfacesGroup(p));

        // ---- When: one of four, as a radio group.
        box.addView(triggerGroup(p));

        // ---- the consequence, in the same words the list row uses.
        box.addView(statusBlock(p, lists, status, now));

        // ---- the action the screen exists for.
        box.addView(primaryActionRow(p, status));

        // ---- Delete, last in the scroll, behind its own hairline. Always
        // drawn: this screen is the ONLY place a playlist can be removed, so
        // hiding it would leave a playlist undeletable. See deleteBand.
        box.addView(deleteBand(p));

        sheet.body(box);

        // The same Done as the settings sheet, in the same place: the one visible
        // way out, ghost, full width, changing nothing. The screen's own action
        // is a row in the body above rather than a second footer plate beside
        // this one -- two actions with opposite consequences must not share a
        // row, and neither must an action and a dismissal.
        Button done = ModalSheet.button(activity, str(R.string.action_done), false, true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commitName();
                showList();
            }
        });
        sheet.footerRow(done);
    }

    /**
     * What this playlist is doing, in one line, with the honest caveat under it.
     *
     * <p>The screen knew all of this and said none of it: the user configured a
     * playlist and then asked "how do I use it" because nothing on the screen
     * named what would move, where, or when. The line is the same sentence the
     * list row reads, from the same {@link PlaylistStatus}, so the two cannot
     * drift.
     *
     * <p>An idle playlist that shares a screen with a running one says
     * <i>which</i> one is running: at most one rotates per screen, so pressing
     * Use here stands that one down, and a user must be able to see whose turn
     * they are taking.
     */
    private View statusBlock(Playlist p, Playlists lists, PlaylistStatus status, long now) {
        LinearLayout box = column();
        TextView line = new TextView(activity);
        line.setTextSize(13);
        if (status.playlist == p) {
            line.setText(activity.getString(R.string.playlists_row_rotating, surfaces(p),
                    nextChangeLabel(activity, p, status, now)));
            line.setTextColor(ACCENT);
        } else {
            String text = str(R.string.playlists_status_not_rotating);
            for (Surface s : p.surfaces) {
                Playlist other = lists.rotating(s);
                if (other != null && other != p) {
                    text = activity.getString(R.string.playlists_status_other,
                            other.displayName(), surfaceName(s));
                    break;
                }
            }
            line.setText(text);
            line.setTextColor(DIM);
        }
        line.setPadding(0, 0, 0, dp(4));
        box.addView(line);
        if (p.size() < 2) {
            // A one-photo list has nowhere to go: Use would "advance" it to
            // itself and the wallpaper would not change, which is the same
            // broken-feature reading this whole screen is fixing.
            box.addView(note(str(R.string.playlists_status_needs_two)));
        }
        return box;
    }

    /**
     * The screen's own action: start this playlist, or stop it.
     *
     * <p>{@code Use} runs {@link #use}, which advances once in the same commit,
     * so the studio behind the card changes in the moment the row is pressed.
     * {@code Stop} only clears the flag: the current photo is not the rotation's
     * to take back. It is an {@link ModalSheet#actionRow} — the shared plate the
     * settings sheet's Reset uses — so it is the same picture as every other
     * thing in the app that can be pressed, and it sits in the body above Delete
     * rather than in the footer beside Done.
     */
    private View primaryActionRow(final Playlist p, PlaylistStatus status) {
        final boolean running = status.playlist == p;
        return ModalSheet.actionRow(activity,
                str(running ? R.string.playlists_stop : R.string.playlists_use_this),
                str(running ? R.string.playlists_stop_sub : R.string.playlists_use_this_sub),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (running) {
                            PlaylistStore.setActive(activity.prefs, p.id, false);
                        } else {
                            use(p);
                        }
                        showEditor();
                    }
                });
    }

    /**
     * Delete, last in the scroll, behind its own hairline.
     *
     * <p>A destructive action is never a peer of the way out: it left the footer
     * because the footer is where Done lives, and it is here, after everything
     * the user configured, because that is the only place on the card that is
     * not on the path between opening the screen and leaving it. The confirm
     * dialog stays as the second gate.
     *
     * <p><b>Always drawn, for one reason: this screen is the only place a playlist
     * can be removed.</b> It was briefly gated behind {@code size() > 1}, on the
     * reasoning that a one-photo playlist is not worth a destructive row. That
     * reasoning was borrowed from a rule about <i>controls that do nothing</i>,
     * and it does not apply here: deleting a playlist always does something and
     * always means something. The result was the opposite of the intent — an
     * empty or single-photo playlist became <b>undeletable from the UI
     * altogether</b>, with the only way out being to add a photo first. A
     * destructive action's correct place is the far end of the scroll; making it
     * unreachable is not a safer version of that.
     */
    private View deleteBand(final Playlist p) {
        LinearLayout box = column();
        View hair = new View(activity);
        hair.setBackgroundColor(LINE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        hp.topMargin = dp(18);
        hp.bottomMargin = dp(16);
        box.addView(hair, hp);
        box.addView(ModalSheet.actionRow(activity, str(R.string.playlist_delete),
                activity.getString(R.string.playlists_delete_message, p.displayName()),
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmDelete(p.id);
                    }
                }));
        return box;
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
                // Only the CURRENT field may commit. A rebuild detaches the old
                // field, and that focus loss arrives after nameField has already
                // been reassigned to the new, empty one; committing then would
                // write "" over the name the user just typed. Every rebuild path
                // commits explicitly before it reassigns, so nothing is lost.
                if (!hasFocus && v == nameField) commitName();
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
     * "How it picks": In order or Shuffle, one of the two.
     *
     * <p><b>Shuffle is a radio here, not a switch, and the previous build was
     * wrong on both counts.</b> Order and shuffle are mutually exclusive — which
     * is exactly what a radio is — and the old pair put "Rotate through this
     * list" directly above "Shuffle", so both read as modes of walking the list
     * and the activation control vanished into the pair. The activation is
     * {@link #primaryActionRow}; this group only says how a running rotation
     * picks.
     */
    private View pickingGroup(final Playlist p) {
        LinearLayout box = column();
        box.addView(caption(str(R.string.playlists_how_it_picks)));
        final SingleChoiceGroup group = new SingleChoiceGroup();
        box.addView(choiceRow(str(R.string.playlists_in_order),
                str(R.string.playlists_in_order_sub), !p.shuffle, group,
                new Runnable() {
                    @Override
                    public void run() {
                        if (!p.shuffle) return;
                        setShuffle(p, false);
                    }
                }));
        box.addView(choiceRow(str(R.string.playlists_shuffle_row),
                str(R.string.playlists_shuffle_row_sub), p.shuffle, group,
                new Runnable() {
                    @Override
                    public void run() {
                        if (p.shuffle) return;
                        setShuffle(p, true);
                    }
                }));
        return box;
    }

    private void setShuffle(final Playlist p, final boolean on) {
        PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
            @Override
            public Playlist edit(Playlist current) {
                return current.withShuffle(on);
            }
        });
        showEditor();
    }

    /**
     * "Where it shows": one switch per screen.
     *
     * <p>Two switches rather than a checkbox group, and that is the judgement
     * call: there are exactly two screens and no third can appear, so a checkbox
     * would imply a set that can grow. Two switches say exactly what
     * {@link Playlist#surfaces} stores — these two screens, independently on or
     * off — and it is the same gesture the studio sheet's Home·Lock segment
     * already means. The spec's trap is the opposite failure: a boolean group
     * that quietly behaves like a radio group. Each switch edits its own screen
     * and never the other; only the last-one-off guard couples them, and it
     * refuses rather than silently choosing.
     */
    private View surfacesGroup(final Playlist p) {
        LinearLayout box = column();
        box.addView(caption(str(R.string.playlists_where_it_shows)));
        box.addView(surfaceSwitch(p, Surface.HOME, R.string.apply_home));
        box.addView(surfaceSwitch(p, Surface.LOCK, R.string.apply_lock));
        if (!SurfaceResolver.supportsPerSurfaceLock(Build.VERSION.SDK_INT)) {
            // The stored surfaces are intent, and a degrade must never be written
            // into the blob, so this is a line under the group rather than a row
            // that cannot be ticked. See the class note on Playlist.surfaces.
            box.addView(note(str(R.string.apply_lock_note)));
        }
        return box;
    }

    /**
     * One surface, on or off.
     *
     * <p>The last one cannot be turned off, and says so.
     * {@link Playlist#appliesTo} answers no for an empty list, so a playlist on no
     * screen is one that never moves — and {@link Playlist#withSurfaces} would
     * quietly put it back on the home screen instead, which is a control that
     * un-ticks and re-ticks itself.
     *
     * <p>The refusal path rebuilds the editor. A platform switch has already
     * moved by the time the listener runs, so returning without a rebuild would
     * leave the widget showing a state the model refused — a control lying about
     * what it did, which is the failure this screen exists to remove.
     */
    private View surfaceSwitch(final Playlist p, final Surface surface, int labelRes) {
        final boolean on = p.surfaces.contains(surface);
        return ModalSheet.switchRow(activity, str(labelRes),
                str(R.string.playlists_surface_sub), on, new ModalSheet.OnToggled() {
                    @Override
                    public void onToggled(boolean nowOn) {
                        if (nowOn == on) return;
                        if (nowOn) {
                            // alsoOn rather than a rebuilt list, so a write that
                            // adds a surface cannot drop one this build has never
                            // heard of.
                            PlaylistStore.edit(activity.prefs, p.id, new PlaylistStore.Editor() {
                                @Override
                                public Playlist edit(Playlist current) {
                                    return current.alsoOn(surface);
                                }
                            });
                        } else {
                            if (p.surfaces.size() <= 1) {
                                toast(R.string.playlists_needs_surface);
                                showEditor();
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
                        }
                        showEditor();
                    }
                });
    }

    /**
     * "When it changes": one of the four triggers.
     *
     * <p>Four rows under one caption, the selected one wearing a faint accent
     * wash, so they read as one choice rather than four switches. The group owns
     * the exclusivity — see {@link SingleChoiceGroup} — and this method is the
     * only place that registers rows with it, so a row cannot be wired to the
     * wrong group without this method changing.
     *
     * <p>{@link Trigger#ON_PICKUP} stays shown, dimmed, and refuses by name.
     * Hiding it would make a stored {@code on_pickup} read as {@code daily}
     * everywhere it is shown, and a wallpaper that starts changing on a clock is
     * the "must never look like a bug" outcome the model exists to prevent.
     */
    private View triggerGroup(final Playlist p) {
        LinearLayout box = column();
        box.addView(caption(str(R.string.playlists_when_it_changes)));
        final SingleChoiceGroup group = new SingleChoiceGroup();
        box.addView(triggerChoice(p, group, Trigger.DAILY,
                R.string.playlists_trigger_daily, R.string.playlists_trigger_daily_sub));
        box.addView(triggerChoice(p, group, Trigger.HOURLY,
                R.string.playlists_trigger_hourly, R.string.playlists_trigger_hourly_sub));
        box.addView(triggerChoice(p, group, Trigger.ON_UNLOCK,
                R.string.playlists_trigger_unlock, R.string.playlists_trigger_unlock_sub));
        box.addView(triggerChoice(p, group, Trigger.ON_PICKUP,
                R.string.playlists_trigger_pickup, R.string.playlists_trigger_pickup_sub));
        return box;
    }

    private View triggerChoice(final Playlist p, SingleChoiceGroup group,
                               final Trigger trigger, int labelRes, int subRes) {
        final boolean unavailable = trigger == Trigger.ON_PICKUP;
        LinearLayout row = choiceRow(str(labelRes), str(subRes), p.trigger == trigger,
                group, new Runnable() {
                    @Override
                    public void run() {
                        if (unavailable) {
                            // Named, not silent. A dimmed row that is also inert
                            // is a control that does nothing at all. The rebuild
                            // puts the stored choice back, because the tap lit
                            // this row before it was refused.
                            toast(R.string.playlists_pickup_unavailable);
                            showEditor();
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
                });
        if (unavailable) row.setAlpha(LOCKED_ALPHA);
        return row;
    }

    /**
     * One choice in a single-choice group.
     *
     * <p>The group registers the mark and lights exactly one at selection time;
     * see {@link SingleChoiceGroup}. A row that owned its own check would let two
     * choices look chosen, which is the trap the brief names.
     */
    private LinearLayout choiceRow(String title, String sub, boolean selected,
                                   final SingleChoiceGroup group, final Runnable choose) {
        LinearLayout row = radioRow(title, sub, selected);
        final RadioButton mark = (RadioButton) row.getChildAt(1);
        group.register(mark);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The group owns exclusivity, and it happens before anything
                // writes: a refusal rebuilds and the mark cannot lie.
                group.select(mark);
                choose.run();
            }
        });
        return row;
    }

    /**
     * One radio choice: the shared switch row's plate with a radio mark where
     * its trailing switch was.
     *
     * <p><b>Built by taking {@link ModalSheet#switchRow} apart, not by copying
     * it.</b> The plate, the padding, the type scale and the 12dp gap are the
     * shared row's, so this screen does not grow the fourth row role that made
     * the previous editor read as a different app; the only thing replaced is
     * the trailing widget, because a {@link android.widget.Switch} cannot say
     * "one of four". The mark is not a target — the row owns the tap — so it is
     * neither clickable nor focusable, the same rule the switch follows.
     */
    private LinearLayout radioRow(String title, String sub, boolean selected) {
        LinearLayout row = ModalSheet.switchRow(activity, title, sub, selected, null);
        // switchRow's children are [text (weight 1), trailing widget]; the
        // trailing LayoutParams carry the gap the text column was given.
        View trailing = row.getChildAt(1);
        row.removeViewAt(1);
        RadioButton mark = new RadioButton(activity);
        mark.setClickable(false);
        mark.setFocusable(false);
        mark.setChecked(selected);
        mark.setButtonTintList(new ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{ ACCENT, DIMMER }));
        // The row describes itself; the mark is a picture of the choice.
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, trailing.getLayoutParams());
        // The selected choice wears the faint accent wash, so a group of rows
        // reads as one choice rather than as so many switches.
        GradientDrawable bg = (GradientDrawable) row.getBackground();
        if (bg != null) bg.setColor(selected ? CHOICE_WASH : Color.TRANSPARENT);
        row.setContentDescription(title + ". " + sub + ", " + str(selected
                ? R.string.playlists_radio_selected
                : R.string.playlists_radio_unselected));
        return row;
    }

    /**
     * One group of single-choice rows.
     *
     * <p><b>Not {@link android.widget.RadioGroup}.</b> That widget only manages
     * direct {@link RadioButton} children, and a choice here is the shared framed
     * row with a mark in its trailing slot; the widget would force either the
     * plate onto the group (one plate for four rows) or the mark out of the row,
     * and the whole point is that the row stays one row. The guarantee is the
     * same and lives in exactly one place: the group holds every mark it owns and
     * {@link #select} lights exactly one. Each group is its own instance, so a
     * pick in the shuffle group cannot disturb the trigger group — two groups
     * sharing one controller is the failure the brief warns about, and the
     * per-group list is what makes it structurally impossible.
     */
    private static final class SingleChoiceGroup {

        private final List<RadioButton> marks = new ArrayList<RadioButton>();

        void register(RadioButton mark) {
            marks.add(mark);
        }

        void select(RadioButton chosen) {
            for (RadioButton mark : marks) mark.setChecked(mark == chosen);
        }
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
    private View photoList(Playlist p, Library lib) {
        LinearLayout box = column();
        if (p.isEmpty()) {
            // The way in is the gallery's own Add to playlist, which already
            // exists and appends to this list. Saying so beats adding a second
            // route that would then have to be kept in step with it.
            box.addView(note(str(R.string.playlists_none_photos)));
            return box;
        }
        for (int i = 0; i < p.itemIds.size(); i++) {
            final String id = p.itemIds.get(i);
            final LibraryItem item = lib.find(id);
            final String label = item == null ? null : item.displayName();
            LinearLayout r = option(false);
            Row texts;
            if (item == null) {
                r.addView(swatch(null, false));
                texts = texts(str(R.string.playlists_photo_missing),
                        str(R.string.playlists_photo_missing_sub));
                announce(r, texts, R.string.playlists_state_missing);
            } else {
                r.addView(swatch(item, false));
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
        final ModalSheet confirm = new ModalSheet(activity, MODAL_MAX_FRACTION);
        // A question whose answer is destructive opts out of scrim-tap dismissal.
        // A tap on inert dimming is very often a mis-aimed tap meant for the card,
        // and losing the question costs the user the whole read-and-decide -- so on
        // THIS dialog the backdrop is not an exit. Its own Cancel and Delete are.
        confirm.setDismissOnScrimTap(false);
        LinearLayout body = column();
        TextView head = ModalSheet.title(activity, str(R.string.gallery_delete_title));
        // The gap the other modals get from their subtitle; see Gallery.modalBody.
        head.setPadding(0, 0, 0, dp(10));
        body.addView(head);
        body.addView(ModalSheet.subtitle(activity,
                activity.getString(R.string.playlists_delete_message, p.displayName())));
        confirm.body(body);

        Button cancel = ModalSheet.button(activity, str(R.string.action_cancel), false, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm.dismiss();
            }
        });
        Button delete = ModalSheet.button(activity, str(R.string.playlist_delete), true, true);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Close first, then act: the editor is what is left when the
                // question goes, and the same order the gallery's confirm uses.
                confirm.dismiss();
                PlaylistStore.remove(activity.prefs, id);
                showList();
            }
        });
        confirm.footerRow(cancel, delete);
        confirm.show(activity);
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
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

}
