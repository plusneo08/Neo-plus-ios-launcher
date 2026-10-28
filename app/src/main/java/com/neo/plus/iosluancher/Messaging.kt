package com.neo.plus.iosluancher

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Remembers recent chat notifications (WhatsApp, Messages, Telegram...) together with their built-in "Reply" action,
 * so the assistant can read them out and answer in the same conversation when the user says so.
 */
object NotifStore {
    class Msg(val key: String, val app: String, val sender: String, val text: String, val time: Long, val reply: Notification.Action?, val pkg: String = "")

    private val msgs = LinkedHashMap<String, Msg>()

    private val skipCategories = setOf(
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM
    )

    @Synchronized
    fun record(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == ctx.packageName) return
        val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skipFlags != 0) return
        if (n.category in skipCategories) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (text.isBlank()) return
        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        if (reply == null && n.category != Notification.CATEGORY_MESSAGE) return
        val pm = ctx.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        msgs.remove(sbn.key)
        msgs[sbn.key] = Msg(sbn.key, app, title.ifBlank { app }, text, sbn.postTime, reply, sbn.packageName)
        while (msgs.size > 30) msgs.remove(msgs.keys.first())
    }

    /** The last five lines of each conversation (incoming messages and our own replies), oldest first. */
    private val convo = LinkedHashMap<String, ArrayList<String>>()

    @Synchronized
    fun convoAdd(pkg: String, who: String, line: String) {
        val k = pkg + "|" + who.trim().lowercase()
        val l = convo.getOrPut(k) { ArrayList() }
        if (l.lastOrNull() != line) l.add(line)
        while (l.size > 5) l.removeAt(0)
        while (convo.size > 100) convo.remove(convo.keys.first())
    }

    @Synchronized
    fun convoLast(pkg: String, who: String): List<String> =
        convo[pkg + "|" + who.trim().lowercase()]?.takeLast(5)?.toList() ?: emptyList()

    @Synchronized
    fun convoClear() = convo.clear()

    /** Every notification (not only chats), so the assistant can read them without opening the notification panel. */
    class Note(val key: String, val app: String, val title: String, val text: String, val time: Long, val reply: Notification.Action? = null, val pkg: String = "")

    private val all = LinkedHashMap<String, Note>()

    @Synchronized
    fun recordAll(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == ctx.packageName) return
        val skipFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skipFlags != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        val pm = ctx.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        all.remove(sbn.key)
        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        all[sbn.key] = Note(sbn.key, app, title, text, sbn.postTime, reply, sbn.packageName)
        while (all.size > 100) all.remove(all.keys.first())
    }

    /** Latest notification from any app that can be answered inline (chat, email...), whose sender or app matches [who]. */
    @Synchronized
    fun findNote(who: String): Note? = all.values.filter {
        it.reply != null && (who.isBlank() || it.title.contains(who.trim(), true) || it.app.contains(who.trim(), true))
    }.maxByOrNull { it.time }

    /** Newest-first notifications from one of [pkgs]; [who] (optional) keeps only senders whose name matches. */
    @Synchronized
    fun recentFrom(pkgs: Collection<String>, who: String, count: Int): List<Note> =
        all.values.filter { it.pkg in pkgs && (who.isBlank() || it.title.contains(who.trim(), true)) }
            .sortedByDescending { it.time }.take(count)

    @Synchronized fun noteByKey(key: String?): Note? = if (key == null) null else all[key]

    fun sendNote(ctx: Context, n: Note, text: String): Boolean = sendAction(ctx, n.reply, text)

    /** Newest first; [app] (optional) keeps only notifications from apps whose name contains it. */
    @Synchronized
    fun allRecent(count: Int, app: String): List<Note> =
        all.values.filter { app.isBlank() || it.app.contains(app.trim(), ignoreCase = true) }
            .sortedByDescending { it.time }.take(count)

    @Synchronized fun recent(count: Int): List<Msg> = msgs.values.toList().takeLast(count).reversed()

    @Synchronized fun byKey(key: String?): Msg? = if (key == null) null else msgs[key]

    /** Latest message we can reply to, from a sender whose name contains [who] (any sender when blank). */
    @Synchronized
    fun find(who: String): Msg? = msgs.values.lastOrNull {
        it.reply != null && (who.isBlank() || it.sender.contains(who.trim(), ignoreCase = true))
    }

    private fun nameMatches(title: String, who: String): Boolean {
        val w = who.trim()
        if (w.isEmpty()) return false
        if (title.contains(w, ignoreCase = true)) return true
        val wd = w.filter { it.isDigit() }
        val td = title.filter { it.isDigit() }
        return wd.length >= 7 && td.length >= 7 && (td.endsWith(wd.takeLast(9)) || wd.endsWith(td.takeLast(9)))
    }

    /** Newest answerable notification from one of [pkgs] (e.g. WhatsApp) whose sender matches any of [who]. */
    @Synchronized
    fun findIn(pkgs: Collection<String>, who: List<String>): Note? = all.values.filter { n ->
        n.reply != null && n.pkg in pkgs && (who.none { it.isNotBlank() } || who.any { nameMatches(n.title, it) })
    }.maxByOrNull { it.time }

    /** Package of the newest notification delivered by one of [pkgs], or null. */
    @Synchronized
    fun lastPkg(pkgs: Collection<String>): String? = all.values.filter { it.pkg in pkgs }.maxByOrNull { it.time }?.pkg

    /** Sends [text] through the notification's own Reply action, i.e. into the same chat. */
    fun send(ctx: Context, m: Msg, text: String): Boolean = sendAction(ctx, m.reply, text)

    fun sendAction(ctx: Context, a: Notification.Action?, text: String): Boolean {
        a ?: return false
        val inputs = a.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val b = Bundle()
            inputs.forEach { b.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, b)
            a.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}
