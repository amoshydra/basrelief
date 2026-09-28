package com.amoshydra.basrelief;

import android.app.AlertDialog;
import android.os.Build;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
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
import com.amoshydra.basrelief.core.store.PrefFiles;
import com.amoshydra.basrelief.core.store.SortOrder;

import java.io.File;
import java.util.List;

/**
 * The gallery: every photo in the app, as a list over the running preview.
 *
 * <p>An overlay rather than a separate screen, and the preview keeps rendering
 * behind it. The whole point of a gallery here is to choose a framing, and
 * choosing it while looking at the result is the difference between picking a
 * photo and picking a <i>wallpaper</i>. A full-screen opaque list would force a
 * back-and-forth for every choice.
 *
 * <p><b>Rows are built by hand</b> because there is no AndroidX anywhere in this
 * app, and that is a deliberate constraint rather than an accident: it is what
 * keeps the APK free of third-party runtime code, and the cost is a few dozen
 * lines of layout code.
 *
 * <p><b>Nothing is deleted without asking.</b> A delete here removes a photo the
 * user chose, and the confirm dialog names it and says what will be on screen
 * afterwards, because the honest outcome of deleting the current photo is the
 * bundled demo -- not a neighbour they never picked.
 */
final class Gallery {

    private final MainActivity activity;
    private final FrameLayout root;

    Gallery(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    void show() {
        if (root.findViewWithTag("gallery") != null) return; // already up
        LinearLayout panel = build();
        panel.setTag("gallery");
        root.addView(panel);
        // ask for them now that it is attached; a listener set before attachment
        // is never called
        panel.requestApplyInsets();
    }

    void dismiss() {
        View panel = root.findViewWithTag("gallery");
        if (panel != null) root.removeView(panel);
    }

    private boolean isOpen() {
        return root.findViewWithTag("gallery") != null;
    }

    // ------------------------------------------------------------------ build

    private LinearLayout build() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF0101418);
        final int pad = activity.dp(16);
        panel.setPadding(pad, pad, pad, pad);
        // Pad out of the system bars, rather than assuming they are hidden.
        //
        // <p>The studio asks for immersive mode but does not always get it -- the
        // status bar reappears on a wake, and a gesture-nav pill sits at the
        // bottom. A header laid out under the status bar looks fine and is
        // completely dead: the bar takes the touch before the view ever sees it,
        // so Close and Sort appeared to be broken buttons. There was no error and
        // no redraw, because nothing was wrong with the buttons.
        panel.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(pad, pad + top, pad, pad + bottom);
                return insets;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        panel.setLayoutParams(lp);

        Library lib = LibraryStore.load(activity.prefs);
        AppState state = AppState.load(activity.prefs, activity.getFilesDir());
        String activeId = lib.activeId();

