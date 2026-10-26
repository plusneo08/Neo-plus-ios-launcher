package com.neo.plus.iosluancher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** A text arrived: float an orb carrying it over whatever is on screen. */
class SmsOrbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext
        if (!MessageOrbs.enabled(app) || !MessageOrbs.canShow(app)) return
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (e: Exception) { null }
        if (parts == null || parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return
        MessageOrbs.show(app, from, body)
    }
}

object MessageOrbs {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("neo_msg_orbs", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("on", on).apply() }
    fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, from: String, body: String) {
        val app = ctx.applicationContext
        MessageOrbService.queue.add(nameFor(app, from) to body)
        try {
            val i = Intent(app, MessageOrbService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) { }
    }

    private fun nameFor(ctx: Context, number: String): String = try {
        if (number.isBlank() || ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) number
        else {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: number
        }
    } catch (e: Exception) { number }
}

/**
 * Draws the message orbs over every app and the home screen. Tap an orb to read the message, tap it again to hide it and
 * get the plain orb back, drag it down to the bottom (the remove circle) to get rid of it.
 */
class MessageOrbService : Service() {
    companion object {
        val queue = ConcurrentLinkedQueue<Pair<String, String>>()
    }

    private class Orb(val root: LinearLayout, val card: LinearLayout, val orb: OrbView, val lp: WindowManager.LayoutParams) {
        var open = false
        var above = false
        var shift = 0
        var gone = false
        var bob: ObjectAnimator? = null
    }

    private lateinit var wm: WindowManager
    private val orbs = ArrayList<Orb>()
    private var zone: ZoneView? = null
    private var slot = 0

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float): Int = dp(v).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        addZone()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        while (true) {
            val m = queue.poll() ?: break
            try { addOrb(m.first, m.second) } catch (e: Exception) { }
        }
        if (orbs.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        for (o in orbs.toList()) { try { o.bob?.cancel() } catch (e: Exception) { }; try { wm.removeView(o.root) } catch (e: Exception) { } }
        orbs.clear()
        zone?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        zone = null
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("neo_msg_orbs", "Message orbs", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "neo_msg_orbs")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New message")
            .setContentText("Tap the orb to read it. Drag it to the bottom to remove it.")
            .setOngoing(true)
            .build()
        startForeground(7412, n)
    }

    /* ---------------- remove circle at the bottom (added first, so it sits under every orb) ---------------- */

    private fun addZone() {
        val z = ZoneView(this)
        val lp = WindowManager.LayoutParams(
            dpi(150f), dpi(130f), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dpi(30f) }
        z.alpha = 0f
        try { wm.addView(z, lp); zone = z } catch (e: Exception) { }
    }

    private fun showZone() { zone?.animate()?.alpha(1f)?.setDuration(150)?.start() }
    private fun hideZone() { zone?.setHot(false); zone?.animate()?.alpha(0f)?.setDuration(120)?.start() }

    private fun inZone(o: Orb): Boolean {
        val loc = IntArray(2)
        o.orb.getLocationOnScreen(loc)
        return loc[1] + o.orb.height / 2 > resources.displayMetrics.heightPixels - dpi(150f)
    }

    /* ---------------- orbs ---------------- */

    private fun buildCard(name: String, body: String): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        card.background = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(Color.parseColor("#F2152238"))
            setStroke(dpi(1f), Color.argb(60, 255, 255, 255))
        }
        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.rgb(125, 211, 252))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = body
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dpi(4f), 0, 0)
        })
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(4f); bottomMargin = dpi(4f)
        }
        card.visibility = View.GONE
        return card
    }

    private fun addOrb(name: String, body: String) {
        if (orbs.size >= 4) removeNow(orbs[0])
        val dm = resources.displayMetrics
        val orbView = OrbView(this)
        val card = buildCard(name, body)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.END
        root.addView(orbView, LinearLayout.LayoutParams(dpi(76f), dpi(76f)))
        root.addView(card)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END        // x counts from the right edge
            x = dpi(4f)
            y = (dm.heightPixels * 0.24f).toInt() + (slot++ % 4) * dpi(84f)
        }
        val o = Orb(root, card, orbView, lp)
        card.setOnClickListener { toggle(o) }
        attachTouch(o)
        wm.addView(root, lp)
        orbs.add(o)

        // flies in from the right edge and settles with a little bounce, then bobs gently
        root.translationX = dpi(90f).toFloat()
        orbView.scaleX = 0f
        orbView.scaleY = 0f
        root.animate().translationX(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.1f)).start()
        orbView.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(1.8f)).start()
        o.bob = ObjectAnimator.ofFloat(orbView, View.TRANSLATION_Y, -dp(6f), dp(6f)).apply {
            duration = 1900L + (slot % 3) * 300L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun attachTouch(o: Orb) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        o.orb.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var drag = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val dm = resources.displayMetrics
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = o.lp.x; startY = o.lp.y; drag = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!drag && (abs(dx) > slop || abs(dy) > slop)) {
                            drag = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showZone()
                        }
                        if (drag) {
                            val off = if (o.open && o.above) o.shift else 0
                            o.lp.x = (startX - dx).toInt().coerceIn(0, maxOf(0, dm.widthPixels - v.width))
                            o.lp.y = (startY + dy).toInt().coerceIn(-off, maxOf(-off, dm.heightPixels - v.height - off))
                            try { wm.updateViewLayout(o.root, o.lp) } catch (ex: Exception) { }
                            zone?.setHot(inZone(o))
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (drag) {
                            val hot = inZone(o)
                            hideZone()
                            if (hot) dismiss(o)
                        } else v.post { toggle(o) }
                    }
                    MotionEvent.ACTION_CANCEL -> hideZone()
                }
                return true
            }
        })
    }

    /** One tap shows the message, the next tap hides it again and leaves the plain orb. */
    private fun toggle(o: Orb) {
        if (o.gone) return
        val dm = resources.displayMetrics
        o.open = !o.open
        if (o.open) {
            val cardW = minOf(dpi(280f), dm.widthPixels - dpi(24f))
            o.card.layoutParams.width = cardW
            val loc = IntArray(2)
            o.orb.getLocationOnScreen(loc)
            o.above = loc[1] > dm.heightPixels * 0.45f           // low on the screen: the message opens above the orb
            o.card.visibility = View.VISIBLE
            o.card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = o.card.measuredHeight + dpi(8f)
            o.root.removeAllViews()
            if (o.above) { o.root.addView(o.card); o.root.addView(o.orb) } else { o.root.addView(o.orb); o.root.addView(o.card) }
            o.shift = if (o.above) h else 0
            o.lp.y -= o.shift
            val maxX = dm.widthPixels - cardW - dpi(6f)         // keep the message inside the screen
            if (o.lp.x > maxX) o.lp.x = maxOf(0, maxX)
            o.card.alpha = 0f
            o.card.animate().alpha(1f).setDuration(160).start()
        } else {
            o.card.visibility = View.GONE
            o.lp.y += o.shift
            o.shift = 0
        }
        try { wm.updateViewLayout(o.root, o.lp) } catch (e: Exception) { }
    }

    private fun dismiss(o: Orb) {
        if (o.gone) return
        o.gone = true
        o.root.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction { removeNow(o) }.start()
    }

    private fun removeNow(o: Orb) {
        o.gone = true
        try { o.bob?.cancel() } catch (e: Exception) { }
        try { wm.removeView(o.root) } catch (e: Exception) { }
        orbs.remove(o)
        if (orbs.isEmpty()) stopSelf()
    }
}

