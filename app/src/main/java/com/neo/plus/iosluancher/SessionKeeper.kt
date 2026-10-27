package com.neo.plus.iosluancher

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONArray

/**
 * Keeps website logins and "this browser is trusted" marks alive between tasks, so a site that already trusts this phone does not
 * ask for a CAPTCHA or a code again on the second login.
 *
 * Android's WebView forgets "session" cookies (the ones a site sets without an expiry, which many logins use) when the app
 * is closed or the phone restarts. After every website task the cookies of the site are saved, encrypted with the same
 * Keystore key as the website logins; before the next task they are put back when the browser no longer has them.
 * The site itself still decides when a login really expires: a login it has ended is simply asked for again.
 *
 * What is saved (v2):
 *  - EVERY host the task visited, not only the bare domain. A site authorised as "example.com" usually lives on www.example.com or
 *    signs in on login. / accounts.example.com, and a cookie set only for such a host cannot be seen from "https://example.com".
 *  - Cookies that showed the same value on two hosts of the site are put back as domain cookies (Domain=example.com), the rest as
 *    cookies of the host they came from.
 *  - Cookies are put back with SameSite=None (the browser's default for a cookie without it is Lax, which hides the trust cookies of
 *    CAPTCHA frames embedded in the site) and trust cookies are kept for 180 days instead of 30.
 */
object SessionKeeper {
    private const val PREF = "neo_session_keeper"
    private const val DAYS = 30
    private const val TRUST_DAYS = 180
    private const val MAX_BYTES = 60_000

    /** CAPTCHA providers decide how much to trust this browser from their own cookies; kept for every site (not removed with a site). */
    private val THIRD_PARTY = listOf(
        "__google_trust" to "https://www.google.com",
        "__recaptcha_trust" to "https://www.recaptcha.net",
        "__hcaptcha_trust" to "https://hcaptcha.com",
        "__turnstile_trust" to "https://challenges.cloudflare.com"
    )

    /** One saved cookie: the host it was seen on, "name=value", and whether it was set for the whole domain. */
    private class Ck(val host: String, val pair: String, val domain: Boolean) {
        val name: String get() = pair.substringBefore('=', "")
    }

    /** Pages the browser opened for each site in this run of the app (scheme + host + path), so their cookies can be read too. */
    private val visited = HashMap<String, LinkedHashSet<String>>()

