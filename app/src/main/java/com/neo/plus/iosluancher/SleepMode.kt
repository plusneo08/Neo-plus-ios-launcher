package com.neo.plus.iosluancher

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
 * Sleep mode. While it is on, chat messages are answered for the user (saying that it is an assistant), money messages
 * are never answered, and everything is written to a log. At the wake-up time the phone "rings" with an incoming call
 * from the assistant, which reads the night summary out loud when answered.
 */
object SleepMode {
    internal const val PREFS = "neo_sleep"
    internal const val CHANNEL = "neo_wake_call"
    internal const val NOTIF_ID = 4712
    const val ACTION_WAKE = "com.neo.plus.iosluancher.SLEEP_WAKE"
    internal const val MAX_LOG = 200
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

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)

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

    fun start(ctx: Context, hour: Int, minute: Int, activity: String = ""): String {
        val app = ctx.applicationContext
        // What the user is doing instead of sleeping ("in a meeting", "driving"); blank = asleep. Kept short and on one line.
        val fromParser = if (System.currentTimeMillis() - pendingAt < 120_000L) pendingActivity else ""
        pendingActivity = ""
        val act = activity.ifBlank { fromParser }.replace(Regex("[\\r\\n\"]"), " ").trim().take(40)
        if (!NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
            app.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Turn on notification access for Neo Plus, then tell me again"
        }
        if (AssistantConfig.load(app).key.isBlank()) return "Couldn’t start sleep mode: add your Gemini key in the assistant settings first"
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        prefs(app).edit().putBoolean("on", true).putLong("wake_at", cal.timeInMillis).putLong("started", System.currentTimeMillis())
            .putString("activity", act).putString("log", "[]").putString("replied", "{}").putString("replied_n", "{}").putInt("retries", 0).putString("report", "").putInt("prep_n", -1).apply()
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
        if (act.isNotEmpty()) return "Okay. I’ll answer your messages as your assistant, saying you’re $act, except money ones, and call you $at.$warn"
        return "Good night. Sleep mode is on: I’ll answer your messages except money ones, and call you $at.$warn"
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
        if (!isOn(app)) return
        SleepService.start(app)
        val at = prefs(app).getLong("wake_at", 0L)
        if (at > System.currentTimeMillis()) { schedule(app, at); SleepPrep.schedule(app, at) } else ring(app)
    }

    fun ring(ctx: Context) {
        val app = ctx.applicationContext
        if (!isOn(app)) return
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Wake-up call", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)   // the ringing itself is played by WakeRinger on the alarm volume
            nm.createNotificationChannel(ch)
        }
        val open = Intent(app, WakeCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(app, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Neo Plus Assistant")
            .setContentText("Incoming call: your night summary")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, n)
        WakeRinger.start(app)
        try { app.startActivity(open) } catch (e: Exception) { }
    }

    /** Nobody answered for a minute: ring again in five minutes (up to five times), then give up quietly. */
    fun missedCall(ctx: Context) {
        val app = ctx.applicationContext
        WakeRinger.stop()
        val nm = app.getSystemService(NotificationManager::class.java)
        val tries = prefs(app).getInt("retries", 0)
        if (isOn(app) && tries < MAX_RETRIES) {
            prefs(app).edit().putInt("retries", tries + 1).apply()
            schedule(app, System.currentTimeMillis() + 5 * 60_000L)
            nm.cancel(NOTIF_ID)
        } else {
            prefs(app).edit().putBoolean("on", false).apply()
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Missed call: Neo Plus Assistant")
                .setContentText("Say “what did I miss” to hear your night summary.")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    /* ---------------- incoming notifications ---------------- */

    internal val skipCategories = setOf(
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM
    )

    /** True when sleep mode took the notification (so the orb must not ask to read it out). */
    fun onPosted(ctx: Context, sbn: StatusBarNotification): Boolean {
        val app = ctx.applicationContext
        if (!isOn(app)) return false
        if (sbn.packageName == app.packageName) return false
        val n = sbn.notification ?: return true
        val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skipFlags != 0) return true
        if (n.category in skipCategories) return true
        val ex = n.extras ?: return true
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return true

        val hash = (title + "|" + text).hashCode()
        synchronized(seen) {
            if (seen[sbn.key] == hash) return true
            seen[sbn.key] = hash
            if (seen.size > 300) seen.clear()
        }
        // Our own reply coming back as a notification update is not a new message
        synchronized(sent) { if (sent.any { it.length >= 8 && text.contains(it.take(40)) }) return true }

        val group = (Build.VERSION.SDK_INT >= 28 && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) ||
            !ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()
        val pm = app.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        val category = n.category.orEmpty()
        val who0 = title.ifBlank { label }
        // The last five messages of this chat (oldest first, newest last), from the notification and from what we saw earlier tonight
        if (NotifStore.convoLast(sbn.packageName, who0).isEmpty()) {
            conversationLines(ex, who0).dropLast(1).forEach { NotifStore.convoAdd(sbn.packageName, who0, it) }
        }
        NotifStore.convoAdd(sbn.packageName, who0, who0 + ": " + text)
        val history = NotifStore.convoLast(sbn.packageName, who0)
        io.execute {
            // Keep the CPU awake while the answer is fetched and sent, so the phone's idle mode cannot cut it off
            val wl = try {
                (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "neo:sleepreply").also { it.acquire(6 * 60_000L) }
            } catch (e: Exception) { null }
            try { handle(app, sbn.key, sbn.packageName, label, title, text, group, category, history) } catch (e: Exception) { }
            finally { try { if (wl?.isHeld == true) wl.release() } catch (e: Exception) { } }
        }
        return true
    }

    /** The messages a chat app put inside its own notification (MessagingStyle), as "Sender: text" lines. */
    @Suppress("DEPRECATION")
    internal fun conversationLines(ex: Bundle, who: String): List<String> {
        val out = ArrayList<String>()
        try {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { p ->
                val b = p as? Bundle ?: return@forEach
                val t = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (t.isBlank()) return@forEach
                var s = b.getCharSequence("sender")?.toString()?.trim().orEmpty()
                if (s.isBlank() && Build.VERSION.SDK_INT >= 28) {
                    s = (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString()?.trim().orEmpty()
                }
                out.add((if (s.isBlank()) "Neo" else s) + ": " + t)
            }
        } catch (e: Exception) { }
        return out.takeLast(5)
    }

}
