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
    val calculatedSha256: String? = null,
    val sha256Verified: Boolean = false,
    val filePath: String? = null,
    val senderName: String? = null
)

class EmbeddedTransferServer(private val context: Context, private val port: Int = 8000) {

    private val tag = "NexusEmbeddedServer"
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private var isRunning = false

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
        val senderName: String = ""
    )

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            threadPool.execute { listenLoop() }
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

        // Seek and write directly into target file
        RandomAccessFile(record.targetFile, "rw").use { raf ->
            raf.seek(startByte)
            val buffer = ByteArray(64 * 1024)
            var bytesReadTotal = 0
            while (bytesReadTotal < expectedLen) {
                val toRead = minOf(buffer.size, expectedLen - bytesReadTotal)
                val count = input.read(buffer, 0, toRead)
                if (count == -1) break
                raf.write(buffer, 0, count)
                bytesReadTotal += count
            }
            raf.fd.sync()
        }

        synchronized(record) {
            val newOffset = endByte + 1
            if (newOffset > record.receivedBytes) {
                record.receivedBytes = newOffset
            }
            if (record.receivedBytes >= record.totalSize) {
                record.status = "COMPLETED"
            } else {
                record.status = "IN_PROGRESS"
            }
        }

        val speed = speedCalculator.recordBytes(expectedLen.toLong())

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
            speedBytesPerSec = speed,
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
            })
        }
        sendJson(output, 200, arr)
    }

    private fun getSaveDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val nexusDir = File(downloads, "NexusFlow")
        if (!nexusDir.exists()) {
            nexusDir.mkdirs()
        }
        return if (nexusDir.exists() && nexusDir.canWrite()) nexusDir else context.filesDir
    }

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

    private fun sendCorsOk(output: OutputStream) {
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Length: 0\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendJson(output: OutputStream, statusCode: Int, json: Any) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json\r\n" +
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
    }
}
