package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import android.app.PendingIntent
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import android.content.BroadcastReceiver
import android.content.ContentValues
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Slider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Neo Messages: the Neo Plus text-message app, with themes. */
class NeoMessagesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val to = try { intent?.data?.schemeSpecificPart.orEmpty().substringBefore('?') } catch (e: Exception) { "" }
        setContent { MessagesApp(to) { finish() } }
    }
}

/* ------------------------------------------------------------------ */
/*  Themes                                                              */
/* ------------------------------------------------------------------ */

class MsgTheme(
    val name: String, val bg: List<Color>, val accent: Color, val sent: List<Color>, val recv: Color,
    val text: Color, val sub: Color, val card: Color, val sentText: Color = Color.White
)

val MsgThemes = listOf(
    MsgTheme("Neo Blue", listOf(Color(0xFF040A16), Color(0xFF061830), Color(0xFF020712)), Color(0xFF38BDF8), listOf(Color(0xFF0A84FF), Color(0xFF1D4ED8)), Color(0xFF14233D), Color.White, Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.07f)),
    MsgTheme("Aurora", listOf(Color(0xFF07140F), Color(0xFF0B2A2A), Color(0xFF120A2A)), Color(0xFF34D399), listOf(Color(0xFF10B981), Color(0xFF6366F1)), Color(0xFF16282B), Color.White, Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.07f)),
    MsgTheme("Sunset", listOf(Color(0xFF1A0B14), Color(0xFF3B1226), Color(0xFF1A0A05)), Color(0xFFFB923C), listOf(Color(0xFFF97316), Color(0xFFE11D48)), Color(0xFF2A1520), Color.White, Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.07f)),
    MsgTheme("Violet", listOf(Color(0xFF0E0820), Color(0xFF1E1048), Color(0xFF080414)), Color(0xFFC084FC), listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)), Color(0xFF21163F), Color.White, Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.07f)),
    MsgTheme("Midnight", listOf(Color.Black, Color(0xFF05060A), Color.Black), Color(0xFF60A5FA), listOf(Color(0xFF2563EB), Color(0xFF2563EB)), Color(0xFF16181D), Color.White, Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f)),
    MsgTheme("Daylight", listOf(Color(0xFFF4F7FC), Color(0xFFE8EFFA), Color.White), Color(0xFF0A84FF), listOf(Color(0xFF0A84FF), Color(0xFF2F9BFF)), Color.White, Color(0xFF0B1220), Color(0xFF5B6475), Color.Black.copy(alpha = 0.05f))
)

/* ------------------------------------------------------------------ */
/*  Data                                                                */
/* ------------------------------------------------------------------ */

private class MsgItem(val id: Long, val address: String, val body: String, val date: Long, val sent: Boolean, val read: Boolean, val state: Int = 0)
private class MsgThread(val key: String, val address: String, val name: String, val msgs: List<MsgItem>)

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun keyOf(a: String): String {
    val d = a.filter { it.isDigit() }
    return if (d.length >= 7) d.takeLast(9) else a.lowercase(Locale.ROOT)
}

private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("neo_messages", Context.MODE_PRIVATE)

/** Messages this app sent: Android only lets the default SMS app write to the phone's message store, so they are kept here. */
private fun outbox(ctx: Context): List<MsgItem> = try {
    val a = JSONArray(prefs(ctx).getString("outbox", "[]"))
    (0 until a.length()).map { val o = a.getJSONObject(it); MsgItem(-o.getLong("d"), o.getString("a"), o.getString("b"), o.getLong("d"), true, true, o.optInt("s", 0)) }
} catch (e: Exception) { emptyList() }

private fun saveOutbox(ctx: Context, to: String, body: String, date: Long = System.currentTimeMillis()) {
    try {
        val a = JSONArray(prefs(ctx).getString("outbox", "[]"))
        a.put(JSONObject().put("a", to).put("b", body).put("d", date))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 400) until a.length()) keep.put(a.get(i))
        prefs(ctx).edit().putString("outbox", keep.toString()).apply()
    } catch (e: Exception) { }
}

/** Delete: removed from the phone's store when Android allows it (Neo Plus is the default SMS app), otherwise hidden in Neo Messages. */
private fun deleteMessages(ctx: Context, list: List<MsgItem>) {
    val hide = HashSet<String>(prefs(ctx).getStringSet("hidden_sms", emptySet()) ?: emptySet())
    val outDates = HashSet<Long>()
    for (m in list) {
        if (m.id < 0) { outDates.add(-m.id); continue }
        var gone = false
        try { gone = ctx.contentResolver.delete(Uri.parse("content://sms/" + m.id), null, null) > 0 } catch (e: Exception) { }
        if (!gone) hide.add(m.id.toString())
    }
    prefs(ctx).edit().putStringSet("hidden_sms", hide).apply()
    if (outDates.isNotEmpty()) {
        try {
            val a = JSONArray(prefs(ctx).getString("outbox", "[]"))
            val keep = JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getLong("d") !in outDates) keep.put(o) }
            prefs(ctx).edit().putString("outbox", keep.toString()).apply()
        } catch (e: Exception) { }
    }
}

