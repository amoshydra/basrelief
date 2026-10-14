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

    // ------------------------------------------------------ the tuning knobs
    //
    // The four values the sheet's splat rows edit: SIZE, BLUR, DEPTH and
    // RESOLUTION. <b>They are in the persisted per-item, per-surface schema now</b>
    // -- on {@link com.amoshydra.basrelief.core.store.SurfaceSettings}, carried by
    // its positional {@code with}, resolved by
    // {@link com.amoshydra.basrelief.core.render.Framing} and compared by
    // {@code sameAs}. They were deliberately prefs while the look was still being
    // chosen by eye ("a field in the counted chain is a schema bump plus a writer
    // at every call site for a number that may not survive the tuning"); the tuning
    // is over, so they are a render value on the photo exactly as Depth and Motion
    // are. Their clamps live here, in :core, so a blob written by a newer build,
    // restored from another device or typed by adb cannot put the shader in a state
    // no test can name, and the product defaults below are what an untouched
    // install renders.

    /**
     * The product default 2D low-pass floor, gsplat's {@code eps2d}: it lifts only
     * the splats whose screen covariance is too small to sample, so a far field of
     * ~2 px splats at 2 px spacing stops reading as a screen-door fog.
     *
     * <p><b>It is a FRACTION OF THE CELL, and the floor is the LARGER of that term
     * and {@link #samplingMinPx2}.</b> The fraction term closes the gap between
     * neighbouring splats, and that gap is the cell a splat stands for:
     * {@code viewportArea / count}, magnified by the zoom. A fixed floor in pixels
     * is measured in a unit the gap does not use -- the cell doubles with the
     * render's resolution and again with every octave of zoom, while a constant
     * does not -- so the same fraction is the same effect on every device and at
     * every zoom, and the knob's maximum cannot fall below what the phone needs.
     * Measured on one cloud: the sky needed 6 at the cloud's own 912x1984 frame and
     * 15 at the 1440x3120 the phone draws it into, which is 6 x (1440/912)^2 to
     * three figures. As a fraction of the cell both are 0.5, which is what makes
     * one default correct for both.
     *
     * <p><b>The fraction term alone is not enough, and that is the artifact
     * {@link #samplingMinPx2} exists for.</b> The cell shrinks as the count grows,
     * so the fraction term shrinks with it, and past a point it falls below one
     * render pixel -- where a splat cannot be sampled at all and a smooth, bright,
     * far region (a sky, a white wall) shows partial coverage as a fine speckle
     * that does not shrink with density. The sampling minimum is the floor's own
     * floor, <b>up to the cell</b>, which is the most it can usefully be: see
     * {@link #eps2dFor}.
     *
     * <p>The measured value: at 0.5 the sky's high-frequency energy falls to the
     * photograph's own, and the dense net it shows at 0.2 is gone.
     */
    public static final float EPS_2D_DEFAULT = 0.5f;

    /**
     * The bottom of the SIZE range. <b>The range is the USEFUL band, not a full
     * sweep, and that is a deliberate product decision.</b> Measured, the floor has
     * a narrow usable window: below about 0.4 of the cell the screen-door returns,
     * and above about 0.8 the picture is soft for no gain. A knob that spans the
     * broken region invites exactly the failure this setting has already shipped
     * twice -- a user drags to a plausible-looking number that is below the cliff,
     * sees no improvement, and concludes the mode is broken. So every setting the
     * control offers is one that works, and the default sits in the middle of them.
     */
    public static final float EPS_2D_MIN = 0.45f;

    /** The top of the SIZE range: the cleaner-sky option at a small subject cost. */
    public static final float EPS_2D_MAX = 0.8f;

    /**
     * The smallest variance at which a splat can be sampled, <b>as a fraction of
     * the render target's area</b> -- the floor's own floor, and the term
     * {@link #eps2dFor} caps by the cell it is a minimum for. Resolved for a target
     * by {@link #samplingMinPx2}.
     *
     * <p><b>Derived from the reference render, and it is an AREA.</b> A clean
     * reference renderer reaches the photograph's own high-frequency energy at
     * {@code eps2d = 6} px^2 on a 912x1984 frame -- the frame the sweep was run in
     * -- so the measurement is {@code 6 / (912 * 1984)} of the target's area. It
     * resolves to <b>6.00</b> px^2 back at 912x1984, <b>0.931</b> at the 360x780 of
     * a 0.25 render scale (the parent's 0.935, to the measurement's own precision),
     * <b>3.72</b> at the 720x1560 of a 0.5 render scale, and <b>14.90</b> at
     * 1440x3120.
     *
     * <p><b>THE UNIT IS A FRACTION OF THE TARGET, AND THE TARGET IS NOT ALWAYS 360
     * WIDE -- which is the bug this is.</b> The requirement is an area: the floor's
     * job is to bring a splat up to roughly the spacing of its neighbours, and BOTH
     * the splat's own screen variance and that spacing scale with the render
     * resolution squared. A constant in render pixels is therefore right at exactly
     * one resolution. Measured with the faithful renderer on the 149504-splat beach
     * cloud's smooth sky (high-pass std, against a clean render's 0.76 at 360x780):
     * at the <b>720x1560</b> target the device draws into at render scale 0.5 --
     * read off its own {@code splat: ... render=720x1560 scale=0.5000} line -- a
     * floor of 1.0 px^2 leaves <b>12.71</b>, 2.0 leaves 5.33, and the resolved 3.72
     * leaves <b>1.15</b>. The old constant was 4x too small at half scale, and the
     * SIZE knob could not recover it: at 0.8 of a dense cloud's cell the term is
     * 0.75, still under the 1.0 constant, which is why the knob changed the texture
     * and never the region.
     *
     * <p><b>BOTH TERMS ARE FRACTIONS OF THE TARGET, SO THE MINIMUM IS CAPPED BY THE
     * CELL -- and the crossover is resolution-free.</b> {@code samplingMin / cell =
     * count * 6/(912*1984)}, which cancels the viewport entirely: <b>0.50</b> at the
     * 149,504-splat beach the constant was derived on (where the floor is genuinely
     * needed), <b>1.34</b> at 403,456 and <b>3.47</b> at 1,046,528. Past {@code
     * 912*1984/6 = 301,568} splats the minimum exceeds the cell it is a minimum for,
     * and a floor larger than the gap no longer closes anything -- it inflates every
     * splat to several times the footprint it stands for, and the picture is equally
     * soft at 25% and at 100% render scale because the ratio has no resolution in it.
     * That is why {@link #eps2dFor} takes {@code min(samplingMin, cell)}: the floor
     * can never exceed the gap it exists to close.
     *
     * <p><b>It is NOT scaled by zoom, and that is the point of a separate term.</b>
     * The cell term carries {@code zoom^2} because the gap between splats is a
     * property of the SCENE, and a magnified render spreads those splats over more
     * pixels. This floor is a property of the render TARGET -- a splat cannot be
     * sampled below one of ITS pixels however the camera is zoomed -- so it is a
     * constant for a given target. The CAP is the on-screen cell and therefore does
     * carry the zoom; the minimum itself does not, so at zoom &lt; 1 the cap pulls it
     * down to the smaller on-screen gap and at zoom &gt; 1 it lets it through.
     * Folding the zoom into the minimum itself would be a different thing -- it would
     * let a zoom-out (0.25 is reachable) divide the target's own sampling floor back
     * toward zero, which is the failure this number exists to end.
     */
    public static final float SAMPLING_MIN_AREA_FRACTION = 6f / (912f * 1984f);

    /**
     * A cloud's identity: its path AND the number of bytes behind it.
     *
     * <p><b>The path alone is not an identity, and that is the bug this exists
     * for.</b> A rebuild writes the SAME filename -- the node's output name is
     * deterministic in the item id and the app stores it as
     * {@code item-&lt;tag&gt;.splat} -- so a renderer that remembers "I am showing
     * this path" cannot tell a rebuilt cloud from the one it already holds, drops
     * the request, and the new file never reaches the screen. Measured: a rebuild
     * at a different budget changed nothing on the phone until the app was
     * restarted, while the file on disk had gone from 4.8 MB to 23 MB.
     *
     * <p>The byte count is a sufficient stamp <b>because the node is
     * deterministic</b>: its own tests pin byte-identical output for the same
     * budget and inputs, so a different size means different bytes and the same
     * size means the same cloud. A count that cannot be read is 0, which is the "a
     * window claims nothing" rule -- an absent file must not be able to collide
     * with a loaded one.
     */
    public static String cloudIdentity(String path, long byteCount) {
        return path + "\u0000" + Math.max(0L, byteCount);
    }

    /**
     * The floor a stored value means. A non-finite value, or one below the range,
     * answers the default rather than the floor: a pref that cannot claim a value
     * must not be read as one -- the "a window claims nothing" rule applied to a
     * number, so a NaN cannot reach the shader as a NaN.
     */
    public static float clampEps2d(float value) {
        if (!Float.isFinite(value)) return EPS_2D_DEFAULT;
        if (value < EPS_2D_MIN) return EPS_2D_MIN;
        if (value > EPS_2D_MAX) return EPS_2D_MAX;
        return value;
    }

    /**
     * The floor in render-target pixels squared: the LARGER of the sampling minimum
     * <b>for THIS target, capped by the cell</b>, and the fraction of the cell the
     * knob stores.
     *
     * <p>This is the one resolution of the setting into a number, and it lives here
     * rather than in the renderer so the invariance it exists for can be asserted.
     * It is a maximum of two quantities in the same unit, render-target pixels
     * squared:
     *
     * <ul>
     *   <li>{@code min(samplingMinPx2, cell)} -- the smallest variance a splat can
     *       be sampled at, for this target's area, <b>capped by the cell it is a
     *       minimum for</b>. It is a property of the RENDER TARGET, so it is
     *       independent of zoom and of count, and it is what keeps the floor from
     *       falling below the pixel grid on a low-resolution target or a high-count
     *       cloud. <b>It scales with the target's area</b>, because the requirement
     *       it states does: see {@link #SAMPLING_MIN_AREA_FRACTION}. The cap is the
     *       ON-SCREEN cell, not the raw {@code viewportArea / count}, because the
     *       gap the floor closes is the gap on screen -- which is also why the cell
     *       term below carries {@code zoom^2}. Capping against the unzoomed cell
     *       would leave a zoomed-out cloud's floor at {@code 1/zoom^2} times the
     *       gap, the same over-blur this cap exists to remove, one axis over.</li>
     *   <li>{@code fraction * cell * zoom^2}, where the cell is the area one splat
     *       stands for on screen: {@code viewportArea / count}. That is the area
     *       when the cloud fills the viewport -- which is the case for a photograph,
     *       whose aspect is the screen's -- and it is the same quantity the shader
     *       is compensating for, so it is the honest one to measure against. The
     *       zoom enters squared because it magnifies both the cell's width and its
     *       height.</li>
     * </ul>
     *
     * <p><b>Why the minimum is capped, and what the cap does at zoom.</b> Both terms
     * are fractions of the target's area, so their ratio cancels the viewport
     * entirely: {@code samplingMin / cell = count * SAMPLING_MIN_AREA_FRACTION},
     * which is 0.50 at the 149,504-splat beach the constant was measured on, 1.34
     * at 403,456 and 3.47 at 1,046,528. Past {@code 1 /
     * SAMPLING_MIN_AREA_FRACTION = 301,568} splats (at zoom 1) the minimum exceeds
     * the cell it is a minimum for, and a floor larger than the gap is not closing
     * anything: it draws every splat several times the footprint it stands for, and
     * because the ratio has no resolution in it the picture is equally soft at 25%
     * and at 100% render scale. Capping makes the floor the largest quantity that
     * can still close the gap -- the gap itself.
     *
     * <p><b>The cap can only bite once the splats already overlap, and it is inert
     * below the crossover.</b> The crossover is where the minimum equals the cell,
     * and there a splat at the minimum variance has a standard deviation
     * {@code sqrt(samplingMin)} equal to the neighbour spacing {@code sqrt(cell)};
     * past it the spacing is smaller still, so the splats cover one another and no
     * gap is left to close. Below it {@code min(samplingMin, cell) = samplingMin}
     * and the floor is exactly the old maximum, so the 149,504-splat beach's look is
     * unchanged -- while the 403,456-splat bundled row, at 1.34x at zoom 1 and 3.48x
     * at the 6T's stored zoom 0.62, is the one the cap moves. At zoom &gt; 1 the cap
     * grows with the cell (the on-screen gap is magnified) and lets the target's
     * minimum through; at zoom &lt; 1 it shrinks with the cell and pulls the minimum
     * down to the smaller gap, which is the honest answer because a zoomed-out
     * cloud's splats are closer on screen.
     *
     * <p><b>The SIZE knob stays meaningful only below the crossover.</b> Above it
     * the floor is the cell itself -- {@code fraction * cell <= 0.8 * cell < cell}
     * -- so the knob cannot move it: the cap has already chosen the largest floor
     * that closes the gap, and a control that could only exceed it would be the
     * over-blur again. Below it the floor is {@code cell * max(count *
     * SAMPLING_MIN_AREA_FRACTION, fraction)}, so the knob is the whole answer once
     * its setting exceeds the minimum's share of the cell and inert below that --
     * the pre-existing hard floor, unchanged.
     *
     * <p>A count or viewport that cannot claim a value (0, or a viewport that is
     * not a size) answers 0, which is the identity: a floor is an enlargement, and
     * enlarging by nothing is the only answer that does not invent one. The caller
     * clamps the fraction, so this cannot be handed a NaN.
     */
    public static float eps2dFor(float fraction, int viewportW, int viewportH,
                                 int count, float zoom) {
        if (!(fraction > 0f) || viewportW <= 0 || viewportH <= 0 || count <= 0) return 0f;
        float z = Float.isFinite(zoom) ? Math.max(0.25f, Math.min(2f, zoom)) : 1f;
        float area = (float) viewportW * viewportH;
        // The ON-SCREEN cell: the area one splat stands for once the zoom has
        // magnified it. The cap below is against this and not the raw area/count,
        // because the gap the floor closes is the gap on screen.
        float cell = (area / count) * z * z;
        // The sampling minimum is a minimum FOR the cell, so it can never need to
        // exceed it: a gap cannot be larger than the cell, and a floor larger than
        // the gap inflates every splat past the footprint it stands for. See
        // SAMPLING_MIN_AREA_FRACTION for the crossover this makes inert.
        float min = Math.min(samplingMinPx2(viewportW, viewportH), cell);
        return Math.max(min, fraction * cell);
    }

    /**
     * The sampling minimum for a given render target, in that target's pixels
     * squared: {@link #SAMPLING_MIN_AREA_FRACTION} of the target's area.
     *
     * <p><b>Why an area and not a constant.</b> The measurement behind it is a
     * variance, and a variance is an area, so the same look on a different
     * resolution is the same number times the resolution ratio squared. The device
     * draws at more than one render scale -- {@code splat_scale} is a dev pref from
     * 0.25 to 1.0, and 0.5 is reachable and was measured -- so a constant stated at
     * one of them is wrong at every other, and the wrongness is silent: a floor
     * that is 4x too small does not error, it just leaves the speckle in.
     *
     * <p>It resolves to 6.00 px^2 at the 912x1984 the sweep was run in, 0.931 at the
     * 360x780 of a 0.25 render scale, 3.72 at the 720x1560 of a 0.5 scale, and 14.90
     * at 1440x3120. It is deliberately NOT scaled by zoom -- see
     * {@link #SAMPLING_MIN_AREA_FRACTION}.
     *
     * <p>A target that cannot claim a size answers 0, the identity: a floor derived
     * from no target is not a floor.
     */
    public static float samplingMinPx2(int viewportW, int viewportH) {
        if (viewportW <= 0 || viewportH <= 0) return 0f;
        return SAMPLING_MIN_AREA_FRACTION * ((float) viewportW * (float) viewportH);
    }

    /** The product default post-blur radius, in pixels: 0 is the identity. */
    public static final float BLUR_DEFAULT = 0f;

    /**
     * The top of the BLUR range, in render-target pixels. Six is where the
     * speckle is gone several times over and the detail cost is obvious; a larger
     * radius is a different effect (a defocus), not a cleaner version of this one.
     */
    public static final float BLUR_MAX = 6f;

    /**
     * The radius a stored value means. Negative and non-finite answer the identity,
     * not the magnitude: a blur is a one-sided quantity and a NaN radius must not
     * reach the shader's loop bound.
     */
    public static float clampBlurPx(float value) {
        if (!Float.isFinite(value) || value < 0f) return BLUR_DEFAULT;
        if (value > BLUR_MAX) return BLUR_MAX;
        return value;
    }

    // ------------------------------------------------- the RESOLUTION knob

    /** The product default render scale: the cloud is drawn at the surface's own size. */
    public static final float RENDER_SCALE_DEFAULT = 1f;

    /**
     * The bottom of the RESOLUTION range: the fraction of the surface the cloud is
     * rasterised at. Below a quarter the picture is softer than the fill it saves,
     * so this is the useful floor rather than a bound on a divisor.
     */
    public static final float RENDER_SCALE_MIN = 0.25f;

    /** The top of the RESOLUTION range: straight to the surface, no offscreen blit. */
    public static final float RENDER_SCALE_MAX = 1f;

    /**
     * The render scale a stored value means. A non-finite value answers the default
     * rather than the nearest bound -- the "a window claims nothing" rule applied to
     * a number, so a NaN cannot size the offscreen target.
     */
    public static float clampRenderScale(float value) {
        if (!Float.isFinite(value)) return RENDER_SCALE_DEFAULT;
        if (value < RENDER_SCALE_MIN) return RENDER_SCALE_MIN;
        if (value > RENDER_SCALE_MAX) return RENDER_SCALE_MAX;
        return value;
    }

    // ------------------------------------------------- the Depth knob
    //
    // The shape knob, added after SIZE / BLUR / RESOLUTION. Same rule as the three
    // above: a per-item, per-surface render value in the counted schema, clamped
    // here so a blob from a newer build or another device cannot reach the shader as
    // a value no test can name.

    /**
     * The product default depth multiplier: 1.0, exactly the depth the node
     * reconstructed. An untouched photo shows the cloud as it was measured.
     */
    public static final float DEPTH_MUL_DEFAULT = 1f;

    /**
     * The bottom of the DEPTH range: 0 collapses every splat onto the reference
     * plane, which is the flat photograph (the cloud has no depth to reveal), and is
     * the honest "off" end of the control rather than a bound on a divisor.
     */
    public static final float DEPTH_MUL_MIN = 0f;

    /**
     * The top of the DEPTH range. Three times the measured depth is well past
     * anything a single-image reconstruction is entitled to and is where the holes a
     * deeper cloud opens stop reading as parallax; the useful band is below it.
     */
    public static final float DEPTH_MUL_MAX = 3f;

    /**
     * The depth multiplier a stored value means. A non-finite value answers the
     * default rather than the nearest bound -- the "a window claims nothing" rule
     * applied to a number, so a NaN cannot reach the shader's position scale.
     */
    public static float clampDepthMul(float value) {
        if (!Float.isFinite(value)) return DEPTH_MUL_DEFAULT;
        if (value < DEPTH_MUL_MIN) return DEPTH_MUL_MIN;
        if (value > DEPTH_MUL_MAX) return DEPTH_MUL_MAX;
        return value;
    }

    /**
     * The scale a splat's position AND size take to multiply its depth about the
     * reference plane <b>without moving its image position</b> -- the vertex shader
     * mirrors this line, and the test pins the identity it exists for.
     *
     * <p><b>Why a per-splat scale and not a multiply on {@code z}.</b> A cloud's
     * splats each sit on the ray from the eye through their own image pixel, at
     * their own depth, so moving one along that ray is what changes the depth and
     * leaves the photograph alone. Scaling only {@code z} would drag the splat off
     * its ray and the at-rest picture would move; scaling the whole vector by
     * {@code (ref + mul*(z - ref)) / z} keeps it on the ray, and the same factor on
     * the splat's own size keeps its angular size its footprint's at every depth --
     * exactly the property the relief's cards carry, which is why at rest the
     * picture is the photograph at EVERY multiplier and only the parallax changes.
     *
     * <p>{@code mul == 1} is the identity for every {@code z}: the cloud is the one
     * the node measured. {@code mul == 0} puts every splat on the reference plane
     * {@code refDepth}, which projects to the same pixels -- the flat photograph.
     * The result is floored at 0 so a multiplier above 1 cannot push a near splat
     * through the eye and render it mirrored; such a splat collapses to the eye and
     * the existing near-plane cull drops it.
     *
     * <p>A depth or reference that cannot claim a value answers 1, the identity: a
     * splat behind the camera has no ray to move along, and a plane that is not a
     * distance cannot be one.
     */
    public static float depthScale(float z, float refDepth, float mul) {
        if (!Float.isFinite(z) || z <= 1e-4f) return 1f;
        if (!Float.isFinite(refDepth) || refDepth <= 0f) return 1f;
        float z2 = refDepth + clampDepthMul(mul) * (z - refDepth);
        if (z2 < 0f) z2 = 0f;
        return z2 / z;
    }

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

    // ------------------------------------------------- the alpha-extent clip

    /**
     * The alpha below which the fragment shader discards a fragment: {@code 1/255},
     * the smallest non-zero value an 8-bit alpha can carry.
     *
     * <p><b>It is the ONE source of truth for the discard, and it reaches both
     * shader stages as a uniform.</b> The fragment stage compares a fragment's alpha
     * against it; the vertex stage uses the same number to clip the quad to the
     * radius at which that comparison would throw the fragment away -- see
     * {@link #extentScaleForAlpha}. A literal copied into two shaders is exactly the
     * drift this avoids: a threshold that moved in one of them would make the quad
     * end somewhere other than where the discard begins, and the difference is a soft
     * ring of clipped splats that no test could name. The renderer uploads this value
     * once per frame, so the two stages cannot disagree.
     */
    public static final float ALPHA_DISCARD = 1f / 255f;

    /**
     * The fraction of a splat's 3-sigma quad that survives the fragment shader's
     * alpha discard, for a splat whose baked opacity is {@code alpha}: 0 when nothing
     * survives, 1 for every splat opaque enough that the whole quad does.
     *
     * <p><b>Where it comes from.</b> The fragment stage keeps a fragment when
     * {@code alpha * exp(power) >= ALPHA_DISCARD}, where {@code power = -0.5 * r^2} in
     * the Mahalanobis radius {@code r} the conic defines. Solving for the radius gives
     * {@code r <= sqrt(2 * ln(alpha / ALPHA_DISCARD))}, and the vertex shader emits the
     * quad at the 3-sigma extent {@code r = 3}. So the quad is scaled by
     * {@code min(1, sqrt(2 * ln(alpha / ALPHA_DISCARD)) / 3)}: it covers exactly the
     * region the fragment stage keeps, and every fragment the smaller quad removes is
     * one the discard was already throwing away. <b>That is the whole of "invisible by
     * construction"</b> -- no pixel that was drawn before changes, because the region
     * drawn shrinks to the boundary of the region kept.
     *
     * <p><b>An opaque splat is exactly the identity.</b> At {@code alpha = 1} the
     * surviving radius is {@code sqrt(2 * ln(255)) = 3.33 > 3}, so {@code min(1, ...)}
     * answers 1 and the shipped look of every opaque splat is unchanged. The quad only
     * starts to shrink below {@code alpha = ALPHA_DISCARD * exp(4.5) = 0.353}.
     * Measured on the shipped demo cloud: 8.3% of its splats are below that and the
     * mean extent is 0.988, so this is a small win on a high-opacity cloud and a larger
     * one on a cloud with many faint splats. The stats line reports both numbers so a
     * device log says which it was.
     *
     * <p><b>A splat at or below the threshold draws nothing at all.</b> Every one of
     * its fragments would be discarded, so the vertex shader drops it outright rather
     * than emit a quad of fragments the fragment stage then throws away. The test is
     * the shader's own {@code !(alpha > ALPHA_DISCARD)}, so a NaN -- which compares
     * false against every bound -- falls on the drop side rather than through it, the
     * "a window claims nothing" rule applied to an opacity.
     */
    public static float extentScaleForAlpha(float alpha) {
        if (!(alpha > ALPHA_DISCARD)) return 0f;
        double radius = Math.sqrt(2.0 * Math.log(alpha / ALPHA_DISCARD));
        return (float) Math.min(1.0, radius / 3.0);
    }
}
