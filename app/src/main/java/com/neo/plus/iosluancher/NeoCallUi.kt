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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/**
 * The NEO PLUS call screens (incoming, connected, declined) drawn from the supplied designs.
 * The logo art is cropped from the designs; every button, timer and word around it is real and live.
 * Used by the wake-up call and the approval call.
 */
object NeoCallColors {
    val Blue = Color(0xFF0A84FF)
    val Cyan = Color(0xFF38BDF8)
    val Red = Color(0xFFE5322D)
    val Green = Color(0xFF22C55E)
    val Panel = Color(0xFF05101F)
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
)

@Composable
fun NeoCallScreen(m: NeoCallModel, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF020611), Color(0xFF000000), Color(0xFF020B1A)))
        )
    ) {
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
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        SpacedTitle(if (m.urgent) "URGENT CALL" else "INCOMING CALL", if (m.urgent) Color(0xFFFF9F0A) else Color.White)
        Art(R.drawable.neo_art_incoming, Modifier.weight(1f).fillMaxWidth())
        CallerCard(true, m.callerName, m.callerSub)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundAction(NeoCallColors.Red, Icons.Filled.CallEnd, "DECLINE", 84.dp) { m.onDecline() }
            RoundAction(NeoCallColors.Green, Icons.Filled.Call, "ACCEPT", 84.dp) { m.onAnswer() }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CallerCard(waveform: Boolean, name: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(NeoCallColors.Panel)
            .border(1.dp, NeoCallColors.Blue.copy(alpha = 0.7f), RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.neo_icon), null,
            Modifier.size(62.dp).clip(CircleShape).border(1.5.dp, NeoCallColors.Blue, CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sub, color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp)
            if (waveform) { Spacer(Modifier.height(6.dp)); Waveform(Modifier.fillMaxWidth().height(22.dp)) }
        }
    }
}

