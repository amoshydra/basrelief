package com.amoshydra.basrelief.core.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The splat mode's two numbers: the focal an image with none is drawn at, and the
 * card size that makes the screen show a photograph rather than a screen door.
 *
 * <p>They live in {@code :core} for the reason the whole package does: both are
 * arithmetic that is easy to get quietly wrong and impossible to check without a
 * device once they are a vertex shader. The trap in each is a sign or a scale,
 * which is exactly what a test can hold and a screenshot cannot.
 */
public class SplatTest {

    /** The height an image of that many pixels subtends, inverted through the pinhole relation. */
    private static double impliedFovDeg(float focalPx, float imageHeightPx) {
        return Math.toDegrees(2.0 * Math.atan(imageHeightPx * 0.5 / focalPx));
    }

    /**
     * The fallback is the documented field of view and not a round number in
     * pixels: {@code H/2 / tan(FOV/2)} inverts to the FOV for every height, which
     * is the only property that keeps the look identical on a 1080p and a 4K
     * image. A focal that were a constant would make the same lens wider on the
     * taller image.
     */
    @Test
    public void theFallbackIsTheDocumentedFieldOfView() {
        for (float h : new float[] {480f, 1080f, 2560f, 4320f}) {
            float f = Splat.fallbackFocalPx(h);
            assertTrue("a positive height must imply a focal", f > 0f);
            assertEquals("the fallback must be " + Splat.DEFAULT_FOV_DEG + " degrees at h=" + h,
                    Splat.DEFAULT_FOV_DEG, impliedFovDeg(f, h), 1e-3);
        }
        // Scaling the image scales the focal, which is what makes it the same lens.
        assertEquals(Splat.fallbackFocalPx(1080f) * 2f, Splat.fallbackFocalPx(2160f), 1e-3f);
    }

    /**
     * A height that cannot imply a focal is "no claim", not a division: 0, a
     * negative height and a NaN all read as 0 rather than as a value the caller
     * would then divide by.
     */
    @Test
    public void aHeightThatClaimsNothingImpliesNothing() {
        assertEquals(0f, Splat.fallbackFocalPx(0f), 0f);
        assertEquals(0f, Splat.fallbackFocalPx(-100f), 0f);
        assertEquals(0f, Splat.fallbackFocalPx(Float.NaN), 0f);
    }

    /** A row's own focal wins; an absent, negative or non-finite one is the default. */
    @Test
    public void knownFocalsAreKeptAndUnknownOnesFallBack() {
        assertEquals(2643f, Splat.effectiveFocalPx(2643f, 2560f), 0f);
        // The literal "NaN" a hand-edited blob can carry must not reach a shader.
        assertEquals(Splat.fallbackFocalPx(2560f),
                Splat.effectiveFocalPx(Float.NaN, 2560f), 1e-3f);
        assertEquals(Splat.fallbackFocalPx(2560f), Splat.effectiveFocalPx(0f, 2560f), 1e-3f);
        assertEquals(Splat.fallbackFocalPx(2560f), Splat.effectiveFocalPx(-5f, 2560f), 1e-3f);
    }

    /**
     * The depth scale the shader computes from these two numbers is the ratio of
     * the image plane's distance in frame units -- f/H -- to the default's, so it
     * is 1 at the default, scales linearly with the focal, and is a pure ratio of
     * {@code f/H}: halving the height and halving the focal leaves it unchanged,
     * which is what "the same lens on a smaller image" means.
     */
    @Test
    public void theDepthScaleIsTheImagePlanesDistanceRelativeToTheDefault() {
        float h = 2560f;
        float dflt = Splat.fallbackFocalPx(h);
        // The ratio the shader forms: uFocal / uRefFocal.
        assertEquals(1f, Splat.effectiveFocalPx(0f, h) / dflt, 1e-6f);
        assertEquals(1f, Splat.effectiveFocalPx(dflt, h) / dflt, 1e-6f);
        assertEquals(2f, Splat.effectiveFocalPx(2f * dflt, h) / dflt, 1e-5f);
        assertEquals(0.5f, Splat.effectiveFocalPx(0.5f * dflt, h) / dflt, 1e-5f);
        assertEquals("the same lens on a smaller image is the same gain",
                Splat.effectiveFocalPx(dflt, h) / dflt,
                Splat.effectiveFocalPx(dflt * 0.5f, h * 0.5f)
                        / Splat.fallbackFocalPx(h * 0.5f), 1e-5f);
        // No height means no reference to normalise against. The row's focal is
        // still the effective focal (it is a real measurement); what is missing is
        // the divisor, and the shader's rule for that is the default look (a ratio
        // against nothing is not a claim) rather than a division.
        assertEquals(2643f, Splat.effectiveFocalPx(2643f, 0f), 0f);
        assertEquals(0f, Splat.fallbackFocalPx(0f), 0f);
        assertEquals(0f, Splat.effectiveFocalPx(0f, 0f), 0f);
    }

