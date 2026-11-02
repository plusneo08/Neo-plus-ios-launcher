package com.neo.plus.iosluancher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Persistent switch between the NEO PLUS A–Z ring home screen (default) and the classic iOS-style home screen. */
object AlphaPrefs {
    private const val KEY = "alpha_home"
    private fun p(ctx: Context) = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY, true)
    fun set(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY, on).apply() }
}

/**
 * Which ring button an app belongs to. Case-insensitive; accents are ignored (É -> E); leading spaces and symbols are skipped.
 * Names that start with a digit or a non-Latin letter go to the small '#' button.
 */
fun alphaKeyOf(label: String): Char {
    val norm = Normalizer.normalize(label.trim(), Normalizer.Form.NFD)
    val c = norm.firstOrNull { it.isLetterOrDigit() } ?: return '#'
    val u = c.uppercaseChar()
    return if (u in 'A'..'Z') u else '#'
}

/** Opens an app with its own launch intent; falls back to the package's default launch intent and tells the user if neither works. */
fun Context.launchAlphaApp(app: AppEntry) {
    when {
        app.role == Role.PHONE || iosKindOf(app.pkg, app.label) == IosKind.PHONE -> {
            safeStart(Intent(this, NeoDialerActivity::class.java)); return
        }
        app.role == Role.MESSAGES && app.pkg.contains('#') -> {
            safeStart(Intent(this, NeoMessagesActivity::class.java)); return
        }
        app.realPkg == packageName -> {
            try { MainActivity.current?.goHome() } catch (e: Exception) { }; return
        }
    }
    val explicit = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(app.realPkg, app.activity))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    if (safeStart(explicit)) return
    val fallback = try { packageManager.getLaunchIntentForPackage(app.realPkg) } catch (e: Exception) { null }
    if (fallback != null && safeStart(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
    Toast.makeText(this, "${app.shownLabel} can't be opened", Toast.LENGTH_SHORT).show()
}

internal val NeoBlue = Color(0xFF1AA7FF)
internal val NeoBlueDeep = Color(0xFF0B3C9E)
internal val NeoSilver = Color(0xFFE8EEF8)

/** The default NEO PLUS home screen: logo in the middle, A–Z ring around it, no app icons until a letter is tapped. */
@Composable
fun AlphaRingHome(
    apps: List<AppEntry>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    onSearch: () -> Unit,
    onAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val buckets = remember(apps) {
        apps.groupBy { alphaKeyOf(it.shownLabel) }
            .mapValues { (_, v) -> v.distinctBy { it.pkg + "/" + it.activity }.sortedBy { it.shownLabel.lowercase() } }
    }
    val letters = remember { ('A'..'Z').toList() }
    val stars = remember {
        val r = java.util.Random(7)
        List(110) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.3f, 0.25f + r.nextFloat() * 0.6f) }
    }
    val bolts = remember {
        val r = java.util.Random(11)
        List(7) { i -> List(9) { floatArrayOf(r.nextFloat(), r.nextFloat()) } to i }
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p"
    )
    val lastLetter = remember { charArrayOf('A') }
    if (selected != null) lastLetter[0] = selected
    val ripple = remember { Animatable(0f) }
    var rippleKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(rippleKey) {
        if (rippleKey > 0) { ripple.snapTo(0f); ripple.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
    }

    // one tap: microphone on when it is off, off when it is on (the floating assistant is the microphone listener)
    fun micToggle() {
        val turnOn = OrbService.instance == null
        val r = PhoneActions.run(ctx, "listen", org.json.JSONObject().put("on", turnOn).put("now", true), apps)
        val msg = if (!turnOn) "Microphone off" else if (r.ok) "Microphone on" else r.note
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun pick(ch: Char) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        rippleKey++
        onSelect(if (selected == ch) null else ch)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val topH = 92.dp      // clock + weather strip
        val botH = 112.dp     // steps tile
        val ringTop = topH + 14.dp
        val side = maxOf(minOf(maxWidth - 12.dp, maxHeight - topH - botH - 44.dp), 200.dp)
        val btn = side * 0.094f
        val rc = side / 2 - btn / 2 - 3.dp
        val inner = (rc - btn / 2) * 2 - 10.dp

        // space background, energy bolts, pedestal
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, (ringTop + side / 2).toPx())
            drawRect(Brush.verticalGradient(listOf(Color(0xFF00020F), Color(0xFF031038), Color(0xFF000308))))
            drawCircle(
                Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.30f * pulse + 0.10f), Color.Transparent), center, side.toPx() * 0.95f),
                side.toPx() * 0.95f, center
            )
            stars.forEach { s -> drawCircle(Color.White.copy(alpha = s[3]), s[2], Offset(s[0] * w, s[1] * h)) }
            bolts.forEach { (pts, i) ->
                val fromLeft = i % 2 == 0
                val path = Path()
                var x = if (fromLeft) 0f else w
                var y = h * (0.05f + 0.13f * i)
                path.moveTo(x, y)
                pts.forEachIndexed { k, p ->
                    val t = (k + 1) / pts.size.toFloat()
                    x = (if (fromLeft) 0f else w) + ((center.x - (if (fromLeft) 0f else w)) * 0.55f * t) + (p[0] - 0.5f) * 46f
                    y += (center.y - y) * 0.16f + (p[1] - 0.5f) * 40f
                    path.lineTo(x, y)
                }
                drawPath(path, NeoBlue.copy(alpha = 0.10f * pulse), style = Stroke(5.dp.toPx()))
                drawPath(path, NeoBlue.copy(alpha = 0.40f), style = Stroke(1.2.dp.toPx()))
            }
            // glowing pedestal under the ring
            val baseY = center.y + side.toPx() / 2f + 16.dp.toPx()
            for (k in 0 until 4) {
                val rw = side.toPx() * (0.52f - k * 0.1f)
                drawOval(
                    NeoBlue.copy(alpha = 0.55f - k * 0.1f),
                    Offset(center.x - rw, baseY - rw * 0.16f + k * 3.dp.toPx()),
                    androidx.compose.ui.geometry.Size(rw * 2f, rw * 0.32f),
                    style = Stroke(1.6.dp.toPx())
                )
            }
        }

        // the ring + logo / app panel
        Box(Modifier.size(side).align(Alignment.TopCenter).offset(y = ringTop), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f
                val innerR = (rc - btn / 2).toPx() - 1.dp.toPx()
                drawCircle(NeoBlue.copy(alpha = 0.18f * pulse), outer + 2.dp.toPx(), c, style = Stroke(9.dp.toPx()))
                drawCircle(Brush.sweepGradient(listOf(NeoSilver, NeoBlue, NeoBlueDeep, NeoBlue, NeoSilver), c), outer - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                drawCircle(NeoBlue.copy(alpha = 0.85f), innerR, c, style = Stroke(2.dp.toPx()))
                drawCircle(NeoBlue.copy(alpha = 0.22f), innerR - 5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xFF00040F).copy(alpha = 0.92f), innerR - 1.dp.toPx(), c)
                val r = ripple.value
                if (r > 0f && r < 1f) {
                    drawCircle(NeoBlue.copy(alpha = (1f - r) * 0.65f), innerR * (0.35f + 0.65f * r), c, style = Stroke(3.dp.toPx() * (1f - r) + 1f))
                    drawCircle(NeoBlue.copy(alpha = (1f - r) * 0.30f), outer * (0.5f + 0.6f * r), c, style = Stroke(2.dp.toPx()))
                }
            }

            // centre: logo when nothing is selected, otherwise the apps of the chosen letter
            AnimatedVisibility(
                selected == null,
                enter = fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.85f),
                exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f)
            ) {
                Box(Modifier.size(inner * 0.98f), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(R.drawable.neo_np_logo), "NEO PLUS",
                        Modifier.fillMaxSize().scale(0.985f + 0.015f * pulse),
                        contentScale = ContentScale.Fit
                    )
                    // Assistant button in the middle of the logo: one tap = microphone on/off, quick double tap = assistant chat, hold = search
                    Box(
                        Modifier.size(inner * 0.2f)
                            .drawBehind {
                                val r = this.size.minDimension * 0.85f
                                drawCircle(Brush.radialGradient(listOf(NeoBlue.copy(alpha = 0.35f + 0.3f * pulse), Color.Transparent), center, r), r)
                            }
                            .semantics { contentDescription = "Assistant: tap for microphone, double tap for chat" }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        micToggle()
                                    },
                                    onDoubleTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAssistant()
                                    },
                                    onLongPress = { onSearch() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) { AssistantOrb(inner * 0.17f) }
                }
            }
            AnimatedVisibility(
                selected != null,
                enter = fadeIn(tween(300)) + scaleIn(spring(0.7f, Spring.StiffnessMedium), initialScale = 0.4f),
                exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.4f)
            ) {
                // keep the last letter while the exit animation runs
                val letter = lastLetter[0]
                AppPanel(letter, buckets[letter].orEmpty(), inner, onClose = { onSelect(null) }) { app ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    ctx.launchAlphaApp(app)
                    onSelect(null)
                }
            }

            // the 26 letters
            letters.forEachIndexed { i, ch ->
                val a = -PI / 2 + i * 2 * PI / 26
                Box(Modifier.align(Alignment.Center).offset(x = rc * cos(a).toFloat(), y = rc * sin(a).toFloat())) {
                    LetterButton(ch, btn, selected == ch, buckets[ch]?.size ?: 0, pulse) { pick(ch) }
                }
            }
        }

        // clock + weather on top, steps at the bottom
        NeoTopStrip(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topH).padding(horizontal = 8.dp))
        NeoStepsTile(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(botH))

        // apps starting with a digit or symbol
        if (buckets['#'].orEmpty().isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 8.dp + (botH - btn * 1.15f) / 2)) {
                LetterButton('#', btn * 1.15f, selected == '#', buckets['#']?.size ?: 0, pulse) { pick('#') }
            }
        }
    }
}

