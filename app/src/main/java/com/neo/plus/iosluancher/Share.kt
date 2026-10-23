package com.neo.plus.iosluancher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Finds the file, photo, video or song the user means: an attached file, a file name, or words such as
 * "latest photo", "last song", "song Despacito", "video called birthday".
 */
object MediaFinder {
    class Found(val uri: Uri?, val file: File?, val name: String, val mime: String)

    private val imageWords = Regex("(?i)\\b(photos?|pictures?|images?|pics?|selfies?|screenshots?)\\b")
    private val videoWords = Regex("(?i)\\b(videos?|clips?|movies?)\\b")
    private val audioWords = Regex("(?i)\\b(songs?|music|audios?|tracks?|voice notes?|recordings?)\\b")
    private val newest = Regex("(?i)\\b(latest|last|recent|newest|most recent|new)\\b")
    private val filler = Regex("(?i)\\b(the|my|a|an|called|named|titled|by|file|latest|last|recent|newest|most recent|new|of)\\b")

    private val placeWords = Regex("(?i)\\b(from|in|on|at|to|gallery|galleries|camera roll|camera|phone|device|storage|album|albums|photos app|taken|please|this|one)\\b")

    fun looksLikeMedia(spec: String) =
        imageWords.containsMatchIn(spec) || videoWords.containsMatchIn(spec) || audioWords.containsMatchIn(spec) ||
            Regex("(?i)\\b(file|document|pdf|attachment)\\b").containsMatchIn(spec)

    private fun hasMediaPermission(ctx: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_AUDIO) ||
            ok(Manifest.permission.READ_MEDIA_VIDEO) || ok(Manifest.permission.READ_EXTERNAL_STORAGE) ||
            (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())
    }

    private fun query(ctx: Context, base: Uri, nameCols: List<String>, word: String, newestOnly: Boolean, pathHint: String? = null): Found? {
        val sel = ArrayList<String>()
        val args = ArrayList<String>()
        if (word.isNotBlank()) {
            sel += "(" + nameCols.joinToString(" OR ") { "$it LIKE ?" } + ")"
            nameCols.forEach { _ -> args += "%$word%" }
        }
        if (pathHint != null) { sel += "${MediaStore.MediaColumns.DATA} LIKE ?"; args += "%$pathHint%" }
        if (!newestOnly && word.isBlank() && pathHint == null) return null
        return try {
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                if (sel.isEmpty()) null else sel.joinToString(" AND "), if (args.isEmpty()) null else args.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(0)
                val name = c.getString(1) ?: "file"
                val mime = c.getString(2) ?: ""
                Found(ContentUris.withAppendedId(base, id), null, name, mime)
            }
        } catch (e: Exception) { null }
    }

    /** [spec] is a file name, or a description such as "latest photo" / "song Despacito". Null when nothing matches. */
    fun find(ctx: Context, spec: String): Found? {
        val s = spec.trim().trim('"', '“', '”', '\'')
        if (s.isEmpty()) return null
        // 1. A real file name (attached or in Downloads, Documents, the Neo Plus folder)
        AssistantFiles.find(ctx, s)?.let { return Found(null, it, it.name, mimeFor(it.name)) }
        if (!hasMediaPermission(ctx)) return null
        // “best photo on my phone”: look through the recent camera photos and choose one
        if (BestPhoto.isAsk(s)) return BestPhoto.pick(ctx)?.also { BestPhoto.lastWhy = it.why }?.found

        val wantNewest = newest.containsMatchIn(s)
        val isShot = Regex("(?i)screenshots?").containsMatchIn(s)
        var word = s.replace(imageWords, " ").replace(videoWords, " ").replace(audioWords, " ").replace(filler, " ")
            .replace(Regex("\\s+"), " ").trim()
        // "the latest photo from my gallery / on my phone / in the camera roll": the place is not part of the file name
        if (wantNewest) word = word.replace(placeWords, " ").replace(Regex("\\s+"), " ").trim()
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val audios = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val nameCols = listOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.TITLE)
        val audioCols = nameCols + MediaStore.Audio.Media.ARTIST

        val order = when {
            imageWords.containsMatchIn(s) -> listOf(images, videos, audios)
            videoWords.containsMatchIn(s) -> listOf(videos, images, audios)
            audioWords.containsMatchIn(s) -> listOf(audios, videos, images)
            else -> listOf(audios, images, videos)
        }
        for (base in order) {
            val cols = if (base == audios) audioCols else nameCols
            val hit = query(ctx, base, cols, word, newestOnly = wantNewest || word.isBlank(), pathHint = if (isShot && base == images) "Screenshot" else null)
            if (hit != null) return hit
        }
        return null
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return AssistantFiles.mimeOf(name)
    }
}

