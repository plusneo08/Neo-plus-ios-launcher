package com.neo.plus.iosluancher

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ====================================================================================================================
 *  Chat notifications (WhatsApp, Messages, Telegram...): answer them
 *    1. right on a card that pops up over the screen when the message arrives (type the reply there), or
 *    2. in a full chat screen inside this app (the whole conversation, your replies and their state).
 *  Both send through the notification's own Reply action, so the answer lands in the same conversation.
 *
 *  "Typing": Android does not tell other apps when somebody is typing. This reads the typing / recording words that
 *  some apps put in their notification and shows them while they are there; apps that never write them in a
 *  notification (WhatsApp, for one) cannot be detected by any launcher.
 * ================================================================================================================== */

class ChatLine(val fromMe: Boolean, val text: String, val time: Long, val sender: String = "", val local: Boolean = false) {
    /** For your own replies: 0 = sending, 1 = sent, 2 = could not be sent. */
    @Volatile var state = 1
}

class ChatThread(val key: String, val pkg: String, val app: String, val title: String) {
    val lines = ArrayList<ChatLine>()
    @Volatile var reply: Notification.Action? = null
    @Volatile var open: PendingIntent? = null
    @Volatile var sbnKey: String = ""
    @Volatile var updated = 0L
}

object NotifChats {
    private val threads = LinkedHashMap<String, ChatThread>()
    val version = mutableIntStateOf(0)

    fun keyOf(pkg: String, title: String) = pkg + "|" + title.trim().lowercase(Locale.ROOT)

    @Synchronized fun get(key: String?): ChatThread? = if (key == null) null else threads[key]

    @Synchronized fun recent(): List<ChatThread> = threads.values.sortedByDescending { it.updated }

    /** Reads a chat notification into its thread. Returns the thread and whether something new came in. */
    @Synchronized
    fun record(ctx: Context, sbn: StatusBarNotification): Pair<ChatThread, Boolean>? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == ctx.packageName) return null
        val skip = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skip != 0) return null
        val ex = n.extras ?: return null
        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true && it.actionIntent != null }
        if (reply == null && n.category != Notification.CATEGORY_MESSAGE) return null
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
        if (title.isBlank()) return null
        val pm = ctx.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        val key = keyOf(sbn.packageName, title)
        val t = threads.getOrPut(key) { ChatThread(key, sbn.packageName, app, title) }
        if (reply != null) t.reply = reply
        t.open = n.contentIntent
        t.sbnKey = sbn.key

        var added = false
        fun addLine(fromMe: Boolean, text: String, time: Long, sender: String) {
            if (text.isBlank() || TypingWatch.isTypingText(text)) return
            if (t.lines.any { it.fromMe == fromMe && it.text == text && (it.time == time || kotlin.math.abs(it.time - time) < 2000) }) return
            if (fromMe) {
                // our own reply coming back in the notification: it confirms the line we added ourselves
                val mine = t.lines.lastOrNull { it.local && it.text == text && System.currentTimeMillis() - it.time < 5 * 60_000L }
                if (mine != null) { mine.state = 1; return }
            }
            t.lines.add(ChatLine(fromMe, text, time, sender)); added = !fromMe || added
        }
        val arr = try { ex.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (e: Exception) { null }
        if (arr != null && arr.isNotEmpty()) {
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString().orEmpty()
                val time = b.getLong("time", sbn.postTime)
                var who = b.getCharSequence("sender")?.toString().orEmpty()
                if (Build.VERSION.SDK_INT >= 28) {
                    try { (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString()?.let { if (it.isNotBlank()) who = it } } catch (e: Exception) { }
                }
                val me = who.isBlank() || who.equals("you", true) || who.equals("me", true)
                addLine(me, text, time, who)
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
            addLine(false, text, sbn.postTime, title)
        }
        while (t.lines.size > 60) t.lines.removeAt(0)
        t.updated = System.currentTimeMillis()
        while (threads.size > 60) threads.remove(threads.keys.first())
        version.intValue++
        return t to added
    }

    /** Sends [text] into the conversation through the notification's Reply action. Runs [done] with true when it was handed over. */
    fun send(ctx: Context, t: ChatThread, text: String, done: ((Boolean) -> Unit)? = null) {
        val line = ChatLine(true, text, System.currentTimeMillis(), "You", local = true).also { it.state = 0 }
        synchronized(this) { t.lines.add(line); t.updated = System.currentTimeMillis() }
        version.intValue++
        Thread {
            val ok = NotifStore.sendAction(ctx.applicationContext, t.reply, text)
            line.state = if (ok) 1 else 2
            Handler(Looper.getMainLooper()).post { version.intValue++; done?.invoke(ok) }
        }.start()
    }
}

