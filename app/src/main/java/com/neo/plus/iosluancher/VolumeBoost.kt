package com.neo.plus.iosluancher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10

/**
 * Media volume above 100 % (up to 200 %). The phone's own volume does the first 100 %; this adds the second 100 % as a
 * real loudness gain (+6 dB = twice the amplitude) on the global audio mix, and on every app that announces its audio
 * session, so it applies to whichever app is playing. 0 = off (no effect is created at all).
 */
object VolumeBoost {
    const val STEP = 10          // percent added / removed per volume-button press
    const val MAX = 100          // 100 % extra = 200 % in total

    @Volatile var percent = 0
        private set

    private var loaded = false
    private var global: LoudnessEnhancer? = null
    private val perSession = HashMap<Int, LoudnessEnhancer>()
    private val sessions = HashSet<Int>()

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        percent = ctx.applicationContext.getSharedPreferences("neo_volume", Context.MODE_PRIVATE).getInt("boost", 0).coerceIn(0, MAX)
        apply()
    }

    @Synchronized
    fun change(ctx: Context, delta: Int) = set(ctx, percent + delta)

    @Synchronized
    fun set(ctx: Context, p: Int) {
        load(ctx)
        percent = p.coerceIn(0, MAX)
        apply()   // sets percent back to 0 when this phone does not allow the loudness effect
        ctx.applicationContext.getSharedPreferences("neo_volume", Context.MODE_PRIVATE).edit().putInt("boost", percent).apply()
    }

    @Synchronized
    fun sessionOpened(ctx: Context, id: Int) {
        load(ctx)
        if (id <= 0) return
        sessions.add(id)
        apply()
    }

    @Synchronized
    fun sessionClosed(id: Int) {
        sessions.remove(id)
        try { perSession.remove(id)?.release() } catch (e: Throwable) { }
    }

    /** +100 % extra = x2 amplitude = +6.02 dB. */
    private fun gainMb() = (2000.0 * log10(1.0 + percent / 100.0)).toInt()

    private fun apply() {
        val gain = gainMb()
        if (gain <= 0) { // off: remove every effect so apps sound exactly as normal
            try { global?.release() } catch (e: Throwable) { }
            global = null
            perSession.values.forEach { try { it.release() } catch (e: Throwable) { } }
            perSession.clear()
            return
        }
        if (global == null) global = make(0)
        global?.let { set(it, gain) }
        for (id in sessions) {
            val e = perSession[id] ?: make(id)?.also { perSession[id] = it }
            e?.let { set(it, gain) }
        }
        // Some phones (and Android 10+ on the global mix) refuse the effect: then there is no boost, so do not claim one.
        // Otherwise Volume Down would look dead while it "removes" a boost that never existed.
        if (global == null && perSession.isEmpty()) percent = 0
    }

    private fun make(session: Int): LoudnessEnhancer? = try { LoudnessEnhancer(session) } catch (e: Throwable) { null }

    private fun set(e: LoudnessEnhancer, gain: Int) {
        try { e.setTargetGain(gain); e.setEnabled(true) } catch (t: Throwable) { }
    }
}

/** Apps that follow the audio-effect convention announce their audio session here; the boost is attached to it. */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        when (i.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> VolumeBoost.sessionOpened(ctx, id)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> VolumeBoost.sessionClosed(id)
        }
    }
}
