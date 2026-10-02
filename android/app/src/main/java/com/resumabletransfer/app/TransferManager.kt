package com.resumabletransfer.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private const val KEY_RECENT_IPS = "recent_ips_list"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _progressState = MutableStateFlow(TransferProgress(status = TransferStatus.IDLE))
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    private var transferJob: Job? = null
    private var activeApiClient: TransferApiClient? = null
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

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

    fun updateProgress(update: (TransferProgress) -> TransferProgress) {
        _progressState.value = update(_progressState.value)
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
        existingTransferId: String? = null
    ) {
        val chunkSize = getChunkSizeKb() * 1024
        val throttleDelay = getThrottleDelayMs()

        Log.d(TAG, "startTransfer: url=$serverUrl, file=$filename, size=$fileSize, id=$existingTransferId")

        activeApiClient?.cancelAllRequests()
        transferJob?.cancel()
        isPaused = false
        isCancelled = false

        saveFileSelection(uri, filename, fileSize)

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

                    while (!chunkSuccess && retryCount < maxRetries && !isCancelled && !isPaused) {
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
                            updateProgress {
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
                        } else {
                            retryCount++
                            val err = uploadRes.exceptionOrNull()?.localizedMessage ?: "Network connection lost"
                            updateProgress {
                                it.copy(
                                    logMessage = "Interruption detected: $err"
                                )
                            }
                            if (retryCount < maxRetries) {
                                delay(600L * retryCount)
                            }
                        }
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

    fun pauseTransfer() {
        isPaused = true
        updateProgress { it.copy(status = TransferStatus.PAUSED, logMessage = "Transfer paused.") }
    }

    fun resumeTransfer(serverUrl: String, uri: Uri, filename: String, fileSize: Long) {
        val currentTransferId = _progressState.value.transferId.ifEmpty {
            prefs.getString(KEY_TRANSFER_ID, null)
        }
        startTransfer(serverUrl, uri, filename, fileSize, currentTransferId)
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
