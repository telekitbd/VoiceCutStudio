package com.voicecut.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that keeps the process (and the CPU) alive while a job runs, even if the app is minimised. */
class KeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = KeepAlive.notification(this, KeepAlive.lastText, KeepAlive.lastProgress)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceCut:job")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val ID = 7
    }
}

/** Counts running jobs and runs the service while at least one is active. */
object KeepAlive {
    private const val CHANNEL = "work"
    private var count = 0
    private var lastPost = 0L
    @Volatile var lastText = "Working in the background…"
    @Volatile var lastProgress: Float? = null

    /** The ongoing notification, with a progress bar (a moving one when the progress is unknown). */
    fun notification(ctx: Context, text: String, progress: Float?): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("VoiceCut Studio")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (progress != null) b.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    /** Called whenever a job reports progress; refreshes the notification (at most twice a second). */
    @Synchronized
    fun progress(ctx: Context, text: String, progress: Float?) {
        lastText = text; lastProgress = progress
        if (count == 0) return
        val now = System.currentTimeMillis()
        if (now - lastPost < 500 && progress != 1f) return
        lastPost = now
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(KeepAliveService.ID, notification(ctx, text, progress))
        }
    }

    @Synchronized
    fun begin(ctx: Context) {
        if (count++ == 0) runCatching {
            lastText = "Working in the background…"; lastProgress = null
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }
    }

    @Synchronized
    fun end(ctx: Context) {
        if (count > 0 && --count == 0) runCatching {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
