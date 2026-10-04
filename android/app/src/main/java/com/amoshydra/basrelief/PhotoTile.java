package com.amoshydra.basrelief;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import java.io.File;

/**
 * One photograph drawn as the app's rounded square tile — the visual the
 * filmstrip, the grid, and the playlists screen's swatch and row photo all
 * share.
 *
 * <p><b>Four near-copies were one visual.</b> Each home built the same thing by
 * hand: a rounded fill, a {@code CENTER_CROP} bitmap clipped to that fill, and
 * the same two ways of marking it. A fix to any one of them — the foreground
 * ring, where a background ring is painted before the bitmap and the clip cuts
 * the stroke at the corners — only ever landed where it was noticed, and the
 * other three kept the old shape. The fill, the clip, the decode and the rings
 * live here once.
 *
 * <p><b>The active mark and the picked mark are independent, and they
 * compose.</b> Active is the persistent white ring that answers "what is on the
 * wallpaper right now"; picked is the 3dp accent ring, the wash and the
 * checkmark badge that answer "what will the next action touch". A tile can be
 * either, or both, and with both the accent ring stays outermost with the white
 * ring immediately inside it — one ring standing for two facts is how the
 * question became unanswerable once. Both are drawn from the same pair of
 * fields, so the outline can never disagree with the wash.
 *
 * <p><b>What is deliberately not here.</b> The grid's depth chip and its row
 * menu, and the playlists screen's corner dot, are added by their hosts with
 * {@code addView}: each is about that screen's job — can this photo stand out;
 * open this row's commands; this list is on — rather than about the tile, and
 * the dot even hangs outside the tile's own bounds. The tile owns only what all
 * four homes draw.
 */
final class PhotoTile extends FrameLayout {

    /**
     * The app's one selection colour, `--accent` in the mockup. It lives here
     * because the tile is where both of its uses in the selection language are
     * drawn — the picked ring and the checkmark badge — and a second literal in
     * the caller is how two "accents" drift apart.
     */
    static final int ACCENT = 0xFF7FD1C1;

    /** The tile's fill, the gallery's own tile colour, for the frames before a decode. */
    private static final int FILL = 0xFF2E3B44;

    /**
     * The picked cell's wash. The accent at 30%, so the photograph is visibly
     * claimed without being hidden — the user still has to be able to tell which
     * photographs they picked by looking at them.
     */
    private static final int PICK_TINT = 0x4D7FD1C1;

    /**
     * The archived cell's mute. A near-black wash that dims the photograph so an
     * archived row reads as set aside while still being recognisably the photo —
     * it is not deleted, and hiding it entirely would say it was. The mockup's
     * {@code .cell.archived} is a darker fill; a wash over the real photograph is
     * the same reading without a second decode path.
     */
    private static final int MUTE_TINT = 0xB3080B0E;

    /** The active ring: `.thumb[aria-current=true]`'s 2px white border. */
    private static final int RING_ACTIVE_DP = 2;

    /**
     * The picked ring, deliberately thicker than the active one. It is the
     * accent, so "picked" is the state that shouts, and a thicker stroke reads
     * across the room where the wash and the badge need a look.
     */
    private static final int RING_PICKED_DP = 3;

    /** The checkmark badge: big enough to read at a glance over a photograph. */
    private static final int BADGE_DP = 22;
    private static final int BADGE_MARGIN_DP = 6;

    private final MainActivity activity;

    /** The decode size: the tile's own edge, so a bitmap is never decoded larger. */
    private final int sizePx;

    /** The corner radius, kept because the rings have to follow it. */
    private final int radiusPx;

    private final ImageView photo;
    private final View mute;
    private final View wash;
    private final TextView badge;

    private boolean active;
    private boolean picked;
    private boolean muted;

    /**
     * One tile of the given edge and corner radius.
     *
     * <p>Both sizes are pixels and both are the caller's: the strip's 46dp
     * square and the grid's computed cell are the same tile at two scales, and
     * the playlists screen draws it at 34dp with a slightly tighter corner. The
     * size is kept because it is also the decode size; the radius is not.
     */
    PhotoTile(MainActivity activity, int sizePx, int radiusPx) {
        super(activity);
        this.activity = activity;
        this.sizePx = sizePx;
        this.radiusPx = radiusPx;

        // One fill for every home, including the bundled demo. The old
        // demo-specific brown was standing in for a photograph the tile could
        // not decode; the bundled row decodes its real res/raw image through
        // the same path as any other, so a second fill would be a colour the
        // user sees for one frame.
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.RECTANGLE);
        fill.setColor(FILL);
        fill.setCornerRadius(radiusPx);
        setBackground(fill);
        // A rounded background plus clipToOutline is the framework's own rounded
        // thumbnail. The alternative is a second bitmap pass per photograph, and
        // there is no view recycling in this app, so that is a per-photo cost
        // for a cosmetic edge.
        setClipToOutline(true);

