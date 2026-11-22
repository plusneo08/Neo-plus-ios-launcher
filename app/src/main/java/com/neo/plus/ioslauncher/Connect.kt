package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

/** One connected app or service. [links] are deep-link templates per intent ({q} = what to search for); [api] is an optional https API. */
class Conn(
    val id: String,
    val name: String,
    val cat: String,
    val pkgs: List<String>,
    val links: Map<String, String> = emptyMap(),
    val api: JSONObject? = null,
    val custom: Boolean = false
)

/** Reads the screen after an action to check that it worked. Needs the accessibility service; without it nothing can be confirmed. */
object Verify {
    fun fg(): String? = try { IosControlService.instance?.rootInActiveWindow?.packageName?.toString() } catch (e: Throwable) { null }

    fun screenText(max: Int = 3000): String {
        val root = (try { IosControlService.instance?.rootInActiveWindow } catch (e: Throwable) { null }) ?: return ""
        val sb = StringBuilder()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null || sb.length > max) return
            if (!n.isPassword && n.isVisibleToUser) {
                val t = n.text?.toString() ?: n.contentDescription?.toString()
                if (!t.isNullOrBlank()) sb.append(t.trim()).append(' ')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    /**
     * Waits up to [ms] until the front app is one of [pkgs] (if any given) and the screen shows one of the words in [text]
     * (separate with |, if given). true = confirmed, false = not seen in time, null = cannot tell (Accessibility off or on the main thread).
     */
    fun waitFor(pkgs: List<String>, text: String, ms: Long): Boolean? {
        if (IosControlService.instance == null) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val words = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val end = System.currentTimeMillis() + ms
        while (true) {
            val okPkg = pkgs.isEmpty() || (fg()?.let { it in pkgs } ?: false)
            val okText = words.isEmpty() || screenText().let { s -> words.any { s.contains(it, ignoreCase = true) } }
            if (okPkg && okText) return true
            if (System.currentTimeMillis() >= end) return false
            try { Thread.sleep(400) } catch (e: InterruptedException) { return false }
        }
    }
}

/**
 * Connected apps, controlled the most reliable way that works, with a check afterwards:
 *   1. API       the connector has an https API set up (stored on this phone only)
 *   2. DEEP LINK the app's own link / intent opens it straight at the right screen
 *   3. TAPS      open the app and let App task look at the screen and tap, step by step
 * If a path fails or the check does not see the result, the next path is tried. The result says which path worked.
 *
 * Provider choice: for a kind of service ("ride", "music", "maps", "video", "food") the assistant never picks the app for you.
 * It uses the one you chose ("use Spotify for music") or asks which to use.
 *
 * Voice or AI: connect {"do":"run","app":"Spotify","intent":"search","q":"jazz"} / {"category":"ride","intent":"ride","q":"airport"} /
 * {"do":"list"} {"do":"add",...} {"do":"remove","id":"..."} {"do":"prefer","category":"music","app":"Spotify"} {"do":"verify","expect":"text"}
 * {"do":"health"} {"do":"approve"} {"do":"cancel"}
 *
 * Not built: full OAuth sign-in (a token you give is stored and a 401 / 403 marks the connector "needs sign-in"), and sending,
 * paying or ordering on your behalf: a ride link opens the app with the address filled in and you press the button there.
 */
object Connect {
    private const val PREF = "neo_connect"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Starting points. If an app ignores a link, the taps path takes over, so a wrong link only costs a few seconds. */
    private val builtin = listOf(
        Conn("spotify", "Spotify", "music", listOf("com.spotify.music"),
            mapOf("search" to "spotify:search:{q}", "play" to "spotify:search:{q}")),
        Conn("ytmusic", "YouTube Music", "music", listOf("com.google.android.apps.youtube.music"),
            mapOf("search" to "https://music.youtube.com/search?q={q}", "play" to "https://music.youtube.com/search?q={q}")),
        Conn("youtube", "YouTube", "video", listOf("com.google.android.youtube"),
            mapOf("search" to "https://www.youtube.com/results?search_query={q}", "play" to "https://www.youtube.com/results?search_query={q}")),
        Conn("facebook", "Facebook", "social", listOf("com.facebook.katana", "com.facebook.lite"),
            mapOf("search" to "https://www.facebook.com/search/top/?q={q}")),
        Conn("instagram", "Instagram", "social", listOf("com.instagram.android", "com.instagram.lite"),
            mapOf("search" to "https://www.instagram.com/explore/")),
        Conn("pinterest", "Pinterest", "social", listOf("com.pinterest"),
            mapOf("search" to "https://www.pinterest.com/search/pins/?q={q}")),
        Conn("x", "X", "social", listOf("com.twitter.android"),
            mapOf("search" to "https://x.com/search?q={q}")),
        Conn("tiktok", "TikTok", "social", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            mapOf("search" to "https://www.tiktok.com/search?q={q}")),
        Conn("gmaps", "Google Maps", "maps", listOf("com.google.android.apps.maps"),
            mapOf("navigate" to "google.navigation:q={q}", "search" to "geo:0,0?q={q}")),
        Conn("waze", "Waze", "maps", listOf("com.waze"),
            mapOf("navigate" to "waze://?q={q}&navigate=yes", "search" to "waze://?q={q}")),
        Conn("uber", "Uber", "ride", listOf("com.ubercab"),
            mapOf("ride" to "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]={q}")),
        Conn("lyft", "Lyft", "ride", listOf("me.lyft.android"),
            mapOf("ride" to "lyft://ridetype?id=lyft&destination[address]={q}")),
        Conn("bolt", "Bolt", "ride", listOf("ee.mtakso.client")),
        Conn("ubereats", "Uber Eats", "food", listOf("com.ubercab.eats")),
        Conn("doordash", "DoorDash", "food", listOf("com.dd.doordash"))
    )

