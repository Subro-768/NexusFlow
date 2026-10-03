"""On-device repro: cancel a chunk while the client is still uploading it.

Same shape as tests/repro_cancel_race.py, but pointed at the Android receiver so
the actual bytes it sends back can be inspected. Run with:

    python tests/repro_cancel_race_device.py http://10.3.150.116:8000
"""

import json
import socket
import sys
import threading
import time
import urllib.request

CHUNK = 1024 * 1024


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://10.3.150.116:8000").rstrip("/")
    host_port = base.split("//", 1)[1]
    host, port = host_port.split(":")
    port = int(port)
    total = CHUNK * 4

    req = urllib.request.Request(
        f"{base}/transfer",
        data=json.dumps({"filename": "race_probe.bin", "filesize": total,
                         "checksum": None, "chunk_size": CHUNK}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    tid = json.load(urllib.request.urlopen(req))["transfer_id"]
    print("session:", tid)

    payload = b"z" * CHUNK
    head = (
        f"POST /transfer/{tid}/chunk HTTP/1.1\r\n"
        f"Host: {host_port}\r\n"
        "Content-Type: application/octet-stream\r\n"
        "X-Start-Byte: 0\r\n"
        f"X-End-Byte: {CHUNK - 1}\r\n"
        f"X-Total-Size: {total}\r\n"
        f"Content-Length: {CHUNK}\r\n\r\n"
    ).encode()

    sock = socket.create_connection((host, port), timeout=25)
    sock.sendall(head)

    def cancel_midway():
        # A separate connection, exactly like the notification's Cancel action.
        time.sleep(0.15)
        urllib.request.urlopen(
            urllib.request.Request(f"{base}/transfer/{tid}/cancel", data=b"", method="POST")
        ).read()
        print("cancel sent mid-upload")

    threading.Thread(target=cancel_midway, daemon=True).start()

    step = CHUNK // 4
    sent = 0
    while sent < CHUNK:
        try:
            sock.sendall(payload[sent:sent + step])
        except BrokenPipeError:
            # The receiver closed mid-upload; whatever it sent before that is
            # still worth reading.
            print("client write refused after", sent, "bytes; reading what came back")
            break
        sent += step
        time.sleep(0.08)
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

    raw = b"".join(chunks)
    print("--- raw response ---")
    print(repr(raw[:500]))
    head_txt, _, body = raw.partition(b"\r\n\r\n")
    print("status :", head_txt.split(b"\r\n")[0].decode(errors="replace"))
    declared = None
    for line in head_txt.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            declared = int(line.split(b":")[1])
    print("declared:", declared, "received:", len(body))
    if declared is not None and len(body) != declared:
        print("RESULT: TRUNCATED RESPONSE")
    else:
        try:
            print("json   :", json.loads(body))
        except Exception as exc:
            print("RESULT: UNPARSEABLE:", exc)

    st = json.load(urllib.request.urlopen(f"{base}/transfer/{tid}/status"))
    print("session status:", st["status"], st["received_bytes"])


if __name__ == "__main__":
    main()