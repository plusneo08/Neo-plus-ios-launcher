package com.neo.plus.iosluancher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class IosKind { PHONE, MESSAGES, CAMERA, SAFARI, CHROME, PLAY, RECORDER }

/** Decides whether an installed app gets a hand-drawn iPhone-style icon. */
fun iosKindOf(pkg: String, label: String): IosKind? {
    val p = pkg.lowercase()
    val l = label.lowercase().trim()
    return when {
        p == "com.android.chrome" || l == "chrome" -> IosKind.CHROME
        p == "com.android.vending" || l == "play store" -> IosKind.PLAY
        "screen" in l || "screen" in p -> null
        "recorder" in p || "voicenote" in p || "recorder" in l || "voice memo" in l -> IosKind.RECORDER
        "dialer" in p || l == "phone" -> IosKind.PHONE
        "messaging" in p || p.endsWith(".mms") || ".mms." in p || l == "messages" || l == "messaging" -> IosKind.MESSAGES
        "camera" in p || l == "camera" -> IosKind.CAMERA
        else -> null
    }
}

private val GreenTop = Color(0xFF6BEA7E)
private val GreenBottom = Color(0xFF1FBF47)

@Composable
fun IosArt(kind: IosKind, modifier: Modifier = Modifier) {
    when (kind) {
        IosKind.PHONE -> Box(
            modifier.background(Brush.verticalGradient(listOf(GreenTop, GreenBottom))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.58f)) }

        IosKind.MESSAGES -> Canvas(modifier.background(Brush.verticalGradient(listOf(GreenTop, GreenBottom)))) {
            val w = size.width
            val h = size.height
            drawOval(Color.White, Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.54f))
            val tail = Path().apply {
                moveTo(w * 0.26f, h * 0.60f); lineTo(w * 0.19f, h * 0.80f); lineTo(w * 0.44f, h * 0.70f); close()
            }
            drawPath(tail, Color.White)
        }

        IosKind.CAMERA -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFDCDCE0), Color(0xFF8E8E93))))) {
            val w = size.width
            val h = size.height
            val dark = Color(0xFF1C1C1E)
            drawRoundRect(dark, Offset(w * 0.30f, h * 0.24f), Size(w * 0.22f, h * 0.12f), CornerRadius(w * 0.03f))
            drawRoundRect(dark, Offset(w * 0.14f, h * 0.31f), Size(w * 0.72f, h * 0.44f), CornerRadius(w * 0.09f))
            val c = Offset(w * 0.5f, h * 0.53f)
            drawCircle(Color(0xFF48484A), w * 0.175f, c)
            drawCircle(Color(0xFF0A0A0C), w * 0.13f, c)
            drawCircle(Color(0xFF3B5B9A).copy(alpha = 0.75f), w * 0.045f, Offset(w * 0.46f, h * 0.49f))
            drawCircle(Color(0xFFFFD60A), w * 0.025f, Offset(w * 0.77f, h * 0.39f))
        }

        IosKind.SAFARI -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFDCE5F2))))) {
            val w = size.width
            val c = Offset(w / 2f, size.height / 2f)
            val r = w * 0.385f
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF63CCFF), Color(0xFF0A5FE6))), r, c)
            drawCircle(Color.White.copy(alpha = 0.9f), r, c, style = Stroke(w * 0.012f))
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0)
                val major = i % 5 == 0
                val r1 = r * 0.93f
                val r2 = r1 - w * (if (major) 0.045f else 0.022f)
                drawLine(
                    Color.White.copy(alpha = if (major) 0.95f else 0.65f),
                    Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                    Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                    strokeWidth = w * (if (major) 0.012f else 0.006f)
                )
            }
            rotate(45f, c) {
                val needle = r * 0.72f
                val half = r * 0.17f
                drawPath(Path().apply {
                    moveTo(c.x, c.y - needle); lineTo(c.x - half, c.y); lineTo(c.x + half, c.y); close()
                }, Color(0xFFFF3B30))
                drawPath(Path().apply {
                    moveTo(c.x, c.y + needle); lineTo(c.x - half, c.y); lineTo(c.x + half, c.y); close()
                }, Color(0xFFF2F2F7))
            }
            drawCircle(Color.White, w * 0.018f, c)
        }

        IosKind.CHROME -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFFAFAFA), Color(0xFFE3E3E8))))) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width * 0.34f
            val tl = Offset(cx - r, cy - r)
            val sz = Size(r * 2, r * 2)
            drawArc(Color(0xFFEA4335), 210f, 120f, true, tl, sz)
            drawArc(Color(0xFFFBBC05), 330f, 120f, true, tl, sz)
            drawArc(Color(0xFF34A853), 90f, 120f, true, tl, sz)
            drawCircle(Color.White, size.width * 0.19f, Offset(cx, cy))
            drawCircle(Color(0xFF4285F4), size.width * 0.145f, Offset(cx, cy))
        }

        IosKind.PLAY -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEDEDF0))))) {
            val w = size.width
            val h = size.height
            fun p(x: Float, y: Float) = Offset(w * x, h * y)
            fun tri(a: Offset, b: Offset, c: Offset, col: Color) =
                drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, col)
            val a = p(0.31f, 0.20f)
            val b = p(0.31f, 0.80f)
            val m = p(0.46f, 0.50f)
            val pt = p(0.54f, 0.35f)
            val pb = p(0.54f, 0.65f)
            val tip = p(0.76f, 0.50f)
            tri(a, b, m, Color(0xFF00A0FF))
            tri(a, pt, m, Color(0xFF00F076))
            tri(b, pb, m, Color(0xFFFF3A44))
            drawPath(Path().apply {
                moveTo(m.x, m.y); lineTo(pt.x, pt.y); lineTo(tip.x, tip.y); lineTo(pb.x, pb.y); close()
            }, Color(0xFFFFE000))
        }

        IosKind.RECORDER -> Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF000000))))) {
            val w = size.width
            val h = size.height
            val bars = floatArrayOf(0.10f, 0.20f, 0.32f, 0.48f, 0.0f, 0.40f, 0.26f, 0.16f, 0.08f)
            val bw = w * 0.045f
            val step = w * 0.085f
            val start = w * 0.5f - step * (bars.size - 1) / 2f
            bars.forEachIndexed { i, f ->
                val x = start + step * i
                if (i == 4) {
                    drawRoundRect(Color(0xFFFF453A), Offset(x - bw / 2, h * 0.18f), Size(bw, h * 0.64f), CornerRadius(bw / 2))
                } else {
                    val bh = h * f
                    drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(x - bw / 2, h * 0.5f - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
                }
            }
        }
    }
}

/** Home-screen / dock / library icon: iPhone art for known apps, full-bleed squircle for the rest, plus a glass sheen. */
@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val kind = remember(app.pkg, app.label, app.role) {
        when (app.role) {
            Role.PHONE -> IosKind.PHONE
            Role.MESSAGES -> IosKind.MESSAGES
            Role.SAFARI -> IosKind.SAFARI
            Role.CAMERA -> IosKind.CAMERA
            null -> iosKindOf(app.pkg, app.label)
        }
    }
    Box(modifier.size(size).shadow(5.dp, Squircle, clip = false).clip(Squircle)) {
        if (kind != null) IosArt(kind, Modifier.fillMaxSize())
        else Image(app.icon, app.shownLabel, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent, Color.Black.copy(alpha = 0.05f)))
            )
        )
        Box(Modifier.fillMaxSize().border(0.8.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f))), Squircle))
    }
}
