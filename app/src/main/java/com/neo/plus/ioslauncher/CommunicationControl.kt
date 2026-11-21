package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/** One persistent communication switch; no activity modes and no end-time schedule. */
object CommunicationControl {
    private const val PREF = "neo_communication"
    private const val ENABLED = "enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean): String {
        val app = ctx.applicationContext
        if (on) {
            if (!NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
                app.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Turn on notification access for NEO PLUS, then ask me again."
            }
            if (AssistantConfig.load(app).key.isBlank()) return "Add your Gemini API key first, then ask me to handle your messages and calls."
            // Cancel any legacy wake-up schedule without clearing its history, then begin continuous handling.
            val settings = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val wasEnabled = settings.getBoolean(ENABLED, false)
            SleepMode.finish(app)
            if (!wasEnabled) {
                val callPrefs = app.getSharedPreferences("neo_calls", Context.MODE_PRIVATE)
                settings.edit().putBoolean("calls_were_on", callPrefs.getBoolean("on", true)).apply()
            }
            settings.edit().putBoolean(ENABLED, true).putLong("enabled_at", System.currentTimeMillis()).apply()
            app.getSharedPreferences("neo_calls", Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
            SleepMode.prefs(app).edit().putLong("started", System.currentTimeMillis())
                .putBoolean("calls_only", false).putString("activity", "").apply()
            // Preserve all existing logs, transcripts, preferences, and per-contact counters.
            SleepService.start(app)
            return "Okay. I’ll handle eligible messages and calls until you say “I’m back”. I’ll keep a record for your catch-up report."
        }
        val settings = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val wasEnabled = settings.getBoolean(ENABLED, false)
        settings.edit().putBoolean(ENABLED, false).apply()
        if (wasEnabled) {
            val restoreCalls = settings.getBoolean("calls_were_on", true)
            app.getSharedPreferences("neo_calls", Context.MODE_PRIVATE).edit().putBoolean("on", restoreCalls).apply()
        }
        if (!SleepMode.prefs(app).getBoolean("on", false)) SleepService.stop(app)
        return "Message and call handling is off."
    }

    fun back(ctx: Context): String {
        val app = ctx.applicationContext
        val report = try { SleepMode.report(app) } catch (e: Exception) {
            "Welcome back. I couldn't prepare the full report: ${e.message ?: "unknown error"}. Your saved communication history is still on the phone."
        }
        setEnabled(app, false)
        return report
    }
}

/** Understands plain requests directly, without asking the model to invent an activity mode. */
object CommunicationCmd {
    private val back = Regex("\\b(?:i['’]?m|i am|im|am)\\s+back\\b|\\b(?:i have|i've|ive)\\s+returned\\b|\\bndabwerera\\b|\\bndabwera\\b", RegexOption.IGNORE_CASE)
    private val enable = Regex("\\b(?:answer|handle|manage|reply to|respond to)\\b.{0,45}\\b(?:my )?(?:messages?|texts?|calls?)\\b|\\b(?:answer|handle)\\b.{0,25}\\bmessages?\\b.{0,20}\\bcalls?\\b", RegexOption.IGNORE_CASE)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        if (back.containsMatchIn(t)) return LocalPlan("", listOf("communication_report" to JSONObject()))
        if (enable.containsMatchIn(t)) return LocalPlan("", listOf("communication" to JSONObject().put("on", true)))
        return null
    }
}
