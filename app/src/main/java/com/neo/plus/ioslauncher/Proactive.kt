package com.neo.plus.ioslauncher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * JARVIS-style initiative: the assistant watches the phone and speaks up (or acts) without being asked.
 *
 * Three levels, the same everywhere:
 *   1. ACT SILENTLY   harmless things only (battery saver at 15 %), then say what it did.
 *   2. TELL YOU       calendar events, missed calls, unread messages: HUD line + spoken line + a notification.
 *   3. ASK FIRST      anything that costs money, sends a message or deletes data is NEVER done here; the notification only
 *                     opens the dialer / the app, so you still press the button (Approvals and the PIN stay as they are).
 *
 * Rules: low battery, next calendar event, missed calls, unread message backlog, learned habits (an app you open at about the
 * same time on most days). It stays quiet (notification only, no voice) at night, in sleep mode and during a call.
 *
 * Voice: "proactive off", "proactive on", "proactive status", "call me sir" (how it addresses you; "call me nothing" clears it).
 * Everything runs on one main-thread tick every 60 seconds; reads that touch content providers run on a short background thread.
 */
object Proactive {
    private const val PREF = "neo_proactive"
    private const val CH = "neo_proactive"
    private const val TICK_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    @Volatile private var app: Context? = null
    private var nid = 4800

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("on", on).apply(); if (on) start(c) }
    private fun address(c: Context) = p(c).getString("address", "") ?: ""

    /** Safe to call as often as you like (orb, accessibility service, main screen): only the first call starts the loop. */
    fun start(c: Context) {
        if (started) return
        started = true
        app = c.applicationContext
        main.postDelayed(tick, 15_000L)
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = app
            if (c != null && enabled(c)) {
                Thread { try { check(c) } catch (e: Throwable) { } }.start()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun check(c: Context) {
        try { battery(c) } catch (e: Throwable) { }
        try { calendar(c) } catch (e: Throwable) { }
        try { missedCalls(c) } catch (e: Throwable) { }
        try { messages(c) } catch (e: Throwable) { }
        try { habits(c) } catch (e: Throwable) { }
        try { Friday.watch(c) } catch (e: Throwable) { }
    }

    /* ------------------------------------------------------------------ delivery */

    /** "Battery at 14%" + ", sir" + "." / "?" */
    fun line(c: Context, body: String, end: Char = '.'): String {
        val a = address(c).trim()
        return body + (if (a.isNotEmpty()) ", $a" else "") + end
    }

    private fun granted(c: Context, perm: String) = ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED

    private fun inCall(c: Context): Boolean = try {
        c.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: Throwable) { false }

    private fun screenOn(c: Context): Boolean = try { c.getSystemService(PowerManager::class.java).isInteractive } catch (e: Throwable) { true }

    /** Night (22:00-07:00), sleep mode or a call: no voice, notification only. */
    private fun quiet(c: Context): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 7 || inCall(c) || (try { SleepMode.isOn(c) } catch (e: Throwable) { false })
    }

    /**
     * One proactive message: HUD line, spoken line (unless quiet or [speak] is false) and a notification whose tap runs [tap]
     * (the dialer, the app, a settings screen: never something that sends or spends).
     */
    fun tell(c: Context, text: String, speak: Boolean = true, tap: Intent? = null, urgent: Boolean = false) {
        try { HudState.event(c, text) } catch (e: Throwable) { }
        val silent = quiet(c) && !urgent
        if (speak && !silent) try { Announcer.say(c, text) } catch (e: Throwable) { }
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Assistant suggestions", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = tap ?: c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, (nid and 0xFFF), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else @Suppress("DEPRECATION") Notification.Builder(c)
            val n = b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Neo Plus")
                .setContentText(text.take(120))
                .setStyle(Notification.BigTextStyle().bigText(text.take(500)))
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(nid, n)
            nid = if (nid >= 4899) 4800 else nid + 1
        } catch (e: Throwable) { }
    }

    /* ------------------------------------------------------------------ 1. battery */

    private fun battery(c: Context) {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pr = p(c)
        var stage = pr.getInt("bat_stage", 0)
        if (charging || pct >= 30) { if (stage != 0) pr.edit().putInt("bat_stage", 0).apply(); return }
        when {
            pct <= 5 && stage < 3 -> {
                pr.edit().putInt("bat_stage", 3).apply()
                tell(c, line(c, "Battery is critical at $pct%") + " Please plug in the charger.", urgent = true,
                    tap = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
            pct <= 15 && stage < 2 -> {
                pr.edit().putInt("bat_stage", 2).apply()
                if (saverOn(c)) tell(c, line(c, "Battery at $pct%. I have turned on power saving"), tap = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                else tell(c, line(c, "Battery at $pct%. Shall I enable power saving? Tap to open it", '?'), tap = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
            pct <= 20 && stage < 1 -> {
                pr.edit().putInt("bat_stage", 1).apply()
                tell(c, line(c, "Battery at $pct%. I will switch on power saving at 15%"))
            }
        }
    }

    /** Level 1 (act silently): battery saver is harmless, and only works when WRITE_SECURE_SETTINGS was granted (grant-all.sh). */
    private fun saverOn(c: Context): Boolean = try {
        Settings.Global.putInt(c.contentResolver, "low_power", 1)
        Settings.Global.getInt(c.contentResolver, "low_power", 0) == 1
    } catch (e: Throwable) { false }

    /* ------------------------------------------------------------------ 2. calendar */

    private fun calendar(c: Context) {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) return
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, now)
        android.content.ContentUris.appendId(b, now + 20 * 60_000L)
        val told = try { JSONArray(p(c).getString("cal_told", "[]")) } catch (e: Exception) { JSONArray() }
        val seen = HashSet<String>()
        for (k in 0 until told.length()) seen.add(told.optString(k))
        var changed = false
        c.contentResolver.query(
            b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(2) == 1) continue
                val begin = cur.getLong(1)
                val mins = ((begin - now) / 60_000L).toInt()
                if (mins < 0 || mins > 15) continue
                val title = cur.getString(0).orEmpty().ifBlank { "An event" }
                val key = "$title@$begin"
                if (!seen.add(key)) continue
                changed = true
                val where = cur.getString(3).orEmpty().trim()
                val when_ = if (mins <= 1) "is starting now" else "starts in $mins minutes"
                tell(c, line(c, "$title $when_" + (if (where.isNotEmpty()) " at $where" else "")))
            }
        }
        if (changed) {
            val arr = JSONArray()
            seen.toList().takeLast(40).forEach { arr.put(it) }
            p(c).edit().putString("cal_told", arr.toString()).apply()
        }
    }

    /* ------------------------------------------------------------------ 3. missed calls */

    private fun missedCalls(c: Context) {
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return
        val pr = p(c)
        val since = pr.getLong("call_since", 0L)
        val now = System.currentTimeMillis()
        if (since == 0L) { pr.edit().putLong("call_since", now).apply(); return }   // never announce the old backlog
        var count = 0
        var number = ""
        var name = ""
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
            CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
            CallLog.Calls.DATE + " DESC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (count == 0) { number = cur.getString(0).orEmpty(); name = cur.getString(1).orEmpty() }
                count++
            }
        }
        if (count == 0) return
        pr.edit().putLong("call_since", now).apply()
        val who = name.ifBlank { number.ifBlank { "an unknown number" } }
        val text = if (count == 1) line(c, "You missed a call from $who. Shall I call back", '?')
                   else line(c, "You missed $count calls. The latest was from $who. Shall I call back", '?')
        // Level 3: the tap only opens the dialer with the number filled in; you press Call yourself.
        tell(c, text, tap = if (number.isNotBlank()) Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))) else null)
    }

    /* ------------------------------------------------------------------ 4. unread messages */

    private fun messages(c: Context) {
        val svc = NeoNotificationService.instance ?: return
        val unread = try { svc.activeNotifications.filter { it.notification.category == Notification.CATEGORY_MESSAGE && !it.isOngoing } } catch (e: Throwable) { return }
        val n = unread.size
        val pr = p(c)
        if (n < 3) { if (n == 0) pr.edit().putInt("msg_told", 0).apply(); return }
        val now = System.currentTimeMillis()
        if (n <= pr.getInt("msg_told", 0) && now - pr.getLong("msg_at", 0L) < 3_600_000L) return   // nothing new, and not for an hour
        if (now - pr.getLong("msg_at", 0L) < 1_800_000L) return                                       // at most every 30 minutes
        if (!screenOn(c)) return                                                                       // only nag when the phone is in use
        pr.edit().putInt("msg_told", n).putLong("msg_at", now).apply()
        tell(c, line(c, "You have $n unread messages waiting. Say “read my messages” and I will go through them", '.'))
    }

    /* ------------------------------------------------------------------ 5. habits */

    /** Called by [launchApp]: remembers which app was opened on which day at which minute (last 14 launches per app). */
    fun noteLaunch(c: Context, pkg: String) {
        try {
            if (pkg.isBlank() || pkg == c.packageName || !enabled(c)) return
            val pr = p(c)
            val all = try { JSONObject(pr.getString("habits", "{}")) } catch (e: Exception) { JSONObject() }
            val arr = all.optJSONArray(pkg) ?: JSONArray()
            val cal = Calendar.getInstance()
            val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            arr.put(JSONArray().put(day).put(minute))
            val trimmed = JSONArray()
            for (k in maxOf(0, arr.length() - 14) until arr.length()) trimmed.put(arr.get(k))
            all.put(pkg, trimmed)
            // forget apps that have not been used for a while so the file never grows
            if (all.length() > 40) all.keys().asSequence().toList().take(all.length() - 40).forEach { all.remove(it) }
            pr.edit().putString("habits", all.toString()).apply()
        } catch (e: Throwable) { }
    }

    private fun habits(c: Context) {
        if (!screenOn(c) || quiet(c)) return
        val pr = p(c)
        val now = Calendar.getInstance()
        val today = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (pr.getInt("hab_day", 0) != today) pr.edit().putInt("hab_day", today).putInt("hab_count", 0).putString("hab_done", "[]").apply()
        if (pr.getInt("hab_count", 0) >= 3) return                                         // at most 3 offers a day
        if (System.currentTimeMillis() - pr.getLong("hab_at", 0L) < 3_600_000L) return     // at most one an hour
        val done = pr.getString("hab_done", "[]").orEmpty()
        val all = try { JSONObject(pr.getString("habits", "{}")) } catch (e: Exception) { return }
        for (pkg in all.keys()) {
            if (done.contains("\"$pkg\"")) continue
            val arr = all.optJSONArray(pkg) ?: continue
            val days = HashSet<Int>()
            var openedNow = false
            for (k in 0 until arr.length()) {
                val e = arr.optJSONArray(k) ?: continue
                val d = e.optInt(0); val m = e.optInt(1)
                if (Math.abs(m - minute) <= 20) { if (d == today) openedNow = true else days.add(d) }
            }
            if (openedNow || days.size < 4) continue                                       // a habit = same time window on 4+ different days
            val label = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { continue }
            val open = c.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val doneArr = try { JSONArray(done) } catch (e: Exception) { JSONArray() }
            doneArr.put(pkg)
            pr.edit().putInt("hab_count", pr.getInt("hab_count", 0) + 1).putLong("hab_at", System.currentTimeMillis()).putString("hab_done", doneArr.toString()).apply()
            tell(c, line(c, "You usually open $label around this time. Tap to open it", '.'), tap = open)
            return
        }
    }

    /* ------------------------------------------------------------------ voice / action */

    fun status(c: Context): String =
        (if (enabled(c)) "Proactive mode is on: battery, calendar, missed calls, unread messages and habits." else "Proactive mode is off.") +
            (address(c).let { if (it.isBlank()) "" else " I call you $it." })

    /** The `proactive` action: {"on":false} / {"on":true} / {"do":"status"} / {"address":"sir"}. */
    fun handle(c: Context, a: JSONObject): String {
        val app = c.applicationContext
        if (a.has("address")) {
            p(app).edit().putString("address", a.optString("address").trim().take(20)).apply()
            return if (address(app).isBlank()) "Okay, I will not use a title." else "Okay, I will call you ${address(app)}."
        }
        if (a.optString("do") == "status") return status(app)
        if (a.has("on")) {
            val on = a.optBoolean("on", true)
            setEnabled(app, on)
            return if (on) "Okay, I will speak up when something needs you." else "Okay, I will stay quiet until you ask."
        }
        return status(app)
    }
}

/** Understands "proactive off / on / status" and "call me sir". */
object ProactiveCmd {
    private val I = RegexOption.IGNORE_CASE
    private val nameRe = Regex("\\bproactive(?:ly)?\\b|\\bjarvis mode\\b|\\bact on your own\\b", I)
    private val offRe = Regex("\\b(?:off|stop|disable|cancel|turn off|switch off|quiet)\\b", I)
    private val statusRe = Regex("\\bstatus\\b|\\bis it on\\b", I)
    private val addrRe = Regex("\\bcall me (sir|boss|madam|ma'am|chief|master|nothing|by name)\\b", I)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        addrRe.find(t)?.let { m ->
            val w = m.groupValues[1].lowercase(Locale.ROOT)
            val v = if (w == "nothing" || w == "by name") "" else w
            return LocalPlan("", listOf("proactive" to JSONObject().put("address", v)))
        }
        if (!nameRe.containsMatchIn(t)) return null
        val a = JSONObject()
        if (statusRe.containsMatchIn(t)) return LocalPlan("", listOf("proactive" to a.put("do", "status")))
        return LocalPlan("", listOf("proactive" to a.put("on", !offRe.containsMatchIn(t))))
    }
}