/** Sends photos, videos, songs and any file through WhatsApp, WhatsApp Business, Messenger / Facebook, Instagram and email. */
object ShareSender {
    private const val WA = "com.whatsapp"
    private const val WAB = "com.whatsapp.w4b"
    private const val GMAIL = "com.google.android.gm"
    private const val IG = "com.instagram.android"

    class Item(val uri: Uri, val name: String, val mime: String)

    internal fun installed(ctx: Context, pkg: String) =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    /** Copies the file to the app's cache and returns a content:// address that other apps are allowed to read. */
    internal fun expose(ctx: Context, f: MediaFinder.Found): Item? = try {
        val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 3600_000L }?.forEach { it.delete() }
        val safe = f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val out = File(dir, safe)
        if (f.file != null) f.file.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        else ctx.contentResolver.openInputStream(f.uri!!)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: throw Exception("unreadable")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        Item(uri, safe, f.mime.ifBlank { MediaFinder.mimeFor(safe) })
    } catch (e: Exception) { null }

    private fun e164(ctx: Context, raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("+")) return t.filter { it.isDigit() }
        val iso = try {
            ctx.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null } ?: Locale.getDefault().country
        val n = try { PhoneNumberUtils.formatNumberToE164(t, iso.uppercase(Locale.ROOT)) } catch (e: Exception) { null }
        return n?.filter { it.isDigit() }
    }

    /**
     * a = {"file" or "files":[...], "to":"name or number", "app":"whatsapp|whatsapp business|messenger|facebook|instagram|email|gmail",
     * "text":"optional caption / message"}
     */
    fun send(ctx: Context, a: JSONObject): String {
        val specs = ArrayList<String>()
        a.optJSONArray("files")?.let { arr -> for (i in 0 until arr.length()) specs += arr.optString(i) }
        a.optString("file").takeIf { it.isNotBlank() }?.let { specs += it }
        if (specs.isEmpty()) return "No file named to send"
        val to = a.optString("to").trim()
        val text = a.optString("text").trim()
        val appWord = a.optString("app").lowercase(Locale.ROOT).replace(" ", "")

        val items = ArrayList<Item>()
        for (sp in specs.distinct()) {
            val found = MediaFinder.find(ctx, sp)
                ?: return "No file, photo or song matching “$sp” (allow Files and media for Neo Plus, or tell me its exact name)"
            items += expose(ctx, found) ?: return "Couldn’t read “${found.name}”"
        }

        val label = if (items.size == 1) "“${items[0].name}”" else "${items.size} files"
        return when {
            appWord.startsWith("whatsappbusiness") || appWord == "whatsappb" || appWord == "w4b" -> viaWhatsApp(ctx, items, to, text, label, business = true)
            appWord.startsWith("whatsapp") -> viaWhatsApp(ctx, items, to, text, label, business = false)
            appWord.startsWith("instagram") -> viaInstagram(ctx, items, to, text, label)
            appWord.startsWith("messenger") || appWord.startsWith("facebook") -> viaMessenger(ctx, items, to, text, label)
            appWord.contains("mail") -> viaEmail(ctx, items, to, a.optString("subject"), text, label)
            else -> "Which app should I send $label with: WhatsApp, WhatsApp Business, Messenger, Instagram or email?"
        }
    }

    internal fun intentFor(ctx: Context, items: List<Item>, text: String): Intent {
        val i = if (items.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        val mime = items.map { it.mime }.distinct().let { m ->
            if (m.size == 1) m[0] else m.map { it.substringBefore('/') }.distinct().let { t -> if (t.size == 1) t[0] + "/*" else "*/*" }
        }
        i.type = mime
        if (text.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = android.content.ClipData.newRawUri("file", items[0].uri)
        items.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it.uri)) }
        i.clipData = clip
        return i
    }

    /* ---- WhatsApp / WhatsApp Business ---- */

    private fun viaWhatsApp(ctx: Context, items: List<Item>, to: String, text: String, label: String, business: Boolean): String {
        val pkg = if (business) WAB else WA
        if (!installed(ctx, pkg)) return "Couldn’t find ${if (business) "WhatsApp Business" else "WhatsApp"} on this phone"
        val appName = if (business) "WhatsApp Business" else "WhatsApp"
        if (to.isBlank()) return "Who should I send $label to on $appName?"
        val target = if (to.isBlank()) null else PhoneActions.resolveContact(ctx, to)
        if (to.isNotBlank() && target == null) return "No contact or number for “$to”"
        val who = target?.first ?: to
        val i = intentFor(ctx, items, text).setPackage(pkg)
        // With a number, WhatsApp opens that chat directly with the files attached.
        target?.second?.let { n -> e164(ctx, n)?.let { d -> i.putExtra("jid", "$d@s.whatsapp.net") } }
        val direct = i.hasExtra("jid")
        if (!ctx.safeStart(i)) return "Couldn’t open $appName"
        return finishWithTaps(ctx, setOf(pkg), who, direct, "$label sent to $who on $appName.", appName,
            sendIds = listOf("$pkg:id/send"))
    }

    /* ---- Messenger / Facebook ---- */

    private fun viaMessenger(ctx: Context, items: List<Item>, to: String, text: String, label: String): String {
        val pkg = listOf("com.facebook.orca", "com.facebook.mlite").firstOrNull { installed(ctx, it) }
            ?: return "Couldn’t find Messenger on this phone"
        if (to.isBlank()) return "Who should I send $label to on Messenger?"
        if (!ctx.safeStart(intentFor(ctx, items, text).setPackage(pkg))) return "Couldn’t open Messenger"
        return finishWithTaps(ctx, setOf(pkg), to, false, "$label sent to $to on Messenger.", "Messenger", sendIds = emptyList())
    }

    /* ---- Instagram (direct message) ---- */

    private fun viaInstagram(ctx: Context, items: List<Item>, to: String, text: String, label: String): String {
        if (!installed(ctx, IG)) return "Couldn’t find Instagram on this phone"
        if (to.isBlank()) return "Who should I send $label to on Instagram?"
        val base = intentFor(ctx, items, text)
        // Instagram's own "send in a message" entry first; the general Instagram share screen if that is missing.
        val directly = Intent(base).setClassName(IG, "com.instagram.direct.share.handler.DirectShareHandlerActivity")
        val started = ctx.safeStart(directly) || ctx.safeStart(Intent(base).setPackage(IG))
        if (!started) return "Couldn’t open Instagram"
        return finishWithTaps(ctx, setOf(IG), to, false, "$label sent to $to on Instagram.", "Instagram", sendIds = emptyList())
    }

    /* ---- Email ---- */

    private fun viaEmail(ctx: Context, items: List<Item>, to: String, subject: String, text: String, label: String): String {
        val addr = if (to.isBlank()) null else Comms.emailOf(ctx, to)
        if (to.isNotBlank() && addr == null) return "No email address for “$to”"
        val i = intentFor(ctx, items, text)
        if (addr != null) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(addr))
        i.putExtra(Intent.EXTRA_SUBJECT, subject.ifBlank { items.singleOrNull()?.name ?: "Files from my phone" })
        val gmail = installed(ctx, GMAIL)
        if (gmail) i.setPackage(GMAIL)
        if (!ctx.safeStart(i)) return "Couldn’t open your email app"
        if (!gmail) return "Email with $label is ready in your mail app. Tap send (only Gmail can be sent for you)."
        if (addr == null) return "Email with $label is open in Gmail. Add who it is for and tap send."
        if (IosControlService.instance == null) {
            return "Email with $label to $addr is ready in Gmail but NOT sent. Tap send, or turn on “NEO PLUS iOS Controls” in Accessibility so I can do it."
        }
        AutoClicker.run(ctx.applicationContext, setOf(GMAIL), 45_000L,
            ok = "Email with $label sent to $addr.",
            fail = "I opened Gmail with $label for $addr but couldn’t press send, so it is NOT sent yet. Please tap send.") { root ->
            // A Gmail warning dialog ("Send anyway") would otherwise block the Send button.
            val anyway = ChatSender.firstNode(root) { n -> n.text?.toString()?.trim().equals("Send anyway", true) }
            if (anyway != null && AutoClicker.click(anyway)) return@run AutoClicker.Result.CLICKED
            // Send by id, then by its exact name, then by any visible button whose name contains "send" (other languages / versions).
            val send = findByIdOrLabel(root, listOf("$GMAIL:id/send"), "Send") ?: ChatSender.firstNode(root) { n ->
                val l = labelOf(n)
                n.isVisibleToUser && l.contains("send") && listOf("later", "schedule", "feedback", "report", "settings").none { l.contains(it) } &&
                    (n.isClickable || n.parent?.isClickable == true)
            }
            if (send == null) return@run AutoClicker.Result.WAIT
            // Gmail keeps Send switched off while the attachment is still being added: wait for it.
            if (!send.isEnabled) return@run AutoClicker.Result.WAIT
            if (AutoClicker.click(send)) return@run AutoClicker.Result.DONE
            // The button refused a normal press: touch it where it is on the screen instead.
            val r = android.graphics.Rect()
            send.getBoundsInScreen(r)
            if (!r.isEmpty && UiDriver.tapAt(r.exactCenterX(), r.exactCenterY())) AutoClicker.Result.DONE else AutoClicker.Result.WAIT
        }
        return "Sending your email with $label to $addr…"
    }

    /* ---- Pressing the buttons for the user ---- */

    private fun labelOf(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")).lowercase(Locale.ROOT)

    private fun findByIdOrLabel(root: AccessibilityNodeInfo, ids: List<String>, label: String): AccessibilityNodeInfo? {
        for (id in ids) {
            val hit = try { root.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            if (!hit.isNullOrEmpty()) return hit.first()
        }
        return ChatSender.firstNode(root) { n ->
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            t.equals(label, true) || d.equals(label, true)
        }
    }

    /**
     * Picks the contact (when the app asks who to send to) and presses Send, as long as the accessibility service is on.
     * Nothing says "sent" until a Send button was really pressed and the screen moved on.
     */
    private fun finishWithTaps(
        ctx: Context, pkgs: Set<String>, who: String, alreadyInChat: Boolean, okText: String, appName: String, sendIds: List<String>
    ): String {
        if (IosControlService.instance == null) {
            return "I opened $appName with the file, but I can’t press the buttons for you: nothing is sent yet. " +
                "${if (who.isNotBlank()) "Pick $who and tap send, or t" else "T"}urn on “NEO PLUS iOS Controls” in Accessibility so I can do it."
        }
        var picked = alreadyInChat
        var typed = false
        var searchTapped = false
        var clicks = 0
        AutoClicker.run(ctx.applicationContext, pkgs, 45_000L,
            ok = okText + " Big files can take a moment to upload.",
            fail = "I opened $appName but couldn’t finish sending to $who, so it is NOT sent yet. Please pick them and tap send.") { root ->
            val send = findByIdOrLabel(root, sendIds, "Send")
            if (picked && send != null) {
                // Sometimes there is a confirmation first and then the real Send, so keep going until Send is gone.
                return@run if (clicks >= 3) AutoClicker.Result.DONE
                else { clicks++; if (AutoClicker.click(send)) AutoClicker.Result.CLICKED else AutoClicker.Result.WAIT }
            }
            if (picked && clicks > 0) return@run AutoClicker.Result.DONE
            if (!picked && who.isNotBlank()) {
                val nameNode = ChatSender.firstNode(root) { n ->
                    !n.isEditable && labelOf(n).contains(who.lowercase(Locale.ROOT))
                }
                if (nameNode != null) {
                    picked = true
                    AutoClicker.click(nameNode)
                    return@run AutoClicker.Result.CLICKED
                }
                val field = ChatSender.firstNode(root) { it.isEditable }
                if (field != null && !typed) {
                    typed = true
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, who)
                    }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    return@run AutoClicker.Result.CLICKED
                }
                if (field == null && !searchTapped) {
                    val search = ChatSender.firstNode(root) { n -> !n.isEditable && labelOf(n).let { it.contains("search") } }
                    if (search != null) { searchTapped = true; AutoClicker.click(search); return@run AutoClicker.Result.CLICKED }
                }
            }
            AutoClicker.Result.WAIT
        }
        return "Sending to ${who.ifBlank { "the chat" }} on $appName…"
    }
}

