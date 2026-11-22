package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

/** Shared task activity feed. */
object HudState {
    val history = mutableStateListOf<String>()
    @Volatile var lastAt = 0L
    @Volatile var current = ""
    private val main = Handler(Looper.getMainLooper())

    fun event(ctx: Context, text: String) {
        val clean = text.trim().replace('\n', ' ').take(90)
        if (clean.isEmpty()) return
        current = clean
        lastAt = System.currentTimeMillis()
        main.post {
            history.add(clean)
            while (history.size > 30) history.removeAt(0)
        }
        // Activity feed is retained for the NEO PLUS holographic dashboard and task history.
    }

    fun describe(name: String, args: JSONObject): String {
        val detail = listOf("app", "to", "label", "describe", "text", "query", "url", "site", "goal", "title")
            .map { args.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(50)
        val verb = when (name) {
            "open_app" -> "Opening"
            "call" -> "Calling"
            "message", "whatsapp", "messenger" -> "Messaging"
            "open_url" -> "Opening address"
            "ui_tap", "ui_tap_visual" -> "Tapping"
            "ui_type" -> "Typing"
            "ui_search", "web_search" -> "Searching"
            "ui_scroll" -> "Scrolling"
            "ui_back" -> "Going back"
            "web_task" -> "Website task"
            "web_batch" -> "Website batch"
            "app_task" -> "App task"
            "settings" -> "Opening settings"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return if (detail.isEmpty()) verb else "$verb: $detail"
    }
}

/**
 * NEO PLUS live task HUD. The full-screen visual layer is click-through so the phone remains usable.
 * A separate narrow task rail receives taps only over its cards. Selecting a card changes TaskLog.focusId;
 * it never calls stop(), pause(), resume(), or any task execution callback.
 */
object HudOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var visual: NeoHudView? = null
    private var rail: NeoHudTaskRailView? = null
    private var wm: WindowManager? = null
    private var railWm: WindowManager? = null
    private var ticking = false
    private var idleSince = 0L

    fun enabled(c: Context): Boolean = c.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("hud_enabled", true) // NEO PLUS holographic HUD; task engine remains independent.

    fun overApps(c: Context): Boolean =
        c.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("hud_over_apps", true)

    /** Temporarily suppresses only the visual layer while the accessibility service captures the real screen. */
    @Volatile var shotHold = false

    fun start(c: Context) {
        if (!enabled(c)) return
        val context = c.applicationContext
        main.post {
            app = context
            if (!ticking) {
                ticking = true
                main.post(tick)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { sync() } catch (_: Throwable) { }
            // 250ms is sufficient for task progress and avoids the old 50ms redraw loop.
            main.postDelayed(this, 250L)
        }
    }

    private fun busy(): Boolean =
        TaskLog.activeCount() > 0 || TaskService.running() ||
            System.currentTimeMillis() - HudState.lastAt < 5_000L

    private fun sync() {
        val c = app ?: return
        if (!enabled(c)) {
            removeAll()
            ticking = false
            main.removeCallbacks(tick)
            return
        }
        if (!busy()) {
            if (idleSince == 0L) idleSince = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() - idleSince > 1_200L) {
                removeAll()
                ticking = false
                main.removeCallbacks(tick)
            }
            return
        }
        idleSince = 0L
        val elsewhere = userElsewhere()
        if (elsewhere && !overApps(c) && TaskLog.visible().none { it.active && it.waiting }) {
            removeAll()
            return
        }
        if (visual == null) addVisual(c)
        val active = TaskLog.visible().filter { it.active }
        val focused = TaskLog.focus ?: active.lastOrNull()
        val current = focused ?: active.lastOrNull()
        val others = TaskLog.visible().filter { it.id != current?.id }
        visual?.update(current, elsewhere, if (elsewhere) foregroundLabel(c) else "")
        syncRail(c, others)
    }

    private var foregroundPackage: String? = null
    private val appLabels = HashMap<String, String>()
    private var foregroundCheckedAt = 0L
    private var awaySince = 0L
    private var away = false

    private fun userElsewhere(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - foregroundCheckedAt < 400L) return away
        foregroundCheckedAt = now
        val pkg = try { Verify.fg() } catch (_: Throwable) { null }
        foregroundPackage = pkg
        val other = pkg != null && pkg != app?.packageName &&
            pkg != "com.android.systemui" && !pkg.contains("launcher", ignoreCase = true)
        if (other) {
            if (awaySince == 0L) awaySince = now
            away = now - awaySince > 700L
        } else {
            awaySince = 0L
            away = false
        }
        return away
    }

