package com.neo.plus.iosluancher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay

/**
 * Real phone calls with the NEO PLUS designs.
 *
 * Android only lets the phone's default Phone app draw the incoming-call and in-call screens. So Neo Plus must be chosen as the
 * Phone app (Home screen: touch and hold > "Use Neo Plus for calls"). Then every call, in and out, uses the Neo Plus screens:
 * Incoming (Decline / Accept), Connected (Mute, Keypad tones, Speaker, Add call, Hold, Contacts, End) and Declined.
 * The ringtone and vibration are still played by Android itself.
 */
object NeoCalls {
    val calls = mutableStateListOf<Call>()
    var tick by mutableStateOf(0)
    var audio by mutableStateOf<CallAudioState?>(null)
    var service: InCallService? = null

    /** Non-null while the "Call declined" screen is up (the call itself is already gone). */
    var declinedName by mutableStateOf<String?>(null)
    var declinedNumber: String = ""

    private val main = Handler(Looper.getMainLooper())

    fun bump() { tick++ }

    fun primary(): Call? {
        @Suppress("UNUSED_VARIABLE") val t = tick   // read, so screens redraw when a call changes
        return calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
            ?: calls.firstOrNull()
    }

    fun number(call: Call): String = try { call.details?.handle?.schemeSpecificPart.orEmpty() } catch (e: Exception) { "" }

    fun name(ctx: Context, call: Call): String {
        val n = number(call)
        val shown = try { call.details?.callerDisplayName?.toString().orEmpty() } catch (e: Exception) { "" }
        if (shown.isNotBlank()) return shown
        if (n.isNotBlank() && ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) { val d = it.getString(0).orEmpty(); if (d.isNotBlank()) return d } }
            } catch (e: Exception) { }
        }
        return n.ifBlank { "Unknown caller" }
    }

    fun answer(call: Call?) { try { call?.answer(0) } catch (e: Exception) { } }

    fun decline(ctx: Context, call: Call?) {
        if (call == null) return
        declinedName = name(ctx, call)
        declinedNumber = number(call)
        try { call.reject(false, null) } catch (e: Exception) { }
    }

    fun hangUp(call: Call?) { try { call?.disconnect() } catch (e: Exception) { } }

    fun toggleHold(call: Call?) {
        try { if (call?.state == Call.STATE_HOLDING) call.unhold() else call?.hold() } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    fun toggleMute() { try { service?.setMuted(audio?.isMuted != true) } catch (e: Exception) { } }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        try {
            val onSpeaker = audio?.route == CallAudioState.ROUTE_SPEAKER
            service?.setAudioRoute(if (onSpeaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        } catch (e: Exception) { }
    }

    fun dtmf(call: Call?, c: Char) {
        try {
            call?.playDtmfTone(c)
            main.postDelayed({ try { call?.stopDtmfTone() } catch (e: Exception) { } }, 150)
        } catch (e: Exception) { }
    }
}

class NeoInCallService : InCallService() {
    companion object { private const val CH = "neo_incall"; private const val NOTIF = 7750 }

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            NeoCalls.bump()
            if (state != Call.STATE_RINGING) cancelNotif()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { NeoCalls.bump() }
    }

    @Suppress("DEPRECATION")
    override fun onCallAdded(call: Call) {
        NeoCalls.service = this
        NeoCalls.declinedName = null
        call.registerCallback(cb)
        NeoCalls.calls.add(call)
        NeoCalls.audio = try { callAudioState } catch (e: Exception) { null }
        NeoCalls.bump()
        if (call.state == Call.STATE_RINGING) notifyIncoming(call)
        openUi()
    }

    override fun onCallRemoved(call: Call) {
        try { call.unregisterCallback(cb) } catch (e: Exception) { }
        NeoCalls.calls.remove(call)
        NeoCalls.bump()
        cancelNotif()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) { NeoCalls.audio = audioState }

    private fun uiIntent() = Intent(this, NeoCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun openUi() { try { startActivity(uiIntent()) } catch (e: Exception) { } }

    /** The full-screen notification opens the incoming-call screen over the lock; it makes no sound (Android rings the phone). */
    private fun notifyIncoming(call: Call) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                ch.setSound(null, null); ch.enableVibration(false)
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(this, 1, uiIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Incoming call").setContentText(NeoCalls.name(this, call))
                .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true).setContentIntent(pi).setFullScreenIntent(pi, true).build()
            nm.notify(NOTIF, n)
        } catch (e: Exception) { }
    }

    private fun cancelNotif() { try { getSystemService(NotificationManager::class.java)?.cancel(NOTIF) } catch (e: Exception) { } }
}

