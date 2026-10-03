# Resumable File Transfer Protocol Specification

## Protocol Overview

The protocol is built on HTTP/1.1 and JSON, with binary octet-stream chunk payloads using custom range headers (`X-Start-Byte`, `X-End-Byte`, `X-Total-Size`).

---

### 1. Health & Discovery

`GET /health`

**Response (200 OK):**
```json
{
  "status": "online",
  "service": "resumable-file-transfer",
  "local_ips": ["192.168.1.105", "10.3.108.148"],
  "upload_dir": "/path/to/uploads"
}
```

---

### 2. Session Initialization

`POST /transfer`

**Request Body:**
```json
{
  "filename": "video.mp4",
  "filesize": 104857600,
  "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "chunk_size": 1048576
}
```

**Response (201 Created):**
```json
{
  "transfer_id": "a9f8b2c1d3e4",
  "filename": "video.mp4",
  "total_size": 104857600,
  "received_bytes": 0,
  "chunk_size": 1048576,
  "status": "CREATED",
  "expected_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "calculated_sha256": null
}
```

---

### 3. Query Transfer Status

`GET /transfer/{transfer_id}/status`

**Response (200 OK):**
```json
{
  "transfer_id": "a9f8b2c1d3e4",
  "filename": "video.mp4",
  "total_size": 104857600,
  "received_bytes": 52428800,
  "chunk_size": 1048576,
  "status": "TRANSFERRING",
  "expected_sha256": "...",
  "calculated_sha256": null
}
```

---

### 4. Upload Chunk

`POST /transfer/{transfer_id}/chunk`

**Headers:**
- `X-Start-Byte: 0`
- `X-End-Byte: 1048575`
- `X-Total-Size: 104857600`
- `Content-Type: application/octet-stream`

**Body:** Binary chunk data (1,048,576 bytes)

**Response (200 OK):**
```json
{
  "transfer_id": "a9f8b2c1d3e4",
  "received_bytes": 1048576,
  "total_size": 104857600,
  "status": "TRANSFERRING",
  "calculated_sha256": null,
  "sha256_verified": false
}
```

**Final Chunk Response (200 OK):**
```json
{
  "transfer_id": "a9f8b2c1d3e4",
  "received_bytes": 104857600,
  "total_size": 104857600,
  "status": "COMPLETED",
  "calculated_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "sha256_verified": true
}
```

**Refused Chunk — receiver paused (409 Conflict):**

A receiver can hold a transfer on purpose, from its own UI or from its
notification. Nothing is written; the body of the chunk is drained first so the
socket is left at a clean request boundary.

```json
{ "detail": "transfer_paused", "status": "PAUSED" }
```

**Refused Chunk — receiver cancelled (410 Gone):**

```json
{ "detail": "transfer_cancelled", "status": "CANCELLED" }
```

Senders must treat these two markers as decisions, not as transport failures:
do not retry them, keep the session id so a later resume continues from
`received_bytes`, and surface them as PAUSED / CANCELLED rather than as an
error. Any other 4xx on a chunk is still a genuine failure and should be
reported as one.

---

### 5. Cancel Session

`POST /transfer/{transfer_id}/cancel`

The receiver marks the session dead and deletes the partial file (the target is
preallocated to the full size, so skipping this leaves a full-size file of
zeros on disk).

**Response (200 OK):**
```json
{
  "transfer_id": "a9f8b2c1d3e4",
  "status": "CANCELLED",
  "received_bytes": 52428800
}
```

**Response (404 Not Found):** the session is unknown or already gone.
