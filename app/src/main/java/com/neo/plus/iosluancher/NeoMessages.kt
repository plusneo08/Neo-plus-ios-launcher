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

private class MsgItem(val id: Long, val address: String, val body: String, val date: Long, val sent: Boolean, val read: Boolean)
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
    (0 until a.length()).map { val o = a.getJSONObject(it); MsgItem(-o.getLong("d"), o.getString("a"), o.getString("b"), o.getLong("d"), true, true) }
} catch (e: Exception) { emptyList() }

private fun saveOutbox(ctx: Context, to: String, body: String) {
    try {
        val a = JSONArray(prefs(ctx).getString("outbox", "[]"))
        a.put(JSONObject().put("a", to).put("b", body).put("d", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 400) until a.length()) keep.put(a.get(i))
        prefs(ctx).edit().putString("outbox", keep.toString()).apply()
    } catch (e: Exception) { }
}

private fun loadSms(ctx: Context): List<MsgItem> {
    val out = ArrayList<MsgItem>()
    try {
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "read"), null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 4000) {
                out.add(MsgItem(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getInt(4) != 1, c.getInt(5) == 1))
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
        if (parts.size > 1) sm.sendMultipartTextMessage(to, null, parts, null, null) else sm.sendTextMessage(to, null, text, null, null)
        saveOutbox(ctx, to, text)
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
    class Chat(val address: String) : Screen()
}

@Composable
private fun Avatar(name: String, th: MsgTheme, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(th.sent)), contentAlignment = Alignment.Center) {
        val c = name.trim().firstOrNull { it.isLetter() }
        if (c != null) Text(c.uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        else Icon(Icons.Filled.Create, null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
    }
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

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { canRead = has(ctx, Manifest.permission.READ_SMS); tick++ }
    LaunchedEffect(canRead, tick) {
        if (!canRead) return@LaunchedEffect
        names = withContext(Dispatchers.IO) { contactNames(ctx) }
        while (true) { items = withContext(Dispatchers.IO) { loadSms(ctx) }; delay(3000) }
    }
    val threads = remember(items, names) { buildThreads(items, names) }

    BackHandler(enabled = true) { when { picker -> picker = false; screen !is Screen.List -> screen = Screen.List; else -> onClose() } }

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
            is Screen.List -> ListScreen(threads, th, { picker = true }, { screen = Screen.New }) { screen = Screen.Chat(it) }
            is Screen.New -> NewScreen(names, th, { screen = Screen.List }) { screen = Screen.Chat(it) }
            is Screen.Chat -> {
                val k = keyOf(s.address)
                val t = threads.firstOrNull { it.key == k }
                ChatScreen(s.address, t?.name ?: names[k]?.first ?: s.address, t?.msgs ?: emptyList(), th, { screen = Screen.List }) { text ->
                    val err = sendSms(ctx, s.address, text)
                    if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show() else tick++
                }
            }
        }

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
private fun ListScreen(threads: List<MsgThread>, th: MsgTheme, onTheme: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, threads) { if (q.isBlank()) threads else threads.filter { it.name.contains(q, true) || it.address.contains(q) || it.msgs.last().body.contains(q, true) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", color = th.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("NEO PLUS", color = th.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                }
                IconBtn(Icons.Filled.Palette, th, "Themes", onTheme)
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
private fun NewScreen(names: Map<String, Pair<String, String>>, th: MsgTheme, onBack: () -> Unit, onPick: (String) -> Unit) {
    var to by remember { mutableStateOf("") }
    val matches = remember(to, names) {
        if (to.isBlank()) names.values.take(30) else names.values.filter { it.first.contains(to, true) || it.second.filter { c -> c.isDigit() }.contains(to.filter { c -> c.isDigit() }.ifEmpty { "\u0000" }) }.take(30)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.ArrowBack, th, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("New message", color = th.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
private fun ChatScreen(address: String, name: String, msgs: List<MsgItem>, th: MsgTheme, onBack: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val rows = remember(msgs) {
        val out = ArrayList<Any>()
        var lastDay = ""
        msgs.forEach { m -> val d = dayHeader(m.date); if (d != lastDay) { out.add(d); lastDay = d }; out.add(m) }
        out
    }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Filled.ArrowBack, th, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Avatar(name, th, 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = th.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name != address) Text(address, color = th.sub, fontSize = 12.sp, maxLines = 1)
            }
            IconBtn(Icons.Filled.Call, th, "Call") { try { ctx.startActivity(Intent(ctx, NeoDialerActivity::class.java).setData(Uri.fromParts("tel", address, null))) } catch (e: Exception) { } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows) { r ->
                if (r is String) {
                    Text(r, color = th.sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                } else if (r is MsgItem) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (r.sent) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.fillMaxWidth(0.80f), horizontalAlignment = if (r.sent) Alignment.End else Alignment.Start) {
                            Box(
                                Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (r.sent) 20.dp else 5.dp, bottomEnd = if (r.sent) 5.dp else 20.dp))
                                    .then(if (r.sent) Modifier.background(Brush.linearGradient(th.sent)) else Modifier.background(th.recv))
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) { Text(r.body, color = if (r.sent) th.sentText else th.text, fontSize = 16.sp) }
                            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.date)), color = th.sub, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(th.card).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (text.isEmpty()) Text("Text message", color = th.sub, fontSize = 16.sp)
                BasicTextField(text, { text = it }, textStyle = TextStyle(color = th.text, fontSize = 16.sp), cursorBrush = SolidColor(th.accent), maxLines = 5, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            val ready = text.isNotBlank()
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (ready) Brush.linearGradient(th.sent) else SolidColor(th.card))
                    .clickable(enabled = ready) { onSend(text.trim()); text = "" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Send, "Send", tint = if (ready) Color.White else th.sub, modifier = Modifier.size(22.dp)) }
        }
    }
}
