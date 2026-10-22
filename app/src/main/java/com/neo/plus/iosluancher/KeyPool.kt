package com.neo.plus.iosluancher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** Thrown when Google says a key has used up its quota / rate limit. [waitMs] = how long until it is worth trying again. */
class KeyLimitException(msg: String, val waitMs: Long, val noAccess: Boolean = false) : Llm.LlmException(msg)

/**
 * Any number of Gemini API keys, used ONE AT A TIME.
 *
 * The keys are stored in the existing "key" preference separated by new lines (so one old key still works, and every
 * place that passes "the key" around now passes the whole list without any change). The key in use stays in use until
 * Google answers "quota / rate limit reached" (HTTP 429); that key is then rested and the next one takes over. A rested
 * key comes back by itself when its wait is over. A key Google rejects (invalid) is skipped too.
 *
 * Quotas are per model, so a key that is out of Gemini 4 Argon is still used for Gemini 3.8 Flash and for the voice.
 */
object KeyPool {
    private const val PREF = "neo_keypool"
    private const val DEFAULT_WAIT_MS = 60_000L
    private const val NO_ACCESS_WAIT_MS = 6 * 3_600_000L   // "limit: 0": this key can never use that model (free tier)
    private const val BAD_WAIT_MS = 30 * 60_000L

    private var prefs: SharedPreferences? = null
    private var activeId = ""
    private val limited = HashMap<String, Long>() // "<keyId>|<model>" -> epoch ms until which it is rested
    private val bad = HashMap<String, Long>()     // keyId -> epoch ms until which it is skipped

    /** Splits what is saved (or pasted) into clean, unique keys. New lines, commas, semicolons and spaces all separate. */
    fun parse(raw: String?): List<String> =
        (raw ?: "").split('\n', '\r', ',', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun join(keys: List<String>): String = parse(keys.joinToString("\n")).joinToString("\n")

    private fun id(key: String) = key.hashCode().toString(36)

    /** Call with any context before using the pool, so the active key and the rests survive the app being closed. */
    @Synchronized
    fun attach(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        try {
            val j = JSONObject(p.getString("state", "{}") ?: "{}")
            activeId = j.optString("active")
            j.optJSONObject("lim")?.let { o -> o.keys().forEach { limited[it] = o.optLong(it) } }
            j.optJSONObject("bad")?.let { o -> o.keys().forEach { bad[it] = o.optLong(it) } }
        } catch (e: Exception) { }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        limited.entries.removeAll { it.value <= now }
        bad.entries.removeAll { it.value <= now }
        val j = JSONObject().put("active", activeId)
            .put("lim", JSONObject().also { o -> limited.forEach { (k, v) -> o.put(k, v) } })
            .put("bad", JSONObject().also { o -> bad.forEach { (k, v) -> o.put(k, v) } })
        prefs?.edit()?.putString("state", j.toString())?.apply()
    }

    /** The keys were edited in settings: forget old "rejected" marks so a corrected key gets a fresh chance. */
    @Synchronized
    fun keysChanged() { bad.clear(); save() }

    /**
     * Runs [call] with the key in use. If Google says that key is out of quota, rests it, switches to the next key and
     * repeats the same request. Gives up only when every key is out of quota (or rejected).
     */
    fun <T> run(raw: String, model: String, call: (String) -> T): T {
        val keys = parse(raw)
        if (keys.isEmpty()) throw Llm.LlmException("The API key was rejected: no Gemini key is saved. Add one in the assistant settings.")
        if (keys.size == 1) return call(keys[0]) // one key: behaves exactly as before
        val tried = HashSet<String>()
        var lastLimit: KeyLimitException? = null
        var lastBad: Llm.LlmException? = null
        while (true) {
            val k = pick(keys, model, tried) ?: break
            tried += k
            try {
                val r = call(k)
                markUsed(k)
                return r
            } catch (e: Llm.LlmException) {
                when {
                    e is KeyLimitException -> { rest(k, model, e.waitMs); lastLimit = e }
                    e.message?.startsWith("The API key was rejected") == true -> { markBad(k); lastBad = e }
                    else -> throw e
                }
            }
        }
        // Nothing left to try.
        lastBad?.let { if (lastLimit == null && tried.size == keys.size) throw it }
        val wait = soonest(keys, model)
        throw KeyLimitException(
            "All ${keys.size} Gemini keys have reached their limit for now" +
                (if (wait > 0) ". The first one is free again in about ${humanWait(wait)}." else "."), wait
        )
    }

    @Synchronized
    private fun pick(keys: List<String>, model: String, tried: Set<String>): String? {
        val now = System.currentTimeMillis()
        val start = keys.indexOfFirst { id(it) == activeId }.let { if (it < 0) 0 else it }
        // The key in use first, then the others in list order (wrapping round).
        for (i in keys.indices) {
            val k = keys[(start + i) % keys.size]
            if (k in tried) continue
            if ((bad[id(k)] ?: 0L) > now) continue
            if ((limited[id(k) + "|" + model] ?: 0L) > now) continue
            return k
        }
        return null
    }

    @Synchronized
    private fun markUsed(k: String) {
        if (activeId != id(k)) { activeId = id(k); save() }
    }

    @Synchronized
    private fun rest(k: String, model: String, waitMs: Long) {
        limited[id(k) + "|" + model] = System.currentTimeMillis() + waitMs.coerceIn(5_000L, 36 * 3_600_000L)
        save()
    }

    @Synchronized
    private fun markBad(k: String) { bad[id(k)] = System.currentTimeMillis() + BAD_WAIT_MS; save() }

    @Synchronized
    private fun soonest(keys: List<String>, model: String): Long {
        val now = System.currentTimeMillis()
        return keys.mapNotNull { k -> limited[id(k) + "|" + model]?.minus(now)?.takeIf { it > 0 } }.minOrNull() ?: 0L
    }

    private fun humanWait(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "${maxOf(s, 1)} seconds"
            s < 5400 -> "${(s + 30) / 60} minutes"
            else -> "${(s + 1800) / 3600} hours"
        }
    }

