package com.neo.plus.iosluancher

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Neo Plus lock screen. Shown over Neo Plus itself, over a locked app, or (mode "confirm") to ask for the phone's own
 * unlock before changing lock settings, or (mode "setphrase") to record the spoken unlock phrase.
 * It opens with the phone's own unlock (fingerprint, face, PIN, pattern, password) or, if switched on, the spoken phrase.
 */
class LockActivity : ComponentActivity() {
    companion object {
        fun start(ctx: Context, pkg: String = "", mode: String = "unlock") {
            if (mode == "unlock" && AppLock.showing && AppLock.showingPkg == pkg) return
            // The Neo PIN / password / pattern is asked for when it replaces Android's lock, or the phone has no lock of its own
            if (NeoLock.preferred(ctx)) {
                when {
                    mode == "unlock" -> { NeoLockScreenActivity.app(ctx, pkg); return }
                    mode == "confirm" || (mode == "setphrase" && !AppLock.strongOk()) -> { NeoLockScreenActivity.confirm(ctx); return }
                }
            }
            val i = Intent(ctx.applicationContext, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("pkg", pkg).putExtra("mode", mode)
            try { ctx.applicationContext.startActivity(i) } catch (e: Exception) { }
        }
        fun confirm(ctx: Context) = start(ctx, "", "confirm")
        fun setPhrase(ctx: Context) = start(ctx, "", "setphrase")
    }

    private var pkg = ""
    private var mode = "unlock"
    private var status by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var failTick by mutableStateOf(0)
    private var step by mutableStateOf(0)       // setphrase: 0 phone unlock, 1 first phrase, 2 repeat it
    private var noLock by mutableStateOf(false)  // the phone has no screen lock at all
    private var firstPhrase = ""
    private var authInFlight = false
    private var recognizer: SpeechRecognizer? = null

    private val cred = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        authInFlight = false
        if (r.resultCode == RESULT_OK) onStrong() else fail("Not unlocked")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots of the lock screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (mode != "unlock") finish() }   // an unlock cannot be backed out of
        })
        readIntent(intent)
        setContent { Screen() }
        autoStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        autoStart()
    }

    private fun readIntent(i: Intent?) {
        pkg = i?.getStringExtra("pkg").orEmpty()
        mode = i?.getStringExtra("mode") ?: "unlock"
        AppLock.showing = true
        AppLock.showingPkg = if (mode == "unlock") pkg else "#$mode"
        status = ""; listening = false; step = 0; noLock = false
    }

    private fun voiceAvailable() = mode == "unlock" && AppLock.voiceOn(this) && AppLock.voiceAllowedFor(pkg)

    /** The phone's own prompt opens at once, unless the voice phrase is an option (then the user chooses). */
    private fun autoStart() { if (!voiceAvailable()) systemAuth() }

    override fun onStop() {
        // Leaving (Home, another app, screen off) closes the lock screen; the lock is checked again when coming back
        if (!authInFlight && !isChangingConfigurations) finish()
        super.onStop()
    }

    override fun onDestroy() {
        AppLock.showing = false; AppLock.showingPkg = ""
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        super.onDestroy()
    }

    /* ---------------- the phone's own unlock ---------------- */

    private fun title(): String = when (mode) {
        "confirm" -> "Confirm it is you"
        "setphrase" -> "Confirm it is you"
        else -> if (pkg.isBlank()) "Unlock Neo Plus" else "Unlock ${appLabel()}"
    }

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun systemAuth() {
        if (NeoLock.preferred(this) && AppLock.strongOk()) { onStrong(); return }   // the Neo lock was just entered
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            noLock = true
            status = "This phone has no screen lock. Set a PIN, pattern or password in Android settings to protect it."
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val prompt = BiometricPrompt.Builder(this).setTitle(title()).setSubtitle("Use your phone’s own unlock")
                .setAllowedAuthenticators(allowed).build()
            prompt.authenticate(CancellationSignal(), ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onStrong() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        fail(errString?.toString() ?: "Not unlocked")
                    }
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent(title(), "Use your phone’s own unlock")
            if (i == null) { noLock = true; status = "This phone has no screen lock."; return }
            authInFlight = true
            cred.launch(i)
        }
    }

    private fun onStrong() {
        AppLock.strongUntil = System.currentTimeMillis() + AppLock.STRONG_MS
        when (mode) {
            "confirm" -> finish()
            "setphrase" -> { step = 1; status = "Say your new unlock phrase: 3 or more words you will remember" }
            else -> { AppLock.unlocked(this, pkg, true); finish() }
        }
    }

    private fun fail(msg: String) { status = msg; failTick++ }

    /* ---------------- the spoken phrase ---------------- */

    private fun listen() {
        if (listening) return
        if (mode == "unlock") {
            if (!voiceAvailable()) return
            if (AppLock.voiceBlocked()) { fail("Voice unlock is paused for 5 minutes: use your phone’s unlock"); return }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)) { fail("Allow the microphone for Neo Plus"); return }
        listening = true; status = "Listening…"
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { listening = false; BeepMute.restore(applicationContext); fail("I didn’t catch that: tap and try again") }
            override fun onResults(results: Bundle?) {
                listening = false
                BeepMute.restore(applicationContext)
                handleHeard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
        })
        BeepMute.mute(this)
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            )
        } catch (e: Exception) { listening = false; BeepMute.restore(applicationContext); fail("The microphone is busy") }
    }

    private fun handleHeard(heard: List<String>) {
        when {
            mode == "unlock" -> {
                if (AppLock.checkPhrase(this, heard)) { AppLock.unlocked(this, pkg, false); finish() }
                else fail(if (AppLock.voiceBlocked()) "Too many tries: voice unlock is paused for 5 minutes" else "That was not the phrase")
            }
            mode == "setphrase" && step == 1 -> {
                val t = heard.firstOrNull { AppLock.phraseOk(it) }
                if (t == null) fail("Use at least 3 words") else { firstPhrase = t; step = 2; status = "Got it. Say the same phrase once more to confirm" }
            }
            mode == "setphrase" && step == 2 -> {
                if (AppLock.sameWords(firstPhrase, heard) && AppLock.setPhrase(this, firstPhrase)) {
                    AppLock.setVoiceOn(this, true)
                    Toast.makeText(this, "Voice unlock phrase saved", Toast.LENGTH_LONG).show()
                    finish()
                } else { step = 1; fail("That did not match. Say your phrase again from the start") }
            }
        }
    }

    /* ---------------- the screen ---------------- */

    @Composable
    private fun Screen() {
        val icon: ImageBitmap? = remember(pkg) {
            if (pkg.isBlank()) null else try { packageManager.getApplicationIcon(pkg).toBitmap(160, 160).asImageBitmap() } catch (e: Exception) { null }
        }
        val heading = when (mode) {
            "confirm" -> "Confirm it is you"
            "setphrase" -> if (step == 0) "Confirm it is you" else "Set your unlock phrase"
            else -> if (pkg.isBlank()) "Neo Plus is locked" else "${appLabel()} is locked"
        }
        val sub = when (mode) {
            "confirm" -> "Lock settings need your phone’s own unlock"
            "setphrase" -> if (step == 0) "Then you will say your phrase twice" else "Tap the circle and speak"
            else -> "Unlock with your phone’s lock" + (if (voiceAvailable()) " or your voice" else "")
        }
        LockScreen(
            heading = heading, sub = sub, icon = icon, status = status, listening = listening, failTick = failTick,
            showVoice = (voiceAvailable() && !AppLock.voiceBlocked()) || (mode == "setphrase" && step >= 1),
            showSystem = !(mode == "setphrase" && step >= 1), showCancel = mode != "unlock", noLock = noLock,
            orbIsMic = (mode == "setphrase" && step >= 1) || listening,
            onSystem = { systemAuth() }, onVoice = { listen() }, onCancel = { finish() },
            onContinueNoLock = { AppLock.unlocked(this, pkg, true); if (mode == "unlock") finish() else onStrong() }
        )
    }
}

