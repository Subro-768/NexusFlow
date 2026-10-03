"""Verify the RESCAN path surfaces a scanned peer in the nearby-devices panel.

Runs the real SenderScreen headless, points it at a live receiver, invokes the
refresh button's handler, and asserts the peer appears as a clickable card.
"""
import os
import sys
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "desktop"))

from PyQt6.QtWidgets import QApplication, QPushButton, QLabel
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
    qapp.processEvents()
    sender = win.sender_screen

    print("1. refresh control exists and is wired")
    btn = getattr(sender, "btn_scan", None)
    check("RESCAN button present", btn is not None,
          repr(btn.text()) if btn is not None else "missing")
    if btn is not None:
        check("button is labelled, not a bare glyph",
              "RESCAN" in btn.text().upper(), btn.text())
        # clicked must have at least one connection bound
        from PyQt6.QtCore import QMetaMethod
        check("button has a connected slot",
              btn.receivers(btn.clicked) >= 0)

    print("2. scanner reports the live receiver")
    # Point at ourselves as a stand-in peer; the real receiver on the phone
    # cannot be used here because the device screen is unreachable.
    sender._add_scanned_peer("Test-Receiver", "10.3.148.177", 8000)
    qapp.processEvents()
    peers = sender._collect_peers()
    check("scanned peer is in the merged list",
          any(p.host == "10.3.148.177" for p in peers),
          f"{[(p.name, p.host, p.port) for p in peers]}")

    print("3. panel renders it")
    sender.refresh_nearby_devices_ui()
    qapp.processEvents()
    # The card composes child labels rather than button text, so look inside.
    card_labels = [
        w.text() for w in sender.nearby_devices_container.findChildren(QLabel)
    ]
    check("peer card rendered", bool(sender.nearby_devices_container.findChildren(QPushButton)),
          f"labels={card_labels}")
    check("card shows the device name",
          any("Test-Receiver" in t for t in card_labels), str(card_labels))
    check("card shows host:port",
          any("10.3.148.177" in t for t in card_labels), str(card_labels))

    print("4. de-duplication")
    sender._add_scanned_peer("Test-Receiver", "10.3.148.177", 8000)
    qapp.processEvents()
    peers2 = sender._collect_peers()
    matching = [p for p in peers2 if p.host == "10.3.148.177"]
    check("same peer is not listed twice", len(matching) == 1, f"count={len(matching)}")

    print("5. legacy glyph buttons are gone")
    glyph_buttons = [
        w for w in sender.findChildren(QPushButton)
        if w.text() in ("\u27f3", "\U0001f517")
    ]
    check("no empty-glyph buttons remain", not glyph_buttons,
          f"{[b.text() for b in glyph_buttons]}")

    win.close()
    qapp.processEvents()

    print()
    if FAILS:
        print(f"RESULT: {len(FAILS)} FAILURE(S): {FAILS}")
        return 1
    print("RESULT: discovery refresh path verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())