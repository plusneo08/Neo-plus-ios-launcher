package com.neo.plus.iosluancher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Calls, SMS, WhatsApp / WhatsApp Business, Facebook / Messenger, email, notifications and call history.
 * These are understood and carried out by the phone itself (Android APIs + notification replies), so a request like
 * "text Mum I'm late" never goes to the AI model, even when a Gemini key is set.
 */
object Comms {
    private val I = RegexOption.IGNORE_CASE
    private val lead = Regex("^(?:hey |ok |okay )?(?:(?:neo plus assistant|neo plus|neo)[, ]+)?(?:please |can you |could you |would you )?", I)

    // channel words -> action
    private const val CH = "whats ?app business|whats ?app|facebook lite|facebook|messenger lite|messenger|e-?mail|gmail|sms|text message|text|message"
    private const val SAY = "(?:saying|says|that says|and say|and tell (?:him|her|them)|with the message|that|:)"

    private fun channelAction(raw: String, name: String, body: String, subject: String = ""): Pair<String, JSONObject> {
        val c = raw.lowercase(Locale.ROOT).replace(" ", "")
        return when {
            c.startsWith("whatsapp") -> "whatsapp" to JSONObject().put("to", name).put("text", body).put("business", "business" in c)
            c.startsWith("facebook") || c.startsWith("messenger") ->
                "messenger" to JSONObject().put("to", name).put("text", body).put("app", raw.lowercase(Locale.ROOT).trim())
            c.contains("mail") -> "email" to JSONObject().put("to", name).put("subject", subject).put("text", body)
            else -> "message" to JSONObject().put("to", name).put("text", body)
        }
    }

    /** The chat box adds "[Attached file: name]" for every file the user attached to the message. */
    private val attachTag = Regex("\\[Attached file: ([^\\]]+)\\]")
    private const val SHARE_CH = "whats ?app business|whats ?app|messenger|facebook|instagram|e-?mail|gmail"

    private fun shareAction(app: String, files: List<String>, name: String, body: String): Pair<String, JSONObject> =
        "share_file" to JSONObject().put("files", JSONArray(files)).put("to", name).put("app", app.lowercase(Locale.ROOT).trim()).put("text", body)