    /**
     * The stored lens is a RATIO and not a pixel count: the same lens on a smaller
     * frame is the same number, which is the whole reason it is stored
     * dimensionless. The renderer's surface, the decoded bitmap's height and the
     * node's uploaded frame height can all differ; the ratio cannot.
     */
    @Test
    public void aCloudsLensIsADimensionlessFocalOverHeight() {
        float f = 1411.2f, h = 1632f;
        assertEquals(f / h, Splat.focalOverHeight(f, h), 1e-6f);
        // The same lens on a smaller frame is the same ratio.
        assertEquals(Splat.focalOverHeight(f, h),
                Splat.focalOverHeight(f * 0.5f, h * 0.5f), 1e-6f);
        // Scaling only the frame is a different lens.
        assertTrue(Splat.focalOverHeight(f, h * 2f) < Splat.focalOverHeight(f, h));
        // A missing focal or height claims nothing, rather than dividing.
        assertEquals(0f, Splat.focalOverHeight(0f, h), 0f);
        assertEquals(0f, Splat.focalOverHeight(f, 0f), 0f);
        assertEquals(0f, Splat.focalOverHeight(-3f, h), 0f);
        assertEquals(0f, Splat.focalOverHeight(Float.NaN, h), 0f);
        assertEquals(0f, Splat.focalOverHeight(f, Float.NaN), 0f);
    }

    /**
     * The field of view the camera is drawn at is the pinhole relation inverted: a
     * lens ratio implies exactly the vertical fov a frame of height h and focal f
     * implies. A ratio that claims nothing answers 0, which the caller reads as
     * "use the fallback lens" rather than inverting.
     */
    @Test
    public void theFovyInvertsThePinholeRelation() {
        for (float[] fh : new float[][] {{1411.2f, 1632f}, {2036.5f, 2560f}, {500f, 4000f}}) {
            float f = fh[0], h = fh[1];
            assertEquals("f=" + f + " h=" + h, impliedFovDeg(f, h),
                    Splat.fovyForFocalOverHeight(Splat.focalOverHeight(f, h)), 1e-3);
        }
        // The demo cloud's own lens is the fallback constant the renderer keeps
        // (SplatRenderer.SOURCE_FOVY), so the conversion agrees with the look every
        // cloud generated before this field existed still gets.
        assertEquals(64.3, Splat.fovyForFocalOverHeight(2036.5f / 2560f), 0.1);
        assertEquals(0f, Splat.fovyForFocalOverHeight(0f), 0f);
        assertEquals(0f, Splat.fovyForFocalOverHeight(-0.5f), 0f);
        assertEquals(0f, Splat.fovyForFocalOverHeight(Float.NaN), 0f);
    }

    /**
     * The coverage constant's one job: a card must be at least the cell it
     * replaces, or the reference plane is a screen door. It is asserted as a
     * range, because a card much larger than its cell is a different defect --
     * the picture blurs into itself.
     */
    @Test
    public void aCardCoversTheCellItReplacesWithoutSwallowingItsNeighbour() {
        for (int grid : new int[] {64, 256, 384, 512}) {
            float card = Splat.cardSize(grid);
            float cell = 1f / grid;
            assertTrue("card " + card + " must cover the cell " + cell + " at grid " + grid,
                    card >= cell);
            assertTrue("card " + card + " must not cover two cells at grid " + grid,
                    card < 2f * cell);
        }
        assertEquals("no grid, no card", 0f, Splat.cardSize(0), 0f);
        assertEquals("no grid, no card", 0f, Splat.cardSize(-3), 0f);
    }