    private val cats = mapOf(
        "ride" to "ride", "rides" to "ride", "taxi" to "ride", "taxis" to "ride", "cab" to "ride", "cabs" to "ride",
        "music" to "music", "songs" to "music", "maps" to "maps", "navigation" to "maps", "directions" to "maps",
        "video" to "video", "videos" to "video", "food" to "food", "delivery" to "food"
    )

    fun catOf(w: String): String? = cats[w.lowercase().trim()]

    /* ---------------------------------------------------------------- registry */

    private fun customs(c: Context): List<Conn> {
        val arr = try { JSONArray(sp(c).getString("custom", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Conn>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pk = ArrayList<String>()
            o.optJSONArray("pkgs")?.let { for (k in 0 until it.length()) pk.add(it.optString(k)) }
            val ln = HashMap<String, String>()
            o.optJSONObject("links")?.let { l -> val ks = l.keys(); while (ks.hasNext()) { val k = ks.next(); ln[k] = l.optString(k) } }
            out.add(Conn(o.optString("id"), o.optString("name"), o.optString("cat", "app"), pk, ln, o.optJSONObject("api"), true))
        }
        return out
    }

    private fun saveCustoms(c: Context, l: List<Conn>) {
        val arr = JSONArray()
        l.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("cat", it.cat)
                .put("pkgs", JSONArray(it.pkgs)).put("links", JSONObject(it.links as Map<*, *>)).put("api", it.api))
        }
        sp(c).edit().putString("custom", arr.toString()).apply()
    }

    fun hasCustom(c: Context): Boolean = customs(c).isNotEmpty()

    fun all(c: Context): List<Conn> {
        val cu = customs(c)
        return builtin.filter { b -> cu.none { it.id == b.id } } + cu
    }

    fun find(c: Context, q: String): Conn? {
        val t = q.lowercase().trim()
        if (t.isEmpty()) return null
        val list = all(c)
        return list.firstOrNull { it.id == t || it.name.lowercase() == t }
            ?: list.firstOrNull { it.name.lowercase().contains(t) || t.contains(it.name.lowercase()) }
    }

    private fun installedPkg(c: Context, k: Conn): String? =
        k.pkgs.firstOrNull { try { c.packageManager.getLaunchIntentForPackage(it) != null } catch (e: Throwable) { false } }

    private fun prefOf(c: Context, cat: String): String? = try {
        JSONObject(sp(c).getString("pref", "{}")).optString(cat).ifBlank { null }
    } catch (e: Exception) { null }

    private fun setPref(c: Context, cat: String, id: String) {
        val o = try { JSONObject(sp(c).getString("pref", "{}")) } catch (e: Exception) { JSONObject() }
        sp(c).edit().putString("pref", o.put(cat, id).toString()).apply()
    }

    private fun markAuth(c: Context, id: String, bad: Boolean) {
        val set = (sp(c).getStringSet("auth", emptySet()) ?: emptySet()).toMutableSet()
        if (bad) set.add(id) else set.remove(id)
        sp(c).edit().putStringSet("auth", set).apply()
    }

