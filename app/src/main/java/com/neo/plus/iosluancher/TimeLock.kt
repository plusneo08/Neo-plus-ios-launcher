package com.neo.plus.iosluancher

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time lock: "lock my phone until 06:00". Until that moment the phone cannot be used by anyone, including the owner.
 *
 *  - The user asks (a time of day, or a number of minutes / hours) and the lock starts at once: the command is enough,
 *    there is no confirmation screen and NO cancel, by design.
 *  - While it runs, the accessibility service (a) puts a full-screen black "Phone locked" cover over everything,
 *    including Settings, and (b) locks the screen again the moment it is woken or unlocked (device admin lockNow).
 *  - Emergency calls stay possible (button on the cover + the Android lock screen) and incoming / ongoing calls are let through.
 *  - The end time is saved, so a restart does not end it: the lock is re-applied as soon as the service starts again.
 *  - If the app is the device owner, safe mode, factory reset and uninstalling it are blocked for the duration too.
 *  - The longest lock is [MAX_HOURS] hours, so a typo can never lock the phone for days.
 *
 * Honest limits (a normal Android app cannot do more): holding the power button can still switch the phone off, and a
 * restart leaves a few seconds before the service is running again. Safe mode / recovery are only blocked on a device-owner phone.
 */
object TimeLock {
    const val MAX_HOURS = 24
    private const val PREFS = "neo_timelock"

    /** While locked the assistant may only do harmless things; nothing that could open settings or switch the lock off. */
    val allowedActions = setOf(
        "alarm", "timer", "weather", "news", "stock", "sports", "calendar", "web_search",
        "volume", "remember", "forget", "sleep_report"
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun untilMs(ctx: Context): Long = prefs(ctx).getLong("until", 0L)
    fun active(ctx: Context): Boolean = untilMs(ctx) > System.currentTimeMillis()

    fun clock(ms: Long): String {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        val today = Calendar.getInstance()
        val that = Calendar.getInstance().apply { timeInMillis = ms }
        return if (today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR) &&
            today.get(Calendar.YEAR) == that.get(Calendar.YEAR)) t else "$t tomorrow"
    }

    fun untilText(ctx: Context) = clock(untilMs(ctx))

    fun left(ms: Long): String {
        val mins = ((ms - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** Called by the assistant: starts the lock straight away. */
    fun request(ctx: Context, a: JSONObject): String {
        val app = ctx.applicationContext
        if (active(app)) return "Couldn’t start: the phone is already locked until ${untilText(app)}."
        val now = System.currentTimeMillis()
        val until: Long = when {
            a.has("minutes") -> now + (a.optDouble("minutes", 0.0) * 60_000.0).toLong()
            a.has("hour") -> {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, a.optInt("hour").coerceIn(0, 23))
                c.set(Calendar.MINUTE, a.optInt("minute", 0).coerceIn(0, 59))
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis <= now + 60_000L) c.add(Calendar.DAY_OF_YEAR, 1)
                c.timeInMillis
            }
            else -> return "Couldn’t start the lock: tell me until what time, for example “until 6:30”, or for how long."
        }
        if (until - now < 60_000L) return "Couldn’t start the lock: it must be at least 1 minute."
        if (until - now > MAX_HOURS * 3_600_000L) return "Couldn’t start the lock: the longest time lock is $MAX_HOURS hours."
        if (!DeviceAdmin.isActive(app)) {
            app.safeStart(DeviceAdmin.requestIntent(app))
            return "Allow Neo Plus as device admin on the screen that opened, then ask me again."
        }
        if (IosControlService.instance == null) {
            return "Turn on “NEO PLUS iOS Controls” in Settings > Accessibility, then ask me again."
        }
        prefs(app).edit().putLong("until", until).apply()
        ownerRestrictions(app, true)
        // a short moment, so the answer ("Locked until ...") can still be seen / spoken before the cover appears
        Handler(Looper.getMainLooper()).postDelayed({ TimeLockGuard.engage(app) }, 2500)
        return "Locked until ${clock(until)} (${left(until)}). Nobody can unlock the phone before then."
    }

    /** Called when the time is up. */
    internal fun clear(ctx: Context) {
        prefs(ctx).edit().remove("until").apply()
        ownerRestrictions(ctx, false)
    }

    /** Only possible when the app is the device owner (see README). */
    private fun ownerRestrictions(ctx: Context, on: Boolean) {
        if (!Kiosk.isOwner(ctx)) return
        val d = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DeviceAdmin.component(ctx)
        for (k in arrayOf(UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_ADD_USER)) {
            attempt { if (on) d.addUserRestriction(admin, k) else d.clearUserRestriction(admin, k); true }
        }
        attempt { d.setUninstallBlocked(admin, ctx.packageName, on); true }
    }
}

/** Runs inside the accessibility service: the cover, the re-locking, and the countdown. All on the main thread. */
object TimeLockGuard {
    private val main = Handler(Looper.getMainLooper())
    private var svc: IosControlService? = null
    private var cover: LinearLayout? = null
    private var info: TextView? = null
    private var receiverOn = false
    @Volatile private var emergencyUntil = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { relock(c) }
    }
    private val tick = object : Runnable { override fun run() { onTick() } }

