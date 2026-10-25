package com.neo.plus.iosluancher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  Built-in iOS-style widgets                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun WidgetCard(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .glass(RoundedCornerShape(24.dp), 0.30f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content
    )
}

@Composable
fun ClockWidget(modifier: Modifier) {
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5000)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
    WidgetCard(modifier, onClick = { ctx.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.neo_icon), "NEO PLUS", Modifier.size(22.dp).clip(Squircle))
            Spacer(Modifier.size(7.dp))
            Text("NEO PLUS", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
            color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, style = ShadowStyle)
        Text(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(now)),
            color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
    }
}

@Composable
fun CalendarWidget(modifier: Modifier) {
    val ctx = LocalContext.current
    val now = remember { Date() }
    WidgetCard(modifier, onClick = {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(System.currentTimeMillis().toString()).build()
        ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
    }) {
        Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(now).uppercase(),
            color = Color(0xFFFF7B7B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(SimpleDateFormat("d", Locale.getDefault()).format(now),
            color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light, style = ShadowStyle)
        Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now),
            color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
    }
}

@Composable
private fun rememberBattery(): Pair<Int, Boolean> {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(0 to false) }
    DisposableEffect(Unit) {
        fun parse(i: Intent): Pair<Int, Boolean> {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return (level * 100 / scale) to charging
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i != null) state = parse(i)
            }
        }
        val sticky = ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
        if (sticky != null) state = parse(sticky)
        onDispose { try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { } }
    }
    return state
}

