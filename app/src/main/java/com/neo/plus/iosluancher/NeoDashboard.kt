package com.neo.plus.iosluancher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Cyan = Color(0xFF22E5FF)
private val Green = Color(0xFF30D158)
private val Amber = Color(0xFFFFC828)
private val Red = Color(0xFFFF453A)
private val PanelBg = Color(0xCC061A2E)

/** Live numbers read from the phone itself (nothing here is made up). */
private class Stats(val battery: Int, val charging: Boolean, val ramPct: Int, val diskPct: Int, val net: String)

private fun readStats(ctx: Context): Stats {
    var bat = 0; var chg = false
    try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (lvl >= 0 && scale > 0) bat = lvl * 100 / scale
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) { }
    var ram = 0
    try {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        if (mi.totalMem > 0) ram = (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt()
    } catch (e: Exception) { }
    var disk = 0
    try {
        val s = StatFs(Environment.getDataDirectory().path)
        if (s.blockCountLong > 0) disk = (((s.blockCountLong - s.availableBlocksLong) * 100) / s.blockCountLong).toInt()
    } catch (e: Exception) { }
    var net = "Offline"
    try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null) net = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    } catch (e: Exception) { }
    return Stats(bat, chg, ram, disk, net)
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(title, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, tint: Color = Green) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .border(1.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RingGauge(label: String, pct: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 7.dp.toPx()
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(Cyan.copy(alpha = 0.15f), 0f, 360f, false, Offset(sw / 2, sw / 2), sz, style = Stroke(sw))
                drawArc(Cyan, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, Offset(sw / 2, sw / 2), sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                Text("$pct%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The Neo Plus command center: the page after the assistant chat (swipe left). Every panel shows live data from this phone
 * and from the assistant itself.
 */
@Composable
fun NeoDashboard(a: AssistantViewModel, apps: List<AppEntry>, onSent: () -> Unit) {
    val ctx = LocalContext.current
    var stats by remember { mutableStateOf(readStats(ctx)) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            stats = readStats(ctx)
            delay(1000)
        }
    }
    val spin = rememberInfiniteTransition(label = "spin")
    val ang by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val ang2 by spin.animateFloat(360f, 0f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "b")
    val tasks = TaskLog.visible()
    val running = tasks.count { it.active }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // header: status + clock
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (a.configured) "SYSTEM STATUS · OPTIMAL" else "ADD A GEMINI KEY", color = if (a.configured) Green else Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(dateFmt.format(now), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            Text(timeFmt.format(now), color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        // centre: rotating rings around the Neo Plus logo
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Cyan.copy(alpha = 0.10f), radius = size.minDimension / 2)
                drawCircle(Cyan.copy(alpha = 0.35f), radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 3) {
                    val inset = 12.dp.toPx()
                    drawArc(Cyan, ang + i * 120f, 70f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                }
                for (i in 0 until 6) {
                    val inset = 34.dp.toPx()
                    drawArc(Cyan.copy(alpha = 0.7f), ang2 + i * 60f, 30f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
                drawCircle(Color(0xFF031322), radius = 48.dp.toPx(), center = c)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.neo_icon), null, Modifier.size(64.dp))
                Text("NEO PLUS", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Text("AI CORE · v3.11.1", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 2.sp)
            }
        }

        Panel("AI CORE OVERVIEW") {
            InfoRow("AI Core", if (a.configured) "Active · Gemini" else "Needs a Gemini key", if (a.configured) Green else Amber)
            InfoRow("Voice", if (Speaker.speaking) "Speaking" else "Ready", if (Speaker.speaking) Cyan else Green)
            InfoRow("Tasks", "$running running · ${tasks.size} listed", if (running > 0) Cyan else Green)
            InfoRow("Chat", "${a.messages.size} messages" + if (a.busy) " · thinking" else "", if (a.busy) Cyan else Green)
            InfoRow("Connection", stats.net, if (stats.net == "Offline") Red else Green)
        }

        Panel("ACTIVE TASKS") {
            if (tasks.isEmpty()) Text("No tasks yet. Ask me to do something on a website or in an app.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            tasks.takeLast(6).reversed().forEach { f ->
                InfoRow(
                    f.title.ifBlank { "Task ${f.id}" }.take(32),
                    if (f.active) (f.status.ifBlank { "Working" }).take(24) else f.status.ifBlank { "Done" }.take(24),
                    if (f.active) Cyan else if (f.status == "Finished") Green else Red
                )
            }
        }

        Panel("LIVE INTELLIGENCE FEED") {
            val lines = TaskLog.focus?.lines?.takeLast(8)?.map { it.time + "  " + it.text } ?: emptyList()
            val feed = if (lines.isNotEmpty()) lines else HudState.history.takeLast(8)
            if (feed.isEmpty()) Text("Waiting for activity…", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            feed.reversed().forEach { Text("› $it", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp)) }
        }

        Panel("SYSTEM MONITOR") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingGauge("BATTERY", stats.battery)
                RingGauge("RAM", stats.ramPct)
                RingGauge("STORAGE", stats.diskPct)
            }
            if (stats.charging) Text("Charging", color = Green, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(), textAlign = TextAlign.Center)
        }

        Panel("QUICK COMMANDS") {
            listOf(
                "What is on my calendar today?",
                "What is the weather today?",
                "Read my notifications",
                "Show the status of my tasks",
                "Take a screenshot"
            ).forEach { cmd ->
                Text(
                    cmd, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Cyan.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .clickable { a.send(cmd, apps); onSent() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
