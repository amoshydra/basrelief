package com.amoshydra.basrelief.core.depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The arithmetic of joining chunk clips: where each part's timeline starts, and
 * how long one frame of a part is.
 *
 * <p><b>Why this is here and not in the muxer loop.</b> The join is
 * {@code MediaExtractor} + {@code MediaMuxer}, so it cannot run in the JVM suite
 * — but the two decisions that make it wrong are pure numbers, and a join that
 * loses or overlaps a frame is exactly the kind of defect that surfaces as a
 * hitch at every loop rather than as an error. This is that arithmetic as plain
 * values, and {@code ClipJoinTest} is the whole check.
 *
 * <p><b>The frame duration is the SMALLEST positive gap between a part's own
 * timestamps, not a consecutive delta.</b> H.264 from the combiner may carry
 * B-frames, so the extractor hands samples back in DECODE order and consecutive
 * timestamps jump around; the sorted set still has every step as a multiple of
 * one frame, so the smallest positive difference is that frame.
 *
 * <p><b>The next part starts STRICTLY after the previous part's last frame.</b>
 * {@link #offsetAfter} adds the gap, so a part whose first sample rebases to
 * zero lands one frame after the previous part's maximum — never on it. A
 * zero-length gap is the one case that would write two samples at the same
 * presentation time, and the caller refuses it for any part but the last (see
 * {@code VideoImport.joinClips}): the muxer consults decoding times and does not
 * guarantee what two identical stamps become, and a dropped or merged frame at
 * a seam is a clip that plays one frame short without any error anywhere.
 */
public final class ClipJoin {

    private ClipJoin() {
    }

    /**
     * The frame duration one part's timestamps imply, or {@code fallbackUs}.
     *
     * <p>A part with fewer than two samples cannot show its own spacing, so it
     * takes the caller's fallback — the spacing a previous part already proved,
     * or the container's declared rate. A non-positive fallback comes back
     * unchanged, and the caller decides whether it has a join at all.
     *
     * @param timesUs    one part's timestamps, rebased or not; order is not read
     * @param fallbackUs the gap to use when the part cannot show one; may be &lt;= 0
     * @return the smallest positive difference between two timestamps, or
     *         {@code fallbackUs} when the part shows none
     */
    public static long frameGapUs(List<Long> timesUs, long fallbackUs) {
        if (timesUs == null || timesUs.size() < 2) return fallbackUs;
        List<Long> sorted = new ArrayList<Long>(timesUs);
        Collections.sort(sorted);
        long smallest = -1;
        for (int i = 1; i < sorted.size(); i++) {
            long d = sorted.get(i) - sorted.get(i - 1);
            if (d > 0 && (smallest < 0 || d < smallest)) smallest = d;
        }
        return smallest > 0 ? smallest : fallbackUs;
    }

    /**
     * Where the next part's rebased timestamps are shifted to.
     *
     * <p>A positive gap makes the result strictly greater than
     * {@code previousMaxUs}; a gap that is zero or negative adds nothing, which
     * is the collision the caller must refuse before this is ever asked (see the
     * class note). The rule exists so the joined timeline can contain every
     * frame of every part: a shift that only reached {@code previousMaxUs} would
     * put the next part's first frame on the previous part's last one, and a
     * muxer asked to write two samples at one instant is a frame away from the
     * count the parts sum to.
     */
    public static long offsetAfter(long previousMaxUs, long gapUs) {
        return previousMaxUs + Math.max(0L, gapUs);
    }
}
