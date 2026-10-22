package com.neo.plus.iosluancher

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

/** One line of the live task feed. */
class TaskLine(val id: Long, val time: String, val kind: Kind, val text: String) {
    enum class Kind { INFO, STEP, OK, WARN, ERROR, FIX }
}

/**
 * Everything the assistant does during a website / Excel task is written here and shown live in [TaskPanel],
 * so the user can watch each step, each row, each problem and each fix as it happens.
 */
object TaskLog {
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
    /** When on, the built-in browser is drawn on screen (top half) so the page can be watched. */
    var showBrowser by mutableStateOf(false)
    /** Which feature owns the feed right now ("money" for a transfer; empty for website / Excel tasks). */
    var owner = ""
    /** When on, the latest steps are drawn over whatever app is on screen (used while the network's USSD pop-ups are open). */
    var overlay by mutableStateOf(false)
    /** What the Stop button does for the running task; empty = stop the website task. */
    var onStop: (() -> Unit)? = null

    fun stop() { val f = onStop; if (f != null) f() else WebTask.cancel() }

    /** Ends the feed, but only when [who] still owns it (a transfer never touches a website task's feed). */
    fun endOwned(who: String, summary: String, ok: Boolean) {
        if (owner != who) return
        if (active) finish(summary, ok)
        overlay = false; onStop = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { StepOverlay.stop() }
    }

    private var nextId = 0L
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized fun start(name: String, totalItems: Int = 0) {
        lines.clear(); owner = ""; title = name; active = true; waiting = false; fullView = true; done = 0; failed = 0; total = totalItems; status = "Starting"; dismissed = false
        add(TaskLine.Kind.INFO, name)
    }

    @Synchronized fun add(kind: TaskLine.Kind, text: String) {
        lines.add(TaskLine(nextId++, fmt.format(java.util.Date()), kind, text.take(400)))
        while (lines.size > 400) lines.removeAt(0)
    }

    fun setState(s: String) { status = s }

    fun finish(summary: String, ok: Boolean) {
        val wasActive = active
        add(if (ok) TaskLine.Kind.OK else TaskLine.Kind.ERROR, summary)
        status = if (ok) "Finished" else "Stopped"
        active = false; waiting = false
        // Report it: a text for one task, a call once five have finished. Money transfers have their own screens.
        if (wasActive && owner != "money") TaskReports.record(null, title, summary, ok)
    }

    /** Plain-text copy of the whole feed (used for the final answer when something went wrong). */
    fun transcript(max: Int = 60): String = lines.takeLast(max).joinToString("\n") { it.time + " " + it.text }
}

@Composable
fun TaskPanel(onStop: () -> Unit) {
    if (TaskLog.lines.isEmpty() || TaskLog.dismissed) return
    val state = rememberLazyListState()
    var expanded by androidx.compose.runtime.remember { mutableStateOf(true) }
    LaunchedEffect(TaskLog.lines.size) { if (TaskLog.lines.isNotEmpty()) state.scrollToItem(TaskLog.lines.size - 1) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(TaskLog.title.ifBlank { "Task" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val progress = if (TaskLog.total > 0) "  ·  ${TaskLog.done + TaskLog.failed}/${TaskLog.total}" + (if (TaskLog.failed > 0) "  ·  ${TaskLog.failed} problem(s)" else "") else ""
                Text((if (TaskLog.waiting) "Waiting for you: do the CAPTCHA on the page, then Continue" else TaskLog.status) + progress, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
            }
            if (TaskLog.waiting) { PanelButton("Continue", Color(0xFF30D158)) { WebTask.resume() }; Spacer(Modifier.padding(horizontal = 3.dp)) }
            PanelButton("Full view") { TaskLog.fullView = true }
            Spacer(Modifier.padding(horizontal = 3.dp))
            PanelButton(if (TaskLog.showBrowser) "Hide page" else "Watch page") { TaskLog.showBrowser = !TaskLog.showBrowser }
            Spacer(Modifier.padding(horizontal = 3.dp))
            PanelButton(if (expanded) "Collapse" else "Expand") { expanded = !expanded }
            Spacer(Modifier.padding(horizontal = 3.dp))
            if (TaskLog.active) PanelButton("Stop", Color(0xFFFF453A)) { onStop() }
            else PanelButton("Close") { TaskLog.dismissed = true }
        }
        if (TaskLog.total > 0) {
            Spacer(Modifier.height(8.dp))
            val frac = ((TaskLog.done + TaskLog.failed).toFloat() / TaskLog.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF30D158)))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(TaskLog.lines.toList(), key = { it.id }) { l ->
                    val c = when (l.kind) {
                        TaskLine.Kind.OK -> Color(0xFF30D158)
                        TaskLine.Kind.WARN -> Color(0xFFFFD60A)
                        TaskLine.Kind.ERROR -> Color(0xFFFF453A)
                        TaskLine.Kind.FIX -> Color(0xFF64D2FF)
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
