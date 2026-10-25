package com.neo.plus.iosluancher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt
import android.util.LruCache
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guesses what language a text is in. Chichewa (Chinyanja) is spelled the way it sounds and uses a small set of very
 * common words that do not exist in English, so counting them is a reliable signal for short messages.
 */
object LangGuess {
    private val chichewa = setOf(
        "muli", "bwanji", "zikomo", "kwambiri", "inde", "ayi", "chonde", "ndikufuna", "ndili", "uthenga", "moni", "pepani",
        "wanu", "watumiza", "tsopano", "lero", "mawa", "dzulo", "ndalama", "chakudya", "abambo", "amayi", "ndiwerenge",
        "werengani", "mukufuna", "tiyeni", "bwino", "koma", "tikuwonana", "madzulo", "masana", "ndipo", "kapena", "ntchito",
        "kunyumba", "galimoto", "sukulu", "wabwino", "ndiwe", "iwe", "inu", "ife", "iwo", "kodi", "chiyani", "liti", "kuti",
        "ndani", "ndikupita", "ndikubwera", "ndatumiza", "walandira", "analandira", "watumizidwa", "ndikuthokozani",
        "mwadzuka", "mwauka", "mwaswera", "ndili bwino", "zili bwanji", "tumizani", "ndithu", "mnzanga", "bambo", "mayi",
        "mwana", "anthu", "pano", "uko", "komwe", "chifukwa", "ndiye", "tidzakumana", "mutipatse", "ndikukuyembekezera"
    )

    /** Returns the Chichewa locale when the text looks Chichewa, otherwise null (use the phone's language). */
    fun detect(text: String): Locale? {
        val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val hits = words.count { it in chichewa }
        val strong = hits >= 2 || (hits == 1 && words.size <= 3)
        return if (strong) Locale("ny") else null
    }

    fun isChichewa(text: String) = detect(text) != null
}

/**
 * A natural, human-sounding voice from Google's Gemini speech models (needs a Gemini key). Falls back to the phone's
 * own voice when there is no key, no internet, or the text is in a language the cloud voice can't be trusted with.
 */
object NaturalVoice {
    class Cfg(val key: String, val voice: String)

    /** Gemini speech voices by gender. The chosen gender never uses a voice from the other list. */
    val FEMALE = listOf("Aoede", "Kore")
    val MALE = listOf("Charon", "Orus")

    /** The chosen voice first, then a second voice of the SAME gender to try if the first one fails. */
    private fun sameGender(voice: String): List<String> {
        val list = if (voice in MALE) MALE else FEMALE
        return listOf(voice) + list.filter { it != voice }
    }

    private val models = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")
    const val RATE = 24000

