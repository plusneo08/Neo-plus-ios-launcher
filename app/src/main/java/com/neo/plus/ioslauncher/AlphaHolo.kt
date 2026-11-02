package com.neo.plus.ioslauncher

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.BoxScope
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

private val HoloPurple = Color(0xFF9D5CFF)

/** One full turn (milliseconds). Raise a number to spin that part slower. */
private const val GLOBE_MS = 22000   // the globe
private const val RING_MS = 7000     // the neon ring around the globe / the bottom ring
private const val ORBIT_MS = 40000   // the app rings
private const val PER_RING = 12      // apps on one orbit ring

private val NeonColors = listOf(
    Color(0xFF00F0FF), Color(0xFF2E9BFF), Color(0xFF9D5CFF), Color(0xFFFF3DDC),
    Color(0xFF9D5CFF), Color(0xFF2E9BFF), Color(0xFF00F0FF), Color(0xFF00F0FF)
)
private val NeonCyan = Color(0xFF00F0FF)
private val NeonMagenta = Color(0xFFFF3DDC)
private val NeonGold = Color(0xFFFFC857)

/** Very rough continent outlines, (longitude, latitude) in degrees: enough to read as a world map on a small globe. */
private val Continents: List<FloatArray> = listOf(
    // North America
    floatArrayOf(-168f,66f, -150f,71f, -125f,70f, -95f,73f, -80f,69f, -62f,60f, -56f,52f, -66f,45f, -75f,38f, -81f,31f, -80f,25f, -90f,29f, -97f,26f, -97f,19f, -88f,16f, -83f,9f, -78f,8f, -86f,13f, -95f,16f, -106f,23f, -113f,31f, -118f,34f, -124f,41f, -124f,48f, -135f,58f, -150f,60f, -165f,60f),
    // Greenland
    floatArrayOf(-55f,60f, -45f,60f, -20f,70f, -20f,80f, -45f,83f, -65f,80f, -70f,70f),
    // South America
    floatArrayOf(-78f,8f, -62f,11f, -50f,0f, -35f,-6f, -39f,-15f, -48f,-26f, -58f,-38f, -66f,-47f, -70f,-54f, -75f,-48f, -73f,-35f, -71f,-18f, -81f,-5f),
    // Europe
    floatArrayOf(-10f,36f, -9f,43f, -2f,47f, -5f,50f, 5f,53f, 10f,57f, 5f,62f, 15f,69f, 28f,71f, 40f,66f, 40f,56f, 30f,46f, 28f,41f, 20f,40f, 15f,38f, 8f,44f, 0f,38f),
    // Africa
    floatArrayOf(-17f,21f, -10f,35f, 10f,37f, 22f,32f, 33f,31f, 43f,12f, 51f,12f, 40f,-3f, 40f,-16f, 33f,-26f, 20f,-35f, 15f,-28f, 12f,-15f, 9f,-1f, 9f,4f, -8f,4f, -17f,14f),
    // Asia
    floatArrayOf(28f,41f, 40f,56f, 40f,66f, 70f,73f, 110f,77f, 150f,71f, 180f,68f, 170f,60f, 140f,54f, 140f,45f, 128f,38f, 122f,30f, 108f,21f, 105f,10f, 100f,3f, 98f,16f, 90f,22f, 80f,13f, 73f,18f, 66f,25f, 57f,25f, 50f,30f, 44f,13f, 35f,28f, 35f,36f),
    // India (so it reads on its own)
    floatArrayOf(68f,24f, 73f,20f, 77f,8f, 80f,14f, 88f,22f, 80f,28f, 72f,32f),
    // Australia
    floatArrayOf(114f,-22f, 122f,-18f, 131f,-12f, 142f,-11f, 146f,-19f, 153f,-27f, 148f,-38f, 138f,-35f, 129f,-32f, 115f,-34f),
    // Indonesia / Japan bits
    floatArrayOf(95f,5f, 105f,-6f, 120f,-9f, 140f,-8f, 130f,-1f, 118f,5f, 108f,3f),
    floatArrayOf(130f,32f, 135f,35f, 141f,41f, 143f,44f, 138f,38f, 131f,34f),
    // Antarctica (a thin cap)
    floatArrayOf(-180f,-72f, -90f,-76f, 0f,-72f, 90f,-76f, 180f,-72f, 180f,-90f, -180f,-90f)
)

/**
 * The logo: a sphere showing a turning world map (grid + continents) inside a rotating neon ring, with the NEO PLUS emblem on it.
 * All the animated values are read while drawing, so the spinning never recomposes the screen.
 */
