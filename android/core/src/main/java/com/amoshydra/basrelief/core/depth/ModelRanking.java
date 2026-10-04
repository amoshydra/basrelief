package com.amoshydra.basrelief.core.depth;

import java.util.ArrayList;
import java.util.List;

/**
 * The depth model chooser's ordering: the models the user picked most recently
 * come first, then everything the server offers in discovery order.
 *
 * <p><b>Why this exists.</b> The chooser used to prefer MoGe by a hard-coded
 * rule in the dialog — the first {@code isMoGeCheckpoint} entry. That rule took
 * the <em>first</em> MoGe entry, so which checkpoint was selected by default was
 * decided by the order {@code /object_info} happened to list them in: a server
 * listing {@code moge_2…} before {@code moge_3…} selected MoGe 2 while MoGe 3 sat
 * unselected. The user's own use is the preference now. Nothing here names a
 * model, parses a version, or ranks one checkpoint above another, so MoGe 4 (and
 * any non-MoGe model) works without a code change.
 *
 * <p><b>Two pure functions and the prefs format.</b> {@link #rank} orders a
 * discovered list by a history; {@link #remember} is the history after one
 * selection; {@link #encode}/{@link #decode} are the prefs string. All four are
 * plain {@code String} list arithmetic so the dialog keeps only the prefs
 * read/write and the row building, and all four are unit-testable.
 *
 * <p><b>The ordering is a permutation.</b> {@link #rank} never adds a model that
 * was not discovered and never drops one that was: a history entry the server no
 * longer offers is skipped, and the rest keep their relative order. That is what
 * lets a photo keep rendering when a checkpoint is deleted server-side, and it
 * is why the caller's existing rule — use the saved model when it is present,
 * else the first entry — does the right thing unchanged once the history decides
 * what is first.
 */
public final class ModelRanking {

    private ModelRanking() {
    }

    /**
     * How many recent selections are remembered.
     *
     * <p>Four is enough to keep the usual rotation of checkpoints within one tap
     * of the top, and short enough that the tail of the list is still discovery
     * order rather than a second history. The number is a judgement, not a
     * platform fact, so it is one constant.
     */
    public static final int HISTORY_CAP = 4;

    /**
     * {@code models} reordered so the {@code history} entries come first, most
     * recent first, and everything else keeps the order it arrived in.
     *
     * <p>A history entry absent from {@code models} is skipped rather than
     * inserted, so the return value is always a permutation of the input: every
     * entry came from {@code models}, and none is lost. Duplicates in either
     * input collapse, so a repeated row cannot be built.
     *
     * @param models  the discovered list, in discovery order
     * @param history recent selections, most recent first; may be null or empty,
     *                which leaves {@code models} untouched
     */
    public static List<String> rank(List<String> models, List<String> history) {
        List<String> out = new ArrayList<String>();
        if (models == null) return out;
        if (history != null) {
            for (String recent : history) {
                if (recent == null || out.contains(recent)) continue;
                if (models.contains(recent)) out.add(recent);
            }
        }
        for (String model : models) {
            if (model != null && !out.contains(model)) out.add(model);
        }
        return out;
    }

    /**
     * The history after the user selects {@code model}: it moves to the front,
     * any earlier occurrence is removed, and the list is capped.
     *
     * <p>Called on the selection itself rather than on a finished run, because
     * choosing is the signal — a user who tries a checkpoint and backs out has
     * still told the app which one they reach for.
     *
     * @param history the history before the selection; may be null
     * @param model   the selected model; null or empty is ignored
     * @return a new history no longer than {@link #HISTORY_CAP}, with no
     *         duplicates and {@code model} first
     */
    public static List<String> remember(List<String> history, String model) {
        List<String> out = new ArrayList<String>();
        if (model != null && model.length() > 0) out.add(model);
        if (history != null) {
            for (String h : history) {
                if (h == null || h.length() == 0 || out.contains(h)) continue;
                if (out.size() >= HISTORY_CAP) break;
                out.add(h);
            }
        }
        return out;
    }

    /**
     * The history as one prefs string: model names separated by newlines.
     *
     * <p>Newlines rather than JSON: {@code :core} has zero runtime dependencies,
     * so its {@code org.json} is a test-only type, and a checkpoint filename
     * cannot contain a newline. An empty history encodes to the empty string, so
     * "no history yet" and "history was cleared" are the same stored value.
     */
    public static String encode(List<String> history) {
        StringBuilder b = new StringBuilder();
        if (history == null) return "";
        for (String h : history) {
            if (h == null || h.length() == 0 || h.indexOf('\n') >= 0) continue;
            if (b.length() > 0) b.append('\n');
            b.append(h);
        }
        return b.toString();
    }

    /**
     * Parse a stored history. Never throws — the dialog reads this while opening
     * a panel — and a null, empty or corrupt value is simply no history.
     */
    public static List<String> decode(String stored) {
        List<String> out = new ArrayList<String>();
        if (stored == null || stored.length() == 0) return out;
        for (String part : stored.split("\n")) {
            if (part.length() == 0 || out.contains(part)) continue;
            if (out.size() >= HISTORY_CAP) break;
            out.add(part);
        }
        return out;
    }
}