    /**
     * The level is an index into {@link Splat#GRID_LEVELS} and nothing else: the
     * two ways to get it wrong are an index off the end and a stored value that was
     * never a level at all, and both answer the default rather than a grid nobody
     * chose. The default is the field's own sampling, which is the look the mode
     * shipped with — a level pref that arrived on an upgrade must not change it.
     */
    @Test
    public void aLevelIsAnIndexIntoTheGridsAndAnythingElseIsTheDefault() {
        assertEquals(Splat.DEFAULT_FIELD_GRID, Splat.GRID_LEVELS[0]);
        assertEquals(Splat.GRID_LEVELS[0], Splat.gridForLevel(0));
        for (int i = 0; i < Splat.GRID_LEVELS.length; i++) {
            assertEquals(Splat.GRID_LEVELS[i], Splat.gridForLevel(i));
        }
        // Monotonic: a level that drew a SMALLER grid than the one below it would
        // make the control a lie in one direction and the cost curve a lie in the
        // other.
        for (int i = 1; i < Splat.GRID_LEVELS.length; i++) {
            assertTrue("levels must not go backwards",
                    Splat.GRID_LEVELS[i] > Splat.GRID_LEVELS[i - 1]);
        }
        assertEquals(Splat.GRID_LEVELS[0], Splat.gridForLevel(-1));
        assertEquals(Splat.GRID_LEVELS[0], Splat.gridForLevel(Splat.GRID_LEVELS.length));
        assertEquals(Splat.GRID_LEVELS[0], Splat.gridForLevel(9999));
    }

    /**
     * The dev SIZE knob's clamp, and the one wrong answer a screenshot cannot tell
     * from the right one: a value that cannot claim a number.
     *
     * <p>The floor is a quantity the shader adds to a covariance, so a NaN reaching
     * it poisons every splat's conic and the whole cloud disappears; a value below
     * the range is the artifact's own setting (the screen-door returns) and a value
     * above it is a look nobody measured. So a non-finite pref answers the DEFAULT,
     * not the nearest bound — that is the {@code DepthSource} "a window claims
     * nothing" rule applied to a number, and it is the assertion that would fail if
     * this were written as a plain min/max, where {@code NaN} compares false and
     * falls through to the value.
     */
    @Test
    public void theSizeKnobClampsAndAValueThatClaimsNothingIsTheDefault() {
        assertEquals(Splat.EPS_2D_DEFAULT, Splat.clampEps2d(Splat.EPS_2D_DEFAULT), 0f);
        assertEquals("inside the range is kept exactly",
                0.5f, Splat.clampEps2d(0.5f), 0f);
        assertEquals("below the range is the floor",
                Splat.EPS_2D_MIN, Splat.clampEps2d(0f), 0f);
        assertEquals("below the range is the floor",
                Splat.EPS_2D_MIN, Splat.clampEps2d(-3f), 0f);
        assertEquals("above the range is the ceiling",
                Splat.EPS_2D_MAX, Splat.clampEps2d(100f), 0f);
        assertEquals("a NaN claims nothing and must be the default, not a NaN",
                Splat.EPS_2D_DEFAULT, Splat.clampEps2d(Float.NaN), 0f);
        assertEquals("an infinity claims nothing",
                Splat.EPS_2D_DEFAULT, Splat.clampEps2d(Float.POSITIVE_INFINITY), 0f);
    }

    /**
     * The dev BLUR knob's clamp: one-sided, so 0 is the identity and a negative or
     * non-finite value is that identity rather than its magnitude.
     *
     * <p>The failure this pins is quiet: a {@code -2} read as a magnitude would
     * blur exactly as much as {@code +2} while the label printed "-2.0", and a NaN
     * radius would reach the shader's weight term and zero every tap.
     */
    @Test
    public void theBlurKnobIsOneSidedAndZeroIsTheIdentity() {
        assertEquals("0 is the identity", 0f, Splat.clampBlurPx(0f), 0f);
        assertEquals(Splat.BLUR_DEFAULT, Splat.clampBlurPx(0f), 0f);
        assertEquals("inside the range is kept exactly", 3f, Splat.clampBlurPx(3f), 0f);
        assertEquals("above the range is the ceiling", Splat.BLUR_MAX, Splat.clampBlurPx(99f), 0f);
        assertEquals("a negative radius is the identity, not its magnitude",
                0f, Splat.clampBlurPx(-2f), 0f);
        assertEquals("a NaN radius claims nothing",
                Splat.BLUR_DEFAULT, Splat.clampBlurPx(Float.NaN), 0f);
    }