object NeoDialer {
    fun isDefault(ctx: Context): Boolean = try {
        ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName
    } catch (e: Exception) { false }

    /** The Android screen that asks "Make Neo Plus your default Phone app?". Null when it already is, or the phone has none. */
    fun requestIntent(ctx: Context): Intent? {
        if (isDefault(ctx)) return null
        return if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) rm.createRequestRoleIntent(RoleManager.ROLE_DIALER) else null
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
        }
    }

    fun place(ctx: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (e: Exception) { false }
    }
}

/** The incoming / in-call / declined screen for real phone calls. */
class NeoCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NeoLock.callUp++
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContent { NeoRealCallScreen { finish() } }
    }

    override fun onDestroy() {
        NeoLock.callUp = maxOf(0, NeoLock.callUp - 1)
        if (isFinishing) NeoLock.showIfNeeded(applicationContext)
        super.onDestroy()
    }
}

@Composable
private fun NeoRealCallScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val call = NeoCalls.primary()
    val declined = NeoCalls.declinedName
    val st = call?.state ?: Call.STATE_DISCONNECTED

    // Nothing left to show (call over, no declined screen): close after a moment
    LaunchedEffect(call == null, declined) {
        if (call == null && declined == null) {
            delay(500)
            if (NeoCalls.primary() == null && NeoCalls.declinedName == null) onFinish()
        }
    }

    var secs by remember { mutableStateOf(0) }
    LaunchedEffect(call) {
        while (true) {
            val t = try { call?.details?.connectTimeMillis ?: 0L } catch (e: Exception) { 0L }
            secs = if (t > 0) ((System.currentTimeMillis() - t) / 1000).toInt().coerceAtLeast(0) else 0
            delay(500)
        }
    }

    val number = if (call != null) NeoCalls.number(call) else NeoCalls.declinedNumber
    val name = remember(call, number, declined) { if (call != null) NeoCalls.name(ctx, call) else declined.orEmpty() }
    val phase = when (st) {
        Call.STATE_RINGING -> 0
        Call.STATE_ACTIVE, Call.STATE_HOLDING -> 2
        else -> 1
    }
    val a = NeoCalls.audio

    NeoCallScreen(
        NeoCallModel(
            phase = phase, secs = secs, urgent = false, declined = declined != null,
            muted = a?.isMuted == true, held = st == Call.STATE_HOLDING, loud = a?.route == CallAudioState.ROUTE_SPEAKER,
            lines = emptyList(), heard = "", micDenied = false,
            onAnswer = { NeoCalls.answer(call) },
            onDecline = { NeoCalls.decline(ctx, call) },
            onEnd = { NeoCalls.hangUp(call) },
            onMute = { NeoCalls.toggleMute() },
            onHold = { NeoCalls.toggleHold(call) },
            onSpeaker = { NeoCalls.toggleSpeaker() },
            onType = { },
            onCallBack = {
                val n = NeoCalls.declinedNumber
                NeoCalls.declinedName = null
                if (!NeoDialer.place(ctx, n)) onFinish()
            },
            onClose = { NeoCalls.declinedName = null; onFinish() },
            callerName = name, callerSub = "is calling you…", callerNumber = number,
            showTranscript = false, header = if (phase == 1) "•  CALLING  •" else null, realCall = true,
            onDtmf = { NeoCalls.dtmf(call, it) },
            onMessage = {
                val n = NeoCalls.declinedNumber.ifBlank { number }
                NeoCalls.declinedName = null
                try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                onFinish()
            }
        )
    )
}

/** The Phone app's number pad (opened by Dial / tel: links). A number in the link is filled in, not dialled, as Android's dialer does. */
class NeoDialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pre = try { intent?.data?.schemeSpecificPart.orEmpty() } catch (e: Exception) { "" }
        setContent { DialerScreen(pre) { finish() } }
    }
}

private val DialLetters = mapOf(
    '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+"
)

