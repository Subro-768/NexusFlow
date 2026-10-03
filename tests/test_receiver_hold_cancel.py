"""End-to-end test of the receiver hold / release / cancel contract.

Real sockets, real HTTP, real files: the Linux receiver refuses chunks with
409/410 and the Linux sender maps those refusals onto TransferPaused /
TransferCancelled instead of a network-failure badge. The Android receiver
implements the same contract (docs/protocol.md); this is the half of it that
can be exercised without a phone attached.

Covers the resume case that matters most: a transfer interrupted by a
receiver-side pause must continue from the byte it stopped at, not from zero.
"""

import os
import sys
import threading
import time

import pytest
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for candidate in (os.path.join(ROOT, "desktop"), ROOT):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

from embedded_server import EmbeddedReceiverServer  # noqa: E402
from transfer_client import (  # noqa: E402
    LinuxTransferClient,
    TransferCancelled,
    TransferPaused,
)

CHUNK = 64 * 1024
PORT = 8931


@pytest.fixture
def receiver(tmp_path):
    # A directory of its own: the payload fixture writes into tmp_path, and the
    # receiver treats an existing destination as an already-received prefix.
    upload_dir = tmp_path / "uploads"
    server = EmbeddedReceiverServer(host="127.0.0.1", port=PORT, upload_dir=str(upload_dir))
    assert server.start() is True
    yield server
    server.stop()


@pytest.fixture
def payload(tmp_path):
    # Big enough that a pause lands mid-transfer rather than after the last chunk.
    path = tmp_path / "big.bin"
    path.write_bytes(os.urandom(12 * CHUNK))
    return str(path)


def _start_session(client, payload):
    digest = LinuxTransferClient.calculate_sha256(payload)
    session = client.create_transfer(payload, checksum=digest, chunk_size=CHUNK)
    return session["transfer_id"], digest


def test_receiver_pause_holds_then_resume_completes(receiver, payload):
    """The pause must stop bytes, and resuming must finish the same file."""
    base = f"http://127.0.0.1:{PORT}"
    client = LinuxTransferClient(base, chunk_size=CHUNK, token=receiver.auth_token)
    transfer_id, digest = _start_session(client, payload)

    offset_seen = {}
    error = {}

    def send():
        try:
            client.send_file(
                payload,
                transfer_id,
                progress_callback=lambda cur, total, speed, eta: offset_seen.update(cur=cur),
            )
        except TransferPaused:
            error["paused"] = True
        except Exception as exc:  # pragma: no cover - surfaced by the asserts below
            error["other"] = exc

    thread = threading.Thread(target=send)
    thread.start()

    # Wait until real progress, then hold it on the receiver.
    deadline = time.time() + 10
    while time.time() < deadline and offset_seen.get("cur", 0) < CHUNK:
        time.sleep(0.02)
    assert offset_seen.get("cur", 0) >= CHUNK, "transfer never started"

    assert receiver.pause_transfer(transfer_id) is True
    thread.join(timeout=10)
    assert error.get("paused") is True, f"sender did not report PAUSED: {error}"

    rec = receiver.transfers[transfer_id]
    held_at = rec["received_bytes"]
    assert rec["status"] == "paused"

    # The held session answers a fresh chunk attempt with 409 and the marker.
    resp = requests.post(
        f"{base}/transfer/{transfer_id}/chunk",
        data=b"z" * 16,
        headers={
            "X-Start-Byte": "0",
            "X-NexusFlow-Token": receiver.auth_token,
            "X-End-Byte": "15",
            "X-Total-Size": str(os.path.getsize(payload)),
        },
        timeout=10,
    )
    assert resp.status_code == 409
    assert resp.json()["detail"] == "transfer_paused"

    # Nothing was written by the refused chunk.
    assert receiver.transfers[transfer_id]["received_bytes"] == held_at

    # Resume and finish from the same offset.
    assert receiver.resume_transfer(transfer_id) is True
    final = client.send_file(payload, transfer_id, start_offset=held_at, chunk_size=CHUNK)
    assert final["received_bytes"] == os.path.getsize(payload)
    assert receiver.transfers[transfer_id].get("sha256_verified") is True

    landed = os.path.join(str(receiver.upload_dir), os.path.basename(payload))
    import hashlib
    with open(landed, "rb") as fh:
        assert hashlib.sha256(fh.read()).hexdigest() == digest


def test_receiver_cancel_ends_session_and_deletes_partial(receiver, payload):
    base = f"http://127.0.0.1:{PORT}"
    client = LinuxTransferClient(base, chunk_size=CHUNK, token=receiver.auth_token)
    transfer_id, _ = _start_session(client, payload)

    partial = os.path.join(str(receiver.upload_dir), os.path.basename(payload))
    assert os.path.exists(partial)

    assert receiver.cancel_transfer(transfer_id) is True
    assert not os.path.exists(partial), "partial file survived a cancel"

    with pytest.raises(TransferCancelled):
        client.send_file(payload, transfer_id, chunk_size=CHUNK)

    # And a later chunk attempt is refused as gone, not silently accepted.
    resp = requests.post(
        f"{base}/transfer/{transfer_id}/chunk",
        data=b"z" * 16,
        headers={
            "X-Start-Byte": "0",
            "X-NexusFlow-Token": receiver.auth_token,
            "X-End-Byte": "15",
            "X-Total-Size": str(os.path.getsize(payload)),
        },
        timeout=10,
    )
    assert resp.status_code == 410
    assert resp.json()["detail"] == "transfer_cancelled"


def test_control_endpoints_over_http(receiver, payload):
    """The same operations, reachable without touching the Hub UI."""
    base = f"http://127.0.0.1:{PORT}"
    client = LinuxTransferClient(base, chunk_size=CHUNK, token=receiver.auth_token)
    transfer_id, _ = _start_session(client, payload)

    assert requests.post(f"{base}/transfer/{transfer_id}/pause", headers={"X-NexusFlow-Token": receiver.auth_token}, timeout=10).json()["ok"]
    assert receiver.transfers[transfer_id]["status"] == "paused"
    assert requests.post(f"{base}/transfer/{transfer_id}/resume", headers={"X-NexusFlow-Token": receiver.auth_token}, timeout=10).json()["ok"]
    assert receiver.transfers[transfer_id]["status"] == "in_progress"
    assert requests.post(f"{base}/transfer/{transfer_id}/cancel", headers={"X-NexusFlow-Token": receiver.auth_token}, timeout=10).json()["ok"]

    missing = requests.post(f"{base}/transfer/does-not-exist/pause", headers={"X-NexusFlow-Token": receiver.auth_token}, timeout=10)
    assert missing.status_code == 404
    assert missing.json()["ok"] is False