@Composable
private fun Waveform(modifier: Modifier) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { t += 0.35f; delay(60) } }
    Canvas(modifier) {
        val n = 48
        val step = size.width / n
        for (i in 0 until n) {
            val env = 1f - abs(i - n / 2f) / (n / 2f) * 0.6f
            val h = (0.15f + 0.85f * abs(sin(i * 0.7f + t))) * env * size.height
            val x = step * i + step / 2
            drawLine(
                NeoCallColors.Blue, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Connected                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun ConnectedScreen(m: NeoCallModel, ctx: Context, extra: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(m.lines.size, m.lines.lastOrNull()?.second?.length, m.heard) { scroll.animateScrollTo(scroll.maxValue) }

    val clock = "%02d:%02d:%02d".format(m.secs / 3600, (m.secs / 60) % 60, m.secs % 60)
    val status = if (!m.showTranscript) when {
        m.held -> "ON HOLD"
        m.muted -> "MUTED"
        m.phase == 1 -> "CALLING…"
        else -> ""
    } else when {
        m.held -> "ON HOLD"
        m.muted && m.phase >= 3 -> "MUTED"
        m.phase == 1 -> "CONNECTING…"
        m.phase == 2 -> "SPEAKING"
        m.phase == 3 -> "LISTENING…"
        else -> "THINKING…"
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        SpacedTitle(m.header ?: "•  CALL CONNECTED  •", NeoCallColors.Blue)
        Spacer(Modifier.height(4.dp))
        Text(clock, color = NeoCallColors.Blue, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 3.sp)
        if (m.waitingText.isNotBlank()) Text(m.waitingText, color = Color(0xFFFFD60A), fontSize = 13.sp)
        Art(R.drawable.neo_art_connected, Modifier.height(150.dp).fillMaxWidth())

        if (!m.showTranscript) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(m.callerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (m.callerNumber.isNotBlank() && m.callerNumber != m.callerName) {
                    Text(m.callerNumber, color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
                }
            }
        } else Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NeoCallColors.Panel.copy(alpha = 0.8f))
                .border(1.dp, NeoCallColors.Blue.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .verticalScroll(scroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            extra()
            for ((me, line) in m.lines) {
                Row(Modifier.fillMaxWidth(), if (me) Arrangement.End else Arrangement.Start) {
                    Text(
                        line, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (me) Color(0xFF2563EB) else Color.White.copy(alpha = 0.12f))
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
                    color = Color(0xFFFFD60A), fontSize = 15.sp,
                    modifier = Modifier.clickable { permission.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }

        if (typing) {
            if (m.realCall) DtmfPad(m.onDtmf)
            else TypeBox(onSend = { typing = false; if (it.isNotBlank()) m.onType(it.trim()) }, onCancel = { typing = false })
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            RoundTool(Icons.Filled.MicOff, "MUTE", m.muted) { m.onMute() }
            RoundTool(Icons.Filled.Dialpad, "KEYPAD", typing) { typing = !typing }
            RoundTool(Icons.Filled.VolumeUp, "SPEAKER", m.loud) { m.onSpeaker() }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            RoundTool(Icons.Filled.Add, "ADD CALL", false) {
                try { ctx.startActivity(Intent(ctx, NeoDialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            }
            RoundTool(if (m.held) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (m.held) "RESUME" else "HOLD", m.held) { m.onHold() }
            RoundTool(Icons.Filled.Contacts, "CONTACTS", false) {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(NeoCallColors.Red)
                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape).clickable { m.onEnd() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(18.dp))
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
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(54.dp).clip(CircleShape).border(1.5.dp, NeoCallColors.Red, CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.CallEnd, null, tint = NeoCallColors.Red, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(10.dp))
        SpacedTitle("CALL DECLINED", NeoCallColors.Red, 26)
        Text("•  You declined the call  •", color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
        Art(R.drawable.neo_art_declined, Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(NeoCallColors.Panel)
                .border(1.dp, NeoCallColors.Blue.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).border(2.dp, NeoCallColors.Red, CircleShape).background(Color(0xFF2A0508)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, null, tint = NeoCallColors.Red, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.size(16.dp))
            Column {
                Text("Call Declined", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("You declined the call from", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
                Text(m.callerName, color = NeoCallColors.Blue, fontSize = 18.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            TextTool(Icons.Filled.Call, "CALL BACK") { m.onCallBack() }
            TextTool(Icons.Filled.ChatBubble, "SEND MESSAGE") {
                val custom = m.onMessage
                if (custom != null) { custom(); return@TextTool }
                try {
                    ctx.startActivity(
                        (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
                m.onClose()
            }
            TextTool(Icons.Filled.Schedule, "REMIND ME") {
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
        Spacer(Modifier.height(18.dp))
        Text("Tap to close", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
            modifier = Modifier.clickable { m.onClose() }.padding(10.dp))
        Spacer(Modifier.height(12.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Parts                                                               */
/* ------------------------------------------------------------------ */

@Composable
private fun Art(res: Int, modifier: Modifier) {
    Box(modifier) {
        Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        // melt the cut edges of the picture into the black screen
        Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
    }
}

@Composable
private fun SpacedTitle(text: String, color: Color, size: Int = 18) {
    Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.Medium, letterSpacing = 6.sp, textAlign = TextAlign.Center)
}

@Composable
private fun RoundAction(color: Color, icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size + 12.dp).clip(CircleShape).border(1.dp, color.copy(alpha = 0.6f), CircleShape).padding(6.dp)
                .clip(CircleShape).background(color).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 4.sp)
    }
}

@Composable
private fun RoundTool(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).clip(CircleShape)
                .background(if (on) NeoCallColors.Blue else Color(0xFF0A1628))
                .border(1.5.dp, NeoCallColors.Blue.copy(alpha = if (on) 1f else 0.55f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun TextTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Icon(icon, label, tint = NeoCallColors.Blue, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 1.sp)
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