    /** Names of connectors whose API said 401 / 403 and so need you to sign in again. */
    fun needsAuth(c: Context): List<String> {
        val ids = sp(c).getStringSet("auth", emptySet()) ?: emptySet()
        return all(c).filter { it.id in ids }.map { it.name }
    }

    /* ---------------------------------------------------------------- commands */

    fun handle(c: Context, a: JSONObject, apps: List<AppEntry>): String = when (a.optString("do").lowercase().ifBlank { "run" }) {
        "list" -> list(c)
        "add" -> add(c, a)
        "remove" -> remove(c, a)
        "prefer" -> prefer(c, a)
        "run" -> run(c, a, apps)
        "approve" -> Guard.approve(c, a, apps)
        "cancel" -> Guard.cancel(a)
        "verify" -> verify(a)
        "health" -> Watch.healthReport(c)
        else -> "Unknown connect command"
    }

    private fun list(c: Context): String {
        val auth = needsAuth(c)
        return all(c).joinToString("\n") { k ->
            val have = if (installedPkg(c, k) != null) "installed" else if (k.api != null) "API only" else "not installed"
            val ways = listOfNotNull(if (k.api != null) "API" else null, if (k.links.isNotEmpty()) "link" else null, "taps").joinToString("/")
            "${k.name} (${k.cat}): $have, $ways" + (if (k.id in (sp(c).getStringSet("auth", emptySet()) ?: emptySet())) ", NEEDS SIGN-IN" else "") +
                (if (prefOf(c, k.cat) == k.id) ", your choice for ${k.cat}" else "")
        } + (if (auth.isNotEmpty()) "\nSign in again: " + auth.joinToString() else "")
    }

    private fun publicHost(h: String?): Boolean {
        if (h.isNullOrBlank()) return false
        val x = h.lowercase()
        if (x == "localhost" || x.endsWith(".local") || x.endsWith(".internal")) return false
        if (x.startsWith("127.") || x.startsWith("10.") || x.startsWith("192.168.") || x.startsWith("169.254.") || x == "0.0.0.0") return false
        if (Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(x)) return false
        return true
    }