object TypingWatch {
    private val TYPING = Regex("(?i)(is typing|are typing|typing\\.\\.\\.|typing…|^typing$|is recording|recording audio|is writing|anaandika|écrit|escribiendo|schreibt)")
    private class State(val until: Long, val text: String)
    private val states = HashMap<String, State>()

    fun isTypingText(s: String) = TYPING.containsMatchIn(s) && s.length < 60

    /** Call for every posted notification. */
    @Synchronized
    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val ex = sbn.notification?.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val hit = listOf(text, sub, title).firstOrNull { isTypingText(it) } ?: return
        // "Anna is typing…" in the title, or the sender's name is the title and the text says typing
        val who = if (isTypingText(title)) title.replace(TYPING, "").trim(' ', ':', '-', '…', '.') else title
        val label = if (hit.contains("record", true)) "recording audio…" else "typing…"
        states[NotifChats.keyOf(sbn.packageName, who)] = State(System.currentTimeMillis() + 8000L, label)
        NotifChats.version.intValue++
    }

    /** Typing states end by themselves a few seconds after the last notification that mentioned them. */
    fun onRemoved(@Suppress("UNUSED_PARAMETER") sbn: StatusBarNotification) { }

    /** "typing…" / "recording audio…" while it is on, else null. */
    @Synchronized
    fun statusFor(key: String): String? {
        val s = states[key] ?: return null
        if (System.currentTimeMillis() > s.until) { states.remove(key); return null }
        return s.text
    }
}

/* ------------------------------------------------------------------ the card that pops up over the screen */

object ReplyCard {
    private val main = Handler(Looper.getMainLooper())
    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var currentKey = ""
    private var typingView: TextView? = null
    private var interacting = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("neo_reply_card", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); if (!on) main.post { dismiss() } }

    /** Called for every posted notification. */
    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val app = ctx.applicationContext
        try { TypingWatch.onPosted(app, sbn) } catch (e: Exception) { }
        val got = try { NotifChats.record(app, sbn) } catch (e: Exception) { null } ?: return
        val (thread, isNew) = got
        if (!isNew || thread.reply == null) return
        if (!enabled(app) || !Settings.canDrawOverlays(app)) return
        val last = thread.lines.lastOrNull { !it.fromMe } ?: return
        main.post { show(app, thread, last.text) }
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(c: Context, label: String, color: Int, onTap: () -> Unit) = TextView(c).apply {
        text = label; setTextColor(AColor.WHITE); textSize = 14f; gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 20).toFloat() }
        setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9))
        setOnClickListener { onTap() }
    }

    private fun show(c: Context, t: ChatThread, text: String) {
        dismiss()
        try {
            val w = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(AColor.argb(240, 22, 28, 44)); cornerRadius = dp(c, 22).toFloat(); setStroke(dp(c, 1), AColor.argb(120, 56, 189, 248)) }
                setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
                elevation = dp(c, 10).toFloat()
            }
            val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(c).apply {
                this.text = "${t.title}  ·  ${t.app}"; setTextColor(AColor.WHITE); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val x = TextView(c).apply { this.text = "✕"; setTextColor(AColor.argb(190, 255, 255, 255)); textSize = 16f; setPadding(dp(c, 10), 0, 0, 0); setOnClickListener { dismiss() } }
            head.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); head.addView(x)
            val body = TextView(c).apply { this.text = text; setTextColor(AColor.argb(235, 255, 255, 255)); textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(c, 4), 0, 0) }
            val typing = TextView(c).apply { setTextColor(AColor.rgb(48, 209, 88)); textSize = 12f; visibility = View.GONE; setPadding(0, dp(c, 2), 0, 0) }
            val status = TextView(c).apply { setTextColor(AColor.argb(190, 255, 255, 255)); textSize = 12f; visibility = View.GONE; setPadding(0, dp(c, 4), 0, 0) }
            val input = EditText(c).apply {
                hint = "Reply to ${t.title}"; setHintTextColor(AColor.argb(120, 255, 255, 255)); setTextColor(AColor.WHITE); textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 3; visibility = View.GONE
                background = GradientDrawable().apply { setColor(AColor.argb(60, 255, 255, 255)); cornerRadius = dp(c, 16).toFloat() }
                setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
            }
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 10), 0, 0) }
            lateinit var replyBtn: TextView
            lateinit var sendBtn: TextView
            replyBtn = pill(c, "Reply", AColor.rgb(10, 132, 255)) {
                interacting = true
                input.visibility = View.VISIBLE; sendBtn.visibility = View.VISIBLE; replyBtn.visibility = View.GONE
                // the card takes the keyboard only now, so it never steals focus from the app underneath before that
                lp?.let { p -> p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(); p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN; try { w.updateViewLayout(box, p) } catch (e: Exception) { } }
                input.requestFocus()
                input.postDelayed({ (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 150)
            }
            sendBtn = pill(c, "Send", AColor.rgb(48, 209, 88)) {
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    status.visibility = View.VISIBLE; status.text = "Sending…"
                    NotifChats.send(c, t, msg) { ok ->
                        status.text = if (ok) "Sent ✓" else "Couldn’t send. Open the chat."
                        if (ok) { input.setText(""); main.postDelayed({ dismiss() }, 900) }
                    }
                }
            }.apply { visibility = View.GONE }
            val chat = pill(c, "Open chat", AColor.argb(110, 255, 255, 255)) {
                c.startActivity(Intent(c, NotifChatActivity::class.java).putExtra("thread", t.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                dismiss()
            }
            row.addView(replyBtn); row.addView(sendBtn)
            row.addView(View(c), LinearLayout.LayoutParams(dp(c, 8), 1))
            row.addView(chat)
            box.addView(head); box.addView(body); box.addView(typing); box.addView(input); box.addView(status); box.addView(row)

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP; x = 0; y = dp(c, 40) }
            // small side margins
            val outer = LinearLayout(c).apply { setPadding(dp(c, 10), 0, dp(c, 10), 0); addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            w.addView(outer, p)
            wm = w; lp = p; card = outer; typingView = typing; currentKey = t.key; interacting = false
            // keep the typing line fresh, and close the card after a while if nobody touched it
            val started = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (card !== outer) return
                    val s = TypingWatch.statusFor(t.key)
                    typing.visibility = if (s != null) View.VISIBLE else View.GONE
                    if (s != null) typing.text = "${t.title} is $s"
                    if (!interacting && System.currentTimeMillis() - started > 14_000L) { dismiss(); return }
                    main.postDelayed(this, 500)
                }
            }
            main.post(tick)
        } catch (e: Exception) { card = null }
    }

    fun dismiss() {
        val c = card ?: return
        try {
            val imm = c.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(c.windowToken, 0)
        } catch (e: Exception) { }
        try { wm?.removeView(c) } catch (e: Exception) { }
        card = null; typingView = null; interacting = false
    }
}

