package io.github.sherazi5110.cloudstream

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while downloads run (the work itself is in [Downloads]) and shows their
 * progress in a notification. It stops by itself when nothing is left to download.
 */
class DownloadService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var off: (() -> Unit)? = null
    private var pending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channel(this)
        val n = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        off = Downloads.onChange {
            if (pending) return@onChange
            pending = true
            main.postDelayed({ pending = false; refresh() }, 700)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) Downloads.active().forEach { Downloads.pause(it.id) }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (Downloads.active().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun build(): Notification {
        val active = Downloads.active()
        val current = active.firstOrNull { it.state == Downloads.State.RUNNING } ?: active.firstOrNull()
        val title = current?.let { listOfNotNull(it.title, it.label).joinToString(" · ") } ?: "Downloads"
        val progress = current?.progress ?: -1.0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (active.size > 1) "${active.size} downloads" else if (progress >= 0) "${(progress * 100).toInt()}%" else "Starting…")
            .setProgress(100, if (progress >= 0) (progress * 100).toInt() else 0, progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pause all", pause)
            .build()
    }

    override fun onDestroy() {
        running = false
        off?.invoke()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4201
        private const val ACTION_PAUSE_ALL = "pause_all"
        @Volatile private var running = false

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress of movies and episodes downloading" })
            }
        }

        /** Starts the service when downloads are running and it isn't. */
        fun ensure(context: Context) {
            if (running || Downloads.active().isEmpty()) return
            running = true
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { running = false; android.util.Log.w("downloads", "Couldn't start the download service: $it") }
        }
    }
}