    private fun add(c: Context, a: JSONObject): String {
        val name = a.optString("name").trim()
        val id = a.optString("id").trim().lowercase().ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "") }
        if (name.isEmpty() || id.isEmpty()) return "Give the connector a name"
        val api = a.optJSONObject("api")
        if (api != null) {
            val url = api.optString("url")
            if (!url.startsWith("https://") || !publicHost(Uri.parse(url).host)) return "Couldn’t add it: an API address must be https and a public site, not this phone’s network."
        }
        val pk = ArrayList<String>()
        a.optString("package").takeIf { it.isNotBlank() }?.let { pk.add(it.trim()) }
        a.optJSONArray("packages")?.let { for (i in 0 until it.length()) pk.add(it.optString(i)) }
        val ln = HashMap<String, String>()
        a.optJSONObject("links")?.let { l -> val ks = l.keys(); while (ks.hasNext()) { val k = ks.next(); ln[k] = l.optString(k) } }
        if (api != null && !Guard.ok(a)) return Guard.hold("connect", a, "connect ${name} to an online service (${Uri.parse(api.optString("url")).host}); anything you ask of it will be sent there")
        if (Guard.tainted() && !Guard.ok(a)) return Guard.hold("connect", a, "add the connector “$name”")
        val keep = customs(c).filter { it.id != id }.toMutableList()
        keep.add(Conn(id, name, a.optString("category").lowercase().ifBlank { "app" }, pk, ln, api, true))
        saveCustoms(c, keep)
        return "Added the connector “$name”" + (if (api != null) ". Its token stays on this phone only." else "")
    }

    private fun remove(c: Context, a: JSONObject): String {
        val id = a.optString("id").ifBlank { a.optString("app") }.trim().lowercase()
        val before = customs(c)
        val keep = before.filter { it.id != id && it.name.lowercase() != id }
        if (keep.size == before.size) return "No custom connector called “$id” (the built-in ones stay)"
        saveCustoms(c, keep)
        return "Removed “$id”"
    }

    private fun prefer(c: Context, a: JSONObject): String {
        val cat = catOf(a.optString("category")) ?: a.optString("category").lowercase().trim()
        val k = find(c, a.optString("app")) ?: return "No connector called “${a.optString("app")}”"
        if (cat.isEmpty()) return "For what? For example: use ${k.name} for ${k.cat}."
        setPref(c, cat, k.id)
        return "Okay, I’ll use ${k.name} for $cat."
    }

    private fun verify(a: JSONObject): String {
        val e = a.optString("expect").trim()
        if (e.isEmpty()) return "What should I look for on the screen?"
        return when (Verify.waitFor(emptyList(), e, 4000L)) {
            true -> "Confirmed: “$e” is on the screen"
            false -> "Couldn’t confirm: “$e” is not on the screen"
            null -> "Couldn’t check: turn on Accessibility control for Neo Plus so I can read the screen"
        }
    }

    /* ---------------------------------------------------------------- run */

    private fun pickProvider(c: Context, cat: String): Conn? {
        val id = prefOf(c, cat) ?: return null
        val k = all(c).firstOrNull { it.id == id } ?: return null
        return if (installedPkg(c, k) != null || k.api != null) k else null
    }

    private fun providerQuestion(c: Context, cat: String): String {
        val opts = all(c).filter { it.cat == cat && (installedPkg(c, it) != null || it.api != null) }
        if (opts.isEmpty()) return "No $cat app found on this phone. Install one, or add it with a connector."
        return "Which should I use for $cat: " + opts.joinToString(" or ") { it.name } +
            "? Say “use ${opts.first().name} for $cat” and I’ll remember it."
    }

    private class ApiRes(val ok: Boolean, val text: String, val held: Boolean = false)

    private fun <T> offMain(block: () -> T): T {
        if (Looper.myLooper() != Looper.getMainLooper()) return block()
        var out: Any? = null
        var err: Throwable? = null
        val t = Thread { try { out = block() } catch (e: Throwable) { err = e } }
        t.start(); t.join(15_000L)
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun callApi(c: Context, k: Conn, intent: String, q: String, a: JSONObject): ApiRes {
        val api = k.api ?: return ApiRes(false, "no API set up")
        val method = api.optString("method", "GET").uppercase()
        if (method != "GET" && !Guard.ok(a)) return ApiRes(false, Guard.hold("connect", a, "send a $method request to ${k.name}"), true)
        if (Guard.tainted() && !Guard.ok(a)) return ApiRes(false, Guard.hold("connect", a, "send “${q.take(40)}” to ${k.name}, because I just read text from elsewhere"), true)
        val url = api.optString("url").replace("{q}", Uri.encode(q)).replace("{intent}", Uri.encode(intent))
        if (!url.startsWith("https://") || !publicHost(Uri.parse(url).host)) return ApiRes(false, "the API address is not allowed")
        return try {
            offMain {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 8000
                    conn.readTimeout = 10000
                    api.optString("token").takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    if (method != "GET") {
                        val body = api.optString("body").replace("{q}", JSONObject.quote(q).let { it.substring(1, it.length - 1) })
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = conn.responseCode
                    if (code == 401 || code == 403) { markAuth(c, k.id, true); ApiRes(false, "it needs you to sign in again (HTTP $code)") }
                    else {
                        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (code in 200..299) { markAuth(c, k.id, false); ApiRes(true, Guard.safe(text.take(600))) }
                        else ApiRes(false, "HTTP $code")
                    }
                } finally { conn.disconnect() }
            }
        } catch (e: Throwable) {
            ApiRes(false, "no answer (${e.javaClass.simpleName})")
        }
    }

    private fun run(c: Context, a: JSONObject, apps: List<AppEntry>): String {
        val via = a.optString("via", "auto").lowercase().ifBlank { "auto" }
        val intent = a.optString("intent", "open").lowercase().ifBlank { "open" }
        val q = a.optString("q").trim()
        val goal = a.optString("goal").trim()
        val expect = a.optString("expect").trim()
        val appQ = a.optString("app").trim()
        val catQ = catOf(a.optString("category")) ?: a.optString("category").lowercase().trim()

        val k: Conn = when {
            appQ.isNotEmpty() -> find(c, appQ)
                ?: apps.findBest(appQ)?.let { Conn(it.pkg, it.shownLabel, "app", listOf(it.pkg)) }
                ?: return "No app called “$appQ”"
            catQ.isNotEmpty() -> pickProvider(c, catQ) ?: return providerQuestion(c, catQ)
            else -> return "Which app should I use?"
        }

        val pkg = installedPkg(c, k)
        if (pkg != null && !AppAccess.allowed(c, pkg)) return AppAccess.blockedMessage(k.name)
        val tried = ArrayList<String>()
        fun done(path: String, text: String): String {
            EventLog.add(c, "connect", "${k.name} $intent via $path")
            return "$text (via $path)"
        }

        // 1) API
        if ((via == "auto" || via == "api") && k.api != null) {
            val r = callApi(c, k, intent, q, a)
            if (r.held) return r.text
            if (r.ok) return done("API", "${k.name}: ${r.text}")
            tried.add("API: ${r.text}")
        }

        // 2) deep link
        val tpl = k.links[intent]
        if ((via == "auto" || via == "link") && tpl != null && pkg != null) {
            if (tpl.contains("{q}") && q.isEmpty()) {
                tried.add("link: it needs something to search for")
            } else {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(tpl.replace("{q}", Uri.encode(q)))).setPackage(pkg)
                if (c.safeStart(i)) {
                    val seen = Verify.waitFor(k.pkgs, expect, 3500L)
                    if (seen != false) {
                        val what = if (q.isNotEmpty()) " for “$q”" else ""
                        return done("deep link" + (if (seen == null) ", not verified" else ", verified"), "Opened ${k.name} ($intent$what)")
                    }
                    tried.add("link: ${k.name} did not come up as expected")
                } else tried.add("link: ${k.name} would not open it")
            }
        }

        // 3) open the app, then taps
        if ((via == "auto" || via == "ui") && pkg != null) {
            val launch = try { c.packageManager.getLaunchIntentForPackage(pkg) } catch (e: Throwable) { null }
            if (launch != null && c.safeStart(launch)) {
                val goalText = goal.ifBlank { if (intent != "open" && q.isNotEmpty()) "$intent $q" else "" }
                if (goalText.isEmpty()) {
                    val seen = Verify.waitFor(k.pkgs, expect, 3500L)
                    if (seen != false) return done("opening the app" + (if (seen == null) ", not verified" else ", verified"), "Opened ${k.name}")
                    tried.add("open: ${k.name} did not come to the front")
                } else {
                    if (Guard.tainted() && !Guard.ok(a))
                        return Guard.hold("connect", a, "do “${goalText.take(60)}” inside ${k.name}, because I just read text from elsewhere")
                    val r = AppTask.run(c, JSONObject().put("goal", goalText).put("app", k.name), apps)
                    val seen = if (expect.isNotEmpty()) Verify.waitFor(k.pkgs, expect, 3000L) else null
                    val check = when (seen) { true -> ", verified"; false -> ", but I could not see “$expect” on screen"; null -> "" }
                    return done("taps$check", r)
                }
            } else tried.add("open: ${k.name} would not start")
        }

        return "Couldn’t use ${k.name} for $intent. " + (if (tried.isEmpty()) "It is not installed and has no API set up." else tried.joinToString(". "))
    }
}

