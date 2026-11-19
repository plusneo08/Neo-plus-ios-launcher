package com.neo.plus.ioslauncher

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The Skills screen (like ChatGPT's Skills page): Installed skills (on/off, update, remove) and Waiting skills that were
 * downloaded and checked, each with its scan status (Ready / Needs review / Blocked) and an Install button.
 * Tapping Install here is the user's OK. The AI model cannot reach this screen.
 */
class SkillsActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()

    /** Bumped in onResume so the lists reload when the user comes back (for example from the notification). */
    var resumed by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkillsScreen(this) }
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    /** Same as [bg] for any result type. */
    fun <T> bgOf(work: () -> T, fallback: T, done: (T) -> Unit) {
        try {
            io.execute {
                val r = try { work() } catch (e: Exception) { fallback }
                runOnUiThread { done(r) }
            }
        } catch (e: Exception) { done(fallback) }
    }

    /** Runs [work] off the main thread (downloads), then [done] on the main thread. */
    fun bg(work: () -> String, done: (String) -> Unit) {
        try {
            io.execute {
                val r = try { work() } catch (e: Exception) { "Something went wrong (" + e.javaClass.simpleName + ")" }
                runOnUiThread { done(r) }
            }
        } catch (e: Exception) { done("Couldn’t start that.") }
    }

    private val draftSystem =
        "You help the owner of this phone write a SKILL: a reusable set of written instructions for the phone assistant " +
        "(for example how to answer messages, how to write a daily report, how to do a routine). " +
        "If you really need more detail, ask ONE short question at a time (at most two in total). " +
        "When you have enough, reply with one short sentence and then the skill between <skill> and </skill>. Inside the tags use exactly this format:\n" +
        "---\nname: short-lowercase-name\ndescription: one line saying what it does and when to use it\n---\n" +
        "Then the instructions as plain numbered steps, under 300 words.\n" +
        "Rules: plain text only, no scripts or code to run. Never tell the assistant to pay or send money, use or reveal PINs, OTPs or passwords, " +
        "share personal details, hide things from the owner, or ignore its safety rules. Write in the language the owner uses."

    /** "Create with chat": the model asks a question or writes the SKILL.md draft. */
    fun draftReply(history: List<Pair<Boolean, String>>): String {
        val key = getSharedPreferences("neo_assistant", MODE_PRIVATE).getString("key", "").orEmpty()
        if (key.isBlank()) return "I need the AI key from the assistant settings first."
        return try {
            Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, draftSystem, history, null, search = false).trim()
        } catch (e: Exception) { "Couldn’t reach the AI model. Check the connection and try again." }
    }

    /** Upload from phone: copies the picked file into the cache, then installs it the same way as a link or a path. */
    fun installFromUri(uri: Uri): String {
        var name = "skill"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (e: Exception) { }
        name = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
        val lower = name.lowercase()
        if (!lower.endsWith(".zip") && !lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".txt")) {
            return "Pick a skill folder as a .zip, or a SKILL.md file."
        }
        val f = File(cacheDir, "skill_upload_" + System.currentTimeMillis() + "_" + name)
        try {
            val input = contentResolver.openInputStream(uri) ?: return "Couldn’t read that file."
            var total = 0L
            input.use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 8_000_000L) return "That file is too big (8 MB at most)."
                        out.write(buf, 0, n)
                    }
                }
            }
            return SkillStore.install(this, JSONObject().put("source", f.absolutePath))
        } finally {
            try { f.delete() } catch (e: Exception) { }
        }
    }
}

private val AMBER = Color(0xFFB26A00)
private val GREEN = Color(0xFF2E7D32)

