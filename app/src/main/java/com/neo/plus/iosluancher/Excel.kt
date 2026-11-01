package com.neo.plus.iosluancher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/* ------------------------------------------------------------------ */
/*  Where the assistant keeps and finds files                          */
/* ------------------------------------------------------------------ */

object AssistantFiles {
    /** Folder the assistant creates and downloads files into (always writable, no permission needed). */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "NeoPlus").also { it.mkdirs() }

    private fun clean(name: String): String {
        val n = name.trim().substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_")
        return n.ifBlank { "file" }
    }

    fun newFile(ctx: Context, name: String): File = File(dir(ctx), clean(name))

    /** Finds a file by absolute path or by name in the assistant folder, Downloads, Documents or the phone's root. */
    fun find(ctx: Context, nameOrPath: String): File? {
        val raw = nameOrPath.trim()
        if (raw.isEmpty()) return null
        val direct = File(raw)
        if (direct.isAbsolute && direct.isFile) return direct
        val name = clean(raw)
        val roots = listOfNotNull(
            dir(ctx),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory()
        )
        for (r in roots) {
            val f = File(r, name)
            if (f.isFile) return f
            File(r, "NeoPlus").let { if (File(it, name).isFile) return File(it, name) }
        }
        return null
    }

    /** Best-effort copy into the public Downloads/NeoPlus folder so the user can open it from any file manager. */
    fun publish(ctx: Context, f: File, mime: String = "application/octet-stream"): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NeoPlus")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                "Downloads/NeoPlus/${f.name}"
            } else null
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NeoPlus").also { it.mkdirs() }
            f.copyTo(File(d, f.name), overwrite = true)
            "Downloads/NeoPlus/${f.name}"
        }
    } catch (e: Exception) { null }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }
}

/* ------------------------------------------------------------------ */
/*  A small, dependency-free .xlsx reader / writer                      */
/* ------------------------------------------------------------------ */

class XCell(val text: String, val number: Boolean, val formula: String? = null)

class XSheet(var name: String) {
    val rows = TreeMap<Int, TreeMap<Int, XCell>>()
    fun set(r: Int, c: Int, cell: XCell) { rows.getOrPut(r) { TreeMap() }[c] = cell }
    fun get(r: Int, c: Int): XCell? = rows[r]?.get(c)
    fun lastRow(): Int = rows.lastEntry()?.key ?: 0
    fun lastCol(): Int = rows.values.maxOfOrNull { it.lastEntry()?.key ?: 0 } ?: 0
}

class XBook { val sheets = ArrayList<XSheet>() }

