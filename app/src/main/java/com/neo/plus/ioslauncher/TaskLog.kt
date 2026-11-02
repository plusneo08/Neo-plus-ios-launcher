package com.neo.plus.ioslauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow

/** One line of the live task feed. */
class TaskLine(val id: Long, val time: String, val kind: Kind, val text: String) {
    enum class Kind { INFO, STEP, OK, WARN, ERROR, FIX }
}

/**
 * The live feed of ONE task (its steps, progress, page picture and buttons). Up to [TaskLog.MAX] feeds exist at once and each
 * one is drawn as its own tile of the split screen. Everything here belongs to this task only.
 */
class TaskFeed(val id: Int) {
    val lines = mutableStateListOf<TaskLine>()
    var title by mutableStateOf("")
    var active by mutableStateOf(false)
    var done by mutableStateOf(0)
    var failed by mutableStateOf(0)
    var total by mutableStateOf(0)
    var status by mutableStateOf("")
    var dismissed by mutableStateOf(false)
    /** True while the task is paused for a CAPTCHA / code step that only the user can do. */
    var waiting by mutableStateOf(false)
    /** The full-screen task view (everything the task does, as a chat) is open; Minimise switches to the small panel. */
    var fullView by mutableStateOf(true)
    /** When on, this task's page is drawn full screen so it can be watched. */
    var showBrowser by mutableStateOf(false)
    /** When on, the latest steps are drawn over whatever app is on screen (used while the network's USSD pop-ups are open). */
    var overlay by mutableStateOf(false)
    /** What the task is asking the user for right now (empty = nothing). */
    var question by mutableStateOf("")
    /** The latest picture of THIS task's own page, shown in its tile. */
    var thumb by mutableStateOf<ImageBitmap?>(null)
    /** Which feature owns the feed ("money" for a transfer; empty for website / Excel tasks). */
    @Volatile var owner = ""
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onResume: (() -> Unit)? = null

    private var nextId = 0L
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized fun add(kind: TaskLine.Kind, text: String) {
        lines.add(TaskLine(nextId++, fmt.format(java.util.Date()), kind, text.take(400)))
        while (lines.size > 400) lines.removeAt(0)
    }

    fun setState(s: String) { status = s }

    fun stop() { val f = onStop; if (f != null) f() else if (active) { active = false } }
    fun resume() { onResume?.invoke() }

    fun finish(summary: String, ok: Boolean) {
        val wasActive = active
        add(if (ok) TaskLine.Kind.OK else TaskLine.Kind.ERROR, summary)
        status = if (ok) "Finished" else "Stopped"
        active = false; waiting = false; question = ""
        // Report it: a text for one task, a call once five have finished. Money transfers have their own screens.
        if (wasActive && owner != "money") TaskReports.record(null, title, summary, ok)
    }

    /** Plain-text copy of the whole feed (used for the final answer when something went wrong). */
    fun transcript(max: Int = 60): String = lines.takeLast(max).joinToString("\n") { it.time + " " + it.text }
}

/**
 * All task feeds. Up to 9 tasks run at once; each has its own [TaskFeed]. The old single-feed calls (TaskLog.add, TaskLog.title ...)
 * still work: they act on the feed of the calling thread (the task that thread started), or else on the focused feed.
 */
object TaskLog {
    const val MAX = 9
    val feeds = mutableStateListOf<TaskFeed>()
    var focusId by mutableStateOf(-1)

    private val bound = ThreadLocal<TaskFeed?>()
    private val idle = TaskFeed(0)
    private var seq = 0

    val focus: TaskFeed? get() = feeds.firstOrNull { it.id == focusId && !it.dismissed } ?: feeds.lastOrNull { !it.dismissed }
    private fun cur(): TaskFeed = bound.get() ?: focus ?: idle

    fun visible(): List<TaskFeed> = feeds.filter { !it.dismissed }
    fun activeCount(): Int = feeds.count { it.active }
    fun byId(id: Int): TaskFeed? = feeds.firstOrNull { it.id == id }
    fun owned(who: String): TaskFeed? = feeds.lastOrNull { it.owner == who && it.active }

    /** Opens a new feed (a new tile). Returns null when 9 tasks are already on screen and none of them is finished. */
    @Synchronized fun begin(name: String, totalItems: Int = 0, owner: String = ""): TaskFeed? {
        feeds.removeAll { !it.active && it.dismissed }
        while (feeds.size >= MAX) {
            val old = feeds.firstOrNull { !it.active } ?: return null
            feeds.remove(old)
        }
        val f = TaskFeed(++seq)
        f.title = name; f.active = true; f.total = totalItems; f.status = "Starting"; f.owner = owner
        f.add(TaskLine.Kind.INFO, name)
        feeds.add(f)
        focusId = f.id
        return f
    }

