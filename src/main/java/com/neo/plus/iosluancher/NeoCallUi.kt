package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * The NEO PLUS call screens (incoming, connected, declined) for normal phone calls AND assistant (AI) calls.
 * One clean, consistent style: dark glass, one blue accent, big readable name and timer, a tidy 3 x 2 button grid.
 * Used by real calls (NeoCalls.kt), the wake-up / assistant call (WakeCallActivity.kt) and the approval call.
 */
object NeoCallColors {
    val Blue = Color(0xFF0A84FF)
    val Cyan = Color(0xFF38BDF8)
    val Red = Color(0xFFE5322D)
    val Green = Color(0xFF22C55E)
    val Panel = Color(0xFF05101F)
    val Amber = Color(0xFFFFB020)
    val Grey = Color(0xFF8E98A8)
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
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0C1C36), Color(0xFF060C18), Color(0xFF000000)))
        )
    ) {
        // soft blue glow behind the avatar
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(420.dp)
                .background(Brush.radialGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.20f), Color.Transparent)))
        )
        when {
            m.declined -> DeclinedScreen(m, ctx)
            m.phase == 0 -> IncomingScreen(m)
            else -> ConnectedScreen(m, ctx, extra)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Incoming                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun IncomingScreen(m: NeoCallModel) {
    val label = when {
        m.urgent -> "URGENT CALL"
        m.realCall -> "INCOMING CALL"
        else -> "AI ASSISTANT"
    }
    val accent = if (m.urgent) NeoCallColors.Amber else NeoCallColors.Blue
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        StatusChip(label, accent)
        Spacer(Modifier.weight(1f))
        Avatar(m, 132.dp, accent, true)
        Spacer(Modifier.height(24.dp))
        Text(
            m.callerName, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(m.callerSub, color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp, textAlign = TextAlign.Center)
        if (m.callerNumber.isNotBlank() && m.callerNumber != m.callerName) {
            Spacer(Modifier.height(2.dp))
            Text(m.callerNumber, color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            CallButton(NeoCallColors.Red, Icons.Filled.CallEnd, "Decline") { m.onDecline() }
            CallButton(NeoCallColors.Green, Icons.Filled.Call, "Accept") { m.onAnswer() }
        }
        Spacer(Modifier.height(44.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Connected                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun ConnectedScreen(m: NeoCallModel, ctx: Context, extra: @Composable () -> Unit) {
    val ai = m.showTranscript
    val scroll = rememberScrollState()
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(m.lines.size, m.lines.lastOrNull()?.second?.length, m.heard) { scroll.animateScrollTo(scroll.maxValue) }

    val state: Pair<String, Color> = when {
        m.held -> "On hold" to NeoCallColors.Grey
        m.muted && (m.phase >= 3 || !ai) -> "Muted" to NeoCallColors.Grey
        m.phase == 1 -> (if (ai) "Connecting…" else "Calling…") to NeoCallColors.Amber
        !ai -> "Connected" to NeoCallColors.Green
        m.phase == 2 -> "Speaking" to NeoCallColors.Blue
        m.phase == 3 -> "Listening" to NeoCallColors.Green
        else -> "Thinking…" to NeoCallColors.Amber
    }
    val level: Float = when {
        m.held || (m.muted && m.phase >= 3) -> 0f
        m.phase == 2 -> 1f
        m.phase == 3 -> if (m.heard.isNotBlank()) 0.8f else 0.35f
        m.phase == 4 -> 0.25f
        else -> 0.1f
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(14.dp))
        Avatar(m, if (ai) 84.dp else 112.dp, state.second, !m.held && !m.muted && m.phase >= 1)
        Spacer(Modifier.height(10.dp))
        Text(
            m.callerName, color = Color.White, fontSize = if (ai) 24.sp else 30.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (!ai && m.callerNumber.isNotBlank() && m.callerNumber != m.callerName) {
            Text(m.callerNumber, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockText(m.secs), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text("   •   ", color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp)
            Box(Modifier.size(8.dp).clip(CircleShape).background(state.second))
            Spacer(Modifier.size(7.dp))
            Text(state.first, color = state.second, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (ai) {
            Spacer(Modifier.height(10.dp))
            VoiceBars(level, state.second, Modifier.fillMaxWidth(0.5f).height(30.dp))
        }
        if (m.waitingText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            StatusChip(m.waitingText, NeoCallColors.Amber)
        }
        Spacer(Modifier.height(12.dp))

        if (!ai) {
            Spacer(Modifier.weight(1f))
        } else Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(24.dp))
                .verticalScroll(scroll).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            extra()
            if (m.lines.isEmpty() && m.heard.isBlank()) {
                Text("Live conversation appears here", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
            }
            for ((me, line) in m.lines) {
                Row(Modifier.fillMaxWidth(), if (me) Arrangement.End else Arrangement.Start) {
                    Text(
                        line, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp,
                        modifier = Modifier.widthIn(max = 290.dp)
                            .clip(
                                if (me) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
                                else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
                            )
                            .background(if (me) Color(0xFF2563EB) else Color.White.copy(alpha = 0.11f))
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
            if (m.phase == 3 && m.heard.isNotBlank() && !m.muted) {
                Row(Modifier.fillMaxWidth(), Arrangement.End) {
                    Text(m.heard, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, fontStyle = FontStyle.Italic)
                }
            }
            if (m.micDenied) {
                val permission = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { ok -> if (ok) m.onMicGranted() }
                Text(
                    "Tap here and allow the microphone so I can hear you.",
                    color = NeoCallColors.Amber, fontSize = 15.sp,
                    modifier = Modifier.clickable { permission.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }

        if (typing) {
            if (m.realCall) DtmfPad(m.onDtmf)
            else TypeBox(onSend = { typing = false; if (it.isNotBlank()) m.onType(it.trim()) }, onCancel = { typing = false })
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            RoundTool(Icons.Filled.MicOff, "Mute", m.muted) { m.onMute() }
            RoundTool(if (m.realCall) Icons.Filled.Dialpad else Icons.Filled.Keyboard, if (m.realCall) "Keypad" else "Type", typing) { typing = !typing }
            RoundTool(Icons.Filled.VolumeUp, "Speaker", m.loud) { m.onSpeaker() }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            val cam = m.onCamera
            if (cam != null) RoundTool(Icons.Filled.CameraAlt, "Show me", false) { cam() }
            else RoundTool(Icons.Filled.Add, "Add call", false) {
                try { ctx.startActivity(Intent(ctx, NeoDialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            }
            RoundTool(if (m.held) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (m.held) "Resume" else "Hold", m.held) { m.onHold() }
            RoundTool(Icons.Filled.Contacts, "Contacts", false) {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(NeoCallColors.Red).clickable { m.onEnd() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(20.dp))
    }
}

/** Number keys sent as tones into the live call (for menus like "press 1 for ..."). */
@Composable
private fun DtmfPad(onKey: (Char) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in listOf("123", "456", "789", "*0#")) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) { row.forEach { c -> PadKey(c, 48.dp, true, onKey) } }
        }
    }
}

@Composable
private fun TypeBox(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Type to the assistant…", color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth()
            )
        }
        Text("Send", color = NeoCallColors.Cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onSend(text) }.padding(start = 12.dp))
        Icon(Icons.Filled.Close, "Close", tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 12.dp).size(20.dp).clickable { onCancel() })
    }
}

/* ------------------------------------------------------------------ */
/*  Declined                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun DeclinedScreen(m: NeoCallModel, ctx: Context) {
    // Closes by itself after a few seconds unless a button is used (the session turns "declined" off for Call back)
    LaunchedEffect(Unit) { delay(9000); m.onClose() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(92.dp).clip(CircleShape).background(NeoCallColors.Red.copy(alpha = 0.14f))
                .border(1.5.dp, NeoCallColors.Red.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CallEnd, null, tint = NeoCallColors.Red, modifier = Modifier.size(42.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("Call declined", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(6.dp))
        Text(
            m.callerName, color = Color.White.copy(alpha = 0.8f), fontSize = 19.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            ActionTile(Icons.Filled.Call, "Call back", NeoCallColors.Green) { m.onCallBack() }
            ActionTile(Icons.Filled.ChatBubble, "Message", NeoCallColors.Blue) {
                val custom = m.onMessage
                if (custom != null) { custom(); return@ActionTile }
                try {
                    ctx.startActivity(
                        (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
                m.onClose()
            }
            ActionTile(Icons.Filled.Schedule, "Remind me", NeoCallColors.Amber) {
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
        Spacer(Modifier.height(20.dp))
        Text("Close", color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp,
            modifier = Modifier.clickable { m.onClose() }.padding(12.dp))
        Spacer(Modifier.height(20.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Parts                                                               */
/* ------------------------------------------------------------------ */

private fun clockText(s: Int): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)

private fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetter() }
    return words.take(2).joinToString("") { it.substring(0, 1).uppercase() }
}

/** Round picture of the caller: the NEO PLUS logo for assistant calls, the caller's initials for real calls, with soft pulse rings. */
@Composable
private fun Avatar(m: NeoCallModel, diameter: Dp, ring: Color, pulse: Boolean) {
    val transition = rememberInfiniteTransition(label = "avatarPulse")
    val p by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    Box(Modifier.size(diameter * 1.8f), contentAlignment = Alignment.Center) {
        if (pulse) Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val q = (p + k * 0.5f) % 1f
                drawCircle(
                    ring.copy(alpha = 0.40f * (1f - q)),
                    radius = diameter.toPx() / 2f * (1f + 0.75f * q),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        if (!m.realCall) {
            Image(
                painterResource(R.drawable.neo_icon), null,
                Modifier.size(diameter).clip(CircleShape).border(2.dp, ring.copy(alpha = 0.9f), CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            val ini = initialsOf(m.callerName)
            Box(
                Modifier.size(diameter).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1E40AF))))
                    .border(2.dp, ring.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (ini.isNotEmpty()) Text(ini, color = Color.White, fontSize = (diameter.value * 0.38f).sp, fontWeight = FontWeight.Medium)
                else Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(diameter * 0.55f))
            }
        }
    }
}

/** Live bars: tall while the assistant speaks, medium while it listens, low while it thinks, flat on hold / muted. */
@Composable
private fun VoiceBars(level: Float, color: Color, modifier: Modifier) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { t += 0.3f; delay(50) } }
    Canvas(modifier) {
        val n = 21
        val step = size.width / n
        for (i in 0 until n) {
            val env = 1f - abs(i - (n - 1) / 2f) / (n / 2f) * 0.65f
            val h = (0.10f + level * 0.90f * abs(sin(i * 0.6f + t))) * env * size.height
            val x = step * i + step / 2
            drawLine(
                color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun CallButton(color: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(color).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

/** Control button: glass when off, solid white with a dark icon when on (like a phone's in-call screen). */
@Composable
private fun RoundTool(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 76.dp)) {
        Box(
            Modifier.size(62.dp).clip(CircleShape)
                .background(if (on) Color.White else Color.White.copy(alpha = 0.09f))
                .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.14f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (on) Color(0xFF0B1628) else Color.White, modifier = Modifier.size(27.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(6.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

@Composable
fun NeoCallPill(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).clickable { onClick() }.padding(horizontal = 20.dp, vertical = 8.dp)
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