    private fun urlFor(domain: String) = "https://" + domain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun inFamily(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    /** Call for every page the task's browser opens (start and finish) so that site's cookies on that host are kept. */
    fun noteVisit(domain: String, url: String?) {
        try {
            if (domain.isBlank() || url.isNullOrBlank()) return
            val u = Uri.parse(url)
            val scheme = u.scheme?.lowercase() ?: return
            if (scheme != "https" && scheme != "http") return
            val host = u.host?.lowercase() ?: return
            val path = (u.encodedPath ?: "").ifBlank { "/" }
            synchronized(visited) {
                val set = visited.getOrPut(domain) { LinkedHashSet() }
                set.add("https://$host$path")
                while (set.size > 30) set.remove(set.first())
            }
        } catch (e: Exception) { }
    }

    /** The addresses whose cookies are read for a site: the bare domain, www, and every page visited. */
    private fun siteUrls(domain: String): List<String> {
        val urls = LinkedHashSet<String>()
        urls.add(urlFor(domain))
        urls.add("https://www.$domain")
        synchronized(visited) { visited[domain]?.let { urls.addAll(it) } }
        return urls.toList()
    }

    /** Cookie counts the browser holds right now per host and for the CAPTCHA providers, for the task log. */
    fun snapshot(domain: String): String = try {
        val cm = CookieManager.getInstance()
        fun n(u: String) = cm.getCookie(u).orEmpty().split(';').count { it.contains('=') }
        val hosts = siteUrls(domain).map { Uri.parse(it).host.orEmpty().lowercase() }.filter { it.isNotEmpty() }.distinct()
        "site [" + hosts.joinToString(", ") { h -> "$h ${n("https://$h")}" } + "], google ${n("https://www.google.com")}, recaptcha.net ${n("https://www.recaptcha.net")}, hcaptcha ${n("https://hcaptcha.com")}"
    } catch (e: Exception) { "unknown" }

    private val LOGIN_LIKE = Regex("sess|auth|token|login|remember|user|account|sid|csrf|xsrf|jwt|member|uid", RegexOption.IGNORE_CASE)
    private val TRUST_LIKE = Regex("cf_clearance|__cf_bm|captcha|device|trust|fingerprint|visitor|bot|clearance|_ga\\b|did\\b", RegexOption.IGNORE_CASE)

    /** A cookie that says "this browser is trusted" (kept when you change accounts), as opposed to one that says "this is account X". */
    private fun isTrust(name: String) = TRUST_LIKE.containsMatchIn(name) && !LOGIN_LIKE.containsMatchIn(name)

    private fun siteKey(domain: String, user: String) = "$domain|${user.trim().lowercase()}"

    private fun encode(list: Collection<Ck>): String {
        val arr = JSONArray()
        for (c in list) arr.put(JSONArray().put(c.host).put(c.pair).put(c.domain))
        return "v2:" + arr.toString()
    }

    /** Reads a saved blob. The first version saved a plain "a=1; b=2" string for the bare domain; that still loads. */
    private fun decode(blob: String, defaultHost: String): List<Ck> {
        val out = ArrayList<Ck>()
        if (blob.startsWith("v2:")) {
            try {
                val arr = JSONArray(blob.substring(3))
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    out.add(Ck(a.getString(0), a.getString(1), a.optBoolean(2, false)))
                }
            } catch (e: Exception) { }
        } else {
            for (pair in blob.split(';')) {
                val p = pair.trim()
                if (p.contains('=')) out.add(Ck(defaultHost, p, false))
            }
        }
        return out
    }

    /** Reads the browser's cookies on every host of the site and marks the ones that were set for the whole domain. */
    private fun collect(cm: CookieManager, domain: String): List<Ck> {
        val byHost = LinkedHashMap<String, LinkedHashMap<String, String>>() // host -> name -> "name=value"
        for (u in siteUrls(domain)) {
            val host = Uri.parse(u).host?.lowercase() ?: continue
            val m = byHost.getOrPut(host) { LinkedHashMap() }
            for (pair in cm.getCookie(u).orEmpty().split(';')) {
                val p = pair.trim()
                if (p.contains('=')) m[p.substringBefore('=')] = p
            }
        }
        // the same name and value on two hosts of the site = a cookie set for the whole domain
        val seen = HashMap<String, Int>()
        for ((h, m) in byHost) {
            if (!inFamily(h, domain)) continue
            for (p in m.values) seen[p] = (seen[p] ?: 0) + 1
        }
        val out = ArrayList<Ck>()
        for ((h, m) in byHost) {
            for (p in m.values) out.add(Ck(h, p, inFamily(h, domain) && (seen[p] ?: 0) >= 2))
        }
        return out
    }

    /**
     * Saves the site's current cookies. Call right after you solve a CAPTCHA and again when the task ends. Cookies saved
     * earlier are kept when the site no longer sends them (a "trusted device" cookie must survive a logout), and the newest
     * value wins when both have the same name on the same host.
     */
    fun save(ctx: Context, domain: String, user: String) {
        try {
            if (domain.isNotBlank()) {
                val cm = CookieManager.getInstance()
                cm.flush()
                val all = collect(cm, domain)
                saveKey(ctx, siteKey(domain, user), all.filter { !isTrust(it.name) }, domain, domain)
                saveKey(ctx, "trust|$domain", all.filter { isTrust(it.name) }, domain, domain)
            }
        } catch (e: Exception) { }
        for ((k, u) in THIRD_PARTY) {
            try {
                val host = Uri.parse(u).host ?: continue
                val fresh = CookieManager.getInstance().getCookie(u).orEmpty().split(';').map { it.trim() }.filter { it.contains('=') }.map { Ck(host, it, false) }
                saveKey(ctx, k, fresh, "", host)
            } catch (e: Exception) { }
        }
    }

    private fun saveKey(ctx: Context, key: String, fresh: List<Ck>, ownDomain: String, defaultHost: String) {
        try {
            if (key.isBlank() || fresh.isEmpty()) return
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val old = sp.getString(key, null)?.let { SiteVault.unseal(it) }.orEmpty()
            val merged = LinkedHashMap<String, Ck>()
            for (c in decode(old, defaultHost)) if (c.name.isNotEmpty()) merged[c.host + "|" + c.name] = c
            for (c in fresh) if (c.name.isNotEmpty()) merged[c.host + "|" + c.name] = c
            var out = encode(merged.values)
            if (out.length > MAX_BYTES && ownDomain.isNotEmpty()) out = encode(merged.values.filter { inFamily(it.host, ownDomain) }) // too big: the site's own hosts only
            if (out.length > MAX_BYTES) out = encode(fresh) // still too big: keep what the site has now
            if (out.length > MAX_BYTES) return
            sp.edit().putString(key, SiteVault.seal(out)).apply()
        } catch (e: Exception) { }
    }

    /** Puts saved cookies back into the browser, leaving any it already has untouched. Call before the first page loads. */
    fun restore(ctx: Context, domain: String, user: String) {
        switchAccount(ctx, domain, user)
        restoreKey(ctx, "trust|$domain", domain, domain, false)
        restoreKey(ctx, siteKey(domain, user), domain, domain, false)
        for ((k, u) in THIRD_PARTY) restoreKey(ctx, k, "", Uri.parse(u).host.orEmpty(), true)
    }

    private fun restoreKey(ctx: Context, key: String, ownDomain: String, defaultHost: String, thirdParty: Boolean) {
        try {
            if (key.isBlank()) return
            val blob = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, null) ?: return
            val saved = SiteVault.unseal(blob)
            if (saved.isBlank()) return
            val cm = CookieManager.getInstance()
            val have = HashMap<String, Set<String>>() // host -> cookie names the browser already has there
            for (c in decode(saved, defaultHost)) {
                val name = c.name
                if (name.isEmpty() || c.host.isEmpty()) continue
                val url = "https://" + c.host
                val names = have.getOrPut(c.host) { cm.getCookie(url).orEmpty().split(';').map { it.trim().substringBefore('=') }.toSet() }
                if (name in names) continue
                val days = if (thirdParty || isTrust(name)) TRUST_DAYS else DAYS
                val dom = if (c.domain && ownDomain.isNotEmpty() && inFamily(c.host, ownDomain)) "; Domain=$ownDomain" else ""
                cm.setCookie(url, "${c.pair}; Max-Age=${days * 24 * 3600}; Path=/; Secure; SameSite=None$dom")
            }
            cm.flush()
        } catch (e: Exception) { }
    }

