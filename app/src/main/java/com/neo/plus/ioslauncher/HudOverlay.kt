package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** What the assistant is doing right now, in plain words. Feeds the live HUD and the dashboard's intelligence feed. */
object HudState {
    val history = mutableStateListOf<String>()
    @Volatile var lastAt = 0L
    @Volatile var current = ""
    private val main = Handler(Looper.getMainLooper())

    fun event(ctx: Context, text: String) {
        val t = text.trim().replace('\n', ' ').take(90)
        if (t.isEmpty()) return
        current = t
        lastAt = System.currentTimeMillis()
        main.post { history.add(t); while (history.size > 30) history.removeAt(0) }
        HudOverlay.start(ctx)
    }

    /** "ui_tap {label: Search}" -> "Tapping Search" style sentence for any action. */
    fun describe(name: String, a: JSONObject): String {
        val detail = listOf("app", "to", "label", "describe", "text", "query", "url", "site", "goal", "title")
            .map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(50)
        val verb = when (name) {
            "open_app" -> "Opening"; "call" -> "Calling"; "message", "whatsapp", "messenger" -> "Messaging"
            "open_url" -> "Opening address"; "ui_tap", "ui_tap_visual" -> "Tapping"; "ui_type" -> "Typing"
            "ui_search" -> "Searching"; "ui_scroll" -> "Scrolling"; "ui_back" -> "Going back"
            "web_task" -> "Website task"; "web_batch" -> "Website batch"; "app_task" -> "App task"
            "web_search" -> "Searching the web"; "settings" -> "Opening settings"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return if (detail.isEmpty()) verb else "$verb: $detail"
    }
}

/**
 * Iron Man Jarvis-style heads-up display drawn over the screen while the assistant works or opens things:
 * clock, system diagnostics, enlarged centered gold/cyan telemetry rings with live internal operations,
 * and a live terminal read-out of real steps.
 */
object HudOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var view: NeoHudView? = null
    private var wm: WindowManager? = null
    private var ticking = false
    private var idleSince = 0L

    fun enabled(c: Context): Boolean =
        c.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("hud", true)

    fun start(c: Context) {
        if (!enabled(c)) return
        val app = c.applicationContext
        main.post { ctx = app; if (!ticking) { ticking = true; main.post(tick) } }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { sync() } catch (_: Throwable) { }
            main.postDelayed(this, 50)
        }
    }

    private fun busy(): Boolean =
        TaskLog.activeCount() > 0 || TaskService.running() || System.currentTimeMillis() - HudState.lastAt < 5000

    private fun sync() {
        val c = ctx ?: return
        if (!enabled(c)) { remove(); ticking = false; return }
        if (busy()) {
            idleSince = 0L
            if (userElsewhere() && TaskLog.visible().none { it.active && it.waiting }) { remove(); return }
            if (view == null) add(c)
            val v = view ?: return
            val active = TaskLog.visible().filter { it.active }
            val rows = ArrayList<Pair<Int, String>>()
            var title = "NEO PLUS"
            var progress = -1f
            var alert = false
            if (active.isNotEmpty()) {
                val f = active.last()
                title = f.title.ifBlank { "Working" }.take(48)
                if (f.total > 0) progress = (f.done.toFloat() / f.total).coerceIn(0f, 1f)
                alert = f.waiting
                f.lines.takeLast(6).forEach { l ->
                    rows += (when (l.kind) {
                        TaskLine.Kind.ERROR, TaskLine.Kind.WARN -> 2
                        TaskLine.Kind.OK, TaskLine.Kind.FIX -> 1
                        else -> 0
                    }) to l.text
                }
                if (f.status.isNotBlank()) rows += 3 to f.status
            } else {
                HudState.history.takeLast(6).forEach { rows += 0 to it }
                if (TaskService.running()) {
                    title = TaskService.currentLabel().take(48)
                    rows += 3 to "ACTIVE PROCESS... STANDBY"
                }
            }
            v.update(title, rows, progress, alert)
        } else {
            if (idleSince == 0L) idleSince = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() - idleSince > 1200) {
                remove(); ticking = false; main.removeCallbacks(tick)
            }
        }
    }

    private var fgCheckedAt = 0L
    private var awaySince = 0L
    private var away = false

    private fun userElsewhere(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - fgCheckedAt < 400) return away
        fgCheckedAt = now
        val pkg = try { Verify.fg() } catch (_: Throwable) { null }
        val other = pkg != null && pkg != ctx?.packageName && pkg != "com.android.systemui" &&
            !pkg.contains("launcher", ignoreCase = true)
        if (other) {
            if (awaySince == 0L) awaySince = now
            away = now - awaySince > 700
        } else {
            awaySince = 0L; away = false
        }
        return away
    }

    private fun add(c: Context) {
        val host = WebTask.overlayHost(c) ?: return
        val v = NeoHudView(c)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, host.second,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        try { host.first.addView(v, lp); view = v; wm = host.first } catch (_: Throwable) { view = null }
    }

    private fun remove() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Throwable) { }
        view = null
    }
}

