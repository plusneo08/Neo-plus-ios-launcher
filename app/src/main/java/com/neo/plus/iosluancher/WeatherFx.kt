package com.neo.plus.iosluancher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Seconds since the first frame. Only read inside draw blocks, so it redraws the canvas without recomposing anything. */
@Composable
fun rememberFrameSeconds(): State<Float> = produceState(0f) {
    var start = 0L
    while (true) {
        androidx.compose.runtime.withFrameNanos { n ->
            if (start == 0L) start = n
            value = (n - start) / 1_000_000_000f
        }
    }
}

private class Particle(val x: Float, val y: Float, val speed: Float, val size: Float, val phase: Float)

private fun particles(n: Int, seed: Int): List<Particle> {
    val r = Random(seed)
    return List(n) { Particle(r.nextFloat(), r.nextFloat(), 0.7f + r.nextFloat() * 0.9f, 0.5f + r.nextFloat(), r.nextFloat()) }
}

/** Soft cloud blob used for the drifting sky. */
private fun DrawScope.blob(x: Float, y: Float, w: Float, color: Color) {
    val h = w * 0.42f
    drawRoundRect(color, Offset(x, y + h * 0.40f), Size(w, h * 0.60f), CornerRadius(h * 0.30f))
    drawCircle(color, h * 0.38f, Offset(x + w * 0.28f, y + h * 0.48f))
    drawCircle(color, h * 0.52f, Offset(x + w * 0.54f, y + h * 0.36f))
    drawCircle(color, h * 0.32f, Offset(x + w * 0.76f, y + h * 0.58f))
}

/**
 * Animated sky that lives behind the whole home screen and follows the real weather: drifting clouds, rain, snow, fog,
 * lightning, twinkling stars and a warm sun glow. It is drawn with low opacity so wallpaper and icons stay readable.
 */
@Composable
fun WeatherScene(kind: WKind, modifier: Modifier = Modifier) {
    val t = rememberFrameSeconds()
    val rain = remember { particles(110, 11) }
    val snow = remember { particles(70, 23) }
    val stars = remember { particles(64, 37) }
    val clouds = remember { particles(5, 51) }

    Canvas(modifier.fillMaxSize()) {
        val time = t.value
        val w = size.width
        val h = size.height
        val dp = density

        // overall mood tint
        val tint = when (kind) {
            WKind.RAIN -> Color(0xFF0B2545).copy(alpha = 0.22f)
            WKind.STORM -> Color(0xFF050B1A).copy(alpha = 0.36f)
            WKind.CLOUDY -> Color(0xFF1B2A41).copy(alpha = 0.12f)
            WKind.FOG -> Color(0xFFCBD5E1).copy(alpha = 0.10f)
            WKind.CLEAR_NIGHT, WKind.PARTLY_NIGHT -> Color(0xFF020617).copy(alpha = 0.22f)
            else -> Color.Transparent
        }
        if (tint != Color.Transparent) drawRect(tint)

        // sun glow + slow light sweep
        if (kind == WKind.CLEAR_DAY || kind == WKind.PARTLY_DAY) {
            val pulse = 0.85f + 0.15f * sin(time * 0.9f)
            val c = Offset(w * 0.86f, h * 0.07f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFFE9A8).copy(alpha = 0.55f * pulse), Color(0xFFFFC857).copy(alpha = 0.16f), Color.Transparent), center = c, radius = w * 0.95f),
                w * 0.95f, c
            )
            rotate(time * 4f, c) {
                for (i in 0 until 6) {
                    rotate(i * 60f, c) {
                        drawRect(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f * pulse), Color.Transparent), startY = c.y, endY = c.y + h * 0.7f),
                            Offset(c.x - w * 0.05f, c.y), Size(w * 0.10f, h * 0.7f)
                        )
                    }
                }
            }
        }

        // stars
        if (kind == WKind.CLEAR_NIGHT || kind == WKind.PARTLY_NIGHT) {
            for (s in stars) {
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * (0.8f + s.speed) + s.phase * 2f * PI.toFloat()))
                drawCircle(Color.White.copy(alpha = 0.55f * tw), (0.7f + s.size) * dp, Offset(s.x * w, s.y * h * 0.62f))
            }
        }

        // drifting clouds
        val cloudCount = when (kind) {
            WKind.PARTLY_DAY, WKind.PARTLY_NIGHT -> 2
            WKind.CLOUDY, WKind.RAIN, WKind.STORM, WKind.SNOW -> 5
            WKind.FOG -> 3
            else -> 0
        }
        val cloudColor = when (kind) {
            WKind.STORM -> Color(0xFF334155).copy(alpha = 0.42f)
            WKind.RAIN, WKind.CLOUDY -> Color(0xFF94A3B8).copy(alpha = 0.26f)
            else -> Color.White.copy(alpha = 0.18f)
        }
        for (i in 0 until cloudCount) {
            val c = clouds[i]
            val cw = w * (0.55f + 0.35f * c.size)
            val travel = w + cw
            val x = ((c.x * travel + time * (6f + 5f * c.speed) * dp) % travel) - cw
            blob(x, h * (0.02f + 0.10f * i), cw, cloudColor)
        }

        // fog banks
        if (kind == WKind.FOG) {
            for (i in 0 until 4) {
                val y = h * (0.18f + 0.2f * i) + sin(time * 0.3f + i) * 12f * dp
                val off = sin(time * 0.15f + i * 1.7f) * w * 0.12f
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent), startX = off, endX = w + off),
                    Offset(0f, y), Size(w, h * 0.16f)
                )
            }
        }

        // rain
        if (kind == WKind.RAIN || kind == WKind.STORM) {
            val heavy = kind == WKind.STORM
            val len = 16f * dp
            for (d in rain) {
                val p = (d.y + time * (0.55f + d.speed * 0.5f) * (if (heavy) 1.5f else 1f)) % 1f
                val y = p * (h + len) - len
                val x = d.x * w + y * 0.16f
                drawLine(
                    Color.White.copy(alpha = if (heavy) 0.34f else 0.26f), Offset(x, y), Offset(x - len * 0.16f, y + len),
                    strokeWidth = (0.9f + d.size * 0.5f) * dp, cap = StrokeCap.Round
                )
            }
        }

        // snow
        if (kind == WKind.SNOW) {
            for (f in snow) {
                val p = (f.y + time * 0.045f * f.speed) % 1f
                val x = f.x * w + sin(time * 0.7f + f.phase * 6f) * 14f * dp
                drawCircle(Color.White.copy(alpha = 0.78f), (1.4f + f.size * 1.7f) * dp, Offset(x, p * h))
            }
        }

        // lightning
        if (kind == WKind.STORM) {
            val c = time % 8f
            val flash = when {
                c < 0.10f -> 1f - c / 0.10f
                c in 0.28f..0.40f -> 0.7f * (1f - (c - 0.28f) / 0.12f)
                else -> 0f
            }
            if (flash > 0f) drawRect(Color.White.copy(alpha = 0.30f * flash))
        }
    }
}

/** Watches the cached weather (HomePanel refreshes it) so the sky follows it without sharing state. Falls back to time of day. */
@Composable
fun rememberSkyKind(): WKind {
    val ctx = LocalContext.current.applicationContext
    fun read(): WKind {
        val w = WeatherRepo.cached(ctx)
        if (w != null) return weatherKind(w.code, w.isDay)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 6..18) WKind.CLEAR_DAY else WKind.CLEAR_NIGHT
    }
    var kind by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) {
        while (true) {
            kind = read()
            delay(20_000L)
        }
    }
    return kind
}
