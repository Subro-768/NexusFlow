"""
lan_discovery.py — UDP broadcast peer discovery for NexusFlow.

Why this exists alongside mDNS
------------------------------
mDNS (multicast 224.0.0.251) does not work reliably on an Android hotspot.
The SoftAP does not forward multicast between the AP interface and clients,
and on the device being tested there was no multicast route at all:

    $ adb shell ip route show 224.0.0.0/4      # (no output)

Multicast also fails across most WiFi access points, so two laptops on a
coffee-shop network would have the same problem. UDP *broadcast* to
255.255.255.255 is handled by the local link and does work on a hotspot,
because the phone (as gateway) receives it locally and its listener is bound
to 0.0.0.0.

So discovery runs over both mechanisms and the results are merged. mDNS is
kept for real LANs where it is cheaper; broadcast is the fallback that makes
hotspot and phone-to-phone transfers actually work.

Wire protocol (all ASCII, one JSON object per datagram)
------------------------------------------------------
  query  -> {"t":"q"}                    "who is out there?"
  hello  -> {"t":"h","n":name,"p":port,"v":1,"id":id}

A responder replies to a query with a unicast `hello` to the sender *and*
broadcasts one, so a passive listener also learns about the peer. Receivers
running with receiving enabled also announce themselves every few seconds, so
discovery converges even if a query is lost.
"""

import json
import socket
import threading
import time
from typing import Callable, Dict, List, Optional, Set

#: UDP port for discovery. Deliberately separate from the HTTP transfer port
#: (8000) so a busy transfer cannot delay or corrupt announcements.
DISCOVERY_PORT = 47777

#: Seconds between self-announcements while receiving is enabled.
ANNOUNCE_INTERVAL = 3.0

#: Seconds between periodic queries from a sender.
SCAN_INTERVAL = 4.0

#: How long a peer stays in the table without a fresh announcement.
PEER_TIMEOUT = 12.0

_BROADCAST = "255.255.255.255"


class LanPeer:
    """A peer seen over UDP broadcast discovery."""

    def __init__(self, name: str, host: str, port: int, key: str):
        self.name = name
        self.host = host
        self.port = port
        self.key = key
        self.last_seen = time.monotonic()

    def __repr__(self):
        return f"LanPeer(name={self.name!r}, host={self.host}, port={self.port})"


def broadcast_addresses() -> List[str]:
    """Addresses worth broadcasting to.

    255.255.255.255 is the limited broadcast and needs no route. Each
    interface's own subnet broadcast is also included because some access
    points drop the limited broadcast but pass the directed one.
    """
    out = [_BROADCAST]
    try:
        # Pass a dummy port so getaddrinfo returns proper (host, port) sockaddr
        # tuples. With port=None the sockaddr entry is a bare string, and
        # indexing [0] on it yields a character rather than an octet.
        for info in socket.getaddrinfo(socket.gethostname(), 0, socket.AF_INET):
            addr = str(info[4][0])
            parts = addr.split(".")
            if len(parts) == 4 and not addr.startswith("127."):
                out.append(".".join(parts[:3]) + ".255")
    except Exception:
        pass
    # Preserve order, drop duplicates.
    seen: Set[str] = set()
    unique = []
    for a in out:
        if a not in seen:
            seen.add(a)
            unique.append(a)
    return unique


