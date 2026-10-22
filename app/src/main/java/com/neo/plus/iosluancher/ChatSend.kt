package com.neo.plus.iosluancher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Really sends WhatsApp and Facebook Messenger messages.
 *
 * 1. If the chat has a live notification, answer through its own Reply action (most reliable).
 * 2. Otherwise open the chat with the text filled in and press Send through the accessibility service.
 *
 * Results are announced through [Announcer] only after the outcome is known; nothing says "sent" unless a
 * reply or the Send button was really triggered.
 */
object ChatSender {
    private val WA = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val MESSENGER = listOf("com.facebook.orca", "com.facebook.mlite")
    private val FB_ALL = listOf("com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.facebook.lite")

    /** Which Facebook app(s) the user means: "messenger", "messenger lite", "facebook", "facebook lite" (blank = any). */
    private fun appPkgs(app: String): List<String> {
        val a = app.lowercase()
        return when {
            a.isBlank() -> FB_ALL
            "messenger" in a && "lite" in a -> listOf("com.facebook.mlite")
            "messenger" in a -> listOf("com.facebook.orca")
            "lite" in a -> listOf("com.facebook.lite")
            else -> listOf("com.facebook.katana")
        }
    }

    private fun installed(ctx: Context, pkg: String) =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun replyLive(ctx: Context, pkgs: List<String>, who: List<String>, text: String): String? {
        val n = NotifStore.findIn(pkgs, who) ?: return null
        val name = n.title.ifBlank { n.app }
        return if (NotifStore.sendNote(ctx, n, text)) "Replied to $name on ${n.app}" else null
    }

    fun whatsapp(ctx: Context, label: String, spoken: String, digits: String, text: String, business: Boolean = false): String {
        val WA = if (business) listOf("com.whatsapp.w4b") else this.WA
        val body = text.trim()
        if (body.isEmpty()) return "No message text given"
        replyLive(ctx, WA, listOf(label, spoken, digits), body)?.let { return it }

        val pkg = WA.firstOrNull { installed(ctx, it) }
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(body))
        val i = Intent(Intent.ACTION_VIEW, uri).apply { if (pkg != null) setPackage(pkg) }
        if (!ctx.safeStart(i)) return "Couldn’t open WhatsApp"

