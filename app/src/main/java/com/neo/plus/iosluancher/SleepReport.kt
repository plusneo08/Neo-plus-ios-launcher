package com.neo.plus.iosluancher

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

private const val SLEEP_REPORT_SYSTEM = """You are Neo Plus Assistant, speaking to Neo on a phone call right after he woke up. Tell him what happened during the night.
Sound like a real person chatting on the phone, not like someone reading a report: warm, relaxed, contractions ("you've", "I didn't"), short and medium sentences mixed, small natural connectors like "so", "also", "and one more thing". Plain text only, no markdown, no lists. Say times the way people say them out loud ("around two in the morning"), never as digits like 02:15. Speak English unless Neo's messages show he wants otherwise.
Start with "Good morning." Then cover, in this order: who wrote to him and what about, and what you answered; then messages that mention money: say only who wrote and on which app, that you did NOT answer them and they are waiting for him, and never read out amounts, numbers or account details; then other notifications grouped by app with counts; then group chats you left alone.
Phone calls, goal progress (app starts with Goal), texts on the assistant line and switched-off permissions also count: mention each in plain words, calls and texts on the assistant line by who and what you did. Skip a part when it is empty. If nothing happened say it was a quiet night. Never read out codes or passwords. Do not invent anything. Keep it under about 150 words unless a lot happened. End by asking nothing, just say have a good day."""

    fun SleepMode.report(ctx: Context): String {
        val app = ctx.applicationContext
        val arr = try { JSONArray(prefs(app).getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        val tf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        for (i in maxOf(0, arr.length() - 80) until arr.length()) {
            val o = arr.getJSONObject(i)
            sb.append("[").append(tf.format(Date(o.optLong("t")))).append("] ").append(o.optString("k")).append(" | ")
                .append(o.optString("app")).append(" | ").append(o.optString("who")).append(" | ")
                .append(o.optString("text").take(160).replace('\n', ' ')).append(" | my reply: ")
                .append(o.optString("reply").ifBlank { "none" }.take(160).replace('\n', ' ')).append('\n')
        }
        if (arr.length() == 0) return "Good morning. It was a quiet night: nothing came in."
        val key = AssistantConfig.load(app).key
        val text = try {
            if (key.isBlank()) throw Exception("no key")
            Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, SLEEP_REPORT_SYSTEM,
                listOf(true to ("Kinds: REPLIED=I answered, MONEY=money message not answered, MSG=message not answered, GROUP=group chat not answered, AUTO=automated message, NOTE=other notification, FAILED=could not answer.\n\n" + sb)), null, false).trim()
        } catch (e: Exception) { fallbackReport(arr) }
        prefs(app).edit().putString("report", text).apply()
        return text
    }

    internal fun SleepMode.fallbackReport(arr: JSONArray): String {
        val c = HashMap<String, Int>()
        val money = LinkedHashSet<String>()
        val people = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = o.optString("k")
            c[k] = (c[k] ?: 0) + 1
            if (k == "MONEY") money.add(o.optString("who") + " on " + o.optString("app"))
            if (k == "REPLIED" || k == "MSG") people.add(o.optString("who"))
        }
        val parts = ArrayList<String>()
        parts.add("Good morning.")
        if (people.isNotEmpty()) parts.add("${people.size} people wrote to you: ${people.joinToString(", ")}. I answered ${c["REPLIED"] ?: 0} of them.")
        if (money.isNotEmpty()) parts.add("There are money messages from ${money.joinToString(", ")}. I did not answer them, they are waiting for you.")
        if ((c["NOTE"] ?: 0) > 0) parts.add("You also had ${c["NOTE"]} other notifications.")
        if ((c["GROUP"] ?: 0) > 0) parts.add("${c["GROUP"]} group messages were left alone.")
        return parts.joinToString(" ")
    }

    fun SleepMode.lastReport(ctx: Context): String = prefs(ctx.applicationContext).getString("report", "").orEmpty()
