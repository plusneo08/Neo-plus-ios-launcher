package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class DialTab(val label: String, val icon: ImageVector) {
    Keypad("Keypad", Icons.Filled.Dialpad),
    Recents("Recents", Icons.Filled.History),
    Missed("Missed", Icons.Filled.PhoneMissed),
    Contacts("Contacts", Icons.Filled.Contacts)
}

private class LogEntry(val id: Long, val number: String, val name: String, val type: Int, val date: Long)
private class ContactEntry(val name: String, val number: String)

private val DialInk = Color(0xFF040A16)
private val CardFill = Color.White.copy(alpha = 0.06f)
private val Hairline = Color.White.copy(alpha = 0.10f)
private val Dim = Color.White.copy(alpha = 0.58f)
private val MissedRed = Color(0xFFFF4D4F)

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun digits(s: String) = s.filter { it.isDigit() }

private fun loadLog(ctx: Context): List<LogEntry> = try {
    val out = ArrayList<LogEntry>()
    ctx.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null, CallLog.Calls.DATE + " DESC"
    )?.use { c ->
        while (c.moveToNext() && out.size < 200) {
            out.add(LogEntry(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3), c.getLong(4)))
        }
    }
    out
} catch (e: Exception) { emptyList() }

private fun loadContacts(ctx: Context): List<ContactEntry> = try {
    val out = ArrayList<ContactEntry>()
    val seen = HashSet<String>()
    ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
    )?.use { c ->
        while (c.moveToNext()) {
            val n = c.getString(0).orEmpty()
            val num = c.getString(1).orEmpty()
            if (n.isBlank() || num.isBlank()) continue
            if (seen.add(n.lowercase() + "|" + digits(num))) out.add(ContactEntry(n, num))
        }
    }
    out
} catch (e: Exception) { emptyList() }

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun whenText(ms: Long): String {
    val days = ((dayStart(System.currentTimeMillis()) - dayStart(ms)) / 86_400_000L).toInt()
    val loc = Locale.getDefault()
    return when {
        days <= 0 -> SimpleDateFormat("HH:mm", loc).format(Date(ms))
        days == 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEEE", loc).format(Date(ms))
        else -> SimpleDateFormat("d MMM", loc).format(Date(ms))
    }
}

private val AvatarColors = listOf(
    Color(0xFF0A84FF) to Color(0xFF5E5CE6), Color(0xFF30D158) to Color(0xFF0A9F6E), Color(0xFFFF9F0A) to Color(0xFFFF6A00),
    Color(0xFFBF5AF2) to Color(0xFF7D3CFF), Color(0xFF38BDF8) to Color(0xFF0369A1), Color(0xFFFF453A) to Color(0xFFB91C1C)
)

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val (a, b) = AvatarColors[(name.hashCode() and 0x7fffffff) % AvatarColors.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(
            if (name.isBlank()) Brush.verticalGradient(listOf(Color(0xFF1B2A44), Color(0xFF0E1829))) else Brush.linearGradient(listOf(a, b))
        ),
        contentAlignment = Alignment.Center
    ) {
        val initial = name.trim().firstOrNull { it.isLetter() }
        if (initial != null) Text(initial.uppercase(), color = Color.White, fontSize = (size.value * 0.40f).sp, fontWeight = FontWeight.SemiBold)
        else Icon(Icons.Filled.Person, null, tint = Dim, modifier = Modifier.size(size * 0.55f))
    }
}

