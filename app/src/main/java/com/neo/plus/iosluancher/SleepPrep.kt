package com.neo.plus.iosluancher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets the night summary ready BEFORE the wake-up call.
 *
 * Five minutes before the wake-up time the assistant goes through the night log, writes the summary and makes the voice for
 * it, so when the call is answered it only has to speak. If something new arrives after that (a message in the last minutes),
 * the summary and voice are redone in the background, so what is read out is always complete.
 * If the preparation never ran (the phone was off, the alarm was missed), answering the call still builds the summary then, as before.
 */
object SleepPrep {
    const val LEAD_MS = 5 * 60_000L
    const val ACTION_PREP = "com.neo.plus.iosluancher.SLEEP_PREP"

    private val preparing = AtomicBoolean(false)
    @Volatile private var dirty = false
    private val lock = Any()

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 5, Intent(ctx, SleepPrepReceiver::class.java).setAction(ACTION_PREP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Plans the preparation [LEAD_MS] before the wake-up time. Inside the last 5 minutes already: starts at once. */
    fun schedule(ctx: Context, wakeAt: Long) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val at = wakeAt - LEAD_MS
        if (at <= now) {
            if (wakeAt > now + 20_000L) SleepMode.io.execute { prepare(app) }
            return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(app)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (e2: Exception) { }
        }
    }

    fun cancel(ctx: Context) {
        try { (ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(ctx.applicationContext)) } catch (e: Exception) { }
    }

    private fun logCount(app: Context): Int =
        try { JSONArray(SleepMode.prefs(app).getString("log", "[]")).length() } catch (e: Exception) { 0 }

    /** Writes the summary and makes its voice ahead of time. Safe to call again: a second call while one is running just asks it to run once more. */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        if (!SleepMode.isOn(app)) return
        if (!preparing.compareAndSet(false, true)) { dirty = true; return }
        var wl: PowerManager.WakeLock? = null
        try {
            try {
                wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neo:sleepprep")
                wl.acquire(3 * 60_000L)
            } catch (e: Exception) { }
            do {
                dirty = false
                var text = ""
                synchronized(lock) {
                    val n = logCount(app)
                    text = SleepMode.report(app)
                    SleepMode.prefs(app).edit().putInt("prep_n", n).putLong("prep_at", System.currentTimeMillis()).apply()
                }
                // The voice, made now and kept, so the call starts speaking at once (same text the call will speak)
                if (!SleepMode.freeVoice(app)) Speaker.prewarmSpeech(app, SpokenText.clean(text))
            } while (dirty && SleepMode.isOn(app))
        } catch (e: Exception) {
        } finally {
            preparing.set(false)
            try { if (wl?.isHeld == true) wl.release() } catch (e: Exception) { }
        }
    }

    /** The summary for the call: the prepared one if nothing new came in since, otherwise a fresh one. */
    fun ready(ctx: Context): String {
        val app = ctx.applicationContext
        synchronized(lock) {   // waits for a preparation that is just finishing
            val cached = SleepMode.lastReport(app)
            val n = SleepMode.prefs(app).getInt("prep_n", -1)
            if (cached.isNotBlank() && n >= 0 && n == logCount(app)) return cached
            return SleepMode.report(app)
        }
    }

    /** Called whenever a new line is added to the night log: after the preparation ran, the summary is redone in the background. */
    fun onLogChanged(app: Context) {
        if (SleepMode.prefs(app).getInt("prep_n", -1) >= 0) SleepMode.io.execute { prepare(app) }
    }
}

class SleepPrepReceiver : BroadcastReceiver() {
    // The sleep-mode service keeps the app alive all night, so the work can run past this broadcast
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        SleepMode.io.execute { SleepPrep.prepare(app) }
    }
}
