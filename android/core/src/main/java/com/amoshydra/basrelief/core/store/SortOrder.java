package com.amoshydra.basrelief.core.store;

/**
 * The orders a library can be listed in.
 *
 * <p>Deliberately three. Each answers a different question, and they do not
 * overlap:
 *
 * <ul>
 *   <li>{@link #ADDED_DESC} — "what did I just put here?" The default, and the
 *       order a new import should land in, because the item you added is the one
 *       you are looking for.
 *   <li>{@link #NAME_ASC} — "where is the one I called Sunset?" Only useful once
 *       there are enough items that scrolling to find one is annoying.
 *   <li>{@link #RECENTLY_USED} — "what have I been looking at?" Items never set
 *       as the wallpaper sink to the bottom.
 * </ul>
 *
 * <p>Two candidates were considered and left out on purpose:
 *
 * <ul>
 *   <li><b>Oldest first</b> is the same axis as {@link #ADDED_DESC}, and a
 *       gallery of one person's photos is rarely a chronology to read. Adding it
 *       later is one enum constant, so refusing it now costs nothing.
 *   <li><b>Has-depth first</b> is a filter wearing a sort's clothes. Every item
 *       can be flat, and promoting the ones with depth is a statement about
 *       depth generation ("go make one"), which is a different feature. If it
 *       ever ships, it is a toggle over a fixed order, not a third comparator.
 * </ul>
 *
 * <p>Sorting is <b>read order only</b>: {@link Library#sorted()} returns a new
 * list and never touches the stored one, so no comparison bug can reorder the
 * blob or change what a restart shows.
 */
public enum SortOrder {

    /** Newest first. Ties fall to the most recently added — see Library.sorted. */
    ADDED_DESC("added_desc"),

    /** Case-insensitive by display name, falling back to the filename. */
    NAME_ASC("name_asc"),

    /** Most recently set as the wallpaper first. Never-used items last. */
    RECENTLY_USED("recently_used");

    /** The order used when nothing has been chosen, and for anything unreadable. */
    public static final SortOrder DEFAULT = ADDED_DESC;

    /**
     * The stored form. A fixed string per constant rather than {@link #name()},
     * so renaming a constant cannot silently change what the last build wrote
     * and reset everyone's order — the same lesson {@link Config} exists to
     * enforce about stored values.
     */
    private final String key;

    SortOrder(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The stored form, for a one-line summary or a log line. */
    public String label() {
        switch (this) {
            case NAME_ASC: return "Name";
            case RECENTLY_USED: return "Recent";
            default: return "Newest";
        }
    }

    /**
     * Read a stored order, falling back to {@link #DEFAULT} for anything this
     * build does not recognise. Never throws.
     *
     * <p>Unlike {@link Config#parse} this is safe to default: a sort is a
     * preference, so guessing costs the user a list order and nothing else.
     */
    public static SortOrder parse(String raw) {
        if (raw != null) {
            for (SortOrder o : values()) {
                if (o.key.equals(raw)) return o;
            }
        }
        return DEFAULT;
    }
}
