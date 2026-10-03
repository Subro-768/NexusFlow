"""Repro: cancel a chunk while the client is still uploading it.

The Hub's Cancel can land in the window between "chunk accepted" and "response
written". If the response is then lost or truncated, the sender reports a
connection error for what is really a deliberate cancel -- the exact failure
this feature is meant to remove.

Drives a raw socket so the body can be trickled and the cancel can be timed to
land mid-write, then reports what the client actually received.
"""

import json
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for candidate in (os.path.join(ROOT, "desktop"), ROOT):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

from embedded_server import EmbeddedReceiverServer  # noqa: E402

PORT = 8937
CHUNK = 1024 * 1024


def raw_post_chunk(host, port, tid, total, payload, cancel_at, cancel_cb):
    """POST one chunk slowly, cancelling mid-body. Returns what came back."""
    head = (
        f"POST /transfer/{tid}/chunk HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Content-Type: application/octet-stream\r\n"
        f"X-Start-Byte: 0\r\n"
        f"X-End-Byte: {len(payload) - 1}\r\n"
        f"X-Total-Size: {total}\r\n"
        f"Content-Length: {len(payload)}\r\n\r\n"
    ).encode()

    sock = socket.create_connection((host, port), timeout=20)
    sock.sendall(head)

    # Trickle the body so the cancel provably lands mid-upload.
    step = len(payload) // 4
    sent = 0
    while sent < len(payload):
        sock.sendall(payload[sent:sent + step])
        sent += step
        if sent >= cancel_at:
            cancel_cb()
            time.sleep(0.05)
    try:
        sock.shutdown(socket.SHUT_WR)
    except OSError:
        pass

    chunks = []
    while True:
        part = sock.recv(65536)
        if not part:
            break
        chunks.append(part)
    sock.close()
    return b"".join(chunks)


def main():
    import tempfile

    tmp = tempfile.mkdtemp(prefix="nf_cancel_race_")
    server = EmbeddedReceiverServer(host="127.0.0.1", port=PORT, upload_dir=tmp)
    assert server.start() is True
    try:
        payload = b"z" * CHUNK
        total = CHUNK * 4
        tid = "race-session"
        server.transfers[tid] = {
            "transfer_id": tid,
            "filename": "race.bin",
            "total_size": total,
            "received_bytes": 0,
            "status": "in_progress",
            "expected_sha256": None,
            "file_path": os.path.join(tmp, "race.bin"),
        }

        fired = threading.Event()

        def cancel():
            if not fired.is_set():
                fired.set()
                server.cancel_transfer(tid)

        raw = raw_post_chunk("127.0.0.1", PORT, tid, total, payload,
                             cancel_at=CHUNK // 2, cancel_cb=cancel)

        print("--- raw response bytes ---")
        print(repr(raw[:400]))
        head_txt, _, body = raw.partition(b"\r\n\r\n")
        status = head_txt.split(b"\r\n")[0].decode(errors="replace")
        print("status line :", status)
        print("body        :", body[:200])
        declared = None
        for line in head_txt.split(b"\r\n"):
            if line.lower().startswith(b"content-length:"):
                declared = int(line.split(b":")[1])
        print("declared len:", declared, "received:", len(body))
        if declared is not None and len(body) != declared:
            print("RESULT: TRUNCATED RESPONSE -- client would report a connection error")
        else:
            try:
                print("json        :", json.loads(body))
            except Exception as exc:
                print("RESULT: UNPARSEABLE BODY:", exc)
        print("session status:", server.transfers[tid]["status"])
        print("partial exists:", os.path.exists(os.path.join(tmp, "race.bin")))
    finally:
        server.stop()


if __name__ == "__main__":
    main()