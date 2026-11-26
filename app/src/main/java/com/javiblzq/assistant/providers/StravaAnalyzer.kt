package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.HarbizWorkout
import com.javiblzq.assistant.data.HrZoneConfig
import com.javiblzq.assistant.data.HrZoneDistribution
import com.javiblzq.assistant.data.SemyraWeekSummary
import com.javiblzq.assistant.data.StravaActivitySummary
import com.javiblzq.assistant.data.StravaRunAnalysis
import com.javiblzq.assistant.data.WeekDayCell
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

data class StreamSample(
    val elapsedTimeS: Double,
    val speedMps: Double?,
    val heartRateBpm: Double?,
    val moving: Boolean
)

data class MovingInterval(
    val durationS: Double,
    val speedMps: Double,
    val paceSPerKm: Double,
    val heartRateBpm: Double?
)

object StravaAnalyzer {
    private const val MIN_SPEED_MPS = 0.5
    private const val MAX_SPEED_MPS = 8.5
    private const val MIN_HR_BPM = 40.0
    private const val MAX_HR_BPM = 240.0
    private const val MAX_GAP_S = 60.0
    private const val MIN_METRIC_DURATION_S = 120.0
    private const val MIN_METRIC_INTERVALS = 12
    private const val MIN_ESTIMATOR_DURATION_S = 300.0
    private const val MIN_ESTIMATOR_INTERVALS = 30
    private const val MIN_REGRESSION_BINS = 4
    private const val MIN_HR_SPAN_BPM = 15.0
    private const val MIN_R2 = 0.35
    private const val MIN_DRIFT_DURATION_S = 1800.0
    private const val MIN_HR_COVERAGE = 0.8

