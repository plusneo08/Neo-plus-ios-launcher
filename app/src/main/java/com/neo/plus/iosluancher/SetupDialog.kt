package com.neo.plus.iosluancher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown only when Dark mode / Battery saver cannot be switched yet because Android has not granted
 * WRITE_SECURE_SETTINGS. A dialog adds nothing to the launcher layout.
 */
@Composable
fun SecureSetupDialog() {
    if (!Toggles.setupNeeded.value) return
    val ctx = LocalContext.current
    val cmd = SecureAccess.adbCommand(ctx)
    AlertDialog(
        onDismissRequest = { Toggles.setupNeeded.value = false },
        title = { Text("One-time permission needed") },
        text = {
            Column {
                Text(
                    "Android only lets Dark mode and Battery saver switch directly after this permission is granted once. " +
                        "No computer needed: open the free LADB app on this phone (see README, Setup without a computer) and run this line. On a computer, put \"adb shell\" in front of it:",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("Then tap the switch again.", fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                Toast.makeText(ctx, "Command copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy command") }
        },
        dismissButton = {
            TextButton(onClick = { Toggles.setupNeeded.value = false }) { Text("Close") }
        }
    )
}
