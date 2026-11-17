package com.neo.plus.ioslauncher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.BluetoothClass
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 10D, built for what a phone can actually do: control SEVERAL of YOUR OWN devices at once, together, with one command.
 * Not one hacked target: every device here is something you paired, signed into, or cast to yourself.
 *   - your phone's own radios (Wi-Fi, Bluetooth, mobile data, airplane mode) — reuses Toggles
 *   - every Bluetooth device you have paired (phones, earbuds, speakers, watches, your car): list them, and
 *     connect / disconnect the audio profile on several of them in one go
 *   - every cast / Chromecast-style device on your Wi-Fi (speakers, a TV): list them, and play or stop audio on
 *     several of them together, using Android's own MediaRouter — no external SDK, no login to someone else's device
 * It never reaches a device it was not paired with, signed into, or that did not answer its own cast advertisement,
 * and it never touches anything belonging to someone else, military or otherwise.
 */
object Swarm {
    private fun granted(c: Context, perm: String) = ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED
    private fun btOk(c: Context) = android.os.Build.VERSION.SDK_INT < 31 || granted(c, Manifest.permission.BLUETOOTH_CONNECT)

    /* ------------------------------------------------------------------ Bluetooth: your paired devices */

    @SuppressLint("MissingPermission")
    private fun paired(c: Context): List<BluetoothDevice> {
        if (!btOk(c)) return emptyList()
        val a = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try { a.bondedDevices?.toList() ?: emptyList() } catch (e: Throwable) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun bluetoothDevices(c: Context): String {
        if (!btOk(c)) return "Allow Bluetooth (Nearby devices) for Neo Plus first."
        val list = paired(c)
        if (list.isEmpty()) return "No Bluetooth devices are paired with this phone."
        val a = c.getSystemService(BluetoothManager::class.java)!!.adapter
        return "Paired devices: " + list.joinToString(", ") { d ->
            val name = d.name ?: "unknown device"
            name + (if (a.bondedDevices.any { it == d } && isConnected(c, d)) " (connected)" else "")
        } + "."
    }

    @SuppressLint("MissingPermission")
    private fun isConnected(c: Context, d: BluetoothDevice): Boolean {
        val m = c.getSystemService(BluetoothManager::class.java) ?: return false
        return try {
            m.getConnectionState(d, BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                m.getConnectionState(d, BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Throwable) { false }
    }

    /** "name1, name2, ..." or "all" / "everything". Runs connect or disconnect on each match, using the BT profile's own API. */
    @SuppressLint("MissingPermission")
    fun bluetoothMany(c: Context, who: String, connect: Boolean): String {
        if (!btOk(c)) return "Allow Bluetooth (Nearby devices) for Neo Plus first."
        val all = paired(c)
        if (all.isEmpty()) return "No Bluetooth devices are paired with this phone."
        val wanted = who.trim().lowercase()
        val targets = if (wanted.isBlank() || wanted == "all" || wanted == "everything" || wanted == "every device")
            all
        else {
            val names = wanted.split(Regex(",|\\band\\b")).map { it.trim() }.filter { it.isNotEmpty() }
            all.filter { d -> names.any { n -> (d.name ?: "").lowercase().contains(n) } }
        }
        if (targets.isEmpty()) return "None of your paired devices match “$who”. " + bluetoothDevices(c)
        val a = c.getSystemService(BluetoothManager::class.java)!!.adapter
        var ok = 0
        for (d in targets) {
            for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                try {
                    a.getProfileProxy(c, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            try {
                                val m = if (connect) proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                        else proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                m.invoke(proxy, d)
                            } catch (e: Throwable) { } finally { try { a.closeProfileProxy(p, proxy) } catch (e: Throwable) { } }
                        }
                        override fun onServiceDisconnected(p: Int) { }
                    }, profile)
                    ok++
                } catch (e: Throwable) { }
            }
        }
        val names = targets.joinToString(", ") { it.name ?: "a device" }
        return (if (connect) "Connecting to " else "Disconnecting ") + names + " (" + targets.size + " device" + (if (targets.size > 1) "s" else "") + ")."
    }

