package com.neo.plus.iosluancher

import android.content.Context

object WebTaskPrefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("web_task_prefs", Context.MODE_PRIVATE)

    fun domainMode(ctx: Context): String = "any"
    fun setDomainMode(ctx: Context, mode: String) {}

    fun extraHostsRaw(ctx: Context): String = ""
    fun setExtraHosts(ctx: Context, raw: String) {}
    fun extraHosts(ctx: Context): List<String> = emptyList()

    fun risky(ctx: Context): Boolean = true
    fun setRisky(ctx: Context, on: Boolean) {}

    fun confirmMoney(ctx: Context): Boolean = false
    fun setConfirmMoney(ctx: Context, on: Boolean) {}

    fun deepRead(ctx: Context): Boolean = true
    fun setDeepRead(ctx: Context, on: Boolean) {}

    fun backendCalls(ctx: Context): Boolean = true
    fun setBackendCalls(ctx: Context, on: Boolean) {}

    fun allows(ctx: Context, url: String): Boolean = true
}
