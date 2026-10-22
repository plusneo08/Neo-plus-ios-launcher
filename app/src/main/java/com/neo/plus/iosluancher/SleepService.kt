package com.neo.plus.iosluancher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Keeps the app process alive (and protected from the phone's battery cleaner) while sleep mode is on. */
class SleepService : Service() {

    companion object {
        private const val CH = "neo_sleep_keep"
        private const val ID = 4713

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, SleepService::class.java)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, SleepService::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Sleep mode", NotificationManager.IMPORTANCE_LOW))
        }
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sleep mode is on")
            .setContentText("Neo Plus is answering your messages")
            .setOngoing(true)
            .build()
        try { startForeground(ID, n) } catch (e: Exception) { }
        if (!SleepMode.isOn(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
}
