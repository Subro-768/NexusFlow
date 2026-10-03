"""Send a real file through the desktop app's real sender stack.

Uses LinuxTransferClient + TransferWorker exactly as the GUI does: chunked HTTP
PUTs with X-Start-Byte/X-End-Byte headers, resume offset negotiation, and
server-side SHA-256 verification. Headless (offscreen Qt), real network.

usage: run_sender.py <file> <host> [port]
"""
import hashlib
import os
import sys
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "desktop"))

from PyQt6.QtWidgets import QApplication
import app as A
from transfer_client import LinuxTransferClient

SRC = os.path.abspath(sys.argv[1])
HOST = sys.argv[2]
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 8000

size = os.path.getsize(SRC)
print(f"[tx] source  {SRC} ({size} bytes)", flush=True)
print(f"[tx] target  http://{HOST}:{PORT}", flush=True)

qapp = QApplication(sys.argv)
win = A.NexusFlowLinuxApp()
win.show()
qapp.processEvents()

client = LinuxTransferClient(f"http://{HOST}:{PORT}")
worker = A.TransferWorker(client, SRC)

outcome = {"kind": None}


def on_progress(curr, total, spd, eta):
    pct = 100.0 * curr / total if total else 0.0
    if int(pct) % 10 == 0:
        print(f"[tx] {pct:5.1f}%  {spd/1e6:6.2f} MB/s  eta {eta:5.1f}s", flush=True)


def on_status(stage, msg):
    print(f"[tx] [{stage}] {msg}", flush=True)


def on_done(res):
    outcome["kind"] = "completed"
    outcome["res"] = res
    print(f"[tx] COMPLETED result={res}", flush=True)


def on_error(msg):
    outcome["kind"] = "error"
    outcome["err"] = msg
    print(f"[tx] ERROR {msg}", flush=True)


worker.progress_signal.connect(on_progress)
worker.status_signal.connect(on_status)
worker.completed_signal.connect(on_done)
worker.error_signal.connect(on_error)

t0 = time.time()
worker.start()
while not worker.isFinished():
    qapp.processEvents()
    time.sleep(0.05)
qapp.processEvents()
time.sleep(0.5)
elapsed = time.time() - t0

print(f"[tx] finished in {elapsed:.1f}s outcome={outcome['kind']}", flush=True)
if outcome["kind"] == "completed":
    res = outcome.get("res") or {}
    print(f"[tx] verified={res.get('verified')} "
          f"received={res.get('received_bytes')}/{res.get('total_size') or size}", flush=True)
    mbps = size / elapsed / 1e6
    print(f"[tx] throughput {mbps:.2f} MB/s", flush=True)

sys.exit(0 if outcome["kind"] == "completed" else 1)