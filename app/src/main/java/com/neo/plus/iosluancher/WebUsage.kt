package com.neo.plus.iosluancher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object WebUsage {
    const val DEFAULT_LIMIT = 0

    var used by mutableStateOf(0)
        private set
    var saved by mutableStateOf(0)
        private set
    var limit by mutableStateOf(0)
        private set

    fun load(ctx: Context) {}
    fun allowed(ctx: Context): Boolean = true
    fun spend(ctx: Context) {}
    fun saveReplay(ctx: Context, steps: Int) {}
    fun setLimit(ctx: Context, n: Int) {}
    fun resetToday(ctx: Context) {}
    fun summary(): String = "unlimited requests"
}