/** A round glass button with a 3x3 dial-pad grid: opens the Neo Plus dialer from the home screen. */
@Composable
fun NeoDialButton(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val t = rememberFrameSeconds()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // slow pulsing ring so the button reads as "live"
        androidx.compose.foundation.Canvas(Modifier.requiredSize(size * 1.5f)) {
            val p = (t.value * 0.5f) % 1f
            drawCircle(NeoCallColors.Cyan.copy(alpha = 0.45f * (1f - p)), this.size.minDimension / 2f * (0.66f + 0.34f * p), center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
        androidx.compose.foundation.Canvas(
            Modifier.size(size).clip(CircleShape)
                .background(Brush.linearGradient(listOf(NeoCallColors.Cyan, NeoCallColors.Blue, Color(0xFF1D4ED8))))
                .border(0.9.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                .semantics { contentDescription = "Neo Plus Dialer" }
        ) {
            val w = this.size.width
            val step = w * 0.19f
            val r = w * 0.055f
            val start = w / 2 - step
            for (row in 0..2) for (col in 0..2) {
                drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(start + col * step, start + row * step))
            }
        }
    }
}

@Composable
private fun DialKey(c: Char, onKey: (Char) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (down) 0.92f else 1f, label = "key")
    Box(
        Modifier.size(76.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(
                if (down) Brush.verticalGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.55f), NeoCallColors.Blue.copy(alpha = 0.25f)))
                else Brush.verticalGradient(listOf(Color(0xFF12223D), Color(0xFF08111F)))
            )
            .border(1.dp, Brush.verticalGradient(listOf(NeoCallColors.Cyan.copy(alpha = 0.65f), NeoCallColors.Blue.copy(alpha = 0.25f))), CircleShape)
            .pointerInput(c) {
                detectTapGestures(
                    onPress = { down = true; tryAwaitRelease(); down = false },
                    onTap = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onKey(c) },
                    onLongPress = { if (c == '0') { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onKey('+') } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
            val sub = DialLetters[c]
            if (sub != null && c != '0') Text(sub, color = NeoCallColors.Cyan.copy(alpha = 0.85f), fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
            else if (c == '0') Text("+", color = NeoCallColors.Cyan.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DialerScreen(initial: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    var pendingCall by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pendingCall && NeoDialer.place(ctx, number)) onClose()
        pendingCall = false
    }
    fun call() {
        if (number.isBlank()) return
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = true; permission.launch(Manifest.permission.CALL_PHONE); return
        }
        if (NeoDialer.place(ctx, number)) onClose()
    }
    val ready = number.isNotBlank()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF040C1C), Color.Black, Color(0xFF020B1A))))) {
        // soft blue glow behind the logo
        Box(
            Modifier.fillMaxWidth().height(320.dp).background(
                Brush.radialGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(540f, 260f), radius = 700f)
            )
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.drawable.neo_art_connected), null, Modifier.height(120.dp).fillMaxWidth(), contentScale = ContentScale.Fit)
            Text("N E O   P L U S   D I A L E R", color = NeoCallColors.Cyan.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                Text(
                    number, color = Color.White, fontSize = if (number.length > 14) 24.sp else if (number.length > 10) 30.sp else 38.sp,
                    fontWeight = FontWeight.Light, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "Number" }
                )
            }
            Box(Modifier.fillMaxWidth(0.7f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, NeoCallColors.Blue.copy(alpha = 0.7f), Color.Transparent))))
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in listOf("123", "456", "789", "*0#")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { c -> DialKey(c) { number += it } }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Text("Close", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.clickable { onClose() }.padding(12.dp))
                Box(
                    Modifier.size(78.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFF4ADE80), NeoCallColors.Green, Color(0xFF15803D))).let { if (ready) it else Brush.verticalGradient(listOf(Color(0xFF1F3B2B), Color(0xFF16291F))) })
                        .clickable { call() }
                        .semantics { contentDescription = "Call" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Call, "Call", tint = Color.White.copy(alpha = if (ready) 1f else 0.5f), modifier = Modifier.size(36.dp)) }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).pointerInput(Unit) {
                        detectTapGestures(onTap = { if (number.isNotEmpty()) number = number.dropLast(1) }, onLongPress = { number = "" })
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Backspace, "Delete", tint = Color.White.copy(alpha = if (ready) 0.85f else 0.25f), modifier = Modifier.size(26.dp)) }
            }
        }
    }
}
