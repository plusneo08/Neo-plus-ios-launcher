package com.neo.plus.iosluancher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
 * The "incoming call" from the assistant at the wake-up time. Answer = the assistant reads the night summary, then the
 * call turns into a normal voice conversation. It only ends when the user taps End call.
 */
class WakeCallActivity : ComponentActivity() {
    private var session: CallSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (!SleepMode.isOn(applicationContext) && !WakeRinger.ringing) { finish(); return }
        val s = CallSession(this)
        session = s
        setContent { CallScreen(s) }
    }

    override fun onDestroy() {
        if (isFinishing) session?.shutdown()
        super.onDestroy()
    }
}

/** One phone call: ringing -> connecting -> the assistant speaks -> listens -> thinks -> speaks ... until End call. */
private class CallSession(val act: WakeCallActivity) {
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
    val lines = mutableStateListOf<Pair<Boolean, String>>()   // true = me, false = assistant

    fun answer() {
        WakeRinger.stop()
        SleepMode.answered(ctx)
        phase = 1
        io.execute {
            val r = SleepMode.report(ctx)
            main.post {
                if (!alive) return@post
                hist += true to "[A phone call just started. I woke up. Tell me what happened overnight.]"
                hist += false to r
                say(r)
            }
        }
    }

    fun decline() {
        WakeRinger.stop()
        SleepMode.answered(ctx)
        act.finish()
    }

    fun end() {
        shutdown()
        act.finish()
    }

    fun shutdown() {
        alive = false
        main.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        BeepMute.restoreNow(ctx)
        Speaker.stop()
        io.shutdownNow()
    }

    private fun say(text: String) {
        if (!alive) return
        phase = 2
        // The Gemini voice, or the phone's free voice when the user chose that. The text appears piece by piece at the
        // very moment each piece starts to be spoken, so the words on screen and the voice are always in step.
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
        main.postDelayed({ if (alive && phase == 3 && !micDenied) listen() }, ms)
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

    private fun handle(text: String) {
        if (!alive) return
        lines.add(true to text)
        heard = ""
        phase = 4
        hist += true to text
        val snapshot = ArrayList(hist)
        io.execute {
            val reply: String = try {
                val apps = OrbBridge.apps()
                val cfg = AssistantConfig.load(ctx)
                val plan = LocalCommands.plan(text, apps, cfg.configured)
                if (plan != null) {
                    val results = plan.actions.map { PhoneActions.run(ctx, it.first, it.second, apps) }
                    results.firstOrNull { !it.ok }?.note ?: plan.reply.ifBlank { results.joinToString("\n") { it.note } }
                } else if (!cfg.configured) {
                    Keyless.answer(ctx, text, apps)
                } else {
                    Agent.respond(
                        ctx, cfg.provider, cfg.key, cfg.activeModel, buildSystemPrompt(ctx, apps), snapshot, apps, null,
                        "[This is a live phone call. Talk like a friendly person on the phone: contractions, one to three short sentences, no markdown, no lists, and say times and numbers the way people say them out loud.]"
                    ).text
                }
            } catch (e: Exception) {
                e.message ?: "Something went wrong."
            }
            main.post {
                if (!alive) return@post
                hist += false to reply
                while (hist.size > 24) hist.removeAt(0)
                say(reply)
            }
        }
    }
}

@Composable
private fun CallScreen(s: CallSession) {
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) s.listen()
    }
    val scroll = rememberScrollState()

    LaunchedEffect(s.phase) {
        if (s.phase == 0) {
            while (WakeRinger.ringing) delay(500)
            if (s.phase == 0) s.act.finish()   // nobody answered: the ringing timed out
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (s.phase >= 1) s.secs++ } }
    LaunchedEffect(s.lines.size, s.lines.lastOrNull()?.second?.length, s.heard) { scroll.animateScrollTo(scroll.maxValue) }

    val clock = "%02d:%02d".format(s.secs / 60, s.secs % 60)
    val status = when (s.phase) {
        0 -> "incoming call…"
        1 -> "connecting…"
        2 -> "$clock · speaking"
        3 -> "$clock · listening…"
        else -> "$clock · thinking…"
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF000000))))) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.drawable.neo_icon), null, Modifier.size(96.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(14.dp))
            Text("Neo Plus Assistant", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(status, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((me, line) in s.lines) {
                    Row(Modifier.fillMaxWidth(), if (me) Arrangement.End else Arrangement.Start) {
                        Text(
                            line, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .background(if (me) Color(0xFF2563EB) else Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
                if (s.phase == 3 && s.heard.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        Text(s.heard, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, fontStyle = FontStyle.Italic)
                    }
                }
                if (s.micDenied) {
                    Text(
                        "Tap here and allow the microphone so I can hear you.",
                        color = Color(0xFFFFD60A), fontSize = 15.sp,
                        modifier = Modifier.clickable { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (s.phase == 0) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    CallButton(Color(0xFFFF3B30), false, "Decline") { s.decline() }
                    CallButton(Color(0xFF34C759), true, "Answer") { s.answer() }
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                    CallButton(Color(0xFFFF3B30), false, "End call") { s.end() }
                }
            }
        }
    }
}

@Composable
private fun CallButton(color: Color, call: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (call) Icons.Filled.Call else Icons.Filled.CallEnd, label, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}
