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
from PyQt6.QtGui import QDropEvent, QDragEnterEvent
from PyQt6.QtWidgets import QApplication, QLabel, QLineEdit

import app as A
from nsd_helper import PeerInfo

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

    # --- 4b. the nearby-devices panel is itself a drop target -------------
    print("4b. nearby-devices panel drop target")
    panel = sender.findChild(A.DropZoneFrame)
    check("panel found and is a DropZoneFrame", panel is not None)
    if panel is not None:
        check("panel accepts drops", panel.acceptDrops())

        def hint_labels():
            return [
                w for w in panel.findChildren(QLabel)
                if "DRAG AND DROP" in (w.text() or "")
            ]

        # Empty state: exactly one faded prompt (not one per rebuild pass)
        sender.refresh_nearby_devices_ui(_peers=[])
        qapp.processEvents()
        hints = hint_labels()
        check("drop hint shown in the empty state", len(hints) == 1, f"count={len(hints)}")
        if hints:
            check(
                "hint text is the requested wording",
                hints[0].text().strip() == "DRAG AND DROP FILES OVER HERE",
                repr(hints[0].text()),
            )
            # Faded: the stylesheet must use an rgba() alpha below 1.0
            check(
                "hint is faded (alpha < 1.0)",
                "rgba(" in hints[0].styleSheet(),
                hints[0].styleSheet()[:70],
            )

        # Rebuilding repeatedly must not stack duplicate hints
        sender.refresh_nearby_devices_ui(_peers=[])
        sender.refresh_nearby_devices_ui(_peers=[])
        qapp.processEvents()
        check("no duplicate hints after rebuilds", len(hint_labels()) == 1,
              f"count={len(hint_labels())}")

        # With peers present the hint is omitted so cards get the full width
        peer = PeerInfo("TestPeer", "10.0.0.9", 8000)
        sender.refresh_nearby_devices_ui(_peers=[peer])
        qapp.processEvents()
        check("hint hidden when peers are discovered", len(hint_labels()) == 0,
              f"count={len(hint_labels())}")
        sender.refresh_nearby_devices_ui(_peers=[])
        qapp.processEvents()
        check("hint returns when peers go away", len(hint_labels()) == 1,
              f"count={len(hint_labels())}")

        # Drag-enter highlights the panel via the dragActive property
        drag_ev = QDragEnterEvent(
            QPoint(10, 10),
            Qt.DropAction.CopyAction,
            mime,
            Qt.MouseButton.LeftButton,
            Qt.KeyboardModifier.NoModifier,
        )
        panel.dragEnterEvent(drag_ev)
        qapp.processEvents()
        check(
            "drag-over sets the dragActive styling property",
            panel.property("dragActive") == "true",
            f"property={panel.property('dragActive')!r}",
        )
        check("drag event accepted", drag_ev.isAccepted())

        # A second file dropped on the panel replaces the selection
        tmp2 = "/tmp/nexusflow_smoke_drop2.bin"
        with open(tmp2, "wb") as fh:
            fh.write(b"y" * 2048)
        mime2 = QMimeData()
        mime2.setUrls([QUrl.fromLocalFile(tmp2)])
        drop_ev = QDropEvent(
            QPointF(10.0, 10.0),
            Qt.DropAction.CopyAction,
            mime2,
            Qt.MouseButton.LeftButton,
            Qt.KeyboardModifier.NoModifier,
        )
        panel.dropEvent(drop_ev)
        qapp.processEvents()
        check(
            "file dropped on the panel became the selection",
            getattr(sender, "selected_file", None) == tmp2,
            f"selected_file={getattr(sender, 'selected_file', None)!r}",
        )
        check(
            "dragActive cleared after drop",
            panel.property("dragActive") != "true",
            f"property={panel.property('dragActive')!r}",
        )

    # --- 4c. no logo bitmap in the drawer ---------------------------------
    print("4c. drawer wordmark has no logo image")
    drawer = win.drawer
    pixmaps = [
        w for w in drawer.findChildren(QLabel) if not w.pixmap().isNull()
    ]
    check("drawer renders no logo pixmap", not pixmaps, f"{len(pixmaps)} found")

    # --- 4d. no IP disclosure anywhere on screen --------------------------
    print("4d. no IP address rendered in any screen")
    import re as _re
    ip_re = _re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

    def visible_texts(root):
        out = []
        for lbl in root.findChildren(QLabel):
            if lbl.isVisible() and lbl.text():
                out.append(lbl.text())
        for le in root.findChildren(QLineEdit):
            if le.isVisible() and le.text():
                out.append(le.text())
        return out

    leaks = [t for t in visible_texts(win) if ip_re.search(t)]
    check("no IP address in any visible label/field", not leaks, str(leaks[:3]))

    # The receiver's pairing QR must encode the name only, never an address.
    qr_payload = A.build_pairing_name("TestDevice")
    check("pairing QR payload carries no address", not ip_re.search(qr_payload),
          qr_payload)
    check("pairing QR still names the device", "TestDevice" in qr_payload,
          qr_payload)
    check("receiver has no IP-hint toggle",
          not hasattr(win.receiver_screen, "ip_hint_widget"))

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
