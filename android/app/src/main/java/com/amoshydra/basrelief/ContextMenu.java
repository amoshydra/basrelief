package com.amoshydra.basrelief;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The gallery's row menu: a small card that opens at the finger that asked for
 * it, or under the view that owns the command, and closes on a choice, an
 * outside tap or BACK.
 *
 * <p><b>Why it is anchored, and not the bottom-sheet card it replaces.</b> A row
 * menu is a per-item action — the finger is on the cell — and the sheet card
 * opened at the far end of the screen, over the content it acts on, with the
 * finger nowhere near the command it chose; the menu belongs where the gesture
 * happened. Do not "simplify" this back into a {@link ModalSheet}.
 *
 * <p><b>Why {@link PopupWindow} and not a sibling view or a Dialog.</b> A
 * sibling drawn over the studio's {@code GLSurfaceView} is the b/7179570
 * hazard — placed, attached, opaque and absent from the display when its region
 * is positioned by a post-layout transform (see AGENTS.md). A Dialog is its own
 * window and so escapes that hazard, but it is a full-screen window that cannot
 * be opened at an arbitrary point without window-flag gymnastics, and this tree
 * already refuses a second Dialog ({@code scripts/check-parity.mjs}). A
 * PopupWindow is its own window <i>and</i> the one framework mechanism that
 * takes an arbitrary x/y with {@code Gravity.NO_GRAVITY}.
 *
 * <p><b>BACK is the platform's, on both API branches.</b> On API 33+
 * PopupWindow's decor registers an {@link android.window.OnBackInvokedCallback}
 * on its own window dispatcher in {@code onAttachedToWindow} and calls
 * {@code dismiss()} (AOSP {@code core/java/android/widget/PopupWindow.java});
 * on 26–32 its decor dispatches {@code KEYCODE_BACK}. A key listener here would
 * be dead code at targetSdk 36, and a second callback would double-dismiss, so
 * this class installs neither. All it does is set {@code focusable(true)}, which
 * is what routes back to the popup at all.
 *
 * <p><b>Dismiss first, then act.</b> A choice can open a {@link ModalSheet}
 * (the gallery's Rename does), and a menu left under it is a back press that
 * resurrects a menu whose moment has passed — the two-stacked-modals failure
 * this app already paid for once. The click listener closes the popup before
 * running the item.
 *
 * <p><b>Every row is a full-width 48dp target, and the drawn plate is that
 * target.</b> 48dp is the platform's minimum touch target; the plate is not a
 * smaller picture inset inside it, because the pressed fill <i>is</i> the
 * feedback and a plate 4dp short on each side would highlight less than the
 * finger covers. The pressed colour is exactly {@code ModalSheet.plate}'s ghost
 * pressed state, so the menu cannot grow a second idea of what "pressed" looks
 * like.
 *
 * <p><b>Destructive items use the app's existing bad ink</b> — the terracotta
 * the fix line's failed sentence and dashed border already use — not a new red.
 * It is the only thing besides the label that tells Delete from Rename.
 *
 * <p><b>No user-facing literals.</b> Labels, descriptions and actions all come
 * from the caller; this file has no strings, no resources and no content
 * descriptions to keep in sync with them.
 */
final class ContextMenu {

    // ----------------------------------------------------------------- tokens

    /**
     * The gap the menu keeps from the visible display's edges, in dp.
     *
     * <p>8dp is Material's own popup-menu margin from the screen edge, and it is
     * the value that clears a modern display's rounded corners and gesture rails
     * while still letting the menu open beside the point that summoned it. The
     * menu is clamped to {@code frame ± this}, never to the raw screen, so the
     * status and navigation bars are already excluded by the frame itself.
     */
    private static final int SCREEN_MARGIN_DP = 8;

    /**
     * The gap between the anchor and the menu's nearer edge, in dp.
     *
     * <p>For a touch anchor this is the fingertip's own radius: without it the
     * first row's label sits under the finger that asked for the menu. For a
     * view anchor it keeps the menu from touching the control it belongs to,
     * which is what makes the two read as one control and its commands.
     */
    private static final int ANCHOR_GAP_DP = 4;

    /** The drawn row height, which is also the touch target. See the class javadoc. */
    private static final int ITEM_HEIGHT_DP = 48;

    /**
     * The narrowest a menu may be, in dp.
     *
     * <p>A menu that shrink-wraps a single "Rename" is a chip barely wider than
     * its label and reads like a tooltip, not a command list. 168dp fits the
     * longest label the gallery sends ("Paste onto selected", 19 characters at
     * 14sp, ~130dp) on one line plus the row's 12dp sides, and still leaves the
     * card visibly wider than tall on a short list.
     */
    private static final int MIN_WIDTH_DP = 168;

    /**
     * The corner radius, in dp — the same 11dp every plate and option row in
     * the mockup uses. Material's sharper 4dp menu corners would be the only
     * square-cornered thing in the app.
     */
    private static final int CORNER_DP = 11;

    /**
     * {@code ModalSheet.plate}'s ghost pressed fill (rgba(255,255,255,.18)),
     * copied rather than invented: {@code plate} is private to ModalSheet and
     * the value, not the helper, is the shared language. A different alpha here
     * would make one control in the app press differently from every other.
     */
    private static final int PRESSED = 0x2EFFFFFF;

    /**
     * The mockup's {@code --bad}, the same value as {@code MainActivity}'s
     * {@code BAD_COLOUR} and the failed-run sentence in {@code DepthGenDialog}.
     * A destructive command is the one thing in the menu that must not read as
     * ordinary, and the app already owns a colour whose meaning is "this is
     * bad"; a new red would be a second colour language for the same idea.
     */
    private static final int DESTRUCTIVE_INK = 0xFFE2725B;

    /**
     * The entrance's duration: the shared card spring's own floor
     * ({@code SpringSettle.CARD.minDurationMs} = 140 ms), which is also under
     * the ~150 ms budget a menu gets — a menu is a response, not a screen.
     *
     * <p>It is deliberately not {@code CARD.duration(distance, speed)}: that
     * derivation assumes a drag released at the profile's minimum speed, and a
     * menu's full-travel entrance at that speed would ask for 450 ms, which is a
     * sheet's settle. The curve is the shared one; the duration is its floor.
     */
    private static final long ENTRANCE_MS = SpringSettle.CARD.minDurationMs;

    /** How far the card starts scaled down. One value, so scale cannot drift from alpha. */
    private static final float ENTRANCE_SCALE = 0.92f;

    private ContextMenu() {
    }

    // -------------------------------------------------------------------- API

    /**
     * One command in a menu.
     *
     * <p>The four fields are the whole of an item; there is no builder and no
     * copy method, because a menu is built once and shown once — an item edited
     * after it is on screen would be a row whose picture and action disagree.
     */
    static final class Item {

        /** The visible label. Supplied by the caller; never invented here. */
        final CharSequence label;

        /** True for an action that destroys something; drawn in {@link ContextMenu#DESTRUCTIVE_INK}. */
        final boolean destructive;

        /**
         * False for a command that is shown but cannot run yet (Paste with
         * nothing on the clipboard). The row dims and consumes no touches.
         */
        final boolean enabled;

        /** What a tap runs, after the menu is gone. May be null on a disabled item. */
        final Runnable action;

        /** An enabled, ordinary command. */
        Item(CharSequence label, Runnable action) {
            this(label, action, false, true);
        }

        /**
         * A command with both flags named.
         *
         * @param destructive the app's bad ink; see {@link ContextMenu#DESTRUCTIVE_INK}
         * @param enabled     false to show the command without offering it
         */
        Item(CharSequence label, Runnable action, boolean destructive, boolean enabled) {
            this.label = label;
            this.action = action;
            this.destructive = destructive;
            this.enabled = enabled;
        }

        /** Delete: the same row language as every other command, in the app's bad ink. */
        static Item destructive(CharSequence label, Runnable action) {
            return new Item(label, action, true, true);
        }
    }

    /**
     * Opens with its top-left near the given screen point, clamped to fit, and
     * flips above the point when the menu is taller than the space below it.
     *
     * @param activity the window the menu belongs to; its decor supplies the
     *                 window token and the visible display frame
     * @param screenX  raw screen x — {@code MotionEvent.getRawX()}, not the
     *                 event's view-relative x
     * @param screenY  raw screen y
     * @param items    the commands, top to bottom. Null or empty is a no-op:
     *                 an empty card is a control with nothing to say
     */
    static void showAt(Activity activity, float screenX, float screenY, List<Item> items) {
        if (items == null || items.isEmpty()) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow().getDecorView();
        if (decor.getWindowToken() == null) return;
        int x = Math.round(screenX);
        int y = Math.round(screenY);
        // A point anchor: left and right, top and bottom are the same pixel, so
        // the shared placement sees a degenerate rect and grows down-right.
        show(activity, decor, x, y, x, y, false, items);
    }

    /**
     * The anchored variant: opens just under a view, aligned to its end edge
     * (right edges in LTR, left edges in RTL), and flips above the view when
     * there is no room below.
     *
     * <p>Uses the anchor's root view as the window host, so a row inside a
     * Dialog opens its menu in the Dialog's own window — the token, the visible
     * frame and the coordinate origin are all the anchor's window's, never the
     * activity's.
     */
    static void showUnder(View anchor, List<Item> items) {
        if (items == null || items.isEmpty()) return;
        View root = anchor.getRootView();
        if (root.getWindowToken() == null) return;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int left = loc[0];
        int top = loc[1];
        // "End" is layout-direction aware: LTR aligns the two right edges, RTL
        // the two left ones. Passing the flag through place() keeps the RTL
        // branch in one place instead of a second geometry here.
        boolean ltr = anchor.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL;
        show(anchor.getContext(), root, left, top, left + anchor.getWidth(),
                top + anchor.getHeight(), ltr, items);
    }

    // -------------------------------------------------------------- internals

    /**
     * Build, measure, place and show one menu, then start its entrance.
     *
     * <p>One path for both public entry points, so the clamp, the flip, the
     * pressed treatment and the dismissal rule cannot drift between the
     * finger-anchored and view-anchored menus — the two differ only in the
     * anchor rect and which horizontal edge the menu prefers.
     *
     * @param context the context the rows are built with
     * @param host    the view whose window the popup attaches to; its on-screen
     *                location is the origin PopupWindow's x/y are relative to
     * @param anchorLeft  anchor's screen left (a point's x for {@link #showAt})
     * @param anchorTop   anchor's screen top
     * @param anchorRight anchor's screen right (the point's x again)
     * @param anchorBottom anchor's screen bottom (the point's y again)
     * @param alignToEnd  true to prefer the menu's right edge at the anchor's
     *                    right edge (LTR {@link #showUnder}); false to prefer
     *                    its left edge at the anchor's left
     */
    private static void show(Context context, View host,
            int anchorLeft, int anchorTop, int anchorRight, int anchorBottom,
            boolean alignToEnd, List<Item> items) {

        Rect frame = new Rect();
        // The visible display frame, not the raw display: it already excludes
        // the status and navigation bars, in screen coordinates (AOSP's own
        // PopupWindow clamps against this same rect, in these same units).
        host.getWindowVisibleDisplayFrame(frame);
        int margin = ModalSheet.dp(context, SCREEN_MARGIN_DP);
        int gap = ModalSheet.dp(context, ANCHOR_GAP_DP);

        final PopupWindow popup = new PopupWindow(context);
        FrameLayout card = buildCard(context, items, popup);

        // Measure before showing, because the clamp and the flip need the
        // menu's size and the window does not exist yet. The width and height
        // are then handed to the popup as its exact window size, so this
        // measurement is the one that ships, not an estimate. The ScrollView
        // caps the height at the AT_MOST spec, which is what makes a long menu
        // scroll rather than run off the display.
        card.measure(
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(1, frame.width() - 2 * margin), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(
                        Math.max(1, frame.height() - 2 * margin), View.MeasureSpec.AT_MOST));
        int width = card.getMeasuredWidth();
        int height = card.getMeasuredHeight();
        if (width <= 0 || height <= 0) return;

        Point topLeft = place(frame, margin, gap, width, height,
                anchorLeft, anchorTop, anchorRight, anchorBottom, alignToEnd);

        // The entrance grows from the corner nearest the anchor: if the anchor
        // is left of the menu's centre, the left edge is the near one; if it is
        // above the menu's centre, the top edge is. That one rule puts the
        // pivot at the finger for a menu opened down-right, at the top-right
        // for a menu clamped back from the right edge, and at the bottom-left
        // for a menu that flipped above — no case table.
        float anchorX = (anchorLeft + anchorRight) / 2f;
        float anchorY = (anchorTop + anchorBottom) / 2f;
        boolean growFromLeft = anchorX < topLeft.x + width / 2f;
        boolean growFromTop = anchorY < topLeft.y + height / 2f;

        // Invisible before the window exists, so even if a frame were drawn
        // before the animator's first update, it would draw nothing: there is
        // no flash of the menu at full size before the entrance starts.
        card.setAlpha(0f);
        card.setScaleX(ENTRANCE_SCALE);
        card.setScaleY(ENTRANCE_SCALE);
        card.setPivotX(growFromLeft ? 0f : width);
        card.setPivotY(growFromTop ? 0f : height);

        popup.setContentView(card);
        popup.setWidth(width);
        popup.setHeight(height);
        // Focusable, for two reasons: BACK is routed to the focused window (the
        // platform's own callback dismisses it; see the class javadoc), and a
        // focusable popup is touch-modal, so an outside tap dismisses instead
        // of falling through to the cell under it.
        popup.setFocusable(true);
        // Sets FLAG_WATCH_OUTSIDE_TOUCH; PopupWindow's decor turns the resulting
        // ACTION_OUTSIDE into dismiss().
        popup.setOutsideTouchable(true);
        // The conventional companion to setOutsideTouchable: a non-null
        // background is what the documented recipe pairs with it, and it is
        // transparent because the card's own rounded shell is the only thing
        // that should draw. (The dismissal itself comes from the decor's
        // ACTION_OUTSIDE branch, which the flag above enables.)
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // PopupWindow's x/y are relative to the app root view, not the screen:
        // AOSP's findDropDownPosition computes
        // drawingLocation = screenLocation - appRootView.getLocationOnScreen(),
        // and host is that root (the decor for showAt, the anchor's root for
        // showUnder). Same subtraction here, so no coordinate space is mixed.
        int[] hostLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        popup.showAtLocation(host, Gravity.NO_GRAVITY,
                topLeft.x - hostLoc[0], topLeft.y - hostLoc[1]);

        startEntrance(card);
    }

    /**
     * The menu's screen top-left, from the anchor and the menu's measured size.
     *
     * <p><b>Horizontal: clamp to the margins.</b> With margin {@code M} and menu
     * width {@code w}, the legal left edge is
     * {@code [frame.left + M, frame.right - M - w]}. The preferred edge is the
     * anchor's own end edge minus {@code w} ({@link #showUnder}, LTR) or its
     * start edge (a point anchor, and RTL), so the result is
     * {@code min(max(preferred, frame.left + M), frame.right - M - w)}. The
     * measured width is capped at {@code frame.width() - 2M}, so that interval
     * is never empty and the menu can never hang off a side. Clamping, not
     * sliding back to the anchor: a menu pinned to the near margin is still at
     * the finger, which is the whole point of an anchored menu.
     *
     * <p><b>Vertical: fit below, else flip above, else pin.</b> With gap
     * {@code g} and height {@code h}, the menu opens below at
     * {@code y = anchorBottom + g} when {@code y + h <= frame.bottom - M}. When
     * it does not, it flips above at {@code y = anchorTop - g - h} if
     * {@code y >= frame.top + M}. When neither fits — an anchor near the middle
     * with a menu close to the frame's full height — it is pinned to
     * {@code frame.top + M}; the menu then overlaps the anchor, which is the
     * only outcome that keeps every row on the display. The height is capped at
     * {@code frame.height() - 2M} by the ScrollView, so the pinned case always
     * has a legal top.
     *
     * <p>Frame and anchor are both screen pixels, so no conversion happens here;
     * {@link #show} subtracts the host's own location once, at the window
     * boundary.
     */
    private static Point place(Rect frame, int margin, int gap, int width, int height,
            int anchorLeft, int anchorTop, int anchorRight, int anchorBottom,
            boolean alignToEnd) {

        int preferredLeft = alignToEnd ? anchorRight - width : anchorLeft;
        int left = Math.max(frame.left + margin,
                Math.min(preferredLeft, frame.right - margin - width));

        int below = anchorBottom + gap;
        int top;
        if (below + height <= frame.bottom - margin) {
            top = below;
        } else {
            int above = anchorTop - gap - height;
            if (above >= frame.top + margin) {
                top = above;
            } else {
                top = Math.max(frame.top + margin,
                        Math.min(below, frame.bottom - margin - height));
            }
        }
        return new Point(left, top);
    }

    /**
     * The card and its rows: a rounded shell, a scrolling column, one row per
     * item.
     *
     * <p>The ScrollView is the height cap the framework does not have an
     * attribute for: measured with an AT_MOST spec it clamps its own height to
     * the spec while the column keeps its natural height, so a menu with more
     * commands than fit scrolls instead of overflowing. The card is clipped to
     * its own rounded outline, which is what rounds the first and last rows'
     * full-bleed pressed fill without a second row shape per position.
     */
    private static FrameLayout buildCard(Context context, List<Item> items,
                                         PopupWindow popup) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        // The floor lives on the column, not the card: the rows are MATCH_PARENT
        // inside the column, so a minimum here stretches every row to it, while
        // a minimum on the card would leave the rows narrower than their shell.
        column.setMinimumWidth(ModalSheet.dp(context, MIN_WIDTH_DP));
        for (Item item : items) {
            column.addView(row(context, item, popup));
        }

        ScrollView scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout card = new FrameLayout(context);
        GradientDrawable shell = new GradientDrawable();
        shell.setColor(ModalSheet.CARD);
        shell.setCornerRadius(ModalSheet.dp(context, CORNER_DP));
        shell.setStroke(ModalSheet.dp(context, 1), ModalSheet.LINE);
        card.setBackground(shell);
        card.setClipToOutline(true);
        card.addView(scroller, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /**
     * One command row: the label on the shared pressed plate, at least 48dp
     * tall, full width.
     *
     * <p>The height is a minimum rather than a fixed 48dp so a label that wraps
     * at a large font scale grows the row instead of clipping it; at the normal
     * scale a single line plus 12dp of padding is shorter than 48dp, so the
     * minimum is what is drawn.
     *
     * <p>A disabled row keeps the app's one disabled treatment —
     * {@code ModalSheet.option}'s {@code .locked}, the whole row at 42% — and
     * no click listener, so it cannot run and cannot announce itself as
     * tappable.
     */
    private static TextView row(Context context, final Item item, final PopupWindow popup) {
        TextView row = new TextView(context);
        row.setText(item.label);
        row.setTextSize(14);
        row.setTextColor(item.destructive ? DESTRUCTIVE_INK : ModalSheet.INK);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        int side = ModalSheet.dp(context, ModalSheet.OPT_PAD_DP);
        row.setPadding(side, ModalSheet.dp(context, 12), side, ModalSheet.dp(context, 12));
        row.setMinimumHeight(ModalSheet.dp(context, ITEM_HEIGHT_DP));
        row.setBackground(rowPlate());
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!item.enabled || item.action == null) {
            row.setAlpha(0.42f);
            row.setEnabled(false);
            row.setClickable(false);
            return row;
        }

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Dismiss FIRST, then act. The action can open a ModalSheet
                // (Rename does), and a menu still up under it is a back press
                // that resurrects a menu whose moment has passed — the stacked
                // modals this app already paid for once.
                popup.dismiss();
                item.action.run();
            }
        });
        return row;
    }

    /**
     * The row's background: transparent at rest, {@link #PRESSED} while held,
     * built exactly the way {@code ModalSheet.plate} builds its ghost state.
     *
     * <p>Square corners, not rounded: the card clips its children to its own
     * outline, so a middle row's fill reaches both edges and the first and last
     * rows get the shell's rounding for free. Rounded per-row corners would
     * leave a notch at the card's own corners.
     */
    private static GradientDrawable rowPlate() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_pressed}, new int[]{} },
                new int[]{ PRESSED, Color.TRANSPARENT }));
        return bg;
    }

    /**
     * The entrance: scale from {@link #ENTRANCE_SCALE} and fade in, over
     * {@link #ENTRANCE_MS}, on the shared card spring.
     *
     * <p>One animator drives both properties from one fraction, because the
     * spring overshoots past 1 by design (~9.5% at CARD's damping): that
     * overshoot is right for scale — it is what reads as a spring — and wrong
     * for alpha, which is clamped at 1 rather than allowed to overshoot into a
     * value the renderer would only clamp anyway. A single
     * {@code ViewPropertyAnimator} cannot express that split, so it is a
     * ValueAnimator and an update listener.
     *
     * <p>The card is already transparent and scaled when this starts (set in
     * {@link #show} before the window exists), so the first drawn frame is the
     * animation's own first frame, never a flash of the finished menu.
     */
    private static void startEntrance(final View card) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ENTRANCE_MS);
        anim.setInterpolator(SpringSettle.CARD.spring());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float scale = ENTRANCE_SCALE + (1f - ENTRANCE_SCALE) * t;
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setAlpha(Math.min(1f, t));
            }
        });
        anim.start();
    }
}
