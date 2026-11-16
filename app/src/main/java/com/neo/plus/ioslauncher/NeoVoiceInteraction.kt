package com.neo.plus.ioslauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Lets Android list Neo Plus under Settings > Apps > Default apps > Digital assistant app.
 * Once chosen, the assist gesture (long-press Home / power button / corner swipe) starts a session, and the session opens the
 * Neo chat panel (the same ACTION_ASSIST path MainActivity already handles).
 * No hotword here: the "Hey Neo" wake phrase stays with the floating orb (OrbService).
 */
class NeoVoiceInteractionService : VoiceInteractionService()

class NeoVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NeoVoiceSession(this)
}

class NeoVoiceSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) { }
        hide() // Neo draws its own panel; the system's empty assistant window is not needed
    }
}
