package com.neo.plus.iosluancher

import android.app.Activity
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Neo Plus lock screen: big clock, date, wallpaper and "Swipe up to unlock", then the way in.
 *
 * Two ways in, chosen in Assistant settings > Lock:
 *  1. Android's own lock stays (default). It is drawn ABOVE Android's lock screen (showWhenLocked); swiping up asks Android for its
 *     real unlock with [KeyguardManager.requestDismissKeyguard], so fingerprint, face, PIN, pattern and password all work and
 *     Neo Plus never sees them.
 *  2. "Replace Android's lock" (device owner): Android's lock screen is switched off and THIS screen is the lock. Swipe up shows
 *     the Neo PIN pad, password box or pattern grid. While it is up, the home button and notification shade are off.
 *
 * Before anyone has unlocked it shows ONLY the time, the date and HOW MANY notifications wait, never their text.
 *
 * The same screen is also the Neo PIN/password/pattern prompt for locked apps ("app"), for confirming changes to the lock
 * ("confirm") and for choosing a new PIN, password or pattern ("setup").
 */
class NeoLockScreenActivity : ComponentActivity() {

    companion object {
        /** True while Android's own lock is up and the clock screen in front of it is wanted (not when Neo replaces the lock). */
        fun shouldShow(ctx: Context): Boolean {
            if (NeoLock.replaceOn(ctx)) return false
            if (!AppLock.lockScreenOn(ctx)) return false
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
            return km.isKeyguardLocked
        }

        private fun launch(ctx: Context, mode: String, pkg: String = "", type: String = "") {
            val i = Intent(ctx.applicationContext, NeoLockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("mode", mode).putExtra("pkg", pkg).putExtra("type", type)
            try { ctx.applicationContext.startActivity(i) } catch (e: Exception) { }
        }

        fun start(ctx: Context) = launch(ctx, "device")
        fun confirm(ctx: Context) = launch(ctx, "confirm")
        fun app(ctx: Context, pkg: String) = launch(ctx, "app", pkg)
        fun setup(ctx: Context, type: String) = launch(ctx, "setup", "", type)

        @Volatile private var current: NeoLockScreenActivity? = null
        /** Closes the lock screen if it is up (the assistant opened the lock by itself). */
        fun closeIfUp() { current?.closeFromOutside() }
    }

    private var mode by mutableStateOf("device")
    private var pkg = ""
    private var ctype by mutableStateOf("")           // setup: the kind being chosen
    private var info by mutableStateOf("")
    private var error by mutableStateOf(false)
    private var resetTick by mutableStateOf(0)
    private var blockedUntil by mutableStateOf(0L)
    private var setupFirst: String? = null
    private var busy = false
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots of the lock screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!(mode == "device" && NeoLock.needsLock(this@NeoLockScreenActivity))) finish() }
        })
        readIntent(intent)
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        val prevApp = mode == "app"
        mode = i?.getStringExtra("mode") ?: "device"
        pkg = i?.getStringExtra("pkg").orEmpty()
        ctype = i?.getStringExtra("type").orEmpty()
        setupFirst = null; error = false; resetTick++
        blockedUntil = NeoLock.blockedUntil(this)
        info = when (mode) {
            "setup" -> NeoLock.hint(ctype)
            "confirm" -> "Enter your ${NeoLock.noun(NeoLock.type(this))} to continue"
            else -> ""
        }
        if (prevApp && mode != "app") { AppLock.showing = false; AppLock.showingPkg = "" }
        if (mode == "app") { AppLock.showing = true; AppLock.showingPkg = pkg }
        if (mode == "device" && NeoLock.replaceOn(this)) { NeoLock.showing = true; lockdown(true) }
        if (mode == "confirm" || mode == "app") window.decorView.post { startBio() }
    }

    fun closeFromOutside() {
        runOnUiThread { if (mode == "device") lockdown(false); finish() }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            if (mode == "device") NeoLock.showing = false   // not on screen any more: the lock must be able to come back
            val keep = mode == "device" && NeoLock.needsLock(this)   // the lock that replaces Android's must stay up
            if (!dismissing && !keep) finish()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (mode != "device") return
        if (NeoLock.replaceOn(this)) {
            if (!NeoLock.needsLock(this)) finish() else NeoLock.showing = true
        } else {
            // Already unlocked (for example by Smart Lock or face unlock while this was up): nothing to show
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) finish()
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (mode == "app") { AppLock.showing = false; AppLock.showingPkg = "" }
        NeoLock.showing = false
        super.onDestroy()
    }

    /* ---------------- Android's own unlock ---------------- */

    /** Asks Android to open ITS OWN unlock: it shows fingerprint, face, PIN, pattern or password as the phone is set up. */
    private fun requestUnlock() {
        if (dismissing) return
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) { finish(); return }
        dismissing = true
        try {
            km.requestDismissKeyguard(this as Activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissing = false
                    // The phone's own unlock just passed, so the Neo Plus lock does not ask a second time right away
                    if (km.isDeviceSecure) AppLock.unlocked(applicationContext, "", true)
                    finish()
                }
                override fun onDismissCancelled() { dismissing = false }
                override fun onDismissError() { dismissing = false }
            })
        } catch (e: Exception) { dismissing = false }
    }

    /* ---------------- the Neo lock ---------------- */

    /** While the lock that replaces Android's is up: no home button, no notification shade, and the task is pinned. */
    private fun lockdown(on: Boolean) {
        NeoLock.applyFeatures(this, on)
        try { if (on) { if (Kiosk.permitted(this)) startLockTask() } else stopLockTask() } catch (e: Exception) { }
    }

    private fun activeType() = if (mode == "setup") ctype else NeoLock.type(this)

    private fun bioAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 30 || mode == "setup") return false
        return try {
            val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            getSystemService(BiometricManager::class.java).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) { false }
    }

    /** Fingerprint or face, when the phone has one enrolled (that needs a lock set in Android settings). */
    private fun startBio() {
        if (Build.VERSION.SDK_INT < 30 || !bioAvailable()) return
        try {
            val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            val prompt = BiometricPrompt.Builder(this).setTitle("Unlock Neo Plus")
                .setNegativeButton("Use ${NeoLock.noun(NeoLock.type(this))}", mainExecutor) { _, _ -> }
                .setAllowedAuthenticators(auth).build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { granted() }
            })
        } catch (e: Exception) { }
    }

    private fun granted() {
        when (mode) {
            "device" -> { NeoLock.unlocked = true; AppLock.unlocked(applicationContext, "", true); lockdown(false) }
            "confirm" -> AppLock.strongUntil = System.currentTimeMillis() + AppLock.STRONG_MS
            "app" -> AppLock.unlocked(applicationContext, pkg, true)
        }
        finish()
    }

    private fun wrong(msg: String) { info = msg; error = true; resetTick++ }

    private fun submit(secret: String) {
        if (busy) return
        val t = activeType()
        if (t == NeoLock.PATTERN && secret.length < 4) { wrong("Connect at least 4 dots"); return }
        if (mode == "setup") {
            val first = setupFirst
            when {
                first == null -> if (!NeoLock.valid(t, secret)) wrong(NeoLock.hint(t))
                    else { setupFirst = secret; error = false; info = "Enter it again to confirm"; resetTick++ }
                first == secret -> {
                    if (NeoLock.set(this, t, secret)) {
                        AppLock.strongUntil = System.currentTimeMillis() + AppLock.STRONG_MS   // just proved by typing it twice
                        Toast.makeText(this, "${NeoLock.label(t)} saved. Remember it: there is no way to reset it from the phone", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
                else -> { setupFirst = null; wrong("They did not match. Start again") }
            }
            return
        }
        busy = true
        val app = applicationContext
        Thread {
            val c = NeoLock.check(app, secret)   // the slow hash runs off the main thread
            runOnUiThread {
                busy = false
                when {
                    c.ok -> granted()
                    c.waitMs > 0 -> { blockedUntil = System.currentTimeMillis() + c.waitMs; wrong("Too many tries") }
                    c.left > 0 -> wrong("Wrong ${NeoLock.noun(t)}. ${c.left} ${if (c.left == 1) "try" else "tries"} left before a wait")
                    else -> wrong("Wrong ${NeoLock.noun(t)}")
                }
            }
        }.start()
    }

    /* ---------------- screens ---------------- */

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        when {
            mode == "device" && !NeoLock.replaceOn(ctx) -> ClockScreen(onUnlock = { requestUnlock() })
            mode == "device" -> {
                var pad by remember { mutableStateOf(false) }
                if (!pad) ClockScreen(onUnlock = { pad = true })
                else Credential(cancelable = false, bio = if (bioAvailable()) ({ startBio() }) else null)
            }
            else -> Credential(cancelable = true, bio = if (bioAvailable()) ({ startBio() }) else null)
        }
    }

    @Composable
    private fun Credential(cancelable: Boolean, bio: (() -> Unit)?) {
        val t = activeType()
        val title = when (mode) {
            "setup" -> if (setupFirst == null) "New ${NeoLock.noun(t)}" else "Confirm ${NeoLock.noun(t)}"
            "confirm" -> "Confirm it is you"
            "app" -> if (pkg.isBlank()) "Unlock Neo Plus" else "Unlock ${appLabel()}"
            else -> "Enter ${NeoLock.noun(t)}"
        }
        var remain by remember { mutableStateOf(0L) }
        LaunchedEffect(blockedUntil) {
            while (true) {
                remain = (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remain <= 0L) break
                delay(500)
            }
        }
        val enabled = remain <= 0L
        val shown = if (!enabled) "Too many tries. Try again in ${(remain + 999) / 1000}s" else info
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF000000)))).statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    shown, color = if (error || !enabled) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.height(40.dp)
                )
                Spacer(Modifier.height(16.dp))
                when (t) {
                    NeoLock.PIN4 -> PinPad(4, resetTick, error, enabled, bio) { submit(it) }
                    NeoLock.PIN6 -> PinPad(6, resetTick, error, enabled, bio) { submit(it) }
                    NeoLock.PASSWORD -> PasswordEntry(resetTick, enabled, if (mode == "setup") "Continue" else "Unlock") { submit(it) }
                    else -> PatternPad(resetTick, error, enabled) { submit(it) }
                }
                Spacer(Modifier.weight(1f))
                if (cancelable) {
                    Text(
                        "Cancel", color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp,
                        modifier = Modifier.clickable { finish() }.padding(vertical = 20.dp, horizontal = 24.dp)
                    )
                }
            }
        }
    }

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}

