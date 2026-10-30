package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.provider.CallLog
import java.util.Calendar
import kotlin.math.sqrt

/**
 * CLAP TWICE TO WAKE.
 *
 * While the screen is off the assistant stops listening for words (the phone's microphone can only serve one listener at a
 * time) and listens for two sharp claps instead. Two claps, about a quarter to three quarters of a second apart, wake the
 * screen, start the unlock and greet you out loud, followed by a report of what happened while the screen was off:
 * missed calls and every notification that arrived.
 *
 * Android never lets an app type your PIN, pattern or password, so with a screen lock set the phone still asks for your
 * fingerprint / face / PIN after the claps (the greeting says so). With no screen lock the phone opens straight away.
 */
object ClapWake {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("neo_clap", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply() }

    /** May two claps also OPEN the lock (only possible when Android has no PIN of its own: see [wakeAndUnlock])? */
    fun unlockEnabled(c: Context) = prefs(c).getBoolean("unlock", true)
    fun setUnlockEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("unlock", on).apply() }

    /**
     * Wakes the screen and opens the phone as far as Android allows, and returns what happened (the greeting uses it).
     *  - Neo lock replacing Android's lock: opens it by itself (if "clap also unlocks" is on), no PIN needed.
     *  - phone with no lock, or only a swipe: dismisses the lock screen.
     *  - phone with an Android PIN / pattern / fingerprint: wakes the screen and shows the unlock prompt. Android never lets an
     *    app enter these for you.
     */
    fun wakeAndUnlock(ctx: Context): String {
        val app = ctx.applicationContext
        PowerControl.wakeScreen(app)
        if (NeoLock.replaceOn(app)) {
            if (!NeoLock.needsLock(app)) return "The phone is already unlocked"
            if (unlockEnabled(app) && NeoLock.unlockBySelf(app)) return "Unlocked"
            NeoLock.showIfNeeded(app)
            return "Screen is on. Enter your Neo PIN, password or pattern to unlock"
        }
        if (!unlockEnabled(app)) return "Screen is on"
        return PowerControl.unlock(app)
    }

    private fun nameFor(c: Context, number: String): String = try {
        if (number.isBlank() || !granted(c, Manifest.permission.READ_CONTACTS)) number
        else {
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: number
        }
    } catch (e: Exception) { number }

    /** When the screen last turned off: the report covers everything after this. */
    @Volatile var screenOffAt = 0L

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Hello"
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /** The spoken greeting plus a report of everything since [since]. [unlockNote] is what the unlock attempt said. */
    fun report(ctx: Context, since: Long, unlockNote: String): String {
        val app = ctx.applicationContext
        val from = if (since > 0L) since else System.currentTimeMillis() - 60 * 60_000L
        val out = StringBuilder(greeting()).append(". ")
        if (unlockNote.contains("fingerprint", true) || unlockNote.contains("PIN", true)) {
            out.append("I woke the screen. Your phone still needs your fingerprint, face or PIN to unlock. ")
        }

        val parts = ArrayList<String>()

        // missed calls
        try {
            if (granted(app, Manifest.permission.READ_CALL_LOG)) {
                app.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                    "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                    arrayOf(CallLog.Calls.MISSED_TYPE.toString(), from.toString()), "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    val names = ArrayList<String>()
                    while (c.moveToNext() && names.size < 5) {
                        val n = c.getString(1).orEmpty().ifBlank { c.getString(0).orEmpty() }.ifBlank { "an unknown number" }
                        if (n !in names) names.add(n)
                    }
                    if (names.isNotEmpty()) parts += "You missed ${if (names.size == 1) "a call" else "calls"} from " + names.joinToString(", ")
                }
            }
        } catch (e: Exception) { }

        // text messages (SMS) that came in
        try {
            if (granted(app, Manifest.permission.READ_SMS)) {
                app.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI, arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY),
                    "${android.provider.Telephony.Sms.DATE} >= ?", arrayOf(from.toString()), "${android.provider.Telephony.Sms.DATE} ASC"
                )?.use { c ->
                    val lines = ArrayList<String>()
                    var total = 0
                    while (c.moveToNext()) {
                        total++
                        if (lines.size < 5) lines += "${nameFor(app, c.getString(0).orEmpty())} texted: ${c.getString(1).orEmpty().replace(Regex("\\s+"), " ").take(90)}"
                    }
                    if (total > lines.size) lines += "and ${total - lines.size} more texts"
                    if (lines.isNotEmpty()) parts += lines.joinToString(". ")
                }
            }
        } catch (e: Exception) { }

        // every notification that arrived (messages first)
        try {
            val notes = NotifStore.allRecent(60, "").filter { it.time >= from }.reversed()
            val chats = notes.filter { it.reply != null }
            val others = notes.filter { it.reply == null }
            val lines = ArrayList<String>()
            for (n in chats.take(6)) lines += "${n.title.ifBlank { n.app }} on ${n.app} said: ${n.text.replace(Regex("\\s+"), " ").take(90)}"
            if (chats.size > 6) lines += "and ${chats.size - 6} more messages"
            val byApp = others.groupBy { it.app }
            for ((a, list) in byApp.entries.take(5)) {
                lines += if (list.size == 1) "$a: ${list[0].title.ifBlank { list[0].text }.take(70)}" else "$a: ${list.size} notifications"
            }
            if (byApp.size > 5) lines += "and ${byApp.size - 5} more apps"
            if (lines.isNotEmpty()) parts += "Here is what came in. " + lines.joinToString(". ")
        } catch (e: Exception) { }

        if (parts.isEmpty()) out.append("Nothing happened while you were away.")
        else out.append(parts.joinToString(". ")).append(".")
        return out.toString()
    }
}

