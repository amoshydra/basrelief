package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Playlists: the blob, and the rotation that drives the wallpaper from it.
 *
 * <p>The trap under every one of these is <b>a second pointer to the photo that
 * is on screen</b>. The index already owns that pointer, so anything a playlist
 * adds next to it is a second copy of one value, and two copies can disagree —
 * which from the home screen looks exactly like the wallpaper showed a photo that had been deleted. So the tests here spend as much effort on what the blob does
 * <i>not</i> contain as on what it does: a rotation must be indistinguishable
 * from a user's own selection, and a playlist must hold no idea of where it has
 * got to.
 *
 * <p>The second theme is the same one the rest of this package has: nothing here
 * throws, and nothing here invents. A corrupt blob must leave the wallpaper
 * exactly as it was rather than crash the cold start or start rotating at
 * random, and an unreadable field must fall back to a state that renders.
 */
public class PlaylistTest {

    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final long FAR_FUTURE = 4_000_000_000_000L;

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

    /** One active daily playlist, ready to rotate, over a library of {@code ids}. */
    private static Playlists daily(String playlistId, String... itemIds) {
        return playlistsOf(playlist(playlistId, itemIds))
                .withActive(playlistId, true);
    }

    private static String ids(Playlist p) {
        return p.itemIds.toString();
    }

    // ------------------------------------------------- the one selection key