/* Chat backgrounds. Value: "" / "-" = the theme's own, "g:<id>" = a colour background, "p:<file>" = your photo. Saved per chat, or for all chats. */
private class ChatBg(val id: String, val name: String, val colors: List<Color>, val light: Boolean = false)

private val ChatBgs = listOf(
    ChatBg("ocean", "Ocean", listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))),
    ChatBg("forest", "Forest", listOf(Color(0xFF0B1F14), Color(0xFF14532D), Color(0xFF052E16))),
    ChatBg("sunset", "Sunset", listOf(Color(0xFF3B0A2A), Color(0xFFB4412C), Color(0xFFF59E0B))),
    ChatBg("berry", "Berry", listOf(Color(0xFF2A0A3B), Color(0xFF7E22CE), Color(0xFFDB2777))),
    ChatBg("slate", "Slate", listOf(Color(0xFF111827), Color(0xFF1F2937), Color(0xFF374151))),
    ChatBg("black", "Black", listOf(Color.Black, Color.Black)),
    ChatBg("sky", "Sky", listOf(Color(0xFF7DD3FC), Color(0xFFBAE6FD), Color(0xFFE0F2FE)), true),
    ChatBg("paper", "Paper", listOf(Color(0xFFF5F1E8), Color(0xFFE9E2D0)), true)
)

private fun chatBgValue(ctx: Context, key: String): String =
    prefs(ctx).getString("chatbg_$key", null) ?: prefs(ctx).getString("chatbg_all", "").orEmpty()

private fun setChatBg(ctx: Context, key: String, v: String, all: Boolean) {
    val e = prefs(ctx).edit()
    if (all) { e.putString("chatbg_all", v); e.remove("chatbg_$key") } else e.putString("chatbg_$key", v)
    e.apply()
}

/** Copies the picked photo into the app (scaled down) so the background still works if the photo is moved or deleted. */
private fun savePhotoBg(ctx: Context, uri: Uri): String? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val name = "chatbg_" + System.currentTimeMillis() + ".jpg"
        File(ctx.filesDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    } catch (e: Exception) { return null }
}

/** true = light background, false = dark (photos get a dark veil), null = the theme's own. Decides the text colours around the bubbles. */
private fun bgIsLight(v: String): Boolean? = when {
    v.startsWith("g:") -> ChatBgs.firstOrNull { "g:" + it.id == v }?.light
    v.startsWith("p:") -> false
    v.startsWith("c:") -> customColor(v)?.let { it.luminance() > 0.55f }
    else -> null
}

private fun chromeTheme(th: MsgTheme, light: Boolean?): MsgTheme = when {
    light == null -> th
    light && th.text == Color.White ->
        MsgTheme(th.name, th.bg, th.accent, th.sent, th.recv, Color(0xFF0B1220), Color(0xFF475569), Color.Black.copy(alpha = 0.08f), th.sentText)
    !light && th.text != Color.White ->
        MsgTheme(th.name, th.bg, th.accent, th.sent, th.recv, Color.White, Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.12f), th.sentText)
    else -> th
}

/* ------------------------------------------------------------------ */
/*  Default messages app                                                */
/* ------------------------------------------------------------------ */

private fun isDefaultSms(ctx: Context): Boolean = try { Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName } catch (e: Exception) { false }

/** The system screen that asks “Make Neo Messages your default messages app?”. */
private fun defaultSmsIntent(ctx: Context): Intent {
    val old = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS) else old
        } else old
    } catch (e: Exception) { old }
}

/** As the default app, a sent text must be written into the phone's message store by us. */
private fun insertSent(ctx: Context, to: String, body: String, date: Long = System.currentTimeMillis()): Boolean = try {
    val v = ContentValues().apply {
        put(Telephony.Sms.ADDRESS, to); put(Telephony.Sms.BODY, body)
        put(Telephony.Sms.DATE, date); put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
    }
    ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, v) != null
} catch (e: Exception) { false }

private fun markRead(ctx: Context, address: String) {
    try {
        val v = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        ctx.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, v, "address=? AND read=0", arrayOf(address))
    } catch (e: Exception) { }
}

private fun notifyIncoming(ctx: Context, from: String, body: String) {
    try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("neo_msgs_in", "Messages", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(ctx, NeoMessagesActivity::class.java).setData(Uri.fromParts("smsto", from, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(ctx, from.hashCode(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, "neo_msgs_in")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(from).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi).build()
        nm.notify(from.hashCode(), n)
    } catch (e: Exception) { }
}

/** Receives texts while Neo Messages is the default app: the phone no longer files them, so we write them to the inbox. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val app = context.applicationContext
        val parts = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (e: Exception) { null }
        if (parts == null || parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        try {
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, parts[0].timestampMillis)
                put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
            }
            app.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
        } catch (e: Exception) { }
        notifyIncoming(app, from, body)
    }
}

/**
 * Hears back from the network about a text you sent: it left the phone, or it was delivered (or failed). This is a delivery
 * report only: ordinary text messages cannot tell the sender whether the other person has opened or read them.
 */
class SmsStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SENT = "com.neo.plus.iosluancher.SMS_SENT"
        const val ACTION_DELIVERED = "com.neo.plus.iosluancher.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val d = intent.getLongExtra("d", 0L)
        if (d == 0L) return
        val n = intent.getIntExtra("n", 1)
        when (intent.action) {
            ACTION_SENT -> if (resultCode != android.app.Activity.RESULT_OK) setSmsState(app, d, 2)
            ACTION_DELIVERED -> {
                var st = 0
                val pdu = intent.getByteArrayExtra("pdu")
                if (pdu != null) try { st = android.telephony.SmsMessage.createFromPdu(pdu, intent.getStringExtra("format")).status } catch (e: Exception) { }
                if (st == 0) {
                    val got = prefs(app).getInt("dlv_$d", 0) + 1
                    prefs(app).edit().putInt("dlv_$d", got).apply()
                    if (got >= n) setSmsState(app, d, 1)
                } else if (st >= 64) setSmsState(app, d, 2)
            }
        }
    }
}

