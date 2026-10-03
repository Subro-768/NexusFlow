import sys
import os
import io
import socket
import json
import subprocess
import re
import time
import qrcode
import threading
from PIL import Image
from dataclasses import dataclass, asdict, fields
from datetime import datetime

from PyQt6.QtCore import Qt, QThread, QObject, pyqtSignal, pyqtSlot, QTimer, QSettings, QSize, QEvent
from PyQt6.QtGui import (
    QFont, QColor, QPixmap, QIcon, QGuiApplication, QFontMetrics,
    QKeySequence, QShortcut, QCursor,
)
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QFileDialog, QProgressBar, QStackedWidget,
    QFrame, QLineEdit, QScrollArea, QMessageBox,
    QSizePolicy, QSpinBox, QCheckBox
)

# Import transfer_client, embedded_server and nsd_helper (they sit next to this file)
try:
    from transfer_client import (
        LinuxTransferClient, TransferPaused, TransferCancelled, TransferError,
        DEFAULT_CHUNK_SIZE,
    )
    from embedded_server import EmbeddedReceiverServer
    from nsd_helper import (
        advertiser as nsd_advertiser, discovery as nsd_discovery,
        ZEROCONF_AVAILABLE, ZEROCONF_UNAVAILABLE_REASON,
    )
except ImportError:
    sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from desktop.transfer_client import (
        LinuxTransferClient, TransferPaused, TransferCancelled, TransferError,
        DEFAULT_CHUNK_SIZE,
    )
    from desktop.embedded_server import EmbeddedReceiverServer
    try:
        from desktop.nsd_helper import (
            advertiser as nsd_advertiser, discovery as nsd_discovery,
            ZEROCONF_AVAILABLE, ZEROCONF_UNAVAILABLE_REASON,
        )
    except ImportError:
        nsd_advertiser = None
        nsd_discovery = None
        ZEROCONF_AVAILABLE = False
        ZEROCONF_UNAVAILABLE_REASON = "mDNS discovery module could not be imported."

# UDP broadcast discovery. Independent of zeroconf and the primary mechanism:
# mDNS does not traverse an Android hotspot, broadcast does. See
# lan_discovery.py for the full rationale.
try:
    from lan_discovery import lan
except ImportError:
    try:
        from desktop.lan_discovery import lan
    except ImportError:  # discovery degrades to a manual-IP fallback
        lan = None

try:
    from nsd_helper import PeerInfo
except ImportError:
    try:
        from desktop.nsd_helper import PeerInfo
    except ImportError as _peer_err:  # pragma: no cover
        # Discovery degrades to manual IP entry rather than failing to import.
        PeerInfo = None

# TCP subnet probe. On the measured target network neither multicast nor
# broadcast crosses the AP, but unicast does -- so asking each address
# directly is the only method that can work there.
try:
    from subnet_scan import scanner
except ImportError:
    try:
        from desktop.subnet_scan import scanner
    except ImportError:
        scanner = None

# Persistence layer: locked, cached, atomic (see desktop/storage.py).
try:
    from storage import (
        load_history, save_history_entry, append_history_entry, clear_history,
        load_recent_ips, save_recent_ip, load_device_name, save_device_name,
        last_error as last_storage_error, subscribe_errors,
    )
except ImportError:
    from desktop.storage import (
        load_history, save_history_entry, append_history_entry, clear_history,
        load_recent_ips, save_recent_ip, load_device_name, save_device_name,
        last_error as last_storage_error, subscribe_errors,
    )
    HISTORY_FILE = os.path.join(os.path.expanduser("~"), ".nexusflow_history.json")

# ─── Palette ──────────────────────────────────────────────────────────────────
SOLORA_BG               = "#090C10"
SOLORA_SURFACE          = "#121824"
SOLORA_SURFACE_CARD     = "#151C28"
SOLORA_SURFACE_ELEVATED = "#1A2232"
SOLORA_BORDER           = "#232D40"
SOLORA_NEON_LIME        = "#D4FF00"
SOLORA_ENERGY_GREEN     = "#00E599"
SOLORA_CYAN             = "#00D4FF"
SOLORA_AMBER            = "#FFB703"
SOLORA_ALERT_RED        = "#FF4D4D"
SOLORA_TEXT_PRIMARY     = "#F8FAFC"
SOLORA_TEXT_SECONDARY   = "#94A3B8"
# Was #64748B (3.35:1 on SURFACE_ELEVATED / 3.59:1 on SURFACE_CARD — below WCAG AA
# for the 7.5–8.5pt meta text that uses it). Lightened one step to #7C8CA1, which
# clears 4.5:1 on both surfaces (4.64 / 4.98).
SOLORA_TEXT_MUTED       = "#7C8CA1"

# ─── Type scale ───────────────────────────────────────────────────────────────
# Named size steps. Qt QSS does support letter-spacing, so the 29 existing
# letter-spacing usages are left untouched.
FS_HERO      = "20pt"
FS_TITLE     = "18pt"
FS_XL        = "16pt"
FS_DISPLAY   = "14pt"
FS_LG        = "13pt"
FS_HEADING   = "11pt"
FS_SUBHEAD   = "10.5pt"
FS_BODY      = "10pt"
FS_BODY_SM   = "9.5pt"
FS_SMALL     = "9pt"
FS_META      = "8.5pt"
FS_META_SM   = "8pt"
FS_META_XS   = "7.5pt"
FS_MICRO     = "7pt"

FONT_STACK = "system-ui, 'Noto Sans', 'DejaVu Sans', -apple-system, sans-serif"
FONT_MONO  = "monospace"

DRAWER_WIDTH = 260
DEFAULT_WINDOW_SIZE = (1080, 720)
MIN_WINDOW_SIZE = (860, 620)
NEXUS_SCHEME = "nexus"
PROGRESS_THROTTLE_HZ = 20.0


def alpha(color: str, a: float) -> str:
    """``alpha(SOLORA_CYAN, 0.3)`` -> ``'rgba(0, 212, 255, 0.3)'``.

    Every translucent palette literal goes through here so a palette change can
    never leave a hardcoded rgba() behind (the old code had 10 of them).
    """
    h = (color or "").lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    if len(h) != 6:
        raise ValueError(f"not a 6-digit hex colour: {color!r}")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return f"rgba({r}, {g}, {b}, {a:g})"


def mix(color_a: str, color_b: str, t: float) -> str:
    """Blend two hex colours; ``t=0`` -> a, ``t=1`` -> b. Used for hover tints."""
    def rgb(h):
        h = h.lstrip("#")
        return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    ar, ag, ab = rgb(color_a)
    br, bg_, bb = rgb(color_b)
    return "#%02X%02X%02X" % (
        round(ar + (br - ar) * t),
        round(ag + (bg_ - ag) * t),
        round(ab + (bb - ab) * t),
    )


def relative_luminance(color: str) -> float:
    h = (color or "").lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    chans = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255.0
        chans.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    r, g, b = chans
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(fg: str, bg: str) -> float:
    """WCAG 2.1 contrast ratio between two hex colours."""
    l1, l2 = relative_luminance(fg), relative_luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


# ─── QSS helpers (de-duplication of repeated inline stylesheet blocks) ─────────
def badge_qss(bg: str, fg: str, size: str = FS_META_SM) -> str:
    """Status badge pill. One place for the shape + padding of every badge."""
    return (f"background: {bg}; color: {fg}; font-size: {size}; font-weight: bold; "
            f"padding: 4px 10px; border-radius: 6px;")


def rx_badge_qss(bg: str, fg: str) -> str:
    """Receiver-side badge (same pill, 3px 8px padding, slightly smaller)."""
    return (f"background: {bg}; color: {fg}; font-size: {FS_META_SM}; "
            f"font-weight: bold; padding: 3px 8px; border-radius: 6px;")


def solid_button_qss(bg: str, fg: str = SOLORA_BG, radius: int = 10) -> str:
    """Solid accent button WITH a matching :hover/:disabled so the global
    ``QPushButton:hover { color: CYAN }`` rule cannot recolour its dark label
    (that combination measured 1.53:1 on btn_start)."""
    hover_bg = mix(bg, SOLORA_BG, 0.14)
    return f"""
        QPushButton {{
            background-color: {bg};
            color: {fg};
            font-weight: bold;
            border-radius: {radius}px;
        }}
        QPushButton:hover {{ background-color: {hover_bg}; color: {fg}; border: none; }}
        QPushButton:pressed {{ background-color: {mix(bg, SOLORA_BG, 0.28)}; color: {fg}; }}
        QPushButton:disabled {{ background-color: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_TEXT_MUTED}; }}
    """


def cancel_button_qss() -> str:
    """Ghost/danger cancel button: keeps the red identity, adds a red :hover
    instead of inheriting the global cyan-text hover (which would put
    #00D4FF on #FF4D4D = 2.16:1)."""
    return f"""
        QPushButton {{
            background-color: {SOLORA_ALERT_RED};
            color: {SOLORA_BG};
            font-weight: bold;
            border-radius: 10px;
        }}
        QPushButton:hover {{
            background-color: {mix(SOLORA_ALERT_RED, SOLORA_BG, 0.14)};
            color: {SOLORA_BG};
            border: none;
        }}
        QPushButton:pressed {{ background-color: {mix(SOLORA_ALERT_RED, SOLORA_BG, 0.28)}; }}
        QPushButton:disabled {{
            background-color: {SOLORA_SURFACE_ELEVATED};
            color: {SOLORA_TEXT_MUTED};
        }}
    """


def scrollbar_qss(handle: str = SOLORA_CYAN, bar_height: int = 6,
                 handle_alpha: float = 0.5, hover_alpha: float = 0.8,
                 include_add_page: bool = False) -> str:
    """Horizontal thin scrollbar used by the nearby-devices and recent-host rows."""
    extra = (f"""
            QScrollBar::add-page:horizontal, QScrollBar::sub-page:horizontal {{
                background: none;
            }}""" if include_add_page else "")
    return f"""
            QScrollArea, QScrollArea > QWidget, QScrollArea > QWidget > QWidget {{
                background: transparent;
                border: none;
            }}
            QScrollBar:horizontal {{
                height: {bar_height}px;
                background: {alpha('#FFFFFF', 0.03)};
                border-radius: {bar_height // 2}px;
            }}
            QScrollBar::handle:horizontal {{
                background: {alpha(handle, handle_alpha)};
                min-width: 24px;
                border-radius: {bar_height // 2}px;
            }}
            QScrollBar::handle:horizontal:hover {{
                background: {alpha(handle, hover_alpha)};
            }}
            QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{
                width: 0px;
                background: none;
            }}{extra}
        """


PROGRESS_GRADIENT = ("qlineargradient(x1:0, y1:0, x2:1, y2:0, "
                     f"stop:0 {SOLORA_CYAN}, stop:1 {SOLORA_ENERGY_GREEN})")


def progress_qss(chunk_radius: int = 5) -> str:
    """Themed progress bar. Shared by the sender and receiver bars so the
    gradient cannot drift apart between the two definitions."""
    return f"""
            QProgressBar {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 7px;
            }}
            QProgressBar::chunk {{
                background-color: {PROGRESS_GRADIENT};
                border-radius: {chunk_radius}px;
            }}
        """


def elide(text: str, widget: QWidget, mode=Qt.TextElideMode.ElideRight) -> str:
    """Elide ``text`` to the widget's current width, keeping the full text as tooltip."""
    text = text or ""
    try:
        metrics = QFontMetrics(widget.font())
        out = metrics.elidedText(text, mode, max(40, widget.width() - 4))
    except Exception:
        return text
    widget.setToolTip(text if out != text else "")
    return out

# ─── Helpers ──────────────────────────────────────────────────────────────────
def format_size(v):
    if v < 1024:      return f"{v} B"
    if v < 1024**2:   return f"{v/1024:.1f} KB"
    if v < 1024**3:   return f"{v/1024**2:.2f} MB"
    return f"{v/1024**3:.2f} GB"

def format_eta(seconds):
    """Seconds left, the same shape the sender screen and Android use."""
    if seconds < 0:      return "--"
    if seconds < 60:     return f"{seconds}s"
    if seconds < 3600:   return f"{seconds // 60}m {seconds % 60}s"
    return f"{seconds // 3600}h {(seconds % 3600) // 60}m"

def get_local_ips():
    ips = []
    # 1. Probe network interfaces using 'ip route get' to find real default gateway interface
    try:
        out = subprocess.check_output(["ip", "route", "get", "1.1.1.1"], text=True)
        m = re.search(r"src\s+([0-9.]+)", out)
        if m:
            ip = m.group(1)
            if not ip.startswith("127.") and not ip.startswith("172.16.") and not ip.startswith("10.8."):
                ips.append(ip)
    except Exception:
        pass
        
    # 2. Probe physical network interfaces via 'ip -4 -br addr'
    try:
        out = subprocess.check_output(["ip", "-4", "-br", "addr"], text=True)
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 3:
                iface, state, addr_cidr = parts[0], parts[1], parts[2]
                if not iface.startswith(("lo", "docker", "virbr", "Cloudflare", "wg", "tun")):
                    ip = addr_cidr.split("/")[0]
                    if ip not in ips and not ip.startswith("127."):
                        ips.append(ip)
    except Exception:
        pass

    # 3. Fallback to UDP socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127.") and not ip.startswith("172.16.") and ip not in ips:
            ips.append(ip)
    except Exception:
        pass

    # 4. Fallback to hostname
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127.") and not ip.startswith("172.16.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass

    return ips if ips else ["127.0.0.1"]

def open_file_or_dir(filepath: str):
    """Open file or folder with system default viewer."""
    try:
        if sys.platform.startswith("linux"):
            subprocess.Popen(["xdg-open", filepath])
        elif sys.platform == "darwin":
            subprocess.Popen(["open", filepath])
        elif sys.platform == "win32":
            os.startfile(filepath)
    except Exception:
        pass


# ─── Settings (QSettings-backed, consumed by the client + server) ─────────────
@dataclass
class Settings:
    """Persisted user settings. Read by LinuxTransferClient and the receiver."""
    port: int = 8000
    chunk_size_kb: int = DEFAULT_CHUNK_SIZE // 1024
    verify_checksum: bool = True
    timeout_sec: int = 30

    @property
    def chunk_size(self) -> int:
        return max(64, int(self.chunk_size_kb)) * 1024

    def validated(self) -> "Settings":
        """Clamp values into ranges the controls advertise."""
        self.port = 1 if not (1 <= self.port <= 65535) else int(self.port)
        self.chunk_size_kb = min(4096, max(64, int(self.chunk_size_kb)))
        self.timeout_sec = min(120, max(5, int(self.timeout_sec)))
        self.verify_checksum = bool(self.verify_checksum)
        return self


SETTINGS_ORG = "NexusFlow"
SETTINGS_APP = "NexusFlow Linux"


def _settings_store() -> QSettings:
    return QSettings(SETTINGS_ORG, SETTINGS_APP)


SETTINGS = Settings()


def load_settings() -> Settings:
    """Read settings from QSettings, falling back to defaults on any problem."""
    global SETTINGS
    defaults = Settings()
    try:
        s = _settings_store()
        cfg = Settings(
            port=int(s.value("network/port", defaults.port)),
            chunk_size_kb=int(s.value("transfer/chunk_kb", defaults.chunk_size_kb)),
            verify_checksum=str(s.value("transfer/verify_sha256",
                                        "true" if defaults.verify_checksum else "false")).lower()
            in ("1", "true", "yes", "on"),
            timeout_sec=int(s.value("network/timeout_sec", defaults.timeout_sec)),
        )
    except Exception as exc:
        print(f"[Settings] falling back to defaults: {type(exc).__name__}: {exc}")
        cfg = defaults
    SETTINGS = cfg.validated()
    return SETTINGS


def save_settings(cfg: Settings) -> Settings:
    """Persist settings to QSettings; returns the validated stored value."""
    global SETTINGS
    SETTINGS = cfg.validated()
    try:
        s = _settings_store()
        s.setValue("network/port", SETTINGS.port)
        s.setValue("transfer/chunk_kb", SETTINGS.chunk_size_kb)
        s.setValue("transfer/verify_sha256", SETTINGS.verify_checksum)
        s.setValue("network/timeout_sec", SETTINGS.timeout_sec)
        s.sync()
    except Exception as exc:
        print(f"[Settings] could not persist: {type(exc).__name__}: {exc}")
    return SETTINGS


def make_client(base_url: str) -> LinuxTransferClient:
    """Build a client that honours the persisted chunk size / timeout / verify."""
    cfg = SETTINGS if isinstance(SETTINGS, Settings) else Settings()
    return LinuxTransferClient(
        base_url,
        timeout=cfg.timeout_sec,
        chunk_size=cfg.chunk_size,
        verify_checksum=cfg.verify_checksum,
    )