        // ---- header: title, sort, close
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("Gallery", 20);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        header.addView(compact("Sort: " + lib.sort().label(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu();
            }
        }));
        header.addView(compact("Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        }));
        panel.addView(header);

        if (lib.size() == 0) {
            TextView empty = text("No photos yet. Use Image to add one.", 14);
            empty.setPadding(0, activity.dp(24), 0, 0);
            panel.addView(empty);
        }

        // ---- rows, in the chosen order
        ScrollView scroll = new ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        List<LibraryItem> items = lib.sorted();
        for (final LibraryItem item : items) {
            rows.addView(row(item, item.id.equals(activeId), state));
        }
        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    /**
     * One row: thumbnail, name, a subtitle that says what is actually true about
     * it, and a menu.
     *
     * <p>The subtitle is the honest part. A photo with no depth map is not
     * styled like one with -- the bundled demo used to report itself the same
     * way, and that is exactly the ambiguity the depth-status line exists to
     * avoid.
     */
    private View row(final LibraryItem item, boolean isActive, AppState state) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = activity.dp(8);
        row.setPadding(0, pad, 0, pad);
        // WRAP_CONTENT explicitly: without it a row can inherit a height and a
        // gallery of two photos renders two full-screen rows.
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Tapping selects; it does not close, because picking one photo is
        // usually a step towards picking a framing of it.
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(item.id);
            }
        });

        ImageView thumb = new ImageView(activity);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(activity.dp(56), activity.dp(56)));
        // Only a real photo has a thumbnail to show; the bundled demo has no
        // file, so it gets a neutral tile rather than a broken one.
        final TextView sub = text(subtitle(item, isActive), 12);
        if (!item.isBundled()) {
            File f = PrefFiles.resolve(activity.getFilesDir(), item.imageName);
            if (f != null) {
                final boolean hasFile = item.hasDepth();
                Io.decodePreview(activity, f, thumb, activity.dp(56),
                        new Io.EmbeddedDepthListener() {
                            @Override
                            public void onFound(boolean embedded) {
                                // A photo can carry its depth inside the PNG, and
                                // hasDepth() only knows about a separate file. So
                                // without this the row would say FLAT for a photo
                                // that is visibly in relief -- the exact ambiguity
                                // the status line exists to avoid.
                                if (embedded && !hasFile) {
                                    sub.setText(subtitleWithEmbedded(item, isActive));
                                }
                            }
                        });
            }
        }
        row.addView(thumb);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(activity.dp(12), 0, activity.dp(8), 0);

        String name = item.isBundled() ? "Marble bust" : item.displayName();
        TextView nameView = text((isActive ? "▸ " : "") + name, 16);
        labels.addView(nameView);
        labels.addView(sub);

        row.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(compact("⋮", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRowMenu(item, isActive);
            }
        }));
        return row;
    }

    private String subtitle(LibraryItem item, boolean isActive) {
        return describe(item, isActive, false);
    }

    private String subtitleWithEmbedded(LibraryItem item, boolean isActive) {
        return describe(item, isActive, true);
    }

    private String describe(LibraryItem item, boolean isActive, boolean embedded) {
        StringBuilder sb = new StringBuilder();
        if (item.isBundled()) {
            sb.append("built in");
        } else if (item.hasDepth()) {
            sb.append("has depth");
        } else if (embedded) {
            sb.append("depth in the file");
        } else {
            sb.append("FLAT, no depth");
        }
        sb.append("  ·  depth ").append(item.intensity).append('%');
        if (isActive) sb.append("  ·  on screen");
        return sb.toString();
    }

    // ----------------------------------------------------------------- actions

    private void select(String id) {
        LibraryStore.select(activity.prefs, id);
        // The engine will pick this up on its own, but the home screen may be
        // showing the system's snapshot of the surface rather than the live
        // engine, and a re-bind is the only thing that refreshes that. See
        // WallpaperRefresh for the whole story. Conditional, so it never
        // activates a wallpaper the user has not already chosen.
        WallpaperRefresh.ifOursIsActive(activity);
        // Mark it used, so "recently used" means recently looked at.
        activity.refreshStatus();
        activity.refreshSliders();
        activity.applyFiles();
        // Rebuild rather than mutate: the sorted order may have changed, and the
        // active marker is on every row.
        dismiss();
        show();
    }

    private void showSortMenu() {
        final SortOrder[] order = SortOrder.values();
        String[] labels = new String[order.length];
        for (int i = 0; i < order.length; i++) labels[i] = order[i].label();
        new AlertDialog.Builder(activity)
                .setTitle("Sort by")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        LibraryStore.setSort(activity.prefs, order[which]);
                        dismiss();
                        show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRowMenu(final LibraryItem item, final boolean isActive) {
        String setLabel = isActive ? "Already on screen" : "Set as wallpaper";
        if (item.isBundled()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Marble bust")
                    .setMessage("The built-in demo. It has no depth map of its own beyond "
                            + "the one shipped in the app, and it cannot be deleted -- "
                            + "it is the way back to the default.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }
        String[] items = {setLabel, "Rename", "Set depth to default",
                "Reset this photo's settings", "Delete"};
        new AlertDialog.Builder(activity)
                .setTitle(item.displayName())
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0: select(item.id); break;
                            case 1: rename(item); break;
                            case 2: clearDepth(item); break;
                            case 3: resetItem(item); break;
                            case 4: confirmDelete(item, isActive); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(final LibraryItem item) {
        final EditText field = new EditText(activity);
        field.setText(item.displayName());
        new AlertDialog.Builder(activity)
                .setTitle("Rename")
                .setView(field)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = field.getText().toString().trim();
                        final String label = name.isEmpty() ? null : name;
                        LibraryStore.editItem(activity.prefs, item.id, new LibraryStore.Editor() {
                            @Override
                            public LibraryItem edit(LibraryItem it) {
                                return it.withLabel(label);
                            }
                        });
                        dismiss();
                        show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearDepth(LibraryItem item) {
        LibraryStore.editItem(activity.prefs, item.id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem it) {
                return it.withDepth(null);
            }
        });
        activity.applyFiles();
        dismiss();
        show();
    }

    private void resetItem(final LibraryItem item) {
        LibraryStore.editItem(activity.prefs, item.id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem it) {
                return it.withIntensity(LibraryItem.DEFAULT_INTENSITY)
                        .withZoom(LibraryItem.DEFAULT_ZOOM)
                        .withOrbit(0f, 0f).withPan(0f, 0f);
            }
        });
        if (LibraryStore.load(activity.prefs).isActive(item.id)) {
            activity.refreshSliders();
            activity.applyFiles();
        }
        Toast.makeText(activity, "Settings reset", Toast.LENGTH_SHORT).show();
    }

    /**
     * Ask before deleting, and say what the screen will show afterwards.
     *
     * <p>The confirmation is not a formality here: the row being deleted is often
     * the one on screen, and the honest outcome is the bundled demo rather than
     * a neighbour the user never chose. Saying so up front is the difference
     * between a decision and a surprise.
     */
    private void confirmDelete(final LibraryItem item, final boolean isActive) {
        String message = isActive
                ? "Delete " + item.displayName() + "?\n\nIt is on screen now, so the "
                + "wallpaper will fall back to the marble bust."
                : "Delete " + item.displayName() + "?";
        new AlertDialog.Builder(activity)
                .setTitle("Delete?")
                .setMessage(message)
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        // Remove the index entry first, then the files. A crash in
                        // between leaves orphan files, which are invisible, rather
                        // than an index that points at files that are gone.
                        LibraryStore.remove(activity.prefs, item.id);
                        // Drop the cached decode too. Nothing would look up these
                        // paths again, but holding several megabytes of a deleted
                        // photo because the cache forgets that photos get deleted
                        // is the kind of leak that only shows up as an OOM much
                        // later, on someone else's device.
                        PairCache.clear();
                        deleteFiles(item);
                        if (isActive) {
                            activity.refreshSliders();
                            activity.applyFiles();
                        }
                        dismiss();
                        show();
                        Toast.makeText(activity,
                                isActive ? "Deleted; showing the marble bust"
                                        : "Deleted", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    // ----------------------------------------------------------------- widgets

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
