package com.neo.plus.iosluancher

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.ln

/**
 * “Find the best pic on my phone.”
 *
 * 1. Takes the newest photos from the camera / pictures folders (not screenshots, not photos other people sent).
 * 2. Scores each one on the phone itself (sharpness and exposure) and throws away blurry, dark and washed-out ones.
 * 3. Shows the best dozen to Gemini as ONE numbered contact sheet and asks which makes the best post.
 *    Without a Gemini key, or if the AI is busy, the best local score wins.
 */
object BestPhoto {
    class Pick(val found: MediaFinder.Found, val why: String)

    /** Why the last photo was chosen (shown to the user). */
    @Volatile var lastWhy: String = ""

    private const val MAX_LOOK = 60          // photos examined
    private const val SHEET_COUNT = 12       // photos the AI compares
    private const val DAYS = 120L

    private val askRx = Regex("(?i)\\b(best|nicest|prettiest|greatest|coolest|most beautiful|top)\\b")
    private val imageRx = Regex("(?i)\\b(photos?|pictures?|images?|pics?|selfies?)\\b")
    private val otherMedia = Regex("(?i)\\b(videos?|clips?|movies?|songs?|music|audios?|tracks?|screenshots?)\\b")

    /** “best pic”, “nicest photo on my phone”... but not “best song” or “best video”. */
    fun isAsk(spec: String) = askRx.containsMatchIn(spec) && imageRx.containsMatchIn(spec) && !otherMedia.containsMatchIn(spec)

    private class Cand(val uri: Uri, val name: String, val mime: String, val thumb: Bitmap, val score: Double)

    private fun candidates(ctx: Context): List<Cand> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val since = System.currentTimeMillis() / 1000 - DAYS * 86_400L
        val bad = listOf("screenshot", "screen_shot", "whatsapp", "telegram", "download", "thumbnail", ".trash", "sticker", "wallpaper")
        val good = ArrayList<Cand>()
        val rest = ArrayList<Cand>()
        try {
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DATE_ADDED} > ?", arrayOf(since.toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                var looked = 0
                while (c.moveToNext() && looked < MAX_LOOK) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val path = (c.getString(3) ?: "").lowercase()
                    if (mime == "image/png" || mime == "image/gif" || mime == "image/webp") continue   // screenshots, stickers, memes
                    if (bad.any { path.contains(it) || name.lowercase().contains(it) }) continue
                    looked++
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    val th = thumb(ctx, uri, 480) ?: continue
                    val cand = Cand(uri, name, mime, th, score(th))
                    if (path.contains("/dcim/")) good += cand else rest += cand
                }
            }
        } catch (e: Exception) { }
        // The camera folder is the best place to look; other folders only fill in when it has too few.
        return if (good.size >= 4) good else good + rest
    }

    /** Decoded, upright, at most [max] px on the long side. */
    private fun thumb(ctx: Context, uri: Uri, max: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) null else {
            var s = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= max) s *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            }
            val deg = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) { 0f }
            if (bmp != null && deg != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true) else bmp
        }
    } catch (e: Exception) { null }

    /** Sharpness (variance of the Laplacian) with a penalty for photos that are too dark or burnt out. */
    private fun score(b: Bitmap): Double {
        val w = 160
        val h = maxOf(1, b.height * w / maxOf(1, b.width))
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== b) s.recycle()
        val g = DoubleArray(w * h) { i -> 0.299 * Color.red(px[i]) + 0.587 * Color.green(px[i]) + 0.114 * Color.blue(px[i]) }
        val mean = g.average()
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            sum += lap; sum2 += lap * lap; n++
        }
        if (n == 0) return 0.0
        val m = sum / n
        val variance = sum2 / n - m * m
        var sc = ln(1.0 + variance)
        if (mean < 45) sc *= 0.4
        else if (mean > 225) sc *= 0.6
        return sc
    }

    /** One JPEG with the photos in a numbered grid (1 = top left). */
    private fun sheet(list: List<Cand>): ByteArray {
        val cols = 4
        val rows = (list.size + cols - 1) / cols
        val tile = 360
        val out = Bitmap.createBitmap(cols * tile, rows * tile, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.BLACK)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; isFakeBoldText = true }
        val chip = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        list.forEachIndexed { i, c ->
            val ox = (i % cols) * tile
            val oy = (i / cols) * tile
            val k = minOf((tile - 6f) / c.thumb.width, (tile - 6f) / c.thumb.height)
            val tw = c.thumb.width * k
            val th = c.thumb.height * k
            val l = ox + (tile - tw) / 2
            val t = oy + (tile - th) / 2
            cv.drawBitmap(c.thumb, null, android.graphics.RectF(l, t, l + tw, t + th), null)
            cv.drawRect(ox.toFloat(), oy.toFloat(), ox + 70f, oy + 60f, chip)
            cv.drawText((i + 1).toString(), ox + 10f, oy + 46f, label)
        }
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
        return bytes.toByteArray()
    }

    private val SYSTEM = """You choose the best photo for a social media post. You are shown ONE image: a grid of numbered photos (the number is in the top-left corner of each).
Return ONLY a JSON object, no code fences: {"pick":3,"why":"short reason"}
Choose the photo that will get the most likes: sharp, well lit, good composition, a clear and appealing subject, a flattering moment.
Never pick a photo of a document, ID, bank card, receipt, screen, or anything private or embarrassing. Do not name anyone from their face."""

    /** The best photo, or null when there is none to look at (no permission, or no recent camera photos). */
    fun pick(ctx: Context): Pick? {
        val all = candidates(ctx)
        if (all.isEmpty()) return null
        val top = all.sortedByDescending { it.score }.take(SHEET_COUNT)
        var chosen = top.first()
        var why = "the sharpest, best-lit recent photo"

        val key = ctx.applicationContext.getSharedPreferences("neo_assistant", Context.MODE_PRIVATE).getString("key", "") ?: ""
        if (top.size > 1 && KeyPool.parse(key).isNotEmpty()) {
            try {
                KeyPool.attach(ctx)
                val raw = Llm.chat(Provider.GEMINI, key, GeminiModels.FLASH, SYSTEM,
                    listOf(true to "There are ${top.size} photos. Which number is the best post? Write the JSON now."),
                    sheet(top), search = false)
                val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val s = cleaned.indexOf('{'); val e = cleaned.lastIndexOf('}')
                val j = JSONObject(if (s >= 0 && e > s) cleaned.substring(s, e + 1) else cleaned)
                val n = j.optInt("pick", 0)
                if (n in 1..top.size) {
                    chosen = top[n - 1]
                    why = j.optString("why").trim().ifBlank { "picked by the AI" }
                }
            } catch (e: Exception) { /* keep the local winner */ }
        }
        return Pick(MediaFinder.Found(chosen.uri, null, chosen.name, chosen.mime), why)
    }
}
