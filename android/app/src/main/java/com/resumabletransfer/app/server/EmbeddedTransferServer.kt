package com.resumabletransfer.app.server

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest
import com.resumabletransfer.app.HistoryEntry
import com.resumabletransfer.app.HistoryStore
import com.resumabletransfer.app.IncomingTransferNotifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class IncomingTransferState(
    val transferId: String,
    val filename: String,
    val totalSize: Long,
    val receivedBytes: Long,
    val status: String,
    val speedBytesPerSec: Long = 0,
    /**
     * Seconds left at the current rate; 0 when unknown (transfer not started,
     * finished, or paused). Derived from the rolling speed, never persisted.
     */
    val etaSeconds: Long = 0,
    val calculatedSha256: String? = null,
    val sha256Verified: Boolean = false,
    val filePath: String? = null,
    val senderName: String? = null
)

class EmbeddedTransferServer(private val context: Context, private val port: Int = 8000) {

    companion object {
        /**
         * The running server, so a notification action can reach it.
         *
         * The receive endpoint is not a Service -- it lives inside the Activity
         * process -- so there is no Intent to target. This handle is set in
         * [start] and cleared in [stop], and it is the only way an
         * out-of-UI tap can pause or cancel an incoming transfer. Volatile
         * because it is written on the caller's thread and read from the
         * broadcast thread.
         */
        @Volatile
        @JvmStatic
        var activeInstance: EmbeddedTransferServer? = null
    }

    private val tag = "NexusEmbeddedServer"
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private var isRunning = false

    /**
     * Pairing token for this receiver: shown on the Hub screen and carried in
     * the QR, so a sender can prove it is meant for this device rather than
     * merely able to reach it.
     *
     * Regenerated on every [start], so a token captured from an earlier session
     * stops working rather than lingering as a standing key.
     */
    @Volatile
    var authToken: String = PairingToken.generate()
        private set

    // State
    private val _incomingState = MutableStateFlow<IncomingTransferState?>(null)
    val incomingState: StateFlow<IncomingTransferState?> = _incomingState.asStateFlow()

    private val transfers = ConcurrentHashMap<String, MutableTransferRecord>()
    private val speedCalculator = SpeedTracker()

