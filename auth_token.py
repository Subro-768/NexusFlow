"""Pairing tokens for the NexusFlow receivers.

Why this exists
---------------
Every receiver in this project speaks plain HTTP to whoever asks, on whatever
network it happens to be on. That means any device on the same Wi-Fi could:

  * write a file onto the receiver (POST /transfer, then chunks), and
  * read what the receiver is currently receiving (/transfers, /transfer/{id}).

For a project whose entire premise is "HTTP across a local network" that is not
a detail to leave unstated -- it is the first question anyone technical will ask.

A note on what a token can and cannot do here
--------------------------------------------
The token is deliberately *not* fetched over the network. Discovery is UDP
broadcast, so anything that can ask "who is out there?" can also read the
answer; serving the token from /health or from the discovery reply would be the
same as publishing it. So the token only ever travels out of band:

  * in the QR code, which the camera reads off the other screen, and
  * typed by a human from the receiver's own display.

That is a real limitation, not a solved problem: it authenticates *who may
talk to this receiver*, and it depends on the network already being one you
control. It is not a defence against an attacker who can read the screen or the
camera, and it is not encryption. What it does buy is that a stranger's laptop
on the hotel Wi-Fi cannot silently become a peer.

Token shape
-----------
Eight characters from a 30-symbol alphabet: no 0/O/1/I/L, so it survives being
read aloud or copied off a screen by hand. Compared in constant time.
"""

from __future__ import annotations

import hmac
import secrets

# Deliberately ambiguous glyphs (0/O, 1/I/L) removed so a token can be dictated
# or transcribed from the receiver's screen without a second channel.
_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
TOKEN_LENGTH = 8

#: Header carrying the token. Prefixed to avoid colliding with anything a
#: reverse proxy in front of the receiver might interpret.
TOKEN_HEADER = "X-NexusFlow-Token"

#: Query parameter accepted as an alternative, for QR payloads and for pasting a
#: code by hand. The header is preferred because a query string lands in logs.
TOKEN_QUERY_PARAM = "t"


def generate_token(length: int = TOKEN_LENGTH) -> str:
    """A fresh token. Compared in constant time everywhere it is checked."""
    return "".join(secrets.choice(_ALPHABET) for _ in range(length))


def tokens_match(expected: str, supplied: str | None) -> bool:
    """Constant-time comparison, tolerant of a missing or non-string value.

    The length is not secret, so a fast length check is fine; the comparison
    itself is what must not leak its position through timing.
    """
    if not supplied or not expected:
        return False
    return hmac.compare_digest(str(expected), str(supplied))


def token_from_request(headers, query_params: dict | None = None) -> str | None:
    """Pull a token out of whatever a request is carrying.

    Accepts the header, or the query parameter for hand-written codes. Both are
    checked so a caller using either convention is authenticated the same way.
    """
    header_value = None
    if headers is not None:
        getter = getattr(headers, "get", None)
        if getter is not None:
            header_value = getter(TOKEN_HEADER)
        else:  # a plain dict works too
            try:
                header_value = headers.get(TOKEN_HEADER)
            except AttributeError:
                header_value = None
    if header_value and str(header_value).strip():
        return str(header_value).strip()
    if query_params:
        value = query_params.get(TOKEN_QUERY_PARAM)
        if value:
            return str(value).strip()
    return None