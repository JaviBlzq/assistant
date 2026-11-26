package com.javiblzq.assistant.data

import org.json.JSONArray
import org.json.JSONObject

data class HrZoneConfig(
    val id: String,
    val label: String,
    val minBpm: Int,
    val maxBpm: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("minBpm", minBpm)
        .put("maxBpm", maxBpm)

    companion object {
        fun defaults(): List<HrZoneConfig> = listOf(
            HrZoneConfig("Z1", "Z1 REC", 95, 125),
            HrZoneConfig("Z2", "Z2 AER", 125, 145),
            HrZoneConfig("Z3", "Z3 TMP", 145, 162),
            HrZoneConfig("Z4", "Z4 THR", 162, 176),
            HrZoneConfig("Z5", "Z5 VO2", 176, 195)
        )

        fun fromJsonArray(arr: JSONArray?): List<HrZoneConfig> {
            if (arr == null || arr.length() == 0) return defaults()
            val out = mutableListOf<HrZoneConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    HrZoneConfig(
                        id = o.optString("id", "Z${i + 1}"),
                        label = o.optString("label", "Z${i + 1}"),
                        minBpm = o.optInt("minBpm", 100),
                        maxBpm = o.optInt("maxBpm", 140)
                    )
                )
            }
            return if (out.isEmpty()) defaults() else out
        }
    }
}

data class QuickTask(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val priority: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val dueDate: String = "",
    val urgency: String = if (priority) URGENCY_HIGH else URGENCY_LOW
) {
    val normalizedUrgency: String
        get() = normalizeUrgency(urgency, priority)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("done", done)
        .put("priority", priority || normalizedUrgency == URGENCY_HIGH)
        .put("createdAtMs", createdAtMs)
        .put("dueDate", dueDate)
        .put("urgency", normalizedUrgency)

    companion object {
        const val URGENCY_HIGH = "HIGH"
        const val URGENCY_MED = "MED"
        const val URGENCY_LOW = "LOW"

        fun normalizeUrgency(raw: String?, priorityFallback: Boolean = false): String {
            return when (raw?.trim()?.uppercase(java.util.Locale.US).orEmpty()) {
                "HIGH", "ALTA", "URGENTE", "ROJO", "RED", "3", "!" -> URGENCY_HIGH
                "MED", "MEDIUM", "MEDIA", "NORMAL", "AMBAR", "ÁMBAR", "AMBER", "2" -> URGENCY_MED
                "LOW", "BAJA", "1" -> URGENCY_LOW
                else -> if (priorityFallback) URGENCY_HIGH else URGENCY_LOW
            }
        }

        fun fromJson(o: JSONObject): QuickTask {
            val priority = o.optBoolean("priority", false)
            val urgency = normalizeUrgency(o.optString("urgency", ""), priority)
            return QuickTask(
                id = o.optString("id", System.nanoTime().toString()),
                text = o.optString("text", ""),
                done = o.optBoolean("done", false),
                priority = priority || urgency == URGENCY_HIGH,
                createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                dueDate = o.optString("dueDate", "").trim(),
                urgency = urgency
            )
        }
    }
}

data class ManualCalendarEvent(
    val id: String,
    val title: String,
    val dateIso: String, // YYYY-MM-DD
    val startTime: String, // HH:MM
    val endTime: String = "",
    val location: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("dateIso", dateIso)
        .put("startTime", startTime)
        .put("endTime", endTime)
        .put("location", location)

    companion object {
        fun fromJson(o: JSONObject): ManualCalendarEvent = ManualCalendarEvent(
            id = o.optString("id", System.nanoTime().toString()),
            title = o.optString("title", ""),
            dateIso = o.optString("dateIso", ""),
            startTime = o.optString("startTime", "09:00"),
            endTime = o.optString("endTime", ""),
            location = o.optString("location", "")
        )
    }
}

