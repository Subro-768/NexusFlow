# Resumable File Transfer — Demonstration Guide

This guide details the step-by-step procedure to demonstrate true resumability and fault tolerance.

---

## Prerequisites
1. Computer and Android device connected to the same Wi-Fi network (or tethered hotspot / adb reverse).
2. Server running on PC (`python3 server/run_server.py`).
3. Resumable Transfer App installed on Android phone.

---

## Demonstration 1: Interruption & Resume from Stored Offset

1. **Select a File**:
   - Open the Android app.
   - Tap **"Select File from Device"** and pick a large file (e.g. 50 MB – 500 MB video/zip).
2. **Connect**:
   - Enter your PC's IP address (e.g., `10.3.108.148` or `192.168.x.x`).
   - Tap **"Test Connection"** (status turns green "Connected").
3. **Start Transfer**:
   - Tap **"Start Transfer"**.
   - Observe real-time progress bar, speed (MB/s), and server logs streaming chunk by chunk.
4. **Trigger Interruption (at ~40-50%)**:
   - Turn OFF Wi-Fi on the phone (or press `Ctrl+C` on the PC server).
   - Observe: The Android app logs `Connection lost / Interrupted at X bytes` and the status badge turns red `Interrupted`.
5. **Restore Connection**:
   - Turn Wi-Fi back ON (or restart the PC server).
6. **Resume Transfer**:
   - Tap **"Resume (XX%)"**.
   - Observe:
     - The client queries `GET /transfer/{id}/status`.
     - The server returns the authoritative offset (e.g., byte `26214400`).
     - Upload immediately resumes from **45%** instead of starting at 0%.
7. **Verify Completion & SHA-256**:
   - Upon reaching 100%, the server computes the disk SHA-256 hash.
   - The Android app displays **"Transfer Complete & SHA-256 Verified"** with the exact match hash.

---

## Demonstration 2: Server Crash & Restart Recovery

1. Start a transfer.
2. In the terminal running the server, kill the server process (`Ctrl+C` or `kill`).
3. Observe Android app pausing/interrupting.
4. Restart the server: `python3 server/run_server.py`.
5. Press **Resume** on the Android app.
6. The SQLite database retains the transfer session and previously saved chunks on disk.
7. Transfer completes seamlessly with full cryptographic verification.
