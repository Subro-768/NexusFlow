"""
subnet_scan.py — TCP subnet probe discovery for NexusFlow.

Why this exists
---------------
Measured on the target network (phone and laptop both on 10.3.0.0/16, gateway
10.3.0.1):

  * mDNS:  no multicast route on either host -> cannot work
  * UDP broadcast: laptop sent 8 datagrams to 255.255.255.255 and
    10.3.255.255; the phone received **none** (the AP does not relay
    client-to-client broadcast)
  * unicast TCP: laptop reached the phone's receiver on 10.3.153.152:8000 in
    0.45s

So the link layer drops anything broadcast-shaped but forwards unicast. The
only discovery method that can work there is to *ask* each address directly.

Performance, measured
---------------------
  * one /24 with 256 concurrent connects: 0.3-0.4s
  * the whole /16: 22.9s

Strategy: probe the local /24 first (instant when the peer is nearby, which is
the common case), then sweep the rest of the /16 in the background and report
peers as they are found. This is a fallback, not a replacement — broadcast and
mDNS are still attempted because they are far cheaper where they do work.
"""

import json
import socket
import threading
import time
import urllib.request
from typing import Callable, List, Optional, Tuple

#: Ports probed. 8000 is the transfer service on both platforms.
DEFAULT_PORTS = (8000,)

#: Concurrency. 256 completes a /24 in well under half a second.
CONCURRENCY = 256

#: Per-attempt connect timeout. 0.35s is robust for mobile hotspots.
CONNECT_TIMEOUT = 0.35

#: Seconds allowed for the HTTP identity probe once a port is open.
HEALTH_TIMEOUT = 1.5


def local_ipv4() -> Optional[str]:
    """This host's primary IPv4 address, used to derive the search space."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            return ip
    except Exception:
        pass

    try:
        import subprocess
        import re
        out = subprocess.check_output(["ip", "route", "get", "1.1.1.1"], text=True)
        m = re.search(r"src\s+([0-9.]+)", out)
        if m:
            ip = m.group(1)
            if not ip.startswith("127."):
                return ip
    except Exception:
        pass

    try:
        import subprocess
        out = subprocess.check_output(["ip", "-4", "-br", "addr"], text=True)
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3:
                iface, addr_cidr = parts[0], parts[2]
                if not iface.startswith(("lo", "docker", "virbr", "wg", "tun")):
                    ip = addr_cidr.split("/")[0]
                    if not ip.startswith("127."):
                        return ip
    except Exception:
        pass

    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127."):
                return ip
    except Exception:
        pass

    return None


def _probe(ip: str, ports: Tuple[int, ...], timeout: float) -> Optional[Tuple[str, int, Optional[dict]]]:
    """Return (ip, port, health_json) if anything answers, else None."""
    for port in ports:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            sock.connect((ip, port))
        except Exception:
            sock.close()
            continue
        sock.close()
        health = None
        try:
            with urllib.request.urlopen(
                f"http://{ip}:{port}/health", timeout=HEALTH_TIMEOUT
            ) as resp:
                health = json.loads(resp.read().decode("utf-8"))
        except Exception:
            pass
        return (ip, port, health)
    return None


def _is_nexusflow(health: Optional[dict]) -> bool:
    """Distinguish a NexusFlow receiver from any other service on the port."""
    if not health:
        return False
    service = str(health.get("service", "")).lower()
    return "nexus" in service or "resumable" in service


class SubnetScanner:
    """Finds NexusFlow receivers by probing addresses directly."""

    def __init__(self):
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._found: List[Tuple[str, int, Optional[dict]]] = []
        self.scanning = False
        self.last_error: Optional[str] = None

    @property
    def found(self) -> List[Tuple[str, int, Optional[dict]]]:
        with self._lock:
            return list(self._found)

    def clear(self):
        with self._lock:
            self._found = []

    def stop(self):
        self._stop.set()

    def start(self,
              on_found: Optional[Callable[[str, int, Optional[dict]], None]] = None,
              on_done: Optional[Callable[[], None]] = None,
              on_progress: Optional[Callable[[int, int], None]] = None,
              ports: Tuple[int, ...] = DEFAULT_PORTS) -> bool:
        """Begin a scan in the background.

        Returns False if a scan is already running.
        """
        if self.scanning:
            return False
        self._stop.clear()
        self.clear()
        self.scanning = True
        self.last_error = None
        self._thread = threading.Thread(
            target=self._run,
            args=(on_found, on_done, on_progress, ports),
            name="nexusflow-subnet-scan",
            daemon=True,
        )
        self._thread.start()
        return True

    def _record(self, ip: str, port: int, health, on_found):
        with self._lock:
            if any(f[0] == ip and f[1] == port for f in self._found):
                return
            self._found.append((ip, port, health))
        if on_found:
            try:
                on_found(ip, port, health)
            except Exception as exc:
                print(f"[SCAN] on_find callback failed: {exc}")

    def _run(self, on_found, on_done, on_progress, ports):
        try:
            me = local_ipv4()
            if not me:
                self.last_error = "Could not determine this machine's IPv4 address."
                return

            octets = me.split(".")
            prefix = ".".join(octets[:3])

            # 1. Local /24 first: sub-second, and the common case.
            total_units = 256
            done_units = 0
            targets = [(f"{prefix}.{i}", ports, CONNECT_TIMEOUT) for i in range(1, 255)]
            for ip, port, health in self._sweep(targets, ports, 0.5):
                if _is_nexusflow(health):
                    self._record(ip, port, health, on_found)
            done_units += 1
            if on_progress:
                on_progress(done_units, total_units)

            if self._stop.is_set():
                return

            # 2. The rest of the /16, a few /24 blocks at a time so progress is
            #    reported steadily rather than in one lump at the end.
            blocks = [b for b in range(0, 256) if f"{prefix}.{b}" != prefix]
            batch_size = 4
            for i in range(0, len(blocks), batch_size):
                if self._stop.is_set():
                    return
                batch_targets = []
                for b in blocks[i:i + batch_size]:
                    block_prefix = ".".join(octets[:2] + [str(b)])
                    batch_targets.extend(
                        (f"{block_prefix}.{h}", ports, CONNECT_TIMEOUT) for h in range(1, 255)
                    )
                for ip, port, health in self._sweep(batch_targets, ports, 0.25):
                    if _is_nexusflow(health):
                        self._record(ip, port, health, on_found)
                done_units += len(blocks[i:i + batch_size])
                if on_progress:
                    on_progress(min(done_units, total_units), total_units)
        except Exception as exc:
            self.last_error = f"{type(exc).__name__}: {exc}"
        finally:
            self.scanning = False
            if on_done:
                try:
                    on_done()
                except Exception as exc:
                    print(f"[SCAN] on_done failed: {exc}")

    @staticmethod
    def _sweep(targets, ports, timeout) -> List[Tuple[str, int, Optional[dict]]]:
        """Probe a list of addresses concurrently. Returns every open port."""
        import concurrent.futures as cf

        results = []
        with cf.ThreadPoolExecutor(max_workers=CONCURRENCY) as ex:
            futures = {ex.submit(_probe, ip, ports, timeout): ip for ip, ports, timeout in targets}
            for fut in cf.as_completed(futures):
                try:
                    hit = fut.result()
                except Exception:
                    hit = None
                if hit:
                    results.append(hit)
        return results


#: Module-level singleton.
scanner = SubnetScanner()