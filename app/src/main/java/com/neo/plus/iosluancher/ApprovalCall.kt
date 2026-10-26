package com.neo.plus.iosluancher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * The assistant "calls" the user when something needs their OK:
 *  - once when the waiting list reaches Approvals.CALL_AT items (not for every item),
 *  - immediately for an urgent item.
 * Answering starts a live voice conversation that only ends when the user hangs up: the assistant reads what is waiting
 * and asks for each OK, the user answers by voice (yes / no / send all / skip all / number 2 ...) or by tapping, and can
 * also ask about anything else (tasks in progress, other apps) like in a normal call.
 */
object ApprovalCall {
    private const val CHANNEL = "neo_approval_call"
    private const val NOTIF_ID = 4713
    private const val RING_MS = 45_000L

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    @Volatile var ringing = false
        private set
    @Volatile var urgent = false
        private set

    fun ring(ctx: Context, isUrgent: Boolean) {
        val app = ctx.applicationContext
        CallAwareness.refreshAsync(app)   // learn where you are and what you're doing while the phone rings
        urgent = isUrgent
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Approval call", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)   // the ringing is played below, on the alarm volume
            nm.createNotificationChannel(ch)
        }
        val open = Intent(app, ApprovalCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(app, 5, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Neo Plus Assistant")
            .setContentText(if (isUrgent) "Incoming call: urgent, waiting for your OK" else "Incoming call: " + waitingLine(app))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, n)
        startRinging(app)
        try { app.startActivity(open) } catch (e: Exception) { }
    }

    /** "5 tasks finished, 2 waiting for your OK" - what the call is about. */
    private fun waitingLine(app: Context): String {
        val tasks = TaskReports.list(app).size
        val ok = Approvals.list(app).size
        val parts = ArrayList<String>()
        if (tasks > 0) parts += "report on $tasks finished ${if (tasks == 1) "task" else "tasks"}"
        if (ok > 0) parts += "$ok ${if (ok == 1) "item" else "items"} waiting for your OK"
        return if (parts.isEmpty()) "your assistant" else parts.joinToString(", ")
    }

    private fun startRinging(app: Context) {
        stopRinging()
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
        main.postDelayed({ if (ringing) missed(app) }, RING_MS)
    }

    private fun stopRinging() {
        main.removeCallbacksAndMessages(null)
        ringing = false
        try { ringtone?.stop() } catch (e: Exception) { }
        try { vibrator?.cancel() } catch (e: Exception) { }
        ringtone = null
        vibrator = null
    }

