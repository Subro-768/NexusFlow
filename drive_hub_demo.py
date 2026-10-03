"""Launch the real Linux Hub with a live transfer arriving, so the new
Pause / Resume / Cancel controls can be seen and used for real.

    python drive_hub_demo.py                  # interactive: click PAUSE / RESUME / CANCEL
    python drive_hub_demo.py --shots          # scripted: grab PNGs of live + paused states
    python drive_hub_demo.py --seconds 60

Offscreen only the *display* is: real Qt window, real HTTP, real chunks. PAUSE
holds the push and the sender stops cleanly; RESUME lets it continue from the
same byte; CANCEL ends it and deletes the partial.
"""

import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))  # the project root
sys.path.insert(0, os.path.join(HERE, "desktop"))

from PyQt6.QtWidgets import QApplication  # noqa: E402
from PyQt6.QtCore import QTimer  # noqa: E402

import app as A  # noqa: E402
from transfer_client import (  # noqa: E402
    LinuxTransferClient, TransferCancelled, TransferPaused,
)

PORT = int(os.environ.get("HUB_DEMO_PORT", "8000"))
CHUNK = 1024 * 1024
CHUNKS = 120          # ~42s at one chunk per 350ms: room to look at the card
SHOTS = "/tmp/hub_controls"


def main():
    seconds = 40
    if "--seconds" in sys.argv:
        seconds = int(sys.argv[sys.argv.index("--seconds") + 1])
    want_shots = "--shots" in sys.argv

    qapp = QApplication(sys.argv)
    win = A.NexusFlowLinuxApp()
    win.show()
    hub = win.receiver_screen

    def open_hub():
        win.drawer.set_active(1)
        win._on_nav(1)
        hub.server.start()

    def sender_body():
        # A fresh name every run. The receiver treats an existing destination of
        # the same size as an already-received prefix (that is how resume works),
        # so reusing a name would make the transfer report complete instantly and
        # there would be nothing to pause.
        payload = f"/tmp/hub_demo_{int(time.time())}.bin"
        with open(payload, "wb") as fh:
            fh.write(os.urandom(CHUNKS * CHUNK))
        total = os.path.getsize(payload)
        t0 = time.time()
        print(f"[t+0.0s] starting {total} bytes in {CHUNKS} chunks", flush=True)

        client = LinuxTransferClient(f"http://127.0.0.1:{PORT}", chunk_size=CHUNK)
        real_post = client.session.post

        def slow_post(*a, **kw):
            time.sleep(0.35)          # slow enough to look at, fast enough to finish
            return real_post(*a, **kw)

        client.session.post = slow_post
        digest = LinuxTransferClient.calculate_sha256(payload)
        tid = client.create_transfer(payload, checksum=digest, chunk_size=CHUNK)["transfer_id"]
        print(f"[demo] session {tid}, {total} bytes -> 127.0.0.1:{PORT}", flush=True)

        def progress(cur, tot, spd, eta):
            print(f"[t+{time.time()-t0:5.1f}s] {100*cur/tot:5.1f}%  "
                  f"{spd/1e6:5.2f} MB/s  eta {eta:5.1f}s", flush=True)

        try:
            res = client.send_file(payload, tid, progress_callback=progress)
            print(f"[t+{time.time()-t0:5.1f}s] COMPLETED verified={res.get('verified')}", flush=True)
        except TransferPaused:
            print(f"[t+{time.time()-t0:5.1f}s] HELD by the Hub (PAUSE) - sender stopped cleanly", flush=True)
        except TransferCancelled:
            print(f"[t+{time.time()-t0:5.1f}s] CANCELLED by the Hub - sender stopped cleanly", flush=True)
        except Exception as exc:
            print(f"[demo] ended: {type(exc).__name__}: {exc}", flush=True)

    def grab(tag):
        path = f"{SHOTS}_{tag}.png"
        # The payload card, not the whole Hub: the incoming card sits below the
        # fold of the page, and the card is what carries the new controls.
        ok = hub.payload_card.grab().save(path)
        print(f"[demo] shot {tag} -> {path} ({'ok' if ok else 'FAILED'})", flush=True)

    if want_shots:
        def do_pause():
            hub.btn_rx_pause.click()
            print("[demo] clicked PAUSE", flush=True)

        def shoot_paused():
            grab("paused")
            print(f"[demo] badge={hub.lbl_rx_status_badge.text()!r} "
                  f"button={hub.btn_rx_pause.text()!r} "
                  f"info={hub.lbl_payload_info.text()!r}", flush=True)

        def do_resume():
            hub.btn_rx_pause.click()
            print("[demo] clicked RESUME", flush=True)

        QTimer.singleShot(5000, lambda: grab("live"))
        QTimer.singleShot(5200, do_pause)
        QTimer.singleShot(6500, shoot_paused)
        QTimer.singleShot(7500, do_resume)
        QTimer.singleShot(9500, lambda: grab("resumed"))

    def kick_off():
        # A thread, not the timer callback itself. The real app sends from a
        # QThread for the same reason: send_file() blocks for the whole transfer,
        # and doing it on the GUI thread freezes the event loop, so the Hub's own
        # queued state updates and button clicks could not run until it finished.
        threading.Thread(target=sender_body, daemon=True).start()

    QTimer.singleShot(600, open_hub)
    QTimer.singleShot(1400, kick_off)
    QTimer.singleShot(seconds * 1000, qapp.quit)
    sys.exit(qapp.exec())


if __name__ == "__main__":
    main()