    // ---- compatibility: the single-feed API used by the chat panel, the money flow and the overlay ----
    val lines get() = cur().lines
    var title: String get() = cur().title; set(v) { cur().title = v }
    var active: Boolean get() = cur().active; set(v) { cur().active = v }
    var done: Int get() = cur().done; set(v) { cur().done = v }
    var failed: Int get() = cur().failed; set(v) { cur().failed = v }
    var total: Int get() = cur().total; set(v) { cur().total = v }
    var status: String get() = cur().status; set(v) { cur().status = v }
    var dismissed: Boolean get() = cur().dismissed; set(v) { cur().dismissed = v }
    var waiting: Boolean get() = cur().waiting; set(v) { cur().waiting = v }
    var fullView: Boolean get() = cur().fullView; set(v) { cur().fullView = v }
    var showBrowser: Boolean get() = cur().showBrowser; set(v) { cur().showBrowser = v }
    var overlay: Boolean get() = cur().overlay; set(v) { cur().overlay = v }
    var owner: String get() = cur().owner; set(v) { cur().owner = v }
    var onStop: (() -> Unit)? get() = cur().onStop; set(v) { cur().onStop = v }

    fun stop() { cur().stop() }
    fun add(kind: TaskLine.Kind, text: String) = cur().add(kind, text)
    fun setState(s: String) = cur().setState(s)
    fun finish(summary: String, ok: Boolean) = cur().finish(summary, ok)
    fun transcript(max: Int = 60): String = cur().transcript(max)

    /** Starts a feed and ties it to the calling thread (used by the money flow). */
    fun start(name: String, totalItems: Int = 0) { val f = begin(name, totalItems); if (f != null) bound.set(f) }

    /** Ends the feed, but only when [who] still owns it (a transfer never touches a website task's feed). */
    fun endOwned(who: String, summary: String, ok: Boolean) {
        val f = owned(who) ?: return
        if (f.active) f.finish(summary, ok)
        f.overlay = false; f.onStop = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { StepOverlay.stop() }
    }

    /** One spoken sentence per task: what each one is doing or asking for right now. */
    fun summary(): String {
        val v = visible()
        if (v.isEmpty()) return "No tasks are running."
        return v.joinToString(" ") { f ->
            "Task ${f.id}: " + when {
                f.waiting -> "needs you, " + f.question.ifBlank { "tap continue" } + "."
                f.active -> f.status.ifBlank { "working" } + "."
                else -> f.status.ifBlank { "finished" } + "."
            }
        }
    }

    /** Adds a line to the feed [who] owns, from any thread. */
    fun addOwned(who: String, kind: TaskLine.Kind, text: String) { owned(who)?.add(kind, text) }
}

@Composable
fun TaskPanel(onStop: () -> Unit, modifier: Modifier = Modifier, maxListHeight: androidx.compose.ui.unit.Dp = 190.dp) {
    if (TaskLog.lines.isEmpty() || TaskLog.dismissed) return
    val state = rememberLazyListState()
    var expanded by androidx.compose.runtime.remember { mutableStateOf(true) }
    LaunchedEffect(TaskLog.lines.size) { if (TaskLog.lines.isNotEmpty()) state.scrollToItem(TaskLog.lines.size - 1) }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(TaskLog.title.ifBlank { "Task" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val progress = if (TaskLog.total > 0) "  ·  ${TaskLog.done + TaskLog.failed}/${TaskLog.total}" + (if (TaskLog.failed > 0) "  ·  ${TaskLog.failed} problem(s)" else "") else ""
                Text((if (TaskLog.waiting) "Waiting for you: do the CAPTCHA on the page, then Continue" else TaskLog.status) + progress, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
            }
            if (TaskLog.waiting) { PanelButton("Continue", Color(0xFF2BFF88)) { WebTask.resume() }; Spacer(Modifier.padding(horizontal = 3.dp)) }
            PanelButton("Full view") { TaskLog.fullView = true }
            Spacer(Modifier.padding(horizontal = 3.dp))
            PanelButton(if (TaskLog.showBrowser) "Hide page" else "Watch page") { TaskLog.showBrowser = !TaskLog.showBrowser }
            Spacer(Modifier.padding(horizontal = 3.dp))
            PanelButton(if (expanded) "Collapse" else "Expand") { expanded = !expanded }
            Spacer(Modifier.padding(horizontal = 3.dp))
            if (TaskLog.active) PanelButton("Stop", Color(0xFFFF3D6E)) { onStop() }
            else PanelButton("Close") { TaskLog.dismissed = true }
        }
        if (TaskLog.total > 0) {
            Spacer(Modifier.height(8.dp))
            val frac = ((TaskLog.done + TaskLog.failed).toFloat() / TaskLog.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2BFF88)))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(TaskLog.lines.toList(), key = { it.id }) { l ->
                    val c = when (l.kind) {
                        TaskLine.Kind.OK -> Color(0xFF2BFF88)
                        TaskLine.Kind.WARN -> Color(0xFFFFE600)
                        TaskLine.Kind.ERROR -> Color(0xFFFF3D6E)
                        TaskLine.Kind.FIX -> Color(0xFF4FE0FF)
                        TaskLine.Kind.STEP -> Color.White.copy(alpha = 0.85f)
                        TaskLine.Kind.INFO -> Color.White.copy(alpha = 0.6f)
                    }
                    Text(l.time + "  " + l.text, color = c, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.18f)).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}