    /** The user answered or declined: stop the ringing and remove the call notification. */
    fun stop(ctx: Context) {
        stopRinging()
        try { ctx.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) } catch (e: Exception) { }
    }

    /** Nobody answered: leave a missed-call notification (the Send / Skip notifications stay as they are). */
    private fun missed(app: Context) {
        stopRinging()
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            val open = Intent(app, ApprovalCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(app, 6, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Missed call: Neo Plus Assistant")
                .setContentText(waitingLine(app) + ". Tap to review.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) { }
    }
}

class ApprovalCallActivity : ComponentActivity() {
    private var session: ApprovalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NeoLock.callUp++
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val s = ApprovalSession(this)
        if (s.items.isEmpty() && TaskReports.list(applicationContext).isEmpty() && !ApprovalCall.ringing) { ApprovalCall.stop(applicationContext); finish(); return }
        session = s
        // Opened from a missed-call notification (nothing is ringing any more): the call connects straight away.
        if (!ApprovalCall.ringing) s.answer()
        setContent { ApprovalCallScreen(s) }
    }

    override fun onDestroy() {
        if (isFinishing) { ApprovalCall.stop(applicationContext); session?.shutdown() }
        NeoLock.callUp = maxOf(0, NeoLock.callUp - 1)
        if (isFinishing) NeoLock.showIfNeeded(applicationContext)
        super.onDestroy()
    }
}

/** One approval call: ringing -> the assistant speaks -> listens -> thinks -> speaks ... until the user hangs up. */
private class ApprovalSession(val act: ApprovalCallActivity) {
    private val ctx = act.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val hist = ArrayList<Pair<Boolean, String>>()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var alive = true

    var phase by mutableStateOf(0)       // 0 ringing, 1 connecting, 2 speaking, 3 listening, 4 thinking
    var secs by mutableStateOf(0)
    var heard by mutableStateOf("")
    var micDenied by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var held by mutableStateOf(false)
    var loud by mutableStateOf(false)
    var declined by mutableStateOf(false)
    var items by mutableStateOf(Approvals.list(ctx))
    val lines = mutableStateListOf<Pair<Boolean, String>>()   // true = me, false = assistant

    fun refresh() { items = Approvals.list(ctx) }

    private fun readable(it: Approvals.Item): String =
        (if (it.summary.startsWith("URGENT")) "Urgent. " else "") + it.summary.removePrefix("URGENT · ")

    /** The question the assistant asks about the item that is first in line. */
    private fun nextPrompt(): String {
        val first = Approvals.list(ctx).firstOrNull() ?: return "Nothing else is waiting for your OK. Anything else I can help with?"
        return "Next: " + readable(first) + ". Send it?"
    }

    fun answer() {
        ApprovalCall.stop(ctx)
        CallAwareness.refreshAsync(ctx)
        phase = 1
        refresh()
        val list = items
        val done = TaskReports.list(ctx)
        val report = TaskReports.spoken(done)
        val intro = (if (ApprovalCall.urgent) "Hi, this is urgent. " else "Hi, it's your assistant. ") + report +
            (if (list.isEmpty()) (if (done.isEmpty()) "Nothing is waiting for your OK right now. What can I do for you?" else "Nothing is waiting for your OK. Anything else I can do for you?")
            else "You have ${list.size} ${if (list.size == 1) "thing" else "things"} waiting for your OK. " +
                "Say send or skip for each one, or say send all or skip all. You can also ask me about anything else. First: " +
                readable(list.first()) + ". Send it?")
        hist += true to "[A phone call just started. Tell me what you finished and what is waiting for my OK.]"
        hist += false to intro
        if (done.isNotEmpty()) TaskReports.clear(ctx)   // reported by voice: the single texts are no longer needed
        say(intro)
    }

    fun decline() {
        ApprovalCall.stop(ctx)
        declined = true
    }

    fun callBack() { declined = false; answer() }

    fun toggleMute() {
        muted = !muted
        if (muted) { try { recognizer?.cancel() } catch (e: Exception) { }; BeepMute.restore(ctx) } else if (phase == 3 && !held) listen()
    }

    fun toggleHold() {
        held = !held
        if (held) { Speaker.stop(); try { recognizer?.cancel() } catch (e: Exception) { }; BeepMute.restore(ctx) }
        else if (phase >= 2) listen()
    }

    fun toggleLoud() { loud = !loud; NeoLoud.toggle(ctx, loud) }

    fun typed(text: String) { if (alive && text.isNotBlank()) { Speaker.stop(); try { recognizer?.cancel() } catch (e: Exception) { }; BeepMute.restore(ctx); handle(text) } }

    fun end() {
        shutdown()
        act.finish()
    }

    fun shutdown() {
        alive = false
        if (loud) { loud = false; NeoLoud.toggle(ctx, false) }
        main.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        BeepMute.restoreNow(ctx)
        Speaker.stop()
        io.shutdownNow()
    }

    /** Taps on the screen (Send / Skip on one item, Send all / Skip all). The call carries on afterwards. */
    fun tap(id: String?, send: Boolean) {
        io.execute {
            val r = try {
                if (id != null) Approvals.decide(ctx, id, send)
                else Approvals.handle(ctx, if (send) "approve_all" else "skip_all")
            } catch (e: Exception) { e.message ?: "Something went wrong" }
            main.post { refresh(); lines.add(false to r) }
        }
    }

    private fun say(text: String) {
        if (!alive) return
        if (held) { phase = 3; return }
        phase = 2
        val spoken = SpokenText.clean(text)
        var at = -1
        Speaker.speak(
            ctx, spoken,
            onStart = { part ->
                main.post {
                    if (alive) {
                        if (at < 0) { lines.add(false to part); at = lines.size - 1 }
                        else lines[at] = false to (lines[at].second + " " + part)
                    }
                }
            },
            onDone = {
                main.post {
                    if (alive) {
                        if (at < 0) lines.add(false to spoken)
                        listen()
                    }
                }
            },
            natural = !SleepMode.freeVoice(ctx)
        )
    }

    fun listen() {
        if (!alive) return
        phase = 3
        heard = ""
        if (muted || held) return
        refresh()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(ctx)
        ) { micDenied = true; return }
        micDenied = false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener); recognizer = it }
        BeepMute.mute(ctx)
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            )
        } catch (e: Exception) {
            BeepMute.restore(ctx)
            retry(1500)
        }
    }

    private fun retry(ms: Long) {
        main.postDelayed({ if (alive && phase == 3 && !micDenied && !muted && !held) listen() }, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (phase == 3) heard = t
        }
        override fun onResults(results: Bundle?) {
            BeepMute.restore(ctx)
            val t = (results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: heard).trim()
            if (t.isBlank()) retry(300) else handle(t)
        }
        override fun onError(error: Int) {
            BeepMute.restore(ctx)
            val partial = heard.trim()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { micDenied = true }
                SpeechRecognizer.ERROR_CLIENT -> Unit
                else -> if (partial.isNotBlank()) handle(partial)
                else retry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 500)
            }
        }
    }

    /* ---------------- what the user said ---------------- */

    private sealed class Cmd {
        object SendAll : Cmd()
        object SkipAll : Cmd()
        class One(val index: Int?, val send: Boolean) : Cmd()   // index null = the item in front
        object Hangup : Cmd()
    }

    private val numberWords = mapOf("one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3, "four" to 4, "fourth" to 4, "five" to 5, "fifth" to 5, "six" to 6, "seven" to 7, "eight" to 8)

    private fun parse(raw: String): Cmd? {
        val t = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        val words = t.split(" ")
        if (Regex("^(bye|goodbye|good bye|hang up|end call|end the call|that s all|thats all|that is all)\\b").containsMatchIn(t) && words.size <= 4) return Cmd.Hangup
        if (Regex("\\b(skip|reject|decline|cancel|delete|discard|don t send)\\b (all|everything|them all)").containsMatchIn(t) ||
            Regex("^no (to )?(all|everything)").containsMatchIn(t)) return Cmd.SkipAll
        if (Regex("\\b(send|approve|approved|accept|ok|okay|yes)\\b (all|everything|them all|to all)").containsMatchIn(t) ||
            Regex("^(approve|send) (them|everything)").containsMatchIn(t)) return Cmd.SendAll
        val send = Regex("^(yes|yeah|yep|yup|ok|okay|sure|send|send it|approve|approved|approve it|go ahead|do it|please do|send that)\\b").containsMatchIn(t)
        val skip = Regex("^(no|nope|nah|skip|skip it|don t|do not|cancel|reject|decline|not now|leave it|ignore)\\b").containsMatchIn(t)
        if ((send || skip) && words.size <= 6) {
            val m = Regex("\\b(number|item|message|one|no)\\s*(\\d|[a-z]+)\\b").find(t)
            val idx = m?.groupValues?.get(2)?.let { it.toIntOrNull() ?: numberWords[it] }
            if (words.size <= 4 || idx != null) return Cmd.One(idx, send && !skip)
        }
        return null
    }

    private fun handle(text: String) {
        if (!alive) return
        lines.add(true to text)
        heard = ""
        phase = 4
        hist += true to text
        val snapshot = ArrayList(hist)
        io.execute {
            val cmd = parse(text)
            var hangup = false
            val reply: String = try {
                when (cmd) {
                    is Cmd.Hangup -> { hangup = true; "Okay, talk soon." }
                    is Cmd.SendAll -> {
                        val n = Approvals.list(ctx).size
                        if (n == 0) "Nothing is waiting. Anything else?"
                        else { Approvals.handle(ctx, "approve_all"); "Okay, sending all $n now, one by one. Anything else I can help with?" }
                    }
                    is Cmd.SkipAll -> {
                        val n = Approvals.list(ctx).size
                        if (n == 0) "Nothing is waiting. Anything else?"
                        else { Approvals.handle(ctx, "skip_all"); "Okay, skipped all $n. Anything else I can help with?" }
                    }
                    is Cmd.One -> {
                        val list = Approvals.list(ctx)
                        val target = if (cmd.index == null) list.firstOrNull() else list.getOrNull(cmd.index - 1)
                        if (target == null) "I can't find that one. " + nextPrompt()
                        else {
                            val r = Approvals.decide(ctx, target.id, cmd.send)
                            val done = if (!cmd.send) "Skipped." else if (r.startsWith("Sent")) "Sent." else r
                            done + " " + nextPrompt()
                        }
                    }
                    null -> {
                        val apps = OrbBridge.apps()
                        val cfg = AssistantConfig.load(ctx)
                        val plan = LocalCommands.plan(text, apps, cfg.configured)
                        val answer = if (plan != null) {
                            val results = plan.actions.map { PhoneActions.run(ctx, it.first, it.second, apps) }
                            results.firstOrNull { !it.ok }?.note ?: plan.reply.ifBlank { results.joinToString("\n") { it.note } }
                        } else if (!cfg.configured) {
                            Keyless.answer(ctx, text, apps)
                        } else {
                            val waiting = Approvals.list(ctx)
                            Agent.respond(
                                ctx, cfg.provider, cfg.key, cfg.activeModel, buildSystemPrompt(ctx, apps), snapshot, apps, null,
                                "[This is a live phone call. Talk like a friendly person on the phone: contractions, one to three short sentences, no markdown, no lists, and say times and numbers the way people say them out loud. " +
                                    "The user can ask about tasks in progress or about other apps; answer or do it. " +
                                    (if (waiting.isEmpty()) "Nothing is waiting for their OK." else "Waiting for their OK: " + waiting.mapIndexed { i, w -> "${i + 1}. " + w.summary }.joinToString(" | ") + ". Do not send these yourself; the user answers them by voice.") + "]" + CallAwareness.hint(ctx)
                            ).text
                        }
                        // After a side question, steer back to what is still waiting.
                        if (Approvals.list(ctx).isNotEmpty()) answer.trim() + " By the way, still waiting: " + readable(Approvals.list(ctx).first()) + ". Send it?" else answer
                    }
                }
            } catch (e: Exception) {
                e.message ?: "Something went wrong."
            }
            main.post {
                if (!alive) return@post
                hist += false to reply
                while (hist.size > 24) hist.removeAt(0)
                refresh()
                if (hangup) { lines.add(false to reply); Speaker.speak(ctx, reply, natural = !SleepMode.freeVoice(ctx)); main.postDelayed({ end() }, 1800) }
                else say(reply)
            }
        }
    }
}

