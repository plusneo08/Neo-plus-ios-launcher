package com.neo.plus.iosluancher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Reads messages straight from the app itself: Messages, WhatsApp / WhatsApp Business, Messenger, Facebook, TikTok,
 * Instagram, email (Gmail, Outlook, ...) and Gemini. First the waiting notifications, then the app is opened and what it
 * shows (the chat list, or one chat when a name is given) is read through the accessibility service. Blocks, so call it
 * from a background thread (the assistant already does). Password fields are never read.
 */
object AppReader {
    private class Target(val key: String, val label: String, val pkgs: List<String>, val tab: List<String> = emptyList())

    private val targets = listOf(
        Target("whatsappbusiness", "WhatsApp Business", listOf("com.whatsapp.w4b")),
        Target("whatsapp", "WhatsApp", listOf("com.whatsapp")),
        Target("messenger", "Messenger", listOf("com.facebook.orca", "com.facebook.mlite")),
        Target("facebook", "Facebook", listOf("com.facebook.katana", "com.facebook.lite"), listOf("~notifications")),
        Target("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"), listOf("inbox", "~inbox")),
        Target("instagram", "Instagram", listOf("com.instagram.android", "com.instagram.lite"), listOf("~messenger", "~messages", "~direct")),
        Target("gemini", "Gemini", listOf("com.google.android.apps.bard")),
        Target("messages", "Messages", listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms")),
        Target("email", "Email", listOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.samsung.android.email.provider", "com.android.email", "ch.protonmail.android", "com.fsck.k9"
        ))
    )

    private fun targetFor(raw: String): Target? {
        val c = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        val k = when {
            c.contains("business") -> "whatsappbusiness"
            c.startsWith("whatsapp") -> "whatsapp"
            c.startsWith("messenger") -> "messenger"
            c.startsWith("facebook") -> "facebook"
            c.startsWith("tiktok") -> "tiktok"
            c.startsWith("insta") -> "instagram"
            c.startsWith("gemini") -> "gemini"
            c.contains("mail") || c == "inbox" -> "email"
            c.contains("message") || c == "sms" || c.startsWith("text") -> "messages"
            else -> return null
        }
        return targets.first { it.key == k }
    }

    /** True for the phone's own text-message app, whose SMS can also be read straight from the phone's database. */
    fun isSms(raw: String): Boolean = targetFor(raw)?.key == "messages"

    private fun installed(ctx: Context, t: Target): List<String> = t.pkgs.filter {
        try { ctx.packageManager.getLaunchIntentForPackage(it) != null } catch (e: Exception) { false }
    }

    fun read(ctx: Context, raw: String, from: String, count: Int): String {
        val t = targetFor(raw) ?: return "I don’t know how to read “$raw”"
        val pkgs = installed(ctx, t)
        if (pkgs.isEmpty()) return "${t.label} is not installed"
        val out = StringBuilder()

        // 1) What is waiting in the notifications (works without opening the app).
        try { NeoNotificationService.instance?.activeNotifications?.forEach { NotifReader.remember(ctx, it) } } catch (e: Exception) { }
        val notes = NotifStore.recentFrom(pkgs, from, count)
        if (notes.isNotEmpty()) {
            out.append("New in ${t.label}:\n")
            notes.forEach {
                val head = if (it.title.isBlank() || it.title.equals(it.app, true)) "" else it.title + ": "
                out.append("• ").append(head).append(it.text.replace("\n", " ").take(240)).append('\n')
            }
        }

        // 2) Open the app and read what it shows.
        if (IosControlService.instance == null) {
            if (out.isEmpty()) return UiDriver.NEED_SERVICE
            return out.toString().trim() + "\n(Turn on “NEO PLUS iOS Controls” in Accessibility and I can also read inside ${t.label}.)"
        }
        val screen = readInApp(ctx, t, pkgs, from)
        if (screen != null) out.append("In ${t.label}:\n").append(screen)
        if (out.isEmpty()) return "I couldn’t read anything in ${t.label}. Make sure you are signed in and it opens to your messages"
        return out.toString().trim()
    }

    private fun readInApp(ctx: Context, t: Target, pkgs: List<String>, from: String): String? {
        val set = pkgs.toSet()
        val launch = pkgs.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!ctx.safeStart(launch)) return null
        UiDriver.waitFor(ctx, set, 9000) { it } ?: return null
        sleep(1300) // let the list load

        // An app that reopens inside a chat: step back to the list first (unless a chat was asked for).
        if (from.isBlank() && t.key in setOf("whatsapp", "whatsappbusiness", "messenger", "messages", "instagram")) {
            val r = UiDriver.rootOf(ctx, set)
            if (r != null && UiDriver.findEditable(r) != null) { IosControlService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); sleep(900) }
        }
        // Go to the right tab (Facebook notifications, TikTok inbox, Instagram messages).
        for (label in t.tab) {
            val r = UiDriver.rootOf(ctx, set) ?: break
            val n = UiDriver.findAny(r, listOf(label))
            if (n != null) { UiDriver.tap(n); sleep(1300); break }
        }
        // Open one conversation when a name was given.
        if (from.isNotBlank()) {
            val r = UiDriver.rootOf(ctx, set)
            val n = r?.let { UiDriver.find(UiDriver.all(it), "~" + from.trim()) }
            if (n != null) { UiDriver.tap(n); sleep(1600) }
        }
        val text = screenText(ctx, set)
        IosControlService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) // leave the phone as it was
        return text
    }

    /** Visible text in reading order (top to bottom); typed text boxes and passwords are skipped. */
    private fun screenText(ctx: Context, set: Set<String>): String? {
        val root = UiDriver.rootOf(ctx, set) ?: return null
        val sb = StringBuilder()
        var prev = ""
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 2200 || visited++ > 3000) return
            if (!n.isPassword && n.isVisibleToUser && !n.isEditable) {
                val s = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (s.isNotEmpty() && s != prev && s.length < 500) { sb.append(s.replace("\n", " ")).append('\n'); prev = s }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString().trim().ifEmpty { null }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }
}
