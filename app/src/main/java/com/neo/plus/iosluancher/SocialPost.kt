package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Posts to Facebook, Facebook Lite, TikTok, WhatsApp Status (and WhatsApp Business), Instagram (post, reel, story)
 * and any other app that accepts shared text / photos / videos. It hands the content to the app the normal Android
 * way, then presses the buttons for the user with the accessibility service. Nothing here uses the internet or an AI
 * model, so it costs no Gemini quota.
 *
 * An app can change its screens at any time, so the result only says "posted" after the Post / Share button was
 * really pressed and the screen moved on; otherwise it says plainly that nothing was posted.
 */
object SocialPoster {
    private const val STEP = 0      // press once, then go on to the next stage
    private const val REPEAT = 1    // press whenever it shows up (e.g. "Next")
    private const val FINAL = 2     // the button that publishes

    private class Stage(val kind: Int, val names: List<String>, val ids: List<String> = emptyList())

    private class Target(val key: String, val label: String, val pkgs: List<String>)

    private val targets = listOf(
        Target("whatsappbusiness", "WhatsApp Business", listOf("com.whatsapp.w4b")),
        Target("whatsapp", "WhatsApp", listOf("com.whatsapp")),
        Target("facebooklite", "Facebook Lite", listOf("com.facebook.lite")),
        Target("facebook", "Facebook", listOf("com.facebook.katana")),
        Target("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")),
        Target("instagramlite", "Instagram Lite", listOf("com.instagram.lite")),
        Target("instagram", "Instagram", listOf("com.instagram.android"))
    )

    fun normalize(raw: String): String {
        val k = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        return when (k) {
            "fb" -> "facebook"
            "fblite" -> "facebooklite"
            "insta", "ig" -> "instagram"
            "iglite", "instalite" -> "instagramlite"
            "tiktoklite" -> "tiktok"
            "whatsappb", "w4b" -> "whatsappbusiness"
            else -> k
        }
    }

    /** "post_apps": which supported apps are on this phone. */
    fun supported(ctx: Context): String {
        val have = targets.filter { t -> t.pkgs.any { ShareSender.installed(ctx, it) } }.map { it.label }
        val base = if (have.isEmpty()) "None of Facebook, TikTok, WhatsApp or Instagram is installed here."
        else "I can post to " + have.joinToString(", ") + "."
        return "$base I can also post or share to any other app that accepts shared text, photos or videos: say “post this on <app name>”."
    }

    /**
     * a = {"app":"facebook|facebook lite|tiktok|whatsapp|whatsapp business|instagram|<any app name>", "pkg":"optional package",
     *      "kind":"post|story|status|reel", "text":"words to post / caption", "files":["latest photo", ...] or "file":"..."}
     */
    fun post(ctx: Context, a: JSONObject, apps: List<AppEntry>): String {
        val raw = a.optString("app").trim()
        val key = normalize(raw)
        var text = a.optString("text").trim()
        var kind = a.optString("kind").lowercase(Locale.ROOT).trim()
        val specs = ArrayList<String>()
        a.optJSONArray("files")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { specs += it } }
        a.optString("file").takeIf { it.isNotBlank() }?.let { specs += it }

        val builtin = targets.firstOrNull { it.key == key }
        val generic: AppEntry? = if (builtin != null) null
        else a.optString("pkg").takeIf { it.isNotBlank() }?.let { p -> apps.firstOrNull { it.pkg == p } } ?: apps.findBest(raw)
        if (builtin == null && generic == null) return "No app called “$raw”"

        val label = builtin?.label ?: generic!!.shownLabel
        val pkg = if (builtin != null) builtin.pkgs.firstOrNull { ShareSender.installed(ctx, it) } ?: return "Couldn’t find $label on this phone"
        else generic!!.pkg
        val pkgs: Set<String> = if (builtin != null) builtin.pkgs.toSet() else setOf(pkg)

        if (text.isEmpty() && specs.isEmpty()) return "No words, photo or video given to post on $label"

        // The photos / videos
        BestPhoto.lastWhy = ""
        val items = ArrayList<ShareSender.Item>()
        val founds = ArrayList<MediaFinder.Found>()
        for (sp in specs.distinct()) {
            val found = MediaFinder.find(ctx, sp)
                ?: return "No file, photo or video matching “$sp” (allow Files and media for Neo Plus, or give its exact name)"
            items += ShareSender.expose(ctx, found) ?: return "Couldn’t read “${found.name}”"
            founds += found
        }
        val pickNote = if (BestPhoto.lastWhy.isNotBlank() && founds.isNotEmpty())
            "\nBest photo chosen: “${founds[0].name}” (${BestPhoto.lastWhy})" else ""

        // What a story gets on top of the photo: the caption as a text sticker and the song from the app's music sticker
        var songName = a.optString("song_name").trim()
        var storyText = a.optString("story_text").trim()

        // “add a viral and cool caption with the matched song”: the phone looks at the photo and writes it
        var autoNote = ""
        if (a.optBoolean("auto_caption", false)) {
            val media = founds.firstOrNull {
                val m = it.mime.ifBlank { MediaFinder.mimeFor(it.name) }
                m.startsWith("image/") || m.startsWith("video/")
            }
            val wantSong = a.optBoolean("song", true)
            val gen = try { CaptionWriter.write(ctx, media, label, a.optString("vibe"), wantSong, text) }
            catch (e: CaptionWriter.Failure) { return "Couldn’t write a caption (${e.why}), so nothing is posted" }
            text = gen.fullText()
            storyText = gen.caption
            songName = gen.songTitle().let { t -> if (t.isBlank()) "" else gen.song }
            if (a.optBoolean("review", true)) {
                // Written by the AI, so it waits for your OK first (Send / Skip in the notification).
                // The chosen photo is pinned by name so the OK posts the very photo you were shown.
                val args = JSONObject(a.toString()).put("text", text).put("auto_caption", false).put("review", false)
                    .put("story_text", storyText).put("song_name", songName)
                if (founds.isNotEmpty()) args.put("files", JSONArray(founds.map { it.name })).remove("file")
                Approvals.add(ctx, "post", args, apps)
                return "Caption ready for $label, waiting for your OK (tap Send or Skip in the notification):\n$text$pickNote"
            }
            autoNote = "\nCaption: $text"
        }

        fun share(): Intent =
            if (items.isNotEmpty()) ShareSender.intentFor(ctx, items, text)
            else Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

        val intents = ArrayList<Intent>()   // tried in this order until one opens
        val stages: List<Stage>
        var caption: String? = null
        val finalName: String

        val base = key.removeSuffix("lite").removeSuffix("business")
        when {
            builtin != null && base == "facebook" -> {
                if (kind == "story") {
                    if (items.isEmpty()) return "No photo or video given: a Facebook story needs one"
                    intents += aimed(ctx, share(), pkg, listOf("story"), listOf("messenger", "direct"))
                    stages = listOf(Stage(STEP, listOf("your story", "add to story", "share to story")),
                        Stage(FINAL, listOf("share", "share now", "post to story")))
                    finalName = "Share"
                } else if (items.isNotEmpty()) {
                    intents += aimed(ctx, share(), pkg, listOf("composer", "share", "post"), listOf("messenger", "direct", "story"))
                    caption = text
                    stages = listOf(Stage(FINAL, listOf("post")))
                    finalName = "Post"
                } else {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { intents += it }
                    caption = text
                    stages = listOf(Stage(STEP, listOf("~on your mind", "~write something", "~create a post")),
                        Stage(FINAL, listOf("post")))
                    finalName = "Post"
                }
            }

            builtin != null && base == "instagram" -> {
                if (items.isEmpty()) return "No photo or video given: Instagram needs one"
                if (kind == "story") {
                    if (items.size > 1) return "Instagram stories take one photo or video at a time"
                    val it0 = items[0]
                    intents += Intent("com.instagram.share.ADD_TO_STORY").setPackage(pkg)
                        .setDataAndType(it0.uri, it0.mime)
                        .putExtra("source_application", ctx.packageName)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intents += aimed(ctx, share(), pkg, listOf("story"), listOf("direct"))
                    stages = listOf(Stage(STEP, listOf("add to story", "story")),
                        Stage(FINAL, listOf("your story", "share to story", "share")))
                    finalName = "Your story"
                } else {
                    val reel = kind == "reel"
                    intents += aimed(ctx, share(), pkg, if (reel) listOf("reel", "share") else listOf("feed", "share", "post"),
                        listOf("direct", "story"))
                    caption = text
                    stages = listOf(
                        Stage(STEP, if (reel) listOf("reel", "reels") else listOf("feed", "new post")),
                        Stage(REPEAT, listOf("next", "continue")),
                        Stage(FINAL, listOf("share", "post")))
                    finalName = "Share"
                }
            }

            builtin != null && base == "tiktok" -> {
                if (items.isEmpty()) return "No photo or video given: TikTok needs one"
                intents += aimed(ctx, share(), pkg, listOf("share", "upload"), emptyList())
                caption = text
                stages = listOf(Stage(REPEAT, listOf("next")), Stage(FINAL, listOf("post", "publish")))
                finalName = "Post"
            }

            builtin != null && base == "whatsapp" -> {
                // Posting on WhatsApp = a status update (to send to one person, say “send … to <name> on WhatsApp”)
                intents += aimed(ctx, share(), pkg, listOf("contactpicker", "share"), listOf("call"))
                if (items.isEmpty()) caption = text
                stages = listOf(
                    Stage(STEP, listOf("my status", "status (contacts)", "status")),
                    Stage(REPEAT, listOf("next")),
                    Stage(FINAL, listOf("send"), ids = listOf("$pkg:id/send")))
                finalName = "Send"
                kind = "status"
            }

            else -> {
                // Any other app that accepts shared content (X / Twitter, Telegram channel, LinkedIn, YouTube, Snapchat...)
                intents += aimed(ctx, share(), pkg, listOf("share", "send", "compose", "post"), emptyList())
                stages = listOf(Stage(REPEAT, listOf("next", "continue")),
                    Stage(FINAL, listOf("post", "share", "publish", "tweet", "upload", "send", "submit")))
                finalName = "Post"
            }
        }

        // Stories: caption as a text sticker + the song through the app's own Music sticker (Instagram / Facebook)
        val extras: StoryExtras? = if (kind == "story" && (base == "instagram" || base == "facebook") && builtin != null) {
            val t = storyText.ifBlank { text }
            if (t.isBlank() && songName.isBlank()) null else StoryExtras(base, t, songName)
        } else null

        val what = when {
            items.isEmpty() -> "your text"
            items.size == 1 -> "“${items[0].name}”"
            else -> "${items.size} files"
        }
        val where = if (kind == "story") "$label story" else if (kind == "status") "$label status" else label

        if (IosControlService.instance == null) {
            intents.firstOrNull { ctx.safeStart(it) }
            return "Turn on “NEO PLUS iOS Controls” in Accessibility so I can press the buttons. I opened $label with $what ready, " +
                "but nothing is posted yet."
        }
        if (intents.none { ctx.safeStart(it) }) return "Couldn’t open $label"
        try { Thread.sleep(1200) } catch (e: InterruptedException) { }

        val problem = runFlow(ctx, pkgs, stages, caption, 90_000L, finalName, extras)
        val songNote = when {
            songName.isBlank() -> ""
            extras != null && extras.songDone -> "\nSong added: “$songName”."
            else -> "\nI couldn’t add the music sticker by myself. The matching song is “$songName”: add it with the app’s Music button" +
                if (kind == "story") "." else " (it is also written in the caption)."
        }
        val textNote = if (extras != null && extras.text.isNotBlank() && !extras.textDone)
            "\nI couldn’t put the caption on the story picture itself." else ""
        return if (problem == null) "Posted $what on $where: I pressed $finalName and the screen moved on, so it should be live now.$autoNote$pickNote$songNote$textNote"
        else "Couldn’t finish posting on $where: $problem$autoNote$pickNote"
    }

    /** The share target inside [pkg] that fits best (e.g. Instagram's story handler), or the app in general. */
    private fun aimed(ctx: Context, base: Intent, pkg: String, prefer: List<String>, avoid: List<String>): Intent {
        val i = Intent(base).setPackage(pkg)
        try {
            val all = ctx.packageManager.queryIntentActivities(i, 0).filter { it.activityInfo.packageName == pkg && it.activityInfo.exported }
            val ok = all.filter { r -> avoid.none { r.activityInfo.name.contains(it, ignoreCase = true) } }
            val pick = ok.firstOrNull { r -> prefer.any { r.activityInfo.name.contains(it, ignoreCase = true) } } ?: ok.firstOrNull()
            if (pick != null) i.setClassName(pkg, pick.activityInfo.name)
        } catch (e: Exception) { }
        return i
    }

    /** Walks through the app's screens. Returns null when the last button was pressed and the screen moved on, else why not. */
    private fun runFlow(ctx: Context, pkgs: Set<String>, stages: List<Stage>, caption: String?, timeoutMs: Long, finalName: String,
                        extras: StoryExtras? = null): String? {
        var extrasTried = false
        val cap = caption.orEmpty()
        val start = SystemClock.uptimeMillis()
        var idx = 0
        var typed = cap.isBlank()
        var sawApp = false
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val elapsed = SystemClock.uptimeMillis() - start
            val root = UiDriver.rootOf(ctx, pkgs)
            if (root == null) { nap(450); continue }
            sawApp = true

            if (!typed) {
                val field = UiDriver.findEditable(root, "", skipSearch = true)
                if (field != null) {
                    val cur = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
                    typed = if (cur.contains(cap.take(20), ignoreCase = true)) true else UiDriver.setText(ctx, field, cap, replace = false)
                    nap(400)
                    continue
                }
            }

            var acted = false
            for (j in idx until stages.size) {
                val st = stages[j]
                val node = UiDriver.findAny(root, st.names, st.ids) ?: continue
                if (st.kind == FINAL) {
                    if (!typed && elapsed < 20_000L) break   // wait for the text box to appear
                    if (extras != null && !extrasTried) {
                        // The story editor is showing: add the caption text and the song, then come back and press Share
                        extrasTried = true
                        StoryEditor.apply(ctx, pkgs, extras)
                        break
                    }
                    return pressFinal(ctx, pkgs, st, node, finalName)
                }
                if (UiDriver.tap(node)) {
                    acted = true
                    if (st.kind == STEP) idx = j + 1
                }
                break
            }
            nap(if (acted) 900 else 450)
        }
        return if (!sawApp) "the app never came to the front, so nothing is posted"
        else "I never saw the $finalName button (is the app logged in, and is its screen showing the post?), so nothing is posted"
    }

    private fun pressFinal(ctx: Context, pkgs: Set<String>, st: Stage, first: android.view.accessibility.AccessibilityNodeInfo, finalName: String): String? {
        var node = first
        for (attempt in 0 until 3) {
            if (!UiDriver.tap(node)) return "I couldn’t press $finalName, so nothing is posted"
            nap(2300)
            val r = UiDriver.rootOf(ctx, pkgs)
            val again = if (r == null) null else UiDriver.findAny(r, st.names, st.ids)
            if (again == null) return null
            node = again
        }
        return "I pressed $finalName but the app still shows it, so please check whether it posted"
    }

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }
}

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