/**
 * Listens to the microphone for two claps. A clap is a short, sharp burst well above the room's background level (it ends
 * within about 0.16 s); speech, music and a TV last longer and are ignored. Runs on its own thread with a partial wake lock
 * so it keeps listening with the screen off. [onDouble] is called on that thread; [onFail] when the microphone can't be opened.
 */
class ClapDetector(private val ctx: Context, private val onDouble: () -> Unit, private val onFail: () -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lock: PowerManager.WakeLock? = null

    fun start() {
        if (running) return
        running = true
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neo:clap").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
        thread = Thread({ loop() }, "neo-clap").also { it.start() }
    }

    fun stop() {
        running = false
        try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        lock = null
        // Wait until the clap thread has really let go of the microphone. While it still holds it, the speech recogniser
        // (a different app process) is silenced by Android: the orb says "listening" but nothing is heard.
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) { try { t.join(800) } catch (e: InterruptedException) { } }
    }

    private fun loop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Exception) { }
        val rate = 16000
        val frameLen = 160 // 10 ms
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord = try {
            val x = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameLen * 2 * 16))
            if (x.state != AudioRecord.STATE_INITIALIZED) { try { x.release() } catch (e: Exception) { }; throw IllegalStateException("mic") }
            x.startRecording()
            x
        } catch (e: Throwable) {
            if (running) { running = false; onFail() }
            return
        }
        val buf = ShortArray(frameLen)
        var bg = 400.0           // the room's usual loudness (RMS)
        var t = 0L               // time in ms, counted in frames
        var inClap = false
        var ignoring = false     // a long loud sound (talking, music): wait until it is over
        var startT = 0L
        var lastClapT = -100_000L
        var holdOffUntil = 0L
        var failures = 0
        while (running) {
            val n = try { rec.read(buf, 0, frameLen) } catch (e: Exception) { -1 }
            if (n <= 0) {
                failures++
                if (failures > 100) { running = false; onFail() }
                try { Thread.sleep(20) } catch (e: InterruptedException) { }
                continue
            }
            failures = 0
            t += 10
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
            val rms = sqrt(sum / n)
            val quiet = maxOf(bg * 2.5, 700.0)
            when {
                ignoring -> {
                    bg = bg * 0.95 + rms * 0.05
                    if (rms < quiet) ignoring = false
                }
                !inClap -> {
                    if (t >= holdOffUntil && rms > 1500.0 && rms > bg * 4.0) { inClap = true; startT = t }
                    else bg = (bg * 0.98 + rms * 0.02).coerceIn(80.0, 4000.0)
                }
                else -> {
                    val dur = t - startT
                    if (rms < quiet) {
                        inClap = false
                        if (dur in 10..160) {
                            val gap = startT - lastClapT
                            if (gap in 150..900) {
                                lastClapT = -100_000L
                                holdOffUntil = t + 4000
                                onDouble()
                            } else lastClapT = startT
                        }
                    } else if (dur > 200) { inClap = false; ignoring = true }
                }
            }
        }
        try { rec.stop() } catch (e: Exception) { }
        try { rec.release() } catch (e: Exception) { }
    }
}