# ─── nexus:// pairing scheme (encoded in the receiver QR, parsed on the sender) ─
@dataclass
class PairTarget:
    """Parsed ``nexus://`` payload: a host, a port and an optional display name."""
    host: str = ""
    port: int = 8000
    name: str = ""
    raw: str = ""

    @property
    def is_valid(self) -> bool:
        return bool(self.host)


def build_pairing_name(name: str) -> str:
    """``nexus://receive/<name>`` — a pairing code that carries no address.

    The receiver QR used to encode the host and port alongside the name, so a
    photo of that QR handed over the machine's LAN address. Senders discover the
    address over mDNS/NSD by name, so the QR now carries only the name and the
    sender resolves it. Kept parseable by parse_pairing_url(), which falls back
    to discovery when no host is present.
    """
    from urllib.parse import quote
    return f"{NEXUS_SCHEME}://receive/{quote(name or '', safe='')}"


def build_pairing_url(host: str, port: int, name: str = "") -> str:
    """``nexus://receive/<name>?host=<ip>&port=<n>``.

    Still used when a host is explicitly known (e.g. importing another device's
    code); the receiver's own QR uses build_pairing_name() instead so it does not
    disclose an address.
    """
    from urllib.parse import quote
    url = f"{NEXUS_SCHEME}://receive/{quote(name or host, safe='')}"
    params = []
    if host:
        params.append(f"host={quote(host, safe='')}")
    if port:
        params.append(f"port={int(port)}")
    if params:
        url += "?" + "&".join(params)
    return url


def parse_pairing_url(text: str) -> PairTarget:
    """Parse a ``nexus://`` URL (or a bare ``http://host:port`` endpoint).

    Returns a :class:`PairTarget`; ``is_valid`` is False for anything that does
    not yield a host. Never raises — untrusted QR content must not crash the app.
    """
    from urllib.parse import parse_qs, unquote, urlparse
    text = (text or "").strip()
    if not text:
        return PairTarget()
    if text.lower().startswith(f"{NEXUS_SCHEME}://"):
        try:
            parsed = urlparse(text)
        except ValueError:
            return PairTarget(raw=text)
        path = parsed.path or ""
        name = unquote(path.strip("/").split("/")[-1]) if path.strip("/") else ""
        qs = parse_qs(parsed.query or "")
        host = (qs.get("host") or [""])[0].strip()
        port_raw = (qs.get("port") or [""])[0].strip()
        if not host:
            # Bare nexus://receive/<ip> form — the host *is* the path segment.
            for candidate in (name, parsed.netloc):
                if candidate and _looks_like_host(candidate):
                    host = candidate
                    break
            name = name if name != host else ""
        port = 8000
        if port_raw.isdigit():
            port = int(port_raw)
        return PairTarget(host=host, port=port, name=name, raw=text)
    if text.lower().startswith(("http://", "https://")):
        try:
            parsed = urlparse(text)
        except ValueError:
            return PairTarget(raw=text)
        port = parsed.port or (443 if parsed.scheme == "https" else 8000)
        return PairTarget(host=(parsed.hostname or ""), port=port, name="", raw=text)
    # Bare host or host:port
    if _looks_like_host(text):
        host, _, port_raw = text.rpartition(":")
        if not host:
            host, port_raw = text, ""
        port = int(port_raw) if port_raw.isdigit() else 8000
        return PairTarget(host=host, port=port, name="", raw=text)
    return PairTarget(raw=text)


def _looks_like_host(candidate: str) -> bool:
    """True for an IPv4 literal or a DNS-ish hostname."""
    if not candidate:
        return False
    if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", candidate):
        return all(0 <= int(o) <= 255 for o in candidate.split("."))
    if re.fullmatch(r"(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
                    r"(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+", candidate):
        return "." in candidate
    return False


# ─── Path of an optional PNG logo, robust when the asset is not bundled ───────
LOGO_CANDIDATES = (
    os.path.join("desktop", "assets", "nexusflow.png"),
    os.path.join("desktop", "assets", "ic_nexus_flow.png"),
    os.path.join("..", "android", "app", "src", "main", "res", "drawable", "ic_nexus_flow.png"),
)

LOGO_GLYPH = "◈"


def find_logo_path() -> str:
    """First existing bundled logo, or '' when none is available.

    The PyInstaller bundle only ships ``desktop/`` (see NexusFlow.spec), so the
    previous ``../android/...`` reference silently resolved to nothing in the
    shipped build. This also looks in ``sys._MEIPASS`` and tolerates a missing
    file by returning '' so the caller can fall back to the glyph.
    """
    base = os.path.dirname(os.path.abspath(__file__))
    roots = [base, getattr(sys, "_MEIPASS", "") or ""]
    for root in roots:
        if not root:
            continue
        for rel in LOGO_CANDIDATES:
            path = os.path.normpath(os.path.join(root, rel))
            if os.path.isfile(path):
                return path
    return ""


# ─── Transfer Worker ──────────────────────────────────────────────────────────
class TransferWorker(QThread):
    """Runs one transfer on its own thread and reports a *distinguishable*
    terminal outcome: completed / paused / cancelled / failed.

    Pause and cancel used to raise a bare ``Exception``, land in ``error_signal``
    and paint the UI as INTERRUPTED. Now :class:`TransferPaused` /
    :class:`TransferCancelled` get their own signals so the badge stays honest.
    """

    progress_signal  = pyqtSignal(int, int, float, float)
    status_signal    = pyqtSignal(str, str)
    completed_signal = pyqtSignal(dict)
    error_signal     = pyqtSignal(str)
    paused_signal    = pyqtSignal()
    cancelled_signal = pyqtSignal()

    def __init__(self, client, filepath, existing_transfer_id=None):
        super().__init__()
        self.client = client
        self.filepath = filepath
        self.existing_transfer_id = existing_transfer_id
        self.last_progress = (0, 0, 0.0, 0.0)
        #: Latest values, for a final flush when the thread is torn down early.
        self.result: dict = {}

    def run(self):
        try:
            self.status_signal.emit("Connecting", "Validating connection with target device...")
            self.client.test_connection()
            self.status_signal.emit("Hashing", "Computing cryptographic SHA-256...")
            checksum = self.client.calculate_sha256(self.filepath)
            transfer_id = self.existing_transfer_id
            start_offset = 0
            if transfer_id:
                self.status_signal.emit("Reconnecting", f"Checking resume offset for {transfer_id}...")
                start_offset = self.client.get_status(transfer_id).get("received_bytes", 0)
            else:
                self.status_signal.emit("Initializing", "Negotiating transfer session...")
                sess = self.client.create_transfer(self.filepath, checksum=checksum)
                transfer_id = sess["transfer_id"]
            self.status_signal.emit("Transferring", f"Streaming from byte {start_offset}...")

            def _progress(curr, total, spd, eta):
                self.last_progress = (curr, total, spd, eta)
                self.progress_signal.emit(curr, total, spd, eta)

            res = self.client.send_file(
                filepath=self.filepath, transfer_id=transfer_id,
                start_offset=start_offset, progress_callback=_progress,
            )
            self.result = res if isinstance(res, dict) else {}
            self.completed_signal.emit(res)
        except TransferPaused:
            self.paused_signal.emit()
        except TransferCancelled:
            self.cancelled_signal.emit()
        except Exception as e:
            self.error_signal.emit(str(e) or type(e).__name__)

    def request_transfer_interrupt(self, cancel: bool = False):
        """Ask the worker to stop. ``cancel=True`` also flags the client."""
        self.requestInterruption()
        try:
            if cancel:
                self.client.cancel()
            else:
                self.client.interrupt()
        except Exception as exc:
            print(f"[TransferWorker] interrupt failed: {type(exc).__name__}: {exc}")


class ConnectionTester(QThread):
    """Item 2: ``GET /health`` on a worker thread so the window never freezes.

    The old implementation called ``requests.get(timeout=4)`` inline plus a
    re-entrant ``QApplication.processEvents()`` from the click handler, so
    clicking any nearby device or recent IP stalled the UI for up to 4 seconds.
    """

    finished_probe = pyqtSignal(bool, str, str)  # (ok, message, detail)

    def __init__(self, base_url: str, parent=None):
        super().__init__(parent)
        self.base_url = base_url
        self.client = make_client(base_url)
        self._seq = 0

    def run(self):
        self._seq += 1
        seq = self._seq
        try:
            res = self.client.test_connection()
            if seq == self._seq:  # superseded by a newer probe
                return
            service = (res or {}).get("service", "ok")
            self.finished_probe.emit(True, f"● Connected ({service})", str(service))
        except Exception as e:
            if seq != self._seq:
                return
            # Stop discarding the reason: wrong port / wrong IP / firewall /
            # receiver-not-enabled are otherwise indistinguishable.
            self.finished_probe.emit(False, "● Unreachable",
                                     f"{type(e).__name__}: {e}")