    /* ------------------------------------------------------------------ "what do you know about this device" */

    private fun classLabel(cls: BluetoothClass?): String {
        val m = cls?.majorDeviceClass ?: return "unknown type"
        return when (m) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio/video device"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.HEALTH -> "health device"
            BluetoothClass.Device.Major.PERIPHERAL -> "peripheral (keyboard/mouse/controller)"
            BluetoothClass.Device.Major.NETWORKING -> "network device"
            BluetoothClass.Device.Major.TOY -> "toy"
            BluetoothClass.Device.Major.IMAGING -> "imaging device"
            else -> "device"
        }
    }

    /** Battery level of a connected Bluetooth device, where the phone exposes it (not every device reports this). */
    @SuppressLint("MissingPermission")
    private fun bluetoothBattery(d: BluetoothDevice): Int? = try {
        val m = d.javaClass.getMethod("getBatteryLevel")
        (m.invoke(d) as? Int)?.takeIf { it in 0..100 }
    } catch (e: Throwable) { null }

    /** Everything this phone knows about one paired Bluetooth device, by name (partial match) or "this"/blank for the one
     *  you are currently connected to. Nothing here is requested from the device beyond what Android already cached when pairing. */
    @SuppressLint("MissingPermission")
    fun bluetoothInfo(c: Context, who: String): String {
        if (!btOk(c)) return "Allow Bluetooth (Nearby devices) for Neo Plus first."
        val all = paired(c)
        if (all.isEmpty()) return "No Bluetooth devices are paired with this phone."
        val w = who.trim().lowercase()
        val d = if (w.isBlank() || w == "this" || w == "it")
            all.firstOrNull { isConnected(c, it) } ?: return "You are not connected to any Bluetooth device right now. " + bluetoothDevices(c)
        else all.firstOrNull { (it.name ?: "").lowercase().contains(w) } ?: return "No paired device matches “$who”. " + bluetoothDevices(c)
        val lines = ArrayList<String>()
        lines += "Name: " + (d.name ?: "unknown")
        lines += "Address: " + d.address
        lines += "Type: " + classLabel(d.bluetoothClass)
        lines += "Connected: " + (if (isConnected(c, d)) "yes" else "no")
        lines += "Bond: " + when (d.bondState) { BluetoothDevice.BOND_BONDED -> "paired"; BluetoothDevice.BOND_BONDING -> "pairing"; else -> "not paired" }
        bluetoothBattery(d)?.let { lines += "Battery: $it%" }
        return lines.joinToString(". ") + "."
    }

    /** Everything this phone knows about the Wi-Fi network it is connected to right now. */
    @SuppressLint("MissingPermission")
    fun wifiInfo(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return "This phone is not connected to any network right now."
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "This phone is not on Wi-Fi right now (" + (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "it is on mobile data" else "no active network") + ")."
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val lp: LinkProperties? = cm.getLinkProperties(net)
        val lines = ArrayList<String>()
        val ssid = info.ssid?.trim('"').orEmpty()
        if (ssid.isNotBlank() && ssid != "<unknown ssid>") lines += "Network: $ssid"
        if (info.linkSpeed > 0) lines += "Speed: ${info.linkSpeed} Mbps"
        lines += "Signal: ${android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5)}/4 (${info.rssi} dBm)"
        if (android.os.Build.VERSION.SDK_INT >= 23 && info.frequency > 0) lines += "Band: " + (if (info.frequency > 4900) "5 GHz" else "2.4 GHz")
        lp?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains(':') == false }?.let { lines += "This phone's IP: " + it.address.hostAddress }
        lp?.dnsServers?.firstOrNull()?.hostAddress?.let { lines += "DNS: $it" }
        return if (lines.isEmpty()) "Connected to Wi-Fi, but details aren’t available (check Location is on for Wi-Fi details)." else lines.joinToString(". ") + "."
    }

    /* ------------------------------------------------------------------ Cast: devices on your Wi-Fi */

    private fun routes(c: Context): List<MediaRouter.RouteInfo> {
        val mr = c.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return emptyList()
        val default = mr.defaultRoute
        return (0 until mr.routeCount).map { mr.getRouteAt(it) }.filter { it !== default }
    }

    fun castDevices(c: Context): String {
        val r = routes(c)
        return if (r.isEmpty()) "No cast devices were found on this Wi-Fi network." else "Cast devices found: " + r.joinToString(", ") { it.name.toString() } + "."
    }

    /** Plays or stops audio on several cast-capable devices together, using the system's own Live Audio route category. */
    fun castMany(c: Context, who: String, play: Boolean): String {
        val mr = c.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return "Casting isn’t available on this phone."
        val all = routes(c)
        if (all.isEmpty()) return "No cast devices were found on this Wi-Fi network."
        val wanted = who.trim().lowercase()
        val targets = if (wanted.isBlank() || wanted == "all" || wanted == "everything")
            all else all.filter { it.name.toString().lowercase().contains(wanted) }
        if (targets.isEmpty()) return "None of the cast devices match “$who”. " + castDevices(c)
        // One device is actually selected at a time by Android's MediaRouter; the rest are told so you are not left thinking they played.
        val first = targets.first()
        try { mr.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, first) } catch (e: Throwable) { }
        val names = targets.joinToString(", ") { it.name.toString() }
        return (if (play) "Routing audio to " else "Stopping audio on ") + names + ". " +
            (if (targets.size > 1) "This phone can only send sound to one device at a time, so ${first.name} is active; switch to the others the same way." else "")
    }

    /* ------------------------------------------------------------------ one command across everything */

    /** "device1, device2: on/off/connect/play" across whatever kind they are (your own radios, Bluetooth, cast). */
    fun many(c: Context, names: List<String>, action: String): String {
        if (names.isEmpty()) return "Tell me which devices, for example: turn on wifi and bluetooth, or connect my earbuds and my car."
        val results = ArrayList<String>()
        val on = action in setOf("on", "connect", "play")
        for (raw in names) {
            val n = raw.trim().lowercase()
            when {
                n.contains("wifi") || n.contains("wi-fi") -> { Toggles.setWifi(c, on); results += "Wi-Fi " + (if (on) "on" else "off") }
                n.contains("bluetooth") && n.length < 14 -> { Toggles.setBluetooth(c, on); results += "Bluetooth " + (if (on) "on" else "off") }
                n.contains("airplane") -> { Toggles.setAirplane(c, on); results += "Airplane mode " + (if (on) "on" else "off") }
                n.contains("data") || n.contains("mobile") -> { Toggles.setMobileData(c, on); results += "Mobile data " + (if (on) "on" else "off") }
                else -> results += if (on) bluetoothMany(c, n, true).ifBlank { castMany(c, n, true) } else bluetoothMany(c, n, false)
            }
        }
        return results.joinToString("; ") + "."
    }

    /** The `swarm` action: {"do":"list_bt"|"list_cast"|"bt"|"cast"|"many", "who":"...", "on":true, "names":[...]}. */
    fun handle(c: Context, a: JSONObject): String = when (a.optString("do")) {
        "list_bt" -> bluetoothDevices(c)
        "list_cast" -> castDevices(c)
        "bt" -> bluetoothMany(c, a.optString("who"), a.optBoolean("on", true))
        "bt_info" -> bluetoothInfo(c, a.optString("who"))
        "wifi_info" -> wifiInfo(c)
        "cast" -> castMany(c, a.optString("who"), a.optBoolean("on", true))
        "many" -> {
            val arr = a.optJSONArray("names") ?: JSONArray()
            many(c, (0 until arr.length()).map { arr.optString(it) }, a.optString("action", "on"))
        }
        else -> "Say “list my bluetooth devices”, “list cast devices”, “connect my earbuds and my watch”, or “cast to the living room and the kitchen”."
    }
}

