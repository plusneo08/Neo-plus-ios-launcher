package com.neo.plus.iosluancher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale

/** Small iOS-style vertical volume bar. */
private class HudView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC3A3A3C.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 11f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; strokeWidth = 1.5f * d }
    /** Bar fill, 0..1. When [boostMode] is on the bar is the 0-200 % scale (the middle is 100 %). */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var boostMode = false
    var percent = 0

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((54 * d).toInt(), (176 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        val rect = RectF(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        fill.color = if (boostMode && level > 0.5f) 0xFFFF9F0A.toInt() else 0xF2FFFFFF.toInt()
        c.drawRect(0f, h * (1f - level), w, h, fill)
        if (boostMode) c.drawLine(0f, h / 2f, w, h / 2f, tick)
        c.restore()
        if (boostMode) {
            label.color = if (level > 0.88f) 0xFF48484A.toInt() else 0xFFFFFFFF.toInt()
            c.drawText("$percent%", w / 2f, 20 * d, label)
        }
        val col = if (level > 0.18f) 0xFF48484A.toInt() else 0xFFFFFFFF.toInt()
        icon.color = col; wave.color = col
        val cx = w / 2f - 3 * d
        val cy = h - 28 * d
        c.drawPath(Path().apply {
            moveTo(cx - 8 * d, cy - 4 * d); lineTo(cx - 3 * d, cy - 4 * d); lineTo(cx + 3 * d, cy - 9 * d)
            lineTo(cx + 3 * d, cy + 9 * d); lineTo(cx - 3 * d, cy + 4 * d); lineTo(cx - 8 * d, cy + 4 * d); close()
        }, icon)
        if (level > 0f) c.drawArc(RectF(cx + 1 * d, cy - 6 * d, cx + 11 * d, cy + 6 * d), -50f, 100f, false, wave)
        if (level > 0.5f) c.drawArc(RectF(cx - 2 * d, cy - 11 * d, cx + 14 * d, cy + 11 * d), -50f, 100f, false, wave)
    }
}

/**
 * iOS status bar drawn on top of every other app, so Android's own bar never shows while an app is open.
 * Solid black so the Android icons underneath are fully covered.
 */
private class StatusOverlayView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.BLACK }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt(); textSize = 12f * d; textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var time = ""
    var pct = 100
    var charging = false
    var lowPower = false
    var sims: List<Int> = emptyList()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val cy = h / 2f
        c.drawText(time, 22 * d, cy - (txt.ascent() + txt.descent()) / 2f, txt)

        val bw = 25 * d
        val bh = 12 * d
        val right = w - 22 * d
        val body = RectF(right - bw, cy - bh / 2f, right, cy + bh / 2f)
        c.drawRoundRect(body, 3.6f * d, 3.6f * d, outline)
        fill.color = when {
            charging -> 0xFF30D158.toInt()
            lowPower -> 0xFFFFD60A.toInt()
            pct <= 20 -> 0xFFFF453A.toInt()
            else -> Color.WHITE
        }
        val inner = RectF(body.left + 1.8f * d, body.top + 1.8f * d,
            body.left + 1.8f * d + (bw - 3.6f * d) * pct.coerceIn(0, 100) / 100f, body.bottom - 1.8f * d)
        if (inner.width() > 0f) c.drawRoundRect(inner, 2f * d, 2f * d, fill)
        c.drawRoundRect(RectF(body.right + 1.2f * d, cy - 2.2f * d, body.right + 2.8f * d, cy + 2.2f * d), 1f * d, 1f * d, fill.apply { color = 0x88FFFFFF.toInt() })
        c.drawText("$pct", body.left - 6 * d, cy - (small.ascent() + small.descent()) / 2f, small)
        drawSims(c, body.left - 6 * d - small.measureText("$pct") - 10 * d, cy)
    }

    /** One signal-bar row per SIM (two stacked rows on a dual-SIM phone), ending at [rightX]. */
    private fun drawSims(c: Canvas, rightX: Float, cy: Float) {
        if (sims.isEmpty()) {
            small.textAlign = Paint.Align.RIGHT
            c.drawText("No SIM", rightX, cy - (small.ascent() + small.descent()) / 2f, small)
            return
        }
        val rows = sims.size.coerceAtMost(2)
        val totalH = 12 * d
        val gapY = if (rows == 2) 1.5f * d else 0f
        val rowH = (totalH - gapY * (rows - 1)) / rows
        val bw = (if (rows == 2) 2.8f else 3f) * d
        val gap = 1.6f * d
        val totalW = 4 * bw + 3 * gap
        val left = rightX - totalW
        val top0 = cy - totalH / 2f
        for (r in 0 until rows) {
            val top = top0 + r * (rowH + gapY)
            for (i in 0..3) {
                val bh = rowH * (0.38f + 0.2066f * i)
                sigPaint.color = if (i < sims[r]) Color.WHITE else 0x59FFFFFF
                val x = left + i * (bw + gap)
                c.drawRoundRect(RectF(x, top + rowH - bh, x + bw, top + rowH), 0.8f * d, 0.8f * d, sigPaint)
            }
        }
    }
}

