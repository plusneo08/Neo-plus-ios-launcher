package com.neo.plus.iosluancher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * SPLIT-SCREEN TASKS
 *
 * Every task gets its own pane. One task = full screen, two = top / bottom, three = three rows, four = 2x2, and so on with no
 * upper limit (nine, ten, twelve...): each new task re-divides the screen again so every task stays in its own pane.
 *
 * Each pane sends ONLY its own request to the AI (its own history, its own thread), so tasks never mix.
 *
 * Voice runs everything while the screen is split: the orb is started automatically and waits in hidden standby for the wake
 * phrase ("Hey Neo" / 147). While any pane is on screen, typed commands to the assistant are ignored ([active]).
 *
 * Voice commands handled by [onVoice]:
 *   "new task <request>" / "another task <request>" / "also <request>"  -> opens another pane
 *   "close task 2" / "stop task three"                                   -> stops and closes that pane
 *   "close all tasks" / "stop all tasks" / "end split screen"            -> closes everything
 *   "split screen on" / "split screen off"                               -> every request becomes a pane / only task-like ones
 *
 * LIMIT (Android, not this app): the phone has one real screen and one accessibility focus, so steps that drive an app or a
 * website on the screen (app_task, web_task, web_batch) take turns through [screenLock]. Everything else (questions, lookups,
 * phone controls, writing) runs truly in parallel. A pane waiting for its turn says so.
 */
object SplitTasks {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    /** Taken by every step that drives the screen, so two tasks never tap against each other. */
    val screenLock = ReentrantLock(true)

    private class Pane(val id: Int, val request: String) {
        @Volatile var status = "Starting"
        @Volatile var text = ""
        @Volatile var running = true
        @Volatile var cancelled = false
        @Volatile var ownsScreen = false
        @Volatile var ok = true
        var thread: Thread? = null
        var statusView: TextView? = null
        var bodyView: TextView? = null
        var scroll: ScrollView? = null
        var stopView: TextView? = null
    }

