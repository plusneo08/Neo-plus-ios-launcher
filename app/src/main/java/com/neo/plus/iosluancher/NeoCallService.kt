package com.neo.plus.iosluancher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a call with the Neo Plus assistant alive while you use other apps. It shows the ongoing-call notification
 * (running timer, tap = back to the call, End call button) and, as a microphone service, lets the voice call keep listening
 * in the background.
 */
class NeoCallService : Service() {

    companion object {
        private const val CH = "neo_call_live"
        private const val ID = 7751
        private const val ACTION_END = "com.neo.plus.iosluancher.END_NEO_CALL"
        private const val EXTRA_START = "call_started_at"

        fun start(ctx: Context, startedAt: Long) {
            val app = ctx.applicationContext
            try { ContextCompat.startForegroundService(app, Intent(app, NeoCallService::class.java).putExtra(EXTRA_START, startedAt)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            try { app.stopService(Intent(app, NeoCallService::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // "End call" in the notification
        if (intent?.action == ACTION_END) {
            endNeoCall()
            stopSelf()
            return START_NOT_STICKY
        }
        val started = intent?.getLongExtra(EXTRA_START, 0L)?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Call with Neo Plus", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null); ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Tapping the notification brings the call screen back (the running call is picked up again, not restarted)
        val open = PendingIntent.getActivity(
            this, 6,
            Intent(this, WakeCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flag
        )
        val end = PendingIntent.getService(this, 7, Intent(this, NeoCallService::class.java).setAction(ACTION_END), flag)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call with Neo Plus")
            .setContentText("Tap to return to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true).setWhen(started).setShowWhen(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End call", end)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
        } catch (e: Exception) {
            try { startForeground(ID, n) } catch (e2: Exception) { }
        }
        return START_NOT_STICKY
    }
}
