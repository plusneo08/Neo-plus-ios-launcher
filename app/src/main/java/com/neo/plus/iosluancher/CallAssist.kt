package com.neo.plus.iosluancher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Incoming calls, handled by the assistant.
 *
 *  announce      says who is calling (and shows Answer / Decline / Decline + text buttons)      [default]
 *  decline       hangs up on the caller
 *  decline_text  hangs up and sends the caller a short text (once per number every 6 hours)
 *  speaker       answers and switches to the loudspeaker, so the owner can talk hands-free
 *  greeting      answers on the loudspeaker, plays a spoken greeting that says it is the assistant, then hangs up
 *
 * Rules are per contact name / number, "unknown" (numbers not in Contacts) or "everyone".
 * Android does not let an app listen to a normal phone call or speak into it: the greeting is played on the loudspeaker
 * and the caller hears it through the phone's microphone, so it is one-way and the quality depends on the phone.
 */
object CallAssist {
    private const val PREF = "neo_calls"
    private const val CH = "neo_calls"
    private const val NOTIF = 7741
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var latestNumber = ""
    @Volatile private var latestSlot = 0
    @Volatile private var ringHandled = false
    @Volatile private var ringingNumber = ""
    @Volatile private var ringingName = ""
    @Volatile private var ringing = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    private fun owner(ctx: Context) = prefs(ctx).getString("owner", "").orEmpty()

    private fun rules(ctx: Context): List<Triple<String, String, String>> = try {
        val a = JSONArray(prefs(ctx).getString("rules", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.optString("who"), o.optString("action"), o.optString("text")) }
    } catch (e: Exception) { emptyList() }

