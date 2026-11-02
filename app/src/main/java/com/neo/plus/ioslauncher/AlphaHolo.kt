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
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val HoloPurple = Color(0xFF8B5CF6)
private const val PER_PAGE = 8

/** One full turn of the logo disc, and one turn of its neon ring (milliseconds). Raise them to spin slower. */
private const val SPIN_MS = 9000
private const val RING_MS = 6000

/** The neon palette of the disc: cyan, blue, violet, magenta, orange, yellow, lime, and back to cyan. */
private val NeonColors = listOf(
    Color(0xFF00F0FF), Color(0xFF1AA7FF), Color(0xFFA855F7), Color(0xFFFF2BD6),
    Color(0xFFFF6A00), Color(0xFFFFE600), Color(0xFF39FF14), Color(0xFF00F0FF)
)

/** One app floating around the disc. x / y are in dp inside the stage; s = -1 (far side of the orbit) .. 1 (near side). */
private class HoloSlot(val app: AppEntry, val x: Float, val y: Float, val s: Float)

private fun holoSlots(items: List<AppEntry>, cx: Float, cy: Float, rx: Float, ry: Float): List<HoloSlot> {
    val n = items.size
    return items.mapIndexed { i, a ->
        val ang = PI / 2 + 2 * PI * i / n // the first app sits at the front
        HoloSlot(a, cx + rx * cos(ang).toFloat(), cy + ry * sin(ang).toFloat(), sin(ang).toFloat())
    }.sortedBy { it.s }
}

/**
 * The disc: the NEO PLUS logo itself, turning like a coin inside a rotating neon ring whose colours keep cycling.
 * All the animated values are read while drawing, so the spinning never recomposes the screen.
 */
@Composable
private fun LogoDisc(
    diameter: Dp,
    spin: State<Float>,
    ring: State<Float>,
    hue: State<Float>,
    pulse: State<Float>,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val r = this.size.minDimension / 2f - 8.dp.toPx()
            val glow = Color.hsv(hue.value, 1f, 1f)
            // colour-cycling halo behind the disc
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.55f * pulse.value), NeonColors[2].copy(alpha = 0.18f), Color.Transparent), c, r * 1.3f),
                r * 1.3f, c
            )
            // the neon light it throws on the floor
            drawOval(glow.copy(alpha = 0.32f), Offset(c.x - r * 0.7f, c.y + r * 1.04f), Size(r * 1.4f, r * 0.16f))
            // neon ring, turning
            rotate(ring.value, c) {
                val sweep = Brush.sweepGradient(NeonColors, c)
                drawCircle(sweep, r, c, alpha = 0.22f, style = Stroke(16.dp.toPx()))
                drawCircle(sweep, r, c, alpha = 0.55f, style = Stroke(8.dp.toPx()))
                drawCircle(sweep, r, c, style = Stroke(3.dp.toPx()))
            }
            // thin dashed ring turning the other way
            rotate(-ring.value * 1.6f, c) {
                drawCircle(
                    Brush.sweepGradient(NeonColors.reversed(), c), r - 7.dp.toPx(), c, alpha = 0.9f,
                    style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())))
                )
            }
        }
        // the logo, turning around its own vertical axis (the back is un-mirrored so the lettering always reads correctly)
        Box(
            Modifier.size(diameter - 30.dp)
                .graphicsLayer {
                    cameraDistance = 14f * density
                    rotationY = spin.value
                    scaleX = if (cos(spin.value * PI.toFloat() / 180f) < 0f) -1f else 1f
                }
                .clip(CircleShape)
                .background(Color(0xFF020617)),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.neo_np_logo), "NEO PLUS", Modifier.fillMaxSize(0.92f), contentScale = ContentScale.Fit)
        }
        // the assistant gem sits on the rim of the ring
        Box(Modifier.align(Alignment.BottomCenter).offset(y = 5.dp)) { AssistantOrb(26.dp) }
    }
}