/**
 * Custom Jarvis HUD Canvas:
 * - Top-left clock + System Diagnostics box (Power, Link, Core Temp)
 * - Enlarged centered gold/cyan telemetry ring with live internal operations
 * - Bottom terminal command feed + progress bar
 */
private class NeoHudView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val cyan = Color.rgb(34, 229, 255)
    private val cyanDeep = Color.rgb(10, 110, 145)
    private val gold = Color.rgb(245, 194, 66)
    private val red = Color.rgb(255, 69, 58)
    private val green = Color.rgb(48, 209, 88)
    private val amber = Color.rgb(255, 200, 40)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val oval = RectF()
    private val clipPath = Path()
    private val panelPath = Path()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val secFmt = SimpleDateFormat("ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE d MMM yyyy", Locale.getDefault())

    private var title = "NEO PLUS"
    private var rows: List<Pair<Int, String>> = emptyList()
    private var progress = -1f
    private var alert = false
    private var newest = ""
    private var newestAt = SystemClock.uptimeMillis()
    private val born = SystemClock.uptimeMillis()

    // Cached device telemetry (polled every ~2s so onDraw stays fast)
    private var lastTelAt = 0L
    private var batteryPct = 0.85f
    private var coreTempNorm = 0.48f

    fun update(t: String, r: List<Pair<Int, String>>, prog: Float, al: Boolean) {
        title = t; rows = r; progress = prog; alert = al
        val last = r.lastOrNull()?.second.orEmpty()
        if (last != newest) { newest = last; newestAt = SystemClock.uptimeMillis() }
        invalidate()
    }

    private fun refreshTelemetry() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTelAt < 2000L) return
        lastTelAt = now
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 85)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                batteryPct = (lvl.toFloat() / scale).coerceIn(0.05f, 1f)
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 340)
                // Normalize 20C..50C into 0.15..0.95
                coreTempNorm = (((tempTenths / 10f) - 20f) / 30f).coerceIn(0.15f, 0.95f)
            }
        } catch (_: Throwable) { }
    }

    private fun col(base: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        refreshTelemetry()

        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        val fade = (((now - born) / 350f).coerceIn(0f, 1f) * 255).toInt()
        val bad = rows.any { it.first == 2 } || alert

        // 1. Dark atmospheric HUD backdrop + bottom gradient veil
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb((120 * fade / 255), 2, 10, 18),
                Color.argb((95 * fade / 255), 3, 16, 26),
                Color.argb((205 * fade / 255), 2, 8, 16)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // 2. Corner HUD brackets
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.color = col(cyan, 190 * fade / 255)
        val m = 12f * d; val L = 30f * d
        cv.drawLine(m, m + L, m, m, p); cv.drawLine(m, m, m + L, m, p)
        cv.drawLine(w - m - L, m, w - m, m, p); cv.drawLine(w - m, m, w - m, m + L, p)
        cv.drawLine(m, h - m - L, m, h - m, p); cv.drawLine(m, h - m, m + L, h - m, p)
        cv.drawLine(w - m - L, h - m, w - m, h - m, p); cv.drawLine(w - m, h - m, w - m, h - m - L, p)

        // 3. Top-left clock + System Diagnostics box
        val top = 46f * d
        val leftX = 22f * d
        tp.style = Paint.Style.FILL; tp.color = col(cyan, 235 * fade / 255); tp.textSize = 40f * d
        val date = Date()
        val hm = timeFmt.format(date)
        cv.drawText(hm, leftX, top + 34f * d, tp)
        val hmW = tp.measureText(hm)
        tp.textSize = 14f * d; cv.drawText(secFmt.format(date), leftX + 4f * d + hmW, top + 34f * d, tp)
        tp.textSize = 10.5f * d; tp.color = col(cyan, 175 * fade / 255)
        cv.drawText(dateFmt.format(date).uppercase(Locale.getDefault()), leftX + 2f * d, top + 51f * d, tp)

        drawSystemDiagnostics(cv, leftX, top + 60f * d, fade, t)

        // 4. ENLARGED CENTER JARVIS RING + LIVE INTERNAL OPERATIONS
        val cx = w * 0.50f
        val cy = h * 0.43f
        val r = minOf(w, h) * 0.34f
        drawCenterJarvisHud(cv, cx, cy, r, t, fade, bad)

        // 5. Bottom terminal feed (real live steps + progress bar)
        drawTerminalFeed(cv, w, h, t, fade, bad)
    }

    private fun drawSystemDiagnostics(cv: Canvas, x: Float, y: Float, fade: Int, t: Float) {
        val boxW = 168f * d
        val boxH = 74f * d
        val cut = 10f * d

        panelPath.reset()
        panelPath.moveTo(x, y)
        panelPath.lineTo(x + boxW - cut, y)
        panelPath.lineTo(x + boxW, y + cut)
        panelPath.lineTo(x + boxW, y + boxH)
        panelPath.lineTo(x + cut, y + boxH)
        panelPath.lineTo(x, y + boxH - cut)
        panelPath.close()

        p.style = Paint.Style.FILL
        p.color = Color.argb((110 * fade / 255), 5, 24, 36)
        cv.drawPath(panelPath, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = col(cyan, 160 * fade / 255)
        cv.drawPath(panelPath, p)

        tp.style = Paint.Style.FILL
        tp.textSize = 9.5f * d
        tp.color = col(cyan, 220 * fade / 255)
        cv.drawText("SYSTEM DIAGNOSTICS", x + 8f * d, y + 14f * d, tp)

        val linkFill = (0.45f + 0.25f * sin(t * 2.2f)).coerceIn(0.2f, 0.95f)
        val bars = listOf(
            Triple("POWER", batteryPct, green),
            Triple("LINK", linkFill, cyan),
            Triple("CORE TEMP", coreTempNorm, if (coreTempNorm > 0.78f) amber else green)
        )
        bars.forEachIndexed { idx, (label, pct, barCol) ->
            val rowY = y + 29f * d + idx * 15f * d
            tp.textSize = 8.5f * d
            tp.color = col(cyan, 195 * fade / 255)
            cv.drawText(label, x + 8f * d, rowY, tp)

            val bx = x + 68f * d
            val bw = boxW - 78f * d
            val bh = 6f * d
            oval.set(bx, rowY - bh, bx + bw, rowY)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * d
            p.color = col(cyan, 120 * fade / 255)
            cv.drawRoundRect(oval, 3f * d, 3f * d, p)

            oval.set(bx + 1f * d, rowY - bh + 1f * d, bx + 1f * d + (bw - 2f * d) * pct, rowY - 1f * d)
            p.style = Paint.Style.FILL
            p.color = col(barCol, 225 * fade / 255)
            cv.drawRoundRect(oval, 2f * d, 2f * d, p)
        }

        tp.textSize = 8.5f * d
        tp.color = col(if (alert) amber else green, 220 * fade / 255)
        cv.drawText(if (alert) "STATUS: AWAITING INPUT" else "NETWORK: ONLINE · ACTIVE", x + 4f * d, y + boxH + 13f * d, tp)
    }

    private fun drawCenterJarvisHud(
        cv: Canvas, cx: Float, cy: Float, r: Float, t: Float, fade: Int, bad: Boolean
    ) {
        val accent = if (bad) red else cyan

        // Subtle radial ambient glow behind the ring
        p.style = Paint.Style.FILL
        p.color = col(cyanDeep, 55 * fade / 255)
        cv.drawCircle(cx, cy, r * 1.12f, p)

        // Outer thin structural ring + diagonal crosshair rays
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * d
        p.color = col(accent, 110 * fade / 255)
        cv.drawCircle(cx, cy, r * 1.08f, p)
        for (deg in listOf(35.0, 145.0, 215.0, 325.0)) {
            val rad = Math.toRadians(deg)
            cv.drawLine(
                cx + (cos(rad) * r * 0.98f).toFloat(),
                cy + (sin(rad) * r * 0.98f).toFloat(),
                cx + (cos(rad) * r * 1.24f).toFloat(),
                cy + (sin(rad) * r * 1.24f).toFloat(),
                p
            )
        }

        // Gold metallic segmented outer bevel ring (signature Iron Man HUD ring)
        p.strokeWidth = 7.5f * d
        p.strokeCap = Paint.Cap.BUTT
        oval.set(cx - r * 0.94f, cy - r * 0.94f, cx + r * 0.94f, cy + r * 0.94f)
        p.color = col(gold, 175 * fade / 255)
        for (i in 0 until 4) {
            cv.drawArc(oval, t * 28f + i * 90f + 8f, 74f, false, p)
        }

        // Rotating 60-tick compass ring
        p.strokeWidth = 1.6f * d
        p.color = col(accent, 175 * fade / 255)
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6.0) + t * 14.0)
            val r1 = r * 0.99f
            val r2 = r * (if (i % 5 == 0) 1.06f else 1.025f)
            cv.drawLine(
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p
            )
        }

        // Counter-rotating cyan segmented telemetry arcs
        p.strokeWidth = 4.5f * d
        oval.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
        p.color = col(accent, 230 * fade / 255)
        for (i in 0 until 3) {
            cv.drawArc(oval, -t * 75f + i * 120f, 76f, false, p)
        }

        // Inner fine dashed arc ring
        p.strokeWidth = 2f * d
        oval.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        p.color = col(gold, 190 * fade / 255)
        for (i in 0 until 12) {
            cv.drawArc(oval, t * 52f + i * 30f, 16f, false, p)
        }

        // --- INNER VISOR WORKSPACE (clipped inside r * 0.74f) ---
        val innerR = r * 0.74f
        clipPath.reset()
        clipPath.addCircle(cx, cy, innerR, Path.Direction.CW)
        cv.save()
        cv.clipPath(clipPath)

        // Dark holographic visor glass background
        p.style = Paint.Style.FILL
        p.color = Color.argb((175 * fade / 255), 3, 20, 32)
        cv.drawCircle(cx, cy, innerR, p)

        // Subtle horizontal tactical scan grid inside the core
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.8f * d
        p.color = col(cyan, 42 * fade / 255)
        val step = 14f * d
        var gx = cx - innerR
        while (gx <= cx + innerR) { cv.drawLine(gx, cy - innerR, gx, cy + innerR, p); gx += step }
        var gy = cy - innerR
        while (gy <= cy + innerR) { cv.drawLine(cx - innerR, gy, cx + innerR, gy, p); gy += step }

        // Rotating 3D-style wireframe telemetry globe in the center
        val globeR = innerR * 0.44f
        p.strokeWidth = 1.1f * d
        p.color = col(cyan, 115 * fade / 255)
        cv.drawCircle(cx, cy, globeR, p)
        for (lat in listOf(-0.6f, -0.3f, 0f, 0.3f, 0.6f)) {
            val ry = globeR * kotlin.math.sqrt((1f - lat * lat).coerceAtLeast(0.05f))
            oval.set(cx - ry, cy + lat * globeR - ry * 0.28f, cx + ry, cy + lat * globeR + ry * 0.28f)
            cv.drawOval(oval, p)
        }
        for (mIdx in 0 until 4) {
            val phase = ((t * 0.6f + mIdx * 0.25f) % 1f)
            val rx = globeR * cos(phase * Math.PI).toFloat()
            oval.set(cx - kotlin.math.abs(rx), cy - globeR, cx + kotlin.math.abs(rx), cy + globeR)
            cv.drawOval(oval, p)
        }

        // Top internal telemetry readout inside ring
        val activeCount = TaskLog.activeCount().coerceAtLeast(if (TaskService.running()) 1 else 0)
        tp.style = Paint.Style.FILL
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 8.5f * d
        tp.color = col(cyan, 225 * fade / 255)
        cv.drawText("SYS STABILITY: 98%  ·  TASKS: $activeCount", cx, cy - innerR * 0.64f, tp)
        tp.color = col(gold, 225 * fade / 255)
        val moduleTitle = title.uppercase(Locale.getDefault()).take(24)
        cv.drawText("MODULE // $moduleTitle", cx, cy - innerR * 0.50f, tp)

        // Left & Right internal mini target reticles
        val sideOffset = innerR * 0.58f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * d
        p.color = col(gold, 200 * fade / 255)
        cv.drawCircle(cx - sideOffset, cy, 14f * d, p)
        cv.drawCircle(cx + sideOffset, cy, 14f * d, p)
        p.color = col(accent, 210 * fade / 255)
        cv.drawArc(
            RectF(cx - sideOffset - 18f * d, cy - 18f * d, cx - sideOffset + 18f * d, cy + 18f * d),
            t * 140f, 110f, false, p
        )
        cv.drawArc(
            RectF(cx + sideOffset - 18f * d, cy - 18f * d, cx + sideOffset + 18f * d, cy + 18f * d),
            -t * 140f, 110f, false, p
        )

        // Live operation / step label inside the lower visor
        val liveOp = (newest.ifBlank { HudState.current.ifBlank { "STANDBY // MONITORING" } })
            .take(36)
        tp.style = Paint.Style.FILL
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 9.5f * d
        tp.color = col(if (alert) amber else cyan, 235 * fade / 255)
        cv.drawText(liveOp, cx, cy + innerR * 0.56f, tp)

        if (progress in 0f..1f) {
            val pctText = "${(progress * 100).toInt()}%"
            tp.textSize = 8.5f * d
            tp.color = col(gold, 220 * fade / 255)
            cv.drawText("PROGRESS // $pctText", cx, cy + innerR * 0.70f, tp)
        }

        cv.restore()
        tp.textAlign = Paint.Align.LEFT
    }

    private fun drawTerminalFeed(
        cv: Canvas, w: Float, h: Float, t: Float, fade: Int, bad: Boolean
    ) {
        val padX = 16f * d
        val barH = 6f * d
        val bottomPad = 20f * d
        val lineH = 15f * d
        val visibleRows = rows.takeLast(5)
        val feedHeight = (visibleRows.size.coerceAtLeast(1) * lineH) + 28f * d
        val topY = (h - bottomPad - feedHeight).coerceAtLeast(h * 0.62f)

        // Translucent terminal backing panel
        p.style = Paint.Style.FILL
        p.color = Color.argb((155 * fade / 255), 3, 12, 20)
        oval.set(padX, topY, w - padX, h - bottomPad)
        cv.drawRoundRect(oval, 8f * d, 8f * d, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.1f * d
        p.color = col(if (bad) red else cyan, 140 * fade / 255)
        cv.drawRoundRect(oval, 8f * d, 8f * d, p)

        // Header row
        tp.textAlign = Paint.Align.LEFT
        tp.style = Paint.Style.FILL
        tp.textSize = 9.5f * d
        tp.color = col(gold, 225 * fade / 255)
        val cursor = if (((t * 2f).toInt() % 2) == 0) "_" else " "
        cv.drawText("> ${title.uppercase(Locale.getDefault())}$cursor", padX + 10f * d, topY + 14f * d, tp)

        // Step rows
        tp.textSize = 10.5f * d
        val maxTextW = (w - padX * 2f) - 24f * d
        if (visibleRows.isEmpty()) {
            tp.color = col(cyan, 170 * fade / 255)
            cv.drawText("• Awaiting live task events...", padX + 10f * d, topY + 30f * d, tp)
        } else {
            visibleRows.forEachIndexed { idx, (kind, msg) ->
                val rowY = topY + 30f * d + idx * lineH
                val rowColor = when (kind) {
                    2 -> red
                    1 -> green
                    3 -> gold
                    else -> cyan
                }
                val isRecent = (SystemClock.uptimeMillis() - newestAt) < 1200L && idx == visibleRows.lastIndex
                val alpha = ((if (isRecent) 255 else 205) * fade / 255)
                tp.color = col(rowColor, alpha)
                val ellipsized = TextUtils.ellipsize(msg, TextPaint(tp), maxTextW, TextUtils.TruncateAt.END).toString()
                cv.drawText("• $ellipsized", padX + 10f * d, rowY, tp)
            }
        }

        // Progress bar along the bottom edge of the feed panel
        if (progress in 0f..1f) {
            val bx = padX + 10f * d
            val bw = (w - padX * 2f) - 20f * d
            val by = h - bottomPad - barH - 6f * d

            p.style = Paint.Style.FILL
            p.color = col(cyanDeep, 140 * fade / 255)
            oval.set(bx, by, bx + bw, by + barH)
            cv.drawRoundRect(oval, 3f * d, 3f * d, p)

            p.color = col(if (bad) red else gold, 235 * fade / 255)
            oval.set(bx, by, bx + bw * progress, by + barH)
            cv.drawRoundRect(oval, 3f * d, 3f * d, p)
        }
    }
}
