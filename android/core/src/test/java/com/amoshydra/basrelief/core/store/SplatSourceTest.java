package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The fourth "where did this come from" vocabulary: a row's Gaussian splat cloud.
 *
 * <p>The trap is the same one its three siblings carry, and it is the whole reason
 * the class has three kinds rather than two: <b>a window claims nothing.</b> The
 * index says a row <i>names</i> a cloud; whether the bytes are here is a file read,
 * so answering {@link SplatSource.Kind#NONE} during that window would put "no cloud
 * yet — make one?" over a row that already has one — the confident wrong answer
 * {@code DepthSource} was written to remove, with a different noun.
 */
public class SplatSourceTest {

    private static final String ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    private static LibraryItem item(String splatName) {
        LibraryItem it = LibraryItem.of(ID, "a.png", null, 1L);
        return splatName == null ? it : it.withSplat(splatName);
    }

    /** A row with no cloud never has a window: the answer is known from the index. */
    @Test
    public void aRowWithNoCloudIsNoneAndNeedsNoRead() {
        assertEquals(SplatSource.Kind.NONE,
                SplatSource.forItem(item(null), null).kind());
        assertEquals(SplatSource.Kind.NONE,
                SplatSource.forItem(item(null), Boolean.TRUE).kind());
        assertTrue(SplatSource.forItem(item(null), null).reportsNoCloud());
    }

    /** The window before the file has been read claims nothing at all. */
    @Test
    public void theUnreadWindowClaimsNothing() {
        SplatSource.Answer a = SplatSource.forItem(item("item-x.splat"), null);
        assertTrue(a.isUnread());
        assertFalse("an unread row is not evidence of a cloud", a.hasCloud());
        assertFalse("and it is not evidence of absence either", a.reportsNoCloud());
    }

    /** A named cloud that is here is FILE; one that is not is NONE. */
    @Test
    public void aNamedCloudIsFileOnlyWhenItIsHere() {
        assertEquals(SplatSource.Kind.FILE,
                SplatSource.forItem(item("item-x.splat"), Boolean.TRUE).kind());
        assertEquals(SplatSource.Kind.NONE,
                SplatSource.forItem(item("item-x.splat"), Boolean.FALSE).kind());
        assertTrue(SplatSource.forItem(item("item-x.splat"), Boolean.TRUE).hasCloud());
    }

    /** Only NONE may say "no cloud"; every kind is reachable. */
    @Test
    public void onlyNoneSaysNoCloudAndEveryKindIsReachable() {
        for (SplatSource.Kind kind : SplatSource.Kind.values()) {
            SplatSource.Answer a = answerFor(kind);
            assertEquals(kind, a.kind());
            assertEquals("only NONE reports no cloud: " + kind,
                    kind == SplatSource.Kind.NONE, a.reportsNoCloud());
            assertEquals("only FILE has a cloud: " + kind,
                    kind == SplatSource.Kind.FILE, a.hasCloud());
            assertEquals("only UNREAD is unread: " + kind,
                    kind == SplatSource.Kind.UNREAD, a.isUnread());
        }
    }

    private static SplatSource.Answer answerFor(SplatSource.Kind kind) {
        switch (kind) {
            case UNREAD: return SplatSource.forItem(item("x.splat"), null);
            case FILE:   return SplatSource.forItem(item("x.splat"), Boolean.TRUE);
            case NONE:
            default:     return SplatSource.forItem(item(null), null);
        }
    }

    /** Nothing selected is not a cloud row: the sheet has no id to act against. */
    @Test
    public void noSelectionIsNone() {
        assertEquals(SplatSource.Kind.NONE, SplatSource.forItem(null, null).kind());
    }

    /** The two fixed answers are singletons, like their siblings'. */
    @Test
    public void theAnswersAreSingletons() {
        assertSame(SplatSource.UNREAD, SplatSource.forItem(item("x.splat"), null));
        assertSame(SplatSource.forItem(item(null), null), SplatSource.forItem(null, null));
    }
}
