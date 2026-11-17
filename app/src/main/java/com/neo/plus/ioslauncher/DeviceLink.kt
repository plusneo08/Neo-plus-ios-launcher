package com.neo.plus.ioslauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * A small, honest version of "send this app to another device" between two phones that both run Neo Plus, on the same
 * Wi-Fi, with the owner's consent on BOTH ends:
 *
 *   1. Each phone opts in once by saying "turn on device sharing" — this is OFF by default, since it opens a local port.
 *   2. Phone A says "show my pairing code" — a 6-digit code, good for 10 minutes, advertised on the local network (NSD),
 *      never over the internet.
 *   3. Phone B says "pair with code 482913" to link the two phones by name, under a name you give it.
 *   4. Phone A says "send Spotify to Dad's phone" — Phone B gets a notification: "A wants you to install Spotify" with
 *      Review and Decline. Review opens that app's Play Store page on Phone B.
 *
 * What this is NOT: it never installs anything by itself. Real MDM can push an install silently only because the
 * receiving device enrolled into Google's own device-management service (Android Enterprise); that is a different,
 * much heavier setup this app does not do, and faking it with a hidden silent install is exactly the kind of thing
 * malware does, so it is not built here. The other device's own person must tap Install themselves, every time.
 */
object DeviceLink {
    private const val PREF = "neo_companion"
    private const val SERVICE_TYPE = "_neoplusapp._tcp."
    private const val CH = "neo_companion"
    private const val BASE_PORT = 48710
    private val random = Random(System.nanoTime())

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("on", false)
    private fun myName(c: Context) = p(c).getString("my_name", null) ?: (Build.MODEL ?: "My phone")

    @Volatile private var serverThread: Thread? = null
    @Volatile private var serverPort: Int = 0
    @Volatile private var registered = false
    @Volatile private var discovering = false

    /** Safe to call often; only the first call while enabled actually opens the listening socket. */
    fun start(c: Context) {
        if (!enabled(c) || serverThread != null) return
        val app = c.applicationContext
        val t = Thread {
            try {
                var port = BASE_PORT
                var socket: ServerSocket? = null
                while (socket == null && port < BASE_PORT + 20) {
                    try { socket = ServerSocket(port) } catch (e: Exception) { port++ }
                }
                if (socket == null) return@Thread
                serverPort = port
                registerService(app, port)
                while (true) {
                    val s = try { socket.accept() } catch (e: Exception) { break }
                    Thread { try { handleConnection(app, s) } catch (e: Exception) { } finally { try { s.close() } catch (e: Exception) { } } }.start()
                }
            } catch (e: Throwable) { }
        }
        t.isDaemon = true
        serverThread = t
        t.start()
    }

    fun setEnabled(c: Context, on: Boolean): String {
        p(c).edit().putBoolean("on", on).apply()
        if (on) { start(c); return "Device sharing is on. Say “show my pairing code” to let another phone pair with you." }
        try { NsdHolder.manager(c)?.unregisterService(serviceListener) } catch (e: Throwable) { }
        registered = false
        return "Device sharing is off. This phone will no longer accept app-send requests."
    }

    fun setMyName(c: Context, name: String): String {
        val n = name.trim().take(24).ifBlank { return "What should I call this phone?" }
        p(c).edit().putString("my_name", n).apply()
        return "Okay, other devices will see this phone as “$n”."
    }

    /* ------------------------------------------------------------------ advertising this phone */

