package com.neo.plus.ioslauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Sleep mode that turns itself on.
 *
 * When the screen goes off a timer starts (30 minutes by default). Any screen-on cancels it. If it runs out while the
 * screen is still off, it is night time (from 21:00 until the wake-up time) and no other mode is on, sleep mode starts
 * by itself with the saved wake-up time (07:00 by default). From then on it is exactly the same as saying
 * "sleep mode, call me at 7": messages are answered, everything is logged, and at the wake-up time the assistant phones
 * you, reads the night summary, greets you and talks with you.
 *
 * Voice commands: "auto sleep off", "auto sleep on", "auto sleep wake me at 6:30", "auto sleep after 45 minutes", "auto sleep status".
 */
object AutoSleep {
    private const val PREFS = "neo_autosleep"
    const val ACTION = "com.neo.plus.ioslauncher.AUTO_SLEEP_CHECK"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = false // retired: communication handling is explicitly user-controlled
    private fun idleMin(ctx: Context) = p(ctx).getInt("idle_min", 30).coerceIn(5, 180)
    private fun fromHour(ctx: Context) = p(ctx).getInt("from_hour", 21).coerceIn(0, 23)
    private fun wakeHour(ctx: Context) = p(ctx).getInt("wake_hour", 7).coerceIn(0, 23)
    private fun wakeMinute(ctx: Context) = p(ctx).getInt("wake_minute", 0).coerceIn(0, 59)

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 9, Intent(ctx, AutoSleepReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** The screen turned off: start counting. Safe to call from several places (the same timer is just set again). */
    fun screenOff(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app) || SleepMode.isOn(app)) return
        val at = System.currentTimeMillis() + idleMin(app) * 60_000L
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(app)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (e2: Exception) { }
        }
    }

    /** The screen turned on: the phone is in use, so the countdown is cancelled. */
    fun screenOn(ctx: Context) {
        val app = ctx.applicationContext
        try { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(app)) } catch (e: Exception) { }
    }

    private var lastUnlockNote = 0L

    /** You unlocked the phone: cancels the countdown, and if sleep mode is on it goes into the night summary. */
    fun userPresent(ctx: Context) {
        val app = ctx.applicationContext
        screenOn(app)
        val now = System.currentTimeMillis()
        if (SleepMode.isOn(app) && now - lastUnlockNote > 10 * 60_000L) {
            lastUnlockNote = now
            SleepHooks.note(app, "NOTE", "Phone", "You", "You picked up and unlocked the phone")
        }
    }

    private fun inNightWindow(ctx: Context): Boolean {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val from = fromHour(ctx) * 60
        val wake = wakeHour(ctx) * 60 + wakeMinute(ctx)
        return if (from > wake) (m >= from || m < wake) else (m in from until wake)
    }

    /** The countdown ran out. Starts sleep mode only if everything still fits. */
    fun check(ctx: Context) {
        // Legacy scheduled sleep/activity automation is intentionally disabled.
        return
        @Suppress("UNREACHABLE_CODE")
        val app = ctx.applicationContext
        if (!enabled(app) || SleepMode.isOn(app)) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return                       // the screen is on again: in use
        if (!inNightWindow(app)) return                    // only at night, so a phone left on a desk at noon is not "sleep"
        try {
            val mode = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return   // a call is going on
        } catch (e: Exception) { }
        // Full sleep mode needs notification access and a Gemini key. Without them it still phones you in the morning (wake-up call only).
        val listener = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        val key = AssistantConfig.load(app).key.isNotBlank()
        val callOnly = !(listener && key)
        SleepMode.start(app, wakeHour(app), wakeMinute(app), "", callOnly)
        if (SleepMode.isOn(app)) {
            SleepMode.prefs(app).edit().putString("mode", "sleep").putBoolean("auto", true).apply()
        }
    }

    fun status(ctx: Context): String {
        val app = ctx.applicationContext
        val wake = String.format(Locale.US, "%02d:%02d", wakeHour(app), wakeMinute(app))
        return if (enabled(app)) "Auto sleep is on: after ${idleMin(app)} minutes with the screen unused, between ${fromHour(app)}:00 and $wake, I start sleep mode and phone you at $wake."
        else "Auto sleep is off."
    }

    /** The `auto_sleep` action: {"on":false}, {"on":true,"hour":6,"minute":30}, {"minutes":45}, {"do":"status"}. */
    fun handle(ctx: Context, a: JSONObject): String {
        val app = ctx.applicationContext
        if (a.optString("do").equals("status", true)) return status(app)
        val e = p(app).edit()
        if (a.has("hour")) e.putInt("wake_hour", a.optInt("hour", 7).coerceIn(0, 23)).putInt("wake_minute", a.optInt("minute", 0).coerceIn(0, 59))
        if (a.has("minutes")) e.putInt("idle_min", a.optInt("minutes", 30).coerceIn(5, 180))
        if (a.has("on")) e.putBoolean("on", a.optBoolean("on", true))
        e.apply()
        if (!enabled(app)) screenOn(app)
        return if (enabled(app)) "Okay. " + status(app) else "Okay, auto sleep is off. You can still say “sleep mode” yourself."
    }
}

class AutoSleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        SleepMode.io.execute { try { AutoSleep.check(app) } catch (e: Exception) { } finally { pending.finish() } }
    }
}

/** Understands "auto sleep off", "auto sleep on", "auto sleep wake me at 6:30", "auto sleep after 45 minutes", "auto sleep status". */
object AutoSleepCmd {
    private val I = RegexOption.IGNORE_CASE
    private val nameRe = Regex("\\bauto(?:matic(?:ally)?)?[- ]?sleep(?:ing)?\\b|\\bsleep (?:mode )?automatic(?:ally)?\\b", I)
    private val offRe = Regex("\\b(?:off|stop|disable|cancel|turn off|switch off)\\b", I)
    private val statusRe = Regex("\\bstatus\\b|\\bis it on\\b|\\bwhat time\\b", I)
    private val timeRe = Regex("\\b(?:at|until|till|by|call me|wake me)\\b[^0-9]{0,15}(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", I)
    private val afterRe = Regex("\\b(?:after|when idle for|idle for)\\s+(\\d{1,3})\\s*(?:minutes?|mins?)\\b", I)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        if (!nameRe.containsMatchIn(t)) return null
        val a = JSONObject()
        if (statusRe.containsMatchIn(t)) return LocalPlan("", listOf("auto_sleep" to a.put("do", "status")))
        if (offRe.containsMatchIn(t)) return LocalPlan("", listOf("auto_sleep" to a.put("on", false)))
        a.put("on", true)
        afterRe.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { a.put("minutes", it) }
        timeRe.find(t)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) a.put("hour", h).put("minute", min)
        }
        return LocalPlan("", listOf("auto_sleep" to a))
    }
}
