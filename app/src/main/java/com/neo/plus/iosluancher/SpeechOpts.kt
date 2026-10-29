package com.neo.plus.iosluancher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Shared settings for every place the assistant listens (orb, chat mic, lock screen, calls).
 *
 * Before, the recogniser was always told "prefer offline". On many phones (Vivo / Funtouch included) the offline
 * model for the language is not installed, so the recogniser opened the mic ("listening") and then returned nothing,
 * and the error was swallowed. Now offline is preferred only when the phone really has no internet; with internet the
 * normal (online) recogniser is used, which works for every language. Real errors are shown instead of hidden.
 */
object SpeechOpts {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastToast = 0L

    /** true = no internet right now, so ask the recogniser to work offline. */
    fun offline(ctx: Context): Boolean = try {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        !(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    } catch (e: Exception) { false }

    fun errorText(code: Int): String = when (code) {
        1 -> "the speech service timed out (network)"
        2 -> "network problem for speech recognition"
        3 -> "the microphone could not record (another app may be using it, or its permission is blocked)"
        4 -> "the speech service had an error"
        8 -> "the speech service is busy"
        9 -> "microphone permission is missing"
        10 -> "too many speech requests, wait a moment"
        11 -> "the speech service disconnected"
        12 -> "this language is not supported for speech"
        13 -> "this language is not downloaded for offline speech (turn on internet or download it in Google settings)"
        14, 15 -> "cannot check speech language support"
        else -> "speech error $code"
    }

    /** Shows a short message for real recogniser errors (not "nothing heard"), at most once every 8 seconds. */
    fun report(ctx: Context, code: Int) {
        if (code == 5 || code == 6 || code == 7) return   // cancelled by us / silence / nothing matched: normal
        val now = System.currentTimeMillis()
        if (now - lastToast < 8000) return
        lastToast = now
        val app = ctx.applicationContext
        main.post { try { Toast.makeText(app, "Neo Plus mic: " + errorText(code), Toast.LENGTH_LONG).show() } catch (e: Exception) { } }
    }
}
