package com.amoshydra.basrelief;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.depth.ComfyWorkflow;
import com.amoshydra.basrelief.core.render.Splat;
import com.amoshydra.basrelief.core.store.LibraryItem;
import com.amoshydra.basrelief.core.store.PrefFiles;

import java.io.File;
import java.util.Locale;

/**
 * The splat look's dev tuning group: SIZE, RESOLUTION, BLUR, DEPTH and PERSPECTIVE,
 * on the studio sheet under the Renderer row.
 *
 * <p><b>A dev path, not a product control, and labelled as one.</b> The values
 * are still being chosen by eye, so they live as prefs read at draw (exactly as the
 * Stage-0 {@code splat_grid} and {@code splat_scale} keys do) rather than in the
 * per-item schema: {@code SurfaceSettings.with} and {@code Framing}'s counted fields
 * are deliberately untouched until the values are known, because a field in the
 * counted chain is a schema bump plus a writer at every call site for a number that
 * may not survive the tuning. A user who never opens this group sees
 * {@link Splat#EPS_2D_DEFAULT}, {@link Splat#BLUR_DEFAULT},
 * {@link Splat#DEPTH_MUL_DEFAULT}, {@link Splat#PERSPECTIVE_DEFAULT} and
 * {@link ComfyWorkflow#SHARP_BUDGET} and nothing else.
 *
 * <p><b>It lives on the studio sheet because the render is behind it.</b> The
 * Settings screen is a Dialog in its own window over a dimmed studio — you cannot
 * see what a knob does while you move it. Here the sheet is a sibling of the
 * {@code GLSurfaceView}, so the Size, Blur, Depth and Perspective rows dim it to
 * glass while held ({@link MainActivity#dimWhileInteracting}) and the picture
 * updates under the finger. That is the whole reason the draw knobs are here and not
 * there.
 *
 * <p><b>Shown only when it can change a pixel.</b> The group appears when the active
 * row is in Splat mode AND names a cloud file that is here — the two conditions under
 * which {@link ReliefRenderer#useSplat()} draws the cloud. A row in Splat mode with
 * no cloud renders its relief, where Size, Blur, Depth and Perspective are inert, and
 * a control that cannot change anything is the lie this project keeps refusing to
 * ship (the same reason the Depth and Light rows are hidden in Splat mode).
 *
 * <p><b>DEPTH and PERSPECTIVE are the cloud's shape, not its look.</b> DEPTH moves
 * every splat along its own ray, so at rest the picture is the photograph at every
 * value and only the parallax changes; PERSPECTIVE multiplies the cloud's lens and
 * therefore re-frames the picture. Both are free to move live with no re-upload --
 * they are uniforms the draw reads -- which is why they sit here beside Size and
 * Blur rather than in Settings.
 *
 * <p><b>BUDGET is generation-side and does not dim.</b> It is read when the user
 * presses Regenerate, not at draw, so moving it changes nothing on screen and there
 * is no render to reveal. It is here rather than in Settings because the comparison
 * is "same photo, higher budget" and the Regenerate action is one row above.
 */
final class SplatDevRows {

    private final MainActivity activity;
    private final LinearLayout box;

    SplatDevRows(MainActivity activity) {
        this.activity = activity;

        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView caption = new TextView(activity);
        caption.setText(R.string.splat_dev_caption);
        caption.setTextColor(0x57FFFFFF);
        caption.setTextSize(11);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.09f);
        caption.setTypeface(null, android.graphics.Typeface.BOLD);
        // The sheet's 16dp inter-group caption gap, because this is its own group
        // under the geometry one rather than a row inside it.
        caption.setPadding(0, activity.dp(16), 0, activity.dp(4));
        box.addView(caption);

        TextView note = new TextView(activity);
        note.setText(R.string.splat_dev_note);
        note.setTextSize(11);
        note.setTextColor(0x8CFFFFFF);
        note.setPadding(0, 0, 0, activity.dp(2));
        box.addView(note);

        box.addView(sizeRow());
        box.addView(scaleRow());
        box.addView(blurRow());
        box.addView(depthRow());
        box.addView(perspectiveRow());

