# Resumable File Transfer System — AI Agent Task

## Your Role

You are the lead software engineer responsible for implementing this project end-to-end.

The developer is a beginner and will rely heavily on you for implementation. **Do the actual coding rather than merely explaining how to code it.**

You may use reasonable libraries, frameworks, and tools when they significantly reduce development time, but the final project must be understandable, runnable, and demonstrable.

Do not stop at a prototype that only works in ideal conditions. The key feature of this project is **true resumable file transfer**.

---

# 1. Project Goal

Build a reliable file-transfer system that allows a large file to be transferred from an Android phone to a computer over a local network.

If the transfer is interrupted — for example:

* Wi-Fi disconnects
* phone goes temporarily offline
* computer/server is stopped
* app is closed
* network connection drops
* transfer is manually cancelled

the transfer must be able to **resume from approximately where it stopped instead of restarting from 0%**.

The system should demonstrate this clearly.

---

# 2. Target Architecture

Use this architecture unless there is a strong technical reason to improve it:

```text
Android Phone
     |
     | HTTP / local Wi-Fi
     |
     v
Computer / Laptop
     |
     v
File Storage
```

The Android device acts as the **file sender/client**.

The computer acts as the **receiver/server**.

The transfer should happen over the local network.

The system must NOT depend on cloud storage.

---

# 3. Core Requirement: Resumability

This is the most important part of the project.

Do NOT implement resumability merely by restarting an HTTP request.

The receiver must maintain the amount of data already successfully received.

Example:

```text
File size: 2 GB

Transfer:
0 MB ────────────────> 750 MB

Connection lost.

Resume:

750 MB ───────────────> 2 GB
```

The second connection must start from approximately byte:

```text
750 * 1024 * 1024
```

rather than byte 0.

---

# 4. Recommended Transfer Protocol

Use HTTP for communication.

Implement a resumable upload/download mechanism using byte offsets or HTTP Range semantics.

A preferred approach is:

### Initial transfer

Client asks the server to create a transfer:

```http
POST /transfer
```

Send metadata such as:

```json
{
  "filename": "example.zip",
  "filesize": 2147483648,
  "checksum": "...",
  "chunk_size": 1048576
}
```

The server responds with a unique transfer ID:

```json
{
  "transfer_id": "abc123"
}
```

---

## 5. Chunked Transfer

Split the file into chunks.

Default chunk size:

```text
1 MB
```

Make the chunk size configurable.

For every chunk, send:

```text
transfer_id
start_byte
end_byte
total_size
```

Example:

```text
Transfer ID: abc123

Chunk:
start = 1048576
end   = 2097151
```

The server writes the chunk at the correct byte offset.

---

# 6. Resume Logic

The client must be able to ask:

```http
GET /transfer/{transfer_id}/status
```

The server should return information similar to:

```json
{
  "filename": "example.zip",
  "total_size": 2147483648,
  "received_bytes": 786432000,
  "status": "in_progress"
}
```

The client then resumes from:

```text
received_bytes
```

instead of starting again.

---

# 7. Important Reliability Requirement

Do not assume that:

```text
received_bytes = last chunk sent
```

The server should be the source of truth.

A chunk should only be considered successfully transferred after the server confirms that it has been written successfully.

Example:

```text
Client
  |
  | Chunk 750 MB → 751 MB
  |
  v
Server
  |
  | Write chunk
  |
  | fsync/flush as appropriate
  |
  | HTTP 200/201
  |
  v
Client
```

Only then should the client advance its confirmed transfer position.

---

# 8. Handling Interrupted Transfers

The system must handle:

### Case 1 — Wi-Fi disconnect

The client detects the failure and retries.

It must query the server for the current offset and continue.

### Case 2 — Server crashes

After restarting the server, previously received data must remain available.

The client should query the transfer status and continue.

### Case 3 — Android app closes

When reopened, the app should be able to identify unfinished transfers and resume them.

### Case 4 — Manual pause

The user should be able to pause a transfer.

Pressing Resume should continue from the stored offset.

### Case 5 — Duplicate chunk

If a chunk is accidentally sent twice, the server must not corrupt the file.

---

# 9. File Integrity

After completion, verify that the resulting file is identical to the source file.

Use a cryptographic hash such as:

```text
SHA-256
```

The client calculates:

```text
SHA256(original_file)
```

The server calculates:

```text
SHA256(received_file)
```

The application should display:

```text
Transfer complete
SHA-256 verified
```

If the hashes differ:

```text
Integrity check failed
```

Do not silently mark the transfer as successful.

---

# 10. Server

Build a lightweight local server.

The server must:

* listen on the local network
* expose transfer APIs
* create transfer sessions
* store transfer metadata
* receive chunks
* write chunks to the correct offsets
* track received bytes
* survive server restarts
* report transfer status
* verify final file integrity
* prevent accidental overwriting/corruption
* provide useful logs