data class AssistantConfig(
    val deviceName: String = "ASSISTANT-01",
    val timezone: String = "Europe/Madrid",
    val dayStartHour: Int = 9,
    val dayEndHour: Int = 18,
    val wakeTimeoutSeconds: Int = 60,
    val nightWakeTimeoutSeconds: Int = 45,
    val lowPowerBrightness: Float = 0.06f,
    val activeBrightness: Float = 0.88f,

    val homeLabel: String = "CASA",
    val homeLat: Double = 40.416775,
    val homeLon: Double = -3.703790,
    val workLabel: String = "TRABAJO",
    val workLat: Double = 40.446000,
    val workLon: Double = -3.692000,
    val gymLabel: String = "GYM",
    val gymLat: Double = 40.415260,
    val gymLon: Double = -3.684500,
    val trafficProvider: String = "osrm", // osrm | tomtom | google
    val trafficIntervalMinutes: Int = 10,
    val googleMapsApiKey: String = "",
    val tomtomApiKey: String = "",

    val harbizEmail: String = "",
    val harbizToken: String = "",
    val harbizAccountId: String = "",
    val harbizTokenExpiresAt: String = "",

    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val stravaAccessToken: String = "",
    val stravaRefreshToken: String = "",
    val stravaExpiresAt: Long = 0L,
    val stravaAthleteName: String = "",
    val hrZones: List<HrZoneConfig> = HrZoneConfig.defaults(),

    val semyraApiUrl: String = "",
    val icalUrls: List<String> = emptyList(),
    val manualEvents: List<ManualCalendarEvent> = emptyList(),
    val tasks: List<QuickTask> = emptyList(),
    val pinnedNote: String = "",
    val syncIntervalMinutes: Int = 15,
    val weeklyTrainingGoal: Int = 6,
    val telegramBotToken: String = "",
    val telegramAllowedChatId: String = "",
    val telegramLastUpdateId: Long = 0L,
    val marketPrimarySymbol: String = DEFAULT_MARKET_PRIMARY,
    val marketWatchlistSymbols: List<String> = DEFAULT_MARKET_WATCHLIST,
    val semyraServiceUrls: List<String> = emptyList(),
    val tapoEmail: String = "",
    val tapoPassword: String = "",
    val smartBulbs: List<SmartBulbDevice> = emptyList()
) {
    fun toJson(redactSecrets: Boolean = false): JSONObject {
        val obj = JSONObject()
        obj.put("deviceName", deviceName)
        obj.put("timezone", timezone)
        obj.put("dayStartHour", dayStartHour)
        obj.put("dayEndHour", dayEndHour)
        obj.put("wakeTimeoutSeconds", wakeTimeoutSeconds)
        obj.put("nightWakeTimeoutSeconds", nightWakeTimeoutSeconds)
        obj.put("lowPowerBrightness", lowPowerBrightness.toDouble())
        obj.put("activeBrightness", activeBrightness.toDouble())

        obj.put("homeLabel", homeLabel)
        obj.put("homeLat", homeLat)
        obj.put("homeLon", homeLon)
        obj.put("workLabel", workLabel)
        obj.put("workLat", workLat)
        obj.put("workLon", workLon)
        obj.put("gymLabel", gymLabel)
        obj.put("gymLat", gymLat)
        obj.put("gymLon", gymLon)
        obj.put("trafficProvider", trafficProvider)
        obj.put("trafficIntervalMinutes", trafficIntervalMinutes)
        obj.put("googleMapsApiKey", if (redactSecrets && googleMapsApiKey.isNotEmpty()) "••••••" else googleMapsApiKey)
        obj.put("tomtomApiKey", if (redactSecrets && tomtomApiKey.isNotEmpty()) "••••••" else tomtomApiKey)

        obj.put("harbizEmail", harbizEmail)
        obj.put("harbizConnected", harbizToken.isNotEmpty())
        if (!redactSecrets) {
            obj.put("harbizToken", harbizToken)
            obj.put("harbizAccountId", harbizAccountId)
        }
        obj.put("harbizTokenExpiresAt", harbizTokenExpiresAt)

        obj.put("stravaClientId", stravaClientId)
        obj.put("stravaClientSecret", if (redactSecrets && stravaClientSecret.isNotEmpty()) "••••••" else stravaClientSecret)
        obj.put("stravaConnected", stravaRefreshToken.isNotEmpty() || stravaAccessToken.isNotEmpty())
        if (!redactSecrets) {
            obj.put("stravaAccessToken", stravaAccessToken)
            obj.put("stravaRefreshToken", stravaRefreshToken)
        }
        obj.put("stravaExpiresAt", stravaExpiresAt)
        obj.put("stravaAthleteName", stravaAthleteName)
        obj.put("hrZones", JSONArray().apply { hrZones.forEach { put(it.toJson()) } })

        obj.put("semyraApiUrl", semyraApiUrl)
        obj.put("icalUrls", JSONArray(icalUrls))
        obj.put("manualEvents", JSONArray().apply { manualEvents.forEach { put(it.toJson()) } })
        obj.put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
        obj.put("pinnedNote", pinnedNote)
        obj.put("syncIntervalMinutes", syncIntervalMinutes)
        obj.put("weeklyTrainingGoal", weeklyTrainingGoal)
        obj.put("telegramBotToken", if (redactSecrets && telegramBotToken.isNotEmpty()) "••••••" else telegramBotToken)
        obj.put("telegramConnected", telegramBotToken.isNotEmpty())
        obj.put("telegramAllowedChatId", telegramAllowedChatId)
        obj.put("telegramLastUpdateId", telegramLastUpdateId)
        obj.put("marketPrimarySymbol", marketPrimarySymbol)
        obj.put("marketWatchlistSymbols", JSONArray(marketWatchlistSymbols))
        obj.put("semyraServiceUrls", JSONArray(semyraServiceUrls))
        obj.put("tapoEmail", tapoEmail)
        obj.put("tapoPassword", if (redactSecrets && tapoPassword.isNotEmpty()) "••••••" else tapoPassword)
        obj.put("smartBulbs", JSONArray(smartBulbs.map { it.toJson(redactSecrets) }))
        return obj
    }

    companion object {
        const val DEFAULT_MARKET_PRIMARY = "IWDA.AS"
        val DEFAULT_MARKET_WATCHLIST = listOf("AAPL", "MSFT", "NVDA")

        internal fun readStringList(array: JSONArray): List<String> =
            (0 until array.length()).mapNotNull { array.opt(it) as? String }
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun fromJson(o: JSONObject): AssistantConfig {
            val icals = mutableListOf<String>()
            val icalArr = o.optJSONArray("icalUrls")
            if (icalArr != null) {
                for (i in 0 until icalArr.length()) {
                    val s = icalArr.optString(i, "").trim()
                    if (s.isNotEmpty()) icals.add(s)
                }
            }
            val events = mutableListOf<ManualCalendarEvent>()
            val evArr = o.optJSONArray("manualEvents")
            if (evArr != null) {
                for (i in 0 until evArr.length()) {
                    evArr.optJSONObject(i)?.let { events.add(ManualCalendarEvent.fromJson(it)) }
                }
            }
            val tasksList = mutableListOf<QuickTask>()
            val tArr = o.optJSONArray("tasks")
            if (tArr != null) {
                for (i in 0 until tArr.length()) {
                    tArr.optJSONObject(i)?.let { tasksList.add(QuickTask.fromJson(it)) }
                }
            }
            return AssistantConfig(
                deviceName = o.optString("deviceName", "ASSISTANT-01"),
                timezone = o.optString("timezone", "Europe/Madrid"),
                dayStartHour = o.optInt("dayStartHour", 9).coerceIn(0, 23),
                dayEndHour = o.optInt("dayEndHour", 18).coerceIn(0, 23),
                wakeTimeoutSeconds = o.optInt("wakeTimeoutSeconds", 60).coerceIn(10, 600),
                nightWakeTimeoutSeconds = o.optInt("nightWakeTimeoutSeconds", 45).coerceIn(10, 300),
                lowPowerBrightness = o.optDouble("lowPowerBrightness", 0.06).toFloat().coerceIn(0.01f, 0.5f),
                activeBrightness = o.optDouble("activeBrightness", 0.88).toFloat().coerceIn(0.2f, 1.0f),

                homeLabel = o.optString("homeLabel", "CASA"),
                homeLat = o.optDouble("homeLat", 40.416775),
                homeLon = o.optDouble("homeLon", -3.703790),
                workLabel = o.optString("workLabel", "TRABAJO"),
                workLat = o.optDouble("workLat", 40.446000),
                workLon = o.optDouble("workLon", -3.692000),
                gymLabel = o.optString("gymLabel", "GYM"),
                gymLat = o.optDouble("gymLat", 40.415260),
                gymLon = o.optDouble("gymLon", -3.684500),
                trafficProvider = o.optString("trafficProvider", "osrm"),
                trafficIntervalMinutes = o.optInt("trafficIntervalMinutes", 10).coerceIn(1, 60),
                googleMapsApiKey = o.optString("googleMapsApiKey", ""),
                tomtomApiKey = o.optString("tomtomApiKey", ""),

                harbizEmail = o.optString("harbizEmail", ""),
                harbizToken = o.optString("harbizToken", ""),
                harbizAccountId = o.optString("harbizAccountId", ""),
                harbizTokenExpiresAt = o.optString("harbizTokenExpiresAt", ""),

                stravaClientId = o.optString("stravaClientId", ""),
                stravaClientSecret = o.optString("stravaClientSecret", ""),
                stravaAccessToken = o.optString("stravaAccessToken", ""),
                stravaRefreshToken = o.optString("stravaRefreshToken", ""),
                stravaExpiresAt = o.optLong("stravaExpiresAt", 0L),
                stravaAthleteName = o.optString("stravaAthleteName", ""),
                hrZones = HrZoneConfig.fromJsonArray(o.optJSONArray("hrZones")),

                semyraApiUrl = o.optString("semyraApiUrl", ""),
                icalUrls = icals,
                manualEvents = events,
                tasks = tasksList,
                pinnedNote = o.optString("pinnedNote", ""),
                syncIntervalMinutes = o.optInt("syncIntervalMinutes", 15).coerceIn(5, 120),
                weeklyTrainingGoal = o.optInt("weeklyTrainingGoal", 6).coerceIn(1, 21),
                telegramBotToken = o.optString("telegramBotToken", ""),
                telegramAllowedChatId = o.optString("telegramAllowedChatId", ""),
                telegramLastUpdateId = o.optLong("telegramLastUpdateId", 0L),
                marketPrimarySymbol = o.optString("marketPrimarySymbol", DEFAULT_MARKET_PRIMARY)
                    .ifBlank { DEFAULT_MARKET_PRIMARY },
                marketWatchlistSymbols = o.optJSONArray("marketWatchlistSymbols")
                    ?.let(::readStringList) ?: DEFAULT_MARKET_WATCHLIST,
                semyraServiceUrls = o.optJSONArray("semyraServiceUrls")?.let(::readStringList).orEmpty(),
                tapoEmail = o.optString("tapoEmail", ""),
                tapoPassword = o.optString("tapoPassword", ""),
                smartBulbs = o.optJSONArray("smartBulbs")?.let { bulbs ->
                    (0 until bulbs.length()).mapNotNull { bulbs.optJSONObject(it)?.let(SmartBulbDevice::fromJson) }
                }.orEmpty()
            )
        }
    }
}

