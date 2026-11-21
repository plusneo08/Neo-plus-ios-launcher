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

    private fun reportSafe(value: String): String {
        val labelled = Regex("(?i)(otp|one[- ]time (?:password|code)|verification code|security code|pin|password)(?:\\s*(?:is|:|=)?\\s*)[A-Za-z0-9-]{3,}")
        val withoutLabels = labelled.replace(value) { match -> match.groupValues[1] + " [hidden]" }
        return Regex("\\b\\d{4,}\\b").replace(withoutLabels, "[number hidden]")
    }

    fun SleepMode.report(ctx: Context): String {
        val app = ctx.applicationContext
        val arr = try { JSONArray(prefs(app).getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        val startedAt = prefs(app).getLong("started", 0L)
        val events = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { startedAt <= 0L || it.optLong("t", 0L) >= startedAt }
        if (events.isEmpty()) {
            val empty = "Welcome back. There were no new messages or calls recorded while communication handling was enabled."
            prefs(app).edit().putString("report", empty).apply()
            return empty
        }
        val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val details = events.mapIndexed { index, event ->
            val kind = when (event.optString("k")) {
                "REPLIED" -> "Message answered"
                "MSG" -> "Message not answered"
                "GROUP" -> "Group message left for you"
                "AUTO" -> "Automated message skipped"
                "FAILED" -> "Reply failed"
                "CALL" -> "Call handled"\n                "NOTE" -> "Other activity or notification"
                "URGENT" -> "Urgent item"
                "IMPORTANT" -> "Important item"
                "OWNER_ONLY" -> "Needs your attention"
                else -> event.optString("k", "Notification").ifBlank { "Notification" }
            }
            val who = reportSafe(event.optString("who").ifBlank { "Unknown sender" })
            val source = reportSafe(event.optString("app").ifBlank { "Unknown app" })
            val message = reportSafe(event.optString("text")).ifBlank { "(no message text saved)" }
            val reply = reportSafe(event.optString("reply")).ifBlank { "No reply sent" }
            val time = tf.format(Date(event.optLong("t", 0L)))
            "${index + 1}. $time — $kind — $who via $source. Message/call: $message. NEO PLUS action: $reply."
        }
        val report = "Welcome back. I recorded ${events.size} communication events since you asked me to handle messages and calls.\n" +
            details.joinToString("\n")
        prefs(app).edit().putString("report", report).apply()
        return report
    }

    fun SleepMode.lastReport(ctx: Context): String = prefs(ctx.applicationContext).getString("report", "").orEmpty()
