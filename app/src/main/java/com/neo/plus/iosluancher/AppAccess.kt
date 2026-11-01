package com.neo.plus.iosluancher

import android.content.Context

/**
 * Which phone apps the assistant may open and operate (Assistant settings -> Apps).
 *
 *  - Nothing to log in: the apps keep the logins you already have on the phone.
 *  - Limit is OFF by default, so the assistant works exactly as before until you switch it on.
 *  - When ON, only apps switched on in the list can be opened or controlled by the assistant.
 */
object AppAccess {
    private const val PREFS = "neo_app_access"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Kept in memory so checking an app costs nothing: voice and text replies are never slowed down by it.
    @Volatile private var cLimited: Boolean? = null
    @Volatile private var cPkgs: Set<String>? = null

    fun limited(ctx: Context): Boolean = cLimited ?: p(ctx).getBoolean("limited", false).also { cLimited = it }

    fun setLimited(ctx: Context, on: Boolean) { cLimited = on; p(ctx).edit().putBoolean("limited", on).apply() }

    fun connected(ctx: Context): Set<String> =
        cPkgs ?: (p(ctx).getStringSet("pkgs", emptySet())?.toSet() ?: emptySet()).also { cPkgs = it }

    private fun saveAll(ctx: Context, s: Set<String>) { cPkgs = s; p(ctx).edit().putStringSet("pkgs", s).apply() }

    fun setConnected(ctx: Context, pkg: String, on: Boolean) =
        saveAll(ctx, if (on) connected(ctx) + pkg else connected(ctx) - pkg)

    /** One tap: connect (or disconnect) every app in the list. */
    fun setAll(ctx: Context, pkgs: Collection<String>, on: Boolean) =
        saveAll(ctx, if (on) connected(ctx) + pkgs else connected(ctx) - pkgs.toSet())

    /** True when the assistant may use this app. The launcher itself is always allowed. */
    fun allowed(ctx: Context, pkg: String?): Boolean {
        if (!limited(ctx) || pkg == null || pkg == ctx.packageName) return true
        return pkg in connected(ctx)
    }

    fun blockedMessage(label: String) =
        "No access to $label: it is not connected. Switch it on in Assistant settings > Apps."
}
