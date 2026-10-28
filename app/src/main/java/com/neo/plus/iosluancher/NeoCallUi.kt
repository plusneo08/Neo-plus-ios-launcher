package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/**
 * The call screens: incoming, in a call, declined. One design for every kind of call.
 *
 *  - Real phone calls (Neo Plus as the Phone app): the person is the subject. Graphite background, a large photo or
 *    initials, name, number, a timer, a 2x3 grid of tools and one wide End call button.
 *  - Assistant calls (wake-up call, approval call): the same layout with a soft indigo glow, the assistant's mark, an
 *    "AI assistant" label and a live conversation with a wave that shows who is talking.
 *  - AiCallScreen.kt adds the screen shown while the assistant answers a call for you.
 *
 * Colour is used for meaning only: green accepts, red ends, indigo means "the assistant", amber warns.
 */
object NeoCallColors {
    // kept for the dialer and other screens that still use these names
    val Blue = Color(0xFF0A84FF)
    val Cyan = Color(0xFF38BDF8)
    val Red = Color(0xFFE5322D)
    val Green = Color(0xFF22C55E)
    val Panel = Color(0xFF05101F)

    // call screen palette
    val Ink = Color(0xFF0B0E14)
    val Ink2 = Color(0xFF131824)
    val Surface = Color(0xFF1A2030)
    val SurfaceHi = Color(0xFF242C40)
    val Line = Color(0xFF2B3449)
    val Text = Color(0xFFF2F5FA)
    val Muted = Color(0xFF9AA5BA)
    val Accept = Color(0xFF2DBE72)
    val Decline = Color(0xFFE5484D)
    val Ai = Color(0xFF8E9CFF)
    val AiDeep = Color(0xFF3F4FD0)
    val Warn = Color(0xFFF5B544)
}

/** Everything the screen needs from a call session. phase: 0 ringing, 1 connecting, 2 speaking, 3 listening, 4 thinking. */
class NeoCallModel(
    val phase: Int,
    val secs: Int,
    val urgent: Boolean,
    val declined: Boolean,
    val muted: Boolean,
    val held: Boolean,
    val loud: Boolean,
    val lines: List<Pair<Boolean, String>>,
    val heard: String,
    val micDenied: Boolean,
    val waitingText: String = "",
    val onAnswer: () -> Unit,
    val onDecline: () -> Unit,
    val onEnd: () -> Unit,
    val onMute: () -> Unit,
    val onHold: () -> Unit,
    val onSpeaker: () -> Unit,
    val onType: (String) -> Unit,
    val onCallBack: () -> Unit,
    val onClose: () -> Unit,
    val onMicGranted: () -> Unit = {},
    // real phone calls
    val callerName: String = "NEO PLUS",
    val callerSub: String = "is calling you…",
    val callerNumber: String = "",
    val callerPhoto: ImageBitmap? = null,
    val showTranscript: Boolean = true,
    val header: String? = null,
    val realCall: Boolean = false,
    val onDtmf: (Char) -> Unit = {},
    val onMessage: (() -> Unit)? = null,
    /** Assistant calls: take a photo with the camera and show it to the assistant. */
    val onCamera: (() -> Unit)? = null,
)

