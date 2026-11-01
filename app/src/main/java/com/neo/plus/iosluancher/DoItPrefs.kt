package com.neo.plus.iosluancher

import android.content.Context

/** "Do what I ask without asking again": the assistant acts on a prompt at once instead of asking for confirmation. */
object DoItPrefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("doit_prefs", Context.MODE_PRIVATE)

    fun noReask(ctx: Context): Boolean = p(ctx).getBoolean("no_reask", true)
    fun setNoReask(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("no_reask", on).apply() }

    /** The SIM (1 or 2) the user last chose for a call, so the next call does not ask again. */
    fun lastSim(ctx: Context): Int = p(ctx).getInt("last_sim", 1)
    fun setLastSim(ctx: Context, slot: Int) { if (slot in 1..2) p(ctx).edit().putInt("last_sim", slot).apply() }
}
