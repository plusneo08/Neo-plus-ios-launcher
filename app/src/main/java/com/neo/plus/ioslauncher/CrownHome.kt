@file:OptIn(ExperimentalFoundationApi::class)

package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Black "crown" home screen: black glass clock + weather/steps panels, then ONE list of every app
 * (black glass tile, white logo, chamfered name tab). There is no app drawer and no drawer button:
 * every app is on this list, which scrolls. Turn it on / off from the touch-and-hold Edit bubble.
 */
object CrownPrefs {
    private const val KEY = "crown_home_v1"
    private fun p(ctx: Context) = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY, true)
    fun set(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY, on).apply() }
}

private val CrownWhite = Color(0xFFF2F2F2)
private val CrownDim = Color(0xFF8C8C8C)
private val CrownGlow = TextStyle(shadow = Shadow(color = Color.White.copy(alpha = 0.35f), offset = Offset.Zero, blurRadius = 18f))

/** Rectangle with two cut corners (top-left and bottom-right by default), like the panels in the design. */
private class ChamferShape(private val cutDp: Float, private val bothSides: Boolean = false) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = cutDp * density.density
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(c, 0f)
            lineTo(w - (if (bothSides) c else 0f), 0f)
            if (bothSides) lineTo(w, c) else lineTo(w, 0f)
            lineTo(w, h - c)
            lineTo(w - c, h)
            lineTo(if (bothSides) c else 0f, h)
            if (bothSides) lineTo(0f, h - c) else lineTo(0f, h)
            lineTo(0f, c)
            close()
        }
        return Outline.Generic(p)
    }
}

/** The name tab: flat on the left (behind the tile), pointed on the right like the design. */
private class TabShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tip = size.height * 0.34f
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width - tip, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width - tip, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(p)
    }
}

private val RimBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.7f)))

private fun Modifier.blackGlass(shape: Shape, rim: Float = 1.2f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF050505), Color(0xFF0C0C0C))))
    .border(rim.dp, RimBrush, shape)

/* ------------------------------------------------------------------------------------------------ */
/*  Apps                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Favourite names first (same order as the design), then every other app A-Z. Nothing is left out: no drawer needed. */
private val CrownOrder = listOf(
    "youtube", "whatsapp", "telegram", "spotify", "play store", "messenger", "facebook", "chrome", "google", "drive",
    "camera", "gallery", "photos", "phone", "messages", "contacts", "settings", "files"
)

private fun crownRank(a: AppEntry): Int {
    val l = a.shownLabel.lowercase().trim()
    val i = CrownOrder.indexOfFirst { l == it }
    return if (i >= 0) i else CrownOrder.size
}

private fun crownVector(a: AppEntry): ImageVector? {
    val l = a.shownLabel.lowercase().trim()
    val p = a.pkg.lowercase()
    return when {
        a.role == Role.PHONE || l == "phone" || "dialer" in p -> Icons.Filled.Call
        a.role == Role.MESSAGES || l == "messages" || l == "messaging" -> Icons.Filled.Sms
        a.role == Role.CAMERA || l == "camera" -> Icons.Filled.PhotoCamera
        l == "contacts" -> Icons.Filled.Person
        l == "settings" -> Icons.Filled.Settings
        l == "files" || l == "my files" || l == "file manager" -> Icons.Filled.Folder
        l == "gallery" || l == "photos" -> Icons.Filled.Photo
        else -> null
    }
}

private val GreyMatrix = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val WhiteInk = ColorFilter.tint(Color.White, androidx.compose.ui.graphics.BlendMode.SrcIn)

/** White logo on a black glass tile. Uses the app's own logo (no coloured background) when the phone gives one. */
@Composable
private fun CrownIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val shape = remember { ChamferShape(9f, bothSides = true) }
    Box(modifier.size(size).blackGlass(shape, 1.4f), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Transparent))))
        val vec = remember(app.pkg, app.label, app.role) { crownVector(app) }
        when {
            vec != null -> Icon(vec, app.shownLabel, tint = Color.White, modifier = Modifier.size(size * 0.58f))
            app.glyph != null -> Image(app.glyph, app.shownLabel, Modifier.fillMaxSize(0.62f), colorFilter = WhiteInk)
            else -> Image(app.icon, app.shownLabel, Modifier.fillMaxSize(0.66f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), colorFilter = GreyMatrix)
        }
    }
}

