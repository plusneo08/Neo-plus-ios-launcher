package com.neo.plus.iosluancher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Modes: sleep, work, journey, chill and meeting. They all do what Sleep mode does (answer chat messages as the
 * assistant, never money ones, log everything, ring you with a spoken summary when the mode ends) and, new, they all
 * answer phone calls by talking to the caller (see CallConversation.kt). The only differences are what the assistant
 * says you are doing and the wording of the summary.
 *
 * It is built on SleepMode: starting a mode starts SleepMode with the activity text of the mode, then remembers
 * which mode it was in the "mode" setting of SleepMode's preferences.
 */
object Modes {
    class Mode(val key: String, val label: String, val activity: String, val situation: String, val defaultMinutes: Int)

    val all = listOf(
        Mode("sleep", "Sleep mode", "", "asleep", 0),
        Mode("work", "Work mode", "busy at work", "busy at work", 0),          // default end 17:00
        Mode("journey", "Journey mode", "travelling", "travelling", 120),
        Mode("chill", "Chill mode", "taking some time to relax", "taking some time to relax", 120),
        Mode("meeting", "Meeting mode", "in a meeting", "in a meeting", 60)
    )

    fun find(key: String): Mode? = all.firstOrNull { it.key == key.trim().lowercase(Locale.ROOT) }

    /** The mode that was last started (also right after it ended, for the summary). Old-style "I'm in a meeting" counts as a custom mode. */
    private fun lastMode(ctx: Context): Mode {
        val p = SleepMode.prefs(ctx.applicationContext)
        find(p.getString("mode", "").orEmpty())?.let { return it }
        val act = p.getString("activity", "").orEmpty().trim()
        return if (act.isEmpty()) all[0] else Mode("busy", "Busy mode", act, act, 0)
    }

    /** The mode that is on right now, or null when no mode (and no sleep mode) is on. */
    fun current(ctx: Context): Mode? = if (SleepMode.isOn(ctx.applicationContext)) lastMode(ctx) else null

    /** The time the mode ends, like "15:30", or "". */
    fun endTime(ctx: Context): String {
        val at = SleepMode.prefs(ctx.applicationContext).getLong("wake_at", 0L)
        return if (at <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    /* ---------------- wording used by the summary, the ring and the messages ---------------- */

    fun labelNow(ctx: Context): String = lastMode(ctx).label
    fun summaryWord(ctx: Context): String = lastMode(ctx).let { if (it.key == "sleep") "night summary" else "${it.key} summary" }
    fun greeting(ctx: Context): String = if (lastMode(ctx).key == "sleep") "Good morning." else "Welcome back."
    fun quiet(ctx: Context): String =
        if (lastMode(ctx).key == "sleep") "Good morning. It was a quiet night: nothing came in." else "Welcome back. It was quiet: nothing came in."

    fun callHint(ctx: Context): String {
        val m = lastMode(ctx)
        return if (m.key == "sleep") "[A phone call just started. I woke up. Tell me what happened overnight.]"
        else "[A phone call just started. My ${m.label.lowercase(Locale.ROOT)} is over. Tell me what happened while I was ${m.situation}.]"
    }

    fun offMessage(ctx: Context): String {
        val m = lastMode(ctx)
        return if (m.key == "sleep") "Sleep mode is off. Say “what did I miss” for the night summary."
        else "${m.label} is off. Say “what did I miss” for the summary."
    }

    /** The summary instructions, changed from “woke up / the night” to the mode that just ended. Sleep mode keeps its own text. */
    fun reportSystem(ctx: Context, base: String): String {
        val m = lastMode(ctx)
        if (m.key == "sleep") return base
        return base
            .replace("right after he woke up. Tell him what happened during the night.",
                "right after his ${m.label.lowercase(Locale.ROOT)} ended. Tell him what happened while he was ${m.situation}.")
            .replace("Start with \"Good morning.\"", "Start with \"Welcome back.\"")
            .replace("If nothing happened say it was a quiet night.", "If nothing happened say it was quiet.")
    }

    /* ---------------- start / stop / status ---------------- */

    /**
     * Starts a mode. The end time is, in this order: [hour]:[minute] when hour is 0..23, else now + [minutes], else the
     * mode's default (sleep 07:00, work 17:00 or two hours from now when it is already late, meeting 1 h, others 2 h).
     */
    fun start(ctx: Context, key: String, hour: Int, minute: Int, minutes: Int): String {
        val app = ctx.applicationContext
        val m = find(key) ?: return "Which mode: sleep, work, journey, chill or meeting?"
        val now = Calendar.getInstance()
        val (h, min) = when {
            hour in 0..23 -> hour to minute.coerceIn(0, 59)
            minutes <= 0 && m.key == "sleep" -> 7 to 0
            minutes <= 0 && m.key == "work" && now.get(Calendar.HOUR_OF_DAY) < 16 -> 17 to 0
            else -> {
                val mins = (if (minutes > 0) minutes else if (m.defaultMinutes > 0) m.defaultMinutes else 120).coerceAtLeast(5)
                now.add(Calendar.MINUTE, mins)
                now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE)
            }
        }
        val r = SleepMode.start(app, h, min, m.activity)
        if (!SleepMode.isOn(app)) return r   // it could not start (notification access, no Gemini key)
        SleepMode.prefs(app).edit().putString("mode", m.key).apply()
        val why = CallConversation.cannotRun(app)
        return r + if (why == null) " Calls: I’ll answer them and talk to the caller for you."
        else " Calls: I can’t talk to callers yet ($why), so they get the short greeting."
    }

    fun status(ctx: Context): String {
        val m = current(ctx) ?: return "No mode is on."
        val until = endTime(ctx)
        return "${m.label} is on" + (if (until.isNotEmpty()) " until $until" else "") + "."
    }

    /** The `mode` action: {"mode":"meeting","on":true,"hour":15,"minute":0} or {"mode":"journey","minutes":90}; {"on":false}; {"do":"status"}. */
    fun handle(ctx: Context, a: JSONObject): String {
        val app = ctx.applicationContext
        if (a.optString("do").equals("status", true)) return status(app)
        if (!a.optBoolean("on", true)) return SleepMode.stop(app)
        return start(app, a.optString("mode"), a.optInt("hour", -1), a.optInt("minute", 0), a.optInt("minutes", 0))
    }
}

/** Understands "meeting mode until 3pm", "journey mode for 2 hours", "work mode", "turn off chill mode" without the AI model. */
object ModeCmd {
    private val I = RegexOption.IGNORE_CASE
    private const val NAMES = "work|meeting|journey|chill|travel|driving"
    private val onRe = Regex("\\b($NAMES)\\s+mode\\b", I)
    private val offRe = Regex("\\b(?:stop|end|turn off|cancel|switch off|disable|exit)\\b.{0,12}\\b(?:$NAMES)\\s+mode\\b|\\b(?:$NAMES)\\s+mode\\s+off\\b", I)
    private val untilRe = Regex("\\b(?:until|till|by|to)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", I)
    private val forRe = Regex("\\bfor\\s+(?:(\\d{1,3})|an?|half an?)\\s*(hours?|hrs?|minutes?|mins?)\\b", I)
    private val statusRe = Regex("\\b(?:which|what) mode\\b|\\bmode status\\b", I)