    private val serviceListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { }
        override fun onServiceUnregistered(info: NsdServiceInfo) { }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { }
    }

    private fun registerService(c: Context, port: Int) {
        if (registered) return
        val nsd = NsdHolder.manager(c) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "neoplus-" + myName(c).replace(Regex("[^A-Za-z0-9]"), "").take(10) + random.nextInt(1000)
            serviceType = SERVICE_TYPE
            this.port = port
        }
        try {
            info.setAttribute("name", myName(c))
            info.setAttribute("code", currentCode(c) ?: "")
        } catch (e: Throwable) { /* setAttribute needs API 21+, which this project already targets */ }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, serviceListener); registered = true } catch (e: Throwable) { }
    }

    /* ------------------------------------------------------------------ pairing code */

    fun showCode(c: Context): String {
        if (!enabled(c)) return "Turn on device sharing first: say “turn on device sharing”."
        val code = (100000 + random.nextInt(900000)).toString()
        p(c).edit().putString("code", code).putLong("code_until", System.currentTimeMillis() + 10 * 60_000L).apply()
        start(c)
        // Refresh the advertised code (the service may already be registered from an earlier start).
        try { NsdHolder.manager(c)?.unregisterService(serviceListener) } catch (e: Throwable) { }
        registered = false
        if (serverPort > 0) registerService(c, serverPort)
        return "Your pairing code is $code. It works on this Wi-Fi network for the next 10 minutes. On the other phone, say “pair with code $code”."
    }

    private fun currentCode(c: Context): String? {
        val pr = p(c)
        return if (System.currentTimeMillis() < pr.getLong("code_until", 0L)) pr.getString("code", null) else null
    }

    private fun companions(c: Context) = try { JSONObject(p(c).getString("companions", "{}")) } catch (e: Exception) { JSONObject() }

    fun listCompanions(c: Context): String {
        val all = companions(c)
        if (all.length() == 0) return "No devices are paired yet. Say “show my pairing code” on one phone and “pair with code ...” on the other."
        return "Paired devices: " + all.keys().asSequence().joinToString(", ") + "."
    }

    /** Looks on the local network for a device advertising [code], for up to 8 seconds, and saves it under [name]. */
    fun pairWithCode(c: Context, code: String, name: String): String {
        if (!enabled(c)) return "Turn on device sharing first: say “turn on device sharing”."
        val cc = code.trim()
        if (cc.length != 6 || cc.toIntOrNull() == null) return "That doesn’t look like a 6-digit pairing code."
        val label = name.trim().ifBlank { "paired phone" }.take(24)
        val nsd = NsdHolder.manager(c) ?: return "This phone can’t search the network right now."
        if (discovering) return "Already searching — wait a moment and try again."
        discovering = true
        val found = java.util.concurrent.CountDownLatch(1)
        var result: NsdServiceInfo? = null
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, err: Int) { }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val theirCode = try { i.attributes["code"]?.toString(Charsets.UTF_8) } catch (e: Throwable) { null }
                        if (theirCode == cc) { result = i; found.countDown() }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) { }
            override fun onDiscoveryStopped(t: String) { }
            override fun onStartDiscoveryFailed(t: String, e: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) { }
        }
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) } catch (e: Throwable) { discovering = false; return "Couldn’t start searching the network." }
        found.await(8, java.util.concurrent.TimeUnit.SECONDS)
        try { nsd.stopServiceDiscovery(discoveryListener) } catch (e: Throwable) { }
        discovering = false
        val r = result ?: return "No phone nearby is showing that code. Make sure both phones are on the same Wi-Fi and device sharing is on."
        val host = r.host?.hostAddress ?: return "Found the phone but couldn’t get its address."
        val all = companions(c)
        all.put(label, JSONObject().put("host", host).put("port", r.port).put("at", System.currentTimeMillis()))
        p(c).edit().putString("companions", all.toString()).apply()
        return "Paired with “$label” at $host. You can now say “send <app> to $label”."
    }

    /* ------------------------------------------------------------------ sending a request (4) */

    /** [appName] must be an app already installed on THIS phone (its Play Store page is the same for the other one). */
    fun sendApp(c: Context, appName: String, companionName: String): String {
        val entry = OrbBridge.apps().findBest(appName) ?: return "No app called “$appName” on this phone."
        val all = companions(c)
        val name = all.keys().asSequence().firstOrNull { it.equals(companionName.trim(), true) || it.contains(companionName.trim(), true) }
            ?: return "No paired device called “$companionName”. " + listCompanions(c)
        val info = all.optJSONObject(name) ?: return "Lost the address for “$name”. Pair again."
        val host = info.optString("host"); val port = info.optInt("port")
        if (host.isBlank() || port <= 0) return "Lost the address for “$name”. Pair again."
        val payload = JSONObject().put("type", "install_request").put("app", entry.label).put("pkg", entry.realPkg).put("from", myName(c))
        return try {
            Socket(InetAddress.getByName(host), port).use { s ->
                s.soTimeout = 4000
                s.getOutputStream().write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                s.getOutputStream().flush()
            }
            "Sent ${entry.label} to $name. They will see a request to review and install it."
        } catch (e: Exception) { "Couldn’t reach “$name” — make sure it’s on the same Wi-Fi and device sharing is turned on there too." }
    }

    /* ------------------------------------------------------------------ receiving a request (the other end) */

    private fun handleConnection(c: Context, s: Socket) {
        s.soTimeout = 4000
        val line = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
        val j = try { JSONObject(line) } catch (e: Exception) { return }
        if (j.optString("type") != "install_request") return
        val app = j.optString("app").take(60).ifBlank { return }
        val pkg = j.optString("pkg").take(120).ifBlank { return }
        val from = j.optString("from").take(40).ifBlank { "Another phone" }
        notifyRequest(c, app, pkg, from)
    }

    private fun notifyRequest(c: Context, app: String, pkg: String, from: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Device sharing", NotificationManager.IMPORTANCE_HIGH))
            val reviewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val reviewPi = PendingIntent.getActivity(c, pkg.hashCode(), reviewIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val declineIntent = Intent(c, CompanionReceiver::class.java).setAction("decline").putExtra("id", pkg.hashCode())
            val declinePi = PendingIntent.getBroadcast(c, pkg.hashCode(), declineIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("$from wants you to install $app")
                .setContentText("Nothing installs until you tap Review and choose Install yourself.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(reviewPi)
                .addAction(0, "Review", reviewPi)
                .addAction(0, "Decline", declinePi)
                .setAutoCancel(true)
                .build()
            nm.notify(pkg.hashCode(), n)
        } catch (e: Throwable) { }
    }

    /* ------------------------------------------------------------------ the `companion` action */

    fun handle(c: Context, a: JSONObject): String = when (a.optString("do")) {
        "on" -> setEnabled(c, true)
        "off" -> setEnabled(c, false)
        "name" -> setMyName(c, a.optString("text"))
        "code" -> showCode(c)
        "pair" -> pairWithCode(c, a.optString("code"), a.optString("text"))
        "list" -> listCompanions(c)
        "send" -> sendApp(c, a.optString("app"), a.optString("text"))
        else -> "Say “turn on device sharing”, “show my pairing code”, “pair with code 123456”, or “send Spotify to <device>”."
    }
}

/** Minimal holder so NsdManager is fetched once; kept separate for clarity. */
private object NsdHolder {
    fun manager(c: Context): NsdManager? = try { c.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager } catch (e: Throwable) { null }
}

class CompanionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        try { context.getSystemService(NotificationManager::class.java).cancel(id) } catch (e: Exception) { }
    }
}

