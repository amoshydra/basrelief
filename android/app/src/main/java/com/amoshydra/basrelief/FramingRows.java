package com.amoshydra.basrelief;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.store.FramingRowValue;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.SurfaceSettings;

/**
 * The framing axes as four slider rows.
 *
 * <p>Replaces a one-line disclosure that opened two columns of platform
 * {@code EditText}s — underlined white boxes, the default Android look, in two
 * cramped columns on a 1440px screen. The wireframe gives Pan the same treatment as
 * Origin: "two axes, like Origin — one slider for each". Four full-width rows, each
 * with a −/＋ pair like every other adjustment, and nothing behind a tap.
 *
 * <p>A separate file rather than more of {@code MainActivity}: that class is the
 * largest in the app and every UI slice this redesign wanted went into it, which is
 * why those slices had to be done one at a time. This one is a whole section with no
 * state of its own, so it can be built elsewhere and added in one line.
 *
 * <p><b>Why sliders and not fields.</b> A numeric field is a keyboard and a caret, and
 * a −/＋ pair reaches every value a drag does. A field also cannot preview: nothing
 * moves until the user commits, and framing is the one adjustment where moving is the
 * whole point.
 */
final class FramingRows {

    private FramingRows() {
    }

    /**
     * Origin is a fraction of the picture, and its range is the model's own
     * bound, not a range of this class's choosing:
     * {@link SurfaceSettings#ORIGIN_LIMIT} is ±2 picture-widths. It is carried in
     * thousandths to keep a slider integral: −2.000..2.000.
     *
     * <p><b>It used to be 0.5, and that was a consequence of the shift, not a
     * property of the model.</b> Origin named the plane point the camera AIMED
     * at, so it also moved the picture: the photo's edge at +0.5 landed
     * mid-screen and at +1.0 the photo was entirely off screen, which is why the
     * far stop was half a plane. Origin no longer translates the picture — it
     * names the ORBIT CENTRE, and the camera is a turntable about it with a fixed
     * rest eye (see {@code CameraModel.frame} and {@code SplatCamera.frame}) — so
     * an origin outside the photo simply means the orbit centre is outside it,
     * and the bound can be what the control needs.
     *
     * <p><b>These used to be a literal ±1000 — twice the far stop — and the
     * difference was not cosmetic.</b> The extra half of the track previewed an
     * origin the model cannot mean, and on release {@code SurfaceSettings} clamped
     * the write to the model's bound while the control kept displaying what it had
     * previewed, leaving the slider, the stored value and the render on three
     * different numbers — the render at the far stop with its bar three-quarters
     * full, which is exactly how a photo that has left half the screen was
     * reached. Deriving the bound from the model's constant is what makes that
     * unable to come back.
     */
    private static final int ORIGIN_MIN = Math.round(-SurfaceSettings.ORIGIN_LIMIT * 1000f);
    private static final int ORIGIN_MAX = Math.round(SurfaceSettings.ORIGIN_LIMIT * 1000f);

    /**
     * How much further than one whole Origin unit the finger must travel.
     *
     * <p>The row's unit is turned into finger travel by {@link #originTravel},
     * which is the on-screen size of one Origin unit — a whole picture-width on
     * the X axis. 1.0 would be exact, and the user's request was "origin tracking
     * sliding, slightly less sensitive"; 1.3 asks a drag to be 30% longer than
     * the unit, and it keeps one feel at every zoom because the sensitivity is
     * derived from the live camera rather than a fixed range. This is a FEEL
     * constant: lower it toward 1.0 to make Origin bite sooner, raise it to make
     * it calmer.
     *
     * <p>Origin no longer moves the picture, so this is a sensitivity and no
     * longer a "the picture follows the finger" rule; it is kept because a whole
     * picture-width per unit is a sensible fine-grained step at every zoom.
     */
    private static final float ORIGIN_DRAG_FACTOR = 1.3f;

