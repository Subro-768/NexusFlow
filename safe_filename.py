"""Filenames from the network are never trusted as path components.

A sender chooses the ``filename`` field, and it arrives over HTTP from whatever
device happens to be on the network. Handing it to ``os.path.join`` unchanged
turns a name like ``../../../.bashrc`` into a write outside the upload
directory -- ``join`` does not stop at the first component.

``safe_filename`` reduces a name to its last path segment and then to characters
that cannot mean anything structural. It never returns an empty string, so the
result is always safe to join.
"""

from __future__ import annotations

import os
import re
import unicodedata

#: Anything outside this set becomes "_". Notably excludes "/", "\\", NUL and
#: the leading-dot pattern, so a name can never re-enter the tree or become
#: a dotfile.
_ALLOWED = re.compile(r"[^A-Za-z0-9._-]")

#: Reserved on Windows; harmless to reject everywhere so a name means the same
#: thing on both ends of the project.
_RESERVED = {
    "con", "prn", "aux", "nul",
    *(f"com{i}" for i in range(1, 10)),
    *(f"lpt{i}" for i in range(1, 10)),
}

FALLBACK_NAME = "unnamed"


def safe_filename(raw: str, fallback: str = FALLBACK_NAME) -> str:
    """A name that is safe to join onto a directory.

    Strips any directory component (POSIX or Windows), rejects traversal and
    hidden names, normalises unicode, and substitutes anything unusable.

    The last path segment is taken *before* filtering, so both ``a/b/c.txt`` and
    ``..\\..\\c.txt`` reduce to ``c.txt`` -- which is the intent, and is also
    why the result can never contain a separator.
    """
    text = str(raw or "").strip()
    if not text:
        return fallback

    # Both separators, so a Windows-style path cannot survive on Linux.
    text = text.replace("\\", "/")
    text = os.path.basename(text)
    text = unicodedata.normalize("NFKD", text)
    text = _ALLOWED.sub("_", text)
    text = text.lstrip(".")          # no "..", no dotfiles
    text = text.strip("._ ") or ""

    if not text:
        return fallback
    stem = text.split(".")[0].lower()
    if stem in _RESERVED:
        return f"_{text}"
    return text
