package com.resumabletransfer.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max

class TransferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TransferManager"

        @Volatile
        private var instance: TransferManager? = null

        fun getInstance(context: Context): TransferManager {
            return instance ?: synchronized(this) {
                instance ?: TransferManager(context.applicationContext).also { instance = it }
            }
        }

        private const val PREFS_NAME = "resumable_transfer_prefs"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_URI = "selected_uri"
        private const val KEY_FILENAME = "selected_filename"
        private const val KEY_FILESIZE = "selected_filesize"
        private const val KEY_TRANSFER_ID = "transfer_id"
        private const val KEY_EXPECTED_SHA256 = "expected_sha256"
        private const val KEY_CHUNK_SIZE_KB = "chunk_size_kb"
        private const val KEY_THROTTLE_DELAY_MS = "throttle_delay_ms"

        /**
         * Minimum gap between UI progress emissions. The transfer loop can
         * complete a 1 MB chunk every few ms on a fast link; emitting each one
         * forces a full screen recomposition for a sub-frame change.
         */
        const val PROGRESS_EMIT_INTERVAL_MS = 120L

        /**
         * Markers the receiver puts in the `detail` of a refused chunk, and
         * which therefore show up inside the error message built by
         * [TransferApiClient.uploadChunk].
         *
         * They are part of the wire contract with the desktop client, so both
         * sides must agree on the exact strings; see docs/protocol.md.
         */
        const val RECEIVER_PAUSED_MARKER = "transfer_paused"
        const val RECEIVER_CANCELLED_MARKER = "transfer_cancelled"

        /** Statuses that must reach the UI immediately, never throttled. */
        private val TERMINAL_STATUSES = setOf(
            TransferStatus.COMPLETED,
            TransferStatus.FAILED,
            TransferStatus.INTERRUPTED,
            TransferStatus.CANCELLED
        )
        private const val KEY_RECENT_IPS = "recent_ips_list"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _progressState = MutableStateFlow(TransferProgress(status = TransferStatus.IDLE))
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    /** Timestamp of the last throttled emission; guarded by the flow's own lock. */
    @Volatile
    private var lastEmitAt = 0L

    /** Peer currently being sent to; used to file history under the right device. */
    @Volatile
    private var currentPeerName: String = ""
    @Volatile
    private var currentPeerHost: String = ""
    @Volatile
    private var currentPeerPort: Int = 8000

    private var transferJob: Job? = null
    private var activeApiClient: TransferApiClient? = null
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    /**
     * What the running (or last) transfer was started with.
     *
     * Pause and resume must work from a notification action, where no Activity
     * exists to supply the URI, file name, size and peer. Captured once at
     * [startTransfer] so the service can restart an interrupted send without
     * asking the UI for parameters it no longer has.
     */
    private data class ActiveSpec(
        val serverUrl: String,
        val uri: Uri,
        val filename: String,
        val fileSize: Long,
        val peerName: String
    )

    @Volatile
    private var activeSpec: ActiveSpec? = null

    /** Statuses where a pause/resume request is meaningful. */
    private val PAUSABLE_STATUSES = setOf(
        TransferStatus.CONNECTING,
        TransferStatus.TRANSFERRING
    )

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getSavedServerIp(): String = prefs.getString(KEY_SERVER_IP, "127.0.0.1") ?: "127.0.0.1"
    fun setSavedServerIp(ip: String) = prefs.edit().putString(KEY_SERVER_IP, ip).apply()

    fun getSavedServerPort(): String = prefs.getString(KEY_SERVER_PORT, "8000") ?: "8000"
    fun setSavedServerPort(port: String) = prefs.edit().putString(KEY_SERVER_PORT, port).apply()

    fun getRecentIps(): List<String> {
        val raw = prefs.getString(KEY_RECENT_IPS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveRecentIp(ip: String) {
        val clean = ip.trim()
        if (clean.isEmpty() || clean == "127.0.0.1") return
        val current = getRecentIps().toMutableList()
        current.remove(clean)
        current.add(0, clean)
        val trimmed = current.take(20)
        prefs.edit().putString(KEY_RECENT_IPS, trimmed.joinToString(",")).apply()
    }

    fun getDeviceName(): String =
        prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun setDeviceName(name: String) =
        prefs.edit().putString(KEY_DEVICE_NAME, name.trim()).apply()

    fun getChunkSizeKb(): Int = prefs.getInt(KEY_CHUNK_SIZE_KB, 1024)
    fun setChunkSizeKb(sizeKb: Int) = prefs.edit().putInt(KEY_CHUNK_SIZE_KB, sizeKb).apply()

    fun getThrottleDelayMs(): Int = prefs.getInt(KEY_THROTTLE_DELAY_MS, 0)
    fun setThrottleDelayMs(delayMs: Int) = prefs.edit().putInt(KEY_THROTTLE_DELAY_MS, delayMs).apply()

    fun getSavedFileUri(): Uri? {
        val str = prefs.getString(KEY_URI, null) ?: return null
        return try { Uri.parse(str) } catch (e: Exception) { null }
    }

    fun getSavedFileName(): String? = prefs.getString(KEY_FILENAME, null)
    fun getSavedFileSize(): Long? {
        val s = prefs.getLong(KEY_FILESIZE, -1L)
        return if (s >= 0) s else null
    }

    fun saveFileSelection(uri: Uri, filename: String, filesize: Long) {
        val prevFilename = prefs.getString(KEY_FILENAME, "")
        val prevFilesize = prefs.getLong(KEY_FILESIZE, 0L)

        // If user selects a different file, reset the previous transfer session
        if (filename != prevFilename || filesize != prevFilesize) {
            Log.i(TAG, "Different file selected: '$filename' ($filesize bytes). Resetting old transfer session.")
            prefs.edit()
                .remove(KEY_TRANSFER_ID)
                .remove(KEY_EXPECTED_SHA256)
                .putString(KEY_URI, uri.toString())
                .putString(KEY_FILENAME, filename)
                .putLong(KEY_FILESIZE, filesize)
                .apply()
            _progressState.value = TransferProgress(
                status = TransferStatus.IDLE,
                filename = filename,
                totalBytes = filesize
            )
        } else {
            prefs.edit()
                .putString(KEY_URI, uri.toString())
                .putString(KEY_FILENAME, filename)
                .putLong(KEY_FILESIZE, filesize)
                .apply()
        }
    }

    fun clearSavedSession() {
        activeApiClient?.cancelAllRequests()
        transferJob?.cancel()
        prefs.edit()
            .remove(KEY_TRANSFER_ID)
            .remove(KEY_EXPECTED_SHA256)
            .remove(KEY_URI)
            .remove(KEY_FILENAME)
            .remove(KEY_FILESIZE)
            .apply()
        _progressState.value = TransferProgress(status = TransferStatus.IDLE)
    }

    /**
     * Atomically updates progress state.
     *
     * The previous body was `_progressState.value = update(_progressState.value)`,
     * a read-modify-write that raced: the IO loop (calling updateProgress from
     * inside its own transfer lambda) could interleave with pauseTransfer /
     * cancelTransfer / resumeTransfer running on the main thread, silently
     * dropping one of the two updates. `MutableStateFlow.update` retries the
     * transform under a lock, so no update is lost.
     */
    fun updateProgress(update: (TransferProgress) -> TransferProgress) {
        _progressState.update(update)
    }

    /**
     * Throttled variant for the per-chunk hot path.
     *
     * The transfer loop calls [updateProgress] once per 1 MB chunk. With a fast
     * link that is tens of emissions per second, each one invalidating the whole
     * Compose screen. This coalesces to at most one emission every
     * [PROGRESS_EMIT_INTERVAL_MS] while still forcing terminal states
     * (COMPLETED / FAILED / INTERRUPTED / CANCELLED) through immediately so the
     * UI never lags behind the real outcome.
     */
    fun updateProgressThrottled(update: (TransferProgress) -> TransferProgress) {
        val next = _progressState.value.let(update)
        val isTerminal = next.status in TERMINAL_STATUSES
        val now = SystemClock.elapsedRealtime()
        val last = lastEmitAt
        if (!isTerminal && now - last < PROGRESS_EMIT_INTERVAL_MS) {
            return
        }
        lastEmitAt = now
        _progressState.value = next
    }

    fun calculateSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(262144)
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun startTransfer(
        serverUrl: String,
        uri: Uri,
        filename: String,
        fileSize: Long,
        existingTransferId: String? = null,
        peerDisplayName: String = ""
    ) {
        val chunkSize = getChunkSizeKb() * 1024
        val throttleDelay = getThrottleDelayMs()

        Log.d(TAG, "startTransfer: url=$serverUrl, file=$filename, size=$fileSize, id=$existingTransferId")

        activeApiClient?.cancelAllRequests()
        transferJob?.cancel()
        isPaused = false
        isCancelled = false

        saveFileSelection(uri, filename, fileSize)

        // Remember who we are talking to, so the completed transfer can be filed
        // under this peer in the device-local history. The name is captured at
        // start because the discovered peer list may change mid-transfer.
        currentPeerHost = Uri.parse(serverUrl).host ?: serverUrl
        currentPeerPort = Uri.parse(serverUrl).port.takeIf { it > 0 } ?: 8000
        currentPeerName = peerDisplayName

        activeSpec = ActiveSpec(serverUrl, uri, filename, fileSize, peerDisplayName)

        transferJob = coroutineScope.launch {
            val client = TransferApiClient(serverUrl)
            activeApiClient = client

            try {
                updateProgress {
                    it.copy(
                        status = TransferStatus.CONNECTING,
                        filename = filename,
                        totalBytes = fileSize,
                        errorMessage = "",
                        logMessage = "Connecting to server $serverUrl..."
                    )
                }

                // 1. Check server health
                var isOnline = false
                var healthErr = ""
                for (attempt in 1..3) {
                    if (isCancelled) return@launch
                    val healthRes = client.checkHealth()
                    if (healthRes.isSuccess) {
                        isOnline = true
                        break
                    } else {
                        healthErr = healthRes.exceptionOrNull()?.localizedMessage ?: "Connection failed"
                        updateProgress {
                            it.copy(
                                logMessage = "Connecting... (Attempt $attempt/3: $healthErr)"
                            )
                        }
                        delay(600L)
                    }
                }

                if (!isOnline) {
                    updateProgress {
                        it.copy(
                            status = TransferStatus.INTERRUPTED,
                            errorMessage = "Cannot reach server: $healthErr",
                            logMessage = "Server unreachable at $serverUrl. Check connection and tap Resume."
                        )
                    }
                    return@launch
                }

                // 2. Local SHA-256 hash
                var localSha256 = prefs.getString(KEY_EXPECTED_SHA256, "") ?: ""
                if (localSha256.isBlank()) {
                    localSha256 = _progressState.value.expectedSha256
                }
                if (localSha256.isBlank()) {
                    updateProgress {
                        it.copy(
                            status = TransferStatus.TRANSFERRING,
                            logMessage = "Calculating file SHA-256 hash..."
                        )
                    }
                    localSha256 = try {
                        val computed = calculateSha256(uri)
                        prefs.edit().putString(KEY_EXPECTED_SHA256, computed).apply()
                        computed
                    } catch (e: Exception) {
                        Log.e(TAG, "Error calculating hash: ${e.message}")
                        ""
                    }
                }

                // 3. Obtain or resume transfer session
                var transferId = existingTransferId ?: prefs.getString(KEY_TRANSFER_ID, null)
                var confirmedOffset = 0L

                if (!transferId.isNullOrBlank()) {
                    updateProgress {
                        it.copy(
                            status = TransferStatus.CONNECTING,
                            logMessage = "Fetching server offset for session $transferId..."
                        )
                    }
                    val statusRes = client.getTransferStatus(transferId)
                    if (statusRes.isSuccess) {
                        val session = statusRes.getOrThrow()
                        // Ensure session belongs to this same filename and size
                        if (session.filename == filename && session.totalSize == fileSize) {
                            confirmedOffset = session.receivedBytes
                            prefs.edit().putString(KEY_TRANSFER_ID, session.transferId).apply()
                            val pct = if (fileSize > 0) (confirmedOffset * 100 / fileSize).toInt() else 0
                            updateProgress {
                                it.copy(
                                    transferId = session.transferId,
                                    transferredBytes = confirmedOffset,
                                    expectedSha256 = localSha256,
                                    logMessage = "Resuming session ${session.transferId} from byte $confirmedOffset ($pct%)"
                                )
                            }
                            Log.i(TAG, "Resuming session $transferId at offset $confirmedOffset / $fileSize ($pct%)")
                        } else {
                            // File mismatch with old session -> start fresh
                            Log.w(TAG, "Old session $transferId was for '${session.filename}', starting fresh for '$filename'")
                            transferId = null
                        }
                    } else {
                        val ex = statusRes.exceptionOrNull()
                        if (ex is NoSuchElementException) {
                            transferId = null
                        } else {
                            updateProgress {
                                it.copy(
                                    status = TransferStatus.INTERRUPTED,
                                    errorMessage = "Status query failed: ${ex?.localizedMessage}",
                                    logMessage = "Status query failed. Tap Resume to retry."
                                )
                            }
                            return@launch
                        }
                    }
                }

                if (transferId.isNullOrBlank()) {
                    val sessionRes = client.createTransfer(filename, fileSize, localSha256, chunkSize)
                    if (sessionRes.isFailure) {
                        val err = sessionRes.exceptionOrNull()?.localizedMessage ?: "Failed to create session"
                        updateProgress {
                            it.copy(
                                status = TransferStatus.FAILED,
                                errorMessage = err,
                                logMessage = "Failed to create transfer session on server."
                            )
                        }
                        return@launch
                    }
                    val session = sessionRes.getOrThrow()
                    transferId = session.transferId
                    confirmedOffset = session.receivedBytes
                    prefs.edit().putString(KEY_TRANSFER_ID, transferId).apply()
                    updateProgress {
                        it.copy(
                            transferId = transferId,
                            transferredBytes = confirmedOffset,
                            expectedSha256 = localSha256,
                            logMessage = "Created transfer session: $transferId"
                        )
                    }
                }

                // 4. Chunked Transfer Loop with fast FileChannel random access seek
                var currentOffset = confirmedOffset
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceSpeedCalc = 0L

                // Set when the *receiver* refuses the chunk because it was
                // paused or cancelled on that device. Distinct from a network
                // failure: the peer is reachable and made a decision, so the
                // session is still resumable and must not be reported as an
                // interruption.
                var peerPaused = false
                var peerCancelled = false

                while (currentOffset < fileSize && !isCancelled) {
                    if (isPaused) {
                        updateProgress {
                            it.copy(
                                status = TransferStatus.PAUSED,
                                logMessage = "Transfer paused at $currentOffset bytes."
                            )
                        }
                        while (isPaused && !isCancelled) {
                            delay(200)
                        }
                        if (isCancelled) break
                        updateProgress {
                            it.copy(
                                status = TransferStatus.TRANSFERRING,
                                logMessage = "Transfer resumed."
                            )
                        }
                        lastSpeedCalcTime = System.currentTimeMillis()
                        bytesSinceSpeedCalc = 0L
                    }

                    if (throttleDelay > 0) {
                        delay(throttleDelay.toLong())
                    }

                    val startByte = currentOffset
                    val endByte = (startByte + chunkSize - 1).coerceAtMost(fileSize - 1)
                    val expectedChunkLen = (endByte - startByte + 1).toInt()

                    // Fast seek & read using FileChannel
                    val chunkData = readChunkFast(uri, startByte, expectedChunkLen)
                    if (chunkData == null || chunkData.size != expectedChunkLen) {
                        updateProgress {
                            it.copy(
                                status = TransferStatus.FAILED,
                                errorMessage = "Failed to read file chunk. Please re-select file if storage permissions expired.",
                                logMessage = "Read error at byte offset $startByte"
                            )
                        }
                        return@launch
                    }

                    // Upload chunk
                    var chunkSuccess = false
                    var retryCount = 0
                    val maxRetries = 2

                    while (!chunkSuccess && retryCount < maxRetries && !isCancelled && !isPaused
                            && !peerPaused && !peerCancelled) {
                        val uploadRes = client.uploadChunk(transferId, startByte, endByte, fileSize, chunkData)
                        if (uploadRes.isSuccess) {
                            val result = uploadRes.getOrThrow()
                            confirmedOffset = result.receivedBytes
                            currentOffset = endByte + 1
                            chunkSuccess = true
                            bytesSinceSpeedCalc += expectedChunkLen

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastSpeedCalcTime
                            var speed = 0L
                            var eta = 0L

                            if (timeDiff >= 400) {
                                speed = (bytesSinceSpeedCalc * 1000) / timeDiff
                                val remainingBytes = max(0L, fileSize - confirmedOffset)
                                eta = if (speed > 0) remainingBytes / speed else 0L
                                lastSpeedCalcTime = now
                                bytesSinceSpeedCalc = 0L
                            } else {
                                speed = _progressState.value.speedBytesPerSec
                                eta = _progressState.value.etaSeconds
                            }

                            val isFinal = (confirmedOffset >= fileSize)
                            updateProgressThrottled {
                                it.copy(
                                    status = if (isFinal) {
                                        if (result.sha256Verified) TransferStatus.COMPLETED else TransferStatus.FAILED
                                    } else TransferStatus.TRANSFERRING,
                                    transferredBytes = confirmedOffset,
                                    speedBytesPerSec = speed,
                                    etaSeconds = eta,
                                    calculatedSha256 = result.calculatedSha256 ?: "",
                                    logMessage = if (isFinal) {
                                        "Transfer complete! SHA-256 verified."
                                    } else {
                                        "Transferred bytes $startByte–$endByte (${it.progressPercent}%)"
                                    }
                                )
                            }

                            // Persist to device-local history so the History
                            // screen can show every transfer ever made, not just
                            // those for whichever peer is currently selected.
                            if (isFinal) {
                                HistoryStore.get(context).upsert(
                                    HistoryEntry(
                                        id = transferId ?: filename,
                                        peerName = currentPeerName,
                                        peerHost = currentPeerHost,
                                        port = currentPeerPort,
                                        filename = filename,
                                        totalBytes = fileSize,
                                        transferredBytes = confirmedOffset,
                                        status = if (result.sha256Verified) "COMPLETED" else "FAILED",
                                        timestampMillis = System.currentTimeMillis(),
                                        direction = HistoryEntry.DIRECTION_SENT,
                                        sha256 = result.calculatedSha256
                                    )
                                )
                            }
                        } else {
                            retryCount++
                            val err = uploadRes.exceptionOrNull()?.localizedMessage ?: "Network connection lost"
                            // The receiver refused deliberately (409/410), so
                            // retrying is pointless and would burn the retries
                            // on a decision the user made on the other device.
                            if (err.contains(RECEIVER_PAUSED_MARKER)) {
                                peerPaused = true
                                Log.i(TAG, "Receiver paused this transfer at offset $confirmedOffset")
                                break
                            }
                            if (err.contains(RECEIVER_CANCELLED_MARKER)) {
                                peerCancelled = true
                                Log.i(TAG, "Receiver cancelled this transfer at offset $confirmedOffset")
                                break
                            }
                            updateProgressThrottled {
                                it.copy(
                                    logMessage = "Interruption detected: $err"
                                )
                            }
                            if (retryCount < maxRetries) {
                                delay(600L * retryCount)
                            }
                        }
                    }

                    if (peerPaused) {
                        // The peer is holding the session open. Keep the
                        // transfer id so Resume picks up at the same offset
                        // instead of re-uploading from zero.
                        val pct = if (fileSize > 0) (confirmedOffset * 100 / fileSize).toInt() else 0
                        updateProgress {
                            it.copy(
                                status = TransferStatus.PAUSED,
                                logMessage = "Paused by the receiving device at $pct%. " +
                                    "Resume here, or resume it on that device."
                            )
                        }
                        return@launch
                    }

                    if (peerCancelled) {
                        prefs.edit().remove(KEY_TRANSFER_ID).remove(KEY_EXPECTED_SHA256).apply()
                        updateProgress {
                            it.copy(
                                status = TransferStatus.CANCELLED,
                                logMessage = "The receiving device cancelled this transfer."
                            )
                        }
                        return@launch
                    }

                    if (!chunkSuccess && !isCancelled && !isPaused) {
                        val pct = if (fileSize > 0) (confirmedOffset * 100 / fileSize).toInt() else 0
                        updateProgress {
                            it.copy(
                                status = TransferStatus.INTERRUPTED,
                                errorMessage = "Connection lost. Tap Resume to continue.",
                                logMessage = "Interrupted at offset $confirmedOffset / $fileSize bytes ($pct%)."
                            )
                        }
                        return@launch
                    }
                }

                if (isCancelled) {
                    if (!transferId.isNullOrBlank()) {
                        client.cancelTransfer(transferId)
                    }
                    prefs.edit().remove(KEY_TRANSFER_ID).remove(KEY_EXPECTED_SHA256).apply()
                    updateProgress {
                        it.copy(
                            status = TransferStatus.CANCELLED,
                            logMessage = "Transfer cancelled."
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in transfer: ${e.message}", e)
                updateProgress {
                    it.copy(
                        status = TransferStatus.INTERRUPTED,
                        errorMessage = e.localizedMessage ?: "Unknown error",
                        logMessage = "Transfer stopped: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun readChunkFast(uri: Uri, startByte: Long, length: Int): ByteArray? {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    channel.position(startByte)
                    val buffer = ByteBuffer.allocate(length)
                    var totalRead = 0
                    while (totalRead < length) {
                        val read = channel.read(buffer)
                        if (read == -1) break
                        totalRead += read
                    }
                    return if (totalRead == length) buffer.array() else buffer.array().copyOf(totalRead)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "openFileDescriptor seek failed: ${e.message}")
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var skipped = 0L
                while (skipped < startByte) {
                    val s = stream.skip(startByte - skipped)
                    if (s <= 0) {
                        val discard = ByteArray(minOf(65536L, startByte - skipped).toInt())
                        val r = stream.read(discard)
                        if (r <= 0) return null
                        skipped += r
                    } else {
                        skipped += s
                    }
                }

                val buffer = ByteArray(length)
                var bytesReadTotal = 0
                while (bytesReadTotal < length) {
                    val r = stream.read(buffer, bytesReadTotal, length - bytesReadTotal)
                    if (r == -1) break
                    bytesReadTotal += r
                }
                if (bytesReadTotal == length) buffer else buffer.copyOf(bytesReadTotal)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback stream read failed: ${e.message}")
            null
        }
    }

    /**
 * Pauses a running send.
 *
 * Guarded on [PAUSABLE_STATUSES]: an unconditional flag used to be able to
 * overwrite INTERRUPTED or COMPLETED with PAUSED, which a notification action
 * could then re-label a finished transfer as resumable.
 */
fun pauseTransfer() {
    if (_progressState.value.status !in PAUSABLE_STATUSES) return
        isPaused = true
        updateProgress { it.copy(status = TransferStatus.PAUSED, logMessage = "Transfer paused.") }
    }

    /**
     * Resume driven from outside the UI (the notification's Resume action).
     *
     * Two cases, and they need different handling:
     *  * PAUSED -- the chunk loop is still parked on the pause flag, so clearing
     *    it continues the upload at the current offset. Restarting the job here
     *    would renegotiate the session for nothing.
     *  * INTERRUPTED -- the loop has already exited, so the transfer has to be
     *    started again from [activeSpec].
     *
     * Returns false when there is nothing to resume, so the caller can say so
     * rather than leaving a dead Resume button that does nothing.
     */
    fun resumeActiveTransfer(): Boolean {
        when (_progressState.value.status) {
            TransferStatus.PAUSED -> {
                isPaused = false
                updateProgress {
                    it.copy(status = TransferStatus.TRANSFERRING, logMessage = "Transfer resumed.")
                }
                return true
            }
            TransferStatus.INTERRUPTED -> {
                val spec = activeSpec ?: return false
                resumeTransfer(
                    serverUrl = spec.serverUrl,
                    uri = spec.uri,
                    filename = spec.filename,
                    fileSize = spec.fileSize,
                    peerDisplayName = spec.peerName
                )
                return true
            }
            else -> return false
        }
    }

    fun resumeTransfer(
        serverUrl: String,
        uri: Uri,
        filename: String,
        fileSize: Long,
        peerDisplayName: String = ""
    ) {
        val currentTransferId = _progressState.value.transferId.ifEmpty {
            prefs.getString(KEY_TRANSFER_ID, null)
        }
        startTransfer(serverUrl, uri, filename, fileSize, currentTransferId, peerDisplayName)
    }

    fun cancelTransfer() {
        isCancelled = true
        isPaused = false
        activeApiClient?.cancelAllRequests()
        transferJob?.cancel()
        prefs.edit().remove(KEY_TRANSFER_ID).remove(KEY_EXPECTED_SHA256).apply()
        updateProgress { it.copy(status = TransferStatus.CANCELLED, logMessage = "Transfer cancelled.") }
    }
}
