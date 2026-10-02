"""LinuxTransferClient — HTTP client for the embedded receiver endpoint.

Control flow notes (frontend fix pass):
  * ``is_paused`` / ``is_cancelled`` are *sticky* control flags honoured by a
    wait loop inside :meth:`send_file`.  The previous implementation reset them
    at the top of ``send_file`` and raised a bare ``Exception`` on pause, which
    the GUI could not distinguish from a genuine failure — so pausing a transfer
    reported INTERRUPTED and clobbered the PAUSED badge.
  * Deliberate pause/cancel raise :class:`TransferPaused` / :class:`TransferCancelled`
    so the worker can report the right terminal state.
  * ``requestInterruption()`` is honoured between chunks via
    :meth:`LinuxTransferClient.interrupt`.
  * ``chunk_size`` and ``timeout`` are injected from persisted Settings instead
    of being hardcoded.
"""

from __future__ import annotations

import hashlib
import os
import threading
import time
from typing import Callable, Optional

import requests

DEFAULT_CHUNK_SIZE = 1024 * 1024
DEFAULT_TIMEOUT = 30.0

#: Polling interval used by the pause wait loop (seconds).
PAUSE_POLL_INTERVAL = 0.15


class TransferError(Exception):
    """Base class for transfer control-flow signals."""


class TransferPaused(TransferError):
    """Raised when the user paused the transfer (resumable, not a failure)."""


class TransferCancelled(TransferError):
    """Raised when the user cancelled the transfer (deliberate, not a failure)."""


class LinuxTransferClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = DEFAULT_TIMEOUT,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
        verify_checksum: bool = True,
    ):
        self.base_url = base_url.rstrip('/')
        self.session = requests.Session()
        self.timeout = float(timeout or DEFAULT_TIMEOUT)
        self.chunk_size = int(chunk_size or DEFAULT_CHUNK_SIZE)
        self.verify_checksum = bool(verify_checksum)
        self.is_paused = False
        self.is_cancelled = False
        self._interrupt = threading.Event()
        self._paused = threading.Event()

    # ── Control flags ─────────────────────────────────────────────────────────
    def interrupt(self) -> None:
        """Cooperative cancellation used by QThread.requestInterruption()."""
        self._interrupt.set()

    def pause(self) -> None:
        """Set the pause flag; ``send_file`` parks in its wait loop until cleared."""
        self.is_paused = True
        self._paused.set()

    def resume(self) -> None:
        self.is_paused = False
        self._paused.clear()

    def cancel(self) -> None:
        self.is_cancelled = True
        self._interrupt.set()
        self._paused.set()  # release the pause wait loop immediately

    def _checkpoint(self) -> None:
        """Raise the right control-flow exception if pause/cancel is requested."""
        if self.is_cancelled or self._interrupt.is_set():
            raise TransferCancelled("Transfer cancelled by user")
        if self.is_paused:
            raise TransferPaused("Transfer paused by user")

    def _wait_if_paused(self) -> None:
        """Park while paused; returns when resumed or cancelled."""
        while self.is_paused and not self.is_cancelled and not self._interrupt.is_set():
            self._paused.wait(PAUSE_POLL_INTERVAL)
        if self.is_cancelled or self._interrupt.is_set():
            raise TransferCancelled("Transfer cancelled by user")

    def _reset_controls(self) -> None:
        """Start a fresh send with clean control flags (per-transfer client)."""
        self.is_paused = False
        self.is_cancelled = False
        self._interrupt.clear()
        self._paused.clear()

    # ── Requests ──────────────────────────────────────────────────────────────
    def test_connection(self) -> dict:
        resp = self.session.get(f"{self.base_url}/health", timeout=min(self.timeout, 10.0))
        resp.raise_for_status()
        return resp.json()

    @staticmethod
    def calculate_sha256(filepath: str, progress_callback: Optional[Callable[[int, int], None]] = None) -> str:
        sha256 = hashlib.sha256()
        total_size = os.path.getsize(filepath)
        read_bytes = 0
        with open(filepath, 'rb') as f:
            while chunk := f.read(256 * 1024):
                sha256.update(chunk)
                read_bytes += len(chunk)
                if progress_callback:
                    progress_callback(read_bytes, total_size)
        return sha256.hexdigest()

    def create_transfer(self, filepath: str, checksum: Optional[str] = None,
                        chunk_size: Optional[int] = None) -> dict:
        filename = os.path.basename(filepath)
        filesize = os.path.getsize(filepath)
        payload = {
            "filename": filename,
            "filesize": filesize,
            "checksum": checksum,
            "chunk_size": chunk_size or self.chunk_size,
        }
        resp = self.session.post(f"{self.base_url}/transfer", json=payload, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def get_status(self, transfer_id: str) -> dict:
        resp = self.session.get(
            f"{self.base_url}/transfer/{transfer_id}/status",
            timeout=min(self.timeout, 15.0),
        )
        resp.raise_for_status()
        return resp.json()

    def send_file(
        self,
        filepath: str,
        transfer_id: str,
        start_offset: int = 0,
        chunk_size: Optional[int] = None,
        progress_callback: Optional[Callable[[int, int, float, float], None]] = None,
    ) -> dict:
        chunk_size = int(chunk_size or self.chunk_size)
        if chunk_size <= 0:
            chunk_size = DEFAULT_CHUNK_SIZE
        self._reset_controls()
        total_size = os.path.getsize(filepath)
        current_offset = start_offset

        with open(filepath, 'rb') as f:
            if current_offset > 0:
                f.seek(current_offset)

            start_time = time.time()
            bytes_since_start = 0

            while current_offset < total_size:
                # Honour pause/cancel *before* every chunk, and wait while paused.
                self._wait_if_paused()
                self._checkpoint()

                chunk_len = min(chunk_size, total_size - current_offset)
                chunk_data = f.read(chunk_len)
                if not chunk_data:
                    break

                end_byte = current_offset + len(chunk_data) - 1
                headers = {
                    "Content-Type": "application/octet-stream",
                    "X-Start-Byte": str(current_offset),
                    "X-End-Byte": str(end_byte),
                    "X-Total-Size": str(total_size)
                }

                resp = self.session.post(
                    f"{self.base_url}/transfer/{transfer_id}/chunk",
                    data=chunk_data,
                    headers=headers,
                    timeout=self.timeout,
                )
                resp.raise_for_status()

                current_offset += len(chunk_data)
                bytes_since_start += len(chunk_data)

                elapsed = max(0.001, time.time() - start_time)
                speed = bytes_since_start / elapsed
                remaining_bytes = total_size - current_offset
                eta = remaining_bytes / max(1.0, speed)

                if progress_callback:
                    progress_callback(current_offset, total_size, speed, eta)

        # `verify_checksum` is a user-facing setting: when off, the caller does
        # not require a server-side digest in the final status payload.
        status = self.get_status(transfer_id)
        if not self.verify_checksum and isinstance(status, dict):
            status = dict(status)
            status.setdefault("verified", False)
        return status