/** "turn on/off device sharing", "show my pairing code", "pair with code 123456 [as Dad's phone]", "send Spotify to Dad's phone",
 *  "list paired devices", "call this phone <name>". */
object CompanionCmd {
    private val I = RegexOption.IGNORE_CASE
    private fun r(s: String) = Regex(s, I)
    private val on = r("\\bturn on device (?:sharing|management)\\b")
    private val off = r("\\bturn off device (?:sharing|management)\\b")
    private val code = r("\\b(?:show|what(?:'s| is)) my pairing code\\b|\\bgenerate a pairing code\\b")
    private val list = r("\\blist paired devices\\b|\\bwhat devices are paired\\b")
    private val pair = r("\\bpair with code\\s+(\\d{6})(?:\\s+as\\s+(.+))?$")
    private val rename = r("\\bcall this phone\\s+(.+)$")
    private val send = r("\\bsend\\s+(.+?)\\s+to\\s+(.+)$")

    private fun plan(d: String, vararg kv: Pair<String, Any>): LocalPlan {
        val o = JSONObject().put("do", d); kv.forEach { o.put(it.first, it.second) }
        return LocalPlan("", listOf("companion" to o))
    }

    fun plan(text: String): LocalPlan? {
        val t = text.trim().trimEnd('.', '!', '?')
        if (on.containsMatchIn(t)) return plan("on")
        if (off.containsMatchIn(t)) return plan("off")
        if (code.containsMatchIn(t)) return plan("code")
        if (list.containsMatchIn(t)) return plan("list")
        pair.find(t)?.let { m -> return plan("pair", "code" to m.groupValues[1], "text" to (m.groupValues[2].ifBlank { "paired phone" })) }
        rename.find(t)?.let { m -> return plan("name", "text" to m.groupValues[1]) }
        send.find(t)?.let { m -> return plan("send", "app" to m.groupValues[1], "text" to m.groupValues[2]) }
        return null
    }
}