/** 1 = delivered, 2 = failed. Written to the phone's store when Neo Messages is the default app, and always to Neo Messages' own list. */
private fun setSmsState(ctx: Context, date: Long, state: Int) {
    try {
        val v = ContentValues().apply {
            put(Telephony.Sms.STATUS, if (state == 1) 0 else 64)
            if (state == 2) put(Telephony.Sms.TYPE, 5)
        }
        ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, v, "date=? AND type IN (2,4,5)", arrayOf(date.toString()))
    } catch (e: Exception) { }
    try {
        val a = JSONArray(prefs(ctx).getString("outbox", "[]"))
        var changed = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("d") == date && o.optInt("s", 0) != 1) { o.put("s", state); changed = true }
        }
        if (changed) prefs(ctx).edit().putString("outbox", a.toString()).apply()
    } catch (e: Exception) { }
}

/** Android requires a default messages app to declare this. Picture messages (MMS) are not handled yet. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { }
}

/** “Reply with message” from the incoming-call screen. */
class RespondViaMessageService : android.app.Service() {
    override fun onBind(intent: Intent?): android.os.IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val to = intent?.data?.schemeSpecificPart.orEmpty().substringBefore('?')
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (to.isNotBlank() && text.isNotBlank()) sendSms(applicationContext, to, text)
        } catch (e: Exception) { }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/* Message list background: same values as the chat backgrounds, plus "c:RRGGBB" for any colour you mix yourself. */
private fun listBgValue(ctx: Context): String = prefs(ctx).getString("listbg", "").orEmpty()
private fun setListBg(ctx: Context, v: String) { prefs(ctx).edit().putString("listbg", v).apply() }

private fun customColor(v: String): Color? =
    if (v.startsWith("c:")) try { Color(android.graphics.Color.parseColor("#" + v.substring(2))) } catch (e: Exception) { null } else null

private fun loadSms(ctx: Context): List<MsgItem> {
    val out = ArrayList<MsgItem>()
    val hidden = prefs(ctx).getStringSet("hidden_sms", emptySet()) ?: emptySet()
    try {
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "read", "status"), null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 4000) {
                val id = c.getLong(0)
                if (id.toString() in hidden) continue
                val type = c.getInt(4)
                val status = c.getInt(6)
                val state = when {
                    type == 1 -> 0
                    type == 5 || status >= 64 -> 2          // failed
                    status == 0 -> 1                        // the delivery report came back: delivered
                    else -> 0
                }
                out.add(MsgItem(id, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), type != 1, c.getInt(5) == 1, state))
            }
        }
    } catch (e: Exception) { }
    out += outbox(ctx)
    return out
}

private fun contactNames(ctx: Context): Map<String, Pair<String, String>> {
    val m = LinkedHashMap<String, Pair<String, String>>()
    if (!has(ctx, Manifest.permission.READ_CONTACTS)) return m
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0).orEmpty(); val num = c.getString(1).orEmpty()
                if (n.isNotBlank() && num.isNotBlank()) m.putIfAbsent(keyOf(num), n to num)
            }
        }
    } catch (e: Exception) { }
    return m
}

private fun buildThreads(items: List<MsgItem>, names: Map<String, Pair<String, String>>): List<MsgThread> =
    items.groupBy { keyOf(it.address) }.map { (k, list) ->
        val sorted = list.sortedBy { it.date }
        val addr = sorted.last().address
        MsgThread(k, addr, names[k]?.first ?: addr, sorted)
    }.sortedByDescending { it.msgs.last().date }

private fun sendSms(ctx: Context, to: String, text: String): String? {
    if (!has(ctx, Manifest.permission.SEND_SMS)) return "Allow sending messages first"
    return try {
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        val now = System.currentTimeMillis()
        // The network tells us when the text was sent and when it reached the other phone (a "delivery report").
        fun pi(action: String, i: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, ((now and 0xFFFFFFFL).toInt() + i * 2 + if (action == SmsStatusReceiver.ACTION_SENT) 0 else 1),
            Intent(ctx, SmsStatusReceiver::class.java).setAction(action).putExtra("d", now).putExtra("n", parts.size),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(
                to, null, parts,
                ArrayList(parts.indices.map { pi(SmsStatusReceiver.ACTION_SENT, it) }),
                ArrayList(parts.indices.map { pi(SmsStatusReceiver.ACTION_DELIVERED, it) })
            )
        } else sm.sendTextMessage(to, null, text, pi(SmsStatusReceiver.ACTION_SENT, 0), pi(SmsStatusReceiver.ACTION_DELIVERED, 0))
        if (!(isDefaultSms(ctx) && insertSent(ctx, to, text, now))) saveOutbox(ctx, to, text, now)
        null
    } catch (e: Exception) { "Couldn’t send: " + (e.message ?: "unknown error") }
}

