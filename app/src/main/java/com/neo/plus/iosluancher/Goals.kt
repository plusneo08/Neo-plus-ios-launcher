package com.neo.plus.iosluancher

import android.app.AlarmManager
import android.app.KeyguardManager
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-running goals inside a connected app, e.g. "post this photo on Facebook Lite, count the likes, comments and shares,
 * answer every comment, and tell me when it has 500 likes".
 *
 * The user gives the goal once. The phone then checks in on it every N minutes (an exact alarm, like [Scheduler]):
 * each check-in runs [AppTask], which publishes the post (once), reads the numbers, and replies to comments that have no
 * reply yet. The user is told ONLY when: the goal is met, time ran out, or the phone cannot carry on without them
 * (it needs their OK, a login / CAPTCHA / PIN, the app is not connected, or three check-ins in a row failed).
 *
 * goal {"do":"start","instruction":"...","app":"Facebook Lite","every_minutes":30,"days":7}
 * goal {"do":"list"}   goal {"do":"stop","id":"..."|"all"}   goal {"do":"resume","id":"..."}
 *
 * The phone must be switched on and unlocked for a check-in to run (an accessibility service cannot unlock it); a check-in
 * that finds it locked or busy is moved a few minutes later instead of being lost.
 */
object Goals {
    private const val PREF = "neo_goals"
    private const val CH = "neo_goals"
    private const val MAX_GOALS = 5
    private const val CHECKIN_STEPS = 70
    private const val CHECKIN_MINUTES = 12
    private const val MAX_REPLIES_PER_CHECKIN = 10
    private val cycleLock = Any()
    private val fmt get() = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    data class Goal(
        val id: String, val instruction: String, val app: String, val everyMin: Int,
        val startedAt: Long, val deadlineAt: Long, val status: String,   // running | paused | done | expired | stopped
        val checkins: Int = 0, val fails: Int = 0, val skips: Int = 0, val posted: Boolean = false,
        val replied: Int = 0, val lastStats: String = "", val nextAt: Long = 0L, val log: List<String> = emptyList()
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    private fun all(ctx: Context): List<Goal> = try {
        val arr = JSONArray(prefs(ctx).getString("goals", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val lg = o.optJSONArray("log")
            Goal(o.getString("id"), o.optString("instruction"), o.optString("app"), o.optInt("everyMin", 30),
                o.optLong("startedAt"), o.optLong("deadlineAt"), o.optString("status", "running"),
                o.optInt("checkins"), o.optInt("fails"), o.optInt("skips"), o.optBoolean("posted"),
                o.optInt("replied"), o.optString("lastStats"), o.optLong("nextAt"),
                (0 until (lg?.length() ?: 0)).map { i -> lg!!.optString(i) })
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<Goal>) {
        val arr = JSONArray()
        for (g in list) arr.put(JSONObject().put("id", g.id).put("instruction", g.instruction).put("app", g.app)
            .put("everyMin", g.everyMin).put("startedAt", g.startedAt).put("deadlineAt", g.deadlineAt).put("status", g.status)
            .put("checkins", g.checkins).put("fails", g.fails).put("skips", g.skips).put("posted", g.posted)
            .put("replied", g.replied).put("lastStats", g.lastStats).put("nextAt", g.nextAt)
            .put("log", JSONArray(g.log.takeLast(12))))
        prefs(ctx).edit().putString("goals", arr.toString()).apply()
    }

    @Synchronized
    private fun update(ctx: Context, id: String, f: (Goal) -> Goal): Goal? {
        val list = all(ctx)
        val g = list.firstOrNull { it.id == id } ?: return null
        val n = f(g)
        saveAll(ctx, list.map { if (it.id == id) n else it })
        return n
    }

    /* ---------------- the `goal` action ---------------- */

    fun handle(ctx: Context, a: JSONObject, apps: List<AppEntry>): String {
        val app = ctx.applicationContext
        return when (a.optString("do").lowercase()) {
            "start" -> start(app, a, apps)
            "stop" -> {
                val id = a.optString("id")
                val hit = all(app).filter { it.status == "running" || it.status == "paused" }
                    .filter { id == "all" || id.isBlank() || it.id == id || it.instruction.contains(id, true) }
                if (hit.isEmpty()) return "I couldn’t find a running goal"
                hit.forEach { disarm(app, it.id); update(app, it.id) { g -> g.copy(status = "stopped") } }
                "Stopped ${hit.size} goal(s)"
            }
            "resume" -> {
                val id = a.optString("id")
                val g = all(app).firstOrNull { (it.id == id || id.isBlank()) && it.status == "paused" }
                    ?: return "I couldn’t find a paused goal"
                if (a.optString("note").isNotBlank()) update(app, g.id) { it.copy(log = it.log + "user: " + a.optString("note").take(80)) }
                update(app, g.id) { it.copy(status = "running", fails = 0, skips = 0) }
                arm(app, g.id, System.currentTimeMillis() + 60_000L)
                "Goal resumed, the next check-in starts in about a minute"
            }
            else -> {
                val list = all(app).filter { it.status == "running" || it.status == "paused" }
                if (list.isEmpty()) "No goals are running"
                else list.joinToString("\n") { g ->
                    "[${g.id}] ${g.status}: ${g.instruction.take(90)} (${g.app}) · ${g.checkins} check-ins, ${g.replied} replies" +
                        (if (g.lastStats.isNotBlank()) " · last: ${g.lastStats}" else "") +
                        (if (g.status == "running" && g.nextAt > 0) " · next ${fmt.format(g.nextAt)}" else "")
                }
            }
        }
    }

    private fun start(ctx: Context, a: JSONObject, apps: List<AppEntry>): String {
        val instruction = a.optString("instruction").trim()
        if (instruction.isEmpty()) return "Tell me the goal in plain words, including what counts as finished"
        val appName = a.optString("app").trim()
        if (appName.isEmpty()) return "Tell me which app the goal is in"
        val found = apps.findBest(appName) ?: return "No app called “$appName”"
        if (!AppAccess.allowed(ctx, found.pkg)) return AppAccess.blockedMessage(found.shownLabel)
        if (all(ctx).count { it.status == "running" || it.status == "paused" } >= MAX_GOALS) return "No more than $MAX_GOALS goals at once: stop one first"
        if (apps.isNotEmpty()) Approvals.lastApps = apps
        val every = a.optInt("every_minutes", 60).coerceIn(10, 720)
        val days = a.optInt("days", 7).coerceIn(1, 30)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().take(6)
        val g = Goal(id, instruction.take(600), found.shownLabel, every, now, now + days * 24L * 3600_000L, "running", nextAt = now + 60_000L)
        saveAll(ctx, all(ctx) + g)
        arm(ctx, id, g.nextAt)
        return "Goal started in ${found.shownLabel}. I’ll check on it every $every minutes for up to $days days. I’ll only message you when it is met, when time runs out, or if I get stuck and need you. Keep the phone on and unlocked (charging helps)."
    }

    /* ---------------- the check-in ---------------- */

    private fun checkinPrompt(g: Goal): String = buildString {
        append("LONG-RUNNING GOAL from the user: ").append(g.instruction).append("\n")
        append("This is check-in number ").append(g.checkins + 1).append(". Do these in the app now, in order:\n")
        if (!g.posted) append("1. If the goal says to publish a post, story, photo or text, publish it exactly as described, UNLESS your latest post on the profile already matches it (then do not post again).\n")
        else append("1. The post was already published by an earlier check-in: do NOT post it again.\n")
        append("2. Open that post and read its current numbers: likes or reactions, comments, shares. Write abbreviations as full numbers (1.2K = 1200).\n")
        append("3. Reply to EVERY comment on it that you have not replied to yet, at most $MAX_REPLIES_PER_CHECKIN replies in this check-in. ")
        append("Each reply: one short friendly sentence in the language the commenter used, about what they wrote, never the same wording twice, no links, no promises, no asking for likes. Skip abusive or hateful comments (do not reply to them).\n")
        append("4. Finish with done. The why field must be ONE line: STATS likes=<n> comments=<n> shares=<n> replied=<n replies made in this check-in> posted=<yes|no> then GOAL_MET or GOAL_NOT_MET, then one short sentence. ")
        append("GOAL_MET only if the success condition in the user's goal is truly satisfied by the numbers you read now; if the goal has no number to reach, GOAL_MET means everything it asked for has been done.\n")
        append("Never like, comment or follow from any other account, never buy likes and never ask people for likes.")
    }

    fun fire(ctx: Context, id: String) {
        val app = ctx.applicationContext
        synchronized(cycleLock) {
            val g0 = all(app).firstOrNull { it.id == id } ?: return
            if (g0.status != "running") return
            val now = System.currentTimeMillis()
            if (now > g0.deadlineAt) { finish(app, g0, "expired"); return }

            val wl = try {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "neo:goal").also { it.acquire((CHECKIN_MINUTES + 2) * 60_000L) }
            } catch (e: Exception) { null }
            try {
                try { Thread.sleep(1800) } catch (e: InterruptedException) { }
                val apps = try { OrbBridge.apps() } catch (e: Exception) { Approvals.lastApps }
                val pkg = apps.findBest(g0.app)?.pkg

                // The phone must be unlocked, and the user must not be in the middle of using something else.
                val locked = try { (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked } catch (e: Exception) { false }
                if (locked) { later(app, g0, 10, "the phone is locked"); return }
                val fg = UiDriver.foregroundPkg()
                if (g0.skips < 3 && fg != null && fg != app.packageName && fg != pkg && fg != "com.android.systemui") {
                    later(app, g0, 5, "the phone is in use"); return
                }

                val args = JSONObject().put("goal", checkinPrompt(g0)).put("app", g0.app)
                    .put("max_steps", CHECKIN_STEPS).put("max_minutes", CHECKIN_MINUTES)
                PinVault.unattended = true   // a check-in runs on its own: no PIN is ever typed
                val r = try { AppTask.run(app, args, apps) } catch (e: Exception) { "Couldn’t do that (${e.javaClass.simpleName})" }
                finally { PinVault.unattended = false }
                handleResult(app, g0, r)
            } finally {
                try { wl?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
            }
        }
    }

    private fun later(ctx: Context, g: Goal, minutes: Int, why: String) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val n = update(ctx, g.id) { it.copy(skips = it.skips + 1, nextAt = at) } ?: return
        arm(ctx, g.id, at)
        // Waiting a long time for an unlocked phone is the one thing the user can fix: tell them once.
        if (n.skips == 8) pauseAndTell(ctx, n, "Waiting for the phone", "I haven’t been able to work on your goal because $why. Unlock the phone and say “resume goal” to carry on.")
    }

    private fun handleResult(ctx: Context, g: Goal, r: String) {
        val stats = Regex("STATS\\s+likes=(\\S+)\\s+comments=(\\S+)\\s+shares=(\\S+)\\s+replied=(\\d+)\\s+posted=(yes|no)", RegexOption.IGNORE_CASE).find(r)
        val met = r.contains("GOAL_MET") && !r.contains("GOAL_NOT_MET")
        val stamp = fmt.format(System.currentTimeMillis())

        if (r.startsWith("Done.") && stats != null) {
            val (likes, comments, shares, replied, posted) = stats.destructured
            val line = "likes $likes · comments $comments · shares $shares"
            val n = update(ctx, g.id) {
                it.copy(checkins = it.checkins + 1, fails = 0, skips = 0, posted = it.posted || posted.equals("yes", true),
                    replied = it.replied + (replied.toIntOrNull() ?: 0), lastStats = line,
                    log = it.log + "$stamp: $line, replied $replied")
            } ?: return
            SleepHooks.note(ctx, "NOTE", "Goal in ${n.app}", n.instruction.take(60), "Check-in ${n.checkins}: $line, replied to $replied comments", if (met) "Goal reached" else "")
            if (met) { finish(ctx, n, "done", r.removePrefix("Done.").trim()); return }
            val at = System.currentTimeMillis() + n.everyMin * 60_000L
            update(ctx, g.id) { it.copy(nextAt = at) }
            arm(ctx, g.id, at)
            return
        }

        // Things only the user can sort out: stop now and say exactly what is needed.
        val needsUser = r.startsWith("I need your OK") || r.startsWith("No access to") || r.startsWith("Turn on") ||
            r.startsWith("Add a Gemini") || r.startsWith("No app")
        val n = update(ctx, g.id) { it.copy(fails = it.fails + 1, log = it.log + "$stamp: ${r.take(100)}") } ?: return
        if (needsUser || n.fails >= 3) {
            pauseAndTell(ctx, n, "Goal needs you", r.take(280) + " — when it is sorted, say “resume goal”" + (if (r.startsWith("I need your OK")) " and tell me yes." else "."))
        } else {
            val at = System.currentTimeMillis() + 10 * 60_000L
            update(ctx, g.id) { it.copy(nextAt = at) }
            arm(ctx, g.id, at)
        }
    }

    private fun finish(ctx: Context, g: Goal, how: String, summary: String = "") {
        disarm(ctx, g.id)
        update(ctx, g.id) { it.copy(status = how, nextAt = 0L) }
        val head = if (how == "done") "Goal reached" else "Goal ran out of time"
        val body = buildString {
            append(g.instruction.take(120)).append("\n")
            if (g.lastStats.isNotBlank()) append("Now: ").append(g.lastStats).append(" · ").append(g.replied).append(" replies sent\n")
            if (summary.isNotBlank()) append(summary.take(160))
        }.trim()
        notify(ctx, g.id, head, body)
    }

    private fun pauseAndTell(ctx: Context, g: Goal, title: String, text: String) {
        disarm(ctx, g.id)
        update(ctx, g.id) { it.copy(status = "paused", nextAt = 0L) }
        notify(ctx, g.id, title, text)
    }

    /* ---------------- alarms, boot, notification ---------------- */

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx.applicationContext, GoalReceiver::class.java).setAction("neo.goal.fire.$id").putExtra("id", id)
        return PendingIntent.getBroadcast(ctx.applicationContext, ("goal$id").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, id: String, at: Long) {
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pending(ctx, id))
        }
    }

    private fun disarm(ctx: Context, id: String) {
        try { (ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id)) } catch (e: Exception) { }
    }

    /** After a restart the alarms are gone: set them again, a couple of minutes after boot. */
    fun rearm(ctx: Context) {
        val app = ctx.applicationContext
        for (g in all(app)) if (g.status == "running") {
            val at = System.currentTimeMillis() + 2 * 60_000L
            update(app, g.id) { it.copy(nextAt = at) }
            arm(app, g.id, at)
        }
    }

    private fun notify(ctx: Context, id: String, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Goals", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(("goal$id" + System.currentTimeMillis()).hashCode(), NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
}

/** Fires when a goal's next check-in is due. */
class GoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        val done = AtomicBoolean(false)
        fun finishOnce() { if (done.compareAndSet(false, true)) { try { pending.finish() } catch (e: Exception) { } } }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, 9000)
        Thread { try { Goals.fire(app, id) } catch (e: Exception) { } finally { finishOnce() } }.start()
    }
}
