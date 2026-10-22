package com.neo.plus.iosluancher

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Answers without an AI key: each part of the sentence is handled on its own, as a phone command
 * (incl. "read my last message", "read my notifications"), as maths, or as a short Wikipedia fact.
 * When it cannot answer something it says so instead of pasting unrelated text.
 */
object Keyless {
    private val splitter = Regex(
        "(?i)\\s*(?:,\\s*and then|,\\s*then|\\band then\\b|\\bthen\\b|\\band also\\b|\\balso\\b|\\band\\b|[,;?]|\\.\\s)\\s*"
    )

    fun answer(ctx: Context, text: String, apps: List<AppEntry>): String {
        // Whole-sentence phone commands first (posting, typing inside apps): their words may contain "and" / "then".
        (SocialCmd.plan(text, apps) ?: UiCmd.planWhole(text, apps))?.let { plan ->
            val results = plan.actions.map { PhoneActions.run(ctx, it.first, it.second, apps) }
            return when {
                results.any { !it.ok } -> results.first { !it.ok }.note
                plan.reply.isNotBlank() -> plan.reply
                else -> results.joinToString("\n") { it.note }.ifBlank { "Done." }
            }
        }
        val parts = text.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }
        val out = ArrayList<String>()
        for (part in parts) {
            val plan = LocalCommands.plan(part, apps, false)
            if (plan != null) {
                val results = plan.actions.map { PhoneActions.run(ctx, it.first, it.second, apps) }
                val info = results.filter { it.info }.joinToString("\n") { it.note }
                out += when {
                    info.isNotBlank() -> info
                    results.any { !it.ok } -> results.first { !it.ok }.note
                    plan.reply.isNotBlank() -> plan.reply
                    else -> results.firstOrNull()?.note ?: "Done."
                }
                continue
            }
            val m = math(part)
            if (m != null) { out += m; continue }
            val low = part.lowercase(Locale.ROOT).trim().trimEnd('.', '!')
            if (low in setOf("hi", "hello", "hey", "hi neo", "hello neo", "hey neo")) {
                out += "Hi! Without an AI key I can open apps, read your notifications and last message, do maths and look up short facts. Add a free Gemini key (gear icon) for full conversations."
                continue
            }
            out += fact(part)
        }
        return out.joinToString("\n\n")
    }

    /* ---- maths ---- */

    private fun math(part: String): String? {
        var t = part.lowercase(Locale.ROOT).trim()
        t = t.replace(Regex("^(?:please\\s+)?(?:what is|what's|whats|calculate|compute|how much is|solve|tell me)\\s+"), "")
        t = t.replace("?", "").replace(Regex("\\s*(?:what is it|is it|equals?|=)\\s*$"), "")
        t = t.replace("multiplied by", "*").replace("times", "*").replace("divided by", "/")
            .replace("plus", "+").replace("minus", "-").replace("×", "*").replace("÷", "/")
        t = t.replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*").trim()
        if (!Regex("^[0-9+\\-*/().%^\\s]+$").matches(t)) return null
        if (!t.any { it.isDigit() } || !t.any { it in "+-*/%^" }) return null
        return try {
            val p = P(t)
            val v = p.expr()
            p.ws()
            if (p.i != t.length || v.isNaN() || v.isInfinite()) return null
            val r = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
            else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            "$t is $r."
        } catch (e: Exception) { null }
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val r = term(); v = if (o == '+') v + r else v - r }
                else return v
            }
        }
        fun term(): Double {
            var v = factor()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                    val o = s[i++]; val r = factor()
                    v = when (o) { '*' -> v * r; '/' -> v / r; else -> v % r }
                } else return v
            }
        }
        fun factor(): Double {
            ws()
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '+') { i++; return factor() }
            var b = base()
            ws()
            if (i < s.length && s[i] == '^') { i++; b = Math.pow(b, factor()) }
            return b
        }
        fun base(): Double {
            ws()
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                ws()
                if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException()
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return s.substring(st, i).toDouble()
        }
    }

    /* ---- short facts ---- */

    private val stop = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "is", "was", "are", "were", "and", "for", "by", "with", "it", "me",
        "tell", "about", "please", "did", "does", "do", "who", "what", "when", "where", "which", "how", "why", "s"
    )

    /** Finds the answer on Google (then Wikipedia) in the background; nothing is opened on screen. */
    fun search(part: String): String = fact(part)

    private fun fact(part: String): String {
        val words = part.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in stop }
        if (words.isEmpty()) return "I didn't catch that."
        googleAnswer(part.trim())?.let { return it }
        val q = words.joinToString(" ")
        val needed = if (words.size >= 4) words.size - 1 else words.size
        try {
            val s = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + Uri.encode(q)))
            val hits = s.optJSONObject("query")?.optJSONArray("search")
            for (h in 0 until (hits?.length() ?: 0)) {
                val title = hits!!.getJSONObject(h).optString("title")
                val sum = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(title.replace(' ', '_'))))
                if (sum.optString("type") == "disambiguation") continue
                val ex = sum.optString("extract")
                val hay = (title + " " + ex).lowercase(Locale.ROOT)
                if (ex.isNotBlank() && words.count { hay.contains(it) } >= needed) {
                    return "From Wikipedia, $title: " + ex.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").take(320)
                }
            }
        } catch (e: Exception) {
            return "I couldn’t reach the internet to look that up."
        }
        return "I couldn’t find a direct answer to “${part.trim()}” without an AI model. Add a free Gemini key in the assistant settings (gear icon) for proper answers."
    }

    /**
     * Asks Google directly (its plain, no-JavaScript results page) and returns the answer box or the top snippets.
     * Google may refuse automated requests; then this returns null and Wikipedia is tried instead.
     */
    private fun googleAnswer(query: String): String? {
        return try {
            val url = "https://www.google.com/search?hl=en&gbv=1&num=5&q=" + Uri.encode(query)
            val c = URL(url).openConnection() as HttpURLConnection
            val html = try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                c.setRequestProperty("Accept-Language", "en")
                c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
                if (c.responseCode !in 200..299) return null
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            if (html.contains("enablejs", true) || html.contains("unusual traffic", true) || html.contains("consent.google", true)) return null
            fun clean(raw: String) = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\s+"), " ").trim()
            val box = Regex("<div class=\"BNeawe iBp4i AP7Wnd\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { clean(it.groupValues[1]) }.firstOrNull { it.length in 2..300 }
            val snippets = Regex("<div class=\"BNeawe s3v9rd AP7Wnd\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { clean(it.groupValues[1]) }.filter { it.length > 40 }.take(2).toList()
            val parts = ArrayList<String>()
            if (box != null) parts += box
            parts += snippets
            if (parts.isEmpty()) null else "From Google: " + parts.joinToString(" ").take(500)
        } catch (e: Exception) { null }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "NeoPlusAssistant/3.5")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
