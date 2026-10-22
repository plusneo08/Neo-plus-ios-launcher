package com.neo.plus.iosluancher

import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/* ---------------- system state ---------------- */

private data class Sys(
    val wifi: Boolean, val bt: Boolean, val airplane: Boolean,
    val cellular: Boolean, val dnd: Boolean, val autoRotate: Boolean,
    val dark: Boolean, val lowPower: Boolean,
    val battery: Int, val charging: Boolean,
    val wifiConnected: Boolean, val carrier: String, val alarmSet: Boolean
)

private fun readSys(ctx: Context): Sys {
    val wifi = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { false }
    val bt = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: Exception) { false }
    val air = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (e: Exception) { false }
    val cell = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).isDataEnabled && !air
    } catch (e: Exception) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (e2: Exception) { false }
    }
    val wifiConnected = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) { false }
    val dnd = try {
        val f = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) { false }
    val rot = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
    } catch (e: Exception) { true }
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val low = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode } catch (e: Exception) { false }

    var pct = 0
    var charging = false
    try {
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (lvl >= 0 && scale > 0) pct = lvl * 100 / scale
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) { }

    val carrier = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName ?: ""
    } catch (e: Exception) { "" }
    val alarm = try {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock != null
    } catch (e: Exception) { false }

    return Sys(wifi, bt, air, cell, dnd, rot, dark, low, pct, charging, wifiConnected, carrier, alarm)
}

/* ---------------- now playing (real media session, needs Notification access) ---------------- */

private data class NowPlaying(
    val title: String, val artist: String, val playing: Boolean,
    val tint: Color?, val controller: MediaController
)

private fun readNowPlaying(ctx: Context, preferred: String?): NowPlaying? {
    return try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = msm.getActiveSessions(ComponentName(ctx, NeoNotificationService::class.java)) ?: emptyList()
        val c = sessions.firstOrNull { it.packageName == preferred && it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: sessions.firstOrNull { it.packageName == preferred && it.metadata != null }
            ?: sessions.firstOrNull { it.metadata != null } ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val tint = art?.let {
            val px = Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0)
            Color(
                (android.graphics.Color.red(px) * 0.55f).toInt(),
                (android.graphics.Color.green(px) * 0.55f).toInt(),
                (android.graphics.Color.blue(px) * 0.55f).toInt()
            )
        }
        NowPlaying(title, artist, c.playbackState?.state == PlaybackState.STATE_PLAYING, tint, c)
    } catch (e: Exception) { null }
}

private fun askWriteSettings(ctx: Context) {
    ctx.safeStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

private fun mediaKey(am: AudioManager, code: Int) {
    try {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    } catch (e: Exception) { }
}

/** Launches the first installed launcher app that the matcher accepts. */
private fun launchMatching(ctx: Context, match: (label: String, pkg: String) -> Boolean): Boolean {
    return try {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(q, 0).firstOrNull {
            match(it.loadLabel(pm).toString().lowercase(), it.activityInfo.packageName.lowercase())
        } ?: return false
        ctx.safeStart(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { false }
}

/* ---------------- look of the tiles (matches the reference screenshot) ---------------- */

private val TileBg = Color(0xFF2B2B2E).copy(alpha = 0.62f)
private val CircleOff = Color(0xFF48484A).copy(alpha = 0.85f)
private val IosRed = Color(0xFFE5483F)
private val IosOrange = Color(0xFFE9692C)
private val IosBlue = Color(0xFF0A84FF)
private val IosCream = Color(0xFFF6EEDC)
private val IosIndigo = Color(0xFF5E5CE6)
private val IosYellow = Color(0xFFFFD60A)
private val Ink = Color(0xFF1C1C1E)

@Composable
private fun Module(modifier: Modifier, radius: Dp, bg: Color = TileBg, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(bg), content = content)
}

@Composable
private fun CircleBtn(
    icon: ImageVector, active: Boolean, activeColor: Color, size: Dp,
    activeIcon: Color = Color.White, onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) activeColor else CircleOff)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) activeIcon else Color.White,
            modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun TileBox(
    modifier: Modifier, radius: Dp, active: Boolean, activeBg: Color,
    onClick: () -> Unit, content: @Composable () -> Unit
) {
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(if (active) activeBg else TileBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun Tile(
    icon: ImageVector, active: Boolean, modifier: Modifier, radius: Dp,
    activeTint: Color = Ink, activeBg: Color = Color.White, onClick: () -> Unit
) {
    TileBox(modifier, radius, active, activeBg, onClick) {
        Icon(icon, contentDescription = null, tint = if (active) activeTint else Color.White,
            modifier = Modifier.size(28.dp))
    }
}

/** Horizontal battery glyph (outline + fill + nub), like the iPhone status icons. */
@Composable
private fun BatteryGlyph(pct: Int, charging: Boolean, width: Dp, tint: Color = Color.White, fill: Color? = null) {
    val h = width * 0.46f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width, h)
                .border(1.2.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(h * 0.32f))
                .padding(1.8.dp)
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(5, 100) / 100f)
                    .clip(RoundedCornerShape(h * 0.2f))
                    .background(fill ?: if (charging) Color(0xFF30D158) else tint)
            )
        }
        Box(
            Modifier.padding(start = 1.dp).size(1.8.dp, h * 0.36f)
                .clip(RoundedCornerShape(1.dp)).background(tint.copy(alpha = 0.55f))
        )
    }
}