/** '*' and '#' are drawn, so they look the same on every phone font. */
@Composable
private fun KeyGlyph(c: Char, compact: Boolean = false) {
    if (c != '*' && c != '#') {
        Text(c.toString(), color = Color.White, fontSize = if (compact) 22.sp else 29.sp, fontWeight = FontWeight.Light)
        return
    }
    Canvas(Modifier.size(if (compact) 18.dp else 26.dp)) {
        val s = size.width
        val sw = 2.3.dp.toPx()
        if (c == '*') {
            for (i in 0 until 3) {
                val a = Math.toRadians(i * 60.0 + 90.0)
                val dx = (kotlin.math.cos(a) * s * 0.40).toFloat()
                val dy = (kotlin.math.sin(a) * s * 0.40).toFloat()
                drawLine(Color.White, Offset(s / 2 - dx, s / 2 - dy), Offset(s / 2 + dx, s / 2 + dy), sw, StrokeCap.Round)
            }
        } else {
            drawLine(Color.White, Offset(s * 0.36f, s * 0.12f), Offset(s * 0.30f, s * 0.88f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(s * 0.70f, s * 0.12f), Offset(s * 0.64f, s * 0.88f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(s * 0.12f, s * 0.36f), Offset(s * 0.88f, s * 0.36f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(s * 0.12f, s * 0.66f), Offset(s * 0.88f, s * 0.66f), sw, StrokeCap.Round)
        }
    }
}

@Composable
internal fun PadKey(c: Char, size: androidx.compose.ui.unit.Dp, compact: Boolean = false, onKey: (Char) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) 0.93f else 1f, label = "key")
    val sub = when (c) {
        '2' -> "ABC"; '3' -> "DEF"; '4' -> "GHI"; '5' -> "JKL"; '6' -> "MNO"; '7' -> "PQRS"; '8' -> "TUV"; '9' -> "WXYZ"; '0' -> "+"; else -> ""
    }
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(
                if (down) Brush.verticalGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.60f), NeoCallColors.Blue.copy(alpha = 0.28f)))
                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f)))
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), NeoCallColors.Blue.copy(alpha = 0.18f))), CircleShape)
            .pointerInput(c) {
                detectTapGestures(
                    onPress = { down = true; tryAwaitRelease(); down = false },
                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(c) },
                    onLongPress = { if (c == '0') { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onKey('+') } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KeyGlyph(c, compact)
            if (sub.isNotEmpty() && !compact) Text(sub, color = NeoCallColors.Cyan, fontSize = 8.5.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DialerScreen(initial: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(DialTab.Keypad) }
    var number by remember { mutableStateOf(initial) }
    var hasLog by remember { mutableStateOf(has(ctx, Manifest.permission.READ_CALL_LOG)) }
    var hasContacts by remember { mutableStateOf(has(ctx, Manifest.permission.READ_CONTACTS)) }
    var log by remember { mutableStateOf(emptyList<LogEntry>()) }
    var contacts by remember { mutableStateOf(emptyList<ContactEntry>()) }

    val readPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLog = has(ctx, Manifest.permission.READ_CALL_LOG)
        hasContacts = has(ctx, Manifest.permission.READ_CONTACTS)
    }
    var pending by remember { mutableStateOf("") }
    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pending.isNotBlank() && NeoDialer.place(ctx, pending)) onClose()
        pending = ""
    }
    fun dial(n: String) {
        if (n.isBlank()) return
        if (!has(ctx, Manifest.permission.CALL_PHONE)) { pending = n; callPerm.launch(Manifest.permission.CALL_PHONE); return }
        if (NeoDialer.place(ctx, n)) onClose()
    }

    LaunchedEffect(hasLog, hasContacts, tab) {
        withContext(Dispatchers.IO) {
            if (hasLog) log = loadLog(ctx)
            if (hasContacts && (contacts.isEmpty() || tab == DialTab.Contacts)) contacts = loadContacts(ctx)
        }
    }

    var isDefault by remember { mutableStateOf(NeoDialer.isDefault(ctx)) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = NeoDialer.isDefault(ctx) }

    val missedRecent = remember(log) { val cut = System.currentTimeMillis() - 86_400_000L; log.count { it.type == CallLog.Calls.MISSED_TYPE && it.date > cut } }

    Box(
        Modifier.fillMaxSize().background(DialInk).drawBehind {
            val r = size.width * 1.1f
            drawCircle(Brush.radialGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.30f), Color.Transparent), center = Offset(size.width * 0.5f, 0f), radius = r), r, Offset(size.width * 0.5f, 0f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7C3AED).copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width, size.height), radius = r), r, Offset(size.width, size.height))
        }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---- header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.neo_art_connected), null,
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, NeoCallColors.Cyan.copy(alpha = 0.5f), RoundedCornerShape(13.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("NEO PLUS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text(tab.label, color = NeoCallColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(CardFill).clickable { onClose() }.semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
            }

            if (!isDefault) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                        .background(NeoCallColors.Blue.copy(alpha = 0.18f)).border(1.dp, NeoCallColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .clickable { NeoDialer.requestIntent(ctx)?.let { try { roleLauncher.launch(it) } catch (e: Exception) { } } }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Call, null, tint = NeoCallColors.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Make Neo Plus your default Phone app", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("SET", color = NeoCallColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    DialTab.Keypad -> KeypadTab(number, contacts, { number += it }, { if (number.isNotEmpty()) number = number.dropLast(1) }, { number = "" }, { number = it }, { dial(number) })
                    DialTab.Recents -> LogTab(log, false, hasLog, { readPerms.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)) }) { dial(it) }
                    DialTab.Missed -> LogTab(log.filter { it.type == CallLog.Calls.MISSED_TYPE }, true, hasLog, { readPerms.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)) }) { dial(it) }
                    DialTab.Contacts -> ContactsTab(contacts, hasContacts, { readPerms.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)) }) { dial(it) }
                }
            }

            // ---- tab bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(30.dp))
                    .background(Color.White.copy(alpha = 0.07f)).border(1.dp, Hairline, RoundedCornerShape(30.dp)).padding(5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DialTab.values().forEach { t ->
                    val sel = t == tab
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(if (sel) Brush.verticalGradient(listOf(NeoCallColors.Blue.copy(alpha = 0.55f), NeoCallColors.Blue.copy(alpha = 0.25f))) else SolidColor(Color.Transparent))
                                .clickable { tab = t }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(t.icon, null, tint = if (sel) Color.White else Dim, modifier = Modifier.size(22.dp))
                            Text(t.label, color = if (sel) Color.White else Dim, fontSize = 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        if (t == DialTab.Missed && missedRecent > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 14.dp).size(17.dp).clip(CircleShape).background(MissedRed),
                                contentAlignment = Alignment.Center
                            ) { Text(if (missedRecent > 9) "9+" else missedRecent.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadTab(
    number: String, contacts: List<ContactEntry>, onKey: (Char) -> Unit, onBack: () -> Unit, onClear: () -> Unit,
    onPick: (String) -> Unit, onCall: () -> Unit
) {
    val ready = number.isNotBlank()
    val q = digits(number)
    val matches = remember(number, contacts) {
        if (q.length < 2) emptyList() else contacts.filter { digits(it.number).contains(q) }.distinctBy { it.name }.take(3)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val key = ((maxHeight - 250.dp) / 4).coerceIn(52.dp, 74.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.4f))
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                if (ready) Text(
                    number, color = Color.White, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Clip,
                    fontSize = if (number.length > 15) 24.sp else if (number.length > 11) 30.sp else 38.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.semantics { contentDescription = "Number" }
                ) else Text("Enter number", color = Color.White.copy(alpha = 0.28f), fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
            Box(Modifier.fillMaxWidth(0.72f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, NeoCallColors.Cyan.copy(alpha = 0.6f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { m ->
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(NeoCallColors.Blue.copy(alpha = 0.22f))
                                .border(1.dp, NeoCallColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).clickable { onPick(m.number) }
                                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(m.name, 26.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(m.name.take(12), color = Color.White, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in listOf("123", "456", "789", "*0#")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { row.forEach { PadKey(it, key, false, onKey) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.width(key * 3 + 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(key))
                Box(Modifier.width(22.dp))
                Box(
                    Modifier.size(key).clip(CircleShape)
                        .background(
                            if (ready) Brush.verticalGradient(listOf(Color(0xFF4ADE80), NeoCallColors.Green, Color(0xFF15803D)))
                            else Brush.verticalGradient(listOf(Color(0xFF1F3B2B), Color(0xFF16291F)))
                        )
                        .clickable { onCall() }.semantics { contentDescription = "Call" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Call, "Call", tint = Color.White.copy(alpha = if (ready) 1f else 0.45f), modifier = Modifier.size(32.dp)) }
                Box(Modifier.width(22.dp))
                Box(
                    Modifier.size(key).clip(CircleShape).pointerInput(Unit) { detectTapGestures(onTap = { onBack() }, onLongPress = { onClear() }) },
                    contentAlignment = Alignment.Center
                ) { if (ready) Icon(Icons.Filled.Backspace, "Delete", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(26.dp)) }
            }
            Spacer(Modifier.weight(0.3f))
        }
    }
}

@Composable
private fun NeedAccess(title: String, text: String, onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(36.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(NeoCallColors.Blue.copy(alpha = 0.2f)).border(1.dp, NeoCallColors.Cyan.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Contacts, null, tint = NeoCallColors.Cyan, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.clip(RoundedCornerShape(26.dp)).background(Brush.horizontalGradient(listOf(NeoCallColors.Cyan, NeoCallColors.Blue))).clickable { onAllow() }
                .padding(horizontal = 30.dp, vertical = 12.dp)
        ) { Text("Allow access", color = Color.White, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(36.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LogTab(entries: List<LogEntry>, missedOnly: Boolean, allowed: Boolean, onAllow: () -> Unit, onCall: (String) -> Unit) {
    if (!allowed) { NeedAccess("See your calls", "Allow Neo Plus to read your call history and contacts so recent and missed calls show here.", onAllow); return }
    if (entries.isEmpty()) {
        EmptyState(if (missedOnly) Icons.Filled.PhoneMissed else Icons.Filled.History, if (missedOnly) "No missed calls" else "No recent calls")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(entries, key = { it.id }) { e ->
            val missed = e.type == CallLog.Calls.MISSED_TYPE || e.type == CallLog.Calls.REJECTED_TYPE || e.type == CallLog.Calls.BLOCKED_TYPE
            val shown = e.name.ifBlank { e.number.ifBlank { "Private number" } }
            val (icon, tint) = when (e.type) {
                CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade to NeoCallColors.Cyan
                CallLog.Calls.INCOMING_TYPE -> Icons.Filled.CallReceived to NeoCallColors.Green
                else -> Icons.Filled.CallMissed to MissedRed
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(18.dp)).background(CardFill)
                    .clickable { onCall(e.number) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(e.name, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(shown, color = if (missed) MissedRed else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (e.name.isNotBlank()) e.number else when (e.type) { CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.INCOMING_TYPE -> "Incoming"; else -> "Missed" },
                            color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(whenText(e.date), color = Dim, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(NeoCallColors.Green.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, null, tint = NeoCallColors.Green, modifier = Modifier.size(18.dp))
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ContactsTab(contacts: List<ContactEntry>, allowed: Boolean, onAllow: () -> Unit, onCall: (String) -> Unit) {
    if (!allowed) { NeedAccess("See your contacts", "Allow Neo Plus to read your contacts so you can call them in one tap.", onAllow); return }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, contacts) {
        if (query.isBlank()) contacts else contacts.filter { it.name.contains(query, true) || digits(it.number).contains(digits(query).ifEmpty { "\u0000" }) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Hairline, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = Dim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search ${contacts.size} contacts", color = Color.White.copy(alpha = 0.35f), fontSize = 16.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(NeoCallColors.Cyan), modifier = Modifier.fillMaxWidth())
            }
        }
        if (shown.isEmpty()) { EmptyState(Icons.Filled.Contacts, if (contacts.isEmpty()) "No contacts yet" else "No match"); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            var last = ' '
            val grouped = shown.map { c ->
                val l = c.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
                val head = l != last
                last = l
                Triple(c, l, head)
            }
            items(grouped) { (c, l, head) ->
                if (head) Text(l.toString(), color = NeoCallColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onCall(c.number) }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(c.name, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.number, color = Dim, fontSize = 13.sp, maxLines = 1)
                    }
                    Icon(Icons.Filled.Call, null, tint = NeoCallColors.Green, modifier = Modifier.size(20.dp))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
