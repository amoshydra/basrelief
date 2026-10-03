package com.amoshydra.basrelief.core.store;

import java.util.List;

/**
 * What a playlist is doing right now: which one rotates, on which screens, and
 * when it next changes.
 *
 * <p><b>Derived at render time, never stored.</b> Every fact here already lives
 * on a {@link Playlist} or on the index, and a written-down copy is the second
 * copy that can disagree — the failure this codebase keeps paying for. It is
 * also a hard constraint: {@code PlaylistTest} enumerates the blob's allowed
 * keys and asserts byte-equality of a rotation, so a stored status would break
 * the format's own test. The answer is cheap, so it is re-read whenever a
 * screen is drawn.
 *
 * <p><b>One function decides what is rotating, and this class is its only
 * caller here.</b> {@link Playlists#rotating(Surface)} gates the active
 * playlist by the screen being asked about, and every method below is built
 * from it. A surface is a gate rather than a hint (see {@link Playlists}), so
 * a playlist whose stored screens are unreadable rotates nowhere, and this
 * says so rather than pretending it is running.
 *
 * <p><b>When it next changes is a clock, and only a clock.</b> A timed trigger
 * answers with the moment its interval elapses after {@code lastAdvancedAt};
 * an event trigger answers with no moment at all ({@code nextChangeAt == 0})
 * because "when you unlock" is not a duration and inventing one would print a
 * countdown that never fires. {@code dueNow} delegates to
 * {@link Playlist#isDue(long)} rather than re-deriving the elapsed comparison,
 * so the status and the rotation itself cannot disagree about whether the
 * timer has run out.
 *
 * <p>Lives in :core, like {@link Framing} and {@link SurfaceResolver}, because
 * it is the one resolution of state into numbers and is therefore testable
 * without a device.
 */
public final class PlaylistStatus {

    /** The one playlist that rotates, or null when none does. */
    public final Playlist playlist;

    /** The screens it is declared for, in the playlist's own order. Empty for none. */
    public final List<Surface> surfaces;

    /**
     * True when a timed trigger's interval has elapsed and a rotation is due —
     * including the first one, because {@code lastAdvancedAt == 0} means never
     * and "never" is longer than any interval.
     */
    public final boolean dueNow;

    /**
     * Epoch ms when the next timed rotation becomes due, or 0 when nothing
     * rotates or the trigger is an event. See the class note: an event has no
     * moment to count down to.
     */
    public final long nextChangeAt;

    /**
     * The position of the index's selected photo in the playlist, 1-based, or 0
     * when it is not in the list. A rotation can legitimately be running while
     * the photo on screen came from somewhere else, and a position printed then
     * would be false.
     */
    public final int position;

    /** The playlist's length, so a "3 of 6" needs no second read of the model. */
    public final int size;

    /** The one value that means nothing rotates. */
    private static final PlaylistStatus NONE =
            new PlaylistStatus(null, null, false, 0L, 0, 0);

    private PlaylistStatus(Playlist playlist, List<Surface> surfaces, boolean dueNow,
                           long nextChangeAt, int position, int size) {
        this.playlist = playlist;
        this.surfaces = surfaces;
        this.dueNow = dueNow;
        this.nextChangeAt = nextChangeAt;
        this.position = position;
        this.size = size;
    }

    /** Whether any playlist rotates on any screen. */
    public boolean isRotating() {
        return playlist != null;
    }

    /** Whether the rotating playlist covers this screen; false when none rotates. */
    public boolean appliesTo(Surface surface) {
        return playlist != null && playlist.appliesTo(surface);
    }

    /**
     * The status of whatever rotates, read once.
     *
     * <p>Home is asked first, then lock. With at most one active playlist (see
     * {@link Playlists}) the order cannot hide a second one; it only decides
     * which surface finds it first when the playlist covers both, and the
     * answer is the same playlist either way.
     *
     * @param lists the playlists, or null for none
     * @param index the gallery index, for the position; null is no position
     * @param now   epoch ms, passed in rather than read from the clock so a
     *              status can be tested and replayed
     */
    public static PlaylistStatus of(Playlists lists, Library index, long now) {
        Playlists from = lists == null ? Playlists.empty() : lists;
        Playlist p = from.rotating(Surface.HOME);
        if (p == null) p = from.rotating(Surface.LOCK);
        if (p == null) return NONE;

        boolean timed = p.trigger.isTimed();
        return new PlaylistStatus(
                p,
                p.surfaces,
                p.isDue(now),
                timed ? p.lastAdvancedAt + p.trigger.intervalMillis() : 0L,
                index == null ? 0 : p.indexOf(index.activeId()) + 1,
                p.size());
    }

    /**
     * How many playlists rotate on any screen.
     *
     * <p>Counted over the screens rather than over {@code active()} so it reads
     * through the same gate as everything else: a playlist the surfaces could
     * not be read from is not running anywhere and must not be counted. Today
     * the model holds at most one rotator, so the answer is 0 or 1; counting
     * both screens keeps the number honest if that ever loosens, instead of
     * hard-coding a fact that could change.
     */
    public static int rotatingCount(Playlists lists) {
        Playlists from = lists == null ? Playlists.empty() : lists;
        Playlist home = from.rotating(Surface.HOME);
        Playlist lock = from.rotating(Surface.LOCK);
        if (home == null) return lock == null ? 0 : 1;
        return (lock == null || lock == home) ? 1 : 2;
    }
}