/** What to add on top of a story photo before it is shared. [songDone] / [textDone] tell what really worked. */
class StoryExtras(val app: String, val text: String, val song: String) {
    @Volatile var songDone = false
    @Volatile var textDone = false
}

/**
 * Presses the story editor's own buttons: Text (the caption) and Music (search the song, pick the first match).
 * Each step is best effort: apps move their buttons around, so every step checks the screen and gives up quietly,
 * and the caller reports exactly what was and was not added.
 */
object StoryEditor {
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }

    fun apply(ctx: Context, pkgs: Set<String>, x: StoryExtras) {
        if (x.text.isNotBlank()) x.textDone = try { addText(ctx, pkgs, x.text) } catch (e: Exception) { false }
        nap(700)
        if (x.song.isNotBlank()) x.songDone = try { addSong(ctx, pkgs, x.song) } catch (e: Exception) { false }
        nap(700)
    }

    private fun addText(ctx: Context, pkgs: Set<String>, text: String): Boolean {
        val root = UiDriver.rootOf(ctx, pkgs) ?: return false
        val btn = UiDriver.findAny(root, listOf("text", "aa", "add text", "~add text")) ?: return false
        if (!UiDriver.tap(btn)) return false
        val field = UiDriver.waitFor(ctx, pkgs, 5000L) { r -> UiDriver.findEditable(r, "", skipSearch = true) } ?: return false
        if (!UiDriver.setText(ctx, field, text, replace = true)) return false
        nap(500)
        val done = UiDriver.waitFor(ctx, pkgs, 4000L) { r -> UiDriver.findAny(r, listOf("done", "next")) } ?: return false
        UiDriver.tap(done)
        nap(900)
        return true
    }

    private fun addSong(ctx: Context, pkgs: Set<String>, song: String): Boolean {
        val title = song.substringBefore(" – ").substringBefore(" - ").trim().lowercase(Locale.ROOT)
        if (title.isBlank()) return false
        val query = song.replace(" – ", " ").replace(" - ", " ").trim()

        // Facebook shows Music in the editor bar; Instagram keeps it inside the Stickers tray.
        var music = UiDriver.rootOf(ctx, pkgs)?.let { UiDriver.findAny(it, listOf("music")) }
        if (music == null) {
            val stickers = UiDriver.rootOf(ctx, pkgs)?.let { UiDriver.findAny(it, listOf("stickers", "sticker tray", "~stickers")) } ?: return false
            if (!UiDriver.tap(stickers)) return false
            music = UiDriver.waitFor(ctx, pkgs, 5000L) { r -> UiDriver.findAny(r, listOf("music")) }
        }
        if (music == null || !UiDriver.tap(music)) return false

        val search = UiDriver.waitFor(ctx, pkgs, 6000L) { r -> UiDriver.findEditable(r) } ?: return false
        if (!UiDriver.setText(ctx, search, query, replace = true)) return false
        nap(2200)

        val hit = UiDriver.waitFor(ctx, pkgs, 9000L) { r ->
            UiDriver.all(r).firstOrNull { n ->
                n.isVisibleToUser && !n.isEditable && UiDriver.names(n).any { it.contains(title) }
            }
        } ?: return false
        if (!UiDriver.tap(hit)) return false
        nap(1800)

        // After choosing, the app asks which part of the song to use: accept it.
        val done = UiDriver.waitFor(ctx, pkgs, 6000L) { r -> UiDriver.findAny(r, listOf("done", "next")) } ?: return false
        UiDriver.tap(done)
        nap(1200)
        // The tray / editor is back when the Share button of the story is visible again, which runFlow checks next.
        return true
    }
}
