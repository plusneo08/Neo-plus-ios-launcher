package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.asContextElement

/**
 * Remembers WHERE a request came from, so the assistant knows whether to come back to the chat when a job in another app ends.
 *  - typed or spoken in the chat panel  -> origin "chat": when the job in the other app is finished, the chat comes back with the result
 *  - said to the floating orb ("Hey Neo ...") -> no origin: the orb answers with subtitles / voice and the chat is never opened
 */
object TaskOrigin {
    val origin = ThreadLocal<String?>()
    fun chatElement() = origin.asContextElement("chat")

    /** Actions that do a job inside another app (the user is taken out of Neo while they run). */
    private val appWork = setOf(
        "app_task", "ui_tap", "ui_tap_visual", "ui_long_press", "ui_type", "ui_scroll", "ui_back", "ui_search", "ui_enter"
    )

    @Volatile private var workAt = 0L

    fun reset() { workAt = 0L }

    /** Called by every action; only counts when the request came from the chat. */
    fun note(action: String) { if (origin.get() == "chat" && action in appWork) workAt = System.currentTimeMillis() }

    fun didAppWork() = workAt > 0L
}

object ChatReturn {
    private val main = Handler(Looper.getMainLooper())

    private fun neoInFront(): Boolean =
        MainActivity.current?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    /** Brings the chat panel back to the front (only if Neo is not already showing and the screen is on). */
    fun bring(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            try {
                if (neoInFront()) return@post
                val pm = app.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isInteractive) return@post // screen off: the "Neo Plus finished" notification covers it
                app.startActivity(
                    Intent(app, MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (e: Exception) { }
        }
    }
}
