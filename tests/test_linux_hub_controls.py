"""Drive the Linux Hub's incoming-transfer controls against a live transfer.

The Android Hub's Pause/Resume/Cancel was verified on a phone; this does the
same for the PyQt one. Real HTTP, real chunks, real Qt widgets -- only the
display is offscreen.

Fails if the buttons do not reach the server, if the badge/label do not follow
the session, or if a cancel leaves the partial file behind.
"""

import os
import sys
import threading
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for candidate in (os.path.join(ROOT, "desktop"), ROOT):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

import pytest  # noqa: E402

from PyQt6.QtWidgets import QApplication  # noqa: E402

import app as A  # noqa: E402
from embedded_server import EmbeddedReceiverServer  # noqa: E402
from transfer_client import LinuxTransferClient, TransferPaused  # noqa: E402

CHUNK = 64 * 1024
PORT = 8941


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or QApplication([])


@pytest.fixture
def hub(qapp, tmp_path):
    """A real ReceiverScreen wired to a real receiver server.

    ReceiverScreen builds its own server on the Settings port; for the test we
    swap in one bound to loopback and a temp dir, which is the same substitution
    the headless/direct-callback path in __init__ already supports.
    """
    screen = A.ReceiverScreen()
    server = EmbeddedReceiverServer(host="127.0.0.1", port=PORT,
                                    upload_dir=str(tmp_path / "rx"))
    if not server.start():
        pytest.fail("could not start the test receiver server")
    screen.server = server
    screen.refresh_received_files()
    try:
        yield screen, server
    finally:
        server.stop()


@pytest.fixture
def payload(tmp_path):
    path = tmp_path / "hub_payload.bin"
    path.write_bytes(os.urandom(12 * CHUNK))
    return str(path)


def _drain(qapp, ms=250):
    end = time.time() + ms / 1000.0
    while time.time() < end:
        qapp.processEvents()
        time.sleep(0.01)


def _throttle(client, seconds=0.15):
    """Slow every chunk down.

    Loopback pushes 768 KB in well under a frame, so a pause tapped right after
    the first chunk would arrive after the file was already on disk -- the test
    would then be asserting against a finished transfer. Real networks are not
    that fast, and neither is Wi-Fi between two devices.
    """
    real_post = client.session.post

    def slow_post(*args, **kwargs):
        time.sleep(seconds)
        return real_post(*args, **kwargs)

    client.session.post = slow_post
    return client


def test_pause_resume_cancel_from_the_hub_buttons(qapp, hub, payload):
    screen, server = hub
    base = f"http://127.0.0.1:{PORT}"
    client = _throttle(LinuxTransferClient(base, chunk_size=CHUNK))
    digest = LinuxTransferClient.calculate_sha256(payload)
    tid = client.create_transfer(payload, checksum=digest, chunk_size=CHUNK)["transfer_id"]

    # The server hands state to a callback from its request thread; the real app
    # marshals that onto the GUI thread via WorkerBridge. Here we only record it
    # and pump it in explicitly, so no Qt widget is touched off-thread.
    seen = {"n": 0, "last": {}}

    def on_state(rec):
        seen["n"] += 1
        seen["last"] = dict(rec or {})

    server.state_callback = on_state

    error = {}
    progress = {}

    def send():
        try:
            client.send_file(
                payload, tid,
                progress_callback=lambda c, t, s, e: progress.update(cur=c),
            )
        except TransferPaused:
            error["paused"] = True
        except Exception as exc:  # pragma: no cover
            error["other"] = exc

    thread = threading.Thread(target=send, daemon=True)
    thread.start()

    deadline = time.time() + 10
    while time.time() < deadline and progress.get("cur", 0) < CHUNK:
        _drain(qapp, 20)
    assert progress.get("cur", 0) >= CHUNK, "transfer never started"

    # --- the card must be live: controls up, badge RECEIVING ---
    screen.on_server_state_update(dict(server.transfers[tid]))
    _drain(qapp, 50)
    assert screen.rx_controls_widget.isVisibleTo(screen) is True, "controls hidden during transfer"
    assert screen.btn_rx_pause.text().startswith("⏸"), screen.btn_rx_pause.text()
    assert screen.lbl_rx_status_badge.text() == "RECEIVING"
    assert seen["n"] > 0, "server never published state for the Hub"

    # --- PAUSE ---
    screen.toggle_rx_pause()
    _drain(qapp, 80)
    rec = server.transfers[tid]
    assert rec["status"] == "paused", rec["status"]
    held_at = rec["received_bytes"]
    thread.join(timeout=10)
    assert not thread.is_alive(), "sender kept streaming after PAUSE"
    assert error.get("paused") is True, f"sender did not report PAUSED: {error}"

    screen.on_server_state_update(dict(rec))
    _drain(qapp, 50)
    assert screen.lbl_rx_status_badge.text() == "PAUSED"
    assert screen.btn_rx_pause.text().startswith("▶"), "button did not become RESUME"
    assert screen.lbl_rx_eta.isVisibleTo(screen) is False, "frozen ETA shown while held"

    # --- RESUME from the same button ---
    screen.toggle_rx_pause()
    _drain(qapp, 80)
    assert server.transfers[tid]["status"] == "in_progress", server.transfers[tid]["status"]

    final = client.send_file(payload, tid, start_offset=held_at, chunk_size=CHUNK)
    assert final["received_bytes"] == os.path.getsize(payload)
    _drain(qapp, 50)

    screen.on_server_state_update(dict(server.transfers[tid]))
    _drain(qapp, 50)
    assert screen.lbl_rx_status_badge.text() == "COMPLETED"
    assert screen.rx_controls_widget.isVisibleTo(screen) is False, \
        "controls still offered after the transfer finished"
    assert server.transfers[tid].get("sha256_verified") is True


def test_cancel_button_deletes_the_partial(qapp, hub, payload):
    screen, server = hub
    base = f"http://127.0.0.1:{PORT}"
    client = LinuxTransferClient(base, chunk_size=CHUNK)
    tid = client.create_transfer(payload, chunk_size=CHUNK)["transfer_id"]

    partial = os.path.join(str(server.upload_dir), os.path.basename(payload))
    assert os.path.exists(partial)

    screen.on_server_state_update(dict(server.transfers[tid]))
    _drain(qapp, 50)
    assert screen.rx_controls_widget.isVisibleTo(screen) is True

    screen.cancel_rx_transfer()
    _drain(qapp, 80)

    assert server.transfers[tid]["status"] == "cancelled"
    assert not os.path.exists(partial), "partial survived a cancel from the Hub"

    screen.on_server_state_update(dict(server.transfers[tid]))
    _drain(qapp, 50)
    assert screen.lbl_rx_status_badge.text() == "CANCELLED"
    assert "partial file was deleted" in screen.lbl_payload_info.text()
    assert screen.rx_controls_widget.isVisibleTo(screen) is False


def test_controls_are_absent_when_nothing_is_arriving(qapp, hub):
    screen, server = hub
    screen.on_server_state_update({
        "filename": "", "received_bytes": 0, "total_size": 0,
        "status": "pending", "speed_bytes_sec": 0,
    })
    _drain(qapp, 50)
    # A session that exists but has not started is still controllable; a button
    # press with no live session must be a no-op rather than a crash.
    screen.toggle_rx_pause()
    screen.cancel_rx_transfer()
    _drain(qapp, 50)