@Composable
private fun LogoGlobe(
    diameter: Dp,
    spin: State<Float>,
    ring: State<Float>,
    hue: State<Float>,
    pulse: State<Float>,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val rOuter = size.minDimension / 2f - 6.dp.toPx()
            val r = rOuter - 14.dp.toPx() // globe radius
            val glow = Color.hsv(hue.value, 1f, 1f)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.50f * pulse.value), NeonColors[2].copy(alpha = 0.16f), Color.Transparent), c, rOuter * 1.3f), rOuter * 1.3f, c)

            // ---- the sphere ----
            drawCircle(Brush.radialGradient(listOf(Color(0xFF1A2A8C), Color(0xFF0A0A3A), Color(0xFF02020F)), c - Offset(r * 0.3f, r * 0.3f), r * 1.5f), r, c)
            val tilt = 18f * PI.toFloat() / 180f
            val lam0 = spin.value * PI.toFloat() / 180f
            // projects lon/lat (deg) -> screen point + depth (z > 0 means the front side)
            fun proj(lon: Float, lat: Float): Triple<Float, Float, Float> {
                val lam = lon * PI.toFloat() / 180f + lam0
                val phi = lat * PI.toFloat() / 180f
                val x = cos(phi) * sin(lam)
                val y0 = sin(phi)
                val z0 = cos(phi) * cos(lam)
                val y = y0 * cos(tilt) - z0 * sin(tilt)
                val z = y0 * sin(tilt) + z0 * cos(tilt)
                return Triple(c.x + x * r, c.y - y * r, z)
            }
            val clip = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
            clipPath(clip) {
                // grid: meridians and parallels
                val gridCol = NeonColors[2].copy(alpha = 0.55f)
                for (lon in -180 until 180 step 30) {
                    val p = Path(); var pen = false
                    for (lat in -90..90 step 6) {
                        val (px, py, pz) = proj(lon.toFloat(), lat.toFloat())
                        if (pz > 0f) { if (!pen) { p.moveTo(px, py); pen = true } else p.lineTo(px, py) } else pen = false
                    }
                    drawPath(p, gridCol, style = Stroke(0.8.dp.toPx()))
                }
                for (lat in -60..60 step 30) {
                    val p = Path(); var pen = false
                    for (lon in -180..180 step 6) {
                        val (px, py, pz) = proj(lon.toFloat(), lat.toFloat())
                        if (pz > 0f) { if (!pen) { p.moveTo(px, py); pen = true } else p.lineTo(px, py) } else pen = false
                    }
                    drawPath(p, gridCol, style = Stroke(0.8.dp.toPx()))
                }
                // continents (points on the far side are pushed onto the rim, the clip cuts the rest)
                Continents.forEach { poly ->
                    val path = Path()
                    var any = false
                    var i = 0
                    while (i < poly.size) {
                        val (px, py, pz) = proj(poly[i], poly[i + 1])
                        var sx = px; var sy = py
                        if (pz > 0f) any = true else {
                            val dx = px - c.x; val dy = py - c.y
                            val len = max(sqrt(dx * dx + dy * dy), 0.001f)
                            sx = c.x + dx / len * r * 1.02f; sy = c.y + dy / len * r * 1.02f
                        }
                        if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                        i += 2
                    }
                    path.close()
                    if (any) {
                        drawPath(path, Brush.linearGradient(listOf(Color(0xFF1AE6A0).copy(alpha = 0.85f), Color(0xFF1FB6FF).copy(alpha = 0.75f)), c - Offset(r, r), c + Offset(r, r)))
                        drawPath(path, NeonCyan, style = Stroke(1.6.dp.toPx()))
                    }
                }
                // light from the upper left, shade on the lower right
                drawCircle(Brush.radialGradient(listOf(NeonCyan.copy(alpha = 0.28f), Color.Transparent), c - Offset(r * 0.45f, r * 0.5f), r * 0.9f), r, c)
                drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), c, r * 1.05f), r, c)
            }
            // atmosphere
            drawCircle(Brush.sweepGradient(NeonColors, c), r, c, alpha = 0.9f, style = Stroke(1.6.dp.toPx()))

            // ---- neon ring round the globe, turning ----
            rotate(ring.value, c) {
                val sweep = Brush.sweepGradient(NeonColors, c)
                drawCircle(sweep, rOuter, c, alpha = 0.22f, style = Stroke(14.dp.toPx()))
                drawCircle(sweep, rOuter, c, alpha = 0.55f, style = Stroke(7.dp.toPx()))
                drawCircle(sweep, rOuter, c, style = Stroke(2.6.dp.toPx()))
            }
            rotate(-ring.value * 1.6f, c) {
                drawCircle(
                    Brush.sweepGradient(NeonColors.reversed(), c), rOuter - 6.dp.toPx(), c, alpha = 0.9f,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 9.dp.toPx())))
                )
            }
        }
        // the NEO PLUS emblem on the globe, turning round its own vertical axis (the back is un-mirrored so it always reads correctly)
        Box(
            Modifier.size(diameter * 0.42f)
                .graphicsLayer {
                    cameraDistance = 14f * density
                    rotationY = spin.value * 2f
                    scaleX = if (cos(spin.value * 2f * PI.toFloat() / 180f) < 0f) -1f else 1f
                    alpha = 0.92f
                }
                .clip(CircleShape)
                .background(Color(0xFF020617).copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.neo_np_logo), "NEO PLUS", Modifier.fillMaxSize(0.92f), contentScale = ContentScale.Fit)
        }
        Box(Modifier.align(Alignment.BottomCenter).offset(y = 5.dp)) { AssistantOrb(26.dp) }
    }
}