@Composable
private fun CrownRow(app: AppEntry, vm: LauncherViewModel, tileSize: Dp, tabWidth: Dp) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf<List<android.content.pm.ShortcutInfo>>(emptyList()) }
    val pinned = app.pkg in vm.dock
    val tab = remember { TabShape() }
    Box(
        Modifier.combinedClickable(
            onClick = {
                if (app.role == Role.PHONE || iosKindOf(app.pkg, app.label) == IosKind.PHONE) {
                    ctx.safeStart(Intent(ctx, NeoDialerActivity::class.java))
                } else if (app.role == Role.MESSAGES && app.pkg.contains('#')) {
                    ctx.safeStart(Intent(ctx, NeoMessagesActivity::class.java))
                } else ctx.launchApp(app)
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                shortcuts = ctx.shortcutsFor(app.pkg)
                menu = true
            }
        )
    ) {
        // name tab sits behind the tile and slides out to the right
        Box(
            Modifier.padding(start = tileSize / 2).width(tabWidth).height(tileSize - 8.dp).align(Alignment.CenterStart)
                .clip(tab)
                .background(Brush.horizontalGradient(listOf(Color(0xFF161616), Color(0xFF070707), Color(0xFF0E0E0E))))
                .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.0f), Color.White.copy(alpha = 0.35f))), tab)
        )
        Row(Modifier.height(tileSize), verticalAlignment = Alignment.CenterVertically) {
            CrownIcon(app, tileSize)
            Spacer(Modifier.width(18.dp))
            Text(
                app.shownLabel, color = CrownWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(tabWidth - 54.dp)
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            shortcuts.forEach { s ->
                DropdownMenuItem(text = { Text(s.shortLabel?.toString() ?: "Shortcut") }, onClick = { menu = false; ctx.startShortcut(s) })
            }
            if (shortcuts.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("App Info") },
                onClick = { menu = false; ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))) }
            )
            DropdownMenuItem(text = { Text("Hide from Home Screen") }, onClick = { menu = false; vm.removeFromHome(app.pkg) })
            if (!app.system) {
                DropdownMenuItem(
                    text = { Text("Uninstall") },
                    onClick = { menu = false; ctx.safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */
/*  Panels                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

@Composable
private fun AnalogClock(now: Long, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f - 2.dp.toPx()
        drawCircle(Color(0xFF050505), r, c)
        drawCircle(Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.8f))), r, c, style = Stroke(2.2.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val l = if (i % 3 == 0) 0.16f else 0.10f
            drawLine(
                Color.White.copy(alpha = 0.9f),
                Offset(c.x + (cos(a) * r * (0.92f - l)).toFloat(), c.y + (sin(a) * r * (0.92f - l)).toFloat()),
                Offset(c.x + (cos(a) * r * 0.92f).toFloat(), c.y + (sin(a) * r * 0.92f).toFloat()),
                strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round
            )
        }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val h = (cal.get(java.util.Calendar.HOUR) + cal.get(java.util.Calendar.MINUTE) / 60f)
        val m = cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60f
        fun hand(deg: Float, len: Float, w: Float) {
            val a = Math.toRadians(deg - 90.0)
            drawLine(Color.White, c, Offset(c.x + (cos(a) * r * len).toFloat(), c.y + (sin(a) * r * len).toFloat()), strokeWidth = w, cap = StrokeCap.Round)
        }
        hand(h * 30f, 0.50f, 3.dp.toPx())
        hand(m * 6f, 0.78f, 2.dp.toPx())
        drawCircle(Color.White, 3.dp.toPx(), c)
    }
}

/** Plain white weather picture (moon / sun / cloud / rain / snow / storm / fog). */
@Composable
private fun CrownWeatherGlyph(kind: WKind, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val soft = Brush.verticalGradient(listOf(Color.White, Color(0xFFB8B8B8)))
        fun cloud(ox: Float, oy: Float, s: Float) {
            drawCircle(soft, h * 0.20f * s, Offset(ox + w * 0.30f * s, oy + h * 0.60f * s))
            drawCircle(soft, h * 0.27f * s, Offset(ox + w * 0.52f * s, oy + h * 0.50f * s))
            drawCircle(soft, h * 0.20f * s, Offset(ox + w * 0.74f * s, oy + h * 0.60f * s))
            drawRoundRect(soft, Offset(ox + w * 0.28f * s, oy + h * 0.58f * s), Size(w * 0.48f * s, h * 0.22f * s), androidx.compose.ui.geometry.CornerRadius(h * 0.1f))
        }
        fun sun(cx: Float, cy: Float, r: Float) {
            drawCircle(Color.White, r, Offset(cx, cy))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(Color.White, Offset(cx + (cos(a) * r * 1.35f).toFloat(), cy + (sin(a) * r * 1.35f).toFloat()),
                    Offset(cx + (cos(a) * r * 1.75f).toFloat(), cy + (sin(a) * r * 1.75f).toFloat()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        fun moon(cx: Float, cy: Float, r: Float) {
            val p = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx - r, cy - r), Size(r * 2, r * 2))) }
            val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx - r * 0.35f, cy - r * 1.15f), Size(r * 2, r * 2))) }
            val res = Path()
            res.op(p, cut, androidx.compose.ui.graphics.PathOperation.Difference)
            drawPath(res, Color.White)
        }
        when (kind) {
            WKind.CLEAR_DAY -> sun(w * 0.5f, h * 0.5f, h * 0.2f)
            WKind.CLEAR_NIGHT -> moon(w * 0.5f, h * 0.5f, h * 0.30f)
            WKind.PARTLY_DAY -> { sun(w * 0.32f, h * 0.34f, h * 0.15f); cloud(w * 0.12f, h * 0.10f, 0.95f) }
            WKind.PARTLY_NIGHT -> { moon(w * 0.34f, h * 0.34f, h * 0.22f); cloud(w * 0.12f, h * 0.10f, 0.95f) }
            WKind.CLOUDY, WKind.FOG -> cloud(0f, 0f, 1f)
            WKind.RAIN -> {
                cloud(0f, -h * 0.08f, 1f)
                for (i in 0 until 3) drawLine(Color.White, Offset(w * (0.34f + i * 0.17f), h * 0.74f), Offset(w * (0.30f + i * 0.17f), h * 0.92f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
            WKind.SNOW -> {
                cloud(0f, -h * 0.08f, 1f)
                for (i in 0 until 3) drawCircle(Color.White, 2.5.dp.toPx(), Offset(w * (0.34f + i * 0.17f), h * (if (i == 1) 0.90f else 0.80f)))
            }
            WKind.STORM -> {
                cloud(0f, -h * 0.08f, 1f)
                val bolt = Path().apply {
                    moveTo(w * 0.54f, h * 0.68f); lineTo(w * 0.44f, h * 0.84f); lineTo(w * 0.52f, h * 0.84f)
                    lineTo(w * 0.46f, h * 0.98f); lineTo(w * 0.62f, h * 0.78f); lineTo(w * 0.53f, h * 0.78f); close()
                }
                drawPath(bolt, Color.White)
            }
        }
    }
}

@Composable
private fun StepBars(week: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val n = 7
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val data = if (week.size >= n) week.takeLast(n) else List(n - week.size) { 0 } + week
        val mx = (data.maxOrNull() ?: 1).coerceAtLeast(1)
        data.forEachIndexed { i, v ->
            val bh = size.height * (0.22f + 0.78f * v / mx)
            val last = i == n - 1
            drawRect(Color.White.copy(alpha = if (last) 0.95f else 0.45f), Offset(i * (bw + gap), size.height - bh), Size(bw, bh))
        }
    }
}