    /**
     * The floor is a fraction of the CELL, and the cell is the viewport divided by
     * the splat count -- so one setting is one effect at every resolution and every
     * zoom, which is the property a number of pixels cannot have.
     *
     * <p>This is the bug it exists for, in the numbers it was measured in. One
     * cloud's smooth sky, high-frequency energy against the photograph's own: at
     * the cloud's 912x1984 frame it needed 6 px (2.5 left 3.0x, 6 reached 0.67x);
     * at the 1440x3120 the phone draws it into it needed 15 px (2.5 left 7.3x, and
     * 8 -- the knob's old maximum -- still left 2.1x). Both are 0.5 of the cell.
     * So the assertion that matters is not either pixel value but that the setting
     * is resolution-free: the floor over the cell is the same number at both, and
     * the phone's floor is the cloud's scaled by (1440/912)^2.
     */
    @Test
    public void theFloorIsAFractionOfTheCellNotANumberOfPixels() {
        int count = 149504;
        float cloud = Splat.eps2dFor(0.5f, 912, 1984, count, 1f);
        float phone = Splat.eps2dFor(0.5f, 1440, 3133, count, 1f);
        assertEquals("the cloud's own frame needs 6 px at half a cell",
                6f, cloud, 0.1f);
        assertEquals("and the phone's needs 15 px at the SAME setting",
                15f, phone, 0.2f);
        assertEquals(0.5f, cloud / (912f * 1984f / count), 1e-5f);
        assertEquals(0.5f, phone / (1440f * 3133f / count), 1e-5f);
        assertEquals("the phone's floor is the cloud's scaled by the resolution "
                + "ratio squared -- a fixed number of pixels is the same at neither",
                (1440f / 912f) * (1440f / 912f), phone / cloud, 1e-3f);
    }

    /**
     * Zoom magnifies the cell's width AND its height, so it enters squared: a floor
     * that grew linearly would close the gaps at 1x and leave them open at 2x,
     * which is the same failure one axis over.
     */
    @Test
    public void zoomEntersSquaredBecauseItMagnifiesBothAxes() {
        int count = 100000;
        float one = Splat.eps2dFor(0.5f, 1000, 2000, count, 1f);
        float two = Splat.eps2dFor(0.5f, 1000, 2000, count, 2f);
        assertEquals("twice the magnification is four times the floor",
                4f, two / one, 1e-4f);
        assertEquals("and it is still half the cell",
                0.5f, two / (1000f * 2000f * 4f / count), 1e-5f);
    }

    /**
     * A cell that cannot be claimed answers zero, not a guess -- the clamp's rule
     * one level up. Zero is the identity for a floor, so a cloud the renderer has
     * not counted draws exactly as it would with the floor off, rather than with a
     * floor derived from a count of nothing.
     */
    @Test
    public void aCellThatClaimsNothingIsTheIdentity() {
        assertEquals("no splats", 0f, Splat.eps2dFor(0.5f, 1000, 2000, 0, 1f), 0f);
        assertEquals("no viewport width", 0f, Splat.eps2dFor(0.5f, 0, 2000, 100, 1f), 0f);
        assertEquals("no viewport height", 0f, Splat.eps2dFor(0.5f, 1000, 0, 100, 1f), 0f);
        assertEquals("no fraction", 0f, Splat.eps2dFor(0f, 1000, 2000, 100, 1f), 0f);
        assertEquals("a NaN fraction claims nothing",
                0f, Splat.eps2dFor(Float.NaN, 1000, 2000, 100, 1f), 0f);
        // A zoom that is not a number is 1x, not zero and not the ceiling: the
        // camera's own range is 0.25..2, and the floor must follow the draw rather
        // than answer a magnification it cannot produce.
        assertEquals("a NaN zoom is 1x",
                Splat.eps2dFor(0.5f, 1000, 2000, 100, 1f),
                Splat.eps2dFor(0.5f, 1000, 2000, 100, Float.NaN), 0f);
        assertEquals("the zoom clamp is the camera's own",
                Splat.eps2dFor(0.5f, 1000, 2000, 100, 0.25f),
                Splat.eps2dFor(0.5f, 1000, 2000, 100, -5f), 0f);
    }

    /**
     * The shipped default is the value measured clean, pinned so an upgrade cannot
     * quietly move the look: 0.5 of the cell, which is the 6 px the smooth sky
     * needed at the frame the sweep was run in.
     */
    @Test
    public void theShippedFloorIsTheMeasuredCleanValue() {
        assertEquals(0.5f, Splat.EPS_2D_DEFAULT, 0f);
        assertEquals("and it resolves to the measured 6 px at that frame",
                6f, Splat.eps2dFor(Splat.EPS_2D_DEFAULT, 912, 1984, 149504, 1f), 0.1f);
    }
}
