package com.neo.plus.iosluancher

import android.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.json.JSONArray
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val UserBlueTop = Color(0xFF2E9BFF)
private val UserBlueBottom = Color(0xFF0A74F5)

/** Whether the phone can really blur what is behind a page (Android 12+). Older phones get a darker cover instead. */
private val canBlur = Build.VERSION.SDK_INT >= 31

/**
 * A page that opens over the assistant (settings, the task view): a nearly solid dark card so it is read clearly and nothing
 * behind it shows through. Everything behind it is blurred by the caller.
 */
private fun Modifier.sheetCard(shape: RoundedCornerShape): Modifier =
    this.clip(shape).background(Color(0xF2121218)).border(0.8.dp, Color.White.copy(alpha = 0.22f), shape)

/** The glowing assistant orb: used on the home screen and in the chat header. */
@Composable
fun AssistantOrb(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "angle"
    )
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF0B0B1A)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }.background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFF3B82FF), Color(0xFFA855F7), Color(0xFFFF5CA8),
                        Color(0xFFFFB347), Color(0xFF34D5C9), Color(0xFF3B82FF)
                    )
                )
            )
        )
        Box(
            Modifier.size(size * 0.62f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.12f))))
        )
        Box(Modifier.fillMaxSize().border(0.8.dp, Color.White.copy(alpha = 0.35f), CircleShape))
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, desc: String, tint: Color = Color.White.copy(alpha = 0.85f), onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0..2) {
            val a = 0.30f + 0.70f * (0.5f + 0.5f * sin((phase - i * 0.18f) * 2f * PI.toFloat()))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = a)))
        }
    }
}

/** A file waiting in the text box to be sent with the next message. */
private class Pending(val name: String) {
    var file by mutableStateOf<java.io.File?>(null)
    var failed by mutableStateOf(false)
}

private val attachTag = Regex("\\[Attached file: ([^\\]]+)\\]")

/** The card shown for an attached file: type badge, name, a spinner while it is being copied and a remove button. */
@Composable
private fun AttachmentCard(name: String, loading: Boolean = false, onRemove: (() -> Unit)? = null) {
    val ext = name.substringAfterLast('.', "").uppercase().take(5).ifBlank { "FILE" }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.width(132.dp).heightIn(min = 108.dp).clip(shape).background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.30f), shape).padding(10.dp)
    ) {
        Box(
            Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)
        ) { Text(ext, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).size(30.dp).clip(CircleShape).background(Color.White).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, "Remove file", tint = Color.Black, modifier = Modifier.size(18.dp)) }
        }
        Text(
            name, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(top = 44.dp).fillMaxWidth(if (loading) 0.66f else 1f)
        )
        if (loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.BottomEnd).size(30.dp), color = Color.White, strokeWidth = 3.dp,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

private val NotifPalette = listOf(
    Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFFF9F0A), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFF64D2FF), Color(0xFFFFD60A)
)

