package com.neo.plus.ioslauncher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A short instruction set for the live voice call with the assistant (wake-up call, morning call, "call Neo").
 *
 * The normal assistant prompt is about 33,000 characters (every phone action, the app list ...). Most of what is said on a call is
 * plain conversation, so that part is only sent when the request looks like it needs the phone or live information
 * ([needsFull]). If the short prompt turns out not to be enough, the model answers with the single word NEED_TOOLS (it has no
 * sentence end, so it is never spoken, and it is not an action tag, so nothing runs), and the same request is asked again with
 * the full prompt ([wantsFull]).
 */
object CallPrompt {

    private val NEEDS_FULL = Regex(
        "\\b(open|launch|call|phone|ring|dial|text|sms|send|message|messages|whatsapp|facebook|messenger|tiktok|instagram|gmail|email|mail|" +
            "reply|answer|remind|reminder|alarm|timer|wake|flashlight|torch|wifi|wi-fi|bluetooth|airplane|flight mode|data|volume|louder|quieter|" +
            "brightness|play|pause|skip|music|song|navigate|directions|route|take me|settings|mode|lock|unlock|screenshot|screen|camera|photo|" +
            "picture|calendar|schedule|appointment|meeting|event|weather|forecast|temperature|rain|news|headlines|stock|stocks|share price|" +
            "price|prices|score|scores|result|results|fixture|match|search|google|look up|lookup|find|latest|today|tonight|tomorrow|current|currently|" +
            "now|president|prime minister|champion|winner|bitcoin|exchange rate|near me|opening hours|remember|forget|skill|approve|approval|" +
            "notification|notifications|missed|download|upload|file|files|excel|spreadsheet|word|post|share|turn|switch|set|change|show|read|" +
            "write|create|make|add|delete|save|copy|type|tap|click|scroll|enable|disable|close|start|stop|go to|check)\\b",
        RegexOption.IGNORE_CASE
    )

    private val FULL_MARK = Regex("\\bNEED_TOOLS\\b")

    /** True when the request may need phone actions or live information, so the full prompt (and live search) is used. */
    fun needsFull(userText: String): Boolean = NEEDS_FULL.containsMatchIn(userText)

    /** True when the (possibly partial) reply is the "I need the full tools" marker (NEED_TOOLS). */
    fun wantsFull(reply: CharSequence): Boolean = FULL_MARK.containsMatchIn(reply)

    /** Short phrases played while a slow answer is being made (see Speaker.filler). Their voice is made ahead of time. */
    val fillers = listOf("One moment.", "Let me check that.", "Sure, one second.")

    fun filler(n: Int): String = fillers[Math.floorMod(n, fillers.size)]

    /** Opens the connection to Google and makes the voice of the filler phrases in the background (cached; free when already made). */
    fun warmFillers(ctx: Context) {
        val app = ctx.applicationContext
        Llm.warm()
        Thread { for (t in fillers) { try { Speaker.prewarmSpeech(app, t) } catch (e: Exception) { } } }.start()
    }

    fun build(ctx: Context): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val weather = WeatherRepo.cached(ctx)?.let {
            "${it.temp}°${if (it.fahrenheit) "F" else "C"}, ${weatherText(it.code)}, high ${it.hi}°, low ${it.lo}°" +
                if (it.place.isNotBlank()) " in ${it.place}" else ""
        } ?: "unknown"
        val facts = PhoneActions.memoryFacts(ctx)
        val mem = if (facts.isEmpty()) "" else "What you know about the user: " + facts.joinToString("; ") + ".\n"
        return """
You are Neo Plus Assistant, the built-in assistant of the NEO PLUS iOS launcher on an Android phone, and you are on a live voice call with the user. Talk naturally and warmly, like a friend on the phone: chat, answer questions, explain, translate, do maths, give advice. Always reply in the language the user spoke, including Chichewa (Chinyanja) and Swahili. Your reply is spoken aloud by a human-sounding voice: plain sentences only, contractions, no markdown, no lists, no emoji, never say you are an AI model. Keep it to one to three short sentences unless the user wants detail. Answer general knowledge, history, maths and simple facts yourself.

If the user asks you to DO something on the phone (open an app, call, message, alarm, timer, settings, music, navigation, reading messages or notifications ...) or needs live information (news, weather, sport, prices, who currently holds a role, their calendar, a web search), do not answer: reply with ONLY the single word NEED_TOOLS and nothing else, and the phone will give you the tools.

Context:
Now: $now (${TimeZone.getDefault().id}).
Weather: $weather.
${mem}""".trimIndent() + Skills.promptFor(ctx, "assistant")
    }
}
