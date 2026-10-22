package com.neo.plus.iosluancher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Reads and handles any file the user gives the assistant (attached in chat or named from Downloads). */
object FileTools {
    /** The display name of a picked file (shown on its card before the copy has finished). */
    fun nameOf(ctx: Context, uri: Uri): String {
        var name = "file"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: "file"
            }
        } catch (e: Exception) { }
        return name
    }

    /** Copies a file the user picked into the assistant folder and returns it. */
    fun importUri(ctx: Context, uri: Uri): File? = try {
        var name = "file"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: "file"
        }
        val f = AssistantFiles.newFile(ctx, name)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        AssistantFiles.publish(ctx, f, AssistantFiles.mimeOf(f.name))
        f
    } catch (e: Exception) { null }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == sep && !q -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Loads a table from .xlsx, .csv or .tsv so batch jobs work on any of them. */
    fun loadTable(f: File): XBook {
        val ext = f.extension.lowercase()
        if (ext == "xlsx" || ext == "xlsm") return Xlsx.read(f)
        if (ext == "csv" || ext == "tsv" || ext == "txt") {
            val text = f.readText().removePrefix("\uFEFF")
            val lines = text.lines().filter { it.isNotBlank() }
            val sep = if (ext == "tsv" || (lines.firstOrNull()?.count { it == '\t' } ?: 0) > 0) '\t' else if ((lines.firstOrNull()?.count { it == ';' } ?: 0) > (lines.firstOrNull()?.count { it == ',' } ?: 0)) ';' else ','
            val sh = XSheet("Sheet1")
            lines.forEachIndexed { r, l -> splitCsv(l, sep).forEachIndexed { c, v -> sh.set(r + 1, c + 1, XCell(v.trim(), false)) } }
            return XBook().also { it.sheets += sh }
        }
        throw IllegalArgumentException("not a table")
    }

    fun readAny(f: File, maxChars: Int = 6000): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "xlsx", "xlsm", "csv", "tsv" -> try { Xlsx.toText(loadTable(f), "", 60) } catch (e: Exception) { "Couldn’t read ${f.name} as a table." }
            "txt", "md", "json", "log", "xml", "html", "htm" -> f.readText().take(maxChars)
            "docx" -> try {
                ZipFile(f).use { z ->
                    val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
                    xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "").take(maxChars)
                }
            } catch (e: Exception) { "Couldn’t read ${f.name} as a Word file." }
            "png", "jpg", "jpeg", "webp", "gif" -> "${f.name} is a picture (${f.length() / 1024} KB). I can upload it to an authorised website, but I can’t read its text without the screen-sharing feature."
            "pdf" -> "${f.name} is a PDF (${f.length() / 1024} KB). I can upload it to an authorised website, but I can’t read PDF text here."
            else -> "${f.name} is a ${ext.ifBlank { "unknown" }} file (${f.length() / 1024} KB). I can upload it to an authorised website but can’t read its content."
        }.take(maxChars)
    }

    fun read(ctx: Context, a: JSONObject): String {
        val name = a.optString("file")
        val f = AssistantFiles.find(ctx, name) ?: return "No file called “$name” in Downloads, Documents or the Neo Plus folder"
        return "Contents of ${f.name}:\n" + readAny(f)
    }
}
