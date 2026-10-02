package com.resumabletransfer.app

import androidx.compose.runtime.Immutable

enum class TransferStatus {
    IDLE,
    CONNECTING,
    READY,
    TRANSFERRING,
    PAUSED,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Progress snapshot handed to the UI.
 *
 * Annotated [Immutable] because every field is a val of a stable primitive
 * type: without it the Compose compiler treats this class as unstable, so every
 * emission invalidates every composable that touches it. This object is
 * re-emitted on each chunk, so that instability was costing a full-screen
 * recomposition per chunk.
 */
@Immutable
data class TransferProgress(
    val status: TransferStatus = TransferStatus.IDLE,
    val filename: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val transferId: String = "",
    val expectedSha256: String = "",
    val calculatedSha256: String = "",
    val errorMessage: String = "",
    val logMessage: String = ""
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes.toFloat() else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}
