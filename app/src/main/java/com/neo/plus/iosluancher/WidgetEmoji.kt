package com.neo.plus.iosluancher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/* ------------------------------------------------------------------ */
/*  Animated "action" emojis for the widgets                           */
/*  Every motion runs in the graphics layer, so nothing recomposes.    */
/* ------------------------------------------------------------------ */

enum class Move {
    BOB,     // hops up and down
    SPIN,    // turns round and round
    SHAKE,   // rings: shakes in bursts, then rests
    FLIP,    // flips over, pauses, flips again
    PULSE,   // grows and shrinks while glowing
    SWAY,    // swings like a pendulum
    FLASH,   // flashes on and off like a spark
    FALL,    // falls straight down and fades out
    SNOW,    // falls while turning and drifting side to side
    TRAVEL   // glides across (left to right, or right to left with reverse)
}

/** One emoji that keeps moving the way [move] says. [travel] is how far it goes (height, width or hop size). */
@Composable
fun ActionEmoji(
    emoji: String,
    move: Move,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    periodMs: Int = 1200,
    phase: Float = 0f,
    travel: Dp = 6.dp,
    reverse: Boolean = false
) {
    val t = rememberInfiniteTransition(label = "actionEmoji")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMs, easing = LinearEasing), RepeatMode.Restart,
            StartOffset((phase.coerceIn(0f, 1f) * periodMs).toInt())
        ),
        label = "p"
    )
    Text(
        emoji, fontSize = size, maxLines = 1, softWrap = false,
        modifier = modifier.graphicsLayer {
            val tau = (2.0 * PI).toFloat()
            val s = sin(tau * p)
            val tr = travel.toPx()
            val fade = minOf(1f, p / 0.12f, (1f - p) / 0.2f).coerceIn(0f, 1f)
            when (move) {
                Move.BOB -> translationY = -tr * abs(s)
                Move.SPIN -> rotationZ = 360f * p
                Move.SHAKE -> if (p < 0.45f) {
                    rotationZ = 16f * sin(tau * p * 10f)
                    scaleX = 1.12f; scaleY = 1.12f
                }
                Move.FLIP -> {
                    cameraDistance = 12f * density
                    rotationX = 360f * minOf(1f, p / 0.6f)
                }
                Move.PULSE -> {
                    val k = (s + 1f) / 2f
                    scaleX = 0.85f + 0.4f * k; scaleY = scaleX
                    alpha = 0.6f + 0.4f * k
                }
                Move.SWAY -> {
                    transformOrigin = TransformOrigin(0.5f, 0.1f)
                    rotationZ = 14f * s
                }
                Move.FLASH -> {
                    val on = p < 0.12f || (p > 0.2f && p < 0.3f)
                    alpha = if (on) 1f else 0.18f
                    scaleX = if (on) 1.25f else 0.9f; scaleY = scaleX
                }
                Move.FALL -> {
                    translationY = p * tr
                    alpha = fade
                }
                Move.SNOW -> {
                    translationY = p * tr
                    translationX = 6f * density * sin(tau * p * 2f)
                    rotationZ = 360f * p
                    alpha = fade
                }
                Move.TRAVEL -> {
                    translationX = (if (reverse) 1f - p else p) * tr
                    translationY = -3f * density * sin(tau * p * 3f)
                    alpha = fade
                }
            }
        }
    )
}

/** An emoji that changes frame by frame, like a flip-book (hands of a clock going round, a person walking). */
@Composable
fun FrameEmoji(frames: List<String>, intervalMs: Long, modifier: Modifier = Modifier, size: TextUnit = 16.sp) {
    val i by produceState(0) {
        while (true) {
            delay(intervalMs)
            value = (value + 1) % frames.size
        }
    }
    Box(modifier.width((size.value * 1.4f).dp), contentAlignment = Alignment.Center) {
        Text(frames[i % frames.size], fontSize = size, maxLines = 1, softWrap = false)
    }
}

/** 12 clock faces: played quickly, the hands of the 🕐 go round. */
val CLOCK_FACES = listOf("🕐", "🕑", "🕒", "🕓", "🕔", "🕕", "🕖", "🕗", "🕘", "🕙", "🕚", "🕛")

/** Walking steps for the step counter. */
val WALK_FRAMES = listOf("👣", "🚶", "🚶‍♂️", "🏃", "🏃‍♂️", "🚶")

/* ------------------------------------------------------------------ */
/*  Weather card: emojis that act out the current weather              */
/* ------------------------------------------------------------------ */

private class Fx(
    val e: String, val x: Float, val move: Move, val period: Int,
    val phase: Float, val size: Float = 14f, val y: Float = 0f
)

