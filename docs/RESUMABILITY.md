# Resumability Mechanism Specification

This document details the exact end-to-end mechanism that guarantees robust resumable file transfers across server crashes, network dropouts, and manual pauses.

---

## 1. Sequence Diagram

```text
Android Client / Sender                                Server / Receiver Hub
       |                                                         |
       |--- 1. POST /transfer (filename, filesize, sha256) ----->|
       |<-- 2. HTTP 201 (transfer_id: "085d9087b434", offset: 0)-| (Preallocates file & DB row)
       |                                                         |
       |--- 3. POST /transfer/{id}/chunk (0..1MB) -------------->| (RandomAccess seek & write)
       |<-- 4. HTTP 200 (received_bytes: 1MB) -------------------| (fsync & DB offset update)
       |--- 5. POST /transfer/{id}/chunk (1MB..2MB) ------------>|
       |<-- 6. HTTP 200 (received_bytes: 2MB) -------------------|
       |                            X                            |
       |                 NETWORK INTERRUPTION / CRASH            |
       |                            X                            |
       |--- 7. GET /transfer/{id}/status ----------------------->| (Reads committed DB state)
       |<-- 8. HTTP 200 (received_bytes: 20971520, status: PEND)-|
       |                                                         |
       | (Client uses FileChannel.position(20971520) - 0.001ms)  |
       |--- 9. POST /transfer/{id}/chunk (20MB..21MB) ---------->|
       |<-- 10. HTTP 200 (received_bytes: 21MB) -----------------|
       |                           ...                           |
       |--- 11. POST /transfer/{id}/chunk (last chunk) ---------->|
       |<-- 12. HTTP 200 (status: COMPLETED, sha256_verified: T)-| (Server computes SHA-256)
```

---

## 2. Technical Protocol Breakdown

### 1. Initial Transfer Session Creation
- The client calculates or retrieves the cached SHA-256 hash of the selected payload.
- Client issues `POST /transfer` with payload `{ "filename": "video.mp4", "filesize": 104857600, "checksum": "<sha256>", "chunk_size": 1048576 }`.

### 2. Transfer ID Generation & Persistence
- Server generates a collision-resistant UUID/hex ID (e.g., `085d9087b434`).
- An atomic SQLite transaction inserts the transfer record with `received_bytes = 0`, `status = 'PENDING'`, and sanitizes the target filename against path traversal attacks.

### 3. Chunk Identification & Byte Boundaries
- Chunks are identified using standard inclusive HTTP byte range headers:
  - `X-Start-Byte`: `0`
  - `X-End-Byte`: `1048575`
  - `X-Total-Size`: `104857600`
  - `Content-Type`: `application/octet-stream`

### 4. Server Offset Determination & File Persistence
- The server writes binary chunks directly to the persistent storage file via `RandomAccessFile.seek(start_byte)` (Python `os.pwrite` / Kotlin `RandomAccessFile`).
- The server forces data to physical disk using `fsync()` and atomically updates the confirmed `received_bytes` and chunk boundaries in SQLite.

### 5. Interruption Detection & Recovery Discovery
- If a connection fails, the client queries `GET /transfer/{transfer_id}/status`.
- The server queries SQLite and returns the confirmed `received_bytes` on disk.

### 6. Client Resumption Mechanism
- On the client side, standard sequential `InputStream.skip()` is completely avoided.
- The client opens an Android kernel `ParcelFileDescriptor` and sets `FileChannel.position(confirmedOffset)`. This achieves instant **0.001 ms random-access seeking** regardless of whether the file is 50 MB or 10 GB.
- Chunk streaming immediately resumes from `confirmedOffset`.

### 7. Duplicate / Repeated Chunk Idempotency
- If network lag causes an ACK to be lost and the client re-sends a previously received chunk, the server detects that the chunk offset `end_byte <= confirmed_offset`.
- The server safely overwrites the exact byte range idempotently without corrupting the file or duplicating byte counts.

### 8. Cryptographic Completion Verification
- When `received_bytes == total_size`, the server reads the file in 128 KB streaming buffers and computes the final SHA-256 digest.
- The server compares the digest against `expected_sha256`. If verified, it updates the database status to `COMPLETED` and returns `sha256_verified: true`.
