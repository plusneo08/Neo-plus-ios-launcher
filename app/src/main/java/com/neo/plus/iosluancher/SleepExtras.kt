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

/** Plays the "phone ringing" sound on the alarm volume, so it is heard even when Do Not Disturb is on. */
object WakeRinger {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var ringing = false
        private set

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        CallAwareness.refreshAsync(app)   // read your situation while it rings, so it is ready when you answer
        stop()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(app, uri)?.also {
                it.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                it.play()
            }
            vibrator = (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.also {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
            }
        } catch (e: Exception) { }
        ringing = true
        main.postDelayed({ if (ringing) SleepMode.missedCall(app) }, 60_000L)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        ringing = false
        try { ringtone?.stop() } catch (e: Exception) { }
        try { vibrator?.cancel() } catch (e: Exception) { }
        ringtone = null
        vibrator = null
    }
}

class SleepWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { SleepMode.ring(context) }
}

class SleepBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SleepMode.rearm(context)
    }
}

/** Understands "I'm sleeping, call me at 6:30", "I'm awake" and "what did I miss" without asking the AI model. */
object SleepCmd {
    private val I = RegexOption.IGNORE_CASE
    private val offRe = Regex("\\b(?:i['’]?m|i am|im) (?:awake|up now)\\b|\\b(?:stop|end|turn off|cancel|switch off|disable) (?:the )?sleep mode\\b|\\bsleep mode off\\b|\\bndadzuka\\b", I)
    private val reportRe = Regex("\\bwhat did i miss\\b|\\b(?:night|sleep|overnight) (?:report|summary)\\b|\\bsummary of the night\\b|\\bwhat happened (?:last night|while i (?:was )?sleeping)\\b", I)
    private val freeRe = Regex("\\b(?:free|phone|offline|system)\\b.{0,20}\\bvoice\\b.{0,40}\\b(?:call|sleep|wake)|\\b(?:call|sleep|wake)\\b.{0,40}\\b(?:free|phone|offline)\\b.{0,15}\\bvoice\\b", I)
    private val gemRe = Regex("\\b(?:gemini|natural|ai)\\b.{0,15}\\bvoice\\b.{0,40}\\b(?:call|sleep|wake)|\\b(?:call|sleep|wake)\\b.{0,40}\\b(?:gemini|natural|ai)\\b.{0,15}\\bvoice\\b", I)
    private val sleepRe = Regex("\\b(?:sleep|sleeping|going to (?:bed|sleep)|good ?night|bedtime)\\b|ndikugona|ndigona", I)
    private val timeRe = Regex("(?:\\bcall\\b|\\bwake\\b|\\bring\\b|\\buntil\\b|\\btill\\b|\\bat\\b|\\bby\\b|\\baround\\b)[^0-9]{0,15}(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", I)

    private val wakeRe = Regex("\\bwake me\\b|\\bwake[- ]?up call\\b|\\bgive me a wake\\b|\\bcall me (?:to|and) wake\\b", I)

    private val answerRe = Regex("\\b(?:answer|reply(?: to)?|respond to|handle|cover)\\b.{0,12}\\bmessages?\\b|\\breply for me\\b", I)
    private val busyRe = Regex("\\b(?:i['’]?m|i am|im)\\s+(.{3,40}?)(?=\\s*(?:[,.;]|\\band\\b|\\buntil\\b|\\btill\\b|\\bfor the next\\b|\\b(?:call|wake|ring)\\b|$))", I)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        if (freeRe.containsMatchIn(t)) return LocalPlan("", listOf("sleep_voice" to JSONObject().put("free", true)))
        if (gemRe.containsMatchIn(t)) return LocalPlan("", listOf("sleep_voice" to JSONObject().put("free", false)))
        if (offRe.containsMatchIn(t)) return LocalPlan("", listOf("sleep_mode" to JSONObject().put("on", false)))
        if (reportRe.containsMatchIn(t)) return LocalPlan("", listOf("sleep_report" to JSONObject()))
        if (wakeRe.containsMatchIn(t) && !sleepRe.containsMatchIn(t) && !t.contains("sleep mode", true)) {
            // "wake me up at 8:30 am": a wake-up call at that time (not a plain alarm, and no answering of messages)
            val wm = timeRe.find(t)
            var wh = wm?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (wm != null && wh >= 0) {
                val wmin = wm.groupValues[2].toIntOrNull() ?: 0
                val wap = wm.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
                if (wap == "pm" && wh < 12) wh += 12
                if (wap == "am" && wh == 12) wh = 0
                if (wh in 0..23 && wmin in 0..59) return LocalPlan("", listOf("wake_call" to JSONObject().put("hour", wh).put("minute", wmin)))
            }
        }
        if (!sleepRe.containsMatchIn(t) && !t.contains("sleep mode", true)) {
            // "I'm in a meeting until 3pm, answer my messages": same as sleep mode, but the replies say what you are doing
            if (!answerRe.containsMatchIn(t)) return null
            val act = busyRe.find(t)?.groupValues?.get(1)?.trim().orEmpty()
            val tm = timeRe.find(t) ?: return null
            if (act.isEmpty()) return null
            var bh = tm.groupValues[1].toIntOrNull() ?: return null
            val bm = tm.groupValues[2].toIntOrNull() ?: 0
            val bap = tm.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
            if (bap == "pm" && bh < 12) bh += 12
            if (bap == "am" && bh == 12) bh = 0
            if (bh !in 0..23 || bm !in 0..59) return null
            SleepMode.pendingActivity = act
            SleepMode.pendingAt = System.currentTimeMillis()
            return LocalPlan("", listOf("sleep_mode" to JSONObject().put("on", true).put("hour", bh).put("minute", bm).put("activity", act)))
        }
        val m = timeRe.find(t) ?: run {
            // "enable sleep mode" with no wake-up time: do not ask for one, use the default (07:00) and say so
            if (t.contains("sleep mode", true) || t.contains("sleeping mode", true))
                return LocalPlan("", listOf("mode" to JSONObject().put("on", true).put("mode", "sleep")))
            return null
        }
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return LocalPlan("", listOf("sleep_mode" to JSONObject().put("on", true).put("hour", h).put("minute", min)))
    }
}

/** Makes written text sound right when read aloud: no markdown or emoji, times said the way people say them. */
object SpokenText {
    private val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s*([ap]\\.?m\\.?)?", RegexOption.IGNORE_CASE)

    fun clean(text: String): String {
        var s = text
        s = s.replace(Regex("https?://\\S+"), "a link")
        s = s.replace(Regex("[*_`#>~|]+"), "")
        s = s.replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        s = s.replace(Regex("[\\p{So}\\p{Cs}]"), "")
        s = time.replace(s) { m -> say(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }
        return s.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun say(h: Int, min: Int, ampm: String): String {
        val ap = ampm.lowercase(Locale.ROOT).replace(".", "")
        val h24 = when {
            ap == "pm" && h < 12 -> h + 12
            ap == "am" && h == 12 -> 0
            else -> h
        }
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val part = when (h24) { in 5..11 -> "in the morning"; in 12..16 -> "in the afternoon"; in 17..20 -> "in the evening"; else -> "at night" }
        val m = when { min == 0 -> ""; min < 10 -> " oh $min"; else -> ":$min" }
        return "$h12$m $part"
    }
}
