package com.neo.plus.iosluancher

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/*
 * Small building blocks for the "AI answers your call" screen (AiCallScreen.kt).
 * The extra colors are extension properties, so they sit next to NeoCallColors (NeoCallUi.kt) without editing it.
 */
val NeoCallColors.Ai: Color get() = Color(0xFFBF5AF2)
val NeoCallColors.Muted: Color get() = Color(0xFF8E9AAB)
val NeoCallColors.Accept: Color get() = Color(0xFF22C55E)
val NeoCallColors.Ink: Color get() = Color(0xFF05101F)
val NeoCallColors.Surface: Color get() = Color(0xFF0E1A2B)
val NeoCallColors.Text: Color get() = Color.White

/** Digits of equal width, so a running clock does not jiggle. */
val NcNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Call length as m:ss (h:mm:ss after an hour). */
fun NcTime(secs: Int): String {
    val s = secs.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** Dark full-screen backdrop; [ai] tints it purple while the assistant is answering. */
@Composable
fun NcBackground(ai: Boolean, content: @Composable () -> Unit) {
    val top = if (ai) Color(0xFF1B1033) else Color(0xFF071A33)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Color(0xFF02060D))))) {
        content()
    }
}

/** Small rounded label. */
@Composable
fun NcChip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Round contact picture, or the first letter of the name when there is no photo. [pulse] draws a soft ring; [ai] colors it. */
@Composable
fun NcAvatar(name: String, photo: ImageBitmap?, diameter: Dp, pulse: Boolean, ai: Boolean) {
    val ring = if (ai) NeoCallColors.Ai else NeoCallColors.Blue
    val t = rememberInfiniteTransition(label = "avatar")
    val glow by t.animateFloat(
        0.25f, 0.9f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val ringAlpha = if (pulse) glow else 0.5f
    Box(
        Modifier.size(diameter).clip(CircleShape).background(NeoCallColors.Surface)
            .border(2.dp, ring.copy(alpha = ringAlpha), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(photo, null, Modifier.size(diameter).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            Text(letter, color = NeoCallColors.Text, fontSize = (diameter.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** One line of the conversation. [mine] = the assistant's reply (right, tinted); otherwise the caller (left). */
@Composable
fun NcBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text, color = NeoCallColors.Text, fontSize = 16.sp,
            modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp))
                .background(if (mine) NeoCallColors.Ai.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Little sound bars; they move while [animate] is true. */
@Composable
fun NcWave(modifier: Modifier, color: Color, animate: Boolean) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier) {
        val n = 7
        val gap = size.width / n
        val bw = gap * 0.5f
        for (i in 0 until n) {
            val amp = if (animate) 0.3f + 0.7f * (0.5f + 0.5f * sin(phase + i * 0.9f)) else 0.2f
            val h = size.height * amp
            drawRoundRect(
                color, Offset(i * gap + (gap - bw) / 2f, (size.height - h) / 2f), Size(bw, h), CornerRadius(bw / 2f)
            )
        }
    }
}

/** Red "end call" pill; fills the width it is given. */
@Composable
fun NcEnd(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(NeoCallColors.Red).clickable { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
