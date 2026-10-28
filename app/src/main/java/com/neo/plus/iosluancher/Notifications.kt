package com.neo.plus.iosluancher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotifItem(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: ImageBitmap?,
    val intent: PendingIntent?,
    val clearable: Boolean,
    val reply: Notification.Action? = null,
    val pkg: String = ""
)

/** Receives every notification so the iOS-style panel can show them (needs Notification access). */
class NeoNotificationService : NotificationListenerService() {

    companion object {
        var instance: NeoNotificationService? = null
        val items = mutableStateListOf<NotifItem>()
    }

    private val iconCache = HashMap<String, ImageBitmap?>()

    override fun onListenerConnected() {
        instance = this
        // Chats that arrived while the service was not running are still in the shade: remember them for "read my messages".
        try { activeNotifications?.forEach { NotifReader.remember(applicationContext, it) } } catch (e: Exception) { }
        refresh()
    }

    override fun onListenerDisconnected() {
        instance = null
        items.clear()
        // Some phones stop the listener to save battery: ask Android to start it again.
        try { requestRebind(ComponentName(this, NeoNotificationService::class.java)) } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            try { NotifReader.onPosted(applicationContext, sbn) } catch (e: Exception) { }
            try { ReplyCard.onPosted(applicationContext, sbn) } catch (e: Exception) { }
        }
        refresh()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun iconFor(pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
        try { renderIcon(packageManager.getApplicationIcon(pkg), 96).asImageBitmap() } catch (e: Exception) { null }
    }

    private fun refresh() {
        val list = try { activeNotifications?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val pm = packageManager
        val mapped = list.filter {
            it.packageName != packageName &&
                !it.isOngoing &&
                (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.sortedByDescending { it.postTime }.map { sbn ->
            val ex = sbn.notification.extras
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) { sbn.packageName }
            NotifItem(
                key = sbn.key,
                app = appLabel,
                title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "",
                time = sbn.postTime,
                icon = iconFor(sbn.packageName),
                intent = sbn.notification.contentIntent,
                clearable = sbn.isClearable,
                reply = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null },
                pkg = sbn.packageName
            )
        }
        items.clear()
        items.addAll(mapped)
    }
}

@Composable
fun NotificationPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val closeCb by rememberUpdatedState(onClose)
    val enabled by produceState(false) {
        while (true) {
            value = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            delay(1000)
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(10_000) }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.80f))
            .consumeTouches()
            .pointerInput(Unit) {
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
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)),
                color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
                color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Notification Centre", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (enabled && NeoNotificationService.items.isNotEmpty()) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                            .clickable { NeoNotificationService.instance?.cancelAllNotifications() }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("Clear All", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (!enabled) {
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 0.16f)
                        .clickable { ctx.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); onClose() }
                        .padding(18.dp)
                ) {
                    Text("Turn on notification access", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap here, then switch on NEO PLUS IOS LUANCHER. Your notifications will then appear in this panel.",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
            } else if (NeoNotificationService.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Notifications", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(NeoNotificationService.items.toList(), key = { it.key }) { n ->
                        var replying by remember(n.key) { mutableStateOf(false) }
                        var draft by remember(n.key) { mutableStateOf("") }
                        var sentOk by remember(n.key) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 0.16f)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    try { n.intent?.send() } catch (e: Exception) { }
                                    onClose()
                                }.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (n.icon != null) Image(n.icon, n.app, Modifier.size(38.dp).clip(Squircle))
                            else Spacer(Modifier.size(38.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                    Text(n.app.uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(n.time)),
                                        color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                }
                                if (n.title.isNotBlank()) Text(n.title, color = Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (n.text.isNotBlank()) Text(n.text, color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (n.clearable) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 8.dp).size(18.dp)
                                        .clickable { NeoNotificationService.instance?.cancelNotification(n.key) })
                            }
                        }
                        if (n.reply != null) {
                            if (!replying) {
                                val tkey = NotifChats.keyOf(n.pkg, n.title)
                                val typingNow = if (NotifChats.version.intValue >= 0) TypingWatch.statusFor(tkey) else null
                                Row(Modifier.padding(start = 62.dp, bottom = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (sentOk) "Sent" else "Reply", color = Color(0xFF7DD3FC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { replying = true; sentOk = false })
                                    Spacer(Modifier.width(18.dp))
                                    Text("Open chat", color = Color(0xFF7DD3FC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable {
                                            ctx.safeStart(Intent(ctx, NotifChatActivity::class.java).putExtra("thread", tkey))
                                            onClose()
                                        })
                                    if (typingNow != null) {
                                        Spacer(Modifier.width(14.dp))
                                        Text(typingNow, color = Color(0xFF30D158), fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(start = 62.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                                        if (draft.isEmpty()) Text("Reply to " + n.title.ifBlank { n.app }, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1)
                                        BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                            cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth())
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("Send", color = Color(0xFF7DD3FC), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            val t = draft.trim()
                                            if (t.isNotEmpty() && NotifStore.sendAction(ctx, n.reply, t)) { draft = ""; replying = false; sentOk = true }
                                        })
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
