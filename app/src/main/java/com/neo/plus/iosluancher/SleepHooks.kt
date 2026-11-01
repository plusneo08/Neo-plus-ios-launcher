package com.neo.plus.iosluancher

import android.content.Context

/**
 * Lets the newer features add to the night summary: while sleep mode is on, calls, texts on the assistant's own number,
 * goal progress and permission problems are written into the same log the wake-up call reads out.
 * Does nothing when sleep mode is off.
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
