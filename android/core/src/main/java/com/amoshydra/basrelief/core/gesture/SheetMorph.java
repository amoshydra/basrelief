package com.amoshydra.basrelief.core.gesture;

/**
 * How the studio's bottom sheet <b>morphs into the pill</b>, as one pure derivation.
 *
 * <p><b>Why this is in :core and not in the view.</b> The studio's sheet used to be
 * two views with two lifecycles: its panel's height was {@code (1 - fraction)} of
 * the natural height, so it reached <b>zero</b>, and a separate {@code pill} view
 * went {@code VISIBLE}. Two state changes driven by one gesture is a pop by
 * construction, and it is also why the pill could not be a drag handle — a
 * different view with its own touch handling cannot be dragging the sheet. The
 * fix is one view on one axis: <b>the pill is the panel at {@code fraction = 1}</b>,
 * and these are the three functions that make that true. They are here because
 * they are the part that is easy to get quietly wrong — exact endpoints, a floor,
 * and a band that must not eat the range where the sheet can rest — and because a
 * function of two floats and a constant is exactly what this project is able to
 * unit test. {@link SheetIntent} is the sibling: the release <i>policy</i>, where
 * this is the <i>pixels</i>.
 *
 * <p><b>Units.</b> {@code fraction} is a fraction of the full travel, the same
 * convention {@link SheetIntent} uses: 0 fully open, 1 fully the pill. The pixel
 * arguments are pixels and only that; the fraction is dimensionless, so every
 * number here means the same thing on a phone of any size.
 *
 * <p><b>Nothing here is a function of scroll position.</b> That is load-bearing
 * and it was a whole bug: scrolling modal content once drove the card's height,
 * which changed the layout, which changed the effective scroll position, and the
 * loop oscillated. The user's rule was expand only when scrolling up with nothing left to scroll — so the morph reads <i>the drag</i> and
 * never the content's offset. See {@link #edgeFade}.
 */
public final class SheetMorph {

    /**
     * Where the content's fade begins, as a fraction of travel.
     *
     * <p><b>It is {@link SheetIntent#SHEET}'s own collapse threshold, and that is
     * the whole reason for its value.</b> The studio sheet is {@code FREE}: a
     * release that is not a collapse rests exactly where the finger left it,
     * <i>anywhere</i> below that threshold. So a fade that began any earlier
     * would leave a <b>parked, fully usable sheet sitting at half alpha</b> — the
     * precise complaint the user filed against the old drag fade, reintroduced
     * through the back door. Pinning the band to the line past which the sheet
     * can no longer rest means there is <b>no reachable position where the sheet is
     * present and faded</b>: inside the band the sheet is within 5% of travel of
     * being the pill, which is not "still here", and outside it the fade is
     * exactly 1.0.
     *
     * <p>It is its own constant rather than a reference to the policy so that a
     * host reading it does not think it is following a policy change; the two
     * cannot drift because {@code SheetMorphTest} asserts they are equal.
     */
    public static final float EDGE_BAND_START = 0.95f;

    /**
     * Where the panel's height starts to fall: the same line as the fade.
     *
     * <p><b>Hold, then fall — the height is not a lerp across the travel.</b> The
     * user: <i>the shrink should happen only at the very end of the travel, when the sheet is dragged on to collapse</i> A linear {@code lerp(natural, pill, f)} spends the whole gesture
     * giving up height, so every centimetre of travel costs the sheet some of the
     * screen and dragging it down feels like shrinking a card rather than putting
     * something away. Holding first makes the gesture mean one thing for almost all
     * of its length — <i>this sheet is moving</i> — and then collapse, once, at the
     * end.
     *
     * <p><b>It is {@link #EDGE_BAND_START}, deliberately, and that is the coherence
     * rather than a coincidence of numbers.</b> Inside the band the sheet's content is
     * dissolving and the sheet's own glass is losing its height; outside it the
     * content is fully present and the sheet is fully tall. Those are the same
     * moment, and a height that fell through the stretch where the content is still
     * fully opaque would be saying <i>it is going</i> for most of the travel and
     * <i>it is gone</i> for the last sliver of it.
     *
     * <p><b>The consequence to price before changing it.</b> The studio sheet is
     * {@code FREE} and can rest anywhere below its collapse threshold, and that
     * threshold <i>is</i> this line — so the whole of the fall happens inside the
     * narrow strip of travel where the sheet can come to rest. A sheet parked at
     * {@code f = 0.96} is already a fifth of the way down. Widening the fall would
     * make that band parkable at more heights; narrowing it makes the end of the
     * collapse abrupt. It is a named constant so that trade is one edit, and the
     * test pins the equality with the fade so the two cannot be moved apart by
     * accident.
     */
    public static final float HEIGHT_BAND_START = EDGE_BAND_START;

