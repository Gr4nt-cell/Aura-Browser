package com.gr4nt.aurabrowser.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.gr4nt.aurabrowser.MainActivity
import com.gr4nt.aurabrowser.formatBytes

/** Keeps the process alive while transfers run and shows one aggregate notification. */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!Downloads.hasActive()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            notifyProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> Downloads.pauseAll()
            ACTION_RESUME_ALL -> Downloads.resumeAll()
        }
        ensureChannel()
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:downloads").apply { setReferenceCounted(false) }
        }
        wake?.acquire(30 * 60 * 1000L)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = Downloads.rows.count { it.state == JobState.Downloading }
        val speed = Downloads.totalSpeed()
        val pct = (Downloads.overallProgress() * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active > 0) "Downloading $active file${if (active == 1) "" else "s"}" else "Downloads queued")
            .setContentText(if (speed > 0) "${formatBytes(speed.toLong())}/s" else "Starting")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pause all", pause)
            .build()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 42
        const val ACTION_PAUSE_ALL = "com.gr4nt.aurabrowser.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.gr4nt.aurabrowser.RESUME_ALL"
    }
}
