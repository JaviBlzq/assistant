package com.javiblzq.assistant.power

import com.javiblzq.assistant.data.AssistantConfig
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

enum class DisplayMode {
    LOW_POWER_AOD,   // 09:00 - 18:00 idle: dimmed, minimal info, touch wake enabled
    NIGHT_BLACKOUT,  // 18:00 - 09:00 idle: pure black screen, 0% backlight, touch-only wake
    FULL_DASHBOARD   // Active interaction window after touch wake
}

data class PowerEvaluation(
    val mode: DisplayMode,
    val isDayWindow: Boolean,
    val targetBrightness: Float
)

object PowerStatePolicy {

    fun isWithinDayWindow(
        dayStartHour: Int,
        dayEndHour: Int,
        hourOfDay: Int
    ): Boolean {
        return if (dayStartHour < dayEndHour) {
            hourOfDay in dayStartHour until dayEndHour
        } else if (dayStartHour > dayEndHour) {
            hourOfDay >= dayStartHour || hourOfDay < dayEndHour
        } else {
            true
        }
    }

    fun evaluate(
        config: AssistantConfig,
        lastTouchWakeMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): PowerEvaluation {
        val tz = TimeZone.getTimeZone(config.timezone)
        val cal = Calendar.getInstance(tz).apply { time = Date(nowMs) }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isDay = isWithinDayWindow(config.dayStartHour, config.dayEndHour, hour)

        val timeoutMs = if (isDay) config.wakeTimeoutSeconds * 1000L else config.nightWakeTimeoutSeconds * 1000L
        val elapsedMs = nowMs - lastTouchWakeMs
        val touchActive = lastTouchWakeMs > 0L && elapsedMs in 0L until timeoutMs

        val mode = when {
            touchActive -> DisplayMode.FULL_DASHBOARD
            isDay -> DisplayMode.LOW_POWER_AOD
            else -> DisplayMode.NIGHT_BLACKOUT
        }

        val brightness = when (mode) {
            DisplayMode.FULL_DASHBOARD -> config.activeBrightness
            DisplayMode.LOW_POWER_AOD -> config.lowPowerBrightness
            DisplayMode.NIGHT_BLACKOUT -> 0.0f
        }

        return PowerEvaluation(
            mode = mode,
            isDayWindow = isDay,
            targetBrightness = brightness
        )
    }

    fun evaluate(
        config: AssistantConfig,
        lastTouchWakeMs: Long,
        @Suppress("UNUSED_PARAMETER") ignoredMotionMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): PowerEvaluation = evaluate(config, lastTouchWakeMs, nowMs)
}