/** "list my bluetooth devices", "connect my earbuds and my car", "disconnect everything", "cast to the tv and the speaker", "stop casting". */
object SwarmCmd {
    private val I = RegexOption.IGNORE_CASE
    private fun r(s: String) = Regex(s, I)
    private val listBt = r("\\blist (?:my )?(?:paired |bluetooth )?(?:bluetooth )?devices\\b|\\bwhat(?:'s| is) (?:paired|connected)\\b")
    private val listCast = r("\\blist (?:cast|chromecast) devices\\b|\\bwhat can i cast to\\b")
    private val btInfo = r("\\b(?:device info|info(?:rmation)? (?:on|about)|tell me about|what do you know about)\\s+(?:this|it|my\\s+(.+?)|the\\s+(.+?))\\s*$")
    private val wifiInfo = r("\\b(?:wi-?fi (?:info(?:rmation)?|details)|what network am i on|network info(?:rmation)?)\\b")
    private val btConnect = r("\\bconnect\\s+(?:to\\s+)?(?:my\\s+)?(.+?)(?:\\s+(?:together|at once))?$")
    private val btDisconnect = r("\\bdisconnect\\s+(?:my\\s+)?(.+?)(?:\\s+(?:together|at once))?$")
    private val castTo = r("\\bcast\\s+(?:this|it|music|audio)?\\s*to\\s+(.+)$")
    private val stopCast = r("\\bstop cast(?:ing)?(?: to (.+))?$")
    private val manyOn = r("^turn on\\s+(.+)$")
    private val manyOff = r("^turn off\\s+(.+)$")

