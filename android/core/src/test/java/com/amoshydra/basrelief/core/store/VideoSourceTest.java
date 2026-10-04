package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a row is a photograph or a clip, and whether the clip can play yet.
 *
 * <p><b>The failure this class exists to prevent is a confident wrong claim about
 * the medium.</b> {@link DepthSource} was written because a PNG carrying its depth
 * in a {@code dePH} chunk printed <b>"No depth"</b> over a photograph whose relief
 * was visibly playing — the status line lied about the picture on screen, which is
 * the one thing a status line exists not to do. A clip opens a second window of the
 * same shape, and it is <i>wider</i>: before the decoder reports, a row naming clips
 * is going to render as one of two genuinely different pictures.
 *
 * <p>The traps:
 *
 * <ol>
 *   <li><b>The window claims nothing.</b> Not "still" and not "clip" — both are
 *       claims, and the default has to be the one that says nothing.
 *   <li><b>No photograph ever has a window.</b> A row with no clips is answered from
 *       the index alone, so no still's tile or status line flickers through
 *       {@code UNREAD} on every selection.
 *   <li><b>POSTER is not STILL.</b> A clip whose files are not here is not a
 *       photograph; it is a clip that fell back to its poster frame, and telling the
 *       user their video is a photo would send them to regenerate a depth map when
 *       what is missing is a file that was there.
 * </ol>
 */
public class VideoSourceTest {

    private static LibraryItem still(String id) {
        return LibraryItem.of(id, "item-" + id + ".png", "item-" + id + "-depth.png", 1L);
    }

    private static LibraryItem clip(String id) {
        return still(id).withVideo(VideoTracks.of(
                LibraryItem.videoFileName(id), LibraryItem.videoDepthFileName(id)));
    }

    private static LibraryItem shadedClip(String id) {
        return clip(id).withVideo(VideoTracks.of(
                LibraryItem.videoFileName(id), LibraryItem.videoDepthFileName(id),
                LibraryItem.videoNormalFileName(id)));
    }

    // ---------------------------------------------------------- the truth table

    @Test
    public void aRowWithNoClipsIsStillAndNeedsNoRead() {
        VideoSource.Answer a = VideoSource.resolve(false, null);
        assertEquals(VideoSource.Kind.STILL, a.kind());
        assertTrue("no clips is the only state that may say so", a.reportsNoClip());
        assertFalse(a.hasClips());
        assertFalse(a.playsClip());
        assertFalse(a.isUnread());
    }

    @Test
    public void bothClipsPresentIsAClip() {
        VideoSource.Answer a = VideoSource.resolve(true, Boolean.TRUE);
        assertEquals(VideoSource.Kind.CLIP, a.kind());
        assertTrue(a.playsClip());
        assertTrue(a.hasClips());
        assertFalse("a playing clip is not a photograph", a.reportsNoClip());
        assertFalse(a.isUnread());
    }

    /**
     * The window, as an assertion: while the clips have not been read the app must
     * say nothing about the medium.
     *
     * <p>Both wrong answers are asserted, because both were available and one of them
     * is what a reader reaching for a default would write. Saying "still" would tell
     * the user their video is a photograph; saying "clip" would start a decoder for a
     * row whose clips a restore deleted.
     */
    @Test
    public void theUnreadWindowClaimsNothingAboutTheMedium() {
        VideoSource.Answer u = VideoSource.resolve(true, null);
        assertEquals(VideoSource.Kind.UNREAD, u.kind());
        assertTrue(u.isUnread());
        assertFalse("not evidence of absence", u.hasClips());
        assertFalse("and not evidence of presence either", u.playsClip());
        assertFalse("THE bug in a new place: the window must never say \"photograph\"",
                u.reportsNoClip());
    }

    /**
     * A clip whose files are not all here is a clip that fell back — never a
     * photograph.
     *
     * <p>§3 step 8 makes the poster frame the fallback precisely so this state is
     * renderable. What it must not be is {@code STILL}, because {@code STILL} is the
     * state that licenses offering a depth map to regenerate, and the user's problem
     * is not a missing map.
     */
    @Test
    public void aClipWithAMissingFileFallsBackToItsPosterRatherThanBecomingAPhoto() {
        VideoSource.Answer a = VideoSource.resolve(true, Boolean.FALSE);
        assertEquals(VideoSource.Kind.POSTER, a.kind());
        assertTrue("it still has clips", a.hasClips());
        assertFalse("but they are not what will be drawn", a.playsClip());
        assertFalse("and it is emphatically not a photograph", a.reportsNoClip());
        assertFalse(a.isUnread());
    }

    /**
 * Only {@code STILL} says "photograph", and every kind is reachable.
 *
     * <p>Enumerated over {@link VideoSource.Kind#values()} rather than over a hand-
 * written list of answers, because a hand-written list is exactly the guard that goes
 * stale: a fifth kind could be added and this test would keep passing. One answer per
 * kind, each checked against the claim it is allowed to make.
     */
    @Test
    public void everyKindIsReachableAndOnlyStillSaysPhotograph() {
        VideoSource.Answer[] all = {
                VideoSource.resolve(false, null),
                VideoSource.resolve(false, Boolean.TRUE),
                VideoSource.resolve(false, Boolean.FALSE),
                VideoSource.resolve(true, null),
                VideoSource.resolve(true, Boolean.TRUE),
                VideoSource.resolve(true, Boolean.FALSE),
        };
        for (VideoSource.Kind kind : VideoSource.Kind.values()) {
            VideoSource.Answer mine = null;
            for (VideoSource.Answer a : all) {
                if (a.kind() == kind) { mine = a; break; }
            }
            assertNotNull("no input reaches " + kind, mine);
            assertEquals("only STILL may say this is a photograph",
                    kind == VideoSource.Kind.STILL, mine.reportsNoClip());
            assertEquals("only CLIP plays", kind == VideoSource.Kind.CLIP, mine.playsClip());
            assertEquals("only UNREAD is unknown",
                    kind == VideoSource.Kind.UNREAD, mine.isUnread());
        }
        assertEquals("and the closed set is four, so a fifth kind fails here",
                4, VideoSource.Kind.values().length);
    }

