package com.neo.plus.ioslauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

/**
 * SPLIT SCREEN: when the assistant has to use a second app or open a website while another app is already on the screen, the new
 * app opens BESIDE the one you are in (top / bottom on a phone held upright) instead of replacing it.
 *
 *   - a task that touches two apps ("open Notes, then WhatsApp ...") : the first app opens full screen, the second one opens beside it
 *   - you are inside an app and say "open YouTube" / "open example.com" : it opens beside the app you are in
 *   - from the Neo Plus home screen nothing is split (there is no app to sit beside)
 *   - "open Chrome and Notes side by side" / "split screen Chrome and Notes" / "open Gmail in split screen" always split
 *   - "split screen off" stops the automatic splitting, "split screen on" brings it back, "exit split screen" closes the split now
 *
 * How it works: Android lets an accessibility service press the system's own "split screen" action
 * (GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN), which moves the app you are in to one half. The next app is then started with
 * FLAG_ACTIVITY_LAUNCH_ADJACENT, which puts it in the other half. Nothing is drawn by Neo Plus itself and no new permission is needed
 * (it uses the "NEO PLUS iOS Controls" accessibility service that is already switched on).
 *
 * If the phone refuses (Android older than 7, an app that cannot be resized, a maker's launcher that blocks the action) the app simply
 * opens full screen like before, and the reply says so. It never leaves a task undone because splitting failed.
 */
@SuppressLint("NewApi")   // every Android 7+ call sits behind `supported`
object SplitScreen {
    private const val PREF = "neo_split"

    /** Actions that start another app or page, so they are looked at for splitting. */
    private val launching = setOf("open_app", "open_url", "navigate", "connect")

    private val armed = ThreadLocal<Boolean>()      // this thread is inside one of the actions above
    private val usedWith = ThreadLocal<String>()    // name of the app the new one was put beside
    private val refused = ThreadLocal<Boolean>()    // the phone would not split

    @Volatile private var ownActive = false          // we started a split and have not seen it end (backup when windows cannot be read)
    @Volatile private var ownAt = 0L

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", true)
    private fun setEnabled(c: Context, on: Boolean) { sp(c).edit().putBoolean("on", on).apply() }

    private val supported get() = Build.VERSION.SDK_INT >= 24

    /** Called once when the accessibility service connects: lets it list on-screen windows, which is how a split is recognised. */
    fun prepare(svc: AccessibilityService) {
        try {
            val i = svc.serviceInfo ?: return
            i.flags = i.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            svc.serviceInfo = i
        } catch (e: Throwable) { }
    }

    /* ------------------------------------------------------------------ wrapping an action */

    /**
     * Runs [block] (the real "open app / open address" work) with splitting switched on for the launches it makes.
     * "split" in the action's JSON: true = always split this one, "base" / false = never (used for the first app of a pair).
     */
    fun around(ctx: Context, name: String, a: JSONObject, block: () -> String): String {
        if (!supported || name !in launching) return block()
        // blocking waits are needed while the split opens, so never on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val mode = a.opt("split")?.toString()?.lowercase().orEmpty()
        val force = mode == "true" || mode == "side"
        val wanted = when {
            mode == "base" || mode == "false" -> false
            force -> true
            else -> enabled(ctx)
        }
        if (!wanted) return block()
        armed.set(true); usedWith.remove(); refused.remove()
        try {
            val r = block()
            val with = usedWith.get()
            return when {
                with != null -> "$r (beside $with)"
                refused.get() == true -> "$r (this phone would not split the screen, so it opened full screen)"
                else -> r
            }
        } finally {
            armed.remove(); usedWith.remove(); refused.remove()
        }
    }

