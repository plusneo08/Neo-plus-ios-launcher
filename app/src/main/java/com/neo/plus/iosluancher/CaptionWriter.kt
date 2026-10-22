package com.neo.plus.iosluancher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * “Post this pic and add a viral and cool caption with the matched song.”
 *
 * Looks at the photo (or one frame of a video) with Gemini and writes a caption, hashtags and one matching song.
 * The song is a SUGGESTION written into the caption (“🎵 Title – Artist”): the apps' own music stickers are not
 * automated, so the audio itself is still added by hand in the app if you want it.
 */
object CaptionWriter {
    class Result(val caption: String, val hashtags: List<String>, val song: String) {
        /** The song title alone, for searching the app's music library. */
        fun songTitle(): String = song.substringBefore(" – ").substringBefore(" - ").trim()

        /** The text that is posted. */
        fun fullText(): String = buildString {
            append(caption)
            if (song.isNotBlank()) append("\n\n🎵 ").append(song)
            if (hashtags.isNotEmpty()) append("\n\n").append(hashtags.joinToString(" "))
        }
    }

    class Failure(val why: String) : Exception(why)

    private val SYSTEM = """You write social media captions. Look at the photo and return ONLY a JSON object, nothing else, no code fences:
{"caption":"...","hashtags":["#one","#two"],"song":"Title – Artist"}
Rules:
- caption: one punchy line, 120 characters or fewer, that fits what is in the photo and the requested vibe. Sound like a real person, not an ad. At most 2 emojis. Do not put quotation marks around it.
- hashtags: 3 to 6 relevant ones for Instagram, TikTok and Facebook. An empty list for WhatsApp.
- song: ONE real, well-known song that MATCHES THE CAPTION YOU WROTE: its mood, its meaning or a line of its title should echo the caption (e.g. a sunset caption about peace -> a calm sunset song; a "main character energy" caption -> a confident upbeat hit). Write it exactly as "Title – Artist" so it can be searched in the app's music library. Never invent a song or an artist. If you are not sure of a real one, use an empty string. Empty string too when no song is wanted.
- Do not name people from their faces. Nothing hateful, sexual or about real tragedies.
- If the user gave a topic or hint, use it."""

    /** Smaller JPEG for the model: long side 1024 px at most. A video gives one frame from its middle. */
    private fun photoBytes(ctx: Context, f: MediaFinder.Found): ByteArray? = try {
        val isVideo = f.mime.startsWith("video/") || MediaFinder.mimeFor(f.name).startsWith("video/")
        val bmp: Bitmap? = if (isVideo) {
            val r = MediaMetadataRetriever()
            try {
                if (f.file != null) r.setDataSource(f.file.absolutePath) else r.setDataSource(ctx, f.uri!!)
                val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
                r.getFrameAtTime(dur / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { try { r.release() } catch (e: Exception) { } }
        } else {
            fun open() = if (f.file != null) f.file.inputStream() else ctx.contentResolver.openInputStream(f.uri!!)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
        bmp?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    } catch (e: Exception) { null }

    /**
     * [platform] e.g. “Instagram”, [vibe] e.g. “viral, cool”, [hint] words the user gave about the photo (may be empty).
     * Throws [Failure] with a plain-words reason (no key, no quota, unreadable photo...).
     */
    fun write(ctx: Context, media: MediaFinder.Found?, platform: String, vibe: String, wantSong: Boolean, hint: String): Result {
        val key = ctx.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (KeyPool.parse(key).isEmpty()) throw Failure("add a Gemini key in the Assistant settings first")
        KeyPool.attach(ctx)

        val image = media?.let { photoBytes(ctx, it) }
        if (media != null && image == null) throw Failure("I couldn’t read “${media.name}” to look at it")

        val ask = buildString {
            append("Platform: ").append(platform).append(".\n")
            append("Vibe: ").append(vibe.ifBlank { "viral, cool" }).append(".\n")
            append(if (wantSong) "A matching song is wanted.\n" else "No song wanted: use an empty string for song.\n")
            if (hint.isNotBlank()) append("Hint from the user: ").append(hint.take(200)).append("\n")
            append("Write the JSON now.")
        }
        val raw = try {
            Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, SYSTEM, listOf(true to ask), image, search = false)
        } catch (e: Llm.LlmException) {
            throw Failure(e.message ?: "the AI is busy, try again in a minute")
        } catch (e: Exception) {
            throw Failure("couldn’t reach the AI (${e.javaClass.simpleName})")
        }
        return parse(raw, platform, wantSong)
    }

    private fun parse(raw: String, platform: String, wantSong: Boolean): Result {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = try {
            val s = cleaned.indexOf('{'); val e = cleaned.lastIndexOf('}')
            JSONObject(if (s >= 0 && e > s) cleaned.substring(s, e + 1) else cleaned)
        } catch (e: Exception) { null }

        val caption = (json?.optString("caption") ?: cleaned.lines().firstOrNull { it.isNotBlank() }.orEmpty())
            .trim().trim('"', '“', '”').take(220)
        if (caption.isBlank()) throw Failure("the AI gave no caption, try again")

        val noTags = platform.lowercase(Locale.ROOT).contains("whatsapp")
        val tags = ArrayList<String>()
        json?.optJSONArray("hashtags")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 6)) {
                val t = arr.optString(i).trim().replace(" ", "")
                if (t.isNotEmpty()) tags += if (t.startsWith("#")) t else "#$t"
            }
        }
        val song = if (wantSong) json?.optString("song").orEmpty().trim().trim('"', '“', '”').take(120) else ""
        return Result(caption, if (noTags) emptyList() else tags, song)
    }
}
