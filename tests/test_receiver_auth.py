"""The receiver must refuse anyone who has not seen its pairing code.

These are the tests that matter most for the security claim: they assert that
an unauthenticated peer gets nothing, that the right token gets everything, and
that the token cannot be guessed or leaked through an endpoint that was meant to
stay open.
"""

import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from auth_token import (  # noqa: E402
    TOKEN_HEADER,
    TOKEN_QUERY_PARAM,
    generate_token,
    token_from_request,
    tokens_match,
)


def _get(url, token=None):
    req = urllib.request.Request(url)
    if token:
        req.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        try:
            return exc.code, json.loads(body or "{}")
        except json.JSONDecodeError:
            return exc.code, {"raw": body}


def _post(url, payload=None, token=None):
    data = json.dumps(payload or {}).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        try:
            return exc.code, json.loads(body or "{}")
        except json.JSONDecodeError:
            return exc.code, {"raw": body}


@pytest.fixture
def receiver():
    from desktop.embedded_server import EmbeddedReceiverServer

    srv = EmbeddedReceiverServer(host="127.0.0.1", port=0,
                                 upload_dir="/tmp/nf_authtest")
    srv.device_name = "PairingProbe"   # not "AuthTest": the leak test greps for "auth"
    assert srv.start() is True
    for _ in range(100):
        if srv.server and srv.server.server_address[1]:
            break
        time.sleep(0.02)
    yield srv
    srv.stop()


@pytest.fixture
def base(receiver):
    host, port = receiver.server.server_address[0], receiver.server.server_address[1]
    return f"http://{host}:{port}", receiver.auth_token


# ── the token itself ──────────────────────────────────────────────────────────

def test_generated_tokens_are_the_advertised_shape():
    for _ in range(200):
        t = generate_token()
        assert len(t) == 8
        # 0/O and 1/I/L are excluded so a code can be read aloud or transcribed.
        assert not set(t) & set("01OIL")


def test_generated_tokens_do_not_repeat():
    assert len({generate_token() for _ in range(500)}) == 500


def test_match_rejects_wrong_missing_and_empty():
    expected = generate_token()
    assert tokens_match(expected, expected) is True
    assert tokens_match(expected, None) is False
    assert tokens_match(expected, "") is False
    assert tokens_match("", expected) is False
    assert tokens_match(expected, expected[:-1]) is False
    assert tokens_match(expected, expected + "X") is False


def test_token_is_read_from_header_or_query_but_header_wins():
    class H(dict):
        pass

    h = H({TOKEN_HEADER: "FROMHEADER"})
    assert token_from_request(h) == "FROMHEADER"
    # a header present but empty falls through to the query rather than yielding ""
    h2 = H({TOKEN_HEADER: "  "})
    assert token_from_request(h2, {TOKEN_QUERY_PARAM: "FROMQUERY"}) == "FROMQUERY"
    assert token_from_request(H({}), {TOKEN_QUERY_PARAM: "Q"}) == "Q"
    assert token_from_request(H({}), None) is None
    assert token_from_request(None, None) is None


# ── the gate ──────────────────────────────────────────────────────────────────

def test_receiver_generates_a_token_on_start(base):
    _, token = base
    assert len(token) == 8


def test_health_stays_open_so_the_preflight_can_still_probe(base):
    """The pre-flight reachability check must not need a credential.

    /health is deliberately outside the gate: it is what tells the sender there
    is *something* here to bother authenticating with.
    """
    url, _ = base
    status, body = _get(f"{url}/health")
    assert status == 200
    assert body["status"] == "online"


def test_health_does_not_leak_the_token(base):
    """The one endpoint left open must not be the one that hands out the key."""
    url, token = base
    _, body = _get(f"{url}/health")
    assert token not in json.dumps(body)
    for forbidden in ("token", "auth", "secret", "key"):
        assert forbidden not in json.dumps(body).lower()


def test_transfers_list_requires_the_token(base):
    url, token = base
    status, body = _get(f"{url}/transfers")
    assert status == 401
    assert body.get("error") == "unauthorised"

    status, body = _get(f"{url}/transfers", token=token)
    assert status == 200
    assert isinstance(body, list)


def test_creating_a_transfer_requires_the_token(base):
    url, token = base
    payload = {"filename": "sneaky.bin", "size": 4, "offset": 0}

    status, _ = _post(f"{url}/transfer", payload)
    assert status == 401

    status, body = _post(f"{url}/transfer", payload, token=token)
    assert status in (200, 201)
    assert body.get("error") != "unauthorised"


def test_token_accepted_as_a_query_parameter(base):
    """So a pairing code can be pasted by hand without header plumbing."""
    url, token = base
    assert _get(f"{url}/transfers", token=token)[0] == 200
    assert _get(f"{url}/transfers?{TOKEN_QUERY_PARAM}={token}")[0] == 200
    assert _get(f"{url}/transfers?{TOKEN_QUERY_PARAM}=WRONGXXX")[0] == 401


def test_near_miss_token_is_refused(base):
    """A one-character difference must fail; this is what makes it a credential."""
    url, token = base
    wrong = ("B" if token[0] != "B" else "C") + token[1:]
    assert _get(f"{url}/transfers", token=wrong)[0] == 401


def test_chunk_upload_requires_the_token(base):
    """The endpoint that actually writes bytes must be the guarded one."""
    url, token = base
    status, body = _post(f"{url}/transfer", {"filename": "a.bin", "size": 3, "offset": 0},
                         token=token)
    assert status in (200, 201)
    tid = body.get("transfer_id") or body.get("id")
    assert tid

    # writing without the token must not create or extend anything
    import requests
    # The chunk endpoint also needs the byte range, so an unauthenticated post is
    # refused for the right reason rather than coincidentally failing validation.
    chunk_headers = {"Content-Type": "application/octet-stream", "X-Start-Byte": "0"}
    r = requests.post(f"{url}/transfer/{tid}/chunk", data=b"abc",
                      headers=chunk_headers, timeout=5)
    assert r.status_code == 401
    assert receiver_size(url, tid, token) == 0

    r = requests.post(f"{url}/transfer/{tid}/chunk", data=b"abc",
                      headers={**chunk_headers, TOKEN_HEADER: token}, timeout=5)
    assert r.status_code == 200
    assert receiver_size(url, tid, token) == 3


def receiver_size(url, tid, token):
    """Bytes the receiver says it holds, under whichever key it reports them."""
    status, body = _get(f"{url}/transfer/{tid}/status", token=token)
    for key in ("received_bytes", "bytes_received", "received", "offset"):
        if key in body:
            return int(body[key] or 0)
    raise AssertionError(f"no byte count in status response: {body}")


def test_token_changes_between_receiver_instances():
    """A restart invalidates the old code, so a leaked screenshot ages out."""
    tokens = set()
    for _ in range(3):
        from desktop.embedded_server import EmbeddedReceiverServer
        srv = EmbeddedReceiverServer(host="127.0.0.1", port=0,
                                     upload_dir="/tmp/nf_authtest2")
        tokens.add(srv.auth_token)
    assert len(tokens) == 3


def test_status_of_an_unknown_transfer_still_needs_the_token(base):
    url, token = base
    assert _get(f"{url}/transfer/does-not-exist/status")[0] == 401
    assert _get(f"{url}/transfer/does-not-exist/status", token=token)[0] == 404