@Composable
internal fun LetterButton(ch: Char, size: Dp, isSelected: Boolean, count: Int, pulse: Float, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (isSelected) 1.24f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "letterScale"
    )
    val glowA = if (isSelected) 0.55f + 0.35f * pulse else 0.22f
    val fill = if (isSelected)
        Brush.radialGradient(listOf(Color(0xFF59C3FF), Color(0xFF0A5FD0), Color(0xFF062A78)))
    else
        Brush.verticalGradient(listOf(Color(0xFF0A1B45), Color(0xFF020716)))
    Box(
        Modifier.size(size).scale(scale)
            .drawBehind {
                val r = this.size.minDimension * 0.95f
                drawCircle(Brush.radialGradient(listOf(NeoBlue.copy(alpha = glowA), Color.Transparent), center, r), r)
            }
            .clip(CircleShape)
            .background(fill)
            .border(1.6.dp, Brush.verticalGradient(listOf(NeoSilver, NeoBlue, NeoBlueDeep)), CircleShape)
            .semantics { contentDescription = "Letter $ch, $count apps" }
            .noRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ch.toString(),
            color = if (count == 0 && !isSelected) NeoSilver.copy(alpha = 0.45f) else NeoSilver,
            fontSize = (size.value * 0.52f).sp,
            fontWeight = FontWeight.Black,
            style = TextStyle(shadow = Shadow(NeoBlue, blurRadius = 8f))
        )
    }
}