/** The notifications as a glass card: one row per notification with a coloured app badge, app name, time, title and text. */
@Composable
private fun NotificationCards(json: String) {
    val items = remember(json) {
        try { val a = JSONArray(json); (0 until a.length()).map { a.getJSONObject(it) } } catch (e: Exception) { emptyList() }
    }
    if (items.isEmpty()) return
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.10f))))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape).padding(vertical = 6.dp)
    ) {
        Text(
            "Notifications", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        )
        items.forEachIndexed { i, o ->
            val app = o.optString("app")
            val title = o.optString("title")
            val text = o.optString("text")
            val whenText = o.optString("when")
            val tint = NotifPalette[(app.lowercase().hashCode() and 0x7fffffff) % NotifPalette.size]
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                    Text(app.take(1).uppercase().ifBlank { "•" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(whenText, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1)
                    }
                    if (title.isNotBlank()) Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (text.isNotBlank()) Text(text, color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i < items.lastIndex) Box(Modifier.padding(start = 60.dp, end = 12.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val mine = m.fromUser
    val files = remember(m.text) { attachTag.findAll(m.text).map { it.groupValues[1].trim() }.toList() }
    val body = remember(m.text) { m.text.replace(attachTag, "").trim() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (files.isNotEmpty()) {
            Row(
                Modifier.widthIn(max = 320.dp).horizontalScroll(rememberScrollState()).padding(bottom = if (body.isNotEmpty()) 6.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { files.forEach { AttachmentCard(it) } }
        }
        // Notifications are drawn as a card list; the bubble under it is only the one-line count.
        m.notes.filter { it.startsWith(NOTIF_MARK) }.forEach {
            NotificationCards(it.removePrefix(NOTIF_MARK))
            Spacer(Modifier.height(8.dp))
        }
        val shape = RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp)
        val bg = when {
            mine -> Brush.verticalGradient(listOf(UserBlueTop, UserBlueBottom))
            m.error -> SolidColor(Color(0xFF7A2A2A).copy(alpha = 0.80f))
            else -> SolidColor(Color.White.copy(alpha = 0.16f))
        }
        if (body.isNotEmpty() || files.isEmpty()) {
            Box(Modifier.widthIn(max = 310.dp).clip(shape).background(bg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(body, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
            }
        }
        m.notes.filter { !it.startsWith(NOTIF_MARK) }.forEach { n ->
            Text(
                "✓ $n", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun GlassField(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false, phone: Boolean = false) {
    Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), 0.18f).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (phone) KeyboardType.Phone else KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsSheet(a: AssistantViewModel, apps: List<AppEntry>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val provider = Provider.GEMINI
    // Any number of Gemini keys: one is used at a time and the next takes over when a key hits its limit.
    val keys = remember { mutableStateListOf<String>().apply { addAll(KeyPool.parse(a.apiKey)); while (size < 3) add("") } }
    fun allKeys() = keys.joinToString("\n")
    var gender by remember { mutableStateOf(a.voiceGender) }
    var voiceKey by remember { mutableStateOf(a.voiceKey) }
    var showSites by remember { mutableStateOf(false) }
    var limitText by remember { WebUsage.load(ctx); mutableStateOf(WebUsage.limit.toString()) }
    var reportNumber by remember { mutableStateOf(TaskReports.smsNumber(ctx)) }
    var reportMsg by remember { mutableStateOf("") }
    var emailFrom by remember { mutableStateOf(EmailReport.from(ctx)) }
    var emailPass by remember { mutableStateOf("") }
    var emailTo by remember { mutableStateOf(EmailReport.to(ctx)) }
    var emailMsg by remember { mutableStateOf(EmailReport.lastError(ctx).let { if (it.isBlank()) "" else "Last email problem: $it" }) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The settings are separate pages: 0 = the menu, 1 = API keys, 2 = Voice, 3 = Website authorisation, 4 = Apps, 5 = Task reports.
    var page by remember { mutableStateOf(0) }
    fun saveAll() {
        a.saveSettings(Provider.GEMINI, allKeys(), "")
        a.saveVoice(gender, voiceKey)
        WebUsage.setLimit(ctx, limitText.toIntOrNull() ?: WebUsage.DEFAULT_LIMIT)
    }
    val note = Color.White.copy(alpha = 0.7f)

    @Composable
    fun MenuRow(title: String, sub: String, go: Int) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f))
                .clickable { page = go }.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }

    @Composable
    fun Footer(saveLabel: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                    .clickable { page = 0 }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Back", color = Color.White, fontSize = 15.sp) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(UserBlueBottom)
                    .clickable { saveAll(); page = 0 }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text(saveLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (canBlur) 0.45f else 0.72f)).consumeTouches().noRipple(onDone),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.then(if (showSites && canBlur) Modifier.blur(16.dp) else Modifier).padding(20.dp).fillMaxWidth().sheetCard(RoundedCornerShape(28.dp)).noRipple { }
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (page) {
                0 -> {
                    Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    MenuRow("API keys", "Your Gemini keys: add, remove, see which one is in use", 1)
                    MenuRow("Voice", "Female or male voice, and hear it", 2)
                    MenuRow("Website authorisation", "Websites the assistant may operate, saved logins and the daily limit", 3)
                    MenuRow("Apps", "Choose which phone apps the assistant may open and control", 4)
                    MenuRow("Task reports by text", if (TaskReports.smsNumber(ctx).isBlank()) "Add the number the assistant texts when a task is done" else "Texting " + TaskReports.smsNumber(ctx), 5)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .clickable { onDone() }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Close", color = Color.White, fontSize = 15.sp) }
                }

                1 -> {
                    Text("API keys", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Neo Plus Assistant chats and speaks through your own Gemini keys. The keys stay on this phone and are only sent to Google. Calls, messages and notifications never use it: the phone handles those itself.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    val status = KeyPool.status(a.apiKey)
                    keys.forEachIndexed { i, k ->
                        GlassField(k, { keys[i] = it }, "Gemini API key ${i + 1}", password = true)
                        val st = status.firstOrNull { k.trim().endsWith(it.masked.removePrefix("…")) && k.isNotBlank() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                st?.text ?: if (k.isBlank()) "Empty" else "New key",
                                color = if (st?.text?.startsWith("Limit") == true || st?.text?.startsWith("Rejected") == true) Color(0xFFFFB347)
                                else if (st?.active == true) Color(0xFF7DE08A) else Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                            if (keys.size > 1) Text(
                                "Remove", color = Color(0xFFFF8A80), fontSize = 12.sp,
                                modifier = Modifier.clickable { keys.removeAt(i) }
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .clickable { keys.add("") }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("+ Add another key", color = Color.White, fontSize = 14.sp) }
                    Text(
                        "Save up to 3 keys here (as many more as you like with “+ Add another key”, there is no limit). Neo Plus uses only one key at a time, and when Google says that key has reached its limit it switches to the next one by itself (and comes back to a key once its limit has reset). Use keys from different Google accounts or projects: keys from the same project share one quota.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Text(
                        "Get a ${provider.label} key", color = Color(0xFF7DB8FF), fontSize = 14.sp,
                        modifier = Modifier.clickable { ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }
                    )
                    Footer("Save keys")
                }

                2 -> {
                    Text("Voice", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("female" to "Female", "male" to "Male").forEach { (g, label) ->
                            val sel = g == gender
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(50))
                                    .background(if (sel) UserBlueBottom else Color.White.copy(alpha = 0.16f))
                                    .clickable { gender = g }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    Text(
                        "The natural, human-sounding voice also comes from Gemini. Without a key, the phone’s own voice is used.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .clickable {
                                saveAll()
                                Speaker.speak(ctx, "Hi, I’m your Neo Plus assistant. How can I help you today?")
                            }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Hear this voice", color = Color.White, fontSize = 15.sp) }
                    Footer("Save voice")
                }

                4 -> {
                    var limited by remember { mutableStateOf(AppAccess.limited(ctx)) }
                    var conn by remember { mutableStateOf(AppAccess.connected(ctx)) }
                    var query by remember { mutableStateOf("") }
                    Text("Apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Nothing to log in here: the assistant uses the apps as they already are on your phone, with the logins you already have. " +
                            "Switch “Only connected apps” on and the assistant can open and control only the apps you turn on below.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Only connected apps", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (limited) "${conn.size} app(s) connected" else "Off: the assistant may use every app",
                                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp
                            )
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (limited) UserBlueBottom else Color.White.copy(alpha = 0.18f))
                                .clickable { limited = !limited; AppAccess.setLimited(ctx, limited) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text(if (limited) "On" else "Off", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Connect all" to true, "Disconnect all" to false).forEach { (label, on) ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                                    .clickable {
                                        AppAccess.setAll(ctx, apps.map { it.pkg }, on)
                                        conn = AppAccess.connected(ctx)
                                    }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = Color.White, fontSize = 14.sp) }
                        }
                    }
                    GlassField(query, { query = it }, "Search apps")
                    apps.filter { query.isBlank() || it.shownLabel.contains(query.trim(), ignoreCase = true) }
                        .sortedBy { it.shownLabel.lowercase() }
                        .forEach { app ->
                            val on = app.pkg in conn
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    app.icon, app.shownLabel, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                                )
                                Text(app.shownLabel, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Box(
                                    Modifier.clip(RoundedCornerShape(50)).background(if (on) UserBlueBottom else Color.White.copy(alpha = 0.18f))
                                        .clickable { AppAccess.setConnected(ctx, app.pkg, !on); conn = AppAccess.connected(ctx) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text(if (on) "On" else "Off", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .clickable { page = 0 }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Back", color = Color.White, fontSize = 15.sp) }
                }

                5 -> {
                    Text("Task reports", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("By email (free, sent from your own mailbox, no SIM used)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Every finished task is emailed to you. Enter the email address that sends the reports and its APP PASSWORD " +
                            "(Gmail: Google Account > Security > 2-Step Verification > App passwords). Your normal password will not work. " +
                            "The app password is stored encrypted on this phone. Leave the email empty and save to turn email reports off.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    GlassField(emailFrom, { emailFrom = it.trim().take(120); emailMsg = "" }, "Your email, e.g. you@gmail.com")
                    GlassField(emailPass, { emailPass = it.take(64); emailMsg = "" },
                        if (EmailReport.hasPassword(ctx)) "App password (saved, leave empty to keep it)" else "App password", password = true)
                    GlassField(emailTo, { emailTo = it.trim().take(120); emailMsg = "" }, "Send reports to (empty = the same email)")
                    if (emailMsg.isNotBlank()) Text(emailMsg, color = if (emailMsg.startsWith("Couldn") || emailMsg.startsWith("Last")) Color(0xFFFFB347) else Color(0xFF7DE08A), fontSize = 13.sp, lineHeight = 18.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(UserBlueBottom)
                                .clickable { emailMsg = EmailReport.save(ctx, emailFrom, emailPass, emailTo); if (!emailMsg.startsWith("Couldn")) emailPass = "" }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Save email", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                                .clickable {
                                    emailMsg = "Sending the test email…"
                                    val c = ctx.applicationContext
                                    Thread { emailMsg = EmailReport.sendTest(c) }.start()
                                }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Send test", color = Color.White, fontSize = 15.sp) }
                    }
                    Text("By text message (sent from your own SIM, uses airtime)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Add the phone number the assistant should text when it finishes a task (include the country code, e.g. +265…). Each finished task is sent as a text message to this number, and after 5 finished tasks the assistant calls you instead. Leave it empty to turn texts off: you still get a notification for each task.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    GlassField(reportNumber, { reportNumber = it.filter { c -> c.isDigit() || c == '+' }.take(20); reportMsg = "" }, "Phone number to text, e.g. +265…", phone = true)
                    if (reportMsg.isNotBlank()) Text(reportMsg, color = Color(0xFF7DE08A), fontSize = 13.sp, lineHeight = 18.sp)
                    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Text(
                            "Allow SMS for Neo Plus, or the texts can’t be sent.", color = Color(0xFFFFB347), fontSize = 13.sp,
                            modifier = Modifier.clickable { smsPermission.launch(Manifest.permission.SEND_SMS) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                                .clickable { page = 0 }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Back", color = Color.White, fontSize = 15.sp) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(UserBlueBottom)
                                .clickable {
                                    val r = TaskReports.setSms(ctx, reportNumber, quiet = true)
                                    if (r.startsWith("Couldn")) reportMsg = r
                                    else {
                                        if (reportNumber.isNotBlank() && androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                            smsPermission.launch(Manifest.permission.SEND_SMS)
                                        page = 0
                                    }
                                }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Save number", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }

                else -> {
                    Text("Website authorisation", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Chat, phone control and website tasks start on Gemini 3.8 Flash. The stronger Gemini 4 Argon (needs a paid key) only takes over a website task after two failed steps. Websites you authorise here can be operated with the login you save; the login is encrypted on this phone, the AI never sees the password, and typing it into a login form is done by the phone without any AI request.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .clickable { showSites = true }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Authorised websites", color = Color.White, fontSize = 15.sp) }
                    Text("Daily limit for website tasks", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    GlassField(limitText, { limitText = it.filter { c -> c.isDigit() }.take(6) }, "Requests per day (0 = no limit)")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            WebUsage.summary() + " · " + WebUsage.saved + " step(s) saved by offline replay",
                            color = if (WebUsage.limit > 0 && WebUsage.used >= WebUsage.limit) Color(0xFFFFB347) else note,
                            fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f)
                        )
                        Text("Reset", color = Color(0xFF7DB8FF), fontSize = 12.sp, modifier = Modifier.clickable { WebUsage.resetToday(ctx) }.padding(start = 8.dp))
                    }
                    Text(
                        "When the limit is reached, website tasks stop and say so. In an Excel batch the first row is done by Gemini and recorded; the other rows replay those steps on the phone with no request, and Gemini is asked again only if a step does not match the page.",
                        color = note, fontSize = 13.sp, lineHeight = 18.sp
                    )
                    Footer("Save limit")
                }
            }
        }
        if (showSites) SitesSheet { showSites = false }
    }
}

@Composable
private fun SitesSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var sites by remember { mutableStateOf(SiteVault.all(ctx)) }
    var domain by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (canBlur) 0.5f else 0.78f)).consumeTouches().noRipple(onDone),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth().sheetCard(RoundedCornerShape(28.dp)).noRipple { }
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Authorised websites", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "The assistant can only work on these websites. Switching one on gives it full control there: it can log in, click, fill forms, upload and download files. It never opens other websites. Say “stop the website task” any time to cancel.",
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp
            )
            if (sites.isEmpty()) Text("No websites yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            sites.forEach { site ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(site.domain, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (site.username.isNotBlank()) "Login saved: ${site.username}" else "No login saved",
                            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp
                        )
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (site.authorized) UserBlueBottom else Color.White.copy(alpha = 0.18f))
                            .clickable { SiteVault.setAuthorized(ctx, site.domain, !site.authorized); sites = SiteVault.all(ctx) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text(if (site.authorized) "On" else "Off", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    Text(
                        "Remove", color = Color(0xFFFF8A80), fontSize = 13.sp,
                        modifier = Modifier.clickable { SiteVault.remove(ctx, site.domain); sites = SiteVault.all(ctx) }.padding(4.dp)
                    )
                }
            }
            Text("Add a website", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            GlassField(domain, { domain = it }, "Website, e.g. example.com")
            GlassField(user, { user = it }, "Username or email (optional)")
            GlassField(pass, { pass = it }, "Password (optional)", password = true)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(UserBlueBottom)
                    .clickable {
                        if (SiteVault.normalizeDomain(domain).isNotBlank()) {
                            SiteVault.put(ctx, domain, user.trim(), pass, true)
                            sites = SiteVault.all(ctx); domain = ""; user = ""; pass = ""
                        }
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Authorise and save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                    .clickable { onDone() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Done", color = Color.White, fontSize = 15.sp) }
        }
    }
}

/** One line of a task, drawn as a chat bubble so every step, result, fix and problem can be read clearly. */
@Composable
private fun TaskBubble(l: TaskLine) {
    val c = when (l.kind) {
        TaskLine.Kind.OK -> Color(0xFF30D158)
        TaskLine.Kind.WARN -> Color(0xFFFFD60A)
        TaskLine.Kind.ERROR -> Color(0xFFFF6B60)
        TaskLine.Kind.FIX -> Color(0xFF64D2FF)
        TaskLine.Kind.STEP -> Color.White
        TaskLine.Kind.INFO -> Color.White.copy(alpha = 0.7f)
    }
    val bg = when (l.kind) {
        TaskLine.Kind.OK -> Color(0xFF30D158).copy(alpha = 0.16f)
        TaskLine.Kind.WARN -> Color(0xFFFFD60A).copy(alpha = 0.16f)
        TaskLine.Kind.ERROR -> Color(0xFFFF453A).copy(alpha = 0.22f)
        TaskLine.Kind.FIX -> Color(0xFF64D2FF).copy(alpha = 0.16f)
        TaskLine.Kind.STEP -> Color.White.copy(alpha = 0.14f)
        TaskLine.Kind.INFO -> Color.White.copy(alpha = 0.08f)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(l.text.trim(), color = c, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Text(l.time, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
    }
}

/**
 * The full-screen view of a running (or finished) website / Excel task: the request as a chat message, then every step the
 * assistant takes, what happened, what it fixed and what went wrong, with the request counter, Continue / Stop and Watch page.
 * The chat behind it is blurred so this page is the only thing to read.
 */
@Composable
private fun TaskScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { WebUsage.load(ctx) }
    val state = rememberLazyListState()
    LaunchedEffect(TaskLog.lines.size) { if (TaskLog.lines.isNotEmpty()) state.animateScrollToItem(TaskLog.lines.size) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (canBlur) 0.40f else 0.70f)).consumeTouches()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(safeInsets()).padding(12.dp).sheetCard(RoundedCornerShape(28.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantOrb(30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Task", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val progress = if (TaskLog.total > 0) " · ${TaskLog.done + TaskLog.failed}/${TaskLog.total} rows" + (if (TaskLog.failed > 0) " · ${TaskLog.failed} problem(s)" else "") else ""
                    Text(
                        (if (TaskLog.waiting) "Waiting for you" else TaskLog.status) + progress,
                        color = if (TaskLog.waiting) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1
                    )
                }
                RoundIconButton(Icons.Filled.Close, "Minimise") { TaskLog.fullView = false }
            }
            if (TaskLog.total > 0) {
                val frac = ((TaskLog.done + TaskLog.failed).toFloat() / TaskLog.total).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF30D158)))
                }
            }
            Text(
                WebUsage.summary() + " · " + WebUsage.saved + " step(s) done offline",
                color = if (WebUsage.limit > 0 && WebUsage.used >= WebUsage.limit) Color(0xFFFFB347) else Color.White.copy(alpha = 0.55f), fontSize = 11.sp
            )
            LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "goal") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        Box(
                            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                .background(Brush.verticalGradient(listOf(UserBlueTop, UserBlueBottom))).padding(horizontal = 14.dp, vertical = 10.dp)
                        ) { Text(TaskLog.title.ifBlank { "Task" }, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp) }
                    }
                }
                items(TaskLog.lines.toList(), key = { it.id }) { l -> TaskBubble(l) }
            }
            if (TaskLog.waiting) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0xFF30D158)).clickable { WebTask.resume() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Continue — I did the CAPTCHA / code", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                        .clickable { TaskLog.showBrowser = !TaskLog.showBrowser }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (TaskLog.showBrowser) "Hide page" else "Watch page", color = Color.White, fontSize = 15.sp) }
                if (TaskLog.active) Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0xFFFF453A).copy(alpha = 0.85f))
                        .clickable { TaskLog.stop() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Stop", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                else Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(UserBlueBottom)
                        .clickable { TaskLog.dismissed = true }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Close", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Full-screen assistant: chat like Gemini, control the phone like Siri. */
@Composable
fun AssistantOverlay(a: AssistantViewModel, apps: List<AppEntry>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val taskOpen = TaskLog.fullView && TaskLog.lines.isNotEmpty() && !TaskLog.dismissed
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateListOf<Pending>() }

    /** Sends the typed or spoken instruction together with every file waiting in the box. */
    fun sendWithFiles(text: String) {
        val files = pending.mapNotNull { it.file?.name }
        val body = text.trim().ifBlank { if (files.isNotEmpty()) "Here is a file for you." else "" }
        if (body.isBlank()) return
        val full = if (files.isEmpty()) body else body + "\n" + files.joinToString("\n") { "[Attached file: $it]" }
        pending.clear()
        a.send(full, apps)
    }

    // Close itself after an action took the user to another app / screen
    val seenClose = remember { mutableIntStateOf(a.closeSignal) }
    LaunchedEffect(a.closeSignal) { if (a.closeSignal != seenClose.intValue) onClose() }

    LaunchedEffect(a.messages.size, a.busy, a.messages.lastOrNull()?.text?.length ?: 0) {
        val count = a.messages.size + if (a.busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // In-app speech recognition: no Google dialog. The panel only shows what you say while you are speaking.
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var alts by remember { mutableStateOf<List<String>>(emptyList()) }
    // Conversation mode: after the first question the mic stays on for follow-ups and turns itself off after
    // 10 seconds without a word (the countdown only runs while nothing is being said, thought or spoken).
    var keepListening by remember { mutableStateOf(false) }
    val voiceAt = remember { longArrayOf(0L) }
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
    }

    fun finishWith(text: String?) {
        BeepMute.restore(ctx)
        voiceAt[0] = System.currentTimeMillis()
        listening = false; heard = ""; alts = emptyList()
        if (!text.isNullOrBlank()) sendWithFiles(text.trim())
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { voiceAt[0] = System.currentTimeMillis() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                if (l.isEmpty()) return
                voiceAt[0] = System.currentTimeMillis()
                heard = l.first()
                alts = l.drop(1).distinct().take(2)
            }
            override fun onResults(results: Bundle?) {
                finishWith(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: heard)
            }
            override fun onError(error: Int) {
                BeepMute.restore(ctx)
                val partial = heard
                listening = false; heard = ""; alts = emptyList()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (partial.isNotBlank()) sendWithFiles(partial.trim())
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        keepListening = false
                        Toast.makeText(ctx, "Allow the microphone for Neo Plus to talk to me.", Toast.LENGTH_LONG).show()
                    }
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    else -> if (!keepListening) Toast.makeText(ctx, "I couldn’t hear that, try again.", Toast.LENGTH_SHORT).show()
                }
            }
        })
        onDispose { try { recognizer?.destroy() } catch (e: Exception) { }; BeepMute.restoreNow(ctx) }
    }

    fun startRecognizer() {
        val r = recognizer
        if (r == null) {
            Toast.makeText(ctx, "No speech recognizer is installed on this phone.", Toast.LENGTH_SHORT).show()
            return
        }
        Speaker.stop()
        heard = ""; alts = emptyList()
        BeepMute.mute(ctx)
        try {
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            )
            listening = true
        } catch (e: Exception) {
            listening = false
            BeepMute.restoreNow(ctx)
            Toast.makeText(ctx, "Couldn’t start listening.", Toast.LENGTH_SHORT).show()
        }
    }

    fun turnMicOff(announce: Boolean) {
        BeepMute.restore(ctx)
        keepListening = false
        try { recognizer?.cancel() } catch (e: Exception) { }
        listening = false; heard = ""; alts = emptyList()
        if (announce) Toast.makeText(ctx, "Mic off", Toast.LENGTH_SHORT).show()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { voiceAt[0] = System.currentTimeMillis(); startRecognizer() }
        else { keepListening = false; Toast.makeText(ctx, "Allow the microphone for Neo Plus to talk to me.", Toast.LENGTH_LONG).show() }
    }

    fun listen() {
        if (listening || keepListening) { turnMicOff(false); return } // tapping the mic again switches it off
        keepListening = true
        voiceAt[0] = System.currentTimeMillis()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecognizer()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(keepListening) {
        while (keepListening) {
            delay(300)
            val now = System.currentTimeMillis()
            // Busy thinking, speaking the reply, or the user is mid-sentence: the 10 s countdown waits.
            if (a.busy || Speaker.speaking || (listening && heard.isNotBlank())) { voiceAt[0] = now; continue }
            if (now - voiceAt[0] >= 10_000L) { turnMicOff(true); break }
            // Reopen the mic for the next command once the reply is finished (or after a silent timeout).
            if (!listening && now - voiceAt[0] >= 700L) startRecognizer()
        }
    }

    // Several files can be picked at once; each shows as a card (with a spinner while it is copied) next to the text box.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val p = Pending(FileTools.nameOf(ctx, uri))
            pending += p
            scope.launch {
                val f = withContext(Dispatchers.IO) { FileTools.importUri(ctx, uri) }
                if (f != null) p.file = f
                else {
                    p.failed = true
                    pending.remove(p)
                    Toast.makeText(ctx, "Couldn’t open that file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun submit() {
        if (input.isBlank() && pending.isEmpty()) return
        if (a.busy && !WebTask.waitingForUser) { Toast.makeText(ctx, "One moment, I’m still answering.", Toast.LENGTH_SHORT).show(); return }
        if (pending.any { it.file == null && !it.failed }) { Toast.makeText(ctx, "Still getting the file ready…", Toast.LENGTH_SHORT).show(); return }
        sendWithFiles(input)
        input = ""
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .then(if ((showSettings || taskOpen) && canBlur) Modifier.blur(18.dp) else Modifier)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.74f))))
                .consumeTouches()
                .windowInsetsPadding(safeInsets())
                .imePadding()
        ) {
            // ---- header
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistantOrb(32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Neo Plus Assistant", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        (if (a.configured) "Gemini" else "Add a Gemini key") + " · v3.11.1",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1
                    )
                }
                RoundIconButton(
                    if (a.speak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    if (a.speak) "Mute replies" else "Read replies aloud"
                ) { a.updateSpeak(!a.speak) }
                RoundIconButton(Icons.Filled.GraphicEq, "Floating assistant: listen over other apps") {
                    val r = PhoneActions.run(ctx, "listen", org.json.JSONObject().put("on", true), apps)
                    if (r.ok) onClose() else Toast.makeText(ctx, r.note, Toast.LENGTH_LONG).show()
                }
                RoundIconButton(Icons.Filled.Settings, "Gemini settings") { showSettings = true }
                if (a.messages.isNotEmpty()) RoundIconButton(Icons.Filled.Delete, "Clear chat") { a.clear() }
                RoundIconButton(Icons.Filled.Close, "Close") { onClose() }
            }

            // ---- conversation
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (a.messages.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AssistantOrb(92.dp)
                        Spacer(Modifier.height(20.dp))
                        Text("How can I help?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Ask me anything, or tell me to open apps, call, message, set alarms and change phone settings.",
                            color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                        if (!a.configured) {
                            Spacer(Modifier.height(18.dp))
                            Box(
                                Modifier.glass(RoundedCornerShape(50), 0.26f).clickable { showSettings = true }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) { Text("Add your Gemini key", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(a.messages, key = { it.id }) { m -> Bubble(m) }
                        if (a.busy) item(key = "typing") { TypingDots() }
                    }
                }
            }

            if (listening && heard.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .glass(RoundedCornerShape(22.dp), 0.20f).padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(heard, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    alts.forEach { alt ->
                        Text(
                            alt, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp).clickable {
                                try { recognizer?.cancel() } catch (e: Exception) { }
                                finishWith(alt)
                            }
                        )
                    }
                }
            }

            TaskPanel(onStop = { TaskLog.stop() })
            EnableAssistantBanner()
            // ---- input
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f).glass(RoundedCornerShape(28.dp), 0.20f)) {
                if (pending.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pending.forEach { p ->
                            AttachmentCard(p.file?.name ?: p.name, loading = p.file == null && !p.failed, onRemove = { pending.remove(p) })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundIconButton(Icons.Filled.AttachFile, "Attach files") { filePicker.launch(arrayOf("*/*")) }
                    Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (input.isEmpty()) {
                            Text(if (listening) "Listening…" else if (pending.isNotEmpty()) "Say what to do with the file" else "Ask Neo Plus Assistant", color = Color.White.copy(alpha = if (listening) 0.8f else 0.5f), fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = input, onValueChange = { input = it }, maxLines = 4,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (input.isNotBlank() || pending.isNotEmpty()) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(Brush.verticalGradient(listOf(UserBlueTop, UserBlueBottom)))
                                .clickable { submit() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    } else {
                        RoundIconButton(Icons.Filled.Mic, "Speak") { listen() }
                    }
                }
              }
            }
        }

        if (taskOpen && !showSettings) TaskScreen()
        if (showSettings) SettingsSheet(a, apps) { showSettings = false }
    }
}
