import os
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

HISTORY_FILE = os.path.join(os.path.expanduser("~"), ".nexusflow_history.json")

def record_server_history_entry(rec: dict, client_address: tuple):
    try:
        history = []
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, "r") as f:
                history = json.load(f)
        
        entry = {
            "filename": rec.get("filename", "unknown"),
            "size": rec.get("total_size", 0),
            "sha256": rec.get("calculated_sha256", ""),
            "status": "completed",
            "timestamp": datetime.now().isoformat(),
            "transfer_id": rec.get("transfer_id", ""),
            "target_ip": f"Received from {client_address[0] if client_address else 'device'}",
            "direction": "received",
            "file_path": rec.get("file_path", "")
        }
        history.insert(0, entry)
        history = history[:100]
        with open(HISTORY_FILE, "w") as f:
            json.dump(history, f, indent=2)
    except Exception:
        pass

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
        
        # In-memory transfer records
        self.transfers: Dict[str, dict] = {}
        self.state_callback: Optional[Callable[[dict], None]] = None
        self.speed_tracker = SpeedTracker()

    def start(self) -> bool:
        if self.is_running:
            return True
        try:
            parent = self

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, format, *args):
                    pass # Quiet

                def _send_cors(self):
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    self.send_header("Access-Control-Allow-Headers", "*")

                def do_OPTIONS(self):
                    self.send_response(200)
                    self._send_cors()
                    self.end_headers()

                def do_GET(self):
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
        <div class="pill"><span class="green">●</span> Target IP: <span class="lime">{self.headers.get('Host', parent.host + ':8000')}</span></div>
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
                                    "status": rec["status"]
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
                        if parent.state_callback:
                            parent.state_callback(rec)

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

                            chunk_bytes = self.rfile.read(length)
                            
                            # Write directly to destination at offset
                            dest_path = rec["file_path"]
                            with open(dest_path, "r+b" if os.path.exists(dest_path) else "wb") as f:
                                f.seek(start_byte)
                                f.write(chunk_bytes)
                                f.flush()
                                os.fsync(f.fileno())

                            new_received = max(rec["received_bytes"], start_byte + len(chunk_bytes))
                            rec["received_bytes"] = new_received
                            
                            # Track live speed
                            speed = parent.speed_tracker.record_bytes(len(chunk_bytes))
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
                                
                                # Record into history database/JSON
                                record_server_history_entry(rec, self.client_address)
                            else:
                                rec["status"] = "in_progress"

                            if parent.state_callback:
                                parent.state_callback(rec)

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
