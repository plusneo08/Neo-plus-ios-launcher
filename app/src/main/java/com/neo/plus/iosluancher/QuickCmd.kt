package com.neo.plus.iosluancher

import org.json.JSONObject
import java.util.Locale

/**
 * Instant phone commands: switches, volume, brightness, media keys and a few system pages.
 *
 * These are matched as WHOLE phrases by the phone itself, so they never wait for the AI model (a round trip of 1-3 seconds).
 * A phrase is only accepted when every word of it is understood; anything else returns null and is left to the other
 * planners or to the AI, so a sentence like "turn off wifi when I get home" is never half-executed.
 */
object QuickCmd {
    private val I = RegexOption.IGNORE_CASE

    private class Sw(val key: String)

    /** noun (regex) -> the action that switches it. */
    private val switches: List<Pair<Regex, Sw>> = listOf(
        "wi-?fi|wireless|wi fi" to "wifi",
        "bluetooth|blue tooth" to "bluetooth",
        "airplane mode|aeroplane mode|flight mode|airplane|aeroplane" to "airplane",
        "mobile data|cellular data|mobile internet|data connection|cellular|mobile network" to "mobile_data",
        "flash ?light|torch" to "flashlight",
        "dark mode|night mode|dark theme" to "dark_mode",
        "battery saver|battery saving|low power mode|low power|power saving mode|power saver" to "low_power",
        "do not disturb|don't disturb|dnd|focus mode|focus" to "dnd"
    ).map { (noun, key) -> Regex("^(?:the )?(?:$noun)$", I) to Sw(key) }

    private val verbFirst = Regex("^(turn on|switch on|enable|activate|start|put on|turn off|switch off|disable|deactivate|stop|shut off|kill|put off)\\s+(.+)$", I)
    private val nounFirst = Regex("^(?:turn|switch)\\s+(?:the\\s+)?(.+?)\\s+(on|off)$", I)
    private val nounOnly = Regex("^(.+?)\\s+(on|off)$", I)
    private val listSplit = Regex("\\s*(?:,\\s*and\\s+|,\\s*|\\s+and\\s+|\\s+plus\\s+)\\s*", I)

    private fun switchFor(noun: String): Sw? = switches.firstOrNull { it.first.matches(noun.trim()) }?.second

    private fun onWord(v: String) = v.lowercase(Locale.ROOT).let { it.startsWith("turn on") || it.startsWith("switch on") || it == "enable" || it == "activate" || it == "start" || it == "put on" }

    /** "wifi, bluetooth and the torch" -> one switch each; null when any word is not a known switch. */
    private fun switchList(nouns: String, on: Boolean): List<Pair<String, JSONObject>>? {
        val parts = nouns.split(listSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 6) return null
        return parts.map { p ->
            val sw = switchFor(p) ?: return null
            sw.key to JSONObject().put("on", on)
        }
    }

    private fun replyFor(actions: List<Pair<String, JSONObject>>): String {
        val names = mapOf(
            "wifi" to "Wi‑Fi", "bluetooth" to "Bluetooth", "airplane" to "Airplane mode", "mobile_data" to "Mobile data",
            "flashlight" to "Flashlight", "dark_mode" to "Dark mode", "low_power" to "Battery saver", "dnd" to "Focus"
        )
        return actions.joinToString(" ") { a ->
            val on = a.second.optBoolean("on", true)
            (names[a.first] ?: "") + (if (on) " on." else " off.")
        }.trim()
    }

    /** The switch actions that can safely be started while the sentence may still be going on (used by the microphone). */
    private val instantKeys = setOf("wifi", "bluetooth", "airplane", "mobile_data", "flashlight", "dark_mode", "low_power", "dnd")

    /**
     * [t]: the request in lower case, without the "hey neo" / "please" lead and without end punctuation.
     * Returns the plan, or null when the phrase is not one of the instant commands.
     */
    fun plan(t: String): LocalPlan? {
        val s = t.trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")
        if (s.isEmpty() || s.length > 80) return null
        switchPlan(s)?.let { return it }
        mediaPlan(s)?.let { return it }
        volumePlan(s)?.let { return it }
        brightnessPlan(s)?.let { return it }
        systemPlan(s)?.let { return it }
        return null
    }

    private fun switchPlan(s: String): LocalPlan? {
        // "turn on wifi", "enable bluetooth and the torch", "switch off mobile data"
        verbFirst.find(s)?.let { m ->
            val on = onWord(m.groupValues[1])
            switchList(m.groupValues[2].removePrefix("the ").trim(), on)?.let { return LocalPlan(replyFor(it), it) }
        }
        // "turn wifi off", "switch the torch on"
        nounFirst.find(s)?.let { m ->
            val on = m.groupValues[2] == "on"
            switchList(m.groupValues[1], on)?.let { return LocalPlan(replyFor(it), it) }
        }
        // "wifi off", "torch on", "bluetooth and wifi on"
        nounOnly.find(s)?.let { m ->
            val on = m.groupValues[2] == "on"
            switchList(m.groupValues[1], on)?.let { return LocalPlan(replyFor(it), it) }
        }
        return null
    }

