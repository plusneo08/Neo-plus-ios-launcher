package com.neo.plus.iosluancher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val detail = listOf("app", "to", "label", "describe", "text", "query", "url", "site", "goal", "title").map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(50)
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
 * Iron Man style heads-up display drawn over whatever is on screen while the assistant works or opens things:
 * clock, rotating rings and a live read-out of exactly what is happening (real steps, not decoration).
 * Switch it off with SharedPreferences neo_assistant / "hud" = false. Needs "Display over other apps" or the Neo Plus accessibility controls.
 */
object HudOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var view: NeoHudView? = null
    private var wm: WindowManager? = null
    private var ticking = false
    private var idleSince = 0L

    fun enabled(c: Context): Boolean = c.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("hud", true)

    fun start(c: Context) {
        if (!enabled(c)) return
        val app = c.applicationContext
        main.post { ctx = app; if (!ticking) { ticking = true; main.post(tick) } }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { sync() } catch (e: Throwable) { }
            main.postDelayed(this, 60)
        }
    }

    private fun busy(): Boolean = TaskLog.activeCount() > 0 || System.currentTimeMillis() - HudState.lastAt < 5000

    private fun sync() {
        val c = ctx ?: return
        if (!enabled(c)) { remove(); ticking = false; return }
        if (busy()) {
            idleSince = 0L
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
                f.lines.takeLast(7).forEach { l -> rows += (when (l.kind) { TaskLine.Kind.ERROR -> 2; TaskLine.Kind.WARN -> 2; TaskLine.Kind.OK, TaskLine.Kind.FIX -> 1; else -> 0 }) to l.text }
                if (f.status.isNotBlank()) rows += 3 to f.status
            } else {
                HudState.history.takeLast(6).forEach { rows += 0 to it }
            }
            v.update(title, rows, progress, alert)
        } else {
            if (idleSince == 0L) idleSince = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() - idleSince > 1200) { remove(); ticking = false; main.removeCallbacks(tick) }
        }
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
        try { host.first.addView(v, lp); view = v; wm = host.first } catch (e: Throwable) { view = null }
    }

    private fun remove() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (e: Throwable) { }
        view = null
    }
}

