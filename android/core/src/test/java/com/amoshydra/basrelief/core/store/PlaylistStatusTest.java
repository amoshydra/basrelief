package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * {@link PlaylistStatus}: the one reading of what a playlist is doing, and the
 * immediate step the user's Use action needs.
 *
 * <p>The trap under these is <b>a status that is written down</b>. Every fact
 * here already lives on a playlist or on the index, so a stored copy would be a
 * second source that can disagree — and {@code PlaylistTest} enumerates the
 * blob's allowed keys, which is the structural half of the same rule. So the
 * tests read the derivation against values that were changed underneath it and
 * check that nothing was persisted to remember.
 *
 * <p>The second theme is that <b>the surface is a gate, not a hint</b>. A
 * playlist the surfaces could not be read from is not rotating anywhere, and a
 * status that guessed the home screen would put a running badge on a wallpaper
 * nothing will move.
 */
public class PlaylistStatusTest {

    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final long MINUTE = 60L * 1000L;
    private static final long NOW = 1_700_000_000_000L;

    // -------------------------------------------------------------- helpers

    private static LibraryItem item(String id) {
        return LibraryItem.of(id, id + ".png", id + "_depth.png", 1000L);
    }

    private static Library lib(String... ids) {
        Library l = Library.empty();
        for (String id : ids) l = l.withItem(item(id));
        return l;
    }

    private static Playlist playlist(String id, String... itemIds) {
        return Playlist.create(id, id, Arrays.asList(itemIds));
    }

    private static Playlists playlistsOf(Playlist... list) {
        Playlists p = Playlists.empty();
        for (Playlist one : list) p = p.withPlaylist(one);
        return p;
    }

    /** One active daily playlist over a library of {@code ids}. */
    private static Playlists daily(String playlistId, String... itemIds) {
        return playlistsOf(playlist(playlistId, itemIds))
                .withActive(playlistId, true);
    }

    // ---------------------------------------------------------------- status

    /** Nothing active, or nothing at all, is no status — and must not throw. */
    @Test
    public void nothingRotatingIsNone() {
        PlaylistStatus s = PlaylistStatus.of(Playlists.empty(), lib("a"), NOW);
        assertFalse(s.isRotating());
        assertNull(s.playlist);
        assertEquals(0, s.position);
        assertEquals(0, s.size);
        assertFalse(s.dueNow);
        assertEquals(0L, s.nextChangeAt);
        assertEquals(0, PlaylistStatus.rotatingCount(Playlists.empty()));

        // A caller that has not read the blob yet gets the same answer, not a
        // NullPointerException on the wallpaper's render path.
        assertFalse(PlaylistStatus.of(null, null, NOW).isRotating());
        assertEquals(0, PlaylistStatus.rotatingCount(null));
    }

    /**
     * A playlist that has never advanced is due at once.
     *
     * <p>{@code lastAdvancedAt == 0} means never, and "never" is longer than any
     * interval — so a freshly activated daily list moves now rather than in 24
     * hours, which is what makes Use visible in the same moment.
     */
    @Test
    public void neverAdvancedMeansDueNow() {
        PlaylistStatus s = PlaylistStatus.of(daily("p", "a", "b"),
                lib("a", "b").withSelection("a"), NOW);
        assertTrue(s.isRotating());
        assertEquals(Arrays.asList(Surface.HOME), s.surfaces);
        assertTrue(s.dueNow);
        // The moment the interval would elapse if it had a start; the caller
        // prints "due now" from dueNow, not from this.
        assertEquals(DAY, s.nextChangeAt);
        assertEquals(1, s.position);
        assertEquals(2, s.size);
    }

    /**
     * After a rotation the status counts down from the playlist's own clock.
     *
     * <p>Derived at read time from {@code lastAdvancedAt}, so a second status
     * object created a second later cannot disagree — there is nothing cached to
     * go stale.
     */
    @Test
    public void afterAdvanceStatusCountsDown() {
        Playlists p = daily("p", "a", "b");
        Library l = lib("a", "b").withSelection("a");
        Playlists.Result r = Playlists.advance(p, l, Surface.HOME, NOW, new Random(7));
        assertEquals("b", r.library.activeId());

        PlaylistStatus s = PlaylistStatus.of(r.playlists, r.library, NOW + 2 * HOUR);
        assertTrue(s.isRotating());
        assertFalse(s.dueNow);
        assertEquals(NOW + DAY, s.nextChangeAt);
        // The position follows the index the rotation just wrote; it is not
        // stored on the playlist either.
        assertEquals(2, s.position);
        assertEquals(2, s.size);
    }

    /**
     * An event trigger answers "no clock", not "due now".
     *
     * <p>Its interval is zero, so a countdown derived from it would read
     * "due now" forever and print a timer that never fires. {@code nextChangeAt
     * == 0} is the honest answer; the UI says "when you unlock" instead.
     */
    @Test
    public void eventTriggerHasNoClock() {
        Playlists p = playlistsOf(playlist("p", "a", "b").withTrigger(Trigger.ON_UNLOCK))
                .withActive("p", true);
        PlaylistStatus s = PlaylistStatus.of(p, lib("a", "b").withSelection("a"), NOW);
        assertTrue(s.isRotating());
        assertFalse(s.dueNow);
        assertEquals(0L, s.nextChangeAt);
    }