data class HourlyWeatherItem(
    val hourLabel: String,
    val tempC: Double,
    val precipProb: Int,
    val weatherCode: Int
)

data class DailyWeatherItem(
    val dayLabel: String,
    val minC: Double,
    val maxC: Double,
    val precipProbMax: Int,
    val weatherCode: Int
)

data class WeatherSnapshot(
    val currentTempC: Double,
    val apparentTempC: Double,
    val windKmh: Double,
    val weatherCode: Int,
    val conditionText: String,
    val hourly: List<HourlyWeatherItem>,
    val daily: List<DailyWeatherItem>,
    val fetchedAtMs: Long,
    val today24h: List<HourlyWeatherItem> = emptyList(),
    val todayMinC: Double = currentTempC,
    val todayMaxC: Double = currentTempC,
    val minHourIndex: Int = 0,
    val maxHourIndex: Int = 0
)

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val congestionLevel: Int = 0 // 0=normal, 1=moderate, 2=heavy red traffic
)

data class CommuteRoute(
    val targetName: String,
    val originLabel: String,
    val durationMin: Int,
    val distanceKm: Double,
    val trafficDelayMin: Int,
    val providerLabel: String,
    val statusLabel: String,
    val fetchedAtMs: Long,
    val polylinePoints: List<RoutePoint> = emptyList()
)

