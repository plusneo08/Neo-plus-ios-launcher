package com.neo.plus.iosluancher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "waiting for your OK" list. Anything the assistant wants to send on its own initiative (follow-ups, promotions,
 * reminders, anything to several people) is parked here as a notification with Send / Skip, and only goes out after the user taps Send.
 * Works for every outgoing channel the assistant has: WhatsApp, WhatsApp Business, Messenger / Facebook, SMS, email and posts.
 * Money never goes through here: send_money has its own authorisation screen.
 */
object Approvals {
    private const val PREF = "neo_approvals"
    private const val CH = "neo_approvals"
    const val EXTRA_ID = "approval_id"
    const val EXTRA_DO = "approval_do"

    /** The app list from the last time something was queued (needed to run a "post" later from a notification button). */
    @Volatile var lastApps: List<AppEntry> = emptyList()

    /** When this many items are waiting, the assistant calls the user once (not for every item). Urgent items call at once. */
    const val CALL_AT = 5
    private const val KEY_CALLED = "batch_called"

    val GATED = setOf("message", "whatsapp", "messenger", "email", "post", "share_file", "reply_message")

    class Item(val id: String, val action: String, val args: JSONObject, val summary: String, val at: Long)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context): List<Item> {
        val arr = try { JSONArray(prefs(ctx).getString("items", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Item(o.optString("id"), o.optString("action"), o.optJSONObject("args") ?: JSONObject(), o.optString("summary"), o.optLong("at")))
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("id", it.id).put("action", it.action).put("args", it.args).put("summary", it.summary).put("at", it.at))
        val e = prefs(ctx).edit().putString("items", arr.toString())
        // Once the list drops below the limit, the next time it reaches the limit the user is called again.
        if (items.size < CALL_AT) e.putBoolean(KEY_CALLED, false)
        e.apply()
    }

    fun summarize(action: String, a: JSONObject): String {
        val to = a.optString("to")
        val text = a.optString("text").ifBlank { a.optString("body") }.ifBlank { a.optString("message") }
        val how = when (action) {
            "whatsapp" -> if (a.optBoolean("business")) "WhatsApp Business" else "WhatsApp"
            "messenger" -> "Messenger"
            "email" -> "Email"
            "message" -> "SMS"
            "post" -> "Post on " + a.optString("app").ifBlank { "your app" }
            else -> action
        }
        val head = if (action == "post" || to.isBlank()) how else how + " to " + to
        return head + ": " + text.take(160)
    }

    fun add(ctx: Context, action: String, a: JSONObject, apps: List<AppEntry>): Item {
        if (apps.isNotEmpty()) lastApps = apps
        val urgent = a.optBoolean("urgent", false)
        val item = Item(UUID.randomUUID().toString().take(8), action, a, (if (urgent) "URGENT · " else "") + summarize(action, a), System.currentTimeMillis())
        val items = list(ctx).toMutableList()
        items.add(item)
        while (items.size > 50) items.removeAt(0)
        save(ctx, items)
        notifyItem(ctx, item)
        try {
            if (urgent) {
                ApprovalCall.ring(ctx, true)
            } else if (items.size >= CALL_AT && !prefs(ctx).getBoolean(KEY_CALLED, false)) {
                prefs(ctx).edit().putBoolean(KEY_CALLED, true).apply()
                ApprovalCall.ring(ctx, false)
            }
        } catch (e: Exception) { }
        return item
    }

    private fun notifyItem(ctx: Context, item: Item) {
        try {
            val app = ctx.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Waiting for your OK", NotificationManager.IMPORTANCE_HIGH))
            }
            fun pi(what: String, code: Int): PendingIntent {
                val i = Intent(app, ApprovalReceiver::class.java)
                    .setAction("neo.approval." + what + "." + item.id)
                    .putExtra(EXTRA_ID, item.id)
                    .putExtra(EXTRA_DO, what)
                return PendingIntent.getBroadcast(app, item.id.hashCode() + code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Waiting for your OK")
                .setContentText(item.summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.summary))
                .addAction(0, "Send", pi("send", 1))
                .addAction(0, "Skip", pi("skip", 2))
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(item.id.hashCode(), n)
        } catch (e: Exception) { }
    }

    private fun cancelNotification(ctx: Context, id: String) {
        try { ctx.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode()) } catch (e: Exception) { }
    }

    /** The user decided: Send runs the saved action, Skip drops it. */
    fun decide(ctx: Context, id: String, send: Boolean): String {
        val app = ctx.applicationContext
        val item = list(app).firstOrNull { it.id == id } ?: return "That one was already handled"
        save(app, list(app).filter { it.id != id })
        cancelNotification(app, id)
        if (!send) return "Skipped: " + item.summary
        val r = PhoneActions.run(app, item.action, JSONObject(item.args.toString()), lastApps)
        return if (r.ok) "Sent: " + item.summary else "Couldn’t send: " + item.summary + " (" + r.note + ")"
    }

    /** "approvals" action: list, approve_all, skip_all. */
    fun handle(ctx: Context, op: String): String {
        val app = ctx.applicationContext
        val items = list(app)
        return when (op.lowercase()) {
            "approve_all" -> {
                if (items.isEmpty()) return "Nothing is waiting for your OK"
                val ids = items.map { it.id }
                Thread {
                    for (id in ids) {
                        try { decide(app, id, true) } catch (e: Exception) { }
                        try { Thread.sleep(4000) } catch (e: InterruptedException) { break }
                    }
                }.start()
                "Sending ${ids.size} approved item(s) one by one"
            }
            "skip_all" -> {
                if (items.isEmpty()) return "Nothing is waiting for your OK"
                for (it in items) cancelNotification(app, it.id)
                save(app, emptyList())
                "Skipped ${items.size} waiting item(s)"
            }
            else -> if (items.isEmpty()) "Nothing is waiting for your OK"
            else items.mapIndexed { i, it -> "${i + 1}. " + it.summary }.joinToString("\n")
        }
    }
}

/** Handles the Send / Skip buttons on the "Waiting for your OK" notifications. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Approvals.EXTRA_ID) ?: return
        val send = intent.getStringExtra(Approvals.EXTRA_DO) == "send"
        val app = context.applicationContext
        val pending = goAsync()
        val done = AtomicBoolean(false)
        fun finishOnce() { if (done.compareAndSet(false, true)) { try { pending.finish() } catch (e: Exception) { } } }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, 8000)
        Thread {
            try { Approvals.decide(app, id, send) } catch (e: Exception) { } finally { finishOnce() }
        }.start()
    }
}
