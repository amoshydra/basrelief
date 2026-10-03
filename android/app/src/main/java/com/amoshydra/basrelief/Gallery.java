package com.amoshydra.basrelief;

import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.amoshydra.basrelief.core.store.Library;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.Playlist;
import com.amoshydra.basrelief.core.store.Playlists;
import com.amoshydra.basrelief.core.store.PlaylistStatus;
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.store.SortOrder;
import com.amoshydra.basrelief.core.store.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gallery: every photo in the app, reached two ways.
 *
 * <p><b>Browsing is a filmstrip inside the sheet, not a takeover.</b> The strip
 * is a child of the sheet's panel, directly under the grip, so switching a photo
 * is one tap while the relief keeps moving behind it. A full-screen list would
 * hide the product at exactly the moment the user is choosing it, and choosing a
 * framing is the whole reason the studio and the gallery share a screen at all.
 *
 * <p><b>Gallery owns the content; the studio owns the seat.</b> The strip used
 * to float over the render, a sibling of the sheet in the root frame, positioned
 * by a {@code bottomMargin} recomputed from the tallest thing beneath it on
 * every layout pass. That machinery is gone: {@link MainActivity} places the
 * strip by calling {@link #installStrip} with the panel and the index it wants,
 * and this class rebuilds into that one seat. There is exactly one place the
 * strip can be, so there is no offset to keep in step with an animated sheet,
 * no global layout listener, and no insets to carry — and a strip inside the
 * panel cannot be positioned by a post-layout transform over the
 * {@code GLSurfaceView}, which is b/7179570.
 *
 * <p><b>The grid is a second surface, not the gallery.</b> It is the full-screen
 * takeover the filmstrip exists to avoid, and it earns that by being for photos
 * with names on them: picking several, renaming one, deleting one. A 46px strip
 * cannot carry a menu, so the grid is one tap away rather than the other way
 * round.
 *
 * <p><b>A depth chip says present or absent, never how much.</b> The percentage
 * is the Depth slider's value, and a second control displaying it goes stale the
 * moment the slider moves. What the chip answers is the one question a
 * photograph cannot answer by looking at it: can this one stand out of the plane
 * at all.
 *
 * <p><b>The active photograph and the picked set are two facts, drawn as three
 * marks.</b> Active is a persistent white ring, because it answers "what is on
 * the wallpaper right now" and must survive every other state. Picked is a
 * thicker accent ring, an accent tint and a checkmark badge, because it answers
 * "what will the next action touch" and is a mode the user can leave. They
 * compose: a picked photograph can also be the one on screen, and then the
 * accent ring stays outermost and a white ring sits immediately inside it, so
 * both facts are readable at once instead of one ring having to stand for two.
 * Drawing both with one ring was how the previous version made the question
 * unanswerable.
 *
 * <p><b>Every ring is a foreground, and none is a background.</b> A ring drawn
 * as the background is painted before the bitmap, so the bitmap covers it — and
 * the previous padding did not save the corners: {@code clipToOutline} clipped
 * the bitmap to the background drawable's outline, which is the ring's
 * <i>outer</i> edge, so at the corners the photo reached over the stroke's inner
 * half and the white ring came out cut off at all four corners. Drawing the ring
 * over the photograph is also what the mockup does: its {@code border} is
 * painted on top of the background, not under it.
 *
 * <p><b>Rows and cells are built by hand</b> because there is no AndroidX
 * anywhere in this app, and that is a deliberate constraint rather than an
 * accident: it is what keeps the APK free of third-party runtime code, and the
 * cost is a few dozen lines of layout code.
 *
 * <p><b>Nothing is deleted without asking</b>, and deleting the <i>active</i> row
 * clears the selection in the same write. The index is the only place the
 * selection is recorded, so a pointer left naming a deleted row is a wallpaper
 * showing a photograph that no longer exists.
 */
final class Gallery {

    /** Thumb size in the filmstrip: identifiable, not choosable. Hence the grid. */
    private static final int THUMB_DP = 46;
    private static final int GRID_COLUMNS = 3;
    private static final int GRID_GAP_DP = 8;
    private static final int GRID_PAD_DP = 14;
    /**
     * The width of one grid cell's picture, in pixels, computed once per grid.
     *
     * <p>SQUARE is what the mockup draws, and every way of getting it from the layout
     * is worse than arithmetic. A fixed 96dp height makes every photograph a different
     * shape on every phone but one. Asking a container to re-height its children means
     * trusting a measure that a ScrollView may have run before the width was final --
     * which is how this ended up with rows of 1032, 1356 and 852 pixels side by side,
     * and blank spacers squared into empty 428px boxes.
     *
     * <p>So the column is arithmetic: the panel is MATCH_PARENT, so its width is the
     * screen's, and three weighted columns sharing the row minus three gaps is a sum
     * with one answer. Uniform by construction, on any screen, with no second
     * measure pass and nothing to get wrong later.
     */
    private int cellPx;

    /**
     * The last resort when the screen is too narrow to divide three ways. 96dp is
     * the height the grid used to have for every cell, so it is the one number here
     * that has been seen.
     */
    private static final int CELL_DP_FALLBACK = 96;
    /** The depth chip. A dot, because a chip that needs reading is a label. */
    private static final int CHIP_DP = 10;
    /**
     * `.thumb`/`.cell`'s 11px radius. The photograph tiles get it passed to
     * {@link PhotoTile}; the two glyph tiles here carry it themselves.
     */
    private static final int TILE_RADIUS_DP = 11;
    /** The row menu's drawn plate: the mockup's 19dp mark, in the tile's corner. */
    private static final int MENU_PLATE_DP = 19;
    /**
     * The row menu's touch target.
     *
     * <p>48dp is the platform's minimum and the plate is not it: a 19dp control
     * is aimed at rather than tapped, and this is the only route to Rename and
     * Delete. The plate stays the mockup's size; this target extends down and
     * towards the tile's middle, clear of the depth chip in the opposite
     * corner.
     */
    private static final int MENU_TARGET_DP = 48;
    /** --dim: a header entry that is a reading rather than an action. */
    private static final int DIM = 0x8CFFFFFF;

    private final MainActivity activity;
    private final FrameLayout root;
    /** The strip. Null when the gallery is not up. */
    private View strip;
    /**
     * Where the strip lives, told by the studio rather than decided here.
     *
     * <p>{@link #installStrip} receives the sheet and the index it wants, and a
     * rebuild — a new sort order, an import, a delete — re-adds into that same
     * seat. The studio changes the seat and every rebuild follows it;
     * this class never moves the strip on its own, and there is no offset to keep
     * in step with an animated sheet because a child of the panel moves with it.
     */
    private LinearLayout stripHost;
    private int stripIndex = -1;

    /** The grid. Null when it is not up. */
    private View grid;

    /**
     * The grid header's count of running playlists, kept so it can be refreshed
     * in place when the Playlists dialog closes.
     *
     * <p>Null while the grid is down. It exists as a field because the answer can
     * change without the grid being rebuilt: the Playlists screen is a dialog,
     * its writes do not rebuild this view, and rebuilding the grid to update one
     * line would drop every thumbnail and decode it again — the flash
     * {@code b426298} removed. See {@link #refreshRotatingLine}.
     */
    private TextView rotatingLine;

    /**
     * The grid's scroll container, kept so an open row menu can be dismissed
     * when the grid moves under it.
     *
     * <p>The menu is anchored at the pixel the finger was on; a scroll leaves
     * that pixel pointing at a different photograph. The popup's outside-touch
     * dismissal covers a finger, but not a programmatic or accessibility
     * scroll, and the scroll listener is the half that does.
     *
     * <p>Nulled with {@link #grid}, so a listener can never address a detached
     * scroll view.
     */
    private View gridScroller;

    /**
     * The photos picked for a many-at-once action, in the order they were picked.
     *
     * <p>Insertion-ordered rather than a sorted set, because the order is the
     * user's and re-sorting a selection is its own surprise. Deliberately not
     * persisted: it is a gesture in progress, and a selection that survived a
     * restart would name photographs the user has forgotten picking.
     *
     * <p>The set is also the mode. Empty means browsing, where a tap uses the
     * photograph; non-empty means picking, where a tap toggles membership and
     * must not touch the wallpaper. There is no second boolean that could
     * disagree with the marks on screen, and clearing the set is the one way to
     * end the mode.
     */
    private final Set<String> picked = new LinkedHashSet<String>();

    /**
     * Each grid cell's picked decoration, by item id.
     *
     * <p>A pick changes one cell's wash, badge and description — and the count
     * line and two foot actions. Rebuilding the grid to draw that re-created
     * every cell and re-ran every thumbnail decode, which is the whole-gallery
     * flash this map exists to remove. The wash and badge are built with every
     * cell and only their visibility changes, so a toggle is a visibility write
     * on views that already exist.
     *
     * <p>Cleared and refilled by each build, because a mark addresses views that
     * must be in the hierarchy; an entry that outlived its cell would toggle a
     * removed view while the cell on screen stayed stale.
     */
    private final Map<String, PickedCell> cellMarks = new HashMap<String, PickedCell>();

    /**
     * Each strip thumbnail's view, by item id, so a selection can move two rings
     * without rebuilding the strip — which would drop every thumbnail and decode
     * it again. The entry holds no active flag: the ring is the foreground,
     * drawn on demand by {@link #drawStripRing}.
     */
    private final Map<String, PhotoTile> stripTiles = new HashMap<String, PhotoTile>();

    /**
     * The strip's row: every photograph's tile, then the add and grid tiles.
     *
     * <p>Kept because under "Recent" a selection genuinely reorders the strip,
     * and the row is where that one position is changed. The tile view and the
     * bitmap already decoded into it move together; rebuilding the row instead
     * would destroy every thumbnail and decode it again, which is the whole-strip
     * flash this map and this field exist to avoid.
     */
    private LinearLayout stripRow;

    /**
     * The grid's count/hint line and its two selection actions.
     *
     * <p>They are part of the picked state and must change with it, so they are
     * found once at build time instead of re-read through the hierarchy on every
     * toggle.
     */
    private TextView selectionLineView;
    private final List<Button> selectionActions = new ArrayList<Button>();

    /**
     * One grid cell's picked mark: the cell whose description names the state,
     * and the tile that draws it.
     *
     * <p>The tile is here because the ring depends on <i>both</i> states: a pick
     * toggled on the active cell has to become the doubled ring, and a pick
     * toggled off has to fall back to the white one. Writing the wash and the
     * badge alone would leave the outline claiming a selection that has just
     * been cleared, which is why {@link PhotoTile#setPicked} is one write.
     */
    private static final class PickedCell {
        final LibraryItem item;
        final View cell;
        final PhotoTile tile;
        final boolean active;

        PickedCell(LibraryItem item, View cell, PhotoTile tile, boolean active) {
            this.item = item;
            this.cell = cell;
            this.tile = tile;
            this.active = active;
        }
    }

    /**
     * Where a copied set of settings came from, or null if nothing has been
     * copied this session.
     *
     * <p>Held in memory and never written. A stored clipboard would be a value a
     * later session reads back and pastes from without the user having chosen a
     * source, which is a stored value nothing on screen justifies.
     */
    private String copySourceId;

    /**
     * Which surface a copy reads and a paste writes.
     *
     * <p>Defaults to {@link Surface#HOME} rather than being asked for lazily, so a
     * caller that never mentions a surface still gets a copy that names the one it
     * took. The sheet owns the Home/Lock segment and tells this; guessing here
     * would paste over a lock screen the user had tuned, from a control whose
     * label never mentioned a surface.
     */
    private Surface surface = Surface.HOME;

    /**
     * Launches the system picker for a new photo, supplied by the studio.
     *
     * <p>An interface rather than a direct call because the import path — the
     * request code, the decode, the write to the index — belongs to the activity
     * that owns {@code onActivityResult}, and this class is not that activity.
     * Duplicating the request code would be a second copy of a value that has to
     * match, which is how a picked photograph gets read as a depth map.
     */
    interface PhotoPicker {
        void pickPhoto();
    }

    private PhotoPicker photoPicker;

    /**
     * Opens the playlists screen, supplied by whoever builds it.
     *
     * <p>Same reason as {@link PhotoPicker}: a route to a screen this class does
     * not own, so the owner installs it rather than this class reaching into
     * another one's internals.
     */
    interface PlaylistsNavigator {
        void openPlaylists();
    }

    private PlaylistsNavigator playlistsNavigator;

    Gallery(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        // The Playlists screen is a dialog in its own window, so closing it is
        // the one moment the gallery's ambient count can be stale and no rebuild
        // will fix it. The root outlives every grid rebuild, and a focus
        // listener refreshes one line where rebuilding the grid would re-decode
        // every thumbnail.
        root.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus) refreshRotatingLine();
                    }
                });
    }

    // ------------------------------------------------------------------- setup

    /**
     * Let the studio launch the system picker on the gallery's behalf.
     *
     * <p>Add photo is the only way in (R-G2), so the gallery has to be able to
     * reach the picker. Until this is installed the add tile says so rather than
     * doing nothing: a control that looks like a button and silently does not is
     * worse than no control.
     */
    void setPhotoPicker(PhotoPicker picker) {
        this.photoPicker = picker;
    }

    /** Route the header's Playlists entry to the playlists screen. */
    void setPlaylistsNavigator(PlaylistsNavigator navigator) {
        this.playlistsNavigator = navigator;
    }

    /**
     * Which surface a copy reads and a paste writes.
     *
     * <p>Per surface on purpose — see {@link LibraryItem#copySettingsFrom} — so
     * this is told by the sheet's segment rather than decided here.
     */
    void setSurface(Surface surface) {
        this.surface = surface == null ? Surface.HOME : surface;
    }

    // ----------------------------------------------------------------- showing

    /**
     * Put the filmstrip inside the sheet, at the seat the studio names.
     *
     * <p><b>The studio owns the seat; this class owns the strip.</b> The panel is
     * the studio's, and so is the choice to put the strip directly under the grip,
     * above the fix line and the scroll — that is passed in as an index and never
     * re-derived here. What this class keeps is the content and the behaviour:
     * which photograph is ringed, what tapping a tile does, the add and grid
     * tiles. The split is one-way: the studio places the view, this class rebuilds
     * into the seat it was given and never moves it.
     *
     * <p>Idempotent: the strip is studio furniture rather than a dialog, so it is
     * installed once and stays — through the sheet opening, the sheet collapsing
     * and the grid being dismissed. Rebuilding on every call would restart every
     * thumbnail decode for nothing, and a strip that appeared and vanished would
     * be an overlay, which is the thing it exists to stop being.
     *
     * <p>Rebuilt — not patched — whenever the index changes, because the ring that
     * says which photograph is on screen is on every tile and the order may have
     * moved.
     *
     * @param host  the sheet's panel: the strip is added directly to it.
     * @param index where among the panel's children it belongs. The studio
     *              decides this once and every rebuild re-uses it, so a rebuild
     *              cannot land somewhere a fresh install would not.
     */
    void installStrip(LinearLayout host, int index) {
        stripHost = host;
        stripIndex = index;
        ensureStrip();
    }

    private void ensureStrip() {
        if (strip != null || stripHost == null) return;
        if (stripIndex < 0 || stripIndex > stripHost.getChildCount()) {
            stripIndex = stripHost.getChildCount();
        }
        strip = buildStrip();
        stripHost.addView(strip, stripIndex);
    }

    /**
     * Whether the full-screen grid is up, which is the one gallery state BACK
     * should dismiss.
     *
     * <p>A query rather than the host reaching into {@link #grid}: the strip is
     * studio furniture and is always up, so "is the gallery showing" is a
     * question about the grid specifically. Exposing the field instead would let
     * the host silently keep a second copy of this class's state, and the two
     * would disagree the moment this class changed when it builds the grid.
     */
    boolean isGridShowing() {
        return grid != null;
    }

    /**
     * The strip, so the studio can treat it as sheet content.
     *
     * <p>The studio fades the sheet's contents as it collapses, and the strip is
     * part of that content even though this class owns it. A getter rather than
     * the host reaching into the field, because {@link #refreshStrip} can replace
     * the view: the host's next fade reads whichever strip is current, instead of
     * holding an alpha target that may have been unparented.
     */
    View stripView() {
        return strip;
    }

    /** Re-read the index and put the strip back, in the seat the studio gave. */
    private void refreshStrip() {
        if (strip == null || stripHost == null) return;
        stripHost.removeView(strip);
        strip = null;
        stripRow = null;
        ensureStrip();
    }

    /**
     * Take the gallery down: the strip, the grid, and any half-finished
     * selection.
     *
     * <p>Laid out by the panel it lives in, so there is no offset, listener or
     * inset to unwind here — removing the view is the whole teardown.
     */
    void dismiss() {
        if (strip != null) {
            if (stripHost != null) stripHost.removeView(strip);
            strip = null;
        }
        stripTiles.clear();
        stripRow = null;
        dismissGrid();
        picked.clear();
        copySourceId = null;
    }

    // ------------------------------------------------------------- the filmstrip

    private View buildStrip() {
        stripTiles.clear();
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        Library lib = state.library;
        String activeId = lib.activeId();

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = activity.dp(GRID_PAD_DP);
        row.setPadding(pad, activity.dp(6), pad, activity.dp(6));
        // Held so a selection under "Recent" can move one tile within it instead
        // of the strip being rebuilt. Replaced with every build, and dropped by
        // refreshStrip/dismiss, so it can never address a detached row.
        stripRow = row;

        for (LibraryItem item : lib.sorted()) {
            row.addView(stripThumb(item, item.id.equals(activeId)));
        }
        // The add tile first, then the grid. Add photo is the only way in, and it
        // should not be something a long library pushes off the end of a row that
        // scrolls; the grid after it, because a grid with no way in is a dead end
        // for the photographs a strip cannot show.
        row.addView(glyphTile(activity.getString(R.string.add_photo_glyph),
                activity.getString(R.string.gallery_add_photo), true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addPhoto();
                    }
                }));
        row.addView(glyphTile(activity.getString(R.string.gallery_all_photos),
                activity.getString(R.string.gallery_more_photos), false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showGrid();
                    }
                }));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.WRAP_CONTENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        // The strip IS the scroll view. There is no host FrameLayout, no insets
        // listener and no bottom margin any more: as a child of the sheet it is
        // laid out with the panel, so its position is the panel's position and
        // there is nothing to recompute per frame. MATCH_PARENT so the row can be
        // dragged across the full width; the row carries its own side padding, so
        // the first and last tiles do not touch the panel's edges.
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * One thumbnail, ringed when it is what the wallpaper is showing.
     *
     * <p>A ring rather than a tick: a tick claims completion and this claims
     * identity. There is deliberately no depth chip at this size — nowhere honest
     * to put one, and a mark that cannot be read is the same stale second control
     * the grid's chip would be.
     */
    private View stripThumb(final LibraryItem item, boolean isActive) {
        final int size = activity.dp(THUMB_DP);
        PhotoTile tile = new PhotoTile(activity, size, activity.dp(TILE_RADIUS_DP));
        // The one ring this size draws. The pick marks exist but are never
        // shown here: a 46dp strip has no picking mode, and a hidden mark costs
        // nothing; see PhotoTile.
        tile.setActive(isActive);
        tile.show(item);
        // The name is what makes an unlabelled tile a photograph rather than a
        // colour, and it is the only thing a screen reader has to read.
        tile.setContentDescription(activity.getString(R.string.gallery_thumb_use,
                item.displayName()));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(item.id);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(activity.dp(3), 0, activity.dp(3), 0);
        tile.setLayoutParams(lp);
        stripTiles.put(item.id, tile);
        return tile;
    }

    /**
     * Redraw one strip tile's ring, in place.
     *
     * <p>The ring is the foreground, so this is one write. A missing tile is a
     * no-op: the strip may not have been installed yet, and a selection arriving
     * with no strip to update is not an error.
     *
     * @param active whether this photograph is now the one on screen
     */
    private void drawStripRing(String id, boolean active) {
        if (id == null) return;
        PhotoTile tile = stripTiles.get(id);
        if (tile == null) return;
        tile.setActive(active);
    }

    /**
     * Move one photograph's tile to wherever the current order puts it, in place.
     *
     * <p>Under "Recent" the selection is the sort key, so choosing a photograph
     * changes the order — the row genuinely moves, which is why the old code
     * rebuilt it through {@link #refreshStrip}. That rebuild threw away every
     * tile and re-decoded every thumbnail, so a reorder of one photograph
     * flashed the whole strip. Here the tile keeps its view and the bitmap
     * already decoded into it; only its position among the row's children
     * changes, which is exactly the difference the user can see.
     *
     * <p>The destination comes from the index rather than from "move it to the
     * front". The two agree today, because selecting stamps a fresh
     * {@code lastUsedAt} and the comparator sorts on it first — but a hard-coded
     * front insert is a second copy of the sort rule, and the index is the only
     * authority on order. An id that is not in the index, or a tile the strip
     * has not built, is a no-op: this is only ever reached for a selection the
     * current index already names.
     *
     * @param sorted the index's post-write order, as returned by
     *               {@link LibraryStore#select}
     */
    private void moveStripTile(String id, List<LibraryItem> sorted) {
        if (stripRow == null || id == null) return;
        PhotoTile tile = stripTiles.get(id);
        if (tile == null) return;
        int at = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).id.equals(id)) {
                at = i;
                break;
            }
        }
        if (at < 0 || stripRow.indexOfChild(tile) == at) return;
        stripRow.removeView(tile);
        stripRow.addView(tile, at);
    }

    /**
     * A tile that is not a photograph: the add tile's glyph, or the grid's label.
     *
     * <p>The add tile is dashed because that is the shape language this app
     * already uses for "not a thing yet" — see {@link DashBorder}, which the fix
     * line is built from. Reusing it is why the add tile reads as an invitation
     * rather than as one more photograph.
     */
    private View glyphTile(String label, String description, boolean dashed,
                           View.OnClickListener listener) {
        int size = activity.dp(THUMB_DP);
        TextView tile = new TextView(activity);
        tile.setText(label);
        tile.setTextColor(0xCCFFFFFF);
        tile.setTextSize(dashed ? 19 : 11);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(dashed
                ? new DashBorder(activity.dp(1), activity.dp(4), 0x57FFFFFF, activity.dp(11))
                : plain(0x22FFFFFF, activity.dp(11)));
        tile.setContentDescription(description);
        tile.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(activity.dp(3), 0, activity.dp(3), 0);
        tile.setLayoutParams(lp);
        return tile;
    }

    private GradientDrawable plain(int fill, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    // ------------------------------------------------------------- the strip's seat

    /* The strip used to own a whole position section here -- a measured bottom
     * margin, a global layout listener, and top/bottom insets to keep it off the
     * status bar. All of it existed because the strip floated as a sibling over
     * the render. It is a child of the panel now, so the panel's layout is the
     * strip's layout and the whole section is gone rather than left dead. */

    private int bottomInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    private int topInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.systemBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    // ---------------------------------------------------------------- the grid

    private void showGrid() {
        if (grid != null) return;
        // Both surfaces are one list read twice. The grid is rebuilt from the
        // index right below, so the strip is brought along rather than left
        // showing an order or a row the grid no longer agrees with.
        refreshStrip();
        grid = buildGrid();
        root.addView(grid);
        // The grid is a full-screen takeover, so "added last" is not good enough:
        // anything that re-adds itself afterwards -- the strip, on a selection
        // change -- would land on top of it. bringChildToFront states the intent
        // instead of relying on the order two unrelated methods happened to run in.
        root.bringChildToFront(grid);
        grid.requestApplyInsets();
    }

    /**
     * Take the grid down, leaving the strip.
     *
     * <p>Package-visible because the system BACK handler on the Activity is where
     * the decision to call it lives (the gallery is a sibling view, not a dialog,
     * so back does not reach this class on its own). It is deliberately not
     * {@link #dismiss}, which also removes the strip: the strip is studio
     * furniture that survives the grid by design, and back out of the grid should
     * return to the filmstrip rather than take the filmstrip with it.
     */
    void dismissGrid() {
        if (grid == null) return;
        // Leaving the grid ends the picking gesture rather than just hiding it.
        // The set is the mode (see {@link #picked}), so a selection that
        // outlived its screen would come back with checkmarks nobody remembers
        // making — and a mode the user cannot see is a mode they cannot leave.
        picked.clear();
        cellMarks.clear();
        selectionActions.clear();
        selectionLineView = null;
        root.removeView(grid);
        grid = null;
        gridScroller = null;
        rotatingLine = null;
    }

    /**
     * The grid: a header, the photographs, and the many-at-once actions.
     *
     * <p>Full screen and near-opaque, which is the takeover R-G1 is about — and
     * which this surface earns, because it is for photographs with names on them.
     * Browsing does not happen here; picking several, renaming one and deleting
     * one do, and none of them fit in a 46px tile.
     */
    private View buildGrid() {
        // The marks and the selection controls are the views the build below is
        // about to create; anything left from the previous grid addresses views
        // that are no longer on screen, so the maps start empty on every build.
        cellMarks.clear();
        selectionActions.clear();
        selectionLineView = null;
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        final Library lib = state.library;
        final List<LibraryItem> rows = lib.sorted();
        String activeId = lib.activeId();

        final int pad = activity.dp(GRID_PAD_DP);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF7080A0D);
        panel.setPadding(pad, pad, pad, pad);
        panel.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // Out of the system bars rather than assuming they are hidden.
                // The studio asks for immersive mode and does not always get it,
                // and a header laid out under the status bar looks fine and is
                // completely dead: the bar takes the touch before the view sees it.
                v.setPadding(pad, pad + topInset(insets), pad, pad + bottomInset(insets));
                return insets;
            }
        });
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- header: title, sort, playlists
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(activity.getString(R.string.gallery_title), 16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        header.addView(headerLink(activity.getString(R.string.gallery_sort)
                + " · " + lib.sort().label(), DIM, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu();
            }
        }));
        header.addView(headerLink(activity.getString(R.string.gallery_playlists),
                PhotoTile.ACCENT, true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPlaylists();
                    }
                }));
        panel.addView(header);
        // The ambient count of what is running, outside the playlists screens.
        // Always built, refreshed in place: the grid is not rebuilt when the
        // Playlists dialog closes, and rebuilding it to change one line would
        // re-decode every thumbnail. See refreshRotatingLine.
        rotatingLine = buildRotatingLine();
        panel.addView(rotatingLine);
        refreshRotatingLine();

        // The count and the hint share one line: they are the same question at two
        // moments, and two lines would be one too many on a screen this size.
        // Absent with no photographs: "tap a photo" is a hint about something that
        // is not there, and the empty state below already answers the question.
        if (!rows.isEmpty()) {
            panel.addView(selectionLine());
        }

        // ---- the photographs
        //
        // cellPx is assigned HERE, before any row is built, because every cell reads
        // it as both its width and its height. It was declared and read but never
        // assigned: an earlier edit that put this line in aborted on a later mismatch,
        // so the assignment silently never happened. cellPx stayed 0, every cell was
        // 0 tall, and the grid rendered as an empty 2400px scroll -- which looks
        // exactly like an empty gallery rather than like a bug.
        //
        // From the SCREEN rather than from panel.getWidth(): this panel was created
        // moments ago and has never been laid out, so its width is a question about
        // the future. It is MATCH_PARENT, so the screen's width is not an
        // approximation of what it will be -- it is what it will be.
        //
        // The gaps are counted BETWEEN the columns -- two of them for three
        // columns -- because the stylesheet's `gap` is not an inset on the first
        // column. Counting three put every cell 8dp right of the header and hint
        // above it, and left the left edge of the grid ragged against them.
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        cellPx = (screen - 2 * pad
                - (GRID_COLUMNS - 1) * activity.dp(GRID_GAP_DP)) / GRID_COLUMNS;
        if (cellPx <= 0) {
            cellPx = activity.dp(CELL_DP_FALLBACK);
        }

        ScrollView scroll = new ScrollView(activity);
        // Held so an open row menu can close when this scrolls; see gridScroller.
        gridScroller = scroll;
        LinearLayout cells = new LinearLayout(activity);
        cells.setOrientation(LinearLayout.VERTICAL);
        // .cells{gap:8px} is a row gap as much as a column one. The rows used to
        // stack with no gap at all: the horizontal 8dp read as the grid's only
        // gutter, and a row of photographs touched the row below it.
        boolean first = true;
        if (rows.isEmpty()) {
            // An empty gallery is legitimate now -- the last row can be deleted
            // and nothing re-seeds it -- so the cell area must say so rather than
            // draw the lone ＋ of a grid with nothing in it. The placeholder is
            // the fix line's shape: name the condition, then the one action that
            // answers it.
            cells.addView(emptyGallery());
        } else {
            // The add tile is a CELL in this flow, not a row of its own. It takes
            // the column after the last photograph, so it is a sibling of the
            // cells -- same width arithmetic, same gutter, same row gap -- and
            // {@code addCellRow} is gone rather than kept beside this.
            int count = rows.size() + 1;
            for (int start = 0; start < count; start += GRID_COLUMNS) {
                cells.addView(cellRow(rows, start, activeId), rowSpacing(first));
                first = false;
            }
        }
        scroll.addView(cells, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- the many-at-once actions
        //
        // The two actions that act on the picked set are live only while there
        // is a set. A disabled button is visibly unavailable, which is honest:
        // nothing is selected to paste onto, and promising the action and then
        // toasting is the silently-does-nothing failure this design removes.
        // Copy settings stays live because it acts on a source, not on the
        // selection; Done stays live because leaving the grid must never be
        // gated on picking. Set as wallpaper lives on the studio sheet's foot.
        panel.addView(actionRow(
                compact(activity.getString(R.string.gallery_copy_settings),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showCopySourcePicker();
                            }
                        }),
                selectionAction(
                        activity.getString(R.string.gallery_paste_onto_selected),
                        R.string.gallery_paste_needs_selection,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pasteOnto(targetsFor(null));
                            }
                        })));
        panel.addView(actionRow(
                selectionAction(
                        activity.getString(R.string.gallery_add_to_playlist),
                        R.string.gallery_playlist_needs_selection,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showPlaylistPicker(null);
                            }
                        }),
                doneButton()));
        return panel;
    }

    /**
     * The grid header's count of running playlists — the ambient proof, outside
     * the playlists screens, that the feature is doing something.
     *
     * <p>It is built always and shown only when the count is not zero, because
     * the alternative — adding and removing it as the answer changes — moves the
     * selection line and the grid below it under the user's thumb.
     */
    private TextView buildRotatingLine() {
        TextView status = text("", 12);
        status.setTextColor(PhotoTile.ACCENT);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(0, activity.dp(2), 0, activity.dp(4));
        // A route to the screen that owns it, so the count is a way in rather
        // than a fact with nowhere to go.
        status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlaylists();
            }
        });
        return status;
    }

    /**
     * Re-read the running count and show or hide the header line.
     *
     * <p>Read through {@link PlaylistStatus#rotatingCount}, the one resolution
     * every playlist surface uses, so the gallery cannot claim a rotation the
     * Playlists screen does not show. A no-op while the grid is down: there is
     * nothing to refresh, and the next build computes the line from scratch.
     *
     * <p>Called on window focus because the Playlists screen is a dialog in its
     * own window — dismissing it returns focus here, and no other callback
     * covers that moment without rebuilding the grid.
     */
    private void refreshRotatingLine() {
        if (rotatingLine == null) return;
        Playlists lists = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        long now = System.currentTimeMillis();
        PlaylistStatus status = PlaylistStatus.of(lists, lib, now);
        if (!status.isRotating()) {
            rotatingLine.setVisibility(View.GONE);
            return;
        }
        if (status.position > 0) {
            // The armed playlist owns the photo on screen: the count answers
            // "is anything rotating" and the position is not needed here.
            int rotating = PlaylistStatus.rotatingCount(lists);
            rotatingLine.setText(activity.getResources().getQuantityString(
                    R.plurals.gallery_rotating_playlists, rotating, rotating));
        } else {
            // Armed, and the photo on screen is not one of its own. A manual tap
            // is legal -- the index owns the pointer and a playlist has no cursor
            // to disagree with it -- but the playlist will take the screen back
            // at its next trigger. The count alone cannot say that, so the line
            // names the playlist and when it moves.
            rotatingLine.setText(activity.getString(R.string.gallery_playlist_armed,
                    status.playlist.displayName(),
                    PlaylistScreen.nextChangeLabel(activity, status.playlist, status, now)));
        }
        rotatingLine.setVisibility(View.VISIBLE);
    }

    /**
     * The 8dp a row leaves above it, except the first: {@code .cells{gap:8px}}.
     *
     * <p>A margin rather than a spacer row, so the gap belongs to the row and
     * cannot be dropped when a row is rebuilt.
     */
    private LinearLayout.LayoutParams rowSpacing(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!first) lp.topMargin = activity.dp(GRID_GAP_DP);
        return lp;
    }

    /**
     * One row of {@link #GRID_COLUMNS} slots: photographs, then the add tile in
     * the next free column, the rest padded with blanks.
     *
     * <p>Fixed rows rather than a wrapping container, because a grid that measures
     * itself against variable-width children produces rows of differing heights,
     * and a gallery whose photographs are not on a baseline reads as broken
     * before it is read.
     *
     * <p>The first column has no left margin: {@code .cells{gap:8px}} puts the
     * gutter <i>between</i> columns, so the grid's first photograph starts at the
     * panel's own 14dp edge, in the same column as the header and the hint line.
     * Every column used to carry the 8dp, which indented the whole grid by one
     * gap and made it ragged against the text above it.
     *
     * <p>The add tile is handled here, at index {@code rows.size()}, because a
     * row of its own put it at the left edge under a row of zero-height blanks --
     * floating rather than sitting beside the last photograph.
     */
    private LinearLayout cellRow(List<LibraryItem> rows, int start, String activeId) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < GRID_COLUMNS; i++) {
            int at = start + i;
            if (at < rows.size()) {
                LibraryItem item = rows.get(at);
                line.addView(cell(item, item.id.equals(activeId), i == 0));
            } else if (at == rows.size()) {
                // Exactly the slot the next photograph would take.
                line.addView(addCell(i == 0));
            } else {
                // A spacer, and deliberately zero-height: it exists only to hold a
                // column open so the last row keeps the first row's column widths,
                // and giving it a height makes an empty box the size of a photograph.
                View blank = new View(activity);
                LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(
                        cellPx, 0);
                if (i > 0) blankLp.leftMargin = activity.dp(GRID_GAP_DP);
                blank.setLayoutParams(blankLp);
                line.addView(blank);
            }
        }
        return line;
    }

    /**
     * The one tile that is not a photograph: the next free grid slot.
     *
     * <p>Geometry is the cells' own, from the same {@link #cellPx} arithmetic the
     * photograph columns use, with the same gutter rule and the same row spacing
     * via the row it is in. That is what makes it a sibling: it occupies a grid
     * slot rather than a position computed separately from one.
     */
    private View addCell(boolean firstInRow) {
        TextView add = text(activity.getString(R.string.add_photo_glyph), 22);
        add.setTextColor(0x99FFFFFF);
        add.setGravity(Gravity.CENTER);
        add.setBackground(new DashBorder(activity.dp(1), activity.dp(4), 0x57FFFFFF, activity.dp(11)));
        add.setContentDescription(activity.getString(R.string.gallery_add_photo));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhoto();
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(cellPx, cellPx);
        // The first column starts at the panel's own edge; every other one
        // carries the gutter, exactly as a photograph in that column does.
        if (!firstInRow) addLp.leftMargin = activity.dp(GRID_GAP_DP);
        add.setLayoutParams(addLp);
        return add;
    }

    /**
     * The grid's empty state: a short line and the action that answers it.
     *
     * <p>An empty gallery is legitimate now — the last row can be deleted and
     * nothing re-seeds it — so the cell area must say what it is rather than show
     * the lone ＋ of a grid with nothing in it. The wording is the fix line's
     * shape: name the condition, then offer the one action, because that is how
     * this app already says "there is nothing here yet".
     *
     * <p>The filmstrip keeps its own ＋, so the door is reachable at the peek;
     * this is the grid's version of the same door, not a second route.
     */
    private View emptyGallery() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, activity.dp(40), 0, activity.dp(40));
        TextView line = text(activity.getString(R.string.gallery_empty), 14);
        line.setTextColor(0x99FFFFFF);
        line.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(line);
        Button add = ModalSheet.button(activity,
                activity.getString(R.string.gallery_add_photo), false, true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhoto();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = activity.dp(12);
        box.addView(add, lp);
        return box;
    }

    /**
     * Whether this photograph can produce relief at all.
     *
     * <p>The bundled demo counts, and it is the one row whose answer cannot come
     * from the index: it has no file, so {@code hasDepth()} is false, but it ships
     * with a map in {@code res/raw} and renders in relief. Reading the index alone
     * would put an absent chip on the photograph that is on screen, which is the
     * one chip nobody is allowed to get wrong.
     */
    private boolean canStandOut(LibraryItem item) {
        return item.isBundled() || item.hasDepth();
    }

    /**
     * One cell: the picture, the active ring, the picked ring, wash and badge,
     * the depth chip, and the menu.
     *
     * <p><b>Tap uses the photograph; a hold starts picking; a tap while picking
     * toggles.</b> While picking, a tap deliberately cannot change the
     * wallpaper: two answers on one gesture is how a user trying to unpick a
     * photograph ends up replacing the wallpaper instead. The marks make the
     * mode visible — a white ring for what is on screen, a thicker accent ring
     * plus wash and checkmark for what is picked — so "which mode am I in" is
     * answered by the grid rather than remembered.
     */
    private View cell(final LibraryItem item, final boolean isActive,
                      boolean firstInRow) {
        final boolean isPicked = picked.contains(item.id);
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        // Fixed, not weighted: cellPx is the column width the buildGrid arithmetic
        // promised, and a weighted cell would take the gutter back out of the
        // column it belongs to. The first column carries no gutter; see cellRow.
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(cellPx,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!firstInRow) cellLp.leftMargin = activity.dp(GRID_GAP_DP);
        cell.setLayoutParams(cellLp);

        // The tile is the picture. It owns the rounded fill, the bitmap, the
        // clip and every ring and pick mark; the grid adds only its own two
        // controls on top. See PhotoTile for why the marks live there.
        final PhotoTile tile = new PhotoTile(activity, cellPx, activity.dp(TILE_RADIUS_DP));
        tile.setActive(isActive);
        tile.setPicked(isPicked);
        // Square by arithmetic, not by measurement: cellPx is the column's width, so
        // using it as the height is what makes the cell a square. MATCH_PARENT width
        // and the computed height, because the two have to agree and only one of them
        // was computed.
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cellPx));
        final View chip = depthChip(item);
        tile.addView(chip, chipParams());
        tile.addView(menuButton(item), menuParams());
        // The embedded-depth answer comes out of the same read that decodes the
        // thumbnail; see PhotoTile.show. The bundled demo's map is the separate
        // res/raw/depth.png, so canStandOut has already shown the chip for it.
        final boolean hasFile = item.hasDepth();
        tile.show(item, new Io.EmbeddedDepthListener() {
            @Override
            public void onFound(boolean embedded) {
                if (embedded && !hasFile) chip.setVisibility(View.VISIBLE);
            }
        });
        cell.addView(tile);
        // No name under the cell. The mockup draws none, and with square cells a
        // caption would be a quarter of the cell's height spent on a label -- the
        // depth chip and the two selection marks already say which photograph
        // this is.

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!picked.isEmpty()) {
                    togglePick(item);
                    return;
                }
                select(item.id);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // A hold only ever adds. It is what starts the mode; a tap is
                // what toggles, so a hold on something already picked does not
                // undo the very thing the user is holding to keep.
                if (picked.add(item.id)) {
                    drawPicked(item.id, true);
                    refreshSelectionUi();
                }
                return true;
            }
        });
        cellMarks.put(item.id, new PickedCell(item, cell, tile, isActive));
        cell.setContentDescription(stateDescription(item, isActive, isPicked));
        return cell;
    }

    /**
     * Toggle one row's membership in the pick set, and redraw the marks.
     *
     * <p>Patched, not rebuilt: a pick changes one cell's wash and badge, the
     * count line, and whether the two foot actions are live — values already on
     * screen. The grid used to be thrown away and rebuilt, which re-created
     * every cell and re-decoded every thumbnail, and that is the whole-gallery
     * flash the user reported. All of it is derived from the same {@link #picked}
     * set here, so the four writes cannot disagree about it.
     *
     * <p>The bundled row is a member like any other. It used to be excluded
     * because the data layer refused to delete it and it had no settings of its
     * own — a checkmark would have promised actions that did not exist. Both
     * refusals are gone: it is an ordinary preloaded photo, and the marks now
     * match what the actions will really do.
     */
    private void togglePick(LibraryItem item) {
        if (!picked.remove(item.id)) picked.add(item.id);
        drawPicked(item.id, picked.contains(item.id));
        refreshSelectionUi();
    }

    /**
     * Show or hide one cell's picked mark, and say the new state to a screen
     * reader.
     *
     * <p>The outline is redrawn here too, and it is not optional: the ring
     * depends on picked and active together, so toggling a pick on the active
     * cell has to turn the white ring into the doubled one, and unpicking it has
     * to put the white ring back. Leaving the outline alone would let the wash
     * say "not picked" while the ring still said "picked".
     *
     * <p>A no-op without a mark: the grid may be down, and a toggle that raced
     * its own dismissal has nothing left to draw on.
     */
    private void drawPicked(String id, boolean on) {
        PickedCell mark = cellMarks.get(id);
        if (mark == null) return;
        mark.tile.setPicked(on);
        mark.cell.setContentDescription(stateDescription(mark.item, mark.active, on));
    }

    /**
     * How the grid's count line and its two selection actions follow the pick
     * set.
     *
     * <p>One method because they are one state: the line reports how many are
     * picked and the actions are live exactly while that count is not zero.
     * Writing them at the toggle separately is how a live Paste ends up beside
     * "Select photos to paste onto".
     */
    private void refreshSelectionUi() {
        boolean picking = !picked.isEmpty();
        if (selectionLineView != null) {
            selectionLineView.setText(picking
                    ? activity.getString(R.string.gallery_selection_count, picked.size())
                    : activity.getString(R.string.gallery_selection_hint));
            selectionLineView.setTextColor(picking ? PhotoTile.ACCENT : 0x99FFFFFF);
        }
        for (Button b : selectionActions) refreshSelectionAction(b);
    }

    /**
     * One foot action's enabled state, dimming and description, from the pick
     * set.
     *
     * <p>A disabled action carries the string that says what would enable it,
     * and a live one goes back to its own label — the same "not available yet"
     * appearance the sheet gives a locked row.
     */
    private void refreshSelectionAction(Button b) {
        boolean live = !picked.isEmpty();
        b.setEnabled(live);
        b.setAlpha(live ? 1f : 0.4f);
        Object reason = b.getTag();
        b.setContentDescription(live || !(reason instanceof Integer)
                ? null : activity.getString((Integer) reason));
    }

    /**
     * What a screen reader should say about one cell.
     *
     * <p>Both marks are drawn as colour and a glyph, which nobody can hear, so
     * the state is spelled out. The plain case keeps the action wording, because
     * using the photograph is what a bare cell offers.
     */
    private String stateDescription(LibraryItem item, boolean isActive, boolean isPicked) {
        String name = item.displayName();
        if (isActive && isPicked) {
            return activity.getString(R.string.gallery_talk_active_selected, name);
        }
        if (isActive) return activity.getString(R.string.gallery_talk_active, name);
        if (isPicked) return activity.getString(R.string.gallery_talk_selected, name);
        return activity.getString(R.string.gallery_thumb_use, name);
    }

    /** Top-LEFT, clear of the row menu, which is top-right. */
    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(CHIP_DP), activity.dp(CHIP_DP), Gravity.TOP | Gravity.START);
        lp.setMargins(activity.dp(6), activity.dp(6), 0, 0);
        return lp;
    }

    /**
     * The row menu's corner and its touch target.
     *
     * <p>Top-RIGHT at 19dp, as the mockup draws the mark — it was top-LEFT at
     * 28dp, which is where the depth chip now sits. Two marks in one corner is
     * one of them invisible, and "which is it on this row" is a question a
     * gallery must not ask. The target is the minimum 48dp; see
     * {@link #MENU_TARGET_DP} for why the two sizes differ.
     */
    private FrameLayout.LayoutParams menuParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(MENU_TARGET_DP), activity.dp(MENU_TARGET_DP),
                Gravity.TOP | Gravity.END);
        lp.setMargins(0, activity.dp(5), activity.dp(5), 0);
        return lp;
    }

    /**
     * The chip, present or absent, carrying no number.
     *
     * <p>A percentage here would be the Depth slider's value shown a second time,
     * and it would go stale the moment the slider moved — a control displaying a
     * value the renderer does not read is the failure this whole redesign is
     * built to remove. What the chip answers is the one question a photograph
     * cannot answer by looking at it.
     */
    private View depthChip(LibraryItem item) {
        View chip = new View(activity);
        chip.setBackground(plain(PhotoTile.ACCENT, activity.dp(CHIP_DP)));
        chip.setContentDescription(activity.getString(R.string.gallery_has_depth_map));
        chip.setVisibility(canStandOut(item) ? View.VISIBLE : View.GONE);
        return chip;
    }

    /**
     * The row menu's handle, in the picture's corner.
     *
     * <p>A small target rather than a button on its own row: a full-width button
     * under every name would make a third of each cell a control, and the cell is
     * already a control. It is added after the picture, so it is above it in the
     * draw order, and it consumes its own touches — the cell's tap does not also
     * fire underneath it.
     *
     * <p><b>Anchored at the finger.</b> {@code OnClick} carries no coordinates,
     * so the DOWN point is recorded on the way past and the click hands that to
     * {@link ContextMenu#showAt}. A click that arrives with no touch at all —
     * TalkBack, a keyboard — falls back to the mark's own centre, so the menu
     * still opens beside the control rather than at the display's corner.
     */
    private View menuButton(final LibraryItem item) {
        final float[] touch = new float[2];
        final boolean[] touched = new boolean[1];
        TextView glyph = text(activity.getString(R.string.gallery_more_glyph), 12);
        glyph.setGravity(Gravity.CENTER);
        // A plate behind the glyph, because a dark mark on a dark photograph is
        // a control that is present and unreadable. 19dp, the mockup's mark;
        // the target around it is menuParams' 48dp.
        glyph.setBackground(plain(0x99000000, activity.dp(10)));

        FrameLayout target = new FrameLayout(activity);
        target.addView(glyph, new FrameLayout.LayoutParams(
                activity.dp(MENU_PLATE_DP), activity.dp(MENU_PLATE_DP),
                Gravity.TOP | Gravity.END));
        target.setContentDescription(activity.getString(R.string.gallery_more_glyph));
        target.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touch[0] = e.getRawX();
                    touch[1] = e.getRawY();
                    touched[0] = true;
                }
                return false;
            }
        });
        target.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float x = touch[0];
                float y = touch[1];
                if (!touched[0]) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    x = loc[0] + v.getWidth() / 2f;
                    y = loc[1] + v.getHeight() / 2f;
                }
                showRowMenu(item, x, y);
            }
        });
        return target;
    }

    /**
     * One row of the foot's buttons: equal shares, any count.
     *
     * <p>Buttons rather than label-and-listener pairs, because the foot is no
     * longer all pairs — {@link #doneButton} is a primary plate and the rest are
     * quiet ones — and a builder that could only take two could not hold a
     * mixed pair without a second builder drifting from this one.
     *
     * <p>{@code .btnrow{gap:8px;margin-top:9px}}: the gap goes on every button
     * after the first, and the 9dp is a top pad on the row itself so two stacked
     * rows sit 9dp apart rather than the 12 the old 6dp pad made. The buttons
     * used to be re-added with fresh {@code LayoutParams}, which silently dropped
     * the 6dp right margin {@code makeButton} had put on them, so each pair
     * shared an edge.
     */
    private LinearLayout actionRow(Button... buttons) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, activity.dp(9), 0, 0);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = activity.dp(GRID_GAP_DP);
            row.addView(buttons[i], lp);
        }
        return row;
    }

    /**
     * One line, two states: the gesture when nothing is picked, the count when
     * something is.
     *
     * <p>It changes text rather than appearing and disappearing, so the row it
     * occupies does not jump under a finger that is mid-gesture. The accent
     * colour arrives with the count, matching the checkmark badges below it, so
     * "there is a selection" is visible before the sentence is read.
     */
    private TextView selectionLine() {
        boolean picking = !picked.isEmpty();
        String s = picking
                ? activity.getString(R.string.gallery_selection_count, picked.size())
                : activity.getString(R.string.gallery_selection_hint);
        TextView line = text(s, 12);
        line.setTextColor(picking ? PhotoTile.ACCENT : 0x99FFFFFF);
        // 8 below, not 2: the line sits directly on the grid, and at 2dp the
        // hint's last pixel nearly touched the first row of photographs. 8 is
        // the grid's own gutter, so the one added line uses the grid's scale.
        line.setPadding(0, activity.dp(6), 0, activity.dp(8));
        // Kept so a toggle can rewrite it without rebuilding the grid it is in.
        selectionLineView = line;
        return line;
    }

    /**
     * A foot action that acts on the picked set and is live only while one
     * exists.
     *
     * <p>Disabled rather than hidden, deliberately: hiding re-flows the foot
     * under the finger that just picked the first photograph, and a control that
     * is absent at the moment its answer is "nothing yet" cannot teach how to
     * make it live. A dimmed, unclickable plate is the same treatment
     * {@link ModalSheet} gives a locked row, so "not available" has one
     * appearance in the app. The content description names the unlock, because
     * a disabled button is exactly the one a screen reader user cannot
     * experiment with.
     *
     * @param reasonRes the string that says what would enable this action
     */
    private Button selectionAction(String label, int reasonRes,
                                   View.OnClickListener listener) {
        Button b = compact(label, listener);
        // The reason is carried on the button itself so one update method can
        // restore either state without a parallel list to keep in step.
        b.setTag(reasonRes);
        selectionActions.add(b);
        refreshSelectionAction(b);
        return b;
    }

    /**
     * The grid's one primary button: leaving is the reason the grid is up.
     *
     * <p>Primary, not another plate, because while picking it is also the exit
     * from the mode — the visible answer to "how do I stop picking". It clears
     * the set through {@link #dismissGrid}, the same exit BACK takes, so there is
     * one way out rather than two that could disagree.
     */
    private Button doneButton() {
        Button done = ModalSheet.button(activity,
                activity.getString(R.string.action_done), true, false);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Done is the way out, not a save. Every action here wrote the
                // moment it was tapped, so there is nothing to commit and a
                // button that said Save would imply otherwise.
                dismissGrid();
            }
        });
        return done;
    }

    private void rebuildGrid() {
        if (grid == null) return;
        root.removeView(grid);
        grid = buildGrid();
        root.addView(grid);
        root.bringChildToFront(grid);
        grid.requestApplyInsets();
    }

    /**
     * The index changed: re-read it into both surfaces.
     *
     * <p>One name for it, because the strip and the grid are the same list read
     * twice and a sort change that redraws one of them leaves the other showing
     * an order the index no longer holds. The grid call no-ops when the grid is
     * down, so a strip-only session pays nothing for the seam.
     *
     * <p>Package-visible because not every write to the index happens in this
     * class: importing a photo is the activity's, since the picker and the file
     * copy are. The activity has to call this after such a write, or the strip
     * keeps showing the row the imported photo replaced — the photo is selected
     * and missing from the gallery, which looks like the import failed.
     */
    void reindex() {
        rebuildGrid();
        refreshStrip();
    }

    // ------------------------------------------------------------------ modals

    /**
     * The first moves of every modal this class opens: the app's one card, a
     * title, and a column the caller fills. One helper, because eight copies
     * of "a title over some rows" is eight places for the gap under the title
     * to drift.
     *
     * <p><b>Why a {@link ModalSheet} rather than an {@code AlertDialog}.</b>
     * Every dialog this class used to open arrived as a light-theme Material
     * card — white plate, near-black text — inside a near-black app, because
     * the platform dialog carries the activity theme and this app deliberately
     * does not flip it: a global dark theme would restyle the GL surface's
     * transparency and every platform dialog at once (see {@code
     * MainActivity.makeButton}). The app's own card is the one the apply,
     * depth, settings and playlist screens already sit on, so building here
     * means the gallery's menus agree with the screens one gesture away
     * rather than with the platform.
     *
     * <p><b>Why a Dialog is right here rather than a view.</b> The grid sits
     * over a {@code GLSurfaceView}, and a sibling view drawn over one is the
     * b/7179570 hazard — placed, attached, opaque, taking every touch and
     * absent from the display. A Dialog is its own window, so the hazard does
     * not apply, which is why every overlay in this app is one.
     *
     * <p>The 10dp under the title is the gap the other modals get from their
     * subtitle (see {@code ApplyChooser}: 3 above, 10 below). A picker with no
     * subtitle has to supply it itself, or its first row touches its title.
     */
    private LinearLayout modalBody(String title) {
        TextView head = ModalSheet.title(activity, title);
        head.setPadding(0, 0, 0, activity.dp(10));
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(head);
        return body;
    }

    /**
     * A button whose whole job is closing the modal: Cancel, Done. Ghost,
     * because it changes nothing on the screen it leaves — the same call the
     * settings screen makes for its own Done. This is the old dialogs'
     * negative button, keeping its place at the left of the pair.
     */
    private Button closeButton(String label, final ModalSheet sheet) {
        Button close = ModalSheet.button(activity, label, false, true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        return close;
    }

    /**
     * The modal's acting button — Save, Delete — primary because it is the
     * reason the modal exists.
     *
     * <p>Closes its own modal <b>before</b> acting, because some of the
     * choices here open the next modal (a rename out of the row menu), and a
     * modal left under a modal is a back press that resurrects a menu whose
     * moment has passed. The platform dialogs closed themselves only after
     * the click handler returned, so closing first is the order the user
     * already saw.
     */
    private Button commitButton(String label, final ModalSheet sheet,
                                final Runnable action) {
        Button commit = ModalSheet.button(activity, label, true, true);
        commit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
                action.run();
            }
        });
        return commit;
    }

    /**
     * One choice in a modal menu: an option row with no second line, because
     * a command's label is its whole description and an invented subtitle is
     * copy nobody asked for. The row keeps the pickers' shape, so the two
     * kinds of list read as one family rather than as two designs that happen
     * to share a card.
     */
    private LinearLayout menuOption(final ModalSheet sheet, String label,
                                    final Runnable action) {
        return ModalSheet.option(activity, label, "", false, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sheet.dismiss();
                        action.run();
                    }
                });
    }

    // ----------------------------------------------------------------- actions

    /**
     * Put a photograph on screen.
     *
     * <p>The one write a photo switch is. {@code LibraryStore.select} is the same
     * read-modify-write a playlist rotation makes, on the same key, so there is
     * never a second pointer that could disagree with this one.
     *
     * <p>Nothing here re-binds the live wallpaper, and nothing should:
     * {@code setWallpaperComponent} needs a signature|privileged permission the
     * platform refuses every ordinary app, so the engine follows the index on its
     * own and "Set as wallpaper" is the apply gesture rather than a refresh.
     *
     * <p>Also the reason the grid closes. The render is the thing the user came
     * for, and a photo chosen from a list should leave the list behind it.
     */
    private void select(String id) {
        // Read before the write: the old ring has to be lifted, and under the
        // "Recent" order the write itself moves the row.
        AppState before = AppState.load(activity.prefs, activity.getFilesDir());
        String was = before.library.activeId();
        SortOrder order = before.library.sort();
        // The returned index is the order as it was just written. Re-reading it
        // below would be a second parse of the same blob -- and a second chance
        // for the wallpaper service to write between the two reads, which would
        // move the tile to an index the photograph is no longer at.
        Library next = LibraryStore.select(activity.prefs, id);
        // The sheet repopulates itself through the guarded path, so a switch does
        // not write: refreshSliders sets syncingZoom around every setProgress
        // precisely because setProgress fires the change listener, and without the
        // guard this would rewrite the photograph just left with the values it
        // happened to be showing.
        activity.refreshStatus();
        activity.refreshSliders();
        activity.applyFiles();
        dismissGrid();
        // The strip itself is never rebuilt for a selection: under every order
        // the two rings are repainted in place, and under "Recent" the selected
        // tile is moved to its new position rather than the whole strip being
        // thrown away and its thumbnails decoded again. Rebuilding here was the
        // strip-wide flash: a reorder of one photograph re-decoded every
        // photograph. The grid closes either way, because the render is the
        // thing the user came for and a photograph chosen from a list should
        // leave the list behind it.
        if (order == SortOrder.RECENTLY_USED) {
            moveStripTile(id, next.sorted());
        }
        drawStripRing(was, false);
        drawStripRing(id, true);
    }

    private void addPhoto() {
        if (photoPicker == null) {
            // Named rather than silent. A tile that looks like a button and does
            // nothing is worse than one that says it is not ready.
            Toast.makeText(activity, R.string.toast_add_photo_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        photoPicker.pickPhoto();
    }

    private void openPlaylists() {
        if (playlistsNavigator == null) {
            Toast.makeText(activity, R.string.toast_playlists_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        playlistsNavigator.openPlaylists();
    }

    /**
     * The sort order, as the app's own list of choices.
     *
     * <p>The current order carries the option row's own dot, because a picker
     * that does not say which choice is in force asks a question the user can
     * only answer from memory — and the header link already answers it, so the
     * sheet agreeing with the header is the second place the two must not
     * disagree.
     */
    private void showSortMenu() {
        final SortOrder current = AppState.load(activity.prefs,
                activity.getFilesDir()).library.sort();
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_sort_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_sort));
        for (final SortOrder pick : SortOrder.values()) {
            body.addView(ModalSheet.option(activity, pick.label(), "",
                    pick == current, false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sheet.dismiss();
                            LibraryStore.setSort(activity.prefs, pick);
                            reindex();
                        }
                    }));
        }
        sheet.body(body);
        sheet.footerRow(closeButton(activity.getString(R.string.action_cancel), sheet));
        sheet.show(activity);
    }

    // ------------------------------------------------------------ copy settings

    /**
     * Choose the photograph whose settings the next paste takes.
     *
     * <p>Every row states exactly what will be copied and for which surface
     * (R-CS1), because "copy settings" on its own is a promise: the user cannot
     * tell whether they are about to take Depth alone or the whole set, and a
     * paste that guesses is a paste onto the wrong nine numbers.
     *
     * <p>The copy is held in memory and never written. See {@link #copySourceId}.
     */
    /**
     * The sheet's foot entry for copying, and the gallery's own. Package-visible so
     * the studio's foot can reach the same picker the gallery's action row does --
     * one picker, not two that could disagree about what is copied.
     */
    void showCopySourcePicker() {
        final List<LibraryItem> items = AppState.load(activity.prefs,
                activity.getFilesDir()).library.sorted();
        if (items.isEmpty()) return;
        // Built once rather than per row: the second line is the same sentence
        // on every row, and one string formatted many times is many chances
        // for one of them to differ.
        final String what = activity.getString(R.string.gallery_copy_source_row_sub,
                surfaceLabel(), activity.getString(R.string.gallery_copy_takes));
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_copy_from_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_copy_from));
        for (final LibraryItem item : items) {
            body.addView(ModalSheet.option(activity, item.displayName(), what,
                    item.id.equals(copySourceId), false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sheet.dismiss();
                            copyFrom(item);
                        }
                    }));
        }
        sheet.body(body);
        sheet.footerRow(closeButton(activity.getString(R.string.action_cancel), sheet));
        sheet.show(activity);
    }

    /**
     * Paste the copied settings onto the targets.
     *
     * <p>Per surface, and only ever one surface. {@link
     * LibraryItem#copySettingsFrom} takes the surface as an argument precisely
     * because a copy that carried both would overwrite a lock screen the user had
     * tuned; the surface used is the one the sheet is editing, so a paste from a
     * Lock sheet lands on Lock and leaves Home alone.
     *
     * <p>One item's read-modify-write per target, never a constructor call with
     * loose values: a surface's settings are a set, and a write that names some
     * of them resets the rest. That is the bug that shipped once, as
     * {@code withDepth} quietly clearing Motion, brightness and shadow.
     */
    private void pasteOnto(List<LibraryItem> targets) {
        if (targets.isEmpty()) return;
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        LibraryItem source = lib.find(copySourceId);
        if (source == null) {
            // Either nothing has been copied, or the photograph it came from has
            // gone. Both are the same answer for the user, so both say it.
            copySourceId = null;
            Toast.makeText(activity, R.string.toast_copy_first, Toast.LENGTH_LONG).show();
            return;
        }
        final Surface onto = surface;
        int done = 0;
        for (final LibraryItem target : targets) {
            LibraryStore.editItem(activity.prefs, target.id, new LibraryStore.Editor() {
                @Override
                public LibraryItem edit(LibraryItem it) {
                    return it.copySettingsFrom(source, onto);
                }
            });
            done++;
        }
        // The render only changes if the photograph on screen was one of the
        // targets, so the refresh is conditional. Reloading files and framing for
        // a paste that touched three other photographs would flash the render for
        // nothing, and a flash reads as the app having changed something.
        if (containsActive(targets)) {
            activity.refreshSliders();
            activity.applyFiles();
        }
        picked.clear();
        rebuildGrid();
        Toast.makeText(activity, activity.getString(R.string.toast_settings_pasted, done),
                Toast.LENGTH_SHORT).show();
    }

    private void copyFrom(LibraryItem item) {
        copySourceId = item.id;
        Toast.makeText(activity, activity.getString(R.string.gallery_settings_copied,
                item.displayName()), Toast.LENGTH_SHORT).show();
    }

    private boolean containsActive(List<LibraryItem> targets) {
        String active = AppState.load(activity.prefs, activity.getFilesDir())
                .library.activeId();
        if (active == null) return false;
        for (LibraryItem item : targets) {
            if (item.id.equals(active)) return true;
        }
        return false;
    }

    private String surfaceLabel() {
        return activity.getString(surface == Surface.LOCK
                ? R.string.surface_lock : R.string.surface_home);
    }

    // ------------------------------------------------------------------ targets

    /**
     * What a many-at-once action means right now: the picks, or the row it was
     * opened from.
     *
     * <p>One place, so a menu item and its own label cannot disagree. Reading
     * {@link #picked} at three call sites is three chances for one of them to be
     * stale, and "you deleted three photographs" when you expected one is not a
     * recoverable kind of surprise even with a confirmation in front of it.
     *
     * <p>The bundled row is a target like any other. The data layer used to
     * refuse to remove it, and a delete button that cannot delete is a control
     * that cannot do anything — so the refusal was mirrored here. The refusal is
     * gone, so the mirror is gone with it: the bundled row is a preloaded photo
     * that can be copied, pasted onto, added to a playlist, renamed and deleted
     * like any other.
     */
    private List<LibraryItem> targetsFor(LibraryItem row) {
        List<LibraryItem> out = new ArrayList<LibraryItem>();
        if (picked.isEmpty()) {
            if (row != null) out.add(row);
            return out;
        }
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        for (String id : picked) {
            LibraryItem item = lib.find(id);
            if (item != null) out.add(item);
        }
        return out;
    }

    // ------------------------------------------------------------------ delete

    /**
     * Delete, after asking.
     *
     * <p>The confirmation is not a formality: the rows about to go are often the
     * one on screen, and the honest outcome of that is the bundled demo rather
     * than a neighbour the user never chose. So the message says so before the
     * question rather than after.
     */
    private void confirmDelete(final List<LibraryItem> targets) {
        if (targets.isEmpty()) return;
        String active = AppState.load(activity.prefs, activity.getFilesDir())
                .library.activeId();
        String deletingActiveName = null;
        for (LibraryItem item : targets) {
            if (item.id.equals(active)) deletingActiveName = item.displayName();
        }
        final boolean deletesActive = deletingActiveName != null;
        String message = targets.size() == 1
                ? activity.getString(R.string.gallery_delete_one_message,
                        targets.get(0).displayName())
                : activity.getString(R.string.gallery_delete_many_message, targets.size());
        if (deletesActive) {
            message = message + "\n\n" + activity.getString(
                    R.string.gallery_delete_active_note, deletingActiveName);
        }
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // A destructive question opts out of scrim-tap dismissal: a tap on inert
        // dimming is very often a mis-aimed tap meant for the card, and losing the
        // read-and-decide to a stray tap is the wrong trade for "delete this
        // photograph". Cancel and Delete are the ways out; the backdrop is not.
        sheet.setDismissOnScrimTap(false);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_delete_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_delete_title));
        // The modal family's own .sub line: the question is the whole content
        // of this modal, and the family's second line is what the design draws
        // under every title.
        body.addView(ModalSheet.subtitle(activity, message));
        sheet.body(body);
        sheet.footerRow(
                closeButton(activity.getString(R.string.action_cancel), sheet),
                commitButton(activity.getString(R.string.gallery_delete_one), sheet,
                        new Runnable() {
                            @Override
                            public void run() {
                                performDelete(targets, deletesActive);
                            }
                        }));
        sheet.show(activity);
    }

    /**
     * The delete, in the order the order has to happen in.
     *
     * <p><b>Index and playlists in one commit, then the files.</b> The index is
     * the only place the selection is recorded, so removing the active row and
     * clearing the selection are one derivation — {@code Library.remove} does both
     * — and one write. A pointer left naming a deleted row is a wallpaper showing
     * a photograph that is gone, and the next reconcile would adopt the files
     * straight back.
     *
     * <p>Playlists come out in the same commit for the same reason: a playlist
     * that still names a deleted photograph does not fail loudly. Rotation steps
     * over the missing id, so the wallpaper simply never comes back to it and
     * nothing anywhere says why. One editor means a reader can never see the
     * index without the playlist fix.
     *
     * <p>Files last, and off the UI thread. A crash in between leaves orphan
     * files, which are invisible and reclaimable, rather than an index that
     * points at files that are gone.
     */
    private void performDelete(List<LibraryItem> targets, boolean deletesActive) {
        // AppState.load rather than LibraryStore.load: the read has to come
        // through the single read path, so any row whose file has gone is
        // dropped before the delete is derived from what is actually there, and
        // the bundled row is read under the same fresh-install seed rule as
        // everywhere else.
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        Playlists lists = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        for (LibraryItem item : targets) {
            Playlists.Result result = Playlists.afterRemoving(lists, lib, item.id);
            lib = result.library;
            lists = result.playlists;
        }
        activity.prefs.edit()
                .putString(Library.KEY, lib.toJson())
                .putString(Playlists.KEY, lists.toJson())
                .commit();
        // Drop the cached decode too. Nothing would look up these paths again,
        // but holding megabytes of a deleted photograph because the cache forgets
        // that photographs get deleted is the kind of leak that only shows up as
        // an OOM much later, on someone else's device.
        PairCache.clear();
        for (LibraryItem item : targets) deleteFiles(item);
        picked.clear();
        if (deletesActive) {
            activity.refreshSliders();
            activity.applyFiles();
        }
        // The grid stays up: watching the row disappear is the feedback.
        reindex();
        Toast.makeText(activity, deletesActive
                        ? R.string.gallery_deleted_active : R.string.gallery_deleted,
                Toast.LENGTH_SHORT).show();
    }

    private void deleteFiles(LibraryItem item) {
        final String image = item.imageName;
        final String depth = item.depthName;
        final File dir = activity.getFilesDir();
        Io.run(new java.util.concurrent.Callable<Void>() {
            @Override
            public Void call() {
                if (image != null) {
                    File f = PrefFiles.resolve(dir, image);
                    if (f != null) f.delete();
                }
                if (depth != null) {
                    File f = PrefFiles.resolve(dir, depth);
                    if (f != null) f.delete();
                }
                return null;
            }
        });
    }

    // -------------------------------------------------------------- row actions

    /**
     * The row menu, which is where the many-at-once actions live (R-G4).
     *
     * <p>Not per-row copies of the sheet's controls. "Paste onto selected"
     * applied to one row is a paste onto one row, and the reason it belongs here
     * is that picking several is a gallery gesture — a hold on a cell — so the
     * action and the gesture are in the same place.
     *
     * <p>Whether each item means "this row" or "everything picked" is said in
     * its own label, because the alternative is a menu whose four entries silently
     * change meaning depending on state the user cannot see.
     *
     * <p><b>Open at the finger, never a bottom sheet.</b> The command belongs to
     * the cell the finger is on, so the card opens there; the sheet version put
     * it at the far end of the screen, over the content it acts on, with the
     * finger nowhere near the row that was named. {@link ContextMenu} clamps the
     * card to the visible frame and flips it above the point when there is no
     * room below, and every action in it dismisses the menu before it runs.
     *
     * @param screenX raw screen x of the touch that opened the menu
     * @param screenY raw screen y of the touch that opened the menu
     */
    private void showRowMenu(final LibraryItem item, float screenX, float screenY) {
        final boolean many = !picked.isEmpty();
        // R-G4's four, in the design's order, with Delete last and in the bad
        // ink. A destructive action under three others is harder to hit by
        // accident than one at the bottom of a list, and "the last thing in the
        // menu" is the only position every platform's muscle memory agrees on.
        List<ContextMenu.Item> items = new ArrayList<ContextMenu.Item>();
        items.add(new ContextMenu.Item(
                activity.getString(R.string.gallery_copy_settings),
                new Runnable() {
                    @Override
                    public void run() {
                        copyFrom(item);
                    }
                }));
        items.add(new ContextMenu.Item(many
                        ? activity.getString(R.string.gallery_paste_onto_selected)
                        : activity.getString(R.string.gallery_paste_onto_this),
                new Runnable() {
                    @Override
                    public void run() {
                        pasteOnto(targetsFor(item));
                    }
                }));
        items.add(new ContextMenu.Item(
                activity.getString(R.string.gallery_add_to_playlist),
                new Runnable() {
                    @Override
                    public void run() {
                        showPlaylistPicker(item);
                    }
                }));
        items.add(new ContextMenu.Item(
                activity.getString(R.string.gallery_rename),
                new Runnable() {
                    @Override
                    public void run() {
                        rename(item);
                    }
                }));
        items.add(ContextMenu.Item.destructive(many
                        ? activity.getString(R.string.gallery_delete_selected)
                        : activity.getString(R.string.gallery_delete_one),
                new Runnable() {
                    @Override
                    public void run() {
                        confirmDelete(targetsFor(item));
                    }
                }));
        ContextMenu.Handle open = ContextMenu.showAt(activity, screenX, screenY, items);
        if (open != null) {
            // The menu is placed in screen coordinates once; if the grid scrolls
            // under it, the command stays at the old pixel. Outside touch covers
            // a finger, but not a programmatic or accessibility scroll.
            open.dismissOnScroll(gridScroller);
        }
    }

    private void rename(final LibraryItem item) {
        // No hint: the field opens carrying the name it would change, so an
        // empty-hint line under a full box is a second thing to read for no
        // answer. The playlist sheet below is the one that starts empty, and
        // there the hint is the whole question.
        final EditText field = ModalSheet.field(activity, item.displayName(), null);
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_rename_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_rename));
        body.addView(field);
        sheet.body(body);
        sheet.footerRow(
                closeButton(activity.getString(R.string.action_cancel), sheet),
                commitButton(activity.getString(R.string.gallery_rename_save), sheet,
                        new Runnable() {
                            @Override
                            public void run() {
                                final String label = renameLabel(field);
                                // withLabel is the identity-only edit, so every
                                // render value carries over untouched. Naming the
                                // nine values here would be the bug that shipped
                                // once: a write that names what it means to change
                                // resets everything it does not name.
                                LibraryStore.editItem(activity.prefs, item.id,
                                        new LibraryStore.Editor() {
                                            @Override
                                            public LibraryItem edit(LibraryItem it) {
                                                return it.withLabel(label);
                                            }
                                        });
                                reindex();
                            }
                        }));
        sheet.show(activity);
    }

    private String renameLabel(EditText field) {
        String name = field.getText().toString().trim();
        // Blank falls back to the on-disk name rather than storing a row of
        // spaces, which is what the name field's own emptiness means.
        return name.isEmpty() ? null : name;
    }

    // ----------------------------------------------------------------- playlists

    /**
     * Add the picks to a playlist, or make one and add them to it.
     *
     * <p>Both halves in one dialog, because "Add to playlist" on an install with
     * no playlists has to create the first one or it is a menu that leads
     * nowhere — and the design's whole complaint is that the feature was
     * previously unreachable.
     *
     * <p>Photos are appended, never reordered: adding five photographs to a
     * playlist the user arranged has no business moving the ones already in it.
     */
    private void showPlaylistPicker(LibraryItem row) {
        // The same rule as every other many-at-once action: the picks, or the row
        // this was opened from. Asking for a pick from a row menu would be the one
        // action in the menu that cannot be used without first leaving the menu.
        final List<LibraryItem> targets = targetsFor(row);
        if (targets.isEmpty()) {
            Toast.makeText(activity, R.string.toast_select_first, Toast.LENGTH_LONG).show();
            return;
        }
        final Playlists lists = Playlists.parse(
                activity.prefs.getString(Playlists.KEY, null));
        final List<Playlist> existing = lists.playlists();
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_playlist_pick_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_playlist_pick));
        for (int i = 0; i < existing.size(); i++) {
            final Playlist chosen = existing.get(i);
            // The count as the row's second line, from the plurals the
            // playlists screen already uses, so the two screens cannot
            // disagree about what a count is called.
            body.addView(ModalSheet.option(activity, chosen.displayName(),
                    activity.getResources().getQuantityString(
                            R.plurals.playlist_photos, chosen.size(), chosen.size()),
                    false, false, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sheet.dismiss();
                            addToPlaylist(chosen, targets);
                        }
                    }));
        }
        body.addView(menuOption(sheet, activity.getString(R.string.gallery_playlist_new),
                new Runnable() {
                    @Override
                    public void run() {
                        newPlaylist(targets);
                    }
                }));
        sheet.body(body);
        sheet.footerRow(closeButton(activity.getString(R.string.action_cancel), sheet));
        sheet.show(activity);
    }

    private void newPlaylist(final List<LibraryItem> targets) {
        final EditText field = ModalSheet.field(activity, "",
                activity.getString(R.string.gallery_playlist_name));
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        // Every ModalSheet carries a handle bar: it is the sheet's
        // discoverable way out, and without one this modal had none at all.
        // The description names THIS sheet, so a screen reader does not
        // announce the same control on six different screens.
        sheet.grip(activity.getString(R.string.gallery_playlist_new_close));
        LinearLayout body = modalBody(activity.getString(R.string.gallery_playlist_new));
        body.addView(field);
        sheet.body(body);
        sheet.footerRow(
                closeButton(activity.getString(R.string.action_cancel), sheet),
                commitButton(activity.getString(R.string.gallery_rename_save), sheet,
                        new Runnable() {
                            @Override
                            public void run() {
                                String name = field.getText().toString().trim();
                                if (name.isEmpty()) {
                                    name = activity.getString(R.string.gallery_playlist_new);
                                }
                                List<String> ids = new ArrayList<String>();
                                for (LibraryItem item : targets) ids.add(item.id);
                                // create() applies the defaults a new playlist
                                // starts with — daily, home screen, in order, not
                                // rotating — and nothing is written to the blob
                                // that this app cannot act on.
                                Playlist made = Playlist.create(Playlist.newId(), name, ids);
                                savePlaylists(loadPlaylists().withPlaylist(made), made,
                                        targets.size());
                            }
                        }));
        sheet.show(activity);
    }

    private void addToPlaylist(Playlist chosen, List<LibraryItem> targets) {
        // Re-read rather than write the snapshot the dialog was built from: a
        // rotation may have run while it was open, and a playlist object that
        // predates someone else's edit is a lost update. Only the id is carried
        // across from the snapshot; the value that gets written is the one on
        // disk.
        Playlists current = loadPlaylists();
        Playlist fresh = current.find(chosen.id);
        if (fresh == null) fresh = chosen;
        for (LibraryItem item : targets) {
            // including() ignores a repeat rather than duplicating it, so a
            // second visit cannot make a rotation show one photograph twice
            // running — which would make the "3 of 6" count in the photo line a
            // lie that is always on screen.
            fresh = fresh.including(item.id);
        }
        savePlaylists(current.withPlaylist(fresh), fresh, targets.size());
    }

    private Playlists loadPlaylists() {
        return Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
    }

    private void savePlaylists(Playlists lists, Playlist written, int count) {
        activity.prefs.edit().putString(Playlists.KEY, lists.toJson()).commit();
        picked.clear();
        rebuildGrid();
        Toast.makeText(activity, activity.getString(R.string.gallery_playlist_added,
                count, written.displayName()), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ widgets

    private TextView text(String s, float size) {
        TextView tv = new TextView(activity);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(size);
        tv.setText(s);
        return tv;
    }

    /** A button that hugs its label, for rows and headers rather than grids. */
    /**
     * A header entry: flat, no plate, no platform chrome.
     *
     * <p>These were {@code compact()} Buttons, and a platform Button under the app's
     * LIGHT theme is a raised grey pill with default-coloured text -- so the
     * accent-coloured "Playlists" the mockup draws arrived as an ordinary looking
     * button. A padded TextView is what was being described all along.
     *
     * @param strong the accent colour and semibold, for the one entry that is an
     *               action rather than a reading
     */
    private TextView headerLink(String label, int colour, boolean strong,
                                View.OnClickListener listener) {
        TextView t = text(label, 13);
        t.setTextColor(colour);
        if (strong) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(activity.dp(6), activity.dp(10), activity.dp(6), activity.dp(10));
        if (listener != null) {
            t.setClickable(true);
            t.setOnClickListener(listener);
        }
        return t;
    }

    private Button compact(String label, View.OnClickListener listener) {
        return activity.makeButton(label, listener,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
    }
}
