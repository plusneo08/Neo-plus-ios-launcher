package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Listens "carefully": fixes speech typos and offers suggestions.
 *  - The recogniser gives up to 5 guesses; the guess whose name (contact or app) really exists on the phone wins.
 *  - After a command word ("call", "text", "open", "message", "did"...), a misheard name or app ("Jon Banda", "Whats app",
 *    "Face book") is corrected to the closest real contact / app when that is clear (one candidate, small difference).
 *  - When it is not clear, the closest contacts / apps are offered as suggestions (the chat shows them as the alternatives).
 * Only the word after the command is touched, so normal sentences are never rewritten.
 */
object SpeechFix {
    @Volatile private var names: List<String> = emptyList()   // contacts + app labels
    @Volatile private var warmedAt = 0L
    @Volatile private var warming = false

    class Result(val text: String, val sure: Boolean, val known: Boolean, val suggestions: List<String>)

    /** Reads contact and app names in the background (at most every 5 minutes). Call when listening starts. */
    fun warm(ctx: Context) {
        val now = System.currentTimeMillis()
        if (warming || now - warmedAt < 5 * 60_000L) return
        warming = true
        val app = ctx.applicationContext
        Thread {
            try {
                val out = LinkedHashSet<String>()
                try {
                    val pm = app.packageManager
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(i, 0).forEach { out += it.loadLabel(pm).toString() }
                } catch (e: Exception) { }
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        app.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                            var n = 0
                            while (c.moveToNext() && n < 3000) { n++; c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it } }
                        }
                    } catch (e: Exception) { }
                }
                names = out.toList()
                warmedAt = System.currentTimeMillis()
            } finally { warming = false }
        }.start()
    }

    private val verbs = Regex("^(call|phone|ring|dial|text|sms|message|whatsapp|open|launch|start|email|mail|reply to|messages? from|texts? from|last message from|did|when did)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val tail = Regex("\\s+(?:on|saying|say|that|with|using|via|and|to|call|called|calls|ring|rang|me)\\b.*$", RegexOption.IGNORE_CASE)
    private val respell = listOf(
        Regex("\\bface ?book\\b", RegexOption.IGNORE_CASE) to "Facebook",
        Regex("\\bwhat'?s ?ap+\\b", RegexOption.IGNORE_CASE) to "WhatsApp",
        Regex("\\byou ?tube\\b", RegexOption.IGNORE_CASE) to "YouTube",
        Regex("\\b(?:tik ?tok|tick ?tock)\\b", RegexOption.IGNORE_CASE) to "TikTok",
        Regex("\\binsta ?gram\\b", RegexOption.IGNORE_CASE) to "Instagram",
        Regex("\\bg ?mail\\b", RegexOption.IGNORE_CASE) to "Gmail"
    )

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Short sound code: similar-sounding words (Jon / John, Brian / Bryan) get the same code. */
    private fun sound(s: String): String {
        if (s.isEmpty()) return ""
        fun code(c: Char) = when (c) {
            'b', 'f', 'p', 'v' -> '1'
            'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
            'd', 't' -> '3'
            'l' -> '4'
            'm', 'n' -> '5'
            'r' -> '6'
            else -> '0'
        }
        val sb = StringBuilder().append(s[0])
        var last = code(s[0])
        for (i in 1 until s.length) {
            val c = code(s[i])
            if (c != '0' && c != last) sb.append(c)
            last = c
        }
        return sb.toString().padEnd(4, '0').take(4)
    }

    /** (best candidate or null, suggestions, true when the name already exists exactly) */
    private fun match(target: String): Triple<String?, List<String>, Boolean> {
        val t = norm(target)
        val list = names
        if (t.length < 3 || list.isEmpty()) return Triple(null, emptyList(), false)
        for (n in list) if (t == norm(n) || t == norm(n.substringBefore(' '))) return Triple(null, emptyList(), true)
        val found = LinkedHashMap<String, Int>()   // what to say -> difference
        for (n in list) {
            for (p in listOf(n, n.substringBefore(' ')).distinct()) {
                val np = norm(p)
                if (np.length < 3) continue
                val d = lev(t, np)
                val close = d <= (if (np.length >= 7) 2 else if (np.length >= 4) 1 else 0) ||
                    (np.length >= 4 && d <= 2 && sound(t) == sound(np))
                if (close) { val old = found[p]; if (old == null || d < old) found[p] = d }
            }
        }
        if (found.isEmpty()) return Triple(null, emptyList(), false)
        val sorted = found.entries.sortedBy { it.value }
        val best = sorted[0]
        val unique = sorted.size == 1 || sorted[1].value > best.value
        return Triple(if (unique && best.value <= 2) best.key else null, sorted.take(3).map { it.key }, false)
    }

    fun fix(text: String): Result {
        var t = text.trim()
        for ((re, to) in respell) t = re.replace(t, to)
        val m = verbs.find(t) ?: return Result(t, true, false, emptyList())
        val verb = m.groupValues[1]
        val rest = m.groupValues[2]
        val tm = tail.find(rest)
        val target = (if (tm != null) rest.substring(0, tm.range.first) else rest).trim()
        val after = if (tm != null) rest.substring(target.length) else ""
        if (target.isEmpty() || target.split(" ").size > 3) return Result(t, true, false, emptyList())
        val (best, sugg, exact) = match(target)
        val phrases = sugg.filter { !it.equals(target, true) }.map { "$verb $it$after" }
        return when {
            exact -> Result(t, true, true, emptyList())
            best != null -> Result("$verb $best$after", true, false, phrases.filter { !it.equals("$verb $best$after", true) })
            else -> Result(t, sugg.isEmpty(), false, phrases)
        }
    }

    /** The best of the recogniser's guesses (a name / app that exists wins), already corrected. */
    fun pick(results: List<String>?): String? {
        val l = results?.filter { it.isNotBlank() }?.take(5) ?: return null
        if (l.isEmpty()) return null
        var best: Result? = null
        var bestScore = Double.NEGATIVE_INFINITY
        l.forEachIndexed { i, h ->
            val r = fix(h)
            val score = -0.1 * i + (if (r.known) 1.5 else 0.0) + (if (r.sure && r.text != h.trim()) 1.0 else 0.0)
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best?.text
    }

    /** Full-sentence suggestions for what was just heard (shown as the alternatives). */
    fun suggest(text: String): List<String> = fix(text).suggestions
}