/* ====================================================================================== */
/*  Typed / spoken commands for everything in Connect, Watch, Gestures and CalendarWrite   */
/* ====================================================================================== */

object ConnectCmd {
    private val I = RegexOption.IGNORE_CASE
    private val useFor = Regex("^\\s*(?:please\\s+)?use\\s+(.+?)\\s+for\\s+(rides?|taxis?|cabs?|music|songs|maps|navigation|directions|videos?|food|delivery)\\W*$", I)
    private val approveRe = Regex("^\\s*(?:yes[, ]*)?(?:approve (?:it|that|the pending (?:action|request))|go ahead and (?:do|approve) it|do it)\\W*$", I)
    private val cancelRe = Regex("^\\s*(?:no[, ]*)?(?:cancel (?:it|that|the pending (?:action|request))|don'?t do it|never mind that)\\W*$", I)
    // plain "watch X for Y" is left alone (it could mean watching a video): "monitor X for Y", "keep an eye on X for Y", "watch X app for Y"
    private val monitorRe = Regex("^\\s*(?:please\\s+)?(?:(?:monitor|keep an eye on)\\s+(?:my\\s+)?(.+?)|watch\\s+(?:my\\s+)?(.+?)\\s+app)(?:\\s+notifications)?\\s+for\\s+(.+?)\\W*$", I)
    private val tellMeRe = Regex("^\\s*tell me when\\s+(?:my\\s+)?(.+?)\\s+(?:says|shows|sends|posts|notifies(?: me)?(?: about)?)\\s+(.+?)\\W*$", I)
    private val stopRe = Regex("^\\s*stop (?:watching|monitoring)\\s+(?:my\\s+)?(.+?)(?:\\s+app)?\\W*$", I)
    private val listWatchRe = Regex("\\b(?:list (?:my )?watch(?:ers| rules)|what are you (?:watching|monitoring))\\b", I)
    private val logRe = Regex("\\b(?:event log|activity log|what (?:have|did) you (?:done|do)(?: today)?)\\b", I)
    private val happenedRe = Regex("^\\s*what happened (?:with|on) (?:my\\s+)?(.+?)\\W*$", I)
    private val healthRe = Regex("\\b(?:connection health|health check|are my (?:apps|connections) (?:ok|okay|working))\\b", I)
    private val listConnRe = Regex("\\b(?:list|show) (?:my )?connectors\\b|what apps can you control", I)
    private val dblTapRe = Regex("^\\s*double[- ]?tap\\s+(?:on\\s+)?(.+?)\\W*$", I)
    private val pinchRe = Regex("^\\s*pinch\\s+(in|out)\\W*$", I)
    private val calAddRe = Regex("^\\s*(?:add|put|schedule|create)\\s+(.+?)\\s+(?:to|on|in)\\s+my\\s+calendar\\s+(today|tomorrow)\\s+at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\W*$", I)

