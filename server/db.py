import sqlite3
import os
import time
from typing import Optional, Dict, Any, List

DB_PATH = os.environ.get("TRANSFER_DB_PATH", os.path.join(os.path.dirname(__file__), "transfers.db"))

def get_db_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=20.0)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    with get_db_connection() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS transfers (
                transfer_id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                sanitized_filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                total_size INTEGER NOT NULL,
                received_bytes INTEGER NOT NULL DEFAULT 0,
                chunk_size INTEGER NOT NULL DEFAULT 1048576,
                expected_sha256 TEXT,
                calculated_sha256 TEXT,
                status TEXT NOT NULL DEFAULT 'CREATED',
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS chunks (
                transfer_id TEXT NOT NULL,
                start_byte INTEGER NOT NULL,
                end_byte INTEGER NOT NULL,
                received_at REAL NOT NULL,
                PRIMARY KEY (transfer_id, start_byte),
                FOREIGN KEY (transfer_id) REFERENCES transfers (transfer_id) ON DELETE CASCADE
            )
        """)
        conn.commit()

def create_transfer(
    transfer_id: str,
    filename: str,
    sanitized_filename: str,
    file_path: str,
    total_size: int,
    chunk_size: int = 1048576,
    expected_sha256: Optional[str] = None,
) -> Dict[str, Any]:
    now = time.time()
    with get_db_connection() as conn:
        conn.execute("""
            INSERT INTO transfers (
                transfer_id, filename, sanitized_filename, file_path,
                total_size, received_bytes, chunk_size, expected_sha256,
                status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, 'CREATED', ?, ?)
        """, (
            transfer_id, filename, sanitized_filename, file_path,
            total_size, chunk_size, expected_sha256, now, now
        ))
        conn.commit()
    return get_transfer(transfer_id)

def get_transfer(transfer_id: str) -> Optional[Dict[str, Any]]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM transfers WHERE transfer_id = ?", (transfer_id,))
        row = cursor.fetchone()
        if not row:
            return None
        return dict(row)

def update_transfer_progress(
    transfer_id: str,
    start_byte: int,
    end_byte: int,
    new_received_bytes: int,
    status: str = "TRANSFERRING"
):
    now = time.time()
    with get_db_connection() as conn:
        conn.execute("""
            INSERT OR REPLACE INTO chunks (transfer_id, start_byte, end_byte, received_at)
            VALUES (?, ?, ?, ?)
        """, (transfer_id, start_byte, end_byte, now))
        
        conn.execute("""
            UPDATE transfers
            SET received_bytes = MAX(received_bytes, ?),
                status = ?,
                updated_at = ?
            WHERE transfer_id = ?
        """, (new_received_bytes, status, now, transfer_id))
        conn.commit()

def update_transfer_status(
    transfer_id: str,
    status: str,
    calculated_sha256: Optional[str] = None
):
    now = time.time()
    with get_db_connection() as conn:
        if calculated_sha256 is not None:
            conn.execute("""
                UPDATE transfers
                SET status = ?, calculated_sha256 = ?, updated_at = ?
                WHERE transfer_id = ?
            """, (status, calculated_sha256, now, transfer_id))
        else:
            conn.execute("""
                UPDATE transfers
                SET status = ?, updated_at = ?
                WHERE transfer_id = ?
            """, (status, now, transfer_id))
        conn.commit()

def list_transfers(limit: int = 50) -> List[Dict[str, Any]]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM transfers ORDER BY created_at DESC LIMIT ?", (limit,))
        return [dict(r) for r in cursor.fetchall()]