@Composable
private fun SkillsScreen(act: SkillsActivity) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            var tab by remember { mutableStateOf(if (SkillStore.pendingList(act).isNotEmpty()) 1 else 0) }
            var tick by remember { mutableStateOf(0) }
            var link by remember { mutableStateOf("") }
            var msg by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var exploreSrc by remember { mutableStateOf("anthropics/skills") }
            var browsed by remember { mutableStateOf("") }
            val found = remember { mutableStateListOf<SkillStore.Found>() }
            val chat = remember { mutableStateListOf<Pair<Boolean, String>>() }
            var chatIn by remember { mutableStateOf("") }
            var draft by remember { mutableStateOf("") }
            val installed = remember(tick, act.resumed) { SkillStore.installedList(act) }
            val waiting = remember(tick, act.resumed) { SkillStore.pendingList(act) }

            fun finish(r: String, goTo: Int?) {
                msg = r
                busy = false
                tick++
                if (goTo != null) tab = goTo
            }

            fun afterGet(r: String) {
                msg = r
                busy = false
                tick++
                tab = if (SkillStore.pendingList(act).isNotEmpty()) 1 else tab
            }

            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    busy = true
                    msg = "Reading the file…"
                    act.bg({ act.installFromUri(uri) }) { r -> afterGet(r) }
                }
            }

            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Text("Skills", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Reusable instructions the assistant can use. A new skill is checked first and installs only when you tap Install.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabButton("Installed (${installed.size})", tab == 0) { tab = 0 }
                    TabButton("Waiting (${waiting.size})", tab == 1) { tab = 1 }
                    TabButton("Explore", tab == 3) { tab = 3 }
                    TabButton("Create", tab == 2) { tab = 2 }
                }
                Spacer(Modifier.height(8.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (tab == 0 || tab == 1) Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Add a skill", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = link, onValueChange = { link = it }, singleLine = true,
                                label = { Text("GitHub address or link to SKILL.md / .zip") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy && link.isNotBlank(),
                                    onClick = {
                                        busy = true
                                        msg = "Downloading and checking…"
                                        val src = link.trim()
                                        act.bg({ SkillStore.install(act, JSONObject().put("source", src)) }) { r ->
                                            if (!r.startsWith("Couldn")) link = ""
                                            afterGet(r)
                                        }
                                    }
                                ) { Text("Get skill") }
                                OutlinedButton(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }) { Text("Upload from phone") }
                            }
                        }
                    }
                    if (msg.isNotBlank()) {
                        Text(msg, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }

                    if (tab == 0) {
                        if (installed.isEmpty()) {
                            Text("No skills installed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        }
                        for (s in installed) {
                            InstalledCard(
                                s,
                                onToggle = { on -> SkillStore.setOn(act, s.slug, on); tick++ },
                                onUpdate = {
                                    busy = true
                                    msg = "Downloading the newest version…"
                                    act.bg({ SkillStore.update(act, s.slug) }) { r -> afterGet(r) }
                                },
                                onRemove = { SkillStore.remove(act, s.slug); tick++ },
                                busy = busy
                            )
                        }
                    } else if (tab == 3) {
                        ExplorePanel(
                            exploreSrc, { exploreSrc = it }, found, browsed, installed.map { it.slug }, busy,
                            onBrowse = { src ->
                                busy = true
                                msg = "Looking for skills…"
                                act.bgOf({ SkillStore.browse(act, src) }, emptyList<SkillStore.Found>() to "Couldn’t open that.") { res ->
                                    found.clear()
                                    found.addAll(res.first)
                                    browsed = if (res.first.isEmpty()) "" else src.trim()
                                    msg = if (res.second.isNotEmpty()) res.second else res.first.size.toString() + " skill(s) found. Tap Get to download and check one."
                                    busy = false
                                }
                            },
                            onGet = { f ->
                                busy = true
                                msg = "Downloading and checking…"
                                val src = browsed
                                act.bg({ SkillStore.install(act, JSONObject().put("source", src).put("name", f.dirName)) }) { r -> afterGet(r) }
                            }
                        )
                    } else if (tab == 2) {
                        CreatePanel(
                            chat, chatIn, { chatIn = it }, draft, { draft = it }, busy,
                            onSend = {
                                val t = chatIn.trim()
                                if (t.isNotEmpty() && !busy) {
                                    chat.add(true to t)
                                    chatIn = ""
                                    busy = true
                                    msg = "Thinking…"
                                    val snapshot = chat.toList()
                                    act.bg({ act.draftReply(snapshot) }) { r ->
                                        val m = Regex("(?s)<skill>(.*?)</skill>").find(r)
                                        if (m != null) {
                                            draft = m.groupValues[1].trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
                                        }
                                        val shown = r.replace(Regex("(?s)<skill>.*?</skill>"), "").trim()
                                            .ifBlank { "Here is the skill. Check it, change it if you like, then add it." }
                                        chat.add(false to shown)
                                        busy = false
                                        msg = ""
                                    }
                                }
                            },
                            onAdd = {
                                busy = true
                                msg = "Checking the skill…"
                                val d = draft
                                act.bg({ SkillStore.install(act, JSONObject().put("text", d)) }) { r ->
                                    if (!r.startsWith("Couldn")) { draft = ""; chat.clear() }
                                    afterGet(r)
                                }
                            }
                        )
                    } else {
                        if (waiting.isEmpty()) {
                            Text("Nothing is waiting. Add a skill above, or ask the assistant to install one.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        }
                        for (p in waiting) {
                            WaitingCard(
                                p, busy,
                                onInstall = { id ->
                                    busy = true
                                    msg = "Installing…"
                                    act.bg({ SkillStore.approvePending(act, id) }) { r -> finish(r, 0) }
                                },
                                onSkip = { id -> SkillStore.skipPending(act, id); msg = "Skipped."; tick++ }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun InstalledCard(s: SkillStore.SkillInfo, onToggle: (Boolean) -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit, busy: Boolean) {
    var sure by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(s.description.take(160), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.on, onCheckedChange = onToggle)
            }
            Row {
                if (s.source.isNotBlank()) TextButton(enabled = !busy, onClick = onUpdate) { Text("Update") }
                TextButton(onClick = { if (sure) onRemove() else sure = true }) { Text(if (sure) "Tap again to remove" else "Remove") }
            }
        }
    }
}

@Composable
private fun WaitingCard(p: SkillStore.PendingInfo, busy: Boolean, onInstall: (String) -> Unit, onSkip: (String) -> Unit) {
    val canInstall = p.skills.any { it.status != "blocked" }
    val needsReview = p.skills.any { it.status == "review" }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("From " + p.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (s in p.skills) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name + (if (s.update) " (update)" else ""), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val (label, color) = when (s.status) {
                        "blocked" -> "Blocked" to MaterialTheme.colorScheme.error
                        "review" -> "Needs review" to AMBER
                        else -> "Ready" to GREEN
                    }
                    Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text(s.description.take(200), fontSize = 13.sp)
                Text(s.files.toString() + " file(s), " + s.kb + " KB. Scripts are never installed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (w in s.warnings) {
                    Text("⚠ it " + w, fontSize = 12.sp, color = if (s.status == "blocked") MaterialTheme.colorScheme.error else AMBER)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInstall) Button(enabled = !busy, onClick = { onInstall(p.id) }) { Text(if (needsReview) "Install anyway" else "Install") }
                OutlinedButton(onClick = { onSkip(p.id) }) { Text(if (canInstall) "Skip" else "Dismiss") }
            }
        }
    }
}

@Composable
private fun CreatePanel(
    chat: List<Pair<Boolean, String>>, chatIn: String, onChatIn: (String) -> Unit,
    draft: String, onDraft: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onAdd: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Create a skill with chat", fontWeight = FontWeight.SemiBold)
            Text(
                "Say what the skill should do. I may ask a question, then I write it. It is checked and waits for your OK on the Waiting tab.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (m in chat) {
                Text((if (m.first) "You: " else "Assistant: ") + m.second, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = chatIn, onValueChange = onChatIn, maxLines = 4,
                label = { Text(if (chat.isEmpty()) "What should the skill do?" else "Your reply") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(enabled = !busy && chatIn.isNotBlank(), onClick = onSend) { Text("Send") }
        }
    }
    if (draft.isNotBlank()) {
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Draft skill (you can edit it)", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = draft, onValueChange = onDraft, maxLines = 14, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(enabled = !busy, onClick = onAdd) { Text("Add to Waiting") }
            }
        }
    }
}

@Composable
private fun ExplorePanel(
    src: String, onSrc: (String) -> Unit, found: List<SkillStore.Found>, browsed: String, have: List<String>, busy: Boolean,
    onBrowse: (String) -> Unit, onGet: (SkillStore.Found) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Explore skills", fontWeight = FontWeight.SemiBold)
            Text(
                "Look through a GitHub collection of skills. Nothing is installed until you tap Get, check the scan, and tap Install on the Waiting tab.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { onSrc("anthropics/skills"); onBrowse("anthropics/skills") }) { Text("Anthropic skills") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val h = "github.com/NousResearch/hermes-agent/tree/main/skills"
                        onSrc(h); onBrowse(h)
                    }
                ) { Text("Hermes skills") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = src, onValueChange = onSrc, singleLine = true,
                label = { Text("GitHub repo or folder (owner/repo)") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(enabled = !busy && src.isNotBlank(), onClick = { onBrowse(src) }) { Text("Browse") }
        }
    }
    for (f in found) {
        val isIn = f.slug in have
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(f.name, fontWeight = FontWeight.SemiBold)
                    Text(f.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isIn) Text("Installed", fontSize = 13.sp, color = GREEN)
                else Button(enabled = !busy && browsed.isNotBlank(), onClick = { onGet(f) }) { Text("Get") }
            }
        }
    }
}
