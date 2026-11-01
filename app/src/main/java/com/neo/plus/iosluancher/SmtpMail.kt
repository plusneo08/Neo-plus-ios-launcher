package com.neo.plus.iosluancher

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small, dependency-free SMTP client: sends one plain-text email from the person's own mailbox (Gmail, Outlook, Yahoo, ...).
 * Pure Kotlin/JVM, so it needs no extra library. The connection is always encrypted (SSL on 465 or STARTTLS on 587) and the
 * server's certificate name is checked before the password is sent. [Security.NONE] exists only for local tests.
 */
object SmtpMail {
    enum class Security { SSL, STARTTLS, NONE }
    class Server(val host: String, val port: Int, val security: Security)

    private val addressRx = Regex("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+$")
    fun isAddress(s: String) = addressRx.matches(s.trim())

    /** The outgoing mail server that belongs to an email address. */
    fun serverFor(email: String): Server {
        val d = email.substringAfter('@').trim().lowercase(Locale.ROOT)
        return when {
            d == "gmail.com" || d == "googlemail.com" -> Server("smtp.gmail.com", 465, Security.SSL)
            d == "outlook.com" || d == "hotmail.com" || d == "live.com" || d == "msn.com" || d.startsWith("outlook.") || d.startsWith("hotmail.") ->
                Server("smtp-mail.outlook.com", 587, Security.STARTTLS)
            d.startsWith("yahoo.") || d == "ymail.com" -> Server("smtp.mail.yahoo.com", 465, Security.SSL)
            d == "zoho.com" -> Server("smtp.zoho.com", 465, Security.SSL)
            d == "icloud.com" || d == "me.com" -> Server("smtp.mail.me.com", 587, Security.STARTTLS)
            else -> Server("smtp.$d", 465, Security.SSL)
        }
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) B64[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun b64(s: String) = base64(s.toByteArray(Charsets.UTF_8))
    private fun header(s: String) = s.replace(Regex("[\\r\\n<>]"), " ").trim()

    /** The whole message (headers + base64 body) as it goes over the wire, without the closing dot line. */
    fun buildMessage(from: String, to: String, subject: String, body: String): String {
        val f = header(from); val t = header(to)
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("From: Neo Plus <").append(f).append(">\r\n")
        sb.append("To: <").append(t).append(">\r\n")
        sb.append("Subject: =?UTF-8?B?").append(b64(header(subject))).append("?=\r\n")
        sb.append("Date: ").append(date).append("\r\n")
        sb.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(f.substringAfter('@')).append(">\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        b64(body).chunked(76).forEach { sb.append(it).append("\r\n") }
        return sb.toString()
    }

    /** Sends the mail. Returns null when the server accepted it, otherwise a plain-language reason. */
    fun send(server: Server, user: String, password: String, from: String, to: String, subject: String, body: String, timeoutMs: Int = 20000): String? {
        if (!isAddress(from)) return "the email address to send from does not look right"
        if (!isAddress(to)) return "the email address to send to does not look right"
        var sock: Socket? = null
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(server.host, server.port), timeoutMs)
            raw.soTimeout = timeoutMs
            sock = if (server.security == Security.SSL) wrap(raw, server) else raw
            var rd = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            var wr = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))

            fun reply(): Pair<Int, String> {
                val all = StringBuilder()
                while (true) {
                    val line = rd.readLine() ?: return -1 to all.toString().ifBlank { "the mail server closed the connection" }
                    all.append(line).append('\n')
                    if (line.length < 4 || line[3] != '-') return (line.take(3).toIntOrNull() ?: -1) to all.toString()
                }
                @Suppress("UNREACHABLE_CODE") return -1 to ""
            }
            fun send(line: String) { wr.write(line); wr.write("\r\n"); wr.flush() }
            fun expect(what: String, vararg ok: Int): String? {
                val (c, t) = reply()
                return if (c in ok) null else explain(what, c, t, server)
            }

            expect("greeting", 220)?.let { return it }
            send("EHLO neoplus.local"); expect("EHLO", 250)?.let { return it }
            if (server.security == Security.STARTTLS) {
                send("STARTTLS"); expect("STARTTLS", 220)?.let { return it }
                sock = wrap(sock, server)
                rd = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                wr = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                send("EHLO neoplus.local"); expect("EHLO", 250)?.let { return it }
            }
            send("AUTH LOGIN"); expect("login", 334)?.let { return it }
            send(b64(user)); expect("login", 334)?.let { return it }
            send(b64(password)); expect("login", 235)?.let { return it }
            send("MAIL FROM:<${header(from)}>"); expect("sender", 250)?.let { return it }
            send("RCPT TO:<${header(to)}>"); expect("recipient", 250, 251)?.let { return it }
            send("DATA"); expect("DATA", 354)?.let { return it }
            wr.write(buildMessage(from, to, subject, body)); wr.write("\r\n.\r\n"); wr.flush()
            expect("message", 250)?.let { return it }
            try { send("QUIT") } catch (e: Exception) { }
            return null
        } catch (e: java.net.UnknownHostException) {
            return "no internet, or the mail server name ${server.host} was not found"
        } catch (e: java.net.SocketTimeoutException) {
            return "the mail server did not answer in time (check the internet)"
        } catch (e: javax.net.ssl.SSLException) {
            return "the secure connection to ${server.host} failed (${e.message.orEmpty().take(80)})"
        } catch (e: Exception) {
            return e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
        } finally {
            try { sock?.close() } catch (e: Exception) { }
        }
    }

    private fun wrap(s: Socket, server: Server): Socket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, server.host, server.port, true) as SSLSocket
        ssl.startHandshake()
        // A raw SSL socket does not check the server's name by itself: without this a fake server could collect the password.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(server.host, ssl.session))
            throw javax.net.ssl.SSLException("the server certificate is not for ${server.host}")
        return ssl
    }

    private fun explain(what: String, code: Int, text: String, server: Server): String {
        val t = text.trim().replace("\n", " ").take(160)
        return when {
            code == 535 || (what == "login" && code in 530..538) ->
                if (server.host.contains("gmail")) "Gmail refused the login. Use an App Password: Google Account > Security > 2-Step Verification > App passwords (your normal password does not work)"
                else "the mail server refused the login (wrong password, or it needs an app password): $t"
            code == 534 || code == 454 -> "the mail account needs extra security setup (app password / 2-step verification): $t"
            else -> "the mail server said $code at $what: $t"
        }
    }
}
