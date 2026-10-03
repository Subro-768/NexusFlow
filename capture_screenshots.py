"""Full-window screenshots of the Linux app, for the repository.

The existing hub harness grabs only the payload card, which is right for
checking one control and wrong for a README: a screenshot showing a widget out
of context does not tell a reader what the application looks like.

    python capture_screenshots.py            # into docs/screenshots/

Offscreen only the *display* is: real Qt widgets, real HTTP, real chunks -- the
same code path a user runs. Nothing here pokes private state to fake a screen;
it calls the same entry points the dialogs and buttons call.
"""

import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))          # project root
sys.path.insert(0, os.path.join(HERE, "desktop"))

from PyQt6.QtWidgets import QApplication                      # noqa: E402
from PyQt6.QtCore import QTimer                               # noqa: E402

import app as A                                               # noqa: E402
from transfer_client import LinuxTransferClient                # noqa: E402

CHUNK = 1024 * 1024
CHUNKS = 150                        # ~150 MB, long enough to pause mid-flight
OUT = os.path.join(HERE, "docs", "screenshots")


def snap(win, name):
    """Save the whole window, not a fragment of it."""
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"linux-{name}.png")
    pm = win.grab()
    ok = pm.save(path, "PNG")
    print(f"  {'saved' if ok else 'FAILED'} {os.path.basename(path)} "
          f"({pm.width()}x{pm.height()})", flush=True)
    return ok


def main():
    # A fresh name every run: the receiver treats an existing destination of the
    # same size as an already-received prefix -- that is how resume works -- so a
    # reused name would report complete instantly and there'd be nothing to see.
    payload = f"/tmp/shot_{int(time.time())}.bin"
    with open(payload, "wb") as fh:
        fh.write(os.urandom(CHUNKS * CHUNK))
    total = os.path.getsize(payload)

    qapp = QApplication(sys.argv)
    win = A.NexusFlowLinuxApp()
    win.resize(1240, 1040)
    win.show()
    hub = win.receiver_screen
    # The receiver binds SETTINGS.port, so read it back rather than assuming one.
    port = hub.server.port
    got = {}

    # ── 1. sender, a file attached ────────────────────────────────────────────
    def step_sender():
        win.drawer.set_active(0)
        win._on_nav(0)
        # set_file is the single entry point the file dialog and drag-and-drop
        # both use, so this is the state a user actually gets.
        win.sender_screen.set_file(payload)
        QTimer.singleShot(1000, lambda: got.__setitem__("sender", snap(win, "sender-ready")))
        QTimer.singleShot(2000, step_hub)

    # ── 2. receiver hub idle, and with the endpoint panel open ───────────────
    def step_hub():
        win.drawer.set_active(1)
        win._on_nav(1)
        # Via the button, not server.start(): the badge and the label are updated
        # by that path, and calling start() directly left the screenshot claiming
        # SERVER OFFLINE while a transfer was visibly arriving.
        if not hub.server.is_running:
            hub.btn_toggle_server.click()
        hub.refresh_ip()
        QTimer.singleShot(1200, lambda: got.__setitem__("hub", snap(win, "receiver-hub")))
        QTimer.singleShot(2200, lambda: hub.btn_endpoint.setChecked(True))
        QTimer.singleShot(3400, lambda: got.__setitem__("endpoint", snap(win, "receiver-endpoint")))
        QTimer.singleShot(4400, lambda: hub.btn_endpoint.setChecked(False))
        QTimer.singleShot(5200, start_push)

    # ── 3. a real transfer arriving, then held ───────────────────────────────
    def start_push():
        client = LinuxTransferClient(f"http://127.0.0.1:{port}",
                                     chunk_size=CHUNK,
                                     token=hub.server.auth_token)
        real_post = client.session.post

        def slow_post(*a, **kw):
            time.sleep(0.30)       # slow enough to photograph
            return real_post(*a, **kw)

        client.session.post = slow_post

        def push():
            try:
                digest = LinuxTransferClient.calculate_sha256(payload)
                tid = client.create_transfer(payload, checksum=digest,
                                             chunk_size=CHUNK)["transfer_id"]
                print(f"  session {tid}, {total} bytes -> 127.0.0.1:{port}", flush=True)
                client.send_file(payload, tid)
            except Exception as exc:                      # noqa: BLE001
                print(f"  [sender] {type(exc).__name__}: {exc}", flush=True)

        threading.Thread(target=push, daemon=True).start()
        def reveal_incoming():
            """Bring the incoming card fully into view.

            It sits below the fold at this window height, and a screenshot that
            crops the Pause / Resume / Cancel buttons is not showing the feature.
            """
            # The Hub page is not a QScrollArea, so there is nothing to scroll:
            # the window is sized so the card fits. ensureWidgetVisible would
            # have silently done nothing here, which is the kind of thing that
            # leaves a cropped screenshot looking deliberate.
            got["live"] = snap(win, "receiver-live")

        QTimer.singleShot(6000, reveal_incoming)
        QTimer.singleShot(9000, step_pause)

    def step_pause():
        if hub.btn_rx_pause.isEnabled():
            hub.btn_rx_pause.click()
            print("  clicked PAUSE", flush=True)
        QTimer.singleShot(2000, lambda: got.__setitem__("paused", snap(win, "receiver-paused")))
        QTimer.singleShot(3200, finish)

    def finish():
        for tid in list(hub.server.transfers.keys()):
            hub.server.cancel_transfer(tid)
        os.unlink(payload)
        QTimer.singleShot(500, qapp.quit)

    QTimer.singleShot(1500, step_sender)
    QTimer.singleShot(180_000, qapp.quit)                  # hard stop
    qapp.exec()
    ok = sum(1 for v in got.values() if v)
    print(f"\n{ok}/{len(got)} screenshots -> {OUT}")
    return 0 if ok == len(got) else 1


if __name__ == "__main__":
    raise SystemExit(main())