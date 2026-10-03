package com.amoshydra.basrelief;

import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    private static final int STRIP_GAP_DP = 14;
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
    /** --dim: a header entry that is a reading rather than an action. */
    private static final int DIM = 0x8CFFFFFF;
    /** --accent: the one header entry that goes somewhere. */
    private static final int ACCENT = 0xFF7FD1C1;

    private static final String STRIP_TAG = "basrelief-strip";
    private static final String GRID_TAG = "basrelief-grid";

    /**
     * The open grid, so the strip can be ordered beneath it.
     *
     * <p>Held rather than found by tag: {@code root} also holds the sheet and the
     * pill, and the ordering has to be right at the moment of insertion, which is
     * before any tag search would be convenient.
     */
    private LinearLayout gridPanel;

    private final MainActivity activity;
    private final FrameLayout root;

    /** The strip. Null when the gallery is not up. */
    private View strip;
    private FrameLayout.LayoutParams stripLp;
    private int stripInsetBottom = 0;
    /**
     * The status-bar inset, in pixels, kept so {@link #layoutStrip} can stop the
     * strip riding up under the clock when the sheet is dragged to full height.
     *
     * <p>Separate from {@link #stripInsetBottom} because it is not placed by a
     * margin: the strip is bottom-anchored, so a top margin would push it further
     * up, and a top padding on a wrap-content view cancels itself out. The inset is
     * therefore applied twice over -- as padding for the host, and as a bound on
     * the margin -- which is what puts the host's top edge at the screen top and its
     * tiles one inset lower. See {@link #layoutStrip}.
     */
    private int stripInsetTop = 0;
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

    /**
     * Opens the apply chooser, supplied by the studio.
     *
     * <p>Same reason as {@link PhotoPicker} and {@link PlaylistsNavigator}: a
     * route to a screen this class does not own, so the owner installs it
     * rather than this class reaching into another one's internals. Applying
     * is a chooser rather than a command — no app can re-bind its own live
     * wallpaper, so the system picker is the only route and the choice of
     * screen cannot be made here — which is exactly why the call belongs to
     * the host and not to the row that asked for it. See {@link ApplyChooser}.
     */
    interface WallpaperApplier {
        void applyWallpaper();
    }

    private WallpaperApplier wallpaperApplier;

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

    /** Route the foot's Set as wallpaper to the apply chooser. */
    void setWallpaperApplier(WallpaperApplier applier) {
        this.wallpaperApplier = applier;
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
        // BELOW the grid, when one is open. Appending puts the strip last in root,
        // i.e. on top of everything -- which was invisible until something refreshed
        // the strip while the gallery was open. Changing the sort order does exactly
        // that, so choosing a sort order replaced the gallery with a filmstrip.
        //
        // The ordering is therefore a property of WHERE it goes rather than of when it
        // was built: a view whose z-order depends on whether a rebuild happened is a
        // z-order that will be wrong again.
        int below = gridPanel == null ? -1 : root.indexOfChild(gridPanel);
        if (below >= 0) {
            root.addView(strip, below);
        } else {
            root.addView(strip);
        }
        // Ask for the insets now that it is attached. A listener set before
        // attachment is never called, and requestApplyInsets on a detached view
        // finds no ViewRootImpl to dispatch through — which is how a strip ends up
        // laid out under a gesture pill that takes the touch before the strip ever
        // sees it, and every tile reads as present and dead.
        strip.requestApplyInsets();
        layoutStrip();
        listenForLayout();
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
                stripInsetTop = topInset(insets);
                // The status bar is a TOP inset on the strip, and the grid's own
                // helper is reused so the two surfaces cannot grow a second answer.
                // Padding rather than margin: the strip is bottom-anchored, and a top
                // margin would move it the wrong way. See layoutStrip for the clamp
                // that actually keeps the tiles below the clock.
                v.setPadding(0, stripInsetTop, 0, 0);
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
        // Never let the strip ride above the status bar. At full sheet height the
        // underlay is the whole screen, and the natural margin above would put the
        // tiles at y=0 behind the clock. The clamp moves the host's BOTTOM edge down,
        // which is the only direction that lowers a bottom-anchored strip's content:
        // adding margin moves it further up. contentHeight excludes the host's own
        // padding, because the padding is what the host wears, not the tiles.
        int rootHeight = root.getHeight();
        int contentHeight = strip.getHeight()
                - strip.getPaddingTop() - strip.getPaddingBottom();
        if (rootHeight > 0 && contentHeight > 0) {
            int maxMargin = rootHeight - stripInsetTop - contentHeight;
            if (margin > maxMargin) margin = maxMargin;
        }
        if (margin < 0) margin = 0;
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
        // Cleared even when there is nothing to remove: gridPanel is what installStrip
        // orders against, and a stale reference would make a later strip build insert
        // itself at an index that no longer means anything.
        gridPanel = null;
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
        gridPanel = panel;
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
                ACCENT, true, new View.OnClickListener() {
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
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        cellPx = (screen - 2 * pad - GRID_COLUMNS * activity.dp(GRID_GAP_DP))
                / GRID_COLUMNS;
        if (cellPx <= 0) {
            cellPx = activity.dp(CELL_DP_FALLBACK);
        }

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
        //
        // Set as wallpaper rides at the head of the second row: it is the one
        // action on this screen that changes what the phone itself shows, so
        // it takes the primary plate and the seat beside Done, the way out —
        // while everything else here rearranges only the gallery's own
        // bookkeeping.
        panel.addView(actionRow(
                compact(activity.getString(R.string.gallery_copy_settings),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showCopySourcePicker();
                            }
                        }),
                compact(activity.getString(R.string.gallery_paste_onto_selected),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                pasteOnto(picksOrToast());
                            }
                        })));
        panel.addView(actionRow(
                applyButton(),
                compact(activity.getString(R.string.gallery_add_to_playlist),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showPlaylistPicker(null);
                            }
                        }),
                compact(activity.getString(R.string.action_done),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // Done is the way out, not a save. Every action here wrote
                                // the moment it was tapped, so there is nothing to commit
                                // and a button that said Save would imply otherwise.
                                dismissGrid();
                            }
                        })));
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
                // A spacer, and deliberately zero-height: it exists only to hold a
                // column open so the row's cells keep their weight, and giving it a
                // height makes an empty box the size of a photograph.
                View blank = new View(activity);
                LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0,
                        0, 1f);
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
        add.setBackground(new DashBorder(activity.dp(1), activity.dp(4), 0x57FFFFFF, activity.dp(11)));
        add.setContentDescription(activity.getString(R.string.gallery_add_photo));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhoto();
            }
        });
        add.setLayoutParams(new LinearLayout.LayoutParams(cellPx, cellPx));
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
        // Square by arithmetic, not by measurement: cellPx is the column's width, so
        // using it as the height is what makes the cell a square. MATCH_PARENT width
        // and the computed height, because the two have to agree and only one of them
        // was computed.
        picture.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cellPx));
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
        // No name under the cell. The mockup draws none, and with square cells a
        // caption would be a quarter of the cell's height spent on a label -- the
        // depth chip and the selected ring already say which photograph this is.

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

    /** Top-LEFT, clear of the row menu, which is top-right. */
    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(CHIP_DP), activity.dp(CHIP_DP), Gravity.TOP | Gravity.START);
        lp.setMargins(activity.dp(6), activity.dp(6), 0, 0);
        return lp;
    }

    /**
     * The row menu's corner.
     *
     * <p>Top-RIGHT, 19dp, as the mockup draws it — it was top-LEFT at 28dp, which is
     * where the depth chip now sits. Two marks in one corner is one of them invisible,
     * and "which is it on this row" is a question a gallery must not ask.
     */
    private FrameLayout.LayoutParams menuParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                activity.dp(19), activity.dp(19), Gravity.TOP | Gravity.END);
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
        TextView menu = text(activity.getString(R.string.gallery_more_glyph), 12);
        menu.setGravity(Gravity.CENTER);
        // A plate behind the glyph, because a dark mark on a dark photograph is
        // a control that is present and unreadable. 19dp, so the glyph and its
        // target are one size rather than two.
        menu.setBackground(plain(0x99000000, activity.dp(10)));
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
        Io.decodePreview(activity, file, thumb, cellPx,
                new Io.EmbeddedDepthListener() {
                    @Override
                    public void onFound(boolean embedded) {
                        if (embedded && !hasFile) chip.setVisibility(View.VISIBLE);
                    }
                });
    }

    /**
     * One row of the foot's buttons: equal shares, any count.
     *
     * <p>Buttons rather than label-and-listener pairs, because the foot is no
     * longer all pairs — the apply button joined the second row — and a
     * builder that could only take two could not hold three without a second
     * builder drifting from this one.
     */
    private LinearLayout actionRow(Button... buttons) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, activity.dp(6), 0, 0);
        for (Button b : buttons) {
            row.addView(b, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /**
     * The apply gesture, from the grid.
     *
     * <p>{@code ModalSheet}'s button rather than the studio's {@code compact}
     * one, because the mockup's foot rows and modals are one {@code .btn}
     * family — the foot is that family's one appearance outside a modal, so
     * it is not the place to invent a second button. Primary, because
     * applying is the reason this screen exists at all: the gallery is where
     * the photographs are chosen, and applying is what puts one of them on
     * the phone.
     *
     * <p>The chooser it opens is {@link ApplyChooser}'s; this row only asks.
     */
    private Button applyButton() {
        Button apply = ModalSheet.button(activity,
                activity.getString(R.string.apply_set_as_wallpaper), true, true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyWallpaper();
            }
        });
        return apply;
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
     */
    private void reindex() {
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

    private void applyWallpaper() {
        if (wallpaperApplier == null) {
            // Same rule as the picker and the playlists route: say so rather
            // than do nothing.
            Toast.makeText(activity, R.string.toast_wallpaper_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        wallpaperApplier.applyWallpaper();
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
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
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
            // The bundled row's sheet is a note rather than a list: it cannot
            // be a target (see targetsFor), so the honest menu is the reason
            // why, with the one button that leaves.
            final ModalSheet sheet = new ModalSheet(activity, 0.88f);
            LinearLayout body = modalBody(activity.getString(R.string.gallery_bundled_name));
            body.addView(ModalSheet.subtitle(activity,
                    activity.getString(R.string.gallery_bundled_note)));
            sheet.body(body);
            sheet.footerRow(closeButton(activity.getString(R.string.action_done), sheet));
            sheet.show(activity);
            return;
        }
        final boolean many = !picked.isEmpty();
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
        LinearLayout body = modalBody(item.displayName());
        // R-G4's four, in the design's order, with Delete last. A destructive
        // action under three others is harder to hit by accident than one at the
        // bottom of a list, and "the last thing in the menu" is the only position
        // every platform's muscle memory agrees on.
        body.addView(menuOption(sheet,
                activity.getString(R.string.gallery_copy_settings),
                new Runnable() {
                    @Override
                    public void run() {
                        copyFrom(item);
                    }
                }));
        body.addView(menuOption(sheet, many
                        ? activity.getString(R.string.gallery_paste_onto_selected)
                        : activity.getString(R.string.gallery_paste_onto_this),
                new Runnable() {
                    @Override
                    public void run() {
                        pasteOnto(targetsFor(item));
                    }
                }));
        body.addView(menuOption(sheet,
                activity.getString(R.string.gallery_add_to_playlist),
                new Runnable() {
                    @Override
                    public void run() {
                        showPlaylistPicker(item);
                    }
                }));
        body.addView(menuOption(sheet, activity.getString(R.string.gallery_rename),
                new Runnable() {
                    @Override
                    public void run() {
                        rename(item);
                    }
                }));
        body.addView(menuOption(sheet, many
                        ? activity.getString(R.string.gallery_delete_selected)
                        : activity.getString(R.string.gallery_delete_one),
                new Runnable() {
                    @Override
                    public void run() {
                        confirmDelete(targetsFor(item));
                    }
                }));
        sheet.body(body);
        sheet.footerRow(closeButton(activity.getString(R.string.action_cancel), sheet));
        sheet.show(activity);
    }

    private void rename(final LibraryItem item) {
        // No hint: the field opens carrying the name it would change, so an
        // empty-hint line under a full box is a second thing to read for no
        // answer. The playlist sheet below is the one that starts empty, and
        // there the hint is the whole question.
        final EditText field = ModalSheet.field(activity, item.displayName(), null);
        final ModalSheet sheet = new ModalSheet(activity, 0.88f);
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