/**
 * NEO PLUS holographic home: the NEO PLUS globe in the middle, the steps / calendar / time / weather panels on top,
 * and ALL of the apps travelling round the globe on turning rings, above a turning platform. Tap an app to open it;
 * slide a finger sideways over the stage to spin the rings by hand.
 * (selected / onSelect are kept only so the caller does not change; the A-Z chain is gone.)
 */
@Composable
fun AlphaHoloHome(
    apps: List<AppEntry>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    onSearch: () -> Unit,
    onAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val all = remember(apps) {
        apps.distinctBy { it.pkg + "/" + it.activity }.sortedBy { it.shownLabel.lowercase() }
    }
    val stars = remember {
        val r = java.util.Random(7)
        List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.3f, 0.25f + r.nextFloat() * 0.6f) }
    }
    val dust = remember {
        val r = java.util.Random(3)
        List(190) { floatArrayOf(r.nextFloat() * 2f * PI.toFloat(), Math.sqrt(r.nextDouble()).toFloat(), 1f + r.nextFloat() * 1.9f, r.nextFloat()) }
    }
    val inf = rememberInfiniteTransition(label = "holo")
    val pulseS = inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val bobS = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val spinS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(GLOBE_MS, easing = LinearEasing)), label = "spin")
    val ringS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(RING_MS, easing = LinearEasing)), label = "ring")
    val hueS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "hue")
    val orbitS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(ORBIT_MS, easing = LinearEasing)), label = "orbit")
    val pulse by pulseS
    val handTurn = remember { mutableFloatStateOf(0f) } // degrees added by the finger

    // one tap on the globe: microphone on / off
    fun micToggle() {
        val turnOn = OrbService.instance == null
        val r = PhoneActions.run(ctx, "listen", org.json.JSONObject().put("on", turnOn).put("now", true), apps)
        Toast.makeText(ctx, if (!turnOn) "Microphone off" else if (r.ok) "Microphone on" else r.note, Toast.LENGTH_SHORT).show()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val topH = 212.dp
        val stageTop = topH + 8.dp
        val stageW = maxWidth
        val stageH = maxOf(maxHeight - stageTop - 8.dp, 260.dp)

        // the platform (bottom ring)
        val discRx = stageW * 0.47f
        val discRy = discRx * 0.26f
        val lift = 22.dp
        val discCy = stageH - discRy - 12.dp
        // the globe hovers above the middle of the platform
        val coinD = maxOf(110.dp, minOf(stageW * 0.5f, discCy - 24.dp))
        val coinTop = maxOf(0.dp, discCy - 10.dp - coinD)
        val globeCy = coinTop.value + coinD.value / 2f

        // the app rings: every app gets a place on one of them, rings turn in opposite directions
        val rings = max(1, (all.size + PER_RING - 1) / PER_RING)
        val perRing = if (all.isEmpty()) 1 else (all.size + rings - 1) / rings
        val orbitRx = stageW.value * 0.40f
        val orbitRy = max(min(stageH.value * 0.10f, 44f), 22f)
        val spread = if (rings == 1) 0f else min(46f, (discCy.value - 70f) / (rings - 1).coerceAtLeast(1))
        val firstCy = globeCy - spread * (rings - 1) / 2f

        val swipeMod = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, d -> handTurn.floatValue += d * 0.45f }
        }

        // space background
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF01020C), Color(0xFF0A0B3A), Color(0xFF1A0B4A), Color(0xFF01020A))))
            drawCircle(Brush.radialGradient(listOf(NeonCyan.copy(alpha = 0.16f), Color.Transparent), Offset(size.width * 0.15f, size.height * 0.55f), size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.15f, size.height * 0.55f))
            drawCircle(Brush.radialGradient(listOf(HoloPurple.copy(alpha = 0.22f), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.7f), size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.9f, size.height * 0.7f))
            stars.forEachIndexed { i, s ->
                val col = when (i % 7) { 0 -> NeonCyan; 1 -> HoloPurple; 2 -> NeonGold; 3 -> NeonMagenta; else -> Color.White }
                drawCircle(col.copy(alpha = s[3]), s[2], Offset(s[0] * size.width, s[1] * size.height))
            }
        }

        Box(Modifier.align(Alignment.TopStart).offset(y = stageTop).fillMaxWidth().height(stageH).then(swipeMod)) {
            // ---- the platform with its turning bottom ring ----
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = discCy.toPx()
                val rxp = discRx.toPx()
                val ryp = discRy.toPx()
                val lf = lift.toPx()
                val h = hueS.value
                val turn = ringS.value
                val ang = turn * PI.toFloat() / 180f
                val topRect = Rect(cx - rxp, cy - lf - ryp, cx + rxp, cy - lf + ryp)
                val botRect = Rect(cx - rxp, cy - ryp, cx + rxp, cy + ryp)
                val beam = Path().apply {
                    moveTo(cx - rxp * 0.18f, cy); lineTo(cx + rxp * 0.18f, cy)
                    lineTo(cx + rxp * 0.5f, 0f); lineTo(cx - rxp * 0.5f, 0f); close()
                }
                drawPath(beam, Brush.verticalGradient(listOf(Color.Transparent, Color.hsv(h, 0.8f, 1f).copy(alpha = 0.18f)), 0f, cy))
                val back = Path().apply {
                    moveTo(cx + rxp, cy - lf); arcTo(topRect, 0f, -180f, false)
                    lineTo(cx - rxp, cy); arcTo(botRect, 180f, 180f, false); close()
                }
                drawPath(back, HoloPurple.copy(alpha = 0.07f))
                drawOval(
                    Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.60f * pulse), HoloPurple.copy(alpha = 0.26f), Color.Transparent), Offset(cx, cy), rxp),
                    Offset(cx - rxp * 1.05f, cy - ryp * 1.05f), Size(rxp * 2.1f, ryp * 2.1f)
                )
                // concentric rings: dotted ones crawl round in opposite directions
                for (k in 0..9) {
                    val f = 1f - k * 0.095f
                    val col = lerp(NeoBlue, HoloPurple, k / 9f).copy(alpha = 0.35f + 0.5f * (1f - k / 9f))
                    val eff = if (k % 2 == 1) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()), turn * 3f * (if (k % 4 == 1) 1f else -1f)) else
                        PathEffect.dashPathEffect(floatArrayOf(26.dp.toPx(), 14.dp.toPx()), -turn * 2f * (if (k % 4 == 0) 1f else -1f))
                    drawOval(col, Offset(cx - rxp * f, cy - ryp * f), Size(rxp * 2f * f, ryp * 2f * f), style = Stroke(if (k % 2 == 1) 2.2.dp.toPx() else 1.3.dp.toPx(), pathEffect = eff))
                }
                drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), NeoBlue.copy(alpha = 0.35f), Color.Transparent), Offset(cx, cy), rxp * 0.3f),
                    Offset(cx - rxp * 0.3f, cy - ryp * 0.3f), Size(rxp * 0.6f, ryp * 0.6f))
                dust.forEach { d ->
                    val a = d[0] + ang * (1.5f - d[1])
                    val col = if (d[3] < 0.55f) lerp(NeoBlue, HoloPurple, d[3] / 0.55f) else NeonColors[((d[3] - 0.55f) / 0.45f * 6.99f).toInt().coerceIn(0, 6)]
                    drawCircle(col.copy(alpha = 0.85f), d[2], Offset(cx + cos(a) * d[1] * rxp * 0.95f, cy + sin(a) * d[1] * ryp * 0.95f))
                }
                val front = Path().apply {
                    moveTo(cx + rxp, cy - lf); arcTo(topRect, 0f, 180f, false)
                    lineTo(cx - rxp, cy); arcTo(botRect, 180f, -180f, false); close()
                }
                drawPath(front, HoloPurple.copy(alpha = 0.14f))
                // the rims: the lower one is a colour sweep that turns round the platform
                drawOval(HoloPurple.copy(alpha = 0.9f), Offset(topRect.left, topRect.top), Size(rxp * 2f, ryp * 2f), style = Stroke(1.5.dp.toPx()))
                rotate(turn, Offset(cx, cy)) {
                    // squash the sweep into the oval by drawing it on a scaled canvas
                    scale(1f, ryp / rxp, Offset(cx, cy)) {
                        drawCircle(Brush.sweepGradient(NeonColors, Offset(cx, cy)), rxp, Offset(cx, cy), style = Stroke(3.dp.toPx()))
                    }
                }
                drawLine(HoloPurple.copy(alpha = 0.7f), Offset(cx - rxp, cy - lf), Offset(cx - rxp, cy), 1.4.dp.toPx())
                drawLine(HoloPurple.copy(alpha = 0.7f), Offset(cx + rxp, cy - lf), Offset(cx + rxp, cy), 1.4.dp.toPx())
                // four small data panels travelling round the rim
                for (n in 0..3) {
                    val rad = ang + n * (PI.toFloat() / 2f)
                    val sn = sin(rad)
                    val k = 0.75f + 0.25f * sn
                    val pw = 40.dp.toPx() * k
                    val ph = 30.dp.toPx() * k
                    val px = cx + rxp * cos(rad) - pw / 2f
                    val py = cy + ryp * sn - ph - 2.dp.toPx()
                    drawRoundRect(Color(0xFF0B1230).copy(alpha = 0.88f), Offset(px, py), Size(pw, ph), CornerRadius(5.dp.toPx()))
                    drawRoundRect(Color.hsv((h + n * 90f) % 360f, 0.8f, 1f).copy(alpha = 0.85f), Offset(px, py), Size(pw, ph), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
                    for (bi in 0..3) {
                        val bh = (4 + bi * 4 + (if (n % 2 == 0) 2 else 0)).dp.toPx() * k
                        drawRect(HoloPurple.copy(alpha = 0.95f), Offset(px + 5.dp.toPx() * k + bi * 8.dp.toPx() * k, py + ph - 4.dp.toPx() * k - bh), Size(4.dp.toPx() * k, bh))
                    }
                }
            }

            // ---- the globe: tap = microphone, double tap = chat, long press = search ----
            LogoGlobe(
                coinD, spinS, ringS, hueS, pulseS,
                Modifier.align(Alignment.TopCenter).offset(y = coinTop)
                    .graphicsLayer { translationY = (bobS.value - 0.5f) * 14.dp.toPx() }
                    .semantics { contentDescription = "NEO PLUS globe: tap for microphone, double tap for chat, long press to search" }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); micToggle() },
                            onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAssistant() },
                            onLongPress = { onSearch() }
                        )
                    }
            )

            // ---- all the apps, travelling round the globe on turning rings ----
            all.forEachIndexed { idx, app ->
                key(app.pkg + "/" + app.activity) {
                    val ring = idx / perRing
                    val slot = idx % perRing
                    val inRing = if (ring == rings - 1) all.size - ring * perRing else perRing
                    val dir = if (ring % 2 == 0) 1f else -1f
                    val base = 360f * slot / max(inRing, 1) + ring * 23f
                    val cyDp = firstCy + spread * ring
                    Column(
                        Modifier.align(Alignment.TopStart).width(74.dp)
                            .graphicsLayer {
                                val a = (base + dir * orbitS.value + handTurn.floatValue * dir) * PI.toFloat() / 180f
                                val s = sin(a)             // -1 far side .. 1 near side
                                val near = (s + 1f) / 2f
                                translationX = (stageW.value / 2f + orbitRx * cos(a) - 37f).dp.toPx()
                                translationY = (cyDp + orbitRy * s - 41f).dp.toPx()
                                val sc = 0.62f + 0.38f * near
                                scaleX = sc; scaleY = sc
                                alpha = 0.55f + 0.45f * near
                            }
                            .noRipple {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                ctx.launchAlphaApp(app)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(56.dp).drawBehind {
                                val rr = size.minDimension * 0.9f
                                drawCircle(Brush.radialGradient(listOf(Color.hsv(hueS.value, 1f, 1f).copy(alpha = 0.40f * pulseS.value + 0.1f), Color.Transparent), center, rr), rr)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                app.icon, app.shownLabel,
                                Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).border(1.6.dp, Brush.linearGradient(listOf(NeonCyan, HoloPurple, NeonMagenta)), RoundedCornerShape(13.dp))
                            )
                        }
                        Text(
                            app.shownLabel, color = Color(0xFFBFFBFF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(NeonCyan, Offset.Zero, 14f))
                        )
                    }
                }
            }
        }

        // steps, calendar, time and weather panels on top
        NeoTopPanels(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topH).padding(horizontal = 8.dp))
    }
}