private fun fxFor(kind: WKind): List<Fx> = when (kind) {
    WKind.CLEAR_DAY -> listOf(
        Fx("✨", 0.46f, Move.PULSE, 1400, 0.0f, 14f, 0.10f),
        Fx("✨", 0.70f, Move.PULSE, 1700, 0.4f, 12f, 0.48f),
        Fx("✨", 0.90f, Move.PULSE, 1500, 0.7f, 14f, 0.18f),
        Fx("🦋", 0.00f, Move.TRAVEL, 9000, 0.2f, 16f, 0.18f)
    )
    WKind.CLEAR_NIGHT -> listOf(
        Fx("⭐", 0.46f, Move.PULSE, 1500, 0.0f, 12f, 0.10f),
        Fx("⭐", 0.72f, Move.PULSE, 1900, 0.5f, 10f, 0.50f),
        Fx("✨", 0.90f, Move.PULSE, 1300, 0.8f, 14f, 0.16f),
        Fx("🌠", 0.00f, Move.TRAVEL, 5200, 0.1f, 16f, 0.08f)
    )
    WKind.PARTLY_DAY -> listOf(
        Fx("☁️", 0.00f, Move.TRAVEL, 11000, 0.0f, 18f, 0.10f),
        Fx("☁️", 0.00f, Move.TRAVEL, 15000, 0.5f, 14f, 0.42f),
        Fx("✨", 0.90f, Move.PULSE, 1500, 0.3f, 13f, 0.16f)
    )
    WKind.PARTLY_NIGHT -> listOf(
        Fx("☁️", 0.00f, Move.TRAVEL, 12000, 0.0f, 18f, 0.10f),
        Fx("☁️", 0.00f, Move.TRAVEL, 16000, 0.55f, 14f, 0.44f),
        Fx("⭐", 0.88f, Move.PULSE, 1600, 0.2f, 11f, 0.14f)
    )
    WKind.CLOUDY -> listOf(
        Fx("☁️", 0.00f, Move.TRAVEL, 10000, 0.0f, 18f, 0.08f),
        Fx("☁️", 0.00f, Move.TRAVEL, 14000, 0.4f, 14f, 0.36f),
        Fx("💨", 0.00f, Move.TRAVEL, 6500, 0.7f, 14f, 0.52f)
    )
    WKind.FOG -> listOf(
        Fx("🌫️", 0.00f, Move.TRAVEL, 13000, 0.0f, 18f, 0.10f),
        Fx("🌫️", 0.00f, Move.TRAVEL, 17000, 0.5f, 16f, 0.40f),
        Fx("💨", 0.00f, Move.TRAVEL, 7000, 0.8f, 13f, 0.55f)
    )
    WKind.RAIN -> listOf(
        Fx("💧", 0.44f, Move.FALL, 1300, 0.00f, 12f),
        Fx("💧", 0.55f, Move.FALL, 1100, 0.35f, 11f),
        Fx("💧", 0.66f, Move.FALL, 1500, 0.70f, 12f),
        Fx("💧", 0.77f, Move.FALL, 1200, 0.15f, 11f),
        Fx("💧", 0.88f, Move.FALL, 1400, 0.55f, 12f),
        Fx("💧", 0.96f, Move.FALL, 1000, 0.85f, 10f)
    )
    WKind.SNOW -> listOf(
        Fx("❄️", 0.44f, Move.SNOW, 3200, 0.00f, 13f),
        Fx("❄️", 0.58f, Move.SNOW, 2800, 0.40f, 11f),
        Fx("❄️", 0.72f, Move.SNOW, 3500, 0.75f, 14f),
        Fx("❄️", 0.84f, Move.SNOW, 3000, 0.20f, 10f),
        Fx("❄️", 0.94f, Move.SNOW, 3300, 0.55f, 12f)
    )
    WKind.STORM -> listOf(
        Fx("⚡", 0.50f, Move.FLASH, 2200, 0.0f, 20f, 0.08f),
        Fx("⚡", 0.80f, Move.FLASH, 3100, 0.6f, 16f, 0.22f),
        Fx("💧", 0.62f, Move.FALL, 1100, 0.20f, 11f),
        Fx("💧", 0.72f, Move.FALL, 1300, 0.60f, 11f),
        Fx("💧", 0.92f, Move.FALL, 1200, 0.90f, 11f)
    )
}

/** Lays small moving emojis over the weather card: rain falls, clouds drift, stars twinkle, lightning flashes. */
@Composable
fun WeatherEmojis(kind: WKind, modifier: Modifier = Modifier) {
    val items = remember(kind) { fxFor(kind) }
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = maxWidth
        val h = maxHeight
        items.forEach { f ->
            val travel = when (f.move) {
                Move.TRAVEL -> w
                Move.FALL, Move.SNOW -> h * 0.85f
                else -> 6.dp
            }
            ActionEmoji(
                f.e, f.move, Modifier.offset(x = w * f.x, y = h * f.y),
                size = f.size.sp, periodMs = f.period, phase = f.phase, travel = travel
            )
        }
    }
}
