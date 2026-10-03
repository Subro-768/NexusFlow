"""A tapped peer must be able to authenticate without the camera.

The pairing token has two routes to a sender: the QR, which is private, and the
discovery reply, which is not. Discovery is unauthenticated UDP broadcast, so
anything on the network can ask "who is out there?" and read the answer -- the
token there stops a stranger *scanning* from writing files, and does not stop one
already listening. That trade is documented in lan_discovery._Responder.

It is only worth making if the token actually survives the trip, which is what
these tests cover: responder to wire, wire to LanPeer, and LanPeer to the token
cache the client reads from.
"""

import json
import os
import sys
import time

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "desktop"))

from lan_discovery import LanPeer, _Responder                      # noqa: E402
from auth_token import generate_token                               # noqa: E402


# ── the wire format ──────────────────────────────────────────────────────────

def test_responder_publishes_the_token_when_given_one():
    r = _Responder(name="Laptop", port=8000, own_ips=[], auth_token="ABC23456")
    hello = r._identity()
    assert hello["tok"] == "ABC23456"
    assert hello["n"] == "Laptop"
    assert hello["p"] == 8000


def test_responder_omits_the_key_entirely_without_a_token():
    """Absent, not empty: an empty "tok" would read as a failed match."""
    hello = _Responder(name="Laptop", port=8000, own_ips=[])._identity()
    assert "tok" not in hello


def test_identity_is_json_serialisable():
    # It goes out as one datagram; a non-serialisable value fails at send time,
    # which is exactly when nobody is looking.
    r = _Responder(name="Laptop", port=8000, own_ips=[], auth_token="ABC23456")
    assert json.loads(json.dumps(r._identity()))["tok"] == "ABC23456"


def test_the_documented_query_and_hello_keys_are_unchanged():
    hello = _Responder(name="Laptop", port=8000, own_ips=[], auth_token="ABC23456")._identity()
    assert set(hello) >= {"t", "n", "p", "v", "id"}
    assert hello["t"] == "h"


# ── the receiving end ────────────────────────────────────────────────────────

def test_lan_peer_carries_a_token():
    p = LanPeer(name="Laptop", host="10.0.0.5", port=8000, key="k", token="ABC23456")
    assert p.token == "ABC23456"


def test_lan_peer_token_defaults_to_empty():
    assert LanPeer(name="L", host="h", port=1, key="k").token == ""


def test_listener_reads_the_token_out_of_a_reply():
    """The parsing the listener performs on a hello datagram."""
    msg = json.loads('{"t":"h","n":"Laptop","p":8000,"v":1,"id":"10.0.0.5","tok":"ABC23456"}')
    assert msg.get("tok") == "ABC23456"
    peer = LanPeer(
        name=str(msg.get("n") or msg["id"]),
        host=msg.get("id"),
        port=int(msg.get("p") or 8000),
        key="k",
        token=str(msg.get("tok") or ""),
    )
    assert peer.token == "ABC23456"


def test_a_reply_without_a_token_yields_an_empty_one():
    msg = json.loads('{"t":"h","n":"Laptop","p":8000,"id":"10.0.0.5"}')
    assert str(msg.get("tok") or "") == ""


def test_a_malicious_tok_is_not_interpreted_as_anything_but_a_string():
    # The token is compared with tokens_match downstream, never eval'd, but a
    # non-string here would break that comparison rather than fail loudly.
    msg = json.loads('{"t":"h","n":"L","p":8000,"id":"1.2.3.4","tok":{"a":1}}')
    assert str(msg.get("tok") or "") == "{'a': 1}"


# ── the GUI hop ──────────────────────────────────────────────────────────────

def test_select_peer_caches_the_advertised_token():
    """A tapped peer with a token ends up in the cache make_client reads.

    select_peer is called on a bare instance rather than a constructed one:
    all it touches is two labels and a stubbed test_connection, so a Qt plugin
    would be needed for nothing.
    """
    import app as A

    cache = A.peer_tokens
    cache.clear()
    screen = A.SenderScreen.__new__(A.SenderScreen)   # no Qt needed for the cache write

    class Stub:
        def setText(self, *_):
            pass

    screen.txt_peer_name = Stub()
    screen.txt_port = Stub()
    screen.test_connection = lambda: None

    A.SenderScreen.select_peer(screen, "10.0.0.5", 8000, "Laptop", token="ABC23456")
    assert cache.get("10.0.0.5") == "ABC23456"


def test_select_peer_without_a_token_caches_nothing():
    """A peer that advertises none must not be given a blank entry that would
    then be sent as a credential."""
    import app as A

    cache = A.peer_tokens
    cache.clear()
    screen = A.SenderScreen.__new__(A.SenderScreen)

    class Stub:
        def setText(self, *_):
            pass

    screen.txt_peer_name = Stub()
    screen.txt_port = Stub()
    screen.test_connection = lambda: None

    A.SenderScreen.select_peer(screen, "10.0.0.5", 8000, "Laptop")
    assert "10.0.0.5" not in cache


def test_make_client_picks_up_the_cached_token():
    """The end of the chain: a client built for that host carries the token."""
    import app as A

    A.peer_tokens.clear()
    A.peer_tokens["10.0.0.5"] = generate_token()
    try:
        client = A.make_client("http://10.0.0.5:8000")
        assert client.token == A.peer_tokens["10.0.0.5"]
        assert client.session.headers.get("X-NexusFlow-Token") == A.peer_tokens["10.0.0.5"]
        # an explicit argument wins over the cache
        explicit = A.make_client("http://10.0.0.5:8000", token="EXPLICIT1")
        assert explicit.token == "EXPLICIT1"
    finally:
        A.peer_tokens.clear()


def test_host_extraction_ignores_scheme_and_port():
    import app as A
    for url, host in (("http://10.0.0.5:8000", "10.0.0.5"),
                      ("10.0.0.5:8000", "10.0.0.5"),
                      ("https://10.0.0.5", "10.0.0.5")):
        assert A._host_of(url) == host, url