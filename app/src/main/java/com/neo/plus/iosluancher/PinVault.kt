package com.neo.plus.iosluancher

import android.content.Context
import org.json.JSONObject

/**
 * PINs the user saved on purpose (Assistant settings -> Saved PINs).
 *
 *  - Encrypted with the same Android Keystore key as the saved website logins; nothing is ever sent anywhere.
 *  - The AI model NEVER sees a PIN: it writes the placeholder {{pin:NAME}} and the phone types the real digits itself.
 *  - Never typed while a scheduled job or a goal check-in is running ([unattended]): only when the user asks.
 *  - Airtel Money / Mpamba PINs are also used by the money flow, but ONLY after the user tapped Authorise and the PIN
 *    screen shows exactly the amount and number that were authorised ([MoneySteps]).
 */
object PinVault {
    private const val PREFS = "neo_pin_vault"

    /** True while something runs without the user asking right now (a schedule, a goal check-in): no PIN is typed then. */
    @Volatile var unattended = false

    /** "Airtel money", "airtel" -> "airtel money"; "Mpamba", "TNM mpamba" -> "mpamba"; anything else stays as written. */
    fun canon(name: String): String {
        val n = name.trim().lowercase().replace(Regex("\\s+"), " ").take(30)
        return when {
            n.contains("mpamba") -> "mpamba"
            n.contains("airtel") && (n == "airtel" || n.contains("money")) -> "airtel money"
            else -> n
        }
    }

    fun isMoney(name: String) = canon(name).let { it == "mpamba" || it == "airtel money" }

    private fun load(ctx: Context): JSONObject = try {
        JSONObject(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pins", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    fun names(ctx: Context): List<String> = load(ctx).keys().asSequence().toList().sorted()

    fun has(ctx: Context, name: String) = load(ctx).has(canon(name))

    /** Returns an error text, or null when saved. */
    fun put(ctx: Context, name: String, pin: String): String? {
        val n = canon(name)
        if (n.isBlank()) return "Give the PIN a name, for example Airtel Money"
        if (pin.length !in 3..10 || !pin.all { it.isDigit() }) return "A PIN is 3 to 10 digits"
        val o = load(ctx).put(n, SiteVault.seal(pin))
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pins", o.toString()).apply()
        return null
    }

    fun remove(ctx: Context, name: String) {
        val o = load(ctx); o.remove(canon(name))
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pins", o.toString()).apply()
    }

    /** The real digits, for the phone to type. Null when there is none or nothing may be typed right now. */
    fun get(ctx: Context, name: String): String? {
        if (unattended) return null
        val blob = load(ctx).optString(canon(name)).ifBlank { return null }
        return SiteVault.unseal(blob).ifBlank { null }
    }

    /** The PIN of the network the money goes out on ("airtel" / "mpamba"); with no network given, the only saved money PIN. */
    fun moneyPin(ctx: Context, network: String): String? {
        val net = network.lowercase()
        return when {
            net.contains("airtel") -> get(ctx, "airtel money")
            net.contains("mpamba") || net.contains("tnm") -> get(ctx, "mpamba")
            else -> {
                val saved = listOf("airtel money", "mpamba").filter { has(ctx, it) }
                if (saved.size == 1) get(ctx, saved[0]) else null
            }
        }
    }
}