/** The glowing orb: a soft pulsing halo, a glassy blue ball and a small envelope. */
private class OrbView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(150, 255, 255, 255) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var pulse = 1f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0.85f, 1.25f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 29f * d
        val haloR = minOf(r * 2.1f * pulse, width / 2f)
        glow.shader = RadialGradient(cx, cy, haloR, intArrayOf(Color.argb(120, 59, 130, 246), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, haloR, glow)
        body.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.5f, r * 1.4f,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.rgb(59, 130, 246), Color.rgb(29, 78, 216)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, body)
        c.drawCircle(cx, cy, r, ring)
        val w = 22f * d
        val h = 15f * d
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(rect, 3f * d, 3f * d, icon)
        c.drawLine(rect.left + 1f * d, rect.top + 2f * d, cx, cy + 1f * d, icon)
        c.drawLine(rect.right - 1f * d, rect.top + 2f * d, cx, cy + 1f * d, icon)
    }
}

/** The circle that appears at the bottom while an orb is dragged; let go on it to remove the orb. */
private class ZoneView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; textAlign = Paint.Align.CENTER }
    private var hot = false

    fun setHot(v: Boolean) { if (hot != v) { hot = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val r = (if (hot) 38f else 31f) * d
        val cy = 12f * d + 38f * d
        fill.color = if (hot) Color.rgb(239, 68, 68) else Color.argb(80, 255, 255, 255)
        c.drawCircle(cx, cy, r, fill)
        val k = 9f * d
        c.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
        c.drawLine(cx + k, cy - k, cx - k, cy + k, cross)
        c.drawText(if (hot) "Let go to remove" else "Drag here to remove", cx, cy + 38f * d + 22f * d, label)
    }
}