        if (IosControlService.instance == null) {
            return "Couldn’t press send for you: the message to $label is typed in WhatsApp but NOT sent. " +
                "Tap send, or turn on “NEO PLUS iOS Controls” in Accessibility so I can do it."
        }
        val app = ctx.applicationContext
        AutoClicker.run(app, WA.toSet(), 18_000L,
            ok = "Message to $label sent on WhatsApp.",
            fail = "I opened WhatsApp for $label but couldn’t press send, so the message is NOT sent yet. Please tap send.") { root ->
            val send = findSend(root, WA.map { "$it:id/send" })
            if (send != null && AutoClicker.click(send)) AutoClicker.Result.DONE else AutoClicker.Result.WAIT
        }
        return "Sending your WhatsApp message to $label…"
    }

    fun messenger(ctx: Context, to: String, text: String, app: String = ""): String {
        val body = text.trim()
        if (body.isEmpty()) return "No message text given"
        val who = to.trim()
        // Answer through the notification of whichever Facebook app delivered the message (Facebook, Facebook Lite,
        // Messenger or Messenger Lite): the reply goes straight back into that app's conversation.
        replyLive(ctx, appPkgs(app), listOf(who), body)?.let { return it }
        if (who.isBlank()) return "No Facebook message to reply to"

        // No live notification: type it into Messenger (the one that last delivered a message, else any installed).
        val preferred = NotifStore.lastPkg(MESSENGER)
        val pkg = (listOfNotNull(preferred) + MESSENGER).filter { it in appPkgs(app) || app.isBlank() }.firstOrNull { installed(ctx, it) }
            ?: return "Couldn’t find ${if (app.isBlank()) "Messenger" else app} on this phone"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).setPackage(pkg)
        if (!ctx.safeStart(i)) return "Couldn’t open Messenger"

        if (IosControlService.instance == null) {
            return "Couldn’t press send for you: Messenger is open with your message but it is NOT sent. " +
                "Pick $who and tap send, or turn on “NEO PLUS iOS Controls” in Accessibility so I can do it."
        }
        var typed = false
        var picked = false
        AutoClicker.run(ctx.applicationContext, setOf(pkg), 25_000L,
            ok = "Message to $who sent on Messenger.",
            fail = "I opened Messenger but couldn’t finish sending to $who, so the message is NOT sent yet. Please pick them and tap send.") { root ->
            val nameNode = firstNode(root) { n ->
                !n.isEditable && ((n.text?.toString()?.contains(who, true) == true) || (n.contentDescription?.toString()?.contains(who, true) == true))
            }
            if (nameNode != null) {
                // Some layouts show a Send button on the contact's own row.
                var row: AccessibilityNodeInfo? = nameNode
                var up = 0
                while (row != null && up < 5) {
                    val btn = firstNode(row) { isSendLabel(it) }
                    if (btn != null) return@run if (AutoClicker.click(btn)) AutoClicker.Result.DONE else AutoClicker.Result.WAIT
                    row = row.parent; up++
                }
                if (!picked) {
                    picked = true
                    AutoClicker.click(nameNode)
                    return@run AutoClicker.Result.CLICKED
                }
                val send = firstNode(root) { isSendLabel(it) }
                if (send != null) return@run if (AutoClicker.click(send)) AutoClicker.Result.DONE else AutoClicker.Result.WAIT
            } else if (!typed) {
                val field = firstNode(root) { it.isEditable }
                if (field != null) {
                    typed = true
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, who)
                    }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    return@run AutoClicker.Result.CLICKED
                }
            }
            AutoClicker.Result.WAIT
        }
        return "Sending your Messenger message to $who…"
    }

    private fun isSendLabel(n: AccessibilityNodeInfo): Boolean {
        val t = n.text?.toString()?.trim()
        val d = n.contentDescription?.toString()?.trim()
        return t.equals("Send", true) || d.equals("Send", true)
    }

    private fun findSend(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            val hit = try { root.findAccessibilityNodeInfosByViewId(id) } catch (e: Exception) { null }
            if (!hit.isNullOrEmpty()) return hit.first()
        }
        return firstNode(root) { isSendLabel(it) }
    }

    internal fun firstNode(root: AccessibilityNodeInfo, depth: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            firstNode(c, depth + 1, pred)?.let { return it }
        }
        return null
    }
}

/** Polls the foreground window (accessibility) and runs [onTick] until it finishes or times out. */
object AutoClicker {
    enum class Result { WAIT, CLICKED, DONE }

    private val thread = HandlerThread("neo-autosend").apply { start() }
    private val h = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())

    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var up = 0
        while (n != null && !n.isClickable && up < 6) { n = n.parent; up++ }
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun run(
        ctx: Context, pkgs: Set<String>, timeoutMs: Long, ok: String, fail: String, goHome: Boolean = true,
        onTick: (AccessibilityNodeInfo) -> Result
    ) {
        val start = System.currentTimeMillis()
        lateinit var tick: Runnable
        tick = Runnable {
            val svc = IosControlService.instance
            if (svc == null || System.currentTimeMillis() - start > timeoutMs) {
                Announcer.say(ctx, fail); return@Runnable
            }
            var delay = 450L
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null }
            if (root != null && (root.packageName?.toString() ?: "") in pkgs) {
                when (try { onTick(root) } catch (e: Exception) { Result.WAIT }) {
                    Result.DONE -> {
                        Announcer.say(ctx, ok)
                        if (goHome) ui.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }, 900)
                        return@Runnable
                    }
                    Result.CLICKED -> delay = 900L
                    Result.WAIT -> Unit
                }
            }
            h.postDelayed(tick, delay)
        }
        h.postDelayed(tick, 1200L) // give the app time to open
    }
}