data class HarbizSet(
    val setNumber: Int,
    val reps: Int?,
    val weightKg: Double?,
    val durationS: Int?,
    val setType: String?,
    val completed: Boolean
) {
    val e1rmKg: Double?
        get() {
            val w = weightKg ?: return null
            val r = reps ?: return null
            if (w <= 0.0 || r < 1 || r > 12) return null
            return kotlin.math.round((w * (1.0 + r / 30.0)) * 10.0) / 10.0
        }
}

data class HarbizExercise(
    val order: Int,
    val sourceId: String,
    val displayName: String,
    val catalogId: String?,
    val target: String?,
    val rest: String?,
    val supersetGroup: Int?,
    val completed: Boolean,
    val sets: List<HarbizSet>
) {
    val bestSetText: String
        get() {
            val working = sets.filter { it.setType != "warmup" }
            val candidates = if (working.isNotEmpty()) working else sets
            val best = candidates.maxWithOrNull(
                compareBy<HarbizSet>({ it.weightKg ?: -1.0 }, { it.reps ?: -1 })
            )
            if (best == null) return target ?: "—"
            val w = best.weightKg
            val r = best.reps
            return when {
                w != null && w > 0 && r != null -> "${trimWeight(w)} KG x $r"
                r != null -> "BW x $r"
                best.durationS != null -> "${best.durationS}s"
                else -> target ?: "${sets.size} SETS"
            }
        }

    val topE1rmKg: Double?
        get() = sets.filter { it.completed && it.setType != "warmup" }
            .mapNotNull { it.e1rmKg }
            .maxOrNull()

    companion object {
        fun trimWeight(w: Double): String {
            return if (w % 1.0 == 0.0) w.toInt().toString() else String.format(java.util.Locale.US, "%.1f", w)
        }
    }
}