private fun timeText(ms: Long): String {
    fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val days = ((day(System.currentTimeMillis()) - day(ms)) / 86_400_000L).toInt()
    val loc = Locale.getDefault()
    return when {
        days <= 0 -> SimpleDateFormat("HH:mm", loc).format(Date(ms))
        days == 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEE", loc).format(Date(ms))
        else -> SimpleDateFormat("d MMM", loc).format(Date(ms))
    }
}

private fun dayHeader(ms: Long): String {
    val t = timeText(ms)
    return if (t.contains(':')) "Today" else if (t == "Yesterday") t else SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ms))
}

/* ------------------------------------------------------------------ */
/*  UI                                                                  */
/* ------------------------------------------------------------------ */

private sealed class Screen {
    object List : Screen()
    object New : Screen()
    class Chat(val address: String, val draft: String = "") : Screen()
    class Forward(val text: String, val from: String) : Screen()
}

@Composable
private fun Avatar(name: String, th: MsgTheme, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(th.sent)), contentAlignment = Alignment.Center) {
        val c = name.trim().firstOrNull { it.isLetter() }
        if (c != null) Text(c.uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        else Icon(Icons.Filled.Create, null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
}

/** A small round button for the top bars; lit up in the theme colour when it is switched on. */
@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, th: MsgTheme, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (active) th.accent.copy(alpha = 0.22f) else Color.Transparent).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = if (active) th.accent else th.text, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, th: MsgTheme, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(th.card).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = th.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MessagesApp(initialTo: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var themeIdx by remember { mutableStateOf(prefs(ctx).getInt("theme", 0).coerceIn(0, MsgThemes.lastIndex)) }
    val th = MsgThemes[themeIdx]
    var screen by remember { mutableStateOf<Screen>(if (initialTo.isNotBlank()) Screen.Chat(initialTo) else Screen.List) }
    var picker by remember { mutableStateOf(false) }
    var canRead by remember { mutableStateOf(has(ctx, Manifest.permission.READ_SMS)) }
    var items by remember { mutableStateOf(emptyList<MsgItem>()) }
    var names by remember { mutableStateOf(emptyMap<String, Pair<String, String>>()) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var listBg by remember { mutableStateOf(listBgValue(ctx)) }
    var listBgSheet by remember { mutableStateOf(false) }
    var isDefault by remember { mutableStateOf(isDefaultSms(ctx)) }
    val defaultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = isDefaultSms(ctx); tick++ }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { canRead = has(ctx, Manifest.permission.READ_SMS); tick++ }
    LaunchedEffect(canRead, tick) {
        if (!canRead) return@LaunchedEffect
        names = withContext(Dispatchers.IO) { contactNames(ctx) }
        while (true) { items = withContext(Dispatchers.IO) { loadSms(ctx) }; delay(3000) }
    }
    val threads = remember(items, names) { buildThreads(items, names) }
    LaunchedEffect(screen, isDefault) {
        val sc = screen
        if (isDefault && sc is Screen.Chat) withContext(Dispatchers.IO) { markRead(ctx, sc.address) }
    }

    BackHandler(enabled = true) {
        val sc = screen
        when {
            listBgSheet -> listBgSheet = false
            picker -> picker = false
            sc is Screen.Forward -> screen = Screen.Chat(sc.from)
            sc !is Screen.List -> screen = Screen.List
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(th.bg)).drawBehind {
        drawCircle(Brush.radialGradient(listOf(th.accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.8f, 0f), radius = size.width), size.width, Offset(size.width * 0.8f, 0f))
    }) {
        if (!canRead) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(36.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Avatar("M", th, 84.dp)
                Spacer(Modifier.height(18.dp))
                Text("Neo Messages", color = th.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Allow Neo Plus to read and send text messages and see your contacts.", color = th.sub, fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(28.dp)).background(Brush.horizontalGradient(th.sent)).clickable {
                        perms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                    }.padding(horizontal = 34.dp, vertical = 13.dp)
                ) { Text("Allow access", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        } else when (val s = screen) {
            is Screen.List -> ListScreen(
                threads, th, listBg, isDefault, { listBgSheet = true },
                {
                    try { defaultLauncher.launch(defaultSmsIntent(ctx)) }
                    catch (e: Exception) { android.widget.Toast.makeText(ctx, "Couldn’t open the setting. Choose Neo Messages under Settings, Apps, Default apps, SMS app.", android.widget.Toast.LENGTH_LONG).show() }
                },
                { picker = true }, { screen = Screen.New }
            ) { screen = Screen.Chat(it) }
            is Screen.New -> NewScreen(names, th, { screen = Screen.List }) { screen = Screen.Chat(it) }
            is Screen.Chat -> {
                val k = keyOf(s.address)
                val t = threads.firstOrNull { it.key == k }
                ChatScreen(
                    s.address, t?.name ?: names[k]?.first ?: s.address, t?.msgs ?: emptyList(), th, s.draft,
                    { screen = Screen.List },
                    { fwd -> screen = Screen.Forward(fwd, s.address) },
                    { list -> scope.launch { withContext(Dispatchers.IO) { deleteMessages(ctx, list) }; tick++ } }
                ) { text ->
                    val err = sendSms(ctx, s.address, text)
                    if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show() else tick++
                    err == null
                }
            }
            is Screen.Forward -> NewScreen(names, th, { screen = Screen.Chat(s.from) }, "Forward to") { screen = Screen.Chat(it, s.text) }
        }

        // message list background picker
        BackgroundSheet(listBgSheet && screen is Screen.List, listBg, th, "Message list background", null, {}, { listBgSheet = false }) { v -> setListBg(ctx, v); listBg = v }

        // theme picker
        AnimatedVisibility(picker, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { picker = false })
        }
        AnimatedVisibility(
            picker, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF0E1626)).navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Text("Choose a theme", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                for (row in MsgThemes.chunked(3)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { t ->
                            val i = MsgThemes.indexOf(t)
                            Column(Modifier.weight(1f).clickable { themeIdx = i; prefs(ctx).edit().putInt("theme", i).apply() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(t.bg))
                                        .border(if (i == themeIdx) 2.dp else 1.dp, if (i == themeIdx) t.accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                                        .padding(10.dp)
                                ) {
                                    Box(Modifier.align(Alignment.TopStart).width(46.dp).height(18.dp).clip(RoundedCornerShape(10.dp)).background(t.recv))
                                    Box(Modifier.align(Alignment.BottomEnd).width(52.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(t.sent)))
                                    if (i == themeIdx) Icon(Icons.Filled.Check, null, tint = t.accent, modifier = Modifier.align(Alignment.TopEnd).size(18.dp))
                                }
                                Text(t.name, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListScreen(
    threads: List<MsgThread>, th0: MsgTheme, bg: String, isDefault: Boolean, onBg: () -> Unit, onMakeDefault: () -> Unit,
    onTheme: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit
) {
    val th = chromeTheme(th0, bgIsLight(bg))
    var q by remember { mutableStateOf("") }
    val shown = remember(q, threads) { if (q.isBlank()) threads else threads.filter { it.name.contains(q, true) || it.address.contains(q) || it.msgs.last().body.contains(q, true) } }
    Box(Modifier.fillMaxSize()) {
        ChatBackdrop(bg)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val lctx = LocalContext.current
            var orbsOn by remember { mutableStateOf(MessageOrbs.enabled(lctx)) }
            val unread = remember(threads) { threads.count { t -> t.msgs.any { !it.sent && !it.read } } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(th.sent)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Email, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Messages", color = th.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (unread > 0) "$unread unread  ·  ${threads.size} chats" else "${threads.size} chats",
                        color = if (unread > 0) th.accent else th.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
                Row(Modifier.clip(RoundedCornerShape(50)).background(th.card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TopIcon(Icons.Filled.Notifications, th, "Floating orbs for new messages", orbsOn) {
                        if (orbsOn) {
                            orbsOn = false; MessageOrbs.setEnabled(lctx, false)
                            android.widget.Toast.makeText(lctx, "New messages will not float as orbs", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            orbsOn = true; MessageOrbs.setEnabled(lctx, true)
                            if (!MessageOrbs.canShow(lctx)) {
                                android.widget.Toast.makeText(lctx, "Allow “Display over other apps” for Neo Plus so the orbs can float", android.widget.Toast.LENGTH_LONG).show()
                                try { lctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + lctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                            } else android.widget.Toast.makeText(lctx, "New messages will float as orbs", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    TopIcon(Icons.Filled.PhotoLibrary, th, "Background", false, onBg)
                    TopIcon(Icons.Filled.Palette, th, "Themes", false, onTheme)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(th.card)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = th.sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search messages", color = th.sub, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = th.text, fontSize = 16.sp), cursorBrush = SolidColor(th.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            if (!isDefault) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(th.card).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Make Neo Messages your default", color = th.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Texts then arrive and send here. Picture messages (MMS) aren’t supported yet.", color = th.sub, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(th.sent)).clickable { onMakeDefault() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text("Set default", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(36.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(if (threads.isEmpty()) "No messages yet" else "No match", color = th.sub, fontSize = 16.sp)
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(shown, key = { it.key }) { t ->
                    val last = t.msgs.last()
                    val unread = t.msgs.count { !it.sent && !it.read }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(20.dp)).background(th.card)
                            .clickable { onOpen(t.address) }.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(t.name, th, 50.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = th.text, fontSize = 16.sp, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (last.sent) "You: " else "") + last.body, color = if (unread > 0) th.text else th.sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeText(last.date), color = if (unread > 0) th.accent else th.sub, fontSize = 12.sp)
                            if (unread > 0) Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(th.accent))
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(22.dp).size(60.dp).clip(CircleShape)
                .background(Brush.linearGradient(th.sent)).clickable { onNew() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Create, "New message", tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun NewScreen(names: Map<String, Pair<String, String>>, th: MsgTheme, onBack: () -> Unit, title: String = "New message", onPick: (String) -> Unit) {
    var to by remember { mutableStateOf("") }
    val matches = remember(to, names) {
        if (to.isBlank()) names.values.take(30) else names.values.filter { it.first.contains(to, true) || it.second.filter { c -> c.isDigit() }.contains(to.filter { c -> c.isDigit() }.ifEmpty { "\u0000" }) }.take(30)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.ArrowBack, th, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text(title, color = th.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(22.dp)).background(th.card).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("To:", color = th.sub, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(to, { to = it }, singleLine = true, textStyle = TextStyle(color = th.text, fontSize = 17.sp), cursorBrush = SolidColor(th.accent), modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (to.isNotBlank()) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(to.trim()) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("", th, 44.dp); Spacer(Modifier.width(12.dp))
                    Text("Send to “${to.trim()}”", color = th.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(matches) { (n, num) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(num) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n, th, 44.dp); Spacer(Modifier.width(12.dp))
                    Column { Text(n, color = th.text, fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(num, color = th.sub, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun BgTile(label: String, brush: Brush, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(16.dp)).background(brush)
                .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
    }
}

/** The picture or colours behind the chat (drawn under the bubbles). */
@Composable
private fun ChatBackdrop(v: String) {
    val ctx = LocalContext.current
    if (v.startsWith("g:")) {
        val c = ChatBgs.firstOrNull { "g:" + it.id == v }
        if (c != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(c.colors)))
    } else if (v.startsWith("c:")) {
        val c = customColor(v)
        if (c != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c, lerp(c, Color.Black, 0.35f)))))
    } else if (v.startsWith("p:")) {
        val bmp by produceState<ImageBitmap?>(null, v) {
            value = withContext(Dispatchers.IO) {
                try { BitmapFactory.decodeFile(File(ctx.filesDir, v.substring(2)).path)?.asImageBitmap() } catch (e: Exception) { null }
            }
        }
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
    }
}

@Composable
private fun ChatScreen(
    address: String, name: String, msgs: List<MsgItem>, th: MsgTheme, draft: String,
    onBack: () -> Unit, onForward: (String) -> Unit, onDelete: (List<MsgItem>) -> Unit, onSend: (String) -> Boolean
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val key = remember(address) { keyOf(address) }
    var text by remember(address, draft) { mutableStateOf(draft) }
    var bg by remember(address) { mutableStateOf(chatBgValue(ctx, key)) }
    var bgSheet by remember { mutableStateOf(false) }
    var allChats by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val orbs = remember(address) { mutableStateListOf<SentOrb>() }
    var orbSeq by remember(address) { mutableStateOf(0) }
    val selected = remember(address) { mutableStateListOf<Long>() }
    val selecting = selected.isNotEmpty()
    val chosen = msgs.filter { it.id in selected }
    // text colours around the bubbles follow the background (light / dark); the bubbles keep the theme's colours
    val ui = chromeTheme(th, bgIsLight(bg))

    fun applyBg(v: String) { setChatBg(ctx, key, v, allChats); bg = v }
    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected.add(id) }

    BackHandler(enabled = selecting || bgSheet) { if (bgSheet) bgSheet = false else selected.clear() }

    val rows = remember(msgs) {
        val out = ArrayList<Any>()
        var lastDay = ""
        msgs.forEach { m -> val d = dayHeader(m.date); if (d != lastDay) { out.add(d); lastDay = d }; out.add(m) }
        out
    }
    val list = rememberLazyListState()
    val lastSentId = remember(msgs) { msgs.lastOrNull { it.sent }?.id }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex) }

    Box(Modifier.fillMaxSize()) {
        ChatBackdrop(bg)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            if (selecting) {
                // selection bar: Copy / Forward / Delete the chosen messages
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(30.dp)).background(ui.card).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(Icons.Filled.Close, ui, "Cancel") { selected.clear() }
                    Spacer(Modifier.width(12.dp))
                    Text("${selected.size} selected", color = ui.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Filled.ContentCopy, ui, "Copy") {
                        val all = chosen.joinToString("\n") { it.body }
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Message", all))
                            android.widget.Toast.makeText(ctx, if (chosen.size == 1) "Message copied" else "Messages copied", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { }
                        selected.clear()
                    }
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Filled.ArrowForward, ui, "Forward") {
                        val all = chosen.joinToString("\n") { it.body }
                        selected.clear()
                        if (all.isNotBlank()) onForward(all)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Filled.Delete, ui, "Delete") { confirmDelete = true }
                }
            } else {
                // floating glass top bar: back, who you are talking to, background, call
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(30.dp)).background(ui.card).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopIcon(Icons.Filled.ArrowBack, ui, "Back", false, onBack)
                    Spacer(Modifier.width(4.dp))
                    Avatar(name, th, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = ui.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name != address) Text(address, color = ui.sub, fontSize = 12.sp, maxLines = 1)
                    }
                    TopIcon(Icons.Filled.Palette, ui, "Chat background", false) { bgSheet = true }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(th.sent))
                            .clickable { try { ctx.startActivity(Intent(ctx, NeoDialerActivity::class.java).setData(Uri.fromParts("tel", address, null))) } catch (e: Exception) { } },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Call, "Call", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows) { r ->
                    if (r is String) {
                        Text(r, color = ui.sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    } else if (r is MsgItem) {
                        val isSel = r.id in selected
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (isSel) th.accent.copy(alpha = 0.25f) else Color.Transparent)
                                .pointerInput(r.id) {
                                    detectTapGestures(
                                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(r.id) },
                                        onTap = { if (selected.isNotEmpty()) toggle(r.id) }
                                    )
                                }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = if (r.sent) Arrangement.End else Arrangement.Start
                        ) {
                            Column(Modifier.fillMaxWidth(0.80f), horizontalAlignment = if (r.sent) Alignment.End else Alignment.Start) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (r.sent) 20.dp else 5.dp, bottomEnd = if (r.sent) 5.dp else 20.dp))
                                        .then(if (r.sent) Modifier.background(Brush.linearGradient(th.sent)) else Modifier.background(th.recv))
                                        .padding(horizontal = 14.dp, vertical = 9.dp)
                                ) { Text(r.body, color = if (r.sent) th.sentText else th.text, fontSize = 16.sp) }
                                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.date)), color = ui.sub, fontSize = 10.sp)
                                    if (r.sent) {
                                        // 👌 = the network says it reached the other phone. Plain texts cannot report that it was opened.
                                        if (r.state == 1) Text("  ·  👌 Delivered", color = ui.sub, fontSize = 10.sp)
                                        else if (r.state == 2) Text("  ·  Not sent", color = Color(0xFFFFB347), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                        else if (r.id == lastSentId) Text("  ·  Sent", color = ui.sub, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(ui.card).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (text.isEmpty()) Text("Text message", color = ui.sub, fontSize = 16.sp)
                    BasicTextField(text, { text = it }, textStyle = TextStyle(color = ui.text, fontSize = 16.sp), cursorBrush = SolidColor(th.accent), maxLines = 5, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(10.dp))
                val ready = text.isNotBlank()
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (ready) Brush.linearGradient(th.sent) else SolidColor(ui.card))
                        .clickable(enabled = ready) {
                            val t = text.trim()
                            if (onSend(t)) {
                                if (orbs.size >= 4) orbs.removeAt(0)
                                orbs.add(SentOrb(System.nanoTime(), t, orbSeq++))
                            }
                            text = ""
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Send, "Send", tint = if (ready) Color.White else ui.sub, modifier = Modifier.size(22.dp)) }
            }
        }

        // each sent message floats on an orb: tap to read it, tap again to hide it, drag it to the bottom to remove it
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            for (o in orbs) key(o.id) { FloatingOrb(o, th, wPx, hPx) { orbs.remove(o) } }
        }

        // chat background picker
        BackgroundSheet(bgSheet, bg, th, "Chat background", allChats, { allChats = it }, { bgSheet = false }) { v -> applyBg(v) }

        if (confirmDelete) {
            val n = chosen.size
            val real = try { Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName } catch (e: Exception) { false }
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(if (n == 1) "Delete this message?" else "Delete $n messages?") },
                text = { Text(if (real) "This can’t be undone." else "They will be removed from Neo Messages. Your phone’s own messages app may still keep a copy.") },
                confirmButton = { TextButton(onClick = { val l = chosen; confirmDelete = false; selected.clear(); if (l.isNotEmpty()) onDelete(l) }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
            )
        }
    }
}


/* ------------------------------------------------------------------ */
/*  Background picker (message list and chats)                          */
/* ------------------------------------------------------------------ */

@Composable
private fun BoxScope.BackgroundSheet(
    visible: Boolean, current: String, th: MsgTheme, title: String,
    allChats: Boolean?, onAllChats: (Boolean) -> Unit, onDismiss: () -> Unit, onApply: (String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showColor by remember { mutableStateOf(false) }
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV((customColor(current) ?: Color(0xFF2563EB)).toArgb(), it) } }
    var hue by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1].coerceIn(0.15f, 1f)) }
    var bri by remember { mutableStateOf(start[2].coerceIn(0.15f, 1f)) }
    fun emit() {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
        onApply("c:" + "%06X".format(c and 0xFFFFFF))
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { savePhotoBg(ctx, uri) }
            if (f != null) onApply("p:$f") else android.widget.Toast.makeText(ctx, "Couldn’t use that photo", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { onDismiss() })
    }
    AnimatedVisibility(
        visible, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF0E1626)).navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BgTile("Default", Brush.verticalGradient(th.bg), current.isEmpty() || current == "-", th.accent) { onApply("-") }
                ChatBgs.forEach { c -> BgTile(c.name, Brush.verticalGradient(c.colors), current == "g:" + c.id, th.accent) { onApply("g:" + c.id) } }
                val cc = customColor(current)
                BgTile(
                    "Any colour",
                    if (cc != null) Brush.verticalGradient(listOf(cc, cc)) else Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22C55E), Color(0xFF06B6D4), Color(0xFF6366F1), Color(0xFFD946EF))),
                    cc != null, th.accent
                ) { showColor = !showColor; if (showColor) emit() }
                Column(Modifier.width(72.dp).clickable { pick.launch("image/*") }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                            .border(if (current.startsWith("p:")) 2.dp else 1.dp, if (current.startsWith("p:")) th.accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(if (current.startsWith("p:")) Icons.Filled.Check else Icons.Filled.Add, null, tint = if (current.startsWith("p:")) th.accent else Color.White, modifier = Modifier.size(26.dp)) }
                    Text("My photo", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
                }
            }
            if (showColor) {
                Spacer(Modifier.height(10.dp))
                Text("Colour", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Slider(value = hue, onValueChange = { hue = it; emit() }, valueRange = 0f..360f)
                Text("Richness", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Slider(value = sat, onValueChange = { sat = it; emit() }, valueRange = 0.15f..1f)
                Text("Brightness", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Slider(value = bri, onValueChange = { bri = it; emit() }, valueRange = 0.15f..1f)
            }
            if (allChats != null) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clickable { onAllChats(!allChats) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Use for all chats", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = allChats, onCheckedChange = { onAllChats(it) })
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sent-message orbs                                                   */
/* ------------------------------------------------------------------ */

private class SentOrb(val id: Long, val text: String, val slot: Int)

/**
 * A sent message, carried by a glowing orb that floats over the chat background.
 * Tap it once to show the message, tap again to hide it and get the plain orb back. Drag it to the bottom to remove it.
 */
@Composable
private fun FloatingOrb(orb: SentOrb, th: MsgTheme, w: Float, h: Float, onRemove: () -> Unit) {
    val d = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val orbPx = with(d) { 58.dp.toPx() }
    val gap = with(d) { 16.dp.toPx() }
    val zoneH = with(d) { 110.dp.toPx() }
    val maxX = (w - orbPx).coerceAtLeast(0f)
    val maxY = (h - orbPx).coerceAtLeast(0f)
    val startX = (w - orbPx - gap).coerceIn(0f, maxX)
    val startY = (h - orbPx - gap * 3f).coerceIn(0f, maxY)
    val targetX = startX
    val targetY = (h * 0.26f + (orb.slot % 4) * (orbPx + gap)).coerceIn(0f, maxY)

    var ox by remember { mutableStateOf(startX) }
    var oy by remember { mutableStateOf(startY) }
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }

    // flies up from the send button to its floating spot
    LaunchedEffect(Unit) {
        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 90f)) { t, _ ->
            if (!moved) { ox = startX + (targetX - startX) * t; oy = startY + (targetY - startY) * t }
        }
    }
    val scale by animateFloatAsState(if (gone) 0f else 1f, tween(220), label = "orbScale", finishedListener = { if (gone) onRemove() })
    val inf = rememberInfiniteTransition(label = "orb")
    val bob by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1900 + (orb.slot % 3) * 300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val glow by inf.animateFloat(0.85f, 1.25f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val inZone = oy + orbPx / 2f > h - zoneH

    Box(Modifier.fillMaxSize()) {
        // remove zone, only while dragging
        AnimatedVisibility(
            dragging, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(if (inZone) 78.dp else 62.dp).clip(CircleShape).background(if (inZone) Color(0xFFEF4444) else Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Delete, "Remove", tint = Color.White, modifier = Modifier.size(28.dp)) }
                Text(if (inZone) "Let go to remove" else "Drag here to remove", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // the message, shown while the orb is opened
        val card: @Composable () -> Unit = {
            AnimatedVisibility(open && !dragging, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f)) {
                Box(
                    Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(th.sent))
                        .clickable { open = false }.padding(horizontal = 16.dp, vertical = 12.dp)
                ) { Text(orb.text, color = th.sentText, fontSize = 16.sp, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (oy > h * 0.30f) {
            Box(Modifier.fillMaxWidth().height((with(d) { oy.toDp() } - 10.dp).coerceAtLeast(0.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.BottomCenter) { card() }
        } else {
            Box(Modifier.fillMaxWidth().padding(top = with(d) { (oy + orbPx).toDp() } + 10.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.TopCenter) { card() }
        }

        // the orb
        Box(
            Modifier.offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .size(58.dp)
                .graphicsLayer {
                    val sc = scale * (if (open) 1.1f else 1f)
                    scaleX = sc; scaleY = sc; alpha = scale
                    translationY = if (dragging) 0f else bob * 7.dp.toPx()
                }
                .drawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(Brush.radialGradient(listOf(th.accent.copy(alpha = 0.45f), Color.Transparent), center = center, radius = r * 2.1f * glow), r * 2.1f * glow, center)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), th.accent, th.sent.last()), center = Offset(size.width * 0.38f, size.height * 0.30f), radius = r * 1.5f),
                        r, center
                    )
                    drawCircle(Color.White.copy(alpha = 0.55f), r, center, style = Stroke(width = 1.5.dp.toPx()))
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { open = !open; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) }
                .pointerInput(w, h) {
                    detectDragGestures(
                        onDragStart = { dragging = true; moved = true },
                        onDrag = { change, amount ->
                            change.consume()
                            ox = (ox + amount.x).coerceIn(0f, maxX)
                            oy = (oy + amount.y).coerceIn(0f, maxY)
                        },
                        onDragEnd = {
                            dragging = false
                            if (oy + orbPx / 2f > h - zoneH) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); gone = true }
                        },
                        onDragCancel = { dragging = false }
                    )
                },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Send, null, tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(24.dp)) }
    }
}
