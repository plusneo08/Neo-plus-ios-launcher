package com.neo.plus.ioslauncher

import android.content.Context

/**
 * Records communication events while the single communication assistant is enabled.
 */
object SleepHooks {
    fun note(ctx: Context, kind: String, app: String, who: String, text: String, reply: String = "") {
        try {
            val a = ctx.applicationContext
            if (!SleepMode.isOn(a)) return
            with(SleepMode) { add(a, kind, app, who, text, reply) }
        } catch (e: Exception) { }
    }
}
