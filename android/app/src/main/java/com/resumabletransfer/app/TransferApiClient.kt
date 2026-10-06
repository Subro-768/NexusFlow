package com.resumabletransfer.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TransferApiClient(
    private val serverUrl: String,
    connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    /**
     * The receiver's pairing token, or "" when none is known.
     *
     * With the receiver gated, a missing token is a 401 on the very first call,
     * so this is attached centrally rather than left to each call site to
     * remember — a per-chunk post that forgot it would fail mid-transfer and
     * surface as an unexplained connection error.
     */
    private val token: String = "",
    /**
     * Called once when the receiver answers 401, so the caller can drop the
     * now-dead credential instead of retrying it forever.
     *
     * Attached at construction rather than handled per call site: the token is
     * minted per receiver process, so staleness is the *expected* cause of a 401,
     * not an edge case. Keeping the token after it has been refused guarantees
     * the next attempt fails identically with no way to tell why.
     */
    private val onUnauthorized: (() -> Unit)? = null
) {

    /**
     * A request the receiver refused because the pairing token was missing or stale.
     *
     * The receiver answers `401` for three different situations that look identical
     * from the wire:
     *
     *  * no token was ever stored for this host,
     *  * a token was stored but the receiver has since restarted and regenerated
     *    its own, or
     *  * the token is simply wrong.
     *
     * All three used to arrive as a plain `IOException("... HTTP 401")`, which the
     * UI could only report as a generic failure. That matters because the second
     * case is the common one: the receiver mints a fresh token on every start (see
     * `generateToken` on both sides), so any receiver restart silently invalidates
     * every token already saved. Retrying the dead credential can never succeed --
     * the only fix is to pair again with the code on the receiver's screen.
     *
     * This type makes the case actionable instead of terminal-looking.
     */
    class UnauthorizedException(
        val statusCode: Int = 401,
        message: String = "Receiver rejected the pairing token."
    ) : IOException(message)

    companion object {
        /**
         * Generous for a real LAN transfer: a busy hotspot can take a second or
         * two to answer, and failing a live peer would be worse than waiting.
         */
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L

        /**
         * Used for the "is anybody there at all?" pre-flight. Long enough to
         * avoid a false negative, short enough that an absent device is named
         * in about a second instead of after three full timeouts.
         */
        const val PREFLIGHT_CONNECT_TIMEOUT_SECONDS = 2L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (token.isNotBlank()) {
                addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header(com.resumabletransfer.app.server.PairingToken.HEADER, token)
                            .build()
                    )
                }
            }
        }
        .build()

    /**
     * Turn a non-2xx response into the right failure type.
     *
     * Centralised so a 401 can never again be reported as a generic IO problem:
     * every call site previously built its own `IOException("... HTTP 401")` from
     * a slightly different message, which meant nothing upstream could recognise
     * the one failure whose remedy is "pair again", not "retry".
     */
    private fun httpFailure(prefix: String, code: Int, message: String = ""): IOException {
        if (code == 401) {
            // The credential is now known-bad: let the caller drop it so the next
            // attempt starts from the truth ("not paired") rather than repeating
            // a request that cannot succeed.
            onUnauthorized?.invoke()
            return UnauthorizedException(
                message = "Receiver refused the request (HTTP 401). " +
                    "The pairing code is missing, wrong, or out of date -- if the " +
                    "receiver was restarted it now has a new code. Tap the keyboard " +
                    "icon in NEARBY DEVICES and enter the code shown on the receiver."
            )
        }
        val suffix = if (message.isBlank()) "" else " $message"
        return IOException("$prefix: HTTP $code$suffix")
    }

    fun cancelAllRequests() {
        client.dispatcher.cancelAll()
    }

    data class ServerInfo(
        val status: String,
        val service: String,
        val localIps: List<String>
    )

    data class TransferSession(
        val transferId: String,
        val filename: String,
        val totalSize: Long,
        val receivedBytes: Long,
        val chunkSize: Int,
        val status: String,
        val expectedSha256: String?,
        val calculatedSha256: String?
    )

    data class ChunkUploadResult(
        val transferId: String,
        val receivedBytes: Long,
        val totalSize: Long,
        val status: String,
        val calculatedSha256: String?,
        val sha256Verified: Boolean
    )

    fun checkHealth(): Result<ServerInfo> {
        val request = Request.Builder()
            .url("$serverUrl/health")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(httpFailure("Server returned", response.code))
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val ips = mutableListOf<String>()
                val ipsArray = json.optJSONArray("local_ips")
                if (ipsArray != null) {
                    for (i in 0 until ipsArray.length()) {
                        ips.add(ipsArray.getString(i))
                    }
                }
                Result.success(
                    ServerInfo(
                        status = json.optString("status", "unknown"),
                        service = json.optString("service", "resumable-file-transfer"),
                        localIps = ips
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createTransfer(
        filename: String,
        filesize: Long,
        checksum: String?,
        chunkSize: Int = 1048576,
        senderName: String? = null
    ): Result<TransferSession> {
        val json = JSONObject().apply {
            put("filename", filename)
            put("filesize", filesize)
            if (!checksum.isNullOrBlank()) {
                put("checksum", checksum)
            }
            put("chunk_size", chunkSize)
        }

        val builder = Request.Builder()
            .url("$serverUrl/transfer")
            .post(json.toString().toRequestBody("application/json".toMediaType()))

        // Lets the receiver label this transfer with our device name instead of
        // a bare address, so the history screen can group by device.
        if (!senderName.isNullOrBlank()) {
            builder.addHeader("X-Sender-Name", senderName)
        }
        val request = builder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(httpFailure("Failed to create transfer", response.code, response.message))
                }
                val body = response.body?.string() ?: ""
                val resJson = JSONObject(body)
                Result.success(
                    TransferSession(
                        transferId = resJson.getString("transfer_id"),
                        filename = resJson.getString("filename"),
                        totalSize = resJson.getLong("total_size"),
                        receivedBytes = resJson.optLong("received_bytes", 0L),
                        chunkSize = resJson.optInt("chunk_size", chunkSize),
                        status = resJson.getString("status"),
                        expectedSha256 = if (resJson.isNull("expected_sha256")) null else resJson.optString("expected_sha256"),
                        calculatedSha256 = if (resJson.isNull("calculated_sha256")) null else resJson.optString("calculated_sha256")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getTransferStatus(transferId: String): Result<TransferSession> {
        val request = Request.Builder()
            .url("$serverUrl/transfer/$transferId/status")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return Result.failure(NoSuchElementException("Transfer session $transferId expired or not found"))
                }
                if (!response.isSuccessful) {
                    return Result.failure(httpFailure("Failed to query transfer status", response.code))
                }
                val body = response.body?.string() ?: ""
                val resJson = JSONObject(body)
                Result.success(
                    TransferSession(
                        transferId = resJson.getString("transfer_id"),
                        filename = resJson.getString("filename"),
                        totalSize = resJson.getLong("total_size"),
                        receivedBytes = resJson.getLong("received_bytes"),
                        chunkSize = resJson.optInt("chunk_size", 1048576),
                        status = resJson.getString("status"),
                        expectedSha256 = if (resJson.isNull("expected_sha256")) null else resJson.optString("expected_sha256"),
                        calculatedSha256 = if (resJson.isNull("calculated_sha256")) null else resJson.optString("calculated_sha256")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getTransfersHistory(): Result<List<TransferSession>> {
        val request = Request.Builder()
            .url("$serverUrl/transfers")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(httpFailure("Failed to fetch history", response.code))
                }
                val body = response.body?.string() ?: "[]"
                val jsonArray = JSONArray(body)
                val list = mutableListOf<TransferSession>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    list.add(
                        TransferSession(
                            transferId = item.getString("transfer_id"),
                            filename = item.getString("filename"),
                            totalSize = item.getLong("total_size"),
                            receivedBytes = item.getLong("received_bytes"),
                            chunkSize = item.optInt("chunk_size", 1048576),
                            status = item.getString("status"),
                            expectedSha256 = if (item.isNull("expected_sha256")) null else item.optString("expected_sha256"),
                            calculatedSha256 = if (item.isNull("calculated_sha256")) null else item.optString("calculated_sha256")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun uploadChunk(
        transferId: String,
        startByte: Long,
        endByte: Long,
        totalSize: Long,
        chunkData: ByteArray
    ): Result<ChunkUploadResult> {
        val mediaType = "application/octet-stream".toMediaType()
        val body = chunkData.toRequestBody(mediaType, 0, chunkData.size)

        val request = Request.Builder()
            .url("$serverUrl/transfer/$transferId/chunk")
            .addHeader("X-Start-Byte", startByte.toString())
            .addHeader("X-End-Byte", endByte.toString())
            .addHeader("X-Total-Size", totalSize.toString())
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    if (response.code == 401) {
                return Result.failure(httpFailure("Chunk rejected", 401, errorBody))
            }
            return Result.failure(IOException("Chunk rejected: HTTP ${response.code} - $errorBody"))
                }
                val respBody = response.body?.string() ?: ""
                val resJson = JSONObject(respBody)
                Result.success(
                    ChunkUploadResult(
                        transferId = resJson.getString("transfer_id"),
                        receivedBytes = resJson.getLong("received_bytes"),
                        totalSize = resJson.getLong("total_size"),
                        status = resJson.getString("status"),
                        calculatedSha256 = if (resJson.isNull("calculated_sha256")) null else resJson.optString("calculated_sha256"),
                        sha256Verified = resJson.optBoolean("sha256_verified", false)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cancelTransfer(transferId: String): Result<Boolean> {
        val request = Request.Builder()
            .url("$serverUrl/transfer/$transferId/cancel")
            .post("".toRequestBody())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
