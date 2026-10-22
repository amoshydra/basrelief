package com.amoshydra.basrelief.core.render;

import org.json.JSONObject;

/**
 * The {@code basrelief_<tag>.splat.json} sidecar {@code SharpStill} writes, as the
 * app reads it.
 *
 * <p><b>The app reads three numbers and nothing else:</b> {@code focal} (pixels of
 * the frame the compressor was handed), {@code width} and {@code height} (that
 * frame). {@link #lens()} reduces them to the dimensionless {@code focal / height}
 * the cloud is drawn through, which is the only form that survives the phone
 * decoding the same still at a different size.
 *
 * <p><b>A missing field is "no claim", never zero.</b> {@code focal} absent, 0 (the
 * node's own "the fit failed"), a width or height absent, a body that is not JSON —
 * every one of those answers {@link #lens()} {@code 0}, and the caller draws the
 * default look. It must never divide by a height of 0 to make a camera out of
 * nothing, and it must never fail the run over a number a look does not need: this
 * is fetched after the cloud is already on disk.
 *
 * <p>The fixture both this reader and the node's writer are tested against is
 * {@code contract/sidecar/}; the writer is {@code basrelief_depth/contract.py}'s
 * {@code splat_sidecar}.
 */
public final class SplatSidecar {

    /** The fitted focal in pixels of the frame described below; 0 = no claim. */
    public final int focal;
    /** The width of the frame the focal was measured on; 0 = not stated. */
    public final int width;
    /** The height of that frame; 0 = not stated. */
    public final int height;

    private SplatSidecar(int focal, int width, int height) {
        this.focal = focal;
        this.width = width;
        this.height = height;
    }

    /**
     * Parse the sidecar's body, or answer {@code null} when it is not a JSON
     * object at all.
     *
     * <p>Null is the honest answer for a truncated or empty body -- the same "a
     * window claims nothing" rule the depth and normal sources follow -- because a
     * half-fetched sidecar is not a sidecar with no lens; it is no sidecar.
     */
    public static SplatSidecar parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            return new SplatSidecar(
                    (int) Math.round(o.optDouble("focal", 0d)),
                    o.optInt("width", 0),
                    o.optInt("height", 0));
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code focal / height}, or 0 when either cannot claim one. */
    public float lens() {
        return Splat.focalOverHeight(focal, height);
    }
}
