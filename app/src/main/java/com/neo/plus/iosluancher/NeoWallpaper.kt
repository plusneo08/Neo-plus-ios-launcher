package com.neo.plus.iosluancher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Fits the artwork to a taller screen without cropping and without squashing the logo: the picture is scaled to the screen width,
 * the middle band (the logo, 27%..68% of the height) keeps its true proportions, and only the top band (sky) and the bottom band
 * (ground) are stretched a little to take up the extra height. Each entry is [srcY, srcHeight, dstY, dstHeight].
 * Returns null when the screen is not taller than the artwork (then the artwork is simply stretched to the screen).
 */
internal fun neoFillBands(sw: Int, sh: Int, w: Int, h: Int): List<IntArray>? {
    val scale = w.toFloat() / sw
    if (sh * scale >= h) return null
    val topH = (sh * 0.27f).toInt()
    val botStart = (sh * 0.68f).toInt()
    val midH = botStart - topH
    val botH = sh - botStart
    val midDst = Math.round(midH * scale)
    val remaining = h - midDst
    if (remaining <= 0) return null
    val topDst = Math.round(remaining * topH.toFloat() / (topH + botH))
    val botDst = remaining - topDst
    return listOf(
        intArrayOf(0, topH, 0, topDst),
        intArrayOf(topH, midH, topDst, midDst),
        intArrayOf(botStart, botH, topDst + midDst, botDst),
    )
}

/**
 * The Neo Plus home-screen wallpaper: the space artwork by default, or the black-and-gold artwork (Edit bubble).
 * It is set once on the first start after this update, and again any time from
 * touch and hold > Edit bubble > "Neo Plus Wallpaper". Only the home screen (system) wallpaper is set.
 */
object NeoWallpaper {
    private const val FLAG = "neo_wallpaper_v2"   // v2: the two Neo Plus artworks (space / black and gold) replace the first generated logo wallpaper
    const val SPACE = 0
    const val GOLD = 1

    /** The artwork, filling the WHOLE screen and shown in full, never cropped or zoomed (it is 1024 x 1536), with a light veil at the bottom so icon labels stay readable. */
    fun build(ctx: Context, which: Int = SPACE): Bitmap {
        val dm = ctx.resources.displayMetrics
        val wm = WallpaperManager.getInstance(ctx)
        val w = maxOf(dm.widthPixels, wm.desiredMinimumWidth, 720)
        val h = maxOf(dm.heightPixels, wm.desiredMinimumHeight, 1280)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        c.drawColor(0xFF020712.toInt())
        val src = BitmapFactory.decodeResource(ctx.resources, if (which == GOLD) R.drawable.neo_wall_gold else R.drawable.neo_wall_space)
        if (src != null) {
            val bands = neoFillBands(src.width, src.height, w, h)
            if (bands != null) {
                for (r in bands) {
                    c.drawBitmap(src, Rect(0, r[0], src.width, r[0] + r[1]), RectF(0f, r[2].toFloat(), w.toFloat(), (r[2] + r[3]).toFloat()), p)
                }
            } else {
                c.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
            }
            src.recycle()
        }
        p.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        return out
    }

    /** Sets it as the home-screen wallpaper (background thread). Returns through [done] on the main thread. */
    fun apply(ctx: Context, which: Int = SPACE, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            var ok = false
            try {
                val bmp = build(app, which)
                WallpaperManager.getInstance(app).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                bmp.recycle()
                ok = true
            } catch (e: Throwable) { }
            Handler(Looper.getMainLooper()).post { done?.invoke(ok) }
        }.start()
    }

    @Volatile private var running = false

    /**
     * Sets it once automatically: on the very first start of the app (MainActivity.onCreate), after an update or
     * a reboot (NeoWallpaperReceiver). It is marked as done only after it really worked, so a failed try is
     * repeated next time; once it worked it never runs again on its own.
     */
    fun applyOnce(ctx: Context, done: (() -> Unit)? = null) {
        val sp = ctx.applicationContext.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
        if (sp.getBoolean(FLAG, false) || running) { done?.invoke(); return }
        running = true
        apply(ctx, SPACE) { ok ->
            running = false
            if (ok) sp.edit().putBoolean(FLAG, true).apply()
            done?.invoke()
        }
    }

    fun applyWithToast(ctx: Context, which: Int = SPACE) = apply(ctx, which) { ok ->
        Toast.makeText(ctx, if (ok) "Neo Plus wallpaper set" else "Couldn’t set the wallpaper", Toast.LENGTH_SHORT).show()
    }
}

/** Runs after an update of the app and after the phone starts: sets the wallpaper if it was not set yet. */
class NeoWallpaperReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val a = intent.action
        if (a != android.content.Intent.ACTION_BOOT_COMPLETED && a != android.content.Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        NeoWallpaper.applyOnce(context) { try { pending.finish() } catch (e: Exception) { } }
    }
}