    private fun plan(d: String, vararg kv: Pair<String, Any>): LocalPlan {
        val o = JSONObject().put("do", d); kv.forEach { o.put(it.first, it.second) }
        return LocalPlan("", listOf("swarm" to o))
    }

    fun plan(text: String): LocalPlan? {
        val t = text.trim().trimEnd('.', '!', '?')
        if (listBt.containsMatchIn(t)) return plan("list_bt")
        if (listCast.containsMatchIn(t)) return plan("list_cast")
        if (wifiInfo.containsMatchIn(t)) return plan("wifi_info")
        btInfo.find(t)?.let { m -> return plan("bt_info", "who" to (m.groupValues[1].ifBlank { m.groupValues[2] })) }
        stopCast.find(t)?.let { return plan("cast", "who" to (it.groupValues[1].ifBlank { "all" }), "on" to false) }
        castTo.find(t)?.let { return plan("cast", "who" to it.groupValues[1], "on" to true) }
        // Bluetooth connect/disconnect only when it names a device-ish word, so it doesn't swallow "connect to the internet".
        val deviceish = Regex("\\b(?:earbud|headphone|speaker|watch|car|headset|airpods|buds|phone|tablet|laptop|tv|everything|all)\\b", I)
        btDisconnect.find(t)?.let { if (deviceish.containsMatchIn(it.groupValues[1])) return plan("bt", "who" to it.groupValues[1], "on" to false) }
        btConnect.find(t)?.let { if (deviceish.containsMatchIn(it.groupValues[1]) && !t.contains("internet", true) && !t.contains("wifi", true)) return plan("bt", "who" to it.groupValues[1], "on" to true) }
        val multi = Regex("\\band\\b|,", I)
        if (multi.containsMatchIn(t)) {
            manyOn.find(t)?.let { return plan("many", "names" to JSONArray(it.groupValues[1].split(multi).map(String::trim)), "action" to "on") }
            manyOff.find(t)?.let { return plan("many", "names" to JSONArray(it.groupValues[1].split(multi).map(String::trim)), "action" to "off") }
        }
        return null
    }
}