    fun config(ctx: Context): Cfg? {
        val p = ctx.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE)
        migrateToGeminiOnly(p)
        KeyPool.attach(ctx)
        val key = p.getString("key", "") ?: ""
        if (key.isBlank()) return null
        val male = (p.getString("voice_gender", "female") ?: "female") == "male"
        return Cfg(key.trim(), if (male) MALE[0] else FEMALE[0])
    }

    fun gender(ctx: Context): String =
        ctx.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getString("voice_gender", "female") ?: "female"

    /** Voices already made this session, so a repeated sentence ("Calling Mum") plays at once. About 8 MB at most. */
    private val cache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** "<key>|<model>" -> epoch ms until which that key is known to have NO quota ("limit: 0") on that voice model. */
    private val noAccess = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile private var warmed = false

    /**
     * Called once when the orb starts. Opens the connection to Google ahead of time (saves the TLS handshake on the first
     * real reply) and makes the most common short replies, so "Yes?" after the wake phrase plays at once.
     */
    fun prewarm(key: String, voice: String) {
        if (warmed) return
        warmed = true
        try {
            val c = URL("https://generativelanguage.googleapis.com/").openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 4_000
            try { c.responseCode; (c.errorStream ?: c.inputStream)?.use { it.readBytes() } } catch (e: Exception) { }
            c.disconnect()
        } catch (e: Exception) { }
        for (t in listOf("Yes?", "Okay.")) {
            try { synth(key, t, voice, fast = true) } catch (e: Exception) { }
        }
    }

    /** Raw 16-bit mono PCM at 24 kHz. */
    fun synth(key: String, text: String, voice: String, fast: Boolean = false): ByteArray {
        val ck = voice + "|" + text
        cache.get(ck)?.let { return it }
        // fast = the first piece of a reply: the lighter model is tried first so the first words start sooner
        val order = if (fast) models.reversed() else models
        var last: Exception? = null
        val now = System.currentTimeMillis()
        // A different voice never fixes a quota problem, so a model that is out of quota / not allowed is skipped for
        // every voice, and a rejected key stops the whole attempt at once (it used to try all 4 combinations).
        val skip = HashSet<String>()
        for (v in sameGender(voice)) {
            for (m in order) {
                if (m in skip || (noAccess[key.hashCode().toString() + "|" + m] ?: 0L) > now) continue
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                try {
                    val pcm = polish(KeyPool.run(key, m) { k -> synthWith(m, k, text, v) })
                    cache.put(ck, pcm)
                    return pcm
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: KeyLimitException) {
                    last = e; skip += m
                    if (e.noAccess) noAccess[key.hashCode().toString() + "|" + m] = System.currentTimeMillis() + 6 * 3_600_000L
                } catch (e: Llm.LlmException) {
                    last = e
                    if (e.message?.startsWith("The API key was rejected") == true) throw e
                } catch (e: Exception) { last = e }
            }
        }
        throw last ?: Exception("No voice")
    }

    private fun synthWith(model: String, key: String, text: String, voice: String): ByteArray {
        // Only the words to be spoken go to the voice model. An instruction such as "say this naturally" in the same
        // text is read out loud by the model ("speak in natural human voice..."), so there is none. The voice name
        // already decides how it sounds. Ending with a full stop stops the model from inventing a trailing sound.
        val spoken = text.trim().let { if (it.isNotEmpty() && it.last() !in ".!?…\"”)") it + "." else it }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", spoken)))))
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        )
                    )
            ).toString()
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 6_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                KeyPool.limitFrom(code, err)?.let { throw it } // out of quota: the next key takes over
                if (KeyPool.rejectedFrom(code, err)) throw Llm.LlmException("The API key was rejected ($code)")
                throw Exception("Voice request failed ($code)")
            }
            val res = c.inputStream.bufferedReader().use { it.readText() }
            // The audio is not always in the first part of the reply: look through every part of every candidate.
            var data: String? = null
            val cands = JSONObject(res).optJSONArray("candidates")
            loop@ for (ci in 0 until (cands?.length() ?: 0)) {
                val parts = cands!!.optJSONObject(ci)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (pi in 0 until parts.length()) {
                    val d = parts.optJSONObject(pi)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (d.isNotEmpty()) { data = d; break@loop }
                }
            }
            if (data == null) throw Exception("No audio in reply")
            val pcm = Base64.decode(data, Base64.DEFAULT)
            if (pcm.size < 2000) throw Exception("Empty audio")
            return pcm
        } finally {
            c.disconnect()
        }
    }

    /**
     * Takes the hiss out of the whole spoken piece, not just its tail. The voice model leaves a steady high-frequency
     * hiss under the words and between them. Three gentle steps, all done in memory in a few milliseconds:
     *  1. a 60 Hz high-pass (rumble, DC offset),
     *  2. a 7 kHz low-pass (hiss lives up there; speech keeps its clarity, "s" and "t" stay),
     *  3. a soft noise gate: stretches that are only noise are turned down to about a third (not to silence, so the voice
     *     never "pumps"), opening 3 ms before a word and closing slowly after it.
     * Pieces that are already clean or very short are passed through untouched.
     */
    internal fun denoise(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val frame = RATE / 200 // 5 ms
        if (n < frame * 20) return pcm
        val x = DoubleArray(n) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort().toDouble() }

        // biquad: direct form I, applied in place
        fun biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0; val na1 = a1 / a0; val na2 = a2 / a0
            for (i in x.indices) {
                val v = x[i]
                val y = nb0 * v + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
                x2 = x1; x1 = v; y2 = y1; y1 = y
                x[i] = y
            }
        }
        fun lowHigh(fc: Double, high: Boolean) {
            val w = 2.0 * Math.PI * fc / RATE
            val c = Math.cos(w); val al = Math.sin(w) / (2.0 * 0.7071)
            if (high) biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            else biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }
        lowHigh(60.0, true)
        lowHigh(7000.0, false)

        // soft noise gate
        val frames = n / frame
        val rms = DoubleArray(frames)
        for (f in 0 until frames) {
            var acc = 0.0
            for (i in f * frame until (f + 1) * frame) acc += x[i] * x[i]
            rms[f] = sqrt(acc / frame)
        }
        val sorted = rms.sorted()
        val floor = sorted[((frames - 1) * 0.08).toInt()].coerceAtLeast(8.0)
        val speechLevel = sorted[((frames - 1) * 0.90).toInt()]
        if (speechLevel > floor * 3.0) {
            val thr = maxOf(floor * 2.4, speechLevel * 0.05)
            var g = 1.0
            val opn = 1.0 - Math.exp(-1.0 / (RATE * 0.003))   // 3 ms to open
            val cls = 1.0 - Math.exp(-1.0 / (RATE * 0.060))  // 60 ms to close
            for (f in 0 until frames) {
                // open when this frame or a neighbour is above the threshold (so a word start is never clipped)
                val loud = rms[f] >= thr || (f > 0 && rms[f - 1] >= thr) || (f + 1 < frames && rms[f + 1] >= thr)
                val target = if (loud) 1.0 else 0.33
                for (i in f * frame until (f + 1) * frame) {
                    g += (target - g) * (if (target > g) opn else cls)
                    x[i] *= g
                }
            }
        }
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = x[i].toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /**
     * Cleans the end of every spoken piece. The voice model adds a hissy / static tail after the last word (it sounds
     * like a radio switching off). Walking back from the end, every frame that is quiet, or noise-like (hiss: lots of
     * zero crossings, little energy), is cut. Only 30 ms is kept after the last real word, then a quick smooth fade
     * out, and 200 ms of true silence is added so the player never stops on a sound.
     */
    internal fun polish(pcmIn: ByteArray): ByteArray {
        val pcm = denoise(pcmIn)
        val n = pcm.size / 2
        val frame = RATE / 200 // 5 ms
        if (n < frame * 20) return pcm
        val s = ShortArray(n) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort() }
        val frames = n / frame
        val rms = DoubleArray(frames)
        val zcr = DoubleArray(frames)
        for (f in 0 until frames) {
            var acc = 0.0
            var cross = 0
            val from = f * frame
            for (i in from until from + frame) {
                acc += s[i].toDouble() * s[i]
                if (i > from && ((s[i] >= 0) != (s[i - 1] >= 0))) cross++
            }
            rms[f] = sqrt(acc / frame)
            zcr[f] = cross.toDouble() / frame
        }
        val ref = rms.sorted()[((frames - 1) * 0.9).toInt()]
        var end = n
        if (ref > 150) {
            // Speech = three frames in a row (15 ms) that are loud enough AND smooth (few zero crossings). Hiss, static
            // and isolated crackle never pass this test, including low rumble and clicks that the old rule counted as
            // "voice" and therefore kept. Everything after the last speech frame is cut, keeping 110 ms so word endings
            // such as "s" and "t" are not clipped.
            fun speech(f: Int) = rms[f] >= ref * 0.30 && zcr[f] < 0.20
            var last = -1
            for (f in frames - 1 downTo 2) if (speech(f) && speech(f - 1) && speech(f - 2)) { last = f; break }
            if (last >= 0) end = min(n, (last + 1) * frame + RATE * 110 / 1000)
        }
        val fadeOut = min(end, RATE * 70 / 1000)
        for (i in 0 until fadeOut) {
            val idx = end - 1 - i
            val g = i.toFloat() / fadeOut
            s[idx] = (s[idx] * g * g).toInt().toShort()
        }
        val fadeIn = min(end, RATE * 6 / 1000)
        for (i in 0 until fadeIn) s[i] = (s[i] * (i.toFloat() / fadeIn)).toInt().toShort()
        val pad = RATE * 200 / 1000
        val out = ByteArray((end + pad) * 2) // the padding stays zero = silence
        for (i in 0 until end) { out[2 * i] = (s[i].toInt() and 0xFF).toByte(); out[2 * i + 1] = (s[i].toInt() shr 8).toByte() }
        return out
    }
}

