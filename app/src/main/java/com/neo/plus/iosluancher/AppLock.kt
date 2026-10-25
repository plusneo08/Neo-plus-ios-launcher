package com.neo.plus.iosluancher

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The Neo Plus lock: a second layer on top of the phone's own lock screen.
 *
 *  - It covers Neo Plus itself and any apps the user chose with a lock screen of its own ([LockActivity]).
 *  - It is opened with the phone's own unlock (fingerprint, face, PIN, pattern or password: Android shows its prompt and
 *    only tells Neo Plus "yes" or "no"; Neo Plus never sees or stores any of them),
 *    or, if the user switched it on, with a spoken phrase.
 *  - The spoken phrase is a convenience, not strong security: it checks the WORDS, not whose voice it is. So it is never
 *    offered for money apps, it stops working for 5 minutes after 3 wrong tries, and anything that could weaken the lock
 *    (switching it off, changing the phrase, removing apps) needs the phone's own unlock.
 *  - Everything that is "unlocked" is kept in memory only: after a restart of the app everything is locked again.
 *  - Goals, schedules and other jobs the user set up to run on their own are not blocked by it.
 */
object AppLock {
    private const val PREF = "neo_lock"
    const val STRONG_MS = 5 * 60_000L

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /* ---------------- settings ---------------- */

    fun enabled(ctx: Context) = p(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("on", on).apply() }
    fun lockOnScreenOff(ctx: Context) = p(ctx).getBoolean("screen_off", true)
    fun setLockOnScreenOff(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("screen_off", on).apply() }
    /** Minutes away from Neo Plus / an app before it locks again. 0 = at once. */
    fun timeoutMin(ctx: Context) = p(ctx).getInt("timeout", 2)
    fun setTimeoutMin(ctx: Context, m: Int) { p(ctx).edit().putInt("timeout", m.coerceIn(0, 60)).apply() }
    fun lockedApps(ctx: Context): Set<String> = p(ctx).getStringSet("apps", emptySet()).orEmpty().toSet()
    fun setApp(ctx: Context, pkg: String, on: Boolean) {
        if (pkg == ctx.packageName) return
        val s = lockedApps(ctx).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        p(ctx).edit().putStringSet("apps", s).apply()
    }
    fun voiceOn(ctx: Context) = p(ctx).getBoolean("voice_on", false) && hasPhrase(ctx)
    fun setVoiceOn(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("voice_on", on).apply() }
    fun hasPhrase(ctx: Context) = p(ctx).getString("phrase_hash", "").orEmpty().isNotBlank()

    /* ---------------- what is unlocked right now (memory only) ---------------- */

    @Volatile var neoUnlocked = false
    @Volatile var neoLeftAt = 0L
    private val appLeft = ConcurrentHashMap<String, Long>()   // pkg -> time it was left; 0 = open right now
    @Volatile var strongUntil = 0L                              // the phone's own unlock was used a moment ago
    @Volatile var showing = false
    @Volatile var showingPkg = ""
    @Volatile private var lastPkg = ""
    @Volatile private var voiceFails = 0
    @Volatile private var voiceBlockedUntil = 0L

    fun strongOk() = System.currentTimeMillis() < strongUntil
    fun voiceBlocked() = System.currentTimeMillis() < voiceBlockedUntil

    /** Money apps never accept the voice phrase. */
    fun voiceAllowedFor(pkg: String) = pkg.isBlank() || !SleepMode.looksLikeMoney(pkg, "", "")

    fun neoLocked(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        if (!neoUnlocked) return true
        val left = neoLeftAt
        if (left > 0 && System.currentTimeMillis() - left > timeoutMin(ctx) * 60_000L) { neoUnlocked = false; return true }
        return false
    }

    fun unlocked(ctx: Context, pkg: String, strong: Boolean) {
        if (pkg.isBlank()) { neoUnlocked = true; neoLeftAt = 0L } else { appLeft[pkg] = 0L; lastPkg = pkg }
        if (strong) strongUntil = System.currentTimeMillis() + STRONG_MS
        voiceFails = 0
    }

