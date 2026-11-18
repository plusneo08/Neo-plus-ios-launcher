package com.neo.plus.ioslauncher

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ====================================================================================== */
/*  EVENT LOG: a short history of what the assistant did and what it saw happen           */
/* ====================================================================================== */

/**
 * Last 300 events, kept on this phone only. Actions are logged by name and result; anything that carries someone's words
 * (messages, notifications, screens, money, memory) is logged by name only, never with its content.
 */
object EventLog {
    private const val PREF = "neo_eventlog"
    private val skip = setOf("ui_list", "ui_wait", "look", "listen", "lock_app", "approvals", "schedules", "unlock")
    private val nameOnly = Guard.untrustedReads + setOf(
        "remember", "forget", "report_number", "call_log",
        "message", "whatsapp", "messenger", "email", "reply_message", "call", "whatsapp_call", "post"
    )

    fun add(c: Context, source: String, text: String, ok: Boolean = true) {
        try {
            val sp = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            synchronized(this) {
                val arr = try { JSONArray(sp.getString("log", "[]")) } catch (e: Exception) { JSONArray() }
                arr.put(JSONObject().put("t", System.currentTimeMillis()).put("s", source).put("x", text.take(160)).put("ok", ok))
                val keep = JSONArray()
                for (i in maxOf(0, arr.length() - 300) until arr.length()) keep.put(arr.get(i))
                sp.edit().putString("log", keep.toString()).apply()
            }
        } catch (e: Throwable) { }
    }

    /** Called for every action the assistant runs. */
    fun fromAction(c: Context, name: String, ok: Boolean, note: String) {
        if (name in skip) return
        val text = if (name in nameOnly) name else name + ": " + note.replace('\n', ' ').take(90)
        add(c, "action", text, ok)
    }

    fun query(c: Context, filter: String, hours: Int, max: Int = 12): String {
        val sp = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try { JSONArray(sp.getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        val from = System.currentTimeMillis() - hours.coerceIn(1, 168) * 3_600_000L
        val f = filter.trim().lowercase()
        val sameDay = SimpleDateFormat("HH:mm", Locale.getDefault())
        val older = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val day0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val rows = ArrayList<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optLong("t")
            if (t < from) break
            val line = o.optString("s") + " " + o.optString("x")
            if (f.isNotEmpty() && !line.lowercase().contains(f)) continue
            val stamp = (if (t >= day0) sameDay else older).format(Date(t))
            rows.add("$stamp  ${o.optString("x")}" + (if (o.optBoolean("ok", true)) "" else "  (failed)"))
            if (rows.size >= max) break
        }
        return if (rows.isEmpty()) "Nothing logged" + (if (f.isNotEmpty()) " about “$filter”" else "") + " in the last ${hours.coerceIn(1, 168)} hours"
        else "Latest first:\n" + rows.joinToString("\n")
    }
}

/* ====================================================================================== */
/*  WATCHERS                                                                              */
/* ====================================================================================== */

/**
 * "Tell me when Uber says arriving": a rule per app, checked every 10 seconds, only while at least one rule exists.
 *   - notification rule: a notification from that app whose title or text contains one of the words (separate with |)
 *   - screen rule ("screen":true): the words appear on that app's screen while it is open (for apps that never notify)
 * Also: an app crash / "not responding" box on screen is reported if one of your watched apps is named in it.
 * It only reads this phone's own notifications and screen. It never sends anything anywhere and never acts: it tells you.
 * Quiet hours, sleep mode and calls are handled by Proactive.tell (notification only, no voice).
 */
object Watch {
    private const val PREF = "neo_watch"
    private const val FAST_MS = 10_000L
    private const val MAX = 12

    class Rule(val id: String, val app: String, val pkg: String, val contains: String, val say: String,
               val once: Boolean, val screen: Boolean, val since: Long)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(c: Context): MutableList<Rule> {
        val arr = try { JSONArray(sp(c).getString("rules", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Rule(o.optString("id"), o.optString("app"), o.optString("pkg"), o.optString("contains"), o.optString("say"),
                o.optBoolean("once", true), o.optBoolean("screen", false), o.optLong("since")))
        }
        return out
    }

    private fun save(c: Context, l: List<Rule>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("id", it.id).put("app", it.app).put("pkg", it.pkg).put("contains", it.contains)
                .put("say", it.say).put("once", it.once).put("screen", it.screen).put("since", it.since))
        }
        sp(c).edit().putString("rules", arr.toString()).apply()
    }

    private fun installed(c: Context, pkg: String): Boolean =
        try { c.packageManager.getLaunchIntentForPackage(pkg) != null } catch (e: Throwable) { false }

