package com.neo.plus.iosluancher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * The Neo Plus home-screen wallpaper: navy gradient, soft blue glow and the NP logo with feathered edges,
 * placed in the empty middle of the home screen (between the weather card and the app icons).
 * It is set once on the first start after this update, and again any time from
 * touch and hold > Edit bubble > "Neo Plus Wallpaper". Only the home screen (system) wallpaper is set.
 */
object NeoWallpaper {
    private const val FLAG = "neo_wallpaper_applied"

    fun build(ctx: Context): Bitmap {
        val dm = ctx.resources.displayMetrics
        val wm = WallpaperManager.getInstance(ctx)
        val w = maxOf(dm.widthPixels, wm.desiredMinimumWidth, 720)
        val h = maxOf(dm.heightPixels, wm.desiredMinimumHeight, 1280)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        // background
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF061830.toInt(), 0xFF040A16.toInt(), 0xFF020712.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // glow behind the logo
        val cx = w / 2f
        val cy = h * 0.52f
        p.shader = RadialGradient(cx, cy, w * 0.85f, intArrayOf(0x4038BDF8, 0x1A0A84FF, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // logo, feathered into the background (neo_icon.png is 192 px, so it is kept moderate in size)
        val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.neo_icon)
        if (src != null) {
            val s = (w * 0.64f).toInt()
            val logo = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val lc = Canvas(logo)
            lc.drawBitmap(src, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
            val mask = Paint(Paint.ANTI_ALIAS_FLAG)
            mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            mask.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            lc.drawRect(0f, 0f, s.toFloat(), s.toFloat(), mask)
            p.alpha = 235
            c.drawBitmap(logo, cx - s / 2f, cy - s / 2f, p)
            p.alpha = 255
            logo.recycle()
            src.recycle()
        }
        return out
    }

    /** Sets it as the home-screen wallpaper (background thread). Returns through [done] on the main thread. */
    fun apply(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            var ok = false
            try {
                val bmp = build(app)
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
        apply(ctx) { ok ->
            running = false
            if (ok) sp.edit().putBoolean(FLAG, true).apply()
            done?.invoke()
        }
    }

    fun applyWithToast(ctx: Context) = apply(ctx) { ok ->
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