@Composable
fun BatteryWidget(modifier: Modifier) {
    val (pct, charging) = rememberBattery()
    WidgetCard(modifier) {
        Text("BATTERY", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(78.dp)) {
                val stroke = 9.dp.toPx()
                val tl = Offset(stroke / 2, stroke / 2)
                val sz = Size(size.width - stroke, size.height - stroke)
                drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                val c = if (pct <= 20 && !charging) Color(0xFFFF6B6B) else Color(0xFF5CE08A)
                drawArc(c, -90f, 360f * pct / 100f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("$pct%", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Text(if (charging) "Charging" else "On battery", color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
fun StorageWidget(modifier: Modifier) {
    val (used, total) = remember {
        try {
            val s = StatFs(Environment.getDataDirectory().path)
            (s.totalBytes - s.availableBytes) to s.totalBytes
        } catch (e: Exception) {
            0L to 1L
        }
    }
    val frac = (used.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    fun gb(b: Long) = String.format(Locale.getDefault(), "%.0f GB", b / 1_073_741_824.0)
    WidgetCard(modifier) {
        Text("STORAGE", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(gb(used), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light, style = ShadowStyle)
        Text("of ${gb(total)} used", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Real Android widgets (AppWidgetHost)                               */
/* ------------------------------------------------------------------ */

class WidgetController(private val activity: ComponentActivity) {
    companion object {
        const val HOST_ID = 1024
        const val REQ_BIND = 7101
        const val REQ_CONFIG = 7102
    }

    private val prefs = activity.getSharedPreferences("neo_widgets", Context.MODE_PRIVATE)
    val host = AppWidgetHost(activity, HOST_ID)
    val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val ids = mutableStateListOf<Int>()
    private var pendingId = -1

    init {
        (prefs.getString("ids", "") ?: "").split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { manager.getAppWidgetInfo(it) != null }
            .forEach { ids.add(it) }
        save()
    }

    fun start() { try { host.startListening() } catch (e: Exception) { } }
    fun stop() { try { host.stopListening() } catch (e: Exception) { } }

    fun providers(): List<AppWidgetProviderInfo> {
        val pm = activity.packageManager
        return manager.installedProviders.sortedBy { (it.loadLabel(pm) ?: "").lowercase() }
    }

    private fun save() {
        prefs.edit().putString("ids", ids.joinToString(",")).apply()
    }

    fun add(p: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        if (manager.bindAppWidgetIdIfAllowed(id, p.provider)) {
            configureOrFinish(id)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(i, REQ_BIND)
        }
    }

    private fun configureOrFinish(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIG, null)
            } catch (e: Exception) {
                finish(id)
            }
        } else {
            finish(id)
        }
    }

    private fun finish(id: Int) {
        if (!ids.contains(id)) ids.add(id)
        save()
        pendingId = -1
    }

    private fun cancel(id: Int) {
        if (id != -1) host.deleteAppWidgetId(id)
        pendingId = -1
    }

    fun remove(id: Int) {
        host.deleteAppWidgetId(id)
        ids.remove(id)
        save()
    }

    /** Returns true if the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId) ?: pendingId
        return when (requestCode) {
            REQ_BIND -> {
                if (resultCode == Activity.RESULT_OK) configureOrFinish(id) else cancel(id)
                true
            }
            REQ_CONFIG -> {
                if (resultCode == Activity.RESULT_OK) finish(id) else cancel(id)
                true
            }
            else -> false
        }
    }
}

@Composable
fun HostedWidgets(wc: WidgetController, editing: Boolean) {
    val density = LocalDensity.current
    wc.ids.toList().forEach { id ->
        val info = wc.manager.getAppWidgetInfo(id) ?: return@forEach
        val h = with(density) { info.minHeight.toDp() }.coerceIn(80.dp, 360.dp)
        Box(Modifier.fillMaxWidth().height(h).jiggle(editing, id % 3).clip(RoundedCornerShape(24.dp))) {
            AndroidView(
                factory = { c -> wc.host.createView(c, id, info) },
                modifier = Modifier.fillMaxSize()
            )
            if (editing) {
                // iOS: a red "−" badge on the corner removes the widget
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(Color(0xFFFF3B30)).clickable { wc.remove(id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(width = 12.dp, height = 2.5.dp).clip(RoundedCornerShape(50)).background(Color.White))
                }
            }
        }
    }
}

/** iOS edit mode: widgets wiggle slightly while you are editing. */
@Composable
fun Modifier.jiggle(on: Boolean, phase: Int = 0): Modifier {
    if (!on) return this
    val t = rememberInfiniteTransition(label = "jiggle")
    val deg by t.animateFloat(
        initialValue = -1.2f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(130 + phase * 25, easing = LinearEasing), RepeatMode.Reverse),
        label = "deg"
    )
    return this.graphicsLayer { rotationZ = deg }
}

private class WidgetApp(val pkg: String, val name: String, val providers: List<AppWidgetProviderInfo>)

private val SheetBg = Color(0xFF1C1C1E)
private val FieldBg = Color(0xFF2C2C2E)
private val IosBlue = Color(0xFF0A84FF)

/** iOS-style widget gallery: a sheet with search, apps that have widgets, then a swipeable preview with "Add Widget". */
@Composable
fun WidgetPicker(wc: WidgetController, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val apps = remember {
        wc.providers().groupBy { it.provider.packageName }.map { (pkg, list) ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            WidgetApp(pkg, name, list)
        }.sortedBy { it.name.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<WidgetApp?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(SheetBg.copy(alpha = 0.98f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }   // taps on the sheet do not close it
                .padding(horizontal = 16.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 10.dp)
                .size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))

            val a = open
            if (a == null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Widgets", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier.align(Alignment.CenterEnd).size(30.dp).clip(CircleShape).background(FieldBg).clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Close, "Close", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)) }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FieldBg).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search Widgets", color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(IosBlue), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val shown = apps.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { app ->
                        val icon = remember(app.pkg) {
                            try { renderIcon(pm.getApplicationIcon(app.pkg), 120).asImageBitmap() } catch (e: Exception) { null }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { open = app }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (icon != null) Image(icon, app.name, Modifier.size(44.dp).clip(Squircle))
                            else Box(Modifier.size(44.dp).clip(Squircle).background(FieldBg))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    if (app.providers.size == 1) (app.providers[0].loadLabel(pm) ?: "1 widget") else "${app.providers.size} widgets",
                                    color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1
                                )
                            }
                            Text("›", color = Color.White.copy(alpha = 0.35f), fontSize = 22.sp)
                        }
                    }
                }
            } else {
                WidgetDetail(a, wc, onBack = { open = null }, onAdded = onClose)
            }
        }
    }
}

private fun sizeName(p: AppWidgetProviderInfo): String {
    val cols = ((p.minWidth + 30) / 70).coerceAtLeast(1)
    val rows = ((p.minHeight + 30) / 70).coerceAtLeast(1)
    val name = if (cols >= 3 && rows >= 3) "Large" else if (cols >= 3) "Medium" else "Small"
    return "$name · $cols×$rows"
}

@Composable
private fun ColumnScope.WidgetDetail(app: WidgetApp, wc: WidgetController, onBack: () -> Unit, onAdded: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val pager = rememberPagerState { app.providers.size }
    val current = app.providers[pager.currentPage.coerceIn(0, app.providers.size - 1)]

    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.CenterStart) {
        Text("‹ Widgets", color = IosBlue, fontSize = 17.sp, modifier = Modifier.clickable { onBack() }.padding(vertical = 6.dp, horizontal = 2.dp))
    }
    Text(app.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(16.dp))

    HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 28.dp), pageSpacing = 14.dp) { i ->
        val p = app.providers[i]
        val preview = remember(p) {
            try { p.loadPreviewImage(ctx, 0)?.toBitmap()?.asImageBitmap() } catch (e: Exception) { null }
        }
        val fallback = remember(p) {
            try { renderIcon(pm.getApplicationIcon(p.provider.packageName), 160).asImageBitmap() } catch (e: Exception) { null }
        }
        Box(
            Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(24.dp)).background(FieldBg).padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) Image(preview, p.loadLabel(pm), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (fallback != null) Image(fallback, p.loadLabel(pm), Modifier.size(72.dp).clip(Squircle))
        }
    }

    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(current.loadLabel(pm) ?: "Widget", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(sizeName(current), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
        val desc = if (android.os.Build.VERSION.SDK_INT >= 31) try { current.loadDescription(ctx)?.toString() } catch (e: Exception) { null } else null
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
        }
        if (app.providers.size > 1) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(app.providers.size) { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.9f else 0.28f)))
                }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Box(
        Modifier.fillMaxWidth().padding(bottom = 22.dp).height(50.dp).clip(RoundedCornerShape(50)).background(IosBlue)
            .clickable { wc.add(current); onAdded() },
        contentAlignment = Alignment.Center
    ) { Text("Add Widget", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}
