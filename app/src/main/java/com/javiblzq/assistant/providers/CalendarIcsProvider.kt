package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.CalendarEventItem
import com.javiblzq.assistant.net.HttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CalendarIcsParser {

    fun parseIcs(
        icsContent: String,
        timezoneId: String = "Europe/Madrid",
        nowMs: Long = System.currentTimeMillis()
    ): List<CalendarEventItem> = parseIcsInternal(
        icsContent = icsContent,
        timezoneId = timezoneId,
        nowMs = nowMs,
        windowDays = 3
    )

    fun parseIcsEvents(
        icsContent: String,
        timezoneId: String = "Europe/Madrid",
        nowMs: Long = System.currentTimeMillis()
    ): List<CalendarEventItem> = parseIcsInternal(
        icsContent = icsContent,
        timezoneId = timezoneId,
        nowMs = nowMs,
        windowDays = null
    )

    private fun parseIcsInternal(
        icsContent: String,
        timezoneId: String,
        nowMs: Long,
        windowDays: Int?
    ): List<CalendarEventItem> {
        val unfolded = icsContent.replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")
        val lines = unfolded.split(Regex("\\r?\\n"))

        val tz = TimeZone.getTimeZone(timezoneId)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }

        val cal = Calendar.getInstance(tz).apply {
            time = Date(nowMs)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayIso = dateFmt.format(cal.time)
        val windowStartMs = cal.timeInMillis
        val windowEndMs = if (windowDays != null) {
            cal.add(Calendar.DAY_OF_YEAR, windowDays)
            cal.timeInMillis
        } else {
            Long.MAX_VALUE
        }

        val out = mutableListOf<CalendarEventItem>()
        var inEvent = false
        var summary = ""
        var location = ""
        var uid = ""
        var dtStartRaw = ""
        var dtEndRaw = ""
        var dtStartTz = tz
        var dtEndTz = tz
        var rrule = ""

        fun flushEvent() {
            if (summary.isBlank() || dtStartRaw.isBlank()) return
            val startMs = parseIcsDateTime(dtStartRaw, dtStartTz) ?: return
            val endMs = if (dtEndRaw.isNotBlank()) parseIcsDateTime(dtEndRaw, dtEndTz) else null
            val durationMs = if (endMs != null && endMs > startMs) endMs - startMs else 3600_000L

            val occurrences = if (windowDays != null) {
                expandOccurrences(startMs, rrule, windowStartMs, windowEndMs, tz)
            } else {
                listOf(startMs)
            }
            for (occMs in occurrences) {
                val dIso = dateFmt.format(Date(occMs))
                val isAllDay = dtStartRaw.length == 8
                val sTime = if (isAllDay) "TODO EL DIA" else timeFmt.format(Date(occMs))
                val eTime = if (isAllDay || endMs == null) "" else timeFmt.format(Date(occMs + durationMs))
                out.add(
                    CalendarEventItem(
                        id = "${uid.ifBlank { summary }}_$occMs",
                        title = unescapeIcs(summary),
                        dateIso = dIso,
                        startTime = sTime,
                        endTime = eTime,
                        location = unescapeIcs(location),
                        isToday = dIso == todayIso,
                        startEpochMs = occMs
                    )
                )
            }
        }

        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true
                    summary = ""; location = ""; uid = ""; dtStartRaw = ""; dtEndRaw = ""; rrule = ""
                    dtStartTz = tz; dtEndTz = tz
                }
                line == "END:VEVENT" -> {
                    if (inEvent) flushEvent()
                    inEvent = false
                }
                inEvent -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val propPart = line.substring(0, colon)
                    val value = line.substring(colon + 1).trim()
                    val propName = propPart.substringBefore(';').uppercase(Locale.US)
                    when (propName) {
                        "SUMMARY" -> summary = value
                        "LOCATION" -> location = value
                        "UID" -> uid = value
                        "DTSTART" -> {
                            dtStartRaw = value
                            dtStartTz = extractPropertyTimeZone(propPart, tz)
                        }
                        "DTEND" -> {
                            dtEndRaw = value
                            dtEndTz = extractPropertyTimeZone(propPart, tz)
                        }
                        "RRULE" -> rrule = value
                    }
                }
            }
        }
        return out.sortedBy { it.startEpochMs }
    }

    private fun extractPropertyTimeZone(propPart: String, fallback: TimeZone): TimeZone {
        val params = propPart.split(';').drop(1)
        for (param in params) {
            val eq = param.indexOf('=')
            if (eq <= 0) continue
            val key = param.substring(0, eq).trim().uppercase(Locale.US)
            if (key == "TZID") {
                val tzId = param.substring(eq + 1).trim().trim('"')
                if (tzId.isNotEmpty() && tzId in TimeZone.getAvailableIDs()) {
                    return TimeZone.getTimeZone(tzId)
                }
            }
        }
        return fallback
    }

    private fun expandOccurrences(
        startMs: Long,
        rrule: String,
        windowStartMs: Long,
        windowEndMs: Long,
        tz: TimeZone
    ): List<Long> {
        if (rrule.isBlank()) {
            return if (startMs in windowStartMs until windowEndMs) listOf(startMs) else emptyList()
        }
        val upper = rrule.uppercase(Locale.US)
        val stepField = when {
            upper.contains("FREQ=DAILY") -> Calendar.DAY_OF_YEAR
            upper.contains("FREQ=WEEKLY") -> Calendar.WEEK_OF_YEAR
            else -> return if (startMs in windowStartMs until windowEndMs) listOf(startMs) else emptyList()
        }
        val cal = Calendar.getInstance(tz).apply { timeInMillis = startMs }
        val results = mutableListOf<Long>()
        var guard = 0
        while (cal.timeInMillis < windowEndMs && guard++ < 2000) {
            if (cal.timeInMillis >= windowStartMs) {
                results.add(cal.timeInMillis)
            }
            cal.add(stepField, 1)
        }
        return results
    }

    private fun parseIcsDateTime(raw: String, localTz: TimeZone): Long? {
        val clean = raw.trim()
        return try {
            when {
                clean.endsWith("Z") && clean.length >= 16 -> {
                    SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(clean)?.time
                }
                clean.contains("T") && clean.length >= 15 -> {
                    SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                        .apply { timeZone = localTz }
                        .parse(clean.substring(0, 15))?.time
                }
                clean.length == 8 -> {
                    SimpleDateFormat("yyyyMMdd", Locale.US)
                        .apply { timeZone = localTz }
                        .parse(clean)?.time
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun unescapeIcs(s: String): String =
        s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}

class CalendarIcsProvider {
    fun fetchAll(config: AssistantConfig): List<CalendarEventItem> {
        val tz = TimeZone.getTimeZone(config.timezone)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val todayIso = dateFmt.format(Date())
        val all = mutableListOf<CalendarEventItem>()

        for (url in config.icalUrls) {
            if (url.isBlank()) continue
            val normalizedUrl = url.replace("webcal://", "https://")
            val resp = HttpClient.get(normalizedUrl)
            if (resp.isSuccess && resp.body.contains("BEGIN:VCALENDAR")) {
                all.addAll(CalendarIcsParser.parseIcs(resp.body, config.timezone))
            }
        }

        val parseManualFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
        for (m in config.manualEvents) {
            val epoch = try {
                parseManualFmt.parse("${m.dateIso} ${m.startTime}")?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (m.dateIso >= todayIso) {
                all.add(
                    CalendarEventItem(
                        id = m.id,
                        title = m.title,
                        dateIso = m.dateIso,
                        startTime = m.startTime,
                        endTime = m.endTime,
                        location = m.location,
                        isToday = m.dateIso == todayIso,
                        startEpochMs = epoch
                    )
                )
            }
        }

        return all.sortedWith(compareBy({ it.dateIso }, { it.startTime }, { it.startEpochMs })).take(12)
    }
}
