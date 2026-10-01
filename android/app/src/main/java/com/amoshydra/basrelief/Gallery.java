package com.amoshydra.basrelief;

import android.app.AlertDialog;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import com.amoshydra.basrelief.core.store.SortOrder;
import com.amoshydra.basrelief.core.store.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The gallery: every photo in the app, reached two ways.
 *
 * <p><b>Browsing is a filmstrip on the render's edge, not a takeover.</b> The
 * strip is a sibling of the sheet in the studio's root frame rather than a child
 * of it, so it survives the collapse and switching a photo is one tap while the
 * relief keeps moving behind it. A full-screen list would hide the product at
 * exactly the moment the user is choosing it, and choosing a framing is the whole
 * reason the studio and the gallery share a screen at all.
 *
 * <p><b>It rides on whatever is beneath it.</b> The strip's offset from the
 * bottom is the top of the tallest thing under it, recomputed on every layout
 * pass, rather than a fraction of the screen. It used to be a fixed 40%, which
 * floated in mid-air the moment the sheet was shorter than that and sat on top of
 * the sheet when it was taller — and the sheet's height is animated and capped at
 * its content, so there is no fraction that is right.
 *
 * <p><b>Position is layout, never a transform.</b> A sibling drawn over a
 * {@code GLSurfaceView} and moved with {@code translationY} may be laid out,
 * attached, opaque, {@code isShown()} and still absent from the display — which is
 * b/7179570, and why the sheet positions itself with {@code LayoutParams}. The
 * strip's {@code bottomMargin} is a layout change for the same reason: layout runs
 * a traversal and repaints the region, a transform does not.
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
    /** The gap between the strip and whatever it is sitting on. */
    private static final int STRIP_GAP_DP = 10;
    private static final int GRID_COLUMNS = 3;
    private static final int GRID_GAP_DP = 8;
    private static final int GRID_PAD_DP = 14;
    /** How tall a grid cell's picture is. Fixed, so the rows share a baseline. */
    private static final int CELL_DP = 96;
    /** The depth chip. A dot, because a chip that needs reading is a label. */
    private static final int CHIP_DP = 10;

    private static final String STRIP_TAG = "basrelief-strip";
    private static final String GRID_TAG = "basrelief-grid";

    private final MainActivity activity;
    private final FrameLayout root;

    /** The strip. Null when the gallery is not up. */
    private View strip;
    private FrameLayout.LayoutParams stripLp;
    private int stripInsetBottom = 0;
    /** The margin last written, so a re-layout with nothing to change stops. */
    private int stripAppliedMargin = -1;
    private ViewTreeObserver.OnGlobalLayoutListener stripListener;

    /** The grid. Null when it is not up. */
    private View grid;

    /**
     * The photos picked for a many-at-once action, in the order they were picked.
     *
     * <p>Insertion-ordered rather than a sorted set, because the order is the
     * user's and re-sorting a selection is its own surprise. Deliberately not
     * persisted: it is a gesture in progress, and a selection that survived a
     * restart would name photographs the user has forgotten picking.
     */
    private final Set<String> picked = new LinkedHashSet<String>();

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
     * The studio's Gallery button: the grid, on top of the filmstrip.
     *
     * <p>The grid is the right answer to this button, because the button means
     * "manage my photographs" — pick several, rename one, delete one — and none of
     * those fit in a 46px tile. Browsing is the strip's job and the strip is
     * already on the render's edge, so opening the grid over it is the takeover
     * taken deliberately rather than by default.
     *
     * <p>The strip is installed first, so the button is never a dead control even
     * before the studio has installed it at launch.
     */
    void show() {
        installStrip();
        showGrid();
    }

    /**
     * Put the filmstrip on the render's edge.
     *
     * <p>Idempotent: the strip is studio furniture rather than a dialog, so it is
     * installed once and stays — through the sheet opening, the sheet collapsing
     * and the grid being dismissed. Rebuilding on every press would restart every
     * thumbnail decode for nothing, and a strip that appeared and vanished with a
     * button would be an overlay, which is the thing it exists to stop being.
     *
     * <p>Rebuilt — not patched — whenever the index changes, because the ring that
     * says which photograph is on screen is on every tile and the order may have
     * moved.
     */
    void installStrip() {
        if (strip != null) return;
        strip = buildStrip();
        root.addView(strip);
        // Ask for the insets now that it is attached. A listener set before
        // attachment is never called, and requestApplyInsets on a detached view
        // finds no ViewRootImpl to dispatch through — which is how a strip ends up
        // laid out under a gesture pill that takes the touch before the strip ever
        // sees it, and every tile reads as present and dead.
        strip.requestApplyInsets();
        layoutStrip();
        listenForLayout();
    }

    /** Re-read the index and put the strip back. */
    private void refreshStrip() {
        if (strip == null) return;
        root.removeView(strip);
        strip = null;
        stripAppliedMargin = -1;
        installStrip();
    }

    /**
     * Take the gallery down: the strip, the grid, and any half-finished
     * selection.
     *
     * <p>The layout listener goes with it. One that outlives its views keeps the
     * gallery reachable from a hierarchy that no longer shows it, which is the
     * kind of leak that only shows up much later.
     */
    void dismiss() {
        stopListeningForLayout();
        if (strip != null) {
            root.removeView(strip);
            strip = null;
            stripLp = null;
            stripAppliedMargin = -1;
        }
        dismissGrid();
        picked.clear();
        copySourceId = null;
    }

    // ------------------------------------------------------------- the filmstrip

    private View buildStrip() {
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        Library lib = state.library;
        String activeId = lib.activeId();

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = activity.dp(GRID_PAD_DP);
        row.setPadding(pad, activity.dp(6), pad, activity.dp(6));

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

        FrameLayout host = new FrameLayout(activity);
        host.setTag(STRIP_TAG);
        host.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        host.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                stripInsetBottom = bottomInset(insets);
                layoutStrip();
                return insets;
            }
        });
        // Bottom-anchored with a margin this class owns. The offset is written by
        // layoutStrip() rather than here, because what the strip sits on has not
        // been measured at this point.
        stripLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        host.setLayoutParams(stripLp);
        return host;
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
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(thumbBackground(item, isActive));
        // A rounded background plus clipToOutline is the framework's own rounded
        // thumbnail. The alternative is a second bitmap pass per photograph, and
        // there is no view recycling in this app, so that is a per-photo cost for
        // a cosmetic edge.
        thumb.setClipToOutline(true);
        if (!item.isBundled()) {
            File f = PrefFiles.resolve(activity.getFilesDir(), item.imageName);
            if (f != null) Io.decodePreview(activity, f, thumb, size);
        }
        // The name is what makes an unlabelled tile a photograph rather than a
        // colour, and it is the only thing a screen reader has to read.
        thumb.setContentDescription(activity.getString(R.string.gallery_thumb_use,
                item.displayName()));
        thumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(item.id);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(activity.dp(3), 0, activity.dp(3), 0);
        thumb.setLayoutParams(lp);
        return thumb;
    }

    /**
     * The background behind one thumbnail: rounded, and ringed when this is the
     * photograph on screen.
     *
     * <p>The bundled demo gets its own fill. It has no file to decode, so a
     * transparent tile would be a hole in the strip — and a hole where the
     * photograph on screen should be is a claim the row cannot back up.
     */
    private GradientDrawable thumbBackground(LibraryItem item, boolean isActive) {
        int fill = item.isBundled() ? 0xFF4A423A : 0xFF2E3B44;
        int ring = isActive ? 0xFFFFFFFF : 0x00000000;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(activity.dp(11));
        d.setStroke(activity.dp(isActive ? 2 : 1), ring);
        return d;
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
                ? new DashBorder(activity.dp(1), activity.dp(4), 0x55FFFFFF)
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

    // ------------------------------------------------------- the strip's position

    /**
     * Sit the strip on top of whatever is beneath it.
     *
     * <p>Measured, not assumed: the margin is the gap, plus the bottom inset,
     * plus the height of the tallest view under the strip. The sheet is animated
     * by rewriting its own {@code LayoutParams} and is capped at its content
     * height, so any fraction of the screen is wrong at one end or the other.
     *
     * <p>A {@code bottomMargin} and a re-layout, never a translation. See the
     * class note: a transformed sibling over a {@code GLSurfaceView} can be
     * placed correctly, opaque, attached and still not composited.
     */
    private void layoutStrip() {
        if (strip == null || stripLp == null) return;
        int margin = activity.dp(STRIP_GAP_DP) + stripInsetBottom + underlayHeight();
        if (margin == stripAppliedMargin) return; // converged; no second layout
        stripAppliedMargin = margin;
        stripLp.bottomMargin = margin;
        strip.setLayoutParams(stripLp);
    }

    /**
     * How much of the screen the strip has to clear.
     *
     * <p>Every <i>bottom-anchored</i> child of the root except this class's own
     * views, which is the sheet when it is open and the collapsed affordance when
     * it is not — the two states the strip is supposed to survive either way.
     * Measuring beats asking the sheet for its height: the sheet is not this
     * class's to instrument, and its height is exactly the thing that is animated.
     *
     * <p>Anchored-to-bottom is the test, and it matters. A full-height child is
     * the backdrop or a takeover, not furniture to sit on, and counting one would
     * push the strip off the top of the screen entirely — a strip that is
     * correctly measured and completely invisible.
     */
    private int underlayHeight() {
        int rootHeight = root.getHeight();
        if (rootHeight <= 0) return 0;
        int top = rootHeight;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null || child == strip) continue;
            if (STRIP_TAG.equals(child.getTag()) || GRID_TAG.equals(child.getTag())) continue;
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof android.opengl.GLSurfaceView) continue; // the backdrop
            if (!isBottomAnchored(child)) continue;
            if (child.getTop() < top) top = child.getTop();
        }
        return Math.max(0, rootHeight - top);
    }

    private boolean isBottomAnchored(View child) {
        android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return false;
        return (((FrameLayout.LayoutParams) lp).gravity & Gravity.BOTTOM) != 0;
    }

    /**
     * Re-place the strip whenever anything beneath it is laid out.
     *
     * <p>A global layout listener rather than one on the sheet, because the sheet
     * is not this class's: the studio animates it by rewriting its own params, and
     * a global pass sees every frame of that without knowing the sheet exists.
     */
    private void listenForLayout() {
        if (stripListener != null) return;
        stripListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                layoutStrip();
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(stripListener);
    }

    private void stopListeningForLayout() {
        if (stripListener == null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(stripListener);
        stripListener = null;
    }

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
        grid = buildGrid();
        root.addView(grid);
        grid.requestApplyInsets();
    }

    private void dismissGrid() {
        if (grid == null) return;
        root.removeView(grid);
        grid = null;
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
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        final Library lib = state.library;
        final List<LibraryItem> rows = lib.sorted();
        String activeId = lib.activeId();

        final int pad = activity.dp(GRID_PAD_DP);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF7080A0D);
        panel.setPadding(pad, pad, pad, pad);
        panel.setTag(GRID_TAG);
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
        TextView title = text(activity.getString(R.string.gallery_title), 18);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        header.addView(compact(activity.getString(R.string.gallery_sort) + ": "
                + lib.sort().label(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu();
            }
        }));
        header.addView(compact(activity.getString(R.string.gallery_playlists),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPlaylists();
                    }
                }));
        panel.addView(header);

        // The count and the hint share one line: they are the same question at two
        // moments, and two lines would be one too many on a screen this size.
        panel.addView(selectionLine());

        // ---- the photographs
        ScrollView scroll = new ScrollView(activity);
        LinearLayout cells = new LinearLayout(activity);
        cells.setOrientation(LinearLayout.VERTICAL);
        for (int start = 0; start < rows.size(); start += GRID_COLUMNS) {
            cells.addView(cellRow(rows, start, activeId));
        }
        cells.addView(addCellRow(rows.size()));
        scroll.addView(cells, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- the many-at-once actions
        panel.addView(actionRow(
                activity.getString(R.string.gallery_copy_settings),
                activity.getString(R.string.gallery_paste_onto_selected),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCopySourcePicker();
                    }
                },
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pasteOnto(picksOrToast());
                    }
                }));
        panel.addView(actionRow(
                activity.getString(R.string.gallery_add_to_playlist),
                activity.getString(R.string.action_done),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPlaylistPicker(null);
                    }
                },
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Done is the way out, not a save. Every action here wrote
                        // the moment it was tapped, so there is nothing to commit
                        // and a button that said Save would imply otherwise.
                        dismissGrid();
                    }
                }));
        return panel;
    }

    /**
     * One row of {@link #GRID_COLUMNS} cells, the last one padded with blanks.
     *
     * <p>Fixed rows rather than a wrapping container, because a grid that measures
     * itself against variable-width children produces rows of differing heights,
     * and a gallery whose photographs are not on a baseline reads as broken
     * before it is read.
     */
    private LinearLayout cellRow(List<LibraryItem> rows, int start, String activeId) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < GRID_COLUMNS; i++) {
            int at = start + i;
            if (at < rows.size()) {
                LibraryItem item = rows.get(at);
                line.addView(cell(item, item.id.equals(activeId)));
            } else {
                View blank = new View(activity);
                LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                blankLp.leftMargin = activity.dp(GRID_GAP_DP);
                blank.setLayoutParams(blankLp);
                line.addView(blank);
            }
        }
        return line;
    }

    private LinearLayout addCellRow(int taken) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = taken % GRID_COLUMNS; i > 0; i--) {
            View blank = new View(activity);
            LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            blankLp.leftMargin = activity.dp(GRID_GAP_DP);
            blank.setLayoutParams(blankLp);
            line.addView(blank);
        }
        TextView add = text(activity.getString(R.string.add_photo_glyph), 22);
        add.setTextColor(0x99FFFFFF);
        add.setGravity(Gravity.CENTER);
        add.setBackground(new DashBorder(activity.dp(1), activity.dp(4), 0x55FFFFFF));
        add.setContentDescription(activity.getString(R.string.gallery_add_photo));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhoto();
            }
        });
        add.setLayoutParams(new LinearLayout.LayoutParams(activity.dp(CELL_DP),
                activity.dp(CELL_DP)));
        line.addView(add);
        return line;
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
     * One cell: the picture, a depth chip that is present or absent, the name,
     * and the menu.
     *
     * <p>Tap uses the photograph; a hold picks it for a many-at-once action. Two
     * gestures rather than one because the two answers are different, and
     * combining them would make picking several impossible to discover and
     * impossible to escape.
     */
    private View cell(final LibraryItem item, final boolean isActive) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cellLp.leftMargin = activity.dp(GRID_GAP_DP);
        cell.setLayoutParams(cellLp);

        FrameLayout picture = new FrameLayout(activity);
        // Ringed when it is on screen OR picked. Two questions, one mark: a cell
        // that is the wallpaper and a cell queued for a paste are not the same
        // fact, but both are "this one is marked", and a second colour for the
        // second question is a legend nobody learns.
        picture.setBackground(thumbBackground(item, isActive || picked.contains(item.id)));
        picture.setClipToOutline(true);
        picture.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(CELL_DP)));
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(thumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        View chip = depthChip(item);
        picture.addView(chip, chipParams());
        picture.addView(menuButton(item), menuParams());
        if (!item.isBundled()) {
            File f = PrefFiles.resolve(activity.getFilesDir(), item.imageName);
            if (f != null) watchForEmbeddedDepth(f, item, thumb, chip);
        }
        cell.addView(picture);

        TextView name = text(item.displayName(), 12);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, activity.dp(4), 0, 0);
        cell.addView(name);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(item.id);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!picked.remove(item.id)) picked.add(item.id);
                // Rebuilt rather than patched: every cell carries its own picked
                // state and its own chip, and a partial update is a second place
                // that has to agree with the set. Posted, because tearing a view
                // out of the hierarchy from inside its own touch dispatch is the
                // kind of thing that works until it does not.
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        rebuildGrid();
                    }
                });
                return true;
            }
        });
        cell.setContentDescription(activity.getString(R.string.gallery_thumb_use,
                item.displayName()));
        return cell;
    }

    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(CHIP_DP), activity.dp(CHIP_DP), Gravity.TOP | Gravity.END);
        lp.setMargins(0, activity.dp(5), activity.dp(5), 0);
        return lp;
    }

    private FrameLayout.LayoutParams menuParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(28), activity.dp(28), Gravity.TOP | Gravity.START);
        lp.setMargins(activity.dp(2), activity.dp(2), 0, 0);
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
        chip.setBackground(plain(0xFF7FD1C1, activity.dp(CHIP_DP)));
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
     */
    private View menuButton(final LibraryItem item) {
        TextView menu = text(activity.getString(R.string.gallery_more_glyph), 15);
        menu.setGravity(Gravity.CENTER);
        // A plate behind the glyph, because a dark mark on a dark photograph is
        // a control that is present and unreadable.
        menu.setBackground(plain(0x99000000, activity.dp(14)));
        menu.setContentDescription(activity.getString(R.string.gallery_more_glyph));
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRowMenu(item);
            }
        });
        return menu;
    }

    /**
     * Reveal the chip for a photograph that carries its depth map inside the PNG.
     *
     * <p>{@code hasDepth()} only knows about a separate file, so without this the
     * chip would be absent for a photograph that is visibly in relief — and an
     * absent chip is a claim, not a lack of one. Read from the same pass that
     * decodes the thumbnail, because the answer is inside the same bytes and a
     * grid that asked separately would read every photograph twice on a list with
     * no view recycling.
     */
    private void watchForEmbeddedDepth(final File file, final LibraryItem item,
                                       final ImageView thumb, final View chip) {
        final boolean hasFile = item.hasDepth();
        Io.decodePreview(activity, file, thumb, activity.dp(CELL_DP),
                new Io.EmbeddedDepthListener() {
                    @Override
                    public void onFound(boolean embedded) {
                        if (embedded && !hasFile) chip.setVisibility(View.VISIBLE);
                    }
                });
    }

    private LinearLayout actionRow(String left, String right,
                                   View.OnClickListener onLeft,
                                   View.OnClickListener onRight) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, activity.dp(6), 0, 0);
        row.addView(compact(left, onLeft), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(compact(right, onRight), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * The line that says how many photographs are picked, or how to pick several.
     *
     * <p>It changes text rather than appearing and disappearing, so the row it
     * occupies does not jump under a finger that is mid-gesture.
     */
    private TextView selectionLine() {
        String s = picked.isEmpty()
                ? activity.getString(R.string.gallery_selection_hint)
                : activity.getString(R.string.gallery_selection_count, picked.size());
        TextView line = text(s, 12);
        line.setTextColor(0x99FFFFFF);
        line.setPadding(0, activity.dp(6), 0, activity.dp(2));
        return line;
    }

    private void rebuildGrid() {
        if (grid == null) return;
        root.removeView(grid);
        grid = buildGrid();
        root.addView(grid);
        grid.requestApplyInsets();
    }

    /**
     * The index changed: re-read it into both surfaces.
     *
     * <p>One name for it, because the strip and the grid are the same list read
     * twice and a sort change that redraws one of them leaves the other showing
     * an order the index no longer holds. The grid call no-ops when the grid is
     * down, so a strip-only session pays nothing for the seam.
     */
    private void reindex() {
        rebuildGrid();
        refreshStrip();
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
        LibraryStore.select(activity.prefs, id);
        // The sheet repopulates itself through the guarded path, so a switch does
        // not write: refreshSliders sets syncingZoom around every setProgress
        // precisely because setProgress fires the change listener, and without the
        // guard this would rewrite the photograph just left with the values it
        // happened to be showing.
        activity.refreshStatus();
        activity.refreshSliders();
        activity.applyFiles();
        dismissGrid();
        // The strip's ring moves with the selection, so it is rebuilt rather than
        // patched — and the grid closes, because the render is the thing the user
        // came for and a photograph chosen from a list should leave the list
        // behind it.
        refreshStrip();
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

    private void showSortMenu() {
        final SortOrder[] order = SortOrder.values();
        String[] labels = new String[order.length];
        for (int i = 0; i < order.length; i++) labels[i] = order[i].label();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_sort)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        LibraryStore.setSort(activity.prefs, order[which]);
                        reindex();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
    private void showCopySourcePicker() {
        final List<LibraryItem> items = AppState.load(activity.prefs,
                activity.getFilesDir()).library.sorted();
        if (items.isEmpty()) return;
        String takes = activity.getString(R.string.gallery_copy_takes);
        String on = surfaceLabel();
        String[] rows = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            rows[i] = activity.getString(R.string.gallery_copy_source_row,
                    items.get(i).displayName(), on, takes);
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_copy_from)
                .setItems(rows, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        copyFrom(items.get(which));
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
     * <p>The bundled row is never a target. It has no file to recreate it from, so
     * {@code Library.remove} refuses it — but refusing at the data layer is a back
     * stop, not a reason to offer the action, and a delete button that cannot
     * delete is a control that cannot do anything.
     */
    private List<LibraryItem> targetsFor(LibraryItem row) {
        List<LibraryItem> out = new ArrayList<LibraryItem>();
        if (picked.isEmpty()) {
            if (row != null && !row.isBundled()) out.add(row);
            return out;
        }
        Library lib = AppState.load(activity.prefs, activity.getFilesDir()).library;
        for (String id : picked) {
            LibraryItem item = lib.find(id);
            if (item != null && !item.isBundled()) out.add(item);
        }
        return out;
    }

    /** The picks, or nothing, with the reason said out loud. */
    private List<LibraryItem> picksOrToast() {
        if (picked.isEmpty()) {
            Toast.makeText(activity, R.string.toast_select_first, Toast.LENGTH_LONG).show();
            return new ArrayList<LibraryItem>();
        }
        return targetsFor(null);
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
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_delete_title)
                .setMessage(message)
                .setPositiveButton(R.string.gallery_delete_one,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                performDelete(targets, deletesActive);
                            }
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
        // through the single read path, so the bundled row is guaranteed present
        // and any row whose file has gone is dropped before the delete is derived
        // from what is actually there.
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
     */
    private void showRowMenu(final LibraryItem item) {
        if (item.isBundled()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.gallery_bundled_name)
                    .setMessage(R.string.gallery_bundled_note)
                    .setPositiveButton(R.string.action_done, null)
                    .show();
            return;
        }
        boolean many = !picked.isEmpty();
        // R-G4's four, in the design's order, with Delete last. A destructive
        // action under three others is harder to hit by accident than one at the
        // bottom of a list, and "the last thing in the menu" is the only position
        // every platform's muscle memory agrees on.
        String[] menu = {
                activity.getString(R.string.gallery_copy_settings),
                many ? activity.getString(R.string.gallery_paste_onto_selected)
                        : activity.getString(R.string.gallery_paste_onto_this),
                activity.getString(R.string.gallery_add_to_playlist),
                activity.getString(R.string.gallery_rename),
                many ? activity.getString(R.string.gallery_delete_selected)
                        : activity.getString(R.string.gallery_delete_one),
        };
        new AlertDialog.Builder(activity)
                .setTitle(item.displayName())
                .setItems(menu, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0: copyFrom(item); break;
                            case 1: pasteOnto(targetsFor(item)); break;
                            case 2: showPlaylistPicker(item); break;
                            case 3: rename(item); break;
                            case 4: confirmDelete(targetsFor(item)); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void rename(final LibraryItem item) {
        final EditText field = new EditText(activity);
        field.setText(item.displayName());
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_rename)
                .setView(field)
                .setPositiveButton(R.string.gallery_rename_save,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
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
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
        List<LibraryItem> targets = targetsFor(row);
        if (targets.isEmpty()) {
            Toast.makeText(activity, R.string.toast_select_first, Toast.LENGTH_LONG).show();
            return;
        }
        final Playlists lists = Playlists.parse(
                activity.prefs.getString(Playlists.KEY, null));
        final List<Playlist> existing = lists.playlists();
        String[] rows = new String[existing.size() + 1];
        for (int i = 0; i < existing.size(); i++) {
            rows[i] = activity.getString(R.string.gallery_playlist_row,
                    existing.get(i).displayName(), existing.get(i).size());
        }
        rows[existing.size()] = activity.getString(R.string.gallery_playlist_new);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_playlist_pick)
                .setItems(rows, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == existing.size()) {
                            newPlaylist(targets);
                        } else {
                            addToPlaylist(existing.get(which), targets);
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void newPlaylist(final List<LibraryItem> targets) {
        final EditText field = new EditText(activity);
        field.setHint(R.string.gallery_playlist_name);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gallery_playlist_new)
                .setView(field)
                .setPositiveButton(R.string.gallery_rename_save,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
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
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
    private Button compact(String label, View.OnClickListener listener) {
        return activity.makeButton(label, listener,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f);
    }
}
