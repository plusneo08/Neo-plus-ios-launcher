package com.neo.plus.ioslauncher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

    /* ---------------- the night summary ---------------- */

private const val SLEEP_REPORT_SYSTEM = """You are Neo Plus Assistant, giving Neo a catch-up report after he returns. Tell him everything recorded while communication assistance was enabled.
Sound like a real person chatting on the phone, not like someone reading a report: warm, relaxed, contractions ("you've", "I didn't"), short and medium sentences mixed, small natural connectors like "so", "also", "and one more thing". Plain text only, no markdown, no lists. Say times the way people say them out loud ("around two in the morning"), never as digits like 02:15. Speak English unless Neo's messages show he wants otherwise.
Start with "Welcome back." Then cover, in this order: who wrote to him and what about, and what you answered; then other notifications grouped by app with counts; then group chats you left alone.
Phone calls, goal progress (app starts with Goal), texts on the assistant line and switched-off permissions also count: mention each in plain words, calls and texts on the assistant line by who and what you did. Skip a part when it is empty. If nothing happened say there were no new messages or calls to report. Never read out codes or passwords. Do not invent anything. Mention every item in the log, none may be skipped; a line like "You picked up and unlocked the phone" means he was awake for a moment in the night, say when. Do not impose a word limit: include every logged message, reply, call, caller summary, missed call, and unresolved item. Group related events only if no detail is lost. Do not invent missing details or expose codes, passwords, PINs or OTPs."""

    fun SleepMode.report(ctx: Context): String {
        val app = ctx.applicationContext
        if (prefs(app).getBoolean("calls_only", false)) {
            // wake-up call only: greet, then tell what came in since it was set (missed calls, texts, notifications)
            val text = try { ClapWake.report(app, prefs(app).getLong("started", 0L), "") } catch (e: Exception) { "Welcome back. I could not retrieve the call-only report." }
            prefs(app).edit().putString("report", text).apply()
            return text
        }
        val arr = try { JSONArray(prefs(app).getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        val startedAt = prefs(app).getLong("started", 0L)
        if (startedAt > 0L) sb.append("Communication assistant enabled at ").append(tf.format(Date(startedAt))).append(if (prefs(app).getBoolean("auto", false)) " by itself, after the screen was unused for a while.\n" else ".\n")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            sb.append("[").append(tf.format(Date(o.optLong("t")))).append("] ").append(o.optString("k")).append(" | ")
                .append(o.optString("app")).append(" | ").append(o.optString("who")).append(" | ")
                .append(o.optString("text").take(160).replace('\n', ' ')).append(" | my reply: ")
                .append(o.optString("reply").ifBlank { "none" }.take(160).replace('\n', ' ')).append('\n')
        }
        if (arr.length() == 0) return "Welcome back. There were no new messages or calls recorded.".also { prefs(app).edit().putString("report", it).apply() }
        val key = AssistantConfig.load(app).key
        val text = try {
            if (key.isBlank()) throw Exception("no key")
            Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, SLEEP_REPORT_SYSTEM,
                listOf(true to ("Kinds: REPLIED=I answered, MSG=message not answered, GROUP=group chat not answered, AUTO=automated message, NOTE=other notification, FAILED=could not answer.\n\n" + sb)), null, false).trim()
        } catch (e: Exception) { fallbackReport(arr) }
        val full = text
        prefs(app).edit().putString("report", full).apply()
        return full
    }

    private val morningAsks = listOf(
        "So, how did you sleep, and how are you feeling this morning?",
        "Anyway, enough about the phone. How was your night, did you dream of anything?",
        "I'm glad you're up. How are you feeling, and what's the plan for today?",
        "How has life been lately? Anything on your mind this morning?"
    )

    /** The morning call must not end after the summary: a question about you is added, so the conversation starts (sleep mode only). */
    internal fun SleepMode.withMorningAsk(ctx: Context, text: String): String = text

    internal fun SleepMode.fallbackReport(arr: JSONArray): String {
        val c = HashMap<String, Int>()
        val people = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = o.optString("k")
            c[k] = (c[k] ?: 0) + 1
            if (k == "REPLIED" || k == "MSG") people.add(o.optString("who"))
        }
        val parts = ArrayList<String>()
        parts.add("Welcome back. Here is the activity I retained.")
        if (people.isNotEmpty()) parts.add("${people.size} people contacted you: ${people.joinToString(", ")}.")
        if ((c["REPLIED"] ?: 0) > 0) parts.add("I sent ${c["REPLIED"]} replies.")
        if ((c["CALL"] ?: 0) > 0) parts.add("There were ${c["CALL"]} call updates.")
        if ((c["URGENT"] ?: 0) + (c["OWNER_ONLY"] ?: 0) + (c["IMPORTANT"] ?: 0) > 0) parts.add("There are ${(c["URGENT"] ?: 0) + (c["OWNER_ONLY"] ?: 0) + (c["IMPORTANT"] ?: 0)} items that need your attention.")
        if ((c["NOTE"] ?: 0) > 0) parts.add("There were ${c["NOTE"]} other notifications.")
        if ((c["GROUP"] ?: 0) > 0) parts.add("${c["GROUP"]} group messages were left unanswered.")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parts.add("${o.optString("who", "Unknown")}: ${o.optString("text").take(100)}. Reply: ${o.optString("reply").ifBlank { "none" }.take(100)}.")
        }
        return parts.joinToString(" ")
    }

    fun SleepMode.lastReport(ctx: Context): String = prefs(ctx.applicationContext).getString("report", "").orEmpty()
