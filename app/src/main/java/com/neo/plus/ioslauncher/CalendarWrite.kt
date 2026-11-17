package com.neo.plus.ioslauncher

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar write: add, update, delete (reading already exists as the "calendar" action).
 *
 *   calendar_edit {"do":"add","title":"Dentist","date":"2026-11-18","time":"15:00","minutes":60,"remind_minutes":15}
 *   calendar_edit {"do":"update","title":"Dentist","start":"2026-11-19T10:00"}      (or "id": event id; "new_title" renames)
 *   calendar_edit {"do":"delete","title":"Dentist"}
 *
 * Rules: no guests are ever added (nobody gets an invitation). Delete always waits for your OK. Add and update wait for your OK
 * too when untrusted text was just read (a message, a web page), so such text cannot plant events by itself.
 * After a write the event is read back to check that it really is in the calendar.
 * Needs WRITE_CALENDAR and READ_CALENDAR granted.
 */
object CalendarWrite {
    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    private val stamps = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss")

    private fun parse(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        for (f in stamps) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                sdf.isLenient = false
                val d = sdf.parse(t)
                if (d != null) return d.time
            } catch (e: Exception) { }
        }
        return null
    }

    private fun parseDayUtc(s: String): Long? = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.isLenient = false
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s.trim())?.time
    } catch (e: Exception) { null }

    private fun startMs(a: JSONObject, allDay: Boolean): Long? {
        if (allDay) return parseDayUtc(a.optString("date").ifBlank { a.optString("start").take(10) })
        val s = a.optString("start")
        if (s.isNotBlank()) return parse(s)
        val d = a.optString("date"); val t = a.optString("time")
        return if (d.isNotBlank() && t.isNotBlank()) parse("$d $t") else null
    }

    private fun whenText(ms: Long, allDay: Boolean = false): String {
        val sdf = SimpleDateFormat(if (allDay) "EEE d MMM" else "EEE d MMM 'at' HH:mm", Locale.getDefault())
        if (allDay) sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    fun handle(c: Context, a: JSONObject): String {
        if (!granted(c, Manifest.permission.WRITE_CALENDAR) || !granted(c, Manifest.permission.READ_CALENDAR))
            return "Allow Calendar access (read and write) for Neo Plus in Settings, then ask again"
        return try {
            when (a.optString("do").lowercase()) {
                "add" -> add(c, a)
                "update" -> update(c, a)
                "delete" -> delete(c, a)
                else -> "Use do: add, update or delete"
            }
        } catch (e: SecurityException) {
            "Allow Calendar access (read and write) for Neo Plus in Settings, then ask again"
        }
    }

    private fun writableCalendar(c: Context): Long? {
        val cur = c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf("500"), null
        ) ?: return null
        cur.use {
            var first: Long? = null
            while (it.moveToNext()) {
                val id = it.getLong(0)
                if (first == null) first = id
                if (it.getInt(1) == 1) return id
            }
            return first
        }
    }

    private fun exists(c: Context, id: Long): Boolean = try {
        c.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) { false }

    private class Hit(val id: Long, val title: String, val begin: Long)

    private fun upcoming(c: Context, title: String): List<Hit> {
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 24L * 3600 * 1000)
        ContentUris.appendId(b, now + 60L * 24 * 3600 * 1000)
        val out = ArrayList<Hit>()
        c.contentResolver.query(b.build(),
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use {
            while (it.moveToNext()) {
                val t = it.getString(1) ?: ""
                if (t.contains(title, ignoreCase = true)) out.add(Hit(it.getLong(0), t, it.getLong(2)))
            }
        }
        return out
    }

    /** Which event: "id" if given, else the one upcoming event whose title contains "title". Returns the id or a message to say. */
    private fun target(c: Context, a: JSONObject): Pair<Long?, String> {
        val id = a.optLong("id", 0L)
        if (id > 0) return if (exists(c, id)) Pair(id, "") else Pair(null, "Couldn’t find an event with that id")
        val title = a.optString("title").trim()
        if (title.isEmpty()) return Pair(null, "Which event? Give its title.")
        val hits = upcoming(c, title)
        return when {
            hits.isEmpty() -> Pair(null, "Couldn’t find an upcoming event called “$title”")
            hits.map { it.id }.distinct().size == 1 -> Pair(hits[0].id, "")
            else -> Pair(null, "Which one? " + hits.take(5).joinToString("; ") { "id ${it.id}: ${it.title}, ${whenText(it.begin)}" })
        }
    }

    private fun add(c: Context, a: JSONObject): String {
        val title = a.optString("title").trim()
        if (title.isEmpty()) return "What should I call the event?"
        val allDay = a.optBoolean("all_day", false)
        val start = startMs(a, allDay) ?: return "Couldn’t add the event: I need the date and time (for example date 2026-11-18, time 15:00)"
        val end = when {
            allDay -> start + 86_400_000L
            a.optString("end").isNotBlank() -> parse(a.optString("end")) ?: (start + 3_600_000L)
            else -> start + a.optInt("minutes", 60).coerceIn(5, 1440) * 60_000L
        }
        if (Guard.tainted() && !Guard.ok(a)) return Guard.hold("calendar_edit", a, "add “$title” on ${whenText(start, allDay)} to your calendar")
        val cal = writableCalendar(c) ?: return "Couldn’t find a calendar I can write to"
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            a.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.optString("notes").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "Couldn’t add the event"
        val id = uri.lastPathSegment?.toLongOrNull() ?: return "Couldn’t add the event"
        val remind = a.optInt("remind_minutes", -1)
        if (remind >= 0) {
            try {
                c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, remind)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            } catch (e: Exception) { }
        }
        val seen = exists(c, id)
        EventLog.add(c, "calendar", "added “$title”", seen)
        return if (seen) "Added “$title” on ${whenText(start, allDay)} (checked: it is in your calendar, id $id)"
        else "Couldn’t confirm that “$title” was saved. Check your calendar."
    }

    private fun update(c: Context, a: JSONObject): String {
        val (id, msg) = target(c, a)
        if (id == null) return msg
        val newTitle = a.optString("new_title").trim()
        if (Guard.tainted() && !Guard.ok(a)) return Guard.hold("calendar_edit", JSONObject(a.toString()).put("id", id), "change that event in your calendar")
        val v = ContentValues()
        if (newTitle.isNotEmpty()) v.put(CalendarContract.Events.TITLE, newTitle)
        a.optString("location").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it) }
        a.optString("notes").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.DESCRIPTION, it) }
        val newStart = startMs(a, false)
        if (newStart != null) {
            var span = 3_600_000L
            c.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(1)) span = (it.getLong(1) - it.getLong(0)).coerceAtLeast(300_000L)
            }
            v.put(CalendarContract.Events.DTSTART, newStart)
            v.put(CalendarContract.Events.DTEND, if (a.optString("end").isNotBlank()) (parse(a.optString("end")) ?: (newStart + span)) else newStart + span)
        }
        if (v.size() == 0) return "Nothing to change: give a new start, new_title, location or notes"
        val n = c.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null)
        EventLog.add(c, "calendar", "updated event $id", n > 0)
        return if (n > 0) "Updated the event" + (if (newStart != null) " to ${whenText(newStart)}" else "") else "Couldn’t update the event"
    }

    private fun delete(c: Context, a: JSONObject): String {
        val (id, msg) = target(c, a)
        if (id == null) return msg
        val name = a.optString("title").ifBlank { "that event" }
        if (!Guard.ok(a)) return Guard.hold("calendar_edit", JSONObject(a.toString()).put("id", id), "delete “$name” from your calendar")
        c.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        val gone = !exists(c, id)
        EventLog.add(c, "calendar", "deleted “$name”", gone)
        return if (gone) "Deleted “$name” (checked: it is gone from your calendar)" else "Couldn’t confirm the delete. Check your calendar."
    }
}
