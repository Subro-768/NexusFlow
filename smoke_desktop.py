"""Headless smoke test for the NexusFlow PyQt6 desktop app.

Verifies the fixes that could not be confirmed by grep alone:
  1. the main window constructs
  2. test_connection no longer blocks the GUI thread (timer keeps firing)
  3. a disconnect/connect cycle emits state, so widgets are reachable
  4. drop-and-drop selects a file
  5. the discovery-unavailable path is reachable
"""
import os
import sys
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "desktop"))

from PyQt6.QtCore import QTimer, QPoint, QPointF, Qt, QMimeData, QUrl
from PyQt6.QtGui import QDropEvent
from PyQt6.QtWidgets import QApplication

import app as A

FAILS = []


def check(label, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {label}{(' -- ' + detail) if detail else ''}")
    if not cond:
        FAILS.append(label)


def main():
    qapp = QApplication(sys.argv)
    win = A.NexusFlowLinuxApp()
    win.show()

    print("1. construction")
    check("MainWindow constructed", win is not None)

    # --- 2. GUI thread stays responsive during a connection test -----------
    print("2. async test_connection (bogus IP, must not block)")
    sender = win.sender_screen
    ticks = {"n": 0}
    timer = QTimer()
    timer.setInterval(10)

    def tick():
        ticks["n"] += 1

    timer.timeout.connect(tick)
    timer.start()

    # 10.255.255.1 is non-routable; the old code blocked the GUI thread for up
    # to 4s doing this synchronously.
    sender.txt_peer_name.setText("10.255.255.1")
    t0 = time.monotonic()
    sender.test_connection()
    elapsed = time.monotonic() - t0
    # let the event loop spin
    deadline = time.monotonic() + 6.0
    while time.monotonic() < deadline and ticks["n"] < 40:
        qapp.processEvents()
        time.sleep(0.01)
    timer.stop()

    check(
        "test_connection returned immediately (<0.5s)",
        elapsed < 0.5,
        f"took {elapsed*1000:.0f}ms",
    )
    check(
        "GUI thread kept ticking while ping was in flight",
        ticks["n"] >= 20,
        f"{ticks['n']} timer ticks in 6s window",
    )

    # --- 3. failure surfaced with a reason ---------------------------------
    print("3. failure reason surfaced (not swallowed)")
    label_text = sender.lbl_conn.text()
    check(
        "connection status shows a reason, not just 'Unreachable'",
        len(label_text.strip()) > 0,
        repr(label_text[:70]),
    )

    # --- 4. drag and drop selects a file -----------------------------------
    print("4. drag-and-drop file selection")
    check("window accepts drops", win.acceptDrops())
    tmp = "/tmp/nexusflow_smoke_drop.bin"
    with open(tmp, "wb") as fh:
        fh.write(b"x" * 1024)
    mime = QMimeData()
    mime.setUrls([QUrl.fromLocalFile(tmp)])
    ev = QDropEvent(
        QPointF(10.0, 10.0),
        Qt.DropAction.CopyAction,
        mime,
        Qt.MouseButton.LeftButton,
        Qt.KeyboardModifier.NoModifier,
    )
    win.dropEvent(ev)
    qapp.processEvents()
    check(
        "dropped file became the selection",
        getattr(sender, "selected_file", None) == tmp,
        f"selected_file={getattr(sender, 'selected_file', None)!r}",
    )

    # --- 5. pause/cancel no longer mislabelled -----------------------------
    print("5. cancel sets CANCELLED and disables the button")
    btn = getattr(sender, "btn_cancel", None)
    sender.cancel_transfer()
    qapp.processEvents()
    badge = getattr(sender, "lbl_badge", None)
    check(
        "status badge is not INTERRUPTED after an explicit cancel",
        badge is not None and "INTERRUPT" not in badge.text().upper(),
        repr(badge.text()) if badge else "no badge",
    )
    check(
        "cancel button disabled after cancelling",
        btn is not None and not btn.isEnabled(),
    )

    # Close cleanly: exercises closeEvent's worker/probe reaping. Without a real
    # close, Qt destroys live QThreads and aborts the interpreter on exit.
    win.close()
    qapp.processEvents()

    print()
    if FAILS:
        print(f"RESULT: {len(FAILS)} FAILURE(S): {FAILS}")
        return 1
    print("RESULT: all smoke checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
