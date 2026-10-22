"""The rules the app and this node must implement identically, kept dependency-free.

Nothing here imports torch, numpy, ComfyUI or folder_paths, so it can be tested in
a bare `python:3.12-slim` container against the SAME fixture bytes the Java app
reads. `nodes.py` imports these; there is no second copy.

The fixtures live at the repository root, in `contract/`. That directory's README
says which implementations each one pins.
"""

import os
import string
import time

# --------------------------------------------------------------- the tag rule

#: The ONLY characters a tag may contain. ASCII on purpose -- see `safe_tag`.
TAG_CHARS = frozenset(string.ascii_letters + string.digits + "-_")

#: Kept characters, inclusive. ASCII means chars == code points == UTF-16 units
#: == bytes, so this single number needs no unit of its own.
MAX_TAG_CHARS = 48


def clean_tag(tag):
    """The rule itself: keep ``[A-Za-z0-9_-]``, then the first 48 of them.

    This is the half the app implements (``ComfyWorkflow.streamTag``) and the half
    ``contract/tag/cases.json`` pins. It has no fallback, because a fallback is the
    node's private policy: an empty tag means "nothing survived", which the app
    refuses to submit and the node replaces with a name only a human can follow.
    """
    return "".join(c for c in (tag or "") if c in TAG_CHARS)[:MAX_TAG_CHARS]


def safe_tag(tag, fallback=None):
    """``clean_tag`` plus the node's private fallback for an empty answer.

    Both sides derive every output filename from this string --
    ``basrelief_<tag>_depth.mp4``, ``_normal.mp4``, ``_color.mp4``,
    ``.progress.json``, ``.splat``, ``.splat.json`` -- and the app addresses files
    it was never told about, so a one-character disagreement is a fetch for a file
    that does not exist. The fixture is ``contract/tag/cases.json``.

    **ASCII, not Unicode.** The rule used to be Python's ``str.isalnum()``, which
    accepts Unicode letters AND the N* categories (Roman numerals, superscripts),
    and was hand-ported to Java as ``Character.isLetterOrDigit``, which is L* plus
    Nd only. The two disagreed on ``Ⅷ``, ``²``, supplementary-plane letters, and --
    worse -- on truncation: Python counted 48 code points while Java counted 48
    UTF-16 units and could stop between the halves of an emoji, leaving a lone
    surrogate. Restricting the alphabet makes all of that unrepresentable, and
    costs nothing a real id needs: ids are UUIDs, and this rule exists for a
    hand-edited index, where the only requirement is that both sides produce the
    SAME name, not that any particular character survives.

    Nothing is trimmed, normalised or replaced: a leading dash stays, a dot is
    dropped rather than becoming one, and an id that keeps nothing yields "".
    """
    safe = clean_tag(tag)
    if not safe:
        safe = fallback or ("%d_%d" % (int(time.time()), os.getpid()))
    return safe


# ------------------------------------------------------------ output filenames

STREAM_FILE_PREFIX = "basrelief_"


def stream_names(tag):
    """The clip run's four server-side names, in the order the run writes them.

    The app derives the same strings in ``ComfyWorkflow.streamDepthName`` and
    friends; ``contract/tag/cases.json``'s ``names`` block is what holds the two
    together.
    """
    return (
        "%s%s_depth.mp4" % (STREAM_FILE_PREFIX, tag),
        "%s%s_normal.mp4" % (STREAM_FILE_PREFIX, tag),
        "%s%s_color.mp4" % (STREAM_FILE_PREFIX, tag),
        "%s%s.progress.json" % (STREAM_FILE_PREFIX, tag),
    )


def splat_names(tag):
    """The still run's cloud and sidecar names."""
    return (
        "%s%s.splat" % (STREAM_FILE_PREFIX, tag),
        "%s%s.splat.json" % (STREAM_FILE_PREFIX, tag),
    )


# ------------------------------------------------------------- the splat sidecar


def splat_sidecar(splat_name, splats, selected, padding, focal,
                  internal_size, budget, width, height, tag):
    """The ``basrelief_<tag>.splat.json`` body, as one value rather than a literal.

    Split out of ``SharpStill.run`` so the writer can be held to the same golden
    file the app's reader is tested against (``contract/sidecar/``). Key order is
    the writer's and is part of the fixture because it is part of the file; the
    reader must not depend on it.

    ``focal`` is in PIXELS of the uploaded ``width``x``height`` frame and is 0
    when the fit failed. The app reads 0 -- and an absent key, and an absent
    height -- as "no lens" and draws its default look; it never divides by it.
    """
    return {
        "state": "done",
        "splat": splat_name,
        "splats": splats,
        "selected": selected,
        "padding": padding,
        "focal": focal,
        "internal_size": internal_size,
        "budget": budget,
        "width": width,
        "height": height,
        "tag": tag,
    }
