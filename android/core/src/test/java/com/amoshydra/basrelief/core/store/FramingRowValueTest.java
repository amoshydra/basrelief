package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The three numbers a framing row prints, and — the part that matters — that Pan
 * and Origin cannot be converted by one rule.
 *
 * <p><b>What this pins, and the two bugs it exists for.</b> The first is the display
 * half of a defect {@code ConfigTest} and {@code PerItemSettingsTest} already pin on
 * the stored half: <b>pan is in pixels and must not be clamped to Origin's fraction
 * range.</b> That rule is easy to state and easy to break by making the two keys
 * share a conversion, and a shared conversion has to pick a clamp — and the only
 * clamps in play are each other's. So both directions are asserted with values that
 * cannot survive the wrong one.
 *
 * <p>The second is the <b>round trip</b>, which is what "the slider displays exactly
 * what the gesture will write" actually requires. A gesture commits a float and the
 * row prints an integer; if the two derivations were written separately they would
 * agree today and drift the first time one was rounded, clamped or unit-changed. The
 * measured numbers in these tests are the ones a Pixel 7 Pro produced while auditing
 * the tap-to-orbit and two-finger-pan paths, so a change of shape shows up as a
 * change of number rather than as a subtly wrong bar.
 */
public class FramingRowValueTest {

    // ------------------------------------------------------------- origin

    @Test
    public void originIsThousandthsSoAThousandthStepIsWhole() {
        assertEquals(500, FramingRowValue.originThousandths(0.5f));
        assertEquals(-500, FramingRowValue.originThousandths(-0.5f));
        assertEquals(1, FramingRowValue.originThousandths(0.001f));
        assertEquals(0, FramingRowValue.originThousandths(0f));
    }

    /**
     * The device's own numbers, so the audit's evidence is pinned rather than a
     * paraphrase of it. {@code -0.15712592} is what a tap on the Pixel 7 Pro put in
     * the renderer; the row printed nothing at all for that tap, which is the
     * defect — but the number it should have printed is fixed here.
     */
    @Test
    public void theNumberATapProducesRoundTripsThroughTheRow() {
        assertEquals(-157, FramingRowValue.originThousandths(-0.15712592f));
        assertEquals(258, FramingRowValue.originThousandths(0.2577801f));
        // The same value a hundredth apart still gets its own row: the reason the
        // row carries three decimals is that two printed neighbouring steps as the
        // same number, and the control looked stuck.
        assertEquals(-157, FramingRowValue.originThousandths(-0.15712f));
        assertEquals(-157, FramingRowValue.originThousandths(-0.15713f));
    }

    /**
     * Origin is NOT clamped here.
     *
     * <p>The row's own range is ±{@link SurfaceSettings#ORIGIN_LIMIT} and
     * {@code BasreliefSlider.setValue} clamps into it, which is the one place a clamp
     * belongs. Asserting the absence here is what stops a future "just clamp it
     * here" — the {@code FramingRows} comment records that the rows once used a
     * literal ±1000, twice the far stop, so the control previewed the void and the
     * store kept a third number.
     */
    @Test
    public void originIsNotClampedToAPixelBoundOrATightenedFraction() {
        assertEquals(86500, FramingRowValue.originThousandths(86.5f));
        assertEquals(1000, FramingRowValue.originThousandths(1.0f));
        assertEquals(-1000, FramingRowValue.originThousandths(-1.0f));
        assertTrue(SurfaceSettings.ORIGIN_LIMIT == 0.5f);
    }

    // ---------------------------------------------------------------- pan

    /**
     * Pan is whole surface pixels, and the bound it is shown against is the
     * renderer's own — the larger of the surface's two dimensions.
     *
     * <p>3120 is the Pixel 7 Pro's long edge, which is the value the device reported
     * for {@code max(surfaceW, surfaceH)}.
     */
    @Test
    public void panIsWholePixelsClampedToTheRenderersBound() {
        assertEquals(0, FramingRowValue.showablePan(0f, 3120));
        assertEquals(86, FramingRowValue.showablePan(86.4f, 3120));
        assertEquals(-86, FramingRowValue.showablePan(-86.4f, 3120));
        assertEquals(3120, FramingRowValue.showablePan(4000f, 3120));
        assertEquals(-3120, FramingRowValue.showablePan(-4000f, 3120));
        assertEquals(3120, FramingRowValue.showablePan(3120f, 3120));
    }

    /**
     * Round half up, the platform's rule, with its asymmetry left in place.
     *
     * <p>{@code -86.5} being {@code -86} is {@link Math#round}'s doing. Correcting
     * it would be a second rule to explain and would not make the row more accurate —
     * but it must be pinned, because a rounding change here is exactly the kind of
     * edit that silently desynchronises the row from the stored float.
     */
    @Test
    public void panRoundsHalfUpAndTheAsymmetryIsDeliberate() {
        assertEquals(87, FramingRowValue.showablePan(86.5f, 3120));
        assertEquals(-86, FramingRowValue.showablePan(-86.5f, 3120));
        assertEquals(1, FramingRowValue.showablePan(0.5f, 3120));
        assertEquals(0, FramingRowValue.showablePan(0.4f, 3120));
    }