/** The HUD itself: one custom view, drawn with plain Canvas calls (no layouts, so it is cheap and cannot crash on lifecycle). */
private class NeoHudView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val cyan = Color.rgb(34, 229, 255)
    private val red = Color.rgb(255, 69, 58)
    private val green = Color.rgb(48, 209, 88)
    private val amber = Color.rgb(255, 200, 40)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val oval = RectF()
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

    fun update(t: String, r: List<Pair<Int, String>>, prog: Float, al: Boolean) {
        title = t; rows = r; progress = prog; alert = al
        val last = r.lastOrNull()?.second.orEmpty()
        if (last != newest) { newest = last; newestAt = SystemClock.uptimeMillis() }
        invalidate()
    }

    private fun col(base: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        val fade = (((now - born) / 350f).coerceIn(0f, 1f) * 255).toInt()

        // soft dark veil at the bottom so the read-out stays readable on any app
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h, Color.argb(0, 0, 8, 20), Color.argb((150 * fade / 255), 0, 8, 20), Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.45f, w, h, p); p.shader = null

        // corner brackets
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = col(cyan, 200 * fade / 255)
        val m = 14f * d; val L = 34f * d
        cv.drawLine(m, m + L, m, m, p); cv.drawLine(m, m, m + L, m, p)
        cv.drawLine(w - m - L, m, w - m, m, p); cv.drawLine(w - m, m, w - m, m + L, p)
        cv.drawLine(m, h - m - L, m, h - m, p); cv.drawLine(m, h - m, m + L, h - m, p)
        cv.drawLine(w - m - L, h - m, w - m, h - m, p); cv.drawLine(w - m, h - m, w - m, h - m - L, p)

        // clock + date (top left)
        val top = 52f * d
        tp.style = Paint.Style.FILL; tp.color = col(cyan, 235 * fade / 255); tp.textSize = 46f * d
        val date = Date()
        val hm = timeFmt.format(date)
        cv.drawText(hm, 26f * d, top + 40f * d, tp)
        val hmW = tp.measureText(hm)
        tp.textSize = 16f * d; cv.drawText(secFmt.format(date), 30f * d + hmW, top + 40f * d, tp)
        tp.textSize = 12f * d; tp.color = col(cyan, 170 * fade / 255)
        cv.drawText(dateFmt.format(date).uppercase(Locale.getDefault()), 28f * d, top + 60f * d, tp)
        tp.textSize = 11f * d; tp.color = col(if (alert) amber else green, 220 * fade / 255)
        cv.drawText(if (alert) "WAITING FOR YOU" else "NEO PLUS · ONLINE", 28f * d, top + 78f * d, tp)

        // main rotating rings (right side)
        val cx = w * 0.74f; val cy = h * 0.30f; val r = minOf(w, h) * 0.17f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d; p.color = col(cyan, 120 * fade / 255); cv.drawCircle(cx, cy, r, p)
        p.strokeWidth = 6f * d; p.strokeCap = Paint.Cap.BUTT
        oval.set(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f)
        p.color = col(cyan, 230 * fade / 255)
        for (i in 0 until 3) cv.drawArc(oval, t * 90f + i * 120f, 70f, false, p)
        p.strokeWidth = 2f * d; p.color = col(cyan, 150 * fade / 255)
        oval.set(cx - r * 0.66f, cy - r * 0.66f, cx + r * 0.66f, cy + r * 0.66f)
        for (i in 0 until 8) cv.drawArc(oval, -t * 140f + i * 45f, 28f, false, p)
        // tick marks
        p.strokeWidth = 1.5f * d; p.color = col(cyan, 140 * fade / 255)
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6.0) + t * 12.0)
            val r1 = r * 1.06f; val r2 = r * (if (i % 5 == 0) 1.18f else 1.12f)
            cv.drawLine(cx + (Math.cos(a) * r1).toFloat(), cy + (Math.sin(a) * r1).toFloat(), cx + (Math.cos(a) * r2).toFloat(), cy + (Math.sin(a) * r2).toFloat(), p)
        }
        // progress arc in the middle (or a pulsing core when the size of the job is unknown)
        val core = r * 0.42f
        oval.set(cx - core, cy - core, cx + core, cy + core)
        p.strokeWidth = 7f * d; p.strokeCap = Paint.Cap.ROUND
        p.color = col(cyan, 60 * fade / 255); cv.drawCircle(cx, cy, core, p)
        p.color = col(if (alert) amber else cyan, 255 * fade / 255)
        if (progress >= 0f) cv.drawArc(oval, -90f, 360f * progress, false, p)
        else cv.drawArc(oval, t * 220f, 100f, false, p)
        p.strokeCap = Paint.Cap.BUTT
        tp.textAlign = Paint.Align.CENTER; tp.textSize = 15f * d; tp.color = col(cyan, 240 * fade / 255)
        cv.drawText(if (progress >= 0f) "${(progress * 100).toInt()}%" else "AI", cx, cy + 5f * d, tp)
        tp.textAlign = Paint.Align.LEFT

        // small red alert ring (lower right of the main ring) that pulses on warnings / errors
        val bad = rows.any { it.first == 2 } || alert
        val pr = 20f * d * (1f + 0.15f * Math.sin(t * 6.0).toFloat())
        p.strokeWidth = 3f * d; p.color = col(if (bad) red else green, 230 * fade / 255)
        cv.drawCircle(w * 0.88f, h * 0.52f, pr, p)
        p.style = Paint.Style.FILL; p.color = col(if (bad) red else green, 110 * fade / 255)
        cv.drawCircle(w * 0.88f, h * 0.52f, pr * 0.45f, p)
        p.style = Paint.Style.STROKE

        // live read-out (bottom left): exactly what is happening, newest line at the bottom with a typing effect
        val left = 26f * d
        val maxW = w - left - 30f * d
        val lineH = 22f * d
        val shown = rows.takeLast(8)
        val base = h - 120f * d
        tp.textSize = 12.5f * d
        p.style = Paint.Style.FILL
        val tit = TextUtils.ellipsize("▌ " + title.uppercase(Locale.getDefault()), android.text.TextPaint(tp), maxW, TextUtils.TruncateAt.END).toString()
        tp.color = col(cyan, 255 * fade / 255); tp.isFakeBoldText = true
        cv.drawText(tit, left, base - lineH * (shown.size + 1), tp); tp.isFakeBoldText = false
        shown.forEachIndexed { i, (kind, text) ->
            val y = base - lineH * (shown.size - 1 - i)
            val newestRow = i == shown.size - 1
            val color = when (kind) { 2 -> red; 1 -> green; 3 -> amber; else -> cyan }
            val a = if (newestRow) 255 else (110 + 20 * i).coerceAtMost(220)
            var s = text
            if (newestRow) { val n = ((now - newestAt) / 22L).toInt().coerceAtLeast(1); if (n < s.length) s = s.substring(0, n) + "█" }
            tp.color = col(color, a * fade / 255)
            val line = TextUtils.ellipsize((if (newestRow) "› " else "  ") + s, android.text.TextPaint(tp), maxW, TextUtils.TruncateAt.END).toString()
            cv.drawText(line, left, y, tp)
        }
        // scanning line under the read-out
        val scanW = (w - 2 * left)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = col(cyan, 70 * fade / 255)
        cv.drawLine(left, h - 92f * d, left + scanW, h - 92f * d, p)
        p.color = col(cyan, 230 * fade / 255)
        val sx = left + ((t * 0.5f) % 1f) * scanW
        cv.drawLine(sx, h - 92f * d, minOf(sx + 60f * d, left + scanW), h - 92f * d, p)
    }
}
