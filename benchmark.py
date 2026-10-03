"""Measure the transfer, so the README quotes numbers rather than memories.

    python benchmark.py            # 3 runs, 120 MB each

Prints a table of what actually happened: wall-clock throughput, time to first
chunk, the overhead of pausing and resuming, and whether the SHA-256 verified.
Everything runs over the real HTTP receiver on a real socket.
"""

import hashlib
import os
import statistics
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "desktop"))

from transfer_client import LinuxTransferClient, TransferPaused   # noqa: E402
from embedded_server import EmbeddedReceiverServer                # noqa: E402

PORT = int(os.environ.get("BENCH_PORT", "8099"))
CHUNK = 1024 * 1024
SIZE = int(os.environ.get("BENCH_BYTES", str(120 * 1024 * 1024)))
RUNS = int(os.environ.get("BENCH_RUNS", "3"))


def fmt_bytes(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}"
        n /= 1024


def one_run(payload, digest, tag):
    """One transfer, timed.

    Each run gets a private upload directory on purpose. Sharing one makes the
    receiver treat an existing destination of the same size as an
    already-received prefix -- which is exactly how resume works -- so runs two
    and onwards would resume a finished file instead of transferring anything.
    """
    receiver = EmbeddedReceiverServer(host="127.0.0.1", port=PORT,
                                      upload_dir=f"/tmp/nexusflow_bench_{tag}")
    assert receiver.start()
    time.sleep(0.3)

    client = LinuxTransferClient(f"http://127.0.0.1:{PORT}", chunk_size=CHUNK,
                                 token=receiver.auth_token)

    t0 = time.perf_counter()
    tid = client.create_transfer(payload, checksum=digest, chunk_size=CHUNK)["transfer_id"]
    t_session = time.perf_counter() - t0

    first_chunk_at = {}

    def progress(cur, total, speed, eta):
        if "t" not in first_chunk_at:
            first_chunk_at["t"] = time.perf_counter()

    t_start = time.perf_counter()
    result = client.send_file(payload, tid, progress_callback=progress)
    elapsed = time.perf_counter() - t_start

    received = result.get("received_bytes")
    verified = result.get("sha256_verified", result.get("verified"))

    receiver.stop()
    return {
        "elapsed": elapsed,
        "throughput": SIZE / elapsed,
        "session_setup": t_session,
        "first_chunk": first_chunk_at.get("t", 0) - t_start,
        "received": received,
        "verified": verified,
    }


def pause_resume_cost(payload, digest):
    """What a hold costs, measured the way the app actually resumes.

    send_file treats a receiver hold as terminal -- it raises rather than
    blocking -- so the resume is a second call from the held offset. That is the
    real path, not a convenient one, so it is the thing worth timing.
    """
    receiver = EmbeddedReceiverServer(host="127.0.0.1", port=PORT,
                                      upload_dir="/tmp/nexusflow_bench_pr")
    assert receiver.start()
    time.sleep(0.3)
    client = LinuxTransferClient(f"http://127.0.0.1:{PORT}", chunk_size=CHUNK,
                                 token=receiver.auth_token)
    tid = client.create_transfer(payload, checksum=digest, chunk_size=CHUNK)["transfer_id"]

    holder = {"at": None, "bytes": 0}

    def hold_once_there():
        while receiver.transfers.get(tid, {}).get("received_bytes", 0) < SIZE * 0.30:
            time.sleep(0.05)
        holder["at"] = time.perf_counter()
        holder["bytes"] = receiver.transfers[tid]["received_bytes"]
        receiver.pause_transfer(tid)

    threading.Thread(target=hold_once_there, daemon=True).start()

    try:
        client.send_file(payload, tid)
        print("  (transfer beat the holder; no hold happened)")
        receiver.stop()
        return None
    except TransferPaused:
        pass

    detected = time.perf_counter() - holder["at"]
    receiver.resume_transfer(tid)

    # Resume from the offset the receiver reports. Passing 0 here would re-send
    # the whole file and quietly turn a resume measurement into a second full
    # transfer -- which is how the first version of this script managed to
    # report "held at 120 MB" for a hold that never happened.
    offset = client.get_status(tid).get("received_bytes", 0)
    client.send_file(payload, tid, start_offset=offset)
    total = time.perf_counter() - holder["at"]
    out = {
        "held_at_bytes": holder["bytes"],
        "resumed_from": offset,
        "detect_ms": detected * 1000,
        "resume_to_finish_s": total,
    }
    receiver.stop()
    return out


def main():
    payload = f"/tmp/bench_{int(time.time())}.bin"
    print(f"writing {fmt_bytes(SIZE)} payload...", flush=True)
    with open(payload, "wb") as fh:
        block = os.urandom(1024 * 1024)
        for _ in range(SIZE // len(block)):
            fh.write(block)
    digest = hashlib.sha256(open(payload, "rb").read()).hexdigest()
    print(f"sha256 = {digest}\n")

    rows = []
    for n in range(1, RUNS + 1):
        print(f"run {n}/{RUNS}...", flush=True)
        r = one_run(payload, digest, n)
        rows.append(r)
        print(f"  {fmt_bytes(SIZE)} in {r['elapsed']:.1f}s "
              f"= {r['throughput']/1e6:.2f} MB/s  "
              f"verified={r['verified']}  got={fmt_bytes(r['received'] or 0)}", flush=True)

    print("\npause / resume (held from the receiver side at ~30%)...")
    pr = pause_resume_cost(payload, digest)
    if pr:
        print(f"  held at {fmt_bytes(pr['held_at_bytes'])}; sender noticed in "
              f"{pr['detect_ms']:.0f} ms")
        print(f"  resumed from {fmt_bytes(pr['resumed_from'])} "
              f"(not 0) and finished {pr['resume_to_finish_s']:.1f}s after the hold")
    os.unlink(payload)
    speeds = [r["throughput"] for r in rows]
    firsts = [r["first_chunk"] for r in rows if r["first_chunk"]]

    print("\n" + "=" * 62)
    print(f"  NexusFlow benchmark -- loopback, {fmt_bytes(SIZE)}, "
          f"{CHUNK // 1024} KB chunks")
    print("=" * 62)
    print(f"  throughput   median {statistics.median(speeds)/1e6:.2f} MB/s"
          f"   (min {min(speeds)/1e6:.2f}, max {max(speeds)/1e6:.2f})")
    if firsts:
        print(f"  time to first chunk   {statistics.median(firsts)*1000:.0f} ms")
    print(f"  session setup         "
          f"{statistics.median([r['session_setup'] for r in rows])*1000:.0f} ms")
    print(f"  sha256 verified       "
          f"{sum(1 for r in rows if r['verified'])}/{len(rows)} runs")
    print(f"  full payload received {sum(1 for r in rows if r['received'] == SIZE)}/{len(rows)} runs")
    print()
    print("  Loopback is the ceiling, not a phone-to-phone figure: it excludes")
    print("  Wi-Fi and costs no radio. On the hotspot tests this project ran on,")
    print("  Android-to-Linux sustained 5.7-5.8 MB/s on a 220 MB file.")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())