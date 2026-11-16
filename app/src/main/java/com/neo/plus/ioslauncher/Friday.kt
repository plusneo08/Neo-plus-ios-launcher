package com.neo.plus.ioslauncher

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.CallLog
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * FRIDAY's roles, on the phone:
 *   STATUS REPORT   "status report" / "diagnostics"        battery, heat, storage, memory, network, running tasks, waiting approvals
 *   SYSTEM SCAN     "system scan"                           the report plus anything that looks wrong, each with what to do about it
 *   BRIEFING        "brief me" / "morning briefing"         time, weather, next event, missed calls, unread messages, battery
 *   WATCH           automatic alerts (called every minute by Proactive): hot phone, nearly full storage, network lost during a task
 * It only reads and reports. It never changes anything on its own, so there is nothing to approve.
 */
object Friday {
    private fun granted(c: Context, perm: String) = ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED

    private class Vitals(val pct: Int, val charging: Boolean, val tempC: Float, val freePct: Int, val freeGb: Float, val ramFreePct: Int, val net: String)

    private fun vitals(c: Context): Vitals {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes.coerceAtLeast(1L); val free = st.availableBytes
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ram = if (mi.totalMem > 0) (mi.availMem * 100 / mi.totalMem).toInt() else -1
        val net = try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "connected"
            }
        } catch (e: Throwable) { "unknown" }
        return Vitals(pct, charging, temp, (free * 100 / total).toInt(), free / 1_073_741_824f, ram, net)
    }

    private fun taskLine(c: Context): String {
        val n = TaskLog.activeCount()
        val waiting = try { Approvals.list(c).size } catch (e: Throwable) { 0 }
        val t = if (n > 0) "$n task${if (n > 1) "s" else ""} running" else "no tasks running"
        return t + if (waiting > 0) ", $waiting item${if (waiting > 1) "s" else ""} waiting for your OK" else ""
    }

    /** Things that look wrong, each with the suggested step (reported, never done automatically). */
    private fun problems(v: Vitals): List<String> {
        val out = ArrayList<String>()
        if (v.pct in 0..15 && !v.charging) out += "battery is low at ${v.pct}%, plug in or use power saving"
        if (v.tempC >= 42f) out += "battery temperature is ${"%.0f".format(v.tempC)} degrees, close heavy apps and take it off the charger for a while"
        if (v.freePct < 8) out += "storage is almost full, ${"%.1f".format(Locale.US, v.freeGb)} GB free, delete large files or old downloads"
        if (v.ramFreePct in 0..8) out += "memory is nearly used up, close apps you are not using"
        if (v.net == "offline") out += "there is no internet, so online tasks cannot run"
        return out
    }

    fun status(c: Context): String {
        val v = vitals(c)
        val bat = if (v.pct >= 0) "Battery ${v.pct}%" + (if (v.charging) ", charging" else "") + ", ${"%.0f".format(v.tempC)} degrees" else "Battery unknown"
        return "$bat. Storage ${v.freePct}% free. Memory ${v.ramFreePct}% free. Network: ${v.net}. ${taskLine(c).replaceFirstChar { it.uppercase() }}."
    }

    fun scan(c: Context): String {
        val v = vitals(c)
        val p = problems(v)
        return status(c) + if (p.isEmpty()) " Scan complete: nothing needs attention." else " Scan complete. " + p.joinToString(". ") { it.replaceFirstChar { ch -> ch.uppercase() } } + "."
    }

    private fun nextEvent(c: Context): String {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) return ""
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, now); android.content.ContentUris.appendId(b, now + 24 * 3_600_000L)
        c.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(2) == 1) continue
                val cal = Calendar.getInstance().apply { timeInMillis = cur.getLong(1) }
                return "Next event: ${cur.getString(0).orEmpty().ifBlank { "untitled" }} at ${"%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}."
            }
        }
        return "No events in the next 24 hours."
    }

    private fun missedToday(c: Context): Int {
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return 0
        val midnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        var n = 0
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE), CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), midnight.toString()), null)?.use { n = it.count }
        return n
    }

    fun briefing(c: Context): String {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val greet = when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
        val parts = ArrayList<String>()
        parts += "$greet. It is ${"%02d:%02d".format(h, now.get(Calendar.MINUTE))}."
        try { PhoneActions.run(c, "weather", JSONObject(), OrbBridge.apps()).note.takeIf { it.isNotBlank() }?.let { parts += it.take(160) } } catch (e: Throwable) { }
        try { nextEvent(c).takeIf { it.isNotBlank() }?.let { parts += it } } catch (e: Throwable) { }
        try { missedToday(c).let { parts += if (it == 0) "No missed calls today." else "$it missed call${if (it > 1) "s" else ""} today." } } catch (e: Throwable) { }
        try {
            val m = NeoNotificationService.instance?.activeNotifications?.count { it.notification.category == android.app.Notification.CATEGORY_MESSAGE } ?: 0
            if (m > 0) parts += "$m unread message${if (m > 1) "s" else ""}."
        } catch (e: Throwable) { }
        try { val v = vitals(c); if (v.pct >= 0) parts += "Battery ${v.pct}%" + (if (v.charging) ", charging." else ".") } catch (e: Throwable) { }
        return parts.joinToString(" ")
    }

    /** The `friday` action: {"do":"status"|"scan"|"brief"}. */
    fun handle(c: Context, a: JSONObject): String = when (a.optString("do")) {
        "scan" -> scan(c)
        "brief" -> briefing(c)
        else -> status(c)
    }

    /* ------------------------------------------------------------------ WATCH (called by Proactive every minute) */

    fun watch(c: Context) {
        val pr = c.getSharedPreferences("neo_friday", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val v = vitals(c)
        if (v.tempC >= 43f && now - pr.getLong("heat_at", 0L) > 30 * 60_000L) {
            pr.edit().putLong("heat_at", now).apply()
            Proactive.tell(c, Proactive.line(c, "The phone is running hot at ${"%.0f".format(v.tempC)} degrees. Closing heavy apps would help"), urgent = true)
        }
        if (v.freePct < 8 && now - pr.getLong("disk_at", 0L) > 24 * 3_600_000L) {
            pr.edit().putLong("disk_at", now).apply()
            Proactive.tell(c, Proactive.line(c, "Storage is almost full, ${"%.1f".format(Locale.US, v.freeGb)} GB left"))
        }
        if (v.net == "offline" && TaskService.running() && now - pr.getLong("net_at", 0L) > 10 * 60_000L) {
            pr.edit().putLong("net_at", now).apply()
            Proactive.tell(c, Proactive.line(c, "I lost the internet connection while a task is running"), urgent = true)
        }
    }
}

/** "status report", "diagnostics", "system scan", "brief me", "morning briefing". Saying "Friday" first is optional. */
object FridayCmd {
    private val I = RegexOption.IGNORE_CASE
    private val scanRe = Regex("\\b(?:system|full|security)?\\s*scan\\b(?! the (?:page|screen|document|qr))|\\brun a scan\\b", I)
    private val statusRe = Regex("\\b(?:status report|system report|system status|diagnostics?|run a check)\\b", I)
    private val briefRe = Regex("\\b(?:brief me|briefing|catch me up|what did i miss|daily report)\\b", I)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        val friday = Regex("\\bfriday\\b", I).containsMatchIn(t)
        val d = when {
            briefRe.containsMatchIn(t) -> "brief"
            statusRe.containsMatchIn(t) -> "status"
            scanRe.containsMatchIn(t) && (friday || Regex("\\bsystem|phone\\b", I).containsMatchIn(t)) -> "scan"
            else -> return null
        }
        return LocalPlan("", listOf("friday" to JSONObject().put("do", d)))
    }
}
