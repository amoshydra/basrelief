package com.amoshydra.basrelief;

import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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

import com.amoshydra.basrelief.core.store.DepthSource;
import com.amoshydra.basrelief.core.store.VideoSource;
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
     * The playlist collage: a 2×2 block of {@link PhotoTile} at
     * {@link #COLLAGE_TILE_DP}, 40dp overall, on the row's leading edge.
     *
     * <p>18–19dp rather than the grid's cell because the collage is four decodes
     * per row and this app has no view recycling: the size is what keeps the
     * section's cost bounded. 19 + 2 + 19 = 40, so the block has no dead sliver.
     */
    private static final int COLLAGE_DP = 40;
    private static final int COLLAGE_TILE_DP = 19;
    private static final int COLLAGE_GAP_DP = 2;
    /** The playlist row's hairline, the mockup's --line. */
    private static final int HAIRLINE = 0x24FFFFFF;
    /**
     * The header's reserved height, and the reason the sort does not move.
     *
     * <p>48dp is the platform's minimum touch target, so the rotating indicator
     * keeps a comfortable one, and it is a FIXED band: the header is this tall
     * with or without a name, so showing or hiding the name cannot change the
     * header's height or the sort's bounds. The indicator no longer carries a
     * min-height of its own -- see {@link #buildHeaderIndicator}.
     */
    private static final int HEADER_MIN_HEIGHT_DP = 48;
    /**
     * The row's vertical divider: configure on one side, state on the other.
     *
     * <p>A {@code View}, not a {@code "|"} glyph: a text bar carries its own
     * font's side bearings and baseline, so it neither aligns with the two marks
     * beside it nor sizes predictably, and it moves when the control's state
     * changes if it is baked into a neighbour. This is a sibling between them,
     * the same idiom as {@code android.widget}'s action-bar dividers, and it
     * stays put when the switch flips. Brighter than the row hairline because it
     * separates two <i>controls</i> rather than outlining the row.
     */
    private static final int DIVIDER_WIDTH_DP = 1;
    private static final int DIVIDER_HEIGHT_DP = 22;
    private static final int DIVIDER_COLOUR = 0x3DFFFFFF;
    /**
     * The state switch's tints, the same tokens {@link ModalSheet#switchRow}
     * uses. A real {@link android.widget.Switch}, so the shape, the motion and
     * the state a screen reader hears are the ones the platform already teaches —
     * see {@link #playlistRow} for why a switch and not a radio.
     */
    private static final int SWITCH_TRACK_ON = 0x997FD1C1;
    private static final int SWITCH_TRACK_OFF = HAIRLINE;
    private static final int SWITCH_THUMB_ON = PhotoTile.ACCENT;
    /** --dim. Spelled out because {@link #DIM} is declared later in the file and
     *  a static initialiser cannot forward-reference it. */
    private static final int SWITCH_THUMB_OFF = 0x8CFFFFFF;
    /**
     * `.thumb`/`.cell`'s 11px radius. The photograph tiles get it passed to
     * {@link PhotoTile}; the two glyph tiles here carry it themselves.
     */
    private static final int TILE_RADIUS_DP = 11;
    /** The row menu's drawn plate: the mockup's 19dp mark, in the tile's corner. */
    private static final int MENU_PLATE_DP = 19;
    /** The clip mark's plate, the row menu's size: two corner marks, one size. */
    private static final int CLIP_MARK_DP = 19;
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
     * The header's rotating-playlist indicator, kept so it can be refreshed in
     * place when the Playlists editor closes.
     *
     * <p>One indicator naming the one playlist that rotates, sitting immediately
     * left of the sort. It exists as a field for the same reason the old count
     * line did: the editor is a dialog in its own window, its writes do not
     * rebuild this view, and rebuilding the grid to update one label would drop
     * every thumbnail and decode it again — the flash {@code b426298} removed.
     * Null while the grid is down.
     */
    private TextView headerIndicator;

    /**
     * The grid's scroll content: every row the grid draws, in order.
     *
     * <p>Held so an archive, a restore, a delete or the disclosure can be laid out
     * again <i>around the cells that already exist</i> rather than the whole grid
     * being rebuilt and every thumbnail decoded again. The cells are views keyed
     * by id in {@link #cellMarks}; this container is where they are arranged. See
     * {@link #relayoutCells}.
     *
     * <p>Null with {@link #grid}.
     */
    private LinearLayout gridCells;

    /**
     * The playlists section: one child of the scroll content, so it can be moved
     * back into place without rebuilding the collages it holds.
     *
     * <p>Its own rows <i>are</i> rebuilt when playlist data changes, because a
     * collage is up to four decodes and rebuilding it is the honest cost of the
     * data having changed. What this field buys is that an archive, a restore or
     * a disclosure toggle — none of which touch a playlist — do not rebuild it.
     * Null with {@link #grid}.
     */
    private LinearLayout playlistsSection;

    /**
     * Each playlist row's live views, by playlist id, so a rotation toggle can
     * recolour the two rows it moves between without rebuilding the section.
     *
     * <p>Rebuilding the section to show one row going on and another off would
     * re-decode every collage in it, which is exactly the class of waste
     * {@code b426298} was about. The map is cleared and refilled with every
     * section build.
     */
    private final Map<String, PlaylistRow> playlistRows =
            new HashMap<String, PlaylistRow>();

    /**
     * The playlists+index bytes the section was last built from, so a window
     * focus that changed nothing does not rebuild it.
     *
     * <p>The row menu is a focusable {@link android.widget.PopupWindow}, so
     * opening and closing one hands focus back to the gallery — and the focus
     * listener exists for the Playlists editor dialog, which is the other thing
     * that does. Without this guard, dismissing ANY row menu (Archive, Rename,
     * Copy) re-decoded every collage in the section, which is exactly the flash
     * this pass removes. The bytes are the honest test of "did anything change".
     */
    private String playlistsSignature;

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
     * Whether the archived section is expanded under the grid.
     *
     * <p>UI state and deliberately not persisted, for the reason {@link #picked}
     * is not: it is a moment's disclosure rather than a setting, and the next
     * visit starts quiet. Cleared with the pick set by {@link #dismissGrid}, and
     * the <i>open</i> state is derived — {@code showArchived && archivedCount() > 0}
     * — so an archive emptied while the row is open simply stops producing rows
     * and the row follows them out, with no second flag to keep in step.
     */
    private boolean showArchived;

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
        /**
         * Whether this cell is the one on screen. Mutable only because a playlist
         * rotation can move the selection while the gallery is up, and the ring
         * is then repainted in place rather than the grid rebuilt.
         */
        boolean active;
        /** True when this cell is in the archived section, so its description says so. */
        boolean archived;

        PickedCell(LibraryItem item, View cell, PhotoTile tile, boolean active,
                   boolean archived) {
            this.item = item;
            this.cell = cell;
            this.tile = tile;
            this.active = active;
            this.archived = archived;
        }
    }

    /**
     * One playlist row's live views, so a toggle can move the accent wash and the
     * control between two rows without rebuilding the section.
     *
     * <p>The subtitle and the description are rewritten together, because they are
     * the same fact at two distances — the row's coloured state is the glance and
     * the sentence is the reading — and one without the other is a row that
     * disagrees with itself.
     */
    private static final class PlaylistRow {
        final View row;
        final TextView name;
        final TextView sub;
        /**
         * The state mark: a switch, driven by {@link #refreshPlaylistRowVisual}
         * from the same status the wash and the subtitle are. Never tapped itself
         * — {@link #toggleZone} is its target, so a tap beside it cannot fall
         * through to the edit half — so it is a picture of the state inside a
         * control, not a control with its own listener. See {@link #playlistRow}.
         */
        final android.widget.Switch control;

        /**
         * The row's right half: divider and switch in one target, so the whole
         * state zone flips the playlist and the whole left half opens the editor.
         * It carries the spoken state, because the switch inside it is
         * {@code IMPORTANT_FOR_ACCESSIBILITY_NO} and would never speak its own.
         */
        final View toggleZone;

        /**
         * What this row was built from — see {@link #playlistRowSignature}. A
         * rebuild that produces the same string reuses this row's views, and
         * with them the collage's four decodes. Null until first built.
         */
        String signature;

        PlaylistRow(View row, TextView name, TextView sub,
                    android.widget.Switch control, View toggleZone) {
            this.row = row;
            this.name = name;
            this.sub = sub;
            this.control = control;
            this.toggleZone = toggleZone;
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
     * Opens the playlist editor for one playlist, supplied by whoever builds it.
     *
     * <p>Same reason as {@link PhotoPicker}: a route to a screen this class does
     * not own, so the owner installs it rather than this class reaching into
     * another one's internals. The id is passed because the gallery's rows open
     * straight into the editor; the list screen is no longer a destination the
     * gallery needs, since the section in its own scroll replaced it.
     */
    interface PlaylistsNavigator {
        void openEditor(String playlistId);
    }

    private PlaylistsNavigator playlistsNavigator;

    Gallery(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        // The Playlists editor is a dialog in its own window, so closing it is
        // the one moment the gallery's ambient state can be stale and no rebuild
        // will fix it. The root outlives every grid rebuild, and a focus listener
        // refreshes the header indicator and the playlists section where
        // rebuilding the grid would re-decode every thumbnail and every collage.
        root.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus) refreshGalleryChrome();
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

        for (LibraryItem item : lib.sortedLive()) {
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
     * Drop one photograph's tile from the strip without rebuilding it.
     *
     * <p>An archived row leaves the live list, and the strip lists live photos.
     * Rebuilding the strip to remove one tile decoded every remaining thumbnail;
     * removing the one view decodes nothing. See {@code b426298}.
     */
    private void stripRemove(String id) {
        if (id == null) return;
        PhotoTile tile = stripTiles.remove(id);
        if (tile == null) return;
        ViewParent parent = tile.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(tile);
    }

    /**
     * Put one photograph's tile back into the strip, in its sorted place.
     *
     * <p>A restored row rejoins the live list. The tile is new — it was dropped
     * when the row was archived — so this decodes exactly one 46dp thumbnail,
     * which is the honest cost of a row reappearing. Every other tile keeps the
     * bitmap it already has.
     *
     * @param live the index's live order after the write, which is the strip's order
     */
    private void stripInsert(LibraryItem item, List<LibraryItem> live, String activeId) {
        if (stripRow == null || item == null || stripTiles.containsKey(item.id)) return;
        View tile = stripThumb(item, item.id.equals(activeId));
        int at = live.size();
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).id.equals(item.id)) {
                at = i;
                break;
            }
        }
        if (at > stripRow.getChildCount()) at = stripRow.getChildCount();
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
        showArchived = false;
        root.removeView(grid);
        grid = null;
        gridScroller = null;
        gridCells = null;
        playlistsSection = null;
        playlistRows.clear();
        playlistsSignature = null;
        headerIndicator = null;
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
        // A targeted relayout (see relayoutCells) deliberately does NOT clear
        // cellMarks: the cells it arranges are these views, and clearing the map
        // would build new ones and decode every thumbnail again.
        cellMarks.clear();
        selectionActions.clear();
        selectionLineView = null;
        playlistRows.clear();
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        final Library lib = state.library;
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

        // ---- header: title, the rotating indicator, sort.
        //
        // The playlists button is gone: the section lives in the scroll below,
        // and the one thing the header still needs to say about playlists is
        // which one is rotating. The sort keeps its intrinsic width and never
        // moves; the indicator is the only flexible part, so a long name
        // ellipsises rather than pushing the sort off the edge.
        //
        // THE HEADER HAS A FIXED HEIGHT, and that is the whole fix for the layout
        // shift the user reported (the layout shifted down). The
        // indicator carries a 48dp minimum height for its touch target, while the
        // sort link is only its text plus 10dp of padding -- so showing the name
        // made the indicator the tallest child and the header grew by ~28px,
        // pushing the sort, the hint line and the whole grid down. GONE also
        // removed it from the row, so the two states genuinely had two heights.
        // Reserving one 48dp band for the header means the sort's bounds are
        // byte-identical with and without the name, and only the name itself
        // appears; see the invariant in AGENTS.md. Reserve rather than overlay:
        // the indicator is inside the same weight-1 right slot, so nothing has to
        // be positioned against a measured width that is not final at build time.
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(activity.dp(HEADER_MIN_HEIGHT_DP));
        TextView title = text(activity.getString(R.string.gallery_title), 16);
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout headerRight = new LinearLayout(activity);
        headerRight.setOrientation(LinearLayout.HORIZONTAL);
        headerRight.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        headerRight.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        headerIndicator = buildHeaderIndicator();
        headerRight.addView(headerIndicator, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        headerRight.addView(headerLink(activity.getString(R.string.gallery_sort)
                + " · " + lib.sort().label(), DIM, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu();
            }
        }));
        header.addView(headerRight);
        panel.addView(header);

        // The count and the hint share one line: they are the same question at two
        // moments, and two lines would be one too many on a screen this size. The
        // line is BUILT even with no photographs -- GONE rather than absent -- so
        // an archive, a restore or a delete that empties the live grid can hide it
        // in place instead of the whole panel being rebuilt. See relayoutCells.
        selectionLineView = selectionLine();
        selectionLineView.setVisibility(lib.sortedLive().isEmpty()
                ? View.GONE : View.VISIBLE);
        panel.addView(selectionLineView);

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
        // Held so an archive, a restore, a delete or the disclosure can lay the
        // rows out again around these same cells; see relayoutCells.
        gridCells = cells;
        // Photos, then the archive disclosure, then the playlists section -- the
        // order the design fixed. The playlists section is ALWAYS present, even at
        // zero: a playlist has no precondition, so hiding it would mean the first
        // one cannot be created.
        fillPhotoCells(cells, lib, activeId);
        playlistsSection = buildPlaylistsSection(lib);
        cells.addView(playlistsSection, rowSpacing(cells.getChildCount() == 0));

        scroll.addView(cells, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshHeaderIndicator();

        // No footer. Every action the footer duplicated -- Copy settings, Paste,
        // Add to playlist -- and Archive / Restore are all in the row's context
        // menu, and Done was the same exit BACK takes. A row of redundant
        // controls drawing over the render was four things to keep in step.
        return panel;
    }

    /**
     * The photograph rows, in the fixed order, reusing the cells that already
     * exist.
     *
     * <p>Separated from {@link #buildGrid} because the archive, the delete and
     * the disclosure all change which rows the grid draws and none of them changes
     * a cell's picture. Building a cell calls {@link PhotoTile#show}, which
     * decodes a thumbnail; rebuilding the grid to move one row therefore re-decoded
     * every photograph in it. This method arranges the existing views instead, and
     * only builds a cell that has never been built. See {@code b426298}, where the
     * strip got the same fix.
     *
     * <p>The open state is DERIVED: an archive emptied while the disclosure is
     * open stops producing rows and the row follows them out.
     */
    private void fillPhotoCells(LinearLayout cells, Library lib, String activeId) {
        final List<LibraryItem> rows = lib.sortedLive();
        final List<LibraryItem> archived = lib.sortedArchived();
        final boolean archiveOpen = showArchived && !archived.isEmpty();
        showArchived = archiveOpen;

        // .cells{gap:8px} is a row gap as much as a column one. The rows used to
        // stack with no gap at all: the horizontal 8dp read as the grid's only
        // gutter, and a row of photographs touched the row below it.
        boolean first = cells.getChildCount() == 0;
        if (rows.isEmpty()) {
            // The ONE empty state. It does not know why the grid is empty --
            // archived, deleted, or never added are the same answer to "what can
            // I tap?" -- so the message is fixed and the archive never gets a
            // hero here. An archived count, if any, still draws the same quiet
            // disclosure below.
            cells.addView(emptyGallery(), rowSpacing(first));
            first = false;
        } else {
            // The add tile FLOWS with the photographs: it is the first CELL of
            // the first row, and the photographs follow it in the same row rather
            // than starting a new one. It is not a photograph, so it is not sorted
            // among them -- it is a fixed leading affordance -- but hoisting it out
            // of the flow made it a row of its own and cost a whole row of height
            // at the top of the grid. Now a 3-column grid of the add tile plus
            // eight photos is three rows of three, exactly as the photographs
            // alone would be. See {@link #cellRowReuse} for the arithmetic.
            cells.addView(cellRowReuse(rows, 0, activeId, false, true), rowSpacing(first));
            first = false;
            // The first row spent its leading column on the add tile, so the
            // photographs start one column in: the next row resumes at
            // GRID_COLUMNS - 1, not at GRID_COLUMNS.
            for (int start = GRID_COLUMNS - 1; start < rows.size(); start += GRID_COLUMNS) {
                cells.addView(cellRowReuse(rows, start, activeId, false, false),
                        rowSpacing(first));
                first = false;
            }
        }

        // The archive: one quiet disclosure, shown only when something is
        // archived, identical in a full grid and an empty one. It sits below the
        // photos (and the add tile, or the empty box) and above the playlists
        // section that follows the grid in the scroll.
        if (!archived.isEmpty()) {
            cells.addView(archiveDisclosure(archived.size(), archiveOpen),
                    rowSpacing(first));
            first = false;
            if (archiveOpen) {
                for (int start = 0; start < archived.size(); start += GRID_COLUMNS) {
                    cells.addView(cellRowReuse(archived, start, activeId, true, false),
                            rowSpacing(first));
                    first = false;
                }
            }
        }
    }

    /**
     * Lay the grid's rows out again around the cells that already exist.
     *
     * <p><b>This is the whole fix for the flash.</b> Archiving a photo, restoring
     * one, deleting one and opening or closing the disclosure all used to call
     * {@link #rebuildGrid} → {@link #buildGrid} → {@link PhotoTile#show} for every
     * cell, so one action re-decoded every thumbnail in the grid. The cells are
     * keyed by id in {@link #cellMarks}; this re-parents them into the rows the
     * current index wants and adds no decode. The playlists section is moved along
     * with them, because none of those actions changes a playlist.
     *
     * <p>A cell is detached from the row it was in before the rows are thrown
     * away: a view still parented to its old row cannot be adopted by a new one.
     */
    private void relayoutCells() {
        if (gridCells == null) return;
        for (PickedCell mark : cellMarks.values()) {
            ViewParent parent = mark.cell.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(mark.cell);
        }
        gridCells.removeAllViews();
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        fillPhotoCells(gridCells, lib, lib.activeId());
        if (playlistsSection != null) {
            ViewParent parent = playlistsSection.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(playlistsSection);
            gridCells.addView(playlistsSection, rowSpacing(gridCells.getChildCount() == 0));
        }
        if (selectionLineView != null) {
            selectionLineView.setVisibility(lib.sortedLive().isEmpty()
                    ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * The one archive treatment: a quiet, full-width disclosure row.
     *
     * <p>12sp, dim ink, no rule, no accent, and the same in a full grid and an
     * empty one. It keeps a 48dp touch target because it is the only route to
     * archived photos -- reachable is not prominent, but a route that is hard to
     * hit is a route that is not there.
     */
    private View archiveDisclosure(final int count, final boolean open) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(activity.dp(48));
        row.setPadding(activity.dp(2), activity.dp(9), activity.dp(2), activity.dp(9));

        TextView label = text(activity.getString(R.string.gallery_archived_toggle, count),
                12);
        label.setTextColor(0x57FFFFFF); // --dimmer
        row.addView(label);

        TextView chev = text(open
                ? activity.getString(R.string.gallery_archived_toggle_hide_glyph)
                : activity.getString(R.string.gallery_archived_toggle_show_glyph), 10);
        chev.setTextColor(0x57FFFFFF);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chevLp.leftMargin = activity.dp(6);
        row.addView(chev, chevLp);

        row.setContentDescription(activity.getString(open
                        ? R.string.gallery_archived_toggle_hide
                        : R.string.gallery_archived_toggle_show, count));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The disclosure changes which rows the grid draws and nothing
                // about any photograph, so the cells are re-parented rather than
                // rebuilt: rebuilding re-decoded every thumbnail and flashed the
                // whole grid.
                showArchived = !open;
                relayoutCells();
            }
        });
        return row;
    }

    /**
     * The header's rotating-playlist indicator: the glyph and the name, one
     * flexible target that ellipsises rather than displacing the sort.
     *
     * <p>Built always and shown only while something rotates, because the
     * alternative — adding and removing it as the answer changes — moves the
     * selection line and the grid below it under the user's thumb. The whole
     * {@code ▶ name} group is the target (not just the glyph), with a 48dp
     * minimum height so the hit area is generous whatever the name's length.
     */
    private TextView buildHeaderIndicator() {
        TextView ind = text("", 13);
        ind.setTextColor(PhotoTile.ACCENT);
        ind.setTypeface(null, Typeface.BOLD);
        ind.setSingleLine(true);
        ind.setEllipsize(TextUtils.TruncateAt.END);
        ind.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ind.setMinHeight(activity.dp(48));
        ind.setPadding(activity.dp(4), 0, activity.dp(4), 0);
        ind.setVisibility(View.GONE);
        ind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Scrolls to the active row and opens NOTHING. The editor has a
                // route already -- the row's own pencil -- and a header tap that
                // opened a modal would be a second, surprising one.
                scrollToActivePlaylist();
            }
        });
        return ind;
    }

    /**
     * Show or hide the header indicator, and name the playlist it names.
     *
     * <p>Read through {@link Playlists#rotating}, the same gate every playlist
     * surface uses, so the gallery cannot claim a rotation that rotates nowhere
     * (a playlist whose stored screens could not be read). A no-op while the grid
     * is down: there is nothing to refresh, and the next build computes it from
     * scratch.
     *
     * <p>Called on window focus because the Playlists editor is a dialog in its
     * own window — dismissing it returns focus here, and no other callback covers
     * that moment without rebuilding the grid.
     */
    private void refreshHeaderIndicator() {
        // headerIndicator is null exactly when the grid is down (cleared with it),
        // so it is the sufficient guard. Not `grid`: buildGrid calls this before
        // assigning the field, and gating on `grid` would leave the indicator
        // hidden on the first build.
        if (headerIndicator == null) return;
        Playlists lists = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        Playlist rotating = activePlaylist(lists);
        if (rotating == null) {
            headerIndicator.setVisibility(View.GONE);
            return;
        }
        String name = rotating.displayName();
        headerIndicator.setText(activity.getString(R.string.gallery_rotating_indicator,
                activity.getString(R.string.gallery_rotating_glyph) + "  " + name));
        headerIndicator.setContentDescription(
                activity.getString(R.string.gallery_rotating_indicator_talk, name));
        headerIndicator.setVisibility(View.VISIBLE);
    }

    /** The one playlist that rotates on any screen, or null. */
    private static Playlist activePlaylist(Playlists lists) {
        if (lists == null) return null;
        Playlist p = lists.rotating(Surface.HOME);
        return p != null ? p : lists.rotating(Surface.LOCK);
    }

    /**
     * Bring the active playlist row into view and mark it briefly.
     *
     * <p>The row, not merely the section: the point of the header tap is to show
     * which playlist is the one rotating, so the scroll stops with that row on
     * screen. The flash is a foreground outline rather than a background change,
     * so it does not disturb the row's own wash.
     */
    private void scrollToActivePlaylist() {
        if (gridScroller == null) return;
        Playlist rotating = activePlaylist(Playlists.parse(
                activity.prefs.getString(Playlists.KEY, null)));
        if (rotating == null) return;
        PlaylistRow r = playlistRows.get(rotating.id);
        if (r == null) return;
        int y = offsetInCells(r.row);
        ((ScrollView) gridScroller).smoothScrollTo(0, Math.max(0, y - activity.dp(24)));
        flashRow(r.row);
    }

    /** A view's top in the scroll's own coordinates, walking up to {@link #gridCells}. */
    private int offsetInCells(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != gridCells) {
            y += cur.getTop();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return y;
    }

    private void flashRow(View row) {
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(activity.dp(11));
        outline.setStroke(activity.dp(2), PhotoTile.ACCENT);
        row.setForeground(outline);
        row.postDelayed(new Runnable() {
            @Override
            public void run() {
                row.setForeground(null);
            }
        }, 900);
    }

    /**
     * The one moment the gallery's playlist state can be stale with no rebuild:
     * a dialog window (the Playlists editor) has just closed over it.
     *
     * <p>Only the header indicator and the playlists section are touched. The grid
     * and the strip are the same index read twice and neither can have changed —
     * editing a playlist does not write the index — so rebuilding the whole grid
     * to reflect a rename would re-decode every photograph for nothing.
     */
    private void refreshGalleryChrome() {
        if (grid == null) return;
        refreshHeaderIndicator();
        if (playlistsSection == null) return;
        // Only a real change rebuilds the section. See playlistsSignature: a row
        // menu handing focus back must not decode every collage.
        if (playlistsSignature != null && playlistsSignature.equals(playlistsSignature())) {
            return;
        }
        fillPlaylistsSection(playlistsSection,
                AppState.load(activity.prefs, activity.getFilesDir()).library);
    }

    /**
     * The blob the playlists section depends on.
     *
     * <p>The blob decides the rows and every value in them. The index resolves a
     * collage's tiles, but membership is the blob's, and a photograph's file going
     * missing is the one case this misses — and that path already rebuilds the
     * section through {@link #refreshPlaylistsSection} wherever it can happen
     * (delete). Comparing raw bytes is exact and costs one preference read,
     * against a rebuild that costs one decode per collage tile.
     */
    private String playlistsSignature() {
        return activity.prefs.getString(Playlists.KEY, "");
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
     * One row of {@link #GRID_COLUMNS} slots: photographs (and optionally the
     * leading add tile), the rest padded with blanks.
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
     * gap and made it ragged against the text above it. When
     * {@code withAddTile} is set the add tile IS that first column, and every
     * photograph after it carries the gutter as its column demands -- so the
     * first photograph is no longer flush left, it is in column 1.
     *
     * <p><b>A cell is reused when one exists for its id.</b> This is what makes a
     * relayout free: the view already holds the decoded bitmap, so re-parenting it
     * decodes nothing. A cell is only built -- and only decoded -- the first time
     * its id appears. The layout params are re-applied on reuse because a cell can
     * move columns, and the gutter belongs to the column.
     *
     * <p><b>The add tile flows in the first row when {@code withAddTile} is set</b>
     * and is absent from every other row. It is cheap to rebuild each time (it
     * holds no decode), so a relayout re-creates it rather than keying it: only
     * photographs need the {@link #cellMarks} reuse.
     *
     * @param archivedDraw true for an archived section's muted cells
     * @param withAddTile  true on the one row that leads with the {@code ＋} tile
     */
    private LinearLayout cellRowReuse(List<LibraryItem> rows, int start, String activeId,
                                      boolean archivedDraw, boolean withAddTile) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        final int lead = withAddTile ? 1 : 0;
        if (withAddTile) {
            // Column 0. The blank spacers below never apply to it: this row
            // always has a tile there, whatever the photograph count.
            line.addView(addCell(true));
        }
        for (int i = 0; i < GRID_COLUMNS - lead; i++) {
            int column = lead + i;
            int at = start + i;
            boolean firstInRow = column == 0;
            if (at < rows.size()) {
                LibraryItem item = rows.get(at);
                PickedCell mark = cellMarks.get(item.id);
                View c;
                if (mark == null) {
                    c = cell(item, item.id.equals(activeId), firstInRow, archivedDraw);
                } else {
                    c = mark.cell;
                    c.setLayoutParams(cellLayoutParams(firstInRow));
                }
                line.addView(c);
            } else {
                // A spacer, and deliberately zero-height: it exists only to hold a
                // column open so the last row keeps the first row's column widths,
                // and giving it a height makes an empty box the size of a photograph.
                View blank = new View(activity);
                LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(
                        cellPx, 0);
                if (column > 0) blankLp.leftMargin = activity.dp(GRID_GAP_DP);
                blank.setLayoutParams(blankLp);
                line.addView(blank);
            }
        }
        return line;
    }

    /** The column width and the gutter, applied on both a build and a reuse. */
    private LinearLayout.LayoutParams cellLayoutParams(boolean firstInRow) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellPx,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!firstInRow) lp.leftMargin = activity.dp(GRID_GAP_DP);
        return lp;
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
        return standsOut(item, null);
    }

    /**
     * Whether this row can produce relief, from the one derivation — with the
     * embedded flag where the read has landed and {@code null} where it has not.
     *
     * <p>This used to be {@code item.isBundled() || item.hasDepth()} here and a
     * separate {@code embedded && !hasFile} in the thumbnail callback, which is two
     * derivations of one question and therefore two chances for one of them to be
     * wrong about a photograph carrying a {@code dePH} chunk. The callback form was
     * the one that patched the chip visible, so the index form was never wrong by
     * itself — it was simply half the answer, and the half that was missing was the
     * clip.
     *
     * <p><b>A clip stands out on its track</b>, which {@code hasDepth()} cannot see:
     * that asks about the <i>still</i> files, because that is the question with one
     * answer for a photograph. A row naming clips is {@link DepthSource.Kind#TRACK}
     * and has relief whatever its poster frame happens to carry — and reading the
     * still would answer for the photograph a user is no longer looking at.
     *
     * <p>{@code null} for the embedded flag is deliberate rather than a hedge: it
     * makes the answer {@link DepthSource.Kind#UNKNOWN} rather than false for a
     * photograph whose depth is inside its own PNG, which is the same window the fix
     * line has, and the chip is reversible — built with it, shown from the callback
     * the read delivers. An absent chip for one thumbnail's load is invisible; a
     * chip on a photograph with no map is a lie that survives the load.
     */
    private boolean standsOut(LibraryItem item, Boolean embedded) {
        if (item == null) return false;
        // The reserved id, first and outside the derivation, because :core's
        // DepthSource deliberately has no branch for it. `Library.withBundled`
        // accepts a NULL depthName -- that is the pre-migration state, before the
        // demo's assets have been copied into filesDir -- and the demo ships a map
        // in res/raw either way, so it stands out. This guard was the only thing
        // standing between that row and an absent chip, which is why it is kept
        // rather than folded in: DepthSource cannot answer it, so folding it in
        // would delete the answer instead of moving it.
        if (item.isBundled()) return true;
        // The clip's playable flag comes from the ONE cache in the process, keyed
        // by id. A second cache here would be a second answer to the same question,
        // and this is the class of bug that put "No depth" over a photograph with a
        // map. Absent means unread, which is the window DepthSource already models.
        return DepthSource.forItem(item, embedded,
                VideoSource.forItem(item, activity.clipPlayableFor(item.id))).hasDepth();
    }

    /**
     * One cell, live or archived.
     *
     * <p>An archived cell is the same cell muted: the same tile, the same depth chip and
     * the same {@code \u22ef} menu, with the photograph turned down. It cannot SELECT --
     * an archived photo is not put on the wallpaper from here, and selecting it would be a
     * route the disclosure exists to gate -- but it can still be PICKED, because the
     * mixed-pick Restore verb acts on a selection.
     *
     * <p><b>A cell can be reused, so the mark's archived flag is read at tap
     * time.</b> Archiving and restoring re-parent the same view instead of
     * rebuilding it (see {@link #relayoutCells}), and a listener that captured the
     * build-time boolean would leave a restored photo refusing a tap because it
     * was archived when it was built.
     *
     * @param archivedDraw true when this cell is drawn in the archived section
     */
    private View cell(final LibraryItem item, final boolean isActive,
                      boolean firstInRow, final boolean archivedDraw) {
        final boolean isPicked = picked.contains(item.id);
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        // Fixed, not weighted: cellPx is the column width the buildGrid arithmetic
        // promised, and a weighted cell would take the gutter back out of the
        // column it belongs to. The first column carries no gutter; see cellRowReuse.
        cell.setLayoutParams(cellLayoutParams(firstInRow));

        // The tile is the picture. It owns the rounded fill, the bitmap, the
        // clip and every ring and pick mark; the grid adds only its own two
        // controls on top. See PhotoTile for why the marks live there.
        final PhotoTile tile = new PhotoTile(activity, cellPx, activity.dp(TILE_RADIUS_DP));
        tile.setActive(isActive);
        tile.setPicked(isPicked);
        // The archived section's rows are the same cells, muted: the wash is the
        // disclosure's whole visual difference, so an archived photo reads as set
        // aside rather than as a second kind of row.
        tile.setMuted(archivedDraw);
        // Square by arithmetic, not by measurement: cellPx is the column's width, so
        // using it as the height is what makes the cell a square. MATCH_PARENT width
        // and the computed height, because the two have to agree and only one of them
        // was computed.
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cellPx));
        final View chip = depthChip(item);
        tile.addView(chip, chipParams());
        // Only for a clip row. The depth chip answers "has relief"; this answers
        // "is it a video", and the two are independent -- a clip with no depth map
        // yet still plays.
        if (item.video != null) tile.addView(clipMark(), clipParams());
        tile.addView(menuButton(item), menuParams());
        // The embedded-depth answer comes out of the same read that decodes the
        // thumbnail; see PhotoTile.show. Re-derived rather than OR-ed in: the chip
        // asks the same question the fix line asks, so a row that could stand out
        // for a reason other than an embedded chunk -- a clip's depth track -- is
        // answered by the same rule instead of by a second one. The bundled demo's
        // map is the separate res/raw/depth.png, which DepthSource already reports
        // as FILE for it.
        final boolean before = chip.getVisibility() == View.VISIBLE;
        tile.show(item, new Io.EmbeddedDepthListener() {
            @Override
            public void onFound(boolean embedded) {
                if (!before && standsOut(item, Boolean.valueOf(embedded))) {
                    chip.setVisibility(View.VISIBLE);
                }
            }
        });
        cell.addView(tile);
        // No name under the cell. The mockup draws none, and with square cells a
        // caption would be a quarter of the cell's height spent on a label -- the
        // depth chip and the two selection marks already say which photograph
        // this is.

        // Kept before the listeners, which read the mutable flag rather than the
        // parameter; see the class note on reuse.
        final PickedCell mark = new PickedCell(item, cell, tile, isActive, archivedDraw);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!picked.isEmpty()) {
                    togglePick(item);
                    return;
                }
                // An archived photo is not selectable from the live grid: it is
                // hidden, and putting it on the wallpaper is a route the
                // disclosure exists to gate. Restore it first, then tap it. The
                // menu still opens, because Restore lives there.
                if (mark.archived) return;
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
        cellMarks.put(item.id, mark);
        cell.setContentDescription(stateDescription(item, isActive, isPicked, archivedDraw));
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
        mark.cell.setContentDescription(
                stateDescription(mark.item, mark.active, on, mark.archived));
    }

    /**
     * End the pick mode in place, redrawing the marks it was showing.
     *
     * <p>A paste or an add-to-playlist ends the gesture, so the badges have to go
     * with it. The grid used to be rebuilt to clear them, which re-decoded every
     * thumbnail for a state the cells already hold — the flash {@code b426298}
     * removed from the strip and this file removes from the grid.
     */
    private void clearPicks() {
        for (String id : new ArrayList<String>(picked)) drawPicked(id, false);
        picked.clear();
        refreshSelectionUi();
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
        return stateDescription(item, isActive, isPicked, item.isArchived());
    }

    /**
     * As above, plus the archived fact for a cell drawn in the archived section.
     *
     * <p>The wash is colour, which nobody can hear, so an archived cell says so.
     * The state composes with the other two rather than replacing them: an
     * archived photo can still be on the wallpaper, and it can still be picked.
     */
    private String stateDescription(LibraryItem item, boolean isActive, boolean isPicked,
                                    boolean archived) {
        String name = item.displayName();
        if (isActive && isPicked) {
            return activity.getString(R.string.gallery_talk_active_selected, name);
        }
        if (isActive) return activity.getString(R.string.gallery_talk_active, name);
        if (isPicked) return activity.getString(R.string.gallery_talk_selected, name);
        // An archived cell's bare state is "archived", not "Use …": the action
        // wording would promise a tap that does nothing on a hidden row.
        if (archived) return activity.getString(R.string.gallery_talk_archived, name);
        return activity.getString(R.string.gallery_thumb_use, name);
    }

    /**
     * Bottom-RIGHT, for the clip mark: the two top corners are taken (the depth
     * chip top-left, the row menu top-right), and "which mark is this" is a question
     * a gallery must not ask.
     */
    private FrameLayout.LayoutParams clipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(CLIP_MARK_DP), activity.dp(CLIP_MARK_DP),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, activity.dp(6), activity.dp(6));
        return lp;
    }

    /**
     * The clip mark: a play glyph on a plate.
     *
     * <p><b>A photograph and a clip are both ordinary rows</b> -- that is the whole
     * design, and it means the tile cannot show the difference by itself: a clip's
     * poster frame is a still of the video, so the grid looks identical either way.
     * The mark is the one place that difference is stated. The plate is there for
     * the same reason the row menu has one: a dark glyph on a dark photograph is a
     * mark that is present and unreadable.
     */
    private View clipMark() {
        TextView glyph = text(activity.getString(R.string.gallery_clip_glyph), 10);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(plain(0x99000000, activity.dp(10)));
        glyph.setContentDescription(activity.getString(R.string.gallery_is_clip));
        return glyph;
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
        android.util.Log.i("Basrelief", "gallery reindex: rebuilding tiles");
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

    /** The playlist's editor, through the route the host installed. */
    private void openEditor(String playlistId) {
        if (playlistsNavigator == null) {
            Toast.makeText(activity, R.string.toast_playlists_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        playlistsNavigator.openEditor(playlistId);
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
        // Patched, not rebuilt: a paste changes render values, none of which the
        // grid draws, so the only grid state that moved is the pick set. Rebuilding
        // would re-decode every thumbnail for a settings change.
        clearPicks();
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
        // Patched, not rebuilt. The deleted ids' cells and strip tiles are dropped
        // and the rows are laid out again around the cells that remain, so the
        // surviving photographs are not decoded again — building the grid to remove
        // one row re-decoded every other thumbnail in it.
        for (LibraryItem item : targets) {
            cellMarks.remove(item.id);
            stripRemove(item.id);
        }
        clearPicks();
        if (deletesActive) {
            activity.refreshSliders();
            activity.applyFiles();
        }
        // The grid stays up: watching the row disappear is the feedback. Delete is
        // the other half of archive — it removes the id from every playlist — so
        // the section is rebuilt in the same breath.
        relayoutCells();
        refreshPlaylistsSection();
        refreshHeaderIndicator();
        Toast.makeText(activity, deletesActive
                        ? R.string.gallery_deleted_active : R.string.gallery_deleted,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Archive or restore a set of photos. One blob, unlike delete's two.
     *
     * <p><b>Archive never touches the selection, the playlists or the files.</b>
     * That is the design's central contrast with delete:
     *
     * <ul>
     *   <li><b>The selection</b> is left alone, so archiving the active photo
     *       keeps the wallpaper. The row is hidden, not destroyed, and the engine
     *       keeps resolving the same id and drawing the same relief. This is why
     *       there is no {@code applyFiles} call here: the render is unchanged.
     *   <li><b>The playlists</b> are left alone, so a playlist keeps an archived
     *       member and skips it ({@code Playlists.livingIds}); restoring brings
     *       back the place the user arranged. Delete is the other half — it
     *       removes the id everywhere — and the two must not be collapsed.
     *   <li><b>The files</b> stay in {@code filesDir}, so restore is the same bit
     *       off and nothing has to be re-imported.
     * </ul>
     *
     * <p>Only the index is written, and only the strip and grid need rebuilding:
     * an archived row leaves the live grid, and a restored one rejoins it in its
     * sorted place. The wallpaper does not move either way, so it is not
     * refreshed.
     */
    private void performArchive(List<LibraryItem> targets, boolean archive) {
        if (targets.isEmpty()) return;
        java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
        for (LibraryItem item : targets) ids.add(item.id);
        LibraryStore.setArchived(activity.prefs, ids, archive);
        // End the pick mode the row menu may have been acting on, redrawing the
        // badges in place; the grid is not rebuilt for it.
        clearPicks();
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        String activeId = lib.activeId();
        List<LibraryItem> live = lib.sortedLive();
        for (LibraryItem item : targets) {
            PickedCell mark = cellMarks.get(item.id);
            if (mark != null) {
                // The same cell, muted or unmuted in place. Its listener reads this
                // flag rather than the build-time one; see cell().
                mark.archived = archive;
                mark.tile.setMuted(archive);
                mark.cell.setContentDescription(stateDescription(
                        mark.item, mark.active, false, archive));
            }
            // The strip lists live photos only, so an archived row leaves it and a
            // restored one rejoins at its sorted place — one tile, not a rebuild.
            if (archive) {
                stripRemove(item.id);
            } else {
                stripInsert(item, live, activeId);
            }
        }
        // Re-parent the cells into the section this new state wants: a restored
        // row rejoins the live grid in its sorted place, an archived one appears
        // under the disclosure. No thumbnail is decoded by any of it.
        relayoutCells();
        if (targets.size() == 1) {
            Toast.makeText(activity, archive
                            ? R.string.gallery_archived : R.string.gallery_restored,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, activity.getResources().getQuantityString(
                            archive ? R.plurals.gallery_archived_many
                                    : R.plurals.gallery_restored_many,
                            targets.size(), targets.size()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** The verb for a row's own state: Archive on a live row, Restore on an archived one. */
    private boolean rowIsArchived(LibraryItem item) {
        LibraryItem fresh = AppState.load(activity.prefs, activity.getFilesDir())
                .library.find(item.id);
        return fresh != null && fresh.isArchived();
    }

    private void deleteFiles(LibraryItem item) {
        final String image = item.imageName;
        final String depth = item.depthName;
        final String normal = item.normalName;
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
                // The supplied normal is a third file of the row; deleting the
                // photo without it would leave an orphan nothing can ever reach.
                if (normal != null) {
                    File f = PrefFiles.resolve(dir, normal);
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
        // Paste acts on the selection, always. With the footer gone this is the
        // only place the requirement is discoverable, so it is stated on a
        // disabled row rather than left to a label. The old "Paste onto this" is
        // retired: to paste onto one photo, hold it first.
        if (many) {
            items.add(new ContextMenu.Item(
                    activity.getString(R.string.gallery_paste_onto_selected),
                    new Runnable() {
                        @Override
                        public void run() {
                            pasteOnto(targetsFor(null));
                        }
                    }));
        } else {
            items.add(ContextMenu.Item.needsSelection(
                    activity.getString(R.string.gallery_paste_onto_selected),
                    activity.getString(R.string.gallery_paste_needs_selection)));
        }
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
        // Archive / Restore. On a multi-pick the verbs are scoped to the pick and
        // a mixed pick offers BOTH, because archiving and restoring are opposite
        // directions and one verb cannot mean both. On a single row it is the
        // row's own state that chooses the verb.
        final List<LibraryItem> archiveTargets = many ? targetsFor(null)
                : java.util.Collections.singletonList(item);
        int live = 0;
        int hidden = 0;
        for (LibraryItem t : archiveTargets) {
            if (rowIsArchived(t)) hidden++;
            else live++;
        }
        if (live > 0) {
            final boolean bulk = many;
            final List<LibraryItem> toArchive = archiveTargets;
            items.add(new ContextMenu.Item(bulk
                            ? activity.getString(R.string.gallery_archive_selected, live)
                            : activity.getString(R.string.gallery_archive),
                    new Runnable() {
                        @Override
                        public void run() {
                            performArchive(toArchive, true);
                        }
                    }));
        }
        if (hidden > 0) {
            final boolean bulk = many;
            final List<LibraryItem> toRestore = archiveTargets;
            items.add(new ContextMenu.Item(bulk
                            ? activity.getString(R.string.gallery_restore_selected, hidden)
                            : activity.getString(R.string.gallery_restore),
                    new Runnable() {
                        @Override
                        public void run() {
                            performArchive(toRestore, false);
                        }
                    }));
        }
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
     * The playlists section: a caption, the {@code New playlist} row, and one row
     * per playlist.
     *
     * <p><b>Always present, even at zero.</b> That is the one rule the archive
     * does not lend it: a photo can be un-archived later, but a playlist has no
     * precondition, so hiding the section when there are none would mean the first
     * one could never be created. At zero it is the caption and the New row and
     * nothing else — no sentence explaining the row to the user looking at it.
     *
     * <p><b>Reached through the grid, not a header button.</b> The section is
     * content in the same scroll as the photos, so the header no longer carries a
     * Playlists entry; it carries only the indicator naming what rotates.
     */
    private LinearLayout buildPlaylistsSection(Library lib) {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        fillPlaylistsSection(section, lib);
        return section;
    }

    /** Rebuild the section's rows because playlist data changed. See its holder. */
    private void refreshPlaylistsSection() {
        if (playlistsSection == null) return;
        fillPlaylistsSection(playlistsSection,
                AppState.load(activity.prefs, activity.getFilesDir()).library);
    }

    /**
     * Fill the section for the playlists on disk.
     *
     * <p><b>A row whose picture has not changed is reused, not rebuilt.</b> A
     * rebuild costs the collage's up-to-four decodes, and the editor is the thing
     * that causes rebuilds: opening it and dismissing it without editing writes
     * nothing, so nothing about the rows differs, so every collage on screen was
     * being decoded again to produce an identical picture. That is why the
     * membership is compared here rather than the section simply being rebuilt.
     *
     * <p>It is a reuse and not a patch, because a rename, a trigger change or a
     * membership change really can move several rows, and only a rebuild gets the
     * collage right. The comparison is per row, so editing ONE playlist
     * redraws ONE collage and leaves the rest of the section alone.
     *
     * <p>A rotation TOGGLE never comes here at all: it repaints the rows it moves
     * between in place. See {@link #togglePlaylist}.
     */
    private void fillPlaylistsSection(LinearLayout section, Library lib) {
        section.removeAllViews();
        Playlists lists = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        long now = System.currentTimeMillis();
        PlaylistStatus status = PlaylistStatus.of(lists, lib, now);

        TextView caption = text(activity.getString(R.string.gallery_playlists_caption), 11);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.09f);
        caption.setTextColor(0x57FFFFFF); // --dimmer
        caption.setTypeface(medium());
        caption.setPadding(0, activity.dp(14), 0, activity.dp(6));
        section.addView(caption);

        section.addView(newPlaylistRow());

        // The rows this pass keeps, so a playlist that has been deleted takes its
        // holder with it: a stale holder would be reused by a future row that
        // happened to reuse the id, and would draw the old playlist's collage.
        Map<String, PlaylistRow> live = new HashMap<String, PlaylistRow>();
        for (Playlist p : lists.playlists()) {
            PlaylistRow held = playlistRows.get(p.id);
            View row;
            if (held != null && playlistRowSignature(p).equals(held.signature)) {
                // THE SAME PICTURE. Reuse the views, and with them the collage's
                // up to four decodes -- which is the whole cost of this section.
                // Only the state text is rewritten, and refreshPlaylistRowVisual
                // does exactly that: subtitle, wash, switch, spoken state.
                row = held.row;
                refreshPlaylistRowVisual(held, p, status, now);
            } else {
                row = playlistRow(p, lib, status, now);
                held = playlistRows.get(p.id);
            }
            if (held != null) live.put(p.id, held);
            section.addView(row);
        }
        playlistRows.clear();
        playlistRows.putAll(live);
        playlistsSignature = playlistsSignature();
    }

    /**
     * The first list item: a fixed leading affordance, not a playlist, so it is
     * not part of any ordering. Creating and opening the editor is one tap,
     * because a New row that only announced itself would be a row that does
     * nothing.
     *
     * <p><b>Neutral, not accent.</b> It is the same weight of chrome as the
     * gallery's own {@code ＋} add tile and the rest of the section: accent is
     * the app's "this is the live thing" colour, and spending it on a row that
     * merely creates an empty playlist makes the one row nothing rotates on the
     * loudest thing in the section. The {@code ＋} and the dashed plate are the
     * grid's own add treatment, so the two add affordances read as one family.
     */
    private View newPlaylistRow() {
        TextView row = text(activity.getString(R.string.add_photo_glyph) + "  "
                + activity.getString(R.string.gallery_playlist_new_row), 13.5f);
        row.setTextColor(0x99FFFFFF); // --ink, dimmed, exactly as addCell's glyph
        row.setTypeface(medium());
        row.setGravity(Gravity.CENTER);
        row.setPadding(activity.dp(12), activity.dp(12),
                activity.dp(12), activity.dp(12));
        row.setBackground(new DashBorder(activity.dp(1), activity.dp(4),
                0x57FFFFFF, activity.dp(11)));
        row.setContentDescription(activity.getString(R.string.gallery_playlist_new));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playlistsNavigator == null) {
                    Toast.makeText(activity, R.string.toast_playlists_unavailable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String id = PlaylistScreen.create(activity);
                if (id != null) openEditor(id);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = activity.dp(GRID_GAP_DP);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * One playlist row: {@code [collage] name · trigger · screens [✎] │ [switch]}.
     *
     * <p>The label leads and the controls trail, which is
     * {@link ModalSheet#switchRow}'s convention: the right edge is where a hand
     * reaches for a control, and a leading control reads as a second label.
     *
     * <p><b>The row is the toggle; the switch is a mark, not a target.</b> The
     * pencil is its own live 38dp target so configure and activate never share a
     * hit area, and a hairline divider separates that configure half from the
     * state half. And the state mark is a <b>switch, not a radio</b>: the
     * interaction really is on/off (tapping the active row clears the flag, and
     * zero selected is legal), so the radio's filled dot was a lie about what a
     * tap does. Enabling stands the others down -- the model enforces one -- and
     * steps once so the wallpaper changes in the moment. See the call site for
     * why the exclusivity does not earn a radio's clothes.
     */
    private View playlistRow(final Playlist p, Library lib, PlaylistStatus status, long now) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10));

        row.addView(collage(p, lib), collageParams());

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(p.displayName(), 14);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text("", 12);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // The pencil: a MARK, not a target. It sits inside the row's left half,
        // which opens the editor, so giving it its own listener would be a second
        // target for one action -- and it would be the only part of the left half
        // that behaves differently from the rest of it, which is exactly what a
        // divider is there to prevent. See playlistRow's shape.
        TextView edit = new TextView(activity);
        edit.setText(activity.getString(R.string.gallery_playlist_edit_glyph));
        edit.setTextSize(15);
        edit.setTextColor(0xCCFFFFFF);
        edit.setGravity(Gravity.CENTER);
        edit.setBackground(plain(0x14FFFFFF, activity.dp(9)));
        edit.setClickable(false);
        edit.setFocusable(false);
        edit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                activity.dp(38), activity.dp(38));
        editLp.leftMargin = activity.dp(10);
        edit.setLayoutParams(editLp);
        row.addView(edit);

        // The state half: divider and switch in ONE target. It is a container
        // rather than the switch itself so that the gap between the divider and
        // the track is part of the toggle's hit area — a bare switch leaves a
        // strip to its left where a tap would fall through to the row and open
        // the editor, which is the one place a mis-hit is worst because the two
        // halves do different things.
        LinearLayout toggleZone = new LinearLayout(activity);
        toggleZone.setOrientation(LinearLayout.HORIZONTAL);
        toggleZone.setGravity(Gravity.CENTER_VERTICAL);
        toggleZone.setClickable(true);
        toggleZone.setFocusable(true);
        toggleZone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // By id, not the captured Playlist: the row can be toggled more
                // than once between section builds, and a stale active flag would
                // make the second tap repeat the first instead of reversing it.
                togglePlaylist(p.id);
            }
        });

        // The divider: a sibling between configure and state, so it stays put
        // when the switch flips and neither neighbour draws it. A hairline view,
        // not a "|" glyph -- see DIVIDER_COLOUR. It lives INSIDE the toggle zone,
        // so it is the boundary the eye reads and the tap zones agree on.
        View divider = new View(activity);
        divider.setBackgroundColor(DIVIDER_COLOUR);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                activity.dp(DIVIDER_WIDTH_DP), activity.dp(DIVIDER_HEIGHT_DP));
        dividerLp.leftMargin = activity.dp(10);
        dividerLp.rightMargin = activity.dp(10);
        divider.setLayoutParams(dividerLp);
        toggleZone.addView(divider);

        // The control: A SWITCH, NOT A RADIO.
        //
        // The interaction is on/off -- tapping the state zone turns it off, and
        // zero selected is legal -- so a radio's filled dot lies about what a tap
        // does: a radio cannot be deselected, and it reads "one of these" for a
        // row whose whole behaviour is flip. The exclusivity (only one playlist
        // rotates) is not a radio's rule here, it is a consequence the model
        // enforces -- Playlists.withActive stands the others down -- so it
        // belongs on the model, not on the control's clothes. This is the app's
        // own precedent: ModalSheet.switchRow's javadoc says the platform Switch
        // "has the shape, the motion and the state channel users and screen
        // readers already know", and it is "a mark, not a second tap target".
        // That is exactly this: the zone is the toggle, the switch only reports.
        //
        // The two-switches-on-at-once risk is closed by WHEN the stand-down is
        // applied: togglePlaylist writes through PlaylistScreen.setActive
        // (which commits the whole playlists blob) and only then repaints every
        // row, so the switch that went off and the one that went on move in the
        // same synchronous pass -- no frame shows two on.
        android.widget.Switch control = new android.widget.Switch(activity);
        control.setShowText(false);
        control.setClickable(false);
        control.setFocusable(false);
        control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        control.setTrackTintList(new android.content.res.ColorStateList(
                new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { SWITCH_TRACK_ON, SWITCH_TRACK_OFF }));
        control.setThumbTintList(new android.content.res.ColorStateList(
                new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { SWITCH_THUMB_ON, SWITCH_THUMB_OFF }));
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleZone.addView(control, controlLp);

        // The zone gets the row's trailing padding, so the switch is not flush to
        // the row's edge while the label is padded on the left, and a min width of
        // 48dp so the target meets the platform minimum whatever the track
        // measures (AGENTS.md: touch targets are >= 48dp).
        LinearLayout.LayoutParams zoneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        zoneLp.leftMargin = activity.dp(4);
        toggleZone.setMinimumWidth(activity.dp(48));
        row.addView(toggleZone, zoneLp);

        final PlaylistRow holder = new PlaylistRow(row, name, sub, control, toggleZone);
        holder.signature = playlistRowSignature(p);
        playlistRows.put(p.id, holder);
        refreshPlaylistRowVisual(holder, p, status, now);

        // The row is the EDIT half. Tapping the collage, the name, the subtitle
        // or the pencil opens this playlist's editor; only the zone right of the
        // divider flips it. Two halves, two actions, one divider -- and the
        // divider is the boundary in both the drawing and the hit testing, so
        // what the eye reads is what the finger gets.
        row.setClickable(true);
        row.setContentDescription(activity.getString(
                R.string.gallery_playlist_edit, p.displayName()));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(p.id);
            }
        });
        return row;
    }

    /**
     * What a playlist row is built from, as one comparable string.
     *
     * <p>Exactly the fields the row DRAWS, and deliberately not {@code
     * lastAdvancedAt}: that moves on every rotation step, so including it would
     * make the signature differ on every tick and the reuse below would never
     * fire. The time-dependent text it feeds is rewritten by
     * {@link #refreshPlaylistRowVisual}, which is called either way.
     *
     * <p>Membership is in ORDER, because the collage draws the first four in
     * order: a reorder changes what the collage shows and must rebuild it, and a
     * set comparison would call that unchanged.
     */
    private static String playlistRowSignature(Playlist p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.name).append(' ');
        for (String id : p.itemIds) sb.append(id).append('');
        sb.append(' ').append(p.trigger).append(' ');
        for (Surface s : p.surfaces) sb.append(s.name()).append('');
        sb.append(' ').append(p.shuffle).append(' ').append(p.active);
        return sb.toString();
    }

    /** The row's fill and border: the accent wash while it rotates, a hairline otherwise. */
    private GradientDrawable playlistRowBackground(boolean rotating) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(activity.dp(11));
        if (rotating) {
            // Stroke first, then colour: setStroke clears the fill, so a colour
            // set before it would be thrown away (see AGENTS.md).
            bg.setStroke(activity.dp(2), PhotoTile.ACCENT);
            bg.setColor(0x337FD1C1);
        } else {
            bg.setStroke(activity.dp(1), HAIRLINE);
            bg.setColor(Color.TRANSPARENT);
        }
        return bg;
    }

    /**
     * Repaint one playlist row's state without rebuilding the section.
     *
     * <p>Called for every row on a toggle — at most two of them change — and it is
     * the reason the collages are not decoded again. The subtitle, the wash, the
     * radio and the spoken state are one fact at four distances and are written
     * together so the row cannot disagree with itself.
     */
    private void refreshPlaylistRowVisual(PlaylistRow holder, Playlist p,
                                          PlaylistStatus status, long now) {
        boolean rotating = status.playlist == p;
        holder.name.setText(p.displayName());
        if (rotating) {
            holder.sub.setText(activity.getString(R.string.playlists_row_rotating,
                    PlaylistScreen.surfaces(activity, p),
                    PlaylistScreen.nextChangeLabel(activity, p, status, now)));
            holder.sub.setTextColor(0xBDD6D6D6);
        } else {
            holder.sub.setText(PlaylistScreen.summary(activity, p));
            holder.sub.setTextColor(DIM);
        }
        holder.row.setBackground(playlistRowBackground(rotating));
        holder.control.setChecked(rotating);
        // The ZONE speaks, not the switch: the switch is
        // IMPORTANT_FOR_ACCESSIBILITY_NO so that a screen reader hears one control
        // per half of the row rather than the mark and its container both. So the
        // state has to be in the zone's description, or the row's two halves would
        // be one silent button and one button with no state.
        holder.toggleZone.setContentDescription(activity.getString(
                R.string.gallery_playlist_control_talk, p.displayName(),
                activity.getString(rotating
                        ? R.string.playlists_state_rotating
                        : R.string.playlists_state_idle)));
        holder.row.setContentDescription(activity.getString(R.string.playlists_talk,
                p.displayName(), holder.sub.getText(),
                activity.getString(rotating
                        ? R.string.playlists_state_rotating
                        : R.string.playlists_state_idle)));
    }

    /**
     * Start or stop this playlist rotating.
     *
     * <p>Turning it on stands the others down and steps once, so the photo on
     * screen changes in the moment. Turning it off touches only
     * {@link Playlists#KEY} — never the index — so the photo on screen stays put;
     * that is what makes "stop" different from "pick another photo". Both go
     * through {@link PlaylistScreen#setActive}, which owns the two-blob commit.
     *
     * <p>The grid's active ring and the strip's follow the step, because the
     * selection moved exactly as if the user had tapped a photo.
     */
    private void togglePlaylist(final String playlistId) {
        Playlists current = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        Playlist p = current.find(playlistId);
        if (p == null) {
            // Deleted while the section was up. Rebuild it so the row leaves.
            refreshPlaylistsSection();
            return;
        }
        boolean turningOn = !p.active;
        String before = AppState.load(activity.prefs, activity.getFilesDir())
                .library.activeId();
        PlaylistScreen.setActive(activity, playlistId, turningOn);
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        String after = lib.activeId();
        Playlists lists = Playlists.parse(activity.prefs.getString(Playlists.KEY, null));
        long now = System.currentTimeMillis();
        PlaylistStatus status = PlaylistStatus.of(lists, lib, now);
        for (Map.Entry<String, PlaylistRow> e : playlistRows.entrySet()) {
            Playlist rowPlaylist = lists.find(e.getKey());
            if (rowPlaylist != null) {
                refreshPlaylistRowVisual(e.getValue(), rowPlaylist, status, now);
            }
        }
        refreshHeaderIndicator();
        if (!TextUtils.equals(before, after)) {
            setGridRing(before, false);
            setGridRing(after, true);
            drawStripRing(before, false);
            drawStripRing(after, true);
        }
    }

    /** Repaint one grid cell's active ring in place, because rotation moved it. */
    private void setGridRing(String id, boolean on) {
        if (id == null) return;
        PickedCell mark = cellMarks.get(id);
        if (mark == null) return;
        mark.active = on;
        mark.tile.setActive(on);
        mark.cell.setContentDescription(
                stateDescription(mark.item, on, picked.contains(id), mark.archived));
    }

    /**
     * The row's leading mark: a playlist's artwork in a 2×2 block of the app's
     * own tile.
     *
     * <p>Four tiles at {@link #COLLAGE_TILE_DP}, or one {@link #COLLAGE_DP} tile
     * when there is one photo or none — a lone photo in a 2×2 would be three dead
     * squares. More than four shows the first three and a {@code +N} cell, where
     * {@code N} is what is <i>not</i> shown; that is the only reading that does
     * not overstate. Every tile decodes through {@link PhotoTile#show}, off the UI
     * thread, so a photo already decoded elsewhere is cheap and a never-seen one is
     * decoded once at this size.
     *
     * <p>Missing members are skipped: a hole cannot be drawn, and claiming it can
     * would put a blank square where a photograph should be.
     */
    private View collage(Playlist p, Library lib) {
        List<LibraryItem> items = new ArrayList<LibraryItem>();
        for (String id : p.itemIds) {
            LibraryItem item = lib.find(id);
            if (item != null) items.add(item);
        }
        FrameLayout holder = new FrameLayout(activity);
        holder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        holder.setContentDescription(activity.getString(
                R.string.gallery_playlist_collage_talk, items.size()));

        if (items.size() <= 1) {
            PhotoTile tile = new PhotoTile(activity, activity.dp(COLLAGE_DP),
                    activity.dp(3));
            if (!items.isEmpty()) tile.show(items.get(0));
            holder.addView(tile, new FrameLayout.LayoutParams(
                    activity.dp(COLLAGE_DP), activity.dp(COLLAGE_DP)));
            return holder;
        }

        int shown = items.size() > 4 ? 3 : items.size();
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int at = r * 2 + c;
                View cell;
                if (at < shown) {
                    PhotoTile tile = new PhotoTile(activity,
                            activity.dp(COLLAGE_TILE_DP), activity.dp(3));
                    tile.show(items.get(at));
                    cell = tile;
                } else if (at == 3 && items.size() > 4) {
                    cell = moreCell(items.size() - 3);
                } else {
                    cell = new View(activity);
                    cell.setBackground(plain(0xFF181D23, activity.dp(3)));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        activity.dp(COLLAGE_TILE_DP), activity.dp(COLLAGE_TILE_DP));
                if (c > 0) lp.leftMargin = activity.dp(COLLAGE_GAP_DP);
                line.addView(cell, lp);
            }
            if (r > 0) {
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lineLp.topMargin = activity.dp(COLLAGE_GAP_DP);
                line.setLayoutParams(lineLp);
            }
            grid.addView(line);
        }
        holder.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    /** The collage's overflow cell: how many photos are not drawn. */
    private View moreCell(int count) {
        TextView more = text(activity.getString(R.string.gallery_playlist_more, count), 10);
        more.setTextColor(0x8CFFFFFF); // --dim
        more.setGravity(Gravity.CENTER);
        more.setBackground(plain(0xFF0B0F12, activity.dp(3)));
        return more;
    }

    private LinearLayout.LayoutParams collageParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                activity.dp(COLLAGE_DP), activity.dp(COLLAGE_DP));
        lp.rightMargin = activity.dp(12);
        return lp;
    }

    /** The app's 600-weight, which Android names {@code sans-serif-medium}. */
    private Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

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
        // Patched, not rebuilt: adding photos to a playlist or making one changes
        // the section and nothing the grid draws. Rebuilding the grid for it
        // re-decoded every thumbnail, which is the flash this change removes.
        clearPicks();
        refreshPlaylistsSection();
        refreshHeaderIndicator();
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
