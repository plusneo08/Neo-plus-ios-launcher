package com.neo.plus.iosluancher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives ANY app on the phone through the accessibility service: tap a button by its label, type into a field,
 * scroll / swipe, press Back, search inside an app. Everything runs on the phone itself: no internet, no AI model,
 * no Gemini quota. Every call here BLOCKS until it is finished, so call it from a background thread (the assistant
 * already does) and several steps in a row happen in order.
 */
object UiDriver {
    const val NEED_SERVICE = "Turn on “NEO PLUS iOS Controls” in Accessibility so I can control other apps"
    const val NO_APP = "No app is open to control. Say “in WhatsApp tap New chat”, or use the floating assistant while the app is open"

    /* ---------------- reading the screen ---------------- */

    /** The window in front, but never Neo Plus itself. [pkgs] limits it to certain apps (null = any other app). */
    fun rootOf(ctx: Context, pkgs: Set<String>?): AccessibilityNodeInfo? {
        val svc = IosControlService.instance ?: return null
        val r = try { svc.rootInActiveWindow } catch (e: Exception) { null } ?: return null
        val p = r.packageName?.toString() ?: return null
        if (p == ctx.packageName) return null
        if (pkgs != null && p !in pkgs) return null
        return r
    }

    fun foregroundPkg(): String? = try { IosControlService.instance?.rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    /** All nodes of the window (breadth first, capped so a huge feed cannot freeze us). */
    fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && out.size < 2500) {
            val n = q.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    private fun clean(s: CharSequence?): String = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""

    /** Everything a node can be called: its text, description, hint and the last part of its id. */
    fun names(n: AccessibilityNodeInfo): List<String> {
        val hint = if (Build.VERSION.SDK_INT >= 26) clean(n.hintText) else ""
        val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: ""
        return listOf(clean(n.text), clean(n.contentDescription), hint, id).filter { it.isNotEmpty() }
    }

    /**
     * [want] is matched against the node's names. Plain = whole name must be equal; a leading "~" = the name just has
     * to contain it. Exact matches are preferred over partial ones, visible nodes over hidden ones.
     */
    fun find(nodes: List<AccessibilityNodeInfo>, want: String, editable: Boolean? = null): AccessibilityNodeInfo? {
        val partial = want.startsWith("~")
        val w = want.removePrefix("~").trim().lowercase(Locale.ROOT)
        if (w.isEmpty()) return null
        fun ok(n: AccessibilityNodeInfo, contains: Boolean): Boolean {
            if (editable != null && n.isEditable != editable) return false
            if (n.isPassword) return false
            return names(n).any { if (contains) it.contains(w) else it == w }
        }
        if (!partial) {
            nodes.firstOrNull { it.isVisibleToUser && ok(it, false) }?.let { return it }
        }
        nodes.firstOrNull { it.isVisibleToUser && ok(it, true) }?.let { return it }
        return null
    }

    /** First label of [labels] (or id of [ids]) that is on the screen. */
    fun findAny(root: AccessibilityNodeInfo, labels: List<String>, ids: List<String> = emptyList()): AccessibilityNodeInfo? {
        for (id in ids) {
            val hit = try { root.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            hit?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        val nodes = all(root)
        for (l in labels) find(nodes, l)?.let { return it }
        return null
    }

    /** The visible password box on the screen, if there is one. */
    fun findPassword(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        all(root).firstOrNull { it.isEditable && it.isVisibleToUser && it.isPassword }

    fun findEditable(root: AccessibilityNodeInfo, into: String = "", skipSearch: Boolean = false): AccessibilityNodeInfo? {
        val nodes = all(root)
        val fields = nodes.filter { it.isEditable && it.isVisibleToUser && !it.isPassword }
        if (into.isNotBlank()) {
            val w = into.lowercase(Locale.ROOT).trim()
            fields.firstOrNull { f -> names(f).any { it.contains(w) } }?.let { return it }
            return null
        }
        val usable = if (skipSearch) fields.filter { f -> names(f).none { it.contains("search") } } else fields
        return usable.firstOrNull { it.isFocused } ?: usable.firstOrNull()
    }

    /** Polls the screen every ~350 ms until [f] returns something, or [ms] have passed. */
    fun <T> waitFor(ctx: Context, pkgs: Set<String>?, ms: Long, f: (AccessibilityNodeInfo) -> T?): T? {
        val end = SystemClock.uptimeMillis() + ms
        while (true) {
            val r = rootOf(ctx, pkgs)
            if (r != null) {
                val v = try { f(r) } catch (e: Exception) { null }
                if (v != null) return v
            }
            if (SystemClock.uptimeMillis() >= end) return null
            try { Thread.sleep(350) } catch (e: InterruptedException) { return null }
        }
    }

    /* ---------------- doing things ---------------- */

    private fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val accepted = try {
            svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.set(true); latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { false }
        if (!accepted) return false
        latch.await(durationMs + 2500, TimeUnit.MILLISECONDS)
        return done.get()
    }

    fun tapAt(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val svc = IosControlService.instance ?: return false
        val p = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        return gesture(svc, p, durationMs)
    }

    /** Clicks the node (or its nearest clickable parent); if the app gives no clickable view, touches its middle. */
    fun tap(node: AccessibilityNodeInfo): Boolean {
        if (AutoClicker.click(node)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    fun longPress(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var up = 0
        while (n != null && !n.isLongClickable && up < 6) { n = n.parent; up++ }
        if (n != null && n.isLongClickable && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY(), 900)
    }

    /** [finger] = the direction the finger moves: "up" is what you do to see the next video / scroll a feed down. */
    fun swipe(ctx: Context, finger: String): Boolean {
        val svc = IosControlService.instance ?: return false
        val m = ctx.resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val p = Path()
        when (finger) {
            "up" -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }
            "down" -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return false
        }
        if (gesture(svc, p, 320)) return true
        // No gesture permission yet: use the scroll buttons of a scrolling list instead.
        val root = rootOf(ctx, null) ?: return false
        val list = all(root).filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height() } ?: return false
        return list.performAction(if (finger == "up" || finger == "left") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /** Puts [text] in the field. [replace] = false adds it after what is already there. */
    fun setText(ctx: Context, node: AccessibilityNodeInfo, text: String, replace: Boolean = false, secret: Boolean = false): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (replace || node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Some apps refuse direct text: paste it instead (never for a password, so it is not left on the clipboard).
        if (secret) return false
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("neo", text))
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    fun pressEnter(ctx: Context, field: AccessibilityNodeInfo?): Boolean {
        if (field != null && Build.VERSION.SDK_INT >= 30 &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        val root = rootOf(ctx, null) ?: return false
        val btn = findAny(root, listOf("search", "go", "enter", "done", "send")) ?: return false
        return tap(btn)
    }

    /**
     * For apps that draw their buttons without any text or description (so the label search finds nothing): photograph
     * the screen, let the AI point at [what], and touch that spot. Costs one Gemini call and sends one screenshot of the
     * app in front, which exec() has already checked is a connected app.
     */
    private fun visionTapFor(ctx: Context, apps: List<AppEntry>, what: String, pressMs: Long = 60L): Boolean {
        val fg = foregroundPkg() ?: return false
        if (fg == ctx.packageName) return false
        val label = apps.firstOrNull { it.pkg == fg }?.shownLabel ?: fg
        val spot = VisionTap.locate(ctx, label, what.removePrefix("~"), extra = "") ?: return false
        return VisionTap.tap(spot, pressMs)
    }

    /** Short list of what can be tapped on the screen now. */
    fun listTappable(root: AccessibilityNodeInfo): String {
        val seen = LinkedHashSet<String>()
        for (n in all(root)) {
            if (!n.isVisibleToUser || n.isPassword) continue
            if (!(n.isClickable || n.isEditable || n.isCheckable)) continue
            val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().replace(Regex("\\s+"), " ")
            if (label.isEmpty() || label.length > 40) continue
            seen += if (n.isEditable) "$label (text box)" else label
            if (seen.size >= 30) break
        }
        return if (seen.isEmpty()) "I can’t read any buttons here: this app draws them without labels. Use look to see the screen, then ui_tap_visual with a description of what to tap" else "On screen I can tap: " + seen.joinToString(", ")
    }

    /* ---------------- assistant actions ---------------- */

    private fun labelsOf(a: JSONObject): List<String> {
        val arr: JSONArray? = a.optJSONArray("labels")
        val l = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { l += it }
        a.optString("label").takeIf { it.isNotBlank() }?.let { l += it }
        return l
    }

    /**
     * ui_tap {"label":"Post","app":"optional"} / {"labels":["post","share"]}   ui_long_press {...}
     * ui_type {"text":"hello","into":"optional field name","replace":false,"app":"optional"}
     * ui_scroll {"finger":"up|down|left|right","times":1,"app":"optional"}   ui_back {"times":1}
     * ui_search {"text":"cats","app":"optional"}   ui_enter {}   ui_list {}   ui_wait {"seconds":2}
     */
    fun exec(ctx: Context, name: String, a: JSONObject, apps: List<AppEntry>): String {
        if (IosControlService.instance == null) return NEED_SERVICE
        if (Looper.myLooper() == Looper.getMainLooper()) return "Couldn’t do that on the main thread"

        if (name == "ui_wait") {
            val s = a.optInt("seconds", 1).coerceIn(1, 30)
            try { Thread.sleep(s * 1000L) } catch (e: InterruptedException) { }
            return "Waited $s s"
        }
        if (name == "ui_back") {
            val n = a.optInt("times", 1).coerceIn(1, 10)
            repeat(n) {
                IosControlService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                try { Thread.sleep(450) } catch (e: InterruptedException) { }
            }
            return "Went back"
        }

        // Optionally start the app first, then wait until it is in front.
        var pkgs: Set<String>? = null
        val appQ = a.optString("app").trim()
        if (appQ.isNotEmpty()) {
            val app = apps.findBest(appQ) ?: return "No app called “$appQ”"
            if (!AppAccess.allowed(ctx, app.pkg)) return AppAccess.blockedMessage(app.shownLabel)
            pkgs = setOf(app.pkg)
            if (foregroundPkg() != app.pkg) {
                ctx.openAppVerified(app)?.let { return it }
                if (waitFor(ctx, pkgs, 9000) { true } == null) return "Couldn’t open ${app.shownLabel} in time"
                try { Thread.sleep(700) } catch (e: InterruptedException) { }
            }
        }

        // No app named: the action works on whatever is in front, so that app must be connected too.
        if (pkgs == null) {
            val fg = foregroundPkg()
            // Only a real phone app can be "not connected". The keyboard, the share sheet, the status bar and permission pop-ups are
            // not in the app list, so they must never block a task inside a connected app.
            val fgApp = apps.firstOrNull { it.pkg == fg }
            if (fgApp != null && !AppAccess.allowed(ctx, fg)) return AppAccess.blockedMessage(fgApp.shownLabel)
        }

        fun nothingOpen(what: String) =
            if (foregroundPkg() == ctx.packageName) NO_APP else "Couldn’t find $what on the screen"

        return when (name) {
            "ui_tap", "ui_long_press" -> {
                val labels = labelsOf(a)
                if (labels.isEmpty()) return "No button name given"
                val long = name == "ui_long_press"
                val node = waitFor(ctx, pkgs, if (a.optBoolean("optional")) 3500 else 6000) { r -> findAny(r, labels) }
                if (node == null) {
                    if (a.optBoolean("optional")) return "Nothing to tap"
                    // No text or description matched: this app may draw its buttons without labels, so look at the screen.
                    if (visionTapFor(ctx, apps, labels[0], if (long) 700L else 60L)) {
                        try { Thread.sleep(550) } catch (e: InterruptedException) { }
                        return (if (long) "Held" else "Tapped") + " “${labels[0].removePrefix("~")}” (found by looking at the screen)"
                    }
                    return nothingOpen("“${labels[0]}”")
                }
                val ok = if (long) longPress(node) else tap(node)
                try { Thread.sleep(550) } catch (e: InterruptedException) { }
                if (ok) (if (long) "Held “${labels[0]}”" else "Tapped “${labels[0]}”") else "Couldn’t tap “${labels[0]}”"
            }

            "ui_type" -> {
                val text = a.optString("text")
                if (text.isEmpty()) return "No text to type"
                val into = a.optString("into")
                // {{password}} / {{username}} are filled in here from the login the user saved in Assistant settings -> Websites
                // (switched on). The AI model only ever sees the placeholder, never the real value.
                val wantsPw = text.contains("{{password}}")
                val wantsUser = text.contains("{{username}}")
                // {{pin:NAME}} is filled in from Assistant settings -> Saved PINs. The model only ever sees the placeholder.
                val pinM = Regex("\\{\\{pin:([^}]{1,30})\\}\\}", RegexOption.IGNORE_CASE).find(text)
                val wantsPin = pinM != null
                var toType = text
                if (wantsPw || wantsUser) {
                    val acct = a.optString("account").ifBlank { appQ }
                    val e = SiteVault.forAccount(ctx, acct)
                        ?: return "No saved login for “${acct.ifBlank { "this account" }}”. Add it in Assistant settings → Websites with your username and password and switch it on, then ask again."
                    toType = text.replace("{{password}}", e.password).replace("{{username}}", e.username)
                }
                if (pinM != null) {
                    val real = PinVault.get(ctx, pinM.groupValues[1])
                        ?: return if (PinVault.unattended) "PINs are only typed when you ask me yourself, not in scheduled jobs"
                        else "No saved PIN called “${pinM.groupValues[1].trim()}”. Save it in Assistant settings → Saved PINs, then ask again."
                    toType = text.replace(pinM.value, real)
                }
                var found = waitFor(ctx, pkgs, 6000) { r ->
                    if (wantsPw) findPassword(r) else if (wantsPin) (findPassword(r) ?: findEditable(r, "pin")) else findEditable(r, into)
                }
                // No readable text box: touch the box the AI points at, then look for the focused one again.
                if (found == null && !wantsPw && !wantsPin && foregroundPkg() != ctx.packageName &&
                    visionTapFor(ctx, apps, if (into.isNotBlank()) "the text box called $into" else "the text box where the text is typed")) {
                    try { Thread.sleep(700) } catch (e: InterruptedException) { }
                    found = waitFor(ctx, pkgs, 3000) { r -> findEditable(r, "") }
                }
                val field = found
                    ?: return if (foregroundPkg() == ctx.packageName) NO_APP
                    else if (wantsPw) "Couldn’t find a password box on the screen"
                    else "Couldn’t find a text box${if (into.isNotBlank()) " called “$into”" else ""} on the screen"
                val ok = setText(ctx, field, toType, a.optBoolean("replace", false) || wantsPw || wantsPin, secret = wantsPw || wantsPin)
                try { Thread.sleep(400) } catch (e: InterruptedException) { }
                if (ok) "Typed “${text.take(60)}”" else "Couldn’t type into that box"
            }

            "ui_enter" -> {
                val field = rootOf(ctx, pkgs)?.let { findEditable(it) }
                if (pressEnter(ctx, field)) "Pressed enter" else "Couldn’t press enter here"
            }

            "ui_scroll" -> {
                val finger = a.optString("finger", "up")
                val times = a.optInt("times", 1).coerceIn(1, 15)
                if (waitFor(ctx, pkgs, 6000) { true } == null) return nothingOpen("an app")
                var done = 0
                repeat(times) { if (swipe(ctx, finger)) done++; try { Thread.sleep(500) } catch (e: InterruptedException) { } }
                if (done > 0) "Scrolled" else "Couldn’t scroll here. Switch “NEO PLUS iOS Controls” off and on once so it can swipe"
            }

            "ui_search" -> {
                val text = a.optString("text")
                if (text.isEmpty()) return "Nothing to search for"
                var field = waitFor(ctx, pkgs, 2500) { r -> findEditable(r, "search") }
                if (field == null) {
                    val icon = waitFor(ctx, pkgs, 5000) { r -> findAny(r, listOf("search", "~search", "find")) }
                        ?: return nothingOpen("a search box")
                    tap(icon)
                    field = waitFor(ctx, pkgs, 5000) { r -> findEditable(r, "") } ?: return "Couldn’t open the search box"
                }
                if (!setText(ctx, field, text, replace = true)) return "Couldn’t type in the search box"
                try { Thread.sleep(500) } catch (e: InterruptedException) { }
                pressEnter(ctx, field)
                "Searched for “$text”"
            }

            "ui_tap_visual" -> {
                val d = a.optString("describe").ifBlank { a.optString("label") }.trim()
                if (d.isEmpty()) return "Say what to tap, for example “the red heart under the video”"
                if (foregroundPkg() == ctx.packageName) return NO_APP
                if (visionTapFor(ctx, apps, d, if (a.optBoolean("long")) 700L else 60L)) {
                    try { Thread.sleep(550) } catch (e: InterruptedException) { }
                    "Tapped “${d.take(60)}” (found by looking at the screen)"
                } else "I couldn’t see “${d.take(60)}” on the screen"
            }

            "ui_list" -> {
                val r = waitFor(ctx, pkgs, 4000) { r -> r } ?: return if (foregroundPkg() == ctx.packageName) NO_APP else "Couldn’t read the screen"
                listTappable(r)
            }

            else -> "Unknown action “$name”"
        }
    }
}

/**
 * Understands plain commands for the actions above, without any AI model:
 * "tap Post", "in WhatsApp tap New chat", "type hello", "scroll down 3 times", "next video", "like", "go back"...
 */
object UiCmd {
    private val I = RegexOption.IGNORE_CASE
    private val lead = Regex("^(?:hey |ok |okay )?(?:(?:neo plus assistant|neo plus|neo)[, ]+)?(?:please |can you |could you |would you )?", I)
    private const val VERB = "tap|click|press|hit|touch|long press|press and hold|type|enter|search|scroll|swipe|like|love|follow|unfollow|subscribe|go back|back|next|previous|refresh|read|what|list|wait|comment"

    private fun act(name: String, app: String, vararg kv: Pair<String, Any>): Pair<String, JSONObject> {
        val o = JSONObject()
        if (app.isNotBlank()) o.put("app", app)
        kv.forEach { o.put(it.first, it.second) }
        return name to o
    }

    private fun countOf(s: String?): Int = when (s?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> 1
        "twice", "two", "2" -> 2
        "thrice", "three", "3" -> 3
        "four", "4" -> 4
        "five", "5" -> 5
        "a few" -> 3
        else -> s?.trim()?.toIntOrNull()?.coerceIn(1, 15) ?: 1
    }

    /** Free-text commands (typed words may contain "and" / "then", so these must be read before the sentence is cut up). */
    fun planWhole(text: String, apps: List<AppEntry>): LocalPlan? {
        val t = text.trim().replace(lead, "").trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null

        // "in WhatsApp tap New chat, then type hello" -> do each part inside that app
        Regex("^(?:in|inside|within|on)\\s+(?:the\\s+)?(.+?)[,:]?\\s+(?:and\\s+|then\\s+)?((?:$VERB)\\b.*)$", I).find(t)?.let { m ->
            val appName = m.groupValues[1].trim()
            if (apps.findBest(appName) != null && appName.split(" ").size <= 4) {
                val shown = apps.findBest(appName)!!.shownLabel
                val parts = m.groupValues[2].split(Regex("\\s*(?:,\\s*)?\\b(?:and then|then)\\b\\s*", I)).map { it.trim() }.filter { it.isNotEmpty() }
                val actions = ArrayList<Pair<String, JSONObject>>()
                for (p in parts) {
                    val got: List<Pair<String, JSONObject>> = planWhole(p, apps)?.actions
                        ?: planPart(p.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?'), apps)?.let { listOf(it) }
                        ?: return null
                    got.forEach { a ->
                        if (!a.second.has("app") && a.first.startsWith("ui_") && a.first != "ui_back" && a.first != "ui_wait") a.second.put("app", shown)
                    }
                    actions += got
                }
                if (actions.isNotEmpty()) return LocalPlan("", actions)
            }
        }

        // "comment saying nice one" -> open the comment box, type, post
        Regex("^(?:post a |add a |write a |leave a )?comment(?: saying|:| with)\\s+(.+)$", I).find(t)?.let { m ->
            return LocalPlan("", listOf(
                act("ui_tap", "", "labels" to JSONArray(listOf("comment", "~add a comment", "~comment")), "optional" to true),
                act("ui_type", "", "text" to m.groupValues[1].trim()),
                act("ui_tap", "", "labels" to JSONArray(listOf("post", "send", "~post comment")))
            ))
        }

        // "search for cats in YouTube"
        Regex("^search(?: for)?\\s+(.+?)\\s+(?:in|on|inside|within)\\s+(?:the\\s+)?(.+)$", I).find(t)?.let { m ->
            val app = apps.findBest(m.groupValues[2].trim()) ?: return@let
            return LocalPlan("", listOf(act("ui_search", app.shownLabel, "text" to m.groupValues[1].trim())))
        }

        // "type hello world", "type hello into the comment box", optionally "... and send"
        Regex("^(?:type|key in|input|enter the text|write in)\\s+(.+)$", I).find(t)?.let { m ->
            var body = m.groupValues[1].trim()
            var follow: String? = null
            Regex("^(.*?)\\s+(?:and|then)\\s+(send|post|submit|search|press enter|enter)$", I).find(body)?.let { f ->
                body = f.groupValues[1].trim(); follow = f.groupValues[2].lowercase(Locale.ROOT)
            }
            var into = ""
            Regex("^(.+?)\\s+into\\s+(?:the\\s+)?(.+?)(?:\\s+box|\\s+field)?$", I).find(body)?.let { f -> body = f.groupValues[1].trim(); into = f.groupValues[2].trim() }
            body = body.trim('"', '“', '”')
            if (body.isEmpty()) return null
            val acts = ArrayList<Pair<String, JSONObject>>()
            acts += act("ui_type", "", "text" to body, "into" to into)
            when (follow) {
                null -> Unit
                "search", "press enter", "enter" -> acts += act("ui_enter", "")
                else -> acts += act("ui_tap", "", "labels" to JSONArray(listOf(follow!!, "~$follow")))
            }
            return LocalPlan("", acts)
        }
        return null
    }

    /** For the sentence splitter: one small command per call. */
    fun planOne(t: String, apps: List<AppEntry>): LocalPlan? {
        val p = planPart(t, apps) ?: return null
        return LocalPlan("", listOf(p))
    }

    private fun planPart(t: String, apps: List<AppEntry>): Pair<String, JSONObject>? {
        if (t.isEmpty()) return null

        // optional trailing "in <app>"
        var body = t
        var app = ""
        Regex("^(.+?)\\s+(?:in|inside|within)\\s+(?:the\\s+)?(.+)$").find(t)?.let { m ->
            val cand = apps.findBest(m.groupValues[2].trim())
            if (cand != null) { body = m.groupValues[1].trim(); app = cand.shownLabel }
        }

        // keep original capitalisation only where words are typed; labels are matched in lower case
        Regex("^(?:go back|press back|tap back|back)$").find(body)?.let { return act("ui_back", "") }
        Regex("^(?:go back|back|press back)\\s+(\\d+|twice|three times|a few)(?: times)?$").find(body)?.let { m ->
            return act("ui_back", "", "times" to countOf(m.groupValues[1].replace(" times", "")))
        }
        Regex("^wait(?: for)?\\s+(\\d+)\\s*(?:seconds?|secs?|s)?$").find(body)?.let { m -> return act("ui_wait", "", "seconds" to m.groupValues[1].toInt()) }
        if (Regex("^(?:read|show)(?: me)?(?: what(?:'s| is) on)?(?: the| my)? screen$|^what(?:'s| is) on (?:the |my )?screen$|^read what(?:'s| is) on (?:the |my )?screen$").matches(body))
            return act("screen", "")
        if (Regex("^(?:what can i (?:tap|click|press)|list (?:the )?(?:buttons|options)|what buttons (?:are there|can you see))$").matches(body))
            return act("ui_list", app)
        if (Regex("^(?:press |hit )?(?:enter|search|go|return)$").matches(body) && body != "go") return act("ui_enter", app)

        // scrolling / swiping
        Regex("^(scroll|swipe)(?: the (?:page|screen|feed|list))?\\s*(up|down|left|right)(?:\\s+(\\d+|twice|thrice|a few)(?: times)?)?(?: a bit| a little)?$").find(body)?.let { m ->
            val dir = m.groupValues[2]
            val finger = if (m.groupValues[1] == "swipe") dir else when (dir) { "down" -> "up"; "up" -> "down"; "left" -> "right"; else -> "left" }
            return act("ui_scroll", app, "finger" to finger, "times" to countOf(m.groupValues[3]))
        }
        Regex("^(?:next|skip)(?: to the next)?(?: video| reel| short| story| post)$").find(body)?.let { return act("ui_scroll", app, "finger" to "up") }
        Regex("^(?:previous|prev|last)(?: video| reel| short| story| post)$").find(body)?.let { return act("ui_scroll", app, "finger" to "down") }
        Regex("^(?:refresh|reload)(?: the)?(?: page| feed| screen)?$").find(body)?.let { return act("ui_scroll", app, "finger" to "down") }

        // one-word actions on posts
        Regex("^(?:like|love|heart)(?: this| the| that)?(?: post| video| photo| reel| tweet| comment| picture)?$").find(body)?.let {
            return act("ui_tap", app, "labels" to JSONArray(listOf("like", "~like", "~heart")))
        }
        Regex("^(follow|unfollow|subscribe|unsubscribe)(?: this| the| that)?(?: account| page| user| channel| profile| post| video)?$").find(body)?.let { m ->
            val w = m.groupValues[1]
            return act("ui_tap", app, "labels" to JSONArray(listOf(w, "~$w")))
        }

        // search inside the app that is open
        Regex("^search(?: for)?\\s+(.+)$").find(body)?.let { m ->
            if (app.isNotBlank()) return act("ui_search", app, "text" to m.groupValues[1].trim())
        }

        // taps
        Regex("^(?:long press|press and hold|touch and hold)(?: on)?(?: the)?\\s+(.+?)(?: button| icon| tab| option| link)?$").find(body)?.let { m ->
            return act("ui_long_press", app, "label" to m.groupValues[1].trim())
        }
        Regex("^(?:tap|click|press|hit|touch)(?: on)?(?: the)?\\s+(.+?)(?: button| icon| tab| option| link| menu)?$").find(body)?.let { m ->
            val label = m.groupValues[1].trim()
            if (label.isNotEmpty() && label.split(" ").size <= 6) return act("ui_tap", app, "label" to label)
        }
        return null
    }
}
