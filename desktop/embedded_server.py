import os
import sys
import io
import json
import time
import socket
import hashlib
import threading
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from typing import Dict, Optional, Callable
from urllib.parse import parse_qsl

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)
from auth_token import TOKEN_HEADER, generate_token, token_from_request, tokens_match

try:
    from storage import record_received_entry
except ImportError:  # package-relative import when imported as desktop.embedded_server
    from desktop.storage import record_received_entry

# The shared transfer-history file is written by this request thread AND by the
# sender UI.  Both go through storage.py, which serialises the read-modify-write
# with an flock + atomic replace, so a receive landing during a send's save can no
# longer be lost.
record_server_history_entry = record_received_entry

class SpeedTracker:
    def __init__(self):
        self.last_time = time.time()
        self.bytes_since_last = 0
        self.current_speed = 0.0
        self.lock = threading.Lock()

    def record_bytes(self, num_bytes: int) -> float:
        with self.lock:
            now = time.time()
            self.bytes_since_last += num_bytes
            elapsed = now - self.last_time
            if elapsed >= 0.5:
                self.current_speed = self.bytes_since_last / elapsed
                self.bytes_since_last = 0
                self.last_time = now
            return self.current_speed


class SpeedTrackerRegistry:
    """One :class:`SpeedTracker` per transfer_id (was a single server-global one).

    The old single tracker mixed bytes from every concurrent transfer, so the
    receiver UI showed a throughput number that belonged to no file in
    particular.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._trackers: Dict[str, SpeedTracker] = {}

    def for_transfer(self, transfer_id: str) -> SpeedTracker:
        with self._lock:
            tracker = self._trackers.get(transfer_id)
            if tracker is None:
                tracker = self._trackers[transfer_id] = SpeedTracker()
            return tracker

    def drop(self, transfer_id: str) -> None:
        with self._lock:
            self._trackers.pop(transfer_id, None)

    def __len__(self) -> int:
        with self._lock:
            return len(self._trackers)

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class EmbeddedReceiverServer:
    def __init__(self, host: str = "0.0.0.0", port: int = 8000, upload_dir: Optional[str] = None):
        self.host = host
        self.port = port
        self.upload_dir = upload_dir or os.path.join(os.path.expanduser("~"), "Downloads", "NexusFlow_Uploads")
        os.makedirs(self.upload_dir, exist_ok=True)
        
        self.server: Optional[ThreadedHTTPServer] = None
        self.thread: Optional[threading.Thread] = None
        self.is_running = False
        self.device_name: str = "NexusFlow Device"

        # Pairing token for this receiver, shown on screen and carried in the QR
        # so a sender can prove it is meant for this device and not merely able
        # to reach it. Regenerated per start: a stale one should not linger.
        self.auth_token: str = generate_token()
        
        # In-memory transfer records
        self._transfers_lock = threading.Lock()
        self.transfers: Dict[str, dict] = {}
        self.state_callback: Optional[Callable[[dict], None]] = None
        # One speed tracker PER transfer (was one shared across all transfers).
        self.speed_trackers = SpeedTrackerRegistry()

    @property
    def speed_tracker(self):
        """Back-compat accessor: tracker for the most recently active transfer."""
        if not self.transfers:
            return None
        tid = next(reversed(self.transfers))
        return self.speed_trackers.for_transfer(tid)

    def _notify_state(self, rec: dict) -> None:
        """Fan a transfer-state record out to the UI callback.

        Called on an HTTP request thread.  The GUI connects this to a Qt signal
        with Qt.QueuedConnection, so the widget work happens on the GUI thread —
        but a raising callback must never kill the request handler.
        """
        if not self.state_callback:
            return
        try:
            self.state_callback(rec)
        except Exception:
            pass

    # ── Receiver-side hold / release / cancel ────────────────────────────────
    #
    # Mirrors the Android receiver so either device can stop a push, and so the
    # 409/410 contract in docs/protocol.md is implemented on both ends rather
    # than only where it was first needed.

    def _refusal_marker_for(self, transfer_id: str) -> Optional[str]:
        """Why this session must not accept a chunk, or None if it may."""
        rec = self.transfers.get(transfer_id)
        if not rec:
            return None
        if rec.get("cancelled"):
            return "transfer_cancelled"
        if rec.get("paused"):
            return "transfer_paused"
        return None

    def pause_transfer(self, transfer_id: str) -> bool:
        with self._transfers_lock:
            rec = self.transfers.get(transfer_id)
            if not rec or rec.get("cancelled") or rec.get("status") == "completed":
                return False
            rec["paused"] = True
            rec["status"] = "paused"
            # Stop reporting the rate of a transfer that is deliberately not
            # moving. A frozen "2.78 MB/s" beside a PAUSED badge reads as a bug,
            # and it would also seed a bogus ETA on resume.
            rec["speed_bytes_sec"] = 0
        self._notify_state(rec)
        return True

    def resume_transfer(self, transfer_id: str) -> bool:
        with self._transfers_lock:
            rec = self.transfers.get(transfer_id)
            if not rec or rec.get("cancelled"):
                return False
            rec["paused"] = False
            rec["status"] = ("completed" if rec["received_bytes"] >= rec["total_size"]
                             else "in_progress")
        self.speed_trackers.drop(transfer_id)  # re-measure rather than reuse the old rate
        self._notify_state(rec)
        return True

    def cancel_transfer(self, transfer_id: str) -> bool:
        """End the session and delete the partial file.

        The destination is preallocated to the full size, so leaving it behind
        would show a full-size file of zeros in the Received list.
        """
        with self._transfers_lock:
            rec = self.transfers.get(transfer_id)
            if not rec:
                return False
            was_complete = rec.get("status") == "completed"
            rec["cancelled"] = True
            rec["paused"] = False
            rec["status"] = "cancelled"
        if not was_complete:
            try:
                os.remove(rec["file_path"])
            except OSError:
                pass
        self.speed_trackers.drop(transfer_id)
        self._notify_state(rec)
        return True

    def start(self) -> bool:
        if self.is_running:
            return True
        try:
            parent = self

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, format, *args):
                    pass # Quiet

                def _drain(self, length: int) -> None:
                    """Consume a rejected request body so the socket stays usable."""
                    remaining = int(length or 0)
                    while remaining > 0:
                        chunk = self.rfile.read(min(65536, remaining))
                        if not chunk:
                            break
                        remaining -= len(chunk)

                def _send_cors(self):
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    self.send_header("Access-Control-Allow-Headers", "*")

                def _authorised(self, query: str) -> bool:
                    """Whether this request may touch transfer data.

                    /health and the landing page stay open on purpose: the
                    pre-flight reachability probe and the browser page both need
                    them, and neither exposes anything a stranger could use.
                    Everything that reads or writes a file needs the token.
                    """
                    bare = query.split('?')[0]
                    if bare in ("/", "/index.html", "/health", "/favicon.ico"):
                        return True
                    supplied = token_from_request(
                        self.headers,
                        dict(parse_qsl(query.split('?', 1)[1])) if '?' in query else None,
                    )
                    if tokens_match(parent.auth_token, supplied):
                        return True
                    self.send_response(401)
                    self.send_header("Content-Type", "application/json")
                    self._send_cors()
                    self.end_headers()
                    self.wfile.write(json.dumps({
                        "error": "unauthorised",
                        "detail": "Pairing token missing or wrong. Scan the QR on "
                                  "this device, or type the code shown in its "
                                  "endpoint panel."
                    }).encode())
                    return False

                def do_OPTIONS(self):
                    self.send_response(200)
                    self._send_cors()
                    self.end_headers()

                def do_GET(self):
                    if not self._authorised(self.path):
                        self._drain(int(self.headers.get("Content-Length") or 0))
                        return
                    path = self.path.split('?')[0]

                    # Browser friendly landing page
                    if path == "/" or path == "/index.html":
                        self.send_response(200)
                        self.send_header("Content-Type", "text/html; charset=utf-8")
                        self._send_cors()
                        self.end_headers()
                        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>NEXUS FLOW Endpoint</title>
    <style>
        body {{
            background: #090C10;
            color: #F8FAFC;
            font-family: system-ui, -apple-system, sans-serif;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            margin: 0;
            text-align: center;
        }}
        .card {{
            background: #151C28;
            border: 1px solid #232D40;
            border-radius: 20px;
            padding: 36px 28px;
            max-width: 440px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.6);
        }}
        h1 {{ margin: 0 0 4px 0; font-size: 26px; }}
        .cyan {{ color: #00D4FF; }}
        .green {{ color: #00E599; }}
        .lime {{ color: #D4FF00; }}
        .pill {{
            display: inline-block;
            background: #1A2232;
            border: 1px solid #232D40;
            border-radius: 12px;
            padding: 10px 18px;
            margin: 18px 0;
            font-family: monospace;
            font-size: 16px;
            font-weight: bold;
        }}
        p {{ color: #94A3B8; font-size: 14px; line-height: 1.6; margin: 8px 0; }}
    </style>
</head>
<body>
    <div class="card">
        <h1><span class="cyan">NEXUS</span> <span class="green">FLOW</span></h1>
        <p style="color:#64748B; font-size:11px; font-weight:bold; letter-spacing:2px; margin-bottom:16px;">RECEIVER ENDPOINT ACTIVE</p>
        <div class="pill"><span class="green">●</span> Target IP: <span class="lime">{self.headers.get('Host', parent.host + ':' + str(parent.port))}</span></div>
        <p>To transfer files, open the <b>Nexus Flow app</b> on your device, enter this target IP, and start transfer.</p>
    </div>
</body>
</html>"""
                        self.wfile.write(html.encode("utf-8"))
                        return

                    if path == "/health":
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self._send_cors()
                        self.end_headers()
                        payload = {
                            "status": "online",
                            "service": "nexus-linux-receiver",
                            "device_name": parent.device_name,
                            "name": parent.device_name,
                            "version": "1.0.0"
                        }
                        self.wfile.write(json.dumps(payload).encode())
                        return

                    if path == "/transfers":
                        # Return history of transfers
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self._send_cors()
                        self.end_headers()
                        res_list = list(parent.transfers.values())
                        self.wfile.write(json.dumps(res_list).encode())
                        return

                    if path.startswith("/transfer/") and path.endswith("/status"):
                        parts = path.strip('/').split('/')
                        if len(parts) == 3:
                            tid = parts[1]
                            rec = parent.transfers.get(tid)
                            if rec:
                                self.send_response(200)
                                self.send_header("Content-Type", "application/json")
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(json.dumps({
                                    "transfer_id": tid,
                                    "filename": rec["filename"],
                                    "received_bytes": rec["received_bytes"],
                                    "total_size": rec["total_size"],
                                    "filesize": rec["total_size"],
                                    "status": rec["status"],
                                    # The sender reports its completion line from
                                    # this payload, so the digest has to be here:
                                    # without it every send ends in "verified=None"
                                    # no matter what the receiver actually checked.
                                    "calculated_sha256": rec.get("calculated_sha256"),
                                    "expected_sha256": rec.get("expected_sha256"),
                                    "sha256_verified": rec.get("sha256_verified"),
                                    # `verified` is what LinuxTransferClient and the
                                    # sender UI read; keep both spellings so either
                                    # side of the pair can ask.
                                    "verified": rec.get("sha256_verified")
                                }).encode())
                                return
                            else:
                                self.send_response(404)
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(b'{"error":"Transfer not found"}')
                                return

                    self.send_response(404)
                    self._send_cors()
                    self.end_headers()

                def do_POST(self):
                    if not self._authorised(self.path):
                        self._drain(int(self.headers.get("Content-Length") or 0))
                        return
                    path = self.path.split('?')[0]
                    
                    if path == "/transfer":
                        # Create session
                        length = int(self.headers.get("Content-Length", 0))
                        body = self.rfile.read(length)
                        data = json.loads(body.decode())
                        
                        filename = data.get("filename", "unknown_file")
                        filesize = data.get("filesize") or data.get("total_size", 0)
                        checksum = data.get("checksum") or data.get("sha256")
                        
                        # Generate transfer ID
                        tid = hashlib.md5(f"{filename}_{filesize}_{time.time()}".encode()).hexdigest()[:16]
                        dest_path = os.path.join(parent.upload_dir, filename)
                        
                        rec = {
                            "transfer_id": tid,
                            "filename": filename,
                            "total_size": filesize,
                            "received_bytes": 0,
                            "status": "pending",
                            "speed_bytes_sec": 0,
                            "expected_sha256": checksum,
                            "file_path": dest_path
                        }
                        
                        # Check existing file size if resuming
                        if os.path.exists(dest_path):
                            existing_sz = os.path.getsize(dest_path)
                            if existing_sz <= filesize:
                                rec["received_bytes"] = existing_sz
                        else:
                            # Touch / create empty file
                            with open(dest_path, "wb") as f:
                                pass
                        
                        parent.transfers[tid] = rec
                        parent._notify_state(rec)

                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self._send_cors()
                        self.end_headers()
                        self.wfile.write(json.dumps({
                            "transfer_id": tid,
                            "filename": filename,
                            "received_bytes": rec["received_bytes"],
                            "total_size": filesize,
                            "filesize": filesize,
                            "status": "in_progress" if rec["received_bytes"] > 0 else "pending"
                        }).encode())
                        return

                    if path.startswith("/transfer/") and path.endswith("/chunk"):
                        parts = path.strip('/').split('/')
                        if len(parts) == 3:
                            tid = parts[1]
                            rec = parent.transfers.get(tid)
                            if not rec:
                                self.send_response(404)
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(b'{"error":"Transfer not found"}')
                                return

                            start_byte = int(self.headers.get("X-Start-Byte", -1))
                            end_byte = int(self.headers.get("X-End-Byte", -1))
                            length = int(self.headers.get("Content-Length", 0))

                            if start_byte < 0 or length == 0:
                                self.send_response(400)
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(b'{"error":"Invalid range or payload"}')
                                return

                            # A deliberate hold/cancel from this device. The body
                            # is drained first: the sender is still streaming, and
                            # closing on it would surface as a broken pipe on
                            # their side instead of the status code explaining
                            # what actually happened. See docs/protocol.md.
                            refusal = parent._refusal_marker_for(tid)
                            if refusal:
                                self._drain(length)
                                code = 410 if refusal == "transfer_cancelled" else 409
                                self.send_response(code)
                                self.send_header("Content-Type", "application/json")
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(json.dumps({
                                    "detail": refusal,
                                    "status": ("CANCELLED" if code == 410 else "PAUSED")
                                }).encode())
                                return

                            chunk_bytes = self.rfile.read(length)

                            # Write directly to destination at offset
                            dest_path = rec["file_path"]
                            try:
                                with open(dest_path, "r+b" if os.path.exists(dest_path) else "wb") as f:
                                    f.seek(start_byte)
                                    f.write(chunk_bytes)
                                    f.flush()
                                    os.fsync(f.fileno())
                            except OSError as exc:
                                # A cancel that lands mid-write removes the
                                # partial underneath us. Answer the refusal so the
                                # sender reports CANCELLED rather than a broken
                                # connection; an unrelated IO failure is a real
                                # 500 and keeps its own detail.
                                was_cancelled = bool(rec.get("cancelled"))
                                self.send_response(410 if was_cancelled else 500)
                                self.send_header("Content-Type", "application/json")
                                self._send_cors()
                                self.end_headers()
                                self.wfile.write(json.dumps({
                                    "detail": "transfer_cancelled" if was_cancelled
                                              else "chunk_write_failed",
                                    "status": "CANCELLED" if was_cancelled else rec.get("status"),
                                    "error": str(exc)
                                }).encode())
                                parent._notify_state(rec)
                                return

                            new_received = max(rec["received_bytes"], start_byte + len(chunk_bytes))
                            rec["received_bytes"] = new_received
                            
                            # Track live speed (per transfer_id, not server-global)
                            speed = parent.speed_trackers.for_transfer(tid).record_bytes(len(chunk_bytes))
                            rec["speed_bytes_sec"] = speed
                            
                            is_complete = (new_received >= rec["total_size"])
                            calculated_sha = None
                            sha_verified = False

                            if is_complete:
                                rec["status"] = "completed"
                                # Calculate SHA-256
                                hasher = hashlib.sha256()
                                with open(dest_path, "rb") as f:
                                    while b := f.read(256 * 1024):
                                        hasher.update(b)
                                calculated_sha = hasher.hexdigest()
                                rec["calculated_sha256"] = calculated_sha
                                if rec.get("expected_sha256"):
                                    sha_verified = (calculated_sha.lower() == rec["expected_sha256"].lower())
                                    rec["sha256_verified"] = sha_verified
                                
                                # Record into history database/JSON. Locked +
                                # atomic, and never raises into the request handler.
                                record_server_history_entry(rec, self.client_address)
                            else:
                                # Same race as on the Android receiver: a chunk
                                # accepted just before Pause arrives here and must
                                # not put the session back into in_progress.
                                if rec.get("cancelled"):
                                    rec["status"] = "cancelled"
                                elif rec.get("paused"):
                                    rec["status"] = "paused"
                                else:
                                    rec["status"] = "in_progress"

                            if rec.get("paused") or rec.get("cancelled"):
                                rec["speed_bytes_sec"] = 0

                            parent._notify_state(rec)

                            self.send_response(200)
                            self.send_header("Content-Type", "application/json")
                            self._send_cors()
                            self.end_headers()
                            self.wfile.write(json.dumps({
                                "transfer_id": tid,
                                "received_bytes": new_received,
                                "total_size": rec["total_size"],
                                "filesize": rec["total_size"],
                                "status": rec["status"],
                                "calculated_sha256": calculated_sha,
                                "sha256_verified": sha_verified
                            }).encode())
                            return

                    # Session control from the sender: cancel a session it is
                    # abandoning. Pause/resume are receiver-driven in the UI, but
                    # both are exposed over HTTP as well so a script can hold a
                    # transfer without touching the Hub.
                    if path.startswith("/transfer/") and (
                            path.endswith("/cancel") or path.endswith("/pause")
                            or path.endswith("/resume")):
                        parts = path.strip('/').split('/')
                        if len(parts) == 3:
                            tid = parts[1]
                            verb = parts[2]
                            action = {
                                "cancel": parent.cancel_transfer,
                                "pause": parent.pause_transfer,
                                "resume": parent.resume_transfer,
                            }[verb]
                            ok = action(tid)
                            self.send_response(200 if ok else 404)
                            self.send_header("Content-Type", "application/json")
                            self._send_cors()
                            self.end_headers()
                            self.wfile.write(json.dumps({
                                "transfer_id": tid,
                                "status": parent.transfers.get(tid, {}).get("status")
                                          if ok else "unknown",
                                "ok": ok
                            }).encode())
                            return

                    self.send_response(404)
                    self._send_cors()
                    self.end_headers()

            self.server = ThreadedHTTPServer((self.host, self.port), Handler)
            self.is_running = True
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            return True
        except Exception as e:
            self.is_running = False
            return False

    def stop(self):
        self.is_running = False
        if self.server:
            try:
                self.server.shutdown()
                self.server.server_close()
            except Exception:
                pass
            self.server = None
        self.thread = None