    /** Turns "until 3" into a clock time: with no am/pm the next 3 o'clock that is still ahead is meant. */
    private fun clock(h: Int, min: Int, ap: String): Pair<Int, Int>? {
        if (h !in 0..23 || min !in 0..59) return null
        if (ap == "am") return (if (h == 12) 0 else h) to min
        if (ap == "pm") return (if (h < 12) h + 12 else h) to min
        if (h in 1..11) {
            val now = Calendar.getInstance()
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            fun ahead(hh: Int) = ((hh * 60 + min) - nowMin + 1440) % 1440
            return (if (ahead(h) <= ahead(h + 12)) h else h + 12) to min
        }
        return h to min
    }

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        if (offRe.containsMatchIn(t)) return LocalPlan("", listOf("mode" to JSONObject().put("on", false)))
        val m = onRe.find(t)
        if (m == null) {
            if (statusRe.containsMatchIn(t)) return LocalPlan("", listOf("mode" to JSONObject().put("do", "status")))
            return null
        }
        val word = m.groupValues[1].lowercase(Locale.ROOT)
        val key = if (word == "travel" || word == "driving") "journey" else word
        val a = JSONObject().put("on", true).put("mode", key)
        val f = forRe.find(t)
        if (f != null) {
            val n = f.groupValues[1].toIntOrNull()
            val hours = f.groupValues[2].lowercase(Locale.ROOT).startsWith("h")
            val mins = when {
                n != null -> if (hours) n * 60 else n
                t.contains("half an", true) -> 30
                else -> if (hours) 60 else 1
            }
            a.put("minutes", mins)
        } else {
            val u = untilRe.find(t)
            if (u != null) {
                val h = u.groupValues[1].toIntOrNull()
                val min = u.groupValues[2].toIntOrNull() ?: 0
                val ap = u.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
                if (h != null) clock(h, min, ap)?.let { a.put("hour", it.first).put("minute", it.second) }
            }
        }
        return LocalPlan("", listOf("mode" to a))
    }
}