# ─── Nav Drawer ───────────────────────────────────────────────────────────────
class NavDrawer(QWidget):
    nav_clicked = pyqtSignal(int)

    NAV_ITEMS = [
        ("\u2191", "Sender Mode"),
        ("\u2193", "Receiver Hub (P2P)"),
        ("\u23F1", "Transfer History"),
        ("\u2699", "Settings"),
    ]

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedWidth(DRAWER_WIDTH)
        self.current_index = 0
        self._build()

    def _build(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        self.setStyleSheet(f"background: {SOLORA_SURFACE}; border-right: 1px solid {SOLORA_BORDER};")

        # Logo header
        header = QWidget()
        header.setFixedHeight(88)
        header.setStyleSheet(f"background: {SOLORA_SURFACE};")
        hl = QHBoxLayout(header)
        hl.setContentsMargins(16, 12, 16, 12)
        hl.setSpacing(12)

        # No logo bitmap: the wordmark alone identifies the app, matching the
        # Android build where the same image was removed from every screen.
        tc = QVBoxLayout(); tc.setSpacing(2)
        lbl_n = QLabel("<span style='color:#00D4FF; font-weight:900;'>NEXUS </span>"
                       "<span style='color:#00E599; font-weight:900;'>FLOW</span>")
        lbl_n.setStyleSheet("font-size: 14pt;")
        lbl_s = QLabel("SMART FILE TRANSFER")
        lbl_s.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-size: 7pt; font-weight: bold; letter-spacing: 2px;")
        tc.addWidget(lbl_n); tc.addWidget(lbl_s)
        hl.addLayout(tc)
        layout.addWidget(header)

        # Divider
        div = QFrame(); div.setFrameShape(QFrame.Shape.HLine)
        div.setStyleSheet(f"color: {SOLORA_BORDER}; background: {SOLORA_BORDER}; max-height: 1px;")
        layout.addWidget(div)
        layout.addSpacing(8)

        # Nav buttons
        self.nav_buttons = []
        for i, (icon, label) in enumerate(self.NAV_ITEMS):
            btn = QPushButton(f"  {icon}   {label}")
            btn.setCheckable(True)
            btn.setFixedHeight(48)
            btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
            btn.setStyleSheet(self._btn_style(False))
            btn.clicked.connect(lambda _, idx=i: self._on_nav(idx))
            self.nav_buttons.append(btn)
            layout.addWidget(btn)
            layout.addSpacing(2)

        self.nav_buttons[0].setChecked(True)
        self.nav_buttons[0].setStyleSheet(self._btn_style(True))
        layout.addStretch()

        # Footer
        footer = QWidget()
        footer.setStyleSheet(f"background: {SOLORA_SURFACE}; border-top: 1px solid {SOLORA_BORDER};")
        fl = QVBoxLayout(footer); fl.setContentsMargins(14, 10, 14, 10); fl.setSpacing(2)
        fl.addWidget(QLabel("NEXUS FLOW v1.0  —  Linux Desktop"))
        proto = QLabel("Protocol: Resumable HTTP/1.1 + SHA-256")
        proto.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 7pt;")
        fl.addWidget(proto)
        layout.addWidget(footer)

    def _btn_style(self, active):
        if active:
            return (f"QPushButton {{ background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_CYAN}; "
                    f"border: none; border-left: 3px solid {SOLORA_CYAN}; border-radius: 0px; "
                    f"text-align: left; padding-left: 14px; font-weight: bold; font-size: 10pt; }}")
        return (f"QPushButton {{ background: transparent; color: {SOLORA_TEXT_SECONDARY}; "
                f"border: none; border-left: 3px solid transparent; border-radius: 0px; "
                f"text-align: left; padding-left: 14px; font-size: 10pt; }}"
                f"QPushButton:hover {{ background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_TEXT_PRIMARY}; }}")

    def _on_nav(self, idx):
        for i, btn in enumerate(self.nav_buttons):
            btn.setChecked(i == idx)
            btn.setStyleSheet(self._btn_style(i == idx))
        self.current_index = idx
        self.nav_clicked.emit(idx)

    def set_active(self, idx):
        self._on_nav(idx)


# ─── Thread bridge ────────────────────────────────────────────────────────────
class WorkerBridge(QObject):
    """Marshals non-GUI-thread callbacks onto the GUI thread.

    Two paths used to call widget methods straight from a background thread:
    ``EmbeddedReceiverServer.state_callback`` (an http.server ThreadingMixIn
    request thread, calling setValue/setText/setStyleSheet/deleteLater) and the
    zeroconf ``on_change`` callback (the mDNS listener thread). Both now emit a
    Qt signal connected with ``Qt.QueuedConnection``, which guarantees the slot
    runs on the thread that owns the receiver — the GUI thread.
    """

    server_state = pyqtSignal(dict)
    peers_changed = pyqtSignal(object)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._lock = threading.Lock()
        self._server_threads: set = set()

    # ── Called from the server request thread ──
    def on_server_state(self, rec: dict):
        """Fire-and-forget from an HTTP request thread; Qt queues the delivery."""
        self.server_state.emit(dict(rec or {}))

    def is_server_thread(self) -> bool:
        return threading.current_thread() in self._server_threads

    def register_server_thread(self, thread) -> None:
        with self._lock:
            self._server_threads.add(thread)

    def unregister_server_thread(self, thread) -> None:
        with self._lock:
            self._server_threads.discard(thread)

    # ── Called from the zeroconf listener thread ──
    def on_peers_changed(self, peers):
        self.peers_changed.emit(list(peers or []))


def _make_peer(name: str, host: str, port: int):
    """Build a peer record the nearby-devices panel can render.

    Uses nsd_helper.PeerInfo when importable and falls back to a tiny local
    object, so a missing dependency degrades discovery instead of the app.
    """
    if PeerInfo is not None:
        return PeerInfo(name, host, port)

    class _Peer:
        def __init__(self, n, h, p):
            self.name, self.host, self.port = n, h, p

        def __repr__(self):
            return f"PeerInfo(name={self.name!r}, host={self.host}, port={self.port})"

    return _Peer(name, host, port)


# ─── Sender Screen ────────────────────────────────────────────────────────────
class DropZoneFrame(QFrame):
    """A frame that highlights itself while a file is dragged over it.

    Qt only routes drag events to the widget that accepts them, so a plain
    QFrame inside the panel cannot show hover feedback. This subclass toggles a
    dynamic property (``dragActive``) so the stylesheet can react, and emits
    ``fileDropped`` with the first local file path in the drop.
    """

    fileDropped = pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._drag_active = False

    def _set_drag_active(self, active: bool):
        if active == self._drag_active:
            return
        self._drag_active = active
        # Dynamic property + repolish makes Qt re-evaluate the stylesheet.
        self.setProperty("dragActive", "true" if active else "false")
        style = self.style()
        if style is not None:
            style.unpolish(self)
            style.polish(self)
        self.update()

    @staticmethod
    def _first_local_file(mime) -> str:
        if mime is None or not mime.hasUrls():
            return ""
        for url in mime.urls():
            if url.isLocalFile():
                path = url.toLocalFile()
                if os.path.isfile(path):
                    return path
        return ""

    def dragEnterEvent(self, event):
        if self._first_local_file(event.mimeData()):
            self._set_drag_active(True)
            event.acceptProposedAction()
        else:
            event.ignore()

    def dragMoveEvent(self, event):
        if self._first_local_file(event.mimeData()):
            event.acceptProposedAction()
        else:
            event.ignore()

    def dragLeaveEvent(self, event):
        self._set_drag_active(False)
        event.accept()

    def dropEvent(self, event):
        self._set_drag_active(False)
        path = self._first_local_file(event.mimeData())
        if path:
            self.fileDropped.emit(path)
            event.acceptProposedAction()
        else:
            event.ignore()


class SenderScreen(QWidget):
    def __init__(self, parent=None, bridge: WorkerBridge = None):
        super().__init__(parent)
        self.selected_file = None
        self.active_worker = None
        self.active_client = None
        self.active_transfer_id = None
        self.active_file = None
        self.conn_tester: ConnectionTester | None = None
        self.bridge = bridge
        self.discovery_ok = False
        self.discovery_error = None
        # UDP broadcast discovery has its own status; discovery_working is the
        # union, so the UI never claims "unavailable" when broadcast is fine.
        self.lan_ok = False
        self.lan_error = None
        self.discovery_working = False
        # Peers located by the TCP subnet sweep, keyed "host:port".
        self._scanned_peers: dict = {}
        self._peer_signature = None
        self._last_progress_emit = 0.0
        self._progress_min_interval = 1.0 / PROGRESS_THROTTLE_HZ
        self._build()

        # Start NSD discovery and refresh the peer panel every 2s. The callback
        # runs on the zeroconf listener thread, so it is routed through the
        # bridge (QueuedConnection) instead of touching widgets directly.
        if bridge is not None:
            bridge.peers_changed.connect(self.refresh_nearby_devices_ui,
                                        Qt.ConnectionType.QueuedConnection)
        self._discovery_timer = QTimer(self)
        self._discovery_timer.timeout.connect(self.refresh_nearby_devices_ui)
        self._discovery_timer.start(2000)
        self.start_discovery()

    # ── Discovery lifecycle ──────────────────────────────────────────────────
    def start_discovery(self):
        """Start discovery over UDP broadcast and mDNS.

        Broadcast is the primary mechanism because mDNS does not traverse an
        Android hotspot: SoftAP does not forward multicast to clients, so two
        devices on a phone hotspot could never see each other. Broadcast works
        there. mDNS is still started because it is cheaper on a real LAN.
        Both peer sets are merged in refresh_nearby_devices_ui().
        """
        own_ips = get_local_ips()

        # UDP broadcast discovery (works on hotspots).
        if lan is None:
            self.lan_ok = False
            self.lan_error = "Broadcast discovery module could not be imported."
        else:
            try:
                lan.on_change = self._on_lan_peers
                lan.start(own_ips=own_ips)
                self.lan_ok = True
                self.lan_error = None
            except Exception as exc:
                self.lan_ok = False
                self.lan_error = f"{type(exc).__name__}: {exc}"

        # mDNS discovery (best effort; may be unavailable).
        if nsd_discovery is None:
            self.discovery_ok = False
            self.discovery_error = "mDNS discovery module unavailable."
        else:
            try:
                on_change = self.bridge.on_peers_changed if self.bridge else None
                self.discovery_ok = bool(nsd_discovery.start(own_ips=own_ips, on_change=on_change))
                self.discovery_error = None if self.discovery_ok else (
                    getattr(nsd_discovery, "last_error", None) or ZEROCONF_UNAVAILABLE_REASON
                )
            except Exception as exc:
                self.discovery_ok = False
                self.discovery_error = f"{type(exc).__name__}: {exc}"

        # Discovery is "working" if either mechanism came up.
        self.discovery_working = self.lan_ok or self.discovery_ok
        self._peer_signature = None
        self.refresh_nearby_devices_ui()
        self.start_subnet_scan()

    def _on_lan_peers(self, peers):
        """Called from the LAN discovery background thread.

        Must not touch widgets: marshal the update onto the GUI thread via the
        bridge's queued signal, exactly like the mDNS listener does.
        """
        if self.bridge is not None:
            self.bridge.on_peers_changed(peers)
        else:
            QTimer.singleShot(0, self.refresh_nearby_devices_ui)

    def refresh_peers_now(self):
        """Refresh button: query broadcast, mDNS, and probe the subnet.

        Broadcast and mDNS are attempted first because they are instant where they
        work. The TCP subnet sweep then runs regardless: on a network whose AP
        blocks client-to-client broadcast (which is the case on the target
        hotspot/WiFi) it is the only mechanism that finds anything. The local
        /24 is probed in well under a second; the rest of the /16 takes about
        23s and reports devices as they appear.
        """
        try:
            if lan is not None:
                lan.scan_now()
        except Exception as exc:
            self.lan_error = f"{type(exc).__name__}: {exc}"
        try:
            if nsd_discovery is not None:
                nsd_discovery.start(
                    own_ips=get_local_ips(),
                    on_change=self.bridge.on_peers_changed if self.bridge else None,
                )
        except Exception:
            pass
        self.lbl_scanning.setText("● Scanning...")
        self.lbl_scanning.setStyleSheet(f"color: {SOLORA_AMBER}; font-size: {FS_META_SM};")
        self._peer_signature = None  # force a rebuild
        self.refresh_nearby_devices_ui()
        self.start_subnet_scan()

    def start_subnet_scan(self):
        """Probe the subnet for receivers, reporting progress and results."""
        if scanner is None:
            return
        if scanner.scanning:
            self.lbl_scanning.setText("● Scanning network...")
            return

        def on_found(ip, port, health):
            # Called from a scan thread: marshal to the GUI thread.
            name = self._display_name_for(ip, health)
            QTimer.singleShot(0, lambda: self._add_scanned_peer(name, ip, port))

        def on_progress(done, total):
            pct = int(done * 100 / max(1, total))
            QTimer.singleShot(0, lambda: self.lbl_scanning.setText(f"● Scanning network {pct}%"))

        def on_done():
            QTimer.singleShot(0, lambda: self.lbl_scanning.setText("● Scan complete"))

        scanner.start(on_found=on_found, on_progress=on_progress, on_done=on_done)

    @staticmethod
    def _display_name_for(ip: str, health) -> str:
        """Prefer a name the device advertises; fall back to its address."""
        if health:
            for key in ("device_name", "name", "hostname"):
                value = health.get(key)
                if value:
                    return str(value)
        return f"Device {ip}"

    def _add_scanned_peer(self, name: str, host: str, port: int):
        """Insert a scanned peer if it is not already listed."""
        self._scanned_peers[f"{host}:{port}"] = _make_peer(name, host, port)
        self._peer_signature = None  # force the panel to rebuild
        self.refresh_nearby_devices_ui()

    def stop_discovery(self):
        try:
            if lan is not None:
                lan.stop()
        except Exception:
            pass
        try:
            if scanner is not None:
                scanner.stop()
        except Exception:
            pass
        try:
            if nsd_discovery is not None:
                nsd_discovery.stop()
        except Exception:
            pass

    def set_page_visible(self, visible: bool):
        """Item 13/26: stop the 2s poll when the Sender page is off-screen."""
        if visible:
            self.start_discovery()
            self._discovery_timer.start(2000)
        else:
            self._discovery_timer.stop()

    def _collect_peers(self) -> list:
        """Merge peers from UDP broadcast and mDNS into one list.

        Broadcast is what works on a hotspot, so it must not be gated on mDNS
        succeeding -- the two sets are unioned and de-duplicated by
        name@host:port, because a device visible on both would otherwise appear
        twice.
        """
        merged: dict[str, object] = {}

        try:
            if lan is not None:
                for p in lan.get_peers():
                    merged[f"{p.name}@{p.host}:{p.port}"] = p
        except Exception as exc:
            print(f"[Sender] LAN peers unavailable: {type(exc).__name__}: {exc}")

        try:
            if nsd_discovery is not None:
                for p in nsd_discovery.get_peers():
                    merged.setdefault(f"{p.name}@{p.host}:{p.port}", p)
        except Exception as exc:
            print(f"[Sender] mDNS peers unavailable: {type(exc).__name__}: {exc}")

        # Devices found by the TCP subnet sweep.
        for p in self._scanned_peers.values():
            merged.setdefault(f"{p.name}@{p.host}:{p.port}", p)

        return list(merged.values())

    @staticmethod
    def _peer_hash(peers) -> str:
        return "|".join(sorted(f"{p.name}@{p.host}:{p.port}" for p in peers))

    def _build(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 24, 28, 24)
        layout.setSpacing(16)

        title = QLabel("SENDER MODE")
        title.setStyleSheet(f"color: {SOLORA_CYAN}; font-size: {FS_HEADING}; font-weight: bold; letter-spacing: 2px;")
        layout.addWidget(title)

        # ── Refresh device list ──
        # Prominent and labelled, not a bare glyph: rescanning is the thing a
        # user reaches for when a peer does not appear, and the old 28px "⟳"
        # next to the paste-code button was easy to miss.
        btn_scan = QPushButton("⟳   RESCAN DEVICES")
        btn_scan.setFixedHeight(34)
        btn_scan.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        btn_scan.setStyleSheet(
            f"QPushButton {{ background-color: {SOLORA_SURFACE_ELEVATED};"
            f" color: {SOLORA_CYAN}; border: 1px solid {SOLORA_CYAN};"
            f" border-radius: 8px; font-weight: bold; font-size: {FS_META_SM}; }}"
            # letter-spacing is deliberately omitted: Qt QSS rejects it on
            # QPushButton ("Could not parse stylesheet"), it is only honoured on
            # QLabel.
            f"QPushButton:hover {{ background-color: {alpha(SOLORA_CYAN, 0.14)};"
            f" color: {SOLORA_TEXT_PRIMARY}; border-color: {SOLORA_CYAN}; }}"
            f"QPushButton:pressed {{ background-color: {alpha(SOLORA_CYAN, 0.24)}; }}"
            f"QPushButton:focus {{ border-color: {SOLORA_NEON_LIME}; }}"
            f"QPushButton:disabled {{ color: {SOLORA_TEXT_MUTED};"
            f" border-color: {SOLORA_BORDER}; background-color: {SOLORA_SURFACE_ELEVATED}; }}"
        )
        btn_scan.setAccessibleName("Rescan for nearby devices")
        btn_scan.setToolTip(
            "Query over UDP broadcast and mDNS for devices that have receiving enabled"
        )
        btn_scan.clicked.connect(self.refresh_peers_now)
        self.btn_scan = btn_scan
        layout.addWidget(btn_scan)

        # Connection card
        cc = QFrame(); cc.setObjectName("card")
        cc.setStyleSheet(f"""
            QFrame#card {{
                background-color: {SOLORA_SURFACE_CARD};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 14px;
            }}
            QLabel {{
                background-color: transparent;
            }}
            QWidget {{
                background-color: transparent;
            }}
            QLineEdit {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_NEON_LIME};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 8px;
                padding: 6px 10px;
                font-family: {FONT_MONO};
                font-size: {FS_BODY};
            }}
            QPushButton {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_TEXT_PRIMARY};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 8px;
                padding: 6px 14px;
                font-weight: bold;
                font-size: {FS_SMALL};
            }}
            QPushButton:hover {{
                border-color: {SOLORA_CYAN};
                color: {SOLORA_CYAN};
            }}
        """)
        cl = QVBoxLayout(cc); cl.setContentsMargins(18,16,18,16); cl.setSpacing(12)

        # ── Nearby Devices Discovery Panel ──
        # Doubles as the app's drag-and-drop target: a file dropped anywhere in
        # this panel is selected exactly as if it had been picked with
        # SELECT FILE FROM DISK (it routes through SenderScreen.set_file).
        nearby_frame = DropZoneFrame()
        nearby_frame.setObjectName("nearbyFrame")
        nearby_frame.setAcceptDrops(True)
        nearby_frame.setStyleSheet(f"""
            QFrame#nearbyFrame {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                border: 1px solid {alpha(SOLORA_CYAN, 0.3)};
                border-radius: 10px;
            }}
            QFrame#nearbyFrame[dragActive="true"] {{
                background-color: {alpha(SOLORA_CYAN, 0.10)};
                border: 2px solid {SOLORA_CYAN};
            }}
            QLabel {{ background: transparent; }}
        """)
        nf_layout = QVBoxLayout(nearby_frame)
        nf_layout.setContentsMargins(12, 10, 12, 10)
        nf_layout.setSpacing(8)

        nearby_title_row = QHBoxLayout()
        lbl_nearby = QLabel("⬡  NEARBY DEVICES")
        lbl_nearby.setStyleSheet(f"color: {SOLORA_CYAN}; font-weight: bold; font-size: {FS_SMALL}; letter-spacing: 1px;")
        self.lbl_scanning = QLabel("● Scanning...")
        self.lbl_scanning.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM};")
        self.lbl_scanning.setAccessibleName("Discovery status")
        nearby_title_row.addWidget(lbl_nearby)
        nearby_title_row.addStretch()
        nearby_title_row.addWidget(self.lbl_scanning)

        # Refresh nearby devices button. Text label rather than a "⟳" glyph:
        # the dingbat and emoji fonts are not installed on this system, so the
        # glyph buttons rendered as empty boxes.
        btn_refresh_nearby = QPushButton("RESCAN")
        btn_refresh_nearby.setFixedHeight(28)
        btn_refresh_nearby.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        btn_refresh_nearby.setStyleSheet(f"background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_CYAN}; border: 1px solid {SOLORA_BORDER}; border-radius: 6px; font-weight: bold; font-size: {FS_META_SM};")
        btn_refresh_nearby.setToolTip("Refresh nearby devices")
        btn_refresh_nearby.setAccessibleName("Refresh nearby devices")
        btn_refresh_nearby.clicked.connect(self.refresh_peers_now)
        nearby_title_row.addWidget(btn_refresh_nearby)

        # Pairing-code paste button. The old "📷" button opened a dialog telling
        # the user to point a camera at the screen — with no camera access
        # anywhere in the app. It now pastes a nexus:// / http:// pairing code,
        # which is the same information the receiver's QR encodes and which
        # parse_pairing_url() actually understands.
        btn_qr_scan = QPushButton("PASTE CODE")
        btn_qr_scan.setFixedHeight(28)
        btn_qr_scan.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        btn_qr_scan.setStyleSheet(f"background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_NEON_LIME}; border: 1px solid {SOLORA_BORDER}; border-radius: 6px; font-weight: bold; font-size: {FS_META_SM};")
        btn_qr_scan.setToolTip("Paste a pairing code from the receiver's QR screen")
        btn_qr_scan.setAccessibleName("Paste pairing code")
        btn_qr_scan.clicked.connect(self.pair_from_code)
        nearby_title_row.addWidget(btn_qr_scan)
        nf_layout.addLayout(nearby_title_row)

        self.nearby_devices_container = QWidget()
        self.nearby_devices_container.setStyleSheet("background: transparent;")
        self.nearby_devices_layout = QHBoxLayout(self.nearby_devices_container)
        self.nearby_devices_layout.setContentsMargins(0, 0, 0, 0)
        self.nearby_devices_layout.setSpacing(8)

        # Faded drop hint. It lives in the empty-state block of
        # refresh_nearby_devices_ui(), so it appears only when no peers are
        # discovered and disappears when cards appear.
        nearby_frame.fileDropped.connect(self._on_panel_file_dropped)

        nearby_scroll = QScrollArea()
        # Item 11: minimum height + expanding policy instead of a rigid 64px, so
        # peer cards can grow/wrap; identical height at the default window size.
        nearby_scroll.setMinimumHeight(64)
        nearby_scroll.setSizePolicy(QSizePolicy.Policy.Expanding,
                                     QSizePolicy.Policy.Fixed)
        nearby_scroll.setWidgetResizable(True)
        nearby_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        nearby_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        nearby_scroll.setStyleSheet(scrollbar_qss(SOLORA_CYAN, bar_height=6,
                                                  handle_alpha=0.5, hover_alpha=0.8))
        nearby_scroll.setWidget(self.nearby_devices_container)
        nf_layout.addWidget(nearby_scroll)
        cl.addWidget(nearby_frame)

        # Manual entry row (collapsed/hidden by default - nearby devices is primary)
        # Kept for edge cases but not prominent
        # Item 22: the label is now a real focusable button stored on self, so
        # _toggle_manual_entry no longer scans every QLabel for a magic substring.
        self.manual_row_label = QPushButton("▼  MANUAL IP ENTRY (fallback)")
        self.manual_row_label.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self.manual_row_label.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.manual_row_label.setStyleSheet(
            f"QPushButton {{ background: transparent; border: none; text-align: left;"
            f" color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM}; font-weight: bold;"
            f" letter-spacing: 1px; padding: 0; }}"
            f"QPushButton:hover {{ color: {SOLORA_CYAN}; background: transparent; }}"
            f"QPushButton:focus {{ color: {SOLORA_CYAN}; }}")
        self.manual_row_label.setAccessibleName("Toggle manual IP entry")
        self.manual_row_label.setToolTip("Show or hide manual host/port entry")
        self.manual_row_label.clicked.connect(self._toggle_manual_entry)
        cl.addWidget(self.manual_row_label)

        self.manual_entry_widget = QWidget()
        self.manual_entry_widget.setVisible(False)
        manual_layout = QVBoxLayout(self.manual_entry_widget)
        manual_layout.setContentsMargins(0, 8, 0, 0)
        manual_layout.setSpacing(8)

        top_conn = QHBoxLayout(); top_conn.setSpacing(10)
        # Item 14: this field holds an IP / hostname, never a device name
        # (select_recent_ip writes an IP, select_peer writes peer.host, and
        # load_recent_ips regex-extracts IPs). The old "DEVICE NAME:" label was
        # actively misleading. Widget texts are otherwise unchanged.
        lbl_t = QLabel("TARGET HOST/IP:")
        lbl_t.setStyleSheet(f"color: {SOLORA_CYAN}; font-weight: bold; font-size: {FS_SMALL}; background: transparent;")
        # Show discovered device host (primary selection via nearby devices panel)
        self.txt_peer_name = QLineEdit(""); self.txt_peer_name.setFixedWidth(150)
        self.txt_peer_name.setPlaceholderText("e.g. 192.168.1.42")
        self.txt_peer_name.setAccessibleName("Target host or IP address")
        self.txt_peer_name.setToolTip("IPv4 address or hostname of the receiving device")
        lbl_p = QLabel("PORT:")
        lbl_p.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-weight: bold; font-size: {FS_SMALL}; background: transparent;")
        self.txt_port = QLineEdit(str(SETTINGS.port)); self.txt_port.setFixedWidth(70)
        self.txt_port.setAccessibleName("Target port")
        self.txt_port.setToolTip("TCP port the receiver is listening on")
        self.btn_test = QPushButton("TEST CONNECTION"); self.btn_test.clicked.connect(self.test_connection)
        self.btn_test.setAccessibleName("Test connection to target")
        self.lbl_conn = QLabel("● Ready")
        self.lbl_conn.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-weight: bold; background: transparent;")
        self.lbl_conn.setAccessibleName("Connection status")
        self.lbl_conn.setWordWrap(True)
        for w in [lbl_t, self.txt_peer_name, lbl_p, self.txt_port, self.btn_test]:
            top_conn.addWidget(w)
        top_conn.addSpacing(10); top_conn.addWidget(self.lbl_conn, 1); top_conn.addStretch()
        manual_layout.addLayout(top_conn)

        # Faded Recent IP History Row / Scrollbar area
        self.recent_ips_container = QWidget()
        self.recent_ips_container.setStyleSheet("background: transparent;")
        self.recent_ips_layout = QHBoxLayout(self.recent_ips_container)
        self.recent_ips_layout.setContentsMargins(0, 0, 0, 0)
        self.recent_ips_layout.setSpacing(6)

        recent_scroll = QScrollArea()
        # Item 11: minimum instead of a rigid 36px, so a longer recent list or a
        # taller theme does not clip; same rendered height by default.
        recent_scroll.setMinimumHeight(36)
        recent_scroll.setSizePolicy(QSizePolicy.Policy.Expanding,
                                    QSizePolicy.Policy.Fixed)
        recent_scroll.setWidgetResizable(True)
        recent_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        recent_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        recent_scroll.setStyleSheet(scrollbar_qss(SOLORA_TEXT_MUTED, bar_height=4,
                                                  handle_alpha=0.35, hover_alpha=0.6,
                                                  include_add_page=True))
        recent_scroll.setWidget(self.recent_ips_container)

        recent_header = QHBoxLayout(); recent_header.setSpacing(8)
        lbl_recent_tag = QLabel("RECENT:")
        lbl_recent_tag.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM}; font-weight: bold; letter-spacing: 1px; background: transparent;")
        recent_header.addWidget(lbl_recent_tag)
        recent_header.addWidget(recent_scroll, 1)
        manual_layout.addLayout(recent_header)

        cl.addWidget(self.manual_entry_widget)

        layout.addWidget(cc)
        self.refresh_recent_ips_ui()
        self.refresh_nearby_devices_ui()

        # Progress card
        pc = QFrame(); pc.setObjectName("card")
        pl = QVBoxLayout(pc); pl.setContentsMargins(20,20,20,20); pl.setSpacing(12)
        top = QHBoxLayout()
        self.lbl_file = QLabel("NO FILE SELECTED")
        self.lbl_file.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-size: {FS_DISPLAY}; font-weight: bold;")
        self.lbl_file.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.lbl_badge = QLabel("STANDBY")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_SURFACE_ELEVATED, SOLORA_TEXT_SECONDARY))
        self.lbl_badge.setAccessibleName("Transfer status")
        top.addWidget(self.lbl_file, 1); top.addStretch(); top.addWidget(self.lbl_badge)
        pl.addLayout(top)
        self.prog_bar = QProgressBar()
        self.prog_bar.setRange(0,100); self.prog_bar.setValue(0)
        self.prog_bar.setFixedHeight(14); self.prog_bar.setTextVisible(False)
        pl.addWidget(self.prog_bar)
        mr = QHBoxLayout()
        self.lbl_vol   = QLabel("0 B / 0 B (0%)")
        self.lbl_vol.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-family: {FONT_MONO}; font-size: {FS_BODY};")
        self.lbl_speed = QLabel("0 KB/s")
        self.lbl_speed.setStyleSheet(f"color: {SOLORA_NEON_LIME}; font-weight: bold; font-family: {FONT_MONO}; font-size: {FS_BODY};")
        self.lbl_eta   = QLabel("ETA: --")
        self.lbl_eta.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-family: {FONT_MONO}; font-size: {FS_BODY};")
        mr.addWidget(self.lbl_vol); mr.addStretch()
        mr.addWidget(self.lbl_speed); mr.addSpacing(16); mr.addWidget(self.lbl_eta)
        pl.addLayout(mr)

        # Status / Log banner with modern styling
        log_box = QFrame()
        log_box.setStyleSheet(f"""
            QFrame {{
                background-color: {alpha(SOLORA_SURFACE_ELEVATED, 0.7)};
                border: 1px solid {alpha(SOLORA_CYAN, 0.25)};
                border-radius: 8px;
            }}
        """)
        lbl_box_layout = QHBoxLayout(log_box)
        lbl_box_layout.setContentsMargins(14, 9, 14, 9)
        lbl_box_layout.setSpacing(10)

        self.lbl_log_dot = QLabel("●")
        self.lbl_log_dot.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-size: {FS_HEADING}; background: transparent;")
        self.lbl_log = QLabel("")
        self.lbl_log.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-size: {FS_BODY_SM}; font-weight: 600; letter-spacing: 0.5px; background: transparent;")
        # Item 23: long SHA / error strings were pushed into a fixed-height box and
        # clipped. Wrap instead, and keep the full text as a tooltip.
        self.lbl_log.setWordWrap(True)
        self.lbl_log.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        self.lbl_log.setAccessibleName("Transfer log message")
        lbl_box_layout.addWidget(self.lbl_log_dot)
        lbl_box_layout.addWidget(self.lbl_log, 1)
        pl.addWidget(log_box)
        self.log_box = log_box
        self.log_box.setVisible(False)   # nothing to say until something happens
        layout.addWidget(pc)

        # Buttons
        br = QHBoxLayout()
        self.btn_pick = QPushButton("SELECT FILE FROM DISK"); self.btn_pick.setFixedHeight(46)
        self.btn_pick.setAccessibleName("Select file from disk")
        self.btn_pick.setToolTip("Choose a file to send (Ctrl+O) — or drag & drop it onto this window")
        self.btn_pick.clicked.connect(self.pick_file)
        # Android has had a "NEW" control that clears the selection; the Linux
        # sender had no equivalent, so once a file was chosen the only ways out
        # were to start it or restart the app. Removed files and picking the
        # wrong one are both routine, so it gets a real button.
        self.btn_clear = QPushButton("✖  REMOVE"); self.btn_clear.setFixedHeight(46)
        self.btn_clear.setEnabled(False)
        self.btn_clear.setAccessibleName("Remove the selected file")
        self.btn_clear.setToolTip("Clear the selected file without sending it (Esc)")
        self.btn_clear.clicked.connect(self.clear_file)
        self.btn_start = QPushButton("▶  START TRANSFER"); self.btn_start.setFixedHeight(46)
        self.btn_start.setEnabled(False)
        # Item 4: a matching :hover/:disabled so the global
        # QPushButton:hover{color:CYAN} cannot recolour the dark label
        # (measured #00D4FF on #D4FF00 = 1.53:1).
        self.btn_start.setStyleSheet(solid_button_qss(SOLORA_NEON_LIME, SOLORA_BG, 10))
        self.btn_start.setAccessibleName("Start or resume transfer")
        self.btn_start.clicked.connect(self.start_or_resume)
        self.btn_pause = QPushButton("⏸  PAUSE"); self.btn_pause.setFixedHeight(46); self.btn_pause.setEnabled(False)
        self.btn_pause.setAccessibleName("Pause transfer")
        self.btn_pause.clicked.connect(self.pause_transfer)
        self.btn_cancel = QPushButton("✖  CANCEL"); self.btn_cancel.setFixedHeight(46); self.btn_cancel.setEnabled(False)
        self.btn_cancel.setAccessibleName("Cancel transfer (Esc)")
        self.btn_cancel.clicked.connect(self.cancel_transfer)
        br.addWidget(self.btn_pick, 2); br.addWidget(self.btn_clear, 1)
        br.addWidget(self.btn_start, 3)
        br.addWidget(self.btn_pause, 1); br.addWidget(self.btn_cancel, 1)
        layout.addLayout(br)
        layout.addStretch()

        # The tooltips have always advertised Ctrl+O and Esc, and neither was
        # ever bound. Wiring them makes the promises true rather than deleting
        # the hints: the shortcuts are what a file manager muscle-memory expects,
        # and Esc doing the *right* thing depends on what is going on.
        QShortcut(QKeySequence("Ctrl+O"), self, activated=self.pick_file)
        QShortcut(QKeySequence("Esc"), self, activated=self._escape_action)

    def _escape_action(self):
        """Esc: drop the file if nothing is running, otherwise stop the transfer.

        One key for "back out of whatever I am looking at" -- but never the
        destructive one by accident, and never cancelling work that has not
        started.
        """
        running = self.active_worker is not None or self.active_transfer_id is not None
        if running:
            self.cancel_transfer()
        elif self.selected_file:
            self.clear_file()

    def _toggle_manual_entry(self):
        """Toggle visibility of manual IP entry section (item 22: direct ref)."""
        is_visible = self.manual_entry_widget.isVisible()
        self.manual_entry_widget.setVisible(not is_visible)
        self.manual_row_label.setText(
            "▲  MANUAL IP ENTRY (fallback)" if not is_visible
            else "▼  MANUAL IP ENTRY (fallback)"
        )

    def pair_from_code(self):
        """Item 15: accept a receiver pairing code (what the QR encodes).

        Replaces the old camera affordance, which promised a scan that the app
        never performed.
        """
        from PyQt6.QtWidgets import QInputDialog
        text, ok = QInputDialog.getText(
            self, "Pair with Receiver",
            "Paste the pairing code from the receiver's QR screen\n"
            "(nexus://receive/... or http://ip:port):"
        )
        if not ok or not text.strip():
            return
        self.apply_pairing_code(text)

    def apply_pairing_code(self, text: str) -> bool:
        """Parse a pairing code and prefill host/port, then test the link."""
        target = parse_pairing_url(text)
        if not target.is_valid:
            QMessageBox.warning(
                self, "Pairing code",
                f"Could not read a host from:\n\n{text}\n\n"
                "Expected a nexus://receive/<name>?host=<ip>&port=<n> code, "
                "an http://ip:port endpoint, or a bare IP address.",
            )
            return False
        self.txt_peer_name.setText(target.host)
        self.txt_port.setText(str(target.port))
        label = target.name or target.host
        self._set_log(f"Paired with '{label}' at {target.host}:{target.port}.")
        self.test_connection()
        return True

    def refresh_recent_ips_ui(self):
        while self.recent_ips_layout.count() > 0:
            item = self.recent_ips_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        recent_ips = load_recent_ips()
        if not recent_ips:
            lbl_none = QLabel("No saved target IPs yet")
            lbl_none.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM}; font-style: italic;")
            self.recent_ips_layout.addWidget(lbl_none)
        else:
            for ip in recent_ips:
                btn_ip = QPushButton(ip)
                btn_ip.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
                btn_ip.setFixedHeight(26)
                btn_ip.setAccessibleName(f"Reconnect to {ip}")
                btn_ip.setToolTip(f"Test the connection to {ip}")
                btn_ip.setStyleSheet(f"""
                    QPushButton {{
                        background-color: {SOLORA_SURFACE_ELEVATED};
                        color: {SOLORA_CYAN};
                        border: 1px solid {SOLORA_BORDER};
                        border-radius: 6px;
                        padding: 2px 10px;
                        font-family: {FONT_MONO};
                        font-size: {FS_META};
                        font-weight: bold;
                    }}
                    QPushButton:hover {{
                        background-color: {SOLORA_BORDER};
                        border-color: {SOLORA_CYAN};
                        color: {SOLORA_ENERGY_GREEN};
                    }}
                """)
                btn_ip.clicked.connect(lambda _, target=ip: self.select_recent_ip(target))
                self.recent_ips_layout.addWidget(btn_ip)

        self.recent_ips_layout.addStretch()

    def select_recent_ip(self, ip: str):
        self.txt_peer_name.setText(ip)
        self.test_connection()

    def select_peer(self, host: str, port: int, name: str):
        """Called when user clicks a discovered nearby device button."""
        self.txt_peer_name.setText(host)
        self.txt_port.setText(str(port))
        self.test_connection()

    def _add_drop_hint(self, layout=None):
        """Add the faded 'DRAG AND DROP FILES OVER HERE' prompt.

        Only shown while the panel is empty. When peers are discovered the cards
        fill the row, so the hint would squeeze them into half the width and
        read as clutter -- refresh_nearby_devices_ui() omits it in that case.
        A dimmed token colour keeps it readable without competing with the
        NEARBY DEVICES heading.
        """
        hint = QLabel("DRAG AND DROP FILES OVER HERE")
        hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hint.setWordWrap(True)
        hint.setStyleSheet(
            f"color: {alpha(SOLORA_TEXT_MUTED, 0.75)};"
            f" font-size: {FS_SMALL}; font-weight: bold; letter-spacing: 2px;"
            " background: transparent;")
        hint.setAccessibleName(
            "Drag and drop files over here to select them for sending")
        target = layout if layout is not None else self.nearby_devices_layout
        target.addWidget(hint)

    def _on_panel_file_dropped(self, path: str):
        """A file was dropped on the nearby-devices panel.

        Routed through set_file() so a dropped file lands in exactly the same
        state as one chosen with SELECT FILE FROM DISK.
        """
        self.set_file(path)

    def refresh_nearby_devices_ui(self, _peers=None):
        """Rebuild the nearby-devices panel from current discovery results.

        Item 13: only rebuild when the peer set actually changed, so the 2s timer
        no longer destroy()/recreate() every card forever, and surface the
        'discovery unavailable' case as its own state instead of an endless
        empty list.
        """
        peers = self._collect_peers()
        if self.bridge is None and _peers is not None:
            peers = _peers

        signature = self._peer_hash(peers)
        if signature == self._peer_signature:
            return
        self._peer_signature = signature

        # Clear old buttons.
        # setParent(None) before deleteLater() is deliberate: deleteLater() is
        # deferred, so a widget stays a child of the frame until the event loop
        # drains the delete event. Without detaching it first, the stale widgets
        # (and their children) remain findable and are still painted for a
        # frame, which duplicated the empty-state labels on every rebuild.
        while self.nearby_devices_layout.count() > 0:
            item = self.nearby_devices_layout.takeAt(0)
            if item is None:
                continue
            widget = item.widget()
            if widget is not None:
                widget.setParent(None)
                widget.deleteLater()

        current_ip = self.txt_peer_name.text().strip()

        if not peers:
            # Empty state: guidance plus the faded drop prompt, stacked
            # vertically so the two never compete for horizontal space.
            empty_container = QWidget()
            empty_container.setStyleSheet("background: transparent;")
            empty_layout = QVBoxLayout(empty_container)
            empty_layout.setContentsMargins(8, 8, 8, 8)
            empty_layout.setSpacing(6)

            if not self.discovery_ok and not self.lan_ok:
                lbl_none = QLabel("Device discovery unavailable")
                lbl_none.setStyleSheet(f"color: {SOLORA_ALERT_RED}; font-size: {FS_SMALL}; font-weight: bold;")
                lbl_none.setAlignment(Qt.AlignmentFlag.AlignCenter)
                lbl_none.setAccessibleName("Device discovery unavailable")
                empty_layout.addWidget(lbl_none)

                reason = (
                    self.lan_error or self.discovery_error
                    or ZEROCONF_UNAVAILABLE_REASON or "unknown error"
                )
                lbl_hint = QLabel(
                    f"{reason}\nBoth discovery methods failed. Use manual IP entry below."
                )
                lbl_hint.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM};")
                lbl_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
                lbl_hint.setWordWrap(True)
                lbl_hint.setToolTip(reason)
                empty_layout.addWidget(lbl_hint)

                self._add_drop_hint(empty_layout)
                self.nearby_devices_layout.addWidget(empty_container)
                self.lbl_scanning.setText("● Discovery off")
                self.lbl_scanning.setStyleSheet(f"color: {SOLORA_ALERT_RED}; font-size: {FS_META_SM}; font-weight: bold;")
                self.lbl_scanning.setToolTip(reason)
                return

            lbl_none = QLabel("No devices found on this Wi-Fi")
            lbl_none.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_SMALL}; font-weight: bold;")
            lbl_none.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty_layout.addWidget(lbl_none)

            lbl_hint = QLabel("Enable 'Receiving' on the target device to appear here")
            lbl_hint.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM};")
            lbl_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty_layout.addWidget(lbl_hint)

            self._add_drop_hint(empty_layout)
            self.nearby_devices_layout.addWidget(empty_container)

            self.lbl_scanning.setText("● Scanning...")
            self.lbl_scanning.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_META_SM};")
            self.lbl_scanning.setToolTip("")
        else:
            self.lbl_scanning.setText(f"● {len(peers)} device(s) found")
            self.lbl_scanning.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-size: {FS_META_SM}; font-weight: bold;")
            self.lbl_scanning.setToolTip("")

            for peer in peers:
                is_selected = peer.host == current_ip

                # Create a card-like widget for each peer.
                # Item 11: minimum height so a longer device name can wrap.
                # Item 20: a real QPushButton (was a QFrame with a monkeypatched
                # mousePressEvent) so it is focusable, tabbable and has a role.
                peer_card = QPushButton()
                peer_card.setMinimumHeight(52)
                peer_card.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
                peer_card.setFlat(True)
                peer_card.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
                sel_color = SOLORA_ENERGY_GREEN if is_selected else SOLORA_CYAN
                sel_bg = alpha(SOLORA_ENERGY_GREEN, 0.12) if is_selected else SOLORA_SURFACE_CARD
                peer_card.setStyleSheet(f"""
                    QPushButton {{
                        background-color: {sel_bg};
                        color: {SOLORA_TEXT_PRIMARY};
                        border: 1px solid {sel_color};
                        border-radius: 10px;
                        text-align: left;
                    }}
                    QPushButton:hover {{
                        background-color: {alpha(SOLORA_CYAN, 0.15)};
                        border-color: {SOLORA_CYAN};
                    }}
                    QPushButton:focus {{ border: 2px solid {SOLORA_TEXT_PRIMARY}; }}
                    QLabel {{ background: transparent; }}
                """)
                peer_card.setAccessibleName(f"Connect to {peer.name}")
                peer_card.setAccessibleDescription(f"{peer.host} port {peer.port}")
                peer_card.setToolTip(f"{peer.name} — {peer.host}:{peer.port}")

                card_layout = QHBoxLayout(peer_card)
                card_layout.setContentsMargins(12, 8, 12, 8)
                card_layout.setSpacing(10)

                # Device icon
                icon_lbl = QLabel("📱")
                icon_lbl.setStyleSheet(f"font-size: {FS_XL}; color: {sel_color}; background: transparent;")
                icon_lbl.setFixedSize(32, 32)
                card_layout.addWidget(icon_lbl)

                # Device info
                info_layout = QVBoxLayout()
                info_layout.setSpacing(1)

                name_lbl = QLabel(peer.name)
                name_lbl.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY if not is_selected else sel_color}; font-weight: bold; font-size: {FS_BODY}; background: transparent;")
                info_layout.addWidget(name_lbl)

                ip_port_lbl = QLabel(f"{peer.host}:{peer.port}")
                ip_port_lbl.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-family: {FONT_MONO}; font-size: {FS_META_XS}; background: transparent;")
                info_layout.addWidget(ip_port_lbl)

                card_layout.addLayout(info_layout)
                card_layout.addStretch()

                # Selected indicator
                if is_selected:
                    check_lbl = QLabel("✓")
                    check_lbl.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-weight: bold; font-size: {FS_DISPLAY}; background: transparent;")
                    check_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
                    check_lbl.setFixedWidth(24)
                    card_layout.addWidget(check_lbl)

                peer_card.clicked.connect(
                    lambda _=False, h=peer.host, p=peer.port, n=peer.name: self.select_peer(h, p, n))

                self.nearby_devices_layout.addWidget(peer_card)

        self.nearby_devices_layout.addStretch()

    def test_connection(self):
        """Item 2: run the /health probe on a worker thread.

        The old version did a blocking ``requests.get(timeout=4)`` plus a
        re-entrant ``QApplication.processEvents()`` inline on the GUI thread, so
        every nearby-device / recent-IP click froze the window for up to 4s.
        """
        ip = self.txt_peer_name.text().strip()
        port = self.txt_port.text().strip() or str(SETTINGS.port)

        if not ip:
            self.lbl_conn.setText("● Enter a host or pick a device")
            self.lbl_conn.setStyleSheet(f"color: {SOLORA_AMBER}; font-weight: bold;")
            self.lbl_conn.setToolTip("")
            return

        # Cancel any in-flight probe: supersede its result and let it finish.
        if self.conn_tester is not None and self.conn_tester.isRunning():
            self.conn_tester.client.interrupt()

        self.lbl_conn.setText(f"● Testing {ip}:{port}...")
        self.lbl_conn.setStyleSheet(f"color: {SOLORA_AMBER}; font-weight: bold;")
        self.lbl_conn.setToolTip("")
        self.btn_test.setEnabled(False)

        tester = ConnectionTester(f"http://{ip}:{port}", parent=self)
        tester.finished_probe.connect(self._on_connection_probe, Qt.ConnectionType.QueuedConnection)
        self.conn_tester = tester
        tester.finished.connect(tester.deleteLater)
        tester.start()

    @pyqtSlot(bool, str, str)
    def _on_connection_probe(self, ok: bool, message: str, detail: str):
        self.btn_test.setEnabled(True)
        if ok:
            self.lbl_conn.setText(message)
            self.lbl_conn.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-weight: bold;")
            self.lbl_conn.setToolTip("")
            ip = self.txt_peer_name.text().strip()
            if ip and ip != "127.0.0.1":
                save_recent_ip(ip)
                self.refresh_recent_ips_ui()
        else:
            # Item 17: keep the reason instead of a bare "Unreachable".
            self.lbl_conn.setText(f"{message} — {detail}" if detail else message)
            self.lbl_conn.setStyleSheet(f"color: {SOLORA_ALERT_RED}; font-weight: bold;")
            self.lbl_conn.setToolTip(detail)
            self._set_log(
                f"⚠ {self.txt_peer_name.text().strip()}:{self.txt_port.text().strip()} "
                f"is not reachable ({detail}). Check the port, the IP, and that "
                f"'ENABLE RECEIVING' is on at the other end."
            )

    def set_file(self, path: str):
        """Item 11/16: single entry point for 'a file was chosen'.

        Used by the file dialog and by the main window's dropEvent, so
        drag-and-drop feeds exactly the same state as SELECT FILE FROM DISK.
        """
        if not path:
            return
        self._teardown_worker(cancel=True)
        self.selected_file = path
        self.active_file = path
        self.active_transfer_id = None
        try:
            sz = os.path.getsize(path)
        except OSError as exc:
            self.lbl_badge.setText("UNREADABLE")
            self.lbl_badge.setStyleSheet(badge_qss(SOLORA_ALERT_RED, SOLORA_BG))
            self._set_log(f"⚠ Cannot read {path}: {type(exc).__name__}: {exc}")
            return
        # Item 23: elide the path, keep the full value as a tooltip.
        self.lbl_file.setText(elide(os.path.basename(path), self.lbl_file))
        self.lbl_file.setToolTip(path)
        self.lbl_vol.setText(f"0 B / {format_size(sz)} (0%)")
        # Deliberately no "Ready to transfer <name> (<size>)" line here: the card
        # directly above already prints the name, the size and the 0%, so the
        # banner restated all three. It is hidden instead of emptied, because an
        # empty bordered box reads as a broken widget.
        self._set_log("")
        self.btn_start.setEnabled(True)
        self.btn_start.setText("▶  START TRANSFER")
        self.btn_clear.setEnabled(True)
        self.prog_bar.setValue(0)

    def _set_log(self, message: str):
        """Show the status banner with `message`, or hide it when nothing to say.

        Every real event -- connecting, resuming, an error -- still goes here.
        Only the redundant chatter is suppressed, and hiding the whole banner
        beats leaving an empty bordered box on screen.
        """
        if message:
            self.lbl_log.setText(message)
            self.log_box.setVisible(True)
        else:
            self.lbl_log.setText("")
            self.log_box.setVisible(False)

    def clear_file(self):
        """Put the file back to "NO FILE SELECTED" without sending it.

        Mirrors Android's NEW button. A transfer already in flight is torn down
        first, because leaving its worker writing to a file the card no longer
        names is how the UI ends up describing a transfer it is not running.
        """
        if self.active_worker is not None or self.active_transfer_id is not None:
            self._teardown_worker(cancel=True)

        self.selected_file = None
        self.active_file = None
        self.active_transfer_id = None
        self.active_client = None

        self.lbl_file.setText("NO FILE SELECTED")
        self.lbl_file.setToolTip("")
        self.lbl_vol.setText("0 B / 0 B (0%)")
        self.lbl_badge.setText("STANDBY")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_SURFACE_ELEVATED, SOLORA_TEXT_SECONDARY))
        self.prog_bar.setValue(0)
        self._set_log("")

        self.btn_start.setEnabled(False)
        self.btn_start.setText("▶  START TRANSFER")
        self.btn_clear.setEnabled(False)
        self.btn_pause.setEnabled(False)
        self.btn_cancel.setEnabled(False)

    def pick_file(self):
        path, _ = QFileDialog.getOpenFileName(self, "Select File to Stream", os.path.expanduser("~"))
        if path:
            self.set_file(path)

    def _teardown_worker(self, cancel: bool = False, wait_ms: int = 4000):
        """Item 16: stop and reap the previous worker before starting a new one.

        The old code just overwrote ``self.active_worker``, orphaning the old
        QThread while pause/cancel kept mutating the *new* transfer's client.
        """
        worker = self.active_worker
        if worker is None:
            self.active_client = None
            return
        self.active_worker = None
        try:
            worker.request_transfer_interrupt(cancel=cancel)
        except Exception as exc:
            print(f"[Sender] interrupt: {type(exc).__name__}: {exc}")
        if not worker.wait(wait_ms):
            print("[Sender] previous worker did not stop within "
                  f"{wait_ms}ms; abandoning it (it is a daemon-less QThread)")
        worker.deleteLater()
        self.active_client = None

    def start_or_resume(self):
        if not self.selected_file:
            return
        # Item 16: tear down any previous worker first.
        self._teardown_worker(cancel=True)
        ip = self.txt_peer_name.text().strip()
        port = self.txt_port.text().strip() or str(SETTINGS.port)
        if ip and ip != "127.0.0.1":
            save_recent_ip(ip)
            self.refresh_recent_ips_ui()
        # Item 18: client honours the persisted chunk size / timeout / verify.
        self.active_client = make_client(f"http://{ip}:{port}")
        self.btn_start.setEnabled(False); self.btn_pick.setEnabled(False)
        self.btn_pause.setEnabled(True); self.btn_cancel.setEnabled(True)
        self.btn_cancel.setStyleSheet(cancel_button_qss())
        self._last_progress_emit = 0.0
        self.active_worker = TransferWorker(self.active_client, self.selected_file, self.active_transfer_id)
        self.active_worker.progress_signal.connect(self.on_progress)
        self.active_worker.status_signal.connect(self.on_status)
        self.active_worker.completed_signal.connect(self.on_completed)
        self.active_worker.paused_signal.connect(self.on_paused)
        self.active_worker.cancelled_signal.connect(self.on_cancelled)
        self.active_worker.error_signal.connect(self.on_error)
        self.active_worker.finished.connect(self._on_worker_finished)
        self.active_worker.start()

    def _on_worker_finished(self):
        self.btn_pause.setEnabled(False)
        if self.active_worker is not None and self.active_worker.isFinished():
            self.btn_cancel.setEnabled(False)

    def pause_transfer(self):
        # Item 3: a real sticky flag the send loop waits on. Previously the
        # worker raised Exception("Transfer paused by user") -> on_error ->
        # INTERRUPTED badge, clobbering the PAUSED state immediately.
        worker = self.active_worker
        if worker is None or not worker.isRunning():
            self._set_log("Nothing is running to pause.")
            return
        try:
            worker.client.pause()
        except Exception as exc:
            self._set_log(f"⚠ Could not pause: {type(exc).__name__}: {exc}")
            return
        self.lbl_badge.setText("PAUSED")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_AMBER, SOLORA_BG))
        self._set_log("Transfer paused. Byte offset preserved.")
        self.btn_pause.setEnabled(False)
        self.btn_start.setText("▶  RESUME")
        self.btn_start.setEnabled(True)

    def cancel_transfer(self):
        # Item 3: disable btn_cancel here (on_error never did), and mark the
        # cancellation deliberate so the worker's result is CANCELLED, not
        # INTERRUPTED.
        worker = self.active_worker
        self.btn_cancel.setEnabled(False)
        if worker is None or not worker.isRunning():
            self._show_cancelled()
            return
        try:
            worker.request_transfer_interrupt(cancel=True)
        except Exception as exc:
            print(f"[Sender] cancel: {type(exc).__name__}: {exc}")
        self._show_cancelled()

    def _show_cancelled(self):
        self.lbl_badge.setText("CANCELLED")
        # Item 8: SOLORA_BG on ALERT_RED is 5.99:1; plain white was 3.27:1.
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_ALERT_RED, SOLORA_BG))
        self._set_log("Transfer cancelled by user. Progress discarded.")
        self.btn_pause.setEnabled(False)
        self.btn_start.setText("▶  START TRANSFER")
        self.btn_start.setEnabled(True)
        self.btn_pick.setEnabled(True)

    def on_paused(self):
        """Worker confirmed a deliberate pause (not a failure)."""
        self.lbl_badge.setText("PAUSED")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_AMBER, SOLORA_BG))
        self._set_log("Transfer paused. Byte offset preserved.")
        self.btn_pause.setEnabled(False)
        self.btn_start.setText("▶  RESUME")
        self.btn_start.setEnabled(True)

    def on_cancelled(self):
        self._show_cancelled()

    def on_progress(self, curr, total, spd, eta):
        # Item 24: throttle to ~20 Hz. Progress arrives per chunk; with a 1 MiB
        # chunk that is 3 QLabel.setText + 1 setValue thousands of times.
        now = time.monotonic()
        if now - self._last_progress_emit < self._progress_min_interval:
            return
        self._last_progress_emit = now
        pct = int((curr/total)*100) if total > 0 else 0
        self.prog_bar.setValue(pct)
        self.lbl_vol.setText(f"{format_size(curr)} / {format_size(total)} ({pct}%)")
        self.lbl_speed.setText(f"{format_size(int(spd))}/s")
        self.lbl_eta.setText(f"ETA: {int(eta)}s" if eta < 3600 else "ETA: >1h")

    def on_status(self, badge, msg):
        self.lbl_badge.setText(badge.upper())
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_CYAN, SOLORA_BG))
        self._set_log(msg)
        self.lbl_log.setToolTip(msg)

    def on_completed(self, res):
        self.lbl_badge.setText("COMPLETED")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_ENERGY_GREEN, SOLORA_BG))
        sha = (res or {}).get("calculated_sha256", "")
        self._set_log(f"✓ Transfer Complete! SHA-256: {sha[:16]}...")
        self.lbl_log.setToolTip(f"SHA-256: {sha}")
        self.btn_pause.setEnabled(False); self.btn_cancel.setEnabled(False)
        self.btn_start.setEnabled(True); self.btn_start.setText("▶  START TRANSFER"); self.btn_pick.setEnabled(True)
        path = self.active_file or self.selected_file
        try:
            size = os.path.getsize(path)
        except (OSError, TypeError):
            size = 0
        save_history_entry({
            "filename": os.path.basename(path or ""),
            "size": size,
            "sha256": sha, "status": "completed",
            "timestamp": datetime.now().isoformat(),
            "transfer_id": (res or {}).get("transfer_id", ""),
            "target_ip": self.txt_peer_name.text().strip(),
            "direction": "sent",
            "file_path": path
        })
        QMessageBox.information(self, "Success", "File transfer completed and cryptographically verified!")

    def on_error(self, err):
        self.lbl_badge.setText("INTERRUPTED")
        # Item 8: SOLORA_BG on ALERT_RED = 5.99:1 (was 'white' at 3.27:1).
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_ALERT_RED, SOLORA_BG))
        msg = f"⚠ Interrupted: {err}. Progress saved — tap Resume when ready."
        self._set_log(msg)
        self.lbl_log.setToolTip(msg)
        # Item 3: cancel must not stay clickable after a failure.
        self.btn_pause.setEnabled(False); self.btn_cancel.setEnabled(False)
        self.btn_start.setText("▶  RESUME")
        self.btn_start.setEnabled(True); self.btn_pick.setEnabled(True)

    def resume_from_history(self, entry):
        self.active_transfer_id = entry.get("transfer_id")
        target = entry.get("target_ip", "127.0.0.1")
        # A history target can read "Received from 10.3.207.215"; keep the host.
        host = re.search(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", str(target))
        self.txt_peer_name.setText(host.group(0) if host else str(target))
        self.lbl_file.setText(elide(entry.get("filename", "Unknown"), self.lbl_file))
        self.lbl_file.setToolTip(entry.get("file_path") or entry.get("filename", ""))
        self.lbl_badge.setText("RESUMING")
        self.lbl_badge.setStyleSheet(badge_qss(SOLORA_AMBER, SOLORA_BG))
        self._set_log(f"Resuming session {self.active_transfer_id}...")
        path, _ = QFileDialog.getOpenFileName(self, f"Re-select: {entry.get('filename','')}", os.path.expanduser("~"))
        if path:
            self.selected_file = path
            self.active_file = path
            self.btn_start.setEnabled(True); self.btn_start.setText("▶  RESUME")

    def shutdown(self):
        """Item 16: called from the main window's closeEvent."""
        self._discovery_timer.stop()
        self._teardown_worker(cancel=True, wait_ms=3000)
        tester = self.conn_tester
        self.conn_tester = None
        if tester is not None:
            try:
                tester.client.interrupt()
                if tester.isRunning():
                    tester.wait(1500)
                tester.deleteLater()
            except Exception as exc:
                print(f"[Sender] tester shutdown: {type(exc).__name__}: {exc}")


# ─── Receiver Screen ──────────────────────────────────────────────────────────
class ReceiverScreen(QWidget):
    ip_updated_signal = pyqtSignal(str)

    def __init__(self, parent=None, bridge: WorkerBridge = None):
        super().__init__(parent)
        self.bridge = bridge
        # Item 18: the port comes from Settings, not a hardcoded 8000 literal.
        self.server = EmbeddedReceiverServer(host="0.0.0.0", port=SETTINGS.port)
        self._device_name = load_device_name()
        self._rx_transfer_id = None
        self._last_rx_state = {}
        self._build()

        # Item 1: the server used to call this handler directly from an HTTP
        # request thread (ThreadingMixIn). The bridge re-emits it as a Qt signal
        # delivered on the GUI thread via QueuedConnection.
        if bridge is not None:
            bridge.server_state.connect(self.on_server_state_update,
                                        Qt.ConnectionType.QueuedConnection)
            self.server.state_callback = bridge.on_server_state
        else:
            # No bridge (headless/unit context): keep it direct but safe.
            self.server.state_callback = self._direct_state_callback

    def _direct_state_callback(self, rec: dict):
        try:
            self.on_server_state_update(dict(rec or {}))
        except Exception as exc:
            print(f"[Receiver] state update: {type(exc).__name__}: {exc}")

    def _build(self):
        # Wrap everything in a main scroll area so long file lists scroll smoothly
        outer_layout = QVBoxLayout(self)
        outer_layout.setContentsMargins(0, 0, 0, 0)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setStyleSheet("QScrollArea { border: none; background: transparent; }")

        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(28, 24, 28, 24)
        layout.setSpacing(16)

        # 1. Top Bar
        top_row = QHBoxLayout()
        title = QLabel("RECEIVER HUB (P2P)")
        title.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-size: {FS_HEADING}; font-weight: bold; letter-spacing: 2px;")
        top_row.addWidget(title)
        top_row.addStretch()


        self.btn_toggle_server = QPushButton("⚡  ENABLE RECEIVING")
        self.btn_toggle_server.setFixedHeight(38)
        self.btn_toggle_server.setMinimumWidth(170)
        self.btn_toggle_server.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        # Item 4: matching :hover so the global cyan hover cannot recolour the
        # dark label on green/red.
        self.btn_toggle_server.setStyleSheet(solid_button_qss(SOLORA_ENERGY_GREEN, SOLORA_BG, 8))
        self.btn_toggle_server.setAccessibleName("Enable or disable receiving")
        self.btn_toggle_server.setToolTip("Start or stop the receiving endpoint")
        self.btn_toggle_server.clicked.connect(self.toggle_server)
        top_row.addWidget(self.btn_toggle_server)
        layout.addLayout(top_row)

        # 2. Connection / Endpoint Card
        card = QFrame(); card.setObjectName("card")
        cl = QVBoxLayout(card); cl.setContentsMargins(20,18,20,18); cl.setSpacing(12)

        # Device Name section (always visible)
        lbl_device_name_header = QLabel("DEVICE NAME  —  Nearby senders will see this name instead of your IP")
        lbl_device_name_header.setStyleSheet(f"color: {SOLORA_CYAN}; font-weight: bold; font-size: {FS_SMALL}; letter-spacing: 1px;")
        cl.addWidget(lbl_device_name_header)

        name_row = QHBoxLayout(); name_row.setSpacing(8)
        self.txt_device_name = QLineEdit(self._device_name)
        self.txt_device_name.setPlaceholderText(f"e.g. {socket.gethostname()}")
        self.txt_device_name.setAccessibleName("This device's advertised name")
        self.txt_device_name.setStyleSheet(f"""
            QLineEdit {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_TEXT_PRIMARY};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 8px;
                padding: 7px 12px;
                font-size: {FS_HEADING};
                font-weight: bold;
            }}
            QLineEdit:focus {{ border-color: {SOLORA_CYAN}; }}
            QLineEdit:disabled {{ color: {SOLORA_TEXT_MUTED}; }}
        """)
        self.txt_device_name.textChanged.connect(self._on_device_name_changed)
        name_row.addWidget(self.txt_device_name, 1)
        cl.addLayout(name_row)

        div = QFrame(); div.setFrameShape(QFrame.Shape.HLine)
        div.setStyleSheet(f"color: {SOLORA_BORDER}; background: {SOLORA_BORDER}; max-height: 1px;")
        cl.addWidget(div)


        status_row = QHBoxLayout()
        lbl_h = QLabel("INCOMING ENDPOINT — DEVICE B MODE")
        lbl_h.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-weight: bold; font-size: {FS_BODY}; letter-spacing: 1px;")
        status_row.addWidget(lbl_h)
        status_row.addStretch()

        self.lbl_server_status = QLabel("● SERVER OFFLINE")
        self.lbl_server_status.setStyleSheet(f"color: {SOLORA_ALERT_RED}; font-weight: bold; font-size: {FS_SMALL};")
        self.lbl_server_status.setAccessibleName("Receiver server status")
        status_row.addWidget(self.lbl_server_status)
        cl.addLayout(status_row)

        # Show device name + port when enabled (instead of IP)
        self.lbl_broadcast_status = QLabel("Not broadcasting")
        self.lbl_broadcast_status.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: {FS_SMALL};")
        cl.addWidget(self.lbl_broadcast_status)

        # No local IP is displayed anywhere on this screen. It used to be behind
        # a collapsible "Show local IP" hint and also encoded into a scannable QR
        # bitmap, which put the machine's address on screen for anyone nearby
        # and in any screenshot. Senders resolve this device by name over
        # mDNS/NSD, so the address is never needed. Matches the Android receiver.
        ips = get_local_ips()
        self.current_ip = ips[0] if ips else "127.0.0.1"

        # Device name, the only identity shown for pairing.
        self.lbl_device_identity = QLabel(
            f"<b style='color:{SOLORA_ENERGY_GREEN};'>Broadcasting as:</b> "
            f"<b style='color:{SOLORA_ENERGY_GREEN};'>{self._device_name}</b>"
        )
        self.lbl_device_identity.setWordWrap(True)
        self.lbl_device_identity.setAccessibleName("This device name as seen by nearby senders")
        cl.addWidget(self.lbl_device_identity)

        qr_row = QHBoxLayout()
        self.lbl_qr = QLabel()
        self.lbl_qr.setStyleSheet("background: white; padding: 6px; border-radius: 8px;")
        self.lbl_qr.setFixedSize(160,160)
        # Item 20: alt text for the QR (it carries the pairing payload).
        self.lbl_qr.setAccessibleName("Pairing QR code")
        self.lbl_qr.setAccessibleDescription(
            "Scan or copy this code on the sender device to prefill host and port.")
        # The QR encodes the device NAME, not an address, so scanning it does
        # not disclose this machine's IP to a camera.
        self.update_qr(build_pairing_name(self._device_name))
        qr_row.addWidget(self.lbl_qr)

        qr_row.addSpacing(16)
        desc = QVBoxLayout()
        desc.addWidget(QLabel(f"<b style='color:{SOLORA_CYAN};'>QUICK PAIRING:</b>"))

        self.lbl_step2 = QLabel(f"2. On sender device, tap your device name: <b>{self._device_name}</b> (no IP needed).")
        self.lbl_step2.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-size: 9pt;")
        self.lbl_step2.setWordWrap(True)

        for s in [
            "1. Click <b>⚡ ENABLE RECEIVING</b> above (No manual terminal needed).",
        ]:
            l = QLabel(s); l.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-size: 9pt;"); l.setWordWrap(True)
            desc.addWidget(l)
        desc.addWidget(self.lbl_step2)
        for s in [
            "3. Sender will auto-discover you on this Wi-Fi.",
            f"4. Incoming files save to: <b>{self.server.upload_dir}</b>",
        ]:
            l = QLabel(s); l.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-size: 9pt;"); l.setWordWrap(True)
            desc.addWidget(l)
        desc.addStretch()
        qr_row.addLayout(desc); qr_row.addStretch()
        cl.addLayout(qr_row)
        layout.addWidget(card)
        
        # 3. Live Payload Monitor Card
        self.payload_card = QFrame()
        self.payload_card.setObjectName("card")
        pl = QVBoxLayout(self.payload_card)
        pl.setContentsMargins(18, 16, 18, 16)
        pl.setSpacing(10)
        
        pl_top = QHBoxLayout()
        pl_title = QLabel("LIVE INCOMING PAYLOAD STREAM")
        pl_title.setStyleSheet(f"color: {SOLORA_CYAN}; font-weight: bold; font-size: 9pt; letter-spacing: 1px;")
        
        self.lbl_rx_status_badge = QLabel("STANDBY")
        self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_TEXT_MUTED}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
        
        pl_top.addWidget(pl_title)
        pl_top.addStretch()
        pl_top.addWidget(self.lbl_rx_status_badge)
        pl.addLayout(pl_top)

        self.lbl_rx_filename = QLabel("No active incoming stream")
        self.lbl_rx_filename.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-weight: bold; font-size: 11pt;")
        pl.addWidget(self.lbl_rx_filename)

        # Progress bar (Themed Energy Green)
        self.rx_prog_bar = QProgressBar()
        self.rx_prog_bar.setRange(0, 100)
        self.rx_prog_bar.setValue(0)
        self.rx_prog_bar.setFixedHeight(12)
        self.rx_prog_bar.setTextVisible(False)
        self.rx_prog_bar.setStyleSheet(f"""
            QProgressBar {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 6px;
            }}
            QProgressBar::chunk {{
                background-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 {SOLORA_CYAN}, stop:1 {SOLORA_ENERGY_GREEN});
                border-radius: 5px;
            }}
        """)
        pl.addWidget(self.rx_prog_bar)

        # Stats row: Bytes / Total (Pct%) on left, Transfer Speed on right
        stats_row = QHBoxLayout()
        self.lbl_rx_vol = QLabel("0 B / 0 B (0%)")
        self.lbl_rx_vol.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-family: monospace; font-size: 9.5pt;")
        
        self.lbl_rx_speed = QLabel("0 KB/s")
        self.lbl_rx_speed.setStyleSheet(f"color: {SOLORA_NEON_LIME}; font-weight: bold; font-family: monospace; font-size: 9.5pt;")

        # Time left, hidden until a rate has actually been measured -- a
        # countdown that reads 0s for the first second is noise, not information.
        self.lbl_rx_eta = QLabel("")
        self.lbl_rx_eta.setStyleSheet(f"color: {SOLORA_AMBER}; font-weight: bold; font-family: monospace; font-size: 9.5pt;")
        self.lbl_rx_eta.setVisible(False)
        
        stats_row.addWidget(self.lbl_rx_vol)
        stats_row.addWidget(self.lbl_rx_eta)
        stats_row.addStretch()
        stats_row.addWidget(self.lbl_rx_speed)
        pl.addLayout(stats_row)

        # Pause / Resume / Cancel for the transfer arriving right now.
        #
        # The Hub was read-only: once a push started, the only way to stop it was
        # to take the endpoint down, which also dropped the session. These act on
        # the live session instead, so the sender is told to hold (HTTP 409) or
        # that it is finished (410) rather than stalling against a socket nobody
        # is reading. Same contract as the Android Hub.
        self.rx_controls = QHBoxLayout()
        self.rx_controls.setSpacing(8)

        self.btn_rx_pause = QPushButton("⏸  PAUSE")
        self.btn_rx_pause.setFixedHeight(34)
        self.btn_rx_pause.setStyleSheet(f"color: {SOLORA_CYAN}; border: 1px solid {SOLORA_CYAN}; border-radius: 6px; padding: 4px 14px; font-weight: bold; font-size: 8.5pt;")
        self.btn_rx_pause.clicked.connect(self.toggle_rx_pause)

        self.btn_rx_cancel = QPushButton("✖  CANCEL")
        self.btn_rx_cancel.setFixedHeight(34)
        self.btn_rx_cancel.setStyleSheet(f"color: {SOLORA_ALERT_RED}; border: 1px solid {SOLORA_ALERT_RED}; border-radius: 6px; padding: 4px 14px; font-weight: bold; font-size: 8.5pt;")
        self.btn_rx_cancel.clicked.connect(self.cancel_rx_transfer)

        self.rx_controls.addWidget(self.btn_rx_pause)
        self.rx_controls.addWidget(self.btn_rx_cancel)
        self.rx_controls.addStretch()
        self.rx_controls_widget = QWidget()
        self.rx_controls_widget.setLayout(self.rx_controls)
        self.rx_controls_widget.setVisible(False)
        pl.addWidget(self.rx_controls_widget)

        self.lbl_payload_info = QLabel("Waiting for incoming connection stream...")
        self.lbl_payload_info.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 8.5pt; font-family: monospace;")
        pl.addWidget(self.lbl_payload_info)
        layout.addWidget(self.payload_card)

        # 4. Received Files Area
        recv_header = QHBoxLayout()
        lbl_recv_title = QLabel("RECEIVED FILES")
        lbl_recv_title.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-weight: bold; font-size: 11pt; letter-spacing: 1px;")
        recv_header.addWidget(lbl_recv_title)
        recv_header.addStretch()

        btn_open_folder = QPushButton("📁  OPEN UPLOAD FOLDER")
        btn_open_folder.setFixedHeight(34)
        btn_open_folder.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; border: 1px solid {SOLORA_BORDER}; border-radius: 6px; padding: 4px 12px; font-weight: bold; font-size: 8.5pt;")
        btn_open_folder.clicked.connect(lambda: open_file_or_dir(self.server.upload_dir))
        recv_header.addWidget(btn_open_folder)

        btn_refresh_files = QPushButton("⟳  REFRESH")
        btn_refresh_files.setFixedHeight(34)
        btn_refresh_files.setStyleSheet(f"color: {SOLORA_CYAN}; border: 1px solid {SOLORA_CYAN}; border-radius: 6px; padding: 4px 12px; font-weight: bold; font-size: 8.5pt;")
        btn_refresh_files.clicked.connect(self.refresh_received_files)
        recv_header.addWidget(btn_refresh_files)
        layout.addLayout(recv_header)

        # Received files list container
        self.files_container = QFrame()
        self.files_container.setObjectName("card")
        self.files_layout = QVBoxLayout(self.files_container)
        self.files_layout.setContentsMargins(14, 12, 14, 12)
        self.files_layout.setSpacing(8)
        layout.addWidget(self.files_container)

        layout.addStretch()
        scroll.setWidget(content)
        outer_layout.addWidget(scroll)

        # NOTE: do NOT re-assign self.server.state_callback here.
        # __init__ already wired it through WorkerBridge so the HTTP request
        # thread only emits a Qt signal delivered on the GUI thread. Assigning
        # the widget handler directly (as this line used to) made every HTTP
        # worker thread call QLabel.setText / setStyleSheet / deleteLater
        # off the GUI thread.
        self.refresh_received_files()

    def refresh_ip(self):
        """Refresh the local address used for binding and pairing.

        The address is no longer rendered anywhere: the old endpoint labels
        were set here and then immediately blanked a few lines later, which was
        the dead set-then-clear flagged in the audit. Only the pairing QR (which
        now encodes the device name) and the instructions are updated.
        """
        ips = get_local_ips()
        self.current_ip = ips[0] if ips else "127.0.0.1"
        self.lbl_device_identity.setText(
            f"<b style='color:{SOLORA_ENERGY_GREEN};'>Broadcasting as:</b> "
            f"<b style='color:{SOLORA_ENERGY_GREEN};'>{self._device_name}</b>"
        )
        self.lbl_step2.setText(f"2. Tap your device name <b>{self._device_name}</b> on the sender device (auto-discovered).")
        self.update_qr(build_pairing_name(self._device_name))
        self.ip_updated_signal.emit(self.current_ip)

    def refresh_received_files(self):
        """Scan upload directory and list all received files with open action."""
        while self.files_layout.count() > 0:
            item = self.files_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        upload_dir = self.server.upload_dir
        if not os.path.exists(upload_dir):
            os.makedirs(upload_dir, exist_ok=True)

        files = sorted(os.listdir(upload_dir), key=lambda f: os.path.getmtime(os.path.join(upload_dir, f)), reverse=True)
        files = [f for f in files if not f.startswith(".")]

        if not files:
            empty_lbl = QLabel("No files received yet.\nStart server and send a payload from Device A to receive files here.")
            empty_lbl.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 9pt;")
            empty_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty_lbl.setContentsMargins(12, 18, 12, 18)
            self.files_layout.addWidget(empty_lbl)
            return

        for fname in files:
            fpath = os.path.join(upload_dir, fname)
            sz = os.path.getsize(fpath)
            mtime = datetime.fromtimestamp(os.path.getmtime(fpath)).strftime("%Y-%m-%d  %H:%M:%S")

            row_card = QFrame()
            row_card.setStyleSheet(f"background: {SOLORA_SURFACE_ELEVATED}; border: 1px solid {SOLORA_BORDER}; border-radius: 8px;")
            rl = QHBoxLayout(row_card)
            rl.setContentsMargins(12, 10, 12, 10)
            rl.setSpacing(12)

            file_icon = QLabel("📄")
            file_icon.setStyleSheet("font-size: 16pt;")
            rl.addWidget(file_icon)

            info_col = QVBoxLayout()
            info_col.setSpacing(2)
            
            lbl_name = QLabel(fname)
            lbl_name.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-weight: bold; font-size: 9.5pt;")
            lbl_meta = QLabel(f"{format_size(sz)}   ·   Received: {mtime}")
            lbl_meta.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 8pt; font-family: monospace;")
            
            info_col.addWidget(lbl_name)
            info_col.addWidget(lbl_meta)
            rl.addLayout(info_col)
            rl.addStretch()

            btn_open = QPushButton("▶  OPEN FILE")
            btn_open.setFixedHeight(32)
            btn_open.setStyleSheet(f"background-color: {SOLORA_SURFACE_CARD}; color: {SOLORA_ENERGY_GREEN}; border: 1px solid {SOLORA_ENERGY_GREEN}; border-radius: 6px; padding: 4px 10px; font-weight: bold; font-size: 8.5pt;")
            btn_open.clicked.connect(lambda _, p=fpath: open_file_or_dir(p))
            rl.addWidget(btn_open)

            # "DIR" rather than the 📂 emoji: this Qt build has no emoji font,
            # so U+1F4C1 rendered as an empty box -- the same failure the delete
            # button hit, which left two identical blank buttons next to each
            # other. Verified against the running font: U+2716 and plain text
            # render, U+1F4C1 and U+1F5D1 do not.
            btn_show_folder = QPushButton("DIR")
            btn_show_folder.setFixedSize(42, 32)
            btn_show_folder.setToolTip("Show in file manager")
            btn_show_folder.setStyleSheet(f"background-color: {SOLORA_SURFACE_CARD}; color: {SOLORA_TEXT_SECONDARY}; border: 1px solid {SOLORA_BORDER}; border-radius: 6px; font-size: 10pt;")
            btn_show_folder.clicked.connect(lambda _, p=upload_dir: open_file_or_dir(p))
            rl.addWidget(btn_show_folder)

            btn_delete = QPushButton("✖")
            btn_delete.setFixedSize(42, 32)
            btn_delete.setToolTip("Delete this file from disk")
            btn_delete.setStyleSheet(f"background-color: {SOLORA_SURFACE_CARD}; color: {SOLORA_ALERT_RED}; border: 1px solid {SOLORA_ALERT_RED}; border-radius: 6px; font-size: 10pt;")
            btn_delete.clicked.connect(
                lambda _, p=fpath, n=fname: self.delete_received_file(p, n)
            )
            rl.addWidget(btn_delete)

            self.files_layout.addWidget(row_card)

    def delete_received_file(self, filepath: str, display_name: str = ""):
        """Delete a received file after an explicit confirmation.

        Receiving a file put it on this machine, and there was no way to undo
        that: the row offered only Open and Show-in-folder, so the only options
        were a file manager or deleting things by hand. This is the obvious
        omission.

        Confirmed first, and it says what it is about to destroy. Deletion is
        permanent and there is no trash, so a single mis-click must not be
        able to lose a 2 GB download.
        """
        name = display_name or os.path.basename(filepath)
        try:
            size = os.path.getsize(filepath)
        except OSError:
            size = 0

        confirm = QMessageBox(self)
        confirm.setWindowTitle("Delete received file?")
        confirm.setIcon(QMessageBox.Icon.Warning)
        confirm.setText(f"Delete “{name}” from this computer?")
        confirm.setInformativeText(
            f"{format_size(size)}\n{filepath}\n\n"
            "This cannot be undone — the file is not moved to a trash folder."
        )
        confirm.setStandardButtons(
            QMessageBox.StandardButton.Cancel | QMessageBox.StandardButton.Yes
        )
        confirm.setDefaultButton(QMessageBox.StandardButton.Cancel)
        if confirm.exec() != QMessageBox.StandardButton.Yes:
            return

        try:
            os.remove(filepath)
        except FileNotFoundError:
            # Someone got there first. Not an error worth a dialog.
            pass
        except OSError as exc:
            QMessageBox.critical(
                self,
                "Could not delete",
                f"“{name}” could not be deleted:\n\n{exc}\n\n"
                "Check the file's permissions and try again.",
            )
            return

        # The history row is deliberately left alone: it records that a transfer
        # happened and verified, which is still true. Its Open File button
        # already hides itself for a path that no longer exists.
        self.refresh_received_files()
        self.lbl_payload_info.setText(f"Deleted “{name}” from this computer.")

    def update_qr(self, text):
        try:
            buf = io.BytesIO(); qrcode.make(text).save(buf, "PNG")
            pix = QPixmap(); pix.loadFromData(buf.getvalue())
            pix = pix.scaled(150,150,Qt.AspectRatioMode.KeepAspectRatio,Qt.TransformationMode.SmoothTransformation)
            self.lbl_qr.setPixmap(pix)
        except Exception:
            pass

    def _on_device_name_changed(self, text: str):
        """Called when user edits the device name field."""
        self._device_name = text.strip()
        save_device_name(self._device_name)

    def toggle_server(self):
        if not self.server.is_running:
            success = self.server.start()
            if success:
                # Register NSD service with device name so senders can discover this PC
                name = self.txt_device_name.text().strip() or socket.gethostname()
                self._device_name = name
                self.server.device_name = name
                if nsd_advertiser:
                    nsd_advertiser.start(device_name=name, port=8000)
                # Also announce over UDP broadcast. mDNS does not traverse an
                # Android hotspot, so without this the phone can never discover
                # a laptop that is receiving while tethered to it.
                if lan is not None:
                    try:
                        lan.advertise(device_name=name, service_port=8000)
                    except Exception as exc:
                        print(f"[LAN] advertise failed: {type(exc).__name__}: {exc}")
                self.txt_device_name.setEnabled(False)
                self.btn_toggle_server.setText("⏹  STOP RECEIVING")
                self.btn_toggle_server.setStyleSheet(f"background-color: {SOLORA_ALERT_RED}; color: white; font-weight: bold; border-radius: 8px;")
                self.lbl_server_status.setText(f"● BROADCASTING AS: {name}")
                self.lbl_server_status.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-weight: bold; font-size: 9pt;")
                self.lbl_broadcast_status.setText(f"Broadcasting as \"{name}\" on port 8000 — Discoverable on this Wi-Fi")
                self.lbl_broadcast_status.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-size: 9pt; font-weight: bold;")
                self.lbl_payload_info.setText(f"Listening on http://0.0.0.0:8000 — Discoverable as '{name}' on this Wi-Fi.")
                # Update QR code pairing instruction
                self.lbl_step2.setText(f"2. On sender device, tap your device name: <b>{name}</b> (no IP needed).")
            else:
                QMessageBox.critical(self, "Error", "Failed to start server. Port 8000 may already be in use.")
        else:
            self.server.stop()
            if nsd_advertiser:
                nsd_advertiser.stop()
            if lan is not None:
                try:
                    lan.stop_advertise()
                except Exception as exc:
                    print(f"[LAN] stop_advertise failed: {type(exc).__name__}: {exc}")
            self.txt_device_name.setEnabled(True)
            self.btn_toggle_server.setText("⚡  ENABLE RECEIVING")
            self.btn_toggle_server.setStyleSheet(f"background-color: {SOLORA_ENERGY_GREEN}; color: {SOLORA_BG}; font-weight: bold; border-radius: 8px;")
            self.lbl_server_status.setText("● RECEIVING DISABLED")
            self.lbl_server_status.setStyleSheet(f"color: {SOLORA_ALERT_RED}; font-weight: bold; font-size: 9pt;")
            self.lbl_broadcast_status.setText("Not broadcasting")
            self.lbl_broadcast_status.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 9pt;")
            self.lbl_payload_info.setText("Server stopped. Not discoverable.")
            # Reset QR code pairing instruction
            self.lbl_step2.setText(f"2. On sender device, tap your device name: <b>{self._device_name}</b> (no IP needed).")

    def _rx_active_id(self) -> str:
        """transfer_id of the session the Hub controls should act on.

        Read from the server rather than kept in a UI field: the state record
        that drives the card is the authority, and a stale id here would pause a
        session that had already finished.
        """
        for tid, rec in list(getattr(self.server, "transfers", {}).items()):
            if rec.get("status") in ("pending", "in_progress", "paused"):
                return tid
        return ""

    def toggle_rx_pause(self):
        """Hold the incoming transfer, or let it continue if already held."""
        tid = self._rx_active_id()
        if not tid:
            return
        if self.server.transfers.get(tid, {}).get("status") == "paused":
            self.server.resume_transfer(tid)
        else:
            self.server.pause_transfer(tid)

    def cancel_rx_transfer(self):
        """End the incoming transfer and remove the partial file."""
        tid = self._rx_active_id()
        if tid:
            self.server.cancel_transfer(tid)

    def on_server_state_update(self, rec: dict):
        fname = rec.get("filename", "")
        recv = rec.get("received_bytes", 0)
        tot = rec.get("total_size", 0)
        pct = int((recv / tot * 100)) if tot > 0 else 0
        stat = rec.get("status", "pending")
        speed = rec.get("speed_bytes_sec", 0)
        sha = rec.get("calculated_sha256")

        self.lbl_rx_filename.setText(fname if fname else "Incoming stream...")
        self.rx_prog_bar.setValue(pct)
        self.lbl_rx_vol.setText(f"{format_size(recv)} / {format_size(tot)} ({pct}%)")
        self.lbl_rx_speed.setText(f"{format_size(int(speed))}/s" if stat in ("pending", "in_progress") else "0 KB/s")

        # Time left from the measured rate. Held or finished transfers get none:
        # a countdown frozen mid-transfer is worse than no countdown.
        eta = (tot - recv) / speed if speed and speed > 0 and recv < tot else 0
        if eta > 0 and stat == "in_progress":
            self.lbl_rx_eta.setText(f"· {format_eta(int(eta))} left")
            self.lbl_rx_eta.setVisible(True)
        else:
            self.lbl_rx_eta.setVisible(False)

        # Controls exist only while a transfer is actually moving.
        live = stat in ("pending", "in_progress", "paused")
        self.rx_controls_widget.setVisible(live)
        self.btn_rx_pause.setText("▶  RESUME" if stat == "paused" else "⏸  PAUSE")

        if stat == "completed":
            self.lbl_rx_status_badge.setText("COMPLETED")
            self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_ENERGY_GREEN}; color: {SOLORA_BG}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
            msg = f"✓ Completed! {fname} ({format_size(tot)})"
            if sha:
                msg += f"  ·  SHA-256: {sha[:16]}..."
            self.lbl_payload_info.setText(msg)
            self.refresh_received_files()
        elif stat == "paused":
            self.lbl_rx_status_badge.setText("PAUSED")
            self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_AMBER}; color: {SOLORA_BG}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
            self.lbl_payload_info.setText(
                f"Holding at {format_size(recv)} / {format_size(tot)} ({pct}%). "
                "The sending device waits and picks up exactly here."
            )
        elif stat == "cancelled":
            self.lbl_rx_status_badge.setText("CANCELLED")
            self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_ALERT_RED}; color: {SOLORA_BG}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
            self.lbl_payload_info.setText(f"Cancelled at {pct}% — the partial file was deleted.")
            self.refresh_received_files()
        elif stat == "in_progress":
            self.lbl_rx_status_badge.setText("RECEIVING")
            self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_CYAN}; color: {SOLORA_BG}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
            self.lbl_payload_info.setText(f"Streaming chunks into {fname}...")
        else:
            self.lbl_rx_status_badge.setText(stat.upper())
            self.lbl_rx_status_badge.setStyleSheet(f"background: {SOLORA_SURFACE_ELEVATED}; color: {SOLORA_TEXT_MUTED}; font-size: 8pt; font-weight: bold; padding: 3px 8px; border-radius: 6px;")
            self.lbl_payload_info.setText(f"Session initialized for {fname}.")