        photo = new ImageView(activity);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(photo, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // The mute, for an archived cell. Added after the photograph so it dims
        // it, and before the pick wash so a picked archived row still shows its
        // accent. GONE until a host asks for it, so the cost on the strip, the
        // grid and every playlist swatch is one invisible view.
        mute = new View(activity);
        mute.setBackgroundColor(MUTE_TINT);
        mute.setVisibility(GONE);
        addView(mute, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // The wash and the badge are built for every tile and only their
        // visibility changes. Building them for the picked state alone is what
        // made a pick a rebuild in the grid: a mark that had never been created
        // could not be shown in place. Their cost on a strip or a playlist row
        // is two GONE views; the alternative is a component with two shapes and
        // a setPicked that only works on one of them.
        wash = new View(activity);
        wash.setBackgroundColor(PICK_TINT);
        wash.setVisibility(GONE);
        addView(wash, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        badge = new TextView(activity);
        badge.setText(activity.getString(R.string.gallery_selected_mark));
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ACCENT);
        badge.setBackground(circle);
        // Near-black on the accent, the same ink the primary buttons use, so the
        // tick reads on the smallest phone this app runs on.
        badge.setTextColor(0xFF08110F);
        badge.setVisibility(GONE);
        LayoutParams badgeLp = new LayoutParams(
                activity.dp(BADGE_DP), activity.dp(BADGE_DP),
                Gravity.BOTTOM | Gravity.END);
        badgeLp.setMargins(0, 0, activity.dp(BADGE_MARGIN_DP), activity.dp(BADGE_MARGIN_DP));
        addView(badge, badgeLp);

        drawRing();
    }

    /**
     * Decode this photograph into the tile, at the tile's own size.
     *
     * <p>The fileless bundled row decodes its packaged {@code res/raw} bytes
     * through the resource path at the same size as every other tile. A row
     * whose file has gone is left as the fill: a hole is a hole, and the
     * screen that owns the row says so in words beside it.
     */
    void show(LibraryItem item) {
        show(item, null);
    }

    /**
     * As above, and also report whether the PNG carries its own depth map.
     *
     * <p>The answer comes from the same read as the thumbnail on purpose: the
     * embedded depth lives in a PNG ancillary chunk, and a grid that asked
     * separately would read every photograph twice on a list with no view
     * recycling. The bundled demo's map is a separate {@code res/raw/depth.png}
     * rather than a chunk in these bytes, so there is nothing in them to find
     * and no listener is called.
     *
     * @param onEmbedded may be null
     */
    void show(LibraryItem item, Io.EmbeddedDepthListener onEmbedded) {
        if (item == null) return;
        if (item.isBundled()) {
            Io.decodeResourcePreview(activity, R.raw.image, photo, sizePx);
            return;
        }
        File file = PrefFiles.resolve(activity.getFilesDir(), item.imageName);
        if (file == null) return;
        Io.decodePreview(activity, file, photo, sizePx, onEmbedded);
    }

    /** Whether this photograph is the one on the wallpaper right now. */
    void setActive(boolean next) {
        if (active == next) return;
        active = next;
        drawRing();
    }

    /**
     * Whether this photograph is picked for the next many-at-once action.
     *
     * <p>Sets all three marks together — ring, wash and badge — because they
     * are one state seen at different distances, and a write that changed two
     * of them would leave the outline claiming a selection that has just been
     * cleared.
     */
    void setPicked(boolean next) {
        if (picked == next) return;
        picked = next;
        wash.setVisibility(next ? VISIBLE : GONE);
        badge.setVisibility(next ? VISIBLE : GONE);
        drawRing();
    }

    /**
     * Whether this photograph is hidden in the archive.
     *
     * <p>One wash, and no ring of its own: the mute says "set aside", and it is
     * independent of active and picked, so an archived photo that is also on the
     * wallpaper still wears the white ring. See {@link #MUTE_TINT}.
     */
    void setMuted(boolean next) {
        if (muted == next) return;
        muted = next;
        mute.setVisibility(next ? VISIBLE : GONE);
    }

    /** Redraw the outline for the current pair of states. */
    private void drawRing() {
        setForeground(tileRing(active, picked));
    }

    /**
     * The outline a tile wears for its two states, or null for neither.
     *
     * <p><b>Picked is thicker than active, and both at once is a doubled
     * ring.</b> Picked and active are independent facts — "what the next action
     * will touch" and "what is on the wallpaper" — so a tile can be either, or
     * both. With both, the accent ring stays outermost, because picked is the
     * mode the user is in and the accent is the app's one selection colour;
     * immediately inside it sits the white active ring, so the tile still
     * answers "this is the one on screen" without a tap on anything else. Two
     * strokes on two concentric radii, rather than one colour hiding the other,
     * is what keeps the doubled state readable at a glance.
     *
     * <p>The radius shrinks with the inset so the two strokes stay concentric:
     * the white ring's corner must follow the accent ring's inner edge, not the
     * tile's outer corner, or the pair pinches at the corners.
     */
    private Drawable tileRing(boolean active, boolean picked) {
        if (!active && !picked) return null;
        int radius = radiusPx;
        if (!picked) {
            return ring(activity.dp(RING_ACTIVE_DP), 0xFFFFFFFF, radius);
        }
        int thick = activity.dp(RING_PICKED_DP);
        if (!active) {
            return ring(thick, ACCENT, radius);
        }
        LayerDrawable both = new LayerDrawable(new Drawable[] {
                ring(thick, ACCENT, radius),
                ring(activity.dp(RING_ACTIVE_DP), 0xFFFFFFFF, radius - thick),
        });
        both.setLayerInset(1, thick, thick, thick, thick);
        return both;
    }

    /**
     * One stroke of a ring, rounded like the tile it sits on.
     *
     * <p>The radius is the caller's rather than the tile's, because the doubled
     * ring's inner stroke follows its own inset; a fixed radius here would
     * pinch the pair at the corners.
     */
    private GradientDrawable ring(int widthPx, int colour, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setStroke(widthPx, colour);
        return d;
    }
}
