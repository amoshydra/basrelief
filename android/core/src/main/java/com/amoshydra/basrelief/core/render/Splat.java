package com.amoshydra.basrelief.core.render;

/**
 * The point-splat mode's numbers, as pure arithmetic, so the two that are easy to
 * get quietly wrong are checked without a device.
 *
 * <p><b>What the mode is.</b> The relief draws one continuous surface displaced
 * by the depth field; at a steep depth cliff a triangle spans the cliff and
 * stretches the photograph across it, which is the shear the eye reads as
 * "not quite 3D". The splat draws one small quad per field sample instead: each
 * card has ONE depth (its own texel's), one flat colour sample, and a normal
 * sampled with a gradient across it. Nothing spans a cliff because nothing is
 * connected, so a silhouette tears open into the two surfaces it really is -- and
 * the holes that appear when the camera moves are the parts of the scene a single
 * view never saw, which is the honest cost of a 3D reconstruction from one photo.
 *
 * <p><b>Why the focal matters here and nowhere else.</b> MoGe infers with
 * {@code force_projection=True}, so with the one focal in
 * {@link com.amoshydra.basrelief.core.store.LibraryItem#focal} a photograph's
 * point cloud is a genuine unprojection. What the focal fixes is the DEPTH: a
 * point at image height {@code h} pixels off the centre sits {@code h/f} frame
 * heights of lateral offset per unit of depth, so the image plane is
 * {@code f/H} image heights in front of the eye. The ratio of this row's
 * {@link #effectiveFocalPx} to {@link #fallbackFocalPx} is therefore the depth
 * scale -- the number the vertex shader forms as {@code uFocal / uRefFocal} --
 * and it is what makes a wide lens reconstruct shallow and a telephoto deep.
 *
 * <p>It is deliberately NOT applied to the ray. {@code X = (u-0.5)/(f/W)*Z} is
 * the ray through the PHOTO's camera, and drawing it through this renderer's
 * camera -- whose field of view is fixed, because it is the same camera the
 * relief and every gesture use -- would re-frame the picture: the same photo
 * would arrive 12-70% larger depending on the lens (a phone's main camera is
 * nearer 70 degrees than 45), and the framing is the app's, not the lens's. The
 * ray is therefore the drawing camera's own; the stored focal scales the depth.
 * Scaling the whole cloud about the eye is the same statement said the other way
 * round, which is why the two can both be called "the focal drives the 3D
 * position" without disagreeing.
 */
public final class Splat {

    /**
     * The vertical field of view a row with no focal is drawn at, in degrees.
     *
     * <p>Roughly a phone's main camera and deliberately close to the relief
     * camera's own 45 ({@code CameraModel.FOV_DEG}), so turning the mode on does
     * not change how big the picture looks before anything moves. It is a
     * FALLBACK, not a claim about the lens: a window claims nothing, but a look
     * must not vanish, and a row written before the node measured a focal is
     * every row in the library today.
     */
    public static final float DEFAULT_FOV_DEG = 50f;

    /**
     * How much bigger a card is than the grid cell it replaces. 1 tiles exactly;
     * the margin covers the sampling gaps between neighbouring cards at the
     * reference plane, so the screen shows the photograph rather than a screen
     * door. It is the reference-plane constant only: a card's size scales with
     * its own depth, exactly as its position does, so its angular size is its
     * footprint's at every depth and the margin stays a margin.
     */
    public static final float COVERAGE = 1.15f;

    /**
     * The splat grids a level can select, smallest first: the field's own sampling
     * and multiples of it.
     *
     * <p>The field is built at {@link #DEFAULT_FIELD_GRID} and the cards may sample
     * it more finely than it holds texels — the field is BLURRED float depth, so a
     * finer card grid interpolates it rather than inventing detail. What a finer
     * grid buys is area: the cards get smaller, the flat-colour mosaic gets closer
     * to the photograph, and the holes a depth cliff opens are covered by more,
     * smaller cards instead of a few large ones. What it costs is vertex work: six
     * vertex invocations per sample, so the largest level here is ~3.5 M per frame
     * against the smallest level's 890 k.
     */
    public static final int[] GRID_LEVELS = {384, 512, 640, 768};

    /** The grid the relief's own field is built at, and the default level. */
    public static final int DEFAULT_FIELD_GRID = 384;

    /**
     * The grid a stored level means. Anything unrecognised is the default rather
     * than an extrapolation: a level written by a newer build, a negative, or the
     * absent pref all draw the documented default look.
     */
    public static int gridForLevel(int level) {
        if (level < 0 || level >= GRID_LEVELS.length) return GRID_LEVELS[0];
        return GRID_LEVELS[level];
    }

