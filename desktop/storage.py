"""
storage.py — locked, cached, atomic persistence for NexusFlow desktop state.

Replaces the module-level ``load_*`` / ``save_*`` helpers that used to live in
app.py *and* (duplicated) in embedded_server.py.  Both modules wrote the same
``~/.nexusflow_history.json`` with no locking, so a receive completing while a
send was mid-save silently dropped an entry.

Design:
  * one process-wide ``threading.RLock`` per file serialises in-process writers
  * ``fcntl.flock`` on a sibling ``.lock`` file serialises writers coming from
    other processes (server, CLI, a second app instance)
  * every write is atomic (temp file + ``os.replace``) so a crash mid-write
    cannot truncate the JSON
  * reads are cached in memory and invalidated by an ``mtime_ns``/size stat, so
    the ``refresh_*()`` UI slots no longer ``json.load()`` on every nav/paint
  * failures are reported through :func:`last_error` instead of ``except: pass``
"""

from __future__ import annotations

import errno
import fcntl
import json
import os
import socket
import tempfile
import threading
import time
from datetime import datetime
from typing import Any, Callable, List, Optional

__all__ = [
    "HISTORY_FILE", "RECENT_IPS_FILE", "DEVICE_NAME_FILE",
    "load_history", "write_history", "append_history_entry",
    "record_received_entry", "clear_history",
    "load_recent_ips", "save_recent_ip",
    "load_device_name", "save_device_name",
    "last_error", "subscribe_errors", "invalidate_caches",
]


def _home(*parts: str) -> str:
    return os.path.join(os.path.expanduser("~"), *parts)


HISTORY_FILE = _home(".nexusflow_history.json")
RECENT_IPS_FILE = _home(".nexusflow_recent_ips.json")
DEVICE_NAME_FILE = _home(".nexusflow_device_name.txt")

MAX_HISTORY = 100
MAX_RECENT_IPS = 20


# ── Error reporting (replaces the old ``except Exception: pass`` silencers) ──
_ERROR_LISTENERS: List[Callable[[str, BaseException], None]] = []
_LAST_ERROR: Optional[str] = None


def subscribe_errors(callback: Callable[[str, BaseException], None]) -> None:
    """Register a callback invoked as ``callback(context, exception)``."""
    _ERROR_LISTENERS.append(callback)


def last_error() -> Optional[str]:
    """Most recent persistence failure as ``'Context: ExcClass: message'``."""
    return _LAST_ERROR


def _report_error(context: str, exc: BaseException) -> None:
    global _LAST_ERROR
    _LAST_ERROR = f"{context}: {type(exc).__name__}: {exc}"
    for cb in list(_ERROR_LISTENERS):
        try:
            cb(_LAST_ERROR, exc)
        except Exception:
            pass


# ── Cross-process + in-process locking, atomic writes ────────────────────────
_LOCKS: dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()
# flock() is held per *open file description*, so re-entering the lock from the
# same thread would deadlock on a second fd. Track depth per (path, thread).
_FLOCK_DEPTH: dict[tuple, int] = {}
_FLOCK_GUARD = threading.Lock()


def _thread_lock(path: str) -> threading.RLock:
    key = os.path.abspath(path)
    with _LOCKS_GUARD:
        lock = _LOCKS.get(key)
        if lock is None:
            lock = _LOCKS[key] = threading.RLock()
        return lock


class _FileLock:
    """Reentrant in-process lock + advisory ``flock`` for one path."""

    def __init__(self, path: str):
        self._path = os.path.abspath(path)
        self._tlock = _thread_lock(path)
        self._key = (self._path, threading.get_ident())
        self._fh = None

    def __enter__(self) -> "_FileLock":
        self._tlock.acquire()
        with _FLOCK_GUARD:
            depth = _FLOCK_DEPTH.get(self._key, 0)
            _FLOCK_DEPTH[self._key] = depth + 1
        if depth > 0:  # already holding the flock in this thread
            return self
        try:
            self._fh = open(self._path + ".lock", "a+")
            fcntl.flock(self._fh.fileno(), fcntl.LOCK_EX)
        except OSError as exc:
            # Cannot create/take the advisory lock (read-only home, exotic FS,
            # /proc, ...). Cross-process exclusion is impossible in that case,
            # so degrade to the in-process lock rather than breaking the app.
            _report_error(f"lock {os.path.basename(self._path)}", exc)
            self._fh = None
        return self

    def __exit__(self, *exc_info) -> None:
        with _FLOCK_GUARD:
            depth = _FLOCK_DEPTH.get(self._key, 0) - 1
            if depth > 0:
                _FLOCK_DEPTH[self._key] = depth
                self._tlock.release()
                return
            _FLOCK_DEPTH.pop(self._key, None)
        if self._fh is not None:
            try:
                fcntl.flock(self._fh.fileno(), fcntl.LOCK_UN)
            finally:
                self._fh.close()
                self._fh = None
        self._tlock.release()


