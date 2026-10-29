package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What to add on top of a story photo before it is shared. [songDone] / [textDone] tell what really worked. */
class StoryExtras(val app: String, val text: String, val song: String) {
    @Volatile var songDone = false
    @Volatile var textDone = false
}

/**
 * Presses the story editor's own buttons: Text (the caption) and Music (search the song, pick the first match).
 * Each step is best effort: apps move their buttons around, so every step checks the screen and gives up quietly,
 * and the caller reports exactly what was and was not added.
 */
object StoryEditor {
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }

    fun apply(ctx: Context, pkgs: Set<String>, x: StoryExtras) {
        if (x.text.isNotBlank()) x.textDone = try { addText(ctx, pkgs, x.text) } catch (e: Exception) { false }
        nap(700)
        if (x.song.isNotBlank()) x.songDone = try { addSong(ctx, pkgs, x.song) } catch (e: Exception) { false }
        nap(700)
    }

    private fun addText(ctx: Context, pkgs: Set<String>, text: String): Boolean {
        val root = UiDriver.rootOf(ctx, pkgs) ?: return false
        val btn = UiDriver.findAny(root, listOf("text", "aa", "add text", "~add text")) ?: return false
        if (!UiDriver.tap(btn)) return false
        val field = UiDriver.waitFor(ctx, pkgs, 5000L) { r -> UiDriver.findEditable(r, "", skipSearch = true) } ?: return false
        if (!UiDriver.setText(ctx, field, text, replace = true)) return false
        nap(500)
        val done = UiDriver.waitFor(ctx, pkgs, 4000L) { r -> UiDriver.findAny(r, listOf("done", "next")) } ?: return false
        UiDriver.tap(done)
        nap(900)
        return true
    }

    private fun addSong(ctx: Context, pkgs: Set<String>, song: String): Boolean {
        val title = song.substringBefore(" – ").substringBefore(" - ").trim().lowercase(Locale.ROOT)
        if (title.isBlank()) return false
        val query = song.replace(" – ", " ").replace(" - ", " ").trim()

        // Facebook shows Music in the editor bar; Instagram keeps it inside the Stickers tray.
        var music = UiDriver.rootOf(ctx, pkgs)?.let { UiDriver.findAny(it, listOf("music")) }
        if (music == null) {
            val stickers = UiDriver.rootOf(ctx, pkgs)?.let { UiDriver.findAny(it, listOf("stickers", "sticker tray", "~stickers")) } ?: return false
            if (!UiDriver.tap(stickers)) return false
            music = UiDriver.waitFor(ctx, pkgs, 5000L) { r -> UiDriver.findAny(r, listOf("music")) }
        }
        if (music == null || !UiDriver.tap(music)) return false

        val search = UiDriver.waitFor(ctx, pkgs, 6000L) { r -> UiDriver.findEditable(r) } ?: return false
        if (!UiDriver.setText(ctx, search, query, replace = true)) return false
        nap(2200)

        val hit = UiDriver.waitFor(ctx, pkgs, 9000L) { r ->
            UiDriver.all(r).firstOrNull { n ->
                n.isVisibleToUser && !n.isEditable && UiDriver.names(n).any { it.contains(title) }
            }
        } ?: return false
        if (!UiDriver.tap(hit)) return false
        nap(1800)

        // After choosing, the app asks which part of the song to use: accept it.
        val done = UiDriver.waitFor(ctx, pkgs, 6000L) { r -> UiDriver.findAny(r, listOf("done", "next")) } ?: return false
        UiDriver.tap(done)
        nap(1200)
        // The tray / editor is back when the Share button of the story is visible again, which runFlow checks next.
        return true
    }
}
