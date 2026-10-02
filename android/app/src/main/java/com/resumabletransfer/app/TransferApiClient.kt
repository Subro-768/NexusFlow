package com.resumabletransfer.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TransferApiClient(private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
                    return Result.failure(IOException("Server returned HTTP ${response.code}"))
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
        chunkSize: Int = 1048576
    ): Result<TransferSession> {
        val json = JSONObject().apply {
            put("filename", filename)
            put("filesize", filesize)
            if (!checksum.isNullOrBlank()) {
                put("checksum", checksum)
            }
            put("chunk_size", chunkSize)
        }

        val request = Request.Builder()
            .url("$serverUrl/transfer")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Failed to create transfer: HTTP ${response.code} ${response.message}"))
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
                    return Result.failure(IOException("Failed to query transfer status: HTTP ${response.code}"))
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
                    return Result.failure(IOException("Failed to fetch history: HTTP ${response.code}"))
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
