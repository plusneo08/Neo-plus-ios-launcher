package com.neo.plus.iosluancher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle

/* ---- NEO PLUS look for the clock and weather cards: deep navy glass, glowing neon edge, light streaks / mountain horizon ---- */
private val NeoCyanC = Color(0xFF38BDF8)
private val NeoBlueC = Color(0xFF1D6BFF)
private val GlowText = TextStyle(shadow = Shadow(color = NeoCyanC.copy(alpha = 0.55f), offset = Offset.Zero, blurRadius = 24f))

private fun Modifier.neoCard(shape: Shape): Modifier = this
    .shadow(12.dp, shape, clip = false, ambientColor = NeoBlueC, spotColor = NeoCyanC)
    .clip(shape)
    .background(Brush.linearGradient(listOf(Color(0xFF0D2C62), Color(0xFF071634), Color(0xFF030A1C))))
    .border(1.6.dp, Brush.linearGradient(listOf(NeoCyanC, NeoBlueC.copy(alpha = 0.55f), NeoCyanC.copy(alpha = 0.9f))), shape)

/** Glowing diagonal light streaks in two corners of the clock card. */
private fun DrawScope.clockStreaks() {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply { moveTo(w * 0.62f, 0f); lineTo(w * 0.80f, 0f); lineTo(w, h * 0.36f); lineTo(w, h * 0.52f); close() },
        Brush.linearGradient(listOf(NeoCyanC.copy(alpha = 0.22f), Color.Transparent), Offset(w, 0f), Offset(w * 0.6f, h * 0.5f))
    )
    fun bar(x1: Float, y1: Float, x2: Float, y2: Float, a: Float) {
        drawLine(NeoCyanC.copy(alpha = a * 0.28f), Offset(x1, y1), Offset(x2, y2), 7.dp.toPx(), StrokeCap.Round)
        drawLine(NeoCyanC.copy(alpha = a), Offset(x1, y1), Offset(x2, y2), 1.8.dp.toPx(), StrokeCap.Round)
    }
    bar(w * 0.62f, 0f, w, h * 0.52f, 0.8f)
    bar(w * 0.80f, 0f, w, h * 0.28f, 0.55f)
    bar(0f, h * 0.74f, w * 0.22f, h, 0.7f)
    bar(0f, h * 0.90f, w * 0.09f, h, 0.45f)
}

/** Dark mountain ridges with a glowing blue horizon along the bottom of the weather card. */
private fun DrawScope.mountains() {
    val w = size.width
    val h = size.height
    fun ridge(pts: List<Pair<Float, Float>>, fill: Brush, edge: Color) {
        val body = Path()
        body.moveTo(0f, h)
        pts.forEach { (x, y) -> body.lineTo(w * x, h * y) }
        body.lineTo(w, h)
        body.close()
        drawPath(body, fill)
        val line = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) line.moveTo(w * x, h * y) else line.lineTo(w * x, h * y) }
        drawPath(line, edge, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    ridge(
        listOf(0f to 0.78f, 0.10f to 0.64f, 0.20f to 0.72f, 0.36f to 0.50f, 0.48f to 0.68f, 0.60f to 0.60f, 0.74f to 0.76f, 0.88f to 0.64f, 1f to 0.72f),
        Brush.verticalGradient(listOf(Color(0xFF1B3B6B), Color(0xFF050B18)), startY = h * 0.5f, endY = h), NeoCyanC.copy(alpha = 0.55f)
    )
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, NeoBlueC.copy(alpha = 0.50f), NeoCyanC.copy(alpha = 0.70f)), startY = h * 0.68f, endY = h))
    ridge(
        listOf(0f to 0.90f, 0.18f to 0.80f, 0.34f to 0.88f, 0.52f to 0.78f, 0.70f to 0.90f, 0.86f to 0.82f, 1f to 0.88f),
        SolidColor(Color(0xFF030810)), NeoBlueC.copy(alpha = 0.60f)
    )
}

/**
 * Clean glass panel at the top of the first home page:
 * big time, the date, and live weather for where the phone is.
 */
