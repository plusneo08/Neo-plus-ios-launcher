package com.neo.plus.iosluancher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the assistant working while other apps are open or the screen is off.
 *
 * While at least one request is running, this foreground service keeps the app process alive (Android would otherwise freeze or
 * kill a launcher that is no longer on screen) and holds a CPU wake lock. The notification says what the assistant is doing.
 * When a request that took a while is finished, a second notification tells the user the result.
 */
class TaskService : Service() {
    private var wake: PowerManager.WakeLock? = null

    companion object {
        private const val CH = "neo_task_running"
        private const val CH_DONE = "neo_task_done"
        private const val ID = 4720
        private const val ID_DONE = 4721
        private val active = AtomicInteger(0)
        @Volatile private var label = "Working on your request"

        fun begin(ctx: Context, what: String) {
            label = what.trim().replace("\n", " ").take(70).ifBlank { "Working on your request" }
            active.incrementAndGet()
            try { ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, TaskService::class.java)) } catch (e: Exception) { }
        }

        fun end(ctx: Context) {
            if (active.updateAndGet { maxOf(0, it - 1) } == 0) {
                try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, TaskService::class.java)) } catch (e: Exception) { }
            }
        }

        /** "Neo Plus finished: ..." for work that ran in the background. */
        fun notifyDone(ctx: Context, text: String) {
            try {
                val app = ctx.applicationContext
                val nm = app.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_DONE) == null) {
                    nm.createNotificationChannel(NotificationChannel(CH_DONE, "Assistant results", NotificationManager.IMPORTANCE_DEFAULT))
                }
                val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
                val pi = PendingIntent.getActivity(app, 0, open ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CH_DONE) else @Suppress("DEPRECATION") Notification.Builder(app)
                val n = b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("Neo Plus finished")
                    .setContentText(text.take(120))
                    .setStyle(Notification.BigTextStyle().bigText(text.take(600)))
                    .setContentIntent(pi).setAutoCancel(true).build()
                nm.notify(ID_DONE, n)
            } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Assistant working", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Neo Plus is working")
            .setContentText(label)
            .setOngoing(true)
            .build()
        try { startForeground(ID, n) } catch (e: Exception) { }
        try {
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neoplus:task").apply {
                    setReferenceCounted(false)
                    acquire(20 * 60_000L)
                }
            }
        } catch (e: Exception) { }
        if (active.get() == 0) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        wake = null
        super.onDestroy()
    }
}
