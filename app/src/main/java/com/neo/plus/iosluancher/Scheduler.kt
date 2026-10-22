package com.neo.plus.iosluancher

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Do X at a time I choose, then tell me what happened."
 *
 * The assistant calls the `schedule` action with a time and the action to run later. The job is saved, an exact
 * Android alarm is set, and when it fires the saved action runs through [PhoneActions.run] and the result
 * (done / failed + reason) is posted as a notification and kept in a short history the assistant can read back
 * ("what did you do?").
 *
 * Time fields (use ONE): "at":"HH:mm" (next time it is that clock time), optionally with "date":"yyyy-MM-dd";
 * or "in_minutes": 90. Optional "repeat":"daily".
 */
object Scheduler {
    private const val PREF = "neo_scheduler"
    private const val CH = "neo_scheduler"
    private const val MAX_JOBS = 50
    private const val MAX_HISTORY = 30

    /** Never run later: they move money, change who owns the phone, or would schedule themselves. */
    private val BLOCKED = setOf(
        "schedule", "schedules", "send_money", "money_setup", "release_device_owner", "power_off", "kiosk",
        "unlock", "forget", "time_lock", "sleep_mode", "queue", "approvals"
    )

    class Job(val id: String, val action: String, val args: JSONObject, val summary: String, val at: Long, val repeat: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun jobs(ctx: Context): List<Job> = try {
        val arr = JSONArray(prefs(ctx).getString("jobs", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Job(o.getString("id"), o.getString("action"), o.optJSONObject("args") ?: JSONObject(), o.optString("summary"), o.optLong("at"), o.optString("repeat"))
        }
    } catch (e: Exception) { emptyList() }

    private fun saveJobs(ctx: Context, list: List<Job>) {
        val arr = JSONArray()
        for (j in list) arr.put(JSONObject().put("id", j.id).put("action", j.action).put("args", j.args).put("summary", j.summary).put("at", j.at).put("repeat", j.repeat))
        prefs(ctx).edit().putString("jobs", arr.toString()).apply()
    }

    private fun history(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString("history", "[]")) } catch (e: Exception) { JSONArray() }

    private fun addHistory(ctx: Context, line: String) {
        val old = history(ctx)
        val out = JSONArray()
        val start = maxOf(0, old.length() - (MAX_HISTORY - 1))
        for (i in start until old.length()) out.put(old.get(i))
        out.put(JSONObject().put("t", System.currentTimeMillis()).put("text", line))
        prefs(ctx).edit().putString("history", out.toString()).apply()
    }

    private val fmt get() = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    /** Works out the first run time from the model's fields, or null when the time is missing / unreadable. */
    private fun firstRun(a: JSONObject): Long? {
        val now = System.currentTimeMillis()
        if (a.has("in_minutes")) {
            val m = a.optDouble("in_minutes", 0.0)
            return if (m > 0) now + (m * 60_000).toLong() else null
        }
        val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(a.optString("at")) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        val cal = Calendar.getInstance()
        val date = a.optString("date")
        if (date.isNotBlank()) {
            val d = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(date.trim()) ?: return null
            cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
        }
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) {
            if (date.isNotBlank()) return null // a date in the past
            cal.add(Calendar.DAY_OF_YEAR, 1)    // clock time already passed today -> tomorrow
        }
        return cal.timeInMillis
    }

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx.applicationContext, SchedulerReceiver::class.java).setAction("neo.schedule.fire.$id").putExtra("id", id)
        return PendingIntent.getBroadcast(ctx.applicationContext, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, j: Job) {
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, j.at, pending(ctx, j.id))
            else am.setExact(AlarmManager.RTC_WAKEUP, j.at, pending(ctx, j.id))
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, j.at, pending(ctx, j.id)) // exact alarms not allowed: still runs, maybe a little late
        }
    }

    private fun disarm(ctx: Context, id: String) {
        try { (ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id)) } catch (e: Exception) { }
    }

    /** The `schedule` action: {"do":"whatsapp","to":"Mum","text":"hi","at":"17:00"} */
    fun add(ctx: Context, a: JSONObject): String {
        val inner = a.optString("do")
        if (inner.isBlank()) return "Couldn’t schedule that: I need to know what to do"
        if (inner in BLOCKED) return "Couldn’t schedule that: “$inner” can’t be run later, only right now"
        val at = firstRun(a) ?: return "Couldn’t schedule that: I need a clear time, like 17:00 or in 30 minutes"
        val repeat = if (a.optString("repeat").equals("daily", true)) "daily" else ""
        val args = JSONObject(a.toString())
        for (k in listOf("do", "at", "date", "in_minutes", "repeat")) args.remove(k)
        val summary = Approvals.summarize(inner, args)
        val list = jobs(ctx).toMutableList()
        while (list.size >= MAX_JOBS) { disarm(ctx, list[0].id); list.removeAt(0) }
        val job = Job(UUID.randomUUID().toString().take(8), inner, args, summary, at, repeat)
        list.add(job)
        saveJobs(ctx, list)
        arm(ctx, job)
        return "Scheduled for ${fmt.format(at)}${if (repeat.isNotEmpty()) ", every day" else ""}: $summary. I’ll send you a notification with the result."
    }

    /** The `schedules` action: list, history, cancel (with "id" or "all"). */
    fun handle(ctx: Context, a: JSONObject): String {
        val list = jobs(ctx)
        return when (a.optString("do").lowercase()) {
            "cancel" -> {
                val id = a.optString("id")
                val gone = if (a.optBoolean("all") || id == "all") list else list.filter { it.id == id || it.summary.contains(id, true) && id.length > 2 }
                if (gone.isEmpty()) return "I couldn’t find that scheduled task"
                gone.forEach { disarm(ctx, it.id) }
                saveJobs(ctx, list - gone.toSet())
                "Cancelled ${gone.size} scheduled task(s)"
            }
            "history" -> {
                val h = history(ctx)
                if (h.length() == 0) "Nothing has run on a schedule yet"
                else (h.length() - 1 downTo maxOf(0, h.length() - 10)).joinToString("\n") {
                    val o = h.getJSONObject(it); fmt.format(o.optLong("t")) + " · " + o.optString("text")
                }
            }
            else -> if (list.isEmpty()) "Nothing is scheduled"
            else list.sortedBy { it.at }.joinToString("\n") { "${fmt.format(it.at)}${if (it.repeat.isNotEmpty()) " (daily)" else ""} · ${it.summary} [${it.id}]" }
        }
    }

    /** Called by the alarm: runs the job, reports the result, and re-arms a daily one. */
    fun fire(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val job = jobs(app).firstOrNull { it.id == id } ?: return
        val rest = jobs(app).filter { it.id != id }
        if (job.repeat == "daily") {
            val next = Job(job.id, job.action, job.args, job.summary, job.at + 24L * 3600_000L, job.repeat)
            saveJobs(app, rest + next); arm(app, next)
        } else saveJobs(app, rest)

        val wl = try {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "neo:schedule").also { it.acquire(45_000L) }
        } catch (e: Exception) { null }

        val apps = try { OrbBridge.apps() } catch (e: Exception) { Approvals.lastApps }
        val seq0 = TaskReports.seq
        val r = try { PhoneActions.run(app, job.action, JSONObject(job.args.toString()), apps) }
        catch (e: Exception) { ActionResult("Couldn’t do that (${e.javaClass.simpleName})", false, false) }
        val line = (if (r.ok) "Done: " else "Couldn’t do: ") + job.summary + (if (r.note.isNotBlank()) " — " + r.note.take(160) else "")
        addHistory(app, line)
        // A website task reports itself through the task feed (text + counts towards the call): do not text twice.
        if (TaskReports.seq == seq0) {
            report(app, id, if (r.ok) "Scheduled task done" else "Scheduled task failed", line)
            TaskReports.record(app, job.summary, r.note, r.ok, notify = false)
        }
        try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
    }

    /** After a restart alarms are gone: set them again. Tasks that were due while the phone was off are reported, not run late. */
    fun rearm(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val keep = mutableListOf<Job>()
        for (j in jobs(app)) {
            if (j.at > now) { keep.add(j); arm(app, j); continue }
            addHistory(app, "Missed (phone was off): " + j.summary)
            report(app, j.id, "Scheduled task missed", "The phone was off at ${fmt.format(j.at)}, so I did not run: ${j.summary}")
            if (j.repeat == "daily") {
                var t = j.at
                while (t <= now) t += 24L * 3600_000L
                val n = Job(j.id, j.action, j.args, j.summary, t, j.repeat); keep.add(n); arm(app, n)
            }
        }
        saveJobs(app, keep)
    }

    private fun report(ctx: Context, id: String, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Scheduled tasks", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(("sched" + id + System.currentTimeMillis()).hashCode(), NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
}

/** Fires when a scheduled time arrives. */
class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val done = AtomicBoolean(false)
        fun finishOnce() { if (done.compareAndSet(false, true)) { try { pending.finish() } catch (e: Exception) { } } }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, 9000)
        Thread {
            try { Scheduler.fire(app, id) } catch (e: Exception) { } finally { finishOnce() }
        }.start()
    }
}

/** Puts the scheduled tasks back after the phone restarts. */
class SchedulerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) try { Scheduler.rearm(context) } catch (e: Exception) { }
    }
}
