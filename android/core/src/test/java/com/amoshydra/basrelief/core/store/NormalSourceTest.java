package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Shading is separate from displacement, and this is the one resolution of it.
 *
 * <p><b>The state this class adds is the one the app has never had:</b> a photo
 * with depth and no normal. It is displaced correctly and lit as a flat plane.
 * The traps, in the order they can bite:
 *
 * <ol>
 *   <li><b>There must be no derived state.</b> The old derived-normal path is
 *       gone, so an answer that named it would put "shaded" on the sheet while
 *       the shader drew flat — the same two-answers-to-one-question failure
 *       {@link DepthSource} exists to prevent. Only the two real states exist.
 *   <li><b>A normal with no depth is inert</b>, not supplied. There is no
 *       geometry for it to shade, so calling it shaded would describe a render
 *       that cannot happen.
 *   <li><b>Nothing selected is the packaged demo pair</b>, which ships a normal,
 *       so the sheet stays silent rather than offering a fix for a photo that is
 *       already shaded.
 * </ol>
 */
public class NormalSourceTest {

    private static LibraryItem depthOnly(String id) {
        return LibraryItem.of(id, id + ".png", id + "-depth.png", 1L);
    }

    private static LibraryItem shaded(String id) {
        return depthOnly(id).withDepthAndNormal(id + "-depth.png", id + "-normal.png");
    }

    @Test
    public void aDepthWithANormalIsSupplied() {
        NormalSource.Answer a = NormalSource.resolve(true, true);
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertTrue(a.hasSuppliedNormals());
        assertFalse(a.needsNormals());
    }

    /** The new middle state: depth, no normals, flat lit. */
    @Test
    public void aDepthWithoutANormalIsNoneAndNeedsOne() {
        NormalSource.Answer a = NormalSource.resolve(true, false);
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertFalse("flat lit, not shaded", a.hasSuppliedNormals());
        assertTrue("and the sheet may offer the fix", a.needsNormals());
    }

    /**
     * A normal with no depth is inert: the renderer draws no relief without a
     * depth map, so this is not the shaded state however many files it names.
     */
    @Test
    public void aNormalWithoutADepthIsNotSupplied() {
        NormalSource.Answer a = NormalSource.resolve(false, true);
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertFalse(a.hasSuppliedNormals());
    }

    @Test
    public void neitherFileIsNone() {
        assertEquals(NormalSource.Kind.NONE, NormalSource.resolve(false, false).kind());
    }

    @Test
    public void anItemWithBothFilesIsSupplied() {
        NormalSource.Answer a = NormalSource.forItem(shaded("a"));
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertFalse(a.needsNormals());
    }

    @Test
    public void anItemWithOnlyDepthIsFlatLit() {
        NormalSource.Answer a = NormalSource.forItem(depthOnly("a"));
        assertEquals(NormalSource.Kind.NONE, a.kind());
        assertTrue(a.needsNormals());
    }

    /** A normal named without a depth is inert on a real item too. */
    @Test
    public void anItemWithANormalButNoDepthIsNotSupplied() {
        LibraryItem item = LibraryItem.of("a", "a.png", null, 1L)
                .withFiles("a.png", null, "a-normal.png");
        assertFalse(item.hasSuppliedNormals());
        assertEquals(NormalSource.Kind.NONE, NormalSource.forItem(item).kind());
    }

    /**
     * Nothing selected is the packaged demo pair, which ships a normal — so the
     * sheet is silent rather than offering to supply what is already drawn. This
     * mirrors {@link DepthSource#forItem}'s answer for no selection.
     */
    @Test
    public void noSelectionIsTheShadedPackagedPair() {
        NormalSource.Answer a = NormalSource.forItem(null);
        assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
        assertFalse(a.needsNormals());
    }

    /**
     * Exactly one kind reports supplied, stated over every reachable answer, so a
     * future kind cannot be added without this failing.
     */
    @Test
    public void suppliedIsTheOnlyStateThatReportsShading() {
        NormalSource.Answer[] all = {
                NormalSource.resolve(false, false),
                NormalSource.resolve(true, false),
                NormalSource.resolve(false, true),
                NormalSource.resolve(true, true),
                NormalSource.forItem(null),
                NormalSource.forItem(depthOnly("a")),
                NormalSource.forItem(shaded("a")),
        };
        int supplied = 0;
        for (NormalSource.Answer a : all) {
            if (a.hasSuppliedNormals()) {
                supplied++;
                assertEquals(NormalSource.Kind.SUPPLIED, a.kind());
                assertFalse(a.needsNormals());
            } else {
                assertEquals(NormalSource.Kind.NONE, a.kind());
                assertTrue(a.needsNormals());
            }
        }
        assertTrue(supplied > 0);
    }

    @Test
    public void theAnswersAreSingletons() {
        assertSame(NormalSource.resolve(true, true), NormalSource.resolve(true, true));
        assertSame(NormalSource.resolve(true, false), NormalSource.resolve(false, true));
        assertSame(NormalSource.forItem(null), NormalSource.resolve(true, true));
    }

    @Test
    public void eachAnswerNamesItself() {
        assertEquals("NormalSource.NONE", NormalSource.resolve(true, false).toString());
        assertEquals("NormalSource.SUPPLIED", NormalSource.resolve(true, true).toString());
    }
}
