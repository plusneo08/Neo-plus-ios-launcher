package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Everything Neo Plus Assistant needs to control the phone and show over other apps, enabled step by step from one button. */
object EnableAssistant {
    fun overlayOk(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun accessibilityOk(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(ctx.packageName) == true

    fun ready(ctx: Context) = overlayOk(ctx) && accessibilityOk(ctx) && DeviceAdmin.isActive(ctx)

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    /** Rooted phone: sets everything up silently. Otherwise opens the next settings page. */
    fun next(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val rooted = RootSetup.applyAll(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (rooted) toast(app, "Enabled with root. Everything is switched on.") else pages(ctx)
            }
        }.start()
    }

    /** Opens the next page that still needs a switch. Tap the button again after each page. */
    private fun pages(ctx: Context) {
        val prefs = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
        val pkg = Uri.parse("package:${ctx.packageName}")
        when {
            !overlayOk(ctx) -> {
                toast(ctx, "Turn on “Display over other apps” for NEO PLUS")
                ctx.safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            }
            !accessibilityOk(ctx) -> {
                // Android 13+ blocks accessibility for apps installed from a file until “Allow restricted settings” is chosen
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("restricted_shown", false)) {
                    prefs.edit().putBoolean("restricted_shown", true).apply()
                    toast(ctx, "Tap ⋮ (top right) > Allow restricted settings, come back and press Enable again")
                    ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                } else {
                    toast(ctx, "Turn on “NEO PLUS iOS Controls”")
                    ctx.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            !DeviceAdmin.isActive(ctx) -> ctx.safeStart(DeviceAdmin.requestIntent(ctx))
            else -> toast(ctx, "Neo Plus Assistant is enabled")
        }
    }
}

/** Shown inside the assistant panel only while something is still switched off. */
@Composable
fun EnableAssistantBanner() {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(EnableAssistant.ready(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            ready = EnableAssistant.ready(ctx)
            delay(1000)
        }
    }
    if (ready) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp)).glass(RoundedCornerShape(18.dp), 0.20f)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("Assistant not fully enabled", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Needed to control the phone and show over other apps", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        Text(
            "Enable assistant", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White)
                .noRipple { EnableAssistant.next(ctx) }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
