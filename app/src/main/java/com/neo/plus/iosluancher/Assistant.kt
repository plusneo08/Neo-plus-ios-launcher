package com.neo.plus.iosluancher

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/*  Model                                                              */
/* ------------------------------------------------------------------ */

data class ChatMsg(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val notes: List<String> = emptyList(),
    val error: Boolean = false
)

/** Older versions let the user pick Claude / OpenRouter. Their keys are useless now: keep only a Gemini key (the old voice key). */
internal fun migrateToGeminiOnly(prefs: android.content.SharedPreferences) {
    if ((prefs.getString("provider", "GEMINI") ?: "GEMINI") != "GEMINI") {
        prefs.edit().putString("provider", "GEMINI").putString("key", prefs.getString("voice_key", "") ?: "")
            .putString("model", "").apply()
    }
    // Only Gemini 3.8 Flash and Gemini 4 Argon are used now: wipe any older model name saved by earlier versions.
    val saved = prefs.getString("model", "") ?: ""
    if (saved.isNotEmpty() && saved != GeminiModels.FLASH && saved != GeminiModels.ARGON) {
        prefs.edit().putString("model", "").apply()
    }
}

/** The only two AI models this app talks to. */
object GeminiModels {
    /** Fast model: normal chat, phone control, short answers. */
    const val FLASH = "gemini-3.8-flash"
    /** Strongest model: multi-step work on websites and spreadsheets (needs a paid Gemini API key; Flash takes over if the key can't use it). */
    const val ARGON = "gemini-4-argon"

    /** Tried in order when the key has "limit: 0" (no quota) on Flash. */
    val FALLBACKS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    fun sanitize(model: String): String = if (model == ARGON) ARGON else FLASH
}

enum class Provider(val label: String, val defaultModel: String, val keyUrl: String) {
    GEMINI("Gemini", GeminiModels.FLASH, "https://aistudio.google.com/apikey")
}

class ActionResult(val note: String, val ok: Boolean, val leavesLauncher: Boolean, val info: Boolean = false, val card: String? = null)

/** Starts a chat note that carries the notifications as JSON, drawn as a card layout instead of text (see [Bubble]). */
const val NOTIF_MARK = "\u0001NOTIFS\u0001"

/* ------------------------------------------------------------------ */
/*  Finding apps by spoken name                                        */
/* ------------------------------------------------------------------ */

fun List<AppEntry>.findBest(query: String): AppEntry? {
    val q = query.lowercase(Locale.getDefault()).trim().removePrefix("the ").removeSuffix(" app").trim()
    if (q.isEmpty()) return null
    val alias = when (q) {
        "imessage", "messages", "message", "messaging", "sms", "text", "texts", "text messages" -> Role.MESSAGES
        "safari", "browser", "internet", "web browser" -> Role.SAFARI
        "phone", "dialer", "dial pad", "calls" -> Role.PHONE
        "camera" -> Role.CAMERA
        else -> null
    }
    if (alias != null) firstOrNull { it.role == alias }?.let { return it }
    fun score(a: AppEntry): Int {
        val l = a.label.lowercase(Locale.getDefault())
        val s = a.shownLabel.lowercase(Locale.getDefault())
        return when {
            l == q || s == q -> 4
            l.startsWith(q) || s.startsWith(q) -> 3
            l.contains(q) || s.contains(q) -> 2
            else -> 0
        }
    }
    return filter { score(it) > 0 }.maxWithOrNull(compareBy<AppEntry>({ score(it) }, { -it.label.length }))
}

/* ------------------------------------------------------------------ */
/*  Phone control                                                      */
/* ------------------------------------------------------------------ */

object PhoneActions {
    /** Actions that move the user out of the launcher, so the assistant panel closes afterwards. */
    private val leaving = setOf(
        "open_app", "call", "message", "whatsapp", "messenger", "email", "navigate", "open_url", "settings", "control_center", "system",
        "share_file", "whatsapp_call", "post", "ui_tap", "ui_long_press", "ui_type", "ui_scroll", "ui_back", "ui_search", "ui_enter"
    )

