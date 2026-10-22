package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Plain-words commands for posting: "post hello on Facebook", "upload my latest video to TikTok with caption ...". */
object SocialCmd {
    private val I = RegexOption.IGNORE_CASE
    private val lead = Regex("^(?:hey |ok |okay )?(?:(?:neo plus assistant|neo plus|neo)[, ]+)?(?:please |can you |could you |would you )?", I)
    private val attachTag = Regex("\\[Attached file: ([^\\]]+)\\]")
    private const val DEST = "whats ?app business|whats ?app|facebook lite|facebook|fb lite|fb|tik ?tok lite|tik ?tok|instagram lite|instagram|insta|ig"
    private val sayWord = Regex("^(?:saying|says|that says|with the (?:caption|text|message)|with (?:a )?caption|captioned|caption|and say|with|:)\\s*", I)
    private val mediaPhrase = Regex("^(?:my |the |a |an |this |that |our )?(?:(?:latest|last|recent|newest|new|first|best)\\s+)?(?:photos?|pictures?|images?|pics?|selfies?|screenshots?|videos?|clips?|movies?|files?|documents?|pdf)\\b", I)
    private val thisWord = Regex("^(?:this|that|it|these|those|them)(?: (?:photo|picture|image|video|file|one))?$", I)

    private fun kindOf(word: String): String = when (word.lowercase(Locale.ROOT)) {
        "story", "stories" -> "story"
        "reel", "reels" -> "reel"
        "status" -> "status"
        else -> "post"
    }

    fun plan(text: String, apps: List<AppEntry>): LocalPlan? {
        val attached = attachTag.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val t = text.replace(attachTag, " ").trim().replace(lead, "").trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null

        if (Regex("^(?:which|what) apps (?:can|do) you (?:post|control|use|work with)|^(?:what|where) can you post|^which apps can i post", I).containsMatchIn(t))
            return LocalPlan("", listOf("post_apps" to JSONObject()))

        // "set my whatsapp status to hello"
        Regex("^(?:set|update|change|put|write|make)\\s+(?:my\\s+)?($DEST)\\s+(status|story)\\s*(?:to|as|:|saying|says|with)?\\s*(.*)$", I).find(t)?.let { m ->
            return compose("post", m.groupValues[1], kindOf(m.groupValues[2]), m.groupValues[3].trim(), "", attached)
        }
        // "update my status on whatsapp to hello"
        Regex("^(?:set|update|change|put|write|make)\\s+(?:my\\s+)?(status|story)\\s+(?:on|in)\\s+($DEST)\\s*(?:to|as|:|saying|says|with)?\\s*(.*)$", I).find(t)?.let { m ->
            return compose("post", m.groupValues[2], kindOf(m.groupValues[1]), m.groupValues[3].trim(), "", attached)
        }
        // "whatsapp status hello" / "post whatsapp status hello"
        Regex("^(?:post |upload |put )?(?:(?:my|a|an|the)\\s+)?($DEST)\\s+(status|story|stories)\\s*(?:to|as|:|saying|says|with)?\\s*(.*)$", I).find(t)?.let { m ->
            return compose("post", m.groupValues[1], kindOf(m.groupValues[2]), m.groupValues[3].trim(), "", attached)
        }

        val vm = Regex("^(post|share|upload|publish|put|add|send|status)\\b\\s*(.*)$", I).find(t) ?: return null
        val verb = vm.groupValues[1].lowercase(Locale.ROOT)
        val rest = vm.groupValues[2].trim()
        val toPerson = Regex("\\b(?:to|with)\\s+(?!(?:my|the|your)\\b)\\S+", I)

        val destRx = Regex("\\b(?:on|to|in|onto|into|via|at|for)\\s+(?:(?:my|the|your)\\s+)?($DEST)\\b(?:\\s+(status|stories|story|reels?|feed|timeline|page))?", I)
        val dm = destRx.find(rest)
        if (dm != null) {
            val before = rest.substring(0, dm.range.first).trim()
            val after = rest.substring(dm.range.last + 1).trim()
            if ((verb == "share" || verb == "send") && toPerson.containsMatchIn(before)) return null   // a private message to a person
            val kindWord = dm.groupValues[2]
            val isWa = dm.groupValues[1].lowercase(Locale.ROOT).replace(" ", "").startsWith("whatsapp")
            val saysStatus = kindWord.equals("status", true) || Regex("\\bstatus\\b", I).containsMatchIn(before)
            val saysStory = kindWord.lowercase(Locale.ROOT).startsWith("stor") || Regex("\\bstory\\b", I).containsMatchIn(before)
            if (verb == "send" && !saysStatus && !saysStory) return null
            if (isWa && !saysStatus && (verb == "share")) return null   // "share ... on whatsapp" = a message
            val kind = when {
                saysStatus && isWa -> "status"
                saysStory -> "story"
                kindWord.isNotBlank() -> kindOf(kindWord)
                isWa -> "status"
                else -> "post"
            }
            return compose(verb, dm.groupValues[1], kind, before, after, attached)
        }

        // Any other app: "post this on telegram", "share my latest photo on x"
        if (verb == "send") return null
        val prep = Regex("\\b(on|onto|via|to)\\s+(?:(?:my|the)\\s+)?", I)
        for (pm in prep.findAll(rest).toList()) {
            val tail = rest.substring(pm.range.last + 1)
            val words = tail.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (n in 1..minOf(3, words.size)) {
                val cand = words.take(n).joinToString(" ").trim(',', ':', '.')
                if (cand.length < 3) continue
                val app = apps.firstOrNull { it.shownLabel.equals(cand, true) || it.label.equals(cand, true) } ?: continue
                val before = rest.substring(0, pm.range.first).trim()
                val after = words.drop(n).joinToString(" ")
                val prepWord = pm.groupValues[1].lowercase(Locale.ROOT)
                if (verb == "share" && (prepWord == "to" || toPerson.containsMatchIn(before))) return null
                return compose(verb, app.shownLabel, "post", before, after, attached, pkg = app.pkg)
            }
        }
        return null
    }

