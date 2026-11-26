package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.DailyWeatherItem
import com.javiblzq.assistant.data.HourlyWeatherItem
import com.javiblzq.assistant.data.WeatherSnapshot
import com.javiblzq.assistant.net.HttpClient
import org.json.JSONObject
import java.util.Locale

class WeatherProvider {
    fun fetch(lat: Double, lon: Double, @Suppress("UNUSED_PARAMETER") timezone: String): Pair<WeatherSnapshot?, String> {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=celsius&forecast_days=5&timezone=auto",
            lat, lon
        )
        val resp = HttpClient.get(url)
        if (!resp.isSuccess) {
            return null to "CLIMA ERR (${resp.code})"
        }
        return try {
            parseResponse(resp.body) to "OK"
        } catch (e: Exception) {
            null to "CLIMA PARSE ERR"
        }
    }

    internal fun parseResponse(
        body: String,
        nowMs: Long = System.currentTimeMillis()
    ): WeatherSnapshot {
        val root = JSONObject(body)
        val cur = root.getJSONObject("current")
        val curTime = cur.optString("time", "")
        val curTempC = cur.optDouble("temperature_2m", 0.0)
        val code = cur.optInt("weather_code", 0)

        val hourlyObj = root.getJSONObject("hourly")
        val hTimes = hourlyObj.getJSONArray("time")
        val hTemps = hourlyObj.getJSONArray("temperature_2m")
        val hProbs = hourlyObj.optJSONArray("precipitation_probability")
        val hCodes = hourlyObj.getJSONArray("weather_code")

        var startIdx = 0
        if (curTime.length >= 13) {
            val prefix = curTime.substring(0, 13)
            for (i in 0 until hTimes.length()) {
                if (hTimes.optString(i).startsWith(prefix)) {
                    startIdx = i + 1
                    break
                }
            }
        }
        val hourlyList = mutableListOf<HourlyWeatherItem>()
        for (offset in 0 until 6) {
            val idx = startIdx + offset
            if (idx >= hTimes.length()) break
            val tStr = hTimes.optString(idx, "")
            val label = if (tStr.length >= 16) tStr.substring(11, 16) else tStr
            hourlyList.add(
                HourlyWeatherItem(
                    hourLabel = label,
                    tempC = finiteOr(hTemps.optDouble(idx, curTempC), curTempC),
                    precipProb = hProbs?.optInt(idx, 0) ?: 0,
                    weatherCode = hCodes.optInt(idx, 0)
                )
            )
        }

        // Extract the 24 hourly entries (00..23) of the current day
        var todayStartIdx = 0
        if (curTime.length >= 10) {
            val datePrefix = curTime.substring(0, 10)
            for (i in 0 until hTimes.length()) {
                if (hTimes.optString(i).startsWith(datePrefix)) {
                    todayStartIdx = i
                    break
                }
            }
        }
        val today24h = mutableListOf<HourlyWeatherItem>()
        for (offset in 0 until 24) {
            val idx = todayStartIdx + offset
            if (idx >= hTimes.length()) break
            val tStr = hTimes.optString(idx, "")
            val hourLabel = if (tStr.length >= 13) {
                tStr.substring(11, 13)
            } else {
                String.format(Locale.US, "%02d", offset)
            }
            today24h.add(
                HourlyWeatherItem(
                    hourLabel = hourLabel,
                    tempC = finiteOr(hTemps.optDouble(idx, curTempC), curTempC),
                    precipProb = hProbs?.optInt(idx, 0) ?: 0,
                    weatherCode = hCodes.optInt(idx, 0)
                )
            )
        }

        var todayMinC = curTempC
        var todayMaxC = curTempC
        var minHourIndex = 0
        var maxHourIndex = 0
        if (today24h.isNotEmpty()) {
            todayMinC = today24h[0].tempC
            todayMaxC = today24h[0].tempC
            for (i in 1 until today24h.size) {
                val t = today24h[i].tempC
                if (t < todayMinC) {
                    todayMinC = t
                    minHourIndex = i
                }
                if (t > todayMaxC) {
                    todayMaxC = t
                    maxHourIndex = i
                }
            }
        }

        val dailyObj = root.getJSONObject("daily")
        val dDates = dailyObj.getJSONArray("time")
        val dMax = dailyObj.getJSONArray("temperature_2m_max")
        val dMin = dailyObj.getJSONArray("temperature_2m_min")
        val dCodes = dailyObj.getJSONArray("weather_code")
        val dProbs = dailyObj.optJSONArray("precipitation_probability_max")
        val dailyList = mutableListOf<DailyWeatherItem>()
        for (i in 0 until dDates.length().coerceAtMost(4)) {
            val dateIso = dDates.optString(i, "")
            val shortLabel = if (i == 0) "HOY" else dateIso.takeLast(5)
            dailyList.add(
                DailyWeatherItem(
                    dayLabel = shortLabel,
                    minC = dMin.optDouble(i, 0.0),
                    maxC = dMax.optDouble(i, 0.0),
                    precipProbMax = dProbs?.optInt(i, 0) ?: 0,
                    weatherCode = dCodes.optInt(i, 0)
                )
            )
        }

        return WeatherSnapshot(
            currentTempC = curTempC,
            apparentTempC = cur.optDouble("apparent_temperature", 0.0),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            weatherCode = code,
            conditionText = describeWmoCode(code),
            hourly = hourlyList,
            daily = dailyList,
            fetchedAtMs = nowMs,
            today24h = today24h,
            todayMinC = todayMinC,
            todayMaxC = todayMaxC,
            minHourIndex = minHourIndex,
            maxHourIndex = maxHourIndex
        )
    }

    private fun finiteOr(value: Double, fallback: Double): Double =
        if (value.isFinite()) value else fallback

    companion object {
        fun describeWmoCode(code: Int): String = when (code) {
            0 -> "DESPEJADO"
            1, 2 -> "POCO NUBOSO"
            3 -> "NUBLADO"
            45, 48 -> "NIEBLA"
            51, 53, 55 -> "LLOVIZNA"
            61, 63, 65 -> "LLUVIA"
            66, 67 -> "LLUVIA HELADA"
            71, 73, 75, 77 -> "NIEVE"
            80, 81, 82 -> "CHUBASCOS"
            85, 86 -> "NIEVE FUERTE"
            95, 96, 99 -> "TORMENTA"
            else -> "VARIABLE"
        }
    }
}
