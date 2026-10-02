"""
nsd_helper.py — mDNS/DNS-SD service advertising and discovery for NexusFlow.

Uses the `zeroconf` library to broadcast `_nexusflow._tcp.local.` when
receiving is enabled, and to discover nearby devices for the sender.
"""

import socket
import threading
import time
from typing import Callable, Dict, List, Optional

try:
    from zeroconf import ServiceBrowser, ServiceInfo, Zeroconf
    ZEROCONF_AVAILABLE = True
except ImportError:
    ZEROCONF_AVAILABLE = False

#: Human-readable reason discovery is unavailable, or None when it is available.
ZEROCONF_UNAVAILABLE_REASON = None if ZEROCONF_AVAILABLE else (
    "The 'zeroconf' Python package is not installed — mDNS device discovery is "
    "disabled. Install it with: pip install zeroconf"
)


SERVICE_TYPE = "_nexusflow._tcp.local."


def _get_local_ip() -> str:
    """Get the best-guess local LAN IP."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class PeerInfo:
    """Represents a discovered NexusFlow device on the LAN."""
    def __init__(self, name: str, host: str, port: int):
        self.name = name
        self.host = host
        self.port = port

    def __repr__(self):
        return f"PeerInfo(name={self.name!r}, host={self.host}, port={self.port})"


class NsdAdvertiser:
    """
    Registers this device as a NexusFlow receiver on the LAN via mDNS.
    Call start() when receiving is enabled and stop() when disabled.
    """

    def __init__(self):
        self._zeroconf: Optional[Zeroconf] = None
        self._info: Optional[ServiceInfo] = None
        self._lock = threading.Lock()
        self.last_error: Optional[str] = ZEROCONF_UNAVAILABLE_REASON
        self.available: bool = ZEROCONF_AVAILABLE

    def start(self, device_name: str, port: int = 8000) -> bool:
        """Register the mDNS service. Returns True on success."""
        if not ZEROCONF_AVAILABLE:
            self.last_error = ZEROCONF_UNAVAILABLE_REASON
            self.available = False
            print(f"[NSD] {self.last_error}")
            return False

        with self._lock:
            self.stop_locked()
            local_ip = _get_local_ip()
            try:
                # mDNS service name must be unique; zeroconf will auto-suffix if collision
                safe_name = device_name.strip()[:63] or "NexusFlow Device"
                self._info = ServiceInfo(
                    type_=SERVICE_TYPE,
                    name=f"{safe_name}.{SERVICE_TYPE}",
                    addresses=[socket.inet_aton(local_ip)],
                    port=port,
                    properties={"version": "1", "app": "nexusflow"},
                    server=f"{socket.gethostname()}.local.",
                )
                self._zeroconf = Zeroconf()
                self._zeroconf.register_service(self._info)
                self.last_error = None
                self.available = True
                print(f"[NSD] Broadcasting as '{safe_name}' at {local_ip}:{port}")
                return True
            except Exception as e:
                self.last_error = f"{type(e).__name__}: {e}"
                print(f"[NSD] Failed to register service: {self.last_error}")
                self.available = False
                self._zeroconf = None
                self._info = None
                return False

    def stop(self):
        with self._lock:
            self.stop_locked()

    def stop_locked(self):
        """Stop broadcasting (must be called with lock held)."""
        if self._zeroconf and self._info:
            try:
                self._zeroconf.unregister_service(self._info)
            except Exception:
                pass
        if self._zeroconf:
            try:
                self._zeroconf.close()
            except Exception:
                pass
        self._zeroconf = None
        self._info = None


class _NexusFlowListener:
    """Internal ServiceBrowser listener that tracks available peers."""

    def __init__(self, own_ips: List[str]):
        self._own_ips = set(own_ips)
        self._peers: Dict[str, PeerInfo] = {}
        self._lock = threading.Lock()
        self._on_change: Optional[Callable] = None
        #: Set when a change callback raised, so the failure is not lost.
        self.last_callback_error: Optional[str] = None

    def set_on_change(self, callback: Callable):
        self._on_change = callback

    def _fire_change(self) -> None:
        """Invoke the on_change callback. Runs on the zeroconf listener thread."""
        if not self._on_change:
            return
        try:
            self._on_change(self.get_peers())
        except Exception as exc:
            # A raising callback must not kill the listener thread.
            self.last_callback_error = f"{type(exc).__name__}: {exc}"

    def add_service(self, zc: "Zeroconf", type_: str, name: str):
        info = zc.get_service_info(type_, name)
        if info is None:
            return
        host = socket.inet_ntoa(info.addresses[0]) if info.addresses else None
        if not host or host in self._own_ips:
            return
        # Strip the service type suffix from the display name
        display_name = name.replace(f".{SERVICE_TYPE}", "").replace("._nexusflow._tcp.local.", "")
        peer = PeerInfo(name=display_name, host=host, port=info.port)
        with self._lock:
            self._peers[name] = peer
        self._fire_change()

    def update_service(self, zc: "Zeroconf", type_: str, name: str):
        self.add_service(zc, type_, name)

    def remove_service(self, zc: "Zeroconf", type_: str, name: str):
        with self._lock:
            self._peers.pop(name, None)
        self._fire_change()

    def get_peers(self) -> List[PeerInfo]:
        with self._lock:
            return list(self._peers.values())


class NsdDiscovery:
    """
    Browses the LAN for NexusFlow devices advertising themselves as receivers.
    Call start() to begin scanning and get_peers() to retrieve the current list.
    Provide an on_change callback to be notified when the peer list updates.
    """

    def __init__(self):
        self._zeroconf: Optional[Zeroconf] = None
        self._browser = None
        self._listener: Optional[_NexusFlowListener] = None
        self._lock = threading.Lock()
        self.last_error: Optional[str] = ZEROCONF_UNAVAILABLE_REASON
        self.available: bool = ZEROCONF_AVAILABLE

    def start(self, own_ips: Optional[List[str]] = None,
              on_change: Optional[Callable[[List[PeerInfo]], None]] = None) -> bool:
        """Start discovering NexusFlow receivers on the LAN.

        Returns True when the browser is actually running.  Callers must check
        the return value (or :attr:`available`) — a bare ``if discovery:`` guard
        is always true because this is a module-level singleton.
        """
        if not ZEROCONF_AVAILABLE:
            self.last_error = ZEROCONF_UNAVAILABLE_REASON
            self.available = False
            print(f"[NSD] {self.last_error}")
            return False

        with self._lock:
            self.stop_locked()
            all_own_ips = list(own_ips or [_get_local_ip()])
            try:
                self._listener = _NexusFlowListener(own_ips=all_own_ips)
                if on_change:
                    self._listener.set_on_change(on_change)
                self._zeroconf = Zeroconf()
                self._browser = ServiceBrowser(self._zeroconf, SERVICE_TYPE, self._listener)
                self.last_error = None
                self.available = True
                print(f"[NSD] Discovery started (own IPs: {all_own_ips})")
                return True
            except Exception as e:
                self.last_error = f"{type(e).__name__}: {e}"
                self.available = False
                print(f"[NSD] Failed to start discovery: {self.last_error}")
                self._zeroconf = None
                self._browser = None
                self._listener = None
                return False

    @property
    def is_running(self) -> bool:
        return self._zeroconf is not None and self._browser is not None

    def get_peers(self) -> List[PeerInfo]:
        with self._lock:
            if self._listener:
                return self._listener.get_peers()
        return []

    def stop(self):
        with self._lock:
            self.stop_locked()

    def stop_locked(self):
        self._browser = None
        if self._zeroconf:
            try:
                self._zeroconf.close()
            except Exception:
                pass
        self._zeroconf = None
        self._listener = None


# ── Module-level singletons for app.py to import directly ────────────────────
advertiser = NsdAdvertiser()
discovery = NsdDiscovery()