    private SheetMorph() {
    }

    /**
     * The panel's height at a collapse fraction: <b>hold, then fall.</b>
     *
     * <p><b>It never reaches zero.</b> That is the single change that makes the morph
     * seamless: at {@code f = 1} the panel <i>is</i> the pill, at some real height,
     * so there is no second view to swap in and therefore nothing to pop. It also
     * means the panel can never be shorter than the strip that draws the pill, which
     * is what keeps the drag handle reachable at the bottom of the travel — a panel
     * that shrank to nothing would take its own handle with it and the sheet could
     * never be dragged back up.
     *
     * <p><b>The shape is not a lerp, and it used to be one.</b> Every fraction below
     * {@link #HEIGHT_BAND_START} returns {@code naturalPx} <b>exactly</b>; only the
     * last stretch falls. The user: <i>the shrink should happen only at the very end of the travel, when the sheet is dragged on to collapse</i> A plain {@code lerp(natural, pill, f)} spends
     * the whole gesture giving up height, so every centimetre of travel costs the
     * sheet some of the screen and dragging down reads as shrinking a card rather
     * than putting something away.
     *
     * <p><b>Monotonic, which here means non-increasing, and the plateau is the
     * point.</b> Every step is level or down and never up, so reversing the gesture
     * reads as a reversal. A <i>strictly</i> decreasing function over the whole
     * travel is precisely the old behaviour, so "monotonic" must not be read on this
     * one as "always shrinking".
     *
     * <p><b>Continuous at the join, and that join is new.</b> A piecewise curve has
     * a seam a linear one did not: where the hold becomes the fall. At
     * {@code f = HEIGHT_BAND_START} the fall's parameter is exactly zero, so the
     * function returns {@code naturalPx} there as well and the two halves meet with
     * no step. Swept at a hundredth of a percent, because a seam in the height is
     * invisible in a screenshot taken at rest and obvious in the hand.
     *
     * <p><b>The floor is the point, not a clamp to be tidy.</b> Never less than
     * {@code pillPx}: the pill's own height. A host that passed a {@code pillPx}
     * larger than the natural height (a very short panel, a rotation mid-morph) gets
     * the pill's height rather than an inverted curve that grows as the sheet
     * collapses.
     *
     * <p><b>Below zero it keeps rising, and that is the tether.</b> A drag upward
     * past fully open carries a small negative fraction
     * ({@code SpringSettle.resistUp}, bounded at a fifth of the span) and the
     * panel visibly rises above its resting place and settles back. Clamping that
     * at zero would delete the spring the user can feel at the top of a drag, so
     * the negative domain is the same {@code (1 - f)} extension the old
     * {@code showing = 1 - fraction} had, and the function stays continuous and
     * monotonic across the whole real line.
     *
     * @param fraction 0 open, 1 the pill; negative is the over-open tether
     * @param naturalPx the panel's own wrap-content height
     * @param pillPx    the collapsed height, which is the strip's height
     * @return the height to lay the panel out at, never below {@code pillPx}
     */
    public static float heightFor(float fraction, float naturalPx, float pillPx) {
        float pill = Math.max(0f, pillPx);
        float natural = Math.max(pill, naturalPx);
        if (fraction >= 1f) return pill;
        if (fraction <= 0f) {
            // The tether: at f = 0 this is exactly `natural`, and each 1% of negative
            // fraction is 1% of the natural height taller.
            return natural * (1f - fraction);
        }
        if (fraction <= HEIGHT_BAND_START) {
            // The hold: the sheet keeps every pixel of its height, so the gesture
            // reads as moving rather than as shrinking.
            return natural;
        }
        // The fall. Its parameter is 0 at the band start, where this returns exactly
        // `natural`, and 1 at f = 1, where it returns exactly `pill` -- so the two
        // halves meet without a step.
        float t = (fraction - HEIGHT_BAND_START) / (1f - HEIGHT_BAND_START);
        return natural + (pill - natural) * t;
    }