    fun lockNow(ctx: Context) {
        neoUnlocked = false; appLeft.clear(); strongUntil = 0L
        if (enabled(ctx)) LockActivity.start(ctx, "")
    }

    fun onScreenOff(ctx: Context) {
        if (!enabled(ctx) || !lockOnScreenOff(ctx)) return
        neoUnlocked = false; appLeft.clear(); strongUntil = 0L; lastPkg = ""
    }

    /** The phone was unlocked: if a locked app is what is in front, cover it. */
    fun onUserPresent(ctx: Context, frontPkg: String?) {
        if (!enabled(ctx) || frontPkg.isNullOrBlank()) return
        lastPkg = frontPkg
        if (frontPkg == ctx.packageName) { if (neoLocked(ctx)) LockActivity.start(ctx, "") } else checkPkg(ctx, frontPkg)
    }

    /** Called for every new window that comes to the front (not the keyboard, not system dialogs). */
    fun onForeground(ctx: Context, pkg: String, cls: String) {
        if (!enabled(ctx) || cls == LockActivity::class.java.name) return
        val prev = lastPkg
        if (pkg == prev) return
        if (prev.isNotBlank() && appLeft.containsKey(prev)) appLeft[prev] = System.currentTimeMillis()
        lastPkg = pkg
        checkPkg(ctx, pkg)
    }

    private fun checkPkg(ctx: Context, pkg: String) {
        if (pkg !in lockedApps(ctx) || PinVault.unattended) return
        if (showing && showingPkg == pkg) return
        val left = appLeft[pkg]
        if (left != null && (left == 0L || System.currentTimeMillis() - left <= timeoutMin(ctx) * 60_000L)) { appLeft[pkg] = 0L; return }
        appLeft.remove(pkg)
        LockActivity.start(ctx, pkg)
    }

    /* ---------------- the spoken phrase ---------------- */

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun hash(salt: String, s: String) = MessageDigest.getInstance("SHA-256").digest("$salt|$s".toByteArray()).joinToString("") { "%02x".format(it) }

    fun phraseOk(raw: String) = norm(raw).split(" ").count { it.isNotBlank() } >= 3
    fun sameWords(a: String, candidates: List<String>) = candidates.any { norm(it) == norm(a) }

    fun setPhrase(ctx: Context, raw: String): Boolean {
        if (!phraseOk(raw)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("phrase_salt", salt).putString("phrase_hash", hash(salt, norm(raw))).apply()
        return true
    }

    fun clearPhrase(ctx: Context) { p(ctx).edit().remove("phrase_salt").remove("phrase_hash").putBoolean("voice_on", false).apply() }

    fun checkPhrase(ctx: Context, candidates: List<String>): Boolean {
        if (voiceBlocked()) return false
        val salt = p(ctx).getString("phrase_salt", "").orEmpty()
        val h = p(ctx).getString("phrase_hash", "").orEmpty()
        if (h.isBlank()) return false
        val ok = candidates.any { MessageDigest.isEqual(hash(salt, norm(it)).toByteArray(), h.toByteArray()) }
        if (ok) voiceFails = 0
        else { voiceFails++; if (voiceFails >= 3) { voiceBlockedUntil = System.currentTimeMillis() + 5 * 60_000L; voiceFails = 0 } }
        return ok
    }

    /* ---------------- the `lock_app` action ---------------- */

    fun handle(ctx: Context, a: JSONObject): String = when (a.optString("do").lowercase()) {
        "now" -> if (!enabled(ctx)) "The lock is off: switch it on in Assistant settings → Lock" else { lockNow(ctx); "Locked" }
        "status" -> "Lock is ${if (enabled(ctx)) "on" else "off"}, ${lockedApps(ctx).size} app(s) locked, voice unlock ${if (voiceOn(ctx)) "on" else "off"}"
        else -> "Lock settings are changed in Assistant settings → Lock, where the phone asks for your unlock first"
    }
}
