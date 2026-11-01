@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neo.plus.iosluancher

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.res.Configuration
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.layout.fillMaxSize
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
    val airplane: Boolean, val wifi: Boolean, val dataOn: Boolean,
    val wifiOn: Boolean = false, val bt: Boolean = false, val dnd: Boolean = false, val rotationLock: Boolean = false,
    val location: Boolean = false, val hotspot: Boolean = false, val alarm: Boolean = false,
    val ringer: Int = 2,            // 0 = silent, 1 = vibrate, 2 = normal
    val dark: Boolean = false
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
    // The other switches that are shown in the top bar while they are on
    val wifiOn = try { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (e: Throwable) { false }
    val bt = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (e: Throwable) { false }
    val dnd = try {
        val f = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Throwable) { false }
    val rotationLock = try { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0 } catch (e: Throwable) { false }
    val location = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Throwable) { false }
    val hotspot = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any {
            (it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name == "wlan1") && it.isUp && it.inetAddresses.hasMoreElements()
        }
    } catch (e: Throwable) { false }
    val alarm = try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock != null } catch (e: Throwable) { false }
    val ringer = try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode } catch (e: Throwable) { AudioManager.RINGER_MODE_NORMAL }
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return PhoneInfo(
        sims, type, air, wifi, dataOn, wifiOn, bt, dnd, rotationLock, location, hotspot, alarm,
        when (ringer) { AudioManager.RINGER_MODE_SILENT -> 0; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 2 }, dark
    )
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

/** A frosted capsule behind a group of status icons. */
private fun Modifier.glass(): Modifier =
    this.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))

/** A status icon that grows in when its switch is turned on and shrinks away when it is turned off. */
@Composable
private fun RowScope.Indicator(on: Boolean, icon: ImageVector, tint: Color = Color.White, alpha: Float = 1f) {
    AnimatedVisibility(
        on,
        enter = fadeIn() + scaleIn(initialScale = 0.4f) + expandHorizontally(),
        exit = fadeOut() + scaleOut(targetScale = 0.4f) + shrinkHorizontally()
    ) {
        Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.padding(start = 6.dp).size(14.dp))
    }
}

/** Battery as a small ring that fills with the charge (it pulses green while charging), with the percentage beside it. */
@Composable
private fun BatteryRing(pct: Int, charging: Boolean, lowPower: Boolean) {
    val color = when {
        charging -> Color(0xFF34C759)
        lowPower -> Color(0xFFFFD60A)
        pct <= 15 -> Color(0xFFFF453A)
        else -> Color.White
    }
    val pulse = if (charging) rememberInfiniteTransition(label = "charging").animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "chargingPulse"
    ).value else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(15.dp)) {
            val sw = 2.2.dp.toPx()
            val tl = Offset(sw / 2f, sw / 2f)
            val box = Size(size.width - sw, size.height - sw)
            drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, tl, box, style = Stroke(sw))
            drawArc(color.copy(alpha = pulse), -90f, 360f * pct.coerceIn(3, 100) / 100f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("$pct%", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
    }
}

/**
 * The launcher's own top bar: two frosted capsules floating over the wallpaper, so the middle stays clear for the camera
 * hole. Left: the time (the colon blinks every second) and the date. Right: SIM signal, data type, Wi-Fi and a battery ring.
 * Android's real bar stays hidden. Swiping down on it opens Android's own notification shade (left half) or quick settings
 * (right half).
 */
@Composable
fun IosStatusBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var hh by remember { mutableStateOf("") }
    var mm by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var colonOn by remember { mutableStateOf(true) }
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

    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cm = try { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager } catch (e: Exception) { null }
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
        }
        try { cm?.registerTorchCallback(cb, null) } catch (e: Exception) { }
        onDispose { try { cm?.unregisterTorchCallback(cb) } catch (e: Exception) { } }
    }

    LaunchedEffect(Unit) {
        val dayFmt = java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault())
        while (true) {
            val c = Calendar.getInstance()
            val h12 = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            if (android.text.format.DateFormat.is24HourFormat(ctx)) hh = "%02d".format(c.get(Calendar.HOUR_OF_DAY)) else hh = h12.toString()
            mm = "%02d".format(c.get(Calendar.MINUTE))
            day = dayFmt.format(c.time)
            colonOn = c.get(Calendar.SECOND) % 2 == 0
            info = readPhone(ctx)
            lowPower = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode } catch (e: Exception) { false }
            delay(1000)
        }
    }

    var dragY by remember { mutableStateOf(0f) }
    Row(
        modifier.fillMaxWidth().windowInsetsTopHeight(statusInsets())
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent)))
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
            .padding(start = 12.dp, end = 12.dp, bottom = 1.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        // left capsule: time, date, and the "mode" switches while they are on (Do Not Disturb, silent / vibrate, rotation lock, dark mode, alarm)
        val modes = listOf(info.dnd, info.ringer != 2, info.rotationLock, info.dark, info.alarm).count { it }
        Row(Modifier.height(24.dp).glass().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(hh, color = Color.White, fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(":", color = Color.White.copy(alpha = if (colonOn) 1f else 0.25f), fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(mm, color = Color.White, fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            if (modes < 3) {
                Spacer(Modifier.width(8.dp))
                Text(day, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
            }
            Indicator(info.dnd, Icons.Filled.DoNotDisturbOn)
            Indicator(info.ringer == 0, Icons.Filled.VolumeOff)
            Indicator(info.ringer == 1, Icons.Filled.Vibration)
            Indicator(info.rotationLock, Icons.Filled.ScreenLockRotation)
            Indicator(info.dark, Icons.Filled.Contrast)
            Indicator(info.alarm, Icons.Filled.Alarm)
        }
        // right capsule: every connection switch that is on, then the battery
        Row(Modifier.height(24.dp).glass().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                info.airplane -> Icon(Icons.Filled.AirplanemodeActive, null, tint = Color.White, modifier = Modifier.size(15.dp))
                info.sims.isEmpty() -> Text("No SIM", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
                else -> {
                    SignalBars(info.sims)
                    if (info.dataOn && info.type.isNotEmpty())
                        Text(info.type, color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
                }
            }
            Indicator(info.wifiOn, Icons.Filled.Wifi, alpha = if (info.wifi) 1f else 0.45f)   // dimmed while on but not connected
            Indicator(info.hotspot, Icons.Filled.WifiTethering)
            Indicator(info.bt, Icons.Filled.Bluetooth)
            Indicator(info.location, Icons.Filled.LocationOn)
            Indicator(torchOn, Icons.Filled.FlashlightOn, tint = Color(0xFFFFD60A))
            Spacer(Modifier.width(8.dp))
            BatteryRing(pct, charging, lowPower)
        }
    }
}

/** The home-screen bar itself, drawn over every other app on solid black so Android's own bar underneath is fully covered. */
@Composable
fun SystemWideStatusBar() {
    Box(Modifier.fillMaxSize().background(Color.Black)) { IosStatusBar() }
}