    /** A lock-only playlist rotates on lock and not on home; the status says both. */
    @Test
    public void lockOnlyPlaylistRotatesOnlyOnLock() {
        Playlist p = playlist("p", "a", "b").withSurfaces(Arrays.asList(Surface.LOCK));
        Playlists lists = playlistsOf(p).withActive("p", true);

        assertNull(lists.rotating(Surface.HOME));
        assertNotNull(lists.rotating(Surface.LOCK));

        PlaylistStatus s = PlaylistStatus.of(lists, lib("a", "b"), NOW);
        assertTrue(s.isRotating());
        assertFalse(s.appliesTo(Surface.HOME));
        assertTrue(s.appliesTo(Surface.LOCK));
    }

    /**
     * An empty surface list rotates nowhere, whatever the active flag says.
     *
     * <p>A blob with no readable screen is one this build cannot act on; guessing
     * home would start moving a wallpaper the user never asked to move. The blob
     * can still hold the playlist, which is why the gate has to be tested here
     * rather than assumed from {@code active}.
     */
    @Test
    public void unreadableSurfacesRotateNowhere() {
        Playlists lists = Playlists.parse("{\"version\":1,\"playlists\":[{\"id\":\"p\","
                + "\"name\":\"p\",\"active\":true,\"surfaces\":[],"
                + "\"item_ids\":[\"a\",\"b\"]}]}");
        assertNotNull(lists.active());

        PlaylistStatus s = PlaylistStatus.of(lists, lib("a", "b"), NOW);
        assertFalse(s.isRotating());
        assertEquals(0, PlaylistStatus.rotatingCount(lists));
    }

    /**
     * A running rotation shows a position only when the photo on screen is one of
     * its own.
     *
     * <p>A wallpaper can be showing a photo the user picked by hand while a
     * playlist rotates behind it; printing "1 of 2" then would be a count that is
     * always wrong, which is the one thing a rotation status may not be.
     */
    @Test
    public void positionIsZeroWhenTheSelectionIsElsewhere() {
        PlaylistStatus s = PlaylistStatus.of(daily("p", "a", "b"),
                lib("a", "b", "c").withSelection("c"), NOW);
        assertTrue(s.isRotating());
        assertEquals(0, s.position);
        assertEquals(2, s.size);
    }

    // ------------------------------------------------------------------ step

    /**
     * {@link Playlists#step} moves now even when the schedule says not yet.
     *
     * <p>This is the Use action. A playlist that advanced an hour ago is not due,
     * and if Use inherited that gate the wallpaper would sit still for the rest
     * of the interval — indistinguishable from a broken feature. The step must
     * also be the <i>same write</i> a rotation is, and stamp the real now rather
     * than a faked due time, or it would delay the next automatic rotation.
     */
    @Test
    public void stepMovesEvenWhenTheScheduleSaysNo() {
        Playlists p = daily("p", "a", "b");
        Library l = lib("a", "b").withSelection("a");
        Playlists.Result first = Playlists.advance(p, l, Surface.HOME, NOW, new Random(7));
        assertEquals("b", first.library.activeId());

        // The precondition: the scheduled path declines here.
        assertFalse(Playlists.advance(first.playlists, first.library, Surface.HOME,
                NOW + MINUTE).rotated());

        Playlists.Result stepped = Playlists.step(first.playlists, first.library,
                Surface.HOME, NOW + MINUTE);
        assertTrue(stepped.rotated());
        assertEquals("a", stepped.selectedId);
        // Byte-identical to the two index calls a tap makes, and the playlist's
        // own clock stamped at the real moment.
        assertEquals(first.library.withSelection("a").markUsed("a", NOW + MINUTE).toJson(),
                stepped.library.toJson());
        assertEquals(NOW + MINUTE, stepped.playlists.active().lastAdvancedAt);
    }

    /** A step with nothing rotating changes nothing, so a caller may still persist. */
    @Test
    public void stepDoesNothingWhenNothingRotates() {
        Library l = lib("a", "b").withSelection("a");
        Playlists.Result r = Playlists.step(Playlists.empty(), l, Surface.HOME, NOW);
        assertFalse(r.rotated());
        assertEquals(l.toJson(), r.library.toJson());

        // Active, but on no screen: the same no-op rather than a guess at home.
        Playlists unreadable = Playlists.parse("{\"version\":1,\"playlists\":[{\"id\":\"p\","
                + "\"active\":true,\"surfaces\":[],\"item_ids\":[\"a\",\"b\"]}]}");
        assertFalse(Playlists.step(unreadable, l, Surface.HOME, NOW).rotated());
    }

    /**
     * One rotator covering both screens counts once, not twice.
     *
     * <p>The count exists to put a single visible number on the gallery; counting
     * the same playlist once per surface would print "2 rotating" beside one
     * playlist, which is a lie a user can see.
     */
    @Test
    public void countCountsOneRotatorOnce() {
        Playlists lists = playlistsOf(playlist("p", "a", "b").alsoOn(Surface.LOCK))
                .withActive("p", true);
        assertEquals(1, PlaylistStatus.rotatingCount(lists));
        assertTrue(PlaylistStatus.of(lists, lib("a", "b"), NOW).appliesTo(Surface.LOCK));
    }
}