@Composable
private fun AppPanel(letter: Char, apps: List<AppEntry>, diameter: Dp, onClose: () -> Unit, onLaunch: (AppEntry) -> Unit) {
    Box(
        Modifier.size(diameter).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF07163C), Color(0xFF020816))))
            .border(1.dp, NeoBlue.copy(alpha = 0.55f), CircleShape)
            .noRipple { },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier.fillMaxSize().padding(top = diameter * 0.115f, bottom = diameter * 0.05f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(letter.toString(), color = NeoBlue, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(
                    "  ·  ${apps.size} ${if (apps.size == 1) "app" else "apps"}",
                    color = NeoSilver.copy(alpha = 0.8f), fontSize = 12.sp
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (apps.isEmpty()) {
                    Text(
                        if (letter == '#') "No apps start with a number or symbol" else "No apps start with $letter",
                        color = NeoSilver.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = diameter * 0.2f)
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        Modifier.size(diameter * 0.76f, diameter * 0.6f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(apps, key = { it.pkg + "/" + it.activity }) { app ->
                            Column(
                                Modifier.fillMaxWidth().noRipple { onLaunch(app) }.padding(vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Image(app.icon, app.shownLabel, Modifier.size(diameter * 0.165f).clip(RoundedCornerShape(diameter * 0.04f)))
                                Text(
                                    app.shownLabel, color = Color.White, fontSize = 10.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(NeoBlue.copy(alpha = 0.18f))
                    .border(1.dp, NeoBlue.copy(alpha = 0.8f), CircleShape)
                    .semantics { contentDescription = "Close" }
                    .noRipple(onClose),
                contentAlignment = Alignment.Center
            ) { Text("✕", color = NeoSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