data class HarbizWorkout(
    val id: String,
    val title: String,
    val scheduledDateIso: String, // YYYY-MM-DD
    val scheduledAtMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
    val routineName: String?,
    val exercises: List<HarbizExercise>,
    val workingSetsCount: Int,
    val volumeLoadKg: Double,
    val eventType: String = "workout",
    val isCardioOrRun: Boolean = false,
    val summarySubtitle: String = ""
)

data class StravaActivitySummary(
    val id: Long,
    val name: String,
    val sportType: String,
    val startDateLocalIso: String, // YYYY-MM-DDTHH:MM:SS
    val dateIso: String, // YYYY-MM-DD
    val distanceM: Double,
    val movingTimeS: Int,
    val elapsedTimeS: Int,
    val elevationGainM: Double,
    val averageSpeedMps: Double,
    val averageHeartRate: Double?,
    val maxHeartRate: Double?,
    val averageCadenceSpm: Double?
) {
    val isRun: Boolean
        get() = sportType in setOf("Run", "TrailRun", "VirtualRun")
    val isStrength: Boolean
        get() = sportType in setOf("WeightTraining", "Crossfit", "Workout")
    val paceSecondsPerKm: Double?
        get() = if (distanceM > 50 && movingTimeS > 0) movingTimeS / (distanceM / 1000.0) else null
}

