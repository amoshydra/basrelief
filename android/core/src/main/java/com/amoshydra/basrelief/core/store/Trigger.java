package com.amoshydra.basrelief.core.store;

/**
 * What makes a playlist move on: an elapsed length of time, or an event.
 *
 * <p>The split between the two families is the whole content of this enum,
 * because only one of them can be answered by asking. A timed trigger is due
 * when a clock says so. An event trigger is due when something <i>happened</i> —
 * the screen was unlocked, the phone was picked up — and the only way to learn
 * that is to be told, because polling for it is exactly the inexact alarm R-PL4
 * rules out ("rotation advances on visibility change or a timer, never a poll").
 * {@link #isTimed()} is that difference, and {@link #intervalMillis()} is 0 for
 * an event so the "is this playlist due" question has one answer to give rather
 * than two that have to be kept in step.
 *
 * <p><b>Snake case, not the camel case the design document writes.</b> The PRD
 * spells these {@code onUnlock} and {@code onPickup}; every stored key in this
 * app is snake case ({@code added_at}, {@code last_used_at}, {@code origin_x}),
 * and a stored form that is derived from {@link #name()} is a stored form a
 * rename silently invalidates. That is the lesson {@link SortOrder} exists to
 * enforce, and it is why {@link #key()} is a field written out by hand.
 *
 * <p><b>{@link #ON_PICKUP} exists with no sensor behind it.</b> Pickup detection
 * rides the accelerometer the app already runs while parked, and that wiring has
 * not landed. The <i>value</i> has to exist regardless: a stored trigger has to
 * round-trip whatever it says, and dropping the constant would quietly turn a
 * stored {@code on_pickup} into {@link #DAILY} — a wallpaper that begins
 * changing on a clock, which is the "must never look like a bug" outcome R-PL3
 * exists to prevent. What is missing is the caller, not the value.
 */
public enum Trigger {

    /**
     * Once every 24 hours of elapsed time.
     *
     * <p>Elapsed, not "the next midnight": a calendar day needs a time zone and a
     * clock, neither of which this value can see, and a wallpaper that changes
     * when the user's date line says so is a surprise rather than a rhythm.
     */
    DAILY("daily", 24L * 60L * 60L * 1000L),

    /** Once every hour of elapsed time. */
    HOURLY("hourly", 60L * 60L * 1000L),

    /**
     * When the screen is unlocked.
     *
     * <p>An event, so {@link #isTimed()} is false and no timer may fire it: the
     * caller that hears the unlock is what advances it. That caller is
     * {@code DepthWallpaperService}, which registers {@code ACTION_USER_PRESENT}
     * while the wallpaper is applied and hands the event to
     * {@link Playlists#advance}.
     */
    ON_UNLOCK("on_unlock", 0L),

    /**
     * When the phone is picked up off a surface. See the class note on why the
     * constant is here before its sensor is.
     */
    ON_PICKUP("on_pickup", 0L);

    /** The trigger a playlist gets when nobody chose one. */
    public static final Trigger DEFAULT = DAILY;

    /** The stored form. Written out, never {@link #name()}. */
    private final String key;

    /** How long to wait, or 0 when only an event can fire this. */
    private final long intervalMillis;

    Trigger(String key, long intervalMillis) {
        this.key = key;
        this.intervalMillis = intervalMillis;
    }

    /** The stored form, in JSON. See {@link SortOrder} for why it is not the name. */
    public String key() {
        return key;
    }

    /**
     * Whether a clock can decide this trigger is due. False for the events.
     *
     * <p>This is the method a caller uses to decide <i>whether to set a timer</i>
     * at all, which is why it is not folded into {@link #intervalMillis()} alone:
     * "wait zero milliseconds" and "nothing is waiting" are different answers and
     * only one of them means a poll.
     */
    public boolean isTimed() {
        return intervalMillis > 0L;
    }

    /**
     * How long between advances, in milliseconds; 0 for an event trigger.
     *
     * <p>0 means "do not ask a clock", so it must never be compared against an
     * elapsed time as though it were a very short interval.
     */
    public long intervalMillis() {
        return intervalMillis;
    }

    /**
     * Read a stored trigger, falling back to {@link #DEFAULT} for anything this
     * build does not recognise. Never throws.
     *
     * <p>Safe to default for the same reason {@link SortOrder#parse} is: a
     * trigger is a preference, so guessing costs the user a rhythm and nothing
     * else — and the guess is the slowest trigger, not a rotation on every read.
     */
    public static Trigger parse(String raw) {
        if (raw != null) {
            for (Trigger t : values()) {
                if (t.key.equals(raw)) return t;
            }
        }
        return DEFAULT;
    }

    /** The stored form, for a one-line summary or an editor row. */
    public String label() {
        switch (this) {
            case HOURLY: return "Hourly";
            case ON_UNLOCK: return "On unlock";
            case ON_PICKUP: return "On pickup";
            default: return "Daily";
        }
    }
}