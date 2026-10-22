@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neo.plus.iosluancher

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

/** Android's own status bar height, read from the system resources (used when the live inset is 0 because the bar is hidden). */
@Composable
private fun systemStatusBarPx(): Int {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }
}

/** Status bar area with the real Android size, even while the system bar is hidden. */
@Composable
fun statusInsets(): WindowInsets =
    WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets(top = systemStatusBarPx()))

/**
 * Insets used by every screen: always the phone's own system sizes (status bar, navigation or gesture bar, camera cut-out,
 * even when a bar is hidden), so the launcher fits every Android phone without fixed margins.
 */
@Composable
fun safeInsets(): WindowInsets =
    statusInsets().union(WindowInsets.systemBarsIgnoringVisibility).union(WindowInsets.navigationBars)

/** Signal level (0-4) of every SIM slot: -1 = empty slot. One entry on a single-SIM phone, two on a dual-SIM phone. */
object SimSignals {
    private fun level(tm: TelephonyManager?): Int = try { (tm?.signalStrength?.level ?: 0).coerceIn(0, 4) } catch (e: Throwable) { 0 }

    fun read(ctx: Context): List<Int> {
        val tm = try { ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager } catch (e: Exception) { return emptyList() }
        val slots = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) tm.activeModemCount else @Suppress("DEPRECATION") tm.phoneCount
        } catch (e: Throwable) { 1 }.coerceIn(1, 2)
        val out = MutableList(slots) { -1 }
        try {
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                for (sub in sm.activeSubscriptionInfoList.orEmpty()) {
                    val slot = sub.simSlotIndex
                    if (slot in 0 until slots) out[slot] = level(tm.createForSubscriptionId(sub.subscriptionId))
                }
            }
        } catch (e: Throwable) { }
        if (out.all { it < 0 }) {
            // The subscription list was unavailable: fall back to the main SIM.
            val ready = try { tm.simState == TelephonyManager.SIM_STATE_READY } catch (e: Throwable) { false }
            if (!ready) return emptyList()
            out[0] = level(tm)
        }
        return out
    }
}

private data class PhoneInfo(
    val sims: List<Int>, val type: String,
    val airplane: Boolean, val wifi: Boolean, val dataOn: Boolean
)

private fun readPhone(ctx: Context): PhoneInfo {
    val tm = try { ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager } catch (e: Exception) { null }
    val air = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (e: Exception) { false }
    val sims = SimSignals.read(ctx)
    val dataOn = try { tm?.isDataEnabled == true } catch (e: Throwable) { false }
    val type = try {
        when (tm?.dataNetworkType) {
            20 -> "5G"
            13 -> "LTE"
            15, 10, 8, 9, 3 -> "3G"
            1, 2 -> "E"
            else -> ""
        }
    } catch (e: Throwable) { "" }
    val wifi = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) { false }
    return PhoneInfo(sims, type, air, wifi, dataOn)
}

/** Signal bars: one row per SIM. A dual-SIM phone shows two stacked rows (SIM 1 on top, SIM 2 below), like an iPhone. */
@Composable
private fun SignalBars(levels: List<Int>) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp, 12.dp)) {
        val rows = levels.size.coerceIn(1, 2)
        val gapY = if (rows == 2) 1.5.dp.toPx() else 0f
        val rowH = (size.height - gapY * (rows - 1)) / rows
        val bw = (if (rows == 2) 2.8 else 3.0).dp.toPx()
        val gap = 1.6.dp.toPx()
        for (r in 0 until rows) {
            val lvl = levels.getOrElse(r) { -1 }
            val top = r * (rowH + gapY)
            for (i in 0..3) {
                val h = rowH * (0.38f + 0.2066f * i)
                drawRoundRect(
                    Color.White.copy(alpha = if (i < lvl) 1f else 0.35f),
                    Offset(i * (bw + gap), top + rowH - h), Size(bw, h), CornerRadius(if (rows == 2) 0.8.dp.toPx() else 1.dp.toPx())
                )
            }
        }
    }
}