    // --------------------------------------------------------------- for an item

    /** A photograph is answered from the index alone — no window, ever. */
    @Test
    public void aPhotographIsNeverUnread() {
        LibraryItem photo = still("a");
        assertEquals(VideoSource.Kind.STILL,
                VideoSource.forItem(photo, null).kind());
        assertEquals(VideoSource.Kind.STILL,
                VideoSource.forItem(photo, Boolean.TRUE).kind());
        assertEquals(VideoSource.Kind.STILL,
                VideoSource.forItem(photo, Boolean.FALSE).kind());
    }

    /** A clip row is unknown until its clips are read, and then it is one thing or the other. */
    @Test
    public void aClipRowIsUnknownUntilItIsRead() {
        LibraryItem item = clip("a");
        assertEquals(VideoSource.Kind.UNREAD, VideoSource.forItem(item, null).kind());
        assertEquals(VideoSource.Kind.CLIP, VideoSource.forItem(item, Boolean.TRUE).kind());
        assertEquals(VideoSource.Kind.POSTER, VideoSource.forItem(item, Boolean.FALSE).kind());
    }

    /**
     * Nothing selected is the packaged demo pair, which is three PNGs.
     *
     * <p>The same reasoning that makes {@link DepthSource#forItem} answer
     * {@code FILE} for no selection and {@link NormalSource#forItem} answer
     * {@code SUPPLIED}: there is no clip behind the demo, so there is nothing for the
     * medium to be.
     */
    @Test
    public void noSelectionIsTheStillPackagedPair() {
        VideoSource.Answer a = VideoSource.forItem(null, null);
        assertEquals(VideoSource.Kind.STILL, a.kind());
        assertTrue(a.reportsNoClip());
    }

    /**
     * One question, answered from the row, and the row is the only place it lives.
     *
     * <p><b>Why this is asserted rather than assumed.</b> The index owns the selection
     * and only the index, so the gallery, the studio and the wallpaper all have to be
     * able to ask "is this row a clip?" of the row they already hold. If the answer
     * needed a second lookup, the two could disagree — and the disagreement is a
     * wallpaper showing the wrong medium on the user's home screen, with nothing in
     * the log.
     */
    @Test
    public void theRowItselfAnswersWhetherItIsAClip() {
        assertFalse("a photograph is not a clip", still("a").isVideo());
        assertTrue(clip("a").isVideo());
        assertTrue(shadedClip("a").isVideo());
        assertEquals("and the reference is the same fact, not a second copy",
                clip("a").isVideo(), clip("a").video != null);
    }

    // --------------------------------------------------------------- the whole surface

    /**
     * Every combination answers, and the flags stay internally consistent.
     *
     * <p>Consistency is asserted rather than re-listed, so a kind added later fails
     * here instead of inheriting a default. The three facts are: only {@code STILL}
     * reports no clip; a playing clip is the only state that plays; and only
     * {@code UNREAD} is unknown.
     */
    @Test
    public void everyInputAnswersAndTheFlagsStayConsistent() {
        Boolean[] playable = {null, Boolean.TRUE, Boolean.FALSE};
        int seen = 0;
        for (boolean hasClips : new boolean[]{false, true}) {
            for (Boolean p : playable) {
                VideoSource.Answer a = VideoSource.resolve(hasClips, p);
                assertEquals("reportsNoClip is STILL alone, got " + a,
                        a.kind() == VideoSource.Kind.STILL, a.reportsNoClip());
                assertEquals("playsClip is CLIP alone, got " + a,
                        a.kind() == VideoSource.Kind.CLIP, a.playsClip());
                assertEquals("isUnknown is UNREAD alone, got " + a,
                        a.kind() == VideoSource.Kind.UNREAD, a.isUnread());
                assertEquals("hasClips is CLIP or POSTER, got " + a,
                        a.kind() == VideoSource.Kind.CLIP
                                || a.kind() == VideoSource.Kind.POSTER, a.hasClips());
                seen++;
            }
        }
        assertEquals(6, seen);
    }

    /** The answers are singletons, so a caller may compare them by identity. */
    @Test
    public void theAnswersAreSingletons() {
        assertSame(VideoSource.resolve(false, null), VideoSource.forItem(null, null));
        assertSame(VideoSource.resolve(true, null), VideoSource.forItem(clip("a"), null));
        assertSame(VideoSource.resolve(true, Boolean.TRUE),
                VideoSource.forItem(clip("a"), Boolean.TRUE));
        assertSame(VideoSource.resolve(true, Boolean.FALSE),
                VideoSource.forItem(clip("a"), Boolean.FALSE));
    }

    /** Each kind says its own name, which is what a diagnostic line will print. */
    @Test
    public void eachAnswerNamesItself() {
        assertEquals("VideoSource.UNREAD", VideoSource.UNREAD.toString());
        assertEquals("VideoSource.STILL", VideoSource.STILL.toString());
        assertEquals("VideoSource.CLIP", VideoSource.resolve(true, Boolean.TRUE).toString());
        assertEquals("VideoSource.POSTER",
                VideoSource.resolve(true, Boolean.FALSE).toString());
    }
}