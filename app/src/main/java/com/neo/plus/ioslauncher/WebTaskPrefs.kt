package com.neo.plus.ioslauncher

import android.content.Context
import android.net.Uri
import java.net.IDN
import java.util.Locale

/**
 * Persistent owner-controlled limits for website automation.
 *
 * Safe defaults are deliberately restrictive: the task starts on a site explicitly
 * authorised in SiteVault, cannot follow unrelated domains by default, and does not
 * perform purchases, deletions, or messages unless the owner changes the setting.
 */
object WebTaskPrefs {
    private const val FILE = "web_task_prefs"
    private const val DOMAIN_MODE = "domain_mode"
    private const val EXTRA_HOSTS = "extra_hosts"
    private const val RISKY = "risky_actions"
    private const val CONFIRM_MONEY = "confirm_money"
    private const val DEEP_READ = "deep_read"
    private const val BACKEND_CALLS = "backend_calls"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** strict = only the SiteVault-authorised site; list = that site plus extraHosts; any = owner opted into any domain. */
    fun domainMode(ctx: Context): String =
        p(ctx).getString(DOMAIN_MODE, "strict")?.takeIf { it in setOf("strict", "list", "any") } ?: "strict"

    fun setDomainMode(ctx: Context, mode: String) {
        if (mode in setOf("strict", "list", "any")) p(ctx).edit().putString(DOMAIN_MODE, mode).apply()
    }

    fun extraHostsRaw(ctx: Context): String = p(ctx).getString(EXTRA_HOSTS, "") ?: ""

    fun setExtraHosts(ctx: Context, raw: String) {
        // Store only host names, never credentials, paths, query strings, or fragments.
        val hosts = parseHosts(raw)
        p(ctx).edit().putString(EXTRA_HOSTS, hosts.joinToString("\n")).apply()
    }

    fun extraHosts(ctx: Context): List<String> = parseHosts(extraHostsRaw(ctx))

    fun risky(ctx: Context): Boolean = p(ctx).getBoolean(RISKY, false)
    fun setRisky(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(RISKY, on).apply()
    }

    /** Payment/order confirmation is enabled by default and cannot be silently disabled by a missing preference. */
    fun confirmMoney(ctx: Context): Boolean = p(ctx).getBoolean(CONFIRM_MONEY, true)
    fun setConfirmMoney(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(CONFIRM_MONEY, on).apply()
    }

    fun deepRead(ctx: Context): Boolean = p(ctx).getBoolean(DEEP_READ, true)
    fun setDeepRead(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(DEEP_READ, on).apply()
    }

    /** Direct website backend/API calls are opt-in, not enabled by default. */
    fun backendCalls(ctx: Context): Boolean = p(ctx).getBoolean(BACKEND_CALLS, false)
    fun setBackendCalls(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(BACKEND_CALLS, on).apply()
    }

    /**
     * Checks a candidate URL against the configured cross-domain policy.
     * The initial authorised SiteVault domain is validated separately by WebTask.
     * strict therefore rejects every cross-domain candidate; list permits exact
     * allowlisted hosts and their subdomains; any is an explicit owner opt-in.
     */
    fun allows(ctx: Context, url: String): Boolean {
        val host = hostOf(url) ?: return false
        return when (domainMode(ctx)) {
            "any" -> true
            "list" -> extraHosts(ctx).any { host == it || host.endsWith(".$it") }
            else -> false
        }
    }

    private fun parseHosts(raw: String): List<String> = raw
        .split(',', ';', '\n', '\r', ' ', '\t')
        .mapNotNull { token -> hostOf(token.trim()) }
        .distinct()
        .take(100)

    private fun hostOf(value: String): String? {
        if (value.isBlank()) return null
        return try {
            val candidate = if (value.contains("://")) value else "https://$value"
            val host = Uri.parse(candidate).host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
            if (host.isBlank() || host.contains('*') || host.contains('@')) return null
            val ascii = IDN.toASCII(host).lowercase(Locale.ROOT)
            if (ascii.length > 253 || ascii.split('.').any { it.isBlank() || it.length > 63 }) null else ascii
        } catch (_: Exception) {
            null
        }
    }
}
