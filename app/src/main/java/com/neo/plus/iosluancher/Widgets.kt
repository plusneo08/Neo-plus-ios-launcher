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
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(24.dp))) {
            AndroidView(
                factory = { c -> wc.host.createView(c, id, info) },
                modifier = Modifier.fillMaxSize()
            )
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { wc.remove(id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun WidgetPicker(wc: WidgetController, onClose: () -> Unit) {
    val pm = LocalContext.current.packageManager
    val providers = remember { wc.providers() }
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .consumeTouches()
            .windowInsetsPadding(safeInsets()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Add Widget", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Done", color = Color(0xFF7DB8FF), fontSize = 17.sp,
                modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(providers) { p ->
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.2f)
                        .clickable { wc.add(p); onClose() }.padding(14.dp)
                ) {
                    Text(p.loadLabel(pm) ?: "Widget", color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(p.provider.packageName, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
        }
    }
}