@Composable
fun HomePanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val use24 = DateFormat.is24HourFormat(ctx)

    // Ticks exactly on the minute
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L + 30L)
        }
    }

    // Weather: cached value shows instantly, fresh data replaces it
    var weather by remember { mutableStateOf(WeatherRepo.cached(appCtx)) }
    var permitted by remember { mutableStateOf(WeatherRepo.hasPermission(appCtx)) }
    var tick by remember { mutableIntStateOf(0) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        if (ok) tick++
    }
    LaunchedEffect(tick, permitted) {
        while (permitted) {
            val c = weather
            val stale = c == null || System.currentTimeMillis() - c.time > 20 * 60_000L
            var failed = false
            if (stale) {
                val fresh = WeatherRepo.refresh(appCtx)
                if (fresh != null) weather = fresh else failed = true
            }
            delay(if (failed && weather == null) 45_000L else 10 * 60_000L)
        }
    }

    val date = Date(now)
    val loc = Locale.getDefault()
    val time = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", loc).format(date)
    val ampm = if (use24) "" else SimpleDateFormat("a", loc).format(date).uppercase(loc)
    val weekday = SimpleDateFormat("EEEE", loc).format(date)
    val dayMonth = SimpleDateFormat("d MMMM", loc).format(date)

    val w = weather
    val card = RoundedCornerShape(26.dp)
    val sky = rememberSkyKind()
    val kind = if (w != null) weatherKind(w.code, w.isDay) else sky
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- clock card: big rolling digits, live seconds and a glowing seconds sweep along the bottom edge
        Box(
            Modifier.weight(1f).fillMaxWidth().neoCard(card)
                .clickable { ctx.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        ) {
            Canvas(Modifier.matchParentSize()) { clockStreaks() }
            SecondsSweep(Modifier.matchParentSize())
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedContent(
                        targetState = time,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "clockTime"
                    ) { t ->
                        Text(t, color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, style = GlowText, maxLines = 1, softWrap = false)
                    }
                    if (ampm.isNotEmpty()) {
                        Text(ampm, color = Color(0xFFBFE6FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clickable {
                        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
                        ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
                    }
                ) {
                    Text(weekday, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(dayMonth, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1)
                    LiveSeconds()
                }
            }
        }

        // ---- weather card: the live sky for the current weather moves behind a large animated icon
        Box(
            Modifier.weight(1f).fillMaxWidth().neoCard(card)
                .clickable {
                    when {
                        w != null -> ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("weather"))))
                        !permitted -> askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        else -> tick++
                    }
                }
        ) {
            WeatherScene(kind, Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize()) { mountains() }
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (w != null) {
                    WeatherGlyph(kind, Modifier.size(80.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${w.temp}°", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Light, style = GlowText)
                        Text(weatherText(w.code), color = Color.White.copy(alpha = 0.94f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (w.place.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 130.dp)) {
                            Icon(Icons.Filled.LocationOn, null, tint = NeoCyanC, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(w.place, color = NeoCyanC, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Column {
                        Text("Weather", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!permitted) "Tap to allow location" else "Getting forecast…",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Seconds that roll over every tick. */
@Composable
private fun LiveSeconds() {
    val s by produceState(0) {
        while (true) {
            val n = System.currentTimeMillis()
            value = ((n / 1000L) % 60L).toInt()
            delay(1000L - n % 1000L + 5L)
        }
    }
    AnimatedContent(
        targetState = s,
        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
        label = "clockSeconds"
    ) { v ->
        Text(String.format(Locale.US, "%02d", v), color = Color(0xFF7DD3FC), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** A glowing line along the bottom of the clock card that fills once a minute, with a bright head. */
@Composable
private fun SecondsSweep(modifier: Modifier = Modifier) {
    val t = rememberFrameSeconds()
    Canvas(modifier) {
        val pulse = 0.8f + 0.2f * kotlin.math.sin(t.value * 3f)
        val frac = (System.currentTimeMillis() % 60_000L) / 60_000f
        val x = size.width * frac
        val y = size.height - 4.dp.toPx()
        val tail = size.width * 0.28f
        drawLine(NeoCyanC.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, NeoCyanC), startX = x - tail, endX = x),
            Offset(maxOf(0f, x - tail), y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round
        )
        drawCircle(NeoCyanC.copy(alpha = 0.35f * pulse), 8.dp.toPx(), Offset(x, y))
        drawCircle(Color.White, 2.6.dp.toPx(), Offset(x, y))
    }
}