Use a simple persistent metadata system such as SQLite or JSON if appropriate.

Prefer SQLite if it does not unnecessarily complicate the implementation.

---

# 11. Android Application

Build an Android application with a simple but polished UI.

The main screen should contain:

```text
Resumable File Transfer

[ Select File ]

Selected:
example.zip
2.1 GB

Destination:
192.168.x.x

[ Connect ]

Progress
████████████░░░░░░░░ 62%

1.3 GB / 2.1 GB

Speed: 18.4 MB/s
ETA: 46 seconds

[ Pause ] [ Cancel ]
```

After interruption:

```text
Connection interrupted.

1.31 GB / 2.10 GB received.

[ Resume ]
```

When completed:

```text
Transfer Complete

2.10 GB transferred
SHA-256 verified

[ Done ]
```

---

# 12. Android File Selection

Use Android's standard file picker.

Do not require the user to manually type filesystem paths.

The application should obtain a URI and read the file through Android's content APIs.

It must work with normal files accessible through the Android file picker.

---

# 13. Background Transfer

If practical, implement the transfer using an Android foreground service or another appropriate background mechanism.

The transfer should not immediately die merely because the user leaves the transfer screen.

Display a notification while a transfer is running.

Example:

```text
File transfer

example.zip
62% — 1.3 GB / 2.1 GB

18.4 MB/s
```

If implementation time becomes a serious constraint, prioritize **correct resumability and reliability over background-service polish**.

---

# 14. Server Discovery

The easiest initial implementation may allow the user to enter the computer's local IP address.

Example:

```text
Computer IP:

192.168.1.105

[ Connect ]
```

If time permits, implement automatic local-network discovery.

Possible approaches include:

* mDNS
* UDP broadcast
* QR code containing server address
* simple discovery endpoint

Do not sacrifice the core resumable-transfer functionality just to implement discovery.

---

# 15. Security

This is a local-network prototype, but still implement basic protections.

At minimum:

* validate transfer IDs
* sanitize filenames
* prevent path traversal
* do not blindly trust client-provided paths
* restrict file storage to a dedicated directory
* validate chunk offsets
* reject invalid ranges
* reject chunks exceeding file size

Do not allow requests such as:

```text
../../important-file
```

to escape the transfer directory.

---

# 16. Transfer State

Each transfer should have a state similar to:

```text
CREATED
TRANSFERRING
PAUSED
INTERRUPTED
COMPLETED
FAILED
CANCELLED
```

Persist enough information so that a server restart does not destroy transfer state.

---

# 17. Concurrency

Initially implement reliable sequential chunk transfer.

Do NOT immediately build complicated parallel uploads.

Once the basic system works, optionally investigate parallel chunk transfer.

Correctness is more important than maximum theoretical speed.

---

# 18. Performance

Use streaming I/O.

Do NOT load an entire multi-gigabyte file into RAM.

Bad:

```text
read entire file → RAM → upload
```

Good:

```text
disk
  ↓
small buffer
  ↓
network
  ↓
server
  ↓
disk
```

Use a configurable buffer/chunk size.

The application should be capable of handling files much larger than available RAM.

---

# 19. Demonstration Requirement

The final application must make the resumability easy to demonstrate.

Create a test/demo procedure.

Example:

### Demo

1. Select a large file.
2. Start transfer.
3. Allow transfer to reach approximately 30–60%.
4. Disconnect Wi-Fi or stop the server.
5. Show that the transfer stops.
6. Restore the connection/server.
7. Press Resume.
8. Show that the transfer continues from the previous position.
9. Complete the transfer.
10. Show SHA-256 verification.

The UI/logs should make it obvious that the second phase did NOT retransmit the entire file.

---

# 20. Logging

Provide useful logs such as:

```text
[INFO] Transfer created: abc123
[INFO] File: example.zip
[INFO] Size: 2147483648 bytes

[INFO] Receiving bytes 0-1048575
[INFO] Receiving bytes 1048576-2097151

[WARN] Connection interrupted

[INFO] Current server offset: 734003200
[INFO] Resuming from byte 734003200

[INFO] Transfer completed
[INFO] SHA-256 verified
```

Avoid logging sensitive data unnecessarily.

---

# 21. Project Structure

Choose a clean structure.

For example:

```text
resumable-file-transfer/
│
├── android/
│   ├── app/
│   └── ...
│
├── server/
│   ├── ...
│
├── docs/
│   ├── architecture.md
│   ├── protocol.md
│   └── demo.md
│
├── README.md
└── .gitignore
```

Adapt this structure if another architecture is technically better.

---

# 22. Documentation

Create a high-quality README containing:

## Project title

Resumable File Transfer

## Problem

Explain why ordinary file transfers fail when a connection is interrupted.

## Solution

Explain:

* chunking
* transfer IDs
* persistent offsets
* resume requests
* server-side state
* SHA-256 verification