@Composable
private fun CrownPanels(modifier: Modifier) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val use24 = DateFormat.is24HourFormat(ctx)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L - value % 30_000L + 30L)
        }
    }
    var weather by remember { mutableStateOf(WeatherRepo.cached(appCtx)) }
    var permitted by remember { mutableStateOf(WeatherRepo.hasPermission(appCtx)) }
    var tick by remember { mutableIntStateOf(0) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
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
    LaunchedEffect(Unit) {
        StepCounter.start(appCtx)
        while (true) {
            StepCounter.refresh(appCtx)
            delay(minOf(30_000L, StepCounter.msToMidnight() + 300L))
        }
    }
    val steps = StepCounter.steps.intValue
    val week = remember(steps) { StepCounter.history(appCtx, 7) }

    val date = Date(now)
    val loc = Locale.getDefault()
    val time = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", loc).format(date)
    val dateLine = SimpleDateFormat("EEE, MMM d", loc).format(date)
    val w = weather
    val sky = rememberSkyKind()
    val kind = if (w != null) weatherKind(w.code, w.isDay) else sky
    val shapeL = remember { ChamferShape(14f, bothSides = true) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // clock panel
        Box(
            Modifier.weight(1.12f).fillMaxHeight().blackGlass(shapeL, 1.5f)
                .clickable { ctx.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AnalogClock(now, Modifier.size(70.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(time, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = CrownGlow)
                    Text(
                        dateLine, color = CrownWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.clickable {
                            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
                            ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
                        }
                    )
                }
            }
        }
        // weather + steps panel
        Box(
            Modifier.weight(1f).fillMaxHeight().blackGlass(shapeL, 1.5f)
                .clickable {
                    when {
                        w != null -> ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("weather"))))
                        !permitted -> askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        else -> tick++
                    }
                }
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1.25f)) {
                    CrownWeatherGlyph(kind, Modifier.size(52.dp))
                    Spacer(Modifier.width(6.dp))
                    if (w != null) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("${w.temp}°${if (w.fahrenheit) "F" else "C"}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = CrownGlow)
                            Text(weatherText(w.code), color = CrownWhite, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (w.place.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOn, null, tint = CrownDim, modifier = Modifier.size(10.dp))
                                Text(w.place, color = CrownDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text(if (!permitted) "Tap to allow location" else "Getting forecast…", color = CrownDim, fontSize = 10.sp, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.35f)))
                Row(Modifier.weight(0.8f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(NumberFormat.getIntegerInstance().format(steps), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        Text("Steps", color = CrownDim, fontSize = 11.sp)
                    }
                    StepBars(week, Modifier.width(44.dp).height(24.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */
/*  Screen                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

@Composable
fun CrownHome(vm: LauncherViewModel, onAssistant: () -> Unit, modifier: Modifier = Modifier) {
    val apps = remember(vm.apps, vm.hidden) {
        vm.apps.filter { it.pkg !in vm.hidden }
            .distinctBy { it.pkg + "/" + it.activity }
            .sortedWith(compareBy<AppEntry> { crownRank(it) }.thenBy { it.shownLabel.lowercase() })
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        // the crown scene, right side, fading into black on its left and top edges
        Box(Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.76f).fillMaxWidth(0.60f)) {
            Image(painterResource(R.drawable.neo_crown_scene), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), endX = 140f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent), endY = 260f)))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tile = 46.dp
            val tabW = (maxWidth * 0.40f).coerceIn(130.dp, 190.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Spacer(Modifier.height(30.dp))   // room for the status bar
                CrownPanels(Modifier.fillMaxWidth().height(118.dp))
                Spacer(Modifier.height(14.dp))
                LazyColumn(Modifier.fillMaxWidth(0.62f).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps, key = { it.pkg + "/" + it.activity }) { app -> CrownRow(app, vm, tile, tabW) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            // the assistant stays one tap away (no drawer button anywhere on this screen)
            AssistantOrb(30.dp, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp).noRipple { onAssistant() })
        }
    }
}