/** Android battery: outlined body with a level fill and a small terminal; the percentage is written beside it. */
@Composable
private fun AndroidBattery(pct: Int, charging: Boolean, lowPower: Boolean) {
    val fill = when {
        charging -> Color(0xFF34C759)
        lowPower -> Color(0xFFFFD60A)
        pct <= 15 -> Color(0xFFFF453A)
        else -> Color.White
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$pct%", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, style = ShadowStyle)
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp, 10.dp).clip(RoundedCornerShape(2.5.dp))
                    .background(Color.White.copy(alpha = 0.30f)).padding(1.2.dp)
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(5, 100) / 100f)
                        .clip(RoundedCornerShape(1.5.dp)).background(fill)
                )
            }
            Box(
                Modifier.padding(start = 1.dp).size(1.8.dp, 4.dp)
                    .clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)).background(Color.White.copy(alpha = 0.6f))
            )
        }
    }
}

/** Asks Android to open its own notification shade (or quick settings). Needs EXPAND_STATUS_BAR, which the app declares. */
fun expandAndroidPanel(ctx: Context, quickSettings: Boolean) {
    try {
        val sbm = ctx.getSystemService("statusbar")
        val name = if (quickSettings) "expandSettingsPanel" else "expandNotificationsPanel"
        sbm.javaClass.getMethod(name).invoke(sbm)
    } catch (e: Throwable) {
        try {
            val sbm = ctx.getSystemService("statusbar")
            sbm.javaClass.getMethod("expand").invoke(sbm)
        } catch (e2: Throwable) { }
    }
}

/**
 * The launcher's own status bar, laid out like a normal Android one: clock on the left, SIM signal / data type / Wi-Fi /
 * battery with % on the right, over a soft dark fade so it reads on any wallpaper. Android's real bar stays hidden.
 * Swiping down on it opens Android's own notification shade (left half) or quick settings (right half).
 */
@Composable
fun IosStatusBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var time by remember { mutableStateOf("") }
    var info by remember { mutableStateOf(readPhone(ctx)) }
    var pct by remember { mutableStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    var lowPower by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        fun readBattery(i: Intent?) {
            if (i == null) return
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) pct = lvl * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = readBattery(i)
        }
        readBattery(ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { try { ctx.unregisterReceiver(r) } catch (e: Exception) { } }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            time = if (android.text.format.DateFormat.is24HourFormat(ctx)) "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            else "%d:%02d".format(h, c.get(Calendar.MINUTE))
            info = readPhone(ctx)
            lowPower = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode } catch (e: Exception) { false }
            delay(1000)
        }
    }

    var dragY by remember { mutableStateOf(0f) }
    Row(
        modifier.fillMaxWidth().windowInsetsTopHeight(statusInsets())
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onVerticalDrag = { change, dy ->
                        dragY += dy
                        if (dragY > 36f) {
                            dragY = -1e9f
                            expandAndroidPanel(ctx, quickSettings = change.position.x > size.width / 2f)
                        }
                    }
                )
            }
            .padding(start = 18.dp, end = 18.dp, bottom = 2.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = ShadowStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                info.airplane -> Icon(Icons.Filled.AirplanemodeActive, null, tint = Color.White, modifier = Modifier.size(15.dp))
                info.sims.isEmpty() -> Text("No SIM", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = ShadowStyle)
                else -> {
                    SignalBars(info.sims)
                    if (info.dataOn && info.type.isNotEmpty() && !info.wifi)
                        Text(info.type, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = ShadowStyle)
                }
            }
            if (info.wifi) Icon(Icons.Filled.Wifi, null, tint = Color.White, modifier = Modifier.size(16.dp))
            AndroidBattery(pct, charging, lowPower)
        }
    }
}