    /**
     * The alpha of the content <b>below the strip</b>, as the sheet becomes the pill.
     *
     * <p><b>1.0 everywhere except the last {@code 1 - }{@value #EDGE_BAND_START}
     * of the travel, and 0.0 only at the pill.</b> The user's rule was fade out, but only at the edge, and it corrects an earlier absolute — the sheet
     * must never be faded <i>while it is still there</i>, because a live panel at
     * 15% alpha reads as switched off. A morph is the opposite case: the content
     * is <i>leaving</i>, and content that is leaving may dissolve. So the fade is
     * content-only (the strip is the pill and the pill never fades) and edge-only.
     *
     * <p><b>A function of the drag fraction alone — never of scroll position.</b>
     * That is the half that matters for the complaint this replaced: the old drag
     * fade keyed off the sheet's position while the sheet could rest anywhere,
     * which is how a usable panel ended up at half alpha. Keying it to the drag
     * and pinning the band to where resting stops being possible is what makes a
     * parked sheet fully opaque by construction rather than by luck.
     *
     * <p>Linear, same function in both directions: dragging down dissolves the
     * content and dragging up brings it back over the same band, so a reversal
     * mid-drag is symmetric and needs no extra rule.
     *
     * @param fraction the same collapse fraction {@link #heightFor} takes
     * @return 1.0 at {@code f = 0} and at {@link #EDGE_BAND_START}, falling to
     *         0.0 at {@code f = 1}; never outside 0..1
     */
    public static float edgeFade(float fraction) {
        float f = fraction;
        if (f <= 0f || f <= EDGE_BAND_START) return 1f;
        if (f >= 1f) return 0f;
        return 1f - (f - EDGE_BAND_START) / (1f - EDGE_BAND_START);
    }

    /**
     * The panel's top corner radius at a collapse fraction.
     *
     * <p><b>From the sheet's own corner to the pill's, interpolated.</b> The two
     * ends have to agree with the two shapes: the expanded sheet's 22dp top
     * corners, and at {@code f = 1} a shape whose top corners are half its own
     * height — which for a 56dp pill is a semicircle, the most "fully rounded" a
     * top edge can be without lifting the panel off the window's bottom.
     *
     * <p><b>The target is the pill's radius, and the panel's half-height is only
     * a ceiling.</b> This is the version that shipped wrong once and the failure is
     * worth writing down: interpolating toward {@code panelHeight / 2} — reading
     * "half the box" as the thing to round to — gives a 2400px panel a <b>611px
     * corner radius half way down the collapse</b>, which is a stadium, not a
     * sheet, and it is nowhere near either end the user chose. The two ends are
     * 22dp and half a pill, so the travel between them is 6dp and the whole morph
     * should be a 6dp rounding; the half-height is a <i>clamp</i>, applied after
     * the interpolation, whose only job is to stop a panel shorter than its
     * target from getting a bite taken out of each top corner.
     *
     * <p>A drawable property, so it costs no relayout and no transform — the two
     * things this project's sheet must never do, because it is a sibling of a
     * {@code GLSurfaceView} (b/7179570).
     *
     * @param fraction       the collapse fraction
     * @param panelHeightPx  the height {@link #heightFor} just returned; used only
     *                       to cap the result, never as an interpolation target
     * @param openRadiusPx   the expanded sheet's top corner radius
     * @param pillRadiusPx   the collapsed strip's top corner radius, normally half
     *                       the pill's height
     */
    public static float cornerRadiusFor(float fraction, float panelHeightPx,
                                        float openRadiusPx, float pillRadiusPx) {
        float ceiling = Math.max(0f, panelHeightPx) * 0.5f;
        float open = Math.min(Math.max(0f, openRadiusPx), ceiling);
        float pill = Math.min(Math.max(0f, pillRadiusPx), ceiling);
        float f = fraction;
        float radius;
        if (f <= 0f) {
            radius = open;
        } else if (f >= 1f) {
            radius = pill;
        } else {
            radius = open + (pill - open) * f;
        }
        // The ceiling is applied to the RESULT, so no fraction and no panel size can
        // produce a radius larger than the box it rounds. Swept by the test.
        return Math.min(radius, ceiling);
    }

