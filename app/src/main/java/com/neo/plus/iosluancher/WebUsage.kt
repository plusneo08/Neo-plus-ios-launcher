package com.neo.plus.iosluancher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Counts the Gemini requests that website tasks make today, how many steps were replayed offline instead (no request),
 * and enforces the daily limit set in the assistant settings. The counter starts again at 0 every day.
 * A limit of 0 means "no limit".
 */
object WebUsage {
    private const val PREFS = "neo_webusage"
    const val DEFAULT_LIMIT = 300

    /** Gemini requests made by website tasks today. */
    var used by mutableStateOf(0)
        private set
    /** Steps that ran offline (offline login, replayed batch rows) and so cost no request. */
    var saved by mutableStateOf(0)
        private set
    /** Requests allowed per day; 0 = no limit. */
    var limit by mutableStateOf(DEFAULT_LIMIT)
        private set

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        limit = p.getInt("limit", DEFAULT_LIMIT)
        if (p.getString("day", "") != today()) {
            p.edit().putString("day", today()).putInt("used", 0).putInt("saved", 0).apply()
            used = 0; saved = 0
        } else {
            used = p.getInt("used", 0); saved = p.getInt("saved", 0)
        }
    }

    /** True while another request is allowed today. */
    @Synchronized
    fun allowed(ctx: Context): Boolean { load(ctx); return limit <= 0 || used < limit }

    /** Call just before every Gemini request a website task makes. */
    @Synchronized
    fun spend(ctx: Context) {
        load(ctx)
        used += 1
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("used", used).apply()
    }

    /** Call when steps ran without Gemini (offline login, replayed rows). */
    @Synchronized
    fun saveReplay(ctx: Context, steps: Int) {
        if (steps <= 0) return
        load(ctx)
        saved += steps
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("saved", saved).apply()
    }

    @Synchronized
    fun setLimit(ctx: Context, n: Int) {
        limit = n.coerceIn(0, 100_000)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("limit", limit).apply()
    }

    @Synchronized
    fun resetToday(ctx: Context) {
        used = 0; saved = 0
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("day", today()).putInt("used", 0).putInt("saved", 0).apply()
    }

    fun summary(): String = "$used of " + (if (limit <= 0) "unlimited" else limit.toString()) + " requests used today"
}