data class HrZoneDistribution(
    val id: String,
    val label: String,
    val minBpm: Int,
    val maxBpm: Int,
    val durationS: Double,
    val percentage: Double
)

data class StravaRunAnalysis(
    val activity: StravaActivitySummary,
    val averagePaceFormatted: String,
    val averageHrBpm: Int?,
    val aerobicEfficiency: Double?,
    val paceAt150BpmFormatted: String?,
    val cardiacDriftPercent: Double?,
    val zones: List<HrZoneDistribution>
)

data class WeekDayCell(
    val dateIso: String,
    val dayShort: String, // L M X J V S D
    val isToday: Boolean,
    val hasRun: Boolean,
    val hasStrength: Boolean,
    val hasOther: Boolean
)

data class TodayTrainingItem(
    val type: String, // "STRENGTH" or "RUN"
    val title: String,
    val subtitle: String // Empty "" for STRENGTH; short description like "40' Z2" for RUN
)

data class SemyraWeekSummary(
    val days: List<WeekDayCell>,
    val trainingDays: Int,
    val runningCount: Int,
    val runningDistanceKm: Double,
    val runningDurationS: Int,
    val strengthCount: Int,
    val completedSets: Int,
    val completedSessionsCount: Int = runningCount + strengthCount,
    val targetSessionsCount: Int = 6
)

data class CalendarEventItem(
    val id: String,
    val title: String,
    val dateIso: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val isToday: Boolean,
    val startEpochMs: Long
)

data class MarketQuoteItem(
    val symbol: String,
    val displayName: String,
    val price: Double,
    val changePercent1d: Double,
    val changePercent1m: Double,
    val currency: String,
    val sparklineCloses: List<Double>,
    val fetchedAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("symbol", symbol)
        .put("displayName", displayName)
        .put("price", price)
        .put("changePercent1d", changePercent1d)
        .put("changePercent1m", changePercent1m)
        .put("currency", currency)
        .put("sparklineCloses", JSONArray(sparklineCloses))
        .put("fetchedAtMs", fetchedAtMs)
}

data class MarketSnapshot(
    val primaryQuote: MarketQuoteItem?,
    val watchlist: List<MarketQuoteItem> = emptyList(),
    val statusText: String = "PENDIENTE",
    val fetchedAtMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("primaryQuote", primaryQuote?.toJson() ?: JSONObject.NULL)
        .put("watchlist", JSONArray(watchlist.map { it.toJson() }))
        .put("statusText", statusText)
        .put("fetchedAtMs", fetchedAtMs)
}

data class ServiceHealthItem(
    val id: String,
    val name: String,
    val online: Boolean,
    val latencyMs: Int,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("online", online)
        .put("latencyMs", latencyMs)
        .put("detail", detail)
        .put("statusLabel", if (online) "EN LÍNEA" else "SIN CONEXIÓN")
}