    private fun foregroundLabel(c: Context): String {
        val pkg = foregroundPackage ?: return ""
        return appLabels.getOrPut(pkg) {
            try {
                val pm = c.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) { pkg.substringAfterLast('.') }
        }
    }

    private fun addVisual(c: Context) {
        val host = WebTask.overlayHost(c) ?: return
        val view = NeoHudView(c)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            host.second,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        try {
            host.first.addView(view, params)
            visual = view
            wm = host.first
        } catch (_: Throwable) {
            visual = null
        }
    }

    private fun syncRail(c: Context, tasks: List<TaskFeed>) {
        // Keep every task in the rail; the rail itself provides vertical scrolling.
        val shown = tasks.filter { !it.dismissed }
        if (shown.isEmpty()) {
            removeRail()
            return
        }
        val host = WebTask.overlayHost(c) ?: return
        if (rail == null) {
            val density = c.resources.displayMetrics.density
            val v = NeoHudTaskRailView(c)
            val screenHeight = c.resources.displayMetrics.heightPixels
            val railHeight = min((screenHeight * 0.54f).toInt(), (430f * density).toInt())
            val params = WindowManager.LayoutParams(
                (136f * density).toInt(),
                railHeight,
                host.second,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                x = (4f * density).toInt()
                y = 0
            }
            try {
                host.first.addView(v, params)
                rail = v
                railWm = host.first
            } catch (_: Throwable) {
                rail = null
            }
        }
        rail?.update(shown)
    }

    private fun removeRail() {
        val v = rail ?: return
        try { railWm?.removeView(v) } catch (_: Throwable) { }
        rail = null
    }

    private fun removeAll() {
        removeRail()
        val v = visual ?: return
        try { wm?.removeView(v) } catch (_: Throwable) { }
        visual = null
    }
}