def local_ip() -> str:
    """Best-guess local address, used as our own identity in announcements."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"


class _Responder(threading.Thread):
    """Listens for queries and answers with this device's identity.

    Runs only while the local device has receiving enabled.
    """

    daemon = True

    def __init__(self, name: str, port: int, own_ips: Set[str],
                 on_peer: Optional[Callable[[LanPeer], None]] = None):
        super().__init__(name="nexusflow-responder")
        self.device_name = name or "NexusFlow Device"
        self.service_port = port
        self.own_ips = set(own_ips)
        self.on_peer = on_peer
        self._running = threading.Event()
        self._sock: Optional[socket.socket] = None

    # -- helpers ---------------------------------------------------------
    def _identity(self) -> dict:
        return {
            "t": "h",
            "n": self.device_name,
            "p": self.service_port,
            "v": 1,
            "id": local_ip(),
        }

    def _send(self, payload: dict, dest: tuple):
        if not self._sock:
            return
        try:
            self._sock.sendto(json.dumps(payload).encode("utf-8"), dest)
        except OSError:
            pass

    def _broadcast_hello(self):
        payload = self._identity()
        for addr in broadcast_addresses():
            self._send(payload, (addr, DISCOVERY_PORT))

    # -- lifecycle -------------------------------------------------------
    def stop(self):
        self._running.clear()
        try:
            if self._sock:
                self._sock.close()
        except Exception:
            pass
        self._sock = None

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock = sock
        # SO_REUSEADDR lets the browser below share the port on some platforms.
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            pass
        # Bind to all interfaces: on a hotspot the relevant address is the
        # SoftAP one, which is not necessarily the primary interface.
        try:
            sock.bind(("", DISCOVERY_PORT))
        except OSError as exc:
            print(f"[LAN] responder could not bind {DISCOVERY_PORT}: {exc}")
            return

        sock.settimeout(1.0)
        self._running.set()
        next_announce = 0.0

        while self._running.is_set():
            now = time.monotonic()
            if now >= next_announce:
                self._broadcast_hello()
                next_announce = now + ANNOUNCE_INTERVAL

            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            try:
                msg = json.loads(data.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue

            kind = msg.get("t")
            if kind == "q":
                # Answer directly, then broadcast so passive listeners see us.
                peer_host = addr[0]
                if peer_host not in self.own_ips:
                    self._send(self._identity(), (peer_host, DISCOVERY_PORT))
                self._broadcast_hello()
            elif kind == "h":
                host = msg.get("id") or addr[0]
                # Ignore our own announcements.
                if host in self.own_ips or addr[0] in self.own_ips:
                    continue
                peer = LanPeer(
                    name=str(msg.get("n") or host),
                    host=host,
                    port=int(msg.get("p") or 8000),
                    key=f"{msg.get('n') or host}|{host}",
                )
                if self.on_peer:
                    try:
                        self.on_peer(peer)
                    except Exception as exc:
                        print(f"[LAN] on_peer callback failed: {exc}")


class LanDiscovery:
    """Sender-side browser: asks who is out there and tracks the answers."""

    def __init__(self):
        self._peers: Dict[str, LanPeer] = {}
        self._lock = threading.Lock()
        self._responder: Optional[_Responder] = None
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._own_ips: Set[str] = set()
        self.on_change: Optional[Callable[[List[LanPeer]], None]] = None
        self.last_error: Optional[str] = None
        self.available = False

    # -- responder (receiver side) ---------------------------------------
    def advertise(self, device_name: str, service_port: int,
                  own_ips: Optional[List[str]] = None) -> bool:
        """Start announcing this device. Call when receiving is enabled."""
        self._own_ips = set(own_ips or [local_ip()])
        self.stop_advertise()
        self._responder = _Responder(
            name=device_name,
            port=service_port,
            own_ips=self._own_ips,
            on_peer=self._note_peer,
        )
        self._responder.start()
        # Give the socket a moment so an immediate scan sees us.
        time.sleep(0.2)
        self.available = True
        self.last_error = None
        print(f"[LAN] Advertising '{device_name}' on port {service_port}")
        return True

    def stop_advertise(self):
        if self._responder:
            self._responder.stop()
            self._responder = None

    # -- browser (sender side) -------------------------------------------
    def start(self, own_ips: Optional[List[str]] = None) -> bool:
        """Start scanning for peers in a background thread."""
        self._own_ips = set(own_ips or [local_ip()])
        self.stop()
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._loop, name="nexusflow-lan-scan", daemon=True
        )
        self._thread.start()
        self.available = True
        self.last_error = None
        return True

    def stop(self):
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        self.stop_advertise()

    def scan_now(self):
        """Trigger an immediate query (used by the Refresh button)."""
        self._query()

    def _note_peer(self, peer: LanPeer):
        with self._lock:
            self._peers[peer.key] = peer
        self._fire()

    def _fire(self):
        if self.on_change:
            try:
                self.on_change(self.get_peers())
            except Exception as exc:
                print(f"[LAN] on_change failed: {exc}")

    def _query(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(0.4)
            payload = json.dumps({"t": "q"}).encode("utf-8")
            for addr in broadcast_addresses():
                try:
                    sock.sendto(payload, (addr, DISCOVERY_PORT))
                except OSError:
                    continue
            # Collect replies for a short window.
            deadline = time.monotonic() + 1.2
            while time.monotonic() < deadline:
                try:
                    data, addr = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    msg = json.loads(data.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    continue
                if msg.get("t") != "h":
                    continue
                host = msg.get("id") or addr[0]
                if host in self._own_ips or addr[0] in self._own_ips:
                    continue
                peer = LanPeer(
                    name=str(msg.get("n") or host),
                    host=host,
                    port=int(msg.get("p") or 8000),
                    key=f"{msg.get('n') or host}|{host}",
                )
                with self._lock:
                    self._peers[peer.key] = peer
            self._fire()
        finally:
            sock.close()

    def _loop(self):
        while not self._stop_event.is_set():
            self._query()
            # Expire peers that stopped announcing.
            cutoff = time.monotonic() - PEER_TIMEOUT
            with self._lock:
                stale = [k for k, v in self._peers.items() if v.last_seen < cutoff]
                for k in stale:
                    self._peers.pop(k, None)
            if stale:
                self._fire()
            self._stop_event.wait(SCAN_INTERVAL)

    def get_peers(self) -> List[LanPeer]:
        with self._lock:
            return list(self._peers.values())


#: Module-level singleton, mirroring nsd_helper's design.
lan = LanDiscovery()