/* ------------------------------------------------------------------ the full chat screen */

class NotifChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val key = intent?.getStringExtra("thread")
        setContent { NotifChatScreen(key) { finish() } }
    }
}

@Composable
fun NotifChatScreen(key: String?, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v = NotifChats.version.intValue
    val t = remember(key, v) { NotifChats.get(key) }
    var draft by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()
    LaunchedEffect(key) { while (true) { typing = key?.let { TypingWatch.statusFor(it) }; kotlinx.coroutines.delay(500) } }
    val lines = t?.lines?.toList() ?: emptyList()
    LaunchedEffect(lines.size, typing) { val n = lines.size + (if (typing != null) 1 else 0); if (n > 0) state.animateScrollToItem(n - 1) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF040A16), Color(0xFF061830), Color(0xFF020712))))
            .windowInsetsPadding(safeInsets()).imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color(0xFF38BDF8), fontSize = 32.sp, modifier = Modifier.clickable { onClose() }.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.title ?: "Chat", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val sub = typing?.let { "$it" } ?: t?.app.orEmpty()
                Text(sub, color = if (typing != null) Color(0xFF30D158) else Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
            }
            Text("Open app", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).clickable {
                    try { t?.open?.send() } catch (e: Exception) { ctx.packageManager.getLaunchIntentForPackage(t?.pkg ?: "")?.let { ctx.safeStart(it) } }
                }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        if (t == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This conversation is no longer available.", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }
        } else {
            LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lines.size) { i ->
                    val m = lines[i]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp))
                                .background(if (m.fromMe) Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0xFF1D4ED8))) else Brush.linearGradient(listOf(Color(0xFF14233D), Color(0xFF14233D))))
                                .padding(horizontal = 13.dp, vertical = 8.dp)
                        ) {
                            Text(m.text, color = Color.White, fontSize = 15.sp)
                            Text(
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time)) + if (m.fromMe) when (m.state) { 0 -> "  · Sending…"; 2 -> "  · Not sent"; else -> "  · Sent ✓" } else "",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp
                            )
                        }
                    }
                }
                if (typing != null) item {
                    Text("${t.title} is $typing", color = Color(0xFF30D158), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                }
            }
            if (t.reply == null) Text("This app has no quick reply right now. Tap Open app to answer there.", color = Color(0xFFFFD60A), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (draft.isEmpty()) Text("Reply to ${t.title}", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp, maxLines = 1)
                    BasicTextField(draft, { draft = it }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                Text("Send", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF0A84FF)).clickable {
                        val msg = draft.trim()
                        if (msg.isNotEmpty()) { NotifChats.send(ctx, t, msg); draft = "" }
                    }.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}
