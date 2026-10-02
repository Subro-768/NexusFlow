# Architecture Document

## Overview

The Resumable File Transfer System enables robust, high-speed, local-network file transmission between an Android device and a host PC/Server. The system is designed to tolerate network drops, manual pauses, app closure, and server restarts without retransmitting previously confirmed chunks.

## System Topology

```
+-----------------------------------------------------------------------+
|                           ANDROID CLIENT                              |
|                                                                       |
|  [ FilePicker (SAF) ]                                                 |
|          | (Uri)                                                      |
|          v                                                            |
|  [ TransferManager ] <======> [ TransferForegroundService ]           |
|          | (Streams 1MB chunks from ContentResolver)                  |
|          v                                                            |
|  [ TransferApiClient (OkHttp) ]                                       |
+-----------------------------------|-----------------------------------+
                                    | HTTP / JSON + Octet-Stream
                                    | (Local Wi-Fi Network)
                                    v
+-----------------------------------|-----------------------------------+
|                         HOST PC / SERVER                              |
|                                                                       |
|  [ FastAPI Web Server ]                                               |
|          |                                                            |
|          +---> [ SQLite Database (transfers.db) ]                     |
|          |         - Session IDs, confirmed byte offsets, state       |
|          |                                                            |
|          +---> [ Disk Storage Engine (uploads/) ]                     |
|                    - Random access write (os.open / seek / fsync)     |
|                    - SHA-256 integrity calculator                     |
+-----------------------------------------------------------------------+
```

## Key Architectural Principles

1. **Server as Single Source of Truth**: The client never assumes chunks were received until confirmed via HTTP response. On reconnect, the client queries `GET /transfer/{id}/status` to fetch the authoritative `received_bytes` offset.
2. **Zero-RAM-Spill Streaming I/O**: Files are read from Android's `ContentResolver` in small chunk buffers (1 MB default) and written directly at disk offsets using file seeking and `fsync()`.
3. **Atomic Persistence**: SQLite tracks all transfer sessions and chunk boundaries with ACID guarantees across power outages and server process restarts.
4. **End-to-End Cryptographic Verification**: The original source file's SHA-256 hash is compared against the server's final file SHA-256 calculation before marking the transfer as `COMPLETED`.
