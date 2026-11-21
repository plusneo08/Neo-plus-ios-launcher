package com.neo.plus.ioslauncher

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Per-conversation language memory for message replies.
 *
 * The latest incoming message always wins: a saved language is only a fallback when
 * the current message is too short or ambiguous to identify reliably. Only language
 * tags are stored; message text is not copied into this preference store.
 */
object MessageLanguage {
    private const val PREF = "neo_message_languages"
    private const val KEY = "contacts"
    private const val MAX_CONTACTS = 100

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun contactKey(app: String, contact: String): String =
        (app.trim().lowercase(Locale.ROOT) + "|" + contact.trim().lowercase(Locale.ROOT)).take(240)

    fun remembered(ctx: Context, app: String, contact: String): String {
        if (app.isBlank() || contact.isBlank()) return ""
        val all = try { JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return all.optJSONObject(contactKey(app, contact))?.optString("lang").orEmpty()
    }

    fun remember(ctx: Context, app: String, contact: String, languageTag: String) {
        val tag = normalize(languageTag)
        if (app.isBlank() || contact.isBlank() || tag.isBlank()) return
        synchronized(this) {
            val p = prefs(ctx)
            val all = try { JSONObject(p.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            all.put(contactKey(app, contact), JSONObject().put("lang", tag).put("updated", System.currentTimeMillis()))
            if (all.length() > MAX_CONTACTS) {
                val oldest = all.keys().asSequence()
                    .mapNotNull { k -> all.optJSONObject(k)?.optLong("updated")?.let { k to it } }
                    .sortedBy { it.second }
                    .take(all.length() - MAX_CONTACTS)
                    .map { it.first }
                    .toList()
                oldest.forEach { all.remove(it) }
            }
            p.edit().putString(KEY, all.toString()).apply()
        }
    }

    /** Prompt hint only. The newest message must override this saved preference. */
    fun promptHint(ctx: Context, app: String, contact: String): String {
        val tag = remembered(ctx, app, contact)
        return if (tag.isBlank()) "" else
            "Previously this conversation used language tag $tag. Use it only if the newest message is ambiguous; if the sender switches languages, follow the newest message instead."
    }

    fun normalize(raw: String): String {
        val tag = raw.trim().removePrefix("LANG:").trim()
        if (tag.equals("und", true) || tag.equals("unknown", true)) return ""
        if (!Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$").matches(tag)) return ""
        return Locale.forLanguageTag(tag).toLanguageTag().takeIf { it.isNotBlank() && it != "und" }.orEmpty()
    }
}
