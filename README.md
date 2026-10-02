# NEXUS FLOW: Resumable File Transfer System

A high-performance, fault-tolerant local network file transfer system between Android devices and a host PC/Server (and direct Phone-to-Phone P2P). Built with FastAPI, SQLite, Kotlin, Jetpack Compose, OkHttp, and an embedded Android Receiver Server.

---

## Technical Documentation

- **[Resumability Protocol Specification](docs/RESUMABILITY.md)**
- **[Empirical Demonstration & Benchmark Results](docs/DEMO_RESULTS.md)**
- **[Comprehensive Project Overview](docs/PROJECT_OVERVIEW.md)**

---

## The Problem

Standard HTTP and FTP uploads treat files as a single atomic stream. If Wi-Fi drops, the phone goes offline, the app closes, or the server crashes midway through a 5 GB transfer at 95%, the entire transfer fails and must start over from 0%.

## The Solution

This system breaks files into configurable streaming chunks (1 MB default) with:
1. **Authoritative Server Offsets**: The server is the single source of truth for confirmed bytes written to disk.
2. **Crash-Proof SQLite Persistence**: State survives power cuts, server crashes, and app closures.
3. **Random-Access Disk Streaming**: Chunks are written with direct file seek & `fsync()` without loading full files into RAM (< 10 MB RAM footprint).
4. **Automatic Interruption Recovery**: On reconnect, the client asks `GET /transfer/{id}/status` and resumes from the exact confirmed byte.
5. **End-to-End Cryptographic Verification**: Computes SHA-256 on both ends to verify file integrity.

---

## How Resumability Works

```text
2 GB file (2,147,483,648 bytes)

Initial Phase:
0 MB ──────────────────────> 800 MB (offset 838,860,800)

[ Wi-Fi drops / Server is stopped / App is closed ]

Server retains:
800 MB partial file on disk + SQLite confirmed offset.

Reconnection Phase:
Client queries: GET /transfer/{id}/status
Server responds: "received_bytes": 838860800

Client resumes:
800 MB (offset 838,860,800) ──────────────> 2 GB (offset 2,147,483,648)
(0 MB – 800 MB is NOT retransmitted!)

Completion Phase:
Server computes SHA-256: 3a7f...9b1c
Client compares SHA-256: 3a7f...9b1c
Result: ✓ Transfer Complete & SHA-256 Verified
```

---

## Architecture

```text
+-----------------------------------------------------------------------+
|                           ANDROID CLIENT                              |
|                                                                       |
|  [ FilePicker (SAF) ]                                                 |
|          | (Uri)                                                      |
|          v                                                            |
|  [ TransferManager (Singleton) ] <===> [ Foreground Service ]         |
|          | (Direct lseek / FileChannel 1MB chunk slicing)             |
|          v                                                            |
|  [ TransferApiClient (OkHttp) ]                                       |
+-----------------------------------|-----------------------------------+
                                    | HTTP / Wi-Fi / USB
                                    | (X-Start-Byte, X-End-Byte headers)
                                    v
+-----------------------------------|-----------------------------------+
|                         HOST PC / SERVER                              |
|                                                                       |
|  [ FastAPI Web Server (Port 8000) ]                                   |
|          |                                                            |
|          +---> [ SQLite Database (transfers.db) ]                     |
|          |         - Session IDs, confirmed byte offsets, state       |
|          |                                                            |
|          +---> [ Disk Storage Engine (uploads/) ]                     |
|                    - Random access write (seek / fsync)               |
|                    - SHA-256 streaming validator                      |
+-----------------------------------------------------------------------+
```

---

## Tech Stack

- **Server**: Python 3.14, FastAPI, Uvicorn, SQLite3, hashlib
- **Android App**: Kotlin 2.0, Jetpack Compose Material 3, Coroutines, OkHttp, Android Foreground Service
- **Testing**: pytest, pytest-asyncio, Starlette TestClient

---

## Quick Setup & Run

### 1. Start Server on Computer
```bash
# Activate virtual environment
source .venv/bin/activate

# Run the server
python3 server/run_server.py
```
The server will listen on `http://0.0.0.0:8000` and display your local network IP (e.g. `http://192.168.x.x:8000` or `http://10.x.x.x:8000`).

### 2. Run Automated Server Tests
```bash
PYTHONPATH=. .venv/bin/pytest -v tests/
```

### 3. Build & Install Android App
```bash
cd android
./gradlew installDebug
```

---

## Step-by-Step Interruption & Resume Demonstration

1. Open **Resumable Transfer** on Android.
2. Enter your Computer's Server IP (or `127.0.0.1` via USB).
3. Tap **"Test Connection"** (status turns green **Connected**).
4. Tap **"Select File from Device"** and pick a large file (e.g. 50 MB – 1.5 GB video/zip).
5. Tap **"Start Transfer"**.
6. When progress reaches ~30–50%, stop the server in terminal (`Ctrl+C`) or toggle phone Wi-Fi off.
7. Observe: Android UI immediately reflects **"Interrupted"** and displays the exact confirmed byte count.
8. Restart the server (`python3 server/run_server.py`) or turn Wi-Fi back on.
9. Tap **"▶ Resume"**: The client queries the server's confirmed offset and resumes streaming from that exact byte offset without restarting.
10. Upon completion, both client and server show **"Transfer Complete & SHA-256 Verified"** with matching hashes.

---

## Automated Test Verification

| Test Name | Description | Result |
| :--- | :--- | :--- |
| `test_health_check` | Server reachability & local IP reporting | ✅ PASS |
| `test_small_file_transfer` | End-to-end chunking and checksum validation | ✅ PASS |
| `test_interrupted_transfer_and_resume` | Interruption recovery from exact stored offset | ✅ PASS |
| `test_multiple_interruptions_and_restarts` | Multiple sequential interruptions (30% -> 60% -> 100%) | ✅ PASS |
| `test_server_restart_persistence` | SQLite persistence across full server process reboot | ✅ PASS |
| `test_duplicate_chunk_handling` | Re-sending duplicate chunks preserves file integrity | ✅ PASS |
| `test_invalid_ranges_rejected` | Negative bounds and out-of-order chunks rejected (HTTP 400) | ✅ PASS |
| `test_path_traversal_sanitization` | Rejection of `../../` path traversal attacks | ✅ PASS |
| `test_sha256_mismatch_detected` | Corrupted checksums immediately flagged as `FAILED` | ✅ PASS |

---

## Project Structure

```text
resumable-file-transfer/
├── android/                   # Android App (Kotlin + Compose)
│   ├── app/src/main/java/     # UI, Navigation Drawer, Foreground Service, Transfer Manager
│   └── build.gradle.kts
├── server/                    # Backend Server (FastAPI + SQLite)
│   ├── db.py                  # SQLite schema & persistence
│   ├── storage.py             # Disk I/O & SHA-256 verification
│   ├── main.py                # REST API endpoints
│   └── run_server.py          # Server launcher
├── tests/                     # Automated Test Suite
│   └── test_server.py         # 9 unit & integration tests
├── docs/                      # Documentation
│   ├── architecture.md
│   ├── protocol.md
│   └── demo.md
├── uploads/                   # Received files storage
├── requirements.txt
└── README.md
```
