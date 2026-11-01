package com.neo.plus.iosluancher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Task reports by EMAIL, sent from the person's own mailbox over SMTP. No SIM, no airtime, nothing is read or received.
 * The mailbox password (an app password) is stored encrypted with the Android Keystore, the same way website logins are.
 */
object EmailReport {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("email_report", Context.MODE_PRIVATE)

    fun from(ctx: Context): String = p(ctx).getString("from", "") ?: ""
    fun to(ctx: Context): String = p(ctx).getString("to", "") ?: ""
    fun hasPassword(ctx: Context): Boolean = !p(ctx).getString("pw", "").isNullOrBlank()
    fun configured(ctx: Context): Boolean = from(ctx).isNotBlank() && hasPassword(ctx)
    fun lastError(ctx: Context): String = p(ctx).getString("err", "") ?: ""
    /** A custom mail server ("smtp.example.com" or "smtp.example.com:587"), only used for providers we don't know. */
    fun host(ctx: Context): String = p(ctx).getString("host", "") ?: ""

    /** The name of the well-known provider behind an address, or "" when it is not one we set up automatically. */
    fun providerName(email: String): String {
        val d = email.substringAfter('@', "").trim().lowercase(Locale.ROOT)
        return when {
            d == "gmail.com" || d == "googlemail.com" -> "Gmail"
            d == "outlook.com" || d == "hotmail.com" || d == "live.com" || d == "msn.com" || d.startsWith("outlook.") || d.startsWith("hotmail.") -> "Outlook"
            d.startsWith("yahoo.") || d == "ymail.com" -> "Yahoo"
            d == "icloud.com" || d == "me.com" -> "iCloud"
            d == "zoho.com" -> "Zoho"
            else -> ""
        }
    }

    /** The page where this provider lets you create an app password. */
    fun appPasswordUrl(email: String): String = when (providerName(email)) {
        "Gmail" -> "https://myaccount.google.com/apppasswords"
        "Outlook" -> "https://account.live.com/proofs/AppPassword"
        "Yahoo" -> "https://login.yahoo.com/account/security/app-passwords"
        "iCloud" -> "https://appleid.apple.com/account/manage"
        "Zoho" -> "https://accounts.zoho.com/home#security/app_password"
        else -> ""
    }

    private val hostRx = Regex("^[A-Za-z0-9.-]+(:\\d{1,5})?$")

    private fun serverFor(ctx: Context, from: String): SmtpMail.Server {
        val h = host(ctx).trim()
        if (h.isBlank()) return SmtpMail.serverFor(from)
        val name = h.substringBefore(':')
        val port = h.substringAfter(':', "465").toIntOrNull() ?: 465
        return SmtpMail.Server(name, port, if (port == 465) SmtpMail.Security.SSL else SmtpMail.Security.STARTTLS)
    }

    /** Saves the settings. A blank [from] turns email reports off. A blank [appPassword] keeps the one already saved. Returns a message. */
    fun save(ctx: Context, from: String, appPassword: String, to: String, host: String = ""): String {
        val f = from.trim()
        if (f.isBlank()) {
            p(ctx).edit().clear().apply()
            return "Email reports are off."
        }
        if (!SmtpMail.isAddress(f)) return "Couldn’t save: that email address does not look right"
        val dest = to.trim().ifBlank { f }
        if (!SmtpMail.isAddress(dest)) return "Couldn’t save: the address to send reports to does not look right"
        val pw = appPassword.filter { !it.isWhitespace() }
        if (pw.isBlank() && !hasPassword(ctx)) return "Couldn’t save: add the app password for $f"
        val h = if (providerName(f).isNotBlank()) "" else host.trim().lowercase(Locale.ROOT)
        if (h.isNotBlank() && !hostRx.matches(h)) return "Couldn’t save: the mail server should look like smtp.example.com"
        val e = p(ctx).edit().putString("from", f).putString("to", if (dest == f) "" else dest).putString("host", h).remove("err")
        if (pw.isNotBlank()) e.putString("pw", SiteVault.seal(pw))
        e.apply()
        return "Saved. Reports will be emailed to $dest. Tap “Send test” to check it works."
    }

    /** Sends one email now (blocking: call from a background thread). Returns null when it was sent, otherwise the reason. */
    fun send(ctx: Context, subject: String, body: String): String? {
        val a = ctx.applicationContext
        val f = from(a)
        val pw = SiteVault.unseal(p(a).getString("pw", "") ?: "")
        if (f.isBlank() || pw.isBlank()) return "email reports are not set up yet"
        val dest = to(a).ifBlank { f }
        val err = SmtpMail.send(serverFor(a, f), f, pw, f, dest, subject, body)
        p(a).edit().apply { if (err == null) remove("err") else putString("err", err) }.apply()
        return err
    }

    /** The same, in the background (never blocks the screen or the task that just finished). */
    fun sendAsync(ctx: Context, subject: String, body: String) {
        if (!configured(ctx)) return
        Thread { try { send(ctx, subject, body) } catch (e: Exception) { } }.start()
    }

    fun sendReport(ctx: Context, r: TaskReports.Report) {
        if (!configured(ctx)) return
        val state = if (r.ok) "done" else "didn’t finish"
        val title = r.title.ifBlank { "Task" }
        val time = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        sendAsync(ctx, "Neo Plus: $state – ${title.take(70)}",
            "$title\n\nResult: $state\n${r.detail}\n\n$time\n- Neo Plus")
    }

    /** The one-tap flow: saves the settings, then sends a test email and says how it went (blocking: call from a background thread). */
    fun connect(ctx: Context, from: String, appPassword: String, to: String, host: String = ""): String {
        val r = save(ctx, from, appPassword, to, host)
        if (r.startsWith("Couldn")) return r
        return sendTest(ctx)
    }

    /** For the “Send test” button: sends now and says how it went. */
    fun sendTest(ctx: Context): String {
        if (!configured(ctx)) return "Save your email and app password first."
        val dest = to(ctx).ifBlank { from(ctx) }
        val err = send(ctx, "Neo Plus: test", "This is a test report from Neo Plus. If you can read it, task reports by email work.\n- Neo Plus")
        return if (err == null) "Connected ✓ A test email was sent to $dest. Check the inbox (and spam)." else "Couldn’t connect: $err. Your settings are saved; fix this and tap Connect again."
    }
}