    private fun compose(verb: String, destRaw: String, kind: String, beforeRaw: String, afterRaw: String, attached: List<String>, pkg: String = ""): LocalPlan {
        val full = "$beforeRaw $afterRaw"
        val ask = AutoCaption.asks(full)
        var what = (if (ask) AutoCaption.head(beforeRaw) else beforeRaw).trim()
        var cap = (if (ask) AutoCaption.head(afterRaw) else afterRaw).trim().replace(sayWord, "").trim().trim('"', '“', '”')
        what = what.replace(Regex("^(?:(?:a|an|the|my|new)\\s+)*(?:status|story|post|update)(?:\\s+update)?(?:\\s*(?:saying|that says|says|of|with|:)\\s*)?", I), "").trim()
        what = what.replace(sayWord, "").trim()
        if (what.isEmpty() && cap.isNotEmpty()) { what = cap; cap = "" }
        what = what.trim('"', '“', '”')

        val files = ArrayList<String>()
        var body: String
        when {
            attached.isNotEmpty() -> { files += attached; body = if (thisWord.matches(what)) cap else listOf(what, cap).filter { it.isNotEmpty() }.joinToString(" ") }
            thisWord.matches(what) -> {
                return LocalPlan("Attach the photo or video with the paperclip first, then say “post this on ${prettyName(destRaw)}”.", emptyList())
            }
            what.split(" ").size <= 9 && (mediaPhrase.containsMatchIn(what) ||
                (MediaFinder.looksLikeMedia(what) && Regex("\\b(called|named|titled)\\b", I).containsMatchIn(what))) -> {
                files += what
                body = cap
            }
            else -> body = listOf(what, cap).filter { it.isNotEmpty() }.joinToString(" ")
        }
        if (files.isEmpty() && body.isEmpty())
            return LocalPlan("What should I post on ${prettyName(destRaw)}? Tell me the words, or name a photo or video.", emptyList())

        val o = JSONObject().put("app", destRaw.trim()).put("kind", kind).put("text", body).put("files", JSONArray(files))
        if (pkg.isNotBlank()) o.put("pkg", pkg)
        if (ask) o.put("auto_caption", true).put("vibe", AutoCaption.vibe(full)).put("song", AutoCaption.wantsSong(full))
            .put("review", !AutoCaption.noReview(full))
        return LocalPlan("", listOf("post" to o))
    }

    private fun prettyName(raw: String) = raw.trim().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}


/** Spots “add a viral and cool caption with the matched song” so those words are not posted as the caption. */
object AutoCaption {
    private val I = RegexOption.IGNORE_CASE
    private const val V = "viral|cool|catchy|trendy|trending|funny|cute|savage|aesthetic|creative|witty|sassy|epic|fire|stylish|smart|clever|motivational|inspiring|romantic|emotional|short|good|great|nice|best|perfect|amazing|awesome|attractive|classy|unique|killer|better|hot"

    private val captionAsk = Regex(
        "\\b(?:(?:$V)(?:\\s*(?:,|and|&)\\s*(?:$V))*\\s+captions?" +
            "|(?:write|generate|create|come up with|think of|make|compose|draft|suggest)\\s+(?:me\\s+)?(?:an?|the|some)?\\s*(?:\\w+\\s+){0,3}captions?" +
            "|(?:ai|auto|automatic)\\s+captions?)\\b", I)
    private val songAsk = Regex(
        "\\b(?:(?:matched|matching|suitable|fitting|perfect|right|best|trending|viral|cool)\\s+(?:song|music|track|audio)s?" +
            "|songs?\\s+(?:that\\s+|to\\s+)?(?:matches|match|fits|fit|goes with))\\b", I)
    private val cutAt = Regex("\\s*(?:,|\\band\\b|\\bthen\\b)?\\s*\\b(?:add|write|make|create|generate|give|put|with|plus|also|using|use|include|caption|captioned)\\b", I)
    private val vibeRx = Regex("\\b($V)\\b", I)
    private val skipReview = Regex(
        "\\b(?:right away|straight away|directly|immediately|without (?:asking|checking|my ok|review)|don'?t ask|no need to (?:ask|check)|just post|post it now)\\b", I)

    /** Text in quotes after “caption” is the user's own caption, not a request to write one. */
    fun asks(s: String): Boolean {
        if (Regex("captions?\\s*[:\"“]", I).containsMatchIn(s)) return false
        return captionAsk.containsMatchIn(s) || songAsk.containsMatchIn(s)
    }

    /** What comes before the “add / write / with ...” request. */
    fun head(s: String): String = cutAt.find(s)?.let { s.substring(0, it.range.first) }?.trim() ?: s.trim()

    fun vibe(s: String): String = vibeRx.findAll(s).map { it.groupValues[1].lowercase(Locale.ROOT) }.distinct().take(4)
        .joinToString(", ").ifBlank { "viral, cool" }

    fun wantsSong(s: String): Boolean = songAsk.containsMatchIn(s)

    fun noReview(s: String): Boolean = skipReview.containsMatchIn(s)
}

