package com.neo.plus.iosluancher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Frosted-glass panel that lets the wallpaper show through. */
fun Modifier.glass(shape: Shape, alpha: Float = 0.26f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = alpha + 0.10f), Color.White.copy(alpha = alpha - 0.04f))
        )
    )
    .border(0.6.dp, Color.White.copy(alpha = 0.22f), shape)

val ShadowStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.30f), offset = Offset(0f, 1f), blurRadius = 4f)
)

/** Stops touches on an overlay from reaching the home screen underneath. */
fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}

@Composable
fun Modifier.noRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

fun Context.safeStart(intent: Intent): Boolean = try {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (e: Exception) {
    false
}

/** Real app launching: starts the exact launcher activity of the installed app. */
fun Context.launchApp(app: AppEntry, options: Bundle? = null) {
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(app.pkg, app.activity))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    try {
        val bundle = options
            ?: ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
        startActivity(intent, bundle)
    } catch (e: Exception) {
        // app disabled/removed - ignore
    }
}

fun Context.shortcutsFor(pkg: String): List<ShortcutInfo> = try {
    val la = getSystemService(LauncherApps::class.java)
    val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
    )
    (la.getShortcuts(q, Process.myUserHandle()) ?: emptyList()).filter { it.isEnabled }.take(4)
} catch (e: Exception) {
    emptyList()
}

fun Context.startShortcut(s: ShortcutInfo) {
    try {
        getSystemService(LauncherApps::class.java).startShortcut(s, null, null)
    } catch (e: Exception) {
    }
}


/** Apple-style continuous-corner icon shape (superellipse, exponent 5). */
object Squircle : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val a = size.width / 2.0
        val b = size.height / 2.0
        val steps = 90
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val c = cos(t)
            val sn = sin(t)
            val x = (a + a * sign(c) * abs(c).pow(0.4)).toFloat()
            val y = (b + b * sign(sn) * abs(sn).pow(0.4)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
