package com.neo.plus.iosluancher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reports on what the assistant did.
 *
 *  - A task finishes -> the user gets a text (a notification) with what was done.
 *  - When [CALL_AT] finished tasks are waiting to be reported, the assistant calls the user instead and reads the
 *    whole report out loud (same call screen as the approval call). The single texts are removed once the call is answered.
 *
 * Counted as a task: a website task, a website batch (one task for the whole batch), and a scheduled task.
 * Money transfers are not reported here (they have their own screens, and the amount should not show on a lock screen).
 */
object TaskReports {
    private const val PREF = "neo_task_reports"
    private const val CH = "neo_task_reports"
    private const val MAX_WAITING = 50
    private const val MAX_HISTORY = 30

    /** When this many finished tasks are waiting to be reported, the assistant calls the user (once per group). */
    const val CALL_AT = 5

    class Report(val id: String, val title: String, val detail: String, val ok: Boolean, val at: Long)

    @Volatile private var app: Context? = null

    /** Goes up by one every time a task is recorded (lets the scheduler see whether a task already reported itself). */
    @Volatile var seq = 0L
        private set

    /** Lets code that has no Context (the task feed) record a report. */
    fun attach(ctx: Context) { app = ctx.applicationContext }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun parse(raw: String?): List<Report> = try {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Report(o.optString("id"), o.optString("title"), o.optString("detail"), o.optBoolean("ok", true), o.optLong("at"))
        }
    } catch (e: Exception) { emptyList() }

    private fun toJson(items: List<Report>): String {
        val arr = JSONArray()
        for (r in items) arr.put(JSONObject().put("id", r.id).put("title", r.title).put("detail", r.detail).put("ok", r.ok).put("at", r.at))
        return arr.toString()
    }

    /** Finished tasks the user has not been told about yet (by a text that was cleared or by a call). */
    @Synchronized
    fun list(ctx: Context): List<Report> = parse(prefs(ctx).getString("waiting", "[]"))

    /** The last tasks that were reported, so the assistant can answer "what did you do?". */
    @Synchronized
    fun history(ctx: Context): List<Report> = parse(prefs(ctx).getString("history", "[]"))

    /**
     * A task just finished. [notify] = false when the caller already posts its own notification for it
     * (a scheduled task does); it still counts towards the call.
     */
    @Synchronized
    fun record(ctx: Context?, title: String, detail: String, ok: Boolean, notify: Boolean = true) {
        val a = ctx?.applicationContext ?: app ?: return
        seq++
        val report = Report(UUID.randomUUID().toString().take(8), title.trim().take(120), detail.trim().take(300), ok, System.currentTimeMillis())
        val items = (list(a) + report).takeLast(MAX_WAITING)
        val p = prefs(a)
        p.edit().putString("waiting", toJson(items)).apply()

        val callNow = items.size >= CALL_AT && !p.getBoolean("called", false)
        if (callNow) {
            p.edit().putBoolean("called", true).apply()
            try { ApprovalCall.ring(a, false) } catch (e: Exception) { if (notify) text(a, report); sms(a, report) }
        } else {
            if (notify) text(a, report)
            sms(a, report)
        }
    }

    /** The phone number the reports are also texted to (SMS). Empty = notification only. */
    fun smsNumber(ctx: Context): String = prefs(ctx).getString("sms_to", "") ?: ""

    /** The `report_number` action: {"number":"+2557..."} turns SMS reports on, an empty number turns them off. */
    fun setSms(ctx: Context, number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.isBlank()) {
            prefs(ctx).edit().remove("sms_to").apply()
            return "Task reports by SMS are off. You still get a notification for each task."
        }
        if (n.count { it.isDigit() } < 6) return "Couldn’t save that: it doesn’t look like a phone number"
        prefs(ctx).edit().putString("sms_to", n).apply()
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return "Okay, I’ll also text each task report to $n." + (if (ok) "" else " Allow SMS for Neo Plus in the phone’s settings, or the texts can’t be sent.")
    }

    /** The same report as a real SMS to the saved number (no spoken announcement, no notification). */
    private fun sms(ctx: Context, r: Report) {
        try {
            val to = smsNumber(ctx)
            if (to.isBlank()) return
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val sm: android.telephony.SmsManager =
                if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
                else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            val body = "Neo Plus: " + (if (r.ok) "done" else "didn’t finish") + ". " +
                (if (r.title.isNotBlank()) r.title.take(80) + ": " else "") + r.detail.take(220)
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(body), null, null)
        } catch (e: Exception) { }
    }

    /** The "text": one notification with what the assistant did. */
    private fun text(ctx: Context, r: Report) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Task reports", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val head = if (r.ok) "Task done" else "Task didn’t finish"
            val body = (if (r.title.isNotBlank()) r.title + "\n" else "") + r.detail
            nm.notify(r.id.hashCode(), NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Neo Plus: $head")
                .setContentText(r.detail.ifBlank { r.title })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }

    /** What the assistant says on the call: how many tasks, then one short line for each. */
    fun spoken(items: List<Report>): String {
        if (items.isEmpty()) return ""
        val bad = items.count { !it.ok }
        val head = "I finished ${items.size} ${if (items.size == 1) "task" else "tasks"}" +
            (if (bad > 0) ", and $bad of them didn’t work out" else "") + ". "
        val lines = items.mapIndexed { i, r ->
            "Number ${i + 1}. " + r.title.take(80).trimEnd('.', ':', ' ') + ". " +
                (if (!r.ok) "It didn’t finish. " else "") + r.detail.take(140).trimEnd() + (if (r.detail.endsWith(".")) "" else ".")
        }
        return head + lines.joinToString(" ") + " "
    }

    /** The call read the report out: remove the single texts, keep a short history, and allow the next call at the next group of five. */
    @Synchronized
    fun clear(ctx: Context) {
        val a = ctx.applicationContext
        val items = list(a)
        try {
            val nm = a.getSystemService(NotificationManager::class.java)
            for (r in items) nm?.cancel(r.id.hashCode())
        } catch (e: Exception) { }
        val hist = (history(a) + items).takeLast(MAX_HISTORY)
        prefs(a).edit().putString("history", toJson(hist)).putString("waiting", "[]").putBoolean("called", false).apply()
    }
}