    /**
     * The blob may not carry anything that could name the photo on screen.
     *
     * <p>Pinned by enumerating the keys rather than by looking for a bad one,
     * because a guard that says "this particular name is not allowed" is passed by
     * a new name. The top level is the version and the list; a playlist is its
     * eight own fields and nothing else — no position, no cursor, no current
     * photo. Adding one has to be a deliberate edit to this test, which is the
     * point: R-DM13 says rotation must never introduce a second "current item",
     * and the cheapest way to honour that is for the shape itself to have no room
     * for one.
     *
     * <p>Its own SharedPreferences key is pinned in the same test because the
     * other half of the rule is about keys: playlists are a separate blob, so a
     * second selection key cannot be hiding in one.
     */
    @Test
    public void theBlobCarriesNoKeyThatCouldNameTheCurrentPhoto() {
        Playlists p = daily("p", "a", "b");
        Library l = lib("a", "b").withSelection("a");
        Playlists.Result r = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));

        JSONObject blob = new JSONObject(r.playlists.toJson());
        assertEquals("top level: " + blob,
                new HashSet<String>(Arrays.asList("version", "playlists")),
                new HashSet<String>(blob.keySet()));

        JSONObject entry = blob.getJSONArray("playlists").getJSONObject(0);
        assertEquals("playlist entry: " + entry,
                new HashSet<String>(Arrays.asList("id", "name", "item_ids", "trigger",
                        "surfaces", "shuffle", "active", "last_advanced_at")),
                new HashSet<String>(entry.keySet()));

        // A second blob is allowed. A second *selection* is not, so it cannot live
        // under the index's key either.
        assertNotEquals("playlists must not share the index's key", Library.KEY, Playlists.KEY);
        assertNotNull(Playlists.KEY);
    }

    /**
     * A rotation is a user write. Not "similar to" one: the index that comes out
     * is byte-identical to the one a selection produces, so there is nothing for a
     * reader to treat differently and nothing for a second mechanism to have
     * written.
     *
     * <p>Written as an equality against the two index calls rather than as field
     * checks, because the failure being pinned is the <i>extra</i> field a
     * rotation might add — a "played by" flag, a glide source, a timestamp of its
     * own. Each would render correctly and each would be a second thing to keep in
     * step with the selection.
     */
    @Test
    public void rotationWritesExactlyTheSelectionAUserWriteWould() {
        Playlists p = daily("p", "a", "b", "c");
        Library l = lib("a", "b", "c").withSelection("a");

        Playlists.Result r = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));

        assertEquals("b", r.selectedId);
        assertEquals(l.withSelection("b").markUsed("b", DAY).toJson(), r.library.toJson());
        // The playlists half moved too, but only its own clock: the stamp is what
        // stops the next ask inside the interval from advancing again.
        assertEquals(DAY, r.playlists.active().lastAdvancedAt);
        assertEquals("[a, b, c]", ids(r.playlists.active()));
    }

    /**
     * An ordered playlist carries on from whatever the index has selected.
     *
     * <p>This is the test that stands in for the cursor there isn't. Because the
     * position is derived from the selection, picking a photo by hand in the
     * gallery is also how a user moves a playlist along — and there is no second
     * field that could disagree with it. A cursor would have to be written by the
     * same code that writes the selection, and the two would drift the first time
     * a user picked something else.
     */
    @Test
    public void anOrderedPlaylistResumesFromWhateverTheIndexHasSelected() {
        Playlists p = daily("p", "a", "b", "c");
        Library l = lib("a", "b", "c").withSelection("a");

        Playlists.Result first = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));
        assertEquals("b", first.selectedId);

        Playlists.Result second = Playlists.advance(first.playlists, first.library,
                Surface.HOME, 2 * DAY, new Random(7));
        assertEquals("c", second.selectedId);

        Playlists.Result third = Playlists.advance(second.playlists, second.library,
                Surface.HOME, 3 * DAY, new Random(7));
        assertEquals("a", third.selectedId);
    }

    /** No position to resume from, so the list starts again. Predictable beats clever. */
    @Test
    public void anOrderedPlaylistWithTheSelectionOutsideItStartsAtItsFirstPhoto() {
        Playlists p = daily("p", "a", "b");
        Library l = lib("a", "b", "unrelated").withSelection("unrelated");

        assertEquals("a", Playlists.advance(p, l, Surface.HOME, DAY, new Random(7)).selectedId);
    }

    /** The end-to-end property of every rotation, and the one a user would file about. */
    @Test
    public void theSelectionResolvesToAnItemThatExistsAfterEveryRotation() {
        Playlists p = daily("p", "a", "b", "c");
        Library l = lib("a", "b", "c");
        String previous = null;
        for (int i = 1; i <= 12; i++) {
            Playlists.Result r = Playlists.advance(p, l, Surface.HOME, i * DAY, new Random(i));
            assertTrue("rotation " + i + " did nothing", r.rotated());
            assertNotNull("rotation " + i + " selected a photo that is not there",
                    r.library.find(r.selectedId));
            assertNotNull("the wallpaper would render nothing",
                    r.library.activeItem());
            assertNotEquals("the rotation went nowhere", previous, r.selectedId);
            previous = r.selectedId;
            p = r.playlists;
            l = r.library;
        }
    }

    /**
     * A deleted photo must never come back.
     *
     * <p>The trap is that this needs <i>two</i> halves and only one of them is
     * about the selection. If the playlist keeps naming a photo the index has
     * dropped, {@link Playlists#advance} skips it — so nothing crashes and nothing
     * looks wrong at the moment of deletion, and the wallpaper quietly stops
     * including a photo the user threw away. It comes back as a rotation that
     * silently selects an id the index refuses, and the visible result is that the
     * playlist never changes again.
     */
    @Test
    public void aRotationNeverComesBackToADeletedPhoto() {
        Playlists p = daily("p", "a", "b", "c");
        Library l = lib("a", "b", "c");

        Playlists.Result deleted = Playlists.afterRemoving(p, l, "b");
        assertNull("the row must be gone from the index too", deleted.library.find("b"));
        for (Playlist pl : deleted.playlists.playlists()) {
            assertFalse("a playlist still names the deleted photo", pl.contains("b"));
        }

        for (int i = 1; i <= 8; i++) {
            Playlists.Result r = Playlists.advance(deleted.playlists, deleted.library,
                    Surface.HOME, i * DAY, new Random(i));
            if (r.rotated()) {
                assertNotEquals("a deleted photo came back on the wallpaper", "b", r.selectedId);
                assertNotNull(r.library.find(r.selectedId));
            }
            deleted = r;
        }
    }

    /**
     * A hole is stepped over, not fatal.
     *
     * <p>There is a real way to get one without deleting anything: a restore that
     * brought back the blobs but not the files. The alternative — dropping any
     * playlist that names a missing id — would throw away a user's ordering
     * because one photo went missing, which is a far worse answer than skipping
     * it.
     */
    @Test
    public void aPhotoMissingFromTheIndexIsSteppedOverWithoutLosingThePlaylist() {
        Playlists p = daily("p", "a", "gone", "c");
        Library l = lib("a", "c").withSelection("a");

        Playlists.Result first = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));
        assertEquals("c", first.selectedId);
        assertEquals("the playlist must survive the hole",
                1, first.playlists.size());
        assertEquals("[a, gone, c]", ids(first.playlists.active()));

        Playlists.Result second = Playlists.advance(first.playlists, first.library,
                Surface.HOME, 2 * DAY, new Random(7));
        assertEquals("and must still be rotating afterwards", "a", second.selectedId);
    }

    /**
     * Asked twice in one interval, it moves once.
     *
     * <p>Rotation is asked for by more than one thing — an engine on every
     * visibility change, a timer, a "next" button — and if each of those advanced,
     * a daily playlist would burn its interval in seconds and come to look like it
     * was rotating when it was not. The stamp written is the playlist's own clock
     * for exactly this reason.
     */
    @Test
    public void askingTwiceInTheSameIntervalMovesOnce() {
        Playlists p = daily("p", "a", "b", "c");
        Library l = lib("a", "b", "c").withSelection("a");

        Playlists.Result first = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));
        Playlists.Result second = Playlists.advance(first.playlists, first.library,
                Surface.HOME, DAY, new Random(7));

        assertFalse("a second ask in the same interval advanced again", second.rotated());
        assertEquals(first.library.toJson(), second.library.toJson());
        assertEquals(first.playlists.toJson(), second.playlists.toJson());
    }

    /**
     * An event trigger is never due on a clock.
     *
     * <p>The trap is the interval of zero. An event trigger has no interval, and if
     * "is it due" compared a zero interval against an elapsed time it would be true
     * on every single read — so an unlock playlist would advance on a timer, which
     * is the inexact alarm R-PL4 rules out, and the wallpaper would change while
     * the user was reading it.
     */
    @Test
    public void anEventTriggerIsNeverDueOnAClock() {
        for (Trigger event : new Trigger[]{Trigger.ON_UNLOCK, Trigger.ON_PICKUP}) {
            assertFalse(event.isTimed());
            assertEquals(0L, event.intervalMillis());
            Playlist p = playlist("p", "a", "b").withTrigger(event).withActive(true);
            assertFalse(event + " must not be due however long it has been",
                    p.isDue(FAR_FUTURE));

            Playlists.Result r = Playlists.advance(playlistsOf(p), lib("a", "b").withSelection("a"),
                    Surface.HOME, FAR_FUTURE, new Random(7));
            assertFalse(event + " rotated on a timer", r.rotated());
        }
    }

    /**
     * A shuffled playlist must show a different photo each time it fires.
     *
     * <p>Land on the photo already on screen and the wallpaper does not change at
     * all, at a moment the user is specifically waiting to see it change. The
     * single-photo case is the degenerate one and is allowed to repeat: there is
     * nothing else to show, and a rotation that did nothing is still a rotation
     * worth stamping.
     */
    @Test
    public void aShuffleNeverRepeatsThePhotoOnScreenWhenItHasAChoice() {
        Playlists p = playlistsOf(playlist("p", "a", "b", "c").withShuffle(true))
                .withActive("p", true);
        Library l = lib("a", "b", "c").withSelection("a");
        String previous = "a";
        for (int i = 1; i <= 30; i++) {
            Playlists.Result r = Playlists.advance(p, l, Surface.HOME, i * DAY, new Random(i));
            assertTrue("rotation " + i + " did nothing", r.rotated());
            assertNotEquals("a shuffle repeated the photo on screen", previous, r.selectedId);
            previous = r.selectedId;
            p = r.playlists;
            l = r.library;
        }
    }

    /**
     * The bundled row is one item like any other as far as rotation is concerned.
     *
     * <p>Pinned because it is the one id with no file behind it, and because
     * "skip the row with no image" is a plausible-looking special case. It has no
     * image <i>by design</i> and it renders; a playlist may include it, and the
     * index is the only thing that decides whether an id resolves.
     */
    @Test
    public void theBundledDemoCanBeInAPlaylist() {
        Library l = Library.empty().withItem(item("a")).withItem(LibraryItem.bundled())
                .withSelection("a");
        Playlists p = daily("p", "a", Library.BUNDLED_ID);

        Playlists.Result r = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));
        assertEquals(Library.BUNDLED_ID, r.selectedId);
        assertNotNull(r.library.activeItem());
    }

    /** Nothing to show is nothing to do — and must not start a fresh interval. */
    @Test
    public void anActivePlaylistWithNoPhotosRotatesNothing() {
        Playlists p = daily("p");
        Library l = lib("a", "b").withSelection("a");

        Playlists.Result r = Playlists.advance(p, l, Surface.HOME, DAY, new Random(7));
        assertFalse(r.rotated());
        assertEquals("the selection must be left alone", "a", r.library.activeId());
        assertEquals("and the playlist must still be due", 0L, r.playlists.active().lastAdvancedAt);
    }

    // ---------------------------------------------------------------- surfaces

    /**
     * A playlist that names only the lock screen must not rotate the home screen.
     *
     * <p>This is the whole of R-P2 as far as the data model is concerned. The lock
     * engine does not exist below API 35, so the caller simply never asks for the
     * lock surface; the stored intent is untouched. A stored "cannot do lock here"
     * would be a capability rather than an intent, and it would outlive the device
     * that could not do it.
     */
    @Test
    public void aPlaylistForTheLockScreenDoesNotRotateTheHomeScreen() {
        List<Surface> lockOnly = new ArrayList<Surface>();
        lockOnly.add(Surface.LOCK);
        Playlist p = playlist("p", "a", "b").withSurfaces(lockOnly).withActive(true);
        Playlists set = playlistsOf(p);
        Library l = lib("a", "b").withSelection("a");

        assertFalse(Playlists.advance(set, l, Surface.HOME, DAY, new Random(7)).rotated());
        assertTrue(Playlists.advance(set, l, Surface.LOCK, DAY, new Random(7)).rotated());
        assertEquals(lockOnly, p.surfaces);
    }

    /** One playlist can cover both screens, which is how a rotation reaches both. */
    @Test
    public void aPlaylistNamingBothScreensRotatesForEither() {
        List<Surface> both = Arrays.asList(Surface.HOME, Surface.LOCK);
        Playlists set = playlistsOf(playlist("p", "a", "b").withSurfaces(both).withActive(true));
        Library l = lib("a", "b").withSelection("a");

        assertEquals("b", Playlists.advance(set, l, Surface.LOCK, DAY, new Random(7)).selectedId);
        assertEquals("b", Playlists.advance(set, l, Surface.HOME, DAY, new Random(7)).selectedId);
        assertNotNull(Playlists.parse(set.toJson()).active().surfaces);
        assertEquals(2, Playlists.parse(set.toJson()).active().surfaces.size());
    }

    /**
     * An unreadable surface list makes a playlist inert, not a home-screen one.
     *
     * <p>The tempting fallback is HOME, because it is the surface every install
     * has. It is wrong: a blob this build cannot read is not a user asking for the
     * home screen, and defaulting to it would start rotating a wallpaper nobody
     * configured — on a timer, with no way to tell it from a deliberate playlist.
     */
    @Test
    public void anUnreadableSurfaceListIsInertRatherThanAssumedToBeHome() {
        for (String raw : new String[]{
                "{\"version\":1,\"playlists\":[{\"id\":\"p\",\"item_ids\":[\"a\"],"
                        + "\"active\":true,\"surfaces\":[]}]}",
                "{\"version\":1,\"playlists\":[{\"id\":\"p\",\"item_ids\":[\"a\"],"
                        + "\"active\":true,\"surfaces\":[5,null,\"kiosk\"]}]}",
                "{\"version\":1,\"playlists\":[{\"id\":\"p\",\"item_ids\":[\"a\"],"
                        + "\"active\":true}]}"}) {
            Playlists parsed = Playlists.parse(raw);
            assertEquals("dropped the whole playlist for one bad field: " + raw,
                    1, parsed.size());
            assertNotNull("the playlist itself must survive: " + raw, parsed.active());
            assertFalse("must not apply to any surface: " + raw,
                    parsed.active().appliesTo(Surface.HOME)
                            || parsed.active().appliesTo(Surface.LOCK));
            assertNull("a home playlist was invented for: " + raw,
                    parsed.rotating(Surface.HOME));
            assertFalse(Playlists.advance(parsed, lib("a").withSelection("a"),
                    Surface.HOME, DAY, new Random(7)).rotated());
        }
    }

    // --------------------------------------------------------- one at a time

    /**
     * One playlist rotates at a time.
     *
     * <p>Not a preference. The index has one selection and one position in it, so
     * two active playlists would each write that pointer and the second would
     * silently undo the first — a wallpaper that appears to skip a photo every
     * time it advances. Enforced here so no caller can route around it, the same
     * reason the bundled row's delete is refused in the data layer.
     */
    @Test
    public void activatingAPlaylistStandsTheOthersDown() {
        Playlists both = playlistsOf(playlist("one", "a", "b"), playlist("two", "b", "c"));
        Playlists onOne = both.withActive("one", true);
        Playlists onTwo = onOne.withActive("two", true);

        assertEquals("one", onOne.active().id);
        assertEquals("two", onTwo.active().id);
        assertFalse(onTwo.find("one").active);

        // The stood-down playlist must not rotate. "two" is [b, c] and the
        // selection is b, so the only photo it can reach is c.
        Library l = lib("a", "b", "c").withSelection("b");
        assertEquals("the stood-down playlist must not rotate", "c",
                Playlists.advance(onTwo, l, Surface.HOME, DAY, new Random(7)).selectedId);
    }

    /** A corrupt blob naming three active playlists resolves to the first one. */
    @Test
    public void aBlobWithSeveralActivePlaylistsLeavesTheFirstOneRotating() {
        String raw = "{\"version\":1,\"playlists\":["
                + "{\"id\":\"one\",\"item_ids\":[\"a\",\"b\"],\"active\":true,"
                + "\"surfaces\":[\"home\"]},"
                + "{\"id\":\"two\",\"item_ids\":[\"b\",\"c\"],\"active\":true,"
                + "\"surfaces\":[\"home\"]},"
                + "{\"id\":\"three\",\"item_ids\":[\"c\"],\"active\":true,"
                + "\"surfaces\":[\"home\"]}]}";
        Playlists parsed = Playlists.parse(raw);

        assertEquals(3, parsed.size());
        assertEquals("one", parsed.active().id);
        assertFalse(parsed.find("two").active);
        assertFalse(parsed.find("three").active);
    }

    /** Setting the value a toggle already shows must not produce a write. */
    @Test
    public void settingTheCurrentActiveValueChangesNothing() {
        Playlists on = playlistsOf(playlist("p", "a")).withActive("p", true);
        assertEquals(on.toJson(), on.withActive("p", true).toJson());
        assertEquals(on.toJson(), on.withActive("nope", true).toJson());
    }

    // ---------------------------------------------------------------- deleting

    /**
     * A delete removes the row and the id from every playlist, in one derivation.
     *
     * <p>Both halves are here so neither can be persisted without the other. The
     * visible failure of doing only the first is covered above: the playlist keeps
     * naming a photo the index has dropped and the rotation quietly stops
     * changing anything.
     */
    @Test
    public void deletingAPhotoRemovesItFromEveryPlaylistAndTheIndex() {
        Playlists p = playlistsOf(playlist("one", "a", "b"), playlist("two", "a", "c"),
                playlist("three", "b", "c"));
        Library l = lib("a", "b", "c").withSelection("a");

        Playlists.Result r = Playlists.afterRemoving(p, l, "a");

        assertNull(r.library.find("a"));
        assertEquals(2, r.library.size());
        for (Playlist pl : r.playlists.playlists()) {
            assertFalse("a playlist still names the deleted photo", pl.contains("a"));
        }
        assertEquals("[b]", ids(r.playlists.find("one")));
        assertEquals("[c]", ids(r.playlists.find("two")));
        assertEquals("[b, c]", ids(r.playlists.find("three")));
    }

    /**
     * Deleting the photo on screen clears the selection in the same derivation.
     *
     * <p>The index already refuses to point at nothing, so a delete of the selected
     * row lands on the bundled demo — a visible, honest outcome. What must not
     * happen is the selection surviving the delete, which is a wallpaper showing a
     * photo that is gone from the gallery.
     */
    @Test
    public void deletingTheSelectedPhotoClearsTheSelection() {
        Playlists p = daily("p", "a", "b");
        Library l = lib("a", "b").withSelection("a");

        Playlists.Result r = Playlists.afterRemoving(p, l, "a");
        assertNull(r.library.activeId());
        assertEquals(1, r.library.size());
        assertFalse(r.playlists.active().contains("a"));
    }

    /**
     * An emptied playlist keeps its name and its trigger.
     *
     * <p>Deleting the photo is not a request to forget the playlist. Dropping it
     * would lose the user's ordering and every setting they chose, because of the
     * one thing they did not ask to lose — and an empty playlist is inert anyway,
     * so nothing is at risk from keeping it.
     */
    @Test
    public void anEmptiedPlaylistKeepsItsNameAndItsTrigger() {
        Playlists p = playlistsOf(
                playlist("p", "a").withTrigger(Trigger.HOURLY).withShuffle(true))
                .withActive("p", true);

        Playlists r = Playlists.afterRemoving(p, lib("a", "b"), "a").playlists;
        Playlist left = r.find("p");

        assertNotNull("the playlist must not be dropped", left);
        assertTrue(left.isEmpty());
        assertEquals(Trigger.HOURLY, left.trigger);
        assertTrue(left.shuffle);
        assertTrue(left.active);
        assertEquals("p", left.displayName());
    }

    /** The one row that cannot be recreated from disk is not deletable either. */
    @Test
    public void theBundledRowSurvivesADelete() {
        Playlists p = daily("p", "a", Library.BUNDLED_ID);
        Library l = lib("a").withItem(LibraryItem.bundled());

        Playlists.Result r = Playlists.afterRemoving(p, l, Library.BUNDLED_ID);
        assertNotNull(r.library.find(Library.BUNDLED_ID));
        assertTrue(r.playlists.active().contains(Library.BUNDLED_ID));
    }

    /**
     * Editing a photo is not a reason to take it out of a playlist.
     *
     * <p>R-PL5: adjusting Depth while a rotation is running must leave the
     * playlist alone. Pinned because the two blobs are about to be held in one
     * object in the app layer, and "keep the playlists in step with the photos"
     * is the tidiness that would quietly turn an edit into a removal.
     */
    @Test
    public void editingAPhotoLeavesItInThePlaylist() {
        Playlists p = daily("p", "a", "b");
        Library edited = lib("a", "b").withSelection("a")
                .withItem(item("a").withIntensity(Surface.HOME, 12));

        assertTrue(p.active().contains("a"));
        assertEquals(12, edited.find("a").settings(Surface.HOME).depth);
        assertEquals("[a, b]", ids(p.active()));
    }

    // ----------------------------------------------------------- never throws

    /**
     * A corrupt blob yields no playlists and no rotation.
     *
     * <p>The wallpaper parses blobs on the GL thread at cold start, where a throw
     * means no wallpaper at all. And the fallback has to be "nothing rotates":
     * falling back to a default playlist would move a user's home screen by
     * itself on a timer they never chose.
     */
    @Test
    public void aCorruptBlobYieldsNoPlaylistsAndNoRotation() {
        for (String bad : new String[]{
                "", "   ", "{", "not json", "[]", "{\"version\":", "{\"version\":1,\"playlists\":5}",
                "{\"version\":1,\"playlists\":[3,\"x\",null,[]]}",
                "{\"version\":1,\"playlists\":[{\"name\":\"no id\"}]}",
                "{\"version\":1,\"playlists\":[{\"id\":9,\"item_ids\":[\"a\"]}]}",
                "{\"version\":1,\"playlists\":[{\"id\":\"\",\"item_ids\":[\"a\"]}]}",
                "{\"version\":\"one\",\"playlists\":[]}"}) {
            Playlists parsed = Playlists.parse(bad);
            assertNotNull("returned null for: " + bad, parsed);
            assertEquals("bad blob: " + bad, 0, parsed.size());
            assertNull("must not invent a rotation for: " + bad, parsed.active());
            Library l = lib("a", "b").withSelection("a");
            Playlists.Result r = Playlists.advance(parsed, l, Surface.HOME, DAY, new Random(7));
            assertFalse("rotated from a corrupt blob: " + bad, r.rotated());
            assertEquals("moved the selection for: " + bad, l.toJson(), r.library.toJson());
        }
    }

    /** One unreadable playlist must not cost the other four. */
    @Test
    public void unreadablePlaylistsAreDroppedAndTheRestAreKept() {
        Playlists parsed = Playlists.parse("{\"version\":1,\"playlists\":["
                + "{\"id\":\"good\",\"name\":\"Keep\",\"item_ids\":[\"a\",\"b\"],"
                + "\"surfaces\":[\"home\"],\"trigger\":\"hourly\",\"shuffle\":true},"
                + "{\"name\":\"no id\"},"
                + "{\"id\":\"\",\"item_ids\":[\"a\"]},"
                + "{\"id\":\"bad-ids\",\"item_ids\":[7,null,\"\",\"a\"]}"
                + "]}");

        assertEquals(2, parsed.size());
        assertEquals("Keep", parsed.find("good").name);
        assertEquals(Trigger.HOURLY, parsed.find("good").trigger);
        assertTrue(parsed.find("good").shuffle);
        assertEquals("a non-string id is one hole, not the whole list",
                "[a]", ids(parsed.find("bad-ids")));
    }

    /** Guessing at a newer schema would rotate a wallpaper from fields we cannot read. */
    @Test
    public void aFutureVersionYieldsNoPlaylists() {
        Playlists parsed = Playlists.parse("{\"version\":" + (Playlists.VERSION + 1)
                + ",\"playlists\":[{\"id\":\"p\",\"item_ids\":[\"a\"],\"active\":true}]}");
        assertTrue(parsed.isEmpty());
    }

    /**
     * Migration is idempotent.
     *
     * <p>There is only one version so far, so this is the v0 shape — a blob with no
     * version key at all — parsed, written back and parsed again. The upgrade path
     * is not guaranteed to run exactly once: a crash between reading and rewriting
     * leaves it to run again, and a migration that changed something the second
     * time would corrupt a blob on the second run rather than the first.
     */
    @Test
    public void migrationIsIdempotent() {
        String v0 = "{\"playlists\":[{\"id\":\"p\",\"name\":\"Favourites\","
                + "\"item_ids\":[\"a\",\"b\"],\"surfaces\":[\"home\",\"lock\"]}]}";

        Playlists once = Playlists.parse(v0);
        assertEquals(1, once.size());
        assertEquals("Favourites", once.find("p").name);
        assertEquals("[a, b]", ids(once.find("p")));
        assertEquals(2, once.find("p").surfaces.size());
        assertEquals(Trigger.DEFAULT, once.find("p").trigger);
        assertTrue("the re-written blob must declare the version it is now",
                once.toJson().contains("\"version\":" + Playlists.VERSION));

        Playlists twice = Playlists.parse(once.toJson());
        assertEquals(once.toJson(), twice.toJson());
        assertEquals(once.toJson(), Playlists.parse(twice.toJson()).toJson());
    }

    /**
     * A migration must survive being run on its own output.
     *
     * <p>Strictly harder than parsing the old blob twice, and the only form that
     * catches a migration that is not idempotent. Re-parsing the re-serialised
     * form does <i>not</i> test it: that blob declares the current version, so the
     * migration is skipped and nothing runs twice. What does happen in the field is
     * the version bump failing to reach storage — a crash between the read and the
     * rewrite — so the next launch migrates content that is already migrated, and a
     * migration that appends, reorders or increments produces a different blob each
     * time. Pinned by feeding it a migrated blob relabelled as the old version.
     */
    @Test
    public void aMigrationSurvivesBeingRunOnItsOwnOutput() {
        String v0 = "{\"playlists\":[{\"id\":\"p\",\"name\":\"Favourites\","
                + "\"item_ids\":[\"a\",\"b\"],\"surfaces\":[\"home\"]}]}";
        String migrated = Playlists.parse(v0).toJson();
        String looksLikeV0 = migrated.replace("\"version\":" + Playlists.VERSION,
                "\"version\":0");

        assertEquals(migrated, Playlists.parse(looksLikeV0).toJson());
        assertEquals("Favourites", Playlists.parse(looksLikeV0).find("p").name);
        assertEquals("[a, b]", ids(Playlists.parse(looksLikeV0).find("p")));
    }

    // ----------------------------------------------------------------- values

    @Test
    public void everyTriggerRoundTripsThroughItsStoredForm() {
        for (Trigger t : Trigger.values()) {
            assertEquals(t, Trigger.parse(t.key()));
        }
    }

    /** A rename must not change what the last build wrote. See SortOrder. */
    @Test
    public void theStoredTriggerFormIsNotTheConstantName() {
        for (Trigger t : Trigger.values()) {
            assertTrue("stored form should be stable lowercase, not " + t.name(),
                    t.key().equals(t.key().toLowerCase()));
            assertNotNull(t.label());
            assertTrue(t.label().length() > 0);
        }
    }

    /**
     * An unknown trigger falls back to the slowest one.
     *
     * <p>Slowest on purpose: a preference this build cannot read should cost the
     * user a rhythm, never surprise them with a wallpaper that changes more often
     * than they asked for.
     */
    @Test
    public void anUnknownStoredTriggerFallsBackToTheSlowestOne() {
        for (String bad : new String[]{null, "", "   ", "ON_UNLOCK", "onUnlock", "weekly", "42"}) {
            assertEquals("bad value: " + bad, Trigger.DAILY, Trigger.parse(bad));
        }
    }

    /**
     * onPickup survives a round trip though no sensor is wired to it.
     *
     * <p>Deferring the pickup detection means not registering a sensor, not
     * pretending the value does not exist. Dropping the constant would turn a
     * stored {@code on_pickup} into DAILY, and the user's wallpaper would start
     * changing on a clock — the "looks like a bug" outcome R-PL3 exists to prevent.
     */
    @Test
    public void onPickupSurvivesARoundTripThoughNoSensorIsWired() {
        Playlists written = playlistsOf(
                playlist("p", "a", "b").withTrigger(Trigger.ON_PICKUP));
        Playlist back = Playlists.parse(written.toJson()).find("p");

        assertEquals(Trigger.ON_PICKUP, back.trigger);
        assertFalse(back.trigger.isTimed());
        assertTrue(written.toJson().contains("\"on_pickup\""));
        assertFalse(written.toJson().contains("onPickup"));
    }

    /**
     * Order is the user's, so it is kept; a repeat is dropped because it would
     * show one photo twice running and make R-PL3's "3 of 6" a lie that is
     * permanently on screen.
     */
    @Test
    public void itemIdsKeepTheirOrderAndDropRepeatsAndJunk() {
        Playlist p = playlist("p", "c", "a", "b")
                .withItemIds(Arrays.asList("c", "a", "a", null, "", "  ", "b"));
        assertEquals("[c, a, b]", ids(p));

        Playlist added = playlist("p", "a").including("b").including("a").including("nope/../x");
        assertEquals("[a, b]", ids(added));
        assertEquals("adding what is there changes nothing",
                ids(added), ids(added.including("b")));
    }

    // ------------------------------------------------------------- reordering

    /**
     * A reorder moves one photo and leaves every other one in order.
     *
     * <p>This is the editor's up/down control as the model sees it. The UI moves
     * by a button press rather than a drag -- see {@code PlaylistScreen.photoList}
     * for why -- and one press is exactly one call here.
     */
    @Test
    public void reorderingMovesAPhotoByWholePlaces() {
        Playlist p = playlist("p", "a", "b", "c", "d");
        assertEquals("[b, a, c, d]", ids(p.moved("a", 1)));
        assertEquals("[a, c, b, d]", ids(p.moved("b", 1)));
        assertEquals("[a, c, d, b]", ids(p.moved("b", 2)));
        assertEquals("[d, a, b, c]", ids(p.moved("d", -3)));
        assertEquals("[a, d, b, c]", ids(p.moved("d", -2)));
    }

    /**
     * A move past either end changes nothing, and a no-op is not a copy.
     *
     * <p>Clamping rather than wrapping is the honest answer for a button at an
     * edge: the first photo has no "up". Returning {@code this} means the
     * editor's store can skip the write outright, not merely write an equal blob.
     */
    @Test
    public void aMovePastEitherEndChangesNothing() {
        Playlist p = playlist("p", "a", "b", "c");
        assertEquals(ids(p), ids(p.moved("a", -1)));
        assertEquals(ids(p), ids(p.moved("a", -50)));
        assertEquals(ids(p), ids(p.moved("c", 1)));
        assertEquals(ids(p), ids(p.moved("c", 50)));
        assertSame("a zero move must not mint an equal copy", p, p.moved("b", 0));
    }

    /** An absent photo has no place to move from, and a null id is not a crash. */
    @Test
    public void movingAPhotoThatIsNotInTheListChangesNothing() {
        Playlist p = playlist("p", "a", "b");
        assertEquals(ids(p), ids(p.moved("z", 1)));
        assertEquals(ids(p), ids(p.moved(null, 1)));
        assertEquals(ids(p), ids(p.moved("", -1)));
    }

    /**
     * A reorder carries every setting, exactly like the other photo edits.
     *
     * <p>Reorder is an itemIds edit and nothing else: the name, the trigger, the
     * surfaces, shuffle, the active flag and the playlist's own clock all have to
     * survive it, or moving a photo quietly changes when the wallpaper moves.
     * The selection pointer is out of reach structurally -- {@link Playlist} has
     * no field for it -- and this is the assertion that keeps the edit from
     * acquiring one.
     */
    @Test
    public void aReorderCarriesEverySetting() {
        Playlist full = playlist("p", "a", "b", "c")
                .withName("Favourites")
                .withTrigger(Trigger.HOURLY)
                .withShuffle(true)
                .withSurfaces(Arrays.asList(Surface.HOME, Surface.LOCK))
                .withActive(true)
                .advancedAt(4242L);

        Playlist moved = full.moved("b", -1);
        assertEquals("[b, a, c]", ids(moved));
        assertOnlyChanges(full, moved, "moved", "itemIds");
    }

    /**
     * A hole is moved, not filled.
     *
     * <p>A playlist can name a photo the gallery no longer holds. Reordering
     * rearranges ids that are already in the list and cannot add one, so a
     * deleted photo stays deleted and the photo count stays honest. The same
     * property has to hold for a hole the delete left behind, which is the path
     * that creates it.
     */
    @Test
    public void aReorderCannotResurrectADeletedPhoto() {
        Playlist withHole = playlistsOf(playlist("p", "a", "b", "c"))
                .withoutItem("b").find("p");
        assertEquals("[a, c]", ids(withHole));

        Playlist moved = withHole.moved("c", -1);
        assertEquals("[c, a]", ids(moved));
        assertFalse("the deleted id must not come back", moved.contains("b"));
        assertEquals(2, moved.size());
    }

    /** A blank name is no name, and there is always something to show in a list. */
    @Test
    public void aBlankNameBecomesNoNameAndDisplaysAsPlaylist() {
        Playlist blank = playlist("p", "a").withName("   ");
        assertNull(blank.name);
        assertEquals("Playlist", blank.displayName());
        assertEquals("Favourites", playlist("p", "a").withName(" Favourites ").displayName());
    }

    /**
     * One edit must not reset the fields it does not name.
     *
     * <p>The hazard {@code LibraryItem.copy} exists for, inherited: a value copied
     * out of an object into a constructor argument is a value that gets left behind
     * the next time a field is added. Every mutator here goes through one private
     * copy that takes all of them, and this is the test that notices when one is
     * added to a mutator's list and not to another.
     */
    @Test
    public void aSingleFieldEditDoesNotResetTheOthers() {
        List<Surface> both = Arrays.asList(Surface.HOME, Surface.LOCK);
        Playlist full = playlist("p", "a", "b", "c")
                .withName("Favourites")
                .withTrigger(Trigger.HOURLY)
                .withShuffle(true)
                .withSurfaces(both)
                .withActive(true)
                .advancedAt(4242L);

        assertOnlyChanges(full, full.withName("Renamed"), "withName", "name");
        assertOnlyChanges(full, full.withTrigger(Trigger.ON_UNLOCK), "withTrigger", "trigger");
        assertOnlyChanges(full, full.withShuffle(false), "withShuffle", "shuffle");
        assertOnlyChanges(full, full.withActive(false), "withActive", "active");
        assertOnlyChanges(full, full.withSurfaces(Arrays.asList(Surface.LOCK)),
                "withSurfaces", "surfaces");
        assertOnlyChanges(full, full.advancedAt(9L), "advancedAt", "lastAdvancedAt");

        // Editing the photos must carry the settings, and re-stamping the clock must
        // carry the photos. Both halves, because the same copy call does both.
        assertOnlyChanges(full, full.withItemIds(Arrays.asList("a", "d")),
                "withItemIds", "itemIds");
        assertOnlyChanges(full, full.excluding("b"), "excluding", "itemIds");
        assertOnlyChanges(full, full.including("d"), "including", "itemIds");

        Playlist homeOnly = playlist("q", "a", "b").withName("One")
                .withTrigger(Trigger.HOURLY).advancedAt(7L);
        Playlist widened = homeOnly.alsoOn(Surface.LOCK);
        assertEquals(2, widened.surfaces.size());
        assertEquals(homeOnly.itemIds, widened.itemIds);
        assertEquals(homeOnly.name, widened.name);
        assertEquals(homeOnly.trigger, widened.trigger);
        assertEquals(homeOnly.lastAdvancedAt, widened.lastAdvancedAt);
    }

    /**
     * Every field except the one the mutator names.
     *
     * <p>Named rather than looped, so the failure says which mutator dropped
     * which value — and so that adding a field to {@link Playlist} without adding
     * it to a mutator shows up as a compile-adjacent gap here rather than as a
     * field nobody remembers to copy.
     */
    private static void assertOnlyChanges(Playlist full, Playlist after, String which,
                                          String field) {
        if (!"name".equals(field)) assertEquals(which + ": name", full.name, after.name);
        if (!"trigger".equals(field)) {
            assertEquals(which + ": trigger", full.trigger, after.trigger);
        }
        if (!"surfaces".equals(field)) {
            assertEquals(which + ": surfaces", full.surfaces, after.surfaces);
        }
        if (!"shuffle".equals(field)) assertEquals(which + ": shuffle", full.shuffle, after.shuffle);
        if (!"active".equals(field)) assertEquals(which + ": active", full.active, after.active);
        if (!"lastAdvancedAt".equals(field)) {
            assertEquals(which + ": lastAdvancedAt", full.lastAdvancedAt, after.lastAdvancedAt);
        }
        if (!"itemIds".equals(field)) assertEquals(which + ": itemIds", full.itemIds, after.itemIds);
        // And the one it does name really did change, or the test is vacuous.
        assertNotEquals(which + ": nothing changed at all", full.toString(), after.toString());
    }

    /**
     * A clock that has gone backwards is not due.
     *
     * <p>The naive comparison makes the elapsed time hugely positive after a clock
     * change or a restored backup, and the wallpaper advances at the moment the
     * user fixes the time — with a playlist that has not changed in weeks suddenly
     * firing.
     */
    @Test
    public void aClockThatWentBackwardsIsNotDue() {
        Playlist p = playlist("p", "a").withTrigger(Trigger.DAILY).advancedAt(10 * DAY);
        assertTrue(p.isDue(11 * DAY));
        assertFalse(p.isDue(9 * DAY));
        assertFalse(p.isDue(10 * DAY));
    }

    /** Never advanced is longer than any interval, so the first advance is due now. */
    @Test
    public void aPlaylistThatHasNeverRotatedIsDue() {
        assertTrue(playlist("p", "a").isDue(DAY));
        assertTrue(playlist("p", "a").withTrigger(Trigger.HOURLY).isDue(HOUR));
        assertEquals(0L, playlist("p", "a").lastAdvancedAt);
    }

    /** The playlist clock is the playlist's own, and a user's pick does not move it. */
    @Test
    public void aUserSelectingAPhotoDoesNotMoveAPlaylistsClock() {
        // The cheaper field to use here would be the selected item's lastUsedAt, and
        // it is wrong twice: a hand-picked selection is not a rotation, and when the
        // selection is not one of this playlist's photos its stamp belongs to an
        // unrelated photograph.
        Playlists p = playlistsOf(playlist("p", "a", "b")
                .withTrigger(Trigger.DAILY).advancedAt(DAY)).withActive("p", true);
        Library picked = lib("a", "b").withSelection("a").markUsed("a", DAY + HOUR);

        assertEquals(DAY + HOUR, picked.find("a").lastUsedAt);
        assertEquals("a user's pick must not be a rotation", DAY,
                p.active().lastAdvancedAt);
        assertFalse("the day must not have restarted because a photo was picked",
                Playlists.advance(p, picked, Surface.HOME, DAY + HOUR, new Random(7)).rotated());
    }

    /** The bundled row is a gallery row, so a playlist may name it. */
    @Test
    public void aPlaylistRoundTripsThroughStorage() {
        Playlists written = playlistsOf(
                playlist("p", "a", "b", "c").withTrigger(Trigger.HOURLY).withShuffle(true)
                        .alsoOn(Surface.LOCK).advancedAt(1234L),
                playlist("q", "d"));
        written = written.withActive("p", true);

        Playlists back = Playlists.parse(written.toJson());
        assertEquals(2, back.size());
        assertEquals("[a, b, c]", ids(back.find("p")));
        assertEquals("q", back.find("q").name);
        assertEquals(Trigger.HOURLY, back.find("p").trigger);
        assertTrue(back.find("p").shuffle);
        assertEquals(2, back.find("p").surfaces.size());
        assertEquals(1234L, back.find("p").lastAdvancedAt);
        assertEquals("p", back.active().id);
        assertNotNull(back.rotating(Surface.LOCK));
        assertNotNull(back.rotating(Surface.HOME));
        assertNull("a playlist that is not active must not be found rotating",
                playlistsOf(playlist("q", "d")).rotating(Surface.HOME));
    }

    /** An empty set is what every install starts with, and it must stay that way. */
    @Test
    public void anEmptyBlobRoundTrips() {
        Playlists back = Playlists.parse(Playlists.empty().toJson());
        assertTrue(back.isEmpty());
        assertNull(back.active());
        assertNull(back.rotating(Surface.HOME));
        assertTrue(back.toJson().contains("\"version\":" + Playlists.VERSION));
    }

    /** Replacing a playlist keeps its row, so a rename does not move it. */
    @Test
    public void replacingAPlaylistKeepsItsPosition() {
        Playlists p = playlistsOf(playlist("one", "a"), playlist("two", "b"),
                playlist("three", "c"));
        Playlists renamed = p.withPlaylist(p.find("two").withName("Two"));

        assertEquals(3, renamed.size());
        assertEquals("one", renamed.playlists().get(0).id);
        assertEquals("two", renamed.playlists().get(1).id);
        assertEquals("Two", renamed.find("two").name);
        assertEquals(2, p.without("two").size());
        assertEquals(p.toJson(), p.without("nope").toJson());
    }
}