    private fun saveRules(ctx: Context, list: List<Triple<String, String, String>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("who", it.first).put("action", it.second).put("text", it.third)) }
        prefs(ctx).edit().putString("rules", a.toString()).apply()
    }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun lookupName(ctx: Context, number: String): String {
        if (number.isBlank() || !granted(ctx, Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }.orEmpty()
        } catch (e: Exception) { "" }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }; val y = b.filter { it.isDigit() }
        return x.length >= 7 && y.length >= 7 && x.takeLast(9) == y.takeLast(9)
    }

    private fun pick(ctx: Context, number: String, name: String): Pair<String, String> {
        val rs = rules(ctx)
        rs.firstOrNull { r -> r.first.isNotBlank() && r.first != "unknown" && r.first != "everyone" &&
            ((name.isNotBlank() && name.contains(r.first, ignoreCase = true)) || sameNumber(r.first, number)) }?.let { return it.second to it.third }
        if (name.isBlank()) rs.firstOrNull { it.first == "unknown" }?.let { return it.second to it.third }
        rs.firstOrNull { it.first == "everyone" }?.let { return it.second to it.third }
        return "announce" to ""
    }

    /* ---------------- phone state ---------------- */

    fun onNumber(n: String?) { if (!n.isNullOrBlank()) latestNumber = n }
    fun onSlot(s: Int) { if (s in 1..2) latestSlot = s }

    fun onRinging(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app) || ringHandled) return
        ringHandled = true
        ringing = true
        // The system sends the ringing broadcast twice; the number is only in one of them, so wait a moment for it.
        var number = latestNumber
        if (number.isBlank() && granted(app, Manifest.permission.READ_CALL_LOG)) { try { Thread.sleep(700) } catch (e: InterruptedException) { }; number = latestNumber }
        val name = lookupName(app, number)
        ringingNumber = number; ringingName = name
        val who = name.ifBlank { number.ifBlank { "an unknown caller" } }
        val slot = latestSlot
        val onLine = slot != 0 && slot == AssistantLine.lineSlot(app)
        val picked = pick(app, number, name)
        // A rule for this exact person wins; otherwise a call on the assistant's own SIM follows the line rule
        val specific = rules(app).any { r -> r.first.isNotBlank() && r.first != "unknown" && r.first != "everyone" &&
            ((name.isNotBlank() && name.contains(r.first, ignoreCase = true)) || sameNumber(r.first, number)) }
        val (action, text) = if (onLine && !specific) AssistantLine.lineAction(app) else picked
        val via = if (onLine) " on your assistant line (${AssistantLine.label(app, slot)})" else ""
        when (action) {
            "decline" -> { endCall(app); notify(app, "Declined a call", "Declined the call from $who", false) }
            "decline_text" -> {
                val r = declineWithText(app, number, name, text)
                notify(app, "Declined a call", "Declined the call from $who. $r", false)
            }
            "speaker" -> {
                val r = answer(app, speaker = true)
                notify(app, "Answered on speaker", "$who: $r", false)
            }
            "greeting" -> {
                val r = answerWithGreeting(app, text)
                notify(app, "Answered with a greeting", "$who: $r", false)
            }
            else -> {
                Announcer.say(app, "Call from $who$via. Say answer, decline, or decline and text.")
                notify(app, "Call from $who", (if (onLine) "On your assistant line. " else "") + "Answer, decline, or decline with a text", true)
            }
        }
    }

    fun onCallEnded(ctx: Context) {
        ringHandled = false
        ringing = false
        latestNumber = ""
        latestSlot = 0
        try { (ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF) } catch (e: Exception) { }
    }

    /* ---------------- doing things to the call ---------------- */

    private fun telecom(ctx: Context) = ctx.applicationContext.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun endCall(ctx: Context): Boolean {
        if (!granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try { Build.VERSION.SDK_INT >= 28 && telecom(ctx).endCall() } catch (e: Exception) { false }
    }

    private fun accept(ctx: Context): Boolean {
        if (!granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try { telecom(ctx).acceptRingingCall(); true } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun speakerOn(ctx: Context) {
        try {
            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
        } catch (e: Exception) { }
    }

    private const val NEED_PERM = "I need the “Phone calls” permission: allow it in the app’s permissions"

    fun answer(ctx: Context, speaker: Boolean): String {
        if (!accept(ctx)) return NEED_PERM
        if (speaker) main.postDelayed({ speakerOn(ctx) }, 1300)
        return if (speaker) "Answered on the loudspeaker" else "Answered"
    }

    private fun greetingText(ctx: Context, custom: String): String {
        if (custom.isNotBlank()) return custom
        val o = owner(ctx)
        return if (o.isBlank()) "Hello, this is the phone’s assistant. The owner can’t take your call right now. Please send a text message and you will get a reply soon."
        else "Hello, this is the assistant of $o. $o can’t take your call right now. Please send a text message and you will get a reply soon."
    }

    private fun answerWithGreeting(ctx: Context, custom: String): String {
        if (!accept(ctx)) return NEED_PERM
        val app = ctx.applicationContext
        main.postDelayed({
            speakerOn(app)
            var ended = false
            fun finish() { if (!ended) { ended = true; endCall(app) } }
            // Hang up after the greeting, with a hard stop in case the speech engine never reports back
            try {
                Speaker.speak(app, greetingText(app, custom), onDone = { main.postDelayed({ finish() }, 1500) }, natural = true)
            } catch (e: Exception) { main.postDelayed({ finish() }, 8000) }
            main.postDelayed({ finish() }, 30_000)
        }, 1400)
        return "Answered, playing the greeting, then hanging up"
    }

    private fun declineWithText(ctx: Context, number: String, name: String, custom: String): String {
        endCall(ctx)
        if (number.isBlank()) return "No number to text"
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return "I can’t send the text: allow SMS for Neo Plus"
        val p = prefs(ctx)
        val key = "sms_" + number.filter { it.isDigit() }.takeLast(9)
        if (System.currentTimeMillis() - p.getLong(key, 0L) < 6 * 3600_000L) return "Already texted them in the last 6 hours"
        p.edit().putLong(key, System.currentTimeMillis()).apply()
        val o = owner(ctx)
        val body = custom.ifBlank { "Sorry, I can’t take your call right now" + (if (o.isNotBlank()) " ($o’s assistant)" else "") + ". Please send a text message and I’ll reply soon." }
        SmsSender.send(ctx, number, body, name.ifBlank { number })
        return "Sent them a text"
    }

    /* ---------------- notification with buttons ---------------- */

    private fun notify(ctx: Context, title: String, text: String, buttons: Boolean) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Calls", NotificationManager.IMPORTANCE_HIGH))
            }
            val b = NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setOnlyAlertOnce(true)
            fun act(code: Int, what: String, label: String) {
                val i = Intent(ctx, CallActionReceiver::class.java).setAction("neo.call.$what").putExtra("what", what)
                b.addAction(0, label, PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (buttons) { act(1, "answer", "Answer"); act(2, "decline", "Decline"); act(3, "decline_text", "Decline + text") }
            // the buttons notification is removed when the call is answered or ends; the result note stays
            nm.notify(if (buttons) NOTIF else NOTIF + 1, b.build())
        } catch (e: Exception) { }
    }

    /* ---------------- the `call_assist` action ---------------- */

    fun handle(ctx: Context, a: JSONObject): String {
        val app = ctx.applicationContext
        return when (a.optString("do").lowercase()) {
            "answer" -> if (!ringing) "No call is ringing" else answer(app, a.optBoolean("speaker", false))
            "decline" -> if (!ringing) "No call is ringing" else { endCall(app); "Declined" }
            "decline_text" -> if (!ringing) "No call is ringing" else declineWithText(app, ringingNumber, ringingName, a.optString("text"))
            "owner" -> { prefs(app).edit().putString("owner", a.optString("name").trim().take(40)).apply(); "The greeting will say it is ${a.optString("name").trim()}’s assistant" }
            "line" -> AssistantLine.configure(app, a)
            "line_log" -> AssistantLine.recent(app)
            "on" -> { prefs(app).edit().putBoolean("on", true).apply(); "Call assistant is on" }
            "off" -> { prefs(app).edit().putBoolean("on", false).apply(); "Call assistant is off: calls ring as normal" }
            "rule" -> {
                val who = a.optString("who").trim().lowercase().ifBlank { return "Say whose calls: a contact name, “unknown” or “everyone”" }
                val action = a.optString("action").trim().lowercase()
                val rs = rules(app).filter { !it.first.equals(who, true) }
                if (action == "remove" || action == "announce") { saveRules(app, rs); return "Calls from $who: just announced" }
                if (action !in listOf("decline", "decline_text", "speaker", "greeting")) return "Choose: announce, decline, decline_text, speaker or greeting"
                if ((action == "decline" || action == "decline_text" || action == "greeting" || action == "speaker") && !granted(app, Manifest.permission.ANSWER_PHONE_CALLS)) return NEED_PERM
                saveRules(app, rs + Triple(who, action, a.optString("text").trim()))
                "Calls from $who: $action"
            }
            else -> {
                val rs = rules(app)
                "Call assistant is ${if (enabled(app)) "on" else "off"}" + (if (owner(app).isNotBlank()) ", greeting names ${owner(app)}" else "") +
                    (if (rs.isEmpty()) ". Every call is just announced." else ". " + rs.joinToString("; ") { "${it.first}: ${it.second}" })
            }
        }
    }
}

/** The system tells the app when a call rings, is answered or ends. */
class CallStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = context.applicationContext
        CallAssist.onNumber(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
        CallAssist.onSlot(AssistantLine.slotOf(app, intent))
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val pending = goAsync()
                Thread {
                    try { CallAssist.onRinging(app) } catch (e: Exception) { }
                    try { pending.finish() } catch (e: Exception) { }
                }.start()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> CallAssist.onCallEnded(app)
            else -> CallAssist.onCallEnded(app) // answered: the buttons are no longer needed
        }
    }
}

/** The Answer / Decline buttons on the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val what = intent.getStringExtra("what") ?: return
        val r = CallAssist.handle(app, JSONObject().put("do", what).put("speaker", false))
        android.widget.Toast.makeText(app, r, android.widget.Toast.LENGTH_SHORT).show()
    }
}
