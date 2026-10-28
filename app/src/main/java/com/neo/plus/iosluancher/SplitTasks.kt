package com.neo.plus.iosluancher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
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
 * You can drive the panes three ways while the screen is split:
 *   - voice: the orb waits in hidden standby for the wake phrase ("Hey Neo" / 147);
 *   - the notification panel: a "Reply" box on the ongoing "task pages" notification (works with the phone locked / any app open);
 *   - the reply bar at the bottom of the split screen (tap a pane's title to answer that pane, otherwise it opens a new one).
 * A reply is a NEW task unless it targets a pane: "2: <text>" or "task 2 <text>" is a follow-up inside pane 2 only, with pane 2's
 * own history, so panes never mix. A follow-up sent while that pane is still working is queued and sent when it finishes.
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
        /** This pane's own conversation with the AI (true = you, false = the AI). Nothing from other panes is ever in here. */
        val history = ArrayList<Pair<Boolean, String>>()
        /** Follow-ups sent while the pane was still working; they go out one by one when it is free. */
        val pending = ArrayList<String>()
        var accent = 0
        var shownSel = false
        var box: LinearLayout? = null
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
    private var hostWm: WindowManager? = null
    private var rootLp: WindowManager.LayoutParams? = null
    private var barInput: EditText? = null
    private var selected = 0          // the pane whose title was tapped: the reply bar answers that pane (0 = open a new one)
    private var draft = ""
    private var shownHint = ""
    private var lastSig = ""
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

    /** Lets code with no Context of its own (a receiver started in a fresh process) reach the panes. */
    fun attach(c: Context) { ctx = c.applicationContext }

    private val TARGET = Regex("^(?:(?:reply|answer|tell|ask)\\s+)?(?:to\\s+)?(?:task|page|pane)\\s+(\\w+)\\s*[:,\\-]?\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TARGET_NUM = Regex("^(\\d{1,3})\\s*[:.)\\-]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    private fun isClose(t: String) = CLOSE_ALL.matches(t) || CLOSE_DONE.matches(t) || CLOSE_ONE.matches(t)

    /** The orb's entry point: a spoken request. Returns the sentence to say when handled, or null when the orb should carry on. */
    fun onVoice(c: Context, cmd: String, apps: List<AppEntry>): String? = handle(c, cmd, apps, spoken = true, force = false)

    /**
     * One entry point for voice, typed chat, the notification-panel reply and the reply bar.
     * [force] = the person clearly wants a pane (notification / bar): never hand the text back, and quick phone controls become panes too.
     * Returns the sentence to show / say, or null when the caller should carry on as before.
     */
    fun handle(c: Context, cmd: String, apps: List<AppEntry>, spoken: Boolean, force: Boolean): String? {
        val app = c.applicationContext
        val raw = cmd.trim()
        val t = raw.trimEnd('.', '!', '?', ',').trim()
        if (t.isEmpty()) return null
        ctx = app

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
            // "task 2 <text>" / "2: <text>": a follow-up that goes to pane 2 only (typed "2: ..." is not used by voice).
            val m = TARGET.find(raw) ?: (if (!spoken) TARGET_NUM.find(raw) else null)
            if (m != null) {
                val w = m.groupValues[1].lowercase(Locale.ROOT)
                val n = w.toIntOrNull() ?: NUMBERS[w]
                if (n != null) return reply(app, n, m.groupValues[2], apps)
            }
        }

        val explicit = NEW_TASK.find(t)?.groupValues?.get(1)?.trim()
        val request = explicit ?: raw
        val always = prefs(app).getBoolean("always", false)
        val wanted = force || explicit != null || active || always || (spoken && TASK_LIKE.containsMatchIn(request))
        if (!wanted) return null

        // Quick phone controls ("turn on the torch", "volume up") never become a pane: they are done straight away.
        if (!force && explicit == null) {
            val configured = AssistantConfig.load(app).configured
            val quick = try { LocalCommands.plan(request, apps, configured) != null } catch (e: Exception) { false }
            if (quick) return null
        }

        val n = launch(app, request, apps)
            ?: return if (force) "I can't open a task page. Allow Neo Plus to display over other apps first." else null
        return "Task $n started."
    }

    // ------------------------------------------------------------------ panes

    /** Opens another pane and starts its own request. Returns the pane number, or null when nothing can be drawn on screen. */
    fun launch(c: Context, request: String, apps: List<AppEntry>): Int? {
        val app = c.applicationContext
        ctx = app
        if (WebTask.overlayHost(app) == null) return null
        val p = synchronized(panes) { Pane(nextId++, request.trim()).also { panes.add(it) } }
        synchronized(p) { p.history.add(true to p.request) }
        ensureOrb(app)
        main.post { relayout(); startTicking() }
        startRun(app, p, apps)
        return p.id
    }

    /**
     * A follow-up for ONE pane. It joins that pane's own history only. When the pane is still working it is queued and goes
     * out as soon as the pane is free; when the pane already finished (or was stopped) it wakes the pane up again.
     */
    fun reply(c: Context, id: Int, text: String, apps: List<AppEntry>): String {
        val app = c.applicationContext
        ctx = app
        val t = text.trim()
        if (t.isEmpty()) return "Nothing to send."
        val p = synchronized(panes) { panes.firstOrNull { it.id == id } } ?: return "There is no task $id."
        synchronized(p) {
            if (p.running) { p.pending.add(t); p.status = "Working (${p.pending.size} follow-up queued)"; return "Queued for task $id." }
            if (p.history.lastOrNull()?.first == true) p.history.add(false to "(no answer yet)")   // it was stopped before it answered
            p.history.add(true to t)
            p.cancelled = false
            p.text = if (p.text.isBlank()) "You: $t" else p.text + "\n\nYou: " + t
        }
        startRun(app, p, apps)
        main.post { refresh() }
        return "Sent to task $id."
    }

    /** Runs the pane's request on its own thread, then any follow-ups that were queued, one after the other. */
    private fun startRun(app: Context, p: Pane, apps: List<AppEntry>) {
        p.running = true
        p.status = "Starting"
        p.thread = Thread({
            current.set(p)
            try {
                while (true) {
                    val asked = synchronized(p) { p.history.lastOrNull { it.first }?.second ?: p.request }
                    TaskService.begin(app, asked)
                    try { runOnce(app, p, apps) } finally { TaskService.end(app) }
                    val next = synchronized(p) { if (p.cancelled || p.pending.isEmpty()) null else p.pending.removeAt(0) }
                    if (next == null) break
                    synchronized(p) {
                        p.history.add(true to next)
                        p.text = if (p.text.isBlank()) "You: $next" else p.text + "\n\nYou: " + next
                    }
                }
            } finally {
                p.running = false
                current.remove()
                if (!p.cancelled) {
                    val first = p.text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(140)
                    OrbService.instance?.say("Task ${p.id} ${if (p.ok) "finished" else "had a problem"}. $first")
                }
                main.post { refresh() }
            }
        }, "split-task-${p.id}").also { it.isDaemon = true; it.start() }
    }

    private fun runOnce(app: Context, p: Pane, apps: List<AppEntry>) {
        try {
            val cfg = AssistantConfig.load(app)
            p.status = "Working"
            // Only this pane's own conversation goes to the AI: nothing from the other panes is in it.
            val hist = synchronized(p) { p.history.toList() }
            val answer: String = if (!cfg.configured) {
                Keyless.answer(app, hist.last { it.first }.second, apps)
            } else {
                Agent.respond(app, cfg.provider, cfg.key, cfg.activeModel, buildSystemPrompt(app, apps), hist, apps, onPartial = null).text
            }
            if (!p.cancelled) {
                val a = answer.ifBlank { "Done." }
                synchronized(p) { p.history.add(false to a); p.text = if (p.text.isBlank()) a else p.text + "\n\n" + a }
                p.status = "Finished"; p.ok = true
            }
        } catch (e: Exception) {
            if (!p.cancelled) {
                val m = e.message ?: "Something went wrong."
                synchronized(p) { p.history.add(false to "(That did not finish: $m)"); p.text = if (p.text.isBlank()) m else p.text + "\n\n" + m }
                p.status = "Problem"; p.ok = false
            }
        }
    }

    private fun stopPane(p: Pane) {
        p.cancelled = true
        synchronized(p) { p.pending.clear() }
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

    private fun navBarHeight(c: Context): Int {
        val id = c.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return maxOf(if (id > 0) c.resources.getDimensionPixelSize(id) else 0, dp(c, 10))
    }

    private fun hintText() = if (selected > 0) "Reply to task $selected" else "Add a new task (or “2: …” to answer task 2)"

    private fun relayout() {
        val c = ctx ?: return
        val list = synchronized(panes) { panes.toList() }
        val host = WebTask.overlayHost(c) ?: return
        val wm = host.first
        root?.let { r -> try { wm.removeView(r) } catch (e: Exception) { } }
        root = null; barInput = null
        if (selected > 0 && list.none { it.id == selected }) selected = 0
        if (list.isEmpty()) { ticking = false; main.removeCallbacks(tick); selected = 0; draft = ""; updateNotification(c); return }

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
        big.addView(buildBar(c), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, host.second,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        try { wm.addView(big, lp); root = big; rootLp = lp; hostWm = wm } catch (e: Exception) { root = null }
        // The orb must stay above the panes so it can be seen waking up.
        OrbService.instance?.raiseToFront()
        refresh()
        updateNotification(c)
    }

    /** The window takes the keyboard only while the person is typing, so it never steals focus from the app underneath otherwise. */
    private fun setFocusable(on: Boolean) {
        val r = root ?: return
        val lp = rootLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { hostWm?.updateViewLayout(r, lp) } catch (e: Exception) { }
    }

    /** The reply bar: types a new task, or answers the pane whose title was tapped. */
    private fun buildBar(c: Context): View {
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 2), dp(c, 5), dp(c, 2), navBarHeight(c))
        }
        val input = EditText(c).apply {
            hint = hintText(); setHintTextColor(Color.argb(120, 255, 255, 255)); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 2
            background = GradientDrawable().apply { setColor(Color.rgb(44, 44, 50)); cornerRadius = dp(c, 18).toFloat() }
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            setText(draft)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, a: Int) { }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) { }
                override fun afterTextChanged(s: Editable?) { draft = s?.toString().orEmpty() }
            })
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    setFocusable(true)
                    v.post { v.requestFocus(); (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
                }
                false
            }
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendFromBar(c); true } else false }
        }
        val send = TextView(c).apply {
            text = "Send"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.rgb(10, 132, 255)); cornerRadius = dp(c, 18).toFloat() }
            setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9))
            setOnClickListener { sendFromBar(c) }
        }
        val all = TextView(c).apply {
            text = "Close all"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.argb(200, 90, 90, 96)); cornerRadius = dp(c, 18).toFloat() }
            setPadding(dp(c, 10), dp(c, 9), dp(c, 10), dp(c, 9))
            setOnClickListener { closeAll() }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(c, 6) })
        bar.addView(all, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(c, 6) })
        barInput = input
        shownHint = input.hint.toString()
        return bar
    }

    private fun sendFromBar(c: Context) {
        val input = barInput ?: return
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val target = selected
        input.setText(""); draft = ""
        try { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (e: Exception) { }
        setFocusable(false)
        val app = c.applicationContext
        Thread {
            val apps = OrbBridge.apps()
            val msg = try {
                if (target > 0 && !isClose(t)) reply(app, target, t, apps) else handle(app, t, apps, spoken = false, force = true)
            } catch (e: Exception) { null }
            if (!msg.isNullOrBlank()) main.post { try { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } catch (e: Exception) { } }
        }.start()
    }

    private fun buildPane(c: Context, p: Pane, total: Int): View {
        val small = total > 4
        val tiny = total > 9
        val textSp = when { tiny -> 9f; small -> 10f; total > 2 -> 12f; else -> 14f }
        val accent = longArrayOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFF375F, 0xFF64D2FF, 0xFFFFD60A, 0xFFAC8E68, 0xFF8E8E93)[(p.id - 1) % 9].toInt()
        p.accent = accent

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(28, 28, 32)); cornerRadius = dp(c, 12).toFloat(); setStroke(dp(c, 2), accent) }
            setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4))
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(c).apply {
            text = "${p.id} · ${p.request}"; setTextColor(accent); textSize = textSp; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            // Tap the title to answer THIS pane from the reply bar (tap again to go back to \"new task\").
            setOnClickListener { selected = if (selected == p.id) 0 else p.id; refresh() }
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
        p.statusView = status; p.bodyView = body; p.scroll = scroll; p.stopView = stop; p.box = box; p.shownSel = false
        return box
    }

    private fun refresh() {
        val c = ctx
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
            val sel = selected == p.id
            if (c != null && sel != p.shownSel) {
                p.shownSel = sel
                (p.box?.background as? GradientDrawable)?.setStroke(dp(c, if (sel) 5 else 2), p.accent)
            }
        }
        val h = hintText()
        if (h != shownHint) { shownHint = h; barInput?.hint = h }
        // The notification only changes when a pane does (not every tick).
        val sig = list.joinToString("|") { "${it.id}:${it.running}:${it.status}" }
        if (c != null && sig != lastSig) { lastSig = sig; updateNotification(c) }
    }

    // ------------------------------------------------------------------ notification panel

    private const val NCH = "neo_split_tasks"
    private const val NID = 4730
    const val ACTION_REPLY = "com.neo.plus.iosluancher.SPLIT_REPLY"
    const val ACTION_CLOSE_ALL = "com.neo.plus.iosluancher.SPLIT_CLOSE_ALL"
    const val EXTRA_KEY = "split_reply_text"

    /**
     * One ongoing notification lists every task page and carries a "Reply" box: type a new task there, or "2: follow-up" to answer
     * task 2 only. It disappears when the last page is closed. Posting it again also clears the reply spinner after a reply.
     */
    fun updateNotification(c: Context? = ctx) {
        val app = (c ?: return).applicationContext
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val list = synchronized(panes) { panes.toList() }
            if (list.isEmpty()) { nm.cancel(NID); return }
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(NCH) == null) {
                nm.createNotificationChannel(NotificationChannel(NCH, "Task pages", NotificationManager.IMPORTANCE_LOW))
            }
            val working = list.count { it.running }
            val inbox = Notification.InboxStyle()
            list.take(9).forEach { inbox.addLine("${it.id} · ${it.request.take(40)} — ${if (it.running) "working" else it.status.lowercase(Locale.ROOT)}") }
            if (list.size > 9) inbox.setSummaryText("+${list.size - 9} more pages")
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val replyPi = PendingIntent.getBroadcast(app, 1, Intent(app, SplitReplyReceiver::class.java).setAction(ACTION_REPLY), PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            val closePi = PendingIntent.getBroadcast(app, 2, Intent(app, SplitReplyReceiver::class.java).setAction(ACTION_CLOSE_ALL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val reply = Notification.Action.Builder(Icon.createWithResource(app, android.R.drawable.ic_menu_send), "Reply", replyPi)
                .addRemoteInput(RemoteInput.Builder(EXTRA_KEY).setLabel("New task, or “2: …” to answer task 2").build()).build()
            val close = Notification.Action.Builder(Icon.createWithResource(app, android.R.drawable.ic_menu_close_clear_cancel), "Close all", closePi).build()
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, NCH) else @Suppress("DEPRECATION") Notification.Builder(app)
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("${list.size} task page${if (list.size == 1) "" else "s"} · $working working")
                .setContentText("Reply here to add a task or answer one")
                .setStyle(inbox).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(reply).addAction(close)
            nm.notify(NID, b.build())
        } catch (e: Exception) { }
    }

}

/** The notification panel's "Reply" box and "Close all" button for the task pages. */
class SplitReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        SplitTasks.attach(app)
        when (intent.action) {
            SplitTasks.ACTION_CLOSE_ALL -> SplitTasks.closeAll()
            SplitTasks.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SplitTasks.EXTRA_KEY)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val msg = try { SplitTasks.handle(app, text, OrbBridge.apps(), spoken = false, force = true) } catch (e: Exception) { null }
                    if (!msg.isNullOrBlank()) try { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
                }
                SplitTasks.updateNotification(app)   // posting again stops the reply spinner
            }
        }
    }
}
