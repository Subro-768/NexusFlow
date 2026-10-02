import os
import hashlib
import time
import requests
from typing import Optional, Callable

class LinuxTransferClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip('/')
        self.session = requests.Session()
        self.is_paused = False
        self.is_cancelled = False

    def test_connection(self) -> dict:
        resp = self.session.get(f"{self.base_url}/health", timeout=4)
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

    def create_transfer(self, filepath: str, checksum: Optional[str] = None, chunk_size: int = 1048576) -> dict:
        filename = os.path.basename(filepath)
        filesize = os.path.getsize(filepath)
        payload = {
            "filename": filename,
            "filesize": filesize,
            "checksum": checksum,
            "chunk_size": chunk_size
        }
        resp = self.session.post(f"{self.base_url}/transfer", json=payload, timeout=10)
        resp.raise_for_status()
        return resp.json()

    def get_status(self, transfer_id: str) -> dict:
        resp = self.session.get(f"{self.base_url}/transfer/{transfer_id}/status", timeout=6)
        resp.raise_for_status()
        return resp.json()

    def send_file(
        self,
        filepath: str,
        transfer_id: str,
        start_offset: int = 0,
        chunk_size: int = 1048576,
        progress_callback: Optional[Callable[[int, int, float, float], None]] = None
    ) -> dict:
        self.is_paused = False
        self.is_cancelled = False
        total_size = os.path.getsize(filepath)
        current_offset = start_offset

        with open(filepath, 'rb') as f:
            if current_offset > 0:
                f.seek(current_offset)

            start_time = time.time()
            bytes_since_start = 0

            while current_offset < total_size:
                if self.is_cancelled:
                    raise Exception("Transfer cancelled by user")
                if self.is_paused:
                    raise Exception("Transfer paused by user")

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
                    timeout=20
                )
                resp.raise_for_status()
                res_json = resp.json()

                current_offset += len(chunk_data)
                bytes_since_start += len(chunk_data)

                elapsed = max(0.001, time.time() - start_time)
                speed = bytes_since_start / elapsed
                remaining_bytes = total_size - current_offset
                eta = remaining_bytes / max(1.0, speed)

                if progress_callback:
                    progress_callback(current_offset, total_size, speed, eta)

        return self.get_status(transfer_id)