    /**
     * Called by every launch helper just before it starts an app or page. When splitting is wanted and an app is on screen, makes
     * the split (once) and adds FLAG_ACTIVITY_LAUNCH_ADJACENT to [intent]. Returns true when the flag was added.
     */
    fun beforeLaunch(c: Context, intent: Intent): Boolean {
        if (armed.get() != true || !supported) return false
        val svc = IosControlService.instance ?: return false
        val fg = foreground(c)
        if (fg == null) { ownActive = false; return false }           // home screen / launcher: nothing to sit beside
        val target = targetPkg(c, intent)
        if (target != null && target == fg) return false              // same app: just open it
        if (target == null && intent.data?.scheme?.startsWith("http") != true && intent.component == null) return false
        if (!inSplit(svc)) {
            if (!enter(svc)) { refused.set(true); return false }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        usedWith.set(labelOf(c, fg))
        return true
    }

    /* ------------------------------------------------------------------ system bits */

    /** The app the user is looking at, or null when it is the home screen, Neo Plus itself or the system UI. */
    private fun foreground(c: Context): String? {
        val pkg: String? = try { UiDriver.foregroundPkg() } catch (e: Throwable) { null }
        if (pkg.isNullOrBlank()) return null
        if (pkg == c.packageName || pkg == "android" || pkg == "com.android.systemui") return null
        val home = try {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (e: Throwable) { null }
        if (home != null && pkg == home) return null
        return pkg
    }

    private fun targetPkg(c: Context, i: Intent): String? {
        i.component?.packageName?.let { return it }
        i.`package`?.let { return it }
        return try {
            val p = c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (p == "android") null else p         // "android" = the "open with" chooser
        } catch (e: Throwable) { null }
    }

    private fun labelOf(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) { "the app you are in" }

    /** true / false when the on-screen windows can be read; null when they cannot. */
    private fun splitFromWindows(svc: AccessibilityService): Boolean? {
        val ws = try { svc.windows } catch (e: Throwable) { null }
        if (ws.isNullOrEmpty()) return null
        return ws.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } >= 2
    }

    private fun inSplit(svc: AccessibilityService): Boolean {
        splitFromWindows(svc)?.let { ownActive = it; return it }
        return ownActive && System.currentTimeMillis() - ownAt < 20 * 60_000L
    }

    /** Presses the system's split-screen action and waits (up to ~1.5 s) for the split to show. */
    private fun enter(svc: AccessibilityService): Boolean {
        if (!supported) return false
        val ok = try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) } catch (e: Throwable) { false }
        if (!ok) return false
        ownActive = true; ownAt = System.currentTimeMillis()
        repeat(25) {
            if (splitFromWindows(svc) == true) return true
            try { Thread.sleep(60) } catch (e: InterruptedException) { return true }
        }
        // windows could not be read, or the split is slow: give the system a moment to finish before the next app starts
        if (splitFromWindows(svc) == null) try { Thread.sleep(700) } catch (e: InterruptedException) { }
        return true
    }

    /* ------------------------------------------------------------------ the `split` action */

    fun handle(c: Context, a: JSONObject): String = when (a.optString("do", "status")) {
        "on" -> { setEnabled(c, true); "Split screen is on. When you are inside an app and ask for another app or a website, it opens beside the one you are in. Say “split screen off” to stop that." }
        "off" -> { setEnabled(c, false); "Split screen is off. Apps open full screen again; say “open X and Y side by side” when you want two." }
        "exit" -> exit(c)
        else -> status(c)
    }

    private fun status(c: Context): String {
        if (!supported) return "This phone’s Android is too old for split screen (it needs Android 7 or newer)."
        val svc = IosControlService.instance
        val sb = StringBuilder("Automatic split screen is ${if (enabled(c)) "on" else "off"}.")
        if (svc == null) sb.append(" Turn on “NEO PLUS iOS Controls” in Accessibility, or I can’t split the screen.")
        else when (splitFromWindows(svc)) {
            true -> sb.append(" The screen is split right now.")
            false -> sb.append(" The screen is not split right now.")
            null -> sb.append(" I can’t tell whether the screen is split at the moment.")
        }
        return sb.toString()
    }

    private fun exit(c: Context): String {
        if (!supported) return "This phone’s Android is too old for split screen."
        val svc = IosControlService.instance ?: return "Turn on “NEO PLUS iOS Controls” in Accessibility first so I can close the split."
        when (splitFromWindows(svc)) {
            false -> { ownActive = false; return "The screen isn’t split right now." }
            null -> if (!ownActive) return "I can’t tell whether the screen is split, and I didn’t start one. Drag the divider to the edge to close it."
            true -> { }
        }
        val ok = try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) } catch (e: Throwable) { false }
        ownActive = false
        return if (ok) "Split screen closed." else "Couldn’t close the split: drag the divider to the edge of the screen."
    }
}