# ─── History Screen ───────────────────────────────────────────────────────────
class HistoryScreen(QWidget):
    resume_requested = pyqtSignal(dict)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._build()

    def _build(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28,24,28,24); layout.setSpacing(16)

        top = QHBoxLayout()
        title = QLabel("TRANSFER HISTORY")
        title.setStyleSheet(f"color: {SOLORA_AMBER}; font-size: 11pt; font-weight: bold; letter-spacing: 2px;")
        top.addWidget(title); top.addStretch()
        br = QPushButton("⟳  REFRESH"); br.setFixedWidth(120); br.clicked.connect(self.refresh)
        bc = QPushButton("✖  CLEAR ALL"); bc.setFixedWidth(120); bc.clicked.connect(self.clear_all)
        top.addWidget(br); top.addWidget(bc)
        layout.addLayout(top)

        self.scroll = QScrollArea(); self.scroll.setWidgetResizable(True)
        self.scroll.setStyleSheet("QScrollArea { border: none; background: transparent; }")
        self.list_widget = QWidget()
        self.list_layout = QVBoxLayout(self.list_widget)
        self.list_layout.setContentsMargins(0,0,0,0); self.list_layout.setSpacing(8)
        self.scroll.setWidget(self.list_widget)
        layout.addWidget(self.scroll)
        self.refresh()

    def refresh(self):
        # Clear all existing widgets
        while self.list_layout.count() > 0:
            item = self.list_layout.takeAt(0)
            if item.widget(): item.widget().deleteLater()
            
        history = load_history()
        if not history:
            lbl = QLabel("No transfer history yet.\nCompleted transfers (Sent & Received) will appear here.")
            lbl.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 10pt;"); lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            lbl.setContentsMargins(0, 40, 0, 0)
            self.list_layout.addWidget(lbl)
            self.list_layout.addStretch()
            return
            
        for entry in history:
            self.list_layout.addWidget(self._make_card(entry))
        self.list_layout.addStretch()

    def _make_card(self, entry):
        card = QFrame(); card.setObjectName("card")
        row = QHBoxLayout(card); row.setContentsMargins(16,12,16,12); row.setSpacing(12)
        
        status = entry.get("status","?")
        direction = entry.get("direction", "sent")
        color = SOLORA_ENERGY_GREEN if status == "completed" else SOLORA_ALERT_RED
        
        icon_lbl = QLabel("↓" if direction == "received" else "↑")
        icon_lbl.setStyleSheet(f"color: {SOLORA_CYAN if direction == 'received' else SOLORA_NEON_LIME}; font-size: 14pt; font-weight: bold;")
        icon_lbl.setFixedWidth(20)
        row.addWidget(icon_lbl)
        
        info = QVBoxLayout(); info.setSpacing(2)
        fname = QLabel(entry.get("filename","Unknown"))
        fname.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-weight: bold; font-size: 10.5pt;")
        
        ts = entry.get("timestamp","")[:19].replace("T","  ")
        dir_tag = "RECEIVED" if direction == "received" else "SENT"
        meta = QLabel(f"[{dir_tag}]  {format_size(entry.get('size',0))}   ·   {ts}   ·   {entry.get('target_ip','?')}")
        meta.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 8pt; font-family: monospace;")
        
        sha = entry.get("sha256","")
        sha_lbl = QLabel(f"SHA-256: {sha[:24]}..." if sha else "SHA-256: Verified")
        sha_lbl.setStyleSheet(f"color: {SOLORA_TEXT_MUTED}; font-size: 7.5pt; font-family: monospace;")
        
        info.addWidget(fname); info.addWidget(meta); info.addWidget(sha_lbl)
        row.addLayout(info); row.addStretch()
        
        fpath = entry.get("file_path")
        if fpath and os.path.exists(fpath):
            btn_open = QPushButton("▶  OPEN FILE")
            btn_open.setFixedHeight(32)
            btn_open.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; border: 1px solid {SOLORA_ENERGY_GREEN}; border-radius: 6px; padding: 4px 10px; font-weight: bold; font-size: 8.5pt;")
            btn_open.clicked.connect(lambda _, p=fpath: open_file_or_dir(p))
            row.addWidget(btn_open)
        elif entry.get("transfer_id") and direction == "sent":
            btn = QPushButton("▶  RESUME"); btn.setFixedWidth(100)
            btn.setStyleSheet(f"color: {SOLORA_NEON_LIME}; border: 1px solid {SOLORA_NEON_LIME}; border-radius: 8px; padding: 4px 8px; font-weight: bold;")
            btn.clicked.connect(lambda _, e=entry: self.resume_requested.emit(e))
            row.addWidget(btn)
            
        return card

    def clear_all(self):
        try:
            with open(HISTORY_FILE,"w") as f: json.dump([],f)
        except Exception: pass
        self.refresh()


