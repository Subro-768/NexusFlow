package com.resumabletransfer.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*

class TransferForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "transfer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        var transferManagerInstance: TransferManager? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                val notification = buildNotification("Transfer starting...", 0, 0, 0L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        } else {
                            0
                        }
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                observeProgress()
            }
            ACTION_PAUSE -> {
                transferManagerInstance?.pauseTransfer()
            }
            ACTION_RESUME -> {
                // Resume is typically triggered with parameters from activity or manager
            }
            ACTION_CANCEL -> {
                transferManagerInstance?.cancelTransfer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun observeProgress() {
        val tm = transferManagerInstance ?: return
        serviceScope.launch {
            tm.progressState.collect { progress ->
                when (progress.status) {
                    TransferStatus.TRANSFERRING, TransferStatus.CONNECTING, TransferStatus.PAUSED -> {
                        val notif = buildNotification(
                            filename = progress.filename,
                            progressPercent = progress.progressPercent,
                            transferredBytes = progress.transferredBytes,
                            totalBytes = progress.totalBytes,
                            speedBytes = progress.speedBytesPerSec,
                            status = progress.status
                        )
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, notif)
                    }
                    TransferStatus.COMPLETED -> {
                        val notif = NotificationCompat.Builder(this@TransferForegroundService, CHANNEL_ID)
                            .setContentTitle("Transfer Complete")
                            .setContentText("${progress.filename} transferred successfully (SHA-256 verified)")
                            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .build()
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, notif)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.INTERRUPTED -> {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun buildNotification(
        filename: String = "",
        progressPercent: Int = 0,
        transferredBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytes: Long = 0L,
        status: TransferStatus = TransferStatus.TRANSFERRING
    ): Notification {
        val speedMb = speedBytes / (1024.0 * 1024.0)
        val transMb = transferredBytes / (1024.0 * 1024.0)
        val totMb = totalBytes / (1024.0 * 1024.0)

        val statusText = when (status) {
            TransferStatus.PAUSED -> "Paused — %.1f / %.1f MB".format(transMb, totMb)
            TransferStatus.CONNECTING -> "Connecting..."
            else -> "%.1f / %.1f MB (%.1f MB/s)".format(transMb, totMb, speedMb)
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pActivityIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (filename.isNotEmpty()) filename else "File Transfer")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, progressPercent, false)
            .setContentIntent(pActivityIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for active file transfers"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
