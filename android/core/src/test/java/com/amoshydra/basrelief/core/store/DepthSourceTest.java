package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where a photograph's depth comes from, and what the app may offer to do about it.
 *
 * <p><b>The reported bug is the first test in this file.</b> A PNG carrying its
 * depth map in a {@code dePH} chunk has {@code depthName == null}, so the studio
 * asked {@code depthName != null}, got "no", and printed <b>"No depth"</b> over a
 * photograph whose relief was visibly playing. The gallery had it right from
 * {@code Io}'s embedded callback. Two answers to one question.
 *
 * <p>The traps, in the order they can bite:
 *
 * <ol>
 *   <li><b>UNKNOWN must not reach "No depth".</b> The embedded answer is a file
 *       read, so there is a window on every selection before it is known, and
 *       defaulting that window to "no depth" <i>is</i> the bug. A boolean cannot
 *       carry three states, so the window has its own kind and every claim is
 *       tested against it.
 *   <li><b>A file beats an embedded map</b>, because that is the order the
 *       renderer reads in. Getting it backwards describes a render that is not
 *       happening, which is the same class of lie in the other direction.
 *   <li><b>A photo with a depth file never needs the read</b>, so its answer is
 *       known from the index and must never come back UNKNOWN. A caller that asked
 *       for the read before checking this would put every mapped photo through the
 *       window and make the whole sheet flicker on every selection.
 * </ol>
 */
public class DepthSourceTest {

    private static LibraryItem noMap(String id) {
        return LibraryItem.of(id, id + ".png", null, 1L);
    }

    private static LibraryItem withMap(String id) {
        return LibraryItem.of(id, id + ".png", "ai_depth.png", 1L);
    }

    // ------------------------------------------------------------- the truth table

    @Test
    public void noFileAndNoEmbeddedChunkIsTheOnlyNone() {
        DepthSource.Answer a = DepthSource.resolve(false, false);
        assertEquals(DepthSource.Kind.NONE, a.kind());
        assertTrue("this is the one state that may say it", a.reportsNoDepth());
        assertFalse(a.hasDepth());
        assertTrue(a.canRegenerate());
        assertTrue(a.canReplace());
    }

    @Test
    public void aSeparateFileIsFileAndOffersBothActions() {
        DepthSource.Answer a = DepthSource.resolve(true, false);
        assertEquals(DepthSource.Kind.FILE, a.kind());
        assertTrue(a.hasDepth());
        assertFalse("a photo with a map is never told it has none", a.reportsNoDepth());
        assertTrue(a.canRegenerate());
        assertTrue(a.canReplace());
    }

    /**
     * The user's product decision, pinned: an embedded map IS depth, and it offers
     * nothing.
     *
     * <p>Both halves matter. Calling it "no depth" is the reported bug; offering
     * Regenerate or Replace is a control that cannot act — there is no depth file to
     * replace, and generating is out of scope for this case.
     */
    @Test
    public void anEmbeddedMapHasDepthAndOffersNoActions() {
        DepthSource.Answer a = DepthSource.resolve(false, true);
        assertEquals(DepthSource.Kind.EMBEDDED, a.kind());
        assertTrue("the relief is drawing it, so it HAS depth", a.hasDepth());
        assertFalse("and must never be told it has none", a.reportsNoDepth());
        assertFalse("there is nothing to replace", a.canReplace());
        assertFalse("and generating is out of scope for this case", a.canRegenerate());
    }

    /**
     * A file beats an embedded map, because that is the order the renderer reads in.
     *
     * <p>{@code ReliefRenderer.decodePair} reads a separate depth file when there is
     * one and only then falls back to the image's chunk. A photo carrying both is
     * drawn from the file, so describing it by its embedded chunk would be a
     * different lie from the same sentence.
     */
    @Test
    public void aFileWinsOverAnEmbeddedMap() {
        DepthSource.Answer both = DepthSource.resolve(true, true);
        assertEquals(DepthSource.Kind.FILE, both.kind());
        assertTrue(both.canReplace());
        assertTrue(both.canRegenerate());
        // And it is the same object as the plain file case, so a caller comparing by
        // identity cannot see two answers where there is one.
        assertSame(DepthSource.resolve(true, false), both);
    }

    // ------------------------------------------------------------- the unknown window

    /**
     * <b>The reported bug, as an assertion.</b> While the answer is unknown the app
     * must say nothing and offer nothing.
     *
     * <p>The window is real — the embedded flag is a file read off the UI thread —
     * and answering {@code NONE} inside it is exactly what put "No depth" over a
     * photograph with a working map. Every claim is checked, because each one was
     * separately a way to assert something not known.
     */
    @Test
    public void theUnknownWindowClaimsNothingAtAll() {
        DepthSource.Answer u = DepthSource.UNKNOWN;
        assertTrue(u.isUnknown());
        assertFalse("not evidence of absence", u.hasDepth());
        assertFalse("THE bug: the unknown window must never say No depth",
                u.reportsNoDepth());
        assertFalse("and must not offer to generate over a photo that may have one",
                u.canRegenerate());
        assertFalse(u.canReplace());
    }

    /**
     * Only {@code NONE} says "No depth" — stated over every reachable answer, so a
     * future {@link DepthSource.Kind} cannot be added without this failing.
     */
    @Test
    public void noneIsTheOnlyStateThatReportsNoDepth() {
        DepthSource.Answer[] all = {
                DepthSource.UNKNOWN,
                DepthSource.resolve(false, false),
                DepthSource.resolve(true, false),
                DepthSource.resolve(false, true),
        };
        int sayNo = 0;
        for (DepthSource.Answer a : all) {
            if (a.reportsNoDepth()) {
                sayNo++;
                assertEquals(DepthSource.Kind.NONE, a.kind());
            }
        }
        assertEquals("exactly one of the four states may say it", 1, sayNo);
    }

