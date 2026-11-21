package com.neo.plus.ioslauncher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Legacy communication event engine. User-facing scheduled modes are retired;
 * CommunicationControl owns the single persistent on/off state and catch-up report.
 */
object SleepMode {
    internal const val PREFS = "neo_sleep"
    internal const val CHANNEL = "neo_wake_call"
    internal const val NOTIF_ID = 4712
    const val ACTION_WAKE = "com.neo.plus.ioslauncher.SLEEP_WAKE"
    internal const val MAX_LOG = 1000
    internal const val MAX_REPLIES = 60
    internal const val MAX_RETRIES = 5
    internal const val MAX_PER_CHAT = 8          // automatic replies per conversation per night
    internal const val SWEEP_MS = 3 * 60 * 60_000L   // at start, notifications already waiting from the last hour are answered too

    internal val io = Executors.newFixedThreadPool(6)   // several chats are answered at the same time
    internal val seen = HashMap<String, Int>()
    internal val sent = ArrayList<String>()

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Set by the plain-words parser just before it starts sleep mode, so the activity needs no change in the AI tool code. */
    @Volatile internal var pendingActivity = ""
    @Volatile internal var pendingAt = 0L

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false) || CommunicationControl.isEnabled(ctx)

    /** True = the wake-up call speaks with the phone's free voice instead of the Gemini voice (saves API quota). */
    fun freeVoice(ctx: Context) = prefs(ctx.applicationContext).getBoolean("free_voice", false)

    fun setFreeVoice(ctx: Context, on: Boolean): String {
        prefs(ctx.applicationContext).edit().putBoolean("free_voice", on).apply()
        return if (on) "Okay, the wake-up call will use the phone’s free voice, so speech uses no Gemini quota."
        else "Okay, the wake-up call will use the natural Gemini voice."
    }

    /* ---------------- money detection ---------------- */

    internal val moneyPkgs = listOf(
        "airtel", "tnm", "mpamba", "nbm", "standardbank", "fdh", "ecobank", "natbank", "cdh", "nbs", "paypal", "wise",
        "mukuru", "worldremit", "westernunion", "moneygram", "venmo", "binance", "revolut", "mpesa", "momo", "cashapp"
    )

    internal val moneyWords = Regex(
        "ndalama|kwacha|\\bmk ?\\d|\\bmwk\\b|mpamba|airtel ?money|ngongole|bwereka|bwereke|kongola|kongoleni|bweza|malipiro|" +
            "kulipira|lipira|\\bpay(?:ment|ments|ing)?\\b|\\bmoney\\b|\\bcash\\b|\\bloan\\b|\\bborrow|\\bdeposit|\\bwithdraw|" +
            "\\btransfer|\\binvoice|\\bbalance\\b|\\bairtime\\b|\\bk ?\\d{3,}|\\$ ?\\d|\\bbank\\b|\\baccount\\b|\\bwallet\\b|\\bpin\\b",
        RegexOption.IGNORE_CASE
    )

    internal fun looksLikeMoney(pkg: String, title: String, text: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        if (p.split('.').any { seg -> moneyPkgs.any { seg == it || seg.startsWith(it) } }) return true
        return moneyWords.containsMatchIn("$title $text")
    }

    /* ---------------- start / stop ---------------- */

    /** [callOnly] = just the wake-up call at that time (the phone rings like an incoming call and tells what happened), no answering of messages. */
    @Deprecated("Scheduled sleep/activity modes were replaced by CommunicationControl")
    fun start(ctx: Context, hour: Int, minute: Int, activity: String = "", callOnly: Boolean = false): String {
        return "Scheduled communication modes are no longer available. Ask me to handle your messages and calls until you say “I’m back”."
    }

    fun stop(ctx: Context): String {
        val app = ctx.applicationContext
        val wasOn = isOn(app)
        finish(app)
        return if (wasOn) "Sleep mode is off. Say “what did I miss” for the night summary." else "Sleep mode was not on."
    }

    internal fun finish(app: Context) {
        prefs(app).edit().putBoolean("on", false).putInt("retries", 0).apply()
        WakeRinger.stop()
        SleepService.stop(app)
        cancelAlarm(app)
        try { app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (e: Exception) { }
    }

    fun answered(ctx: Context) = finish(ctx.applicationContext)

    /* ---------------- the wake-up alarm and the "call" ---------------- */

    internal fun wakeIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, SleepWakeReceiver::class.java).setAction(ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), wakeIntent(ctx))
    }

    internal fun cancelAlarm(ctx: Context) {
        SleepPrep.cancel(ctx)
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakeIntent(ctx)) } catch (e: Exception) { }
    }

    /** After a restart the alarm is gone: put it back, or ring at once if the wake-up time has already passed. */
    fun rearm(ctx: Context) {
        val app = ctx.applicationContext
        // Legacy alarm-based modes are retired. Keep only the user-controlled communication service.
        cancelAlarm(app)
        prefs(app).edit().putBoolean("on", false).putInt("retries", 0).apply()
        if (CommunicationControl.isEnabled(app)) SleepService.start(app) else SleepService.stop(app)
    }
