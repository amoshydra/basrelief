package com.amoshydra.basrelief.core.store;

/**
 * The three numbers a framing row prints, derived from the renderer's live values.
 *
 * <p><b>Why this is a class and not arithmetic at the call site.</b> The studio's
 * four framing rows and its two touch gestures all need the same answer to one
 * question — "the renderer holds this float; what whole number does the row show,
 * and what does the store keep?" — and they need it to be the <i>same</i> answer. That
 * has already gone wrong in this app twice, in both directions: the Origin rows used
 * a literal range twice the model's own bound, so the control previewed a position
 * nothing could render and the store clamped a third number; and the Pan rows used a
 * flat bound that disagreed with {@code ReliefRenderer}'s clamp, so the last stretch
 * of drag moved the slider and not the picture. Both were two places computing one
 * bound. This is that one place, in {@code :core} so it can be checked without a
 * device — the same reason {@link Picture} and {@link com.amoshydra.basrelief.core.render.LightDirection}
 * are pure functions rather than arithmetic trapped in a shader's caller.
 *
 * <p><b>Pan is in PIXELS and Origin is a FRACTION, and the two must never share a
 * conversion.</b> That is not a detail of the naming. {@code originThousandths(86.5f)}
 * is 86500 — a fraction so far outside the plane that the row's own range clamp throws
 * it away — and {@code showablePan(0.5f, 3120)} is {@code 1}, a half-pixel of camera
 * travel rounded up to a whole pixel. A single "float to slider integer" helper shared
 * by both keys would therefore have to clamp, and the only clamp available is the
 * other's: pan clamped to Origin's ±2.0 would make every drag past two pixels
 * invisible, and Origin scaled by pan's pixel bound would put the far stop 3120 units
 * away. {@code ConfigTest} and {@code PerItemSettingsTest} already pin that the stored
 * pan is pixels and is not clamped to Origin's range; this is the same rule applied
 * to the two numbers a slider prints, which is where it actually reaches the user.
 *
 * <p><b>The renderer is the authority, and this is only its display.</b> These three
 * functions convert a value the renderer is already holding; they never decide what
 * the value should be. {@link #showablePan} is the one place a displayed pan is
 * clamped to the surface's own bound, and it is deliberately <i>display</i> clamping:
 * {@code ReliefRenderer.setPan}'s comment promises that a pan stored on a larger
 * screen survives a visit to a smaller one, so the store keeps the raw number and only
 * the row shows what this surface can render.
 */
public final class FramingRowValue {

    private FramingRowValue() {
    }

    /**
     * The Origin row's number: thousandths of the camera's reachable travel.
     *
     * <p>Carried in thousandths so a slider can be integral and still step by a
     * thousandth — two decimals printed neighbouring steps as the same number, which
     * is what made the row look stuck. Not clamped here: the row's own range is
     * {@code ±SurfaceSettings.ORIGIN_LIMIT}, derived from the model's constant rather
     * than typed out, and {@code BasreliefSlider.setValue} clamps into it. Clamping in
     * two places is how the row came to preview a position the model cannot mean.
     *
     * @param orbit the renderer's live orbit fraction, as {@code getOrbitX/Y} gives it
     */
    public static int originThousandths(float orbit) {
        return (int) Math.round(orbit * 1000f);
    }

    /**
     * The inverse of {@link #originThousandths}: a slider's integer as the fraction
     * the renderer and the store both hold.
     *
     * <p>Named so the pair cannot be applied one way and not the other. The rounding
     * is asymmetric on purpose and is {@link #originThousandths}'s to undo:
     * {@code -157} thousandths is {@code -0.157f}, which reads back as {@code -157} and
     * not {@code -158} — the float simply cannot hold a third more decimal, which is
     * exactly why the row carries a thousandth at all.
     */
    public static float originFraction(int thousandths) {
        return thousandths / 1000f;
    }

    /**
     * The Pan row's number: whole surface pixels, shown clamped to what this surface
     * can render.
     *
     * <p>Rounds half <b>up</b>, as {@link Math#round(float)} does — so {@code 86.5f}
     * is {@code 87} and {@code -86.5f} is {@code -86}. The asymmetry is the
     * platform's, deliberately not corrected here: a row that rounded away from zero
     * would need its own rule to explain, and one that disagreed with the store's own
     * reading of the same float would be worse than either.
     *
     * <p>The clamp is {@code ±limit} where {@code limit} is the renderer's own bound —
     * {@code max(surfaceW, surfaceH)}, read from the GL view whose layout size is the
     * size the renderer clamps against. A non-positive limit means the surface does
     * not exist yet, and the value is then shown unclamped rather than crushed to
     * zero, which is the same reason {@code ReliefRenderer.setPan} accepts an
     * unclamped value before the first layout.
     *
     * @param pan   the renderer's live pan offset, in pixels
     * @param limit the renderer's bound for this surface, in pixels
     */
    public static int showablePan(float pan, int limit) {
        long v = Math.round(pan);
        if (limit > 0) {
            if (v > limit) v = limit;
            if (v < -limit) v = -limit;
        }
        return (int) v;
    }

    /**
     * The Zoom row's number, which is also what the store keeps.
     *
     * <p>One function because the pinch writes this number and the row prints it: two
     * derivations of one value is how a gesture commits a zoom the control never
     * showed. Zoom is stored as whole percent and rendered as the fraction
     * {@link SurfaceSettings#zoomScale()} returns, so the conversion is the one
     * inverse of that.
     *
     * <p>Not clamped here either — {@link #showablePan}'s comment explains why
     * clamping at display time and again at store time is the wrong shape, and
     * {@link SurfaceSettings} already owns the bound for both.
     *
     * @param zoomScale the renderer's live zoom, where 1 is no magnification
     */
    public static int zoomPercent(float zoomScale) {
        return (int) Math.round(zoomScale * 100f);
    }
}