        box.setVisibility(View.GONE);
    }

    View view() {
        return box;
    }

    /**
     * Repaint the group from the live index. Called by {@code refreshSliders}, the
     * same contract {@link RendererRows#refresh} has, so a selection change or a
     * finished build re-decides visibility from the row that is actually selected.
     */
    void refresh() {
        LibraryItem item = activity.state().activeItem();
        boolean show = item != null && item.isSplatMode() && hasCloud(item);
        box.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private boolean hasCloud(LibraryItem item) {
        if (item.splatName == null) return false;
        File f = PrefFiles.resolve(activity.getFilesDir(), item.splatName);
        return f != null && f.isFile();
    }

    // ---------------------------------------------------------------- the rows

    /** SIZE: the 2D low-pass floor, as a fraction of the cell, in hundredths. */
    private View sizeRow() {
        int initial = Math.round(readFloat(ReliefRenderer.KEY_SPLAT_EPS2D,
                Splat.EPS_2D_DEFAULT) * 100f);
        initial = Math.round(Splat.clampEps2d(initial / 100f) * 100f);
        BasreliefSlider s = BasreliefSlider.create(activity, ReliefRenderer.KEY_SPLAT_EPS2D,
                initial, Math.round(Splat.EPS_2D_MIN * 100f), Math.round(Splat.EPS_2D_MAX * 100f),
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        return String.format(Locale.US, "%.2f", value / 100f);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        float v = Splat.clampEps2d(value / 100f);
                        activity.prefs.edit()
                                .putFloat(ReliefRenderer.KEY_SPLAT_EPS2D, v).apply();
                        activity.renderer.setSplatEps2d(v);
                        activity.glView.requestRender();
                    }
                });
        // Live while dragging: writes the renderer's volatile field and asks for a
        // frame, exactly as the Brightness and Shadow previews do. Nothing is
        // persisted here -- the release above is the one write.
        s.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                activity.renderer.setSplatEps2d(value / 100f);
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(s);
        return s.view();
    }

    /**
     * RESOLUTION: the fraction of the surface the cloud is rasterised at, in per
     * cent. The one lever that is linear in what a frame costs -- fill is pixels,
     * so 70 is about half the fragment work -- at the price of a softer picture.
     * Live, because the whole point is to watch the frame rate and the softness
     * move together, and the pref it writes is the one the renderer already reads.
     */
    private View scaleRow() {
        int initial = Math.round(readFloat(ReliefRenderer.KEY_SPLAT_SCALE, 1f) * 100f);
        initial = Math.max(25, Math.min(100, initial));
        BasreliefSlider s = BasreliefSlider.create(activity, ReliefRenderer.KEY_SPLAT_SCALE,
                initial, 25, 100,
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        return value + "%";
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        float v = Math.max(0.25f, Math.min(1f, value / 100f));
                        activity.prefs.edit()
                                .putFloat(ReliefRenderer.KEY_SPLAT_SCALE, v).apply();
                        activity.renderer.setSplatRenderScale(v);
                        activity.glView.requestRender();
                    }
                });
        s.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                activity.renderer.setSplatRenderScale(value / 100f);
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(s);
        return s.view();
    }

    /** BLUR: the post-blur radius, in tenths of a render-target pixel. */
    private View blurRow() {
        int initial = Math.round(readFloat(ReliefRenderer.KEY_SPLAT_BLUR,
                Splat.BLUR_DEFAULT) * 10f);
        initial = Math.round(Splat.clampBlurPx(initial / 10f) * 10f);
        BasreliefSlider s = BasreliefSlider.create(activity, ReliefRenderer.KEY_SPLAT_BLUR,
                initial, 0, Math.round(Splat.BLUR_MAX * 10f),
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        // 0 is the identity and reads as exactly that, not "0.0".
                        if (value == 0) return "0";
                        return String.format(Locale.US, "%.1f", value / 10f);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        float v = Splat.clampBlurPx(value / 10f);
                        activity.prefs.edit()
                                .putFloat(ReliefRenderer.KEY_SPLAT_BLUR, v).apply();
                        activity.renderer.setSplatBlurPx(v);
                        activity.glView.requestRender();
                    }
                });
        s.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                activity.renderer.setSplatBlurPx(value / 10f);
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(s);
        return s.view();
    }

    /**
     * DEPTH: a multiplier on the cloud's own depth, in hundredths. 1 is the depth
     * the node measured; 0 collapses the cloud onto its reference plane, the flat
     * photograph. At rest the picture is the photograph at every value -- each splat
     * is moved along the ray from the eye through its own pixel, and its size with
     * it -- so this row reveals the parallax and nothing else, which is exactly why
     * it dims the sheet while held: there is no change to see but the motion.
     */
    private View depthRow() {
        int initial = Math.round(readFloat(ReliefRenderer.KEY_SPLAT_DEPTH,
                Splat.DEPTH_MUL_DEFAULT) * 100f);
        initial = Math.round(Splat.clampDepthMul(initial / 100f) * 100f);
        BasreliefSlider s = BasreliefSlider.create(activity, ReliefRenderer.KEY_SPLAT_DEPTH,
                initial, Math.round(Splat.DEPTH_MUL_MIN * 100f),
                Math.round(Splat.DEPTH_MUL_MAX * 100f),
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        return String.format(Locale.US, "%.2f", value / 100f);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        float v = Splat.clampDepthMul(value / 100f);
                        activity.prefs.edit()
                                .putFloat(ReliefRenderer.KEY_SPLAT_DEPTH, v).apply();
                        activity.renderer.setSplatDepthMul(v);
                        activity.glView.requestRender();
                    }
                });
        s.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                activity.renderer.setSplatDepthMul(value / 100f);
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(s);
        return s.view();
    }

    /**
     * PERSPECTIVE: a multiplier on the cloud's own lens, in hundredths. 1 is the
     * cloud's lens, at which the at-rest picture is the photograph; above 1 is a
     * wider lens (more of the scene, stronger parallax) and below 1 flatter. Unlike
     * DEPTH this RE-FRAMES the picture, so it is not free -- the renderer clamps the
     * resulting fovy, which is what stops it becoming a fisheye. It dims the sheet
     * while held because the re-frame is exactly what there is to see.
     */
    private View perspectiveRow() {
        int initial = Math.round(readFloat(ReliefRenderer.KEY_SPLAT_PERSPECTIVE,
                Splat.PERSPECTIVE_DEFAULT) * 100f);
        initial = Math.round(Splat.clampPerspective(initial / 100f) * 100f);
        BasreliefSlider s = BasreliefSlider.create(activity, ReliefRenderer.KEY_SPLAT_PERSPECTIVE,
                initial, Math.round(Splat.PERSPECTIVE_MIN * 100f),
                Math.round(Splat.PERSPECTIVE_MAX * 100f),
                new BasreliefSlider.ValueFormatter() {
                    @Override
                    public String format(int value) {
                        return String.format(Locale.US, "%.2f", value / 100f);
                    }
                },
                new BasreliefSlider.OnChanged() {
                    @Override
                    public void onChanged(int value) {
                        float v = Splat.clampPerspective(value / 100f);
                        activity.prefs.edit()
                                .putFloat(ReliefRenderer.KEY_SPLAT_PERSPECTIVE, v).apply();
                        activity.renderer.setSplatPerspective(v);
                        activity.glView.requestRender();
                    }
                });
        s.setOnPreview(new BasreliefSlider.OnPreview() {
            @Override
            public void onPreview(int value) {
                activity.renderer.setSplatPerspective(value / 100f);
                activity.glView.requestRender();
            }
        });
        activity.dimWhileInteracting(s);
        return s.view();
    }

    // ---------------------------------------------------------------- pref reads

    /** Defensive on the read: a pref written as another type must not take the sheet down. */
    private float readFloat(String key, float fallback) {
        try {
            return activity.prefs.getFloat(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The budget the next build will send, from the same pref {@link SplatImport} reads. */
}