/** Tall slider: drag or tap anywhere, light fill rises from the bottom. */
@Composable
private fun VSlider(icon: ImageVector, value: Float, modifier: Modifier, radius: Dp, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(TileBg)
            .pointerInput(Unit) {
                detectTapGestures { o -> cb((1f - o.y / size.height.toFloat()).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { o -> cb((1f - o.y / size.height.toFloat()).coerceIn(0f, 1f)) },
                    onVerticalDrag = { change, _ ->
                        cb((1f - change.position.y / size.height.toFloat()).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .fillMaxHeight(value.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.93f))
        )
        Icon(
            icon, contentDescription = null,
            tint = if (value < 0.14f) Color.White else Color(0xFF48484A),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(26.dp)
        )
    }
}

/** Carrier / battery line shown at the top of the Control Center. */
@Composable
private fun StatusHeader(sys: Sys, locked: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        Arrangement.SpaceBetween, Alignment.Top) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SignalCellularAlt, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (sys.airplane) "Airplane Mode" else sys.carrier.ifBlank { "No Service" },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            if (sys.wifiConnected) {
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Filled.Wifi, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sys.alarmSet) Icon(Icons.Filled.Alarm, null, tint = Color.White, modifier = Modifier.size(15.dp))
                if (locked) Icon(Icons.Filled.ScreenLockRotation, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/* ---------------- Control Center ---------------- */

@Composable
fun ControlCenter(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val closeCb by rememberUpdatedState(onClose)

    var sys by remember { mutableStateOf(readSys(ctx)) }
    var now by remember { mutableStateOf<NowPlaying?>(null) }
    var selPkg by remember { mutableStateOf(MusicPlayers.selected(ctx)) }
    var players by remember { mutableStateOf<List<MusicPlayers.Player>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { players = withContext(Dispatchers.Default) { MusicPlayers.list(ctx) } }
    LaunchedEffect(Unit) {
        while (true) {
            sys = readSys(ctx)
            now = readNowPlaying(ctx, selPkg)
            delay(1000)
        }
    }
    val selPlayer = players.firstOrNull { it.pkg == selPkg }

    // Flashlight (Camera2 torch, no permission needed)
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
        }
        try { cm.registerTorchCallback(cb, null) } catch (e: Exception) { }
        onDispose { try { cm.unregisterTorchCallback(cb) } catch (e: Exception) { } }
    }

    // Volume
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    // The slider is the 0-200 % scale: the first half is the phone's volume, the second half is the extra boost.
    var vol by remember {
        VolumeBoost.load(ctx)
        mutableFloatStateOf(
            if (VolumeBoost.percent > 0) 0.5f + VolumeBoost.percent / 200f
            else am.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat() / 2f
        )
    }

    // Brightness
    var bright by remember {
        mutableFloatStateOf(
            try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }
            catch (e: Exception) { 0.5f }
        )
    }
    var askedWrite by remember { mutableStateOf(false) }

    fun open(intent: Intent) { ctx.safeStart(intent); onClose() }

    fun openOrToast(ok: Boolean, msg: String) {
        if (ok) onClose() else Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun toggleFocus() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                if (sys.dnd) NotificationManager.INTERRUPTION_FILTER_ALL
                else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        } else open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun toggleRotationLock() {
        if (Settings.System.canWrite(ctx)) {
            try {
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                    if (sys.autoRotate) 0 else 1)
            } catch (e: Exception) { }
        } else { askWriteSettings(ctx); onClose() }
    }

    fun toggleTorch() {
        try {
            val id = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(id, !torchOn)
        } catch (e: Exception) { }
    }

    fun toggleScreenRecording() {
        if (RecorderState.recording) {
            ScreenRecordService.stop(ctx)
        } else {
            ctx.safeStart(Intent(ctx, RecorderPermissionActivity::class.java))
            onClose()
        }
    }

    fun openNotes() {
        val ok = ctx.safeStart(Intent("android.intent.action.CREATE_NOTE")) ||
            launchMatching(ctx) { label, _ -> label == "notes" || label == "note" || "keep" in label || "notepad" in label }
        openOrToast(ok, "No notes app found on this phone")
    }

    fun openQrScanner() {
        val ok = ctx.safeStart(Intent("com.google.zxing.client.android.SCAN")) ||
            launchMatching(ctx) { label, _ -> "qr" in label || label == "google lens" } ||
            ctx.safeStart(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        openOrToast(ok, "No QR scanner or camera app found")
    }

    fun media(code: Int) {
        val ctl = now?.controller
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && ctl == null) {
            val p = selPkg
            if (p == null) showPicker = true else MusicPlayers.play(ctx, p)
            return
        }
        if (ctl != null) {
            try {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ctl.transportControls.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> ctl.transportControls.skipToNext()
                    else -> if (now?.playing == true) ctl.transportControls.pause()
                        else if (selPkg != null && ctl.packageName != selPkg && now?.playing != true) MusicPlayers.play(ctx, selPkg!!, null)
                        else ctl.transportControls.play()
                }
                return
            } catch (e: Exception) { }
        }
        mediaKey(am, code)
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f))
            .consumeTouches()
            .pointerInput(Unit) {
                // swipe down anywhere on the dimmed area closes it
                var t = 0f
                detectVerticalDragGestures(
                    onDragStart = { t = 0f },
                    onVerticalDrag = { _, dy ->
                        t += dy
                        if (t > 140f) { t = -1e9f; closeCb() }
                    }
                )
            }
            .noRipple(onClose)
            .windowInsetsPadding(safeInsets())
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val gap = 12.dp
            val side = 22.dp
            val cell = (maxWidth - side * 2 - gap * 3) / 4
            val wide = cell * 2 + gap
            val circle = cell * 0.82f
            val rTile = cell * 0.30f
            val rModule = cell * 0.36f

            Column(
                Modifier.width(maxWidth - side * 2).verticalScroll(rememberScrollState())
                    .noRipple { }.padding(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                StatusHeader(sys, locked = !sys.autoRotate)

                // Row 1: connectivity + now playing
                Row(Modifier.fillMaxWidth().height(wide), Arrangement.spacedBy(gap)) {
                    Module(Modifier.weight(1f).fillMaxHeight(), rModule) {
                        Column(Modifier.fillMaxSize().padding(10.dp), Arrangement.SpaceEvenly) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                CircleBtn(Icons.Filled.AirplanemodeActive, sys.airplane, IosRed, circle) {
                                    Toggles.setAirplane(ctx, !sys.airplane)
                                }
                                CircleBtn(Icons.Filled.CellTower, sys.cellular, IosOrange, circle) {
                                    Toggles.setMobileData(ctx, !sys.cellular)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                                CircleBtn(Icons.Filled.Wifi, sys.wifi, IosBlue, circle) {
                                    Toggles.setWifi(ctx, !sys.wifi)
                                }
                                CircleBtn(Icons.Filled.Bluetooth, sys.bt, IosCream, circle, activeIcon = Color(0xFF48484A)) {
                                    Toggles.setBluetooth(ctx, !sys.bt)
                                }
                            }
                        }
                    }
                    Module(Modifier.weight(1f).fillMaxHeight(), rModule, bg = now?.tint?.copy(alpha = 0.78f) ?: TileBg) {
                        Column(Modifier.fillMaxSize().padding(12.dp), Arrangement.SpaceBetween) {
                            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                                if (selPlayer != null) {
                                    Image(selPlayer.icon, "Music player",
                                        Modifier.size(24.dp).clip(Squircle).clickable { showPicker = true })
                                } else {
                                    Icon(Icons.Filled.LibraryMusic, null, tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(22.dp).clickable { showPicker = true })
                                }
                            }
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(now?.title ?: "Not Playing", color = Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                if (now == null) {
                                    Text(selPlayer?.label ?: "Choose a player", color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                }
                                if (!now?.artist.isNullOrBlank()) {
                                    Text(now?.artist ?: "", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                                Icon(Icons.Filled.FastRewind, null, tint = Color.White,
                                    modifier = Modifier.size(32.dp).clickable { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
                                Icon(if (now?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp).clickable { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) })
                                Icon(Icons.Filled.FastForward, null, tint = Color.White,
                                    modifier = Modifier.size(32.dp).clickable { media(KeyEvent.KEYCODE_MEDIA_NEXT) })
                            }
                        }
                    }
                }

                // Row 2: orientation lock, mirroring, focus | brightness | volume
                Row(Modifier.fillMaxWidth().height(wide), Arrangement.spacedBy(gap)) {
                    Column(Modifier.width(wide).fillMaxHeight(), Arrangement.spacedBy(gap)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
                            Tile(Icons.Filled.ScreenLockRotation, !sys.autoRotate, Modifier.size(cell), rTile,
                                activeTint = Color(0xFFFF453A)) { toggleRotationLock() }
                            Tile(Icons.Filled.FlipToFront, false, Modifier.size(cell), rTile) {
                                open(Intent(Settings.ACTION_CAST_SETTINGS))
                            }
                        }
                        Row(
                            Modifier.width(wide).height(cell).clip(RoundedCornerShape(rModule))
                                .background(if (sys.dnd) IosIndigo.copy(alpha = 0.85f) else TileBg)
                                .clickable { toggleFocus() }.padding(horizontal = cell * 0.15f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(cell * 0.70f).clip(CircleShape)
                                    .background(Color.White.copy(alpha = if (sys.dnd) 0.92f else 0.22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.NightsStay, null,
                                    tint = if (sys.dnd) IosIndigo else Color.White, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Focus", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    VSlider(Icons.Filled.BrightnessHigh, bright, Modifier.width(cell).fillMaxHeight(), rModule) { v ->
                        bright = v
                        if (Settings.System.canWrite(ctx)) {
                            try {
                                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                                    (v * 255).roundToInt().coerceIn(1, 255))
                            } catch (e: Exception) { }
                        } else if (!askedWrite) {
                            askedWrite = true
                            askWriteSettings(ctx)
                            onClose()
                        }
                    }
                    VSlider(
                        if (vol <= 0.01f) Icons.Filled.VolumeOff else if (vol < 0.25f) Icons.Filled.VolumeDown else Icons.Filled.VolumeUp,
                        vol, Modifier.width(cell).fillMaxHeight(), rModule
                    ) { v ->
                        vol = v
                        val phonePart = (v * 2f).coerceIn(0f, 1f)
                        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, (phonePart * maxVol).roundToInt(), 0) }
                        catch (e: Exception) { }
                        VolumeBoost.set(ctx, if (v > 0.5f) ((v - 0.5f) * 200f).roundToInt() else 0)
                    }
                }

                // Row 3: flashlight, timer, calculator, camera
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
                    Tile(Icons.Filled.FlashlightOn, torchOn, Modifier.size(cell), rTile) { toggleTorch() }
                    Tile(Icons.Filled.Timer, false, Modifier.size(cell), rTile) { open(Intent(AlarmClock.ACTION_SHOW_TIMERS)) }
                    Tile(Icons.Filled.Calculate, false, Modifier.size(cell), rTile) {
                        open(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
                    }
                    Tile(Icons.Filled.CameraAlt, false, Modifier.size(cell), rTile) {
                        open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    }
                }

                // Row 4: screen recording, notes, QR scanner, dark mode
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
                    TileBox(Modifier.size(cell), rTile, RecorderState.recording, IosRed, { toggleScreenRecording() }) {
                        Box(
                            Modifier.size(28.dp).border(2.dp, Color.White, CircleShape)
                                .padding(5.dp).clip(CircleShape).background(Color.White)
                        )
                    }
                    Tile(Icons.Filled.NoteAdd, false, Modifier.size(cell), rTile) { openNotes() }
                    Tile(Icons.Filled.QrCodeScanner, false, Modifier.size(cell), rTile) { openQrScanner() }
                    Tile(Icons.Filled.Contrast, sys.dark, Modifier.size(cell), rTile) { Toggles.setDarkMode(ctx, !sys.dark) }
                }

                // Row 5: low power mode
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
                    TileBox(Modifier.size(cell), rTile, sys.lowPower, IosYellow, { Toggles.setLowPower(ctx, !sys.lowPower) }) {
                        BatteryGlyph(
                            pct = 40, charging = false, width = 30.dp,
                            tint = if (sys.lowPower) Ink else Color.White
                        )
                    }
                }
            }
        }
        if (showPicker) {
            PlayerPicker(
                players, selPkg,
                onPick = { p ->
                    MusicPlayers.select(ctx, p.pkg)
                    selPkg = p.pkg
                    showPicker = false
                    MusicPlayers.play(ctx, p.pkg, now?.controller)
                },
                onDismiss = { showPicker = false }
            )
        }
    }
}

/** Sheet listing the installed music apps; the chosen one is used by the Control Center player. */
@Composable
private fun PlayerPicker(
    players: List<MusicPlayers.Player>, selected: String?,
    onPick: (MusicPlayers.Player) -> Unit, onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).noRipple(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f).heightIn(max = 480.dp)
                .clip(RoundedCornerShape(28.dp)).background(Color(0xFF2C2C2E).copy(alpha = 0.97f))
                .noRipple { }.padding(vertical = 14.dp)
        ) {
            Text("Choose Music Player", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (players.isEmpty()) {
                Text("No music apps found", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                players.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(p.icon, p.label, Modifier.size(42.dp).clip(Squircle))
                        Spacer(Modifier.width(14.dp))
                        Text(p.label, color = Color.White, fontSize = 16.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (p.pkg == selected) Icon(Icons.Filled.Check, null, tint = IosBlue, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