/**
 * - Volume buttons: consumed, so Android's volume panel never shows; the iOS bar is shown instead.
 * - Android notification shade: closed as soon as it opens, and the iOS Control Center opens instead.
 * Must be switched on once in Settings > Accessibility.
 */
class IosControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var hud: HudView? = null
    private var hudShown = false
    private var lastShade = 0L
    private var statusOverlay: StatusOverlayView? = null
    private var launcherFront = true
    private var lastBringBack = 0L
    private var imeCache: Set<String> = emptySet()
    private var imeAt = 0L
    private var homeCache: Set<String> = emptySet()
    private var homeAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            refreshOverlay()
            main.postDelayed(this, 6_000)
        }
    }
    private val hide = Runnable {
        hud?.animate()?.alpha(0f)?.setDuration(220)?.withEndAction { hud?.visibility = View.GONE }?.start()
        hudShown = false
    }

    companion object {
        /** Lets the assistant press Home / Back / Recents, lock the screen and take screenshots. */
        @Volatile
        var instance: IosControlService? = null
    }

    private val shotExecutor = Executors.newSingleThreadExecutor()

    /** Real pixel size (width to height) of the screen at the last captureScreen(), used to turn a spot in the picture into a touch. */
    @Volatile var lastShotSize: Pair<Int, Int>? = null

    /** A JPEG of what is on the screen right now (Android 11+), scaled down; null if unavailable. Call off the main thread. */
    fun captureScreen(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, shotExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (bmp != null) {
                            lastShotSize = bmp.width to bmp.height
                            val scale = 1280f / maxOf(bmp.width, bmp.height)
                            val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                            ) else bmp
                            val bos = ByteArrayOutputStream()
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos)
                            out = bos.toByteArray()
                        }
                    } catch (e: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) { }
        return out
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BeepMute.recover(this)   // a stream left muted by a killed process
        VolumeBoost.load(this)
        VolumeBoost.set(this, 0)   // a stale saved boost made Volume Down look dead (it lowered the boost first)
        val v = HudView(this)
        v.visibility = View.GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        lp.x = (14 * resources.displayMetrics.density).toInt()
        try { getSystemService(WindowManager::class.java).addView(v, lp); hud = v } catch (e: Exception) { }
        addStatusOverlay()
        main.post(tick)
        TimeLockGuard.attach(this)   // re-applies a running time lock (e.g. after a restart)
    }

    // ---- iOS status bar over other apps ------------------------------------------------------

    private fun statusBarHeight(): Int {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var id = if (land) resources.getIdentifier("status_bar_height_landscape", "dimen", "android") else 0
        if (id <= 0) id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (28 * resources.displayMetrics.density).toInt()
    }

    private fun addStatusOverlay() {
        val v = StatusOverlayView(this)
        v.visibility = View.GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try { getSystemService(WindowManager::class.java).addView(v, lp); statusOverlay = v } catch (e: Exception) { }
        refreshOverlay()
    }

    /** Rebuilds the top bar (its height depends on rotation). */
    private fun rebuildOverlays() {
        val wm = getSystemService(WindowManager::class.java)
        try { statusOverlay?.let { wm.removeView(it) } } catch (e: Exception) { }
        statusOverlay = null
        addStatusOverlay()
    }

    /** Updates the clock, SIM signal and battery and shows the top bar whenever another app is in front: portrait, landscape and fullscreen apps. */
    private fun refreshOverlay() {
        val v = statusOverlay ?: return
        val show = !launcherFront
        if (show) {
            val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
            v.time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (level >= 0) v.pct = level * 100 / scale
                val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                v.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
            v.lowPower = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode } catch (e: Exception) { false }
            v.sims = SimSignals.read(this)
            v.invalidate()
        }
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildOverlays()
    }

    private fun imePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - imeAt > 60_000) {
            imeAt = now
            imeCache = try {
                getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
            } catch (e: Exception) { emptySet() }
        }
        return imeCache
    }

    /** "package/Class" of the home screens of OTHER launchers (the stock Android launcher etc.). */
    private fun otherHomeActivities(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - homeAt > 60_000) {
            homeAt = now
            homeCache = try {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
                ).map { it.activityInfo.packageName to it.activityInfo.name }
                    .filter { it.first != packageName && it.first != "com.android.settings" }
                    .map { it.first + "/" + it.second }.toSet()
            } catch (e: Exception) { emptySet() }
        }
        return homeCache
    }

    /** Remembers which app is in front, and pulls the user straight back if the stock Android home screen ever shows. */
    private fun trackForeground(pkg: String, cls: String) {
        if (pkg == packageName) { launcherFront = true; refreshOverlay(); return }
        if (pkg == "android" || pkg in imePackages()) return
        if ("$pkg/$cls" in otherHomeActivities()) {
            val now = System.currentTimeMillis()
            if (now - lastBringBack > 800) {
                lastBringBack = now
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClass(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            return
        }
        launcherFront = false
        refreshOverlay()
    }

    override fun onDestroy() {
        TimeLockGuard.detach(this)
        if (instance === this) instance = null
        main.removeCallbacks(tick)
        try { statusOverlay?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (e: Exception) { }
        statusOverlay = null
        try { hud?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (e: Exception) { }
        hud = null
        super.onDestroy()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            // If anything goes wrong, give the key back to Android so the button never ends up dead
            consumedDown = try { adjust(dir); true } catch (t: Throwable) { false }
            return consumedDown
        }
        return consumedDown
    }

    @Volatile private var consumedDown = true

    /**
     * Which window is in front right now (a missed window event used to leave the ringer selected inside an app).
     * Returns false when the front app could not be read at all: then the caller must not assume the home screen.
     */
    private fun refreshFront(): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val p = root.packageName?.toString()
            try { root.recycle() } catch (e: Exception) { }
            if (p == null) return false
            if (p == "android" || p == "com.android.systemui" || p in imePackages()) return launcherFront
            launcherFront = p == packageName
            return true
        } catch (e: Exception) { return false }
    }

    private fun adjust(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // A mic beep-mute that has not been undone yet must never swallow a button press
        if (BeepMute.isHolding()) BeepMute.restoreNow(this)
        // The ringer is only chosen when the home screen is KNOWN to be in front. If the front app cannot be read
        // (some apps give no window to read), or the last window event was missed, the media volume is used, so the
        // buttons never silently change the ringer while an app is open and look dead.
        val onHome = refreshFront() && launcherFront
        var stream = when {
            am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            am.isMusicActive -> AudioManager.STREAM_MUSIC
            onHome -> AudioManager.STREAM_RING          // on the home screen with nothing playing: ringer
            else -> AudioManager.STREAM_MUSIC           // inside any other app: media volume
        }
        // Media volume goes on past 100 % (up to 200 %): at the top, volume-up adds a boost, volume-down removes it first.
        if (stream == AudioManager.STREAM_MUSIC) {
            val mx = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            if (dir == AudioManager.ADJUST_RAISE && am.getStreamVolume(stream) >= mx) {
                VolumeBoost.change(this, VolumeBoost.STEP)
                if (VolumeBoost.percent > 0) { showHud(1f, true); return }
                // the phone does not allow a boost: carry on as a normal press at maximum
            }
            if (dir == AudioManager.ADJUST_LOWER && VolumeBoost.percent > 0) {
                VolumeBoost.change(this, -VolumeBoost.STEP)
                showHud(1f, true)
                return
            }
        }
        // The ringer needs FLAG_ALLOW_RINGER_MODES, otherwise Volume Up does nothing while the phone is on vibrate or silent
        val flags = if (stream == AudioManager.STREAM_RING) AudioManager.FLAG_ALLOW_RINGER_MODES else 0
        // A stream left muted (e.g. by an old mic beep-mute) ignores the buttons: lift the mute first.
        try { if (am.isStreamMute(stream)) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        val before = am.getStreamVolume(stream)
        try { am.adjustStreamVolume(stream, dir, flags) } catch (e: Exception) {
            stream = AudioManager.STREAM_MUSIC
            try { am.adjustStreamVolume(stream, dir, 0) } catch (e2: Exception) { }
        }
        // Nothing moved although it was not at its limit: let Android pick the stream itself and show its own panel.
        val mxNow = am.getStreamMaxVolume(stream)
        val atLimit = (dir == AudioManager.ADJUST_RAISE && before >= mxNow) || (dir == AudioManager.ADJUST_LOWER && before <= 0)
        if (!atLimit && am.getStreamVolume(stream) == before) {
            try { am.adjustVolume(dir, AudioManager.FLAG_SHOW_UI); return } catch (e: Exception) { }
        }
        val max = mxNow.coerceAtLeast(1)
        showHud(am.getStreamVolume(stream) / max.toFloat(), stream == AudioManager.STREAM_MUSIC)
    }

    private fun showHud(level: Float, media: Boolean = false) {
        val v = hud ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val boost = if (media) VolumeBoost.percent else 0
        v.boostMode = media
        if (media) {
            val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val total = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / mx + boost
            v.percent = total
            v.level = total / 200f
        } else v.level = level
        main.removeCallbacks(hide)
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 1f
        hudShown = true
        main.postDelayed(hide, 1600)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        if (pkg != "com.android.systemui") { trackForeground(pkg, cls); return }
        val shade = cls.contains("NotificationShade") || cls.contains("StatusBarWindow") ||
            cls.contains("QuickSettings") || cls.contains("QSPanel") || cls.contains("NotificationPanel")
        if (!shade) return
        val now = System.currentTimeMillis()
        if (now - lastShade < 800) return
        lastShade = now
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else performGlobalAction(GLOBAL_ACTION_BACK)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_cc", true)
        )
    }

    override fun onInterrupt() {}
}