/* ------------------------------------------------------------------ */
/*  The three ways to enter a Neo lock                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun PinPad(len: Int, resetTick: Int, error: Boolean, enabled: Boolean, bio: (() -> Unit)?, onFull: (String) -> Unit) {
    var entered by remember(resetTick) { mutableStateOf("") }
    val dot = if (error) Color(0xFFFF453A) else Color.White
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(len) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape).then(
                    if (i < entered.length) Modifier.background(dot) else Modifier.border(1.5.dp, dot.copy(alpha = 0.8f), CircleShape)
                )
            )
        }
    }
    Spacer(Modifier.height(36.dp))

    @Composable
    fun Key(label: String, onClick: () -> Unit) {
        PadKey(label[0], 78.dp) { onClick() }
    }

    fun digit(d: String) {
        if (!enabled || entered.length >= len) return
        val n = entered + d
        entered = n
        if (n.length == len) onFull(n)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { row.forEach { d -> Key(d) { digit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bio != null) {
                Box(Modifier.size(78.dp).clip(CircleShape).clickable(enabled = enabled) { bio() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Fingerprint, "Fingerprint or face", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            } else Spacer(Modifier.size(78.dp))
            Key("0") { digit("0") }
            Box(
                Modifier.size(78.dp).clip(CircleShape).clickable(enabled = enabled) { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
                contentAlignment = Alignment.Center
            ) { Text("⌫", color = Color.White, fontSize = 26.sp) }
        }
    }
}

@Composable
private fun PasswordEntry(resetTick: Int, enabled: Boolean, button: String, onSubmit: (String) -> Unit) {
    var text by remember(resetTick) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(resetTick) { try { focus.requestFocus() } catch (e: Exception) { } }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = text, onValueChange = { if (enabled) text = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled && text.isNotEmpty()) onSubmit(text) }),
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (enabled) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.2f))
            .clickable(enabled = enabled && text.isNotEmpty()) { onSubmit(text) }.padding(horizontal = 40.dp, vertical = 12.dp)
    ) { Text(button, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PatternPad(resetTick: Int, error: Boolean, enabled: Boolean, onDone: (String) -> Unit) {
    val path = remember(resetTick) { mutableStateListOf<Int>() }
    var cur by remember(resetTick) { mutableStateOf<Offset?>(null) }
    val color = if (error) Color(0xFFFF453A) else Color.White
    Canvas(
        Modifier.size(280.dp).pointerInput(resetTick, enabled) {
            if (!enabled) return@pointerInput
            fun hit(o: Offset): Int {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                for (r in 0..2) for (c in 0..2) {
                    val center = Offset(w * (2 * c + 1) / 6f, h * (2 * r + 1) / 6f)
                    if ((o - center).getDistance() < w / 9f) return r * 3 + c
                }
                return -1
            }
            detectDragGestures(
                onDragStart = { o -> path.clear(); cur = o; val i = hit(o); if (i >= 0) path.add(i) },
                onDrag = { change, _ -> cur = change.position; val i = hit(change.position); if (i >= 0 && i !in path) path.add(i) },
                onDragEnd = { cur = null; if (path.isNotEmpty()) onDone(path.joinToString("")) },
                onDragCancel = { cur = null; path.clear() }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        fun center(i: Int) = Offset(w * (2 * (i % 3) + 1) / 6f, h * (2 * (i / 3) + 1) / 6f)
        for (k in 1 until path.size) {
            drawLine(color, center(path[k - 1]), center(path[k]), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        }
        val c = cur
        if (path.isNotEmpty() && c != null) drawLine(color.copy(alpha = 0.6f), center(path.last()), c, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        for (i in 0..8) {
            val on = i in path
            drawCircle(if (on) color else Color.White.copy(alpha = 0.35f), radius = if (on) 14.dp.toPx() else 9.dp.toPx(), center = center(i))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  The clock screen                                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun ClockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }

    val is24 = DateFormat.is24HourFormat(ctx)
    val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
    val waiting = NeoNotificationService.items.size
    var drag by remember { mutableStateOf(0f) }
    var fired by remember { mutableStateOf(false) }

    // Flashlight: a real torch switch (no permission needed)
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    var torchOn by remember { mutableStateOf(false) }
    fun toggleTorch() {
        try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) { torchOn = false }
    }
    DisposableEffect(Unit) { onDispose { if (torchOn) { try { cm.cameraIdList.forEach { cm.setTorchMode(it, false) } } catch (e: Exception) { } } } }

    val blue = Color(0xFF0A84FF)
    val glow = Brush.verticalGradient(listOf(Color.White, Color(0xFF4DA3FF), Color(0xFF0A5CFF)))

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF000308), Color(0xFF000000), Color(0xFF020A18))))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f; fired = false },
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                    onVerticalDrag = { _, dy ->
                        drag += dy
                        if (!fired && drag < -140f) { fired = true; onUnlock() }   // swiped up
                    }
                )
            }
    ) {
        // the NEO PLUS artwork in the middle, edges melted into black
        Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(top = 120.dp)) {
            Image(painterResource(R.drawable.neo_art_lock), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        }

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Lock, "Locked", tint = Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(22.dp))
            Text(date, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp)
            Text(
                time,
                style = TextStyle(
                    brush = glow, fontSize = 92.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                    shadow = Shadow(blue.copy(alpha = 0.9f), Offset(0f, 0f), 36f)
                ),
                textAlign = TextAlign.Center
            )
            StepsPill(color = Color.White.copy(alpha = 0.92f), size = 17.sp)   // 👣 today's steps, back to 0 at midnight
            if (waiting > 0) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(blue.copy(alpha = 0.25f))
                        .border(1.dp, blue.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (waiting == 1) "1 notification" else "$waiting notifications",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // fingerprint: opens Android's own unlock, which then shows the fingerprint / face / PIN the phone uses
            Box(
                Modifier.size(84.dp).clip(CircleShape).border(2.dp, blue.copy(alpha = 0.8f), CircleShape).clickable { onUnlock() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Fingerprint, "Unlock", tint = blue, modifier = Modifier.size(50.dp)) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(if (torchOn) Color.White else Color(0xFF0A1220))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape).clickable { toggleTorch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, "Flashlight",
                        tint = if (torchOn) Color.Black else Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Swipe up to unlock", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF0A1220))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable {
                            // the phone's own camera, opened above the lock without unlocking
                            val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                            try {
                                ctx.startActivity(
                                    Intent(if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.ACTION_IMAGE_CAPTURE)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                try { ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.CameraAlt, "Camera", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(width = 134.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.85f)))
        }
    }
}
