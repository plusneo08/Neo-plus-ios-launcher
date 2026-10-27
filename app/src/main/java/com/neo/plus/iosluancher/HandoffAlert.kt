package com.neo.plus.iosluancher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Loud notification for the moment a website task needs you (CAPTCHA, a code, a payment to confirm). It is shown when the
 * task pauses, repeated while it waits, and removed as soon as you have finished the step or the task stops.
 */
object HandoffAlert {
    private const val CH = "neo_handoff_alert"
    private const val ID = 7421

    fun show(ctx: Context, reason: String, minutesLeft: Int, repeat: Boolean) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Website task needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
                })
            }
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = "$reason. The page is open and waiting: finish this step and the task carries on by itself. About $minutesLeft min left."
            nm.notify(ID, NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(if (repeat) "Still waiting for you" else "Your website task needs you")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(false).build())
        } catch (e: Exception) { }
    }

    fun clear(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (e: Exception) { }
    }
}
