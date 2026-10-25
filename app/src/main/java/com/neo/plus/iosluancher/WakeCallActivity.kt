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
        NeoLock.callUp++
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
        NeoLock.callUp = maxOf(0, NeoLock.callUp - 1)
        if (isFinishing) NeoLock.showIfNeeded(applicationContext)
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
    var muted by mutableStateOf(false)
    var held by mutableStateOf(false)
    var loud by mutableStateOf(false)
    var declined by mutableStateOf(false)
    val lines = mutableStateListOf<Pair<Boolean, String>>()   // true = me, false = assistant

    fun answer() {
        WakeRinger.stop()
        SleepMode.answered(ctx)
        phase = 1
        io.execute {
            val r = SleepPrep.ready(ctx)   // already written (and its voice made) 5 minutes earlier, if nothing new came in
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

    private fun say(text: String) {
        if (!alive) return
        if (held) { phase = 3; return }
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
        if (muted || held) return
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
    LaunchedEffect(s.phase, s.declined) {
        if (s.phase == 0 && !s.declined) {
            while (WakeRinger.ringing) delay(500)
            if (s.phase == 0 && !s.declined) s.act.finish()   // nobody answered: the ringing timed out
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (s.phase >= 1 && !s.declined) s.secs++ } }

    NeoCallScreen(
        NeoCallModel(
            phase = s.phase, secs = s.secs, urgent = false, declined = s.declined, muted = s.muted, held = s.held, loud = s.loud,
            lines = s.lines.toList(), heard = s.heard, micDenied = s.micDenied,
            onAnswer = { s.answer() }, onDecline = { s.decline() }, onEnd = { s.end() },
            onMute = { s.toggleMute() }, onHold = { s.toggleHold() }, onSpeaker = { s.toggleLoud() },
            onType = { s.typed(it) }, onCallBack = { s.callBack() }, onClose = { s.act.finish() },
            onMicGranted = { s.listen() }
        )
    )
}
