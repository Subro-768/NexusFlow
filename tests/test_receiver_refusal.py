"""Sender-side handling of a deliberate refusal by the receiver.

The Android receiver can pause or cancel an incoming transfer on purpose. It
answers the chunk POST with 409/410 and a ``detail`` marker instead of writing
bytes. Before this, ``LinuxTransferClient`` called ``raise_for_status()`` on
that answer, so the GUI painted a red network-failure badge for what is a
decision the user made on the other device -- and burned both chunk retries on
it.

These tests drive the real ``send_file`` loop against a fake session so the
mapping is verified end to end rather than by inspection.
"""

import json
import os
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for candidate in (os.path.join(ROOT, "desktop"), ROOT):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

from transfer_client import (  # noqa: E402
    LinuxTransferClient,
    TransferCancelled,
    TransferPaused,
)


class FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload
        self.text = json.dumps(payload)
        self.reason = "Refused"

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            import requests

            raise requests.HTTPError(f"{self.status_code} {self.reason}", response=self)


class FakeSession:
    """Stands in for requests.Session; records the chunks it was handed."""

    def __init__(self, refusal):
        self.refusal = refusal
        self.chunk_posts = 0

    def post(self, url, data=None, headers=None, timeout=None, **kwargs):
        if "/chunk" in url:
            self.chunk_posts += 1
            return self.refusal
        raise AssertionError(f"unexpected POST {url}")

    def get(self, url, timeout=None, **kwargs):
        raise AssertionError(f"unexpected GET {url}")


@pytest.fixture
def payload_file(tmp_path):
    path = tmp_path / "payload.bin"
    path.write_bytes(b"x" * 4096)
    return str(path)


def test_receiver_pause_becomes_paused_not_failure(payload_file):
    session = FakeSession(FakeResponse(409, {"detail": "transfer_paused", "status": "PAUSED"}))
    client = LinuxTransferClient("http://peer:8000", chunk_size=1024)
    client.session = session

    with pytest.raises(TransferPaused):
        client.send_file(payload_file, transfer_id="abc")

    # One attempt only: retrying a deliberate refusal is pointless.
    assert session.chunk_posts == 1


def test_receiver_cancel_becomes_cancelled(payload_file):
    session = FakeSession(FakeResponse(410, {"detail": "transfer_cancelled", "status": "CANCELLED"}))
    client = LinuxTransferClient("http://peer:8000", chunk_size=1024)
    client.session = session

    with pytest.raises(TransferCancelled):
        client.send_file(payload_file, transfer_id="abc")

    assert session.chunk_posts == 1


def test_other_http_errors_still_raise_for_status(payload_file):
    """A 409 that is not one of our markers must not be mislabelled as a pause."""
    session = FakeSession(FakeResponse(409, {"detail": "chunk overlaps an existing range"}))
    client = LinuxTransferClient("http://peer:8000", chunk_size=1024)
    client.session = session

    with pytest.raises(Exception) as excinfo:
        client.send_file(payload_file, transfer_id="abc")
    assert not isinstance(excinfo.value, (TransferPaused, TransferCancelled))