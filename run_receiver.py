"""Run the desktop receiver (for phone -> laptop) and hold it open.

Binding to 0.0.0.0:8000 with the real HTTP chunk protocol, SQLite persistence
and SHA-256 verification, so a transfer from the phone exercises the genuine
receiver path.
"""
import os
import sys
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "desktop"))

from PyQt6.QtWidgets import QApplication
import app as A

DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 600

qapp = QApplication(sys.argv)
win = A.NexusFlowLinuxApp()
win.show()
qapp.processEvents()

rx = win.receiver_screen
rx.txt_device_name.setText("Laptop")
qapp.processEvents()

# Enable receiving exactly as the button does.
rx.btn_toggle_server.click()
qapp.processEvents()
time.sleep(1.5)
qapp.processEvents()

print(f"[desktop] receiver running={bool(rx.server)} status={rx.lbl_server_status.text()!r}",
      flush=True)
print(f"[desktop] port={A.SETTINGS.port} upload_dir={rx.server.upload_dir if rx.server else None}",
      flush=True)

end = time.time() + DURATION
last_report = 0
while time.time() < end:
    qapp.processEvents()
    time.sleep(0.2)
    now = time.time()
    if now - last_report >= 5:
        last_report = now
        try:
            rec = rx._last_rx_state
            if rec:
                print(f"[desktop] rx: {rec.get('filename')} "
                      f"{rec.get('received_bytes')}/{rec.get('total_size')} "
                      f"status={rec.get('status')}", flush=True)
        except Exception as exc:
            print(f"[desktop] state read failed: {exc}", flush=True)

print("[desktop] receiver done", flush=True)
