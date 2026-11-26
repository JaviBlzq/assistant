package com.javiblzq.assistant.power

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.providers.CalendarIcsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CalendarAndPowerTest {

    private fun madridTimeMs(dateTime: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Madrid")
        }
        return fmt.parse(dateTime)!!.time
    }

    @Test
    fun touchWakesBothWindowsAndExpiresAtTimeout() {
        val cfg = AssistantConfig(dayStartHour = 9, dayEndHour = 18, wakeTimeoutSeconds = 60, nightWakeTimeoutSeconds = 45)
        for ((time, idle, timeout) in listOf(
            Triple("2026-11-24 09:00", DisplayMode.LOW_POWER_AOD, 60),
            Triple("2026-11-24 17:59", DisplayMode.LOW_POWER_AOD, 60),
            Triple("2026-11-24 18:00", DisplayMode.NIGHT_BLACKOUT, 45),
            Triple("2026-11-24 08:59", DisplayMode.NIGHT_BLACKOUT, 45)
        )) {
            val now = madridTimeMs(time)
            assertEquals(idle, PowerStatePolicy.evaluate(cfg, 0L, now).mode)
            assertEquals(DisplayMode.FULL_DASHBOARD, PowerStatePolicy.evaluate(cfg, now - 1000L, now).mode)
            assertEquals(idle, PowerStatePolicy.evaluate(cfg, now - timeout * 1000L, now).mode)
        }
    }

    @Test
    fun parsesIcsEventsIncludingWeeklyRecurrence() {
        val now = madridTimeMs("2026-11-24 08:00")
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:ev-weekly
            DTSTART:20261117T100000
            DTEND:20261117T110000
            RRULE:FREQ=WEEKLY
            SUMMARY:Daily Sync
            LOCATION:Lab
            END:VEVENT
            BEGIN:VEVENT
            UID:ev-tomorrow
            DTSTART:20261125T173000
            SUMMARY:Fisio
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = CalendarIcsParser.parseIcs(ics, "Europe/Madrid", now)
        assertEquals(2, events.size)
        assertEquals("Daily Sync", events[0].title)
        assertTrue(events[0].isToday)
        assertEquals("10:00", events[0].startTime)
        assertEquals("Fisio", events[1].title)
        assertFalse(events[1].isToday)
    }
}