def _atomic_write_text(path: str, text: str) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp = tempfile.mkstemp(prefix=".nexusflow-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
        tmp = None
    finally:
        if tmp is not None and os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except OSError:
                pass


def _read_text_unlocked(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()
    except FileNotFoundError:
        return None
    except OSError as exc:
        _report_error(f"read {os.path.basename(path)}", exc)
        return None


# ── mtime-invalidated JSON cache ─────────────────────────────────────────────
class _JsonCache:
    """Cache a parsed JSON document; invalidate on mtime_ns/size change."""

    def __init__(self, path: str, default: Any):
        self._path = path
        self._default = default
        self._value: Any = None
        self._stamp: Any = "unset"
        self._loaded = False

    @property
    def path(self) -> str:
        return self._path

    def _stamp_now(self):
        try:
            st = os.stat(self._path)
        except OSError:
            return None
        return (st.st_mtime_ns, st.st_size)

    def read(self) -> Any:
        with _FileLock(self._path):
            stamp = self._stamp_now()
            if not self._loaded or stamp != self._stamp:
                raw = _read_text_unlocked(self._path)
                value = self._default() if isinstance(self._default, type) else self._default
                if raw:
                    try:
                        parsed = json.loads(raw)
                    except (ValueError, TypeError) as exc:
                        _report_error(f"parse {os.path.basename(self._path)}", exc)
                        parsed = value
                    else:
                        if isinstance(self._default, list) and not isinstance(parsed, list):
                            _report_error(
                                f"parse {os.path.basename(self._path)}",
                                TypeError(f"expected a JSON list, got {type(parsed).__name__}"),
                            )
                        else:
                            value = parsed
                self._value = value
                self._stamp = self._stamp_now()
                self._loaded = True
            return self._value

    def peek(self) -> Any:
        """Cached value without touching the filesystem."""
        with _FileLock(self._path):
            if not self._loaded:
                return self.read()
            return self._value

    def write(self, value: Any) -> Any:
        with _FileLock(self._path):
            try:
                _atomic_write_text(self._path, json.dumps(value, indent=2))
            except OSError as exc:
                _report_error(f"write {os.path.basename(self._path)}", exc)
            self._value = value
            self._stamp = self._stamp_now()
            self._loaded = True
            return value

    def update(self, mutate: Callable[[Any], Any]) -> Any:
        """Atomic read-modify-write under the file lock; returns new value."""
        with _FileLock(self._path):
            raw = _read_text_unlocked(self._path)
            current = self._default() if isinstance(self._default, type) else self._default
            if raw:
                try:
                    current = json.loads(raw)
                except (ValueError, TypeError) as exc:
                    _report_error(f"parse {os.path.basename(self._path)}", exc)
                    current = self._default() if isinstance(self._default, type) else self._default
            value = mutate(current)
            try:
                _atomic_write_text(self._path, json.dumps(value, indent=2))
            except OSError as exc:
                _report_error(f"write {os.path.basename(self._path)}", exc)
            self._value = value
            self._stamp = self._stamp_now()
            self._loaded = True
            return value

    def invalidate(self) -> None:
        with _FileLock(self._path):
            self._loaded = False
            self._stamp = "unset"


_history_cache = _JsonCache(HISTORY_FILE, list)
_recent_ips_cache = _JsonCache(RECENT_IPS_FILE, list)
_device_name_cache: Optional[str] = None


def invalidate_caches() -> None:
    """Force the next read to hit disk (used after external writes / in tests)."""
    _history_cache.invalidate()
    _recent_ips_cache.invalidate()
    global _device_name_cache
    _device_name_cache = None


# ── Transfer history (two writers: app.py sender + embedded_server receiver) ──
def load_history() -> List[dict]:
    """Cached read of the shared transfer history (newest first)."""
    data = _history_cache.read()
    return list(data) if isinstance(data, list) else []


def write_history(entries: List[dict]) -> List[dict]:
    """Replace the whole history file (used by 'CLEAR ALL')."""
    return _history_cache.write(list(entries)[:MAX_HISTORY])


def clear_history() -> List[dict]:
    return write_history([])


def append_history_entry(entry: dict, dedup: bool = False) -> List[dict]:
    """Insert ``entry`` at the head of the history atomically.

    ``dedup=True`` (sender re-save for the same session) replaces an existing
    entry with the same transfer_id + filename instead of stacking duplicates.
    Previously this was read-modify-write with no lock, so a receive landing in
    between was lost.
    """

    def _mutate(history: list) -> list:
        history = [x for x in history if isinstance(x, dict)]
        if dedup:
            history = [
                x for x in history
                if not (x.get("transfer_id") == entry.get("transfer_id")
                        and x.get("filename") == entry.get("filename"))
            ]
        history.insert(0, dict(entry))
        return history[:MAX_HISTORY]

    try:
        return _history_cache.update(_mutate)
    except OSError as exc:
        _report_error("save history", exc)
        return []


# Backwards-compatible alias (the old app.py name).
save_history_entry = append_history_entry


def record_received_entry(rec: dict, client_address) -> List[dict]:
    """Receiver-side history writer, used by embedded_server's request thread."""
    try:
        host = "device"
        if client_address:
            try:
                host = str(client_address[0])
            except (IndexError, TypeError):
                host = "device"
        entry = {
            "filename": rec.get("filename", "unknown"),
            "size": rec.get("total_size", 0),
            "sha256": rec.get("calculated_sha256", ""),
            "status": "completed",
            "timestamp": datetime.now().isoformat(),
            "transfer_id": rec.get("transfer_id", ""),
            "target_ip": f"Received from {host}",
            "direction": "received",
            "file_path": rec.get("file_path", ""),
        }
        return append_history_entry(entry)
    except OSError as exc:
        _report_error("record received history", exc)
        return []


# ── Recent target hosts ──────────────────────────────────────────────────────
def _extract_ip(raw: str) -> str:
    import re
    match = re.search(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", raw)
    return match.group(0) if match else raw


def load_recent_ips() -> List[str]:
    """Cached recent target hosts; falls back to mining history when unset."""
    data = _recent_ips_cache.read()
    if isinstance(data, list) and data:
        return [str(ip).strip() for ip in data if str(ip).strip()]
    ips: List[str] = []
    for entry in load_history():
        raw_ip = str(entry.get("target_ip", "")).strip()
        clean = _extract_ip(raw_ip)
        if clean and clean not in ips and clean != "127.0.0.1":
            ips.append(clean)
    return ips


def save_recent_ip(ip: str) -> List[str]:
    ip = (ip or "").strip()
    if not ip:
        return load_recent_ips()

    def _mutate(current: list) -> list:
        current = [str(x).strip() for x in current if str(x).strip()]
        if ip in current:
            current.remove(ip)
        current.insert(0, ip)
        return current[:MAX_RECENT_IPS]

    try:
        return _recent_ips_cache.update(_mutate)
    except OSError as exc:
        _report_error("save recent host", exc)
        return []


# ── Device name (read on every nav / ip update; cached in memory) ────────────
def load_device_name() -> str:
    """Cached device name; falls back to the hostname. No disk I/O in steady state."""
    global _device_name_cache
    if _device_name_cache is not None:
        return _device_name_cache
    raw = _read_text_unlocked(DEVICE_NAME_FILE)
    name = (raw or "").strip() if raw else ""
    _device_name_cache = name or socket.gethostname()
    return _device_name_cache


def save_device_name(name: str) -> str:
    global _device_name_cache
    name = (name or "").strip() or socket.gethostname()
    _device_name_cache = name
    try:
        with _FileLock(DEVICE_NAME_FILE):
            _atomic_write_text(DEVICE_NAME_FILE, name)
    except OSError as exc:
        _report_error("save device name", exc)
    return name


def touch(path: str) -> float:
    """Utility: best-effort mtime (used by tests / future watchers)."""
    try:
        return os.stat(path).st_mtime
    except OSError:
        return time.time()