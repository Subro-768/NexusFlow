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
import com.resumabletransfer.app.ui.formatEta
import kotlinx.coroutines.*
import java.util.Locale

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

        /** Stable PendingIntent request codes for the notification actions. */
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_CANCEL = 12

        var transferManagerInstance: TransferManager? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

        private var wakeLock: PowerManager.WakeLock? = null
        private var wifiLock: WifiManager.WifiLock? = null

        /**
         * Whether startForeground() has already been answered for this service
         * instance.
         *
         * Android gives a started foreground service about five seconds to call
         * startForeground() and kills it otherwise. A notification action reaches
         * the service through startService(), and a sticky restart delivers a null
         * intent, so the foreground call cannot be tied to ACTION_START: it has to
         * happen on the first onStartCommand whatever action arrives, and must not
         * repeat on the ones after it (which would repost the generic
         * "Transfer running" notification over the live one).
         */
        private var isForeground = false

        override fun onCreate() {
            super.onCreate()
            createNotificationChannel()
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (!isForeground) {
                startForegroundNow()
                acquireLocks()
                observeProgress()
            }

            when (intent?.action) {
                ACTION_PAUSE -> transferManagerInstance?.pauseTransfer()
                // Resume is driven from here because the notification may be the
                // only thing on screen: the service has no Activity to ask for the
                // URI, name and size, so TransferManager reuses what it captured at
                // startTransfer().
                ACTION_RESUME -> transferManagerInstance?.resumeActiveTransfer()
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
        isForeground = true
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
        isForeground = false
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
                                status = progress.status,
                                etaSeconds = progress.etaSeconds
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
        status: TransferStatus = TransferStatus.TRANSFERRING,
        etaSeconds: Long = 0L
    ): Notification {
        val speedMb = speedBytes / (1024.0 * 1024.0)
        val transMb = transferredBytes / (1024.0 * 1024.0)
        val totMb = totalBytes / (1024.0 * 1024.0)

        // Time left is only meaningful with a measured rate; showing "0s left"
        // while the first chunk is still uploading is worse than showing none.
        val etaSuffix = if (etaSeconds > 0 && speedBytes > 0) {
            " · " + getString(R.string.notify_eta_suffix, formatEta(etaSeconds))
        } else {
            ""
        }

        val statusText = when (status) {
            TransferStatus.PAUSED -> getString(
                R.string.notify_paused_detail,
                String.format(Locale.US, "%.1f", transMb),
                String.format(Locale.US, "%.1f", totMb)
            )
            TransferStatus.CONNECTING -> getString(R.string.notify_connecting)
            else -> String.format(
                Locale.US,
                "%.1f / %.1f MB (%.1f MB/s)",
                transMb,
                totMb,
                speedMb
            ) + etaSuffix
        }

        val pActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (filename.isNotEmpty()) filename else getString(R.string.notify_transfer_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, status == TransferStatus.PAUSED)
            .setContentIntent(pActivityIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Actions are what make the notification usable while the phone is in a
        // pocket: the app may not even be in recents. Pause flips to Resume
        // once the transfer is actually paused, and Cancel is always offered
        // because a transfer nobody can stop is a transfer nobody can trust.
        val paused = status == TransferStatus.PAUSED
        builder.addAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            getString(if (paused) R.string.action_resume else R.string.action_pause),
            actionPendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, REQUEST_PAUSE)
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_cancel),
            actionPendingIntent(ACTION_CANCEL, REQUEST_CANCEL)
        )

        return builder.build()
    }

    /**
     * A PendingIntent that re-enters this service with [action].
     *
     * Request codes are fixed per action, not per call: with
     * FLAG_UPDATE_CURRENT a stable code means one PendingIntent per action,
     * reused by every rebuild of the notification. A code derived from the
     * action string alone would collide with the receive side's intents.
     */
    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TransferForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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