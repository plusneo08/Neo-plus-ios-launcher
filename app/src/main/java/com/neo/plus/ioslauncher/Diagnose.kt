package com.neo.plus.ioslauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * DIAGNOSE: finds out why one of YOUR websites (or an app's back-end address) is down, slow or broken, and watches it.
 *
 *   check     one full look: this phone's internet, DNS, TLS certificate, HTTP status, redirects, response time, error pages,
 *             broken scripts / stylesheets, plain-http resources on an https page, and what changed since the last good look
 *   add       watch it every few minutes (2 min at the fastest, 8 sites at most); tells you when it goes down, comes back,
 *             stays slow, or its certificate is about to expire (down is only reported after 2 failed checks in a row, and never
 *             while this phone itself is offline)
 *   remove / list / history / baseline
 *
 * What it does NOT do: it sends plain GET / HEAD requests only (one page plus at most 8 of its own scripts / stylesheets),
 * never logs in, never posts, never scans ports, never changes anything. It cannot see errors that happen inside the page's
 * JavaScript (a blank React / Next.js page with a 200 status looks healthy except for the checks above), or anything behind a login.
 * Only add sites you own or run. Alerts go through Proactive.tell, so quiet hours, sleep mode and calls are respected.
 */
object Diagnose {
    private const val PREF = "neo_diagnose"
    private const val UA = "NeoPlus-SiteCheck/1.0 (owner-run check)"
    private const val MAX_SITES = 8
    private const val NO_CERT = Int.MIN_VALUE
    private const val HIST = 100

    private val busy = AtomicBoolean(false)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun load(c: Context): JSONArray = try { JSONArray(sp(c).getString("sites", "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { sp(c).edit().putString("sites", a.toString()).apply() }

    /* ------------------------------------------------------------------ small helpers */

    private fun norm(s: String): String {
        var u = s.trim().trim('"', '\'', '<', '>', ',', ';', ' ')
        if (u.isEmpty()) return ""
        if (!u.contains("://")) u = "https://$u"
        return u
    }

    private fun hostOf(u: String): String = try {
        val url = URL(u)
        if (url.protocol != "http" && url.protocol != "https") "" else url.host.lowercase(Locale.US)
    } catch (e: Exception) { "" }

    private fun looksLikeHost(h: String) = h.isNotEmpty() && (h.contains('.') || h == "localhost")

    private fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Throwable) { true }

    /** Page text is someone else's words: one short line, no control characters, no [[action]] markers. */
    private fun clean(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        return Guard.clean(t.filter { it >= ' ' }).take(80)
    }

    private fun secs(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f s".format(Locale.US, ms / 1000.0)

    private fun shortPath(u: String): String = try {
        val p = URL(u).path.ifEmpty { "/" }
        if (p.length > 38) "…" + p.takeLast(37) else p
    } catch (e: Exception) { u.take(38) }

    /** Network calls must not run on the main thread: run there-called work on a helper thread and wait for it. */
    private fun offMain(block: () -> String): String {
        if (Looper.myLooper() != Looper.getMainLooper()) return block()
        val out = AtomicReference<String?>(null)
        val t = Thread { out.set(try { block() } catch (e: Throwable) { "The check failed: " + (e.message ?: e.javaClass.simpleName) }) }
        t.start()
        try { t.join(60_000) } catch (e: InterruptedException) { }
        return out.get() ?: "The check is still running. Ask again in a moment."
    }

    /* ------------------------------------------------------------------ one probe */

    private class Probe(val url: String, val host: String) {
        var online = true
        var dnsMs = -1L
        var dnsErr = false
        var addrs = 0
        var certDays = NO_CERT
        var code = -1
        var ttfbMs = -1L
        var totalMs = -1L
        var netErr = ""
        var netMsg = ""
        val hops = ArrayList<String>()
        var loop = false
        var finalUrl = ""
        var server = ""
        var type = ""
        var title = ""
        var bytes = 0
        var errPhrase = ""
        var mixed = 0
        var assetsChecked = 0
        val badAssets = ArrayList<String>()
    }

    private val errPhrases = listOf(
        "internal server error", "bad gateway", "service unavailable", "gateway time-out", "gateway timeout",
        "application error", "error establishing a database connection", "fatal error", "unhandled runtime error",
        "cannot get /", "traceback (most recent call last)", "account suspended", "this domain has expired",
        "default web site page", "welcome to nginx", "apache2 ubuntu default page", "index of /", "site can't be reached",
        "too many requests", "access denied"
    )

    private fun classify(e: Throwable): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val m = (e.message ?: "").lowercase(Locale.US)
        val rm = (root.message ?: "").lowercase(Locale.US)
        return when {
            e is SocketTimeoutException || m.contains("timed out") -> "timeout"
            m.contains("cleartext") -> "cleartext"
            m.contains("expired") || rm.contains("expired") -> "tls_expired"
            m.contains("not verified") || m.contains("hostname") || m.contains("subject alternative") -> "tls_host"
            m.contains("trust anchor") || rm.contains("trust anchor") || m.contains("certpath") -> "tls_trust"
            e is SSLException -> "tls_other"
            e is ConnectException || m.contains("refused") -> "refused"
            m.contains("reset") || m.contains("broken pipe") || m.contains("unexpected end of stream") -> "reset"
            m.contains("unreachable") -> "unreachable"
            else -> "other"
        }
    }

    private fun probe(c: Context, url: String, deep: Boolean): Probe {
        val host = hostOf(url)
        val p = Probe(url, host)
        val deadline = System.currentTimeMillis() + 40_000L
        if (!online(c)) { p.online = false; return p }

        val d0 = System.nanoTime()
        try {
            p.addrs = InetAddress.getAllByName(host).size
            p.dnsMs = (System.nanoTime() - d0) / 1_000_000
        } catch (e: UnknownHostException) {
            p.dnsErr = true
            return p
        }

        var cur = url
        var body = ""
        val seen = HashSet<String>()
        seen.add(cur)
        val t0 = System.nanoTime()
        try {
            var hop = 0
            while (true) {
                val conn = URL(cur).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                val code = conn.responseCode
                if (p.ttfbMs < 0) p.ttfbMs = (System.nanoTime() - t0) / 1_000_000
                if (conn is HttpsURLConnection && p.certDays == NO_CERT) {
                    try {
                        val x = conn.serverCertificates.firstOrNull() as? X509Certificate
                        if (x != null) p.certDays = ((x.notAfter.time - System.currentTimeMillis()) / 86_400_000L).toInt()
                    } catch (e: Exception) { }
                }
                if (code in 301..308 && code != 304) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    p.code = code
                    if (loc.isNullOrBlank()) break
                    val next = URL(URL(cur), loc).toString()
                    p.hops.add("$code to $next")
                    hop++
                    if (!seen.add(next) || hop > 6) { p.loop = true; break }
                    cur = next
                    continue
                }
                p.code = code
                p.server = (conn.getHeaderField("Server") ?: "").take(40)
                p.type = (conn.contentType ?: "").lowercase(Locale.US)
                val stream = try { if (code >= 400) conn.errorStream else conn.inputStream } catch (e: Exception) { null }
                if (stream != null) {
                    val buf = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    stream.use { s ->
                        while (out.size() < 200_000) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    p.bytes = out.size()
                    body = out.toString("UTF-8")
                }
                conn.disconnect()
                break
            }
            p.totalMs = (System.nanoTime() - t0) / 1_000_000
            p.finalUrl = cur
        } catch (e: Exception) {
            p.netErr = classify(e)
            p.netMsg = (e.message ?: e.javaClass.simpleName).take(80)
            p.totalMs = (System.nanoTime() - t0) / 1_000_000
            return p
        }

        if (p.code > 0) analyse(p, body, deep, deadline)
        return p
    }

    private fun analyse(p: Probe, body: String, deep: Boolean, deadline: Long) {
        val tm = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(body)
        p.title = clean(tm?.groupValues?.get(1) ?: "")
        val low = body.lowercase(Locale.US)
        if (p.code >= 400 || body.length < 30_000) p.errPhrase = errPhrases.firstOrNull { low.contains(it) } ?: ""
        if (p.finalUrl.startsWith("https://", ignoreCase = true)) {
            p.mixed = Regex("""\bsrc\s*=\s*["']http://""", RegexOption.IGNORE_CASE).findAll(body).count()
        }
        if (!deep || p.code !in 200..299 || p.finalUrl.isEmpty()) return

        val base = try { URL(p.finalUrl) } catch (e: Exception) { return }
        val found = LinkedHashSet<String>()
        Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach { found.add(it.groupValues[1]) }
        Regex("""<link[^>]*>""", RegexOption.IGNORE_CASE).findAll(body).forEach { tag ->
            val t = tag.value
            if (t.contains("stylesheet", ignoreCase = true) || t.contains("modulepreload", ignoreCase = true)) {
                Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(t)?.let { found.add(it.groupValues[1]) }
            }
        }
        val same = ArrayList<String>()
        for (ref in found) {
            if (same.size >= 8) break
            val abs = try { URL(base, ref.trim()) } catch (e: Exception) { continue }
            if (abs.protocol != "http" && abs.protocol != "https") continue
            if (!abs.host.equals(base.host, ignoreCase = true)) continue
            same.add(abs.toString())
        }
        for (a in same) {
            if (System.currentTimeMillis() > deadline) break
            p.assetsChecked++
            try {
                val cn = URL(a).openConnection() as HttpURLConnection
                cn.requestMethod = "HEAD"
                cn.connectTimeout = 6_000
                cn.readTimeout = 6_000
                cn.setRequestProperty("User-Agent", UA)
                val ac = cn.responseCode
                cn.disconnect()
                if (ac >= 400 && ac != 405 && ac != 501) p.badAssets.add(shortPath(a) + " (" + ac + ")")
            } catch (e: Exception) {
                p.badAssets.add(shortPath(a) + " (no answer)")
            }
        }
    }

    private fun isUp(p: Probe): Boolean =
        p.online && !p.dnsErr && p.netErr.isEmpty() && !p.loop && (p.code in 200..399 || p.code == 401 || p.code == 403)

    /* ------------------------------------------------------------------ what it means */

    private class F(val sev: Int, val what: String, val why: String)

    private fun netFinding(p: Probe): F = when (p.netErr) {
        "timeout" -> F(3, "${p.host} did not answer in time",
            "The server is not responding: it may be overloaded or hung, the app process may have stopped, or a firewall is dropping the connection. Check the server or hosting dashboard.")
        "refused" -> F(3, "${p.host} refused the connection",
            "The machine is reachable but nothing is listening on the web port: the web server or app process has stopped, or the port is closed in the firewall. Restart it and read its log.")
        "reset" -> F(3, "The connection to ${p.host} was cut off",
            "The server accepted the connection and then dropped it: a crash while answering, a proxy or CDN problem, or a firewall rule. Check the server log for the same moment.")
        "tls_expired" -> F(3, "The security certificate for ${p.host} has expired",
            "Browsers will warn visitors. Renew the certificate; if you use Let’s Encrypt or a host-managed one, check that automatic renewal is actually running.")
        "tls_host" -> F(3, "The certificate does not match ${p.host}",
            "The certificate was issued for another name, or DNS points this name at the wrong server. Add the name to the certificate, or correct the DNS record.")
        "tls_trust" -> F(3, "The certificate for ${p.host} is not trusted",
            "It is self-signed or the intermediate certificate is missing. Install the full chain from your certificate provider.")
        "tls_other" -> F(3, "The secure connection to ${p.host} failed",
            "The TLS handshake did not complete: unsupported protocol settings, a broken proxy or CDN setting, or a half-installed certificate. Detail: ${p.netMsg}")
        "cleartext" -> F(2, "This app blocks plain http addresses",
            "Android refuses http:// unless the app allows it. Use https:// for the check, or enable cleartext traffic for this host in the app’s network config.")
        "unreachable" -> F(3, "${p.host} cannot be reached from this network",
            "There is no route to the server from here. Try on mobile data instead of Wi-Fi (or the reverse) to tell a network problem from a server problem.")
        else -> F(3, "Could not connect to ${p.host}", "Detail: ${p.netMsg}")
    }

    private fun httpFinding(p: Probe): F? = when {
        p.loop -> F(3, "Redirect loop", "The site keeps redirecting in a circle. Check www / non-www and http / https redirect rules, and any CDN or proxy SSL mode that redirects back to itself.")
        p.code == 500 -> F(3, "The server returned an internal error (500)", "The app crashed while building this page. Read the server error log for that moment, and check the most recent deploy or config change.")
        p.code == 502 || p.code == 504 -> F(3, "Gateway error (${p.code})", "The front server (proxy / CDN) is up but cannot get an answer from your app. The app process is probably stopped, restarting or too slow. Restart it and check its log.")
        p.code == 503 -> F(3, "Service unavailable (503)", "The site is overloaded, in maintenance mode, or the host has paused it (a quota or billing limit is common on free plans). Check the hosting dashboard.")
        p.code >= 500 -> F(3, "Server error (${p.code})", "Check the server log for that moment and the latest deploy.")
        p.code == 404 -> F(3, "The page was not found (404)", "The address is wrong, or the deploy did not publish this page. Check the build output and the hosting root / base path.")
        p.code == 429 -> F(2, "The site is rate-limiting requests (429)", "Not necessarily a fault. A limit this low may also block real visitors, so check your rate-limit settings.")
        p.code == 401 -> F(1, "The page asks for a login (401)", "Expected if the site is private. I cannot look behind a login.")
        p.code == 403 -> F(2, "The server refused the request (403)", "A firewall (WAF), bot rule or permission setting is blocking this request, or the hosting account is suspended. Visitors may be blocked too, so test in a normal browser.")
        p.code in 400..499 -> F(3, "The server rejected the request (${p.code})", "Check the address and the server’s access rules.")
        else -> null
    }

    private fun findings(p: Probe, site: JSONObject?): List<F> {
        val out = ArrayList<F>()
        if (!p.online) {
            out.add(F(3, "This phone has no internet connection", "I cannot tell anything about ${p.host} until the phone is online."))
            return out
        }
        if (p.dnsErr) {
            out.add(F(3, "The name ${p.host} does not resolve",
                "DNS cannot find it. Check the domain has not expired, the A / CNAME record exists at your DNS host, and the nameservers are the right ones. New or changed records can take a while to spread."))
            return out
        }
        if (p.netErr.isNotEmpty()) {
            out.add(netFinding(p))
            return out
        }
        httpFinding(p)?.let { out.add(it) }

        if (p.badAssets.isNotEmpty()) {
            out.add(F(3, "${p.badAssets.size} of ${p.assetsChecked} scripts or stylesheets failed: " + p.badAssets.take(3).joinToString(", "),
                "A missing script or stylesheet is the most common cause of a blank or unstyled page. Check that the deploy uploaded those files and that the asset paths (base URL / asset prefix) are right."))
        }
        if (p.errPhrase.isNotEmpty() && p.code in 200..399) {
            val why = if (p.errPhrase.startsWith("welcome to nginx") || p.errPhrase.contains("default")) "The server is showing its default page: your site is not deployed there, or the server config points at the wrong folder."
            else "The page text itself looks like an error page. Check the server log and the latest deploy."
            out.add(F(if (p.code in 200..299) 2 else 3, "The page contains “${p.errPhrase}”", why))
        }
        if (p.code in 200..299 && p.errPhrase.isEmpty() && p.bytes < 300 && (p.type.isEmpty() || p.type.contains("html"))) {
            out.add(F(2, "The page is almost empty (${p.bytes} bytes)", "A 200 answer with no content gives a blank page: the build output is empty, the app failed to start, or a cache is serving an empty copy."))
        }
        if (p.ttfbMs > 2500) {
            out.add(F(2, "Slow first response (${secs(p.ttfbMs)})",
                "The server took a long time before sending anything: a slow database or API call, a cold start on serverless or free hosting, or an overloaded host."))
        } else if (p.totalMs > 6000) {
            out.add(F(1, "The page took ${secs(p.totalMs)} to download", "The server answers quickly but the page is heavy or the connection is slow. Compress or trim what it sends."))
        }
        if (p.mixed > 0) {
            out.add(F(2, "${p.mixed} resource(s) load over plain http on an https page", "Browsers block these, so images or scripts may be missing. Change those addresses to https."))
        }
        if (p.certDays != NO_CERT && p.certDays <= 14) {
            out.add(F(if (p.certDays <= 3) 3 else 2, "The certificate expires in ${maxOf(p.certDays, 0)} day(s)", "Renew it now. With automatic renewal, find out why it has not renewed yet."))
        }
        if (p.hops.size > 3) {
            out.add(F(1, "${p.hops.size} redirects before the page", "Each redirect adds delay. Point the address straight at the final one."))
        }
        if (site != null && isUp(p)) {
            val bt = site.optString("baseTitle")
            val bl = site.optInt("baseLen", 0)
            if (bt.isNotEmpty() && p.title.isNotEmpty() && bt != p.title) {
                out.add(F(2, "The page title changed from “$bt” to “${p.title}”",
                    "Fine if you changed it. If not, a different page is being served: the wrong deploy, the host’s default page, or a parked or hijacked domain."))
            }
            if (bl in 2000..150_000 && p.bytes in 1 until (bl * 4 / 10)) {
                out.add(F(2, "The page is much smaller than usual (${p.bytes} bytes, was about $bl)", "Part of the page is missing: an include or data call failed, or a different version was deployed."))
            }
        }
        return out
    }

    private fun reason(p: Probe): String = findings(p, null).firstOrNull { it.sev == 3 }?.what
        ?: (if (p.code > 0) "HTTP ${p.code}" else "no answer")

    private fun report(p: Probe, site: JSONObject?): String {
        val fs = findings(p, site).sortedByDescending { it.sev }
        val sb = StringBuilder()
        sb.append(when {
            fs.any { it.sev == 3 } -> "${p.host} has a problem: " + fs.first().what
            fs.any { it.sev == 2 } -> "${p.host} is up, with warnings"
            else -> "${p.host} looks healthy"
        })
        for (f in fs.take(4)) sb.append("\n• ").append(f.what).append("\n  → ").append(f.why)
        val facts = ArrayList<String>()
        if (p.dnsMs >= 0) facts.add("DNS ${p.dnsMs} ms")
        if (p.ttfbMs >= 0) facts.add("first byte ${secs(p.ttfbMs)}")
        if (p.totalMs >= 0 && p.code > 0) facts.add("total ${secs(p.totalMs)}")
        if (p.code > 0) facts.add("HTTP ${p.code}")
        if (p.certDays != NO_CERT) facts.add("certificate ${p.certDays} days left")
        if (p.assetsChecked > 0) facts.add("${p.assetsChecked - p.badAssets.size}/${p.assetsChecked} scripts and styles OK")
        if (p.server.isNotEmpty()) facts.add("server ${clean(p.server)}")
        if (p.title.isNotEmpty()) facts.add("title “${p.title}”")
        if (facts.isNotEmpty()) sb.append("\n\n").append(facts.joinToString(" · "))
        if (site == null && isUp(p)) sb.append("\nSay “monitor ${p.host}” to watch it and be told about changes.")
        return sb.toString()
    }

    /* ------------------------------------------------------------------ commands */

    fun handle(c: Context, a: JSONObject): String = offMain {
        try { exec(c, a) } catch (e: Throwable) { "Couldn’t run that check: " + (e.message ?: e.javaClass.simpleName) }
    }

    private fun exec(c: Context, a: JSONObject): String {
        val d = a.optString("do").ifBlank { "check" }
        val raw = a.optString("url").ifBlank { a.optString("site") }.ifBlank { a.optString("name") }
        return when (d) {
            "list" -> list(c)
            "history" -> history(c, raw)
            "remove" -> remove(c, raw)
            "add" -> add(c, a, raw)
            "baseline" -> baseline(c, raw)
            else -> check(c, a, raw)
        }
    }

    private fun find(arr: JSONArray, q: String): Int {
        val name = q.trim().lowercase(Locale.US)
        val h = hostOf(norm(q))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if ((h.isNotEmpty() && o.optString("host") == h) || o.optString("name").lowercase(Locale.US) == name) return i
        }
        return -1
    }

    /** A saved site (by address or name) or a new address. */
    private fun resolve(c: Context, raw: String): Pair<String, JSONObject?>? {
        if (raw.isBlank()) return null
        val arr = load(c)
        val i = find(arr, raw)
        if (i >= 0) { val o = arr.getJSONObject(i); return Pair(o.optString("url"), o) }
        val u = norm(raw)
        return if (looksLikeHost(hostOf(u))) Pair(u, null) else null
    }

    private fun check(c: Context, a: JSONObject, raw: String): String {
        val (url, site) = resolve(c, raw) ?: return "Tell me which address to check, for example “check example.com”."
        if (site == null && Guard.tainted() && !Guard.ok(a)) {
            return Guard.hold("diagnose", a, "check ${hostOf(url)} (the address came from text I just read, not from you)")
        }
        return report(probe(c, url, true), site)
    }

    private fun add(c: Context, a: JSONObject, raw: String): String {
        val url = norm(raw)
        val host = hostOf(url)
        if (!looksLikeHost(host)) return "Tell me the address to watch, for example “monitor my site example.com”."
        if (Guard.tainted() && !Guard.ok(a)) {
            return Guard.hold("diagnose", a, "start watching $host (the address came from text I just read, not from you)")
        }
        val every = a.optInt("every", 5).coerceIn(2, 60)
        val name = a.optString("name").ifBlank { host }
        val p = probe(c, url, false)
        val up = isUp(p)
        synchronized(this) {
            val arr = load(c)
            val i = find(arr, host)
            if (i < 0 && arr.length() >= MAX_SITES) return "I can watch up to $MAX_SITES sites. Remove one first."
            val o = if (i >= 0) arr.getJSONObject(i) else JSONObject().also { arr.put(it) }
            o.put("host", host).put("url", url).put("name", name).put("every", every)
            o.put("last", System.currentTimeMillis()).put("up", up || !p.online).put("fails", 0).put("slow", 0)
            if (up) o.put("baseTitle", p.title).put("baseLen", p.bytes)
            save(c, arr)
        }
        val first = when {
            up -> "$name is up (HTTP ${p.code}, first byte ${secs(p.ttfbMs)})."
            !p.online -> "This phone is offline, so I could not do the first check."
            else -> "Right now it looks down: " + reason(p) + "."
        }
        return "Watching $name every $every min. $first I’ll tell you when it goes down, comes back, stays slow or its certificate is close to expiring. Only add sites you own or run."
    }

    private fun remove(c: Context, raw: String): String {
        if (raw.isBlank()) return "Which site should I stop watching?"
        synchronized(this) {
            val arr = load(c)
            val i = find(arr, raw)
            if (i < 0) return "I am not watching “$raw”."
            val n = arr.getJSONObject(i).optString("name")
            arr.remove(i)
            save(c, arr)
            return "Stopped watching $n."
        }
    }

    private fun baseline(c: Context, raw: String): String {
        val (url, site) = resolve(c, raw) ?: return "Which site? Say “monitor ${raw.ifBlank { "example.com" }}” first."
        if (site == null) return "I am not watching that site yet. Say “monitor $raw” first."
        val p = probe(c, url, false)
        if (!isUp(p)) return "It does not look healthy right now (" + reason(p) + "), so I did not save this as the normal page."
        synchronized(this) {
            val arr = load(c)
            val i = find(arr, hostOf(url))
            if (i >= 0) { arr.getJSONObject(i).put("baseTitle", p.title).put("baseLen", p.bytes); save(c, arr) }
        }
        return "Saved how ${p.host} looks now (title “${p.title}”, ${p.bytes} bytes) as the normal page. I’ll flag big changes."
    }

    private fun stats(o: JSONObject): String {
        val h = o.optJSONArray("hist") ?: return "no checks yet"
        if (h.length() == 0) return "no checks yet"
        var ok = 0
        var sum = 0L
        var n = 0
        var worst = 0L
        for (i in 0 until h.length()) {
            val e = h.optJSONArray(i) ?: continue
            if (e.optInt(1) == 1) { ok++; val ms = e.optLong(2); sum += ms; n++; if (ms > worst) worst = ms }
        }
        val pct = ok * 100 / h.length()
        return "$pct% up over the last ${h.length()} checks" + (if (n > 0) ", average ${secs(sum / n)}, slowest ${secs(worst)}" else "")
    }

    private fun list(c: Context): String {
        val arr = load(c)
        if (arr.length() == 0) return "I am not watching any site. Say “monitor my site example.com”."
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            val state = if (o.optBoolean("up", true)) "up" else "DOWN since " + tf.format(Date(o.optLong("downSince")))
            sb.append("• ").append(o.optString("name")).append(": ").append(state).append(" · ").append(stats(o))
        }
        return sb.toString()
    }

    private fun history(c: Context, raw: String): String {
        if (raw.isBlank()) return list(c)
        val arr = load(c)
        val i = find(arr, raw)
        if (i < 0) return "I am not watching “$raw”."
        val o = arr.getJSONObject(i)
        val h = o.optJSONArray("hist") ?: JSONArray()
        val tf = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val bad = ArrayList<String>()
        for (k in h.length() - 1 downTo 0) {
            val e = h.optJSONArray(k) ?: continue
            if (e.optInt(1) != 1) bad.add(tf.format(Date(e.optLong(0))))
            if (bad.size >= 5) break
        }
        return o.optString("name") + ": " + stats(o) + (if (bad.isEmpty()) ". No failed checks recorded." else ". Latest failed checks: " + bad.joinToString(", ") + ".")
    }

    /* ------------------------------------------------------------------ watching (called every minute from Proactive) */

    fun tick(c: Context) {
        val app = c.applicationContext
        val arr = load(app)
        if (arr.length() == 0) return
        val now = System.currentTimeMillis()
        var due = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (now - o.optLong("last") >= o.optInt("every", 5) * 60_000L) due = true
        }
        if (!due || !busy.compareAndSet(false, true)) return
        Thread {
            try { runDue(app) } catch (e: Throwable) { } finally { busy.set(false) }
        }.start()
    }

    private fun runDue(c: Context) {
        val hosts = ArrayList<String>()
        val first = load(c)
        val now0 = System.currentTimeMillis()
        for (i in 0 until first.length()) {
            val o = first.optJSONObject(i) ?: continue
            if (now0 - o.optLong("last") >= o.optInt("every", 5) * 60_000L) hosts.add(o.optString("host"))
        }
        for (host in hosts) {
            val site = synchronized(this) { val a = load(c); val i = find(a, host); if (i >= 0) a.getJSONObject(i) else null } ?: continue
            val p = probe(c, site.optString("url"), false)
            val now = System.currentTimeMillis()
            val alerts = ArrayList<Pair<String, Boolean>>()
            synchronized(this) {
                val arr = load(c)
                val i = find(arr, host)
                if (i < 0) return@synchronized
                val o = arr.getJSONObject(i)
                o.put("last", now)
                val name = o.optString("name")
                if (!p.online) { save(c, arr); return@synchronized }      // this phone is offline: nothing is learned about the site
                val up = isUp(p)
                val hist = o.optJSONArray("hist") ?: JSONArray()
                hist.put(JSONArray().put(now).put(if (up) 1 else 0).put(maxOf(p.ttfbMs, 0L)))
                val keep = JSONArray()
                for (k in maxOf(0, hist.length() - HIST) until hist.length()) keep.put(hist.get(k))
                o.put("hist", keep)
                if (up) {
                    if (!o.optBoolean("up", true)) {
                        val mins = (now - o.optLong("downSince")) / 60_000L
                        alerts.add(Pair("$name is back after $mins minute(s)", false))
                        o.put("up", true)
                    }
                    o.put("fails", 0)
                    val slow = if (p.ttfbMs > 3000) o.optInt("slow", 0) + 1 else 0
                    o.put("slow", slow)
                    if (slow >= 3 && now - o.optLong("slowAlert") > 6 * 3_600_000L) {
                        o.put("slowAlert", now)
                        alerts.add(Pair("$name has been slow for a while: ${secs(p.ttfbMs)} before it starts to answer", false))
                    }
                    if (p.certDays != NO_CERT && p.certDays <= 14 && now - o.optLong("certAlert") > 24 * 3_600_000L) {
                        o.put("certAlert", now)
                        alerts.add(Pair("The certificate for $name expires in ${maxOf(p.certDays, 0)} day(s)", false))
                    }
                } else {
                    val fails = o.optInt("fails", 0) + 1
                    o.put("fails", fails)
                    if (fails >= 2 && o.optBoolean("up", true)) {
                        o.put("up", false).put("downSince", now)
                        alerts.add(Pair("$name looks down: " + reason(p) + ". Ask “what’s wrong with ${p.host}” for the full diagnosis", true))
                    }
                }
                save(c, arr)
            }
            for ((text, urgent) in alerts) {
                try { Proactive.tell(c, Proactive.line(c, text), urgent = urgent) } catch (e: Throwable) { }
            }
        }
    }
}