/**
 * NEO PLUS holographic home: the spinning neon NEO PLUS logo disc in the middle, the steps, calendar, time and weather panels on top,
 * and the A-Z letters along the bottom. Pick a letter (or swipe left / right) and its apps float around the disc;
 * keep swiping to turn the pages and to move on to the next or the previous letter.
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
    val inf = rememberInfiniteTransition(label = "holo")
    val pulseS = inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val bobS = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val spinS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin")
    val ringS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(RING_MS, easing = LinearEasing)), label = "ring")
    val hueS = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "hue")
    val pulse by pulseS
    val bob by bobS
    val ripple = remember { Animatable(0f) }
    var rippleKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(rippleKey) {
        if (rippleKey > 0) { ripple.snapTo(0f); ripple.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    }
    // swiping back into the previous letter lands on its last page
    val landLast = remember { booleanArrayOf(false) }
    var page by remember(selected) {
        mutableIntStateOf(
            if (landLast[0] && selected != null) maxOf(0, (buckets[selected].orEmpty().size + PER_PAGE - 1) / PER_PAGE - 1) else 0
        )
    }
    LaunchedEffect(selected) { landLast[0] = false }
    val discScale by animateFloatAsState(if (selected == null) 1f else 0.36f, tween(450, easing = FastOutSlowInEasing), label = "discScale")

    fun pick(ch: Char) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        rippleKey++
        onSelect(if (selected == ch) null else ch)
    }
    // one tap on the disc: microphone on / off
    fun micToggle() {
        val turnOn = OrbService.instance == null
        val r = PhoneActions.run(ctx, "listen", org.json.JSONObject().put("on", turnOn).put("now", true), apps)
        Toast.makeText(ctx, if (!turnOn) "Microphone off" else if (r.ok) "Microphone on" else r.note, Toast.LENGTH_SHORT).show()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val topH = 212.dp
        val chainH = 54.dp
        val infoH = 56.dp
        val stageTop = topH + 8.dp
        val stageW = maxWidth
        val stageH = maxOf(maxHeight - stageTop - chainH - infoH - 12.dp, 230.dp)

        val discD = minOf(stageW * 0.84f, stageH * 0.9f)
        val orbitRx = stageW.value * 0.36f
        val orbitRy = maxOf(minOf(stageH.value / 2f - 60f, stageW.value * 0.42f), 60f)
        val orbitCy = stageH.value / 2f

        val shown = if (selected != null) buckets[selected].orEmpty() else emptyList()
        val pages = maxOf(1, (shown.size + PER_PAGE - 1) / PER_PAGE)
        val pg = page.coerceIn(0, pages - 1)

        fun hasApps(ch: Char) = buckets[ch].orEmpty().isNotEmpty()

        /** dir = +1: swipe left (next), dir = -1: swipe right (previous). Pages first, then the next / previous letter that has apps. */
        fun swipe(dir: Int) {
            val cur = selected
            if (cur == null) {
                val target = (if (dir > 0) chain.firstOrNull { hasApps(it) } else chain.lastOrNull { hasApps(it) }) ?: return
                landLast[0] = false
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                rippleKey++
                onSelect(target)
                return
            }
            if (dir > 0 && pg < pages - 1) { page = pg + 1; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); return }
            if (dir < 0 && pg > 0) { page = pg - 1; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); return }
            val idx = chain.indexOf(cur)
            val order: IntProgression = if (dir > 0) (idx + 1)..chain.lastIndex else (idx - 1) downTo 0
            val next = order.firstOrNull { hasApps(chain[it]) } ?: return
            landLast[0] = dir < 0
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            rippleKey++
            onSelect(chain[next])
        }

        // the same sideways swipe works on the stage and on the strip under it
        val swipeMod = Modifier.pointerInput(selected, pg, pages, buckets) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag < -70f) swipe(1) else if (drag > 70f) swipe(-1) },
                onDragCancel = { drag = 0f }
            ) { _, d -> drag += d }
        }

        // space background
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF00020F), Color(0xFF05103A), Color(0xFF000308))))
            stars.forEach { s -> drawCircle(Color.White.copy(alpha = s[3]), s[2], Offset(s[0] * size.width, s[1] * size.height)) }
        }

        // the stage: the logo disc, and the apps of the chosen letter around it
        Box(Modifier.align(Alignment.TopStart).offset(y = stageTop).fillMaxWidth().height(stageH).then(swipeMod)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = ripple.value
                if (r > 0f && r < 1f) {
                    val rr = discD.toPx() * 0.5f * (0.6f + 0.9f * r)
                    drawCircle(Color.hsv(hueS.value, 1f, 1f).copy(alpha = (1f - r) * 0.8f), rr, Offset(size.width / 2f, size.height / 2f), style = Stroke(3.dp.toPx() * (1f - r) + 1f))
                }
            }

            // the disc: tap = microphone (or close, while apps are showing), double tap = chat, long press = search
            LogoDisc(
                discD, spinS, ringS, hueS, pulseS,
                Modifier.align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = discScale
                        scaleY = discScale
                        translationY = (bobS.value - 0.5f) * 14.dp.toPx()
                    }
                    .semantics { contentDescription = "NEO PLUS disc: tap for microphone, double tap for chat, long press to search" }
                    .pointerInput(selected) {
                        detectTapGestures(
                            onTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (selected != null) onSelect(null) else micToggle()
                            },
                            onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAssistant() },
                            onLongPress = { onSearch() }
                        )
                    }
            )

            // the apps of the chosen letter float around the disc; a swipe slides to the next page / letter
            val ord = if (selected == null) -1 else chain.indexOf(selected).let { if (it < 0) -1 else it * 100 + pg }
            AnimatedContent(
                targetState = ord,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    when {
                        initialState < 0 -> (fadeIn(tween(300)) + scaleIn(tween(350), initialScale = 0.6f)) togetherWith fadeOut(tween(100))
                        targetState < 0 -> fadeIn(tween(100)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(250), targetScale = 0.6f))
                        targetState > initialState ->
                            (slideInHorizontally(tween(280)) { it } + fadeIn(tween(280))) togetherWith
                                (slideOutHorizontally(tween(280)) { -it / 2 } + fadeOut(tween(200)))
                        else ->
                            (slideInHorizontally(tween(280)) { -it } + fadeIn(tween(280))) togetherWith
                                (slideOutHorizontally(tween(280)) { it / 2 } + fadeOut(tween(200)))
                    }
                },
                label = "apps"
            ) { o ->
                if (o < 0) {
                    Box(Modifier.fillMaxSize())
                } else {
                    val letter = chain[o / 100]
                    val p = o % 100
                    val items = buckets[letter].orEmpty()
                    val pgs = maxOf(1, (items.size + PER_PAGE - 1) / PER_PAGE)
                    val slots = remember(o, stageW, buckets) {
                        holoSlots(items.drop(p * PER_PAGE).take(PER_PAGE), stageW.value / 2f, orbitCy, orbitRx, orbitRy)
                    }
                    Box(Modifier.fillMaxSize()) {
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
                                                drawCircle(Brush.radialGradient(listOf(Color.hsv(hueS.value, 1f, 1f).copy(alpha = 0.45f * pulse + 0.1f), Color.Transparent), center, rr), rr)
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
                        if (items.isEmpty()) {
                            Text(
                                if (letter == '#') "No apps start with a number or symbol" else "No apps start with $letter",
                                color = NeoSilver.copy(alpha = 0.75f), fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                            )
                        }
                        if (pgs > 1) {
                            Row(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in 0 until pgs) {
                                    Box(
                                        Modifier.size(if (i == p) 10.dp else 7.dp).clip(CircleShape)
                                            .background(if (i == p) NeoBlue else NeoSilver.copy(alpha = 0.35f)).clickable { page = i }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // steps, calendar, time and weather panels on top
        NeoTopPanels(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topH).padding(horizontal = 8.dp))

        // under the stage: a hint when idle, the chosen letter with a close button otherwise (swipe works here too)
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = chainH + 4.dp).fillMaxWidth().height(infoH).then(swipeMod),
            contentAlignment = Alignment.Center
        ) {
            if (selected == null) {
                Text("‹  swipe left or right to browse your apps  ›", color = NeoSilver.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.toString(), color = NeoBlue, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("  ·  ${shown.size} ${if (shown.size == 1) "app" else "apps"}", color = NeoSilver.copy(alpha = 0.85f), fontSize = 13.sp)
                    Box(
                        Modifier.padding(start = 16.dp).size(34.dp).clip(CircleShape).background(NeoBlue.copy(alpha = 0.18f))
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
