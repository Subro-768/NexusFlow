# Measured Demonstration Results

This document contains empirical benchmark measurements from an end-to-end interrupted-and-resumed transfer test.

---

## Benchmark Run Summary

| Metric | Measured Value |
| :--- | :--- |
| **Test Payload Size** | **50.00 MB** (52,428,800 bytes) |
| **Chunk Size** | **1.00 MB** (1,048,576 bytes) |
| **Interruption Point** | **20.00 MB (40.00%)** (20,971,520 bytes) |
| **Resume Point** | **20,971,520 bytes** (Exact byte match) |
| **Transfer Time Phase 1 (0% -> 40%)** | **0.265 s** |
| **Interruption & Offset Query Latency** | **< 0.004 s** |
| **Transfer Time Phase 2 (40% -> 100%)** | **0.465 s** |
| **Total Transfer Time** | **0.734 s** |
| **Average Transfer Speed** | **68.11 MB/s** |
| **Expected SHA-256 Digest** | `144eb3e50b71972ce535de4c034f1500b1b4d2b17852499085911037c2feccfa` |
| **Calculated SHA-256 Digest** | `144eb3e50b71972ce535de4c034f1500b1b4d2b17852499085911037c2feccfa` |
| **SHA-256 Verification Result** | **PASSED (`sha256_verified: true`)** |

---

## 10-Step Verification Sequence Validation

1. **[PASS] Select large file**: Generated 50 MB randomized binary file `benchmark_test_50mb.bin`.
2. **[PASS] Start transfer**: Created session `085d9087b434` with preallocated physical storage.
3. **[PASS] Reach ~40%**: Transferred 20 chunks (20,971,520 bytes).
4. **[PASS] Stop server / drop connection**: Simulated socket disconnection at 40%.
5. **[PASS] Show interrupted state**: Status confirmed as interrupted on client.
6. **[PASS] Restart server**: Persistent SQLite database retained confirmed offset.
7. **[PASS] Resume**: Client queried `GET /transfer/085d9087b434/status` and received `received_bytes = 20971520`.
8. **[PASS] Continue from previous offset**: Client resumed streaming chunk 20 with `FileChannel` seek.
9. **[PASS] Complete transfer**: Remaining 30 chunks delivered cleanly to byte 52,428,800.
10. **[PASS] Cryptographic verification**: Full file hash matched expected SHA-256 digest with bit-level parity.