    /**
     * Turns a Google error into a [KeyLimitException] when it means "quota / rate limit reached" (null otherwise).
     * Per-minute limits rest the key for the delay Google names (default one minute); a daily limit rests it until
     * Google's quota day starts again (midnight Pacific time); "limit: 0" means that key can't use that model at all.
     */
    fun limitFrom(code: Int, body: String): KeyLimitException? {
        val status = try { JSONObject(body).optJSONObject("error")?.optString("status").orEmpty() } catch (e: Exception) { "" }
        if (code != 429 && status != "RESOURCE_EXHAUSTED") return null
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
        val low = body.lowercase()
        val wait = when {
            low.contains("limit: 0") -> NO_ACCESS_WAIT_MS
            low.contains("perday") || low.contains("per day") || low.contains("daily") -> untilPacificMidnight()
            else -> Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() + 1_000L } ?: DEFAULT_WAIT_MS
        }
        return KeyLimitException("Request failed ($code): " + msg.ifBlank { "quota reached" }, wait, low.contains("limit: 0"))
    }

    /** True when Google refused the key itself (401/403, or the 400 "API key not valid / expired" answer): skip this key. */
    fun rejectedFrom(code: Int, body: String): Boolean {
        if (code == 401 || code == 403) return true
        if (code != 400) return false
        val low = body.lowercase()
        return low.contains("api_key_invalid") || low.contains("api key not valid") || low.contains("api key expired") || low.contains("api key not found")
    }

    private fun untilPacificMidnight(): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        val now = c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return (c.timeInMillis - now + 30_000L).coerceAtLeast(60_000L)
    }

    /** For the settings screen: one line of status per saved key. */
    class Status(val masked: String, val active: Boolean, val text: String)

    @Synchronized
    fun status(raw: String): List<Status> {
        val keys = parse(raw)
        val now = System.currentTimeMillis()
        val act = keys.firstOrNull { id(it) == activeId } ?: keys.firstOrNull()
        return keys.map { k ->
            val badLeft = (bad[id(k)] ?: 0L) - now
            val limLeft = limited.filter { it.key.startsWith(id(k) + "|") }.values.maxOrNull()?.minus(now) ?: 0L
            val text = when {
                badLeft > 0 -> "Rejected by Google"
                limLeft > 0 -> "Limit reached · back in ${humanWait(limLeft)}"
                k == act -> "In use"
                else -> "Ready"
            }
            Status("…" + k.takeLast(4), k == act && badLeft <= 0 && limLeft <= 0, text)
        }
    }
}
