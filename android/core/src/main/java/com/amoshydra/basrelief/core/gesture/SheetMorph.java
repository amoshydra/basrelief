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

    private SheetMorph() {
    }

    /**
     * The panel's height at a collapse fraction.
     *
     * <p><b>A lerp from the natural height to the pill height, not a shrink to
     * zero.</b> This is the single change that makes the morph seamless: at
     * {@code f = 1} the panel <i>is</i> the pill, at some real height, so there is
     * no second view to swap in and therefore nothing to pop. It also means the
     * panel can never be shorter than the strip that draws the pill, which is what
     * keeps the drag handle reachable at the bottom of the travel — a panel that
     * shrank to nothing would take its own handle with it and the sheet could
     * never be dragged back up.
     *
     * <p><b>The floor is the point, not a clamp to be tidy.</b> {@link
     * #heightFor} never returns less than {@code pillPx}: the pill's own height.
     * A host that passed a {@code pillPx} larger than the natural height (a very
     * short panel, a rotation mid-morph) gets the pill's height rather than an
     * inverted lerp that grows as the sheet collapses.
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
        return natural + (pill - natural) * fraction;
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
}