/** Full-screen NEO PLUS holographic dashboard. The visual layer remains click-through; task cards are independently selectable. */
private class NeoHudView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    }
    private val panel = Path()
    private var feed: TaskFeed? = null
    private var elsewhere = false
    private var appLabel = ""
    private var battery = 0.85f
    private var online = true
    private var telemetryAt = 0L
    private val born = SystemClock.uptimeMillis()

    fun update(task: TaskFeed?, away: Boolean, label: String) {
        feed = task
        elsewhere = away
        appLabel = label
        invalidate()
    }

    private fun refreshTelemetry() {
        val now = SystemClock.uptimeMillis()
        if (now - telemetryAt < 2_000L) return
        telemetryAt = now
        try {
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 85)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                battery = (level.toFloat() / scale).coerceIn(0f, 1f)
            }
        } catch (_: Throwable) { }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            online = cm.activeNetworkInfo?.isConnected ?: true
        } catch (_: Throwable) { }
    }

    private fun color(r: Int, g: Int, b: Int, alpha: Int = 255) = Color.argb(alpha, r, g, b)

    private fun label(cv: Canvas, value: String, x: Float, y: Float, size: Float, c: Int, bold: Boolean = false) {
        text.color = c
        text.textSize = size
        text.typeface = android.graphics.Typeface.create(
            "sans-serif",
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        cv.drawText(value.take(64), x, y, text)
    }

    private fun panel(cv: Canvas, rect: RectF, alpha: Int = 150) {
        paint.style = Paint.Style.FILL
        paint.color = color(3, 18, 34, alpha)
        cv.drawRoundRect(rect, 14f * density, 14f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = color(24, 205, 255, 220)
        cv.drawRoundRect(rect, 14f * density, 14f * density, paint)
        paint.style = Paint.Style.FILL
    }

    private fun ring(cv: Canvas, cx: Float, cy: Float, radius: Float, phase: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = color(20, 195, 255, 175)
        cv.drawCircle(cx, cy, radius, paint)
        paint.strokeWidth = 1f * density
        paint.color = color(125, 70, 255, 155)
        cv.drawCircle(cx, cy, radius * 0.84f, paint)
        paint.color = color(20, 220, 255, 110)
        cv.drawArc(
            RectF(cx - radius * 1.12f, cy - radius * 1.12f, cx + radius * 1.12f, cy + radius * 1.12f),
            phase * 45f, 105f, false, paint
        )
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (HudOverlay.shotHold) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        refreshTelemetry()
        val d = density
        val now = SystemClock.uptimeMillis()
        val phase = (now - born) / 1_000f
        // Fully opaque helmet-dark canvas: underlying app windows are never shown through the HUD.
        // App work may continue in the background, but the user sees only NEO PLUS virtual tasks.
        canvas.drawColor(color(0, 3, 12, 255))

        // Top identity and live device status.
        label(canvas, "NEO PLUS", 18f * d, 34f * d, 22f * d, color(44, 220, 255), true)
        label(canvas, "AI ASSISTANT  •  LIVE TASK HUD", 19f * d, 53f * d, 9f * d, color(160, 230, 255, 220))
        val status = if (online) "ONLINE" else "OFFLINE"
        label(canvas, status, w - 82f * d, 32f * d, 11f * d, color(if (online) 60 else 255, if (online) 245 else 90, 110), true)
        label(canvas, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), w - 82f * d, 49f * d, 10f * d, color(190, 225, 255))

        val task = feed
        val left = 14f * d
        val top = 76f * d
        val mainW = w * 0.58f
        val taskRect = RectF(left, top, left + mainW, top + 188f * d)
        panel(canvas, taskRect, 180)
        label(canvas, "CURRENT TASK", taskRect.left + 12f * d, taskRect.top + 22f * d, 11f * d, color(38, 224, 255), true)
        val title = task?.title?.ifBlank { "NEO PLUS" } ?: "NEO PLUS"
        label(canvas, title, taskRect.left + 12f * d, taskRect.top + 49f * d, 14f * d, Color.WHITE, true)
        val statusText = when {
            task == null -> "Standing by"
            task.waiting -> "WAITING FOR YOUR INPUT"
            task.active -> task.status.ifBlank { "Working..." }
            else -> task.status.ifBlank { "Finished" }
        }
        label(canvas, statusText, taskRect.left + 12f * d, taskRect.top + 68f * d, 10f * d,
            color(if (task?.waiting == true) 255 else 65, if (task?.waiting == true) 190 else 225, 255), true)

        if (task != null && task.total > 0) {
            val fraction = ((task.done + task.failed).toFloat() / task.total).coerceIn(0f, 1f)
            val bar = RectF(taskRect.left + 12f * d, taskRect.top + 82f * d, taskRect.right - 12f * d, taskRect.top + 90f * d)
            paint.color = color(35, 75, 105, 210)
            canvas.drawRoundRect(bar, 5f * d, 5f * d, paint)
            bar.right = bar.left + bar.width() * fraction
            paint.color = color(0, 215, 255)
            canvas.drawRoundRect(bar, 5f * d, 5f * d, paint)
            label(canvas, (fraction * 100).toInt().toString() + "%  •  " + task.done + "/" + task.total,
                taskRect.left + 12f * d, taskRect.top + 107f * d, 10f * d, Color.WHITE)
        } else {
            label(canvas, if (task?.active == true) "RUNNING IN REAL TIME" else "NO ACTIVE TASK",
                taskRect.left + 12f * d, taskRect.top + 103f * d, 10f * d, color(60, 220, 255), true)
        }

        // Most recent real steps from this task only.
        val recent = task?.lines?.takeLast(3).orEmpty()
        recent.forEachIndexed { index, line ->
            val c = when (line.kind) {
                TaskLine.Kind.ERROR, TaskLine.Kind.WARN -> color(255, 190, 65)
                TaskLine.Kind.OK, TaskLine.Kind.FIX -> color(80, 255, 190)
                else -> color(190, 225, 255)
            }
            label(canvas, "› " + line.text, taskRect.left + 12f * d,
                taskRect.top + (132f + index * 16f) * d, 9f * d, c)
        }

        // Central NEO PLUS smoke orb: soft fluid-like wisps, never waveform bars.
        val cx = w * 0.51f
        val cy = h * 0.48f
        val radius = min(w * 0.25f, h * 0.145f)
        ring(canvas, cx, cy, radius, phase)
        val orb = RectF(cx - radius * 0.72f, cy - radius * 0.72f, cx + radius * 0.72f, cy + radius * 0.72f)
        paint.style = Paint.Style.FILL
        paint.color = color(2, 15, 35, 220)
        canvas.drawCircle(cx, cy, radius * 0.70f, paint)
        // Layered translucent curved wisps create a calm smoke effect; animation follows the existing HUD phase.
        for (i in 0 until 9) {
            val a = phase * (if (i % 2 == 0) 0.22f else -0.16f) + i * 0.698f
            val drift = sin(phase * 0.7f + i * 0.91f)
            val x1 = cx + kotlin.math.cos(a) * radius * (0.12f + (i % 3) * 0.10f)
            val y1 = cy + kotlin.math.sin(a) * radius * (0.16f + (i % 4) * 0.07f)
            val x2 = cx + kotlin.math.cos(a + 1.35f) * radius * (0.34f + drift * 0.08f)
            val y2 = cy + kotlin.math.sin(a + 1.35f) * radius * (0.34f + drift * 0.08f)
            val smoke = Path().apply {
                moveTo(x1, y1)
                cubicTo(
                    x1 + radius * 0.30f * sin(a + 0.7f), y1 - radius * 0.24f,
                    x2 - radius * 0.24f, y2 + radius * 0.30f * sin(a),
                    x2, y2
                )
            }
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (7f + (i % 3) * 3f) * d
            paint.color = if (i % 2 == 0) color(55, 205, 255, 62) else color(145, 95, 255, 54)
            canvas.drawPath(smoke, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color(80, 195, 255, 36)
        canvas.drawCircle(cx, cy, radius * 0.34f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = color(100, 225, 255, 170)
        canvas.drawCircle(cx, cy, radius * 0.70f, paint)
        paint.style = Paint.Style.FILL
        label(canvas, "NEO PLUS", cx - 29f * d, cy + radius + 20f * d, 10f * d, color(85, 230, 255), true)

        // Real device telemetry.
        val sys = RectF(14f * d, h - 142f * d, w * 0.43f, h - 48f * d)
        panel(canvas, sys, 170)
        label(canvas, "SYSTEM STATUS", sys.left + 11f * d, sys.top + 19f * d, 10f * d, color(40, 220, 255), true)
        label(canvas, "BATTERY", sys.left + 11f * d, sys.top + 43f * d, 9f * d, color(180, 220, 255))
        label(canvas, (battery * 100).toInt().toString() + "%", sys.left + 70f * d, sys.top + 43f * d, 10f * d, Color.WHITE, true)
        label(canvas, "NETWORK", sys.left + 11f * d, sys.top + 65f * d, 9f * d, color(180, 220, 255))
        label(canvas, if (online) "CONNECTED" else "OFFLINE", sys.left + 70f * d, sys.top + 65f * d, 9f * d,
            color(if (online) 70 else 255, if (online) 245 else 90, 140), true)
        label(canvas, "VIRTUAL TASKS ONLY",
            sys.left + 11f * d, sys.top + 84f * d, 8f * d, color(120, 220, 255))

        // Recent activity status at the bottom right.
        val activity = RectF(w * 0.55f, h - 142f * d, w - 14f * d, h - 48f * d)
        panel(canvas, activity, 170)
        label(canvas, "TASK CONTROL", activity.left + 11f * d, activity.top + 19f * d, 10f * d, color(40, 220, 255), true)
        label(canvas, TaskLog.activeCount().toString() + " TASK(S) RUNNING",
            activity.left + 11f * d, activity.top + 43f * d, 10f * d, Color.WHITE, true)
        label(canvas, "BACKGROUND TASKS CONTINUE",
            activity.left + 11f * d, activity.top + 64f * d, 8f * d, color(80, 245, 190))
        label(canvas, "Select a card to switch focus",
            activity.left + 11f * d, activity.top + 83f * d, 8f * d, color(175, 220, 255))

        // No soundwave bars: the smoke orb is the sole voice-reactive visual.

    }
}

/** Narrow, touchable task rail. Only this small region intercepts touches; the rest of the phone stays usable. */
private class NeoHudTaskRailView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private var tasks: List<TaskFeed> = emptyList()
    private var cardHeight = 68f * density
    private var gap = 8f * density
    private var scrollOffset = 0
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    fun update(items: List<TaskFeed>) {
        tasks = items
        invalidate()
    }

    private fun drawText(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        text.color = color
        text.textSize = size
        text.typeface = android.graphics.Typeface.create(
            "sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        canvas.drawText(value.take(20), x, y, text)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density
        val left = 4f * d
        val right = width - 4f * d
        val stride = cardHeight + gap
        val visibleCount = ((height - 8f * d) / stride).toInt().coerceAtLeast(1)
        val maxOffset = (tasks.size - visibleCount).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)
        val visible = tasks.drop(scrollOffset).take(visibleCount)
        visible.forEachIndexed { index, task ->
            val top = 4f * d + index * stride
            val rect = RectF(left, top, right, top + cardHeight)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 4, 22, 42)
            canvas.drawRoundRect(rect, 11f * d, 11f * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * d
            paint.color = Color.argb(240, 25, 205, 255)
            canvas.drawRoundRect(rect, 11f * d, 11f * d, paint)
            paint.style = Paint.Style.FILL
            val state = when {
                task.waiting -> "NEEDS YOU"
                task.active -> "RUNNING"
                else -> "FINISHED"
            }
            drawText(canvas, task.title.ifBlank { "Task " + task.id }, rect.left + 8f * d, rect.top + 19f * d,
                9f * d, Color.WHITE, true)
            drawText(canvas, state, rect.left + 8f * d, rect.top + 37f * d, 8f * d,
                if (task.waiting) Color.YELLOW else if (task.active) Color.rgb(60, 245, 180) else Color.LTGRAY, true)
            val progress = if (task.total > 0) ((task.done + task.failed).toFloat() / task.total).coerceIn(0f, 1f) else 0f
            val bar = RectF(rect.left + 8f * d, rect.bottom - 13f * d, rect.right - 8f * d, rect.bottom - 8f * d)
            paint.color = Color.argb(150, 60, 105, 145)
            canvas.drawRoundRect(bar, 3f * d, 3f * d, paint)
            if (task.total > 0) {
                bar.right = bar.left + bar.width() * progress
                paint.color = Color.rgb(0, 215, 255)
                canvas.drawRoundRect(bar, 3f * d, 3f * d, paint)
            }
        }
        if (tasks.size > visibleCount) {
            drawText(canvas, "${scrollOffset + 1}-${(scrollOffset + visibleCount).coerceAtMost(tasks.size)}/${tasks.size}  SWIPE",
                8f * d, height - 4f * d, 7f * d, Color.CYAN, true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stride = cardHeight + gap
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - lastY
                if (kotlin.math.abs(event.y - downY) > 8f * density) dragging = true
                if (dragging) {
                    val visibleCount = ((height - 8f * density) / stride).toInt().coerceAtLeast(1)
                    val maxOffset = (tasks.size - visibleCount).coerceAtLeast(0)
                    scrollOffset = (scrollOffset - (delta / stride).toInt()).coerceIn(0, maxOffset)
                    invalidate()
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && event.y >= 4f * density) {
                    val visibleIndex = ((event.y - 4f * density) / stride).toInt()
                    val task = tasks.getOrNull(scrollOffset + visibleIndex)
                    if (task != null) {
                        // Focus changes presentation only. The task's worker and callbacks are untouched.
                        TaskLog.focusId = task.id
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