    fun plan(text: String): LocalPlan? {
        val attached = attachTag.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val t = text.replace(attachTag, " ").trim().replace(lead, "").trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null
        val low = t.lowercase(Locale.ROOT)

        // ---- WhatsApp / WhatsApp Business calls (voice or video) ----
        Regex("^(?:make a |start a |place a )?(video |voice )?(?:call|phone|ring|dial)\\s+(?:to\\s+)?(.+?)\\s+(?:on|via|using|through|with|over)\\s+(whats ?app business|whats ?app)(?:\\s+(video|voice)(?: call)?)?$", I).find(t)?.let { m ->
            val video = m.groupValues[1].trim().equals("video", true) || m.groupValues[4].equals("video", true)
            return LocalPlan("", listOf("whatsapp_call" to JSONObject().put("to", m.groupValues[2].trim()).put("video", video)
                .put("business", m.groupValues[3].contains("business", true))))
        }
        Regex("^whats ?app (business )?(video |voice )?call\\s+(?:to\\s+)?(.+)$", I).find(t)?.let { m ->
            return LocalPlan("", listOf("whatsapp_call" to JSONObject().put("to", m.groupValues[3].trim())
                .put("video", m.groupValues[2].trim().equals("video", true)).put("business", m.groupValues[1].isNotBlank())))
        }

        // ---- sending a file, photo, video or song: "send this to Mum on WhatsApp", "send my latest photo to Joe on Instagram" ----
        Regex("^(?:send|share|forward|whatsapp|text|message)\\s+(?:(?:this|that|these|those|it|them)|(.+?))\\s+(?:to|with)\\s+(.+?)\\s+(?:on|via|through|using|by|over)\\s+($SHARE_CH)(?:\\s*$SAY\\s*(.*))?$", I).find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            val files = if (attached.isNotEmpty()) attached else if (what.isNotEmpty() && MediaFinder.looksLikeMedia(what)) listOf(what) else emptyList()
            if (files.isNotEmpty()) return LocalPlan("", listOf(shareAction(m.groupValues[3], files, m.groupValues[2].trim(), m.groupValues[4].trim())))
        }
        Regex("^(?:email|e-?mail|mail)\\s+(?:(?:this|that|these|those|it|them)|(.+?))\\s+to\\s+(.+?)(?:\\s*$SAY\\s*(.*))?$", I).find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            val files = if (attached.isNotEmpty()) attached else if (what.isNotEmpty() && MediaFinder.looksLikeMedia(what)) listOf(what) else emptyList()
            if (files.isNotEmpty()) return LocalPlan("", listOf(shareAction("email", files, m.groupValues[2].trim(), m.groupValues[3].trim())))
        }
        if (attached.isNotEmpty()) {
            Regex("^(?:send|share|forward)\\s+(?:this|that|these|those|it|them|the files?)\\s+to\\s+(.+)$", I).find(t)?.let { m ->
                return LocalPlan("", listOf(shareAction("", attached, m.groupValues[1].trim(), "")))
            }
        }

        // ---- "send this to <n> on <app> <text or link>" with nothing attached: the rest of the sentence is the message ----
        Regex("^(?:send|share|forward)\\s+(?:this|that|it)\\s+to\\s+(.+?)\\s+(?:on|via|through|using|by|over)\\s+(whats ?app business|whats ?app|messenger lite|messenger|facebook lite|facebook)\\s*(?:$SAY\\s*)?(.+)$", I).find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val body = m.groupValues[3].trim()
            if (name.isNotEmpty() && body.isNotEmpty()) return LocalPlan("", listOf(channelAction(m.groupValues[2], name, body)))
        }

        // ---- calls ----
        Regex("^(?:call|phone|ring|dial)\\s+(.+?)(?:\\s+(?:on|using|with|via)\\s+sim\\s*(1|2|one|two))?$", I).find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            if (who.isNotEmpty() && !who.startsWith("back", true) && who.split(" ").size <= 5) {
                val sim = when (m.groupValues[2].lowercase(Locale.ROOT)) { "1", "one" -> 1; "2", "two" -> 2; else -> 0 }
                return LocalPlan("", listOf("call" to JSONObject().put("to", who).put("sim", sim)))
            }
        }
        // "SIM 1" / "SIM 2" answers are handled by the caller's follow-up; nothing to do here.

        // ---- call history ----
        if (Regex("^(?:(?:show|read|check|what(?:'s| is| are)?) (?:my |the )?)?(?:missed calls?|call log|call history|recent calls)$|^who (?:called|rang|phoned)(?: me)?(?: last)?$|^(?:did|have) (?:anyone|someone) (?:call|called|rang)(?:ed)?(?: me)?$", I).containsMatchIn(low))
            return LocalPlan("", listOf("call_log" to JSONObject().put("count", 5)))

        // ---- reply to the latest message / notification ----
        Regex("^(?:reply|respond|answer)(?: to (.+?))?\\s*$SAY\\s*(.+)$|^(?:reply|respond|answer)(?: to (.+?))?\\s+(?:with\\s+)(.+)$", I).find(t)?.let { m ->
            val to = m.groupValues[1].ifBlank { m.groupValues[3] }.trim()
            val body = m.groupValues[2].ifBlank { m.groupValues[4] }.trim()
            if (body.isNotEmpty()) return LocalPlan("", listOf("reply_message" to JSONObject().put("to", to).put("text", body)))
        }

        // ---- send: "message <name> on <channel> saying <text>" ----
        Regex("^(?:send |text |message |tell |email |write to )(?:a message to |an email to |a text to |to )?(.+?)\\s+(?:on|via|through|by|using)\\s+($CH)\\s*$SAY\\s*(.+)$", I).find(t)?.let { m ->
            return LocalPlan("", listOf(channelAction(m.groupValues[2], m.groupValues[1].trim(), m.groupValues[3].trim())))
        }
        // ---- send: "<channel> <name> saying <text>" ----
        Regex("^(?:send |write |compose )?(?:a |an |the )?($CH)(?: message| to)?\\s+(?:to\\s+)?(.+?)\\s*$SAY\\s*(.+)$", I).find(t)?.let { m ->
            val name = m.groupValues[2].trim()
            if (name.isNotEmpty()) return LocalPlan("", listOf(channelAction(m.groupValues[1], name, m.groupValues[3].trim())))
        }
        // ---- send: "send <text> to <name> on <channel>" ----
        Regex("^send (?:a message |the message |message )?(.+?) to (.+?) (?:on|via|through|by|using) ($CH)$", I).find(t)?.let { m ->
            return LocalPlan("", listOf(channelAction(m.groupValues[3], m.groupValues[2].trim(), m.groupValues[1].trim().trim('"', '“', '”'))))
        }
        // ---- default: plain SMS ----
        Regex("^(?:text|sms|message|tell|send (?:a )?(?:text|sms|message) to|send an? (?:text|sms|message) to)\\s+(.+?)\\s*$SAY\\s*(.+)$", I).find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && name.split(" ").size <= 5)
                return LocalPlan("", listOf("message" to JSONObject().put("to", name).put("text", m.groupValues[2].trim())))
        }
        // ---- email with subject ----
        Regex("^(?:send |write )?(?:an? )?e-?mail to (.+?) (?:with )?subject (.+?)\\s*$SAY\\s*(.+)$", I).find(t)?.let { m ->
            return LocalPlan("", listOf("email" to JSONObject().put("to", m.groupValues[1].trim()).put("subject", m.groupValues[2].trim()).put("text", m.groupValues[3].trim())))
        }

        // ---- read messages / notifications / email ----
        Regex("^(?:read|check|show|what(?:'s| is| are)|any|do i have any|have i got any|did i get any)(?: out)?(?: all)?(?: of)?(?: my| the| new| unread| latest| recent)* ?(whats ?app business|whats ?app|facebook lite|facebook|messenger lite|messenger|tik ?tok|insta(?:gram)?|gemini|google messages|samsung messages|messages app|e-?mails?|gmail|inbox|sms|texts?|text messages?|chats?|messages?|missed calls?)(?: messages?| notifications?| replies| chats?| dms?| inbox| e-?mails?)?(?: from (.+))?$", I).find(t)?.let { m ->
            val ch = m.groupValues[1].lowercase(Locale.ROOT).replace(" ", "")
            val from = m.groupValues[2].trim()
            return LocalPlan("", listOf(when {
                ch.startsWith("missedcall") -> "call_log" to JSONObject().put("count", 5)
                ch == "sms" || ch.startsWith("text") -> "read_sms" to JSONObject().put("count", 3).put("from", from)
                ch.startsWith("whatsapp") || ch.startsWith("facebook") || ch.startsWith("messenger") || ch.startsWith("tiktok") ||
                    ch.startsWith("insta") || ch.startsWith("gemini") || ch.contains("mail") || ch == "inbox" ||
                    ch.endsWith("messages") && ch != "messages" || ch == "messagesapp" ->
                    "read_app" to JSONObject().put("count", 8).put("app", ch).put("from", from)
                else -> "read_messages" to JSONObject().put("count", 5)
            }))
        }
        Regex("^(?:read|check|show) (?:my |the )?(?:last|latest) (?:sms|text|message)(?: from (.+))?$", I).find(t)?.let { m ->
            return LocalPlan("", listOf("last_message" to JSONObject().put("with", m.groupValues[1].trim())))
        }
        if (Regex("^(?:read|check|show|what(?:'s| are| is)) (?:me )?(?:my |the )?(?:new |unread |latest |recent )?notifications?$|^(?:do i have|have i got|any) (?:any )?(?:new )?notifications?$", I).matches(low))
            return LocalPlan("", listOf("read_notifications" to JSONObject().put("count", 8)))
        // "did anyone send any message", "has anybody texted me", "who messaged me"
        if (Regex("^(?:did|has|have|do|does)\\s+(?:anyone|anybody|someone|somebody|any ?one|people)(?:\\s+else)?\\s+(?:send|sent|text|texted|message|messaged|write|wrote|email|emailed|whatsapp(?:ed)?|reach)(?:\\s+me)?(?:\\s+(?:any|a|an|new|something|anything))*(?:\\s+(?:messages?|texts?|e-?mails?|whatsapps?))?(?:\\s+(?:today|yet|lately|recently|now))?$|^who (?:sent|texted|messaged|wrote to|emailed)(?: me)?(?: (?:anything|a message|any messages?|today|lately|recently))*$", I).matches(low))
            return LocalPlan("", listOf("read_messages" to JSONObject().put("count", 5)))
        return null
    }

    /* ---- email, done by the phone ---- */

    internal fun emailOf(ctx: Context, to: String): String? {
        val t = to.trim()
        if (t.contains("@")) return t
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?", arrayOf("%$t%"), null
        ) ?: return null
        c.use { return if (it.moveToFirst()) it.getString(0) else null }
    }

    /** Replies in the mail notification when there is one with a reply action, otherwise opens a filled-in draft. */
    fun email(ctx: Context, to: String, subject: String, text: String): String {
        if (text.isBlank()) return "No email text given"
        NotifStore.findNote(to)?.let { n ->
            if (n.reply != null && n.app.contains("mail", true)) {
                return if (NotifStore.sendNote(ctx, n, text.trim())) "Replied to ${n.title.ifBlank { n.app }} on ${n.app}"
                else "Couldn’t send the reply to ${n.title.ifBlank { n.app }}"
            }
        }
        val addr = emailOf(ctx, to) ?: return "No email address for “$to”"
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(addr)))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text.trim())
        return if (ctx.safeStart(i)) "Email to $addr is ready in your mail app — tap send (Android does not let apps send email silently)"
        else "Couldn’t open your email app"
    }
}
