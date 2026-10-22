package com.neo.plus.iosluancher

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Silences the speech recognizer's start/stop beeps. The beep plays on a different audio stream depending on the
 * phone, so the likely streams are muted while the mic is open and put back right after (only the ones that were
 * not already muted by the user). Muting never touches the assistant's spoken replies: the mic is closed while it talks.
 */
object BeepMute {
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private val main = Handler(Looper.getMainLooper())
    private val mutedByUs = HashSet<Int>()
    private var restoreJob: Runnable? = null
    private const val PREF = "neo_beepmute"

    private fun save(ctx: Context) {
        try {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putStringSet("streams", mutedByUs.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) { }
    }

    /** True while a stream is still muted by us (waiting for the delayed restore). */
    fun isHolding() = mutedByUs.isNotEmpty()

    /**
     * If the app was killed while a stream was muted by us, nobody would ever unmute it (the phone would also report it
     * as "already muted by the user"). Call this when the app or the controls service starts.
     */
    fun recover(ctx: Context) {
        if (mutedByUs.isNotEmpty()) return
        try {
            val app = ctx.applicationContext
            val p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val left = p.getStringSet("streams", emptySet())?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (left.isEmpty()) return
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (s in left) { try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { } }
            p.edit().putStringSet("streams", emptySet()).apply()
        } catch (e: Exception) { }
    }

    /** [includeMusic] = false leaves media playing (used by the hidden wake standby, which restarts the recogniser all the time). */
    fun mute(ctx: Context, includeMusic: Boolean = true) {
        restoreJob?.let { main.removeCallbacks(it) }
        restoreJob = null
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in streams) {
            if (!includeMusic && s == AudioManager.STREAM_MUSIC) continue
            if (s in mutedByUs) continue
            try {
                val already = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(s) else false
                if (!already) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs += s
                }
            } catch (e: Exception) { } // e.g. Do Not Disturb blocks changing a stream: skip it
        }
        save(ctx)
    }

    /** Puts the volumes back. The short delay lets the recognizer's closing beep pass while still muted. */
    fun restore(ctx: Context, delayMs: Long = 600L) {
        if (mutedByUs.isEmpty()) return
        restoreJob?.let { main.removeCallbacks(it) }
        val app = ctx.applicationContext
        val job = Runnable { restoreNow(app) }
        restoreJob = job
        main.postDelayed(job, delayMs)
    }

    fun restoreNow(ctx: Context) {
        restoreJob?.let { main.removeCallbacks(it) }
        restoreJob = null
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in mutedByUs.toList()) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        mutedByUs.clear()
        save(ctx)
    }
}