    /**
     * How far the collapsed shape sits inside the window's edges, as the sheet
     * becomes the pill.
     *
     * <p><b>The pill was never flush to the sides and it is never flush to the
     * bottom.</b> This is the other half of the shape, and it was the half that
     * could not be fixed by rounding: the morph turned the sheet's full-width top
     * bar into a strip with a 22dp corner on it, measured the strip at exactly
     * 167px and unmoved, and it was still not the pill — because the original pill
     * was bounded. 28dp of margin each side and 26dp off the bottom, so the
     * collapsed object <i>floats</i> instead of being the sheet's top edge with
     * its corners filed down.
     *
     * <p><b>Same shape as the height: a lerp with a floor at each end, and nothing
     * new.</b> Zero while the sheet is open — so the open sheet is laid out at
     * exactly the margins it always was, and there is no seam to see — and
     * {@code pillInsetPx} once collapsed, with the same extension past the ends
     * {@link #heightFor} has, so an over-open drag lifts it by the same fraction
     * of the travel it raises the panel and the two cannot disagree.
     *
     * <p><b>Carried on the panel's own {@code LayoutParams}, never as a
     * transform.</b> A sibling of a {@code GLSurfaceView} whose position is moved
     * by a post-layout transform can end up placed correctly, attached and
     * {@code isShown()} in a window that has drawn hundreds of frames, and still
     * be absent from the display — b/7179570, and the sheet's own standing
     * constraint. Layout is what the surface's transparent region is computed
     * from, so this is the one channel that repaints.
     *
     * @param fraction    the collapse fraction, as {@link #heightFor} takes it
     * @param pillInsetPx how far in the collapsed shape sits, in px
     * @return the inset in px, never negative
     */
    public static float insetFor(float fraction, float pillInsetPx) {
        float inset = Math.max(0f, pillInsetPx);
        float f = fraction;
        // `!(f > 0f)` rather than `f <= 0f`, because the second is false for NaN and
        // would fall through to `inset * NaN` -- a NaN margin, which is not a
        // degraded margin, it is an unusable one. An unreadable fraction reads as
        // "not collapsed", which is the safe direction: no inset is the layout the
        // sheet had before the morph existed.
        if (!(f > 0f)) return 0f;
        if (f >= 1f) return inset;
        return inset * f;
    }

