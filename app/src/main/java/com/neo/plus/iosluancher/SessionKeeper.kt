package com.neo.plus.iosluancher

import android.content.Context
import android.webkit.CookieManager

/**
 * Keeps website logins alive between tasks, so a site that already trusts the session does not ask for a CAPTCHA again.
 *
 * Android's WebView forgets "session" cookies (the ones a site sets without an expiry, which many logins use) when the app
 * is closed or the phone restarts. After every website task the cookies of that site are saved, encrypted with the same
 * Keystore key as the website logins; before the next task they are put back (as 30-day cookies) when the browser no longer
 * has them. The site itself still decides when a login really expires: a login it has ended is simply asked for again.
 */
object SessionKeeper {
    private const val PREF = "neo_session_keeper"
    private const val DAYS = 30
    private const val MAX_BYTES = 20_000

    private fun urlFor(domain: String) = "https://" + domain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * Saves the site's current cookies. Call right after you solve a CAPTCHA and again when the task ends. Cookies saved
     * earlier are kept when the site no longer sends them (a "trusted device" cookie must survive a logout), and the newest
     * value wins when both have the same name.
     */
    fun save(ctx: Context, domain: String) {
        try {
            if (domain.isBlank()) return
            val cm = CookieManager.getInstance()
            cm.flush()
            val now = cm.getCookie(urlFor(domain)).orEmpty()
            if (now.isBlank()) return
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val old = sp.getString(domain, null)?.let { SiteVault.unseal(it) }.orEmpty()
            val merged = LinkedHashMap<String, String>()
            for (pair in old.split(';')) { val p = pair.trim(); if (p.contains('=')) merged[p.substringBefore('=')] = p }
            for (pair in now.split(';')) { val p = pair.trim(); if (p.contains('=')) merged[p.substringBefore('=')] = p }
            var out = merged.values.joinToString("; ")
            if (out.length > MAX_BYTES) out = now.trim() // too big to keep the old ones as well: keep what the site has now
            if (out.length > MAX_BYTES) return
            sp.edit().putString(domain, SiteVault.seal(out)).apply()
        } catch (e: Exception) { }
    }

    /** Puts saved cookies back into the browser, leaving any it already has untouched. Call before the first page loads. */
    fun restore(ctx: Context, domain: String) {
        try {
            if (domain.isBlank()) return
            val blob = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(domain, null) ?: return
            val saved = SiteVault.unseal(blob)
            if (saved.isBlank()) return
            val cm = CookieManager.getInstance()
            val url = urlFor(domain)
            val have = cm.getCookie(url).orEmpty().split(';').map { it.trim().substringBefore('=') }.toSet()
            val maxAge = DAYS * 24 * 3600
            for (pair in saved.split(';')) {
                val p = pair.trim()
                val name = p.substringBefore('=', "")
                if (name.isEmpty() || name in have) continue
                cm.setCookie(url, "$p; Max-Age=$maxAge; Path=/; Secure")
            }
            cm.flush()
        } catch (e: Exception) { }
    }

    /** Forgets what was saved for one site (used when the site is removed from the vault). */
    fun forget(ctx: Context, domain: String) {
        try { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(domain).apply() } catch (e: Exception) { }
    }
}