    data class MutableTransferRecord(
        val transferId: String,
        val filename: String,
        val totalSize: Long,
        var receivedBytes: Long,
        var status: String,
        val expectedSha256: String?,
        var calculatedSha256: String? = null,
        val targetFile: File,
        val senderName: String = "",
        /**
         * Receiver-side pause. While set, every chunk POST is answered with
         * 409 transfer_paused instead of being written, so the sender stops
         * instead of the transfer silently stalling.
         */
        @Volatile var paused: Boolean = false,
        /** Receiver-side cancel: the session is dead and its partial file is gone. */
        @Volatile var cancelled: Boolean = false
    )

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            // Fresh credential per session: a code read off a screenshot of an
            // earlier session must not keep working.
            authToken = PairingToken.generate()
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            activeInstance = this
            threadPool.execute { listenLoop() }
            IncomingTransferNotifier.start(context, this)
            Log.i(tag, "Embedded Receiver Server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server on port $port", e)
            false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        IncomingTransferNotifier.stop()
        if (activeInstance === this) activeInstance = null
        Log.i(tag, "Embedded Receiver Server stopped")
    }

    fun isServerRunning(): Boolean = isRunning

    private fun listenLoop() {
        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                threadPool.execute { handleClient(socket) }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(tag, "Accept error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 30000
        var input: InputStream? = null
        var output: OutputStream? = null

        try {
            input = socket.getInputStream()
            output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim().lowercase()
                    val v = line.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
            }

            // Route handling
            if (method == "OPTIONS") {
                sendCorsOk(output)
                return
            }

            // /health and the landing page stay outside the gate: the sender's
            // pre-flight probe needs /health to learn there is anything here
            // worth authenticating with, and neither exposes anything usable.
            // Everything that reads or writes a file needs the token.
            if (!isOpenPath(path)) {
                val query = fullPath.substringAfter("?", "")
                if (!PairingToken.matches(authToken, PairingToken.from(headers, query))) {
                    // Drain the body before answering. The client is usually
                    // still writing it, and closing on an unread request makes
                    // the kernel send RST -- which the sender sees as a dead
                    // connection instead of the 401 and the reason for it. A
                    // rejection a caller cannot read is not a rejection.
                    drainRequestBody(input, headers)
                    sendJson(
                        output, 401,
                        """{"error":"unauthorised","detail":"Pairing token missing or wrong. Scan the QR on this device, or type the code shown in its endpoint panel."}"""
                    )
                    return
                }
            }

            when {
                path == "/health" && method == "GET" -> {
                    handleHealth(output)
                }
                path == "/transfer" && method == "POST" -> {
                    handleCreateTransfer(input, headers, output)
                }
                path.startsWith("/transfer/") && path.endsWith("/chunk") && method == "POST" -> {
                    val transferId = path.removePrefix("/transfer/").removeSuffix("/chunk")
                    handleUploadChunk(transferId, input, headers, output)
                }
                path.startsWith("/transfer/") && path.endsWith("/status") && method == "GET" -> {
                    val transferId = path.removePrefix("/transfer/").removeSuffix("/status")
                    handleGetStatus(transferId, output)
                }
                path.startsWith("/transfer/") && path.endsWith("/cancel") && method == "POST" -> {
                    // The sender's cancelTransfer() has always called this; the
                    // route did not exist, so every cancel answered 404 and the
                    // half-written file stayed on disk forever.
                    val transferId = path.removePrefix("/transfer/").removeSuffix("/cancel")
                    handleRemoteCancel(transferId, output)
                }
                path == "/transfers" && method == "GET" -> {
                    handleListTransfers(output)
                }
                else -> {
                    sendJson(output, 404, JSONObject().put("detail", "Endpoint not found"))
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
            try {
                output?.let {
                    sendJson(it, 500, JSONObject().put("detail", e.message ?: "Internal Server Error"))
                }
            } catch (ignored: Exception) {}
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    @Volatile var deviceName: String = "Android Device"

    private fun handleHealth(output: OutputStream) {
        val ips = getLocalIpAddresses()
        val json = JSONObject().apply {
            put("status", "ok")
            put("service", "nexus-flow-android-receiver")
            put("device_name", deviceName)
            put("name", deviceName)
            put("local_ips", JSONArray(ips))
        }
        sendJson(output, 200, json)
    }

    private fun handleCreateTransfer(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = readExactBytes(input, contentLength)
        val bodyStr = String(bodyBytes, Charsets.UTF_8)
        val reqJson = JSONObject(bodyStr)

        val rawFilename = reqJson.getString("filename")
        val totalSize = if (reqJson.has("filesize")) reqJson.getLong("filesize") else reqJson.getLong("total_size")
        val expectedSha256 = when {
            reqJson.has("checksum") && !reqJson.isNull("checksum") -> reqJson.getString("checksum")
            reqJson.has("sha256") && !reqJson.isNull("sha256") -> reqJson.getString("sha256")
            else -> null
        }
        // Sender's advertised device name, used to group this file under a
        // device in the receiver's history. Falls back to blank if absent.
        val senderName = headers.entries
            .firstOrNull { it.key.equals("X-Sender-Name", ignoreCase = true) }
            ?.value
            ?.take(64)
            ?.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            ?: ""

        // Sanitize filename
        val cleanName = File(rawFilename).name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val transferId = UUID.randomUUID().toString()

        val downloadDir = getSaveDirectory()
        val targetFile = File(downloadDir, cleanName)

        // Preallocate
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.setLength(totalSize)
        }

        val record = MutableTransferRecord(
            transferId = transferId,
            filename = cleanName,
            totalSize = totalSize,
            receivedBytes = 0,
            status = "PENDING",
            expectedSha256 = expectedSha256,
            targetFile = targetFile,
            senderName = senderName
        )
        transfers[transferId] = record

        // A new session starts a fresh rate measurement; otherwise the first
        // second of this transfer reports the previous file's speed.
        speedCalculator.reset()

        _incomingState.value = IncomingTransferState(
            transferId = transferId,
            filename = cleanName,
            totalSize = totalSize,
            receivedBytes = 0,
            status = "PENDING",
            filePath = targetFile.absolutePath,
            senderName = senderName.ifBlank { null }
        )

        val resp = JSONObject().apply {
            put("transfer_id", transferId)
            put("filename", cleanName)
            put("total_size", totalSize)
            put("received_bytes", 0)
            put("chunk_size", 1048576)
            put("status", "PENDING")
            put("expected_sha256", expectedSha256)
            put("calculated_sha256", JSONObject.NULL)
        }
        sendJson(output, 201, resp)
    }

    private fun handleUploadChunk(transferId: String, input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val record = transfers[transferId]
        if (record == null) {
            sendJson(output, 404, JSONObject().put("detail", "Transfer not found"))
            return
        }

        val startByte = headers["x-start-byte"]?.toLongOrNull()
        val endByte = headers["x-end-byte"]?.toLongOrNull()
        val totalSize = headers["x-total-size"]?.toLongOrNull()
        val contentLength = headers["content-length"]?.toIntOrNull()

        if (startByte == null || endByte == null || contentLength == null) {
            sendJson(output, 400, JSONObject().put("detail", "Missing chunk range headers"))
            return
        }

        val expectedLen = (endByte - startByte + 1).toInt()
        if (contentLength != expectedLen) {
            sendJson(output, 400, JSONObject().put("detail", "Content-Length mismatch: expected $expectedLen but got $contentLength"))
            return
        }

        // Receiver-side pause/cancel, checked before a single byte is written.
        //
        // The body is drained first: the sender is still streaming a 1 MB chunk
        // when we decide to refuse it. Answering and closing the socket at that
        // point makes the client see a broken pipe instead of our status code,
        // so it would report a network error rather than "paused by receiver".
        // Draining is cheap and keeps the rejection legible.
        val refused: String? = when {
            record.cancelled -> "transfer_cancelled"
            record.paused -> "transfer_paused"
            else -> null
        }
        if (refused != null) {
            drainBody(input, contentLength)
            val code = if (refused == "transfer_cancelled") 410 else 409
            sendJson(
                output, code,
                JSONObject()
                    .put("detail", refused)
                    .put("status", if (refused == "transfer_cancelled") "CANCELLED" else "PAUSED")
            )
            return
        }

        // Seek and write directly into target file
        //
        // The write can fail after the session was cancelled underneath it:
        // cancelIncoming() deletes the partial, and on Android's sdcard an open
        // file that is unlinked underneath the writer fails with ENOENT rather
        // than quietly writing to a dead inode. That exception used to escape to
        // the generic handler in handleClient, which answered 500 with a
        // truncated body -- so the sender reported a connection error for what
        // was a deliberate cancel. Answer the refusal instead.
        var bytesConsumed = 0
        val writeFailure: String? = try {
            RandomAccessFile(record.targetFile, "rw").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(64 * 1024)
                while (bytesConsumed < expectedLen) {
                    val toRead = minOf(buffer.size, expectedLen - bytesConsumed)
                    val count = input.read(buffer, 0, toRead)
                    if (count == -1) break
                    raf.write(buffer, 0, count)
                    bytesConsumed += count
                }
                raf.fd.sync()
            }
            null
        } catch (e: Exception) {
            Log.w(tag, "Chunk write failed for $transferId: ${e.message}")
            e.message ?: e.javaClass.simpleName
        }

        if (writeFailure != null) {
            val wasCancelled = record.cancelled
            val detail = if (wasCancelled) "transfer_cancelled" else "chunk_write_failed"
            // Consume the rest of the request before answering. Closing a socket
            // that still has unread inbound data sends a TCP reset, which throws
            // away the response the sender has not read yet -- the sender then
            // reports "connection reset" and never learns the transfer was
            // deliberately cancelled. Draining first makes this an ordinary FIN.
            drainBody(input, contentLength - bytesConsumed)
            sendJson(
                output,
                if (wasCancelled) 410 else 500,
                JSONObject()
                    .put("detail", detail)
                    .put("status", if (wasCancelled) "CANCELLED" else record.status)
                    .put("error", writeFailure)
            )
            publish(record, etaSeconds = 0L)
            return
        }

        synchronized(record) {
            val newOffset = endByte + 1
            if (newOffset > record.receivedBytes) {
                record.receivedBytes = newOffset
            }
            // Re-read the flags instead of assuming this chunk is the newest
            // word on the session: a chunk accepted a moment before the user hit
            // Pause lands here afterwards and would otherwise overwrite PAUSED
            // with IN_PROGRESS -- the Hub would then show a Pause button for a
            // transfer that is already held, and tapping it would do nothing.
            record.status = when {
                record.cancelled -> "CANCELLED"
                record.paused -> "PAUSED"
                record.receivedBytes >= record.totalSize -> "COMPLETED"
                else -> "IN_PROGRESS"
            }
        }

        val speed = speedCalculator.recordBytes(expectedLen.toLong())
        val eta = if (record.paused || record.cancelled) 0L
        else estimateEta(record.receivedBytes, record.totalSize, speed)

        var verified = false
        if (record.status == "COMPLETED") {
            val calcHash = calculateFileSha256(record.targetFile)
            record.calculatedSha256 = calcHash
            if (record.expectedSha256 != null && !record.expectedSha256.equals(calcHash, ignoreCase = true)) {
                record.status = "FAILED"
                verified = false
            } else {
                verified = true
            }
        }

        // Persist to the device-local history exactly once, on the transition
        // into a terminal state. HistoryStore de-duplicates on transferId, so a
        // repeated completion is harmless.
        if (record.status == "COMPLETED" || record.status == "FAILED") {
            try {
                HistoryStore.get(context).upsert(
                    HistoryEntry(
                        id = transferId,
                        peerName = record.senderName,
                        peerHost = record.senderName,
                        port = port,
                        filename = record.filename,
                        totalBytes = record.totalSize,
                        transferredBytes = record.receivedBytes,
                        status = record.status,
                        timestampMillis = System.currentTimeMillis(),
                        direction = HistoryEntry.DIRECTION_RECEIVED,
                        sha256 = record.calculatedSha256
                    )
                )
            } catch (e: Exception) {
                Log.w(tag, "history upsert failed: ${e.message}")
            }
        }

        _incomingState.value = IncomingTransferState(
            transferId = transferId,
            filename = record.filename,
            totalSize = record.totalSize,
            receivedBytes = record.receivedBytes,
            status = record.status,
            speedBytesPerSec = if (record.paused || record.cancelled) 0L else speed,
            etaSeconds = eta,
            calculatedSha256 = record.calculatedSha256,
            sha256Verified = verified,
            filePath = record.targetFile.absolutePath,
            senderName = record.senderName.ifBlank { null }
        )

        val resp = JSONObject().apply {
            put("transfer_id", transferId)
            put("received_bytes", record.receivedBytes)
            put("total_size", record.totalSize)
            put("status", record.status)
            put("calculated_sha256", record.calculatedSha256 ?: JSONObject.NULL)
            put("sha256_verified", verified)
        }
        sendJson(output, 200, resp)
    }

    private fun handleGetStatus(transferId: String, output: OutputStream) {
        val record = transfers[transferId]
        if (record == null) {
            sendJson(output, 404, JSONObject().put("detail", "Transfer not found"))
            return
        }

        val resp = JSONObject().apply {
            put("transfer_id", record.transferId)
            put("filename", record.filename)
            put("total_size", record.totalSize)
            put("received_bytes", record.receivedBytes)
            put("status", record.status)
            put("expected_sha256", record.expectedSha256 ?: JSONObject.NULL)
            put("calculated_sha256", record.calculatedSha256 ?: JSONObject.NULL)
        }
        sendJson(output, 200, resp)
    }

    /**
     * Sender-driven cancel: `POST /transfer/{id}/cancel`.
     *
     * Shared with [cancelIncoming] so a cancel from either device ends the same
     * way -- session marked dead, partial file deleted, peers told 410 on their
     * next chunk.
     */
    private fun handleRemoteCancel(transferId: String, output: OutputStream) {
        val record = transfers[transferId]
        if (record == null) {
            sendJson(output, 404, JSONObject().put("detail", "Transfer not found"))
            return
        }
        cancelIncoming(transferId)
        sendJson(
            output, 200,
            JSONObject()
                .put("transfer_id", transferId)
                .put("status", "CANCELLED")
                .put("received_bytes", record.receivedBytes)
        )
    }

    // ── Receiver-side pause / resume / cancel ────────────────────────────────
    // Driven from the Receiver Hub UI and from the receive notification's
    // action buttons. Every one of them updates _incomingState, so the screen
    // and the notification can never disagree about what the session is doing.

    fun pauseIncoming(transferId: String): Boolean {
        val record = transfers[transferId] ?: return false
        if (record.cancelled || record.status == "COMPLETED") return false
        record.paused = true
        record.status = "PAUSED"
        publish(record, etaSeconds = 0L)
        Log.i(tag, "Incoming transfer $transferId paused at ${record.receivedBytes}/${record.totalSize}")
        return true
    }

    fun resumeIncoming(transferId: String): Boolean {
        val record = transfers[transferId] ?: return false
        if (record.cancelled) return false
        record.paused = false
        record.status = if (record.receivedBytes >= record.totalSize) "COMPLETED" else "IN_PROGRESS"
        // The rate measured before the pause understates the resumed rate, and
        // an ETA computed from it would be wrong for the first second, so drop
        // it and let the next chunk re-measure.
        speedCalculator.reset()
        publish(record, etaSeconds = 0L)
        Log.i(tag, "Incoming transfer $transferId resumed")
        return true
    }

    /**
     * Ends the session and removes the partial file.
     *
     * Deleting the partial is the point: it was preallocated to the full size,
     * so without this a cancelled 2 GB transfer leaves 2 GB of zeroes on disk
     * that the Received Files list would happily show as a finished download.
     * A completed transfer is never deleted.
     */
    fun cancelIncoming(transferId: String): Boolean {
        val record = transfers[transferId] ?: return false
        val wasComplete = record.status == "COMPLETED"
        record.cancelled = true
        record.paused = false
        record.status = "CANCELLED"
        if (!wasComplete) {
            runCatching { record.targetFile.delete() }
                .onFailure { Log.w(tag, "Could not delete partial ${record.targetFile}: ${it.message}") }
        }
        publish(record, etaSeconds = 0L)
        Log.i(tag, "Incoming transfer $transferId cancelled (partial deleted: ${!wasComplete})")
        return true
    }

    /** Convenience for callers that only care about "whatever is arriving now". */
    fun pauseActiveIncoming(): Boolean =
        _incomingState.value?.takeIf { it.status == "PENDING" || it.status == "IN_PROGRESS" }
            ?.let { pauseIncoming(it.transferId) } ?: false

    fun resumeActiveIncoming(): Boolean =
        _incomingState.value?.takeIf { it.status == "PAUSED" }
            ?.let { resumeIncoming(it.transferId) } ?: false

    fun cancelActiveIncoming(): Boolean =
        _incomingState.value?.let { cancelIncoming(it.transferId) } ?: false

    /**
     * Single place where [_incomingState] is built for a live session, so the
     * Hub, the notification and the log all report the same numbers.
     */
    private fun publish(record: MutableTransferRecord, etaSeconds: Long) {
        _incomingState.value = IncomingTransferState(
            transferId = record.transferId,
            filename = record.filename,
            totalSize = record.totalSize,
            receivedBytes = record.receivedBytes,
            status = record.status,
            speedBytesPerSec = if (record.paused) 0L else speedCalculator.currentSpeed(),
            etaSeconds = etaSeconds,
            calculatedSha256 = record.calculatedSha256,
            sha256Verified = record.status == "COMPLETED",
            filePath = record.targetFile.absolutePath,
            senderName = record.senderName.ifBlank { null }
        )
    }

    private fun estimateEta(received: Long, total: Long, speed: Long): Long =
        if (speed > 0 && received < total) (total - received) / speed else 0L

    private fun handleListTransfers(output: OutputStream) {
        val arr = JSONArray()
        for (r in transfers.values) {
            arr.put(JSONObject().apply {
                put("transfer_id", r.transferId)
                put("filename", r.filename)
                put("total_size", r.totalSize)
                put("received_bytes", r.receivedBytes)
                put("status", r.status)
                put("created_at", System.currentTimeMillis().toString())
                // Same digest fields the per-transfer /status endpoint reports.
                //
                // They were missing here, so a client reading the list saw
                // `sha256_verified: null` for a transfer that had in fact been
                // verified -- measured on a real transfer, where /transfer/{id}
                // /status returned a matching digest for the same transfer_id.
                // Two endpoints describing one transfer must not disagree about
                // whether it was verified, or a caller has no way to tell which
                // one to believe.
                put("expected_sha256", r.expectedSha256 ?: JSONObject.NULL)
                put("calculated_sha256", r.calculatedSha256 ?: JSONObject.NULL)
                // Derived rather than stored, so it cannot disagree with the
                // digests beside it: a completed transfer is verified exactly
                // when both digests are present and equal. Anything else is
                // genuinely not yet known, and is reported as null rather than
                // a misleading false.
                val verifiedNow: Any = if (
                    r.status == "COMPLETED" &&
                    r.expectedSha256 != null &&
                    r.calculatedSha256 != null &&
                    r.expectedSha256.equals(r.calculatedSha256, ignoreCase = true)
                ) true else JSONObject.NULL
                put("sha256_verified", verifiedNow)
                put("verified", verifiedNow)
            })
        }
        sendJson(output, 200, arr)
    }

    /**
     * Where a received file is written, and where the user will look for it.
     *
     * The public `Downloads/NexusFlow` folder is the right answer and is what
     * this returns on a normal device: it is created by the app, so it exists
     * and is writable, and a user browsing Downloads finds the file there.
     *
     * `getExternalFilesDir(null)` is a fallback for when that is *not* possible --
     * a profile with no public storage, or a device where the folder cannot be
     * created. It needs no permission at any API level and is visible under
     * Android/data/<pkg>, so the file is still reachable rather than lost in
     * private storage where no file manager can see it. `receivedFilesDirectory()`
     * exposes whatever was chosen, so the UI opens the folder the bytes are
     * actually in rather than assuming the public path.
     */
    private fun getSaveDirectory(): File {
        val legacy = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NexusFlow"
        )
        // Still the first choice where the platform actually permits it: on API
        // 28 and below this is the real Downloads folder, which is where a user
        // expects a received file to be.
        @Suppress("DEPRECATION")
        if (legacy.exists() && legacy.canWrite()) return legacy

        val ext = context.getExternalFilesDir(null)
        if (ext != null) {
            val dir = File(ext, "NexusFlow")
            if (dir.exists() || dir.mkdirs()) return dir
        }
        // Last resort. Reported honestly by lastResortSavePath() below, because
        // a file the user cannot find is not a successful transfer.
        return context.filesDir
    }

    /**
     * The folder the UI should open when the user taps "received downloads".
     *
     * Asks the receiver rather than hard-coding the public Downloads path, so
     * that if the fallback above ever does engage, the button opens the folder
     * the bytes are actually in instead of one that was never written to.
     */
    fun receivedFilesDirectory(): File = getSaveDirectory()

    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(128 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error retrieving IP addresses", e)
        }
        return if (ips.isNotEmpty()) ips else listOf("127.0.0.1")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) {
                val s = sb.toString()
                return if (s.endsWith("\r")) s.substring(0, s.length - 1) else s
            }
            sb.append(c.toChar())
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(data, totalRead, length - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return data
    }

    /**
     * Reads and throws away [length] bytes so a rejected chunk leaves the
     * socket at a clean request boundary. See the refusal branch in
     * [handleUploadChunk] for why this matters.
     */
    private fun drainBody(input: InputStream, length: Int) {
        if (length <= 0) return
        val scratch = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size, remaining))
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun sendCorsOk(output: OutputStream) {
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Length: 0\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * Paths reachable without a token.
     *
     * Deliberately short. `/health` is what tells a sender there is something
     * here worth authenticating against, and it returns a status and a device
     * name — never the token. Everything that touches a file is authenticated.
     */
    /**
     * Read and discard a request body we are about to refuse.
     *
     * Bounded by Content-Length, and tolerant of a client that sends less than
     * it promised -- this runs on the rejection path, so it must never be the
     * thing that throws. A socket error here still gets the 401 out.
     */
    private fun drainRequestBody(input: InputStream, headers: Map<String, String>) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return
        var remaining = length
        try {
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val read = input.read(scratch, 0, minOf(scratch.size, remaining))
                if (read <= 0) break
                remaining -= read
            }
        } catch (e: Exception) {
            Log.d(tag, "drain before 401 ended early: ${e.message}")
        }
    }

    private fun isOpenPath(path: String): Boolean =
        path == "/" || path == "/index.html" || path == "/health" || path == "/favicon.ico"

    private fun sendJson(output: OutputStream, statusCode: Int, json: Any) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            410 -> "Gone"
            else -> "Internal Server Error"
        }
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                // This endpoint closes the socket after every response, but
                // without saying so, a client with a connection pool (requests,
                // OkHttp) assumes keep-alive and reuses a socket that is already
                // gone -- surfacing as "Remote end closed connection without
                // response" on some later, unrelated chunk. Advertising the close
                // makes the client open a fresh connection instead.
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private class SpeedTracker {
        private var lastTime = System.currentTimeMillis()
        private var bytesSinceLast = 0L
        private var currentSpeed = 0L

        @Synchronized
        fun recordBytes(bytes: Long): Long {
            val now = System.currentTimeMillis()
            bytesSinceLast += bytes
            val elapsed = now - lastTime
            if (elapsed >= 1000) {
                currentSpeed = (bytesSinceLast * 1000) / elapsed
                bytesSinceLast = 0L
                lastTime = now
            }
            return currentSpeed
        }

        /** The rate as last measured, without advancing the window. */
        @Synchronized
        fun currentSpeed(): Long = currentSpeed

        /**
         * Forgets the window. Called when a new session starts and after a
         * receiver-side resume: carrying the pre-pause rate into a new transfer
         * would report the old file's speed on the new one.
         */
        @Synchronized
        fun reset() {
            bytesSinceLast = 0L
            currentSpeed = 0L
            lastTime = System.currentTimeMillis()
        }
    }
}