    private Splat() {
    }

    /**
     * The focal length in pixels an image of this height is drawn at when the row
     * has none: {@code H/2 / tan(FOV/2)}, the pinhole relation between a height in
     * pixels and the field of view it subtends.
     *
     * <p>0 for an unknown or unusable height, because a height of 0 cannot imply a
     * focal -- and 0 is the caller's "no claim", not a value to divide by.
     */
    public static float fallbackFocalPx(float imageHeightPx) {
        if (!Float.isFinite(imageHeightPx) || imageHeightPx <= 0f) return 0f;
        return imageHeightPx * 0.5f
                / (float) Math.tan(Math.toRadians(DEFAULT_FOV_DEG * 0.5f));
    }

    /**
     * The focal to draw with: the row's when it has one, else the default.
     *
     * <p>A row's stored focal is in pixels of its own image, which is why the
     * height is an argument rather than a constant: the same lens is a different
     * number of pixels on a different image, and the renderer hands this the
     * height of the bitmap it is about to draw. The DEPTH SCALE is then this
     * answer over {@link #fallbackFocalPx}, which is the pair the shader is given
     * -- one derivation of the ratio, in one place, with the constant in it.
     *
     * <p>A height that implies no reference (0, negative, non-finite) leaves this
     * answering the row's own focal when it has one -- it is still the effective
     * focal -- and the caller must then NOT form the ratio: the shader treats a
     * non-positive reference as the default look rather than dividing by it,
     * because a ratio against nothing is not a claim.
     */
    public static float effectiveFocalPx(float focalPx, float imageHeightPx) {
        if (Float.isFinite(focalPx) && focalPx > 0f) return focalPx;
        return fallbackFocalPx(imageHeightPx);
    }

    /**
     * A cloud's lens as the dimensionless ratio {@code focal / height}, or 0 when
     * either number cannot claim one.
     *
     * <p><b>This is the form a cloud's lens is stored in, and it is dimensionless
     * on purpose.</b> The node measures a focal in pixels of <i>its</i> uploaded
     * frame ({@code basrelief_<tag>.splat.json}'s {@code focal}, {@code height}),
     * and the phone may decode that same still at a different height and draw it
     * on a different surface; a focal in pixels is only meaningful against the
     * one frame it was measured on, so the two numbers would have to travel
     * together and could disagree. {@code focal / height} is the lens itself --
     * the image plane's distance in image heights -- and is the same number
     * whatever the frame's pixel size. It is deliberately a different field from
     * {@link com.amoshydra.basrelief.core.store.LibraryItem#focal}, which is in
     * pixels and belongs to the colour image the relief's depth scale is read
     * from; reusing that one would have put two units in one number.
     *
     * <p>A missing or non-positive focal or height is 0 -- "no lens" -- rather
     * than a division, the same rule {@link #fallbackFocalPx} follows. The
     * caller reads 0 as "use the fallback look", not as a value to invert.
     */
    public static float focalOverHeight(float focalPx, float heightPx) {
        if (!Float.isFinite(focalPx) || focalPx <= 0f) return 0f;
        if (!Float.isFinite(heightPx) || heightPx <= 0f) return 0f;
        return focalPx / heightPx;
    }

    /**
     * The vertical field of view a lens ratio subtends, in degrees:
     * {@code 2 * atan(1 / (2 * focalOverHeight))}, the pinhole relation inverted.
     *
     * <p>It is the inverse of {@link #focalOverHeight} in the one direction the
     * splat camera needs, and it is here rather than in the renderer for the
     * reason {@link #effectiveFocalPx} is: the mapping is arithmetic that a
     * screenshot cannot check once it is a projection matrix. A ratio that
     * claims nothing (0, negative, non-finite) answers 0, which the caller reads
     * as "the fallback lens" rather than dividing by it.
     */
    public static float fovyForFocalOverHeight(float focalOverHeight) {
        if (!Float.isFinite(focalOverHeight) || focalOverHeight <= 0f) return 0f;
        return (float) (2.0 * Math.toDegrees(Math.atan(0.5 / focalOverHeight)));
    }

    /**
     * One card's size in the model's plane units: the cell it replaces plus
     * {@link #COVERAGE}.
     *
     * <p>{@code grid} is the subdivision count, so the grid is {@code grid+1}
     * samples a side, and the card that replaces one sample's cell must be at
     * least {@code 1/grid} to leave no gap. The 0 case is "no grid", which is what
     * a caller with no surface gets -- and 0 is the safe answer there, because a
     * card sized by nothing is a card that draws nothing.
     */
    public static float cardSize(int grid) {
        if (grid < 1) return 0f;
        return COVERAGE / (grid + 1f);
    }
}