    /**
     * THE UNITS TRAP, both directions, with numbers that cannot pass either way.
     *
     * <p>A pan of {@code 86.5} pixels and an Origin of {@code 0.0865} are one
     * conversion apart. Read with the other's rule:
     *
     * <ul>
     *   <li>pan through Origin's rule — scale by 1000 — would print {@code 86500},
     *       which the row's own range throws away to {@code 500}: a 13-pixel drag
     *       reads as the far stop. Asserted, because {@link
     *       SurfaceSettings#ORIGIN_LIMIT} is the number that would do it.
     *   <li>Origin through pan's rule would print {@code 87} thousandths of a
     *       fraction that is 0.0865, and Origin's clamp is ±500 so nothing would
     *       notice: the camera would swing 87x further than the finger asked for.
     * </ul>
     *
     * <p>So the two must not share a conversion, and the test says it in the only
     * place it can be enforced: by putting both wrong answers on record.
     */
    @Test
    public void panAndOriginCannotBeReadThroughEachOthersRule() {
        int limit = 3120;
        int originMax = Math.round(SurfaceSettings.ORIGIN_LIMIT * 1000f);

        // An 86.5-pixel pan read with Origin's rule is 86500 thousandths, and the
        // row's own range then throws it away to the far stop: a 13-pixel drag
        // printed as "the edge of the plane".
        assertEquals(86500, FramingRowValue.originThousandths(86.5f));
        assertEquals(500, Math.min(originMax, FramingRowValue.originThousandths(86.5f)));

        // The same 8.65% camera swing read with pan's rule is 0 pixels, because a
        // fraction of a pixel is not a distance anyone aims at. Nothing complains:
        // pan has no range clamp to notice, which is why this is the quieter of the
        // two failures and the one worth asserting.
        assertEquals(87, FramingRowValue.originThousandths(0.0865f));
        assertEquals(0, FramingRowValue.showablePan(0.0865f, limit));
        assertEquals(0, FramingRowValue.showablePan(-0.0865f, limit));
    }

    /**
     * A surface with no size yet must not crush the value.
     *
     * <p>{@code ReliefRenderer.setPan} documents the same trap it avoids: clamping a
     * pixel-valued pan against a zero bound is {@code max(-0f, -135f)}, so the offset
     * would be silently destroyed and then persisted back. A display conversion that
     * did the same would show a pan the user stored on a real screen as zero.
     */
    @Test
    public void noSurfaceYetShowsThePanRatherThanZeroingIt() {
        assertEquals(-135, FramingRowValue.showablePan(-135.4f, 0));
        assertEquals(4000, FramingRowValue.showablePan(4000f, 0));
    }

    // --------------------------------------------------------------- zoom

    @Test
    public void zoomPercentIsTheInverseOfTheStoredScale() {
        assertEquals(100, FramingRowValue.zoomPercent(1f));
        assertEquals(142, FramingRowValue.zoomPercent(1.42f));
        assertEquals(200, FramingRowValue.zoomPercent(2f));
        assertEquals(25, FramingRowValue.zoomPercent(0.25f));
        // The gesture and the row must be handed the same number, which is what
        // makes "the pinch committed exactly what the control showed" true.
        assertEquals(FramingRowValue.zoomPercent(1.376f),
                new SurfaceSettings(90, FramingRowValue.zoomPercent(1.376f), 60,
                        0f, 0f, 0f, 0f, 0, 0, 0f, 0f, SurfaceSettings.DEFAULT_SPEED).zoom);
        assertEquals(1.376f, new SurfaceSettings(90, FramingRowValue.zoomPercent(1.376f),
                60, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f, SurfaceSettings.DEFAULT_SPEED).zoomScale(), 0.005f);
    }

    // --------------------------------------------------------- the round trip

    /**
     * The row's integer must survive the store and come back as itself.
     *
     * <p>This is the invariant {@code FramingRows.refresh} was written for — "a
     * control must display exactly what the gesture will write" — expressed as
     * arithmetic. It holds because both sides call the same function, which is why
     * there is only one function and why it lives in {@code :core}: two derivations
     * of one value agree until the day one of them is edited, and then the gesture
     * writes what the control happened to be showing.
     */
    @Test
    public void whatTheRowPrintsIsWhatTheStoreKeeps() {
        SurfaceSettings stored = new SurfaceSettings(90, 142, 60,
                0.2577801f, -0.15712592f, 86.5f, -126f, 0, 0, 0f, 0f, SurfaceSettings.DEFAULT_SPEED);
        assertEquals(258, FramingRowValue.originThousandths(stored.originX));
        assertEquals(-157, FramingRowValue.originThousandths(stored.originY));
        assertEquals(87, FramingRowValue.showablePan(stored.panX, 3120));
        assertEquals(-126, FramingRowValue.showablePan(stored.panY, 3120));
        assertEquals(142, stored.zoom);
    }

    /**
     * A row's integer must go back into the renderer as the value it came from.
     *
     * <p>The other direction of the same round trip, and the one a nudge exercises: a
     * one-step change on the Origin row has to move the camera by a thousandth of the
     * plane and come back to the number the row was already showing. If
     * {@code originFraction} were written as a multiply, or if the row's scale and this
     * one's drifted apart, the row would walk away from the picture one nudge at a time
     * and nothing would notice until the offsets were visible.
     */
    @Test
    public void aRowsIntegerComesBackAsTheNumberItCameFrom() {
        int[] steps = {-500, -157, -1, 0, 1, 157, 258, 500};
        for (int step : steps) {
            float fraction = FramingRowValue.originFraction(step);
            assertTrue("origin " + step + " left the reachable range",
                    Math.abs(fraction) <= SurfaceSettings.ORIGIN_LIMIT);
            assertEquals("origin " + step + " did not round-trip",
                    step, FramingRowValue.originThousandths(fraction));
        }
        // And a nudge is a whole thousandth: one step is exactly one, not 1.0000001.
        assertEquals(0.001f, FramingRowValue.originFraction(1)
                - FramingRowValue.originFraction(0), 0f);
    }
}