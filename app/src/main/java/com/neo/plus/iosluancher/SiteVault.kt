package com.neo.plus.iosluancher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One website the user has authorised the assistant to work on. */
class SiteEntry(val domain: String, val username: String, val password: String, val authorized: Boolean)

/**
 * The websites the assistant may operate on, with the login the user gave for each.
 *
 *  - Everything is encrypted with an Android Keystore key (AES-GCM) that never leaves the phone's secure hardware/TEE.
 *  - The AI model NEVER sees a username or password: it writes {{username}} / {{password}} and the phone types the
 *    real values into the page itself ([WebTask]).
 *  - The assistant only ever opens addresses on a domain listed here with "authorised" switched on.
 */
object SiteVault {
    private const val ALIAS = "neo_site_vault_key"
    private const val PREFS = "neo_site_vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun dec(blob: String): String {
        val (iv, ct) = blob.split(":").let { it[0] to it[1] }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** Encrypts / decrypts one secret (e.g. the email app password) with the same Keystore key as the website logins. */
    fun seal(plain: String): String = enc(plain)
    fun unseal(blob: String): String = try { dec(blob) } catch (e: Exception) { "" }

    /** "https://www.Example.com/login" -> "example.com" */
    fun normalizeDomain(input: String): String {
        var d = input.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://")
        d = d.substringBefore('/').substringBefore('?').substringBefore(':')
        return d.removePrefix("www.")
    }

    private fun load(ctx: Context): List<SiteEntry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sites", null) ?: return emptyList()
        return try {
            val arr = JSONArray(dec(raw))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SiteEntry(o.getString("domain"), o.optString("user"), o.optString("pass"), o.optBoolean("on", true))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(ctx: Context, list: List<SiteEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("domain", it.domain).put("user", it.username).put("pass", it.password).put("on", it.authorized))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("sites", enc(arr.toString())).apply()
    }

    fun all(ctx: Context): List<SiteEntry> = load(ctx)

    fun put(ctx: Context, domainInput: String, user: String, pass: String, authorized: Boolean) {
        val d = normalizeDomain(domainInput)
        if (d.isBlank()) return
        save(ctx, load(ctx).filter { it.domain != d } + SiteEntry(d, user, pass, authorized))
    }

    fun setAuthorized(ctx: Context, domain: String, on: Boolean) {
        save(ctx, load(ctx).map { if (it.domain == domain) SiteEntry(it.domain, it.username, it.password, on) else it })
    }

    fun remove(ctx: Context, domain: String) { save(ctx, load(ctx).filter { it.domain != domain }); SessionKeeper.forget(ctx, domain) }

    /**
     * The saved login (switched on) for an account the user names, e.g. "google.com", "Google Drive" or "facebook".
     * Blank [q] works only when exactly one login is switched on. Used to type a password into a phone app's own login box.
     */
    fun forAccount(ctx: Context, q: String): SiteEntry? {
        val on = load(ctx).filter { it.authorized }
        val t = q.trim().lowercase()
        if (t.isBlank()) return on.singleOrNull()
        val d = normalizeDomain(t)
        return on.firstOrNull { it.domain == d || d.endsWith("." + it.domain) || it.domain.endsWith("." + d) }
            ?: on.firstOrNull { val stem = it.domain.substringBefore('.'); stem.length >= 3 && t.contains(stem) }
    }

    /** The authorised entry whose domain matches [hostOrUrl] (exact host or any of its subdomains), or null. */
    fun authorizedFor(ctx: Context, hostOrUrl: String): SiteEntry? {
        val host = normalizeDomain(hostOrUrl)
        return load(ctx).firstOrNull { it.authorized && (host == it.domain || host.endsWith("." + it.domain)) }
    }
}