# ─── Settings Screen ──────────────────────────────────────────────────────────
class SettingsScreen(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._build()

    def _build(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28,24,28,24); layout.setSpacing(16)
        title = QLabel("SETTINGS")
        title.setStyleSheet(f"color: {SOLORA_TEXT_SECONDARY}; font-size: 11pt; font-weight: bold; letter-spacing: 2px;")
        layout.addWidget(title)

        def section(lbl):
            l = QLabel(lbl); l.setStyleSheet(f"color: {SOLORA_CYAN}; font-size: 9pt; font-weight: bold; letter-spacing: 1px; margin-top: 8px;")
            layout.addWidget(l)

        def card_row(label, widget):
            card = QFrame(); card.setObjectName("card")
            row = QHBoxLayout(card); row.setContentsMargins(16,12,16,12)
            lbl = QLabel(label); lbl.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-size: 10pt;")
            row.addWidget(lbl); row.addStretch(); row.addWidget(widget)
            layout.addWidget(card)

        section("TRANSFER")
        self.chunk_spin = QSpinBox(); self.chunk_spin.setRange(64,4096); self.chunk_spin.setValue(512); self.chunk_spin.setSuffix(" KB"); self.chunk_spin.setFixedWidth(120)
        card_row("Chunk Size", self.chunk_spin)
        self.verify_check = QCheckBox(); self.verify_check.setChecked(True)
        card_row("Verify SHA-256 on completion", self.verify_check)

        section("NETWORK")
        self.port_input = QLineEdit("8000"); self.port_input.setFixedWidth(120)
        card_row("Default Port", self.port_input)
        self.timeout_spin = QSpinBox(); self.timeout_spin.setRange(5,120); self.timeout_spin.setValue(30); self.timeout_spin.setSuffix(" s"); self.timeout_spin.setFixedWidth(120)
        card_row("Connection Timeout", self.timeout_spin)

        section("ABOUT")
        about = QFrame(); about.setObjectName("card")
        ab = QVBoxLayout(about); ab.setContentsMargins(16,14,16,14); ab.setSpacing(4)
        for line, col in [
            ("NEXUS FLOW  —  Smart Resumable File Transfer", SOLORA_TEXT_PRIMARY),
            ("Version 1.0  ·  Linux Desktop (PyQt6)", SOLORA_TEXT_SECONDARY),
            ("Protocol: Resumable HTTP/1.1 chunked streaming + SHA-256 integrity", SOLORA_TEXT_MUTED),
            ("Supports: Android ↔ Linux ↔ Linux cross-platform transfers", SOLORA_TEXT_MUTED),
        ]:
            l = QLabel(line); l.setStyleSheet(f"color: {col}; font-size: 9pt;"); l.setWordWrap(True); ab.addWidget(l)
        layout.addWidget(about)
        layout.addStretch()


# ─── Main Window ──────────────────────────────────────────────────────────────
class NexusFlowLinuxApp(QMainWindow):
    PAGE_TITLES = ["Sender Mode", "Receiver Hub (P2P)", "Transfer History", "Settings"]

    def __init__(self):
        super().__init__()
        self.setWindowTitle("NEXUS FLOW — Smart Resumable File Transfer")
        self.resize(1080, 720)
        self.setMinimumSize(860, 620)
        # Accept files dropped anywhere on the window (see dropEvent).
        self.setAcceptDrops(True)
        self._restore_window_state()

        # Window / taskbar icon. This is OS chrome rather than in-app UI, so the
        # logo bitmap stays here even though the in-app wordmarks are now
        # text-only. Routed through find_logo_path() so it degrades cleanly when
        # the asset is not bundled.
        _logo = find_logo_path()
        if _logo:
            self.setWindowIcon(QIcon(_logo))

        self.drawer_open = True
        self._init_ui()
        self._apply_styles()

    def _init_ui(self):
        root = QWidget(); root.setObjectName("centralRoot"); self.setCentralWidget(root)
        root_layout = QHBoxLayout(root)
        root_layout.setContentsMargins(0,0,0,0); root_layout.setSpacing(0)

        # Drawer
        self.drawer = NavDrawer()
        self.drawer.nav_clicked.connect(self._on_nav)
        root_layout.addWidget(self.drawer)

        # Right side
        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.setContentsMargins(0,0,0,0); right_layout.setSpacing(0)

        # Top bar
        topbar = QWidget(); topbar.setFixedHeight(50)
        topbar.setStyleSheet(f"background: {SOLORA_SURFACE}; border-bottom: 1px solid {SOLORA_BORDER};")
        tb = QHBoxLayout(topbar); tb.setContentsMargins(12,0,20,0); tb.setSpacing(12)

        self.btn_ham = QPushButton("☰"); self.btn_ham.setFixedSize(36,36)
        self.btn_ham.setStyleSheet(f"""QPushButton {{ background: transparent; color: {SOLORA_TEXT_PRIMARY};
            border: none; font-size: 18pt; border-radius: 6px; }}
            QPushButton:hover {{ background: {SOLORA_SURFACE_ELEVATED}; }}""")
        self.btn_ham.clicked.connect(self._toggle_drawer)
        tb.addWidget(self.btn_ham)

        self.lbl_page = QLabel("Sender Mode")
        self.lbl_page.setStyleSheet(f"color: {SOLORA_TEXT_PRIMARY}; font-size: 11pt; font-weight: bold;")
        tb.addWidget(self.lbl_page); tb.addStretch()

        # Device name broadcast status instead of IP
        device_name_display = load_device_name() or socket.gethostname()
        self.lbl_top_ip = QLabel(f"● {device_name_display[:20]}")
        self.lbl_top_ip.setStyleSheet(f"color: {SOLORA_ENERGY_GREEN}; font-family: monospace; font-size: 9pt;")
        tb.addWidget(self.lbl_top_ip)
        right_layout.addWidget(topbar)

        # Pages
        #
        # Both screens get the thread bridge. Without it, ReceiverScreen falls
        # back to calling its state handler straight from the http.server request
        # thread, so every chunk updated QLabels -- and every completed transfer
        # rebuilt the Received Files list -- off the GUI thread, which Qt answers
        # with "QObject::setParent: Cannot set parent, new parent is in a
        # different thread". The bridge turns that into a queued signal.
        self.bridge = WorkerBridge()
        self.stack = QStackedWidget()
        self.sender_screen   = SenderScreen(bridge=self.bridge)
        self.receiver_screen = ReceiverScreen(bridge=self.bridge)
        self.history_screen  = HistoryScreen()
        self.settings_screen = SettingsScreen()
        self.stack.addWidget(self.sender_screen)
        self.stack.addWidget(self.receiver_screen)
        self.stack.addWidget(self.history_screen)
        self.stack.addWidget(self.settings_screen)
        
        self.history_screen.resume_requested.connect(self._on_history_resume)
        self.receiver_screen.ip_updated_signal.connect(self._on_ip_updated)
        
        right_layout.addWidget(self.stack, 1)
        root_layout.addWidget(right, 1)

    def _toggle_drawer(self):
        self.drawer_open = not self.drawer_open
        self.drawer.setVisible(self.drawer_open)

    # ── Persisted window state ────────────────────────────────────────────
    # Size, position and drawer state used to reset to 1080x720 with the drawer
    # closed on every launch. Stored via QSettings so the app reopens where the
    # user left it.
    def _restore_window_state(self):
        store = _settings_store()
        geom = store.value("window/geometry")
        if geom:
            try:
                self.restoreGeometry(geom)
            except (TypeError, ValueError):
                pass  # corrupt payload from an older build; fall back to default
        self.drawer_open = str(store.value("window/drawer_open", "false")).lower() == "true"
        if hasattr(self, "drawer"):
            self.drawer.setVisible(self.drawer_open)

    def _save_window_state(self):
        store = _settings_store()
        store.setValue("window/geometry", self.saveGeometry())
        store.setValue("window/drawer_open", "true" if self.drawer_open else "false")

    # ── Drag and drop ─────────────────────────────────────────────────────
    # A file-transfer app should accept a file dropped anywhere on the window.
    # Routing it through SenderScreen.set_file() means a dropped file lands in
    # exactly the same state as one chosen with SELECT FILE FROM DISK.
    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dragMoveEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event):
        urls = event.mimeData().urls()
        path = next(
            (u.toLocalFile() for u in urls if u.isLocalFile() and os.path.isfile(u.toLocalFile())),
            None,
        )
        if not path:
            if hasattr(self, "sender_screen"):
                self.sender_screen.lbl_log.setText(
                    "⚠ That drop contained no readable local file."
                )
            event.ignore()
            return
        # Only the sender page has a file slot; switch to it first.
        self.drawer.set_active(0)
        self._on_nav(0)
        self.sender_screen.set_file(path)
        event.acceptProposedAction()

    def _on_nav(self, idx):
        self.stack.setCurrentIndex(idx)
        self.lbl_page.setText(self.PAGE_TITLES[idx])
        if idx == 1:
            self.receiver_screen.refresh_ip()
            self.receiver_screen.refresh_received_files()
        elif idx == 2:
            self.history_screen.refresh()

    def _on_ip_updated(self, ip: str):
        # Don't show IP in top bar; show device name instead
        name = load_device_name() or socket.gethostname()
        self.lbl_top_ip.setText(f"● {name[:20]}")

    def _on_history_resume(self, entry):
        self.drawer.set_active(0)
        self._on_nav(0)
        self.sender_screen.resume_from_history(entry)

    def closeEvent(self, event):
        # Persist geometry/drawer so the next launch reopens where we left off.
        try:
            self._save_window_state()
        except Exception as exc:  # never block shutdown on a settings write
            print(f"[warn] could not save window state: {type(exc).__name__}: {exc}")
        # Stop the sender worker before the receiver server: a live QThread
        # holding a socket will otherwise outlive the window.
        if hasattr(self, "sender_screen"):
            sender = self.sender_screen
            # Stop device discovery so the scan thread and its broadcast socket
            # do not outlive the window.
            try:
                sender.stop_discovery()
            except Exception as exc:
                print(f"[warn] discovery teardown: {type(exc).__name__}: {exc}")
            # In-flight connection probe: interrupt its socket so the thread
            # returns promptly, then reap it. Without this, closing the window
            # during a ping destroys a running QThread (crash on exit).
            tester = getattr(sender, "conn_tester", None)
            if tester is not None:
                try:
                    if tester.isRunning():
                        tester.client.interrupt()
                        # /health is bounded by min(timeout, 10s), so give the
                        # thread room to unwind. Forcing deleteLater() on a
                        # still-running QThread aborts the interpreter with
                        # "QThread: Destroyed while thread is still running".
                        if not tester.wait(12000):
                            print("[warn] connection probe did not stop in 12s")
                    else:
                        tester.deleteLater()
                except Exception as exc:
                    print(f"[warn] ping teardown: {type(exc).__name__}: {exc}")
                sender.conn_tester = None
            # Active transfer worker: request interruption, wait, reap.
            try:
                sender._teardown_worker(cancel=True)
            except Exception as exc:
                print(f"[warn] worker teardown: {type(exc).__name__}: {exc}")
        # Stop receiver server on app close
        if hasattr(self, 'receiver_screen') and self.receiver_screen.server:
            self.receiver_screen.server.stop()
        event.accept()

    def _apply_styles(self):
        self.setStyleSheet(f"""
            QMainWindow, #centralRoot {{
                background-color: {SOLORA_BG};
                color: {SOLORA_TEXT_PRIMARY};
                font-family: {FONT_STACK};
            }}
            QWidget {{
                color: {SOLORA_TEXT_PRIMARY};
                font-family: {FONT_STACK};
            }}
            QLabel {{
                background-color: transparent;
            }}
            QFrame#card {{
                background-color: {SOLORA_SURFACE_CARD};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 14px;
            }}
            QLineEdit {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_NEON_LIME};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 8px;
                padding: 6px 10px;
                font-family: monospace; font-size: 10pt;
            }}
            QLineEdit:focus {{ border: 1px solid {SOLORA_CYAN}; }}
            QPushButton {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_TEXT_PRIMARY};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 10px;
                padding: 8px 16px;
                font-weight: bold; font-size: 9pt;
            }}
            QPushButton:hover {{ border: 1px solid {SOLORA_CYAN}; color: {SOLORA_CYAN}; }}
            QPushButton:checked {{ background-color: {SOLORA_CYAN}; color: {SOLORA_BG}; border: 1px solid {SOLORA_CYAN}; }}
            QProgressBar {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 7px;
            }}
            QProgressBar::chunk {{
                background-color: qlineargradient(x1:0,y1:0,x2:1,y2:0,stop:0 {SOLORA_CYAN},stop:1 {SOLORA_ENERGY_GREEN});
                border-radius: 6px;
            }}
            QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; }}
            QSpinBox {{
                background-color: {SOLORA_SURFACE_ELEVATED};
                color: {SOLORA_TEXT_PRIMARY};
                border: 1px solid {SOLORA_BORDER};
                border-radius: 8px; padding: 4px 8px;
            }}
            QCheckBox::indicator {{
                width: 18px; height: 18px;
                border: 1px solid {SOLORA_BORDER};
                border-radius: 4px;
                background: {SOLORA_SURFACE_ELEVATED};
            }}
            QCheckBox::indicator:checked {{
                background: {SOLORA_ENERGY_GREEN};
                border: 1px solid {SOLORA_ENERGY_GREEN};
            }}
        """)


class _TooltipSuppressor(QObject):
    """Swallows hover tooltips app-wide.

    The app called setToolTip() on two dozen widgets, several of them created at
    runtime (peer cards, per-IP test buttons). Editing each call site would miss
    the dynamic ones and leave the behaviour half-removed, so this filters the
    event instead: returning True consumes it and no tooltip is ever shown.
    """

    # Parameter names match PyQt6's stub so the override type-checks.
    def eventFilter(self, a0, a1):
            if a1.type() == QEvent.Type.ToolTip:
                return True
            return False


def main():
    app = QApplication(sys.argv)
    app.installEventFilter(_TooltipSuppressor())
    window = NexusFlowLinuxApp()
    window.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
