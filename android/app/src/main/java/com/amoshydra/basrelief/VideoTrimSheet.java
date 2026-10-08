package com.amoshydra.basrelief;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.amoshydra.basrelief.core.depth.BatchBudget;

/**
 * The video trim sheet: two 44dp handles, a pinch, and a frame stepper.
 *
 * <p><b>The range is the decision.</b> A living photo needs a RANGE, and the sheet
 * is a bar with two handles: a preview of the frame under each handle, a one-frame
 * stepper beside each time, and a pinch that changes how much time sits under your
 * thumb. The resolution row is not a second decision to make -- it is the smallest
 * control that keeps a source too big for the server importable, and the MEMORY
 * GUARD moves it by itself when the current choice cannot fit.
 *
 * <p><b>The range is in FRAMES and travels to the server as frames.</b>
 * {@code skip_first_frames} and {@code frame_load_cap} are the loader's own window
 * (see {@code ComfyWorkflow}), so all three clips come back trimmed and aligned. The
 * device never re-encodes anything, which is why there is no second trimming path to
 * disagree with this one -- the mockup's "the seek is the missing feature" is
 * answered by the same mechanism as the trim.
 *
 * <p><b>A range too big for one batch is no longer refused -- it is chunked.</b>
 * The memory guard below still finds the resolution and states the number, but
 * "the whole range must fit one batch" stopped being a condition of importing:
 * the run is split into prompts of {@code BatchBudget.chunkFrames(height)} frames
 * and joined on the device, so the line that used to say "this range cannot be
 * generated" now says how many chunks it takes. The one thing that still refuses
 * is a height no chunk fits at, which is the unknown-reading case rather than a
 * length.
 *
 * <p><b>Zoom stops, not a free pinch.</b> The deepest useful zoom is set by the
 * binding loop: a 2s range must be wider than a 44dp handle, which is what makes
 * the handles usable on an hour-long clip. Fit, 15s-on-bar and 14s-on-bar are the
 * three stops; the handles never change size, the time beneath them does.
 *
 * <p><b>Past 30 seconds of server time it is a JOB, and the sheet says so without
 * advising.</b> The estimate is a fact -- the frames cost what they cost in time
 * AND in the server's memory -- and the copy states where the result lives and that
 * closing the app does not cancel it. Facts, no steering: the mockup is explicit
 * that the sheet does not warn, so an over-budget range states its number and the
 * guard refuses it rather than advising a smaller one.
 */
final class VideoTrimSheet {

    /**
     * Measured server cost per frame: a 120-frame import took about 90s end to end,
     * so ~0.75s of server time per frame at 720p-class input. An ESTIMATE shown as
     * one ("about ..."), never a promise, and the one constant to revise when the
     * pipeline or the host changes.
     */
    private static final float SECONDS_PER_FRAME = 0.75f;

    /**
     * The server-time line where the framing flips to a Job. The mockup's number,
     * and it is about the SHAPE of the wait rather than about cost: under half a
     * minute a sheet can honestly say "about 40s"; past it, the honest thing is that
     * the user can leave and the work continues.
     */
    private static final float JOB_SECONDS = 30f;

    /** How many preview frames the filmstrip shows; more is wasted at tile width. */
    private static final int THUMBS = 10;

    /** The loop the deepest zoom is sized against; the mockup's 2s binding loop. */
    private static final float LOOP_SECONDS = 2f;

    /**
     * Called with the chosen range, in frames, or with whole=true for all of it.
     *
     * @param outHeight the height to scale the clip to, or 0 for the source's own.
     *                  It travels to the SERVER, which scales the batch as it loads:
     *                  a smaller batch is less GPU memory, a shorter job and smaller
     *                  files, and it is the one control that makes a high-resolution
     *                  source importable at all -- a 1380x2156 x 120-frame batch died
     *                  with "Allocation on device 0 would exceed allowed memory" while
     *                  the same clip at 720p height is under half a gigabyte.
     */
    interface Chosen {
        void range(int startFrame, int frames, boolean whole, int outHeight);
        void cancelled();
    }

