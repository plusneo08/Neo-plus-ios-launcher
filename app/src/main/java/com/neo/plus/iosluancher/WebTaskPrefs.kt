package com.neo.plus.iosluancher

import android.content.Context

/**
 * How far the website assistant may go. Everything here can be loosened or tightened in the assistant settings.
 * Not a setting, on purpose: the CAPTCHA stop, page text being treated as untrusted data, and the saved login being typed
 * only on the site it belongs to.
 */
object WebTaskPrefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("web_task_prefs", Context.MODE_PRIVATE)

    /** strict = only authorised sites | list = also the chosen sites | any = follow links anywhere. */
    fun domainMode(ctx: Context): String = p(ctx).getString("domain_mode", "strict") ?: "strict"
    fun setDomainMode(ctx: Context, mode: String) {
        p(ctx).edit().putString("domain_mode", if (mode == "any" || mode == "list") mode else "strict").apply()
    }

    fun extraHostsRaw(ctx: Context): String = p(ctx).getString("extra_hosts", "") ?: ""
    fun setExtraHosts(ctx: Context, raw: String) { p(ctx).edit().putString("extra_hosts", raw.take(500)).apply() }
    fun extraHosts(ctx: Context): List<String> =
        extraHostsRaw(ctx).split(',', '\n', ' ', ';').map { SiteVault.normalizeDomain(it.trim()) }.filter { it.isNotBlank() }.distinct()

    /** Purchases, deleting and messaging are allowed when the request clearly asks for them. */
    fun risky(ctx: Context): Boolean = p(ctx).getBoolean("risky", true)
    fun setRisky(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("risky", on).apply() }

    /** The phone stops and asks before a pay / order button is pressed. */
    fun confirmMoney(ctx: Context): Boolean = p(ctx).getBoolean("confirm_money", false)
    fun setConfirmMoney(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("confirm_money", on).apply() }

    /** Reads the HTML behind the page, frames and shadow DOM, and watches the page's own server calls. */
    fun deepRead(ctx: Context): Boolean = p(ctx).getBoolean("deep_read", true)
    fun setDeepRead(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("deep_read", on).apply() }

    /** May the agent open [url] although it is not one of the authorised sites? */
    fun allows(ctx: Context, url: String): Boolean {
        val u = try { android.net.Uri.parse(if (url.startsWith("http")) url else "https://$url") } catch (e: Exception) { return false }
        if (u.scheme != "https" && u.scheme != "http") return false
        return when (domainMode(ctx)) {
            "any" -> true
            "list" -> {
                val h = SiteVault.normalizeDomain(url)
                extraHosts(ctx).any { h == it || h.endsWith(".$it") }
            }
            else -> false
        }
    }
}
