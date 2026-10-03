package com.resumabletransfer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.resumabletransfer.app.server.EmbeddedTransferServer
import com.resumabletransfer.app.server.IncomingTransferState
import com.resumabletransfer.app.ui.formatEta
import com.resumabletransfer.app.ui.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Progress notification for an *incoming* transfer.
 *
 * The receive endpoint used to be visible only while the Receiver Hub happened
 * to be on screen: with the phone locked, a 2 GB push from the laptop showed
 * nothing at all, and the user had no way to tell it was running, let alone stop
 * it. This posts the transfer the way the send path does and -- more to the
 * point -- puts Pause and Cancel where they can be reached with the screen off.
 *
 * The Hub's on-screen controls and this notification act on the same
 * [EmbeddedTransferServer] session, so the two cannot disagree.
 */
object IncomingTransferNotifier {

    private const val TAG = "IncomingNotifier"
    const val CHANNEL_ID = "receive_channel"
    const val NOTIFICATION_ID = 1002

    const val ACTION_PAUSE = "com.resumabletransfer.app.RECEIVE_PAUSE"
    const val ACTION_RESUME = "com.resumabletransfer.app.RECEIVE_RESUME"
    const val ACTION_CANCEL = "com.resumabletransfer.app.RECEIVE_CANCEL"
    const val EXTRA_TRANSFER_ID = "transfer_id"

    /**
     * Posts at most one update per [MIN_UPDATE_INTERVAL_MS] unless the status
     * changed. The state flow emits once per 1 MB chunk, and re-posting a
     * visibly identical notification dozens of times a second is wasted work in
     * the shade.
     */
    private const val MIN_UPDATE_INTERVAL_MS = 700L

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    @Volatile private var lastPostAt = 0L
    @Volatile private var lastStatus = ""

    /** Begins mirroring the server's incoming state into a notification. */
    fun start(context: Context, server: EmbeddedTransferServer) {
        stop()
        appContext = context.applicationContext
        createChannel()
        val newScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = newScope
        job = newScope.launch {
            server.incomingState.collect { state ->
                try {
                    if (state == null) dismiss() else post(state)
                } catch (e: Exception) {
                    // A notification must never take the receive endpoint down.
                    Log.w(TAG, "Could not update receive notification: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        lastStatus = ""
        lastPostAt = 0L
        clearNotification()
    }

    private fun notificationManager(): NotificationManager? =
        appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext?.getString(R.string.recv_channel_name) ?: "Incoming files",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext?.getString(R.string.recv_channel_description)
            setShowBadge(true)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun post(state: IncomingTransferState) {
        val context = appContext ?: return
        val nm = notificationManager() ?: return

        val terminal = state.status == "COMPLETED" || state.status == "CANCELLED" ||
            state.status == "FAILED"
        val paused = state.status == "PAUSED"

        val now = System.currentTimeMillis()
        val statusChanged = state.status != lastStatus
        if (!statusChanged && now - lastPostAt < MIN_UPDATE_INTERVAL_MS) return
        lastPostAt = now
        lastStatus = state.status

        val percent = if (state.totalSize > 0) {
            (state.receivedBytes * 100 / state.totalSize).toInt()
        } else 0

        val detail = when (state.status) {
            "PAUSED" -> context.getString(R.string.recv_notification_paused_detail)
            "COMPLETED" -> context.getString(
                if (state.sha256Verified) R.string.recv_notification_done_verified
                else R.string.recv_notification_done_unverified
            )
            "CANCELLED" -> context.getString(R.string.recv_notification_cancelled)
            "FAILED" -> context.getString(R.string.recv_notification_failed)
            else -> buildString {
                append(formatFileSize(state.receivedBytes))
                append(" / ")
                append(formatFileSize(state.totalSize))
                if (state.speedBytesPerSec > 0) {
                    append(" · ")
                    append(formatFileSize(state.speedBytesPerSec))
                    append("/s")
                }
                if (state.etaSeconds > 0) {
                    append(" · ")
                    append(context.getString(R.string.recv_notification_eta, formatEta(state.etaSeconds)))
                }
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(state.filename)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(!terminal && !paused)
            .setAutoCancel(terminal)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (terminal) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(100, percent, paused)
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                context.getString(if (paused) R.string.action_resume else R.string.action_pause),
                actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, state.transferId)
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                actionIntent(ACTION_CANCEL, state.transferId)
            )
        }

        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }

    private fun dismiss() {
        lastStatus = ""
        lastPostAt = 0L
        clearNotification()
    }

    private fun clearNotification() {
        notificationManager()?.cancel(NOTIFICATION_ID)
    }

    private fun actionIntent(action: String, transferId: String): PendingIntent {
        val context = appContext ?: return PendingIntent.getBroadcast(
            null,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE
        )
        val intent = Intent(action).apply { putExtra(EXTRA_TRANSFER_ID, transferId) }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Applies the receive notification's action buttons.
 *
 * A [BroadcastReceiver] rather than a Service: the receive endpoint is not a
 * component Android can start, so the broadcast is delivered in-process and
 * routed to the live server through [EmbeddedTransferServer.activeInstance].
 */
class ReceiveActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val server = EmbeddedTransferServer.activeInstance
        if (server == null) {
            Log.i(TAG, "No active receiver; dropping ${intent.action}")
            // Do not leave a stale notification behind once the endpoint is gone.
            IncomingTransferNotifier.stop()
            return
        }

        val transferId = intent.getStringExtra(IncomingTransferNotifier.EXTRA_TRANSFER_ID)
            ?.takeIf { it.isNotBlank() }
        val applied = when (intent.action) {
            IncomingTransferNotifier.ACTION_PAUSE ->
                if (transferId != null) server.pauseIncoming(transferId) else server.pauseActiveIncoming()
            IncomingTransferNotifier.ACTION_RESUME ->
                if (transferId != null) server.resumeIncoming(transferId) else server.resumeActiveIncoming()
            IncomingTransferNotifier.ACTION_CANCEL ->
                if (transferId != null) server.cancelIncoming(transferId) else server.cancelActiveIncoming()
            else -> false
        }
        Log.i(TAG, "${intent.action} -> transfer=$transferId applied=$applied")
    }

    private companion object {
        const val TAG = "ReceiveActionReceiver"
    }
}