    private fun mediaPlan(s: String): LocalPlan? {
        val cmd = when {
            Regex("^(?:next|skip)(?: this| the)?(?: song| track| music)$", I).matches(s) -> "next"
            Regex("^(?:previous|prev|last|go back to the previous)(?: song| track)$|^play (?:the )?previous(?: song| track)$", I).matches(s) -> "previous"
            Regex("^pause(?: the)?(?: music| song| track| playback| video)?$|^stop (?:the )?(?:music|song|track|playback)$", I).matches(s) -> "pause"
            Regex("^resume(?: the)?(?: music| song| track| playback| video)?$|^unpause(?: the)?(?: music| song| track)?$|^continue (?:playing|the music|playback)$", I).matches(s) -> "play"
            else -> return null
        }
        val say = when (cmd) { "next" -> "Next."; "previous" -> "Previous."; "pause" -> "Paused."; else -> "Playing." }
        return LocalPlan(say, listOf("media" to JSONObject().put("cmd", cmd)))
    }

    private fun volumePlan(s: String): LocalPlan? {
        fun dir(d: String, say: String) = LocalPlan(say, listOf("volume" to JSONObject().put("direction", d)))
        fun level(p: Int) = LocalPlan("Volume $p percent.", listOf("volume" to JSONObject().put("level", p.coerceIn(0, 200))))
        if (Regex("^(?:turn |put |make )?(?:the |your )?(?:volume|sound)(?: up| higher| louder)$|^(?:turn |make )?(?:it |that )?(?:louder|volume up)$|^(?:raise|increase|turn up)(?: the)? (?:volume|sound)$|^(?:turn it up)$|^(?:louder)$", I).matches(s)) return dir("up", "Volume up.")
        if (Regex("^(?:turn |put |make )?(?:the |your )?(?:volume|sound)(?: down| lower| quieter| softer)$|^(?:turn |make )?(?:it |that )?(?:quieter|softer|volume down)$|^(?:lower|reduce|decrease|turn down)(?: the)? (?:volume|sound)$|^(?:turn it down)$|^(?:quieter)$", I).matches(s)) return dir("down", "Volume down.")
        if (Regex("^(?:mute|silence)(?: the)?(?: phone| sound| volume| music| media)?$|^(?:turn|switch) (?:the )?(?:sound|volume) off$|^(?:turn|switch) off (?:the )?(?:sound|volume)$", I).matches(s)) return dir("mute", "Muted.")
        if (Regex("^(?:max(?:imum)?|full|highest) volume$|^volume (?:to )?(?:max(?:imum)?|full)$|^(?:set |turn )?(?:the )?volume (?:to )?(?:max(?:imum)?|full)$", I).matches(s)) return level(100)
        Regex("^(?:set |turn |put |make )?(?:the |your )?(?:media |music )?volume (?:to |at )?(\\d{1,3})(?: ?(?:%|percent|per cent))?$", I).find(s)?.let { return level(it.groupValues[1].toInt()) }
        return null
    }

    private fun brightnessPlan(s: String): LocalPlan? {
        fun level(p: Int) = LocalPlan("Brightness $p percent.", listOf("brightness" to JSONObject().put("level", p.coerceIn(0, 100))))
        Regex("^(?:set |turn |put |make )?(?:the |your )?(?:screen )?brightness (?:to |at )?(\\d{1,3})(?: ?(?:%|percent|per cent))?$", I).find(s)?.let { return level(it.groupValues[1].toInt()) }
        if (Regex("^(?:max(?:imum)?|full|highest) (?:screen )?brightness$|^(?:set |turn )?(?:the )?(?:screen )?brightness (?:to )?(?:max(?:imum)?|full)$", I).matches(s)) return level(100)
        if (Regex("^(?:min(?:imum)?|lowest) (?:screen )?brightness$|^(?:set |turn )?(?:the )?(?:screen )?brightness (?:to )?(?:min(?:imum)?|lowest)$", I).matches(s)) return level(1)
        return null
    }

    private fun systemPlan(s: String): LocalPlan? {
        fun sys(a: String, say: String) = LocalPlan(say, listOf("system" to JSONObject().put("action", a)))
        if (Regex("^(?:open |show |go to )?(?:the )?(?:recent apps|recents|recent|app switcher|multitasking)$", I).matches(s)) return sys("recents", "Recent apps.")
        if (Regex("^(?:open |show )?(?:the )?power menu$", I).matches(s)) return sys("power_menu", "Power menu.")
        if (Regex("^(?:open |show |pull down )?(?:the )?control cent(?:er|re)$", I).matches(s)) return LocalPlan("Control Center.", listOf("control_center" to JSONObject()))
        // "open wifi settings", "bluetooth settings", "go to display settings"
        Regex("^(?:open |show |go to |take me to )?(?:the )?(wi-?fi|bluetooth|display|sound|battery|apps|location|accessibility|airplane mode|airplane|storage|date|language|security|wallpaper) settings$", I).find(s)?.let { m ->
            val page = when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "wifi", "wi-fi" -> "wifi"
                "airplane mode" -> "airplane"
                else -> m.groupValues[1].lowercase(Locale.ROOT)
            }
            return LocalPlan("Opening settings.", listOf("settings" to JSONObject().put("page", page)))
        }
        return null
    }

    /**
     * True when [t] is a command that can be started the moment it is heard, before the speaker has even finished the
     * sentence: a plain on/off switch, volume up / down / mute or a media key. Commands with a number ("volume 5" may become
     * "volume 50") and commands that could continue ("turn off wifi and bluetooth") are NOT meant to fire early; the
     * microphone waits until the words have stopped changing for a moment before it uses this.
     */
    fun isInstant(t: String): Boolean {
        val p = plan(t) ?: return false
        if (p.actions.size != 1) return false
        return p.actions.all { (name, args) ->
            when (name) {
                in instantKeys -> true
                "media" -> true
                "volume" -> !args.has("level")
                else -> false
            }
        }
    }
}