/**
 * Typed / spoken commands. A web address must be in the sentence, so this never takes over other commands.
 *   "what's wrong with example.com" / "diagnose example.com" / "is example.com down" / "check example.com"
 *   "monitor my site example.com" / "stop monitoring example.com" / "uptime of example.com" / "list my sites"
 */
object DiagnoseCmd {
    private val I = RegexOption.IGNORE_CASE
    private val domain = Regex("""\b((?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:/[^\s]*)?)""", I)
    private val listRe = Regex("""\b(?:list|show|which)\b.*\b(?:sites|websites)\b|\bsite status\b""", I)
    private val removeRe = Regex("""\b(?:stop|remove|unmonitor|cancel)\b.*\b(?:monitor|monitoring|watching|tracking|site|website)""", I)
    private val addRe = Regex("""\b(?:monitor|keep an eye on)\b""", I)
    private val historyRe = Regex("""\b(?:uptime|downtime|how often)\b""", I)
    private val checkRe = Regex("""\b(?:diagnose|diagnosis|what'?s wrong with|what is wrong with|why is|is .{1,60} (?:down|up|working|slow)|not working|check)\b""", I)

    fun plan(text: String): LocalPlan? {
        val t = text.trim()
        val m = domain.find(t)
        if (m == null) {
            return if (listRe.containsMatchIn(t)) LocalPlan("", listOf("diagnose" to JSONObject().put("do", "list"))) else null
        }
        val url = m.value.trimEnd('.', ',', '?', '!')
        val d = when {
            removeRe.containsMatchIn(t) -> "remove"
            addRe.containsMatchIn(t) -> "add"
            historyRe.containsMatchIn(t) -> "history"
            checkRe.containsMatchIn(t) -> "check"
            else -> return null
        }
        return LocalPlan("", listOf("diagnose" to JSONObject().put("do", d).put("url", url)))
    }
}
