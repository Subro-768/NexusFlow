package com.resumabletransfer.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Keeps a transfer alive while the app is not in the foreground.
 *
 * Background reliability needs three things working together:
 *
 *  1. [START_STICKY] so a process killed under memory pressure is restarted and
 *     re-foregrounds instead of silently dropping the transfer.
 *  2. A partial WakeLock, or the CPU sleeps once the screen is off and chunk
 *     uploads stall.
 *  3. A high-performance WifiLock. Without one the WiFi radio enters power-save
 *     and a transfer over a hotspot can freeze even with the screen lit.
 *
 * Locks are reference-count-off and released on every terminal state, so an
 * idle app never keeps the radio awake.
 */
class TransferForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "transfer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        /** Safety ceiling so a leaked lock cannot drain the battery forever. */
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        var transferManagerInstance: TransferManager? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

       override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart delivers a null intent: re-foreground so the
        // notification and locks are restored without disturbing the transfer.
        if (intent == null || intent.action == ACTION_START) {
            startForegroundNow()
            acquireLocks()
            observeProgress()
        }

        when (intent?.action) {
            ACTION_PAUSE -> transferManagerInstance?.pauseTransfer()
            ACTION_RESUME -> Unit
            ACTION_CANCEL -> {
                transferManagerInstance?.cancelTransfer()
                finish()
            }
        }

        return START_STICKY
    }

    private fun startForegroundNow() {
        val notification = buildNotification("Transfer running", 0, 0, 0L)
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
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexusFlow::Transfer")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
        }
        if (wifiLock == null) {
            wifiLock = (getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NexusFlow::Transfer")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /** Leaves the foreground and drops the locks; used on every terminal state. */
    private fun finish() {
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun observeProgress() {
        val tm = transferManagerInstance ?: return
        serviceScope.launch {
            tm.progressState.collect { progress ->
                when (progress.status) {
                    TransferStatus.TRANSFERRING,
                    TransferStatus.CONNECTING,
                    TransferStatus.PAUSED -> {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                filename = progress.filename,
                                progressPercent = progress.progressPercent,
                                transferredBytes = progress.transferredBytes,
                                totalBytes = progress.totalBytes,
                                speedBytes = progress.speedBytesPerSec,
                                status = progress.status
                            )
                       )
                    }
                    TransferStatus.COMPLETED -> {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(
                            NOTIFICATION_ID,
                            NotificationCompat.Builder(this@TransferForegroundService, CHANNEL_ID)
                                .setContentTitle("Transfer complete")
                                .setContentText(
                                    "${progress.filename} transferred successfully (SHA-256 verified)"
                                )
                                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .build()
                        )
                        finish()
                    }
                    TransferStatus.FAILED,
                    TransferStatus.CANCELLED,
                    TransferStatus.INTERRUPTED -> finish()
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
            TransferStatus.PAUSED -> "Paused - %.1f / %.1f MB".format(transMb, totMb)
            TransferStatus.CONNECTING -> "Connecting..."
            else -> "%.1f / %.1f MB (%.1f MB/s)".format(transMb, totMb, speedMb)
        }

        val pActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (filename.isNotEmpty()) filename else "File transfer")
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
                "File transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress for active file transfers"
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}