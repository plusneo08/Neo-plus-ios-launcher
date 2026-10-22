package com.neo.plus.iosluancher

import android.content.Context

/**
 * Follows a reply that is still being written. [push] gets every new piece, returns the text to show so far (action tags
 * such as [[do:...]] are never shown or spoken), and, when [voice] is on, hands finished sentences to the Gemini voice so it
 * starts talking after the first sentence instead of after the whole reply. The first sentence goes out alone (fastest
 * start); later ones are grouped (about 160 characters) so the voice makes fewer requests.
 */
internal class LiveReply(private val ctx: Context, private var voice: Boolean) {
    private val raw = StringBuilder()
    private var visible = ""
    private var spokenTo = 0
    private var first = true

    /** True once the voice session was opened (something was handed to the voice). */
    @Volatile var voiceStarted = false
        private set

    private val boundary = Regex("(?<=[.!?…])\\s+")

    @Synchronized
    fun push(piece: String): String {
        raw.append(piece)
        var t = raw.toString()
        val cut = t.indexOf("[[")
        if (cut >= 0) t = t.substring(0, cut)
        else if (t.endsWith("[")) t = t.dropLast(1) // the start of a tag that has not fully arrived yet
        visible = parseActionTags(t).first
        if (voice) emit(false)
        return visible
    }

    /** The reply is complete: speak whatever is left and close the voice session. */
    @Synchronized
    fun finish(): String {
        if (voice) {
            emit(true)
            if (voiceStarted) Speaker.liveEnd()
        }
        return visible
    }

    private fun emit(last: Boolean) {
        spokenTo = spokenTo.coerceAtMost(visible.length)
        val pending = visible.substring(spokenTo)
        if (pending.isBlank()) return
        val ends = boundary.findAll(pending).toList()
        val cutAt = when {
            last -> pending.length
            first -> ends.firstOrNull()?.range?.first ?: return
            else -> ends.lastOrNull { it.range.first >= 160 }?.range?.first ?: return
        }
        val chunk = pending.substring(0, cutAt).trim()
        spokenTo += cutAt
        if (chunk.isEmpty()) return
        if (!voiceStarted) {
            if (!Speaker.liveStart(ctx)) { voice = false; return } // no Gemini key: the normal path speaks at the end
            voiceStarted = true
        }
        first = false
        Speaker.liveFeed(chunk)
    }
}
