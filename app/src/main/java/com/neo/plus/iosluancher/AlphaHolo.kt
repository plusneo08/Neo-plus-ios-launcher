package com.neo.plus.iosluancher

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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val HoloPurple = Color(0xFF8B5CF6)
private const val PER_PAGE = 8

/** One app floating above the disc. x / y are in dp inside the stage; s = -1 (far side of the orbit) .. 1 (near side). */
private class HoloSlot(val app: AppEntry, val x: Float, val y: Float, val s: Float)

private fun holoSlots(items: List<AppEntry>, cx: Float, cy: Float, rx: Float, ry: Float): List<HoloSlot> {
    val n = items.size
    return items.mapIndexed { i, a ->
        val ang = PI / 2 + 2 * PI * i / n // the first app sits at the front
        HoloSlot(a, cx + rx * cos(ang).toFloat(), cy + ry * sin(ang).toFloat(), sin(ang).toFloat())
    }.sortedBy { it.s }
}

/**
 * NEO PLUS holographic home: the logo hovers over a glowing disc, the A-Z letters sit on a straight chain along the bottom,
 * and the apps of the chosen letter float in an orbit above the disc (no icons are shown until a letter is tapped).
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
    val density = LocalDensity.current
    val buckets = remember(apps) {
        apps.groupBy { alphaKeyOf(it.shownLabel) }
            .mapValues { (_, v) -> v.distinctBy { it.pkg + "/" + it.activity }.sortedBy { it.shownLabel.lowercase() } }
    }
    val chain = remember(buckets) { ('A'..'Z').toList() + (if (buckets['#'].orEmpty().isNotEmpty()) listOf('#') else emptyList()) }
    val stars = remember {
        val r = java.util.Random(7)
        List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.3f, 0.25f + r.nextFloat() * 0.6f) }
    }
    val dust = remember {
        val r = java.util.Random(3)
        List(80) { floatArrayOf(r.nextFloat() * 2f * PI.toFloat(), r.nextFloat(), 1f + r.nextFloat() * 1.8f, r.nextFloat()) }
    }
    val inf = rememberInfiniteTransition(label = "holo")
    val pulse by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val ripple = remember { Animatable(0f) }
    var rippleKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(rippleKey) {
        if (rippleKey > 0) { ripple.snapTo(0f); ripple.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    }
    var page by remember(selected) { mutableIntStateOf(0) }

    fun pick(ch: Char) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        rippleKey++
        onSelect(if (selected == ch) null else ch)
    }
    // one tap on the middle button: microphone on / off
    fun micToggle() {
        val turnOn = OrbService.instance == null
        val r = PhoneActions.run(ctx, "listen", org.json.JSONObject().put("on", turnOn).put("now", true), apps)
        Toast.makeText(ctx, if (!turnOn) "Microphone off" else if (r.ok) "Microphone on" else r.note, Toast.LENGTH_SHORT).show()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val topH = 92.dp
        val chainH = 54.dp
        val infoH = 104.dp
        val stageTop = topH + 8.dp
        val stageW = maxWidth
        val stageH = maxOf(maxHeight - stageTop - chainH - infoH - 20.dp, 230.dp)

        // disc geometry (dp, inside the stage)
        val discRx = stageW * 0.47f
        val discRy = discRx * 0.2f
        val discCy = stageH - discRy - 10.dp
        val orbitRx = stageW.value * 0.34f
        val orbitRy = 58f
        val orbitCy = discCy.value - 96f

        val shown = if (selected != null) buckets[selected].orEmpty() else emptyList()
        val pages = maxOf(1, (shown.size + PER_PAGE - 1) / PER_PAGE)
        val pg = page.coerceIn(0, pages - 1)
        val slots = remember(shown, pg, stageW) {
            holoSlots(shown.drop(pg * PER_PAGE).take(PER_PAGE), stageW.value / 2f, orbitCy, orbitRx, orbitRy)
        }

        // space background
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF00020F), Color(0xFF05103A), Color(0xFF000308))))
            stars.forEach { s -> drawCircle(Color.White.copy(alpha = s[3]), s[2], Offset(s[0] * size.width, s[1] * size.height)) }
        }

        // the stage: beam, disc, floating logo or apps
        Box(Modifier.align(Alignment.TopStart).offset(y = stageTop).fillMaxWidth().height(stageH)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = discCy.toPx()
                val rxp = discRx.toPx()
                val ryp = discRy.toPx()
                // light beam rising from the disc
                val beam = Path().apply {
                    moveTo(cx - rxp * 0.2f, cy); lineTo(cx + rxp * 0.2f, cy)
                    lineTo(cx + rxp * 0.62f, 0f); lineTo(cx - rxp * 0.62f, 0f); close()
                }
                drawPath(beam, Brush.verticalGradient(listOf(Color.Transparent, NeoBlue.copy(alpha = if (selected == null) 0.20f else 0.12f)), 0f, cy))
                // glow + rings of the disc
                drawOval(
                    Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.55f * pulse), HoloPurple.copy(alpha = 0.22f), Color.Transparent), Offset(cx, cy), rxp),
                    Offset(cx - rxp * 1.05f, cy - ryp * 1.05f), Size(rxp * 2.1f, ryp * 2.1f)
                )
                for (k in 0..6) {
                    val f = 1f - k * 0.14f
                    drawOval(
                        lerp(NeoBlue, HoloPurple, k / 6f).copy(alpha = 0.30f + 0.45f * (1f - k / 6f)),
                        Offset(cx - rxp * f, cy - ryp * f), Size(rxp * 2f * f, ryp * 2f * f), style = Stroke(1.2.dp.toPx())
                    )
                }
                // glass rim
                val lift = 18.dp.toPx()
                drawOval(HoloPurple.copy(alpha = 0.85f), Offset(cx - rxp * 1.02f, cy - ryp * 1.02f - lift), Size(rxp * 2.04f, ryp * 2.04f), style = Stroke(1.6.dp.toPx()))
                drawOval(NeoBlue.copy(alpha = 0.8f), Offset(cx - rxp * 1.02f, cy - ryp * 1.02f), Size(rxp * 2.04f, ryp * 2.04f), style = Stroke(2.dp.toPx()))
                drawLine(HoloPurple.copy(alpha = 0.6f), Offset(cx - rxp * 1.02f, cy - lift), Offset(cx - rxp * 1.02f, cy), 1.4.dp.toPx())
                drawLine(HoloPurple.copy(alpha = 0.6f), Offset(cx + rxp * 1.02f, cy - lift), Offset(cx + rxp * 1.02f, cy), 1.4.dp.toPx())
                // data dust on the disc
                dust.forEach { d ->
                    val a = d[0]
                    val r = d[1]
                    drawCircle(lerp(NeoBlue, Color(0xFFFFC857), d[3]).copy(alpha = 0.75f), d[2], Offset(cx + cos(a) * r * rxp * 0.95f, cy + sin(a) * r * ryp * 0.95f))
                }
                // two small holographic panels on the rim
                for (side in listOf(-1f, 1f)) {
                    val px = cx + side * rxp * 0.86f - 23.dp.toPx()
                    val py = cy - ryp * 0.5f - 34.dp.toPx()
                    drawRoundRect(Color(0xFF0B1230).copy(alpha = 0.85f), Offset(px, py), Size(46.dp.toPx(), 34.dp.toPx()), CornerRadius(6.dp.toPx()))
                    drawRoundRect(NeoBlue.copy(alpha = 0.7f), Offset(px, py), Size(46.dp.toPx(), 34.dp.toPx()), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
                    for (b in 0..3) {
                        val bh = (6 + b * 5 + (if (side > 0) 3 else 0)).dp.toPx()
                        drawRect(HoloPurple.copy(alpha = 0.9f), Offset(px + 6.dp.toPx() + b * 9.dp.toPx(), py + 28.dp.toPx() - bh), Size(5.dp.toPx(), bh))
                    }
                }
                // shadows of the floating apps on the disc
                slots.forEach { sl ->
                    drawOval(
                        NeoBlue.copy(alpha = 0.25f + 0.2f * pulse),
                        Offset(sl.x.dp.toPx() - 22.dp.toPx(), (discCy.value + discRy.value * 0.5f * sl.s).dp.toPx() - 6.dp.toPx()),
                        Size(44.dp.toPx(), 12.dp.toPx())
                    )
                }
                val r = ripple.value
                if (r > 0f && r < 1f) {
                    drawOval(NeoBlue.copy(alpha = (1f - r) * 0.8f), Offset(cx - rxp * r, cy - ryp * r), Size(rxp * 2f * r, ryp * 2f * r), style = Stroke(3.dp.toPx() * (1f - r) + 1f))
                }
            }

            // idle: the logo hovers over the disc, the assistant button sits in its middle
            AnimatedVisibility(
                selected == null, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.85f), exit = fadeOut(tween(200))
            ) {
                val logoW = minOf(stageW * 0.88f, (stageH - discRy * 2 - 28.dp) / 0.9f)
                Box(Modifier.padding(top = 6.dp).offset(y = (bob * 10f).dp).size(logoW, logoW * 0.9f), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.neo_np_logo), "NEO PLUS", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Box(
                        Modifier.size(logoW * 0.2f)
                            .drawBehind {
                                val rr = size.minDimension * 0.85f
                                drawCircle(Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.35f + 0.3f * pulse), Color.Transparent), center, rr), rr)
                            }
                            .semantics { contentDescription = "Assistant: tap for microphone, double tap for chat" }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); micToggle() },
                                    onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAssistant() },
                                    onLongPress = { onSearch() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) { AssistantOrb(logoW * 0.17f) }
                }
            }

            // a letter is chosen: its apps float in an orbit over the disc (swipe sideways for more pages)
            AnimatedVisibility(
                selected != null,
                enter = fadeIn(tween(300)) + scaleIn(tween(350), initialScale = 0.6f), exit = fadeOut(tween(150))
            ) {
                Box(
                    Modifier.fillMaxSize().pointerInput(selected, pages) {
                        var drag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (drag < -60f && page < pages - 1) page++
                                else if (drag > 60f && page > 0) page--
                            }
                        ) { _, d -> drag += d }
                    }
                ) {
                    slots.forEachIndexed { idx, sl ->
                        key(sl.app.pkg + "/" + sl.app.activity) {
                            val near = (sl.s + 1f) / 2f
                            val scale = 0.78f + 0.22f * near
                            val dy = sin((bob + idx * 0.17f) * 2f * PI.toFloat()) * 4f
                            Column(
                                Modifier.align(Alignment.TopStart).offset(x = (sl.x - 37f).dp, y = (sl.y - 41f + dy).dp).width(74.dp)
                                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = 0.72f + 0.28f * near)
                                    .noRipple {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        ctx.launchAlphaApp(sl.app)
                                        onSelect(null)
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.size(56.dp)
                                        .drawBehind {
                                            val rr = size.minDimension * 0.9f
                                            drawCircle(Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.45f * pulse + 0.1f), Color.Transparent), center, rr), rr)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        sl.app.icon, sl.app.shownLabel,
                                        Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, NeoBlue.copy(alpha = 0.85f), RoundedCornerShape(13.dp))
                                    )
                                }
                                Text(
                                    sl.app.shownLabel, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            if (selected == '#') "No apps start with a number or symbol" else "No apps start with $selected",
                            color = NeoSilver.copy(alpha = 0.75f), fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp)
                        )
                    }
                    if (pages > 1) {
                        Row(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            for (i in 0 until pages) {
                                Box(
                                    Modifier.size(if (i == pg) 10.dp else 7.dp).clip(CircleShape)
                                        .background(if (i == pg) NeoBlue else NeoSilver.copy(alpha = 0.35f)).clickable { page = i }
                                )
                            }
                        }
                    }
                }
            }
        }

        // clock + weather on top
        NeoTopStrip(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topH).padding(horizontal = 8.dp))

        // under the stage: steps when idle, the chosen letter and a close button otherwise
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = chainH + 10.dp).fillMaxWidth().height(infoH), contentAlignment = Alignment.Center) {
            if (selected == null) {
                NeoStepsTile(Modifier.size(96.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.toString(), color = NeoBlue, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("  ·  ${shown.size} ${if (shown.size == 1) "app" else "apps"}", color = NeoSilver.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                    Box(
                        Modifier.padding(top = 8.dp).size(36.dp).clip(CircleShape).background(NeoBlue.copy(alpha = 0.18f))
                            .border(1.dp, NeoBlue.copy(alpha = 0.8f), CircleShape)
                            .semantics { contentDescription = "Close" }.noRipple { onSelect(null) },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = NeoSilver, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // the A-Z chain along the bottom (slide it sideways)
        val scroll = rememberScrollState()
        LaunchedEffect(selected) {
            val i = chain.indexOf(selected ?: return@LaunchedEffect)
            if (i >= 0) scroll.animateScrollTo(with(density) { (48.dp * i + 19.dp - stageW / 2).toPx() }.toInt().coerceAtLeast(0))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(chainH).padding(bottom = 8.dp)
                .horizontalScroll(scroll).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            chain.forEachIndexed { i, ch ->
                LetterButton(ch, 38.dp, selected == ch, buckets[ch]?.size ?: 0, pulse) { pick(ch) }
                if (i < chain.lastIndex) {
                    Box(
                        Modifier.width(10.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(NeoBlue.copy(alpha = 0.9f), HoloPurple.copy(alpha = 0.9f))))
                    )
                }
            }
        }
    }
}
