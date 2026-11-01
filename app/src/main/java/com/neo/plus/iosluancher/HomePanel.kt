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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Size
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
    val hours = SimpleDateFormat(if (use24) "HH" else "h", loc).format(date)
    val minutes = SimpleDateFormat("mm", loc).format(date)
    val ampm = if (use24) "" else SimpleDateFormat("a", loc).format(date).uppercase(loc)
    val weekday = SimpleDateFormat("EEEE", loc).format(date)
    val dayMonth = SimpleDateFormat("d MMMM", loc).format(date)
    val weekdayS = SimpleDateFormat("EEE", loc).format(date)
    val dayMonthS = SimpleDateFormat("d MMM", loc).format(date)

    val w = weather
    val card = RoundedCornerShape(26.dp)
    val sky = rememberSkyKind()
    val kind = if (w != null) weatherKind(w.code, w.isDay) else sky
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- clock (left) and calendar (right) side by side
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1.5f).fillMaxHeight().neoCard(card)
                    .clickable { ctx.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            ) {
                Canvas(Modifier.matchParentSize()) { clockStreaks() }
                Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedContent(
                            targetState = hours,
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "clockHours"
                        ) { h -> Text(h, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Thin, style = GlowText, maxLines = 1, softWrap = false) }
                        PulsingColon(42.sp)
                        AnimatedContent(
                            targetState = minutes,
                            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                            label = "clockMinutes"
                        ) { m -> Text(m, color = Color(0xFF7DD3FC), fontSize = 46.sp, fontWeight = FontWeight.Thin, style = GlowText, maxLines = 1, softWrap = false) }
                        if (ampm.isNotEmpty()) {
                            Text(ampm, color = NeoCyanC, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 9.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // day and date as two glass chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.clickable {
                            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
                            ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
                        }
                    ) {
                        Chip(weekdayS.uppercase(loc), NeoCyanC.copy(alpha = 0.22f), NeoCyanC)
                        Chip(dayMonthS.uppercase(loc), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.9f))
                    }
                    Spacer(Modifier.height(8.dp))
                    DayBar(now)
                }
            }
            CalendarTile(Modifier.weight(1f).fillMaxHeight().neoCard(card), now)
        }

        // ---- bottom row: weather and footsteps as two square glass tiles
        Row(Modifier.weight(1.15f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().neoCard(card)
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
                WeatherEmojis(kind, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
                if (w != null) {
                    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("${w.temp}°", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Thin, style = GlowText, modifier = Modifier.weight(1f))
                            WeatherGlyph(kind, Modifier.size(52.dp))
                        }
                        Column {
                            Text(weatherText(w.code), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (w.place.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOn, null, tint = NeoCyanC, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(w.place, color = NeoCyanC, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) {
                        Text("Weather", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (!permitted) "Tap to allow location" else "Getting forecast…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            StepsCard(Modifier.weight(1f).fillMaxHeight().neoCard(card))
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.8.sp,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** 24 little bars for the 24 hours of the day: hours that have passed glow, the current hour pulses brightest. */
@Composable
private fun DayBar(now: Long) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * 23) / 24f
        for (i in 0 until 24) {
            val c = when {
                i == hour -> NeoCyanC
                i < hour -> NeoBlueC.copy(alpha = 0.75f)
                else -> Color.White.copy(alpha = 0.14f)
            }
            val h = if (i == hour) size.height else size.height * 0.6f
            drawRoundRect(c, Offset(i * (bw + gap), size.height - h), androidx.compose.ui.geometry.Size(bw, h), androidx.compose.ui.geometry.CornerRadius(bw / 2f))
        }
    }
}

/** 👣 Footsteps tile: a glowing progress ring around today's steps, distance / energy below and the last 7 days as small bars. Tap to change the goal. */
@Composable
private fun StepsCard(modifier: Modifier) {
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        StepCounter.start(appCtx)
        while (true) {
            StepCounter.refresh(appCtx)
            delay(minOf(30_000L, StepCounter.msToMidnight() + 300L))
        }
    }
    var goal by remember { mutableStateOf(StepCounter.goal(appCtx)) }
    val steps = StepCounter.steps.intValue
    val week = remember(steps) { StepCounter.history(appCtx, 7) }
    val miles = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")
    val dist = StepCounter.km(steps).let { if (miles) it * 0.621371 else it }
    val frac = (steps.toFloat() / goal).coerceIn(0f, 1f)
    Box(modifier.clickable {
        goal = when (goal) { 4000 -> 6000; 6000 -> 8000; 8000 -> 10000; 10000 -> 12000; else -> 4000 }
        StepCounter.setGoal(appCtx, goal)
    }) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
                    val sw = 7.dp.toPx()
                    val inset = sw / 2f + 1.dp.toPx()
                    val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Color.White.copy(alpha = 0.12f), -90f, 360f, false, Offset(inset, inset), sz, style = Stroke(sw, cap = StrokeCap.Round))
                    if (frac > 0f) {
                        drawArc(NeoCyanC.copy(alpha = 0.25f), -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(sw * 1.9f, cap = StrokeCap.Round))
                        drawArc(Brush.sweepGradient(listOf(NeoBlueC, NeoCyanC, NeoBlueC)), -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FrameEmoji(WALK_FRAMES, 350L, size = 16.sp)
                    Text(java.text.NumberFormat.getIntegerInstance().format(steps), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, style = GlowText, maxLines = 1, softWrap = false)
                    Text("of ${java.text.NumberFormat.getIntegerInstance().format(goal)}", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1)
                }
            }
            Text(
                if (!StepCounter.working.value && steps == 0) "Carry your phone to start"
                else String.format(Locale.US, "%.1f %s · %d kcal", dist, if (miles) "mi" else "km", StepCounter.kcal(steps)),
                color = Color(0xFF7DD3FC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
            )
            Canvas(Modifier.fillMaxWidth().height(16.dp).padding(top = 5.dp)) {
                val top = maxOf(goal, week.maxOrNull() ?: 0).toFloat()
                val gap = 4.dp.toPx()
                val bw = (size.width - gap * 6) / 7f
                week.forEachIndexed { i, v ->
                    val h = maxOf(2.dp.toPx(), size.height * (v / top))
                    drawRoundRect(
                        if (i == week.lastIndex) NeoCyanC else Color.White.copy(alpha = 0.28f),
                        Offset(i * (bw + gap), size.height - h), androidx.compose.ui.geometry.Size(bw, h),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
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
        Text(String.format(Locale.US, "%02d", v), color = Color(0xFF7DD3FC), fontSize = 20.sp, fontWeight = FontWeight.Medium)
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

/** The colon between hours and minutes breathes once a second. */
@Composable
private fun PulsingColon(size: androidx.compose.ui.unit.TextUnit = 52.sp) {
    val t = rememberFrameSeconds()
    val a = 0.45f + 0.55f * ((kotlin.math.sin(t.value * 3f) + 1f) / 2f)
    Text(":", color = NeoCyanC.copy(alpha = a), fontSize = size, fontWeight = FontWeight.Light, style = GlowText,
        modifier = Modifier.padding(horizontal = 1.dp).padding(bottom = 4.dp))
}

/** A ring that fills once a minute with a glowing head; the seconds sit in the middle. */
@Composable
private fun SecondsRing(modifier: Modifier = Modifier) {
    val t = rememberFrameSeconds()
    Canvas(modifier) {
        val pulse = 0.8f + 0.2f * kotlin.math.sin(t.value * 3f)
        val stroke = 4.dp.toPx()
        val pad = stroke / 2f + 3.dp.toPx()
        val d = size.minDimension - pad * 2f
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val sz = Size(d, d)
        val frac = (System.currentTimeMillis() % 60_000L) / 60_000f
        drawArc(NeoCyanC.copy(alpha = 0.16f), 0f, 360f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(NeoCyanC.copy(alpha = 0.25f * pulse), -90f, 360f * frac, false, tl, sz, style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
        drawArc(NeoCyanC, -90f, 360f * frac, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val ang = Math.toRadians(-90.0 + 360.0 * frac)
        val r = d / 2f
        val head = Offset(size.width / 2f + r * kotlin.math.cos(ang).toFloat(), size.height / 2f + r * kotlin.math.sin(ang).toFloat())
        drawCircle(Color.White, 2.8.dp.toPx(), head)
    }
}


private class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/** Events from the phone's calendars between [from] and [to] (needs the calendar permission, asked at first start). */
private fun weekEvents(ctx: android.content.Context, from: Long, to: Long): List<CalEvent> = try {
    val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
    android.content.ContentUris.appendId(b, from)
    android.content.ContentUris.appendId(b, to)
    val out = ArrayList<CalEvent>()
    ctx.contentResolver.query(
        b.build(),
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
        null, null, CalendarContract.Instances.BEGIN + " ASC"
    )?.use { c ->
        while (c.moveToNext() && out.size < 80) {
            val allDay = c.getInt(3) == 1
            var bg = c.getLong(1); var en = c.getLong(2)
            if (allDay) { // all-day events are stored in UTC: move them to the phone's own day
                bg -= java.util.TimeZone.getDefault().getOffset(bg); en -= java.util.TimeZone.getDefault().getOffset(en)
            }
            out.add(CalEvent(c.getString(0).orEmpty().ifBlank { "Event" }, bg, en, allDay))
        }
    }
    out
} catch (e: Exception) { emptyList() }

/** Calendar card beside the clock: big day number, month, this week with today lit and a dot on days with events, and the next event. */
@Composable
private fun CalendarTile(modifier: Modifier, now: Long) {
    val ctx = LocalContext.current
    val loc = Locale.getDefault()
    val use24 = DateFormat.is24HourFormat(ctx)
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val back = (cal.get(java.util.Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
    val start = (cal.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_MONTH, -back)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val days = (0..6).map { i -> (start.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, i) } }
    val weekFrom = start.timeInMillis
    val weekTo = days.last().timeInMillis + 24L * 3600_000L
    val allowed = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var events by remember { mutableStateOf(emptyList<CalEvent>()) }
    LaunchedEffect(now / 60_000L, allowed) {
        if (allowed) events = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { weekEvents(ctx, weekFrom, weekTo) }
    }
    fun key(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val todayKey = key(cal)
    val busy = events.map { e -> key(java.util.Calendar.getInstance().apply { timeInMillis = e.begin }) }.toSet()
    val next = events.firstOrNull { it.end > now }
    val sub = Color.White.copy(alpha = 0.65f)

    Column(
        modifier.clickable {
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
            ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
        }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SimpleDateFormat("d", loc).format(Date(now)), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Thin, style = GlowText, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(SimpleDateFormat("MMM", loc).format(Date(now)).uppercase(loc), color = NeoCyanC, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1)
                Text(SimpleDateFormat("yyyy", loc).format(Date(now)), color = sub, fontSize = 10.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                val isToday = key(d) == todayKey
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SimpleDateFormat("EEEEE", loc).format(d.time).uppercase(loc), color = sub, fontSize = 9.sp, maxLines = 1)
                    Box(Modifier.size(17.dp).background(if (isToday) NeoCyanC else Color.Transparent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(d.get(java.util.Calendar.DAY_OF_MONTH).toString(), color = if (isToday) Color(0xFF031026) else Color.White, fontSize = 10.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                    }
                    Box(Modifier.padding(top = 2.dp).size(3.dp).background(if (key(d) in busy) NeoCyanC else Color.Transparent, RoundedCornerShape(50)))
                }
            }
        }
        Column {
            if (next != null) {
                val sameDay = key(java.util.Calendar.getInstance().apply { timeInMillis = next.begin }) == todayKey
                val whenText = (if (sameDay) "" else SimpleDateFormat("EEE ", loc).format(Date(next.begin))) +
                    (if (next.allDay) "All day" else SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", loc).format(Date(next.begin)))
                Text(next.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenText, color = NeoCyanC, fontSize = 10.sp, maxLines = 1)
            } else {
                Text(if (allowed) "No events this week" else "Tap to open calendar", color = sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
