package com.neo.plus.iosluancher

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Switching the phone off and waking / unlocking it.
 *
 * Power off needs more than a normal app may do. It is tried in this order, and the first one that works wins:
 *  1. Shizuku or root (Magisk): `reboot -p`
 *  2. system app (Magisk module / priv-app with the SHUTDOWN or REBOOT permission, see rom/ folder)
 *  3. the Android power menu (the user taps "Power off")
 *
 * Unlock: Android never lets an app type a PIN, pattern or password for you. With no screen lock set the phone is
 * opened directly. With a lock set, the screen is woken and Android's own unlock prompt is shown, so fingerprint or face
 * unlock works as normal and the PIN is entered by the user.
 */
object PowerControl {

    private val main = Handler(Looper.getMainLooper())

    fun powerOff(ctx: Context): String {
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(2500) // lets the spoken answer finish first
            if (!shutdown(app)) {
                main.post {
                    val opened = IosControlService.instance
                        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) == true
                    Announcer.say(
                        app,
                        if (opened) "I can’t switch the phone off by myself on this phone, so I opened the power menu. Tap Power off."
                        else "I can’t switch the phone off by myself on this phone. Hold the power button and tap Power off."
                    )
                }
            }
        }.start()
        return "Powering off in a moment"
    }

    private fun shutdown(app: Context): Boolean {
        // 1) Shizuku or root
        for (cmd in arrayOf("reboot -p", "svc power shutdown", "/system/bin/reboot -p")) {
            if (attempt { Shell.ok(cmd) }) return true
        }
        // 2) system / privileged app
        if (attempt {
                app.startActivity(
                    Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN")
                        .putExtra("android.intent.extra.KEY_CONFIRM", false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }) return true
        if (attempt {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                PowerManager::class.java
                    .getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(pm, false, null, false)
                true
            }) return true
        return false
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context): Boolean = attempt {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "neo:wake"
        )
        wl.acquire(4000L)
        true
    }

    fun unlock(ctx: Context): String {
        val app = ctx.applicationContext
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        wakeScreen(app)
        if (NeoLock.replaceOn(app)) {
            // The Neo lock is the phone's lock: it opens by itself only right after the PIN was proved, otherwise the PIN pad is shown.
            if (!NeoLock.needsLock(app)) return "The phone is already unlocked"
            if (AppLock.strongOk() && NeoLock.unlockBySelf(app)) return "Unlocked"
            NeoLock.showIfNeeded(app)
            return "Screen is on. Enter your Neo PIN, password or pattern to unlock"
        }
        if (!km.isKeyguardLocked) return "The phone is already unlocked"
        val secure = km.isKeyguardSecure

        // Bring the launcher up over the lock screen and ask Android to dismiss it (see MainActivity.unlockScreen).
        val started = app.safeStart(
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("unlock", true)
        )
        if (!secure) {
            // No lock set: a phone with Shizuku or root can also dismiss it directly
            Thread { attempt { Shell.ok("wm dismiss-keyguard") } }.start()
        }
        return when {
            !started -> "Couldn’t open the unlock screen"
            secure -> "Screen is on. Unlock with your fingerprint, PIN or pattern"
            else -> "Unlocking"
        }
    }
}