    fun resolvePkg(c: Context, app: String): String {
        val q = app.trim()
        if (q.contains('.') && !q.contains(' ')) return q
        Connect.find(c, q)?.let { k -> k.pkgs.firstOrNull { installed(c, it) }?.let { return it } }
        val pm = c.packageManager
        val t = q.lowercase()
        return try {
            val all = pm.getInstalledApplications(0)
            all.firstOrNull { pm.getApplicationLabel(it).toString().lowercase() == t }?.packageName
                ?: all.firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(t) }?.packageName
                ?: ""
        } catch (e: Throwable) { "" }
    }

    /* ---- the loop ---- */

    @Volatile private var loop: Handler? = null
    @Volatile private var appCtx: Context? = null
    private val seen = LinkedHashSet<String>()
    private val lastFire = HashMap<String, Long>()
    private var lastCrash = 0L

    /** Called every minute from Proactive.check, and when a rule is added. Starts the 10 second loop once. */
    fun tick(c: Context) {
        appCtx = c.applicationContext
        if (loop == null) {
            synchronized(this) {
                if (loop == null) {
                    val t = HandlerThread("neo-watch")
                    t.start()
                    val h = Handler(t.looper)
                    loop = h
                    h.postDelayed(fast, FAST_MS)
                }
            }
        }
        try { health(c) } catch (e: Throwable) { }
    }

    private val fast = object : Runnable {
        override fun run() {
            val c = appCtx
            try { if (c != null) scan(c) } catch (e: Throwable) { }
            loop?.postDelayed(this, FAST_MS)
        }
    }

    private fun matches(hay: String, words: String): Boolean {
        if (words.isBlank()) return true
        val h = hay.lowercase()
        return words.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { h.contains(it) }
    }

    private fun scan(c: Context) {
        val rules = load(c)
        if (rules.isEmpty()) return

        // 1) notification rules
        val svc = NeoNotificationService.instance
        if (svc != null && rules.any { !it.screen }) {
            val list = try { svc.activeNotifications } catch (e: Throwable) { null }
            if (list != null) for (n in list) {
                if (n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
                val key = n.key + "|" + n.postTime
                if (!seen.add(key)) continue
                while (seen.size > 400) { val it = seen.iterator(); it.next(); it.remove() }
                val ex = n.notification.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                for (r in rules) {
                    if (r.screen || r.pkg != n.packageName || n.postTime < r.since) continue
                    if (matches("$title $text", r.contains)) fire(c, r, title, text)
                }
            }
        }

        // 2) screen rules and crash boxes
        val s = IosControlService.instance ?: return
        val root = try { s.rootInActiveWindow } catch (e: Throwable) { null } ?: return
        val pkg = root.packageName?.toString() ?: return
        val screenRules = rules.filter { it.screen && it.pkg == pkg }
        if (screenRules.isEmpty() && pkg != "android") return
        val text = Verify.screenText(2500)
        for (r in screenRules) if (r.since <= System.currentTimeMillis() && matches(text, r.contains)) fire(c, r, "", text.take(80))
        if (pkg == "android" && Regex("(?i)keeps stopping|isn.t responding|has stopped").containsMatchIn(text)) {
            val now = System.currentTimeMillis()
            if (now - lastCrash > 5 * 60_000L) {
                lastCrash = now
                val hit = rules.firstOrNull { text.contains(it.app, ignoreCase = true) }
                val msg = if (hit != null) "${hit.app} has crashed or stopped responding" else "An app has crashed or stopped responding"
                Proactive.tell(c, Proactive.line(c, msg), true, null, false)
                EventLog.add(c, "watch", msg, false)
            }
        }
    }

    private fun fire(c: Context, r: Rule, title: String, text: String) {
        val now = System.currentTimeMillis()
        if (now - (lastFire[r.id] ?: 0L) < 20_000L) return
        lastFire[r.id] = now
        // Default line: the notification title only (short), never the whole text. {title} and {text} are there if you asked for them.
        val body = (if (r.say.isNotBlank()) r.say.replace("{title}", title).replace("{text}", text)
        else "${r.app}: ${title.ifBlank { r.contains.split('|').first() }}").take(160)
        val launch = try { c.packageManager.getLaunchIntentForPackage(r.pkg) } catch (e: Throwable) { null }
        Proactive.tell(c, Proactive.line(c, body), true, launch, false)
        EventLog.add(c, "watch", "${r.app} matched “${r.contains}”")
        if (r.once) { val l = load(c); l.removeAll { it.id == r.id }; save(c, l) }
    }

    /* ---- health ---- */

    private fun due(c: Context, key: String, everyMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = sp(c).getLong("warn_$key", 0L)
        if (now - last < everyMs) return false
        sp(c).edit().putLong("warn_$key", now).apply()
        return true
    }

    /** Quiet unless something is wrong; each warning repeats at most every 6 hours. */
    private fun health(c: Context) {
        val rules = load(c)
        if (rules.any { !it.screen } && NeoNotificationService.instance == null && due(c, "notif", 6 * 3_600_000L))
            Proactive.tell(c, Proactive.line(c, "Notification access is off, so I can’t watch your apps"), false)
        if ((rules.any { it.screen } || Connect.hasCustom(c)) && IosControlService.instance == null && due(c, "acc", 6 * 3_600_000L))
            Proactive.tell(c, Proactive.line(c, "Accessibility control is off, so I can’t read or tap apps"), false)
        val gone = rules.filter { !installed(c, it.pkg) }
        if (gone.isNotEmpty()) {
            val l = load(c); l.removeAll { r -> gone.any { it.id == r.id } }; save(c, l)
            Proactive.tell(c, Proactive.line(c, "I stopped watching " + gone.joinToString { it.app } + " because it is no longer installed"), false)
        }
        val auth = Connect.needsAuth(c)
        if (auth.isNotEmpty() && due(c, "auth", 6 * 3_600_000L))
            Proactive.tell(c, Proactive.line(c, auth.joinToString() + " needs you to sign in again"), false)
    }

    fun healthReport(c: Context): String {
        val rules = load(c)
        val lines = ArrayList<String>()
        lines.add("Accessibility control: " + if (IosControlService.instance != null) "on" else "OFF (I can’t tap or read apps)")
        lines.add("Notification access: " + if (NeoNotificationService.instance != null) "on" else "OFF (I can’t watch notifications)")
        lines.add("Watchers: ${rules.size}" + if (rules.isNotEmpty()) " (checked every ${FAST_MS / 1000} seconds)" else "")
        val gone = rules.filter { !installed(c, it.pkg) }
        if (gone.isNotEmpty()) lines.add("Not installed any more: " + gone.joinToString { it.app })
        val auth = Connect.needsAuth(c)
        if (auth.isNotEmpty()) lines.add("Needs sign-in: " + auth.joinToString())
        val missing = Connect.all(c).filter { it.pkgs.isNotEmpty() && it.pkgs.none { p -> installed(c, p) } }.size
        lines.add("Connectors: ${Connect.all(c).size} known, $missing not installed on this phone")
        return lines.joinToString("\n")
    }

    /* ---- commands ---- */

    fun handle(c: Context, a: JSONObject): String = when (a.optString("do").lowercase().ifBlank { "list" }) {
        "add" -> add(c, a)
        "list" -> list(c)
        "remove" -> remove(c, a)
        "log" -> EventLog.query(c, a.optString("app"), a.optInt("hours", 24))
        "health" -> healthReport(c)
        else -> "Use do: add, list, remove, log or health"
    }

    private fun add(c: Context, a: JSONObject): String {
        val app = a.optString("app").trim()
        if (app.isEmpty()) return "Which app should I watch?"
        val pkg = resolvePkg(c, app)
        if (pkg.isEmpty()) return "No app called “$app”"
        val rules = load(c)
        if (rules.size >= MAX) return "That is the most I can watch ($MAX). Remove one first."
        val screen = a.optBoolean("screen", false)
        val contains = a.optString("contains").trim()
        val id = ((rules.maxOfOrNull { it.id.toIntOrNull() ?: 0 }) ?: 0) + 1
        rules.add(Rule(id.toString(), app, pkg, contains, a.optString("say").take(120), a.optBoolean("once", true), screen, System.currentTimeMillis()))
        save(c, rules)
        tick(c)
        val warn = when {
            !screen && NeoNotificationService.instance == null -> " Turn on notification access for Neo Plus, or I won’t see them."
            screen && IosControlService.instance == null -> " Turn on Accessibility control for Neo Plus, or I can’t read that screen."
            else -> ""
        }
        return "Watching $app" + (if (contains.isNotEmpty()) " for “$contains”" else " for any " + (if (screen) "screen text" else "notification")) +
            (if (a.optBoolean("once", true)) " (once)" else "") + "." + warn
    }

    private fun list(c: Context): String {
        val rules = load(c)
        if (rules.isEmpty()) return "I’m not watching any app"
        return rules.joinToString("\n") {
            "${it.id}. ${it.app}: " + (if (it.contains.isEmpty()) "anything" else "“${it.contains}”") +
                (if (it.screen) " on screen" else " in notifications") + (if (it.once) ", once" else ", every time")
        }
    }

    private fun remove(c: Context, a: JSONObject): String {
        val rules = load(c)
        val id = a.optString("id").trim()
        val app = a.optString("app").trim().lowercase()
        val before = rules.size
        when {
            id.isNotEmpty() -> rules.removeAll { it.id == id }
            app == "all" || app == "everything" -> rules.clear()
            app.isNotEmpty() -> rules.removeAll { it.app.lowercase().contains(app) || app.contains(it.app.lowercase()) }
            else -> return "Which watcher? Give its app or id."
        }
        save(c, rules)
        val n = before - rules.size
        return if (n > 0) "Stopped watching ($n removed)" else "No watcher matched"
    }
}