    /** Actions that fetch something to tell the user (the note is the answer). */
    val infoActions = setOf("calendar", "weather", "lookup", "news", "stock", "sports", "read_sms", "call_log", "screen", "read_messages", "read_notifications", "read_app", "last_message", "sleep_mode", "sleep_report", "sleep_voice", "time_lock", "send_money", "money_setup", "web_search", "look", "excel_create", "excel_edit", "excel_read", "web_task", "web_batch", "file_read", "ui_list", "post_apps", "queue", "approvals", "schedule", "schedules", "report_number")

    fun run(ctx: Context, name: String, a: JSONObject, apps: List<AppEntry>): ActionResult {
        // Time lock running: only harmless actions, so nothing can be used to get around the lock (also by voice)
        if (TimeLock.active(ctx) && name !in TimeLock.allowedActions) {
            return ActionResult("Couldn’t do that: the phone is time-locked until ${TimeLock.untilText(ctx)}.", false, false, info = true)
        }
        val note = try {
            exec(ctx, name, a, apps)
        } catch (e: Exception) {
            "Couldn’t do that (${e.javaClass.simpleName})"
        }
        val ok = !note.startsWith("Couldn’t") && !note.startsWith("No ") && !note.startsWith("Allow ") &&
            !note.startsWith("Turn on") && !note.startsWith("Pick ") && !note.startsWith("Unknown")
        val card = if (name == "read_notifications" && ok) lastNotifJson else null
        return ActionResult(note, ok, ok && name in leaving && !note.startsWith("Sending your message"), info = name in infoActions, card = card)
    }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun main(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    private fun onOff(a: JSONObject) = a.optBoolean("on", true)

    private fun exec(ctx: Context, name: String, a: JSONObject, apps: List<AppEntry>): String {
        when (name) {
            "open_app" -> {
                val q = a.optString("app")
                val app = apps.findBest(q) ?: return "No app called “$q”"
                if (!AppAccess.allowed(ctx, app.pkg)) return AppAccess.blockedMessage(app.shownLabel)
                ctx.launchApp(app)
                return "Opened ${app.shownLabel}"
            }

            "call" -> {
                val to = a.optString("to")
                val (label, number) = resolveNumber(ctx, to) ?: return "No contact or number for “$to”"
                val sim = a.optInt("sim", 0)
                val chosen = CallSim.pick(ctx, sim)
                if (chosen.ask) return "Which SIM should I use for $label, SIM 1 or SIM 2? Say “SIM 1” or “SIM 2”."
                val action = if (granted(ctx, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse("tel:" + Uri.encode(number)))
                chosen.handle?.let { intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                val ok = ctx.safeStart(intent)
                return if (ok) "Calling $label" + (chosen.slot?.let { " on SIM $it" } ?: "") else "Couldn’t start the call"
            }

            "message" -> {
                val via = a.optString("app").lowercase()
                if (via.contains("whatsapp") || via.contains("w4b")) return exec(ctx, "whatsapp", JSONObject(a.toString()).put("business", via.contains("business") || via.contains("w4b")), apps)
                if (via.contains("messenger") || via.contains("facebook")) return exec(ctx, "messenger", JSONObject(a.toString()), apps)
                val to = a.optString("to")
                val text = a.optString("text")
                val target = resolveNumber(ctx, to)
                if (target == null && to.isNotBlank()) return "No contact or number for “$to”"
                // Real send + sent/delivered reports when SMS permission is granted and there is something to send
                if (target != null && text.isNotBlank() && granted(ctx, Manifest.permission.SEND_SMS)) {
                    return SmsSender.send(ctx, target.second, text, target.first)
                }
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(target?.second ?: "")))
                    .putExtra("sms_body", text)
                return if (ctx.safeStart(i)) "Message to ${target?.first ?: "…"} ready — tap send" else "Couldn’t open Messages"
            }

            "whatsapp" -> {
                val to = a.optString("to")
                val text = a.optString("text")
                val target = resolveNumber(ctx, to) ?: return "No contact or number for “$to”"
                val digits = target.second.filter { it.isDigit() }
                if (digits.isEmpty()) return "No contact or number for “$to”"
                // Really sends: live reply if the chat has a notification, otherwise opens the chat and presses Send.
                return ChatSender.whatsapp(ctx, target.first, to, digits, text, a.optBoolean("business", false))
            }

            "messenger", "facebook" -> {
                val to = a.optString("to")
                val text = a.optString("text")
                return ChatSender.messenger(ctx, to, text, a.optString("app"))
            }

            "queue" -> {
                val inner = a.optString("do")
                if (inner !in Approvals.GATED) return "Couldn’t queue that: I can only hold messages, emails and posts for your OK"
                val args = JSONObject(a.toString())
                args.remove("do")
                val item = Approvals.add(ctx, inner, args, apps)
                return "Waiting for your OK: " + item.summary + ". Tap Send or Skip in the notification." + (if (args.optBoolean("urgent")) " I’m calling you now because it’s urgent." else "")
            }
            "approvals" -> return Approvals.handle(ctx, a.optString("do"))
            "schedule" -> return Scheduler.add(ctx, a)
            "schedules" -> return Scheduler.handle(ctx, a)
            "report_number" -> return TaskReports.setSms(ctx, a.optString("number"))
            "email" -> return Comms.email(ctx, a.optString("to"), a.optString("subject"), a.optString("text"))

            "share_file" -> return ShareSender.send(ctx, a)

            "post" -> return SocialPoster.post(ctx, a, apps)
            "post_apps" -> return SocialPoster.supported(ctx)
            "ui_tap", "ui_long_press", "ui_type", "ui_scroll", "ui_back", "ui_search", "ui_enter", "ui_list", "ui_wait" ->
                return UiDriver.exec(ctx, name, a, apps)

            "whatsapp_call" -> return WhatsAppCall.call(ctx, a.optString("to"), a.optBoolean("video", false), a.optBoolean("business", false))

            "calendar" -> return readCalendar(ctx, a.optInt("days", 1).coerceIn(1, 14))

            "weather" -> return weatherNow(ctx, a.optString("place"))

            "lookup" -> return lookUp(a.optString("query"))

            "read_messages" -> return readMessages(ctx, a.optInt("count", 5).coerceIn(1, 10))
            "last_message" -> return lastMessage(ctx, a.optString("with"))
            "read_notifications" -> return readNotifications(ctx, a.optInt("count", 8).coerceIn(1, 20), a.optString("app"))
            "reply_message" -> return replyMessage(ctx, a.optString("to"), a.optString("text"))
            "send_money" -> return MoneySender.request(ctx, a)
            "money_setup" -> return if (a.has("network")) MoneySteps.setup(ctx, a) else MoneySender.setup(ctx, a.optString("ussd"))
            "look" -> return look()
            "excel_create" -> return ExcelActions.create(ctx, a)
            "excel_edit" -> return ExcelActions.edit(ctx, a)
            "excel_read" -> return ExcelActions.read(ctx, a)
            "web_task" -> return WebTask.run(ctx, a)
            "web_batch" -> return WebTask.runBatch(ctx, a)
            "file_read" -> return FileTools.read(ctx, a)
            "continue_web_task" -> return if (WebTask.resume()) "Continuing the website task" else "The website task is not waiting for you"
            "stop_web_task" -> { WebTask.cancel(); return if (WebTask.running) "Stopping the website task" else "No website task is running" }
            "screen_share" -> return screenShare(a.optBoolean("on", true))
            "read_app" -> {
                val app = a.optString("app")
                val from = a.optString("from")
                val sms = if (AppReader.isSms(app) && granted(ctx, Manifest.permission.READ_SMS)) readSms(ctx, 5, from) + "\n" else ""
                return sms + AppReader.read(ctx, app, from, a.optInt("count", 8).coerceIn(1, 15))
            }
            "read_sms" -> return readSms(ctx, a.optInt("count", 3).coerceIn(1, 8), a.optString("from"))
            "call_log" -> return readCallLog(ctx, a.optInt("count", 5).coerceIn(1, 10))
            "screen" -> return readScreen()
            "news" -> return news(a.optString("query"))
            "stock" -> return stock(a.optString("symbol"))
            "sports" -> return sports(a.optString("league"))
            "remember" -> return remember(ctx, a.optString("fact"))
            "forget" -> return forget(ctx, a.optString("fact"), a.optBoolean("all", false))

            "alarm" -> {
                val h = a.getInt("hour").coerceIn(0, 23)
                val m = a.optInt("minute", 0).coerceIn(0, 59)
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", ""))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return if (ctx.safeStart(i)) "Alarm set for %02d:%02d".format(h, m) else "Couldn’t set the alarm"
            }

            "timer" -> {
                val s = a.getInt("seconds").coerceAtLeast(1)
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", ""))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val text = if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
                return if (ctx.safeStart(i)) "Timer started: $text" else "Couldn’t start the timer"
            }

            "flashlight" -> {
                val on = onOff(a)
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "No flashlight on this phone"
                cm.setTorchMode(id, on)
                return if (on) "Flashlight on" else "Flashlight off"
            }

            "wifi" -> { Toggles.setWifi(ctx, onOff(a)); return "Wi‑Fi ${if (onOff(a)) "on" else "off"}" }
            "bluetooth" -> { Toggles.setBluetooth(ctx, onOff(a)); return "Bluetooth ${if (onOff(a)) "on" else "off"}" }
            "airplane" -> { Toggles.setAirplane(ctx, onOff(a)); return "Airplane mode ${if (onOff(a)) "on" else "off"}" }
            "mobile_data" -> { Toggles.setMobileData(ctx, onOff(a)); return "Mobile data ${if (onOff(a)) "on" else "off"}" }
            "dark_mode" -> { Toggles.setDarkMode(ctx, onOff(a)); return "Dark mode ${if (onOff(a)) "on" else "off"}" }
            "low_power", "battery_saver" -> { Toggles.setLowPower(ctx, onOff(a)); return "Low Power mode ${if (onOff(a)) "on" else "off"}" }

            "kiosk" -> {
                val on = onOff(a)
                val app = ctx.applicationContext
                if (!Kiosk.isOwner(ctx)) {
                    if (!on) return "Full-phone launcher mode is already off"
                    Thread {
                        val ok = Kiosk.becomeOwnerViaRoot(app)
                        Handler(Looper.getMainLooper()).post {
                            if (ok) { Kiosk.setEnabled(app, true); Kiosk.configure(app); MainActivity.current?.startKiosk() }
                            else android.widget.Toast.makeText(app, "Run on a computer: sh grant-all.sh --kiosk (see README)", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.start()
                    return "Setting up full-phone launcher mode…"
                }
                Kiosk.setEnabled(ctx, on)
                main { if (on) MainActivity.current?.startKiosk() else MainActivity.current?.stopKiosk() }
                return if (on) "Full-phone launcher mode on" else "Full-phone launcher mode off"
            }

            "release_device_owner" -> {
                main { MainActivity.current?.stopKiosk() }
                return if (Kiosk.release(ctx)) "Device owner removed" else "This app is not the device owner"
            }

            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    ctx.safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return "Allow Do Not Disturb access, then ask again"
                }
                nm.setInterruptionFilter(
                    if (onOff(a)) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                return "Focus ${if (onOff(a)) "on" else "off"}"
            }

            "volume" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = if (a.optString("stream") == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val fl = if (stream == AudioManager.STREAM_RING) AudioManager.FLAG_ALLOW_RINGER_MODES else 0
                try {
                    when {
                        a.has("level") -> am.setStreamVolume(stream, (a.getInt("level") / 100f * max).roundToInt().coerceIn(0, max), fl)
                        a.optString("direction") == "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, fl)
                        a.optString("direction") == "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, f