    private fun one(name: String, o: JSONObject) = LocalPlan("", listOf(name to o))

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        if (t.isEmpty()) return null

        if (Guard.pending != null) {
            if (approveRe.containsMatchIn(t)) return one("connect", JSONObject().put("do", "approve").put("_u", Guard.mintUserToken()))
            if (cancelRe.containsMatchIn(t)) return one("connect", JSONObject().put("do", "cancel").put("_u", Guard.mintUserToken()))
        }
        useFor.find(t)?.let { m ->
            return one("connect", JSONObject().put("do", "prefer").put("app", m.groupValues[1]).put("category", m.groupValues[2]))
        }
        if (listConnRe.containsMatchIn(t)) return one("connect", JSONObject().put("do", "list"))
        if (healthRe.containsMatchIn(t)) return one("watch", JSONObject().put("do", "health"))
        if (listWatchRe.containsMatchIn(t)) return one("watch", JSONObject().put("do", "list"))
        if (logRe.containsMatchIn(t)) return one("watch", JSONObject().put("do", "log"))
        happenedRe.find(t)?.let { m -> return one("watch", JSONObject().put("do", "log").put("app", m.groupValues[1])) }
        stopRe.find(t)?.let { m -> return one("watch", JSONObject().put("do", "remove").put("app", m.groupValues[1])) }
        val orAnd = Regex("(?i)\\s+(?:or|and)\\s+")
        monitorRe.find(t)?.let { m ->
            val app = m.groupValues[1].ifBlank { m.groupValues[2] }
            return one("watch", JSONObject().put("do", "add").put("app", app).put("contains", m.groupValues[3].replace(orAnd, "|")).put("once", true))
        }
        tellMeRe.find(t)?.let { m ->
            return one("watch", JSONObject().put("do", "add").put("app", m.groupValues[1]).put("contains", m.groupValues[2].replace(orAnd, "|")).put("once", true))
        }
        dblTapRe.find(t)?.let { m -> return one("gesture", JSONObject().put("type", "double_tap").put("label", m.groupValues[1])) }
        pinchRe.find(t)?.let { m -> return one("gesture", JSONObject().put("type", "pinch_" + m.groupValues[1].lowercase())) }
        calAddRe.find(t)?.let { m ->
            val ampm = m.groupValues[5].lowercase()
            var hour = m.groupValues[3].toIntOrNull() ?: return null
            val minute = m.groupValues[4].ifBlank { "0" }.toIntOrNull() ?: return null
            if (ampm.isEmpty() && hour <= 12) return null          // “at 3” is ambiguous: let the AI ask
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (hour > 23 || minute > 59) return null
            val day = Calendar.getInstance()
            if (m.groupValues[2].equals("tomorrow", true)) day.add(Calendar.DAY_OF_YEAR, 1)
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)
            return one("calendar_edit", JSONObject().put("do", "add").put("title", m.groupValues[1])
                .put("date", date).put("time", String.format(Locale.US, "%02d:%02d", hour, minute)))
        }
        return null
    }
}
