package com.neo.plus.iosluancher

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Neo Plus's OWN lock: a PIN (4 or 6 digits), a password or a pattern that Neo Plus checks itself.
 *
 * "Replace Android's lock" (device owner only): Android's own lock screen is switched off with
 * DevicePolicyManager.setKeyguardDisabled and this lock takes its place. Android only allows that when the phone has NO lock of
 * its own, and fingerprint and face unlock only exist together with an Android lock, so in this mode they are not available.
 * (With the replacement off, the Neo lock screen still opens Android's real unlock, which takes every lock type including
 * fingerprint and face.)
 *
 * The secret is never stored: only a salted PBKDF2 hash. Wrong tries are counted for good (they survive restarts) and every 5
 * wrong tries add a longer wait.
 */
object NeoLock {
    const val PIN4 = "pin4"
    const val PIN6 = "pin6"
    const val PASSWORD = "password"
    const val PATTERN = "pattern"

    private const val PREF = "neo_credlock"
    private const val ITER = 60_000

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun dpm(ctx: Context) = ctx.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True from the moment of a correct entry until the screen turns off (memory only, so a restart always locks). */
    @Volatile var unlocked = false
    /** How many call screens (wake-up call, approval call) are on screen: the lock waits for them to end. */
    @Volatile var callUp = 0
    @Volatile var showing = false

    class Check(val ok: Boolean, val waitMs: Long, val left: Int)

    /* ---------------- the credential ---------------- */

    fun type(ctx: Context): String = p(ctx).getString("type", "").orEmpty()
    fun hasCredential(ctx: Context) = type(ctx).isNotEmpty() && p(ctx).getString("hash", "").orEmpty().isNotEmpty()

    fun label(type: String) = when (type) {
        PIN4 -> "PIN (4 digits)"
        PIN6 -> "PIN (6 digits)"
        PASSWORD -> "Password"
        PATTERN -> "Pattern"
        else -> "Not set"
    }

    fun noun(type: String) = when (type) { PIN4, PIN6 -> "PIN"; PASSWORD -> "password"; else -> "pattern" }

    fun hint(type: String) = when (type) {
        PIN4 -> "Choose a 4-digit PIN"
        PIN6 -> "Choose a 6-digit PIN"
        PASSWORD -> "Choose a password of at least 6 characters"
        else -> "Draw a pattern through at least 4 dots"
    }

    fun valid(type: String, s: String) = when (type) {
        PIN4 -> Regex("\\d{4}").matches(s)
        PIN6 -> Regex("\\d{6}").matches(s)
        PASSWORD -> s.length >= 6
        PATTERN -> s.length >= 4 && s.toSet().size == s.length && s.all { it in '0'..'8' }
        else -> false
    }

