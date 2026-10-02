"""Thread-safety regression test.

The original bug: ``EmbeddedReceiverServer.state_callback`` was assigned the
ReceiverScreen's widget handler, and http.server's ThreadingMixIn invokes it on
a request thread. That handler then called QLabel.setText / setStyleSheet /
deleteLater from a non-GUI thread -- undefined behaviour in Qt, and a crash in
practice.

This test drives real callbacks from a non-GUI thread and asserts the receiving
handler runs on the GUI thread.
"""
import os
import sys
import threading

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "desktop"))

from PyQt6.QtCore import QCoreApplication, QTimer
from PyQt6.QtWidgets import QApplication

import app as A

FAILS = []


def check(label, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {label}{(' -- ' + detail) if detail else ''}")
    if not cond:
        FAILS.append(label)


def main():
    qapp = QApplication(sys.argv)
    gui_thread = threading.get_ident()

    bridge = A.WorkerBridge()
    rx = A.ReceiverScreen(bridge=bridge)
    rx.show()

    observed = {}
    original = rx.on_server_state_update

    def spy(rec):
        observed["thread"] = threading.get_ident()
        observed["gui"] = gui_thread
        original(rec)

    # Patch the slot the bridge is connected to so we can see where it runs.
    rx.on_server_state_update = spy
    bridge.server_state.disconnect()
    bridge.server_state.connect(spy, __import__("PyQt6.QtCore", fromlist=["Qt"]).Qt.ConnectionType.QueuedConnection)

    # 1. What the server actually holds as its callback.
    cb = rx.server.state_callback
    check(
        "server callback is the bridge, not the widget handler",
        cb == bridge.on_server_state,
        f"callback={getattr(cb, '__qualname__', cb)}",
    )

    # 2. Invoke it from a genuine non-GUI thread, like a real HTTP request would.
    payload = {
        "status": "RECEIVING",
        "filename": "thread_probe.bin",
        "received_bytes": 1024,
        "total_size": 4096,
    }

    def from_worker():
        try:
            cb(payload)
        except Exception as exc:  # pragma: no cover - diagnostics only
            observed["error"] = f"{type(exc).__name__}: {exc}"

    t = threading.Thread(target=from_worker, name="fake-http-worker")
    t.start()
    t.join(5)

    check("worker thread completed", not t.is_alive())
    check("no exception raised in worker thread", "error" not in observed, observed.get("error", ""))

    # 3. Spin the event loop so the QueuedConnection is delivered.
    deadline = 0
    while observed.get("thread") is None and deadline < 200:
        qapp.processEvents()
        deadline += 1

    check(
        "handler was delivered",
        observed.get("thread") is not None,
        f"observed thread={observed.get('thread')}, gui thread={gui_thread}",
    )
    check(
        "handler ran on the GUI thread, not the HTTP worker thread",
        observed.get("thread") == gui_thread,
        f"handler thread={observed.get('thread')} vs gui={gui_thread}",
    )

    rx.close()
    qapp.processEvents()

    print()
    if FAILS:
        print(f"RESULT: {len(FAILS)} FAILURE(S): {FAILS}")
        return 1
    print("RESULT: thread-safety regression test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