    private val panes = ArrayList<Pane>()
    private var nextId = 1
    private var ctx: Context? = null
    private var root: LinearLayout? = null
    private var ticking = false
    private val current = ThreadLocal<Pane?>()

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { refresh() } catch (e: Exception) { }
            main.postDelayed(this, 400)
        }
    }

    /** True while at least one pane is on screen: typed commands to the assistant are ignored then, voice is used instead. */
    val active: Boolean get() = synchronized(panes) { panes.isNotEmpty() }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("neo_split", Context.MODE_PRIVATE)

    /** Run a screen-driving step (app_task, web_task...) one at a time; the pane that asked shows "waiting" until it is its turn. */
    fun <T> withScreen(block: () -> T): T {
        val p = current.get()
        if (p == null) return block()
        if (!screenLock.tryLock()) {
            p.status = "Waiting for the screen (another task is using it)"
            screenLock.lock()
        }
        try {
            p.ownsScreen = true
            p.status = "Working on the screen"
            return block()
        } finally {
            p.ownsScreen = false
            screenLock.unlock()
            if (p.running) p.status = "Working"
        }
    }

    // ------------------------------------------------------------------ voice entry point (called by the orb)

    private val NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12
    )
    private val NEW_TASK = Regex("^(?:(?:new|another|next|add(?: a)?|start(?: a)?) task|and also|also|at the same time)[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val CLOSE_ALL = Regex("^(?:close|stop|end|cancel|finish)\\s+(?:all(?: the)?\\s+tasks?|everything|(?:the\\s+)?split(?:\\s*screen)?|all panes|all pages)$", RegexOption.IGNORE_CASE)
    private val CLOSE_ONE = Regex("^(?:close|stop|end|cancel)\\s+(?:task|page|pane|screen)\\s+(\\w+)$", RegexOption.IGNORE_CASE)
    private val CLOSE_DONE = Regex("^(?:close|clear)\\s+(?:the\\s+)?(?:finished|done|completed)(?:\\s+tasks?)?$", RegexOption.IGNORE_CASE)
    private val MODE_ON = Regex("^(?:turn on|switch on|enable|start)\\s+split(?:\\s*screen)?(?:\\s+mode)?$|^split(?:\\s*screen)?(?:\\s+mode)?\\s+on$", RegexOption.IGNORE_CASE)
    private val MODE_OFF = Regex("^(?:turn off|switch off|disable)\\s+split(?:\\s*screen)?(?:\\s+mode)?$|^split(?:\\s*screen)?(?:\\s+mode)?\\s+off$", RegexOption.IGNORE_CASE)

    /** Things that sound like a job (not a one-line question or a quick phone toggle). */
    private val TASK_LIKE = Regex(
        "\\b(task|download|upload|fill|submit|register|sign up|log ?in|book|order|research|compare|find and|search and|go to|on the website|" +
            "on (?:facebook|instagram|whatsapp|tiktok|youtube|amazon|jumia)|excel|spreadsheet|every row|write (?:a|an|the)|post|schedule|" +
            "and then|then)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Gets a spoken request first. Returns the sentence for the orb to say when this class handled it, or null when the orb
     * should carry on as it always did.
     */
    fun onVoice(c: Context, cmd: String, apps: List<AppEntry>): String? {
        val app = c.applicationContext
        val t = cmd.trim().trimEnd('.', '!', '?', ',').trim()
        if (t.isEmpty()) return null

        if (MODE_ON.matches(t)) { prefs(app).edit().putBoolean("always", true).apply(); ensureOrb(app); return "Split screen is on. Every task gets its own page." }
        if (MODE_OFF.matches(t)) { prefs(app).edit().putBoolean("always", false).apply(); return "Split screen is back to automatic." }

        if (active) {
            if (CLOSE_ALL.matches(t)) { closeAll(); return "Closed all tasks." }
            if (CLOSE_DONE.matches(t)) { val n = closeFinished(); return if (n == 0) "No finished tasks." else "Closed $n finished task${if (n == 1) "" else "s"}." }
            CLOSE_ONE.find(t)?.let { m ->
                val w = m.groupValues[1].lowercase(Locale.ROOT)
                val n = w.toIntOrNull() ?: NUMBERS[w]
                if (n != null) return if (close(n)) "Closed task $n." else "There is no task $n."
            }
        }

        val explicit = NEW_TASK.find(t)?.groupValues?.get(1)?.trim()
        val request = explicit ?: t

        // Quick phone controls ("turn on the torch", "volume up") never become a pane: the orb does them straight away.
        val configured = AssistantConfig.load(app).configured
        val quick = try { LocalCommands.plan(request, apps, configured) != null } catch (e: Exception) { false }
        if (quick && explicit == null) return null

        val always = prefs(app).getBoolean("always", false)
        val wanted = explicit != null || active || always || TASK_LIKE.containsMatchIn(request)
        if (!wanted) return null

        val n = launch(app, request, apps) ?: return null
        return "Task $n started."
    }

    // ------------------------------------------------------------------ panes

    /** Opens another pane and starts its own request. Returns the pane number, or null when nothing can be drawn on screen. */
    fun launch(c: Context, request: String, apps: List<AppEntry>): Int? {
        val app = c.applicationContext
        ctx = app
        if (WebTask.overlayHost(app) == null) return null
        val p = synchronized(panes) { Pane(nextId++, request.trim()).also { panes.add(it) } }
        ensureOrb(app)
        main.post { relayout(); startTicking() }

        TaskService.begin(app, request)
        p.thread = Thread({
            current.set(p)
            try {
                val cfg = AssistantConfig.load(app)
                p.status = "Working"
                val answer: String = if (!cfg.configured) {
                    Keyless.answer(app, p.request, apps)
                } else {
                    // Only this pane's request goes to the AI: its history holds nothing from the other panes.
                    val out = Agent.respond(
                        app, cfg.provider, cfg.key, cfg.activeModel, buildSystemPrompt(app, apps),
                        listOf(true to p.request), apps,
                        onPartial = null
                    )
                    out.text
                }
                if (!p.cancelled) { p.text = answer.ifBlank { "Done." }; p.status = "Finished"; p.ok = true }
            } catch (e: Exception) {
                if (!p.cancelled) { p.text = e.message ?: "Something went wrong."; p.status = "Problem"; p.ok = false }
            } finally {
                p.running = false
                current.remove()
                TaskService.end(app)
                if (!p.cancelled) {
                    val first = p.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
                    OrbService.instance?.say("Task ${p.id} ${if (p.ok) "finished" else "had a problem"}. $first")
                }
            }
        }, "split-task-${p.id}").also { it.isDaemon = true; it.start() }
        return p.id
    }

    private fun stopPane(p: Pane) {
        p.cancelled = true
        p.status = "Stopped"
        if (p.text.isBlank()) p.text = "Stopped."
        if (p.ownsScreen) { try { TaskLog.stop() } catch (e: Exception) { } }
        try { p.thread?.interrupt() } catch (e: Exception) { }
    }

    fun close(id: Int): Boolean {
        val p = synchronized(panes) { panes.firstOrNull { it.id == id } } ?: return false
        if (p.running) stopPane(p)
        synchronized(panes) { panes.remove(p) }
        main.post { relayout() }
        return true
    }

    fun closeAll() {
        val all = synchronized(panes) { panes.toList() }
        all.forEach { if (it.running) stopPane(it) }
        synchronized(panes) { panes.clear() }
        main.post { relayout() }
    }

    fun closeFinished(): Int {
        val done = synchronized(panes) { panes.filter { !it.running } }
        synchronized(panes) { panes.removeAll(done.toSet()) }
        main.post { relayout() }
        return done.size
    }

    /** Turns the floating orb on by itself and leaves it in hidden standby, waiting for the wake phrase. */
    private fun ensureOrb(app: Context) {
        try {
            if (OrbService.instance == null) OrbService.start(app)
            app.getSharedPreferences("neo_orb", Context.MODE_PRIVATE).edit().putBoolean("stealth", true).apply()
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ drawing

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun statusBarHeight(c: Context): Int {
        val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) c.resources.getDimensionPixelSize(id) else dp(c, 24)
    }

    private fun startTicking() { if (!ticking) { ticking = true; main.post(tick) } }

    /** Rows x columns for [n] panes: 1 / 2 / 3 stack top to bottom, 4 = 2x2, 5-6 = 2 wide, 7-9 = 3x3, and it keeps growing. */
    private fun columnsFor(n: Int): Int = if (n <= 3) 1 else ceil(sqrt(n.toDouble())).toInt()

    private fun relayout() {
        val c = ctx ?: return
        val list = synchronized(panes) { panes.toList() }
        val wm = (WebTask.overlayHost(c) ?: return).first
        root?.let { r -> try { wm.removeView(r) } catch (e: Exception) { } }
        root = null
        if (list.isEmpty()) { ticking = false; main.removeCallbacks(tick); return }

        val host = WebTask.overlayHost(c) ?: return
        val n = list.size
        val cols = columnsFor(n)
        val rows = ceil(n / cols.toDouble()).toInt()
        val big = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 10, 14))
            setPadding(dp(c, 3), statusBarHeight(c) + dp(c, 3), dp(c, 3), dp(c, 3))
        }
        var i = 0
        for (r in 0 until rows) {
            val inRow = minOf(cols, n - i)
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(inRow) { row.addView(buildPane(c, list[i], n), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)); i++ }
            big.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, host.second,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { host.first.addView(big, lp); root = big } catch (e: Exception) { root = null }
        // The orb must stay above the panes so it can be seen waking up.
        OrbService.instance?.raiseToFront()
        refresh()
    }

    private fun buildPane(c: Context, p: Pane, total: Int): View {
        val small = total > 4
        val tiny = total > 9
        val textSp = when { tiny -> 9f; small -> 10f; total > 2 -> 12f; else -> 14f }
        val accent = longArrayOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF375F, 0xFF64D2FF, 0xFFFFD60A, 0xFFAC8E68, 0xFF8E8E93)[(p.id - 1) % 9].toInt()

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(28, 28, 32)); cornerRadius = dp(c, 12).toFloat(); setStroke(dp(c, 2), accent) }
            setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4))
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(c).apply {
            text = "${p.id} · ${p.request}"; setTextColor(accent); textSize = textSp; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val stop = TextView(c).apply {
            text = if (p.running) "Stop" else "✕"; setTextColor(Color.WHITE); textSize = textSp - 1f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(if (p.running) Color.argb(230, 255, 69, 58) else Color.argb(160, 90, 90, 96)); cornerRadius = dp(c, 14).toFloat() }
            setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
            setOnClickListener { if (p.running) { stopPane(p); refresh() } else close(p.id) }
        }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(stop)
        val status = TextView(c).apply { setTextColor(Color.argb(170, 255, 255, 255)); textSize = textSp - 1f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val body = TextView(c).apply { setTextColor(Color.WHITE); textSize = textSp; setTextIsSelectable(false) }
        val scroll = ScrollView(c).apply { addView(body) }
        box.addView(head); box.addView(status)
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        p.statusView = status; p.bodyView = body; p.scroll = scroll; p.stopView = stop
        return box
    }

    private fun refresh() {
        val list = synchronized(panes) { panes.toList() }
        for (p in list) {
            p.statusView?.text = p.status
            val shown = if (p.text.isNotBlank()) p.text else if (p.running) "…" else ""
            if (p.bodyView?.text?.toString() != shown) { p.bodyView?.text = shown; p.scroll?.post { p.scroll?.fullScroll(View.FOCUS_DOWN) } }
            p.stopView?.let { v ->
                val label = if (p.running) "Stop" else "✕"
                if (v.text.toString() != label) {
                    v.text = label
                    v.background = GradientDrawable().apply { setColor(if (p.running) Color.argb(230, 255, 69, 58) else Color.argb(160, 90, 90, 96)); cornerRadius = 40f }
                }
            }
        }
    }
}
