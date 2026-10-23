package com.neo.plus.iosluancher

import android.content.Context
import org.json.JSONObject

/**
 * Last resort for apps whose buttons have no text, description or id (Facebook Lite is one): the accessibility tree has
 * nothing to match, so the screen is photographed, Gemini says where the named button is, and that spot is touched.
 *
 * Only used by the social poster after the normal label search has found nothing for several seconds, and at most a few
 * times per post. Each call costs one Gemini request and sends one screenshot of the app being driven.
 */
object VisionTap {
    class Spot(val xPer1000: Int, val yPer1000: Int)

    private const val SYSTEM = """You find buttons on an Android screenshot. Return ONLY a JSON object, no code fences:
{"found":true,"x":0-1000,"y":0-1000}
x and y are the CENTRE of the button, as thousandths of the picture width (x) and height (y).
Rules:
- Only report a control that is clearly visible and clearly the one asked for (its words or its usual icon).
- If it is not on this screen, or you are not sure, return {"found":false}. Never guess.
- Never choose buttons that delete, block, report, log out or pay."""

    /** [name] e.g. "Share", "Post", "Your story". Null when the model could not see it (or the AI / screenshot is unavailable). */
    fun locate(ctx: Context, appLabel: String, name: String, extra: String = "It is the button that publishes or continues a post."): Spot? {
        val svc = IosControlService.instance ?: return null
        val key = ctx.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (KeyPool.parse(key).isEmpty()) return null
        KeyPool.attach(ctx)
        val shot = svc.captureScreen() ?: return null
        val ask = "App: $appLabel. Find this control on the screen: \"$name\" (a button, icon, tab, link or text box; it may have no words, only an icon or an arrow). $extra Write the JSON now."
        val raw = try {
            Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, SYSTEM, listOf(true to ask), shot, search = false)
        } catch (e: Exception) { return null }
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val s = cleaned.indexOf('{'); val e = cleaned.lastIndexOf('}')
            val j = JSONObject(cleaned.substring(s, e + 1))
            if (!j.optBoolean("found", false)) null
            else Spot(j.getInt("x").coerceIn(0, 1000), j.getInt("y").coerceIn(0, 1000))
        } catch (e: Exception) { null }
    }

    /** Touches the spot. Needs the screen size from the screenshot that found it. */
    fun tap(spot: Spot, pressMs: Long = 60L): Boolean {
        val size = IosControlService.instance?.lastShotSize ?: return false
        return UiDriver.tapAt(spot.xPer1000 / 1000f * size.first, spot.yPer1000 / 1000f * size.second, pressMs)
    }

    /** True when [a] and [b] are about the same place (the button is still where it was). */
    fun same(a: Spot, b: Spot) = Math.abs(a.xPer1000 - b.xPer1000) < 60 && Math.abs(a.yPer1000 - b.yPer1000) < 60
}