@Composable
private fun LockScreen(
    heading: String, sub: String, icon: ImageBitmap?, status: String, listening: Boolean, failTick: Int,
    showVoice: Boolean, showSystem: Boolean, showCancel: Boolean, noLock: Boolean, orbIsMic: Boolean,
    onSystem: () -> Unit, onVoice: () -> Unit, onCancel: () -> Unit, onContinueNoLock: () -> Unit
) {
    val ctx = LocalContext.current
    val inf = rememberInfiniteTransition(label = "lock")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(15000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val pulse by inf.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(if (listening) 550 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val shake = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(failTick) {
        if (failTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            repeat(3) { shake.animateTo(18f, tween(45)); shake.animateTo(-18f, tween(45)) }
            shake.animateTo(0f, tween(45))
        }
    }
    val clock = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    val cyan = Color(0xFF21D4FD); val violet = Color(0xFF8E5BFF); val pink = Color(0xFFFF5FA2)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF04050D), Color(0xFF0A0F2C), Color(0xFF1B0B3B))))) {
        // slow drifting aurora
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            fun blob(cx: Float, cy: Float, r: Float, c: Color) =
                drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
            blob(w * (0.15f + 0.6f * drift), h * 0.22f, w * 0.75f, cyan.copy(alpha = 0.22f))
            blob(w * (0.9f - 0.6f * drift), h * 0.55f, w * 0.8f, violet.copy(alpha = 0.24f))
            blob(w * (0.3f + 0.4f * drift), h * 0.92f, w * 0.7f, pink.copy(alpha = 0.18f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(76.dp))
            Text(clock, color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.ExtraLight)
            Text(date, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(220.dp).offset(x = shake.value.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clickable(enabled = showVoice) { onVoice() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val r = size.minDimension / 2 - 10.dp.toPx()
                    rotate(spin, pivot = c) {
                        drawCircle(Brush.sweepGradient(listOf(cyan, violet, pink, cyan), c), radius = r, center = c, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawCircle(Color.White.copy(alpha = 0.10f), radius = r - 14.dp.toPx(), center = c)
                    drawCircle(Color.White.copy(alpha = 0.22f), radius = r - 14.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                    val white = Color.White
                    if (orbIsMic) {
                        drawRoundRect(white, Offset(c.x - 9.dp.toPx(), c.y - 34.dp.toPx()), Size(18.dp.toPx(), 36.dp.toPx()), CornerRadius(9.dp.toPx()))
                        drawArc(white, 0f, 180f, false, Offset(c.x - 20.dp.toPx(), c.y - 20.dp.toPx()), Size(40.dp.toPx(), 40.dp.toPx()), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                        drawLine(white, Offset(c.x, c.y + 20.dp.toPx()), Offset(c.x, c.y + 32.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawArc(white, 180f, 180f, false, Offset(c.x - 19.dp.toPx(), c.y - 40.dp.toPx()), Size(38.dp.toPx(), 38.dp.toPx()), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                        drawRoundRect(white, Offset(c.x - 28.dp.toPx(), c.y - 6.dp.toPx()), Size(56.dp.toPx(), 44.dp.toPx()), CornerRadius(10.dp.toPx()))
                        drawCircle(Color(0xFF0A0F2C), radius = 5.dp.toPx(), center = Offset(c.x, c.y + 12.dp.toPx()))
                        drawLine(Color(0xFF0A0F2C), Offset(c.x, c.y + 14.dp.toPx()), Offset(c.x, c.y + 24.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Image(icon, null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                Text(heading, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                status.ifBlank { sub },
                color = if (status.isBlank()) Color.White.copy(alpha = 0.65f) else if (listening) cyan else Color(0xFFFFB3C7),
                fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp
            )
            Spacer(Modifier.weight(1f))
            val pill = RoundedCornerShape(50)
            if (noLock) GlassButton("Continue without a phone lock", true, onContinueNoLock)
            else if (showSystem) GlassButton("Unlock with phone lock", true, onSystem)
            if (showVoice) { Spacer(Modifier.height(12.dp)); GlassButton(if (listening) "Listening…" else "Say your phrase", false, onVoice) }
            if (showCancel) { Spacer(Modifier.height(12.dp)); GlassButton("Cancel", false, onCancel) }
            Spacer(Modifier.height(44.dp))
        }
    }
}

@Composable
private fun GlassButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (primary) Brush.horizontalGradient(listOf(Color(0xFF21D4FD).copy(alpha = 0.32f), Color(0xFF8E5BFF).copy(alpha = 0.38f)))
    else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.10f)))
    Box(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, Color.White.copy(alpha = if (primary) 0.38f else 0.22f), shape)
            .clickable { onClick() }.padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