    fun formatPace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0.0) return "—"
        val total = secondsPerKm.roundToInt()
        val mins = total / 60
        val secs = total % 60
        return String.format(Locale.US, "%d:%02d/km", mins, secs)
    }

    fun extractMovingIntervals(rawSamples: List<StreamSample>): List<MovingInterval> {
        val sorted = rawSamples
            .filter { it.elapsedTimeS.isFinite() && it.elapsedTimeS >= 0.0 }
            .sortedBy { it.elapsedTimeS }
        val unique = ArrayList<StreamSample>(sorted.size)
        for (s in sorted) {
            if (unique.isEmpty() || unique.last().elapsedTimeS != s.elapsedTimeS) {
                unique.add(s)
            }
        }
        val intervals = ArrayList<MovingInterval>()
        for (i in 0 until unique.size - 1) {
            val cur = unique[i]
            val dt = unique[i + 1].elapsedTimeS - cur.elapsedTimeS
            if (dt <= 0.0 || dt > MAX_GAP_S || !cur.moving) continue
            val spd = validSpeed(unique, i) ?: continue
            val hr = cur.heartRateBpm?.takeIf { it.isFinite() && it in MIN_HR_BPM..MAX_HR_BPM }
            intervals.add(
                MovingInterval(
                    durationS = dt,
                    speedMps = spd,
                    paceSPerKm = 1000.0 / spd,
                    heartRateBpm = hr
                )
            )
        }
        return intervals
    }

    private fun validSpeed(samples: List<StreamSample>, index: Int): Double? {
        val spd = samples[index].speedMps ?: return null
        if (!spd.isFinite() || spd < MIN_SPEED_MPS || spd > MAX_SPEED_MPS) return null
        val prev = samples.getOrNull(index - 1)?.speedMps
        val next = samples.getOrNull(index + 1)?.speedMps
        if (prev != null && next != null && prev > 0.0 && next > 0.0) {
            val neighborMax = max(prev, next)
            if (spd > neighborMax * 1.8 && spd - neighborMax > 2.0) return null
        }
        return spd
    }

    fun analyzeRun(
        activity: StravaActivitySummary,
        samples: List<StreamSample>,
        hrZones: List<HrZoneConfig>
    ): StravaRunAnalysis {
        val intervals = extractMovingIntervals(samples)
        if (intervals.size < MIN_METRIC_INTERVALS || intervals.sumOf { it.durationS } < MIN_METRIC_DURATION_S) {
            return StravaRunAnalysis(
                activity = activity,
                averagePaceFormatted = formatPace(activity.paceSecondsPerKm),
                averageHrBpm = activity.averageHeartRate?.roundToInt(),
                aerobicEfficiency = null,
                paceAt150BpmFormatted = null,
                cardiacDriftPercent = null,
                zones = emptyList()
            )
        }

        val totalDur = intervals.sumOf { it.durationS }
        val totalDist = intervals.sumOf { it.speedMps * it.durationS }
        val avgPaceS = if (totalDist > 0.0) totalDur / (totalDist / 1000.0) else activity.paceSecondsPerKm

        val hrIntervals = intervals.filter { it.heartRateBpm != null }
        val hrDur = hrIntervals.sumOf { it.durationS }
        val hasHrCoverage = hrIntervals.size >= MIN_METRIC_INTERVALS && hrDur >= MIN_METRIC_DURATION_S && (hrDur / totalDur) >= MIN_HR_COVERAGE

        val avgHr = if (hasHrCoverage) {
            (hrIntervals.sumOf { it.heartRateBpm!! * it.durationS } / hrDur).roundToInt()
        } else {
            activity.averageHeartRate?.roundToInt()
        }

        val eff = if (hasHrCoverage) {
            val rawEff = hrIntervals.sumOf { (it.speedMps / it.heartRateBpm!!) * it.durationS } / hrDur
            round(rawEff * 100000.0) / 100000.0
        } else null

        val paceAt150 = estimatePaceAt150Bpm(intervals, totalDur)
        val drift = computeCardiacDrift(intervals, totalDur)
        val zones = computeZones(hrIntervals, hrZones)

        return StravaRunAnalysis(
            activity = activity,
            averagePaceFormatted = formatPace(avgPaceS),
            averageHrBpm = avgHr,
            aerobicEfficiency = eff,
            paceAt150BpmFormatted = paceAt150?.let { formatPace(it) },
            cardiacDriftPercent = drift,
            zones = zones
        )
    }

    private fun estimatePaceAt150Bpm(intervals: List<MovingInterval>, totalDur: Double): Double? {
        val hrIntervals = intervals.filter { it.heartRateBpm != null }
        val hrDur = hrIntervals.sumOf { it.durationS }
        if (hrIntervals.size < MIN_ESTIMATOR_INTERVALS || hrDur < MIN_ESTIMATOR_DURATION_S || hrDur / totalDur < MIN_HR_COVERAGE) {
            return null
        }
        val minObs = hrIntervals.minOf { it.heartRateBpm!! }
        val maxObs = hrIntervals.maxOf { it.heartRateBpm!! }
        if (150.0 < minObs || 150.0 > maxObs || maxObs - minObs < MIN_HR_SPAN_BPM) return null

        data class Bin(var w: Double = 0.0, var wx: Double = 0.0, var wy: Double = 0.0)
        val binsMap = HashMap<Int, Bin>()
        for (item in hrIntervals) {
            val hr = item.heartRateBpm!!
            val key = (hr / 5.0).roundToInt() * 5
            val b = binsMap.getOrPut(key) { Bin() }
            b.w += item.durationS
            b.wx += hr * item.durationS
            b.wy += item.paceSPerKm * item.durationS
        }
        if (binsMap.size < MIN_REGRESSION_BINS) return null

        val bins = binsMap.values.map { Triple(it.wx / it.w, it.wy / it.w, it.w) }.sortedBy { it.first }
        if (bins.last().first - bins.first().first < MIN_HR_SPAN_BPM) return null

        val totalW = bins.sumOf { it.third }
        val meanX = bins.sumOf { it.first * it.third } / totalW
        val meanY = bins.sumOf { it.second * it.third } / totalW
        val denom = bins.sumOf { it.third * (it.first - meanX) * (it.first - meanX) }
        if (denom <= 0.0) return null

        val slope = bins.sumOf { it.third * (it.first - meanX) * (it.second - meanY) } / denom
        if (slope >= 0.0) return null
        val intercept = meanY - slope * meanX

        val totalVar = bins.sumOf { it.third * (it.second - meanY) * (it.second - meanY) }
        if (totalVar <= 0.0) return null
        val resVar = bins.sumOf {
            val pred = intercept + slope * it.first
            it.third * (it.second - pred) * (it.second - pred)
        }
        val r2 = 1.0 - resVar / totalVar
        if (!r2.isFinite() || r2 < MIN_R2) return null

        val est = intercept + slope * 150.0
        val minPace = 1000.0 / MAX_SPEED_MPS
        val maxPace = 1000.0 / MIN_SPEED_MPS
        return if (est in minPace..maxPace) est else null
    }

    private fun computeCardiacDrift(intervals: List<MovingInterval>, totalDur: Double): Double? {
        if (totalDur < MIN_DRIFT_DURATION_S) return null
        val half = totalDur * 0.5
        val firstHalf = sliceWindow(intervals, 0.0, half)
        val secondHalf = sliceWindow(intervals, half, totalDur)
        val eff1 = windowEfficiency(firstHalf) ?: return null
        val eff2 = windowEfficiency(secondHalf) ?: return null
        if (eff1 <= 0.0) return null
        return round(((eff1 - eff2) / eff1) * 1000.0) / 10.0
    }

    private fun sliceWindow(intervals: List<MovingInterval>, startS: Double, endS: Double): List<MovingInterval> {
        val out = mutableListOf<MovingInterval>()
        var elapsed = 0.0
        for (item in intervals) {
            val s = elapsed
            elapsed += item.durationS
            val overlap = max(0.0, min(elapsed, endS) - max(s, startS))
            if (overlap > 0.0) out.add(item.copy(durationS = overlap))
        }
        return out
    }

    private fun windowEfficiency(window: List<MovingInterval>): Double? {
        val total = window.sumOf { it.durationS }
        val hr = window.filter { it.heartRateBpm != null }
        val hrDur = hr.sumOf { it.durationS }
        if (total <= 0.0 || hr.size < MIN_METRIC_INTERVALS || hrDur < MIN_METRIC_DURATION_S || hrDur / total < MIN_HR_COVERAGE) {
            return null
        }
        return hr.sumOf { (it.speedMps / it.heartRateBpm!!) * it.durationS } / hrDur
    }

    private fun computeZones(hrIntervals: List<MovingInterval>, zones: List<HrZoneConfig>): List<HrZoneDistribution> {
        if (zones.isEmpty() || hrIntervals.isEmpty()) return emptyList()
        val durations = zones.map { z ->
            hrIntervals.filter { it.heartRateBpm!! >= z.minBpm && it.heartRateBpm < z.maxBpm }
                .sumOf { it.durationS }
        }
        val total = durations.sum()
        if (total <= 0.0) return emptyList()
        return zones.mapIndexed { idx, z ->
            HrZoneDistribution(
                id = z.id,
                label = z.label,
                minBpm = z.minBpm,
                maxBpm = z.maxBpm,
                durationS = durations[idx],
                percentage = round((durations[idx] / total) * 1000.0) / 10.0
            )
        }
    }

    fun buildWeekSummary(
        stravaActivities: List<StravaActivitySummary>,
        harbizWorkouts: List<HarbizWorkout>,
        timezoneId: String,
        nowMs: Long = System.currentTimeMillis(),
        weeklyGoal: Int = 6
    ): SemyraWeekSummary {
        val tz = TimeZone.getTimeZone(timezoneId)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val cal = Calendar.getInstance(tz).apply { time = Date(nowMs) }
        val todayIso = fmt.format(cal.time)

        // Move calendar to Monday of current week
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dow == Calendar.SUNDAY) 6 else (dow - Calendar.MONDAY)
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)

        val dayShorts = listOf("L", "M", "X", "J", "V", "S", "D")
        val weekDates = ArrayList<String>(7)
        for (i in 0 until 7) {
            weekDates.add(fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val weekDateSet = weekDates.toSet()

        val weekStrava = stravaActivities.filter { it.dateIso in weekDateSet }
        val weekHarbiz = harbizWorkouts.filter { it.completed && it.scheduledDateIso in weekDateSet }

        val cells = weekDates.mapIndexed { idx, dateIso ->
            val dayStrava = weekStrava.filter { it.dateIso == dateIso }
            val dayHarbiz = weekHarbiz.filter { it.scheduledDateIso == dateIso }
            val hasRun = dayStrava.any { it.isRun } || dayHarbiz.any { it.isCardioOrRun }
            val hasStrength = dayHarbiz.any { !it.isCardioOrRun } || dayStrava.any { it.isStrength }
            val hasOther = dayStrava.any { !it.isRun && !it.isStrength }
            WeekDayCell(
                dateIso = dateIso,
                dayShort = dayShorts[idx],
                isToday = dateIso == todayIso,
                hasRun = hasRun,
                hasStrength = hasStrength,
                hasOther = hasOther
            )
        }

        val trainingDays = cells.count { it.hasRun || it.hasStrength || it.hasOther }
        val runs = weekStrava.filter { it.isRun }
        val stravaRunDays = runs.map { it.dateIso }.toSet()
        val runCount = runs.size + weekHarbiz.count { it.isCardioOrRun && it.scheduledDateIso !in stravaRunDays }
        val harbizStrength = weekHarbiz.filterNot { it.isCardioOrRun }
        val stravaStrengthOnlyDays = weekStrava.filter { it.isStrength }
            .map { it.dateIso }
            .toSet() - harbizStrength.map { it.scheduledDateIso }.toSet()
        val strengthCount = harbizStrength.size + stravaStrengthOnlyDays.size

        return SemyraWeekSummary(
            days = cells,
            trainingDays = trainingDays,
            runningCount = runCount,
            runningDistanceKm = round((runs.sumOf { it.distanceM } / 1000.0) * 10.0) / 10.0,
            runningDurationS = runs.sumOf { it.movingTimeS },
            strengthCount = strengthCount,
            completedSets = harbizStrength.sumOf { it.workingSetsCount },
            completedSessionsCount = runCount + strengthCount,
            targetSessionsCount = weeklyGoal
        )
    }
}