    // ------------------------------------------------------------------- for an item

    /**
     * A photo that <b>has</b> a depth file is answered from the index alone.
     *
     * <p>This is what keeps the unknown window small: the read is only ever needed
     * for a photo with no separate file, so a mapped photo's fix line never flickers
     * through "unknown" on every selection.
     */
    @Test
    public void anItemWithADepthFileIsNeverUnknown() {
        assertEquals(DepthSource.Kind.FILE,
                DepthSource.forItem(withMap("a"), null).kind());
        assertEquals(DepthSource.Kind.FILE,
                DepthSource.forItem(withMap("a"), Boolean.TRUE).kind());
    }

    /** An item with no file is unknown until the image has been read. */
    @Test
    public void anItemWithoutADepthFileIsUnknownUntilItIsRead() {
        assertEquals(DepthSource.Kind.UNKNOWN,
                DepthSource.forItem(noMap("a"), null).kind());
        assertEquals(DepthSource.Kind.EMBEDDED,
                DepthSource.forItem(noMap("a"), Boolean.TRUE).kind());
        assertEquals(DepthSource.Kind.NONE,
                DepthSource.forItem(noMap("a"), Boolean.FALSE).kind());
    }

    /**
     * Nothing selected is {@link DepthSource.Kind#FILE}.
     *
     * <p>No selection means the renderer draws the packaged demo pair, which does
     * have a map — so the honest answer is "has depth" and the line stays silent,
     * which is what it has always done there. Answering {@code NONE} would print
     * "No depth" over the marble bust, which has relief.
     */
    @Test
    public void noSelectionIsThePackagedPairAndHasDepth() {
        DepthSource.Answer a = DepthSource.forItem(null, null);
        assertEquals(DepthSource.Kind.FILE, a.kind());
        assertTrue(a.hasDepth());
        assertFalse(a.reportsNoDepth());
    }

    /**
     * The bundled demo now carries a copied depth file, so it resolves as
     * {@link DepthSource.Kind#FILE} and keeps its Regenerate/Replace row.
     *
     * <p>Pinned because it is the case that regressed once already: the demo had no
     * file, the map section grew a special body for it, and the whole of the
     * "give it a file" work exists to put it back on the ordinary path.
     */
    @Test
    public void theBundledDemoIsAnOrdinaryFileBackedPhoto() {
        LibraryItem demo = LibraryItem.bundled(
                LibraryTest.DEMO_IMAGE, LibraryTest.DEMO_DEPTH);
        DepthSource.Answer a = DepthSource.forItem(demo, null);
        assertEquals(DepthSource.Kind.FILE, a.kind());
        assertTrue(a.canRegenerate());
        assertTrue(a.canReplace());
        assertFalse(a.reportsNoDepth());
    }

    // --------------------------------------------------------------- the whole surface

    /**
     * Every combination answers, and the flags stay internally consistent.
     *
     * <p>Consistency is asserted rather than re-listed: {@code hasDepth} is true for
     * exactly FILE and EMBEDDED, and the actions for exactly NONE and FILE. A new
     * kind added later fails here instead of quietly inheriting a default.
     */
    @Test
    public void everyInputAnswersAndTheFlagsStayConsistent() {
        Boolean[] embedded = {null, Boolean.TRUE, Boolean.FALSE};
        LibraryItem[] items = {null, withMap("a"), noMap("a")};
        int seen = 0;
        for (LibraryItem item : items) {
            for (Boolean e : embedded) {
                DepthSource.Answer a = DepthSource.forItem(item, e);
                assertEquals("hasDepth must be FILE or EMBEDDED, got " + a,
                        a.kind() == DepthSource.Kind.FILE
                                || a.kind() == DepthSource.Kind.EMBEDDED, a.hasDepth());
                assertEquals("actions must be NONE or FILE, got " + a,
                        a.kind() == DepthSource.Kind.NONE
                                || a.kind() == DepthSource.Kind.FILE, a.canRegenerate());
                assertEquals(a.canRegenerate(), a.canReplace());
                assertEquals("reportsNoDepth is NONE alone, got " + a,
                        a.kind() == DepthSource.Kind.NONE, a.reportsNoDepth());
                seen++;
            }
        }
        assertEquals(9, seen);
    }

    /** The answers are singletons, so a caller may compare them by identity. */
    @Test
    public void theAnswersAreSingletons() {
        assertSame(DepthSource.resolve(false, false), DepthSource.resolve(false, false));
        assertSame(DepthSource.resolve(false, true), DepthSource.resolve(false, true));
        assertSame(DepthSource.forItem(null, null), DepthSource.resolve(true, false));
    }

    /** Each kind says its own name, which is what a diagnostic line will print. */
    @Test
    public void eachAnswerNamesItself() {
        assertEquals("DepthSource.UNKNOWN", DepthSource.UNKNOWN.toString());
        assertEquals("DepthSource.NONE", DepthSource.resolve(false, false).toString());
        assertEquals("DepthSource.FILE", DepthSource.resolve(true, false).toString());
        assertEquals("DepthSource.EMBEDDED", DepthSource.resolve(false, true).toString());
    }
}
