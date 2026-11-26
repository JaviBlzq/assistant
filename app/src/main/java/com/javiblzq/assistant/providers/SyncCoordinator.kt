package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.CommuteRoute
import com.javiblzq.assistant.data.HarbizWorkout
import com.javiblzq.assistant.data.HrZoneConfig
import com.javiblzq.assistant.data.LocalStore
import com.javiblzq.assistant.data.ServiceHealthItem
import com.javiblzq.assistant.data.SmartBulbDevice
import com.javiblzq.assistant.data.StravaActivitySummary
import com.javiblzq.assistant.data.StravaRunAnalysis
import com.javiblzq.assistant.data.TodayTrainingItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SyncCoordinator(
    private val store: LocalStore,
    private val commuteProvider: CommuteProvider = CommuteProvider(),
    private val deviceGnssProvider: DeviceGnssProvider? = null,
    private val satelliteProvider: SatelliteTrackerProvider = SatelliteTrackerProvider(
        File(store.filesDir, SatelliteTrackerProvider.CACHE_FILE_NAME)
    )
) {
    enum class CommuteSyncTarget { ALL, WORK, GYM }

    private val executor = Executors.newSingleThreadExecutor()
    private val commuteExecutor = Executors.newSingleThreadExecutor()
    private val weatherProvider = WeatherProvider()
    private val harbizClient = HarbizClient()
    private val stravaClient = StravaClient()
    private val calendarProvider = CalendarIcsProvider()
    private val marketProvider = MarketProvider()
    private val serviceMonitor = SemyraServiceMonitor()
    private val tapo = TapoSmartHomeClient()
    private val bulbLock = Any()
    private val commuteLock = Any()
    private val syncQueued = AtomicBoolean(false)
    private var pendingCommuteTarget: CommuteSyncTarget? = null

    fun triggerSyncAsync() {
        if (!syncQueued.compareAndSet(false, true)) return
        executor.execute {
            syncQueued.set(false)
            runSyncBlocking()
        }
    }

    fun triggerCommuteSyncAsync(target: CommuteSyncTarget = CommuteSyncTarget.ALL) {
        val shouldSubmit = synchronized(commuteLock) {
            val prev = pendingCommuteTarget
            if (prev == null) {
                pendingCommuteTarget = target
                true
            } else {
                if (prev != target) {
                    pendingCommuteTarget = CommuteSyncTarget.ALL
                }
                false
            }
        }
        store.updateSnapshot { if (it.commuteSyncing) it else it.copy(commuteSyncing = true) }
        if (!shouldSubmit) return
        commuteExecutor.execute {
            while (true) {
                val nextTarget = synchronized(commuteLock) {
                    val t = pendingCommuteTarget
                    pendingCommuteTarget = null
                    t
                } ?: break
                runCommuteSyncBlocking(nextTarget)
            }
        }
    }

    fun refreshSatellitesFast(nowMs: Long = System.currentTimeMillis()) {
        val cfg = store.config
        val origin = deviceGnssProvider?.resolveOrigin(cfg)
            ?: DeviceGnssProvider.resolveOrigin(cfg, store.snapshot.deviceLocation)
        val hwSats = deviceGnssProvider?.currentHardwareSatellites().orEmpty()
        val sky = satelliteProvider.computeOverhead(
            observerLatDeg = origin.lat,
            observerLonDeg = origin.lon,
            nowMs = nowMs,
            hardwareSatellites = hwSats,
            hardwareFixActive = origin.isHardwareFix
        )
        val locFix = deviceGnssProvider?.currentLocation()
        store.updateSnapshot { prev ->
            prev.copy(
                satellites = sky,
                deviceLocation = locFix ?: prev.deviceLocation
            )
        }
    }

    fun runCommuteSyncBlocking(target: CommuteSyncTarget = CommuteSyncTarget.ALL) {
        val cfg = store.config
        val routeCfg = deviceGnssProvider?.applyOriginToConfig(cfg)
            ?: DeviceGnssProvider.applyOriginToConfig(cfg, store.snapshot.deviceLocation)
        store.updateSnapshot { if (it.commuteSyncing) it else it.copy(commuteSyncing = true) }
        var workRoute: CommuteRoute? = null
        var gymRoute: CommuteRoute? = null
        try {
            if (target == CommuteSyncTarget.ALL || target == CommuteSyncTarget.WORK) {
                workRoute = commuteProvider.fetchRoute(routeCfg, cfg.workLabel, cfg.workLat, cfg.workLon)
            }
            if (target == CommuteSyncTarget.ALL || target == CommuteSyncTarget.GYM) {
                gymRoute = commuteProvider.fetchRoute(routeCfg, cfg.gymLabel, cfg.gymLat, cfg.gymLon)
            }
        } finally {
            val hasMorePending = synchronized(commuteLock) { pendingCommuteTarget != null }
            val locFix = deviceGnssProvider?.currentLocation()
            store.updateSnapshot { prev ->
                val nextWork = workRoute ?: prev.workCommute
                val nextGym = gymRoute ?: prev.gymCommute
                val nextStatus = (workRoute ?: gymRoute ?: nextWork ?: nextGym)?.statusLabel ?: prev.commuteStatus
                prev.copy(
                    workCommute = nextWork,
                    gymCommute = nextGym,
                    commuteStatus = nextStatus,
                    commuteSyncing = hasMorePending,
                    deviceLocation = locFix ?: prev.deviceLocation
                )
            }
        }
    }

    fun toggleSmartBulbAsync(id: String) {
        executor.execute {
            setSmartBulb(id, null, null)
        }
    }

    fun runSyncBlocking() {
        val cfg = store.config
        val tz = TimeZone.getTimeZone(cfg.timezone)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date())

        val weatherStart = System.nanoTime()
        val (weather, weatherMsg) = weatherProvider.fetch(cfg.homeLat, cfg.homeLon, cfg.timezone)
        val weatherHealth = ServiceHealthItem(
            "open-meteo", "OPEN-METEO", weather != null, SemyraServiceMonitor.elapsedMs(weatherStart), weatherMsg
        )
        val routeCfg = deviceGnssProvider?.applyOriginToConfig(cfg)
            ?: DeviceGnssProvider.applyOriginToConfig(cfg, store.snapshot.deviceLocation)
        val routeStart = System.nanoTime()
        val workRoute = commuteProvider.fetchRoute(routeCfg, cfg.workLabel, cfg.workLat, cfg.workLon)
        val routeHealth = when (workRoute.providerLabel) {
            "TOMTOM LIVE" -> ServiceHealthItem("tomtom", "TOMTOM", true, SemyraServiceMonitor.elapsedMs(routeStart), workRoute.statusLabel)
            "OSRM CAR" -> ServiceHealthItem("osrm", "OSRM", true, SemyraServiceMonitor.elapsedMs(routeStart), workRoute.statusLabel)
            else -> null
        }
        val gymRoute = commuteProvider.fetchRoute(routeCfg, cfg.gymLabel, cfg.gymLat, cfg.gymLon)
        val calEvents = calendarProvider.fetchAll(cfg)

        var harbizWorkouts: List<HarbizWorkout> = store.snapshot.harbizWorkouts
        var harbizStatus: String? = null
        var harbizHealth: ServiceHealthItem? = null
        if (cfg.harbizToken.isNotBlank()) {
            val start = System.nanoTime()
            val res = harbizClient.syncWithToken(cfg.harbizToken, cfg.timezone)
            harbizHealth = ServiceHealthItem("harbiz", "HARBIZ DDP", res.ok, SemyraServiceMonitor.elapsedMs(start), res.message)
            if (res.ok) {
                harbizWorkouts = res.workouts
            }
            harbizStatus = res.message
            res.session?.let { s ->
                store.updateConfig {
                    if (it.harbizToken == cfg.harbizToken) {
                        it.copy(
                            harbizToken = s.token,
                            harbizAccountId = s.accountId,
                            harbizTokenExpiresAt = s.expiresAt
                        )
                    } else it
                }
            }
        }

        var stravaActs: List<StravaActivitySummary> = store.snapshot.stravaActivities
        var stravaStatus: String? = null
        var stravaHealth: ServiceHealthItem? = null
        var runAnalysis = store.snapshot.latestRunAnalysis
        val stravaConfig = store.config
        if (stravaConfig.stravaRefreshToken.isNotBlank() || stravaConfig.stravaAccessToken.isNotBlank()) {
            val start = System.nanoTime()
            val res = stravaClient.sync(stravaConfig)
            stravaHealth = ServiceHealthItem("strava", "STRAVA API", res.ok, SemyraServiceMonitor.elapsedMs(start), res.statusText)
            res.updatedTokens?.let { tok ->
                store.updateConfig {
                    if (it.stravaClientId == stravaConfig.stravaClientId &&
                        it.stravaClientSecret == stravaConfig.stravaClientSecret &&
                        it.stravaRefreshToken == stravaConfig.stravaRefreshToken &&
                        it.stravaAccessToken == stravaConfig.stravaAccessToken
                    ) {
                        it.copy(
                            stravaAccessToken = tok.accessToken,
                            stravaRefreshToken = tok.refreshToken,
                            stravaExpiresAt = tok.expiresAt,
                            stravaAthleteName = tok.athleteName.ifBlank { it.stravaAthleteName }
                        )
                    } else it
                }
            }
            if (res.ok) {
                stravaActs = res.activities
                runAnalysis = res.latestRunAnalysis
                stravaStatus = res.statusText
            } else {
                stravaStatus = res.statusText
            }
        }

        val latestCfg = store.config
        val scheduled = agendaWorkouts(todayIso, harbizWorkouts)
        val todayWorkout = scheduled.firstOrNull { !it.isCardioOrRun } ?: scheduled.firstOrNull()
        val latestCompleted = harbizWorkouts.firstOrNull { it.completed && !it.isCardioOrRun }
            ?: harbizWorkouts.firstOrNull { it.completed }
        val weekSummary = StravaAnalyzer.buildWeekSummary(
            stravaActivities = stravaActs,
            harbizWorkouts = harbizWorkouts,
            timezoneId = latestCfg.timezone,
            weeklyGoal = latestCfg.weeklyTrainingGoal
        )
        val todayTrainings = buildTodayTrainings(
            todayIso = todayIso,
            harbizWorkouts = harbizWorkouts,
            todayWorkout = todayWorkout,
            stravaActivities = stravaActs,
            latestRunAnalysis = runAnalysis,
            config = latestCfg
        )
        val market = marketProvider.fetch(latestCfg)
        val serviceHealth = serviceMonitor.checkAll(
            latestCfg, harbizHealth, stravaHealth, store.snapshot.telegramStatus, weatherHealth, routeHealth
        )
        satelliteProvider.refreshAlmanacIfNeeded()
        val satOrigin = deviceGnssProvider?.resolveOrigin(latestCfg)
            ?: DeviceGnssProvider.resolveOrigin(latestCfg, store.snapshot.deviceLocation)
        val satSnapshot = satelliteProvider.computeOverhead(
            observerLatDeg = satOrigin.lat,
            observerLonDeg = satOrigin.lon,
            nowMs = System.currentTimeMillis(),
            hardwareSatellites = deviceGnssProvider?.currentHardwareSatellites().orEmpty(),
            hardwareFixActive = satOrigin.isHardwareFix
        )
        val locFix = deviceGnssProvider?.currentLocation()
        refreshSmartBulbs()

        store.updateSnapshot { prev ->
            val nextTelegramStatus = when {
                latestCfg.telegramBotToken.isBlank() -> "SIN TOKEN"
                prev.telegramStatus == "SIN TOKEN" -> "CONECTANDO"
                else -> prev.telegramStatus
            }
            val nextWork = newestRoute(workRoute, prev.workCommute)
            val nextGym = newestRoute(gymRoute, prev.gymCommute)
            prev.copy(
                weather = weather ?: prev.weather,
                weatherStatus = weatherMsg,
                workCommute = nextWork,
                gymCommute = nextGym,
                commuteStatus = nextWork.statusLabel,
                harbizWorkouts = harbizWorkouts,
                todayWorkout = todayWorkout,
                latestCompletedWorkout = latestCompleted,
                stravaActivities = stravaActs,
                latestRunAnalysis = runAnalysis,
                weekSummary = weekSummary,
                todayTrainings = todayTrainings,
                calendarEvents = calEvents,
                lastSyncMs = System.currentTimeMillis(),
                harbizStatus = harbizStatus ?: prev.harbizStatus,
                stravaStatus = stravaStatus ?: prev.stravaStatus,
                telegramStatus = nextTelegramStatus,
                market = market,
                serviceHealth = serviceHealth,
                smartBulbsStatus = smartBulbsStatus(store.config.smartBulbs),
                satellites = satSnapshot,
                deviceLocation = locFix ?: prev.deviceLocation
            )
        }
    }

    internal fun setSmartBulb(id: String, on: Boolean?, brightness: Int?): Pair<Boolean, String>? =
        synchronized(bulbLock) {
            val cfg = store.config
            val bulb = cfg.smartBulbs.firstOrNull { it.id == id } ?: return@synchronized null
            val target = on ?: !bulb.isOn
            val (ok, status) = tapo.setBulbState(cfg, bulb, target, brightness)
            val webhookUsed = if (target) bulb.webhookOnUrl.isNotBlank() else bulb.webhookOffUrl.isNotBlank()
            val updated = bulb.copy(
                isOn = if (ok) target else bulb.isOn,
                brightness = if (ok && target && !webhookUsed && brightness != null) brightness else bulb.brightness,
                statusText = status
            )
            store.updateConfig { current ->
                current.copy(smartBulbs = current.smartBulbs.map { if (it == bulb) updated else it })
            }
            store.updateSnapshot { it.copy(smartBulbsStatus = smartBulbsStatus(store.config.smartBulbs)) }
            ok to status
        }

    private fun refreshSmartBulbs() {
        for (id in store.config.smartBulbs.map { it.id }) {
            synchronized(bulbLock) {
                val cfg = store.config
                val bulb = cfg.smartBulbs.firstOrNull { it.id == id } ?: return@synchronized
                if (bulb.ipAddress.isBlank() || cfg.tapoEmail.isBlank() || cfg.tapoPassword.isBlank()) return@synchronized
                val (state, status) = tapo.readBulbState(cfg, bulb)
                val updated = bulb.copy(
                    isOn = state?.isOn ?: bulb.isOn,
                    brightness = state?.brightness ?: bulb.brightness,
                    statusText = status
                )
                if (updated != bulb) store.updateConfig { current ->
                    if (current.tapoEmail != cfg.tapoEmail || current.tapoPassword != cfg.tapoPassword) current
                    else current.copy(smartBulbs = current.smartBulbs.map { if (it == bulb) updated else it })
                }
            }
        }
    }

    companion object {
        private fun newestRoute(candidate: CommuteRoute, existing: CommuteRoute?): CommuteRoute {
            if (existing == null) return candidate
            return if (existing.fetchedAtMs > candidate.fetchedAtMs) existing else candidate
        }

        internal fun smartBulbsStatus(bulbs: List<SmartBulbDevice>): String {
            if (bulbs.isEmpty()) return "SIN DISPOSITIVOS"
            val errors = bulbs.count { it.statusText !in setOf("LISTO", "TAPO OK", "WEBHOOK OK") }
            return if (errors == 0) "${bulbs.size} DISPOSITIVOS" else "$errors SIN CONEXIÓN"
        }

        private fun agendaWorkouts(todayIso: String, workouts: List<HarbizWorkout>): List<HarbizWorkout> {
            val today = workouts.filter { it.scheduledDateIso == todayIso }
            if (today.isNotEmpty()) return today.sortedBy { it.scheduledAtMs }
            val utc = TimeZone.getTimeZone("UTC")
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
            val cutoff = Calendar.getInstance(utc).apply {
                time = fmt.parse(todayIso) ?: return emptyList()
                add(Calendar.DATE, 7)
            }
            val endIso = fmt.format(cutoff.time)
            val next = workouts.filter { !it.completed && it.scheduledDateIso > todayIso && it.scheduledDateIso <= endIso }
                .minByOrNull { it.scheduledAtMs } ?: return emptyList()
            return listOf(next)
        }

        internal fun buildTodayTrainings(
            todayIso: String,
            harbizWorkouts: List<HarbizWorkout>,
            todayWorkout: HarbizWorkout?,
            stravaActivities: List<StravaActivitySummary>,
            latestRunAnalysis: StravaRunAnalysis?,
            config: AssistantConfig
        ): List<TodayTrainingItem> {
            val items = mutableListOf<TodayTrainingItem>()

            val todayRuns = stravaActivities.filter { it.isRun && it.dateIso == todayIso }
            val unmatchedStrava = todayRuns.toMutableList()
            val workouts = if (todayWorkout == null || harbizWorkouts.any { it.id == todayWorkout.id }) {
                harbizWorkouts
            } else harbizWorkouts + todayWorkout
            for (w in agendaWorkouts(todayIso, workouts)) {
                if (w.isCardioOrRun && w.scheduledDateIso == todayIso) {
                    val match = unmatchedStrava.indexOfFirst { it.name.trim().equals(w.title.trim(), ignoreCase = true) }
                    if (match >= 0) {
                        unmatchedStrava.removeAt(match)
                        continue
                    }
                }
                val title = w.title.uppercase(Locale.US)
                items.add(
                    TodayTrainingItem(
                        type = if (w.isCardioOrRun) "RUN" else "STRENGTH",
                        title = if (w.scheduledDateIso == todayIso) title else "${w.scheduledDateIso} · $title",
                        subtitle = if (w.isCardioOrRun) w.summarySubtitle else ""
                    )
                )
            }

            for (act in todayRuns) {
                val analysis = latestRunAnalysis?.takeIf { it.activity.id == act.id }
                items.add(
                    TodayTrainingItem(
                        type = "RUN",
                        title = act.name.uppercase(Locale.US),
                        subtitle = formatRunSubtitle(act, analysis, config.hrZones)
                    )
                )
            }

            return items
        }

        internal fun formatRunSubtitle(
            act: StravaActivitySummary,
            analysis: StravaRunAnalysis?,
            hrZones: List<HrZoneConfig>
        ): String {
            val mins = if (act.movingTimeS > 0) (act.movingTimeS / 60).coerceAtLeast(1) else 0
            val durPart = "${mins}'"
            val dominantZone = analysis?.zones
                ?.filter { it.durationS > 0.0 }
                ?.maxByOrNull { it.durationS }
                ?.id
                ?: resolveZoneId(act.averageHeartRate, hrZones)

            val detail = dominantZone
                ?: StravaAnalyzer.formatPace(act.paceSecondsPerKm).takeIf { it != "—" }
                ?: if (act.distanceM > 0.0) {
                    String.format(Locale.US, "%.1fKM", act.distanceM / 1000.0)
                } else null

            return if (detail != null) "$durPart $detail" else durPart
        }

        private fun resolveZoneId(avgHr: Double?, zones: List<HrZoneConfig>): String? {
            val hr = avgHr ?: return null
            if (!hr.isFinite() || zones.isEmpty()) return null
            val sorted = zones.sortedBy { it.minBpm }
            sorted.firstOrNull { hr >= it.minBpm && hr < it.maxBpm }?.let { return it.id }
            return when {
                hr < sorted.first().minBpm -> sorted.first().id
                else -> sorted.last().id
            }
        }
    }
}
