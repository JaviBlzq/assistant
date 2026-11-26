package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.HarbizWorkout
import com.javiblzq.assistant.data.HrZoneConfig
import com.javiblzq.assistant.data.StravaActivitySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaAnalyzerTest {

    @Test
    fun computesRunMetricsAndHrZones() {
        val act = StravaActivitySummary(
            id = 101L,
            name = "Rodaje Suave",
            sportType = "Run",
            startDateLocalIso = "2026-11-24T08:00:00",
            dateIso = "2026-11-24",
            distanceM = 6000.0,
            movingTimeS = 1800,
            elapsedTimeS = 1800,
            elevationGainM = 45.0,
            averageSpeedMps = 3.33,
            averageHeartRate = 150.0,
            maxHeartRate = 170.0,
            averageCadenceSpm = 172.0
        )

        // Build 60 samples (every 30s = 1800s) covering HR 132..168 bpm and speed 2.8..3.8 m/s
        val samples = (0..60).map { idx ->
            val frac = idx / 60.0
            StreamSample(
                elapsedTimeS = idx * 30.0,
                speedMps = 2.8 + frac * 1.0,
                heartRateBpm = 132.0 + frac * 36.0,
                moving = true
            )
        }

        val analysis = StravaAnalyzer.analyzeRun(act, samples, HrZoneConfig.defaults())
        assertNotNull(analysis.averageHrBpm)
        assertTrue(analysis.averageHrBpm in 145..155)
        assertNotNull(analysis.aerobicEfficiency)
        assertNotNull(analysis.paceAt150BpmFormatted)
        assertEquals(5, analysis.zones.size)
        val totalPct = analysis.zones.sumOf { it.percentage }
        assertTrue(totalPct in 99.0..101.0)
    }

    @Test
    fun buildsMondayToSundayWeekStripMergingStravaAndHarbiz() {
        val run = StravaActivitySummary(
            id = 1L,
            name = "Run",
            sportType = "Run",
            startDateLocalIso = "2026-11-23T07:30:00",
            dateIso = "2026-11-23", // Monday
            distanceM = 8400.0,
            movingTimeS = 2400,
            elapsedTimeS = 2400,
            elevationGainM = 50.0,
            averageSpeedMps = 3.5,
            averageHeartRate = 148.0,
            maxHeartRate = 165.0,
            averageCadenceSpm = 174.0
        )
        val gym = HarbizWorkout(
            id = "w1",
            title = "Push 1",
            scheduledDateIso = "2026-11-24", // Tuesday
            scheduledAtMs = 1795500000000L,
            updatedAtMs = 1795500000000L,
            completed = true,
            routineName = "Push 1",
            exercises = emptyList(),
            workingSetsCount = 21,
            volumeLoadKg = 4200.0
        )

        // Tuesday 2026-11-24 12:00 UTC -> 1795521600000L
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Europe/Madrid")
        }
        val tuesdayMs = fmt.parse("2026-11-24 12:00")!!.time

        val summary = StravaAnalyzer.buildWeekSummary(listOf(run, run.copy(id = 2L)), listOf(gym), "Europe/Madrid", tuesdayMs)
        assertEquals(3, summary.completedSessionsCount)
        assertEquals(6, summary.targetSessionsCount)
        assertEquals(7, summary.days.size)
        assertTrue(summary.days[0].hasRun)       // L
        assertTrue(summary.days[1].hasStrength)  // M
        assertTrue(summary.days[1].isToday)
        assertEquals(2, summary.trainingDays)
        assertEquals(2, summary.runningCount)
        assertEquals(16.8, summary.runningDistanceKm, 0.05)
        assertEquals(1, summary.strengthCount)
        assertEquals(21, summary.completedSets)
    }
}