/** Voice / typed commands for [SplitScreen]. Checked before [LabCmd], so "side by side" is not taken for a list of tasks. */
object SplitCmd {
    private val I = RegexOption.IGNORE_CASE
    private fun r(s: String) = Regex(s, I)
    private const val SS = "split[- ]?(?:screen|view)"

    private val exitRe = r("\\b(?:exit|close|leave|end|quit|get out of)\\b.*\\b$SS\\b")
    private val offRe = r("\\b(?:turn off|switch off|disable)\\b.*\\b$SS\\b|\\b(?:auto(?:matic)?[- ]?)?$SS (?:mode )?off\\b")
    private val onRe = r("\\b(?:turn on|switch on|enable)\\b.*\\b$SS\\b|\\b(?:auto(?:matic)?[- ]?)?$SS (?:mode )?on\\b")
    private val statusRe = r("\\b$SS status\\b|\\bis (?:the |my )?(?:screen|phone) split\\b")
    private val sideBySide = r("^(?:please\\s+)?(?:open|show|put|run|use|launch)\\s+(.+?)\\s+(?:and|with|plus|&)\\s+(.+?)\\s+(?:side[- ]by[- ]side|in $SS|$SS|together on (?:the )?screen)$")
    private val splitThese = r("^(?:please\\s+)?$SS\\s+(?:with\\s+)?(.+?)\\s+(?:and|with|plus|&)\\s+(.+)$")
    private val nextTo = r("^(?:please\\s+)?(?:open|show|launch)\\s+(.+?)\\s+(?:next to|beside|alongside)\\s+(.+)$")
    private val oneSplit = r("^(?:please\\s+)?(?:open|launch|show)\\s+(.+?)\\s+(?:in|on|with)\\s+(?:the\\s+)?(?:$SS|side|a split)$")
    private val url = r("^(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:/\\S*)?$")

    private fun clean(s: String) = s.trim().trim(',', '.', '!', '?').replace(Regex("^(?:the|my)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "").trim()

    private fun open(item: String, split: String): Pair<String, JSONObject> {
        val x = clean(item)
        return if (url.matches(x)) "open_url" to JSONObject().put("url", x).put("split", split)
        else "open_app" to JSONObject().put("app", x).put("split", split)
    }

    private fun act(d: String) = LocalPlan("", listOf("split" to JSONObject().put("do", d)))

    fun plan(text: String): LocalPlan? {
        val t = text.trim().trimEnd('.', '!', '?')
        if (exitRe.containsMatchIn(t)) return act("exit")
        if (offRe.containsMatchIn(t)) return act("off")
        if (onRe.containsMatchIn(t)) return act("on")
        if (statusRe.containsMatchIn(t)) return act("status")
        sideBySide.find(t)?.let { m -> return pair(m.groupValues[1], m.groupValues[2]) }
        splitThese.find(t)?.let { m -> return pair(m.groupValues[1], m.groupValues[2]) }
        // "open B next to A": A is the one that stays, B goes beside it
        nextTo.find(t)?.let { m -> return pair(m.groupValues[2], m.groupValues[1]) }
        oneSplit.find(t)?.let { m ->
            val (n, j) = open(m.groupValues[1], "true")
            return LocalPlan("Opening ${clean(m.groupValues[1])} beside this app.", listOf(n to j))
        }
        return null
    }

    private fun pair(first: String, second: String): LocalPlan {
        val a = clean(first); val b = clean(second)
        return LocalPlan("Opening $a and $b side by side.", listOf(open(a, "base"), open(b, "true")))
    }
}
