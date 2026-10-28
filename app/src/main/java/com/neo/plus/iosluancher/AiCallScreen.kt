package com.neo.plus.iosluancher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shown while the assistant answers a call for you (CallConversation). You watch the conversation as it happens:
 * the caller's words on the left, the assistant's replies on the right, who is talking now, the language it detected,
 * and the mode that is on. Two choices: Take over (the loudspeaker goes off so you can talk, the assistant stops) or End call.
 */
@Composable
fun NeoAiAnsweringScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val active = CallConversation.active
    val lines = CallConversation.ui
    val scroll = rememberScrollState()
    var secs by remember { mutableStateOf(0) }
    val name = CallConversation.uiWho.ifBlank { "Unknown caller" }
    val number = CallConversation.uiNumber
    val photo = remember(number) { if (number.isBlank()) null else ContactPhotos.get(ctx, number)?.asImageBitmap() }
    val mode = remember(active) { Modes.current(ctx) }
    val until = remember(active) { Modes.endTime(ctx) }

    LaunchedEffect(Unit) {
        while (true) {
            val s = CallConversation.uiStart
            if (CallConversation.active && s > 0) secs = ((System.currentTimeMillis() - s) / 1000).toInt().coerceAtLeast(0)
            delay(500)
        }
    }
    LaunchedEffect(lines.size, CallConversation.uiHeard) { scroll.animateScrollTo(scroll.maxValue) }
    // the call is over: let the person read the end of it, then close
    LaunchedEffect(active) { if (!active) { delay(3500); onFinish() } }

    val status = when (CallConversation.uiStatus) {
        0 -> "Connecting…"
        1 -> "Assistant is speaking"
        2 -> "Listening to the caller"
        3 -> "Thinking…"
        else -> "Call ended"
    }

    NcBackground(true) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcChip(if (active) "AI is answering for you" else "Call ended", if (active) NeoCallColors.Ai else NeoCallColors.Muted)
                Spacer(Modifier.weight(1f))
                if (mode != null) NcChip(mode.label + if (until.isNotEmpty()) " until $until" else "", NeoCallColors.Muted)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcAvatar(name, photo, 64.dp, false, false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = NeoCallColors.Text, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (number.isNotBlank() && number != name) Text(number, color = NeoCallColors.Muted, fontSize = 15.sp, style = NcNumbers)
                }
                Text(NcTime(secs), color = NeoCallColors.Text, fontSize = 20.sp, style = NcNumbers)
            }
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(NeoCallColors.Surface.copy(alpha = 0.85f))
                    .verticalScroll(scroll).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (lines.isEmpty()) {
                    Text("The assistant is greeting the caller. Their words will appear here.", color = NeoCallColors.Muted, fontSize = 15.sp)
                }
                // the caller on the left, the assistant on the right
                for (l in lines) NcBubble(l.text, !l.fromCaller)
                if (active && CallConversation.uiStatus == 2 && CallConversation.uiHeard.isNotBlank()) {
                    Text(CallConversation.uiHeard, color = NeoCallColors.Muted, fontSize = 16.sp, fontStyle = FontStyle.Italic)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcWave(
                    Modifier.width(70.dp).height(20.dp),
                    if (CallConversation.uiStatus == 2) NeoCallColors.Accept else NeoCallColors.Ai,
                    active && CallConversation.uiStatus in 1..2
                )
                Spacer(Modifier.width(10.dp))
                Text(status, color = NeoCallColors.Muted, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                if (CallConversation.uiLang.isNotBlank()) NcChip(CallConversation.uiLang, NeoCallColors.Muted)
            }
            Spacer(Modifier.height(16.dp))

            if (active) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(NeoCallColors.Text)
                            .clickable { CallConversation.takeOver(ctx); onFinish() },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Call, null, tint = NeoCallColors.Ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Take over", color = NeoCallColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.weight(1f)) { NcEnd("End call") { CallConversation.hangUpNow() } }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Take over turns the loudspeaker off and stops the assistant, so you can talk to the caller.",
                    color = NeoCallColors.Muted, fontSize = 12.sp
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(NeoCallColors.Surface).clickable { onFinish() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) { Text("Close", color = NeoCallColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

class AiCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NeoLock.callUp++
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContent { NeoAiAnsweringScreen { finish() } }
    }

    override fun onDestroy() {
        NeoLock.callUp = maxOf(0, NeoLock.callUp - 1)
        if (isFinishing) NeoLock.showIfNeeded(applicationContext)
        super.onDestroy()
    }
}
