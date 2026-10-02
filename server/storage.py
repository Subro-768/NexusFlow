import os
import re
import hashlib
from typing import Tuple

UPLOAD_DIR = os.environ.get("TRANSFER_UPLOAD_DIR", os.path.join(os.path.dirname(__file__), "..", "uploads"))

def get_upload_dir() -> str:
    abs_path = os.path.abspath(UPLOAD_DIR)
    os.makedirs(abs_path, exist_ok=True)
    return abs_path

def sanitize_filename(filename: str) -> str:
    # Strip any leading directories
    clean = os.path.basename(filename)
    # Remove null bytes and path separators
    clean = clean.replace("\x00", "").replace("/", "_").replace("\\", "_")
    # Replace dangerous characters while keeping unicode/alphanumerics, dots, hyphens, underscores
    clean = re.sub(r'[^a-zA-Z0-9._\- ]', '_', clean)
    if not clean or clean.strip(". ") == "":
        clean = "unnamed_file"
    return clean

def get_file_paths(transfer_id: str, original_filename: str) -> Tuple[str, str]:
    upload_dir = get_upload_dir()
    clean_name = sanitize_filename(original_filename)
    # Prefix transfer_id to avoid filename collisions
    disk_filename = f"{transfer_id}_{clean_name}"
    full_path = os.path.join(upload_dir, disk_filename)
    return clean_name, full_path

def allocate_file(file_path: str, total_size: int):
    # Ensure directory exists
    os.makedirs(os.path.dirname(file_path), exist_ok=True)
    # Create or truncate file if not existing
    if not os.path.exists(file_path):
        with open(file_path, "wb") as f:
            if total_size > 0:
                f.seek(total_size - 1)
                f.write(b"\0")
            f.flush()
            os.fsync(f.fileno())

def write_chunk(file_path: str, start_byte: int, data: bytes) -> int:
    with open(file_path, "r+b") as f:
        f.seek(start_byte)
        bytes_written = f.write(data)
        f.flush()
        os.fsync(f.fileno())
        return bytes_written

def calculate_sha256(file_path: str, buffer_size: int = 65536) -> str:
    sha = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(buffer_size)
            if not chunk:
                break
            sha.update(chunk)
    return sha.hexdigest()
