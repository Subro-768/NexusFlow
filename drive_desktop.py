"""Drive the desktop app's real transfer classes headlessly.

Real network, real chunking, real SHA-256, real SQLite state on the desktop
receiver -- only the Qt GUI is offscreen. Used to test transfers end to end
without depending on injected taps (the phone's notification shade has been
swallowing input).
"""
import os
import sys
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "desktop"))

from PyQt6.QtWidgets import QApplication
import app as A


def pump(qapp, seconds, on_tick=None):
    """Spin the event loop, calling on_tick(state) periodically."""
    end = time.time() + seconds
    last = 0.0
    while time.time() < end:
        qapp.processEvents()
        time.sleep(0.05)
        if on_tick and (time.time() - last) >= 1.0:
            last = time.time()
            on_tick()