    /**
     * Pan is in pixels, and its bound is the renderer's, not a constant here.
     *
     * <p><b>The renderer is the truth and this control derives from it.</b>
     * {@code ReliefRenderer.setPan} (and {@code panBy}) clamp to
     * {@code max(surfaceW, surfaceH)} — the camera's reachable travel on the
     * surface it is actually drawing into. This row used to carry a flat ±4000,
     * which on a 3120px-tall phone left the last ~880 units of drag as a silent
     * dead stop: the slider moved, the picture did not, and the stored value was
     * then re-clamped away on the next load. A range that can disagree with the
     * clamp is a control that displays values nothing renders.
     *
     * <p><b>That the clamp depends on the surface size decides the shape of the
     * fix.</b> A different phone, a rotation or a multi-window resize all move
     * the bound, so no compile-time number can be right; the honest option is
     * for the row to follow the same quantity the renderer clamps against. That
     * quantity is read from {@link MainActivity#glView}, whose layout size IS
     * the size {@code onSurfaceChanged} hands the renderer, and the range is
     * refreshed on every layout change. Before the first layout the display
     * metrics are the best reading of the same number, because the GL view is
     * full-screen; see {@link #panLimit}.
     *
     * <p>The row is built with {@link BasreliefSlider#createScreenPixels} because a
     * pan unit already is a screen pixel: the camera applies pan after the
     * projection, so a drag of D pixels changes pan by D and the picture by D, at
     * every zoom. Zoom is deliberately not passed to the row.
     */
    private static int panLimit(MainActivity activity) {
        int w = activity.glView.getWidth();
        int h = activity.glView.getHeight();
        if (w > 0 && h > 0) return Math.max(w, h);
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /**
     * Finger travel, in surface pixels, that one thousandth of Origin asks for.
     *
     * <p>Read at the start of every gesture, so a zoom changed since the sheet
     * was built cannot leave the row stale. The renderer answers the on-screen
     * size of one whole Origin unit at the zoom and image aspect of the last
     * frame ({@code CameraModel.screenPixelsPerOrbit}); the slider counts in
     * thousandths; and {@link #ORIGIN_DRAG_FACTOR} is the margin over that unit.
     * Origin no longer moves the picture, so this is a sensitivity rather than a
     * tracking rule.
     *
     * <p>Zero before the surface exists, which the slider treats as "use the
     * range rule"; see BasreliefSlider.unitTravel.
     */
    /** Z-pan (camera distance) as a pan-family row: same range feel, global pref. */
    private static View zpan(final MainActivity activity) {
        int initial = Math.round(activity.renderer.getCamDist() * 100f);
        BasreliefSlider.ValueFormatter fmt = new BasreliefSlider.ValueFormatter() {
            @Override
            public String format(int value) {
                return String.format(java.util.Locale.US, "%.2f", value / 100f);
            }
        };
        BasreliefSlider.OnChanged changed = new BasreliefSlider.OnChanged() {
            @Override
            public void onChanged(int v) {
                float _v = v / 100f;
                if (!Float.isFinite(_v)) _v = SurfaceSettings.DEFAULT_PAN_Z;
                if (_v < SurfaceSettings.PAN_Z_MIN) _v = SurfaceSettings.PAN_Z_MIN;
                if (_v > SurfaceSettings.PAN_Z_MAX) _v = SurfaceSettings.PAN_Z_MAX;
                final float val = _v;
                final String id = activity.state().activeId();
                if (id == null) return;
                LibraryStore.editItem(activity.prefs, id, new LibraryStore.Editor() {
                    @Override
                    public LibraryItem edit(LibraryItem item) {
                        return item.withPanZ(activity.editSurface, val);
                    }
                });
                activity.glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        activity.renderer.reloadFromPrefs();
                    }
                });
                activity.glView.requestRender();
            }
        };
        BasreliefSlider slider = BasreliefSlider.create(activity, "zpan", initial,
                50, 500, fmt, changed);
        slider.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int v) {
                float _v = v / 100f;
                if (!Float.isFinite(_v)) _v = SurfaceSettings.DEFAULT_PAN_Z;
                if (_v < SurfaceSettings.PAN_Z_MIN) _v = SurfaceSettings.PAN_Z_MIN;
                if (_v > SurfaceSettings.PAN_Z_MAX) _v = SurfaceSettings.PAN_Z_MAX;
                final float val = _v;
                activity.glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        activity.renderer.setCamDist(val);
                    }
                });
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(slider);
        activity.sliders.put("zpan", slider);
        return slider.view();
    }

    private static float originTravel(MainActivity activity, boolean isX) {
        return activity.renderer.getOrbitPixelsPerUnit(isX)
                * ORIGIN_DRAG_FACTOR / 1000f;
    }

    /** Build the whole section: Origin caption, X, Y, Pan caption, X, Y. */
    static View build(final MainActivity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(caption(activity, R.string.framing_origin_caption));
        box.addView(axis(activity, "originX", true));
        box.addView(axis(activity, "originY", true));

        box.addView(caption(activity, R.string.framing_pan_caption));
        box.addView(axis(activity, "panX", false));
        box.addView(axis(activity, "panY", false));
        box.addView(zpan(activity));

        // The row is built before the first layout pass, when glView has no size;
        // panLimit() falls back to the display metrics there. This listener
        // replaces that estimate with the real surface size the renderer will
        // clamp against — and re-derives it on a rotation or a window resize, so
        // the slider can never be left offering travel the picture cannot take.
        activity.glView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (l == ol && t == ot && r == or && b == ob) return;
                int limit = Math.max(r - l, b - t);
                if (limit <= 0) return;
                setPanRange(activity, limit);
            }
        });

        return box;
    }

    /**
     * Give both pan rows the renderer's current bound, silently.
     *
     * <p>The value is only shown clamped; the stored blob is not rewritten, so a
     * pan stored on a larger screen survives a visit to a smaller one, exactly as
     * {@code ReliefRenderer.setPan}'s own comment promises.
     */
    private static void setPanRange(MainActivity activity, int limit) {
        setRange(activity, "panX", limit);
        setRange(activity, "panY", limit);
    }

    private static void setRange(MainActivity activity, String key, int limit) {
        BasreliefSlider slider = activity.sliders.get(key);
        if (slider != null) slider.setRange(-limit, limit);
    }

    /**
     * The small caption above a pair of rows, naming the unit.
     *
     * <p>The same treatment as a group heading but for a family rather than a group —
     * which is why this is not {@code sectionLabel}: that one is for the four groups,
     * and using it here would make "Origin" read as a section in its own right.
     */
    private static TextView caption(MainActivity activity, int res) {
        TextView t = new TextView(activity);
        t.setText(res);
        t.setTextSize(11);
        t.setAllCaps(true);
        t.setLetterSpacing(0.09f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0x57FFFFFF);
        // No side padding: this caption names the rows under it, and those rows'
        // labels begin at the sheet's content edge. The 4dp here put "Origin
        // (fraction)" 4dp right of "Origin X" directly beneath it.
        t.setPadding(0, activity.dp(6), 0, activity.dp(4));
        return t;
    }

    /** One axis as a slider row. */
    private static View axis(final MainActivity activity, final String key,
                             final boolean fraction) {
        BasreliefSlider.ValueFormatter fmt = new BasreliefSlider.ValueFormatter() {
            @Override
            public String format(int v) {
                // Origin is a fraction of the reachable range carried in
                // thousandths, so three decimals show the step a nudge takes.
                // Two decimals printed neighbouring steps as the same number,
                // which makes the control look stuck at exactly the values it
                // is meant to be aimed at. Pan stays whole pixels: a fraction
                // of a pixel is not a distance anyone aims at.
                return fraction
                        ? String.format(java.util.Locale.US, "%.3f", v / 1000f)
                        : String.valueOf(v);
            }
        };
        BasreliefSlider.OnChanged changed = new BasreliefSlider.OnChanged() {
            @Override
            public void onChanged(int v) {
                apply(activity, key, fraction, v);
            }
        };
        // Pan's unit is a screen pixel, so its row drags 1:1 and its range is
        // the renderer's own bound; see panLimit. Origin is a fraction of the
        // picture, so its on-screen size scales with zoom and with the image's
        // aspect; its travel therefore comes live from the renderer through the
        // provider below, so one whole Origin unit is a whole picture-width of
        // drag at any zoom instead of the range-scaled sweep's fixed,
        // zoom-blind speed. That is a SENSITIVITY, not tracking: Origin no longer
        // moves the picture. Pan deliberately does NOT get the provider: its
        // unit already is a pixel, and that is the whole distinction.
        final int limit = panLimit(activity);
        BasreliefSlider slider = fraction
                ? BasreliefSlider.create(activity, key, read(activity, key, true),
                        ORIGIN_MIN, ORIGIN_MAX, fmt, changed)
                : BasreliefSlider.createScreenPixels(activity, key, read(activity, key, false),
                        -limit, limit, fmt, changed);
        if (fraction) {
            slider.setPixelsPerUnit(new BasreliefSlider.PixelsPerUnit() {
                @Override
                public float pixelsPerUnit() {
                    return originTravel(activity, key.endsWith("X"));
                }
            });
        }
        // Live preview, the half these four rows were missing. The store is still
        // written once, on release, by OnChanged above; this only moves the
        // renderer's own fields, which is what makes pan and origin follow the
        // finger like Depth and Zoom already do. Nothing here reads or writes the
        // index: a preview that persisted would re-read and re-validate the whole
        // gallery once per step of travel.
        final boolean isX = key.endsWith("X");
        slider.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int v) {
                final float value = fraction ? FramingRowValue.originFraction(v) : v;
                // One queued event for the cancel and the write together. Queued
                // because cancelGlide is GL-thread state (the GLSurfaceView's own
                // touch handler queues it the same way), and one event rather than
                // two so the cancel cannot run after the write and re-apply the
                // glide's destination over it.
                activity.glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        activity.renderer.cancelGlide();
                        if (fraction) {
                            activity.renderer.setOrbit(
                                    isX ? value : activity.renderer.getOrbitX(),
                                    isX ? activity.renderer.getOrbitY() : value);
                        } else {
                            activity.renderer.setPan(
                                    isX ? value : activity.renderer.getPanX(),
                                    isX ? activity.renderer.getPanY() : value);
                        }
                    }
                });
                activity.glView.requestRender();
            }
        });
        // The sheet goes to glass while the control is in use; the row stays at
        // 60%. Installed here because these sliders are built in this class rather
        // than by MainActivity.addSlider, and the two paths must not diverge.
        activity.dimWhileInteracting(slider);
        activity.sliders.put(key, slider);
        return slider.view();
    }

    /** One axis's stored value as a slider integer, on the surface being edited. */
    private static int read(MainActivity activity, String key, boolean fraction) {
        // Z-pan first, and before the "no item" answer below: it is a GLOBAL pref,
        // not a value on a row, so it has a value with an empty gallery too. Reading
        // it after `item == null` returned 0 made the row show 0.00 over a pref that
        // was not 0 -- the "a row must display what the gesture will write" rule.
        LibraryItem item = activity.state().activeItem();
        if (item == null) return 0;
        SurfaceSettings f = item.settings(activity.editSurface);
        if ("zpan".equals(key)) {
            // Per photo and per surface, read exactly as panX/panY are.
            float v = f.panZ;
            if (!Float.isFinite(v)) v = SurfaceSettings.DEFAULT_PAN_Z;
            if (v < SurfaceSettings.PAN_Z_MIN) v = SurfaceSettings.PAN_Z_MIN;
            if (v > SurfaceSettings.PAN_Z_MAX) v = SurfaceSettings.PAN_Z_MAX;
            return Math.round(v * 100f);
        }
        boolean isX = key.endsWith("X");
        if (fraction) return FramingRowValue.originThousandths(isX ? f.originX : f.originY);
        // Shown clamped to the same bound the renderer uses, not as stored: a pan
        // written on a larger surface is inside the blob but outside what this
        // screen can render, so printing the raw number here would describe a
        // position the picture is not at. Both halves of that are
        // FramingRowValue.showablePan's, so the stored read and the renderer's live
        // value below cannot clamp differently.
        return FramingRowValue.showablePan(isX ? f.panX : f.panY, panLimit(activity));
    }

    /**
     * The renderer's live value for one axis, as this row's integer.
     *
     * <p>Same function {@link #read} uses, over a different source. That is the whole
     * point: the two differ only in where the float comes from, so a row cannot end up
     * displaying a number derived one way while the gesture commits another.
     */
    private static int fromRenderer(MainActivity activity, String key, boolean fraction) {
        boolean isX = key.endsWith("X");
        if ("zpan".equals(key)) {
            return Math.round(activity.renderer.getCamDist() * 100f);
        }
        if (fraction) {
            return FramingRowValue.originThousandths(
                    isX ? activity.renderer.getOrbitX() : activity.renderer.getOrbitY());
        }
        return FramingRowValue.showablePan(
                isX ? activity.renderer.getPanX() : activity.renderer.getPanY(),
                panLimit(activity));
    }

    /**
     * Put the renderer's live framing into all four rows, silently.
     *
     * <p><b>The renderer is the authority here, and storage is not — measured, not
     * argued.</b> A tap or a two-finger drag writes the store 150–430 ms later and on
     * another thread ({@code LibraryStore.editItem} is a synchronous {@code commit()}),
     * so a sheet that re-read storage after a gesture reads the value from <i>before</i>
     * it. On a Pixel 7 Pro the tap's store landed 388 ms after the orbit did, and the
     * row showed the pre-tap origin for as long as the sheet stayed open. The
     * renderer's volatiles are the only one of the three candidates that is current at
     * the moment the row has to display something: they are what the next frame draws
     * ({@code captureFraming}), and they are written by the gesture itself.
     *
     * <p><b>Which is why this is a push and not a re-read.</b> {@link #refresh} is the
     * other direction — stored values onto the rows — and it is right there: after a
     * gallery selection or a surface switch the stored value IS the truth, and the
     * renderer has not caught up yet. Two callers, two sources, one derivation each,
     * and the rule for which is which is the writer's: the renderer owns what is on
     * screen, the index owns what is kept.
     *
     * <p>Silent by construction, like every other population of a row:
     * {@link BasreliefSlider#setValue} has no listener to fire, so this cannot re-enter
     * {@link #apply} and write the value it is only trying to display.
     */
    static void pushFromRenderer(MainActivity activity) {
        push(activity, "originX", true);
        push(activity, "originY", true);
        push(activity, "panX", false);
        push(activity, "panY", false);
    }

    /**
     * One row, from the renderer, only if it would actually change.
     *
     * <p>The skip is the same one {@link BasreliefSlider#preview} makes, for the same
     * reason: this runs per step of travel, and re-formatting a number that did not
     * move is work with nothing to show for it. It also means a gesture that changes
     * one axis does not churn the other three rows' drawables.
     */
    private static void push(MainActivity activity, String key, boolean fraction) {
        BasreliefSlider slider = activity.sliders.get(key);
        if (slider == null) return;
        int v = fromRenderer(activity, key, fraction);
        if (v == slider.value()) return;
        slider.setValue(v);
    }

    /**
     * Persist one axis, on the surface being edited, leaving the other seven alone.
     *
     * <p>{@code editItem} rather than a whole-item write, so the sibling axis and every
     * adjustment survive: the rule the whole sheet follows, and the one
     * {@code withOrigin} exists to make easy. The renderer follows by re-resolving
     * from the index on the GL thread — one path for everything, no second way for the
     * renderer to learn where the camera is, which is how the two surfaces came to
     * disagree about the bundled item in the first place.
     */
    private static void apply(final MainActivity activity, final String key,
                              final boolean fraction, final int value) {
        final boolean isX = key.endsWith("X");
        final float v = fraction ? FramingRowValue.originFraction(value) : value;
        final String id = activity.state().activeId();
        if (id == null) return; // nothing is selected, so there is nothing to write
        LibraryStore.editItem(activity.prefs, id, new LibraryStore.Editor() {
            @Override
            public LibraryItem edit(LibraryItem item) {
                SurfaceSettings other = item.settings(activity.editSurface);
                return fraction
                        ? (isX ? item.withOrigin(activity.editSurface, v, other.originY)
                               : item.withOrigin(activity.editSurface, other.originX, v))
                        : (isX ? item.withPan(activity.editSurface, v, other.panY)
                               : item.withPan(activity.editSurface, other.panX, v));
            }
        });
        activity.glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                activity.renderer.reloadFromPrefs();
            }
        });
        activity.glView.requestRender();
    }

    /**
     * Repopulate every framing control from the surface being edited.
     *
     * <p><b>Called from {@code MainActivity.refreshSliders}, and that is not
     * optional.</b> These four rows register into the same {@code sliders} map as
     * Depth and Zoom (see {@link #axis}), but they are addressed by a different set
     * of keys and — unlike the other five — were never written back after the sheet
     * was built. A surface switch or a gallery selection therefore left them showing
     * the <i>previous</i> surface's origin and pan, and the next nudge committed
     * that stale value onto the surface the user had just switched to. Switching
     * Home → Lock and nudging repeated, so two surfaces the user had framed
     * differently converged on one of them.
     *
     * <p>The rule is the one the whole sheet follows: a control must display
     * exactly what the gesture will write, or the gesture writes what the control
     * happened to be displaying.
     */
    static void refresh(MainActivity activity) {
        setSlider(activity, "originX", true);
        setSlider(activity, "originY", true);
        setSlider(activity, "panX", false);
        setSlider(activity, "panY", false);
        setSlider(activity, "zpan", false);
    }

    /**
     * One framing row set from the stored value, silently.
     *
     * <p>{@link BasreliefSlider#setValue} is inert by construction — it has no
     * listener to fire — so this cannot re-enter {@code onChanged} and write the
     * value it is only trying to display.
     */
    private static void setSlider(MainActivity activity, String key, boolean fraction) {
        BasreliefSlider slider = activity.sliders.get(key);
        if (slider == null) return;
        slider.setValue(read(activity, key, fraction));
    }
}