package com.neo.plus.iosluancher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * The Neo Plus home-screen wallpaper: the space artwork by default, or the black-and-gold artwork (Edit bubble).
 * It is set once on the first start after this update, and again any time from
 * touch and hold > Edit bubble > "Neo Plus Wallpaper". Only the home screen (system) wallpaper is set.
 */
object NeoWallpaper {
    private const val FLAG = "neo_wallpaper_v2"   // v2: the two Neo Plus artworks (space / black and gold) replace the first generated logo wallpaper
    const val SPACE = 0
    const val GOLD = 1

    /** The artwork, centre-cropped to the screen (it is 1024 x 1536), with a light veil at the bottom so icon labels stay readable. */
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
            val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            c.drawBitmap(src, null, RectF(left, top, left + dw, top + dh), p)
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
