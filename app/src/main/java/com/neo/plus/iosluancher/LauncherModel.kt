package com.neo.plus.iosluancher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The default apps every home screen starts with. The user can remove any of them. */
enum class Role(val label: String) {
    PHONE("Phone"), MESSAGES("Messages"), SAFARI("Safari"), CAMERA("Camera")
}

data class AppEntry(
    val label: String,
    val pkg: String,
    val activity: String,
    val category: String,
    val installTime: Long,
    val icon: ImageBitmap,
    val system: Boolean = false,
    val role: Role? = null
) {
    /** Name shown under the icon: default apps use their iPhone names (Phone, Messages, Safari, Camera). */
    val shownLabel: String get() = role?.label ?: label

    fun matches(q: String): Boolean =
        label.contains(q, ignoreCase = true) || shownLabel.contains(q, ignoreCase = true)
}

data class AppGroup(val name: String, val apps: List<AppEntry>)

object Cat {
    const val RECENT = "Recently Added"
    const val SOCIAL = "Social"
    const val ENTERTAINMENT = "Entertainment"
    const val PRODUCTIVITY = "Productivity"
    const val GAMES = "Games"
    const val TRAVEL = "Travel & Maps"
    const val OTHER = "Utilities"
    val order = listOf(SOCIAL, ENTERTAINMENT, PRODUCTIVITY, GAMES, TRAVEL, OTHER)
}

fun buildGroups(apps: List<AppEntry>): List<AppGroup> {
    if (apps.isEmpty()) return emptyList()
    val result = mutableListOf<AppGroup>()
    result += AppGroup(Cat.RECENT, apps.sortedByDescending { it.installTime }.take(12))
    Cat.order.forEach { c ->
        val list = apps.filter { it.category == c }
        if (list.isNotEmpty()) result += AppGroup(c, list)
    }
    return result
}

private fun categoryOf(info: ApplicationInfo?): String = when (info?.category) {
    ApplicationInfo.CATEGORY_SOCIAL -> Cat.SOCIAL
    ApplicationInfo.CATEGORY_GAME -> Cat.GAMES
    ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> Cat.ENTERTAINMENT
    ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS -> Cat.PRODUCTIVITY
    ApplicationInfo.CATEGORY_MAPS -> Cat.TRAVEL
    else -> Cat.OTHER
}

/** Renders any app icon into an iOS-style full-bleed square (adaptive layers are scaled to fill). */
fun renderIcon(d: Drawable, px: Int): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    if (d is AdaptiveIconDrawable) {
        val off = (px * 0.25f).toInt()
        d.background?.let { it.setBounds(-off, -off, px + off, px + off); it.draw(c) }
        d.foreground?.let { it.setBounds(-off, -off, px + off, px + off); it.draw(c) }
    } else {
        c.drawColor(android.graphics.Color.WHITE)
        val pad = (px * 0.12f).toInt()
        d.setBounds(pad, pad, px - pad, px - pad)
        d.draw(c)
    }
    return bmp
}

/** Loads every launchable app. Icons are rendered in parallel on all cores, sized to the screen density. */
private suspend fun queryApps(ctx: Context): List<AppEntry> = coroutineScope {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val px = (ctx.resources.displayMetrics.density * 78f).toInt().coerceIn(180, 320)
    pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            async(Dispatchers.Default) {
                val ai = ri.activityInfo
                val label = ri.loadLabel(pm)?.toString() ?: ai.packageName
                val time = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: Exception) { 0L }
                val bmp = try {
                    renderIcon(ri.loadIcon(pm), px)
                } catch (e: Exception) {
                    renderIcon(pm.defaultActivityIcon, px)
                }
                val flags = ai.applicationInfo?.flags ?: 0
                val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppEntry(
                    label, ai.packageName, ai.name, categoryOf(ai.applicationInfo), time,
                    bmp.asImageBitmap(), system = isSystem
                )
            }
        }
        .awaitAll()
        .distinctBy { it.pkg + "/" + it.activity }
        .sortedBy { it.label.lowercase() }
}

/** Finds which installed app currently fills each default role (dialer, SMS app, browser, camera). */
private fun defaultRoles(ctx: Context, apps: List<AppEntry>): LinkedHashMap<String, Role> {
    val pm = ctx.packageManager
    val installed = apps.map { it.pkg }.toSet()
    fun pkgOf(vararg intents: Intent): String? {
        for (i in intents) {
            val pkg = try {
                pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) { null }
            if (pkg != null && pkg in installed) return pkg
        }
        return null
    }
    val result = LinkedHashMap<String, Role>()
    fun put(role: Role, pkg: String?) { if (pkg != null && pkg !in result) result[pkg] = role }
    put(Role.PHONE, pkgOf(Intent(Intent.ACTION_DIAL)))
    put(Role.MESSAGES, pkgOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)))
    put(
        Role.SAFARI,
        pkgOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        )
    )
    put(Role.CAMERA, pkgOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
    return result
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Context get() = getApplication<Application>()
    private val prefs = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var dock by mutableStateOf<List<String>>(emptyList())
        private set

    /** Apps the user removed from the Home Screen. They stay in the App Library and can be added back. */
    var hidden by mutableStateOf<Set<String>>(prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet())
        private set

    /** Apps shown on the home pages: everything that is not in the dock and was not removed. */
    val homeApps: List<AppEntry> get() = apps.filter { it.pkg !in hidden && it.pkg !in dock }

    init {
        reload()
    }

    private var reloadJob: Job? = null

    /** Debounced: a burst of package broadcasts only triggers one rescan. */
    fun reload(delayMs: Long = 0L) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val raw = withContext(Dispatchers.Default) { queryApps(ctx) }
            val roles = withContext(Dispatchers.Default) { defaultRoles(ctx, raw) }
            val list = raw.map { a -> roles[a.pkg]?.let { a.copy(role = it) } ?: a }
            apps = list
            val saved = prefs.getString("dock", null)
            dock = if (saved != null) saved.split(",").filter { it.isNotBlank() } else roles.keys.toList()
        }
    }

    private fun saveDock(list: List<String>) {
        dock = list
        prefs.edit().putString("dock", list.joinToString(",")).apply()
    }

    private fun saveHidden(set: Set<String>) {
        hidden = set
        prefs.edit().putStringSet("hidden", set).apply()
    }

    private class Snap(val dock: List<String>, val hidden: Set<String>)
    private val undoStack = ArrayList<Snap>()
    private val redoStack = ArrayList<Snap>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun syncUndo() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }

    /** Called before every change to the dock or the home screen, so Undo can bring it back (iOS 27 Undo / Redo). */
    private fun checkpoint() {
        undoStack.add(Snap(dock, hidden))
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
        syncUndo()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val s = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(Snap(dock, hidden))
        saveDock(s.dock); saveHidden(s.hidden)
        syncUndo()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(Snap(dock, hidden))
        saveDock(s.dock); saveHidden(s.hidden)
        syncUndo()
    }

    /** "Delete" for the home screen: the app leaves the dock and the home pages but stays installed. */
    fun removeFromHome(pkg: String) {
        checkpoint()
        if (pkg in dock) saveDock(dock - pkg)
        saveHidden(hidden + pkg)
    }

    fun addToHome(pkg: String) { checkpoint(); saveHidden(hidden - pkg) }

    fun pin(pkg: String) {
        if (pkg in dock) return
        checkpoint()
        if (pkg in hidden) saveHidden(hidden - pkg)
        saveDock(if (dock.size < 4) dock + pkg else dock.dropLast(1) + pkg)
    }

    fun unpin(pkg: String) { checkpoint(); saveDock(dock - pkg) }
}
