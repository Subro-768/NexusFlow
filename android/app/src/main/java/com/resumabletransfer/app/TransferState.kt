package com.resumabletransfer.app

import androidx.compose.runtime.Immutable

enum class TransferStatus {
    IDLE,
    /**
     * Accepted and persisted, not started yet.
     *
     * A real state rather than an implicit one: a queued item is not "idle" (it
     * has a file and a destination) and not "transferring", and the task asks for
     * QUEUED -> TRANSFERRING -> COMPLETED explicitly. It exists so a queue can
     * outlive the process with its order and position intact.
     */
    QUEUED,
    CONNECTING,
    /**
     * Nothing answered at the target address.
     *
     * Distinct from INTERRUPTED on purpose. INTERRUPTED means a transfer was
     * under way and lost its connection, and it is resumable because there is
     * something to resume. Here nothing ever started, so offering RESUME and
     * reporting an interruption both misdescribe what happened.
     */
    NO_DEVICE,
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