## Architecture

Include an ASCII architecture diagram.

## Tech stack

List the technologies used.

## Setup

Give exact instructions for:

1. Starting the server
2. Finding the computer's IP
3. Connecting the Android application
4. Selecting a file
5. Starting a transfer
6. Resuming an interrupted transfer

## Demo

Explain exactly how to demonstrate an interrupted transfer.

## Technical highlights

Explain the most impressive engineering aspects.

---

# 23. Development Strategy

Work incrementally.

Do NOT attempt to generate the entire project blindly in one step.

Follow this order:

### Phase 1 — Repository setup

Create the project structure and initialize version control.

### Phase 2 — Server

Implement:

* health endpoint
* transfer creation
* metadata
* chunk receiving
* persistent state
* status endpoint

Test the server independently.

### Phase 3 — Basic Android client

Implement:

* file picker
* server address input
* HTTP connection
* basic upload

### Phase 4 — Resumability

Implement:

* status query
* offset calculation
* interrupted-transfer recovery
* pause/resume
* retry logic

### Phase 5 — Integrity

Implement:

* SHA-256
* final verification
* failure handling

### Phase 6 — UI

Improve:

* progress
* speed
* ETA
* transfer status
* errors
* notifications

### Phase 7 — Testing

Perform actual interruption tests.

### Phase 8 — Documentation

Create the final README and demo instructions.

---

# 24. Testing Requirements

Create automated tests wherever practical.

At minimum test:

### Normal transfer

```text
small file → complete
```

### Large file

```text
large file → complete without excessive RAM usage
```

### Interrupted transfer

```text
transfer → interruption → resume → complete
```

### Server restart

```text
transfer → stop server → restart server → resume
```

### Invalid offset

```text
invalid chunk range → reject
```

### Duplicate chunk

```text
same chunk sent twice → file remains correct
```

### Integrity

```text
source SHA256 == received SHA256
```

---

# 25. Error Handling

Never silently fail.

Display useful errors.

Examples:

```text
Unable to connect to server.

Connection lost. Retrying...

Server rejected chunk.

Invalid transfer offset.

File changed since transfer started.

Integrity verification failed.
```

If the source file changes during a transfer, detect this where practical and prevent silently producing a corrupted result.

---

# 26. AI Agent Rules

While implementing this project:

1. **Inspect the existing repository before changing anything.**
2. Do not overwrite working code unnecessarily.
3. Prefer small, testable changes.
4. Run builds/tests after significant changes.
5. Fix errors rather than merely reporting them.
6. Do not claim something works without testing it.
7. If a dependency is required, explain why and install/configure it when possible.
8. Keep the implementation simple enough for a beginner to understand.
9. Prioritize reliability over unnecessary features.
10. Do not implement fake progress bars.
11. Do not simulate resumability.
12. The server's actual stored offset must determine resume position.
13. Do not load entire files into memory.
14. Preserve partial files across server restarts.
15. Verify the final file cryptographically.

---

# 27. Definition of Done

The project is complete only when all of the following are true:

* [ ] Android app builds successfully.
* [ ] Server starts successfully.
* [ ] Android can connect to the server.
* [ ] User can select a file.
* [ ] File transfers over local Wi-Fi.
* [ ] Progress is displayed.
* [ ] Transfer can be paused.
* [ ] Transfer can be interrupted.
* [ ] Server retains partial data.
* [ ] Client can query the server for the current offset.
* [ ] Transfer resumes from the stored offset.
* [ ] Transfer does not restart from zero.
* [ ] Server survives restart without losing partial transfer state.
* [ ] Large files are streamed rather than loaded completely into RAM.
* [ ] Final SHA-256 hash is verified.
* [ ] Invalid ranges are rejected.
* [ ] Filenames are safely handled.
* [ ] Automated tests cover the important transfer logic.
* [ ] README contains complete setup instructions.
* [ ] A reproducible interruption/resume demo works.

---

# 28. Final Priority Order

If development time becomes limited, prioritize features in this exact functional order:

1. **Actual file transfer**
2. **Chunked transfer**
3. **Persistent server-side offset**
4. **Resume after interruption**
5. **Server restart recovery**
6. **File integrity verification**
7. **Error handling**
8. **Progress/speed/ETA UI**
9. **Background transfer**
10. **Automatic server discovery**
11. **Extra optimizations**

Do not sacrifice items 1–6 for cosmetic features.

---

# 29. Start Now

First inspect the current environment and repository.

Determine:

* operating system
* installed development tools
* Android SDK availability
* Java/Kotlin/Gradle availability
* Python/Node/etc. availability
* existing project files
* connected Android device availability

Then create a concise implementation plan.

After that, **begin implementing Phase 1 and Phase 2 immediately**.

Do not spend the entire response explaining the plan.

Make the project.

Run it.

Test it.

Fix problems.

Continue until the working resumable-transfer prototype is complete.