    /**
     * A different login for the same site must never inherit the previous account's session. When the saved username for a site
     * changes, the browser's cookies for that site (on every host it used) are expired first, so the task logs in fresh as the new
     * account (and gets the new account's own saved cookies back, if it has any).
     */
    private fun switchAccount(ctx: Context, domain: String, user: String) {
        try {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val k = "last|$domain"
            val me = user.trim().lowercase()
            val last = sp.getString(k, null)
            if (last != null && last != me) {
                val cm = CookieManager.getInstance()
                val hosts = LinkedHashSet<String>()
                hosts.add(domain)
                for (u in siteUrls(domain)) Uri.parse(u).host?.let { hosts.add(it.lowercase()) }
                for (key in listOf(siteKey(domain, last), "trust|$domain", siteKey(domain, me))) {
                    val blob = sp.getString(key, null)?.let { SiteVault.unseal(it) }.orEmpty()
                    for (c in decode(blob, domain)) hosts.add(c.host)
                }
                for (h in hosts) {
                    if (!inFamily(h, domain)) continue
                    val url = "https://$h"
                    for (pair in cm.getCookie(url).orEmpty().split(';')) {
                        val name = pair.trim().substringBefore('=', "")
                        if (name.isEmpty() || isTrust(name)) continue // device-trust cookies stay: they belong to the browser, not the account
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$domain")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$domain")
                    }
                }
                cm.flush()
            }
            sp.edit().putString(k, me).apply()
        } catch (e: Exception) { }
    }

    /** Forgets what was saved for one site (used when the site is removed from the vault). */
    fun forget(ctx: Context, domain: String) {
        try {
            synchronized(visited) { visited.remove(domain) }
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val e = sp.edit()
            for (k in sp.all.keys) if (k.startsWith("$domain|") || k == "last|$domain" || k == "trust|$domain") e.remove(k)
            e.apply()
        } catch (e: Exception) { }
    }
}