object Xlsx {
    // ---- cell references ------------------------------------------------
    fun colName(c: Int): String { var n = c; val sb = StringBuilder(); while (n > 0) { n--; sb.insert(0, ('A' + n % 26)); n /= 26 }; return sb.toString() }
    fun colIndex(letters: String): Int = letters.uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) }
    /** "B3" -> (3, 2) as (row, col), both 1-based; null if it is not a cell reference. */
    fun parseRef(ref: String): Pair<Int, Int>? {
        val m = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})$").find(ref.trim()) ?: return null
        return m.groupValues[2].toInt() to colIndex(m.groupValues[1])
    }

    fun looksNumeric(s: String) = s.isNotBlank() && s.trim().matches(Regex("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")) &&
        !(s.trim().length > 1 && s.trim().startsWith("0") && !s.trim().startsWith("0."))

    /** Builds a cell from loose input: numbers stay numbers, text starting with "=" becomes a formula. */
    fun cellOf(v: Any?): XCell? = when {
        v == null || v == JSONObject.NULL -> null
        v is Number -> XCell(if (v.toDouble() % 1.0 == 0.0 && Math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toString(), true)
        v is Boolean -> XCell(if (v) "TRUE" else "FALSE", false)
        else -> {
            val s = v.toString()
            when {
                s.startsWith("=") && s.length > 1 -> XCell("", false, s.substring(1))
                looksNumeric(s) -> XCell(s.trim(), true)
                s.isEmpty() -> null
                else -> XCell(s, false)
            }
        }
    }

    // ---- reading ----------------------------------------------------------
    fun read(file: File): XBook {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && (e.name.startsWith("xl/") || e.name == "[Content_Types].xml")) parts[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = ArrayList<String>()
        parts["xl/sharedStrings.xml"]?.let { shared += parseShared(it) }
        val rels = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { b ->
            val p = Xml.newPullParser(); p.setInput(b.inputStream(), "UTF-8")
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship")
                    rels[p.getAttributeValue(null, "Id") ?: ""] = (p.getAttributeValue(null, "Target") ?: "")
            }
        }
        val book = XBook()
        val wb = parts["xl/workbook.xml"] ?: throw IllegalArgumentException("Not an .xlsx workbook")
        val p = Xml.newPullParser(); p.setInput(wb.inputStream(), "UTF-8")
        val found = ArrayList<Pair<String, String>>() // name -> part path
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: "Sheet"
                var rid = ""
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == "id") rid = p.getAttributeValue(i)
                val target = rels[rid] ?: continue
                found += name to (if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target)
            }
        }
        for ((name, path) in found) {
            val data = parts[path] ?: continue
            book.sheets += parseSheet(name, data, shared)
        }
        if (book.sheets.isEmpty()) book.sheets += XSheet("Sheet1")
        return book
    }

    private fun parseShared(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = Xml.newPullParser(); p.setInput(b.inputStream(), "UTF-8")
        var inSi = false; var inT = false; var cur = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> { inSi = true; cur = StringBuilder() }; "t" -> inT = inSi }
                XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { inSi = false; out += cur.toString() } }
            }
        }
        return out
    }

    private fun parseSheet(name: String, b: ByteArray, shared: List<String>): XSheet {
        val sh = XSheet(name)
        val p = Xml.newPullParser(); p.setInput(b.inputStream(), "UTF-8")
        var ref = ""; var type = ""; var f: String? = null; var v = StringBuilder(); var inline = StringBuilder()
        var inV = false; var inF = false; var inT = false; var inCell = false; var inIs = false
        var autoRow = 0; var autoCol = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { autoRow = (p.getAttributeValue(null, "r") ?: "${autoRow + 1}").toIntOrNull() ?: (autoRow + 1); autoCol = 0 }
                    "c" -> {
                        inCell = true; autoCol++
                        ref = p.getAttributeValue(null, "r") ?: (colName(autoCol) + autoRow)
                        type = p.getAttributeValue(null, "t") ?: ""
                        f = null; v = StringBuilder(); inline = StringBuilder()
                    }
                    "v" -> inV = inCell
                    "f" -> { inF = inCell; if (f == null) f = "" }
                    "is" -> inIs = inCell
                    "t" -> inT = inIs
                }
                XmlPullParser.TEXT -> {
                    if (inV) v.append(p.text) else if (inF) f = (f ?: "") + p.text else if (inT) inline.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "f" -> inF = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        inCell = false
                        val rc = parseRef(ref)
                        if (rc != null) {
                            autoCol = rc.second
                            val raw = v.toString()
                            val cell = when (type) {
                                "s" -> XCell(shared.getOrElse(raw.toIntOrNull() ?: -1) { "" }, false, f?.takeIf { it.isNotBlank() })
                                "inlineStr" -> XCell(inline.toString(), false, f?.takeIf { it.isNotBlank() })
                                "b" -> XCell(if (raw == "1") "TRUE" else "FALSE", false, f?.takeIf { it.isNotBlank() })
                                "str", "e" -> XCell(raw, false, f?.takeIf { it.isNotBlank() })
                                else -> XCell(raw, true, f?.takeIf { it.isNotBlank() })
                            }
                            if (cell.text.isNotEmpty() || cell.formula != null) sh.set(rc.first, rc.second, cell)
                        }
                    }
                }
            }
        }
        return sh
    }

    // ---- writing ------------------------------------------------------------
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code < 32 && ch != '\n' && ch != '\t' && ch != '\r' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun safeSheetName(n: String, used: Set<String>): String {
        var s = n.replace(Regex("[\\\\/?*\\[\\]:]"), "_").trim().take(31).ifBlank { "Sheet" }
        var i = 2; val base = s
        while (s in used) { s = base.take(28) + " " + i++ }
        return s
    }

    fun write(book: XBook, file: File, boldHeader: Boolean = true) {
        if (book.sheets.isEmpty()) book.sheets += XSheet("Sheet1")
        val names = ArrayList<String>()
        book.sheets.forEach { it.name = safeSheetName(it.name, names.toSet()); names += it.name }
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            fun put(path: String, text: String) { z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            val ct = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            book.sheets.indices.forEach { ct.append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            ct.append("</Types>")
            put("[Content_Types].xml", ct.toString())
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val wb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            val rel = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            book.sheets.forEachIndexed { i, s ->
                wb.append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                rel.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            wb.append("</sheets></workbook>")
            rel.append("""<Relationship Id="rId${book.sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/workbook.xml", wb.toString())
            put("xl/_rels/workbook.xml.rels", rel.toString())
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>""")
            book.sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, boldHeader)) }
        }
    }

    private fun sheetXml(s: XSheet, boldHeader: Boolean): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        val cols = s.lastCol()
        if (cols > 0) {
            sb.append("<cols>")
            for (c in 1..cols) {
                val longest = s.rows.values.maxOfOrNull { (it[c]?.let { x -> if (x.formula != null && x.text.isEmpty()) 10 else x.text.length } ?: 0) } ?: 0
                sb.append("""<col min="$c" max="$c" width="${(longest + 3).coerceIn(8, 60)}" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        val firstRow = s.rows.firstEntry()?.key
        for ((r, cells) in s.rows) {
            sb.append("""<row r="$r">""")
            for ((c, cell) in cells) {
                val ref = colName(c) + r
                val style = if (boldHeader && r == firstRow && !cell.number && cell.formula == null) """ s="1"""" else ""
                when {
                    cell.formula != null -> sb.append("""<c r="$ref"$style><f>${esc(cell.formula)}</f>${if (cell.text.isNotEmpty()) "<v>${esc(cell.text)}</v>" else ""}</c>""")
                    cell.number -> sb.append("""<c r="$ref"$style><v>${esc(cell.text)}</v></c>""")
                    else -> sb.append("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(cell.text)}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // ---- friendly operations used by the assistant ----------------------------
    fun fillRows(sheet: XSheet, rows: JSONArray, startRow: Int = 1) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i)
            if (row != null) {
                for (j in 0 until row.length()) cellOf(row.opt(j))?.let { sheet.set(startRow + i, j + 1, it) }
            } else {
                // A row given as an object {"Name":"A","Qty":2} is written in key order
                val o = rows.optJSONObject(i) ?: continue
                var j = 1
                val it2 = o.keys()
                while (it2.hasNext()) { cellOf(o.opt(it2.next()))?.let { c -> sheet.set(startRow + i, j, c) }; j++ }
            }
        }
    }

    fun toText(book: XBook, sheetName: String, maxRows: Int): String {
        val sheets = if (sheetName.isBlank()) book.sheets else book.sheets.filter { it.name.equals(sheetName, true) }
        if (sheets.isEmpty()) return "No sheet called “$sheetName”. Sheets: " + book.sheets.joinToString(", ") { it.name }
        val sb = StringBuilder()
        for (s in sheets) {
            sb.append("Sheet “${s.name}” (${s.lastRow()} rows, ${s.lastCol()} columns)\n")
            var shown = 0
            for ((r, cells) in s.rows) {
                if (shown++ >= maxRows) { sb.append("… ${s.lastRow() - r + 1} more rows\n"); break }
                sb.append("$r: ")
                sb.append((1..s.lastCol()).joinToString(" | ") { c ->
                    val x = cells[c]; if (x == null) "" else if (x.formula != null) "=" + x.formula + (if (x.text.isNotEmpty()) " (" + x.text + ")" else "") else x.text
                })
                sb.append('\n')
            }
        }
        return sb.toString().take(6000)
    }
}

/* ------------------------------------------------------------------ */
/*  Assistant actions: excel_create / excel_edit / excel_read           */
/* ------------------------------------------------------------------ */

object ExcelActions {
    private fun fileName(a: JSONObject): String {
        var n = a.optString("file").ifBlank { a.optString("name") }.ifBlank { "Neo Plus " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date()) }
        if (!n.lowercase().endsWith(".xlsx")) n = n.substringBeforeLast('.', n) + ".xlsx"
        return n
    }

    fun create(ctx: Context, a: JSONObject): String {
        val book = XBook()
        val sheets = a.optJSONArray("sheets")
        if (sheets != null && sheets.length() > 0) {
            for (i in 0 until sheets.length()) {
                val so = sheets.optJSONObject(i) ?: continue
                val sh = XSheet(so.optString("name", "Sheet${i + 1}"))
                so.optJSONArray("rows")?.let { Xlsx.fillRows(sh, it) }
                book.sheets += sh
            }
        } else {
            val sh = XSheet(a.optString("sheet", "Sheet1"))
            a.optJSONArray("rows")?.let { Xlsx.fillRows(sh, it) }
            book.sheets += sh
        }
        val f = AssistantFiles.newFile(ctx, fileName(a))
        Xlsx.write(book, f, a.optBoolean("bold_header", true))
        val pub = AssistantFiles.publish(ctx, f, AssistantFiles.mimeOf(f.name))
        val rows = book.sheets.sumOf { it.lastRow() }
        return "Created ${f.name} with ${book.sheets.size} sheet(s) and $rows row(s). Saved in ${pub ?: f.absolutePath}. (file: ${f.name})"
    }

    fun read(ctx: Context, a: JSONObject): String {
        val name = a.optString("file")
        val f = AssistantFiles.find(ctx, name) ?: return "No file called “$name” in Downloads, Documents or the Neo Plus folder"
        return try {
            "Contents of ${f.name}:\n" + Xlsx.toText(Xlsx.read(f), a.optString("sheet"), a.optInt("max_rows", 40).coerceIn(1, 200))
        } catch (e: Exception) { "Couldn’t read ${f.name} (${e.javaClass.simpleName}). Only .xlsx files can be read." }
    }

    fun edit(ctx: Context, a: JSONObject): String {
        val name = a.optString("file")
        val src = AssistantFiles.find(ctx, name) ?: return "No file called “$name” in Downloads, Documents or the Neo Plus folder"
        val book = try { Xlsx.read(src) } catch (e: Exception) { return "Couldn’t open ${src.name} (${e.javaClass.simpleName}). Only .xlsx files can be edited." }
        val edits = a.optJSONArray("edits") ?: return "No edits were given"
        var done = 0
        val problems = ArrayList<String>()
        fun sheetFor(o: JSONObject, create: Boolean = false): XSheet? {
            val n = o.optString("sheet")
            if (n.isBlank()) return book.sheets.firstOrNull() ?: XSheet("Sheet1").also { book.sheets += it }
            return book.sheets.firstOrNull { it.name.equals(n, true) } ?: if (create) XSheet(n).also { book.sheets += it } else null
        }
        for (i in 0 until edits.length()) {
            val e = edits.optJSONObject(i) ?: continue
            try {
                when {
                    e.has("add_sheet") -> { val n = e.optString("add_sheet"); if (book.sheets.none { it.name.equals(n, true) }) book.sheets += XSheet(n); done++ }
                    e.has("rename_sheet") -> { sheetFor(e)?.name = e.optString("rename_sheet"); done++ }
                    e.has("delete_sheet") -> { book.sheets.removeAll { it.name.equals(e.optString("delete_sheet"), true) }; if (book.sheets.isEmpty()) book.sheets += XSheet("Sheet1"); done++ }
                    e.has("cell") -> {
                        val sh = sheetFor(e, true)!!
                        val rc = Xlsx.parseRef(e.optString("cell")) ?: run { problems += "bad cell ${e.optString("cell")}"; null }
                        if (rc != null) {
                            val v: Any? = if (e.has("formula")) "=" + e.optString("formula").removePrefix("=") else e.opt("value")
                            val cell = Xlsx.cellOf(v)
                            if (cell == null) sh.rows[rc.first]?.remove(rc.second) else sh.set(rc.first, rc.second, cell)
                            done++
                        }
                    }
                    e.has("append") -> {
                        val sh = sheetFor(e, true)!!
                        val row = e.optJSONArray("append")
                        val r = sh.lastRow() + 1
                        if (row != null) { for (j in 0 until row.length()) Xlsx.cellOf(row.opt(j))?.let { sh.set(r, j + 1, it) }; done++ }
                    }
                    e.has("rows") -> { // write a block of rows starting at a cell (default A1)
                        val sh = sheetFor(e, true)!!
                        val start = Xlsx.parseRef(e.optString("start", "A1")) ?: (1 to 1)
                        val rows = e.optJSONArray("rows") ?: JSONArray()
                        for (r in 0 until rows.length()) {
                            val row = rows.optJSONArray(r) ?: continue
                            for (c in 0 until row.length()) Xlsx.cellOf(row.opt(c))?.let { sh.set(start.first + r, start.second + c, it) }
                        }
                        done++
                    }
                    e.has("delete_row") -> {
                        val sh = sheetFor(e)!!
                        val del = e.optInt("delete_row")
                        val moved = TreeMap<Int, TreeMap<Int, XCell>>()
                        for ((r, cells) in sh.rows) if (r != del) moved[if (r > del) r - 1 else r] = cells
                        sh.rows.clear(); sh.rows.putAll(moved); done++
                    }
                    e.has("clear") -> { sheetFor(e)?.rows?.clear(); done++ }
                    else -> problems += "unknown edit #${i + 1}"
                }
            } catch (ex: Exception) { problems += "edit #${i + 1} failed" }
        }
        // The rewrite keeps values and formulas but not charts, images or cell colours, so the original is backed up first.
        val saveAs = a.optString("save_as")
        val written = when {
            saveAs.isNotBlank() -> AssistantFiles.newFile(ctx, if (saveAs.lowercase().endsWith(".xlsx")) saveAs else "$saveAs.xlsx")
            src.canWrite() -> src
            else -> AssistantFiles.newFile(ctx, src.name)
        }
        if (written.absolutePath == src.absolutePath) {
            runCatching { src.copyTo(File(src.parentFile, src.nameWithoutExtension + " (backup).xlsx"), overwrite = true) }
        }
        Xlsx.write(book, written)
        val pub = AssistantFiles.publish(ctx, written, AssistantFiles.mimeOf(written.name))
        return "Edited ${written.name}: $done change(s) applied" + (if (problems.isEmpty()) "" else ", skipped: " + problems.joinToString()) +
            ". A backup of the original was kept. Saved in ${pub ?: written.absolutePath}. (file: ${written.name})"
    }
}
