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
 * Sleep mode. While it is on, chat messages are answered for the user and everything is written to a log. At the wake-up time the phone "rings" with an incoming call
 * from the assistant, which reads the night summary out loud when answered.
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
    fun start(ctx: Context, hour: Int, minute: Int, activity: String = "", callOnly: Boolean = false): String {
        val app = ctx.applicationContext
        // What the user is doing instead of sleeping ("in a meeting", "driving"); blank = asleep. Kept short and on one line.
        val fromParser = if (System.currentTimeMillis() - pendingAt < 120_000L) pendingActivity else ""
        pendingActivity = ""
        val act = activity.ifBlank { fromParser }.replace(Regex("[\\r\\n\"]"), " ").trim().take(40)
        if (!callOnly && !NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
            app.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Turn on notification access for Neo Plus, then tell me again"
        }
        if (!callOnly && AssistantConfig.load(app).key.isBlank()) return "Couldn’t start sleep mode: add your Gemini key in the assistant settings first"
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        prefs(app).edit().putBoolean("on", true).putLong("wake_at", cal.timeInMillis).putLong("started", System.currentTimeMillis())
            .putString("activity", act).putBoolean("auto", false).putBoolean("calls_only", callOnly).putString("log", "[]").putString("replied", "{}").putString("replied_n", "{}").putInt("retries", 0).putString("report", "").putInt("prep_n", -1).apply()
        synchronized(seen) { seen.clear() }
        NotifStore.convoClear()
        // Capture what is already waiting in the notification shade, so nothing that arrived just before bed is missed
        io.execute {
            try {
                val now = System.currentTimeMillis()
                NeoNotificationService.instance?.activeNotifications?.sortedBy { it.postTime }
                    ?.filter { now - it.postTime <= SWEEP_MS }?.forEach { onPosted(app, it) }
            } catch (e: Exception) { }
        }
        schedule(app, cal.timeInMillis)
        SleepPrep.schedule(app, cal.timeInMillis)   // the summary and its voice are made 5 minutes before the call
        SleepService.start(app)
        var battery = false
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(app.packageName)) {
                battery = true
                app.safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) { }
        val day = if (cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "tomorrow " else ""
        val at = day + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cal.timeInMillis))
        val warn = (if (!Settings.canDrawOverlays(app)) " To make sure the call screen opens by itself, allow Display over other apps for Neo Plus." else "") +
            (if (battery) " Please allow “No restrictions” for Neo Plus so the phone does not stop me while you sleep." else "")
        if (callOnly) return "Okay, I’ll call you at $at to wake you up and tell you what happened.$warn"
        if (act.isNotEmpty()) return "Okay. I’ll answer your messages as your assistant, saying you’re $act, and call you $at.$warn"
        return "Good night. Sleep mode is on: I’ll answer your messages and call you $at.$warn"
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