data class SmartBulbDevice(
    val id: String,
    val name: String,
    val ipAddress: String = "",
    val isOn: Boolean = false,
    val brightness: Int = 100,
    val webhookOnUrl: String = "",
    val webhookOffUrl: String = "",
    val statusText: String = "LISTO"
) {
    fun toJson(redactSecrets: Boolean = false): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("ipAddress", ipAddress)
        .put("isOn", isOn)
        .put("brightness", brightness)
        .put("webhookOnUrl", redactUrl(webhookOnUrl, redactSecrets))
        .put("webhookOffUrl", redactUrl(webhookOffUrl, redactSecrets))
        .put("statusText", statusText)

    private fun redactUrl(url: String, redactSecrets: Boolean): String =
        if (redactSecrets && url.isNotEmpty()) "••••••" else url

    companion object {
        fun fromJson(o: JSONObject): SmartBulbDevice = SmartBulbDevice(
            id = o.getString("id"),
            name = o.getString("name"),
            ipAddress = o.optString("ipAddress", ""),
            isOn = o.optBoolean("isOn", false),
            brightness = o.optInt("brightness", 100).coerceIn(1, 100),
            webhookOnUrl = o.optString("webhookOnUrl", ""),
            webhookOffUrl = o.optString("webhookOffUrl", ""),
            statusText = o.optString("statusText", "LISTO")
        )
    }
}

data class DeviceLocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float = 0f,
    val provider: String = "",
    val timestampMs: Long = 0L
)

data class HardwareGnssSatellite(
    val prn: Int,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val snr: Float,
    val usedInFix: Boolean
)

data class SatelliteOverheadItem(
    val id: String,
    val name: String,
    val constellation: String,
    val agency: String,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val altitudeKm: Int,
    val snr: Float = 0f,
    val usedInFix: Boolean = false,
    val hardwareLocked: Boolean = false,
    val launchDate: String = "DESCONOCIDO",
    val inclinationDeg: Float = Float.NaN,
    val operator: String = "DESCONOCIDO"
) {
    val isGnss: Boolean get() = constellation == "GALILEO" || constellation == "GPS"
}

data class SatelliteSkySnapshot(
    val satellites: List<SatelliteOverheadItem> = emptyList(),
    val euCount: Int = 0,
    val usCount: Int = 0,
    val almanacSource: String = "ALMANAQUE LOCAL",
    val calculatedAtMs: Long = 0L,
    val hardwareFixActive: Boolean = false,
    val hardwareSatCount: Int = 0,
    val observerLat: Double = 0.0,
    val observerLon: Double = 0.0
) {
    fun withGnssVisibility(showGnss: Boolean): SatelliteSkySnapshot {
        if (showGnss) return this
        val visible = satellites.filterNot { it.isGnss }
        return copy(satellites = visible, euCount = visible.count { it.agency == "EU" },
            usCount = visible.count { it.agency == "US" }, hardwareSatCount = 0)
    }
}

data class DashboardSnapshot(
    val weather: WeatherSnapshot? = null,
    val workCommute: CommuteRoute? = null,
    val gymCommute: CommuteRoute? = null,
    val harbizWorkouts: List<HarbizWorkout> = emptyList(),
    val todayWorkout: HarbizWorkout? = null,
    val latestCompletedWorkout: HarbizWorkout? = null,
    val stravaActivities: List<StravaActivitySummary> = emptyList(),
    val latestRunAnalysis: StravaRunAnalysis? = null,
    val weekSummary: SemyraWeekSummary? = null,
    val todayTrainings: List<TodayTrainingItem> = emptyList(),
    val calendarEvents: List<CalendarEventItem> = emptyList(),
    val lastSyncMs: Long = 0L,
    val harbizStatus: String = "NO CONFIGURADO",
    val stravaStatus: String = "NO CONFIGURADO",
    val weatherStatus: String = "PENDIENTE",
    val commuteStatus: String = "PENDIENTE",
    val commuteSyncing: Boolean = false,
    val telegramStatus: String = "SIN TOKEN",
    val market: MarketSnapshot? = null,
    val serviceHealth: List<ServiceHealthItem> = emptyList(),
    val smartBulbsStatus: String = "SIN DISPOSITIVOS",
    val satellites: SatelliteSkySnapshot? = null,
    val deviceLocation: DeviceLocationFix? = null
)
