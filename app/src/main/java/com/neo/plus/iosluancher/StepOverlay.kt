package com.neo.plus.iosluancher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * While "Watch page" is on, the website page fills the whole screen and this draws the task steps over it on a transparent
 * background (semi-transparent bubbles, so the page stays visible behind them), with Hide page and Stop buttons.
 * It hides itself while the task is waiting for the user (CAPTCHA / code), so nothing covers the page then.
 * All of it runs on the main thread.
 */
object StepOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var root: LinearLayout? = null
    private var header: TextView? = null
    private var list: LinearLayout? = null
    private var ticking = false
    private var lastKey = ""
    private const val SHOW_LAST = 7

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { WebTask.syncVisibility(); sync() } catch (e: Exception) { }
            main.postDelayed(this, 500)
        }
    }

    fun start(c: Context) {
        ctx = c.applicationContext
        if (!ticking) { ticking = true; main.post(tick) }
    }

    fun stop() {
        ticking = false
        main.removeCallbacks(tick)
        remove()
    }

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun bubble(c: Context, color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 16).toFloat() }

    private fun pill(c: Context, label: String, color: Int, onTap: () -> Unit): TextView = TextView(c).apply {
        text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 24).toFloat() }
        setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12))
        setOnClickListener { onTap() }
    }

    private fun sync() {
        val c = ctx ?: return
        val want = (TaskLog.showBrowser || TaskLog.overlay) && TaskLog.active && !WebTask.waitingForUser
        if (!want) { remove(); return }
        if (root == null && !add(c)) return
        refresh(c)
    }

    private fun add(c: Context): Boolean {
        return try {
            val host = WebTask.overlayHost(c) ?: return false
            val wm = host.first
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 24))
            }
            val h = TextView(c).apply {
                setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                background = bubble(c, Color.argb(150, 0, 0, 0))
                setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }
            val l = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 10), 0, 0) }
            val hide = pill(c, if (TaskLog.overlay) "Hide" else "Hide page", Color.argb(190, 60, 60, 67)) { TaskLog.showBrowser = false; TaskLog.overlay = false }
            val stopBtn = pill(c, "Stop", Color.argb(230, 255, 69, 58)) { TaskLog.stop() }
            row.addView(hide, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(android.view.View(c), LinearLayout.LayoutParams(dp(c, 10), 1))
            row.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(h)
            box.addView(l)
            box.addView(row)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, host.second,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
            wm.addView(box, lp)
            root = box; header = h; list = l; lastKey = ""
            true
        } catch (e: Exception) { root = null; false }
    }

    private fun refresh(c: Context) {
        val progress = if (TaskLog.total > 0) "  ·  ${TaskLog.done + TaskLog.failed}/${TaskLog.total} rows" else ""
        header?.text = TaskLog.title.ifBlank { "Task" } + "\n" + TaskLog.status + progress
        val lines = TaskLog.lines.toList()
        val key = "${lines.lastOrNull()?.id}:${lines.size}"
        if (key == lastKey) return
        lastKey = key
        val l = list ?: return
        l.removeAllViews()
        lines.takeLast(SHOW_LAST).forEach { ln ->
            val color = when (ln.kind) {
                TaskLine.Kind.OK -> Color.rgb(48, 209, 88)
                TaskLine.Kind.WARN -> Color.rgb(255, 214, 10)
                TaskLine.Kind.ERROR -> Color.rgb(255, 69, 58)
                TaskLine.Kind.FIX -> Color.rgb(100, 210, 255)
                else -> Color.WHITE
            }
            val t = TextView(c).apply {
                text = ln.text; setTextColor(color); textSize = 14f
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                background = bubble(c, Color.argb(140, 0, 0, 0))
                setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(c, 6)
            l.addView(t, p)
        }
    }

    private fun remove() {
        val r = root ?: return
        val c = ctx
        if (c != null) runCatching { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
        root = null; header = null; list = null; lastKey = ""
    }
}