    private VideoTrimSheet() {
    }

    /** The sheet's own dp: the activity's helper is on MainActivity, not Activity. */
    /** One resolution chip: a plate when selected, an outline when not. */
    private static void paintChip(TextView chip, boolean selected) {
        android.graphics.drawable.GradientDrawable plate =
                new android.graphics.drawable.GradientDrawable();
        plate.setCornerRadius(chip.getResources().getDisplayMetrics().density * 7);
        // setColor AFTER the stroke: a GradientDrawable clears its fill when the
        // stroke is set, which is the trap that made a plate invisible once already.
        plate.setStroke(Math.round(chip.getResources().getDisplayMetrics().density),
                selected ? 0x00000000 : 0x33FFFFFF);
        plate.setColor(selected ? 0x2EFFFFFF : 0x00000000);
        chip.setBackground(plate);
        chip.setTextColor(selected ? 0xFFEDEFF3 : 0x8AFFFFFF);
    }

    private static int dp(Context c, int n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Show the sheet for one video.
     *
     * @param fps          the source rate, so a time becomes a frame
     * @param totalFrames  the source's frame count, or 0 when it could not be read
     * @param sourceHeight the video's own height, for the "Original" estimate
     */
    static void show(final Activity activity, final Uri video, final String label,
                     final float fps, final int totalFrames, final int sourceHeight,
                     final Chosen onChosen) {
        // Guarded here as well as at the call site: the check there and the show here
        // are not one atomic step, and a Dialog with a dead token throws rather than
        // declining. A sheet nobody can see is the right outcome for an activity that
        // has gone.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final ModalSheet sheet = new ModalSheet(activity, 0.62f);
        final LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText(label == null || label.isEmpty()
                ? activity.getString(R.string.trim_title) : label);
        title.setTextSize(15);
        title.setTextColor(0xFFEDEFF3);
        body.addView(title);

        final TextView reading = new TextView(activity);
        reading.setText(activity.getString(R.string.trim_reading));
        reading.setTextSize(12);
        reading.setTextColor(0x8AFFFFFF);
        body.addView(reading);

        final TrimBar bar = new TrimBar(activity, fps, totalFrames);
        body.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 72)));

        // The times, each with a one-frame stepper. The stepper exists because a
        // clock picker is the accessible path to an exact time and this is the
        // PRECISE one: at the deepest zoom the bar is already about a frame per dp.
        final LinearLayout times = new LinearLayout(activity);
        times.setOrientation(LinearLayout.HORIZONTAL);
        final TextView startText = timeText(activity);
        final TextView endText = timeText(activity);
        times.addView(stepper(activity, R.string.trim_start, startText, bar, true));
        times.addView(stepper(activity, R.string.trim_end, endText, bar, false));
        body.addView(times);

        // THE RESOLUTION. It exists because a source can be too big to generate at
        // all: the depth inference holds the whole batch, and the cost scales with
        // frames AND with height -- superlinearly in height, because MoGe's own
        // working resolution follows the input. Scaling at the LOADER fixes batch,
        // job length and file size together, and it costs the user one tap. The
        // MEMORY GUARD below makes that tap automatic when the chosen size cannot
        // fit: the sheet must not let a batch be submitted that can only OOM.
        final int[] outHeight = {0};
        LinearLayout resRow = new LinearLayout(activity);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView resLabel = new TextView(activity);
        resLabel.setText(activity.getString(R.string.trim_resolution));
        resLabel.setTextSize(11);
        resLabel.setTextColor(0x8AFFFFFF);
        resLabel.setPadding(0, 0, dp(activity, 10), 0);
        resRow.addView(resLabel);
        final TextView[] chips = new TextView[3];
        final int[] heights = {0, 1080, 720};
        for (int i = 0; i < heights.length; i++) {
            TextView chip = new TextView(activity);
            chip.setText(activity.getString(i == 0 ? R.string.trim_res_original
                    : i == 1 ? R.string.trim_res_1080 : R.string.trim_res_720));
            chip.setTextSize(11);
            chip.setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5));
            chips[i] = chip;
            resRow.addView(chip);
        }
        paintChip(chips[0], true);
        body.addView(resRow);

        final TextView estimate = new TextView(activity);
        estimate.setTextSize(12);
        estimate.setTextColor(0xB0FFFFFF);
        body.addView(estimate);

        final TextView caption = new TextView(activity);
        caption.setTextSize(11);
        caption.setTextColor(0x8AFFFFFF);
        body.addView(caption);

        // The sheet owns the footer: its buttons are the sheet's two verbs, and a
        // row inside the scrolling body would scroll away from the thing it acts on.
        final android.widget.Button living = ModalSheet.button(activity,
                activity.getString(R.string.trim_living_photo), true, false);
        final android.widget.Button whole = ModalSheet.button(activity,
                activity.getString(R.string.trim_whole_clip), false, true);

        // The live readout: times, the estimate, and the Job framing past the line.
        // ONE runnable for all of it, because the memory guard and the number are one
        // decision: the guard may move the resolution chip, and the line must then
        // state the number that chip implies. A second derivation of the same fact is
        // how a sheet ends up showing an estimate for a size it will not submit.
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                final int frames = bar.endFrame() - bar.startFrame();
                startText.setText(hms(bar.startFrame() / fps));
                endText.setText(hms(bar.endFrame() / fps));

                // THE MEMORY GUARD, and what chunking changed about it. A SINGLE
                // batch is still the shape the budget describes, so when the whole
                // range fits one the guard behaves exactly as before -- the user's
                // chip when it can, otherwise the tallest that can. When it does not
                // fit ANYWHERE, length is no longer what refuses the import: the run
                // is split into prompts of BatchBudget.chunkFrames(height) and joined
                // on the device, so the guard's remaining job is the CHUNK size and
                // the number the line states. The user's chip is kept for a chunked
                // run rather than moved: every chip can be chunked, and the count of
                // chunks the user sees is the honest cost of the one they picked.
                final int pick = BatchBudget.pick(frames, sourceHeight, outHeight[0]);
                final boolean single = pick >= 0;
                if (single) outHeight[0] = pick;
                for (int j = 0; j < chips.length; j++) {
                    paintChip(chips[j], heights[j] == outHeight[0]);
                }

                final int height = BatchBudget.heightOf(outHeight[0], sourceHeight);
                final int chunk = BatchBudget.chunkFrames(height);
                final boolean canChunk = chunk > 0;
                final int chunks = single ? 1 : BatchBudget.chunkCount(frames, height);
                // THE NUMBER IS THE BATCH'S, not the range's. For a single batch that
                // is every frame it holds; for a chunked run it is the largest chunk,
                // because per-chunk memory is the thing that decides whether the run
                // can happen at all -- the whole range's total is no longer asked of
                // the card in one go, and stating it would be the old lie in a new
                // place.
                final int perBatch = single ? frames : Math.min(frames, chunk);
                final double gb = BatchBudget.estimateMb(perBatch, height) / 1024.0;
                final float secs = frames * SECONDS_PER_FRAME;
                final boolean job = secs >= JOB_SECONDS;
                String line;
                if (single) {
                    line = activity.getString(R.string.trim_estimate,
                            fmtWait(secs), fmtGb(gb));
                } else if (canChunk) {
                    line = activity.getString(R.string.trim_estimate_chunked,
                            fmtWait(secs), chunks, fmtGb(gb));
                } else {
                    line = activity.getString(R.string.trim_estimate_over,
                            fmtWait(secs), fmtGb(gb));
                }
                if (job) line = line + "\n" + activity.getString(R.string.trim_job_note);
                estimate.setText(line);
                caption.setText(job ? activity.getString(R.string.trim_job_caption) : "");
                caption.setVisibility(job ? View.VISIBLE : View.GONE);
                living.setText(job ? activity.getString(R.string.trim_job_start)
                        : activity.getString(R.string.trim_living_photo));
                living.setEnabled(single || canChunk);
                // THE WHOLE CLIP IS A SUPERSET OF THE RANGE. It needs a length to
                // chunk by, so an unreadable frame count still refuses it -- the
                // "a window claims nothing" rule -- and otherwise it is bounded the
                // same way the range is, by the chunk.
                whole.setEnabled(totalFrames > 0 && canChunk);
            }
        };
        bar.onChange = refresh;

        // The chips are wired AFTER refresh exists: a tap only sets the candidate,
        // and refresh is what decides whether it can stay -- a chip that cannot fit
        // is moved off by the same guard that moves it when the handles drag.
        for (int i = 0; i < heights.length; i++) {
            final int idx = i;
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    outHeight[0] = heights[idx];
                    refresh.run();
                }
            });
        }

        living.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int s = bar.startFrame();
                int f = bar.endFrame() - s;
                sheet.dismiss();
                onChosen.range(s, f, false, outHeight[0]);
            }
        });
        whole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
                onChosen.range(0, 0, true, outHeight[0]);
            }
        });

        sheet.body(body);
        sheet.footerRow(living, whole);
        sheet.show(activity);

        // The filmstrip is a decode per thumbnail, so it arrives off the UI thread
        // and the bar redraws when it does. Until then the bar still works: the
        // handles and the times are arithmetic, not pixels.
        Io.run(activity, new java.util.concurrent.Callable<Bitmap[]>() {
            @Override
            public Bitmap[] call() {
                return thumbs(activity, video, THUMBS);
            }
        }, new Io.Callback<Bitmap[]>() {
            @Override
            public void onDone(Bitmap[] bitmaps, Exception error) {
                reading.setVisibility(View.GONE);
                if (bitmaps != null) bar.setThumbs(bitmaps);
            }
        });
        refresh.run();
    }

    /** "0:04" — the sheet's own clock, because times here are small. */
    private static String hms(float seconds) {
        int s = Math.max(0, Math.round(seconds));
        int m = s / 60;
        return m + ":" + String.format("%02d", s % 60);
    }

    private static String fmtWait(float sec) {
        if (sec < 90) return Math.round(sec) + "s";
        if (sec < 5400) return Math.round(sec / 60f) + " min";
        return (Math.round(sec / 360f) / 10f) + " hr";
    }

    /**
     * "15.2" — the memory estimate's GiB, one decimal. The unit lives in the
     * string resource, so the number is all this returns; a value is never
     * rounded up to a rounder one, because the line is a fact.
     */
    private static String fmtGb(double gb) {
        return String.format("%.1f", gb);
    }

    private static TextView timeText(Activity a) {
        TextView t = new TextView(a);
        t.setTextSize(13);
        t.setTextColor(0xFFEDEFF3);
        return t;
    }

    /**
     * The direct seek: tap a time, flick minutes and seconds, Set.
     *
     * <p>The mockup calls this the ACCESSIBLE path, and it is the reason there is no
     * whole-clip scrub bar: at any zoom a bar is coarse -- eleven seconds per dp on
     * an hour -- so it cannot land on 55:30, and it would be a second timeline to
     * learn. The picker is exact for a five-second clip and for an hour, and a screen
     * reader reads it as a number rather than as a position.
     *
     * <p>Seconds wrap into minutes by hand rather than by carrying: a picker that
     * carried would move the other wheel while the user is still turning this one.
     */
    private static void openPicker(final Activity a, final TrimBar bar, final boolean isStart) {
        android.util.Log.i("Basrelief", "trim picker opened isStart=" + isStart);
        final ModalSheet sheet = new ModalSheet(a, 0.5f);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int total = Math.max(0, Math.round(
                (isStart ? bar.startFrame() : bar.endFrame()) / bar.fps));
        row.addView(wheel(a, R.string.trim_min, 59, Math.min(59, total / 60)));
        row.addView(wheel(a, R.string.trim_sec, 59, total % 60));
        final android.widget.NumberPicker min =
                (android.widget.NumberPicker) ((LinearLayout) row.getChildAt(0)).getChildAt(0);
        final android.widget.NumberPicker sec =
                (android.widget.NumberPicker) ((LinearLayout) row.getChildAt(1)).getChildAt(0);
        android.widget.Button set = ModalSheet.button(a,
                a.getString(R.string.trim_set), true, false);
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bar.seek(isStart, min.getValue() * 60 + sec.getValue());
                sheet.dismiss();
            }
        });
        sheet.body(row);
        sheet.footerRow(set);
        sheet.show(a);
    }

    /** One wheel with a caption under it. */
    private static View wheel(Activity a, int captionRes, int max, int value) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.NumberPicker p = new android.widget.NumberPicker(a);
        p.setMinValue(0);
        p.setMaxValue(max);
        p.setValue(value);
        TextView cap = new TextView(a);
        cap.setText(a.getString(captionRes));
        cap.setTextSize(11);
        cap.setTextColor(0x8AFFFFFF);
        col.addView(p);
        col.addView(cap);
        col.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        return col;
    }

    /** A caption, the time, and ‹ › steppers that move one frame. */
    private static View stepper(final Activity a, int captionRes, final TextView time,
                                final TrimBar bar, final boolean isStart) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView cap = new TextView(a);
        cap.setText(a.getString(captionRes));
        cap.setTextSize(11);
        cap.setTextColor(0x8AFFFFFF);
        row.addView(cap);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = step(a, R.string.trim_step_back);
        TextView fwd = step(a, R.string.trim_step_forward);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { bar.nudge(isStart, -1); }
        });
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { bar.nudge(isStart, +1); }
        });
        // The time itself opens the picker: it is the thing the user wants to set,
        // so it is the thing that answers a tap.
        time.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPicker(a, bar, isStart); }
        });
        line.addView(back);
        line.addView(time);
        line.addView(fwd);
        row.addView(line);
        return row;
    }

    private static TextView step(Activity a, int res) {
        TextView t = new TextView(a);
        t.setText(a.getString(res));
        t.setTextSize(17);
        t.setTextColor(0xB0FFFFFF);
        t.setPadding(dp(a, 10), dp(a, 2), dp(a, 10), dp(a, 2));
        return t;
    }

    /** Frames across the clip, scaled down for the strip. Never throws. */
    private static Bitmap[] thumbs(Activity activity, Uri video, int count) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            // The SHARE URI, read where it is: the sheet no longer waits for the
            // whole clip to be copied into filesDir before it can draw a filmstrip.
            r.setDataSource(activity, video);
            long us = 0;
            try {
                us = Long.parseLong(r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            } catch (Throwable ignored) {
            }
            if (us <= 0) return null;
            Bitmap[] out = new Bitmap[count];
            for (int i = 0; i < count; i++) {
                long at = (long) ((i + 0.5) / count * us);
                Bitmap f = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f == null) continue;
                // A strip tile is about 40dp tall; decoding the full frame for each
                // of ten of them is the difference between a sheet that opens and
                // one that stutters.
                int h = 64;
                int w = Math.max(1, f.getWidth() * h / Math.max(1, f.getHeight()));
                out[i] = Bitmap.createScaledBitmap(f, w, h, true);
                if (out[i] != f) f.recycle();
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /**
     * The bar: filmstrip, the range, and two handles.
     *
     * <p>All state is in FRAMES, and every pixel question is asked through
     * {@link #pxPerFrame()} -- so a zoom changes one number and the handles, the
     * overlay and the times follow from it. The alternative (pixels with a scale
     * factor applied in several places) is how a handle ends up drawn beside the
     * frame it will trim to.
     */
    static final class TrimBar extends View {
        private final float fps;
        private final int totalFrames;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private Bitmap[] thumbs;

        /** The window on screen: first frame, and how many fit on the bar. */
        private float viewStart;
        private float viewFrames;
        /** The trim, in frames. */
        private float start;
        private float end;

        private final float handlePx;
        private final float touchPx;
        private final ScaleGestureDetector pinch;
        private int dragging = 0; // 0 none, 1 start, 2 end, 3 window
        private float downX;
        private float downStart, downEnd, downView;

        Runnable onChange;

        TrimBar(Context c, float fps, int totalFrames) {
            super(c);
            this.fps = fps <= 0 ? 30f : fps;
            this.totalFrames = Math.max(1, totalFrames);
            this.handlePx = c.getResources().getDisplayMetrics().density * 22f; // 44dp wide
            this.touchPx = c.getResources().getDisplayMetrics().density * 28f;
            // A clip's first view is Fit when it is short and the deepest stop when
            // it is long: at Fit the handles on a minute-long clip overlap, which
            // makes the sheet open in a state its own handles cannot be grabbed.
            this.viewStart = 0;
            this.viewFrames = Math.min(this.totalFrames, Math.max(
                    this.totalFrames, fitFrames()));
            this.viewFrames = deepestStopFor(this.totalFrames);
            // The trim starts as the whole clip, clamped to the loop length: a
            // living photo is a loop, and the sheet opens with the range a loop
            // wants rather than with an hour selected.
            this.start = 0;
            this.end = Math.min(this.totalFrames, Math.max(1f, LOOP_SECONDS * this.fps));
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    // Pinch changes the WINDOW only; the trim is the user's, and a
                    // zoom that moved it would be a second thing the same gesture did.
                    float focusFrame = viewStart + (d.getFocusX() / Math.max(1f, getWidth())) * viewFrames;
                    float next = clamp(viewFrames / Math.max(0.1f, d.getScaleFactor()),
                            2f * TrimBar.this.fps, TrimBar.this.totalFrames);
                    viewFrames = next;
                    viewStart = clamp(focusFrame - (d.getFocusX() / Math.max(1f, getWidth())) * next,
                            0, Math.max(0, TrimBar.this.totalFrames - next));
                    invalidate();
                    return true;
                }
            });
        }

        private float fitFrames() {
            return totalFrames;
        }

        /** The three stops: fit, 15s on the bar, or the loop over a 44dp handle. */
        private float deepestStopFor(int frames) {
            float loop = LOOP_SECONDS * fps;
            // 44dp of handle on a ~300dp bar: the bar must show at most
            // loop * (bar / handle) worth of time for the handles to clear.
            float barDp = 300f;
            float maxFrames = loop * (barDp / 44f) * (barDp / 44f);
            return Math.max(loop, Math.min(frames, Math.max(maxFrames, 15f * fps)));
        }

        void setThumbs(Bitmap[] t) {
            thumbs = t;
            invalidate();
        }

        float pxPerFrame() {
            return getWidth() / Math.max(1f, viewFrames);
        }

        int startFrame() { return Math.round(start); }
        int endFrame() { return Math.round(end); }

        /**
         * Jump a handle to an exact time, the picker's Set.
         *
         * <p>Clamped to the trim's own bounds and the clip's, so a Set that lands
         * past the other handle moves the handle to the nearest legal frame rather
         * than swapping the two or leaving the range inverted.
         */
        void seek(boolean isStart, float seconds) {
            float frame = clamp(seconds * fps, 0, totalFrames);
            if (isStart) {
                start = clamp(frame, 0, Math.max(0, end - 1));
                ensureVisible(start);
            } else {
                end = clamp(frame, start + 1, totalFrames);
                ensureVisible(end);
            }
            invalidate();
            if (onChange != null) onChange.run();
        }

        /** One frame, clamped to the trim's own bounds and to the clip. */
        void nudge(boolean isStart, int delta) {
            if (isStart) {
                start = clamp(start + delta, 0, Math.max(0, end - 1));
                ensureVisible(start);
            } else {
                end = clamp(end + delta, start + 1, totalFrames);
                ensureVisible(end);
            }
            invalidate();
            if (onChange != null) onChange.run();
        }

        private void ensureVisible(float frame) {
            if (frame < viewStart) viewStart = Math.max(0, frame - viewFrames * 0.1f);
            if (frame > viewStart + viewFrames) {
                viewStart = Math.min(Math.max(0, totalFrames - viewFrames),
                        frame - viewFrames * 0.9f);
            }
        }

        private static float clamp(float x, float a, float b) {
            return Math.max(a, Math.min(b, x));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            float x = e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downStart = start;
                    downEnd = end;
                    downView = viewStart;
                    float sx = (start - viewStart) * pxPerFrame();
                    float ex = (end - viewStart) * pxPerFrame();
                    if (Math.abs(x - sx) < touchPx) dragging = 1;
                    else if (Math.abs(x - ex) < touchPx) dragging = 2;
                    else dragging = 3;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pinch.isInProgress()) return true;
                    float df = (x - downX) / Math.max(0.001f, pxPerFrame());
                    if (dragging == 1) {
                        start = clamp(downStart + df, 0, end - 1);
                    } else if (dragging == 2) {
                        end = clamp(downEnd + df, start + 1, totalFrames);
                    } else if (dragging == 3) {
                        viewStart = clamp(downView - df, 0,
                                Math.max(0, totalFrames - viewFrames));
                    }
                    invalidate();
                    if (onChange != null) onChange.run();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float strip = h * 0.56f;

            // The filmstrip: whatever arrived, stretched to the window. A strip that
            // could not be read leaves an empty bar, which is honest -- the handles
            // and times still work.
            if (thumbs != null && thumbs.length > 0) {
                float per = w / thumbs.length;
                for (int i = 0; i < thumbs.length; i++) {
                    Bitmap b = thumbs[i];
                    if (b == null) continue;
                    float at = viewStart + (i + 0.5f) / thumbs.length * viewFrames;
                    float px = at * pxPerFrame() - per / 2f;
                    rect.set(px, 0, px + per, strip);
                    c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), rect, paint);
                }
            } else {
                paint.setColor(0x22FFFFFF);
                c.drawRect(0, 0, w, strip, paint);
            }

            // Outside the range is dimmed; inside is left alone. The dim is the
            // sheet's whole statement of what will be kept.
            float sx = (start - viewStart) * pxPerFrame();
            float ex = (end - viewStart) * pxPerFrame();
            paint.setColor(0xB0000000);
            c.drawRect(0, 0, Math.max(0, sx), strip, paint);
            c.drawRect(Math.min(w, ex), 0, w, strip, paint);

            // The handles: 44dp of touch, drawn as a plate with a grip line.
            paint.setColor(0xFFEDEFF3);
            drawHandle(c, sx, strip);
            drawHandle(c, ex, strip);

            // The loop band under the strip: the length a living photo wants, so
            // "too short to loop well" is visible rather than explained.
            float loopPx = LOOP_SECONDS * fps * pxPerFrame();
            paint.setColor(0x5520C0FF);
            c.drawRect(sx, strip + 2, Math.min(ex, sx + loopPx), strip + 5, paint);
        }

        private void drawHandle(Canvas c, float x, float strip) {
            paint.setColor(0xFFEDEFF3);
            rect.set(x - handlePx / 2f, 0, x + handlePx / 2f, strip);
            c.drawRoundRect(rect, handlePx / 4f, handlePx / 4f, paint);
            paint.setColor(0x66000000);
            c.drawRect(x - 1, strip * 0.28f, x + 1, strip * 0.72f, paint);
        }
    }
}