    /**
     * The <b>per-side margin</b> that narrows the sheet to the collapsed pill's
     * width, as the sheet becomes the pill.
     *
     * <p><b>The pill's shape is a width, not a margin.</b> It used to be specified as
     * a margin — 28dp a side, recovered from the pre-morph pill's holder — and that
     * was never going to read as a capsule, because a margin is a rounding of the
     * screen's own size: on a 483dp-wide phone it left the pill 88% of the width,
     * which is a bar. The user: <i>the capsule's width should be smaller too</i> A total width is the thing the eye reads as a shape.
     *
     * <p><b>Expressed as a fraction of the screen, not as dp, and that is the
     * argument.</b> The sheet is edge-to-edge on every phone the app supports, so a
     * fixed dp width would be nearly the whole screen on a small one and a floating
     * lozenge on a large one — the same shape failing in two directions, and it
     * could not be tuned without picking a phone to tune it on. A fraction of the
     * screen is one number that reads as a capsule on a compact phone and on a
     * large one, and it is also exactly the quantity the panel's own width is
     * measured against, so the arithmetic is a subtraction rather than a guess.
     *
     * <p><b>Centred by construction rather than by two numbers.</b> The margin is
     * computed as half of what is left over, so the two sides cannot differ and
     * there is no second value that could drift from the first.
     *
     * <p>Reached through {@link #insetFor}, so the margin grows on the same curve as
     * the height's fall and the two cannot reach the capsule at different moments.
     *
     * @param fraction          the collapse fraction
     * @param screenWidthPx     the width the sheet spans while open
     * @param pillWidthFraction the collapsed width as a fraction of that, 0..1
     * @return the margin in px for each side, never negative
     */
    public static float pillSideMarginFor(float fraction, float screenWidthPx,
                                          float pillWidthFraction) {
        float screen = Math.max(0f, screenWidthPx);
        float width = Math.min(1f, Math.max(0f, pillWidthFraction));
        // Half of what is left over, which is what makes the sides equal.
        return insetFor(fraction, screen * (1f - width) * 0.5f);
    }

    /**
     * Blend two opaque ARGB colours along the collapse.
     *
     * <p><b>Because the two ends are both facts, not choices.</b> The strip's
     * colour at {@code f = 0} is the sheet's own glass, so the open sheet has no
     * plate sitting on it that the morph then has to explain away; and at
     * {@code f = 1} it is the plate the original pill drew, recovered from
     * {@code 227a53f^} rather than picked. The morph is one object on one axis, so
     * its colour is on the same axis — a lerp with both ends named.
     *
     * <p><b>Straight per-channel, alpha included.</b> Alpha is blended rather than
     * stepped because the strip is <i>glass over a render</i>: its whole job is
     * that the photograph's edge continues underneath, so a pill that arrived at
     * full opacity would stop floating and start sitting, and the difference
     * between the two ends here is mostly the alpha (0xEB to 0xB8).
     *
     * <p><b>The failure this is here to prevent is a step, not a wrong
     * end.</b> Blending a colour over the last 5% of travel while the shape is
     * still recognisably the sheet is what makes the last few per cent read as
     * motion; a colour that snapped in at the end reads as the old two-view pop in
     * a different channel, and is invisible to every bound-based check. Rounding
     * per channel is safe here for the same reason the tests can sweep it: the
     * whole blend spans 0xEB→0xB8 and 0x16→0x1C, so the largest possible rounding
     * step is one level on a channel that moves eleven.
     *
     * @param from the colour while the sheet is open
     * @param to   the colour once collapsed
     * @param fraction the collapse fraction, clamped to 0..1
     * @return the ARGB colour to draw at that fraction
     */
    public static int blendColour(int from, int to, float fraction) {
        float f = fraction;
        // NaN-safe for the same reason as insetFor: a NaN fraction would otherwise
        // reach Math.round and come back as a channel of 0, i.e. a fully transparent
        // strip -- the one outcome that would be silently invisible on screen. The
        // safe reading of an unreadable fraction is "not collapsed".
        if (!(f > 0f)) return from;
        if (f >= 1f) return to;
        int a = lerpChannel(from >>> 24, to >>> 24, f);
        int r = lerpChannel(from >> 16 & 0xFF, to >> 16 & 0xFF, f);
        int g = lerpChannel(from >> 8 & 0xFF, to >> 8 & 0xFF, f);
        int b = lerpChannel(from & 0xFF, to & 0xFF, f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float f) {
        return Math.round(from + (to - from) * f);
    }
}