    private fun hash(saltHex: String, secret: String): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITER, 256)).encoded.joinToString("") { "%02x".format(it) }
    }

    fun set(ctx: Context, type: String, secret: String): Boolean {
        if (!valid(type, secret)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("type", type).putString("salt", salt).putString("hash", hash(salt, secret))
            .putInt("fails", 0).putLong("blocked", 0L).apply()
        return true
    }

    /** Removes the credential. Refused while it is replacing Android's lock. */
    fun clear(ctx: Context): Boolean {
        if (replaceOn(ctx)) return false
        p(ctx).edit().remove("type").remove("salt").remove("hash").putInt("fails", 0).putLong("blocked", 0L).apply()
        return true
    }

    fun blockedUntil(ctx: Context) = p(ctx).getLong("blocked", 0L)

    /** Checks an entry. Counts wrong tries for good; every 5 wrong tries bring a wait (30 s, 60 s, ... up to 30 min). */
    fun check(ctx: Context, secret: String): Check {
        val pr = p(ctx)
        val now = System.currentTimeMillis()
        val blocked = pr.getLong("blocked", 0L)
        if (now < blocked) return Check(false, blocked - now, 0)
        val stored = pr.getString("hash", "").orEmpty()
        val salt = pr.getString("salt", "").orEmpty()
        if (stored.isEmpty() || salt.isEmpty()) return Check(false, 0L, 0)
        val ok = try { MessageDigest.isEqual(hash(salt, secret).toByteArray(), stored.toByteArray()) } catch (e: Exception) { false }
        if (ok) { pr.edit().putInt("fails", 0).putLong("blocked", 0L).apply(); return Check(true, 0L, 0) }
        val f = pr.getInt("fails", 0) + 1
        var wait = 0L
        if (f % 5 == 0) wait = minOf(30_000L shl minOf(f / 5 - 1, 6), 30 * 60_000L)
        pr.edit().putInt("fails", f).putLong("blocked", if (wait > 0) now + wait else 0L).apply()
        return Check(false, wait, if (wait > 0) 0 else 5 - f % 5)
    }

    /* ---------------- replacing Android's lock ---------------- */

    fun replaceOn(ctx: Context) = p(ctx).getBoolean("replace", false) && hasCredential(ctx)
    fun needsLock(ctx: Context) = replaceOn(ctx) && !unlocked
    fun lock() { unlocked = false }

    /** The Neo credential is the one to ask for: it replaces Android's lock, or the phone has no lock of its own. */
    fun preferred(ctx: Context): Boolean {
        if (!hasCredential(ctx)) return false
        if (replaceOn(ctx)) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure != true
    }

    /** Switches Android's lock screen off and the Neo lock on. Returns "" when done, otherwise what is missing. */
    fun enableReplace(ctx: Context): String {
        val app = ctx.applicationContext
        if (!hasCredential(app)) return "Set a Neo PIN, password or pattern first"
        if (Kiosk.isOwner(app)) {
            val ok = try { dpm(app).setKeyguardDisabled(DeviceAdmin.component(app), true) } catch (e: Exception) { false }
            if (!ok) return "Android’s own lock is still on. Remove it first (Settings > Security > Screen lock > None), then switch this on again"
        } else {
            // No device-owner mode: the Neo lock simply sits on top of a phone that has no lock of its own.
            val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isDeviceSecure == true) return "Android’s own lock is on. Remove it first (Settings > Security > Screen lock > None), then switch this on again"
        }
        p(app).edit().putBoolean("replace", true).apply()
        unlocked = true   // the credential was just proven; the lock closes the next time the screen turns off
        return ""
    }

    /** Gives Android's own lock screen back (the phone then has the plain swipe lock until a lock is set in Android settings). */
    fun disableReplace(ctx: Context) {
        val app = ctx.applicationContext
        try { dpm(app).setKeyguardDisabled(DeviceAdmin.component(app), false) } catch (e: Exception) { }
        p(app).edit().putBoolean("replace", false).apply()
        unlocked = false
        applyFeatures(app, false)
    }

    /** While the Neo lock is up, the home button and notification shade are switched off (lock-task mode); afterwards they come back. */
    fun applyFeatures(ctx: Context, locked: Boolean) {
        if (Build.VERSION.SDK_INT < 28 || !Kiosk.isOwner(ctx)) return
        try {
            dpm(ctx).setLockTaskFeatures(
                DeviceAdmin.component(ctx),
                if (locked) DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                else DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
            )
        } catch (e: Exception) { }
    }

    /* ---------------- locking and unlocking by itself ---------------- */

    /** May the assistant open the lock by itself for the user's own goals and scheduled jobs, and lock again afterwards? */
    fun autoJobs(ctx: Context) = p(ctx).getBoolean("auto_jobs", true)
    fun setAutoJobs(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("auto_jobs", on).apply() }

    /** Locks the phone now: the screen goes off and the Neo lock is up when it comes back. */
    fun lockNow(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!replaceOn(app)) return false
        lock()
        if (!DeviceAdmin.lockNow(app)) showIfNeeded(app)   // no device admin: cover the screen instead of switching it off
        return true
    }

    /** Opens the lock without anyone typing the PIN. Only used for things the owner already allowed (see [openForJob], [PowerControl.unlock]). */
    fun unlockBySelf(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!replaceOn(app)) return false
        unlocked = true
        applyFeatures(app, false)
        PowerControl.wakeScreen(app)
        NeoLockScreenActivity.closeIfUp()
        return true
    }

    /** A goal check-in or scheduled job is about to run: opens the lock if the user allowed it. Returns true when it did. */
    fun openForJob(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!replaceOn(app) || !autoJobs(app) || !needsLock(app)) return false
        return unlockBySelf(app)
    }

    /** The job is done: locks again, but only if [openForJob] was the one that opened it. */
    fun closeAfterJob(ctx: Context, opened: Boolean) { if (opened) lockNow(ctx) }

    /** Puts the Neo lock on screen when it is needed and nothing (a wake-up call, for example) is meant to be above it. */
    fun showIfNeeded(ctx: Context) {
        if (needsLock(ctx) && callUp == 0 && !showing) NeoLockScreenActivity.start(ctx)
    }
}
