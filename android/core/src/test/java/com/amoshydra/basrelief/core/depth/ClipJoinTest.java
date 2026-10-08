package com.amoshydra.basrelief.core.depth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The join's arithmetic: one frame's duration, and where the next part starts.
 *
 * <p>This is the half of {@code VideoImport.joinClips} that a device test cannot
 * reach — the loop itself is {@code MediaExtractor} + {@code MediaMuxer} — and
 * the half that decides whether the joined clip can hold every frame its parts
 * do. It exists because a merge that loses or overlaps a frame fails silently:
 * the file decodes cleanly and plays one frame short.
 *
 * <p>The measured two-chunk import that prompted this work lost its last frame
 * upstream of the join — the plan asked for one frame fewer than the colour clip
 * held, see {@link FrameCountTest} — and this class is the other half of the
 * guarantee: given parts whose sum is right, the seam must not be where a frame
 * is dropped.
 */
public class ClipJoinTest {

    /** One frame at 24fps, the rate the measured remux carries. */
    private static final long FRAME_US = 41667L;

    /**
     * The frame duration is the SMALLEST positive gap, not a consecutive delta.
     *
     * <p>The list is decode order for a closed GOP — 0, 4, 2, 1, 3 — exactly what
     * {@code MediaExtractor} hands back when the encoder carried B-frames. The
     * consecutive deltas are +4, -2, -1, +2 frames; only the sorted set says
     * every step is one frame.
     */
    @Test
    public void theFrameDurationIsTheSmallestPositiveStep() {
        List<Long> decodeOrder = Arrays.asList(0L, 4 * FRAME_US, 2 * FRAME_US,
                FRAME_US, 3 * FRAME_US);
        assertEquals("B-frames make consecutive deltas meaningless",
                FRAME_US, ClipJoin.frameGapUs(decodeOrder, -1L));
    }

    /**
     * A part that cannot show its own spacing takes the caller's fallback, and a
     * caller with neither gets -1 — the value that makes the join refuse rather
     * than mux frames on top of each other.
     */
    @Test
    public void aPartWithoutSpacingTakesTheFallback() {
        assertEquals("one frame shows no spacing",
                FRAME_US, ClipJoin.frameGapUs(Arrays.asList(0L), FRAME_US));
        assertEquals("no frames, same answer",
                FRAME_US, ClipJoin.frameGapUs(new ArrayList<Long>(), FRAME_US));
        assertEquals("no spacing and no fallback is -1",
                -1L, ClipJoin.frameGapUs(new ArrayList<Long>(), -1L));
    }

    /**
     * THE BOUNDARY CASE. A part whose first frame would land on the previous
     * part's last frame is shifted strictly past it, so the merged timeline holds
     * every frame of both parts — including the second part's first, which is the
     * frame a same-instant write loses.
     *
     * <p>The numbers are the shape of the real join: part A is 3 frames on the 24fps
     * grid; part B's first raw timestamp EQUALS part A's maximum before rebasing,
     * because each part restarts its own clock and the second one's zero has to be
     * placed relative to the first one's end.
     */
    @Test
    public void theNextPartStartsStrictlyAfterThePreviousPartsLastFrame() {
        List<Long> partA = Arrays.asList(0L, FRAME_US, 2 * FRAME_US);
        long maxA = 2 * FRAME_US;
        long gap = ClipJoin.frameGapUs(partA, -1L);

        List<Long> partB = Arrays.asList(maxA, maxA + FRAME_US, maxA + 2 * FRAME_US);
        long shift = ClipJoin.offsetAfter(maxA, gap);

        assertTrue("the seam is strictly after the last frame, not on it",
                shift > maxA);
        assertEquals("and exactly one frame after it", maxA + FRAME_US, shift);

        List<Long> merged = new ArrayList<Long>(partA);
        for (long t : partB) merged.add(t - partB.get(0) + shift);
        java.util.Collections.sort(merged);
        assertEquals("every frame of both parts survives", 6, merged.size());
        assertEquals("the seam sits one frame after the first part",
                maxA + FRAME_US, merged.get(3).longValue());
        for (int i = 1; i < merged.size(); i++) {
            assertEquals("frames stay one frame apart across the seam",
                    FRAME_US, merged.get(i) - merged.get(i - 1));
        }
    }

    /**
     * A zero gap adds nothing, which is a collision — pinned because it is what
     * the caller must refuse for every part but the last. A run whose only part
     * is one frame needs no spacing, so the last part may use it.
     */
    @Test
    public void aZeroGapIsACollisionAndTheCallerMustRefuseIt() {
        assertEquals("writing two samples at one instant is what the guard is for",
                1000L, ClipJoin.offsetAfter(1000L, 0L));
        assertEquals("and a part with no own spacing adds none",
                1000L, ClipJoin.offsetAfter(1000L, ClipJoin.frameGapUs(
                        Arrays.asList(5L), -1L)));
    }
}