/**
 * One shared text-to-speech engine. Speaks sentence by sentence so the floating orb can show each sentence as a
 * subtitle while it is spoken. Chichewa is spoken with a Chichewa voice if the phone has one; most phones do not,
 * so it falls back to Swahili (closest widely available pronunciation), then to the phone's language.
 */
object Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initFailed = false
    private var queued: (() -> Unit)? = null
    private var queuedGiveUp: (() -> Unit)? = null

    @Volatile
    var speaking = false
        private set

    /** Set when the spoken language had to be replaced by a different voice (shown to the user once). */
    @Volatile
    var voiceNote: String = ""
        private set

    private var batch = 0L
    private val sentences = HashMap<String, Pair<String, Boolean>>() // utteranceId -> (sentence, isLast)
    private var onSentence: ((String) -> Unit)? = null
    private var onFinished: (() -> Unit)? = null

    private var appCtx: Context? = null
    @Volatile private var naturalToken = 0L
    private var track: AudioTrack? = null
    private val pool = Executors.newFixedThreadPool(5)

    /** Warms up the cloud voice in the background (once per run). Safe to call when there is no key. */
    /** Makes the voice for a whole text ahead of time (the wake-up summary), so it starts at once when it is spoken. Blocks until done. */
    fun prewarmSpeech(ctx: Context, text: String) {
        val cfg = NaturalVoice.config(ctx.applicationContext) ?: return
        if (text.isBlank() || LangGuess.isChichewa(text)) return
        chunk(split(text)).forEachIndexed { i, c ->
            try { NaturalVoice.synth(cfg.key, c, cfg.voice, fast = i == 0) } catch (e: Exception) { }
        }
    }

    fun prewarm(ctx: Context) {
        val cfg = NaturalVoice.config(ctx.applicationContext) ?: return
        pool.execute { try { NaturalVoice.prewarm(cfg.key, cfg.voice) } catch (e: Exception) { } }
    }

    /** [natural] = false always uses the phone's own voice (no Gemini request). */
    fun speak(ctx: Context, text: String, onStart: ((String) -> Unit)? = null, onDone: (() -> Unit)? = null, natural: Boolean = true) {
        val app = ctx.applicationContext
        appCtx = app
        main.post {
            if (text.isBlank()) { onDone?.invoke(); return@post }
            // One voice for everything: when a Gemini key exists, command answers and announcements use the same Gemini
            // voice as chat replies (the phone's own voice is a different speaker and sounded like a second person).
            val cfg = NaturalVoice.config(app)
            if (cfg != null && !LangGuess.isChichewa(text)) speakNatural(app, text, cfg, onStart, onDone)
            else speakSystem(app, text, onStart, onDone)
        }
    }

    private fun speakSystem(app: Context, text: String, onStart: ((String) -> Unit)?, onDone: (() -> Unit)?) {
        // Never talk over a Gemini voice that is still playing (two voices at once).
        naturalToken++
        try { track?.pause(); track?.flush() } catch (e: Exception) { }
        if (ready) {
            say(text, onStart, onDone)
        } else if (initFailed) {
            onStart?.invoke(text)
            main.postDelayed({ onDone?.invoke() }, 1500L + text.length * 45L)
        } else {
            queued = { say(text, onStart, onDone) }
            queuedGiveUp = { onStart?.invoke(text); main.postDelayed({ onDone?.invoke() }, 1500L + text.length * 45L) }
            if (tts == null) init(app)
        }
    }

    /**
     * Joins short sentences so the voice keeps its natural flow; each chunk is one subtitle. The first chunk is short
     * so the voice can start quickly; the rest are longer because they are made while the first one is playing.
     */
    private fun chunk(sentencesIn: List<String>): List<String> {
        // A long first sentence is cut at its first comma / colon so the first piece to make (and hear) is short.
        val sentences = sentencesIn.toMutableList()
        if (sentences.isNotEmpty() && sentences[0].length > 70) {
            val m = Regex("[,;:] ").findAll(sentences[0]).firstOrNull { it.range.first in 18..70 }
            if (m != null) {
                val first = sentences[0]
                sentences[0] = first.substring(0, m.range.first + 1).trim()
                sentences.add(1, first.substring(m.range.last + 1).trim())
            }
        }
        val out = mutableListOf<String>()
        var cur = ""
        for (s in sentences) {
            val limit = if (out.isEmpty()) 40 else 220
            if (cur.isNotEmpty() && cur.length + s.length > limit) { out += cur; cur = s }
            else cur = if (cur.isEmpty()) s else "$cur $s"
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun speakNatural(app: Context, text: String, cfg: NaturalVoice.Cfg, onStart: ((String) -> Unit)?, onDone: (() -> Unit)?) {
        try { tts?.stop() } catch (e: Exception) { }
        try { track?.pause(); track?.flush() } catch (e: Exception) { }
        sentences.clear()
        val chunks = chunk(split(text))
        if (chunks.isEmpty()) { onDone?.invoke(); return }
        val my = ++naturalToken
        speaking = true
        // Every chunk is requested at once (the pool runs several together), so while one plays the next are ready.
        val jobs: List<Future<ByteArray>> = chunks.mapIndexed { i, c -> pool.submit<ByteArray> { NaturalVoice.synth(cfg.key, c, cfg.voice, fast = i == 0) } }
        pool.execute {
            try {
                for (i in chunks.indices) {
                    if (naturalToken != my) { jobs.forEach { it.cancel(true) }; return@execute }
                    // The first words must not keep the user waiting: after 5 s the phone's own voice takes over.
                    val pcm = try { jobs[i].get(if (i == 0) 5L else 25L, TimeUnit.SECONDS) } catch (e: Exception) { null }
                    if (naturalToken != my) { jobs.forEach { it.cancel(true) }; return@execute }
                    if (pcm == null) { // cloud voice failed: finish with the phone's own voice
                        jobs.forEach { it.cancel(true) }
                        val rest = chunks.subList(i, chunks.size).joinToString(" ")
                        main.post { if (naturalToken == my) { speaking = false; speakSystem(app, rest, onStart, onDone) } }
                        return@execute
                    }
                    main.post { if (naturalToken == my) onStart?.invoke(chunks[i]) }
                    playPcm(pcm, my)
                }
                main.post { if (naturalToken == my) { speaking = false; onDone?.invoke() } }
            } catch (e: Exception) {
                main.post { if (naturalToken == my) { speaking = false; onDone?.invoke() } }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Live speech: speak a reply while it is still being written          */
    /* ------------------------------------------------------------------ */

    private class LiveItem(val text: String, val job: Future<ByteArray>?) // job == null: the reply is complete

    @Volatile private var liveQ: java.util.concurrent.LinkedBlockingQueue<LiveItem>? = null
    @Volatile private var liveCfg: NaturalVoice.Cfg? = null
    @Volatile private var liveToken = 0L
    private val liveLock = Any()
    private var liveEnded = false
    @Volatile private var liveFedOnce = false   // reset by liveStart: the first piece of a live reply uses the fast voice model
    private var liveFailed = false              // guarded by liveLock
    private val liveRest = StringBuilder()      // text the Gemini voice could not make; guarded by liveLock

    /**
     * Opens a live session (stops anything being said). False when there is no Gemini key. Finished pieces of the reply are
     * then fed with [liveFeed] and the end is signalled with [liveEnd]. If the Gemini voice fails part-way, the rest of the
     * reply is spoken with the phone's own voice once the reply is complete.
     */
    fun liveStart(ctx: Context): Boolean {
        val app = ctx.applicationContext
        appCtx = app
        val cfg = NaturalVoice.config(app) ?: return false
        try { tts?.stop() } catch (e: Exception) { }
        try { track?.pause(); track?.flush() } catch (e: Exception) { }
        val my = ++naturalToken
        val q = java.util.concurrent.LinkedBlockingQueue<LiveItem>()
        synchronized(liveLock) { liveEnded = false; liveFailed = false; liveRest.setLength(0) }
        liveFedOnce = false
        liveQ = q
        liveCfg = cfg
        liveToken = my
        speaking = true
        pool.execute {
            var idle = 0
            var firstItem = true
            try {
                while (naturalToken == my) {
                    val item = q.poll(200, TimeUnit.MILLISECONDS)
                    if (item == null) { if (++idle > 600) break else continue } // nothing for 2 minutes: give up
                    idle = 0
                    val job = item.job ?: break
                    // The first words must not keep the user waiting: after 5 s the phone's own voice takes over.
                    val pcm = try { job.get(if (firstItem) 5L else 25L, TimeUnit.SECONDS) } catch (e: Exception) { null }
                    firstItem = false
                    if (naturalToken != my) break
                    if (pcm == null) { // cloud voice failed: this piece and everything after it is spoken by the phone's voice
                        var speakNow = false
                        synchronized(liveLock) {
                            liveFailed = true
                            liveRest.append(item.text).append(' ')
                            while (true) { val more = q.poll() ?: break; if (more.job != null) liveRest.append(more.text).append(' ') }
                            speakNow = liveEnded
                        }
                        if (speakNow) speakLiveRest(my)
                        break
                    }
                    playPcm(pcm, my)
                }
            } catch (e: Exception) {
                // playback problem: treat like a failed voice so the reply is still read out
                var speakNow = false
                synchronized(liveLock) { liveFailed = true; speakNow = liveEnded }
                if (speakNow) speakLiveRest(my)
            } finally {
                q.forEach { it.job?.cancel(true) }
                main.post { if (naturalToken == my) speaking = false }
            }
        }
        return true
    }

    private fun speakLiveRest(my: Long) {
        val text = synchronized(liveLock) { liveRest.toString().also { liveRest.setLength(0) } }.trim()
        val app = appCtx ?: return
        if (text.isEmpty()) return
        main.post { if (naturalToken == my) { speaking = false; speakSystem(app, text, null, null) } }
    }

    /** One finished piece of the reply (a sentence or a few). Its voice is made at once, in order. */
    fun liveFeed(text: String) {
        val q = liveQ ?: return
        val cfg = liveCfg ?: return
        if (naturalToken != liveToken) return
        val t = split(text).joinToString(" ").trim()
        if (t.isEmpty()) return
        synchronized(liveLock) {
            if (liveFailed) { liveRest.append(t).append(' '); return } // Gemini voice already failed: keep it for the phone voice
        }
        val first = q.isEmpty() && !liveFedOnce
        liveFedOnce = true
        q.add(LiveItem(t, pool.submit<ByteArray> { NaturalVoice.synth(cfg.key, t, cfg.voice, fast = first) }))
    }

    /** The reply is complete: the voice finishes what was fed and then stops. */
    fun liveEnd() {
        val q = liveQ ?: return
        var speakNow = false
        synchronized(liveLock) {
            liveEnded = true
            if (liveFailed) speakNow = true else q.add(LiveItem("", null))
        }
        if (speakNow) speakLiveRest(liveToken)
    }

    private fun playPcm(pcm: ByteArray, my: Long) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(NaturalVoice.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            val frames = pcm.size / 2
            t.play()
            val limit = System.currentTimeMillis() + frames * 1000L / NaturalVoice.RATE + 2000L
            while (naturalToken == my && t.playbackHeadPosition < frames && System.currentTimeMillis() < limit) Thread.sleep(25)
        } finally {
            try { t.setVolume(0f) } catch (e: Exception) { }
            try { t.stop() } catch (e: Exception) { }
            try { t.release() } catch (e: Exception) { }
            if (track === t) track = null
        }
    }

    fun stop() {
        main.post {
            naturalToken++
            try { track?.pause(); track?.flush() } catch (e: Exception) { }
            try { tts?.stop() } catch (e: Exception) { }
            sentences.clear()
            speaking = false
            onSentence = null
            onFinished = null
        }
    }

    /** Starts Google's speech engine when it is installed (it sounds far more natural than most phone-maker engines), otherwise the phone's default. */
    private fun init(app: Context, engine: String? = "com.google.android.tts") {
        val onInit = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    queued?.invoke()
                    queued = null
                    queuedGiveUp = null
                } else if (engine != null) {
                    try { tts?.shutdown() } catch (e: Exception) { }
                    tts = null
                    init(app, null)
                } else {
                    initFailed = true
                    tts = null
                    queuedGiveUp?.invoke()
                    queued = null
                    queuedGiveUp = null
                }
            }
        }
        tts = try {
            if (engine != null) TextToSpeech(app, onInit, engine) else TextToSpeech(app, onInit)
        } catch (e: Exception) {
            if (engine != null) { init(app, null); return }
            null
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val e = sentences[utteranceId ?: return] ?: return
            main.post { onSentence?.invoke(e.first) }
        }

        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId)

        private fun finish(utteranceId: String?) {
            val e = sentences.remove(utteranceId ?: return) ?: return
            if (e.second) main.post {
                speaking = false
                val done = onFinished
                onFinished = null
                onSentence = null
                done?.invoke()
            }
        }
    }

    private fun say(text: String, onStart: ((String) -> Unit)?, onDone: (() -> Unit)?) {
        val engine = tts ?: run { onDone?.invoke(); return }
        val loc = pickLocale(engine, LangGuess.detect(text))
        engine.setLanguage(loc)
        applySystemVoice(engine, loc)
        val parts = split(text)
        if (parts.isEmpty()) { onDone?.invoke(); return }
        onSentence = onStart
        onFinished = onDone
        speaking = true
        val b = ++batch
        sentences.clear()
        parts.forEachIndexed { i, s ->
            val id = "neo_${b}_$i"
            sentences[id] = s to (i == parts.lastIndex)
            engine.speak(s, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    /** Best installed voice of the chosen gender. Never picks a voice that is tagged as the other gender. */
    private fun applySystemVoice(engine: TextToSpeech, loc: Locale) {
        try {
            val male = appCtx?.let { NaturalVoice.gender(it) } == "male"
            // Google voices carry the speaker in their name (en-us-x-iol-local). Known male / female speakers are used
            // even when the name has no "male" / "female" tag.
            val maleIds = setOf("iol", "iom", "tpd", "rjs", "gbb", "gbd", "ahs", "cfc")
            val femaleIds = setOf("iob", "iof", "iog", "sfg", "tpc", "tpf", "gba", "gbc", "gbg", "fis", "afs", "cmn")
            fun speaker(v: Voice) = v.name.split('-', '_').firstOrNull { it in maleIds || it in femaleIds }
            fun isFemale(v: Voice) = v.name.contains("female", ignoreCase = true) || speaker(v) in femaleIds
            fun isMale(v: Voice) = (v.name.contains("male", ignoreCase = true) && !v.name.contains("female", ignoreCase = true)) || speaker(v) in maleIds
            val installed = engine.voices?.filter {
                it.locale.language == loc.language && it.features?.contains("notInstalled") != true
            }.orEmpty()
            val tagged = installed.filter { if (male) isMale(it) else isFemale(it) }
            // Never a voice known to be the other gender; untagged voices only when nothing tagged exists.
            val candidates = when {
                tagged.isNotEmpty() -> tagged
                male -> installed.filter { !isFemale(it) }
                else -> installed.filter { !isMale(it) }
            }
            val pick: Voice? = candidates.sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                    .thenByDescending { loc.country.isNotEmpty() && it.locale.country == loc.country }
                    .thenByDescending { it.quality }
            ).firstOrNull()
            engine.setSpeechRate(0.95f)
            if (pick != null) engine.voice = pick
            engine.setPitch(if (tagged.isNotEmpty()) 1.0f else if (male) 0.85f else 1.0f)
        } catch (e: Exception) { }
    }

    private fun available(e: TextToSpeech, l: Locale) = try {
        e.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE
    } catch (ex: Exception) { false }

    private fun pickLocale(e: TextToSpeech, wanted: Locale?): Locale {
        val phone = Locale.getDefault()
        val fallback = if (available(e, phone)) phone else Locale.ENGLISH
        if (wanted == null) { voiceNote = ""; return fallback }
        if (available(e, wanted)) { voiceNote = ""; return wanted }
        val sw = Locale("sw")
        if (available(e, sw)) {
            voiceNote = "No Chichewa voice is installed, so I read it with a Swahili voice."
            return sw
        }
        voiceNote = "No Chichewa voice is installed, so I read it with the phone's normal voice."
        return fallback
    }

    /** Sentences for subtitles; very long ones are cut at a word so the engine's length limit is never hit. */
    private fun split(text: String): List<String> {
        val clean = text
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[*_#`]+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
        val out = mutableListOf<String>()
        clean.split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            var rest = s
            while (rest.length > 150) {
                val cut = rest.lastIndexOf(' ', 150).let { if (it < 50) 150 else it }
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }
}

/**
 * Things that happen later and on their own (a text message was delivered, ...). Shows up in the open chat panel, is
 * spoken with subtitles when the floating orb is running (or when "read replies aloud" is on), and falls back to a toast
 * so the news is never lost.
 */
object Announcer {
    private val handlers = CopyOnWriteArrayList<(String) -> Unit>()

    fun register(h: (String) -> Unit) { if (!handlers.contains(h)) handlers.add(h) }
    fun unregister(h: (String) -> Unit) { handlers.remove(h) }

    fun say(ctx: Context, text: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            handlers.forEach { try { it(text) } catch (e: Exception) { } }
            val orb = OrbService.instance
            if (orb != null) {
                orb.say(text)
            } else if (app.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getBoolean("speak", false)) {
                Speaker.speak(app, text, natural = false)
            } else if (handlers.isEmpty()) {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