@Composable
private fun ApprovalCallScreen(s: ApprovalSession) {
    LaunchedEffect(s.phase, s.declined) {
        if (s.phase == 0 && !s.declined) {
            while (ApprovalCall.ringing) delay(500)
            if (s.phase == 0 && !s.declined) s.act.finish()   // nobody answered: the ringing timed out (a missed-call notification remains)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (s.phase >= 1 && !s.declined) s.secs++ } }
    LaunchedEffect(Unit) { while (true) { delay(1500); s.refresh() } }

    NeoCallScreen(
        NeoCallModel(
            phase = s.phase, secs = s.secs, urgent = ApprovalCall.urgent, declined = s.declined, muted = s.muted, held = s.held, loud = s.loud,
            lines = s.lines.toList(), heard = s.heard, micDenied = s.micDenied,
            waitingText = if (s.phase >= 1 && s.items.isNotEmpty()) "${s.items.size} waiting for your OK" else "",
            onAnswer = { s.answer() }, onDecline = { s.decline() }, onEnd = { s.end() },
            onMute = { s.toggleMute() }, onHold = { s.toggleHold() }, onSpeaker = { s.toggleLoud() },
            onType = { s.typed(it) }, onCallBack = { s.callBack() }, onClose = { s.act.finish() },
            onMicGranted = { s.listen() }
        )
    ) {
        if (s.phase >= 1) {
            for (item in s.items) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp)) {
                    Text(item.summary, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoCallPill("Send", Color(0xFF34C759)) { s.tap(item.id, true) }
                        NeoCallPill("Skip", Color(0xFFFF3B30)) { s.tap(item.id, false) }
                    }
                }
            }
        }
    }
}
