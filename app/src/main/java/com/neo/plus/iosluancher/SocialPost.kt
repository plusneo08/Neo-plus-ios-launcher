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
        // Also try the same share without a named screen (the app picks its own), and say WHY it failed if nothing opens.
        val failures = ArrayList<String>()
        fun tryStart(i: Intent): Boolean = try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            failures += (e.javaClass.simpleName + ": " + e.message.orEmpty().take(90))
            false
        }
        if (builtin != null && kind != "story" && items.isNotEmpty()) intents += Intent(share()).setPackage(pkg)
        if (intents.none { tryStart(it) })
            return "Couldn’t open $label (${failures.distinct().joinToString(" / ").ifBlank { "no way to open it" }})"
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
