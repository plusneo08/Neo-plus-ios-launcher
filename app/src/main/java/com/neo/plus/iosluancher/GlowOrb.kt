package com.neo.plus.iosluancher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The glowing orb of the message bubbles (soft pulsing halo, glassy blue ball, thin ring) with the NEO PLUS logo in the middle.
 * It is the floating free-chat orb: the halo and ring take the colour of what the assistant is doing
 * (green = listening, orange = thinking, blue = speaking, grey = muted) and pulse faster while it is busy.
 */
class GlowOrbView(ctx: Context, logoRes: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val logoRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(200, 255, 255, 255) }
    private val logoBmp: Bitmap? = try { BitmapFactory.decodeResource(ctx.resources, logoRes) } catch (e: Exception) { null }
    private var logoShader: BitmapShader? = null

    private var tint = 0            // 0 = the plain blue look
    private var pulse = 1f
    private var anim: ValueAnimator? = null
    private var fast = false

    /** [color] 0 = plain blue; [active] = pulse faster while listening / thinking / speaking. */
    fun setLook(color: Int, active: Boolean) {
        tint = color
        if (active != fast) { fast = active; anim?.duration = if (active) 700L else 1500L }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0.85f, 1.25f).apply {
            duration = if (fast) 700L else 1500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val b = logoBmp ?: return
        val lr = LOGO_R * d
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = (2f * lr) / b.width
        sh.setLocalMatrix(Matrix().apply { setScale(sc, sc); postTranslate(w / 2f - lr, h / 2f - lr) })
        logoShader = sh
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = BALL_R * d
        val haloR = minOf(r * 2.1f * pulse, width / 2f)
        val haloColor = if (tint == 0) Color.argb(120, 59, 130, 246) else Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
        glow.shader = RadialGradient(cx, cy, haloR, intArrayOf(haloColor, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, haloR, glow)
        body.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.5f, r * 1.4f,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.rgb(59, 130, 246), Color.rgb(29, 78, 216)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, body)
        ring.color = if (tint == 0) Color.argb(150, 255, 255, 255) else Color.argb(235, Color.red(tint), Color.green(tint), Color.blue(tint))
        ring.strokeWidth = (if (tint == 0) 1.5f else 3f) * d
        c.drawCircle(cx, cy, r, ring)
        val sh = logoShader
        if (sh != null) {
            logoPaint.shader = sh
            c.drawCircle(cx, cy, LOGO_R * d, logoPaint)
            c.drawCircle(cx, cy, LOGO_R * d, logoRing)
        }
    }

    private companion object {
        const val BALL_R = 31f   // dp, the glass ball
        const val LOGO_R = 21f   // dp, the logo inside it
    }
}