@Composable
fun NeoCallScreen(m: NeoCallModel, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val ai = !m.realCall
    NcBackground(ai) {
        when {
            m.declined -> NcDeclined(m, ctx)
            m.phase == 0 -> NcIncoming(m)
            m.showTranscript -> NcAssistantCall(m, extra)
            else -> NcPersonCall(m, ctx)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Incoming                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun NcIncoming(m: NeoCallModel) {
    val ai = !m.realCall
    val title = if (ai && m.callerName.equals("NEO PLUS", true)) "Neo Plus Assistant" else m.callerName
    val secondary = when {
        ai -> if (m.urgent) "Needs your attention" else "Calling you…"
        m.callerNumber.isNotBlank() && m.callerNumber != m.callerName -> m.callerNumber
        else -> "Calling you…"
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            if (m.urgent) "Urgent call" else if (ai) "Assistant call" else "Incoming call",
            color = if (m.urgent) NeoCallColors.Warn else NeoCallColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(44.dp))
        NcAvatar(title, m.callerPhoto, 176.dp, ai, true)
        Spacer(Modifier.height(28.dp))
        Text(
            title, color = NeoCallColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(secondary, color = NeoCallColors.Muted, fontSize = 17.sp, style = NcNumbers)
        if (ai) {
            Spacer(Modifier.height(18.dp))
            NcChip("AI assistant", NeoCallColors.Ai)
            Spacer(Modifier.height(14.dp))
            NcWave(Modifier.fillMaxWidth(0.45f).height(24.dp), NeoCallColors.Ai, true)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            NcRound(NeoCallColors.Decline, Icons.Filled.CallEnd, "Decline", 76.dp) { m.onDecline() }
            NcRound(NeoCallColors.Accept, Icons.Filled.Call, "Accept", 76.dp) { m.onAnswer() }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  In a real call: the person is the subject                           */
/* ------------------------------------------------------------------ */

@Composable
private fun NcPersonCall(m: NeoCallModel, ctx: Context) {
    var keypad by remember { mutableStateOf(false) }
    val state = when {
        m.held -> "On hold"
        m.muted -> "Muted"
        m.phase == 1 -> "Calling…"
        else -> ""
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))
        Text(m.header?.let { it.replace("•", "").trim().lowercase().replaceFirstChar { c -> c.uppercase() } } ?: "In call",
            color = NeoCallColors.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(22.dp))
        NcAvatar(m.callerName, m.callerPhoto, 120.dp, false, false)
        Spacer(Modifier.height(18.dp))
        Text(m.callerName, color = NeoCallColors.Text, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (m.callerNumber.isNotBlank() && m.callerNumber != m.callerName) {
            Text(m.callerNumber, color = NeoCallColors.Muted, fontSize = 16.sp, style = NcNumbers)
        }
        Spacer(Modifier.height(10.dp))
        Text(if (m.phase == 1) "" else NcTime(m.secs), color = NeoCallColors.Text, fontSize = 20.sp, style = NcNumbers)
        Text(state, color = NeoCallColors.Warn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))

        if (keypad) { NcDtmf(m.onDtmf); Spacer(Modifier.height(14.dp)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NcTile(Icons.Filled.MicOff, "Mute", m.muted, Modifier.weight(1f)) { m.onMute() }
            NcTile(Icons.Filled.Dialpad, "Keypad", keypad, Modifier.weight(1f)) { keypad = !keypad }
            NcTile(Icons.Filled.VolumeUp, "Speaker", m.loud, Modifier.weight(1f)) { m.onSpeaker() }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NcTile(Icons.Filled.Add, "Add call", false, Modifier.weight(1f)) {
                try { ctx.startActivity(Intent(ctx, NeoDialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            }
            NcTile(if (m.held) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (m.held) "Resume" else "Hold", m.held, Modifier.weight(1f)) { m.onHold() }
            NcTile(Icons.Filled.Contacts, "Contacts", false, Modifier.weight(1f)) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            }
        }
        Spacer(Modifier.height(18.dp))
        NcEnd("End call") { m.onEnd() }
        Spacer(Modifier.height(22.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  In an assistant call: a conversation                                */
/* ------------------------------------------------------------------ */

@Composable
private fun NcAssistantCall(m: NeoCallModel, extra: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(m.lines.size, m.lines.lastOrNull()?.second?.length, m.heard) { scroll.animateScrollTo(scroll.maxValue) }

    val title = if (m.callerName.equals("NEO PLUS", true)) "Neo Plus Assistant" else m.callerName
    val state = when {
        m.held -> "On hold"
        m.muted && m.phase >= 3 -> "Muted"
        m.phase == 1 -> "Connecting…"
        m.phase == 2 -> "Speaking"
        m.phase == 3 -> "Listening"
        else -> "Thinking…"
    }
    val live = !m.held && !(m.muted && m.phase >= 3) && m.phase >= 2

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NcAvatar(title, null, 52.dp, true, false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = NeoCallColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.header?.replace("•", "")?.trim() ?: "AI assistant", color = NeoCallColors.Muted, fontSize = 13.sp)
            }
            Text(NcTime(m.secs), color = NeoCallColors.Text, fontSize = 18.sp, style = NcNumbers)
        }
        if (m.waitingText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(m.waitingText, color = NeoCallColors.Warn, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(NeoCallColors.Surface.copy(alpha = 0.85f))
                .verticalScroll(scroll).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            extra()
            for ((me, line) in m.lines) NcBubble(line, me)
            if (m.phase == 3 && m.heard.isNotBlank() && !m.muted) {
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    Text(m.heard, color = NeoCallColors.Muted, fontSize = 16.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(end = 4.dp))
                }
            }
            if (m.micDenied) {
                val permission = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { ok -> if (ok) m.onMicGranted() }
                Text(
                    "Tap here and allow the microphone so I can hear you.",
                    color = NeoCallColors.Warn, fontSize = 15.sp,
                    modifier = Modifier.clickable { permission.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NcWave(Modifier.width(70.dp).height(20.dp), if (m.phase == 3) NeoCallColors.Accept else NeoCallColors.Ai, live)
            Spacer(Modifier.width(10.dp))
            Text(state, color = NeoCallColors.Muted, fontSize = 14.sp)
        }

        if (typing) NcTypeBox(onSend = { typing = false; if (it.isNotBlank()) m.onType(it.trim()) }, onCancel = { typing = false })

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NcTile(Icons.Filled.MicOff, "Mute", m.muted, Modifier.weight(1f), compact = true) { m.onMute() }
            NcTile(Icons.Filled.Dialpad, "Type", typing, Modifier.weight(1f), compact = true) { typing = !typing }
            NcTile(Icons.Filled.VolumeUp, "Speaker", m.loud, Modifier.weight(1f), compact = true) { m.onSpeaker() }
            val cam = m.onCamera
            if (cam != null) NcTile(Icons.Filled.CameraAlt, "Show me", false, Modifier.weight(1f), compact = true) { cam() }
            else NcTile(if (m.held) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (m.held) "Resume" else "Hold", m.held, Modifier.weight(1f), compact = true) { m.onHold() }
        }
        Spacer(Modifier.height(14.dp))
        NcEnd("End call") { m.onEnd() }
        Spacer(Modifier.height(18.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Declined                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun NcDeclined(m: NeoCallModel, ctx: Context) {
    // Closes by itself after a few seconds unless a button is used (the session turns "declined" off for Call back)
    LaunchedEffect(Unit) { delay(9000); m.onClose() }
    val title = if (!m.realCall && m.callerName.equals("NEO PLUS", true)) "Neo Plus Assistant" else m.callerName
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.8f))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(NeoCallColors.Decline.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CallEnd, null, tint = NeoCallColors.Decline, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("Call declined", color = NeoCallColors.Text, fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = NeoCallColors.Muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (m.realCall && m.callerNumber.isNotBlank() && m.callerNumber != m.callerName) {
            Text(m.callerNumber, color = NeoCallColors.Muted, fontSize = 14.sp, style = NcNumbers)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NcTile(Icons.Filled.Call, "Call back", false, Modifier.weight(1f)) { m.onCallBack() }
            NcTile(Icons.Filled.ChatBubble, "Message", false, Modifier.weight(1f)) {
                val custom = m.onMessage
                if (custom != null) { custom(); return@NcTile }
                try {
                    ctx.startActivity((ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
                m.onClose()
            }
            NcTile(Icons.Filled.Schedule, "Remind me", false, Modifier.weight(1f)) {
                // 10 minute timer in the phone's clock app (no screen), so it rings and reminds you to come back
                try {
                    ctx.startActivity(
                        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 600)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, "Neo Plus called you")
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
                m.onClose()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Close", color = NeoCallColors.Muted, fontSize = 15.sp, modifier = Modifier.clickable { m.onClose() }.padding(16.dp))
        Spacer(Modifier.height(20.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Parts (internal: AiCallScreen.kt uses them too)                     */
/* ------------------------------------------------------------------ */

/** Digits keep their width so a running timer does not jiggle. */
internal val NcNumbers = TextStyle(fontFeatureSettings = "tnum")

internal fun NcTime(secs: Int): String =
    if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60) else "%02d:%02d".format(secs / 60, secs % 60)

/** Graphite background; assistant calls get a soft indigo glow from the top so they read as different at a glance. */
@Composable
internal fun NcBackground(ai: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(NeoCallColors.Ink, NeoCallColors.Ink2)))
            .drawBehind {
                if (ai) drawRect(
                    Brush.radialGradient(
                        colors = listOf(NeoCallColors.Ai.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width / 2f, 0f), radius = size.width * 0.95f
                    )
                )
            }
    ) { content() }
}

/** Photo, the assistant's mark, or initials. [ring] adds the slow pulse used while a call is ringing. */
@Composable
internal fun NcAvatar(name: String, photo: ImageBitmap?, size: Dp, ai: Boolean, ring: Boolean) {
    val t = rememberFrameSeconds()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring) Canvas(Modifier.size(size)) {
            val tint = if (ai) NeoCallColors.Ai else Color.White
            for (k in 0..1) {
                val q = (t.value * 0.45f + k * 0.5f) % 1f
                drawCircle(tint.copy(alpha = 0.30f * (1f - q)), this.size.minDimension / 2f * (0.80f + 0.20f * q), center, style = Stroke(width = 2.dp.toPx()))
            }
        }
        val inner = size * 0.80f
        when {
            photo != null -> Image(photo, null, Modifier.size(inner).clip(CircleShape), contentScale = ContentScale.Crop)
            ai -> Image(
                painterResource(R.drawable.neo_icon), null,
                Modifier.size(inner).clip(CircleShape).border(1.dp, NeoCallColors.Line, CircleShape), contentScale = ContentScale.Crop
            )
            else -> {
                val letters = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
                Box(Modifier.size(inner).clip(CircleShape).background(NeoCallColors.SurfaceHi), contentAlignment = Alignment.Center) {
                    if (name.firstOrNull()?.isLetter() == true && letters.isNotEmpty())
                        Text(letters, color = NeoCallColors.Text, fontSize = (inner.value * 0.36f).sp, fontWeight = FontWeight.Normal)
                    else Icon(Icons.Filled.Call, null, tint = NeoCallColors.Muted, modifier = Modifier.size(inner * 0.38f))
                }
            }
        }
    }
}

@Composable
internal fun NcChip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** A quiet wave. [active] makes it move; otherwise it rests as a flat line. */
@Composable
internal fun NcWave(modifier: Modifier, color: Color, active: Boolean) {
    val t = rememberFrameSeconds()
    Canvas(modifier) {
        val n = 28
        val step = size.width / n
        for (i in 0 until n) {
            val env = 1f - abs(i - n / 2f) / (n / 2f) * 0.65f
            val amp = if (active) 0.2f + 0.8f * abs(sin(i * 0.65f + t.value * 7f)) else 0.12f
            val h = amp * env * size.height
            val x = step * i + step / 2
            drawLine(color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun NcRound(color: Color, icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.46f)) }
        Spacer(Modifier.height(10.dp))
        Text(label, color = NeoCallColors.Muted, fontSize = 14.sp)
    }
}

/** A tool: rounded square, filled white when on. */
@Composable
internal fun NcTile(icon: ImageVector, label: String, on: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) NeoCallColors.Text else NeoCallColors.Surface)
            .clickable { onClick() }
            .padding(vertical = if (compact) 11.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (on) NeoCallColors.Ink else NeoCallColors.Text, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
        Spacer(Modifier.height(if (compact) 4.dp else 7.dp))
        Text(label, color = if (on) NeoCallColors.Ink else NeoCallColors.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun NcEnd(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(NeoCallColors.Decline).clickable { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One line of a conversation. [mine] = right side, indigo; the other side is a quiet surface. */
@Composable
internal fun NcBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), if (mine) Arrangement.End else Arrangement.Start) {
        val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
        Text(
            text, color = NeoCallColors.Text, fontSize = 16.sp, lineHeight = 22.sp,
            modifier = Modifier.clip(shape).background(if (mine) NeoCallColors.AiDeep else NeoCallColors.SurfaceHi)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** Number keys sent as tones into the live call (for menus like "press 1 for ..."). */
@Composable
private fun NcDtmf(onKey: (Char) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in listOf("123", "456", "789", "*0#")) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) { row.forEach { c -> PadKey(c, 52.dp, true, onKey) } }
        }
    }
}

@Composable
private fun NcTypeBox(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(26.dp)).background(NeoCallColors.Surface)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Type to the assistant…", color = NeoCallColors.Muted, fontSize = 16.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = NeoCallColors.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(NeoCallColors.Ai), modifier = Modifier.fillMaxWidth()
            )
        }
        Text("Send", color = NeoCallColors.Ai, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onSend(text) }.padding(start = 12.dp))
        Icon(Icons.Filled.Close, "Close", tint = NeoCallColors.Muted,
            modifier = Modifier.padding(start = 12.dp).size(20.dp).clickable { onCancel() })
    }
}

/** Small action button used by the approval call (Send / Skip). */
@Composable
fun NeoCallPill(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).clickable { onClick() }.padding(horizontal = 22.dp, vertical = 9.dp)
    )
}

/** Loud speaker: raises the media volume to the top while on, and puts it back when off. */
object NeoLoud {
    private var saved = -1
    fun toggle(ctx: Context, on: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (on) { saved = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0) }
            else if (saved >= 0) { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, saved, 0); saved = -1 }
        } catch (e: Exception) { }
    }
}