    fun attach(s: IosControlService) {
        svc = s
        if (TimeLock.active(s)) arm()
    }

    fun detach(s: IosControlService) {
        if (svc === s) { disarm(); svc = null }
    }

    fun engage(ctx: Context) {
        if (svc != null && TimeLock.active(ctx)) arm()
    }

    private fun arm() {
        val s = svc ?: return
        if (!receiverOn) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try { s.registerReceiver(receiver, f); receiverOn = true } catch (e: Exception) { }
        }
        showCover(s)
        relock(s)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun disarm() {
        main.removeCallbacks(tick)
        val s = svc
        if (receiverOn && s != null) { try { s.unregisterReceiver(receiver) } catch (e: Exception) { } }
        receiverOn = false
        removeCover()
    }

    private fun onTick() {
        val s = svc ?: return
        if (!TimeLock.active(s)) {
            disarm()
            TimeLock.clear(s)
            Toast.makeText(s, "Time lock finished. Your phone is free again.", Toast.LENGTH_LONG).show()
            return
        }
        if (inCall(s) || System.currentTimeMillis() < emergencyUntil) hideCover() else showCover(s)
        info?.text = "Unlocks at ${TimeLock.untilText(s)}\n${TimeLock.left(TimeLock.untilMs(s))} left"
        main.postDelayed(tick, 1000)
    }

    /** Puts the lock screen straight back whenever the screen is woken or the phone is unlocked. */
    private fun relock(c: Context) {
        if (!TimeLock.active(c)) return
        if (inCall(c) || System.currentTimeMillis() < emergencyUntil) return
        DeviceAdmin.lockNow(c)
    }

    /** Ringing, ongoing call: let it through so calls can still be answered. */
    private fun inCall(c: Context): Boolean {
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.mode == AudioManager.MODE_RINGTONE || am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        } catch (e: Throwable) { }
        return try {
            val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: Throwable) { false }
    }

    private fun showCover(s: IosControlService) {
        val v = cover ?: build(s).also { cover = it }
        if (v.parent == null) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE
            )
            try { s.getSystemService(WindowManager::class.java).addView(v, lp) } catch (e: Exception) { }
        }
        v.visibility = View.VISIBLE
    }

    private fun hideCover() { cover?.visibility = View.GONE }

    private fun removeCover() {
        val v = cover ?: return
        try { svc?.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) { }
        cover = null
        info = null
    }

    private fun build(s: IosControlService): LinearLayout {
        val d = s.resources.displayMetrics.density
        fun px(n: Int) = (n * d).toInt()
        val root = LinearLayout(s)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(px(32), px(32), px(32), px(32))
        root.isClickable = true            // swallows every touch, nothing underneath can be reached
        root.setOnTouchListener { _, _ -> true }

        val title = TextView(s)
        title.text = "Phone locked"
        title.setTextColor(Color.WHITE)
        title.textSize = 30f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        root.addView(title)

        val sub = TextView(s)
        sub.setTextColor(Color.rgb(190, 190, 190))
        sub.textSize = 18f
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, px(16), 0, px(40))
        root.addView(sub)
        info = sub

        val sos = Button(s)
        sos.text = "Emergency call"
        sos.setOnClickListener {
            emergencyUntil = System.currentTimeMillis() + 120_000L
            hideCover()
            s.safeStart(Intent(Intent.ACTION_DIAL))
        }
        root.addView(sos)
        return root
    }
}

/** “lock my phone until 6:30” / “lock my phone for 2 hours”, understood without the AI model. */
object TimeLockCmd {
    private val I = RegexOption.IGNORE_CASE
    private val untilRe = Regex("\\block (?:my |the )?phone\\b.*?\\b(?:until|till|til)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", I)
    private val forRe = Regex("\\block (?:my |the )?phone\\b.*?\\bfor\\s+(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b", I)

    fun plan(text: String): LocalPlan? {
        forRe.find(text)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mins = if (m.groupValues[2].lowercase().startsWith("h")) n * 60 else n
            return LocalPlan("Okay, locking the phone.", listOf("time_lock" to JSONObject().put("minutes", mins)))
        }
        untilRe.find(text)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3].lowercase().replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h > 23 || min > 59) return null
            return LocalPlan("Okay, locking the phone.",
                listOf("time_lock" to JSONObject().put("hour", h).put("minute", min)))
        }
        return null
    }
}
