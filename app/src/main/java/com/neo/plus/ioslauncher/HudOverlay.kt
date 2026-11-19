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
import android.os.Build
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

    /** Keep the HUD on screen while another app is in front (default on; set "hud_over_apps" to false in "neo_assistant" to hide it there). */
    fun overApps(c: Context): Boolean =
        c.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("hud_over_apps", true)

    /** True for a moment while a screenshot is taken for the AI: the HUD draws nothing then so the AI sees the real screen. */
    @Volatile var shotHold = false

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
            val elsewhere = userElsewhere()
            if (elsewhere && !overApps(c) && TaskLog.visible().none { it.active && it.waiting }) { remove(); return }
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
            v.update(title, rows, progress, alert, elsewhere, if (elsewhere) appLabel(c) else "")
        } else {
            if (idleSince == 0L) idleSince = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() - idleSince > 1200) {
                remove(); ticking = false; main.removeCallbacks(tick)
            }
        }
    }

    private var fgPkg: String? = null
    private val labels = HashMap<String, String>()

    /** Name of the app that is in front, as the user knows it ("WhatsApp"). */
    private fun appLabel(c: Context): String {
        val pkg = fgPkg ?: return ""
        return labels.getOrPut(pkg) {
            try { val pm = c.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Throwable) { pkg.substringAfterLast('.') }
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
        fgPkg = pkg
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
 * Jarvis HUD canvas, laid out like the reference screenshot:
 * - big thin clock with seconds + date, SYSTEM DIAGNOSTICS box, LOCATION / NETWORK lines
 * - large gold/cyan telemetry ring with a visor (helmet icon, live step text, battery + temperature dials, globe)
 * - terminal read-out: "> REAL STEP... COMPLETE", the newest line "... ACTIVE" or "[62%]", blinking cursor block
 * Everything shown is real: steps come from the task log / HudState, the front app from the accessibility service.
 * While another app is in front ([elsewhere]) the dark veil is much lighter so that app stays visible underneath.
 */
private class NeoHudView(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private var u = d
    private val teal = Color.rgb(84, 214, 200)
    private val cyan = Color.rgb(34, 229, 255)
    private val cyanDeep = Color.rgb(10, 110, 145)
    private val gold = Color.rgb(226, 178, 60)
    private val red = Color.rgb(255, 69, 58)
    private val green = Color.rgb(48, 209, 88)
    private val amber = Color.rgb(255, 200, 40)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val clockP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
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
    private var elsewhere = false
    private var appLabel = ""
    private var newest = ""
    private var newestAt = SystemClock.uptimeMillis()
    private val born = SystemClock.uptimeMillis()

    // Device telemetry, polled every ~2s so onDraw stays fast
    private var lastTelAt = 0L
    private var batteryPct = 0.85f
    private var coreTempNorm = 0.48f
    private var online = true
    private var place = ""

    fun update(t: String, r: List<Pair<Int, String>>, prog: Float, al: Boolean, away: Boolean, app: String) {
        title = t; rows = r; progress = prog; alert = al; elsewhere = away; appLabel = app
        val last = r.lastOrNull()?.second.orEmpty()
        if (last != newest) { newest = last; newestAt = SystemClock.uptimeMillis() }
        invalidate()
    }

    @Suppress("DEPRECATION")
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
                coreTempNorm = (((tempTenths / 10f) - 20f) / 30f).coerceIn(0.15f, 0.95f)
            }
        } catch (_: Throwable) { }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            online = cm.activeNetworkInfo?.isConnected ?: true
        } catch (_: Throwable) { }
        try { place = WeatherRepo.cached(context)?.place.orEmpty() } catch (_: Throwable) { }
    }

    private fun col(base: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))

    @Suppress("DEPRECATION")
    private fun topInset(): Float =
        if (Build.VERSION.SDK_INT >= 23) (rootWindowInsets?.systemWindowInsetTop ?: 0).toFloat() else 0f

    override fun onDraw(cv: Canvas) {
        if (HudOverlay.shotHold) return // a screenshot is being taken for the AI: draw nothing so it sees the real screen
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        u = d * (w / d / 412f).coerceIn(0.85f, 1.35f)
        refreshTelemetry()

        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        val fade = (((now - born) / 350f).coerceIn(0f, 1f) * 255).toInt()
        val bad = rows.any { it.first == 2 } || alert
        val veil = if (elsewhere) 0.42f else 1f

        // 1. Dark veil (much lighter over other apps so they stay visible)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb((110 * veil * fade / 255).toInt(), 2, 10, 18),
                Color.argb((85 * veil * fade / 255).toInt(), 3, 16, 26),
                Color.argb((190 * veil * fade / 255).toInt(), 2, 8, 16)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // 2. Clock + date (top-left)
        val left = 22f * u
        val top = topInset() + (if (elsewhere) 6f * u else 54f * u)
        val base = top + 52f * u
        val date = Date()
        val hm = timeFmt.format(date)
        clockP.textSize = 58f * u
        clockP.color = col(teal, 235 * fade / 255)
        clockP.setShadowLayer(5f * u, 0f, 0f, Color.argb(200, 0, 0, 0))
        cv.drawText(hm, left, base, clockP)
        val hmW = clockP.measureText(hm)
        clockP.textSize = 20f * u
        clockP.color = col(teal, 190 * fade / 255)
        cv.drawText(secFmt.format(date), left + hmW + 8f * u, base - 6f * u, clockP)
        clockP.clearShadowLayer()
        tp.style = Paint.Style.FILL; tp.textSize = 13f * u; tp.color = col(teal, 200 * fade / 255)
        tp.setShadowLayer(4f * u, 0f, 0f, Color.argb(200, 0, 0, 0))
        cv.drawText(dateFmt.format(date).uppercase(Locale.getDefault()), left + 2f * u, base + 22f * u, tp)
        tp.clearShadowLayer()

        // 3. System diagnostics + location / network
        val boxY = base + 36f * u
        drawSystemDiagnostics(cv, left, boxY, w * 0.43f, 86f * u, fade, t)
        val infoY = boxY + 86f * u + 20f * u
        tp.textSize = 12.5f * u
        tp.setShadowLayer(4f * u, 0f, 0f, Color.argb(200, 0, 0, 0))
        tp.color = col(Color.WHITE, 215 * fade / 255)
        val loc = "LOCATION: " + (place.ifBlank { "—" }).uppercase(Locale.getDefault()).take(18)
        cv.drawText(loc, left, infoY, tp)
        val netLabel = "NETWORK: "
        cv.drawText(netLabel, left, infoY + 17f * u, tp)
        tp.color = col(if (online) green else red, 235 * fade / 255)
        cv.drawText(if (online) "ONLINE" else "OFFLINE", left + tp.measureText(netLabel), infoY + 17f * u, tp)
        tp.clearShadowLayer()

        // 4. Ring + visor
        val cx = w * 0.50f
        val cy = h * 0.46f
        val r = minOf(w * 0.37f, h * 0.17f)
        drawRing(cv, cx, cy, r, t, fade, bad)

        // 5. Terminal feed
        drawTerminalFeed(cv, w, h, t, fade)
    }

    private fun drawSystemDiagnostics(cv: Canvas, x: Float, y: Float, boxW: Float, boxH: Float, fade: Int, t: Float) {
        val cut = 11f * u
        panelPath.reset()
        panelPath.moveTo(x, y)
        panelPath.lineTo(x + boxW - cut, y)
        panelPath.lineTo(x + boxW, y + cut)
        panelPath.lineTo(x + boxW, y + boxH)
        panelPath.lineTo(x + cut, y + boxH)
        panelPath.lineTo(x, y + boxH - cut)
        panelPath.close()

        p.style = Paint.Style.FILL
        p.color = Color.argb((120 * fade / 255), 5, 24, 36)
        cv.drawPath(panelPath, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f * u
        p.color = col(teal, 170 * fade / 255)
        cv.drawPath(panelPath, p)

        tp.style = Paint.Style.FILL
        tp.textSize = 12.5f * u
        tp.color = col(teal, 225 * fade / 255)
        cv.drawText("SYSTEM DIAGNOSTICS", x + 9f * u, y + 17f * u, tp)

        val linkFill = (0.45f + 0.25f * sin(t * 2.2f)).coerceIn(0.2f, 0.95f)
        val bars = listOf(
            Triple("POWER", batteryPct, green),
            Triple("LINK", if (online) linkFill else 0.08f, green),
            Triple("CORE TEMP", coreTempNorm, if (coreTempNorm > 0.78f) amber else green)
        )
        bars.forEachIndexed { idx, (label, pct, barCol) ->
            val rowY = y + 36f * u + idx * 18f * u
            tp.textSize = 11.5f * u
            tp.color = col(teal, 215 * fade / 255)
            cv.drawText(label, x + 9f * u, rowY, tp)
            val bx = x + 84f * u
            val bw = boxW - 96f * u
            val bh = 8f * u
            oval.set(bx, rowY - bh, bx + bw, rowY)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * u
            p.color = col(teal, 130 * fade / 255)
            cv.drawRoundRect(oval, 4f * u, 4f * u, p)
            oval.set(bx + 1.2f * u, rowY - bh + 1.2f * u, bx + 1.2f * u + (bw - 2.4f * u) * pct, rowY - 1.2f * u)
            p.style = Paint.Style.FILL
            p.color = col(barCol, 230 * fade / 255)
            cv.drawRoundRect(oval, 3f * u, 3f * u, p)
        }
    }

    private fun drawRing(cv: Canvas, cx: Float, cy: Float, r: Float, t: Float, fade: Int, bad: Boolean) {
        val accent = if (bad) red else cyan

        p.style = Paint.Style.FILL
        p.color = col(cyanDeep, 55 * fade / 255)
        cv.drawCircle(cx, cy, r * 1.12f, p)

        // Outer structural ring + diagonal rays
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * u
        p.color = col(accent, 110 * fade / 255)
        cv.drawCircle(cx, cy, r * 1.08f, p)
        for (deg in listOf(35.0, 145.0, 215.0, 325.0)) {
            val rad = Math.toRadians(deg)
            cv.drawLine(
                cx + (cos(rad) * r * 0.98f).toFloat(), cy + (sin(rad) * r * 0.98f).toFloat(),
                cx + (cos(rad) * r * 1.24f).toFloat(), cy + (sin(rad) * r * 1.24f).toFloat(), p
            )
        }

        // Gold segmented bevel ring
        p.strokeWidth = 8f * u
        p.strokeCap = Paint.Cap.BUTT
        oval.set(cx - r * 0.94f, cy - r * 0.94f, cx + r * 0.94f, cy + r * 0.94f)
        p.color = col(gold, 185 * fade / 255)
        for (i in 0 until 4) cv.drawArc(oval, t * 28f + i * 90f + 8f, 74f, false, p)

        // 60-tick compass ring
        p.strokeWidth = 1.6f * u
        p.color = col(Color.WHITE, 150 * fade / 255)
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6.0) + t * 14.0)
            val r1 = r * 0.99f
            val r2 = r * (if (i % 5 == 0) 1.06f else 1.025f)
            cv.drawLine(
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p
            )
        }

        // Counter-rotating telemetry arcs
        p.strokeWidth = 4.5f * u
        oval.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
        p.color = col(accent, 230 * fade / 255)
        for (i in 0 until 3) cv.drawArc(oval, -t * 75f + i * 120f, 76f, false, p)

        // Inner dashed ring
        p.strokeWidth = 2f * u
        oval.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        p.color = col(gold, 190 * fade / 255)
        for (i in 0 until 12) cv.drawArc(oval, t * 52f + i * 30f, 16f, false, p)

        // --- Visor ---
        val innerR = r * 0.74f
        clipPath.reset()
        clipPath.addCircle(cx, cy, innerR, Path.Direction.CW)
        cv.save()
        cv.clipPath(clipPath)

        p.style = Paint.Style.FILL
        p.color = Color.argb((175 * fade / 255), 3, 20, 32)
        cv.drawCircle(cx, cy, innerR, p)

        // Scan grid
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.8f * u
        p.color = col(cyan, 40 * fade / 255)
        val step = 14f * u
        var gx = cx - innerR
        while (gx <= cx + innerR) { cv.drawLine(gx, cy - innerR, gx, cy + innerR, p); gx += step }
        var gy = cy - innerR
        while (gy <= cy + innerR) { cv.drawLine(cx - innerR, gy, cx + innerR, gy, p); gy += step }

        // Wireframe globe
        val globeR = innerR * 0.40f
        p.strokeWidth = 1.1f * u
        p.color = col(cyan, 120 * fade / 255)
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

        // Helmet icon (top-left of the visor)
        val hw = 30f * u; val hh = 34f * u
        val hx = cx - innerR * 0.62f; val hy = cy - innerR * 0.62f
        p.strokeWidth = 1.3f * u
        p.color = col(gold, 215 * fade / 255)
        oval.set(hx, hy, hx + hw, hy + hh)
        cv.drawRoundRect(oval, 5f * u, 5f * u, p)
        panelPath.reset()
        panelPath.moveTo(hx + hw * 0.50f, hy + hh * 0.14f)
        panelPath.lineTo(hx + hw * 0.82f, hy + hh * 0.34f)
        panelPath.lineTo(hx + hw * 0.78f, hy + hh * 0.72f)
        panelPath.lineTo(hx + hw * 0.62f, hy + hh * 0.88f)
        panelPath.lineTo(hx + hw * 0.38f, hy + hh * 0.88f)
        panelPath.lineTo(hx + hw * 0.22f, hy + hh * 0.72f)
        panelPath.lineTo(hx + hw * 0.18f, hy + hh * 0.34f)
        panelPath.close()
        p.color = col(accent, 220 * fade / 255)
        cv.drawPath(panelPath, p)
        cv.drawLine(hx + hw * 0.28f, hy + hh * 0.46f, hx + hw * 0.44f, hy + hh * 0.50f, p)
        cv.drawLine(hx + hw * 0.72f, hy + hh * 0.46f, hx + hw * 0.56f, hy + hh * 0.50f, p)

        // Live step text next to the helmet (the last real steps)
        tp.style = Paint.Style.FILL
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 7.5f * u
        tp.color = col(teal, 225 * fade / 255)
        val tx = hx + hw + 7f * u
        val maxW = (cx + innerR * 0.80f) - tx
        rows.takeLast(4).forEachIndexed { i, (_, msg) ->
            val line = TextUtils.ellipsize(msg.uppercase(Locale.getDefault()), TextPaint(tp), maxW, TextUtils.TruncateAt.END).toString()
            cv.drawText(line, tx, hy + 8f * u + i * 9f * u, tp)
        }

        // Module title (top of the visor)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 8f * u
        tp.color = col(gold, 230 * fade / 255)
        cv.drawText("MODULE // " + title.uppercase(Locale.getDefault()).take(22), cx, cy - innerR * 0.82f, tp)

        // Side dials: battery (left), core temperature (right)
        val sideOffset = innerR * 0.62f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * u
        p.color = col(gold, 200 * fade / 255)
        cv.drawCircle(cx - sideOffset, cy + innerR * 0.08f, 14f * u, p)
        cv.drawCircle(cx + sideOffset, cy + innerR * 0.08f, 14f * u, p)
        p.strokeWidth = 2.6f * u
        oval.set(cx - sideOffset - 18f * u, cy + innerR * 0.08f - 18f * u, cx - sideOffset + 18f * u, cy + innerR * 0.08f + 18f * u)
        p.color = col(green, 220 * fade / 255)
        cv.drawArc(oval, -90f, 360f * batteryPct, false, p)
        oval.set(cx + sideOffset - 18f * u, cy + innerR * 0.08f - 18f * u, cx + sideOffset + 18f * u, cy + innerR * 0.08f + 18f * u)
        p.color = col(if (coreTempNorm > 0.78f) amber else cyan, 220 * fade / 255)
        cv.drawArc(oval, -90f, 360f * coreTempNorm, false, p)

        // Bottom of the visor: front app, live operation, progress, task count
        tp.style = Paint.Style.FILL
        tp.textAlign = Paint.Align.CENTER
        if (appLabel.isNotBlank()) {
            tp.textSize = 8.5f * u
            tp.color = col(gold, 235 * fade / 255)
            cv.drawText("APP // " + appLabel.uppercase(Locale.getDefault()).take(20), cx, cy + innerR * 0.44f, tp)
        }
        val liveOp = newest.ifBlank { HudState.current.ifBlank { "STANDBY // MONITORING" } }.take(30)
        tp.textSize = 8.5f * u
        tp.color = col(if (alert) amber else cyan, 235 * fade / 255)
        cv.drawText(liveOp.uppercase(Locale.getDefault()), cx, cy + innerR * 0.58f, tp)
        if (progress in 0f..1f) {
            tp.color = col(gold, 225 * fade / 255)
            cv.drawText("PROGRESS // ${(progress * 100).toInt()}%", cx, cy + innerR * 0.71f, tp)
        }
        val activeCount = TaskLog.activeCount().coerceAtLeast(if (TaskService.running()) 1 else 0)
        tp.textSize = 7.5f * u
        tp.color = col(teal, 200 * fade / 255)
        cv.drawText("TASKS: $activeCount", cx, cy + innerR * 0.84f, tp)

        cv.restore()
        tp.textAlign = Paint.Align.LEFT
    }

    private fun drawTerminalFeed(cv: Canvas, w: Float, h: Float, t: Float, fade: Int) {
        val padX = 22f * u
        val lineH = 26f * u
        val maxW = w - padX * 2f

        // Build "> STEP... STATUS" lines out of the real task lines
        val feed = ArrayList<Triple<String, String, Int>>()
        val appLine = elsewhere && appLabel.isNotBlank()
        if (appLine) feed += Triple("APP IN FRONT: " + appLabel.uppercase(Locale.getDefault()), "... ACTIVE", 0)
        val shown = rows.takeLast(if (appLine) 4 else 5)
        shown.forEachIndexed { i, (kind, msg) ->
            val txt = msg.trim().trimEnd('.', '…', ':', ' ').uppercase(Locale.getDefault())
            val last = i == shown.lastIndex
            val suf = when {
                kind == 2 -> "... FAILED"
                last && alert -> "... AWAITING INPUT"
                last && progress in 0f..1f -> " [${(progress * 100).toInt()}%]"
                last -> "... ACTIVE"
                else -> "... COMPLETE"
            }
            feed += Triple(txt, suf, kind)
        }
        if (feed.isEmpty()) feed += Triple("AWAITING LIVE TASK EVENTS", "... STANDBY", 0)

        val y0 = h * 0.625f
        tp.style = Paint.Style.FILL
        tp.textAlign = Paint.Align.LEFT
        tp.setShadowLayer(4f * u, 0f, 0f, Color.argb(230, 0, 0, 0))
        feed.forEachIndexed { idx, (txt, suf, kind) ->
            val y = y0 + idx * lineH
            val lastRow = idx == feed.lastIndex
            tp.textSize = 15f * u
            var line = "> $txt$suf"
            val wide = tp.measureText(line)
            if (wide > maxW) { tp.textSize = maxOf(10f * u, 15f * u * maxW / wide) }
            if (tp.measureText(line) > maxW) {
                val room = (maxW - tp.measureText("> $suf")).coerceAtLeast(10f * u)
                line = "> " + TextUtils.ellipsize(txt, TextPaint(tp), room, TextUtils.TruncateAt.END) + suf
            }
            val isNew = lastRow && (SystemClock.uptimeMillis() - newestAt) < 1200L
            val color = when {
                kind == 2 -> red
                lastRow && alert -> amber
                else -> teal
            }
            tp.color = col(color, (if (isNew || lastRow) 255 else 220) * fade / 255)
            cv.drawText(line, padX, y, tp)
            if (lastRow && ((t * 2f).toInt() % 2) == 0) {
                p.style = Paint.Style.FILL
                p.color = col(green, 240 * fade / 255)
                cv.drawRect(padX - 14f * u, y - 15f * u, padX - 8f * u, y + 2f * u, p)
            }
        }
        tp.clearShadowLayer()

        // Thin progress line under the feed
        if (progress in 0f..1f) {
            val by = y0 + feed.size * lineH + 4f * u
            val bw = w - padX * 2f
            p.style = Paint.Style.FILL
            p.color = col(cyanDeep, 150 * fade / 255)
            oval.set(padX, by, padX + bw, by + 3f * u)
            cv.drawRoundRect(oval, 2f * u, 2f * u, p)
            p.color = col(if (alert) amber else gold, 235 * fade / 255)
            oval.set(padX, by, padX + bw * progress, by + 3f * u)
            cv.drawRoundRect(oval, 2f * u, 2f * u, p)
        }
    }
}