/** Voice and video calls through WhatsApp / WhatsApp Business. */
object WhatsAppCall {
    private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private class Row(val id: Long, val mime: String, val name: String, val jid: String)

    private fun rows(ctx: Context, video: Boolean, business: Boolean): List<Row> {
        val suffix = if (video) "video.call" else "voip.call"
        val out = ArrayList<Row>()
        try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} LIKE ?", arrayOf("vnd.android.cursor.item/vnd.com.whatsapp%.$suffix"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    if (mime.contains("w4b") != business) continue
                    out += Row(c.getLong(0), mime, c.getString(2) ?: "", c.getString(3) ?: "")
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun call(ctx: Context, to: String, video: Boolean, business: Boolean): String {
        val pkg = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        val appName = if (business) "WhatsApp Business" else "WhatsApp"
        val kind = if (video) "video call" else "call"
        try { ctx.packageManager.getPackageInfo(pkg, 0) } catch (e: Exception) { return "Couldn’t find $appName on this phone" }
        if (to.isBlank()) return "Who should I $kind on $appName?"
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return "Allow Contacts for Neo Plus, then ask again"
        val target = PhoneActions.resolveContact(ctx, to) ?: return "No contact or number for “$to”"
        val digits = target.second.filter { it.isDigit() }
        val all = rows(ctx, video, business)
        val hit = all.firstOrNull { it.name.equals(target.first, true) }
            ?: all.firstOrNull { digits.length >= 7 && it.jid.filter { ch -> ch.isDigit() }.let { j -> j.length >= 7 && (j.endsWith(digits.takeLast(9)) || digits.endsWith(j.takeLast(9))) } }
            ?: all.firstOrNull { it.name.contains(target.first, true) }
        if (hit != null) {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://com.android.contacts/data/${hit.id}"), hit.mime)
                .setPackage(pkg)
            if (ctx.safeStart(i)) return "Starting a $appName $kind with ${target.first}"
        }
        // Not synced to WhatsApp's own contact entries: open the chat and press the call button.
        if (IosControlService.instance == null || digits.isEmpty())
            return "Couldn’t start the $appName $kind: ${target.first} isn’t in WhatsApp’s contacts yet. Open the chat and tap the call button."
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(pkg)
        if (!ctx.safeStart(i)) return "Couldn’t open $appName"
        var clicks = 0
        AutoClicker.run(ctx.applicationContext, setOf(pkg), 20_000L,
            ok = "Starting a $appName $kind with ${target.first}.",
            fail = "I opened the chat with ${target.first} but couldn’t press the call button. Please tap it yourself.",
            goHome = false) { root ->
            val want = if (video) "video call" else "voice call"
            val btn = ChatSender.firstNode(root) { n ->
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                d == want || d == "call" && !video
            }
            if (btn != null && clicks == 0 && AutoClicker.click(btn)) { clicks++; AutoClicker.Result.CLICKED }
            else if (clicks > 0) {
                // WhatsApp may ask "Call X?" first
                val ok = ChatSender.firstNode(root) { n -> n.text?.toString()?.trim().equals("Call", true) || n.text?.toString()?.trim().equals("Video call", true) }
                if (ok != null) AutoClicker.click(ok)
                AutoClicker.Result.DONE
            } else AutoClicker.Result.WAIT
        }
        return "Starting a $appName $kind with ${target.first}…"
    }
}
